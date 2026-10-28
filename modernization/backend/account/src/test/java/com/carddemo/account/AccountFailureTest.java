package com.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.carddemo.account.internal.AccountCustomerStore;
import com.carddemo.card.api.CardDirectory;
import com.carddemo.test.Covers;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** File errors on both account screens, and the lock, rewrite, and atomicity outcomes of the COACTUPC save. */
@TestPropertySource(properties = "carddemo.account.lock-timeout-ms=300")
class AccountFailureTest extends AccountScreenTestBase {

    static final String UNSUCCESSFUL = "Changes unsuccessful. Please try again";

    @MockitoSpyBean
    CardDirectory cards;
    @MockitoSpyBean
    AccountCustomerStore store;
    @Autowired
    PlatformTransactionManager txm;

    @AfterEach
    void unstub() {
        reset(cards, store);
    }

    private static DataAccessResourceFailureException down() {
        return new DataAccessResourceFailureException("simulated outage");
    }

    private static String fileError(String file) {
        return "File Error: READ     on " + String.format("%-9s", file) + " returned RESP 000000017 ,RESP2 000000000 ";
    }

    private String viewMessage() {
        return client.post("COACTVWC", "ENTER", Map.of("accountId", "00000000024")).at("/message").asText();
    }

    private JsonNode update(String key, Map<String, String> fields) {
        return client.post("COACTUPC", key, Map.of("fields", fields));
    }

    @Test
    @Covers({"TS-COACTVWC-10", "ERR-COACTVWC-02", "ERR-COACTVWC-04", "ERR-COACTVWC-06", "ERR-COACTUPC-02",
        "ERR-COACTUPC-04", "ERR-COACTUPC-06"})
    void read_failures_give_the_file_error_message() {
        client.signOn(ADMIN);
        doThrow(down()).when(cards).lowestCardForAccount(anyLong());
        assertThat(viewMessage()).isEqualTo(fileError("CXACAIX"));
        JsonNode r = client.post("COACTVWC", "ENTER", Map.of("accountId", "00000000024"));
        assertThat(r.at("/fields/accountInError").asBoolean()).isTrue();
        assertThat(update("ENTER", Map.of("accountId", "00000000024")).at("/message").asText())
            .isEqualTo(fileError("CXACAIX"));
        reset(cards);

        doThrow(down()).when(store).account(anyLong());
        assertThat(viewMessage()).isEqualTo(fileError("ACCTDAT"));
        assertThat(update("ENTER", Map.of("accountId", "00000000024")).at("/message").asText())
            .isEqualTo(fileError("ACCTDAT"));
        reset(store);

        doThrow(down()).when(store).customer(anyLong());
        r = client.post("COACTVWC", "ENTER", Map.of("accountId", "00000000024"));
        assertThat(r.at("/message").asText()).isEqualTo(fileError("CUSTDAT"));
        assertThat(r.at("/fields/creditLimit").asText()).isEqualTo("+      5,174.00");
        assertThat(r.at("/fields/firstName").asText()).isEmpty();
        JsonNode u = update("ENTER", Map.of("accountId", "00000000024"));
        assertThat(u.at("/message").asText()).isEqualTo(fileError("CUSTDAT"));
        assertThat(u.at("/fields/mode").asText()).isEqualTo("SEARCH");
    }

    /** Fetches account 24 and confirms a zip and credit limit change (state N). */
    private void confirmChange() {
        client.signOn(ADMIN);
        client.show("COACTUPC");
        JsonNode s = update("ENTER", Map.of("accountId", "00000000024"));
        Map<String, String> form = new LinkedHashMap<>();
        s.at("/fields/values").fields().forEachRemaining(e -> form.put(e.getKey(), e.getValue().asText()));
        form.put("zip", "66044");
        form.put("creditLimit", "6000");
        assertThat(update("ENTER", form).at("/fields/mode").asText()).isEqualTo("N");
    }

    private void assertNothingWritten() {
        assertThat(jdbc.queryForObject("SELECT zip FROM customer WHERE customer_id = 24", String.class)).isEqualTo("15414");
        assertThat(jdbc.queryForObject("SELECT credit_limit FROM account WHERE account_id = 24", BigDecimal.class))
            .isEqualByComparingTo("5174");
    }

    /** Runs {@code f5} while another transaction holds an update lock on the row. */
    private JsonNode whileLocked(String table, String key, long id) throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = ex.submit(() -> new TransactionTemplate(txm).executeWithoutResult(st -> {
                jdbc.queryForList("SELECT " + key + " FROM " + table + " WITH (UPDLOCK, ROWLOCK) WHERE " + key + " = ?", id);
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                st.setRollbackOnly();
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            JsonNode r = update("PF5", Map.of());
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            return r;
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
    }

    @Test
    @Covers({"TS-COACTUPC-15", "ERR-COACTUPC-07", "BR-COACTUPC-15", "BR-COACTUPC-18", "BR-COACTUPC-21"})
    void account_lock_failure() throws Exception {
        confirmChange();
        JsonNode r = whileLocked("account", "account_id", 24);
        assertThat(r.at("/message").asText()).isEqualTo("Could not lock account record for update");
        assertThat(r.at("/fields/info").asText()).isEqualTo(UNSUCCESSFUL);
        assertThat(r.at("/fields/mode").asText()).isEqualTo("L");
        assertThat(r.at("/fields/cancelKey").asBoolean()).isTrue();
        assertThat(r.at("/fields/values/zip").asText()).isEqualTo("66044");
        assertNothingWritten();
        assertThat(update("ENTER", Map.of()).at("/fields/mode").asText()).isEqualTo("SEARCH");
    }

    @Test
    @Covers({"TS-COACTUPC-16", "ERR-COACTUPC-08", "Q-COACTUPC-02", "Q-COACTUPC-03"})
    void customer_lock_failure_writes_nothing() throws Exception {
        confirmChange();
        JsonNode r = whileLocked("customer", "customer_id", 24);
        assertThat(r.at("/message").asText()).isEqualTo("Could not lock customer record for update");
        assertThat(r.at("/fields/info").asText()).isEqualTo(UNSUCCESSFUL);
        assertThat(r.at("/fields/mode").asText()).isEqualTo("L");
        assertNothingWritten();
    }

    @Test
    @Covers({"TS-COACTUPC-17", "ERR-COACTUPC-09", "ERR-COACTUPC-10", "Q-COACTUPC-03"})
    void a_failed_rewrite_rolls_back_both_records() {
        confirmChange();
        doThrow(down()).when(store).updateCustomer(any());
        JsonNode r = update("PF5", Map.of());
        assertThat(r.at("/message").asText()).isEqualTo("Update of record failed");
        assertThat(r.at("/fields/info").asText()).isEqualTo(UNSUCCESSFUL);
        assertThat(r.at("/fields/mode").asText()).isEqualTo("F");
        assertNothingWritten();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCOUNT_UPDATE' AND target = '00000000024'"
            + " AND recorded_at > DATEADD(MINUTE, -1, SYSDATETIME())", Integer.class)).isZero();
        reset(store);

        confirmChange();
        doThrow(down()).when(store).updateAccount(any());
        assertThat(update("PF5", Map.of()).at("/message").asText()).isEqualTo("Update of record failed");
        assertNothingWritten();
    }
}
