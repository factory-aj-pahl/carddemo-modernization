package com.carddemo.billpayservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Selective conversion of BC-5: bill payment alone as a service. It carries sign-on and the
 * session (IAM) plus the account, card, and transaction APIs that bill payment needs, and nothing
 * else. Main-menu options of contexts that are not deployed are switched off in configuration.
 * Only the api and internal packages of account, card, and transaction are scanned so their
 * screen controllers (and the beans those need) are not deployed here.
 */
@SpringBootApplication(scanBasePackages = {
    "com.carddemo.common", "com.carddemo.iam",
    "com.carddemo.account.api", "com.carddemo.account.internal",
    "com.carddemo.card.api", "com.carddemo.card.internal",
    "com.carddemo.transaction.api", "com.carddemo.transaction.internal",
    "com.carddemo.billpay"})
public class BillPayServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillPayServiceApplication.class, args);
    }
}
