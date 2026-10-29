package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.test.Covers;
import com.carddemo.test.DailyRecords;
import com.carddemo.test.SampleData;
import com.carddemo.test.TestDatabaseConfig;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** BACKUP and RESTORE around a cycle (B-CYCLE, Q-TRANBKP-01, Q-ACCTFILE-02). */
@SpringBootTest(classes = {BatchApplication.class, TestDatabaseConfig.class, SampleData.class})
class CycleBackupTest extends CycleJobTestSupport {

    List<Map<String, Object>> state() {
        return List.of(
            Map.of("accounts", jdbc.queryForList("SELECT account_id, current_balance, curr_cycle_credit, curr_cycle_debit FROM account"
                + " ORDER BY account_id")),
            Map.of("transactions", jdbc.queryForList("SELECT tran_id, statement_run_id FROM card_transaction ORDER BY tran_id")),
            Map.of("categories", jdbc.queryForList("SELECT * FROM tran_cat_balance ORDER BY account_id, type_cd, cat_cd")),
            Map.of("rejects", jdbc.queryForList("SELECT reject_id FROM posting_reject ORDER BY reject_id")),
            Map.of("runs", jdbc.queryForList("SELECT statement_run_id FROM statement_run ORDER BY statement_run_id")),
            Map.of("interest", jdbc.queryForList("SELECT interest_month, account_id FROM interest_account ORDER BY account_id")));
    }

    @Test
    @Covers({"Q-ACCTFILE-02", "Q-TRANBKP-01", "B-CYCLE"})
    void restore_puts_back_the_state_before_the_cycle_and_the_cycle_can_rerun() throws IOException {
        postSample("2022-06-30");
        assertThat(cli.execute("CREASTMT", "businessDate=2022-06-30", "stmtFile=" + dir.resolve("s0"), "htmlFile=" + dir.resolve("h0")))
            .isZero();
        List<Map<String, Object>> before = state();
        assertThat(cli.execute("BACKUP", "businessDate=2022-07-01")).isZero();

        Path daily = dir.resolve("daily.txt");
        Files.write(daily, DailyRecords.sample().stream().map(r -> "9" + r.substring(1)).toList(), StandardCharsets.ISO_8859_1);
        String[] posttran = {"POSTTRAN", "businessDate=2022-07-01", "dailyFile=" + daily, "rejectsFile=" + dir.resolve("rej.txt")};
        assertThat(cli.execute(posttran)).isEqualTo(4);
        assertThat(cli.execute("INTCALC", "businessDate=2022-07-01")).isZero();
        String[] creastmt = {"CREASTMT", "businessDate=2022-07-01", "stmtFile=" + dir.resolve("s1"), "htmlFile=" + dir.resolve("h1")};
        assertThat(cli.execute(creastmt)).isZero();
        assertThat(state()).isNotEqualTo(before);

        assertThat(cli.execute("RESTORE", "businessDate=2022-07-01")).isZero();
        assertThat(state()).isEqualTo(before);
        log.startCapture();
        assertThat(cli.execute(posttran)).isEqualTo(4);
        assertThat(cli.execute("INTCALC", "businessDate=2022-07-01")).isZero();
        assertThat(cli.execute(creastmt)).isZero();
        assertThat(log.captured()).noneMatch(l -> l.contains("ALREADY"));
    }

    String[] posttranOfDay(String date) throws IOException {
        Path daily = dir.resolve("daily-" + date + ".txt");
        Files.write(daily, DailyRecords.sample().stream().map(r -> "9" + r.substring(1)).toList(), StandardCharsets.ISO_8859_1);
        return new String[] {"POSTTRAN", "businessDate=" + date, "dailyFile=" + daily, "rejectsFile=" + dir.resolve("rej-" + date + ".txt")};
    }

    @Test
    @Covers({"Q-ACCTFILE-02", "Q-TRANBKP-01", "B-CYCLE"})
    void a_repeated_trigger_is_refused_at_backup_and_restore_by_date_returns_to_the_state_before_posting() throws IOException {
        List<Map<String, Object>> before = state();
        assertThat(cli.execute("BACKUP", "businessDate=2022-07-01")).isZero();
        String[] posttran = posttranOfDay("2022-07-01");
        assertThat(cli.execute(posttran)).isEqualTo(4);
        List<Map<String, Object>> posted = state();
        assertThat(posted).isNotEqualTo(before);

        // The daily cycle triggered again for the same date stops at BACKUP; no backup of the posted state exists.
        log.startCapture();
        assertThat(cli.execute("BACKUP", "businessDate=2022-07-01")).isEqualTo(8);
        assertThat(log.captured()).containsExactly("BACKUP REFUSED: POSTING ALREADY STARTED FOR 2022-07-01");
        assertThat(state()).isEqualTo(posted);
        assertThat(count("SELECT COUNT(*) FROM cycle_backup WHERE business_date = '2022-07-01'")).isEqualTo(1);

        assertThat(cli.execute("RESTORE", "businessDate=2022-07-01")).isZero();
        assertThat(state()).isEqualTo(before);
        assertThat(cli.execute(posttran)).isEqualTo(4);
    }

    /**
     * Runs the day's POSTTRAN with a non-numeric amount in record 150, so it abends after committing the earlier
     * records, then corrects the file in place: the job parameters stay the same, so the next run is a restart.
     */
    void abendMidFile(String[] posttran) throws IOException {
        Path daily = Path.of(posttran[2].substring("dailyFile=".length()));
        List<String> good = Files.readAllLines(daily, StandardCharsets.ISO_8859_1);
        List<String> bad = new ArrayList<>(good);
        String rec = bad.get(149);
        bad.set(149, rec.substring(0, 132) + "ABCDEFGHIJK" + rec.substring(143));
        Files.write(daily, bad, StandardCharsets.ISO_8859_1);
        assertThat(cli.execute(posttran)).isEqualTo(99);
        Files.write(daily, good, StandardCharsets.ISO_8859_1);
    }

    int postedOfDay() {
        return count("SELECT COUNT(*) FROM card_transaction WHERE tran_id LIKE '9%'");
    }

    @Test
    @Covers({"Q-ACCTFILE-02", "B-CYCLE", "B-UOW"})
    void backup_after_an_abended_posting_is_refused_so_restore_by_date_returns_to_the_state_before_posting() throws IOException {
        List<Map<String, Object>> before = state();
        assertThat(cli.execute("BACKUP", "businessDate=2022-07-01")).isZero();
        String[] posttran = posttranOfDay("2022-07-01");
        abendMidFile(posttran);
        List<Map<String, Object>> partlyPosted = state();
        assertThat(postedOfDay()).isPositive();
        assertThat(partlyPosted).isNotEqualTo(before);

        // The cycle triggered again stops at BACKUP: no backup of the partly posted state exists.
        log.startCapture();
        assertThat(cli.execute("BACKUP", "businessDate=2022-07-01")).isEqualTo(8);
        assertThat(log.captured()).containsExactly("BACKUP REFUSED: POSTING ALREADY STARTED FOR 2022-07-01");
        assertThat(state()).isEqualTo(partlyPosted);
        assertThat(count("SELECT COUNT(*) FROM cycle_backup WHERE business_date = '2022-07-01'")).isEqualTo(1);

        // Restart with the corrected file continues from the last commit (B-UOW) and completes.
        assertThat(cli.execute(posttran)).isEqualTo(4);
        assertThat(postedOfDay()).isEqualTo(262);

        assertThat(cli.execute("RESTORE", "businessDate=2022-07-01")).isZero();
        assertThat(state()).isEqualTo(before);
        assertThat(postedOfDay()).isZero();
    }

    @Test
    @Covers({"Q-ACCTFILE-02", "B-CYCLE", "B-UOW"})
    void after_an_abended_posting_restore_by_date_lets_the_whole_cycle_run_again() throws IOException {
        List<Map<String, Object>> before = state();
        assertThat(cli.execute("BACKUP", "businessDate=2022-07-01")).isZero();
        String[] posttran = posttranOfDay("2022-07-01");
        abendMidFile(posttran);
        assertThat(postedOfDay()).isPositive();

        assertThat(cli.execute("RESTORE", "businessDate=2022-07-01")).isZero();
        assertThat(state()).isEqualTo(before);
        assertThat(cli.execute("BACKUP", "businessDate=2022-07-01")).isZero();
        assertThat(count("SELECT COUNT(*) FROM cycle_backup WHERE business_date = '2022-07-01'")).isEqualTo(2);
        assertThat(cli.execute(posttran)).isEqualTo(4);
        assertThat(postedOfDay()).isEqualTo(262);
        assertThat(cli.execute("RESTORE", "businessDate=2022-07-01")).isZero();
        assertThat(state()).isEqualTo(before);
    }

    @Test
    @Covers({"Q-ACCTFILE-02", "B-CYCLE"})
    void a_second_restore_by_date_keeps_online_changes_made_after_the_first_restore() throws IOException {
        assertThat(cli.execute("BACKUP", "businessDate=2022-07-01")).isZero();
        String[] posttran = posttranOfDay("2022-07-01");
        assertThat(cli.execute(posttran)).isEqualTo(4);
        assertThat(cli.execute("RESTORE", "businessDate=2022-07-01")).isZero();

        long account = jdbc.queryForObject("SELECT MIN(account_id) FROM account", Long.class);
        BigDecimal balance = jdbc.queryForObject("SELECT current_balance FROM account WHERE account_id = ?", BigDecimal.class, account);
        jdbc.update("UPDATE account SET current_balance = current_balance - 10.00 WHERE account_id = ?", account);
        List<Map<String, Object>> afterOnline = state();

        assertThat(cli.execute("BACKUP", "businessDate=2022-07-01")).isZero();
        assertThat(cli.execute(posttran)).isEqualTo(4);
        assertThat(state()).isNotEqualTo(afterOnline);
        assertThat(cli.execute("RESTORE", "businessDate=2022-07-01")).isZero();

        assertThat(state()).isEqualTo(afterOnline);
        assertThat(jdbc.queryForObject("SELECT current_balance FROM account WHERE account_id = ?", BigDecimal.class, account))
            .isEqualByComparingTo(balance.subtract(new BigDecimal("10.00")));
    }

    @Test
    @Covers({"Q-ACCTFILE-02", "B-CYCLE"})
    void a_backup_on_a_database_with_no_job_runs_lets_the_restored_date_post_again() throws IOException {
        // The first job ever run on a fresh database gets instance id 0 (V2: BATCH_JOB_SEQ START WITH 0).
        long saved = jdbc.queryForObject("SELECT CONVERT(bigint, current_value) FROM sys.sequences WHERE name = 'BATCH_JOB_SEQ'",
            Long.class);
        jdbc.execute("ALTER SEQUENCE BATCH_JOB_SEQ RESTART WITH 0");
        try {
            assertThat(cli.execute("BACKUP", "businessDate=2022-07-01")).isZero();
            assertThat(jdbc.queryForObject("SELECT max_job_instance_id FROM cycle_backup WHERE business_date = '2022-07-01'",
                Long.class)).isEqualTo(-1L);
            String[] posttran = posttranOfDay("2022-07-01");
            assertThat(cli.execute(posttran)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT MIN(JOB_INSTANCE_ID) FROM BATCH_JOB_INSTANCE", Long.class)).isZero();
            assertThat(cli.execute("RESTORE", "businessDate=2022-07-01")).isZero();
            assertThat(count("SELECT COUNT(*) FROM BATCH_JOB_INSTANCE")).isZero();
            assertThat(cli.execute(posttran)).isEqualTo(4);
        } finally {
            Long used = jdbc.queryForObject("SELECT MAX(JOB_INSTANCE_ID) FROM BATCH_JOB_INSTANCE", Long.class);
            long next = Math.max(saved + 1, used == null ? 0 : used + 1);
            jdbc.execute("ALTER SEQUENCE BATCH_JOB_SEQ RESTART WITH " + next);
        }
    }

    @Test
    @Covers({"Q-ACCTFILE-02"})
    void restoring_an_unknown_backup_is_refused() {
        assertThat(cli.execute("RESTORE", "backupId=424242")).isEqualTo(8);
        assertThat(cli.execute("RESTORE", "businessDate=1999-01-01")).isEqualTo(8);
    }
}
