package com.carddemo.billpayservice.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.test.Covers;
import com.carddemo.test.RepoPaths;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The security matrix of the standalone bill-payment service (P-SEC, D-AUTH, D-USER-ACCT, A-1, A-2), in the
 * style of the API's SecurityMatrixTest but over HTTP against the running service. The owed set is every
 * operation in the service's own {@code /v3/api-docs}; each needs exactly one line in {@link #CLASSIFICATION}
 * and is called as anonymous, USER0001, and ADMIN001. Every response body is scanned for sample passwords,
 * BCrypt hashes, full card numbers, and SSNs. The report goes to {@code target/billpay-security-matrix.txt}.
 */
class StandaloneSecurityMatrixIT {

    enum Access { PUBLIC, AUTHENTICATED, ADMIN_ONLY, ACCOUNT_SCOPED }

    enum Role { ANONYMOUS, USER, ADMIN }

    static final String USER = "USER0001";
    static final String OTHER_USER = "USER0002";
    static final String ADMIN = "ADMIN001";
    static final String ADMIN_ONLY_MESSAGE = "No access - Admin Only option... ";

    record Target(String name, String accountId) {
    }

    record Rule(Access access, Function<Target, Map<String, Object>> body) {
    }

    // Screen POST bodies are one ENTER turn that only reads (no confirm), so the matrix changes no data.
    static final Map<String, Rule> CLASSIFICATION = table(
        op("GET /api/screens/COSGN00C", Access.PUBLIC, null),
        op("POST /api/screens/COSGN00C", Access.PUBLIC, t -> screen("userId", "", "password", "")),
        op("GET /api/session", Access.AUTHENTICATED, null),
        op("GET /api/screens/COMEN01C", Access.AUTHENTICATED, null),
        op("POST /api/screens/COMEN01C", Access.AUTHENTICATED, t -> screen("option", "")),
        op("GET /api/screens/COADM01C", Access.ADMIN_ONLY, null),
        op("POST /api/screens/COADM01C", Access.ADMIN_ONLY, t -> screen("option", "")),
        // User maintenance comes with the IAM module; admin-only here as in the API.
        op("GET /api/screens/COUSR00C", Access.ADMIN_ONLY, null),
        op("POST /api/screens/COUSR00C", Access.ADMIN_ONLY, t -> screen("searchUserId", "")),
        op("GET /api/screens/COUSR01C", Access.ADMIN_ONLY, null),
        op("POST /api/screens/COUSR01C", Access.ADMIN_ONLY, t -> screen("firstName", "", "lastName", "", "userId", "",
            "password", "", "userType", "", "accounts", "")),
        op("GET /api/screens/COUSR02C", Access.ADMIN_ONLY, null),
        op("POST /api/screens/COUSR02C", Access.ADMIN_ONLY, t -> screen("userId", OTHER_USER)),
        op("GET /api/screens/COUSR03C", Access.ADMIN_ONLY, null),
        op("POST /api/screens/COUSR03C", Access.ADMIN_ONLY, t -> screen("userId", OTHER_USER)),
        op("GET /api/screens/COBIL00C", Access.AUTHENTICATED, null),
        op("POST /api/screens/COBIL00C", Access.ACCOUNT_SCOPED, t -> screen("accountId", t.accountId(), "confirm", "",
            "shownBalance", "")));

    static final List<String> INFRASTRUCTURE = List.of("GET /actuator/health", "GET /v3/api-docs");
    static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");
    static final Pattern BCRYPT = Pattern.compile("\\$2[aby]\\$");

    record Fixture(Target linked, Target unlinked, Target missing, Set<String> passwords, Set<String> cards, Set<String> ssns) {
    }

    final ObjectMapper json = new ObjectMapper();
    final List<String> failures = new ArrayList<>();
    int responsesScanned;

    @Test
    @Covers({"P-SEC", "P-MODULES", "D-AUTH", "D-USER-ACCT", "A-1", "A-2", "C-ROLE", "C-SCOPE"})
    void every_operation_of_the_standalone_service_enforces_its_access_rule_and_leaks_nothing() throws Exception {
        JdbcTemplate jdbc = StandaloneDatabase.loaded();
        Fixture fx = fixture(jdbc);

        ServiceClient anonymous = new ServiceClient(ServiceClient.baseUrl());
        ServiceClient.Response docs = anonymous.get("/v3/api-docs");
        assertThat(docs.status()).as("/v3/api-docs").isEqualTo(200);
        Set<String> owed = new TreeSet<>();
        json.readTree(docs.body()).path("paths").fields().forEachRemaining(p -> p.getValue().fieldNames().forEachRemaining(m -> {
            if (HTTP_METHODS.contains(m)) {
                owed.add(m.toUpperCase() + " " + p.getKey());
            }
        }));
        assertThat(owed).isNotEmpty();
        Set<String> unclassified = new TreeSet<>(owed);
        unclassified.removeAll(CLASSIFICATION.keySet());
        unclassified.forEach(op -> failures.add("UNCLASSIFIED: " + op + " is in /v3/api-docs but has no line in CLASSIFICATION"));
        Set<String> stale = new TreeSet<>(CLASSIFICATION.keySet());
        stale.removeAll(owed);
        stale.forEach(op -> failures.add("STALE: " + op + " is classified but no longer in /v3/api-docs"));

        Map<String, Map<Role, String>> outcomes = new LinkedHashMap<>();
        int covered = 0;
        for (String op : owed) {
            Rule rule = CLASSIFICATION.get(op);
            if (rule == null) {
                continue;
            }
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
            outcomes.put(op, results);
            covered += exercised ? 1 : 0;
        }
        for (String op : INFRASTRUCTURE) {
            ServiceClient.Response r = anonymous.get(op.substring(4));
            scan(op, Role.ANONYMOUS, fx.linked(), r, fx);
            if (r.status() != 200) {
                failures.add(op + " as ANONYMOUS: expected 200, got " + r.status());
            }
        }

        String report = report(owed, outcomes, covered);
        Path out = Path.of("target", "billpay-security-matrix.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);
        System.out.println("billpay security matrix: operations covered " + covered + "/" + owed.size()
            + ", responses scanned " + responsesScanned + ", failures " + failures.size());

        assertThat(failures).as("security matrix failures (details in target/billpay-security-matrix.txt)").isEmpty();
        assertThat(covered).isEqualTo(owed.size());
    }

    String exercise(String op, Rule rule, Role role, Fixture fx) {
        ServiceClient c = client(role);
        boolean rejected = (role == Role.ANONYMOUS && rule.access() != Access.PUBLIC)
            || (role == Role.USER && rule.access() == Access.ADMIN_ONLY);
        if (rejected) {
            ServiceClient.Response r = call(c, op, rule, fx.linked(), false);
            scan(op, role, fx.linked(), r, fx);
            if (role == Role.ANONYMOUS) {
                expect(op, role, r.status() == 401, "expected 401, got " + r.status());
                return "401";
            }
            expect(op, role, r.status() == 403, "expected 403, got " + r.status());
            if (r.status() == 403) {
                JsonNode b = c.body(r);
                expect(op, role, "COMEN01C".equals(b.path("navigate").asText()) && ADMIN_ONLY_MESSAGE.equals(b.path("message").asText()),
                    "expected the COMEN01C admin-only answer, got " + r.body());
            }
            return "403";
        }
        if (rule.access() != Access.ACCOUNT_SCOPED) {
            ServiceClient.Response r = call(c, op, rule, fx.linked(), true);
            scan(op, role, fx.linked(), r, fx);
            expect(op, role, r.status() / 100 == 2, "expected 2xx, got " + r.status() + ": " + r.body());
            return String.valueOf(r.status());
        }
        ServiceClient.Response linked = call(c, op, rule, fx.linked(), true);
        ServiceClient.Response unlinked = call(c, op, rule, fx.unlinked(), true);
        ServiceClient.Response missing = call(c, op, rule, fx.missing(), true);
        scan(op, role, fx.linked(), linked, fx);
        scan(op, role, fx.unlinked(), unlinked, fx);
        scan(op, role, fx.missing(), missing, fx);
        String nl = normalized(linked, fx.linked());
        String nu = normalized(unlinked, fx.unlinked());
        String nm = normalized(missing, fx.missing());
        expect(op, role, linked.status() / 100 == 2, "linked: expected 2xx, got " + linked.status() + ": " + linked.body());
        expect(op, role, !nl.equals(nm), "linked account answered like a nonexistent one: " + nl);
        if (role == Role.USER) {
            expect(op, role, nu.equals(nm), "unlinked account must be answered exactly like a nonexistent one (A-2)."
                + "\n  unlinked: " + nu + "\n  missing:  " + nm);
            return "linked " + linked.status() + ", unlinked=missing " + unlinked.status();
        }
        expect(op, role, !nu.equals(nm) && unlinked.status() / 100 == 2,
            "ADMIN is unrestricted (A-1) but another user's account was answered like a nonexistent one: " + nu);
        return "linked " + linked.status() + ", unlinked " + unlinked.status();
    }

    ServiceClient client(Role role) {
        ServiceClient c = new ServiceClient(ServiceClient.baseUrl());
        if (role != Role.ANONYMOUS) {
            String id = role == Role.USER ? USER : ADMIN;
            JsonNode r = c.signOn(id);
            assertThat(r.path("navigate").asText()).as("sign-on of " + id).isEqualTo(role == Role.USER ? "COMEN01C" : "COADM01C");
        }
        return c;
    }

    /** With firstEntry, a screen POST follows the screen's GET, as in the web app. */
    ServiceClient.Response call(ServiceClient c, String op, Rule rule, Target t, boolean firstEntry) {
        String[] parts = op.split(" ", 2);
        if (firstEntry && parts[0].equals("POST") && parts[1].startsWith("/api/screens/")) {
            c.get(parts[1]);
        }
        return c.send(parts[0], parts[1], rule.body() == null ? null : rule.body().apply(t));
    }

    void expect(String op, Role role, boolean ok, String message) {
        if (!ok) {
            failures.add(op + " as " + role + ": " + message);
        }
    }

    static String normalized(ServiceClient.Response r, Target t) {
        return r.status() + " " + r.body().replace(t.accountId(), "<key>");
    }

    void scan(String op, Role role, Target t, ServiceClient.Response r, Fixture fx) {
        responsesScanned++;
        String b = r.body();
        String where = op + " as " + role + " (" + t.name() + ")";
        for (String p : fx.passwords()) {
            if (b.contains(p)) {
                failures.add("LEAK " + where + ": a sample password in the response");
            }
        }
        if (BCRYPT.matcher(b).find()) {
            failures.add("LEAK " + where + ": BCrypt hash prefix in the response");
        }
        Matcher digits = Pattern.compile("(?<!\\d)\\d{16}(?!\\d)").matcher(b);
        while (digits.find()) {
            if (fx.cards().contains(digits.group())) {
                failures.add("LEAK " + where + ": unmasked card number ending " + digits.group().substring(12));
            }
        }
        for (String ssn : fx.ssns()) {
            String dashed = ssn.substring(0, 3) + "-" + ssn.substring(3, 5) + "-" + ssn.substring(5);
            if (Pattern.compile("(?<!\\d)(" + ssn + "|" + dashed + ")(?!\\d)").matcher(b).find()) {
                failures.add("LEAK " + where + ": SSN ending " + ssn.substring(5) + " in the response");
            }
        }
    }

    Fixture fixture(JdbcTemplate jdbc) throws Exception {
        Target linked = new Target("linked", StandaloneDatabase.account(USER, true, false));
        Long otherAcct = jdbc.queryForObject("""
            SELECT MIN(ua.account_id) FROM user_account ua WHERE ua.user_id = ?
              AND EXISTS (SELECT 1 FROM card_xref x WHERE x.account_id = ua.account_id)
            """, Long.class, OTHER_USER);
        Target unlinked = new Target("unlinked", String.format("%011d", otherAcct));
        Target missing = new Target("missing", "99999999999");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE account_id = 99999999999", Integer.class)).isZero();
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
        return new Fixture(linked, unlinked, missing, samplePasswords(), cards, ssns);
    }

    /** The demo password and every password in the sample USRSEC file. */
    static Set<String> samplePasswords() throws Exception {
        Set<String> out = new TreeSet<>(Set.of(ServiceClient.DEMO_PASSWORD));
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

    String report(Set<String> owed, Map<String, Map<Role, String>> outcomes, int covered) {
        StringBuilder sb = new StringBuilder("Security matrix (billpay-service, StandaloneSecurityMatrixIT)\n");
        sb.append("Service: ").append(ServiceClient.baseUrl()).append('\n');
        sb.append("Operations owed (in /v3/api-docs): ").append(owed.size()).append('\n');
        sb.append("Operations covered (exercised as ANONYMOUS, USER0001, ADMIN001): ").append(covered).append('\n');
        Map<Access, Integer> perAccess = new TreeMap<>();
        outcomes.keySet().forEach(op -> perAccess.merge(CLASSIFICATION.get(op).access(), 1, Integer::sum));
        sb.append("By category: ").append(perAccess).append('\n');
        sb.append("Responses scanned for secrets: ").append(responsesScanned).append('\n');
        sb.append("Failures: ").append(failures.size()).append("\n\n");
        outcomes.forEach((op, results) -> {
            sb.append(String.format("%-32s %-15s", op, CLASSIFICATION.get(op).access()));
            results.forEach((role, res) -> sb.append(" | ").append(role).append(": ").append(res));
            sb.append('\n');
        });
        if (!failures.isEmpty()) {
            sb.append("\nFailures:\n");
            failures.forEach(f -> sb.append("- ").append(f).append('\n'));
        }
        return sb.toString();
    }

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

    static Map.Entry<String, Rule> op(String operation, Access access, Function<Target, Map<String, Object>> body) {
        return Map.entry(operation, new Rule(access, body));
    }

    static Map<String, Object> screen(Object... nameValue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", "ENTER");
        for (int i = 0; i < nameValue.length; i += 2) {
            m.put((String) nameValue[i], nameValue[i + 1]);
        }
        return m;
    }
}
