package com.carddemo.app;

import static com.carddemo.app.SecurityMatrixTest.Access.ACCOUNT_SCOPED;
import static com.carddemo.app.SecurityMatrixTest.Access.ADMIN_ONLY;
import static com.carddemo.app.SecurityMatrixTest.Access.AUTHENTICATED;
import static com.carddemo.app.SecurityMatrixTest.Access.PUBLIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.carddemo.common.msg.CommonMessages;
import com.carddemo.migration.Layouts;
import com.carddemo.migration.SampleDataLoader;
import com.carddemo.test.Covers;
import com.carddemo.test.RepoPaths;
import com.carddemo.test.ScreenClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The security matrix (P-SEC, D-AUTH, D-USER-ACCT, D-REPORT, D-FIX-SEC, A-1, A-2). The owed set is every
 * operation in the live OpenAPI document; each one must have exactly one line in {@link #CLASSIFICATION},
 * and is called as anonymous, USER0001, and ADMIN001 with the outcome its category requires. Every
 * response body is scanned for sample passwords, BCrypt hashes, full sample card numbers, and SSNs.
 * Counts and per-operation outcomes go to {@code target/security-matrix.txt}.
 */
class SecurityMatrixTest extends SecurityTestBase {

    /** What an operation requires of the caller. */
    enum Access {
        /** Anyone, signed on or not (sign-on screen). */
        PUBLIC,
        /** Any signed-on user; anonymous gets 401. */
        AUTHENTICATED,
        /** ADMIN only: anonymous 401, USER 403 with the COMEN01C admin-only answer (C-ROLE). */
        ADMIN_ONLY,
        /**
         * Signed-on; a USER reaches only linked accounts (or own report runs), and an unlinked key is answered
         * exactly like a key that does not exist (A-2). ADMIN reaches every key (A-1).
         */
        ACCOUNT_SCOPED
    }

    enum Role { ANONYMOUS, USER, ADMIN }

    static final String USER = "USER0001";
    static final String OTHER_USER = "USER0002";
    static final String ADMIN = "ADMIN001";

    /** The keys a request is built from: a linked, an unlinked, or a nonexistent account (and its card, transaction, run). */
    record Target(String name, String accountId, String cardNum, String tranId, String runId) {
    }

    /** An extra check of a list response for one role; returns a failure text or null. */
    interface ListCheck {
        String check(Role role, JsonNode body, Fixture fx);
    }

    /** One classified operation: its category, how to build the request body from a target, and an optional list check. */
    record Rule(Access access, Function<Target, Map<String, Object>> body, ListCheck listCheck) {
    }

    // ---- Classification: one line per documented operation --------------------------------------------------
    // A new endpoint fails the test until it gets a line here. Screen POST bodies are one ENTER turn that only
    // reads (no confirm, no PF5), so the matrix never changes data.

    static final Map<String, Rule> CLASSIFICATION = table(
        // IAM: sign-on, session, menus, user maintenance
        op("GET /api/screens/COSGN00C", PUBLIC),
        op("POST /api/screens/COSGN00C", PUBLIC, t -> screen("userId", "", "password", "")),
        op("GET /api/session", AUTHENTICATED),
        op("GET /api/screens/COMEN01C", AUTHENTICATED),
        op("POST /api/screens/COMEN01C", AUTHENTICATED, t -> screen("option", "")),
        op("GET /api/screens/COADM01C", ADMIN_ONLY),
        op("POST /api/screens/COADM01C", ADMIN_ONLY, t -> screen("option", "")),
        op("GET /api/screens/COUSR00C", ADMIN_ONLY),
        op("POST /api/screens/COUSR00C", ADMIN_ONLY, t -> screen("searchUserId", "")),
        op("GET /api/screens/COUSR01C", ADMIN_ONLY),
        op("POST /api/screens/COUSR01C", ADMIN_ONLY, t -> screen("firstName", "", "lastName", "", "userId", "",
            "password", "", "userType", "", "accounts", "")),
        op("GET /api/screens/COUSR02C", ADMIN_ONLY),
        op("POST /api/screens/COUSR02C", ADMIN_ONLY, t -> screen("userId", OTHER_USER)),
        op("GET /api/screens/COUSR03C", ADMIN_ONLY),
        op("POST /api/screens/COUSR03C", ADMIN_ONLY, t -> screen("userId", OTHER_USER)),
        op("GET /api/screens/COTRTLIC", ADMIN_ONLY),
        op("POST /api/screens/COTRTLIC", ADMIN_ONLY, t -> screen("typeFilter", "", "descFilter", "")),
        op("GET /api/screens/COTRTUPC", ADMIN_ONLY),
        op("POST /api/screens/COTRTUPC", ADMIN_ONLY, t -> screen("typeCode", "")),
        // Account
        op("GET /api/screens/COACTVWC", AUTHENTICATED),
        op("POST /api/screens/COACTVWC", ACCOUNT_SCOPED, t -> screen("accountId", t.accountId())),
        op("GET /api/screens/COACTUPC", AUTHENTICATED),
        op("POST /api/screens/COACTUPC", ACCOUNT_SCOPED, t -> screen("fields", Map.of("accountId", t.accountId()))),
        // Card
        op("GET /api/screens/COCRDLIC", AUTHENTICATED, SecurityMatrixTest::cardRowsInScope),
        op("POST /api/screens/COCRDLIC", ACCOUNT_SCOPED, t -> screen("accountFilter", t.accountId(), "cardFilter", "",
            "sel", Collections.nCopies(7, "")), SecurityMatrixTest::cardRowsInScope),
        op("GET /api/screens/COCRDSLC", AUTHENTICATED),
        op("POST /api/screens/COCRDSLC", ACCOUNT_SCOPED, t -> screen("accountId", t.accountId(), "cardNum", t.cardNum())),
        op("GET /api/screens/COCRDUPC", AUTHENTICATED),
        op("POST /api/screens/COCRDUPC", ACCOUNT_SCOPED, t -> screen("accountId", t.accountId(), "cardNum", t.cardNum())),
        // Transaction
        op("GET /api/screens/COTRN00C", AUTHENTICATED, SecurityMatrixTest::transactionRowsInScope),
        op("POST /api/screens/COTRN00C", ACCOUNT_SCOPED, t -> screen("searchTranId", t.tranId()),
            SecurityMatrixTest::transactionRowsInScope),
        op("GET /api/screens/COTRN01C", AUTHENTICATED),
        op("POST /api/screens/COTRN01C", ACCOUNT_SCOPED, t -> screen("tranIdIn", t.tranId())),
        op("GET /api/screens/COTRN02C", AUTHENTICATED),
        op("POST /api/screens/COTRN02C", ACCOUNT_SCOPED, t -> screen("accountId", t.accountId(), "cardNum", "", "typeCd", "",
            "catCd", "", "source", "", "description", "", "amount", "", "origDate", "", "procDate", "", "merchantId", "",
            "merchantName", "", "merchantCity", "", "merchantZip", "", "confirm", "")),
        // Bill payment
        op("GET /api/screens/COBIL00C", AUTHENTICATED),
        op("POST /api/screens/COBIL00C", ACCOUNT_SCOPED, t -> screen("accountId", t.accountId(), "confirm", "",
            "shownBalance", "")),
        // Reporting (D-REPORT: a USER sees only own runs)
        op("GET /api/screens/CORPT00C", AUTHENTICATED),
        op("POST /api/screens/CORPT00C", AUTHENTICATED, t -> screen("reportType", "", "startMonth", "", "startDay", "",
            "startYear", "", "endMonth", "", "endDay", "", "endYear", "", "confirm", "")),
        op("GET /api/reports", AUTHENTICATED, SecurityMatrixTest::ownRunsOnly),
        op("GET /api/reports/{runId}", ACCOUNT_SCOPED),
        op("GET /api/reports/{runId}/TRANREPT.txt", ACCOUNT_SCOPED),
        // MQ inquiry REST equivalents (D-MQ)
        op("GET /api/inquiry/date", AUTHENTICATED),
        op("GET /api/inquiry/account/{id}", ACCOUNT_SCOPED));

    /** Endpoints outside the OpenAPI document that must stay public and leak-free. */
    static final List<String> INFRASTRUCTURE = List.of("GET /actuator/health", "GET /v3/api-docs");

    static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");
    static final Pattern BCRYPT = Pattern.compile("\\$2[aby]\\$");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    final ObjectMapper json = new ObjectMapper();

    /** Sample data facts the checks need. */
    record Fixture(Target linked, Target unlinked, Target missing, Set<String> userAccounts, Set<String> userTranIds,
                   Set<String> passwords, Set<String> cardNumbers, Set<String> ssns) {
    }

    record Outcome(String operation, Access access, Map<Role, String> results, boolean exercised) {
    }

    final List<String> failures = new ArrayList<>();
    int responsesScanned;

    @Test
    @Covers({"P-SEC", "D-AUTH", "D-USER-ACCT", "D-REPORT", "D-FIX-SEC", "A-1", "A-2", "C-ROLE", "C-SCOPE", "C-MASK"})
    void every_documented_operation_enforces_its_access_rule_and_leaks_nothing() throws Exception {
        sample.load();
        new SampleDataLoader(jdbc).loadFile(Layouts.TRANSACT, RepoPaths.asciiData().resolve("dailytran.txt"));
        Fixture fx = fixture();

        Set<String> owed = documentedOperations();
        checkHandlersAreDocumented(owed);
        Set<String> unclassified = new TreeSet<>(owed);
        unclassified.removeAll(CLASSIFICATION.keySet());
        for (String op : unclassified) {
            failures.add("UNCLASSIFIED: " + op + " is in /v3/api-docs but has no line in SecurityMatrixTest.CLASSIFICATION."
                + " Add one, for example: op(\"" + op + "\", AUTHENTICATED) or op(\"" + op
                + "\", ACCOUNT_SCOPED, t -> screen(\"accountId\", t.accountId())).");
        }
        Set<String> stale = new TreeSet<>(CLASSIFICATION.keySet());
        stale.removeAll(owed);
        for (String op : stale) {
            failures.add("STALE: " + op + " is classified in SecurityMatrixTest.CLASSIFICATION but is no longer in /v3/api-docs;"
                + " remove or rename its line.");
        }

        List<Outcome> outcomes = new ArrayList<>();
        for (String op : owed) {
            Rule rule = CLASSIFICATION.get(op);
            if (rule != null) {
                outcomes.add(exercise(op, rule, fx));
            }
        }
        for (String op : INFRASTRUCTURE) {
            MockHttpServletResponse r = call(new ScreenClient(mvc), op, null, fx.linked());
            scan(op, Role.ANONYMOUS, fx.linked(), r, fx);
            if (r.getStatus() != 200) {
                failures.add(op + " as ANONYMOUS: expected 200, got " + r.getStatus());
            }
        }

        long covered = outcomes.stream().filter(Outcome::exercised).count();
        String report = report(owed, outcomes, covered, unclassified, stale);
        Path out = Path.of("target", "security-matrix.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);
        System.out.println("security-matrix: operations covered " + covered + "/" + owed.size() + ", responses scanned "
            + responsesScanned + ", failures " + failures.size());

        assertThat(failures).as("security matrix failures (details in target/security-matrix.txt)").isEmpty();
        assertThat(covered).isEqualTo(owed.size());
    }

    // ---- Owed set --------------------------------------------------------------------------------------------

    Set<String> documentedOperations() throws Exception {
        MockHttpServletResponse res = mvc.perform(MockMvcRequestBuilders.get("/v3/api-docs")).andReturn().getResponse();
        assertThat(res.getStatus()).as("/v3/api-docs").isEqualTo(200);
        JsonNode paths = json.readTree(res.getContentAsString()).path("paths");
        Set<String> ops = new TreeSet<>();
        paths.fields().forEachRemaining(p -> p.getValue().fieldNames().forEachRemaining(m -> {
            if (HTTP_METHODS.contains(m)) {
                ops.add(m.toUpperCase() + " " + p.getKey());
            }
        }));
        assertThat(ops).as("operations in /v3/api-docs").isNotEmpty();
        return ops;
    }

    /** A CardDemo handler missing from the OpenAPI document (for example {@code @Hidden}) would escape the matrix. */
    void checkHandlersAreDocumented(Set<String> documented) {
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            if (!e.getValue().getBeanType().getName().startsWith("com.carddemo.")) {
                continue;
            }
            RequestMappingInfo info = e.getKey();
            Set<String> patterns = info.getPathPatternsCondition() != null
                ? info.getPathPatternsCondition().getPatternValues() : info.getPatternValues();
            var methods = info.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                if (methods.isEmpty()) {
                    failures.add("UNDOCUMENTED: " + pattern + " (" + e.getValue() + ") maps every HTTP method; declare the method");
                }
                for (var m : methods) {
                    String op = m.name() + " " + pattern;
                    if (!documented.contains(op)) {
                        failures.add("UNDOCUMENTED: " + op + " (" + e.getValue() + ") is served but missing from /v3/api-docs");
                    }
                }
            }
        }
    }

    // ---- Exercising one operation ----------------------------------------------------------------------------

    Outcome exercise(String op, Rule rule, Fixture fx) {
        Map<Role, String> results = new EnumMap<>(Role.class);
        boolean exercised = true;
        for (Role role : Role.values()) {
            int before = failures.size();
            try {
                results.put(role, exercise(op, rule, role, fx));
            } catch (RuntimeException | AssertionError e) {
                exercised = false;
                failures.add(op + " as " + role + ": " + e);
                results.put(role, "ERROR");
            }
            if (failures.size() > before && !"ERROR".equals(results.get(role))) {
                results.put(role, results.get(role) + " FAIL");
            }
        }
        return new Outcome(op, rule.access(), results, exercised);
    }

    String exercise(String op, Rule rule, Role role, Fixture fx) {
        ScreenClient c = client(role);
        boolean rejected = (role == Role.ANONYMOUS && rule.access() != PUBLIC) || (role == Role.USER && rule.access() == ADMIN_ONLY);
        if (rejected) {
            MockHttpServletResponse r = call(c, op, rule, fx.linked());
            scan(op, role, fx.linked(), r, fx);
            if (role == Role.ANONYMOUS) {
                expect(op, role, r.getStatus() == 401, "expected 401, got " + r.getStatus());
                return "401";
            }
            expect(op, role, r.getStatus() == 403, "expected 403, got " + r.getStatus());
            if (op.contains("/api/screens/") && r.getStatus() == 403) {
                JsonNode b = c.body(r);
                expect(op, role, "COMEN01C".equals(b.path("navigate").asText())
                    && CommonMessages.ADMIN_ONLY.equals(b.path("message").asText()),
                    "expected the COMEN01C admin-only answer, got " + content(r));
            }
            return "403";
        }
        if (rule.access() != ACCOUNT_SCOPED || role == Role.ANONYMOUS) {
            MockHttpServletResponse r = prepareAndCall(c, op, rule, fx.linked());
            scan(op, role, fx.linked(), r, fx);
            expect(op, role, r.getStatus() / 100 == 2, "expected 2xx, got " + r.getStatus() + ": " + content(r));
            if (rule.listCheck() != null && r.getStatus() == 200) {
                String problem = rule.listCheck().check(role, c.body(r), fx);
                if (problem != null) {
                    failures.add(op + " as " + role + ": " + problem);
                }
            }
            return String.valueOf(r.getStatus());
        }
        MockHttpServletResponse linked = prepareAndCall(c, op, rule, fx.linked());
        MockHttpServletResponse unlinked = prepareAndCall(c, op, rule, fx.unlinked());
        MockHttpServletResponse missing = prepareAndCall(c, op, rule, fx.missing());
        scan(op, role, fx.linked(), linked, fx);
        scan(op, role, fx.unlinked(), unlinked, fx);
        scan(op, role, fx.missing(), missing, fx);
        String nl = normalized(linked, fx.linked());
        String nu = normalized(unlinked, fx.unlinked());
        String nm = normalized(missing, fx.missing());
        expect(op, role, linked.getStatus() / 100 == 2, "linked: expected 2xx, got " + linked.getStatus() + ": " + content(linked));
        expect(op, role, !nl.equals(nm), "linked account answered like a nonexistent one: " + nl);
        if (rule.listCheck() != null && linked.getStatus() == 200) {
            String problem = rule.listCheck().check(role, c.body(linked), fx);
            if (problem != null) {
                failures.add(op + " as " + role + " (linked): " + problem);
            }
        }
        if (role == Role.USER) {
            expect(op, role, nu.equals(nm), "unlinked account must be answered exactly like a nonexistent one (A-2)."
                + "\n  unlinked: " + nu + "\n  missing:  " + nm);
            return "linked " + linked.getStatus() + ", unlinked=missing " + unlinked.getStatus();
        }
        expect(op, role, !nu.equals(nm) && unlinked.getStatus() / 100 == 2,
            "ADMIN is unrestricted (A-1) but another user's account was answered like a nonexistent one: " + nu);
        return "linked " + linked.getStatus() + ", unlinked " + unlinked.getStatus();
    }

    ScreenClient client(Role role) {
        ScreenClient c = new ScreenClient(mvc);
        if (role != Role.ANONYMOUS) {
            String id = role == Role.USER ? USER : ADMIN;
            JsonNode r = c.signOn(id);
            assertThat(r.path("navigate").asText()).as("sign-on of " + id).isEqualTo(role == Role.USER ? "COMEN01C" : "COADM01C");
        }
        return c;
    }

    /** A screen turn follows the screen's first entry, as in the web app. */
    MockHttpServletResponse prepareAndCall(ScreenClient c, String op, Rule rule, Target t) {
        String[] parts = op.split(" ", 2);
        if (parts[0].equals("POST") && parts[1].startsWith("/api/screens/")) {
            c.showRaw(parts[1].substring("/api/screens/".length()));
        }
        return call(c, op, rule, t);
    }

    MockHttpServletResponse call(ScreenClient c, String op, Rule rule, Target t) {
        String[] parts = op.split(" ", 2);
        String path = parts[1].replace("{runId}", t.runId()).replace("{id}", t.accountId());
        MockHttpServletRequestBuilder req = switch (parts[0]) {
            case "GET" -> MockMvcRequestBuilders.get(path);
            case "POST" -> MockMvcRequestBuilders.post(path);
            case "PUT" -> MockMvcRequestBuilders.put(path);
            case "DELETE" -> MockMvcRequestBuilders.delete(path);
            case "PATCH" -> MockMvcRequestBuilders.patch(path);
            default -> throw new IllegalArgumentException("no request builder for " + op);
        };
        if (!parts[0].equals("GET")) {
            req = req.with(csrf());
        }
        if (rule != null && rule.body() != null) {
            try {
                req = req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(rule.body().apply(t)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        } else if (!parts[0].equals("GET")) {
            req = req.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        try {
            return mvc.perform(req.session(c.session())).andReturn().getResponse();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    void expect(String op, Role role, boolean ok, String message) {
        if (!ok) {
            failures.add(op + " as " + role + ": " + message);
        }
    }

    /** Status and body with the target's own keys replaced, so answers for different keys can be compared. */
    static String normalized(MockHttpServletResponse r, Target t) {
        String b = content(r);
        List<String> keys = new ArrayList<>(List.of(t.accountId(), t.cardNum(), masked(t.cardNum()), t.tranId(), t.runId()));
        keys.sort((a, c) -> c.length() - a.length());
        for (String k : keys) {
            b = b.replace(k, "<" + (k.equals(t.runId()) ? "run" : "key") + ">");
        }
        return r.getStatus() + " " + b;
    }

    static String content(MockHttpServletResponse r) {
        try {
            return r.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String masked(String card) {
        return "*".repeat(card.length() - 4) + card.substring(card.length() - 4);
    }

    // ---- Leak scan -------------------------------------------------------------------------------------------

    void scan(String op, Role role, Target t, MockHttpServletResponse r, Fixture fx) {
        responsesScanned++;
        String b = content(r);
        String where = op + " as " + role + " (" + t.name() + ")";
        for (String p : fx.passwords()) {
            if (b.contains(p)) {
                failures.add("LEAK " + where + ": sample password \"" + p + "\" in the response");
            }
        }
        if (BCRYPT.matcher(b).find()) {
            failures.add("LEAK " + where + ": BCrypt hash prefix in the response");
        }
        Matcher digits = Pattern.compile("(?<!\\d)\\d{16}(?!\\d)").matcher(b);
        while (digits.find()) {
            if (fx.cardNumbers().contains(digits.group())) {
                failures.add("LEAK " + where + ": unmasked card number " + masked(digits.group()));
            }
        }
        for (String ssn : fx.ssns()) {
            String dashed = ssn.substring(0, 3) + "-" + ssn.substring(3, 5) + "-" + ssn.substring(5);
            if (Pattern.compile("(?<!\\d)(" + ssn + "|" + dashed + ")(?!\\d)").matcher(b).find()) {
                failures.add("LEAK " + where + ": SSN ending " + ssn.substring(5) + " in the response");
            }
        }
    }

    // ---- List checks (rows a USER sees are in scope) -----------------------------------------------------------

    static String cardRowsInScope(Role role, JsonNode body, Fixture fx) {
        if (role != Role.USER) {
            return null;
        }
        List<String> outside = new ArrayList<>();
        int shown = 0;
        for (JsonNode row : body.at("/fields/rows")) {
            String acct = row.path("accountId").asText();
            if (!acct.isBlank()) {
                shown++;
                if (!fx.userAccounts().contains(acct)) {
                    outside.add(acct);
                }
            }
        }
        if (shown == 0) {
            return "card list shows no rows for a user with linked accounts";
        }
        return outside.isEmpty() ? null : "card list shows unlinked accounts " + outside;
    }

    static String transactionRowsInScope(Role role, JsonNode body, Fixture fx) {
        if (role != Role.USER) {
            return null;
        }
        List<String> outside = new ArrayList<>();
        int shown = 0;
        for (JsonNode row : body.at("/fields/rows")) {
            String id = row.path("tranId").asText();
            if (!id.isBlank()) {
                shown++;
                if (!fx.userTranIds().contains(id)) {
                    outside.add(id);
                }
            }
        }
        if (shown == 0) {
            return "transaction list shows no rows for a user with linked transactions";
        }
        return outside.isEmpty() ? null : "transaction list shows transactions of unlinked accounts " + outside;
    }

    static String ownRunsOnly(Role role, JsonNode body, Fixture fx) {
        Set<String> ids = new HashSet<>();
        body.forEach(r -> ids.add(r.path("runId").asText()));
        boolean own = ids.contains(fx.linked().runId());
        boolean other = ids.contains(fx.unlinked().runId());
        if (role == Role.USER && (!own || other)) {
            return "a USER must see own runs only (D-REPORT); runs shown: " + ids;
        }
        if (role == Role.ADMIN && (!own || !other)) {
            return "an ADMIN sees every run; runs shown: " + ids;
        }
        return null;
    }

    // ---- Fixture -----------------------------------------------------------------------------------------------

    Fixture fixture() throws Exception {
        long userAcct = jdbc.queryForObject("""
            SELECT MIN(ua.account_id) FROM user_account ua JOIN card c ON c.account_id = ua.account_id
            JOIN card_xref x ON x.card_num = c.card_num
            WHERE ua.user_id = ? AND EXISTS (SELECT 1 FROM card_transaction t WHERE t.card_num = c.card_num)
            """, Long.class, USER);
        Set<String> userTranIds = new TreeSet<>(jdbc.queryForList("""
            SELECT t.tran_id FROM card_transaction t JOIN card_xref x ON x.card_num = t.card_num
            JOIN user_account ua ON ua.account_id = x.account_id WHERE ua.user_id = ?
            """, String.class, USER));
        String userCard = jdbc.queryForObject("SELECT MIN(card_num) FROM card WHERE account_id = ?", String.class, userAcct);
        String userTran = jdbc.queryForObject("SELECT MIN(tran_id) FROM card_transaction WHERE card_num = ?", String.class, userCard);
        String maxUserTran = ((TreeSet<String>) userTranIds).last();
        // Out of scope and above every in-scope ID, so a list search from it finds nothing for the user, as for a missing ID.
        String otherTran = jdbc.queryForObject("""
            SELECT MIN(t.tran_id) FROM card_transaction t JOIN card_xref x ON x.card_num = t.card_num
            WHERE t.tran_id > ? AND x.account_id NOT IN (SELECT account_id FROM user_account WHERE user_id = ?)
            """, String.class, maxUserTran, USER);
        assertThat(otherTran).as("an out-of-scope transaction above USER0001's highest").isNotNull();
        long otherAcct = jdbc.queryForObject("""
            SELECT MIN(ua.account_id) FROM user_account ua JOIN card c ON c.account_id = ua.account_id WHERE ua.user_id = ?
            """, Long.class, OTHER_USER);
        String otherCard = jdbc.queryForObject("SELECT MIN(card_num) FROM card WHERE account_id = ?", String.class, otherAcct);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE account_id = 99999999999", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card WHERE card_num = '9999999999999999'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_transaction WHERE tran_id >= '9999999999999999'", Integer.class)).isZero();

        Target linked = new Target("linked", String.format("%011d", userAcct), userCard, userTran, "MATRIX-RUN-USER0001");
        Target unlinked = new Target("unlinked", String.format("%011d", otherAcct), otherCard, otherTran, "MATRIX-RUN-USER0002");
        Target missing = new Target("missing", "99999999999", "9999999999999999", "9999999999999999", "MATRIX-RUN-NONE");
        jdbc.update("DELETE FROM report_run");
        for (String[] run : new String[][] {{linked.runId(), USER}, {unlinked.runId(), OTHER_USER}}) {
            jdbc.update("""
                INSERT INTO report_run (dag_run_id, report_type, start_date, end_date, user_id, requested_at, state)
                VALUES (?, 'Monthly', '2022-06-01', '2022-06-30', ?, '2022-06-15T09:00:00', 'success')
                """, run[0], run[1]);
            Path dir = Files.createDirectories(OUTBOX.resolve("reports").resolve(run[0]));
            Files.writeString(dir.resolve("TRANREPT.txt"), "TRANREPT of " + run[0] + "\n");
        }

        Set<String> userAccounts = new HashSet<>();
        for (Long a : jdbc.queryForList("SELECT account_id FROM user_account WHERE user_id = ?", Long.class, USER)) {
            userAccounts.add(String.format("%011d", a));
        }
        Set<String> cards = new HashSet<>(jdbc.queryForList("SELECT card_num FROM card", String.class));
        cards.addAll(jdbc.queryForList("SELECT card_num FROM card_xref", String.class));
        Set<String> ssns = new HashSet<>();
        for (String s : jdbc.queryForList("SELECT ssn FROM customer", String.class)) {
            if (s != null && s.strip().matches("\\d{9}")) {
                ssns.add(s.strip());
            }
        }
        assertThat(cards).hasSizeGreaterThanOrEqualTo(50);
        assertThat(ssns).hasSizeGreaterThanOrEqualTo(50);
        return new Fixture(linked, unlinked, missing, userAccounts, userTranIds, samplePasswords(), cards, ssns);
    }

    /** The demo password (README) and every password in the sample USRSEC file. */
    static Set<String> samplePasswords() throws Exception {
        Set<String> out = new TreeSet<>(Set.of(ScreenClient.DEMO_PASSWORD));
        byte[] data = Files.readAllBytes(RepoPaths.usrsec());
        Charset cp037 = Charset.forName("IBM037");
        for (int off = 0; off + 80 <= data.length; off += 80) {
            String pwd = new String(data, off + 48, 8, cp037).strip();
            if (!pwd.isEmpty()) {
                out.add(pwd);
            }
        }
        return out;
    }

    // ---- Report ------------------------------------------------------------------------------------------------

    String report(Set<String> owed, List<Outcome> outcomes, long covered, Set<String> unclassified, Set<String> stale) {
        StringBuilder sb = new StringBuilder();
        sb.append("Security matrix (SecurityMatrixTest)\n");
        sb.append("Operations owed (in /v3/api-docs): ").append(owed.size()).append('\n');
        sb.append("Operations covered (exercised as ANONYMOUS, USER0001, ADMIN001): ").append(covered).append('\n');
        Map<Access, Integer> perAccess = new TreeMap<>();
        outcomes.forEach(o -> perAccess.merge(o.access(), 1, Integer::sum));
        sb.append("By category: ").append(perAccess).append('\n');
        sb.append("Unclassified: ").append(unclassified).append('\n');
        sb.append("Stale classifications: ").append(stale).append('\n');
        sb.append("Responses scanned for secrets: ").append(responsesScanned).append('\n');
        sb.append("Failures: ").append(failures.size()).append("\n\n");
        for (Outcome o : outcomes) {
            sb.append(String.format("%-45s %-15s", o.operation(), o.access()));
            o.results().forEach((role, res) -> sb.append(" | ").append(role).append(": ").append(res));
            sb.append('\n');
        }
        if (!failures.isEmpty()) {
            sb.append("\nFailures:\n");
            failures.forEach(f -> sb.append("- ").append(f).append('\n'));
        }
        return sb.toString();
    }

    // ---- Table helpers -----------------------------------------------------------------------------------------

    @SafeVarargs
    static Map<String, Rule> table(Map.Entry<String, Rule>... entries) {
        Map<String, Rule> m = new LinkedHashMap<>();
        for (Map.Entry<String, Rule> e : entries) {
            if (m.put(e.getKey(), e.getValue()) != null) {
                throw new IllegalStateException("operation classified twice: " + e.getKey());
            }
        }
        return Collections.unmodifiableMap(m);
    }

    static Map.Entry<String, Rule> op(String operation, Access access) {
        return Map.entry(operation, new Rule(access, null, null));
    }

    static Map.Entry<String, Rule> op(String operation, Access access, Function<Target, Map<String, Object>> body) {
        return Map.entry(operation, new Rule(access, body, null));
    }

    static Map.Entry<String, Rule> op(String operation, Access access, Function<Target, Map<String, Object>> body,
                                      ListCheck listCheck) {
        return Map.entry(operation, new Rule(access, body, listCheck));
    }

    static Map.Entry<String, Rule> op(String operation, Access access, ListCheck listCheck) {
        return Map.entry(operation, new Rule(access, null, listCheck));
    }

    /** An ENTER turn with the given field name/value pairs. */
    static Map<String, Object> screen(Object... nameValue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", "ENTER");
        for (int i = 0; i < nameValue.length; i += 2) {
            m.put((String) nameValue[i], nameValue[i + 1]);
        }
        return m;
    }
}
