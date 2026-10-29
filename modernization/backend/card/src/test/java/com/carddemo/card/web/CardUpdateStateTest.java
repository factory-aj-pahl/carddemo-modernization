package com.carddemo.card.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.test.Covers;
import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** 2000-DECIDE-ACTION of COCRDUPC with a change action it has no branch for (ERR-COCRDUPC-06). */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class CardUpdateStateTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;

    @Test
    @Covers({"ERR-COCRDUPC-06"})
    void an_unexpected_change_action_fails_the_turn() {
        sample.load();
        ScreenClient client = new ScreenClient(mvc);
        client.signOn("ADMIN001");
        client.show("COCRDUPC");
        client.session().setAttribute(CardUpdateController.STATE,
            new CardUpdateController.UpdState(false, null, 50L, "0500024453765740", null, null));
        assertThatThrownBy(() -> client.post("COCRDUPC", "ENTER", Map.of("accountId", "00000000050", "cardNum", "0500024453765740")))
            .as("the turn ends without a screen").hasRootCauseInstanceOf(RuntimeException.class);
    }
}
