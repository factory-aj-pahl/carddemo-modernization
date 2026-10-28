package com.carddemo.reporting.internal;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code report_run} table. */
@Repository
public class ReportRuns {

    private static final String COLUMNS = "dag_run_id, report_type, start_date, end_date, user_id, requested_at, state";

    private final JdbcClient jdbc;

    ReportRuns(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(ReportRun r) {
        jdbc.sql("INSERT INTO report_run (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)")
            .params(r.runId(), r.reportType(), r.startDate(), r.endDate(), r.userId(), r.requestedAt(), r.state())
            .update();
    }

    void delete(String runId) {
        jdbc.sql("DELETE FROM report_run WHERE dag_run_id = ?").param(runId).update();
    }

    void updateState(String runId, String state, LocalDateTime checkedAt) {
        jdbc.sql("UPDATE report_run SET state = ?, state_checked_at = ? WHERE dag_run_id = ?")
            .params(state, checkedAt, runId).update();
    }

    Optional<ReportRun> find(String runId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM report_run WHERE dag_run_id = ?").param(runId)
            .query(ReportRuns::row).optional();
    }

    /** Newest first; {@code userId} null means every user's runs. */
    List<ReportRun> recent(String userId, int limit) {
        if (userId == null) {
            return jdbc.sql("SELECT TOP (?) " + COLUMNS + " FROM report_run ORDER BY requested_at DESC, dag_run_id DESC")
                .param(limit).query(ReportRuns::row).list();
        }
        return jdbc.sql("SELECT TOP (?) " + COLUMNS + " FROM report_run WHERE user_id = ? ORDER BY requested_at DESC, dag_run_id DESC")
            .params(limit, userId).query(ReportRuns::row).list();
    }

    private static ReportRun row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new ReportRun(rs.getString("dag_run_id"), rs.getString("report_type"), rs.getObject("start_date", java.time.LocalDate.class),
            rs.getObject("end_date", java.time.LocalDate.class), rs.getString("user_id"),
            rs.getObject("requested_at", LocalDateTime.class), rs.getString("state"));
    }
}
