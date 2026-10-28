package com.carddemo.authorization;

import com.carddemo.migration.ImsPendingAuthUnload;
import com.carddemo.test.RepoPaths;
import com.carddemo.test.SampleData;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test fixtures: the base sample plus the DBPAUTP0 IMS unload, and a settable clock. */
public final class AuthFixtures {

    public static final String IMS_FILE = "app/app-authorization-ims-db2-mq/data/EBCDIC/AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat";
    public static final ZoneId ZONE = ZoneId.of("America/Chicago");
    /** Account 7's card (customer 7). */
    public static final String CARD_7 = "4859452612877065";
    /** Account 2's card (customer 2, no pending authorizations in the sample). */
    public static final String CARD_2 = "0923877193247330";
    public static final String EXAMPLE = "231026,143015,4859452612877065,0100,1226,1234  ,102030,000000,100.00,5442,USA,01,"
        + "MERCH0000000001,Amazon.com,Wilmington,DE,19801,TXN000000000001";

    private AuthFixtures() {
    }

    public static Path imsFile() {
        return RepoPaths.root().resolve(IMS_FILE);
    }

    /** Base sample data (users, accounts, cards, customers) plus the 21 summaries and 202 details of the IMS sample. */
    public static void load(SampleData sample, JdbcTemplate jdbc) {
        sample.load();
        try {
            new ImsPendingAuthUnload(jdbc).load(imsFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The example request with another card and amount. */
    public static String request(String card, String amount) {
        return EXAMPLE.replace(CARD_7, card).replace(",100.00,", "," + amount + ",");
    }

    /** A clock the test moves; starts at 2024-03-05 10:00 local, after every sample authorization. */
    public static final class MutableClock extends Clock {
        private volatile Instant now = LocalDateTime.of(2024, 3, 5, 10, 0).atZone(ZONE).toInstant();

        public void set(LocalDateTime local) {
            now = local.atZone(ZONE).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
