package com.carddemo.account.internal;

import java.io.Serializable;
import java.time.LocalDate;

/** One CUSTDAT record (CVCUS01Y) with the row version read with it; {@code city} is CUST-ADDR-LINE-3. */
public record CustomerRecord(int customerId, String firstName, String middleName, String lastName, String addrLine1,
                             String addrLine2, String city, String stateCd, String countryCd, String zip,
                             String phone1, String phone2, String ssn, String govtIssuedId, LocalDate dob,
                             String eftAccountId, String priCardHolder, int ficoScore, byte[] rowVersion)
    implements Serializable {
}
