package com.carddemo.card.web;

import jakarta.servlet.http.HttpSession;
import java.io.Serializable;

/**
 * Server-side hand-off from the card list to the view and update screens (C-SESSION). The list
 * resolves the selected row on the server, so the full card number never reaches the client
 * (Q-COCRDLIC-16).
 */
final class CardScreens {

    static final String SELECTED = "carddemo.COCRDLIC.selected";

    record Selection(String program, long accountId, String cardNum) implements Serializable {
    }

    private CardScreens() {
    }

    static void select(HttpSession session, String program, long accountId, String cardNum) {
        session.setAttribute(SELECTED, new Selection(program, accountId, cardNum));
    }

    /** Takes (and clears) the pending selection; null unless it was made for {@code program}. */
    static Selection take(HttpSession session, String program) {
        Object o = session.getAttribute(SELECTED);
        session.removeAttribute(SELECTED);
        return o instanceof Selection sel && sel.program().equals(program) ? sel : null;
    }
}
