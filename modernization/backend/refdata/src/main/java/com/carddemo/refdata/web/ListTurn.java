package com.carddemo.refdata.web;

import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.MessageColor;
import com.carddemo.common.screen.ScreenResponse;
import com.carddemo.refdata.internal.CursorFailure;
import com.carddemo.refdata.internal.Db2Codes;
import com.carddemo.refdata.internal.Db2Error;
import com.carddemo.refdata.internal.Db2Failure;
import com.carddemo.refdata.internal.TransactionTypeStore;
import com.carddemo.refdata.internal.TypeEdits;
import com.carddemo.refdata.internal.TypeFilter;
import com.carddemo.refdata.internal.TypeRow;
import com.carddemo.refdata.web.TranTypeListController.Fields;
import com.carddemo.refdata.web.TranTypeListController.Request;
import com.carddemo.refdata.web.TranTypeListController.Row;
import com.carddemo.refdata.web.TranTypeListController.RowInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One COTRTLIC turn: 1000-RECEIVE-MAP and 1200-EDIT-INPUTS, the key checks, the main EVALUATE of 0000-MAIN,
 * the cursor reads 8000/8100, the changes 9200/9300, and 2000-SEND-MAP. Working storage is per turn; the
 * conversation state is {@link ListState}.
 */
final class ListTurn {

    static final String INFO_REC_ACTIONS = "Type U to update, D to delete any record";
    static final String INFO_DELETE = "Delete HIGHLIGHTED row ? Press F10 to confirm";
    static final String INFO_UPDATE = "Update HIGHLIGHTED row. Press F10 to save";
    static final String INFO_DELETED = "HIGHLIGHTED row deleted.Hit Enter to continue";
    static final String INFO_UPDATED = "HIGHLIGHTED row was updated";
    static final String NO_RECORDS_FOUND = "No records found for this search condition.";
    static final String NO_MORE_RECORDS = "No more pages for these search conditions";
    static final String MORE_THAN_1_ACTION = "Please select only 1 action";
    static final String INVALID_ACTION = "Action code selected is invalid";
    static final String NO_CHANGES = "No change detected with respect to database values.";
    static final String TYPE_FILTER_INVALID = "TYPE CODE FILTER,IF SUPPLIED MUST BE A 2 DIGIT NUMBER";
    static final String NO_FILTER_MATCH = "No Records found for these filter conditions";
    static final String NO_PREVIOUS_PAGES = "No previous pages to display";
    static final String NO_MORE_PAGES = "No more pages to display";
    /** Reused for a concurrent change detected by the compare-and-set update (Q-COTRTLIC-19). */
    static final String DEADLOCK = "Deadlock. Someone else updating ?";

    private static final int ROWS = TransactionTypeStore.PAGE;
    private static final int INFO_WIDTH = 45;
    private static final int MSG_LEN = 75;
    private static final int LONG_MSG_LEN = 800;

    private enum Flag { BLANK, VALID, NOT_OK }

    /** Outcome of the turn: a screen, or a transfer of control. */
    record Result(ScreenResponse<Fields> response, boolean endConversation, boolean toMaintenance) {
    }

    private final TransactionTypeStore store;
    private final ListState ca;

    private Key key = Key.ENTER;
    private boolean firstEntry;
    private String inType = "  ";
    private String inDesc = " ".repeat(50);
    private final char[] sel = new char[ROWS + 1];
    /** Trimmed row descriptions as received; null when the field was blank or {@code *} (LOW-VALUES). */
    private final String[] descIn = new String[ROWS + 1];
    private final boolean[] rowError = new boolean[ROWS + 1];

    private boolean inputError;
    private boolean protectRows;
    private String msg;
    private Flag typeFlag = Flag.BLANK;
    private Flag descFlag = Flag.BLANK;
    private boolean typeChanged;
    private boolean descChanged;
    private boolean rowSelectionChanged = true;
    private String typeCdFilter;
    private String descLike;
    private int deletes;
    private int updates;
    private int requested;
    private int validActions;
    private int iSelected;
    private boolean badActions;
    private boolean changesOccurred;
    private TypeEdits.Check rowDescCheck;
    private boolean deleted;
    private boolean updateCompleted;
    private boolean firstPageBefore;
    private boolean countFailed;

    ListTurn(TransactionTypeStore store, ListState ca) {
        this.store = store;
        this.ca = ca;
        java.util.Arrays.fill(sel, ' ');
    }

    /** First entry (BR-COTRTLIC-01, -02, -03): fresh state, no input received, read from the first type. */
    Result firstEntry() {
        firstEntry = true;
        Result down = priming();
        if (down != null) {
            return down;
        }
        readForward(ca.firstKey, false);
        return screen();
    }

    Result turn(Request req) {
        receive(req);
        edits();
        key = req.key() == null ? Key.ENTER : req.key().folded();
        boolean pfkValid = key == Key.ENTER || key == Key.PF2 || key == Key.PF3 || key == Key.PF7 || key == Key.PF8
            || key == Key.PF10 && (ca.deletePending || ca.updatePending);
        if (!pfkValid) {
            key = Key.ENTER;
        }
        if (key == Key.PF3) {
            return new Result(ScreenResponse.navigate(TranTypeNavigation.ADMIN_MENU), true, false);
        }
        if (key == Key.PF2) {
            return new Result(ScreenResponse.navigate("COTRTUPC"), true, true);
        }
        if (key != Key.PF8) {
            ca.lastPageShown = false;
        }
        if (key == Key.PF10 && !((ca.deletePending || ca.updatePending) && !typeChanged && !descChanged && !rowSelectionChanged)) {
            key = Key.ENTER;
        }
        Result down = priming();
        if (down != null) {
            return down;
        }
        decide();
        return screen();
    }

    // ---- 1100-RECEIVE-SCREEN ------------------------------------------------------------------------------

    private void receive(Request req) {
        inType = pad(cut(req.typeFilter(), 2), 2);
        inDesc = pad(cut(req.descFilter(), 50), 50);
        List<RowInput> rows = req.rows() == null ? List.of() : req.rows();
        for (int i = 1; i <= ROWS; i++) {
            RowInput in = i <= rows.size() ? rows.get(i - 1) : null;
            String s = in == null || in.select() == null || in.select().isEmpty() ? " " : in.select().substring(0, 1);
            // An empty row's select field is protected, so nothing can be typed there.
            sel[i] = ca.rows[i] == null ? ' ' : s.charAt(0);
            String d = in == null ? null : cut(in.description(), 50);
            String r = TypeEdits.rtrim(d);
            descIn[i] = r.isBlank() || r.equals("*") ? null : TypeEdits.trim(d);
        }
    }

    // ---- 1200-EDIT-INPUTS ---------------------------------------------------------------------------------

    private void edits() {
        boolean typeBlank = inType.isBlank() || inType.equals("00");
        typeChanged = !(inType.equals(ca.typeFilter) || typeBlank && (ca.typeFilter.isBlank() || ca.typeFilter.equals("00")));
        boolean descBlank = inDesc.isBlank();
        descChanged = !(inDesc.equals(ca.descFilter) || descBlank && ca.descFilter.isBlank());
        editArray();
        editDescFilter(descBlank);
        editTypeFilter(typeBlank);
        crossEdits();
        // Q-COTRTLIC-22: after a failed count the new filters are not taken over, so the rows and page of the
        // previous filters stay and the next ENTER counts again as a filter change.
        if ((typeChanged || descChanged) && !countFailed) {
            ca.typeFilter = inType;
            ca.descFilter = inDesc;
            // Q-COTRTLIC-21: the paging variables stay as they were while a filter is in error.
            if (typeFlag != Flag.NOT_OK && descFlag != Flag.NOT_OK) {
                ca.resetPaging();
            }
        }
    }

    /** 1210-EDIT-ARRAY (BR-COTRTLIC-12, VR-COTRTLIC-03, -04). */
    private void editArray() {
        if (typeChanged || descChanged) {
            // Q-COTRTLIC-01: a filter change clears every row selection, so PF10 never acts on another record.
            java.util.Arrays.fill(sel, ' ');
            ca.rowSelected = 0;
            return;
        }
        int blanks = 0;
        for (int i = 1; i <= ROWS; i++) {
            switch (sel[i]) {
                case ' ' -> blanks++;
                case 'D' -> deletes++;
                case 'U' -> updates++;
                default -> { }
            }
        }
        requested = ROWS - blanks;
        validActions = deletes + updates;
        boolean moreThan1 = requested >= 2;
        for (int i = ROWS; i >= 1; i--) {
            char c = sel[i];
            if (c == 'D' || c == 'U') {
                iSelected = i;
                if (moreThan1) {
                    rowError[i] = true;
                    badActions = true;
                }
                if (c == 'U') {
                    editRowDescription(i);
                }
            } else if (c != ' ') {
                inputError = true;
                rowError[i] = true;
                badActions = true;
                msg = INVALID_ACTION;
            }
        }
        rowSelectionChanged = iSelected != ca.rowSelected;
        ca.rowSelected = iSelected;
        if (moreThan1) {
            inputError = true;
            msg = MORE_THAN_1_ACTION;
        }
    }

    /** 1211-EDIT-ARRAY-DESC (VR-COTRTLIC-05 to -07). */
    private void editRowDescription(int i) {
        String stored = TypeEdits.trim(ca.rows[i].description());
        String in = descIn[i];
        if (in != null && in.toUpperCase(Locale.ROOT).equals(stored.toUpperCase(Locale.ROOT))) {
            msg = NO_CHANGES;
            return;
        }
        changesOccurred = true;
        rowDescCheck = in == null ? TypeEdits.Check.BLANK : TypeEdits.description(in);
        if (rowDescCheck != TypeEdits.Check.OK) {
            inputError = true;
            if (msg == null) {
                msg = TypeEdits.descriptionMessage(rowDescCheck);
            }
        }
    }

    /** 1230-EDIT-DESC (BR-COTRTLIC-10). */
    private void editDescFilter(boolean blank) {
        if (blank) {
            descFlag = Flag.BLANK;
            return;
        }
        descFlag = Flag.VALID;
        descLike = "%" + TypeEdits.trim(inDesc) + "%";
    }

    /** 1220-EDIT-TYPECD (BR-COTRTLIC-09, VR-COTRTLIC-01). */
    private void editTypeFilter(boolean blank) {
        if (blank) {
            typeFlag = Flag.BLANK;
            return;
        }
        if (!inType.chars().allMatch(c -> c >= '0' && c <= '9')) {
            inputError = true;
            typeFlag = Flag.NOT_OK;
            protectRows = true;
            msg = TYPE_FILTER_INVALID;
            return;
        }
        typeFlag = Flag.VALID;
        typeCdFilter = inType;
    }

    /** 1290-CROSS-EDITS (BR-COTRTLIC-11, VR-COTRTLIC-02, ERR-COTRTLIC-09). */
    private void crossEdits() {
        if (typeFlag != Flag.VALID && descFlag != Flag.VALID) {
            return;
        }
        int count;
        try {
            count = store.count(filter());
        } catch (Db2Failure e) {
            // Q-COTRTLIC-22: the database error is shown; VR-COTRTLIC-02 does not apply to a failed count.
            inputError = true;
            countFailed = true;
            if (msg == null) {
                msg = db2Message("Error reading TRANSACTION_TYPE table ", e.error());
            }
            return;
        }
        if (count == 0) {
            inputError = true;
            if (typeFlag == Flag.VALID) {
                typeFlag = Flag.NOT_OK;
            }
            if (descFlag == Flag.VALID) {
                descFlag = Flag.NOT_OK;
            }
            protectRows = true;
            msg = NO_FILTER_MATCH;
        }
    }

    private TypeFilter filter() {
        return new TypeFilter(typeFlag == Flag.VALID ? typeCdFilter : null, descFlag == Flag.VALID ? descLike : null);
    }

    // ---- 0000-MAIN decision -------------------------------------------------------------------------------

    /** 9998-PRIMING-QUERY; a failure ends the conversation with the long message (ERR-COTRTLIC-01). */
    private Result priming() {
        try {
            store.ping();
            return null;
        } catch (Db2Failure e) {
            String text = cut(db2Text("Db2 access failure. ", e.error()), LONG_MSG_LEN);
            return new Result(ScreenResponse.navigate(TranTypeNavigation.ADMIN_MENU, text, MessageColor.RED), true, false);
        }
    }

    private void decide() {
        boolean firstPage = ca.page == 1;
        firstPageBefore = firstPage;
        if (inputError) {
            if (typeFlag != Flag.NOT_OK && descFlag != Flag.NOT_OK && !countFailed) {
                readForward(ca.firstKey, false);
            }
        } else if (key == Key.PF7 && firstPage) {
            readForward(ca.firstKey, false);
        } else if (key == Key.PF8 && ca.nextPage) {
            ca.page++;
            // Q-COTRTLIC-20: arriving at the last page is not pressing PF8 at the boundary.
            readForward(ca.lastKey, true);
            clearSelections();
        } else if (key == Key.PF7) {
            ca.page = Math.max(ca.page - 1, 1);
            readBackward();
            clearSelections();
        } else if (key == Key.ENTER && deletes > 0) {
            readForward(ca.firstKey, false);
        } else if (key == Key.PF10 && deletes > 0) {
            deleteRecord();
            if (deleted) {
                // Q-COTRTLIC-04: the signed-on user is untouched (session); the list restarts at the first page of
                // the same filters and is re-read, so the deleted row disappears.
                ca.rows = new TypeRow[ROWS + 1];
                ca.rowSelected = 0;
                ca.deletePending = false;
                ca.updatePending = false;
                ca.resetPaging();
                ca.page = 1;
                clearSelections();
                readForward(ca.firstKey, false);
            }
        } else if (key == Key.ENTER && updates > 0) {
            readForward(ca.firstKey, false);
        } else if (key == Key.PF10 && updates > 0) {
            updateRecord();
            readForward(ca.firstKey, false);
        } else {
            readForward(ca.firstKey, false);
        }
    }

    private void clearSelections() {
        java.util.Arrays.fill(sel, ' ');
    }

    // ---- 8000-READ-FORWARD / 8100-READ-BACKWARDS ----------------------------------------------------------

    /** BR-COTRTLIC-05. */
    private void readForward(String startKey, boolean arrivingAtNextPage) {
        ca.rows = new TypeRow[ROWS + 1];
        List<TypeRow> rows;
        CursorFailure failure = null;
        try {
            rows = store.forward(startKey, filter());
        } catch (CursorFailure f) {
            failure = f;
            rows = f.rows();
        }
        if (failure != null && failure.phase() == CursorFailure.Phase.OPEN) {
            setDb2Message("C-TR-TYPE-FORWARD Open", failure.error());
            return;
        }
        boolean pressedPf8 = key == Key.PF8 && !arrivingAtNextPage;
        ca.nextPage = true;
        int n = 0;
        for (TypeRow r : rows) {
            if (n == ROWS) {
                break;
            }
            ca.rows[++n] = r;
            if (n == 1) {
                ca.firstKey = r.code();
                if (ca.page == 0) {
                    ca.page = 1;
                }
            }
        }
        if (n == ROWS) {
            ca.lastKey = ca.rows[ROWS].code();
            if (rows.size() > ROWS) {
                ca.nextPage = true;
                ca.lastKey = rows.get(ROWS).code();
            } else if (failure != null && failure.phase() == CursorFailure.Phase.LOOKAHEAD) {
                setDb2Message("C-TR-TYPE-FORWARD fetch", failure.error());
            } else {
                ca.nextPage = false;
                if (msg == null && pressedPf8) {
                    msg = NO_MORE_RECORDS;
                }
            }
        } else if (failure != null && failure.phase() == CursorFailure.Phase.FETCH) {
            // Q-COTRTLIC-08 (preserved): a fetch error inside the loop is reported as "close".
            setDb2Message("C-TR-TYPE-FORWARD close", failure.error());
        } else {
            ca.nextPage = false;
            ca.lastKey = "";
            if (msg == null && pressedPf8) {
                msg = NO_MORE_RECORDS;
            }
            if (ca.page == 1 && n == 0) {
                msg = NO_RECORDS_FOUND;
            }
        }
        if (failure != null && failure.phase() == CursorFailure.Phase.CLOSE) {
            setDb2Message("C-TR-TYPE-FORWARD close", failure.error());
        }
    }

    /** BR-COTRTLIC-06. */
    private void readBackward() {
        ca.rows = new TypeRow[ROWS + 1];
        String start = ca.firstKey;
        List<TypeRow> rows;
        CursorFailure failure = null;
        try {
            rows = store.backward(start, filter());
        } catch (CursorFailure f) {
            failure = f;
            rows = f.rows();
        }
        if (failure == null && rows.size() < ROWS) {
            // Q-COTRTLIC-07: fewer than seven rows before this page means the start was reached: show a full
            // first page instead of the SQLCODE 100 error.
            ca.page = 1;
            readForward("", false);
            return;
        }
        ca.lastKey = start;
        ca.nextPage = true;
        if (failure != null && failure.phase() == CursorFailure.Phase.OPEN) {
            setDb2Message("C-TR-TYPE-BACKWARD Open", failure.error());
            return;
        }
        int slot = ROWS;
        for (TypeRow r : rows) {
            if (slot == 0) {
                break;
            }
            ca.rows[slot--] = r;
            if (slot == 0) {
                ca.firstKey = r.code();
            }
        }
        if (failure != null && failure.phase() != CursorFailure.Phase.CLOSE) {
            setDb2Message("Error on fetch Cursor C-TR-TYPE-BACKWARD", failure.error());
        } else if (failure != null) {
            setDb2Message("C-TR-TYPE-BACKWARD close", failure.error());
        }
    }

    // ---- 9200-UPDATE-RECORD / 9300-DELETE-RECORD ----------------------------------------------------------

    private void updateRecord() {
        TypeRow row = ca.rows[iSelected];
        String text = descIn[iSelected] == null ? "" : descIn[iSelected];
        try {
            switch (store.update(row.code(), text, row.version())) {
                case DONE -> {
                    // Q-COTRTLIC-18: anything that is not an error is a success.
                    ca.updatePending = false;
                    updateCompleted = true;
                }
                case NOT_FOUND -> {
                    ca.updatePending = true;
                    setDb2Message("Record not found. Deleted by others ? ", new Db2Error(Db2Codes.NOT_FOUND, Db2Codes.NOT_FOUND_TEXT));
                }
                case CONFLICT -> {
                    ca.updatePending = true;
                    inputError = true;
                    if (msg == null) {
                        msg = DEADLOCK;
                    }
                }
            }
        } catch (Db2Failure e) {
            ca.updatePending = true;
            if (e.error().sqlcode() == Db2Codes.DEADLOCK) {
                inputError = true;
                setDb2Message("Deadlock. Someone else updating ?", e.error());
            } else {
                setDb2Message("Update failed with", e.error());
            }
        }
    }

    private void deleteRecord() {
        TypeRow row = ca.rows[iSelected];
        try {
            switch (store.delete(row.code(), null)) {
                case DONE -> {
                    ca.deletePending = false;
                    deleted = true;
                }
                default -> setDb2Message("Delete failed with message:", new Db2Error(Db2Codes.NOT_FOUND, Db2Codes.NOT_FOUND_TEXT));
            }
        } catch (Db2Failure e) {
            if (e.error().sqlcode() == Db2Codes.FK_DELETE) {
                ca.deletePending = true;
                setDb2Message("Please delete associated child records first:", e.error());
            } else {
                setDb2Message("Delete failed with message:", e.error());
            }
        }
    }

    // ---- 2000-SEND-MAP ------------------------------------------------------------------------------------

    private Result screen() {
        List<Row> rows = new ArrayList<>();
        String focus = null;
        String rowFocus = null;
        boolean oneValid = validActions == 1 && !badActions;
        for (int i = 1; i <= ROWS; i++) {
            TypeRow r = ca.rows[i];
            if (r == null) {
                rows.add(new Row("", "", "", false, false, false, false, false));
                continue;
            }
            boolean delete = sel[i] == 'D' && oneValid;
            boolean update = sel[i] == 'U' && oneValid;
            if (delete) {
                if (deleted) {
                    sel[i] = ' ';
                } else {
                    ca.deletePending = true;
                }
            }
            String description = r.description();
            if (update) {
                if (updateCompleted) {
                    sel[i] = ' ';
                } else {
                    ca.updatePending = true;
                }
                if (changesOccurred) {
                    description = rowDescCheck == TypeEdits.Check.BLANK ? "*" : descIn[i];
                }
            }
            boolean selectable = !protectRows;
            boolean selectInvalid = selectable && rowError[i];
            boolean highlighted = selectable && (delete || update);
            boolean descEditable = selectable && update && !updateCompleted;
            boolean descInvalid = descEditable && rowDescCheck != null && rowDescCheck != TypeEdits.Check.OK;
            if (rowFocus == null && selectable) {
                if (selectInvalid || delete || update && updateCompleted) {
                    rowFocus = "select" + i;
                } else if (descEditable) {
                    rowFocus = "desc" + i;
                }
            }
            rows.add(new Row(String.valueOf(sel[i]).strip(), r.code(), description, selectable, selectInvalid, highlighted,
                descEditable, descInvalid));
        }
        String typeOut = "";
        String descOut = "";
        boolean locked = false;
        if (!firstEntry) {
            if (requested > 0) {
                typeOut = inType;
                descOut = inDesc;
                locked = true;
            } else {
                typeOut = typeFlag == Flag.BLANK ? "" : inType;
                descOut = descFlag == Flag.BLANK ? "" : inDesc;
            }
        }
        boolean typeInvalid = typeFlag == Flag.NOT_OK;
        boolean descInvalid = descFlag == Flag.NOT_OK;
        if (typeInvalid || !inputError && (requested == 0 || key == Key.PF7 || key == Key.PF8)) {
            focus = "typeFilter";
        } else if (descInvalid) {
            focus = "descFilter";
        } else {
            focus = rowFocus != null ? rowFocus : "typeFilter";
        }
        String info = info();
        String shownInfo = info == null || NO_RECORDS_FOUND.equals(msg) ? null : center(info);
        Fields fields = new Fields(String.valueOf(ca.page), TypeEdits.rtrim(typeOut), TypeEdits.rtrim(descOut), locked,
            typeInvalid, descInvalid, rows, shownInfo);
        ScreenResponse<Fields> r = ScreenResponse.show(fields).withFocus(focus);
        if (msg != null) {
            r = r.error(cut(msg, MSG_LEN));
        }
        return new Result(r, false, false);
    }

    /** 2500-SETUP-MESSAGE (BR-COTRTLIC-17). */
    private String info() {
        if (deleted) {
            return INFO_DELETED;
        }
        if (updateCompleted) {
            return INFO_UPDATED;
        }
        if (typeFlag == Flag.NOT_OK || descFlag == Flag.NOT_OK) {
            return null;
        }
        if (key == Key.ENTER && deletes > 0 && requested == 1 && validActions == 1) {
            return !typeChanged && !descChanged ? INFO_DELETE : null;
        }
        if (key == Key.ENTER && updates > 0 && requested == 1 && validActions == 1) {
            return !typeChanged && !descChanged ? INFO_UPDATE : null;
        }
        // Q-COTRTLIC-20: the first-page message only when PF7 was pressed on the first page.
        if (key == Key.PF7 && ca.page == 1 && firstPageBefore) {
            msg = NO_PREVIOUS_PAGES;
            return null;
        }
        if (key == Key.PF8 && !ca.nextPage && ca.lastPageShown) {
            msg = NO_MORE_PAGES;
            return null;
        }
        if (key == Key.PF8 && !ca.nextPage) {
            ca.lastPageShown = true;
        }
        return INFO_REC_ACTIONS;
    }

    /** BR-COTRTLIC-18: centered in the 45-character info line. */
    static String center(String info) {
        String t = TypeEdits.trim(info);
        int start = (INFO_WIDTH - t.length()) / 2 + 1;
        return pad(" ".repeat(start - 1) + t, INFO_WIDTH);
    }

    // ---- 9999-FORMAT-DB2-MESSAGE --------------------------------------------------------------------------

    private void setDb2Message(String action, Db2Error e) {
        if (msg == null) {
            msg = db2Message(action, e);
        }
    }

    /** ERR-COTRTLIC-02: the action, the SQLCODE as {@code ----9}, and the database's text (Q-COTRTLIC-13). */
    static String db2Text(String action, Db2Error e) {
        return TypeEdits.trim(action) + " SQLCODE:" + Db2Codes.pic(e.sqlcode()) + " " + e.text();
    }

    private static String db2Message(String action, Db2Error e) {
        return cut(db2Text(action, e), MSG_LEN);
    }

    private static String cut(String s, int len) {
        if (s == null) {
            return "";
        }
        return s.length() > len ? s.substring(0, len) : s;
    }

    private static String pad(String s, int len) {
        return s.length() >= len ? s : s + " ".repeat(len - s.length());
    }
}
