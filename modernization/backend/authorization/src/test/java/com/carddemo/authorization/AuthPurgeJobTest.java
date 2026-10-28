package com.carddemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.carddemo.authorization.api.AuthorizationJobs;
import com.carddemo.authorization.internal.PendingAuthRepository;
import com.carddemo.common.batch.JobLog;
import com.carddemo.test.Covers;
import com.carddemo.test.SampleData;
import com.carddemo.test.TestApplication;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** CBPAUP0C (job CBPAUP0J) against the IMS sample; scenarios changed by a ledger FIX assert the fixed behavior. */
@SpringBootTest(classes = {TestApplication.class, AuthPurgeJobTest.Clocks.class})
class AuthPurgeJobTest {

    static final String DASHES = "*-------------------------------------*";
    static final long[] ACCOUNTS = {1, 5, 7, 13, 15, 16, 17, 18, 23, 29, 30, 31, 32, 33, 34, 38, 42, 45, 46, 47, 48};

    @TestConfiguration(proxyBeanMethods = false)
    static class Clocks {
        @Bean
        @Primary
        AuthFixtures.MutableClock mutableClock() {
            return new AuthFixtures.MutableClock();
        }
    }

    @Autowired
    AuthorizationJobs jobs;
    @Autowired
    JobLog log;
    @Autowired
    SampleData sample;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Clock clock;
    @MockitoSpyBean
    PendingAuthRepository repo;
    @MockitoSpyBean
    PlatformTransactionManager tm;

    @BeforeEach
    void load() {
        AuthFixtures.load(sample, jdbc);
        today(LocalDateTime.of(2025, 3, 5, 6, 0));
        log.startCapture();
    }

    @AfterEach
    void unstub() {
        reset(repo, tm);
    }

    void today(LocalDateTime t) {
        ((AuthFixtures.MutableClock) clock).set(t);
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    int details() {
        return count("SELECT COUNT(*) FROM pending_auth_detail");
    }

    int summaries() {
        return count("SELECT COUNT(*) FROM pending_auth_summary");
    }

    static List<String> totals(int read, int deleted, int dRead, int dDeleted) {
        return List.of(" ", DASHES, String.format("# TOTAL SUMMARY READ  :+%08d", read), String.format("# SUMMARY REC DELETED :+%08d", deleted),
            String.format("# TOTAL DETAILS READ  :+%08d", dRead), String.format("# DETAILS REC DELETED :+%08d", dDeleted), DASHES, " ");
    }

    @Test
    @Covers({"TS-CBPAUP0C-01", "TS-CBPAUP0C-08", "BR-CBPAUP0C-03", "BR-CBPAUP0C-04", "BR-CBPAUP0C-06", "BR-CBPAUP0C-07",
        "BR-CBPAUP0C-08", "Q-CBPAUP0C-04", "Q-CBPAUP0J-02", "Q-ENT-42", "Q-CBPAUP0C-02"})
    void jcl_card_purges_every_detail_and_every_summary_with_a_checkpoint_per_summary() {
        assertThat(jobs.purgeExpired("00,00001,00001,Y").returnCode()).isZero();
        List<String> expected = new ArrayList<>(List.of("STARTING PROGRAM CBPAUP0C::", DASHES,
            "CBPAUP0C PARM RECEIVED :00,00001,00001,Y ", "TODAYS DATE            :25064", " "));
        // Q-CBPAUP0C-04 (FIX): with frequency 1 a checkpoint follows every summary, plus the final one; display
        // frequency 1 shows each. Debug lines go to the DEBUG log, not SYSOUT (Q-CBPAUP0J-02).
        for (int i = 0; i < ACCOUNTS.length; i++) {
            expected.add(String.format("CHKP SUCCESS: AUTH COUNT - +%08d, APP ID - %011d", i + 1, ACCOUNTS[i]));
        }
        expected.add("CHKP SUCCESS: AUTH COUNT - +00000021, APP ID - 00000000048");
        expected.addAll(totals(21, 21, 202, 202));
        assertThat(log.captured()).containsExactlyElementsOf(expected);
        assertThat(details()).isZero();
        assertThat(summaries()).isZero();
    }

    @Test
    @Covers({"TS-CBPAUP0C-02", "BR-CBPAUP0C-04", "Q-CBPAUP0C-10", "Q-ENT-27"})
    void detail_is_purged_from_the_day_its_age_reaches_the_expiry_days() {
        today(LocalDateTime.of(2023, 11, 20, 0, 1));
        int old = count("SELECT COUNT(*) FROM pending_auth_detail WHERE auth_ts < '2023-11-16'");
        int kept = count("SELECT COUNT(*) FROM pending_auth_detail WHERE auth_ts >= '2023-11-16'");
        assertThat(old).isPositive();
        assertThat(kept).isPositive();
        assertThat(jobs.purgeExpired("05,00001,00001,N").returnCode()).isZero();
        // 2023-11-15 is 5 days old on 2023-11-20 and purged; 2023-11-16 is 4 days old and kept.
        assertThat(count("SELECT COUNT(*) FROM pending_auth_detail WHERE auth_ts < '2023-11-16'")).isZero();
        assertThat(details()).isEqualTo(kept);
        assertThat(log.captured()).contains(String.format("# DETAILS REC DELETED :+%08d", old), "TODAYS DATE            :23324");
    }

    @Test
    @Covers({"TS-CBPAUP0C-03", "Q-CBPAUP0C-01", "BR-CBPAUP0C-05"})
    void kept_summary_is_saved_with_the_purged_approvals_taken_out_but_balances_unchanged() {
        today(LocalDateTime.of(2024, 5, 1, 9, 0));
        // Account 7: the 10 oldest details keep their 2023 dates, the 40 newer ones move to yesterday.
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT auth_id, approved_amt FROM pending_auth_detail WHERE account_id = 7 ORDER BY auth_ts, auth_id");
        assertThat(rows).hasSize(50);
        BigDecimal purgedAmt = BigDecimal.ZERO;
        for (int i = 0; i < rows.size(); i++) {
            if (i < 10) {
                purgedAmt = purgedAmt.add((BigDecimal) rows.get(i).get("approved_amt"));
            } else {
                jdbc.update("UPDATE pending_auth_detail SET auth_ts = DATEADD(day, DATEDIFF(day, auth_ts, '2024-04-30'), auth_ts) "
                    + "WHERE auth_id = ?", rows.get(i).get("auth_id"));
            }
        }
        assertThat(jobs.purgeExpired("05,00005,00010,N").returnCode()).isZero();
        Map<String, Object> s = jdbc.queryForMap("SELECT * FROM pending_auth_summary WHERE account_id = 7");
        assertThat(s.get("approved_count")).isEqualTo(40);
        assertThat((BigDecimal) s.get("approved_amt")).isEqualByComparingTo(new BigDecimal("74.79").subtract(purgedAmt));
        assertThat((BigDecimal) s.get("credit_balance")).isEqualByComparingTo("74.79");
        assertThat((BigDecimal) s.get("cash_balance")).isEqualByComparingTo("0.00");
        assertThat(count("SELECT COUNT(*) FROM pending_auth_detail WHERE account_id = 7")).isEqualTo(40);
        assertThat(summaries()).isEqualTo(1);
        assertThat(log.captured()).containsSequence(totals(21, 20, 202, 162));
    }

    void declinedAccount2(LocalDateTime... dates) {
        jdbc.update("INSERT INTO pending_auth_summary (account_id, customer_id, auth_status, account_status, credit_limit, cash_limit, "
            + "credit_balance, cash_balance, approved_count, declined_count, approved_amt, declined_amt) "
            + "VALUES (2, 2, '', '', 6130.00, 5448.00, 0, 0, 0, ?, 0, ?)", dates.length, new BigDecimal("30.00").multiply(BigDecimal.valueOf(dates.length)));
        for (LocalDateTime d : dates) {
            jdbc.update("INSERT INTO pending_auth_detail (account_id, auth_ts, auth_orig_date, auth_orig_time, card_num, auth_type, "
                + "card_expiry_date, message_type, message_source, auth_id_code, auth_resp_code, auth_resp_reason, processing_code, "
                + "transaction_amt, approved_amt, merchant_category_code, acqr_country_code, pos_entry_mode, merchant_id, merchant_name, "
                + "merchant_city, merchant_state, merchant_zip, transaction_id, match_status, auth_fraud) VALUES (2, ?, '240305', '100000', "
                + "?, '0100', '1226', '1234', '102030', '100000', '05', '4100', '000000', 30.00, 0.00, '5442', 'USA', '01', 'M1', 'Shop', "
                + "'City', 'TX', '75001', 'T1', 'D', '')", java.sql.Timestamp.valueOf(d), AuthFixtures.CARD_2);
        }
    }

    @Test
    @Covers({"TS-CBPAUP0C-04", "Q-CBPAUP0C-02", "Q-ENT-42", "BR-CBPAUP0C-05", "BR-CBPAUP0C-06"})
    void summary_without_approvals_is_kept_while_unexpired_declined_details_remain() {
        today(LocalDateTime.of(2024, 3, 5, 9, 0));
        LocalDateTime fresh = LocalDateTime.of(2024, 3, 4, 12, 0);
        declinedAccount2(fresh, fresh.plusMinutes(1), fresh.plusMinutes(2), LocalDateTime.of(2024, 1, 2, 8, 0));
        assertThat(jobs.purgeExpired("05,00005,00010,N").returnCode()).isZero();
        Map<String, Object> s = jdbc.queryForMap("SELECT * FROM pending_auth_summary WHERE account_id = 2");
        // The expired declined detail is purged and taken out of the declined count and amount; the three unexpired stay.
        assertThat(s.get("declined_count")).isEqualTo(3);
        assertThat((BigDecimal) s.get("declined_amt")).isEqualByComparingTo("90.00");
        assertThat(count("SELECT COUNT(*) FROM pending_auth_detail WHERE account_id = 2")).isEqualTo(3);
    }

    @Test
    @Covers({"TS-CBPAUP0C-06", "Q-CBPAUP0C-03", "Q-ENT-27"})
    void age_across_a_year_end_is_counted_in_real_days() {
        today(LocalDateTime.of(2024, 1, 1, 9, 0));
        declinedAccount2(LocalDateTime.of(2023, 12, 31, 23, 0));
        jobs.purgeExpired("05,00005,00010,N");
        // YYDDD arithmetic (24001 - 23365 = 636) would purge it; it is one day old.
        assertThat(count("SELECT COUNT(*) FROM pending_auth_detail WHERE account_id = 2")).isEqualTo(1);
        today(LocalDateTime.of(2024, 1, 5, 9, 0));
        jobs.purgeExpired("05,00005,00010,N");
        assertThat(count("SELECT COUNT(*) FROM pending_auth_detail WHERE account_id = 2")).isZero();
        assertThat(count("SELECT COUNT(*) FROM pending_auth_summary WHERE account_id = 2")).isZero();
    }

    @Test
    @Covers({"TS-CBPAUP0C-05", "VR-CBPAUP0C-01", "VR-CBPAUP0C-02", "BR-CBPAUP0C-01", "BR-CBPAUP0C-02", "Q-CBPAUP0C-09"})
    void invalid_parameters_take_the_defaults() {
        today(LocalDateTime.of(2023, 11, 20, 0, 1));
        int old = count("SELECT COUNT(*) FROM pending_auth_detail WHERE auth_ts < '2023-11-16'");
        assertThat(jobs.purgeExpired("XX,     ,     ,N").returnCode()).isZero();
        List<String> out = log.captured();
        assertThat(out.get(2)).isEqualTo("CBPAUP0C PARM RECEIVED :XX,     ,     ,N ");
        // Expiry 5; checkpoints every 5 summaries (4 plus the final one) stay below the display frequency 10.
        assertThat(out).noneMatch(l -> l.startsWith("CHKP SUCCESS")).contains(String.format("# DETAILS REC DELETED :+%08d", old));

        load();
        today(LocalDateTime.of(2023, 11, 20, 0, 1));
        // An empty card takes the same defaults.
        assertThat(jobs.purgeExpired("").returnCode()).isZero();
        assertThat(log.captured().get(2)).isEqualTo("CBPAUP0C PARM RECEIVED :" + " ".repeat(17));
        assertThat(log.captured()).noneMatch(l -> l.startsWith("CHKP SUCCESS"));
        load();
        // Non-numeric checkpoint frequency takes the default 5 (numeric comparison); display frequency 2 shows
        // every second of the 5 checkpoints.
        today(LocalDateTime.of(2023, 11, 20, 0, 1));
        assertThat(jobs.purgeExpired("5 ,ABCDE,00002,y").returnCode()).isZero();
        assertThat(log.captured()).filteredOn(l -> l.startsWith("CHKP SUCCESS")).containsExactly(
            "CHKP SUCCESS: AUTH COUNT - +00000010, APP ID - 00000000029",
            "CHKP SUCCESS: AUTH COUNT - +00000020, APP ID - 00000000047");
        assertThat(count("SELECT COUNT(*) FROM pending_auth_detail WHERE auth_ts < '2023-11-16'")).isZero();
    }

    @Test
    @Covers({"TS-CBPAUP0C-07", "ERR-CBPAUP0C-03", "ERR-CBPAUP0C-06", "Q-CBPAUP0C-08"})
    void detail_delete_failure_ends_with_rc16_and_keeps_committed_checkpoints() {
        List<Long> acct13 = jdbc.queryForList("SELECT auth_id FROM pending_auth_detail WHERE account_id = 13 ORDER BY auth_ts DESC, auth_id DESC",
            Long.class);
        assertThat(acct13).hasSize(58);
        doThrow(new DataAccessResourceFailureException("dlet")).when(repo).deleteDetail(eq(acct13.get(1)));
        int earlier = count("SELECT COUNT(*) FROM pending_auth_detail WHERE account_id IN (1, 5, 7)");
        assertThat(jobs.purgeExpired("00,00001,00010,N").returnCode()).isEqualTo(16);
        assertThat(log.captured()).endsWith("AUTH DETAIL DELETE FAILED :DataAccessResourceFailureException",
            "AUTH APP ID               :+00000000013", "CBPAUP0C ABENDING ...");
        // Accounts 1, 5 and 7 were committed at their checkpoints; account 13's first delete rolled back with the open interval.
        assertThat(earlier).isEqualTo(57);
        assertThat(count("SELECT COUNT(*) FROM pending_auth_detail WHERE account_id IN (1, 5, 7)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM pending_auth_summary WHERE account_id IN (1, 5, 7)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM pending_auth_detail WHERE account_id = 13")).isEqualTo(58);
        assertThat(details()).isEqualTo(202 - 57);
        assertThat(log.captured()).noneMatch(l -> l.startsWith("# TOTAL"));
    }

    @Test
    @Covers({"ERR-CBPAUP0C-01", "ERR-CBPAUP0C-02", "ERR-CBPAUP0C-04", "ERR-CBPAUP0C-06"})
    void read_and_summary_delete_failures_display_their_lines_and_end_with_rc16() {
        doThrow(new DataAccessResourceFailureException("gn")).when(repo).nextSummary(eq(7L));
        assertThat(jobs.purgeExpired("00,00001,00010,N").returnCode()).isEqualTo(16);
        assertThat(log.captured()).endsWith("AUTH SUMMARY READ FAILED  :DataAccessResourceFailureException",
            "SUMMARY READ BEFORE ABEND :+00000003", "CBPAUP0C ABENDING ...");
        reset(repo);

        load();
        doThrow(new DataAccessResourceFailureException("gnp")).when(repo).detailsFrom(eq(13L), any(), any(), anyBoolean(), anyInt());
        int before = count("SELECT COUNT(*) FROM pending_auth_detail WHERE account_id IN (1, 5, 7)");
        assertThat(jobs.purgeExpired("00,00001,00010,N").returnCode()).isEqualTo(16);
        assertThat(before).isPositive();
        assertThat(log.captured()).endsWith("AUTH DETAIL READ FAILED  :DataAccessResourceFailureException",
            "SUMMARY AUTH APP ID      :+00000000013", "DETAIL READ BEFORE ABEND :+" + String.format("%08d", before),
            "CBPAUP0C ABENDING ...");
        reset(repo);

        load();
        doThrow(new DataAccessResourceFailureException("dlet root")).when(repo).deleteSummary(eq(1L));
        assertThat(jobs.purgeExpired("00,00001,00010,N").returnCode()).isEqualTo(16);
        assertThat(log.captured()).endsWith("AUTH SUMMARY DELETE FAILED :DataAccessResourceFailureException",
            "AUTH APP ID                :+00000000001", "CBPAUP0C ABENDING ...");
        assertThat(count("SELECT COUNT(*) FROM pending_auth_summary WHERE account_id = 1")).isEqualTo(1);
        assertThat(details()).isEqualTo(202);
    }

    @Test
    @Covers({"ERR-CBPAUP0C-05"})
    void checkpoint_failure_displays_chkp_failed_and_ends_with_rc16() {
        doAnswer(inv -> {
            ((PlatformTransactionManager) inv.getMock()).rollback(inv.<TransactionStatus>getArgument(0));
            throw new TransactionSystemException("commit failed");
        }).when(tm).commit(argThat(s -> s != null && !s.isCompleted()
            && "CBPAUP0C".equals(TransactionSynchronizationManager.getCurrentTransactionName())));
        assertThat(jobs.purgeExpired("00,00002,00010,N").returnCode()).isEqualTo(16);
        assertThat(log.captured()).endsWith("CHKP FAILED: DIBSTAT - TransactionSystemException, REC COUNT - +00000002, APP ID - 00000000005",
            "CBPAUP0C ABENDING ...");
        assertThat(details()).isEqualTo(202);
    }
}
