package com.tinyfabmonitor;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class MainFrameTest {
    @Test public void dagContainsAllCurrentDateStatusesEvenWithoutDependencies() {
        Models.Dashboard dashboard = new Models.Dashboard();
        dashboard.tasks = Arrays.asList(task("T1", "41", "FAB-W", "W"), task("T1", "42", "FAB-I", "I"), task("T2", "43", "FAB-R", "R"));
        java.util.Set<String> ids = ViewLogic.collectFabIds(dashboard);
        assertEquals(3, ids.size());
        assertTrue(ids.contains("FAB-W"));
        assertTrue(ids.contains("FAB-I"));
        assertTrue(ids.contains("FAB-R"));
    }

    @Test public void dagSearchCombinesThreadLevelAndFabInputs() {
        List<Models.TaskView> tasks = Arrays.asList(
            task("THREAD-A", "41", "FAB-100", "I"),
            task("THREAD-A", "42", "FAB-200", "W"),
            task("THREAD-B", "41", "FAB-100", "R")
        );
        List<Models.TaskView> matches = ViewLogic.findDagTasks(tasks, "thread-a", "41", "100");
        assertEquals(1, matches.size());
        assertEquals("FAB-100", matches.get(0).fabId);
    }

    @Test public void levelRangeIsInclusiveAndAllowsOneSidedBounds() {
        assertTrue(ViewLogic.levelInRange("41", 41, 69));
        assertTrue(ViewLogic.levelInRange("69", 41, 69));
        assertFalse(ViewLogic.levelInRange("40", 41, 69));
        assertTrue(ViewLogic.levelInRange("70", 70, null));
        assertTrue(ViewLogic.levelInRange("40", null, 40));
        assertFalse(ViewLogic.levelInRange("not-number", 41, 69));
    }

    @Test public void blankLevelBoundsMeanNoFiltering() {
        assertEquals(null, ViewLogic.parseLevelBound("  "));
        assertTrue(ViewLogic.levelInRange("any-value", null, null));
    }

    @Test public void threadFilterUsesCaseInsensitiveContainsMatching() {
        assertTrue(ViewLogic.threadContains("TEST_DAILY_001", "daily"));
        assertTrue(ViewLogic.threadContains("TEST_DAILY_001", "test_daily"));
        assertFalse(ViewLogic.threadContains("TEST_DAILY_001", "weekly"));
    }

    private static Models.TaskView task(String thread, String level, String fab, String status) {
        Models.TaskView task = new Models.TaskView(); task.threadId = thread; task.levelNo = level; task.fabId = fab; task.status = status; return task;
    }
}
