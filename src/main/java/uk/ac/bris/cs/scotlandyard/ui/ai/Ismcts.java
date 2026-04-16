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

    // getDest : helper method to get destinations
    // uses visitor pattern
    // @input move : a move that we want to wind its destination
    // @output destination : self explanatory
    private static int getDest(Move move) {
        return move.accept(new Move.Visitor<Integer>() {
            @Override public Integer visit(Move.SingleMove m) { return m.destination; }
            @Override public Integer visit(Move.DoubleMove m) { return m.destination2; }
        });
    }

    // dijkstraEV : main reason this method exists is that we need a way to score
    // after either max depth is reached or time is out.
    // returning a constant was an easy choice bu I decided to normalize the dijkstra distance
    // between mrX and the closest detective and use it.
    // @input state : current simulated state
    // @input mrXLoc : mrX's guessed location
    // @output : normalized minimum distance between the detectives and mrX
    private static double dijkstraEV(Board.GameState state, int mrXLoc) {
        var graph = state.getSetup().graph;
        int maxNode = dijkstraAlgorithm.maxNode(graph);
        int[] distMrX = dijkstraAlgorithm.mergeDist(graph, mrXLoc, maxNode);

        int minDist = Integer.MAX_VALUE;
        for (Piece p : state.getPlayers()) {
            if (p.isDetective()) {
                int detLoc = state.getDetectiveLocation((Piece.Detective) p).orElse(-1);
                if (detLoc != -1) {
                    int d = (distMrX[detLoc] == Integer.MAX_VALUE) ? 200 : distMrX[detLoc];
                    if (d < minDist) minDist = d;
                }
            }
        }

        if (minDist == Integer.MAX_VALUE) return 0.1; // all detectives unreachable, MrX is safe
        return 1.0 / (1.0 + minDist);
    }

    // greedy : a cool addition to reduce noise
    // instead of picking a move from legal moves randomly,
    // we pick the move that minimises distance to mrX's most likely position
    // with probability 1−ε, and random with probability ε.
    // basically bringing heuristics for move decision instead of complete randomness.
    private static final double EPSILON = 0.2; // heuristic constant.
    // @input legal : a list of legal moves
    // @input state : game state thats being simulated
    // @input mrXLoc : mrX's guessed location
    private static Move greedy(List<Move> legal, Board.GameState state, int mrXLoc) {
        var graph = state.getSetup().graph;
        int maxNode = dijkstraAlgorithm.maxNode(graph);
        int[] distFromMrX = dijkstraAlgorithm.mergeDist(graph, mrXLoc, maxNode);

        Move bestMove = null;
        int bestDist = Integer.MAX_VALUE;
        for (Move move : legal) {
            int dest = getDest(move);
            int d = (distFromMrX[dest] == Integer.MAX_VALUE) ? 200 : distFromMrX[dest];
            if (d < bestDist) { bestDist = d; bestMove = move; }
        }
        return (bestMove != null) ? bestMove : legal.get(rng.nextInt(legal.size()));
    }

    // simulation : this is where simulation happens.
    // this method executes a simulated playout from the current nodes state
    // @input state: current nodes state
    // @input Depth : max simulation depth
    // @input mrXLoc : guessed location of mrX
    // @output : winrate with values between 0 and 1
    private static double simulation(Board.GameState state, int Depth, int mrXLoc){
        Board.GameState currentState = state;
        int moves = 0;
        int currentMrXLoc = mrXLoc;

        while (currentState.getWinner().isEmpty() && moves < Depth){
            List<Move> legal = currentState.getAvailableMoves().asList();
            Move chosen;

            // mrX always plays randomly
            // detectives play randomly a certain percentage of time for exploration
            // rest of the time, we play greedily
            boolean mrXTurn = legal.get(0).commencedBy() == Piece.MrX.MRX;
            if (mrXTurn || rng.nextDouble() < EPSILON) {
                chosen = legal.get(rng.nextInt(legal.size()));
            } else {
                chosen = greedy(legal, currentState, currentMrXLoc);
            }

            // If mrX moved, we update his location so that our detectives greedy
            // heuristics knows where to chase him
            if (chosen.commencedBy() == Piece.MrX.MRX) {
                currentMrXLoc = getDest(chosen);
            }

            currentState = currentState.advance(chosen);
            moves++;
        }

        // If any player won we return definite scores aka 1 or 0
        if (!currentState.getWinner().isEmpty()){
            if (currentState.getWinner().contains(Piece.MrX.MRX)) return 0;
            else return 1;
        }

        // If we hit depth limit we return the dijkstra heuristic
        return dijkstraEV(currentState, currentMrXLoc);
    }

    // backpropagate : aka move back up the tree.
    // after a simulation finishes this function takes the resulting score back up the tree
    // this updates win/loss statistics, thus informing future paths
    // @input node : current node
    // @input score : win/loss score. can hold a value between 0 and 1
    public static void backpropagate(IsmctsNode node, double score) {
        IsmctsNode current = node;
        while (current != null) {
            current.update(score);
            current = current.parent;
        }
    }

    // getBestMove : After time limit runs out this method looks at the
    // immediate children and to decide which move to play
    // quick side note: the best child is not the highest winrate
    // its the most path visited
    // @input root : root of the tree
    // @output : move of the best child
    public static Move getBestMove(IsmctsNode root) {
        IsmctsNode bestNode = null;
        int maxVisits = -1;

        for (IsmctsNode child : root.children) {
            if (child.visits > maxVisits) {
                maxVisits = child.visits;
                bestNode = child;
            }
        }

        if (bestNode == null) return null; // 50 coursework mark mistake v2
        return bestNode.incomingMove;
    }


    // pickMove : entry point for the detective AI / ISMCTS
    // @input board : current, real time board
    // @input timeoutPair : time unit
    // @output : AI's move decision
    public static Move pickMove(Board board, Pair<Long, TimeUnit> timeoutPair){
        // time management
        long startTime = System.currentTimeMillis();
        long limit = timeoutPair.right().toMillis(timeoutPair.left()) - 500; // safety buffer. May need change

        // tree initialization
        IsmctsNode root = new IsmctsNode(null, null);
        IsmctsTreePolicy policy = new IsmctsTreePolicy();

        // information set generation
        List<Integer> posLocs = Ismcts.forwardPass(board);

        // main ISMCTS loop
        while(System.currentTimeMillis() - startTime < limit){
            if (posLocs.isEmpty()) {break;}
            // determinization step
            int guessLoc = posLocs.get(rng.nextInt(posLocs.size()));

            // Selection and Expansion
            Board.GameState fakeState = FakeGameStateGenerator.buildFakeGameState(board, guessLoc);
            IsmctsTreePolicy.TreePolicyResult treeResult = policy.treePolicy(root, fakeState, guessLoc);
            IsmctsNode selectNode = treeResult.node();
            Board.GameState stateAtNode = treeResult.state();
            int mrXLocAtNode = treeResult.mrXLoc();

            // Simulation
            double score = simulation(stateAtNode, 15, mrXLocAtNode);

            // Backpropagation
            backpropagate(selectNode, score);
        }

        // Final Selection
        return getBestMove(root);
    }
}
