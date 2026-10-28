package com.carddemo.reporting.internal;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** One row of {@code report_run}. {@code reportType} is {@code Monthly}, {@code Yearly}, or {@code Custom}. */
public record ReportRun(String runId, String reportType, LocalDate startDate, LocalDate endDate, String userId,
                        LocalDateTime requestedAt, String state) {

    /** States after which the scheduler no longer changes the run. */
    public boolean finished() {
        return "success".equals(state) || "failed".equals(state);
    }

    public ReportRun withState(String newState) {
        return new ReportRun(runId, reportType, startDate, endDate, userId, requestedAt, newState);
    }
}
