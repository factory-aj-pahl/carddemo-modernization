package com.carddemo.inquiry.internal;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * CODATE01 listener (CDRD): every request gets one date reply; the request content, including its size, is not
 * read (Q-CODATE01-02, -07). A failed put of the reply is reported on the error queue (Q-CODATE01-06).
 */
@Component
class DateInquiryListener {

    private final DateInquiry date;
    private final InquiryReplies replies;
    private final InquiryQueues queues;
    private final RequesterRateLimit rateLimit;

    DateInquiryListener(DateInquiry date, InquiryReplies replies, InquiryQueues queues, RequesterRateLimit rateLimit) {
        this.date = date;
        this.replies = replies;
        this.queues = queues;
        this.rateLimit = rateLimit;
    }

    @RabbitListener(queues = "#{@inquiryQueues.dateRequest()}")
    void onRequest(Message request) {
        MessageProperties in = request.getMessageProperties();
        String replyQueue = InquiryReplies.replyQueue(in, queues.dateReply());
        if (!rateLimit.tryAcquire(in)) {
            ErrorRecord limited = ErrorRecord.broker(RequesterRateLimit.RATE_EXCEEDED, 0, queues.dateRequest());
            replies.reportError(limited, in);
            replies.reply(in, replyQueue, limited.text(), "error");
            return;
        }
        replies.reply(in, replyQueue, date.legacyReply(), "reply");
    }
}
