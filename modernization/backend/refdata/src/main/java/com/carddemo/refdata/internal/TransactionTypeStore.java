package com.carddemo.refdata.internal;

import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * SQL of the transaction-type programs (COTRTLIC, COTRTUPC, COBTUPDT) against {@code transaction_type}.
 * Each call is one statement; outside a caller's transaction it commits on success, which is the SYNCPOINT
 * the screens take after a successful change.
 */
@Repository
public class TransactionTypeStore {

    /** Rows per COTRTLIC page (WS-MAX-SCREEN-LINES). */
    public static final int PAGE = 7;

    public enum Change { DONE, NOT_FOUND, CONFLICT }

    private static final String COLUMNS = "type_cd, description, CAST(row_version AS BIGINT) AS v";
    private static final RowMapper<TypeRow> ROW = (rs, i) -> new TypeRow(rs.getString(1), TypeEdits.rtrim(rs.getString(2)), rs.getLong(3));

    private final JdbcTemplate jdbc;

    public TransactionTypeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 9998-PRIMING-QUERY. */
    public void ping() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
        } catch (DataAccessException e) {
            throw new Db2Failure(Db2Codes.of(e, Db2Codes.Op.READ), e);
        }
    }

    /** 9100-CHECK-FILTERS. */
    public int count(TypeFilter f) {
        try {
            Integer n = jdbc.queryForObject("SELECT COUNT(1) FROM transaction_type WHERE 1 = 1" + where(f), Integer.class, args(f).toArray());
            return n == null ? 0 : n;
        } catch (DataAccessException e) {
            throw new Db2Failure(Db2Codes.of(e, Db2Codes.Op.READ), e);
        }
    }

    /**
     * C-TR-TYPE-FORWARD: up to {@link #PAGE} rows from the start key upward plus one look-ahead row.
     *
     * @throws CursorFailure with phase OPEN when the query fails
     */
    public List<TypeRow> forward(String startKey, TypeFilter f) {
        try {
            return jdbc.query("SELECT TOP " + (PAGE + 1) + " " + COLUMNS + " FROM transaction_type WHERE type_cd >= ?" + where(f)
                + " ORDER BY type_cd", ROW, withKey(startKey, f));
        } catch (DataAccessException e) {
            throw new CursorFailure(CursorFailure.Phase.OPEN, List.of(), Db2Codes.of(e, Db2Codes.Op.READ));
        }
    }

    /**
     * C-TR-TYPE-BACKWARD: up to {@link #PAGE} rows below the start key, highest first.
     *
     * @throws CursorFailure with phase OPEN when the query fails
     */
    public List<TypeRow> backward(String startKey, TypeFilter f) {
        try {
            return jdbc.query("SELECT TOP " + PAGE + " " + COLUMNS + " FROM transaction_type WHERE type_cd < ?" + where(f)
                + " ORDER BY type_cd DESC", ROW, withKey(startKey, f));
        } catch (DataAccessException e) {
            throw new CursorFailure(CursorFailure.Phase.OPEN, List.of(), Db2Codes.of(e, Db2Codes.Op.READ));
        }
    }

    public Optional<TypeRow> find(String code) {
        try {
            return jdbc.query("SELECT " + COLUMNS + " FROM transaction_type WHERE type_cd = ?", ROW, code).stream().findFirst();
        } catch (DataAccessException e) {
            throw new Db2Failure(Db2Codes.of(e, Db2Codes.Op.READ), e);
        }
    }

    /** Sets the description if the row still has the version that was read. */
    public Change update(String code, String description, long expectedVersion) {
        int n;
        try {
            n = jdbc.update("UPDATE transaction_type SET description = ? WHERE type_cd = ? AND CAST(row_version AS BIGINT) = ?",
                TypeEdits.trim(description), code, expectedVersion);
        } catch (DataAccessException e) {
            throw new Db2Failure(Db2Codes.of(e, Db2Codes.Op.UPDATE), e);
        }
        if (n > 0) {
            return Change.DONE;
        }
        return exists(code) ? Change.CONFLICT : Change.NOT_FOUND;
    }

    /** COBTUPDT: insert; each SQL warning of the statement is passed to {@code warnings}. */
    public void batchInsert(String code, String description, Consumer<String> warnings) {
        try {
            withWarnings("INSERT INTO transaction_type (type_cd, description) VALUES (?, ?)", warnings, code,
                TypeEdits.trim(description));
        } catch (DataAccessException e) {
            throw new Db2Failure(Db2Codes.of(e, Db2Codes.Op.INSERT), e);
        }
    }

    /** COBTUPDT: unconditional update by key; each SQL warning of the statement is passed to {@code warnings}. */
    public Change batchUpdate(String code, String description, Consumer<String> warnings) {
        try {
            int n = withWarnings("UPDATE transaction_type SET description = ? WHERE type_cd = ?", warnings,
                TypeEdits.trim(description), code);
            return n > 0 ? Change.DONE : Change.NOT_FOUND;
        } catch (DataAccessException e) {
            throw new Db2Failure(Db2Codes.of(e, Db2Codes.Op.UPDATE), e);
        }
    }

    /** COBTUPDT: delete by key; each SQL warning of the statement is passed to {@code warnings}. */
    public Change batchDelete(String code, Consumer<String> warnings) {
        try {
            int n = withWarnings("DELETE FROM transaction_type WHERE type_cd = ?", warnings, code);
            return n > 0 ? Change.DONE : Change.NOT_FOUND;
        } catch (DataAccessException e) {
            throw new Db2Failure(Db2Codes.of(e, Db2Codes.Op.DELETE), e);
        }
    }

    /**
     * SQL Server has no positive SQLCODEs; its informational messages reach JDBC as {@link SQLWarning}s on the
     * statement, which {@code JdbcTemplate.update} discards (or turns into an exception).
     */
    private int withWarnings(String sql, Consumer<String> warnings, Object... args) {
        Integer n = jdbc.execute(sql, (PreparedStatementCallback<Integer>) ps -> {
            new ArgumentPreparedStatementSetter(args).setValues(ps);
            int rows = ps.executeUpdate();
            for (SQLWarning w = ps.getWarnings(); w != null; w = w.getNextWarning()) {
                warnings.accept(w.getMessage());
            }
            return rows;
        });
        return n == null ? 0 : n;
    }

    public void insert(String code, String description) {
        try {
            jdbc.update("INSERT INTO transaction_type (type_cd, description) VALUES (?, ?)", code, TypeEdits.trim(description));
        } catch (DataAccessException e) {
            throw new Db2Failure(Db2Codes.of(e, Db2Codes.Op.INSERT), e);
        }
    }

    /** Deletes the row; with an expected version, only while the row is unchanged since it was read. */
    public Change delete(String code, Long expectedVersion) {
        int n;
        try {
            n = expectedVersion == null ? jdbc.update("DELETE FROM transaction_type WHERE type_cd = ?", code)
                : jdbc.update("DELETE FROM transaction_type WHERE type_cd = ? AND CAST(row_version AS BIGINT) = ?", code, expectedVersion);
        } catch (DataAccessException e) {
            throw new Db2Failure(Db2Codes.of(e, Db2Codes.Op.DELETE), e);
        }
        if (n > 0) {
            return Change.DONE;
        }
        return expectedVersion != null && exists(code) ? Change.CONFLICT : Change.NOT_FOUND;
    }

    private boolean exists(String code) {
        return find(code).isPresent();
    }

    /**
     * The optional filters of the cursors and the COUNT. The description column uses the database default
     * collation, so the match is forced to a binary collation: Db2 compares case-sensitively (Q-COTRTLIC-09).
     * A {@code [} is a pattern character in SQL Server but not in Db2, so it is matched literally.
     */
    private static String where(TypeFilter f) {
        return (f.typeCode() == null ? "" : " AND type_cd = ?")
            + (f.descriptionLike() == null ? "" : " AND description COLLATE Latin1_General_BIN2 LIKE ? COLLATE Latin1_General_BIN2");
    }

    private static List<Object> args(TypeFilter f) {
        List<Object> a = new ArrayList<>();
        if (f.typeCode() != null) {
            a.add(f.typeCode());
        }
        if (f.descriptionLike() != null) {
            a.add(f.descriptionLike().replace("[", "[[]"));
        }
        return a;
    }

    private static Object[] withKey(String startKey, TypeFilter f) {
        List<Object> a = new ArrayList<>();
        a.add(startKey);
        a.addAll(args(f));
        return a.toArray();
    }
}
