package com.carddemo.dataexchange.internal;

/** A failed legacy file operation, carrying the two-character file status the COBOL program would have seen. */
final class FileStatusException extends RuntimeException {

    private final String status;

    FileStatusException(String status, Throwable cause) {
        super("file status " + status, cause);
        this.status = status;
    }

    FileStatusException(String status) {
        this(status, null);
    }

    String status() {
        return status;
    }
}
