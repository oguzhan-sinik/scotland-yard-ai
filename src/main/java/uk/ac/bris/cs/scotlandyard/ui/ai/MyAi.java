package uk.ac.bris.cs.scotlandyard.ui.ai;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.Nonnull;
import io.atlassian.fugue.Pair;
import uk.ac.bris.cs.scotlandyard.model.Ai;
import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Move;

public class MyAi implements Ai {

	@Nonnull @Override public String name() { return "helele"; }

	@Nonnull @Override public Move pickMove(
			@Nonnull Board board,
			Pair<Long, TimeUnit> timeoutPair) {
		// returns a random move, replace with your own implementation


		/*

		TODO:
		 Implement a scoring function,
		 a game tree,
		 and a Mini-Max based AI for MrX and enhance it with Alpha-beta Pruning and other features

		 For MrX, a good move is usually one that will get away from detectives and open up a wide variety of moves.

		 So when writing the scoring functions, we can first get possible places that MrX can go. And using Dijkstra's
		 algorithm we can get shorthest ways that each detective can go those places. And select one node that
		 each detective can't go very easily.


		(OPTIONAL) for detectives, a good move is one that will either capture or get close to MrX.
		 */


		// SCORING FUNCTION



		var moves = board.getAvailableMoves().asList();
		return moves.get(new Random().nextInt(moves.size()));
	}
}
