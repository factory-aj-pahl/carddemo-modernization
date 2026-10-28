package com.carddemo.iam.web;

import static com.carddemo.iam.internal.UserForms.blank;

import com.carddemo.common.msg.CommonMessages;
import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.MessageColor;
import com.carddemo.common.screen.ScreenResponse;
import com.carddemo.common.security.CurrentUser;
import com.carddemo.iam.internal.UserAdminRepository.UserDetail;
import com.carddemo.iam.internal.UserForms;
import com.carddemo.iam.internal.UserMaintenance;
import com.carddemo.iam.security.ActiveSessions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * COUSR02C update user (transaction CU02). ADMIN only (C-ROLE). The fetched user ID and row version
 * are held in the session (Q-COUSR02C-02, -05); the password is never returned (Q-COUSR02C-03).
 */
@RestController
@RequestMapping("/api/screens/COUSR02C")
@Tag(name = "COUSR02C", description = "Update user")
public class UserUpdateController {

    static final String PROMPT = "Press PF5 key to save your updates ...";
    static final String NO_CHANGE = "Please modify to update ...";
    /** Q-COUSR02C-04 (NEW). */
    static final String OWN_TYPE = "You cannot change your own User Type...";
    static final String STATE = "carddemo.COUSR02C";

    public record Fields(String userId, String firstName, String lastName, String userType, String accounts) {
        static final Fields EMPTY = new Fields("", "", "", "", "");
    }

    public record Request(Key key, String userId, String firstName, String lastName, String password, String userType,
                          String accounts) {
    }

    /** Caller for PF3 (NAV-COUSR02C-02) and the last fetch; {@code fetchedId} null when nothing is fetched. */
    record EditState(String caller, String fetchedId, String version) implements Serializable {
        EditState forget() {
            return new EditState(caller, null, null);
        }
    }

    private final UserMaintenance users;
    private final ActiveSessions sessions;

    public UserUpdateController(UserMaintenance users, ActiveSessions sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    @GetMapping
    @Operation(summary = "First entry: the user selected on the list is fetched, otherwise an empty screen (BR-COUSR02C-02)")
    public ScreenResponse<Fields> show(HttpSession session) {
        UserScreens.Selection sel = UserScreens.take(session, "COUSR02C");
        EditState st = new EditState(sel != null ? "COUSR00C" : "COADM01C", null, null);
        if (sel == null) {
            session.setAttribute(STATE, st);
            return ScreenResponse.show(Fields.EMPTY).withFocus("userId");
        }
        return fetch(session, st, sel.userId(), Fields.EMPTY);
    }

    @PostMapping
    @Operation(summary = "Update User turn: ENTER fetches, PF5 saves, PF3 saves changes and exits, PF4 clears, PF12 cancels")
    public ScreenResponse<Fields> turn(@RequestBody Request req, HttpSession session) {
        Key key = req.key() == null ? Key.ENTER : req.key();
        EditState st = session.getAttribute(STATE) instanceof EditState s ? s : new EditState("COADM01C", null, null);
        Fields typed = typed(req);
        switch (key) {
            case ENTER:
                return fetch(session, st, UserForms.userId(req.userId()), typed);
            case PF3:
                if (!pinned(st, req)) {
                    return ScreenResponse.navigate(st.caller());
                }
                return save(session, st, req, true);
            case PF4:
                session.setAttribute(STATE, st.forget());
                return ScreenResponse.show(Fields.EMPTY).withFocus("userId");
            case PF5:
                // Q-COUSR02C-02: without a fetch of the same ID, PF5 is a fetch and writes nothing.
                return pinned(st, req) ? save(session, st, req, false)
                    : fetch(session, st, UserForms.userId(req.userId()), typed);
            case PF12:
                return ScreenResponse.navigate("COADM01C");
            default:
                return ScreenResponse.show(typed).error(CommonMessages.INVALID_KEY);
        }
    }

    private static boolean pinned(EditState st, Request req) {
        return st.fetchedId() != null && st.fetchedId().equals(UserForms.userId(req.userId()));
    }

    private ScreenResponse<Fields> fetch(HttpSession session, EditState st, String userId, Fields typed) {
        if (blank(userId)) {
            session.setAttribute(STATE, st.forget());
            return ScreenResponse.show(typed).error(UserForms.ID_EMPTY).withFocus("userId");
        }
        Optional<UserDetail> found;
        try {
            found = users.find(userId);
        } catch (DataAccessException e) {
            session.setAttribute(STATE, st.forget());
            return ScreenResponse.show(typed).error(UserForms.LOOKUP_FAILED).withFocus("firstName");
        }
        if (found.isEmpty()) {
            session.setAttribute(STATE, st.forget());
            return ScreenResponse.show(new Fields(userId, "", "", "", "")).error(UserForms.ID_NOT_FOUND).withFocus("userId");
        }
        UserDetail u = found.get();
        session.setAttribute(STATE, new EditState(st.caller(), u.userId(), u.version()));
        return ScreenResponse.show(fields(u)).withMessage(PROMPT, MessageColor.NEUTRAL).withFocus("firstName");
    }

    private ScreenResponse<Fields> save(HttpSession session, EditState st, Request req, boolean exit) {
        String userId = st.fetchedId();
        String first = UserForms.field(req.firstName(), UserForms.NAME_LEN);
        String last = UserForms.field(req.lastName(), UserForms.NAME_LEN);
        String password = UserForms.password(req.password());
        String type = UserForms.userType(req.userType());
        String accountsRaw = req.accounts() == null ? "" : req.accounts();
        ScreenResponse<Fields> screen = ScreenResponse.show(new Fields(userId, first, last, type, accountsRaw));
        if (blank(first)) {
            return screen.error(UserForms.FIRST_EMPTY).withFocus("firstName");
        }
        if (blank(last)) {
            return screen.error(UserForms.LAST_EMPTY).withFocus("lastName");
        }
        if (blank(type)) {
            return screen.error(UserForms.TYPE_EMPTY).withFocus("userType");
        }
        if (!UserForms.validType(type)) {
            return screen.error(UserForms.TYPE_VALUE).withFocus("userType");
        }
        String actor = CurrentUser.require().userId();
        try {
            UserMaintenance.LinkCheck links = users.checkLinks(type, accountsRaw);
            if (links.problem() != null) {
                return screen.error(links.problem().message()).withFocus(links.problem().focus());
            }
            Optional<UserDetail> found = users.find(userId);
            if (found.isEmpty()) {
                session.setAttribute(STATE, st.forget());
                return screen.error(UserForms.ID_NOT_FOUND).withFocus("userId");
            }
            UserDetail current = found.get();
            if (!current.version().equals(st.version())) {
                return changedByOther(session, st, current);
            }
            boolean typeChanged = !type.equals(current.userType());
            if (typeChanged && userId.equals(actor)) {
                return screen.error(OWN_TYPE).withFocus("userType");
            }
            boolean passwordChanged = !blank(password) && !users.passwordMatches(password, current.passwordHash());
            boolean linksChanged = !links.accounts().equals(Set.copyOf(current.accounts()));
            List<String> changed = new ArrayList<>();
            if (!first.equals(current.firstName())) {
                changed.add("firstName");
            }
            if (!last.equals(current.lastName())) {
                changed.add("lastName");
            }
            if (passwordChanged) {
                changed.add("password");
            }
            if (typeChanged) {
                changed.add("userType");
            }
            if (linksChanged) {
                changed.add("accounts");
            }
            if (changed.isEmpty()) {
                // Q-COUSR02C-01: PF3 with nothing changed exits without a message.
                return exit ? ScreenResponse.navigate(st.caller()) : screen.error(NO_CHANGE).withFocus("firstName");
            }
            UserMaintenance.UpdateOutcome outcome = users.update(actor, userId, st.version(), first, last, type,
                passwordChanged ? password : null, linksChanged ? links.accounts() : null, changed);
            switch (outcome) {
                case NOT_FOUND:
                    session.setAttribute(STATE, st.forget());
                    return screen.error(UserForms.ID_NOT_FOUND).withFocus("userId");
                case CHANGED:
                    return changedByOther(session, st, users.find(userId).orElse(current));
                default:
                    break;
            }
            if ((typeChanged || passwordChanged) && !userId.equals(actor)) {
                sessions.endAll(userId);
            }
            String version = users.find(userId).map(UserDetail::version).orElse(st.version());
            session.setAttribute(STATE, new EditState(st.caller(), userId, version));
        } catch (DataAccessException e) {
            return screen.error(UserForms.UPDATE_FAILED).withFocus("firstName");
        }
        if (exit) {
            return ScreenResponse.navigate(st.caller());
        }
        return ScreenResponse.show(new Fields(userId, first, last, type, UserForms.formatAccounts(
                UserForms.accounts(accountsRaw).orElse(Set.of()))))
            .withMessage("User " + userId + " has been updated ...", MessageColor.GREEN).withFocus("firstName");
    }

    /** Q-COUSR02C-05: nothing written; the current stored values replace the edits. */
    private static ScreenResponse<Fields> changedByOther(HttpSession session, EditState st, UserDetail current) {
        session.setAttribute(STATE, new EditState(st.caller(), current.userId(), current.version()));
        return ScreenResponse.show(fields(current)).error(UserForms.CHANGED_BY_OTHER).withFocus("firstName");
    }

    private static Fields fields(UserDetail u) {
        return new Fields(u.userId(), u.firstName(), u.lastName(), u.userType(), UserForms.formatAccounts(u.accounts()));
    }

    private static Fields typed(Request req) {
        return new Fields(UserForms.field(req.userId(), UserForms.ID_LEN), UserForms.field(req.firstName(), UserForms.NAME_LEN),
            UserForms.field(req.lastName(), UserForms.NAME_LEN), UserForms.field(req.userType(), 1),
            req.accounts() == null ? "" : req.accounts());
    }
}
