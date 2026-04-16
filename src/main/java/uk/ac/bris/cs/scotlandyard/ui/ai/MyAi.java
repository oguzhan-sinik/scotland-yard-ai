package uk.ac.bris.cs.scotlandyard.ui.ai;

import java.util.*;
import java.util.concurrent.TimeUnit;

import com.google.common.graph.ImmutableValueGraph;
import com.google.common.collect.ImmutableSet;
import jakarta.annotation.Nonnull;
import io.atlassian.fugue.Pair;
import uk.ac.bris.cs.scotlandyard.model.*;

public class MyAi implements Ai {

    @Nonnull @Override public String name() { return "biTERIM"; }

    // -------------------------------------------------------------------------
    // Role-assignment state (persists across detective calls within one round).
    // -------------------------------------------------------------------------
    // Before the first detective moves in a round we choose a target node for
    // each detective — a position in MrX's escape neighbourhood that we want
    // that detective to cover.  Every detective's ISMCTS search then biases its
    // rollouts toward that target, so the team naturally fans out rather than
    // all chasing the same path.
    //
    // The assignment is recomputed each time MrX plays (needsRoleAssignment=true)
    // so it adapts to MrX's new position after every reveal / move sequence.
    private boolean               needsRoleAssignment = true;
    private Map<Piece, Integer>   roleTargets         = new HashMap<>();

    @Nonnull @Override public Move pickMove(
            @Nonnull Board board,
            Pair<Long, TimeUnit> timeoutPair) {

        boolean areWeMrX = board.getAvailableMoves().asList().get(0).commencedBy()
                == Piece.MrX.MRX;

        if (areWeMrX) {
            needsRoleAssignment = true;   // force fresh assignment next detective round
            return minimaxAlg.pickBestMove(board, timeoutPair.left());
        }

        // First detective of this round: compute the team's target assignments.
        if (needsRoleAssignment) {
            computeRoleTargets(board);
            needsRoleAssignment = false;
        }

        IsmctsNode root = new IsmctsNode(null, null);
        return Ismcts.pickMove(board, timeoutPair, root, roleTargets);
    }

    // -------------------------------------------------------------------------
    // computeRoleTargets
    // -------------------------------------------------------------------------
    // Assigns each detective a unique target node in MrX's 1-/2-step escape
    // neighbourhood, so the team covers different exits rather than converging
    // on the same one.
    //
    // Algorithm:
    //   1. Estimate MrX's position from the information set (median of posLocs).
    //   2. Collect candidate target nodes: all nodes 1 or 2 hops from MrX.
    //   3. Run Dijkstra from each candidate target (once each).
    //   4. Build every (detective, target) distance pair and sort ascending.
    //   5. Greedy assignment: commit the globally shortest unassigned pair first.
    //      This approximates a minimum-cost matching without full Hungarian.
    private void computeRoleTargets(Board board) {
        roleTargets.clear();

        List<Integer> posLocs = Ismcts.forwardPass(board);
        if (posLocs.isEmpty()) return;

        // Representative MrX location: median of the information set
        int mrXEstimate = posLocs.get(posLocs.size() / 2);

        ImmutableValueGraph<Integer, ImmutableSet<ScotlandYard.Transport>> graph =
                board.getSetup().graph;
        int maxNode = dijkstraAlgorithm.maxNode(graph);

        // Candidate targets: 1-step then 2-step escapes
        Set<Integer> step1 = new HashSet<>(graph.adjacentNodes(mrXEstimate));
        Set<Integer> candidates = new LinkedHashSet<>(step1);
        for (int e1 : step1) {
            for (int e2 : graph.adjacentNodes(e1)) {
                if (e2 != mrXEstimate && !step1.contains(e2)) candidates.add(e2);
            }
        }
        List<Integer> targetList = new ArrayList<>(candidates);
        if (targetList.isEmpty()) return;

        // Gather detectives
        List<Piece.Detective> detectives = new ArrayList<>();
        for (Piece p : board.getPlayers()) {
            if (p.isDetective()) detectives.add((Piece.Detective) p);
        }
        if (detectives.isEmpty()) return;

        // Dijkstra from each candidate target → distances to every node
        List<int[]> distFromTarget = new ArrayList<>();   // index matches targetList
        for (int target : targetList) {
            distFromTarget.add(dijkstraAlgorithm.mergeDist(graph, target, maxNode));
        }

        // Build all (detIdx, targetIdx, distance) triples
        List<int[]> pairs = new ArrayList<>();
        for (int d = 0; d < detectives.size(); d++) {
            int detLoc = board.getDetectiveLocation(detectives.get(d)).orElse(-1);
            if (detLoc < 0) continue;
            for (int t = 0; t < targetList.size(); t++) {
                int[] dist = distFromTarget.get(t);
                int distance = (dist[detLoc] == Integer.MAX_VALUE) ? 1000 : dist[detLoc];
                pairs.add(new int[]{d, t, distance});
            }
        }
        pairs.sort((a, b) -> Integer.compare(a[2], b[2]));

        // Greedy assignment: closest unassigned (detective, target) pair first
        Set<Integer> assignedDets     = new HashSet<>();
        Set<Integer> assignedTargets  = new HashSet<>();
        for (int[] pair : pairs) {
            int dIdx = pair[0], tIdx = pair[1];
            if (assignedDets.contains(dIdx) || assignedTargets.contains(tIdx)) continue;
            roleTargets.put(detectives.get(dIdx), targetList.get(tIdx));
            assignedDets.add(dIdx);
            assignedTargets.add(tIdx);
            if (assignedDets.size() == detectives.size()) break;
        }
    }
}
