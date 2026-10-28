package com.carddemo.dataexchange.internal;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.batch.JobLog;
import com.carddemo.dataexchange.api.AccountExtractFiles;
import com.carddemo.dataexchange.api.JobResult;
import com.carddemo.dataexchange.internal.Fixtures.FailingSinks;
import com.carddemo.dataexchange.internal.Fixtures.StubFile;
import com.carddemo.test.Covers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** JOB-READACCT (spec/programs/CBACT01C.md) with the ledger fixes Q-CBACT01C-01, -02, -04, -06, -07, -08. */
class AccountExtractJobTest {

    @TempDir
    Path dir;

    JobLog log = new JobLog();
    AccountExtractFiles files;

    @BeforeEach
    void setUp() {
        log.startCapture();
        files = new AccountExtractFiles(dir.resolve("pscomp"), dir.resolve("arryps"), dir.resolve("vbps"));
    }

    JobResult run(StubFile accounts, Sinks sinks) {
        return new AccountExtractJob(log, sinks).run(accounts, files);
    }

    static byte[] hex(String h) {
        return HexFormat.of().parseHex(h);
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    static byte[] ascii(String s) {
        return s.getBytes(ISO_8859_1);
    }

    /** Splits a RECFM=VB file into its record data, checking each RDW. */
    static List<String> variableRecords(byte[] file) {
        List<String> recs = new ArrayList<>();
        int off = 0;
        while (off < file.length) {
            int len = ((file[off] & 0xFF) << 8) | (file[off + 1] & 0xFF);
            assertThat(file[off + 2]).isZero();
            assertThat(file[off + 3]).isZero();
            recs.add(new String(file, off + 4, len - 4, ISO_8859_1));
            off += len;
        }
        return recs;
    }

    @Test
    @Covers({"TS-CBACT01C-01", "BR-CBACT01C-01", "BR-CBACT01C-07", "Q-CBACT01C-09", "Q-CBACT01C-06"})
    void listing_shows_labelled_fields_vbrc_records_and_raw_record_for_every_account_in_key_order() throws IOException {
        List<String> accounts = Fixtures.accounts();
        JobResult r = run(new StubFile(accounts), new FileSinks());
        assertThat(r.abended()).isFalse();
        List<String> out = log.captured();
        assertThat(out).hasSize(2 + 50 * 15);
        assertThat(out.get(0)).isEqualTo("START OF EXECUTION OF PROGRAM CBACT01C");
        assertThat(out.get(out.size() - 1)).isEqualTo("END OF EXECUTION OF PROGRAM CBACT01C");
        assertThat(out.subList(1, 16)).containsExactly(
            "ACCT-ID                 :00000000001",
            "ACCT-ACTIVE-STATUS      :Y",
            "ACCT-CURR-BAL           :00000001940{",
            "ACCT-CREDIT-LIMIT       :00000020200{",
            "ACCT-CASH-CREDIT-LIMIT  :00000010200{",
            "ACCT-OPEN-DATE          :2014-11-20",
            "ACCT-EXPIRAION-DATE     :2025-05-20",
            "ACCT-REISSUE-DATE       :2025-05-20",
            "ACCT-CURR-CYC-CREDIT    :00000000000{",
            "ACCT-CURR-CYC-DEBIT     :00000000000{",
            "ACCT-GROUP-ID           :A000000000",
            "-------------------------------------------------",
            "VBRC-REC1:00000000001Y",
            "VBRC-REC2:00000000001" + "00000001940{" + "00000020200{" + "2025",
            accounts.get(0));
        for (int i = 0; i < 50; i++) {
            assertThat(out.get(1 + i * 15)).isEqualTo("ACCT-ID                 :" + accounts.get(i).substring(0, 11));
            assertThat(out.get(15 + i * 15)).hasSize(300).isEqualTo(accounts.get(i));
        }
        assertThat(Files.size(files.outFile())).isEqualTo(50L * 107);
        assertThat(Files.size(files.arrayFile())).isEqualTo(50L * 110);
        List<String> vb = variableRecords(Files.readAllBytes(files.vbrcFile()));
        assertThat(vb).hasSize(100);
        for (int i = 0; i < 50; i++) {
            assertThat(vb.get(2 * i)).hasSize(12).startsWith(accounts.get(i).substring(0, 11));
            assertThat(vb.get(2 * i + 1)).hasSize(39).startsWith(accounts.get(i).substring(0, 11));
        }
    }

    @Test
    @Covers({"TS-CBACT01C-02", "BR-CBACT01C-02", "BR-CBACT01C-03", "BR-CBACT01C-04", "Q-CBACT01C-02", "Q-CBACT01C-07",
        "Q-CBACT01C-08"})
    void out_record_has_compact_reissue_date_with_two_spaces_packed_debit_and_group_id() throws IOException {
        run(new StubFile(Fixtures.accounts().subList(0, 1)), new FileSinks());
        byte[] expected = concat(ascii("00000000001Y00000001940{00000020200{00000010200{2014-11-202025-05-20"),
            ascii("20250520  "), ascii("00000000000{"), hex("0000000252500C"), ascii("A000000000"));
        assertThat(expected).hasSize(107);
        assertThat(Files.readAllBytes(files.outFile())).isEqualTo(expected);
    }

    @Test
    @Covers({"TS-CBACT01C-03", "BR-CBACT01C-05", "Q-CBACT01C-03"})
    void array_record_carries_the_balance_and_the_demonstration_constants() throws IOException {
        run(new StubFile(Fixtures.accounts().subList(0, 1)), new FileSinks());
        byte[] expected = concat(ascii("00000000001"),
            ascii("00000001940{"), hex("0000000100500C"),
            ascii("00000001940{"), hex("0000000152500C"),
            ascii("00000010250}"), hex("0000000250000D"),
            ascii("00000000000{"), hex("0000000000000C"),
            ascii("00000000000{"), hex("0000000000000C"),
            ascii("    "));
        assertThat(expected).hasSize(110);
        assertThat(Files.readAllBytes(files.arrayFile())).isEqualTo(expected);
    }

    @Test
    @Covers({"TS-CBACT01C-04", "BR-CBACT01C-06"})
    void vbrc_file_gets_a_12_byte_and_a_39_byte_variable_record() throws IOException {
        run(new StubFile(Fixtures.accounts().subList(0, 1)), new FileSinks());
        byte[] expected = concat(hex("00100000"), ascii("00000000001Y"),
            hex("002B0000"), ascii("00000000001" + "00000001940{" + "00000020200{" + "2025"));
        assertThat(Files.readAllBytes(files.vbrcFile())).isEqualTo(expected);
    }

    /**
     * The spec scenario expects 2525.00 for both records (the stale COBOL field); the ledger FIX for
     * Q-CBACT01C-01 keeps 2525.00 only for a zero debit and writes a non-zero debit as itself.
     */
    @Test
    @Covers({"TS-CBACT01C-05", "BR-CBACT01C-04", "Q-CBACT01C-01"})
    void zero_cycle_debit_writes_2525_and_non_zero_debit_writes_the_real_amount() throws IOException {
        List<String> accounts = Fixtures.accounts();
        String second = accounts.get(1).substring(0, 90) + "00000000500{" + accounts.get(1).substring(102);
        String third = accounts.get(2).substring(0, 90) + "00000001234M" + accounts.get(2).substring(102);
        run(new StubFile(List.of(accounts.get(0), second, third)), new FileSinks());
        byte[] out = Files.readAllBytes(files.outFile());
        assertThat(out).hasSize(3 * 107);
        assertThat(java.util.Arrays.copyOfRange(out, 90, 97)).isEqualTo(hex("0000000252500C"));
        assertThat(java.util.Arrays.copyOfRange(out, 107 + 90, 107 + 97)).isEqualTo(hex("0000000005000C"));
        assertThat(java.util.Arrays.copyOfRange(out, 214 + 90, 214 + 97)).isEqualTo(hex("0000000012344D"));
    }

    @Test
    @Covers({"TS-CBACT01C-06", "ERR-CBACT01C-01", "ERR-CBACT01C-05", "B-ABEND"})
    void outfile_that_cannot_be_allocated_abends_with_status_37() {
        Sinks sinks = path -> {
            if (path.equals(files.outFile())) {
                throw new IOException("cannot allocate");
            }
            return new FileSinks().create(path);
        };
        JobResult r = run(new StubFile(Fixtures.accounts()), sinks);
        assertThat(r.abended()).isTrue();
        assertThat(log.captured()).containsExactly("START OF EXECUTION OF PROGRAM CBACT01C", "ERROR OPENING OUTFILE37",
            "FILE STATUS IS: NNNN0037", "ABENDING PROGRAM");
        assertThat(files.arrayFile()).doesNotExist();
    }

    @Test
    @Covers({"ERR-CBACT01C-01"})
    void account_file_open_failure_abends_before_any_output_exists() {
        JobResult r = run(new StubFile(Fixtures.accounts()).failOpen("35"), new FileSinks());
        assertThat(r.abended()).isTrue();
        assertThat(log.captured()).containsExactly("START OF EXECUTION OF PROGRAM CBACT01C", "ERROR OPENING ACCTFILE",
            "FILE STATUS IS: NNNN0035", "ABENDING PROGRAM");
        assertThat(files.outFile()).doesNotExist();
    }

    @Test
    @Covers({"ERR-CBACT01C-01"})
    void array_and_vbrc_open_failures_name_their_file_and_delete_the_outputs_already_created() {
        for (Path failing : List.of(files.arrayFile(), files.vbrcFile())) {
            log.startCapture();
            Sinks sinks = path -> {
                if (path.equals(failing)) {
                    throw new IOException("cannot allocate");
                }
                return new FileSinks().create(path);
            };
            assertThat(run(new StubFile(Fixtures.accounts()), sinks).abended()).isTrue();
            String message = failing.equals(files.arrayFile()) ? "ERROR OPENING ARRAYFILE37" : "ERROR OPENING VBRC FILE37";
            assertThat(log.captured()).containsExactly("START OF EXECUTION OF PROGRAM CBACT01C", message,
                "FILE STATUS IS: NNNN0037", "ABENDING PROGRAM");
            assertThat(files.outFile()).doesNotExist();
        }
    }

    @Test
    @Covers({"TS-CBACT01C-07", "ERR-CBACT01C-02", "B-ABEND"})
    void account_read_failure_30_abends_and_deletes_the_outputs() {
        JobResult r = run(new StubFile(Fixtures.accounts()).failRead(1, "30"), new FileSinks());
        assertThat(r.abended()).isTrue();
        List<String> out = log.captured();
        assertThat(out).hasSize(1 + 15 + 3);
        assertThat(out.subList(16, 19)).containsExactly("ERROR READING ACCOUNT FILE", "FILE STATUS IS: NNNN0030", "ABENDING PROGRAM");
        assertThat(files.outFile()).doesNotExist();
        assertThat(files.arrayFile()).doesNotExist();
        assertThat(files.vbrcFile()).doesNotExist();
    }

    @Test
    @Covers({"TS-CBACT01C-08", "ERR-CBACT01C-03", "Q-CBACT01C-04", "Q-CBACT01C-05"})
    void array_file_out_of_space_abends_with_status_34_and_deletes_the_outputs() {
        FailingSinks sinks = new FailingSinks(files.arrayFile(), 0, "No space left on device");
        JobResult r = run(new StubFile(Fixtures.accounts()), sinks);
        assertThat(r.abended()).isTrue();
        List<String> out = log.captured();
        assertThat(out.subList(out.size() - 3, out.size())).containsExactly("ACCOUNT FILE WRITE STATUS IS:34",
            "FILE STATUS IS: NNNN0034", "ABENDING PROGRAM");
        assertThat(out).doesNotContain("END OF EXECUTION OF PROGRAM CBACT01C");
        assertThat(sinks.closed).containsExactlyInAnyOrder(files.outFile(), files.arrayFile(), files.vbrcFile());
        assertThat(files.outFile()).doesNotExist();
        assertThat(files.arrayFile()).doesNotExist();
        assertThat(files.vbrcFile()).doesNotExist();
    }

    @Test
    @Covers({"ERR-CBACT01C-03"})
    void outfile_and_vbrc_write_failures_use_the_same_message_with_status_30() {
        for (Path failing : List.of(files.outFile(), files.vbrcFile())) {
            log.startCapture();
            JobResult r = run(new StubFile(Fixtures.accounts()), new FailingSinks(failing, 1, "I/O error"));
            assertThat(r.abended()).isTrue();
            List<String> out = log.captured();
            assertThat(out.subList(out.size() - 3, out.size())).containsExactly("ACCOUNT FILE WRITE STATUS IS:30",
                "FILE STATUS IS: NNNN0030", "ABENDING PROGRAM");
        }
    }

    @Test
    @Covers({"ERR-CBACT01C-04"})
    void account_close_failure_abends_after_all_records() {
        JobResult r = run(new StubFile(Fixtures.accounts()).failClose("30"), new FileSinks());
        assertThat(r.abended()).isTrue();
        List<String> out = log.captured();
        assertThat(out).hasSize(1 + 50 * 15 + 3);
        assertThat(out.subList(751, 754)).containsExactly("ERROR CLOSING ACCOUNT FILE", "FILE STATUS IS: NNNN0030", "ABENDING PROGRAM");
        assertThat(files.outFile()).doesNotExist();
    }

    @Test
    @Covers({"Q-CBACT01C-08"})
    void invalid_reissue_date_abends_instead_of_writing_garbage() {
        List<String> accounts = Fixtures.accounts();
        String bad = accounts.get(0).substring(0, 68) + "2025-02-30" + accounts.get(0).substring(78);
        JobResult r = run(new StubFile(List.of(bad)), new FileSinks());
        assertThat(r.abended()).isTrue();
        List<String> out = log.captured();
        assertThat(out.get(out.size() - 1)).isEqualTo("ABENDING PROGRAM");
        assertThat(files.outFile()).doesNotExist();
    }

    @Test
    @Covers({"BR-CBACT01C-07"})
    void empty_account_file_creates_empty_outputs() throws IOException {
        JobResult r = run(new StubFile(List.of()), new FileSinks());
        assertThat(r.abended()).isFalse();
        assertThat(log.captured()).containsExactly("START OF EXECUTION OF PROGRAM CBACT01C", "END OF EXECUTION OF PROGRAM CBACT01C");
        assertThat(Files.size(files.outFile())).isZero();
        assertThat(Files.size(files.arrayFile())).isZero();
        assertThat(Files.size(files.vbrcFile())).isZero();
    }
}
