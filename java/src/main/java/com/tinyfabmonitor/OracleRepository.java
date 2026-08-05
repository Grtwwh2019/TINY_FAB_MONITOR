package com.tinyfabmonitor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.Properties;

final class OracleRepository {
    private final AppConfig config;
    private final String taskSql;
    private final String dependencySql;

    OracleRepository(AppConfig config) throws ClassNotFoundException {
        this.config = config;
        Class.forName("oracle.jdbc.OracleDriver");
        taskSql = "select p.prcss_dt, p.thread_id, p.lvl_no, p.fab_id, p.stat_cde, p.act_tm, " +
            "(select ldesc.descr from " + config.levelDescTable + " ldesc where ldesc.thread_id=p.thread_id and ldesc.lvl_no=p.lvl_no) Ldes, " +
            "(select fplan.descr from " + config.fabPlanTable + " fplan where fplan.fab_id=p.fab_id and fplan.thread_id=p.thread_id) fdesc " +
            "from " + config.scheduleTable + " p where p.prcss_dt=to_date(?,'yyyymmdd') " +
            "and p.lvl_no between ? and ? order by 1,2,3,4,5";
        dependencySql = "select fab_id, depn_id from " + config.dependencyTable + " where fab_id=?";
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

    List<Models.OracleTask> fetchTasks(Connection connection, String processDate) throws SQLException {
        List<Models.OracleTask> result = new ArrayList<Models.OracleTask>();
        PreparedStatement statement = connection.prepareStatement(taskSql);
        statement.setQueryTimeout(90);
        statement.setString(1, processDate);
        statement.setInt(2, config.levelMin);
        statement.setInt(3, config.levelMax);
        ResultSet rows = statement.executeQuery();
        try {
            while (rows.next()) {
                Models.OracleTask task = new Models.OracleTask();
                task.processDate = normalizeDate(rows.getObject(1));
                task.threadId = text(rows.getObject(2));
                task.levelNo = text(rows.getObject(3));
                task.fabId = text(rows.getObject(4));
                task.status = text(rows.getObject(5)).toUpperCase(Locale.ROOT);
                Timestamp timestamp = rows.getTimestamp(6);
                task.actTime = timestamp == null ? null : new Date(timestamp.getTime());
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

    List<Models.Dependency> fetchDependencyGraph(Connection connection, List<String> rootIds) throws SQLException {
        List<Models.Dependency> result = new ArrayList<Models.Dependency>();
        Queue<String> queue = new ArrayDeque<String>();
        Set<String> queued = new HashSet<String>();
        Set<String> seen = new HashSet<String>();
        for (String id : rootIds) if (id != null && !id.isEmpty() && queued.add(id)) queue.add(id);
        PreparedStatement statement = connection.prepareStatement(dependencySql);
        statement.setQueryTimeout(90);
        try {
            while (!queue.isEmpty()) {
                String fabId = queue.remove();
                if (!seen.add(fabId)) continue;
                statement.setString(1, fabId);
                ResultSet rows = statement.executeQuery();
                try {
                    while (rows.next()) {
                        String owner = text(rows.getObject(1));
                        String dependency = text(rows.getObject(2));
                        if (owner.isEmpty() || dependency.isEmpty()) continue;
                        result.add(new Models.Dependency(owner, dependency));
                        if (!seen.contains(dependency) && queued.add(dependency)) queue.add(dependency);
                    }
                } finally { rows.close(); }
            }
        } finally { statement.close(); }
        return result;
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    private static String normalizeDate(Object value) {
        if (value instanceof Date) return new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format((Date) value);
        String raw = text(value);
        return raw.length() >= 10 && raw.charAt(4) == '-' ? raw.substring(0, 4) + raw.substring(5, 7) + raw.substring(8, 10) : raw;
    }
}
