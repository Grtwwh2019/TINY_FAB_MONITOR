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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

final class PerformanceAnalyzer {
    private static class TaskSnapshot {
        Models.OracleTask task;
        Date completedAt;
        Date readinessAt;
        Long readyToCompleteSeconds;
        boolean readinessPartial;
        long completionOffsetSeconds;
        long businessCompletionOffsetSeconds;
        Long readinessOffsetSeconds;
        List<TaskSnapshot> readinessDrivers = new ArrayList<TaskSnapshot>();
        List<String> incompleteDependencies = new ArrayList<String>();
        List<String> ambiguousDependencies = new ArrayList<String>();
    }

    private static class DaySnapshot {
        String date;
        Map<String, TaskSnapshot> byGroup = new LinkedHashMap<String, TaskSnapshot>();
        Map<String, TaskSnapshot> byFab = new LinkedHashMap<String, TaskSnapshot>();
        Map<String, List<TaskSnapshot>> byFabAll = new LinkedHashMap<String, List<TaskSnapshot>>();
        TaskSnapshot startTask;
        TaskSnapshot endTask;
        Date start;
        Date finish;
        long durationSeconds;
        long businessCompletionOffsetSeconds;
        boolean complete;
        String startBasis = "";
    }

    private static class Averages {
        Long readyToComplete;
        Long completionOffset;
        Long businessCompletionOffset;
        Date singleCompletedAt;
        Date singleReadinessAt;
        boolean readinessPartial;
        int completionCount;
        Long readinessOffset;
        String readinessDependency = "--";
        boolean dependencyMappingAmbiguous;
        List<String> dependencyIssues = new ArrayList<String>();
    }

    private PerformanceAnalyzer() {}

    static String baselineIssue(Models.AnalysisRequest request, String date, List<Models.OracleTask> tasks,
                                List<Models.RunRecord> runs) {
        DaySnapshot day = snapshot(request, date, tasks, runs, Collections.<Models.Dependency>emptyList(), new Date());
        return baselineRUnusableReason(day);
    }

    static String targetIssue(Models.AnalysisRequest request, String date, List<Models.OracleTask> tasks,
                              List<Models.RunRecord> runs) {
        DaySnapshot day = snapshot(request, date, tasks, runs, Collections.<Models.Dependency>emptyList(), new Date());
        if (day.startTask == null) return "分析日期找不到启动作业";
        if (day.endTask == null) return "分析日期找不到结束作业";
        if (groupKey(day.startTask.task).equals(groupKey(day.endTask.task))) return "启动作业和结束作业不能相同";
        if (!isLevel40(day.startTask.task)) return "批次启动作业必须是 Level 40";
        if (level(day.endTask.task) < 40) return "批次结束作业 Level 不能小于 40";
        if (day.startTask.completedAt == null) {
            String cause = day.startTask.task.actTimePlaceholder ? "R 时间是占位值" : "尚未进入 R 或没有有效 R 时间";
            return "分析日期的启动作业缺少真实 R 时间（" + cause + "），请切换日期或启动作业";
        }
        if ("R".equalsIgnoreCase(day.endTask.task.status) && day.finish == null) {
            return day.endTask.task.actTimePlaceholder ? "分析日期的结束作业 R 时间是占位值" : "分析日期的结束作业没有有效 R 时间";
        }
        if (day.finish != null && day.finish.before(day.startTask.completedAt)) return "分析日期的结束作业 R 时间早于启动作业 R 时间";
        return null;
    }

    static Models.AnalysisResult analyze(Models.AnalysisRequest request,
                                         Map<String, List<Models.OracleTask>> tasksByDate,
                                         List<Models.RunRecord> runs,
                                         List<Models.Dependency> allDependencies,
                                         List<String> baselineCandidates,
                                         Date now) {
        return analyze(request, tasksByDate, runs, allDependencies, baselineCandidates, now, null);
    }

    static Models.AnalysisResult analyze(Models.AnalysisRequest request,
                                         Map<String, List<Models.OracleTask>> tasksByDate,
                                         List<Models.RunRecord> runs,
                                         List<Models.Dependency> allDependencies,
                                         List<String> baselineCandidates,
                                         Date now,
                                         Models.DagEta targetEta) {
        Models.AnalysisResult result = new Models.AnalysisResult();
        if (targetEta != null) result.eta = targetEta;
        result.analysisDate = request.analysisDate;
        result.startTaskLabel = taskLabel(request.startThreadId, request.startLevelNo, request.startFabId);
        result.endTaskLabel = taskLabel(request.endThreadId, request.endLevelNo, request.endFabId);
        result.attentionThresholdSeconds = request.attentionThresholdSeconds;

        List<Models.OracleTask> targetTasks = tasksByDate.get(request.analysisDate);
        if (targetTasks == null || targetTasks.isEmpty()) throw new IllegalArgumentException("分析日期没有任务数据");
        List<Models.Dependency> dependencies = dependenciesForTasks(allDependencies, targetTasks);
        result.dependencies.addAll(dependencies);
        DaySnapshot target = snapshot(request, request.analysisDate, targetTasks, runs, dependencies, now);
        requireTargetBoundaryTasks(target);

        int requiredBaselines = request.baselineMode == Models.AnalysisBaselineMode.RECENT_AVERAGE ? request.recentDateCount : 1;
        List<DaySnapshot> baselines = new ArrayList<DaySnapshot>();
        List<String> rejected = new ArrayList<String>();
        for (String date : baselineCandidates) {
            DaySnapshot day = snapshot(request, date, tasksByDate.get(date), runs, dependencies, now);
            String unusable = baselineRUnusableReason(day);
            if (unusable == null) {
                baselines.add(day);
                result.baselineDates.add(date);
                if (baselines.size() >= requiredBaselines) break;
            } else if (rejected.size() < 3) rejected.add(date + "（" + unusable + "）");
        }
        if (baselines.size() < requiredBaselines) {
            String detail = rejected.isEmpty() ? "没有读到候选日期任务" : "候选：" + join(rejected, "；");
            throw new IllegalArgumentException("可用基准日期不足，需要 " + requiredBaselines + " 个，实际 " + baselines.size() + " 个；" + detail);
        }
        result.baselineLabel = result.baselineDates.size() == 1 ? result.baselineDates.get(0) :
            "最近 " + result.baselineDates.size() + " 个结束任务已完成日期平均";

        requireRAnchor(target, true);
        for (DaySnapshot day : baselines) requireRAnchor(day, false);
        applyRAnchor(target, true);
        for (DaySnapshot day : baselines) applyRAnchor(day, false);

        result.anchorMode = "R";
        result.startBasis = "纯R分析：整体只比较结束作业R时刻；启动作业R只用于批次内部对齐";
        result.targetComplete = target.complete;
        result.targetEstimatedStart = false;
        result.targetStart = copy(target.start);
        result.targetFinish = copy(target.finish);
        result.baselineFinish = baselines.size() == 1 ? copy(baselines.get(0).finish) : null;
        result.targetDurationSeconds = target.complete ? target.durationSeconds : 0L;
        result.baselineDurationSeconds = averageDayDuration(baselines);
        result.baselineBusinessCompletionOffsetSeconds = averageBusinessCompletionOffset(baselines);
        result.expectedFinish = new Date(target.start.getTime() + result.baselineDurationSeconds * 1000L);

        Set<String> critical = criticalPath(target, dependencies, request.startFabId, request.endFabId);
        result.criticalPath.addAll(critical);
        result.dependencyPathComplete = dependencyPathExists(target, dependencies, request.startFabId, request.endFabId);
        Map<String, Models.AnalysisTaskMetric> metricsByFab = new LinkedHashMap<String, Models.AnalysisTaskMetric>();
        List<Models.AnalysisTaskMetric> metrics = new ArrayList<Models.AnalysisTaskMetric>();
        for (TaskSnapshot task : target.byGroup.values()) {
            Models.AnalysisTaskMetric metric = metric(task, baselineAverage(task.task, baselines),
                request.baselineMode == Models.AnalysisBaselineMode.RECENT_AVERAGE);
            metric.criticalPath = target.byFab.containsKey(normalize(task.task.fabId)) &&
                critical.contains(normalize(task.task.fabId));
            if (target.byFab.containsKey(normalize(metric.fabId))) metricsByFab.put(normalize(metric.fabId), metric);
            metrics.add(metric);
        }
        enrichDependencyAttribution(metrics, metricsByFab, target, dependencies, request.startFabId, result);
        for (Models.AnalysisTaskMetric metric : metrics) classify(metric, request.attentionThresholdSeconds);
        Collections.sort(metrics, new Comparator<Models.AnalysisTaskMetric>() {
            public int compare(Models.AnalysisTaskMetric left, Models.AnalysisTaskMetric right) {
                long rightDelay = right.completionDelaySeconds == null ? Long.MIN_VALUE : right.completionDelaySeconds;
                long leftDelay = left.completionDelaySeconds == null ? Long.MIN_VALUE : left.completionDelaySeconds;
                return Long.compare(rightDelay, leftDelay);
            }
        });
        result.allRows.addAll(metrics);
        for (Models.AnalysisTaskMetric metric : metrics) if (includeMetric(metric, request)) result.rows.add(metric);

        result.predictedFinish = null;
        result.predictedDelay = false;
        Date comparedFinish = target.finish;
        if (comparedFinish != null) {
            result.targetBusinessCompletionOffsetSeconds = businessCompletionOffsetSeconds(request.analysisDate, comparedFinish);
            result.completionDelaySeconds = result.targetBusinessCompletionOffsetSeconds - result.baselineBusinessCompletionOffsetSeconds;
            result.overallDeltaSeconds = result.completionDelaySeconds;
        }
        for (Models.AnalysisTaskMetric metric : result.rows) {
            if ("可比较".equals(metric.dataQuality) || metric.dataQuality.startsWith("可比较（")) result.completionOnlyCount++;
            else result.insufficientCount++;
        }
        buildSummary(result, target);
        return result;
    }

    private static DaySnapshot snapshot(Models.AnalysisRequest request, String date, List<Models.OracleTask> tasks,
                                        List<Models.RunRecord> runs, List<Models.Dependency> dependencies, Date now) {
        DaySnapshot day = new DaySnapshot(); day.date = date;
        if (tasks == null) return day;
        for (Models.OracleTask task : latest(tasks)) {
            TaskSnapshot value = new TaskSnapshot(); value.task = task;
            boolean completedInDatabase = "R".equalsIgnoreCase(task.status) && task.actTime != null && !task.actTimePlaceholder;
            if (completedInDatabase) {
                value.completedAt = copy(task.actTime);
                value.businessCompletionOffsetSeconds = businessCompletionOffsetSeconds(date, task.actTime);
            }
            day.byGroup.put(groupKey(task), value);
            String fab = normalize(task.fabId);
            List<TaskSnapshot> sameFab = day.byFabAll.get(fab);
            if (sameFab == null) { sameFab = new ArrayList<TaskSnapshot>(); day.byFabAll.put(fab, sameFab); }
            sameFab.add(value);
        }
        for (Map.Entry<String, List<TaskSnapshot>> entry : day.byFabAll.entrySet())
            if (entry.getValue().size() == 1) day.byFab.put(entry.getKey(), entry.getValue().get(0));
        for (TaskSnapshot task : day.byGroup.values()) resolveReadiness(task, day, dependencies);

        day.startTask = findTask(day, request.startThreadId, request.startLevelNo, request.startFabId);
        day.endTask = findTask(day, request.endThreadId, request.endLevelNo, request.endFabId);
        if (day.endTask != null && "R".equalsIgnoreCase(day.endTask.task.status)) day.finish = copy(day.endTask.completedAt);
        day.complete = day.finish != null;
        return day;
    }

    private static void applyRAnchor(DaySnapshot day, boolean target) {
        day.start = copy(day.startTask.completedAt);
        day.startBasis = "启动作业 R 时间";
        if (day.start == null) throw new IllegalArgumentException(dayLabel(day, target) + "没有可用的启动作业真实 R 时间");
        if (day.finish != null) {
            if (day.finish.before(day.start)) throw new IllegalArgumentException(dayLabel(day, target) + "的结束作业 R 时间早于启动锚点，数据或边界配置异常");
            day.durationSeconds = (day.finish.getTime() - day.start.getTime()) / 1000L;
            day.businessCompletionOffsetSeconds = businessCompletionOffsetSeconds(day.date, day.finish);
        }
        for (TaskSnapshot task : day.byGroup.values()) {
            if (task.completedAt != null) task.completionOffsetSeconds = (task.completedAt.getTime() - day.start.getTime()) / 1000L;
            if (task.readinessAt != null) task.readinessOffsetSeconds =
                (task.readinessAt.getTime() - day.start.getTime()) / 1000L;
        }
    }

    private static void requireTargetBoundaryTasks(DaySnapshot day) {
        if (day.startTask == null) throw new IllegalArgumentException("分析日期找不到启动作业");
        if (day.endTask == null) throw new IllegalArgumentException("分析日期找不到结束作业");
        if (groupKey(day.startTask.task).equals(groupKey(day.endTask.task))) throw new IllegalArgumentException("启动作业和结束作业不能相同");
        if (level(day.endTask.task) < 40) throw new IllegalArgumentException("批次结束作业 Level 不能小于 40");
        if (!isLevel40(day.startTask.task)) throw new IllegalArgumentException("批次启动作业必须是 Level 40");
    }

    private static void requireRAnchor(DaySnapshot day, boolean target) {
        if (day.startTask == null) throw new IllegalArgumentException(dayLabel(day, target) + "找不到启动作业");
        if (!isLevel40(day.startTask.task)) throw new IllegalArgumentException(dayLabel(day, target) + "的启动作业必须是 Level 40");
        if (day.startTask.completedAt == null) {
            String cause = day.startTask.task.actTimePlaceholder ? "R 时间是占位值" : "尚未进入 R 或没有有效 R 时间";
            throw new IllegalArgumentException(dayLabel(day, target) + "的启动作业缺少真实 R 时间（" + cause + "），请切换日期或启动作业");
        }
    }

    private static String baselineRUnusableReason(DaySnapshot day) {
        if (day.startTask == null) return "找不到启动作业";
        if (day.endTask == null) return "找不到结束作业";
        if (level(day.endTask.task) < 40) return "结束作业 Level 不能小于 40";
        if (!"R".equalsIgnoreCase(day.endTask.task.status)) return "结束作业状态不是 R";
        if (day.finish == null) return day.endTask.task.actTimePlaceholder ? "结束作业 R 时间是占位值" : "结束作业没有有效 R 时间";
        if (!isLevel40(day.startTask.task)) return "启动作业必须是 Level 40";
        if (day.startTask.completedAt == null) return day.startTask.task.actTimePlaceholder ?
            "启动作业 R 时间是占位值" : "启动作业没有真实 R 时间";
        if (day.finish.before(day.startTask.completedAt)) return "结束作业 R 时间早于启动作业 R 时间";
        return null;
    }

    private static Models.AnalysisTaskMetric metric(TaskSnapshot task, Averages baseline, boolean baselineAverageMode) {
        Models.AnalysisTaskMetric value = new Models.AnalysisTaskMetric();
        value.fabId = task.task.fabId; value.fabDescription = task.task.fabDescription;
        value.threadId = task.task.threadId; value.levelNo = task.task.levelNo; value.status = task.task.status;
        value.completedAt = copy(task.completedAt);
        value.baselineCompletedAt = copy(baseline.singleCompletedAt);
        value.baselineCompletionAverage = baselineAverageMode;
        value.readinessAt = copy(task.readinessAt); value.readinessPartial = task.readinessPartial;
        value.readyToCompleteSeconds = task.readyToCompleteSeconds;
        value.baselineCompletionOffsetSeconds = baseline.completionOffset;
        value.baselineReadinessAt = copy(baseline.singleReadinessAt);
        value.baselineReadinessPartial = baseline.readinessPartial;
        value.baselineReadyToCompleteSeconds = baseline.readyToComplete;
        value.readinessClockDeltaSeconds = difference(task.readinessOffsetSeconds, baseline.readinessOffset);
        value.completionClockDeltaSeconds = task.completedAt == null || baseline.businessCompletionOffset == null ? null :
            task.businessCompletionOffsetSeconds - baseline.businessCompletionOffset;
        value.completionOffsetSeconds = task.completedAt == null ? null : task.completionOffsetSeconds;
        value.readyToCompleteDeltaSeconds = difference(value.readyToCompleteSeconds, value.baselineReadyToCompleteSeconds);
        value.completionDelaySeconds = difference(value.completionOffsetSeconds, value.baselineCompletionOffsetSeconds);
        value.baselineSampleCount = baseline.completionCount;
        value.incompleteDependencies.addAll(task.incompleteDependencies);
        value.incompleteDependencies.addAll(baseline.dependencyIssues);
        value.ambiguousDependencies.addAll(task.ambiguousDependencies);
        if (baseline.dependencyMappingAmbiguous)
            for (String issue : baseline.dependencyIssues) if (!value.ambiguousDependencies.contains(issue)) value.ambiguousDependencies.add(issue);
        value.dependencyMappingAmbiguous = !task.ambiguousDependencies.isEmpty() || baseline.dependencyMappingAmbiguous;
        value.targetReadinessDependency = readinessDriverLabel(task.readinessDrivers);
        value.baselineReadinessDependency = baseline.readinessDependency;
        return value;
    }

    private static void classify(Models.AnalysisTaskMetric value, long thresholdSeconds) {
        value.confidence = "纯R时间对比";
        value.evidence = "只使用数据库中有效、非占位的 R 时间；不推断执行、调度等待或同Thread阻塞";
        if (value.completedAt == null || value.baselineCompletionOffsetSeconds == null) {
            value.dataQuality = "缺少有效R";
            value.recommendation = "无法比较";
        } else if (value.dependencyMappingAmbiguous) {
            value.dataQuality = "依赖映射有歧义";
            value.recommendation = "人工核对依赖映射";
        } else if (!value.incompleteDependencies.isEmpty()) {
            value.dataQuality = "依赖R不完整";
            value.recommendation = "人工核对前置依赖";
        } else {
            value.dataQuality = value.readinessAt == null ? "可比较（无依赖分解）" : "可比较";
            long completion = value.completionDelaySeconds == null ? Long.MIN_VALUE : value.completionDelaySeconds;
            long readiness = value.readinessClockDeltaSeconds == null ? Long.MIN_VALUE : value.readinessClockDeltaSeconds;
            long afterReady = value.readyToCompleteDeltaSeconds == null ? Long.MIN_VALUE : value.readyToCompleteDeltaSeconds;
            if (completion <= thresholdSeconds) value.recommendation = "正常或未超过关注阈值";
            else if (readiness > thresholdSeconds && afterReady > thresholdSeconds) value.recommendation = "上游偏移和就绪后间隔均增加";
            else if (afterReady > thresholdSeconds) value.recommendation = "建议人工检查当前任务";
            else if (readiness > thresholdSeconds) value.recommendation = "沿当天关键前置依赖检查";
            else value.recommendation = "完成偏移增加，建议人工核对";
        }
        value.reason = value.recommendation;
    }

    private static void enrichDependencyAttribution(List<Models.AnalysisTaskMetric> metrics,
                                                     Map<String, Models.AnalysisTaskMetric> metricsByFab,
                                                     DaySnapshot target,
                                                     List<Models.Dependency> dependencies,
                                                     String startFabId,
                                                     Models.AnalysisResult result) {
        Set<String> edgeKeys = new LinkedHashSet<String>();
        Map<String, List<String>> chainCache = new HashMap<String, List<String>>();
        for (Models.AnalysisTaskMetric metric : metrics) {
            TaskSnapshot task = target.byFab.get(normalize(metric.fabId));
            if (task == null || task.readinessDrivers.isEmpty()) continue;
            List<String> drivers = new ArrayList<String>();
            for (TaskSnapshot driver : task.readinessDrivers) {
                drivers.add(snapshotLabel(driver));
                String key = normalize(driver.task.fabId) + "->" + normalize(task.task.fabId);
                if (edgeKeys.add(key)) result.readinessCriticalDependencies.add(new Models.Dependency(task.task.fabId, driver.task.fabId));
            }
            metric.delayedDependencyChains.addAll(delayedDependencyChains(task, metricsByFab,
                normalize(startFabId), chainCache, new LinkedHashSet<String>()));
        }
    }

    private static List<String> delayedDependencyChains(TaskSnapshot current,
                                                        Map<String, Models.AnalysisTaskMetric> metricsByFab,
                                                        String startFabId,
                                                        Map<String, List<String>> cache,
                                                        Set<String> visiting) {
        String id = normalize(current.task.fabId);
        List<String> cached = cache.get(id);
        if (cached != null) return new ArrayList<String>(cached);
        Models.AnalysisTaskMetric metric = metricsByFab.get(id);
        boolean stop = id.equals(startFabId) || isLevel40(current.task) || current.readinessDrivers.isEmpty() ||
            metric == null || metric.completionDelaySeconds == null || metric.completionDelaySeconds <= 0;
        List<String> result = new ArrayList<String>();
        if (stop || !visiting.add(id)) {
            result.add(current.task.fabId);
        } else {
            for (TaskSnapshot driver : current.readinessDrivers) {
                List<String> upstreamChains = delayedDependencyChains(driver, metricsByFab, startFabId,
                    cache, new LinkedHashSet<String>(visiting));
                for (String chain : upstreamChains) {
                    String value = chain + " → " + current.task.fabId;
                    if (!result.contains(value)) result.add(value);
                }
            }
        }
        cache.put(id, new ArrayList<String>(result));
        return result;
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
                if (candidate != null && level(candidate.task) >= 40 && candidate.completedAt != null &&
                    (latest == null || candidate.completedAt.after(latest.completedAt))) latest = candidate;
            }
            current = latest;
        }
        List<String> order = new ArrayList<String>(reversed); Collections.reverse(order);
        return new LinkedHashSet<String>(order);
    }

    private static boolean dependencyPathExists(DaySnapshot day, List<Models.Dependency> dependencies,
                                                String startFab, String endFab) {
        return reachesStart(normalize(endFab), normalize(startFab), day, upstream(dependencies), new LinkedHashSet<String>());
    }

    private static boolean reachesStart(String current, String start, DaySnapshot day,
                                        Map<String, List<String>> upstream, Set<String> visiting) {
        if (current.equals(start)) return true;
        if (!visiting.add(current)) return false;
        List<String> values = upstream.get(current);
        if (values != null) for (String dependency : values) {
            TaskSnapshot task = day.byFab.get(dependency);
            if (task != null && level(task.task) >= 40 && reachesStart(dependency, start, day, upstream, visiting)) return true;
        }
        visiting.remove(current);
        return false;
    }

    private static Averages baselineAverage(Models.TaskKey taskKey, List<DaySnapshot> days) {
        long ready = 0, completion = 0, businessCompletion = 0, readinessOffset = 0;
        int rc = 0, cc = 0, ro = 0;
        LinkedHashSet<String> readinessDependencies = new LinkedHashSet<String>();
        Averages value = new Averages();
        for (DaySnapshot day : days) {
            TaskSnapshot task = day.byGroup.get(groupKey(taskKey)); if (task == null) continue;
            if (task.readyToCompleteSeconds != null) { ready += task.readyToCompleteSeconds; rc++; if (task.readinessPartial) value.readinessPartial = true; }
            if (task.readinessOffsetSeconds != null) { readinessOffset += task.readinessOffsetSeconds; ro++; }
            if (task.completedAt != null) {
                completion += task.completionOffsetSeconds;
                businessCompletion += task.businessCompletionOffsetSeconds;
                cc++;
            }
            if (!task.ambiguousDependencies.isEmpty()) value.dependencyMappingAmbiguous = true;
            for (String issue : task.incompleteDependencies) value.dependencyIssues.add(day.date + "：" + issue);
            for (String issue : task.ambiguousDependencies) value.dependencyIssues.add(day.date + "：" + issue);
            String drivers = readinessDriverLabel(task.readinessDrivers);
            if (!"--".equals(drivers)) readinessDependencies.add(drivers);
        }
        value.readyToComplete = rc == 0 ? null : ready / rc;
        value.completionCount = cc;
        value.readinessOffset = ro == 0 ? null : readinessOffset / ro;
        value.completionOffset = cc == 0 ? null : completion / cc;
        value.businessCompletionOffset = cc == 0 ? null : businessCompletion / cc;
        value.readinessDependency = readinessDependencies.isEmpty() ? "--" : join(new ArrayList<String>(readinessDependencies), "；");
        if (days.size() == 1) {
            TaskSnapshot task = days.get(0).byGroup.get(groupKey(taskKey));
            if (task != null) { value.singleCompletedAt = copy(task.completedAt); value.singleReadinessAt = copy(task.readinessAt); }
        }
        return value;
    }

    private static long averageDayDuration(List<DaySnapshot> days) {
        long total = 0; for (DaySnapshot day : days) total += day.durationSeconds; return total / days.size();
    }

    private static long averageBusinessCompletionOffset(List<DaySnapshot> days) {
        long total = 0; for (DaySnapshot day : days) total += day.businessCompletionOffsetSeconds; return total / days.size();
    }

    private static void buildSummary(Models.AnalysisResult result, DaySnapshot target) {
        Models.AnalysisTaskMetric bottleneck = result.rows.isEmpty() ? null : result.rows.get(0);
        String verdict = result.completionDelaySeconds == null ? "暂时无法判断整体完成时刻" :
            result.completionDelaySeconds > 0 ? "整体 delay " + UiFormat.duration(result.completionDelaySeconds) :
            result.completionDelaySeconds < 0 ? "整体提前 " + UiFormat.duration(Math.abs(result.completionDelaySeconds)) : "持平";
        String baselineClock = businessClock(result.baselineBusinessCompletionOffsetSeconds);
        if (target.complete) {
            result.summary = result.analysisDate + " 实际完成 " + UiFormat.dateTime(result.targetFinish) +
                "（业务完成时刻 " + businessClock(result.targetBusinessCompletionOffsetSeconds) + "），基准业务完成时刻 " +
                baselineClock + "，" + verdict + "；启动对齐应完成时间 " + UiFormat.dateTime(result.expectedFinish);
        } else if (target.endTask != null && "R".equalsIgnoreCase(target.endTask.task.status)) {
            result.summary = result.analysisDate + " 的结束作业状态为 R，但完成时间无效；启动对齐应完成时间 " +
                UiFormat.dateTime(result.expectedFinish) + "，无法判断整体完成时刻";
        } else {
            result.summary = result.analysisDate + " 的结束作业尚未完成；启动对齐应完成时间 " + UiFormat.dateTime(result.expectedFinish) +
                etaSummary(result.eta) + "；ETA 仅供 Checkpoint，不作为正式 delay 判定";
        }
        result.detail = "区间：" + result.startTaskLabel + " → " + result.endTaskLabel +
            "；纯 R 口径：整体只比较结束作业完成时刻；批次内部以启动作业真实 R 对齐；任务完成偏移 = 依赖就绪偏移 + 就绪后完成间隔差。" +
            "启动作业真实 R：" + UiFormat.dateTime(result.targetStart) +
            "；基准：" + result.baselineLabel + "（基准批次耗时 " + UiFormat.duration(result.baselineDurationSeconds) +
            "，业务完成时刻 " + baselineClock + "）" +
            (result.dependencyPathComplete ? "" : "；启动与结束作业在当前依赖数据中不连通，慢点路径可能不完整") +
            (bottleneck == null ? "。" : "；优先人工核对：" + bottleneck.fabId + "（" + bottleneck.recommendation + "）。") +
            "关注阈值 " + result.attentionThresholdSeconds + " 秒；可比较 " + result.completionOnlyCount +
            "，数据不足或有歧义 " + result.insufficientCount + "。所有归因均为时间区间定位，不自动认定具体原因。";
        if (!target.complete && result.eta != null && result.eta.detail != null && !result.eta.detail.isEmpty())
            result.detail += " 纯R ETA：" + result.eta.detail;
    }

    private static String etaSummary(Models.DagEta eta) {
        if (eta == null) return "；纯 R ETA 未计算";
        if (eta.available) return "；纯 R ETA P50 " + UiFormat.dateTime(eta.estimatedCompletion) +
            "，P75 " + UiFormat.dateTime(eta.conservativeCompletion) +
            "，下一个 Checkpoint " + UiFormat.dateTime(eta.nextCheckpoint);
        if (eta.lowerBound) return "；仅有已知路径下限 " + UiFormat.dateTime(eta.estimatedCompletion) + "，不生成完整 ETA";
        return "；无法生成可靠 ETA（" + eta.detail + "）";
    }

    private static boolean includeMetric(Models.AnalysisTaskMetric metric, Models.AnalysisRequest request) {
        boolean boundary = taskMatches(metric, request.startThreadId, request.startLevelNo, request.startFabId) ||
            taskMatches(metric, request.endThreadId, request.endLevelNo, request.endFabId);
        String thread = request.threadFilter == null ? "" : request.threadFilter.trim().toUpperCase(Locale.ROOT);
        if (!boundary && !thread.isEmpty() && !normalize(metric.threadId).contains(thread)) return false;
        Integer level = null;
        try { level = Integer.valueOf(metric.levelNo.trim()); } catch (Exception ignored) {}
        if (!boundary && request.levelMinimum != null && (level == null || level < request.levelMinimum)) return false;
        if (!boundary && request.levelMaximum != null && (level == null || level > request.levelMaximum)) return false;
        return true;
    }

    private static boolean taskMatches(Models.AnalysisTaskMetric task, String thread, String level, String fab) {
        return normalize(task.threadId).equals(normalize(thread)) && normalize(task.levelNo).equals(normalize(level)) &&
            normalize(task.fabId).equals(normalize(fab));
    }

    private static List<Models.Dependency> dependenciesForTasks(List<Models.Dependency> dependencies, List<Models.OracleTask> tasks) {
        Set<String> allowed = new LinkedHashSet<String>();
        for (Models.OracleTask task : tasks) allowed.add(normalize(task.fabId));
        Map<String, Models.Dependency> unique = new LinkedHashMap<String, Models.Dependency>();
        for (Models.Dependency edge : dependencies) {
            String owner = normalize(edge.fabId), dependency = normalize(edge.dependencyId);
            if (allowed.contains(owner)) unique.put(dependency + "->" + owner, edge);
        }
        return new ArrayList<Models.Dependency>(unique.values());
    }

    private static void resolveReadiness(TaskSnapshot task, DaySnapshot day,
                                         List<Models.Dependency> dependencies) {
        if (task == null || level(task.task) <= 40) return;
        boolean hasDependency = false, incomplete = false;
        int eligible = 0;
        Date latest = null;
        for (Models.Dependency edge : dependencies) if (normalize(edge.fabId).equals(normalize(task.task.fabId))) {
            hasDependency = true;
            List<TaskSnapshot> matches = day.byFabAll.get(normalize(edge.dependencyId));
            if (matches != null && matches.size() > 1) {
                incomplete = true;
                task.ambiguousDependencies.add(edge.dependencyId + "（当前日期匹配到 " + matches.size() + " 个 Thread/Level）");
                continue;
            }
            TaskSnapshot dependency = matches == null || matches.isEmpty() ? null : matches.get(0);
            if (dependency != null && level(dependency.task) < 40) {
                incomplete = true; task.incompleteDependencies.add(edge.dependencyId + "（Level 小于 40）"); continue;
            }
            eligible++;
            if (dependency == null || dependency.completedAt == null) {
                incomplete = true;
                task.incompleteDependencies.add(dependencyLabel(edge.dependencyId, dependency));
                continue;
            }
            if (latest == null || dependency.completedAt.after(latest)) {
                latest = dependency.completedAt;
                task.readinessDrivers.clear();
                task.readinessDrivers.add(dependency);
            } else if (dependency.completedAt.equals(latest)) task.readinessDrivers.add(dependency);
        }
        if (hasDependency && eligible > 0 && !incomplete && latest != null) {
            task.readinessAt = copy(latest);
            if (task.completedAt != null && !latest.after(task.completedAt)) {
                task.readyToCompleteSeconds = (task.completedAt.getTime() - latest.getTime()) / 1000L;
            }
        }
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
    private static long businessCompletionOffsetSeconds(String processDate, Date completion) {
        try {
            LocalDate businessDate = LocalDate.parse(processDate, DateTimeFormatter.BASIC_ISO_DATE);
            LocalDateTime local = LocalDateTime.ofInstant(Instant.ofEpochMilli(completion.getTime()), ZoneId.systemDefault());
            long dayOffset = ChronoUnit.DAYS.between(businessDate, local.toLocalDate());
            return dayOffset * 86400L + local.toLocalTime().toSecondOfDay();
        } catch (Exception e) {
            throw new IllegalArgumentException("业务日期 " + processDate + " 无法用于完成时刻比较", e);
        }
    }
    private static String businessClock(Long seconds) {
        if (seconds == null) return "--";
        long day = Math.floorDiv(seconds, 86400L), secondOfDay = Math.floorMod(seconds, 86400L);
        long hour = secondOfDay / 3600L, minute = secondOfDay % 3600L / 60L, second = secondOfDay % 60L;
        String prefix = day == 0 ? "" : day == 1 ? "次日 " : day == -1 ? "前一日 " : (day > 0 ? "+" + day + "日 " : day + "日 ");
        return prefix + String.format(Locale.ROOT, "%02d:%02d:%02d", hour, minute, second);
    }
    private static String snapshotLabel(TaskSnapshot task) {
        if (task == null || task.task == null) return "--";
        return task.task.fabId + (task.task.fabDescription == null || task.task.fabDescription.trim().isEmpty() ? "" : " " + task.task.fabDescription) +
            "（" + task.task.threadId + "/" + task.task.levelNo + "）";
    }
    private static String readinessDriverLabel(List<TaskSnapshot> drivers) {
        if (drivers == null || drivers.isEmpty()) return "--";
        List<String> labels = new ArrayList<String>();
        for (TaskSnapshot driver : drivers) labels.add(snapshotLabel(driver));
        return join(labels, "；");
    }
    private static String dependencyLabel(String fabId, TaskSnapshot task) {
        if (task == null) return fabId + "（当前业务日期无任务）";
        String time = task.task.actTimePlaceholder ? "占位时间" : UiFormat.dateTime(task.task.actTime);
        return snapshotLabel(task) + "，状态 " + task.task.status + "，状态时间 " + time;
    }
    private static long time(Date value) { return value == null ? Long.MIN_VALUE : value.getTime(); }
    private static Date copy(Date value) { return value == null ? null : new Date(value.getTime()); }
    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static boolean isLevel40(Models.TaskKey task) { return task != null && "40".equals(normalize(task.levelNo)); }
    private static int level(Models.TaskKey task) { try { return Integer.parseInt(normalize(task.levelNo)); } catch (Exception e) { return Integer.MIN_VALUE; } }
    private static String groupKey(Models.TaskKey value) { return groupKey(value.threadId, value.levelNo, value.fabId); }
    private static String groupKey(String thread, String level, String fab) { return normalize(thread) + "|" + normalize(level) + "|" + normalize(fab); }
    private static String taskLabel(String thread, String level, String fab) { return thread + "/" + level + "/" + fab; }
    private static String dayLabel(DaySnapshot day, boolean target) { return target ? "分析日期" : day.date; }
    private static String join(List<String> values, String delimiter) { StringBuilder result = new StringBuilder(); for (String value : values) { if (result.length() > 0) result.append(delimiter); result.append(value); } return result.toString(); }
}
