package com.carddemo.refdata;

import static com.carddemo.refdata.TranTypeScreens.describe;
import static com.carddemo.refdata.TranTypeScreens.listInput;
import static com.carddemo.refdata.TranTypeScreens.row;
import static com.carddemo.refdata.TranTypeScreens.select;
import static com.carddemo.refdata.TranTypeScreens.types;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.carddemo.refdata.internal.CursorFailure;
import com.carddemo.refdata.internal.CursorFailure.Phase;
import com.carddemo.refdata.internal.Db2Error;
import com.carddemo.refdata.internal.Db2Failure;
import com.carddemo.refdata.internal.TransactionTypeStore;
import com.carddemo.refdata.internal.TypeRow;
import com.carddemo.test.Covers;
import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
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

/** COTRTLIC database failures, injected into the store (ERR-COTRTLIC-01, -04 to -14). */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class TranTypeListFaultsTest {

    static final String P = "COTRTLIC";
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

    private static TypeRow r(String code, String desc) {
        return new TypeRow(code, desc, 0);
    }

    private static List<TypeRow> seed(int n) {
        String[] d = {"PURCHASE", "PAYMENT", "CREDIT", "AUTHORIZATION", "REFUND", "REVERAL", "ADJUSTMENT"};
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> r(String.format("%02d", i + 1), d[i])).toList();
    }

    @Test
    @Covers({"TS-COTRTLIC-19", "ERR-COTRTLIC-01", "BR-COTRTLIC-22"})
    void database_unavailable_ends_the_conversation_with_the_long_message() {
        doThrow(new Db2Failure(DOWN, null)).when(store).ping();
        JsonNode r = client.show(P);
        assertThat(r.at("/navigate").asText()).isEqualTo("COADM01C");
        assertThat(r.at("/message").asText())
            .isEqualTo("Db2 access failure. SQLCODE: -904 UNSUCCESSFUL EXECUTION CAUSED BY AN UNAVAILABLE RESOURCE");
        assertThat(r.has("fields")).isFalse();
    }

    @Test
    @Covers({"ERR-COTRTLIC-01"})
    void database_failing_mid_conversation_ends_it_too() {
        JsonNode first = client.show(P);
        doThrow(new Db2Failure(DOWN, null)).when(store).ping();
        JsonNode r = client.post(P, "ENTER", listInput(first));
        assertThat(r.at("/navigate").asText()).isEqualTo("COADM01C");
        assertThat(r.at("/message").asText()).startsWith("Db2 access failure. SQLCODE: -904");
    }

    @Test
    @Covers({"ERR-COTRTLIC-04"})
    void forward_open_failure_shows_empty_rows() {
        JsonNode first = client.show(P);
        doThrow(new CursorFailure(Phase.OPEN, List.of(), BROKEN)).when(store).forward(anyString(), any());
        JsonNode r = client.post(P, "ENTER", listInput(first));
        assertThat(r.at("/message").asText()).startsWith("C-TR-TYPE-FORWARD Open SQLCODE: -901 UNSUCCESSFUL EXECUTION").hasSize(75);
        assertThat(types(r)).isEmpty();
    }

    @Test
    @Covers({"ERR-COTRTLIC-05", "Q-COTRTLIC-14"})
    void forward_fetch_failures_keep_the_rows_read_so_far() {
        JsonNode first = client.show(P);
        doThrow(new CursorFailure(Phase.FETCH, seed(3), BROKEN)).when(store).forward(anyString(), any());
        JsonNode r = client.post(P, "ENTER", listInput(first));
        assertThat(r.at("/message").asText()).startsWith("C-TR-TYPE-FORWARD close SQLCODE: -901 ");
        assertThat(types(r)).containsExactly("01", "02", "03");
        doThrow(new CursorFailure(Phase.LOOKAHEAD, seed(7), BROKEN)).when(store).forward(anyString(), any());
        r = client.post(P, "ENTER", listInput(first));
        assertThat(r.at("/message").asText()).startsWith("C-TR-TYPE-FORWARD fetch SQLCODE: -901 ");
        assertThat(types(r)).hasSize(7);
    }

    @Test
    @Covers({"ERR-COTRTLIC-06"})
    void cursor_close_failures_are_reported() {
        JsonNode first = client.show(P);
        doThrow(new CursorFailure(Phase.CLOSE, seed(7), BROKEN)).when(store).forward(anyString(), any());
        JsonNode r = client.post(P, "ENTER", listInput(first));
        assertThat(r.at("/message").asText()).startsWith("C-TR-TYPE-FORWARD close SQLCODE: -901 ");
        assertThat(types(r)).hasSize(7);
        reset(store);
        jdbc.update("INSERT INTO transaction_type (type_cd, description) VALUES ('08', 'TEST A')");
        JsonNode page2 = client.post(P, "PF8", listInput(client.show(P)));
        doThrow(new CursorFailure(Phase.CLOSE, seed(7).reversed(), BROKEN)).when(store).backward(anyString(), any());
        r = client.post(P, "PF7", listInput(page2));
        assertThat(r.at("/message").asText()).startsWith("C-TR-TYPE-BACKWARD close SQLCODE: -901 ");
        assertThat(types(r)).hasSize(7);
    }

    @Test
    @Covers({"ERR-COTRTLIC-07", "ERR-COTRTLIC-08"})
    void backward_failures_are_reported() {
        jdbc.update("INSERT INTO transaction_type (type_cd, description) VALUES ('08', 'TEST A')");
        JsonNode page2 = client.post(P, "PF8", listInput(client.show(P)));
        doThrow(new CursorFailure(Phase.OPEN, List.of(), BROKEN)).when(store).backward(anyString(), any());
        JsonNode r = client.post(P, "PF7", listInput(page2));
        assertThat(r.at("/message").asText()).startsWith("C-TR-TYPE-BACKWARD Open SQLCODE: -901 ");
        assertThat(types(r)).isEmpty();
        reset(store);
        page2 = client.post(P, "PF8", listInput(client.show(P)));
        doThrow(new CursorFailure(Phase.FETCH, List.of(r("07", "ADJUSTMENT"), r("06", "REVERAL")), BROKEN)).when(store)
            .backward(anyString(), any());
        r = client.post(P, "PF7", listInput(page2));
        assertThat(r.at("/message").asText()).startsWith("Error on fetch Cursor C-TR-TYPE-BACKWARD SQLCODE: -901 ");
        assertThat(row(r, 7).get("type").asText()).isEqualTo("07");
        assertThat(row(r, 6).get("type").asText()).isEqualTo("06");
        assertThat(row(r, 1).get("type").asText()).isEmpty();
    }

    @Test
    @Covers({"ERR-COTRTLIC-09"})
    void count_failure_ends_as_no_records_found_for_the_filter() {
        JsonNode first = client.show(P);
        doThrow(new Db2Failure(BROKEN, null)).when(store).count(any());
        Map<String, Object> in = listInput(first);
        in.put("typeFilter", "03");
        JsonNode r = client.post(P, "ENTER", in);
        // The count error sets the message, then the count of 0 (working storage) replaces it (VR-COTRTLIC-02).
        assertThat(r.at("/message").asText()).isEqualTo("No Records found for these filter conditions");
        assertThat(r.at("/fields/typeFilterInvalid").asBoolean()).isTrue();
        assertThat(types(r)).hasSize(7);
    }

    @Test
    @Covers({"ERR-COTRTLIC-11", "ERR-COTRTLIC-12"})
    void update_failures_keep_the_update_pending() {
        JsonNode r = client.post(P, "ENTER", select(listInput(client.show(P)), 5, "U"));
        doThrow(new Db2Failure(new Db2Error(-911, "DEADLOCK OR TIMEOUT"), null)).when(store).update(eq("05"), anyString(), anyLong());
        JsonNode d = client.post(P, "PF10", describe(listInput(r), 5, "REFUND CREDIT"));
        assertThat(d.at("/message").asText()).isEqualTo("Deadlock. Someone else updating ? SQLCODE: -911 DEADLOCK OR TIMEOUT");
        assertThat(row(d, 5).get("descriptionEditable").asBoolean()).isTrue();
        doThrow(new Db2Failure(new Db2Error(-913, "UNSUCCESSFUL EXECUTION"), null)).when(store).update(eq("05"), anyString(), anyLong());
        JsonNode f = client.post(P, "PF10", listInput(d));
        assertThat(f.at("/message").asText()).isEqualTo("Update failed with SQLCODE: -913 UNSUCCESSFUL EXECUTION");
        assertThat(row(f, 5).get("descriptionEditable").asBoolean()).isTrue();
        assertThat(TranTypeScreens.description(jdbc, "05")).isEqualTo("REFUND");
        reset(store);
        f = client.post(P, "PF10", listInput(f));
        assertThat(f.at("/fields/info").asText().strip()).isEqualTo("HIGHLIGHTED row was updated");
    }

    @Test
    @Covers({"ERR-COTRTLIC-14"})
    void other_delete_failures_are_reported() {
        jdbc.update("INSERT INTO transaction_type (type_cd, description) VALUES ('08', 'TEST A')");
        JsonNode r = client.post(P, "PF8", listInput(client.show(P)));
        r = client.post(P, "ENTER", select(listInput(r), 1, "D"));
        doThrow(new Db2Failure(new Db2Error(-911, "DEADLOCK OR TIMEOUT"), null)).when(store).delete(eq("08"), isNull());
        JsonNode d = client.post(P, "PF10", listInput(r));
        assertThat(d.at("/message").asText()).isEqualTo("Delete failed with message: SQLCODE: -911 DEADLOCK OR TIMEOUT");
        reset(store);
        jdbc.update("DELETE FROM transaction_type WHERE type_cd = '08'");
        d = client.post(P, "PF10", listInput(d));
        assertThat(d.at("/message").asText()).startsWith("Delete failed with message: SQLCODE:  100 ROW NOT FOUND");
    }
}
