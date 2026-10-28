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
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * COUSR03C delete user (transaction CU03). ADMIN only (C-ROLE). PF5 deletes only the user fetched on
 * this screen (Q-COUSR03C-01); the fetched screen is the confirmation step.
 */
@RestController
@RequestMapping("/api/screens/COUSR03C")
@Tag(name = "COUSR03C", description = "Delete user")
public class UserDeleteController {

    static final String PROMPT = "Press PF5 key to delete this user ...";
    /** Q-COUSR03C-02 (NEW). */
    static final String OWN_ID = "You cannot delete your own User ID...";
    static final String STATE = "carddemo.COUSR03C";

    /** First Name, Last Name, and User Type are output-only on this screen. */
    public record Fields(String userId, String firstName, String lastName, String userType) {
        static final Fields EMPTY = new Fields("", "", "", "");
    }

    public record Request(Key key, String userId) {
    }

    record DeleteState(String caller, UserDetailView fetched) implements Serializable {
        DeleteState forget() {
            return new DeleteState(caller, null);
        }
    }

    record UserDetailView(String userId, String firstName, String lastName, String userType) implements Serializable {
    }

    private final UserMaintenance users;
    private final ActiveSessions sessions;

    public UserDeleteController(UserMaintenance users, ActiveSessions sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    @GetMapping
    @Operation(summary = "First entry: the user selected on the list is fetched, otherwise an empty screen (BR-COUSR03C-02)")
    public ScreenResponse<Fields> show(HttpSession session) {
        UserScreens.Selection sel = UserScreens.take(session, "COUSR03C");
        DeleteState st = new DeleteState(sel != null ? "COUSR00C" : "COADM01C", null);
        if (sel == null) {
            session.setAttribute(STATE, st);
            return ScreenResponse.show(Fields.EMPTY).withFocus("userId");
        }
        return fetch(session, st, sel.userId());
    }

    @PostMapping
    @Operation(summary = "Delete User turn: ENTER fetches, PF5 deletes the fetched user, PF3 back, PF4 clears, PF12 admin menu")
    public ScreenResponse<Fields> turn(@RequestBody Request req, HttpSession session) {
        Key key = req.key() == null ? Key.ENTER : req.key();
        DeleteState st = session.getAttribute(STATE) instanceof DeleteState s ? s : new DeleteState("COADM01C", null);
        String userId = UserForms.userId(req.userId());
        return switch (key) {
            case ENTER -> fetch(session, st, userId);
            case PF3 -> ScreenResponse.navigate(st.caller());
            case PF4 -> {
                session.setAttribute(STATE, st.forget());
                yield ScreenResponse.show(Fields.EMPTY).withFocus("userId");
            }
            case PF5 -> st.fetched() != null && st.fetched().userId().equals(userId) ? delete(session, st)
                : fetch(session, st, userId);
            case PF12 -> ScreenResponse.navigate("COADM01C");
            default -> ScreenResponse.show(shown(st, req)).error(CommonMessages.INVALID_KEY).withFocus("userId");
        };
    }

    private ScreenResponse<Fields> fetch(HttpSession session, DeleteState st, String userId) {
        session.setAttribute(STATE, st.forget());
        if (blank(userId)) {
            return ScreenResponse.show(Fields.EMPTY).error(UserForms.ID_EMPTY).withFocus("userId");
        }
        Optional<UserDetail> found;
        try {
            found = users.find(userId);
        } catch (DataAccessException e) {
            return ScreenResponse.show(new Fields(userId, "", "", "")).error(UserForms.LOOKUP_FAILED).withFocus("userId");
        }
        if (found.isEmpty()) {
            return ScreenResponse.show(new Fields(userId, "", "", "")).error(UserForms.ID_NOT_FOUND).withFocus("userId");
        }
        UserDetail u = found.get();
        UserDetailView view = new UserDetailView(u.userId(), u.firstName(), u.lastName(), u.userType());
        session.setAttribute(STATE, new DeleteState(st.caller(), view));
        return ScreenResponse.show(fields(view)).withMessage(PROMPT, MessageColor.NEUTRAL).withFocus("userId");
    }

    private ScreenResponse<Fields> delete(HttpSession session, DeleteState st) {
        UserDetailView u = st.fetched();
        if (u.userId().equals(CurrentUser.require().userId())) {
            return ScreenResponse.show(fields(u)).error(OWN_ID).withFocus("userId");
        }
        boolean deleted;
        try {
            deleted = users.delete(CurrentUser.require().userId(), u.userId());
        } catch (DataAccessException e) {
            return ScreenResponse.show(fields(u)).error(UserForms.UPDATE_FAILED).withFocus("userId");
        }
        session.setAttribute(STATE, st.forget());
        if (!deleted) {
            return ScreenResponse.show(new Fields(u.userId(), "", "", "")).error(UserForms.ID_NOT_FOUND).withFocus("userId");
        }
        sessions.endAll(u.userId());
        return ScreenResponse.show(Fields.EMPTY)
            .withMessage("User " + u.userId() + " has been deleted ...", MessageColor.GREEN).withFocus("userId");
    }

    /** Invalid key: the screen is re-sent as it was (typed ID, displayed details). */
    private static Fields shown(DeleteState st, Request req) {
        String typed = UserForms.field(req.userId(), UserForms.ID_LEN);
        UserDetailView u = st.fetched();
        return u == null ? new Fields(typed, "", "", "") : new Fields(typed, u.firstName(), u.lastName(), u.userType());
    }

    private static Fields fields(UserDetailView u) {
        return new Fields(u.userId(), u.firstName(), u.lastName(), u.userType());
    }
}
