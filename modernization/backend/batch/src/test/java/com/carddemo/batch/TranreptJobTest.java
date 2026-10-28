package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.security.AccountScope;
import com.carddemo.test.Covers;
import com.carddemo.test.SampleData;
import com.carddemo.test.TestDatabaseConfig;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** JOB-TRANREPT (CBTRN03C) through the batch command line (spec/programs/CBTRN03C.md). */
@SpringBootTest(classes = {BatchApplication.class, TestDatabaseConfig.class, SampleData.class})
class TranreptJobTest extends CycleJobTestSupport {

    static final String ALL = "startDate=2000-01-01";
    static final String TO = "endDate=2099-12-31";

    @Autowired
    AccountScope scope;

    Path report() {
        return dir.resolve("TRANREPT.txt");
    }

    int tranrept(String... extra) {
        String[] args = new String[extra.length + 2];
        args[0] = "TRANREPT";
        args[1] = "reportFile=" + report();
        System.arraycopy(extra, 0, args, 2, extra.length);
        return cli.execute(args);
    }

    static String total(String label, int dots, String amount) {
        return String.format("%-133s", label + ".".repeat(dots) + amount);
    }

    void addTransaction(String id, String card, String type, int cat, String amount) {
        jdbc.update("""
            INSERT INTO card_transaction (tran_id, type_cd, cat_cd, source, description, amount, card_num, proc_ts_text, proc_ts)
            VALUES (?, ?, ?, 'POS TERM', 'test', ?, ?, '2022-06-10 10:00:00.000000', '2022-06-10T10:00:00')""",
            id, type, cat, new BigDecimal(amount), card);
    }

    @Test
    @Covers({"TS-CBTRN03C-01", "TS-CBTRN03C-02", "TS-CBTRN03C-03", "BR-CBTRN03C-02", "BR-CBTRN03C-03", "BR-CBTRN03C-04",
        "BR-CBTRN03C-05", "BR-CBTRN03C-06", "BR-CBTRN03C-07", "BR-CBTRN03C-08", "BR-CBTRN03C-09", "BR-CBTRN03C-10",
        "Q-CBTRN03C-02", "Q-CBTRN03C-03", "Q-CBTRN03C-04", "Q-CBTRN03C-05", "Q-CBTRN03C-08", "Q-CBTRN03C-09", "Q-TRANREPT-04"})
    void posted_sample_report_has_the_fixed_end_totals() throws IOException {
        postSample("2022-06-10");
        assertThat(tranrept(ALL, TO)).isZero();
        List<String> r = lines(report());
        assertThat(r).hasSize(453).allSatisfy(l -> assertThat(l).hasSize(133));
        assertThat(r.get(0).stripTrailing()).isEqualTo(String.format("%-38s%-41sDate Range: 2000-01-01 to 2099-12-31", "DALYREPT",
            "Daily Transaction Report"));
        assertThat(r.get(1)).isBlank();
        assertThat(r.get(3)).isEqualTo("-".repeat(133));
        assertThat(r.get(4).stripTrailing()).isEqualTo(
            "0000000058866561 00000000050 01-Purchase        0001-Regular Sales Draft           POS TERM               183.88");
        assertThat(r.get(10)).isEqualTo(total("Account Total", 84, "+      1,453.87"));
        assertThat(r.get(20)).isEqualTo(total("Page Total ", 86, "+      4,665.52"));
        assertThat(r.get(21)).isEqualTo("-".repeat(133));
        assertThat(r.get(22)).isEqualTo(r.get(0));
        assertThat(r.subList(448, 453)).containsExactly(total("Account Total", 84, "+      1,609.10"), "-".repeat(133),
            total("Page Total ", 86, "+        959.99"), "-".repeat(133), total("Grand Total", 86, "+     77,954.70"));
        List<String> out = log.captured();
        assertThat(out).hasSize(267);
        assertThat(out.get(0)).isEqualTo("START OF EXECUTION OF PROGRAM CBTRN03C");
        assertThat(out.get(1)).isEqualTo("Reporting from 2000-01-01 to 2099-12-31");
        assertThat(out.subList(2, 264)).allSatisfy(l -> assertThat(l.substring(262, 274)).isEqualTo("*".repeat(12)));
        assertThat(out.subList(264, 267)).containsExactly("TRAN-AMT 00000076566+", "WS-PAGE-TOTAL00000095999+",
            "END OF EXECUTION OF PROGRAM CBTRN03C");
    }

    @Test
    @Covers({"Q-CBTRN03C-01", "BR-CBTRN03C-01", "Q-CBTRN03C-11", "Q-TRANREPT-02"})
    void transactions_outside_the_range_are_skipped_and_the_report_continues() throws IOException {
        postSample("2022-06-10");
        String first = jdbc.queryForObject("SELECT TOP 1 tran_id FROM card_transaction ORDER BY card_num, tran_id", String.class);
        jdbc.update("UPDATE card_transaction SET proc_ts = '1999-01-01T00:00:00', proc_ts_text = '1999-01-01 00:00:00.000000'"
            + " WHERE tran_id = ?", first);
        jdbc.update("UPDATE card_transaction SET proc_ts = NULL, proc_ts_text = '' WHERE tran_id = '0000000329724245'");
        assertThat(tranrept(ALL, TO)).isZero();
        List<String> r = lines(report());
        assertThat(r.stream().filter(l -> l.matches("^\\d{16} \\d{11} .*")).count()).isEqualTo(260);
        assertThat(r).noneMatch(l -> l.startsWith(first) || l.startsWith("0000000329724245"));
        assertThat(r.get(r.size() - 1)).startsWith("Grand Total");
    }

    @Test
    @Covers({"Q-CBTRN03C-07", "Q-CBTRN03C-06", "Q-TRANREPT-03"})
    void a_missing_invalid_or_reversed_range_fails_with_exit_12() {
        assertThat(tranrept("startDate=2022-01-01")).isEqualTo(12);
        assertThat(tranrept("startDate=2022-01-01", "endDate=2022-02-30")).isEqualTo(12);
        assertThat(tranrept("startDate=2022-03-01", "endDate=2022-01-31")).isEqualTo(12);
        assertThat(log.captured()).containsExactly("INVALID OR MISSING REPORT DATE RANGE", "INVALID OR MISSING REPORT DATE RANGE",
            "INVALID OR MISSING REPORT DATE RANGE");
        assertThat(Files.exists(report())).isFalse();
    }

    @Test
    @Covers({"Q-CBTRN03C-06", "D-REPORT"})
    void a_user_request_covers_only_the_linked_accounts_and_an_admin_request_covers_all() throws IOException {
        postSample("2022-06-10");
        Set<Long> linked = scope.linkedAccounts("USER0001");
        assertThat(linked).isNotEmpty();
        assertThat(tranrept(ALL, TO, "userId=USER0001")).isZero();
        List<String> details = lines(report()).stream().filter(l -> l.matches("^\\d{16} \\d{11} .*")).toList();
        assertThat(details).isNotEmpty().allSatisfy(l -> assertThat(linked).contains(Long.parseLong(l.substring(17, 28))));
        int expected = count("SELECT COUNT(*) FROM card_transaction t JOIN card_xref x ON x.card_num = t.card_num"
            + " JOIN user_account u ON u.account_id = x.account_id AND u.user_id = 'USER0001'");
        assertThat(details).hasSize(expected);

        assertThat(tranrept(ALL, TO, "userId=ADMIN001")).isZero();
        assertThat(lines(report())).hasSize(453);
        assertThat(tranrept(ALL, TO, "userId=NOBODY")).isZero();
        assertThat(lines(report())).hasSize(3);
    }

    @Test
    @Covers({"TS-CBTRN03C-08", "TS-CBTRN03C-04"})
    void an_empty_selection_gives_only_the_zero_totals() throws IOException {
        assertThat(tranrept(ALL, TO)).isZero();
        assertThat(lines(report())).containsExactly(total("Page Total ", 86, " ".repeat(15)), "-".repeat(133),
            total("Grand Total", 86, " ".repeat(15)));
        assertThat(log.captured()).containsExactly("START OF EXECUTION OF PROGRAM CBTRN03C",
            "Reporting from 2000-01-01 to 2099-12-31", "TRAN-AMT 00000000000+", "WS-PAGE-TOTAL00000000000+",
            "END OF EXECUTION OF PROGRAM CBTRN03C");
    }

    @Test
    @Covers({"TS-CBTRN03C-07", "ERR-CBTRN03C-04", "VR-CBTRN03C-01", "B-ABEND"})
    void an_unknown_transaction_type_abends_and_leaves_no_report() throws IOException {
        String card = jdbc.queryForObject("SELECT MIN(card_num) FROM card_xref", String.class);
        addTransaction("8000000000000001", card, "99", 1, "1.00");
        assertThat(tranrept(ALL, TO)).isEqualTo(99);
        List<String> out = log.captured();
        assertThat(out.subList(out.size() - 3, out.size())).containsExactly("INVALID TRANSACTION TYPE : 99",
            "FILE STATUS IS: NNNN0023", "ABENDING PROGRAM");
        assertThat(Files.list(dir)).isEmpty();
    }

    @Test
    @Covers({"ERR-CBTRN03C-04"})
    void a_card_without_cross_reference_abends_with_a_masked_number() {
        addTransaction("8000000000000001", "9999999999991234", "01", 1, "1.00");
        assertThat(tranrept(ALL, TO)).isEqualTo(99);
        List<String> out = log.captured();
        assertThat(out.subList(out.size() - 3, out.size())).containsExactly("INVALID CARD NUMBER : ************1234",
            "FILE STATUS IS: NNNN0023", "ABENDING PROGRAM");
    }

    @Test
    @Covers({"Q-CBTRN03C-10"})
    void a_total_too_large_for_its_picture_abends_instead_of_losing_digits() throws IOException {
        String card = jdbc.queryForObject("SELECT MIN(card_num) FROM card_xref", String.class);
        addTransaction("8000000000000001", card, "01", 1, "999999999.99");
        addTransaction("8000000000000002", card, "01", 1, "0.01");
        assertThat(tranrept(ALL, TO)).isEqualTo(99);
        List<String> out = log.captured();
        assertThat(out.get(out.size() - 1)).isEqualTo("ABENDING PROGRAM");
        assertThat(Files.list(dir)).isEmpty();
    }
}
