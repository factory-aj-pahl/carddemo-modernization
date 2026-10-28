package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.batch.JobLog;
import com.carddemo.migration.Db2Seeds;
import com.carddemo.test.Covers;
import com.carddemo.test.RepoPaths;
import com.carddemo.test.TestDatabaseConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** JOB-MNTTRDB2 (COBTUPDT) through the batch command line: the return code becomes the exit code. */
@SpringBootTest(classes = {BatchApplication.class, TestDatabaseConfig.class})
class TranTypeCommandLineTest {

    static final Path CTL = RepoPaths.root().resolve("app/app-transaction-type-db2/ctl");

    @Autowired
    BatchCommandLine cli;
    @Autowired
    JobLog log;
    @Autowired
    JdbcTemplate jdbc;
    @TempDir
    Path dir;

    @BeforeEach
    void load() {
        assertThat(cli.execute("loaddb2", "types=" + CTL.resolve(Db2Seeds.TYPES_FILE),
            "categories=" + CTL.resolve(Db2Seeds.CATEGORIES_FILE))).isZero();
        log.startCapture();
    }

    private int run(String... lines) throws IOException {
        Path f = dir.resolve("inpfile.txt");
        Files.write(f, List.of(lines));
        return cli.execute("COBTUPDT", "inputFile=" + f);
    }

    @Test
    @Covers({"TS-COBTUPDT-01", "BR-COBTUPDT-07"})
    void a_clean_run_exits_0_and_a_rejected_record_exits_4() throws IOException {
        assertThat(run("A08INTEREST")).isEqualTo(ExitCodes.OK);
        assertThat(jdbc.queryForObject("SELECT description FROM transaction_type WHERE type_cd = '08'", String.class))
            .isEqualTo("INTEREST");
        assertThat(run("D99")).isEqualTo(ExitCodes.WARNING);
        assertThat(log.captured()).contains("No records found.");
    }

    @Test
    @Covers({"TS-COBTUPDT-12", "Q-COBTUPDT-01", "Q-COBTUPDT-04"})
    void a_stopped_run_exits_12() throws IOException {
        assertThat(cli.execute("COBTUPDT", "inputFile=" + dir.resolve("missing.txt"))).isEqualTo(ExitCodes.STOPPED).isEqualTo(12);
        assertThat(log.captured()).contains("OPEN FILE NOT OK");
        assertThat(run("D01")).isEqualTo(12);
        assertThat(cli.execute("COBTUPDT")).isEqualTo(ExitCodes.USAGE);
    }
}
