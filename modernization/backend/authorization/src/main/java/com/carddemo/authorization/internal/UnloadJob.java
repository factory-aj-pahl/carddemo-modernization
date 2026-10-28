package com.carddemo.authorization.internal;

import static com.carddemo.authorization.internal.LegacyDisplay.status;

import com.carddemo.authorization.api.AuthorizationJobResult;
import com.carddemo.common.batch.JobLog;
import com.carddemo.common.format.Pic;
import com.carddemo.migration.PendingAuthSegments;
import com.carddemo.migration.PendingAuthSegments.Detail;
import com.carddemo.migration.PendingAuthSegments.Summary;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PAUDBUNL and DBUNLDGS: every summary in account order to a 100-byte file and every detail, newest first, to a
 * 206-byte file as the 6-byte packed parent key plus the 200-byte segment (DBUNLDGS writes the key too,
 * Q-DBUNLDGS-01). The tables are read in one serializable read-only transaction, so the files are one consistent
 * snapshot (Q-PAUDBUNL-09). A failed run deletes both files (Q-PAUDBUNL-06, Q-DBUNLDGS-05).
 */
@Component
class UnloadJob {

    enum Program {
        PAUDBUNL("STARTING PROGRAM PAUDBUNL::", "IMSUNLOD ABENDING ..."),
        DBUNLDGS("STARTING PROGRAM DBUNLDGS::", "DBUNLDGS ABENDING ...");

        final String start;
        final String abend;

        Program(String start, String abend) {
            this.start = start;
            this.abend = abend;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(UnloadJob.class);
    private static final int PAGE = 500;

    private static final class Abend extends RuntimeException {
        Abend(Throwable cause) {
            super(cause);
        }
    }

    private final PendingAuthRepository repo;
    private final AuthBatchFiles files;
    private final JobLog log;
    private final PlatformTransactionManager tm;
    private final Clock clock;

    UnloadJob(PendingAuthRepository repo, AuthBatchFiles files, JobLog log, PlatformTransactionManager tm, Clock clock) {
        this.repo = repo;
        this.files = files;
        this.log = log;
        this.tm = tm;
        this.clock = clock;
    }

    AuthorizationJobResult run(Program program, Path rootFile, Path childFile) {
        log.display(program.start);
        log.display(LegacyDisplay.DASHES);
        log.display("TODAYS DATE            :" + LegacyDisplay.yymmdd(LocalDate.now(clock)));
        log.display(" ");
        FileChannel roots = null;
        FileChannel children = null;
        int[] counts = new int[2];
        try {
            roots = open(program, rootFile, "ERROR IN OPENING OPFILE1:");
            children = open(program, childFile, "ERROR IN OPENING OPFILE2:");
            FileChannel r = roots;
            FileChannel c = children;
            TransactionTemplate snapshot = new TransactionTemplate(tm);
            snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
            snapshot.setReadOnly(true);
            snapshot.setName(program.name());
            snapshot.executeWithoutResult(s -> unloadAll(program, r, c, counts));
        } catch (Abend e) {
            LOG.error("{} failed", program, e.getCause());
            closeQuietly(roots);
            closeQuietly(children);
            deleteQuietly(rootFile);
            deleteQuietly(childFile);
            log.display(program.abend);
            return AuthorizationJobResult.rc16();
        }
        log.display("CLOSING THE FILE");
        close(program, roots, "ERROR IN CLOSING 1ST FILE:");
        close(program, children, "ERROR IN CLOSING 2ND FILE:");
        // Q-PAUDBUNL-01, Q-DBUNLDGS-03: NEW end-of-run counts.
        log.display("ROOT SEGMENTS UNLOADED  :" + String.format("%09d", counts[0]));
        log.display("CHILD SEGMENTS UNLOADED :" + String.format("%09d", counts[1]));
        return AuthorizationJobResult.completed();
    }

    private void unloadAll(Program program, FileChannel roots, FileChannel children, int[] counts) {
        long after = -1;
        while (true) {
            Optional<Summary> next;
            try {
                next = repo.nextSummary(after);
            } catch (DataAccessException e) {
                log.display("AUTH SUM  GN FAILED  :" + status(e));
                log.display("KEY FEEDBACK AREA    :" + (after < 0 ? "" : Pic.accountId(after)));
                throw new Abend(e);
            }
            if (next.isEmpty()) {
                return;
            }
            Summary s = next.get();
            after = s.accountId();
            write(program, roots, PendingAuthSegments.encodeSummary(s));
            counts[0]++;
            byte[] parent = PendingAuthSegments.rootKey(s.accountId());
            LocalDateTime lastTs = null;
            Long lastId = null;
            while (true) {
                List<Detail> page;
                try {
                    page = repo.detailsFrom(s.accountId(), lastTs, lastId, false, PAGE);
                } catch (DataAccessException e) {
                    log.display("GNP CALL FAILED  :" + status(e));
                    log.display("KFB AREA IN CHILD:" + Pic.accountId(s.accountId()));
                    throw new Abend(e);
                }
                for (Detail d : page) {
                    byte[] rec = new byte[PendingAuthSegments.ROOT_KEY_LENGTH + PendingAuthSegments.DETAIL_LENGTH];
                    System.arraycopy(parent, 0, rec, 0, parent.length);
                    System.arraycopy(PendingAuthSegments.encodeDetail(d), 0, rec, parent.length, PendingAuthSegments.DETAIL_LENGTH);
                    write(program, children, rec);
                    counts[1]++;
                    lastTs = d.authTs();
                    lastId = d.authId();
                }
                if (page.size() < PAGE) {
                    break;
                }
            }
            log.display("CHILD SEG FLAG GE : Y");
        }
    }

    private FileChannel open(Program program, Path path, String message) {
        try {
            return files.create(path);
        } catch (IOException e) {
            // DBUNLDGS has no OPEN of its own: IMS reports the GSAM dataset problem on the first ISRT.
            log.display(program == Program.PAUDBUNL ? message + AuthBatchFiles.openStatus(e)
                : "GSAM PARENT FAIL :" + AuthBatchFiles.openStatus(e));
            throw new Abend(e);
        }
    }

    /** Q-PAUDBUNL-04: a write error ends the run with RC 16. */
    private void write(Program program, FileChannel ch, byte[] rec) {
        try {
            ByteBuffer b = ByteBuffer.wrap(rec);
            while (b.hasRemaining()) {
                ch.write(b);
            }
        } catch (IOException e) {
            if (program == Program.DBUNLDGS) {
                // ERR-DBUNLDGS-03/-04; the KFB line has no counterpart (Q-DBUNLDGS-08).
                log.display("GSAM PARENT FAIL :" + status(e));
            }
            throw new Abend(e);
        }
    }

    private void close(Program program, FileChannel ch, String message) {
        try {
            ch.close();
        } catch (IOException e) {
            if (program == Program.PAUDBUNL) {
                log.display(message + "30");
            }
        }
    }

    private static void closeQuietly(FileChannel ch) {
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException ignored) {
                // the file is deleted next
            }
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            LOG.warn("could not delete partial output {}", p, e);
        }
    }
}
