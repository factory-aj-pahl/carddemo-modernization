package com.carddemo.inquiry.internal;

import com.carddemo.account.api.AccountInquiry;
import com.carddemo.account.api.AccountInquiry.AccountSnapshot;
import com.carddemo.common.codec.Overpunch;
import com.carddemo.common.security.AccountScope;
import com.carddemo.common.security.CurrentUser;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * COACCT01 (CDRA): account inquiry by account ID. The queue request is positional (bytes 1-4 function, bytes
 * 5-15 account ID); the answer is the legacy 1000-byte text (Q-COACCT01-05). The REST view returns the same
 * outcome to a signed-on user within C-SCOPE.
 */
@Service
public class AccountInquiryService {

    public static final String FUNCTION = "INQA";
    static final int REPLY_LENGTH = 1000;
    /** Q-COACCT01-08: the COBOL buffer size; a longer request is answered as invalid. */
    static final int MAX_REQUEST = 1000;
    static final int MIN_REQUEST = 15;
    public static final String INVALID = "INVALID REQUEST PARAMETERS ";
    /** ERR-COACCT01-06: the 28-character literal truncated to MQ-APPL-RETURN-MESSAGE X(25). */
    public static final String READ_ERROR = "ERROR WHILE READING ACCTF";

    /** Outcome of one inquiry. */
    public sealed interface Outcome {
    }

    public record Found(AccountSnapshot account) implements Outcome {
    }

    /** BR-COACCT01-08: valid request, no such account (or, on REST, outside the user's scope). */
    public record NotFound(String key) implements Outcome {
    }

    /** BR-COACCT01-09: wrong function, key not a positive number, or a request of the wrong size. */
    public record Invalid(String key, String function) implements Outcome {
    }

    /** ERR-COACCT01-06: the account store could not be read; {@link #storageCode()} is the database error code. */
    public static class ReadFailed extends RuntimeException {
        private final String storageCode;

        ReadFailed(DataAccessException cause) {
            super(cause.getMessage(), cause);
            this.storageCode = storageCode(cause);
        }

        public String storageCode() {
            return storageCode;
        }

        private static String storageCode(Throwable t) {
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (c instanceof SQLException sql) {
                    return sql.getErrorCode() != 0 ? Integer.toString(sql.getErrorCode())
                        : sql.getSQLState() == null ? "" : sql.getSQLState();
                }
            }
            return t.getClass().getSimpleName();
        }
    }

    private final AccountInquiry accounts;
    private final AccountScope scope;

    public AccountInquiryService(AccountInquiry accounts, AccountScope scope) {
        this.accounts = accounts;
        this.scope = scope;
    }

    /** The queue request as received (Q-COACCT01-04: only the received bytes; VR-COACCT01-01, -03). */
    public Outcome answer(byte[] request) {
        String function = text(request, 0, 4);
        String key = text(request, 4, 15);
        if (request.length < MIN_REQUEST || request.length > MAX_REQUEST || !function.equals(FUNCTION) || !positive(key)) {
            return new Invalid(key, function);
        }
        return lookup(Long.parseLong(key), key);
    }

    /**
     * REST: {@code id} must be the 11-digit key, as on the queue (Q-COACCT01-01, -04, -06; C-KEYLEN): a shorter or
     * non-numeric ID is never left-padded and gets the invalid-request answer. The key is shown as the queue
     * shows bytes 5-15 of {@code INQA}+id: a short ID space-padded, a longer one cut to its first 11 characters
     * (it is still rejected). An account outside the user's scope is answered exactly like a missing one
     * (C-SCOPE).
     */
    public Outcome answer(CurrentUser user, String id) {
        String key = id.length() < 11 ? id + " ".repeat(11 - id.length()) : id.substring(0, 11);
        if (id.length() != 11 || !positive(key)) {
            return new Invalid(key, FUNCTION);
        }
        long accountId = Long.parseLong(key);
        if (!scope.canAccess(user, accountId)) {
            return new NotFound(key);
        }
        return lookup(accountId, key);
    }

    private Outcome lookup(long accountId, String key) {
        Optional<AccountSnapshot> found;
        try {
            found = accounts.find(accountId);
        } catch (DataAccessException e) {
            throw new ReadFailed(e);
        }
        return found.<Outcome>map(Found::new).orElseGet(() -> new NotFound(key));
    }

    /** The 1000-byte queue reply (BR-COACCT01-07, -08, -09). */
    public static String legacyReply(Outcome outcome) {
        String text = switch (outcome) {
            case Found f -> accountResponse(f.account());
            case NotFound n -> INVALID + "ACCT ID : " + n.key();
            case Invalid i -> INVALID + "ACCT ID : " + i.key() + "FUNCTION : " + i.function();
        };
        return text + " ".repeat(REPLY_LENGTH - text.length());
    }

    /** One line of text for REST error bodies: the queue reply without its padding. */
    public static String message(Outcome outcome) {
        return legacyReply(outcome).stripTrailing();
    }

    /** WS-ACCT-RESPONSE: 252 bytes of labelled fields, amounts as signed zoned S9(10)V99 (Q-COACCT01-05). */
    static String accountResponse(AccountSnapshot a) {
        return "ACCOUNT ID : " + String.format("%011d", a.accountId())
            + "ACCOUNT STATUS : " + x(a.activeStatus(), 1)
            + "BALANCE : " + zoned(a.currentBalance())
            + "CREDIT LIMIT : " + zoned(a.creditLimit())
            + "CASH LIMIT : " + zoned(a.cashCreditLimit())
            + "OPEN DATE : " + date(a.openDate())
            + "EXPR DATE : " + date(a.expirationDate())
            + "REIS DATE : " + date(a.reissueDate())
            + "CREDIT BAL : " + zoned(a.currentCycleCredit())
            + "DEBIT BAL : " + zoned(a.currentCycleDebit())
            + "GROUP ID : " + x(a.groupId(), 10);
    }

    private static String zoned(BigDecimal v) {
        return Overpunch.encode(v == null ? BigDecimal.ZERO : v, 12, 2);
    }

    private static String date(LocalDate d) {
        return d == null ? " ".repeat(10) : d.toString();
    }

    private static String x(String s, int len) {
        String v = s == null ? "" : s;
        return v.length() >= len ? v.substring(0, len) : v + " ".repeat(len - v.length());
    }

    private static boolean positive(String key) {
        return key.length() == 11 && key.chars().allMatch(c -> c >= '0' && c <= '9') && Long.parseLong(key) > 0;
    }

    /** Bytes [from, to) of the request as printable ASCII, space-padded when the request is shorter. */
    private static String text(byte[] b, int from, int to) {
        StringBuilder sb = new StringBuilder(to - from);
        for (int i = from; i < to; i++) {
            int c = i < b.length ? b[i] & 0xff : ' ';
            sb.append(c >= 0x20 && c < 0x7f ? (char) c : '?');
        }
        return sb.toString();
    }
}
