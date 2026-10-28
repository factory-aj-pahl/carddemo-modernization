package com.carddemo.iam.web;

import com.carddemo.common.msg.CommonMessages;
import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.MessageColor;
import com.carddemo.common.screen.ScreenResponse;
import com.carddemo.common.security.CurrentUser;
import com.carddemo.iam.internal.SignonService;
import com.carddemo.iam.security.ActiveSessions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** COSGN00C sign-on screen (transaction CC00). */
@RestController
@RequestMapping("/api/screens/COSGN00C")
@Tag(name = "COSGN00C", description = "Sign-on")
public class SignonController {

    /** Q-COSGN00C-04 / D-SEC-EXTRAS generic failure text. */
    public static final String INVALID_CREDENTIALS = "Invalid User ID or Password. Try again ...";

    public record Fields(String userId) {
    }

    public record Request(Key key, String userId, String password) {
    }

    private final SignonService signon;
    private final SecurityContextRepository contexts;
    private final ActiveSessions sessions;

    public SignonController(SignonService signon, SecurityContextRepository contexts, ActiveSessions sessions) {
        this.signon = signon;
        this.contexts = contexts;
        this.sessions = sessions;
    }

    @GetMapping
    @Operation(summary = "First entry: empty sign-on screen (BR-COSGN00C-01)")
    public ScreenResponse<Fields> show() {
        return ScreenResponse.show(new Fields("")).withFocus("userId");
    }

    @PostMapping
    @Operation(summary = "Sign-on screen turn: ENTER signs on, PF3 exits, other keys are invalid")
    public ScreenResponse<Fields> turn(@RequestBody Request req, HttpServletRequest http, HttpServletResponse res) {
        Key key = req.key() == null ? Key.ENTER : req.key();
        if (key == Key.PF3) {
            HttpSession s = http.getSession(false);
            if (s != null) {
                s.invalidate();
            }
            return ScreenResponse.<Fields>navigate("EXIT", CommonMessages.THANK_YOU, MessageColor.NEUTRAL);
        }
        if (key != Key.ENTER) {
            return ScreenResponse.show(new Fields("")).error(CommonMessages.INVALID_KEY).withFocus("userId");
        }
        String userId = cut(req.userId());
        String password = req.password() == null ? "" : req.password();
        if (userId.isBlank()) {
            return ScreenResponse.show(new Fields("")).error("Please enter User ID ...").withFocus("userId");
        }
        String upperId = userId.stripTrailing().toUpperCase();
        if (password.isBlank()) {
            return ScreenResponse.show(new Fields(upperId)).error("Please enter Password ...").withFocus("password");
        }
        SignonService.Outcome outcome;
        try {
            outcome = signon.authenticate(upperId.strip(), password, http.getRemoteAddr());
        } catch (DataAccessException e) {
            return ScreenResponse.show(new Fields(upperId)).error("Unable to verify the User ...").withFocus("userId");
        }
        if (outcome instanceof SignonService.Success ok) {
            startSession(ok.user(), http, res);
            return ScreenResponse.navigate(ok.user().isAdmin() ? "COADM01C" : "COMEN01C");
        }
        return ScreenResponse.show(new Fields(upperId)).error(INVALID_CREDENTIALS).withFocus("password");
    }

    private void startSession(CurrentUser user, HttpServletRequest http, HttpServletResponse res) {
        HttpSession old = http.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        sessions.register(user.userId(), http.getSession(true));
        var auth = new UsernamePasswordAuthenticationToken(user, null,
            List.of(new SimpleGrantedAuthority(user.role().authority())));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        contexts.saveContext(ctx, http, res);
    }

    /** BMS maps the 8-byte field: longer input is cut. */
    private static String cut(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 8 ? s.substring(0, 8) : s;
    }
}
