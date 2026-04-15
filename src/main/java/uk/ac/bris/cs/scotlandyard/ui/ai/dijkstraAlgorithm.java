package uk.ac.bris.cs.scotlandyard.ui.ai;

import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;
import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;

import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;

public class dijkstraAlgorithm {

    private dijkstraAlgorithm() {}

    //  handle all shortest path calcs on the graph

    // we find the highest numbered node, so we can size our distance arrays properly
    public static int maxNode(
            ImmutableValueGraph<Integer, ImmutableSet<ScotlandYard.Transport>> graph) {
        return graph.nodes().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    // we run Dijkstra for a single transportation type
    public static int[] dijktraTarget(ImmutableValueGraph<Integer, ImmutableSet<ScotlandYard.Transport>> graph, int source, ScotlandYard.Transport transport, int maxNode){
        int[] dist = new int[maxNode + 1];
        Arrays.fill(dist, Integer.MAX_VALUE);
        dist[source] = 0;

        // using priority queue for processing the closest node first
        PriorityQueue<int[]> pq = new PriorityQueue<>(Comparator.comparingInt(v -> v[0]));
        pq.add(new int[]{0, source});

        while (!pq.isEmpty()) {
            int[] entry = pq.poll();
            int d = entry[0];
            int u   = entry[1];
            if (d > dist[u]) {continue;} // skip if we had a shorter path

            for (Integer v : graph.adjacentNodes(u)) {
                ImmutableSet<ScotlandYard.Transport> edge = graph.edgeValueOrDefault(u, v, ImmutableSet.of());
                if (!edge.contains(transport)) {continue;}

                // each edge costs 1 since we care about num of moves not exactly distance
                int nd = d + 1;
                if (nd < dist[v]) {
                    dist[v] = nd;
                    pq.add(new int[]{nd, v});
                }
            }
        }

        return dist;
    }

    public static int[] mergeDist(ImmutableValueGraph<Integer, ImmutableSet<ScotlandYard.Transport>> graph, int source, int maxNode) {
        int[] minDist = new int[maxNode + 1];
        Arrays.fill(minDist, Integer.MAX_VALUE);
        minDist[source] = 0;

        // and here we run dijkstra for each transport type, and keep only the shortest one
        for (ScotlandYard.Transport transport : ScotlandYard.Transport.values()) {
            int[] d = dijktraTarget(graph, source, transport, maxNode);
            for (int node = 1; node <= maxNode; node++) {
                if (d[node] < minDist[node]) minDist[node] = d[node];
            }
        }
        return minDist;

    }

}
