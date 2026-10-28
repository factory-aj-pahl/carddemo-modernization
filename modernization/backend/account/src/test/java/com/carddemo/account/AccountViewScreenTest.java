package com.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.test.Covers;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** COACTVWC screen turns. */
class AccountViewScreenTest extends AccountScreenTestBase {

    static final String[] DETAILS = {"active", "opened", "expiry", "reissue", "creditLimit", "cashCreditLimit",
        "currentBalance", "cycleCredit", "cycleDebit", "group", "customerId", "ssn", "dob", "fico", "firstName",
        "middleName", "lastName", "addr1", "addr2", "city", "state", "zip", "country", "phone1", "phone2", "govtId", "eft",
        "primary"};
    static final String[] CUSTOMER = {"customerId", "ssn", "dob", "fico", "firstName", "middleName", "lastName", "addr1",
        "addr2", "city", "state", "zip", "country", "phone1", "phone2", "govtId", "eft", "primary"};

    private JsonNode view(String account) {
        return client.post("COACTVWC", "ENTER", Map.of("accountId", account));
    }

    private static String f(JsonNode r, String name) {
        return r.at("/fields/" + name).asText();
    }

    private static void assertNoDetails(JsonNode r, String... names) {
        for (String n : names.length == 0 ? DETAILS : names) {
            assertThat(f(r, n)).as(n).isEmpty();
        }
    }

    @Test
    @Covers({"TS-COACTVWC-01", "NAV-COACTVWC-03", "NAV-COACTVWC-06", "BR-COACTVWC-06", "Q-COACTVWC-03", "Q-COACTVWC-04"})
    void menu_entry_shows_the_empty_search_screen() {
        client.signOn(ADMIN);
        JsonNode r = client.show("COACTVWC");
        assertThat(f(r, "accountId")).isEmpty();
        assertNoDetails(r);
        assertThat(f(r, "info")).isEqualTo("Enter or update id of account to display");
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
        assertThat(r.at("/fields/accountInError").asBoolean()).isFalse();
    }

    @Test
    @Covers({"TS-COACTVWC-02", "BR-COACTVWC-01", "BR-COACTVWC-02", "BR-COACTVWC-03", "BR-COACTVWC-05", "BR-COACTVWC-07",
        "BR-COACTVWC-09", "NAV-COACTVWC-04", "NAV-COACTVWC-05", "Q-COACTVWC-04", "Q-COACTVWC-09", "Q-COACTVWC-10",
        "C-MASK"})
    void enter_displays_the_account_and_its_customer_masked() {
        client.signOn(ADMIN);
        client.show("COACTVWC");
        JsonNode r = view("00000000001");
        assertThat(r.has("message")).isFalse();
        assertThat(f(r, "accountId")).isEqualTo("00000000001");
        assertThat(f(r, "active")).isEqualTo("Y");
        assertThat(f(r, "opened")).isEqualTo("2014-11-20");
        assertThat(f(r, "expiry")).isEqualTo("2025-05-20");
        assertThat(f(r, "reissue")).isEqualTo("2025-05-20");
        assertThat(f(r, "creditLimit")).isEqualTo("+      2,020.00");
        assertThat(f(r, "cashCreditLimit")).isEqualTo("+      1,020.00");
        assertThat(f(r, "currentBalance")).isEqualTo("+        194.00");
        // COBOL edits zero with +ZZZ,ZZZ,ZZZ.99 as blanks up to the decimal point.
        assertThat(f(r, "cycleCredit")).isEqualTo("+           .00");
        assertThat(f(r, "cycleDebit")).isEqualTo("+           .00");
        assertThat(f(r, "group")).isEqualTo("A000000000");
        assertThat(f(r, "customerId")).isEqualTo("000000001");
        String ssn = jdbc.queryForObject("SELECT ssn FROM customer WHERE customer_id = 1", String.class);
        assertThat(f(r, "ssn")).isEqualTo("***-**-" + ssn.substring(5));
        assertThat(f(r, "dob")).isEqualTo("1961-06-08");
        assertThat(f(r, "fico")).isEqualTo("274");
        assertThat(f(r, "firstName")).isEqualTo("Immanuel");
        assertThat(f(r, "middleName")).isEqualTo("Madeline");
        assertThat(f(r, "lastName")).isEqualTo("Kessler");
        assertThat(f(r, "addr1")).isEqualTo("618 Deshaun Route");
        assertThat(f(r, "addr2")).isEqualTo("Apt. 802");
        assertThat(f(r, "city")).isEqualTo("Altenwerthshire");
        assertThat(f(r, "state")).isEqualTo("NC");
        assertThat(f(r, "zip")).isEqualTo("12546");
        assertThat(f(r, "country")).isEqualTo("USA");
        assertThat(f(r, "phone1")).isEqualTo("(908)119-8310");
        assertThat(f(r, "phone2")).isEqualTo("(373)693-8684");
        assertThat(f(r, "govtId")).isEqualTo("****************8437");
        assertThat(f(r, "eft")).isEqualTo("0053581756");
        assertThat(f(r, "primary")).isEqualTo("Y");
        assertThat(f(r, "info")).isEqualTo("Displaying details of given Account");
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
        assertThat(r.toString()).doesNotContain(ssn).doesNotContain("00000000000049368437");
    }

    @Test
    @Covers({"TS-COACTVWC-03", "TS-COACTVWC-04", "VR-COACTVWC-01", "VR-COACTVWC-03", "BR-COACTVWC-07", "Q-COACTVWC-02"})
    void blank_account_number() {
        client.signOn(ADMIN);
        client.show("COACTVWC");
        for (String in : new String[] {"", "   ", "*"}) {
            JsonNode r = view(in);
            assertThat(r.at("/message").asText()).as(in).isEqualTo("No input received");
            assertThat(f(r, "accountId")).isEqualTo("*");
            assertThat(r.at("/fields/accountInError").asBoolean()).isTrue();
            assertThat(r.at("/focus").asText()).isEqualTo("accountId");
            assertThat(f(r, "info")).isEqualTo("Enter or update id of account to display");
            assertNoDetails(r);
        }
    }

    @Test
    @Covers({"TS-COACTVWC-05", "TS-COACTVWC-06", "VR-COACTVWC-02", "Q-COACTVWC-06", "C-KEYLEN"})
    void account_number_must_be_eleven_digits_and_not_zero() {
        client.signOn(ADMIN);
        for (String in : new String[] {"00000000000", "1", "0000000001", "0000000000A"}) {
            JsonNode r = view(in);
            assertThat(r.at("/message").asText()).as(in).isEqualTo("Account Filter must  be a non-zero 11 digit number");
            assertThat(f(r, "accountId")).isEqualTo(in);
            assertThat(r.at("/fields/accountInError").asBoolean()).isTrue();
            assertNoDetails(r);
        }
    }

    @Test
    @Covers({"TS-COACTVWC-07", "ERR-COACTVWC-01", "Q-COACTVWC-05"})
    void account_not_in_the_cross_reference() {
        client.signOn(ADMIN);
        JsonNode r = view("00000000099");
        assertThat(r.at("/message").asText())
            .isEqualTo("Account:00000000099 not found in Cross ref file.  Resp:000000013  Reas:0000");
        assertThat(r.at("/fields/accountInError").asBoolean()).isTrue();
        assertThat(f(r, "info")).isEqualTo("Enter or update id of account to display");
        assertNoDetails(r);
    }

    @Test
    @Covers({"TS-COACTVWC-08", "ERR-COACTVWC-03", "Q-COACTVWC-01", "Q-COACTVWC-05"})
    void account_missing_from_the_master_stops_the_display() {
        jdbc.update("INSERT INTO card_xref (card_num, customer_id, account_id) VALUES ('7777777777777777', 1, 77)");
        client.signOn(ADMIN);
        JsonNode r = view("00000000077");
        assertThat(r.at("/message").asText())
            .isEqualTo("Account:00000000077 not found in Acct Master file.Resp:000000013  Reas:0000");
        assertThat(r.at("/fields/accountInError").asBoolean()).isTrue();
        assertNoDetails(r);
    }

    @Test
    @Covers({"TS-COACTVWC-09", "ERR-COACTVWC-05", "Q-COACTVWC-05"})
    void customer_missing_shows_the_account_only() {
        jdbc.update("UPDATE card_xref SET customer_id = 999999 WHERE account_id = 1");
        client.signOn(ADMIN);
        JsonNode r = view("00000000001");
        assertThat(r.at("/message").asText())
            .isEqualTo("CustId:000999999 not found in customer master.Resp: 000000013  REAS:0000000");
        assertThat(r.at("/fields/accountInError").asBoolean()).isFalse();
        assertThat(f(r, "creditLimit")).isEqualTo("+      2,020.00");
        assertThat(f(r, "active")).isEqualTo("Y");
        assertNoDetails(r, CUSTOMER);
    }

    @Test
    @Covers({"TS-COACTVWC-11", "NAV-COACTVWC-02", "Q-COACTVWC-03"})
    void pf3_exits_to_the_menu_from_any_state() {
        client.signOn(ADMIN);
        client.show("COACTVWC");
        assertThat(client.post("COACTVWC", "PF3", Map.of()).at("/navigate").asText()).isEqualTo("COMEN01C");
        view("00000000001");
        assertThat(client.post("COACTVWC", "PF3", Map.of("accountId", "00000000001")).at("/navigate").asText())
            .isEqualTo("COMEN01C");
        assertThat(client.post("COACTVWC", "PF15", Map.of("accountId", "00000000001")).at("/navigate").asText())
            .as("PF15 folds to PF3").isEqualTo("COMEN01C");
    }

    @Test
    @Covers({"TS-COACTVWC-12", "NAV-COACTVWC-01"})
    void other_keys_act_like_enter() {
        client.signOn(ADMIN);
        JsonNode first = view("00000000001");
        for (String k : new String[] {"PF5", "PF17", "CLEAR", "PA1", "PA2", "PF12"}) {
            JsonNode r = client.post("COACTVWC", k, Map.of("accountId", "00000000001"));
            assertThat(r.has("navigate")).as(k).isFalse();
            assertThat(r.get("fields")).as(k).isEqualTo(first.get("fields"));
        }
    }

    @Test
    @Covers({"TS-COACTVWC-13", "BR-COACTVWC-05"})
    void a_new_account_number_shows_that_account() {
        client.signOn(ADMIN);
        view("00000000001");
        JsonNode r = view("00000000002");
        assertThat(f(r, "currentBalance")).isEqualTo("+        158.00");
        assertThat(f(r, "creditLimit")).isEqualTo("+      6,130.00");
        assertThat(f(r, "cashCreditLimit")).isEqualTo("+      5,448.00");
        assertThat(f(r, "opened")).isEqualTo("2013-06-19");
    }

    @Test
    @Covers({"Q-COACTVWC-08"})
    void amounts_show_all_ten_integer_digits() {
        jdbc.update("UPDATE account SET credit_limit = 1234567890.12, current_balance = -2.5 WHERE account_id = 1");
        client.signOn(ADMIN);
        JsonNode r = view("00000000001");
        assertThat(f(r, "creditLimit")).isEqualTo("+1,234,567,890.12");
        assertThat(f(r, "currentBalance")).isEqualTo("-          2.50");
    }

    @Test
    @Covers({"BR-COACTVWC-04"})
    void viewing_never_writes() {
        byte[] before = jdbc.queryForObject("SELECT row_version FROM account WHERE account_id = 1", byte[].class);
        byte[] cust = jdbc.queryForObject("SELECT row_version FROM customer WHERE customer_id = 1", byte[].class);
        client.signOn(ADMIN);
        view("00000000001");
        assertThat(jdbc.queryForObject("SELECT row_version FROM account WHERE account_id = 1", byte[].class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT row_version FROM customer WHERE customer_id = 1", byte[].class)).isEqualTo(cust);
    }

    @Test
    @Covers({"Q-COACTVWC-09", "C-SCOPE", "D-USER-ACCT"})
    void a_user_views_only_linked_accounts() {
        String user = ownerOf(1);
        long outside = 2;
        assertThat(linkedAccounts(user)).contains(1L).doesNotContain(outside);
        client.signOn(user);
        assertThat(view("00000000001").has("message")).isFalse();
        JsonNode r = view("00000000002");
        assertThat(r.at("/message").asText())
            .isEqualTo("Account:00000000002 not found in Cross ref file.  Resp:000000013  Reas:0000");
        assertNoDetails(r);
    }

    @Test
    @Covers({"C-ROLE"})
    void without_a_session_the_view_is_refused() {
        assertThat(client.showRaw("COACTVWC").getStatus()).isEqualTo(401);
        assertThat(client.turn("COACTVWC", "ENTER", Map.of("accountId", "00000000001")).getStatus()).isEqualTo(401);
    }
}
