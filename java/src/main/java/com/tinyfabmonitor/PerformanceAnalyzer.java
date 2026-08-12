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
        Long completionOffset;
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
            Models.AnalysisTaskMetric metric = metric(task, baselineAverage(task.task, baselines));
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

        result.predictedFinish = target.complete ? target.finish : predictFinish(target, dependencies, runs, request.endFabId, now);
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

        day.startTask = findTask(day, request.startThreadId, request.startLevelNo, request.startFabId);
        day.endTask = findTask(day, request.endThreadId, request.endLevelNo, request.endFabId);
        if (day.startTask != null) resolveBoundaryStart(day, day.startTask, runs);
        if (day.endTask != null && "R".equalsIgnoreCase(day.endTask.task.status)) day.finish = copy(day.endTask.completedAt);
        day.complete = day.finish != null;
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

    private static void resolveBoundaryStart(DaySnapshot day, TaskSnapshot startTask, List<Models.RunRecord> runs) {
        if (startTask.startedAt != null) {
            day.start = copy(startTask.startedAt); day.startBasis = "开始任务 I 时间（精确）"; return;
        }
        if (startTask.completedAt == null) return;
        Long average = historicalAverage(runs, startTask.task);
        if (average != null) {
            day.start = new Date(startTask.completedAt.getTime() - average * 1000L);
            day.startBasis = "开始任务 R 时间减历史平均（估算）";
        } else {
            day.start = copy(startTask.completedAt);
            day.startBasis = "开始任务仅有 R 时间（低精度估算）";
        }
        day.estimatedStart = true;
    }

    private static void requireBoundaryTasks(DaySnapshot day, boolean target) {
        if (day.startTask == null) throw new IllegalArgumentException((target ? "分析日期" : day.date) + "找不到开始基准任务");
        if (day.endTask == null) throw new IllegalArgumentException((target ? "分析日期" : day.date) + "找不到结束基准任务");
        if (day.start == null) throw new IllegalArgumentException((target ? "分析日期" : day.date) + "的开始基准任务没有有效 I 或 R 时间");
    }

    private static String baselineUnusableReason(DaySnapshot day) {
        if (day.startTask == null) return "找不到开始任务";
        if (day.endTask == null) return "找不到结束任务";
        if (day.start == null) return "开始任务没有有效 I/R 时间";
        if (!"R".equalsIgnoreCase(day.endTask.task.status)) return "结束任务状态不是 R";
        if (day.finish == null) return day.endTask.task.actTimePlaceholder ? "结束任务 R 时间是占位值" : "结束任务没有有效 R 时间";
        return null;
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
                if (candidate != null && candidate.completedAt != null && (latest == null || candidate.completedAt.after(latest.completedAt))) latest = candidate;
            }
            current = latest;
        }
        List<String> order = new ArrayList<String>(reversed); Collections.reverse(order);
        return new LinkedHashSet<String>(order);
    }

    private static Date predictFinish(DaySnapshot day, List<Models.Dependency> dependencies,
                                      List<Models.RunRecord> runs, String endFabId, Date now) {
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
        Models.DagEta eta = EtaCalculator.calculate(endFabId, views, dependencies, now);
        return eta.available ? eta.estimatedCompletion : null;
    }

    private static Averages baselineAverage(Models.TaskKey taskKey, List<DaySnapshot> days) {
        long execution = 0, wait = 0, completion = 0; int ec = 0, wc = 0, cc = 0;
        for (DaySnapshot day : days) {
            TaskSnapshot task = day.byGroup.get(groupKey(taskKey)); if (task == null) continue;
            if (task.executionSeconds != null) { execution += task.executionSeconds; ec++; }
            if (task.waitSeconds != null) { wait += task.waitSeconds; wc++; }
            if (task.completedAt != null) { completion += task.completionOffsetSeconds; cc++; }
        }
        Averages value = new Averages();
        value.execution = ec == 0 ? null : execution / ec; value.wait = wc == 0 ? null : wait / wc;
        value.completionOffset = cc == 0 ? null : completion / cc; return value;
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
            "，仅完成时间 " + result.completionOnlyCount + "，数据不足 " + result.insufficientCount + "。";
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

    private static Long historicalAverage(List<Models.RunRecord> runs, Models.TaskKey task) {
        long total = 0L; int count = 0;
        for (Models.RunRecord run : runs) {
            if (run == null || run.task == null || run.startedAt == null || run.completedAt == null) continue;
            if (!groupKey(run.task).equals(groupKey(task))) continue;
            if (run.task.processDate != null && task.processDate != null && run.task.processDate.compareTo(task.processDate) >= 0) continue;
            total += run.durationSeconds; count++;
        }
        return count == 0 ? null : total / count;
    }

    private static TaskSnapshot findTask(DaySnapshot day, String thread, String level, String fab) {
        return day.byGroup.get(groupKey(thread, level, fab));
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
    private static String groupKey(Models.TaskKey value) { return groupKey(value.threadId, value.levelNo, value.fabId); }
    private static String groupKey(String thread, String level, String fab) { return normalize(thread) + "|" + normalize(level) + "|" + normalize(fab); }
    private static String taskLabel(String thread, String level, String fab) { return thread + "/" + level + "/" + fab; }
    private static String join(List<String> values, String delimiter) { StringBuilder result = new StringBuilder(); for (String value : values) { if (result.length() > 0) result.append(delimiter); result.append(value); } return result.toString(); }
}
