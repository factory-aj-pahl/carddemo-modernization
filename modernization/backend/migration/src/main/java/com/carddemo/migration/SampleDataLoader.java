package com.carddemo.migration;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Loads legacy fixed-width files into the schema (replaces the setup JCL ACCTFILE, CARDFILE,
 * CUSTFILE, XREFFILE, TCATBALF, TRANTYPE, TRANCATG, DISCGRP, DUSRSECJ). Missing optional files are
 * skipped. Rows are loaded without online validation (Q-ENT-37).
 */
public class SampleDataLoader {

    /** Tables cleared by {@link #clear()}, children first. */
    public static final List<String> TABLES = List.of("auth_fraud_report", "auth_error_log",
            "pending_auth_detail", "pending_auth_summary", "user_account", "audit_log", "app_user", "posting_reject",
            "card_transaction", "tran_cat_balance", "card_xref", "card", "account", "customer", "disclosure_group",
            "transaction_category", "transaction_type");

    private final JdbcTemplate jdbc;
    private final int bcryptCost;

    public SampleDataLoader(JdbcTemplate jdbc) {
        this(jdbc, 12);
    }

    /** @param bcryptCost BCrypt cost for seeded passwords (12 in production, lower in tests for speed) */
    public SampleDataLoader(JdbcTemplate jdbc, int bcryptCost) {
        this.jdbc = jdbc;
        this.bcryptCost = bcryptCost;
    }

    public record Result(Map<String, Integer> rowsPerFile, int groupIdsMoved, int users, int links) {
    }

    /** Deletes all rows of the base tables and resets the online transaction id counter. */
    public void clear() {
        for (String t : TABLES) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("UPDATE id_counter SET value = 0 WHERE name = 'online_tran_id'");
    }

    /**
     * Loads every base layout whose file exists in {@code dir}, then applies the migration rule of
     * Q-ENT-02 (group ID found in the ZIP slot moves to the group slot).
     */
    public Result loadDirectory(Path dir) throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Layout layout : Layouts.BASE) {
            Path file = dir.resolve(layout.fileName());
            if (Files.exists(file)) {
                counts.put(layout.fileName(), loadFile(layout, file));
            }
        }
        int moved = applyGroupIdMigration();
        return new Result(counts, moved, 0, 0);
    }

    public int loadFile(Layout layout, Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.ISO_8859_1).stream()
                .filter(l -> !l.isEmpty() && !l.equals("\r")).toList();
        if (lines.isEmpty()) {
            return 0;
        }
        List<String> columns = new ArrayList<>(RecordCodec.decode(layout, lines.get(0)).keySet());
        String sql = "INSERT INTO " + layout.table() + " (" + String.join(", ", columns) + ") VALUES ("
                + columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
        List<Object[]> batch = new ArrayList<>();
        for (String line : lines) {
            Map<String, Object> v = RecordCodec.decode(layout, line);
            batch.add(columns.stream().map(v::get).toArray());
        }
        jdbc.batchUpdate(sql, batch);
        return lines.size();
    }

    /** Q-ENT-02 / RP-DATA-1: move a group ID stored in the ZIP slot to the group slot. */
    public int applyGroupIdMigration() {
        return jdbc.update("UPDATE account SET group_id = addr_zip, addr_zip = '' "
                + "WHERE group_id = '' AND addr_zip COLLATE Latin1_General_BIN2 IN (SELECT DISTINCT group_id FROM disclosure_group)");
    }

    /**
     * Loads users from an EBCDIC (code page 037) USRSEC file of 80-byte records, hashing each password
     * with BCrypt (Q-COSGN00C-01), and links accounts round-robin to the regular users (D-SEED).
     */
    public Result loadUsers(Path usrsecEbcdic) throws IOException {
        byte[] data = Files.readAllBytes(usrsecEbcdic);
        Charset cp037 = Charset.forName("IBM037");
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(bcryptCost);
        int users = 0;
        for (int off = 0; off + 80 <= data.length; off += 80) {
            String rec = new String(data, off, 80, cp037);
            String id = rec.substring(0, 8).strip().toUpperCase();
            if (id.isEmpty()) {
                continue;
            }
            String type = rec.substring(56, 57);
            if (!type.equals("A") && !type.equals("U")) {
                throw new IllegalArgumentException("USRSEC record " + id + " has invalid user type (Q-COSGN00C-07)");
            }
            jdbc.update("INSERT INTO app_user (user_id, first_name, last_name, password_hash, user_type) VALUES (?, ?, ?, ?, ?)",
                    id, rec.substring(8, 28).stripTrailing(), rec.substring(28, 48).stripTrailing(),
                    encoder.encode(rec.substring(48, 56).stripTrailing()), type);
            users++;
        }
        int links = linkAccountsRoundRobin();
        return new Result(Map.of(), 0, users, links);
    }

    /** D-SEED: account i (in key order) is linked to regular user (i mod n) in user-ID order. */
    public int linkAccountsRoundRobin() {
        List<String> userIds = jdbc.queryForList("SELECT user_id FROM app_user WHERE user_type = 'U' ORDER BY user_id", String.class);
        if (userIds.isEmpty()) {
            return 0;
        }
        List<Long> accounts = jdbc.queryForList("SELECT account_id FROM account ORDER BY account_id", Long.class);
        int i = 0;
        for (Long acct : accounts) {
            jdbc.update("INSERT INTO user_account (user_id, account_id) VALUES (?, ?)", userIds.get(i % userIds.size()), acct);
            i++;
        }
        return accounts.size();
    }
}
