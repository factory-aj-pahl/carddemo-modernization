package com.carddemo.transaction.web;

import com.carddemo.common.format.Pic;
import com.carddemo.common.mask.Masking;
import com.carddemo.common.msg.CommonMessages;
import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.ScreenResponse;
import com.carddemo.common.security.CurrentUser;
import com.carddemo.transaction.internal.TransactionReader;
import com.carddemo.transaction.internal.TransactionReader.TransactionRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** COTRN01C view one transaction (transaction CT01). */
@RestController
@RequestMapping("/api/screens/COTRN01C")
@Tag(name = "COTRN01C", description = "View transaction")
public class TransactionViewController {

    static final String EMPTY = "Tran ID can NOT be empty...";
    public static final String NOT_FOUND = "Transaction ID NOT found...";
    static final String LOOKUP_FAILED = "Unable to lookup Transaction...";
    static final String FROM_ATTR = "carddemo.transaction.view.from";
    static final String FOCUS = "tranIdIn";

    /**
     * Screen fields of COTRN1A (BR-COTRN01C-02). The card number is masked (C-MASK, Q-COTRN01C-05).
     */
    public record Fields(String tranIdIn, String tranId, String cardNum, String typeCd, String catCd, String source,
                         String description, String amount, String origDate, String procDate, String merchantId,
                         String merchantName, String merchantCity, String merchantZip) {

        static Fields blank(String tranIdIn) {
            return new Fields(tranIdIn, "", "", "", "", "", "", "", "", "", "", "", "", "");
        }
    }

    public record Request(Key key, String tranIdIn) {
    }

    private final TransactionReader reader;

    public TransactionViewController(TransactionReader reader) {
        this.reader = reader;
    }

    @GetMapping
    @Operation(summary = "First entry: empty form, or the transaction selected in COTRN00C (BR-COTRN01C-03)")
    public ScreenResponse<Fields> show(HttpSession session) {
        Selection selected = session.getAttribute(Selection.ATTR) instanceof Selection s ? s : null;
        session.setAttribute(FROM_ATTR, selected != null ? "COTRN00C" : "COMEN01C");
        if (selected == null) {
            return ScreenResponse.show(Fields.blank("")).withFocus(FOCUS);
        }
        return lookup(selected.tranId());
    }

    @PostMapping
    @Operation(summary = "View turn: ENTER fetches, PF3 back, PF4 clears, PF5 opens the list")
    public ScreenResponse<Fields> turn(@RequestBody Request req, HttpSession session) {
        session.removeAttribute(Selection.ATTR);
        Key key = req.key() == null ? Key.ENTER : req.key();
        String in = Input.cut(req.tranIdIn(), 16);
        return switch (key) {
            case PF3 -> ScreenResponse.navigate(session.getAttribute(FROM_ATTR) instanceof String from ? from : "COMEN01C");
            case PF4 -> ScreenResponse.show(Fields.blank("")).withFocus(FOCUS);
            case PF5 -> ScreenResponse.navigate("COTRN00C");
            case ENTER -> lookup(in);
            default -> ScreenResponse.show(Fields.blank(in)).error(CommonMessages.INVALID_KEY).withFocus(FOCUS);
        };
    }

    private ScreenResponse<Fields> lookup(String in) {
        if (in.isBlank()) {
            return ScreenResponse.show(Fields.blank(in)).error(EMPTY).withFocus(FOCUS);
        }
        CurrentUser user = CurrentUser.require();
        Optional<TransactionRow> found;
        try {
            found = reader.find(in).filter(t -> reader.cardInScope(user, t.cardNum()));
        } catch (DataAccessException e) {
            return ScreenResponse.show(Fields.blank(in)).error(LOOKUP_FAILED).withFocus(FOCUS);
        }
        if (found.isEmpty()) {
            return ScreenResponse.show(Fields.blank(in)).error(NOT_FOUND).withFocus(FOCUS);
        }
        TransactionRow t = found.get();
        return ScreenResponse.show(new Fields(in, t.tranId(), Masking.card(t.cardNum()), t.typeCd(),
            String.format("%04d", t.catCd()), t.source(), Input.part(t.description(), 60), Pic.signedZeros(t.amount(), 8),
            Input.part(t.origTsText(), 10).strip(), Input.part(t.procTsText(), 10).strip(),
            String.format("%09d", t.merchantId()), Input.part(t.merchantName(), 30), Input.part(t.merchantCity(), 25),
            t.merchantZip())).withFocus(FOCUS);
    }
}
