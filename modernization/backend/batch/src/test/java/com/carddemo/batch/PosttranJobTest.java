package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.batch.JobLog;
import com.carddemo.test.Covers;
import com.carddemo.test.DailyRecords;
import com.carddemo.test.SampleData;
import com.carddemo.test.TestDatabaseConfig;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** JOB-POSTTRAN end to end through the batch command line (spec/programs/CBTRN02C.md TS- scenarios). */
@SpringBootTest(classes = {BatchApplication.class, TestDatabaseConfig.class, SampleData.class})
class PosttranJobTest {

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
    void load() {
        sample.load();
        log.startCapture();
    }

    int posttran(Path daily, String date) {
        return cli.execute("POSTTRAN", "businessDate=" + date, "dailyFile=" + daily, "rejectsFile=" + dir.resolve("dalyrejs.txt"));
    }

    Path daily(List<String> records) throws IOException {
        Path f = dir.resolve("dailytran.txt");
        Files.write(f, records, StandardCharsets.ISO_8859_1);
        return f;
    }

    int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    List<String> rejects() throws IOException {
        return Files.readAllLines(dir.resolve("dalyrejs.txt"), StandardCharsets.ISO_8859_1);
    }

    @Test
    @Covers({"TS-CBTRN02C-01", "TS-CBTRN02C-02", "BR-CBTRN02C-01", "BR-CBTRN02C-10", "Q-CBTRN02C-08", "Q-CBTRN02C-10", "Q-CBTRN02C-01",
        "TS-POSTTRAN-01"})
    void sample_file_posts_262_and_rejects_38_with_return_code_4() throws IOException {
        int rc = posttran(daily(DailyRecords.sample()), "2022-06-10");
        assertThat(rc).isEqualTo(4);
        List<String> out = log.captured();
        assertThat(out.get(0)).isEqualTo("START OF EXECUTION OF PROGRAM CBTRN02C");
        assertThat(out.subList(out.size() - 3, out.size())).containsExactly("TRANSACTIONS PROCESSED :000000300",
            "TRANSACTIONS REJECTED  :000000038", "END OF EXECUTION OF PROGRAM CBTRN02C");
        assertThat(out.stream().filter(l -> l.endsWith(".. Creating.")).count()).isEqualTo(50);
        assertThat(out).hasSize(54);
        assertThat(count("card_transaction")).isEqualTo(262);
        assertThat(count("tran_cat_balance")).isEqualTo(100);
        assertThat(rejects()).hasSize(38).allSatisfy(r -> {
            assertThat(r).hasSize(430);
            assertThat(r.substring(350)).isEqualTo("0102OVERLIMIT TRANSACTION" + " ".repeat(55));
        });
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("dalyrejs.txt")))).isEqualTo("rw-------");
        Map<String, Object> a7 = jdbc.queryForMap("SELECT current_balance, curr_cycle_credit, curr_cycle_debit FROM account WHERE account_id = 7");
        assertThat((BigDecimal) a7.get("current_balance")).isEqualByComparingTo("900.21");
        assertThat((BigDecimal) a7.get("curr_cycle_credit")).isEqualByComparingTo("1287.09");
        assertThat((BigDecimal) a7.get("curr_cycle_debit")).isEqualByComparingTo("-579.88");
        assertThat(jdbc.queryForObject("SELECT balance FROM tran_cat_balance WHERE account_id = 7 AND type_cd = '01' AND cat_cd = 1",
            BigDecimal.class)).isEqualByComparingTo("1287.09");
        assertThat(jdbc.queryForObject("SELECT balance FROM tran_cat_balance WHERE account_id = 7 AND type_cd = '03' AND cat_cd = 1",
            BigDecimal.class)).isEqualByComparingTo("-579.88");
        assertThat(out).contains("TCATBAL record not found for key : 00000000007030001.. Creating.");
    }

    @Test
    @Covers({"TS-POSTTRAN-02", "Q-CBTRN02C-01"})
    void posting_into_a_non_empty_transaction_table_appends_and_keeps_the_earlier_rows() throws IOException {
        assertThat(posttran(daily(DailyRecords.sample()), "2022-06-10")).isEqualTo(4);
        String next = DailyRecords.withAmount(DailyRecords.withTranId(DailyRecords.sample("0000000000683580"), "9000000000000001"),
            "1.00");
        assertThat(posttran(daily(List.of(next)), "2022-06-11")).isZero();
        assertThat(count("card_transaction")).isEqualTo(263);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_transaction WHERE tran_id IN ('0000000000683580', '9000000000000001')",
            Integer.class)).isEqualTo(2);
    }

    @Test
    @Covers({"TS-CBTRN02C-08"})
    void empty_daily_file_ends_with_zero_counts_and_return_code_0() throws IOException {
        assertThat(posttran(daily(List.of()), "2022-06-10")).isZero();
        assertThat(log.captured()).containsExactly("START OF EXECUTION OF PROGRAM CBTRN02C", "TRANSACTIONS PROCESSED :000000000",
            "TRANSACTIONS REJECTED  :000000000", "END OF EXECUTION OF PROGRAM CBTRN02C");
        assertThat(rejects()).isEmpty();
    }

    @Test
    @Covers({"TS-CBTRN02C-09", "ERR-CBTRN02C-06", "Q-CBTRN02C-07", "B-UOW", "B-ABEND"})
    void duplicate_id_abends_after_committing_earlier_records_and_a_restart_resumes() throws IOException {
        List<String> recs = new ArrayList<>(DailyRecords.sample().subList(0, 5));
        String first = recs.get(0);
        recs.set(2, first);
        Path f = daily(recs);
        BigDecimal balanceBefore = jdbc.queryForObject("SELECT SUM(current_balance) FROM account", BigDecimal.class);

        assertThat(posttran(f, "2022-06-10")).isEqualTo(99);
        List<String> out = log.captured();
        assertThat(out.subList(out.size() - 3, out.size()))
            .containsExactly("ERROR WRITING TO TRANSACTION FILE", "FILE STATUS IS: NNNN0022", "ABENDING PROGRAM");
        assertThat(out).noneMatch(l -> l.startsWith("TRANSACTIONS"));
        assertThat(count("card_transaction")).isGreaterThanOrEqualTo(1);
        assertThat(count("card_transaction") + count("posting_reject")).isEqualTo(2);
        assertThat(Files.exists(dir.resolve("dalyrejs.txt"))).isFalse();
        BigDecimal posted = jdbc.queryForObject("SELECT SUM(amount) FROM card_transaction", BigDecimal.class);
        assertThat(jdbc.queryForObject("SELECT SUM(current_balance) FROM account", BigDecimal.class))
            .isEqualByComparingTo(balanceBefore.add(posted));

        recs.set(2, DailyRecords.sample().get(2));
        daily(recs);
        log.startCapture();
        int rc = posttran(f, "2022-06-10");
        assertThat(rc).isIn(0, 4);
        assertThat(log.captured()).contains("TRANSACTIONS PROCESSED :000000005");
        assertThat(count("card_transaction") + count("posting_reject")).isEqualTo(5);
        BigDecimal allPosted = jdbc.queryForObject("SELECT SUM(amount) FROM card_transaction", BigDecimal.class);
        assertThat(jdbc.queryForObject("SELECT SUM(current_balance) FROM account", BigDecimal.class))
            .isEqualByComparingTo(balanceBefore.add(allPosted));

        log.startCapture();
        assertThat(posttran(f, "2022-06-10")).isEqualTo(8);
        assertThat(log.captured()).containsExactly("POSTING ALREADY COMPLETED FOR 2022-06-10");
    }

    @Test
    @Covers({"ERR-CBTRN02C-01", "B-ABEND"})
    void missing_daily_file_abends_before_reading() {
        assertThat(posttran(dir.resolve("missing.txt"), "2022-06-10")).isEqualTo(99);
        assertThat(log.captured()).containsExactly("START OF EXECUTION OF PROGRAM CBTRN02C", "ERROR OPENING DALYTRAN",
            "FILE STATUS IS: NNNN0035", "ABENDING PROGRAM");
        assertThat(count("card_transaction")).isZero();
    }

    @Test
    @Covers({"B-DATE"})
    void missing_or_invalid_business_date_stops_with_exit_12() throws IOException {
        Path f = daily(DailyRecords.sample().subList(0, 1));
        assertThat(cli.execute("POSTTRAN", "dailyFile=" + f, "rejectsFile=" + dir.resolve("r.txt"))).isEqualTo(12);
        assertThat(posttran(f, "2022-02-30")).isEqualTo(12);
        assertThat(posttran(f, "20220610")).isEqualTo(12);
        assertThat(log.captured()).containsExactly("INVALID OR MISSING BUSINESS DATE", "INVALID OR MISSING BUSINESS DATE",
            "INVALID OR MISSING BUSINESS DATE");
        assertThat(count("card_transaction")).isZero();
    }
}
