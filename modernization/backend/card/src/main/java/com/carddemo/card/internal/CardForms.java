package com.carddemo.card.internal;

import com.carddemo.common.mask.Masking;
import java.util.ArrayList;
import java.util.List;

/** Verbatim texts and the key edits shared by COCRDLIC, COCRDSLC, and COCRDUPC. */
public final class CardForms {

    public static final String ACCOUNT_FILTER_INVALID = "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";
    public static final String CARD_FILTER_INVALID = "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";
    public static final String ACCOUNT_NOT_PROVIDED = "Account number not provided";
    public static final String CARD_NOT_PROVIDED = "Card number not provided";
    public static final String NO_INPUT = "No input received";
    public static final String NOT_FOUND = "Did not find cards for this search condition";
    public static final String PROMPT = "Please enter Account and Card Number";

    /**
     * ERR-COCRDLIC-01 / ERR-COCRDSLC-01 / ERR-COCRDUPC-01 layout for a failed read. A database
     * failure has no CICS response code; it is reported as IOERR (17) with RESP2 0.
     */
    public static final String FILE_ERROR = fileError(17, 0);

    public static final String ACCOUNT = "accountId";
    public static final String CARD = "cardNum";

    private CardForms() {
    }

    static String fileError(int resp, int resp2) {
        return String.format("%-12s%-8s%-4s%-9s%-15s%09d %-7s%09d ", "File Error: ", "READ", " on ", "CARDDAT ",
            " returned RESP ", resp, ",RESP2 ", resp2);
    }

    /**
     * Result of the search-key edits (2210-EDIT-ACCOUNT, 2220-EDIT-CARD).
     *
     * @param accountShown what the account field shows next (input, {@code *} when blank; when invalid, empty or as typed)
     * @param cardShown    what the card field shows next (masked when valid, C-MASK)
     * @param account      edited account, null when blank or invalid
     * @param card         edited full card number, null when blank or invalid
     * @param message      first edit message, or null
     * @param errors       fields in error style ({@link #ACCOUNT}, {@link #CARD})
     */
    public record KeyEdit(String accountShown, String cardShown, Long account, String card, String message,
                          List<String> errors) {
        public boolean ok() {
            return message == null;
        }
    }

    /**
     * Edits the account and card keys of the view and update search screens. {@code heldCard} is the
     * full card number behind a masked value the server sent; submitting that mask unchanged means
     * that card (C-MASK). With {@code echoInvalidAccount} a non-numeric account is shown as typed
     * (COCRDUPC, VR-COCRDUPC-02); otherwise it is shown empty (COCRDSLC).
     */
    public static KeyEdit editKeys(String accountIn, String cardIn, String heldCard, boolean echoInvalidAccount) {
        String a = accountIn == null ? "" : accountIn;
        String c = cardIn == null ? "" : cardIn;
        String message = null;
        List<String> errors = new ArrayList<>();
        Long account = null;
        String accountShown;
        boolean accountBlank = blankKey(a, 11);
        if (accountBlank) {
            message = ACCOUNT_NOT_PROVIDED;
            errors.add(ACCOUNT);
            accountShown = "*";
        } else if (!digits(a, 11)) {
            message = ACCOUNT_FILTER_INVALID;
            errors.add(ACCOUNT);
            accountShown = echoInvalidAccount ? (a.length() > 11 ? a.substring(0, 11) : a) : "";
        } else {
            account = Long.parseLong(a);
            accountShown = a;
        }
        String card = null;
        String cardShown;
        boolean held = heldCard != null && c.equals(Masking.card(heldCard));
        boolean cardBlank = !held && blankKey(c, 16);
        if (held) {
            card = heldCard;
            cardShown = c;
        } else if (cardBlank) {
            message = message == null ? CARD_NOT_PROVIDED : message;
            errors.add(CARD);
            cardShown = "*";
        } else if (!digits(c, 16)) {
            message = message == null ? CARD_FILTER_INVALID : message;
            errors.add(CARD);
            cardShown = "";
        } else {
            card = c;
            cardShown = Masking.card(c);
        }
        if (accountBlank && cardBlank) {
            message = NO_INPUT;
        }
        return new KeyEdit(accountShown, cardShown, account, card, message, List.copyOf(errors));
    }

    /** Spaces, {@code *}, or the all-zero key are "not provided". */
    static boolean blankKey(String s, int len) {
        String t = s.strip();
        return t.isEmpty() || t.equals("*") || t.equals("0".repeat(len));
    }

    /** C-KEYLEN: exactly {@code len} digits. */
    public static boolean digits(String s, int len) {
        return s.length() == len && s.chars().allMatch(ch -> ch >= '0' && ch <= '9');
    }

    public static String accountText(long account) {
        return String.format("%011d", account);
    }
}
