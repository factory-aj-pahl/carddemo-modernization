package com.carddemo.inquiry.internal;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/** CODATE01 listener (CDRD): every request gets one date reply; the request content is not read (Q-CODATE01-02). */
@Component
class DateInquiryListener {

    private final RabbitTemplate rabbit;
    private final DateInquiry date;
    private final InquiryQueues queues;

    DateInquiryListener(RabbitTemplate rabbit, DateInquiry date, InquiryQueues queues) {
        this.rabbit = rabbit;
        this.date = date;
        this.queues = queues;
    }

    @RabbitListener(queues = "#{@inquiryQueues.dateRequest()}")
    void onRequest(Message request) {
        MessageProperties in = request.getMessageProperties();
        rabbit.send("", replyQueue(in, queues.dateReply()), new Message(date.legacyReply().getBytes(StandardCharsets.US_ASCII),
            replyProperties(in)));
    }

    /** Q-CODATE01-04: the request's reply_to, else the fixed reply queue. */
    static String replyQueue(MessageProperties request, String fallback) {
        String replyTo = request.getReplyTo();
        return replyTo == null || replyTo.isBlank() ? fallback : replyTo;
    }

    /** Q-CODATE01-05: correlation_id = the request's message_id (its correlation_id if it has none), new message_id. */
    static MessageProperties replyProperties(MessageProperties request) {
        MessageProperties out = new MessageProperties();
        out.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
        out.setContentEncoding(StandardCharsets.US_ASCII.name());
        out.setMessageId(UUID.randomUUID().toString());
        out.setType("reply");
        String correlation = request.getMessageId() != null ? request.getMessageId() : request.getCorrelationId();
        if (correlation != null) {
            out.setCorrelationId(correlation);
        }
        return out;
    }
}
