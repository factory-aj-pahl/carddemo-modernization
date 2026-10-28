package com.carddemo.reporting.web;

import com.carddemo.common.security.CurrentUser;
import com.carddemo.reporting.internal.ReportRun;
import com.carddemo.reporting.internal.ReportService;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * One report run as the web app shows it. {@code downloadUrl} is set once the job has written the report.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunView(String runId, String reportType, String startDate, String endDate, String userId,
                      String requestedAt, String state, String downloadUrl) {

    private static final DateTimeFormatter REQUESTED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static RunView of(ReportRun r, ReportService reports) {
        String url = reports.reportFile(r).isPresent() ? "/api/reports/" + r.runId() + "/" + ReportService.REPORT_FILE : null;
        return new RunView(r.runId(), r.reportType(), r.startDate().toString(), r.endDate().toString(), r.userId(),
            REQUESTED.format(r.requestedAt()), r.state(), url);
    }

    static List<RunView> list(ReportService reports, CurrentUser user) {
        return reports.visibleRuns(user).stream().map(r -> of(r, reports)).toList();
    }
}
