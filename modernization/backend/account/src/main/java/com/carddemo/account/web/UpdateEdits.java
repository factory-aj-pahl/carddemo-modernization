package com.carddemo.account.web;

import com.carddemo.account.internal.AccountRecord;
import com.carddemo.account.internal.CustomerRecord;
import com.carddemo.account.internal.Numval;
import com.carddemo.account.internal.UsLookups;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Change detection, field edits, and the saved image of COACTUPC under D-VALIDATE-CHANGED
 * (Q-COACTUPC-05, -06, -07): only the changed fields are edited, in the COBOL order, and the first
 * failing message is kept (VR-COACTUPC-34) while every failing field is flagged.
 */
final class UpdateEdits {

    /** One logical field; multi-part fields (dates, SSN, phones) change and are edited as a whole. */
    enum Group {
        ACTIVE_STATUS("active"),
        OPEN_DATE("openYear", "openMonth", "openDay"),
        CREDIT_LIMIT("creditLimit"),
        EXPIRATION_DATE("expYear", "expMonth", "expDay"),
        CASH_CREDIT_LIMIT("cashCreditLimit"),
        REISSUE_DATE("risYear", "risMonth", "risDay"),
        CURRENT_BALANCE("currentBalance"),
        CYCLE_CREDIT("cycleCredit"),
        CYCLE_DEBIT("cycleDebit"),
        GROUP_ID("group"),
        SSN("ssn1", "ssn2", "ssn3"),
        DOB("dobYear", "dobMonth", "dobDay"),
        FICO_SCORE("fico"),
        FIRST_NAME("firstName"),
        MIDDLE_NAME("middleName"),
        LAST_NAME("lastName"),
        ADDR_LINE1("addr1"),
        ADDR_LINE2("addr2"),
        STATE("state"),
        ZIP("zip"),
        CITY("city"),
        PHONE1("phone1a", "phone1b", "phone1c"),
        PHONE2("phone2a", "phone2b", "phone2c"),
        GOVT_ID("govtId"),
        EFT_ACCOUNT_ID("eft"),
        PRIMARY_HOLDER("primary");

        final List<String> fields;

        Group(String... fields) {
            this.fields = List.of(fields);
        }

        boolean amount() {
            return this == CREDIT_LIMIT || this == CASH_CREDIT_LIMIT || this == CURRENT_BALANCE || this == CYCLE_CREDIT
                || this == CYCLE_DEBIT;
        }

        /** Name used in audit records: never a value. */
        String auditName() {
            StringBuilder b = new StringBuilder();
            boolean upper = false;
            for (char ch : name().toLowerCase().toCharArray()) {
                if (ch == '_') {
                    upper = true;
                } else {
                    b.append(upper ? Character.toUpperCase(ch) : ch);
                    upper = false;
                }
            }
            return b.toString();
        }
    }

    /** Outcome of the edits: the first message, the fields flagged in error, and those flagged blank (shown as {@code *}). */
    static final class Result {
        String message;
        final Set<String> errors = new LinkedHashSet<>();
        final Set<String> blanks = new LinkedHashSet<>();

        boolean ok() {
            return errors.isEmpty();
        }

        void fail(String field, String msg) {
            errors.add(field);
            if (message == null) {
                message = msg;
            }
        }

        void blank(String field, String msg) {
            blanks.add(field);
            fail(field, msg);
        }

        /** First flagged field in screen order (BR-COACTUPC-19). */
        String focus() {
            return UpdateForm.LENGTHS.keySet().stream().filter(errors::contains).findFirst().orElse("active");
        }
    }

    private UpdateEdits() {
    }

    /** BR-COACTUPC-07: {@code *} and all spaces are blank. */
    static String norm(String v) {
        return v == null || v.isBlank() || v.strip().equals("*") ? "" : v;
    }

    /**
     * Q-COACTUPC-05/-06: a field changed when its trimmed, case-sensitive value differs from the shown
     * snapshot value; amounts compare as numbers (BR-COACTUPC-08); a masked SSN or government id
     * submitted as shown is unchanged (Q-COACTUPC-28).
     */
    static Set<Group> changed(Map<String, String> shown, Map<String, String> in) {
        Set<Group> out = EnumSet.noneOf(Group.class);
        for (Group g : Group.values()) {
            if (g.amount()) {
                Optional<BigDecimal> before = Numval.amount(shown.get(g.fields.get(0)));
                Optional<BigDecimal> after = Numval.amount(norm(in.get(g.fields.get(0))));
                if (after.isEmpty() || before.isEmpty() || after.get().compareTo(before.get()) != 0) {
                    out.add(g);
                }
                continue;
            }
            for (String f : g.fields) {
                if (!norm(in.get(f)).strip().equals(norm(shown.get(f)).strip())) {
                    out.add(g);
                    break;
                }
            }
        }
        return out;
    }

    /** VR-COACTUPC-07 to VR-COACTUPC-33 for the changed groups. */
    static Result validate(Set<Group> changed, Map<String, String> in, LocalDate today) {
        Result r = new Result();
        for (Group g : changed) {
            switch (g) {
                case ACTIVE_STATUS -> yesNo(r, "active", "Account Status", in);
                case OPEN_DATE -> date(r, "Open Date", g, in, null);
                case CREDIT_LIMIT -> amount(r, "creditLimit", "Credit Limit", in);
                case EXPIRATION_DATE -> date(r, "Expiry Date", g, in, null);
                case CASH_CREDIT_LIMIT -> amount(r, "cashCreditLimit", "Cash Credit Limit", in);
                case REISSUE_DATE -> date(r, "Reissue Date", g, in, null);
                case CURRENT_BALANCE -> amount(r, "currentBalance", "Current Balance", in);
                // Q-COACTUPC-12: the 26-character name is cut to WS-EDIT-VARIABLE-NAME X(25).
                case CYCLE_CREDIT -> amount(r, "cycleCredit", "Current Cycle Credit Limi", in);
                case CYCLE_DEBIT -> amount(r, "cycleDebit", "Current Cycle Debit Limit", in);
                case SSN -> ssn(r, in);
                case DOB -> date(r, "Date of Birth", g, in, today);
                case FICO_SCORE -> {
                    if (numRequired(r, "fico", "FICO Score", 3, in)) {
                        int fico = Integer.parseInt(in.get("fico"));
                        if (fico < 300 || fico > 850) {
                            r.fail("fico", "FICO Score: should be between 300 and 850");
                        }
                    }
                }
                case FIRST_NAME -> alpha(r, "firstName", "First Name", true, in);
                case MIDDLE_NAME -> alpha(r, "middleName", "Middle Name", false, in);
                case LAST_NAME -> alpha(r, "lastName", "Last Name", true, in);
                case ADDR_LINE1 -> {
                    if (norm(in.get("addr1")).isBlank()) {
                        r.blank("addr1", "Address Line 1 must be supplied.");
                    }
                }
                case STATE -> {
                    if (alpha(r, "state", "State", true, in) && !UsLookups.isStateCode(in.get("state"))) {
                        r.fail("state", "State: is not a valid state code");
                    }
                }
                case ZIP -> numRequired(r, "zip", "Zip", 5, in);
                case CITY -> alpha(r, "city", "City", true, in);
                case PHONE1 -> phone(r, "Phone Number 1", g, in);
                case PHONE2 -> phone(r, "Phone Number 2", g, in);
                case EFT_ACCOUNT_ID -> numRequired(r, "eft", "EFT Account Id", 10, in);
                case PRIMARY_HOLDER -> yesNo(r, "primary", "Primary Card Holder", in);
                default -> {
                    // VR-COACTUPC-35: group id, address line 2, and government id have no edit.
                }
            }
        }
        boolean stateOrZip = changed.contains(Group.STATE) || changed.contains(Group.ZIP);
        if (stateOrZip && !r.errors.contains("state") && !r.errors.contains("zip")
            && !UsLookups.zipMatchesState(in.get("state").strip(), in.get("zip").strip())) {
            r.fail("state", "Invalid zip code for state");
            r.fail("zip", "Invalid zip code for state");
        }
        return r;
    }

    /** The records to save: changed groups from the validated input, all other columns exactly as stored. */
    static AccountRecord account(AccountRecord old, Set<Group> changed, Map<String, String> in) {
        return new AccountRecord(old.accountId(),
            changed.contains(Group.ACTIVE_STATUS) ? in.get("active") : old.activeStatus(),
            changed.contains(Group.CURRENT_BALANCE) ? amountOf(in, "currentBalance") : old.currentBalance(),
            changed.contains(Group.CREDIT_LIMIT) ? amountOf(in, "creditLimit") : old.creditLimit(),
            changed.contains(Group.CASH_CREDIT_LIMIT) ? amountOf(in, "cashCreditLimit") : old.cashCreditLimit(),
            changed.contains(Group.OPEN_DATE) ? dateOf(in, Group.OPEN_DATE) : old.openDate(),
            changed.contains(Group.EXPIRATION_DATE) ? dateOf(in, Group.EXPIRATION_DATE) : old.expirationDate(),
            changed.contains(Group.REISSUE_DATE) ? dateOf(in, Group.REISSUE_DATE) : old.reissueDate(),
            changed.contains(Group.CYCLE_CREDIT) ? amountOf(in, "cycleCredit") : old.cycleCredit(),
            changed.contains(Group.CYCLE_DEBIT) ? amountOf(in, "cycleDebit") : old.cycleDebit(),
            changed.contains(Group.GROUP_ID) ? text(in, "group") : old.groupId(),
            old.rowVersion());
    }

    static CustomerRecord customer(CustomerRecord old, Set<Group> changed, Map<String, String> in) {
        return new CustomerRecord(old.customerId(),
            changed.contains(Group.FIRST_NAME) ? text(in, "firstName") : old.firstName(),
            changed.contains(Group.MIDDLE_NAME) ? text(in, "middleName") : old.middleName(),
            changed.contains(Group.LAST_NAME) ? text(in, "lastName") : old.lastName(),
            changed.contains(Group.ADDR_LINE1) ? text(in, "addr1") : old.addrLine1(),
            changed.contains(Group.ADDR_LINE2) ? text(in, "addr2") : old.addrLine2(),
            changed.contains(Group.CITY) ? text(in, "city") : old.city(),
            changed.contains(Group.STATE) ? text(in, "state") : old.stateCd(),
            old.countryCd(),
            changed.contains(Group.ZIP) ? text(in, "zip") : old.zip(),
            changed.contains(Group.PHONE1) ? phoneOf(in, Group.PHONE1) : old.phone1(),
            changed.contains(Group.PHONE2) ? phoneOf(in, Group.PHONE2) : old.phone2(),
            changed.contains(Group.SSN) ? in.get("ssn1") + in.get("ssn2") + in.get("ssn3") : old.ssn(),
            changed.contains(Group.GOVT_ID) ? text(in, "govtId") : old.govtIssuedId(),
            changed.contains(Group.DOB) ? dateOf(in, Group.DOB) : old.dob(),
            changed.contains(Group.EFT_ACCOUNT_ID) ? in.get("eft") : old.eftAccountId(),
            changed.contains(Group.PRIMARY_HOLDER) ? in.get("primary") : old.priCardHolder(),
            changed.contains(Group.FICO_SCORE) ? Integer.parseInt(in.get("fico")) : old.ficoScore(),
            old.rowVersion());
    }

    /** BR-COACTUPC-05: a valid amount is shown re-edited, an invalid one as typed. */
    static String shownAmount(String typed) {
        return Numval.amount(norm(typed)).map(Amounts::edited).orElse(typed);
    }

    static List<String> auditNames(Set<Group> changed) {
        List<String> names = new ArrayList<>();
        changed.forEach(g -> names.add(g.auditName()));
        return names;
    }

    // ---- edits ---------------------------------------------------------------------------------

    /** 1220-EDIT-YESNO: uppercase Y or N only (Q-COACTUPC-25). */
    private static void yesNo(Result r, String field, String name, Map<String, String> in) {
        String v = norm(in.get(field));
        if (v.isEmpty() || v.equals("0")) {
            r.blank(field, name + " must be supplied.");
        } else if (!v.equals("Y") && !v.equals("N")) {
            r.fail(field, name + " must be Y or N.");
        }
    }

    /** 1225-EDIT-ALPHA-REQD / 1235-EDIT-ALPHA-OPT: letters and spaces only. True when the field passed. */
    private static boolean alpha(Result r, String field, String name, boolean required, Map<String, String> in) {
        String v = norm(in.get(field));
        if (v.isBlank()) {
            if (required) {
                r.blank(field, name + " must be supplied.");
                return false;
            }
            return true;
        }
        if (!v.chars().allMatch(c -> c == ' ' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
            r.fail(field, name + " can have alphabets only.");
            return false;
        }
        return true;
    }

    /** 1245-EDIT-NUM-REQD: supplied, all {@code len} positions numeric, not zero. True when the field passed. */
    private static boolean numRequired(Result r, String field, String name, int len, Map<String, String> in) {
        String v = norm(in.get(field));
        if (v.isBlank()) {
            r.blank(field, name + " must be supplied.");
            return false;
        }
        if (!digits(v, len)) {
            r.fail(field, name + " must be all numeric.");
            return false;
        }
        if (Long.parseLong(v) == 0) {
            r.fail(field, name + " must not be zero.");
            return false;
        }
        return true;
    }

    /** 1250-EDIT-SIGNED-9V2 with the Q-COACTUPC-15 size limit. */
    private static void amount(Result r, String field, String name, Map<String, String> in) {
        String v = norm(in.get(field));
        if (v.isEmpty()) {
            r.blank(field, name + " must be supplied.");
        } else if (Numval.amount(v).isEmpty()) {
            r.fail(field, name + " is not valid");
        }
    }

    /** 1265-EDIT-US-SSN: each part edited separately; part 1 also rejects 666 and 900-999. */
    private static void ssn(Result r, Map<String, String> in) {
        if (numRequired(r, "ssn1", "SSN: First 3 chars", 3, in)) {
            int p1 = Integer.parseInt(in.get("ssn1"));
            if (p1 == 666 || p1 >= 900) {
                r.fail("ssn1", "SSN: First 3 chars: should not be 000, 666, or between 900 and 999");
            }
        }
        numRequired(r, "ssn2", "SSN 4th & 5th chars", 2, in);
        numRequired(r, "ssn3", "SSN Last 4 chars", 4, in);
    }

    /** 1260-EDIT-US-PHONE-NUM: optional as a whole (all parts blank), otherwise each part edited. */
    private static void phone(Result r, String name, Group g, Map<String, String> in) {
        String a = g.fields.get(0);
        String b = g.fields.get(1);
        String c = g.fields.get(2);
        if (norm(in.get(a)).isBlank() && norm(in.get(b)).isBlank() && norm(in.get(c)).isBlank()) {
            return;
        }
        if (phonePart(r, a, name + ": Area code", "A 3 digit number", 3, in)
            && !UsLookups.isGeneralPurposeAreaCode(in.get(a))) {
            r.fail(a, name + ": Not valid North America general purpose area code");
        }
        phonePart(r, b, name + ": Prefix code", "A 3 digit number", 3, in);
        phonePart(r, c, name + ": Line number code", "A 4 digit number", 4, in);
    }

    private static boolean phonePart(Result r, String field, String label, String shape, int len, Map<String, String> in) {
        String v = norm(in.get(field));
        if (v.isBlank()) {
            r.blank(field, label + " must be supplied.");
            return false;
        }
        if (!digits(v, len)) {
            r.fail(field, label + " must be " + shape + ".");
            return false;
        }
        if (Integer.parseInt(v) == 0) {
            r.fail(field, label + " cannot be zero");
            return false;
        }
        return true;
    }

    /**
     * EDIT-DATE-CCYYMMDD of CSUTLDPY. A date passing these edits is a real calendar date, so the
     * CSUTLDTC call adds nothing (Q-COACTUPC-14). With {@code today} set, the date must also be before
     * today (EDIT-DATE-OF-BIRTH), checked only for a valid date (Q-COACTUPC-13).
     */
    private static void date(Result r, String name, Group g, Map<String, String> in, LocalDate today) {
        String yf = g.fields.get(0);
        String mf = g.fields.get(1);
        String df = g.fields.get(2);
        String y = norm(in.get(yf));
        String m = norm(in.get(mf));
        String d = norm(in.get(df));
        int before = r.errors.size();

        Integer year = null;
        if (y.isBlank()) {
            r.blank(yf, name + " : Year must be supplied.");
        } else if (!digits(y, 4)) {
            r.fail(yf, name + " must be 4 digit number.");
        } else if (!y.startsWith("19") && !y.startsWith("20")) {
            r.fail(yf, name + " : Century is not valid.");
            year = Integer.parseInt(y);
        } else {
            year = Integer.parseInt(y);
        }

        Integer month = smallNumber(m);
        if (m.isBlank()) {
            r.blank(mf, name + " : Month must be supplied.");
        } else if (month == null || month < 1 || month > 12) {
            r.fail(mf, name + ": Month must be a number between 1 and 12.");
        }

        Integer day = smallNumber(d);
        if (d.isBlank()) {
            r.blank(df, name + " : Day must be supplied.");
        } else if (day == null || day < 1 || day > 31) {
            r.fail(df, name + ":day must be a number between 1 and 31.");
        }

        if (month != null && day != null) {
            boolean longMonth = Set.of(1, 3, 5, 7, 8, 10, 12).contains(month);
            if (!longMonth && day == 31) {
                r.fail(df, name + ":Cannot have 31 days in this month.");
                r.fail(mf, name + ":Cannot have 31 days in this month.");
            } else if (month == 2 && day == 30) {
                r.fail(df, name + ":Cannot have 30 days in this month.");
                r.fail(mf, name + ":Cannot have 30 days in this month.");
            } else if (month == 2 && day == 29 && year != null && year % (year % 100 == 0 ? 400 : 4) != 0) {
                String msg = name + ":Not a leap year.Cannot have 29 days in this month.";
                r.fail(df, msg);
                r.fail(mf, msg);
                r.fail(yf, msg);
            }
        }
        if (r.errors.size() != before || today == null) {
            return;
        }
        if (!LocalDate.of(year, month, day).isBefore(today)) {
            String msg = name + ":cannot be in the future ";
            r.fail(df, msg);
            r.fail(mf, msg);
            r.fail(yf, msg);
        }
    }

    /** A 1-2 digit number with surrounding spaces, as TEST-NUMVAL accepts it for a 2-character part. */
    private static Integer smallNumber(String v) {
        String s = v.strip();
        return s.matches("[0-9]{1,2}") ? Integer.valueOf(s) : null;
    }

    private static boolean digits(String v, int len) {
        return v.length() == len && v.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    private static BigDecimal amountOf(Map<String, String> in, String field) {
        return Numval.amount(norm(in.get(field))).orElseThrow();
    }

    private static LocalDate dateOf(Map<String, String> in, Group g) {
        return LocalDate.of(Integer.parseInt(in.get(g.fields.get(0))), Integer.parseInt(in.get(g.fields.get(1)).strip()),
            Integer.parseInt(in.get(g.fields.get(2)).strip()));
    }

    private static String text(Map<String, String> in, String field) {
        return norm(in.get(field)).stripTrailing();
    }

    /** BR-COACTUPC-14 phone image {@code (AAA)BBB-CCCC}; all parts blank is stored empty (Q-COACTUPC-10). */
    private static String phoneOf(Map<String, String> in, Group g) {
        String a = norm(in.get(g.fields.get(0)));
        String b = norm(in.get(g.fields.get(1)));
        String c = norm(in.get(g.fields.get(2)));
        if (a.isBlank() && b.isBlank() && c.isBlank()) {
            return "";
        }
        return "(" + a + ")" + b + "-" + c;
    }
}
