package com.carddemo.transaction.web;

import com.carddemo.common.format.Pic;
import com.carddemo.common.msg.CommonMessages;
import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.ScreenResponse;
import com.carddemo.common.security.CurrentUser;
import com.carddemo.transaction.web.TransactionReader.Bound;
import com.carddemo.transaction.web.TransactionReader.Scope;
import com.carddemo.transaction.web.TransactionReader.TransactionRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** COTRN00C transaction list (transaction CT00): 10 rows per page, keyset paging, selection for the view. */
@RestController
@RequestMapping("/api/screens/COTRN00C")
@Tag(name = "COTRN00C", description = "List transactions")
public class TransactionListController {

    static final String TOP_REACHED = "You have reached the top of the page...";
    static final String BOTTOM_REACHED = "You have reached the bottom of the page...";
    static final String ALREADY_TOP = "You are already at the top of the page...";
    static final String ALREADY_BOTTOM = "You are already at the bottom of the page...";
    static final String NOT_NUMERIC = "Tran ID must be Numeric ...";
    static final String INVALID_SELECTION = "Invalid selection. Valid value is S";
    static final String LOOKUP_FAILED = "Unable to lookup transaction...";
    static final int PAGE_SIZE = 10;
    static final String FOCUS = "searchTranId";

    /** One list row (BR-COTRN00C-04). */
    public record Row(String tranId, String date, String description, String amount) {
    }

    /**
     * @param searchTranId TRNIDIN, 16 characters
     * @param page         PAGENUM, 8 digits
     * @param rows         the filled rows of the page, top to bottom (at most 10)
     */
    public record Fields(String searchTranId, String page, List<Row> rows) {
    }

    /** @param selections SEL0001-SEL0010 as typed, by row position */
    public record Request(Key key, String searchTranId, List<String> selections) {
    }

    /** CDEMO-CT00-INFO kept between turns (first and last IDs, page number, next-page flag) plus the row keys. */
    record Position(String firstId, String lastId, int page, boolean nextPage, List<String> rowIds) implements Serializable {
        static final String ATTR = "carddemo.transaction.list";
        static final Position NONE = new Position(null, null, 0, false, List.of());

        Position withNextPage(boolean next) {
            return new Position(firstId, lastId, page, next, rowIds);
        }
    }

    private final TransactionReader reader;

    public TransactionListController(TransactionReader reader) {
        this.reader = reader;
    }

    @GetMapping
    @Operation(summary = "First entry: page 1 from the first in-scope transaction (NAV-COTRN00C-02, Q-COTRN00C-01)")
    public ScreenResponse<Fields> show(HttpSession session) {
        session.removeAttribute(Selection.ATTR);
        Scope scope = reader.scopeOf(CurrentUser.require());
        try {
            return forward(session, scope, Position.NONE, null, Bound.FROM_START, 0, null);
        } catch (DataAccessException e) {
            return failed(session, Position.NONE, "");
        }
    }

    @PostMapping
    @Operation(summary = "List turn: ENTER selects or searches, PF7/PF8 page, PF3 back to the menu")
    public ScreenResponse<Fields> turn(@RequestBody Request req, HttpSession session) {
        Key key = req.key() == null ? Key.ENTER : req.key();
        String search = Input.cut(req.searchTranId(), 16);
        Position pos = session.getAttribute(Position.ATTR) instanceof Position p ? p : Position.NONE;
        session.removeAttribute(Selection.ATTR);
        if (key == Key.PF3) {
            return ScreenResponse.navigate("COMEN01C");
        }
        Scope scope = reader.scopeOf(CurrentUser.require());
        try {
            return switch (key) {
                case ENTER -> enter(session, scope, pos, search, req.selections());
                case PF7 -> backward(session, scope, pos, search);
                case PF8 -> pos.nextPage()
                    ? forward(session, scope, pos, pos.lastId(), Bound.AFTER, pos.page(), null)
                    : redisplay(session, scope, pos, search).error(ALREADY_BOTTOM);
                default -> redisplay(session, scope, pos, search).error(CommonMessages.INVALID_KEY);
            };
        } catch (DataAccessException e) {
            return failed(session, pos, search);
        }
    }

    private ScreenResponse<Fields> enter(HttpSession session, Scope scope, Position pos, String search, List<String> selections) {
        String preset = null;
        List<String> sels = selections == null ? List.of() : selections;
        for (int i = 0; i < Math.min(sels.size(), PAGE_SIZE); i++) {
            String sel = Input.cut(sels.get(i), 1);
            if (sel.isBlank()) {
                continue;
            }
            // BR-COTRN00C-09: only the first marked row counts; a mark on an empty row is ignored.
            String id = i < pos.rowIds().size() ? pos.rowIds().get(i) : "";
            if (!id.isBlank()) {
                if (sel.equals("S") || sel.equals("s")) {
                    session.setAttribute(Selection.ATTR, new Selection(id));
                    return ScreenResponse.navigate("COTRN01C");
                }
                preset = INVALID_SELECTION;
            }
            break;
        }
        String start = null;
        if (!search.isBlank()) {
            if (!Input.digits(search, 16)) {
                // Q-COTRN00C-07: the list is not re-read; the current page stays.
                return redisplay(session, scope, pos, search).error(NOT_NUMERIC);
            }
            start = search;
        }
        return forward(session, scope, pos, start, Bound.AT_OR_AFTER, 0, preset);
    }

    /** PROCESS-PAGE-FORWARD over the in-scope rows with a look-ahead row for the next-page flag. */
    private ScreenResponse<Fields> forward(HttpSession session, Scope scope, Position pos, String key, Bound bound,
                                           int pageBefore, String preset) {
        List<TransactionRow> rows = reader.page(scope, key, bound, PAGE_SIZE + 1);
        if (rows.isEmpty()) {
            if (bound == Bound.AFTER) {
                Position kept = pos.withNextPage(false);
                return redisplay(session, scope, kept, "").error(BOTTOM_REACHED);
            }
            // Q-COTRN00C-02: nothing at or after the start key is an empty page 1, not an abend.
            Position empty = new Position(null, null, 1, false, List.of());
            session.setAttribute(Position.ATTR, empty);
            return ScreenResponse.show(new Fields("", page(1), List.of()))
                .error(preset != null ? preset : TransactionViewController.NOT_FOUND).withFocus(FOCUS);
        }
        boolean more = rows.size() > PAGE_SIZE;
        List<TransactionRow> shown = rows.subList(0, Math.min(PAGE_SIZE, rows.size()));
        Position next = store(session, shown, pageBefore + 1, more);
        ScreenResponse<Fields> r = ScreenResponse.show(new Fields("", page(next.page()), rows(shown))).withFocus(FOCUS);
        String message = preset != null ? preset : more ? null : BOTTOM_REACHED;
        return message == null ? r : r.error(message);
    }

    /**
     * PROCESS-PF7-KEY / PROCESS-PAGE-BACKWARD. A page shown by PF7 always has a next page (BR-COTRN00C-08);
     * a rejected PF7 on page 1 keeps the next-page state (Q-COTRN00C-13).
     */
    private ScreenResponse<Fields> backward(HttpSession session, Scope scope, Position pos, String search) {
        if (pos.page() <= 1 || pos.firstId() == null) {
            return redisplay(session, scope, pos, search).error(ALREADY_TOP);
        }
        List<TransactionRow> before = reader.page(scope, pos.firstId(), Bound.BEFORE, PAGE_SIZE + 1);
        List<TransactionRow> shown;
        int page;
        String message;
        if (before.size() > PAGE_SIZE) {
            shown = new ArrayList<>(before.subList(0, PAGE_SIZE));
            Collections.reverse(shown);
            page = pos.page() - 1;
            message = null;
        } else if (before.size() == PAGE_SIZE) {
            shown = new ArrayList<>(before);
            Collections.reverse(shown);
            page = 1;
            message = TOP_REACHED;
        } else {
            // Q-COTRN00C-11: reaching the start early shows the first 10 in-scope rows as page 1.
            shown = reader.page(scope, null, Bound.FROM_START, PAGE_SIZE);
            page = 1;
            message = TOP_REACHED;
        }
        Position next = store(session, shown, page, true);
        ScreenResponse<Fields> r = ScreenResponse.show(new Fields(search, page(next.page()), rows(shown))).withFocus(FOCUS);
        return message == null ? r : r.error(message);
    }

    /** The current page again (a send without erase keeps the rows on the 3270 screen). */
    private ScreenResponse<Fields> redisplay(HttpSession session, Scope scope, Position pos, String search) {
        List<TransactionRow> shown = pos.rowIds().isEmpty() ? List.of()
            : reader.page(scope, pos.firstId(), Bound.AT_OR_AFTER, pos.rowIds().size());
        session.setAttribute(Position.ATTR, pos);
        return ScreenResponse.show(new Fields(search, page(pos.page()), rows(shown))).withFocus(FOCUS);
    }

    private ScreenResponse<Fields> failed(HttpSession session, Position pos, String search) {
        session.setAttribute(Position.ATTR, pos);
        return ScreenResponse.show(new Fields(search, page(pos.page()), List.of())).error(LOOKUP_FAILED).withFocus(FOCUS);
    }

    private static Position store(HttpSession session, List<TransactionRow> shown, int page, boolean more) {
        List<String> ids = shown.stream().map(TransactionRow::tranId).toList();
        Position p = new Position(ids.isEmpty() ? null : ids.get(0), ids.isEmpty() ? null : ids.get(ids.size() - 1),
            page, more, ids);
        session.setAttribute(Position.ATTR, p);
        return p;
    }

    private static List<Row> rows(List<TransactionRow> shown) {
        return shown.stream().map(TransactionListController::row).toList();
    }

    /** BR-COTRN00C-04: MM/DD/YY from the origination timestamp, description 1-26, amount {@code +99999999.99}. */
    static Row row(TransactionRow t) {
        String ts = String.format("%-26s", t.origTsText() == null ? "" : t.origTsText());
        String date = ts.substring(5, 7) + "/" + ts.substring(8, 10) + "/" + ts.substring(2, 4);
        return new Row(t.tranId(), date, Input.part(t.description(), 26), Pic.signedZeros(t.amount(), 8));
    }

    private static String page(int n) {
        return String.format("%08d", n);
    }
}
