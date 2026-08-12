package com.tinyfabmonitor;

import org.junit.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DependencyGraphBuilderTest {
    @Test public void recursivelyLoadsUpstreamAndDownstreamAndStopsAtNonCurrentTask() throws Exception {
        FakeLookup lookup = new FakeLookup();
        lookup.edge("A", "B"); lookup.edge("B", "C"); lookup.edge("C", "D");
        lookup.edge("X", "A"); lookup.edge("Y", "X");
        lookup.edge("B", "OUTSIDE"); lookup.edge("BEYOND", "OUTSIDE");
        lookup.edge("OUTSIDE-DOWN", "A"); lookup.edge("BEYOND-DOWN", "OUTSIDE-DOWN");
        Set<String> current = new HashSet<String>(Arrays.asList("A", "B", "C", "D", "X", "Y", "BEYOND"));
        List<Models.Dependency> result = DependencyGraphBuilder.build("A", current, 15, 15, lookup);
        assertEquals(5, result.size());
        assertTrue(has(result, "A", "B")); assertTrue(has(result, "B", "C")); assertTrue(has(result, "C", "D"));
        assertTrue(has(result, "X", "A")); assertTrue(has(result, "Y", "X"));
        assertFalse(has(result, "B", "OUTSIDE")); assertFalse(lookup.upstreamQueries.contains("OUTSIDE"));
        assertFalse(has(result, "OUTSIDE-DOWN", "A")); assertFalse(lookup.downstreamQueries.contains("OUTSIDE-DOWN"));
    }

    @Test public void limitsBothDirectionsToFifteenLevelsAndHandlesCycles() throws Exception {
        FakeLookup lookup = new FakeLookup();
        Set<String> current = new HashSet<String>(); current.add("ROOT");
        String previous = "ROOT";
        for (int i = 1; i <= 18; i++) { String id = "U" + i; current.add(id); lookup.edge(previous, id); previous = id; }
        previous = "ROOT";
        for (int i = 1; i <= 18; i++) { String id = "D" + i; current.add(id); lookup.edge(id, previous); previous = id; }
        lookup.edge("U3", "ROOT");
        List<Models.Dependency> result = DependencyGraphBuilder.build("ROOT", current, 15, 15, lookup);
        assertTrue(has(result, "U14", "U15"));
        assertFalse(has(result, "U15", "U16"));
        assertTrue(has(result, "D15", "D14"));
        assertFalse(has(result, "D16", "D15"));
        assertTrue(result.size() <= 31); // 15 上游 + 15 下游，加一条已发现的循环边。
    }

    @Test public void upstreamAndDownstreamDepthsAreIndependent() throws Exception {
        FakeLookup lookup = new FakeLookup();
        lookup.edge("ROOT", "U1"); lookup.edge("U1", "U2");
        lookup.edge("D1", "ROOT"); lookup.edge("D2", "D1"); lookup.edge("D3", "D2");
        Set<String> current = new HashSet<String>(Arrays.asList("ROOT", "U1", "U2", "D1", "D2", "D3"));
        List<Models.Dependency> result = DependencyGraphBuilder.build("ROOT", current, 1, 2, lookup);
        assertTrue(has(result, "ROOT", "U1")); assertFalse(has(result, "U1", "U2"));
        assertTrue(has(result, "D1", "ROOT")); assertTrue(has(result, "D2", "D1")); assertFalse(has(result, "D3", "D2"));
    }

    private static boolean has(List<Models.Dependency> values, String owner, String dependency) {
        for (Models.Dependency value : values) if (owner.equals(value.fabId) && dependency.equals(value.dependencyId)) return true;
        return false;
    }

    private static class FakeLookup implements DependencyGraphBuilder.Lookup {
        final Map<String, List<Models.Dependency>> byOwner = new HashMap<String, List<Models.Dependency>>();
        final Map<String, List<Models.Dependency>> byDependency = new HashMap<String, List<Models.Dependency>>();
        final Set<String> upstreamQueries = new HashSet<String>();
        final Set<String> downstreamQueries = new HashSet<String>();
        void edge(String owner, String dependency) {
            Models.Dependency edge = new Models.Dependency(owner, dependency);
            byOwner.computeIfAbsent(owner, k -> new ArrayList<Models.Dependency>()).add(edge);
            byDependency.computeIfAbsent(dependency, k -> new ArrayList<Models.Dependency>()).add(edge);
        }
        public List<Models.Dependency> upstream(String fabId) throws SQLException { upstreamQueries.add(fabId); return byOwner.getOrDefault(fabId, new ArrayList<Models.Dependency>()); }
        public List<Models.Dependency> downstream(String dependencyId) throws SQLException { downstreamQueries.add(dependencyId); return byDependency.getOrDefault(dependencyId, new ArrayList<Models.Dependency>()); }
    }
}
