package com.carddemo.transaction.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One CVTRA05Y record to insert. Timestamp texts are stored exactly as the COBOL writer formats
 * them; the parsed values are stored alongside for ordering (Q-ENT-08).
 */
public record NewTransaction(
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
    LocalDateTime origTs,
    String procTsText,
    LocalDateTime procTs) {
}
