package com.carddemo.transaction.web;

import com.carddemo.card.api.CardDirectory;
import com.carddemo.card.api.CardXref;
import com.carddemo.common.date.CalendarDates;
import com.carddemo.common.format.Pic;
import com.carddemo.common.mask.Masking;
import com.carddemo.common.msg.CommonMessages;
import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.MessageColor;
import com.carddemo.common.screen.ScreenResponse;
import com.carddemo.common.security.AccountScope;
import com.carddemo.common.security.CurrentUser;
import com.carddemo.refdata.api.TransactionCodes;
import com.carddemo.transaction.internal.TransactionAdder;
import com.carddemo.transaction.internal.TransactionAdder.AddFailed;
import com.carddemo.transaction.internal.TransactionAdder.Entry;
import com.carddemo.transaction.internal.TransactionReader;
import com.carddemo.transaction.internal.TransactionReader.TransactionRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** COTRN02C add a transaction (transaction CT02). */
@RestController
@RequestMapping("/api/screens/COTRN02C")
@Tag(name = "COTRN02C", description = "Add transaction")
public class TransactionAddController {

    static final String ACCT_NOT_NUMERIC = "Account ID must be Numeric...";
    static final String ACCT_NOT_FOUND = "Account ID NOT found...";
    static final String CARD_NOT_NUMERIC = "Card Number must be Numeric...";
    static final String CARD_NOT_FOUND = "Card Number NOT found...";
    static final String KEY_MISSING = "Account or Card Number must be entered...";
    /** Q-COTRN02C-06, NEW text. */
    static final String CARD_MISMATCH = "Card Number does not match the Account ID...";
    static final String AIX_FAILED = "Unable to lookup Acct in XREF AIX file...";
    static final String XREF_FAILED = "Unable to lookup Card # in XREF file...";
    /** Q-COTRN02C-09, NEW texts. */
    static final String TYPE_NOT_FOUND = "Type CD NOT found...";
    static final String CATEGORY_NOT_FOUND = "Category CD NOT found...";
    static final String CONFIRM_PROMPT = "Confirm to add this transaction...";
    static final String INVALID_CONFIRM = "Invalid value. Valid values are (Y/N)...";
    static final String HELD_ATTR = "carddemo.transaction.add.card";

    private static final Pattern AMOUNT = Pattern.compile("[+-][0-9]{8}\\.[0-9]{2}");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    /** COTRN2A input fields; {@code cardNum} is shown masked (C-MASK). */
    public record Fields(String accountId, String cardNum, String typeCd, String catCd, String source, String description,
                         String amount, String origDate, String procDate, String merchantId, String merchantName,
                         String merchantCity, String merchantZip, String confirm) {

        static final Fields EMPTY = new Fields("", "", "", "", "", "", "", "", "", "", "", "", "", "");

        Fields withKeys(String account, String card) {
            return new Fields(account, card, typeCd, catCd, source, description, amount, origDate, procDate, merchantId,
                merchantName, merchantCity, merchantZip, confirm);
        }

        Fields withAmount(String a) {
            return new Fields(accountId, cardNum, typeCd, catCd, source, description, a, origDate, procDate, merchantId,
                merchantName, merchantCity, merchantZip, confirm);
        }
    }

    public record Request(Key key, String accountId, String cardNum, String typeCd, String catCd, String source,
                          String description, String amount, String origDate, String procDate, String merchantId,
                          String merchantName, String merchantCity, String merchantZip, String confirm) {

        Fields fields() {
            return new Fields(Input.cut(accountId, 11), Input.cut(cardNum, 16), Input.cut(typeCd, 2), Input.cut(catCd, 4),
                Input.cut(source, 10), Input.cut(description, 60), Input.cut(amount, 12), Input.cut(origDate, 10),
                Input.cut(procDate, 10), Input.cut(merchantId, 9), Input.cut(merchantName, 30), Input.cut(merchantCity, 25),
                Input.cut(merchantZip, 10), Input.cut(confirm, 1));
        }
    }

    /**
     * Q-COTRN02C-15: the full card behind the masked card field. {@code derived} cards came from the
     * account (DF-6) and are derived again when the account changes; typed cards are kept as typed.
     */
    record HeldCard(String cardNum, boolean derived) implements Serializable {
    }

    /** First failing rule: the message, the field that gets the cursor, and the form as it is sent back. */
    private static final class Failed extends RuntimeException {
        final transient ScreenResponse<Fields> response;

        Failed(Fields form, String message, String focus) {
            super(message, null, false, false);
            this.response = ScreenResponse.show(form).error(message).withFocus(focus);
        }
    }

    private final CardDirectory cards;
    private final AccountScope scope;
    private final TransactionCodes codes;
    private final TransactionReader reader;
    private final TransactionAdder adder;

    public TransactionAddController(CardDirectory cards, AccountScope scope, TransactionCodes codes,
                                    TransactionReader reader, TransactionAdder adder) {
        this.cards = cards;
        this.scope = scope;
        this.codes = codes;
        this.reader = reader;
        this.adder = adder;
    }

    @GetMapping
    @Operation(summary = "First entry: empty form with the cursor on Acct # (NAV-COTRN02C-02)")
    public ScreenResponse<Fields> show(HttpSession session) {
        session.removeAttribute(HELD_ATTR);
        return ScreenResponse.show(Fields.EMPTY).withFocus("accountId");
    }

    @PostMapping
    @Operation(summary = "Add turn: ENTER validates and adds on Y, PF3 back, PF4 clears, PF5 copies the last transaction")
    public ScreenResponse<Fields> turn(@RequestBody Request req, HttpSession session) {
        Key key = req.key() == null ? Key.ENTER : req.key();
        Fields in = req.fields();
        HeldCard held = session.getAttribute(HELD_ATTR) instanceof HeldCard h ? h : null;
        switch (key) {
            case PF3:
                return ScreenResponse.navigate("COMEN01C");
            case PF4:
                session.removeAttribute(HELD_ATTR);
                return ScreenResponse.show(Fields.EMPTY).withFocus("accountId");
            case ENTER:
            case PF5:
                break;
            default:
                return ScreenResponse.show(in.withKeys(in.accountId(), Input.cardShown(in.cardNum())))
                    .error(CommonMessages.INVALID_KEY).withFocus("accountId");
        }
        CurrentUser user = CurrentUser.require();
        try {
            Keys k = keys(user, in, held);
            session.setAttribute(HELD_ATTR, k.held());
            Fields form = in.withKeys(k.accountId(), Masking.card(k.held().cardNum()));
            if (key == Key.PF5) {
                return copyLast(user, form);
            }
            Entry entry = validate(form, k.held().cardNum());
            form = form.withAmount(Pic.signedZeros(entry.amount(), 8));
            String c = form.confirm();
            if (c.equals("Y") || c.equals("y")) {
                String tranId;
                try {
                    tranId = adder.add(entry);
                } catch (AddFailed e) {
                    return ScreenResponse.show(form).error(e.getMessage()).withFocus("accountId");
                }
                session.removeAttribute(HELD_ATTR);
                return ScreenResponse.show(Fields.EMPTY)
                    .withMessage("Transaction added successfully.  Your Tran ID is " + tranId + ".", MessageColor.GREEN)
                    .withFocus("accountId");
            }
            if (c.isBlank() || c.equals("N") || c.equals("n")) {
                return ScreenResponse.show(form).error(CONFIRM_PROMPT).withFocus("confirm");
            }
            return ScreenResponse.show(form).error(INVALID_CONFIRM).withFocus("confirm");
        } catch (Failed f) {
            return f.response;
        }
    }

    /** Q-COTRN02C-04: copy the highest in-scope transaction into the form and validate it; never writes. */
    private ScreenResponse<Fields> copyLast(CurrentUser user, Fields form) {
        Optional<TransactionRow> last;
        try {
            last = reader.highest(reader.scopeOf(user));
        } catch (DataAccessException e) {
            throw new Failed(form, TransactionAdder.LOOKUP_FAILED, "accountId");
        }
        if (last.isEmpty()) {
            throw new Failed(form, TransactionViewController.NOT_FOUND, "accountId");
        }
        TransactionRow t = last.get();
        Fields copy = new Fields(form.accountId(), form.cardNum(), t.typeCd(), String.format("%04d", t.catCd()),
            Input.part(t.source(), 10), Input.part(t.description(), 60), Pic.signedZeros(t.amount(), 8),
            Input.part(t.origTsText(), 10).strip(), Input.part(t.procTsText(), 10).strip(), String.format("%09d", t.merchantId()),
            Input.part(t.merchantName(), 30), Input.part(t.merchantCity(), 25), Input.part(t.merchantZip(), 10), "");
        validate(copy, null);
        return ScreenResponse.show(copy).error(CONFIRM_PROMPT).withFocus("confirm");
    }

    private record Keys(String accountId, HeldCard held) {
    }

    /** VALIDATE-INPUT-KEY-FIELDS with the Q-COTRN02C-06/-07/-14/-15 fixes. */
    private Keys keys(CurrentUser user, Fields in, HeldCard held) {
        String acctIn = in.accountId();
        String cardIn = in.cardNum();
        Fields echo = in.withKeys(acctIn, Input.cardShown(cardIn));
        HeldCard card = null;
        if (!cardIn.isBlank()) {
            card = held != null && cardIn.equals(Masking.card(held.cardNum())) ? held : new HeldCard(cardIn, false);
        }
        if (!acctIn.isBlank()) {
            if (!Input.digits(acctIn, 11)) {
                throw new Failed(echo, ACCT_NOT_NUMERIC, "accountId");
            }
            long account = Long.parseLong(acctIn);
            if (!scope.canAccess(user, account)) {
                throw new Failed(echo, ACCT_NOT_FOUND, "accountId");
            }
            if (card == null || card.derived()) {
                Optional<String> lowest;
                try {
                    lowest = cards.lowestCardForAccount(account);
                } catch (DataAccessException e) {
                    throw new Failed(echo, AIX_FAILED, "accountId");
                }
                if (lowest.isEmpty()) {
                    throw new Failed(echo, ACCT_NOT_FOUND, "accountId");
                }
                return new Keys(acctIn, new HeldCard(lowest.get(), true));
            }
            Optional<CardXref> xref = xref(card.cardNum(), echo);
            if (xref.isEmpty() || xref.get().accountId() != account) {
                throw new Failed(echo, CARD_MISMATCH, "cardNum");
            }
            return new Keys(acctIn, card);
        }
        if (card != null) {
            if (!Input.digits(card.cardNum(), 16)) {
                throw new Failed(echo, CARD_NOT_NUMERIC, "cardNum");
            }
            Optional<CardXref> xref = xref(card.cardNum(), echo);
            if (xref.isEmpty() || !scope.canAccess(user, xref.get().accountId())) {
                throw new Failed(echo, CARD_NOT_FOUND, "cardNum");
            }
            return new Keys(Pic.accountId(xref.get().accountId()), new HeldCard(card.cardNum(), false));
        }
        throw new Failed(echo, KEY_MISSING, "accountId");
    }

    private Optional<CardXref> xref(String cardNum, Fields echo) {
        if (!Input.digits(cardNum, 16)) {
            return Optional.empty();
        }
        try {
            return cards.findXref(cardNum);
        } catch (DataAccessException e) {
            throw new Failed(echo, XREF_FAILED, "cardNum");
        }
    }

    /** VALIDATE-INPUT-DATA-FIELDS in COBOL order (Q-COTRN02C-11), with the reference-data checks of Q-COTRN02C-09. */
    private Entry validate(Fields f, String cardNum) {
        required(f, f.typeCd(), "Type CD can NOT be empty...", "typeCd");
        required(f, f.catCd(), "Category CD can NOT be empty...", "catCd");
        required(f, f.source(), "Source can NOT be empty...", "source");
        required(f, f.description(), "Description can NOT be empty...", "description");
        required(f, f.amount(), "Amount can NOT be empty...", "amount");
        required(f, f.origDate(), "Orig Date can NOT be empty...", "origDate");
        required(f, f.procDate(), "Proc Date can NOT be empty...", "procDate");
        required(f, f.merchantId(), "Merchant ID can NOT be empty...", "merchantId");
        required(f, f.merchantName(), "Merchant Name can NOT be empty...", "merchantName");
        required(f, f.merchantCity(), "Merchant City can NOT be empty...", "merchantCity");
        required(f, f.merchantZip(), "Merchant Zip can NOT be empty...", "merchantZip");
        if (!Input.digits(f.typeCd(), 2)) {
            throw new Failed(f, "Type CD must be Numeric...", "typeCd");
        }
        if (!Input.digits(f.catCd(), 4)) {
            throw new Failed(f, "Category CD must be Numeric...", "catCd");
        }
        int cat = Integer.parseInt(f.catCd());
        if (!codes.typeExists(f.typeCd())) {
            throw new Failed(f, TYPE_NOT_FOUND, "typeCd");
        }
        if (!codes.categoryExists(f.typeCd(), cat)) {
            throw new Failed(f, CATEGORY_NOT_FOUND, "catCd");
        }
        if (!AMOUNT.matcher(f.amount()).matches()) {
            throw new Failed(f, "Amount should be in format -99999999.99", "amount");
        }
        if (!DATE.matcher(f.origDate()).matches()) {
            throw new Failed(f, "Orig Date should be in format YYYY-MM-DD", "origDate");
        }
        if (!DATE.matcher(f.procDate()).matches()) {
            throw new Failed(f, "Proc Date should be in format YYYY-MM-DD", "procDate");
        }
        BigDecimal amount = new BigDecimal(f.amount());
        Fields normalized = f.withAmount(Pic.signedZeros(amount, 8));
        // DF-8 / Q-COTRN02C-12: dates before 1582-10-15 are not valid dates.
        if (!CalendarDates.isValid(f.origDate(), CalendarDates.ISO)) {
            throw new Failed(normalized, "Orig Date - Not a valid date...", "origDate");
        }
        if (!CalendarDates.isValid(f.procDate(), CalendarDates.ISO)) {
            throw new Failed(normalized, "Proc Date - Not a valid date...", "procDate");
        }
        if (!Input.digits(f.merchantId(), 9)) {
            throw new Failed(normalized, "Merchant ID must be Numeric...", "merchantId");
        }
        return new Entry(cardNum, f.typeCd(), cat, f.source().stripTrailing(), f.description().stripTrailing(), amount,
            Long.parseLong(f.merchantId()), f.merchantName().stripTrailing(), f.merchantCity().stripTrailing(),
            f.merchantZip().stripTrailing(), LocalDate.parse(f.origDate()), LocalDate.parse(f.procDate()));
    }

    private static void required(Fields f, String value, String message, String focus) {
        if (value.isBlank()) {
            throw new Failed(f, message, focus);
        }
    }
}
