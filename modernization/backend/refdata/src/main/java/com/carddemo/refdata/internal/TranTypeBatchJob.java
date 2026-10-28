package com.carddemo.refdata.internal;

import com.carddemo.common.batch.JobLog;
import com.carddemo.refdata.api.BatchUpdateResult;
import com.carddemo.refdata.api.TranTypeBatchUpdate;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * COBTUPDT. Records are 53 bytes (action, type code, description); 80-byte card images are accepted and columns
 * 54-80 ignored (Q-COBTUPDT-08); shorter lines are padded with spaces. Every record is validated with the
 * COTRTUPC rules (Q-COBTUPDT-05). Any SQL error stops the run and rolls back the whole file (Q-COBTUPDT-04).
 */
@Service
class TranTypeBatchJob implements TranTypeBatchUpdate {

    static final int RECORD = 53;
    static final int CARD = 80;
    static final String TYPE_NOT_VALID = "ERROR: TYPE NOT VALID";
    static final String NO_RECORDS = "No records found.";
    static final String SQL_ERROR = "Error accessing: TRANSACTION_TYPE table. SQLCODE:";
    /** NEW: COBTUPDT has no read error message (its loop never ends, Q-COBTUPDT-01). */
    static final String READ_NOT_OK = "READ FILE NOT OK";
    /** NEW: a SQL warning, followed by the type code and the warning text (Q-COBTUPDT-07). */
    static final String SQL_WARNING = "SQL WARNING FOR TYPE ";
    /** NEW: end-of-run summary (Q-COBTUPDT-07). */
    static final String STOPPED = "RUN STOPPED: ALL CHANGES ROLLED BACK, NOTHING APPLIED";

    private final TransactionTypeStore store;
    private final JobLog log;
    private final TransactionTemplate tx;

    TranTypeBatchJob(TransactionTypeStore store, JobLog log, PlatformTransactionManager tm) {
        this.store = store;
        this.log = log;
        this.tx = new TransactionTemplate(tm);
    }

    private static final class Counts {
        int read;
        int added;
        int updated;
        int deleted;
        int rejected;
        boolean stopped;
    }

    @Override
    public BatchUpdateResult apply(Path inputFile) {
        Counts c = new Counts();
        BufferedReader in;
        try {
            in = Files.newBufferedReader(inputFile, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            log.display("OPEN FILE NOT OK");
            c.stopped = true;
            return summary(c);
        }
        log.display("OPEN FILE OK");
        try (in) {
            tx.executeWithoutResult(status -> run(in, c, status));
        } catch (IOException e) {
            // close failure after the commit: the changes stand
        }
        return summary(c);
    }

    private void run(BufferedReader in, Counts c, TransactionStatus status) {
        while (true) {
            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                line = null;
                c.stopped = true;
            }
            if (line != null && line.length() > CARD) {
                c.stopped = true;
            }
            if (c.stopped) {
                log.display(READ_NOT_OK);
                status.setRollbackOnly();
                return;
            }
            if (line == null) {
                return;
            }
            c.read++;
            String rec = line.length() >= RECORD ? line.substring(0, RECORD) : line + " ".repeat(RECORD - line.length());
            log.display("PROCESSING   " + rec);
            if (!treat(rec, c)) {
                c.stopped = true;
                status.setRollbackOnly();
                return;
            }
        }
    }

    /** 1003-TREAT-RECORD; false when a SQL error stops the run. */
    private boolean treat(String rec, Counts c) {
        char action = rec.charAt(0);
        String type = rec.substring(1, 3);
        String desc = rec.substring(3);
        switch (action) {
            case 'A' -> log.display("ADDING RECORD");
            case 'U' -> log.display("UPDATING RECORD");
            case 'D' -> log.display("DELETING RECORD");
            case '*' -> {
                log.display("IGNORING COMMENTED LINE");
                return true;
            }
            default -> {
                reject(c, TYPE_NOT_VALID);
                return true;
            }
        }
        boolean typeOk = type.matches("[0-9]{2}") && !type.equals("00");
        if (!typeOk || action != 'D' && TypeEdits.description(desc) != TypeEdits.Check.OK) {
            reject(c, TYPE_NOT_VALID);
            return true;
        }
        Consumer<String> warnings = text -> log.display(SQL_WARNING + type + ": " + text);
        try {
            switch (action) {
                case 'A' -> {
                    store.batchInsert(type, desc, warnings);
                    c.added++;
                    log.display("RECORD INSERTED SUCCESSFULLY");
                }
                case 'U' -> {
                    if (store.batchUpdate(type, desc, warnings) == TransactionTypeStore.Change.DONE) {
                        c.updated++;
                        log.display("RECORD UPDATED SUCCESSFULLY");
                    } else {
                        reject(c, NO_RECORDS);
                    }
                }
                default -> {
                    if (store.batchDelete(type, warnings) == TransactionTypeStore.Change.DONE) {
                        c.deleted++;
                        log.display("RECORD DELETED SUCCESSFULLY");
                    } else {
                        reject(c, NO_RECORDS);
                    }
                }
            }
            return true;
        } catch (Db2Failure e) {
            log.display(SQL_ERROR + Db2Codes.pic(e.error().sqlcode()));
            return false;
        }
    }

    /** 9999-ABEND: the message (built fresh, Q-COBTUPDT-02) and return code 4. */
    private void reject(Counts c, String message) {
        c.rejected++;
        log.display(message);
    }

    private BatchUpdateResult summary(Counts c) {
        if (c.stopped) {
            c.added = 0;
            c.updated = 0;
            c.deleted = 0;
            log.display(STOPPED);
        }
        log.display("RECORDS READ     : " + c.read);
        log.display("RECORDS ADDED    : " + c.added);
        log.display("RECORDS UPDATED  : " + c.updated);
        log.display("RECORDS DELETED  : " + c.deleted);
        log.display("RECORDS REJECTED : " + c.rejected);
        return new BatchUpdateResult(c.read, c.added, c.updated, c.deleted, c.rejected, c.stopped);
    }
}
