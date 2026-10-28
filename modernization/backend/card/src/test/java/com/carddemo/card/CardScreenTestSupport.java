package com.carddemo.card;

import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Shared setup of the card screen tests: the base sample data and a signed-on ADMIN001 client. */
abstract class CardScreenTestSupport {

    /** carddata.txt line 1: account 50, Aniya Von, 2023-03-09, Y, CVV 747. */
    static final String CARD1 = "0500024453765740";
    /** carddata.txt line 2: account 27, Ward Jones, 2025-07-13, Y. */
    static final String CARD2 = "0683586198171516";
    static final String ACCT1 = "00000000050";
    static final String ACCT2 = "00000000027";

    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;
    @Autowired
    JdbcTemplate jdbc;

    ScreenClient admin;

    @BeforeEach
    void loadAndSignOn() {
        sample.load();
        admin = signedOn("ADMIN001");
    }

    ScreenClient signedOn(String userId) {
        ScreenClient c = new ScreenClient(mvc);
        c.signOn(userId);
        return c;
    }

    String ownerOf(long account) {
        return jdbc.queryForObject("SELECT user_id FROM user_account WHERE account_id = ?", String.class, account);
    }

    /** A regular user that is not linked to {@code account}. */
    String nonOwnerOf(long account) {
        return jdbc.queryForObject("SELECT MIN(user_id) FROM user_account WHERE user_id <> ?", String.class, ownerOf(account));
    }

    static String masked(String card) {
        return "************" + card.substring(12);
    }

    /** The masked card numbers of the shown rows (empty rows give ""). */
    static List<String> cards(JsonNode r) {
        List<String> out = new ArrayList<>();
        r.at("/fields/rows").forEach(row -> out.add(row.get("cardNum").asText()));
        return out;
    }

    static List<String> maskedAll(List<String> fullCards) {
        List<String> out = new ArrayList<>();
        for (String c : fullCards) {
            out.add(c.isEmpty() ? "" : masked(c));
        }
        while (out.size() < 7) {
            out.add("");
        }
        return out;
    }

    /** All card numbers in key order (full), optionally only those of {@code user}'s linked accounts. */
    List<String> allCards(String user) {
        if (user == null) {
            return jdbc.queryForList("SELECT card_num FROM card ORDER BY card_num", String.class);
        }
        return jdbc.queryForList("SELECT c.card_num FROM card c JOIN user_account u ON u.account_id = c.account_id "
            + "WHERE u.user_id = ? ORDER BY c.card_num", String.class, user);
    }

    Map<String, Object> cardRow(String card) {
        Map<String, Object> row = new HashMap<>(jdbc.queryForMap("SELECT * FROM card WHERE card_num = ?", card));
        row.remove("row_version");
        return row;
    }

    String column(String card, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM card WHERE card_num = ?", String.class, card);
    }

    LocalDate expiry(String card) {
        return jdbc.queryForObject("SELECT expiration_date FROM card WHERE card_num = ?", LocalDate.class, card);
    }

    void insertCard(String card, long account) {
        jdbc.update("INSERT INTO card (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status) "
            + "VALUES (?, ?, '123', 'TEST CARD', '2026-01-15', 'Y')", card, account);
    }

    /** Select codes for rows 1-7 with {@code code} on 1-based {@code row}. */
    static List<String> sel(Object... rowAndCode) {
        List<String> s = new ArrayList<>(List.of("", "", "", "", "", "", ""));
        for (int i = 0; i < rowAndCode.length; i += 2) {
            s.set((Integer) rowAndCode[i] - 1, (String) rowAndCode[i + 1]);
        }
        return s;
    }
}
