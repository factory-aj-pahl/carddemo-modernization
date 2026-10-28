package com.carddemo.authorization.web;

import com.carddemo.authorization.internal.PendingAuthDetails;
import com.carddemo.authorization.internal.PendingAuthDetails.Read;
import com.carddemo.authorization.web.AuthScreens.Selection;
import com.carddemo.common.mask.Masking;
import com.carddemo.common.msg.CommonMessages;
import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.ScreenResponse;
import com.carddemo.common.security.AccountScope;
import com.carddemo.common.security.CurrentUser;
import com.carddemo.migration.PendingAuthSegments.Detail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * COPAUS1C View Authorization Details (transaction CPVD): every field of the authorization selected on COPAUS0C;
 * PF8 moves to the next older one, PF5 marks or removes fraud (with COPAUS2C). The card number is masked.
 */
@RestController
@RequestMapping("/api/screens/COPAUS1C")
@Tag(name = "COPAUS1C", description = "View one pending authorization and mark or remove fraud")
public class PendingAuthDetailController {

    static final String LAST = "Already at the last Authorization...";
    static final String NOT_FOUND = "Authorization not found...";
    static final String SELECT_FIRST = "Please select an authorization...";
    static final String CARD_NUM = "cardNum";

    /** WS-DECLINE-REASON-TABLE (BR-COPAUS1C-03, Q-COPAUS1C-11). */
    static final Map<String, String> REASONS = Map.of("0000", "APPROVED", "3100", "INVALID CARD", "4100", "INSUFFICNT FUND",
        "4200", "CARD NOT ACTIVE", "4300", "ACCOUNT CLOSED", "4400", "EXCED DAILY LMT", "5100", "CARD FRAUD", "5200",
        "MERCHANT FRAUD", "5300", "LOST CARD", "9000", "UNKNOWN");

    /** The map fields; all empty when nothing is shown. {@code authResp} 'A' is shown green, 'D' red. */
    public record Fields(String cardNum, String authDate, String authTime, String authResp, String respReason, String authCode,
            String amount, String posEntryMode, String source, String mccCode, String cardExpiry, String authType,
            String transactionId, String matchStatus, String fraudStatus, String merchantName, String merchantId,
            String merchantCity, String merchantState, String merchantZip) {
        static final Fields EMPTY = new Fields("", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "");
    }

    public record Request(Key key) {
    }

    private final PendingAuthDetails details;
    private final ObjectProvider<AccountScope> scope;

    public PendingAuthDetailController(PendingAuthDetails details, ObjectProvider<AccountScope> scope) {
        this.details = details;
        this.scope = scope;
    }

    @GetMapping
    @Operation(summary = "Entry from COPAUS0C: the selected authorization (NAV-COPAUS1C-02); without any context, back to COPAUS0C")
    public ScreenResponse<Fields> show(HttpSession session) {
        Selection sel = AuthScreens.selection(session);
        if (sel == null && AuthScreens.account(session) == null) {
            return ScreenResponse.navigate("COPAUS0C");
        }
        return sel == null ? render(Fields.EMPTY, SELECT_FIRST) : enter(sel, null);
    }

    @PostMapping
    @Operation(summary = "Turn: ENTER redisplays, PF5 marks or removes fraud, PF8 shows the next older authorization, PF3 returns")
    public ScreenResponse<Fields> turn(@RequestBody Request req, HttpSession session) {
        Key key = req == null || req.key() == null ? Key.ENTER : req.key();
        if (key == Key.PF3) {
            return ScreenResponse.navigate("COPAUS0C");
        }
        Selection sel = AuthScreens.selection(session);
        if (sel == null) {
            // VR-COPAUS1C-01 / Q-COPAUS1C-08.
            return render(Fields.EMPTY, key == Key.ENTER || key == Key.PF5 || key == Key.PF8 ? SELECT_FIRST : CommonMessages.INVALID_KEY);
        }
        return switch (key) {
            case ENTER -> enter(sel, null);
            case PF5 -> fraud(sel);
            case PF8 -> next(sel, session);
            default -> enter(sel, CommonMessages.INVALID_KEY);
        };
    }

    private ScreenResponse<Fields> enter(Selection sel, String message) {
        if (!inScope(sel)) {
            return render(Fields.EMPTY, message != null ? message : NOT_FOUND);
        }
        Read r = details.current(sel.accountId(), sel.authId());
        String m = message != null ? message : r.notFound() ? NOT_FOUND : r.message();
        return render(r.detail() == null ? Fields.EMPTY : fields(r.detail()), m);
    }

    /** PROCESS-PF8-KEY; at the end the current authorization stays on screen (NAV-COPAUS1C-06). */
    private ScreenResponse<Fields> next(Selection sel, HttpSession session) {
        if (!inScope(sel)) {
            return render(Fields.EMPTY, NOT_FOUND);
        }
        Read r = details.withNext(sel.accountId(), sel.authId());
        if (r.detail() == null) {
            return render(Fields.EMPTY, r.notFound() ? NOT_FOUND : r.message());
        }
        if (r.next() == null) {
            return render(fields(r.detail()), LAST);
        }
        AuthScreens.select(session, new Selection(sel.accountId(), sel.customerId(), r.next().authId()));
        return render(fields(r.next()), null);
    }

    /** MARK-AUTH-FRAUD; afterwards the stored flag and date are shown, also after a failure (Q-COPAUS1C-01). */
    private ScreenResponse<Fields> fraud(Selection sel) {
        if (!inScope(sel)) {
            return render(Fields.EMPTY, NOT_FOUND);
        }
        Read t = details.toggleFraud(sel.accountId(), sel.customerId(), sel.authId());
        if (t.notFound()) {
            return render(Fields.EMPTY, NOT_FOUND);
        }
        Read stored = details.current(sel.accountId(), sel.authId());
        return render(stored.detail() == null ? Fields.EMPTY : fields(stored.detail()), t.message());
    }

    private boolean inScope(Selection sel) {
        CurrentUser user = CurrentUser.require();
        return user.isAdmin() || scope.getObject().canAccess(user, sel.accountId());
    }

    /** POPULATE-AUTH-DETAILS (BR-COPAUS1C-02 to -04). */
    static Fields fields(Detail d) {
        String reason = d.respReason() == null ? "" : d.respReason();
        String desc = REASONS.get(reason);
        String respReason = desc == null ? "9999-ERROR" : reason + "-" + AuthFormat.field(desc, 15);
        String fraud = d.authFraud();
        String fraudStatus = "F".equals(fraud) || "R".equals(fraud) ? fraud + "-" + reportDate(d.fraudRptDate()) : "-";
        return new Fields(Masking.card(d.cardNum()), AuthFormat.date(d.origDate()), AuthFormat.time(d.origTime()),
            "00".equals(d.respCode()) ? "A" : "D", respReason, AuthFormat.field(d.processingCode(), 6),
            AuthFormat.amount(d.approvedAmt(), 12), AuthFormat.field(d.posEntryMode(), 4), AuthFormat.field(d.messageSource(), 10),
            AuthFormat.field(d.merchantCategoryCode(), 4), AuthFormat.expiry(d.cardExpiryDate()), AuthFormat.field(d.authType(), 14),
            AuthFormat.field(d.transactionId(), 15), AuthFormat.field(d.matchStatus(), 1), fraudStatus.stripTrailing(),
            AuthFormat.field(d.merchantName(), 25), AuthFormat.field(d.merchantId(), 15), AuthFormat.field(d.merchantCity(), 25),
            AuthFormat.field(d.merchantState(), 2), AuthFormat.field(d.merchantZip(), 10));
    }

    /** Q-ENT-28 / Q-COPAUS1C-06: the one report date, shown MM/DD/YY. */
    private static String reportDate(LocalDate d) {
        return d == null ? "" : String.format("%02d/%02d/%02d", d.getMonthValue(), d.getDayOfMonth(), d.getYear() % 100);
    }

    private static ScreenResponse<Fields> render(Fields f, String message) {
        ScreenResponse<Fields> r = ScreenResponse.show(f).withFocus(CARD_NUM);
        return message == null ? r : r.error(message);
    }
}
