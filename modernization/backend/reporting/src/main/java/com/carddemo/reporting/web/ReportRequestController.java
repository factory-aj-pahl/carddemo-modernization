package com.carddemo.reporting.web;

import com.carddemo.common.date.CalendarDates;
import com.carddemo.common.msg.CommonMessages;
import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.MessageColor;
import com.carddemo.common.screen.ScreenResponse;
import com.carddemo.common.security.CurrentUser;
import com.carddemo.reporting.internal.ReportLauncher.LaunchFailed;
import com.carddemo.reporting.internal.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CORPT00C transaction report request (transaction CR00). The job submission to the internal reader
 * becomes one launch of the report DAG with the date range and requester (Q-CORPT00C-07, -09); the
 * screen also lists the user's report runs with their state and a download link (Q-CORPT00C-10 keeps
 * the success message without a job ID).
 */
@RestController
@RequestMapping("/api/screens/CORPT00C")
@Tag(name = "CORPT00C", description = "Transaction reports")
public class ReportRequestController {

    static final String SELECT_TYPE = "Select a report type to print report...";
    static final String LAUNCH_FAILED = "Unable to Write TDQ (JOBS)...";
    static final String FOCUS_TYPE = "monthly";

    /** Q-CORPT00C-04: one choice instead of three marker fields. */
    enum ReportType {
        MONTHLY("Monthly"), YEARLY("Yearly"), CUSTOM("Custom");

        final String label;

        ReportType(String label) {
            this.label = label;
        }

        static ReportType parse(String value) {
            if (value == null) {
                return null;
            }
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "monthly" -> MONTHLY;
                case "yearly" -> YEARLY;
                case "custom" -> CUSTOM;
                default -> null;
            };
        }
    }

    /** CORPT0A input fields plus the visible report runs. */
    public record Fields(String reportType, String startMonth, String startDay, String startYear, String endMonth,
                         String endDay, String endYear, String confirm, List<RunView> runs) {

        Fields withRuns(List<RunView> r) {
            return new Fields(reportType, startMonth, startDay, startYear, endMonth, endDay, endYear, confirm, r);
        }

        Fields withDates(String sm, String sd, String sy, String em, String ed, String ey) {
            return new Fields(reportType, sm, sd, sy, em, ed, ey, confirm, runs);
        }
    }

    private static final Fields EMPTY = new Fields("", "", "", "", "", "", "", "", List.of());

    public record Request(Key key, String reportType, String startMonth, String startDay, String startYear,
                          String endMonth, String endDay, String endYear, String confirm) {

        Fields fields() {
            return new Fields(cut(reportType, 7), cut(startMonth, 2), cut(startDay, 2), cut(startYear, 4), cut(endMonth, 2),
                cut(endDay, 2), cut(endYear, 4), cut(confirm, 1), List.of());
        }
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** First failing rule: message, cursor field, and the form as it is sent back. */
    private static final class Failed extends RuntimeException {
        final transient Fields form;
        final String focus;

        Failed(Fields form, String message, String focus) {
            super(message, null, false, false);
            this.form = form;
            this.focus = focus;
        }
    }

    private final ReportService reports;

    public ReportRequestController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    @Operation(summary = "First entry: empty form with the cursor on the report type (NAV-CORPT00C-02)")
    public ScreenResponse<Fields> show() {
        return screen(EMPTY).withFocus(FOCUS_TYPE);
    }

    @PostMapping
    @Operation(summary = "Report turn: ENTER validates, asks for and acts on the confirmation; PF3 back to the menu")
    public ScreenResponse<Fields> turn(@RequestBody Request req) {
        Key key = req.key() == null ? Key.ENTER : req.key();
        Fields in = req.fields();
        if (key == Key.PF3) {
            return ScreenResponse.navigate("COMEN01C");
        }
        if (key != Key.ENTER) {
            return screen(in).error(CommonMessages.INVALID_KEY).withFocus(FOCUS_TYPE);
        }
        try {
            return enter(in);
        } catch (Failed f) {
            return screen(f.form).error(f.getMessage()).withFocus(f.focus);
        }
    }

    /** PROCESS-ENTER-KEY: report type first (BR-CORPT00C-01), then the dates, then the confirmation. */
    private ScreenResponse<Fields> enter(Fields in) {
        ReportType type = ReportType.parse(in.reportType());
        if (type == null) {
            throw new Failed(in, SELECT_TYPE, FOCUS_TYPE);
        }
        LocalDate today = reports.today();
        return switch (type) {
            case MONTHLY -> confirm(in, type, today.withDayOfMonth(1), YearMonth.from(today).atEndOfMonth());
            case YEARLY -> confirm(in, type, LocalDate.of(today.getYear(), 1, 1), LocalDate.of(today.getYear(), 12, 31));
            case CUSTOM -> custom(in);
        };
    }

    private ScreenResponse<Fields> custom(Fields in) {
        required(in, in.startMonth(), "Start Date - Month can NOT be empty...", "startMonth");
        required(in, in.startDay(), "Start Date - Day can NOT be empty...", "startDay");
        required(in, in.startYear(), "Start Date - Year can NOT be empty...", "startYear");
        required(in, in.endMonth(), "End Date - Month can NOT be empty...", "endMonth");
        required(in, in.endDay(), "End Date - Day can NOT be empty...", "endDay");
        required(in, in.endYear(), "End Date - Year can NOT be empty...", "endYear");
        // BR-CORPT00C-04: leading zeros are written back; Q-CORPT00C-01: parts that are not digits stay as typed and fail below.
        Fields f = in.withDates(zeros(in.startMonth(), 2), zeros(in.startDay(), 2), zeros(in.startYear(), 4),
            zeros(in.endMonth(), 2), zeros(in.endDay(), 2), zeros(in.endYear(), 4));
        part(f, f.startMonth(), 12, "Start Date - Not a valid Month...", "startMonth");
        part(f, f.startDay(), 31, "Start Date - Not a valid Day...", "startDay");
        part(f, f.startYear(), 9999, "Start Date - Not a valid Year...", "startYear");
        part(f, f.endMonth(), 12, "End Date - Not a valid Month...", "endMonth");
        part(f, f.endDay(), 31, "End Date - Not a valid Day...", "endDay");
        part(f, f.endYear(), 9999, "End Date - Not a valid Year...", "endYear");
        String start = f.startYear() + "-" + f.startMonth() + "-" + f.startDay();
        String end = f.endYear() + "-" + f.endMonth() + "-" + f.endDay();
        // DF-8 (Q-CORPT00C-02, Q-CSUTLDTC-05): dates before 1582-10-15, such as year 22 read as 0022, are not valid.
        if (!CalendarDates.isValid(start, CalendarDates.ISO)) {
            throw new Failed(f, "Start Date - Not a valid date...", "startMonth");
        }
        if (!CalendarDates.isValid(end, CalendarDates.ISO)) {
            throw new Failed(f, "End Date - Not a valid date...", "endMonth");
        }
        // Q-CORPT00C-03 (PRESERVE): a start after the end is accepted.
        return confirm(f, ReportType.CUSTOM, LocalDate.parse(start), LocalDate.parse(end));
    }

    /** SUBMIT-JOB-TO-INTRDR. */
    private ScreenResponse<Fields> confirm(Fields form, ReportType type, LocalDate start, LocalDate end) {
        String c = form.confirm();
        if (c.isBlank()) {
            throw new Failed(form, "Please confirm to print the " + type.label + " report...", "confirm");
        }
        if (c.equals("N") || c.equals("n")) {
            return screen(EMPTY).withFocus(FOCUS_TYPE);
        }
        if (!c.equals("Y") && !c.equals("y")) {
            throw new Failed(form, "\"" + c.strip() + "\" is not a valid value to confirm...", "confirm");
        }
        try {
            reports.submit(type.label, start, end, CurrentUser.require());
        } catch (LaunchFailed e) {
            throw new Failed(form, LAUNCH_FAILED, FOCUS_TYPE);
        }
        return screen(EMPTY).withMessage(type.label + " report submitted for printing ...", MessageColor.GREEN)
            .withFocus(FOCUS_TYPE);
    }

    private ScreenResponse<Fields> screen(Fields f) {
        return ScreenResponse.show(f.withRuns(RunView.list(reports, CurrentUser.require())));
    }

    private static void required(Fields f, String value, String message, String focus) {
        if (value.isBlank()) {
            throw new Failed(f, message, focus);
        }
    }

    /** NUMVAL-C into PIC 99 / PIC 9999, applied only to digit text (Q-CORPT00C-01). */
    private static String zeros(String raw, int width) {
        String s = raw.strip();
        if (!digits(s)) {
            return raw;
        }
        return "0".repeat(Math.max(0, width - s.length())) + s;
    }

    private static void part(Fields f, String value, int max, String message, String focus) {
        if (!digits(value) || Integer.parseInt(value) > max) {
            throw new Failed(f, message, focus);
        }
    }

    private static boolean digits(String s) {
        return !s.isEmpty() && s.chars().allMatch(ch -> ch >= '0' && ch <= '9');
    }
}
