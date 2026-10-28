package com.carddemo.cycle.internal;

import com.carddemo.common.codec.FixedWidth;
import com.carddemo.common.codec.LegacyTimestamps;
import com.carddemo.transaction.api.NewTransaction;
import java.math.BigDecimal;

/** A DALYTRAN record (CVTRA06Y, 350 bytes), decoded with EBCDIC overpunch semantics (Q-CBTRN02C-08). */
record DailyRecord(String raw, String tranId, String typeCd, int catCd, String source, String description,
        BigDecimal amount, long merchantId, String merchantName, String merchantCity, String merchantZip,
        String cardNum, String origTsText) {

    static final int LENGTH = 350;

    static DailyRecord parse(String line) {
        String r = FixedWidth.normalize(line, LENGTH);
        return new DailyRecord(r, FixedWidth.text(r, 0, 16), FixedWidth.text(r, 16, 2), (int) FixedWidth.unsigned(r, 18, 4),
            FixedWidth.text(r, 22, 10), FixedWidth.text(r, 32, 100), FixedWidth.signed(r, 132, 11, 2),
            FixedWidth.unsigned(r, 143, 9), FixedWidth.text(r, 152, 50), FixedWidth.text(r, 202, 50),
            FixedWidth.text(r, 252, 10), r.substring(262, 278), FixedWidth.text(r, 278, 26));
    }

    /** DALYTRAN-ORIG-TS(1:10), compared with the account expiry date as text (BR-CBTRN02C-03). */
    String origDateText() {
        return raw.substring(278, 288);
    }

    /** BR-CBTRN02C-04: every field copied one to one; TRAN-PROC-TS is the posting timestamp. */
    NewTransaction toTransaction(String procTsText) {
        return new NewTransaction(tranId, typeCd, catCd, source, description, amount, merchantId, merchantName,
            merchantCity, merchantZip, cardNum, origTsText, LegacyTimestamps.parse(origTsText), procTsText,
            LegacyTimestamps.parse(procTsText));
    }
}
