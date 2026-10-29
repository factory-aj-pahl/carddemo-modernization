package com.carddemo.batch;

import com.carddemo.authorization.api.AuthorizationJobResult;
import com.carddemo.authorization.api.AuthorizationJobs;
import com.carddemo.batch.backup.CycleBackupService;
import com.carddemo.batch.cycle.CycleJobsConfig;
import com.carddemo.batch.posting.PosttranJobConfig;
import com.carddemo.common.batch.JobLog;
import com.carddemo.cycle.api.InterestCalculation;
import com.carddemo.dataexchange.api.AccountExtractFiles;
import com.carddemo.dataexchange.api.DataExchangeJobs;
import com.carddemo.dataexchange.api.ImportFiles;
import com.carddemo.dataexchange.api.JobResult;
import com.carddemo.migration.Db2Seeds;
import com.carddemo.migration.ImsPendingAuthUnload;
import com.carddemo.migration.Layout;
import com.carddemo.migration.Layouts;
import com.carddemo.migration.SampleDataLoader;
import com.carddemo.migration.TableExporter;
import com.carddemo.refdata.api.TranTypeBatchUpdate;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * <li>{@code READCARD}, {@code READXREF}, {@code READCUST}: masked listings (CBACT02C, CBACT03C, CBCUS01C)</li>
 * <li>{@code READACCT outFile=PATH arryFile=PATH vbrcFile=PATH}: account listing and extracts (CBACT01C)</li>
 * <li>{@code CBEXPORT exportFile=PATH}: branch migration export (CBEXPORT)</li>
 * <li>{@code CBIMPORT exportFile=PATH custOut=PATH acctOut=PATH xrefOut=PATH trnxOut=PATH cardOut=PATH errOut=PATH}
 * (CBIMPORT)</li>
 * <li>{@code INTCALC businessDate=YYYY-MM-DD}: monthly interest (CBACT04C); at most one completed run per month</li>
 * <li>{@code INTSTATUS businessDate=YYYY-MM-DD}: read-only; whether INTCALC completed for the date's month, and the
 * latest earlier month it completed for (the daily cycle's month-start branch, B-CYCLE)</li>
 * <li>{@code CREASTMT businessDate=YYYY-MM-DD stmtFile=PATH htmlFile=PATH}: statements (CBSTM03A/B)</li>
 * <li>{@code TRANVERIFY dailyFile=PATH}: read-only check of a daily file (CBTRN01C)</li>
 * <li>{@code TRANREPT startDate=YYYY-MM-DD endDate=YYYY-MM-DD reportFile=PATH [userId=ID]}: report (CBTRN03C)</li>
 * <li>{@code BACKUP businessDate=YYYY-MM-DD}: pre-cycle snapshot; {@code RESTORE backupId=N | businessDate=YYYY-MM-DD}</li>
 * <li>{@code COBTUPDT inputFile=PATH}: apply transaction type add, update, and delete records (JOB-MNTTRDB2); exit
 * code 0, 4 when a record was rejected, 12 when the run stopped and was rolled back</li>
 * <li>{@code CBPAUP0C [sysin=EE,CCCCC,DDDDD,F]}: purge expired pending authorizations (CBPAUP0J)</li>
 * <li>{@code PAUDBUNL rootFile=PATH childFile=PATH}, {@code DBUNLDGS rootFile=PATH childFile=PATH}: unload the
 * pending-authorization tables (UNLDPADB, UNLDGSAM)</li>
 * <li>{@code PAUDBLOD rootFile=PATH childFile=PATH}: load a PAUDBUNL unload (LOADPADB)</li>
 * <li>{@code load DIR [usrsec=FILE] [ims=FILE]}: load sample-format files (seed and migration step); {@code ims} is
 * the DBPAUTP0 IMS unload</li>
 * <li>{@code loaddb2 [types=FILE] [categories=FILE]}: replace the transaction types and categories with the Db2
 * module seed files (DB2LTTYP.ctl, DB2LTCAT.ctl)</li>
 * <li>{@code export DIR [files=a.txt,b.txt] [ims=NAME]}: write tables back in their legacy layouts ({@code DB2LTTYP.ctl}
 * and {@code DB2LTCAT.ctl} are written as the Db2 seed SQL); {@code ims} writes the pending-authorization tables as an
 * IMS unload file named NAME (only that file unless {@code files} is given)</li>
 * <li>{@code WAITSTEP wait=NNNNNNNN}: pause for that many hundredths of a second (COBSWAIT); exit 12 with
 * {@code INVALID WAIT TIME} when the value is not 1 to 8 digits</li>
 * <li>{@code PRTCATBL reportFile=PATH}: category balance listing (JOB-PRTCATBL)</li>
 * <li>{@code reset}: drop and re-create the schema (only with {@code --spring.flyway.clean-disabled=false})</li>
 * </ul>
 */
@Component
public class BatchCommandLine {

    static final DateTimeFormatter BUSINESS_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    static final String INVALID_DATE = "INVALID OR MISSING BUSINESS DATE";
    static final String INVALID_RANGE = "INVALID OR MISSING REPORT DATE RANGE";
    static final String INVALID_WAIT = "INVALID WAIT TIME";
    static final int CATBAL_LINE = 40;

    /** Suspends the step; replaceable so tests need not sleep for the full interval. */
    interface Pause {
        void centiseconds(long n) throws InterruptedException;
    }

    Pause pause = n -> Thread.sleep(n * 10);

    private final JobLauncher launcher;
    private final Map<String, Job> jobs;
    private final JobLog log;
    private final JdbcTemplate jdbc;
    private final Flyway flyway;
    private final DataExchangeJobs dataExchange;
    private final CycleBackupService backups;
    private final TranTypeBatchUpdate tranTypeUpdate;
    private final AuthorizationJobs authorizations;

    BatchCommandLine(JobLauncher launcher, @Qualifier(PosttranJobConfig.JOB) Job posttran,
            @Qualifier(CycleJobsConfig.INTCALC) Job intcalc, @Qualifier(CycleJobsConfig.CREASTMT) Job creastmt,
            @Qualifier(CycleJobsConfig.TRANVERIFY) Job tranverify, @Qualifier(CycleJobsConfig.TRANREPT) Job tranrept,
            JobLog log, JdbcTemplate jdbc, Flyway flyway, CycleBackupService backups, DataExchangeJobs dataExchange,
            TranTypeBatchUpdate tranTypeUpdate, AuthorizationJobs authorizations) {
        this.launcher = launcher;
        this.jobs = Map.of(PosttranJobConfig.JOB, posttran, CycleJobsConfig.INTCALC, intcalc, CycleJobsConfig.CREASTMT, creastmt,
            CycleJobsConfig.TRANVERIFY, tranverify, CycleJobsConfig.TRANREPT, tranrept);
        this.log = log;
        this.jdbc = jdbc;
        this.flyway = flyway;
        this.dataExchange = dataExchange;
        this.backups = backups;
        this.tranTypeUpdate = tranTypeUpdate;
        this.authorizations = authorizations;
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
                case "READCARD" -> result(dataExchange.listCards());
                case "READXREF" -> result(dataExchange.listXrefs());
                case "READCUST" -> result(dataExchange.listCustomers());
                case "READACCT" -> readacct(params);
                case "CBEXPORT" -> cbexport(params);
                case "CBIMPORT" -> cbimport(params);
                case "INTCALC" -> intcalc(params);
                case "INTSTATUS" -> intstatus(params);
                case "CREASTMT" -> creastmt(params);
                case "TRANVERIFY" -> tranverify(params);
                case "TRANREPT" -> tranrept(params);
                case "BACKUP" -> backup(params);
                case "RESTORE" -> restore(params);
                case "COBTUPDT" -> cobtupdt(params);
                case "CBPAUP0C" -> authResult(authorizations.purgeExpired(params.getOrDefault("sysin", "")));
                case "PAUDBUNL", "PAUDBLOD", "DBUNLDGS" -> imsFiles(words.get(0), params);
                case "load" -> load(params);
                case "loaddb2" -> loadDb2(params);
                case "export" -> export(params);
                case "WAITSTEP" -> waitstep(params);
                case "PRTCATBL" -> prtcatbl(params);
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
            + " | INTCALC businessDate=YYYY-MM-DD | INTSTATUS businessDate=YYYY-MM-DD | CREASTMT businessDate=YYYY-MM-DD stmtFile=PATH htmlFile=PATH"
            + " | TRANVERIFY dailyFile=PATH | TRANREPT startDate=YYYY-MM-DD endDate=YYYY-MM-DD reportFile=PATH [userId=ID]"
            + " | BACKUP businessDate=YYYY-MM-DD | RESTORE backupId=N|businessDate=YYYY-MM-DD"
            + " | READCARD | READXREF | READCUST | READACCT outFile=PATH arryFile=PATH vbrcFile=PATH"
            + " | CBEXPORT exportFile=PATH"
            + " | CBIMPORT exportFile=PATH custOut=PATH acctOut=PATH xrefOut=PATH trnxOut=PATH cardOut=PATH errOut=PATH"
            + " | COBTUPDT inputFile=PATH"
            + " | CBPAUP0C [sysin=EE,CCCCC,DDDDD,F] | PAUDBUNL|PAUDBLOD|DBUNLDGS rootFile=PATH childFile=PATH"
            + " | load DIR [usrsec=FILE] [ims=FILE] | loaddb2 [types=FILE] [categories=FILE] | export DIR [files=NAMES] [ims=NAME]"
            + " | WAITSTEP wait=NNNNNNNN | PRTCATBL reportFile=PATH"
            + " | reset");
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

    /**
     * B-CYCLE, Q-CBACT04C-08: reads the same completed INTCALC job instances that make INTCALC refuse a second run in a
     * month (a RESTORE forgets the runs started after its backup, so a restored month reads as not calculated).
     */
    private int intstatus(Map<String, String> params) {
        LocalDate date = businessDate(params);
        if (date == null) {
            log.display(INVALID_DATE);
            return ExitCodes.INVALID_DATE;
        }
        String month = InterestCalculation.interestMonth(date);
        String latest = jdbc.queryForObject("""
            SELECT MAX(p.PARAMETER_VALUE) FROM BATCH_JOB_INSTANCE i
            JOIN BATCH_JOB_EXECUTION e ON e.JOB_INSTANCE_ID = i.JOB_INSTANCE_ID
            JOIN BATCH_JOB_EXECUTION_PARAMS p ON p.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID
            WHERE i.JOB_NAME = ? AND e.STATUS = 'COMPLETED' AND p.PARAMETER_NAME = 'interestMonth' AND p.PARAMETER_VALUE <= ?""",
            String.class, CycleJobsConfig.INTCALC, month);
        if (month.equals(latest)) {
            log.display("INTEREST ALREADY CALCULATED FOR " + month);
        } else {
            log.display("INTEREST NOT CALCULATED FOR " + month + "; "
                + (latest == null ? "NO EARLIER MONTH CALCULATED" : "LAST CALCULATED FOR " + latest));
        }
        return ExitCodes.OK;
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

    private int readacct(Map<String, String> params) {
        Map<String, Path> p = paths(params, "outFile", "arryFile", "vbrcFile");
        if (p == null) {
            return usage("READACCT needs outFile, arryFile and vbrcFile");
        }
        return result(dataExchange.extractAccounts(new AccountExtractFiles(p.get("outFile"), p.get("arryFile"), p.get("vbrcFile"))));
    }

    private int cbexport(Map<String, String> params) {
        Map<String, Path> p = paths(params, "exportFile");
        if (p == null) {
            return usage("CBEXPORT needs exportFile");
        }
        return result(dataExchange.exportAll(p.get("exportFile")));
    }

    private int cbimport(Map<String, String> params) {
        Map<String, Path> p = paths(params, "exportFile", "custOut", "acctOut", "xrefOut", "trnxOut", "cardOut", "errOut");
        if (p == null) {
            return usage("CBIMPORT needs exportFile, custOut, acctOut, xrefOut, trnxOut, cardOut and errOut");
        }
        return result(dataExchange.importAll(p.get("exportFile"), new ImportFiles(p.get("custOut"), p.get("acctOut"),
            p.get("xrefOut"), p.get("trnxOut"), p.get("cardOut"), p.get("errOut"))));
    }

    private int cobtupdt(Map<String, String> params) {
        Map<String, Path> p = paths(params, "inputFile");
        if (p == null) {
            return usage("COBTUPDT needs inputFile");
        }
        int rc = tranTypeUpdate.apply(p.get("inputFile")).returnCode();
        return rc == 12 ? ExitCodes.STOPPED : rc == 4 ? ExitCodes.WARNING : ExitCodes.OK;
    }

    private int imsFiles(String program, Map<String, String> params) {
        Map<String, Path> p = paths(params, "rootFile", "childFile");
        if (p == null) {
            return usage(program + " needs rootFile and childFile");
        }
        return authResult(switch (program) {
            case "PAUDBUNL" -> authorizations.unload(p.get("rootFile"), p.get("childFile"));
            case "PAUDBLOD" -> authorizations.load(p.get("rootFile"), p.get("childFile"));
            default -> authorizations.unloadGsam(p.get("rootFile"), p.get("childFile"));
        });
    }

    /** The program's own return code: 0, or 16 from its 9999-ABEND path. */
    private static int authResult(AuthorizationJobResult r) {
        return r.returnCode() == 0 ? ExitCodes.OK : ExitCodes.PROGRAM_RC16;
    }

    /** The named path parameters, or null when one is missing. */
    private static Map<String, Path> paths(Map<String, String> params, String... names) {
        Map<String, Path> out = new LinkedHashMap<>();
        for (String n : names) {
            String v = params.get(n);
            if (v == null || v.isBlank()) {
                return null;
            }
            out.put(n, Path.of(v).toAbsolutePath().normalize());
        }
        return out;
    }

    private int result(JobResult r) {
        return r.abended() ? abend() : ExitCodes.OK;
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
        if (params.containsKey("ims")) {
            ImsPendingAuthUnload.Result r2 = new ImsPendingAuthUnload(jdbc).load(Path.of(params.get("ims")));
            System.out.println("LOADED IMS ROOTS " + r2.roots() + " CHILDREN " + r2.children() + " INVALID ROOTS SKIPPED "
                + r2.invalidRoots() + " CHILDREN OF INVALID ROOTS SKIPPED " + r2.skippedChildren());
        }
        return ExitCodes.OK;
    }

    private int loadDb2(Map<String, String> params) throws IOException {
        String types = params.get("types");
        String categories = params.get("categories");
        if (types == null && categories == null) {
            return usage("loaddb2 needs types=FILE or categories=FILE");
        }
        Db2Seeds.load(jdbc, types == null ? null : Path.of(types), categories == null ? null : Path.of(categories))
            .forEach((f, n) -> System.out.println("LOADED " + f + " " + n));
        return ExitCodes.OK;
    }

    private int export(Map<String, String> params) throws IOException {
        String dir = params.get("");
        if (dir == null) {
            return usage("export needs a directory");
        }
        if (params.containsKey("ims")) {
            int[] n = new ImsPendingAuthUnload(jdbc).export(Path.of(dir, params.get("ims")));
            System.out.println("EXPORTED IMS " + params.get("ims") + " ROOTS " + n[0] + " CHILDREN " + n[1]);
            if (!params.containsKey("files")) {
                return ExitCodes.OK;
            }
        }
        List<String> names = params.containsKey("files") ? List.of(params.get("files").split(","))
            : Layouts.BASE.stream().map(Layout::fileName).toList();
        TableExporter exporter = new TableExporter(jdbc);
        for (String name : names) {
            if (name.equals(Db2Seeds.TYPES_FILE)) {
                System.out.println("EXPORTED " + name + " " + Db2Seeds.exportTypes(jdbc, Path.of(dir, name)));
                continue;
            }
            if (name.equals(Db2Seeds.CATEGORIES_FILE)) {
                System.out.println("EXPORTED " + name + " " + Db2Seeds.exportCategories(jdbc, Path.of(dir, name)));
                continue;
            }
            Layout layout = Layouts.BASE.stream().filter(l -> l.fileName().equals(name)).findFirst().orElse(null);
            if (layout == null) {
                return usage("no layout for " + name);
            }
            System.out.println("EXPORTED " + name + " " + exporter.export(layout, Path.of(dir, name)));
        }
        return ExitCodes.OK;
    }

    /** Q-COBSWAIT-01: the interval must be 1 to 8 decimal digits (at most 99,999,999 hundredths of a second). */
    private int waitstep(Map<String, String> params) {
        String v = params.getOrDefault("wait", "");
        if (!v.matches("[0-9]{1,8}")) {
            log.display(INVALID_WAIT);
            return ExitCodes.INVALID_WAIT;
        }
        try {
            pause.centiseconds(Long.parseLong(v));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return abend();
        }
        return ExitCodes.OK;
    }

    /**
     * JOB-PRTCATBL: account (11), type (2), category (4), and the balance edited as 9 digits, a period, and 2 digits,
     * blank-separated and sorted by key. Q-PRTCATBL-02: a negative balance gets a trailing {@code -}. Q-PRTCATBL-01:
     * each line keeps the first 40 bytes.
     */
    private int prtcatbl(Map<String, String> params) throws IOException {
        Map<String, Path> p = paths(params, "reportFile");
        if (p == null) {
            return usage("PRTCATBL needs reportFile");
        }
        List<String> lines = jdbc.query("SELECT account_id, type_cd, cat_cd, balance FROM tran_cat_balance"
                + " ORDER BY account_id, type_cd, cat_cd",
            (rs, i) -> catbalLine(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getBigDecimal(4)));
        Files.write(p.get("reportFile"), lines, StandardCharsets.ISO_8859_1);
        return ExitCodes.OK;
    }

    static String catbalLine(long account, String type, int category, BigDecimal balance) {
        String digits = balance.abs().setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        String edited = "0".repeat(Math.max(0, 12 - digits.length())) + digits + (balance.signum() < 0 ? "-" : "");
        String line = String.format("%011d %-2s %04d %s", account, type, category, edited);
        return String.format("%-" + CATBAL_LINE + "s", line).substring(0, CATBAL_LINE);
    }

    private int reset() {
        flyway.clean();
        flyway.migrate();
        System.out.println("SCHEMA RESET");
        return ExitCodes.OK;
    }
}
