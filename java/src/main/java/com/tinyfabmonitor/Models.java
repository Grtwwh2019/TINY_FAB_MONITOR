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
        public boolean actTimePlaceholder;
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
        public String fabDescription = "";
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

    static class DependencyAnalysis {
        List<Dependency> displayDependencies = new ArrayList<Dependency>();
        List<Dependency> etaUpstreamDependencies = new ArrayList<Dependency>();
    }

    static class DagEta {
        boolean available;
        boolean completed;
        boolean lowerBound;
        boolean overdue;
        Date estimatedCompletion;
        long remainingSeconds;
        int sampleCount;
        String confidence = "";
        String summary = "";
        String detail = "";
        List<String> criticalPath = new ArrayList<String>();
    }

    enum AnalysisBaselineMode { PREVIOUS_COMPLETE, SPECIFIED_DATE, RECENT_AVERAGE }

    static class AnalysisRequest {
        String analysisDate = "";
        AnalysisBaselineMode baselineMode = AnalysisBaselineMode.PREVIOUS_COMPLETE;
        String specifiedBaselineDate = "";
        int recentDateCount = 7;
        String threadFilter = "";
        Integer levelMinimum;
        Integer levelMaximum;
        String startThreadId = "";
        String startLevelNo = "";
        String startFabId = "";
        String endThreadId = "";
        String endLevelNo = "";
        String endFabId = "";

        String startGroupId() { return startThreadId + "|" + startLevelNo + "|" + startFabId; }
        String endGroupId() { return endThreadId + "|" + endLevelNo + "|" + endFabId; }
    }

    static class AnalysisTaskMetric {
        String fabId = "";
        String fabDescription = "";
        String threadId = "";
        String levelNo = "";
        String status = "";
        Date startedAt;
        Date completedAt;
        Date baselineCompletedAt;
        boolean baselineCompletionAverage;
        boolean executionEstimated;
        boolean baselineExecutionEstimated;
        boolean waitEstimated;
        boolean baselineWaitEstimated;
        int estimateSampleCount;
        String startBasis = "";
        Long executionSeconds;
        Long baselineExecutionSeconds;
        Long executionDeltaSeconds;
        Long waitSeconds;
        Long baselineWaitSeconds;
        Long waitDeltaSeconds;
        Date readinessAt;
        Date baselineReadinessAt;
        boolean readinessPartial;
        boolean baselineReadinessPartial;
        Long readyToCompleteSeconds;
        Long baselineReadyToCompleteSeconds;
        Long readyToCompleteDeltaSeconds;
        Long completionOffsetSeconds;
        Long baselineCompletionOffsetSeconds;
        Long completionDelaySeconds;
        long delayContributionSeconds;
        int anomalyCount;
        String confidence = "数据不足";
        String reason = "数据不足";
        boolean criticalPath;
    }

    static class AnalysisResult {
        String analysisDate = "";
        List<String> baselineDates = new ArrayList<String>();
        String baselineLabel = "";
        boolean targetComplete;
        boolean targetEstimatedStart;
        Date targetStart;
        Date targetFinish;
        long targetDurationSeconds;
        long baselineDurationSeconds;
        long overallDeltaSeconds;
        Date expectedFinish;
        Date predictedFinish;
        Long completionDelaySeconds;
        boolean predictedDelay;
        String anchorMode = "";
        boolean dependencyPathComplete;
        String startBasis = "";
        String startTaskLabel = "";
        String endTaskLabel = "";
        String summary = "";
        String detail = "";
        List<AnalysisTaskMetric> rows = new ArrayList<AnalysisTaskMetric>();
        List<AnalysisTaskMetric> allRows = new ArrayList<AnalysisTaskMetric>();
        List<Dependency> dependencies = new ArrayList<Dependency>();
        List<String> criticalPath = new ArrayList<String>();
        int preciseCount;
        int estimatedCount;
        int completionOnlyCount;
        int insufficientCount;
    }

    static class AnalysisState {
        boolean loading;
        String error = "";
        long requestId;
        AnalysisResult result = new AnalysisResult();
    }

    static class TaskView extends OracleTask {
        public Date startedAt;
        public Date completedAt;
        public long currentDurationSeconds;
        public long lastDurationSeconds;
        public long averageDurationSeconds;
        public int completedRunCount;
        public long executionTypicalSeconds;
        public int executionTypicalSampleCount;
        public Date readinessAt;
        public Long readyToCompleteSeconds;
        public long readyToCompleteTypicalSeconds;
        public int readyToCompleteSampleCount;
        public boolean readinessPartial;
        public boolean hasLevel20Upstream;
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
        List<Dependency> etaUpstreamDependencies = new ArrayList<Dependency>();
        String dagRootFabId = "";
        boolean dagLoading;
        String dagError = "";
        long dagRequestId;
        DagEta dagEta = new DagEta();
        AnalysisState analysis = new AnalysisState();
        Map<String, Long> historicalAverageByFab = new LinkedHashMap<String, Long>();
        List<RunRecord> recentRuns = new ArrayList<RunRecord>();
        int totalHistoricalRuns;
    }
}
