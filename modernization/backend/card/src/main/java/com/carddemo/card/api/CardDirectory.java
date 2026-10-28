package com.carddemo.card.api;

import java.util.Optional;

/** Published card lookups for other contexts (CXACAIX alternate-index path over the cross-reference). */
public interface CardDirectory {

    /**
     * DF-6: the card an account-level operation is attributed to, which is the lowest card number in
     * the cross-reference for the account. Empty when the account has no cross-reference record.
     */
    Optional<String> lowestCardForAccount(long accountId);

    /** The cross-reference record of a card (XREFFILE read by card number). Empty when not found. */
    Optional<CardXref> findXref(String cardNum);
}
