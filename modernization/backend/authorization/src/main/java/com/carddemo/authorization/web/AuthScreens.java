package com.carddemo.authorization.web;

import jakarta.servlet.http.HttpSession;
import java.io.Serializable;

/**
 * Server-side COMMAREA fields shared by COPAUS0C and COPAUS1C (C-SESSION): the account in context
 * (CDEMO-ACCT-ID, with CDEMO-CUST-ID from its cross-reference) and the selected authorization
 * (CDEMO-CPVS-PAU-SELECTED).
 */
final class AuthScreens {

    static final String ACCOUNT = "carddemo.authorization.account";
    static final String SELECTED = "carddemo.authorization.selected";

    /** @param customerId null until a cross-reference read for the account succeeded */
    record AccountContext(long accountId, Long customerId) implements Serializable {
    }

    record Selection(long accountId, long customerId, long authId) implements Serializable {
    }

    private AuthScreens() {
    }

    static AccountContext account(HttpSession session) {
        return session.getAttribute(ACCOUNT) instanceof AccountContext a ? a : null;
    }

    static void account(HttpSession session, AccountContext a) {
        session.setAttribute(ACCOUNT, a);
    }

    static Selection selection(HttpSession session) {
        return session.getAttribute(SELECTED) instanceof Selection s ? s : null;
    }

    static void select(HttpSession session, Selection s) {
        session.setAttribute(SELECTED, s);
    }

    static void clearSelection(HttpSession session) {
        session.removeAttribute(SELECTED);
    }
}
