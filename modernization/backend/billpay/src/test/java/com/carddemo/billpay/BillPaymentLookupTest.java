package com.carddemo.billpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.carddemo.account.api.AccountBalances;
import com.carddemo.card.api.CardDirectory;
import com.carddemo.migration.Layouts;
import com.carddemo.migration.SampleDataLoader;
import com.carddemo.test.Covers;
import com.carddemo.test.RepoPaths;
import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** COBIL00C account and cross-reference lookup failures, empty input, and the continuing conversation. */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class BillPaymentLookupTest {

    static final String ACCT1 = "00000000001";

    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoSpyBean
    AccountBalances balances;
    @MockitoSpyBean
    CardDirectory cards;

    ScreenClient client;

    @BeforeEach
    void load() throws Exception {
        sample.load();
        new SampleDataLoader(jdbc).loadFile(Layouts.TRANSACT, RepoPaths.asciiData().resolve("dailytran.txt"));
        client = new ScreenClient(mvc);
        client.signOn(jdbc.queryForObject("SELECT user_id FROM user_account WHERE account_id = 1", String.class));
        client.show("COBIL00C");
    }

    @AfterEach
    void unstub() {
        reset(balances, cards);
    }

    private JsonNode enter(String acct, String confirm, String shown) {
        Map<String, String> f = new HashMap<>();
        f.put("accountId", acct);
        f.put("confirm", confirm);
        f.put("shownBalance", shown);
        return client.post("COBIL00C", "ENTER", f);
    }

    private int transactionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM card_transaction", Integer.class);
    }

    private BigDecimal balance() {
        return jdbc.queryForObject("SELECT current_balance FROM account WHERE account_id = 1", BigDecimal.class);
    }

    @Test
    @Covers({"ERR-COBIL00C-01"})
    void an_account_read_failure_shows_unable_to_lookup_account() {
        doThrow(new DataAccessResourceFailureException("ACCTDAT unavailable")).when(balances).currentBalance(anyLong());
        JsonNode shown = enter(ACCT1, "", "");
        assertThat(shown.at("/message").asText()).isEqualTo("Unable to lookup Account...");
        assertThat(shown.at("/focus").asText()).isEqualTo("accountId");
        assertThat(shown.at("/fields/currentBalance").asText()).isEmpty();
    }

    @Test
    @Covers({"ERR-COBIL00C-02", "Q-COBIL00C-02"})
    void a_missing_or_unreadable_card_cross_reference_stops_the_payment() {
        int count = transactionCount();
        BigDecimal before = balance();
        doReturn(Optional.empty()).when(cards).lowestCardForAccount(anyLong());
        JsonNode none = enter(ACCT1, "Y", "+0000000194.00");
        assertThat(none.at("/message").asText()).isEqualTo("Account ID NOT found...");
        assertThat(transactionCount()).isEqualTo(count);
        assertThat(balance()).isEqualByComparingTo(before);

        reset(cards);
        doThrow(new DataAccessResourceFailureException("CXACAIX unavailable")).when(cards).lowestCardForAccount(anyLong());
        JsonNode failed = enter(ACCT1, "Y", "+0000000194.00");
        assertThat(failed.at("/message").asText()).isEqualTo("Unable to lookup XREF AIX file...");
        assertThat(failed.at("/focus").asText()).isEqualTo("accountId");
        assertThat(transactionCount()).as("processing does not continue to the write").isEqualTo(count);
        assertThat(balance()).isEqualByComparingTo(before);
    }

    @Test
    @Covers({"ERR-COBIL00C-08"})
    void a_turn_with_no_fields_is_handled_as_blank_input() {
        JsonNode r = client.post("COBIL00C", "ENTER", Map.of());
        assertThat(r.at("/message").asText()).isEqualTo("Acct ID can NOT be empty...");
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
    }

    @Test
    @Covers({"NAV-COBIL00C-07", "C-SESSION"})
    void after_each_redisplay_the_next_turn_continues_the_payment() {
        JsonNode shown = enter(ACCT1, "", "");
        assertThat(shown.at("/message").asText()).isEqualTo("Confirm to make a bill payment...");
        JsonNode bad = enter(ACCT1, "X", shown.at("/fields/currentBalance").asText());
        assertThat(bad.at("/message").asText()).isEqualTo("Invalid value. Valid values are (Y/N)...");
        JsonNode paid = enter(ACCT1, "Y", bad.at("/fields/currentBalance").asText());
        assertThat(paid.at("/messageColor").asText()).isEqualTo("GREEN");
        assertThat(paid.at("/message").asText()).startsWith("Payment successful.");
    }
}
