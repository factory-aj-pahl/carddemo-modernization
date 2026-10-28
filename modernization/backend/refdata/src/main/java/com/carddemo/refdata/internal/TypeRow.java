package com.carddemo.refdata.internal;

import java.io.Serializable;

/**
 * One TRANSACTION_TYPE row: two-character code, the description as stored (trimmed, Q-COTRTLIC-10), and the
 * row version read with it; an update applies only while the version is unchanged (Q-COTRTLIC-19, Q-COTRTUPC-17).
 */
public record TypeRow(String code, String description, long version) implements Serializable {
}
