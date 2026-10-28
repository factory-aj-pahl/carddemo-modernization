package com.carddemo.card.internal;

import com.carddemo.card.internal.CardRepository.CardRecord;
import com.carddemo.common.security.AccountScope;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Card read by account and card number for COCRDSLC and COCRDUPC. The card must belong to the
 * entered account (Q-COCRDSLC-01, Q-COCRDUPC-02) and the account must be in the user's scope
 * (C-SCOPE); otherwise it is answered as not found.
 */
@Service
public class CardLookup {

    private final CardRepository cards;
    /**
     * Resolved per call: modules that depend on card without iam (account, transaction, cycle, ...)
     * build contexts with no AccountScope bean, and they never call the lookup.
     */
    private final ObjectProvider<AccountScope> scope;

    public CardLookup(CardRepository cards, ObjectProvider<AccountScope> scope) {
        this.cards = cards;
        this.scope = scope;
    }

    public Optional<CardRecord> find(long accountId, String cardNum) {
        return cards.find(cardNum).filter(c -> c.accountId() == accountId && scope.getObject().canAccess(accountId));
    }
}
