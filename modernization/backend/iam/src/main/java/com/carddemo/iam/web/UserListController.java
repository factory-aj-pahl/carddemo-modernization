package com.carddemo.iam.web;

import static com.carddemo.iam.internal.UserForms.blank;

import com.carddemo.common.msg.CommonMessages;
import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.ScreenResponse;
import com.carddemo.iam.internal.UserAdminRepository;
import com.carddemo.iam.internal.UserAdminRepository.UserRow;
import com.carddemo.iam.internal.UserForms;
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

/**
 * COUSR00C user list (transaction CU00): 10 users per page in user-ID order, keyset paging on
 * exclusive bounds (Q-COUSR00C-07, -09), selection resolved from the server's page (Q-COUSR00C-14).
 * ADMIN only (C-ROLE), enforced in SecurityConfig.
 */
@RestController
@RequestMapping("/api/screens/COUSR00C")
@Tag(name = "COUSR00C", description = "User list")
public class UserListController {

    static final int PAGE_SIZE = 10;
    static final String BOTTOM_REACHED = "You have reached the bottom of the page...";
    static final String TOP_REACHED = "You have reached the top of the page...";
    static final String ALREADY_TOP = "You are already at the top of the page...";
    static final String ALREADY_BOTTOM = "You are already at the bottom of the page...";
    static final String INVALID_SELECTION = "Invalid selection. Valid values are U and D";
    static final String STATE = "carddemo.COUSR00C";

    public record Row(String sel, String userId, String firstName, String lastName, String userType) {
    }

    /** Always 10 rows; unfilled rows are blank (BR-COUSR00C-16). Page is 8 digits (BR-COUSR00C-14). */
    public record Fields(String searchUserId, String pageNum, List<Row> rows) {
    }

    /** {@code sel}: the Sel codes of rows 1-10. Rows are identified by position only. */
    public record Request(Key key, String searchUserId, List<String> sel) {
    }

    /** Paging state: first and last user ID actually displayed (Q-COUSR00C-04). */
    record ListState(int page, boolean nextPage, String firstKey, String lastKey, List<UserRow> rows)
        implements Serializable {
        static final ListState EMPTY = new ListState(1, false, null, null, List.of());
    }

    private record Page(ListState state, String message) {
    }

    private final UserAdminRepository users;

    public UserListController(UserAdminRepository users) {
        this.users = users;
    }

    @GetMapping
    @Operation(summary = "First entry: page 1 from the first user (BR-COUSR00C-02)")
    public ScreenResponse<Fields> show(HttpSession session) {
        session.removeAttribute(UserScreens.SELECTED);
        try {
            return render(session, start(null), null, "");
        } catch (DataAccessException e) {
            return lookupFailed(session, ListState.EMPTY, "");
        }
    }

    @PostMapping
    @Operation(summary = "List turn: ENTER selects or rebuilds, PF7/PF8 page, PF3 returns to the admin menu")
    public ScreenResponse<Fields> turn(@RequestBody Request req, HttpSession session) {
        Key key = req.key() == null ? Key.ENTER : req.key();
        ListState st = session.getAttribute(STATE) instanceof ListState s ? s : ListState.EMPTY;
        List<String> sel = codes(req.sel());
        String typedSearch = req.searchUserId() == null ? "" : req.searchUserId();
        try {
            switch (key) {
                case PF3:
                    return ScreenResponse.navigate("COADM01C");
                case PF7:
                    if (st.page() <= 1) {
                        return same(st, sel, typedSearch, ALREADY_TOP);
                    }
                    return render(session, backward(st), null, "");
                case PF8:
                    if (!st.nextPage()) {
                        return same(st, sel, typedSearch, ALREADY_BOTTOM);
                    }
                    return render(session, forward(st), null, "");
                case ENTER:
                    return enter(session, st, sel, typedSearch);
                default:
                    return same(st, sel, typedSearch, CommonMessages.INVALID_KEY);
            }
        } catch (DataAccessException e) {
            return lookupFailed(session, st, typedSearch);
        }
    }

    private ScreenResponse<Fields> enter(HttpSession session, ListState st, List<String> sel, String typedSearch) {
        boolean invalid = false;
        for (int i = 0; i < PAGE_SIZE; i++) {
            if (blank(sel.get(i))) {
                continue;
            }
            // BR-COUSR00C-05: only the first non-blank Sel is processed; BR-COUSR00C-08: ignored on an empty row.
            if (i < st.rows().size()) {
                String userId = st.rows().get(i).userId();
                switch (sel.get(i)) {
                    case "U", "u" -> {
                        UserScreens.select(session, "COUSR02C", userId);
                        return ScreenResponse.navigate("COUSR02C");
                    }
                    case "D", "d" -> {
                        UserScreens.select(session, "COUSR03C", userId);
                        return ScreenResponse.navigate("COUSR03C");
                    }
                    default -> invalid = true;
                }
            }
            break;
        }
        String search = UserForms.userId(typedSearch).strip();
        Page p = start(search.isEmpty() ? null : search);
        return render(session, p, invalid ? INVALID_SELECTION : null, "");
    }

    /** ENTER and first entry: page 1 from the search key (inclusive) or the first user. */
    private Page start(String search) {
        List<UserRow> found = users.from(search, PAGE_SIZE + 1);
        if (found.isEmpty()) {
            return new Page(ListState.EMPTY, UserForms.ID_NOT_FOUND);
        }
        boolean more = found.size() > PAGE_SIZE;
        List<UserRow> shown = List.copyOf(found.subList(0, Math.min(PAGE_SIZE, found.size())));
        return new Page(state(1, more, shown), more ? null : BOTTOM_REACHED);
    }

    private Page forward(ListState st) {
        List<UserRow> found = users.after(st.lastKey(), PAGE_SIZE + 1);
        if (found.isEmpty()) {
            // BR-COUSR00C-12: no row filled, page number unchanged.
            return new Page(new ListState(st.page(), false, st.firstKey(), st.lastKey(), List.of()), BOTTOM_REACHED);
        }
        boolean more = found.size() > PAGE_SIZE;
        List<UserRow> shown = List.copyOf(found.subList(0, Math.min(PAGE_SIZE, found.size())));
        return new Page(state(st.page() + 1, more, shown), more ? null : BOTTOM_REACHED);
    }

    private Page backward(ListState st) {
        List<UserRow> before = st.firstKey() == null ? List.of() : users.before(st.firstKey(), PAGE_SIZE + 1);
        if (before.size() < PAGE_SIZE) {
            // Fewer than 10 users before the first key (users deleted meanwhile): show the first page of the file.
            List<UserRow> found = users.from(null, PAGE_SIZE + 1);
            List<UserRow> shown = List.copyOf(found.subList(0, Math.min(PAGE_SIZE, found.size())));
            return new Page(state(1, found.size() > PAGE_SIZE, shown), TOP_REACHED);
        }
        boolean moreBefore = before.size() > PAGE_SIZE;
        List<UserRow> shown = new ArrayList<>(before.subList(0, PAGE_SIZE));
        Collections.reverse(shown);
        boolean next = users.existsAfter(shown.get(shown.size() - 1).userId());
        return new Page(state(moreBefore ? st.page() - 1 : 1, next, List.copyOf(shown)), moreBefore ? null : TOP_REACHED);
    }

    private static ListState state(int page, boolean next, List<UserRow> shown) {
        return new ListState(page, next, shown.get(0).userId(), shown.get(shown.size() - 1).userId(), shown);
    }

    /** Rebuilt list: Sel cleared (Q-COUSR00C-06), search field cleared (BR-COUSR00C-09). */
    private ScreenResponse<Fields> render(HttpSession session, Page p, String override, String search) {
        session.setAttribute(STATE, p.state());
        ScreenResponse<Fields> r = ScreenResponse.show(fields(p.state(), blankCodes(), search)).withFocus("searchUserId");
        String message = override != null ? override : p.message();
        return message == null ? r : r.error(message);
    }

    /** The current rows stay on the screen with the typed codes (sent without ERASE). */
    private static ScreenResponse<Fields> same(ListState st, List<String> sel, String search, String message) {
        return ScreenResponse.show(fields(st, sel, search)).error(message).withFocus("searchUserId");
    }

    private static ScreenResponse<Fields> lookupFailed(HttpSession session, ListState st, String search) {
        ListState empty = new ListState(st.page(), false, st.firstKey(), st.lastKey(), List.of());
        session.setAttribute(STATE, empty);
        return ScreenResponse.show(fields(empty, blankCodes(), search)).error(UserForms.LOOKUP_FAILED)
            .withFocus("searchUserId");
    }

    private static Fields fields(ListState st, List<String> sel, String search) {
        List<Row> rows = new ArrayList<>(PAGE_SIZE);
        for (int i = 0; i < PAGE_SIZE; i++) {
            if (i < st.rows().size()) {
                UserRow u = st.rows().get(i);
                rows.add(new Row(sel.get(i), u.userId(), u.firstName(), u.lastName(), u.userType()));
            } else {
                rows.add(new Row(sel.get(i), "", "", "", ""));
            }
        }
        return new Fields(search, String.format("%08d", st.page()), rows);
    }

    private static List<String> codes(List<String> sel) {
        List<String> out = new ArrayList<>(PAGE_SIZE);
        for (int i = 0; i < PAGE_SIZE; i++) {
            String s = sel != null && i < sel.size() && sel.get(i) != null ? sel.get(i) : "";
            out.add(UserForms.field(s, 1));
        }
        return out;
    }

    private static List<String> blankCodes() {
        return Collections.nCopies(PAGE_SIZE, "");
    }
}
