package com.carddemo.migration;

import static com.carddemo.migration.Layout.Field.d;
import static com.carddemo.migration.Layout.Field.n;
import static com.carddemo.migration.Layout.Field.s;
import static com.carddemo.migration.Layout.Field.ts;
import static com.carddemo.migration.Layout.Field.x;

import java.util.List;

/** The base record layouts (spec/entities.md), in load order (reference data before masters). */
public final class Layouts {

    public static final Layout TRANTYPE = new Layout("trantype.txt", "transaction_type", 60, List.of(
            x("type_cd", 0, 2), x("description", 2, 50), x("legacy_filler", 52, 8)), "type_cd");

    public static final Layout TRANCATG = new Layout("trancatg.txt", "transaction_category", 60, List.of(
            x("type_cd", 0, 2), n("cat_cd", 2, 4), x("description", 6, 50), x("legacy_filler", 56, 4)),
            "type_cd, cat_cd");

    public static final Layout DISCGRP = new Layout("discgrp.txt", "disclosure_group", 50, List.of(
            x("group_id", 0, 10), x("type_cd", 10, 2), n("cat_cd", 12, 4), s("int_rate", 16, 6, 2),
            x("legacy_filler", 22, 28)),
            "group_id, type_cd, cat_cd");

    public static final Layout CUSTDATA = new Layout("custdata.txt", "customer", 500, List.of(
            n("customer_id", 0, 9), x("first_name", 9, 25), x("middle_name", 34, 25), x("last_name", 59, 25),
            x("addr_line1", 84, 50), x("addr_line2", 134, 50), x("city", 184, 50), x("state_cd", 234, 2),
            x("country_cd", 236, 3), x("zip", 239, 10), x("phone1", 249, 15), x("phone2", 264, 15),
            x("ssn", 279, 9), x("govt_issued_id", 288, 20), d("dob", 308), x("eft_account_id", 318, 10),
            x("pri_card_holder", 328, 1), n("fico_score", 329, 3)), "customer_id");

    public static final Layout ACCTDATA = new Layout("acctdata.txt", "account", 300, List.of(
            n("account_id", 0, 11), x("active_status", 11, 1), s("current_balance", 12, 12, 2),
            s("credit_limit", 24, 12, 2), s("cash_credit_limit", 36, 12, 2), d("open_date", 48),
            d("expiration_date", 58), d("reissue_date", 68), s("curr_cycle_credit", 78, 12, 2),
            s("curr_cycle_debit", 90, 12, 2), x("addr_zip", 102, 10), x("group_id", 112, 10)), "account_id");

    public static final Layout CARDDATA = new Layout("carddata.txt", "card", 150, List.of(
            x("card_num", 0, 16), n("account_id", 16, 11), x("cvv_cd", 27, 3), x("embossed_name", 30, 50),
            d("expiration_date", 80), x("active_status", 90, 1)), "card_num");

    public static final Layout CARDXREF = new Layout("cardxref.txt", "card_xref", 50, List.of(
            x("card_num", 0, 16), n("customer_id", 16, 9), n("account_id", 25, 11)), "card_num");

    public static final Layout TCATBAL = new Layout("tcatbal.txt", "tran_cat_balance", 50, List.of(
            n("account_id", 0, 11), x("type_cd", 11, 2), n("cat_cd", 13, 4), s("balance", 17, 11, 2),
            x("legacy_filler", 28, 22)),
            "account_id, type_cd, cat_cd");

    /** TRANSACT (CVTRA05Y). Not part of the base sample (the initial record is not migrated, Q-ENT-31). */
    public static final Layout TRANSACT = new Layout("transact.txt", "card_transaction", 350, List.of(
            x("tran_id", 0, 16), x("type_cd", 16, 2), n("cat_cd", 18, 4), x("source", 22, 10),
            x("description", 32, 100), s("amount", 132, 11, 2), n("merchant_id", 143, 9),
            x("merchant_name", 152, 50), x("merchant_city", 202, 50), x("merchant_zip", 252, 10),
            x("card_num", 262, 16), ts("orig_ts_text", 278, "orig_ts"), ts("proc_ts_text", 304, "proc_ts")),
            "tran_id");

    public static final List<Layout> BASE = List.of(TRANTYPE, TRANCATG, DISCGRP, CUSTDATA, ACCTDATA, CARDDATA,
            CARDXREF, TCATBAL, TRANSACT);

    private Layouts() {
    }
}
