package com.carddemo.account.api;

import java.math.BigDecimal;

/** ACCT-ACTIVE-STATUS, ACCT-CURR-BAL, ACCT-CREDIT-LIMIT and ACCT-CASH-CREDIT-LIMIT of one account. */
public record AccountLimits(long accountId, String activeStatus, BigDecimal currentBalance, BigDecimal creditLimit,
        BigDecimal cashCreditLimit) {
}
