package com.carddemo.reporting.internal;

import com.carddemo.common.security.CurrentUser;
import com.carddemo.reporting.internal.ReportLauncher.LaunchFailed;
import com.carddemo.reporting.internal.ReportLauncher.Parameters;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Report requests of CORPT00C: record the run, launch it once (Q-CORPT00C-09), follow its state, and find
 * the report file under {@code <outbox>/reports/<run id>/TRANREPT.txt}.
 */
@Service
public class ReportService {

    /** Runs listed on the screen (TRANREPT keeps the last 10 reports, Q-ENT-16). */
    public static final int LISTED = 10;
    public static final String REPORT_FILE = "TRANREPT.txt";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ReportRuns runs;
    private final ReportLauncher launcher;
    private final Clock clock;
    private final Path outbox;

    public ReportService(ReportRuns runs, ReportLauncher launcher, Clock clock,
                         @Value("${carddemo.reports.outbox:${CARDDEMO_OUTBOX:${user.home}/.carddemo/outbox}}") String outbox) {
        this.runs = runs;
        this.launcher = launcher;
        this.clock = clock;
        this.outbox = Path.of(outbox);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Records and launches one run; on a failed launch nothing remains. */
    public ReportRun submit(String reportType, LocalDate start, LocalDate end, CurrentUser user) throws LaunchFailed {
        LocalDateTime now = LocalDateTime.now(clock);
        String runId = "CR00_" + user.userId().replaceAll("[^A-Za-z0-9]", "-") + "_" + STAMP.format(now) + "_"
            + String.format("%04x", RANDOM.nextInt(0x10000));
        ReportRun run = new ReportRun(runId, reportType, start, end, user.userId(), now, "queued");
        runs.insert(run);
        try {
            launcher.launch(runId, new Parameters(start, end, user.userId()));
        } catch (LaunchFailed | RuntimeException e) {
            runs.delete(runId);
            throw e;
        }
        return run;
    }

    /** The runs a user may see, newest first, with current states: a USER's own, every run for an ADMIN. */
    public List<ReportRun> visibleRuns(CurrentUser user) {
        List<ReportRun> out = new ArrayList<>();
        for (ReportRun r : runs.recent(user.isAdmin() ? null : user.userId(), LISTED)) {
            out.add(refreshed(r));
        }
        return out;
    }

    /** A run the user may see (own runs; admins any), with its current state. */
    public Optional<ReportRun> visibleRun(String runId, CurrentUser user) {
        return runs.find(runId).filter(r -> user.isAdmin() || r.userId().equals(user.userId())).map(this::refreshed);
    }

    /** The report file of a run, when the job has written it. */
    public Optional<Path> reportFile(ReportRun run) {
        Path p = outbox.resolve("reports").resolve(run.runId()).resolve(REPORT_FILE);
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }

    private ReportRun refreshed(ReportRun r) {
        if (r.finished()) {
            return r;
        }
        Optional<String> state = launcher.state(r.runId());
        if (state.isEmpty() || state.get().equals(r.state())) {
            return r;
        }
        runs.updateState(r.runId(), state.get(), LocalDateTime.now(clock));
        return r.withState(state.get());
    }
}
