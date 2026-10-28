package com.carddemo.billpay.internal;

import com.carddemo.account.api.AccountBalances;
import com.carddemo.card.api.CardDirectory;
import com.carddemo.common.codec.LegacyTimestamps;
import com.carddemo.common.security.AccountScope;
import com.carddemo.common.security.CurrentUser;
import com.carddemo.transaction.api.NewTransaction;
import com.carddemo.transaction.api.TransactionLedger;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** COBIL00C payment logic: pays the full current balance of one account. */
@Service
public class BillPaymentService {

    /** Largest TRAN-AMT, PIC S9(9)V99 (Q-COBIL00C-07). */
    static final BigDecimal MAX_TRAN_AMT = new BigDecimal("999999999.99");

    public static final String NOT_FOUND = "Account ID NOT found...";
    public static final String LOOKUP_ACCOUNT_FAILED = "Unable to lookup Account...";
    public static final String LOOKUP_XREF_FAILED = "Unable to lookup XREF AIX file...";
    public static final String LOOKUP_TRAN_FAILED = "Unable to lookup Transaction...";
    public static final String ADD_FAILED = "Unable to Add Bill pay Transaction...";
    public static final String UPDATE_FAILED = "Unable to Update Account...";

    /** Outcome of a confirmed payment that did not fail on a data-access step. */
    public sealed interface Outcome {
    }

    public record Paid(String tranId, long accountId, BigDecimal amount, BigDecimal newBalance) implements Outcome {
    }

    public record NotFound() implements Outcome {
    }

    public record NothingToPay(BigDecimal balance) implements Outcome {
    }

    /** Q-COBIL00C-08: the balance changed after it was shown; nothing was written. */
    public record BalanceChanged(BigDecimal balance) implements Outcome {
    }

    /** Q-COBIL00C-07 (NEW): the balance does not fit TRAN-AMT; nothing was written. */
    public record TooLarge(BigDecimal balance) implements Outcome {
    }

    /** A data-access step failed; the whole payment is rolled back and {@link #getMessage()} is the screen text. */
    public static class PaymentFailed extends RuntimeException {
        PaymentFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final AccountBalances accounts;
    private final CardDirectory cards;
    private final TransactionLedger transactions;
    private final AccountScope scope;
    private final Clock clock;

    public BillPaymentService(AccountBalances accounts, CardDirectory cards, TransactionLedger transactions,
                              AccountScope scope, Clock clock) {
        this.accounts = accounts;
        this.cards = cards;
        this.transactions = transactions;
        this.scope = scope;
        this.clock = clock;
    }

    /**
     * Display turn: a plain read of the balance (Q-COBIL00C-13). Empty when the account does not
     * exist or is outside the user's scope (C-SCOPE). Throws {@link PaymentFailed} on a read failure.
     */
    public Optional<BigDecimal> balance(CurrentUser user, long accountId) {
        if (!scope.canAccess(user, accountId)) {
            return Optional.empty();
        }
        try {
            return accounts.currentBalance(accountId);
        } catch (DataAccessException e) {
            throw new PaymentFailed(LOOKUP_ACCOUNT_FAILED, e);
        }
    }

    /**
     * Confirmed payment as one database transaction (Q-COBIL00C-02): lock the account, choose the
     * card (DF-6), take the next TRAN-ID, insert the payment, zero the balance.
     *
     * @param balanceShown false when the screen showed no balance yet (Y on the first ENTER pays at
     *                     once, BR-COBIL00C-08)
     * @param shownBalance the balance the user saw before confirming; null when it was unreadable,
     *                     which never matches and so asks for a new confirmation
     */
    @Transactional
    public Outcome pay(CurrentUser user, long accountId, boolean balanceShown, BigDecimal shownBalance) {
        if (!scope.canAccess(user, accountId)) {
            return new NotFound();
        }
        BigDecimal balance;
        try {
            Optional<BigDecimal> locked = accounts.lockCurrentBalance(accountId);
            if (locked.isEmpty()) {
                return new NotFound();
            }
            balance = locked.get();
        } catch (DataAccessException e) {
            throw new PaymentFailed(LOOKUP_ACCOUNT_FAILED, e);
        }
        if (balance.signum() <= 0) {
            return new NothingToPay(balance);
        }
        if (balanceShown && (shownBalance == null || shownBalance.compareTo(balance) != 0)) {
            return new BalanceChanged(balance);
        }
        if (balance.compareTo(MAX_TRAN_AMT) > 0) {
            return new TooLarge(balance);
        }
        String card;
        try {
            Optional<String> found = cards.lowestCardForAccount(accountId);
            if (found.isEmpty()) {
                throw new PaymentFailed(NOT_FOUND, null);
            }
            card = found.get();
        } catch (DataAccessException e) {
            throw new PaymentFailed(LOOKUP_XREF_FAILED, e);
        }
        String tranId;
        try {
            tranId = transactions.nextOnlineTransactionId();
        } catch (DataAccessException e) {
            throw new PaymentFailed(LOOKUP_TRAN_FAILED, e);
        }
        // BR-COBIL00C-05: the CICS timestamp has whole seconds and a zero fraction.
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        String ts = LegacyTimestamps.cicsTimestamp(now);
        BigDecimal amount = balance;
        try {
            transactions.insert(new NewTransaction(tranId, "02", 2, "POS TERM", "BILL PAYMENT - ONLINE", amount,
                999999999L, "BILL PAYMENT", "N/A", "N/A", card, ts, now, ts, now));
        } catch (DataAccessException e) {
            throw new PaymentFailed(ADD_FAILED, e);
        }
        BigDecimal newBalance = balance.subtract(amount);
        try {
            if (!accounts.updateCurrentBalance(accountId, newBalance)) {
                throw new PaymentFailed(NOT_FOUND, null);
            }
        } catch (DataAccessException e) {
            throw new PaymentFailed(UPDATE_FAILED, e);
        }
        return new Paid(tranId, accountId, amount, newBalance);
    }
}
