package com.tinyfabmonitor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Properties;

final class OracleRepository {
    private final AppConfig config;
    private final String taskSql;
    private final String upstreamDependencySql;
    private final String downstreamDependencySql;

    OracleRepository(AppConfig config) throws ClassNotFoundException {
        this.config = config;
        Class.forName("oracle.jdbc.OracleDriver");
        taskSql = "select p.prcss_dt, p.thread_id, p.lvl_no, p.fab_id, p.stat_cde, p.act_tm, " +
            "(select ldesc.descr from " + config.levelDescTable + " ldesc where ldesc.thread_id=p.thread_id and ldesc.lvl_no=p.lvl_no) Ldes, " +
            "(select fplan.descr from " + config.fabPlanTable + " fplan where fplan.fab_id=p.fab_id and fplan.thread_id=p.thread_id) fdesc " +
            "from " + config.scheduleTable + " p where p.prcss_dt=to_date(?,'yyyymmdd') " +
            "order by 1,2,3,4,5";
        // FAB/依赖字段在部分 Oracle 环境中是 CHAR。JDBC setString 绑定为 VARCHAR2，
        // 直接使用“字段=?”可能因为 CHAR 尾部补空格而无法命中，导致 DAG 只显示中心节点。
        upstreamDependencySql = "select fab_id, depn_id from " + config.dependencyTable + " where trim(fab_id)=?";
        downstreamDependencySql = "select fab_id, depn_id from " + config.dependencyTable + " where trim(depn_id)=?";
        DriverManager.setLoginTimeout(config.connectTimeoutSeconds);
    }

    Connection open() throws SQLException {
        String url = "jdbc:oracle:thin:@//" + config.host + ":" + config.port + "/" + config.serviceName;
        Properties properties = new Properties();
        properties.setProperty("user", config.username);
        properties.setProperty("password", config.password);
        properties.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(config.connectTimeoutSeconds * 1000));
        properties.setProperty("oracle.jdbc.ReadTimeout", "90000");
        return DriverManager.getConnection(url, properties);
    }

    String taskSqlForTest() { return taskSql; }
    String upstreamDependencySqlForTest() { return upstreamDependencySql; }
    String downstreamDependencySqlForTest() { return downstreamDependencySql; }

    List<Models.OracleTask> fetchTasks(Connection connection, String processDate) throws SQLException {
        List<Models.OracleTask> result = new ArrayList<Models.OracleTask>();
        PreparedStatement statement = connection.prepareStatement(taskSql);
        statement.setQueryTimeout(90);
        statement.setString(1, processDate);
        ResultSet rows = statement.executeQuery();
        try {
            while (rows.next()) {
                Models.OracleTask task = new Models.OracleTask();
                task.processDate = normalizeDate(rows.getObject(1));
                task.threadId = text(rows.getObject(2));
                task.levelNo = text(rows.getObject(3));
                task.fabId = text(rows.getObject(4));
                task.status = text(rows.getObject(5)).toUpperCase(Locale.ROOT);
                // ACT_TM 在部分环境是 CHAR，例如 2026-08-01-19.18.09.582000。
                // 不能调用 getTimestamp，否则 Oracle 驱动会先按自己的格式转换并直接报错。
                Object actTimeValue = rows.getObject(6);
                task.actTimePlaceholder = DateCompatibility.isPlaceholder(actTimeValue);
                task.actTime = parseActTime(actTimeValue);
                task.levelDescription = text(rows.getObject(7));
                task.fabDescription = text(rows.getObject(8));
                result.add(task);
            }
        } finally {
            rows.close();
            statement.close();
        }
        return result;
    }

    Models.DependencyAnalysis fetchDependencyAnalysis(Connection connection, String rootFabId, List<Models.OracleTask> currentDateTasks,
                                                       int upstreamDepth, int downstreamDepth) throws SQLException {
        return DependencySearch.load(rootFabId, currentDateTasks, upstreamDepth, downstreamDepth, new CachedBatchLookup(connection));
    }

    private class CachedBatchLookup implements DependencySearch.BatchLookup {
        private static final int ORACLE_BATCH_SIZE = 500;
        private final Connection connection;
        private final Map<String, List<Models.Dependency>> upstreamCache = new LinkedHashMap<String, List<Models.Dependency>>();
        private final Map<String, List<Models.Dependency>> downstreamCache = new LinkedHashMap<String, List<Models.Dependency>>();

        CachedBatchLookup(Connection connection) { this.connection = connection; }

        public List<Models.Dependency> upstream(Set<String> fabIds) throws SQLException {
            return cached(fabIds, true, upstreamCache);
        }

        public List<Models.Dependency> downstream(Set<String> dependencyIds) throws SQLException {
            return cached(dependencyIds, false, downstreamCache);
        }

        private List<Models.Dependency> cached(Collection<String> requested, boolean upstream,
                                               Map<String, List<Models.Dependency>> cache) throws SQLException {
            List<String> missing = new ArrayList<String>();
            for (String value : requested) {
                String key = normalizeFab(value);
                if (!key.isEmpty() && !cache.containsKey(key)) { cache.put(key, new ArrayList<Models.Dependency>()); missing.add(value.trim()); }
            }
            for (int offset = 0; offset < missing.size(); offset += ORACLE_BATCH_SIZE) {
                int end = Math.min(missing.size(), offset + ORACLE_BATCH_SIZE);
                loadBatch(missing.subList(offset, end), upstream, cache);
            }
            Map<String, Models.Dependency> unique = new LinkedHashMap<String, Models.Dependency>();
            for (String value : requested) {
                List<Models.Dependency> values = cache.get(normalizeFab(value));
                if (values == null) continue;
                for (Models.Dependency edge : values) unique.put(normalizeFab(edge.dependencyId) + "->" + normalizeFab(edge.fabId), edge);
            }
            return new ArrayList<Models.Dependency>(unique.values());
        }

        private void loadBatch(List<String> values, boolean upstream, Map<String, List<Models.Dependency>> cache) throws SQLException {
            if (values.isEmpty()) return;
            String column = upstream ? "fab_id" : "depn_id";
            StringBuilder sql = new StringBuilder("select fab_id, depn_id from ").append(config.dependencyTable)
                .append(" where trim(").append(column).append(")");
            if (values.size() == 1) sql.append("=?");
            else {
                sql.append(" in (");
                for (int i = 0; i < values.size(); i++) { if (i > 0) sql.append(','); sql.append('?'); }
                sql.append(')');
            }
            PreparedStatement statement = connection.prepareStatement(sql.toString());
            statement.setQueryTimeout(90);
            for (int i = 0; i < values.size(); i++) statement.setString(i + 1, values.get(i));
            ResultSet rows = statement.executeQuery();
            try {
                while (rows.next()) {
                    String owner = text(rows.getObject(1)), dependency = text(rows.getObject(2));
                    if (owner.isEmpty() || dependency.isEmpty()) continue;
                    Models.Dependency edge = new Models.Dependency(owner, dependency);
                    String key = normalizeFab(upstream ? owner : dependency);
                    List<Models.Dependency> bucket = cache.get(key);
                    if (bucket != null) bucket.add(edge);
                }
            } finally { rows.close(); statement.close(); }
        }
    }

    private static String normalizeFab(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    static Date parseActTime(Object value) throws SQLException {
        return DateCompatibility.parseActTime(value);
    }

    private static String normalizeDate(Object value) {
        if (value instanceof Date) return new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format((Date) value);
        String raw = text(value);
        return raw.length() >= 10 && raw.charAt(4) == '-' ? raw.substring(0, 4) + raw.substring(5, 7) + raw.substring(8, 10) : raw;
    }
}
