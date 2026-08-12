package com.tinyfabmonitor;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

final class DependencyGraphBuilder {
    interface Lookup {
        List<Models.Dependency> upstream(String fabId) throws SQLException;
        List<Models.Dependency> downstream(String dependencyId) throws SQLException;
    }

    private static class AtDepth {
        final String fabId;
        final int depth;
        AtDepth(String fabId, int depth) { this.fabId = fabId; this.depth = depth; }
    }

    private DependencyGraphBuilder() {}

    static List<Models.Dependency> build(String rootFabId, Set<String> currentDateFabIds, int upstreamDepth, int downstreamDepth, Lookup lookup) throws SQLException {
        Map<String, String> allowed = new LinkedHashMap<String, String>();
        for (String id : currentDateFabIds) if (id != null && !id.trim().isEmpty()) allowed.put(normalize(id), id.trim());
        String root = allowed.get(normalize(rootFabId));
        if (root == null || (upstreamDepth <= 0 && downstreamDepth <= 0)) return new ArrayList<Models.Dependency>();

        Map<String, Models.Dependency> edges = new LinkedHashMap<String, Models.Dependency>();
        if (upstreamDepth > 0) traverse(root, upstreamDepth, true, allowed, edges, lookup);
        if (downstreamDepth > 0) traverse(root, downstreamDepth, false, allowed, edges, lookup);
        return new ArrayList<Models.Dependency>(edges.values());
    }

    private static void traverse(String root, int maximumDepth, boolean upstream, Map<String, String> allowed,
                                 Map<String, Models.Dependency> edges, Lookup lookup) throws SQLException {
        Queue<AtDepth> queue = new ArrayDeque<AtDepth>();
        Set<String> expanded = new LinkedHashSet<String>();
        queue.add(new AtDepth(root, 0));
        while (!queue.isEmpty()) {
            AtDepth current = queue.remove();
            String normalizedCurrent = normalize(current.fabId);
            if (current.depth >= maximumDepth || !expanded.add(normalizedCurrent)) continue;
            List<Models.Dependency> found = upstream ? lookup.upstream(current.fabId) : lookup.downstream(current.fabId);
            for (Models.Dependency raw : found) {
                String owner = allowed.get(normalize(raw.fabId));
                String dependency = allowed.get(normalize(raw.dependencyId));
                // 非当前业务日期节点不显示，也不继续穿过该节点递归。
                if (owner == null || dependency == null) continue;
                String edgeKey = normalize(dependency) + "->" + normalize(owner);
                if (!edges.containsKey(edgeKey)) edges.put(edgeKey, new Models.Dependency(owner, dependency));
                String next = upstream ? dependency : owner;
                if (!expanded.contains(normalize(next))) queue.add(new AtDepth(next, current.depth + 1));
            }
        }
    }

    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
}
