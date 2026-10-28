package com.carddemo.account.api;

import java.math.BigDecimal;
import java.util.Optional;

/** Published account and customer access for the batch cycle jobs other than posting (INTCALC, TRANVERIFY, CREASTMT). */
public interface CycleAccounts {

    /**
     * CBACT04C 1100-GET-ACCT-DATA: the account, with an update lock held until the surrounding database
     * transaction ends. Must be called inside a transaction. Empty when the account does not exist.
     */
    Optional<InterestAccount> lockForInterest(long accountId);

    /** CBACT04C 1050-UPDATE-ACCOUNT: sets the current balance and resets cycle credit and cycle debit to zero. */
    boolean applyInterest(long accountId, BigDecimal newBalance);

    /** True when the account record exists (CBTRN01C 3000-READ-ACCOUNT). */
    boolean exists(long accountId);

    /** The account fields a statement prints (CBSTM03A 3000-ACCTFILE-GET). */
    Optional<StatementAccount> statementAccount(long accountId);

    /** The customer fields a statement prints (CBSTM03A 2000-CUSTFILE-GET). */
    Optional<StatementCustomer> statementCustomer(long customerId);
}
