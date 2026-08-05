package com.tinyfabmonitor;

import org.junit.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MonitorServiceTest {
    @Test public void anomalyThenIRefreshesStartAndStateSurvivesReload() throws Exception {
        Path statePath = java.nio.file.Files.createTempDirectory("tiny-fab-test").resolve("state.json");
        StateStore store = new StateStore(statePath);
        store.update(state -> state.selectedProcessDate = "20251231");
        MonitorService monitor = new MonitorService(null, null, store, java.util.logging.Logger.getAnonymousLogger());
        long base = 1767175200000L;
        Models.TaskKey key = new Models.TaskKey("20251231", "7", "41", "FAB01");
        observe(monitor, key, "I", base);
        observe(monitor, key, "E", base + 5 * 60000L);
        observe(monitor, key, "B", base + 6 * 60000L);
        observe(monitor, key, "I", base + 8 * 60000L);
        observe(monitor, key, "R", base + 20 * 60000L);
        observe(monitor, key, "R", base + 20 * 60000L);

        Models.PersistedState reloaded = new StateStore(statePath).snapshot();
        assertEquals(1, reloaded.runs.size());
        Models.RunRecord run = reloaded.runs.get(0);
        assertEquals(base + 8 * 60000L, run.startedAt.getTime());
        assertEquals(12 * 60, run.durationSeconds);
        assertEquals(1, run.anomalyTimes.size());
    }

    @Test public void averageAppearsFromSecondCompletedRun() {
        Models.TaskKey key1 = new Models.TaskKey("20251231", "T", "42", "F");
        Models.TaskKey key2 = new Models.TaskKey("20260101", "T", "42", "F");
        Models.RunRecord first = completed(key1, 0, 600);
        Map<String, Models.GroupStat> one = MonitorService.buildGroupStats(Arrays.asList(first));
        assertEquals(1, one.get(key1.groupId()).count);
        assertEquals(0, one.get(key1.groupId()).average);
        Models.RunRecord second = completed(key2, 1000000, 720);
        Models.GroupStat stat = MonitorService.buildGroupStats(Arrays.asList(first, second)).get(key1.groupId());
        assertEquals(2, stat.count);
        assertEquals(660, stat.average);
    }

    @Test public void anomalyIsPersistedEvenWhenInitialIWasMissed() throws Exception {
        Path statePath = java.nio.file.Files.createTempDirectory("tiny-fab-anomaly").resolve("state.json");
        StateStore store = new StateStore(statePath);
        store.update(state -> state.selectedProcessDate = "20251231");
        MonitorService monitor = new MonitorService(null, null, store, java.util.logging.Logger.getAnonymousLogger());
        Models.TaskKey key = new Models.TaskKey("20251231", "7", "41", "FAB01");
        observe(monitor, key, "E", 1000L);
        observe(monitor, key, "B", 2000L);
        observe(monitor, key, "I", 3000L);
        observe(monitor, key, "R", 13000L);
        Models.RunRecord run = new StateStore(statePath).snapshot().runs.get(0);
        assertEquals(3000L, run.startedAt.getTime());
        assertEquals(10L, run.durationSeconds);
        assertEquals(1, run.anomalyTimes.size());
    }

    private static void observe(MonitorService monitor, Models.TaskKey key, String status, long at) throws Exception {
        Models.OracleTask task = new Models.OracleTask();
        task.processDate = key.processDate; task.threadId = key.threadId; task.levelNo = key.levelNo; task.fabId = key.fabId;
        task.status = status; task.actTime = new Date(at);
        monitor.applyTaskStates(Arrays.asList(task));
    }

    private static Models.RunRecord completed(Models.TaskKey key, long start, long duration) {
        Models.RunRecord run = new Models.RunRecord(); run.task = key; run.startedAt = new Date(start); run.completedAt = new Date(start + duration * 1000L); run.durationSeconds = duration; return run;
    }
}
