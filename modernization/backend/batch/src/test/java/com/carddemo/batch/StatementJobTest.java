package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.test.Covers;
import com.carddemo.test.SampleData;
import com.carddemo.test.TestDatabaseConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** JOB-CREASTMT (CBSTM03A/CBSTM03B) through the batch command line (spec/programs/CBSTM03A.md). */
@SpringBootTest(classes = {BatchApplication.class, TestDatabaseConfig.class, SampleData.class})
class StatementJobTest extends CycleJobTestSupport {

    Path stmt(String date) {
        return dir.resolve("stmt-" + date + ".txt");
    }

    Path html(String date) {
        return dir.resolve("stmt-" + date + ".html");
    }

    int creastmt(String date) {
        return cli.execute("CREASTMT", "businessDate=" + date, "stmtFile=" + stmt(date), "htmlFile=" + html(date));
    }

    String firstCard() {
        return jdbc.queryForObject("SELECT MIN(card_num) FROM card_xref", String.class);
    }

    void addTransaction(String id, String card, String desc, String amount) {
        jdbc.update("""
            INSERT INTO card_transaction (tran_id, type_cd, cat_cd, source, description, amount, card_num)
            VALUES (?, '01', 1, 'POS TERM', ?, ?, ?)""", id, desc, new java.math.BigDecimal(amount), card);
    }

    @Test
    @Covers({"TS-CBSTM03A-01", "TS-CBSTM03A-02", "TS-CBSTM03A-04", "BR-CBSTM03A-03", "BR-CBSTM03A-04", "BR-CBSTM03A-05",
        "BR-CBSTM03A-06", "BR-CBSTM03A-07", "BR-CBSTM03A-09", "BR-CBSTM03A-10", "BR-CBSTM03A-11", "BR-CBSTM03A-12",
        "Q-CBSTM03A-07", "Q-CBSTM03A-11", "BR-CBSTM03B-02", "BR-CBSTM03B-03"})
    void posted_sample_gives_one_text_and_html_statement_per_card() throws IOException {
        postSample("2022-06-10");
        assertThat(creastmt("2022-06-10")).isZero();
        assertThat(log.captured()).isEmpty();
        List<String> text = lines(stmt("2022-06-10"));
        List<String> html = lines(html("2022-06-10"));
        assertThat(text).hasSize(50 * 19 + 262).allSatisfy(l -> assertThat(l).hasSize(80));
        assertThat(html).hasSize(50 * 64 + 262 * 11).allSatisfy(l -> assertThat(l).hasSize(100));
        assertThat(html).filteredOn(l -> l.startsWith("<!DOCTYPE html>")).hasSize(50);
        assertThat(text.subList(0, 16).stream().map(String::stripTrailing).toList()).containsExactly(
            "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31), "Aniya Alba Von", "1588 Nienow Cape", "Suite 187",
            "New Aricchester OR USA 04257", "-".repeat(80), " ".repeat(33) + "Basic Details", "-".repeat(80),
            "Account ID         :00000000050", "Current Balance    :000001945.87", "FICO Score         :623", "-".repeat(80),
            " ".repeat(30) + "TRANSACTION SUMMARY", "-".repeat(80),
            "Tran ID         Tran Details                                         Tran Amount", "-".repeat(80));
        assertThat(text.get(16)).isEqualTo("0000000058866561 " + String.format("%-49s", "Purchase at Blick-Rippin") + "$      183.88 ");
        assertThat(text).contains(String.format("%-80s", "0000000577826814 " + String.format("%-49s",
            "Return item at DuBuque, Wuckert and Mraz") + "$       47.88-"));
        assertThat(text.get(23).stripTrailing()).isEqualTo("Total EXP:" + " ".repeat(56) + "$     1453.87");
        assertThat(text.get(24).stripTrailing()).isEqualTo("*".repeat(32) + "END OF STATEMENT" + "*".repeat(32));
        List<String> h = html.stream().map(String::stripTrailing).toList();
        assertThat(h.get(10)).isEqualTo("<h3>Statement for Account Number: 00000000050         </h3>");
        assertThat(h.subList(22, 26)).containsExactly("<p style=\"font-size:16px\">Aniya Alba Von  </p>", "<p>1588 Nienow Cape  </p>",
            "<p>Suite 187  </p>", "<p>New Aricchester OR USA 04257  </p>");
        assertThat(h.subList(35, 38)).containsExactly("<p>Account ID         : 00000000050         </p>",
            "<p>Current Balance    : 000001945.87 </p>", "<p>FICO Score         : 623                 </p>");
        assertThat(h.get(64)).isEqualTo("<p>      183.88 </p>");
        assertThat(count("SELECT COUNT(*) FROM card_transaction WHERE statement_run_id IS NULL")).isZero();
        assertThat(count("SELECT COUNT(*) FROM statement_run WHERE transaction_count = 262 AND statement_count = 50")).isEqualTo(1);
    }

    @Test
    @Covers({"Q-CREASTMT-04", "D-HISTORY", "Q-TRANBKP-02", "B-CYCLE"})
    void each_run_covers_only_transactions_no_earlier_run_covered() throws IOException {
        postSample("2022-06-10");
        assertThat(creastmt("2022-06-10")).isZero();
        addTransaction("9000000000000001", firstCard(), "Online purchase", "12.34");
        assertThat(creastmt("2022-06-13")).isZero();
        List<String> text = lines(stmt("2022-06-13"));
        assertThat(text).hasSize(50 * 19 + 1);
        assertThat(text).filteredOn(l -> l.startsWith("9000000000000001 Online purchase")).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM card_transaction")).isEqualTo(263);
        assertThat(count("SELECT COUNT(DISTINCT statement_run_id) FROM card_transaction")).isEqualTo(2);
        log.startCapture();
        assertThat(creastmt("2022-06-13")).isEqualTo(8);
        assertThat(log.captured()).isEmpty();
    }

    @Test
    @Covers({"Q-CBSTM03A-03", "TS-CBSTM03A-05", "TS-CBSTM03A-06", "Q-CBSTM03A-06"})
    void no_transactions_gives_statements_without_transaction_lines() throws IOException {
        assertThat(creastmt("2022-06-10")).isZero();
        List<String> text = lines(stmt("2022-06-10"));
        assertThat(text).hasSize(50 * 19);
        assertThat(text.get(17).stripTrailing()).isEqualTo("Total EXP:" + " ".repeat(56) + "$         .00");
        assertThat(lines(html("2022-06-10"))).hasSize(50 * 64);
    }

    @Test
    @Covers({"Q-CBSTM03A-04", "TS-CBSTM03A-11", "Q-CBSTM03A-05"})
    void a_card_with_more_than_ten_transactions_prints_them_all_in_id_order() throws IOException {
        for (int i = 12; i >= 1; i--) {
            addTransaction(String.format("80000000000000%02d", i), firstCard(), "Purchase " + i, "1.00");
        }
        addTransaction("7000000000000001", "9999999999999999", "Card not in xref", "5.00");
        assertThat(creastmt("2022-06-10")).isZero();
        List<String> text = lines(stmt("2022-06-10"));
        assertThat(text).hasSize(50 * 19 + 12);
        assertThat(text.subList(16, 28)).allSatisfy(l -> assertThat(l).startsWith("80000000000000"));
        assertThat(text.get(16)).startsWith("8000000000000001 Purchase 1 ");
        assertThat(text.get(27)).startsWith("8000000000000012 Purchase 12 ");
        assertThat(text.get(29).stripTrailing()).endsWith("$       12.00");
        assertThat(count("SELECT COUNT(*) FROM card_transaction WHERE statement_run_id IS NULL")).isZero();
    }

    @Test
    @Covers({"Q-CBSTM03A-10"})
    void values_in_the_html_are_escaped() throws IOException {
        jdbc.update("UPDATE customer SET first_name = 'A&B', last_name = '<Lee>' WHERE customer_id = 50");
        addTransaction("8000000000000001", firstCard(), "Fish & Chips <to go>", "3.00");
        assertThat(creastmt("2022-06-10")).isZero();
        List<String> h = lines(html("2022-06-10")).stream().map(String::stripTrailing).toList();
        assertThat(h.get(22)).isEqualTo("<p style=\"font-size:16px\">A&amp;B Alba &lt;Lee&gt;  </p>");
        assertThat(h).contains("<p>Fish &amp; Chips &lt;to go&gt;" + " ".repeat(29) + "</p>");
        assertThat(lines(stmt("2022-06-10")).get(1).stripTrailing()).isEqualTo("A&B Alba <Lee>");
    }

    @Test
    @Covers({"ERR-CBSTM03A-05", "ERR-CBSTM03A-08", "TS-CBSTM03A-07", "Q-CBSTM03A-14", "B-ABEND"})
    void a_missing_customer_abends_and_leaves_no_statement_files_or_marks() throws IOException {
        postSample("2022-06-10");
        jdbc.update("DELETE FROM customer WHERE customer_id = 25");
        assertThat(creastmt("2022-06-10")).isEqualTo(99);
        assertThat(log.captured()).containsExactly("ERROR READING CUSTFILE", "RETURN CODE: 23", "ABENDING PROGRAM");
        assertThat(Files.exists(stmt("2022-06-10"))).isFalse();
        assertThat(Files.exists(html("2022-06-10"))).isFalse();
        assertThat(Files.list(dir).filter(p -> p.toString().endsWith(".part"))).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM card_transaction WHERE statement_run_id IS NOT NULL")).isZero();
        assertThat(count("SELECT COUNT(*) FROM statement_run")).isZero();
    }

    @Test
    @Covers({"ERR-CBSTM03A-06", "TS-CBSTM03A-08"})
    void a_missing_account_abends() {
        jdbc.update("DELETE FROM account WHERE account_id = 3");
        assertThat(creastmt("2022-06-10")).isEqualTo(99);
        assertThat(log.captured()).containsExactly("ERROR READING ACCTFILE", "RETURN CODE: 23", "ABENDING PROGRAM");
    }

    @Test
    @Covers({"Q-CBSTM03A-09"})
    void a_balance_or_total_too_large_for_its_picture_abends_instead_of_losing_digits() {
        jdbc.update("UPDATE account SET current_balance = 1000000000.00 WHERE account_id = 50");
        assertThat(creastmt("2022-06-10")).isEqualTo(99);
        assertThat(log.captured()).containsExactly("ABENDING PROGRAM");
        jdbc.update("UPDATE account SET current_balance = 999999999.99 WHERE account_id = 50");
        addTransaction("8000000000000001", firstCard(), "Big one", "999999999.99");
        addTransaction("8000000000000002", firstCard(), "Big two", "0.01");
        log.startCapture();
        assertThat(creastmt("2022-06-10")).isEqualTo(99);
        assertThat(log.captured()).containsExactly("ABENDING PROGRAM");
        assertThat(count("SELECT COUNT(*) FROM statement_run")).isZero();
    }

    @Test
    @Covers({"B-DATE"})
    void missing_business_date_stops_with_exit_12() {
        assertThat(cli.execute("CREASTMT", "stmtFile=" + stmt("x"), "htmlFile=" + html("x"))).isEqualTo(12);
        assertThat(log.captured()).containsExactly("INVALID OR MISSING BUSINESS DATE");
    }
}
