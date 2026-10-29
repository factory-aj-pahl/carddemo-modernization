package com.carddemo.billpayservice.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.test.Covers;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Bill payment over HTTP against the standalone service process that failsafe started
 * (spring-boot:start in pre-integration-test), signed on as USER0001 through the service's own sign-on.
 */
class StandaloneBillPaymentIT {

    static final String USER = "USER0001";
    static final String NOT_FOUND = "Account ID NOT found...";
    static final String NOTHING_TO_PAY = "You have nothing to pay...";

    static JdbcTemplate jdbc;

    @BeforeAll
    static void data() {
        jdbc = StandaloneDatabase.loaded();
    }

    static ServiceClient user() {
        ServiceClient c = new ServiceClient(ServiceClient.baseUrl());
        assertThat(c.signOn(USER).path("navigate").asText()).isEqualTo("COMEN01C");
        return c;
    }

    static BigDecimal balance(String account) {
        return jdbc.queryForObject("SELECT current_balance FROM account WHERE account_id = ?", BigDecimal.class,
            Long.parseLong(account));
    }

    static JsonNode pay(ServiceClient c, String account) {
        return c.turn("COBIL00C", Map.of("accountId", account, "confirm", "Y"));
    }

    @Test
    @Covers({"P-MODULES", "TS-COBIL00C-02", "BR-COBIL00C-08"})
    void pays_the_positive_balance_of_a_linked_account_and_records_the_payment() {
        String account = StandaloneDatabase.account(USER, true, true);
        BigDecimal before = balance(account);
        assertThat(before).isPositive();

        JsonNode r = pay(user(), account);

        String message = r.path("message").asText();
        assertThat(message).matches("Payment successful\\.  Your Transaction ID is \\d{16}\\.");
        String tranId = message.replaceAll("\\D", "");
        assertThat(balance(account)).isEqualByComparingTo(BigDecimal.ZERO);
        Map<String, Object> tran = jdbc.queryForMap(
            "SELECT type_cd, cat_cd, amount, description FROM card_transaction WHERE tran_id = ?", tranId);
        assertThat(tran.get("type_cd")).isEqualTo("02");
        assertThat((BigDecimal) tran.get("amount")).isEqualByComparingTo(before);
        assertThat(String.valueOf(tran.get("description")).strip()).isEqualTo("BILL PAYMENT - ONLINE");
    }

    @Test
    @Covers({"P-MODULES", "TS-COBIL00C-03", "BR-COBIL00C-02"})
    void rejects_an_account_with_a_zero_balance_and_writes_nothing() {
        String account = StandaloneDatabase.account(USER, true, true);
        jdbc.update("UPDATE account SET current_balance = 0 WHERE account_id = ?", Long.parseLong(account));
        int transactions = jdbc.queryForObject("SELECT COUNT(*) FROM card_transaction", Integer.class);

        JsonNode r = pay(user(), account);

        assertThat(r.path("message").asText()).isEqualTo(NOTHING_TO_PAY);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_transaction", Integer.class)).isEqualTo(transactions);
        assertThat(balance(account)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @Covers({"P-MODULES", "TS-COBIL00C-06", "VR-COBIL00C-03"})
    void answers_account_not_found_for_an_unknown_account() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE account_id = 99999999999", Integer.class)).isZero();

        JsonNode r = pay(user(), "99999999999");

        assertThat(r.path("message").asText()).isEqualTo(NOT_FOUND);
    }

    @Test
    @Covers({"P-MODULES", "C-SCOPE", "D-USER-ACCT", "A-2"})
    void answers_an_out_of_scope_account_like_an_unknown_one_and_leaves_it_unpaid() {
        String other = StandaloneDatabase.account(USER, false, true);
        BigDecimal before = balance(other);
        int transactions = jdbc.queryForObject("SELECT COUNT(*) FROM card_transaction", Integer.class);
        ServiceClient c = user();

        JsonNode unlinked = pay(c, other);
        JsonNode missing = pay(c, "99999999999");

        assertThat(unlinked.path("message").asText()).isEqualTo(NOT_FOUND);
        assertThat(unlinked.toString().replace(other, "<key>")).isEqualTo(missing.toString().replace("99999999999", "<key>"));
        assertThat(balance(other)).isEqualByComparingTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_transaction", Integer.class)).isEqualTo(transactions);
    }
}
