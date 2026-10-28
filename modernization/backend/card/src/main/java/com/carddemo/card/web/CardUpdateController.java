package com.carddemo.card.web;

import com.carddemo.card.internal.CardForms;
import com.carddemo.card.internal.CardForms.KeyEdit;
import com.carddemo.card.internal.CardLookup;
import com.carddemo.card.internal.CardRepository.CardRecord;
import com.carddemo.card.internal.CardUpdateService;
import com.carddemo.card.internal.CardUpdateService.Changed;
import com.carddemo.card.internal.CardUpdateService.LockFailed;
import com.carddemo.card.internal.CardUpdateService.Outcome;
import com.carddemo.card.internal.CardUpdateService.Saved;
import com.carddemo.card.internal.CardUpdateService.Snapshot;
import com.carddemo.card.internal.CardUpdateService.WriteFailed;
import com.carddemo.common.mask.Masking;
import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.ScreenResponse;
import com.carddemo.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * COCRDUPC credit card update (transaction CCUP): fetch, edit name / status / expiry month and
 * year, confirm with F5. The state machine of CCUP-CHANGE-ACTION is kept in the session.
 */
@RestController
@RequestMapping("/api/screens/COCRDUPC")
@Tag(name = "COCRDUPC", description = "Credit card update")
public class CardUpdateController {

    static final String DETAILS_SHOWN = "Details of selected card shown above";
    static final String UPDATE_PROMPT = "Update card details presented above.";
    static final String VALIDATED = "Changes validated.Press F5 to save";
    static final String COMMITTED = "Changes committed to database";
    static final String UNSUCCESSFUL = "Changes unsuccessful. Please try again";
    static final String LOCK_FAILED = "Could not lock record for update";
    static final String WRITE_FAILED = "Update of record failed";
    static final String CHANGED = "Record changed by some one else. Please review";
    static final String NO_CHANGE = "No change detected with respect to values fetched.";
    static final String NAME_MISSING = "Card name not provided";
    static final String NAME_ALPHA = "Card name can only contain alphabets and spaces";
    static final String STATUS_YN = "Card Active Status must be Y or N";
    static final String MONTH_BAD = "Card expiry month must be between 1 and 12";
    static final String YEAR_BAD = "Invalid card expiry year";
    static final String STATE = "carddemo.COCRDUPC";

    static final String NAME = "name";
    static final String STATUS = "status";
    static final String MONTH = "expMonth";
    static final String YEAR = "expYear";

    /** CCUP-CHANGE-ACTION: not fetched, S, E, N, C, L, F. */
    enum Mode {
        NOT_FETCHED(""), S("S"), E("E"), N("N"), C("C"), L("L"), F("F");

        final String code;

        Mode(String code) {
            this.code = code;
        }
    }

    /**
     * @param state          CCUP-CHANGE-ACTION ({@code ""} = details not fetched)
     * @param errors         fields in error style
     * @param keysEditable   account and card can be typed (BR-COCRDUPC-15)
     * @param detailsEditable name, status, month, and year can be typed
     * @param confirmKeys    the {@code F5=Save F12=Cancel} legend is shown (state N, Q-COCRDUPC-15)
     */
    public record Fields(String accountId, String cardNum, String name, String status, String expMonth, String expYear,
                         String info, List<String> errors, boolean keysEditable, boolean detailsEditable,
                         boolean confirmKeys, String state) {
    }

    public record Request(Key key, String accountId, String cardNum, String name, String status, String expMonth,
                          String expYear) {
    }

    /** CCUP-NEW-*: the detail values received, blank (LOW-VALUES) as {@code ""}. */
    record Entered(String name, String status, String month, String year) implements Serializable {
    }

    /** {@code card}: the full card number behind the masked value on screen. */
    record UpdState(boolean fromList, Mode mode, Long account, String card, Snapshot old, Entered entered)
        implements Serializable {
        static final UpdState SEARCH = new UpdState(false, Mode.NOT_FETCHED, null, null, null, null);
    }

    private record Edited(String message, List<String> errors) {
    }

    private final CardLookup lookup;
    private final CardUpdateService updates;

    public CardUpdateController(CardLookup lookup, CardUpdateService updates) {
        this.lookup = lookup;
        this.updates = updates;
    }

    @GetMapping
    @Operation(summary = "Entry: from the list the selected card is fetched at once (NAV-COCRDUPC-04); otherwise the search screen")
    public ScreenResponse<Fields> show(HttpSession session) {
        CardScreens.Selection sel = CardScreens.take(session, "COCRDUPC");
        if (sel == null) {
            return search(session);
        }
        return fetch(session, new UpdState(true, Mode.NOT_FETCHED, sel.accountId(), sel.cardNum(), null, null));
    }

    @PostMapping
    @Operation(summary = "Update turn: ENTER fetches or validates, F5 saves in state N, F12 re-reads, F3 returns to the caller")
    public ScreenResponse<Fields> turn(@RequestBody Request req, HttpSession session) {
        UpdState st = session.getAttribute(STATE) instanceof UpdState s ? s : UpdState.SEARCH;
        Key key = effective(req.key(), st.mode());
        if (key == Key.PF3) {
            return exit(session, st);
        }
        if (st.mode() == Mode.C || st.mode() == Mode.L || st.mode() == Mode.F) {
            // BR-COCRDUPC-09: after a save attempt the next key leaves (list path) or starts a new search (menu path).
            return st.fromList() ? exit(session, st) : search(session);
        }
        if (key == Key.PF12) {
            return fetch(session, new UpdState(st.fromList(), Mode.NOT_FETCHED, st.account(), st.card(), null, null));
        }
        return switch (st.mode()) {
            case NOT_FETCHED -> searchTurn(session, st, req);
            case S, E -> editTurn(session, st, req);
            case N -> key == Key.PF5 ? saveTurn(session, st) : render(session, st, null, List.of(), false);
            default -> throw new IllegalStateException("UNEXPECTED DATA SCENARIO");
        };
    }

    /** NAV-COCRDUPC-01: PF5 counts only in state N and PF12 only once details are fetched; other keys act as ENTER. */
    private static Key effective(Key key, Mode mode) {
        Key k = key == null ? Key.ENTER : key.folded();
        if (k == Key.PF3 || (k == Key.PF5 && mode == Mode.N) || (k == Key.PF12 && mode != Mode.NOT_FETCHED)) {
            return k;
        }
        return Key.ENTER;
    }

    /** NAV-COCRDUPC-02: unsaved changes are discarded; the list path returns to COCRDLIC. */
    private static ScreenResponse<Fields> exit(HttpSession session, UpdState st) {
        session.removeAttribute(STATE);
        return ScreenResponse.navigate(st.fromList() ? "COCRDLIC" : "COMEN01C");
    }

    private static ScreenResponse<Fields> search(HttpSession session) {
        session.setAttribute(STATE, UpdState.SEARCH);
        return ScreenResponse.show(new Fields("", "", "", "", "", "", CardForms.PROMPT, List.of(), true, false, false, ""))
            .withFocus(CardForms.ACCOUNT);
    }

    private ScreenResponse<Fields> searchTurn(HttpSession session, UpdState st, Request req) {
        if (st.fromList()) {
            // Q-COCRDUPC-09: the keys from the list stay read-only; ENTER only re-reads them.
            return fetch(session, st);
        }
        KeyEdit edit = CardForms.editKeys(req.accountId(), req.cardNum(), st.card(), true);
        UpdState next = new UpdState(false, Mode.NOT_FETCHED, edit.account(), edit.card(), null, null);
        if (!edit.ok()) {
            session.setAttribute(STATE, next);
            String focus = edit.errors().contains(CardForms.ACCOUNT) || !edit.errors().contains(CardForms.CARD)
                ? CardForms.ACCOUNT : CardForms.CARD;
            return ScreenResponse.show(new Fields(edit.accountShown(), edit.cardShown(), "", "", "", "", CardForms.PROMPT,
                edit.errors(), true, false, false, "")).error(edit.message()).withFocus(focus);
        }
        return fetch(session, next);
    }

    /** 9000-READ-DATA: found gives state S with the fetched values; otherwise the state stays not fetched. */
    private ScreenResponse<Fields> fetch(HttpSession session, UpdState st) {
        Optional<CardRecord> card;
        try {
            card = lookup.find(st.account(), st.card());
        } catch (DataAccessException e) {
            UpdState next = new UpdState(st.fromList(), Mode.NOT_FETCHED, st.account(), st.card(), null, null);
            return render(session, next, CardForms.FILE_ERROR, List.of(CardForms.ACCOUNT), false);
        }
        if (card.isEmpty()) {
            UpdState next = new UpdState(st.fromList(), Mode.NOT_FETCHED, st.account(), st.card(), null, null);
            return render(session, next, CardForms.NOT_FOUND, List.of(CardForms.ACCOUNT, CardForms.CARD), false);
        }
        UpdState next = new UpdState(st.fromList(), Mode.S, st.account(), st.card(), Snapshot.of(card.get()), null);
        return render(session, next, null, List.of(), false);
    }

    /** 1200-EDIT-MAP-INPUTS once details are fetched (states S and E). */
    private ScreenResponse<Fields> editTurn(HttpSession session, UpdState st, Request req) {
        Entered in = new Entered(detail(req.name(), 50), detail(req.status(), 1), detail(req.expMonth(), 2),
            detail(req.expYear(), 4));
        Snapshot old = st.old();
        boolean same = upper(in.name()).equals(old.name()) && upper(in.status()).equals(upper(old.status()))
            && upper(in.month()).equals(old.month()) && upper(in.year()).equals(old.year());
        if (same) {
            // BR-COCRDUPC-03: no edits, state unchanged; state E keeps showing what was received.
            UpdState next = st.mode() == Mode.E ? new UpdState(st.fromList(), Mode.E, st.account(), st.card(), old, in) : st;
            return render(session, next, NO_CHANGE, List.of(), true);
        }
        Edited e = edit(in);
        Mode mode = e.message() == null ? Mode.N : Mode.E;
        return render(session, new UpdState(st.fromList(), mode, st.account(), st.card(), old, in), e.message(), e.errors(),
            false);
    }

    private ScreenResponse<Fields> saveTurn(HttpSession session, UpdState st) {
        Entered in = st.entered();
        Outcome outcome = updates.save(CurrentUser.require().userId(), st.card(), st.old(), in.name(), in.status(),
            Integer.parseInt(in.month()), Integer.parseInt(in.year()));
        return switch (outcome) {
            case Saved s -> render(session, withMode(st, Mode.C), null, List.of(), false);
            case Changed c -> render(session, new UpdState(st.fromList(), Mode.S, st.account(), st.card(),
                Snapshot.of(c.current()), null), CHANGED, List.of(), false);
            case LockFailed l -> render(session, withMode(st, Mode.L), LOCK_FAILED, List.of(), false);
            case WriteFailed w -> render(session, withMode(st, Mode.F), WRITE_FAILED, List.of(), false);
        };
    }

    /** 1230-1260: all four edits run; only the first message is kept (VR-COCRDUPC-12). */
    static Edited edit(Entered in) {
        List<String> errors = new ArrayList<>();
        String message = null;
        String name = in.name();
        if (name.isBlank() || name.chars().allMatch(ch -> ch == '0')) {
            message = NAME_MISSING;
            errors.add(NAME);
        } else if (!name.chars().allMatch(ch -> ch == ' ' || (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z'))) {
            message = NAME_ALPHA;
            errors.add(NAME);
        }
        String status = in.status();
        if (!status.equals("Y") && !status.equals("N")) {
            message = message == null ? STATUS_YN : message;
            errors.add(STATUS);
        }
        if (month(in.month()) == 0) {
            message = message == null ? MONTH_BAD : message;
            errors.add(MONTH);
        }
        if (year(in.year()) == 0) {
            message = message == null ? YEAR_BAD : message;
            errors.add(YEAR);
        }
        return new Edited(message, List.copyOf(errors));
    }

    /** Q-COCRDUPC-19: an integer 1-12 with or without a leading zero; 0 when invalid. */
    static int month(String s) {
        if (s.isEmpty() || s.length() > 2 || !s.chars().allMatch(Character::isDigit)) {
            return 0;
        }
        int m = Integer.parseInt(s);
        return m >= 1 && m <= 12 ? m : 0;
    }

    /** VR-COCRDUPC-11: four digits, 1950-2099; 0 when invalid. */
    static int year(String s) {
        if (s.length() != 4 || !s.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            return 0;
        }
        int y = Integer.parseInt(s);
        return y >= 1950 && y <= 2099 ? y : 0;
    }

    /** A blank-type value (spaces, {@code *}, or zeros) is shown as {@code *} when its field is in error. */
    private static boolean blankType(String field, String value) {
        return switch (field) {
            case NAME -> value.isBlank() || value.chars().allMatch(ch -> ch == '0');
            case STATUS -> value.isBlank() || value.equals("0");
            case MONTH -> value.isBlank() || value.equals("00");
            case YEAR -> value.isBlank() || value.equals("0000");
            default -> false;
        };
    }

    private static ScreenResponse<Fields> render(HttpSession session, UpdState st, String message, List<String> errors,
                                                 boolean noChange) {
        session.setAttribute(STATE, st);
        String account = st.account() == null ? "" : CardForms.accountText(st.account());
        String card = st.card() == null ? "" : Masking.card(st.card());
        Mode m = st.mode();
        String name = "";
        String status = "";
        String month = "";
        String year = "";
        if (m == Mode.S) {
            name = st.old().name();
            status = st.old().status();
            month = st.old().month();
            year = st.old().year();
        } else if (m != Mode.NOT_FETCHED) {
            Entered in = st.entered();
            name = shown(NAME, in.name(), errors);
            status = shown(STATUS, in.status(), errors);
            month = shown(MONTH, in.month(), errors);
            year = shown(YEAR, in.year(), errors);
        }
        String info = switch (m) {
            case NOT_FETCHED -> CardForms.PROMPT;
            case S -> DETAILS_SHOWN;
            case E -> UPDATE_PROMPT;
            case N -> VALIDATED;
            case C -> COMMITTED;
            case L, F -> UNSUCCESSFUL;
        };
        boolean keysEditable = (m == Mode.NOT_FETCHED && !st.fromList()) || m == Mode.L || m == Mode.F;
        boolean detailsEditable = m == Mode.S || m == Mode.E;
        Fields f = new Fields(account, card, name, status, month, year, info, errors, keysEditable, detailsEditable,
            m == Mode.N, m.code);
        ScreenResponse<Fields> r = ScreenResponse.show(f).withFocus(focus(m, errors, noChange));
        return message == null ? r : r.error(message);
    }

    /** BR-COCRDUPC-16 cursor, first match wins. */
    private static String focus(Mode m, List<String> errors, boolean noChange) {
        if (m == Mode.S || noChange) {
            return NAME;
        }
        for (String f : List.of(CardForms.ACCOUNT, CardForms.CARD, NAME, STATUS, MONTH, YEAR)) {
            if (errors.contains(f)) {
                return f;
            }
        }
        return CardForms.ACCOUNT;
    }

    private static String shown(String field, String value, List<String> errors) {
        return errors.contains(field) && blankType(field, value) ? "*" : value;
    }

    private static UpdState withMode(UpdState st, Mode mode) {
        return new UpdState(st.fromList(), mode, st.account(), st.card(), st.old(), st.entered());
    }

    /** 1100-RECEIVE-MAP: {@code *} or spaces become blank; trailing spaces are not significant. */
    private static String detail(String s, int len) {
        String v = s == null ? "" : s;
        v = v.length() > len ? v.substring(0, len) : v;
        v = v.stripTrailing();
        return v.isBlank() || v.equals("*") ? "" : v;
    }

    private static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }
}
