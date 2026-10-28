package com.carddemo.transaction.api;

import com.carddemo.common.codec.FixedWidth;
import java.math.BigDecimal;

/** One stored transaction as the batch readers see it (CVTRA05Y fields; timestamp texts as stored, Q-ENT-08). */
public record TransactionRecord(
    String tranId,
    String typeCd,
    int catCd,
    String source,
    String description,
    BigDecimal amount,
    long merchantId,
    String merchantName,
    String merchantCity,
    String merchantZip,
    String cardNum,
    String origTsText,
    String procTsText) {

    /** The 350-byte TRAN-RECORD (CVTRA05Y), FILLER as spaces. */
    public String legacy() {
        return FixedWidth.x(tranId, 16) + FixedWidth.x(typeCd, 2) + FixedWidth.n(catCd, 4) + FixedWidth.x(source, 10)
            + FixedWidth.x(description, 100) + FixedWidth.s(amount, 11, 2) + FixedWidth.n(merchantId, 9)
            + FixedWidth.x(merchantName, 50) + FixedWidth.x(merchantCity, 50) + FixedWidth.x(merchantZip, 10)
            + FixedWidth.x(cardNum, 16) + FixedWidth.x(origTsText, 26) + FixedWidth.x(procTsText, 26) + " ".repeat(20);
    }
}
