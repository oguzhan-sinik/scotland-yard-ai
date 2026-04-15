package uk.ac.bris.cs.scotlandyard.ui.ai;


import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.atlassian.fugue.Pair;
import uk.ac.bris.cs.scotlandyard.model.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

// Information Set Monte Carlo Tree Search algorithm for Scotland Yard AI.
// This file is the main engine of the implementation

// ISMCTS : Information Set Monte Carlo Tree Search
// MCTS : Monte Carlo Tree Search

/*
* Since detectives work on imperfect data (meaning that the information on the target is not always available)
* Minimax would not suit to be a good AI for the detectives. Its kinda slow and only works on perfect data.
* I decided to work with an algorithm that can work with imperfect data. Its a really cool version of MCTS
* Called Information Set Monte Carlo Search Tree.
*
* Main idea of MCTS is instead of calculating every possible scenario like MiniMax,
* MCTS simulates many random games and see which moves lead to a win
* Its essentially a tree of games which we expand its nodes one by one and at each expansion,
* we walk back the tree to record win/loss.
*
* The issue with MCTS is that it only works with a game with perfect data (since we need an anchor point for the nodes)
* ScotlandYard Detectives dont know where MrX is AKA imperfect data / we dont know where the anchor point is
* This is where the information set comes in. In MCTS, a node is a single, fully known game state
* in ISMCTS, every node is a set of possible game states. eg, a set of possible nodes MrX can take after a reveal
* So before calculating the win/loss for the current node, it runs a determinization step.
* determinization step may sound cool but it just picks a random state from the set :(
* This way, ISMCTS can work with imperfect data, very suitable for Scotland Yard
*
* Overall, really cool algorithm!
* Burak Alican Kilinc
* */

// Core class of ISMCTS
public class Ismcts {
    // @Param rng : a random seed for simulation step. Initialized here for optimization purposes
    // @Param anchorData : A package function to store an anchor point
    private static final Random rng =  new Random();
    public record anchorData(Optional<Integer> loc, List<ScotlandYard.Ticket> ticketSinceAnchor){}

    // getDet : the Information Set
    // Looks at the travel log of MrX and the moves since mrX location was last reveals
    // returns the set of all possible nodes he can be in
    // @input board : current board
    // @output anchorData : the info set
    public static anchorData getDet(Board board){
        ImmutableList<LogEntry> log = board.getMrXTravelLog();
        List<ScotlandYard.Ticket> ticketSinceAnchor = new ArrayList<>();

        for (int i = log.size()-1; i >= 0; i--) { // we only care about the last reveal, hence reverse search
            LogEntry entry = log.get(i);

            if(entry.location().isPresent()){
                java.util.Collections.reverse(ticketSinceAnchor); //using reverse to return the normal log slice
                return new anchorData(entry.location(), ticketSinceAnchor);
            } else {
                ticketSinceAnchor.add(entry.ticket());
            }
        }

        java.util.Collections.reverse(ticketSinceAnchor);
        return new anchorData(Optional.empty(), ticketSinceAnchor);
    }

    // forwardPass : iterates through LogEntry and for each ticket used, looks for adjacent nodes designated by the type of ticket
    // @input board : current board
    // @output possibleLocs : A list of possible locations of mrX
    public static List<Integer> forwardPass(Board board){
        anchorData data = getDet(board);
        Set<Integer> possibleLocs = new HashSet<>();
        List<Integer> detectiveLocs = getDetectiveLocs(board);

        // filtering current locations of detectives
        if (data.loc().isPresent()) {possibleLocs.add(data.loc().get());}
        else {
            possibleLocs.addAll(ScotlandYard.MRX_LOCATIONS);
            possibleLocs.removeAll(detectiveLocs);
        }

        // basically the same loops for the construction of moves param in MyGameStateFactory.
        // Only difference is its just for mrX and looks through multiple tickets
        for (ScotlandYard.Ticket ticket : data.ticketSinceAnchor()) {
            Set<Integer> nextPossibleLocs = new HashSet<>();
            for (int source : possibleLocs) {
                for (int destination : board.getSetup().graph.adjacentNodes(source)) {
                    if (detectiveLocs.contains(destination)) continue;
                    var transports = board.getSetup().graph.edgeValueOrDefault(source, destination, ImmutableSet.of());
                    boolean canTravel = false;

                    if (ticket == ScotlandYard.Ticket.SECRET && !transports.isEmpty()) {canTravel = true;}
                    else {
                        for (ScotlandYard.Transport t : transports) {
                            if (t.requiredTicket() == ticket) {canTravel = true; break;}
                        }
                    }

                    if (canTravel) {nextPossibleLocs.add(destination);}
                }
            }

            possibleLocs = nextPossibleLocs;
        }

        return new ArrayList<>(possibleLocs);
    }

    // getDetectiveLocs : helper method for returning a list of the locations of the detectives
    // @input board : current board
    // @output Locs : a list of locations of the detectives
    private static List<Integer> getDetectiveLocs(Board board){
        List<Integer> Locs = new ArrayList<>();
        for (Piece p : board.getPlayers()){
            if(p.isDetective()){
                board.getDetectiveLocation((Piece.Detective) p).ifPresent(Locs::add);
            }
        }

        return Locs;
    }


    private static double simulation(Board.GameState state, int Depth){
        Board.GameState currentState = state;
        int moves = 0;

        while (currentState.getWinner().isEmpty() && moves < Depth){
            List<Move> legal = currentState.getAvailableMoves().asList();
            Move random = legal.get(rng.nextInt(legal.size()));
            currentState = currentState.advance(random);
            moves++;
        }

        if (!currentState.getWinner().isEmpty()){
            if (currentState.getWinner().contains(Piece.MrX.MRX)) return 0;
            else return 1;
        }

        return 0.3; // assume mrX has advantage, might want to use Dijktra in future
    }

    private static void backpropagate(IsmctsNode node, double score) {
        IsmctsNode current = node;
        while (current != null) {
            current.update(score);
            current = current.parent;
        }
    }

    private static Move getBestMove(IsmctsNode root) {
        IsmctsNode bestNode = null;
        int maxVisits = -1;

        for (IsmctsNode child : root.children) {
            if (child.visits > maxVisits) {
                maxVisits = child.visits;
                bestNode = child;
            }
        }

        if (bestNode == null) return null;
        return bestNode.incomingMove;
    }

    //buralar MyAI'da olucak/olabilecek kısımlar

    public static Move pickMove(Board board, Pair<Long, TimeUnit> timeoutPair){
        long startTime = System.currentTimeMillis();
        long limit = timeoutPair.right().toMillis(timeoutPair.left()) - 500; // safety buffer. May need change

        IsmctsNode root = new IsmctsNode(null, null);
        IsmctsTreePolicy policy = new IsmctsTreePolicy();

        while(System.currentTimeMillis() - startTime < limit){
            List<Integer> posLocs = Ismcts.forwardPass(board);
            if (posLocs.isEmpty()) {break;}
            int guessLoc = posLocs.get(new Random().nextInt(posLocs.size()));

            Board.GameState fakeState = FakeGameStateGenerator.buildFakeGameState(board, guessLoc);
            Pair<IsmctsNode, Board.GameState> treeResult = policy.treePolicy(root, fakeState);
            IsmctsNode selectNode = treeResult.left();
            Board.GameState stateAtNode = treeResult.right();

            double score = simulation(stateAtNode, 15);
            backpropagate(selectNode, score);
        }

        return getBestMove(root);
    }
}
