package com.carddemo.account.api;

import java.math.BigDecimal;
import java.util.Optional;

/** Published access to an account's current balance (ACCT-CURR-BAL) for other contexts. */
public interface AccountBalances {

    /** Plain read without a lock (Q-COBIL00C-13). Empty when the account does not exist. */
    Optional<BigDecimal> currentBalance(long accountId);

    /**
     * Reads the balance and holds an update lock on the account row until the surrounding database
     * transaction ends. Must be called inside a transaction. Empty when the account does not exist.
     */
    Optional<BigDecimal> lockCurrentBalance(long accountId);

    /** Sets the current balance; returns false when the account row no longer exists. */
    boolean updateCurrentBalance(long accountId, BigDecimal newBalance);
}
