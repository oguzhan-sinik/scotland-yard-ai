package uk.ac.bris.cs.scotlandyard.ui.ai;
import io.atlassian.fugue.Pair;
import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Move;

import java.util.List;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class IsmctsTreePolicy {
    private final Random rng = new Random();

    private Pair<IsmctsNode, Board.GameState> expand(IsmctsNode node, Board.GameState state, List<Move> untried) {
        Move randomUntried = untried.get(rng.nextInt(untried.size()));
        IsmctsNode child = node.addChild(randomUntried);
        Board.GameState next = state.advance(randomUntried);

        return new Pair<>(child, next);
    }

    private IsmctsNode getBest(List<IsmctsNode> children) {
        IsmctsNode bestChild = null;
        double bestScore = -Double.MAX_VALUE;
        double explorationC = 1.414; //standard UCB1 constant sqrt(2). Higher = more exploring, lower = more exploiting
        for (IsmctsNode child : children) {
            double ucbVal = child.getUCB(explorationC);
            if (ucbVal > bestScore) {
                bestScore = ucbVal;
                bestChild = child;
            }
        }

        return bestChild;

    }

    public Pair<IsmctsNode, Board.GameState> treePolicy(IsmctsNode root, Board.GameState state) {
        IsmctsNode currentNode = root;
        Board.GameState currentState = state;

        while(currentState.getWinner().isEmpty()){
            List<Move> legal = currentState.getAvailableMoves().asList();
            List<Move> untried = currentNode.getUntriedMoves(legal);

            if(!untried.isEmpty()){
                return expand(currentNode, currentState, untried);
            }

            List<IsmctsNode> legalChildren = new ArrayList<>();
            for (Move move : legal) {
                IsmctsNode child = currentNode.getChild(move);
                if (child != null) {legalChildren.add(child);}
            }

            if (legalChildren.isEmpty()) {break;}

            currentNode = getBest(legalChildren);
            currentState = currentState.advance(currentNode.incomingMove);
        }
        return new Pair<>(currentNode, currentState);
    }


}
