package com.carddemo.account.api;

import java.math.BigDecimal;
import java.util.Optional;

/** Published posting access to accounts for the batch cycle (CBTRN02C account read and rewrite). */
public interface AccountPostings {

    /**
     * Reads the account and holds an update lock on its row until the surrounding database transaction
     * ends. Must be called inside a transaction. Empty when the account does not exist.
     */
    Optional<PostingAccount> lockForPosting(long accountId);

    /**
     * BR-CBTRN02C-07: adds the amount to the current balance, and to the cycle credit when the amount is
     * zero or positive, otherwise to the cycle debit. Returns false when the account row does not exist.
     */
    boolean applyPosting(long accountId, BigDecimal amount);
}
