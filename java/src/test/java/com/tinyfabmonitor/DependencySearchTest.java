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
            task("ROOT", "41", "W", null, false), task("WAITING", "42", "W", null, false),
            task("PLACEHOLDER_R", "43", "R", null, true), task("DONE", "41", "R", 1000L, false),
            task("ANCHOR", "41", "R", 2000L, false));
        Models.DependencyAnalysis result = DependencySearch.load("ROOT", tasks, 0, 0, lookup);
        assertEquals(0, result.displayDependencies.size());
        assertEquals(4, result.etaUpstreamDependencies.size());
        assertEquals(2, lookup.upstreamBatches.size());
        assertEquals(new LinkedHashSet<String>(Arrays.asList("ROOT")), lookup.upstreamBatches.get(0));
        assertEquals(new LinkedHashSet<String>(Arrays.asList("WAITING", "PLACEHOLDER_R")), lookup.upstreamBatches.get(1));
        assertFalse(lookup.allUpstreamQueries().contains("DONE"));
        assertFalse(lookup.allUpstreamQueries().contains("ANCHOR"));
    }

    @Test public void iAndNon40UpstreamsExpandRecursivelyUntilLevel40() throws Exception {
        FakeBatchLookup lookup = new FakeBatchLookup();
        lookup.edge("ROOT", "MID"); lookup.edge("MID", "WAIT"); lookup.edge("WAIT", "BOUNDARY");
        lookup.edge("BOUNDARY", "POLL");
        List<Models.OracleTask> tasks = Arrays.asList(
            task("ROOT", "58", "I", 9000L, false), task("MID", "46", "I", 8000L, false),
            task("WAIT", "41", "W", null, false), task("BOUNDARY", "40", "R", 1000L, false),
            task("POLL", "20", "R", 500L, false));
        Models.DependencyAnalysis result = DependencySearch.load("ROOT", tasks, 0, 0, lookup);
        assertEquals(3, result.etaUpstreamDependencies.size());
        assertTrue(lookup.allUpstreamQueries().contains("ROOT"));
        assertTrue(lookup.allUpstreamQueries().contains("MID"));
        assertTrue(lookup.allUpstreamQueries().contains("WAIT"));
        assertFalse(lookup.allUpstreamQueries().contains("BOUNDARY"));
        assertFalse(lookup.allUpstreamQueries().contains("POLL"));

        List<Models.TaskView> views = Arrays.asList(
            view("ROOT", "58", "I", 10L, null), view("MID", "46", "I", 20L, null),
            view("WAIT", "41", "W", 30L, null), view("BOUNDARY", "40", "R", 0L, 1000L),
            view("POLL", "20", "R", 0L, 500L));
        Models.DagEta dagEta = EtaCalculator.calculate("ROOT", views, result.etaUpstreamDependencies,
            new Date(1000L), new Date(61000L), 30);
        Models.DagEta analysisEta = EtaCalculator.calculate("ROOT", views, lookup.allEdges(),
            new Date(1000L), new Date(61000L), 30);
        assertTrue(dagEta.available);
        assertEquals(61000L, dagEta.estimatedCompletion.getTime());
        assertEquals(analysisEta.estimatedCompletion, dagEta.estimatedCompletion);
    }

    private static Models.OracleTask task(String fab, String level, String status, Long actTime, boolean placeholder) {
        Models.OracleTask task = new Models.OracleTask(); task.fabId = fab; task.levelNo = level; task.status = status;
        task.actTime = actTime == null ? null : new Date(actTime); task.actTimePlaceholder = placeholder; return task;
    }

    private static Models.TaskView view(String fab, String level, String status, long sample, Long actTime) {
        Models.TaskView task = new Models.TaskView(); task.processDate = "20260102"; task.threadId = "T";
        task.fabId = fab; task.levelNo = level; task.status = status;
        task.actTime = actTime == null ? null : new Date(actTime);
        if (sample > 0) task.readyToCompleteSamples.add(sample);
        task.readyToCompleteSampleCount = task.readyToCompleteSamples.size(); return task;
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
        List<Models.Dependency> allEdges() { List<Models.Dependency> values = new ArrayList<Models.Dependency>(); for (List<Models.Dependency> edges : byOwner.values()) values.addAll(edges); return values; }
        private List<Models.Dependency> collect(Set<String> ids, Map<String, List<Models.Dependency>> source) {
            List<Models.Dependency> result = new ArrayList<Models.Dependency>();
            for (String id : ids) if (source.containsKey(id)) result.addAll(source.get(id));
            return result;
        }
    }
}
