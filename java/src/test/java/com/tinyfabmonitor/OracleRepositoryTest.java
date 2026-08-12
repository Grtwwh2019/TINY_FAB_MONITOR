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
    }

    @Test public void completedDateQueryUsesOneGroupedReadAndRowLimit() throws Exception {
        Path directory = Files.createTempDirectory("tiny-fab-analysis-config");
        Path configFile = directory.resolve("config.properties");
        String config = "oracle.host=db.example\n" + "oracle.service_name=ORCL\n" + "oracle.username=user\n" + "oracle.password=password\n" +
            "tables.schedule=TEST_SCHEDULE_TABLE\n" + "tables.level_desc=TEST_LEVEL_DESCRIPTION_TABLE\n" +
            "tables.fab_plan=TEST_FAB_PLAN_TABLE\n" + "tables.fab_dependency=TEST_FAB_DEPENDENCY_TABLE\n" +
            "monitor.process_date=20251231\n" + "monitor.poll_interval_min_minutes=4\n" + "monitor.poll_interval_max_minutes=6\n";
        Files.write(configFile, config.getBytes(StandardCharsets.UTF_8));
        AppConfig appConfig = AppConfig.load(configFile, directory);
        assertEquals(4, appConfig.pollIntervalMinMinutes); assertEquals(6, appConfig.pollIntervalMaxMinutes);
        String sql = new OracleRepository(appConfig).completedDatesSqlForTest().toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("group by p.prcss_dt")); assertTrue(sql.contains("having sum(case")); assertTrue(sql.contains("rownum<=?"));
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
}
