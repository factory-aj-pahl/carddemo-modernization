package com.carddemo.cycle.api;

import java.nio.file.Path;
import java.time.LocalDate;

/** CREASTMT (CBSTM03A/CBSTM03B): text and HTML statements for the current cycle's transactions (D-HISTORY). */
public interface StatementCycle {

    /**
     * Covers every committed transaction no earlier run covered, writes one statement per cross-reference card,
     * and records the run, all in one database transaction. Throws {@code BatchAbend} on the abend paths, in
     * which case nothing is marked and no output file is left behind.
     */
    StatementRun createStatements(LocalDate businessDate, Path statementFile, Path htmlFile);

    record StatementRun(long runId, int statements, int transactions) {
    }
}
