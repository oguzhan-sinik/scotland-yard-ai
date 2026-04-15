package uk.ac.bris.cs.scotlandyard.ui.ai;
import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.Piece;

import java.util.List;
import java.util.ArrayList;
import java.util.Random;

public class IsmctsTreePolicy {
    private final Random rng = new Random();

    // Bundles what treePolicy returns so we can pass mrXLoc into simulation
    public record TreePolicyResult(IsmctsNode node, Board.GameState state, int mrXLoc) {}

    // Gets the final destination of any move (single or double)
    private static int getMoveDestination(Move move) {
        return move.accept(new Move.Visitor<Integer>() {
            @Override public Integer visit(Move.SingleMove m) { return m.destination; }
            @Override public Integer visit(Move.DoubleMove m) { return m.destination2; }
        });
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

    // mrXLoc is threaded through so the caller knows where MrX ended up at the leaf node,
    // which is needed for Dijkstra-based leaf evaluation in the simulation step.
    public TreePolicyResult treePolicy(IsmctsNode root, Board.GameState state, int mrXLoc) {
        IsmctsNode currentNode = root;
        Board.GameState currentState = state;
        int currentMrXLoc = mrXLoc;

        while(currentState.getWinner().isEmpty()){
            List<Move> legal = currentState.getAvailableMoves().asList();
            List<Move> untried = currentNode.getUntriedMoves(legal);

            if(!untried.isEmpty()){
                Move randomUntried = untried.get(rng.nextInt(untried.size()));
                IsmctsNode child = currentNode.addChild(randomUntried);
                if (randomUntried.commencedBy() == Piece.MrX.MRX) {
                    currentMrXLoc = getMoveDestination(randomUntried);
                }
                Board.GameState next = currentState.advance(randomUntried);
                return new TreePolicyResult(child, next, currentMrXLoc);
            }

            List<IsmctsNode> legalChildren = new ArrayList<>();
            for (Move move : legal) {
                IsmctsNode child = currentNode.getChild(move);
                if (child != null) {legalChildren.add(child);}
            }

            if (legalChildren.isEmpty()) {break;}

            currentNode = getBest(legalChildren);
            if (currentNode.incomingMove.commencedBy() == Piece.MrX.MRX) {
                currentMrXLoc = getMoveDestination(currentNode.incomingMove);
            }
            currentState = currentState.advance(currentNode.incomingMove);
        }
        return new TreePolicyResult(currentNode, currentState, currentMrXLoc);
    }
}
