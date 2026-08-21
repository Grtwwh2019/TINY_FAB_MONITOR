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
        assertFalse(result.targetEstimatedStart);
        assertEquals("R", result.anchorMode);
        assertEquals(1000, result.targetDurationSeconds);
        assertEquals(500, result.baselineDurationSeconds);
        assertEquals(500, result.overallDeltaSeconds);
        Models.AnalysisTaskMetric b = find(result, "B");
        assertEquals("仅完成时间分析", b.confidence);
        assertEquals(Long.valueOf(500), b.completionDelaySeconds);
        assertEquals(2000L * 1000L, b.completedAt.getTime());
        assertEquals(500L * 1000L, b.baselineCompletedAt.getTime());
        assertFalse(b.baselineCompletionAverage);
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
        assertEquals("精确执行分析", b.confidence);
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
        Models.AnalysisTaskMetric b = find(result, "B");
        assertTrue(b.baselineCompletionAverage);
        assertEquals(Long.valueOf(900), b.baselineCompletionOffsetSeconds);
        assertEquals(null, b.baselineCompletedAt);
    }

    @Test public void marksNewTaskWithoutBaselineAsInsufficientInsteadOfInventingDelay() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 1000), task("20260102", "NEW", "R", 2000), task("20260102", "B", "R", 2500)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 500), task("20260101", "B", "R", 1500)));
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

    @Test public void unfinishedUnrelatedTaskDoesNotInvalidateCompletedEndBoundary() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 1000), task("20260102", "B", "R", 2200), task("20260102", "OTHER", "W", 0)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 500), task("20260101", "B", "R", 1500), task("20260101", "OTHER", "I", 1400)));
        Models.AnalysisResult result = analyze(days, new ArrayList<Models.RunRecord>(), new ArrayList<Models.Dependency>(), "20260102", Arrays.asList("20260101"));
        assertTrue(result.targetComplete);
        assertEquals(1000, result.baselineDurationSeconds);
    }

    @Test public void overallFinishUsesConfiguredEndTaskInsteadOfLatestUnrelatedR() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 1000), task("20260102", "B", "R", 2000), task("20260102", "LATE", "R", 4000)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 500), task("20260101", "B", "R", 1500), task("20260101", "LATE", "R", 5000)));
        Models.AnalysisResult result = analyze(days, new ArrayList<Models.RunRecord>(), new ArrayList<Models.Dependency>(), "20260102", Arrays.asList("20260101"));
        assertEquals(2000L * 1000L, result.targetFinish.getTime());
        assertEquals(1000, result.targetDurationSeconds);
    }

    @Test public void missingBoundaryIUsesRForBothSidesAndIgnoresHistoricalGuess() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 1000), task("20260102", "B", "R", 2500)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 800), task("20260101", "B", "R", 1600)));
        List<Models.RunRecord> runs = Arrays.asList(run("20251231", "A", 100, 400));
        Models.AnalysisResult result = analyze(days, runs, new ArrayList<Models.Dependency>(), "20260102", Arrays.asList("20260101"));
        assertEquals(1000L * 1000L, result.targetStart.getTime());
        assertFalse(result.targetEstimatedStart);
        assertEquals("R", result.anchorMode);
        assertTrue(result.startBasis.contains("统一使用启动作业 R"));
        assertEquals(700, result.overallDeltaSeconds);
    }

    @Test public void missingIDoesNotPretendPredecessorRIsAnIStart() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 1000), task("20260102", "B", "R", 2500)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 500), task("20260101", "B", "R", 1500)));
        Models.AnalysisResult result = analyze(days, new ArrayList<Models.RunRecord>(), Arrays.asList(new Models.Dependency("B", "A")), "20260102", Arrays.asList("20260101"));
        Models.AnalysisTaskMetric b = find(result, "B");
        assertEquals(null, b.startedAt);
        assertEquals(null, b.executionSeconds);
        assertEquals(1000L * 1000L, b.readinessAt.getTime());
        assertEquals(Long.valueOf(1500), b.readyToCompleteSeconds);
        assertEquals("R 区间分析", b.confidence);
    }

    @Test public void missingIUsesBoundedHistoricalExecutionOnlyAsSecondaryEvidence() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 1000), task("20260102", "B", "R", 2500)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 500), task("20260101", "B", "R", 1500)));
        List<Models.RunRecord> runs = Arrays.asList(run("20251231", "A", 100, 400), run("20251231", "B", 460, 800));
        Models.AnalysisResult result = analyze(days, runs, Arrays.asList(new Models.Dependency("B", "A")), "20260102", Arrays.asList("20260101"));
        Models.AnalysisTaskMetric b = find(result, "B");
        assertEquals(2160L * 1000L, b.startedAt.getTime());
        assertEquals(Long.valueOf(1160), b.waitSeconds);
        assertEquals(Long.valueOf(1500), b.readyToCompleteSeconds);
        assertTrue(b.startBasis.contains("历史执行典型值"));
        assertEquals("R 区间分析", b.confidence);
    }

    @Test public void level20PredecessorStopsThatPathAndFallsBackToOwnHistory() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 0),
            taskLevel("20260102", "POLL", "20", "R", 1000), task("20260102", "B", "R", 2500)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 0),
            taskLevel("20260101", "POLL", "20", "R", 600), task("20260101", "B", "R", 1500)));
        List<Models.RunRecord> runs = Arrays.asList(run("20251231", "B", 100, 400));
        Models.AnalysisResult result = analyze(days, runs,
            Arrays.asList(new Models.Dependency("B", "POLL")), "20260102", Arrays.asList("20260101"));
        Models.AnalysisTaskMetric b = find(result, "B");
        assertEquals(2200L * 1000L, b.startedAt.getTime());
        assertTrue(b.startBasis.contains("自身 R - 历史执行典型值"));
    }

    @Test public void level20BranchDoesNotHideAnotherEligiblePredecessor() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 0),
            taskLevel("20260102", "POLL", "20", "R", 2000), task("20260102", "C", "R", 1500),
            task("20260102", "B", "R", 2500)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 0),
            taskLevel("20260101", "POLL", "20", "R", 800), task("20260101", "C", "R", 700),
            task("20260101", "B", "R", 1500)));
        List<Models.Dependency> edges = Arrays.asList(new Models.Dependency("B", "POLL"), new Models.Dependency("B", "C"));
        Models.AnalysisResult result = analyze(days, new ArrayList<Models.RunRecord>(), edges,
            "20260102", Arrays.asList("20260101"));
        Models.AnalysisTaskMetric b = find(result, "B");
        assertEquals(null, b.startedAt);
        assertEquals(1500L * 1000L, b.readinessAt.getTime());
        assertEquals(Long.valueOf(1000), b.readyToCompleteSeconds);
        assertTrue(b.readinessPartial);
        assertEquals("R 区间分析", b.confidence);
    }

    @Test public void level20EndTaskIsRejected() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 0), task("20260102", "C", "R", 1800),
            taskLevel("20260102", "B", "20", "R", 2500)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 0), task("20260101", "C", "R", 900),
            taskLevel("20260101", "B", "20", "R", 1500)));
        List<Models.RunRecord> runs = Arrays.asList(runLevel("20251231", "B", "20", 100, 400));
        try {
            analyze(days, runs, Arrays.asList(new Models.Dependency("B", "C")),
                "20260102", Arrays.asList("20260101"));
            throw new AssertionError("Expected Level 20 end task to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Level 20"));
            assertTrue(expected.getMessage().contains("结束作业"));
        }
    }

    @Test public void level20CutoffWithoutOwnHistoryKeepsCompletionOnly() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 0),
            taskLevel("20260102", "POLL", "20", "R", 1000), task("20260102", "B", "R", 2500)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 0),
            taskLevel("20260101", "POLL", "20", "R", 600), task("20260101", "B", "R", 1500)));
        Models.AnalysisResult result = analyze(days, new ArrayList<Models.RunRecord>(),
            Arrays.asList(new Models.Dependency("B", "POLL")), "20260102", Arrays.asList("20260101"));
        Models.AnalysisTaskMetric b = find(result, "B");
        assertEquals(null, b.startedAt);
        assertEquals(null, b.executionSeconds);
        assertEquals("仅完成时间分析", b.confidence);
    }

    @Test public void rejectsHistoricalStartThatWouldBeBeforeDependencyReadiness() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 2400), task("20260102", "B", "R", 2500)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 1000), task("20260101", "B", "R", 1500)));
        List<Models.RunRecord> runs = Arrays.asList(run("20251231", "B", 100, 400));
        Models.AnalysisResult result = analyze(days, runs, Arrays.asList(new Models.Dependency("B", "A")),
            "20260102", Arrays.asList("20260101"));
        Models.AnalysisTaskMetric b = find(result, "B");
        assertEquals(null, b.startedAt);
        assertEquals(null, b.executionSeconds);
        assertEquals(Long.valueOf(100), b.readyToCompleteSeconds);
        assertEquals("R 区间分析", b.confidence);
    }

    @Test public void startBoundaryAlsoRejectsHistoryBeforeDependencyReadiness() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 2400), task("20260102", "B", "R", 2500), task("20260102", "C", "R", 2700)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 1000), task("20260101", "B", "R", 1500), task("20260101", "C", "R", 1700)));
        Models.AnalysisRequest request = new Models.AnalysisRequest(); request.analysisDate = "20260102";
        request.startThreadId = "T"; request.startLevelNo = "41"; request.startFabId = "B";
        request.endThreadId = "T"; request.endLevelNo = "41"; request.endFabId = "C";
        Models.AnalysisResult result = PerformanceAnalyzer.analyze(request, days,
            Arrays.asList(run("20251231", "B", 100, 400)),
            Arrays.asList(new Models.Dependency("B", "A"), new Models.Dependency("C", "B")),
            Arrays.asList("20260101"), new Date(5000L * 1000L));
        assertEquals(2500L * 1000L, result.targetStart.getTime());
        assertEquals("R", result.anchorMode);
        assertTrue(result.startBasis.contains("统一使用启动作业 R"));
    }

    @Test public void level20StartBoundaryWithoutHistoryRejectsItsLoopingR() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(taskLevel("20260102", "POLL", "20", "R", 1000),
            task("20260102", "B", "R", 2500)));
        Models.AnalysisRequest request = new Models.AnalysisRequest(); request.analysisDate = "20260102";
        request.startThreadId = "T"; request.startLevelNo = "20"; request.startFabId = "POLL";
        request.endThreadId = "T"; request.endLevelNo = "41"; request.endFabId = "B";
        try {
            PerformanceAnalyzer.analyze(request, days, new ArrayList<Models.RunRecord>(),
                Arrays.asList(new Models.Dependency("B", "POLL")), new ArrayList<String>(), new Date(5000));
            throw new AssertionError("Expected Level 20 start boundary to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Level 20"));
            assertTrue(expected.getMessage().contains("不能使用循环 Poll 的 R"));
        }
    }

    @Test public void usesIOnlyWhenTargetAndEveryBaselineHaveExactI() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 1000), task("20260102", "B", "R", 2000)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 500), task("20260101", "B", "R", 1500)));
        List<Models.RunRecord> runs = Arrays.asList(run("20260102", "A", 100, 1000), run("20260101", "A", 0, 500));
        Models.AnalysisResult result = analyze(days, runs, new ArrayList<Models.Dependency>(), "20260102", Arrays.asList("20260101"));
        assertEquals("I", result.anchorMode);
        assertEquals(100L * 1000L, result.targetStart.getTime());
        assertEquals(400, result.completionDelaySeconds.longValue());
        assertEquals(1600L * 1000L, result.expectedFinish.getTime());
    }

    @Test public void oneMissingIForcesRAlignmentForTheWholeComparison() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 1000), task("20260102", "B", "R", 2000)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 500), task("20260101", "B", "R", 1500)));
        List<Models.RunRecord> runs = Arrays.asList(run("20260102", "A", 100, 1000));
        Models.AnalysisResult result = analyze(days, runs, new ArrayList<Models.Dependency>(), "20260102", Arrays.asList("20260101"));
        assertEquals("R", result.anchorMode);
        assertEquals(1000L * 1000L, result.targetStart.getTime());
        assertEquals(0, result.completionDelaySeconds.longValue());
        assertTrue(result.summary.contains("持平"));
    }

    @Test public void recentAverageSwitchesAllDatesToRWhenOneBaselineLacksI() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260103", Arrays.asList(task("20260103", "A", "R", 1000), task("20260103", "B", "R", 2200)));
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 600), task("20260102", "B", "R", 1600)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 400), task("20260101", "B", "R", 1400)));
        List<Models.RunRecord> runs = Arrays.asList(run("20260103", "A", 100, 1000), run("20260102", "A", 50, 600));
        Models.AnalysisResult result = analyze(days, runs, new ArrayList<Models.Dependency>(), "20260103", Arrays.asList("20260102", "20260101"));
        assertEquals("R", result.anchorMode);
        assertEquals(1000, result.baselineDurationSeconds);
        assertEquals(200, result.completionDelaySeconds.longValue());
    }

    @Test public void rejectsFinishEarlierThanTheUnifiedStartAnchor() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 2000), task("20260102", "B", "R", 1500)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 500), task("20260101", "B", "R", 1500)));
        try {
            analyze(days, new ArrayList<Models.RunRecord>(), new ArrayList<Models.Dependency>(), "20260102", Arrays.asList("20260101"));
            throw new AssertionError("Expected invalid boundary order to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("早于启动锚点"));
        }
    }

    @Test public void displayFilterDoesNotCutTheInternalDependencyPath() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        Models.OracleTask targetMiddle = taskThread("20260102", "X", "C", "R", 1600);
        Models.OracleTask baselineMiddle = taskThread("20260101", "X", "C", "R", 900);
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 1000), targetMiddle, task("20260102", "B", "R", 2200)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 500), baselineMiddle, task("20260101", "B", "R", 1500)));
        Models.AnalysisRequest request = new Models.AnalysisRequest(); request.analysisDate = "20260102"; request.threadFilter = "T";
        request.startThreadId = "T"; request.startLevelNo = "41"; request.startFabId = "A";
        request.endThreadId = "T"; request.endLevelNo = "41"; request.endFabId = "B";
        Models.AnalysisResult result = PerformanceAnalyzer.analyze(request, days, new ArrayList<Models.RunRecord>(),
            Arrays.asList(new Models.Dependency("C", "A"), new Models.Dependency("B", "C")),
            Arrays.asList("20260101"), new Date(5000L * 1000L));
        assertEquals(2, result.rows.size());
        assertEquals(3, result.allRows.size());
        assertTrue(result.dependencyPathComplete);
    }

    @Test public void completedStatusWithPlaceholderEndTimeDoesNotInventActualDelay() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        Models.OracleTask placeholder = task("20260102", "B", "R", 0); placeholder.actTime = null; placeholder.actTimePlaceholder = true;
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 1000), placeholder));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 500), task("20260101", "B", "R", 1500)));
        Models.AnalysisResult result = analyze(days, new ArrayList<Models.RunRecord>(), new ArrayList<Models.Dependency>(), "20260102", Arrays.asList("20260101"));
        assertEquals(null, result.completionDelaySeconds);
        assertTrue(result.summary.contains("状态为 R"));
        assertTrue(result.summary.contains("完成时间无效"));
    }

    @Test public void exactIAnchorUsesTheLastRestartedRunMatchingTheDatabaseR() {
        Map<String, List<Models.OracleTask>> days = new LinkedHashMap<String, List<Models.OracleTask>>();
        days.put("20260102", Arrays.asList(task("20260102", "A", "R", 1000), task("20260102", "B", "R", 2000)));
        days.put("20260101", Arrays.asList(task("20260101", "A", "R", 500), task("20260101", "B", "R", 1500)));
        List<Models.RunRecord> runs = Arrays.asList(run("20260102", "A", 100, 800), run("20260102", "A", 300, 1000), run("20260101", "A", 0, 500));
        Models.AnalysisResult result = analyze(days, runs, new ArrayList<Models.Dependency>(), "20260102", Arrays.asList("20260101"));
        assertEquals("I", result.anchorMode);
        assertEquals(300L * 1000L, result.targetStart.getTime());
    }

    private static Models.AnalysisResult analyze(Map<String, List<Models.OracleTask>> days, List<Models.RunRecord> runs,
                                                 List<Models.Dependency> edges, String target, List<String> baselines) {
        Models.AnalysisRequest request = new Models.AnalysisRequest(); request.analysisDate = target;
        request.startThreadId = "T"; request.startLevelNo = "41"; request.startFabId = "A";
        request.endThreadId = "T";
        request.endFabId = contains(days.get(target), "ROOT") ? "ROOT" : contains(days.get(target), "B") ? "B" : "A";
        request.endLevelNo = levelOf(days.get(target), request.endFabId);
        if (baselines.size() > 1) { request.baselineMode = Models.AnalysisBaselineMode.RECENT_AVERAGE; request.recentDateCount = baselines.size(); }
        return PerformanceAnalyzer.analyze(request, days, runs, edges, baselines, new Date(5000));
    }

    private static boolean contains(List<Models.OracleTask> tasks, String fab) {
        for (Models.OracleTask task : tasks) if (fab.equals(task.fabId)) return true;
        return false;
    }

    private static String levelOf(List<Models.OracleTask> tasks, String fab) {
        for (Models.OracleTask task : tasks) if (fab.equals(task.fabId)) return task.levelNo;
        return "41";
    }

    private static Models.AnalysisTaskMetric find(Models.AnalysisResult result, String fab) {
        for (Models.AnalysisTaskMetric metric : result.rows) if (fab.equals(metric.fabId)) return metric;
        throw new AssertionError("Missing " + fab);
    }

    private static Models.OracleTask task(String date, String fab, String status, long at) {
        return taskLevel(date, fab, "41", status, at);
    }

    private static Models.OracleTask taskLevel(String date, String fab, String level, String status, long at) {
        Models.OracleTask task = new Models.OracleTask(); task.processDate = date; task.threadId = "T"; task.levelNo = level;
        task.fabId = fab; task.status = status; task.actTime = new Date(at * 1000L); return task;
    }

    private static Models.OracleTask taskThread(String date, String thread, String fab, String status, long at) {
        Models.OracleTask task = task(date, fab, status, at); task.threadId = thread; return task;
    }

    private static Models.RunRecord run(String date, String fab, long start, long finish) {
        return runLevel(date, fab, "41", start, finish);
    }

    private static Models.RunRecord runLevel(String date, String fab, String level, long start, long finish) {
        Models.RunRecord run = new Models.RunRecord(); run.task = new Models.TaskKey(date, "T", level, fab);
        run.startedAt = new Date(start * 1000L); run.completedAt = new Date(finish * 1000L); run.durationSeconds = finish - start; return run;
    }
}
