package com.carddemo.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.carddemo.test.Covers;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** {@code /api/reports}: run state and the TRANREPT download (own runs; admins any). */
class ReportRunApiTest extends ReportScreenTestBase {

    private MockHttpServletResponse fetch(String path) throws Exception {
        return mvc.perform(get(path).session(client.session())).andReturn().getResponse();
    }

    @Test
    @Covers({"Q-CORPT00C-10"})
    void the_requester_downloads_the_report_once_the_job_has_written_it() throws Exception {
        client.signOn(USER);
        enter(with(form("monthly"), "confirm", "Y"));
        String runId = launcher.launches.get(0).get("runId");
        assertThat(fetch("/api/reports/" + runId + "/TRANREPT.txt").getStatus()).isEqualTo(404);
        JsonNode list = client.body(fetch("/api/reports"));
        assertThat(list.at("/0/runId").asText()).isEqualTo(runId);
        assertThat(list.at("/0/downloadUrl").isMissingNode()).isTrue();

        writeReport(runId, "DAILY TRANSACTION REPORT\n");
        launcher.states.put(runId, "success");
        JsonNode run = client.body(fetch("/api/reports/" + runId));
        assertThat(run.at("/state").asText()).isEqualTo("success");
        assertThat(run.at("/downloadUrl").asText()).isEqualTo("/api/reports/" + runId + "/TRANREPT.txt");
        MockHttpServletResponse file = fetch("/api/reports/" + runId + "/TRANREPT.txt");
        assertThat(file.getStatus()).isEqualTo(200);
        assertThat(file.getContentAsString()).isEqualTo("DAILY TRANSACTION REPORT\n");
        assertThat(file.getHeader("Content-Disposition")).contains("attachment").contains(runId + "-TRANREPT.txt");
        assertThat(client.show("CORPT00C").at("/fields/runs/0/downloadUrl").asText())
            .isEqualTo("/api/reports/" + runId + "/TRANREPT.txt");
    }

    @Test
    @Covers({"D-REPORT"})
    void a_user_cannot_see_or_download_another_users_run_but_an_admin_can() throws Exception {
        insertRun("someone_elses_run", "USER0002", "success");
        writeReport("someone_elses_run", "OTHER REPORT\n");
        client.signOn(USER);
        assertThat(fetch("/api/reports/someone_elses_run").getStatus()).isEqualTo(404);
        assertThat(fetch("/api/reports/someone_elses_run/TRANREPT.txt").getStatus()).isEqualTo(404);
        assertThat(client.body(fetch("/api/reports")).size()).isZero();
        client.signOn(ADMIN);
        assertThat(fetch("/api/reports/someone_elses_run").getStatus()).isEqualTo(200);
        MockHttpServletResponse file = fetch("/api/reports/someone_elses_run/TRANREPT.txt");
        assertThat(file.getStatus()).isEqualTo(200);
        assertThat(file.getContentAsString()).isEqualTo("OTHER REPORT\n");
    }

    @Test
    @Covers({"D-REPORT"})
    void unknown_runs_and_path_tricks_are_not_found_and_anonymous_calls_are_refused() throws Exception {
        assertThat(fetch("/api/reports").getStatus()).isEqualTo(401);
        client.signOn(ADMIN);
        assertThat(fetch("/api/reports/no_such_run/TRANREPT.txt").getStatus()).isEqualTo(404);
        assertThat(fetch("/api/reports/..%2F..%2Fetc/TRANREPT.txt").getStatus()).isIn(400, 404);
    }
}
