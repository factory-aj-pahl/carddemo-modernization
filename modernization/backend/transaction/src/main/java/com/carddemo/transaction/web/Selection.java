package com.carddemo.transaction.web;

import java.io.Serializable;

/**
 * The row selected in the list (CDEMO-CT00-TRN-SELECTED), held in the session for the view's first
 * entry (C-SESSION). The view consumes it on its first turn; the list clears it whenever it is shown.
 */
record Selection(String tranId) implements Serializable {

    static final String ATTR = "carddemo.transaction.selection";
}
