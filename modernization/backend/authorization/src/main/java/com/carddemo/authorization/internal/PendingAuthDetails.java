package com.carddemo.authorization.internal;

import com.carddemo.migration.PendingAuthSegments.Detail;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/**
 * COPAUS1C reads and the PF5 fraud toggle. Each call is one transaction named {@value #TRANSACTION_NAME} (the PSB
 * schedule); a failure to start it is the "scheduling PSB" error.
 */
@Service
public class PendingAuthDetails {

    public static final String TRANSACTION_NAME = "COPAUS1C";
    public static final String PSB_FAILED = " System error while scheduling PSB: Code:";
    public static final String SUMMARY_FAILED = " System error while reading Auth Summary: Code:";
    public static final String DETAIL_FAILED = " System error while reading Auth Details: Code:";
    public static final String NEXT_FAILED = " System error while reading next Auth: Code:";
    public static final String TAGGING_FAILED = " System error while FRAUD Tagging, ROLLBACK||";
    public static final String MARKED = "AUTH MARKED FRAUD...";
    public static final String REMOVED = "AUTH FRAUD REMOVED...";

    /**
     * @param detail   the detail read (null when not found or on error)
     * @param next     PF8: the next older detail, null at the end
     * @param message  the error message, or the PF5 result message
     * @param error    a read failed (the COBOL error flag): nothing is shown
     * @param notFound the summary or the detail does not exist (Q-COPAUS1C-02)
     */
    public record Read(Detail detail, Detail next, String message, boolean error, boolean notFound) {
        static Read failed(String message) {
            return new Read(null, null, message, true, false);
        }

        static Read missing() {
            return new Read(null, null, null, false, true);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(PendingAuthDetails.class);

    private final PendingAuthRepository repo;
    private final FraudReports fraud;
    private final PlatformTransactionManager tm;
    private final Clock clock;

    PendingAuthDetails(PendingAuthRepository repo, FraudReports fraud, PlatformTransactionManager tm, Clock clock) {
        this.repo = repo;
        this.fraud = fraud;
        this.tm = tm;
        this.clock = clock;
    }

    /** READ-AUTH-RECORD: GU the summary, then the detail with the key. */
    public Read current(long accountId, long authId) {
        return inUnit(true, () -> find(accountId, authId, false));
    }

    /** PROCESS-PF8-KEY: the current detail and the next older one under the same summary. */
    public Read withNext(long accountId, long authId) {
        return inUnit(true, () -> {
            Read cur = find(accountId, authId, false);
            if (cur.detail() == null) {
                return cur;
            }
            Detail d = cur.detail();
            try {
                return new Read(d, repo.nextOlder(accountId, d.authTs(), d.authId()).orElse(null), null, false, false);
            } catch (DataAccessException e) {
                return Read.failed(NEXT_FAILED + LegacyDisplay.status(e));
            }
        });
    }

    /**
     * MARK-AUTH-FRAUD and UPDATE-AUTH-DETAILS in one unit of work: toggle F to R, anything else to F (BR-COPAUS1C-05);
     * record it through COPAUS2C first, then update the detail, and commit both or neither (BR-COPAUS1C-06).
     * The returned detail is null; callers re-read what is stored (Q-COPAUS1C-01).
     */
    public Read toggleFraud(long accountId, long customerId, long authId) {
        LocalDate today = LocalDate.now(clock);
        TransactionStatus tx;
        try {
            tx = tm.getTransaction(definition(false));
        } catch (TransactionException e) {
            return Read.failed(PSB_FAILED + LegacyDisplay.status(e));
        }
        boolean done = false;
        try {
            Read cur = find(accountId, authId, true);
            if (cur.detail() == null) {
                return cur;
            }
            Detail d = cur.detail();
            String action = "F".equals(d.authFraud()) ? "R" : "F";
            FraudReports.Result r;
            try {
                r = fraud.record(accountId, customerId, d, action, today);
            } catch (RuntimeException e) {
                return Read.failed(FraudReports.systemError(e));
            }
            if (!r.success()) {
                return Read.failed(r.message());
            }
            LOG.debug("RPT DT: {}", String.format("%02d/%02d/%02d", today.getMonthValue(), today.getDayOfMonth(),
                today.getYear() % 100));
            String status;
            try {
                status = repo.updateFraud(d.authId(), action, today) ? null : "GE";
            } catch (DataAccessException e) {
                status = LegacyDisplay.status(e);
            }
            if (status != null) {
                return Read.failed(TAGGING_FAILED + status);
            }
            done = true;
            try {
                tm.commit(tx);
            } catch (TransactionException e) {
                return Read.failed(TAGGING_FAILED + LegacyDisplay.status(e));
            }
            return new Read(null, null, "R".equals(action) ? REMOVED : MARKED, false, false);
        } finally {
            if (!done && !tx.isCompleted()) {
                tm.rollback(tx);
            }
        }
    }

    private Read find(long accountId, long authId, boolean lock) {
        try {
            if (repo.summary(accountId).isEmpty()) {
                return Read.missing();
            }
        } catch (DataAccessException e) {
            return Read.failed(SUMMARY_FAILED + LegacyDisplay.status(e));
        }
        try {
            Optional<Detail> d = lock ? repo.lockDetail(accountId, authId) : repo.detail(accountId, authId);
            return d.map(x -> new Read(x, null, null, false, false)).orElseGet(Read::missing);
        } catch (DataAccessException e) {
            return Read.failed(DETAIL_FAILED + LegacyDisplay.status(e));
        }
    }

    private Read inUnit(boolean readOnly, java.util.function.Supplier<Read> work) {
        TransactionStatus tx;
        try {
            tx = tm.getTransaction(definition(readOnly));
        } catch (TransactionException e) {
            return Read.failed(PSB_FAILED + LegacyDisplay.status(e));
        }
        try {
            return work.get();
        } finally {
            // Read-only: the SYNCPOINT before the send only releases the PSB.
            tm.rollback(tx);
        }
    }

    private static DefaultTransactionDefinition definition(boolean readOnly) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        def.setName(TRANSACTION_NAME);
        def.setReadOnly(readOnly);
        return def;
    }
}
