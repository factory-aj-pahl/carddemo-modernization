package com.carddemo.refdata.web;

import com.carddemo.refdata.internal.TypeRow;
import java.io.Serializable;

/**
 * Server-side state of the COTRTLIC conversation (WS-THIS-PROGCOMMAREA), kept in the HTTP session. The rows are
 * the server copy of the displayed page; updates and deletes take their key from here (Q-COTRTLIC-17).
 */
final class ListState implements Serializable {

    private static final long serialVersionUID = 1L;

    /** WS-CA-TYPE-CD, X(02), as last entered. */
    String typeFilter = "  ";
    /** WS-CA-TYPE-DESC, X(50), as last entered. */
    String descFilter = " ".repeat(50);
    /** Slots 1-7; null is an empty row. */
    TypeRow[] rows = new TypeRow[8];
    int rowSelected;
    String firstKey = "";
    String lastKey = "";
    int page = 1;
    boolean lastPageShown;
    boolean nextPage;
    /**
     * Q-COTRTLIC-23: the action ({@code D} or {@code U}) whose confirmation prompt was shown, with its row and the
     * type code in that row; a blank action means nothing is pending. PF10 confirms only this action and record.
     */
    char armedAction = ' ';
    int armedRow;
    String armedCode;

    boolean armed() {
        return armedAction != ' ';
    }

    void arm(char action, int row, String code) {
        armedAction = action;
        armedRow = row;
        armedCode = code;
    }

    void disarm() {
        arm(' ', 0, null);
    }

    /** WS-CA-PAGING-VARIABLES initialized after a filter change (BR-COTRTLIC-08). */
    void resetPaging() {
        firstKey = "";
        lastKey = "";
        page = 0;
        lastPageShown = false;
        nextPage = false;
    }
}
