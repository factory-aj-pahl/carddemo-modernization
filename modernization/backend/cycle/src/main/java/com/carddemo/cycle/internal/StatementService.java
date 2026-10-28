package com.carddemo.cycle.internal;

import com.carddemo.account.api.CycleAccounts;
import com.carddemo.account.api.StatementAccount;
import com.carddemo.account.api.StatementCustomer;
import com.carddemo.card.api.CardXref;
import com.carddemo.card.api.CardXrefListing;
import com.carddemo.common.batch.BatchAbend;
import com.carddemo.common.batch.UnlabeledAbend;
import com.carddemo.common.codec.FixedWidth;
import com.carddemo.common.format.ReportPictures;
import com.carddemo.cycle.api.StatementCycle;
import com.carddemo.transaction.api.TransactionQueries;
import com.carddemo.transaction.api.TransactionRecord;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * CBSTM03A/CBSTM03B with RP-CBSTM03A-1..4 behaviour (spec/programs/CBSTM03A.md): one text and one HTML
 * statement per cross-reference card in card order, covering the transactions of the current cycle
 * (Q-CREASTMT-04). No table limits (Q-CBSTM03A-04), full names and cities (Q-CBSTM03A-07), statements without
 * transactions when there are none (Q-CBSTM03A-03), HTML values escaped (Q-CBSTM03A-10), and values that no
 * longer fit their picture abend (Q-CBSTM03A-09).
 */
@Service
class StatementService implements StatementCycle {

    static final int TEXT_WIDTH = 80;
    static final int HTML_WIDTH = 100;
    private static final BigDecimal BILLION = BigDecimal.TEN.pow(9);

    private final JdbcClient jdbc;
    private final TransactionQueries transactions;
    private final CardXrefListing xrefs;
    private final CycleAccounts accounts;
    private final Clock clock;

    StatementService(JdbcClient jdbc, TransactionQueries transactions, CardXrefListing xrefs, CycleAccounts accounts,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.xrefs = xrefs;
        this.accounts = accounts;
        this.clock = clock;
    }

    /** The CBSTM03A error DISPLAY pair: message, then {@code RETURN CODE: } + the file status. */
    static final class StatementAbend extends BatchAbend {
        private final String status;

        StatementAbend(String message, String status, Throwable cause) {
            super(message, null, cause);
            this.status = status;
        }

        @Override
        public List<String> sysoutLines() {
            return status == null ? List.of(getMessage()) : List.of(getMessage(), "RETURN CODE: " + status);
        }
    }

    @Override
    @Transactional
    public StatementRun createStatements(LocalDate businessDate, Path statementFile, Path htmlFile) {
        // Q-CBSTM03A-01: the configured locations instead of the TIOT walk; stderr keeps SYSOUT identical.
        System.err.println("CREASTMT " + businessDate + ": transactions not yet covered by a statement run, card_xref,"
            + " customer, account; STMTFILE=" + statementFile.toAbsolutePath() + " HTMLFILE=" + htmlFile.toAbsolutePath());
        long runId;
        Map<String, List<TransactionRecord>> byCard = new LinkedHashMap<>();
        List<CardXref> cards;
        int covered;
        try {
            runId = jdbc.sql("""
                    INSERT INTO statement_run (business_date, started_at, transaction_count, statement_count)
                    OUTPUT INSERTED.statement_run_id VALUES (?, ?, 0, 0)
                    """)
                .params(businessDate, LocalDateTime.now(clock)).query(Long.class).single();
            covered = transactions.coverUncovered(runId);
            for (TransactionRecord t : transactions.coveredBy(runId)) {
                byCard.computeIfAbsent(t.cardNum(), k -> new ArrayList<>()).add(t);
            }
        } catch (DataAccessException e) {
            throw new StatementAbend("ERROR READING TRNXFILE", null, e);
        }
        try {
            cards = xrefs.allByCardNumber();
        } catch (DataAccessException e) {
            throw new StatementAbend("ERROR READING XREFFILE", null, e);
        }
        Path stmtTemp = part(statementFile);
        Path htmlTemp = part(htmlFile);
        int statements = 0;
        try (BufferedWriter stmt = Files.newBufferedWriter(stmtTemp, StandardCharsets.ISO_8859_1);
                BufferedWriter html = Files.newBufferedWriter(htmlTemp, StandardCharsets.ISO_8859_1)) {
            Writer w = new Writer(stmt, html);
            for (CardXref x : cards) {
                StatementCustomer customer = customer(x.customerId());
                StatementAccount account = account(x.accountId());
                w.statement(customer, account, byCard.getOrDefault(x.cardNum(), List.of()));
                statements++;
            }
        } catch (IOException e) {
            deleteQuietly(stmtTemp, htmlTemp);
            throw new UnlabeledAbend("statement file write failed (ERR-CBSTM03A-09)", e);
        } catch (ReportPictures.PictureOverflow e) {
            deleteQuietly(stmtTemp, htmlTemp);
            throw new UnlabeledAbend("statement value does not fit its picture (Q-CBSTM03A-09): " + e.getMessage(), e);
        } catch (RuntimeException e) {
            deleteQuietly(stmtTemp, htmlTemp);
            throw e;
        }
        jdbc.sql("UPDATE statement_run SET transaction_count = ?, statement_count = ? WHERE statement_run_id = ?")
            .params(covered, statements, runId).update();
        publishOnCommit(stmtTemp, statementFile, htmlTemp, htmlFile);
        return new StatementRun(runId, statements, covered);
    }

    /**
     * The files appear only with the committed run: they are moved into place just before the commit, and
     * removed again if the commit fails (the COBOL DD has DISP=(NEW,CATLG,DELETE)).
     */
    private static void publishOnCommit(Path stmtTemp, Path stmtFile, Path htmlTemp, Path htmlFile) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                try {
                    Files.move(stmtTemp, stmtFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    Files.move(htmlTemp, htmlFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(stmtTemp, htmlTemp, stmtFile, htmlFile);
                }
            }
        });
    }

    private StatementCustomer customer(long customerId) {
        Optional<StatementCustomer> c;
        try {
            c = accounts.statementCustomer(customerId);
        } catch (DataAccessException e) {
            throw new StatementAbend("ERROR READING CUSTFILE", null, e);
        }
        return c.orElseThrow(() -> new StatementAbend("ERROR READING CUSTFILE", "23", null));
    }

    private StatementAccount account(long accountId) {
        Optional<StatementAccount> a;
        try {
            a = accounts.statementAccount(accountId);
        } catch (DataAccessException e) {
            throw new StatementAbend("ERROR READING ACCTFILE", null, e);
        }
        return a.orElseThrow(() -> new StatementAbend("ERROR READING ACCTFILE", "23", null));
    }

    /** 5000-CREATE-STATEMENT, 4000-TRNXFILE-GET, 5100/5200/6000 HTML paragraphs. */
    private static final class Writer {
        private final BufferedWriter stmt;
        private final BufferedWriter html;

        Writer(BufferedWriter stmt, BufferedWriter html) {
            this.stmt = stmt;
            this.html = html;
        }

        void statement(StatementCustomer c, StatementAccount a, List<TransactionRecord> trans) throws IOException {
            String name = FixedWidth.x(name(c), 75);
            String add1 = FixedWidth.x(c.addrLine1(), 50);
            String add2 = FixedWidth.x(c.addrLine2(), 50);
            String add3 = FixedWidth.x(addressLine3(c), 80);
            String acctId = FixedWidth.x(FixedWidth.n(a.accountId(), 11), 20);
            String balance = ReportPictures.nineAmount(a.currentBalance());
            String fico = FixedWidth.x(FixedWidth.n(c.ficoScore(), 3), 20);
            String dashes = "-".repeat(TEXT_WIDTH);

            text("*".repeat(31) + "START OF STATEMENT" + "*".repeat(31));
            htmlHeader(acctId);
            htmlNameAndDetails(name, add1, add2, add3, acctId, balance, fico);
            text(name);
            text(add1);
            text(add2);
            text(add3);
            text(dashes);
            text(" ".repeat(33) + "Basic Details");
            text(dashes);
            text("Account ID         :" + acctId);
            text("Current Balance    :" + balance);
            text("FICO Score         :" + fico);
            text(dashes);
            text(" ".repeat(30) + "TRANSACTION SUMMARY ");
            text(dashes);
            text(FixedWidth.x("Tran ID", 16) + FixedWidth.x("Tran Details", 51) + "  Tran Amount");
            text(dashes);

            BigDecimal total = BigDecimal.ZERO.setScale(2);
            for (TransactionRecord t : trans) {
                String tranId = FixedWidth.x(t.tranId(), 16);
                String desc = FixedWidth.x(t.description(), 49);
                String amount = ReportPictures.zAmount(t.amount());
                text(tranId + " " + desc + "$" + amount);
                htmlTransaction(tranId, desc, amount);
                total = total.add(t.amount());
                if (total.abs().compareTo(BILLION) >= 0) {
                    // WS-TOTAL-AMT is S9(9)V99: the COBOL ADD would drop the high-order digits here.
                    throw new ReportPictures.PictureOverflow("S9(9)V99", total);
                }
            }
            text(dashes);
            text("Total EXP:" + " ".repeat(56) + "$" + ReportPictures.zAmount(total));
            text("*".repeat(32) + "END OF STATEMENT" + "*".repeat(32));
            for (String l : List.of("<tr>", "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">",
                    "<h3>End of Statement</h3>", "</td>", "</tr>", "</table>", "</body>", "</html>")) {
                htmlLine(l);
            }
        }

        private void htmlHeader(String acctId) throws IOException {
            for (String l : List.of("<!DOCTYPE html>", "<html lang=\"en\">", "<head>", "<meta charset=\"utf-8\">",
                    "<title>HTML Table Layout</title>", "</head>", "<body style=\"margin:0px;\">",
                    "<table  align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">", "<tr>",
                    "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">")) {
                htmlLine(l);
            }
            htmlLine("<h3>Statement for Account Number: " + escape(acctId) + "</h3>");
            for (String l : List.of("</td>", "</tr>", "<tr>", "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">",
                    "<p style=\"font-size:16px\">Bank of XYZ</p>", "<p>410 Terry Ave N</p>", "<p>Seattle WA 99999</p>", "</td>",
                    "</tr>", "<tr>", GREY_CELL)) {
                htmlLine(l);
            }
        }

        private static final String GREY_CELL = "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">";
        private static final String GREEN_CELL =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">";

        private void htmlNameAndDetails(String name, String add1, String add2, String add3, String acctId, String balance,
                String fico) throws IOException {
            htmlLine("<p style=\"font-size:16px\">" + escape(beforeDoubleSpace(name.substring(0, 50))) + "  </p>");
            htmlLine("<p>" + escape(beforeDoubleSpace(add1)) + "  </p>");
            htmlLine("<p>" + escape(beforeDoubleSpace(add2)) + "  </p>");
            htmlLine("<p>" + escape(beforeDoubleSpace(add3)) + "  </p>");
            for (String l : List.of("</td>", "</tr>", "<tr>", GREEN_CELL, "<p style=\"font-size:16px\">Basic Details</p>", "</td>",
                    "</tr>", "<tr>", GREY_CELL)) {
                htmlLine(l);
            }
            htmlLine("<p>Account ID         : " + escape(beforeStar(acctId)) + "</p>");
            htmlLine("<p>Current Balance    : " + escape(beforeStar(balance)) + "</p>");
            htmlLine("<p>FICO Score         : " + escape(beforeStar(fico)) + "</p>");
            for (String l : List.of("</td>", "</tr>", "<tr>", GREEN_CELL, "<p style=\"font-size:16px\">Transaction Summary</p>",
                    "</td>", "</tr>", "<tr>",
                    "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">",
                    "<p style=\"font-size:16px\">Tran ID</p>", "</td>",
                    "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">",
                    "<p style=\"font-size:16px\">Tran Details</p>", "</td>",
                    "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">",
                    "<p style=\"font-size:16px\">Amount</p>", "</td>", "</tr>")) {
                htmlLine(l);
            }
        }

        private void htmlTransaction(String tranId, String desc, String amount) throws IOException {
            htmlLine("<tr>");
            htmlLine("<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">");
            htmlLine("<p>" + escape(beforeStar(tranId)) + "</p>");
            htmlLine("</td>");
            htmlLine("<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">");
            htmlLine("<p>" + escape(beforeStar(desc)) + "</p>");
            htmlLine("</td>");
            htmlLine("<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">");
            htmlLine("<p>" + escape(beforeStar(amount)) + "</p>");
            htmlLine("</td>");
            htmlLine("</tr>");
        }

        private void text(String line) throws IOException {
            stmt.write(FixedWidth.x(line, TEXT_WIDTH));
            stmt.write('\n');
        }

        /**
         * One HTMLFILE record, padded to 100. Unescaped lines never exceed 100 bytes; a line that escaping made
         * longer is written whole rather than cut inside an entity.
         */
        private void htmlLine(String line) throws IOException {
            html.write(line.length() >= HTML_WIDTH ? line : FixedWidth.x(line, HTML_WIDTH));
            html.write('\n');
        }
    }

    /** RP-CBSTM03A-3: first, middle (when not blank), and last name, each without trailing spaces, one space apart. */
    static String name(StatementCustomer c) {
        String middle = c.middleName() == null ? "" : c.middleName();
        return middle.isBlank() ? rtrim(c.firstName()) + " " + rtrim(c.lastName())
            : rtrim(c.firstName()) + " " + rtrim(middle) + " " + rtrim(c.lastName());
    }

    /** RP-CBSTM03A-3: city, state, country, and ZIP, the non-blank ones, one space apart. */
    static String addressLine3(StatementCustomer c) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[] {c.city(), c.stateCd(), c.countryCd(), c.zip()}) {
            if (part != null && !part.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(rtrim(part));
            }
        }
        return sb.toString();
    }

    /** STRING ... DELIMITED BY '  ': the field up to its first double space (the whole field when none). */
    static String beforeDoubleSpace(String field) {
        int i = field.indexOf("  ");
        return i < 0 ? field : field.substring(0, i);
    }

    /** STRING ... DELIMITED BY '*': the field up to its first asterisk. */
    static String beforeStar(String field) {
        int i = field.indexOf('*');
        return i < 0 ? field : field.substring(0, i);
    }

    /** Q-CBSTM03A-10: values inserted into the HTML escape {@code &}, {@code <}, and {@code >}. */
    static String escape(String value) {
        if (value.indexOf('&') < 0 && value.indexOf('<') < 0 && value.indexOf('>') < 0) {
            return value;
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String rtrim(String s) {
        return s == null ? "" : s.stripTrailing();
    }

    private static Path part(Path target) {
        return target.resolveSibling(target.getFileName() + ".part");
    }

    private static void deleteQuietly(Path... paths) {
        for (Path p : paths) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                System.err.println("could not delete " + p + ": " + e);
            }
        }
    }
}
