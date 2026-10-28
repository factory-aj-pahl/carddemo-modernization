package com.carddemo.refdata.internal;

import java.io.Serializable;

/** One failed SQL statement as the COBOL saw it: the SQLCODE and the message text. */
public record Db2Error(int sqlcode, String text) implements Serializable {
}
