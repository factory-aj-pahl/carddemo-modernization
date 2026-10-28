package com.carddemo.authorization.internal;

import com.carddemo.authorization.internal.AuthErrorLog.Entry;
import com.carddemo.authorization.internal.AuthErrorLog.Level;
import com.carddemo.authorization.internal.AuthErrorLog.Subsystem;
import com.carddemo.authorization.internal.PendingAuthRepository.AccountRow;
import com.carddemo.card.api.CardDirectory;
import com.carddemo.card.api.CardXref;
import com.carddemo.common.format.Pic;
import com.carddemo.common.mask.Masking;
import com.carddemo.migration.PendingAuthSegments.Detail;
import com.carddemo.migration.PendingAuthSegments.Summary;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/**
 * COPAUA0C 5000-PROCESS-AUTH for one request: look up card, account, customer, and summary, decide, and record
 * the summary and detail. One database transaction per request (Q-COPAUA0C-18) that commits before the caller
 * sends the reply (Q-COPAUA0C-06); a critical error rolls it back (Q-COPAUA0C-07) and no reply is produced.
 */
@Service
public class AuthorizationEngine {

    /** A critical error (ERR-COPAUA0C-03, -04 to -07, -09, -10): nothing committed, no reply. */
    public static class AuthorizationFailure extends RuntimeException {
        private final transient Entry entry;

        AuthorizationFailure(Entry entry, Throwable cause) {
            super(entry.location() + " " + entry.message(), cause);
            this.entry = entry;
        }

        public Entry entry() {
            return entry;
        }
    }

    /** Name of the per-request transaction. */
    public static final String TRANSACTION_NAME = "COPAUA0C";
    public static final String REASON_APPROVED = "0000";
    public static final String REASON_INVALID_CARD = "3100";
    public static final String REASON_INSUFFICIENT_FUND = "4100";
    public static final String REASON_OTHER = "9000";

    private final CardDirectory cards;
    private final PendingAuthRepository repo;
    private final AuthErrorLog errors;
    private final PlatformTransactionManager tm;
    private final Clock clock;

    AuthorizationEngine(CardDirectory cards, PendingAuthRepository repo, AuthErrorLog errors, PlatformTransactionManager tm,
            Clock clock) {
        this.cards = cards;
        this.repo = repo;
        this.errors = errors;
        this.tm = tm;
        this.clock = clock;
    }

    /** Decides and records one request; the returned reply is committed. */
    public AuthReply authorize(AuthRequest rq) {
        String eventKey = Masking.card(rq.cardNum());
        TransactionStatus tx;
        try {
            DefaultTransactionDefinition def = new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            def.setName(TRANSACTION_NAME);
            tx = tm.getTransaction(def);
        } catch (TransactionException e) {
            throw critical("I001", Subsystem.IMS, e, "IMS SCHD FAILED", eventKey);
        }
        AuthReply reply;
        try {
            reply = decideAndRecord(rq, eventKey);
        } catch (RuntimeException e) {
            try {
                tm.rollback(tx);
            } catch (TransactionException ignored) {
                // the original failure is the one reported
            }
            throw e instanceof AuthorizationFailure f ? f : critical("I003", Subsystem.IMS, e, "IMS UPDATE SUMRY FAILED", eventKey);
        }
        try {
            tm.commit(tx);
        } catch (TransactionException e) {
            // Q-COPAUA0C-06: the commit failed, so no approval may be sent.
            throw critical("I003", Subsystem.IMS, e, "IMS UPDATE SUMRY FAILED", eventKey);
        }
        return reply;
    }

    private AuthReply decideAndRecord(AuthRequest rq, String eventKey) {
        Optional<CardXref> xref;
        try {
            xref = cards.findXref(rq.cardNum());
        } catch (DataAccessException e) {
            throw critical("C001", Subsystem.CICS, e, "FAILED TO READ XREF FILE", eventKey);
        }
        AccountRow account = null;
        boolean customerFound = false;
        Summary summary = null;
        if (xref.isEmpty()) {
            errors.log("A001", Level.WARNING, Subsystem.APP, "", "", "CARD NOT FOUND IN XREF", eventKey);
        } else {
            CardXref x = xref.get();
            try {
                account = repo.account(x.accountId()).orElse(null);
            } catch (DataAccessException e) {
                throw critical("C002", Subsystem.CICS, e, "FAILED TO READ ACCT FILE", Pic.accountId(x.accountId()));
            }
            if (account == null) {
                errors.log("A002", Level.WARNING, Subsystem.APP, "", "", "ACCT NOT FOUND IN XREF", Pic.accountId(x.accountId()));
            }
            try {
                customerFound = repo.customer(x.customerId()).isPresent();
            } catch (DataAccessException e) {
                throw critical("C003", Subsystem.CICS, e, "FAILED TO READ CUST FILE", String.format("%09d", x.customerId()));
            }
            if (!customerFound) {
                errors.log("A003", Level.WARNING, Subsystem.APP, "", "", "CUST NOT FOUND IN XREF", String.format("%09d", x.customerId()));
            }
            try {
                summary = repo.lockSummary(x.accountId()).orElse(null);
            } catch (DataAccessException e) {
                throw critical("I002", Subsystem.IMS, e, "IMS GET SUMMARY FAILED", eventKey);
            }
        }

        BigDecimal amount = rq.amount();
        boolean amountValid = amount != null && amount.signum() > 0;
        boolean insufficient = false;
        boolean declined;
        if (xref.isEmpty() || account == null || !amountValid) {
            // Q-COPAUA0C-02, -03, -12: unknown card, missing account, or an amount that is not a positive number.
            declined = true;
        } else {
            BigDecimal available = summary != null ? summary.creditLimit().subtract(summary.creditBalance())
                : account.creditLimit().subtract(account.currentBalance());
            insufficient = amount.compareTo(available) > 0;
            declined = insufficient;
        }
        String reason;
        if (!declined) {
            reason = REASON_APPROVED;
        } else if (xref.isEmpty() || account == null || !customerFound) {
            reason = REASON_INVALID_CARD;
        } else if (insufficient) {
            reason = REASON_INSUFFICIENT_FUND;
        } else {
            reason = REASON_OTHER;
        }
        BigDecimal approvedAmt = declined ? BigDecimal.ZERO.setScale(2) : amount;
        AuthReply reply = new AuthReply(rq.cardNum(), rq.transactionId(), rq.authTime(), declined ? AuthReply.DECLINED
            : AuthReply.APPROVED, reason, approvedAmt);

        if (xref.isPresent() && amountValid) {
            record(rq, xref.get(), account, summary, reply, amount, eventKey);
        }
        return reply;
    }

    /** 8400-UPDATE-SUMMARY and 8500-INSERT-AUTH. */
    private void record(AuthRequest rq, CardXref x, AccountRow account, Summary found, AuthReply reply, BigDecimal amount,
            String eventKey) {
        Summary s = found != null ? found : new Summary(x.accountId(), (int) x.customerId(), "", "", BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null);
        BigDecimal creditLimit = account != null ? account.creditLimit() : s.creditLimit();
        BigDecimal cashLimit = account != null ? account.cashCreditLimit() : s.cashLimit();
        Summary updated;
        if (reply.approved()) {
            // BR-COPAUA0C-14, Q-COPAUA0C-17: the cash balance is set to 0 on every approval.
            updated = new Summary(s.accountId(), s.customerId(), s.authStatus(), s.accountStatus(), creditLimit, cashLimit,
                s.creditBalance().add(reply.approvedAmount()), BigDecimal.ZERO, s.approvedCount() + 1, s.declinedCount(),
                s.approvedAmt().add(reply.approvedAmount()), s.declinedAmt(), s.legacyStatus(), s.legacyFiller(), s.legacyRba());
        } else {
            // Q-COPAUA0C-04: this request's amount.
            updated = new Summary(s.accountId(), s.customerId(), s.authStatus(), s.accountStatus(), creditLimit, cashLimit,
                s.creditBalance(), s.cashBalance(), s.approvedCount(), s.declinedCount() + 1, s.approvedAmt(),
                s.declinedAmt().add(amount), s.legacyStatus(), s.legacyFiller(), s.legacyRba());
        }
        try {
            if (found != null) {
                if (!repo.updateSummary(updated)) {
                    throw new org.springframework.dao.EmptyResultDataAccessException("summary row vanished", 1);
                }
            } else {
                repo.insertSummary(updated);
            }
        } catch (DataAccessException e) {
            throw critical("I003", Subsystem.IMS, e, "IMS UPDATE SUMRY FAILED", eventKey);
        }
        // Q-COPAUA0C-11: the key is the insert timestamp plus the generated authorization id, so it never collides.
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        Detail d = new Detail(0, x.accountId(), now, rq.authDate(), rq.authTime(), rq.cardNum(), rq.authType(),
            rq.cardExpiryDate(), rq.messageType(), rq.messageSource(), reply.authIdCode(), reply.respCode(), reply.reasonCode(),
            rq.processingCode(), amount, reply.approvedAmount(), rq.merchantCategoryCode(), rq.acqrCountryCode(),
            rq.posEntryMode(), rq.merchantId(), rq.merchantName(), rq.merchantCity(), rq.merchantState(), rq.merchantZip(),
            rq.transactionId(), reply.approved() ? "P" : "D", "", null, null, null);
        try {
            repo.insertDetail(d);
        } catch (DataAccessException e) {
            throw critical("I004", Subsystem.IMS, e, "IMS INSERT DETL FAILED", eventKey);
        }
    }

    private AuthorizationFailure critical(String location, Subsystem subsystem, Throwable e, String message, String eventKey) {
        String code1 = "";
        String code2 = "";
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                code1 = sql.getSQLState() == null ? "" : sql.getSQLState();
                code2 = Integer.toString(sql.getErrorCode());
                break;
            }
        }
        return new AuthorizationFailure(errors.log(location, Level.CRITICAL, subsystem, code1, code2, message, eventKey), e);
    }
}
