package com.carddemo.refdata.web;

import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.ScreenResponse;
import com.carddemo.refdata.internal.Db2Codes;
import com.carddemo.refdata.internal.Db2Error;
import com.carddemo.refdata.internal.Db2Failure;
import com.carddemo.refdata.internal.TransactionTypeStore;
import com.carddemo.refdata.internal.TypeEdits;
import com.carddemo.refdata.internal.TypeRow;
import com.carddemo.refdata.web.MaintState.Action;
import com.carddemo.refdata.web.TranTypeMaintController.Abend;
import com.carddemo.refdata.web.TranTypeMaintController.Fields;
import com.carddemo.refdata.web.TranTypeMaintController.Keys;
import com.carddemo.refdata.web.TranTypeMaintController.Request;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One COTRTUPC turn: 0001-CHECK-PFKEYS, the reset and the main EVALUATE of 0000-MAIN, 1000-PROCESS-INPUTS,
 * 2000-DECIDE-ACTION, the writes 9600/9700/9800 and 3000-SEND-MAP. Working storage is per turn; the conversation
 * state is {@link MaintState}.
 */
final class MaintTurn {

    static final String PROMPT = "Enter transaction type to be maintained";
    static final String INFO_NOT_FOUND = "Press F05 to add. F12 to cancel";
    static final String INFO_DELETE_CONFIRM = "Delete this record ? Press F4 to confirm";
    static final String INFO_DELETED = "Delete successful.";
    static final String INFO_CHANGES = "Update transaction type details shown.";
    static final String INFO_NEW = "Enter new transaction type details.";
    static final String INFO_VALIDATED = "Changes validated.Press F5 to save";
    static final String INFO_COMMITTED = "Changes committed to database";
    static final String INFO_FAILED = "Changes unsuccessful";
    static final String INVALID_KEY = "Invalid key pressed";
    static final String NOT_FOUND = "No record found for this key in database";
    static final String NO_CHANGES = "No change detected with respect to values fetched.";
    static final String LOCK_FAILED = "Could not lock record for update";
    static final String UPDATE_CANCELLED = "Update was cancelled";
    static final String DELETE_CANCELLED = "Delete was cancelled";
    static final String CULPRIT = "COTRTUPC";
    static final String UNEXPECTED_DATA = "UNEXPECTED DATA SCENARIO";
    static final String UNEXPECTED_ABEND = "UNEXPECTED ABEND OCCURRED.";

    private static final int MSG_LEN = 75;
    private static final int INFO_CENTER = 40;
    private static final int INFO_WIDTH = 45;
    private static final int DESC_LEN = 50;

    private static final Set<Action> SHOWS_OLD =
        EnumSet.of(Action.SHOW, Action.CONFIRM_DELETE, Action.START_DELETE, Action.DELETE_FAILED, Action.BACKED_OUT);
    private static final Set<Action> ASKS_FOR_TYPE = EnumSet.of(Action.NOT_FETCHED, Action.NOT_FOUND, Action.LOOKUP_FAILED);
    private static final Set<Action> DESC_OPEN = EnumSet.of(Action.SHOW, Action.CHANGES_NOT_OK, Action.CREATE, Action.BACKED_OUT);

    private enum Flag { NONE, BLANK, VALID, NOT_OK }

    /**
     * @param response the screen, or null when the turn exits (PF3)
     * @param exit     PF3: return to the caller
     * @param ended    the conversation is over without a return (abend)
     */
    record Result(ScreenResponse<Fields> response, boolean exit, boolean ended) {
    }

    private final TransactionTypeStore store;
    private final MaintState ca;

    private Key key = Key.ENTER;
    /** CDEMO-PGM-ENTER: the empty prompt of BR-COTRTUPC-02. */
    private boolean contextEnter;
    private String msg;
    private Flag typeFlag = Flag.NONE;
    private Flag descFlag = Flag.NONE;

    MaintTurn(TransactionTypeStore store, MaintState ca) {
        this.store = store;
        this.ca = ca;
    }

    /** BR-COTRTUPC-02: extension cleared, empty fields, prompt, cursor on the type; the screen is not received. */
    Result firstEntry() {
        ca.clear();
        contextEnter = true;
        msg = null;
        typeFlag = Flag.NONE;
        descFlag = Flag.NONE;
        return screen();
    }

    Result turn(Request req) {
        key = req.key() == null ? Key.ENTER : req.key().folded();
        Action st = ca.action;
        boolean valid = validKey(st);
        if (key == Key.PF3) {
            return new Result(null, true, false);
        }
        boolean reset = key == Key.PF12 && (st == Action.SHOW || st == Action.CREATE || st == Action.NOT_FOUND)
            || st == Action.DONE || st == Action.LOCK_ERROR || st == Action.FAILED || st == Action.DELETE_DONE
            || st == Action.DELETE_FAILED || st == Action.BACKED_OUT && ca.oldTypeBlank();
        if (reset) {
            // Q-COTRTUPC-19: a type typed into the open field after a finished action is looked up, not discarded.
            boolean typeOpen = st == Action.LOCK_ERROR || st == Action.FAILED || st == Action.DELETE_DONE || st == Action.BACKED_OUT;
            if (valid && key != Key.PF12 && typeOpen && !blank(req.typeCode())) {
                ca.clear();
                return lookup(req.typeCode());
            }
            return firstEntry();
        }
        if (!valid) {
            msg = INVALID_KEY;
            return screen();
        }
        if (key == Key.PF4) {
            if (st == Action.CONFIRM_DELETE) {
                return delete();
            }
            ca.action = Action.CONFIRM_DELETE;
            return screen();
        }
        if (key == Key.PF5 && st == Action.NOT_FOUND) {
            ca.action = Action.CREATE;
            return screen();
        }
        if (key == Key.PF5 && st == Action.CHANGES_OK) {
            return save();
        }
        if (key == Key.PF12 && st == Action.CHANGES_OK) {
            msg = UPDATE_CANCELLED;
            ca.action = Action.BACKED_OUT;
            ca.newType = null;
            ca.newDesc = "";
            return screen();
        }
        if (key == Key.PF12 && st == Action.CONFIRM_DELETE) {
            msg = DELETE_CANCELLED;
            ca.clear();
            return screen();
        }
        return switch (st) {
            case NOT_FETCHED, NOT_FOUND, LOOKUP_FAILED -> lookup(req.typeCode());
            case SHOW, CHANGES_NOT_OK, BACKED_OUT, CREATE -> editChanges(req.description());
            case CHANGES_OK -> screen();
            default -> abend("0001", UNEXPECTED_DATA);
        };
    }

    /** ERR-COTRTUPC-10 (Q-COTRTUPC-08): the abend data, sent instead of the map; the conversation ends. */
    static Result abend(String code, String message) {
        Fields f = new Fields("", "", false, false, false, false, null, new Keys(false, false, false, false),
            new Abend(code, CULPRIT, "", message));
        return new Result(ScreenResponse.show(f), false, true);
    }

    /** NAV-COTRTUPC-01, without PF5 in state {@code 9} (Q-COTRTUPC-07). */
    private boolean validKey(Action st) {
        return switch (key) {
            case PF3 -> true;
            case ENTER -> st != Action.CONFIRM_DELETE;
            case PF4 -> st == Action.SHOW || st == Action.CONFIRM_DELETE;
            case PF5 -> st == Action.CHANGES_OK || st == Action.NOT_FOUND || st == Action.START_DELETE
                || st == Action.DELETE_DONE || st == Action.DELETE_FAILED;
            case PF12 -> st == Action.CHANGES_OK || st == Action.SHOW || st == Action.NOT_FOUND
                || st == Action.CONFIRM_DELETE || st == Action.CREATE;
            default -> false;
        };
    }

    // ---- 1210-EDIT-TRANTYPE and 9000-READ-TRANTYPE ---------------------------------------------------------

    /** The type edit always runs before a lookup (Q-COTRTUPC-01, -14), then BR-COTRTUPC-10. */
    private Result lookup(String rawType) {
        TypeEdits.TypeResult t = TypeEdits.typeCode(cut(rawType, 2));
        ca.clear();
        if (t.check() != TypeEdits.Check.OK) {
            typeFlag = t.check() == TypeEdits.Check.BLANK ? Flag.BLANK : Flag.NOT_OK;
            msg = TypeEdits.typeMessage(t.check());
            return screen();
        }
        typeFlag = Flag.VALID;
        ca.newType = t.code();
        Optional<TypeRow> row;
        try {
            row = store.find(t.code());
        } catch (Db2Failure e) {
            typeFlag = Flag.NOT_OK;
            msg = sqlMessage("Error accessing: TRANSACTION_TYPE table. SQLCODE:", e.error());
            ca.action = Action.LOOKUP_FAILED;
            return screen();
        }
        if (row.isEmpty()) {
            typeFlag = Flag.NOT_OK;
            msg = NOT_FOUND;
            ca.action = Action.NOT_FOUND;
            return screen();
        }
        ca.oldType = row.get().code();
        ca.oldDesc = row.get().description();
        ca.oldVersion = row.get().version();
        ca.newType = null;
        ca.action = Action.SHOW;
        return screen();
    }

    // ---- 1205-COMPARE-OLD-NEW and the description edit -----------------------------------------------------

    /** BR-COTRTUPC-07..09: the type comes from the session (Q-COTRTUPC-21), only the description is received. */
    private Result editChanges(String rawDesc) {
        Action st = ca.action;
        String type = st == Action.SHOW || st == Action.BACKED_OUT ? ca.oldType : ca.newType;
        String desc = receivedDescription(rawDesc);
        typeFlag = Flag.VALID;
        ca.newType = type;
        ca.newDesc = desc;
        boolean noChange = Objects.equals(upper(type), upper(ca.oldType))
            && upper(desc).equals(upper(TypeEdits.trim(ca.oldDesc)));
        if (noChange) {
            msg = NO_CHANGES;
            descFlag = Flag.VALID;
            if (st == Action.BACKED_OUT) {
                ca.action = Action.CHANGES_NOT_OK;
            }
            return screen();
        }
        ca.action = Action.CHANGES_NOT_OK;
        TypeEdits.Check c = TypeEdits.description(desc);
        if (c != TypeEdits.Check.OK) {
            descFlag = c == TypeEdits.Check.BLANK ? Flag.BLANK : Flag.NOT_OK;
            msg = TypeEdits.descriptionMessage(c);
            return screen();
        }
        descFlag = Flag.VALID;
        ca.action = Action.CHANGES_OK;
        return screen();
    }

    // ---- 9600-WRITE-PROCESSING, 9700-INSERT-RECORD ---------------------------------------------------------

    /**
     * BR-COTRTUPC-17: an add (no row was read) inserts; an update applies only while the row is unchanged since it
     * was read and never inserts (Q-COTRTUPC-17). A failure reaches state {@code F} (Q-COTRTUPC-02) with the SQLCODE
     * of the failing statement (Q-COTRTUPC-04).
     */
    private Result save() {
        boolean add = ca.oldTypeBlank();
        try {
            if (add) {
                store.insert(ca.newType, ca.newDesc);
                ca.action = Action.DONE;
            } else if (store.update(ca.oldType, ca.newDesc, ca.oldVersion) == TransactionTypeStore.Change.DONE) {
                ca.action = Action.DONE;
            } else {
                msg = LOCK_FAILED;
                ca.action = Action.LOCK_ERROR;
            }
        } catch (Db2Failure e) {
            if (add) {
                msg = sqlMessage("Error inserting record into: TRANSACTION_TYPE Table. SQLCODE:", e.error());
                ca.action = Action.FAILED;
            } else if (e.error().sqlcode() == Db2Codes.DEADLOCK) {
                msg = LOCK_FAILED;
                ca.action = Action.LOCK_ERROR;
            } else {
                msg = sqlMessage("Error updating: TRANSACTION_TYPE Table. SQLCODE:", e.error());
                ca.action = Action.FAILED;
            }
        }
        return screen();
    }

    // ---- 9800-DELETE-PROCESSING ----------------------------------------------------------------------------

    /** BR-COTRTUPC-15 by the session's OLD type; any failure reaches state {@code 6} (Q-COTRTUPC-06). */
    private Result delete() {
        ca.action = Action.START_DELETE;
        try {
            if (store.delete(ca.oldType, null) == TransactionTypeStore.Change.DONE) {
                // Q-COTRTUPC-19: the deleted row is no longer shown.
                ca.clear();
                ca.action = Action.DELETE_DONE;
            } else {
                msg = sqlMessage("Delete failed with message:SQLCODE :", new Db2Error(Db2Codes.NOT_FOUND, ""));
                ca.action = Action.DELETE_FAILED;
            }
        } catch (Db2Failure e) {
            String prefix = e.error().sqlcode() == Db2Codes.FK_DELETE
                ? "Please delete associated child records first:SQLCODE :" : "Delete failed with message:SQLCODE :";
            msg = sqlMessage(prefix, e.error());
            ca.action = Action.DELETE_FAILED;
        }
        return screen();
    }

    /** The prefix, the SQLCODE as {@code ----9}, and the error text once without length bytes (Q-COTRTUPC-03). */
    static String sqlMessage(String prefix, Db2Error e) {
        return cut(prefix + Db2Codes.pic(e.sqlcode()) + ":" + e.text(), MSG_LEN);
    }

    // ---- 3000-SEND-MAP -------------------------------------------------------------------------------------

    private Result screen() {
        Action st = ca.action;
        boolean blankOldB = st == Action.BACKED_OUT && ca.oldTypeBlank();
        String type = "";
        String desc = "";
        if (!contextEnter && st != Action.NOT_FETCHED && st != Action.DELETE_DONE) {
            if (SHOWS_OLD.contains(st)) {
                type = nz(ca.oldType);
                desc = nz(ca.oldDesc);
            } else {
                type = nz(ca.newType);
                desc = nz(ca.newDesc);
            }
        }
        boolean typeOpen = contextEnter || ASKS_FOR_TYPE.contains(st) || st == Action.LOCK_ERROR || st == Action.FAILED
            || st == Action.DELETE_DONE || blankOldB;
        boolean descOpen = !contextEnter && DESC_OPEN.contains(st);
        boolean typeStar = !contextEnter && typeFlag == Flag.BLANK;
        if (typeStar) {
            type = "*";
        }
        boolean typeInvalid = typeFlag == Flag.NOT_OK || typeStar || !contextEnter && st == Action.DELETE_FAILED;
        boolean descColored = !contextEnter && !ASKS_FOR_TYPE.contains(st) && typeFlag != Flag.BLANK && typeFlag != Flag.NOT_OK;
        boolean descInvalid = descColored && (descFlag == Flag.NOT_OK || descFlag == Flag.BLANK);
        if (descColored && descFlag == Flag.BLANK) {
            desc = "*";
        }
        String focus;
        if (contextEnter || ASKS_FOR_TYPE.contains(st) || typeFlag == Flag.NOT_OK || typeFlag == Flag.BLANK
            || st == Action.DONE || blankOldB) {
            focus = "typeCode";
        } else if (st == Action.CREATE || NO_CHANGES.equals(msg) || descFlag == Flag.NOT_OK || descFlag == Flag.BLANK
            || EnumSet.of(Action.CHANGES_NOT_OK, Action.CHANGES_OK, Action.LOCK_ERROR, Action.FAILED, Action.BACKED_OUT,
                Action.SHOW).contains(st)) {
            focus = "description";
        } else {
            focus = "typeCode";
        }
        Keys keys = contextEnter ? new Keys(true, false, false, false)
            : new Keys(st != Action.CONFIRM_DELETE,
                st == Action.SHOW || st == Action.CONFIRM_DELETE,
                st == Action.CHANGES_OK || st == Action.NOT_FOUND,
                EnumSet.of(Action.CHANGES_OK, Action.SHOW, Action.NOT_FOUND, Action.CONFIRM_DELETE, Action.CREATE).contains(st));
        Fields f = new Fields(TypeEdits.rtrim(type), TypeEdits.rtrim(desc), typeOpen, descOpen, typeInvalid, descInvalid,
            center(info(st, blankOldB)), keys, null);
        ScreenResponse<Fields> r = ScreenResponse.show(f).withFocus(focus);
        if (msg != null) {
            r = r.error(cut(msg, MSG_LEN));
        }
        return new Result(r, false, false);
    }

    /** 3250-SETUP-INFOMSG (BR-COTRTUPC-21), with {@code S} showing the update prompt (Q-COTRTUPC-18). */
    private String info(Action st, boolean blankOldB) {
        if (contextEnter || blankOldB) {
            return PROMPT;
        }
        return switch (st) {
            case NOT_FOUND -> INFO_NOT_FOUND;
            case SHOW, BACKED_OUT, CHANGES_NOT_OK -> INFO_CHANGES;
            case CONFIRM_DELETE -> INFO_DELETE_CONFIRM;
            case DELETE_FAILED, LOCK_ERROR, FAILED -> INFO_FAILED;
            case DELETE_DONE -> INFO_DELETED;
            case CREATE -> INFO_NEW;
            case CHANGES_OK -> INFO_VALIDATED;
            case DONE -> INFO_COMMITTED;
            default -> PROMPT;
        };
    }

    /** BR-COTRTUPC-22: centered on 40 columns, then placed in the 45-character info line. */
    static String center(String info) {
        String t = TypeEdits.trim(info);
        int start = (INFO_CENTER - t.length()) / 2 + 1;
        return pad(" ".repeat(start - 1) + t, INFO_WIDTH);
    }

    /** 1150-STORE-MAP-IN-NEW: {@code *} or spaces is no input; otherwise the trimmed text. */
    private static String receivedDescription(String raw) {
        String t = TypeEdits.trim(cut(raw, DESC_LEN));
        return t.equals("*") ? "" : t;
    }

    private static boolean blank(String s) {
        String t = TypeEdits.trim(s);
        return t.isEmpty() || t.equals("*");
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
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
