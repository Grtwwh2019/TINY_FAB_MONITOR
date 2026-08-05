package com.tinyfabmonitor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
        upstreamDependencySql = "select fab_id, depn_id from " + config.dependencyTable + " where fab_id=?";
        downstreamDependencySql = "select fab_id, depn_id from " + config.dependencyTable + " where depn_id=?";
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

    List<Models.Dependency> fetchDependencyDag(Connection connection, String rootFabId, Set<String> currentDateFabIds, int maximumDepth) throws SQLException {
        final PreparedStatement upstream = connection.prepareStatement(upstreamDependencySql);
        final PreparedStatement downstream = connection.prepareStatement(downstreamDependencySql);
        upstream.setQueryTimeout(90);
        downstream.setQueryTimeout(90);
        try {
            return DependencyGraphBuilder.build(rootFabId, currentDateFabIds, maximumDepth, new DependencyGraphBuilder.Lookup() {
                public List<Models.Dependency> upstream(String fabId) throws SQLException { return readDependencies(upstream, fabId); }
                public List<Models.Dependency> downstream(String dependencyId) throws SQLException { return readDependencies(downstream, dependencyId); }
            });
        } finally {
            upstream.close();
            downstream.close();
        }
    }

    private static List<Models.Dependency> readDependencies(PreparedStatement statement, String value) throws SQLException {
        List<Models.Dependency> result = new ArrayList<Models.Dependency>();
        statement.setString(1, value);
        ResultSet rows = statement.executeQuery();
        try {
            while (rows.next()) {
                String owner = text(rows.getObject(1));
                String dependency = text(rows.getObject(2));
                if (!owner.isEmpty() && !dependency.isEmpty()) result.add(new Models.Dependency(owner, dependency));
            }
        } finally { rows.close(); }
        return result;
    }

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
