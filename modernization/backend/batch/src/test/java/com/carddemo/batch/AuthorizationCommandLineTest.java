package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.batch.JobLog;
import com.carddemo.test.Covers;
import com.carddemo.test.RepoPaths;
import com.carddemo.test.TestDatabaseConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** The BC-10 authorization jobs through the batch command line (CBPAUP0J, UNLDPADB, LOADPADB, UNLDGSAM). */
@SpringBootTest(classes = {BatchApplication.class, TestDatabaseConfig.class})
class AuthorizationCommandLineTest {

    static final String IMS = "app/app-authorization-ims-db2-mq/data/EBCDIC/AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat";

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
        cli.execute("reset");
        assertThat(cli.execute("load", RepoPaths.asciiData().toString(), "ims=" + RepoPaths.root().resolve(IMS))).isZero();
        log.startCapture();
    }

    int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Test
    @Covers({"TS-CBPAUP0C-01", "Q-CA7-04", "Q-CBPAUP0J-01"})
    void purge_command_runs_cbpaup0c_with_the_sysin_card() {
        assertThat(cli.execute("CBPAUP0C", "sysin=00,00005,00010,N")).isEqualTo(ExitCodes.OK);
        assertThat(log.captured()).startsWith("STARTING PROGRAM CBPAUP0C::", "*-------------------------------------*",
            "CBPAUP0C PARM RECEIVED :00,00005,00010,N ");
        assertThat(log.captured()).contains("# TOTAL SUMMARY READ  :+00000021", "# SUMMARY REC DELETED :+00000021",
            "# TOTAL DETAILS READ  :+00000202", "# DETAILS REC DELETED :+00000202");
        assertThat(count("pending_auth_detail")).isZero();
    }

    @Test
    @Covers({"TS-PAUDBUNL-01", "TS-PAUDBLOD-01", "TS-DBUNLDGS-01"})
    void unload_and_load_commands_round_trip_the_tables() throws IOException {
        Path root = dir.resolve("PAUTDB.ROOT.FILEO");
        Path child = dir.resolve("PAUTDB.CHILD.FILEO");
        assertThat(cli.execute("PAUDBUNL", "rootFile=" + root, "childFile=" + child)).isEqualTo(ExitCodes.OK);
        assertThat(Files.size(root)).isEqualTo(21L * 100);
        assertThat(Files.size(child)).isEqualTo(202L * 206);
        assertThat(cli.execute("DBUNLDGS", "rootFile=" + dir.resolve("g.root"), "childFile=" + dir.resolve("g.child")))
            .isEqualTo(ExitCodes.OK);
        assertThat(Files.readAllBytes(dir.resolve("g.child"))).isEqualTo(Files.readAllBytes(child));
        jdbc.update("DELETE FROM pending_auth_detail");
        jdbc.update("DELETE FROM pending_auth_summary");
        assertThat(cli.execute("PAUDBLOD", "rootFile=" + root, "childFile=" + child)).isEqualTo(ExitCodes.OK);
        assertThat(count("pending_auth_summary")).isEqualTo(21);
        assertThat(count("pending_auth_detail")).isEqualTo(202);
    }

    @Test
    @Covers({"ERR-PAUDBLOD-01"})
    void failed_runs_and_missing_parameters_exit_16() {
        assertThat(cli.execute("PAUDBLOD", "rootFile=" + dir.resolve("absent"), "childFile=" + dir.resolve("absent2")))
            .isEqualTo(ExitCodes.PROGRAM_RC16);
        assertThat(log.captured()).endsWith("ERROR IN OPENING INFILE1:35", "IMS LOAD ABENDING ...");
        assertThat(cli.execute("PAUDBUNL", "rootFile=" + dir.resolve("r"))).isEqualTo(ExitCodes.USAGE);
        assertThat(cli.execute("DBUNLDGS")).isEqualTo(ExitCodes.USAGE);
    }
}
