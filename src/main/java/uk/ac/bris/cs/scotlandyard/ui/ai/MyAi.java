package uk.ac.bris.cs.scotlandyard.ui.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.Nonnull;
import io.atlassian.fugue.Pair;
import uk.ac.bris.cs.scotlandyard.model.*;

public class MyAi implements Ai {

	@Nonnull @Override public String name() { return "helele"; }

	@Nonnull @Override public Move pickMove(
			@Nonnull Board board,
			Pair<Long, TimeUnit> timeoutPair) {

		/*

		TODO:
		 Implement a scoring function,
		 a game tree,
		 and a Mini-Max based AI for MrX and enhance it with Alpha-beta Pruning and other features

		 For MrX, a good move is usually one that will get away from detectives and open up a wide variety of moves.

		 So when writing the scoring functions, we can first get possible places that MrX can go. And using Dijkstra's
		 algorithm we can get shorthest ways that each detective can go those places. And select one node that
		 farthest from all detectives. Before takign the medians, taking the square of distances should be put
		 us to the best move.


		(OPTIONAL) for detectives, a good move is one that will either capture or get close to MrX.
		 */


		// SCORING FUNCTION

		boolean areWeMrX = board.getAvailableMoves().asList().get(0).commencedBy() == Piece.MrX.MRX;

		return pickMove(board, areWeMrX);

	}

	@Nonnull public Integer stepsRequired(Board board, int from, int to){

		// TODO Dijkstra algorithm for finding the shortest path
		// We have a starting point and dest point. The score we want as a return is the shortest distance.



		return new Random().nextInt(5);
	}

	@Nonnull public Integer nodeScore(Board board, int mrXLoc, List<Integer> detectiveLocs){
		int totalScore = 0;

		for (int detLoc : detectiveLocs) {
			int dist = stepsRequired(board, detLoc, mrXLoc);
			totalScore += dist;
		}

		return totalScore;
	}

	@Nonnull public Move pickMove(Board board, boolean isMrX){

		var moves = board.getAvailableMoves().asList();
		Move bestMove = moves.get(0);
		int bestScore = 0;
		int newScore = 0;

		for (Move move : moves) {

			//Board.GameState nextState = ((Board.GameState) board).advance(move);
			//int newScore = minimaxAlg(nextState, isMrX, 4);

			//System.out.println("\nnew score " + newScore + "best score ever " + bestScore);

			if(newScore > bestScore){
				bestScore = newScore;
				bestMove = move;
			}
		}
		return bestMove;
	}

	@Nonnull public Integer minimaxAlg(Board board, boolean isMrX, int depth, int mrXLoc, List<Integer> detectiveLocs){

		List<Integer> scores = new ArrayList<>();
		var moves = board.getAvailableMoves().asList();

		if (depth == 0) {
			int totalScore = 0;
			for ( int detectiveLoc : detectiveLocs) {
				int dist = stepsRequired(board, detectiveLoc, mrXLoc);
				totalScore = dist * dist;
			}
			return  totalScore;
		}

		int bestScore = 0;

		for (int adjacent : board.getSetup().graph.adjacentNodes(mrXLoc)){

			if (detectiveLocs.contains(adjacent)) continue;

			int score = minimaxAlg(board, isMrX, depth - 1, mrXLoc, detectiveLocs);
			if (score > bestScore) bestScore = score;

		}

		return isMrX ? Collections.max(scores) : Collections.min(scores);
	}

}
