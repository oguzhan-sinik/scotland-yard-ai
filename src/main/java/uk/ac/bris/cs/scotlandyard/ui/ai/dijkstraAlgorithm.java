package uk.ac.bris.cs.scotlandyard.ui.ai;

import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;
import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;

import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;

// Dijkstra Algorithm
// Used by Minimax and ISMCTS
// Burak Alican Kilinc

public class dijkstraAlgorithm {

    private dijkstraAlgorithm() {}

    // score : returns the shortest distance in moves
    // @input board : current game board
    // @input mrXloc : target we want to go
    // @input from : source we want to start
    // @output : shortest distance between target and source
    public static int score(Board board, int mrXloc, int from) {
        ImmutableValueGraph<Integer, ImmutableSet<ScotlandYard.Transport>> graph = board.getSetup().graph;
        int maxNode = maxNode(graph);
        int[] minDist = mergeDist(graph, mrXloc, maxNode);
        
        return minDist[from];
    }

    // maxNode : helper method for returning maximum node ID
    // @input graph : graph of scotland yard
    // @output : maximum node ID
    public static int maxNode(
            ImmutableValueGraph<Integer, ImmutableSet<ScotlandYard.Transport>> graph) {
        return graph.nodes().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    // mergeDist : the dijkstra algorithm
    // @input graph : map of scotland yard
    // @input source : source node we want to start at
    // @input maxNode : maximum node id
    // @output : the list of routes from source
    public static int[] mergeDist(ImmutableValueGraph<Integer, ImmutableSet<ScotlandYard.Transport>> graph, int source, int maxNode) {
        int[] minDist = new int[maxNode + 1];
        // initialize distance with infinity
        Arrays.fill(minDist, Integer.MAX_VALUE);
        minDist[source] = 0;

        // priority queue prioritizes shortest distances
        PriorityQueue<int[]> pq = new PriorityQueue<>(Comparator.comparingInt(v -> v[0]));
        pq.add(new int[]{0, source});

        while (!pq.isEmpty()) {
            int[] entry = pq.poll();
            int d = entry[0], u = entry[1];
            // optimization : ignores bad routes if a faster route is available
            if (d > minDist[u]) continue;

            // iterate all adjacent nodes
            for (int v : graph.adjacentNodes(u)) {
                if (!graph.edgeValueOrDefault(u, v, ImmutableSet.of()).isEmpty()) {
                    int nd = d + 1; // no weight aka all routes take same amount of moves

                    //if shorter path is found, update and queue
                    if (nd < minDist[v]){
                        minDist[v] = nd;
                        pq.add(new int[]{nd, v});
                    }
                }
            }
        }

        return minDist;

    }

}
