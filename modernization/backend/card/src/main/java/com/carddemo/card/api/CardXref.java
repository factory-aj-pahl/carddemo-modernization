package com.carddemo.card.api;

/** One card cross-reference record (CVACT03Y): card number, customer, and account. */
public record CardXref(String cardNum, long customerId, long accountId) {
}
