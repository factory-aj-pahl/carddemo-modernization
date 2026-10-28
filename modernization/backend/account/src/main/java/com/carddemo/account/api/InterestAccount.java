package com.carddemo.account.api;

import java.math.BigDecimal;

/** The account fields the interest calculation reads (ACCT-ID, ACCT-GROUP-ID, ACCT-CURR-BAL). */
public record InterestAccount(long accountId, String groupId, BigDecimal currentBalance) {
}
