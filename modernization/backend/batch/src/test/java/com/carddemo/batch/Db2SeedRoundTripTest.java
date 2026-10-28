package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Db2 module seed files (JOB-CREADB21: DB2LTTYP.ctl, DB2LTCAT.ctl) load into the shared transaction type and
 * category tables and export back as the identical SQL text (verification/data/owed-files.md).
 */
@SpringBootTest(classes = {BatchApplication.class, TestDatabaseConfig.class})
class Db2SeedRoundTripTest {

    @Autowired
    BatchCommandLine cli;
    @Autowired
    JdbcTemplate jdbc;
    @TempDir
    Path dir;

    static Path ctl(String name) {
        return RepoPaths.root().resolve("app/app-transaction-type-db2/ctl").resolve(name);
    }

    @BeforeEach
    void reset() {
        cli.execute("reset");
    }

    @Test
    @Covers({"JOB-CREADB21", "DF-1"})
    void db2_seed_files_load_and_export_back_byte_for_byte() throws IOException {
        assertThat(cli.execute("loaddb2", "types=" + ctl("DB2LTTYP.ctl"), "categories=" + ctl("DB2LTCAT.ctl"))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_type", Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_category", Integer.class)).isEqualTo(18);
        assertThat(jdbc.queryForObject("SELECT description FROM transaction_type WHERE type_cd = '06'", String.class))
            .isEqualTo("REVERAL");
        assertThat(jdbc.queryForObject("SELECT description FROM transaction_category WHERE type_cd = '04' AND cat_cd = 2",
            String.class)).isEqualTo("ONLINE PURCHASE AUTHORIZATION");
        Path out = dir.resolve("out");
        assertThat(cli.execute("export", out.toString(), "files=DB2LTTYP.ctl,DB2LTCAT.ctl")).isZero();
        assertThat(Files.readAllBytes(out.resolve("DB2LTTYP.ctl"))).isEqualTo(Files.readAllBytes(ctl("DB2LTTYP.ctl")));
        assertThat(Files.readAllBytes(out.resolve("DB2LTCAT.ctl"))).isEqualTo(Files.readAllBytes(ctl("DB2LTCAT.ctl")));
    }

    @Test
    @Covers({"ENT-DB2-TRAN-TYPE-CATEGORY"})
    void a_category_needs_its_type_and_a_type_with_categories_cannot_be_deleted() throws IOException {
        assertThat(cli.execute("loaddb2", "types=" + ctl("DB2LTTYP.ctl"), "categories=" + ctl("DB2LTCAT.ctl"))).isZero();
        assertThatThrownBy(() -> jdbc.update("DELETE FROM transaction_type WHERE type_cd = '07'"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() ->
                jdbc.update("INSERT INTO transaction_category (type_cd, cat_cd, description) VALUES ('99', 1, 'X')"))
            .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO transaction_type (type_cd, description) VALUES ('08', 'TEST A')");
        assertThat(jdbc.update("DELETE FROM transaction_type WHERE type_cd = '08'")).isEqualTo(1);
    }

    @Test
    void loaddb2_without_files_is_a_usage_error() {
        assertThat(cli.execute("loaddb2")).isEqualTo(ExitCodes.USAGE);
    }
}
