package com.carddemo.authorization.internal;

import com.carddemo.migration.PendingAuthSegments.Detail;
import com.carddemo.migration.PendingAuthSegments.Summary;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/**
 * COPAUS0C reads of the summary and one page of details, newest first. Each call is one read-only transaction named
 * {@value #TRANSACTION_NAME} (the PSB schedule, released before the screen is sent).
 */
@Service
public class PendingAuthBrowser {

    public static final String TRANSACTION_NAME = "COPAUS0C";
    public static final String PSB_FAILED = " System error while scheduling PSB: Code:";
    public static final String SUMMARY_FAILED = " System error while reading AUTH Summary: Code:";
    public static final String DETAILS_FAILED = " System error while reading AUTH Details: Code:";
    public static final String REPOS_FAILED = " System error while repos. AUTH Details: Code:";

    /**
     * @param summary the summary, or null when there is none or its read failed
     * @param rows    up to the page size of details
     * @param more    another detail follows the page
     * @param error   the error message (the COBOL error flag is on), or null
     */
    public record Page(Summary summary, List<Detail> rows, boolean more, String error) {
    }

    private final PendingAuthRepository repo;
    private final PlatformTransactionManager tm;

    PendingAuthBrowser(PendingAuthRepository repo, PlatformTransactionManager tm) {
        this.repo = repo;
        this.tm = tm;
    }

    /** GET-AUTH-SUMMARY, then PROCESS-PAGE-FORWARD from the first detail when the summary exists. */
    public Page first(long accountId, int size) {
        return read(accountId, null, 0, false, size, DETAILS_FAILED);
    }

    /** The page that starts at (inclusive) or after (exclusive) a saved key: PF7 and PF8. */
    public Page from(long accountId, LocalDateTime ts, long authId, boolean inclusive, int size) {
        return read(accountId, ts, authId, inclusive, size, REPOS_FAILED);
    }

    private Page read(long accountId, LocalDateTime ts, long authId, boolean inclusive, int size, String detailError) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        def.setName(TRANSACTION_NAME);
        def.setReadOnly(true);
        TransactionStatus tx;
        try {
            tx = tm.getTransaction(def);
        } catch (TransactionException e) {
            return new Page(null, List.of(), false, PSB_FAILED + LegacyDisplay.status(e));
        }
        try {
            Optional<Summary> s;
            try {
                s = repo.summary(accountId);
            } catch (DataAccessException e) {
                return new Page(null, List.of(), false, SUMMARY_FAILED + LegacyDisplay.status(e));
            }
            if (s.isEmpty()) {
                return new Page(null, List.of(), false, null);
            }
            List<Detail> found;
            try {
                found = repo.detailsFrom(accountId, ts, ts == null ? null : authId, inclusive, size + 1);
            } catch (DataAccessException e) {
                return new Page(s.get(), List.of(), false, detailError + LegacyDisplay.status(e));
            }
            boolean more = found.size() > size;
            return new Page(s.get(), List.copyOf(found.subList(0, Math.min(size, found.size()))), more, null);
        } finally {
            tm.rollback(tx);
        }
    }
}
