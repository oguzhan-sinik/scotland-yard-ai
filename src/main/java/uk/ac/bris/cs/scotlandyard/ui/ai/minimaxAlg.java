package uk.ac.bris.cs.scotlandyard.ui.ai;

import com.google.common.collect.ImmutableSet;
import jakarta.annotation.Nonnull;
import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;

import java.util.ArrayList;
import java.util.List;

public class minimaxAlg {

    private minimaxAlg () {}

    // we score each node in this function
    public static Integer nodeScore(Board board, int mrXLoc, List<Integer> detectiveLocs) {

        // for finding the distance between nodes, and also reading the graph for simulations we use dijkstra algorithm
        var graph = board.getSetup().graph;
        int maxNode = dijkstraAlgorithm.maxNode(graph);
        int[] distFromMrX = dijkstraAlgorithm.mergeDist(graph, mrXLoc, maxNode);

        // We first calculate node's score based on detectives locations
        int totalScore = totalScoreBasedOnDetectiveLocations(board, distFromMrX, detectiveLocs);

        // Then also add some penalty for potential escape routes for better decision here.
        totalScore += escapeRouteCount(board, mrXLoc, detectiveLocs) * 30;

        return totalScore;
    }

    // we calculate the total score by looking at how far each detective is from mrX
    private static Integer totalScoreBasedOnDetectiveLocations (Board board, int[] distFromMrX, List<Integer> detectiveLocs) {
        int totalScore = 0;
        int minDist = Integer.MAX_VALUE;

        int dangerCount = 0;

        /*
        In this for loop, using each detective's locations,
            1 - we count the number of danger's (the points which are right one step from detectives)
            2 - we find the closest distance of any detective to use it later
            3 - then we first calculate our total score with the square of distance
         */
        for (int detLoc : detectiveLocs) {
            int dist = (distFromMrX[detLoc] == Integer.MAX_VALUE) ? 0 : distFromMrX[detLoc];
            if (dist <= 1) dangerCount++;
            if (dist < minDist) minDist = dist;

            totalScore += dist * dist;
        }

        if (dangerCount == 0 && minDist > 3) {
            System.out.println("Safe enough to skip some penalties");
            return totalScore;
        }

        // if any detective is right next to mrX we apply a big penalty
        if (dangerCount > 0) totalScore -= 5000 + (dangerCount * 2000);

        // on reveal rounds we penlise being close to detectives
        if (isRevealRound(board) && minDist <= 3) totalScore -= minDist * 500;

        // we also give a bonus for the closest detective to add a margin
        if (minDist != Integer.MAX_VALUE) totalScore += minDist * 50;

        return totalScore;
    }

    static boolean  isRevealRound(Board board) { // for being extra carefully if the round is the reveal round
        int nextRound = board.getMrXTravelLog().size();
        List<Boolean> rounds = board.getSetup().moves;
        return nextRound < rounds.size() && rounds.get(nextRound);
    }

    // we count how many adjacent nodes are not blocked by detectives so mrX has options to escape
    private static Integer escapeRouteCount (Board board, int mrXLoc, List<Integer> detectiveLocs) {

        var graph = board.getSetup().graph;

        int escapeRoutes = 0;
        for (int adj : graph.adjacentNodes(mrXLoc)) {
            boolean blocked = false;
            for (int det : detectiveLocs) { if (det == adj) { blocked = true; break; } }
            if (!blocked) escapeRoutes++;
        }

        return escapeRoutes;

    }

    // checks if a detective can reach from one node to another
    static boolean canDetectiveReach(Board board, int from, int to) {
        var transports = board.getSetup().graph.edgeValueOrDefault(from, to, ImmutableSet.of());
        for (ScotlandYard.Transport t : transports) {
            if (t != ScotlandYard.Transport.FERRY) return true; // ferry is excluded
        }
        return false;
    }

    // we extract final destination for moves to use it later
    @Nonnull public static Integer destination (Move move) {
        int destination = move.accept(new Move.Visitor<Integer>() {
            @Override
            public Integer visit(Move.SingleMove move) {
                return move.destination;
            }

            @Override
            public Integer visit(Move.DoubleMove move) {
                return move.destination2;
            }
        });

        return destination;
    }

    // we check if mrX can reach from one node another
    static boolean canMrXReach(Board board, int from, int to) {
        var transports = board.getSetup().graph.edgeValueOrDefault(from, to, ImmutableSet.of());
        var tickets = board.getPlayerTickets(Piece.MrX.MRX).orElseThrow();
        for (ScotlandYard.Transport t : transports) {
            if (tickets.getCount(t.requiredTicket()) > 0) return true;
        }
        return tickets.getCount(ScotlandYard.Ticket.SECRET) > 0; // if no normal ticket works, we have secret ticket!!
    }

    // this is the main entry point, we go through all possible moves and select one based on the results of minimax score
    @Nonnull
    public static Move pickBestMove(Board board, long timeToMove){

        var moves = board.getAvailableMoves().asList();
        Move bestMove = moves.get(0);


            int bestScore = Integer.MIN_VALUE;
            List<Integer> detectiveLocs = new ArrayList<>();

            for (Piece piece : board.getPlayers()) { // Checking every single detective playing
                if (piece.isDetective()) board.getDetectiveLocation((Piece.Detective) piece).ifPresent(detectiveLocs::add); // adding the locs of detectives
            }

            for (Move move : moves) { // evaluate each possible move

                int destination = destination(move);

                int newScore = minimaxAlgorithm(board, false, treeDepth(timeToMove), destination, detectiveLocs, Integer.MIN_VALUE, Integer.MAX_VALUE);

                newScore = ticketIntelligence(board, move, newScore, destination, detectiveLocs); // then also apply ticket intelligence for better decisions

                if(newScore > bestScore) {
                    bestScore = newScore;
                    bestMove = move;
                }

                System.out.printf("Move -> node %3d | score: %d | ticket: %s%n", destination, newScore, move.tickets());

            }

            System.out.printf(">>> CHOSEN: node %d | score: %d%n", destination(bestMove), bestScore);

            return bestMove;

    }


    // we adjust the score based on how wisely mrX uses tickets like secret or double move
    static Integer ticketIntelligence (Board board, Move move, Integer score, int mrXLoc, List<Integer> detectiveLocs) {

        var graph = board.getSetup().graph;
        int maxNode = dijkstraAlgorithm.maxNode(graph);
        int[] distFromMrX = dijkstraAlgorithm.mergeDist(graph, mrXLoc, maxNode);

        // finding the distance of closes detective, so we can decide on ticket usage
        int minDist = Integer.MAX_VALUE;
        for (int detLoc : detectiveLocs) {
            int d = distFromMrX[detLoc];
            if (d < minDist) minDist = d;
        }

        boolean usesSecret = isMoveContainSecret(move);
        boolean revealRound = isRevealRound(board);

        // on reveal rounds, if detectives are close, using secret tickets is smart move, but otherwise it is not.
        if (revealRound) {
            if (usesSecret && minDist <= 2) score += 400;
            else if (usesSecret && minDist > 3) score -= 200;
        } else {
            if (usesSecret) {
                if (minDist > 3) score -= countSecretTicket(move) * 1500; // leave secret tickets for next rounds
            }
        }

        int destination = destination(move);

        if (mrXLoc == destination) score -= 3000; // we don't want to stay in same nodes

        int connectivity = graph.degree(destination);

        if (minDist < 2) {
            score += connectivity * 8; // penalty for nodes that have better connectivity, we can escape more easily
        }

        // double moves only we are in extremely danger
        if (isDoubleMove(move)) {
            if (minDist <= 2) score += 500;
            else if (minDist <= 4) score -= 3000;
            else score -= 7000;
        }

        return score;
    }

    // checks if a move is double move using visitor pattern
    static boolean isDoubleMove (Move move) {
        return move.accept(new Move.Visitor<Boolean>() {
            @Override
            public Boolean visit(Move.SingleMove move) {
                return false;
            }

            @Override
            public Boolean visit(Move.DoubleMove move) {
                return true;
            }
        });
    }

    static boolean isMoveContainSecret (Move move) {
        for (ScotlandYard.Ticket t : move.tickets()) {
            if (t == ScotlandYard.Ticket.SECRET) return true;
        }
        return false;
    }

    static Integer countSecretTicket (Move move) {
        int count = 0;

        for (ScotlandYard.Ticket t : move.tickets()) {
            if (t == ScotlandYard.Ticket.SECRET) count++;
        }

        return count;
    }

    // based on the time we have, we make our minimax tree deeper
    static Integer treeDepth (long time) {
        return 4 + ((int) time / 15);
    }

    // the core entry point of minimax alg.
    @Nonnull public static Integer minimaxAlgorithm(Board board, boolean isMrX, int depth, int mrXLoc, List<Integer> detectiveLocs, int alpha, int beta){

        if (depth == 0) return nodeScore(board, mrXLoc, detectiveLocs); // last leaf

        if (isMrX) return minimaxMrXTurn(board, depth, mrXLoc, detectiveLocs, alpha, beta); // max
        else return minimaxDetectiveTurn(board, depth, mrXLoc, detectiveLocs, alpha, beta); // min

    }

    // mrX is the maximising player, we try all reachable nodes and pick the best score
    @Nonnull static Integer minimaxMrXTurn (Board board, int depth, int mrXLoc, List<Integer> detectiveLocs, int alpha, int beta) {
        int bestScore = Integer.MIN_VALUE;

        for (int adjacent : board.getSetup().graph.adjacentNodes(mrXLoc)) {

            // skips nodes that are occupied, or unreachable atm
            if (detectiveLocs.contains(adjacent)) continue;
            if (!canMrXReach(board, mrXLoc, adjacent)) continue;

            int score = minimaxAlgorithm(board, false, depth -1, adjacent, detectiveLocs, alpha, beta);

            if (score > bestScore) bestScore = score;
            if (bestScore > alpha) alpha = bestScore;
            if (beta <= alpha) break; // detective already have a better option to go (alpha-beta pruning)
        }

        if (bestScore == Integer.MIN_VALUE) return -100000;
        return bestScore;
    }

    // detectives are the minimising player
    @Nonnull static Integer minimaxDetectiveTurn (Board board, int depth, int mrXLoc, List<Integer> detectiveLocs, int alpha, int beta) {

        var graph = board.getSetup().graph;
        int maxNode = dijkstraAlgorithm.maxNode(graph);
        // we run dijkstra once from mrX and reuse the distances for all detectives
        int[] distFromMrX = dijkstraAlgorithm.mergeDist(graph, mrXLoc, maxNode);

        // we ding where each detective would most likely to move
        List<Integer> potNewDetLocs = potentialDetLocFinder(board, detectiveLocs, distFromMrX, mrXLoc);
        if (potNewDetLocs == null) return -100000;  // MrX got caught

        return minimaxAlgorithm(board, true, depth - 1, mrXLoc, potNewDetLocs, alpha, beta); // it is mrX's turn again
    }

    // for each detective we pick the adjacent node that get them closest to mrX
    private static List<Integer> potentialDetLocFinder (Board board, List<Integer> detectiveLocs, int[] distFromMrX, int mrXLoc) {

        var graph = board.getSetup().graph;
        List<Integer> potNewDetLocs = new ArrayList<>(detectiveLocs);

        for (int i = 0; i < potNewDetLocs.size(); i++) {
            int currentLoc = potNewDetLocs.get(i);
            int bestAdj = currentLoc;
            int bestScore = Integer.MAX_VALUE;

            for (int adj : graph.adjacentNodes(currentLoc)) {

                if (adj == mrXLoc) return null;
                // skips if detective can't reach, or occupied by another detective
                if (!canDetectiveReach(board, currentLoc, adj)) continue;
                boolean occupiedByAnotherDet = false;
                for (int j = 0; j < potNewDetLocs.size(); j++) if (potNewDetLocs.get(j) == adj) { occupiedByAnotherDet = true; break; }

                if (occupiedByAnotherDet) continue;

                int dist = distFromMrX[adj]; // O(1) lookup, no Dijkstra

                if (dist < bestScore) { bestScore = dist; bestAdj = adj; }
            }
            potNewDetLocs.set(i, bestAdj);
        }

        return potNewDetLocs;
    }
}
