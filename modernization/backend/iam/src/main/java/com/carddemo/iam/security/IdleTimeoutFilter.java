package com.carddemo.iam.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Q-COSGN00C-05 (D-SEC-EXTRAS): a session expires after the idle timeout measured with the
 * application clock. The expired request continues unauthenticated, so protected endpoints answer 401.
 */
class IdleTimeoutFilter extends OncePerRequestFilter {

    static final String LAST_ACCESS = "carddemo.lastAccess";

    private final Clock clock;
    private final Duration timeout;

    IdleTimeoutFilter(Clock clock, Duration timeout) {
        this.clock = clock;
        this.timeout = timeout;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Instant now = clock.instant();
            Instant last = (Instant) session.getAttribute(LAST_ACCESS);
            if (last != null && Duration.between(last, now).compareTo(timeout) > 0) {
                session.invalidate();
                SecurityContextHolder.clearContext();
            } else {
                session.setAttribute(LAST_ACCESS, now);
            }
        }
        chain.doFilter(request, response);
    }
}
