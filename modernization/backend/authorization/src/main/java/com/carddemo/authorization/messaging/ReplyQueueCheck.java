package com.carddemo.authorization.messaging;

import java.io.IOException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Whether a reply queue exists; an exclusive queue of another connection (a requester's private queue) counts as existing. */
final class ReplyQueueCheck {

    private ReplyQueueCheck() {
    }

    static boolean exists(RabbitTemplate rabbit, String queue) {
        try {
            rabbit.execute(channel -> {
                channel.queueDeclarePassive(queue);
                return null;
            });
            return true;
        } catch (AmqpException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                String m = t.getMessage();
                if (m != null && m.contains("reply-code=404")) {
                    return false;
                }
                if (t instanceof IOException && t.getCause() != null && String.valueOf(t.getCause().getMessage()).contains("reply-code=404")) {
                    return false;
                }
            }
            return true;
        }
    }
}
