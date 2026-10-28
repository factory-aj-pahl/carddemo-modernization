package com.carddemo.common.security;

/** Server-side role from the user type: {@code A} = ADMIN, {@code U} = USER (Q-COSGN00C-07). */
public enum Role {
    ADMIN, USER;

    public static Role fromUserType(String type) {
        return switch (type) {
            case "A" -> ADMIN;
            case "U" -> USER;
            default -> throw new IllegalArgumentException("Unknown user type " + type);
        };
    }

    public String userType() {
        return this == ADMIN ? "A" : "U";
    }

    public String authority() {
        return "ROLE_" + name();
    }
}
