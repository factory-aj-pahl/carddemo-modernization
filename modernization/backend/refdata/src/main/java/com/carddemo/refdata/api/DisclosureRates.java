package com.carddemo.refdata.api;

import java.math.BigDecimal;
import java.util.Optional;

/** Disclosure-group interest rates (DISCGRP, CVTRA02Y) for the interest calculation. */
public interface DisclosureRates {

    /** DIS-INT-RATE (annual percent) for group + type + category; empty when no such record exists. */
    Optional<BigDecimal> interestRate(String groupId, String typeCd, int catCd);
}
