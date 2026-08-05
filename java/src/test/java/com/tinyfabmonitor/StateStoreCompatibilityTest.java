package com.tinyfabmonitor;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
