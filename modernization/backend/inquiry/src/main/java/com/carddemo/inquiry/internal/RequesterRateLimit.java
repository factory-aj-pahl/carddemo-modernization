package com.carddemo.inquiry.internal;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Q-CODATE01-14: per-requester rate limit for the inquiry queues. The requester is the broker user that
 * published the request ({@code user_id}, which RabbitMQ verifies against the connection's user) together with
 * the {@code app_id} it declared; requests over the limit in one window get an error reply instead of an answer.
 */
@Component
class RequesterRateLimit {

    /** NEW text (no COBOL counterpart), 25 characters at most: the return-message field of the error record. */
    static final String RATE_EXCEEDED = "REQUEST RATE EXCEEDED";

    private final int maxRequests;
    private final long windowMillis;
    private final LongSupplier millis;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    RequesterRateLimit(@Value("${carddemo.mq.rate-limit.requests:100}") int maxRequests,
                       @Value("${carddemo.mq.rate-limit.window:1s}") Duration window) {
        this(maxRequests, window, System::currentTimeMillis);
    }

    /** @param millis elapsed-time source; the limit uses real time, not the business clock */
    RequesterRateLimit(int maxRequests, Duration window, LongSupplier millis) {
        this.maxRequests = maxRequests;
        this.windowMillis = window.toMillis();
        this.millis = millis;
    }

    private record Window(long start, int count) {
    }

    static String requester(MessageProperties p) {
        String user = p.getUserId() == null ? "" : p.getUserId();
        String app = p.getAppId() == null ? "" : p.getAppId();
        return user + "/" + app;
    }

    /** True when the request may be served; counts it against its requester's current window. */
    boolean tryAcquire(MessageProperties request) {
        long now = millis.getAsLong();
        Window w = windows.compute(requester(request), (k, old) ->
            old == null || now - old.start() >= windowMillis ? new Window(now, 1) : new Window(old.start(), old.count() + 1));
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> now - e.getValue().start() >= windowMillis);
        }
        return w.count() <= maxRequests;
    }
}
