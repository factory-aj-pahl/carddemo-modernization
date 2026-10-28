package com.carddemo.account.api;

import java.math.BigDecimal;

/** The account fields a statement prints (ACCT-ID, ACCT-CURR-BAL). */
public record StatementAccount(long accountId, BigDecimal currentBalance) {
}
