package com.tinyfabmonitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure-R recursive ETA. Level 40 is the fixed, completed boundary of every path. */
final class EtaCalculator {
    private static final class Estimate {
        boolean available;
        long p50;
        long p75;
        long lowerBound;
        int sampleCount;
        String confidence = "";
        List<List<String>> paths = new ArrayList<List<String>>();
        List<String> issues = new ArrayList<String>();
    }

    private EtaCalculator() {}

    static Models.DagEta calculate(String rootFabId, List<Models.TaskView> tasks,
                                   List<Models.Dependency> edges, Date now) {
        return calculate(rootFabId, tasks, edges, now, now, 30);
    }

    static Models.DagEta calculate(String rootFabId, List<Models.TaskView> tasks,
                                   List<Models.Dependency> edges, Date now,
                                   Date nextPollAt, int manualInterventionMinutes) {
        Models.DagEta result = new Models.DagEta();
        Index index = index(tasks);
        String rootId = normalize(rootFabId);
        Models.TaskView root = index.unique.get(rootId);
        if (root == null) {
            result.summary = index.all.containsKey(rootId) ? "预计完成：中心 FAB 映射到多个任务" : "预计完成：找不到中心 FAB 状态";
            result.detail = "ETA 要求当前业务日期中每个 FAB 唯一对应一个 Thread/Level 任务。";
            return result;
        }
        if (validR(root)) {
            result.completed = true; result.available = true;
            result.estimatedCompletion = copy(root.actTime); result.conservativeCompletion = copy(root.actTime);
            result.summary = "已完成：" + UiFormat.dateTime(root.actTime);
            result.detail = "中心 FAB 已进入 R；占位时间已排除，显示数据库实际完成时间。";
            return result;
        }

        Map<String, List<String>> upstream = adjacency(edges);
        Estimate estimate = estimate(rootId, index, upstream, new LinkedHashMap<String, Estimate>(),
            new LinkedHashSet<String>(), now.getTime(), nextPollAt, manualInterventionMinutes, result);
        result.issues.addAll(estimate.issues);
        result.sampleCount = estimate.sampleCount;
        result.confidence = estimate.confidence;
        result.criticalPaths = stringifyPaths(estimate.paths);
        if (!estimate.paths.isEmpty()) result.criticalPath.addAll(estimate.paths.get(0));
        Models.TaskEta rootEta = result.taskEtas.get(rootId);
        if (rootEta != null) copyRootStats(result, rootEta);
        collectCheckpoints(result, nextPollAt);

        if (!estimate.available) {
            result.lowerBound = estimate.lowerBound > 0L;
            if (result.lowerBound) result.estimatedCompletion = new Date(estimate.lowerBound);
            result.summary = result.lowerBound ? "已知路径参考下限：" + UiFormat.dateTime(result.estimatedCompletion) : "预计完成：无法可靠估算";
            result.detail = join(estimate.issues, "；") + (result.lowerBound ? "；存在未知必要路径，因此不生成完整 ETA。" : "") +
                (result.historicalRangeExceeded ? "；立即人工检查；下一个 Checkpoint 为下次自动刷新 " + UiFormat.dateTime(result.nextCheckpoint) : "") +
                (result.manualInterventionRequired ? "；已到硬阈值，必须人工介入" : "");
            return result;
        }

        result.available = true; result.estimatedCompletion = new Date(estimate.p50);
        result.conservativeCompletion = new Date(estimate.p75);
        result.remainingSeconds = (estimate.p50 - now.getTime()) / 1000L;
        result.overdue = result.remainingSeconds < 0L;
        result.summary = "预计完成 P50 " + UiFormat.dateTime(result.estimatedCompletion) +
            " / P75 " + UiFormat.dateTime(result.conservativeCompletion) + "（" + result.confidence + "）";
        result.detail = "历史范围 " + UiFormat.duration(result.historicalMinimumSeconds) + "–" +
            UiFormat.duration(result.historicalMaximumSeconds) + "；关键路径：" +
            (result.criticalPaths.isEmpty() ? "--" : join(result.criticalPaths, "；")) +
            "；Checkpoint：" + checkpointText(result);
        return result;
    }

    private static Estimate estimate(String id, Index index, Map<String, List<String>> upstream,
                                     Map<String, Estimate> memo, Set<String> visiting,
                                     long now, Date nextPoll, int graceMinutes, Models.DagEta output) {
        Estimate cached = memo.get(id); if (cached != null) return cached;
        Estimate value = new Estimate();
        Models.TaskView task = index.unique.get(id);
        if (task == null) return issue(value, index.all.containsKey(id) ? "FAB " + id + " 映射到多个任务" : "FAB " + id + " 不属于当前业务日期");
        Models.TaskEta taskEta = new Models.TaskEta(); taskEta.fabId = task.fabId; output.taskEtas.put(id, taskEta);
        int level = level(task);
        if (level < 40) return issue(value, "FAB " + task.fabId + " 的 Level 小于 40，超出 ETA 边界");
        if (!visiting.add(id)) return issue(value, "发现循环依赖：" + task.fabId);
        try {
            if (level == 40) {
                taskEta.boundary = true;
                if (!validR(task)) { taskEta.reason = "Level 40 边界缺少有效真实 R"; return issue(value, "Level 40 边界 " + task.fabId + " 缺少有效真实 R"); }
                completed(value, task); completed(taskEta, task); return memo(memo, id, value);
            }
            if (validR(task)) { completed(value, task); completed(taskEta, task); return memo(memo, id, value); }
            String status = normalize(task.status);
            if ("E".equals(status) || "B".equals(status)) {
                taskEta.reason = "状态 " + status + " 不生成可靠 ETA";
                return issue(value, "FAB " + task.fabId + " 当前为 " + status + "，必要路径 ETA 已取消");
            }
            List<String> deps = upstream.get(id);
            if (deps == null || deps.isEmpty()) return issue(value, "FAB " + task.fabId + " 在 Level 40 以上但没有前置依赖");
            long ready50 = Long.MIN_VALUE, ready75 = Long.MIN_VALUE, lower = Long.MIN_VALUE;
            boolean allAvailable = true, allActualR = true;
            List<List<String>> critical = new ArrayList<List<String>>();
            int pathSamples = Integer.MAX_VALUE; String pathConfidence = "";
            Date actualReadiness = null; List<String> actualDrivers = new ArrayList<String>();
            for (String dep : deps) {
                Models.TaskView depTask = index.unique.get(dep);
                if (depTask == null || !validR(depTask)) allActualR = false;
                else if (actualReadiness == null || depTask.actTime.after(actualReadiness)) {
                    actualReadiness = depTask.actTime; actualDrivers.clear(); actualDrivers.add(depTask.fabId);
                } else if (depTask.actTime.equals(actualReadiness)) actualDrivers.add(depTask.fabId);
                Estimate candidate = estimate(dep, index, upstream, memo, visiting, now, nextPoll, graceMinutes, output);
                value.issues.addAll(candidate.issues);
                if (!candidate.available) allAvailable = false;
                else {
                    if (candidate.p50 > ready50) { ready50 = candidate.p50; critical = copyPaths(candidate.paths); }
                    else if (candidate.p50 == ready50) addPaths(critical, candidate.paths);
                    ready75 = Math.max(ready75, candidate.p75);
                    pathSamples = Math.min(pathSamples, candidate.sampleCount == 0 ? Integer.MAX_VALUE : candidate.sampleCount);
                    if (pathConfidence.isEmpty() || confidenceRank(candidate.confidence) < confidenceRank(pathConfidence)) pathConfidence = candidate.confidence;
                }
                lower = Math.max(lower, candidate.available ? candidate.p50 : candidate.lowerBound);
            }
            if (!allAvailable) { value.lowerBound = lower; taskEta.reason = join(value.issues, "；"); return memo(memo, id, value); }
            if (task.readyToCompleteSamples == null || task.readyToCompleteSamples.isEmpty()) {
                value.lowerBound = Math.max(lower, ready50); taskEta.reason = "没有历史“依赖就绪→R”样本";
                return issue(value, "FAB " + task.fabId + " 没有历史“依赖就绪→R”样本");
            }

            List<Long> samples = new ArrayList<Long>(task.readyToCompleteSamples);
            long minimum = Collections.min(samples), maximum = Collections.max(samples);
            long duration50, duration75;
            if (allActualR && actualReadiness != null) {
                long elapsed = Math.max(0L, (now - actualReadiness.getTime()) / 1000L);
                List<Long> survivors = new ArrayList<Long>(); for (Long sample : samples) if (sample > elapsed) survivors.add(sample);
                fillTaskStats(taskEta, task, actualReadiness, actualDrivers, samples, survivors, elapsed, now, nextPoll, graceMinutes);
                if (survivors.isEmpty()) {
                    taskEta.historicalRangeExceeded = true; taskEta.checkpoint = true;
                    taskEta.nextCheckpoint = copy(nextPoll); taskEta.reason = "已超过全部历史区间，无法可靠预测";
                    value.lowerBound = now; value.issues.add("FAB " + task.fabId + " 已等待 " + UiFormat.duration(elapsed) +
                        "，超过历史最大值 " + UiFormat.duration(maximum));
                    return memo(memo, id, value);
                }
                duration50 = TimingStatistics.percentile(survivors, 50); duration75 = TimingStatistics.percentile(survivors, 75);
                ready50 = actualReadiness.getTime(); ready75 = actualReadiness.getTime();
                taskEta.conditionalSampleCount = survivors.size();
            } else {
                fillTaskStats(taskEta, task, null, Collections.<String>emptyList(), samples, samples, 0L, now, nextPoll, graceMinutes);
                duration50 = TimingStatistics.percentile(samples, 50); duration75 = TimingStatistics.percentile(samples, 75);
            }
            value.available = true; value.p50 = ready50 + duration50 * 1000L; value.p75 = ready75 + duration75 * 1000L;
            value.sampleCount = Math.min(samples.size(), pathSamples == Integer.MAX_VALUE ? samples.size() : pathSamples);
            value.confidence = weaker(pathConfidence, TimingStatistics.confidence(samples.size(), TimingStatistics.median(samples), minimum, maximum));
            for (List<String> path : critical) { List<String> appended = new ArrayList<String>(path); appended.add(task.fabId); value.paths.add(appended); }
            taskEta.available = true; taskEta.p50Completion = new Date(value.p50); taskEta.p75Completion = new Date(value.p75);
            taskEta.p50Seconds = duration50; taskEta.p75Seconds = duration75; taskEta.confidence = value.confidence; taskEta.paths = stringifyPaths(value.paths);
            return memo(memo, id, value);
        } finally { visiting.remove(id); }
    }

    private static void fillTaskStats(Models.TaskEta eta, Models.TaskView task, Date readiness, List<String> drivers,
                                      List<Long> all, List<Long> conditional, long elapsed, long now, Date nextPoll, int graceMinutes) {
        eta.ready = readiness != null; eta.readinessAt = copy(readiness); eta.elapsedSeconds = elapsed;
        eta.sampleCount = all.size(); eta.conditionalSampleCount = conditional.size();
        eta.minimumSeconds = Collections.min(all); eta.maximumSeconds = Collections.max(all);
        eta.confidence = TimingStatistics.confidence(all.size(), TimingStatistics.median(all), eta.minimumSeconds, eta.maximumSeconds);
        eta.readinessDriver = drivers.isEmpty() ? "预计关键上游" : join(drivers, "、");
        if (readiness == null) return;
        eta.manualInterventionAt = new Date(readiness.getTime() + (eta.maximumSeconds + graceMinutes * 60L) * 1000L);
        eta.manualInterventionRequired = eta.manualInterventionAt.getTime() <= now;
        long[] milestones = {TimingStatistics.percentile(all, 50), TimingStatistics.percentile(all, 75), eta.maximumSeconds};
        for (long seconds : milestones) {
            Date candidate = new Date(readiness.getTime() + seconds * 1000L);
            if (candidate.getTime() > now) { eta.nextCheckpoint = candidate; break; }
        }
        if (eta.nextCheckpoint == null) { eta.historicalRangeExceeded = true; eta.nextCheckpoint = copy(nextPoll); }
        eta.checkpoint = true;
    }

    private static void collectCheckpoints(Models.DagEta result, Date nextPoll) {
        Date earliest = null;
        for (Models.TaskEta eta : result.taskEtas.values()) if (eta.checkpoint && !eta.completed) {
            result.checkpointTasks.add(eta.fabId);
            if (eta.nextCheckpoint != null && (earliest == null || eta.nextCheckpoint.before(earliest))) earliest = eta.nextCheckpoint;
            result.historicalRangeExceeded |= eta.historicalRangeExceeded;
            result.manualInterventionRequired |= eta.manualInterventionRequired;
        }
        result.nextCheckpoint = earliest == null ? copy(nextPoll) : earliest;
    }

    private static void copyRootStats(Models.DagEta result, Models.TaskEta eta) {
        result.elapsedSinceReadinessSeconds = eta.elapsedSeconds; result.historicalMinimumSeconds = eta.minimumSeconds;
        result.historicalMaximumSeconds = eta.maximumSeconds; result.conditionalSampleCount = eta.conditionalSampleCount;
        result.historicalRangeExceeded = eta.historicalRangeExceeded; result.manualInterventionAt = copy(eta.manualInterventionAt);
        result.manualInterventionRequired = eta.manualInterventionRequired;
    }

    private static String checkpointText(Models.DagEta value) {
        if (value.manualInterventionRequired) return "必须人工介入";
        if (value.historicalRangeExceeded) return "已超过历史范围，立即检查；下次刷新 " + UiFormat.dateTime(value.nextCheckpoint);
        return (value.checkpointTasks.isEmpty() ? "无已就绪未完成任务" : join(value.checkpointTasks, "、")) +
            "，下一个 " + UiFormat.dateTime(value.nextCheckpoint);
    }

    private static void completed(Estimate value, Models.TaskView task) {
        value.available = true; value.p50 = task.actTime.getTime(); value.p75 = value.p50; value.confidence = "实际 R";
        List<String> path = new ArrayList<String>(); path.add(task.fabId); value.paths.add(path);
    }
    private static void completed(Models.TaskEta value, Models.TaskView task) {
        value.available = true; value.completed = true; value.p50Completion = copy(task.actTime); value.p75Completion = copy(task.actTime); value.confidence = "实际 R";
    }
    private static Estimate memo(Map<String, Estimate> memo, String id, Estimate value) { memo.put(id, value); return value; }
    private static Estimate issue(Estimate value, String issue) { value.issues.add(issue); return value; }
    private static boolean validR(Models.TaskView task) { return task != null && "R".equalsIgnoreCase(task.status) && task.actTime != null && !task.actTimePlaceholder; }
    private static int level(Models.TaskKey task) { try { return Integer.parseInt(normalize(task.levelNo)); } catch (Exception e) { return Integer.MIN_VALUE; } }

    private static Map<String, List<String>> adjacency(List<Models.Dependency> edges) {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        for (Models.Dependency edge : edges) { String owner = normalize(edge.fabId), dep = normalize(edge.dependencyId); if (owner.isEmpty() || dep.isEmpty()) continue; List<String> values = result.get(owner); if (values == null) { values = new ArrayList<String>(); result.put(owner, values); } if (!values.contains(dep)) values.add(dep); }
        return result;
    }
    private static Index index(List<Models.TaskView> tasks) {
        Index result = new Index();
        for (Models.TaskView task : tasks) { String id = normalize(task.fabId); List<Models.TaskView> values = result.all.get(id); if (values == null) { values = new ArrayList<Models.TaskView>(); result.all.put(id, values); } values.add(task); }
        for (Map.Entry<String, List<Models.TaskView>> entry : result.all.entrySet()) if (entry.getValue().size() == 1) result.unique.put(entry.getKey(), entry.getValue().get(0));
        return result;
    }
    private static List<List<String>> copyPaths(List<List<String>> source) { List<List<String>> result = new ArrayList<List<String>>(); for (List<String> path : source) result.add(new ArrayList<String>(path)); return result; }
    private static void addPaths(List<List<String>> target, List<List<String>> source) { for (List<String> path : source) if (!target.contains(path)) target.add(new ArrayList<String>(path)); }
    private static List<String> stringifyPaths(List<List<String>> paths) { List<String> values = new ArrayList<String>(); for (List<String> path : paths) values.add(join(path, " → ")); return values; }
    private static String weaker(String a, String b) { if (a == null || a.isEmpty()) return b; if (b == null || b.isEmpty()) return a; return confidenceRank(a) <= confidenceRank(b) ? a : b; }
    private static int confidenceRank(String value) { if (value == null || value.contains("低") || value.contains("无")) return 1; if (value.contains("中")) return 2; return 3; }
    private static String join(List<String> values, String delimiter) { StringBuilder out = new StringBuilder(); for (String value : values) { if (out.length() > 0) out.append(delimiter); out.append(value); } return out.toString(); }
    private static Date copy(Date value) { return value == null ? null : new Date(value.getTime()); }
    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static final class Index { Map<String, List<Models.TaskView>> all = new LinkedHashMap<String, List<Models.TaskView>>(); Map<String, Models.TaskView> unique = new LinkedHashMap<String, Models.TaskView>(); }
}
