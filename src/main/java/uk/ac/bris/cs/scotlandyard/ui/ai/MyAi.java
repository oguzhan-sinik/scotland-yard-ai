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

		// our AI is only capable of playing MrX, so we check if we are playing MrX
		boolean areWeMrX = board.getAvailableMoves().asList().get(0).commencedBy() == Piece.MrX.MRX;

		return pickBestMove(board, areWeMrX, timeoutPair.left());

	}


	@Nonnull public Move pickBestMove(Board board, boolean isMrX, long timeToMove){

		var moves = board.getAvailableMoves().asList();
		Move bestMove = moves.get(0);

		if (isMrX) {
			return minimaxAlg.pickBestMove(board, timeToMove);
		} else {
			// System.out.println("We are not capable of playing detectives yet!");
			return bestMove;
		}
	}


}
