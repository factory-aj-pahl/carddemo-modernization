package com.carddemo.common.security;

import java.io.Serializable;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** The signed-on user held in the server-side session (C-SESSION). */
public record CurrentUser(String userId, Role role, String firstName, String lastName) implements Serializable {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    /** The user of the current request, if the request has an authenticated session. */
    public static Optional<CurrentUser> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    /** The user of the current request; fails if there is none (endpoints are protected by the security filter). */
    public static CurrentUser require() {
        return current().orElseThrow(() -> new IllegalStateException("No signed-on user"));
    }
}
