package com.carddemo.refdata.web;

import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.ScreenResponse;
import com.carddemo.refdata.internal.TransactionTypeStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** COTRTLIC transaction type list (transaction CTLI). ADMIN only (Q-COTRTLIC-05), enforced in SecurityConfig. */
@RestController
@RequestMapping("/api/screens/COTRTLIC")
@Tag(name = "COTRTLIC", description = "Transaction type list with in-line update and delete")
public class TranTypeListController {

    /**
     * One of the seven list rows.
     *
     * @param select              action code as shown ({@code D}, {@code U}, other text, or empty)
     * @param selectable          the select field accepts input (rows with data, unless a filter is in error)
     * @param selectInvalid       the select field is in error (red)
     * @param highlighted         the row is the one a pending delete or update acts on
     * @param descriptionEditable the description can be edited (update mode)
     * @param descriptionInvalid  the edited description failed its edit (red)
     */
    public record Row(String select, String type, String description, boolean selectable, boolean selectInvalid,
        boolean highlighted, boolean descriptionEditable, boolean descriptionInvalid) {
    }

    /**
     * @param page          PAGENO
     * @param filtersLocked a row action is pending: both filters are shown but cannot be changed (BR-COTRTLIC-20)
     * @param info          INFOMSG centered in 45 characters, or null when the info line is dark
     */
    public record Fields(String page, String typeFilter, String descFilter, boolean filtersLocked, boolean typeFilterInvalid,
        boolean descFilterInvalid, List<Row> rows, String info) {
    }

    public record RowInput(String select, String description) {
    }

    /** The screen as the user left it; type codes are not sent: the server copy of each row is used. */
    public record Request(Key key, String typeFilter, String descFilter, List<RowInput> rows) {
    }

    private final TransactionTypeStore store;

    public TranTypeListController(TransactionTypeStore store) {
        this.store = store;
    }

    @GetMapping
    @Operation(summary = "First entry: page 1 of all transaction types (BR-COTRTLIC-01, -02)")
    public ScreenResponse<Fields> show(HttpSession session) {
        ListState state = new ListState();
        return finish(new ListTurn(store, state).firstEntry(), state, session);
    }

    @PostMapping
    @Operation(summary = "List turn: ENTER, PF2 add, PF3 exit, PF7/PF8 paging, PF10 confirm; other keys act as ENTER")
    public ScreenResponse<Fields> turn(@RequestBody Request req, HttpSession session) {
        ListState state = session.getAttribute(TranTypeNavigation.LIST_STATE) instanceof ListState s ? s : null;
        if (state == null) {
            // No conversation yet (BR-COTRTLIC-03): start afresh without receiving the screen.
            return show(session);
        }
        return finish(new ListTurn(store, state).turn(req), state, session);
    }

    private static ScreenResponse<Fields> finish(ListTurn.Result r, ListState state, HttpSession session) {
        if (r.endConversation()) {
            session.removeAttribute(TranTypeNavigation.LIST_STATE);
            if (r.toMaintenance()) {
                session.setAttribute(TranTypeNavigation.CALLER, "COTRTLIC");
            }
        } else {
            session.setAttribute(TranTypeNavigation.LIST_STATE, state);
        }
        return r.response();
    }
}
