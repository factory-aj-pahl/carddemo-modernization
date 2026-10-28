# CBSTM03A: Produce plain-text and HTML account statements for every card in the cross-reference file

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/CBSTM03A.CBL` (924 lines) |
| Module | base |
| Kind | Batch |
| Transaction ID / Job | CREASTMT, step STEP040 (`app/jcl/CREASTMT.JCL:79-96`), run after the SORT and REPRO steps that build the TRXFL file (`app/jcl/CREASTMT.JCL:22-62`) |
| BMS map | none |
| Copybooks | `COSTM01` (TRNX-RECORD, 350 bytes, key card + transaction ID), `CVACT03Y` (CARD-XREF-RECORD, 50 bytes), `CUSTREC` (CUSTOMER-RECORD, 500 bytes), `CVACT01Y` (ACCOUNT-RECORD, 300 bytes) |
| Calls / XCTL targets | `CBSTM03B` (file I/O subroutine, every TRNX/XREF/CUST/ACCT open, read, and close), `CEE3ABD` (Language Environment abend, called with no arguments) |
| Data stores | Through CBSTM03B: DD `TRNXFILE` = `AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS` (KSDS, 350 bytes, key card number + transaction ID, offset 0 length 32): Browse; DD `XREFFILE` = `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS`: Browse; DD `CUSTFILE` = `AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS`: Read (random by customer ID); DD `ACCTFILE` = `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS`: Read (random by account ID). Directly: DD `STMTFILE` = `AWS.M2.CARDDEMO.STATEMNT.PS` (sequential, FB 80): Write; DD `HTMLFILE` = `AWS.M2.CARDDEMO.STATEMNT.HTML` (sequential, FB 100): Write |

## Purpose
CBSTM03A writes one account statement per card cross-reference record, in two formats at once: an
80-column plain-text statement and an HTML page. It loads every transaction from the card-sorted
transaction copy into an in-memory table, then for each card looks up the customer and account and
prints name, address, account ID, current balance, FICO score, the card's transactions, and their
total. It changes no master data. The program is also a deliberate showcase of legacy constructs
(z/OS control-block addressing, ALTER and GO TO, COMP and COMP-3 fields, a two-dimensional table, and
a called I/O subroutine), so several parts of its control flow have no business meaning.

## Invocation and state
- Run as STEP040 of CREASTMT with COND=(0,NE), so it runs only when the delete/define, SORT, REPRO,
  and IEFBR14 steps all ended RC 0. DDs: `TRNXFILE`, `XREFFILE`, `ACCTFILE`, `CUSTFILE` (DISP=SHR),
  `STMTFILE` and `HTMLFILE` (DISP=(NEW,CATLG,DELETE)), `SYSOUT`, `SYSPRINT`. No PARM, no SYSIN.
- Input preparation in the same job: STEP010 sorts the TRANSACT KSDS by card number (positions
  263-278) then transaction ID (1-16) and reformats each record so the card number comes first
  (`OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)`); STEP020 REPROs the result into the TRXFL KSDS
  defined with KEYS(32 0) (`app/jcl/CREASTMT.JCL:29-61`).
- Working-storage state: WS-FL-DD (file-open state machine, initially `TRNXFILE`), CR-CNT/TR-CNT
  (table load counters, COMP), CR-JMP/TR-JMP (table scan subscripts, COMP), WS-TOTAL-AMT (COMP-3
  S9(9)V99 statement total), WS-SAVE-CARD (previous card during load), END-OF-FILE (`N`/`Y`, for the
  xref loop), WS-M03B-AREA (parameter block passed to CBSTM03B: DD name, operation, 2-byte status,
  key, key length, 1000-byte record buffer), WS-TRNX-TABLE (51 cards x 10 transactions), and
  WS-TRN-TBL-CNTR (transaction count per card).
- Return code: never set; normal end RC 0. Every I/O failure displays a message and calls CEE3ABD.

## Processing flow
1. Control-block diagnostics: address the PSA at address 0, follow it to the TCB and the TIOT,
   DISPLAY `Running JCL : ` + job name + ` Step ` + step name, then DISPLAY `DD Names from TIOT: ` and
   one line per DD in the TIOT (BR-CBSTM03A-01).
2. OPEN OUTPUT STMT-FILE and HTML-FILE; INITIALIZE the transaction table and counters.
3. File-open state machine (0000-START, BR-CBSTM03A-02), driven by WS-FL-DD:
   1. `TRNXFILE`: open TRNXFILE, read its first record, set CR-CNT = 1, TR-CNT = 0, save the card
      number, set WS-FL-DD = `READTRNX`.
   2. `READTRNX`: load the whole file into the table (BR-CBSTM03A-03); at end of file set WS-FL-DD =
      `XREFFILE`.
   3. `XREFFILE`: open XREFFILE; set `CUSTFILE`. 4. `CUSTFILE`: open CUSTFILE; set `ACCTFILE`.
   5. `ACCTFILE`: open ACCTFILE, then GO TO 1000-MAINLINE.
4. 1000-MAINLINE: FOR EACH xref record, in card-number order, until end of file:
   1. Read the customer by XREF-CUST-ID and the account by XREF-ACCT-ID (any status other than `00`
      abends).
   2. 5000-CREATE-STATEMENT: write the text statement header and the HTML header, name/address, basic
      details, and transaction table header (BR-CBSTM03A-05 to BR-CBSTM03A-08, BR-CBSTM03A-11).
   3. Set WS-TOTAL-AMT = 0; 4000-TRNXFILE-GET: write one text line and one HTML table row per
      transaction of this card and add each amount to the total (BR-CBSTM03A-09); then write the
      total and the end-of-statement lines and close the HTML document (BR-CBSTM03A-10).
5. Close TRNXFILE, XREFFILE, CUSTFILE, ACCTFILE (through CBSTM03B), then STMT-FILE and HTML-FILE;
   GOBACK.

## Rules
### Business rules
- **BR-CBSTM03A-01**: Before any file work the program walks z/OS control blocks: PSA (address 0) at
  offset 536 holds the TCB address, TCB offset 12 holds the TIOT address. It displays
  `Running JCL : ` + TIOTNJOB (8) + ` Step ` + TIOTJSTP (8), then `DD Names from TIOT: `. It then
  steps through TIOT entries in fixed 20-byte increments starting 24 bytes into the TIOT. WHILE the 4
  bytes after the current 20-byte segment are not LOW-VALUES and the entry length byte is not
  LOW-VALUES: IF the 3-byte UCB address is not LOW-VALUES, display `: ` + DD name + ` -- valid UCB`,
  OTHERWISE `: ` + DD name + ` --  null UCB` (two spaces before `null`). After the loop the entry it
  stopped on is displayed once more, with ` -- valid UCB` or ` -- null  UCB` (two spaces after
  `null`). Source: `app/cbl/CBSTM03A.CBL:235-260`, `app/cbl/CBSTM03A.CBL:266-291 (procedure division prologue)`.
- **BR-CBSTM03A-02**: Files are opened in a fixed order, TRNXFILE, then XREFFILE, CUSTFILE, ACCTFILE,
  through a state machine: EVALUATE WS-FL-DD; WHEN `TRNXFILE`/`XREFFILE`/`CUSTFILE`/`ACCTFILE`, ALTER
  the paragraph 8100-FILE-OPEN to proceed to 8100/8200/8300/8400-xxx-OPEN and GO TO 8100-FILE-OPEN;
  WHEN `READTRNX`, GO TO 8500-READTRNX-READ; WHEN OTHER, GO TO 9999-GOBACK (unreachable, because only
  the program sets WS-FL-DD). Each open paragraph sets the next state and jumps back to 0000-START;
  8400-ACCTFILE-OPEN jumps to 1000-MAINLINE instead. The net effect is the sequence: open TRNXFILE,
  read the first transaction, load all transactions, open XREFFILE, open CUSTFILE, open ACCTFILE,
  main loop. Source: `app/cbl/CBSTM03A.CBL:296-314 (0000-START)`,
  `app/cbl/CBSTM03A.CBL:726-815 (8100-FILE-OPEN through 8400-ACCTFILE-OPEN)`.
- **BR-CBSTM03A-03**: All transactions are loaded into a table of up to 51 cards, each with up to 10
  transactions (16-byte transaction ID + 318-byte remainder). Records arrive in card + transaction ID
  order. For each record: IF its card equals the previous record's card, TR-CNT = TR-CNT + 1;
  OTHERWISE store TR-CNT as the previous card's count, CR-CNT = CR-CNT + 1, TR-CNT = 1. The card number,
  transaction ID, and remainder go into slot (CR-CNT, TR-CNT). Read the next record: status `00`
  repeats the step, status `10` stores the final TR-CNT and ends the load. The first record starts
  with CR-CNT = 1, TR-CNT = 0 and the saved card equal to its own card, so it lands in slot (1, 1).
  No bounds check exists (Q-CBSTM03A-04). Source: `app/cbl/CBSTM03A.CBL:225-233`,
  `app/cbl/CBSTM03A.CBL:756-760 (8100-TRNXFILE-OPEN)`, `app/cbl/CBSTM03A.CBL:818-853 (8500-READTRNX-READ, 8599-EXIT)`.
- **BR-CBSTM03A-04**: One statement is produced per xref record, in ascending card-number order. The
  customer is read with key XREF-CUST-ID (9 characters) and the account with key XREF-ACCT-ID
  (11 characters). The inner `IF END-OF-FILE = 'N'` before the xref read is always true. Source:
  `app/cbl/CBSTM03A.CBL:316-329 (1000-MAINLINE)`, `app/cbl/CBSTM03A.CBL:368-414 (2000-CUSTFILE-GET, 3000-ACCTFILE-GET)`.
- **BR-CBSTM03A-05**: Text statement header, 16 lines of 80 bytes, in this order: line 0
  (31 `*` + `START OF STATEMENT` + 31 `*`); line 1 name (75) + 5 spaces; line 2 address line 1 (50);
  line 3 address line 2 (50); line 4 composed address line 3 (80); line 5 (80 `-`); line 6 (33 spaces +
  `Basic Details` + 34 spaces); line 5 again; line 7 `Account ID         :` + account ID; line 8
  `Current Balance    :` + balance; line 9 `FICO Score         :` + FICO score; line 10 (80 `-`);
  line 11 (30 spaces + `TRANSACTION SUMMARY ` + 30 spaces); line 12 (80 `-`); line 13 `Tran ID` padded
  to 16 + `Tran Details` padded to 51 + `  Tran Amount`; line 12 again. The program INITIALIZEs the
  line group first, which blanks the variable fields and leaves the literal FILLERs intact.
  Source: `app/cbl/CBSTM03A.CBL:85-146`, `app/cbl/CBSTM03A.CBL:458-504 (5000-CREATE-STATEMENT)`.
- **BR-CBSTM03A-06**: Name line: STRING first name up to its first space, one space, middle name up to
  its first space, one space, last name up to its first space, one space, into the 75-byte field
  (example `Aniya Alba Von`). Address line 1 and 2 are copied whole (50 bytes each). Address line 3
  is STRING city (CUST-ADDR-LINE-3) up to its first space, space, state code up to its first space,
  space, country code up to its first space, space, ZIP up to its first space, space (example
  `New OR USA 04257` for the city `New Aricchester`). Source: `app/cbl/CBSTM03A.CBL:462-481 (5000-CREATE-STATEMENT)`.
- **BR-CBSTM03A-07**: Basic details formatting: account ID is the 11-digit ACCT-ID left-justified in
  20 bytes (`00000000050` + 9 spaces); current balance is ACCT-CURR-BAL (S9(10)V99) moved to
  `9(9).99-`, which keeps 9 integer digits with leading zeros and puts a trailing `-` for negative or
  a space otherwise (`000001945.87 `); FICO score is the 3-digit score left-justified in 20 bytes
  (`623`). Source: `app/cbl/CBSTM03A.CBL:107-119`, `app/cbl/CBSTM03A.CBL:483-485 (5000-CREATE-STATEMENT)`.
- **BR-CBSTM03A-08**: Statement scope: every transaction in the TRXFL file for the card is printed;
  there is no date range, statement period, or posting-status filter. Transactions whose card is not
  in the xref file are never printed. Source: `app/cbl/CBSTM03A.CBL:416-432 (4000-TRNXFILE-GET)`.
- **BR-CBSTM03A-09**: Transaction lines: FOR CR-JMP = 1 upward UNTIL CR-JMP > CR-CNT OR table card
  (CR-JMP) > xref card: IF table card = xref card, FOR each of its WS-TRCT transactions in load order
  (transaction ID order): write text line 14 = transaction ID (16) + space + description (first 49 of
  100 characters) + `$` + amount as `Z(9).99-` (13 characters, leading zeros blank, trailing sign:
  `      183.88 `, `       47.88-`, zero is `         .00 `), write the HTML row (BR-CBSTM03A-11), and
  ADD TRNX-AMT (signed) to WS-TOTAL-AMT. Source: `app/cbl/CBSTM03A.CBL:132-137`,
  `app/cbl/CBSTM03A.CBL:416-432 (4000-TRNXFILE-GET)`, `app/cbl/CBSTM03A.CBL:675-679 (6000-WRITE-TRANS)`.
- **BR-CBSTM03A-10**: Statement trailer: total = WS-TOTAL-AMT (COMP-3 S9(9)V99, the signed sum of the
  card's transaction amounts, credits reduce it) formatted `Z(9).99-`. Write line 12 (80 `-`), line
  14A (`Total EXP:` + 56 spaces + `$` + total, for example `$     1472.64 `), and line 15 (32 `*` +
  `END OF STATEMENT` + 32 `*`). A card with no transactions gets the full header, no transaction
  lines, and `Total EXP:` with `         .00 `. Source: `app/cbl/CBSTM03A.CBL:138-146`,
  `app/cbl/CBSTM03A.CBL:433-437 (4000-TRNXFILE-GET)`.
- **BR-CBSTM03A-11**: HTML output, one complete HTML document per statement, each line written as a
  100-byte record padded with spaces:
  1. Header (22 lines): `<!DOCTYPE html>`, `<html lang="en">`, `<head>`, `<meta charset="utf-8">`,
     `<title>HTML Table Layout</title>`, `</head>`, `<body style="margin:0px;">`, the `<table ...>`
     line, `<tr>`, the dark-blue `<td colspan="3" ...#1d1d96b3;">` cell,
     `<h3>Statement for Account Number: ` + account ID (20 bytes, with its trailing spaces) + `</h3>`,
     `</td>`, `</tr>`, `<tr>`, the orange `#FFAF33` cell, `<p style="font-size:16px">Bank of XYZ</p>`,
     `<p>410 Terry Ave N</p>`, `<p>Seattle WA 99999</p>`, `</td>`, `</tr>`, `<tr>`, the grey `#f2f2f2` cell.
  2. Name and address (4 lines): `<p style="font-size:16px">` + name up to the first double space + two
     spaces + `</p>`; then `<p>` + address line 1, 2, and the composed line 3, each up to the first
     double space, + two spaces + `</p>` (`<p>1588 Nienow Cape  </p>`).
  3. `</td>`, `</tr>`, `<tr>`, the `#33FFD1` centred cell, `<p style="font-size:16px">Basic Details</p>`,
     `</td>`, `</tr>`, `<tr>`, grey cell; then `<p>Account ID         : ` + 20-byte account ID + `</p>`,
     `<p>Current Balance    : ` + 13-byte balance + `</p>`, `<p>FICO Score         : ` + 20-byte score +
     `</p>` (trailing spaces kept); `</td>`, `</tr>`, `<tr>`, centred cell,
     `<p style="font-size:16px">Transaction Summary</p>`, `</td>`, `</tr>`, then the column header row
     (`<tr>`, 25% cell `Tran ID`, `</td>`, 55% cell `Tran Details`, `</td>`, 20% right-aligned cell
     `Amount`, `</td>`, `</tr>`): 34 lines in all for this part.
  4. Per transaction (11 lines): `<tr>`, 25% grey cell, `<p>` + transaction ID + `</p>`, `</td>`, 55%
     grey cell, `<p>` + 49-byte description (trailing spaces kept) + `</p>`, `</td>`, 20% grey
     right-aligned cell, `<p>` + 13-byte amount + `</p>`, `</td>`, `</tr>`.
  5. Closing (8 lines): `<tr>`, the dark-blue cell, `<h3>End of Statement</h3>`, `</td>`, `</tr>`,
     `</table>`, `</body>`, `</html>`. The statement total is not shown in HTML.
  HTML line count per statement = 64 + 11 x transactions. Source: `app/cbl/CBSTM03A.CBL:148-223`,
  `app/cbl/CBSTM03A.CBL:439-454 (4000-TRNXFILE-GET)`, `app/cbl/CBSTM03A.CBL:506-555 (5100-WRITE-HTML-HEADER)`,
  `app/cbl/CBSTM03A.CBL:558-672 (5200-WRITE-HTML-NMADBS)`, `app/cbl/CBSTM03A.CBL:681-721 (6000-WRITE-TRANS)`.
- **BR-CBSTM03A-12**: Text line count per statement = 19 + transactions (16 header, 1 per
  transaction, 3 trailer). Source: `app/cbl/CBSTM03A.CBL:460-502`, `app/cbl/CBSTM03A.CBL:435-437`.

### Validation rules
None. Input data is trusted; there are no field checks.

### Navigation and keys
Not applicable (batch).

### Error handling
All messages are DISPLAYed to SYSOUT; `RETURN CODE: ` is followed by the raw 2-character file status
returned by CBSTM03B. Each error then performs 9999-ABEND-PROGRAM (ERR-CBSTM03A-08).
- **ERR-CBSTM03A-01**: OPEN status not `00` or `04` -> `ERROR OPENING TRNXFILE` / `ERROR OPENING XREFFILE`
  / `ERROR OPENING CUSTFILE` / `ERROR OPENING ACCTFILE`, `RETURN CODE: ` + status, abend.
  Source: `app/cbl/CBSTM03A.CBL:736-742`, `app/cbl/CBSTM03A.CBL:771-777`, `app/cbl/CBSTM03A.CBL:789-795`,
  `app/cbl/CBSTM03A.CBL:807-813 (8100/8200/8300/8400-xxx-OPEN)`.
- **ERR-CBSTM03A-02**: First TRNXFILE read status not `00` or `04` -> `ERROR READING TRNXFILE`,
  `RETURN CODE: ` + status, abend. Status `10` (empty file) is included (Q-CBSTM03A-03).
  Source: `app/cbl/CBSTM03A.CBL:744-754 (8100-TRNXFILE-OPEN)`.
- **ERR-CBSTM03A-03**: Later TRNXFILE read: `00` continues the load, `10` ends it, any other status ->
  `ERROR READING TRNXFILE`, `RETURN CODE: ` + status, abend. Source: `app/cbl/CBSTM03A.CBL:837-847 (8500-READTRNX-READ)`.
- **ERR-CBSTM03A-04**: XREFFILE read: `00` continues, `10` sets END-OF-FILE = `Y`, any other status ->
  `ERROR READING XREFFILE`, `RETURN CODE: ` + status, abend. Source: `app/cbl/CBSTM03A.CBL:353-362 (1000-XREFFILE-GET-NEXT)`.
- **ERR-CBSTM03A-05**: CUSTFILE keyed read status not `00` (including `23`, customer not found) ->
  `ERROR READING CUSTFILE`, `RETURN CODE: ` + status, abend. Source: `app/cbl/CBSTM03A.CBL:379-386 (2000-CUSTFILE-GET)`.
- **ERR-CBSTM03A-06**: ACCTFILE keyed read status not `00` (including `23`) -> `ERROR READING ACCTFILE`,
  `RETURN CODE: ` + status, abend. Source: `app/cbl/CBSTM03A.CBL:403-410 (3000-ACCTFILE-GET)`.
- **ERR-CBSTM03A-07**: CLOSE status not `00` or `04` -> `ERROR CLOSING TRNXFILE` / `XREFFILE` /
  `CUSTFILE` / `ACCTFILE` (each as `ERROR CLOSING ` + DD name), `RETURN CODE: ` + status, abend.
  Source: `app/cbl/CBSTM03A.CBL:856-919 (9100-TRNXFILE-CLOSE through 9400-ACCTFILE-CLOSE)`.
- **ERR-CBSTM03A-08**: Abend: DISPLAY `ABENDING PROGRAM`, then `CALL 'CEE3ABD'` with no arguments (no
  abend code or timing is passed, unlike the CBACT* programs that pass 999 and 0). If CEE3ABD returned,
  control would fall off the end of the program. Source: `app/cbl/CBSTM03A.CBL:921-923 (9999-ABEND-PROGRAM)`.
- **ERR-CBSTM03A-09**: STMT-FILE and HTML-FILE have no FILE STATUS clause and no declaratives; an open or
  write failure on them is handled by the runtime (abend on z/OS), not by the program.
  Source: `app/cbl/CBSTM03A.CBL:39-40`, `app/cbl/CBSTM03A.CBL:293`.

## Data effects
| Dataset | Access | Effect |
|:--|:--|:--|
| TRNXFILE (TRXFL KSDS) | Read sequentially through CBSTM03B | Read once, entirely, into memory |
| XREFFILE | Read sequentially through CBSTM03B | One statement per record |
| CUSTFILE, ACCTFILE | Keyed read through CBSTM03B | Lookup only |
| STMTFILE | OPEN OUTPUT, write | 80-byte text statements, (19 + transactions) lines per card |
| HTMLFILE | OPEN OUTPUT, write | 100-byte HTML lines, one full HTML document per card, (64 + 11 x transactions) lines per card |

No master file is updated.

## Quirks
- **Q-CBSTM03A-01**: The PSA/TCB/TIOT walk dereferences address 0 and z/OS-specific offsets; it crashes
  on any other platform (the local GnuCOBOL run removes it). It also assumes every TIOT entry is
  20 bytes, while real TIOT entries carry their own length in the first byte, so on z/OS it can print
  wrong names when an entry is longer. The two "null UCB" texts differ in spacing. Recommendation: fix
  (drop the walk or log the configured input and output locations). Source: `app/cbl/CBSTM03A.CBL:266-291`.
- **Q-CBSTM03A-02**: ALTER and GO TO implement what is a straight sequence of opens; 8100-FILE-OPEN as
  written (`GO TO 8100-TRNXFILE-OPEN`) is only the initial target. Recommendation: fix (replace with
  sequential calls; behaviour is identical). Source: `app/cbl/CBSTM03A.CBL:296-314`, `app/cbl/CBSTM03A.CBL:726-728`.
- **Q-CBSTM03A-03**: An empty TRNXFILE aborts the job: the first read returns `10`, which the
  `00`/`04` check treats as an error (`ERROR READING TRNXFILE`, `RETURN CODE: 10`). No statements are
  produced, even though xref cards exist. Recommendation: decide (most likely fix: produce statements
  with no transactions). Source: `app/cbl/CBSTM03A.CBL:748-754`.
- **Q-CBSTM03A-04**: The table holds at most 51 distinct cards and 10 transactions per card, with no
  bounds checks. An 11th transaction for a card writes into the next card's slot area; a 52nd card
  writes past the table into the counter array and beyond. Results are silently wrong statements or a
  storage overlay. The sample data (312 transactions after POSTTRAN and INTCALC, 50 cards, at most 7 per
  card) fits. Recommendation: fix (unbounded per-card lists, or stream the sorted file). Source:
  `app/cbl/CBSTM03A.CBL:225-233`, `app/cbl/CBSTM03A.CBL:818-830`.
- **Q-CBSTM03A-05**: The table scan restarts at entry 1 for every xref record and stops early when a
  table card is greater than the xref card, so it relies on both files being in ascending card order
  (guaranteed by the KSDS keys). It is O(cards x xref records). Recommendation: preserve the ordering
  rule, fix the lookup (map by card). Source: `app/cbl/CBSTM03A.CBL:417-419`.
- **Q-CBSTM03A-06**: Statements are per card, not per account. An account with two cards gets two
  statements, each showing the full account balance but only that card's transactions; transactions on
  cards absent from the xref file are dropped without notice. Recommendation: decide. Source:
  `app/cbl/CBSTM03A.CBL:316-326`, `app/cbl/CBSTM03A.CBL:420`.
- **Q-CBSTM03A-07**: Name and city composition use `DELIMITED BY ' '`, so only the first word of each
  part survives: 24 of the 50 sample customers have a two-word city (`New Aricchester` prints as
  `New`), and a multi-word first or last name would be cut the same way. A blank middle name gives two
  spaces between first and last name; the HTML name, which stops at the first double space, would then
  show only the first name. Recommendation: decide (fix is expected; preserve only if output is
  compared byte for byte). Source: `app/cbl/CBSTM03A.CBL:462-481`, `app/cbl/CBSTM03A.CBL:562-567`.
- **Q-CBSTM03A-08**: Descriptions are cut to 49 of 100 characters in both formats (sample maximum is 48,
  so no visible effect). Recommendation: decide. Source: `app/cbl/CBSTM03A.CBL:135`, `app/cbl/CBSTM03A.CBL:677`.
- **Q-CBSTM03A-09**: Number formats lose digits silently: ACCT-CURR-BAL (10 integer digits) goes into
  `9(9).99-`, so balances of 1,000,000,000.00 or more lose the leading digit; the balance shows leading
  zeros (`000001945.87`) while amounts are zero-suppressed; the COMP-3 total S9(9)V99 has no ON SIZE
  ERROR. Recommendation: preserve the formats, decide on overflow handling. Source:
  `app/cbl/CBSTM03A.CBL:113`, `app/cbl/CBSTM03A.CBL:137`, `app/cbl/CBSTM03A.CBL:142`, `app/cbl/CBSTM03A.CBL:429`.
- **Q-CBSTM03A-10**: HTMLFILE is a concatenation of complete HTML documents (50 `<!DOCTYPE html>` in the
  sample run), so it is not a valid single HTML file. Values are inserted without HTML escaping (an `&`
  or `<` in a name or merchant description would break the markup), and field padding is kept inside
  `<p>` elements. Recommendation: decide (one file per statement and escaping are the likely target;
  preserve the layout if the output is compared). Source: `app/cbl/CBSTM03A.CBL:506-523`, `app/cbl/CBSTM03A.CBL:687-716`.
- **Q-CBSTM03A-11**: The statement has no statement date, period, payment due, or minimum payment, and
  includes the account's live current balance at run time. Because CREASTMT normally runs after
  INTCALC, sample balances reflect the INTCALC last-account defect (account 50 shows `000001945.87`).
  Recommendation: decide. Source: `app/cbl/CBSTM03A.CBL:483-485`.
- **Q-CBSTM03A-12**: The CREASTMT SORT OUTREC copies 50 bytes from position 279, so the last 2 bytes of
  TRAN-PROC-TS and the 20-byte filler are not copied (positions 329-350 of the TRXFL record are padding).
  CBSTM03A never prints the timestamps, so output is unaffected. Recommendation: preserve (note for any
  reuse of TRXFL). Source: `app/jcl/CREASTMT.JCL:53-54`.
- **Q-CBSTM03A-13**: CREASTMT JCL oddities: STEP030 deletes HTMLFILE with LRECL=80 while STEP040 creates
  it with LRECL=100, and the STMTFILE SPACE line carries stray text after the continuation comma
  (`00,RECFM=FB), ATA.VSAM.KSDS`, treated as a comment). Recommendation: fix in the target job
  definition. Source: `app/jcl/CREASTMT.JCL:66-75`, `app/jcl/CREASTMT.JCL:87-96`.
- **Q-CBSTM03A-14**: CEE3ABD is called without its two required arguments, so the abend code on z/OS is
  undefined; off z/OS the call fails with `libcob: error: module 'CEE3ABD' not found` (exit code 1).
  Recommendation: fix (explicit non-zero exit with a defined code). Source: `app/cbl/CBSTM03A.CBL:921-923`.
- **Q-CBSTM03A-15**: Open and close checks accept status `04`, which for these files would indicate a
  record-length or attribute mismatch, and continue. Recommendation: decide. Source:
  `app/cbl/CBSTM03A.CBL:736`, `app/cbl/CBSTM03A.CBL:862`.

## Test scenarios
Sample run (GnuCOBOL with `-fsign=EBCDIC`, TIOT walk removed): TRXFL built from the 262 transactions
posted by POSTTRAN plus the 50 interest transactions written by INTCALC (312 records, 50 cards), the 50
sample xref, customer, and account records. First xref card 0500****5740, customer `000000050`, account
`00000000050`.

- **TS-CBSTM03A-01** (covers BR-CBSTM03A-03, BR-CBSTM03A-04, BR-CBSTM03A-12, BR-CBSTM03A-11): Given the sample
  run inputs, when STEP040 runs, then RC is 0, STMTFILE has 1262 lines (50 x 19 + 312), HTMLFILE has 6632
  lines (50 x 64 + 312 x 11) containing 50 `<!DOCTYPE html>` documents.
- **TS-CBSTM03A-02** (covers BR-CBSTM03A-05, BR-CBSTM03A-06, BR-CBSTM03A-07): Given the same inputs, then the
  first statement reads, line by line: `*` x31 + `START OF STATEMENT` + `*` x31; `Aniya Alba Von`;
  `1588 Nienow Cape`; `Suite 187`; `New OR USA 04257`; dashes; `Basic Details` centred; dashes;
  `Account ID         :00000000050`; `Current Balance    :000001945.87`; `FICO Score         :623`; dashes;
  `TRANSACTION SUMMARY`; dashes; the column header; dashes (all padded to 80).
- **TS-CBSTM03A-03** (covers BR-CBSTM03A-09, BR-CBSTM03A-10): Given the same inputs, then the first statement
  has 7 transaction lines in transaction-ID order, including
  `0000000058866561 Purchase at Blick-Rippin` ... `$      183.88 ` and a credit
  `0000000577826814 Return item at DuBuque, Wuckert and Mraz` ... `$       47.88-`, and the interest line
  `2022071800000050 Int. for a/c 00000000050` ... `$       18.77 `; the trailer is
  `Total EXP:` + 56 spaces + `$     1472.64 ` followed by `*` x32 + `END OF STATEMENT` + `*` x32.
- **TS-CBSTM03A-04** (covers BR-CBSTM03A-11): Given the same inputs, then the first HTML document contains
  `<h3>Statement for Account Number: 00000000050         </h3>`,
  `<p style="font-size:16px">Aniya Alba Von  </p>`, `<p>1588 Nienow Cape  </p>`,
  `<p>Current Balance    : 000001945.87 </p>`, `<p>FICO Score         : 623                 </p>`, and a row
  `<p>      183.88 </p>`; it ends with `<h3>End of Statement</h3>`, `</td>`, `</tr>`, `</table>`, `</body>`, `</html>`.
- **TS-CBSTM03A-05** (covers BR-CBSTM03A-10, Q-CBSTM03A-06): Given an xref card with no transactions in
  TRXFL, when the program runs, then that card's statement has the 16 header lines, no transaction
  lines, and `Total EXP:` with `$         .00 `; its HTML document has 64 lines.
- **TS-CBSTM03A-06** (covers Q-CBSTM03A-03, ERR-CBSTM03A-02, ERR-CBSTM03A-08): Given an empty TRXFL KSDS, when
  the program runs, then SYSOUT shows `ERROR READING TRNXFILE`, `RETURN CODE: 10`, `ABENDING PROGRAM`, and the
  step abends; STMTFILE and HTMLFILE are empty.
- **TS-CBSTM03A-07** (covers ERR-CBSTM03A-05): Given an xref record whose customer ID is not in CUSTFILE, when
  that record is processed, then SYSOUT shows `ERROR READING CUSTFILE`, `RETURN CODE: 23`,
  `ABENDING PROGRAM`; statements for earlier cards are already written.
- **TS-CBSTM03A-08** (covers ERR-CBSTM03A-06): Given an xref record whose account ID is not in ACCTFILE, then
  `ERROR READING ACCTFILE`, `RETURN CODE: 23`, `ABENDING PROGRAM`.
- **TS-CBSTM03A-09** (covers ERR-CBSTM03A-01): Given the XREFFILE DD points to a missing dataset (status `35`),
  then after the transaction load SYSOUT shows `ERROR OPENING XREFFILE`, `RETURN CODE: 35`, `ABENDING PROGRAM`.
- **TS-CBSTM03A-10** (covers Q-CBSTM03A-07): Given a customer whose city is `Lake Virginie`, state `NY`,
  country `USA`, ZIP `12345`, then address line 4 of the statement is `Lake NY USA 12345` (current
  behaviour); a fixed implementation would print the full city.
- **TS-CBSTM03A-11** (covers Q-CBSTM03A-04): Given a card with 11 transactions, then the current program
  corrupts the table (11th transaction overwrites the next card's first slot area); the target
  implementation must print all 11. Characterisation only; do not assert the corrupted output.
- **TS-CBSTM03A-12** (covers BR-CBSTM03A-01): On z/OS, when the step runs, then SYSOUT begins with
  `Running JCL : CREASTMT Step STEP040 ` followed by `DD Names from TIOT: ` and one `: <ddname> -- valid UCB`
  line per allocated DD.

## Open questions
- Should statements be per card (current) or per account (Q-CBSTM03A-06)?
- Should an empty transaction file produce empty statements instead of an abend (Q-CBSTM03A-03)?
- Is the HTML output consumed by anything that relies on the concatenated layout (Q-CBSTM03A-10)?
- What statement period should apply, given that the program prints all transactions on file (BR-CBSTM03A-08)?
- Is the TIOT diagnostic listing used operationally, or only a demonstration (Q-CBSTM03A-01)?
