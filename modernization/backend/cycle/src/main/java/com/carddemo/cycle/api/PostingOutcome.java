package com.carddemo.cycle.api;

/**
 * Result of posting one daily transaction: reason 0 when it was posted, otherwise the reject reason
 * code and description written to the rejects (BR-CBTRN02C-09).
 */
public record PostingOutcome(int reasonCode, String reasonText) {

    public static final PostingOutcome POSTED = new PostingOutcome(0, "");

    public boolean posted() {
        return reasonCode == 0;
    }
}
