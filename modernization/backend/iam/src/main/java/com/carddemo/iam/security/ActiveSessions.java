package com.carddemo.iam.security;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * Signed-on sessions by user ID, so user maintenance can end the sessions of a user whose type or
 * password was changed (Q-COUSR02C-04) or who was deleted (Q-COUSR03C-07). A session leaves the
 * registry when it is invalidated or expires. The registry is per API instance.
 */
@Component
public class ActiveSessions {

    static final String ATTR = "carddemo.activeSession";

    private final ConcurrentMap<String, Set<HttpSession>> byUser = new ConcurrentHashMap<>();

    public void register(String userId, HttpSession session) {
        // Attribute first: replacing an earlier binding fires valueUnbound, which would drop the new entry.
        session.setAttribute(ATTR, new Binding(userId));
        byUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    /** Invalidates every registered session of the user; returns how many were ended. */
    public int endAll(String userId) {
        Set<HttpSession> sessions = byUser.remove(userId);
        if (sessions == null) {
            return 0;
        }
        int ended = 0;
        for (HttpSession s : List.copyOf(sessions)) {
            try {
                s.invalidate();
                ended++;
            } catch (IllegalStateException alreadyInvalid) {
                // ended by sign-off or idle timeout in the meantime
            }
        }
        return ended;
    }

    private void forget(String userId, HttpSession session) {
        byUser.computeIfPresent(userId, (k, set) -> {
            set.remove(session);
            return set.isEmpty() ? null : set;
        });
    }

    /** Not Serializable on purpose: a persisted session restored after a restart is not re-registered. */
    private final class Binding implements HttpSessionBindingListener {
        private final String userId;

        Binding(String userId) {
            this.userId = userId;
        }

        @Override
        public void valueUnbound(HttpSessionBindingEvent event) {
            forget(userId, event.getSession());
        }
    }
}
