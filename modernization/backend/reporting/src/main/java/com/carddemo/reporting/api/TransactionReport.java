package com.carddemo.reporting.api;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

/**
 * TRANREPT (CBTRN03C): the transaction detail report for one processing-date range, with per-card
 * ("Account Total"), page, and grand totals, in the 133-byte COBOL layout. Throws {@code BatchAbend} on the
 * COBOL abend paths, in which case no report file is left behind.
 */
public interface TransactionReport {

    /**
     * Writes the report. {@code accounts} limits it to transactions of cards whose cross-reference account is in
     * the set (a USER request, D-REPORT); empty means every transaction (ADMIN or an operator run).
     */
    ReportSummary write(LocalDate startDate, LocalDate endDate, Optional<Set<Long>> accounts, Path reportFile);

    record ReportSummary(int detailLines, int reportLines, BigDecimal grandTotal) {
    }
}
