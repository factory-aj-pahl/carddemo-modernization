package com.carddemo.batch;

import com.carddemo.batch.posting.PosttranJobConfig;
import com.carddemo.common.batch.JobLog;
import com.carddemo.dataexchange.api.AccountExtractFiles;
import com.carddemo.dataexchange.api.DataExchangeJobs;
import com.carddemo.dataexchange.api.ImportFiles;
import com.carddemo.dataexchange.api.JobResult;
import com.carddemo.migration.Db2Seeds;
import com.carddemo.migration.Layout;
import com.carddemo.migration.Layouts;
import com.carddemo.migration.SampleDataLoader;
import com.carddemo.migration.TableExporter;
import com.carddemo.refdata.api.TranTypeBatchUpdate;
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
 * <li>{@code COBTUPDT inputFile=PATH}: apply transaction type add, update, and delete records (JOB-MNTTRDB2); exit
 * code 0, 4 when a record was rejected, 12 when the run stopped and was rolled back</li>
 * <li>{@code load DIR [usrsec=FILE]}: load sample-format files (seed and migration step)</li>
 * <li>{@code loaddb2 [types=FILE] [categories=FILE]}: replace the transaction types and categories with the Db2
 * module seed files (DB2LTTYP.ctl, DB2LTCAT.ctl)</li>
 * <li>{@code export DIR [files=a.txt,b.txt]}: write tables back in their legacy layouts ({@code DB2LTTYP.ctl} and
 * {@code DB2LTCAT.ctl} are written as the Db2 seed SQL)</li>
 * <li>{@code reset}: drop and re-create the schema (only with {@code --spring.flyway.clean-disabled=false})</li>
 * </ul>
 */
@Component
public class BatchCommandLine {

    static final DateTimeFormatter BUSINESS_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private final JobLauncher launcher;
    private final Job posttran;
    private final JobLog log;
    private final JdbcTemplate jdbc;
    private final Flyway flyway;
    private final DataExchangeJobs dataExchange;
    private final TranTypeBatchUpdate tranTypeUpdate;

    BatchCommandLine(JobLauncher launcher, @Qualifier(PosttranJobConfig.JOB) Job posttran, JobLog log, JdbcTemplate jdbc,
            Flyway flyway, DataExchangeJobs dataExchange, TranTypeBatchUpdate tranTypeUpdate) {
        this.launcher = launcher;
        this.posttran = posttran;
        this.log = log;
        this.jdbc = jdbc;
        this.flyway = flyway;
        this.dataExchange = dataExchange;
        this.tranTypeUpdate = tranTypeUpdate;
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
                case "COBTUPDT" -> cobtupdt(params);
                case "load" -> load(params);
                case "loaddb2" -> loadDb2(params);
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
        System.err.println("usage: POSTTRAN businessDate=YYYY-MM-DD dailyFile=PATH rejectsFile=PATH | READCARD | READXREF"
            + " | READCUST | READACCT outFile=PATH arryFile=PATH vbrcFile=PATH | CBEXPORT exportFile=PATH"
            + " | CBIMPORT exportFile=PATH custOut=PATH acctOut=PATH xrefOut=PATH trnxOut=PATH cardOut=PATH errOut=PATH"
            + " | COBTUPDT inputFile=PATH"
            + " | load DIR [usrsec=FILE] | loaddb2 [types=FILE] [categories=FILE] | export DIR [files=NAMES] | reset");
        return ExitCodes.USAGE;
    }

    /** B-DATE: the business date, or null when it is missing or not a valid calendar date. */
    static LocalDate businessDate(Map<String, String> params) {
        String v = params.get("businessDate");
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(v, BUSINESS_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private int posttran(Map<String, String> params) {
        LocalDate date = businessDate(params);
        if (date == null) {
            log.display("INVALID OR MISSING BUSINESS DATE");
            return ExitCodes.INVALID_DATE;
        }
        for (String required : List.of("dailyFile", "rejectsFile")) {
            if (!params.containsKey(required)) {
                return usage("missing parameter " + required);
            }
        }
        JobParameters jp = new JobParametersBuilder()
            .addString("businessDate", date.toString())
            .addString("dailyFile", Path.of(params.get("dailyFile")).toAbsolutePath().normalize().toString())
            .addString("rejectsFile", Path.of(params.get("rejectsFile")).toAbsolutePath().normalize().toString(), false)
            .toJobParameters();
        JobExecution execution;
        try {
            execution = launcher.run(posttran, jp);
        } catch (JobInstanceAlreadyCompleteException | JobExecutionAlreadyRunningException e) {
            log.display("POSTING ALREADY COMPLETED FOR " + date);
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

    private int reset() {
        flyway.clean();
        flyway.migrate();
        System.out.println("SCHEMA RESET");
        return ExitCodes.OK;
    }
}
