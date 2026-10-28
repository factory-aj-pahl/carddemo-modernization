package com.carddemo.authorization.internal;

import static com.carddemo.authorization.internal.LegacyDisplay.status;

import com.carddemo.authorization.api.AuthorizationJobResult;
import com.carddemo.common.batch.JobLog;
import com.carddemo.common.codec.Packed;
import com.carddemo.common.format.Pic;
import com.carddemo.migration.PendingAuthSegments;
import com.carddemo.migration.PendingAuthSegments.Detail;
import com.carddemo.migration.PendingAuthSegments.Summary;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PAUDBLOD: insert the summaries of the root file, then the details of the child file under their parents.
 * Existing segments are reported and skipped (Q-PAUDBLOD-05). Each file is one transaction with row locks, so a
 * failure loads nothing of that file (B-UOW, Q-PAUDBLOD-04); a read error ends with RC 16 (Q-PAUDBLOD-03). Orphan
 * children get {@code ROOT GU CALL FAIL:} (Q-PAUDBLOD-02), invalid root keys {@code INVALID ROOT KEY}
 * (Q-PAUDBLOD-07), and the run ends with the counts of Q-PAUDBLOD-09.
 */
@Component
class LoadJob {

    private static final Logger LOG = LoggerFactory.getLogger(LoadJob.class);
    static final int CHILD_LENGTH = PendingAuthSegments.ROOT_KEY_LENGTH + PendingAuthSegments.DETAIL_LENGTH;

    private static final class Abend extends RuntimeException {
        Abend(Throwable cause) {
            super(cause);
        }
    }

    /** End-of-run counts. */
    private static final class Counts {
        int roots;
        int children;
        int duplicates;
        int orphans;
        int rejects;
    }

    private final PendingAuthRepository repo;
    private final AuthBatchFiles files;
    private final JobLog log;
    private final PlatformTransactionManager tm;
    private final Clock clock;

    LoadJob(PendingAuthRepository repo, AuthBatchFiles files, JobLog log, PlatformTransactionManager tm, Clock clock) {
        this.repo = repo;
        this.files = files;
        this.log = log;
        this.tm = tm;
        this.clock = clock;
    }

    AuthorizationJobResult run(Path rootFile, Path childFile) {
        log.display("STARTING PAUDBLOD");
        log.display(LegacyDisplay.DASHES);
        log.display("TODAYS DATE            :" + LegacyDisplay.yymmdd(LocalDate.now(clock)));
        log.display(" ");
        Counts n = new Counts();
        InputStream roots = null;
        InputStream children = null;
        try {
            roots = open(rootFile, "ERROR IN OPENING INFILE1:");
            children = open(childFile, "ERROR IN OPENING INFILE2:");
            InputStream r = roots;
            InputStream c = children;
            TransactionTemplate perFile = new TransactionTemplate(tm);
            perFile.setName("PAUDBLOD");
            perFile.executeWithoutResult(s -> loadRoots(r, n));
            perFile.executeWithoutResult(s -> loadChildren(c, n));
        } catch (Abend e) {
            LOG.error("PAUDBLOD failed", e.getCause());
            closeQuietly(roots);
            closeQuietly(children);
            log.display("IMS LOAD ABENDING ...");
            return AuthorizationJobResult.rc16();
        }
        log.display("CLOSING THE FILE");
        close(roots, "ERROR IN CLOSING 1ST FILE:");
        close(children, "ERROR IN CLOSING 2ND FILE:");
        log.display("ROOT SEGMENTS INSERTED  :" + String.format("%09d", n.roots));
        log.display("CHILD SEGMENTS INSERTED :" + String.format("%09d", n.children));
        log.display("DUPLICATES SKIPPED      :" + String.format("%09d", n.duplicates));
        log.display("ORPHAN CHILDREN SKIPPED :" + String.format("%09d", n.orphans));
        log.display("INVALID KEYS REJECTED   :" + String.format("%09d", n.rejects));
        return AuthorizationJobResult.completed();
    }

    private void loadRoots(InputStream in, Counts n) {
        while (true) {
            byte[] rec = read(in, PendingAuthSegments.SUMMARY_LENGTH, "ERROR READING ROOT SEG INFILE");
            if (rec == null) {
                return;
            }
            log.display(" *******************************");
            log.display(" *******************************");
            if (!PendingAuthSegments.validRootKey(rec, 0)) {
                log.display("INVALID ROOT KEY");
                n.rejects++;
                continue;
            }
            try {
                Summary s = PendingAuthSegments.decodeSummary(rec, 0, null);
                if (repo.lockSummary(s.accountId()).isPresent()) {
                    log.display("ROOT SEGMENT ALREADY IN DB");
                    n.duplicates++;
                    continue;
                }
                repo.insertSummary(s);
            } catch (DuplicateKeyException e) {
                log.display("ROOT SEGMENT ALREADY IN DB");
                n.duplicates++;
                continue;
            } catch (DataAccessException | IllegalArgumentException e) {
                log.display("ROOT INSERT FAILED  :" + statusOf(e));
                throw new Abend(e);
            }
            log.display("ROOT INSERT SUCCESS    ");
            n.roots++;
        }
    }

    private void loadChildren(InputStream in, Counts n) {
        while (true) {
            byte[] rec = read(in, CHILD_LENGTH, "ERROR READING CHILD SEG INFILE");
            if (rec == null) {
                return;
            }
            if (!PendingAuthSegments.validRootKey(rec, 0)) {
                // BR-PAUDBLOD-04: skipped without a message; counted as a reject (Q-PAUDBLOD-09).
                n.rejects++;
                continue;
            }
            long acct = Packed.decode(rec, 0, PendingAuthSegments.ROOT_KEY_LENGTH, 0).longValueExact();
            String kfb = Pic.accountId(acct) + HexFormat.of().formatHex(rec, PendingAuthSegments.ROOT_KEY_LENGTH,
                PendingAuthSegments.ROOT_KEY_LENGTH + 8).toUpperCase();
            log.display("***************************");
            log.display("***************************");
            boolean parent;
            try {
                parent = repo.lockSummary(acct).isPresent();
            } catch (DataAccessException e) {
                log.display("ROOT GU CALL FAIL:" + status(e));
                log.display("KFB AREA IN CHILD:" + kfb);
                throw new Abend(e);
            }
            if (!parent) {
                log.display("ROOT GU CALL FAIL:GE");
                n.orphans++;
                continue;
            }
            log.display("GU CALL TO ROOT SEG SUCCESS");
            try {
                Detail d = PendingAuthSegments.decodeDetail(rec, PendingAuthSegments.ROOT_KEY_LENGTH, acct, null);
                if (repo.lockDetailKey(acct, d.authTs())) {
                    log.display("CHILD SEGMENT ALREADY IN DB");
                    n.duplicates++;
                    continue;
                }
                repo.insertDetail(d);
            } catch (DataAccessException | IllegalArgumentException e) {
                log.display("INSERT CALL FAIL FOR CHILD:" + statusOf(e));
                log.display("KFB AREA IN CHILD:" + kfb);
                throw new Abend(e);
            }
            log.display("CHILD SEGMENT INSERTED SUCCESS");
            n.children++;
        }
    }

    /** One fixed-length record, or null at end of file; a short last record is a read error. */
    private byte[] read(InputStream in, int length, String message) {
        try {
            byte[] rec = in.readNBytes(length);
            if (rec.length == 0) {
                return null;
            }
            if (rec.length < length) {
                throw new IOException("record of " + rec.length + " bytes, expected " + length);
            }
            return rec;
        } catch (IOException e) {
            log.display(message);
            throw new Abend(e);
        }
    }

    /** A segment that cannot be stored as table columns is a failed insert with status DATA. */
    private static String statusOf(Exception e) {
        return e instanceof IllegalArgumentException ? "DATA" : status(e);
    }

    private InputStream open(Path path, String message) {
        try {
            return files.open(path);
        } catch (IOException e) {
            log.display(message + AuthBatchFiles.openStatus(e));
            throw new Abend(e);
        }
    }

    private void close(InputStream in, String message) {
        try {
            in.close();
        } catch (IOException e) {
            log.display(message + "30");
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // the run already failed
            }
        }
    }
}
