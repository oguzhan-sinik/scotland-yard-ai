package uk.ac.bris.cs.scotlandyard.ui.ai;

import uk.ac.bris.cs.scotlandyard.model.Move;

import java.util.ArrayList;
import java.util.List;

// Tree architecture of ISMCTS
// Each node is an Information set rather than a complete game state
// Burak Alican Kilinc

public class IsmctsNode {
    public final Move incomingMove;
    public IsmctsNode parent;   // non-final so detachFromParent() can null it out
    public final List<IsmctsNode> children;

    public int visits;
    public double totalReward;
    public double prior = 0.0;  // Reserved for future use; currently unused (always 0)

    // IsmctsNode : Construction of a node
    // @input incomingMove : holds stored gameState's Move
    // @input parent : parent node
    public IsmctsNode(Move incomingMove, IsmctsNode parent) {
        this.parent =  parent;
        this.incomingMove = incomingMove;
        this.children = new ArrayList<>();
        this.visits = 0;
        this.totalReward = 0.0;
    }

    // detachFromParent : severs this node from its parent so it can become the new root.
    // Prevents backpropagation from leaking scores into the discarded portion of the tree
    // and allows the GC to collect the old nodes above this point.
    public void detachFromParent() {
        this.parent = null;
    }

    // getChild : checks if this node has a child representing a move
    // @input move : parents move
    // @output (has a child) ? child : null
    public IsmctsNode getChild(Move move){
        for (IsmctsNode child : children){
            if (child.incomingMove.equals(move)){return child;}
        }

        return null; // 50 coursework mark mistake
    }

    // addChild : creates a new child
    // @input move : childs move
    // @output child : new child
    public IsmctsNode addChild(Move move){
        IsmctsNode child = new  IsmctsNode(move, this);
        this.children.add(child);
        return child;
    }

    // update : updates the nodes reward values after a simulation completes
    // @input reward : result of the simulation. 1.0 for win / 0.0 for loss
    public void update(double reward){
        this.visits++;
        this.totalReward += reward;
    }

    // getUCB : UCB1 augmented with a decaying move prior (AlphaGo-style).
    //
    // Unvisited nodes return a large base value + prior so that exploration
    // proceeds in prior-quality order rather than arbitrarily.
    // Once visited, the prior contributes a decaying bonus:
    //   priorBonus = PRIOR_WEIGHT * prior / (1 + visits)
    // At 1 visit this adds ~0.25; at 20 visits ~0.012; effectively zero by 100 visits.
    //
    // input : exploration constant (√2 is standard)
    private static final double PRIOR_WEIGHT = 0.5;

    public double getUCB(double c){
        if (this.visits == 0) { return 100.0 + prior; }  // explore in prior order before first visit
        if (this.parent == null || this.parent.visits == 0) {
            return this.totalReward / this.visits + PRIOR_WEIGHT * prior / (1.0 + this.visits);
        }
        double exploitation = this.totalReward / this.visits;
        double exploration  = c * Math.sqrt(Math.log(this.parent.visits) / this.visits);
        double priorBonus   = PRIOR_WEIGHT * prior / (1.0 + this.visits);
        return exploitation + exploration + priorBonus;
    }

    public List<Move> getUntriedMoves(List<Move> moves){
        List<Move> untriedMoves = new ArrayList<>();
        for (Move Legal : moves){
            boolean alreadyExpanded = false;
            for (IsmctsNode child : children){
                if (child.incomingMove.equals(Legal)){alreadyExpanded = true; break;}
            }
            if (!alreadyExpanded) {untriedMoves.add(Legal);}
        }
        return untriedMoves;
    }




}
