package com.tinyfabmonitor;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Models {
    private Models() {}

    static class TaskKey {
        public String processDate = "";
        public String threadId = "";
        public String levelNo = "";
        public String fabId = "";

        TaskKey() {}
        TaskKey(String processDate, String threadId, String levelNo, String fabId) {
            this.processDate = processDate;
            this.threadId = threadId;
            this.levelNo = levelNo;
            this.fabId = fabId;
        }
        String fullId() { return processDate + "|" + threadId + "|" + levelNo + "|" + fabId; }
        String groupId() { return threadId + "|" + levelNo + "|" + fabId; }
    }

    static class OracleTask extends TaskKey {
        public String status = "";
        public Date actTime;
        public String levelDescription = "";
        public String fabDescription = "";
    }

    static class StateEvent {
        public String status = "";
        public Date at;
        StateEvent() {}
        StateEvent(String status, Date at) { this.status = status; this.at = at; }
    }

    static class RunRecord {
        public String id = "";
        public TaskKey task = new TaskKey();
        public Date startedAt;
        public Date completedAt;
        public long durationSeconds;
        public List<Date> anomalyTimes = new ArrayList<Date>();
        public List<StateEvent> events = new ArrayList<StateEvent>();
    }

    static class TrackedTask {
        public TaskKey key = new TaskKey();
        public String lastStatus = "";
        public Date lastActTime;
        public String activeRunId = "";
    }

    static class PersistedState {
        public int version = 2;
        public String selectedProcessDate = "";
        public Map<String, TrackedTask> tracked = new LinkedHashMap<String, TrackedTask>();
        public List<RunRecord> runs = new ArrayList<RunRecord>();
        public Date updatedAt;
    }

    static class Dependency {
        public String fabId;
        public String dependencyId;
        Dependency(String fabId, String dependencyId) {
            this.fabId = fabId;
            this.dependencyId = dependencyId;
        }
    }

    static class TaskView extends OracleTask {
        public Date startedAt;
        public Date completedAt;
        public long currentDurationSeconds;
        public long lastDurationSeconds;
        public long averageDurationSeconds;
        public int completedRunCount;
        public List<Date> anomalyTimes = new ArrayList<Date>();
    }

    static class GroupStat {
        long average;
        int count;
        long last;
        RunRecord lastRun;
    }

    static class Dashboard {
        String processDate = "";
        boolean connected;
        boolean polling;
        Date lastPollAt;
        Date nextPollAt;
        String lastError = "";
        List<TaskView> tasks = new ArrayList<TaskView>();
        List<Dependency> dependencies = new ArrayList<Dependency>();
        Map<String, Long> historicalAverageByFab = new LinkedHashMap<String, Long>();
        List<RunRecord> recentRuns = new ArrayList<RunRecord>();
        int totalHistoricalRuns;
    }
}
