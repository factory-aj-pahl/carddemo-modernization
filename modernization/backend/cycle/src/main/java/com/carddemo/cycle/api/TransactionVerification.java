package com.carddemo.cycle.api;

import java.nio.file.Path;

/** TRANVERIFY (CBTRN01C, Q-CBTRN01C-06): read-only check of a daily file's cards and accounts, SYSOUT only. */
public interface TransactionVerification {

    /** Returns the number of records read. Throws {@code BatchAbend} on the COBOL abend paths. */
    long verify(Path dailyFile);
}
