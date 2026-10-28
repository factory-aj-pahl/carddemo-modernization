package com.carddemo.account.web;

import com.carddemo.account.internal.AccountRecord;
import com.carddemo.account.internal.CustomerRecord;
import com.carddemo.common.format.Pic;
import com.carddemo.common.mask.Masking;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The CACTUPA input fields by name with their BMS lengths, and the renderings of a fetched snapshot
 * (BR-COACTUPC-04). SSN parts are shown as {@code ***}, {@code **}, last 4 and the government id as
 * {@code *} except the last 4 (Q-COACTUPC-28).
 */
final class UpdateForm {

    /** Amount fields: 15 BMS positions, widened to 17 for the 10-integer-digit rendering (Q-COACTUPC-30). */
    static final int AMOUNT = 17;

    static final Map<String, Integer> LENGTHS = lengths(
        "accountId", 11, "active", 1, "openYear", 4, "openMonth", 2, "openDay", 2, "creditLimit", AMOUNT,
        "expYear", 4, "expMonth", 2, "expDay", 2, "cashCreditLimit", AMOUNT, "risYear", 4, "risMonth", 2, "risDay", 2,
        "currentBalance", AMOUNT, "cycleCredit", AMOUNT, "cycleDebit", AMOUNT, "group", 10, "customerId", 9,
        "ssn1", 3, "ssn2", 2, "ssn3", 4, "dobYear", 4, "dobMonth", 2, "dobDay", 2, "fico", 3,
        "firstName", 25, "middleName", 25, "lastName", 25, "addr1", 50, "addr2", 50, "state", 2, "zip", 5,
        "city", 50, "country", 3, "phone1a", 3, "phone1b", 3, "phone1c", 4, "phone2a", 3, "phone2b", 3, "phone2c", 4,
        "govtId", 20, "eft", 10, "primary", 1);

    /** Never editable after a fetch: the key, the customer id, and the country (BR-COACTUPC-18). */
    static final Set<String> PROTECTED = Set.of("accountId", "customerId", "country");

    static final String SSN1_SHOWN = "***";
    static final String SSN2_SHOWN = "**";

    private UpdateForm() {
    }

    static Map<String, String> empty() {
        Map<String, String> f = new LinkedHashMap<>();
        LENGTHS.keySet().forEach(k -> f.put(k, ""));
        return f;
    }

    /** The submitted fields as the map returns them: every field present, cut to its length. */
    static Map<String, String> received(Map<String, String> in) {
        Map<String, String> f = new LinkedHashMap<>();
        LENGTHS.forEach((k, len) -> {
            String v = in == null ? null : in.get(k);
            v = v == null ? "" : v;
            f.put(k, v.length() > len ? v.substring(0, len) : v);
        });
        return f;
    }

    static Map<String, String> of(AccountRecord a, CustomerRecord c) {
        Map<String, String> f = empty();
        f.put("accountId", Pic.accountId(a.accountId()));
        f.put("active", a.activeStatus());
        putDate(f, "open", "openYear", "openMonth", "openDay", a.openDate());
        putDate(f, "exp", "expYear", "expMonth", "expDay", a.expirationDate());
        putDate(f, "ris", "risYear", "risMonth", "risDay", a.reissueDate());
        f.put("creditLimit", Amounts.edited(a.creditLimit()));
        f.put("cashCreditLimit", Amounts.edited(a.cashCreditLimit()));
        f.put("currentBalance", Amounts.edited(a.currentBalance()));
        f.put("cycleCredit", Amounts.edited(a.cycleCredit()));
        f.put("cycleDebit", Amounts.edited(a.cycleDebit()));
        f.put("group", a.groupId());
        f.put("customerId", String.format("%09d", c.customerId()));
        f.put("ssn1", SSN1_SHOWN);
        f.put("ssn2", SSN2_SHOWN);
        f.put("ssn3", ssnLast4(c.ssn()));
        putDate(f, "dob", "dobYear", "dobMonth", "dobDay", c.dob());
        f.put("fico", String.format("%03d", c.ficoScore()));
        f.put("firstName", c.firstName());
        f.put("middleName", c.middleName());
        f.put("lastName", c.lastName());
        f.put("addr1", c.addrLine1());
        f.put("addr2", c.addrLine2());
        f.put("state", c.stateCd());
        f.put("zip", part(c.zip(), 0, 5));
        f.put("city", c.city());
        f.put("country", c.countryCd());
        putPhone(f, "phone1", c.phone1());
        putPhone(f, "phone2", c.phone2());
        f.put("govtId", Masking.keepLast(c.govtIssuedId(), 4));
        f.put("eft", c.eftAccountId());
        f.put("primary", c.priCardHolder());
        return f;
    }

    /** Area code, prefix, and line number at positions 2-4, 6-8, 10-13 of {@code (AAA)BBB-CCCC} (Q-COACTUPC-11). */
    static List<String> phoneParts(String phone) {
        return List.of(part(phone, 1, 4), part(phone, 5, 8), part(phone, 9, 13));
    }

    static String ssnLast4(String ssn) {
        return ssn.length() >= 4 ? ssn.substring(ssn.length() - 4) : ssn;
    }

    static List<String> dateParts(LocalDate d) {
        return d == null ? List.of("", "", "") : List.of(String.format("%04d", d.getYear()),
            String.format("%02d", d.getMonthValue()), String.format("%02d", d.getDayOfMonth()));
    }

    private static void putDate(Map<String, String> f, String prefix, String y, String m, String d, LocalDate date) {
        List<String> p = dateParts(date);
        f.put(y, p.get(0));
        f.put(m, p.get(1));
        f.put(d, p.get(2));
    }

    private static void putPhone(Map<String, String> f, String prefix, String phone) {
        List<String> p = phoneParts(phone);
        f.put(prefix + "a", p.get(0));
        f.put(prefix + "b", p.get(1));
        f.put(prefix + "c", p.get(2));
    }

    private static String part(String s, int from, int to) {
        if (s == null || s.length() <= from) {
            return "";
        }
        return s.substring(from, Math.min(to, s.length())).strip();
    }

    private static Map<String, Integer> lengths(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }
}
