package com.tinyfabmonitor;

import org.junit.Test;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OracleRepositoryTest {
    @Test public void parsesOracleCharActTimeWithSixFractionDigits() throws Exception {
        Date value = OracleRepository.parseActTime("2026-08-01-19.18.09.582000   ");
        assertEquals("2026-08-01 19:18:09.582", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(value));
    }

    @Test public void treatsYearOneActTimeAsDatabasePlaceholder() throws Exception {
        assertTrue(DateCompatibility.isPlaceholder("0001-01-01-00.00.00.000000"));
        assertNull(OracleRepository.parseActTime("0001-01-01-00.00.00.000000"));
    }

    @Test public void parsesCharActTimeWithoutFractionAndWithStandardSeparators() throws Exception {
        Date value = OracleRepository.parseActTime("2026-08-01 19:18:09");
        assertEquals("2026-08-01 19:18:09.000", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(value));
    }

    @Test public void keepsRealTimestampValuesCompatible() throws Exception {
        Timestamp input = Timestamp.valueOf("2026-08-01 19:18:09.582");
        assertEquals(input.getTime(), OracleRepository.parseActTime(input).getTime());
    }

    @Test public void taskQueryFiltersOnlyByProcessDateNotLevelRange() throws Exception {
        Path directory = Files.createTempDirectory("tiny-fab-config");
        Path configFile = directory.resolve("config.properties");
        String config = "oracle.host=db.example\n" +
            "oracle.service_name=ORCL\n" +
            "oracle.username=user\n" +
            "oracle.password=password\n" +
            "tables.schedule=TEST_SCHEDULE_TABLE\n" +
            "tables.level_desc=TEST_LEVEL_DESCRIPTION_TABLE\n" +
            "tables.fab_plan=TEST_FAB_PLAN_TABLE\n" +
            "tables.fab_dependency=TEST_FAB_DEPENDENCY_TABLE\n" +
            "monitor.process_date=20251231\n" +
            "monitor.level_min=obsolete-value-is-ignored\n" +
            "monitor.level_max=also-ignored\n";
        Files.write(configFile, config.getBytes(StandardCharsets.UTF_8));
        AppConfig appConfig = AppConfig.load(configFile, directory);
        assertEquals(5, appConfig.dagUpstreamLevels);
        assertEquals(5, appConfig.dagDownstreamLevels);
        assertEquals(5, appConfig.pollIntervalMinMinutes);
        assertEquals(5, appConfig.pollIntervalMaxMinutes);
        String sql = new OracleRepository(appConfig).taskSqlForTest().toLowerCase(Locale.ROOT);
        org.junit.Assert.assertTrue(sql.contains("p.prcss_dt=to_date(?,'yyyymmdd')"));
        org.junit.Assert.assertFalse(sql.contains("lvl_no between"));
        org.junit.Assert.assertFalse(sql.contains("select ldesc.descr"));
        org.junit.Assert.assertFalse(sql.contains("select fplan.descr"));
    }

    @Test public void completedDateQueryUsesOnlyTheConfiguredEndTaskAndRowLimit() throws Exception {
        Path directory = Files.createTempDirectory("tiny-fab-analysis-config");
        Path configFile = directory.resolve("config.properties");
        String config = "oracle.host=db.example\n" + "oracle.service_name=ORCL\n" + "oracle.username=user\n" + "oracle.password=password\n" +
            "tables.schedule=TEST_SCHEDULE_TABLE\n" + "tables.level_desc=TEST_LEVEL_DESCRIPTION_TABLE\n" +
            "tables.fab_plan=TEST_FAB_PLAN_TABLE\n" + "tables.fab_dependency=TEST_FAB_DEPENDENCY_TABLE\n" +
            "monitor.process_date=20251231\n" + "monitor.poll_interval_min_minutes=4\n" + "monitor.poll_interval_max_minutes=6\n";
        Files.write(configFile, config.getBytes(StandardCharsets.UTF_8));
        AppConfig appConfig = AppConfig.load(configFile, directory);
        assertEquals(4, appConfig.pollIntervalMinMinutes); assertEquals(6, appConfig.pollIntervalMaxMinutes);
        OracleRepository repository = new OracleRepository(appConfig);
        String sql = repository.endCompletedDatesSqlForTest().toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("trim(p.thread_id)=?")); assertTrue(sql.contains("trim(p.fab_id)=?"));
        assertTrue(sql.contains("upper(trim(p.stat_cde))='r'")); assertTrue(sql.contains("rownum<=?"));
        assertEquals("select fab_id, depn_id from test_fab_dependency_table", repository.allDependenciesSqlForTest().toLowerCase(Locale.ROOT));
        assertEquals("select thread_id, lvl_no, descr from test_level_description_table", repository.allLevelDescriptionsSqlForTest().toLowerCase(Locale.ROOT));
        assertEquals("select thread_id, fab_id, descr from test_fab_plan_table", repository.allFabDescriptionsSqlForTest().toLowerCase(Locale.ROOT));
    }

    @Test public void dependencyQueriesTrimOracleCharColumnsBeforeMatching() throws Exception {
        Path directory = Files.createTempDirectory("tiny-fab-dependency-config");
        Path configFile = directory.resolve("config.properties");
        String config = "oracle.host=db.example\n" +
            "oracle.service_name=ORCL\n" +
            "oracle.username=user\n" +
            "oracle.password=password\n" +
            "tables.schedule=TEST_SCHEDULE_TABLE\n" +
            "tables.level_desc=TEST_LEVEL_DESCRIPTION_TABLE\n" +
            "tables.fab_plan=TEST_FAB_PLAN_TABLE\n" +
            "tables.fab_dependency=TEST_FAB_DEPENDENCY_TABLE\n" +
            "monitor.process_date=20251231\n";
        Files.write(configFile, config.getBytes(StandardCharsets.UTF_8));
        OracleRepository repository = new OracleRepository(AppConfig.load(configFile, directory));
        assertTrue(repository.upstreamDependencySqlForTest().toLowerCase(Locale.ROOT).contains("where trim(fab_id)=?"));
        assertTrue(repository.downstreamDependencySqlForTest().toLowerCase(Locale.ROOT).contains("where trim(depn_id)=?"));
    }

    @Test public void analysisBoundaryDefaultsMustBeCompleteTriplesWhenConfigured() throws Exception {
        Path directory = Files.createTempDirectory("tiny-fab-boundary-config");
        Path configFile = directory.resolve("config.properties");
        String config = "oracle.host=db.example\n" + "oracle.service_name=ORCL\n" + "oracle.username=user\n" + "oracle.password=password\n" +
            "tables.schedule=SCHEDULE_TABLE\n" + "tables.level_desc=LEVEL_TABLE\n" + "tables.fab_plan=FAB_TABLE\n" +
            "tables.fab_dependency=DEPENDENCY_TABLE\n" + "monitor.process_date=20251231\n" +
            "monitor.analysis_start_thread_id=T\n" + "monitor.analysis_start_fab_id=FAB-A\n";
        Files.write(configFile, config.getBytes(StandardCharsets.UTF_8));
        boolean failed = false;
        try { AppConfig.load(configFile, directory); }
        catch (IllegalArgumentException expected) { failed = expected.getMessage().contains("必须同时配置"); }
        assertTrue(failed);
    }
}
