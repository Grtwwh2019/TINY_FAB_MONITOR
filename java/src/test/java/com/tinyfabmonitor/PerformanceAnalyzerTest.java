package com.tinyfabmonitor;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PerformanceAnalyzerTest {
    @Test public void overallDelayUsesOnlyEndTaskRClock() {
        Models.AnalysisResult result = analyze(days(
            tasks("20260102", task("20260102", "A", "R", 1000), task("20260102", "B", "R", 2000)),
            tasks("20260101", task("20260101", "A", "R", 0), task("20260101", "B", "R", 500))),
            noEdges(), "20260102", "20260101");
        assertEquals(1500L, result.overallDeltaSeconds);
        assertEquals(Long.valueOf(2000L), result.targetBusinessCompletionOffsetSeconds);
        assertEquals(500L, result.baselineBusinessCompletionOffsetSeconds);
        assertEquals("纯R时间对比", find(result, "B").confidence);
    }

    @Test public void startAlignmentRemovesDifferentBatchStartTimes() {
        Models.AnalysisResult result = analyze(days(
            tasks("20260102", task("20260102", "A", "R", 3000), task("20260102", "B", "R", 5000)),
            tasks("20260101", task("20260101", "A", "R", 1000), task("20260101", "B", "R", 3000))),
            noEdges(), "20260102", "20260101");
        assertEquals(Long.valueOf(2000L), find(result, "B").completionClockDeltaSeconds);
        assertEquals(Long.valueOf(0L), find(result, "B").completionDelaySeconds);
    }

    @Test public void decompositionIsExactUsingOnlyRTimes() {
        List<Models.Dependency> edges = Arrays.asList(new Models.Dependency("B", "C"));
        Models.AnalysisResult result = analyze(days(
            tasks("20260102", task("20260102", "A", "R", 1000), task("20260102", "C", "R", 1800), task("20260102", "B", "R", 3000)),
            tasks("20260101", task("20260101", "A", "R", 1000), task("20260101", "C", "R", 1200), task("20260101", "B", "R", 2000))),
            edges, "20260102", "20260101");
        Models.AnalysisTaskMetric b = find(result, "B");
        assertEquals(Long.valueOf(600L), b.readinessClockDeltaSeconds);
        assertEquals(Long.valueOf(400L), b.readyToCompleteDeltaSeconds);
        assertEquals(Long.valueOf(1000L), b.completionDelaySeconds);
        assertEquals(b.completionDelaySeconds.longValue(), b.readinessClockDeltaSeconds + b.readyToCompleteDeltaSeconds);
    }

    @Test public void latestParallelDependenciesAreAllReadinessDriversWhenTied() {
        List<Models.Dependency> edges = Arrays.asList(new Models.Dependency("B", "A"), new Models.Dependency("B", "C"));
        Models.AnalysisResult result = analyze(days(
            tasks("20260102", task("20260102", "A", "R", 1000), task("20260102", "C", "R", 1000), task("20260102", "B", "R", 2000)),
            tasks("20260101", task("20260101", "A", "R", 500), task("20260101", "C", "R", 500), task("20260101", "B", "R", 1500))),
            edges, "20260102", "20260101");
        String drivers = find(result, "B").targetReadinessDependency;
        assertTrue(drivers.contains("A"));
        assertTrue(drivers.contains("C"));
        assertEquals(2, result.readinessCriticalDependencies.size());
    }

    @Test public void targetAndBaselineCanHaveDifferentReadinessDrivers() {
        List<Models.Dependency> edges = Arrays.asList(new Models.Dependency("B", "A"), new Models.Dependency("B", "C"));
        Models.AnalysisResult result = analyze(days(
            tasks("20260102", task("20260102", "A", "R", 1200), task("20260102", "C", "R", 900), task("20260102", "B", "R", 2000)),
            tasks("20260101", task("20260101", "A", "R", 500), task("20260101", "C", "R", 800), task("20260101", "B", "R", 1500))),
            edges, "20260102", "20260101");
        assertTrue(find(result, "B").targetReadinessDependency.contains("A"));
        assertTrue(find(result, "B").baselineReadinessDependency.contains("C"));
    }

    @Test public void missingRIsNeverEstimatedFromHistory() {
        Map<String, List<Models.OracleTask>> values = days(
            tasks("20260102", task("20260102", "A", "R", 1000), task("20260102", "X", "I", 1500), task("20260102", "B", "R", 2200)),
            tasks("20260101", task("20260101", "A", "R", 500), task("20260101", "X", "R", 1000), task("20260101", "B", "R", 1500)));
        Models.AnalysisRequest request = request("20260102");
        Models.RunRecord history = new Models.RunRecord(); history.task = new Models.TaskKey("20251231", "T", "41", "X");
        history.startedAt = dateAt("20251231", 100); history.completedAt = dateAt("20251231", 200); history.durationSeconds = 100;
        Models.AnalysisResult result = PerformanceAnalyzer.analyze(request, values, Arrays.asList(history), noEdges(), Arrays.asList("20260101"), dateAt("20260102", 5000));
        Models.AnalysisTaskMetric x = find(result, "X");
        assertNull(x.completedAt);
        assertNull(x.completionDelaySeconds);
        assertEquals("缺少有效R", x.dataQuality);
    }

    @Test public void incompleteDirectDependencyPreventsReadinessDecomposition() {
        List<Models.Dependency> edges = Arrays.asList(new Models.Dependency("B", "X"));
        Models.AnalysisResult result = analyze(days(
            tasks("20260102", task("20260102", "A", "R", 1000), task("20260102", "X", "I", 1400), task("20260102", "B", "R", 2000)),
            tasks("20260101", task("20260101", "A", "R", 500), task("20260101", "X", "R", 900), task("20260101", "B", "R", 1500))),
            edges, "20260102", "20260101");
        Models.AnalysisTaskMetric b = find(result, "B");
        assertNull(b.readinessAt);
        assertEquals("依赖R不完整", b.dataQuality);
        assertFalse(b.incompleteDependencies.isEmpty());
    }

    @Test public void duplicateFabAcrossTaskKeysIsMarkedAmbiguous() {
        Models.OracleTask duplicate = task("20260102", "X", "R", 1300); duplicate.threadId = "OTHER";
        List<Models.Dependency> edges = Arrays.asList(new Models.Dependency("B", "X"));
        Models.AnalysisResult result = analyze(days(
            tasks("20260102", task("20260102", "A", "R", 1000), task("20260102", "X", "R", 1200), duplicate, task("20260102", "B", "R", 2000)),
            tasks("20260101", task("20260101", "A", "R", 500), task("20260101", "X", "R", 800), task("20260101", "B", "R", 1500))),
            edges, "20260102", "20260101");
        Models.AnalysisTaskMetric b = find(result, "B");
        assertEquals("依赖映射有歧义", b.dataQuality);
        assertFalse(b.ambiguousDependencies.isEmpty());
    }

    @Test public void level20DependencyIsCutOffButOtherDependencyStillDrivesReadiness() {
        Models.OracleTask poll = task("20260102", "POLL", "R", 1800); poll.levelNo = "20";
        Models.OracleTask oldPoll = task("20260101", "POLL", "R", 700); oldPoll.levelNo = "20";
        List<Models.Dependency> edges = Arrays.asList(new Models.Dependency("B", "POLL"), new Models.Dependency("B", "A"));
        Models.AnalysisResult result = analyze(days(
            tasks("20260102", task("20260102", "A", "R", 1000), poll, task("20260102", "B", "R", 2000)),
            tasks("20260101", task("20260101", "A", "R", 500), oldPoll, task("20260101", "B", "R", 1500))),
            edges, "20260102", "20260101");
        Models.AnalysisTaskMetric b = find(result, "B");
        assertEquals(dateAt("20260102", 1000), b.readinessAt);
        assertTrue(b.readinessPartial);
    }

    @Test public void placeholderRIsInvalid() {
        Models.OracleTask x = task("20260102", "X", "R", 1400); x.actTimePlaceholder = true;
        Models.AnalysisResult result = analyze(days(
            tasks("20260102", task("20260102", "A", "R", 1000), x, task("20260102", "B", "R", 2000)),
            tasks("20260101", task("20260101", "A", "R", 500), task("20260101", "X", "R", 900), task("20260101", "B", "R", 1500))),
            noEdges(), "20260102", "20260101");
        assertEquals("缺少有效R", find(result, "X").dataQuality);
    }

    @Test public void crossMidnightCompletionClockRemainsContinuous() {
        Models.AnalysisResult result = analyze(days(
            tasks("20260102", task("20260102", "A", "R", 86000), task("20260102", "B", "R", 87000)),
            tasks("20260101", task("20260101", "A", "R", 85000), task("20260101", "B", "R", 85500))),
            noEdges(), "20260102", "20260101");
        assertEquals(1500L, result.overallDeltaSeconds);
    }

    @Test public void thresholdChangesRecommendationButNotRawDelta() {
        Map<String, List<Models.OracleTask>> values = days(
            tasks("20260102", task("20260102", "A", "R", 1000), task("20260102", "B", "R", 1100)),
            tasks("20260101", task("20260101", "A", "R", 500), task("20260101", "B", "R", 550)));
        Models.AnalysisRequest low = request("20260102"); low.attentionThresholdSeconds = 10;
        Models.AnalysisRequest high = request("20260102"); high.attentionThresholdSeconds = 100;
        Models.AnalysisResult lowResult = PerformanceAnalyzer.analyze(low, values, new ArrayList<Models.RunRecord>(), noEdges(), Arrays.asList("20260101"), dateAt("20260102", 5000));
        Models.AnalysisResult highResult = PerformanceAnalyzer.analyze(high, values, new ArrayList<Models.RunRecord>(), noEdges(), Arrays.asList("20260101"), dateAt("20260102", 5000));
        assertEquals(find(lowResult, "B").completionDelaySeconds, find(highResult, "B").completionDelaySeconds);
        assertFalse(find(lowResult, "B").recommendation.equals(find(highResult, "B").recommendation));
    }

    @Test public void configuredEndTaskControlsOverallVerdict() {
        Models.AnalysisResult result = analyze(days(
            tasks("20260102", task("20260102", "A", "R", 1000), task("20260102", "B", "R", 2000), task("20260102", "LATE", "R", 9000)),
            tasks("20260101", task("20260101", "A", "R", 500), task("20260101", "B", "R", 1500), task("20260101", "LATE", "R", 1000))),
            noEdges(), "20260102", "20260101");
        assertEquals(dateAt("20260102", 2000), result.targetFinish);
        assertEquals(500L, result.overallDeltaSeconds);
    }

    @Test public void recursiveDriverChainStopsAtConfiguredStart() {
        List<Models.Dependency> edges = Arrays.asList(new Models.Dependency("X", "A"), new Models.Dependency("B", "X"));
        Models.AnalysisResult result = analyze(days(
            tasks("20260102", task("20260102", "A", "R", 1000), task("20260102", "X", "R", 1500), task("20260102", "B", "R", 2500)),
            tasks("20260101", task("20260101", "A", "R", 500), task("20260101", "X", "R", 800), task("20260101", "B", "R", 1300))),
            edges, "20260102", "20260101");
        assertTrue(find(result, "B").delayedDependencyChains.get(0).contains("A → X → B"));
    }

    private static Models.AnalysisResult analyze(Map<String, List<Models.OracleTask>> days, List<Models.Dependency> edges,
                                                  String target, String baseline) {
        return PerformanceAnalyzer.analyze(request(target), days, new ArrayList<Models.RunRecord>(), edges,
            Arrays.asList(baseline), dateAt(target, 10000));
    }

    private static Models.AnalysisRequest request(String target) {
        Models.AnalysisRequest request = new Models.AnalysisRequest(); request.analysisDate = target;
        request.baselineMode = Models.AnalysisBaselineMode.SPECIFIED_DATE;
        request.startThreadId = "T"; request.startLevelNo = "41"; request.startFabId = "A";
        request.endThreadId = "T"; request.endLevelNo = "41"; request.endFabId = "B";
        return request;
    }

    private static Map<String, List<Models.OracleTask>> days(List<Models.OracleTask> first, List<Models.OracleTask> second) {
        Map<String, List<Models.OracleTask>> values = new LinkedHashMap<String, List<Models.OracleTask>>();
        values.put(first.get(0).processDate, first); values.put(second.get(0).processDate, second); return values;
    }
    private static List<Models.OracleTask> tasks(String date, Models.OracleTask... tasks) { return Arrays.asList(tasks); }
    private static List<Models.Dependency> noEdges() { return new ArrayList<Models.Dependency>(); }
    private static Models.AnalysisTaskMetric find(Models.AnalysisResult result, String fab) {
        for (Models.AnalysisTaskMetric metric : result.rows) if (fab.equals(metric.fabId) && "T".equals(metric.threadId)) return metric;
        throw new AssertionError("Missing " + fab);
    }
    private static Models.OracleTask task(String date, String fab, String status, long at) {
        Models.OracleTask task = new Models.OracleTask(); task.processDate = date; task.threadId = "T"; task.levelNo = "41";
        task.fabId = fab; task.status = status; task.actTime = dateAt(date, at); return task;
    }
    private static Date dateAt(String processDate, long seconds) {
        LocalDate date = LocalDate.parse(processDate, DateTimeFormatter.BASIC_ISO_DATE);
        LocalDateTime value = date.atStartOfDay().plusSeconds(seconds);
        return Date.from(value.atZone(ZoneId.systemDefault()).toInstant());
    }
}
