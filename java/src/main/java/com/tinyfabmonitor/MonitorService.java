package com.tinyfabmonitor;

import java.io.IOException;
import java.sql.Connection;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

final class MonitorService implements AutoCloseable {
    interface Listener { void dashboardChanged(); }

    private final AppConfig config;
    private final OracleRepository repository;
    private final StateStore store;
    private final Logger logger;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService analysisExecutor;
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private final Semaphore databaseReadPermit = new Semaphore(1, true);
    private final Object lock = new Object();
    private final List<Listener> listeners = new ArrayList<Listener>();

    private String processDate;
    private List<Models.OracleTask> tasks = new ArrayList<Models.OracleTask>();
    private List<Models.Dependency> dependencies = new ArrayList<Models.Dependency>();
    private List<Models.Dependency> etaUpstreamDependencies = new ArrayList<Models.Dependency>();
    private String dagRootFabId = "";
    private boolean dagLoading;
    private String dagError = "";
    private long dagRequestId;
    private boolean connected;
    private Date lastPollAt;
    private Date nextPollAt;
    private String lastError = "";
    private ScheduledFuture<?> pollFuture;
    private boolean closed;
    private Models.AnalysisState analysisState = new Models.AnalysisState();

    MonitorService(AppConfig config, OracleRepository repository, StateStore store, Logger logger) {
        this.config = config;
        this.repository = repository;
        this.store = store;
        this.logger = logger;
        Models.PersistedState state = store.snapshot();
        this.processDate = state.selectedProcessDate == null || state.selectedProcessDate.isEmpty()
            ? config.processDate : state.selectedProcessDate;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "fab-monitor-poller");
            thread.setDaemon(true);
            return thread;
        });
        this.analysisExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "fab-performance-analysis");
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        schedulePoll(0);
    }

    void addListener(Listener listener) { synchronized (listeners) { listeners.add(listener); } }

    void refreshNow() { schedulePoll(0); }

    private void schedulePoll(long delayMinutes) {
        synchronized (lock) {
            if (closed) return;
            if (pollFuture != null && !pollFuture.isDone()) pollFuture.cancel(false);
            nextPollAt = new Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(delayMinutes));
            pollFuture = scheduler.schedule(this::pollSafely, delayMinutes, TimeUnit.MINUTES);
        }
        fireChanged();
    }

    static int randomPollInterval(int minimum, int maximum) {
        if (minimum > maximum) throw new IllegalArgumentException("最小刷新间隔不能大于最大刷新间隔");
        return minimum == maximum ? minimum : ThreadLocalRandom.current().nextInt(minimum, maximum + 1);
    }

    void setProcessDate(String date) throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        format.setLenient(false);
        if (date == null || !date.matches("\\d{8}")) throw new IllegalArgumentException("日期必须是 YYYYMMDD 格式");
        try { format.parse(date); } catch (ParseException e) { throw new IllegalArgumentException("日期无效：" + date); }
        synchronized (lock) {
            processDate = date;
            tasks = new ArrayList<Models.OracleTask>();
            dependencies = new ArrayList<Models.Dependency>();
            etaUpstreamDependencies = new ArrayList<Models.Dependency>();
            dagRootFabId = "";
            dagLoading = false;
            dagError = "";
            dagRequestId++;
            lastError = "";
        }
        store.update(state -> state.selectedProcessDate = date);
        fireChanged();
        refreshNow();
    }

    private void pollSafely() {
        if (!polling.compareAndSet(false, true)) return;
        fireChanged();
        String date;
        synchronized (lock) { date = processDate; }
        logger.info("开始读取业务日期 " + date);
        try {
            List<Models.OracleTask> fetchedTasks;
            databaseReadPermit.acquireUninterruptibly();
            try {
                Connection connection = repository.open();
                try {
                    fetchedTasks = selectLatestTasks(repository.fetchTasks(connection, date));
                    applyTaskStates(fetchedTasks);
                } finally { connection.close(); }
            } finally { databaseReadPermit.release(); }

            Date now = new Date();
            synchronized (lock) {
                if (date.equals(processDate)) {
                    tasks = fetchedTasks;
                    if (!dagRootFabId.isEmpty() && !containsFabId(fetchedTasks, dagRootFabId)) {
                        dependencies = new ArrayList<Models.Dependency>();
                        etaUpstreamDependencies = new ArrayList<Models.Dependency>();
                        dagRootFabId = "";
                        dagLoading = false;
                        dagError = "中心 FAB 已不在当前业务日期任务中";
                        dagRequestId++;
                    }
                    connected = true;
                    lastPollAt = now;
                    lastError = "";
                }
            }
            logger.info("读取完成：" + fetchedTasks.size() + " 个任务");
        } catch (Exception e) {
            logger.log(Level.WARNING, "轮询失败", e);
            Date now = new Date();
            synchronized (lock) {
                connected = false;
                lastPollAt = now;
                lastError = rootMessage(e);
            }
        } finally {
            polling.set(false);
            fireChanged();
            schedulePoll(randomPollInterval(config.pollIntervalMinMinutes, config.pollIntervalMaxMinutes));
        }
    }

    void loadDependencyDag(String requestedFabId) {
        loadDependencyDag(requestedFabId, config.dagUpstreamLevels, config.dagDownstreamLevels);
    }

    void loadDependencyDag(String requestedFabId, int upstreamLevels, int downstreamLevels) {
        if (upstreamLevels < 0 || upstreamLevels > 15 || downstreamLevels < 0 || downstreamLevels > 15) {
            throw new IllegalArgumentException("DAG 上下游层数必须是 0–15 的整数");
        }
        final String date;
        final String root;
        final List<Models.OracleTask> currentTasks;
        final long requestId;
        synchronized (lock) {
            root = canonicalFabId(tasks, requestedFabId);
            if (root == null) {
                dagError = "FAB 不属于当前业务日期：" + requestedFabId;
                dagLoading = false;
                dependencies = new ArrayList<Models.Dependency>();
                etaUpstreamDependencies = new ArrayList<Models.Dependency>();
                dagRootFabId = "";
                fireChangedLater();
                return;
            }
            currentTasks = new ArrayList<Models.OracleTask>(tasks);
            date = processDate;
            dagRootFabId = root;
            dependencies = new ArrayList<Models.Dependency>();
            etaUpstreamDependencies = new ArrayList<Models.Dependency>();
            dagLoading = true;
            dagError = "";
            requestId = ++dagRequestId;
        }
        fireChanged();
        scheduler.execute(() -> {
            try {
                logger.info("读取 FAB " + root + " 的依赖（上游 " + upstreamLevels + " 层，下游 " + downstreamLevels + " 层）");
                Models.DependencyAnalysis fetched;
                databaseReadPermit.acquireUninterruptibly();
                try {
                    Connection connection = repository.open();
                    try { fetched = repository.fetchDependencyAnalysis(connection, root, currentTasks, upstreamLevels, downstreamLevels); }
                    finally { connection.close(); }
                } finally { databaseReadPermit.release(); }
                synchronized (lock) {
                    if (requestId == dagRequestId && date.equals(processDate) && root.equals(dagRootFabId)) {
                        dependencies = fetched.displayDependencies;
                        etaUpstreamDependencies = fetched.etaUpstreamDependencies;
                        dagLoading = false;
                        dagError = "";
                    }
                }
                logger.info("依赖读取完成：中心 " + root + "，画面 " + fetched.displayDependencies.size() + " 条，ETA 上游 " + fetched.etaUpstreamDependencies.size() + " 条");
            } catch (Exception e) {
                logger.log(Level.WARNING, "依赖 DAG 读取失败", e);
                synchronized (lock) {
                    if (requestId == dagRequestId) {
                        dependencies = new ArrayList<Models.Dependency>();
                        etaUpstreamDependencies = new ArrayList<Models.Dependency>();
                        dagLoading = false;
                        dagError = rootMessage(e);
                    }
                }
            } finally { fireChanged(); }
        });
    }

    void applyTaskStates(final List<Models.OracleTask> observed) throws IOException {
        store.update(state -> {
            for (Models.OracleTask task : observed) {
                if (task.actTime == null) continue;
                final String fullId = task.fullId();
                Models.TrackedTask tracked = state.tracked.get(fullId);
                if (tracked != null && task.status.equals(tracked.lastStatus) && task.actTime.equals(tracked.lastActTime)) continue;
                if (tracked == null) {
                    tracked = new Models.TrackedTask();
                    tracked.key = keyOf(task);
                    state.tracked.put(fullId, tracked);
                }
                Models.RunRecord active = findRun(state.runs, tracked.activeRunId);
                Models.StateEvent event = new Models.StateEvent(task.status, task.actTime);
                if ("I".equals(task.status)) {
                    if (active == null) {
                        active = new Models.RunRecord();
                        active.id = fullId + "|" + task.actTime.getTime();
                        active.task = keyOf(task);
                        active.startedAt = task.actTime;
                        active.events.add(event);
                        state.runs.add(active);
                        tracked.activeRunId = active.id;
                    } else {
                        if (hasAnomalySinceLastI(active.events)) active.startedAt = task.actTime;
                        appendEvent(active, event);
                    }
                } else if ("E".equals(task.status)) {
                    if (active == null) {
                        active = new Models.RunRecord();
                        active.id = fullId + "|anomaly|" + task.actTime.getTime();
                        active.task = keyOf(task);
                        state.runs.add(active);
                        tracked.activeRunId = active.id;
                    }
                    appendEvent(active, event);
                    if (!active.anomalyTimes.contains(task.actTime)) active.anomalyTimes.add(task.actTime);
                } else if ("R".equals(task.status)) {
                    if (active != null && active.completedAt == null) {
                        appendEvent(active, event);
                        active.completedAt = task.actTime;
                        if (active.startedAt != null) active.durationSeconds = Math.max(0L, (active.completedAt.getTime() - active.startedAt.getTime()) / 1000L);
                        tracked.activeRunId = "";
                    }
                } else if (active != null) {
                    appendEvent(active, event);
                }
                if (active != null && task.fabDescription != null && !task.fabDescription.trim().isEmpty()) active.fabDescription = task.fabDescription.trim();
                tracked.lastStatus = task.status;
                tracked.lastActTime = task.actTime;
            }
        });
    }

    Models.Dashboard dashboard() {
        Models.Dashboard dashboard = new Models.Dashboard();
        synchronized (lock) {
            dashboard.processDate = processDate;
            dashboard.connected = connected;
            dashboard.polling = polling.get();
            dashboard.lastPollAt = copy(lastPollAt);
            dashboard.nextPollAt = copy(nextPollAt);
            dashboard.lastError = lastError;
            dashboard.dagRootFabId = dagRootFabId;
            dashboard.dagLoading = dagLoading;
            dashboard.dagError = dagError;
            dashboard.dagRequestId = dagRequestId;
            dashboard.analysis = analysisState;
        }
        Models.PersistedState state = store.snapshot();
        Map<String, Models.GroupStat> stats = buildGroupStats(state.runs);
        Map<String, long[]> fabTotals = new HashMap<String, long[]>();
        for (Models.RunRecord run : state.runs) {
            if (run.startedAt == null || run.completedAt == null) continue;
            long[] total = fabTotals.get(run.task.fabId);
            if (total == null) { total = new long[2]; fabTotals.put(run.task.fabId, total); }
            total[0] += run.durationSeconds; total[1]++;
        }
        for (Map.Entry<String, long[]> entry : fabTotals.entrySet()) {
            if (entry.getValue()[1] >= 1) dashboard.historicalAverageByFab.put(entry.getKey(), entry.getValue()[0] / entry.getValue()[1]);
        }
        List<Models.OracleTask> taskSnapshot;
        synchronized (lock) {
            taskSnapshot = new ArrayList<Models.OracleTask>(tasks);
            dashboard.dependencies = new ArrayList<Models.Dependency>(dependencies);
            dashboard.etaUpstreamDependencies = new ArrayList<Models.Dependency>(etaUpstreamDependencies);
        }
        Date now = new Date();
        for (Models.OracleTask task : taskSnapshot) {
            Models.TaskView view = viewOf(task);
            Models.TrackedTask tracked = state.tracked.get(task.fullId());
            if (tracked != null) {
                Models.RunRecord active = findRun(state.runs, tracked.activeRunId);
                if (active != null) {
                    view.startedAt = copy(active.startedAt);
                    if (active.startedAt != null) view.currentDurationSeconds = Math.max(0L, (now.getTime() - active.startedAt.getTime()) / 1000L);
                    view.anomalyTimes = new ArrayList<Date>(active.anomalyTimes);
                }
            }
            Models.GroupStat stat = stats.get(task.groupId());
            if (stat != null) {
                view.averageDurationSeconds = stat.average;
                view.completedRunCount = stat.count;
                view.lastDurationSeconds = stat.last;
                if (view.startedAt == null && stat.lastRun != null && task.processDate.equals(stat.lastRun.task.processDate)) {
                    view.startedAt = copy(stat.lastRun.startedAt);
                    view.completedAt = copy(stat.lastRun.completedAt);
                    view.anomalyTimes = new ArrayList<Date>(stat.lastRun.anomalyTimes);
                }
            }
            dashboard.tasks.add(view);
        }
        Collections.sort(dashboard.tasks, Comparator.comparing(t -> t.threadId + "|" + t.levelNo + "|" + t.fabId));
        dashboard.totalHistoricalRuns = state.runs.size();
        dashboard.recentRuns = new ArrayList<Models.RunRecord>(state.runs);
        Map<String, String> currentDescriptions = new HashMap<String, String>();
        for (Models.OracleTask task : taskSnapshot) {
            if (task.fabDescription != null && !task.fabDescription.trim().isEmpty()) currentDescriptions.put(task.groupId(), task.fabDescription.trim());
        }
        for (Models.RunRecord run : dashboard.recentRuns) {
            if ((run.fabDescription == null || run.fabDescription.trim().isEmpty()) && run.task != null) {
                String matched = currentDescriptions.get(run.task.groupId());
                if (matched != null) run.fabDescription = matched;
            }
        }
        Collections.sort(dashboard.recentRuns, (a, b) -> eventTime(b).compareTo(eventTime(a)));
        if (dashboard.recentRuns.size() > 200) dashboard.recentRuns = new ArrayList<Models.RunRecord>(dashboard.recentRuns.subList(0, 200));
        if (!dashboard.dagRootFabId.isEmpty() && !dashboard.dagLoading && dashboard.dagError.isEmpty()) {
            dashboard.dagEta = EtaCalculator.calculate(dashboard.dagRootFabId, dashboard.tasks, dashboard.etaUpstreamDependencies, now);
        }
        return dashboard;
    }

    int defaultDagUpstreamLevels() { return config.dagUpstreamLevels; }
    int defaultDagDownstreamLevels() { return config.dagDownstreamLevels; }
    StateStore.CleanupPreview previewCleanup(int retentionDays) { return store.previewCleanup(retentionDays); }
    StateStore.CleanupPreview cleanupHistory(int retentionDays) throws IOException {
        StateStore.CleanupPreview result = store.cleanup(retentionDays);
        fireChanged();
        return result;
    }

    void runPerformanceAnalysis(final Models.AnalysisRequest request) {
        validateAnalysisRequest(request);
        final long requestId;
        synchronized (lock) {
            if (analysisState.loading) throw new IllegalStateException("已有耗时分析正在运行，请等待完成");
            Models.AnalysisState next = new Models.AnalysisState(); next.loading = true; next.requestId = analysisState.requestId + 1;
            analysisState = next; requestId = next.requestId;
        }
        fireChanged();
        analysisExecutor.execute(() -> {
            try {
                logger.info("开始耗时分析：" + request.analysisDate + "，模式 " + request.baselineMode);
                Map<String, List<Models.OracleTask>> tasksByDate = new LinkedHashMap<String, List<Models.OracleTask>>();
                List<String> baselineDates;
                List<Models.Dependency> analysisDependencies;
                databaseReadPermit.acquireUninterruptibly();
                try {
                    Connection connection = repository.open();
                    try {
                        List<Models.OracleTask> target = filteredTasks(tasksForAnalysisDate(connection, request.analysisDate), request);
                        tasksByDate.put(request.analysisDate, target);
                        if (request.baselineMode == Models.AnalysisBaselineMode.SPECIFIED_DATE) {
                            baselineDates = Collections.singletonList(request.specifiedBaselineDate);
                        } else {
                            int count = request.baselineMode == Models.AnalysisBaselineMode.PREVIOUS_COMPLETE ? 1 : request.recentDateCount;
                            baselineDates = repository.fetchPreviousCompletedDates(connection, request.analysisDate, count);
                        }
                        if (baselineDates.isEmpty()) throw new IllegalArgumentException("找不到早于分析日期的完整业务日期");
                        Set<String> owners = new java.util.LinkedHashSet<String>();
                        for (Models.OracleTask task : target) owners.add(task.fabId);
                        for (String date : baselineDates) {
                            List<Models.OracleTask> baseline = filteredTasks(tasksForAnalysisDate(connection, date), request);
                            tasksByDate.put(date, baseline);
                            for (Models.OracleTask task : baseline) owners.add(task.fabId);
                        }
                        analysisDependencies = repository.fetchDependenciesForOwners(connection, owners);
                    } finally { connection.close(); }
                } finally { databaseReadPermit.release(); }
                Models.AnalysisResult result = PerformanceAnalyzer.analyze(request, tasksByDate, store.snapshot().runs,
                    analysisDependencies, baselineDates, new Date());
                synchronized (lock) {
                    if (analysisState.requestId == requestId) {
                        analysisState.loading = false; analysisState.error = ""; analysisState.result = result;
                    }
                }
                logger.info("耗时分析完成：" + result.summary);
            } catch (Exception e) {
                logger.log(Level.WARNING, "耗时分析失败", e);
                synchronized (lock) {
                    if (analysisState.requestId == requestId) { analysisState.loading = false; analysisState.error = rootMessage(e); }
                }
            } finally { fireChanged(); }
        });
    }

    private List<Models.OracleTask> tasksForAnalysisDate(Connection connection, String date) throws Exception {
        synchronized (lock) {
            if (date.equals(processDate) && !tasks.isEmpty()) return new ArrayList<Models.OracleTask>(tasks);
        }
        return selectLatestTasks(repository.fetchTasks(connection, date));
    }

    private static List<Models.OracleTask> filteredTasks(List<Models.OracleTask> values, Models.AnalysisRequest request) {
        List<Models.OracleTask> result = new ArrayList<Models.OracleTask>();
        String thread = request.threadFilter == null ? "" : request.threadFilter.trim().toUpperCase(Locale.ROOT);
        for (Models.OracleTask task : values) {
            if (!thread.isEmpty() && (task.threadId == null || !task.threadId.toUpperCase(Locale.ROOT).contains(thread))) continue;
            Integer level = null;
            try { level = Integer.valueOf(task.levelNo.trim()); } catch (Exception ignored) {}
            if (request.levelMinimum != null && (level == null || level < request.levelMinimum)) continue;
            if (request.levelMaximum != null && (level == null || level > request.levelMaximum)) continue;
            result.add(task);
        }
        return result;
    }

    private static void validateAnalysisRequest(Models.AnalysisRequest request) {
        validateDate(request.analysisDate, "分析日期");
        if (request.baselineMode == Models.AnalysisBaselineMode.SPECIFIED_DATE) {
            validateDate(request.specifiedBaselineDate, "基准日期");
            if (request.specifiedBaselineDate.compareTo(request.analysisDate) >= 0) throw new IllegalArgumentException("基准日期必须早于分析日期");
        }
        if (request.recentDateCount < 2 || request.recentDateCount > 30) throw new IllegalArgumentException("历史平均日期数必须是 2–30");
        if (request.levelMinimum != null && request.levelMaximum != null && request.levelMinimum > request.levelMaximum) throw new IllegalArgumentException("Level No 起始值不能大于结束值");
    }

    private static void validateDate(String date, String label) {
        if (date == null || !date.matches("\\d{8}")) throw new IllegalArgumentException(label + "必须是 YYYYMMDD 格式");
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.ROOT); format.setLenient(false);
        try { format.parse(date); } catch (ParseException e) { throw new IllegalArgumentException(label + "无效：" + date); }
    }

    static Map<String, Models.GroupStat> buildGroupStats(List<Models.RunRecord> runs) {
        class Acc { long total; int count; Models.RunRecord last; }
        Map<String, Acc> accumulators = new LinkedHashMap<String, Acc>();
        for (Models.RunRecord run : runs) {
            if (run.completedAt == null || run.startedAt == null) continue;
            String key = run.task.groupId();
            Acc acc = accumulators.get(key);
            if (acc == null) { acc = new Acc(); accumulators.put(key, acc); }
            acc.total += run.durationSeconds;
            acc.count++;
            if (acc.last == null || run.completedAt.after(acc.last.completedAt)) acc.last = run;
        }
        Map<String, Models.GroupStat> result = new HashMap<String, Models.GroupStat>();
        for (Map.Entry<String, Acc> entry : accumulators.entrySet()) {
            Acc acc = entry.getValue();
            Models.GroupStat stat = new Models.GroupStat();
            stat.count = acc.count;
            stat.average = acc.count >= 1 ? acc.total / acc.count : 0L;
            stat.last = acc.last.durationSeconds;
            stat.lastRun = acc.last;
            result.put(entry.getKey(), stat);
        }
        return result;
    }

    static List<Models.OracleTask> selectLatestTasks(List<Models.OracleTask> observed) {
        Map<String, Models.OracleTask> latest = new LinkedHashMap<String, Models.OracleTask>();
        for (Models.OracleTask task : observed) {
            Models.OracleTask previous = latest.get(task.fullId());
            if (previous == null || previous.actTime == null ||
                (task.actTime != null && task.actTime.after(previous.actTime))) {
                latest.put(task.fullId(), task);
            }
        }
        return new ArrayList<Models.OracleTask>(latest.values());
    }

    private void fireChanged() {
        List<Listener> copy;
        synchronized (listeners) { copy = new ArrayList<Listener>(listeners); }
        for (Listener listener : copy) listener.dashboardChanged();
    }

    private void fireChangedLater() { scheduler.execute(this::fireChanged); }

    private static boolean containsFabId(List<Models.OracleTask> values, String fabId) { return canonicalFabId(values, fabId) != null; }
    private static String canonicalFabId(List<Models.OracleTask> values, String fabId) {
        if (fabId == null) return null;
        for (Models.OracleTask task : values) if (task.fabId != null && task.fabId.equalsIgnoreCase(fabId.trim())) return task.fabId;
        return null;
    }

    private static Models.TaskKey keyOf(Models.TaskKey source) {
        return new Models.TaskKey(source.processDate, source.threadId, source.levelNo, source.fabId);
    }

    private static Models.TaskView viewOf(Models.OracleTask task) {
        Models.TaskView view = new Models.TaskView();
        view.processDate = task.processDate; view.threadId = task.threadId; view.levelNo = task.levelNo; view.fabId = task.fabId;
        view.status = task.status; view.actTime = copy(task.actTime); view.actTimePlaceholder = task.actTimePlaceholder; view.levelDescription = task.levelDescription; view.fabDescription = task.fabDescription;
        return view;
    }

    private static Models.RunRecord findRun(List<Models.RunRecord> runs, String id) {
        if (id == null || id.isEmpty()) return null;
        for (int i = runs.size() - 1; i >= 0; i--) if (id.equals(runs.get(i).id)) return runs.get(i);
        return null;
    }

    private static void appendEvent(Models.RunRecord run, Models.StateEvent event) {
        if (!run.events.isEmpty()) {
            Models.StateEvent last = run.events.get(run.events.size() - 1);
            if (event.status.equals(last.status) && event.at.equals(last.at)) return;
        }
        run.events.add(event);
    }

    private static boolean hasAnomalySinceLastI(List<Models.StateEvent> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if ("E".equals(events.get(i).status)) return true;
            if ("I".equals(events.get(i).status)) return false;
        }
        return false;
    }

    private static Date copy(Date value) { return value == null ? null : new Date(value.getTime()); }
    private static Date eventTime(Models.RunRecord run) {
        if (run.startedAt != null) return run.startedAt;
        if (run.events != null && !run.events.isEmpty() && run.events.get(0).at != null) return run.events.get(0).at;
        return new Date(0);
    }
    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : "：" + message);
    }

    @Override public void close() {
        synchronized (lock) { closed = true; if (pollFuture != null) pollFuture.cancel(false); }
        scheduler.shutdownNow();
        analysisExecutor.shutdownNow();
    }
}
