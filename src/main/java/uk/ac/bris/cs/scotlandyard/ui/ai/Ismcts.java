package uk.ac.bris.cs.scotlandyard.ui.ai;


import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.atlassian.fugue.Pair;
import uk.ac.bris.cs.scotlandyard.model.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

// Information Set Monte Carlo Tree Search algorithm for Scotland Yard AI.
// This file is the main engine of the implementation

// ISMCTS : Information Set Monte Carlo Tree Search
// MCTS : Monte Carlo Tree Search

/*
* Since detectives work on imperfect data (meaning that the information on the target is not always available)
* Minimax would not suit to be a good AI for the detectives. Its kinda slow and only works on perfect data.
* I decided to work with an algorithm that can work with imperfect data. Its a really cool version of MCTS
* Called Information Set Monte Carlo Search Tree.
*
* Main idea of MCTS is instead of calculating every possible scenario like MiniMax,
* MCTS simulates many random games and see which moves lead to a win
* Its essentially a tree of games which we expand its nodes one by one and at each expansion,
* we walk back the tree to record win/loss.
*
* The issue with MCTS is that it only works with a game with perfect data (since we need an anchor point for the nodes)
* ScotlandYard Detectives dont know where MrX is AKA imperfect data / we dont know where the anchor point is
* This is where the information set comes in. In MCTS, a node is a single, fully known game state
* in ISMCTS, every node is a set of possible game states. eg, a set of possible nodes MrX can take after a reveal
* So before calculating the win/loss for the current node, it runs a determinization step.
* determinization step may sound cool but it just picks a random state from the set :(
* This way, ISMCTS can work with imperfect data, very suitable for Scotland Yard
*
* Overall, really cool algorithm!
* Burak Alican Kilinc
*
* --- Three coordination layers (see MyAi for role-assignment context) ---
*
* 1. coordinatedLeafEval  — evaluates tree leaves using ALL detectives (sum of
*    inverse distances) PLUS 1-step AND 2-step escape-route coverage.  2-step
*    analysis rewards positions that cut off MrX two moves out, not just one.
*
* 2. greedyDetectiveMove  — rollout heuristic that is:
*    (a) role-aware: each detective blends 60 % target-seeking / 40 % MrX-chasing,
*        steering each piece toward its assigned escape node,
*    (b) anti-clustering: penalises destinations already taken by committed dets,
*    (c) coverage-bonus: rewards covering an escape not yet threatened.
*
* 3. Move priors at the root  — before the ISMCTS loop, every root child is
*    pre-seeded with a prior score = 0.5 * expected escape coverage + 0.5 *
*    target proximity (averaged over posLocs).  The modified UCB in IsmctsNode
*    causes independent detective searches to explore the same high-value moves
*    first, making their plans converge even without shared tree memory.
* */

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
                    var transports = board.getSetup().graph.edgeValueOrDefault(source, destination, ImmutableSet.of());
                    boolean canTravel = false;
                    if (ticket == ScotlandYard.Ticket.SECRET && !transports.isEmpty()) { canTravel = true; }
                    else {
                        for (ScotlandYard.Transport t : transports) {
                            if (t.requiredTicket() == ticket) { canTravel = true; break; }
                        }
                    }
                    if (canTravel) { nextPossibleLocs.add(destination); }
                }
            }
            possibleLocs = nextPossibleLocs;
        }
        return new ArrayList<>(possibleLocs);
    }

    private static List<Integer> getDetectiveLocs(Board board){
        List<Integer> locs = new ArrayList<>();
        for (Piece p : board.getPlayers()){
            if(p.isDetective()){
                board.getDetectiveLocation((Piece.Detective) p).ifPresent(locs::add);
            }
        }
        return locs;
    }

    private static int getMoveDestination(Move move) {
        return move.accept(new Move.Visitor<Integer>() {
            @Override public Integer visit(Move.SingleMove m) { return m.destination; }
            @Override public Integer visit(Move.DoubleMove m) { return m.destination2; }
        });
    }

    // ------------------------------------------------------------------
    // COORDINATION LAYER 1: coordinatedLeafEval (1-step + 2-step escapes)
    // ------------------------------------------------------------------
    // Components:
    //   closenessScore  — normalised sum of 1/(1+d_i) over all detectives.
    //                     Every detective getting closer improves the score.
    //   coverageScore   — weighted combination of:
    //       step1: fraction of MrX's immediate escape nodes covered (detective
    //              is on or adjacent to the escape).  Weight 0.7.
    //       step2: fraction of MrX's 2-step escape nodes covered.  These are
    //              nodes reachable in 2 moves from MrX but not in 1 move.
    //              "Covered" means a detective is on or adjacent to the node.
    //              Weight 0.3.
    //
    // Final score = 0.5 * closenessScore + 0.5 * coverageScore.
    private static double coordinatedLeafEval(Board.GameState state, int mrXLoc) {
        var graph = state.getSetup().graph;
        int maxNode = dijkstraAlgorithm.maxNode(graph);
        int[] distFromMrX = dijkstraAlgorithm.mergeDist(graph, mrXLoc, maxNode);

        // --- closeness ---
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

        // --- 1-step escape coverage ---
        Set<Integer> step1 = new HashSet<>(graph.adjacentNodes(mrXLoc));
        int coveredStep1 = 0;
        for (int escape : step1) {
            for (Piece p : state.getPlayers()) {
                if (p.isDetective()) {
                    int detLoc = state.getDetectiveLocation((Piece.Detective) p).orElse(-1);
                    if (detLoc != -1
                            && (detLoc == escape || graph.adjacentNodes(detLoc).contains(escape))) {
                        coveredStep1++;
                        break;
                    }
                }
            }
        }
        double step1Score = step1.isEmpty() ? 0.0 : (double) coveredStep1 / step1.size();

        // --- 2-step escape coverage ---
        // Nodes reachable from MrX in exactly 2 hops that are NOT in step1 and NOT mrXLoc itself.
        Set<Integer> step2 = new HashSet<>();
        for (int e1 : step1) {
            for (int e2 : graph.adjacentNodes(e1)) {
                if (e2 != mrXLoc && !step1.contains(e2)) step2.add(e2);
            }
        }
        int coveredStep2 = 0;
        for (int escape : step2) {
            for (Piece p : state.getPlayers()) {
                if (p.isDetective()) {
                    int detLoc = state.getDetectiveLocation((Piece.Detective) p).orElse(-1);
                    if (detLoc != -1
                            && (detLoc == escape || graph.adjacentNodes(detLoc).contains(escape))) {
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
    // COORDINATION LAYER 2: role-aware, anti-clustering rollout
    // ------------------------------------------------------------------
    // roleTargets       : piece → assigned target node for this round (from MyAi)
    // distFromTargetNode: target node → precomputed Dijkstra distances from that node
    //
    // Scoring per candidate destination D:
    //   base   — if piece has a target: 60 % 1/(1+distToTarget) + 40 % 1/(1+distToMrX)
    //            otherwise: 100 % 1/(1+distToMrX)
    //   penalty — -0.30 if D == committed detective dest, -0.15 if adjacent to one
    //   bonus   — +0.15 if D covers an escape not yet covered by any committed det

    private static final double EPSILON = 0.2;

    private static Move greedyDetectiveMove(List<Move> legal,
                                             Board.GameState state,
                                             int mrXLoc,
                                             Set<Integer> committedDests,
                                             Map<Piece, Integer> roleTargets,
                                             Map<Integer, int[]> distFromTargetNode) {
        var graph = state.getSetup().graph;
        int maxNode = dijkstraAlgorithm.maxNode(graph);
        int[] distFromMrX = dijkstraAlgorithm.mergeDist(graph, mrXLoc, maxNode);
        Set<Integer> mrXEscapes = new HashSet<>(graph.adjacentNodes(mrXLoc));

        // Look up this detective's target distances (null if no target assigned)
        Piece currentPiece = legal.get(0).commencedBy();
        Integer targetNode = (roleTargets != null) ? roleTargets.get(currentPiece) : null;
        int[] distFromTarget = (targetNode != null) ? distFromTargetNode.get(targetNode) : null;

        Move bestMove = null;
        double bestScore = -Double.MAX_VALUE;

        for (Move move : legal) {
            int dest = getMoveDestination(move);
            int dMrX = (distFromMrX[dest] == Integer.MAX_VALUE) ? 200 : distFromMrX[dest];

            double score;
            if (distFromTarget != null) {
                // Role-aware: blend target-seeking (primary) with MrX-chasing (secondary)
                int dTarget = (distFromTarget[dest] == Integer.MAX_VALUE) ? 200 : distFromTarget[dest];
                score = 0.6 * (1.0 / (1.0 + dTarget)) + 0.4 * (1.0 / (1.0 + dMrX));
            } else {
                score = 1.0 / (1.0 + dMrX);
            }

            // Anti-clustering penalty
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

    private static double simulation(Board.GameState state,
                                      int depth,
                                      int mrXLoc,
                                      Map<Piece, Integer> roleTargets,
                                      Map<Integer, int[]> distFromTargetNode) {
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
                chosen = greedyDetectiveMove(legal, currentState, currentMrXLoc,
                        committedDetDests, roleTargets, distFromTargetNode);
                committedDetDests.add(getMoveDestination(chosen));
            }

            if (chosen.commencedBy() == Piece.MrX.MRX) {
                currentMrXLoc = getMoveDestination(chosen);
            }
            currentState = currentState.advance(chosen);
            moves++;
        }

        if (!currentState.getWinner().isEmpty()) {
            return currentState.getWinner().contains(Piece.MrX.MRX) ? 0.0 : 1.0;
        }
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
    // COORDINATION LAYER 3: move priors — seed root children before search
    // ------------------------------------------------------------------
    // Each legal move is given a prior = 0.5 * expectedCoverage + 0.5 * targetProximity
    // where:
    //   expectedCoverage  — average fraction of MrX's 1-step escapes that this move
    //                       newly covers, averaged over all possible MrX locations
    //                       in posLocs.  Uses only adjacency checks (no extra Dijkstra).
    //   targetProximity   — 1/(1 + dist_to_assigned_target) if this detective has a
    //                       role target; 0 otherwise.
    //
    // Seeding root children with priors means exploration proceeds in prior order
    // (highest-prior moves tried first) and the persistent prior bonus in UCB keeps
    // biasing toward better moves even after the initial round of visits.
    // Because all detectives share the same evaluation function and role targets,
    // their independent searches converge on compatible formation moves.
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

            // --- expected escape coverage (averaged over posLocs) ---
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
                            && (dest == escape || graph.adjacentNodes(dest).contains(escape))) {
                        newlyCovered++;
                    }
                }
                totalCoverage += escapes.isEmpty() ? 0.0 : (double) newlyCovered / escapes.size();
            }
            double coverageScore = posLocs.isEmpty() ? 0.0 : totalCoverage / posLocs.size();

            // --- target proximity ---
            double targetScore = 0.0;
            Integer target = (roleTargets != null) ? roleTargets.get(move.commencedBy()) : null;
            if (target != null) {
                int[] distArr = distFromTargetNode.get(target);
                if (distArr != null) {
                    int d = (distArr[dest] == Integer.MAX_VALUE) ? 200 : distArr[dest];
                    targetScore = 1.0 / (1.0 + d);
                }
            }

            // Pre-seed root child with this prior
            IsmctsNode child = root.addChild(move);
            child.prior = 0.5 * coverageScore + 0.5 * targetScore;
        }
    }

    // ------------------------------------------------------------------
    // getBestMove — filters to legal moves only (guards against stale children)
    // ------------------------------------------------------------------
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
        List<Integer> posLocs = forwardPass(board);
        List<Move> legalMoves = board.getAvailableMoves().asList();

        // Precompute distance arrays from each unique role-target node (once, not per iteration)
        var graph = board.getSetup().graph;
        int maxNode = dijkstraAlgorithm.maxNode(graph);
        Map<Integer, int[]> distFromTargetNode = new HashMap<>();
        if (roleTargets != null) {
            for (int target : new HashSet<>(roleTargets.values())) {
                distFromTargetNode.put(target, dijkstraAlgorithm.mergeDist(graph, target, maxNode));
            }
        }

        // Seed the root with all legal moves + computed priors before the search loop.
        // This guarantees exploration proceeds in prior order and the prior bonus persists
        // throughout the search (see IsmctsNode.getUCB).
        seedPriors(root, board, legalMoves, posLocs, roleTargets, distFromTargetNode);

        while (System.currentTimeMillis() - startTime < limit) {
            if (posLocs.isEmpty()) break;
            int guessLoc = posLocs.get(rng.nextInt(posLocs.size()));

            Board.GameState fakeState = FakeGameStateGenerator.buildFakeGameState(board, guessLoc);
            IsmctsTreePolicy.TreePolicyResult treeResult =
                    policy.treePolicy(root, fakeState, guessLoc);
            IsmctsNode selectNode   = treeResult.node();
            Board.GameState stateAtNode = treeResult.state();
            int mrXLocAtNode        = treeResult.mrXLoc();

            double score = simulation(stateAtNode, 15, mrXLocAtNode, roleTargets, distFromTargetNode);
            backpropagate(selectNode, score);
        }

        Move best = getBestMove(root, legalMoves);
        if (best == null && !legalMoves.isEmpty()) return legalMoves.get(0);
        return best;
    }
}
