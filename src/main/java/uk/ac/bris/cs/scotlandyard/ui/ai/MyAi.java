package uk.ac.bris.cs.scotlandyard.ui.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.ImmutableSet;
import jakarta.annotation.Nonnull;
import io.atlassian.fugue.Pair;
import uk.ac.bris.cs.scotlandyard.model.*;

public class MyAi implements Ai {

	@Nonnull @Override public String name() { return "biTERIM"; }


	@Nonnull @Override public Move pickMove(
			@Nonnull Board board,
			Pair<Long, TimeUnit> timeoutPair) {

		boolean areWeMrX = board.getAvailableMoves().asList().get(0).commencedBy() == Piece.MrX.MRX;

		return pickMove(board, areWeMrX);

	}

	@Nonnull public Integer stepsRequired(Board board, int from, int to){

		// TODO Dijkstra algorithm for finding the shortest path
		// We have a starting point and dest point. The score we want as a return is the shortest distance.



		return dijkstraAlgorithm.score(board, from, to);
	}

	public Integer nodeScore(Board board, int mrXLoc, List<Integer> detectiveLocs) {
		var graph = board.getSetup().graph;
		int maxNode = dijkstraAlgorithm.maxNode(graph);
		int[] distFromMrX = dijkstraAlgorithm.mergeDist(graph, mrXLoc, maxNode);

		int totalScore = 0;
		for (int detLoc : detectiveLocs) {
			int dist = (distFromMrX[detLoc] == Integer.MAX_VALUE) ? 0 : distFromMrX[detLoc];

			if (dist <= 1) totalScore -= 10000;
			totalScore += dist * dist;
		}
		return totalScore;
	}

	private boolean canDetectiveReach(Board board, int from, int to) {
		var transports = board.getSetup().graph.edgeValueOrDefault(from, to, ImmutableSet.of());
		// Detectives have TAXI, BUS, UNDERGROUND — never SECRET, so FERRY is off-limits
		for (ScotlandYard.Transport t : transports) {
			if (t != ScotlandYard.Transport.FERRY) return true;
		}
		return false;
	}

	@Nonnull public Integer destination (Move move) {
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

	private boolean canMrXReach(Board board, int from, int to) {
		var transports = board.getSetup().graph.edgeValueOrDefault(from, to, ImmutableSet.of());
		var tickets = board.getPlayerTickets(Piece.MrX.MRX).orElseThrow();
		for (ScotlandYard.Transport t : transports) {
			if (tickets.getCount(t.requiredTicket()) > 0) return true;
		}
		// SECRET ticket works as a wildcard for any transport
		return tickets.getCount(ScotlandYard.Ticket.SECRET) > 0;
	}

	@Nonnull public Move pickMove(Board board, boolean isMrX){

		var moves = board.getAvailableMoves().asList();
		Move bestMove = moves.get(0);

		if (isMrX) {
			int bestScore = Integer.MIN_VALUE;
			List<Integer> detectiveLocs = new ArrayList<>();

			for (Piece piece : board.getPlayers()) {
				if (piece.isDetective()) board.getDetectiveLocation((Piece.Detective) piece).ifPresent(detectiveLocs::add);
			}

			for (Move move : moves) {

				int destination = destination(move);

				int newScore = minimaxAlg(board, false, 20, destination, detectiveLocs, Integer.MIN_VALUE, Integer.MAX_VALUE);

				if(newScore > bestScore){
					System.out.println("\nnew best score " + newScore + " Previous best score " + bestScore);
					bestScore = newScore;
					bestMove = move;
				}
			}

			return bestMove;
		} else {
			System.out.println("We are not capable of playing detectives yet!");
			return bestMove;
		}



	}

	@Nonnull public Integer minimaxAlg(Board board, boolean isMrX, int depth, int mrXLoc, List<Integer> detectiveLocs, int alpha, int beta){

		if (depth == 0) return nodeScore(board, mrXLoc, detectiveLocs);

		if (isMrX) {

			int bestScore = Integer.MIN_VALUE;

			for (int adjacent : board.getSetup().graph.adjacentNodes(mrXLoc)) {

				if (detectiveLocs.contains(adjacent)) continue;
				if (!canMrXReach(board, mrXLoc, adjacent)) continue;

				int score = minimaxAlg(board, false, depth -1, adjacent, detectiveLocs, alpha, beta);

				if (score > bestScore) bestScore = score;
				if (bestScore > alpha) alpha = bestScore;
				if (beta <= alpha) break; // detective already have a better option to go
			}

			return bestScore;

		} else {
			var graph = board.getSetup().graph;
			int maxNode = dijkstraAlgorithm.maxNode(graph);
			int[] distFromMrX = dijkstraAlgorithm.mergeDist(graph, mrXLoc, maxNode); // ONE call

			List<Integer> potNewDetLocs = new ArrayList<>(detectiveLocs);

			for (int i = 0; i < potNewDetLocs.size(); i++) {
				int currentLoc = potNewDetLocs.get(i);
				int bestAdj = currentLoc;
				int bestScore = Integer.MAX_VALUE;

				for (int adj : graph.adjacentNodes(currentLoc)) {
					if (!canDetectiveReach(board, currentLoc, adj)) continue;
					boolean occupiedByAnotherDet = false;
					for (int j = 0; j < potNewDetLocs.size(); j++) {
						if (potNewDetLocs.get(j) == adj) { occupiedByAnotherDet = true; break; }
					}
					if (occupiedByAnotherDet) continue;

					int dist = distFromMrX[adj]; // O(1) lookup, no Dijkstra
					if (dist < bestScore) { bestScore = dist; bestAdj = adj; }
				}
				potNewDetLocs.set(i, bestAdj);
			}
			return minimaxAlg(board, true, depth - 1, mrXLoc, potNewDetLocs, alpha, beta);
		}


	}

}
