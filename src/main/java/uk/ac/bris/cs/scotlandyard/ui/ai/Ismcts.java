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
* --- Coordination design ---
*
* The coordination between detectives comes from TWO sources that work
* independently so neither can destabilise the other:
*
* SOURCE 1 — coordinatedLeafEval (quality signal for the tree)
*   Every tree leaf is scored using ALL five detectives:
*     closenessScore = normalised sum of 1/(1+d_i)  — every detective
*                      getting closer improves the score, not just the nearest.
*     coverageScore  = 0.7 × (fraction of MrX's 1-step escapes covered)
*                    + 0.3 × (fraction of MrX's 2-step escapes covered)
*   "Covered" means a detective is on or directly adjacent to the escape node.
*   Final score = 0.5 * closenessScore + 0.5 * coverageScore.
*   Because ALL detectives appear in this evaluation, the tree learns that
*   spreading out (covering different exits) is better than clustering.
*
* SOURCE 2 — greedyDetectiveMove (rollout heuristic)
*   Primary goal: get close to MrX — score = 1 / (1 + dist_to_MrX).
*   Anti-clustering: if another detective in this round is already heading to
*   the exact same destination, scale the score by 0.6. Multiplicative so
*   the penalty is always proportional to the underlying distance signal —
*   it can never flip a "good, close move" into a "worse than far away" move.
*
* WHY additive bonuses/penalties were removed from the rollout:
*   Adding +0.15 for covering an escape or -0.15 for adjacency to another
*   detective produces bonuses on the same order as 1/(1+d) for d=3..8
*   (range 0.11–0.25). Result: a node 7 hops away covering an escape scores
*   0.13 + 0.15 = 0.28, beating a node 3 hops away with a cluster penalty
*   0.25 - 0.15 = 0.10. Detectives are sent far from MrX. Multiplicative
*   penalties scale with the base score and cannot have this effect.
* */
public class Ismcts {
    private static final Random rng = new Random();
    public record anchorData(Optional<Integer> loc, List<ScotlandYard.Ticket> ticketSinceAnchor) {}

    // ------------------------------------------------------------------
    // Information-set construction (unchanged)
    // ------------------------------------------------------------------

    public static anchorData getDet(Board board) {
        ImmutableList<LogEntry> log = board.getMrXTravelLog();
        List<ScotlandYard.Ticket> ticketSinceAnchor = new ArrayList<>();
        for (int i = log.size() - 1; i >= 0; i--) {
            LogEntry entry = log.get(i);
            if (entry.location().isPresent()) {
                java.util.Collections.reverse(ticketSinceAnchor);
                return new anchorData(entry.location(), ticketSinceAnchor);
            } else {
                ticketSinceAnchor.add(entry.ticket());
            }
        }
        java.util.Collections.reverse(ticketSinceAnchor);
        return new anchorData(Optional.empty(), ticketSinceAnchor);
    }

    public static List<Integer> forwardPass(Board board) {
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

    private static List<Integer> getDetectiveLocs(Board board) {
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
    // SOURCE 1: coordinatedLeafEval
    // ------------------------------------------------------------------
    private static double coordinatedLeafEval(Board.GameState state, int mrXLoc) {
        var graph = state.getSetup().graph;
        int maxNode = dijkstraAlgorithm.maxNode(graph);
        int[] distFromMrX = dijkstraAlgorithm.mergeDist(graph, mrXLoc, maxNode);

        // Closeness: sum of 1/(1+d) for every detective, normalised by count
        double sumInverse = 0.0;
        int detCount = 0;
        for (Piece p : state.getPlayers()) {
            if (p.isDetective()) {
                int dl = state.getDetectiveLocation((Piece.Detective) p).orElse(-1);
                if (dl != -1) {
                    int d = (distFromMrX[dl] == Integer.MAX_VALUE) ? 200 : distFromMrX[dl];
                    sumInverse += 1.0 / (1.0 + d);
                    detCount++;
                }
            }
        }
        double closenessScore = detCount > 0 ? sumInverse / detCount : 0.0;

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

        // 2-step escape coverage (nodes exactly 2 hops from MrX)
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
    // SOURCE 2: greedyDetectiveMove
    // ------------------------------------------------------------------
    // Score = 1/(1+dist_to_MrX).  Multiplicative same-node penalty (×0.6)
    // applied when another detective this round is already heading to the
    // same destination.  Multiplicative so the penalty is proportional to
    // the distance signal — it can never make a close move score worse than
    // a farther move.
    private static final double EPSILON = 0.2;

    private static Move greedyDetectiveMove(List<Move> legal,
                                             Board.GameState state,
                                             int mrXLoc,
                                             Set<Integer> committedDests) {
        var graph = state.getSetup().graph;
        int maxNode = dijkstraAlgorithm.maxNode(graph);
        int[] distFromMrX = dijkstraAlgorithm.mergeDist(graph, mrXLoc, maxNode);

        Move bestMove = null;
        double bestScore = -Double.MAX_VALUE;

        for (Move move : legal) {
            int dest = getMoveDestination(move);
            int d = (distFromMrX[dest] == Integer.MAX_VALUE) ? 200 : distFromMrX[dest];
            double score = 1.0 / (1.0 + d);

            // Multiplicative same-destination penalty
            if (committedDests.contains(dest)) score *= 0.6;

            if (score > bestScore) { bestScore = score; bestMove = move; }
        }
        return bestMove != null ? bestMove : legal.get(rng.nextInt(legal.size()));
    }

    private static double simulation(Board.GameState state, int depth, int mrXLoc) {
        Board.GameState current = state;
        int moves = 0;
        int currentMrXLoc = mrXLoc;
        Set<Integer> committedDetDests = new HashSet<>();

        while (current.getWinner().isEmpty() && moves < depth) {
            List<Move> legal = current.getAvailableMoves().asList();
            Move chosen;
            boolean mrXTurn = legal.get(0).commencedBy() == Piece.MrX.MRX;

            if (mrXTurn) {
                committedDetDests.clear();
                chosen = legal.get(rng.nextInt(legal.size()));
            } else if (rng.nextDouble() < EPSILON) {
                chosen = legal.get(rng.nextInt(legal.size()));
                committedDetDests.add(getMoveDestination(chosen));
            } else {
                chosen = greedyDetectiveMove(legal, current, currentMrXLoc, committedDetDests);
                committedDetDests.add(getMoveDestination(chosen));
            }

            if (chosen.commencedBy() == Piece.MrX.MRX)
                currentMrXLoc = getMoveDestination(chosen);

            current = current.advance(chosen);
            moves++;
        }

        if (!current.getWinner().isEmpty())
            return current.getWinner().contains(Piece.MrX.MRX) ? 0.0 : 1.0;

        return coordinatedLeafEval(current, currentMrXLoc);
    }

    private static void backpropagate(IsmctsNode node, double score) {
        IsmctsNode cur = node;
        while (cur != null) { cur.update(score); cur = cur.parent; }
    }

    // Only considers children whose move is still legal on the current board.
    // Guards against stale children from a prior round being selected.
    private static Move getBestMove(IsmctsNode root, List<Move> legalMoves) {
        Set<Move> legalSet = new HashSet<>(legalMoves);
        IsmctsNode best = null;
        int maxVisits = -1;
        for (IsmctsNode child : root.children) {
            if (!legalSet.contains(child.incomingMove)) continue;
            if (child.visits > maxVisits) { maxVisits = child.visits; best = child; }
        }
        return best != null ? best.incomingMove : null;
    }

    public static Move pickMove(Board board, Pair<Long, TimeUnit> timeoutPair, IsmctsNode root) {
        long startTime = System.currentTimeMillis();
        long limit = timeoutPair.right().toMillis(timeoutPair.left()) - 500;

        IsmctsTreePolicy policy = new IsmctsTreePolicy();
        List<Integer> posLocs = forwardPass(board);
        List<Move> legalMoves = board.getAvailableMoves().asList();

        while (System.currentTimeMillis() - startTime < limit) {
            if (posLocs.isEmpty()) break;
            int guessLoc = posLocs.get(rng.nextInt(posLocs.size()));

            Board.GameState fakeState = FakeGameStateGenerator.buildFakeGameState(board, guessLoc);
            IsmctsTreePolicy.TreePolicyResult result =
                    policy.treePolicy(root, fakeState, guessLoc);

            double score = simulation(result.state(), 15, result.mrXLoc());
            backpropagate(result.node(), score);
        }

        Move best = getBestMove(root, legalMoves);
        return best != null ? best : legalMoves.get(0);
    }
}
