package com.carddemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.carddemo.account.api.AccountProfiles;
import com.carddemo.authorization.internal.FraudReports;
import com.carddemo.authorization.internal.PendingAuthRepository;
import com.carddemo.card.api.CardDirectory;
import com.carddemo.test.Covers;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/** COPAUS0C and COPAUS1C read and update failures (CICS file errors, DL/I statuses, PSB scheduling, rollback). */
@SpringBootTest(classes = {TestApplication.class, AuthScreenTestSupport.Clocks.class})
@AutoConfigureMockMvc
class PendingAuthScreenFailureTest extends AuthScreenTestSupport {

    static final String IOERR = "Resp:000000017 Reas:000000000";
    static final String NOTFND = "Resp:000000013 Reas:000000080";

    @MockitoSpyBean
    PendingAuthRepository repo;
    @MockitoSpyBean
    AccountProfiles accounts;
    @MockitoSpyBean
    CardDirectory cards;
    @MockitoSpyBean
    PlatformTransactionManager tm;
    @MockitoSpyBean
    FraudReports fraud;

    @AfterEach
    void unstub() {
        reset(repo, accounts, cards, tm, fraud);
    }

    static DataAccessResourceFailureException down() {
        return new DataAccessResourceFailureException("unavailable", new SQLException("unavailable", "57011", 904));
    }

    void failScheduling(String transactionName) {
        doThrow(new CannotCreateTransactionException("PSB"))
            .when(tm).getTransaction(argThat(d -> d != null && transactionName.equals(d.getName())));
    }

    /** Records the transactions started under {@code transactionName}, with their definitions. */
    record Started(List<TransactionDefinition> defs, List<TransactionStatus> statuses) {
    }

    Started record(String transactionName) {
        Started s = new Started(new ArrayList<>(), new ArrayList<>());
        doAnswer(inv -> {
            TransactionStatus status = (TransactionStatus) inv.callRealMethod();
            TransactionDefinition d = inv.getArgument(0);
            if (d != null && transactionName.equals(d.getName())) {
                s.defs().add(d);
                s.statuses().add(status);
            }
            return status;
        }).when(tm).getTransaction(any());
        return s;
    }

    // COPAUS0C

    @Test
    @Covers({"ERR-COPAUS0C-02"})
    void an_xref_read_failure_stops_with_the_system_error() {
        doThrow(down()).when(cards).lowestCardForAccount(7L);
        JsonNode r = list("ENTER", ACCT7);
        assertThat(r.at("/message").asText())
            .isEqualTo("Account:00000000007 System error while reading XREF file. " + IOERR);
        assertThat(r.at("/fields/customerId").asText()).isEmpty();
        assertThat(r.at("/fields/name").asText()).isEmpty();
        assertThat(txns(r)).containsOnly("");
        verify(repo, never()).summary(anyLong());
    }

    @Test
    @Covers({"ERR-COPAUS0C-03", "Q-COPAUS0C-07"})
    void a_missing_account_record_is_reported_and_the_list_still_shows() {
        doReturn(Optional.empty()).when(accounts).limits(7L);
        JsonNode r = list("ENTER", ACCT7);
        assertThat(r.at("/message").asText()).isEqualTo("Account:00000000007 not found in ACCT file. " + NOTFND);
        assertThat(r.at("/fields/creditLimit").asText()).isEmpty();
        assertThat(r.at("/fields/accountStatus").asText()).isEmpty();
        assertThat(r.at("/fields/customerId").asText()).isEqualTo("000000007");
        assertThat(txns(r)).containsExactlyElementsOf(rows(ACCOUNT_7, 1, 5));
    }

    @Test
    @Covers({"ERR-COPAUS0C-03"})
    void an_account_read_failure_sets_the_error_flag_and_shows_no_list() {
        doThrow(down()).when(accounts).limits(7L);
        JsonNode r = list("ENTER", ACCT7);
        assertThat(r.at("/message").asText())
            .isEqualTo("Account:00000000007 System error while reading ACCT file. " + IOERR);
        assertThat(txns(r)).containsOnly("");
    }

    @Test
    @Covers({"ERR-COPAUS0C-04"})
    void customer_not_found_and_customer_read_failure_have_their_messages() {
        doReturn(Optional.empty()).when(accounts).contact(7L);
        JsonNode missing = list("ENTER", ACCT7);
        assertThat(missing.at("/message").asText()).isEqualTo("Customer:000000007 not found in CUST file. " + NOTFND);
        assertThat(missing.at("/fields/name").asText()).isEmpty();
        assertThat(txns(missing)).containsExactlyElementsOf(rows(ACCOUNT_7, 1, 5));

        doThrow(down()).when(accounts).contact(7L);
        JsonNode failed = list("ENTER", ACCT7);
        assertThat(failed.at("/message").asText())
            .isEqualTo("Customer:000000007 System error while reading CUST file. " + IOERR);
        assertThat(txns(failed)).containsOnly("");
    }

    @Test
    @Covers({"ERR-COPAUS0C-05"})
    void a_summary_read_failure_shows_the_status() {
        doThrow(down()).when(repo).summary(7L);
        JsonNode r = list("ENTER", ACCT7);
        assertThat(r.at("/message").asText()).isEqualTo(" System error while reading AUTH Summary: Code:57011");
        assertThat(txns(r)).containsOnly("");
    }

    @Test
    @Covers({"ERR-COPAUS0C-06"})
    void a_list_detail_read_failure_shows_the_status() {
        doThrow(down()).when(repo).detailsFrom(eq(7L), isNull(), isNull(), anyBoolean(), anyInt());
        JsonNode r = list("ENTER", ACCT7);
        assertThat(r.at("/message").asText()).isEqualTo(" System error while reading AUTH Details: Code:57011");
        assertThat(r.at("/fields/approvedCount").asText()).isNotEmpty();
        assertThat(txns(r)).containsOnly("");
    }

    @Test
    @Covers({"ERR-COPAUS0C-07"})
    void a_repositioning_failure_on_paging_shows_the_status() {
        list("ENTER", ACCT7);
        doThrow(down()).when(repo).detailsFrom(eq(7L), notNull(), notNull(), anyBoolean(), anyInt());
        JsonNode r = list("PF8", ACCT7);
        assertThat(r.at("/message").asText()).isEqualTo(" System error while repos. AUTH Details: Code:57011");
        assertThat(txns(r)).containsOnly("");
    }

    @Test
    @Covers({"ERR-COPAUS0C-08"})
    void a_scheduling_failure_shows_the_psb_error() {
        failScheduling("COPAUS0C");
        JsonNode r = list("ENTER", ACCT7);
        assertThat(r.at("/message").asText())
            .isEqualTo(" System error while scheduling PSB: Code:CannotCreateTransactionException");
        assertThat(txns(r)).containsOnly("");
    }

    @Test
    @Covers({"BR-COPAUS0C-12"})
    void every_list_read_is_released_before_the_screen_is_sent() {
        Started s = record("COPAUS0C");
        list("ENTER", ACCT7);
        list("PF8", ACCT7);
        list("PF7", ACCT7);
        assertThat(s.defs()).hasSize(3).allMatch(TransactionDefinition::isReadOnly);
        for (TransactionStatus st : s.statuses()) {
            verify(tm).rollback(st);
            verify(tm, never()).commit(st);
            assertThat(st.isCompleted()).isTrue();
        }
    }

    // COPAUS1C

    @Test
    @Covers({"ERR-COPAUS1C-01"})
    void a_detail_scheduling_failure_shows_the_psb_error_and_no_detail() {
        openDetail(admin, 1);
        failScheduling("COPAUS1C");
        for (String key : List.of("ENTER", "PF8", "PF5")) {
            JsonNode r = detail(admin, key);
            assertThat(r.at("/message").asText()).as(key)
                .isEqualTo(" System error while scheduling PSB: Code:CannotCreateTransactionException");
            assertThat(r.at("/fields/transactionId").asText()).as(key).isEmpty();
        }
        assertThat(fraudRows()).isZero();
    }

    @Test
    @Covers({"ERR-COPAUS1C-02"})
    void a_detail_summary_read_failure_shows_the_status() {
        openDetail(admin, 1);
        doThrow(down()).when(repo).summary(7L);
        JsonNode r = detail(admin, "ENTER");
        assertThat(r.at("/message").asText()).isEqualTo(" System error while reading Auth Summary: Code:57011");
        assertThat(r.at("/fields/cardNum").asText()).isEmpty();
    }

    @Test
    @Covers({"ERR-COPAUS1C-03"})
    void a_detail_read_failure_shows_the_status() {
        openDetail(admin, 1);
        doThrow(down()).when(repo).detail(eq(7L), anyLong());
        JsonNode r = detail(admin, "ENTER");
        assertThat(r.at("/message").asText()).isEqualTo(" System error while reading Auth Details: Code:57011");
        assertThat(r.at("/fields/cardNum").asText()).isEmpty();
    }

    @Test
    @Covers({"ERR-COPAUS1C-04"})
    void a_next_detail_read_failure_shows_the_status() {
        openDetail(admin, 1);
        doThrow(down()).when(repo).nextOlder(eq(7L), any(), anyLong());
        JsonNode r = detail(admin, "PF8");
        assertThat(r.at("/message").asText()).isEqualTo(" System error while reading next Auth: Code:57011");
        assertThat(r.at("/fields/cardNum").asText()).isEmpty();
    }

    @Test
    @Covers({"ERR-COPAUS1C-06", "Q-COPAUS1C-01"})
    void a_failed_fraud_call_rolls_back_and_shows_the_copaus2c_error_text() {
        openDetail(admin, 1);
        Map<String, Object> before = detailRow(NEWEST_TXN);
        doThrow(new IllegalStateException("link failed")).when(fraud).record(anyLong(), anyLong(), any(), any(), any());
        JsonNode r = detail(admin, "PF5");
        assertThat(r.at("/message").asText()).isEqualTo(" SYSTEM ERROR DB2: CODE:+000000, STATE: ");
        assertThat(r.at("/fields/fraudStatus").asText()).isEqualTo("-");
        assertThat(detailRow(NEWEST_TXN)).isEqualTo(before);
        assertThat(fraudRows()).isZero();
    }

    @Test
    @Covers({"ERR-COPAUS1C-07", "TS-COPAUS2C-05", "BR-COPAUS2C-07"})
    void a_failed_detail_update_rolls_back_the_fraud_row_too() {
        openDetail(admin, 1);
        Map<String, Object> before = detailRow(NEWEST_TXN);
        doThrow(down()).when(repo).updateFraud(anyLong(), any(), any());
        Started s = record("COPAUS1C");
        JsonNode r = detail(admin, "PF5");
        assertThat(r.at("/message").asText()).isEqualTo(" System error while FRAUD Tagging, ROLLBACK||57011");
        assertThat(r.at("/fields/fraudStatus").asText()).isEqualTo("-");
        assertThat(detailRow(NEWEST_TXN)).isEqualTo(before);
        // COPAUS2C inserted the row inside the unit of work; the rollback removed it.
        verify(fraud).record(eq(7L), eq(7L), any(), eq("F"), any());
        assertThat(fraudRows()).isZero();
        verify(tm, never()).commit(s.statuses().get(0));
        verify(tm).rollback(s.statuses().get(0));

        doReturn(false).when(repo).updateFraud(anyLong(), any(), any());
        assertThat(detail(admin, "PF5").at("/message").asText()).isEqualTo(" System error while FRAUD Tagging, ROLLBACK||GE");
        assertThat(fraudRows()).isZero();
    }

    @Test
    @Covers({"BR-COPAUS1C-09", "BR-COPAUS1C-06"})
    void detail_reads_are_released_and_the_fraud_toggle_commits_once() {
        openDetail(admin, 1);
        Started s = record("COPAUS1C");
        detail(admin, "ENTER");
        detail(admin, "PF8");
        assertThat(s.defs()).hasSize(2).allMatch(TransactionDefinition::isReadOnly);
        s.statuses().forEach(st -> verify(tm).rollback(st));
        s.defs().clear();
        s.statuses().clear();
        detail(admin, "PF5");
        // The toggle's unit of work, then the read-only re-read of what is stored.
        assertThat(s.defs()).hasSize(2);
        assertThat(s.defs().get(0).isReadOnly()).isFalse();
        verify(tm).commit(s.statuses().get(0));
        assertThat(s.defs().get(1).isReadOnly()).isTrue();
        verify(tm).rollback(s.statuses().get(1));
    }
}
