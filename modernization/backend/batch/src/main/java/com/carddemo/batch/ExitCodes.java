package com.carddemo.batch;

/** Process exit codes of the batch jar (ledger-batch-data.md conventions B-ABEND and B-DATE). */
public final class ExitCodes {

    public static final int OK = 0;
    /** CBTRN02C RETURN-CODE 4: at least one record was rejected. */
    public static final int WARNING = 4;
    /** The run was refused because it already completed. */
    public static final int REFUSED = 8;
    /** B-DATE: missing or invalid business date. */
    public static final int INVALID_DATE = 12;
    /** Unknown command or missing parameter. */
    public static final int USAGE = 16;
    /** B-ABEND: the COBOL would have abended (U999). */
    public static final int ABEND = 99;

    private ExitCodes() {
    }
}
