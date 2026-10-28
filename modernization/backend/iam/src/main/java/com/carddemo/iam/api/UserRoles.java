package com.carddemo.iam.api;

import com.carddemo.common.security.Role;
import java.util.Optional;

/** The role of a stored user (app_user.user_type), for jobs that act on behalf of a user (D-REPORT). */
public interface UserRoles {

    /** Empty when no such user exists. */
    Optional<Role> role(String userId);
}
