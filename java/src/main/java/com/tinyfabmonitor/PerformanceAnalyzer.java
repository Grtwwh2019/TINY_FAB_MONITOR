package com.tinyfabmonitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class PerformanceAnalyzer {
    private static class TaskSnapshot {
        Models.OracleTask task;
        Date startedAt;
        Date completedAt;
        Long executionSeconds;
        Long waitSeconds;
        Date readinessAt;
        Long readyToCompleteSeconds;
        boolean readinessPartial;
        boolean estimatedStart;
        boolean estimatedWait;
        String startBasis = "";
        int estimateSampleCount;
        int anomalyCount;
        long completionOffsetSeconds;
    }

    private static class DaySnapshot {
        String date;
        Map<String, TaskSnapshot> byGroup = new LinkedHashMap<String, TaskSnapshot>();
        Map<String, TaskSnapshot> byFab = new LinkedHashMap<String, TaskSnapshot>();
        TaskSnapshot startTask;
        TaskSnapshot endTask;
        Date start;
        Date finish;
        long durationSeconds;
        boolean complete;
        boolean estimatedStart;
        String startBasis = "";
    }

    private static class Averages {
        Long execution;
        Long wait;
        Long readyToComplete;
        Long completionOffset;
        Date singleCompletedAt;
        Date singleReadinessAt;
        boolean readinessPartial;
        boolean executionEstimated;
        boolean waitEstimated;
        int readyCount;
    }

    private PerformanceAnalyzer() {}

    static String baselineIssue(Models.AnalysisRequest request, String date, List<Models.OracleTask> tasks,
                                List<Models.RunRecord> runs) {
        DaySnapshot day = snapshot(request, date, tasks, runs, Collections.<Models.Dependency>emptyList());
        return baselineUnusableReason(day);
    }

    static Models.AnalysisResult analyze(Models.AnalysisRequest request,
                                         Map<String, List<Models.OracleTask>> tasksByDate,
                                         List<Models.RunRecord> runs,
                                         List<Models.Dependency> allDependencies,
                                         List<String> baselineCandidates,
                                         Date now) {
        Models.AnalysisResult result = new Models.AnalysisResult();
        result.analysisDate = request.analysisDate;
        result.startTaskLabel = taskLabel(request.startThreadId, request.startLevelNo, request.startFabId);
        result.endTaskLabel = taskLabel(request.endThreadId, request.endLevelNo, request.endFabId);

        List<Models.OracleTask> targetTasks = tasksByDate.get(request.analysisDate);
        if (targetTasks == null || targetTasks.isEmpty()) throw new IllegalArgumentException("分析日期没有符合筛选条件的任务");
        List<Models.Dependency> dependencies = dependenciesForTasks(allDependencies, targetTasks);
        result.dependencies.addAll(dependencies);
        DaySnapshot target = snapshot(request, request.analysisDate, targetTasks, runs, dependencies);
        requireBoundaryTasks(target, true);

        int requiredBaselines = request.baselineMode == Models.AnalysisBaselineMode.RECENT_AVERAGE ? request.recentDateCount : 1;
        List<DaySnapshot> baselines = new ArrayList<DaySnapshot>();
        List<String> rejected = new ArrayList<String>();
        for (String date : baselineCandidates) {
            DaySnapshot day = snapshot(request, date, tasksByDate.get(date), runs, dependencies);
            String unusable = baselineUnusableReason(day);
            if (unusable == null) {
                baselines.add(day);
                result.baselineDates.add(date);
                if (baselines.size() >= requiredBaselines) break;
            } else if (rejected.size() < 3) rejected.add(date + "（" + unusable + "）");
        }
        if (baselines.isEmpty()) {
            String detail = rejected.isEmpty() ? "没有读到候选日期任务" : "候选：" + join(rejected, "；");
            throw new IllegalArgumentException("没有可用的基准日期；" + detail);
        }
        result.baselineLabel = result.baselineDates.size() == 1 ? result.baselineDates.get(0) :
            "最近 " + result.baselineDates.size() + " 个结束任务已完成日期平均";

        result.targetComplete = target.complete;
        result.targetEstimatedStart = target.estimatedStart;
        result.targetStart = copy(target.start);
        result.targetFinish = copy(target.finish);
        result.startBasis = target.startBasis;
        result.targetDurationSeconds = target.complete ? target.durationSeconds : target.start == null ? 0L :
            Math.max(0L, (now.getTime() - target.start.getTime()) / 1000L);
        result.baselineDurationSeconds = averageDayDuration(baselines);

        Set<String> critical = criticalPath(target, dependencies, request.startFabId, request.endFabId);
        result.criticalPath.addAll(critical);
        Map<String, Models.AnalysisTaskMetric> metricsByFab = new LinkedHashMap<String, Models.AnalysisTaskMetric>();
        for (TaskSnapshot task : target.byGroup.values()) {
            Models.AnalysisTaskMetric metric = metric(task, baselineAverage(task.task, baselines),
                request.baselineMode == Models.AnalysisBaselineMode.RECENT_AVERAGE);
            metric.criticalPath = critical.contains(normalize(task.task.fabId));
            classify(metric);
            metricsByFab.put(normalize(metric.fabId), metric);
            result.rows.add(metric);
        }
        calculateContributions(result.rows, metricsByFab, dependencies);
        Collections.sort(result.rows, new Comparator<Models.AnalysisTaskMetric>() {
            public int compare(Models.AnalysisTaskMetric left, Models.AnalysisTaskMetric right) {
                int contribution = Long.compare(right.delayContributionSeconds, left.delayContributionSeconds);
                if (contribution != 0) return contribution;
                long rightDelay = right.completionDelaySeconds == null ? Long.MIN_VALUE : right.completionDelaySeconds;
                long leftDelay = left.completionDelaySeconds == null ? Long.MIN_VALUE : left.completionDelaySeconds;
                return Long.compare(rightDelay, leftDelay);
            }
        });

        result.predictedFinish = target.complete ? target.finish : predictFinish(target, baselines, dependencies, runs, request.endFabId, now);
        long comparedDuration = result.predictedFinish != null && target.start != null
            ? Math.max(0L, (result.predictedFinish.getTime() - target.start.getTime()) / 1000L) : result.targetDurationSeconds;
        result.overallDeltaSeconds = comparedDuration - result.baselineDurationSeconds;
        for (Models.AnalysisTaskMetric metric : result.rows) {
            if ("精确执行分析".equals(metric.confidence)) result.preciseCount++;
            else if ("R 区间分析".equals(metric.confidence) || "历史辅助估算".equals(metric.confidence)) result.estimatedCount++;
            else if ("仅完成时间分析".equals(metric.confidence)) result.completionOnlyCount++;
            else result.insufficientCount++;
        }
        buildSummary(result, target);
        return result;
    }

    private static DaySnapshot snapshot(Models.AnalysisRequest request, String date, List<Models.OracleTask> tasks,
                                        List<Models.RunRecord> runs, List<Models.Dependency> dependencies) {
        DaySnapshot day = new DaySnapshot(); day.date = date;
        if (tasks == null) return day;
        Map<String, Models.RunRecord> runByGroup = runsForDate(runs, date);
        for (Models.OracleTask task : latest(tasks)) {
            TaskSnapshot value = new TaskSnapshot(); value.task = task;
            Models.RunRecord run = runByGroup.get(groupKey(task));
            boolean completedInDatabase = "R".equalsIgnoreCase(task.status) && task.actTime != null && !task.actTimePlaceholder;
            if (run != null) {
                boolean matchingCompletedRun = completedInDatabase && run.completedAt != null &&
                    Math.abs(run.completedAt.getTime() - task.actTime.getTime()) < 1000L;
                if (!completedInDatabase || matchingCompletedRun) value.startedAt = copy(run.startedAt);
                if (matchingCompletedRun && run.startedAt != null) {
                    value.executionSeconds = Math.max(0L, (task.actTime.getTime() - run.startedAt.getTime()) / 1000L);
                }
                value.anomalyCount = run.anomalyTimes == null ? 0 : run.anomalyTimes.size();
            }
            if (completedInDatabase) value.completedAt = copy(task.actTime);
            day.byGroup.put(groupKey(task), value);
            TaskSnapshot previous = day.byFab.get(normalize(task.fabId));
            if (previous == null || time(value.completedAt) > time(previous.completedAt)) day.byFab.put(normalize(task.fabId), value);
        }

        for (TaskSnapshot task : day.byGroup.values()) {
            resolveReadiness(task, day.byFab, dependencies);
            estimateMissingStart(task, runs);
        }

        day.startTask = findTask(day, request.startThreadId, request.startLevelNo, request.startFabId);
        day.endTask = findTask(day, request.endThreadId, request.endLevelNo, request.endFabId);
        if (day.startTask != null) resolveBoundaryStart(day, day.startTask, runs);
        if (day.endTask != null && "R".equalsIgnoreCase(day.endTask.task.status)) day.finish = copy(day.endTask.completedAt);
        day.complete = day.finish != null;
        if (day.start != null && day.finish != null) day.durationSeconds = Math.max(0L, (day.finish.getTime() - day.start.getTime()) / 1000L);

        for (TaskSnapshot task : day.byGroup.values()) {
            if (task.completedAt != null && day.start != null) task.completionOffsetSeconds = Math.max(0L, (task.completedAt.getTime() - day.start.getTime()) / 1000L);
            if (task.startedAt != null) {
                if (task.readinessAt != null) {
                    task.waitSeconds = Math.max(0L, (task.startedAt.getTime() - task.readinessAt.getTime()) / 1000L);
                    task.estimatedWait = task.estimatedStart;
                }
            }
        }
        return day;
    }

    private static void resolveBoundaryStart(DaySnapshot day, TaskSnapshot startTask, List<Models.RunRecord> runs) {
        if (startTask.startedAt != null) {
            day.start = copy(startTask.startedAt);
            day.estimatedStart = startTask.estimatedStart;
            day.startBasis = startTask.estimatedStart ? startTask.startBasis : "开始任务 I 时间（精确）";
            return;
        }
        if (startTask.completedAt == null) return;
        DurationTypical typical = historicalTypical(runs, startTask.task);
        Date historicalStart = typical == null ? null : new Date(startTask.completedAt.getTime() - typical.seconds * 1000L);
        if (historicalStart != null && (startTask.readinessAt == null || !historicalStart.before(startTask.readinessAt))) {
            day.start = historicalStart;
            day.startBasis = "开始任务 R 时间减历史执行典型值（" + typical.samples + "次，" + TimingStatistics.confidence(typical.samples) + "）";
        } else if (isLevel20(startTask.task)) {
            return;
        } else {
            day.start = copy(startTask.completedAt);
            day.startBasis = "开始任务仅有 R 时间（低精度估算）";
        }
        day.estimatedStart = true;
    }

    private static void estimateMissingStart(TaskSnapshot task, List<Models.RunRecord> runs) {
        if (task.startedAt != null || task.completedAt == null) return;
        DurationTypical typical = historicalTypical(runs, task.task);
        if (typical == null) return;
        Date estimated = new Date(task.completedAt.getTime() - typical.seconds * 1000L);
        if (estimated.after(task.completedAt) || (task.readinessAt != null && estimated.before(task.readinessAt))) return;
        task.startedAt = estimated;
        task.estimatedStart = true;
        task.estimateSampleCount = typical.samples;
        task.startBasis = "任务自身 R - 历史执行典型值（" + typical.samples + "次，" + TimingStatistics.confidence(typical.samples) + "）";
        task.executionSeconds = Math.max(0L, (task.completedAt.getTime() - task.startedAt.getTime()) / 1000L);
    }

    private static void resolveReadiness(TaskSnapshot task, Map<String, TaskSnapshot> byFab,
                                         List<Models.Dependency> dependencies) {
        if (task == null || isLevel20(task.task)) return;
        boolean hasDependency = false, incomplete = false;
        int eligible = 0;
        Date latest = null;
        for (Models.Dependency edge : dependencies) if (normalize(edge.fabId).equals(normalize(task.task.fabId))) {
            hasDependency = true;
            TaskSnapshot dependency = byFab.get(normalize(edge.dependencyId));
            if (dependency != null && isLevel20(dependency.task)) { task.readinessPartial = true; continue; }
            eligible++;
            if (dependency == null || dependency.completedAt == null) { incomplete = true; continue; }
            if (latest == null || dependency.completedAt.after(latest)) latest = dependency.completedAt;
        }
        if (hasDependency && eligible > 0 && !incomplete && latest != null) {
            task.readinessAt = copy(latest);
            if (task.completedAt != null && !latest.after(task.completedAt)) {
                task.readyToCompleteSeconds = (task.completedAt.getTime() - latest.getTime()) / 1000L;
            }
        }
    }

    private static void requireBoundaryTasks(DaySnapshot day, boolean target) {
        if (day.startTask == null) throw new IllegalArgumentException((target ? "分析日期" : day.date) + "找不到开始基准任务");
        if (day.endTask == null) throw new IllegalArgumentException((target ? "分析日期" : day.date) + "找不到结束基准任务");
        if (day.start == null) {
            if (day.startTask.completedAt != null && isLevel20(day.startTask.task)) {
                throw new IllegalArgumentException((target ? "分析日期" : day.date) +
                    "的 Level 20 开始任务缺少真实 I 和历史执行时长，不能使用循环 Poll 的 R 估算开始时间");
            }
            throw new IllegalArgumentException((target ? "分析日期" : day.date) + "的开始基准任务没有有效 I 或 R 时间");
        }
    }

    private static String baselineUnusableReason(DaySnapshot day) {
        if (day.startTask == null) return "找不到开始任务";
        if (day.endTask == null) return "找不到结束任务";
        if (day.start == null) return day.startTask.completedAt != null && isLevel20(day.startTask.task)
            ? "Level 20 开始任务缺少真实 I 和历史执行时长" : "开始任务没有有效 I/R 时间";
        if (!"R".equalsIgnoreCase(day.endTask.task.status)) return "结束任务状态不是 R";
        if (day.finish == null) return day.endTask.task.actTimePlaceholder ? "结束任务 R 时间是占位值" : "结束任务没有有效 R 时间";
        return null;
    }

    private static Models.AnalysisTaskMetric metric(TaskSnapshot task, Averages baseline, boolean baselineAverageMode) {
        Models.AnalysisTaskMetric value = new Models.AnalysisTaskMetric();
        value.fabId = task.task.fabId; value.fabDescription = task.task.fabDescription;
        value.threadId = task.task.threadId; value.levelNo = task.task.levelNo; value.status = task.task.status;
        value.startedAt = copy(task.startedAt); value.completedAt = copy(task.completedAt);
        value.baselineCompletedAt = copy(baseline.singleCompletedAt);
        value.baselineCompletionAverage = baselineAverageMode;
        value.executionSeconds = task.executionSeconds; value.waitSeconds = task.waitSeconds; value.anomalyCount = task.anomalyCount;
        value.executionEstimated = task.estimatedStart; value.waitEstimated = task.estimatedWait; value.startBasis = task.startBasis;
        value.estimateSampleCount = task.estimateSampleCount;
        value.readinessAt = copy(task.readinessAt); value.readinessPartial = task.readinessPartial;
        value.readyToCompleteSeconds = task.readyToCompleteSeconds;
        value.baselineExecutionSeconds = baseline.execution; value.baselineWaitSeconds = baseline.wait;
        value.baselineExecutionEstimated = baseline.executionEstimated; value.baselineWaitEstimated = baseline.waitEstimated;
        value.baselineCompletionOffsetSeconds = baseline.completionOffset;
        value.baselineReadinessAt = copy(baseline.singleReadinessAt);
        value.baselineReadinessPartial = baseline.readinessPartial;
        value.baselineReadyToCompleteSeconds = baseline.readyToComplete;
        value.completionOffsetSeconds = task.completedAt == null ? null : task.completionOffsetSeconds;
        value.executionDeltaSeconds = difference(value.executionSeconds, value.baselineExecutionSeconds);
        value.waitDeltaSeconds = difference(value.waitSeconds, value.baselineWaitSeconds);
        value.readyToCompleteDeltaSeconds = difference(value.readyToCompleteSeconds, value.baselineReadyToCompleteSeconds);
        value.completionDelaySeconds = difference(value.completionOffsetSeconds, value.baselineCompletionOffsetSeconds);
        return value;
    }

    private static void classify(Models.AnalysisTaskMetric value) {
        if (value.executionDeltaSeconds != null && !value.executionEstimated && !value.baselineExecutionEstimated) {
            value.confidence = "精确执行分析";
            long execution = value.executionDeltaSeconds;
            long waiting = value.waitDeltaSeconds == null ? Long.MIN_VALUE : value.waitDeltaSeconds;
            if (value.anomalyCount > 0 && execution > 0) value.reason = "异常后重新运行/执行耗时增加";
            else if (waiting > 0 && waiting >= execution) value.reason = "等待调度时间增加";
            else if (execution > 0) value.reason = "执行耗时增加";
            else if (value.completionDelaySeconds != null && value.completionDelaySeconds > 0) value.reason = "上游延迟传递";
            else value.reason = "与基准接近或更快";
        } else if (value.readyToCompleteDeltaSeconds != null) {
            value.confidence = "R 区间分析";
            value.reason = value.readyToCompleteDeltaSeconds > 0 ? "依赖就绪到完成阶段增加" : "就绪到完成区间未变慢";
            if (value.readinessPartial || value.baselineReadinessPartial) value.reason += "；Level 20 路径已截止，结果为部分可观测区间";
        } else if (value.executionDeltaSeconds != null) {
            boolean estimated = value.executionEstimated || value.baselineExecutionEstimated || value.waitEstimated || value.baselineWaitEstimated;
            value.confidence = estimated ? "历史辅助估算" : "精确执行分析";
            long execution = value.executionDeltaSeconds;
            long waiting = value.waitDeltaSeconds == null ? Long.MIN_VALUE : value.waitDeltaSeconds;
            if (value.anomalyCount > 0 && execution > 0) value.reason = "异常后重新运行/执行耗时增加";
            else if (waiting > 0 && waiting >= execution) value.reason = "等待调度时间增加";
            else if (execution > 0) value.reason = "执行耗时增加";
            else if (value.completionDelaySeconds != null && value.completionDelaySeconds > 0) value.reason = "上游延迟传递";
            else value.reason = "与基准接近或更快";
            if (estimated && !value.startBasis.isEmpty()) value.reason += "；" + value.startBasis;
        } else if (value.completionDelaySeconds != null) {
            value.confidence = "仅完成时间分析";
            value.reason = value.completionDelaySeconds > 0 ? "完成阶段延迟，缺少I时间" : "完成时间未慢于基准";
        } else {
            value.confidence = "数据不足"; value.reason = "当天或基准缺少有效完成时间";
        }
    }

    private static void calculateContributions(List<Models.AnalysisTaskMetric> rows,
                                               Map<String, Models.AnalysisTaskMetric> byFab,
                                               List<Models.Dependency> dependencies) {
        Map<String, List<String>> upstream = upstream(dependencies);
        for (Models.AnalysisTaskMetric row : rows) {
            if (!row.criticalPath) continue;
            if (row.readyToCompleteDeltaSeconds != null) {
                row.delayContributionSeconds = Math.max(0L, row.readyToCompleteDeltaSeconds);
                continue;
            }
            if (row.completionDelaySeconds == null) continue;
            long prior = 0L;
            List<String> values = upstream.get(normalize(row.fabId));
            if (values != null) for (String id : values) {
                Models.AnalysisTaskMetric dependency = byFab.get(id);
                if (dependency != null && dependency.completionDelaySeconds != null) prior = Math.max(prior, dependency.completionDelaySeconds);
            }
            row.delayContributionSeconds = Math.max(0L, row.completionDelaySeconds - Math.max(0L, prior));
        }
    }

    private static Set<String> criticalPath(DaySnapshot day, List<Models.Dependency> dependencies, String startFab, String endFab) {
        LinkedHashSet<String> reversed = new LinkedHashSet<String>();
        TaskSnapshot current = day.byFab.get(normalize(endFab));
        Map<String, List<String>> upstream = upstream(dependencies);
        Set<String> visiting = new LinkedHashSet<String>();
        while (current != null && visiting.add(normalize(current.task.fabId))) {
            String id = normalize(current.task.fabId); reversed.add(id);
            if (id.equals(normalize(startFab))) break;
            TaskSnapshot latest = null;
            List<String> values = upstream.get(id);
            if (values != null) for (String dependency : values) {
                TaskSnapshot candidate = day.byFab.get(dependency);
                if (candidate != null && !isLevel20(candidate.task) && candidate.completedAt != null &&
                    (latest == null || candidate.completedAt.after(latest.completedAt))) latest = candidate;
            }
            current = latest;
        }
        List<String> order = new ArrayList<String>(reversed); Collections.reverse(order);
        return new LinkedHashSet<String>(order);
    }

    private static Date predictFinish(DaySnapshot day, List<DaySnapshot> baselines, List<Models.Dependency> dependencies,
                                      List<Models.RunRecord> runs, String endFabId, Date now) {
        List<Models.TaskView> views = new ArrayList<Models.TaskView>();
        for (TaskSnapshot snapshot : day.byGroup.values()) {
            Models.TaskView view = new Models.TaskView();
            view.processDate = snapshot.task.processDate; view.threadId = snapshot.task.threadId; view.levelNo = snapshot.task.levelNo;
            view.fabId = snapshot.task.fabId; view.status = snapshot.task.status; view.actTime = copy(snapshot.task.actTime);
            view.actTimePlaceholder = snapshot.task.actTimePlaceholder; view.startedAt = copy(snapshot.startedAt);
            view.readinessAt = copy(snapshot.readinessAt); view.readyToCompleteSeconds = snapshot.readyToCompleteSeconds;
            view.readinessPartial = snapshot.readinessPartial;
            DurationTypical execution = historicalTypical(runs, snapshot.task);
            if (execution != null) { view.executionTypicalSeconds = execution.seconds; view.executionTypicalSampleCount = execution.samples; }
            Averages baseline = baselineAverage(snapshot.task, baselines);
            if (baseline.readyToComplete != null) {
                view.readyToCompleteTypicalSeconds = baseline.readyToComplete;
                view.readyToCompleteSampleCount = baseline.readyCount;
            }
            views.add(view);
        }
        Models.DagEta eta = EtaCalculator.calculate(endFabId, views, dependencies, now);
        return eta.available && !eta.lowerBound ? eta.estimatedCompletion : null;
    }

    private static Averages baselineAverage(Models.TaskKey taskKey, List<DaySnapshot> days) {
        long execution = 0, wait = 0, ready = 0, completion = 0; int ec = 0, wc = 0, rc = 0, cc = 0;
        Averages value = new Averages();
        for (DaySnapshot day : days) {
            TaskSnapshot task = day.byGroup.get(groupKey(taskKey)); if (task == null) continue;
            if (task.executionSeconds != null) { execution += task.executionSeconds; ec++; }
            if (task.executionSeconds != null && task.estimatedStart) value.executionEstimated = true;
            if (task.waitSeconds != null) { wait += task.waitSeconds; wc++; if (task.estimatedWait) value.waitEstimated = true; }
            if (task.readyToCompleteSeconds != null) { ready += task.readyToCompleteSeconds; rc++; if (task.readinessPartial) value.readinessPartial = true; }
            if (task.completedAt != null) { completion += task.completionOffsetSeconds; cc++; }
        }
        value.execution = ec == 0 ? null : execution / ec; value.wait = wc == 0 ? null : wait / wc;
        value.readyToComplete = rc == 0 ? null : ready / rc;
        value.readyCount = rc;
        value.completionOffset = cc == 0 ? null : completion / cc;
        if (days.size() == 1) {
            TaskSnapshot task = days.get(0).byGroup.get(groupKey(taskKey));
            if (task != null) { value.singleCompletedAt = copy(task.completedAt); value.singleReadinessAt = copy(task.readinessAt); }
        }
        return value;
    }

    private static long averageDayDuration(List<DaySnapshot> days) {
        long total = 0; for (DaySnapshot day : days) total += day.durationSeconds; return total / days.size();
    }

    private static void buildSummary(Models.AnalysisResult result, DaySnapshot target) {
        Models.AnalysisTaskMetric bottleneck = result.rows.isEmpty() ? null : result.rows.get(0);
        String delta = (result.overallDeltaSeconds >= 0 ? "慢 " : "快 ") + UiFormat.duration(Math.abs(result.overallDeltaSeconds));
        if (target.complete) result.summary = result.analysisDate + " 比 " + result.baselineLabel + " 整体" + delta;
        else if (result.predictedFinish != null) result.summary = result.analysisDate + " 的结束任务尚未完成，预计 " + UiFormat.dateTime(result.predictedFinish) + " 完成，预计整体" + delta;
        else result.summary = result.analysisDate + " 的结束任务尚未完成，当前已运行 " + UiFormat.duration(result.targetDurationSeconds) + "，暂时无法预测完成时间";
        String basis = result.startBasis.isEmpty() ? "开始时间不可用" : result.startBasis;
        if (bottleneck != null) result.detail = "区间：" + result.startTaskLabel + " → " + result.endTaskLabel + "；开始依据：" + basis +
            "；主要候选慢点：" + bottleneck.fabId + "（" + bottleneck.reason + "）。精确 " + result.preciseCount +
            "，估算 " + result.estimatedCount + "，仅完成时间 " + result.completionOnlyCount + "，数据不足 " + result.insufficientCount + "。";
    }

    private static List<Models.Dependency> dependenciesForTasks(List<Models.Dependency> dependencies, List<Models.OracleTask> tasks) {
        Set<String> allowed = new LinkedHashSet<String>();
        for (Models.OracleTask task : tasks) allowed.add(normalize(task.fabId));
        Map<String, Models.Dependency> unique = new LinkedHashMap<String, Models.Dependency>();
        for (Models.Dependency edge : dependencies) {
            String owner = normalize(edge.fabId), dependency = normalize(edge.dependencyId);
            if (allowed.contains(owner) && allowed.contains(dependency)) unique.put(dependency + "->" + owner, edge);
        }
        return new ArrayList<Models.Dependency>(unique.values());
    }

    private static Map<String, Models.RunRecord> runsForDate(List<Models.RunRecord> runs, String date) {
        Map<String, Models.RunRecord> result = new HashMap<String, Models.RunRecord>();
        for (Models.RunRecord run : runs) {
            if (run == null || run.task == null || !date.equals(run.task.processDate)) continue;
            String key = groupKey(run.task); Models.RunRecord previous = result.get(key);
            if (previous == null || eventTime(run) > eventTime(previous)) result.put(key, run);
        }
        return result;
    }

    private static DurationTypical historicalTypical(List<Models.RunRecord> runs, Models.TaskKey task) {
        List<Long> values = new ArrayList<Long>();
        for (Models.RunRecord run : runs) {
            if (run == null || run.task == null || run.startedAt == null || run.completedAt == null) continue;
            if (!groupKey(run.task).equals(groupKey(task))) continue;
            if (run.task.processDate != null && task.processDate != null && run.task.processDate.compareTo(task.processDate) >= 0) continue;
            if (run.durationSeconds >= 0) values.add(run.durationSeconds);
        }
        return values.isEmpty() ? null : new DurationTypical(TimingStatistics.median(values), values.size());
    }

    private static final class DurationTypical {
        final long seconds; final int samples;
        DurationTypical(long seconds, int samples) { this.seconds = seconds; this.samples = samples; }
    }

    private static TaskSnapshot findTask(DaySnapshot day, String thread, String level, String fab) {
        return day.byGroup.get(groupKey(thread, level, fab));
    }

    private static List<Models.OracleTask> latest(List<Models.OracleTask> tasks) { return MonitorService.selectLatestTasks(tasks); }

    private static Map<String, List<String>> upstream(List<Models.Dependency> dependencies) {
        Map<String, List<String>> result = new HashMap<String, List<String>>();
        for (Models.Dependency edge : dependencies) {
            String owner = normalize(edge.fabId), dependency = normalize(edge.dependencyId);
            List<String> values = result.get(owner); if (values == null) { values = new ArrayList<String>(); result.put(owner, values); }
            if (!values.contains(dependency)) values.add(dependency);
        }
        return result;
    }

    private static Long difference(Long left, Long right) { return left == null || right == null ? null : left - right; }
    private static long eventTime(Models.RunRecord run) { if (run.completedAt != null) return run.completedAt.getTime(); if (run.startedAt != null) return run.startedAt.getTime(); return 0L; }
    private static long time(Date value) { return value == null ? Long.MIN_VALUE : value.getTime(); }
    private static Date copy(Date value) { return value == null ? null : new Date(value.getTime()); }
    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static boolean isLevel20(Models.TaskKey task) { return task != null && "20".equals(normalize(task.levelNo)); }
    private static String groupKey(Models.TaskKey value) { return groupKey(value.threadId, value.levelNo, value.fabId); }
    private static String groupKey(String thread, String level, String fab) { return normalize(thread) + "|" + normalize(level) + "|" + normalize(fab); }
    private static String taskLabel(String thread, String level, String fab) { return thread + "/" + level + "/" + fab; }
    private static String join(List<String> values, String delimiter) { StringBuilder result = new StringBuilder(); for (String value : values) { if (result.length() > 0) result.append(delimiter); result.append(value); } return result.toString(); }
}
