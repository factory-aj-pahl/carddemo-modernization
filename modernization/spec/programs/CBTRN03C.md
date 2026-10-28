# CBTRN03C: Print the daily transaction detail report with account, page, and grand totals

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/CBTRN03C.cbl` (649 lines) |
| Module | base |
| Kind | Batch |
| Transaction ID / Job | JOB-TRANREPT, step STEP10R (`app/jcl/TRANREPT.jcl:59-80`; the same step exists in procedure `app/proc/TRANREPT.prc:57`) |
| BMS map | none |
| Copybooks | `CVTRA05Y` (TRAN-RECORD, 350), `CVACT03Y` (CARD-XREF-RECORD, 50), `CVTRA03Y` (TRAN-TYPE-RECORD, 60), `CVTRA04Y` (TRAN-CAT-RECORD, 60), `CVTRA07Y` (report line layouts) |
| Calls / XCTL targets | `CEE3ABD` (abend, only on error) |
| Data stores | DD `TRANFILE` = `AWS.M2.CARDDEMO.TRANSACT.DALY(+1)` (sequential 350, sorted by card number by the preceding SORT step): Read. DD `CARDXREF` = `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS` (random, key card number X(16)): Read by key. DD `TRANTYPE` = `AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS` (random, key type X(2)): Read by key. DD `TRANCATG` = `AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS` (random, key type X(2) + category 9(4)): Read by key. DD `DATEPARM` = `AWS.M2.CARDDEMO.DATEPARM` (sequential 80): Read first record. DD `TRANREPT` = `AWS.M2.CARDDEMO.TRANREPT(+1)` (new GDG generation, FB 133): Write |

## Purpose
CBTRN03C produces the "Daily Transaction Report". It reads posted transactions that the preceding SORT step has
filtered by processing date and sorted by card number, enriches each with the card's account number and the
descriptions of its transaction type and category, and prints one detail line per transaction. It prints an
account total whenever the card changes, a page total every 20 lines (approximately), and a grand total at the end.
The reporting date range is read from a parameter file and printed in the page header.

## Invocation and state
- JOB-TRANREPT: STEP05R (procedure REPROC) unloads the transaction master KSDS to `TRANSACT.BKUP(+1)`; the second
  STEP05R (SORT) keeps records whose processing date (positions 305-314) is between the hard-coded symbols
  `2022-01-01` and `2022-07-06`, sorts them by card number (positions 263-278, as ZD), and writes `TRANSACT.DALY(+1)`;
  STEP10R runs this program (`app/jcl/TRANREPT.jcl:23-80`).
- DATEPARM record layout (first 21 bytes used): start date X(10), one filler byte, end date X(10), for example
  `2022-01-01 2022-07-06`. No job in the repository creates `AWS.M2.CARDDEMO.DATEPARM`.
- Working-storage state: WS-FIRST-TIME (`Y` until the first detail line), WS-LINE-COUNTER (9(9) COMP-3, starts 0,
  counts every line written except page/grand total and detail-check exceptions noted below), WS-PAGE-SIZE = 20,
  WS-PAGE-TOTAL / WS-ACCOUNT-TOTAL / WS-GRAND-TOTAL (S9(9)V99, start 0), WS-CURR-CARD-NUM (X(16), starts spaces),
  CARD-XREF-RECORD (account of the current card), TRAN-TYPE-RECORD, TRAN-CAT-RECORD.
- Return code: never set (RC 0); errors end in abend U999 via CEE3ABD.

## Processing flow
1. DISPLAY `START OF EXECUTION OF PROGRAM CBTRN03C`.
2. Open, in order (abend on the first failure): TRANFILE INPUT, TRANREPT OUTPUT, CARDXREF INPUT, TRANTYPE INPUT,
   TRANCATG INPUT, DATEPARM INPUT.
3. 0550-DATEPARM-READ: read the first DATEPARM record into WS-DATEPARM-RECORD.
   - Status `00`: DISPLAY `Reporting from ` + start + ` to ` + end.
   - Status `10` (empty file): set END-OF-FILE = `Y`, so no transaction is processed and the report is empty.
   - Other: error and abend (ERR-CBTRN03C-02).
4. Repeat until END-OF-FILE = `Y`:
   1. 1000-TRANFILE-GET-NEXT: read the next transaction INTO TRAN-RECORD (`10` sets END-OF-FILE; other non-`00` abends).
      At end of file TRAN-RECORD still holds the last record read.
   2. IF TRAN-PROC-TS(1:10) is between start and end date (inclusive, string comparison), continue. OTHERWISE
      `NEXT SENTENCE`: control jumps past the period that ends the PERFORM statement, i.e. the loop ends immediately
      and the program goes to the close step (Q-CBTRN03C-01).
   3. IF not end of file:
      1. DISPLAY TRAN-RECORD (raw 350 bytes).
      2. IF TRAN-CARD-NUM differs from WS-CURR-CARD-NUM: IF this is not the first detail, 1120-WRITE-ACCOUNT-TOTALS;
         WS-CURR-CARD-NUM = card; 1500-A-LOOKUP-XREF reads the card's xref (abend if missing).
      3. 1500-B-LOOKUP-TRANTYPE by TRAN-TYPE-CD; 1500-C-LOOKUP-TRANCATG by (TRAN-TYPE-CD, TRAN-CAT-CD) (abend if missing).
      4. 1100-WRITE-TRANSACTION-REPORT.
   4. OTHERWISE (end of file reached and the stale last record is in range): DISPLAY `TRAN-AMT ` + TRAN-AMT and
      `WS-PAGE-TOTAL` + WS-PAGE-TOTAL; add TRAN-AMT (the last record's amount, a second time) to the page and account
      totals; 1110-WRITE-PAGE-TOTALS; 1110-WRITE-GRAND-TOTALS.
5. Close, in order: TRANFILE, TRANREPT, CARDXREF, TRANTYPE, TRANCATG, DATEPARM (abend on failure).
6. DISPLAY `END OF EXECUTION OF PROGRAM CBTRN03C`, GOBACK.

1100-WRITE-TRANSACTION-REPORT:
1. IF WS-FIRST-TIME = `Y`: set it to `N`, copy the start and end dates into the name header, PERFORM 1120-WRITE-HEADERS.
2. IF WS-LINE-COUNTER MOD 20 = 0: 1110-WRITE-PAGE-TOTALS, then 1120-WRITE-HEADERS.
3. Add TRAN-AMT to WS-PAGE-TOTAL and WS-ACCOUNT-TOTAL; 1120-WRITE-DETAIL.

Line writers (every write goes through 1111-WRITE-REPORT-REC):
- 1120-WRITE-HEADERS: name header, blank line (133 spaces), header 1, header 2 (133 dashes); counter +4.
- 1120-WRITE-DETAIL: INITIALIZE the detail line, fill it (BR-CBTRN03C-04), write; counter +1.
- 1110-WRITE-PAGE-TOTALS: page-total line with WS-PAGE-TOTAL; WS-GRAND-TOTAL += WS-PAGE-TOTAL; WS-PAGE-TOTAL = 0;
  counter +1; dashes line; counter +1.
- 1120-WRITE-ACCOUNT-TOTALS: account-total line with WS-ACCOUNT-TOTAL; WS-ACCOUNT-TOTAL = 0; counter +1; dashes line; counter +1.
- 1110-WRITE-GRAND-TOTALS: grand-total line; counter unchanged.

## Rules
### Business rules
- **BR-CBTRN03C-01**: Only transactions with TRAN-PROC-TS(1:10) >= start date and <= end date are reported. The first
  record outside the range ends the report silently (Q-CBTRN03C-01). With the JCL as delivered, the preceding SORT
  already removed out-of-range records using its own hard-coded range. Source: `app/cbl/CBTRN03C.cbl:173-178 (main procedure)`, `app/jcl/TRANREPT.jcl:40-48`.
- **BR-CBTRN03C-02**: The account number printed is XREF-ACCT-ID of the transaction's card. The xref is read only when
  the card number changes from the previous record; consecutive records of the same card reuse it.
  Source: `app/cbl/CBTRN03C.cbl:181-188 (main procedure)`, `app/cbl/CBTRN03C.cbl:484-492 (1500-A-LOOKUP-XREF)`.
- **BR-CBTRN03C-03**: An account-total line is written when the card number changes, before the first detail of the new
  card, except before the very first detail. It shows the sum of the previous card's amounts and resets that sum. Totals
  are per card, although labelled "Account Total". Source: `app/cbl/CBTRN03C.cbl:181-185`, `app/cbl/CBTRN03C.cbl:306-316 (1120-WRITE-ACCOUNT-TOTALS)`.
- **BR-CBTRN03C-04**: Detail line (114 bytes, padded to 133 with spaces): TRAN-ID X(16), space, account ID X(11), space,
  type code X(2), `-`, type description X(15) (first 15 characters of the 50-byte TRAN-TYPE-DESC), space, category code
  9(4), `-`, category description X(29) (first 29 characters of TRAN-CAT-TYPE-DESC), space, TRAN-SOURCE X(10), 4 spaces,
  amount edited `-ZZZ,ZZZ,ZZZ.ZZ` (15 characters: a leading `-` for negative values, a space for positive; leading zeros
  and their commas replaced by spaces; zero prints as 15 spaces), 2 spaces. Examples: `183.88` -> `         183.88`
  preceded by one space; -47.88 -> `-         47.88`.
  Source: `app/cbl/CBTRN03C.cbl:361-374 (1120-WRITE-DETAIL)`, `app/cpy/CVTRA07Y.cpy:15-31`.
- **BR-CBTRN03C-05**: Header block (4 lines): (1) `DALYREPT` padded to 38, `Daily Transaction Report` padded to 41,
  `Date Range: `, start date, ` to `, end date (115 bytes); (2) 133 spaces; (3) `Transaction ID` (17), `Account ID` (12),
  `Transaction Type` (19), `Tran Category` (35), `Tran Source` (14), 1 space, `        Amount` (16); (4) 133 dashes.
  The block is written before the first detail and after every page total triggered by the line counter.
  Source: `app/cbl/CBTRN03C.cbl:324-341 (1120-WRITE-HEADERS)`, `app/cpy/CVTRA07Y.cpy:4-13`, `app/cpy/CVTRA07Y.cpy:33-48`.
- **BR-CBTRN03C-06**: Total lines (112 bytes, padded to 133): `Page Total` + 1 space + 86 dots + amount; `Account Total`
  + 84 dots + amount; `Grand Total` + 86 dots + amount; each amount edited `+ZZZ,ZZZ,ZZZ.ZZ` (`+` or `-` sign in the first
  position, zero prints as 15 spaces). Each page or account total line is followed by a dashes line.
  Source: `app/cpy/CVTRA07Y.cpy:50-66`, `app/cbl/CBTRN03C.cbl:293-322`.
- **BR-CBTRN03C-07**: Page break: before writing a detail, IF WS-LINE-COUNTER MOD 20 = 0, write the page total and a new
  header block. The counter includes header, detail, account-total, page-total, and dash lines, so breaks happen only when
  a detail write finds the counter exactly on a multiple of 20; account-total lines can push the counter past a multiple and
  delay the break to the next multiple (Q-CBTRN03C-04). The very first header block makes the counter 4, so the first check
  (4 MOD 20) does not break. Source: `app/cbl/CBTRN03C.cbl:274-290 (1100-WRITE-TRANSACTION-REPORT)`.
- **BR-CBTRN03C-08**: WS-GRAND-TOTAL is the sum of all page totals written; it is updated only when a page total is written.
  Source: `app/cbl/CBTRN03C.cbl:297 (1110-WRITE-PAGE-TOTALS)`.
- **BR-CBTRN03C-09**: End of report: when end of file is reached (and the last record was in range), the last transaction's
  amount is added again to the page and account totals, a final page total and dashes line are written, then the grand
  total line. No account total is written for the last card. Source: `app/cbl/CBTRN03C.cbl:197-204 (main procedure)`.
- **BR-CBTRN03C-10**: Every reported transaction is also displayed raw on SYSOUT, and the end-of-file branch displays
  `TRAN-AMT ` + amount and `WS-PAGE-TOTAL` + page total (no space before the value).
  Source: `app/cbl/CBTRN03C.cbl:180`, `app/cbl/CBTRN03C.cbl:198-199`.

### Validation rules
- **VR-CBTRN03C-01**: Every reported transaction's card must exist in the xref, its type in TRANTYPE, and its
  type+category in TRANCATG; a missing one stops the job (ERR-CBTRN03C-04). Source: `app/cbl/CBTRN03C.cbl:484-512`.

### Navigation and keys
Not applicable (batch).

### Error handling
- **ERR-CBTRN03C-01**: Open failure (status not `00`) -> message, status line, `ABENDING PROGRAM`, abend U999. Messages:
  `ERROR OPENING TRANFILE`, `ERROR OPENING REPTFILE`, `ERROR OPENING CROSS REF FILE`, `ERROR OPENING TRANSACTION TYPE FILE`,
  `ERROR OPENING TRANSACTION CATG FILE`, `ERROR OPENING DATE PARM FILE`. Source: `app/cbl/CBTRN03C.cbl:376-482 (0000-TRANFILE-OPEN .. 0500-DATEPARM-OPEN)`.
- **ERR-CBTRN03C-02**: DATEPARM read status other than `00`/`10` -> `ERROR READING DATEPARM FILE`, status line, abend U999.
  Source: `app/cbl/CBTRN03C.cbl:220-243 (0550-DATEPARM-READ)`.
- **ERR-CBTRN03C-03**: TRANFILE read status other than `00`/`10` -> `ERROR READING TRANSACTION FILE`, status line, abend U999.
  Source: `app/cbl/CBTRN03C.cbl:248-272 (1000-TRANFILE-GET-NEXT)`.
- **ERR-CBTRN03C-04**: Lookup not found (INVALID KEY) -> one of `INVALID CARD NUMBER : ` + card number,
  `INVALID TRANSACTION TYPE : ` + type code, `INVALID TRAN CATG KEY : ` + 6-byte type+category key; then
  `FILE STATUS IS: NNNN0023` (the program forces IO-STATUS to `23`), `ABENDING PROGRAM`, abend U999. Other read statuses
  are not checked. Source: `app/cbl/CBTRN03C.cbl:484-512 (1500-A-LOOKUP-XREF, 1500-B-LOOKUP-TRANTYPE, 1500-C-LOOKUP-TRANCATG)`.
- **ERR-CBTRN03C-05**: Report write status not `00` -> `ERROR WRITING REPTFILE`, status line, abend U999.
  Source: `app/cbl/CBTRN03C.cbl:343-359 (1111-WRITE-REPORT-REC)`.
- **ERR-CBTRN03C-06**: Close failure -> message, status line, abend: `ERROR CLOSING POSTED TRANSACTION FILE`,
  `ERROR CLOSING REPORT FILE`, `ERROR CLOSING CROSS REF FILE`, `ERROR CLOSING TRANSACTION TYPE FILE`,
  `ERROR CLOSING TRANSACTION CATG FILE`, `ERROR CLOSING DATE PARM FILE`. Source: `app/cbl/CBTRN03C.cbl:514-621 (9000-TRANFILE-CLOSE .. 9500-DATEPARM-CLOSE)`.
- **ERR-CBTRN03C-07**: Status line `FILE STATUS IS: NNNN` + 4 characters as in the other batch programs; CEE3ABD code 999.
  Source: `app/cbl/CBTRN03C.cbl:626-646 (9999-ABEND-PROGRAM, 9910-DISPLAY-IO-STATUS)`.

## Data effects
Creates `AWS.M2.CARDDEMO.TRANREPT(+1)` (133-byte report lines). Reads only; no master data is changed.

## Quirks
- **Q-CBTRN03C-01**: `NEXT SENTENCE` inside the IF/END-IF at line 177 transfers control to the statement after the next
  period, which is the period ending the whole `PERFORM UNTIL ... END-PERFORM.` at line 206. The first transaction outside
  the date range therefore ends the report: no further records, no page total, no grand total, and no message. Verified
  under GnuCOBOL: a first out-of-range record gives a 0-byte report. The likely intent was to skip the record.
  Recommendation: fix (skip out-of-range records), but note the delivered JCL pre-filters with SORT so the defect is
  latent. Source: `app/cbl/CBTRN03C.cbl:173-178`, `app/cbl/CBTRN03C.cbl:206`.
- **Q-CBTRN03C-02**: At end of file the last transaction's amount is added a second time to the page (and therefore
  grand) total. Verified with the sample data: the last page total is 1,725.65 = 194.33 + 765.66 + 765.66. Recommendation:
  fix (keep a parity mode if reports are compared). Source: `app/cbl/CBTRN03C.cbl:197-203`.
- **Q-CBTRN03C-03**: The last card's account total is never printed. Recommendation: fix. Source: `app/cbl/CBTRN03C.cbl:197-204`.
- **Q-CBTRN03C-04**: Page breaks depend on the combined line counter hitting an exact multiple of 20 at a detail line;
  account-total and page-total lines shift it, so pages have irregular length (the first sample page has 22 lines
  including its page total; later pages can run to the next multiple of 20). The page-total line also appears right
  after an account-total line. Recommendation: decide (define page length in the target). Source: `app/cbl/CBTRN03C.cbl:282-285`.
- **Q-CBTRN03C-05**: Totals are per card number but labelled "Account Total"; an account with two cards prints two
  account totals, and the report sorts by card, not account. Recommendation: decide. Source: `app/cbl/CBTRN03C.cbl:181-185`, `app/jcl/TRANREPT.jcl:46`.
- **Q-CBTRN03C-06**: The date range exists twice: hard-coded SORT symbols in the JCL (`2022-01-01`..`2022-07-06`) and the
  DATEPARM file read by the program; no job creates DATEPARM. If they disagree the header shows one range while the data
  was filtered by the other. Recommendation: fix (single run-date parameter). Source: `app/jcl/TRANREPT.jcl:43-48`, `app/jcl/TRANREPT.jcl:73-74`.
- **Q-CBTRN03C-07**: An empty DATEPARM file (status `10`) sets END-OF-FILE and produces an empty report with RC 0 and no
  warning. Recommendation: fix (fail). Source: `app/cbl/CBTRN03C.cbl:225-236`.
- **Q-CBTRN03C-08**: Raw transaction records including full card numbers are displayed on SYSOUT (rule CD070).
  Recommendation: fix. Source: `app/cbl/CBTRN03C.cbl:180`.
- **Q-CBTRN03C-09**: Type and category descriptions are silently truncated to 15 and 29 characters (for example
  `Regular Cash Advance` -> `Regular Cash Ad`). Recommendation: preserve for report parity, decide for the target layout.
  Source: `app/cpy/CVTRA07Y.cpy:22`, `app/cpy/CVTRA07Y.cpy:26`.
- **Q-CBTRN03C-10**: Totals are S9(9)V99 without ON SIZE ERROR; a grand total of 1,000,000,000.00 or more loses digits.
  The edit pictures hold 9 integer digits. Recommendation: decide. Source: `app/cbl/CBTRN03C.cbl:134-136`.
- **Q-CBTRN03C-11**: The date check uses TRAN-PROC-TS, which POSTTRAN sets to the run timestamp; an unposted
  (blank) timestamp sorts below any date and ends the report (Q-CBTRN03C-01). Recommendation: preserve. Source: `app/cbl/CBTRN03C.cbl:173-174`.

## Test scenarios
Sample run (verified under GnuCOBOL, `-fsign=EBCDIC`): input = the 262 transactions posted by POSTTRAN on the sample data,
sorted by card number, DATEPARM `2026-01-01 2026-12-31` (covering the run date stamped by POSTTRAN). With the mainframe JCL
the dates would be the 2022 range.

- **TS-CBTRN03C-01** (covers BR-CBTRN03C-04, BR-CBTRN03C-05, BR-CBTRN03C-03): Given the sample run, then the report has 451
  lines; lines 1-4 are the header block (`DALYREPT` ... `Date Range: 2026-01-01 to 2026-12-31`, blank, column headings, dashes);
  line 5 is `0000000058866561 00000000050 01-Purchase        0001-Regular Sales Draft           POS TERM               183.88`
  (card 0500****5740); after its 6 transactions comes `Account Total` + 84 dots + `+      1,453.87` and a dashes line.
- **TS-CBTRN03C-02** (covers BR-CBTRN03C-07, Q-CBTRN03C-04): Given the sample run, then line 21 is `Page Total ` + 86 dots +
  `+      4,665.52`, line 22 dashes, and lines 23-26 a new header block.
- **TS-CBTRN03C-03** (covers BR-CBTRN03C-09, Q-CBTRN03C-02, Q-CBTRN03C-03): Given the sample run, then the report ends with
  the last two details of card 9805****6588 (194.33 and 765.66), `Page Total ` + dots + `+      1,725.65`, dashes, and
  `Grand Total` + dots + `+     78,720.36`; there is no account-total line for that card; SYSOUT shows
  `TRAN-AMT 0000007656F` and `WS-PAGE-TOTAL0000009599I` on z/OS (GnuCOBOL shows `00000076566+` and `00000095999+`).
- **TS-CBTRN03C-04** (covers BR-CBTRN03C-04): Given a credit of -47.88, then the amount column shows `-         47.88`;
  given an amount of 0.00, the column is 15 spaces.
- **TS-CBTRN03C-05** (covers Q-CBTRN03C-01): Given DATEPARM `2022-01-01 2022-07-06` and an input whose first record has
  processing date `2022-07-18`, then the report is empty (0 records), SYSOUT shows only the start message,
  `Reporting from 2022-01-01 to 2022-07-06`, and the end message, RC 0.
- **TS-CBTRN03C-06** (covers Q-CBTRN03C-07): Given an empty DATEPARM file, then no `Reporting from` message is shown and the
  report is empty, RC 0.
- **TS-CBTRN03C-07** (covers ERR-CBTRN03C-04): Given a transaction with type code `99`, then SYSOUT shows the raw record,
  `INVALID TRANSACTION TYPE : 99`, `FILE STATUS IS: NNNN0023`, `ABENDING PROGRAM`, abend U999.
- **TS-CBTRN03C-08** (covers BR-CBTRN03C-01): Given an empty TRANFILE, then the report is empty (the blank stale record is
  out of range), RC 0.

## Open questions
- Who creates the DATEPARM dataset in production, and should the SORT step's hard-coded dates be driven by the same value
  (Q-CBTRN03C-06)?
- Are page length and per-card (not per-account) totals requirements or accidents (Q-CBTRN03C-04, Q-CBTRN03C-05)?
- Is the report compared byte-for-byte anywhere downstream (which would argue for a parity mode for Q-CBTRN03C-02/03)?
