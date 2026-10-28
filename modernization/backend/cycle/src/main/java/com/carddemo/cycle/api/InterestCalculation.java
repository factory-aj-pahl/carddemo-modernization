package com.carddemo.cycle.api;

import java.time.LocalDate;
import java.util.Optional;

/**
 * INTCALC (CBACT04C): monthly interest from the transaction-category balances, one database transaction per
 * account (B-UOW). Throws {@code BatchAbend} on the COBOL abend paths.
 */
public interface InterestCalculation {

    /** B-DATE / Q-INTCALC-01: the 10-character TRAN-ID prefix, {@code YYYYMMDD} of the business date + {@code 00}. */
    static String idPrefix(LocalDate businessDate) {
        return String.format("%04d%02d%02d00", businessDate.getYear(), businessDate.getMonthValue(), businessDate.getDayOfMonth());
    }

    /** {@code YYYY-MM} of the business date: at most one completed INTCALC run per calendar month (Q-CBACT04C-08). */
    static String interestMonth(LocalDate businessDate) {
        return String.format("%04d-%02d", businessDate.getYear(), businessDate.getMonthValue());
    }

    /** The highest TRAN-ID suffix already used with the prefix (0 when none), so a rerun never repeats an ID. */
    long lastSuffix(String idPrefix);

    /**
     * The next account, in key order after {@code afterAccountId}, that has category balances and was not yet
     * processed for the month (a rerun after an abend skips the accounts already committed).
     */
    Optional<Long> nextAccount(String interestMonth, long afterAccountId);

    /**
     * Processes one account's category balances: SYSOUT lines, interest transactions, the account update, and
     * the month marker. Must run inside the caller's transaction. Returns the last TRAN-ID suffix used.
     */
    long chargeAccount(long accountId, LocalDate businessDate, long lastSuffix);
}
