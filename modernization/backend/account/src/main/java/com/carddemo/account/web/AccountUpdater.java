package com.carddemo.account.web;

import com.carddemo.account.internal.AccountCustomerStore;
import com.carddemo.account.internal.AccountRecord;
import com.carddemo.account.internal.CustomerRecord;
import com.carddemo.common.audit.AuditLog;
import com.carddemo.common.format.Pic;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The COACTUPC save (BR-COACTUPC-11) as one database transaction (Q-COACTUPC-03): lock both rows,
 * check that neither changed since it was fetched (row version), then update both by column name
 * and write the audit record (C-AUDIT) in the same transaction. It lives with the screen because the
 * batch jar scans account.internal without the IAM audit log.
 */
@Component
public class AccountUpdater {

    public enum Outcome { ACCOUNT_LOCK_FAILED, CUSTOMER_LOCK_FAILED, CHANGED, UPDATE_FAILED }

    /** A save that wrote nothing; the transaction is rolled back. */
    public static final class SaveFailed extends RuntimeException {
        private final Outcome outcome;

        SaveFailed(Outcome outcome, Throwable cause) {
            super(outcome.name(), cause, false, false);
            this.outcome = outcome;
        }

        public Outcome outcome() {
            return outcome;
        }
    }

    private final AccountCustomerStore store;
    private final AuditLog audit;

    public AccountUpdater(AccountCustomerStore store, AuditLog audit) {
        this.store = store;
        this.audit = audit;
    }

    /**
     * Saves {@code account} and {@code customer}, whose row versions are those of the fetched snapshot,
     * and audits the change by {@code actor} with the names (never the values) of {@code changedFields}.
     *
     * @throws SaveFailed when a lock, the concurrency check, or an update fails; nothing is written
     */
    @Transactional
    public void save(AccountRecord account, CustomerRecord customer, String actor, List<String> changedFields) {
        Optional<AccountRecord> lockedAccount = lock(() -> store.lockAccount(account.accountId()), Outcome.ACCOUNT_LOCK_FAILED);
        Optional<CustomerRecord> lockedCustomer = lock(() -> store.lockCustomer(customer.customerId()),
            Outcome.CUSTOMER_LOCK_FAILED);
        if (!Arrays.equals(lockedAccount.get().rowVersion(), account.rowVersion())
            || !Arrays.equals(lockedCustomer.get().rowVersion(), customer.rowVersion())) {
            throw new SaveFailed(Outcome.CHANGED, null);
        }
        try {
            if (!store.updateAccount(account) || !store.updateCustomer(customer)) {
                throw new SaveFailed(Outcome.UPDATE_FAILED, null);
            }
        } catch (DataAccessException e) {
            throw new SaveFailed(Outcome.UPDATE_FAILED, e);
        }
        audit.record(actor, "ACCOUNT_UPDATE", Pic.accountId(account.accountId()), changedFields);
    }

    private static <T> Optional<T> lock(java.util.function.Supplier<Optional<T>> read, Outcome failure) {
        Optional<T> row;
        try {
            row = read.get();
        } catch (DataAccessException e) {
            throw new SaveFailed(failure, e);
        }
        if (row.isEmpty()) {
            throw new SaveFailed(failure, null);
        }
        return row;
    }
}
