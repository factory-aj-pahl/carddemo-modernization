package com.carddemo.account.web;

import com.carddemo.account.internal.AccountCustomerStore;
import com.carddemo.account.internal.AccountRecord;
import com.carddemo.account.internal.CustomerRecord;
import com.carddemo.card.api.CardDirectory;
import com.carddemo.card.api.CardXref;
import com.carddemo.common.security.AccountScope;
import com.carddemo.common.security.CurrentUser;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * The account lookup chain of COACTVWC and COACTUPC (BR-COACTVWC-01, BR-COACTUPC-02): card
 * cross-reference by account, account master, then the customer of the cross-reference record. An
 * account outside the user's scope is answered like one missing from the cross-reference (C-SCOPE).
 */
@Component
class AccountLookup {

    /** Which step failed, and how. */
    enum Failure { XREF_NOT_FOUND, XREF_ERROR, ACCOUNT_NOT_FOUND, ACCOUNT_ERROR, CUSTOMER_NOT_FOUND, CUSTOMER_ERROR }

    /**
     * Outcome of a lookup. {@code account} is present when the account master was read; {@code customer}
     * when the customer was read too. {@code message} is the verbatim error text of the failure, if any.
     */
    record Result(Failure failure, String message, AccountRecord account, CustomerRecord customer) {

        boolean complete() {
            return failure == null;
        }

        /** The account number is shown in error style (filter "not OK"): every failure before the customer read. */
        boolean accountInError() {
            return failure != null && failure != Failure.CUSTOMER_NOT_FOUND && failure != Failure.CUSTOMER_ERROR;
        }
    }

    private final CardDirectory cards;
    private final AccountCustomerStore store;
    private final AccountScope scope;

    AccountLookup(CardDirectory cards, AccountCustomerStore store, AccountScope scope) {
        this.cards = cards;
        this.store = store;
        this.scope = scope;
    }

    Result find(CurrentUser user, long accountId) {
        Optional<CardXref> xref;
        try {
            xref = scope.canAccess(user, accountId) ? firstXref(accountId) : Optional.empty();
        } catch (DataAccessException e) {
            return new Result(Failure.XREF_ERROR, LookupMessages.fileError("CXACAIX"), null, null);
        }
        if (xref.isEmpty()) {
            return new Result(Failure.XREF_NOT_FOUND, LookupMessages.xrefNotFound(accountId), null, null);
        }
        Optional<AccountRecord> account;
        try {
            account = store.account(accountId);
        } catch (DataAccessException e) {
            return new Result(Failure.ACCOUNT_ERROR, LookupMessages.fileError("ACCTDAT"), null, null);
        }
        if (account.isEmpty()) {
            return new Result(Failure.ACCOUNT_NOT_FOUND, LookupMessages.accountNotFound(accountId), null, null);
        }
        long customerId = xref.get().customerId();
        Optional<CustomerRecord> customer;
        try {
            customer = store.customer(customerId);
        } catch (DataAccessException e) {
            return new Result(Failure.CUSTOMER_ERROR, LookupMessages.fileError("CUSTDAT"), account.get(), null);
        }
        if (customer.isEmpty()) {
            return new Result(Failure.CUSTOMER_NOT_FOUND, LookupMessages.customerNotFound(customerId), account.get(), null);
        }
        return new Result(null, null, account.get(), customer.get());
    }

    /** CXACAIX READ by account: the first cross-reference record of the account, which is its lowest card (DF-6). */
    private Optional<CardXref> firstXref(long accountId) {
        return cards.lowestCardForAccount(accountId).flatMap(cards::findXref);
    }
}
