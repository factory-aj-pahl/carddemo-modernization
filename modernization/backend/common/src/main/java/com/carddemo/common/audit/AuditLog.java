package com.carddemo.common.audit;

import java.util.List;

/**
 * C-AUDIT: one server-side record per audited event. Never pass passwords, full card numbers, CVVs,
 * SSNs, or government IDs in any argument.
 */
public interface AuditLog {

    void record(String actor, String action, String target, List<String> changedFields, String clientAddr);

    default void record(String actor, String action, String target, List<String> changedFields) {
        record(actor, action, target, changedFields, null);
    }
}
