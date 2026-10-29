package com.carddemo.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.msg.CommonMessages;
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

@SpringBootTest(classes = TestApplication.class, properties = "carddemo.features.disabled-programs=COPAUS0C,COTRTLIC")
@AutoConfigureMockMvc
class DisabledOptionsTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;

    @Test
    @Covers({"Q-COMEN01C-05", "Q-COMEN01C-08", "BR-COMEN01C-06", "TS-COMEN01C-06", "ERR-COMEN01C-01"})
    void disabled_main_menu_option_names_the_option_in_red() {
        sample.load();
        ScreenClient client = new ScreenClient(mvc);
        client.signOn("USER0001");
        JsonNode r = client.post("COMEN01C", "ENTER", Map.of("option", "11"));
        assertThat(r.at("/message").asText()).isEqualTo("This option Pending Authorization View is not installed...");
        assertThat(r.at("/messageColor").asText()).isEqualTo("RED");
        assertThat(r.has("navigate")).isFalse();
    }

    @Test
    @Covers({"Q-COADM01C-03", "Q-COADM01C-04", "ERR-COADM01C-01", "TS-COADM01C-04", "BR-COADM01C-06"})
    void disabled_admin_option_is_green_without_name() {
        sample.load();
        ScreenClient client = new ScreenClient(mvc);
        client.signOn("ADMIN001");
        JsonNode r = client.post("COADM01C", "ENTER", Map.of("option", "5"));
        assertThat(r.at("/message").asText()).isEqualTo("This option is not installed ...");
        assertThat(r.at("/messageColor").asText()).isEqualTo("GREEN");
        assertThat(r.has("navigate")).isFalse();
    }

    @Test
    @Covers({"Q-COADM01C-07", "TS-COADM01C-10", "ERR-COADM01C-01"})
    void after_not_installed_the_admin_menu_processes_the_next_key() {
        sample.load();
        ScreenClient client = new ScreenClient(mvc);
        client.signOn("ADMIN001");

        client.post("COADM01C", "ENTER", Map.of("option", "5"));
        JsonNode invalidOption = client.post("COADM01C", "ENTER", Map.of("option", "7"));
        assertThat(invalidOption.at("/message").asText()).isEqualTo("Please enter a valid option number...");
        assertThat(invalidOption.at("/fields/option").asText()).isEqualTo("07");

        client.post("COADM01C", "ENTER", Map.of("option", "5"));
        JsonNode invalidKey = client.post("COADM01C", "PF2", Map.of("option", ""));
        assertThat(invalidKey.at("/message").asText()).isEqualTo(CommonMessages.INVALID_KEY);

        client.post("COADM01C", "ENTER", Map.of("option", "5"));
        assertThat(client.post("COADM01C", "ENTER", Map.of("option", "1")).at("/navigate").asText()).isEqualTo("COUSR00C");

        client.show("COADM01C");
        client.post("COADM01C", "ENTER", Map.of("option", "5"));
        assertThat(client.post("COADM01C", "PF3", Map.of("option", "")).at("/navigate").asText()).isEqualTo("COSGN00C");
        assertThat(client.showRaw("COADM01C").getStatus()).isEqualTo(401);
    }
}
