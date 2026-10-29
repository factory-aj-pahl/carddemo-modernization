package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.test.Covers;
import com.carddemo.test.SampleData;
import com.carddemo.test.TestDatabaseConfig;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** JOB-INTCALC (CBACT04C) through the batch command line (spec/programs/CBACT04C.md). */
@SpringBootTest(classes = {BatchApplication.class, TestDatabaseConfig.class, SampleData.class})
class IntcalcJobTest extends CycleJobTestSupport {

    int intcalc(String date) {
        return cli.execute("INTCALC", "businessDate=" + date);
    }

    List<String> interestIds() {
        return jdbc.queryForList("SELECT tran_id FROM card_transaction WHERE type_cd = '01' AND cat_cd = 5 AND source = 'System'"
            + " ORDER BY tran_id", String.class);
    }

    @Test
    @Covers({"TS-CBACT04C-02", "TS-CBACT04C-03", "Q-CBACT04C-01", "Q-CBACT04C-05", "Q-INTCALC-01", "B-DATE", "BR-CBACT04C-01",
        "BR-CBACT04C-06", "BR-CBACT04C-07", "Q-CBACT04C-06", "Q-COMBTRAN-01"})
    void posted_sample_charges_every_account_including_the_last() throws IOException {
        postSample("2022-07-18");
        assertThat(intcalc("2022-07-18")).isZero();
        List<String> out = log.captured();
        assertThat(out).hasSize(102);
        assertThat(out.get(0)).isEqualTo("START OF EXECUTION OF PROGRAM CBACT04C");
        assertThat(out.get(101)).isEqualTo("END OF EXECUTION OF PROGRAM CBACT04C");
        assertThat(out).noneMatch(l -> l.contains("DISCLOSURE GROUP RECORD MISSING"));
        List<String> ids = interestIds();
        assertThat(ids).hasSize(50);
        assertThat(ids.get(0)).isEqualTo("2022071800000001");
        assertThat(ids.get(49)).isEqualTo("2022071800000050");
        Map<String, Object> a50 = jdbc.queryForMap(
            "SELECT current_balance, curr_cycle_credit, curr_cycle_debit FROM account WHERE account_id = 50");
        assertThat((BigDecimal) a50.get("current_balance")).isEqualByComparingTo("1964.64");
        assertThat((BigDecimal) a50.get("curr_cycle_credit")).isEqualByComparingTo("0");
        assertThat((BigDecimal) a50.get("curr_cycle_debit")).isEqualByComparingTo("0");
        Map<String, Object> row = jdbc.queryForMap("SELECT description, amount, card_num, merchant_id, merchant_name, orig_ts_text,"
            + " proc_ts_text FROM card_transaction WHERE tran_id = '2022071800000050'");
        assertThat(row.get("description")).isEqualTo("Int. for a/c 00000000050");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("18.77");
        assertThat(row.get("merchant_name")).isEqualTo("");
        assertThat((String) row.get("proc_ts_text")).matches("\\d{4}-\\d\\d-\\d\\d-\\d\\d\\.\\d\\d\\.\\d\\d\\.\\d\\d0000");
        assertThat(count("SELECT COUNT(*) FROM interest_account WHERE interest_month = '2022-07'")).isEqualTo(50);
    }

    @Test
    @Covers({"Q-CBACT04C-08", "B-CYCLE"})
    void a_second_run_in_the_same_month_is_refused_and_the_next_month_runs() throws IOException {
        postSample("2022-07-01");
        assertThat(intcalc("2022-07-01")).isZero();
        log.startCapture();
        assertThat(intcalc("2022-07-29")).isEqualTo(8);
        assertThat(log.captured()).containsExactly("INTEREST ALREADY CALCULATED FOR 2022-07");
        assertThat(interestIds()).hasSize(50);
        log.startCapture();
        assertThat(intcalc("2022-08-01")).isZero();
        assertThat(interestIds()).filteredOn(id -> id.startsWith("2022080100")).isNotEmpty();
    }

    @Test
    @Covers({"B-CYCLE", "Q-CBACT04C-08"})
    void interest_status_reports_the_month_and_the_latest_earlier_month_without_running_anything() throws IOException {
        assertThat(cli.execute("INTSTATUS", "businessDate=2022-07-05")).isZero();
        assertThat(log.captured()).containsExactly("INTEREST NOT CALCULATED FOR 2022-07; NO EARLIER MONTH CALCULATED");
        postSample("2022-06-01");
        assertThat(intcalc("2022-06-01")).isZero();
        int rows = interestIds().size();
        log.startCapture();
        assertThat(cli.execute("INTSTATUS", "businessDate=2022-06-20")).isZero();
        assertThat(cli.execute("INTSTATUS", "businessDate=2022-07-05")).isZero();
        assertThat(cli.execute("INTSTATUS", "businessDate=2022-09-01")).isZero();
        assertThat(cli.execute("INTSTATUS", "businessDate=2022-05-31")).isZero();
        assertThat(log.captured()).containsExactly("INTEREST ALREADY CALCULATED FOR 2022-06",
            "INTEREST NOT CALCULATED FOR 2022-07; LAST CALCULATED FOR 2022-06",
            "INTEREST NOT CALCULATED FOR 2022-09; LAST CALCULATED FOR 2022-06",
            "INTEREST NOT CALCULATED FOR 2022-05; NO EARLIER MONTH CALCULATED");
        assertThat(interestIds()).hasSize(rows);
        assertThat(count("SELECT COUNT(*) FROM BATCH_JOB_INSTANCE WHERE JOB_NAME = 'INTCALC'")).isEqualTo(1);
    }

    @Test
    @Covers({"B-CYCLE", "Q-CBACT04C-08", "B-DATE"})
    void interest_status_counts_only_completed_runs_and_needs_a_valid_date() throws IOException {
        postSample("2022-07-01");
        jdbc.update("DELETE FROM account WHERE account_id = 10");
        assertThat(intcalc("2022-07-01")).isEqualTo(99);
        log.startCapture();
        assertThat(cli.execute("INTSTATUS", "businessDate=2022-07-05")).isZero();
        assertThat(cli.execute("INTSTATUS", "businessDate=2022-02-30")).isEqualTo(12);
        assertThat(cli.execute("INTSTATUS")).isEqualTo(12);
        assertThat(log.captured()).containsExactly("INTEREST NOT CALCULATED FOR 2022-07; NO EARLIER MONTH CALCULATED",
            "INVALID OR MISSING BUSINESS DATE", "INVALID OR MISSING BUSINESS DATE");
    }

    @Test
    @Covers({"Q-CBACT04C-08", "B-UOW", "B-ABEND", "ERR-CBACT04C-03", "ERR-CBACT04C-09", "TS-CBACT04C-07"})
    void a_rerun_after_an_abend_charges_only_the_accounts_not_yet_charged() throws IOException {
        postSample("2022-07-18");
        Map<String, Object> saved = jdbc.queryForMap("SELECT * FROM account WHERE account_id = 10");
        jdbc.update("DELETE FROM account WHERE account_id = 10");
        assertThat(intcalc("2022-07-18")).isEqualTo(99);
        List<String> out = log.captured();
        assertThat(out.subList(out.size() - 4, out.size())).containsExactly("ACCOUNT NOT FOUND: 00000000010",
            "ERROR READING ACCOUNT FILE", "FILE STATUS IS: NNNN0023", "ABENDING PROGRAM");
        int charged = interestIds().size();
        assertThat(charged).isPositive().isLessThan(50);
        assertThat(count("SELECT COUNT(*) FROM interest_account")).isEqualTo(9);

        jdbc.update("""
            INSERT INTO account (account_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date,
                expiration_date, reissue_date, curr_cycle_credit, curr_cycle_debit, addr_zip, group_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", saved.get("account_id"), saved.get("active_status"),
            saved.get("current_balance"), saved.get("credit_limit"), saved.get("cash_credit_limit"), saved.get("open_date"),
            saved.get("expiration_date"), saved.get("reissue_date"), saved.get("curr_cycle_credit"), saved.get("curr_cycle_debit"),
            saved.get("addr_zip"), saved.get("group_id"));
        log.startCapture();
        assertThat(intcalc("2022-07-18")).isZero();
        List<String> ids = interestIds();
        assertThat(ids).hasSize(50).doesNotHaveDuplicates();
        assertThat(ids.get(49)).isEqualTo("2022071800000050");
        assertThat(log.captured()).noneMatch(l -> l.startsWith("00000000001"));
        assertThat(count("SELECT COUNT(*) FROM interest_account")).isEqualTo(50);
    }

    @Test
    @Covers({"Q-CBACT04C-08", "Q-ENT-07"})
    void more_than_999999_interest_ids_for_one_prefix_abend_instead_of_wrapping() throws IOException {
        postSample("2022-07-18");
        jdbc.update("""
            INSERT INTO card_transaction (tran_id, type_cd, cat_cd, source, description, amount, card_num)
            VALUES ('2022071800999999', '01', 5, 'System', 'filler', 0, '0500024453765740')""");
        assertThat(intcalc("2022-07-18")).isEqualTo(99);
        List<String> out = log.captured();
        assertThat(out.subList(out.size() - 2, out.size())).containsExactly("ERROR WRITING TRANSACTION RECORD", "ABENDING PROGRAM");
        assertThat(interestIds()).containsExactly("2022071800999999");
    }

    @Test
    @Covers({"Q-CBACT04C-03", "DF-6", "Q-ENT-32"})
    void an_account_with_several_cards_is_charged_once_on_its_lowest_card() throws IOException {
        postSample("2022-07-18");
        jdbc.update("INSERT INTO card_xref (card_num, customer_id, account_id) VALUES ('0000000000000001', 1, 1)");
        assertThat(intcalc("2022-07-18")).isZero();
        assertThat(jdbc.queryForList("SELECT card_num FROM card_transaction WHERE tran_id LIKE '20220718%'"
            + " AND description = 'Int. for a/c 00000000001'", String.class)).containsExactly("0000000000000001");
    }

    @Test
    @Covers({"BR-CBACT04C-02", "TS-CBACT04C-04"})
    void a_blank_group_falls_back_to_default_with_the_message_pair() throws IOException {
        postSample("2022-07-18");
        jdbc.update("UPDATE account SET group_id = '' WHERE account_id = 1");
        assertThat(intcalc("2022-07-18")).isZero();
        List<String> out = log.captured();
        assertThat(out.get(1)).startsWith("00000000001");
        assertThat(out.subList(2, 4)).containsExactly("DISCLOSURE GROUP RECORD MISSING", "TRY WITH DEFAULT GROUP CODE");
    }

    @Test
    @Covers({"Q-COMBTRAN-02"})
    void there_is_no_combtran_job_because_interest_is_already_in_the_transactions() {
        assertThat(cli.execute("COMBTRAN")).isEqualTo(ExitCodes.USAGE);
        assertThat(log.captured()).isEmpty();
    }

    @Test
    @Covers({"B-DATE"})
    void missing_or_invalid_business_date_stops_with_exit_12() {
        assertThat(cli.execute("INTCALC")).isEqualTo(12);
        assertThat(intcalc("2022-13-01")).isEqualTo(12);
        assertThat(log.captured()).containsExactly("INVALID OR MISSING BUSINESS DATE", "INVALID OR MISSING BUSINESS DATE");
        assertThat(count("SELECT COUNT(*) FROM interest_account")).isZero();
    }
}
