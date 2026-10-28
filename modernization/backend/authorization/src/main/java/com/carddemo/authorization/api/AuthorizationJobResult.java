package com.carddemo.authorization.api;

/** Return code of an authorization batch job: 0 normal end, 16 the program's 9999-ABEND path. */
public record AuthorizationJobResult(int returnCode) {

    public static AuthorizationJobResult completed() {
        return new AuthorizationJobResult(0);
    }

    public static AuthorizationJobResult rc16() {
        return new AuthorizationJobResult(16);
    }
}
