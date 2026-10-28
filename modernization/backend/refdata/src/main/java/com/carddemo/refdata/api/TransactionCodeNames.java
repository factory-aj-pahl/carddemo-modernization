package com.carddemo.refdata.api;

import java.util.Optional;

/** Transaction type and category descriptions (TRANTYPE, TRANCATG) for reports. */
public interface TransactionCodeNames {

    Optional<String> typeDescription(String typeCd);

    Optional<String> categoryDescription(String typeCd, int catCd);
}
