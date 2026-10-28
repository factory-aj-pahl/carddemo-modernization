package com.carddemo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** The assembled online API starts with every context and serves its OpenAPI contract. */
@SpringBootTest(classes = CardDemoApplication.class)
@AutoConfigureMockMvc
class ApplicationSmokeTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;

    @Test
    void health_and_openapi_are_public() throws Exception {
        assertThat(mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
        String docs = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        assertThat(docs).contains("/api/screens/COSGN00C", "/api/screens/COMEN01C", "/api/screens/COADM01C",
            "/api/screens/COBIL00C");
    }

    @Test
    void signon_menu_and_bill_payment_work_end_to_end() {
        sample.load();
        ScreenClient client = new ScreenClient(mvc);
        assertThat(client.signOn("USER0001").at("/navigate").asText()).isEqualTo("COMEN01C");
        assertThat(client.post("COMEN01C", "ENTER", Map.of("option", "10")).at("/navigate").asText()).isEqualTo("COBIL00C");
        assertThat(client.show("COBIL00C").at("/focus").asText()).isEqualTo("accountId");
    }
}
