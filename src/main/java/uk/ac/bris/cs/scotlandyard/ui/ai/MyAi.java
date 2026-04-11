package uk.ac.bris.cs.scotlandyard.ui.ai;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.Nonnull;
import io.atlassian.fugue.Pair;
import uk.ac.bris.cs.scotlandyard.model.Ai;
import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.Piece;

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

		System.out.println("Blue: " + board.getDetectiveLocation(Piece.Detective.BLUE).orElseThrow().toString());
		System.out.println("Red: " + board.getDetectiveLocation(Piece.Detective.RED).orElseThrow().toString());
		System.out.println("Green: " + board.getDetectiveLocation(Piece.Detective.GREEN).orElseThrow().toString());
		System.out.println("White: " + board.getDetectiveLocation(Piece.Detective.WHITE).orElseThrow().toString());
		System.out.println("Yellow: " + board.getDetectiveLocation(Piece.Detective.YELLOW).orElseThrow().toString());

		var moves = board.getAvailableMoves().asList();

		boolean areWeMrX = moves.get(0).commencedBy() == Piece.MrX.MRX;

		for (var move : moves) {
			int destination = move.accept(new Move.Visitor<Integer>() {
				@Override
				public Integer visit(Move.SingleMove m){
					return m.destination;
				}

				@Override
				public Integer visit(Move.DoubleMove m){
					return m.destination2;
				}
			});

			System.out.println("from " + move.source() + " to " + destination);

		}

		System.out.println("\n=========================================\n");

		if (areWeMrX) {
			return moves.get(new Random().nextInt(moves.size()));
		} else {
			return moves.get(new Random().nextInt(moves.size()));
		}


	}
}
