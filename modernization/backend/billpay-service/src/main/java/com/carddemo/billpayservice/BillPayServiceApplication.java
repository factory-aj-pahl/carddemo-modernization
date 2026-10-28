package com.carddemo.billpayservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Selective conversion of BC-5: bill payment alone as a service. It carries sign-on and the
 * session (IAM) plus the account, card, and transaction APIs that bill payment needs, and nothing
 * else. Main-menu options of contexts that are not deployed are switched off in configuration.
 */
@SpringBootApplication(scanBasePackages = {
    "com.carddemo.common", "com.carddemo.iam", "com.carddemo.account", "com.carddemo.card",
    "com.carddemo.transaction", "com.carddemo.billpay"})
public class BillPayServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillPayServiceApplication.class, args);
    }
}
