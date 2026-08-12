package com.tinyfabmonitor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PerformanceAnalyzerTest {
    @Test public void comparesCompletedDatesUsingOnlyDatabaseRTimes() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 1000), task("20260102", "B", "R", 2000)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 0), task("20260101", "B", "R", 500)));
        Models.AnalysisResult result = analyze(days, new ArrayList<Models.RunRecord>(), new ArrayList<Models.Dependency>(), "20260102", Arrays.asList("20260101"));
        assertTrue(result.targetComplete);
        assertTrue(result.targetEstimatedStart);
        assertEquals(1000, result.targetDurationSeconds);
        assertEquals(500, result.baselineDurationSeconds);
        assertEquals(500, result.overallDeltaSeconds);
        Models.AnalysisTaskMetric b = find(result, "B");
        assertEquals("完成时间分析", b.confidence);
        assertEquals(Long.valueOf(500), b.completionDelaySeconds);
    }

    @Test public void separatesExecutionAndWaitingDelayWhenIRHistoryExists() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 1000), task("20260102", "B", "R", 3000)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 1000), task("20260101", "B", "R", 2100)));
        List<Models.RunRecord> runs = Arrays.asList(
            run("20260102", "A", 0, 1000), run("20260102", "B", 1500, 3000),
            run("20260101", "A", 0, 1000), run("20260101", "B", 1100, 2100));
        Models.AnalysisResult result = analyze(days, runs, Arrays.asList(new Models.Dependency("B", "A")), "20260102", Arrays.asList("20260101"));
        Models.AnalysisTaskMetric b = find(result, "B");
        assertEquals("精确分析", b.confidence);
        assertEquals(Long.valueOf(500), b.executionDeltaSeconds);
        assertEquals(Long.valueOf(400), b.waitDeltaSeconds);
        assertEquals("执行耗时增加", b.reason);
    }

    @Test public void choosesLatestParallelBranchAsCriticalPath() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 2000), task("20260102", "C", "R", 3000), task("20260102", "ROOT", "R", 4000)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 1000), task("20260101", "C", "R", 2500), task("20260101", "ROOT", "R", 3000)));
        List<Models.Dependency> edges = Arrays.asList(new Models.Dependency("ROOT", "A"), new Models.Dependency("ROOT", "C"));
        Models.AnalysisResult result = analyze(days, new ArrayList<Models.RunRecord>(), edges, "20260102", Arrays.asList("20260101"));
        assertFalse(find(result, "A").criticalPath);
        assertTrue(find(result, "C").criticalPath);
        assertTrue(find(result, "ROOT").criticalPath);
    }

    @Test public void averagesSeveralCompleteBaselineDates() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260103", Arrays.asList(task("20260103", "A", "R", 0), task("20260103", "B", "R", 1200)));
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 0), task("20260102", "B", "R", 800)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 0), task("20260101", "B", "R", 1000)));
        Models.AnalysisResult result = analyze(days, new ArrayList<Models.RunRecord>(), new ArrayList<Models.Dependency>(), "20260103", Arrays.asList("20260102", "20260101"));
        assertEquals(900, result.baselineDurationSeconds);
        assertEquals(300, result.overallDeltaSeconds);
    }

    @Test public void marksNewTaskWithoutBaselineAsInsufficientInsteadOfInventingDelay() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 1000), task("20260102", "NEW", "R", 2000)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 500)));
        Models.AnalysisResult result = analyze(days, new ArrayList<Models.RunRecord>(), new ArrayList<Models.Dependency>(), "20260102", Arrays.asList("20260101"));
        Models.AnalysisTaskMetric added = find(result, "NEW");
        assertEquals("数据不足", added.confidence);
        assertEquals(null, added.completionDelaySeconds);
    }

    @Test public void cycleInDependencyGraphTerminatesCriticalPathWalk() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 1000), task("20260102", "B", "R", 2000)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 900), task("20260101", "B", "R", 1800)));
        List<Models.Dependency> cycle = Arrays.asList(new Models.Dependency("A", "B"), new Models.Dependency("B", "A"));
        Models.AnalysisResult result = analyze(days, new ArrayList<Models.RunRecord>(), cycle, "20260102", Arrays.asList("20260101"));
        assertTrue(result.criticalPath.size() <= 2);
    }

    private static Models.AnalysisResult analyze(Map<String, List<Models.OracleTask>> days, List<Models.RunRecord> runs,
                                                 List<Models.Dependency> edges, String target, List<String> baselines) {
        Models.AnalysisRequest request = new Models.AnalysisRequest(); request.analysisDate = target;
        return PerformanceAnalyzer.analyze(request, days, runs, edges, baselines, new Date(5000));
    }

    private static Models.AnalysisTaskMetric find(Models.AnalysisResult result, String fab) {
        for (Models.AnalysisTaskMetric metric : result.rows) if (fab.equals(metric.fabId)) return metric;
        throw new AssertionError("Missing " + fab);
    }

    private static Models.OracleTask task(String date, String fab, String status, long at) {
        Models.OracleTask task = new Models.OracleTask(); task.processDate = date; task.threadId = "T"; task.levelNo = "41";
        task.fabId = fab; task.status = status; task.actTime = new Date(at * 1000L); return task;
    }

    private static Models.RunRecord run(String date, String fab, long start, long finish) {
        Models.RunRecord run = new Models.RunRecord(); run.task = new Models.TaskKey(date, "T", "41", fab);
        run.startedAt = new Date(start * 1000L); run.completedAt = new Date(finish * 1000L); run.durationSeconds = finish - start; return run;
    }
}
