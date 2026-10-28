package com.carddemo.billpay.web;

import com.carddemo.billpay.internal.BillPaymentService;
import com.carddemo.billpay.internal.BillPaymentService.BalanceChanged;
import com.carddemo.billpay.internal.BillPaymentService.NotFound;
import com.carddemo.billpay.internal.BillPaymentService.NothingToPay;
import com.carddemo.billpay.internal.BillPaymentService.Paid;
import com.carddemo.billpay.internal.BillPaymentService.PaymentFailed;
import com.carddemo.billpay.internal.BillPaymentService.TooLarge;
import com.carddemo.common.msg.CommonMessages;
import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.MessageColor;
import com.carddemo.common.screen.ScreenResponse;
import com.carddemo.common.format.Pic;
import com.carddemo.common.security.CurrentUser;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** COBIL00C bill payment screen (transaction CB00). */
@RestController
@RequestMapping("/api/screens/COBIL00C")
@Tag(name = "COBIL00C", description = "Bill payment")
public class BillPaymentController {

    static final String EMPTY_ID = "Acct ID can NOT be empty...";
    static final String INVALID_CONFIRM = "Invalid value. Valid values are (Y/N)...";
    static final String NOTHING_TO_PAY = "You have nothing to pay...";
    static final String CONFIRM_PROMPT = "Confirm to make a bill payment...";
    /** Q-COBIL00C-07, NEW text. */
    static final String TOO_LARGE = "Balance too large for online payment...";

    /**
     * @param accountId      ACTIDIN, 11 characters
     * @param currentBalance CURBAL as shown ({@code +9999999999.99}), empty when none is shown
     * @param confirm        CONFIRM, one character
     * @param receipt        D-RECEIPT panel after a successful payment, otherwise absent
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Fields(String accountId, String currentBalance, String confirm, Receipt receipt) {
        static final Fields EMPTY = new Fields("", "", "", null);
    }

    /** D-RECEIPT: amounts as plain decimals with two places. */
    public record Receipt(String tranId, String accountId, String amountPaid, String newBalance) {
    }

    /**
     * @param shownBalance the CURBAL value the screen showed when the user pressed the key; the
     *                     confirming turn is checked against it (Q-COBIL00C-08)
     */
    public record Request(Key key, String accountId, String confirm, String shownBalance) {
    }

    private final BillPaymentService payments;

    public BillPaymentController(BillPaymentService payments) {
        this.payments = payments;
    }

    @GetMapping
    @Operation(summary = "First entry: blank form, focus on account ID (NAV-COBIL00C-02)")
    public ScreenResponse<Fields> show() {
        return ScreenResponse.show(Fields.EMPTY).withFocus("accountId");
    }

    @PostMapping
    @Operation(summary = "Bill payment turn: ENTER shows or pays, PF3 back, PF4 clears, other keys are invalid")
    public ScreenResponse<Fields> turn(@RequestBody Request req) {
        Key key = req.key() == null ? Key.ENTER : req.key();
        String accountId = cut(req.accountId(), 11);
        String confirm = cut(req.confirm(), 1);
        String shown = req.shownBalance() == null ? "" : req.shownBalance();
        Fields received = new Fields(accountId, shown, confirm, null);
        return switch (key) {
            case PF3 -> ScreenResponse.navigate("COMEN01C");
            case PF4 -> ScreenResponse.show(Fields.EMPTY).withFocus("accountId");
            case ENTER -> enter(received);
            default -> ScreenResponse.show(received).error(CommonMessages.INVALID_KEY).withFocus("accountId");
        };
    }

    private ScreenResponse<Fields> enter(Fields in) {
        if (in.accountId().isBlank()) {
            return ScreenResponse.show(in).error(EMPTY_ID).withFocus("accountId");
        }
        String c = in.confirm();
        boolean pay = c.equals("Y") || c.equals("y");
        if (c.equals("N") || c.equals("n")) {
            return ScreenResponse.show(Fields.EMPTY).withFocus("accountId");
        }
        if (!pay && !c.isBlank()) {
            return ScreenResponse.show(in).error(INVALID_CONFIRM).withFocus("confirm");
        }
        Optional<Long> id = parseAccountId(in.accountId());
        if (id.isEmpty()) {
            return ScreenResponse.show(in).error(BillPaymentService.NOT_FOUND).withFocus("accountId");
        }
        CurrentUser user = CurrentUser.require();
        try {
            return pay ? confirmPayment(in, user, id.get()) : display(in, user, id.get());
        } catch (PaymentFailed e) {
            return ScreenResponse.show(in).error(e.getMessage()).withFocus("accountId");
        }
    }

    private ScreenResponse<Fields> display(Fields in, CurrentUser user, long accountId) {
        Optional<BigDecimal> balance = payments.balance(user, accountId);
        if (balance.isEmpty()) {
            return ScreenResponse.show(in).error(BillPaymentService.NOT_FOUND).withFocus("accountId");
        }
        Fields shown = new Fields(in.accountId(), curbal(balance.get()), in.confirm(), null);
        if (balance.get().signum() <= 0) {
            return ScreenResponse.show(shown).error(NOTHING_TO_PAY).withFocus("accountId");
        }
        return ScreenResponse.show(shown).error(CONFIRM_PROMPT).withFocus("confirm");
    }

    private ScreenResponse<Fields> confirmPayment(Fields in, CurrentUser user, long accountId) {
        boolean shown = !in.currentBalance().isBlank();
        BillPaymentService.Outcome outcome = payments.pay(user, accountId, shown, shown ? parseShown(in.currentBalance()) : null);
        return switch (outcome) {
            case NotFound n -> ScreenResponse.show(in).error(BillPaymentService.NOT_FOUND).withFocus("accountId");
            case NothingToPay n -> ScreenResponse.show(new Fields(in.accountId(), curbal(n.balance()), in.confirm(), null))
                .error(NOTHING_TO_PAY).withFocus("accountId");
            case BalanceChanged b -> ScreenResponse.show(new Fields(in.accountId(), curbal(b.balance()), "", null))
                .error(CONFIRM_PROMPT).withFocus("confirm");
            case TooLarge t -> ScreenResponse.show(new Fields(in.accountId(), curbal(t.balance()), in.confirm(), null))
                .error(TOO_LARGE).withFocus("accountId");
            case Paid p -> ScreenResponse.show(new Fields("", "", "",
                    new Receipt(p.tranId(), Pic.accountId(p.accountId()), plain(p.amount()), plain(p.newBalance()))))
                .withMessage("Payment successful.  Your Transaction ID is " + p.tranId() + ".", MessageColor.GREEN)
                .withFocus("accountId");
        };
    }

    /** C-KEYLEN: exactly 11 digits as typed; anything else cannot match an ACCT-ID key. */
    static Optional<Long> parseAccountId(String s) {
        if (s.length() != 11 || !s.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(s));
    }

    /** The shown CURBAL text as a number, or null when it cannot be read. */
    static BigDecimal parseShown(String s) {
        try {
            return new BigDecimal(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String curbal(BigDecimal balance) {
        return Pic.signedZeros(balance, 10);
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String cut(String s, int len) {
        if (s == null) {
            return "";
        }
        return s.length() > len ? s.substring(0, len) : s;
    }
}
