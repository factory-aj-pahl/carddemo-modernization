package com.carddemo.iam.internal;

import java.time.LocalDateTime;

/** Row of app_user (ENT-USER-SECURITY with a BCrypt hash instead of the clear password). */
public record UserRecord(String userId, String firstName, String lastName, String passwordHash, String userType,
                         int failedAttempts, LocalDateTime lockedUntil) {
}
