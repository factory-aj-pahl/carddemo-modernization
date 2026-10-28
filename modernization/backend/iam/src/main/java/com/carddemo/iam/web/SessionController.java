package com.carddemo.iam.web;

import com.carddemo.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Who is signed on (the web app's replacement for reading the COMMAREA user fields). */
@RestController
@Tag(name = "Session")
public class SessionController {

    @GetMapping("/api/session")
    @Operation(summary = "Signed-on user and role, or 401 when there is no valid session")
    public ResponseEntity<CurrentUser> session() {
        return CurrentUser.current().map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }
}
