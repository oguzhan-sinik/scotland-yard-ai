package uk.ac.bris.cs.scotlandyard.ui.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;
import jakarta.annotation.Nonnull;
import io.atlassian.fugue.Pair;
import uk.ac.bris.cs.gamekit.graph.Graph;
import uk.ac.bris.cs.scotlandyard.model.*;

public class MyAi implements Ai {

	@Nonnull @Override public String name() { return "biTERIM"; }


	@Nonnull @Override public Move pickMove(
			@Nonnull Board board,
			Pair<Long, TimeUnit> timeoutPair) {

		// checking which player we are!
		boolean areWeMrX = board.getAvailableMoves().asList().get(0).commencedBy() == Piece.MrX.MRX;

		return pickBestMove(board, areWeMrX, timeoutPair);

	}


	@Nonnull public Move pickBestMove(Board board, boolean isMrX, Pair<Long, TimeUnit> timeToMove){

		var moves = board.getAvailableMoves().asList();
		Move bestMove = moves.get(0);

		if (isMrX) {
			// We play MrX through minimax algorithm
			return minimaxAlg.pickBestMove(board, timeToMove.left());
		} else {
			// We play detectives through Monte-Carlo Algorithm
			return Ismcts.pickMove(board, timeToMove);
		}
	}


}
