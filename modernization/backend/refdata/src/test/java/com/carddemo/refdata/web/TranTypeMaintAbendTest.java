package com.carddemo.refdata.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.test.Covers;
import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** 2000-DECIDE-ACTION with a state it has no branch for (ERR-COTRTUPC-09). */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class TranTypeMaintAbendTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;

    @Test
    @Covers({"ERR-COTRTUPC-09", "Q-COTRTUPC-08"})
    void an_unexpected_state_abends_with_code_0001() {
        sample.load();
        ScreenClient client = new ScreenClient(mvc);
        client.signOn("ADMIN001");
        client.show("COTRTUPC");
        MaintState stuck = new MaintState();
        stuck.action = MaintState.Action.START_DELETE;
        stuck.oldType = "01";
        stuck.oldDesc = "PURCHASE";
        client.session().setAttribute(TranTypeNavigation.MAINT_STATE, stuck);
        JsonNode r = client.post("COTRTUPC", "ENTER", Map.of("typeCode", "01", "description", "PURCHASE"));
        assertThat(r.at("/fields/abend/code").asText()).isEqualTo("0001");
        assertThat(r.at("/fields/abend/culprit").asText()).isEqualTo("COTRTUPC");
        assertThat(r.at("/fields/abend/reason").asText()).isEmpty();
        assertThat(r.at("/fields/abend/message").asText()).isEqualTo("UNEXPECTED DATA SCENARIO");
        assertThat(client.session().getAttribute(TranTypeNavigation.MAINT_STATE)).isNull();
    }
}
