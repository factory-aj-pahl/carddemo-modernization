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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** TRANVERIFY (CBTRN01C) through the batch command line (spec/programs/CBTRN01C.md). */
@SpringBootTest(classes = {BatchApplication.class, TestDatabaseConfig.class, SampleData.class})
class TranverifyJobTest extends CycleJobTestSupport {

    Path daily(List<String> records) throws IOException {
        Path f = dir.resolve("dailytran.txt");
        Files.write(f, records, StandardCharsets.ISO_8859_1);
        return f;
    }

    @Test
    @Covers({"TS-CBTRN01C-01", "BR-CBTRN01C-01", "BR-CBTRN01C-02", "BR-CBTRN01C-04", "BR-CBTRN01C-05", "Q-CBTRN01C-01",
        "Q-CBTRN01C-04", "Q-CBTRN01C-05", "Q-CBTRN01C-06"})
    void sample_file_is_checked_with_masked_cards_and_nothing_written() throws IOException {
        int tables = count("SELECT COUNT(*) FROM card_transaction") + count("SELECT COUNT(*) FROM posting_reject");
        assertThat(cli.execute("TRANVERIFY", "dailyFile=" + daily(DailyRecords.sample()))).isZero();
        List<String> out = log.captured();
        assertThat(out).hasSize(1802);
        assertThat(out.get(0)).isEqualTo("START OF EXECUTION OF PROGRAM CBTRN01C");
        assertThat(out.get(1801)).isEqualTo("END OF EXECUTION OF PROGRAM CBTRN01C");
        assertThat(out).filteredOn(l -> l.equals("SUCCESSFUL READ OF XREF")).hasSize(300);
        assertThat(out).filteredOn(l -> l.startsWith("CARD NUMBER: ")).allSatisfy(l -> assertThat(l).matches("CARD NUMBER: \\*{12}\\d{4}"));
        assertThat(out.get(1).substring(262, 274)).isEqualTo("*".repeat(12));
        assertThat(out.subList(2, 7)).containsExactly("SUCCESSFUL READ OF XREF", "CARD NUMBER: ************7065",
            "ACCOUNT ID : 00000000007", "CUSTOMER ID: 000000007", "SUCCESSFUL READ OF ACCOUNT FILE");
        assertThat(count("SELECT COUNT(*) FROM card_transaction") + count("SELECT COUNT(*) FROM posting_reject")).isEqualTo(tables);
    }

    @Test
    @Covers({"TS-CBTRN01C-02", "TS-CBTRN01C-03", "BR-CBTRN01C-03", "BR-CBTRN01C-04"})
    void unknown_card_and_unknown_account_are_reported_and_skipped() throws IOException {
        String rec = DailyRecords.sample().get(0);
        String unknownCard = DailyRecords.withTranId(DailyRecords.withCard(rec, "1111222233331111"), "T000000000000001");
        jdbc.update("DELETE FROM account WHERE account_id = 7");
        assertThat(cli.execute("TRANVERIFY", "dailyFile=" + daily(List.of(unknownCard, rec)))).isZero();
        assertThat(log.captured()).containsSubsequence("INVALID CARD NUMBER FOR XREF",
            "CARD NUMBER ************1111 COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-T000000000000001",
            "INVALID ACCOUNT NUMBER FOUND", "ACCOUNT 00000000007 NOT FOUND", "END OF EXECUTION OF PROGRAM CBTRN01C");
    }

    @Test
    @Covers({"TS-CBTRN01C-04", "Q-CBTRN01C-01"})
    void an_empty_file_prints_only_the_start_and_end_messages() throws IOException {
        assertThat(cli.execute("TRANVERIFY", "dailyFile=" + daily(List.of()))).isZero();
        assertThat(log.captured()).containsExactly("START OF EXECUTION OF PROGRAM CBTRN01C", "END OF EXECUTION OF PROGRAM CBTRN01C");
    }

    @Test
    @Covers({"ERR-CBTRN01C-01", "ERR-CBTRN01C-05", "B-ABEND"})
    void a_missing_daily_file_abends() {
        assertThat(cli.execute("TRANVERIFY", "dailyFile=" + dir.resolve("missing.txt"))).isEqualTo(99);
        assertThat(log.captured()).containsExactly("START OF EXECUTION OF PROGRAM CBTRN01C", "ERROR OPENING DAILY TRANSACTION FILE",
            "FILE STATUS IS: NNNN0035", "ABENDING PROGRAM");
    }
}
