package com.carddemo.refdata.internal;

import java.util.List;

/** A list read that failed part way: where it failed, the rows read before, and the error (ERR-COTRTLIC-04 to -08). */
public class CursorFailure extends RuntimeException {

    /** OPEN, FETCH inside the page, FETCH of the look-ahead row after a full page, CLOSE. */
    public enum Phase { OPEN, FETCH, LOOKAHEAD, CLOSE }

    private final Phase phase;
    private final transient List<TypeRow> rows;
    private final Db2Error error;

    public CursorFailure(Phase phase, List<TypeRow> rows, Db2Error error) {
        super(phase + " failed: " + error.text());
        this.phase = phase;
        this.rows = List.copyOf(rows);
        this.error = error;
    }

    public Phase phase() {
        return phase;
    }

    public List<TypeRow> rows() {
        return rows;
    }

    public Db2Error error() {
        return error;
    }
}
