package com.carddemo.common.security;

import java.util.Set;

/**
 * C-SCOPE (D-USER-ACCT): which accounts a user may see. ADMIN is unrestricted; a USER sees only
 * accounts linked to their user ID. Out-of-scope keys must be answered exactly like missing keys.
 */
public interface AccountScope {

    /** True when the user may read or change the account. */
    boolean canAccess(CurrentUser user, long accountId);

    /** Linked accounts of a USER. Not meaningful for ADMIN (unrestricted). */
    Set<Long> linkedAccounts(String userId);

    /** Convenience for the current request's user. */
    default boolean canAccess(long accountId) {
        return canAccess(CurrentUser.require(), accountId);
    }
}
