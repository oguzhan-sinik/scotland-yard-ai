package uk.ac.bris.cs.scotlandyard.ui.ai;

import uk.ac.bris.cs.scotlandyard.model.Move;

import java.util.ArrayList;
import java.util.List;

// Tree architecture of ISMCTS
// This file is 

public class IsmctsNode {
    public final Move incomingMove;
    public final IsmctsNode parent;
    public final List<IsmctsNode> children;

    public int visits;
    public double totalReward;

    public IsmctsNode(Move incomingMove, IsmctsNode parent) {
        this.parent =  parent;
        this.incomingMove = incomingMove;
        this.children = new ArrayList<>();
        this.visits = 0;
        this.totalReward = 0.0;
    }

    public IsmctsNode getChild(Move move){
        for (IsmctsNode child : children){
            if (child.incomingMove.equals(move)){return child;}
        }

        return null; //I'm sorry for this lol
    }

    public IsmctsNode addChild(Move move){
        IsmctsNode child = new  IsmctsNode(move, this);
        this.children.add(child);
        return child;
    }

    public void update(double reward){
        this.visits++;
        this.totalReward += reward;
    }

    public double getUCB(double c){
        if (this.visits == 0) {return Double.MAX_VALUE;}

        double exploitation = this.totalReward / this.visits;
        double exploration = c * Math.sqrt(Math.log(this.parent.visits) / this.visits);
        return exploitation + exploration;
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
