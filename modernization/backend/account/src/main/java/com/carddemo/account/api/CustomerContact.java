package com.carddemo.account.api;

/** The CUSTREC name, address and first phone of one customer, as stored. */
public record CustomerContact(long customerId, String firstName, String middleName, String lastName, String addrLine1,
        String addrLine2, String city, String stateCd, String zip, String phone1) {
}
