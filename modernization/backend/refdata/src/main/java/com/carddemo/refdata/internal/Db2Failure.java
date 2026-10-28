package com.carddemo.refdata.internal;

/** A failed single SQL statement, carrying the Db2-style SQLCODE and the database's message text. */
public class Db2Failure extends RuntimeException {

    private final Db2Error error;

    public Db2Failure(Db2Error error, Throwable cause) {
        super("SQLCODE " + error.sqlcode() + ": " + error.text(), cause);
        this.error = error;
    }

    public Db2Error error() {
        return error;
    }
}
