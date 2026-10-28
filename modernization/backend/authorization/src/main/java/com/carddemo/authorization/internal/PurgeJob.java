package com.carddemo.authorization.internal;

import static com.carddemo.authorization.internal.LegacyDisplay.count;
import static com.carddemo.authorization.internal.LegacyDisplay.signedAccount;
import static com.carddemo.authorization.internal.LegacyDisplay.status;

import com.carddemo.authorization.api.AuthorizationJobResult;
import com.carddemo.common.batch.JobLog;
import com.carddemo.common.format.Pic;
import com.carddemo.migration.PendingAuthSegments.Detail;
import com.carddemo.migration.PendingAuthSegments.Summary;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/**
 * CBPAUP0C: walk every summary in account order and every detail newest first, delete details whose age in whole
 * days (insert date to today, Q-CBPAUP0C-03) is at least the expiry days, save the kept summary with the count and
 * amount adjustments (Q-CBPAUP0C-01), and delete a summary only when its approved count is 0 or less and no detail
 * remains (Q-ENT-42). One transaction per checkpoint interval of N summaries (Q-CBPAUP0C-04); on an error the open
 * interval rolls back, earlier intervals stay (Q-CBPAUP0C-08), and the job ends with RC 16 without totals.
 */
@Component
class PurgeJob {

    /** DEBUG lines of the debug flag (Q-CBPAUP0J-02). */
    private static final Logger DEBUG = LoggerFactory.getLogger(PurgeJob.class);
    static final String TRANSACTION_NAME = "CBPAUP0C";
    private static final int PAGE = 200;

    /** PRM-INFO: the first 17 characters of the SYSIN card. */
    record Parm(String card, int expiryDays, int checkpointFrequency, int displayFrequency, boolean debug) {

        static final int LENGTH = 17;

        static Parm parse(String sysin) {
            String raw = sysin == null ? "" : sysin;
            String card = raw.length() >= LENGTH ? raw.substring(0, LENGTH) : raw + " ".repeat(LENGTH - raw.length());
            String expiry = card.substring(0, 2);
            // BR-CBPAUP0C-01: a non-numeric expiry falls back to 5.
            int days = expiry.chars().allMatch(c -> c >= '0' && c <= '9') ? Integer.parseInt(expiry) : 5;
            return new Parm(card, days, frequency(card.substring(3, 8), 5), frequency(card.substring(9, 14), 10),
                card.charAt(15) == 'Y');
        }

        /**
         * BR-CBPAUP0C-02 defaults for blank, zero or low-values; Q-CBPAUP0C-04 compares the value numerically, so text
         * that is not a number also takes the default.
         */
        private static int frequency(String field, int dflt) {
            String v = field.strip();
            if (v.isEmpty() || field.chars().allMatch(c -> c == 0) || !v.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return dflt;
            }
            int n = Integer.parseInt(v);
            return n == 0 ? dflt : n;
        }
    }

    /** Ends the run through 9999-ABEND after the error lines are displayed. */
    private static final class Abend extends RuntimeException {
        Abend(Throwable cause) {
            super(cause);
        }
    }

    private final PendingAuthRepository repo;
    private final JobLog log;
    private final PlatformTransactionManager tm;
    private final Clock clock;

    PurgeJob(PendingAuthRepository repo, JobLog log, PlatformTransactionManager tm, Clock clock) {
        this.repo = repo;
        this.log = log;
        this.tm = tm;
        this.clock = clock;
    }

    AuthorizationJobResult run(String sysin) {
        LocalDate today = LocalDate.now(clock);
        Parm p = Parm.parse(sysin);
        log.display("STARTING PROGRAM CBPAUP0C::");
        log.display(LegacyDisplay.DASHES);
        log.display("CBPAUP0C PARM RECEIVED :" + p.card());
        log.display("TODAYS DATE            :" + LegacyDisplay.yyddd(today));
        log.display(" ");
        return new Run(p, today).execute();
    }

    private final class Run {
        private final Parm p;
        private final LocalDate today;
        private int summariesRead;
        private int summariesDeleted;
        private int detailsRead;
        private int detailsDeleted;
        private int processed;
        private int checkpoints;
        private long currentAccount;
        private TransactionStatus tx;

        Run(Parm p, LocalDate today) {
            this.p = p;
            this.today = today;
        }

        AuthorizationJobResult execute() {
            try {
                tx = begin();
                long after = -1;
                while (true) {
                    debug("DEBUG: AUTH SMRY READ : " + count(summariesRead));
                    Optional<Summary> next;
                    try {
                        next = repo.nextSummary(after);
                    } catch (DataAccessException e) {
                        log.display("AUTH SUMMARY READ FAILED  :" + status(e));
                        log.display("SUMMARY READ BEFORE ABEND :" + count(summariesRead));
                        throw new Abend(e);
                    }
                    if (next.isEmpty()) {
                        break;
                    }
                    Summary s = next.get();
                    summariesRead++;
                    processed++;
                    currentAccount = s.accountId();
                    after = s.accountId();
                    purgeAccount(s);
                    if (processed >= p.checkpointFrequency()) {
                        checkpoint(false);
                        processed = 0;
                    }
                }
                checkpoint(true);
            } catch (Abend e) {
                if (tx != null && !tx.isCompleted()) {
                    try {
                        tm.rollback(tx);
                    } catch (TransactionException ignored) {
                        // the failure already reported is the one that matters
                    }
                }
                LoggerFactory.getLogger(PurgeJob.class).error("CBPAUP0C failed", e.getCause());
                log.display("CBPAUP0C ABENDING ...");
                return AuthorizationJobResult.rc16();
            }
            log.display(" ");
            log.display(LegacyDisplay.DASHES);
            log.display("# TOTAL SUMMARY READ  :" + count(summariesRead));
            log.display("# SUMMARY REC DELETED :" + count(summariesDeleted));
            log.display("# TOTAL DETAILS READ  :" + count(detailsRead));
            log.display("# DETAILS REC DELETED :" + count(detailsDeleted));
            log.display(LegacyDisplay.DASHES);
            log.display(" ");
            return AuthorizationJobResult.completed();
        }

        private void purgeAccount(Summary s) {
            long acct = s.accountId();
            int approvedCount = s.approvedCount();
            BigDecimal approvedAmt = s.approvedAmt();
            int declinedCount = s.declinedCount();
            BigDecimal declinedAmt = s.declinedAmt();
            boolean purged = false;
            int remaining = 0;
            LocalDateTime lastTs = null;
            Long lastId = null;
            while (true) {
                List<Detail> page;
                try {
                    page = repo.detailsFrom(acct, lastTs, lastId, false, PAGE);
                } catch (DataAccessException e) {
                    log.display("AUTH DETAIL READ FAILED  :" + status(e));
                    log.display("SUMMARY AUTH APP ID      :" + signedAccount(acct));
                    log.display("DETAIL READ BEFORE ABEND :" + count(detailsRead));
                    throw new Abend(e);
                }
                for (Detail d : page) {
                    debug("DEBUG: AUTH DTL READ : " + count(detailsRead));
                    detailsRead++;
                    lastTs = d.authTs();
                    lastId = d.authId();
                    if (ChronoUnit.DAYS.between(d.authTs().toLocalDate(), today) < p.expiryDays()) {
                        remaining++;
                        continue;
                    }
                    if ("00".equals(d.respCode())) {
                        approvedCount--;
                        approvedAmt = approvedAmt.subtract(d.approvedAmt());
                    } else {
                        declinedCount--;
                        declinedAmt = declinedAmt.subtract(d.transactionAmt());
                    }
                    debug("DEBUG: AUTH DTL DLET : " + signedAccount(acct));
                    try {
                        if (!repo.deleteDetail(d.authId())) {
                            throw new EmptyResultDataAccessException("detail " + d.authId() + " vanished", 1);
                        }
                    } catch (DataAccessException e) {
                        log.display("AUTH DETAIL DELETE FAILED :" + status(e));
                        log.display("AUTH APP ID               :" + signedAccount(acct));
                        throw new Abend(e);
                    }
                    detailsDeleted++;
                    purged = true;
                }
                if (page.size() < PAGE) {
                    break;
                }
            }
            debug("DEBUG: AUTH DTL READ : " + count(detailsRead));
            if (approvedCount <= 0 && remaining == 0) {
                debug("DEBUG: AUTH SMRY DLET : " + signedAccount(acct));
                try {
                    if (!repo.deleteSummary(acct)) {
                        throw new EmptyResultDataAccessException("summary " + acct + " vanished", 1);
                    }
                } catch (DataAccessException e) {
                    log.display("AUTH SUMMARY DELETE FAILED :" + status(e));
                    log.display("AUTH APP ID                :" + signedAccount(acct));
                    throw new Abend(e);
                }
                summariesDeleted++;
            } else if (purged) {
                Summary adjusted = new Summary(acct, s.customerId(), s.authStatus(), s.accountStatus(), s.creditLimit(),
                    s.cashLimit(), s.creditBalance(), s.cashBalance(), approvedCount, declinedCount, approvedAmt, declinedAmt,
                    s.legacyStatus(), s.legacyFiller(), s.legacyRba());
                try {
                    if (!repo.updateSummary(adjusted)) {
                        throw new EmptyResultDataAccessException("summary " + acct + " vanished", 1);
                    }
                } catch (DataAccessException e) {
                    // The program never replaces the summary, so it has no message for this; the job log shows the abend.
                    throw new Abend(e);
                }
            }
        }

        /** 9000-TAKE-CHECKPOINT: commit the interval and start the next one. */
        private void checkpoint(boolean last) {
            try {
                tm.commit(tx);
            } catch (TransactionException e) {
                log.display("CHKP FAILED: DIBSTAT - " + status(e) + ", REC COUNT - " + count(summariesRead) + ", APP ID - "
                    + Pic.accountId(currentAccount));
                throw new Abend(e);
            }
            checkpoints++;
            if (checkpoints >= p.displayFrequency()) {
                checkpoints = 0;
                log.display("CHKP SUCCESS: AUTH COUNT - " + count(summariesRead) + ", APP ID - " + Pic.accountId(currentAccount));
            }
            tx = last ? null : begin();
        }

        private TransactionStatus begin() {
            DefaultTransactionDefinition def = new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            def.setName(TRANSACTION_NAME);
            try {
                return tm.getTransaction(def);
            } catch (TransactionException e) {
                throw new Abend(e);
            }
        }

        private void debug(String line) {
            if (p.debug()) {
                DEBUG.debug(line);
            }
        }
    }
}
