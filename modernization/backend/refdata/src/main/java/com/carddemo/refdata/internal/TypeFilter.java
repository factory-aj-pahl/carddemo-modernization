package com.carddemo.refdata.internal;

/**
 * The COTRTLIC filters that are in force: an exact type code and a description LIKE pattern
 * ({@code %text%}, case-sensitive, user {@code %} and {@code _} stay wildcards, Q-COTRTLIC-09); null when unused.
 */
public record TypeFilter(String typeCode, String descriptionLike) {

    public static final TypeFilter NONE = new TypeFilter(null, null);
}
