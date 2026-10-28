package com.carddemo.batch.posting;

import com.carddemo.common.batch.BatchAbend;
import com.carddemo.common.batch.JobLog;
import com.carddemo.common.codec.FixedWidth;
import com.carddemo.cycle.api.DailyPosting;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.List;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

/**
 * The CBTRN02C main loop as a chunk step: reads DALYTRAN, posts or rejects each record through
 * {@link DailyPosting}, and writes SYSOUT and the DALYREJS file. Restart state (records read, processed and
 * rejected counts) is saved in the step execution context with every commit, so a restart after an abend
 * continues with the first record that was not committed (Q-CBTRN02C-07). One job runs per process.
 */
@Component
class PosttranStep implements ItemStreamReader<String>, ItemWriter<String>, StepExecutionListener {

    private static final String LINES = "posttran.lines";
    private static final String PROCESSED = "posttran.processed";
    private static final String REJECTED = "posttran.rejected";

    private final DailyPosting posting;
    private final JobLog log;

    private Path dailyFile;
    private Path rejectsFile;
    private LocalDate businessDate;
    private long runId;
    private BufferedReader reader;
    private long lines;
    private long processed;
    private long rejected;

    PosttranStep(DailyPosting posting, JobLog log) {
        this.posting = posting;
        this.log = log;
    }

    @Override
    public void beforeStep(StepExecution se) {
        var p = se.getJobParameters();
        dailyFile = Path.of(p.getString("dailyFile"));
        rejectsFile = Path.of(p.getString("rejectsFile"));
        businessDate = LocalDate.parse(p.getString("businessDate"));
        runId = se.getJobExecution().getJobInstance().getInstanceId();
        reader = null;
        log.display("START OF EXECUTION OF PROGRAM CBTRN02C");
    }

    @Override
    public void open(ExecutionContext ctx) {
        lines = ctx.getLong(LINES, 0);
        processed = ctx.getLong(PROCESSED, 0);
        rejected = ctx.getLong(REJECTED, 0);
        try {
            reader = Files.newBufferedReader(dailyFile, StandardCharsets.ISO_8859_1);
            for (long i = 0; i < lines; i++) {
                reader.readLine();
            }
        } catch (NoSuchFileException e) {
            throw new BatchAbend("ERROR OPENING DALYTRAN", "35", e);
        } catch (IOException e) {
            throw new BatchAbend("ERROR OPENING DALYTRAN", null, e);
        }
        try {
            Files.deleteIfExists(rejectsFile);
            Files.createFile(rejectsFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (IOException e) {
            throw new BatchAbend("ERROR OPENING DALY REJECTS FILE", null, e);
        }
    }

    @Override
    public String read() {
        try {
            String line;
            do {
                line = reader.readLine();
                if (line == null) {
                    return null;
                }
                lines++;
            } while (line.isEmpty() || line.equals("\r"));
            return line;
        } catch (IOException e) {
            throw new BatchAbend("ERROR READING DALYTRAN FILE", null, e);
        }
    }

    @Override
    public void write(Chunk<? extends String> chunk) {
        for (String line : chunk) {
            processed++;
            if (!posting.post(line, runId, businessDate).posted()) {
                rejected++;
            }
        }
    }

    @Override
    public void update(ExecutionContext ctx) {
        ctx.putLong(LINES, lines);
        ctx.putLong(PROCESSED, processed);
        ctx.putLong(REJECTED, rejected);
    }

    @Override
    public void close() {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                throw new ItemStreamException(e);
            }
            reader = null;
        }
    }

    @Override
    public ExitStatus afterStep(StepExecution se) {
        if (se.getStatus() != BatchStatus.COMPLETED) {
            for (Throwable t : se.getFailureExceptions()) {
                BatchAbend abend = abendOf(t);
                if (abend != null) {
                    abend.sysoutLines().forEach(log::display);
                }
            }
            log.display("ABENDING PROGRAM");
            deleteRejectsFile();
            return ExitStatus.FAILED;
        }
        List<String> records = posting.rejectRecords(runId);
        String failure;
        try {
            failure = writeRejects(Files.newBufferedWriter(rejectsFile, StandardCharsets.ISO_8859_1,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE), records);
        } catch (IOException e) {
            System.err.println(rejectsFile + ": " + e);
            failure = "ERROR WRITING TO REJECTS FILE";
        }
        if (failure != null) {
            log.display(failure);
            log.display("ABENDING PROGRAM");
            deleteRejectsFile();
            return ExitStatus.FAILED;
        }
        log.display("TRANSACTIONS PROCESSED :" + FixedWidth.n(processed, 9));
        log.display("TRANSACTIONS REJECTED  :" + FixedWidth.n(rejected, 9));
        log.display("END OF EXECUTION OF PROGRAM CBTRN02C");
        return rejected > 0 ? new ExitStatus(PosttranJobConfig.COMPLETED_WITH_REJECTS) : ExitStatus.COMPLETED;
    }

    /**
     * 2500-WRITE-REJECT-REC and 9300-DALYREJS-CLOSE for the committed rejects: null when the file is complete,
     * otherwise the COBOL message of the failure (shown without another file's status, Q-CBTRN02C-05).
     */
    static String writeRejects(Writer w, List<String> records) {
        try {
            for (String rec : records) {
                w.write(rec);
                w.write('\n');
            }
            w.flush();
        } catch (IOException e) {
            System.err.println("rejects write failed: " + e);
            try {
                w.close();
            } catch (IOException ignored) {
                // the write failure is the one reported
            }
            return "ERROR WRITING TO REJECTS FILE";
        }
        try {
            w.close();
        } catch (IOException e) {
            System.err.println("rejects close failed: " + e);
            return "ERROR CLOSING DAILY REJECTS FILE";
        }
        return null;
    }

    private void deleteRejectsFile() {
        try {
            Files.deleteIfExists(rejectsFile);
        } catch (IOException e) {
            System.err.println("could not delete " + rejectsFile + ": " + e);
        }
    }

    private static BatchAbend abendOf(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof BatchAbend a) {
                return a;
            }
        }
        return null;
    }
}
