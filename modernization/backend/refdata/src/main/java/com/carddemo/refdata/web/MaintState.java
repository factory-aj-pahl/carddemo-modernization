package com.carddemo.refdata.web;

import java.io.Serializable;

/**
 * Server-side state of the COTRTUPC conversation (TTUP-CHANGE-ACTION and the OLD/NEW details of
 * WS-THIS-PROGCOMMAREA), kept in the HTTP session. Keys for the save and the delete come from here, never from
 * the screen (Q-COTRTUPC-21).
 */
final class MaintState implements Serializable {

    private static final long serialVersionUID = 1L;

    /** TTUP-CHANGE-ACTION values that the port can reach; {@code K}, {@code V} and {@code 8} are not kept (Q-COTRTUPC-15). */
    enum Action {
        /** LOW-VALUES or space: waiting for a type code. */
        NOT_FETCHED,
        /** {@code K}, reused for a failed lookup: the type is shown in error and no add is offered (Q-COTRTUPC-09). */
        LOOKUP_FAILED,
        /** {@code X}. */
        NOT_FOUND,
        /** {@code S}. */
        SHOW,
        /** {@code R}. */
        CREATE,
        /** {@code 9}. */
        CONFIRM_DELETE,
        /** {@code 8}: only while the DELETE runs; a stored {@code 8} is an unexpected state (ERR-COTRTUPC-09). */
        START_DELETE,
        /** {@code 7}. */
        DELETE_DONE,
        /** {@code 6}, also after a child-row failure (Q-COTRTUPC-06). */
        DELETE_FAILED,
        /** {@code E}. */
        CHANGES_NOT_OK,
        /** {@code N}. */
        CHANGES_OK,
        /** {@code L}. */
        LOCK_ERROR,
        /** {@code F} (Q-COTRTUPC-02). */
        FAILED,
        /** {@code C}. */
        DONE,
        /** {@code B}. */
        BACKED_OUT
    }

    Action action = Action.NOT_FETCHED;
    /** TTUP-OLD-TTYP-TYPE: null when no row was read (add mode). */
    String oldType;
    String oldDesc = "";
    /** Row version read with the OLD details; the save applies only while it is unchanged (Q-COTRTUPC-17). */
    long oldVersion;
    /** TTUP-NEW-TTYP-TYPE, normalized to two digits. */
    String newType;
    /** TTUP-NEW-TTYP-TYPE-DESC, trimmed; empty when blank. */
    String newDesc = "";

    boolean oldTypeBlank() {
        return oldType == null;
    }

    /** First entry (BR-COTRTUPC-02): the extension is initialized. */
    void clear() {
        action = Action.NOT_FETCHED;
        oldType = null;
        oldDesc = "";
        oldVersion = 0;
        newType = null;
        newDesc = "";
    }
}
