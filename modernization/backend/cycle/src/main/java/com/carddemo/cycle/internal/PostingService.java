package com.carddemo.cycle.internal;

import com.carddemo.account.api.AccountPostings;
import com.carddemo.account.api.PostingAccount;
import com.carddemo.card.api.CardDirectory;
import com.carddemo.card.api.CardXref;
import com.carddemo.common.batch.BatchAbend;
import com.carddemo.common.batch.JobLog;
import com.carddemo.common.codec.FixedWidth;
import com.carddemo.common.codec.LegacyTimestamps;
import com.carddemo.cycle.api.DailyPosting;
import com.carddemo.cycle.api.PostingOutcome;
import com.carddemo.transaction.api.TransactionLedger;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** CBTRN02C 1500-VALIDATE-TRAN, 2000-POST-TRANSACTION, and 2500-WRITE-REJECT-REC for one daily record. */
@Service
class PostingService implements DailyPosting {

    static final PostingOutcome CARD_NOT_FOUND = new PostingOutcome(100, "INVALID CARD NUMBER FOUND");
    static final PostingOutcome ACCOUNT_NOT_FOUND = new PostingOutcome(101, "ACCOUNT RECORD NOT FOUND");
    static final PostingOutcome OVERLIMIT = new PostingOutcome(102, "OVERLIMIT TRANSACTION");
    static final PostingOutcome EXPIRED = new PostingOutcome(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
    static final PostingOutcome ACCOUNT_UPDATE_FAILED = new PostingOutcome(109, "ACCOUNT RECORD NOT FOUND");

    private final CardDirectory cards;
    private final AccountPostings accounts;
    private final TransactionLedger ledger;
    private final CategoryBalances balances;
    private final PostingRejects rejects;
    private final JobLog log;
    private final Clock clock;
    private final TransactionTemplate savepoint;

    PostingService(CardDirectory cards, AccountPostings accounts, TransactionLedger ledger, CategoryBalances balances,
            PostingRejects rejects, JobLog log, Clock clock, PlatformTransactionManager tm) {
        this.cards = cards;
        this.accounts = accounts;
        this.ledger = ledger;
        this.balances = balances;
        this.rejects = rejects;
        this.log = log;
        this.clock = clock;
        this.savepoint = new TransactionTemplate(tm);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    private static final class AccountUpdateFailed extends RuntimeException {
        AccountUpdateFailed() {
            super(null, null, false, false);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PostingOutcome post(String dailyRecord, long runId, LocalDate businessDate) {
        DailyRecord d = DailyRecord.parse(dailyRecord);
        PostingOutcome outcome;
        Optional<CardXref> xref = cards.findXref(d.cardNum());
        if (xref.isEmpty()) {
            outcome = CARD_NOT_FOUND;
        } else {
            outcome = validateAccount(d, xref.get().accountId());
        }
        if (outcome.posted()) {
            try {
                savepoint.executeWithoutResult(s -> postValid(d, xref.get().accountId()));
            } catch (AccountUpdateFailed e) {
                outcome = ACCOUNT_UPDATE_FAILED; // Q-CBTRN02C-04: the savepoint undid the category balance
            }
        }
        if (!outcome.posted()) {
            try {
                rejects.insert(runId, businessDate, d.raw(), outcome.reasonCode(), outcome.reasonText());
            } catch (DataAccessException e) {
                throw new BatchAbend("ERROR WRITING TO REJECTS FILE", null, e);
            }
        }
        return outcome;
    }

    private PostingOutcome validateAccount(DailyRecord d, long accountId) {
        Optional<PostingAccount> found = accounts.lockForPosting(accountId);
        if (found.isEmpty()) {
            return ACCOUNT_NOT_FOUND;
        }
        PostingAccount a = found.get();
        PostingOutcome outcome = PostingOutcome.POSTED;
        // BR-CBTRN02C-02 with full precision (Q-CBTRN02C-11); returns raise the sum (Q-CBTRN02C-02, preserved).
        BigDecimal tempBal = a.cycleCredit().subtract(a.cycleDebit()).add(d.amount());
        if (a.creditLimit().compareTo(tempBal) < 0) {
            outcome = OVERLIMIT;
        }
        // BR-CBTRN02C-03: independent of the limit check; when both fail, 103 is the one kept.
        if (a.expirationDateText().compareTo(d.origDateText()) < 0) {
            outcome = EXPIRED;
        }
        return outcome;
    }

    private void postValid(DailyRecord d, long accountId) {
        String procTs = LegacyTimestamps.db2(LocalDateTime.now(clock));
        updateCategoryBalance(d, accountId);
        if (!accounts.applyPosting(accountId, d.amount())) {
            throw new AccountUpdateFailed();
        }
        try {
            ledger.insert(d.toTransaction(procTs));
        } catch (DuplicateKeyException e) {
            throw new BatchAbend("ERROR WRITING TO TRANSACTION FILE", "22", e);
        } catch (DataAccessException e) {
            throw new BatchAbend("ERROR WRITING TO TRANSACTION FILE", null, e);
        }
    }

    private void updateCategoryBalance(DailyRecord d, long accountId) {
        Optional<BigDecimal> current;
        try {
            current = balances.lock(accountId, d.typeCd(), d.catCd());
        } catch (DataAccessException e) {
            throw new BatchAbend("ERROR READING TRANSACTION BALANCE FILE", null, e);
        }
        if (current.isEmpty()) {
            log.display("TCATBAL record not found for key : " + FixedWidth.n(accountId, 11) + FixedWidth.x(d.typeCd(), 2)
                + FixedWidth.n(d.catCd(), 4) + ".. Creating.");
            try {
                balances.insert(accountId, d.typeCd(), d.catCd(), d.amount());
            } catch (DataAccessException e) {
                throw new BatchAbend("ERROR WRITING TRANSACTION BALANCE FILE", null, e);
            }
        } else {
            try {
                balances.add(accountId, d.typeCd(), d.catCd(), d.amount());
            } catch (DataAccessException e) {
                throw new BatchAbend("ERROR REWRITING TRANSACTION BALANCE FILE", null, e);
            }
        }
    }

    @Override
    public List<String> rejectRecords(long runId) {
        return rejects.records(runId);
    }
}
