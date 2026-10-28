package com.carddemo.refdata.api;

/** Published lookups of the transaction type (TRANTYPE) and category (TRANCATG) reference tables. */
public interface TransactionCodes {

    /** True when the two-character type code exists in the transaction type table. */
    boolean typeExists(String typeCd);

    /** True when the type and category pair exists in the transaction category table. */
    boolean categoryExists(String typeCd, int catCd);
}
