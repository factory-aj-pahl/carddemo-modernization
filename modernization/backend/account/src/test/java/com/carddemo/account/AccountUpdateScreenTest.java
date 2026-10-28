package com.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.test.Covers;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** COACTUPC screen turns. */
class AccountUpdateScreenTest extends AccountScreenTestBase {

    static final String PROMPT = "Enter or update id of account to update";
    static final String SHOWN = "Update account details presented above.";
    static final String VALIDATED = "Changes validated.Press F5 to save";
    static final String COMMITTED = "Changes committed to database";
    static final String NO_CHANGE = "No change detected with respect to values fetched.";

    JsonNode last;

    private JsonNode turn(String key, Map<String, String> fields) {
        last = client.post("COACTUPC", key, Map.of("fields", fields));
        return last;
    }

    private JsonNode fetch(String account) {
        client.show("COACTUPC");
        return turn("ENTER", Map.of("accountId", account));
    }

    /** ENTER with the snapshot shown by the last S screen plus the edits. */
    private JsonNode change(JsonNode shown, Consumer<Map<String, String>> edits) {
        Map<String, String> form = new LinkedHashMap<>(values(shown));
        edits.accept(form);
        return turn("ENTER", form);
    }

    private static String mode(JsonNode r) {
        return r.at("/fields/mode").asText();
    }

    private static String info(JsonNode r) {
        return r.at("/fields/info").asText();
    }

    private static String msg(JsonNode r) {
        return r.has("message") ? r.get("message").asText() : "";
    }

    private Map<String, Object> customer(int id) {
        return jdbc.queryForMap("SELECT * FROM customer WHERE customer_id = ?", id);
    }

    private Map<String, Object> account(long id) {
        return jdbc.queryForMap("SELECT * FROM account WHERE account_id = ?", id);
    }

    /** Asserts state E with the message, the field in error, and focus on it. */
    private void assertEdit(JsonNode r, String message, String field) {
        assertThat(msg(r)).isEqualTo(message);
        assertThat(mode(r)).isEqualTo("E");
        assertThat(list(r, "errorFields")).contains(field);
        assertThat(r.at("/focus").asText()).isEqualTo(field);
        assertThat(info(r)).isEqualTo(SHOWN);
        assertThat(r.at("/fields/cancelKey").asBoolean()).isTrue();
        assertThat(r.at("/fields/saveKey").asBoolean()).isFalse();
    }

    private JsonNode fetched24() {
        client.signOn(ADMIN);
        JsonNode s = fetch("00000000024");
        assertThat(mode(s)).isEqualTo("S");
        return s;
    }

    @Test
    @Covers({"TS-COACTUPC-01", "NAV-COACTUPC-03", "BR-COACTUPC-06", "BR-COACTUPC-17", "BR-COACTUPC-18", "BR-COACTUPC-21",
        "BR-COACTUPC-22", "Q-COACTUPC-21"})
    void menu_entry_shows_the_empty_search_screen() {
        client.signOn(ADMIN);
        JsonNode r = client.show("COACTUPC");
        assertThat(mode(r)).isEqualTo("SEARCH");
        assertThat(values(r).values()).allMatch(String::isEmpty);
        assertThat(list(r, "editable")).containsExactly("accountId");
        assertThat(info(r)).isEqualTo(PROMPT);
        assertThat(r.at("/fields/saveKey").asBoolean()).isFalse();
        assertThat(r.at("/fields/cancelKey").asBoolean()).isFalse();
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
    }

    @Test
    @Covers({"TS-COACTUPC-02", "VR-COACTUPC-01", "BR-COACTUPC-07", "BR-COACTUPC-20"})
    void blank_account_number() {
        client.signOn(ADMIN);
        client.show("COACTUPC");
        for (String in : new String[] {"", "  ", "*"}) {
            JsonNode r = turn("ENTER", Map.of("accountId", in));
            assertThat(msg(r)).as(in).isEqualTo("No input received");
            assertThat(values(r).get("accountId")).isEqualTo("*");
            assertThat(list(r, "errorFields")).containsExactly("accountId");
            assertThat(r.at("/focus").asText()).isEqualTo("accountId");
            assertThat(mode(r)).isEqualTo("SEARCH");
        }
    }

    @Test
    @Covers({"TS-COACTUPC-03", "VR-COACTUPC-02", "Q-COACTUPC-24", "C-KEYLEN"})
    void account_number_must_be_eleven_digits_and_not_zero() {
        client.signOn(ADMIN);
        client.show("COACTUPC");
        for (String in : new String[] {"24", "0000000024", "00000000000", "0000000002X"}) {
            JsonNode r = turn("ENTER", Map.of("accountId", in));
            assertThat(msg(r)).as(in).isEqualTo("Account Number if supplied must be a 11 digit Non-Zero Number");
            assertThat(values(r).get("accountId")).isEqualTo(in);
            assertThat(list(r, "errorFields")).containsExactly("accountId");
            assertThat(mode(r)).isEqualTo("SEARCH");
        }
    }

    @Test
    @Covers({"TS-COACTUPC-04", "VR-COACTUPC-03", "ERR-COACTUPC-01"})
    void account_not_in_the_cross_reference() {
        client.signOn(ADMIN);
        JsonNode r = fetch("00000000099");
        assertThat(msg(r)).isEqualTo("Account:00000000099 not found in Cross ref file.  Resp:000000013  Reas:0000");
        assertThat(mode(r)).isEqualTo("SEARCH");
        assertThat(list(r, "errorFields")).containsExactly("accountId");
        assertThat(turn("ENTER", Map.of("accountId", "00000000024")).at("/fields/mode").asText())
            .as("still searching").isEqualTo("S");
    }

    @Test
    @Covers({"VR-COACTUPC-04", "ERR-COACTUPC-03", "ERR-COACTUPC-05", "Q-COACTUPC-20"})
    void missing_account_or_customer_stops_the_fetch() {
        jdbc.update("INSERT INTO card_xref (card_num, customer_id, account_id) VALUES ('7777777777777777', 1, 77)");
        jdbc.update("UPDATE card_xref SET customer_id = 999999 WHERE account_id = 2");
        client.signOn(ADMIN);
        JsonNode r = fetch("00000000077");
        assertThat(msg(r)).isEqualTo("Account:00000000077 not found in Acct Master file.Resp:000000013  Reas:0000");
        assertThat(mode(r)).isEqualTo("SEARCH");
        assertThat(values(r).get("firstName")).isEmpty();
        r = turn("ENTER", Map.of("accountId", "00000000002"));
        assertThat(msg(r)).isEqualTo("CustId:000999999 not found in customer master.Resp: 000000013  REAS:0000000");
        assertThat(mode(r)).isEqualTo("SEARCH");
        assertThat(values(r).get("creditLimit")).as("no details from a partial fetch").isEmpty();
        assertThat(list(r, "errorFields")).isEmpty();
    }

    @Test
    @Covers({"TS-COACTUPC-05", "BR-COACTUPC-02", "BR-COACTUPC-03", "BR-COACTUPC-04", "BR-COACTUPC-17", "BR-COACTUPC-18",
        "BR-COACTUPC-19", "BR-COACTUPC-24", "Q-COACTUPC-16", "Q-COACTUPC-17", "Q-COACTUPC-28", "Q-COACTUPC-29",
        "Q-COACTUPC-11", "C-MASK"})
    void fetch_shows_the_snapshot_masked_and_editable() {
        JsonNode r = fetched24();
        Map<String, String> v = values(r);
        String ssn = (String) customer(24).get("ssn");
        assertThat(v).containsEntry("accountId", "00000000024").containsEntry("active", "Y")
            .containsEntry("creditLimit", "+      5,174.00").containsEntry("cashCreditLimit", "+      4,129.00")
            .containsEntry("currentBalance", "+        400.00").containsEntry("cycleCredit", "+           .00")
            .containsEntry("cycleDebit", "+           .00")
            .containsEntry("openYear", "2015").containsEntry("openMonth", "08").containsEntry("openDay", "08")
            .containsEntry("expYear", "2025").containsEntry("expMonth", "02").containsEntry("expDay", "11")
            .containsEntry("risYear", "2025").containsEntry("risMonth", "02").containsEntry("risDay", "11")
            .containsEntry("group", "A000000000").containsEntry("customerId", "000000024")
            .containsEntry("ssn1", "***").containsEntry("ssn2", "**").containsEntry("ssn3", ssn.substring(5))
            .containsEntry("dobYear", "1996").containsEntry("dobMonth", "01").containsEntry("dobDay", "24")
            .containsEntry("fico", "711").containsEntry("firstName", "Stefanie").containsEntry("middleName", "Verla")
            .containsEntry("lastName", "Dickinson").containsEntry("addr1", "6367 Stracke River")
            .containsEntry("addr2", "Apt. 444").containsEntry("city", "East Otho").containsEntry("state", "KS")
            .containsEntry("zip", "15414").containsEntry("country", "USA")
            .containsEntry("phone1a", "617").containsEntry("phone1b", "348").containsEntry("phone1c", "9142")
            .containsEntry("phone2a", "330").containsEntry("phone2b", "116").containsEntry("phone2c", "5634")
            .containsEntry("govtId", "****************4633").containsEntry("eft", "0005459662")
            .containsEntry("primary", "Y");
        assertThat(info(r)).isEqualTo(SHOWN);
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("active");
        assertThat(list(r, "editable")).doesNotContain("accountId", "customerId", "country").contains("active", "zip",
            "ssn1", "govtId", "group", "currentBalance");
        assertThat(r.at("/fields/cancelKey").asBoolean()).as("F12 works in S but is not shown").isFalse();
        assertThat(r.toString()).doesNotContain(ssn).doesNotContain("00000000000439244633");
    }

    @Test
    @Covers({"TS-COACTUPC-06", "TS-COACTUPC-07", "VR-COACTUPC-05", "BR-COACTUPC-08", "Q-COACTUPC-06", "Q-COACTUPC-07"})
    void enter_without_changes_is_no_change_even_with_a_blank_group() {
        JsonNode s = fetched24();
        JsonNode r = change(s, f -> { });
        assertThat(msg(r)).isEqualTo(NO_CHANGE);
        assertThat(mode(r)).isEqualTo("S");
        assertThat(r.at("/focus").asText()).isEqualTo("active");
        assertThat(info(r)).isEqualTo(SHOWN);

        jdbc.update("UPDATE account SET group_id = '' WHERE account_id = 24");
        s = turn("PF12", values(s));
        assertThat(values(s).get("group")).isEmpty();
        r = change(s, f -> f.put("group", "   "));
        assertThat(msg(r)).as("blank, spaces, and LOW-VALUES compare equal").isEqualTo(NO_CHANGE);
        r = change(s, f -> {
            f.put("firstName", "  Stefanie   ");
            f.put("country", "XYZ");
            f.put("customerId", "000000001");
            f.put("accountId", "00000000001");
            f.put("creditLimit", "5174");
        });
        assertThat(msg(r)).as("trimmed, protected fields ignored, amounts compared as numbers").isEqualTo(NO_CHANGE);
    }

    @Test
    @Covers({"TS-COACTUPC-08", "TS-COACTUPC-09", "BR-COACTUPC-05", "BR-COACTUPC-09", "BR-COACTUPC-11",
        "BR-COACTUPC-13", "BR-COACTUPC-14", "BR-COACTUPC-15", "BR-COACTUPC-21", "VR-COACTUPC-26", "VR-COACTUPC-33",
        "Q-COACTUPC-01", "Q-COACTUPC-18", "Q-COACTUPC-28", "C-AUDIT"})
    void a_valid_change_is_confirmed_then_saved_by_column() {
        JsonNode s = fetched24();
        Map<String, Object> acctBefore = account(24);
        Map<String, Object> custBefore = customer(24);
        JsonNode n = change(s, f -> f.put("zip", "66044"));
        assertThat(msg(n)).isEmpty();
        assertThat(mode(n)).isEqualTo("N");
        assertThat(info(n)).isEqualTo(VALIDATED);
        assertThat(n.at("/fields/saveKey").asBoolean()).isTrue();
        assertThat(n.at("/fields/cancelKey").asBoolean()).isTrue();
        assertThat(list(n, "editable")).isEmpty();
        assertThat(values(n).get("zip")).isEqualTo("66044");

        Map<String, String> tampered = new LinkedHashMap<>(values(n));
        tampered.put("zip", "99999");
        tampered.put("firstName", "Mallory");
        JsonNode c = turn("PF5", tampered);
        assertThat(mode(c)).isEqualTo("C");
        assertThat(info(c)).isEqualTo(COMMITTED);
        assertThat(c.has("message")).isFalse();
        assertThat(list(c, "editable")).isEmpty();
        assertThat(c.at("/fields/cancelKey").asBoolean()).isFalse();

        Map<String, Object> cust = customer(24);
        assertThat(cust.get("zip")).as("the validated value, not the F5 input").isEqualTo("66044");
        assertThat(cust.get("first_name")).isEqualTo("Stefanie");
        for (String col : List.of("phone1", "phone2", "dob", "ssn", "govt_issued_id", "eft_account_id", "country_cd",
            "fico_score", "addr_line1", "addr_line2", "city", "state_cd")) {
            assertThat(cust.get(col)).as(col).isEqualTo(custBefore.get(col));
        }
        Map<String, Object> acct = account(24);
        for (String col : List.of("active_status", "current_balance", "credit_limit", "cash_credit_limit", "open_date",
            "expiration_date", "reissue_date", "curr_cycle_credit", "curr_cycle_debit", "addr_zip", "group_id")) {
            assertThat(acct.get(col)).as(col).isEqualTo(acctBefore.get(col));
        }
        Map<String, Object> audit = jdbc.queryForMap("SELECT TOP 1 * FROM audit_log ORDER BY audit_id DESC");
        assertThat(audit).containsEntry("actor", ADMIN).containsEntry("action", "ACCOUNT_UPDATE")
            .containsEntry("target", "00000000024").containsEntry("fields", "zip");
    }

    @Test
    @Covers({"TS-COACTUPC-10", "NAV-COACTUPC-04", "BR-COACTUPC-16", "Q-COACTUPC-19"})
    void after_a_save_the_next_key_shows_the_empty_search_screen() {
        JsonNode s = fetched24();
        change(s, f -> f.put("zip", "66044"));
        turn("PF5", Map.of());
        for (String key : new String[] {"ENTER", "PF12"}) {
            if (key.equals("PF12")) {
                change(fetch("00000000024"), f -> f.put("zip", "66045"));
                turn("PF5", Map.of());
            }
            JsonNode r = turn(key, Map.of("accountId", "00000000001"));
            assertThat(mode(r)).as(key).isEqualTo("SEARCH");
            assertThat(values(r).values()).allMatch(String::isEmpty);
            assertThat(info(r)).isEqualTo(PROMPT);
            assertThat(r.has("message")).isFalse();
        }
    }

    @Test
    @Covers({"TS-COACTUPC-11", "BR-COACTUPC-10", "NAV-COACTUPC-08"})
    void pf12_rereads_and_discards_the_changes() {
        JsonNode s = fetched24();
        byte[] version = (byte[]) customer(24).get("row_version");
        change(s, f -> f.put("zip", "66044"));
        JsonNode r = turn("PF12", Map.of());
        assertThat(mode(r)).isEqualTo("S");
        assertThat(values(r).get("zip")).isEqualTo("15414");
        assertThat(info(r)).isEqualTo(SHOWN);
        assertThat((byte[]) customer(24).get("row_version")).isEqualTo(version);
        change(s, f -> f.put("fico", "900"));
        assertThat(mode(turn("PF24", values(s)))).as("PF24 folds to PF12 in state E").isEqualTo("S");
    }

    @Test
    @Covers({"TS-COACTUPC-12", "NAV-COACTUPC-01"})
    void pf5_outside_state_n_and_other_keys_act_like_enter() {
        JsonNode s = fetched24();
        for (String key : new String[] {"PF5", "PF1", "CLEAR", "PA1"}) {
            Map<String, String> form = new LinkedHashMap<>(values(s));
            JsonNode r = turn(key, form);
            assertThat(msg(r)).as(key).isEqualTo(NO_CHANGE);
        }
        Map<String, String> form = new LinkedHashMap<>(values(s));
        form.put("zip", "66044");
        assertThat(mode(turn("PF5", form))).as("PF5 in S validates, never saves").isEqualTo("N");
        assertThat(customer(24).get("zip")).isEqualTo("15414");
        client.show("COACTUPC");
        assertThat(msg(turn("PF12", Map.of("accountId", "")))).as("PF12 before a fetch").isEqualTo("No input received");
    }

    @Test
    @Covers({"TS-COACTUPC-13", "NAV-COACTUPC-07"})
    void enter_in_state_n_redisplays() {
        JsonNode s = fetched24();
        JsonNode n = change(s, f -> f.put("zip", "66044"));
        JsonNode again = turn("ENTER", Map.of("zip", "12345"));
        assertThat(again.get("fields")).isEqualTo(n.get("fields"));
        assertThat(customer(24).get("zip")).isEqualTo("15414");
    }

    @Test
    @Covers({"TS-COACTUPC-14", "ERR-COACTUPC-11", "BR-COACTUPC-12", "Q-COACTUPC-04"})
    void a_conflicting_change_reloads_the_current_values() {
        JsonNode s = fetched24();
        change(s, f -> f.put("zip", "66044"));
        jdbc.update("UPDATE customer SET first_name = 'Stephanie' WHERE customer_id = 24");
        JsonNode r = turn("PF5", Map.of());
        assertThat(msg(r)).isEqualTo("Record changed by some one else. Please review");
        assertThat(info(r)).isEqualTo(SHOWN);
        assertThat(mode(r)).isEqualTo("S");
        assertThat(values(r).get("firstName")).isEqualTo("Stephanie");
        assertThat(values(r).get("zip")).isEqualTo("15414");
        assertThat(customer(24).get("zip")).isEqualTo("15414");

        change(r, f -> f.put("zip", "66044"));
        assertThat(info(turn("PF5", Map.of()))).as("retry against the new snapshot").isEqualTo(COMMITTED);
        assertThat(customer(24)).containsEntry("zip", "66044").containsEntry("first_name", "Stephanie");
    }

    @Test
    @Covers({"TS-COACTUPC-18", "Q-COACTUPC-07", "VR-COACTUPC-06", "VR-COACTUPC-09", "Q-COACTUPC-30"})
    void only_changed_fields_are_validated() {
        client.signOn(ADMIN);
        JsonNode s = fetch("00000000001");
        JsonNode n = change(s, f -> f.put("creditLimit", "3000.00"));
        assertThat(msg(n)).isEmpty();
        assertThat(info(n)).as("stored FICO 274 is not edited").isEqualTo(VALIDATED);
        assertThat(values(n).get("creditLimit")).isEqualTo("+      3,000.00");
        turn("PF5", Map.of());
        assertThat((BigDecimal) account(1).get("credit_limit")).isEqualByComparingTo("3000.00");
        assertThat(((Number) customer(1).get("fico_score")).intValue()).isEqualTo(274);

        jdbc.update("UPDATE account SET cash_credit_limit = 1234567890.12 WHERE account_id = 1");
        s = fetch("00000000001");
        assertThat(values(s).get("cashCreditLimit")).isEqualTo("+1,234,567,890.12");
        assertThat(msg(change(s, f -> { }))).as("a large amount survives an unchanged re-submit").isEqualTo(NO_CHANGE);
    }

    @Test
    @Covers({"TS-COACTUPC-19", "VR-COACTUPC-33", "Q-COACTUPC-08"})
    void state_and_zip_cross_edit() {
        client.signOn(ADMIN);
        JsonNode s = fetch("00000000010");
        assertThat(mode(change(s, f -> f.put("city", "Hartford")))).as("state and zip unchanged").isEqualTo("N");
        turn("PF12", Map.of());
        assertThat(mode(change(s, f -> f.put("zip", "06103")))).isEqualTo("N");
        turn("PF12", Map.of());
        JsonNode r = change(s, f -> f.put("zip", "60601"));
        assertEdit(r, "Invalid zip code for state", "state");
        assertThat(list(r, "errorFields")).containsExactly("state", "zip");

        s = fetch("00000000024");
        assertThat(mode(change(s, f -> { f.put("state", "PR"); f.put("zip", "00601"); }))).isEqualTo("N");
        turn("PF12", Map.of());
        assertThat(mode(change(s, f -> { f.put("state", "VI"); f.put("zip", "00802"); }))).isEqualTo("N");
        turn("PF12", Map.of());
        assertEdit(change(s, f -> f.put("state", "NY")), "Invalid zip code for state", "state");
    }

    @Test
    @Covers({"TS-COACTUPC-20", "VR-COACTUPC-25", "Q-COACTUPC-09"})
    void state_must_be_a_listed_code() {
        client.signOn(ADMIN);
        JsonNode s = fetch("00000000018");
        assertThat(mode(change(s, f -> f.put("city", "Hansen")))).as("stored AP is not edited").isEqualTo("N");
        turn("PF12", Map.of());
        assertEdit(change(s, f -> f.put("state", "AE")), "State: is not a valid state code", "state");
        assertEdit(change(s, f -> f.put("state", "ks")), "State: is not a valid state code", "state");
        assertEdit(change(s, f -> f.put("state", "K1")), "State can have alphabets only.", "state");
        JsonNode r = change(s, f -> f.put("state", ""));
        assertEdit(r, "State must be supplied.", "state");
        assertThat(values(r).get("state")).isEqualTo("*");
    }

    private JsonNode with66044(JsonNode s, Consumer<Map<String, String>> edits) {
        return change(s, f -> {
            f.put("zip", "66044");
            edits.accept(f);
        });
    }

    @Test
    @Covers({"TS-COACTUPC-21", "VR-COACTUPC-07", "Q-COACTUPC-25", "BR-COACTUPC-20"})
    void account_status_edit() {
        JsonNode s = fetched24();
        assertEdit(with66044(s, f -> f.put("active", "y")), "Account Status must be Y or N.", "active");
        JsonNode r = with66044(s, f -> f.put("active", ""));
        assertEdit(r, "Account Status must be supplied.", "active");
        assertThat(values(r).get("active")).isEqualTo("*");
        assertEdit(with66044(s, f -> f.put("active", "0")), "Account Status must be supplied.", "active");
        assertThat(mode(with66044(s, f -> f.put("active", "N")))).isEqualTo("N");
    }

    @Test
    @Covers({"TS-COACTUPC-22", "VR-COACTUPC-08", "VR-COACTUPC-10", "VR-COACTUPC-12", "Q-COACTUPC-13", "Q-COACTUPC-14"})
    void date_edits() {
        JsonNode s = fetched24();
        assertEdit(with66044(s, f -> f.put("openMonth", "13")), "Open Date: Month must be a number between 1 and 12.",
            "openMonth");
        JsonNode r = with66044(s, f -> { f.put("openMonth", "04"); f.put("openDay", "31"); });
        assertEdit(r, "Open Date:Cannot have 31 days in this month.", "openMonth");
        assertThat(list(r, "errorFields")).contains("openMonth", "openDay");
        r = with66044(s, f -> { f.put("openYear", "2023"); f.put("openMonth", "02"); f.put("openDay", "29"); });
        assertEdit(r, "Open Date:Not a leap year.Cannot have 29 days in this month.", "openYear");
        assertEdit(with66044(s, f -> { f.put("openYear", "1900"); f.put("openMonth", "02"); f.put("openDay", "29"); }),
            "Open Date:Not a leap year.Cannot have 29 days in this month.", "openYear");
        assertEdit(with66044(s, f -> { f.put("openMonth", "02"); f.put("openDay", "30"); }),
            "Open Date:Cannot have 30 days in this month.", "openMonth");
        assertEdit(with66044(s, f -> f.put("openYear", "1899")), "Open Date : Century is not valid.", "openYear");
        assertEdit(with66044(s, f -> f.put("openYear", "")), "Open Date : Year must be supplied.", "openYear");
        assertEdit(with66044(s, f -> f.put("openYear", "15")), "Open Date must be 4 digit number.", "openYear");
        assertEdit(with66044(s, f -> f.put("openMonth", "")), "Open Date : Month must be supplied.", "openMonth");
        assertEdit(with66044(s, f -> f.put("openDay", "")), "Open Date : Day must be supplied.", "openDay");
        assertEdit(with66044(s, f -> f.put("openDay", "32")), "Open Date:day must be a number between 1 and 31.", "openDay");
        assertEdit(with66044(s, f -> f.put("expDay", "AB")), "Expiry Date:day must be a number between 1 and 31.", "expDay");
        assertEdit(with66044(s, f -> f.put("risMonth", "00")), "Reissue Date: Month must be a number between 1 and 12.",
            "risMonth");
        JsonNode n = with66044(s, f -> { f.put("openYear", "2000"); f.put("openMonth", "02"); f.put("openDay", "29"); });
        assertThat(mode(n)).isEqualTo("N");
        turn("PF5", Map.of());
        assertThat(account(24).get("open_date").toString()).isEqualTo("2000-02-29");
    }

    @Test
    @Covers({"TS-COACTUPC-23", "VR-COACTUPC-09", "VR-COACTUPC-11", "VR-COACTUPC-13", "VR-COACTUPC-14", "VR-COACTUPC-15",
        "Q-COACTUPC-12", "Q-COACTUPC-15"})
    void amount_edits() {
        JsonNode s = fetched24();
        JsonNode r = with66044(s, f -> f.put("creditLimit", "ABC"));
        assertEdit(r, "Credit Limit is not valid", "creditLimit");
        assertThat(values(r).get("creditLimit")).as("invalid amounts are shown as typed").isEqualTo("ABC");
        assertEdit(with66044(s, f -> f.put("creditLimit", "")), "Credit Limit must be supplied.", "creditLimit");
        assertEdit(with66044(s, f -> f.put("cycleCredit", "X")), "Current Cycle Credit Limi is not valid", "cycleCredit");
        assertEdit(with66044(s, f -> f.put("cycleDebit", "")), "Current Cycle Debit Limit must be supplied.", "cycleDebit");
        assertEdit(with66044(s, f -> f.put("cashCreditLimit", "12345678901")), "Cash Credit Limit is not valid",
            "cashCreditLimit");
        assertEdit(with66044(s, f -> f.put("currentBalance", "1.234")), "Current Balance is not valid", "currentBalance");
        JsonNode n = with66044(s, f -> {
            f.put("creditLimit", "-500");
            f.put("currentBalance", "$1,000.50CR");
        });
        assertThat(mode(n)).isEqualTo("N");
        assertThat(values(n).get("creditLimit")).isEqualTo("-        500.00");
        turn("PF5", Map.of());
        assertThat((BigDecimal) account(24).get("credit_limit")).isEqualByComparingTo("-500");
        assertThat((BigDecimal) account(24).get("current_balance")).isEqualByComparingTo("-1000.50");
    }

    @Test
    @Covers({"TS-COACTUPC-24", "VR-COACTUPC-16", "VR-COACTUPC-17", "VR-COACTUPC-18", "Q-COACTUPC-28", "Q-ENT-24",
        "C-AUDIT"})
    void ssn_edits_and_masked_parts() {
        JsonNode s = fetched24();
        String last4 = values(s).get("ssn3");
        assertEdit(with66044(s, f -> { f.put("ssn1", "666"); f.put("ssn2", "12"); }),
            "SSN: First 3 chars: should not be 000, 666, or between 900 and 999", "ssn1");
        assertEdit(with66044(s, f -> { f.put("ssn1", "950"); f.put("ssn2", "12"); }),
            "SSN: First 3 chars: should not be 000, 666, or between 900 and 999", "ssn1");
        assertEdit(with66044(s, f -> { f.put("ssn1", "000"); f.put("ssn2", "12"); }), "SSN: First 3 chars must not be zero.",
            "ssn1");
        assertEdit(with66044(s, f -> { f.put("ssn1", "123"); f.put("ssn2", "0A"); }),
            "SSN 4th & 5th chars must be all numeric.", "ssn2");
        assertEdit(with66044(s, f -> { f.put("ssn1", "123"); f.put("ssn2", "00"); }),
            "SSN 4th & 5th chars must not be zero.", "ssn2");
        assertEdit(with66044(s, f -> { f.put("ssn1", "123"); f.put("ssn2", "45"); f.put("ssn3", ""); }),
            "SSN Last 4 chars must be supplied.", "ssn3");
        String other4 = last4.equals("1234") ? "4321" : "1234";
        assertEdit(with66044(s, f -> f.put("ssn3", other4)), "SSN: First 3 chars must be all numeric.", "ssn1");
        assertEdit(with66044(s, f -> { f.put("ssn1", "123"); f.put("ssn3", other4); }),
            "SSN 4th & 5th chars must be all numeric.", "ssn2");

        JsonNode n = change(s, f -> { f.put("ssn1", "123"); f.put("ssn2", "45"); f.put("ssn3", "6789"); });
        assertThat(mode(n)).isEqualTo("N");
        assertThat(values(n)).as("C-MASK on the echo").containsEntry("ssn1", "***").containsEntry("ssn2", "**")
            .containsEntry("ssn3", "6789");
        turn("PF5", Map.of());
        assertThat(((String) customer(24).get("ssn")).strip()).isEqualTo("123456789");
        Map<String, Object> audit = jdbc.queryForMap("SELECT TOP 1 * FROM audit_log ORDER BY audit_id DESC");
        assertThat(audit.get("fields")).isEqualTo("ssn");
        assertThat(audit.toString()).doesNotContain("123456789").doesNotContain(last4);
    }

    @Test
    @Covers({"TS-COACTUPC-25", "VR-COACTUPC-19", "Q-COACTUPC-27"})
    void date_of_birth_must_be_in_the_past() {
        JsonNode s = fetched24();
        LocalDate today = LocalDate.now();
        JsonNode r = with66044(s, f -> {
            f.put("dobYear", String.format("%04d", today.getYear()));
            f.put("dobMonth", String.format("%02d", today.getMonthValue()));
            f.put("dobDay", String.format("%02d", today.getDayOfMonth()));
        });
        assertEdit(r, "Date of Birth:cannot be in the future ", "dobYear");
        assertEdit(with66044(s, f -> f.put("dobYear", "2101")), "Date of Birth : Century is not valid.", "dobYear");
        assertThat(mode(with66044(s, f -> f.put("dobYear", "1995")))).isEqualTo("N");
    }

    @Test
    @Covers({"TS-COACTUPC-26", "VR-COACTUPC-20"})
    void fico_edits() {
        JsonNode s = fetched24();
        assertEdit(with66044(s, f -> f.put("fico", "900")), "FICO Score: should be between 300 and 850", "fico");
        assertEdit(with66044(s, f -> f.put("fico", "000")), "FICO Score must not be zero.", "fico");
        assertEdit(with66044(s, f -> f.put("fico", "7A1")), "FICO Score must be all numeric.", "fico");
        assertEdit(with66044(s, f -> f.put("fico", "")), "FICO Score must be supplied.", "fico");
        assertEdit(with66044(s, f -> f.put("fico", "71")), "FICO Score must be all numeric.", "fico");
    }

    @Test
    @Covers({"TS-COACTUPC-27", "VR-COACTUPC-21", "VR-COACTUPC-22", "VR-COACTUPC-23", "VR-COACTUPC-24", "VR-COACTUPC-27",
        "VR-COACTUPC-35", "Q-COACTUPC-26", "Q-COACTUPC-05"})
    void name_and_address_edits() {
        JsonNode s = fetched24();
        assertEdit(with66044(s, f -> f.put("firstName", "Anne-Marie")), "First Name can have alphabets only.", "firstName");
        assertEdit(with66044(s, f -> f.put("firstName", "")), "First Name must be supplied.", "firstName");
        assertEdit(with66044(s, f -> f.put("middleName", "J.")), "Middle Name can have alphabets only.", "middleName");
        assertEdit(with66044(s, f -> f.put("lastName", "O'Neil")), "Last Name can have alphabets only.", "lastName");
        assertEdit(with66044(s, f -> f.put("addr1", " ")), "Address Line 1 must be supplied.", "addr1");
        assertEdit(with66044(s, f -> f.put("city", "St. Louis")), "City can have alphabets only.", "city");
        assertEdit(with66044(s, f -> f.put("city", "")), "City must be supplied.", "city");
        JsonNode n = change(s, f -> {
            f.put("middleName", "");
            f.put("lastName", "dickinson");
            f.put("addr2", "#12 / Unit-3");
            f.put("group", "GROUP-B");
            f.put("govtId", "X-99");
        });
        assertThat(mode(n)).isEqualTo("N");
        turn("PF12", Map.of());
        assertThat(values(change(s, f -> f.put("govtId", "AB123456789")))).containsEntry("govtId", "*******6789");
        turn("PF12", Map.of());
        n = change(s, f -> {
            f.put("middleName", "");
            f.put("lastName", "dickinson");
            f.put("addr2", "#12 / Unit-3");
            f.put("group", "GROUP-B");
            f.put("govtId", "X-99");
        });
        assertThat(mode(n)).as("case-only change, empty middle name, unedited fields").isEqualTo("N");
        turn("PF5", Map.of());
        assertThat(values(n).get("govtId")).as("C-MASK on the echo").isEqualTo("X-99");
        assertThat(customer(24)).containsEntry("middle_name", "").containsEntry("last_name", "dickinson")
            .containsEntry("addr_line2", "#12 / Unit-3").containsEntry("govt_issued_id", "X-99");
        assertThat(account(24).get("group_id")).isEqualTo("GROUP-B");
    }

    @Test
    @Covers({"TS-COACTUPC-28", "VR-COACTUPC-29", "VR-COACTUPC-30", "Q-COACTUPC-10", "Q-COACTUPC-11"})
    void phone_edits() {
        JsonNode s = fetched24();
        assertEdit(with66044(s, f -> f.put("phone1a", "911")),
            "Phone Number 1: Not valid North America general purpose area code", "phone1a");
        assertEdit(with66044(s, f -> f.put("phone1a", "")), "Phone Number 1: Area code must be supplied.", "phone1a");
        assertEdit(with66044(s, f -> f.put("phone1a", "6A7")), "Phone Number 1: Area code must be A 3 digit number.",
            "phone1a");
        assertEdit(with66044(s, f -> f.put("phone1a", "000")), "Phone Number 1: Area code cannot be zero", "phone1a");
        assertEdit(with66044(s, f -> f.put("phone2b", "")), "Phone Number 2: Prefix code must be supplied.", "phone2b");
        assertEdit(with66044(s, f -> f.put("phone2b", "000")), "Phone Number 2: Prefix code cannot be zero", "phone2b");
        assertEdit(with66044(s, f -> f.put("phone2c", "12")), "Phone Number 2: Line number code must be A 4 digit number.",
            "phone2c");
        assertEdit(with66044(s, f -> f.put("phone2c", "0000")), "Phone Number 2: Line number code cannot be zero",
            "phone2c");
        JsonNode n = change(s, f -> {
            f.put("phone1a", "212");
            f.put("phone1b", "555");
            f.put("phone1c", "0100");
            f.put("phone2a", "");
            f.put("phone2b", "");
            f.put("phone2c", "");
        });
        assertThat(mode(n)).isEqualTo("N");
        turn("PF5", Map.of());
        assertThat(customer(24)).containsEntry("phone1", "(212)555-0100").containsEntry("phone2", "");
        JsonNode again = fetch("00000000024");
        assertThat(values(again)).containsEntry("phone2a", "").containsEntry("phone2b", "").containsEntry("phone2c", "");
    }

    @Test
    @Covers({"TS-COACTUPC-29", "VR-COACTUPC-31", "VR-COACTUPC-32", "Q-COACTUPC-25"})
    void eft_and_primary_holder_edits() {
        JsonNode s = fetched24();
        assertEdit(with66044(s, f -> f.put("eft", "0000000000")), "EFT Account Id must not be zero.", "eft");
        assertEdit(with66044(s, f -> f.put("eft", "12345")), "EFT Account Id must be all numeric.", "eft");
        assertEdit(with66044(s, f -> f.put("eft", "")), "EFT Account Id must be supplied.", "eft");
        assertEdit(with66044(s, f -> f.put("primary", "X")), "Primary Card Holder must be Y or N.", "primary");
        assertEdit(with66044(s, f -> f.put("primary", "n")), "Primary Card Holder must be Y or N.", "primary");
        assertEdit(with66044(s, f -> f.put("primary", "")), "Primary Card Holder must be supplied.", "primary");
    }

    @Test
    @Covers({"TS-COACTUPC-30", "VR-COACTUPC-34", "BR-COACTUPC-19", "BR-COACTUPC-20"})
    void first_message_wins_and_every_failing_field_is_flagged() {
        JsonNode s = fetched24();
        JsonNode r = with66044(s, f -> {
            f.put("active", "X");
            f.put("fico", "900");
            f.put("eft", "");
        });
        assertEdit(r, "Account Status must be Y or N.", "active");
        assertThat(list(r, "errorFields")).containsExactly("active", "fico", "eft");
        assertThat(values(r).get("eft")).isEqualTo("*");
        assertThat(values(r).get("fico")).isEqualTo("900");
        r = with66044(s, f -> {
            f.put("eft", "");
            f.put("fico", "900");
        });
        assertEdit(r, "FICO Score: should be between 300 and 850", "fico");
    }

    @Test
    @Covers({"TS-COACTUPC-31", "NAV-COACTUPC-02", "NAV-COACTUPC-06", "Q-COACTUPC-21"})
    void pf3_exits_from_any_state_and_discards_changes() {
        client.signOn(ADMIN);
        client.show("COACTUPC");
        assertThat(turn("PF3", Map.of()).at("/navigate").asText()).isEqualTo("COMEN01C");
        JsonNode s = fetch("00000000024");
        change(s, f -> f.put("zip", "66044"));
        assertThat(turn("PF15", Map.of()).at("/navigate").asText()).isEqualTo("COMEN01C");
        assertThat(customer(24).get("zip")).isEqualTo("15414");
        assertThat(mode(turn("ENTER", Map.of("accountId", "")))).as("a new conversation after exit").isEqualTo("SEARCH");
    }

    @Test
    @Covers({"Q-COACTUPC-28", "C-SCOPE", "D-USER-ACCT"})
    void a_user_updates_only_linked_accounts() {
        String user = ownerOf(24);
        long outside = 1;
        assertThat(linkedAccounts(user)).contains(24L).doesNotContain(outside);
        client.signOn(user);
        JsonNode r = fetch("00000000001");
        assertThat(msg(r)).isEqualTo("Account:00000000001 not found in Cross ref file.  Resp:000000013  Reas:0000");
        JsonNode s = turn("ENTER", Map.of("accountId", "00000000024"));
        change(s, f -> f.put("zip", "66044"));
        assertThat(info(turn("PF5", Map.of()))).isEqualTo(COMMITTED);
        assertThat(jdbc.queryForObject("SELECT TOP 1 actor FROM audit_log ORDER BY audit_id DESC", String.class)).isEqualTo(user);
    }

    @Test
    @Covers({"C-ROLE"})
    void without_a_session_the_update_is_refused() {
        assertThat(client.showRaw("COACTUPC").getStatus()).isEqualTo(401);
        assertThat(client.turn("COACTUPC", "ENTER", Map.of("fields", Map.of("accountId", "00000000024"))).getStatus())
            .isEqualTo(401);
    }
}
