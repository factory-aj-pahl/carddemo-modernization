package com.carddemo.account.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/** Published read of one account's master record (ACCOUNT-RECORD, CVACT01Y) for inquiry services. */
public interface AccountInquiry {

    /** The fields an account inquiry returns (ACCT-ADDR-ZIP is not part of it). */
    record AccountSnapshot(long accountId, String activeStatus, BigDecimal currentBalance, BigDecimal creditLimit,
                           BigDecimal cashCreditLimit, LocalDate openDate, LocalDate expirationDate, LocalDate reissueDate,
                           BigDecimal currentCycleCredit, BigDecimal currentCycleDebit, String groupId) {
    }

    /**
     * Plain read by key, without a lock. Empty when the account does not exist; a storage failure is thrown
     * as a {@link org.springframework.dao.DataAccessException}.
     */
    Optional<AccountSnapshot> find(long accountId);
}
