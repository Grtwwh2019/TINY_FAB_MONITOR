package com.tinyfabmonitor;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds pure-R timing evidence locally; it never reads Oracle itself. */
final class TimingStatistics {
    private TimingStatistics() {}

    static void apply(List<Models.TaskView> current, Collection<Models.TrackedTask> tracked,
                      List<Models.RunRecord> runs, List<Models.Dependency> dependencies) {
        apply(current, tracked, runs, dependencies, Collections.<List<Models.OracleTask>>emptyList());
    }

    static void apply(List<Models.TaskView> current, Collection<Models.TrackedTask> tracked,
                      List<Models.RunRecord> runs, List<Models.Dependency> dependencies,
                      Collection<List<Models.OracleTask>> cachedDays) {
        applyExecutionTypical(current, runs); // monitoring/history only; ETA does not consume this value.
        Map<String, List<String>> upstream = upstream(dependencies);
        applyCurrentReadiness(current, currentIndex(current), upstream);
        applyHistoricalStatistics(current, historicalDays(tracked, cachedDays), upstream);
    }

    static long median(List<Long> values) { return percentile(values, 50); }

    static long percentile(List<Long> values, int percentile) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("分位数样本不能为空");
        List<Long> sorted = new ArrayList<Long>(values); Collections.sort(sorted);
        if (percentile == 50 && (sorted.size() & 1) == 0)
            return (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2L;
        int index = Math.max(0, Math.min(sorted.size() - 1,
            (int) Math.ceil(percentile / 100.0d * sorted.size()) - 1));
        return sorted.get(index);
    }

    static String confidence(int samples) {
        if (samples >= 5) return "较高置信度";
        if (samples >= 2) return "中等置信度";
        return samples == 1 ? "低置信度" : "无历史样本";
    }

    static String confidence(int samples, long p50, long minimum, long maximum) {
        String value = confidence(samples);
        if (samples > 1 && volatileRange(p50, minimum, maximum))
            return samples >= 5 ? "中等置信度（波动较大）" : "低置信度（波动较大）";
        return value;
    }

    static boolean volatileRange(long p50, long minimum, long maximum) {
        return maximum - minimum > Math.max(1800L, Math.max(1L, p50) * 2L);
    }

    private static void applyExecutionTypical(List<Models.TaskView> tasks, List<Models.RunRecord> runs) {
        Map<String, List<Long>> values = new HashMap<String, List<Long>>();
        for (Models.RunRecord run : runs) {
            if (run == null || run.task == null || run.startedAt == null || run.completedAt == null || run.durationSeconds < 0) continue;
            List<Long> samples = values.get(group(run.task));
            if (samples == null) { samples = new ArrayList<Long>(); values.put(group(run.task), samples); }
            samples.add(run.durationSeconds);
        }
        for (Models.TaskView task : tasks) {
            List<Long> samples = values.get(group(task));
            if (samples == null || samples.isEmpty()) continue;
            task.executionTypicalSeconds = median(samples); task.executionTypicalSampleCount = samples.size();
        }
    }

    private static void applyCurrentReadiness(List<Models.TaskView> tasks, CurrentIndex index,
                                              Map<String, List<String>> upstream) {
        for (Models.TaskView task : tasks) {
            if (level(task) <= 40) continue;
            List<String> dependencyIds = upstream.get(normalize(task.fabId));
            if (dependencyIds == null || dependencyIds.isEmpty()) continue;
            Date latest = null; task.readinessDrivers.clear();
            for (String dependencyId : dependencyIds) {
                List<Models.TaskView> matches = index.byFabAll.get(dependencyId);
                if (matches == null || matches.isEmpty()) { task.readinessIssues.add(dependencyId + "（当前业务日期无任务）"); continue; }
                if (matches.size() != 1) {
                    task.dependencyMappingAmbiguous = true;
                    task.readinessIssues.add(dependencyId + "（匹配到 " + matches.size() + " 个任务）"); continue;
                }
                Models.TaskView dependency = matches.get(0);
                if (level(dependency) < 40) { task.readinessIssues.add(dependencyId + "（Level 小于 40）"); continue; }
                if (!validR(dependency)) { task.readinessIssues.add(dependencyId + "（尚无有效 R）"); continue; }
                if (latest == null || dependency.actTime.after(latest)) {
                    latest = dependency.actTime; task.readinessDrivers.clear(); task.readinessDrivers.add(dependency.fabId);
                } else if (dependency.actTime.equals(latest)) task.readinessDrivers.add(dependency.fabId);
            }
            if (task.readinessIssues.isEmpty() && latest != null) {
                task.readinessAt = copy(latest);
                if (validR(task) && !latest.after(task.actTime)) task.readyToCompleteSeconds = (task.actTime.getTime() - latest.getTime()) / 1000L;
            }
        }
    }

    private static void applyHistoricalStatistics(List<Models.TaskView> tasks, Map<String, HistoricalDay> days,
                                                  Map<String, List<String>> upstream) {
        for (Models.TaskView task : tasks) {
            if (level(task) <= 40) continue;
            List<String> dependencyIds = upstream.get(normalize(task.fabId));
            if (dependencyIds == null || dependencyIds.isEmpty()) continue;
            List<Long> samples = new ArrayList<Long>();
            for (HistoricalDay day : days.values()) {
                if (day.date.compareTo(task.processDate) >= 0) continue;
                Models.OracleTask completed = unique(day.byGroupAll, group(task));
                if (!validR(completed)) continue;
                Date latest = null; boolean valid = true;
                for (String dependencyId : dependencyIds) {
                    Models.OracleTask dependency = unique(day.byFabAll, dependencyId);
                    if (!validR(dependency) || level(dependency) < 40) { valid = false; break; }
                    if (latest == null || dependency.actTime.after(latest)) latest = dependency.actTime;
                }
                if (valid && latest != null && !latest.after(completed.actTime))
                    samples.add((completed.actTime.getTime() - latest.getTime()) / 1000L);
            }
            if (samples.isEmpty()) continue;
            Collections.sort(samples); task.readyToCompleteSamples = samples; task.readyToCompleteSampleCount = samples.size();
            task.readyToCompleteTypicalSeconds = percentile(samples, 50); task.readyToCompleteP75Seconds = percentile(samples, 75);
            task.readyToCompleteMinimumSeconds = samples.get(0); task.readyToCompleteMaximumSeconds = samples.get(samples.size() - 1);
            task.readyToCompleteConfidence = confidence(samples.size(), task.readyToCompleteTypicalSeconds,
                task.readyToCompleteMinimumSeconds, task.readyToCompleteMaximumSeconds);
            task.readyToCompleteVolatility = volatileRange(task.readyToCompleteTypicalSeconds,
                task.readyToCompleteMinimumSeconds, task.readyToCompleteMaximumSeconds) ? "波动较大" : "波动正常";
        }
    }

    private static Map<String, HistoricalDay> historicalDays(Collection<Models.TrackedTask> tracked,
                                                               Collection<List<Models.OracleTask>> cachedDays) {
        Map<String, HistoricalDay> result = new LinkedHashMap<String, HistoricalDay>();
        for (Models.TrackedTask value : tracked) {
            if (value == null || value.key == null || blank(value.key.processDate)) continue;
            Models.OracleTask task = new Models.OracleTask(); task.processDate = value.key.processDate;
            task.threadId = value.key.threadId; task.levelNo = value.key.levelNo; task.fabId = value.key.fabId;
            task.status = value.lastStatus; task.actTime = copy(value.lastActTime); task.actTimePlaceholder = placeholder(task.actTime);
            add(result, task, false);
        }
        for (List<Models.OracleTask> day : cachedDays) if (day != null)
            for (Models.OracleTask task : day) if (task != null && !blank(task.processDate)) add(result, task, true);
        for (HistoricalDay day : result.values()) day.rebuild();
        return result;
    }

    private static void add(Map<String, HistoricalDay> days, Models.OracleTask task, boolean replace) {
        HistoricalDay day = days.get(task.processDate);
        if (day == null) { day = new HistoricalDay(); day.date = task.processDate; days.put(day.date, day); }
        if (replace || !day.byFull.containsKey(task.fullId())) day.byFull.put(task.fullId(), copyTask(task));
    }

    private static Models.OracleTask unique(Map<String, List<Models.OracleTask>> map, String key) {
        List<Models.OracleTask> values = map.get(normalize(key));
        return values != null && values.size() == 1 ? values.get(0) : null;
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

    private static CurrentIndex currentIndex(List<Models.TaskView> tasks) {
        CurrentIndex result = new CurrentIndex();
        for (Models.TaskView task : tasks) add(result.byFabAll, normalize(task.fabId), task);
        return result;
    }

    private static boolean validR(Models.TaskView task) { return task != null && "R".equalsIgnoreCase(task.status) && task.actTime != null && !task.actTimePlaceholder && !placeholder(task.actTime); }
    private static boolean validR(Models.OracleTask task) { return task != null && "R".equalsIgnoreCase(task.status) && task.actTime != null && !task.actTimePlaceholder && !placeholder(task.actTime); }
    private static boolean placeholder(Date value) { if (value == null) return false; Calendar c = Calendar.getInstance(); c.setTime(value); return c.get(Calendar.YEAR) <= 1; }
    private static int level(Models.TaskKey task) { try { return Integer.parseInt(normalize(task.levelNo)); } catch (Exception e) { return Integer.MIN_VALUE; } }
    private static Models.OracleTask copyTask(Models.OracleTask source) {
        Models.OracleTask task = new Models.OracleTask(); task.processDate = source.processDate; task.threadId = source.threadId;
        task.levelNo = source.levelNo; task.fabId = source.fabId; task.status = source.status; task.actTime = copy(source.actTime);
        task.actTimePlaceholder = source.actTimePlaceholder; task.levelDescription = source.levelDescription; task.fabDescription = source.fabDescription; return task;
    }
    private static Date copy(Date value) { return value == null ? null : new Date(value.getTime()); }
    private static String group(Models.TaskKey task) { return normalize(task.threadId) + "|" + normalize(task.levelNo) + "|" + normalize(task.fabId); }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static <T> void add(Map<String, List<T>> map, String key, T value) { List<T> values = map.get(key); if (values == null) { values = new ArrayList<T>(); map.put(key, values); } values.add(value); }

    private static final class CurrentIndex { Map<String, List<Models.TaskView>> byFabAll = new LinkedHashMap<String, List<Models.TaskView>>(); }
    private static final class HistoricalDay {
        String date; Map<String, Models.OracleTask> byFull = new LinkedHashMap<String, Models.OracleTask>();
        Map<String, List<Models.OracleTask>> byGroupAll = new HashMap<String, List<Models.OracleTask>>();
        Map<String, List<Models.OracleTask>> byFabAll = new HashMap<String, List<Models.OracleTask>>();
        void rebuild() { byGroupAll.clear(); byFabAll.clear(); for (Models.OracleTask task : byFull.values()) { add(byGroupAll, group(task), task); add(byFabAll, normalize(task.fabId), task); } }
    }
}
