package uk.ac.bris.cs.scotlandyard.ui.ai;


import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.atlassian.fugue.Pair;
import uk.ac.bris.cs.scotlandyard.model.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

/*
* ISMCTS — Information Set Monte Carlo Tree Search
* Burak Alican Kilinc
*
* --- Coordination architecture ---
*
* Three layers, each independent so none can destabilise the others:
*
* LAYER 1 — coordinatedLeafEval (quality signal for the tree)
*   Evaluates every leaf using ALL detectives: normalised sum of 1/(1+d_i)
*   for closeness, PLUS 1-step AND 2-step escape-route coverage.
*   The 2-step component rewards positions that cut off MrX two moves out.
*   All five detectives appear in the evaluation, so the tree learns team value,
*   not just "how close is the nearest detective."
*
* LAYER 2 — greedyDetectiveMove (rollout heuristic)
*   Pure MrX-chasing (get close) + anti-clustering (penalise landing near a
*   detective already committed this round) + escape-coverage bonus.
*   Role targets are intentionally NOT used here: if the target estimate is
*   wrong, a 60 % weight on it would steer the detective to the wrong side of
*   the map.  Coordination in rollouts comes from the clustering penalty and
*   coverage bonus, which are always correct regardless of MrX's position.
*
* LAYER 3 — move priors at the root (exploration bias)
*   Before the ISMCTS loop, every root child is pre-seeded with
*     prior = 0.5 × expected-escape-coverage + 0.5 × target-proximity
*   where target-proximity uses MyAi's role-assignment targets (if available).
*   This biases initial exploration toward formation moves without forcing
*   the rollout to commit to a potentially wrong target.  The prior decays
*   with visits (see IsmctsNode.getUCB) so the search stays data-driven.
*/
public class Ismcts {
    private static final Random rng = new Random();
    public record anchorData(Optional<Integer> loc, List<ScotlandYard.Ticket> ticketSinceAnchor){}

    // ------------------------------------------------------------------
    // Information-set construction (unchanged)
    // ------------------------------------------------------------------

    public static anchorData getDet(Board board){
        ImmutableList<LogEntry> log = board.getMrXTravelLog();
        List<ScotlandYard.Ticket> ticketSinceAnchor = new ArrayList<>();
        for (int i = log.size()-1; i >= 0; i--) {
            LogEntry entry = log.get(i);
            if(entry.location().isPresent()){
                java.util.Collections.reverse(ticketSinceAnchor);
                return new anchorData(entry.location(), ticketSinceAnchor);
            } else {
                ticketSinceAnchor.add(entry.ticket());
            }
        }
        java.util.Collections.reverse(ticketSinceAnchor);
        return new anchorData(Optional.empty(), ticketSinceAnchor);
    }

    public static List<Integer> forwardPass(Board board){
        anchorData data = getDet(board);
        Set<Integer> possibleLocs = new HashSet<>();
        List<Integer> detectiveLocs = getDetectiveLocs(board);

        if (data.loc().isPresent()) { possibleLocs.add(data.loc().get()); }
        else {
            possibleLocs.addAll(ScotlandYard.MRX_LOCATIONS);
            possibleLocs.removeAll(detectiveLocs);
        }

        for (ScotlandYard.Ticket ticket : data.ticketSinceAnchor()) {
            Set<Integer> nextPossibleLocs = new HashSet<>();
            for (int source : possibleLocs) {
                for (int destination : board.getSetup().graph.adjacentNodes(source)) {
                    if (detectiveLocs.contains(destination)) continue;
                    var transports = board.getSetup().graph.edgeValueOrDefault(
                            source, destination, ImmutableSet.of());
                    boolean canTravel = false;
                    if (ticket == ScotlandYard.Ticket.SECRET && !transports.isEmpty()) {
                        canTravel = true;
                    } else {
                        for (ScotlandYard.Transport t : transports) {
                            if (t.requiredTicket() == ticket) { canTravel = true; break; }
                        }
                    }
                    if (canTravel) nextPossibleLocs.add(destination);
                }
            }
            possibleLocs = nextPossibleLocs;
        }
        return new ArrayList<>(possibleLocs);
    }

    private static List<Integer> getDetectiveLocs(Board board){
        List<Integer> locs = new ArrayList<>();
        for (Piece p : board.getPlayers())
            if (p.isDetective())
                board.getDetectiveLocation((Piece.Detective) p).ifPresent(locs::add);
        return locs;
    }

    private static int getMoveDestination(Move move) {
        return move.accept(new Move.Visitor<Integer>() {
            @Override public Integer visit(Move.SingleMove m) { return m.destination; }
            @Override public Integer visit(Move.DoubleMove m) { return m.destination2; }
        });
    }

    // ------------------------------------------------------------------
    // LAYER 1: coordinatedLeafEval — 1-step + 2-step escape coverage
    // ------------------------------------------------------------------
    private static double coordinatedLeafEval(Board.GameState state, int mrXLoc) {
        var graph = state.getSetup().graph;
        int maxNode = dijkstraAlgorithm.maxNode(graph);
        int[] distFromMrX = dijkstraAlgorithm.mergeDist(graph, mrXLoc, maxNode);

        // Closeness: normalised sum of 1/(1+d) over all detectives
        double sumInverse = 0.0;
        int detCount = 0;
        for (Piece p : state.getPlayers()) {
            if (p.isDetective()) {
                int detLoc = state.getDetectiveLocation((Piece.Detective) p).orElse(-1);
                if (detLoc != -1) {
                    int d = (distFromMrX[detLoc] == Integer.MAX_VALUE) ? 200 : distFromMrX[detLoc];
                    sumInverse += 1.0 / (1.0 + d);
                    detCount++;
                }
            }
        }
        double closenessScore = (detCount > 0) ? sumInverse / detCount : 0.0;

        // 1-step escape coverage
        Set<Integer> step1 = new HashSet<>(graph.adjacentNodes(mrXLoc));
        int coveredStep1 = 0;
        for (int escape : step1) {
            for (Piece p : state.getPlayers()) {
                if (p.isDetective()) {
                    int dl = state.getDetectiveLocation((Piece.Detective) p).orElse(-1);
                    if (dl != -1 && (dl == escape || graph.adjacentNodes(dl).contains(escape))) {
                        coveredStep1++;
                        break;
                    }
                }
            }
        }
        double step1Score = step1.isEmpty() ? 0.0 : (double) coveredStep1 / step1.size();

        // 2-step escape coverage (nodes reachable in exactly 2 hops from MrX)
        Set<Integer> step2 = new HashSet<>();
        for (int e1 : step1)
            for (int e2 : graph.adjacentNodes(e1))
                if (e2 != mrXLoc && !step1.contains(e2)) step2.add(e2);

        int coveredStep2 = 0;
        for (int escape : step2) {
            for (Piece p : state.getPlayers()) {
                if (p.isDetective()) {
                    int dl = state.getDetectiveLocation((Piece.Detective) p).orElse(-1);
                    if (dl != -1 && (dl == escape || graph.adjacentNodes(dl).contains(escape))) {
                        coveredStep2++;
                        break;
                    }
                }
            }
        }
        double step2Score = step2.isEmpty() ? 0.0 : (double) coveredStep2 / step2.size();

        double coverageScore = 0.7 * step1Score + 0.3 * step2Score;
        return 0.5 * closenessScore + 0.5 * coverageScore;
    }

    // ------------------------------------------------------------------
    // LAYER 2: rollout heuristic — MrX-chasing + anti-clustering
    // ------------------------------------------------------------------
    // Role targets are deliberately NOT used here.  If the target estimate
    // is off, weighting toward it would override the reliable MrX-chasing
    // signal and send detectives to the wrong part of the map.
    // Coordination in rollouts comes purely from:
    //   • chasing MrX (correct by definition)
    //   • clustering penalty (ensures detectives spread out)
    //   • coverage bonus (rewards covering unclaimed escape exits)
    private static final double EPSILON = 0.2;

    private static Move greedyDetectiveMove(List<Move> legal,
                                             Board.GameState state,
                                             int mrXLoc,
                                             Set<Integer> committedDests) {
        var graph = state.getSetup().graph;
        int maxNode = dijkstraAlgorithm.maxNode(graph);
        int[] distFromMrX = dijkstraAlgorithm.mergeDist(graph, mrXLoc, maxNode);
        Set<Integer> mrXEscapes = new HashSet<>(graph.adjacentNodes(mrXLoc));

        Move bestMove = null;
        double bestScore = -Double.MAX_VALUE;

        for (Move move : legal) {
            int dest = getMoveDestination(move);
            int d = (distFromMrX[dest] == Integer.MAX_VALUE) ? 200 : distFromMrX[dest];
            double score = 1.0 / (1.0 + d);

            // Clustering penalty
            for (int committed : committedDests) {
                if (dest == committed) {
                    score -= 0.30;
                } else if (graph.adjacentNodes(dest).contains(committed)) {
                    score -= 0.15;
                }
            }

            // Escape-coverage bonus: reward covering an exit not yet threatened
            for (int escape : mrXEscapes) {
                boolean alreadyCovered = false;
                for (int committed : committedDests) {
                    if (committed == escape || graph.adjacentNodes(committed).contains(escape)) {
                        alreadyCovered = true;
                        break;
                    }
                }
                if (!alreadyCovered
                        && (dest == escape || graph.adjacentNodes(dest).contains(escape))) {
                    score += 0.15;
                    break;
                }
            }

            if (score > bestScore) { bestScore = score; bestMove = move; }
        }
        return (bestMove != null) ? bestMove : legal.get(rng.nextInt(legal.size()));
    }

    private static double simulation(Board.GameState state, int depth, int mrXLoc) {
        Board.GameState currentState = state;
        int moves = 0;
        int currentMrXLoc = mrXLoc;
        Set<Integer> committedDetDests = new HashSet<>();

        while (currentState.getWinner().isEmpty() && moves < depth) {
            List<Move> legal = currentState.getAvailableMoves().asList();
            Move chosen;
            boolean mrXTurn = legal.get(0).commencedBy() == Piece.MrX.MRX;

            if (mrXTurn) {
                committedDetDests.clear();
                chosen = legal.get(rng.nextInt(legal.size()));
            } else if (rng.nextDouble() < EPSILON) {
                chosen = legal.get(rng.nextInt(legal.size()));
                committedDetDests.add(getMoveDestination(chosen));
            } else {
                chosen = greedyDetectiveMove(legal, currentState, currentMrXLoc, committedDetDests);
                committedDetDests.add(getMoveDestination(chosen));
            }

            if (chosen.commencedBy() == Piece.MrX.MRX)
                currentMrXLoc = getMoveDestination(chosen);

            currentState = currentState.advance(chosen);
            moves++;
        }

        if (!currentState.getWinner().isEmpty())
            return currentState.getWinner().contains(Piece.MrX.MRX) ? 0.0 : 1.0;

        return coordinatedLeafEval(currentState, currentMrXLoc);
    }

    private static void backpropagate(IsmctsNode node, double score) {
        IsmctsNode current = node;
        while (current != null) {
            current.update(score);
            current = current.parent;
        }
    }

    // ------------------------------------------------------------------
    // LAYER 3: move priors — seed root children before the search loop
    // ------------------------------------------------------------------
    // prior = 0.5 × expected-escape-coverage + 0.5 × target-proximity
    //
    // expected-escape-coverage: fraction of MrX's 1-step escapes newly covered
    //   by this move, averaged over all posLocs.  Uses adjacency checks only.
    //
    // target-proximity: 1/(1+dist_to_role_target) if a target is assigned for
    //   this detective, 0 otherwise.  This is a soft nudge — the prior decays
    //   with visits so Q-values dominate once real data accumulates.
    //
    // All detectives share the same coverage formula and the same role targets,
    // so their independent searches explore the same high-value moves first.
    private static void seedPriors(IsmctsNode root,
                                    Board board,
                                    List<Move> legalMoves,
                                    List<Integer> posLocs,
                                    Map<Piece, Integer> roleTargets,
                                    Map<Integer, int[]> distFromTargetNode) {
        var graph = board.getSetup().graph;
        List<Integer> detLocs = getDetectiveLocs(board);

        for (Move move : legalMoves) {
            int dest = getMoveDestination(move);

            // Expected escape coverage averaged over posLocs
            double totalCoverage = 0.0;
            for (int mrXLoc : posLocs) {
                Set<Integer> escapes = new HashSet<>(graph.adjacentNodes(mrXLoc));
                int newlyCovered = 0;
                for (int escape : escapes) {
                    boolean alreadyCovered = false;
                    for (int dl : detLocs) {
                        if (dl == escape || graph.adjacentNodes(dl).contains(escape)) {
                            alreadyCovered = true;
                            break;
                        }
                    }
                    if (!alreadyCovered
                            && (dest == escape || graph.adjacentNodes(dest).contains(escape)))
                        newlyCovered++;
                }
                totalCoverage += escapes.isEmpty() ? 0.0 : (double) newlyCovered / escapes.size();
            }
            double coverageScore = posLocs.isEmpty() ? 0.0 : totalCoverage / posLocs.size();

            // Target proximity (soft nudge, not a hard constraint)
            double targetScore = 0.0;
            Integer target = (roleTargets != null) ? roleTargets.get(move.commencedBy()) : null;
            if (target != null) {
                int[] distArr = distFromTargetNode.get(target);
                if (distArr != null) {
                    int d = (distArr[dest] == Integer.MAX_VALUE) ? 200 : distArr[dest];
                    targetScore = 1.0 / (1.0 + d);
                }
            }

            IsmctsNode child = root.addChild(move);
            child.prior = 0.5 * coverageScore + 0.5 * targetScore;
        }
    }

    // Filters to current legal moves so stale children (e.g. from a prior round)
    // can never be selected as the final answer.
    private static Move getBestMove(IsmctsNode root, List<Move> legalMoves) {
        Set<Move> legalSet = new HashSet<>(legalMoves);
        IsmctsNode bestNode = null;
        int maxVisits = -1;
        for (IsmctsNode child : root.children) {
            if (!legalSet.contains(child.incomingMove)) continue;
            if (child.visits > maxVisits) {
                maxVisits = child.visits;
                bestNode = child;
            }
        }
        return (bestNode != null) ? bestNode.incomingMove : null;
    }

    // ------------------------------------------------------------------
    // pickMove — main entry point
    // ------------------------------------------------------------------
    public static Move pickMove(Board board,
                                 Pair<Long, TimeUnit> timeoutPair,
                                 IsmctsNode root,
                                 Map<Piece, Integer> roleTargets) {
        long startTime = System.currentTimeMillis();
        long limit = timeoutPair.right().toMillis(timeoutPair.left()) - 500;

        IsmctsTreePolicy policy = new IsmctsTreePolicy();
        List<Integer> posLocs  = forwardPass(board);
        List<Move> legalMoves  = board.getAvailableMoves().asList();

        // Precompute Dijkstra from each unique role-target node (once, not per iteration).
        // Used only for priors — not passed into simulation.
        var graph = board.getSetup().graph;
        int maxNode = dijkstraAlgorithm.maxNode(graph);
        Map<Integer, int[]> distFromTargetNode = new HashMap<>();
        if (roleTargets != null) {
            for (int target : new HashSet<>(roleTargets.values()))
                distFromTargetNode.put(target, dijkstraAlgorithm.mergeDist(graph, target, maxNode));
        }

        // Seed root with all legal moves + computed priors before the search loop.
        seedPriors(root, board, legalMoves, posLocs, roleTargets, distFromTargetNode);

        while (System.currentTimeMillis() - startTime < limit) {
            if (posLocs.isEmpty()) break;
            int guessLoc = posLocs.get(rng.nextInt(posLocs.size()));

            Board.GameState fakeState = FakeGameStateGenerator.buildFakeGameState(board, guessLoc);
            IsmctsTreePolicy.TreePolicyResult treeResult =
                    policy.treePolicy(root, fakeState, guessLoc);

            double score = simulation(treeResult.state(), 15, treeResult.mrXLoc());
            backpropagate(treeResult.node(), score);
        }

        Move best = getBestMove(root, legalMoves);
        if (best == null && !legalMoves.isEmpty()) return legalMoves.get(0);
        return best;
    }
}
