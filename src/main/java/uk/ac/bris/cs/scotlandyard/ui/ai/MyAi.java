package uk.ac.bris.cs.scotlandyard.ui.ai;

import java.util.concurrent.TimeUnit;

import jakarta.annotation.Nonnull;
import io.atlassian.fugue.Pair;
import uk.ac.bris.cs.scotlandyard.model.*;

public class MyAi implements Ai {

	@Nonnull @Override public String name() { return "biTERIM"; }
	
	// Reset conditions:
	//   • MrX just moved — his true position may have changed, the old tree is
	//     anchored on a determinisation that is now stale.  Start fresh.
	//   • The requested child is missing (rare edge-case, e.g. first iteration
	//     didn't expand that branch yet) — start fresh rather than crash.
	private IsmctsNode sharedRoot        = null;
	private Move       lastDetectiveMove = null;
	private boolean    lastWasDetective  = false;


	@Nonnull @Override public Move pickMove(
			@Nonnull Board board,
			Pair<Long, TimeUnit> timeoutPair) {

		boolean areWeMrX = board.getAvailableMoves().asList().get(0).commencedBy()
				== Piece.MrX.MRX;

		if (areWeMrX) {
			// MrX is playing — reset the shared tree for the upcoming detective round
			sharedRoot        = null;
			lastDetectiveMove = null;
			lastWasDetective  = false;
			return minimaxAlg.pickBestMove(board, timeoutPair.left());
		}

		// Detective turn: advance or create the shared tree
		if (lastWasDetective && lastDetectiveMove != null && sharedRoot != null) {
			// Try to find the subtree rooted at the move the previous detective made
			IsmctsNode advanced = sharedRoot.getChild(lastDetectiveMove);
			if (advanced != null) {
				// Sever from old tree: prevents backpropagation from leaking scores
				// upward into nodes we no longer care about, and lets the GC collect them.
				advanced.detachFromParent();
				sharedRoot = advanced;
			} else {
				// Child wasn't explored yet (can happen at game start or with tiny time budgets)
				sharedRoot = new IsmctsNode(null, null);
			}
		} else {
			// First detective in this round — start with a fresh root
			sharedRoot = new IsmctsNode(null, null);
		}

		Move best = Ismcts.pickMove(board, timeoutPair, sharedRoot);

		// Remember what we played so the next detective can advance the tree
		lastDetectiveMove = best;
		lastWasDetective  = true;

		return best;
	}
}
