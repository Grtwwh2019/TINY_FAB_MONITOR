package com.tinyfabmonitor;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StateStoreCompatibilityTest {
    @Test public void repairsLegacyYearZeroDateAndPreservesOtherHistory() throws Exception {
        Path directory = Files.createTempDirectory("tiny-fab-state-repair");
        Path path = directory.resolve("state.json");
        String json = "{\n" +
            "  \"version\": 2,\n" +
            "  \"selected_process_date\": \"20260804\",\n" +
            "  \"tracked\": {\"20260804|TEST_THREAD_001|120|TEST_FAB_001\": {\n" +
            "    \"key\": {\"process_date\":\"20260804\",\"thread_id\":\"TEST_THREAD_001\",\"level_no\":\"120\",\"fab_id\":\"TEST_FAB_001\"},\n" +
            "    \"last_status\": \"W\", \"last_act_time\": \"+0000-12-31T16:00:00.000+00:00\"\n" +
            "  }},\n" +
            "  \"runs\": [{\"id\":\"kept\",\"task\":{\"process_date\":\"20260803\",\"thread_id\":\"T\",\"level_no\":\"41\",\"fab_id\":\"F\"},\n" +
            "    \"started_at\":\"2026-08-03T01:00:00.123456789+00:00\",\"completed_at\":\"2026-08-03T01:10:00.000+00:00\",\"duration_seconds\":600,\"events\":[]}]}";
        Files.write(path, json.getBytes(StandardCharsets.UTF_8));

        Models.PersistedState state = new StateStore(path).snapshot();
        assertNull(state.tracked.get("20260804|TEST_THREAD_001|120|TEST_FAB_001").lastActTime);
        assertEquals(1, state.runs.size());
        assertEquals(123, state.runs.get(0).startedAt.getTime() % 1000);
        assertEquals(600, state.runs.get(0).durationSeconds);
        assertTrue(Files.isRegularFile(directory.resolve("state.json.before-placeholder-repair.bak")));
        String repaired = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertFalse(repaired.contains("+0000-12-31"));
    }

    @Test public void manuallyCleansByLatestBusinessDateAndKeepsBackupForOneDay() throws Exception {
        Path directory = Files.createTempDirectory("tiny-fab-cleanup");
        Path path = directory.resolve("state.json");
        StateStore store = new StateStore(path);
        store.update(state -> {
            state.selectedProcessDate = "20260120";
            addTracked(state, "20260101", "OLD"); addTracked(state, "20260110", "KEPT"); addTracked(state, "20260120", "LATEST");
            state.runs.add(run("20260101", "OLD")); state.runs.add(run("20260110", "KEPT")); state.runs.add(run("20260120", "LATEST"));
        });
        StateStore.CleanupPreview preview = store.previewCleanup(14);
        assertEquals("2026-01-20", preview.latestDate.toString());
        assertEquals("2026-01-07", preview.cutoffDate.toString());
        assertEquals(1, preview.trackedToDelete); assertEquals(1, preview.runsToDelete);
        store.cleanup(14);
        Models.PersistedState cleaned = new StateStore(path).snapshot();
        assertEquals("20260120", cleaned.selectedProcessDate);
        assertEquals(2, cleaned.tracked.size()); assertEquals(2, cleaned.runs.size());
        int backups = 0;
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(directory, "state.json.before-cleanup-*.bak")) { for (Path ignored : files) backups++; }
        assertEquals(1, backups);
    }

    @Test public void removesCleanupBackupsOlderThanTwentyFourHoursOnStartup() throws Exception {
        Path directory = Files.createTempDirectory("tiny-fab-old-cleanup-backup");
        Path old = directory.resolve("state.json.before-cleanup-20260101-000000-000.bak");
        Files.write(old, "old".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() - 25L * 60L * 60L * 1000L));
        new StateStore(directory.resolve("state.json"));
        assertFalse(Files.exists(old));
    }

    private static void addTracked(Models.PersistedState state, String date, String fab) {
        Models.TrackedTask tracked = new Models.TrackedTask(); tracked.key = new Models.TaskKey(date, "T", "41", fab);
        state.tracked.put(tracked.key.fullId(), tracked);
    }

    private static Models.RunRecord run(String date, String fab) {
        Models.RunRecord run = new Models.RunRecord(); run.id = date + fab; run.task = new Models.TaskKey(date, "T", "41", fab);
        run.startedAt = new Date(0); run.completedAt = new Date(600000); run.durationSeconds = 600; return run;
    }
}
