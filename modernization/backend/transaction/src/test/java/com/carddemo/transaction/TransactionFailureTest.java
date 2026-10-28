package com.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.carddemo.card.api.CardDirectory;
import com.carddemo.test.Covers;
import com.carddemo.transaction.api.TransactionLedger;
import com.carddemo.transaction.web.TransactionReader;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

/** Data-access failures on the transaction screens (ERR rules): each gives its message and writes nothing. */
class TransactionFailureTest extends TransactionScreenTestBase {

    @MockitoSpyBean
    TransactionReader reader;
    @MockitoSpyBean
    TransactionLedger ledger;
    @MockitoSpyBean
    CardDirectory cards;

    @AfterEach
    void unstub() {
        reset(AopTestUtils.getUltimateTargetObject(reader), AopTestUtils.getUltimateTargetObject(ledger),
            AopTestUtils.getUltimateTargetObject(cards));
    }

    private static DataAccessResourceFailureException down() {
        return new DataAccessResourceFailureException("simulated outage");
    }

    @Test
    @Covers({"ERR-COTRN00C-02", "ERR-COTRN00C-04", "ERR-COTRN00C-06"})
    void list_read_failure() {
        client.signOn(ADMIN);
        client.show("COTRN00C");
        doThrow(down()).when(reader).page(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
        for (String key : new String[] {"ENTER", "PF8"}) {
            assertThat(client.post("COTRN00C", key, Map.of()).at("/message").asText()).as(key)
                .isEqualTo("Unable to lookup transaction...");
        }
    }

    @Test
    @Covers({"ERR-COTRN01C-01"})
    void view_read_failure() {
        client.signOn(ADMIN);
        doThrow(down()).when(reader).find(anyString());
        assertThat(client.post("COTRN01C", "ENTER", Map.of("tranIdIn", "0000000000683580")).at("/message").asText())
            .isEqualTo("Unable to lookup Transaction...");
    }

    private String add() {
        Map<String, String> f = TransactionAddScreenTest.sampleForm();
        f.put("accountId", "00000000001");
        f.put("cardNum", "");
        f.put("confirm", "Y");
        return client.post("COTRN02C", "ENTER", f).at("/message").asText();
    }

    @Test
    @Covers({"ERR-COTRN02C-01", "ERR-COTRN02C-02"})
    void cross_reference_failures() {
        client.signOn(ADMIN);
        doThrow(down()).when(cards).lowestCardForAccount(anyLong());
        assertThat(add()).isEqualTo("Unable to lookup Acct in XREF AIX file...");
        doThrow(down()).when(cards).findXref(anyString());
        Map<String, String> f = TransactionAddScreenTest.sampleForm();
        f.put("accountId", "");
        f.put("cardNum", "0000000000000001");
        assertThat(client.post("COTRN02C", "ENTER", f).at("/message").asText()).isEqualTo("Unable to lookup Card # in XREF file...");
    }

    @Test
    @Covers({"ERR-COTRN02C-04", "ERR-COTRN02C-07", "ERR-COTRN02C-08"})
    void write_failures_roll_back() {
        client.signOn(ADMIN);
        int count = transactionCount();
        long counter = jdbc.queryForObject("SELECT value FROM id_counter WHERE name = 'online_tran_id'", Long.class);
        // The ledger bean is a transactional proxy around the spy; stub the spy itself.
        TransactionLedger spy = AopTestUtils.getUltimateTargetObject(ledger);
        doThrow(down()).when(spy).nextOnlineTransactionId();
        assertThat(add()).isEqualTo("Unable to lookup Transaction...");
        reset(spy);
        doThrow(new DuplicateKeyException("dup")).when(spy).insert(any());
        assertThat(add()).isEqualTo("Tran ID already exist...");
        doThrow(down()).when(spy).insert(any());
        assertThat(add()).isEqualTo("Unable to Add Transaction...");
        assertThat(transactionCount()).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT value FROM id_counter WHERE name = 'online_tran_id'", Long.class))
            .as("counter increment rolled back").isEqualTo(counter);
        reset(spy);
        assertThat(add()).as("retry with Y after the failure").startsWith("Transaction added successfully.");
    }
}
