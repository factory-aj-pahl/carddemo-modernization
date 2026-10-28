# Fidelity ledger

Every quirk (`Q-` ID) in `../spec/` gets exactly one decision here. The ledger, together with the
user decisions below, defines where the modern system intentionally differs from the COBOL. Any
behavior not listed as FIX or CHANGE is preserved as specified in `../spec/`.

## User decisions (2026-10-28)

| ID | Decision |
|:--|:--|
| D-SCOPE | Convert everything: base application, transaction-type Db2 module, authorization IMS/Db2/MQ module, VSAM-MQ inquiry module |
| D-FIDELITY | Same behavior as the COBOL, except the approved fixes in this ledger |
| D-FIX-SEC | Fix security defects: hashed passwords, server-side role checks, no password echo, mask card numbers and SSNs on screens |
| D-FIX-DATA | Fix data-corruption and concurrency defects (group-id overwrite, CVV overwrite, non-atomic updates, max+1 ids, ...) |
| D-FIX-FUNC | Fix clear functional bugs (interest skips last account, report totals, paging skips, ...) |
| D-COSMETIC | Cosmetic quirks are NOT fixed: user-visible message and label text stays verbatim |
| D-AUTH | Server-side session, BCrypt password hashes, admin/user roles enforced in the API |
| D-UI | Modern clean layout; same fields, flows, messages, and F-key shortcuts |
| D-MQ | MQ request/reply is replaced by a message broker (RabbitMQ) with request/reply queues, plus REST endpoints |
| D-CYCLE | Batch cycle follows the runbook order with interest daily |
| D-USER-ACCT | New user-to-account link: regular users see only their linked accounts and the cards, transactions, bill payments and pending authorizations of those accounts; admins are unrestricted |
| D-REPORT | A regular user's transaction report is filtered to their linked accounts |
| D-SEED | Sample accounts are linked round-robin to the sample regular users (every account one owner) |
| D-BATCH-WINDOW | Online stays available during the batch cycle; database transactions keep data consistent |
| D-UI-LOOK | The clickable prototype's look and interaction style (layout, palette, type, message bar, F-key bar) is the standard for all screens |
| D-RECEIPT | Bill payment shows a receipt panel (transaction ID, account, amount paid, new balance) after success, in addition to the COBOL message |
| D-ACCTID | Account ID input keeps the COBOL rule: 11 digits required, no zero-padding |
| D-INTEREST | Amends D-CYCLE: posting (POSTTRAN) and statements (CREASTMT) run daily; interest (INTCALC) runs monthly on the first business day of the month, after that day's posting and before its statements; the formula (annual rate / 1200) is unchanged |
| D-HISTORY | The transaction table keeps full history (online lists show earlier cycles); statements cover only the current cycle's transactions so statement output is identical to the COBOL's; no new statement header lines |
| D-SEC-EXTRAS | Added beyond the COBOL: one generic sign-on failure message ("Invalid User ID or Password. Try again ..."), lockout of an ID for 15 minutes after 5 consecutive failed sign-ons, 15-minute idle session timeout, audit log of sign-ons and of user, account, and card changes |
| D-VALIDATE-CHANGED | Account update validates only fields the user changed; unchanged legacy values are accepted as stored |
| D-PII-FILES | Export/import files and authorization unload files keep full card numbers, CVVs, and SSNs as a data-interchange contract, written owner-only (mode 600); the database keeps CVVs as the COBOL does; screens and job-log listings stay masked (D-FIX-SEC) |
| D-PURGE | The authorization purge expiry is a DAG parameter defaulting to 5 days (not the JCL's 00); the purge also updates the summary counts and amounts of what it deletes |
| D-INACTIVE | Account and card active-status flags are shown but not enforced, as in the COBOL |

## Recorded defaults (no user decision needed; object if wrong)

| ID | Default |
|:--|:--|
| DF-1 | Transaction types and categories are seeded from the base ASCII sample (`trantype.txt`, `trancatg.txt`), which the report reference uses; the Db2 seed's `REVERAL` spelling is not used |
| DF-2 | The ASCII sample set is the migration baseline where ASCII and EBCDIC samples disagree |
| DF-3 | Transaction report files are retained for the last 10 runs |
| DF-4 | Statements get no due date or minimum payment (no rule in the source) |
| DF-5 | Authorization decline reasons that the COBOL can never reach are not invented; available credit is not reconciled with the account balance (as in the COBOL) |
| DF-6 | Where an account has several cards and one card must be chosen (transaction add, bill payment), the lowest card number is used |
| DF-7 | A card expiry day past the end of the edited month is stored as the last day of that month |
| DF-8 | Dates before 1582-10-15 are rejected with the existing "not a valid date" messages |
| DF-9 | Numeric keys are not left-padded (consistent with D-ACCTID) |
| DF-10 | Admins keep the COBOL admin menu only; their API scope is unrestricted |
| DF-11 | The account extract's fixed 2525.00 cycle-debit constant (CBACT01C) is preserved as a business choice |
| DF-12 | CBIMPORT's validation step keeps its always-success behavior and text; no reconciliation is added |
| DF-13 | The authorization purge does not change the summary's credit and cash balances (the COBOL never adjusts them); only counts and amounts of deleted details are reduced |

## Decision values

| Value | Meaning |
|:--|:--|
| FIX | The target behaves as described in "Target behavior", not as the COBOL does |
| PRESERVE | The target reproduces the COBOL behavior (includes verbatim cosmetic text and business choices) |
| CHANGE | Behavior changes because of a user decision above (cite the D- ID) |
| PLATFORM | The quirk is an artifact of CICS/BMS/VSAM/JCL/z/OS with no target counterpart; "Target behavior" names the modern equivalent |
| NOT-PORTED | Dead or unreachable code, or unused definitions, with no observable behavior |

## Classification policy

1. Security (credentials, authorization, PII exposure, injection, secrets in source) -> FIX (D-FIX-SEC).
2. Data corruption, lost updates, races, non-atomic multi-record updates, id collisions -> FIX (D-FIX-DATA).
3. Clear functional bug: the code contradicts its own evident intent (crash or abend on a normal
   path, skipped or double-counted records, wrong totals, a message that can never show, a check
   that can never fail, paging that loses rows, state flags that never get set) -> FIX (D-FIX-FUNC).
4. Cosmetic (typos, truncated labels, odd spacing, inconsistent wording in user-visible text) -> PRESERVE (D-COSMETIC).
5. Business or design choice that a reasonable product owner may have intended (pay full balance only,
   online add does not post balances, DEFAULT disclosure-group fallback, interest truncation, page sizes)
   -> PRESERVE, flagged "business choice".
6. 3270/CICS/JCL/z/OS artifacts (cursor placement, MDT/FSET, attribute bytes, COMMAREA size,
   CEE3ABD, ALTER/GO TO, control-block walks, file open/close, GDGs, internal reader) -> PLATFORM.
7. Dead code and unused definitions -> NOT-PORTED.
8. Quirks resolved by a user decision (scoping, schedule, MQ) -> CHANGE with the D- ID.

When a FIX touches a batch program whose output is compared with GnuCOBOL, "Reference patch" states
the minimal COBOL change applied to a scratch copy (never to `app/`) so the patched COBOL produces the
expected output.

## Files

| File | Covers |
|:--|:--|
| `ledger-online.md` | Q- items of base online programs (CO*C in app/cbl) and CSUTLDTC |
| `ledger-batch-data.md` | Q- items of base batch programs, entities.md, jobs.md, and all optional-module programs |

Row format (one row per Q- ID, ordered by ID):

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
