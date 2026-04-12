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

    public static int score(Board board, int mrXloc, int from) {
        ImmutableValueGraph<Integer, ImmutableSet<ScotlandYard.Transport>> graph = board.getSetup().graph;
        int maxNode = maxNode(graph);
        int[] minDist = mergeDist(graph, mrXloc, maxNode);
        
        return minDist[from];
    }

    public static int maxNode(
            ImmutableValueGraph<Integer, ImmutableSet<ScotlandYard.Transport>> graph) {
        return graph.nodes().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    public static int[] dijktraTarget(ImmutableValueGraph<Integer, ImmutableSet<ScotlandYard.Transport>> graph, int source, ScotlandYard.Transport transport, int maxNode){
        int[] dist = new int[maxNode + 1];
        Arrays.fill(dist, Integer.MAX_VALUE);
        dist[source] = 0;

        PriorityQueue<int[]> pq = new PriorityQueue<>(Comparator.comparingInt(v -> v[0]));
        pq.add(new int[]{0, source});

        while (!pq.isEmpty()) {
            int[] entry = pq.poll();
            int d = entry[0];
            int u   = entry[1];
            if (d > dist[u]) {continue;}

            for (Integer v : graph.adjacentNodes(u)) {
                ImmutableSet<ScotlandYard.Transport> edge = graph.edgeValueOrDefault(u, v, ImmutableSet.of());
                if (!edge.contains(transport)) {continue;}

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
        PriorityQueue<int[]> pq = new PriorityQueue<>(Comparator.comparingInt(v -> v[0]));
        pq.add(new int[]{0, source});

        while (!pq.isEmpty()) {
            int[] entry = pq.poll();
            int d = entry[0], u = entry[1];
            if (d > minDist[u]) continue;

            for (int v : graph.adjacentNodes(u)) {
                if (!graph.edgeValueOrDefault(u, v, ImmutableSet.of()).isEmpty()) {
                    int nd = d + 1;
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
