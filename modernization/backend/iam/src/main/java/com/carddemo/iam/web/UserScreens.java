package com.carddemo.iam.web;

import jakarta.servlet.http.HttpSession;
import java.io.Serializable;

/** Server-side hand-off between the user list and the update/delete screens (Q-COUSR00C-14, C-SESSION). */
final class UserScreens {

    static final String SELECTED = "carddemo.COUSR00C.selected";

    /** The user picked on the list for {@code program}; the list's row number was resolved on the server. */
    record Selection(String program, String userId) implements Serializable {
    }

    private UserScreens() {
    }

    static void select(HttpSession session, String program, String userId) {
        session.setAttribute(SELECTED, new Selection(program, userId));
    }

    /** Takes (and clears) the pending selection; null unless it was made for {@code program}. */
    static Selection take(HttpSession session, String program) {
        Object o = session.getAttribute(SELECTED);
        session.removeAttribute(SELECTED);
        return o instanceof Selection sel && sel.program().equals(program) ? sel : null;
    }
}
