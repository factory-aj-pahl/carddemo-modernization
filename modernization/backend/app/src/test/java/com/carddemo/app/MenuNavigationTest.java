package com.carddemo.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.test.Covers;
import com.carddemo.test.ScreenClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Control passing between the main menu and the function screens on the assembled API. */
class MenuNavigationTest extends SecurityTestBase {

    ScreenClient client;

    @BeforeEach
    void signOn() {
        clock.set(T0);
        sample.load();
        client = new ScreenClient(mvc);
        client.signOn("USER0001");
    }

    private void assertFreshMenu() {
        JsonNode menu = client.show("COMEN01C");
        assertThat(menu.has("message")).isFalse();
        assertThat(menu.at("/fields/option").asText()).isEmpty();
        assertThat(menu.at("/fields/options")).hasSize(11);
    }

    @Test
    @Covers({"TS-COMEN01C-07"})
    void option_11_passes_control_to_the_pending_authorization_list_at_first_entry() {
        assertThat(client.post("COMEN01C", "ENTER", Map.of("option", "11")).at("/navigate").asText()).isEqualTo("COPAUS0C");
        assertThat(client.showRaw("COPAUS0C").getStatus()).isEqualTo(200);
    }

    @Test
    @Covers({"TS-COMEN01C-12", "C-SESSION"})
    void returning_from_bill_payment_shows_the_menu_fresh() {
        assertThat(client.post("COMEN01C", "ENTER", Map.of("option", "10")).at("/navigate").asText()).isEqualTo("COBIL00C");
        client.show("COBIL00C");
        client.post("COBIL00C", "ENTER", Map.of("accountId", ""));
        assertThat(client.post("COBIL00C", "PF3", Map.of()).at("/navigate").asText()).isEqualTo("COMEN01C");
        assertFreshMenu();
    }

    @Test
    @Covers({"NAV-COTRN02C-08", "C-SESSION"})
    void leaving_transaction_add_returns_to_the_menu_at_first_entry_with_the_same_user() {
        assertThat(client.post("COMEN01C", "ENTER", Map.of("option", "8")).at("/navigate").asText()).isEqualTo("COTRN02C");
        client.show("COTRN02C");
        client.post("COTRN02C", "ENTER", Map.of());
        assertThat(client.post("COTRN02C", "PF3", Map.of()).at("/navigate").asText()).isEqualTo("COMEN01C");
        assertFreshMenu();
        assertThat(client.showRaw("COADM01C").getStatus()).as("still the USER session").isEqualTo(403);
    }
}
