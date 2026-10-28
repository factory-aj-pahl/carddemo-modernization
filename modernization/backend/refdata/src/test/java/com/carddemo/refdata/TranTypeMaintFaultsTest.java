package com.carddemo.refdata;

import static com.carddemo.refdata.TranTypeScreens.description;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.carddemo.refdata.internal.Db2Error;
import com.carddemo.refdata.internal.Db2Failure;
import com.carddemo.refdata.internal.TransactionTypeStore;
import com.carddemo.test.Covers;
import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** COTRTUPC database failures and abends, injected into the store (ERR-COTRTUPC-02 to -05, -08, -10, -12). */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class TranTypeMaintFaultsTest {

    static final String P = "COTRTUPC";
    static final Db2Error DOWN = new Db2Error(-904, "UNSUCCESSFUL EXECUTION CAUSED BY AN UNAVAILABLE RESOURCE");
    static final Db2Error BROKEN = new Db2Error(-901, "UNSUCCESSFUL EXECUTION CAUSED BY A SYSTEM ERROR");

    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoSpyBean
    TransactionTypeStore store;

    ScreenClient client;

    @BeforeEach
    void load() {
        sample.load();
        TranTypeScreens.loadDb2Seed(jdbc);
        client = new ScreenClient(mvc);
        client.signOn("ADMIN001");
    }

    @AfterEach
    void heal() {
        reset(store);
    }

    private JsonNode send(String key, String type, String desc) {
        Map<String, String> m = new HashMap<>();
        m.put("typeCode", type);
        m.put("description", desc);
        return client.post(P, key, m);
    }

    private JsonNode validated(String type, String desc) {
        client.show(P);
        send("ENTER", type, "");
        return send("ENTER", type, desc);
    }

    private static Db2Failure fail(Db2Error e) {
        return new Db2Failure(e, null);
    }

    @Test
    @Covers({"ERR-COTRTUPC-02", "ERR-COTRTUPC-12", "Q-COTRTUPC-09"})
    void a_lookup_error_shows_the_sql_message_and_offers_no_add() {
        doThrow(fail(DOWN)).when(store).find(anyString());
        client.show(P);
        JsonNode r = send("ENTER", "01", "");
        assertThat(r.at("/message").asText())
            .isEqualTo("Error accessing: TRANSACTION_TYPE table. SQLCODE: -904:UNSUCCESSFUL EXECUTI")
            .hasSize(75);
        assertThat(r.at("/fields/typeCode").asText()).isEqualTo("01");
        assertThat(r.at("/fields/typeInvalid").asBoolean()).isTrue();
        assertThat(r.at("/fields/typeEditable").asBoolean()).isTrue();
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("Enter transaction type to be maintained");
        assertThat(r.at("/fields/keys/save").asBoolean()).isFalse();
        assertThat(r.at("/fields/keys/cancel").asBoolean()).isFalse();
        assertThat(send("PF5", "01", "").at("/message").asText()).isEqualTo("Invalid key pressed");
        reset(store);
        r = send("ENTER", "01", "");
        assertThat(r.at("/fields/description").asText()).isEqualTo("PURCHASE");
    }

    @Test
    @Covers({"TS-COTRTUPC-20", "ERR-COTRTUPC-03"})
    void a_deadlock_on_save_reports_the_lock_failure() {
        validated("01", "PURCHASES");
        doThrow(fail(new Db2Error(-911, "Transaction was deadlocked"))).when(store).update(eq("01"), anyString(), anyLong());
        JsonNode r = send("PF5", "01", "PURCHASES");
        assertThat(r.at("/message").asText()).isEqualTo("Could not lock record for update");
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("Changes unsuccessful");
        assertThat(description(jdbc, "01")).isEqualTo("PURCHASE");
        // The next ENTER with nothing typed shows the empty prompt.
        r = send("ENTER", "", "");
        assertThat(r.at("/fields/typeCode").asText()).isEmpty();
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("Enter transaction type to be maintained");
        assertThat(r.has("message")).isFalse();
    }

    @Test
    @Covers({"TS-COTRTUPC-21", "ERR-COTRTUPC-04", "Q-COTRTUPC-02", "Q-COTRTUPC-03"})
    void an_update_failure_is_reported_as_unsuccessful() {
        validated("01", "PURCHASES");
        doThrow(fail(BROKEN)).when(store).update(eq("01"), anyString(), anyLong());
        JsonNode r = send("PF5", "01", "PURCHASES");
        assertThat(r.at("/message").asText())
            .isEqualTo("Update of record failed. Error updating: TRANSACTION_TYPE Table. SQLCODE: -901:UNSUCCESSFUL EXECUTIO");
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("Changes unsuccessful").isNotEqualTo("Changes committed to database");
        assertThat(r.at("/fields/typeEditable").asBoolean()).isTrue();
        assertThat(description(jdbc, "01")).isEqualTo("PURCHASE");
    }

    @Test
    @Covers({"ERR-COTRTUPC-05", "Q-COTRTUPC-04", "Q-COTRTUPC-02"})
    void an_insert_failure_shows_the_insert_sqlcode() {
        client.show(P);
        send("ENTER", "15", "");
        send("PF5", "15", "");
        send("ENTER", "15", "INTEREST");
        // Another user adds the same type first: the INSERT fails with a duplicate key.
        jdbc.update("INSERT INTO transaction_type (type_cd, description) VALUES ('15', 'OTHER')");
        JsonNode r = send("PF5", "15", "INTEREST");
        assertThat(r.at("/message").asText())
            .startsWith("Update of record failed. Error inserting record into: TRANSACTION_TYPE Table. SQLCODE: -803:");
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("Changes unsuccessful").isNotEqualTo("Changes committed to database");
        assertThat(r.at("/fields/typeEditable").asBoolean()).isTrue();
        assertThat(description(jdbc, "15")).isEqualTo("OTHER");
    }

    @Test
    @Covers({"ERR-COTRTUPC-08"})
    void a_delete_failure_shows_the_sql_message() {
        client.show(P);
        send("ENTER", "01", "");
        send("PF4", "01", "PURCHASE");
        doThrow(fail(BROKEN)).when(store).delete(eq("01"), isNull());
        JsonNode r = send("PF4", "01", "PURCHASE");
        assertThat(r.at("/message").asText()).isEqualTo("Delete failed with message:SQLCODE : -901:UNSUCCESSFUL EXECUTION CAUSED BY ");
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("Changes unsuccessful");
        assertThat(r.at("/fields/typeInvalid").asBoolean()).isTrue();
    }

    @Test
    @Covers({"ERR-COTRTUPC-10", "Q-COTRTUPC-08"})
    void an_unexpected_failure_ends_the_conversation_with_the_abend_data() {
        client.show(P);
        doThrow(new IllegalStateException("boom")).when(store).find(anyString());
        JsonNode r = send("ENTER", "01", "");
        assertThat(r.at("/fields/abend/code").asText()).isEqualTo("9999");
        assertThat(r.at("/fields/abend/culprit").asText()).isEqualTo("COTRTUPC");
        assertThat(r.at("/fields/abend/message").asText()).isEqualTo("UNEXPECTED ABEND OCCURRED.");
        assertThat(r.at("/fields/typeCode").asText()).isEmpty();
        reset(store);
        // No conversation survives the abend: the next turn starts with the empty prompt.
        r = send("ENTER", "01", "");
        assertThat(r.at("/fields/abend").isNull()).isTrue();
        assertThat(r.at("/fields/typeCode").asText()).isEmpty();
    }
}
