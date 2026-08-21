package com.tinyfabmonitor;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TimingStatisticsTest {
    @Test public void usesMedianAndBuildsReadyToRFromPersistedFinalRTimes() {
        Models.TaskView a = view("20260103", "A", "41", "R", 5000000);
        Models.TaskView b = view("20260103", "B", "41", "W", 0);
        List<Models.TrackedTask> history = Arrays.asList(
            tracked("20260101", "A", "41", 1000000), tracked("20260101", "B", "41", 1120000),
            tracked("20260102", "A", "41", 2000000), tracked("20260102", "B", "41", 2600000));
        List<Models.RunRecord> runs = Arrays.asList(run("20260101", "B", 0, 10), run("20260102", "B", 0, 1000), run("20251231", "B", 0, 20));
        TimingStatistics.apply(Arrays.asList(a, b), history, runs,
            Arrays.asList(new Models.Dependency("B", "A")));
        assertEquals(360L, b.readyToCompleteTypicalSeconds);
        assertEquals(2, b.readyToCompleteSampleCount);
        assertEquals(20L, b.executionTypicalSeconds);
        assertEquals(3, b.executionTypicalSampleCount);
        assertFalse(b.readinessPartial);
    }

    @Test public void level20StopsItsBranchButKeepsOtherBranchAsPartialEvidence() {
        Models.TaskView poll = view("20260103", "POLL", "20", "R", 3000000);
        Models.TaskView a = view("20260103", "A", "41", "R", 4000000);
        Models.TaskView b = view("20260103", "B", "41", "R", 4500000);
        List<Models.TrackedTask> history = Arrays.asList(
            tracked("20260101", "POLL", "20", 900000), tracked("20260101", "A", "41", 1000000), tracked("20260101", "B", "41", 1300000));
        TimingStatistics.apply(Arrays.asList(poll, a, b), history, Collections.<Models.RunRecord>emptyList(),
            Arrays.asList(new Models.Dependency("B", "POLL"), new Models.Dependency("B", "A")));
        assertEquals(4000000L, b.readinessAt.getTime());
        assertEquals(Long.valueOf(500), b.readyToCompleteSeconds);
        assertEquals(300L, b.readyToCompleteTypicalSeconds);
        assertTrue(b.readinessPartial);
        assertTrue(b.hasLevel20Upstream);
    }

    @Test public void confidenceReflectsAvailableSampleCount() {
        assertEquals("无历史样本", TimingStatistics.confidence(0));
        assertEquals("低置信度", TimingStatistics.confidence(1));
        assertEquals("中等置信度", TimingStatistics.confidence(2));
        assertEquals("较高置信度", TimingStatistics.confidence(5));
    }

    private static Models.TaskView view(String date, String fab, String level, String status, long millis) {
        Models.TaskView task = new Models.TaskView(); task.processDate = date; task.threadId = "T"; task.levelNo = level;
        task.fabId = fab; task.status = status;
        if (millis > 0) task.actTime = new Date(millis);
        return task;
    }

    private static Models.TrackedTask tracked(String date, String fab, String level, long millis) {
        Models.TrackedTask task = new Models.TrackedTask(); task.key = new Models.TaskKey(date, "T", level, fab);
        task.lastStatus = "R"; task.lastActTime = new Date(millis); return task;
    }

    private static Models.RunRecord run(String date, String fab, long start, long finish) {
        Models.RunRecord run = new Models.RunRecord(); run.task = new Models.TaskKey(date, "T", "41", fab);
        run.startedAt = new Date(start * 1000L); run.completedAt = new Date(finish * 1000L); run.durationSeconds = finish - start;
        return run;
    }
}
