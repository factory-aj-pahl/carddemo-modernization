package com.carddemo.batch;

import com.carddemo.batch.backup.CycleBackupService;
import com.carddemo.batch.cycle.CycleJobsConfig;
import com.carddemo.batch.posting.PosttranJobConfig;
import com.carddemo.common.batch.JobLog;
import com.carddemo.cycle.api.InterestCalculation;
import com.carddemo.migration.Layout;
import com.carddemo.migration.Layouts;
import com.carddemo.migration.SampleDataLoader;
import com.carddemo.migration.TableExporter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Parses one batch command and runs it. Arguments starting with {@code --} are Spring Boot properties and
 * are ignored here. Commands:
 * <ul>
 * <li>{@code POSTTRAN businessDate=YYYY-MM-DD dailyFile=PATH rejectsFile=PATH}</li>
 * <li>{@code INTCALC businessDate=YYYY-MM-DD}: monthly interest (CBACT04C); at most one completed run per month</li>
 * <li>{@code CREASTMT businessDate=YYYY-MM-DD stmtFile=PATH htmlFile=PATH}: statements (CBSTM03A/B)</li>
 * <li>{@code TRANVERIFY dailyFile=PATH}: read-only check of a daily file (CBTRN01C)</li>
 * <li>{@code TRANREPT startDate=YYYY-MM-DD endDate=YYYY-MM-DD reportFile=PATH [userId=ID]}: report (CBTRN03C)</li>
 * <li>{@code BACKUP businessDate=YYYY-MM-DD}: pre-cycle snapshot; {@code RESTORE backupId=N | businessDate=YYYY-MM-DD}</li>
 * <li>{@code load DIR [usrsec=FILE]}: load sample-format files (seed and migration step)</li>
 * <li>{@code export DIR [files=a.txt,b.txt]}: write tables back in their legacy layouts</li>
 * <li>{@code reset}: drop and re-create the schema (only with {@code --spring.flyway.clean-disabled=false})</li>
 * </ul>
 */
@Component
public class BatchCommandLine {

    static final DateTimeFormatter BUSINESS_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    static final String INVALID_DATE = "INVALID OR MISSING BUSINESS DATE";
    static final String INVALID_RANGE = "INVALID OR MISSING REPORT DATE RANGE";

    private final JobLauncher launcher;
    private final Map<String, Job> jobs;
    private final JobLog log;
    private final JdbcTemplate jdbc;
    private final Flyway flyway;
    private final CycleBackupService backups;

    BatchCommandLine(JobLauncher launcher, @Qualifier(PosttranJobConfig.JOB) Job posttran,
            @Qualifier(CycleJobsConfig.INTCALC) Job intcalc, @Qualifier(CycleJobsConfig.CREASTMT) Job creastmt,
            @Qualifier(CycleJobsConfig.TRANVERIFY) Job tranverify, @Qualifier(CycleJobsConfig.TRANREPT) Job tranrept,
            JobLog log, JdbcTemplate jdbc, Flyway flyway, CycleBackupService backups) {
        this.launcher = launcher;
        this.jobs = Map.of(PosttranJobConfig.JOB, posttran, CycleJobsConfig.INTCALC, intcalc, CycleJobsConfig.CREASTMT, creastmt,
            CycleJobsConfig.TRANVERIFY, tranverify, CycleJobsConfig.TRANREPT, tranrept);
        this.log = log;
        this.jdbc = jdbc;
        this.flyway = flyway;
        this.backups = backups;
    }

    public int execute(String... args) {
        List<String> words = Arrays.stream(args).filter(a -> !a.startsWith("--")).toList();
        if (words.isEmpty()) {
            return usage("no command");
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (String w : words.subList(1, words.size())) {
            int eq = w.indexOf('=');
            params.put(eq < 0 ? "" : w.substring(0, eq), eq < 0 ? w : w.substring(eq + 1));
        }
        try {
            return switch (words.get(0)) {
                case "POSTTRAN" -> posttran(params);
                case "INTCALC" -> intcalc(params);
                case "CREASTMT" -> creastmt(params);
                case "TRANVERIFY" -> tranverify(params);
                case "TRANREPT" -> tranrept(params);
                case "BACKUP" -> backup(params);
                case "RESTORE" -> restore(params);
                case "load" -> load(params);
                case "export" -> export(params);
                case "reset" -> reset();
                default -> usage("unknown command " + words.get(0));
            };
        } catch (IOException e) {
            System.err.println(e);
            return ExitCodes.ABEND;
        }
    }

    private int usage(String problem) {
        System.err.println("carddemo-batch: " + problem);
        System.err.println("usage: POSTTRAN businessDate=YYYY-MM-DD dailyFile=PATH rejectsFile=PATH"
            + " | INTCALC businessDate=YYYY-MM-DD | CREASTMT businessDate=YYYY-MM-DD stmtFile=PATH htmlFile=PATH"
            + " | TRANVERIFY dailyFile=PATH | TRANREPT startDate=YYYY-MM-DD endDate=YYYY-MM-DD reportFile=PATH [userId=ID]"
            + " | BACKUP businessDate=YYYY-MM-DD | RESTORE backupId=N|businessDate=YYYY-MM-DD"
            + " | load DIR [usrsec=FILE] | export DIR [files=NAMES] | reset");
        return ExitCodes.USAGE;
    }

    /** B-DATE: the business date, or null when it is missing or not a valid calendar date. */
    static LocalDate businessDate(Map<String, String> params) {
        return date(params.get("businessDate"));
    }

    static LocalDate date(String v) {
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(v, BUSINESS_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String absolute(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString();
    }

    private Optional<String> missing(Map<String, String> params, String... required) {
        return Arrays.stream(required).filter(r -> !params.containsKey(r) || params.get(r).isEmpty()).findFirst();
    }

    private int posttran(Map<String, String> params) {
        LocalDate date = businessDate(params);
        if (date == null) {
            log.display(INVALID_DATE);
            return ExitCodes.INVALID_DATE;
        }
        Optional<String> missing = missing(params, "dailyFile", "rejectsFile");
        if (missing.isPresent()) {
            return usage("missing parameter " + missing.get());
        }
        JobParameters jp = new JobParametersBuilder()
            .addString("businessDate", date.toString())
            .addString("dailyFile", absolute(params.get("dailyFile")))
            .addString("rejectsFile", absolute(params.get("rejectsFile")), false)
            .toJobParameters();
        return launch(PosttranJobConfig.JOB, jp, "POSTING ALREADY COMPLETED FOR " + date, true);
    }

    /** Q-CBACT04C-08: the job instance is the calendar month, so a second completed run in a month is refused. */
    private int intcalc(Map<String, String> params) {
        LocalDate date = businessDate(params);
        if (date == null) {
            log.display(INVALID_DATE);
            return ExitCodes.INVALID_DATE;
        }
        String month = InterestCalculation.interestMonth(date);
        JobParameters jp = new JobParametersBuilder()
            .addString("interestMonth", month)
            .addString("businessDate", date.toString(), false)
            .toJobParameters();
        return launch(CycleJobsConfig.INTCALC, jp, "INTEREST ALREADY CALCULATED FOR " + month, true);
    }

    private int creastmt(Map<String, String> params) {
        LocalDate date = businessDate(params);
        if (date == null) {
            log.display(INVALID_DATE);
            return ExitCodes.INVALID_DATE;
        }
        Optional<String> missing = missing(params, "stmtFile", "htmlFile");
        if (missing.isPresent()) {
            return usage("missing parameter " + missing.get());
        }
        JobParameters jp = new JobParametersBuilder()
            .addString("businessDate", date.toString())
            .addString("stmtFile", absolute(params.get("stmtFile")), false)
            .addString("htmlFile", absolute(params.get("htmlFile")), false)
            .toJobParameters();
        // A repeated CREASTMT for a date is refused on the error stream only; SYSOUT stays the COBOL's.
        return launch(CycleJobsConfig.CREASTMT, jp, "STATEMENTS ALREADY CREATED FOR " + date, false);
    }

    private int tranverify(Map<String, String> params) {
        Optional<String> missing = missing(params, "dailyFile");
        if (missing.isPresent()) {
            return usage("missing parameter " + missing.get());
        }
        JobParameters jp = new JobParametersBuilder()
            .addString("dailyFile", absolute(params.get("dailyFile")))
            .addString("run", UUID.randomUUID().toString())
            .toJobParameters();
        return launch(CycleJobsConfig.TRANVERIFY, jp, null, false);
    }

    /** Q-CBTRN03C-06/-07: one date range from the request; a missing, invalid, or reversed range is refused. */
    private int tranrept(Map<String, String> params) {
        LocalDate start = date(params.get("startDate"));
        LocalDate end = date(params.get("endDate"));
        if (start == null || end == null || start.isAfter(end)) {
            log.display(INVALID_RANGE);
            return ExitCodes.INVALID_DATE;
        }
        Optional<String> missing = missing(params, "reportFile");
        if (missing.isPresent()) {
            return usage("missing parameter " + missing.get());
        }
        JobParametersBuilder jp = new JobParametersBuilder()
            .addString("startDate", start.toString())
            .addString("endDate", end.toString())
            .addString("reportFile", absolute(params.get("reportFile")))
            .addString("run", UUID.randomUUID().toString());
        if (params.containsKey("userId") && !params.get("userId").isBlank()) {
            jp.addString("userId", params.get("userId").strip().toUpperCase());
        }
        return launch(CycleJobsConfig.TRANREPT, jp.toJobParameters(), null, false);
    }

    private int launch(String job, JobParameters jp, String refusal, boolean refusalOnSysout) {
        JobExecution execution;
        try {
            execution = launcher.run(jobs.get(job), jp);
        } catch (JobInstanceAlreadyCompleteException | JobExecutionAlreadyRunningException e) {
            if (refusalOnSysout) {
                log.display(refusal);
            } else {
                System.err.println(refusal != null ? refusal : e.getMessage());
            }
            return ExitCodes.REFUSED;
        } catch (Exception e) {
            e.printStackTrace();
            return abend();
        }
        return exitCode(execution);
    }

    private int exitCode(JobExecution execution) {
        if (execution.getStatus() != BatchStatus.COMPLETED) {
            execution.getAllFailureExceptions().forEach(t -> t.printStackTrace());
            return abend();
        }
        boolean warning = execution.getStepExecutions().stream().map(StepExecution::getExitStatus)
            .anyMatch(s -> PosttranJobConfig.COMPLETED_WITH_REJECTS.equals(s.getExitCode()));
        return warning ? ExitCodes.WARNING : ExitCodes.OK;
    }

    private int abend() {
        System.err.println("ABEND CODE U999");
        return ExitCodes.ABEND;
    }

    /** Q-TRANBKP-01: the cycle starts only after this succeeds (exit 0). */
    private int backup(Map<String, String> params) {
        LocalDate date = businessDate(params);
        if (date == null) {
            log.display(INVALID_DATE);
            return ExitCodes.INVALID_DATE;
        }
        try {
            CycleBackupService.Snapshot s = backups.backup(date);
            System.out.println("BACKUP " + s.backupId() + " TAKEN FOR " + date + ": " + s.accounts() + " ACCOUNTS, "
                + s.transactions() + " TRANSACTIONS, " + s.categoryBalances() + " CATEGORY BALANCES");
            return ExitCodes.OK;
        } catch (RuntimeException e) {
            e.printStackTrace();
            return abend();
        }
    }

    private int restore(Map<String, String> params) {
        Optional<Long> id;
        if (params.containsKey("backupId")) {
            try {
                id = Optional.of(Long.parseLong(params.get("backupId")));
            } catch (NumberFormatException e) {
                return usage("backupId must be a number");
            }
        } else {
            LocalDate date = businessDate(params);
            if (date == null) {
                log.display(INVALID_DATE);
                return ExitCodes.INVALID_DATE;
            }
            id = backups.latestFor(date);
        }
        try {
            Optional<CycleBackupService.Snapshot> s = id.flatMap(backups::restore);
            if (s.isEmpty()) {
                System.err.println("NO SUCH BACKUP");
                return ExitCodes.REFUSED;
            }
            System.out.println("RESTORED BACKUP " + s.get().backupId() + " OF " + s.get().businessDate());
            return ExitCodes.OK;
        } catch (RuntimeException e) {
            e.printStackTrace();
            return abend();
        }
    }

    private int load(Map<String, String> params) throws IOException {
        String dir = params.get("");
        if (dir == null) {
            return usage("load needs a directory");
        }
        SampleDataLoader loader = new SampleDataLoader(jdbc);
        SampleDataLoader.Result r = loader.loadDirectory(Path.of(dir));
        r.rowsPerFile().forEach((f, n) -> System.out.println("LOADED " + f + " " + n));
        System.out.println("GROUP IDS MOVED " + r.groupIdsMoved());
        if (params.containsKey("usrsec")) {
            SampleDataLoader.Result u = loader.loadUsers(Path.of(params.get("usrsec")));
            System.out.println("USERS " + u.users() + " ACCOUNT LINKS " + u.links());
        }
        return ExitCodes.OK;
    }

    private int export(Map<String, String> params) throws IOException {
        String dir = params.get("");
        if (dir == null) {
            return usage("export needs a directory");
        }
        List<String> names = params.containsKey("files") ? List.of(params.get("files").split(","))
            : Layouts.BASE.stream().map(Layout::fileName).toList();
        TableExporter exporter = new TableExporter(jdbc);
        for (String name : names) {
            Layout layout = Layouts.BASE.stream().filter(l -> l.fileName().equals(name)).findFirst().orElse(null);
            if (layout == null) {
                return usage("no layout for " + name);
            }
            System.out.println("EXPORTED " + name + " " + exporter.export(layout, Path.of(dir, name)));
        }
        return ExitCodes.OK;
    }

    private int reset() {
        flyway.clean();
        flyway.migrate();
        System.out.println("SCHEMA RESET");
        return ExitCodes.OK;
    }
}
