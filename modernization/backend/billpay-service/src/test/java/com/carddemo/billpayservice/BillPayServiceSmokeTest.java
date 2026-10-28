package com.carddemo.billpayservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestDatabaseConfig;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Selective conversion: the bill payment service runs alone with only the contexts it needs. */
@SpringBootTest(classes = BillPayServiceApplication.class)
@AutoConfigureMockMvc
@Import({SampleData.class, TestDatabaseConfig.class})
class BillPayServiceSmokeTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;

    @Test
    void only_bill_payment_is_installed_and_it_pays() throws Exception {
        sample.load();
        ScreenClient client = new ScreenClient(mvc);
        client.signOn("USER0001");
        JsonNode other = client.post("COMEN01C", "ENTER", Map.of("option", "1"));
        assertThat(other.at("/message").asText()).isEqualTo("This option Account View is not installed...");
        assertThat(client.post("COMEN01C", "ENTER", Map.of("option", "10")).at("/navigate").asText()).isEqualTo("COBIL00C");
        JsonNode paid = client.post("COBIL00C", "ENTER", Map.of("accountId", "00000000001", "confirm", "Y"));
        assertThat(paid.at("/message").asText()).isEqualTo("Payment successful.  Your Transaction ID is 0000000000000001.");

        String docs = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        assertThat(docs).contains("/api/screens/COBIL00C").doesNotContain("/api/screens/COACTVWC");
    }
}
