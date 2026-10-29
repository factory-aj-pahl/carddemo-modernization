package com.carddemo.authorization.web;

import com.carddemo.account.api.AccountLimits;
import com.carddemo.account.api.AccountProfiles;
import com.carddemo.account.api.CustomerContact;
import com.carddemo.authorization.internal.PendingAuthBrowser;
import com.carddemo.authorization.internal.PendingAuthBrowser.Page;
import com.carddemo.authorization.internal.PendingAuthRepository;
import com.carddemo.authorization.web.AuthScreens.AccountContext;
import com.carddemo.card.api.CardDirectory;
import com.carddemo.card.api.CardXref;
import com.carddemo.common.msg.CommonMessages;
import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.ScreenResponse;
import com.carddemo.common.security.AccountScope;
import com.carddemo.common.security.CurrentUser;
import com.carddemo.migration.PendingAuthSegments.Detail;
import com.carddemo.migration.PendingAuthSegments.Summary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * COPAUS0C View Authorizations (transaction CPVS): the account's customer, limits, and pending-authorization
 * summary, and its pending authorizations five per page, newest first. A USER sees only linked accounts; any
 * other account is answered as not found in the cross-reference.
 */
@RestController
@RequestMapping("/api/screens/COPAUS0C")
@Tag(name = "COPAUS0C", description = "View pending authorizations of an account")
public class PendingAuthListController {

    static final int PAGE_SIZE = 5;
    static final String ENTER_ACCOUNT = "Please enter Acct Id...";
    static final String NOT_NUMERIC = "Acct Id must be Numeric ...";
    static final String INVALID_SELECTION = "Invalid selection. Valid value is S";
    static final String TOP = "You are already at the top of the page...";
    static final String BOTTOM = "You are already at the bottom of the page...";
    static final String ACCOUNT_ID = "accountId";
    /** CICS RESP / RESP2 of a READ that found no record (NOTFND), and of a failed READ (IOERR). */
    static final String NOTFND = "Resp:000000013 Reas:000000080";
    static final String IOERR = "Resp:000000017 Reas:000000000";

    /** One list row; {@code selectable} is false for an empty row (protected select field). */
    public record Row(String sel, String transactionId, String date, String time, String type, String approval,
            String status, String amount, boolean selectable) implements Serializable {
        static final Row EMPTY = new Row("", "", "", "", "", "", "", "", false);
    }

    /** The map fields; amounts keep their edit-picture widths (leading spaces). {@code pageNum} is 0 before any row. */
    public record Fields(String accountId, String name, String customerId, String address1, String address2,
            String accountStatus, String phone, String approvedCount, String declinedCount, String creditLimit,
            String cashLimit, String approvedAmount, String creditBalance, String cashBalance, String declinedAmount,
            String pageNum, List<Row> rows) implements Serializable {
    }

    /** {@code sel}: the select fields of rows 1-5. */
    public record Request(Key key, String accountId, List<String> sel) {
    }

    record PageKey(LocalDateTime ts, long authId) implements Serializable {
        static PageKey of(Detail d) {
            return new PageKey(d.authTs(), d.authId());
        }
    }

    /**
     * Paging state: the first key of every page shown (no page limit, Q-COPAUS0C-02), the last row's key, the
     * next-page flag, the keys of the rows on screen, and the screen as last sent.
     */
    record ListState(Long account, Long customerId, int page, List<PageKey> firstKeys, PageKey last, boolean nextPage,
            List<PageKey> rowKeys, Fields screen) implements Serializable {
    }

    /** The header part of the screen and what the reads found. */
    private record Header(Fields fields, Long customerId, String message, boolean error, boolean stop) {
    }

    static final String STATE = "carddemo.COPAUS0C";

    private final CardDirectory cards;
    private final AccountProfiles accounts;
    private final PendingAuthRepository repo;
    private final PendingAuthBrowser browser;
    private final ObjectProvider<AccountScope> scope;

    public PendingAuthListController(CardDirectory cards, AccountProfiles accounts, PendingAuthRepository repo,
            PendingAuthBrowser browser, ObjectProvider<AccountScope> scope) {
        this.cards = cards;
        this.accounts = accounts;
        this.repo = repo;
        this.browser = browser;
        this.scope = scope;
    }

    @GetMapping
    @Operation(summary = "Entry from the menu or COPAUS1C: the account in context (BR-COPAUS0C-02), else an empty prompt (Q-COPAUS0C-03)")
    public ScreenResponse<Fields> show(HttpSession session) {
        AuthScreens.clearSelection(session);
        AccountContext ctx = AuthScreens.account(session);
        if (ctx == null) {
            ListState st = empty("");
            session.setAttribute(STATE, st);
            return render(st, null);
        }
        return build(session, ctx.accountId(), null);
    }

    @PostMapping
    @Operation(summary = "Turn: ENTER searches or selects (S), PF7/PF8 page, PF3 returns to the main menu, other keys are invalid")
    public ScreenResponse<Fields> turn(@RequestBody Request req, HttpSession session) {
        Key key = req.key() == null ? Key.ENTER : req.key();
        ListState st = session.getAttribute(STATE) instanceof ListState s ? s : empty("");
        return switch (key) {
            case ENTER -> enter(req, st, session);
            case PF3 -> ScreenResponse.navigate("COMEN01C");
            case PF7 -> backward(st, session);
            case PF8 -> forward(st, session);
            // Q-COPAUS0C-09: the whole current screen again.
            default -> render(st, CommonMessages.INVALID_KEY);
        };
    }

    private ScreenResponse<Fields> enter(Request req, ListState st, HttpSession session) {
        String in = req.accountId() == null ? "" : req.accountId();
        if (in.length() > 11) {
            in = in.substring(0, 11);
        }
        // Q-COPAUS0C-14: nothing of the account shown before stays, and paging waits for a valid account.
        if (in.isBlank()) {
            return keep(session, empty(""), ENTER_ACCOUNT);
        }
        if (in.length() != 11 || !in.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return keep(session, empty(in), NOT_NUMERIC);
        }
        long account = Long.parseLong(in);
        AuthScreens.account(session, new AccountContext(account, st.customerId()));
        String message = null;
        // Q-COPAUS0C-15: select codes refer to the rows of the account shown; another account is just searched.
        boolean shown = st.account() != null && st.account() == account;
        List<String> sel = req.sel() == null || !shown ? List.of() : req.sel();
        for (int i = 0; i < PAGE_SIZE && i < sel.size(); i++) {
            String code = sel.get(i) == null ? "" : sel.get(i).strip();
            if (code.isEmpty()) {
                continue;
            }
            // VR-COPAUS0C-04: only the first non-blank select counts, and only on a filled row.
            if (i < st.rowKeys().size()) {
                if (code.equals("S") || code.equals("s")) {
                    long customer = st.customerId() == null ? 0 : st.customerId();
                    AuthScreens.select(session, new AuthScreens.Selection(account, customer, st.rowKeys().get(i).authId()));
                    return ScreenResponse.navigate("COPAUS1C");
                }
                message = INVALID_SELECTION;
            }
            break;
        }
        return build(session, account, message);
    }

    /** GATHER-DETAILS: page 1 for the account, header re-read (BR-COPAUS0C-03). */
    private ScreenResponse<Fields> build(HttpSession session, long account, String initialMessage) {
        Header h = header(account);
        Long customerId = h.customerId();
        AuthScreens.account(session, new AccountContext(account, customerId));
        String message = h.message() != null ? h.message() : initialMessage;
        if (h.stop()) {
            return keep(session, new ListState(account, customerId, 0, List.of(), null, false, List.of(), h.fields()), message);
        }
        Page p = browser.first(account, PAGE_SIZE);
        Fields f = withSummary(h.fields(), p.summary());
        if (p.error() != null) {
            message = p.error();
        }
        if (h.error() || p.error() != null || p.summary() == null) {
            return keep(session, new ListState(account, customerId, 0, List.of(), null, false, List.of(), withRows(f, 0, List.of())),
                message);
        }
        int page = p.rows().isEmpty() ? 0 : 1;
        List<PageKey> firstKeys = p.rows().isEmpty() ? List.of() : List.of(PageKey.of(p.rows().get(0)));
        return keep(session, paged(account, customerId, page, firstKeys, p, f), message);
    }

    /** PROCESS-PF8-KEY. */
    private ScreenResponse<Fields> forward(ListState st, HttpSession session) {
        if (!st.nextPage() || st.last() == null || st.account() == null) {
            return keep(session, st, BOTTOM);
        }
        Page p = browser.from(st.account(), st.last().ts(), st.last().authId(), false, PAGE_SIZE);
        if (p.error() != null) {
            return keep(session, failed(st), p.error());
        }
        if (p.rows().isEmpty()) {
            // Q-COPAUS0C-16: the authorizations after the last row were deleted since it was shown.
            return keep(session, new ListState(st.account(), st.customerId(), st.page(), st.firstKeys(), st.last(), false,
                st.rowKeys(), st.screen()), BOTTOM);
        }
        List<PageKey> firstKeys = new ArrayList<>(st.firstKeys().subList(0, Math.min(st.page(), st.firstKeys().size())));
        firstKeys.add(PageKey.of(p.rows().get(0)));
        return keep(session, paged(st.account(), st.customerId(), st.page() + 1, firstKeys, p, st.screen()), null);
    }

    /** PROCESS-PF7-KEY; the previous page down to page 1 (Q-COPAUS0C-01). */
    private ScreenResponse<Fields> backward(ListState st, HttpSession session) {
        if (st.page() <= 1 || st.account() == null) {
            return keep(session, st, TOP);
        }
        int page = st.page() - 1;
        PageKey start = st.firstKeys().get(page - 1);
        Page p = browser.from(st.account(), start.ts(), start.authId(), true, PAGE_SIZE);
        if (p.error() != null) {
            return keep(session, failed(st), p.error());
        }
        if (p.rows().isEmpty()) {
            // Q-COPAUS0C-16: nothing remains from the previous page's first row on, so show what remains from the newest.
            Page top = browser.first(st.account(), PAGE_SIZE);
            if (top.error() != null) {
                return keep(session, failed(st), top.error());
            }
            if (top.rows().isEmpty()) {
                return keep(session, new ListState(st.account(), st.customerId(), 0, List.of(), null, false, List.of(),
                    withRows(st.screen(), 0, List.of())), TOP);
            }
            return keep(session, paged(st.account(), st.customerId(), 1, List.of(PageKey.of(top.rows().get(0))), top,
                st.screen()), null);
        }
        List<PageKey> firstKeys = new ArrayList<>(st.firstKeys().subList(0, page - 1));
        firstKeys.add(PageKey.of(p.rows().get(0)));
        return keep(session, paged(st.account(), st.customerId(), page, firstKeys, p, st.screen()), null);
    }

    private static ListState paged(long account, Long customerId, int page, List<PageKey> firstKeys, Page p, Fields header) {
        List<PageKey> rowKeys = p.rows().stream().map(PageKey::of).toList();
        PageKey last = rowKeys.isEmpty() ? null : rowKeys.get(rowKeys.size() - 1);
        return new ListState(account, customerId, page, List.copyOf(firstKeys), last, p.more(), rowKeys,
            withRows(header, page, p.rows()));
    }

    /** A failed page read: the list is cleared (INITIALIZE-AUTH-DATA ran before the read). */
    private static ListState failed(ListState st) {
        return new ListState(st.account(), st.customerId(), st.page(), st.firstKeys(), null, false, List.of(),
            withRows(st.screen(), st.page(), List.of()));
    }

    /**
     * GATHER-ACCOUNT-DETAILS up to the summary: cross-reference by account (the lowest card, DF-6), then account and
     * customer by the cross-reference's keys. A missing or out-of-scope cross-reference stops the turn
     * (Q-COPAUS0C-04); later messages replace earlier ones.
     */
    private Header header(long account) {
        String acct11 = String.format("%011d", account);
        Fields blank = blankFields(acct11);
        CurrentUser user = CurrentUser.require();
        Optional<CardXref> xref;
        try {
            boolean inScope = user.isAdmin() || scope.getObject().canAccess(user, account);
            Optional<String> card = inScope ? cards.lowestCardForAccount(account) : Optional.empty();
            xref = card.isEmpty() ? Optional.empty() : cards.findXref(card.get());
        } catch (DataAccessException e) {
            return new Header(blank, null, "Account:" + acct11 + " System error while reading XREF file. " + IOERR, true, true);
        }
        if (xref.isEmpty()) {
            return new Header(blank, null, "Account:" + acct11 + " not found in XREF file. " + NOTFND, false, true);
        }
        CardXref x = xref.get();
        String message = null;
        boolean error = false;
        String xrefAcct = String.format("%011d", x.accountId());
        AccountLimits a = null;
        try {
            a = accounts.limits(x.accountId()).orElse(null);
            if (a == null) {
                message = "Account:" + xrefAcct + " not found in ACCT file. " + NOTFND;
            }
        } catch (DataAccessException e) {
            error = true;
            message = "Account:" + xrefAcct + " System error while reading ACCT file. " + IOERR;
        }
        String cust9 = String.format("%09d", x.customerId());
        CustomerContact c = null;
        try {
            c = accounts.contact(x.customerId()).orElse(null);
            if (c == null) {
                message = "Customer:" + cust9 + " not found in CUST file. " + NOTFND;
            }
        } catch (DataAccessException e) {
            error = true;
            message = "Customer:" + cust9 + " System error while reading CUST file. " + IOERR;
        }
        Fields f = new Fields(acct11,
            c == null ? "" : name(c),
            c == null ? "" : String.format("%09d", c.customerId()),
            c == null ? "" : AuthFormat.field(AuthFormat.upTo(c.addrLine1(), "  ") + "," + AuthFormat.upTo(c.addrLine2(), "  "), 25),
            c == null ? "" : AuthFormat.field(AuthFormat.upTo(c.city(), "  ") + "," + AuthFormat.fixed(c.stateCd(), 2) + ","
                + AuthFormat.fixed(c.zip(), 5), 25),
            a == null ? "" : AuthFormat.field(a.activeStatus(), 1),
            c == null ? "" : AuthFormat.field(c.phone1(), 13),
            "", "",
            a == null ? "" : AuthFormat.amount(a.creditLimit(), 12),
            a == null ? "" : AuthFormat.amount(a.cashCreditLimit(), 9),
            "", "", "", "", "0", rows(List.of()));
        return new Header(f, x.customerId(), message, error, false);
    }

    /** BR-COPAUS0C-05 / Q-COPAUS0C-10: first and last name up to their first space, middle initial between. */
    private static String name(CustomerContact c) {
        String middle = c.middleName() == null || c.middleName().isEmpty() ? " " : c.middleName().substring(0, 1);
        return AuthFormat.field(AuthFormat.upTo(c.firstName(), " ") + " " + middle + " " + AuthFormat.upTo(c.lastName(), " "), 25);
    }

    /** BR-COPAUS0C-06; without a summary every amount shows 0.00 and every count 0 (Q-COPAUS0C-06). */
    private static Fields withSummary(Fields f, Summary s) {
        BigDecimal zero = BigDecimal.ZERO;
        return new Fields(f.accountId(), f.name(), f.customerId(), f.address1(), f.address2(), f.accountStatus(), f.phone(),
            s == null ? "0" : AuthFormat.count(s.approvedCount()),
            s == null ? "0" : AuthFormat.count(s.declinedCount()),
            f.creditLimit(), f.cashLimit(),
            AuthFormat.amount(s == null ? zero : s.approvedAmt(), 9),
            AuthFormat.amount(s == null ? zero : s.creditBalance(), 12),
            AuthFormat.amount(s == null ? zero : s.cashBalance(), 9),
            AuthFormat.amount(s == null ? zero : s.declinedAmt(), 9),
            f.pageNum(), f.rows());
    }

    private static Fields withRows(Fields f, int page, List<Detail> details) {
        return new Fields(f.accountId(), f.name(), f.customerId(), f.address1(), f.address2(), f.accountStatus(), f.phone(),
            f.approvedCount(), f.declinedCount(), f.creditLimit(), f.cashLimit(), f.approvedAmount(), f.creditBalance(),
            f.cashBalance(), f.declinedAmount(), Integer.toString(page), rows(details));
    }

    /** BR-COPAUS0C-09 / BR-COPAUS0C-11: filled rows are selectable, the rest blank and protected. */
    private static List<Row> rows(List<Detail> details) {
        List<Row> out = new ArrayList<>(PAGE_SIZE);
        for (int i = 0; i < PAGE_SIZE; i++) {
            if (i < details.size()) {
                Detail d = details.get(i);
                out.add(new Row("", AuthFormat.field(d.transactionId(), 16), AuthFormat.date(d.origDate()),
                    AuthFormat.time(d.origTime()), AuthFormat.field(d.authType(), 4), "00".equals(d.respCode()) ? "A" : "D",
                    AuthFormat.field(d.matchStatus(), 1), AuthFormat.amount(d.approvedAmt(), 12), true));
            } else {
                out.add(Row.EMPTY);
            }
        }
        return List.copyOf(out);
    }

    private static Fields blankFields(String accountId) {
        return new Fields(accountId, "", "", "", "", "", "", "", "", "", "", "", "", "", "", "0", rows(List.of()));
    }

    private static ListState empty(String accountId) {
        return new ListState(null, null, 0, List.of(), null, false, List.of(), blankFields(accountId));
    }

    private static ScreenResponse<Fields> keep(HttpSession session, ListState st, String message) {
        session.setAttribute(STATE, st);
        return render(st, message);
    }

    private static ScreenResponse<Fields> render(ListState st, String message) {
        ScreenResponse<Fields> r = ScreenResponse.show(st.screen()).withFocus(ACCOUNT_ID);
        return message == null ? r : r.error(message);
    }
}
