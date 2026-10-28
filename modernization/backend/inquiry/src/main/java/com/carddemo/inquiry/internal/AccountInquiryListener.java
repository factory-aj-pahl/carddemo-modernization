package com.carddemo.inquiry.internal;

import com.carddemo.inquiry.internal.AccountInquiryService.ReadFailed;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * COACCT01 listener (CDRA). Every request is answered on its reply queue: the account reply, the not-found or
 * invalid-request reply, or, after a read failure, an error reply together with the error record on the error
 * queue (Q-COACCT01-07). The request is acknowledged once answered; an unexpected failure is thrown, so the
 * request is redelivered and dead-lettered after the delivery limit (Q-COACCT01-08).
 */
@Component
class AccountInquiryListener {

    private final AccountInquiryService service;
    private final InquiryReplies replies;
    private final InquiryQueues queues;
    private final RequesterRateLimit rateLimit;

    AccountInquiryListener(AccountInquiryService service, InquiryReplies replies, InquiryQueues queues, RequesterRateLimit rateLimit) {
        this.service = service;
        this.replies = replies;
        this.queues = queues;
        this.rateLimit = rateLimit;
    }

    @RabbitListener(queues = "#{@inquiryQueues.accountRequest()}")
    void onRequest(Message request) {
        MessageProperties in = request.getMessageProperties();
        String replyQueue = InquiryReplies.replyQueue(in, queues.accountReply());
        if (!rateLimit.tryAcquire(in)) {
            ErrorRecord limited = ErrorRecord.broker(RequesterRateLimit.RATE_EXCEEDED, 0, queues.accountRequest());
            replies.reportError(limited, in);
            replies.reply(in, replyQueue, limited.text(), "error");
            return;
        }
        String text;
        try {
            text = AccountInquiryService.legacyReply(service.answer(request.getBody()));
        } catch (ReadFailed e) {
            ErrorRecord record = ErrorRecord.storage(AccountInquiryService.READ_ERROR, queues.accountRequest(), e.storageCode());
            replies.reportError(record, in);
            replies.reply(in, replyQueue, record.text(), "error");
            return;
        }
        replies.reply(in, replyQueue, text, "reply");
    }
}
