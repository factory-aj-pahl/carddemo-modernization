package com.carddemo.account.api;

/** The customer fields a statement prints (CUSTREC): text fields with trailing spaces removed. */
public record StatementCustomer(long customerId, String firstName, String middleName, String lastName, String addrLine1,
        String addrLine2, String city, String stateCd, String countryCd, String zip, int ficoScore) {
}
