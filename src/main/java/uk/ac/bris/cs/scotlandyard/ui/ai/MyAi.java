package uk.ac.bris.cs.scotlandyard.ui.ai;

import java.util.concurrent.TimeUnit;

import jakarta.annotation.Nonnull;
import io.atlassian.fugue.Pair;
import uk.ac.bris.cs.scotlandyard.model.*;

public class MyAi implements Ai {

    @Nonnull @Override public String name() { return "biTERIM"; }

    @Nonnull @Override public Move pickMove(
            @Nonnull Board board,
            Pair<Long, TimeUnit> timeoutPair) {

        boolean areWeMrX = board.getAvailableMoves().asList().get(0).commencedBy()
                == Piece.MrX.MRX;

        if (areWeMrX) return minimaxAlg.pickBestMove(board, timeoutPair.left());

        return Ismcts.pickMove(board, timeoutPair, new IsmctsNode(null, null));
    }
}
