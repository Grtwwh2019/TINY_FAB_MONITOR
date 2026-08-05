package com.tinyfabmonitor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ViewLogic {
    private ViewLogic() {}

    static List<Models.TaskView> findDagTasks(List<Models.TaskView> tasks, String thread, String level, String fab) {
        String wantedThread = normalized(thread), wantedLevel = normalized(level), wantedFab = normalized(fab);
        List<Models.TaskView> result = new ArrayList<Models.TaskView>();
        for (Models.TaskView task : tasks) {
            if (!wantedThread.isEmpty() && !normalized(task.threadId).contains(wantedThread)) continue;
            if (!wantedLevel.isEmpty() && !normalized(task.levelNo).contains(wantedLevel)) continue;
            if (!wantedFab.isEmpty() && !normalized(task.fabId).contains(wantedFab)) continue;
            result.add(task);
        }
        return result;
    }

    static Set<String> collectFabIds(Models.Dashboard dashboard) {
        Set<String> ids = new HashSet<String>();
        for (Models.TaskView task : dashboard.tasks) if (task.fabId != null && !task.fabId.isEmpty()) ids.add(task.fabId);
        for (Models.Dependency dependency : dashboard.dependencies) {
            if (dependency.fabId != null && !dependency.fabId.isEmpty()) ids.add(dependency.fabId);
            if (dependency.dependencyId != null && !dependency.dependencyId.isEmpty()) ids.add(dependency.dependencyId);
        }
        return ids;
    }

    static Integer parseLevelBound(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return null;
        try { return Integer.valueOf(text); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Level No 必须是整数：" + text, e); }
    }

    static boolean levelInRange(String value, Integer minimum, Integer maximum) {
        if (minimum == null && maximum == null) return true;
        if (value == null) return false;
        final int level;
        try { level = Integer.parseInt(value.trim()); }
        catch (NumberFormatException e) { return false; }
        return (minimum == null || level >= minimum) && (maximum == null || level <= maximum);
    }

    static boolean threadContains(String threadId, String filter) {
        String wanted = normalized(filter);
        return wanted.isEmpty() || normalized(threadId).contains(wanted);
    }

    private static String normalized(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
}
