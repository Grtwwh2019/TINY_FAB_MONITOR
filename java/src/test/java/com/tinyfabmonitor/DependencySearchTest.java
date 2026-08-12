package com.tinyfabmonitor;

import org.junit.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DependencySearchTest {
    @Test public void inMemoryLookupReusesOneFullDependencySetForBothDirections() throws Exception {
        List<Models.Dependency> all = Arrays.asList(new Models.Dependency("B", "A"), new Models.Dependency("C", "B"));
        DependencySearch.BatchLookup lookup = DependencySearch.inMemory(all);
        assertEquals(1, lookup.upstream(new LinkedHashSet<String>(Arrays.asList("B"))).size());
        assertEquals(1, lookup.downstream(new LinkedHashSet<String>(Arrays.asList("B"))).size());
    }
    @Test public void etaIgnoresDisplayDepthAndBatchesOnlyExpandableNodes() throws Exception {
        FakeBatchLookup lookup = new FakeBatchLookup();
        lookup.edge("ROOT", "WAITING"); lookup.edge("ROOT", "PLACEHOLDER_R");
        lookup.edge("WAITING", "DONE"); lookup.edge("PLACEHOLDER_R", "ANCHOR");
        List<Models.OracleTask> tasks = Arrays.asList(
            task("ROOT", "W", null, false), task("WAITING", "W", null, false),
            task("PLACEHOLDER_R", "R", null, true), task("DONE", "R", 1000L, false),
            task("ANCHOR", "R", 2000L, false));
        Models.DependencyAnalysis result = DependencySearch.load("ROOT", tasks, 0, 0, lookup);
        assertEquals(0, result.displayDependencies.size());
        assertEquals(4, result.etaUpstreamDependencies.size());
        assertEquals(2, lookup.upstreamBatches.size());
        assertEquals(new LinkedHashSet<String>(Arrays.asList("ROOT")), lookup.upstreamBatches.get(0));
        assertEquals(new LinkedHashSet<String>(Arrays.asList("WAITING", "PLACEHOLDER_R")), lookup.upstreamBatches.get(1));
        assertFalse(lookup.allUpstreamQueries().contains("DONE"));
        assertFalse(lookup.allUpstreamQueries().contains("ANCHOR"));
    }

    private static Models.OracleTask task(String fab, String status, Long actTime, boolean placeholder) {
        Models.OracleTask task = new Models.OracleTask(); task.fabId = fab; task.status = status;
        task.actTime = actTime == null ? null : new Date(actTime); task.actTimePlaceholder = placeholder; return task;
    }

    private static class FakeBatchLookup implements DependencySearch.BatchLookup {
        final Map<String, List<Models.Dependency>> byOwner = new LinkedHashMap<String, List<Models.Dependency>>();
        final Map<String, List<Models.Dependency>> byDependency = new LinkedHashMap<String, List<Models.Dependency>>();
        final List<Set<String>> upstreamBatches = new ArrayList<Set<String>>();
        void edge(String owner, String dependency) {
            Models.Dependency edge = new Models.Dependency(owner, dependency);
            byOwner.computeIfAbsent(owner, key -> new ArrayList<Models.Dependency>()).add(edge);
            byDependency.computeIfAbsent(dependency, key -> new ArrayList<Models.Dependency>()).add(edge);
        }
        public List<Models.Dependency> upstream(Set<String> ids) throws SQLException {
            upstreamBatches.add(new LinkedHashSet<String>(ids)); return collect(ids, byOwner);
        }
        public List<Models.Dependency> downstream(Set<String> ids) throws SQLException { return collect(ids, byDependency); }
        Set<String> allUpstreamQueries() { Set<String> values = new LinkedHashSet<String>(); for (Set<String> batch : upstreamBatches) values.addAll(batch); return values; }
        private List<Models.Dependency> collect(Set<String> ids, Map<String, List<Models.Dependency>> source) {
            List<Models.Dependency> result = new ArrayList<Models.Dependency>();
            for (String id : ids) if (source.containsKey(id)) result.addAll(source.get(id));
            return result;
        }
    }
}
