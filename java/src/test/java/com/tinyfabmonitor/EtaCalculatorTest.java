package com.tinyfabmonitor;

import org.junit.Test;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import static org.junit.Assert.*;

public class EtaCalculatorTest {
    @Test public void level40RAnchorsPureREtaAndOneSampleIsEnough() {
        Models.TaskView start = task("START", "40", "R", 1000L, 0L);
        Models.TaskView root = task("ROOT", "41", "W", null, 120L);
        Models.DagEta eta = calculate("ROOT", Arrays.asList(start, root), Arrays.asList(edge("ROOT", "START")), 61000L);
        assertTrue(eta.available); assertEquals(121000L, eta.estimatedCompletion.getTime());
        assertEquals("低置信度", eta.confidence); assertEquals(Arrays.asList("START", "ROOT"), eta.criticalPath);
    }

    @Test public void filtersImpossibleSamplesAfterTaskIsReady() {
        Models.TaskView start = task("START", "40", "R", 1000L, 0L);
        Models.TaskView root = task("ROOT", "41", "I", 50000L, 30L, 90L, 180L);
        Models.DagEta eta = calculate("ROOT", Arrays.asList(start, root), Arrays.asList(edge("ROOT", "START")), 71000L);
        assertTrue(eta.available); assertEquals(136000L, eta.estimatedCompletion.getTime());
        assertEquals(2, eta.conditionalSampleCount); // 30 is impossible after 70 seconds; 90 and 180 remain.
        assertEquals(181000L, eta.conservativeCompletion.getTime());
    }

    @Test public void exhaustedHistoryCancelsPointEtaAndUsesNextRefreshCheckpoint() {
        Models.TaskView start = task("START", "40", "R", 1000L, 0L);
        Models.TaskView root = task("ROOT", "41", "W", null, 30L, 60L);
        Models.DagEta eta = EtaCalculator.calculate("ROOT", Arrays.asList(start, root), Arrays.asList(edge("ROOT", "START")),
            new Date(121000L), new Date(180000L), 30);
        assertFalse(eta.available); assertTrue(eta.lowerBound); assertTrue(eta.detail.contains("超过历史最大值"));
        assertEquals(new Date(180000L), eta.taskEtas.get("ROOT").nextCheckpoint);
    }

    @Test public void longestParallelPathWinsAndTiesAreRetained() {
        Models.TaskView a = task("A", "40", "R", 1000L, 0L);
        Models.TaskView b = task("B", "40", "R", 1000L, 0L);
        Models.TaskView x = task("X", "41", "W", null, 100L);
        Models.TaskView y = task("Y", "41", "W", null, 100L);
        Models.TaskView root = task("ROOT", "42", "W", null, 10L);
        Models.DagEta eta = calculate("ROOT", Arrays.asList(a,b,x,y,root),
            Arrays.asList(edge("X","A"), edge("Y","B"), edge("ROOT","X"), edge("ROOT","Y")), 1000L);
        assertTrue(eta.available); assertEquals(111000L, eta.estimatedCompletion.getTime());
        assertEquals(2, eta.criticalPaths.size());
    }

    @Test public void level40MustHaveRealRAndLevelBelow40IsNeverUsed() {
        Models.TaskView bad = task("START", "40", "W", null, 0L);
        Models.TaskView below = task("POLL", "20", "R", 500L, 0L);
        Models.TaskView root = task("ROOT", "41", "W", null, 60L);
        Models.DagEta eta = calculate("ROOT", Arrays.asList(bad, below, root),
            Arrays.asList(edge("START", "POLL"), edge("ROOT", "START")), 1000L);
        assertFalse(eta.available); assertTrue(eta.detail.contains("Level 40")); assertFalse(eta.detail.contains("POLL"));
    }

    @Test public void iTimeIsIgnoredAndAnomalyCancelsNecessaryPath() {
        Models.TaskView start = task("START", "40", "R", 1000L, 0L);
        Models.TaskView running = task("RUN", "41", "I", 50000L, 100L); running.startedAt = new Date(50000L);
        Models.DagEta eta = calculate("RUN", Arrays.asList(start, running), Arrays.asList(edge("RUN", "START")), 1000L);
        assertTrue(eta.available); assertEquals(101000L, eta.estimatedCompletion.getTime());
        running.status = "E";
        Models.DagEta blocked = calculate("RUN", Arrays.asList(start, running), Arrays.asList(edge("RUN", "START")), 1000L);
        assertFalse(blocked.available); assertTrue(blocked.detail.contains("当前为 E"));
    }

    @Test public void validRAtAnyLevelAbove40IsAnExactAnchor() {
        Models.TaskView boundary = task("BOUNDARY", "40", "W", null, 0L);
        Models.TaskView completedMiddle = task("MIDDLE", "46", "R", 50000L, 0L);
        Models.TaskView root = task("ROOT", "58", "W", null, 30L);
        Models.DagEta eta = calculate("ROOT", Arrays.asList(boundary, completedMiddle, root),
            Arrays.asList(edge("MIDDLE", "BOUNDARY"), edge("ROOT", "MIDDLE")), 50000L);
        assertTrue(eta.available);
        assertEquals(80000L, eta.estimatedCompletion.getTime());
        assertEquals(Arrays.asList("MIDDLE", "ROOT"), eta.criticalPath);
        assertFalse(eta.detail.contains("BOUNDARY"));
    }

    private static Models.DagEta calculate(String root, List<Models.TaskView> tasks, List<Models.Dependency> edges, long now) {
        return EtaCalculator.calculate(root, tasks, edges, new Date(now), new Date(now + 60000L), 30);
    }
    private static Models.Dependency edge(String owner, String dependency) { return new Models.Dependency(owner, dependency); }
    private static Models.TaskView task(String fab, String level, String status, Long act, long... samples) {
        Models.TaskView task = new Models.TaskView(); task.processDate = "20260103"; task.threadId = "T"; task.fabId = fab;
        task.levelNo = level; task.status = status; task.actTime = act == null ? null : new Date(act);
        for (long sample : samples) if (sample > 0) task.readyToCompleteSamples.add(sample);
        task.readyToCompleteSampleCount = task.readyToCompleteSamples.size(); return task;
    }
}
