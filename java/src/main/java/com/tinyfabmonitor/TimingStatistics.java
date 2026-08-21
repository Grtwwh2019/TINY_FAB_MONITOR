package com.tinyfabmonitor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds robust timing evidence from locally persisted I/R records. */
final class TimingStatistics {
    private TimingStatistics() {}

    static void apply(List<Models.TaskView> currentTasks, Collection<Models.TrackedTask> tracked,
                      List<Models.RunRecord> runs, List<Models.Dependency> dependencies) {
        applyExecutionTypical(currentTasks, runs);
        Map<String, List<String>> upstream = upstream(dependencies);
        Map<String, Models.TaskView> currentByFab = latestCurrentByFab(currentTasks);
        applyCurrentReadiness(currentTasks, currentByFab, upstream);
        applyHistoricalReadyTypical(currentTasks, tracked, upstream, currentByFab);
    }

    static long median(List<Long> values) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("中位数样本不能为空");
        List<Long> sorted = new ArrayList<Long>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if ((sorted.size() & 1) == 1) return sorted.get(middle);
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2L;
    }

    static String confidence(int samples) {
        if (samples >= 5) return "较高置信度";
        if (samples >= 2) return "中等置信度";
        return samples == 1 ? "低置信度" : "无历史样本";
    }

    private static void applyExecutionTypical(List<Models.TaskView> tasks, List<Models.RunRecord> runs) {
        Map<String, List<Long>> values = new HashMap<String, List<Long>>();
        for (Models.RunRecord run : runs) {
            if (run == null || run.task == null || run.startedAt == null || run.completedAt == null || run.durationSeconds < 0) continue;
            String groupId = group(run.task);
            List<Long> samples = values.get(groupId);
            if (samples == null) { samples = new ArrayList<Long>(); values.put(groupId, samples); }
            samples.add(run.durationSeconds);
        }
        for (Models.TaskView task : tasks) {
            List<Long> samples = values.get(group(task));
            if (samples == null || samples.isEmpty()) continue;
            task.executionTypicalSeconds = median(samples);
            task.executionTypicalSampleCount = samples.size();
        }
    }

    private static void applyCurrentReadiness(List<Models.TaskView> tasks, Map<String, Models.TaskView> byFab,
                                              Map<String, List<String>> upstream) {
        for (Models.TaskView task : tasks) {
            if (isLevel20(task)) continue;
            List<String> dependencyIds = upstream.get(normalize(task.fabId));
            if (dependencyIds == null || dependencyIds.isEmpty()) continue;
            Date latest = null;
            int eligible = 0;
            boolean incomplete = false;
            for (String dependencyId : dependencyIds) {
                Models.TaskView dependency = byFab.get(dependencyId);
                if (dependency != null && isLevel20(dependency)) {
                    task.hasLevel20Upstream = true; task.readinessPartial = true; continue;
                }
                eligible++;
                if (!validR(dependency)) { incomplete = true; continue; }
                if (latest == null || dependency.actTime.after(latest)) latest = dependency.actTime;
            }
            if (eligible > 0 && !incomplete && latest != null) {
                task.readinessAt = copy(latest);
                if (validR(task) && !latest.after(task.actTime)) {
                    task.readyToCompleteSeconds = (task.actTime.getTime() - latest.getTime()) / 1000L;
                }
            }
        }
    }

    private static void applyHistoricalReadyTypical(List<Models.TaskView> tasks,
                                                     Collection<Models.TrackedTask> tracked,
                                                     Map<String, List<String>> upstream,
                                                     Map<String, Models.TaskView> currentByFab) {
        Map<String, HistoricalDay> days = historicalDays(tracked);
        for (Models.TaskView task : tasks) {
            if (isLevel20(task)) continue;
            List<String> dependencyIds = upstream.get(normalize(task.fabId));
            if (dependencyIds == null || dependencyIds.isEmpty()) continue;
            List<Long> samples = new ArrayList<Long>();
            boolean partial = false;
            for (HistoricalDay day : days.values()) {
                if (task.processDate != null && task.processDate.equals(day.date)) continue;
                Models.TrackedTask completed = day.byGroup.get(group(task));
                if (!validR(completed)) continue;
                Date latest = null;
                int eligible = 0;
                boolean incomplete = false;
                boolean samplePartial = false;
                for (String dependencyId : dependencyIds) {
                    Models.TrackedTask dependency = day.byFab.get(dependencyId);
                    Models.TaskView currentDependency = currentByFab.get(dependencyId);
                    if ((dependency != null && isLevel20(dependency.key)) ||
                        (dependency == null && currentDependency != null && isLevel20(currentDependency))) {
                        samplePartial = true; continue;
                    }
                    eligible++;
                    if (!validR(dependency)) { incomplete = true; continue; }
                    if (latest == null || dependency.lastActTime.after(latest)) latest = dependency.lastActTime;
                }
                if (eligible == 0 || incomplete || latest == null || latest.after(completed.lastActTime)) continue;
                samples.add((completed.lastActTime.getTime() - latest.getTime()) / 1000L);
                partial |= samplePartial;
            }
            if (!samples.isEmpty()) {
                task.readyToCompleteTypicalSeconds = median(samples);
                task.readyToCompleteSampleCount = samples.size();
                task.readinessPartial |= partial;
            }
        }
    }

    private static Map<String, HistoricalDay> historicalDays(Collection<Models.TrackedTask> tracked) {
        Map<String, HistoricalDay> result = new LinkedHashMap<String, HistoricalDay>();
        for (Models.TrackedTask task : tracked) {
            if (task == null || task.key == null || task.key.processDate == null) continue;
            HistoricalDay day = result.get(task.key.processDate);
            if (day == null) { day = new HistoricalDay(); day.date = task.key.processDate; result.put(day.date, day); }
            day.byGroup.put(group(task.key), task);
            if (validR(task)) {
                String fab = normalize(task.key.fabId);
                Models.TrackedTask previous = day.byFab.get(fab);
                if (previous == null || previous.lastActTime.before(task.lastActTime)) day.byFab.put(fab, task);
            }
        }
        return result;
    }

    private static Map<String, List<String>> upstream(List<Models.Dependency> dependencies) {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        for (Models.Dependency edge : dependencies) {
            String owner = normalize(edge.fabId), dependency = normalize(edge.dependencyId);
            if (owner.isEmpty() || dependency.isEmpty()) continue;
            List<String> values = result.get(owner);
            if (values == null) { values = new ArrayList<String>(); result.put(owner, values); }
            if (!values.contains(dependency)) values.add(dependency);
        }
        return result;
    }

    private static Map<String, Models.TaskView> latestCurrentByFab(List<Models.TaskView> tasks) {
        Map<String, Models.TaskView> result = new LinkedHashMap<String, Models.TaskView>();
        for (Models.TaskView task : tasks) {
            String key = normalize(task.fabId);
            Models.TaskView previous = result.get(key);
            if (previous == null || previous.actTime == null || (task.actTime != null && task.actTime.after(previous.actTime))) result.put(key, task);
        }
        return result;
    }

    private static boolean validR(Models.TaskView task) {
        return task != null && "R".equalsIgnoreCase(task.status) && task.actTime != null && !task.actTimePlaceholder;
    }

    private static boolean validR(Models.TrackedTask task) {
        return task != null && "R".equalsIgnoreCase(task.lastStatus) && task.lastActTime != null;
    }

    private static boolean isLevel20(Models.TaskKey task) { return task != null && "20".equals(normalize(task.levelNo)); }
    private static Date copy(Date value) { return value == null ? null : new Date(value.getTime()); }
    private static String group(Models.TaskKey task) { return normalize(task.threadId) + "|" + normalize(task.levelNo) + "|" + normalize(task.fabId); }
    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }

    private static final class HistoricalDay {
        String date;
        Map<String, Models.TrackedTask> byGroup = new HashMap<String, Models.TrackedTask>();
        Map<String, Models.TrackedTask> byFab = new HashMap<String, Models.TrackedTask>();
    }
}
