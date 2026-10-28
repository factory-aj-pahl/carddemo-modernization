package com.carddemo.account.web;

import com.carddemo.account.internal.AccountRecord;
import com.carddemo.account.web.AccountUpdater.SaveFailed;
import com.carddemo.account.internal.CustomerRecord;
import com.carddemo.account.web.UpdateEdits.Group;
import com.carddemo.common.format.Pic;
import com.carddemo.common.mask.Masking;
import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.ScreenResponse;
import com.carddemo.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** COACTUPC update an account and its customer (transaction CAUP). */
@RestController
@RequestMapping("/api/screens/COACTUPC")
@Tag(name = "COACTUPC", description = "Update account")
public class AccountUpdateController {

    static final String PROMPT = "Enter or update id of account to update";
    static final String SHOWN = "Update account details presented above.";
    static final String VALIDATED = "Changes validated.Press F5 to save";
    static final String COMMITTED = "Changes committed to database";
    static final String UNSUCCESSFUL = "Changes unsuccessful. Please try again";
    static final String NO_INPUT = "No input received";
    static final String BAD_ACCOUNT = "Account Number if supplied must be a 11 digit Non-Zero Number";
    static final String NO_CHANGE = "No change detected with respect to values fetched.";
    static final String ACCOUNT_LOCK = "Could not lock account record for update";
    static final String CUSTOMER_LOCK = "Could not lock customer record for update";
    static final String UPDATE_FAILED = "Update of record failed";
    static final String CHANGED = "Record changed by some one else. Please review";
    static final String CONVERSATION_ATTR = "carddemo.account.update";

    /** ACUP-CHANGE-ACTION (BR-COACTUPC-01); SEARCH is "details not fetched". */
    public enum Mode { SEARCH, S, E, N, C, L, F }

    /**
     * CACTUPA as the client renders it: {@code values} by field name (see {@link UpdateForm#LENGTHS}),
     * the fields the user may edit, the fields in error style, the info line, and whether the
     * {@code F5=Save} and {@code F12=Cancel} legends are shown (BR-COACTUPC-21).
     */
    public record Fields(Mode mode, Map<String, String> values, List<String> editable, List<String> errorFields,
                         String info, boolean saveKey, boolean cancelKey) {
    }

    /** {@code fields} holds the submitted CACTUPA input fields by name. */
    public record Request(Key key, Map<String, String> fields) {
    }

    /** The server-side part of the COMMAREA (C-SESSION): state, fetched snapshot, and the validated save image. */
    static final class Conversation implements Serializable {
        Mode mode;
        AccountRecord account;
        CustomerRecord customer;
        LinkedHashMap<String, String> shown;
        AccountRecord newAccount;
        CustomerRecord newCustomer;
        LinkedHashMap<String, String> newShown;
        ArrayList<String> changed;
    }

    private final AccountLookup lookup;
    private final AccountUpdater updater;
    private final Clock clock;

    public AccountUpdateController(AccountLookup lookup, AccountUpdater updater, Clock clock) {
        this.lookup = lookup;
        this.updater = updater;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "First entry: empty search screen with the cursor on the account number (NAV-COACTUPC-03)")
    public ScreenResponse<Fields> show(HttpSession session) {
        session.removeAttribute(CONVERSATION_ATTR);
        return search(UpdateForm.empty(), List.of());
    }

    @PostMapping
    @Operation(summary = "Update turn: fetch, edit changed fields, confirm with F5, cancel with F12 (NAV-COACTUPC-08)")
    public ScreenResponse<Fields> turn(@RequestBody Request req, HttpSession session) {
        Key key = req.key() == null ? Key.ENTER : req.key().folded();
        if (key == Key.PF3) {
            session.removeAttribute(CONVERSATION_ATTR);
            return ScreenResponse.navigate("COMEN01C");
        }
        Map<String, String> in = UpdateForm.received(req.fields());
        Conversation conv = session.getAttribute(CONVERSATION_ATTR) instanceof Conversation c ? c : null;
        if (conv == null || conv.mode == Mode.SEARCH) {
            return fetch(session, in.get("accountId"));
        }
        switch (conv.mode) {
            case C, L, F -> {
                // BR-COACTUPC-16 / Q-COACTUPC-19: the input of the result screen is ignored.
                session.removeAttribute(CONVERSATION_ATTR);
                return search(UpdateForm.empty(), List.of());
            }
            case N -> {
                if (key == Key.PF12) {
                    return refetch(session, conv, null);
                }
                if (key == Key.PF5) {
                    return save(session, conv);
                }
                return confirm(conv);
            }
            default -> {
                if (key == Key.PF12) {
                    return refetch(session, conv, null);
                }
                return edit(session, conv, in);
            }
        }
    }

    /** VR-COACTUPC-01/-02, then BR-COACTUPC-02 with the Q-COACTUPC-20 stops. */
    private ScreenResponse<Fields> fetch(HttpSession session, String typed) {
        Map<String, String> values = UpdateForm.empty();
        String acct = UpdateEdits.norm(typed);
        if (acct.isBlank()) {
            values.put("accountId", "*");
            return search(values, List.of("accountId")).error(NO_INPUT);
        }
        values.put("accountId", acct);
        if (!acct.matches("[0-9]{11}") || Long.parseLong(acct) == 0) {
            return search(values, List.of("accountId")).error(BAD_ACCOUNT);
        }
        AccountLookup.Result r = lookup.find(CurrentUser.require(), Long.parseLong(acct));
        if (!r.complete()) {
            session.removeAttribute(CONVERSATION_ATTR);
            return search(values, r.accountInError() ? List.of("accountId") : List.of()).error(r.message());
        }
        return snapshot(session, new Conversation(), r);
    }

    /** PF12 (BR-COACTUPC-10) and the reload after a conflict (Q-COACTUPC-04): the stored values become the snapshot. */
    private ScreenResponse<Fields> refetch(HttpSession session, Conversation conv, String error) {
        AccountLookup.Result r = lookup.find(CurrentUser.require(), conv.account.accountId());
        if (!r.complete()) {
            session.removeAttribute(CONVERSATION_ATTR);
            Map<String, String> values = UpdateForm.empty();
            values.put("accountId", Pic.accountId(conv.account.accountId()));
            return search(values, r.accountInError() ? List.of("accountId") : List.of()).error(r.message());
        }
        ScreenResponse<Fields> res = snapshot(session, conv, r);
        return error == null ? res : res.error(error);
    }

    private ScreenResponse<Fields> snapshot(HttpSession session, Conversation conv, AccountLookup.Result r) {
        conv.mode = Mode.S;
        conv.account = r.account();
        conv.customer = r.customer();
        conv.shown = new LinkedHashMap<>(UpdateForm.of(r.account(), r.customer()));
        conv.newAccount = null;
        conv.newCustomer = null;
        conv.newShown = null;
        conv.changed = null;
        session.setAttribute(CONVERSATION_ATTR, conv);
        return detail(conv.mode, conv.shown, List.of(), SHOWN).withFocus("active");
    }

    /** States S and E (BR-COACTUPC-08, -09 with D-VALIDATE-CHANGED). */
    private ScreenResponse<Fields> edit(HttpSession session, Conversation conv, Map<String, String> in) {
        for (String p : UpdateForm.PROTECTED) {
            in.put(p, conv.shown.get(p));
        }
        Set<Group> changed = UpdateEdits.changed(conv.shown, in);
        if (changed.isEmpty()) {
            conv.mode = Mode.S;
            session.setAttribute(CONVERSATION_ATTR, conv);
            return detail(Mode.S, conv.shown, List.of(), SHOWN).error(NO_CHANGE).withFocus("active");
        }
        UpdateEdits.Result r = UpdateEdits.validate(changed, in, LocalDate.now(clock));
        LinkedHashMap<String, String> values = new LinkedHashMap<>(in);
        for (Group g : changed) {
            if (g.amount()) {
                String f = g.fields.get(0);
                values.put(f, UpdateEdits.shownAmount(in.get(f)));
            }
        }
        maskEcho(values);
        if (!r.ok()) {
            r.blanks.forEach(f -> values.put(f, "*"));
            conv.mode = Mode.E;
            session.setAttribute(CONVERSATION_ATTR, conv);
            return detail(Mode.E, values, new ArrayList<>(r.errors), SHOWN).error(r.message).withFocus(r.focus());
        }
        conv.mode = Mode.N;
        conv.newAccount = UpdateEdits.account(conv.account, changed, in);
        conv.newCustomer = UpdateEdits.customer(conv.customer, changed, in);
        conv.changed = new ArrayList<>(UpdateEdits.auditNames(changed));
        conv.newShown = values;
        session.setAttribute(CONVERSATION_ATTR, conv);
        return confirm(conv);
    }

    /** State N: everything protected, F5 and F12 shown (NAV-COACTUPC-07). */
    private ScreenResponse<Fields> confirm(Conversation conv) {
        return detail(Mode.N, conv.newShown, List.of(), VALIDATED);
    }

    /** BR-COACTUPC-15 with Q-COACTUPC-02, -03, -04, and -18: the image validated at state N is saved. */
    private ScreenResponse<Fields> save(HttpSession session, Conversation conv) {
        String error;
        try {
            updater.save(conv.newAccount, conv.newCustomer, CurrentUser.require().userId(), conv.changed);
            conv.mode = Mode.C;
            session.setAttribute(CONVERSATION_ATTR, conv);
            return detail(Mode.C, conv.newShown, List.of(), COMMITTED);
        } catch (SaveFailed e) {
            switch (e.outcome()) {
                case CHANGED -> {
                    return refetch(session, conv, CHANGED);
                }
                case ACCOUNT_LOCK_FAILED -> {
                    conv.mode = Mode.L;
                    error = ACCOUNT_LOCK;
                }
                case CUSTOMER_LOCK_FAILED -> {
                    conv.mode = Mode.L;
                    error = CUSTOMER_LOCK;
                }
                default -> {
                    conv.mode = Mode.F;
                    error = UPDATE_FAILED;
                }
            }
        }
        session.setAttribute(CONVERSATION_ATTR, conv);
        return detail(conv.mode, conv.newShown, List.of(), UNSUCCESSFUL).error(error);
    }

    /** C-MASK: typed SSN parts 1-2 and a typed government id are echoed masked; the browser keeps what was typed. */
    private static void maskEcho(Map<String, String> values) {
        if (!UpdateEdits.norm(values.get("ssn1")).isEmpty()) {
            values.put("ssn1", UpdateForm.SSN1_SHOWN);
        }
        if (!UpdateEdits.norm(values.get("ssn2")).isEmpty()) {
            values.put("ssn2", UpdateForm.SSN2_SHOWN);
        }
        values.put("govtId", Masking.keepLast(values.get("govtId"), 4));
    }

    private static ScreenResponse<Fields> search(Map<String, String> values, List<String> errors) {
        return ScreenResponse.show(new Fields(Mode.SEARCH, values, List.of("accountId"), errors, PROMPT, false, false))
            .withFocus("accountId");
    }

    /** BR-COACTUPC-18 protection and BR-COACTUPC-21 legends for a details screen. */
    private static ScreenResponse<Fields> detail(Mode mode, Map<String, String> values, List<String> errors, String info) {
        List<String> editable = new ArrayList<>();
        if (mode == Mode.S || mode == Mode.E) {
            UpdateForm.LENGTHS.keySet().stream().filter(f -> !UpdateForm.PROTECTED.contains(f)).forEach(editable::add);
        }
        boolean cancel = EnumSet.of(Mode.E, Mode.N, Mode.L, Mode.F).contains(mode);
        return ScreenResponse.show(new Fields(mode, values, editable, errors, info, mode == Mode.N, cancel));
    }
}
