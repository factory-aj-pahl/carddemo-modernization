# Fidelity ledger: batch programs, data, jobs, and optional modules

Covers every `Q-` ID defined in `../spec/entities.md`, `../spec/jobs.md`, and `../spec/programs/` for
the base batch programs (CBACT01C, CBACT02C, CBACT03C, CBACT04C, CBCUS01C, CBTRN01C, CBTRN02C,
CBTRN03C, CBSTM03A, CBSTM03B, CBEXPORT, CBIMPORT, COBSWAIT) and all optional-module programs
(COTRTLIC, COTRTUPC, COBTUPDT, COACCT01, CODATE01, COPAUA0C, COPAUS0C, COPAUS1C, COPAUS2C, CBPAUP0C,
PAUDBUNL, PAUDBLOD, DBUNLDGS). Decision values, the classification policy, and the user decisions
(D- IDs) are defined in `README.md`. Behavior not listed here as FIX or CHANGE is preserved as
specified in `../spec/`.

Message text in backticks is verbatim. Text marked **NEW** does not exist in the COBOL. The online
conventions C-ROLE, C-SCOPE, C-MASK, C-SESSION, C-RENDER, and C-AUDIT are defined in
`ledger-online.md` and apply unchanged to the optional-module screens (COTRTLIC, COTRTUPC, COPAUS0C,
COPAUS1C).

## Conventions used by the rows

| ID | Rule |
|:--|:--|
| B-ABEND | A COBOL abend (CEE3ABD with or without arguments, U999, or a runtime data exception) maps to job status ABENDED, abend code `U999` in the job log, and process exit code 99, which no normal path uses. Every DISPLAY line written before the abend stays verbatim. The open database transaction is rolled back; work committed by earlier units of work (B-UOW) stays. |
| B-DATE | Cycle jobs take a business date parameter (`YYYY-MM-DD`) from the scheduler. A missing or invalid value stops the job before any read with exit code 12 and the **NEW** line `INVALID OR MISSING BUSINESS DATE`. Where the COBOL used a PARM date (`2022071800` in INTCALC) the target uses the business date as `YYYYMMDD` + `00`, so the COBOL ID format is kept. |
| B-UOW | (D-BATCH-WINDOW, D-FIX-DATA) Batch updates are database transactions with row locks; online stays available. POSTTRAN commits one transaction per daily record (category balance, account, and transaction row, or one reject row). INTCALC commits one transaction per account (its interest rows and the account update). Load and maintenance jobs (COBTUPDT, PAUDBLOD) commit once per input file. A failure rolls back only the current unit of work. |
| B-MASK | (D-FIX-SEC) No job writes a full card number, CVV, SSN, government ID, date of birth, or EFT account to SYSOUT or a log. Card numbers print as `************` + last 4, CVV as `***`, SSN as `*****` + last 4, EFT account as `******` + last 4, government ID and date of birth as all `*` of the same length. Record layout and line count are otherwise unchanged. Data files that are contracts (exports, unloads, rejects) are covered by their own rows. |
| B-CYCLE | (D-CYCLE, D-INTEREST) The cycle runs once per business date in runbook order with explicit dependencies. On every business date: POSTTRAN, then CREASTMT. On the first business date of each calendar month (the first business date the scheduler issues in that month): POSTTRAN, then INTCALC, then CREASTMT. INTCALC runs at most once per calendar month (Q-CBACT04C-08) with the COBOL formula, annual rate / 1200 (Q-ENT-11). CLOSEFIL, OPENFIL, WAITSTEP, TRANBKP, COMBTRAN, and TRANIDX have no target step: online stays up (D-BATCH-WINDOW), interest rows are inserted directly into the transaction table, and a database backup is taken before POSTTRAN. TRANREPT runs on request (CORPT00C) and is not part of the cycle. Setup jobs (ACCTFILE, CARDFILE, CUSTFILE, XREFFILE, TRANFILE, TCATBALF, TRANTYPE, TRANCATG, DISCGRP, DUSRSECJ) are seed and migration steps, never scheduled. Module jobs (CBPAUP0J, MNTTRDB2, UNLDPADB, LOADPADB, UNLDGSAM) run on their own schedules or on request and are never prerequisites of the base cycle. The CA7 and Control-M definitions are not ported. |
| B-PARITY | Parity runs compile the original and the patched scratch copies with `cobc -x -std=ibm -fsign=EBCDIC -I app/cpy` and load the ASCII sample data. Reference patches are applied to copies under `/tmp`, never to `app/`. |

## entities.md

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-ENT-01 | COACTUPC rewrite layout moves the group ID into the ZIP slot and FILLER into ACCT-GROUP-ID | data-corruption | FIX | The account save writes columns by name (ledger-online Q-COACTUPC-01): ACCT-GROUP-ID receives the edited group, ACCT-ADDR-ZIP keeps its stored value. Fixed-width exports keep bytes 103-112 as ZIP and 113-122 as group ID. | n/a (spec-derived tests; CICS) |
| Q-ENT-02 | All 50 sample accounts carry the group ID at offset 102 and spaces at 112 | data-corruption | FIX | Migration rule: when bytes 113-122 are blank and bytes 103-112 hold a group ID that exists in DISCGRP, the value moves to ACCT-GROUP-ID and ACCT-ADDR-ZIP becomes blank. Impact on the interest run with the ASCII sample: all 50 accounts get group `A000000000`; the 100 "missing" message lines disappear (SYSOUT 303 to 103 lines); the interest transactions and every balance are identical because the `A000000000` rates equal the `DEFAULT` rates in `discgrp.txt`. | RP-DATA-1 |
| Q-ENT-03 | Misspelled field names (`EXPIRAION`, `CATAGORY`, `0UT`) are part of the contract | platform | PLATFORM | Target column and property names are spelled correctly (`expiration_date`, `merchant_category_code`); the migration keeps a name mapping. Screen labels and report text keep their verbatim spelling (D-COSMETIC). | n/a |
| Q-ENT-04 | CUST-ADDR-LINE-3 is used as the city | platform | PLATFORM | The column is named `city`; screens, statements, and exports use it exactly where the COBOL uses ADDR-LINE-3. | n/a |
| Q-ENT-05 | CUSTREC duplicates CVCUS01Y with a misleading DOB name | platform | PLATFORM | One customer entity; DOB stored as a DATE and rendered `YYYY-MM-DD` wherever the COBOL shows it. | n/a |
| Q-ENT-06 | CDEMO-CARD-NUM is numeric in the COMMAREA but text in files | platform | PLATFORM | Card numbers are 16-character strings everywhere (C-SESSION); a value is never converted to a number. | n/a |
| Q-ENT-07 | Three TRAN-ID generators that can collide | concurrency | FIX | TRAN-ID is a unique business key. Feed IDs are stored as supplied; online IDs come from one locked counter shared by the online writers (ledger-online Q-COTRN02C-01); interest IDs keep the format business date `YYYYMMDD00` + 6-digit counter (B-DATE) and INTCALC completes at most once per calendar month (B-CYCLE, Q-CBACT04C-08). A duplicate ID is rejected by the unique constraint and handled per writer (POSTTRAN: B-ABEND for that record's unit of work; INTCALC: B-ABEND). | n/a (covered by Q-INTCALC-01) |
| Q-ENT-08 | TRAN-ORIG-TS and TRAN-PROC-TS hold at least four text formats | functional-defect | FIX | Timestamps are stored as `DATETIME2` parsed from every legacy variant; the raw text is kept in a migration column. Screens and reports render the text the COBOL writer would have produced, so displayed values do not change; sorting and date filters use the parsed value. | n/a |
| Q-ENT-09 | Negative amounts are added to cycle debit; over-limit formula adds returns to used credit | business-choice | PRESERVE | Business choice: same sign convention and same formula `CYC-CREDIT - CYC-DEBIT + AMT` (Q-CBTRN02C-02). | n/a |
| Q-ENT-10 | Category code numeric in VSAM, CHAR(4) in Db2 | platform | PLATFORM | One `SMALLINT` column displayed as 4 digits with leading zeros. | n/a |
| Q-ENT-11 | Interest truncated; negative balances give negative interest | business-choice | PRESERVE (D-INTEREST) | Business choice (D-INTEREST): interest = balance x rate / 1200 per monthly INTCALC run, truncated to 2 decimals, negative for negative balances (Q-CBACT04C-02). The formula is unchanged. | n/a |
| Q-ENT-12 | FILLER in ASCII reference files is `0` characters | platform | PLATFORM | Loaders ignore FILLER content. | n/a |
| Q-ENT-13 | ASCII and EBCDIC sample sets differ | platform | PLATFORM (DF-2) | The migration and parity baseline is `app/data/ASCII` (the set the GnuCOBOL harness runs, DF-2). Where the EBCDIC set differs (account 49, `DEFAULT` 07/0001 rate, export snapshot) the ASCII values are used and the EBCDIC values are not migrated. | n/a |
| Q-ENT-14 | Defined but unused access paths (TRANSACT AIX, CARDAIX, CXACAIX handling) | dead-code | NOT-PORTED | Unused indexes are not created; the only added index is on the transaction processed timestamp (Q-TRANFILE-02). | n/a |
| Q-ENT-15 | DEFCUST defines an unused cluster with a wrong key length | dead-code | NOT-PORTED | Not migrated. | n/a |
| Q-ENT-16 | TRANREPT GDG defined twice with LIMIT 5 and LIMIT 10 | platform | PLATFORM (DF-3) | Generated transaction reports are retained for the last 10 runs (DF-3); storing an eleventh report deletes the oldest. | n/a |
| Q-ENT-17 | Clear-text 8-character passwords, case-insensitive compare, user type unchecked | security | FIX | D-AUTH as specified in ledger-online Q-COSGN00C-01, Q-COSGN00C-02, and Q-COSGN00C-07 (BCrypt, case-sensitive passwords, type `A` or `U`). | n/a |
| Q-ENT-18 | UNUSED1Y is copied by no program | dead-code | NOT-PORTED | Not ported. | n/a |
| Q-ENT-19 | CVEXPORT mixes zoned, binary, and packed fields | platform | PLATFORM | The export/import file keeps the CVEXPORT byte layout and is read and written by a field-by-field codec (never a whole-record code-page conversion); key order is the binary sequence number (Q-CBEXPORT-04, Q-CBEXPORT-06). | n/a |
| Q-ENT-20 | CODATECN type codes and duplicate names | platform | PLATFORM | Replaced by a library date formatter that reproduces the same input and output formats. | n/a |
| Q-ENT-21 | Menu tables declare more OCCURS entries than initialized | dead-code | NOT-PORTED | Menus come from a configuration table holding only the real options. | n/a |
| Q-ENT-22 | Phone area code checked against general-purpose codes only; military states unreachable | business-choice | PRESERVE | Business choice: same lookup tables (ledger-online Q-COACTUPC-09). | n/a |
| Q-ENT-23 | Date edits accept centuries 19 and 20 only | business-choice | PRESERVE | Business choice: same century limits for DOB and expiry dates. | n/a |
| Q-ENT-24 | SSN parts 2 and 3 checked only for numeric and non-zero | business-choice | PRESERVE | Business choice: same edits. | n/a |
| Q-ENT-25 | Blank-phone check tests the line number for LOW-VALUES only | functional-defect | FIX | Same finding and decision as ledger-online Q-COACTUPC-10: every phone part is tested for blank, and a blank optional phone is stored empty. | n/a |
| Q-ENT-26 | Authorization limits S9(09)V99 filled from S9(10)V99 | data-corruption | FIX | All money fields of the authorization module are `DECIMAL(12,2)`; limits and available amounts of 1,000,000,000.00 or more are kept exactly. | n/a |
| Q-ENT-27 | Authorization keys use an inverted 2-digit-year date; purge age wrong across year ends | data-corruption | FIX | Authorizations store a full insert timestamp; display order (newest first) and purge age (whole days between the insert date and the run date) are computed from it (Q-CBPAUP0C-03). | n/a |
| Q-ENT-28 | AUTH_TS rebuilt from a 2-digit year; fraud report date from two sources | data-corruption | FIX | One source each: Db2 `AUTH_TS` is the stored authorization insert timestamp (Q-COPAUS2C-01); the fraud report date is one DATE value written to both the authorization and the fraud row and shown `MM/DD/YY`. | n/a |
| Q-ENT-29 | Active-status and several indicator fields are never enforced | business-choice | PRESERVE (D-INACTIVE) | Business choice (D-INACTIVE): inactive accounts and cards are not blocked in posting, interest, bill pay, authorization, or any screen; the fields are migrated, shown, and editable as today. | n/a |
| Q-ENT-30 | ACCT-ADDR-ZIP used only by export and import | business-choice | PRESERVE | Business choice: the column is kept, migrated (blank for the sample accounts after Q-ENT-02), exported, and imported; no screen shows it. | n/a |
| Q-ENT-31 | TRANSACT is initialized with a LOW-VALUES record | platform | PLATFORM | The initialization record is not migrated; an empty transaction table is valid (ledger-online Q-COTRN02C-13). | n/a |
| Q-ENT-32 | Online programs use only the first card of an account | functional-defect | FIX (DF-6) | Where one card of an account is needed, the lowest card number is used (DF-6) (ledger-online Q-COTRN02C-07, Q-COBIL00C-11; Q-CBACT04C-03); lists show all cards. | n/a |
| Q-ENT-33 | CVV, PAN, SSN, and government ID stored in clear | security | PRESERVE (D-PII-FILES) | (D-PII-FILES) The database stores PAN, CVV, SSN, and government ID with the COBOL content; CVV is kept as the COBOL keeps it (card update preserves it, ledger-online Q-COCRDUPC-01; the export carries it, Q-CBEXPORT-01). These values are never sent to a client (C-MASK) or written to SYSOUT or a log (B-MASK). Files that carry them are written mode 600 (Q-CBEXPORT-01, Q-CBIMPORT-10, Q-PAUDBUNL-10, Q-DBUNLDGS-09, Q-CBTRN02C-10). | n/a |
| Q-ENT-34 | Db2 seed has `REVERAL` and upper case; VSAM has `Reversal` | platform | PLATFORM (DF-1) | The target has one transaction-type table and one category table used by both the base application and the Db2 module, seeded (DF-1) from `app/data/ASCII/trantype.txt` and `trancatg.txt` (mixed case, `Reversal`), so report text matches the base batch. The Db2 seed text is not loaded. | n/a |
| Q-ENT-35 | CUST-UPDATE-RECORD comment says 300 bytes | dead-code | NOT-PORTED | Comment only. | n/a |
| Q-ENT-36 | Decline reasons 4200, 4300, 5100, 5200 unreachable | business-choice | PRESERVE (DF-5) | Business choice (DF-5): only 0000, 3100, 4100, and 9000 are produced (Q-COPAUA0C-08); no card-status, account-status, fraud, or expiry checks are added. | n/a |
| Q-ENT-37 | Sample customers fail the online edits | platform | PLATFORM | Migration loads legacy rows without online validation and records each failing rule in a migration report. Screens edit only the fields a user changes (ledger-online Q-COACTUPC-07, D-VALIDATE-CHANGED), so these accounts can be updated without first correcting other stored values. | n/a |
| Q-ENT-38 | ESDSRRDS defines unused USRSEC copies | dead-code | NOT-PORTED | Not migrated. | n/a |
| Q-ENT-39 | CICS files declare RECORDFORMAT(V) for fixed records | platform | PLATFORM | No counterpart. | n/a |
| Q-ENT-40 | CREASTMT SORT OUTREC drops the last 2 timestamp bytes | platform | PLATFORM | Statements read the transaction table directly; no sort copy exists. Output unaffected. | n/a |
| Q-ENT-41 | No writer produces real microseconds | platform | PLATFORM | Stored timestamps keep the precision each COBOL writer produced (hundredths padded with `0000` in batch, `000000` in COBIL00C) when rendered as text. | n/a |
| Q-ENT-42 | Purge tests the approved count twice; root delete removes unexpired details | data-corruption | FIX | Same as Q-CBPAUP0C-02: a summary is deleted only when no detail remains after the expired details are deleted; an unexpired detail is never deleted. | n/a (IMS) |

## jobs.md

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-ACCTFILE-01 | ACCTFILE does not close ACCTDAT in CICS | platform | PLATFORM | Seed load into the account table is a migration step (B-CYCLE); no file close. | n/a |
| Q-ACCTFILE-02 | Rollback reloads the original seed account file | platform | PLATFORM | Rollback restores the database backup taken before POSTTRAN (B-CYCLE), not seed data. | n/a |
| Q-CA7-01 | CA7 triggers form concurrent chains and a loop | user-decision | CHANGE (D-CYCLE) | CA7 schedule not ported; B-CYCLE defines the order. | n/a |
| Q-CA7-02 | CA7 chain lacks TRANBKP, INTCALC, COMBTRAN, TRANIDX | user-decision | CHANGE (D-CYCLE, D-INTEREST) | B-CYCLE runs POSTTRAN and CREASTMT every business date, with INTCALC between them on the first business date of each month; posting appends (Q-CBTRN02C-01). | n/a |
| Q-CA7-03 | Chain B reloads TCATBALF and reference data | user-decision | CHANGE (D-CYCLE) | Reference and category-balance loads are setup only and never scheduled; accumulated category balances are never reset by a schedule. | n/a |
| Q-CA7-04 | Base chain includes the IMS module job | user-decision | CHANGE (D-CYCLE) | The base cycle never depends on a module job; CBPAUP0J runs on its own schedule. | n/a |
| Q-CA7-05 | OPENFIL listings disagree | user-decision | CHANGE (D-CYCLE) | Not ported (no OPENFIL step). | n/a |
| Q-CARDFILE-01 | Second DELETE returns RC 8 | platform | PLATFORM | No counterpart. | n/a |
| Q-CBADMCDJ-01 | Stale CICS definitions | dead-code | NOT-PORTED | Routes come from `app/csd/CARDDEMO.CSD` and the program specs. | n/a |
| Q-CBPAUP0J-01 | Expiry `00` purges every authorization on every run | user-decision | CHANGE (D-PURGE) | (D-PURGE) The expiry-days value is a DAG parameter `expiry_days` defaulting to 5; the JCL's `00` is not carried over. An explicit numeric value is used as given (0 still purges every detail); a non-numeric value falls back to 5 as in the program (BR-CBPAUP0C-01). | n/a |
| Q-CBPAUP0J-02 | Debug `Y` prints account IDs per deletion | platform | PLATFORM | The debug flag maps to DEBUG-level logging, off by default. Account IDs are not card data and are logged unmasked. | n/a |
| Q-COMBTRAN-01 | Duplicate TRAN-IDs fail the reload | data-corruption | FIX | No merge or reload: interest rows are inserted directly with unique IDs (Q-INTCALC-01). | n/a |
| Q-COMBTRAN-02 | Monthly flow merges into a non-empty TRANSACT | user-decision | CHANGE (D-CYCLE) | Control-M flow not ported; no COMBTRAN step (B-CYCLE). | n/a |
| Q-CONTROLM-01 | Daily folder empties TRANSACT without posting | user-decision | CHANGE (D-CYCLE) | Control-M not ported; B-CYCLE applies. | n/a |
| Q-CONTROLM-02 | Monthly folder never ordered; duplicate JOBISN | user-decision | CHANGE (D-CYCLE, D-INTEREST) | Not ported; interest runs monthly as a step of B-CYCLE on the first business date of the month. | n/a |
| Q-CONTROLM-03 | Monthly flow lacks TRANBKP, TRANIDX, CREASTMT | user-decision | CHANGE (D-CYCLE, D-INTEREST) | Not ported; CREASTMT runs every business date after POSTTRAN, and on the first business date of the month after INTCALC (B-CYCLE). | n/a |
| Q-CONTROLM-04 | Weekly DISCGRP reload from seed waits on MNTTRDB2 | user-decision | CHANGE (D-CYCLE) | Not ported: disclosure-group rates are seeded once and never reloaded by a schedule. | n/a |
| Q-CONTROLM-05 | Shared condition can strand a folder; extract never reaches VSAM | user-decision | CHANGE (D-CYCLE) | Not ported; MNTTRDB2 runs on request (B-CYCLE). | n/a |
| Q-CONTROLM-06 | Duplicate and swapped folder names, odd attributes | user-decision | CHANGE (D-CYCLE) | Not ported. | n/a |
| Q-CONTROLM-07 | Weekly MNTTRDB2 has no DAYS | user-decision | CHANGE (D-CYCLE) | Not ported; MNTTRDB2 runs on request. | n/a |
| Q-CREADB21-01 | `TYPRUN=SCAN` on the job card | platform | PLATFORM | Schema creation runs as a migration. | n/a |
| Q-CREADB21-02 | Category table never loaded on a fresh install | platform | PLATFORM | The migration creates and seeds both tables (Q-ENT-34). | n/a |
| Q-CREADB21-03 | DSNTIAD run without PLAN | platform | PLATFORM | No counterpart. | n/a |
| Q-CREADB21-04 | Seed spells type 06 `REVERAL` | platform | PLATFORM (DF-1) | See Q-ENT-34 (seed from the VSAM sample, `Reversal`). | n/a |
| Q-CREASTMT-01 | Stray text after a continuation comma | platform | PLATFORM | No counterpart. | n/a |
| Q-CREASTMT-02 | DCB differs on a delete-only DD | platform | PLATFORM | No counterpart. | n/a |
| Q-CREASTMT-03 | Site-specific volume | platform | PLATFORM | No counterpart. | n/a |
| Q-CREASTMT-04 | Statement covers whatever TRANSACT holds | user-decision | CHANGE (D-HISTORY, D-BATCH-WINDOW) | (D-HISTORY) The transaction table keeps full history (Q-TRANBKP-02), so each CREASTMT run selects only the current cycle's transactions: every transaction row committed before the run starts that no earlier statement run covered (posted by POSTTRAN, inserted by INTCALC, or added online). The run marks those rows as covered in the same database transaction that records the run. Statement content and layout are identical to the COBOL output for the same set of transactions (no new header lines, Q-CBSTM03A-11). For the parity data (first run) the set is the whole table, the same set the COBOL reads. | n/a |
| Q-DEFCUST-01 | DEFCUST defines unused datasets and ends RC 8 | dead-code | NOT-PORTED | Not migrated. | n/a |
| Q-DEFGDGB-01 | DALYREJS GDG defined elsewhere | platform | PLATFORM | Schema migration creates every table, including the rejects table, in one idempotent step. | n/a |
| Q-DEFGDGB-02 | TRANREPT GDG defined twice | platform | PLATFORM | See Q-ENT-16 (retain 10 reports). | n/a |
| Q-DEFGDGD-01 | DEFGDGD is not rerunnable | platform | PLATFORM | Schema migrations are idempotent. | n/a |
| Q-DEFGDGD-02 | Inconsistent backup names | platform | PLATFORM | No counterpart (reference data lives in tables; backups are database backups). | n/a |
| Q-DUSRSECJ-01 | Passwords seeded in clear text | security | FIX | The seed stores BCrypt hashes only (ledger-online Q-COSGN00C-01); demo users exist only in the demo/test seed profile. | n/a |
| Q-DUSRSECJ-02 | `UNIT=SYSAD` typo | platform | PLATFORM | No counterpart. | n/a |
| Q-DUSRSECJ-03 | USRSEC not closed before delete | platform | PLATFORM | No counterpart. | n/a |
| Q-FTPJCL-01 | FTP host, user, and password in clear text | security | FIX | File transfers use SFTP or HTTPS with credentials from the secret store; no credential appears in a job definition or log. | n/a |
| Q-INTCALC-01 | Hard-coded PARM date repeats TRAN-IDs | data-corruption | FIX | INTCALC takes the business date (B-DATE); interest IDs are `YYYYMMDD00` + counter, and at most one INTCALC run per calendar month completes (Q-CBACT04C-08), so IDs never repeat. | none needed (harness passes PARM `2022071800`; output unchanged) |
| Q-INTCALC-02 | Daily (runbook) versus monthly (Control-M) | user-decision | CHANGE (D-INTEREST) | Interest runs monthly, on the first business date of each month, after that day's POSTTRAN and before its CREASTMT (B-CYCLE). The runbook's daily INTCALC is not used. | n/a |
| Q-INTRDRJ2-01 | Internal-reader copy job with wrong comment and HLQ | platform | PLATFORM | No counterpart. | n/a |
| Q-MNTTRDB2-01 | MNTTRDB2 is a template and not idempotent | platform | PLATFORM | The job takes the input file as a parameter; COBTUPDT behavior on rerun is preserved (an `A` record for an existing key fails, as specified). | n/a |
| Q-PROC-TRANREPT-01 | PROC label REPROC collides with the real REPROC | platform | PLATFORM | No counterpart. | n/a |
| Q-PRTCATBL-01 | OUTREC builds 41 bytes for a 40-byte record | platform | PLATFORM | The category-balance listing writes the same first 40 bytes per line. | n/a |
| Q-PRTCATBL-02 | Negative category balances print without a sign | functional-defect | FIX | A negative balance prints with a trailing `-` after the edited value (for example `000000012.50-`); positive values are unchanged. | n/a (DFSORT step; spec-derived test) |
| Q-READCARD-01 | Listing jobs display full card numbers and customer PII | security | FIX | Listing jobs are ported with masked output (B-MASK; Q-CBACT02C-01, Q-CBACT03C-02, Q-CBCUS01C-02). | RP-CBACT02C-1, RP-CBACT03C-2, RP-CBCUS01C-2 |
| Q-READCUST-01 | `NOTIFY=&SYUID` typo | platform | PLATFORM | No counterpart. | n/a |
| Q-RUNBOOK-01 | Interest frequency conflict; monthly rate formula | user-decision | CHANGE (D-CYCLE, D-INTEREST) | Interest runs once a month (B-CYCLE) with the COBOL formula unchanged (annual rate / 1200), so each run charges one month's interest and resets the cycle totals once a month. | n/a |
| Q-RUNBOOK-02 | Scripts reload every master from seed before posting; FTP submissions overlap | user-decision | CHANGE (D-CYCLE) | The daily cycle never reloads master data; a demo reset is a separate seed command. Steps run strictly in order (B-CYCLE). | n/a |
| Q-RUNBOOK-03 | README misdescribes CLOSEFIL/OPENFIL/TRANBKP | platform | PLATFORM | Target documentation describes the B-CYCLE steps. | n/a |
| Q-TRANBKP-01 | Delete step runs even when the backup failed | data-corruption | FIX | No target step deletes transactions. The pre-cycle database backup must succeed before POSTTRAN starts; if it fails the cycle stops. | n/a |
| Q-TRANBKP-02 | TRANBKP empties TRANSACT so only the latest cycle remains | user-decision | CHANGE (D-HISTORY, D-BATCH-WINDOW) | (D-HISTORY) The transaction table keeps full history. COTRN00C lists every transaction, including earlier cycles and online-added ones; nothing is moved to backup generations. Statements cover only the current cycle (Q-CREASTMT-04). | n/a |
| Q-TRANBKP-03 | Redefine omits the AIX | platform | PLATFORM | No counterpart (indexes persist). | n/a |
| Q-TRANEXTR-01 | Extract refreshes only sequential files | platform | PLATFORM | One shared reference table (Q-ENT-34), so no extract or refresh step exists. | n/a |
| Q-TRANEXTR-02 | Restart after a partial TRANEXTR fails | platform | PLATFORM | No counterpart. | n/a |
| Q-TRANFILE-01 | TRANFILE closes the CARDXREF AIX path | platform | PLATFORM | No counterpart. | n/a |
| Q-TRANFILE-02 | TRANSACT AIX has no consumer | platform | PLATFORM | An index on the transaction processed timestamp is created for the CORPT00C/CBTRN03C date filter. | n/a |
| Q-TRANIDX-01 | TRANIDX rerun builds into an existing AIX | platform | PLATFORM | No counterpart. | n/a |
| Q-TRANREPT-01 | Two steps named STEP05R | platform | PLATFORM | No counterpart. | n/a |
| Q-TRANREPT-02 | Report job writes into the backup GDG | data-corruption | FIX | The report reads the live transaction table and writes nothing else; backups are separate. | n/a |
| Q-TRANREPT-03 | Hard-coded date range and hand-made DATEPARM | functional-defect | FIX | Same as Q-CBTRN03C-06: one start/end date pair taken from the report request. | n/a |
| Q-TRANREPT-04 | Card number sorted as ZD | platform | PLATFORM | Sort by card number as text (same order for digit-only values), then as the COBOL report does. | n/a |
| Q-UNLDPADB-01 | First run fails on `DISP=(OLD,...)` | platform | PLATFORM | The unload replaces its output files whether or not they exist. | n/a |
| Q-XREFFILE-01 | XREFFILE depends on CLOSEFIL | platform | PLATFORM | No counterpart (online stays up). | n/a |

## CBACT01C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-CBACT01C-01 | Cycle debit set to 2525.00 only when zero, otherwise stale | functional-defect | FIX | Zero cycle debit still writes the constant 2525.00 (preserved as a business choice, classification policy rule 5); a non-zero cycle debit writes the account's own ACCT-CURR-CYC-DEBIT instead of the previous record's value. | n/a (needs COBDATFT; spec-derived tests) |
| Q-CBACT01C-02 | Reissue date bytes 9-10 uninitialized | functional-defect | FIX | OUT-ACCT-REISSUE-DATE is `YYYYMMDD` followed by 2 spaces. | n/a (needs COBDATFT) |
| Q-CBACT01C-03 | Array and VBRC records carry hard-coded amounts | business-choice | PRESERVE | Business choice: ported as the same data-format demonstration with the same constants. | n/a |
| Q-CBACT01C-04 | WRITE status `10` treated as success | functional-defect | FIX | Only status `00` is success; any other status takes the existing error path (B-ABEND). | n/a |
| Q-CBACT01C-05 | Same write error text for three files | cosmetic | PRESERVE | Text kept verbatim (D-COSMETIC). | n/a |
| Q-CBACT01C-06 | Output files never closed explicitly | platform | PLATFORM | Every output is flushed and closed at job end. | n/a |
| Q-CBACT01C-07 | Group ID blank for every sample account | data-corruption | FIX | Fixed by the Q-ENT-02 migration rule: OUT-ACCT-GROUP-ID is `A000000000` for the sample accounts. | RP-DATA-1 |
| Q-CBACT01C-08 | COBDATFT is assembler and ignores errors | platform | PLATFORM | Library date formatter with the same output; an invalid input date takes B-ABEND instead of being ignored. | n/a |
| Q-CBACT01C-09 | Account displayed twice; labels keep `EXPIRAION` | cosmetic | PRESERVE | Both displays and labels kept verbatim (no card or customer PII in the account record). | n/a |

## CBACT02C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-CBACT02C-01 | Full card number and CVV printed to SYSOUT | security | FIX | Each card line shows `************` + last 4 in bytes 1-16 and `***` in the CVV bytes 28-30 (B-MASK); 53 lines as before. | RP-CBACT02C-1 |
| Q-CBACT02C-02 | Status prefix `FILE STATUS IS: NNNN` | cosmetic | PRESERVE | Text kept verbatim. | n/a |
| Q-CBACT02C-03 | Obscure APPL-RESULT arithmetic | platform | PLATFORM | Same outcomes (0 or 12) with direct assignment. | n/a |
| Q-CBACT02C-04 | CEE3ABD missing off z/OS | platform | PLATFORM | B-ABEND. | n/a |
| Q-CBACT02C-05 | Redundant END-OF-FILE test | dead-code | NOT-PORTED | No observable behavior. | n/a |

## CBACT03C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-CBACT03C-01 | Each record displayed twice | functional-defect | FIX | Each cross-reference record is displayed once: 53 lines instead of 103 for the sample. | RP-CBACT03C-1 |
| Q-CBACT03C-02 | Full card numbers in SYSOUT | security | FIX | Card number shown as `************` + last 4 (B-MASK). | RP-CBACT03C-2 |
| Q-CBACT03C-03 | CEE3ABD missing off z/OS | platform | PLATFORM | B-ABEND. | n/a |

## CBACT04C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-CBACT04C-01 | Last account never updated | functional-defect | FIX | The last account receives its interest and its cycle totals are reset. Sample: account `00000000050` balance 1945.87 becomes 1964.64 and cycle credit and debit become 0.00; SYSTRAN and SYSOUT unchanged. | RP-CBACT04C-1 |
| Q-CBACT04C-02 | Interest truncated; negative interest; category balance never reset | business-choice | PRESERVE | Business choice (Q-ENT-11). | n/a |
| Q-CBACT04C-03 | Xref read by non-unique alternate key without duplicates | functional-defect | FIX (DF-6) | Accounts with several cards are processed; the interest transaction carries the lowest card number of the account (Q-ENT-32, DF-6). Sample data has one card per account, so output is unchanged. | RP-CBACT04C-2 (compile-checked; stamps the first card in alternate-key order) |
| Q-CBACT04C-04 | DISCGRP open error says `DALY REJECTS FILE` | cosmetic | PRESERVE | Text kept verbatim. | n/a |
| Q-CBACT04C-05 | Every sample lookup falls back to DEFAULT with a warning | data-corruption | FIX | After the Q-ENT-02 migration the sample accounts find group `A000000000`; the 100 "missing" lines disappear (SYSOUT 303 to 103 lines) and amounts are unchanged. A blank group still falls back to `DEFAULT` with the same message pair. | RP-DATA-1 |
| Q-CBACT04C-06 | TRAN-RECORD never initialized | functional-defect | FIX | Interest rows have spaces after the description and in FILLER. | none needed (GnuCOBOL output already has spaces) |
| Q-CBACT04C-07 | Cycle totals reset only for accounts with category balances, even without interest | business-choice | PRESERVE | Business choice: same reset rule. | n/a |
| Q-CBACT04C-08 | PARM unchecked; reused PARM repeats IDs; counter wraps | data-corruption | FIX (D-INTEREST) | B-DATE validation. A run whose business date falls in a calendar month that already has a completed INTCALC run is refused with exit code 8 and the **NEW** line `INTEREST ALREADY CALCULATED FOR YYYY-MM` (year and month of the business date), so interest is never charged twice in a month (D-INTEREST). A rerun after an abended run in the same month processes only the accounts that have no interest row for that month. More than 999,999 interest rows in one run takes B-ABEND instead of wrapping. | none needed (harness passes a valid PARM; output unchanged) |
| Q-CBACT04C-09 | Interest original timestamp is processing time | business-choice | PRESERVE | Business choice: same timestamps. | n/a |
| Q-CBACT04C-10 | Record count never reported; fees stub | dead-code | NOT-PORTED | No count output and no fee logic. | n/a |
| Q-CBACT04C-11 | Every category-balance record displayed | cosmetic | PRESERVE | Displays kept (no card data). | n/a |

## CBCUS01C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-CBCUS01C-01 | Each record displayed twice | functional-defect | FIX | Each customer is displayed once: 53 lines instead of 103. | RP-CBCUS01C-1 |
| Q-CBCUS01C-02 | SSN, government ID, DOB, EFT account in SYSOUT | security | FIX (D-FIX-SEC) | B-MASK: SSN `*****` + last 4, government ID and DOB all `*`, EFT account `******` + last 4. Names, addresses, and phone numbers are not in the B-MASK list and stay unmasked in the listing (PRESERVE; D-PII-FILES leaves job-log masking to D-FIX-SEC, which covers only card, CVV, SSN, government ID, DOB, and EFT data). | RP-CBCUS01C-2 |
| Q-CBCUS01C-03 | `CUSTOMER FILE` vs `CUSTFILE` wording | cosmetic | PRESERVE | Text kept verbatim. | n/a |
| Q-CBCUS01C-04 | `NOTIFY=&SYUID` typo | platform | PLATFORM | No counterpart. | n/a |
| Q-CBCUS01C-05 | CEE3ABD missing off z/OS | platform | PLATFORM | B-ABEND. | n/a |

## CBTRN01C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-CBTRN01C-01 | Lookups repeated after end of file | functional-defect | FIX | No lookup after end of file: 300 xref reads for 300 records (was 301); an empty input file prints no lookup messages. | RP-CBTRN01C-1 |
| Q-CBTRN01C-02 | Close error shows CUSTFILE-STATUS | functional-defect | FIX | The DALYTRAN close error shows DALYTRAN-STATUS; the text `ERROR CLOSING CUSTOMER FILE` stays verbatim. | none needed (error path; sample output unchanged) |
| Q-CBTRN01C-03 | Non-23 read errors ignored, stale data reused | data-corruption | FIX | Any status other than `00` or `23` on XREFFILE or ACCTFILE takes B-ABEND. | none needed (error path) |
| Q-CBTRN01C-04 | Unused files opened | platform | PLATFORM | The job needs only the daily file, xref, and account data. | n/a |
| Q-CBTRN01C-05 | Full card numbers displayed | security | FIX | B-MASK for `CARD NUMBER: `, the raw record (bytes 263-274), and the "could not be verified" line. | RP-CBTRN01C-2 |
| Q-CBTRN01C-06 | Not scheduled; possible predecessor of CBTRN02C | user-decision | CHANGE (D-SCOPE) | Ported as an on-request validation job; not in B-CYCLE. | n/a |

## CBTRN02C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-CBTRN02C-01 | TRANSACT opened OUTPUT, so history cannot accumulate | user-decision | CHANGE (D-HISTORY, D-BATCH-WINDOW) | (D-HISTORY) Posted rows are appended to the persistent transaction table (Q-TRANBKP-02); earlier cycles stay, and the next statement run covers only rows not yet covered (Q-CREASTMT-04). | none needed (harness starts with an empty TRANSACT) |
| Q-CBTRN02C-02 | Limit test uses cycle credit minus cycle debit | business-choice | PRESERVE | Business choice (Q-ENT-09). | n/a |
| Q-CBTRN02C-03 | Reason 103 overwrites 102; string expiry compare | business-choice | PRESERVE | Business choice: one reason per reject, same compare. | n/a |
| Q-CBTRN02C-04 | Reason 109 on REWRITE failure is dead | functional-defect | FIX | If the account update fails, the record's unit of work (B-UOW) is rolled back and the record is written to the rejects with reason 109 `ACCOUNT RECORD NOT FOUND`; it counts as rejected. | none needed (error path) |
| Q-CBTRN02C-05 | Rejects close error shows XREFFILE-STATUS | functional-defect | FIX | The message shows DALYREJS-STATUS; text verbatim. | none needed (error path) |
| Q-CBTRN02C-06 | Non-23 read errors reuse the previous records | data-corruption | FIX | Any status other than `00` or `23` on xref or account reads takes B-ABEND. | none needed (error path) |
| Q-CBTRN02C-07 | No unit of work | data-corruption | FIX | B-UOW: one database transaction per daily record; restart reruns the remaining records of the same file (already posted IDs are skipped by the unique key). | none needed (error path) |
| Q-CBTRN02C-08 | ASCII sample uses EBCDIC sign overpunch | functional-defect | FIX | Signed zoned fields are decoded with EBCDIC overpunch semantics in the loader and the parity harness. Expected sample result: 300 processed, 38 rejected (all `0102 OVERLIMIT TRANSACTION`), RC 4. The 43 in `AGENTS.md` and the runbook is an ASCII-decoding artifact and should read 38. | RP-BUILD-1 |
| Q-CBTRN02C-09 | TRAN-RECORD FILLER never initialized | functional-defect | FIX | FILLER written as spaces. | none needed (GnuCOBOL output already has spaces) |
| Q-CBTRN02C-10 | Category key from xref; rejects contain full card numbers | security | FIX | Key derivation preserved. Reject records keep their layout and card number; reject files are written mode 600 and the rejects table is readable only by the batch and ADMIN roles. | none needed (content unchanged) |
| Q-CBTRN02C-11 | WS-TEMP-BAL too small | data-corruption | FIX | Limit sum uses full precision (13 digits); no high-order truncation. Sample output unchanged (300 processed, 38 rejected, RC 4). | RP-CBTRN02C-1 |
| Q-CBTRN02C-12 | New category-balance record keeps leftover FILLER | functional-defect | FIX | A new category-balance record's FILLER is spaces, as for TRAN-RECORD (Q-CBTRN02C-09). Sample: the 50 created records end in 22 spaces instead of 22 zeros; SYSOUT, counts, and balances unchanged. | RP-CBTRN02C-2 |

## CBTRN03C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-CBTRN03C-01 | NEXT SENTENCE ends the report at the first out-of-range record | functional-defect | FIX | Out-of-range records are skipped and the report continues. | RP-CBTRN03C-1 |
| Q-CBTRN03C-02 | Last amount added twice | functional-defect | FIX | No double add. Sample (262 posted records): last page total 1,725.65 becomes 959.99; grand total 78,720.36 becomes 77,954.70. | RP-CBTRN03C-2 |
| Q-CBTRN03C-03 | Last account total never printed | functional-defect | FIX | The last card's account total is printed before the last page total (sample: `1,609.10`); report grows from 451 to 453 lines. | RP-CBTRN03C-2 |
| Q-CBTRN03C-04 | Irregular page breaks | business-choice | PRESERVE | Business choice: same line-counter rule. | n/a |
| Q-CBTRN03C-05 | Totals per card labelled "Account Total" | business-choice | PRESERVE | Business choice: totals per card, sorted by card. | n/a |
| Q-CBTRN03C-06 | Date range in JCL and in DATEPARM | functional-defect | FIX | One start/end date pair from the report request drives both the selection and the header. A USER request is limited to linked accounts (D-REPORT). | none needed (harness passes the same range to both) |
| Q-CBTRN03C-07 | Empty DATEPARM gives an empty report with RC 0 | functional-defect | FIX | A missing or invalid date range fails the job with exit code 12 and the **NEW** line `INVALID OR MISSING REPORT DATE RANGE`. | none needed (sample DATEPARM present) |
| Q-CBTRN03C-08 | Raw records with full card numbers on SYSOUT | security | FIX | B-MASK on the raw record display (bytes 263-274). | RP-CBTRN03C-3 |
| Q-CBTRN03C-09 | Descriptions truncated to 15 and 29 characters | cosmetic | PRESERVE | Truncation kept. | n/a |
| Q-CBTRN03C-10 | Totals overflow silently | data-corruption | FIX | Totals accumulate exactly; the report layout is unchanged. A value that does not fit its edit picture fails the job (B-ABEND) instead of printing a truncated amount. | none needed (sample totals fit) |
| Q-CBTRN03C-11 | Blank processing timestamp excluded | business-choice | PRESERVE | Business choice: rows with a blank processing date are never selected (now skipped, not ending the report, Q-CBTRN03C-01). | n/a |

## CBSTM03A

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-CBSTM03A-01 | PSA/TCB/TIOT walk | platform | PLATFORM | No control-block walk; the job log lists the configured input and output locations. | RP-CBSTM03A-1 (precondition) |
| Q-CBSTM03A-02 | ALTER and GO TO open sequence | platform | PLATFORM | Files opened in sequence; same behavior. | n/a |
| Q-CBSTM03A-03 | Empty TRNXFILE aborts the job | functional-defect | FIX | With no transactions, a statement with no transaction lines is produced for every xref card. | RP-CBSTM03A-4 |
| Q-CBSTM03A-04 | Table limits 51 cards and 10 transactions per card | data-corruption | FIX | No limit on cards or transactions per card. | RP-CBSTM03A-2 (raises limits to 500 and 50) |
| Q-CBSTM03A-05 | Table scan relies on ascending card order | platform | PLATFORM | Lookup by card number; statements still produced in ascending card order. | n/a |
| Q-CBSTM03A-06 | Statements per card; cards absent from xref dropped | business-choice | PRESERVE | Business choice: same. | n/a |
| Q-CBSTM03A-07 | Name and city cut at the first space | functional-defect | FIX | Full multi-word names and cities; a blank middle name gives one space between first and last name, so the HTML name is complete. Sample: `New Aricchester` instead of `New`. | RP-CBSTM03A-3 |
| Q-CBSTM03A-08 | Descriptions cut to 49 characters | cosmetic | PRESERVE | Truncation kept. | n/a |
| Q-CBSTM03A-09 | Number formats lose digits; balance shows leading zeros | data-corruption | FIX | Formats preserved byte for byte, including the leading-zero balance. A value that does not fit its picture fails the run (B-ABEND) instead of printing a truncated number. | none needed (sample values fit) |
| Q-CBSTM03A-10 | Concatenated HTML documents; no escaping | security | FIX | Values inserted into HTML escape `&`, `<`, and `>`. The file stays one concatenation of per-statement documents with the same padding. | none needed (sample has no such characters) |
| Q-CBSTM03A-11 | No statement date or period | business-choice | PRESERVE (D-HISTORY, DF-4) | Business choice (D-HISTORY, DF-4): statements keep the COBOL layout with no statement date, statement period, payment due date, or minimum payment, so plain and HTML output are identical to the COBOL's for the same transactions (Q-CREASTMT-04). | n/a |
| Q-CBSTM03A-12 | SORT OUTREC drops 2 timestamp bytes | platform | PLATFORM | No sort copy (Q-ENT-40). | n/a |
| Q-CBSTM03A-13 | CREASTMT JCL oddities | platform | PLATFORM | No counterpart. | n/a |
| Q-CBSTM03A-14 | CEE3ABD called without arguments | platform | PLATFORM | B-ABEND. | n/a |
| Q-CBSTM03A-15 | Open and close accept status `04` | platform | PLATFORM | No record-attribute mismatch exists in the target. | n/a |

## CBSTM03B

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-CBSTM03B-01 | Unsupported operations return the previous status | platform | PLATFORM | Typed read-only repositories; unsupported operations do not exist. | n/a |
| Q-CBSTM03B-02 | Unknown DD name looks like success | platform | PLATFORM | No DD-name dispatch. | n/a |
| Q-CBSTM03B-03 | Key length not range-checked | platform | PLATFORM | No key-length parameter. | n/a |
| Q-CBSTM03B-04 | Subroutine only demonstrates a CALL interface | platform | PLATFORM | Four repositories: sequential transactions and xref, customer and account by ID. | n/a |

## CBEXPORT

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-CBEXPORT-01 | Export carries PAN, CVV, SSN, DOB, government ID in clear | security | PRESERVE (D-PII-FILES) | (D-PII-FILES) The export keeps its content (full PAN, CVV, SSN, DOB, government ID) as a data-interchange contract so CBIMPORT round-trips. The file is written mode 600 (owner read and write only). Nothing from it is written to SYSOUT or a log (B-MASK). | n/a |
| Q-CBEXPORT-02 | Branch `0001` and region `NORTH` hard-coded | business-choice | PRESERVE | Business choice: same constants. | n/a |
| Q-CBEXPORT-03 | Timestamp ends `.00` plus 4 spaces, local time | business-choice | PRESERVE | Business choice: same format for file compatibility. | n/a |
| Q-CBEXPORT-04 | Mixed numeric encodings in one record | platform | PLATFORM | Same per-field encodings via the copybook codec (Q-ENT-19). | n/a |
| Q-CBEXPORT-05 | Non-numeric zoned data can raise S0C7 | platform | PLATFORM | Typed database columns cannot hold non-numeric values. | n/a |
| Q-CBEXPORT-06 | "Sequential" file is a KSDS keyed on sequence | platform | PLATFORM | Records written and read in sequence-number order. | n/a |
| Q-CBEXPORT-07 | Inconsistent count message labels | cosmetic | PRESERVE | Text kept verbatim. | n/a |
| Q-CBEXPORT-08 | CEE3ABD without arguments | platform | PLATFORM | B-ABEND. | n/a |
| Q-CBEXPORT-09 | Comment with asterisks in the sequence area | dead-code | NOT-PORTED | No behavior. | n/a |

## CBIMPORT

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-CBIMPORT-01 | JCL has no CARDOUT DD | platform | PLATFORM | The target job definition declares the card output. | n/a |
| Q-CBIMPORT-02 | Validation is a stub that always succeeds | business-choice | PRESERVE | Business choice (classification policy rule 5): same success text (`CBIMPORT: Import validation completed`, `CBIMPORT: No validation errors detected`); no reconciliation or checksum is added. | n/a |
| Q-CBIMPORT-03 | Output FILLER bytes are buffer leftovers | functional-defect | FIX | FILLER is spaces in every output record, so outputs match the original files byte for byte. | RP-CBIMPORT-1 |
| Q-CBIMPORT-04 | Error line truncates the sequence number | functional-defect | FIX | The error line shows the full 9-digit sequence number; the timestamp field is unchanged. | RP-CBIMPORT-2 |
| Q-CBIMPORT-05 | ERROUT write failure not fatal | functional-defect | FIX | A failed error-record write takes B-ABEND. | none needed (error path) |
| Q-CBIMPORT-06 | No duplicate or referential checks | business-choice | PRESERVE | Business choice: records written as they come. | n/a |
| Q-CBIMPORT-07 | Invalid packed or binary value can raise S0C7 | functional-defect | FIX | A record with an undecodable numeric field goes to ERROUT with the **NEW** message `INVALID NUMERIC DATA` and the import continues. | none needed (sample data clean) |
| Q-CBIMPORT-08 | NEW,CATLG outputs fail on rerun | platform | PLATFORM | Outputs are replaced on rerun. | n/a |
| Q-CBIMPORT-09 | CEE3ABD without arguments | platform | PLATFORM | B-ABEND. | n/a |
| Q-CBIMPORT-10 | Imported data holds PAN, CVV, SSN in clear | security | PRESERVE (D-PII-FILES) | (D-PII-FILES) Same content as the export (Q-CBEXPORT-01); every output file is written mode 600. Values loaded into the database follow Q-ENT-33. | n/a |

## COBSWAIT

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COBSWAIT-01 | Non-numeric wait value unchecked | functional-defect | FIX | The wait value must be an unsigned integer of 1 to 8 digits (hundredths of a second; `00000000` waits zero time and ends with RC 0, TS-COBSWAIT-02); otherwise exit code 12 with the **NEW** line `INVALID WAIT TIME`. | n/a (assembler dependency) |
| Q-COBSWAIT-02 | "PARM" is read from SYSIN | platform | PLATFORM | The wait value is a job parameter. | n/a |
| Q-COBSWAIT-03 | MVI save-area flag | platform | PLATFORM | No assembler. | n/a |
| Q-COBSWAIT-04 | Needs MVSWAIT and STIMER | platform | PLATFORM | A plain sleep. | n/a |
| Q-COBSWAIT-05 | Timing-based job synchronization | user-decision | CHANGE (D-CYCLE) | B-CYCLE uses explicit dependencies; WAITSTEP is not in the cycle. The utility stays available (D-SCOPE). | n/a |

## COTRTLIC

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COTRTLIC-01 | Row selections not cleared on a filter change | functional-defect | FIX | A changed filter clears every row selection before the list is re-read; PF10 never acts on a different record. | n/a (spec-derived tests; CICS/Db2) |
| Q-COTRTLIC-02 | Duplicate PF7 WHEN | dead-code | NOT-PORTED | No behavior. | n/a |
| Q-COTRTLIC-03 | Unreachable PF3 and re-entry branches | dead-code | NOT-PORTED | Not ported. | n/a |
| Q-COTRTLIC-04 | Delete clears the signed-on user; deleted row stays shown | functional-defect | FIX | The session user is kept (C-SESSION); after a delete the list is re-read so the row disappears. | n/a |
| Q-COTRTLIC-05 | No authorization | security | FIX | C-ROLE: ADMIN only. | n/a |
| Q-COTRTLIC-06 | Page number wraps after 9 | functional-defect | FIX | Page numbers 1-999 display correctly; PF7 on page 1 gives the first-page message. | n/a |
| Q-COTRTLIC-07 | Backward read fails with SQLCODE 100 | functional-defect | FIX | A backward page that reaches the start shows a full first page with no error. | n/a |
| Q-COTRTLIC-08 | Forward fetch error labelled `close` | cosmetic | PRESERVE | Text kept verbatim. | n/a |
| Q-COTRTLIC-09 | Case-sensitive description filter; `%` and `_` not escaped | business-choice | PRESERVE | Business choice: same matching. | n/a |
| Q-COTRTLIC-10 | Descriptions stored with trailing spaces | platform | PLATFORM | Stored trimmed; displayed padded as before; comparisons ignore trailing spaces. | n/a |
| Q-COTRTLIC-11 | "No change detected" with PF10 still saving | business-choice | PRESERVE | Business choice: same messages and PF10 behavior. | n/a |
| Q-COTRTLIC-12 | Inconsistent message precedence | business-choice | PRESERVE | Business choice: same precedence. | n/a |
| Q-COTRTLIC-13 | DSNTIAC called with zero length | functional-defect | FIX | Database errors show the database error text in the message line, as DSNTIAC was meant to. | n/a |
| Q-COTRTLIC-14 | List read error not tested | business-choice | PRESERVE | Business choice: rows read so far are shown with the error text. | n/a |
| Q-COTRTLIC-15 | Shared LOW-VALUES state flags | platform | PLATFORM | Explicit server-side state; same visible behavior. | n/a |
| Q-COTRTLIC-16 | Unused eighth map row | dead-code | NOT-PORTED | Seven rows. | n/a |
| Q-COTRTLIC-17 | Update/delete key read from the screen | security | FIX | The key comes from the server-side copy of the row. | n/a |
| Q-COTRTLIC-18 | Positive SQLCODE warnings unhandled | functional-defect | FIX | A warning counts as success: commit and the normal success message. | n/a |
| Q-COTRTLIC-19 | No optimistic concurrency | concurrency | FIX | The update applies only if the row still has the values read; otherwise `Deadlock. Someone else updating ?` (reused from ERR-COTRTLIC-11) and the list is re-read. | n/a |
| Q-COTRTLIC-20 | Boundary message shown on arrival at first or last page | functional-defect | FIX | The boundary message appears only when PF7 or PF8 is pressed at the boundary. | n/a |
| Q-COTRTLIC-21 | Filter error shows old rows with page 0 | functional-defect | FIX | Paging variables are kept when a filter is in error; the old rows show their page number. | n/a |
| Q-COTRTLIC-22 | Count-query failure reported as "no matching records" | functional-defect | FIX | When the filter count query fails, the message line shows the database error built per ERR-COTRTLIC-02 with action `Error reading TRANSACTION_TYPE table ` and the database error text (Q-COTRTLIC-13). `No Records found for these filter conditions` is not shown, no filter is highlighted, and the select fields are not protected; as for other input errors the list is not re-read (rows and page number kept, Q-COTRTLIC-21). TS-COTRTLIC-23 changes accordingly. | n/a |
| Q-COTRTLIC-23 | Delete or update confirmed without its prompt | data-corruption | FIX | PF10 performs only the action (delete or update) on the row that the user was prompted to confirm. The confirmation is armed only when `Delete HIGHLIGHTED row ? Press F10 to confirm` or `Update HIGHLIGHTED row. Press F10 to save` is shown for that action and row. Any other key, or any change of the selected row, its action code, the record in that row, or a filter, disarms it; PF10 is then processed as ENTER, so the input is validated again and a new prompt is shown. A confirmed action that fails and stays pending (ERR-COTRTLIC-11 to -14, Q-COTRTLIC-19) stays armed for the same row. A delete never runs unless its delete prompt was shown for that row. TS-COTRTLIC-24 and TS-COTRTLIC-25 change accordingly. | n/a |
| Q-COTRTLIC-24 | Paging key pressed with a filter change | functional-defect | FIX | A filter change entered together with PF7 or PF8 is applied as a new search, as ENTER would apply it: page 1 of the new results is shown with no boundary message (`No previous pages to display`, `No more pages for these search conditions`, `No more pages to display`) and no database error, consistent with Q-COTRTLIC-07, -20, and -21. A filter in error is handled as for ENTER (VR-COTRTLIC-01, -02). TS-COTRTLIC-26 and TS-COTRTLIC-27 change accordingly. | n/a |
| Q-COTRTLIC-25 | Pending update moves to another record after "Record not found" | data-corruption | FIX | After the `Record not found. Deleted by others ? ` error (ERR-COTRTLIC-10) the list is re-read and no update or delete is left pending: the select codes are cleared, every row shows its database values, and a second PF10 is processed as ENTER, so it cannot change another record. TS-COTRTLIC-20 changes accordingly. | n/a |

## COTRTUPC

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COTRTUPC-01 | Type edit bypassed on the not-found screen | functional-defect | FIX | The type edit and zero padding always run before a lookup or add; non-numeric or unpadded codes cannot be inserted. | n/a (spec-derived tests; CICS/Db2) |
| Q-COTRTUPC-02 | Save failures reported as success | functional-defect | FIX | A failed update or insert reaches state `F` with `Update of record failed` and the SQL message; `Changes committed to database` only after a commit. | n/a |
| Q-COTRTUPC-03 | SQL messages include binary length bytes and lose tokens | functional-defect | FIX | Messages use the error text without length bytes, once each. | n/a |
| Q-COTRTUPC-04 | INSERT error shows the UPDATE SQLCODE | functional-defect | FIX | The message shows the INSERT SQLCODE. | n/a |
| Q-COTRTUPC-05 | Direct start runs without a signed-on user | security | FIX | C-ROLE and C-SESSION: no session gives the sign-on screen; PF3 returns to the real caller. | n/a |
| Q-COTRTUPC-06 | Failed delete leaves state `8` and the next key abends | functional-defect | FIX | State becomes `6` after a failed delete. | n/a |
| Q-COTRTUPC-07 | PF5 in state `9` abends | functional-defect | FIX | PF5 in state `9` is an invalid key with the program's invalid-key handling. | n/a |
| Q-COTRTUPC-08 | ABEND-ROUTINE hides code and default text | functional-defect | FIX | The abend screen shows code `0001` for "UNEXPECTED DATA SCENARIO" and `UNEXPECTED ABEND OCCURRED.` when no message is set. | n/a |
| Q-COTRTUPC-09 | Db2 lookup error treated as not found | functional-defect | FIX | A lookup error shows the SQL message and offers no add. | n/a |
| Q-COTRTUPC-10 | No authorization; PF3 sets admin | security | FIX | C-ROLE: ADMIN only; the role never changes on navigation. | n/a |
| Q-COTRTUPC-11 | Case-only description changes rejected | business-choice | PRESERVE | Business choice: same case-insensitive change check. | n/a |
| Q-COTRTUPC-12 | Description stored with trailing spaces | platform | PLATFORM | As Q-COTRTLIC-10. | n/a |
| Q-COTRTUPC-13 | Description edit stricter than COBTUPDT | business-choice | PRESERVE | Business choice: same screen edit; COBTUPDT now applies it too (Q-COBTUPDT-05). | n/a |
| Q-COTRTUPC-14 | NUMVAL lets signs and decimal points pass | functional-defect | FIX | Type code accepts one or two digits only, 01-99, with the existing type-code messages. | n/a |
| Q-COTRTUPC-15 | Dead states, messages, and copybooks | dead-code | NOT-PORTED | Not ported. | n/a |
| Q-COTRTUPC-16 | Cancel redisplays session values; re-read branch dead | dead-code | NOT-PORTED | Visible behavior kept (old values from the session); dead branch not ported. | n/a |
| Q-COTRTUPC-17 | No optimistic locking; deleted row silently re-inserted | concurrency | FIX | Save applies only if the row is unchanged since it was read; an update never inserts. A conflict shows `Could not lock record for update` (reused, ERR-COTRTUPC-03). | n/a |
| Q-COTRTUPC-18 | State `S` shows "Enter transaction type to be maintained" | functional-defect | FIX | State `S` shows `Update transaction type details shown.` (existing text that never appeared). | n/a |
| Q-COTRTUPC-19 | Typed type after a reset is ignored; deleted row stays shown | functional-defect | FIX | Input typed after a completed action is processed on the next key; after `Delete successful.` the fields are cleared. | n/a |
| Q-COTRTUPC-20 | LOW-VALUES moved twice to TRTYPCDO | dead-code | NOT-PORTED | No behavior. | n/a |
| Q-COTRTUPC-21 | Key taken from the protected screen field | security | FIX | The key comes from the server-side session. | n/a |
| Q-COTRTUPC-22 | First entry clears account context | platform | PLATFORM | C-SESSION: navigation does not clear unrelated context. | n/a |

## COBTUPDT

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COBTUPDT-01 | OPEN or READ failure loops forever | functional-defect | FIX | An OPEN or non-end-of-file READ error ends the job with exit code 12 after `OPEN FILE NOT OK` or the read error line. | n/a (Db2) |
| Q-COBTUPDT-02 | Return message keeps the previous tail | functional-defect | FIX | The message is cleared before each build. | n/a |
| Q-COBTUPDT-03 | One unit of work with a table lock | user-decision | CHANGE (D-BATCH-WINDOW) | B-UOW: one transaction per input file with row locks; online screens are blocked only on rows the job touches. | n/a |
| Q-COBTUPDT-04 | -911 rolls back silently while later records commit | data-corruption | FIX | Any negative SQLCODE stops the run, rolls back the whole file, and ends with exit code 12; the summary (Q-COBTUPDT-07) states that nothing was applied. | n/a |
| Q-COBTUPDT-05 | No input validation | functional-defect | FIX | Same rules as COTRTUPC (type 01-99, two digits; description letters, digits, spaces). A failing record gets the existing `ERROR: TYPE NOT VALID` and is skipped. | n/a |
| Q-COBTUPDT-06 | Descriptions padded to 50 | platform | PLATFORM | As Q-COTRTLIC-10. | n/a |
| Q-COBTUPDT-07 | Warnings silent; no summary | functional-defect | FIX | Warnings are logged with the type code; the job ends with a **NEW** summary of records read, added, updated, deleted, and rejected. | n/a |
| Q-COBTUPDT-08 | Requires 53-byte records | platform | PLATFORM | Accepts 53-byte records and 80-byte card images (columns 54-80 ignored). | n/a |
| Q-COBTUPDT-09 | Dead or odd code | dead-code | NOT-PORTED | Not ported. | n/a |
| Q-COBTUPDT-10 | Plan shared with CICS | platform | PLATFORM | No counterpart. | n/a |

## COACCT01

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COACCT01-01 | README does not match the code | user-decision | CHANGE (D-MQ) | The code is the contract: RabbitMQ request queue, reply queue `CARD.DEMO.REPLY.ACCT`, error queue `CARD.DEMO.ERROR`, function `INQA`, labelled text reply; plus a REST endpoint returning the same data. | n/a (spec-derived tests; MQ) |
| Q-COACCT01-02 | ReplyToQ ignored | functional-defect | FIX | The reply goes to the request's `reply_to`; the fixed queue is the default. | n/a |
| Q-COACCT01-03 | CorrelId copied from the request | functional-defect | FIX | Reply `correlation_id` = request `message_id`; new `message_id`; message type reply. | n/a |
| Q-COACCT01-04 | Buffer not cleared; data length unused | data-corruption | FIX | Only the received bytes are used; a request shorter than 15 bytes gets the existing `INVALID REQUEST PARAMETERS ...` reply. | n/a |
| Q-COACCT01-05 | Reply is text with overpunched amounts | user-decision | CHANGE (D-MQ) | The queue reply keeps the legacy text byte for byte; the REST endpoint returns JSON with decimal amounts. | n/a |
| Q-COACCT01-06 | Non-numeric key bytes | functional-defect | FIX | A non-numeric key gets the existing `INVALID REQUEST PARAMETERS ...` reply. | n/a |
| Q-COACCT01-07 | Errors consume the request without a reply | functional-defect | FIX | After a read or publish error the requester gets an error reply and the error queue gets the error record; the request is acknowledged. | n/a |
| Q-COACCT01-08 | No poison-message handling | functional-defect | FIX | Requests over 1000 bytes get the invalid-request reply; a request that fails 3 deliveries goes to a dead-letter queue. | n/a |
| Q-COACCT01-09 | Close failures recurse | platform | PLATFORM | Channel close errors are logged once. | n/a |
| Q-COACCT01-10 | RETRIEVE error shows RESP2 zero; misspelled text | platform | PLATFORM | No trigger message to retrieve. | n/a |
| Q-COACCT01-11 | CICS codes in MQ fields; truncated message | functional-defect | FIX | The error record carries the storage error code in its own field; the text `ERROR WHILE READING ACCTF` stays verbatim. | n/a |
| Q-COACCT01-12 | MQ-MSG-COUNT uninitialized and unused | dead-code | NOT-PORTED | Not ported. | n/a |
| Q-COACCT01-13 | Swapped flag names | dead-code | NOT-PORTED | No counterpart. | n/a |
| Q-COACCT01-14 | Dead or redundant items | dead-code | NOT-PORTED | Not ported. | n/a |
| Q-COACCT01-15 | Any queue writer can read any account | security | FIX | Broker requesters authenticate and need permission on the request queue; REST requires a session with C-ROLE and C-SCOPE. | n/a |
| Q-COACCT01-16 | Error record keeps stale fields | functional-defect | FIX | The error record is reset for each error. | n/a |
| Q-COACCT01-17 | 5-second wait; parallel tasks | platform | PLATFORM | Long-lived consumer with configured concurrency. | n/a |

## CODATE01

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-CODATE01-01 | README does not match the code | user-decision | CHANGE (D-MQ) | The code is the contract: reply queue `CARD.DEMO.REPLY.DATE`, error queue `CARD.DEMO.ERROR`; plus a REST endpoint. | n/a (spec-derived tests; MQ) |
| Q-CODATE01-02 | Request never inspected | business-choice | PRESERVE | Business choice: any request gets a date reply. | n/a |
| Q-CODATE01-03 | Local US-format text with no time zone | user-decision | CHANGE (D-MQ) | The queue reply keeps the legacy text; the REST endpoint returns ISO 8601 with offset. | n/a |
| Q-CODATE01-04 | ReplyToQ ignored | functional-defect | FIX | As Q-COACCT01-02. | n/a |
| Q-CODATE01-05 | CorrelId copied from the request | functional-defect | FIX | As Q-COACCT01-03. | n/a |
| Q-CODATE01-06 | Errors consume the request without a reply | functional-defect | FIX | As Q-COACCT01-07. | n/a |
| Q-CODATE01-07 | No poison-message handling | functional-defect | FIX | Oversized requests are accepted (content unused) and get a normal date reply; repeated delivery failures go to the dead-letter queue. | n/a |
| Q-CODATE01-08 | Close failures recurse | platform | PLATFORM | As Q-COACCT01-09. | n/a |
| Q-CODATE01-09 | RETRIEVE error shows RESP2 zero | platform | PLATFORM | As Q-COACCT01-10. | n/a |
| Q-CODATE01-10 | MQ-MSG-COUNT uninitialized and unused | dead-code | NOT-PORTED | Not ported. | n/a |
| Q-CODATE01-11 | Swapped flag names | dead-code | NOT-PORTED | No counterpart. | n/a |
| Q-CODATE01-12 | Error record keeps stale fields | functional-defect | FIX | As Q-COACCT01-16. | n/a |
| Q-CODATE01-13 | Dead items copied from COACCT01 | dead-code | NOT-PORTED | Not ported. | n/a |
| Q-CODATE01-14 | No security or throttling | security | FIX | Broker authentication and permissions as Q-COACCT01-15, plus a per-requester rate limit and a bounded reply queue. | n/a |
| Q-CODATE01-15 | Time read at processing, not send | business-choice | PRESERVE | Business choice: server processing time. | n/a |
| Q-CODATE01-16 | 5-second wait; parallel tasks | platform | PLATFORM | As Q-COACCT01-17. | n/a |

## COPAUA0C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COPAUA0C-01 | Reply pointer never reset; replies accumulate | functional-defect | FIX | Each reply contains only its own text (63 characters for the documented reply, no stray byte). | n/a (spec-derived tests; IMS/MQ) |
| Q-COPAUA0C-02 | Stale summary flag can approve an unknown card | data-corruption | FIX | An unknown card is always declined `05`/`3100` with amount 0.00. | n/a |
| Q-COPAUA0C-03 | Account not found copies the previous account's limits | data-corruption | FIX | No limits are copied when the account is not found; the request is declined `05`/`3100`. | n/a |
| Q-COPAUA0C-04 | Declined amount uses the previous message's amount | data-corruption | FIX | The declined amount adds this request's amount. | n/a |
| Q-COPAUA0C-05 | Available credit ignores the account balance | business-choice | PRESERVE (DF-5) | Business choice (DF-5): same formula; available credit is not reconciled with the account balance. | n/a |
| Q-COPAUA0C-06 | Reply sent before the database update | data-corruption | FIX | The database update commits first, then the reply is published, then the request is acknowledged. If the commit fails no approval is sent and the request goes to the dead-letter queue. | n/a |
| Q-COPAUA0C-07 | Critical errors commit partial updates | data-corruption | FIX | A critical error rolls back the message's database transaction. | n/a |
| Q-COPAUA0C-08 | Several decline reasons never set; profile stub | business-choice | PRESERVE (DF-5) | Business choice (Q-ENT-36, DF-5): unreachable decline reasons are not implemented; the profile stub stays. | n/a |
| Q-COPAUA0C-09 | Customer not found does not decline | business-choice | PRESERVE | Business choice: same rule and reason order. | n/a |
| Q-COPAUA0C-10 | Decline-reason flag not reset | functional-defect | FIX | Reset for every message; no visible change. | n/a |
| Q-COPAUA0C-11 | Millisecond key can collide | concurrency | FIX | Authorization keys are unique (timestamp plus sequence); concurrent requests never collide. | n/a |
| Q-COPAUA0C-12 | Negative, zero, or non-numeric amounts approved | functional-defect | FIX | Such requests are declined `05` with reason `9000` (the existing "otherwise" reason); no summary or detail is written. | n/a |
| Q-COPAUA0C-13 | Error log key from the previous message; full card numbers logged | security | FIX | Error records use this request's card number, masked per B-MASK. | n/a |
| Q-COPAUA0C-14 | Trailing comma and space-padded amount | business-choice | PRESERVE | Business choice: reply contract kept. | n/a |
| Q-COPAUA0C-15 | 501 messages per task | platform | PLATFORM | Long-lived consumer; no per-task limit. | n/a |
| Q-COPAUA0C-16 | M003 tagged subsystem `C` | functional-defect | FIX | M003 tagged `M`. | n/a |
| Q-COPAUA0C-17 | Approval sets cash balance to 0 | business-choice | PRESERVE | Business choice: same. | n/a |
| Q-COPAUA0C-18 | PSB scheduled once per message | platform | PLATFORM | One database transaction per message. | n/a |

## COPAUS0C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COPAUS0C-01 | PF7 never returns to page 1 | functional-defect | FIX | PF7 moves back one page and reaches page 1, where the top-of-page message appears. | n/a (spec-derived tests; IMS) |
| Q-COPAUS0C-02 | PREV-PG overflows after 20 pages | data-corruption | FIX | No page limit. | n/a |
| Q-COPAUS0C-03 | Menu entry searches account 00000000000 | functional-defect | FIX | No account in context shows the empty prompt. | n/a |
| Q-COPAUS0C-04 | Xref not found continues with a stale account | data-corruption | FIX | Processing stops with the existing ERR-COPAUS0C-01 message (`Account:` + account + ` not found in XREF file.` ...); nothing from another account is shown. | n/a |
| Q-COPAUS0C-05 | Account status never filled | functional-defect | FIX | ACCSTAT shows ACCT-ACTIVE-STATUS. | n/a |
| Q-COPAUS0C-06 | Amount and count fields lose digits; zeros shown as strings | functional-defect | FIX | All digits shown; zero amounts formatted as `0.00` and counts as `0`. | n/a |
| Q-COPAUS0C-07 | Multiple sends per turn | platform | PLATFORM | C-RENDER. | n/a |
| Q-COPAUS0C-08 | Amount column shows the approved amount | business-choice | PRESERVE | Business choice: same. | n/a |
| Q-COPAUS0C-09 | Invalid key redisplays only FSET fields | platform | PLATFORM | An invalid key redisplays the whole current screen with the invalid-key message. | n/a |
| Q-COPAUS0C-10 | Name cut at the first space; address cut at 25 | cosmetic | PRESERVE | Display kept. | n/a |
| Q-COPAUS0C-11 | Stale keys of protected rows | platform | PLATFORM | No counterpart. | n/a |
| Q-COPAUS0C-12 | Unused copybook and file names | dead-code | NOT-PORTED | Not ported. | n/a |

## COPAUS1C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COPAUS1C-01 | Toggled flag shown after a failed save | functional-defect | FIX | After a failed fraud update the stored flag and date are shown with the error message; a failed call shows the existing COPAUS2C error text. | n/a (spec-derived tests; IMS/Db2) |
| Q-COPAUS1C-02 | Not-found summary or detail shows leftover data | functional-defect | FIX | Nothing is shown and PF5 does nothing; message **NEW** `Authorization not found...`. | n/a |
| Q-COPAUS1C-03 | Unlimited F/R toggling | business-choice | PRESERVE | Business choice: same. | n/a |
| Q-COPAUS1C-04 | "Auth Code" shows the processing code | cosmetic | PRESERVE | Label and value kept. | n/a |
| Q-COPAUS1C-05 | Amount shows the approved amount | business-choice | PRESERVE | Business choice: same. | n/a |
| Q-COPAUS1C-06 | Report date text in IMS, DATE in Db2 | platform | PLATFORM | One DATE (Q-ENT-28) shown `MM/DD/YY`. | n/a |
| Q-COPAUS1C-07 | Map sent twice in one turn | platform | PLATFORM | C-RENDER. | n/a |
| Q-COPAUS1C-08 | Validation failure shows an empty screen | functional-defect | FIX | The screen shows **NEW** `Please select an authorization...` with the header. | n/a |
| Q-COPAUS1C-09 | `RPT DT:` written to the CICS log | platform | PLATFORM | DEBUG-level log only. | n/a |
| Q-COPAUS1C-10 | Unused fraud data area | platform | PLATFORM | No counterpart. | n/a |
| Q-COPAUS1C-11 | Reason table lists unreachable codes | business-choice | PRESERVE | Business choice: same table. | n/a |

## COPAUS2C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COPAUS2C-01 | AUTH_TS mixes two clocks | data-corruption | FIX | `AUTH_TS` is the stored insert timestamp (Q-ENT-28). | n/a (spec-derived tests; Db2) |
| Q-COPAUS2C-02 | 2-digit year resolved by Db2 | platform | PLATFORM | Full stored dates; migration maps `YY` to `20YY`. | n/a |
| Q-COPAUS2C-03 | Two authorizations can map to one key | data-corruption | FIX | The fraud row key includes the unique authorization key (Q-COPAUA0C-11). | n/a |
| Q-COPAUS2C-04 | SQLSTATE moved to a numeric edit | functional-defect | FIX | SQLSTATE shown as its 5 characters. | n/a |
| Q-COPAUS2C-05 | Success messages never shown | dead-code | NOT-PORTED | Not ported (COPAUS1C messages kept). | n/a |
| Q-COPAUS2C-06 | MERCHANT_NAME stored with trailing spaces | platform | PLATFORM | Stored trimmed. | n/a |
| Q-COPAUS2C-07 | Schema name hard-coded | platform | PLATFORM | Configured schema. | n/a |

## CBPAUP0C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-CBPAUP0C-01 | Summary adjustments never saved | functional-defect | FIX (D-PURGE) | (D-PURGE) A kept summary is saved, in the same unit of work as its detail deletes, with the adjustments of 4000-CHECK-IF-EXPIRED: approved count - 1 and approved amount - PA-APPROVED-AMT for each purged detail with response `00`; otherwise declined count - 1 and declined amount - PA-TRANSACTION-AMT. PA-CREDIT-BALANCE and PA-CASH-BALANCE are not changed (BR-CBPAUP0C-05). TS-CBPAUP0C-03 changes: the kept summary shows approved count 40 and approved amount 74.79 minus the 10 purged approved amounts; credit balance stays 74.79. | n/a (spec-derived tests; IMS) |
| Q-CBPAUP0C-02 | Approved count tested twice | data-corruption | FIX | As Q-ENT-42. | n/a |
| Q-CBPAUP0C-03 | YYDDD age wrong across year ends | data-corruption | FIX | Age in whole days from the stored insert date (Q-ENT-27). | n/a |
| Q-CBPAUP0C-04 | Checkpoint parameter compared as text | functional-defect | FIX | Commit every N summaries where N is the numeric parameter (default 5); `CHKP SUCCESS` shown per the display-frequency parameter compared numerically. | n/a |
| Q-CBPAUP0C-05 | DLET after GNP GE may fail | platform | PLATFORM | Relational delete by key. | n/a |
| Q-CBPAUP0C-06 | Position lost after CHKP | platform | PLATFORM | Keyset iteration survives commits. | n/a |
| Q-CBPAUP0C-07 | Constant checkpoint ID; no restart | platform | PLATFORM | Rerun is the restart; the purge is idempotent. | n/a |
| Q-CBPAUP0C-08 | RC 16 commits partial deletes | platform | PLATFORM | Exit code 16 kept; batches committed before the error stay. | n/a |
| Q-CBPAUP0C-09 | JCL expiry 00 and debug Y | user-decision | CHANGE (D-PURGE) | (D-PURGE) Expiry as Q-CBPAUP0J-01 (DAG parameter, default 5 days). Debug is a DAG parameter defaulting to off (Q-CBPAUP0J-02). | n/a |
| Q-CBPAUP0C-10 | Age uses the insert date | business-choice | PRESERVE | Business choice: same. | n/a |

## PAUDBUNL

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-PAUDBUNL-01 | Children counted as summaries; no counts shown | functional-defect | FIX | **NEW** end-of-run lines with root and child counts. | n/a (spec-derived tests; IMS) |
| Q-PAUDBUNL-02 | Invalid-key root dropped silently | platform | PLATFORM | The target holds no invalid keys; migration reports and skips the root with the X'40' key (Q-PAUDBLOD-07). | n/a |
| Q-PAUDBUNL-03 | IF closed by a period | dead-code | NOT-PORTED | No behavior. | n/a |
| Q-PAUDBUNL-04 | WRITE status unchecked | functional-defect | FIX | A write error ends with exit code 16. | n/a |
| Q-PAUDBUNL-05 | Abend text names IMSUNLOD; unused items | cosmetic | PRESERVE | Text kept verbatim; unused items not ported. | n/a |
| Q-PAUDBUNL-06 | Partial outputs cataloged on RC 16 | data-corruption | FIX | On a failed run the outputs are deleted so LOADPADB cannot read a partial extract. | n/a |
| Q-PAUDBUNL-07 | First run fails on `DISP=OLD` | platform | PLATFORM | As Q-UNLDPADB-01. | n/a |
| Q-PAUDBUNL-08 | Program clears the IMS PCB status | platform | PLATFORM | Local status variables. | n/a |
| Q-PAUDBUNL-09 | Unload can run during online updates | user-decision | CHANGE (D-BATCH-WINDOW) | The unload reads one consistent snapshot (read-only transaction); online stays up. | n/a |
| Q-PAUDBUNL-10 | Output holds full card numbers | security | PRESERVE (D-PII-FILES) | (D-PII-FILES) Content kept, including full card numbers (load contract with PAUDBLOD); both unload files are written mode 600. | n/a |
| Q-PAUDBUNL-11 | Parent key only link; not interchangeable with DBUNLDGS | business-choice | PRESERVE | Business choice: same format. | n/a |
| Q-PAUDBUNL-12 | Parent key on later children relies on the record area after WRITE | data-corruption | FIX | Every child record carries its own parent's account id (6-byte packed, bytes 1-6), for the second and later children of a root as well as the first, which is what GnuCOBOL produces and the program intends. | n/a (spec-derived tests; IMS; GnuCOBOL keeps the record area, so no patch would be needed) |

## PAUDBLOD

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-PAUDBLOD-01 | DLITCBL entry names only the DB PCB | platform | PLATFORM | No PCB binding. | n/a (spec-derived tests; IMS) |
| Q-PAUDBLOD-02 | Orphan children dropped silently | functional-defect | FIX | Each orphan child gets the `ROOT GU CALL FAIL:` line and is counted (Q-PAUDBLOD-09). | n/a |
| Q-PAUDBLOD-03 | READ errors loop or lose records | functional-defect | FIX | A non-end-of-file read error ends with exit code 16. | n/a |
| Q-PAUDBLOD-04 | No checkpoints; partial load committed on RC 16 | user-decision | CHANGE (D-BATCH-WINDOW) | B-UOW: one transaction per file with row locks; a failure loads nothing. | n/a |
| Q-PAUDBLOD-05 | Duplicates skipped | business-choice | PRESERVE | Business choice: skip, never replace. | n/a |
| Q-PAUDBLOD-06 | Summaries loaded unreconciled | business-choice | PRESERVE | Business choice: same. | n/a |
| Q-PAUDBLOD-07 | Root keys not validated | data-corruption | FIX | A root with an invalid key is rejected with a **NEW** line `INVALID ROOT KEY` and counted. | n/a |
| Q-PAUDBLOD-08 | GU per child | platform | PLATFORM | Performance only. | n/a |
| Q-PAUDBLOD-09 | No counts; very long SYSOUT | functional-defect | FIX | **NEW** end-of-run counts (roots, children, duplicates, orphans, rejects); per-record lines kept. | n/a |
| Q-PAUDBLOD-10 | Unused working storage | dead-code | NOT-PORTED | Not ported. | n/a |
| Q-PAUDBLOD-11 | Reads only the PAUDBUNL format | business-choice | PRESERVE | Business choice: same input format. | n/a |

## DBUNLDGS

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-DBUNLDGS-01 | Detail file has no parent key | data-corruption | FIX | Each detail record is preceded by the parent key prefix the program already builds (ROOT-SEG-KEY). | n/a (spec-derived tests; IMS) |
| Q-DBUNLDGS-02 | Same error text for both files | cosmetic | PRESERVE | Text kept verbatim. | n/a |
| Q-DBUNLDGS-03 | No counts | functional-defect | FIX | As Q-PAUDBUNL-01. | n/a |
| Q-DBUNLDGS-04 | Invalid-key roots dropped silently | platform | PLATFORM | As Q-PAUDBUNL-02. | n/a |
| Q-DBUNLDGS-05 | Outputs pre-allocated and kept on RC 16 | data-corruption | FIX | As Q-PAUDBUNL-06; outputs created by the job. | n/a |
| Q-DBUNLDGS-06 | No reader of the GSAM files | user-decision | CHANGE (D-SCOPE) | Job kept and run on request. | n/a |
| Q-DBUNLDGS-07 | IF closed by a period | dead-code | NOT-PORTED | No behavior. | n/a |
| Q-DBUNLDGS-08 | GSAM key feedback prints unreadable bytes | platform | PLATFORM | No counterpart; the line is not written. | n/a |
| Q-DBUNLDGS-09 | Unload during online updates; card numbers in clear | user-decision | CHANGE (D-BATCH-WINDOW, D-PII-FILES) | Snapshot read as Q-PAUDBUNL-09. Card numbers are kept in the GSAM unload as Q-PAUDBUNL-10 (D-PII-FILES); the file is written mode 600. | n/a |
| Q-DBUNLDGS-10 | Unused working storage | dead-code | NOT-PORTED | Not ported. | n/a |

## Business choices preserved

Every PRESERVE row with category business-choice. The target keeps the COBOL behavior.

- Q-ENT-09: Negative amounts are added to cycle debit; over-limit formula adds returns to used credit.
- Q-ENT-11: Interest truncated; negative balances give negative interest.
- Q-ENT-22: Phone area code checked against general-purpose codes only; military states unreachable.
- Q-ENT-23: Date edits accept centuries 19 and 20 only.
- Q-ENT-24: SSN parts 2 and 3 checked only for numeric and non-zero.
- Q-ENT-29: Active-status and several indicator fields are never enforced.
- Q-ENT-30: ACCT-ADDR-ZIP used only by export and import.
- Q-ENT-36: Decline reasons 4200, 4300, 5100, 5200 unreachable.
- Q-CBACT01C-03: Array and VBRC records carry hard-coded amounts.
- Q-CBACT04C-02: Interest truncated; negative interest; category balance never reset.
- Q-CBACT04C-07: Cycle totals reset only for accounts with category balances, even without interest.
- Q-CBACT04C-09: Interest original timestamp is processing time.
- Q-CBTRN02C-02: Limit test uses cycle credit minus cycle debit.
- Q-CBTRN02C-03: Reason 103 overwrites 102; string expiry compare.
- Q-CBTRN03C-04: Irregular page breaks.
- Q-CBTRN03C-05: Totals per card labelled "Account Total".
- Q-CBTRN03C-11: Blank processing timestamp excluded.
- Q-CBSTM03A-06: Statements per card; cards absent from xref dropped.
- Q-CBSTM03A-11: No statement date or period.
- Q-CBEXPORT-02: Branch `0001` and region `NORTH` hard-coded.
- Q-CBEXPORT-03: Timestamp ends `.00` plus 4 spaces, local time.
- Q-CBIMPORT-02: Validation is a stub that always succeeds.
- Q-CBIMPORT-06: No duplicate or referential checks.
- Q-COTRTLIC-09: Case-sensitive description filter; `%` and `_` not escaped.
- Q-COTRTLIC-11: "No change detected" with PF10 still saving.
- Q-COTRTLIC-12: Inconsistent message precedence.
- Q-COTRTLIC-14: List read error not tested.
- Q-COTRTUPC-11: Case-only description changes rejected.
- Q-COTRTUPC-13: Description edit stricter than COBTUPDT.
- Q-CODATE01-02: Request never inspected.
- Q-CODATE01-15: Time read at processing, not send.
- Q-COPAUA0C-05: Available credit ignores the account balance.
- Q-COPAUA0C-08: Several decline reasons never set; profile stub.
- Q-COPAUA0C-09: Customer not found does not decline.
- Q-COPAUA0C-14: Trailing comma and space-padded amount.
- Q-COPAUA0C-17: Approval sets cash balance to 0.
- Q-COPAUS0C-08: Amount column shows the approved amount.
- Q-COPAUS1C-03: Unlimited F/R toggling.
- Q-COPAUS1C-05: Amount shows the approved amount.
- Q-COPAUS1C-11: Reason table lists unreachable codes.
- Q-CBPAUP0C-10: Age uses the insert date.
- Q-PAUDBUNL-11: Parent key only link; not interchangeable with DBUNLDGS.
- Q-PAUDBLOD-05: Duplicates skipped.
- Q-PAUDBLOD-06: Summaries loaded unreconciled.
- Q-PAUDBLOD-11: Reads only the PAUDBUNL format.

## New behavior introduced

Every FIX or CHANGE row that changes what a user, operator, or downstream job sees. **NEW** marks
text that does not exist in the COBOL.

Data and migration:
- Q-ENT-01, Q-ENT-02, Q-CBACT01C-07, Q-CBACT04C-05: sample accounts get group `A000000000` in ACCT-GROUP-ID and a blank ZIP; the interest run no longer prints the 100 "missing" lines (SYSOUT 303 to 103 lines); amounts unchanged.
- Q-ENT-08: timestamps sort chronologically; displayed text unchanged.
- Q-ENT-33, Q-CBTRN02C-10, Q-CBEXPORT-01, Q-CBIMPORT-10, Q-PAUDBUNL-10, Q-DBUNLDGS-09 (D-PII-FILES): stored values and file content unchanged (PAN, CVV, SSN kept); reject, export, import, and unload files are written mode 600; the rejects table is readable only by batch and ADMIN roles.
- Q-ENT-34, Q-CREADB21-04: one shared transaction-type and category table seeded from the VSAM sample (`Reversal`, mixed case); the Db2 module screens show mixed-case descriptions.
- Q-ENT-26, Q-ENT-27, Q-ENT-28, Q-COPAUS2C-01, Q-COPAUS2C-03: authorization amounts are exact above 1,000,000,000.00; authorizations carry full timestamps; purge age is in real days; `AUTH_TS` is the insert timestamp.

Batch cycle and jobs:
- B-CYCLE, Q-INTCALC-02, Q-RUNBOOK-01, Q-CA7-*, Q-CONTROLM-* (D-INTEREST): every business date runs POSTTRAN then CREASTMT; the first business date of each month runs POSTTRAN, INTCALC, CREASTMT; the interest formula is unchanged; no file close/open, backup-and-reload, or wait steps; CA7 and Control-M not ported.
- Q-TRANBKP-02, Q-CBTRN02C-01, Q-CREASTMT-04 (D-HISTORY): the transaction table keeps all history and COTRN00C shows earlier cycles and online-added transactions; each statement run covers only transactions no earlier statement run covered, and statement output is identical to the COBOL's (no new header lines).
- Q-INTCALC-01, Q-CBACT04C-08: INTCALC needs a valid business date (**NEW** `INVALID OR MISSING BUSINESS DATE`, exit 12) and refuses a second completed run in the same calendar month (**NEW** `INTEREST ALREADY CALCULATED FOR YYYY-MM`, exit 8); a rerun after an abend skips accounts that already have that month's interest.
- Q-TRANREPT-02, Q-TRANREPT-03, Q-CBTRN03C-06, Q-CBTRN03C-07: the transaction report reads the live table with one date range from the request (USER requests limited to linked accounts, D-REPORT); a missing range fails with **NEW** `INVALID OR MISSING REPORT DATE RANGE` (exit 12).
- Q-PRTCATBL-02: negative category balances print with a trailing `-`.
- B-ABEND (all CEE3ABD rows): an abend ends with exit code 99 and code `U999`.
- Q-FTPJCL-01, Q-DUSRSECJ-01: no clear-text credentials in job definitions or seeds.

Base batch programs:
- Q-READCARD-01, Q-CBACT02C-01, Q-CBACT03C-02, Q-CBCUS01C-02, Q-CBTRN01C-05, Q-CBTRN03C-08: SYSOUT shows masked card numbers (`************` + last 4), `***` for CVV, masked SSN, government ID, DOB, and EFT account.
- Q-CBACT03C-01, Q-CBCUS01C-01: listings show each record once (53 lines instead of 103).
- Q-CBTRN01C-01: no repeated lookup after end of file (300 instead of 301 xref reads).
- Q-CBTRN02C-08: the expected posting result is 300 processed, 38 rejected, RC 4 (not 43).
- Q-CBTRN02C-04: an account update failure rejects the record with reason 109 and rolls it back.
- Q-CBACT04C-01: the last account gets its interest (sample account 50: 1945.87 becomes 1964.64; cycle totals 0.00).
- Q-CBACT04C-03: accounts with several cards are processed (interest row carries the lowest card number).
- Q-CBTRN03C-01, Q-CBTRN03C-02, Q-CBTRN03C-03: out-of-range records are skipped; the last amount is no longer doubled (last page total 959.99, grand total 77,954.70); the last account total (1,609.10) is printed.
- Q-CBTRN03C-10, Q-CBSTM03A-09: an amount that does not fit its print picture fails the job instead of printing truncated digits.
- Q-CBSTM03A-03, Q-CBSTM03A-04, Q-CBSTM03A-07, Q-CBSTM03A-10: an empty transaction file gives statements with no transaction lines; no table limits; full names and cities (`New Aricchester`); HTML values escaped.
- Q-CBIMPORT-03, Q-CBIMPORT-04, Q-CBIMPORT-07: imported files have space-filled FILLER; error lines show the 9-digit sequence; undecodable numerics go to ERROUT with **NEW** `INVALID NUMERIC DATA`.
- Q-CBACT01C-01, Q-CBACT01C-02: the account extract writes the real non-zero cycle debit and a space-padded reissue date.
- Q-COBSWAIT-01: an invalid wait value fails with **NEW** `INVALID WAIT TIME` (exit 12).

Transaction-type module (Db2):
- Q-COTRTLIC-05, Q-COTRTUPC-05, Q-COTRTUPC-10: ADMIN only (C-ROLE); keys from server-side state (Q-COTRTLIC-17, Q-COTRTUPC-21).
- Q-COTRTLIC-01, -04, -06, -07, -13, -18, -20, -21: selections cleared on filter change; user kept after delete; pages above 9; full first page on backward paging; Db2 error text shown; warnings treated as success; boundary messages only at the boundary; page number kept on filter error.
- Q-COTRTLIC-22: a failed filter count query shows the database error instead of `No Records found for these filter conditions`.
- Q-COTRTLIC-23, -24, -25: PF10 confirms only the prompted action and row; a filter change with PF7 or PF8 shows page 1 of the new search without a boundary message; after `Record not found. Deleted by others ?` nothing stays pending.
- Q-COTRTLIC-19, Q-COTRTUPC-17: concurrent changes rejected with `Deadlock. Someone else updating ?` (COTRTLIC) or `Could not lock record for update` (COTRTUPC); a deleted row is never re-inserted by an update.
- Q-COTRTUPC-01, -02, -03, -04, -06, -07, -08, -09, -14, -18, -19: type edit always applied (01-99 digits only); save failures shown as `Update of record failed`; clean SQL messages with the right SQLCODE; no abend after a failed delete or PF5 in state `9`; abend screen shows its code and default text; lookup errors not treated as not found; state `S` shows `Update transaction type details shown.`; typed input after a reset is processed.
- Q-COBTUPDT-01, -02, -04, -05, -07: stops with exit 12 on open/read errors and on any negative SQLCODE (whole file rolled back); clean messages; online validation rules applied; **NEW** end-of-run summary.

MQ inquiry module (D-MQ):
- Q-COACCT01-01, Q-CODATE01-01, Q-COACCT01-05, Q-CODATE01-03: RabbitMQ queues with the COBOL names and reply texts, plus REST endpoints (JSON amounts, ISO 8601 time).
- Q-COACCT01-02, -03, Q-CODATE01-04, -05: replies honor `reply_to` and carry `correlation_id` = request `message_id`.
- Q-COACCT01-04, -06, -07, -08, -16, Q-CODATE01-06, -07, -12: short, non-numeric, or oversized requests get the existing invalid-request reply (account) or a normal reply (date); errors produce an error reply; poison messages go to a dead-letter queue; error records are clean.
- Q-COACCT01-15, Q-CODATE01-14: requesters must authenticate; REST applies C-ROLE and C-SCOPE; date requests are rate-limited.

Authorization module (IMS, Db2, MQ):
- Q-COPAUA0C-01: each reply carries only its own text.
- Q-COPAUA0C-02, -03, -04, -12: unknown cards and accounts are declined `05`/`3100`; declined amounts are this request's; zero, negative, or non-numeric amounts are declined `05`/`9000`.
- Q-COPAUA0C-06, -07, -11: database update commits before the reply; critical errors roll back; no key collisions.
- Q-COPAUA0C-13, -16: error log records carry this request's masked card number; M003 tagged `M`.
- Q-COPAUS0C-01, -02, -03, -04, -05, -06: PF7 reaches page 1; no page limit; empty prompt without account context; xref not-found stops; Acct Status filled; amounts and counts show all digits and zeros as `0.00`.
- Q-COPAUS1C-01, -02, -08: stored values shown after a failed fraud update; **NEW** `Authorization not found...` and **NEW** `Please select an authorization...`.
- Q-COPAUS2C-04: SQLSTATE shown as text.
- Q-CBPAUP0J-01, Q-CBPAUP0C-09 (D-PURGE): purge expiry is a DAG parameter defaulting to 5 days (not the JCL's `00`); debug off by default.
- Q-CBPAUP0C-01 (D-PURGE), -02, -03, -04, Q-ENT-42: purge saves the summary count and amount adjustments for deleted details (credit and cash balances unchanged), never deletes unexpired details, uses real day ages, and checkpoints by the numeric parameter.
- Q-PAUDBUNL-01, -04, -06, -12, Q-DBUNLDGS-01, -03, -05: unloads print **NEW** root and child counts, fail on write errors, delete partial outputs; every detail record of both unloads carries its own parent key.
- Q-PAUDBLOD-02, -03, -04, -07, -09: orphan children reported, read errors fatal, all-or-nothing load, invalid root keys rejected (**NEW** `INVALID ROOT KEY`), **NEW** end-of-run counts.

## Reference patches

Each patch is described as a mechanical edit of the original source; line numbers refer to the
unpatched file in `app/`. Patches are applied to scratch copies only, and all builds follow
B-PARITY.

| RP | Rows | Change | Verified result |
|:--|:--|:--|:--|
| RP-BUILD-1 | Q-CBTRN02C-08 | Compile every parity build with `-fsign=EBCDIC` (no source change). | CBTRN02C: 300 processed, 38 rejected, RC 4 (default ASCII sign: 43). |
| RP-DATA-1 | Q-ENT-02, Q-CBACT01C-07, Q-CBACT04C-05 | Pre-process `acctdata.txt`: for each 300-byte record, if bytes 113-122 are spaces and bytes 103-112 equal a group ID present in `discgrp.txt`, copy bytes 103-112 to 113-122 and set bytes 103-112 to spaces. | 50 accounts changed. CBACT04C: SYSOUT 303 to 103 lines (0 "missing" lines); SYSTRAN and all balances identical. |
| RP-CBACT02C-1 | Q-CBACT02C-01, Q-READCARD-01 | Before `DISPLAY CARD-RECORD` (line 78): `MOVE '************' TO CARD-RECORD(1:12)` and `MOVE '***' TO CARD-RECORD(28:3)`. | 53 lines; card numbers show last 4 only; CVV `***`. |
| RP-CBACT03C-1 | Q-CBACT03C-01 | Delete `DISPLAY CARD-XREF-RECORD` in 1000-XREFFILE-GET-NEXT (line 96). | 103 to 53 lines. |
| RP-CBACT03C-2 | Q-CBACT03C-02, Q-READCARD-01 | Before the line-78 DISPLAY: `MOVE '************' TO CARD-XREF-RECORD(1:12)`. | Masked; account and customer IDs unchanged. |
| RP-CBCUS01C-1 | Q-CBCUS01C-01 | Delete `DISPLAY CUSTOMER-RECORD` in 1000-CUSTFILE-GET-NEXT (line 96). | 103 to 53 lines. |
| RP-CBCUS01C-2 | Q-CBCUS01C-02, Q-READCARD-01 | Before the line-78 DISPLAY: `MOVE '*****' TO CUSTOMER-RECORD(280:5)`, `MOVE ALL '*' TO CUSTOMER-RECORD(289:20)`, `MOVE ALL '*' TO CUSTOMER-RECORD(309:10)`, `MOVE '******' TO CUSTOMER-RECORD(319:6)`. | SSN last 4, government ID and DOB all `*`, EFT last 4. |
| RP-CBTRN01C-1 | Q-CBTRN01C-01 | Move the lookup block (MOVE to XREF-CARD-NUM through the "could not be verified" DISPLAY) inside the inner `IF END-OF-DAILY-TRANS-FILE = 'N'`. | 301 to 300 xref reads; 1808 to 1803 lines. |
| RP-CBTRN01C-2 | Q-CBTRN01C-05 | Add `01 WS-MASKED-REC PIC X(350).`; display the daily record through it with `(263:12)` set to `************`; print `'************' XREF-CARD-NUM(13:4)` and `'************' DALYTRAN-CARD-NUM(13:4)` in the two card messages. | All card numbers masked. |
| RP-CBTRN02C-1 | Q-CBTRN02C-11 | `WS-TEMP-BAL PIC S9(09)V99` to `PIC S9(11)V99` (line 187). | Output unchanged: 300 processed, 38 rejected, RC 4. |
| RP-CBTRN02C-2 | Q-CBTRN02C-12 | In 2700-A-CREATE-TCATBAL-REC, before `INITIALIZE TRAN-CAT-BAL-RECORD` (line 504): `MOVE SPACES TO TRAN-CAT-BAL-RECORD`. | The 50 created records have space FILLER; everything else unchanged. |
| RP-CBACT04C-1 | Q-CBACT04C-01 | After the main loop's `END-PERFORM.` (line 222): `IF WS-FIRST-TIME = 'N' PERFORM 1050-UPDATE-ACCOUNT END-IF.` | Only account 50 differs: balance 1945.87 to 1964.64, cycle credit and debit to 0.00; SYSTRAN identical except timestamps; SYSOUT identical (303 lines). Harness passes PARM `2022071800`. |
| RP-CBACT04C-2 | Q-CBACT04C-03 | RP-CBACT04C-1 plus `WITH DUPLICATES` on `ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID` and `IF XREFFILE-STATUS = '00' OR '02'` in 1110-GET-XREF-DATA. | Compile-checked; sample has one card per account. Stamps the first card in alternate-key order; the target uses the lowest card number. |
| RP-CBTRN03C-1 | Q-CBTRN03C-01 | Replace the IF / CONTINUE / ELSE NEXT SENTENCE block (lines 173-178) with `IF END-OF-FILE = 'N' AND (TRAN-PROC-TS (1:10) < WS-START-DATE OR TRAN-PROC-TS (1:10) > WS-END-DATE) EXIT PERFORM CYCLE END-IF`. | A first out-of-range record: original gives a 0-byte report, patched skips it (261 detail lines). |
| RP-CBTRN03C-2 | Q-CBTRN03C-02, Q-CBTRN03C-03 | In the end-of-file branch replace `ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL` with `IF WS-FIRST-TIME = 'N' PERFORM 1120-WRITE-ACCOUNT-TOTALS END-IF`. | 262 posted records: report 451 to 453 lines; last page total 1,725.65 to 959.99; new last account total 1,609.10; grand total 78,720.36 to 77,954.70. |
| RP-CBTRN03C-3 | Q-CBTRN03C-08 | Add `01 WS-MASKED-REC PIC X(350).`; display TRAN-RECORD through it with `(263:12)` set to `************`. | Masked. |
| RP-CBSTM03A-1 | Q-CBSTM03A-01 | Precondition for any off-z/OS run: delete the control-block walk (lines 266-291). | Required to run at all. |
| RP-CBSTM03A-2 | Q-CBSTM03A-04 | `OCCURS 51` to `OCCURS 500` (WS-CARD-TBL and WS-TRN-TBL-CTR); `OCCURS 10` to `OCCURS 50` (WS-TRAN-TBL). | Syntax-checked; not run. |
| RP-CBSTM03A-3 | Q-CBSTM03A-07 | Build ST-NAME from `FUNCTION TRIM(... TRAILING)` of first, middle, and last name with one space between parts, omitting the middle name when blank; build ST-ADD3 the same way from city, state, country, and ZIP. | Syntax-checked; not run. |
| RP-CBSTM03A-4 | Q-CBSTM03A-03 | In 8100-TRNXFILE-OPEN, before the `'00' OR '04'` check of the first read: `IF WS-M03B-RC = '10' MOVE 0 TO CR-CNT, MOVE 'XREFFILE' TO WS-FL-DD, GO TO 0000-START END-IF`. | Syntax-checked; not run. |
| RP-CBIMPORT-1 | Q-CBIMPORT-03 | `MOVE SPACES TO <record>` before each of the five `INITIALIZE` statements (customer, account, xref, transaction, card). | Syntax-checked; not run. |
| RP-CBIMPORT-2 | Q-CBIMPORT-04 | `ERR-SEQUENCE PIC 9(07)` to `PIC 9(09)`; the following trailing `FILLER PIC X(43)` to `PIC X(41)` (record length unchanged). | Syntax-checked; not run. |

Retired patches:

- RP-CBSTM03A-5 (post-filter removing the statement date and period lines) is retired: under D-HISTORY the target adds no statement header lines (Q-CBSTM03A-11), so target statements are compared with the RP-CBSTM03A-1..4 output unfiltered.

Rows marked "none needed" are FIX or CHANGE rows on GnuCOBOL-runnable programs whose target
behavior produces the same output as the unpatched COBOL on the parity data (error paths, or
outputs that already match).

## Resolved questions

Every question previously listed for the user is resolved by a user decision or recorded default in `README.md`. The rows cite the resolving ID.

- Q-ENT-13: DF-2 (ASCII sample set is the baseline).
- Q-ENT-16: DF-3 (keep the last 10 reports).
- Q-ENT-29: D-INACTIVE (flags shown, not enforced).
- Q-ENT-33: D-PII-FILES (CVV kept in the database as the COBOL does; masked on screens and logs).
- Q-ENT-34: DF-1 (seed from `trantype.txt` and `trancatg.txt`).
- Q-ENT-36, Q-COPAUA0C-08: DF-5 (unreachable decline reasons not invented).
- Q-CBPAUP0J-01, Q-CBPAUP0C-09: D-PURGE (DAG parameter, default 5 days).
- Q-RUNBOOK-01: D-INTEREST (interest monthly, formula unchanged).
- Q-CBACT01C-01: no D- or DF- ID; the 2525.00 constant is preserved as a business choice (classification policy rule 5).
- Q-CBCUS01C-02: D-PII-FILES and D-FIX-SEC (names, addresses, and phones stay; card, CVV, SSN, government ID, DOB, and EFT data masked).
- Q-CBSTM03A-11: D-HISTORY and DF-4 (no statement date, period, due date, or minimum payment).
- Q-CBEXPORT-01, Q-CBIMPORT-10, Q-PAUDBUNL-10, Q-DBUNLDGS-09: D-PII-FILES (content kept, files mode 600).
- Q-CBIMPORT-02: no D- or DF- ID; the stub success message is preserved as a business choice (classification policy rule 5).
- Q-COPAUA0C-05: DF-5 (available credit not reconciled with the account balance).
- Q-CBPAUP0C-01: D-PURGE (summary counts and amounts adjusted; credit and cash balances unchanged, BR-CBPAUP0C-05).

Still open: none.

## Counts

Rows by category and decision (391 rows).

| Category | FIX | PRESERVE | CHANGE | PLATFORM | NOT-PORTED | Total |
|:--|--:|--:|--:|--:|--:|--:|
| security | 19 | 4 | 0 | 0 | 0 | 23 |
| data-corruption | 40 | 0 | 0 | 0 | 0 | 40 |
| concurrency | 4 | 0 | 0 | 0 | 0 | 4 |
| functional-defect | 89 | 0 | 0 | 0 | 0 | 89 |
| cosmetic | 0 | 14 | 0 | 0 | 0 | 14 |
| business-choice | 0 | 45 | 0 | 0 | 0 | 45 |
| platform | 0 | 0 | 0 | 114 | 0 | 114 |
| dead-code | 0 | 0 | 0 | 0 | 30 | 30 |
| user-decision | 0 | 0 | 32 | 0 | 0 | 32 |
| Total | 152 | 63 | 32 | 114 | 30 | 391 |
