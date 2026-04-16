package uk.ac.bris.cs.scotlandyard.ui.ai;

import uk.ac.bris.cs.scotlandyard.model.Move;

import java.util.ArrayList;
import java.util.List;

// Tree architecture of ISMCTS
// representation of a single node in tree
// Burak Alican Kilinc

public class IsmctsNode {
    public final Move incomingMove; // moved played to reach current state
    public final IsmctsNode parent; // null if root
    public final List<IsmctsNode> children; // expanded childs

    public int visits; // number of thimes this node was visited during search
    public double totalReward; // cumulative results of sims that passed this node

    // IsmctsNode : constructor
    public IsmctsNode(Move incomingMove, IsmctsNode parent) {
        this.parent =  parent;
        this.incomingMove = incomingMove;
        this.children = new ArrayList<>();
        this.visits = 0;
        this.totalReward = 0.0;
    }

    // getChild : checks if a move is already expanded somewhere in the tree
    // @input move : move we want to search
    // @output : (expansion exists) ? (expansion child) : null
    public IsmctsNode getChild(Move move){
        for (IsmctsNode child : children){
            if (child.incomingMove.equals(move)){return child;}
        }

        return null; // there goes another billion
    }

    // addChild : expands the tree
    // @input move : childs move value
    // @output : the new child
    public IsmctsNode addChild(Move move){
        IsmctsNode child = new  IsmctsNode(move, this);
        this.children.add(child);
        return child;
    }

    // update : this is for backpropagation
    // updates statistics
    // @input reward : reward won in a single node
    public void update(double reward){
        this.visits++;
        this.totalReward += reward;
    }

    // getUCB : calculates the Upper Confidence Bound 1
    // this formula balances exploration (trying different moves)
    // and exploitation (picking moves that are known to win)
    // @input c : UCB1 constant
    // @output : UCB1 value
    public double getUCB(double c){
        if (this.visits == 0) {return Double.MAX_VALUE;}

        double exploitation = this.totalReward / this.visits;
        double exploration = c * Math.sqrt(Math.log(this.parent.visits) / this.visits);
        return exploitation + exploration;
    }

    // getUntriedMoves : returns a list of untried legal moves
    // helper method for expansion phase
    // @input move : list of all legal moves
    // @oputput : list of untried moves
    public List<Move> getUntriedMoves(List<Move> moves){
        List<Move> untriedMoves = new ArrayList<>();
        for (Move Legal : moves){
            boolean alreadyExpanded = false;
            for (IsmctsNode child : children){
                if (child.incomingMove.equals(Legal)){alreadyExpanded = true; break;}
            }
            // If theres no existing child for the specific move, we havent tried it yet
            if (!alreadyExpanded) {untriedMoves.add(Legal);}
        }
        return untriedMoves;
    }




}
