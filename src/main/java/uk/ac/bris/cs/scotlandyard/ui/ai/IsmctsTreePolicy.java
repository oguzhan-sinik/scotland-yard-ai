package uk.ac.bris.cs.scotlandyard.ui.ai;
import io.atlassian.fugue.Pair;
import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.Piece;

import java.util.List;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.TimeUnit;

// This is where selection and expansion stages are implemented
// tree policy tells the algorithm how to navigate the tree
// It walks down the tree by picking the most promising nodes (Selection) until it
// finds a state where not all legal moves have been explored. It then picks one of
// those unexplored moves, adds it to the tree (Expansion), and returns it
// Burak Alican Kilinc


public class IsmctsTreePolicy {
    private final Random rng = new Random();

    // a bundle that keeps all information required for the ISMCTS loop
    public record TreePolicyResult(IsmctsNode node, Board.GameState state, int mrXLoc) {}

    // getDest : helper method to extract destinations
    // yes I used visitor design pattern :D
    // @input move : move we want to extract its destination
    // @output : destination of the move
    private static int getDest(Move move) {
        return move.accept(new Move.Visitor<Integer>() {
            @Override public Integer visit(Move.SingleMove m) { return m.destination; }
            @Override public Integer visit(Move.DoubleMove m) { return m.destination2; }
        });
    }

    // getBest : aka selection phase
    // When a node is fully expanded, we use the Upper Confidence Bound 1 algorithm
    // to pick the best child to explore next
    // @input children : list of childrens that we want to find the best
    // @output : the best child
    // side note: by best, I mean most visited. Not best win rate.
    // is it better to have %100 winrate in 1 simulated game or %95 winrate in 1000 simulated games?
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

    // treePolicy : executes Selection and Expansion phases
    // @input root : root of the tree
    // @input state : current simulated board state
    // @input mrXLoc : assumed location of mrX
    // @output : our bundle (look top of the file)
    public TreePolicyResult treePolicy(IsmctsNode root, Board.GameState state, int mrXLoc) {
        IsmctsNode currentNode = root;
        Board.GameState currentState = state;
        int currentMrXLoc = mrXLoc;

        // traverse the tree until we hit an ending (win or lose)
        // or expand
        while(currentState.getWinner().isEmpty()){
            List<Move> legal = currentState.getAvailableMoves().asList();

            // check if theres any legal  moves left that we havent expanded yet
            List<Move> untried = currentNode.getUntriedMoves(legal);

            // this is where expansion is done.
            // if theres any untried moves left, we dont search the best, we select a random node to expand
            if(!untried.isEmpty()){
                Move randomUntried = untried.get(rng.nextInt(untried.size()));
                IsmctsNode child = currentNode.addChild(randomUntried);

                // track mrX if he was the one who was moved
                if (randomUntried.commencedBy() == Piece.MrX.MRX) {
                    currentMrXLoc = getDest(randomUntried);
                }
                Board.GameState next = currentState.advance(randomUntried);

                // Return the newly created node to hand off to the Simulation phase
                return new TreePolicyResult(child, next, currentMrXLoc);
            }

            // this is where selection is handled
            // reaching here means that we have tried all legal moves
            // and we have to select the best path to move down to
            List<IsmctsNode> legalChildren = new ArrayList<>();
            for (Move move : legal) {
                IsmctsNode child = currentNode.getChild(move);
                if (child != null) {legalChildren.add(child);} // billion dollar fix (I'm starting to hate null already)
            }

            if (legalChildren.isEmpty()) {break;} // billion dollar fix v2

            // UCB1
            currentNode = getBest(legalChildren);

            // Keep tracking Mr. X's location as we traverse down the existing tree
            if (currentNode.incomingMove.commencedBy() == Piece.MrX.MRX) {
                currentMrXLoc = getDest(currentNode.incomingMove);
            }

            //advance game
            currentState = currentState.advance(currentNode.incomingMove);
        }
        return new TreePolicyResult(currentNode, currentState, currentMrXLoc);
    }


}
