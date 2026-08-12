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
        int anomalyCount;
        long completionOffsetSeconds;
    }

    private static class DaySnapshot {
        String date;
        Map<String, TaskSnapshot> byGroup = new LinkedHashMap<String, TaskSnapshot>();
        Map<String, TaskSnapshot> byFab = new LinkedHashMap<String, TaskSnapshot>();
        Date start;
        Date finish;
        long durationSeconds;
        boolean complete;
        boolean estimatedStart;
    }

    private static class Averages {
        Long execution;
        Long wait;
        Long completionOffset;
    }

    private PerformanceAnalyzer() {}

    static Models.AnalysisResult analyze(Models.AnalysisRequest request,
                                         Map<String, List<Models.OracleTask>> tasksByDate,
                                         List<Models.RunRecord> runs,
                                         List<Models.Dependency> dependencies,
                                         List<String> baselineDates,
                                         Date now) {
        Models.AnalysisResult result = new Models.AnalysisResult();
        result.analysisDate = request.analysisDate;
        result.baselineDates.addAll(baselineDates);
        result.baselineLabel = baselineDates.size() == 1 ? baselineDates.get(0) : "最近 " + baselineDates.size() + " 个完整业务日期平均";
        result.dependencies.addAll(dependencies);

        DaySnapshot target = snapshot(request.analysisDate, tasksByDate.get(request.analysisDate), runs, dependencies);
        if (target.byGroup.isEmpty()) throw new IllegalArgumentException("分析日期没有符合筛选条件的任务");
        List<DaySnapshot> baselines = new ArrayList<DaySnapshot>();
        for (String date : baselineDates) {
            DaySnapshot day = snapshot(date, tasksByDate.get(date), runs, dependencies);
            if (!day.byGroup.isEmpty() && day.complete) baselines.add(day);
        }
        if (baselines.isEmpty()) throw new IllegalArgumentException("没有可用的完整基准日期");

        result.targetComplete = target.complete;
        result.targetEstimatedStart = target.estimatedStart;
        result.targetStart = target.start;
        result.targetFinish = target.finish;
        result.targetDurationSeconds = target.complete ? target.durationSeconds : target.start == null ? 0L : Math.max(0L, (now.getTime() - target.start.getTime()) / 1000L);
        result.baselineDurationSeconds = averageDayDuration(baselines);

        Set<String> critical = criticalPath(target, dependencies);
        result.criticalPath.addAll(critical);
        Map<String, Models.AnalysisTaskMetric> metricsByFab = new LinkedHashMap<String, Models.AnalysisTaskMetric>();
        for (TaskSnapshot task : target.byGroup.values()) {
            Models.AnalysisTaskMetric metric = metric(task, baselineAverage(task.task.groupId(), baselines));
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

        result.predictedFinish = target.complete ? target.finish : predictFinish(target, dependencies, runs, now);
        long comparedDuration = result.predictedFinish != null && target.start != null
            ? Math.max(0L, (result.predictedFinish.getTime() - target.start.getTime()) / 1000L) : result.targetDurationSeconds;
        result.overallDeltaSeconds = comparedDuration - result.baselineDurationSeconds;
        for (Models.AnalysisTaskMetric metric : result.rows) {
            if ("精确分析".equals(metric.confidence)) result.preciseCount++;
            else if ("完成时间分析".equals(metric.confidence)) result.completionOnlyCount++;
            else result.insufficientCount++;
        }
        buildSummary(result, target);
        return result;
    }

    private static DaySnapshot snapshot(String date, List<Models.OracleTask> tasks, List<Models.RunRecord> runs,
                                        List<Models.Dependency> dependencies) {
        DaySnapshot day = new DaySnapshot(); day.date = date;
        if (tasks == null) return day;
        Map<String, Models.RunRecord> runByGroup = runsForDate(runs, date);
        for (Models.OracleTask task : latest(tasks)) {
            TaskSnapshot value = new TaskSnapshot(); value.task = task;
            Models.RunRecord run = runByGroup.get(task.groupId());
            if (run != null) {
                value.startedAt = copy(run.startedAt); value.completedAt = copy(run.completedAt);
                if (run.startedAt != null && run.completedAt != null) value.executionSeconds = run.durationSeconds;
                value.anomalyCount = run.anomalyTimes == null ? 0 : run.anomalyTimes.size();
            }
            if (value.completedAt == null && "R".equalsIgnoreCase(task.status) && task.actTime != null && !task.actTimePlaceholder) value.completedAt = copy(task.actTime);
            day.byGroup.put(task.groupId(), value);
            TaskSnapshot previous = day.byFab.get(normalize(task.fabId));
            if (previous == null || time(value.completedAt) > time(previous.completedAt)) day.byFab.put(normalize(task.fabId), value);
        }
        day.complete = !day.byGroup.isEmpty();
        for (TaskSnapshot task : day.byGroup.values()) {
            Date effectiveStart = task.startedAt != null ? task.startedAt : task.completedAt;
            if (task.startedAt == null && task.completedAt != null) day.estimatedStart = true;
            if (effectiveStart != null && (day.start == null || effectiveStart.before(day.start))) day.start = effectiveStart;
            if (task.completedAt != null && (day.finish == null || task.completedAt.after(day.finish))) day.finish = task.completedAt;
            if (!"R".equalsIgnoreCase(task.task.status) || task.completedAt == null) day.complete = false;
        }
        if (day.start != null && day.finish != null) day.durationSeconds = Math.max(0L, (day.finish.getTime() - day.start.getTime()) / 1000L);
        for (TaskSnapshot task : day.byGroup.values()) {
            if (task.completedAt != null && day.start != null) task.completionOffsetSeconds = Math.max(0L, (task.completedAt.getTime() - day.start.getTime()) / 1000L);
            if (task.startedAt != null) {
                Date predecessorFinish = latestPredecessorFinish(task.task.fabId, day.byFab, dependencies);
                if (predecessorFinish != null) task.waitSeconds = Math.max(0L, (task.startedAt.getTime() - predecessorFinish.getTime()) / 1000L);
            }
        }
        return day;
    }

    private static Models.AnalysisTaskMetric metric(TaskSnapshot task, Averages baseline) {
        Models.AnalysisTaskMetric value = new Models.AnalysisTaskMetric();
        value.fabId = task.task.fabId; value.fabDescription = task.task.fabDescription;
        value.threadId = task.task.threadId; value.levelNo = task.task.levelNo; value.status = task.task.status;
        value.startedAt = copy(task.startedAt); value.completedAt = copy(task.completedAt);
        value.executionSeconds = task.executionSeconds; value.waitSeconds = task.waitSeconds; value.anomalyCount = task.anomalyCount;
        value.baselineExecutionSeconds = baseline.execution; value.baselineWaitSeconds = baseline.wait;
        value.baselineCompletionOffsetSeconds = baseline.completionOffset;
        value.completionOffsetSeconds = task.completedAt == null ? null : task.completionOffsetSeconds;
        value.executionDeltaSeconds = difference(value.executionSeconds, value.baselineExecutionSeconds);
        value.waitDeltaSeconds = difference(value.waitSeconds, value.baselineWaitSeconds);
        value.completionDelaySeconds = difference(value.completionOffsetSeconds, value.baselineCompletionOffsetSeconds);
        return value;
    }

    private static void classify(Models.AnalysisTaskMetric value) {
        if (value.executionDeltaSeconds != null) {
            value.confidence = "精确分析";
            long execution = value.executionDeltaSeconds;
            long waiting = value.waitDeltaSeconds == null ? Long.MIN_VALUE : value.waitDeltaSeconds;
            if (value.anomalyCount > 0 && execution > 0) value.reason = "异常后重新运行/执行耗时增加";
            else if (waiting > 0 && waiting >= execution) value.reason = "等待调度时间增加";
            else if (execution > 0) value.reason = "执行耗时增加";
            else if (value.completionDelaySeconds != null && value.completionDelaySeconds > 0) value.reason = "上游延迟传递";
            else value.reason = "与基准接近或更快";
        } else if (value.completionDelaySeconds != null) {
            value.confidence = "完成时间分析";
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
            if (!row.criticalPath || row.completionDelaySeconds == null) continue;
            long prior = 0L;
            List<String> values = upstream.get(normalize(row.fabId));
            if (values != null) for (String id : values) {
                Models.AnalysisTaskMetric dependency = byFab.get(id);
                if (dependency != null && dependency.completionDelaySeconds != null) prior = Math.max(prior, dependency.completionDelaySeconds);
            }
            row.delayContributionSeconds = Math.max(0L, row.completionDelaySeconds - Math.max(0L, prior));
        }
    }

    private static Set<String> criticalPath(DaySnapshot day, List<Models.Dependency> dependencies) {
        LinkedHashSet<String> reversed = new LinkedHashSet<String>();
        TaskSnapshot end = null;
        for (TaskSnapshot task : day.byGroup.values()) if (task.completedAt != null && (end == null || task.completedAt.after(end.completedAt))) end = task;
        Map<String, List<String>> upstream = upstream(dependencies);
        Set<String> visiting = new LinkedHashSet<String>();
        while (end != null && visiting.add(normalize(end.task.fabId))) {
            String id = normalize(end.task.fabId); reversed.add(id);
            TaskSnapshot latest = null;
            List<String> values = upstream.get(id);
            if (values != null) for (String dependency : values) {
                TaskSnapshot candidate = day.byFab.get(dependency);
                if (candidate != null && candidate.completedAt != null && (latest == null || candidate.completedAt.after(latest.completedAt))) latest = candidate;
            }
            end = latest;
        }
        List<String> order = new ArrayList<String>(reversed); Collections.reverse(order);
        return new LinkedHashSet<String>(order);
    }

    private static Date predictFinish(DaySnapshot day, List<Models.Dependency> dependencies,
                                      List<Models.RunRecord> runs, Date now) {
        Map<String, Models.GroupStat> stats = MonitorService.buildGroupStats(runs);
        List<Models.TaskView> views = new ArrayList<Models.TaskView>();
        for (TaskSnapshot snapshot : day.byGroup.values()) {
            Models.TaskView view = new Models.TaskView();
            view.processDate = snapshot.task.processDate; view.threadId = snapshot.task.threadId; view.levelNo = snapshot.task.levelNo;
            view.fabId = snapshot.task.fabId; view.status = snapshot.task.status; view.actTime = copy(snapshot.task.actTime);
            view.actTimePlaceholder = snapshot.task.actTimePlaceholder; view.startedAt = copy(snapshot.startedAt);
            Models.GroupStat stat = stats.get(snapshot.task.groupId());
            if (stat != null) { view.averageDurationSeconds = stat.average; view.completedRunCount = stat.count; }
            views.add(view);
        }
        Date latest = day.finish;
        for (Models.TaskView view : views) {
            if ("R".equalsIgnoreCase(view.status)) continue;
            Models.DagEta eta = EtaCalculator.calculate(view.fabId, views, dependencies, now);
            if (eta.available && (latest == null || eta.estimatedCompletion.after(latest))) latest = eta.estimatedCompletion;
        }
        return latest;
    }

    private static Averages baselineAverage(String groupId, List<DaySnapshot> days) {
        long execution = 0, wait = 0, completion = 0; int ec = 0, wc = 0, cc = 0;
        for (DaySnapshot day : days) {
            TaskSnapshot task = day.byGroup.get(groupId); if (task == null) continue;
            if (task.executionSeconds != null) { execution += task.executionSeconds; ec++; }
            if (task.waitSeconds != null) { wait += task.waitSeconds; wc++; }
            if (task.completedAt != null) { completion += task.completionOffsetSeconds; cc++; }
        }
        Averages value = new Averages();
        value.execution = ec == 0 ? null : execution / ec; value.wait = wc == 0 ? null : wait / wc;
        value.completionOffset = cc == 0 ? null : completion / cc; return value;
    }

    private static long averageDayDuration(List<DaySnapshot> days) { long total = 0; for (DaySnapshot day : days) total += day.durationSeconds; return total / days.size(); }

    private static void buildSummary(Models.AnalysisResult result, DaySnapshot target) {
        Models.AnalysisTaskMetric bottleneck = result.rows.isEmpty() ? null : result.rows.get(0);
        String delta = (result.overallDeltaSeconds >= 0 ? "慢 " : "快 ") + UiFormat.duration(Math.abs(result.overallDeltaSeconds));
        if (target.complete) result.summary = result.analysisDate + " 比 " + result.baselineLabel + " 整体" + delta;
        else if (result.predictedFinish != null) result.summary = result.analysisDate + " 尚未完成，预计完成 " + UiFormat.dateTime(result.predictedFinish) + "，预计整体" + delta;
        else result.summary = result.analysisDate + " 尚未完成，当前已运行 " + UiFormat.duration(result.targetDurationSeconds) + "，暂时无法预测最终完成时间";
        if (bottleneck != null) result.detail = "主要候选慢点：" + bottleneck.fabId + "；" + bottleneck.reason +
            "。精确分析 " + result.preciseCount + " 个，完成时间分析 " + result.completionOnlyCount + " 个，数据不足 " + result.insufficientCount + " 个。";
    }

    private static Map<String, Models.RunRecord> runsForDate(List<Models.RunRecord> runs, String date) {
        Map<String, Models.RunRecord> result = new HashMap<String, Models.RunRecord>();
        for (Models.RunRecord run : runs) {
            if (run == null || run.task == null || !date.equals(run.task.processDate)) continue;
            Models.RunRecord previous = result.get(run.task.groupId());
            if (previous == null || eventTime(run) > eventTime(previous)) result.put(run.task.groupId(), run);
        }
        return result;
    }

    private static List<Models.OracleTask> latest(List<Models.OracleTask> tasks) { return MonitorService.selectLatestTasks(tasks); }

    private static Date latestPredecessorFinish(String fabId, Map<String, TaskSnapshot> byFab, List<Models.Dependency> dependencies) {
        Date latest = null;
        for (Models.Dependency edge : dependencies) if (normalize(edge.fabId).equals(normalize(fabId))) {
            TaskSnapshot task = byFab.get(normalize(edge.dependencyId));
            if (task != null && task.completedAt != null && (latest == null || task.completedAt.after(latest))) latest = task.completedAt;
        }
        return latest;
    }

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
}
