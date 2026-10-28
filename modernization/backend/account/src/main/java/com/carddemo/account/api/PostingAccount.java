package com.carddemo.account.api;

import java.math.BigDecimal;

/**
 * The account fields the posting validation reads (CBTRN02C 1500-B-LOOKUP-ACCT).
 *
 * @param expirationDateText ACCT-EXPIRAION-DATE as its 10-character text ({@code YYYY-MM-DD}, or 10 spaces
 *                           when the date is empty), for the alphanumeric expiry comparison
 */
public record PostingAccount(long accountId, BigDecimal creditLimit, BigDecimal cycleCredit, BigDecimal cycleDebit,
        String expirationDateText) {
}
