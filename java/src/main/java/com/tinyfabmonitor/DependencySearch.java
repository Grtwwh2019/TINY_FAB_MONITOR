package com.tinyfabmonitor;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class DependencySearch {
    interface BatchLookup {
        List<Models.Dependency> upstream(Set<String> fabIds) throws SQLException;
        List<Models.Dependency> downstream(Set<String> dependencyIds) throws SQLException;
    }

    private DependencySearch() {}

    static BatchLookup inMemory(final List<Models.Dependency> dependencies) {
        final Map<String, List<Models.Dependency>> byOwner = new LinkedHashMap<String, List<Models.Dependency>>();
        final Map<String, List<Models.Dependency>> byDependency = new LinkedHashMap<String, List<Models.Dependency>>();
        for (Models.Dependency edge : dependencies) {
            add(byOwner, normalize(edge.fabId), edge);
            add(byDependency, normalize(edge.dependencyId), edge);
        }
        return new BatchLookup() {
            public List<Models.Dependency> upstream(Set<String> fabIds) {
                return collect(byOwner, fabIds);
            }
            public List<Models.Dependency> downstream(Set<String> dependencyIds) {
                return collect(byDependency, dependencyIds);
            }
        };
    }

    private static void add(Map<String, List<Models.Dependency>> index, String key, Models.Dependency edge) {
        List<Models.Dependency> bucket = index.get(key);
        if (bucket == null) { bucket = new ArrayList<Models.Dependency>(); index.put(key, bucket); }
        bucket.add(edge);
    }

    private static List<Models.Dependency> collect(Map<String, List<Models.Dependency>> index, Set<String> ids) {
        Map<String, Models.Dependency> unique = new LinkedHashMap<String, Models.Dependency>();
        for (String id : ids) {
            List<Models.Dependency> values = index.get(normalize(id));
            if (values == null) continue;
            for (Models.Dependency edge : values) unique.put(normalize(edge.dependencyId) + "->" + normalize(edge.fabId), edge);
        }
        return new ArrayList<Models.Dependency>(unique.values());
    }

    static Models.DependencyAnalysis load(String rootFabId, List<Models.OracleTask> tasks, int upstreamDepth,
                                          int downstreamDepth, BatchLookup lookup) throws SQLException {
        Map<String, Models.OracleTask> tasksByFab = latestTasksByFab(tasks);
        String root = canonical(tasksByFab, rootFabId);
        Models.DependencyAnalysis result = new Models.DependencyAnalysis();
        if (root == null) return result;

        Map<String, Models.Dependency> display = new LinkedHashMap<String, Models.Dependency>();
        traverseDisplay(root, upstreamDepth, true, tasksByFab, display, lookup);
        traverseDisplay(root, downstreamDepth, false, tasksByFab, display, lookup);
        result.displayDependencies.addAll(display.values());

        Map<String, Models.Dependency> eta = new LinkedHashMap<String, Models.Dependency>();
        traverseEta(root, tasksByFab, eta, lookup);
        result.etaUpstreamDependencies.addAll(eta.values());
        return result;
    }

    private static void traverseDisplay(String root, int maximumDepth, boolean upstream,
                                        Map<String, Models.OracleTask> tasksByFab,
                                        Map<String, Models.Dependency> edges, BatchLookup lookup) throws SQLException {
        if (maximumDepth <= 0) return;
        Set<String> frontier = new LinkedHashSet<String>(); frontier.add(root);
        Set<String> expanded = new LinkedHashSet<String>();
        for (int depth = 0; depth < maximumDepth && !frontier.isEmpty(); depth++) {
            Set<String> query = unexpanded(frontier, expanded);
            if (query.isEmpty()) break;
            List<Models.Dependency> found = upstream ? lookup.upstream(query) : lookup.downstream(query);
            Set<String> next = new LinkedHashSet<String>();
            for (Models.Dependency raw : found) {
                String owner = canonical(tasksByFab, raw.fabId), dependency = canonical(tasksByFab, raw.dependencyId);
                if (owner == null || dependency == null) continue;
                put(edges, owner, dependency);
                next.add(upstream ? dependency : owner);
            }
            frontier = next;
        }
    }

    private static void traverseEta(String root, Map<String, Models.OracleTask> tasksByFab,
                                    Map<String, Models.Dependency> edges, BatchLookup lookup) throws SQLException {
        Set<String> frontier = new LinkedHashSet<String>(); frontier.add(root);
        Set<String> expanded = new LinkedHashSet<String>();
        while (!frontier.isEmpty()) {
            Set<String> query = new LinkedHashSet<String>();
            for (String fab : frontier) {
                Models.OracleTask task = tasksByFab.get(normalize(fab));
                if (shouldExpandForEta(task) && expanded.add(normalize(fab))) query.add(fab);
            }
            if (query.isEmpty()) break;
            List<Models.Dependency> found = lookup.upstream(query);
            Set<String> next = new LinkedHashSet<String>();
            for (Models.Dependency raw : found) {
                String owner = canonical(tasksByFab, raw.fabId), dependency = canonical(tasksByFab, raw.dependencyId);
                if (owner == null || dependency == null) continue;
                put(edges, owner, dependency);
                if (!expanded.contains(normalize(dependency))) next.add(dependency);
            }
            frontier = next;
        }
    }

    private static boolean shouldExpandForEta(Models.OracleTask task) {
        if (task == null) return false;
        if ("W".equalsIgnoreCase(task.status)) return true;
        return "R".equalsIgnoreCase(task.status) && (task.actTime == null || task.actTimePlaceholder);
    }

    private static Set<String> unexpanded(Collection<String> values, Set<String> expanded) {
        Set<String> result = new LinkedHashSet<String>();
        for (String value : values) if (expanded.add(normalize(value))) result.add(value);
        return result;
    }

    private static void put(Map<String, Models.Dependency> edges, String owner, String dependency) {
        String key = normalize(dependency) + "->" + normalize(owner);
        if (!edges.containsKey(key)) edges.put(key, new Models.Dependency(owner, dependency));
    }

    private static Map<String, Models.OracleTask> latestTasksByFab(List<Models.OracleTask> tasks) {
        Map<String, Models.OracleTask> result = new LinkedHashMap<String, Models.OracleTask>();
        for (Models.OracleTask task : tasks) {
            if (task == null || task.fabId == null || task.fabId.trim().isEmpty()) continue;
            String key = normalize(task.fabId);
            Models.OracleTask previous = result.get(key);
            if (previous == null || previous.actTime == null || (task.actTime != null && task.actTime.after(previous.actTime))) result.put(key, task);
        }
        return result;
    }

    private static String canonical(Map<String, Models.OracleTask> tasksByFab, String fabId) {
        Models.OracleTask task = tasksByFab.get(normalize(fabId));
        return task == null ? null : task.fabId.trim();
    }

    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
}
