package com.carddemo.iam.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * D-SEC-EXTRAS settings.
 *
 * @param bcryptCost       BCrypt cost for new hashes (Q-COSGN00C-01: 12)
 * @param idleTimeout      session expires after this long without a request
 * @param lockoutThreshold consecutive failures that lock a user ID
 * @param lockoutDuration  how long a locked ID is refused, from the failure that locked it
 */
@ConfigurationProperties("carddemo.security")
public record SecurityProperties(Integer bcryptCost, Duration idleTimeout, Integer lockoutThreshold, Duration lockoutDuration) {

    public SecurityProperties {
        bcryptCost = bcryptCost == null ? 12 : bcryptCost;
        idleTimeout = idleTimeout == null ? Duration.ofMinutes(15) : idleTimeout;
        lockoutThreshold = lockoutThreshold == null ? 5 : lockoutThreshold;
        lockoutDuration = lockoutDuration == null ? Duration.ofMinutes(15) : lockoutDuration;
    }
}
