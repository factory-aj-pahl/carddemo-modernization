package com.carddemo.iam.web;

import static com.carddemo.iam.internal.UserForms.blank;

import com.carddemo.common.msg.CommonMessages;
import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.MessageColor;
import com.carddemo.common.screen.ScreenResponse;
import com.carddemo.common.security.CurrentUser;
import com.carddemo.iam.internal.UserForms;
import com.carddemo.iam.internal.UserMaintenance;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * COUSR01C add user (transaction CU01). ADMIN only (C-ROLE). The password is never returned
 * (C-MASK); the account-link field is A-3.
 */
@RestController
@RequestMapping("/api/screens/COUSR01C")
@Tag(name = "COUSR01C", description = "Add user")
public class UserAddController {

    static final String DUPLICATE = "User ID already exist...";
    static final String ADD_FAILED = "Unable to Add User...";

    /** Output fields; there is no password field (C-MASK). */
    public record Fields(String firstName, String lastName, String userId, String userType, String accounts) {
        static final Fields EMPTY = new Fields("", "", "", "", "");
    }

    public record Request(Key key, String firstName, String lastName, String userId, String password, String userType,
                          String accounts) {
    }

    private final UserMaintenance users;

    public UserAddController(UserMaintenance users) {
        this.users = users;
    }

    @GetMapping
    @Operation(summary = "First entry: empty Add User screen, focus First Name (BR-COUSR01C-02)")
    public ScreenResponse<Fields> show() {
        return ScreenResponse.show(Fields.EMPTY).withFocus("firstName");
    }

    @PostMapping
    @Operation(summary = "Add User turn: ENTER adds, PF3/PF12 return to the admin menu, PF4 clears")
    public ScreenResponse<Fields> turn(@RequestBody Request req) {
        Key key = req.key() == null ? Key.ENTER : req.key();
        return switch (key) {
            case PF3, PF12 -> ScreenResponse.navigate("COADM01C");
            case PF4 -> ScreenResponse.show(Fields.EMPTY).withFocus("firstName");
            case ENTER -> add(req);
            default -> ScreenResponse.show(typed(req)).error(CommonMessages.INVALID_KEY).withFocus("firstName");
        };
    }

    private ScreenResponse<Fields> add(Request req) {
        String first = UserForms.field(req.firstName(), UserForms.NAME_LEN);
        String last = UserForms.field(req.lastName(), UserForms.NAME_LEN);
        String userId = UserForms.userId(req.userId());
        String password = UserForms.password(req.password());
        String type = UserForms.userType(req.userType());
        Fields typed = new Fields(first, last, userId, type, req.accounts() == null ? "" : req.accounts());
        ScreenResponse<Fields> screen = ScreenResponse.show(typed);
        if (blank(first)) {
            return screen.error(UserForms.FIRST_EMPTY).withFocus("firstName");
        }
        if (blank(last)) {
            return screen.error(UserForms.LAST_EMPTY).withFocus("lastName");
        }
        if (blank(userId)) {
            return screen.error(UserForms.ID_EMPTY).withFocus("userId");
        }
        if (!UserForms.validUserId(userId)) {
            return screen.error(UserForms.ID_FORMAT).withFocus("userId");
        }
        if (blank(password)) {
            return screen.error(UserForms.PASSWORD_EMPTY).withFocus("password");
        }
        if (blank(type)) {
            return screen.error(UserForms.TYPE_EMPTY).withFocus("userType");
        }
        if (!UserForms.validType(type)) {
            return screen.error(UserForms.TYPE_VALUE).withFocus("userType");
        }
        try {
            UserMaintenance.LinkCheck links = users.checkLinks(type, req.accounts());
            if (links.problem() != null) {
                return screen.error(links.problem().message()).withFocus(links.problem().focus());
            }
            users.add(CurrentUser.require().userId(), userId, first, last, password, type, links.accounts());
        } catch (DuplicateKeyException e) {
            return screen.error(DUPLICATE).withFocus("userId");
        } catch (DataAccessException e) {
            return screen.error(ADD_FAILED).withFocus("firstName");
        }
        return ScreenResponse.show(Fields.EMPTY)
            .withMessage("User " + userId + " has been added ...", MessageColor.GREEN).withFocus("firstName");
    }

    private static Fields typed(Request req) {
        return new Fields(UserForms.field(req.firstName(), UserForms.NAME_LEN), UserForms.field(req.lastName(), UserForms.NAME_LEN),
            UserForms.field(req.userId(), UserForms.ID_LEN), UserForms.field(req.userType(), 1),
            req.accounts() == null ? "" : req.accounts());
    }
}
