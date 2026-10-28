package com.carddemo.transaction.api;

/** Published write access to the transaction table for online writers (COTRN02C, COBIL00C). */
public interface TransactionLedger {

    /**
     * Q-COTRN02C-01 / Q-COBIL00C-01: the next online TRAN-ID, 16 digits zero-padded. It is
     * max(counter, highest all-numeric TRAN-ID) + 1, taken under a row lock on the counter that is
     * held until the surrounding transaction ends, so concurrent writers never get the same ID.
     * Must be called inside a transaction.
     */
    String nextOnlineTransactionId();

    /** Inserts one transaction row (CVTRA05Y). */
    void insert(NewTransaction transaction);
}
