package com.tinyfabmonitor;

import org.junit.Test;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EtaCalculatorTest {
    @Test public void usesValidRTimeAsPathAnchorWithOneHistoryRun() {
        Models.TaskView anchor = task("A", "R", 1000L, false, 0, 0);
        Models.TaskView waiting = task("B", "W", null, false, 60, 1);
        Models.TaskView root = task("ROOT", "W", null, false, 120, 1);
        Models.DagEta eta = calculate("ROOT", Arrays.asList(anchor, waiting, root),
            Arrays.asList(edge("B", "A"), edge("ROOT", "B")));
        assertTrue(eta.available);
        assertEquals(181000L, eta.estimatedCompletion.getTime());
        assertEquals(Arrays.asList("A", "B", "ROOT"), eta.criticalPath);
    }

    @Test public void placeholderRContinuesUpstreamAndAddsItsAverage() {
        Models.TaskView anchor = task("A", "R", 1000L, false, 0, 0);
        Models.TaskView placeholder = task("B", "R", null, true, 30, 1);
        Models.TaskView root = task("ROOT", "W", null, false, 60, 1);
        Models.DagEta eta = calculate("ROOT", Arrays.asList(anchor, placeholder, root),
            Arrays.asList(edge("B", "A"), edge("ROOT", "B")));
        assertTrue(eta.available);
        assertEquals(91000L, eta.estimatedCompletion.getTime());
    }

    @Test public void selectsThePathWithLatestEstimatedFinish() {
        Models.TaskView a = task("A", "R", 0L, false, 0, 0);
        Models.TaskView b = task("B", "W", null, false, 100, 1);
        Models.TaskView c = task("C", "R", 90000L, false, 0, 0);
        Models.TaskView d = task("D", "W", null, false, 30, 1);
        Models.TaskView root = task("ROOT", "W", null, false, 10, 1);
        Models.DagEta eta = calculate("ROOT", Arrays.asList(a, b, c, d, root),
            Arrays.asList(edge("B", "A"), edge("ROOT", "B"), edge("D", "C"), edge("ROOT", "D")));
        assertTrue(eta.available);
        assertEquals(130000L, eta.estimatedCompletion.getTime());
        assertEquals(Arrays.asList("C", "D", "ROOT"), eta.criticalPath);
    }

    @Test public void validIStartOverridesOlderUpstreamAndStopsThere() {
        Models.TaskView running = task("I", "I", 5000L, false, 60, 1); running.startedAt = new Date(5000L);
        Models.TaskView root = task("ROOT", "W", null, false, 30, 1);
        Models.DagEta eta = calculate("ROOT", Arrays.asList(running, root), Arrays.asList(edge("ROOT", "I")));
        assertTrue(eta.available);
        assertEquals(95000L, eta.estimatedCompletion.getTime());
    }

    @Test public void anomalyAndCyclesReturnExplicitUnavailableResult() {
        Models.TaskView blocked = task("BLOCKED", "E", 1000L, false, 60, 1);
        Models.TaskView root = task("ROOT", "W", null, false, 30, 1);
        Models.DagEta blockedEta = calculate("ROOT", Arrays.asList(blocked, root), Arrays.asList(edge("ROOT", "BLOCKED")));
        assertFalse(blockedEta.available); assertTrue(blockedEta.detail.contains("BLOCKED"));

        Models.TaskView a = task("A", "W", null, false, 30, 1);
        Models.DagEta cycle = calculate("ROOT", Arrays.asList(a, root), Arrays.asList(edge("ROOT", "A"), edge("A", "ROOT")));
        assertFalse(cycle.available); assertTrue(cycle.detail.contains("循环依赖"));
    }

    private static Models.DagEta calculate(String root, List<Models.TaskView> tasks, List<Models.Dependency> edges) {
        return EtaCalculator.calculate(root, tasks, edges, new Date(0));
    }

    private static Models.Dependency edge(String owner, String dependency) { return new Models.Dependency(owner, dependency); }

    private static Models.TaskView task(String fab, String status, Long actTime, boolean placeholder, long average, int count) {
        Models.TaskView task = new Models.TaskView(); task.fabId = fab; task.status = status;
        task.actTime = actTime == null ? null : new Date(actTime); task.actTimePlaceholder = placeholder;
        task.averageDurationSeconds = average; task.completedRunCount = count; return task;
    }
}
