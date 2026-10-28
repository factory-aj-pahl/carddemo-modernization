package com.carddemo.refdata.internal;

import java.sql.SQLException;

/**
 * Db2 SQLCODE equivalents of SQL Server errors, so the screens and COBTUPDT can keep their SQLCODE branches
 * (-532 child rows exist, -803 duplicate key, -911 deadlock or lock timeout) and message formats. The text
 * is the database's own error message (Q-COTRTLIC-13, Q-COTRTUPC-03).
 */
public final class Db2Codes {

    public static final int NOT_FOUND = 100;
    public static final int FK_DELETE = -532;
    public static final int DUPLICATE = -803;
    public static final int DEADLOCK = -911;
    /** Db2 text of SQLCODE +100, as DSNTIAC would format it. */
    public static final String NOT_FOUND_TEXT =
        "ROW NOT FOUND FOR FETCH, UPDATE OR DELETE, OR THE RESULT OF A QUERY IS AN EMPTY TABLE";

    /** The kind of statement that failed; SQL Server reports a foreign key violation the same way for all. */
    public enum Op { READ, INSERT, UPDATE, DELETE }

    private Db2Codes() {
    }

    public static Db2Error of(Throwable failure, Op op) {
        SQLException sql = null;
        for (Throwable t = failure; t != null && sql == null; t = t.getCause()) {
            if (t instanceof SQLException s) {
                sql = s;
            }
        }
        if (sql == null) {
            String text = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return new Db2Error(-901, text);
        }
        String state = sql.getSQLState();
        int code = switch (sql.getErrorCode()) {
            case 547 -> op == Op.DELETE ? FK_DELETE : -530;
            case 2627, 2601 -> DUPLICATE;
            case 1205, 1222 -> DEADLOCK;
            case 515 -> -407;
            case 8152, 2628 -> -404;
            case 208 -> -204;
            case 207 -> -206;
            default -> state != null && state.startsWith("08") ? -904 : -901;
        };
        return new Db2Error(code, sql.getMessage() == null ? "" : sql.getMessage());
    }

    /** {@code PIC ----9}: five characters, sign floating left of the digits ({@code -532} as {@code " -532"}). */
    public static String pic(int code) {
        String digits = Integer.toString(Math.abs(code));
        String s = code < 0 ? "-" + digits : digits;
        return s.length() >= 5 ? s : " ".repeat(5 - s.length()) + s;
    }

    public static String cut(String s, int len) {
        return s.length() > len ? s.substring(0, len) : s;
    }
}
