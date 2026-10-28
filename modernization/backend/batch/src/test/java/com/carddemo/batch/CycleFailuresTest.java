package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.carddemo.test.Covers;
import com.carddemo.test.DailyRecords;
import com.carddemo.test.SampleData;
import com.carddemo.test.TestDatabaseConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * File, table, and lookup failures of the cycle jobs that no parity dataset can produce (datasets.md, "Not
 * reproducible by a dataset"). A table is made unavailable by renaming it, and its writes are refused by a
 * CHECK constraint that existing rows are exempt from; both are undone after each test.
 */
@SpringBootTest(classes = {BatchApplication.class, TestDatabaseConfig.class, SampleData.class})
class CycleFailuresTest extends CycleJobTestSupport {

    static final Path DEV_FULL = Path.of("/dev/full");

    private final List<String> renamed = new ArrayList<>();
    private final List<String> refused = new ArrayList<>();

    void unavailable(String... tables) {
        for (String t : tables) {
            jdbc.execute("EXEC sp_rename '" + t + "', '" + t + "_unavailable'");
            renamed.add(t);
        }
    }

    void refuseWrites(String table, String allowed) {
        jdbc.execute("ALTER TABLE " + table + " WITH NOCHECK ADD CONSTRAINT fault_" + table + " CHECK (" + allowed + ")");
        refused.add(table);
    }

    @AfterEach
    void restoreTables() {
        for (String t : renamed) {
            jdbc.execute("EXEC sp_rename '" + t + "_unavailable', '" + t + "'");
        }
        for (String t : refused) {
            jdbc.execute("ALTER TABLE " + t + " DROP CONSTRAINT fault_" + t);
        }
    }

    List<String> tail(int n) {
        List<String> out = log.captured();
        return out.subList(Math.max(0, out.size() - n), out.size());
    }

    // ---- INTCALC (CBACT04C) ----

    int intcalc() {
        return cli.execute("INTCALC", "businessDate=2022-07-18");
    }

    int interestRows() {
        return count("SELECT COUNT(*) FROM card_transaction WHERE tran_id LIKE '2022071800%'");
    }

    @Test
    @Covers({"ERR-CBACT04C-02", "B-ABEND"})
    void intcalc_abends_when_the_category_balances_cannot_be_read() throws IOException {
        postSample("2022-07-18");
        unavailable("tran_cat_balance");
        assertThat(intcalc()).isEqualTo(99);
        assertThat(log.captured()).containsExactly("START OF EXECUTION OF PROGRAM CBACT04C",
            "ERROR READING TRANSACTION CATEGORY FILE", "ABENDING PROGRAM");
        assertThat(interestRows()).isZero();
    }

    @Test
    @Covers({"ERR-CBACT04C-04", "ERR-CBACT04C-09", "B-UOW"})
    void intcalc_abends_on_an_account_without_a_card_after_committing_the_earlier_accounts() throws IOException {
        postSample("2022-07-18");
        jdbc.update("UPDATE card_xref SET account_id = 99999 WHERE account_id = 10");
        jdbc.update("UPDATE card SET account_id = 99999 WHERE account_id = 10");
        assertThat(intcalc()).isEqualTo(99);
        assertThat(tail(4)).containsExactly("ACCOUNT NOT FOUND: 00000000010", "ERROR READING XREF FILE",
            "FILE STATUS IS: NNNN0023", "ABENDING PROGRAM");
        assertThat(count("SELECT COUNT(*) FROM interest_account")).isEqualTo(9);
    }

    @Test
    @Covers({"ERR-CBACT04C-05"})
    void intcalc_abends_when_the_disclosure_groups_cannot_be_read() throws IOException {
        postSample("2022-07-18");
        unavailable("disclosure_group");
        assertThat(intcalc()).isEqualTo(99);
        assertThat(tail(2)).containsExactly("ERROR READING DISCLOSURE GROUP FILE", "ABENDING PROGRAM");
        assertThat(interestRows()).isZero();
    }

    @Test
    @Covers({"TS-CBACT04C-08", "ERR-CBACT04C-05", "ERR-CBACT04C-09"})
    void a_blank_group_without_a_default_rate_abends_after_the_message_pair() throws IOException {
        postSample("2022-07-18");
        jdbc.update("UPDATE account SET group_id = '' WHERE account_id = 1");
        jdbc.update("DELETE FROM disclosure_group WHERE group_id = 'DEFAULT' AND type_cd = '01' AND cat_cd = 1");
        assertThat(intcalc()).isEqualTo(99);
        List<String> out = log.captured();
        assertThat(out).hasSize(7);
        assertThat(out.get(1)).startsWith("0000000000101");
        assertThat(out.subList(2, 7)).containsExactly("DISCLOSURE GROUP RECORD MISSING", "TRY WITH DEFAULT GROUP CODE",
            "ERROR READING DEFAULT DISCLOSURE GROUP", "FILE STATUS IS: NNNN0023", "ABENDING PROGRAM");
        assertThat(interestRows()).isZero();
    }

    @Test
    @Covers({"ERR-CBACT04C-06", "B-UOW"})
    void intcalc_abends_when_an_account_cannot_be_rewritten_and_keeps_none_of_its_interest() throws IOException {
        postSample("2022-07-18");
        refuseWrites("account", "1 = 0");
        assertThat(intcalc()).isEqualTo(99);
        assertThat(tail(2)).containsExactly("ERROR RE-WRITING ACCOUNT FILE", "ABENDING PROGRAM");
        assertThat(interestRows()).isZero();
        assertThat(count("SELECT COUNT(*) FROM interest_account")).isZero();
    }

    @Test
    @Covers({"ERR-CBACT04C-07"})
    void intcalc_abends_when_an_interest_transaction_cannot_be_written() throws IOException {
        postSample("2022-07-18");
        refuseWrites("card_transaction", "1 = 0");
        assertThat(intcalc()).isEqualTo(99);
        assertThat(tail(2)).containsExactly("ERROR WRITING TRANSACTION RECORD", "ABENDING PROGRAM");
        assertThat(interestRows()).isZero();
    }

    // ---- TRANVERIFY (CBTRN01C) ----

    int tranverify(Path daily) {
        return cli.execute("TRANVERIFY", "dailyFile=" + daily);
    }

    Path daily(List<String> recs) throws IOException {
        Path p = dir.resolve("daily-" + System.nanoTime() + ".txt");
        Files.write(p, recs, StandardCharsets.ISO_8859_1);
        return p;
    }

    @Test
    @Covers({"ERR-CBTRN01C-02"})
    void tranverify_abends_when_the_daily_file_cannot_be_read() throws IOException {
        Path notAFile = Files.createDirectory(dir.resolve("dailytran"));
        assertThat(tranverify(notAFile)).isEqualTo(99);
        assertThat(log.captured()).containsExactly("START OF EXECUTION OF PROGRAM CBTRN01C", "ERROR READING DAILY TRANSACTION FILE",
            "ABENDING PROGRAM");
    }

    @Test
    @Covers({"Q-CBTRN01C-03", "B-ABEND"})
    void tranverify_abends_on_a_failed_lookup_instead_of_reusing_the_previous_record() throws IOException {
        Path one = daily(DailyRecords.sample().subList(0, 1));
        unavailable("card_xref");
        assertThat(tranverify(one)).isEqualTo(99);
        List<String> out = log.captured();
        assertThat(out).hasSize(3);
        assertThat(out.get(2)).isEqualTo("ABENDING PROGRAM");
        restoreTables();
        renamed.clear();

        unavailable("account");
        log.startCapture();
        assertThat(tranverify(one)).isEqualTo(99);
        out = log.captured();
        assertThat(out).hasSize(7);
        assertThat(out.get(2)).isEqualTo("SUCCESSFUL READ OF XREF");
        assertThat(out.get(6)).isEqualTo("ABENDING PROGRAM");
        assertThat(out).noneMatch(l -> l.contains("ACCOUNT FILE") || l.contains("NOT FOUND"));
    }

    @Test
    @Covers({"Q-CBTRN01C-04"})
    void tranverify_needs_only_the_daily_file_the_cross_reference_and_the_accounts() throws IOException {
        Path all = daily(DailyRecords.sample());
        unavailable("card", "customer", "card_transaction");
        assertThat(tranverify(all)).isZero();
        List<String> out = log.captured();
        assertThat(out.get(out.size() - 1)).isEqualTo("END OF EXECUTION OF PROGRAM CBTRN01C");
        assertThat(out).filteredOn("SUCCESSFUL READ OF ACCOUNT FILE"::equals).hasSize(300);
    }

    @Test
    @Covers({"VR-CBTRN01C-01"})
    void only_the_card_and_its_account_are_checked() throws IOException {
        String rec = DailyRecords.withType(DailyRecords.withAmount(DailyRecords.sample().get(0), "-99999.99"), "99", 9999);
        long acct = jdbc.queryForObject("SELECT account_id FROM card_xref WHERE card_num = ?", Long.class, rec.substring(262, 278));
        jdbc.update("UPDATE account SET active_status = 'N', expiration_date = '2000-01-01' WHERE account_id = ?", acct);
        jdbc.update("UPDATE card SET active_status = 'N', expiration_date = '2000-01-01' WHERE account_id = ?", acct);
        assertThat(tranverify(daily(List.of(rec)))).isZero();
        assertThat(log.captured()).contains("SUCCESSFUL READ OF XREF", "SUCCESSFUL READ OF ACCOUNT FILE")
            .noneMatch(l -> l.startsWith("INVALID") || l.contains("COULD NOT BE VERIFIED"));
    }

    // ---- POSTTRAN (CBTRN02C) ----

    Path rejects() {
        return dir.resolve("dalyrejs.txt");
    }

    int posttran(Path daily, String date) {
        return cli.execute("POSTTRAN", "businessDate=" + date, "dailyFile=" + daily, "rejectsFile=" + rejects());
    }

    @Test
    @Covers({"ERR-CBTRN02C-02", "B-ABEND"})
    void posttran_abends_when_the_daily_file_cannot_be_read() throws IOException {
        Path notAFile = Files.createDirectory(dir.resolve("dailytran"));
        assertThat(posttran(notAFile, "2022-06-10")).isEqualTo(99);
        assertThat(log.captured()).containsExactly("START OF EXECUTION OF PROGRAM CBTRN02C", "ERROR READING DALYTRAN FILE",
            "ABENDING PROGRAM");
        assertThat(Files.exists(rejects())).isFalse();
    }

    @Test
    @Covers({"ERR-CBTRN02C-03"})
    void posttran_abends_when_a_reject_cannot_be_written() throws IOException {
        refuseWrites("posting_reject", "1 = 0");
        assertThat(posttran(daily(DailyRecords.sample()), "2022-06-10")).isEqualTo(99);
        assertThat(tail(2)).containsExactly("ERROR WRITING TO REJECTS FILE", "ABENDING PROGRAM");
        assertThat(count("SELECT COUNT(*) FROM posting_reject")).isZero();
        assertThat(Files.exists(rejects())).isFalse();
    }

    @Test
    @Covers({"ERR-CBTRN02C-04"})
    void posttran_abends_when_category_balances_cannot_be_read_written_or_rewritten() throws IOException {
        String purchase = DailyRecords.sample("0000000000683580");
        unavailable("tran_cat_balance");
        assertThat(posttran(daily(List.of(purchase)), "2022-06-10")).isEqualTo(99);
        assertThat(log.captured()).containsExactly("START OF EXECUTION OF PROGRAM CBTRN02C",
            "ERROR READING TRANSACTION BALANCE FILE", "ABENDING PROGRAM");
        restoreTables();
        renamed.clear();

        refuseWrites("tran_cat_balance", "type_cd <> '03'");
        log.startCapture();
        assertThat(posttran(daily(List.of(DailyRecords.withType(purchase, "03", 2))), "2022-06-11")).isEqualTo(99);
        assertThat(log.captured()).containsExactly("START OF EXECUTION OF PROGRAM CBTRN02C",
            "TCATBAL record not found for key : 00000000007030002.. Creating.", "ERROR WRITING TRANSACTION BALANCE FILE",
            "ABENDING PROGRAM");
        restoreTables();
        refused.clear();

        refuseWrites("tran_cat_balance", "1 = 0");
        log.startCapture();
        assertThat(posttran(daily(List.of(purchase)), "2022-06-12")).isEqualTo(99);
        assertThat(log.captured()).containsExactly("START OF EXECUTION OF PROGRAM CBTRN02C",
            "ERROR REWRITING TRANSACTION BALANCE FILE", "ABENDING PROGRAM");
        assertThat(count("SELECT COUNT(*) FROM card_transaction WHERE tran_id = '0000000000683580'")).isZero();
    }

    @Test
    @Covers({"Q-CBTRN02C-06", "B-ABEND"})
    void posttran_abends_on_a_failed_lookup_instead_of_posting_to_the_previous_account() throws IOException {
        Path one = daily(List.of(DailyRecords.sample("0000000000683580")));
        unavailable("card_xref");
        assertThat(posttran(one, "2022-06-10")).isEqualTo(99);
        assertThat(log.captured()).containsExactly("START OF EXECUTION OF PROGRAM CBTRN02C", "ABENDING PROGRAM");
        restoreTables();
        renamed.clear();

        unavailable("account");
        log.startCapture();
        assertThat(posttran(one, "2022-06-11")).isEqualTo(99);
        assertThat(log.captured()).containsExactly("START OF EXECUTION OF PROGRAM CBTRN02C", "ABENDING PROGRAM");
        assertThat(count("SELECT COUNT(*) FROM card_transaction WHERE tran_id = '0000000000683580'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM posting_reject")).isZero();
    }

    // ---- TRANREPT (CBTRN03C) ----

    int tranrept(Path report) {
        return cli.execute("TRANREPT", "startDate=2000-01-01", "endDate=2099-12-31", "reportFile=" + report);
    }

    @Test
    @Covers({"ERR-CBTRN03C-01"})
    void tranrept_abends_when_the_report_file_cannot_be_opened() {
        assertThat(tranrept(dir.resolve("missing").resolve("TRANREPT.txt"))).isEqualTo(99);
        assertThat(log.captured()).containsExactly("START OF EXECUTION OF PROGRAM CBTRN03C", "ERROR OPENING REPTFILE",
            "ABENDING PROGRAM");
    }

    @Test
    @Covers({"ERR-CBTRN03C-03"})
    void tranrept_abends_when_the_transactions_cannot_be_read() throws IOException {
        Path report = dir.resolve("TRANREPT.txt");
        unavailable("card_transaction");
        assertThat(tranrept(report)).isEqualTo(99);
        assertThat(log.captured()).containsExactly("START OF EXECUTION OF PROGRAM CBTRN03C",
            "Reporting from 2000-01-01 to 2099-12-31", "ERROR READING TRANSACTION FILE", "ABENDING PROGRAM");
        assertThat(Files.list(dir)).isEmpty();
    }

    @Test
    @Covers({"B-ABEND"})
    void tranrept_abends_when_the_cross_reference_cannot_be_read() throws IOException {
        postSample("2022-06-10");
        unavailable("card_xref");
        assertThat(tranrept(dir.resolve("TRANREPT.txt"))).isEqualTo(99);
        assertThat(tail(2)).containsExactly("ERROR READING CROSS REF FILE", "ABENDING PROGRAM");
        assertThat(Files.exists(dir.resolve("TRANREPT.txt"))).isFalse();
    }

    @Test
    @Covers({"ERR-CBTRN03C-05"})
    void tranrept_abends_when_the_report_cannot_be_written() throws IOException {
        assumeTrue(Files.isWritable(DEV_FULL), "/dev/full is needed to make writes fail");
        postSample("2022-06-10");
        Path report = dir.resolve("TRANREPT.txt");
        Files.createSymbolicLink(dir.resolve("TRANREPT.txt.part"), DEV_FULL);
        assertThat(tranrept(report)).isEqualTo(99);
        assertThat(tail(2)).containsExactly("ERROR WRITING REPTFILE", "ABENDING PROGRAM");
        assertThat(Files.exists(report)).isFalse();
    }

    @Test
    @Covers({"ERR-CBTRN03C-06"})
    void tranrept_abends_when_the_finished_report_cannot_be_put_in_place() throws IOException {
        Path report = dir.resolve("TRANREPT.txt");
        Files.createDirectories(report.resolve("occupied"));
        assertThat(tranrept(report)).isEqualTo(99);
        assertThat(tail(2)).containsExactly("ERROR CLOSING REPORT FILE", "ABENDING PROGRAM");
        assertThat(Files.exists(dir.resolve("TRANREPT.txt.part"))).isFalse();
    }

    // ---- CREASTMT (CBSTM03A/CBSTM03B) ----

    int creastmt(Path stmt, Path html) {
        return cli.execute("CREASTMT", "businessDate=2022-06-10", "stmtFile=" + stmt, "htmlFile=" + html);
    }

    void assertNoStatementRun() throws IOException {
        assertThat(count("SELECT COUNT(*) FROM statement_run")).isZero();
        assertThat(count("SELECT COUNT(*) FROM card_transaction WHERE statement_run_id IS NOT NULL")).isZero();
        try (var files = Files.walk(dir)) {
            assertThat(files.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    @Covers({"ERR-CBSTM03A-02", "ERR-CBSTM03A-03", "B-ABEND"})
    void creastmt_abends_when_the_transactions_cannot_be_read() throws IOException {
        unavailable("card_transaction");
        assertThat(creastmt(dir.resolve("STMT.txt"), dir.resolve("STMT.html"))).isEqualTo(99);
        assertThat(log.captured()).containsExactly("ERROR READING TRNXFILE", "ABENDING PROGRAM");
        restoreTables();
        renamed.clear();
        assertNoStatementRun();
    }

    @Test
    @Covers({"ERR-CBSTM03A-04"})
    void creastmt_abends_when_the_cross_reference_cannot_be_read() throws IOException {
        unavailable("card_xref");
        assertThat(creastmt(dir.resolve("STMT.txt"), dir.resolve("STMT.html"))).isEqualTo(99);
        assertThat(log.captured()).containsExactly("ERROR READING XREFFILE", "ABENDING PROGRAM");
        assertNoStatementRun();
    }

    @Test
    @Covers({"ERR-CBSTM03B-01", "Q-CBSTM03B-04"})
    void a_customer_lookup_failure_is_reported_by_the_statement_job_not_by_the_lookup() throws IOException {
        unavailable("customer");
        assertThat(creastmt(dir.resolve("STMT.txt"), dir.resolve("STMT.html"))).isEqualTo(99);
        assertThat(log.captured()).containsExactly("ERROR READING CUSTFILE", "ABENDING PROGRAM");
        assertNoStatementRun();
    }

    @Test
    @Covers({"ERR-CBSTM03A-09", "B-ABEND"})
    void creastmt_abends_without_a_message_when_a_statement_file_cannot_be_opened_or_written() throws IOException {
        assertThat(creastmt(dir.resolve("missing").resolve("STMT.txt"), dir.resolve("STMT.html"))).isEqualTo(99);
        assertThat(log.captured()).containsExactly("ABENDING PROGRAM");
        assertNoStatementRun();

        assumeTrue(Files.isWritable(DEV_FULL), "/dev/full is needed to make writes fail");
        Path link = Files.createSymbolicLink(dir.resolve("STMT.txt.part"), DEV_FULL);
        log.startCapture();
        assertThat(creastmt(dir.resolve("STMT.txt"), dir.resolve("STMT.html"))).isEqualTo(99);
        assertThat(log.captured()).containsExactly("ABENDING PROGRAM");
        assertThat(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertNoStatementRun();
    }

    @Test
    @Covers({"Q-CBSTM03A-01"})
    void creastmt_logs_its_input_and_output_locations_instead_of_the_dd_names() {
        Path stmt = dir.resolve("STMT.txt");
        Path html = dir.resolve("STMT.html");
        PrintStream err = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
        int rc;
        try {
            rc = creastmt(stmt, html);
        } finally {
            System.setErr(err);
        }
        assertThat(rc).isZero();
        assertThat(buf.toString(StandardCharsets.UTF_8)).contains("STMTFILE=" + stmt, "HTMLFILE=" + html, "card_xref, customer, account");
        assertThat(log.captured()).noneMatch(l -> l.startsWith("Running JCL") || l.startsWith("DD Names") || l.contains("valid UCB"));
    }
}
