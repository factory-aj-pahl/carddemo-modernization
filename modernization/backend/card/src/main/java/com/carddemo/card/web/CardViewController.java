package com.carddemo.card.web;

import com.carddemo.card.internal.CardForms;
import com.carddemo.card.internal.CardForms.KeyEdit;
import com.carddemo.card.internal.CardLookup;
import com.carddemo.card.internal.CardRepository.CardRecord;
import com.carddemo.common.mask.Masking;
import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.ScreenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * COCRDSLC credit card view (transaction CCDL): one card's name, status, and expiry, reached from
 * the card list (keys trusted and protected) or from main menu option 4 (keys typed). Read-only.
 */
@RestController
@RequestMapping("/api/screens/COCRDSLC")
@Tag(name = "COCRDSLC", description = "Credit card view")
public class CardViewController {

    static final String FOUND = "   Displaying requested details";
    static final String STATE = "carddemo.COCRDSLC";

    /**
     * @param errors        key fields in error style ({@code accountId}, {@code cardNum})
     * @param keysProtected true on the list path (BR-COCRDSLC-05)
     */
    public record Fields(String accountId, String cardNum, String name, String status, String expMonth, String expYear,
                         String info, List<String> errors, boolean keysProtected) {
    }

    public record Request(Key key, String accountId, String cardNum) {
    }

    /** {@code card}: the full card number behind the masked value on screen. */
    record ViewState(boolean fromList, Long account, String card) implements Serializable {
        static final ViewState MENU = new ViewState(false, null, null);
    }

    private final CardLookup lookup;

    public CardViewController(CardLookup lookup) {
        this.lookup = lookup;
    }

    @GetMapping
    @Operation(summary = "Entry: from the list the selected card is shown at once (BR-COCRDSLC-03); otherwise the empty search screen")
    public ScreenResponse<Fields> show(HttpSession session) {
        CardScreens.Selection sel = CardScreens.take(session, "COCRDSLC");
        if (sel == null) {
            session.setAttribute(STATE, ViewState.MENU);
            return ScreenResponse.show(new Fields("", "", "", "", "", "", CardForms.PROMPT, List.of(), false))
                .withFocus(CardForms.ACCOUNT);
        }
        ViewState st = new ViewState(true, sel.accountId(), sel.cardNum());
        session.setAttribute(STATE, st);
        return read(st, CardForms.accountText(sel.accountId()));
    }

    @PostMapping
    @Operation(summary = "View turn: PF3 returns to the caller; every other key re-edits the keys and re-reads the card")
    public ScreenResponse<Fields> turn(@RequestBody Request req, HttpSession session) {
        Key key = req.key() == null ? Key.ENTER : req.key().folded();
        ViewState st = session.getAttribute(STATE) instanceof ViewState s ? s : ViewState.MENU;
        if (key == Key.PF3) {
            session.removeAttribute(STATE);
            return ScreenResponse.navigate(st.fromList() ? "COCRDLIC" : "COMEN01C");
        }
        if (st.fromList()) {
            return read(st, CardForms.accountText(st.account()));
        }
        KeyEdit edit = CardForms.editKeys(req.accountId(), req.cardNum(), st.card(), false);
        ViewState next = new ViewState(false, edit.account(), edit.card());
        session.setAttribute(STATE, next);
        if (!edit.ok()) {
            return ScreenResponse.show(new Fields(edit.accountShown(), edit.cardShown(), "", "", "", "", CardForms.PROMPT,
                edit.errors(), false)).error(edit.message()).withFocus(focus(edit.errors()));
        }
        return read(next, edit.accountShown());
    }

    /** 9100-GETCARD-BYACCTCARD and the screen built from it. */
    private ScreenResponse<Fields> read(ViewState st, String accountShown) {
        String cardShown = Masking.card(st.card());
        Optional<CardRecord> card;
        try {
            card = lookup.find(st.account(), st.card());
        } catch (DataAccessException e) {
            return ScreenResponse.show(new Fields(accountShown, cardShown, "", "", "", "", CardForms.PROMPT,
                List.of(CardForms.ACCOUNT), st.fromList())).error(CardForms.FILE_ERROR).withFocus(CardForms.ACCOUNT);
        }
        if (card.isEmpty()) {
            return ScreenResponse.show(new Fields(accountShown, cardShown, "", "", "", "", CardForms.PROMPT,
                    List.of(CardForms.ACCOUNT, CardForms.CARD), st.fromList()))
                .error(CardForms.NOT_FOUND).withFocus(CardForms.ACCOUNT);
        }
        CardRecord c = card.get();
        String month = c.expiry() == null ? "" : String.format("%02d", c.expiry().getMonthValue());
        String year = c.expiry() == null ? "" : String.format("%04d", c.expiry().getYear());
        return ScreenResponse.show(new Fields(accountShown, cardShown, c.embossedName(), c.activeStatus(), month, year, FOUND,
            List.of(), st.fromList())).withFocus(CardForms.ACCOUNT);
    }

    /** BR-COCRDSLC-06: the account if it is in error or blank, else the card if it is, else the account. */
    private static String focus(List<String> errors) {
        return errors.contains(CardForms.ACCOUNT) || !errors.contains(CardForms.CARD) ? CardForms.ACCOUNT : CardForms.CARD;
    }
}
