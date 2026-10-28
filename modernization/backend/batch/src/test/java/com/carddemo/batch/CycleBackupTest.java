package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.test.Covers;
import com.carddemo.test.DailyRecords;
import com.carddemo.test.SampleData;
import com.carddemo.test.TestDatabaseConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test
    @Covers({"Q-ACCTFILE-02"})
    void restoring_an_unknown_backup_is_refused() {
        assertThat(cli.execute("RESTORE", "backupId=424242")).isEqualTo(8);
        assertThat(cli.execute("RESTORE", "businessDate=1999-01-01")).isEqualTo(8);
    }
}
