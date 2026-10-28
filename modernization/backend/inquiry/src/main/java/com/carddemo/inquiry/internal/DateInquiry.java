package com.carddemo.inquiry.internal;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;

/** CODATE01: the server's current date and time, read when the request is processed (Q-CODATE01-15). */
@Service
public class DateInquiry {

    /** BR-CODATE01-07: 46 bytes of labelled text, then spaces to 1000 bytes. */
    static final DateTimeFormatter LEGACY = DateTimeFormatter.ofPattern("'SYSTEM DATE : 'MM-dd-uuuu'SYSTEM TIME : 'HH:mm:ss");
    static final int REPLY_LENGTH = 1000;

    private final Clock clock;

    public DateInquiry(Clock clock) {
        this.clock = clock;
    }

    /** The queue reply text, in the server's local time without zone (Q-CODATE01-03: kept on the queue). */
    public String legacyReply() {
        String text = LocalDateTime.now(clock).format(LEGACY);
        return text + " ".repeat(REPLY_LENGTH - text.length());
    }

    /** The REST answer: local time with its UTC offset, to the second (Q-CODATE01-03). */
    public OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
