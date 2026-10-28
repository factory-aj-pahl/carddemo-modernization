package com.carddemo.card.web;

import com.carddemo.card.internal.CardForms;
import com.carddemo.card.internal.CardRepository;
import com.carddemo.card.internal.CardRepository.CardRow;
import com.carddemo.card.internal.CardRepository.Filter;
import com.carddemo.common.mask.Masking;
import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.ScreenResponse;
import com.carddemo.common.security.AccountScope;
import com.carddemo.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * COCRDLIC credit card list (transaction CCLI): 7 cards per page in card-number order, optional
 * account and card filters, select {@code S} (view) or {@code U} (update). A USER sees only cards of
 * linked accounts (Q-COCRDLIC-01); card numbers are masked (Q-COCRDLIC-16).
 */
@RestController
@RequestMapping("/api/screens/COCRDLIC")
@Tag(name = "COCRDLIC", description = "Credit card list")
public class CardListController {

    static final int PAGE_SIZE = 7;
    static final String INFO = "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";
    static final String NO_MORE_RECORDS = "NO MORE RECORDS TO SHOW";
    static final String NO_RECORDS_FOUND = "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";
    static final String NO_PREVIOUS_PAGES = "NO PREVIOUS PAGES TO DISPLAY";
    static final String NO_MORE_PAGES = "NO MORE PAGES TO DISPLAY";
    static final String ONLY_ONE = "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE";
    static final String INVALID_ACTION = "INVALID ACTION CODE";
    static final String STATE = "carddemo.COCRDLIC";
    static final String ACCOUNT_FILTER = "accountFilter";
    static final String CARD_FILTER = "cardFilter";

    /**
     * @param selectable false when the select field is protected (no data on the row, or a filter error)
     * @param error      the select field is in error style
     */
    public record Row(String sel, String accountId, String cardNum, String status, boolean selectable, boolean error) {
    }

    /**
     * Always 7 rows; unfilled rows are blank. {@code info} is the info line (empty when dark);
     * {@code errors} names the filter fields in error style.
     */
    public record Fields(String accountFilter, String cardFilter, String pageNum, List<Row> rows, String info,
                         List<String> errors) {
    }

    /** {@code sel}: the select codes of rows 1-7; rows are identified by position only. */
    public record Request(Key key, String accountFilter, String cardFilter, List<String> sel) {
    }

    /**
     * Paging state. {@code account}/{@code card} are the filters that produced the page (null = none);
     * {@code heldCard} is the full card filter behind the masked value on screen.
     */
    record ListState(int page, String firstKey, String lastKey, boolean nextPage, boolean lastPageShown,
                     Long account, String card, List<CardRow> rows) implements Serializable {
        static final ListState EMPTY = new ListState(1, null, null, false, false, null, null, List.of());
    }

    /** The filters as edited this turn. */
    private record Filters(String accountText, String cardText, Long account, String card, String message,
                           List<String> errors) {
    }

    private record Read(ListState state, String message) {
    }

    private final CardRepository cards;
    /** Resolved per call so that contexts without iam (modules depending on card) still start. */
    private final ObjectProvider<AccountScope> scope;

    public CardListController(CardRepository cards, ObjectProvider<AccountScope> scope) {
        this.cards = cards;
        this.scope = scope;
    }

    @GetMapping
    @Operation(summary = "Entry from the menu or a return from view/update: page 1, no filters (NAV-COCRDLIC-09, Q-COCRDLIC-13)")
    public ScreenResponse<Fields> show(HttpSession session) {
        session.removeAttribute(CardScreens.SELECTED);
        try {
            Read r = forward(ListState.EMPTY, null, null, null, true, 1);
            session.setAttribute(STATE, r.state());
            return render(r.state(), "", "", blankCodes(), r.message(), true, List.of(), null);
        } catch (DataAccessException e) {
            session.setAttribute(STATE, ListState.EMPTY);
            return render(ListState.EMPTY, "", "", blankCodes(), CardForms.FILE_ERROR, true, List.of(), null);
        }
    }

    @PostMapping
    @Operation(summary = "List turn: ENTER filters or selects, PF7/PF8 page, PF3 returns to the main menu; other keys act as ENTER")
    public ScreenResponse<Fields> turn(@RequestBody Request req, HttpSession session) {
        Key key = effective(req.key());
        if (key == Key.PF3) {
            return ScreenResponse.navigate("COMEN01C");
        }
        ListState st = session.getAttribute(STATE) instanceof ListState s ? s : ListState.EMPTY;
        List<String> sel = codes(req.sel());
        Filters f = editFilters(req.accountFilter(), req.cardFilter(), st);
        boolean lastPageShown = key == Key.PF8 && st.lastPageShown();

        if (f.message() != null) {
            // VR-COCRDLIC-01/02: no browse; the previous rows are shown again with protected selects.
            session.setAttribute(STATE, withFlag(st, lastPageShown));
            return render(st, f.accountText(), f.cardText(), sel, f.message(), false, f.errors(), null, false);
        }

        boolean[] flagged = new boolean[PAGE_SIZE];
        String selError = null;
        int selected = -1;
        int requests = 0;
        for (int i = 0; i < st.rows().size(); i++) {
            String code = sel.get(i);
            if (code.equals("S") || code.equals("U")) {
                requests++;
                selected = i;
            }
        }
        if (requests > 1) {
            selError = ONLY_ONE;
            for (int i = 0; i < st.rows().size(); i++) {
                String code = sel.get(i);
                flagged[i] = code.equals("S") || code.equals("U");
            }
        }
        for (int i = 0; i < st.rows().size(); i++) {
            String code = sel.get(i);
            if (!code.isBlank() && !code.equals("S") && !code.equals("U")) {
                selError = selError == null ? INVALID_ACTION : selError;
                flagged[i] = true;
            }
        }
        try {
            if (selError != null) {
                // Q-COCRDLIC-02: the same page again, typed codes on their rows, offending rows in error style.
                ListState same = withFlag(st, false);
                session.setAttribute(STATE, same);
                return render(same, f.accountText(), f.cardText(), sel, selError, true, List.of(), flagged, true);
            }
            if (key == Key.ENTER && selected >= 0) {
                CardRow row = st.rows().get(selected);
                String target = sel.get(selected).equals("S") ? "COCRDSLC" : "COCRDUPC";
                CardScreens.select(session, target, row.accountId(), row.cardNum());
                return ScreenResponse.navigate(target);
            }
            boolean changed = !Objects.equals(f.account(), st.account()) || !Objects.equals(f.card(), st.card());
            Read r;
            String message;
            boolean info = true;
            if (changed) {
                // Q-COCRDLIC-03: a new or changed filter starts at page 1 with the first matching card.
                r = forward(st, f.account(), f.card(), null, true, 1);
                message = r.message();
            } else if (key == Key.PF7 && st.page() <= 1) {
                r = forward(st, f.account(), f.card(), st.firstKey(), true, 1);
                message = NO_PREVIOUS_PAGES;
                info = false;
            } else if (key == Key.PF7) {
                r = backward(st, f.account(), f.card());
                message = r.message();
            } else if (key == Key.PF8 && st.nextPage()) {
                r = forward(st, f.account(), f.card(), st.lastKey(), false, st.page() + 1);
                message = r.message();
                if (!r.state().nextPage()) {
                    r = new Read(withFlag(r.state(), true), r.message());
                }
            } else {
                r = forward(st, f.account(), f.card(), st.firstKey(), true, st.page());
                message = r.message();
                if (key == Key.PF8 && !r.state().nextPage()) {
                    if (lastPageShown) {
                        message = NO_MORE_PAGES;
                        info = false;
                    }
                    r = new Read(withFlag(r.state(), true), r.message());
                }
            }
            session.setAttribute(STATE, r.state());
            return render(r.state(), f.accountText(), f.cardText(), blankCodes(), message, info, List.of(), null, true);
        } catch (DataAccessException e) {
            ListState empty = new ListState(st.page(), st.firstKey(), st.lastKey(), false, false, st.account(), st.card(),
                List.of());
            session.setAttribute(STATE, empty);
            return render(empty, f.accountText(), f.cardText(), blankCodes(), CardForms.FILE_ERROR, true, List.of(), null,
                true);
        }
    }

    /** NAV-COCRDLIC-01: PF13-PF24 fold onto PF1-PF12; only ENTER, PF3, PF7, and PF8 are valid, the rest act as ENTER. */
    private static Key effective(Key key) {
        Key k = key == null ? Key.ENTER : key.folded();
        return k == Key.PF3 || k == Key.PF7 || k == Key.PF8 ? k : Key.ENTER;
    }

    private static Filters editFilters(String accountIn, String cardIn, ListState st) {
        String a = accountIn == null ? "" : accountIn;
        String c = cardIn == null ? "" : cardIn;
        String message = null;
        List<String> errors = new ArrayList<>();
        Long account = null;
        if (!a.isBlank() && !a.equals("0".repeat(11))) {
            if (CardForms.digits(a, 11)) {
                account = Long.parseLong(a);
            } else {
                message = CardForms.ACCOUNT_FILTER_INVALID;
                errors.add(ACCOUNT_FILTER);
            }
        }
        String card = null;
        String cardText = c;
        if (st.card() != null && c.equals(Masking.card(st.card()))) {
            card = st.card();
        } else if (!c.isBlank() && !c.equals("0".repeat(16))) {
            if (CardForms.digits(c, 16)) {
                card = c;
                cardText = Masking.card(c);
            } else {
                message = message == null ? CardForms.CARD_FILTER_INVALID : message;
                errors.add(CARD_FILTER);
            }
        }
        return new Filters(a, cardText, account, card, message, List.copyOf(errors));
    }

    private Filter filter(Long account, String card) {
        CurrentUser user = CurrentUser.require();
        return new Filter(account, card, user.isAdmin() ? null : scope.getObject().linkedAccounts(user.userId()));
    }

    /** 9000-READ-FORWARD with the next-page indicator set only when another matching card exists (Q-COCRDLIC-04). */
    private Read forward(ListState st, Long account, String card, String key, boolean inclusive, int page) {
        List<CardRow> found = cards.forward(filter(account, card), key, inclusive, PAGE_SIZE + 1);
        boolean more = found.size() > PAGE_SIZE;
        List<CardRow> shown = List.copyOf(found.subList(0, Math.min(PAGE_SIZE, found.size())));
        String message = null;
        if (!more) {
            message = page == 1 && shown.isEmpty() ? NO_RECORDS_FOUND : NO_MORE_RECORDS;
        }
        String first = shown.isEmpty() ? key : shown.get(0).cardNum();
        String last = shown.isEmpty() ? key : shown.get(shown.size() - 1).cardNum();
        return new Read(new ListState(page, first, last, more, false, account, card, shown), message);
    }

    /** 9100-READ-BACKWARDS; reaching the first matching card early shows page 1 from the start (Q-COCRDLIC-07). */
    private Read backward(ListState st, Long account, String card) {
        List<CardRow> before = st.firstKey() == null ? List.of() : cards.backward(filter(account, card), st.firstKey(), PAGE_SIZE);
        if (before.size() < PAGE_SIZE) {
            return forward(st, account, card, null, true, 1);
        }
        List<CardRow> shown = new ArrayList<>(before);
        Collections.reverse(shown);
        return new Read(new ListState(st.page() - 1, shown.get(0).cardNum(), shown.get(PAGE_SIZE - 1).cardNum(), true,
            false, account, card, List.copyOf(shown)), null);
    }

    private static ListState withFlag(ListState s, boolean lastPageShown) {
        return new ListState(s.page(), s.firstKey(), s.lastKey(), s.nextPage(), lastPageShown, s.account(), s.card(), s.rows());
    }

    private static ScreenResponse<Fields> render(ListState st, String accountText, String cardText, List<String> sel,
                                                 String message, boolean info, List<String> errors, boolean[] flagged) {
        return render(st, accountText, cardText, sel, message, info, errors, flagged, true);
    }

    /**
     * Builds the screen. Focus (BR-COCRDLIC-13, Q-COCRDLIC-10): the first filter in error, else the
     * first row in error, else the account filter.
     */
    private static ScreenResponse<Fields> render(ListState st, String accountText, String cardText, List<String> sel,
                                                 String message, boolean info, List<String> errors, boolean[] flagged,
                                                 boolean selectable) {
        List<Row> rows = new ArrayList<>(PAGE_SIZE);
        String focus = errors.isEmpty() ? null : errors.get(0);
        for (int i = 0; i < PAGE_SIZE; i++) {
            boolean err = flagged != null && flagged[i];
            if (i < st.rows().size()) {
                CardRow c = st.rows().get(i);
                String code = err && sel.get(i).isBlank() ? "*" : sel.get(i);
                rows.add(new Row(code, CardForms.accountText(c.accountId()), Masking.card(c.cardNum()), c.activeStatus(),
                    selectable, err));
            } else {
                rows.add(new Row("", "", "", "", false, false));
            }
            if (err && focus == null) {
                focus = "sel" + (i + 1);
            }
        }
        boolean showInfo = info && !NO_RECORDS_FOUND.equals(message);
        Fields fields = new Fields(accountText, cardText, Integer.toString(st.page()), rows, showInfo ? INFO : "", errors);
        ScreenResponse<Fields> r = ScreenResponse.show(fields).withFocus(focus == null ? ACCOUNT_FILTER : focus);
        return message == null ? r : r.error(message);
    }

    private static List<String> codes(List<String> sel) {
        List<String> out = new ArrayList<>(PAGE_SIZE);
        for (int i = 0; i < PAGE_SIZE; i++) {
            String s = sel != null && i < sel.size() && sel.get(i) != null ? sel.get(i) : "";
            out.add(s.length() > 1 ? s.substring(0, 1) : s);
        }
        return out;
    }

    private static List<String> blankCodes() {
        return Collections.nCopies(PAGE_SIZE, "");
    }
}
