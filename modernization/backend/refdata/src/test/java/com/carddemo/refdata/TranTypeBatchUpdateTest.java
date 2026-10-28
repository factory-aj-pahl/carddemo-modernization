package com.carddemo.refdata;

import static com.carddemo.refdata.TranTypeScreens.description;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.carddemo.common.batch.JobLog;
import com.carddemo.refdata.api.BatchUpdateResult;
import com.carddemo.refdata.api.TranTypeBatchUpdate;
import com.carddemo.refdata.internal.Db2Error;
import com.carddemo.refdata.internal.Db2Failure;
import com.carddemo.refdata.internal.TransactionTypeStore;
import com.carddemo.test.Covers;
import com.carddemo.test.TestApplication;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** COBTUPDT (JOB-MNTTRDB2) against the Db2 seed types and categories. */
@SpringBootTest(classes = TestApplication.class)
class TranTypeBatchUpdateTest {

    @Autowired
    TranTypeBatchUpdate job;
    @Autowired
    JobLog log;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoSpyBean
    TransactionTypeStore store;
    @TempDir
    Path dir;

    @BeforeEach
    void load() {
        TranTypeScreens.loadDb2Seed(jdbc);
        log.startCapture();
    }

    @AfterEach
    void heal() {
        reset(store);
    }

    private BatchUpdateResult run(String... lines) throws IOException {
        Path f = dir.resolve("inpfile.txt");
        Files.write(f, List.of(lines), StandardCharsets.ISO_8859_1);
        return job.apply(f);
    }

    private static String rec(String s) {
        return String.format("%-53s", s);
    }

    private List<String> summary() {
        List<String> out = log.captured();
        return out.subList(out.size() - 5, out.size());
    }

    private static List<String> counts(int read, int added, int updated, int deleted, int rejected) {
        return List.of("RECORDS READ     : " + read, "RECORDS ADDED    : " + added, "RECORDS UPDATED  : " + updated,
            "RECORDS DELETED  : " + deleted, "RECORDS REJECTED : " + rejected);
    }

    @Test
    @Covers({"TS-COBTUPDT-01", "BR-COBTUPDT-02", "BR-COBTUPDT-05", "BR-COBTUPDT-07", "Q-COBTUPDT-06"})
    void an_add_record_inserts_the_type() throws IOException {
        BatchUpdateResult r = run("A08INTEREST");
        assertThat(log.captured()).startsWith("OPEN FILE OK", "PROCESSING   " + rec("A08INTEREST"), "ADDING RECORD",
            "RECORD INSERTED SUCCESSFULLY");
        assertThat(description(jdbc, "08")).isEqualTo("INTEREST");
        assertThat(r.returnCode()).isZero();
        assertThat(summary()).isEqualTo(counts(1, 1, 0, 0, 0));
    }

    @Test
    @Covers({"TS-COBTUPDT-02", "BR-COBTUPDT-03"})
    void an_update_record_changes_the_description() throws IOException {
        run("A08INTEREST");
        log.startCapture();
        BatchUpdateResult r = run("U08INTEREST CHARGE");
        assertThat(log.captured()).contains("UPDATING RECORD", "RECORD UPDATED SUCCESSFULLY");
        assertThat(description(jdbc, "08")).isEqualTo("INTEREST CHARGE");
        assertThat(r.returnCode()).isZero();
        assertThat(r.updated()).isEqualTo(1);
    }

    @Test
    @Covers({"TS-COBTUPDT-03", "BR-COBTUPDT-04"})
    void a_delete_record_removes_a_type_without_categories() throws IOException {
        run("A08INTEREST");
        log.startCapture();
        BatchUpdateResult r = run("D08");
        assertThat(log.captured()).contains("DELETING RECORD", "RECORD DELETED SUCCESSFULLY");
        assertThat(description(jdbc, "08")).isNull();
        assertThat(r.returnCode()).isZero();
    }

    @Test
    @Covers({"TS-COBTUPDT-04", "ERR-COBTUPDT-02", "Q-COBTUPDT-04"})
    void a_duplicate_add_stops_the_run_and_rolls_back_the_file() throws IOException {
        BatchUpdateResult r = run("A09TEMP", "A01PURCHASE", "A10LATER");
        List<String> out = log.captured();
        assertThat(out).contains("RECORD INSERTED SUCCESSFULLY", "Error accessing: TRANSACTION_TYPE table. SQLCODE: -803",
            "RUN STOPPED: ALL CHANGES ROLLED BACK, NOTHING APPLIED");
        assertThat(out).doesNotContain("PROCESSING   " + rec("A10LATER"));
        assertThat(description(jdbc, "09")).isNull();
        assertThat(description(jdbc, "10")).isNull();
        assertThat(description(jdbc, "01")).isEqualTo("PURCHASE");
        assertThat(r.stopped()).isTrue();
        assertThat(r.returnCode()).isEqualTo(12);
        assertThat(summary()).isEqualTo(counts(2, 0, 0, 0, 0));
    }

    @Test
    @Covers({"TS-COBTUPDT-05", "ERR-COBTUPDT-03", "Q-COBTUPDT-02"})
    void an_update_of_a_missing_type_is_rejected_with_a_clean_message() throws IOException {
        BatchUpdateResult r = run("a09TEST", "U99X");
        List<String> out = log.captured();
        assertThat(out).containsSubsequence("ERROR: TYPE NOT VALID", "UPDATING RECORD", "No records found.");
        assertThat(out).noneMatch(l -> l.startsWith("No records found.") && !l.equals("No records found."));
        assertThat(r.returnCode()).isEqualTo(4);
        assertThat(description(jdbc, "99")).isNull();
    }

    @Test
    @Covers({"TS-COBTUPDT-06", "ERR-COBTUPDT-04"})
    void a_delete_of_a_missing_type_is_rejected() throws IOException {
        BatchUpdateResult r = run("D99", "A08INTEREST");
        assertThat(log.captured()).containsSubsequence("DELETING RECORD", "No records found.", "RECORD INSERTED SUCCESSFULLY");
        assertThat(r.returnCode()).isEqualTo(4);
        assertThat(description(jdbc, "08")).isEqualTo("INTEREST");
        assertThat(summary()).isEqualTo(counts(2, 1, 0, 0, 1));
    }

    @Test
    @Covers({"TS-COBTUPDT-07", "ERR-COBTUPDT-05", "BR-COBTUPDT-04"})
    void a_delete_of_a_type_with_categories_is_refused_by_the_foreign_key() throws IOException {
        BatchUpdateResult r = run("A08INTEREST", "D01");
        assertThat(log.captured()).contains("Error accessing: TRANSACTION_TYPE table. SQLCODE: -532");
        assertThat(description(jdbc, "01")).isEqualTo("PURCHASE");
        assertThat(description(jdbc, "08")).isNull();
        assertThat(r.returnCode()).isEqualTo(12);
    }

    @Test
    @Covers({"TS-COBTUPDT-08", "BR-COBTUPDT-06"})
    void a_comment_is_ignored() throws IOException {
        BatchUpdateResult r = run("*THIS IS A COMMENT");
        assertThat(log.captured()).containsExactly("OPEN FILE OK", "PROCESSING   " + rec("*THIS IS A COMMENT"),
            "IGNORING COMMENTED LINE", "RECORDS READ     : 1", "RECORDS ADDED    : 0", "RECORDS UPDATED  : 0",
            "RECORDS DELETED  : 0", "RECORDS REJECTED : 0");
        assertThat(r.returnCode()).isZero();
    }

    @Test
    @Covers({"TS-COBTUPDT-09", "VR-COBTUPDT-01"})
    void lower_case_and_blank_actions_are_rejected() throws IOException {
        BatchUpdateResult r = run("a09TEST", "");
        assertThat(log.captured()).filteredOn("ERROR: TYPE NOT VALID"::equals).hasSize(2);
        assertThat(log.captured()).contains("PROCESSING   " + rec(""));
        assertThat(description(jdbc, "09")).isNull();
        assertThat(r.returnCode()).isEqualTo(4);
        assertThat(r.rejected()).isEqualTo(2);
    }

    @Test
    @Covers({"TS-COBTUPDT-10", "Q-COBTUPDT-05"})
    void records_get_the_online_type_and_description_edits() throws IOException {
        BatchUpdateResult r = run("AABPUNCT-UATION.", "A 5FIVE", "A5 FIVE", "A00ZERO", "A08PUNCT-UATION.", "A08", "U01",
            "DAB", "A09Mixed Case 9");
        assertThat(log.captured()).filteredOn("ERROR: TYPE NOT VALID"::equals).hasSize(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_type WHERE type_cd NOT LIKE '0[1-9]'", Integer.class)).isZero();
        assertThat(description(jdbc, "08")).isNull();
        assertThat(description(jdbc, "01")).isEqualTo("PURCHASE");
        assertThat(description(jdbc, "09")).isEqualTo("Mixed Case 9");
        assertThat(r.returnCode()).isEqualTo(4);
        assertThat(summary()).isEqualTo(counts(9, 1, 0, 0, 8));
    }

    @Test
    @Covers({"TS-COBTUPDT-11", "BR-COBTUPDT-01", "ERR-COBTUPDT-06", "Q-COBTUPDT-07"})
    void records_apply_in_file_order_with_one_result_line_each_and_a_summary() throws IOException {
        BatchUpdateResult r = run("A09TEMP", "U09TEMPORARY", "D09");
        List<String> out = log.captured();
        assertThat(out).containsSubsequence("RECORD INSERTED SUCCESSFULLY", "RECORD UPDATED SUCCESSFULLY", "RECORD DELETED SUCCESSFULLY");
        assertThat(out).hasSize(1 + 3 * 3 + 5);
        assertThat(description(jdbc, "09")).isNull();
        assertThat(r).isEqualTo(new BatchUpdateResult(3, 1, 1, 1, 0, false));
        assertThat(summary()).isEqualTo(counts(3, 1, 1, 1, 0));
    }

    @Test
    @Covers({"TS-COBTUPDT-12", "ERR-COBTUPDT-01", "Q-COBTUPDT-01"})
    void an_unreadable_input_stops_the_run_with_exit_code_12() throws IOException {
        BatchUpdateResult r = job.apply(dir.resolve("missing.txt"));
        assertThat(log.captured()).startsWith("OPEN FILE NOT OK", "RUN STOPPED: ALL CHANGES ROLLED BACK, NOTHING APPLIED");
        assertThat(r.returnCode()).isEqualTo(12);
        log.startCapture();
        r = run("A08INTEREST", "A09" + "X".repeat(90));
        assertThat(log.captured()).containsSubsequence("RECORD INSERTED SUCCESSFULLY", "READ FILE NOT OK",
            "RUN STOPPED: ALL CHANGES ROLLED BACK, NOTHING APPLIED");
        assertThat(description(jdbc, "08")).isNull();
        assertThat(r.returnCode()).isEqualTo(12);
    }

    @Test
    @Covers({"Q-COBTUPDT-08"})
    void card_images_of_80_bytes_are_accepted() throws IOException {
        String card = rec("A08INTEREST") + "IGNORED COLUMNS 54 TO 80 ..";
        assertThat(card).hasSize(80);
        BatchUpdateResult r = run(card);
        assertThat(log.captured()).contains("PROCESSING   " + rec("A08INTEREST"));
        assertThat(description(jdbc, "08")).isEqualTo("INTEREST");
        assertThat(r.returnCode()).isZero();
    }

    @Test
    @Covers({"ERR-COBTUPDT-07", "Q-COBTUPDT-04"})
    void a_deadlock_stops_the_run_instead_of_committing_the_rest() throws IOException {
        doThrow(new Db2Failure(new Db2Error(-911, "Transaction was deadlocked"), null)).when(store).update(eq("02"), anyString());
        BatchUpdateResult r = run("A08INTEREST", "U02PAYMENTS", "A09LATER");
        assertThat(log.captured()).contains("Error accessing: TRANSACTION_TYPE table. SQLCODE: -911");
        assertThat(description(jdbc, "08")).isNull();
        assertThat(description(jdbc, "09")).isNull();
        assertThat(description(jdbc, "02")).isEqualTo("PAYMENT");
        assertThat(r.returnCode()).isEqualTo(12);
    }

    @Test
    @Covers({"TS-COBTUPDT-13", "Q-COBTUPDT-03"})
    void while_the_run_is_open_only_the_rows_it_touched_are_locked() throws Exception {
        AtomicReference<Integer> otherRow = new AtomicReference<>();
        AtomicReference<Integer> touchedRow = new AtomicReference<>();
        ExecutorService online = Executors.newSingleThreadExecutor();
        try {
            doAnswer(inv -> {
                Object result = inv.callRealMethod();
                // Another session (a CTTU save) while the job's transaction is still open.
                otherRow.set(online.submit(() -> updateWithTimeout("01", "PURCHASES")).get(30, TimeUnit.SECONDS));
                touchedRow.set(online.submit(() -> updateWithTimeout("08", "OTHER USER")).get(30, TimeUnit.SECONDS));
                return result;
            }).when(store).update(eq("02"), anyString());
            BatchUpdateResult r = run("A08INTEREST", "U02PAYMENTS");
            assertThat(r.returnCode()).isZero();
        } finally {
            online.shutdownNow();
        }
        assertThat(otherRow.get()).isEqualTo(1);
        assertThat(touchedRow.get()).isEqualTo(1222);
        assertThat(description(jdbc, "01")).isEqualTo("PURCHASES");
        assertThat(description(jdbc, "02")).isEqualTo("PAYMENTS");
        assertThat(description(jdbc, "08")).isEqualTo("INTEREST");
    }

    /** Rows updated, or the SQL Server error number (1222: lock request time out). */
    private Integer updateWithTimeout(String code, String desc) {
        return jdbc.execute((ConnectionCallback<Integer>) con -> {
            try (Statement s = con.createStatement()) {
                s.execute("SET LOCK_TIMEOUT 1000");
                try {
                    return s.executeUpdate("UPDATE transaction_type SET description = '" + desc + "' WHERE type_cd = '" + code + "'");
                } catch (SQLException e) {
                    return e.getErrorCode();
                } finally {
                    s.execute("SET LOCK_TIMEOUT -1");
                }
            }
        });
    }

    @Test
    @Covers({"TS-COBTUPDT-14", "BR-COBTUPDT-07"})
    void an_empty_input_ends_with_return_code_0() throws IOException {
        Path f = dir.resolve("empty.txt");
        Files.writeString(f, "");
        BatchUpdateResult r = job.apply(f);
        assertThat(log.captured()).containsExactly("OPEN FILE OK", "RECORDS READ     : 0", "RECORDS ADDED    : 0",
            "RECORDS UPDATED  : 0", "RECORDS DELETED  : 0", "RECORDS REJECTED : 0");
        assertThat(r.returnCode()).isZero();
    }
}
