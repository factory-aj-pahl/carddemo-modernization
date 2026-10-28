package com.carddemo.transaction.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Published batch reads and statement marks over the transaction table. */
public interface TransactionQueries {

    /**
     * TRANREPT selection: transactions whose processing date (parsed TRAN-PROC-TS, Q-ENT-08) lies in
     * [start, end], ordered by card number, then transaction ID (the SORT of JOB-TRANREPT is stable over
     * the key-ordered unload). Rows without a processing timestamp are never selected (Q-CBTRN03C-11).
     */
    List<TransactionRecord> processedBetween(LocalDate start, LocalDate end);

    /**
     * D-HISTORY / Q-CREASTMT-04: marks every committed row that no statement run covers yet as covered by
     * the given run and returns how many were marked. Must run in the transaction that records the run.
     */
    int coverUncovered(long statementRunId);

    /** The rows covered by a statement run, ordered by card number, then transaction ID (TRXFL key order). */
    List<TransactionRecord> coveredBy(long statementRunId);

    /** The highest transaction ID that starts with the prefix, if any. */
    Optional<String> highestIdWithPrefix(String prefix);
}
