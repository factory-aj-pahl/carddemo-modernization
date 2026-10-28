package com.carddemo.refdata.web;

import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.ScreenResponse;
import com.carddemo.refdata.internal.TransactionTypeStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** COTRTUPC single transaction type add, update, and delete (transaction CTTU). ADMIN only (Q-COTRTUPC-10), enforced in SecurityConfig. */
@RestController
@RequestMapping("/api/screens/COTRTUPC")
@Tag(name = "COTRTUPC", description = "Transaction type maintenance: look up, add, update, or delete one type")
public class TranTypeMaintController {

    private static final Logger LOG = LoggerFactory.getLogger(TranTypeMaintController.class);

    /** PF-key labels shown on line 24 (BR-COTRTUPC-26). */
    public record Keys(boolean enterExit, boolean delete, boolean save, boolean cancel) {
    }

    /** ABEND-DATA (CSMSG02Y) shown instead of the screen when the task abends (ERR-COTRTUPC-09, -10). */
    public record Abend(String code, String culprit, String reason, String message) {
    }

    /**
     * @param typeCode            TRTYPCD as shown ({@code *} marks a missing value)
     * @param description         TRTYDSC as shown ({@code *} marks a missing value)
     * @param typeEditable        the type field accepts input
     * @param descriptionEditable the description field accepts input
     * @param typeInvalid         the type field is red
     * @param descriptionInvalid  the description field is red
     * @param info                INFOMSG: the prompt centered on 40 columns in 45 characters
     * @param abend               set when the turn abended; the other fields are then empty
     */
    public record Fields(String typeCode, String description, boolean typeEditable, boolean descriptionEditable,
        boolean typeInvalid, boolean descriptionInvalid, String info, Keys keys, Abend abend) {
    }

    /** The screen as the user left it; values of protected fields are ignored (the session copy is used). */
    public record Request(Key key, String typeCode, String description) {
    }

    private final TransactionTypeStore store;

    public TranTypeMaintController(TransactionTypeStore store) {
        this.store = store;
    }

    @GetMapping
    @Operation(summary = "First entry: empty prompt for a transaction type (BR-COTRTUPC-02)")
    public ScreenResponse<Fields> show(HttpSession session) {
        MaintState state = new MaintState();
        return finish(guarded(() -> new MaintTurn(store, state).firstEntry()), state, session);
    }

    @PostMapping
    @Operation(summary = "Maintenance turn: ENTER, PF3 exit, PF4 delete, PF5 add or save, PF12 cancel")
    public ScreenResponse<Fields> turn(@RequestBody Request req, HttpSession session) {
        MaintState state = session.getAttribute(TranTypeNavigation.MAINT_STATE) instanceof MaintState s ? s : null;
        if (state == null) {
            return show(session);
        }
        return finish(guarded(() -> new MaintTurn(store, state).turn(req)), state, session);
    }

    /** HANDLE ABEND LABEL(ABEND-ROUTINE): any unexpected failure ends the task with the abend data. */
    private static MaintTurn.Result guarded(Supplier<MaintTurn.Result> turn) {
        try {
            return turn.get();
        } catch (RuntimeException e) {
            LOG.error("COTRTUPC abend", e);
            return MaintTurn.abend("9999", MaintTurn.UNEXPECTED_ABEND);
        }
    }

    private static ScreenResponse<Fields> finish(MaintTurn.Result r, MaintState state, HttpSession session) {
        if (r.exit()) {
            // NAV-COTRTUPC-02: back to the program that started the conversation (Q-COTRTUPC-05).
            String caller = session.getAttribute(TranTypeNavigation.CALLER) instanceof String c ? c : TranTypeNavigation.ADMIN_MENU;
            session.removeAttribute(TranTypeNavigation.CALLER);
            session.removeAttribute(TranTypeNavigation.MAINT_STATE);
            return ScreenResponse.navigate(caller);
        }
        if (r.ended()) {
            session.removeAttribute(TranTypeNavigation.MAINT_STATE);
        } else {
            session.setAttribute(TranTypeNavigation.MAINT_STATE, state);
        }
        return r.response();
    }
}
