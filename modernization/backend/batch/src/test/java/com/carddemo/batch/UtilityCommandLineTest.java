package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.test.Covers;
import com.carddemo.test.SampleData;
import com.carddemo.test.TestDatabaseConfig;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** WAITSTEP (COBSWAIT) and JOB-PRTCATBL through the batch command line. */
@SpringBootTest(classes = {BatchApplication.class, TestDatabaseConfig.class, SampleData.class})
class UtilityCommandLineTest extends CycleJobTestSupport {

    final List<Long> pauses = new ArrayList<>();

    @AfterEach
    void realPause() {
        cli.pause = n -> Thread.sleep(n * 10);
    }

    private void recordPauses() {
        cli.pause = pauses::add;
    }

    @Test
    @Covers({"TS-COBSWAIT-01", "BR-COBSWAIT-01", "BR-COBSWAIT-02"})
    void the_wait_value_is_hundredths_of_a_second() {
        recordPauses();
        assertThat(cli.execute("WAITSTEP", "wait=00003600")).isEqualTo(ExitCodes.OK);
        assertThat(pauses).containsExactly(3600L);
    }

    @Test
    @Covers({"TS-COBSWAIT-02", "BR-COBSWAIT-01"})
    void a_zero_wait_ends_at_once_with_rc_0() {
        long start = System.nanoTime();
        assertThat(cli.execute("WAITSTEP", "wait=00000000")).isEqualTo(ExitCodes.OK);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1000);
    }

    @Test
    @Covers({"TS-COBSWAIT-03", "BR-COBSWAIT-01", "BR-COBSWAIT-02"})
    void a_wait_of_150_pauses_for_one_and_a_half_seconds() {
        long start = System.nanoTime();
        assertThat(cli.execute("WAITSTEP", "wait=00000150")).isEqualTo(ExitCodes.OK);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(ms).isBetween(1500L, 4000L);
    }

    @Test
    @Covers({"BR-COBSWAIT-03"})
    void the_largest_wait_is_99999999_hundredths() {
        recordPauses();
        assertThat(cli.execute("WAITSTEP", "wait=99999999")).isEqualTo(ExitCodes.OK);
        assertThat(pauses).containsExactly(99_999_999L);
        assertThat(cli.execute("WAITSTEP", "wait=100000000")).isEqualTo(ExitCodes.INVALID_WAIT);
        assertThat(pauses).hasSize(1);
    }

    @Test
    @Covers({"TS-COBSWAIT-04", "VR-COBSWAIT-01", "Q-COBSWAIT-01", "ERR-COBSWAIT-01"})
    void a_non_numeric_or_missing_wait_value_fails_with_exit_12() {
        recordPauses();
        for (String bad : List.of("wait=ABC", "wait=", "wait=-0000001", "wait=12 4")) {
            log.startCapture();
            assertThat(cli.execute("WAITSTEP", bad)).as(bad).isEqualTo(ExitCodes.INVALID_WAIT).isEqualTo(12);
            assertThat(log.captured()).containsExactly("INVALID WAIT TIME");
        }
        log.startCapture();
        assertThat(cli.execute("WAITSTEP")).isEqualTo(12);
        assertThat(log.captured()).containsExactly("INVALID WAIT TIME");
        assertThat(pauses).isEmpty();
    }

    @Test
    @Covers({"Q-PRTCATBL-02", "Q-PRTCATBL-01"})
    void the_category_balance_listing_prints_a_trailing_minus_for_credits() throws IOException {
        Map<String, Object> first = jdbc.queryForMap(
            "SELECT TOP 1 account_id, type_cd, cat_cd FROM tran_cat_balance ORDER BY account_id, type_cd, cat_cd");
        jdbc.update("UPDATE tran_cat_balance SET balance = ? WHERE account_id = ? AND type_cd = ? AND cat_cd = ?",
            new BigDecimal("-12.50"), first.get("account_id"), first.get("type_cd"), first.get("cat_cd"));
        jdbc.update("INSERT INTO tran_cat_balance (account_id, type_cd, cat_cd, balance) VALUES (99999999999, '01', 1, 1234.56)");
        Path report = dir.resolve("TCATBALF.REPT");
        assertThat(cli.execute("PRTCATBL", "reportFile=" + report)).isEqualTo(ExitCodes.OK);
        List<String> r = lines(report);
        assertThat(r).hasSize(count("SELECT COUNT(*) FROM tran_cat_balance")).allSatisfy(l -> assertThat(l).hasSize(40));
        assertThat(r.get(0)).isEqualTo(String.format("%011d %s %04d 000000012.50-%7s", first.get("account_id"),
            first.get("type_cd"), first.get("cat_cd"), ""));
        assertThat(r.get(r.size() - 1)).as("positive values are unchanged").isEqualTo("99999999999 01 0001 000001234.56" + " ".repeat(8));
        assertThat(r).isSorted();
    }

    @Test
    @Covers({"Q-PRTCATBL-02"})
    void the_balance_edit_keeps_nine_integer_digits() {
        assertThat(BatchCommandLine.catbalLine(1, "02", 5, new BigDecimal("0.00"))).isEqualTo("00000000001 02 0005 000000000.00        ");
        assertThat(BatchCommandLine.catbalLine(1, "02", 5, new BigDecimal("-999999999.99")))
            .isEqualTo("00000000001 02 0005 999999999.99-       ");
    }
}
