package com.carddemo.reporting.internal;

import com.carddemo.card.api.CardDirectory;
import com.carddemo.card.api.CardXref;
import com.carddemo.common.batch.BatchAbend;
import com.carddemo.common.batch.JobLog;
import com.carddemo.common.batch.UnlabeledAbend;
import com.carddemo.common.codec.FixedWidth;
import com.carddemo.common.format.ReportPictures;
import com.carddemo.common.mask.Masking;
import com.carddemo.reporting.api.TransactionReport;
import com.carddemo.refdata.api.TransactionCodeNames;
import com.carddemo.transaction.api.TransactionQueries;
import com.carddemo.transaction.api.TransactionRecord;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * CBTRN03C with RP-CBTRN03C-1..3 behaviour (spec/programs/CBTRN03C.md): out-of-range rows are not selected
 * (Q-CBTRN03C-01), the last amount is not added twice (Q-CBTRN03C-02), the last card's total is printed
 * (Q-CBTRN03C-03), raw records are masked (Q-CBTRN03C-08), and totals that no longer fit their picture
 * abend (Q-CBTRN03C-10). The page-break rule is the COBOL line counter (Q-CBTRN03C-04).
 */
@Service
class TransactionReportService implements TransactionReport {

    static final int WIDTH = 133;
    static final int PAGE_SIZE = 20;

    private final TransactionQueries transactions;
    private final CardDirectory cards;
    private final TransactionCodeNames names;
    private final JobLog log;

    TransactionReportService(TransactionQueries transactions, CardDirectory cards, TransactionCodeNames names, JobLog log) {
        this.transactions = transactions;
        this.cards = cards;
        this.names = names;
        this.log = log;
    }

    @Override
    public ReportSummary write(LocalDate startDate, LocalDate endDate, Optional<Set<Long>> accounts, Path reportFile) {
        log.display("START OF EXECUTION OF PROGRAM CBTRN03C");
        log.display("Reporting from " + startDate + " to " + endDate);
        Path temp = reportFile.resolveSibling(reportFile.getFileName() + ".part");
        Run run;
        try (BufferedWriter w = Files.newBufferedWriter(temp, StandardCharsets.ISO_8859_1)) {
            run = new Run(w, startDate, endDate);
            for (TransactionRecord t : selected(startDate, endDate)) {
                Optional<CardXref> xref = run.xrefFor(t.cardNum());
                if (accounts.isPresent() && (xref.isEmpty() || !accounts.get().contains(xref.get().accountId()))) {
                    continue;
                }
                run.detail(t, xref);
            }
            run.finish();
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new BatchAbend("ERROR WRITING REPTFILE", null, e);
        } catch (ReportPictures.PictureOverflow e) {
            deleteQuietly(temp);
            throw new UnlabeledAbend("report total does not fit its picture (Q-CBTRN03C-10): " + e.getMessage(), e);
        } catch (RuntimeException e) {
            deleteQuietly(temp);
            throw e;
        }
        try {
            Files.move(temp, reportFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new BatchAbend("ERROR CLOSING REPORT FILE", null, e);
        }
        log.display("END OF EXECUTION OF PROGRAM CBTRN03C");
        return new ReportSummary(run.details, run.lines, run.grandTotal);
    }

    private java.util.List<TransactionRecord> selected(LocalDate start, LocalDate end) {
        try {
            return transactions.processedBetween(start, end);
        } catch (DataAccessException e) {
            throw new BatchAbend("ERROR READING TRANSACTION FILE", null, e);
        }
    }

    /** The state of one report run (WS-LINE-COUNTER, the totals, the current card). */
    private final class Run {
        private final BufferedWriter out;
        private final String nameHeader;
        private boolean firstTime = true;
        private long lineCounter;
        private BigDecimal pageTotal = BigDecimal.ZERO.setScale(2);
        private BigDecimal accountTotal = BigDecimal.ZERO.setScale(2);
        private BigDecimal grandTotal = BigDecimal.ZERO.setScale(2);
        private BigDecimal lastAmount = BigDecimal.ZERO.setScale(2);
        private String currentCard;
        private CardXref currentXref;
        private int details;
        private int lines;

        Run(BufferedWriter out, LocalDate start, LocalDate end) {
            this.out = out;
            this.nameHeader = FixedWidth.x("DALYREPT", 38) + FixedWidth.x("Daily Transaction Report", 41) + "Date Range: "
                + start + " to " + end;
        }

        /** 1500-A-LOOKUP-XREF, only when the card changes (BR-CBTRN03C-02). */
        Optional<CardXref> xrefFor(String cardNum) {
            if (cardNum.equals(currentCard) && currentXref != null) {
                return Optional.of(currentXref);
            }
            try {
                return cards.findXref(cardNum);
            } catch (DataAccessException e) {
                throw new BatchAbend("ERROR READING CROSS REF FILE", null, e);
            }
        }

        void detail(TransactionRecord t, Optional<CardXref> xref) throws IOException {
            lastAmount = t.amount();
            // Q-CBTRN03C-08 / B-MASK: bytes 263-274 of the displayed record.
            String rec = t.legacy();
            log.display(rec.substring(0, 262) + "*".repeat(12) + rec.substring(274));
            if (!t.cardNum().equals(currentCard)) {
                if (!firstTime) {
                    accountTotals();
                }
                currentCard = t.cardNum();
                currentXref = xref.orElseThrow(() -> new BatchAbend("INVALID CARD NUMBER : " + Masking.card(t.cardNum()), "23"));
            }
            String typeDesc = lookup(() -> names.typeDescription(t.typeCd()), "INVALID TRANSACTION TYPE : " + t.typeCd());
            String catDesc = lookup(() -> names.categoryDescription(t.typeCd(), t.catCd()),
                "INVALID TRAN CATG KEY : " + FixedWidth.x(t.typeCd(), 2) + FixedWidth.n(t.catCd(), 4));
            if (firstTime) {
                firstTime = false;
                headers();
            }
            if (lineCounter % PAGE_SIZE == 0) {
                pageTotals();
                headers();
            }
            pageTotal = pageTotal.add(t.amount());
            accountTotal = accountTotal.add(t.amount());
            line(FixedWidth.x(t.tranId(), 16) + " " + FixedWidth.n(currentXref.accountId(), 11) + " " + FixedWidth.x(t.typeCd(), 2)
                + "-" + FixedWidth.x(typeDesc, 15) + " " + FixedWidth.n(t.catCd(), 4) + "-" + FixedWidth.x(catDesc, 29) + " "
                + FixedWidth.x(t.source(), 10) + "    " + ReportPictures.commaAmount(t.amount(), '-') + "  ");
            lineCounter++;
            details++;
        }

        private String lookup(java.util.function.Supplier<Optional<String>> read, String notFound) {
            Optional<String> v;
            try {
                v = read.get();
            } catch (DataAccessException e) {
                throw new UnlabeledAbend("reference data read failed", e);
            }
            return v.orElseThrow(() -> new BatchAbend(notFound, "23"));
        }

        /** The end-of-file branch with RP-CBTRN03C-2: no second add; the last card's total is printed. */
        void finish() throws IOException {
            log.display("TRAN-AMT " + ReportPictures.display(lastAmount, 9, 2));
            log.display("WS-PAGE-TOTAL" + ReportPictures.display(pageTotal, 9, 2));
            if (!firstTime) {
                accountTotals();
            }
            pageTotals();
            line(FixedWidth.x("Grand Total", 11) + ".".repeat(86) + ReportPictures.commaAmount(grandTotal, '+'));
        }

        private void headers() throws IOException {
            line(nameHeader);
            line("");
            line(FixedWidth.x("Transaction ID", 17) + FixedWidth.x("Account ID", 12) + FixedWidth.x("Transaction Type", 19)
                + FixedWidth.x("Tran Category", 35) + FixedWidth.x("Tran Source", 14) + " " + FixedWidth.x("        Amount", 16));
            line("-".repeat(WIDTH));
            lineCounter += 4;
        }

        private void pageTotals() throws IOException {
            line(FixedWidth.x("Page Total", 11) + ".".repeat(86) + ReportPictures.commaAmount(pageTotal, '+'));
            grandTotal = grandTotal.add(pageTotal);
            pageTotal = BigDecimal.ZERO.setScale(2);
            line("-".repeat(WIDTH));
            lineCounter += 2;
        }

        private void accountTotals() throws IOException {
            line(FixedWidth.x("Account Total", 13) + ".".repeat(84) + ReportPictures.commaAmount(accountTotal, '+'));
            accountTotal = BigDecimal.ZERO.setScale(2);
            line("-".repeat(WIDTH));
            lineCounter += 2;
        }

        private void line(String text) throws IOException {
            out.write(FixedWidth.x(text, WIDTH));
            out.write('\n');
            lines++;
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            System.err.println("could not delete " + p + ": " + e);
        }
    }
}
