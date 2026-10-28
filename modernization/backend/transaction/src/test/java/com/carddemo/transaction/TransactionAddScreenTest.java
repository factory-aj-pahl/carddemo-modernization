package com.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.msg.CommonMessages;
import com.carddemo.test.Covers;
import com.carddemo.test.ScreenClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** COTRN02C screen turns against the 300 dailytran.txt records (highest ID 0000000996722787). */
class TransactionAddScreenTest extends TransactionScreenTestBase {

    static final String ACCT1 = "00000000001";
    static final String CONFIRM = "Confirm to add this transaction...";

    /** The spec's "sample form". */
    static Map<String, String> sampleForm() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("typeCd", "01");
        f.put("catCd", "0001");
        f.put("source", "POS TERM");
        f.put("description", "Purchase at Test Store");
        f.put("amount", "+00000025.50");
        f.put("origDate", "2022-06-15");
        f.put("procDate", "2022-06-15");
        f.put("merchantId", "800000001");
        f.put("merchantName", "Test Store");
        f.put("merchantCity", "Seattle");
        f.put("merchantZip", "98101");
        f.put("confirm", "");
        return f;
    }

    private static Map<String, String> form(String acct, String card, Map<String, String> changes) {
        Map<String, String> f = sampleForm();
        f.put("accountId", acct);
        f.put("cardNum", card);
        f.putAll(changes);
        return f;
    }

    private JsonNode enter(Map<String, String> f) {
        return client.post("COTRN02C", "ENTER", f);
    }

    /** Sends the response's fields back with changes, as the browser does. */
    private static Map<String, String> echo(JsonNode r, Map<String, String> changes) {
        Map<String, String> f = new HashMap<>();
        r.get("fields").fields().forEachRemaining(e -> f.put(e.getKey(), e.getValue().asText()));
        f.putAll(changes);
        return f;
    }

    private String cardOf(long account) {
        return jdbc.queryForObject("SELECT MIN(card_num) FROM card_xref WHERE account_id = ?", String.class, account);
    }

    private BigDecimal balance(long account) {
        return jdbc.queryForObject("SELECT current_balance FROM account WHERE account_id = ?", BigDecimal.class, account);
    }

    private String message(Map<String, String> f) {
        return enter(f).at("/message").asText();
    }

    @Test
    @Covers({"NAV-COTRN02C-02"})
    void first_entry_is_a_blank_form() {
        client.signOn(ownerOf(1));
        JsonNode r = client.show("COTRN02C");
        r.get("fields").fields().forEachRemaining(e -> assertThat(e.getValue().asText()).as(e.getKey()).isEmpty());
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
    }

    @Test
    @Covers({"TS-COTRN02C-01", "TS-COTRN02C-02", "BR-COTRN02C-01", "BR-COTRN02C-02", "BR-COTRN02C-03", "BR-COTRN02C-04",
        "BR-COTRN02C-05", "BR-COTRN02C-06", "BR-COTRN02C-07", "BR-COTRN02C-08", "VR-COTRN02C-25", "Q-COTRN02C-01",
        "Q-COTRN02C-02", "Q-COTRN02C-03", "Q-COTRN02C-15", "C-MASK", "NAV-COTRN02C-03"})
    void confirm_prompt_then_add() {
        client.signOn(ownerOf(1));
        String card = cardOf(1);
        int count = transactionCount();
        JsonNode shown = enter(form(ACCT1, "", Map.of()));
        assertThat(shown.at("/fields/cardNum").asText()).isEqualTo("************3697");
        assertThat(card).endsWith("3697");
        assertThat(shown.toString()).doesNotContain(card);
        assertThat(shown.at("/fields/amount").asText()).isEqualTo("+00000025.50");
        assertThat(shown.at("/message").asText()).isEqualTo(CONFIRM);
        assertThat(shown.at("/focus").asText()).isEqualTo("confirm");
        assertThat(transactionCount()).isEqualTo(count);

        JsonNode added = enter(echo(shown, Map.of("confirm", "Y")));
        assertThat(added.at("/message").asText()).isEqualTo("Transaction added successfully.  Your Tran ID is 0000000996722788.");
        assertThat(added.at("/messageColor").asText()).isEqualTo("GREEN");
        assertThat(added.at("/focus").asText()).isEqualTo("accountId");
        added.get("fields").fields().forEachRemaining(e -> assertThat(e.getValue().asText()).as(e.getKey()).isEmpty());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM card_transaction WHERE tran_id = '0000000996722788'");
        assertThat(row.get("card_num")).isEqualTo(card);
        assertThat(row.get("type_cd")).isEqualTo("01");
        assertThat(row.get("cat_cd")).isEqualTo((short) 1);
        assertThat(row.get("source")).isEqualTo("POS TERM");
        assertThat(row.get("description")).isEqualTo("Purchase at Test Store");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("25.50");
        assertThat(row.get("merchant_id")).isEqualTo(800000001);
        assertThat(row.get("merchant_name")).isEqualTo("Test Store");
        assertThat(row.get("merchant_city")).isEqualTo("Seattle");
        assertThat(row.get("merchant_zip")).isEqualTo("98101");
        assertThat(row.get("orig_ts_text")).isEqualTo("2022-06-15");
        assertThat(row.get("proc_ts_text")).isEqualTo("2022-06-15");
        assertThat(row.get("proc_ts").toString()).startsWith("2022-06-15");
        assertThat(transactionCount()).isEqualTo(count + 1);
        assertThat(balance(1)).isEqualByComparingTo("194.00");
    }

    @Test
    @Covers({"TS-COTRN02C-03", "BR-COTRN02C-01", "Q-COTRN02C-06"})
    void card_only_fills_the_account_and_keeps_the_typed_card() {
        client.signOn(ownerOf(1));
        jdbc.update("INSERT INTO card_xref (card_num, customer_id, account_id) VALUES ('9999999999990001', 1, 1)");
        JsonNode r = enter(form("", "9999999999990001", Map.of()));
        assertThat(r.at("/fields/accountId").asText()).isEqualTo(ACCT1);
        assertThat(r.at("/fields/cardNum").asText()).isEqualTo("************0001");
        assertThat(r.at("/message").asText()).isEqualTo(CONFIRM);
        JsonNode added = enter(echo(r, Map.of("confirm", "y")));
        String id = added.at("/message").asText().replaceAll("\\D", "");
        assertThat(jdbc.queryForObject("SELECT card_num FROM card_transaction WHERE tran_id = ?", String.class, id))
            .as("not re-derived from the account on the confirming turn").isEqualTo("9999999999990001");
    }

    @Test
    @Covers({"Q-COTRN02C-06"})
    void a_card_of_another_account_does_not_match() {
        client.signOn(ADMIN);
        int count = transactionCount();
        JsonNode r = enter(form(ACCT1, cardOf(2), Map.of("confirm", "Y")));
        assertThat(r.at("/message").asText()).isEqualTo("Card Number does not match the Account ID...");
        assertThat(r.at("/focus").asText()).isEqualTo("cardNum");
        assertThat(r.at("/fields/cardNum").asText()).isEqualTo("************" + cardOf(2).substring(12));
        assertThat(transactionCount()).isEqualTo(count);

        jdbc.update("INSERT INTO card_xref (card_num, customer_id, account_id) VALUES ('9999999999990001', 1, 1)");
        JsonNode both = enter(form(ACCT1, "9999999999990001", Map.of("confirm", "Y")));
        String id = both.at("/message").asText().replaceAll("\\D", "");
        assertThat(jdbc.queryForObject("SELECT card_num FROM card_transaction WHERE tran_id = ?", String.class, id))
            .as("typed card is stored").isEqualTo("9999999999990001");
    }

    @Test
    @Covers({"Q-COTRN02C-07", "DF-6"})
    void account_with_several_cards_uses_the_lowest() {
        client.signOn(ownerOf(1));
        jdbc.update("INSERT INTO card_xref (card_num, customer_id, account_id) VALUES ('0000000000000007', 1, 1)");
        JsonNode r = enter(form(ACCT1, "", Map.of("confirm", "Y")));
        String id = r.at("/message").asText().replaceAll("\\D", "");
        assertThat(jdbc.queryForObject("SELECT card_num FROM card_transaction WHERE tran_id = ?", String.class, id))
            .isEqualTo("0000000000000007");
    }

    @Test
    @Covers({"Q-COTRN02C-15", "C-MASK"})
    void a_changed_account_derives_its_own_card() {
        client.signOn(ADMIN);
        JsonNode r = enter(form(ACCT1, "", Map.of()));
        JsonNode other = enter(echo(r, Map.of("accountId", "00000000002", "confirm", "Y")));
        String id = other.at("/message").asText().replaceAll("\\D", "");
        assertThat(jdbc.queryForObject("SELECT card_num FROM card_transaction WHERE tran_id = ?", String.class, id))
            .isEqualTo(cardOf(2));
    }

    @Test
    @Covers({"TS-COTRN02C-04", "VR-COTRN02C-05"})
    void account_or_card_is_required() {
        client.signOn(ownerOf(1));
        JsonNode r = enter(form("", "", Map.of()));
        assertThat(r.at("/message").asText()).isEqualTo("Account or Card Number must be entered...");
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
    }

    @Test
    @Covers({"TS-COTRN02C-05", "VR-COTRN02C-01", "VR-COTRN02C-03", "Q-COTRN02C-08", "C-KEYLEN"})
    void keys_must_be_typed_at_full_length() {
        client.signOn(ownerOf(1));
        for (String acct : new String[] {"ABC", "1", "0000000001 "}) {
            JsonNode r = enter(form(acct, "", Map.of()));
            assertThat(r.at("/message").asText()).as(acct).isEqualTo("Account ID must be Numeric...");
            assertThat(r.at("/focus").asText()).isEqualTo("accountId");
        }
        JsonNode card = enter(form("", "3697", Map.of()));
        assertThat(card.at("/message").asText()).isEqualTo("Card Number must be Numeric...");
        assertThat(card.at("/focus").asText()).isEqualTo("cardNum");
    }

    @Test
    @Covers({"TS-COTRN02C-06", "VR-COTRN02C-02", "VR-COTRN02C-04"})
    void unknown_account_or_card() {
        client.signOn(ADMIN);
        assertThat(message(form("99999999999", "", Map.of()))).isEqualTo("Account ID NOT found...");
        JsonNode r = enter(form("", "0000000000000000", Map.of()));
        assertThat(r.at("/message").asText()).isEqualTo("Card Number NOT found...");
        assertThat(r.at("/focus").asText()).isEqualTo("cardNum");
    }

    @Test
    @Covers({"Q-COTRN02C-14", "C-SCOPE", "D-USER-ACCT"})
    void a_user_adds_only_for_linked_accounts_and_cards() {
        String user = ownerOf(1);
        long other = jdbc.queryForObject("""
            SELECT MIN(x.account_id) FROM card_xref x WHERE x.account_id NOT IN (SELECT account_id FROM user_account WHERE user_id = ?)
            """, Long.class, user);
        client.signOn(user);
        int count = transactionCount();
        assertThat(message(form(String.format("%011d", other), "", Map.of("confirm", "Y")))).isEqualTo("Account ID NOT found...");
        assertThat(message(form("", cardOf(other), Map.of("confirm", "Y")))).isEqualTo("Card Number NOT found...");
        assertThat(transactionCount()).isEqualTo(count);
    }

    @Test
    @Covers({"TS-COTRN02C-07", "VR-COTRN02C-06", "BR-COTRN02C-02", "Q-COTRN02C-11"})
    void empty_data_fields_report_the_first_one() {
        client.signOn(ownerOf(1));
        Map<String, String> blank = new HashMap<>();
        sampleForm().keySet().forEach(k -> blank.put(k, ""));
        JsonNode r = enter(form(ACCT1, "", blank));
        assertThat(r.at("/message").asText()).isEqualTo("Type CD can NOT be empty...");
        assertThat(r.at("/focus").asText()).isEqualTo("typeCd");
        assertThat(r.at("/fields/cardNum").asText()).isEqualTo("************3697");
    }

    @Test
    @Covers({"VR-COTRN02C-06", "VR-COTRN02C-07", "VR-COTRN02C-08", "VR-COTRN02C-09", "VR-COTRN02C-10", "VR-COTRN02C-11",
        "VR-COTRN02C-12", "VR-COTRN02C-13", "VR-COTRN02C-14", "VR-COTRN02C-15", "VR-COTRN02C-16", "Q-COTRN02C-11"})
    void each_empty_field_has_its_message_in_screen_order() {
        client.signOn(ownerOf(1));
        String[][] cases = {
            {"typeCd", "Type CD can NOT be empty..."}, {"catCd", "Category CD can NOT be empty..."},
            {"source", "Source can NOT be empty..."}, {"description", "Description can NOT be empty..."},
            {"amount", "Amount can NOT be empty..."}, {"origDate", "Orig Date can NOT be empty..."},
            {"procDate", "Proc Date can NOT be empty..."}, {"merchantId", "Merchant ID can NOT be empty..."},
            {"merchantName", "Merchant Name can NOT be empty..."}, {"merchantCity", "Merchant City can NOT be empty..."},
            {"merchantZip", "Merchant Zip can NOT be empty..."}};
        for (String[] c : cases) {
            Map<String, String> change = new HashMap<>();
            change.put("merchantId", "ABC");
            change.put(c[0], "  ");
            JsonNode r = enter(form(ACCT1, "", change));
            assertThat(r.at("/message").asText()).as("emptiness comes before the numeric checks").isEqualTo(c[1]);
            assertThat(r.at("/focus").asText()).isEqualTo(c[0]);
        }
    }

    @Test
    @Covers({"TS-COTRN02C-08", "VR-COTRN02C-17", "VR-COTRN02C-18", "VR-COTRN02C-19", "Q-COTRN02C-08"})
    void numeric_and_amount_formats() {
        client.signOn(ownerOf(1));
        assertThat(message(form(ACCT1, "", Map.of("typeCd", "1")))).isEqualTo("Type CD must be Numeric...");
        assertThat(message(form(ACCT1, "", Map.of("catCd", "1")))).isEqualTo("Category CD must be Numeric...");
        for (String amt : new String[] {"25.50", "00000025.50", "+0000025.500", "+00000025,50", "+0000002A.50"}) {
            JsonNode r = enter(form(ACCT1, "", Map.of("amount", amt)));
            assertThat(r.at("/message").asText()).as(amt).isEqualTo("Amount should be in format -99999999.99");
            assertThat(r.at("/focus").asText()).isEqualTo("amount");
        }
        JsonNode zero = enter(form(ACCT1, "", Map.of("amount", "-00000000.00")));
        assertThat(zero.at("/message").asText()).isEqualTo(CONFIRM);
        assertThat(zero.at("/fields/amount").asText()).as("normalized").isEqualTo("+00000000.00");
        assertThat(enter(form(ACCT1, "", Map.of("amount", "-00000050.00"))).at("/fields/amount").asText()).isEqualTo("-00000050.00");
    }

    @Test
    @Covers({"Q-COTRN02C-09"})
    void type_and_category_must_exist_in_the_reference_data() {
        client.signOn(ownerOf(1));
        JsonNode t = enter(form(ACCT1, "", Map.of("typeCd", "99")));
        assertThat(t.at("/message").asText()).isEqualTo("Type CD NOT found...");
        assertThat(t.at("/focus").asText()).isEqualTo("typeCd");
        JsonNode c = enter(form(ACCT1, "", Map.of("catCd", "0099")));
        assertThat(c.at("/message").asText()).isEqualTo("Category CD NOT found...");
        assertThat(c.at("/focus").asText()).isEqualTo("catCd");
        assertThat(message(form(ACCT1, "", Map.of("typeCd", "02", "catCd", "0002", "amount", "-00000010.00", "source", "x"))))
            .as("negative amounts and free-text source stay accepted").isEqualTo(CONFIRM);
    }

    @Test
    @Covers({"TS-COTRN02C-09", "VR-COTRN02C-20", "VR-COTRN02C-21", "VR-COTRN02C-22", "VR-COTRN02C-23", "Q-COTRN02C-12", "DF-8"})
    void date_formats_and_calendar_checks() {
        client.signOn(ownerOf(1));
        assertThat(message(form(ACCT1, "", Map.of("origDate", "06/15/2022")))).isEqualTo("Orig Date should be in format YYYY-MM-DD");
        assertThat(message(form(ACCT1, "", Map.of("procDate", "2022-6-15")))).isEqualTo("Proc Date should be in format YYYY-MM-DD");
        JsonNode orig = enter(form(ACCT1, "", Map.of("origDate", "2023-02-29", "amount", "-00000000.00")));
        assertThat(orig.at("/message").asText()).isEqualTo("Orig Date - Not a valid date...");
        assertThat(orig.at("/focus").asText()).isEqualTo("origDate");
        assertThat(orig.at("/fields/amount").asText()).as("amount normalized before the date check").isEqualTo("+00000000.00");
        assertThat(message(form(ACCT1, "", Map.of("procDate", "2022-06-31")))).isEqualTo("Proc Date - Not a valid date...");
        assertThat(message(form(ACCT1, "", Map.of("procDate", "1500-01-01")))).isEqualTo("Proc Date - Not a valid date...");
        assertThat(message(form(ACCT1, "", Map.of("origDate", "1582-10-14")))).isEqualTo("Orig Date - Not a valid date...");
        assertThat(message(form(ACCT1, "", Map.of("origDate", "1582-10-15")))).isEqualTo(CONFIRM);
    }

    @Test
    @Covers({"TS-COTRN02C-10", "VR-COTRN02C-24"})
    void merchant_id_must_be_numeric() {
        client.signOn(ownerOf(1));
        for (String mid : new String[] {"ABC", "80000000"}) {
            JsonNode r = enter(form(ACCT1, "", Map.of("merchantId", mid)));
            assertThat(r.at("/message").asText()).as(mid).isEqualTo("Merchant ID must be Numeric...");
            assertThat(r.at("/focus").asText()).isEqualTo("merchantId");
        }
    }

    @Test
    @Covers({"TS-COTRN02C-11", "VR-COTRN02C-25", "VR-COTRN02C-26"})
    void confirmation_values() {
        client.signOn(ownerOf(1));
        int count = transactionCount();
        JsonNode x = enter(form(ACCT1, "", Map.of("confirm", "X")));
        assertThat(x.at("/message").asText()).isEqualTo("Invalid value. Valid values are (Y/N)...");
        assertThat(x.at("/focus").asText()).isEqualTo("confirm");
        for (String c : new String[] {"N", "n", " "}) {
            JsonNode r = enter(form(ACCT1, "", Map.of("confirm", c)));
            assertThat(r.at("/message").asText()).as(c).isEqualTo(CONFIRM);
            assertThat(r.at("/focus").asText()).isEqualTo("confirm");
        }
        assertThat(transactionCount()).isEqualTo(count);
    }

    @Test
    @Covers({"TS-COTRN02C-12", "BR-COTRN02C-09", "NAV-COTRN02C-06", "Q-COTRN02C-05"})
    void f5_copies_the_highest_transaction() {
        client.signOn(ADMIN);
        Map<String, String> f = new HashMap<>();
        f.put("accountId", ACCT1);
        int count = transactionCount();
        JsonNode r = client.post("COTRN02C", "PF5", f);
        assertThat(r.at("/fields/cardNum").asText()).isEqualTo("************3697");
        assertThat(r.at("/fields/typeCd").asText()).isEqualTo("01");
        assertThat(r.at("/fields/catCd").asText()).isEqualTo("0001");
        assertThat(r.at("/fields/source").asText()).isEqualTo("POS TERM");
        assertThat(r.at("/fields/amount").asText()).isEqualTo("+00000603.22");
        assertThat(r.at("/fields/description").asText()).isEqualTo("Purchase at Kilback LLC");
        assertThat(r.at("/fields/origDate").asText()).isEqualTo("2022-06-10");
        assertThat(r.at("/fields/procDate").asText()).isEmpty();
        assertThat(r.at("/fields/merchantId").asText()).isEqualTo("800000000");
        assertThat(r.at("/fields/merchantName").asText()).isEqualTo("Kilback LLC");
        assertThat(r.at("/fields/merchantCity").asText()).isEqualTo("Cummeratamouth");
        assertThat(r.at("/fields/merchantZip").asText()).isEqualTo("53200-7529");
        assertThat(r.at("/message").asText()).isEqualTo("Proc Date can NOT be empty...");
        assertThat(r.at("/focus").asText()).isEqualTo("procDate");
        assertThat(transactionCount()).isEqualTo(count);
    }

    @Test
    @Covers({"TS-COTRN02C-13", "Q-COTRN02C-04"})
    void f5_with_y_shows_the_copy_for_review_and_writes_nothing() {
        client.signOn(ADMIN);
        enter(form(ACCT1, "", Map.of("confirm", "Y")));
        int count = transactionCount();
        Map<String, String> f = new HashMap<>();
        f.put("accountId", ACCT1);
        f.put("confirm", "Y");
        JsonNode r = client.post("COTRN02C", "PF5", f);
        assertThat(r.at("/message").asText()).isEqualTo(CONFIRM);
        assertThat(r.at("/fields/procDate").asText()).isEqualTo("2022-06-15");
        assertThat(r.at("/fields/description").asText()).isEqualTo("Purchase at Test Store");
        assertThat(r.at("/fields/confirm").asText()).isEmpty();
        assertThat(transactionCount()).isEqualTo(count);
    }

    @Test
    @Covers({"Q-COTRN02C-04", "C-SCOPE"})
    void f5_for_a_user_copies_the_highest_in_scope_transaction() {
        String user = ownerOf(1);
        List<String> mine = inScopeIds(user);
        String highest = mine.get(mine.size() - 1);
        String desc = jdbc.queryForObject("SELECT description FROM card_transaction WHERE tran_id = ?", String.class, highest);
        client.signOn(user);
        JsonNode r = client.post("COTRN02C", "PF5", Map.of("accountId", ACCT1));
        assertThat(r.at("/fields/description").asText()).isEqualTo(desc.length() > 60 ? desc.substring(0, 60) : desc);
    }

    @Test
    @Covers({"Q-COTRN02C-04"})
    void f5_reports_key_errors_first() {
        client.signOn(ownerOf(1));
        assertThat(client.post("COTRN02C", "PF5", Map.of()).at("/message").asText())
            .isEqualTo("Account or Card Number must be entered...");
    }

    @Test
    @Covers({"TS-COTRN02C-14", "BR-COTRN02C-10", "NAV-COTRN02C-04", "NAV-COTRN02C-05", "NAV-COTRN02C-07"})
    void keys() {
        client.signOn(ownerOf(1));
        JsonNode cleared = client.post("COTRN02C", "PF4", form(ACCT1, "", Map.of("confirm", "Y")));
        cleared.get("fields").fields().forEachRemaining(e -> assertThat(e.getValue().asText()).as(e.getKey()).isEmpty());
        assertThat(cleared.has("message")).isFalse();
        assertThat(cleared.at("/focus").asText()).isEqualTo("accountId");
        int count = transactionCount();
        JsonNode inv = client.post("COTRN02C", "PF7", form(ACCT1, "", Map.of("confirm", "Y")));
        assertThat(inv.at("/message").asText()).isEqualTo(CommonMessages.INVALID_KEY);
        assertThat(inv.at("/fields/accountId").asText()).isEqualTo(ACCT1);
        assertThat(inv.at("/fields/description").asText()).isEqualTo("Purchase at Test Store");
        assertThat(inv.at("/fields/confirm").asText()).isEqualTo("Y");
        assertThat(transactionCount()).isEqualTo(count);
        assertThat(client.post("COTRN02C", "PF3", Map.of()).at("/navigate").asText()).isEqualTo("COMEN01C");
    }

    @Test
    @Covers({"NAV-COTRN02C-01", "C-ROLE"})
    void without_a_session_the_add_screen_is_refused() {
        int count = transactionCount();
        assertThat(client.showRaw("COTRN02C").getStatus()).isEqualTo(401);
        assertThat(client.turn("COTRN02C", "ENTER", form(ACCT1, "", Map.of("confirm", "Y"))).getStatus()).isEqualTo(401);
        assertThat(transactionCount()).isEqualTo(count);
    }

    @Test
    @Covers({"Q-COTRN02C-13", "ERR-COTRN02C-03", "ERR-COTRN02C-05"})
    void first_transaction_on_an_empty_table_gets_id_1() {
        jdbc.update("DELETE FROM card_transaction");
        client.signOn(ownerOf(1));
        assertThat(message(form(ACCT1, "", Map.of("confirm", "Y"))))
            .isEqualTo("Transaction added successfully.  Your Tran ID is 0000000000000001.");
    }

    @Test
    @Covers({"TS-COTRN02C-15", "Q-COTRN02C-01", "ERR-COTRN02C-07"})
    void concurrent_adds_get_different_ids() throws Exception {
        String user = ownerOf(1);
        int n = 4;
        List<ScreenClient> clients = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ScreenClient c = new ScreenClient(mvc);
            c.signOn(user);
            clients.add(c);
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (ScreenClient c : clients) {
                Callable<String> task = () -> c.post("COTRN02C", "ENTER", form(ACCT1, "", Map.of("confirm", "Y"))).at("/message").asText();
                results.add(pool.submit(task));
            }
            List<String> messages = new ArrayList<>();
            for (Future<String> f : results) {
                messages.add(f.get());
            }
            assertThat(messages).allMatch(m -> m.startsWith("Transaction added successfully.  Your Tran ID is "));
            assertThat(messages.stream().distinct().count()).isEqualTo(n);
        } finally {
            pool.shutdownNow();
        }
    }
}
