package com.carddemo.iam.internal;

import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Menu option tables COMEN02Y (main menu) and COADM02Y (admin menu) as configuration, with a role
 * column (Q-COMEN01C-01) and feature flags for optional modules (Q-COMEN01C-05).
 */
@Component
public class MenuCatalog {

    /** @param userType {@code U} or {@code A}: the user type the option is restricted to */
    public record Option(int number, String name, String program, String userType) {
        /** {@code nn. <name>} as BUILD-MENU-OPTIONS formats it (two-digit number). */
        public String line() {
            return String.format("%02d. %s", number, name);
        }
    }

    public static final List<Option> MAIN = List.of(
        new Option(1, "Account View", "COACTVWC", "U"),
        new Option(2, "Account Update", "COACTUPC", "U"),
        new Option(3, "Credit Card List", "COCRDLIC", "U"),
        new Option(4, "Credit Card View", "COCRDSLC", "U"),
        new Option(5, "Credit Card Update", "COCRDUPC", "U"),
        new Option(6, "Transaction List", "COTRN00C", "U"),
        new Option(7, "Transaction View", "COTRN01C", "U"),
        new Option(8, "Transaction Add", "COTRN02C", "U"),
        new Option(9, "Transaction Reports", "CORPT00C", "U"),
        new Option(10, "Bill Payment", "COBIL00C", "U"),
        new Option(11, "Pending Authorization View", "COPAUS0C", "U"));

    /** COADM02Y; every option requires ADMIN (Q-COADM01C-02). Options 5 and 6 are the transaction-type module. */
    public static final List<Option> ADMIN = List.of(
        new Option(1, "User List (Security)", "COUSR00C", "A"),
        new Option(2, "User Add (Security)", "COUSR01C", "A"),
        new Option(3, "User Update (Security)", "COUSR02C", "A"),
        new Option(4, "User Delete (Security)", "COUSR03C", "A"),
        new Option(5, "Transaction Type List/Update (Db2)", "COTRTLIC", "A"),
        new Option(6, "Transaction Type Maintenance (Db2)", "COTRTUPC", "A"));

    private final Set<String> disabledPrograms;

    public MenuCatalog(@Value("${carddemo.features.disabled-programs:}") Set<String> disabledPrograms) {
        this.disabledPrograms = disabledPrograms;
    }

    public List<Option> main() {
        return MAIN;
    }

    public List<Option> admin() {
        return ADMIN;
    }

    public boolean installed(String program) {
        return !disabledPrograms.contains(program);
    }
}
