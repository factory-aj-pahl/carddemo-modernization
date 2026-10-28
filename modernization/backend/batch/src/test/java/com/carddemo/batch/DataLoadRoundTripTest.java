package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.test.Covers;
import com.carddemo.test.RepoPaths;
import com.carddemo.test.TestDatabaseConfig;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * The migration loader and exporter through the batch command line: every base sample file loads with one row
 * per record and exports back byte for byte (verification/data/owed-files.md), except the Q-ENT-02 group-ID move.
 */
@SpringBootTest(classes = {BatchApplication.class, TestDatabaseConfig.class})
class DataLoadRoundTripTest {

    static final Map<String, Integer> LENGTHS = Map.of("acctdata.txt", 300, "carddata.txt", 150, "cardxref.txt", 50,
        "custdata.txt", 500, "discgrp.txt", 50, "tcatbal.txt", 50, "trancatg.txt", 60, "trantype.txt", 60, "transact.txt", 350);

    @Autowired
    BatchCommandLine cli;
    @Autowired
    JdbcTemplate jdbc;
    @TempDir
    Path dir;

    Path in;
    Path out;

    @BeforeEach
    void stage() throws IOException {
        cli.execute("reset");
        in = Files.createDirectories(dir.resolve("in"));
        out = Files.createDirectories(dir.resolve("out"));
        for (String name : LENGTHS.keySet()) {
            String source = name.equals("transact.txt") ? "dailytran.txt" : name;
            Files.copy(RepoPaths.asciiData().resolve(source), in.resolve(name));
        }
    }

    static List<String> normalized(Path file, int length) throws IOException {
        List<String> recs = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.ISO_8859_1)) {
            String l = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (!l.isEmpty()) {
                recs.add(l + " ".repeat(length - l.length()));
            }
        }
        return recs;
    }

    int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Test
    @Covers({"Q-ENT-02", "Q-ENT-30", "Q-ENT-12", "Q-ENT-34", "DF-09"})
    void every_base_file_loads_one_row_per_record_and_exports_back_byte_for_byte() throws IOException {
        assertThat(cli.execute("load", in.toString())).isZero();
        assertThat(cli.execute("export", out.toString())).isZero();
        assertThat(count("account")).isEqualTo(50);
        assertThat(count("card")).isEqualTo(50);
        assertThat(count("card_xref")).isEqualTo(50);
        assertThat(count("customer")).isEqualTo(50);
        assertThat(count("disclosure_group")).isEqualTo(51);
        assertThat(count("tran_cat_balance")).isEqualTo(50);
        assertThat(count("transaction_category")).isEqualTo(18);
        assertThat(count("transaction_type")).isEqualTo(7);
        assertThat(count("card_transaction")).isEqualTo(300);
        for (Map.Entry<String, Integer> e : LENGTHS.entrySet()) {
            List<String> expected = normalized(in.resolve(e.getKey()), e.getValue());
            if (e.getKey().equals("acctdata.txt")) {
                expected = expected.stream().map(r -> r.substring(0, 102) + " ".repeat(10) + r.substring(102, 112) + r.substring(122))
                    .toList();
            }
            String exported = Files.readString(out.resolve(e.getKey()), StandardCharsets.ISO_8859_1);
            assertThat(exported).doesNotContain("\r");
            assertThat(normalized(out.resolve(e.getKey()), e.getValue())).as(e.getKey()).containsExactlyElementsOf(expected);
        }
        assertThat(jdbc.queryForList("SELECT DISTINCT group_id FROM account", String.class)).containsExactly("A000000000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE LEN(ISNULL(addr_zip, '')) > 0", Integer.class))
            .isZero();
        assertThat(jdbc.queryForObject("SELECT description FROM transaction_type WHERE type_cd = '06'", String.class))
            .isEqualTo("Reversal");
    }

    @Test
    @Covers({"DF-02", "DF-03"})
    void signed_zoned_fields_decode_with_the_ebcdic_overpunch_meaning() {
        assertThat(cli.execute("load", in.toString())).isZero();
        List<String> daily = jdbc.queryForList("SELECT tran_id FROM card_transaction ORDER BY tran_id", String.class);
        assertThat(daily).hasSize(300);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_transaction WHERE amount = 504.77", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_transaction WHERE amount = -919.00", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_transaction WHERE amount = -56.77", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT current_balance FROM account WHERE account_id = 1", BigDecimal.class))
            .isEqualByComparingTo("194.00");
        assertThat(jdbc.queryForObject("SELECT int_rate FROM disclosure_group WHERE group_id = 'A000000000' AND type_cd = '01' AND cat_cd = 1",
            BigDecimal.class)).isEqualByComparingTo("15.00");
    }

    @Test
    @Covers({"Q-ENT-17", "Q-COSGN00C-01", "Q-DUSRSECJ-01", "D-SEED"})
    void usrsec_users_load_with_bcrypt_hashes_of_their_passwords_and_round_robin_links() throws IOException {
        assertThat(cli.execute("load", in.toString(), "usrsec=" + RepoPaths.usrsec())).isZero();
        byte[] data = Files.readAllBytes(RepoPaths.usrsec());
        assertThat(data).hasSize(800);
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
        for (int off = 0; off < data.length; off += 80) {
            String rec = new String(data, off, 80, Charset.forName("IBM037"));
            Map<String, Object> u = jdbc.queryForMap("SELECT * FROM app_user WHERE user_id = ?", rec.substring(0, 8).strip());
            assertThat(u.get("first_name")).isEqualTo(rec.substring(8, 28).stripTrailing());
            assertThat(u.get("last_name")).isEqualTo(rec.substring(28, 48).stripTrailing());
            assertThat(u.get("user_type")).isEqualTo(rec.substring(56, 57));
            String hash = (String) u.get("password_hash");
            assertThat(hash).startsWith("$2a$12$").hasSize(60).isNotEqualTo(rec.substring(48, 56).strip());
            assertThat(bcrypt.matches(rec.substring(48, 56).stripTrailing(), hash)).isTrue();
        }
        assertThat(count("app_user")).isEqualTo(10);
        assertThat(count("user_account")).isEqualTo(50);
        assertThat(jdbc.queryForList("SELECT user_id FROM user_account WHERE account_id IN (1, 6, 46) ORDER BY account_id", String.class))
            .containsExactly("USER0001", "USER0001", "USER0001");
        assertThat(jdbc.queryForObject("SELECT user_id FROM user_account WHERE account_id = 5", String.class)).isEqualTo("USER0005");
    }
}
