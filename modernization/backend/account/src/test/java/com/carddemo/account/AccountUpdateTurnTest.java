package com.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.carddemo.account.internal.AccountCustomerStore;
import com.carddemo.test.Covers;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** COACTUPC conversation state and turn structure, protected and blank fields, column saves, and abends of both account screens. */
class AccountUpdateTurnTest extends AccountScreenTestBase {

    @MockitoSpyBean
    AccountCustomerStore store;

    @AfterEach
    void unstub() {
        reset(store);
    }

    private JsonNode turn(String key, Map<String, String> fields) {
        return client.post("COACTUPC", key, Map.of("fields", fields));
    }

    private JsonNode fetched24() {
        client.signOn(ADMIN);
        client.show("COACTUPC");
        return turn("ENTER", Map.of("accountId", "00000000024"));
    }

    private JsonNode change(JsonNode shown, Consumer<Map<String, String>> edits) {
        Map<String, String> form = new LinkedHashMap<>(values(shown));
        edits.accept(form);
        return turn("ENTER", form);
    }

    private static String mode(JsonNode r) {
        return r.at("/fields/mode").asText();
    }

    @Test
    @Covers({"BR-COACTUPC-01", "NAV-COACTUPC-05", "C-SESSION"})
    void the_change_action_moves_through_its_states_one_turn_at_a_time() {
        JsonNode s = fetched24();
        assertThat(mode(s)).isEqualTo("S");
        JsonNode e = change(s, f -> f.put("zip", "ABCDE"));
        assertThat(mode(e)).isEqualTo("E");
        assertThat(e.at("/message").asText()).isNotEmpty();
        JsonNode n = change(s, f -> f.put("zip", "66044"));
        assertThat(mode(n)).isEqualTo("N");
        assertThat(mode(turn("ENTER", Map.of()))).as("ENTER in N asks for F5 again").isEqualTo("N");
        JsonNode c = turn("PF5", Map.of());
        assertThat(mode(c)).isEqualTo("C");
        JsonNode next = turn("ENTER", Map.of("accountId", "00000000024"));
        assertThat(mode(next)).as("the input of the result screen is ignored").isEqualTo("SEARCH");
        assertThat(mode(turn("ENTER", Map.of("accountId", "00000000024")))).isEqualTo("S");
    }

    @Test
    @Covers({"ERR-COACTUPC-14"})
    void an_update_turn_with_no_fields_is_handled_as_blank_input() {
        client.signOn(ADMIN);
        client.show("COACTUPC");
        JsonNode r = client.post("COACTUPC", "ENTER", Map.of());
        assertThat(r.at("/message").asText()).isEqualTo("No input received");
        assertThat(mode(r)).isEqualTo("SEARCH");
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
    }

    @Test
    @Covers({"ERR-COACTVWC-09"})
    void a_view_turn_with_no_fields_is_handled_as_blank_input() {
        client.signOn(ADMIN);
        client.show("COACTVWC");
        JsonNode r = client.post("COACTVWC", "ENTER", Map.of());
        assertThat(r.at("/message").asText()).isEqualTo("No input received");
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
    }

    @Test
    @Covers({"ERR-COACTVWC-07"})
    void a_view_turn_has_no_context_state_so_any_turn_is_an_ordinary_lookup() {
        client.signOn(ADMIN);
        JsonNode first = client.post("COACTVWC", "ENTER", Map.of("accountId", "00000000024"));
        assertThat(first.at("/message").asText()).as("no UNEXPECTED DATA SCENARIO without a first entry").isEmpty();
        assertThat(first.at("/fields/info").asText()).isEqualTo("Displaying details of given Account");
        JsonNode again = client.post("COACTVWC", "ENTER", Map.of("accountId", "00000000024"));
        assertThat(again.at("/fields/accountId").asText()).isEqualTo("00000000024");
        assertThat(again.at("/fields/info").asText()).isEqualTo("Displaying details of given Account");
    }

    @Test
    @Covers({"VR-COACTUPC-28"})
    void the_protected_country_is_never_edited_or_saved_from_input() {
        JsonNode s = fetched24();
        assertThat(list(s, "editable")).doesNotContain("country");
        JsonNode n = change(s, f -> {
            f.put("zip", "66044");
            f.put("country", "");
        });
        assertThat(mode(n)).as("a blank country is not edited").isEqualTo("N");
        assertThat(values(n).get("country")).isEqualTo("USA");
        n = change(s, f -> {
            f.put("zip", "66044");
            f.put("country", "U2A");
        });
        assertThat(mode(n)).isEqualTo("N");
        assertThat(n.has("message")).isFalse();
        turn("PF5", Map.of());
        assertThat(jdbc.queryForObject("SELECT country_cd FROM customer WHERE customer_id = 24", String.class)).isEqualTo("USA");
    }

    @Test
    @Covers({"Q-ENT-01", "Q-COACTUPC-01"})
    void a_group_id_change_is_saved_to_the_group_column_and_the_zip_is_kept() {
        String zipBefore = jdbc.queryForObject("SELECT addr_zip FROM account WHERE account_id = 24", String.class);
        JsonNode s = fetched24();
        JsonNode n = change(s, f -> f.put("group", "GOLD"));
        assertThat(mode(n)).isEqualTo("N");
        assertThat(mode(turn("PF5", Map.of()))).isEqualTo("C");
        assertThat(jdbc.queryForObject("SELECT group_id FROM account WHERE account_id = 24", String.class).strip())
            .isEqualTo("GOLD");
        assertThat(jdbc.queryForObject("SELECT addr_zip FROM account WHERE account_id = 24", String.class)).isEqualTo(zipBefore);
    }

    @Test
    @Covers({"Q-ENT-25", "Q-COACTUPC-10"})
    void every_phone_part_is_tested_for_blank_and_an_all_blank_phone_is_stored_empty() {
        JsonNode s = fetched24();
        JsonNode e = change(s, f -> f.put("phone2c", "    "));
        assertThat(mode(e)).isEqualTo("E");
        assertThat(e.at("/message").asText()).isEqualTo("Phone Number 2: Line number code must be supplied.");
        assertThat(e.at("/focus").asText()).isEqualTo("phone2c");
        JsonNode n = change(s, f -> {
            f.put("phone2a", "   ");
            f.put("phone2b", "");
            f.put("phone2c", "    ");
        });
        assertThat(mode(n)).isEqualTo("N");
        turn("PF5", Map.of());
        assertThat(jdbc.queryForObject("SELECT phone2 FROM customer WHERE customer_id = 24", String.class)).isEmpty();
    }

    @Test
    @Covers({"ERR-COACTUPC-13", "ERR-COACTVWC-08"})
    void an_unexpected_error_fails_the_turn_instead_of_showing_a_screen() {
        client.signOn(ADMIN);
        doThrow(new IllegalStateException("unexpected")).when(store).account(anyLong());
        client.show("COACTVWC");
        assertThatThrownBy(() -> client.post("COACTVWC", "ENTER", Map.of("accountId", "00000000024")))
            .hasRootCauseInstanceOf(IllegalStateException.class);
        client.show("COACTUPC");
        assertThatThrownBy(() -> turn("ENTER", Map.of("accountId", "00000000024")))
            .hasRootCauseInstanceOf(IllegalStateException.class);
    }
}
