package com.carddemo.inquiry.web;

import com.carddemo.account.api.AccountInquiry.AccountSnapshot;
import com.carddemo.common.security.CurrentUser;
import com.carddemo.inquiry.internal.AccountInquiryService;
import com.carddemo.inquiry.internal.AccountInquiryService.Found;
import com.carddemo.inquiry.internal.AccountInquiryService.Invalid;
import com.carddemo.inquiry.internal.AccountInquiryService.NotFound;
import com.carddemo.inquiry.internal.AccountInquiryService.ReadFailed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** REST equivalent of the COACCT01 queue service (Q-COACCT01-01, -05, -15): JSON with decimal amounts and ISO dates. */
@RestController
@Tag(name = "COACCT01", description = "Account inquiry")
public class AccountInquiryController {

    /** The account fields of the queue reply (BR-COACCT01-07); amounts are decimals with two places. */
    public record AccountReply(String accountId, String activeStatus, BigDecimal currentBalance, BigDecimal creditLimit,
                               BigDecimal cashCreditLimit, LocalDate openDate, LocalDate expirationDate, LocalDate reissueDate,
                               BigDecimal currentCycleCredit, BigDecimal currentCycleDebit, String groupId) {
    }

    /** @param message the queue reply text without its padding (verbatim COBOL text) */
    public record ErrorReply(String message) {
    }

    private final AccountInquiryService service;

    public AccountInquiryController(AccountInquiryService service) {
        this.service = service;
    }

    @GetMapping("/api/inquiry/account/{id}")
    @Operation(summary = "Account inquiry: 200 with the account, 404 when missing or out of scope (C-SCOPE), "
        + "400 for an invalid ID, 503 when the account store cannot be read")
    public ResponseEntity<?> account(@PathVariable String id) {
        AccountInquiryService.Outcome outcome;
        try {
            outcome = service.answer(CurrentUser.require(), id);
        } catch (ReadFailed e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorReply(AccountInquiryService.READ_ERROR));
        }
        return switch (outcome) {
            case Found f -> ResponseEntity.ok(reply(f.account()));
            case NotFound n -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorReply(AccountInquiryService.message(n)));
            case Invalid i -> ResponseEntity.badRequest().body(new ErrorReply(AccountInquiryService.message(i)));
        };
    }

    static AccountReply reply(AccountSnapshot a) {
        return new AccountReply(String.format("%011d", a.accountId()), a.activeStatus(), money(a.currentBalance()),
            money(a.creditLimit()), money(a.cashCreditLimit()), a.openDate(), a.expirationDate(), a.reissueDate(),
            money(a.currentCycleCredit()), money(a.currentCycleDebit()), a.groupId());
    }

    private static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.UNNECESSARY);
    }
}
