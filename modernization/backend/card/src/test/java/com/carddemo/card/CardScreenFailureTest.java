package com.carddemo.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.carddemo.card.internal.CardRepository;
import com.carddemo.test.Covers;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Data-access failures and unexpected errors of the card screens (the COBOL "other RESP" and abend branches). */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class CardScreenFailureTest extends CardScreenTestSupport {

    static final DataAccessResourceFailureException DOWN = new DataAccessResourceFailureException("CARDDAT unavailable");
    static final String FILE_ERROR = "File Error: READ     on CARDDAT   returned RESP 000000017 ,RESP2 000000000 ";

    @MockitoSpyBean
    CardRepository repo;

    static Map<String, Object> keys(String acct, String card) {
        Map<String, Object> f = new HashMap<>();
        f.put("accountId", acct);
        f.put("cardNum", card);
        return f;
    }

    @Test
    @Covers({"ERR-COCRDLIC-01", "ERR-COCRDLIC-02", "Q-COCRDLIC-19"})
    void a_failed_browse_shows_the_file_error_with_no_rows() {
        doThrow(DOWN).when(repo).forward(any(), any(), anyBoolean(), anyInt());
        JsonNode first = admin.show("COCRDLIC");
        assertThat(first.at("/message").asText()).isEqualTo(FILE_ERROR);
        assertThat(cards(first)).containsOnly("");
        JsonNode filtered = admin.post("COCRDLIC", "ENTER", Map.of("accountFilter", ACCT1, "cardFilter", ""));
        assertThat(filtered.at("/message").asText()).isEqualTo(FILE_ERROR);
        assertThat(filtered.at("/fields/accountFilter").asText()).isEqualTo(ACCT1);
        assertThat(cards(filtered)).containsOnly("");
    }

    @Test
    @Covers({"ERR-COCRDLIC-03"})
    void a_failed_backward_read_shows_the_file_error_with_no_rows() {
        admin.show("COCRDLIC");
        admin.post("COCRDLIC", "PF8", Map.of());
        doThrow(DOWN).when(repo).backward(any(), anyString(), anyInt());
        JsonNode r = admin.post("COCRDLIC", "PF7", Map.of());
        assertThat(r.at("/message").asText()).isEqualTo(FILE_ERROR);
        assertThat(cards(r)).containsOnly("");
    }

    @Test
    @Covers({"ERR-COCRDLIC-05", "ERR-COCRDSLC-02"})
    void an_unexpected_error_fails_the_turn_instead_of_showing_a_screen() {
        doThrow(new IllegalStateException("unexpected")).when(repo).forward(any(), any(), anyBoolean(), anyInt());
        assertThatThrownBy(() -> admin.show("COCRDLIC")).hasRootCauseInstanceOf(IllegalStateException.class);
        admin.show("COCRDSLC");
        doThrow(new IllegalStateException("unexpected")).when(repo).find(anyString());
        assertThatThrownBy(() -> admin.post("COCRDSLC", "ENTER", keys(ACCT1, CARD1)))
            .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @Covers({"ERR-COCRDSLC-01", "TS-COCRDSLC-14"})
    void a_failed_card_read_in_view_shows_the_file_error_with_the_account_red() {
        admin.show("COCRDSLC");
        doThrow(DOWN).when(repo).find(anyString());
        JsonNode r = admin.post("COCRDSLC", "ENTER", keys(ACCT1, CARD1));
        assertThat(r.at("/message").asText()).startsWith("File Error: READ     on CARDDAT   returned RESP ").isEqualTo(FILE_ERROR);
        assertThat(r.at("/fields/errors").toString()).isEqualTo("[\"accountId\"]");
        assertThat(r.at("/fields/name").asText()).isEmpty();
        assertThat(r.at("/fields/status").asText()).isEmpty();
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
    }

    @Test
    @Covers({"ERR-COCRDUPC-01"})
    void a_failed_fetch_in_update_shows_the_file_error_and_stays_unfetched() {
        admin.show("COCRDUPC");
        doThrow(DOWN).when(repo).find(anyString());
        JsonNode r = admin.post("COCRDUPC", "ENTER", keys(ACCT1, CARD1));
        assertThat(r.at("/message").asText()).isEqualTo(FILE_ERROR);
        assertThat(r.at("/fields/state").asText()).isEmpty();
        assertThat(r.at("/fields/errors").toString()).isEqualTo("[\"accountId\"]");
        assertThat(r.at("/fields/name").asText()).isEmpty();
        assertThat(r.at("/fields/keysEditable").asBoolean()).isTrue();
    }

    /** Fetches CARD1 from the menu path and changes its name to a valid value; state N. */
    JsonNode confirmed() {
        admin.show("COCRDUPC");
        JsonNode s = admin.post("COCRDUPC", "ENTER", keys(ACCT1, CARD1));
        Map<String, Object> f = keys(ACCT1, s.at("/fields/cardNum").asText());
        f.put("name", "BROKEN NAME");
        f.put("status", s.at("/fields/status").asText());
        f.put("expMonth", s.at("/fields/expMonth").asText());
        f.put("expYear", s.at("/fields/expYear").asText());
        JsonNode n = admin.post("COCRDUPC", "ENTER", f);
        assertThat(n.at("/fields/state").asText()).isEqualTo("N");
        return n;
    }

    @Test
    @Covers({"ERR-COCRDUPC-01", "Q-COCRDUPC-20"})
    void a_failed_pf12_reread_on_the_menu_path_shows_the_file_error_on_the_search_screen() {
        JsonNode n = confirmed();
        doThrow(DOWN).when(repo).find(anyString());
        JsonNode r = admin.post("COCRDUPC", "PF12", keys(ACCT1, n.at("/fields/cardNum").asText()));
        assertThat(r.at("/message").asText()).isEqualTo(FILE_ERROR);
        assertThat(r.at("/fields/errors").toString()).isEqualTo("[\"accountId\"]");
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
        assertThat(r.at("/fields/state").asText()).isEmpty();
        assertThat(r.at("/fields/accountId").asText()).isEqualTo(ACCT1);
        assertThat(r.at("/fields/cardNum").asText()).isEqualTo(n.at("/fields/cardNum").asText());
        assertThat(r.at("/fields/keysEditable").asBoolean()).isTrue();
        assertThat(r.at("/fields/detailsEditable").asBoolean()).isFalse();
        assertThat(r.at("/fields/name").asText()).isEmpty();
    }

    @Test
    @Covers({"ERR-COCRDUPC-03"})
    void a_failed_rewrite_shows_update_failed_and_writes_nothing() {
        Map<String, Object> before = cardRow(CARD1);
        JsonNode n = confirmed();
        doThrow(DOWN).when(repo).updateDetails(anyString(), anyString(), anyString(), any());
        JsonNode r = admin.post("COCRDUPC", "PF5", keys(ACCT1, n.at("/fields/cardNum").asText()));
        assertThat(r.at("/message").asText()).isEqualTo("Update of record failed");
        assertThat(r.at("/fields/state").asText()).isEqualTo("F");
        assertThat(r.at("/fields/info").asText()).isEqualTo("Changes unsuccessful. Please try again");
        assertThat(r.at("/fields/keysEditable").asBoolean()).isTrue();
        assertThat(r.at("/fields/detailsEditable").asBoolean()).isFalse();
        assertThat(cardRow(CARD1)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'CARD_UPDATE'", Integer.class)).isZero();
    }

    @Test
    @Covers({"ERR-COCRDUPC-05"})
    void an_unexpected_error_after_the_rewrite_backs_the_update_out() {
        Map<String, Object> before = cardRow(CARD1);
        JsonNode n = confirmed();
        doAnswer(inv -> {
            inv.callRealMethod();
            throw new IllegalStateException("unexpected");
        }).when(repo).updateDetails(anyString(), anyString(), anyString(), any());
        assertThatThrownBy(() -> admin.post("COCRDUPC", "PF5", keys(ACCT1, n.at("/fields/cardNum").asText())))
            .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(cardRow(CARD1)).isEqualTo(before);
    }
}
