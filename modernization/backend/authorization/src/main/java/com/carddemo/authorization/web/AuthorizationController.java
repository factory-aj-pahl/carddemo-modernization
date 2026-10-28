package com.carddemo.authorization.web;

import com.carddemo.authorization.internal.AuthReply;
import com.carddemo.authorization.internal.AuthRequest;
import com.carddemo.authorization.internal.AuthorizationEngine;
import com.carddemo.authorization.internal.AuthorizationEngine.AuthorizationFailure;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * COPAUA0C over REST (D-MQ): the same decision and recording as the queue consumer, JSON in and out. The
 * amount is a JSON number or string read like the queue's amount text. Callers are systems acting for the
 * card network, so the endpoint is limited to ADMIN sessions.
 */
@RestController
@RequestMapping("/api/authorizations")
@Tag(name = "COPAUA0C", description = "Card authorization decision")
public class AuthorizationController {

    /** The 18 request fields of ENT-AUTH-REQUEST. */
    public record Request(String authDate, String authTime, String cardNum, String authType, String cardExpiryDate,
            String messageType, String messageSource, String processingCode, String transactionAmt, String merchantCategoryCode,
            String acqrCountryCode, String posEntryMode, String merchantId, String merchantName, String merchantCity,
            String merchantState, String merchantZip, String transactionId) {
    }

    /** ENT-AUTH-RESPONSE as JSON (decimal amount). */
    public record Response(String cardNum, String transactionId, String authIdCode, String respCode, String reasonCode,
            BigDecimal approvedAmount) {
    }

    /** A critical error: the CCPAUERY location and message; nothing was recorded. */
    public record Failure(String location, String message) {
    }

    private final AuthorizationEngine engine;

    public AuthorizationController(AuthorizationEngine engine) {
        this.engine = engine;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Decide one authorization request and record it as a pending authorization")
    public ResponseEntity<?> authorize(@RequestBody Request r) {
        AuthRequest rq = new AuthRequest(r.authDate(), r.authTime(), r.cardNum(), r.authType(), r.cardExpiryDate(), r.messageType(),
            r.messageSource(), r.processingCode(), r.transactionAmt(), r.merchantCategoryCode(), r.acqrCountryCode(),
            r.posEntryMode(), r.merchantId(), r.merchantName(), r.merchantCity(), r.merchantState(), r.merchantZip(),
            r.transactionId());
        try {
            AuthReply a = engine.authorize(rq);
            return ResponseEntity.ok(new Response(a.cardNum(), a.transactionId(), a.authIdCode(), a.respCode(), a.reasonCode(),
                a.approvedAmount()));
        } catch (AuthorizationFailure e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new Failure(e.entry().location(), e.entry().message()));
        }
    }
}
