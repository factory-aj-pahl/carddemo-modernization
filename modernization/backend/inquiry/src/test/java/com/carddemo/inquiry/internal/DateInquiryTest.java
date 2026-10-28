package com.carddemo.inquiry.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.test.Covers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DateInquiryTest {

    @Test
    @Covers({"TS-CODATE01-04", "BR-CODATE01-06"})
    void legacy_text_uses_leading_zeros_and_a_24_hour_clock() {
        DateInquiry d = new DateInquiry(Clock.fixed(Instant.parse("2024-01-05T09:03:07Z"), ZoneOffset.UTC));
        assertThat(d.legacyReply()).startsWith("SYSTEM DATE : 01-05-2024SYSTEM TIME : 09:03:07").hasSize(1000);
        Clock evening = Clock.fixed(Instant.parse("2024-01-05T21:03:07Z"), ZoneOffset.UTC);
        assertThat(new DateInquiry(evening).legacyReply()).startsWith("SYSTEM DATE : 01-05-2024SYSTEM TIME : 21:03:07");
        assertThat(d.now()).hasToString("2024-01-05T09:03:07Z");
    }
}
