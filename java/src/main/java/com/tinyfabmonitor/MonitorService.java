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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private final Object lock = new Object();
    private final List<Listener> listeners = new ArrayList<Listener>();

    private String processDate;
    private List<Models.OracleTask> tasks = new ArrayList<Models.OracleTask>();
    private List<Models.Dependency> dependencies = new ArrayList<Models.Dependency>();
    private boolean connected;
    private Date lastPollAt;
    private Date nextPollAt;
    private String lastError = "";

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
    }

    void start() {
        scheduler.scheduleWithFixedDelay(this::pollSafely, 0, config.pollIntervalMinutes, TimeUnit.MINUTES);
    }

    void addListener(Listener listener) { synchronized (listeners) { listeners.add(listener); } }

    void refreshNow() { scheduler.execute(this::pollSafely); }

    void setProcessDate(String date) throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        format.setLenient(false);
        if (date == null || !date.matches("\\d{8}")) throw new IllegalArgumentException("日期必须是 YYYYMMDD 格式");
        try { format.parse(date); } catch (ParseException e) { throw new IllegalArgumentException("日期无效：" + date); }
        synchronized (lock) {
            processDate = date;
            tasks = new ArrayList<Models.OracleTask>();
            dependencies = new ArrayList<Models.Dependency>();
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
            List<Models.Dependency> fetchedDependencies;
            Connection connection = repository.open();
            try {
                fetchedTasks = repository.fetchTasks(connection, date);
                applyTaskStates(fetchedTasks);
                Set<String> seen = new HashSet<String>();
                List<String> roots = new ArrayList<String>();
                for (Models.OracleTask task : fetchedTasks) if (!task.fabId.isEmpty() && seen.add(task.fabId)) roots.add(task.fabId);
                fetchedDependencies = repository.fetchDependencyGraph(connection, roots);
            } finally { connection.close(); }

            Date now = new Date();
            synchronized (lock) {
                if (date.equals(processDate)) {
                    tasks = fetchedTasks;
                    dependencies = fetchedDependencies;
                    connected = true;
                    lastPollAt = now;
                    nextPollAt = new Date(now.getTime() + TimeUnit.MINUTES.toMillis(config.pollIntervalMinutes));
                    lastError = "";
                }
            }
            logger.info("读取完成：" + fetchedTasks.size() + " 个任务，" + fetchedDependencies.size() + " 条依赖");
        } catch (Exception e) {
            logger.log(Level.WARNING, "轮询失败", e);
            Date now = new Date();
            synchronized (lock) {
                connected = false;
                lastPollAt = now;
                nextPollAt = new Date(now.getTime() + TimeUnit.MINUTES.toMillis(config.pollIntervalMinutes));
                lastError = rootMessage(e);
            }
        } finally {
            polling.set(false);
            fireChanged();
        }
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
            if (entry.getValue()[1] >= 2) dashboard.historicalAverageByFab.put(entry.getKey(), entry.getValue()[0] / entry.getValue()[1]);
        }
        List<Models.OracleTask> taskSnapshot;
        synchronized (lock) {
            taskSnapshot = new ArrayList<Models.OracleTask>(tasks);
            dashboard.dependencies = new ArrayList<Models.Dependency>(dependencies);
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
        Collections.sort(dashboard.recentRuns, (a, b) -> eventTime(b).compareTo(eventTime(a)));
        if (dashboard.recentRuns.size() > 200) dashboard.recentRuns = new ArrayList<Models.RunRecord>(dashboard.recentRuns.subList(0, 200));
        return dashboard;
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
            stat.average = acc.count >= 2 ? acc.total / acc.count : 0L;
            stat.last = acc.last.durationSeconds;
            stat.lastRun = acc.last;
            result.put(entry.getKey(), stat);
        }
        return result;
    }

    private void fireChanged() {
        List<Listener> copy;
        synchronized (listeners) { copy = new ArrayList<Listener>(listeners); }
        for (Listener listener : copy) listener.dashboardChanged();
    }

    private static Models.TaskKey keyOf(Models.TaskKey source) {
        return new Models.TaskKey(source.processDate, source.threadId, source.levelNo, source.fabId);
    }

    private static Models.TaskView viewOf(Models.OracleTask task) {
        Models.TaskView view = new Models.TaskView();
        view.processDate = task.processDate; view.threadId = task.threadId; view.levelNo = task.levelNo; view.fabId = task.fabId;
        view.status = task.status; view.actTime = copy(task.actTime); view.levelDescription = task.levelDescription; view.fabDescription = task.fabDescription;
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

    @Override public void close() { scheduler.shutdownNow(); }
}
