package com.carddemo.account.internal;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/** One ACCTDAT record (CVACT01Y) with the row version read with it. ACCT-ADDR-ZIP is never edited online. */
public record AccountRecord(long accountId, String activeStatus, BigDecimal currentBalance, BigDecimal creditLimit,
                            BigDecimal cashCreditLimit, LocalDate openDate, LocalDate expirationDate,
                            LocalDate reissueDate, BigDecimal cycleCredit, BigDecimal cycleDebit, String groupId,
                            byte[] rowVersion) implements Serializable {
}
