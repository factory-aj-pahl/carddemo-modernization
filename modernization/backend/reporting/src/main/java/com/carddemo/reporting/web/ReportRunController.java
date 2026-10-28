package com.carddemo.reporting.web;

import com.carddemo.common.security.CurrentUser;
import com.carddemo.reporting.internal.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Report runs started from CORPT00C: state and download. A USER sees only own runs, an ADMIN every run. */
@RestController
@RequestMapping("/api/reports")
@Tag(name = "Reports", description = "Transaction report runs")
public class ReportRunController {

    private final ReportService reports;

    public ReportRunController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    @Operation(summary = "Recent report runs visible to the signed-on user, newest first")
    public List<RunView> runs() {
        return RunView.list(reports, CurrentUser.require());
    }

    @GetMapping("/{runId}")
    @Operation(summary = "One report run with its current state")
    public ResponseEntity<RunView> run(@PathVariable String runId) {
        return reports.visibleRun(runId, CurrentUser.require())
            .map(r -> ResponseEntity.ok(RunView.of(r, reports)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{runId}/" + ReportService.REPORT_FILE)
    @Operation(summary = "The report file (TRANREPT) of a run, once the job has written it")
    public ResponseEntity<Resource> download(@PathVariable String runId) {
        return reports.visibleRun(runId, CurrentUser.require())
            .flatMap(reports::reportFile)
            .map(p -> ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(runId + "-" + ReportService.REPORT_FILE).build().toString())
                .<Resource>body(new FileSystemResource(p)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
