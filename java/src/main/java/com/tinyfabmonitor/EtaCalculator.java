package com.tinyfabmonitor;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class EtaCalculator {
    private static class Estimate {
        boolean available;
        boolean lowerBound;
        boolean level20Cutoff;
        long finishMillis;
        int sampleCount;
        String reason = "";
        List<String> path = new ArrayList<String>();
    }

    private EtaCalculator() {}

    static Models.DagEta calculate(String rootFabId, List<Models.TaskView> tasks,
                                   List<Models.Dependency> upstreamEdges, Date now) {
        Models.DagEta result = new Models.DagEta();
        Map<String, Models.TaskView> tasksByFab = latestTasksByFab(tasks);
        Models.TaskView root = tasksByFab.get(normalize(rootFabId));
        if (root == null) { result.summary = "预计完成：找不到中心 FAB 状态"; return result; }

        if ("R".equalsIgnoreCase(root.status)) {
            result.completed = true;
            if (validActTime(root)) {
                result.summary = "已完成：" + UiFormat.dateTime(root.actTime);
                result.detail = "中心 FAB 已进入 R，显示数据库实际完成时间。";
            } else {
                result.summary = "已完成，但数据库未提供有效完成时间";
                result.detail = "中心 FAB 状态为 R，act_tm 为占位值；不使用占位时间生成 ETA。";
            }
            return result;
        }
        if ("E".equalsIgnoreCase(root.status) || "B".equalsIgnoreCase(root.status)) {
            result.summary = "预计完成：中心 FAB 当前为 " + root.status + "，等待重新进入 I";
            result.detail = "异常或阻塞状态无法给出可靠预计完成时间。";
            return result;
        }

        Map<String, List<String>> dependencies = adjacency(upstreamEdges);
        Estimate estimate = estimate(root.fabId, true, tasksByFab, dependencies,
            new HashMap<String, Estimate>(), new LinkedHashSet<String>(), now.getTime());
        if (!estimate.available) {
            result.summary = "预计完成：无法可靠估算";
            result.detail = estimate.reason;
            return result;
        }

        result.available = true;
        result.lowerBound = estimate.lowerBound;
        result.estimatedCompletion = new Date(estimate.finishMillis);
        result.remainingSeconds = (estimate.finishMillis - now.getTime()) / 1000L;
        result.overdue = result.remainingSeconds < 0;
        result.criticalPath = estimate.path;
        result.sampleCount = estimate.sampleCount;
        result.confidence = TimingStatistics.confidence(estimate.sampleCount);
        if (estimate.lowerBound) {
            result.summary = "已知路径参考下限：" + UiFormat.dateTime(result.estimatedCompletion);
        } else {
            result.summary = "预计完成：" + UiFormat.dateTime(result.estimatedCompletion) +
                (result.overdue ? "（可能已超时 " + UiFormat.duration(Math.abs(result.remainingSeconds)) + "）"
                    : "（预计剩余 " + UiFormat.duration(result.remainingSeconds) + "）");
        }
        result.detail = "关键路径：" + joinPath(estimate.path) + "；依据：" + estimate.reason +
            "；历史样本：" + estimate.sampleCount + "；" + result.confidence +
            (estimate.lowerBound ? "；Level 20 路径时间未知，本结果不是确定 ETA。" : "");
        return result;
    }

    private static Estimate estimate(String fabId, boolean root, Map<String, Models.TaskView> tasksByFab,
                                     Map<String, List<String>> dependencies, Map<String, Estimate> memo,
                                     Set<String> visiting, long nowMillis) {
        String key = normalize(fabId);
        Estimate cached = memo.get(key);
        if (cached != null) return cached;
        Estimate result = new Estimate();
        Models.TaskView task = tasksByFab.get(key);
        if (task == null) return unavailable(result, "依赖 FAB " + fabId + " 不属于当前业务日期");
        if (!root && isLevel20(task)) {
            result.level20Cutoff = true;
            result.reason = "路径在 Level 20 FAB " + task.fabId + " 截止";
            return result;
        }
        if (!visiting.add(key)) return unavailable(result, "发现循环依赖，涉及 FAB " + task.fabId);
        try {
            String status = normalize(task.status);
            if ("R".equals(status) && validActTime(task)) {
                result.available = true; result.finishMillis = task.actTime.getTime(); result.path.add(task.fabId);
                result.reason = "数据库真实 R";
            } else if ("I".equals(status) && validIStart(task)) {
                if (task.executionTypicalSampleCount < 1) {
                    return unavailable(result, "FAB " + task.fabId + " 已进入 I，但没有完整 I→R 历史记录");
                }
                Date start = task.startedAt != null ? task.startedAt : task.actTime;
                result.available = true;
                result.finishMillis = start.getTime() + task.executionTypicalSeconds * 1000L;
                result.sampleCount = task.executionTypicalSampleCount;
                result.path.add(task.fabId);
                result.reason = "真实 I + 历史 I→R 典型值";
            } else if ("E".equals(status) || "B".equals(status)) {
                return unavailable(result, "路径被 FAB " + task.fabId + " 的状态 " + status + " 阻塞");
            } else if ("W".equals(status) || "R".equals(status) || "I".equals(status)) {
                if (root && isLevel20(task)) {
                    return unavailable(result, "中心 Level 20 FAB " + task.fabId + " 缺少有效真实 I，不能使用循环 Poll 的 R 推算");
                }
                if (task.readyToCompleteSampleCount < 1) {
                    return unavailable(result, "FAB " + task.fabId + " 没有可靠的历史“依赖就绪→R”样本");
                }
                List<String> upstream = dependencies.get(key);
                if (upstream == null || upstream.isEmpty()) {
                    return unavailable(result, "FAB " + task.fabId + " 向上找不到有效依赖完成时间起点");
                }
                Estimate latest = null;
                boolean cutoff = task.hasLevel20Upstream || task.readinessPartial;
                for (String dependency : upstream) {
                    Estimate candidate = estimate(dependency, false, tasksByFab, dependencies, memo, visiting, nowMillis);
                    if (candidate.level20Cutoff) { cutoff = true; continue; }
                    if (!candidate.available) return unavailable(result, candidate.reason);
                    if (latest == null || candidate.finishMillis > latest.finishMillis) latest = candidate;
                }
                if (latest == null && !cutoff) return unavailable(result, "FAB " + task.fabId + " 没有可用的上游完成时间");
                long readiness = latest == null ? nowMillis : latest.finishMillis;
                result.available = true;
                result.lowerBound = cutoff || (latest != null && latest.lowerBound);
                result.finishMillis = readiness + task.readyToCompleteTypicalSeconds * 1000L;
                result.sampleCount = combineSamples(latest == null ? 0 : latest.sampleCount, task.readyToCompleteSampleCount);
                if (latest != null) result.path.addAll(latest.path);
                result.path.add(task.fabId);
                result.reason = "历史“依赖就绪→R”典型值";
            } else {
                return unavailable(result, "FAB " + task.fabId + " 的状态 " + status + " 不支持 ETA 计算");
            }
        } finally { visiting.remove(key); }
        memo.put(key, result);
        return result;
    }

    private static int combineSamples(int upstream, int current) {
        if (upstream < 1) return current;
        if (current < 1) return upstream;
        return Math.min(upstream, current);
    }

    private static Estimate unavailable(Estimate value, String reason) { value.reason = reason; return value; }
    private static boolean validActTime(Models.TaskView task) { return task.actTime != null && !task.actTimePlaceholder; }
    private static boolean validIStart(Models.TaskView task) {
        return task.startedAt != null || ("I".equalsIgnoreCase(task.status) && validActTime(task));
    }
    private static boolean isLevel20(Models.TaskKey task) { return task != null && "20".equals(normalize(task.levelNo)); }

    private static Map<String, List<String>> adjacency(List<Models.Dependency> edges) {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        for (Models.Dependency edge : edges) {
            String owner = normalize(edge.fabId), dependency = normalize(edge.dependencyId);
            if (owner.isEmpty() || dependency.isEmpty()) continue;
            List<String> values = result.get(owner);
            if (values == null) { values = new ArrayList<String>(); result.put(owner, values); }
            if (!values.contains(dependency)) values.add(dependency);
        }
        return result;
    }

    private static Map<String, Models.TaskView> latestTasksByFab(List<Models.TaskView> tasks) {
        Map<String, Models.TaskView> result = new LinkedHashMap<String, Models.TaskView>();
        for (Models.TaskView task : tasks) {
            if (task == null || task.fabId == null || task.fabId.trim().isEmpty()) continue;
            String key = normalize(task.fabId);
            Models.TaskView previous = result.get(key);
            if (previous == null || previous.actTime == null || (task.actTime != null && task.actTime.after(previous.actTime))) result.put(key, task);
        }
        return result;
    }

    private static String joinPath(List<String> path) {
        StringBuilder value = new StringBuilder();
        for (String id : path) { if (value.length() > 0) value.append(" → "); value.append(id); }
        return value.length() == 0 ? "仅有 Level 20 截止路径" : value.toString();
    }

    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
}
