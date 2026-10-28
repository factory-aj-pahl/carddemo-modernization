package com.carddemo.iam.internal;

import com.carddemo.common.security.Role;
import com.carddemo.iam.api.UserRoles;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class JdbcUserRoles implements UserRoles {

    private final JdbcClient jdbc;

    public JdbcUserRoles(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Role> role(String userId) {
        return jdbc.sql("SELECT user_type FROM app_user WHERE user_id = ?").param(userId).query(String.class).optional()
            .map(Role::fromUserType);
    }
}
