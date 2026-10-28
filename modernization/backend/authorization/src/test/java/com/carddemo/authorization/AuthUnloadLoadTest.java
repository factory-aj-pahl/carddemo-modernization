package com.carddemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

import com.carddemo.authorization.api.AuthorizationJobs;
import com.carddemo.authorization.internal.AuthBatchFiles;
import com.carddemo.authorization.internal.PendingAuthRepository;
import com.carddemo.common.batch.JobLog;
import com.carddemo.common.codec.Packed;
import com.carddemo.migration.PendingAuthSegments;
import com.carddemo.test.Covers;
import com.carddemo.test.SampleData;
import com.carddemo.test.TestApplication;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** PAUDBUNL (UNLDPADB), DBUNLDGS (UNLDGSAM) and PAUDBLOD (LOADPADB) against the IMS sample in the tables. */
@SpringBootTest(classes = TestApplication.class)
class AuthUnloadLoadTest {

    static final long[] ACCOUNTS = {1, 5, 7, 13, 15, 16, 17, 18, 23, 29, 30, 31, 32, 33, 34, 38, 42, 45, 46, 47, 48};
    static final int CHILD = 206;

    @Autowired
    AuthorizationJobs jobs;
    @Autowired
    JobLog log;
    @Autowired
    SampleData sample;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoSpyBean
    PendingAuthRepository repo;
    @MockitoSpyBean
    AuthBatchFiles files;
    @TempDir
    Path dir;

    @BeforeEach
    void load() {
        AuthFixtures.load(sample, jdbc);
        log.startCapture();
    }

    @AfterEach
    void unstub() {
        reset(repo, files);
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    static List<byte[]> records(Path p, int len) throws IOException {
        byte[] all = Files.readAllBytes(p);
        assertThat(all.length % len).isZero();
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < all.length; i += len) {
            out.add(Arrays.copyOfRange(all, i, i + len));
        }
        return out;
    }

    static long account(byte[] rec) {
        return Packed.decode(rec, 0, 6, 0).longValueExact();
    }

    void emptyTables() {
        jdbc.update("DELETE FROM pending_auth_detail");
        jdbc.update("DELETE FROM pending_auth_summary");
    }

    String today() {
        return "TODAYS DATE            :" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
    }

    @Test
    @Covers({"ERR-PAUDBUNL-06"})
    void a_close_error_is_reported_and_the_run_continues_with_rc0() throws IOException {
        List<FileChannel> real = new ArrayList<>();
        doAnswer(inv -> {
            FileChannel ch = (FileChannel) inv.callRealMethod();
            if (!inv.<Path>getArgument(0).getFileName().toString().startsWith("root")) {
                return ch;
            }
            real.add(ch);
            // Every call goes to the real channel except close, which fails as a CLOSE with a bad status would.
            return mock(FileChannel.class, call -> {
                if (call.getMethod().getName().equals("close")) {
                    throw new IOException("close failed");
                }
                return call.getMethod().invoke(ch, call.getArguments());
            });
        }).when(files).create(any());
        Path root = dir.resolve("root.fileo");
        Path child = dir.resolve("child.fileo");
        try {
            assertThat(jobs.unload(root, child).returnCode()).isZero();
        } finally {
            for (FileChannel ch : real) {
                ch.close();
            }
        }
        assertThat(log.captured()).endsWith("CLOSING THE FILE", "ERROR IN CLOSING 1ST FILE:30", "ROOT SEGMENTS UNLOADED  :000000021",
            "CHILD SEGMENTS UNLOADED :000000202");
        assertThat(records(root, 100)).hasSize(21);
        assertThat(records(child, CHILD)).hasSize(202);
    }

    @Test
    @Covers({"TS-PAUDBUNL-01", "TS-PAUDBUNL-09", "BR-PAUDBUNL-01", "BR-PAUDBUNL-02", "BR-PAUDBUNL-03", "BR-PAUDBUNL-05",
        "BR-PAUDBUNL-06", "BR-PAUDBUNL-07", "BR-PAUDBUNL-08", "Q-PAUDBUNL-01", "Q-PAUDBUNL-10", "Q-PAUDBUNL-09",
        "Q-PAUDBUNL-11"})
    void unload_writes_every_root_and_child_in_key_order_with_counts() throws IOException {
        Path root = dir.resolve("root.fileo");
        Path child = dir.resolve("child.fileo");
        assertThat(jobs.unload(root, child).returnCode()).isZero();
        List<byte[]> roots = records(root, 100);
        assertThat(roots.stream().mapToLong(AuthUnloadLoadTest::account).toArray()).containsExactly(ACCOUNTS);
        List<byte[]> children = records(child, CHILD);
        assertThat(children).hasSize(202);
        List<String> out = log.captured();
        assertThat(out.subList(0, 4)).containsExactly("STARTING PROGRAM PAUDBUNL::", "*-------------------------------------*", today(), " ");
        assertThat(out.subList(4, 25)).allMatch("CHILD SEG FLAG GE : Y"::equals);
        // Q-PAUDBUNL-01 (FIX): NEW count lines.
        assertThat(out.subList(25, out.size())).containsExactly("CLOSING THE FILE", "ROOT SEGMENTS UNLOADED  :000000021",
            "CHILD SEGMENTS UNLOADED :000000202");
        // D-PII-FILES: full card numbers, so owner-only.
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(root))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(child))).isEqualTo("rw-------");
        // Every child is the segment the tables hold under the prefixed parent.
        for (byte[] c : children) {
            PendingAuthSegments.Detail d = PendingAuthSegments.decodeDetail(c, 6, account(c), null);
            assertThat(count("SELECT COUNT(*) FROM pending_auth_detail WHERE account_id = ? AND auth_ts = ? AND card_num = ?",
                account(c), java.sql.Timestamp.valueOf(d.authTs()), d.cardNum())).isEqualTo(1);
        }
    }

    @Test
    @Covers({"TS-PAUDBUNL-02", "BR-PAUDBUNL-03", "BR-PAUDBUNL-06"})
    void account_7_children_are_consecutive_after_account_5_newest_first() throws IOException {
        Path child = dir.resolve("child");
        jobs.unload(dir.resolve("root"), child);
        List<byte[]> children = records(child, CHILD);
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < children.size(); i++) {
            if (account(children.get(i)) == 7) {
                idx.add(i);
                assertThat(HexFormat.of().formatHex(children.get(i), 0, 6)).isEqualTo("00000000007c");
            }
        }
        assertThat(idx).hasSize(50);
        assertThat(idx.get(49) - idx.get(0)).isEqualTo(49);
        assertThat(account(children.get(idx.get(0) - 1))).isEqualTo(5);
        assertThat(account(children.get(idx.get(49) + 1))).isEqualTo(13);
        for (int i = 1; i < idx.size(); i++) {
            byte[] prev = Arrays.copyOfRange(children.get(idx.get(i - 1)), 6, 14);
            byte[] cur = Arrays.copyOfRange(children.get(idx.get(i)), 6, 14);
            assertThat(Arrays.compareUnsigned(prev, cur)).as("ascending key = newest first").isLessThanOrEqualTo(0);
        }
    }

    @Test
    @Covers({"TS-PAUDBUNL-03", "TS-DBUNLDGS-06", "BR-PAUDBUNL-05", "BR-DBUNLDGS-05"})
    void root_without_children_is_written_and_still_shows_the_ge_line() throws IOException {
        jdbc.update("INSERT INTO pending_auth_summary (account_id, customer_id, auth_status, account_status, credit_limit, cash_limit, "
            + "credit_balance, cash_balance, approved_count, declined_count, approved_amt, declined_amt) "
            + "VALUES (99, 99, '', '', 100.00, 10.00, 0, 0, 0, 0, 0, 0)");
        for (boolean gsam : new boolean[] {false, true}) {
            log.startCapture();
            Path root = dir.resolve("root" + gsam);
            Path child = dir.resolve("child" + gsam);
            assertThat((gsam ? jobs.unloadGsam(root, child) : jobs.unload(root, child)).returnCode()).isZero();
            List<byte[]> roots = records(root, 100);
            assertThat(account(roots.get(roots.size() - 1))).isEqualTo(99);
            assertThat(records(child, CHILD)).noneMatch(c -> account(c) == 99).hasSize(202);
            assertThat(log.captured()).filteredOn("CHILD SEG FLAG GE : Y"::equals).hasSize(22);
        }
    }

    @Test
    @Covers({"TS-PAUDBUNL-04", "TS-DBUNLDGS-03", "BR-PAUDBUNL-04", "BR-DBUNLDGS-04", "TS-PAUDBLOD-10", "Q-PAUDBLOD-07",
        "Q-PAUDBUNL-02", "Q-DBUNLDGS-04"})
    void an_all_spaces_root_is_rejected_by_the_load_so_no_unload_can_carry_it() throws IOException {
        emptyTables();
        Path root = dir.resolve("in.root");
        byte[] spaces = new byte[100];
        Arrays.fill(spaces, (byte) 0x40);
        byte[] good = PendingAuthSegments.encodeSummary(new PendingAuthSegments.Summary(99, 99, "", "", new java.math.BigDecimal("1.00"),
            new java.math.BigDecimal("1.00"), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, 0, 0, java.math.BigDecimal.ZERO,
            java.math.BigDecimal.ZERO, null, null, null));
        Files.write(root, concat(spaces, good));
        Path child = dir.resolve("in.child");
        Files.write(child, new byte[0]);
        assertThat(jobs.load(root, child).returnCode()).isZero();
        List<String> out = log.captured();
        assertThat(out).containsSubsequence(" *******************************", " *******************************", "INVALID ROOT KEY",
            " *******************************", " *******************************", "ROOT INSERT SUCCESS    ");
        assertThat(out).contains("ROOT SEGMENTS INSERTED  :000000001", "INVALID KEYS REJECTED   :000000001");
        assertThat(count("SELECT COUNT(*) FROM pending_auth_summary")).isEqualTo(1);
        for (boolean gsam : new boolean[] {false, true}) {
            Path r = dir.resolve("out.root" + gsam);
            Path c = dir.resolve("out.child" + gsam);
            assertThat((gsam ? jobs.unloadGsam(r, c) : jobs.unload(r, c)).returnCode()).isZero();
            assertThat(Files.readAllBytes(r)).isEqualTo(good);
        }
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

    @Test
    @Covers({"TS-PAUDBUNL-05", "ERR-PAUDBUNL-02", "ERR-PAUDBUNL-07", "Q-PAUDBUNL-06", "ERR-PAUDBUNL-01"})
    void outfil2_that_cannot_be_opened_ends_with_rc16_and_no_output() {
        Path root = dir.resolve("root");
        assertThat(jobs.unload(root, dir.resolve("missing/child")).returnCode()).isEqualTo(16);
        assertThat(log.captured()).endsWith("ERROR IN OPENING OPFILE2:35", "IMSUNLOD ABENDING ...");
        assertThat(root).doesNotExist();
        log.startCapture();
        assertThat(jobs.unload(dir.resolve("missing/root"), dir.resolve("child")).returnCode()).isEqualTo(16);
        assertThat(log.captured()).endsWith("ERROR IN OPENING OPFILE1:35", "IMSUNLOD ABENDING ...");
    }

    @Test
    @Covers({"TS-PAUDBUNL-06", "ERR-PAUDBUNL-03", "Q-PAUDBUNL-06", "TS-DBUNLDGS-05", "ERR-DBUNLDGS-01", "Q-DBUNLDGS-05",
        "ERR-DBUNLDGS-05"})
    void root_read_failure_after_10_roots_ends_with_rc16_and_deletes_the_partial_files() {
        doThrow(new DataAccessResourceFailureException("BA")).when(repo).nextSummary(eq(29L));
        for (boolean gsam : new boolean[] {false, true}) {
            log.startCapture();
            Path root = dir.resolve("root" + gsam);
            Path child = dir.resolve("child" + gsam);
            assertThat((gsam ? jobs.unloadGsam(root, child) : jobs.unload(root, child)).returnCode()).isEqualTo(16);
            assertThat(log.captured()).endsWith("AUTH SUM  GN FAILED  :DataAccessResourceFailureException",
                "KEY FEEDBACK AREA    :00000000029", gsam ? "DBUNLDGS ABENDING ..." : "IMSUNLOD ABENDING ...");
            assertThat(log.captured()).filteredOn("CHILD SEG FLAG GE : Y"::equals).hasSize(10);
            // Q-PAUDBUNL-06 / Q-DBUNLDGS-05 (FIX): no partial extract is left for a load.
            assertThat(root).doesNotExist();
            assertThat(child).doesNotExist();
        }
    }

    @Test
    @Covers({"TS-PAUDBUNL-07", "ERR-PAUDBUNL-04", "ERR-DBUNLDGS-02"})
    void child_read_failure_ends_with_rc16() {
        doThrow(new DataAccessResourceFailureException("GNP")).when(repo).detailsFrom(eq(7L), any(), any(), anyBoolean(), anyInt());
        for (boolean gsam : new boolean[] {false, true}) {
            log.startCapture();
            assertThat((gsam ? jobs.unloadGsam(dir.resolve("r"), dir.resolve("c")) : jobs.unload(dir.resolve("r"), dir.resolve("c")))
                .returnCode()).isEqualTo(16);
            assertThat(log.captured()).endsWith("GNP CALL FAILED  :DataAccessResourceFailureException", "KFB AREA IN CHILD:00000000007",
                gsam ? "DBUNLDGS ABENDING ..." : "IMSUNLOD ABENDING ...");
        }
    }

    @Test
    @Covers({"TS-PAUDBUNL-08", "Q-PAUDBUNL-07", "Q-UNLDPADB-01"})
    void unload_creates_missing_outputs_and_replaces_existing_ones() throws IOException {
        Path root = dir.resolve("root");
        Path child = dir.resolve("child");
        assertThat(jobs.unload(root, child).returnCode()).isZero();
        Files.write(root, new byte[5000]);
        Files.write(child, new byte[7]);
        assertThat(jobs.unload(root, child).returnCode()).isZero();
        assertThat(Files.size(root)).isEqualTo(2100);
        assertThat(Files.size(child)).isEqualTo(202L * CHILD);
    }

    @Test
    @Covers({"Q-PAUDBUNL-04", "ERR-PAUDBUNL-05", "TS-DBUNLDGS-04", "ERR-DBUNLDGS-04", "ERR-DBUNLDGS-03", "Q-DBUNLDGS-02",
        "Q-DBUNLDGS-08"})
    void write_error_ends_with_rc16_and_deletes_the_outputs() throws IOException {
        Path child = dir.resolve("child");
        doAnswer(inv -> {
            Path p = inv.getArgument(0);
            if (p.getFileName().toString().startsWith("child")) {
                inv.callRealMethod();
                return FileChannel.open(Path.of("/dev/full"), StandardOpenOption.WRITE);
            }
            return inv.callRealMethod();
        }).when(files).create(any());
        assertThat(jobs.unload(dir.resolve("root"), child).returnCode()).isEqualTo(16);
        assertThat(log.captured()).endsWith("IMSUNLOD ABENDING ...");
        assertThat(dir.resolve("root")).doesNotExist();
        assertThat(child).doesNotExist();
        log.startCapture();
        assertThat(jobs.unloadGsam(dir.resolve("groot"), dir.resolve("child2")).returnCode()).isEqualTo(16);
        // Q-DBUNLDGS-02: the detail file error keeps the parent text; Q-DBUNLDGS-08: no KFB line.
        assertThat(log.captured()).endsWith("GSAM PARENT FAIL :IOException", "DBUNLDGS ABENDING ...");
        assertThat(dir.resolve("groot")).doesNotExist();
    }

    @Test
    @Covers({"TS-DBUNLDGS-01", "TS-DBUNLDGS-02", "BR-DBUNLDGS-01", "BR-DBUNLDGS-02", "BR-DBUNLDGS-03", "BR-DBUNLDGS-06",
        "BR-DBUNLDGS-07", "Q-DBUNLDGS-01", "Q-DBUNLDGS-03", "Q-DBUNLDGS-06", "Q-DBUNLDGS-09"})
    void gsam_unload_writes_the_same_records_with_the_parent_key_on_each_detail() throws IOException {
        Path r = dir.resolve("root.gsam");
        Path c = dir.resolve("child.gsam");
        assertThat(jobs.unloadGsam(r, c).returnCode()).isZero();
        List<String> out = log.captured();
        assertThat(out.subList(0, 4)).containsExactly("STARTING PROGRAM DBUNLDGS::", "*-------------------------------------*", today(), " ");
        assertThat(out.subList(4, 25)).allMatch("CHILD SEG FLAG GE : Y"::equals);
        assertThat(out.subList(25, out.size())).containsExactly("CLOSING THE FILE", "ROOT SEGMENTS UNLOADED  :000000021",
            "CHILD SEGMENTS UNLOADED :000000202");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(c))).isEqualTo("rw-------");
        // Q-DBUNLDGS-01 (FIX): each detail carries its parent key, so the files equal the PAUDBUNL pair.
        jobs.unload(dir.resolve("root.fileo"), dir.resolve("child.fileo"));
        assertThat(Files.readAllBytes(r)).isEqualTo(Files.readAllBytes(dir.resolve("root.fileo")));
        assertThat(Files.readAllBytes(c)).isEqualTo(Files.readAllBytes(dir.resolve("child.fileo")));
        List<byte[]> children = records(c, CHILD);
        assertThat(children.stream().filter(x -> account(x) == 7)).hasSize(50)
            .allMatch(x -> new String(x, 26, 16, PendingAuthSegments.CP037).equals(AuthFixtures.CARD_7));
    }

    // ---- PAUDBLOD ----

    Path[] unloadSample() {
        Path r = dir.resolve("unl.root");
        Path c = dir.resolve("unl.child");
        assertThat(jobs.unload(r, c).returnCode()).isZero();
        log.startCapture();
        return new Path[] {r, c};
    }

    @Test
    @Covers({"TS-PAUDBLOD-01", "BR-PAUDBLOD-01", "BR-PAUDBLOD-02", "BR-PAUDBLOD-05", "BR-PAUDBLOD-09", "BR-PAUDBLOD-10",
        "Q-PAUDBLOD-09", "Q-PAUDBLOD-06", "Q-PAUDBLOD-11", "Q-PAUDBLOD-01"})
    void load_into_empty_tables_inserts_every_root_and_child_and_round_trips() throws IOException {
        Path[] f = unloadSample();
        emptyTables();
        assertThat(jobs.load(f[0], f[1]).returnCode()).isZero();
        List<String> out = log.captured();
        assertThat(out.subList(0, 4)).containsExactly("STARTING PAUDBLOD", "*-------------------------------------*", today(), " ");
        assertThat(out).filteredOn("ROOT INSERT SUCCESS    "::equals).hasSize(21);
        assertThat(out).filteredOn(" *******************************"::equals).hasSize(42);
        assertThat(out).filteredOn("***************************"::equals).hasSize(404);
        assertThat(out).filteredOn("GU CALL TO ROOT SEG SUCCESS"::equals).hasSize(202);
        assertThat(out).filteredOn("CHILD SEGMENT INSERTED SUCCESS"::equals).hasSize(202);
        assertThat(out.subList(out.size() - 6, out.size())).containsExactly("CLOSING THE FILE", "ROOT SEGMENTS INSERTED  :000000021",
            "CHILD SEGMENTS INSERTED :000000202", "DUPLICATES SKIPPED      :000000000", "ORPHAN CHILDREN SKIPPED :000000000",
            "INVALID KEYS REJECTED   :000000000");
        assertThat(count("SELECT COUNT(*) FROM pending_auth_detail WHERE account_id = 7")).isEqualTo(50);
        Path r2 = dir.resolve("again.root");
        Path c2 = dir.resolve("again.child");
        assertThat(jobs.unload(r2, c2).returnCode()).isZero();
        assertThat(Files.readAllBytes(r2)).isEqualTo(Files.readAllBytes(f[0]));
        assertThat(Files.readAllBytes(c2)).isEqualTo(Files.readAllBytes(f[1]));
    }

    @Test
    @Covers({"TS-PAUDBLOD-02", "BR-PAUDBLOD-03", "BR-PAUDBLOD-07", "Q-PAUDBLOD-05"})
    void reload_of_the_same_data_skips_every_segment() {
        Path[] f = unloadSample();
        String before = jdbc.queryForObject("SELECT CAST(SUM(CAST(approved_count AS BIGINT)) AS VARCHAR) FROM pending_auth_summary", String.class);
        assertThat(jobs.load(f[0], f[1]).returnCode()).isZero();
        assertThat(log.captured()).filteredOn("ROOT SEGMENT ALREADY IN DB"::equals).hasSize(21);
        assertThat(log.captured()).filteredOn("CHILD SEGMENT ALREADY IN DB"::equals).hasSize(202);
        assertThat(log.captured()).contains("DUPLICATES SKIPPED      :000000223");
        assertThat(count("SELECT COUNT(*) FROM pending_auth_detail")).isEqualTo(202);
        assertThat(jdbc.queryForObject("SELECT CAST(SUM(CAST(approved_count AS BIGINT)) AS VARCHAR) FROM pending_auth_summary", String.class))
            .isEqualTo(before);
    }

    byte[] childOf(long account, java.time.LocalDateTime ts) {
        PendingAuthSegments.Detail d = PendingAuthTablesHelper.anyDetail(jdbc, 7);
        PendingAuthSegments.Detail moved = new PendingAuthSegments.Detail(0, account, ts, d.origDate(), d.origTime(), d.cardNum(),
            d.authType(), d.cardExpiryDate(), d.messageType(), d.messageSource(), d.authIdCode(), d.respCode(), d.respReason(),
            d.processingCode(), d.transactionAmt(), d.approvedAmt(), d.merchantCategoryCode(), d.acqrCountryCode(), d.posEntryMode(),
            d.merchantId(), d.merchantName(), d.merchantCity(), d.merchantState(), d.merchantZip(), d.transactionId(), d.matchStatus(),
            d.authFraud(), d.fraudRptDate(), null, null);
        return concat(PendingAuthSegments.rootKey(account), PendingAuthSegments.encodeDetail(moved));
    }

    /** Reads one stored detail of an account. */
    static final class PendingAuthTablesHelper {
        static PendingAuthSegments.Detail anyDetail(JdbcTemplate jdbc, long account) {
            return com.carddemo.migration.PendingAuthTables.details(jdbc, account).get(0);
        }
    }

    @Test
    @Covers({"TS-PAUDBLOD-03", "TS-PAUDBLOD-04", "TS-PAUDBLOD-05", "BR-PAUDBLOD-04", "BR-PAUDBLOD-06", "BR-PAUDBLOD-08",
        "Q-PAUDBLOD-02", "ERR-PAUDBLOD-07"})
    void orphans_are_reported_invalid_parents_skipped_and_new_children_merged_into_existing_roots() throws IOException {
        Path root = dir.resolve("r");
        Files.write(root, new byte[0]);
        byte[] orphan = childOf(99, java.time.LocalDateTime.of(2024, 3, 1, 10, 0));
        byte[] invalid = childOf(7, java.time.LocalDateTime.of(2024, 3, 1, 10, 1));
        Arrays.fill(invalid, 0, 6, (byte) 0x40);
        byte[] extra = childOf(7, java.time.LocalDateTime.of(2024, 3, 1, 10, 2));
        Path child = dir.resolve("c");
        Files.write(child, concat(orphan, invalid, extra));
        String summarySql = "SELECT approved_count, declined_count, approved_amt, declined_amt, credit_balance, cash_balance "
            + "FROM pending_auth_summary WHERE account_id = 7";
        String summaryBefore = jdbc.queryForMap(summarySql).toString();
        assertThat(jobs.load(root, child).returnCode()).isZero();
        List<String> out = log.captured();
        // Q-PAUDBLOD-02 (FIX): the orphan is reported with the status of the failed GU and counted.
        assertThat(out.subList(4, out.size())).containsExactly("***************************", "***************************",
            "ROOT GU CALL FAIL:GE", "***************************", "***************************", "GU CALL TO ROOT SEG SUCCESS",
            "CHILD SEGMENT INSERTED SUCCESS", "CLOSING THE FILE", "ROOT SEGMENTS INSERTED  :000000000",
            "CHILD SEGMENTS INSERTED :000000001", "DUPLICATES SKIPPED      :000000000", "ORPHAN CHILDREN SKIPPED :000000001",
            "INVALID KEYS REJECTED   :000000001");
        assertThat(count("SELECT COUNT(*) FROM pending_auth_detail WHERE account_id = 7")).isEqualTo(51);
        assertThat(count("SELECT COUNT(*) FROM pending_auth_detail WHERE account_id = 99")).isZero();
        assertThat(jdbc.queryForMap(summarySql).toString()).isEqualTo(summaryBefore);
    }

    @Test
    @Covers({"TS-PAUDBLOD-06", "ERR-PAUDBLOD-01", "ERR-PAUDBLOD-02", "ERR-PAUDBLOD-09"})
    void input_that_cannot_be_opened_ends_with_rc16_and_loads_nothing() throws IOException {
        emptyTables();
        Path[] f = {dir.resolve("none.root"), dir.resolve("none.child")};
        assertThat(jobs.load(f[0], f[1]).returnCode()).isEqualTo(16);
        assertThat(log.captured()).endsWith("ERROR IN OPENING INFILE1:35", "IMS LOAD ABENDING ...");
        Files.write(f[0], new byte[0]);
        log.startCapture();
        assertThat(jobs.load(f[0], f[1]).returnCode()).isEqualTo(16);
        assertThat(log.captured()).endsWith("ERROR IN OPENING INFILE2:35", "IMS LOAD ABENDING ...");
        assertThat(count("SELECT COUNT(*) FROM pending_auth_summary")).isZero();
    }

    @Test
    @Covers({"TS-PAUDBLOD-07", "ERR-PAUDBLOD-05", "ERR-PAUDBLOD-09", "Q-PAUDBLOD-04"})
    void eleventh_root_insert_failure_loads_nothing() {
        Path[] f = unloadSample();
        emptyTables();
        AtomicInteger n = new AtomicInteger();
        doAnswer(inv -> {
            if (n.incrementAndGet() == 11) {
                throw new DataAccessResourceFailureException("isrt");
            }
            return inv.callRealMethod();
        }).when(repo).insertSummary(any());
        assertThat(jobs.load(f[0], f[1]).returnCode()).isEqualTo(16);
        assertThat(log.captured()).endsWith("ROOT INSERT FAILED  :DataAccessResourceFailureException", "IMS LOAD ABENDING ...");
        // Q-PAUDBLOD-04 (CHANGE): the root file is one transaction, so the first 10 roots are rolled back too.
        assertThat(count("SELECT COUNT(*) FROM pending_auth_summary")).isZero();
        assertThat(count("SELECT COUNT(*) FROM pending_auth_detail")).isZero();
        assertThat(log.captured()).noneMatch("GU CALL TO ROOT SEG SUCCESS"::equals);
    }

    @Test
    @Covers({"TS-PAUDBLOD-08", "ERR-PAUDBLOD-06", "Q-PAUDBLOD-04"})
    void child_insert_failure_ends_with_rc16_and_rolls_back_the_child_file() {
        Path[] f = unloadSample();
        emptyTables();
        AtomicInteger n = new AtomicInteger();
        doAnswer(inv -> {
            if (n.incrementAndGet() == 3) {
                throw new DataAccessResourceFailureException("isrt child");
            }
            return inv.callRealMethod();
        }).when(repo).insertDetail(argThat(d -> d != null));
        assertThat(jobs.load(f[0], f[1]).returnCode()).isEqualTo(16);
        List<String> out = log.captured();
        assertThat(out.get(out.size() - 3)).isEqualTo("INSERT CALL FAIL FOR CHILD:DataAccessResourceFailureException");
        assertThat(out.get(out.size() - 2)).startsWith("KFB AREA IN CHILD:00000000001").hasSize("KFB AREA IN CHILD:".length() + 11 + 16);
        assertThat(out.get(out.size() - 1)).isEqualTo("IMS LOAD ABENDING ...");
        // B-UOW: the root file committed on its own; nothing of the child file stays.
        assertThat(count("SELECT COUNT(*) FROM pending_auth_summary")).isEqualTo(21);
        assertThat(count("SELECT COUNT(*) FROM pending_auth_detail")).isZero();
    }

    @Test
    @Covers({"ERR-PAUDBLOD-03", "ERR-PAUDBLOD-04", "Q-PAUDBLOD-03", "ERR-PAUDBLOD-08"})
    void read_error_ends_with_rc16() throws IOException {
        Path[] f = unloadSample();
        emptyTables();
        Path shortRoot = dir.resolve("short.root");
        Files.write(shortRoot, Arrays.copyOf(Files.readAllBytes(f[0]), 150));
        assertThat(jobs.load(shortRoot, f[1]).returnCode()).isEqualTo(16);
        assertThat(log.captured()).endsWith("ROOT INSERT SUCCESS    ", "ERROR READING ROOT SEG INFILE", "IMS LOAD ABENDING ...");
        assertThat(count("SELECT COUNT(*) FROM pending_auth_summary")).isZero();
        log.startCapture();
        Path shortChild = dir.resolve("short.child");
        Files.write(shortChild, Arrays.copyOf(Files.readAllBytes(f[1]), 300));
        assertThat(jobs.load(f[0], shortChild).returnCode()).isEqualTo(16);
        assertThat(log.captured()).endsWith("CHILD SEGMENT INSERTED SUCCESS", "ERROR READING CHILD SEG INFILE", "IMS LOAD ABENDING ...");
        assertThat(count("SELECT COUNT(*) FROM pending_auth_summary")).isEqualTo(21);
        assertThat(count("SELECT COUNT(*) FROM pending_auth_detail")).isZero();
    }
}
