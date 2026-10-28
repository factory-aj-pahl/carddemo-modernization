package com.carddemo.inquiry.web;

import com.carddemo.inquiry.internal.DateInquiry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST equivalent of the CODATE01 queue service (D-MQ). */
@RestController
@Tag(name = "CODATE01", description = "System date and time inquiry")
public class DateInquiryController {

    /** @param timestamp ISO 8601 local date-time with UTC offset, for example {@code 2024-03-21T14:05:09-05:00} */
    public record DateTimeReply(String timestamp) {
    }

    private final DateInquiry date;

    public DateInquiryController(DateInquiry date) {
        this.date = date;
    }

    @GetMapping("/api/inquiry/date")
    @Operation(summary = "Server date and time with offset (Q-CODATE01-03)")
    public DateTimeReply date() {
        return new DateTimeReply(date.now().toString());
    }
}
