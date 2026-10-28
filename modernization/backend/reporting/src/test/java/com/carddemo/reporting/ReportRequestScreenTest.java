package com.carddemo.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.msg.CommonMessages;
import com.carddemo.test.Covers;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** CORPT00C screen turns (the CICS date of the spec's scenarios is 2022-06-15). */
class ReportRequestScreenTest extends ReportScreenTestBase {

    private static void assertBlankForm(JsonNode r) {
        for (String f : new String[] {"reportType", "startMonth", "startDay", "startYear", "endMonth", "endDay", "endYear", "confirm"}) {
            assertThat(r.at("/fields/" + f).asText()).as(f).isEmpty();
        }
    }

    private static Map<String, String> echo(JsonNode r, String key, String value) {
        Map<String, String> f = new HashMap<>();
        r.get("fields").fields().forEachRemaining(e -> {
            if (e.getValue().isTextual()) {
                f.put(e.getKey(), e.getValue().asText());
            }
        });
        f.put(key, value);
        return f;
    }

    @Test
    @Covers({"NAV-CORPT00C-01"})
    void without_a_session_the_screen_is_refused() {
        assertThat(client.showRaw("CORPT00C").getStatus()).isEqualTo(401);
        assertThat(client.turn("CORPT00C", "ENTER", form("monthly")).getStatus()).isEqualTo(401);
        assertThat(launcher.launches).isEmpty();
    }

    @Test
    @Covers({"NAV-CORPT00C-02"})
    void first_entry_is_an_empty_form_with_the_cursor_on_the_report_type() {
        client.signOn(USER);
        JsonNode r = client.show("CORPT00C");
        assertBlankForm(r);
        assertThat(r.at("/focus").asText()).isEqualTo("monthly");
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/fields/runs").size()).isZero();
    }

    @Test
    @Covers({"TS-CORPT00C-01", "BR-CORPT00C-01", "BR-CORPT00C-02", "BR-CORPT00C-05", "VR-CORPT00C-16", "NAV-CORPT00C-03"})
    void monthly_without_confirmation_asks_to_confirm_and_starts_nothing() {
        client.signOn(USER);
        JsonNode r = enter(form("monthly"));
        assertThat(r.at("/message").asText()).isEqualTo("Please confirm to print the Monthly report...");
        assertThat(r.at("/messageColor").asText()).isEqualTo("RED");
        assertThat(r.at("/focus").asText()).isEqualTo("confirm");
        assertThat(r.at("/fields/reportType").asText()).isEqualTo("monthly");
        assertThat(launcher.launches).isEmpty();
        assertThat(runCount()).isZero();
    }

    @Test
    @Covers({"TS-CORPT00C-02", "BR-CORPT00C-02", "BR-CORPT00C-07", "BR-CORPT00C-08", "BR-CORPT00C-09", "BR-CORPT00C-11",
        "Q-CORPT00C-07", "Q-CORPT00C-10", "NAV-CORPT00C-07"})
    void confirmed_monthly_launches_one_run_for_the_current_month_and_clears_the_form() {
        client.signOn(USER);
        JsonNode first = enter(form("monthly"));
        JsonNode r = enter(echo(first, "confirm", "Y"));
        assertThat(launcher.launches).hasSize(1);
        Map<String, String> l = launcher.launches.get(0);
        assertThat(l).containsEntry("start_date", "2022-06-01").containsEntry("end_date", "2022-06-30")
            .containsEntry("user_id", USER);
        assertBlankForm(r);
        assertThat(r.at("/message").asText()).isEqualTo("Monthly report submitted for printing ...");
        assertThat(r.at("/message").asText()).doesNotContain(l.get("runId"));
        assertThat(r.at("/messageColor").asText()).isEqualTo("GREEN");
        assertThat(r.at("/focus").asText()).isEqualTo("monthly");
        JsonNode run = r.at("/fields/runs/0");
        assertThat(run.at("/runId").asText()).isEqualTo(l.get("runId"));
        assertThat(run.at("/reportType").asText()).isEqualTo("Monthly");
        assertThat(run.at("/startDate").asText()).isEqualTo("2022-06-01");
        assertThat(run.at("/endDate").asText()).isEqualTo("2022-06-30");
        assertThat(run.at("/state").asText()).isEqualTo("queued");
        assertThat(run.has("downloadUrl")).isFalse();
        assertThat(jdbc.queryForObject("SELECT user_id FROM report_run", String.class)).isEqualTo(USER);
    }

    @Test
    @Covers({"TS-CORPT00C-03", "BR-CORPT00C-02", "Q-CORPT00C-05"})
    void monthly_ends_on_the_last_day_of_the_month_including_december_and_leap_february() {
        clock.set(LocalDateTime.of(2022, 12, 10, 9, 0));
        client.signOn(USER);
        enter(with(form("monthly"), "confirm", "Y"));
        clock.set(LocalDateTime.of(2024, 2, 5, 9, 0));
        client.signOn(USER);
        enter(with(form("monthly"), "confirm", "y"));
        assertThat(launcher.launches).extracting(m -> m.get("start_date") + " " + m.get("end_date"))
            .containsExactly("2022-12-01 2022-12-31", "2024-02-01 2024-02-29");
    }

    @Test
    @Covers({"TS-CORPT00C-04", "BR-CORPT00C-03", "BR-CORPT00C-09"})
    void yearly_covers_the_current_calendar_year() {
        client.signOn(USER);
        JsonNode r = enter(with(form("yearly"), "confirm", "Y"));
        assertThat(launcher.launches.get(0)).containsEntry("start_date", "2022-01-01").containsEntry("end_date", "2022-12-31");
        assertThat(r.at("/message").asText()).isEqualTo("Yearly report submitted for printing ...");
        assertThat(r.at("/messageColor").asText()).isEqualTo("GREEN");
    }

    @Test
    @Covers({"BR-CORPT00C-01"})
    void date_fields_are_ignored_for_monthly_and_yearly() {
        client.signOn(USER);
        Map<String, String> f = custom("13", "AA", "", "", "", "", "Y");
        f.put("reportType", "monthly");
        JsonNode r = enter(f);
        assertThat(r.at("/message").asText()).isEqualTo("Monthly report submitted for printing ...");
        assertThat(launcher.launches.get(0)).containsEntry("start_date", "2022-06-01");
    }

    @Test
    @Covers({"TS-CORPT00C-05", "BR-CORPT00C-04", "BR-CORPT00C-05", "VR-CORPT00C-16", "BR-CORPT00C-09"})
    void custom_range_asks_for_confirmation_then_launches_with_the_entered_dates() {
        client.signOn(USER);
        JsonNode r = enter(custom("06", "01", "2022", "06", "30", "2022", ""));
        assertThat(r.at("/message").asText()).isEqualTo("Please confirm to print the Custom report...");
        assertThat(r.at("/focus").asText()).isEqualTo("confirm");
        assertThat(launcher.launches).isEmpty();
        r = enter(echo(r, "confirm", "Y"));
        assertThat(launcher.launches.get(0)).containsEntry("start_date", "2022-06-01").containsEntry("end_date", "2022-06-30");
        assertThat(r.at("/message").asText()).isEqualTo("Custom report submitted for printing ...");
        assertBlankForm(r);
    }

    @Test
    @Covers({"TS-CORPT00C-06", "VR-CORPT00C-02", "VR-CORPT00C-03", "VR-CORPT00C-04", "VR-CORPT00C-05", "VR-CORPT00C-06",
        "VR-CORPT00C-07", "Q-CORPT00C-12"})
    void each_empty_date_part_is_reported_in_order() {
        client.signOn(USER);
        String[][] cases = {
            {"", "", "", "", "", "", "Start Date - Month can NOT be empty...", "startMonth"},
            {"06", "", "", "", "", "", "Start Date - Day can NOT be empty...", "startDay"},
            {"06", "01", " ", "", "", "", "Start Date - Year can NOT be empty...", "startYear"},
            {"06", "01", "2022", "", "", "", "End Date - Month can NOT be empty...", "endMonth"},
            {"06", "01", "2022", "06", "", "", "End Date - Day can NOT be empty...", "endDay"},
            {"06", "01", "2022", "06", "30", "", "End Date - Year can NOT be empty...", "endYear"},
        };
        for (String[] c : cases) {
            JsonNode r = enter(custom(c[0], c[1], c[2], c[3], c[4], c[5], "Y"));
            assertThat(r.at("/message").asText()).as(c[6]).isEqualTo(c[6]);
            assertThat(r.at("/focus").asText()).as(c[6]).isEqualTo(c[7]);
        }
        assertThat(launcher.launches).isEmpty();
    }

    @Test
    @Covers({"TS-CORPT00C-07", "VR-CORPT00C-08", "VR-CORPT00C-09", "VR-CORPT00C-11", "VR-CORPT00C-12"})
    void month_above_12_and_day_above_31_are_rejected() {
        client.signOn(USER);
        String[][] cases = {
            {"13", "01", "2022", "06", "30", "2022", "Start Date - Not a valid Month...", "startMonth"},
            {"06", "32", "2022", "06", "30", "2022", "Start Date - Not a valid Day...", "startDay"},
            {"06", "01", "2022", "13", "30", "2022", "End Date - Not a valid Month...", "endMonth"},
            {"06", "01", "2022", "06", "32", "2022", "End Date - Not a valid Day...", "endDay"},
        };
        for (String[] c : cases) {
            JsonNode r = enter(custom(c[0], c[1], c[2], c[3], c[4], c[5], "Y"));
            assertThat(r.at("/message").asText()).as(c[6]).isEqualTo(c[6]);
            assertThat(r.at("/focus").asText()).as(c[6]).isEqualTo(c[7]);
        }
        assertThat(launcher.launches).isEmpty();
    }

    @Test
    @Covers({"Q-CORPT00C-01", "VR-CORPT00C-08", "VR-CORPT00C-09", "VR-CORPT00C-10", "VR-CORPT00C-11", "VR-CORPT00C-12",
        "VR-CORPT00C-13"})
    void date_parts_that_are_not_digits_get_the_part_message_in_rule_order() {
        client.signOn(USER);
        String[][] cases = {
            {"A6", "01", "2022", "06", "30", "2022", "Start Date - Not a valid Month...", "startMonth"},
            {"06", "-1", "2022", "06", "30", "2022", "Start Date - Not a valid Day...", "startDay"},
            {"06", "01", "20A2", "06", "30", "2022", "Start Date - Not a valid Year...", "startYear"},
            {"06", "01", "2022", "1x", "30", "2022", "End Date - Not a valid Month...", "endMonth"},
            {"06", "01", "2022", "06", "3.", "2022", "End Date - Not a valid Day...", "endDay"},
            {"06", "01", "2022", "06", "30", "22 2", "End Date - Not a valid Year...", "endYear"},
        };
        for (String[] c : cases) {
            JsonNode r = enter(custom(c[0], c[1], c[2], c[3], c[4], c[5], "Y"));
            assertThat(r.at("/message").asText()).as(c[6]).isEqualTo(c[6]);
            assertThat(r.at("/focus").asText()).as(c[6]).isEqualTo(c[7]);
        }
        // A non-digit part stays as typed; the digit parts before the check are normalized.
        JsonNode r = enter(custom("6", "1", "20A2", "6", "30", "2022", "Y"));
        assertThat(r.at("/fields/startMonth").asText()).isEqualTo("06");
        assertThat(r.at("/fields/startYear").asText()).isEqualTo("20A2");
        assertThat(launcher.launches).isEmpty();
    }

    @Test
    @Covers({"TS-CORPT00C-08", "VR-CORPT00C-14", "VR-CORPT00C-15"})
    void dates_that_are_not_calendar_dates_are_rejected() {
        client.signOn(USER);
        String[][] cases = {
            {"02", "30", "2022", "06", "30", "2022", "Start Date - Not a valid date...", "startMonth"},
            {"06", "01", "2022", "06", "31", "2022", "End Date - Not a valid date...", "endMonth"},
            {"00", "01", "2022", "06", "30", "2022", "Start Date - Not a valid date...", "startMonth"},
            {"06", "00", "2022", "06", "30", "2022", "Start Date - Not a valid date...", "startMonth"},
            {"06", "01", "2022", "06", "30", "0000", "End Date - Not a valid date...", "endMonth"},
        };
        for (String[] c : cases) {
            JsonNode r = enter(custom(c[0], c[1], c[2], c[3], c[4], c[5], "Y"));
            assertThat(r.at("/message").asText()).as(c[6]).isEqualTo(c[6]);
            assertThat(r.at("/focus").asText()).as(c[6]).isEqualTo(c[7]);
        }
        assertThat(launcher.launches).isEmpty();
    }

    @Test
    @Covers({"TS-CORPT00C-09", "BR-CORPT00C-04"})
    void single_digit_parts_are_written_back_with_leading_zeros() {
        client.signOn(USER);
        JsonNode r = enter(custom("6", "1", "2022", "06", "30", "2022", ""));
        assertThat(r.at("/message").asText()).isEqualTo("Please confirm to print the Custom report...");
        assertThat(r.at("/fields/startMonth").asText()).isEqualTo("06");
        assertThat(r.at("/fields/startDay").asText()).isEqualTo("01");
        assertThat(r.at("/fields/endDay").asText()).isEqualTo("30");
        assertThat(r.at("/fields/startYear").asText()).isEqualTo("2022");
    }

    @Test
    @Covers({"TS-CORPT00C-10", "Q-CORPT00C-02", "Q-CSUTLDTC-05"})
    void two_digit_and_pre_gregorian_years_are_not_valid_dates() {
        client.signOn(USER);
        JsonNode r = enter(custom("06", "01", "22", "06", "30", "2022", "Y"));
        assertThat(r.at("/message").asText()).isEqualTo("Start Date - Not a valid date...");
        assertThat(r.at("/fields/startYear").asText()).isEqualTo("0022");
        r = enter(custom("06", "01", "2022", "06", "30", "1500", "Y"));
        assertThat(r.at("/message").asText()).isEqualTo("End Date - Not a valid date...");
        assertThat(r.at("/focus").asText()).isEqualTo("endMonth");
        assertThat(launcher.launches).isEmpty();
    }

    @Test
    @Covers({"TS-CORPT00C-11", "Q-CORPT00C-03"})
    void a_start_after_the_end_is_accepted() {
        client.signOn(USER);
        JsonNode r = enter(custom("06", "30", "2022", "06", "01", "2022", "Y"));
        assertThat(r.at("/message").asText()).isEqualTo("Custom report submitted for printing ...");
        assertThat(launcher.launches.get(0)).containsEntry("start_date", "2022-06-30").containsEntry("end_date", "2022-06-01");
    }

    @Test
    @Covers({"TS-CORPT00C-12", "VR-CORPT00C-01", "Q-CORPT00C-04"})
    void no_report_type_asks_to_select_one() {
        client.signOn(USER);
        for (String type : new String[] {"", "X", "weekly"}) {
            JsonNode r = enter(with(form(type), "confirm", "Y"));
            assertThat(r.at("/message").asText()).isEqualTo("Select a report type to print report...");
            assertThat(r.at("/focus").asText()).isEqualTo("monthly");
        }
        assertThat(launcher.launches).isEmpty();
    }

    @Test
    @Covers({"TS-CORPT00C-13", "VR-CORPT00C-17", "BR-CORPT00C-06", "Q-CORPT00C-11"})
    void confirm_other_than_y_or_n_is_rejected_and_n_clears_the_form() {
        client.signOn(USER);
        JsonNode r = enter(with(form("monthly"), "confirm", "X"));
        assertThat(r.at("/message").asText()).isEqualTo("\"X\" is not a valid value to confirm...");
        assertThat(r.at("/focus").asText()).isEqualTo("confirm");
        for (String no : new String[] {"N", "n"}) {
            r = enter(custom("06", "01", "2022", "06", "30", "2022", no));
            assertBlankForm(r);
            assertThat(r.has("message")).isFalse();
            assertThat(r.at("/focus").asText()).isEqualTo("monthly");
        }
        assertThat(launcher.launches).isEmpty();
        assertThat(runCount()).isZero();
    }

    @Test
    @Covers({"TS-CORPT00C-14", "NAV-CORPT00C-04", "NAV-CORPT00C-06"})
    void pf3_returns_to_the_main_menu() {
        client.signOn(USER);
        JsonNode r = client.post("CORPT00C", "PF3", with(form("monthly"), "confirm", "Y"));
        assertThat(r.at("/navigate").asText()).isEqualTo("COMEN01C");
        assertThat(launcher.launches).isEmpty();
    }

    @Test
    @Covers({"TS-CORPT00C-14", "NAV-CORPT00C-05"})
    void other_keys_are_invalid_and_keep_the_entered_values() {
        client.signOn(USER);
        for (String key : new String[] {"PF4", "PF5", "PF12", "CLEAR", "PA1"}) {
            JsonNode r = client.post("CORPT00C", key, custom("6", "01", "2022", "06", "30", "2022", "Y"));
            assertThat(r.at("/message").asText()).as(key).isEqualTo(CommonMessages.INVALID_KEY);
            assertThat(r.at("/focus").asText()).isEqualTo("monthly");
            assertThat(r.at("/fields/startMonth").asText()).isEqualTo("6");
            assertThat(r.at("/fields/confirm").asText()).isEqualTo("Y");
        }
        assertThat(launcher.launches).isEmpty();
    }

    @Test
    @Covers({"ERR-CORPT00C-01", "ERR-CORPT00C-02", "Q-CORPT00C-09"})
    void a_failed_launch_shows_the_tdq_message_and_leaves_nothing_behind() {
        client.signOn(USER);
        launcher.fail = true;
        JsonNode r = enter(custom("6", "01", "2022", "06", "30", "2022", "Y"));
        assertThat(r.at("/message").asText()).isEqualTo("Unable to Write TDQ (JOBS)...");
        assertThat(r.at("/messageColor").asText()).isEqualTo("RED");
        assertThat(r.at("/focus").asText()).isEqualTo("monthly");
        assertThat(r.at("/fields/startMonth").asText()).isEqualTo("06");
        assertThat(r.at("/fields/confirm").asText()).isEqualTo("Y");
        assertThat(runCount()).isZero();
        assertThat(r.at("/fields/runs").size()).isZero();
    }

    @Test
    @Covers({"ERR-CORPT00C-03"})
    void missing_input_fields_are_treated_as_blank() {
        client.signOn(USER);
        JsonNode r = client.post("CORPT00C", "ENTER", Map.of("reportType", "custom"));
        assertThat(r.at("/message").asText()).isEqualTo("Start Date - Month can NOT be empty...");
        r = client.post("CORPT00C", "ENTER", Map.of());
        assertThat(r.at("/message").asText()).isEqualTo("Select a report type to print report...");
    }

    @Test
    @Covers({"ERR-CORPT00C-04", "BR-CORPT00C-11"})
    void the_screen_lists_the_state_the_scheduler_reports_including_failures() {
        client.signOn(USER);
        enter(with(form("yearly"), "confirm", "Y"));
        String runId = launcher.launches.get(0).get("runId");
        launcher.states.put(runId, "failed");
        JsonNode r = client.show("CORPT00C");
        assertThat(r.at("/fields/runs/0/state").asText()).isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT state FROM report_run WHERE dag_run_id = ?", String.class, runId))
            .isEqualTo("failed");
        // A finished run is not asked about again.
        launcher.states.put(runId, "running");
        assertThat(client.show("CORPT00C").at("/fields/runs/0/state").asText()).isEqualTo("failed");
    }

    @Test
    @Covers({"D-REPORT", "Q-CORPT00C-07"})
    void users_see_their_own_runs_and_admins_see_every_run() {
        insertRun("other_run", "USER0002", "success");
        client.signOn(USER);
        enter(with(form("monthly"), "confirm", "Y"));
        JsonNode r = client.show("CORPT00C");
        assertThat(r.at("/fields/runs").size()).isEqualTo(1);
        assertThat(r.at("/fields/runs/0/userId").asText()).isEqualTo(USER);
        client.signOn(ADMIN);
        r = enter(with(form("yearly"), "confirm", "Y"));
        assertThat(r.at("/fields/runs").size()).isEqualTo(3);
        assertThat(launcher.launches.get(1)).containsEntry("user_id", ADMIN);
    }
}
