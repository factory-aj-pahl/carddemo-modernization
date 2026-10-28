package com.carddemo.batch;

import com.carddemo.common.batch.JobLog;
import com.carddemo.test.DailyRecords;
import com.carddemo.test.SampleData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared setup for the cycle job tests: the base sample loaded, SYSOUT captured, a scratch directory. */
abstract class CycleJobTestSupport {

    @Autowired
    BatchCommandLine cli;
    @Autowired
    SampleData sample;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JobLog log;
    @TempDir
    Path dir;

    @BeforeEach
    void loadSample() {
        sample.load();
        clearCycleState();
        log.startCapture();
    }

    /** INTCALC and CREASTMT refuse reruns from this state, so each test starts without earlier tests' runs. */
    void clearCycleState() {
        for (String t : List.of("interest_account", "statement_run", "cycle_backup", "BATCH_STEP_EXECUTION_CONTEXT",
                "BATCH_STEP_EXECUTION", "BATCH_JOB_EXECUTION_CONTEXT", "BATCH_JOB_EXECUTION_PARAMS", "BATCH_JOB_EXECUTION",
                "BATCH_JOB_INSTANCE")) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    /** POSTTRAN of the sample daily file (262 posted, 38 rejected). */
    void postSample(String date) throws IOException {
        Path daily = dir.resolve("dailytran-" + date + ".txt");
        Files.write(daily, DailyRecords.sample(), StandardCharsets.ISO_8859_1);
        int rc = cli.execute("POSTTRAN", "businessDate=" + date, "dailyFile=" + daily, "rejectsFile=" + dir.resolve("rej-" + date + ".txt"));
        if (rc != 4) {
            throw new IllegalStateException("POSTTRAN rc " + rc);
        }
        log.startCapture();
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    List<String> lines(Path p) throws IOException {
        return Files.readAllLines(p, StandardCharsets.ISO_8859_1);
    }

    static String dots(int n) {
        return ".".repeat(n);
    }
}
