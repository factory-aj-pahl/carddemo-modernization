package com.carddemo.billpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.msg.CommonMessages;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** COBIL00C screen turns against the sample data with TRANSACT loaded from dailytran.txt (spec test assumptions). */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class BillPaymentScreenTest {

    static final String ACCT1 = "00000000001";
    static final String ACCT2 = "00000000002";

    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;
    @Autowired
    JdbcTemplate jdbc;

    ScreenClient client;

    @BeforeEach
    void load() throws Exception {
        sample.load();
        new SampleDataLoader(jdbc).loadFile(Layouts.TRANSACT, RepoPaths.asciiData().resolve("dailytran.txt"));
        client = new ScreenClient(mvc);
    }

    private String ownerOf(long account) {
        return jdbc.queryForObject("SELECT user_id FROM user_account WHERE account_id = ?", String.class, account);
    }

    private JsonNode enter(String acct, String confirm, String shown) {
        Map<String, String> f = new HashMap<>();
        f.put("accountId", acct);
        f.put("confirm", confirm);
        f.put("shownBalance", shown);
        return client.post("COBIL00C", "ENTER", f);
    }

    private Map<String, Object> accountRow(long id) {
        Map<String, Object> row = new HashMap<>(jdbc.queryForMap("SELECT * FROM account WHERE account_id = ?", id));
        row.remove("row_version");
        return row;
    }

    private int transactionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM card_transaction", Integer.class);
    }

    private BigDecimal balance(long id) {
        return jdbc.queryForObject("SELECT current_balance FROM account WHERE account_id = ?", BigDecimal.class, id);
    }

    @Test
    @Covers({"NAV-COBIL00C-02"})
    void first_entry_is_a_blank_form() {
        client.signOn(ownerOf(1));
        JsonNode r = client.show("COBIL00C");
        assertThat(r.at("/fields/accountId").asText()).isEmpty();
        assertThat(r.at("/fields/currentBalance").asText()).isEmpty();
        assertThat(r.at("/fields/confirm").asText()).isEmpty();
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
    }

    @Test
    @Covers({"TS-COBIL00C-01", "BR-COBIL00C-01", "BR-COBIL00C-03", "BR-COBIL00C-04", "BR-COBIL00C-05", "BR-COBIL00C-06",
        "BR-COBIL00C-08", "BR-COBIL00C-09", "NAV-COBIL00C-03", "Q-COBIL00C-01", "Q-COBIL00C-05", "Q-COBIL00C-06",
        "Q-COBIL00C-13", "D-RECEIPT"})
    void show_balance_then_confirm_pays_the_full_balance() {
        client.signOn(ownerOf(1));
        Map<String, Object> before = accountRow(1);
        int count = transactionCount();

        JsonNode shown = enter(ACCT1, "", "");
        assertThat(shown.at("/fields/currentBalance").asText()).isEqualTo("+0000000194.00");
        assertThat(shown.at("/message").asText()).isEqualTo("Confirm to make a bill payment...");
        assertThat(shown.at("/messageColor").asText()).isEqualTo("RED");
        assertThat(shown.at("/focus").asText()).isEqualTo("confirm");
        assertThat(transactionCount()).isEqualTo(count);
        assertThat(accountRow(1)).isEqualTo(before);

        JsonNode paid = enter(ACCT1, "Y", shown.at("/fields/currentBalance").asText());
        assertThat(paid.at("/message").asText()).isEqualTo("Payment successful.  Your Transaction ID is 0000000996722788.");
        assertThat(paid.at("/messageColor").asText()).isEqualTo("GREEN");
        assertThat(paid.at("/fields/accountId").asText()).isEmpty();
        assertThat(paid.at("/fields/currentBalance").asText()).isEmpty();
        assertThat(paid.at("/fields/confirm").asText()).isEmpty();
        assertThat(paid.at("/focus").asText()).isEqualTo("accountId");
        assertThat(paid.at("/fields/receipt/tranId").asText()).isEqualTo("0000000996722788");
        assertThat(paid.at("/fields/receipt/accountId").asText()).isEqualTo(ACCT1);
        assertThat(paid.at("/fields/receipt/amountPaid").asText()).isEqualTo("194.00");
        assertThat(paid.at("/fields/receipt/newBalance").asText()).isEqualTo("0.00");

        Map<String, Object> tran = jdbc.queryForMap("SELECT * FROM card_transaction WHERE tran_id = '0000000996722788'");
        assertThat(tran.get("type_cd")).isEqualTo("02");
        assertThat(tran.get("cat_cd")).isEqualTo((short) 2);
        assertThat(tran.get("source")).isEqualTo("POS TERM");
        assertThat(tran.get("description")).isEqualTo("BILL PAYMENT - ONLINE");
        assertThat((BigDecimal) tran.get("amount")).isEqualByComparingTo("194.00");
        assertThat(tran.get("merchant_id")).isEqualTo(999999999);
        assertThat(tran.get("merchant_name")).isEqualTo("BILL PAYMENT");
        assertThat(tran.get("merchant_city")).isEqualTo("N/A");
        assertThat(tran.get("merchant_zip")).isEqualTo("N/A");
        assertThat((String) tran.get("card_num")).endsWith("3697")
            .isEqualTo(jdbc.queryForObject("SELECT MIN(card_num) FROM card_xref WHERE account_id = 1", String.class));
        String ts = (String) tran.get("orig_ts_text");
        assertThat(ts).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.000000").isEqualTo(tran.get("proc_ts_text"));
        assertThat(tran.get("orig_ts")).isNotNull();

        Map<String, Object> after = accountRow(1);
        assertThat((BigDecimal) after.get("current_balance")).isEqualByComparingTo("0.00");
        after.remove("current_balance");
        before.remove("current_balance");
        assertThat(after).isEqualTo(before);
        assertThat(transactionCount()).isEqualTo(count + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tran_cat_balance WHERE account_id = 1 AND type_cd = '02'", Integer.class))
            .as("Q-COBIL00C-06: TCATBAL untouched").isZero();
    }

    @Test
    @Covers({"TS-COBIL00C-02", "BR-COBIL00C-08"})
    void confirm_on_first_enter_pays_at_once() {
        client.signOn(ownerOf(2));
        JsonNode r = enter(ACCT2, "y", "");
        assertThat(r.at("/message").asText()).startsWith("Payment successful.  Your Transaction ID is ");
        assertThat(r.at("/fields/receipt/amountPaid").asText()).isEqualTo("158.00");
        assertThat(balance(2)).isEqualByComparingTo("0.00");
    }

    @Test
    @Covers({"TS-COBIL00C-03", "TS-COBIL00C-04", "BR-COBIL00C-02"})
    void nothing_to_pay_for_zero_or_negative_balance() {
        client.signOn(ownerOf(1));
        enter(ACCT1, "Y", "");
        int count = transactionCount();
        for (String confirm : new String[] {"", "Y"}) {
            JsonNode r = enter(ACCT1, confirm, "");
            assertThat(r.at("/message").asText()).isEqualTo("You have nothing to pay...");
            assertThat(r.at("/fields/currentBalance").asText()).isEqualTo("+0000000000.00");
            assertThat(r.at("/focus").asText()).isEqualTo("accountId");
        }
        jdbc.update("UPDATE account SET current_balance = -25.00 WHERE account_id = 1");
        JsonNode neg = enter(ACCT1, "Y", "");
        assertThat(neg.at("/message").asText()).isEqualTo("You have nothing to pay...");
        assertThat(neg.at("/fields/currentBalance").asText()).isEqualTo("-0000000025.00");
        assertThat(transactionCount()).isEqualTo(count);
        assertThat(balance(1)).isEqualByComparingTo("-25.00");
    }

    @Test
    @Covers({"TS-COBIL00C-05", "VR-COBIL00C-01"})
    void blank_account_id() {
        client.signOn(ownerOf(1));
        JsonNode r = enter("", "Y", "");
        assertThat(r.at("/message").asText()).isEqualTo("Acct ID can NOT be empty...");
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
        assertThat(enter("   ", "", "").at("/message").asText()).isEqualTo("Acct ID can NOT be empty...");
    }

    @Test
    @Covers({"TS-COBIL00C-06", "VR-COBIL00C-03", "Q-COBIL00C-04", "C-KEYLEN"})
    void unknown_or_short_account_id_is_not_found() {
        client.signOn(ownerOf(1));
        for (String id : new String[] {"99999999999", "1", "ABC", "0000000001"}) {
            JsonNode r = enter(id, "", "");
            assertThat(r.at("/message").asText()).as(id).isEqualTo("Account ID NOT found...");
            assertThat(r.at("/focus").asText()).isEqualTo("accountId");
        }
    }

    @Test
    @Covers({"TS-COBIL00C-07", "VR-COBIL00C-02", "Q-COBIL00C-09"})
    void invalid_confirm_value_keeps_the_screen_and_writes_nothing() {
        client.signOn(ownerOf(1));
        int count = transactionCount();
        JsonNode r = enter(ACCT1, "X", "+0000000111.00");
        assertThat(r.at("/message").asText()).isEqualTo("Invalid value. Valid values are (Y/N)...");
        assertThat(r.at("/focus").asText()).isEqualTo("confirm");
        assertThat(r.at("/fields/currentBalance").asText()).as("not refreshed").isEqualTo("+0000000111.00");
        assertThat(transactionCount()).isEqualTo(count);
        assertThat(balance(1)).isEqualByComparingTo("194.00");
    }

    @Test
    @Covers({"TS-COBIL00C-08", "BR-COBIL00C-08"})
    void n_clears_the_form_without_message() {
        client.signOn(ownerOf(1));
        JsonNode r = enter(ACCT1, "N", "+0000000194.00");
        assertThat(r.at("/fields/accountId").asText()).isEmpty();
        assertThat(r.at("/fields/currentBalance").asText()).isEmpty();
        assertThat(r.at("/fields/confirm").asText()).isEmpty();
        assertThat(r.has("message")).isFalse();
        assertThat(balance(1)).isEqualByComparingTo("194.00");
    }

    @Test
    @Covers({"TS-COBIL00C-09", "NAV-COBIL00C-04", "NAV-COBIL00C-05", "NAV-COBIL00C-06"})
    void keys() {
        client.signOn(ownerOf(1));
        JsonNode clear = client.post("COBIL00C", "PF4", Map.of("accountId", ACCT1, "confirm", "Y", "shownBalance", "+0000000194.00"));
        assertThat(clear.at("/fields/accountId").asText()).isEmpty();
        assertThat(clear.at("/fields/currentBalance").asText()).isEmpty();
        assertThat(clear.has("message")).isFalse();
        assertThat(clear.at("/focus").asText()).isEqualTo("accountId");
        for (String key : new String[] {"PF5", "PF1", "PF15", "PA1", "CLEAR"}) {
            JsonNode inv = client.post("COBIL00C", key, Map.of("accountId", ACCT1, "confirm", "Y"));
            assertThat(inv.at("/message").asText()).as(key).isEqualTo(CommonMessages.INVALID_KEY);
            assertThat(inv.at("/fields/accountId").asText()).isEqualTo(ACCT1);
        }
        assertThat(balance(1)).isEqualByComparingTo("194.00");
        assertThat(client.post("COBIL00C", "PF3", Map.of()).at("/navigate").asText()).isEqualTo("COMEN01C");
    }

    @Test
    @Covers({"TS-COBIL00C-10", "NAV-COBIL00C-01", "C-ROLE"})
    void without_a_session_the_screen_is_refused() {
        assertThat(client.showRaw("COBIL00C").getStatus()).isEqualTo(401);
        assertThat(client.turn("COBIL00C", "ENTER", Map.of("accountId", ACCT1, "confirm", "Y")).getStatus()).isEqualTo(401);
        assertThat(balance(1)).isEqualByComparingTo("194.00");
    }

    @Test
    @Covers({"C-SCOPE", "D-USER-ACCT"})
    void another_users_account_is_answered_as_not_found() {
        String owner1 = ownerOf(1);
        String other = jdbc.queryForObject(
            "SELECT MIN(account_id) FROM user_account WHERE user_id <> ?", Long.class, owner1).toString();
        String otherId = "0".repeat(11 - other.length()) + other;
        client.signOn(owner1);
        BigDecimal before = balance(Long.parseLong(other));
        for (String confirm : new String[] {"", "Y"}) {
            assertThat(enter(otherId, confirm, "").at("/message").asText()).isEqualTo("Account ID NOT found...");
        }
        assertThat(balance(Long.parseLong(other))).isEqualByComparingTo(before);
    }

    @Test
    @Covers({"Q-COBIL00C-08"})
    void balance_changed_after_display_asks_for_a_new_confirmation() {
        client.signOn(ownerOf(1));
        JsonNode shown = enter(ACCT1, "", "");
        jdbc.update("UPDATE account SET current_balance = 200.00 WHERE account_id = 1");
        int count = transactionCount();
        JsonNode r = enter(ACCT1, "Y", shown.at("/fields/currentBalance").asText());
        assertThat(r.at("/message").asText()).isEqualTo("Confirm to make a bill payment...");
        assertThat(r.at("/fields/currentBalance").asText()).isEqualTo("+0000000200.00");
        assertThat(r.at("/fields/confirm").asText()).isEmpty();
        assertThat(r.at("/focus").asText()).isEqualTo("confirm");
        assertThat(transactionCount()).isEqualTo(count);
        assertThat(balance(1)).isEqualByComparingTo("200.00");

        JsonNode paid = enter(ACCT1, "Y", r.at("/fields/currentBalance").asText());
        assertThat(paid.at("/fields/receipt/amountPaid").asText()).isEqualTo("200.00");
    }

    @Test
    @Covers({"Q-COBIL00C-07"})
    void balance_too_large_for_tran_amt_is_refused() {
        client.signOn(ownerOf(1));
        jdbc.update("UPDATE account SET current_balance = 1000000000.00 WHERE account_id = 1");
        int count = transactionCount();
        JsonNode r = enter(ACCT1, "Y", "");
        assertThat(r.at("/message").asText()).isEqualTo("Balance too large for online payment...");
        assertThat(transactionCount()).isEqualTo(count);
        assertThat(balance(1)).isEqualByComparingTo("1000000000.00");

        jdbc.update("UPDATE account SET current_balance = 999999999.99 WHERE account_id = 1");
        assertThat(enter(ACCT1, "Y", "").at("/fields/receipt/amountPaid").asText()).isEqualTo("999999999.99");
    }

    @Test
    @Covers({"Q-COBIL00C-11", "DF-6", "BR-COBIL00C-07"})
    void account_with_several_cards_pays_with_the_lowest_card_number() {
        client.signOn(ownerOf(1));
        String existing = jdbc.queryForObject("SELECT MIN(card_num) FROM card_xref WHERE account_id = 1", String.class);
        jdbc.update("INSERT INTO card_xref (card_num, customer_id, account_id) VALUES ('0000000000000007', 1, 1)");
        jdbc.update("INSERT INTO card_xref (card_num, customer_id, account_id) VALUES ('9999999999999999', 1, 1)");
        JsonNode r = enter(ACCT1, "Y", "");
        String tranId = r.at("/fields/receipt/tranId").asText();
        assertThat(jdbc.queryForObject("SELECT card_num FROM card_transaction WHERE tran_id = ?", String.class, tranId))
            .isEqualTo("0000000000000007").isNotEqualTo(existing);
    }

    @Test
    @Covers({"Q-COBIL00C-12", "BR-COBIL00C-03"})
    void first_payment_on_an_empty_transaction_table_gets_id_1() {
        jdbc.update("DELETE FROM card_transaction");
        client.signOn(ownerOf(1));
        JsonNode r = enter(ACCT1, "Y", "");
        assertThat(r.at("/message").asText()).isEqualTo("Payment successful.  Your Transaction ID is 0000000000000001.");
    }

    @Test
    @Covers({"Q-COBIL00C-01", "BR-COBIL00C-03"})
    void ids_ignore_non_numeric_batch_keys_and_never_go_backwards() {
        jdbc.update("DELETE FROM card_transaction");
        jdbc.update("""
            INSERT INTO card_transaction (tran_id, type_cd, cat_cd, amount, card_num)
            VALUES ('2022-07-18000001', '01', 5, 1.00, '0000000000000001'), ('0000000000000500', '01', 5, 1.00, '0000000000000001')
            """);
        client.signOn(ownerOf(1));
        assertThat(enter(ACCT1, "Y", "").at("/fields/receipt/tranId").asText()).isEqualTo("0000000000000501");
        jdbc.update("DELETE FROM card_transaction WHERE tran_id = '0000000000000501'");
        client.signOn(ownerOf(2));
        assertThat(enter(ACCT2, "Y", "").at("/fields/receipt/tranId").asText())
            .as("counter keeps IDs unique even if the highest row is removed").isEqualTo("0000000000000502");
    }
}
