package com.carddemo.dataexchange.internal;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.batch.JobLog;
import com.carddemo.common.codec.Overpunch;
import com.carddemo.dataexchange.api.ImportFiles;
import com.carddemo.dataexchange.api.JobResult;
import com.carddemo.dataexchange.internal.Fixtures.FailingSinks;
import com.carddemo.dataexchange.internal.Fixtures.StubFile;
import com.carddemo.migration.Layout;
import com.carddemo.migration.Layouts;
import com.carddemo.test.Covers;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CBEXPORT and CBIMPORT (spec/programs/CBEXPORT.md, CBIMPORT.md) with the ledger fixes Q-CBIMPORT-03/04/05/07. */
class ExportImportJobTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-28T13:48:03.47Z"), ZoneOffset.UTC);
    static final String TIMESTAMP = "2026-10-28 13:48:03.00    ";

    @TempDir
    Path dir;

    JobLog log = new JobLog();
    Path exportFile;
    ImportFiles importFiles;
    Map<Layout, StubFile> inputs = new HashMap<>();

    @BeforeEach
    void setUp() {
        log.startCapture();
        exportFile = dir.resolve("expfile.dat");
        importFiles = new ImportFiles(dir.resolve("custout"), dir.resolve("acctout"), dir.resolve("xrefout"), dir.resolve("trnxout"),
            dir.resolve("cardout"), dir.resolve("errout"));
        inputs.put(Layouts.CUSTDATA, new StubFile(Fixtures.customers()));
        inputs.put(Layouts.ACCTDATA, new StubFile(Fixtures.accounts()));
        inputs.put(Layouts.CARDXREF, new StubFile(Fixtures.xrefs()));
        inputs.put(Layouts.TRANSACT, new StubFile(Fixtures.transactions()));
        inputs.put(Layouts.CARDDATA, new StubFile(Fixtures.cards()));
    }

    JobResult export(Sinks sinks) {
        return new ExportJob(log, sinks, CLOCK).run(inputs::get, exportFile);
    }

    JobResult importAll(Sinks sinks) {
        return new ImportJob(log, sinks, CLOCK).run(exportFile, importFiles);
    }

    List<byte[]> exportRecords() throws IOException {
        byte[] data = Files.readAllBytes(exportFile);
        assertThat(data.length % 500).isZero();
        List<byte[]> recs = new ArrayList<>();
        for (int off = 0; off < data.length; off += 500) {
            recs.add(Arrays.copyOfRange(data, off, off + 500));
        }
        return recs;
    }

    static byte[] slice(byte[] r, int from, int to) {
        return Arrays.copyOfRange(r, from, to + 1);
    }

    static String text(byte[] r, int from, int to) {
        return new String(r, from, to - from + 1, ISO_8859_1);
    }

    static byte[] hex(String h) {
        return HexFormat.of().parseHex(h);
    }

    static String lines(List<String> records) {
        StringBuilder b = new StringBuilder();
        records.forEach(r -> b.append(r).append('\n'));
        return b.toString();
    }

    void writeExport(List<byte[]> records) throws IOException {
        byte[] all = new byte[records.size() * 500];
        for (int i = 0; i < records.size(); i++) {
            System.arraycopy(records.get(i), 0, all, i * 500, 500);
        }
        Files.write(exportFile, all);
    }

    // ---- CBEXPORT ----

    @Test
    @Covers({"TS-CBEXPORT-01", "BR-CBEXPORT-01", "BR-CBEXPORT-02", "BR-CBEXPORT-09", "Q-CBEXPORT-07"})
    void export_displays_section_counts_and_totals_in_order() {
        assertThat(export(new FileSinks()).abended()).isFalse();
        assertThat(log.captured()).containsExactly(
            "CBEXPORT: Starting Customer Data Export",
            "CBEXPORT: Export Date: 2026-10-28",
            "CBEXPORT: Export Time: 13:48:03",
            "CBEXPORT: Processing customer records",
            "CBEXPORT: Customers exported: 000000050",
            "CBEXPORT: Processing account records",
            "CBEXPORT: Accounts exported: 000000050",
            "CBEXPORT: Processing cross-reference records",
            "CBEXPORT: Cross-references exported: 000000050",
            "CBEXPORT: Processing transaction records",
            "CBEXPORT: Transactions exported: 000000300",
            "CBEXPORT: Processing card records",
            "CBEXPORT: Cards exported: 000000050",
            "CBEXPORT: Export completed",
            "CBEXPORT: Customers Exported: 000000050",
            "CBEXPORT: Accounts Exported: 000000050",
            "CBEXPORT: XRefs Exported: 000000050",
            "CBEXPORT: Transactions Exported: 000000300",
            "CBEXPORT: Cards Exported: 000000050",
            "CBEXPORT: Total Records Exported: 000000500");
        inputs.values().forEach(f -> assertThat(f.closed).isTrue());
    }

    @Test
    @Covers({"TS-CBEXPORT-02", "BR-CBEXPORT-03", "Q-CBEXPORT-02", "Q-CBEXPORT-03", "Q-CBEXPORT-06"})
    void every_export_record_has_the_run_timestamp_a_continuous_sequence_branch_and_region() throws IOException {
        export(new FileSinks());
        List<byte[]> recs = exportRecords();
        assertThat(recs).hasSize(500);
        for (int i = 0; i < recs.size(); i++) {
            byte[] r = recs.get(i);
            char expectedType = i < 50 ? 'C' : i < 100 ? 'A' : i < 150 ? 'X' : i < 450 ? 'T' : 'D';
            assertThat((char) r[0]).isEqualTo(expectedType);
            assertThat(text(r, 1, 26)).isEqualTo(TIMESTAMP);
            assertThat(ExportCodec.sequence(r)).isEqualTo(i + 1);
            assertThat(text(r, 31, 34)).isEqualTo("0001");
            assertThat(text(r, 35, 39)).isEqualTo("NORTH");
        }
        assertThat(slice(recs.get(461), 27, 30)).isEqualTo(hex("000001CE"));
    }

    @Test
    @Covers({"TS-CBEXPORT-03", "BR-CBEXPORT-05", "Q-CBEXPORT-04"})
    void account_record_mixes_zoned_packed_and_binary_fields() throws IOException {
        export(new FileSinks());
        byte[] a = exportRecords().get(50);
        assertThat(text(a, 40, 50)).isEqualTo("00000000001");
        assertThat(text(a, 51, 51)).isEqualTo("Y");
        assertThat(slice(a, 52, 58)).isEqualTo(hex("0000000019400C"));
        assertThat(text(a, 59, 70)).isEqualTo("00000020200{");
        assertThat(slice(a, 71, 77)).isEqualTo(hex("0000000102000C"));
        assertThat(text(a, 78, 107)).isEqualTo("2014-11-202025-05-202025-05-20");
        assertThat(text(a, 108, 119)).isEqualTo("00000000000{");
        assertThat(slice(a, 120, 127)).isEqualTo(hex("0000000000000000"));
        assertThat(text(a, 128, 147)).isEqualTo(" ".repeat(10) + "A000000000");
        assertThat(text(a, 148, 499)).isBlank().hasSize(352);
    }

    @Test
    @Covers({"TS-CBEXPORT-04", "BR-CBEXPORT-06", "BR-CBEXPORT-08", "Q-CBEXPORT-01"})
    void xref_and_card_records_carry_binary_account_id_and_cvv() throws IOException {
        export(new FileSinks());
        List<byte[]> recs = exportRecords();
        String xref = Fixtures.xrefs().stream().filter(x -> x.startsWith("0500") && x.substring(12, 16).equals("5740")).findFirst().orElseThrow();
        byte[] x = recs.stream().filter(r -> r[0] == 'X' && text(r, 40, 55).equals(xref.substring(0, 16))).findFirst().orElseThrow();
        assertThat(text(x, 56, 64)).isEqualTo("000000050");
        assertThat(slice(x, 65, 72)).isEqualTo(hex("0000000000000032"));
        assertThat(text(x, 73, 499)).isBlank();
        String card = Fixtures.cards().stream().filter(c -> c.startsWith(xref.substring(0, 16))).findFirst().orElseThrow();
        byte[] d = recs.stream().filter(r -> r[0] == 'D' && text(r, 40, 55).equals(xref.substring(0, 16))).findFirst().orElseThrow();
        assertThat(slice(d, 56, 63)).isEqualTo(hex("0000000000000032"));
        int cvv = Integer.parseInt(card.substring(27, 30));
        assertThat(slice(d, 64, 65)).isEqualTo(new byte[] {(byte) (cvv >> 8), (byte) cvv});
        assertThat(text(d, 66, 126)).isEqualTo(card.substring(30, 91));
        assertThat(text(d, 127, 499)).isBlank();
    }

    @Test
    @Covers({"TS-CBEXPORT-05", "BR-CBEXPORT-04", "BR-CBEXPORT-07"})
    void customer_and_transaction_records_carry_binary_ids_and_packed_amounts() throws IOException {
        export(new FileSinks());
        List<byte[]> recs = exportRecords();
        byte[] c = recs.get(0);
        String cust = Fixtures.customers().get(0);
        assertThat(cust.substring(329, 332)).isEqualTo("274");
        assertThat(slice(c, 40, 43)).isEqualTo(hex("00000001"));
        assertThat(text(c, 44, 363)).isEqualTo(cust.substring(9, 329));
        assertThat(slice(c, 364, 365)).isEqualTo(hex("274F"));
        assertThat(text(c, 366, 499)).isBlank();
        List<String> trans = Fixtures.transactions();
        int i = 0;
        while (!(Overpunch.decode(trans.get(i).substring(132, 143), 2).compareTo(new BigDecimal("504.77")) == 0
            && trans.get(i).substring(143, 152).equals("800000000"))) {
            i++;
        }
        byte[] t = recs.get(150 + i);
        assertThat(text(t, 40, 171)).isEqualTo(trans.get(i).substring(0, 132));
        assertThat(slice(t, 172, 177)).isEqualTo(hex("00000050477C"));
        assertThat(slice(t, 178, 181)).isEqualTo(hex("2FAF0800"));
        assertThat(text(t, 182, 359)).isEqualTo(trans.get(i).substring(152, 330));
        assertThat(text(t, 360, 499)).isBlank();
    }

    @Test
    @Covers({"TS-CBEXPORT-06", "BR-CBEXPORT-02"})
    void empty_transaction_file_exports_zero_transactions_and_cards_follow_the_xrefs() throws IOException {
        inputs.put(Layouts.TRANSACT, new StubFile(List.of()));
        export(new FileSinks());
        assertThat(log.captured()).contains("CBEXPORT: Transactions exported: 000000000", "CBEXPORT: Total Records Exported: 000000200");
        List<byte[]> recs = exportRecords();
        assertThat(recs).hasSize(200);
        assertThat((char) recs.get(150)[0]).isEqualTo('D');
        assertThat(ExportCodec.sequence(recs.get(150))).isEqualTo(151);
        assertThat(ExportCodec.sequence(recs.get(199))).isEqualTo(200);
    }

    @Test
    @Covers({"TS-CBEXPORT-07", "ERR-CBEXPORT-01", "ERR-CBEXPORT-04", "Q-CBEXPORT-08", "B-ABEND"})
    void missing_customer_input_abends_with_status_35() {
        inputs.get(Layouts.CUSTDATA).failOpen("35");
        assertThat(export(new FileSinks()).abended()).isTrue();
        assertThat(log.captured()).containsExactly("CBEXPORT: Starting Customer Data Export",
            "ERROR: Cannot open CUSTOMER-INPUT, Status: 35", "CBEXPORT: ABENDING PROGRAM");
        assertThat(exportFile).doesNotExist();
    }

    @Test
    @Covers({"ERR-CBEXPORT-01"})
    void open_failures_of_later_inputs_and_of_the_export_file_name_the_file() {
        String[][] cases = {{"ACCOUNT-INPUT"}, {"XREF-INPUT"}, {"TRANSACTION-INPUT"}, {"CARD-INPUT"}};
        Layout[] layouts = {Layouts.ACCTDATA, Layouts.CARDXREF, Layouts.TRANSACT, Layouts.CARDDATA};
        for (int i = 0; i < layouts.length; i++) {
            setUp();
            inputs.get(layouts[i]).failOpen("35");
            assertThat(export(new FileSinks()).abended()).isTrue();
            assertThat(log.captured()).endsWith("ERROR: Cannot open " + cases[i][0] + ", Status: 35", "CBEXPORT: ABENDING PROGRAM");
        }
        setUp();
        Sinks failing = path -> {
            throw new IOException("cannot allocate");
        };
        assertThat(export(failing).abended()).isTrue();
        assertThat(log.captured()).containsExactly("CBEXPORT: Starting Customer Data Export",
            "ERROR: Cannot open EXPORT-OUTPUT, Status: 37", "CBEXPORT: ABENDING PROGRAM");
    }

    @Test
    @Covers({"ERR-CBEXPORT-02", "ERR-CBEXPORT-04"})
    void read_failure_abends_naming_the_input() {
        inputs.get(Layouts.TRANSACT).failRead(3, "30");
        assertThat(export(new FileSinks()).abended()).isTrue();
        assertThat(log.captured()).endsWith("CBEXPORT: Processing transaction records", "ERROR: Reading TRANSACTION-INPUT, Status: 30",
            "CBEXPORT: ABENDING PROGRAM");
    }

    @Test
    @Covers({"TS-CBEXPORT-08", "ERR-CBEXPORT-03", "ERR-CBEXPORT-04"})
    void export_write_failure_abends_and_counts_only_written_records() throws IOException {
        assertThat(export(new FailingSinks(exportFile, 60, "No space left on device")).abended()).isTrue();
        assertThat(log.captured()).endsWith("CBEXPORT: Customers exported: 000000050", "CBEXPORT: Processing account records",
            "ERROR: Writing export record, Status: 34", "CBEXPORT: ABENDING PROGRAM");
        assertThat(Files.size(exportFile)).isEqualTo(60L * 500);
    }

    @Test
    @Covers({"ERR-CBEXPORT-05"})
    void input_close_failures_are_ignored() {
        inputs.get(Layouts.CUSTDATA).failClose("30");
        assertThat(export(new FileSinks()).abended()).isFalse();
        assertThat(log.captured()).contains("CBEXPORT: Export completed", "CBEXPORT: Total Records Exported: 000000500");
    }

    @Test
    @Covers({"Q-CBEXPORT-01", "Q-ENT-33", "B-MASK"})
    void export_file_is_owner_only_and_nothing_sensitive_reaches_sysout() throws IOException {
        export(new FileSinks());
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(exportFile))).isEqualTo("rw-------");
        String sysout = String.join("\n", log.captured());
        for (String card : Fixtures.cards()) {
            assertThat(sysout).doesNotContain(card.substring(0, 16));
        }
        for (String cust : Fixtures.customers()) {
            assertThat(sysout).doesNotContain(cust.substring(9, 34).trim()).doesNotContain(cust.substring(234 + 85, 243 + 85));
        }
    }

    // ---- CBIMPORT ----

    @Test
    @Covers({"TS-CBIMPORT-01", "BR-CBIMPORT-01", "BR-CBIMPORT-02", "BR-CBIMPORT-09", "BR-CBIMPORT-10", "TS-CBIMPORT-07", "Q-CBIMPORT-01"})
    void import_of_the_full_export_counts_every_type_and_writes_no_errors() throws IOException {
        export(new FileSinks());
        log.startCapture();
        assertThat(importAll(new FileSinks()).abended()).isFalse();
        assertThat(log.captured()).containsExactly(
            "CBIMPORT: Starting Customer Data Import",
            "CBIMPORT: Import Date: 2026-10-28",
            "CBIMPORT: Import Time: 13:48:03",
            "CBIMPORT: Import validation completed",
            "CBIMPORT: No validation errors detected",
            "CBIMPORT: Import completed",
            "CBIMPORT: Total Records Read: 000000500",
            "CBIMPORT: Customers Imported: 000000050",
            "CBIMPORT: Accounts Imported: 000000050",
            "CBIMPORT: XRefs Imported: 000000050",
            "CBIMPORT: Transactions Imported: 000000300",
            "CBIMPORT: Cards Imported: 000000050",
            "CBIMPORT: Errors Written: 000000000",
            "CBIMPORT: Unknown Record Types: 000000000");
        assertThat(Files.size(importFiles.errors())).isZero();
        assertThat(Files.size(importFiles.cards())).isEqualTo(50L * 151);
    }

    @Test
    @Covers({"TS-CBIMPORT-02", "TS-CBEXPORT-09", "BR-CBIMPORT-03", "BR-CBIMPORT-04", "BR-CBIMPORT-05", "BR-CBIMPORT-06",
        "Q-CBIMPORT-03", "Q-CBIMPORT-06"})
    void export_then_import_reproduces_every_source_record_byte_for_byte() throws IOException {
        export(new FileSinks());
        importAll(new FileSinks());
        assertThat(Files.readString(importFiles.customers(), ISO_8859_1)).isEqualTo(lines(Fixtures.customers()));
        assertThat(Files.readString(importFiles.accounts(), ISO_8859_1)).isEqualTo(lines(Fixtures.accounts()));
        assertThat(Files.readString(importFiles.xrefs(), ISO_8859_1)).isEqualTo(lines(Fixtures.xrefs()));
        assertThat(Files.readString(importFiles.transactions(), ISO_8859_1)).isEqualTo(lines(Fixtures.transactions()));
        assertThat(Files.readString(importFiles.cards(), ISO_8859_1)).isEqualTo(lines(Fixtures.cards()));
    }

    @Test
    @Covers({"BR-CBIMPORT-04", "BR-CBIMPORT-05"})
    void negative_amounts_survive_the_packed_and_binary_round_trip() throws IOException {
        String acct = Fixtures.accounts().get(0);
        String negative = acct.substring(0, 12) + "00000012345J" + "00000020200}" + "00000000001K" + acct.substring(48, 78)
            + "00000000077}" + "00000099999R" + acct.substring(102);
        inputs.put(Layouts.ACCTDATA, new StubFile(List.of(negative)));
        String tran = Fixtures.transactions().get(0);
        String refund = tran.substring(0, 132) + "0000005047P" + tran.substring(143);
        inputs.put(Layouts.TRANSACT, new StubFile(List.of(refund)));
        export(new FileSinks());
        byte[] a = exportRecords().get(50);
        assertThat(slice(a, 52, 58)).isEqualTo(hex("0000000123451D"));
        assertThat(slice(a, 120, 127)).isEqualTo(hex("FFFFFFFFFFF0BDC1"));
        importAll(new FileSinks());
        assertThat(Files.readString(importFiles.accounts(), ISO_8859_1)).isEqualTo(negative + "\n");
        assertThat(Files.readString(importFiles.transactions(), ISO_8859_1)).isEqualTo(refund + "\n");
    }

    @Test
    @Covers({"BR-CBIMPORT-01"})
    void import_processes_records_in_sequence_order_regardless_of_file_order() throws IOException {
        export(new FileSinks());
        List<byte[]> recs = exportRecords();
        List<byte[]> reversed = new ArrayList<>(recs);
        java.util.Collections.reverse(reversed);
        writeExport(reversed);
        importAll(new FileSinks());
        assertThat(Files.readString(importFiles.customers(), ISO_8859_1)).isEqualTo(lines(Fixtures.customers()));
        assertThat(Files.readString(importFiles.cards(), ISO_8859_1)).isEqualTo(lines(Fixtures.cards()));
    }

    @Test
    @Covers({"TS-CBIMPORT-03", "TS-CBIMPORT-04", "BR-CBIMPORT-07", "BR-CBIMPORT-08", "Q-CBIMPORT-02", "Q-CBIMPORT-04"})
    void unknown_record_type_goes_to_the_error_file_with_the_full_sequence_number() throws IOException {
        byte[] c = ExportCodec.customer(Fixtures.customers().get(0), TIMESTAMP, 1);
        byte[] z = ExportCodec.header('Z', TIMESTAMP, 123456789);
        writeExport(List.of(z, c));
        assertThat(importAll(new FileSinks()).abended()).isFalse();
        assertThat(log.captured()).contains("CBIMPORT: Import validation completed", "CBIMPORT: No validation errors detected",
            "CBIMPORT: Total Records Read: 000000002", "CBIMPORT: Customers Imported: 000000001",
            "CBIMPORT: Errors Written: 000000001", "CBIMPORT: Unknown Record Types: 000000001");
        String err = Files.readString(importFiles.errors(), ISO_8859_1);
        String line = "2026102813480347+0000     |Z|123456789|" + String.format("%-50s", "Unknown record type encountered");
        assertThat(line).hasSize(26 + 1 + 1 + 1 + 9 + 1 + 50);
        assertThat(err).isEqualTo(String.format("%-132s", line) + "\n");
    }

    @Test
    @Covers({"Q-CBIMPORT-07"})
    void undecodable_numeric_field_goes_to_the_error_file_and_the_import_continues() throws IOException {
        byte[] good = ExportCodec.customer(Fixtures.customers().get(0), TIMESTAMP, 1);
        byte[] badPacked = ExportCodec.account(Fixtures.accounts().get(0), TIMESTAMP, 2);
        badPacked[58] = (byte) 0x41;
        byte[] badBinary = ExportCodec.xref(Fixtures.xrefs().get(0), TIMESTAMP, 3);
        Arrays.fill(badBinary, 65, 73, (byte) 0xFF);
        byte[] badZoned = ExportCodec.transaction(Fixtures.transactions().get(0), TIMESTAMP, 4);
        badZoned[58] = 'X';
        writeExport(List.of(good, badPacked, badBinary, badZoned));
        assertThat(importAll(new FileSinks()).abended()).isFalse();
        assertThat(log.captured()).contains("CBIMPORT: Total Records Read: 000000004", "CBIMPORT: Customers Imported: 000000001",
            "CBIMPORT: Accounts Imported: 000000000", "CBIMPORT: Errors Written: 000000003", "CBIMPORT: Unknown Record Types: 000000000");
        List<String> errs = Files.readAllLines(importFiles.errors(), ISO_8859_1);
        assertThat(errs).hasSize(3);
        assertThat(errs).extracting(l -> l.substring(26, 38)).containsExactly("|A|000000002", "|X|000000003", "|T|000000004");
        assertThat(errs).allSatisfy(l -> assertThat(l).hasSize(132).contains("|INVALID NUMERIC DATA"));
        assertThat(Files.size(importFiles.accounts())).isZero();
    }

    @Test
    @Covers({"TS-CBIMPORT-05", "BR-CBIMPORT-01", "BR-CBIMPORT-09"})
    void empty_export_imports_nothing() throws IOException {
        Files.write(exportFile, new byte[0]);
        assertThat(importAll(new FileSinks()).abended()).isFalse();
        assertThat(log.captured()).contains("CBIMPORT: Total Records Read: 000000000", "CBIMPORT: Customers Imported: 000000000",
            "CBIMPORT: Unknown Record Types: 000000000");
        for (Path p : List.of(importFiles.customers(), importFiles.accounts(), importFiles.xrefs(), importFiles.transactions(),
            importFiles.cards(), importFiles.errors())) {
            assertThat(Files.size(p)).isZero();
        }
    }

    @Test
    @Covers({"TS-CBIMPORT-06", "ERR-CBIMPORT-01", "ERR-CBIMPORT-05", "Q-CBIMPORT-09", "B-ABEND"})
    void missing_export_file_abends_with_status_35_before_creating_outputs() {
        assertThat(importAll(new FileSinks()).abended()).isTrue();
        assertThat(log.captured()).containsExactly("CBIMPORT: Starting Customer Data Import",
            "ERROR: Cannot open EXPORT-INPUT, Status: 35", "CBIMPORT: ABENDING PROGRAM");
        assertThat(importFiles.customers()).doesNotExist();
    }

    @Test
    @Covers({"ERR-CBIMPORT-01"})
    void output_open_failure_names_the_output_and_deletes_the_ones_created() throws IOException {
        export(new FileSinks());
        String[] names = {"CUSTOMER-OUTPUT", "ACCOUNT-OUTPUT", "XREF-OUTPUT", "TRANSACTION-OUTPUT", "CARD-OUTPUT", "ERROR-OUTPUT"};
        Path[] paths = {importFiles.customers(), importFiles.accounts(), importFiles.xrefs(), importFiles.transactions(), importFiles.cards(),
            importFiles.errors()};
        for (int i = 0; i < names.length; i++) {
            log.startCapture();
            Path failing = paths[i];
            Sinks sinks = path -> {
                if (path.equals(failing)) {
                    throw new IOException("cannot allocate");
                }
                return new FileSinks().create(path);
            };
            assertThat(importAll(sinks).abended()).isTrue();
            assertThat(log.captured()).containsExactly("CBIMPORT: Starting Customer Data Import",
                "ERROR: Cannot open " + names[i] + ", Status: 37", "CBIMPORT: ABENDING PROGRAM");
            for (Path p : paths) {
                assertThat(p).doesNotExist();
            }
        }
    }

    @Test
    @Covers({"ERR-CBIMPORT-02"})
    void trailing_partial_record_abends_as_a_read_error_after_the_complete_records() throws IOException {
        export(new FileSinks());
        Files.write(exportFile, new byte[100], java.nio.file.StandardOpenOption.APPEND);
        log.startCapture();
        assertThat(importAll(new FileSinks()).abended()).isTrue();
        assertThat(log.captured()).containsExactly("CBIMPORT: Starting Customer Data Import", "CBIMPORT: Import Date: 2026-10-28",
            "CBIMPORT: Import Time: 13:48:03", "ERROR: Reading EXPORT-INPUT, Status: 04", "CBIMPORT: ABENDING PROGRAM");
        assertThat(importFiles.customers()).doesNotExist();
    }

    @Test
    @Covers({"TS-CBIMPORT-08", "ERR-CBIMPORT-03", "ERR-CBIMPORT-05"})
    void target_write_failure_abends_naming_the_record_type() throws IOException {
        export(new FileSinks());
        String[] what = {"customer record", "account record", "xref record", "transaction record", "card record"};
        Path[] paths = {importFiles.customers(), importFiles.accounts(), importFiles.xrefs(), importFiles.transactions(), importFiles.cards()};
        for (int i = 0; i < what.length; i++) {
            log.startCapture();
            assertThat(importAll(new FailingSinks(paths[i], 10, "No space left on device")).abended()).isTrue();
            assertThat(log.captured()).endsWith("ERROR: Writing " + what[i] + ", Status: 34", "CBIMPORT: ABENDING PROGRAM");
            assertThat(paths[i]).doesNotExist();
        }
    }

    @Test
    @Covers({"ERR-CBIMPORT-04", "Q-CBIMPORT-05"})
    void error_file_write_failure_abends() throws IOException {
        writeExport(List.of(ExportCodec.header('Z', TIMESTAMP, 1)));
        assertThat(importAll(new FailingSinks(importFiles.errors(), 0, "I/O error")).abended()).isTrue();
        assertThat(log.captured()).endsWith("ERROR: Writing error record, Status: 30", "CBIMPORT: ABENDING PROGRAM");
        assertThat(importFiles.errors()).doesNotExist();
    }

    @Test
    @Covers({"Q-CBIMPORT-08", "Q-CBIMPORT-10", "D-PII-FILES"})
    void rerun_replaces_the_outputs_and_every_output_is_owner_only() throws IOException {
        export(new FileSinks());
        importAll(new FileSinks());
        assertThat(importAll(new FileSinks()).abended()).isFalse();
        assertThat(Files.readString(importFiles.cards(), ISO_8859_1)).isEqualTo(lines(Fixtures.cards()));
        for (Path p : List.of(importFiles.customers(), importFiles.accounts(), importFiles.xrefs(), importFiles.transactions(),
            importFiles.cards(), importFiles.errors())) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(p))).isEqualTo("rw-------");
        }
    }
}
