package com.carddemo.account.web;

import com.carddemo.account.internal.AccountRecord;
import com.carddemo.account.internal.CustomerRecord;
import com.carddemo.common.mask.Masking;
import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.ScreenResponse;
import com.carddemo.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** COACTVWC view one account and its customer (transaction CAVW). Read-only (BR-COACTVWC-04). */
@RestController
@RequestMapping("/api/screens/COACTVWC")
@Tag(name = "COACTVWC", description = "View account")
public class AccountViewController {

    static final String PROMPT = "Enter or update id of account to display";
    /** Q-COACTVWC-04. */
    static final String DISPLAYING = "Displaying details of given Account";
    static final String NO_INPUT = "No input received";
    static final String BAD_ACCOUNT = "Account Filter must  be a non-zero 11 digit number";

    /**
     * CACTVWA output fields. {@code ssn} and {@code govtId} are masked (Q-COACTVWC-09); {@code info} is
     * the info line (INFOMSG); {@code accountInError} marks the account number in error style.
     */
    public record Fields(String accountId, String active, String opened, String expiry, String reissue,
                         String creditLimit, String cashCreditLimit, String currentBalance, String cycleCredit,
                         String cycleDebit, String group, String customerId, String ssn, String dob, String fico,
                         String firstName, String middleName, String lastName, String addr1, String addr2, String city,
                         String state, String zip, String country, String phone1, String phone2, String govtId,
                         String eft, String primary, String info, boolean accountInError) {

        static Fields search(String accountId, String info, boolean accountInError) {
            return new Fields(accountId, "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
                "", "", "", "", "", "", "", "", info, accountInError);
        }

        /** BR-COACTVWC-02 and -03. */
        static Fields of(String accountId, AccountRecord a, CustomerRecord c, String info) {
            return new Fields(accountId, a.activeStatus(), date(a.openDate()), date(a.expirationDate()),
                date(a.reissueDate()), Amounts.edited(a.creditLimit()), Amounts.edited(a.cashCreditLimit()),
                Amounts.edited(a.currentBalance()), Amounts.edited(a.cycleCredit()), Amounts.edited(a.cycleDebit()),
                a.groupId(),
                c == null ? "" : String.format("%09d", c.customerId()),
                c == null ? "" : Masking.ssnScreen(c.ssn()),
                c == null ? "" : date(c.dob()),
                c == null ? "" : String.format("%03d", c.ficoScore()),
                c == null ? "" : c.firstName(), c == null ? "" : c.middleName(), c == null ? "" : c.lastName(),
                c == null ? "" : c.addrLine1(), c == null ? "" : c.addrLine2(), c == null ? "" : c.city(),
                c == null ? "" : c.stateCd(), c == null ? "" : first(c.zip(), 5), c == null ? "" : c.countryCd(),
                c == null ? "" : first(c.phone1(), 13), c == null ? "" : first(c.phone2(), 13),
                c == null ? "" : Masking.keepLast(c.govtIssuedId(), 4), c == null ? "" : c.eftAccountId(),
                c == null ? "" : c.priCardHolder(), info, false);
        }
    }

    public record Request(Key key, String accountId) {
    }

    private final AccountLookup lookup;

    public AccountViewController(AccountLookup lookup) {
        this.lookup = lookup;
    }

    @GetMapping
    @Operation(summary = "First entry: empty search screen with the cursor on the account number (NAV-COACTVWC-03)")
    public ScreenResponse<Fields> show() {
        return ScreenResponse.show(Fields.search("", PROMPT, false)).withFocus("accountId");
    }

    @PostMapping
    @Operation(summary = "View turn: PF3 exits; every other key looks the account up (NAV-COACTVWC-01)")
    public ScreenResponse<Fields> turn(@RequestBody Request req) {
        Key key = req.key() == null ? Key.ENTER : req.key().folded();
        if (key == Key.PF3) {
            return ScreenResponse.navigate("COMEN01C");
        }
        String in = req.accountId() == null ? "" : req.accountId();
        if (in.length() > 11) {
            in = in.substring(0, 11);
        }
        if (in.isBlank() || in.strip().equals("*")) {
            return ScreenResponse.show(Fields.search("*", PROMPT, true)).error(NO_INPUT).withFocus("accountId");
        }
        if (!in.matches("[0-9]{11}") || Long.parseLong(in) == 0) {
            return ScreenResponse.show(Fields.search(in, PROMPT, true)).error(BAD_ACCOUNT).withFocus("accountId");
        }
        AccountLookup.Result r = lookup.find(CurrentUser.require(), Long.parseLong(in));
        if (r.complete()) {
            return ScreenResponse.show(Fields.of(in, r.account(), r.customer(), DISPLAYING)).withFocus("accountId");
        }
        // Q-COACTVWC-01: an account master failure stops the display; a customer failure still shows the account.
        Fields f = r.account() == null ? Fields.search(in, PROMPT, true) : Fields.of(in, r.account(), null, PROMPT);
        return ScreenResponse.show(f).error(r.message()).withFocus("accountId");
    }

    private static String date(LocalDate d) {
        return d == null ? "" : d.toString();
    }

    private static String first(String s, int n) {
        return s == null ? "" : s.length() > n ? s.substring(0, n) : s;
    }
}
