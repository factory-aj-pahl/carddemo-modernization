package com.carddemo.account.api;

import java.util.Optional;

/** Published read of an account's status and limits and of a customer's name, address and phone for other contexts. */
public interface AccountProfiles {

    /** Plain read without a lock. Empty when the account does not exist. */
    Optional<AccountLimits> limits(long accountId);

    /** Plain read without a lock. Empty when the customer does not exist. */
    Optional<CustomerContact> contact(long customerId);
}
