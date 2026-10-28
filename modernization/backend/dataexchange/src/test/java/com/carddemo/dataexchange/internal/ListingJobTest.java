package com.carddemo.dataexchange.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.batch.JobLog;
import com.carddemo.dataexchange.api.JobResult;
import com.carddemo.dataexchange.internal.Fixtures.StubFile;
import com.carddemo.test.Covers;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** JOB-READCARD, JOB-READXREF, JOB-READCUST (spec/programs CBACT02C, CBACT03C, CBCUS01C). */
class ListingJobTest {

    JobLog log = new JobLog();
    ListingJob job = new ListingJob(log);

    @BeforeEach
    void capture() {
        log.startCapture();
    }

    List<String> run(ListingJob.Spec spec, StubFile file, boolean abended) {
        JobResult r = job.run(spec, file);
        assertThat(r.abended()).isEqualTo(abended);
        return log.captured();
    }

    // ---- CBACT02C ----

    @Test
    @Covers({"TS-CBACT02C-01", "BR-CBACT02C-01", "BR-CBACT02C-02", "TS-CBACT02C-05", "Q-CBACT02C-01", "Q-READCARD-01"})
    void card_listing_shows_every_card_once_in_key_order_with_card_number_and_cvv_masked() {
        List<String> cards = Fixtures.cards();
        List<String> out = run(ListingJob.CARDS, new StubFile(cards), false);
        assertThat(out).hasSize(52);
        assertThat(out.get(0)).isEqualTo("START OF EXECUTION OF PROGRAM CBACT02C");
        assertThat(out.get(51)).isEqualTo("END OF EXECUTION OF PROGRAM CBACT02C");
        assertThat(out.get(1)).hasSize(150).startsWith("************5740" + "00000000050" + "***" + "Aniya Von");
        for (int i = 0; i < 50; i++) {
            String src = cards.get(i);
            String line = out.get(i + 1);
            assertThat(line).isEqualTo("************" + src.substring(12, 27) + "***" + src.substring(30));
            assertThat(line).doesNotContain(src.substring(0, 16));
        }
        assertThat(out.get(1).substring(27, 30)).isEqualTo("***").isNotEqualTo(cards.get(0).substring(27, 30));
        assertThat(cards.get(0).substring(27, 30)).isEqualTo("747");
    }

    @Test
    @Covers({"TS-CBACT02C-02", "BR-CBACT02C-03"})
    void empty_card_file_shows_only_start_and_end_messages() {
        assertThat(run(ListingJob.CARDS, new StubFile(List.of()), false))
            .containsExactly("START OF EXECUTION OF PROGRAM CBACT02C", "END OF EXECUTION OF PROGRAM CBACT02C");
    }

    @Test
    @Covers({"TS-CBACT02C-03", "ERR-CBACT02C-01", "ERR-CBACT02C-04", "B-ABEND"})
    void card_file_open_failure_abends_with_status_0035() {
        assertThat(run(ListingJob.CARDS, new StubFile(Fixtures.cards()).failOpen("35"), true)).containsExactly(
            "START OF EXECUTION OF PROGRAM CBACT02C", "ERROR OPENING CARDFILE", "FILE STATUS IS: NNNN0035", "ABENDING PROGRAM");
    }

    @Test
    @Covers({"TS-CBACT02C-04", "ERR-CBACT02C-02", "ERR-CBACT02C-04"})
    void card_read_failure_93_abends_with_the_ebcdic_status_value() {
        List<String> out = run(ListingJob.CARDS, new StubFile(Fixtures.cards()).failRead(2, "93"), true);
        assertThat(out).hasSize(6);
        assertThat(out.subList(3, 6)).containsExactly("ERROR READING CARDFILE", "FILE STATUS IS: NNNN9243", "ABENDING PROGRAM");
    }

    @Test
    @Covers({"ERR-CBACT02C-03"})
    void card_close_failure_abends_after_the_listing() {
        List<String> out = run(ListingJob.CARDS, new StubFile(Fixtures.cards()).failClose("30"), true);
        assertThat(out).hasSize(54);
        assertThat(out.subList(51, 54)).containsExactly("ERROR CLOSING CARDFILE", "FILE STATUS IS: NNNN0030", "ABENDING PROGRAM");
    }

    // ---- CBACT03C ----

    @Test
    @Covers({"TS-CBACT03C-01", "BR-CBACT03C-01", "BR-CBACT03C-02", "Q-CBACT03C-01", "Q-CBACT03C-02", "Q-READCARD-01"})
    void xref_listing_shows_each_record_once_with_the_card_number_masked() {
        List<String> xrefs = Fixtures.xrefs();
        List<String> out = run(ListingJob.XREFS, new StubFile(xrefs), false);
        assertThat(out).hasSize(52);
        assertThat(out.get(0)).isEqualTo("START OF EXECUTION OF PROGRAM CBACT03C");
        assertThat(out.get(51)).isEqualTo("END OF EXECUTION OF PROGRAM CBACT03C");
        assertThat(out.get(1)).isEqualTo("************5740" + "000000050" + "00000000050" + " ".repeat(14));
        for (int i = 0; i < 50; i++) {
            assertThat(out.get(i + 1)).isEqualTo("************" + xrefs.get(i).substring(12));
        }
        assertThat(out.subList(1, 51)).doesNotHaveDuplicates();
    }

    @Test
    @Covers({"TS-CBACT03C-02", "BR-CBACT03C-03"})
    void empty_xref_file_shows_only_start_and_end_messages() {
        assertThat(run(ListingJob.XREFS, new StubFile(List.of()), false))
            .containsExactly("START OF EXECUTION OF PROGRAM CBACT03C", "END OF EXECUTION OF PROGRAM CBACT03C");
    }

    @Test
    @Covers({"TS-CBACT03C-03", "ERR-CBACT03C-01", "ERR-CBACT03C-04", "Q-CBACT03C-03"})
    void xref_open_failure_abends_with_status_0035() {
        assertThat(run(ListingJob.XREFS, new StubFile(List.of()).failOpen("35"), true)).containsExactly(
            "START OF EXECUTION OF PROGRAM CBACT03C", "ERROR OPENING XREFFILE", "FILE STATUS IS: NNNN0035", "ABENDING PROGRAM");
    }

    @Test
    @Covers({"TS-CBACT03C-04", "ERR-CBACT03C-02"})
    void xref_read_failure_30_abends() {
        assertThat(run(ListingJob.XREFS, new StubFile(Fixtures.xrefs()).failRead(0, "30"), true)).containsExactly(
            "START OF EXECUTION OF PROGRAM CBACT03C", "ERROR READING XREFFILE", "FILE STATUS IS: NNNN0030", "ABENDING PROGRAM");
    }

    @Test
    @Covers({"ERR-CBACT03C-03"})
    void xref_close_failure_abends() {
        List<String> out = run(ListingJob.XREFS, new StubFile(List.of()).failClose("30"), true);
        assertThat(out).containsExactly("START OF EXECUTION OF PROGRAM CBACT03C", "ERROR CLOSING XREFFILE",
            "FILE STATUS IS: NNNN0030", "ABENDING PROGRAM");
    }

    // ---- CBCUS01C ----

    @Test
    @Covers({"TS-CBCUS01C-01", "BR-CBCUS01C-01", "BR-CBCUS01C-02", "Q-CBCUS01C-01", "Q-CBCUS01C-02", "Q-READCARD-01"})
    void customer_listing_shows_each_customer_once_with_ssn_id_dob_and_eft_masked() {
        List<String> customers = Fixtures.customers();
        List<String> out = run(ListingJob.CUSTOMERS, new StubFile(customers), false);
        assertThat(out).hasSize(52);
        assertThat(out.get(0)).isEqualTo("START OF EXECUTION OF PROGRAM CBCUS01C");
        assertThat(out.get(51)).isEqualTo("END OF EXECUTION OF PROGRAM CBCUS01C");
        String first = out.get(1);
        String src = customers.get(0);
        assertThat(first).hasSize(500).startsWith("000000001Immanuel");
        assertThat(first.substring(0, 279)).isEqualTo(src.substring(0, 279));
        assertThat(first.substring(279, 288)).isEqualTo("*****" + src.substring(284, 288));
        assertThat(first.substring(288, 318)).isEqualTo("*".repeat(30));
        assertThat(first.substring(318, 328)).isEqualTo("******" + src.substring(324, 328));
        assertThat(first.substring(328)).isEqualTo(src.substring(328));
        assertThat(first).doesNotContain(src.substring(279, 288)).doesNotContain(src.substring(308, 318));
        assertThat(out.subList(1, 51)).doesNotHaveDuplicates();
    }

    @Test
    @Covers({"TS-CBCUS01C-02", "BR-CBCUS01C-03"})
    void empty_customer_file_shows_only_start_and_end_messages() {
        assertThat(run(ListingJob.CUSTOMERS, new StubFile(List.of()), false))
            .containsExactly("START OF EXECUTION OF PROGRAM CBCUS01C", "END OF EXECUTION OF PROGRAM CBCUS01C");
    }

    @Test
    @Covers({"TS-CBCUS01C-03", "ERR-CBCUS01C-01", "ERR-CBCUS01C-04", "Q-CBCUS01C-05"})
    void customer_open_failure_abends_with_status_0035() {
        assertThat(run(ListingJob.CUSTOMERS, new StubFile(List.of()).failOpen("35"), true)).containsExactly(
            "START OF EXECUTION OF PROGRAM CBCUS01C", "ERROR OPENING CUSTFILE", "FILE STATUS IS: NNNN0035", "ABENDING PROGRAM");
    }

    @Test
    @Covers({"TS-CBCUS01C-04", "ERR-CBCUS01C-02"})
    void customer_read_failure_30_abends() {
        assertThat(run(ListingJob.CUSTOMERS, new StubFile(Fixtures.customers()).failRead(0, "30"), true)).containsExactly(
            "START OF EXECUTION OF PROGRAM CBCUS01C", "ERROR READING CUSTOMER FILE", "FILE STATUS IS: NNNN0030", "ABENDING PROGRAM");
    }

    @Test
    @Covers({"ERR-CBCUS01C-03"})
    void customer_close_failure_abends() {
        assertThat(run(ListingJob.CUSTOMERS, new StubFile(List.of()).failClose("30"), true)).containsExactly(
            "START OF EXECUTION OF PROGRAM CBCUS01C", "ERROR CLOSING CUSTOMER FILE", "FILE STATUS IS: NNNN0030", "ABENDING PROGRAM");
    }
}
