# Glossary

CardDemo mixes mainframe vocabulary, card-issuing vocabulary, and the identifiers of its own modernization contract. This page defines the terms you will meet in the code, the specification, and the rest of this wiki.

## Mainframe terms

| Term | Meaning |
|:--|:--|
| **3270** | IBM block-mode terminal. The user fills fields on a 24x80 screen and presses an attention key; the whole screen is sent at once. The web app imitates this, including holding keys until a screen has arrived |
| **Abend** | Abnormal end of a program. COBOL programs here abend through `CEE3ABD`; the modern batch jar maps any abend to exit code 99 (B-ABEND) |
| **AID / attention key** | The key that sends a 3270 screen: ENTER, PF1-PF24, CLEAR, PA1-PA3. In the screen API it is the `key` field (`modernization/backend/common/src/main/java/com/carddemo/common/screen/Key.java`) |
| **AIX** | VSAM alternate index, a secondary key over a KSDS (for example the card cross-reference by account). Becomes a secondary SQL index |
| **BMS map** | CICS screen definition (`app/bms/*.bms`). Each generates a copybook in `app/cpy-bms`. A map is named after its program without the trailing `C` (`COSGN00` for `COSGN00C`) |
| **CA7 / Control-M** | Mainframe job schedulers. Definitions are in `app/scheduler/`; they disagree with each other and with the runbook, so the Airflow DAGs follow the runbook (`modernization/spec/jobs.md`) |
| **CICS** | IBM transaction monitor that runs the online programs |
| **COMMAREA** | Communication area passed between pseudo-conversational turns and on `XCTL`. CardDemo's layout is `app/cpy/COCOM01Y.cpy`. Replaced by the server session plus request parameters (C-SESSION) |
| **COMP-3 / packed decimal** | Two digits per byte with a trailing sign nibble. Decoded by `modernization/backend/common/src/main/java/com/carddemo/common/codec/Packed.java` |
| **Copybook** | Shared COBOL source included with `COPY`, usually a record layout (`app/cpy`). Record copybooks mostly end in `Y`, for example `CVACT01Y` for the account record |
| **DD / SYSIN / SYSOUT** | JCL data definitions: the files a job step reads and writes. SYSIN carries control cards, SYSOUT the printed output. The batch jar writes the SYSOUT text to standard output |
| **GDG** | Generation data group, a versioned dataset (used for transaction backups). Replaced by snapshot tables written by the BACKUP step |
| **GnuCOBOL / cobc** | Open-source COBOL compiler. Used by the root tooling and by the parity harness to run the original programs |
| **IDCAMS** | Utility that defines and loads VSAM files. Its DEFINE jobs became Flyway migrations |
| **IMS / HIDAM / DL/I / PSB** | IBM hierarchical database used by the authorization module. A PSB schedule becomes the start of a database transaction; segments become two tables |
| **JCL** | Job control language, the scripts in `app/jcl` that run batch programs |
| **KSDS** | VSAM key-sequenced dataset, the indexed file type behind every master file. Becomes a table with a primary key |
| **Overpunch / zoned decimal** | Signed number stored as text where the last digit carries the sign (`{`, `A`-`I` positive, `}`, `J`-`R` negative). Decoded by `modernization/backend/common/src/main/java/com/carddemo/common/codec/Overpunch.java` |
| **PF keys** | Program function keys. CardDemo keeps their meanings: F3 back or exit, F7/F8 page backward/forward, F5 save or action, F12 cancel |
| **Pseudo-conversational** | CICS style where a program ends after every screen and is restarted on the next key, carrying state in the COMMAREA |
| **RETURN-CODE** | Job step condition code. The batch jar keeps the same convention: 0, 4, 8, 12, 16, 99 |
| **Transaction ID** | Four-character CICS code that starts a program, for example `CC00` (sign-on), `CB00` (bill payment). Listed in `modernization/web/src/screens.ts` |
| **USRSEC** | VSAM user security file with plaintext passwords. Replaced by the `app_user` table with BCrypt hashes |
| **XCTL** | CICS transfer of control to another program. Replaced by the `navigate` field of the screen response |

## Business terms

| Term | Meaning |
|:--|:--|
| **Account** | A credit account with balance, credit limit, cash limit, cycle credit and debit, group ID. Record `app/cpy/CVACT01Y.cpy` |
| **Business date** | The date a cycle run posts. Every cycle job takes `businessDate=YYYY-MM-DD`; a missing or invalid one exits 12 (B-DATE) |
| **Card cross-reference (XREF)** | Links a card number to its account and customer (`app/cpy/CVACT03Y.cpy`) |
| **Category balance (TCATBAL)** | Running balance per account, transaction type, and category; interest is charged on these |
| **Daily transaction file (DALYTRAN)** | Fixed-width file of the day's card transactions, the input of POSTTRAN. Not a table |
| **Disclosure group** | Interest rate per account group, type, and category, with a `DEFAULT` group fallback |
| **Linked account** | An account attached to a regular user in the `user_account` table (D-USER-ACCT). A regular user sees only linked accounts |
| **Pending authorization** | A card authorization decision held in the authorization module (summary per account, detail per request) until purged |
| **Reject (DALYREJS)** | A daily transaction POSTTRAN refused, with a reason code such as `0102 OVERLIMIT TRANSACTION` |
| **Transaction type and category** | Two-level classification of transactions (`trantype.txt`, `trancatg.txt`), maintained online by the transaction-type module |

## Batch jobs

| Job | Program | What it does |
|:--|:--|:--|
| **POSTTRAN** | CBTRN02C | Validates and posts the daily transaction file; writes rejects |
| **INTCALC** | CBACT04C | Calculates monthly interest from category balances and disclosure groups |
| **CREASTMT** | CBSTM03A, CBSTM03B | Writes text and HTML account statements |
| **TRANREPT** | CBTRN03C | Transaction detail report for a date range |
| **COMBTRAN** | sort/merge | Merges system transactions into the master; disappears in the target because interest is inserted directly |
| **BACKUP / RESTORE** | none | Target-only steps: snapshot tables before posting, and restore by date or backup ID |
| **INTSTATUS** | none | Target-only jar command that tells the DAG whether the month's interest is already calculated |

## Modernization identifiers

These IDs appear in code comments, test names, `@Covers` annotations, and commit messages. They are defined in `modernization/spec/README.md` and `modernization/decisions/README.md`.

| Prefix | Meaning | Example |
|:--|:--|:--|
| `BR-<MEMBER>-nn` | Business rule | `BR-COBIL00C-03` |
| `VR-<MEMBER>-nn` | Input validation rule | `VR-COACTUPC-17` |
| `NAV-<MEMBER>-nn` | Navigation or key-handling rule | `NAV-COBIL00C-02` |
| `ERR-<MEMBER>-nn` | Error or file-status handling | `ERR-CBTRN02C-04` |
| `Q-<MEMBER>-nn` | Quirk: suspected defect, dead code, race, or surprising behavior | `Q-COBIL00C-01` |
| `TS-<MEMBER>-nn` | Test scenario (Given/When/Then) | `TS-COBIL00C-05` |
| `ENT-<NAME>` / `JOB-<NAME>` | Entity in `entities.md` / job in `jobs.md` | `ENT-ACCOUNT`, `JOB-POSTTRAN` |
| `D-*` | User decision | `D-USER-ACCT`, `D-INTEREST` |
| `DF-*` | Recorded default | `DF-6` (lowest card number is used) |
| `B-*` | Batch cross-cutting decision | `B-UOW` (one commit per daily record), `B-ABEND`, `B-MASK`, `B-PARITY` |
| `C-*` | Online cross-cutting decision | `C-RENDER` (screen envelope), `C-SCOPE`, `C-ROLE`, `C-MASK`, `C-SESSION` |
| `RP-*` | Reference patch applied to a scratch copy of the COBOL for parity | `RP-CBTRN02C-1` |

| Term | Meaning |
|:--|:--|
| **Fidelity ledger** | `modernization/decisions/ledger-*.md`: one row per quirk with a decision of FIX, PRESERVE, CHANGE, PLATFORM, or NOT-PORTED. See [Fidelity ledger](../verification/fidelity-ledger.md) |
| **Owed pair** | A (program, dataset) combination the parity harness must run and match, listed in `modernization/verification/parity/datasets.md` |
| **Parity** | Byte-for-byte agreement of a Java job's outputs with the original COBOL's under GnuCOBOL |
| **Bounded context** | One business area of the backend, a Maven module such as `iam` or `card`. See [Modules](../modules/index.md) |
| **Published API** | A context's `api` package; the only code other contexts may use |
| **Screen envelope** | The `ScreenResponse` record every screen endpoint returns (C-RENDER) |
| **Selective conversion** | Running one context alone, as `billpay-service` does |
| **Inbox / outbox** | Batch input and output directories, `~/.carddemo/inbox/<date>/` and `~/.carddemo/outbox/<date>/` |

## Related pages

- [Architecture](architecture.md)
- [Specification](../verification/specification.md)
- [Data models](../reference/data-models.md)
