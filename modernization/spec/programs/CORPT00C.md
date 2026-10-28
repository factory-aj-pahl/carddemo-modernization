# CORPT00C: Request a transaction report by submitting the TRANREPT batch job from CICS

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/CORPT00C.cbl` (649 lines) |
| Module | base |
| Kind | Online (CICS), pseudo-conversational; submits a batch job |
| Transaction ID / Job | CR00 (`app/csd/CARDDEMO.CSD:409-410`); submits job `TRNRPT00`, which runs procedure `TRANREPT` (`app/proc/TRANREPT.prc`) |
| BMS map | mapset `CORPT00` / map `CORPT0A` (`app/bms/CORPT00.bms`, symbolic map `app/cpy-bms/CORPT00.CPY`) |
| Copybooks | COCOM01Y, CORPT00, COTTL01Y, CSDAT01Y, CSMSG01Y, CVTRA05Y (unused), DFHAID, DFHBMSCA |
| Calls / XCTL targets | `CALL 'CSUTLDTC'` (custom dates only, twice); XCTL `COMEN01C` (PF3); XCTL `COSGN00C` (no COMMAREA) |
| Data stores | Transient data queue `JOBS`: WRITEQ TD, 80-byte records. The CSD defines it as extrapartition, output, DDNAME `INREADER` (the JES internal reader) (`app/csd/CARDDEMO.CSD:499-504`). `TRANSACT` is named in working storage but never accessed. |

## Purpose
Lets a user ask for the transaction detail report for the current month, the current year, or a
custom date range. After the user confirms, the program builds a 17-line job stream in working
storage, fills in the start and end dates, and writes it line by line to the `JOBS` queue, which feeds
the JES internal reader. The batch job then unloads TRANSACT, selects the transactions whose
processing date is in the range, and prints the report with CBTRN03C. Nothing is printed online, and
the user gets no job number or completion status.

## Invocation and state
- Entered by XCTL from `COMEN01C`, menu option 9 "Transaction Reports" (user type `U`), with the
  160-byte COMMAREA and context 0. `CDEMO-FROM-PROGRAM` is not used: PF3 always goes to the menu.
- If `EIBCALEN = 0`, the program transfers to `COSGN00C`.
- The COMMAREA is the plain 160-byte COCOM01Y; there is no program-specific extension.
- Between turns the selections and dates survive only because every input field is FSET and is
  returned by each RECEIVE.

## Screen
Custom range entered, after the first ENTER with the confirmation blank:

```text
         1         2         3         4         5         6         7         8
12345678901234567890123456789012345678901234567890123456789012345678901234567890
 Tran: CR00                AWS Mainframe Modernization           Date: 06/15/22
 Prog: CORPT00C                    CardDemo                      Time: 10:30:00

                              Transaction Reports


          _    Monthly (Current Month)

          _    Yearly (Current Year)

          X    Custom (Date Range)

               Start Date :  06 / 01 / 2022   (MM/DD/YYYY)
                 End Date :  06 / 30 / 2022   (MM/DD/YYYY)




      The Report will be submitted for printing. Please confirm:  _  (Y/N)



 Please confirm to print the Custom report...
 ENTER=Continue  F3=Back
```

Positions are the (row, column) of the first data character. Every input field is UNPROT FSET,
green, underline.

| Field | BMS name | Len | In/Out | Attributes | Meaning |
|:--|:--|:--|:--|:--|:--|
| Header | TRNNAME, TITLE01, CURDATE, PGMNAME, TITLE02, CURTIME | 4/40/8/8/40/8 | Out | rows 1-2, as in all CardDemo screens | `CR00`, titles, `MM/DD/YY`, `CORPT00C`, `HH:MM:SS` |
| Monthly | MONTHLY | 1 | In | (7,11) IC | Any non-blank character selects the current-month report |
| Yearly | YEARLY | 1 | In | (9,11) | Any non-blank character selects the current-year report |
| Custom | CUSTOM | 1 | In | (11,11) | Any non-blank character selects the custom range |
| Start month / day / year | SDTMM, SDTDD, SDTYYYY | 2/2/4 | In/Out | (13,30), (13,35), (13,40), NUM | Start date; normalized to leading zeros |
| End month / day / year | EDTMM, EDTDD, EDTYYYY | 2/2/4 | In/Out | (14,30), (14,35), (14,40), NUM | End date; normalized to leading zeros |
| Confirm | CONFIRM | 1 | In | (19,67) | `Y`/`y` submits; `N`/`n` clears the form |
| Message | ERRMSG | 78 | Out | (23,2) ASKIP BRT FSET, red; green for the success message | Messages |

Constant text: "Transaction Reports" (4,31); "Monthly (Current Month)" (7,16); "Yearly (Current Year)"
(9,16); "Custom (Date Range)" (11,16); "Start Date :" (13,16); "  End Date :" (14,16); `/`
separators at columns 33 and 38; "(MM/DD/YYYY)" (13,47) and (14,47);
"The Report will be submitted for printing. Please confirm: " (19,7); "(Y/N)" (19,70);
"ENTER=Continue  F3=Back" (24,2).

## Processing flow
Every send of the screen (SEND-TRNRPT-SCREEN) ends with GO TO RETURN-TO-CICS, so the first message
sent ends the task.
1. **MAIN-PARA**: clear the error flag and the message. IF `EIBCALEN = 0`, XCTL to `COSGN00C`.
2. Copy the COMMAREA. IF the context is not 1 (first entry): set it to 1, clear the map, put the cursor
   on MONTHLY, and send the empty form.
3. OTHERWISE, RECEIVE the map and evaluate the AID: ENTER runs PROCESS-ENTER-KEY; PF3 transfers to
   `COMEN01C`; any other key shows the invalid-key message with the cursor on MONTHLY.
4. **PROCESS-ENTER-KEY** (writes `PROCESS ENTER KEY` to the log with DISPLAY), first match wins:
   1. MONTHLY not blank: report name `Monthly`; start = current year, current month, day `01`; end =
      the day before the first day of the next month (BR-CORPT00C-02). Run
      SUBMIT-JOB-TO-INTRDR.
   2. YEARLY not blank: report name `Yearly`; start = `YYYY-01-01`, end = `YYYY-12-31` of the current
      year. Run SUBMIT-JOB-TO-INTRDR.
   3. CUSTOM not blank:
      1. Each of the six date parts must be non-blank, in the order start month, day, year, end month,
         day, year (VR-CORPT00C-02 to -07).
      2. Normalize each part with NUMVAL-C into `PIC 99` or `PIC 9999` and write it back to the screen.
      3. Range checks: month not greater than `12`, day not greater than `31`, and the NUMERIC test on
         each part (VR-CORPT00C-08 to -13).
      4. Build `YYYY-MM-DD` start and end dates and validate each with CSUTLDTC
         (VR-CORPT00C-14, -15).
      5. Move the dates into the job stream, set the report name to `Custom`, and IF there is no error,
         run SUBMIT-JOB-TO-INTRDR.
   4. None selected: show "Select a report type to print report..." with the cursor on MONTHLY.
   5. IF there is still no error after submission: clear every input field and the message, set the
      message colour to green, and show "<name> report submitted for printing ..." with the cursor on
      MONTHLY.
5. **SUBMIT-JOB-TO-INTRDR**:
   1. IF CONFIRM is blank: show "Please confirm to print the <name> report..." with the cursor on
      CONFIRM.
   2. `Y`/`y` continues. `N`/`n` clears every input field and the message and sends the empty form (no
      message). Any other value shows `"<value>" is not a valid value to confirm...`.
   3. FOR each line of JOB-DATA from 1: copy it to `JCL-RECORD`; if it is `/*EOF` or blank, mark this
      as the last line; WRITEQ TD `JOBS` with the 80-byte record. Stop after the last line or on an
      error. The `/*EOF` line itself is written (17 lines in total).
6. **RETURN-TO-PREV-SCREEN**: IF TO-PROGRAM is blank, use `COSGN00C`; set FROM-TRANID `CR00`,
   FROM-PROGRAM `CORPT00C`, context 0; XCTL with the COMMAREA.
7. Every turn that does not XCTL ends with RETURN TRANSID `CR00` and the COMMAREA.

## Rules
### Business rules
- **BR-CORPT00C-01**: Report type precedence is Monthly, then Yearly, then Custom. Any non-blank
  character selects a type, and when several are marked the first one in that order is used. Date
  fields are ignored for Monthly and Yearly. Source: `app/cbl/CORPT00C.cbl:212-256 (PROCESS-ENTER-KEY)`.
- **BR-CORPT00C-02**: Monthly range: start = the first day of the current month; end = the last day of
  the current month. The end is computed as the day before the first day of the next month
  (DATE-OF-INTEGER of INTEGER-OF-DATE minus 1), and December rolls into January of the next year.
  "Current" is the CICS task date from FUNCTION CURRENT-DATE. Example: on 2022-06-15 the range is
  `2022-06-01` to `2022-06-30`. Source: `app/cbl/CORPT00C.cbl:213-238 (PROCESS-ENTER-KEY)`.
- **BR-CORPT00C-03**: Yearly range: `YYYY-01-01` to `YYYY-12-31` of the current year.
  Source: `app/cbl/CORPT00C.cbl:239-255 (PROCESS-ENTER-KEY)`.
- **BR-CORPT00C-04**: Custom range: start and end are built as `YYYY-MM-DD` from the six fields after
  normalization to leading zeros (for example month `6` becomes `06`). The normalized values are
  written back to the screen. Source: `app/cbl/CORPT00C.cbl:305-327, 381-386, 429-433 (PROCESS-ENTER-KEY)`.
- **BR-CORPT00C-05**: Nothing is submitted until CONFIRM is `Y` or `y`. The confirmation is evaluated
  after the report type and, for Custom, after the dates are validated.
  Source: `app/cbl/CORPT00C.cbl:462-494 (SUBMIT-JOB-TO-INTRDR)`.
- **BR-CORPT00C-06**: Confirm `N` or `n` cancels: every input field and the message are cleared and
  the empty form is shown with no message. Source: `app/cbl/CORPT00C.cbl:480-483 (SUBMIT-JOB-TO-INTRDR)`, `:633-646 (INITIALIZE-ALL-FIELDS)`.
- **BR-CORPT00C-07**: The submitted job stream is fixed text with the start and end dates substituted in
  three places (the two SYMNAMES parameters and the DATEPARM record). With start `S` and end `E`:

  ```text
  //TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,
  // NOTIFY=&SYSUID
  //*
  //JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')
  //*
  //STEP10 EXEC PROC=TRANREPT
  //*
  //STEP05R.SYMNAMES DD *
  TRAN-CARD-NUM,263,16,ZD
  TRAN-PROC-DT,305,10,CH
  PARM-START-DATE,C'S'
  PARM-END-DATE,C'E'
  /*
  //STEP10R.DATEPARM DD *
  S E
  /*
  /*EOF
  ```

  Each line is an 80-byte record padded with spaces; `S` and `E` are 10 characters (`YYYY-MM-DD`).
  Source: `app/cbl/CORPT00C.cbl:81-127 (JOB-DATA)`, `:220-221, 235-236, 247-253, 429-432`.
- **BR-CORPT00C-08**: Each line is written as one record to the TD queue `JOBS`, in order, stopping
  after the `/*EOF` line (or at the first blank line). `/*EOF` is the internal-reader control
  statement that makes JES take the job at once instead of waiting for the queue to be closed.
  Source: `app/cbl/CORPT00C.cbl:496-523 (SUBMIT-JOB-TO-INTRDR, WIRTE-JOBSUB-TDQ)`; `app/csd/CARDDEMO.CSD:499-504`.
- **BR-CORPT00C-09**: After a complete submission, every input field is cleared and the green message
  is "Monthly report submitted for printing ...", "Yearly report submitted for printing ...", or
  "Custom report submitted for printing ...", with the cursor on MONTHLY.
  Source: `app/cbl/CORPT00C.cbl:445-456 (PROCESS-ENTER-KEY)`.
- **BR-CORPT00C-10**: What the job does (procedure `app/proc/TRANREPT.prc`, overrides from
  BR-CORPT00C-07):
  1. STEP01R runs procedure REPROC (IDCAMS REPRO) to unload the TRANSACT KSDS into a new generation
     `AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)`.
  2. STEP05R sorts that copy by `TRAN-CARD-NUM` (position 263, 16 bytes) and keeps only records whose
     `TRAN-PROC-DT` (position 305, 10 bytes, the date part of `TRAN-PROC-TS`) is between start and end
     inclusive, writing `AWS.M2.CARDDEMO.TRANSACT.DALY(+1)`.
  3. STEP10R runs CBTRN03C, which reads the start and end dates from DATEPARM, applies the same
     date filter again, looks up CARDXREF, TRANTYPE, and TRANCATG, and writes the 133-byte report
     `AWS.M2.CARDDEMO.TRANREPT(+1)`.

  Source: `app/proc/TRANREPT.prc:21-78`; `app/cbl/CBTRN03C.cbl:168-174, 220-233`.
- **BR-CORPT00C-11**: The online program does not learn whether the job ran or what it produced.
  Source: `app/cbl/CORPT00C.cbl:462-535`.
- **BR-CORPT00C-12**: Every send fills the header (titles, `CR00`, `CORPT00C`, `MM/DD/YY`, `HH:MM:SS`).
  Source: `app/cbl/CORPT00C.cbl:609-628 (POPULATE-HEADER-INFO)`.

### Validation rules
The first failing rule sends its message and ends the task.
- **VR-CORPT00C-01**: One report type must be selected. Otherwise
  "Select a report type to print report..." (cursor MONTHLY).
  Source: `app/cbl/CORPT00C.cbl:437-442 (PROCESS-ENTER-KEY)`.
- **VR-CORPT00C-02**: For Custom, SDTMM must not be spaces or LOW-VALUES; otherwise "Start Date - Month can NOT be empty..." (cursor SDTMM).
  Source: `app/cbl/CORPT00C.cbl:259-265 (PROCESS-ENTER-KEY)`.
- **VR-CORPT00C-03**: For Custom, SDTDD must not be spaces or LOW-VALUES; otherwise "Start Date - Day can NOT be empty..." (cursor SDTDD).
  Source: `app/cbl/CORPT00C.cbl:266-272 (PROCESS-ENTER-KEY)`.
- **VR-CORPT00C-04**: For Custom, SDTYYYY must not be spaces or LOW-VALUES; otherwise "Start Date - Year can NOT be empty..." (cursor SDTYYYY).
  Source: `app/cbl/CORPT00C.cbl:273-279 (PROCESS-ENTER-KEY)`.
- **VR-CORPT00C-05**: For Custom, EDTMM must not be spaces or LOW-VALUES; otherwise "End Date - Month can NOT be empty..." (cursor EDTMM).
  Source: `app/cbl/CORPT00C.cbl:280-286 (PROCESS-ENTER-KEY)`.
- **VR-CORPT00C-06**: For Custom, EDTDD must not be spaces or LOW-VALUES; otherwise "End Date - Day can NOT be empty..." (cursor EDTDD).
  Source: `app/cbl/CORPT00C.cbl:287-293 (PROCESS-ENTER-KEY)`.
- **VR-CORPT00C-07**: For Custom, EDTYYYY must not be spaces or LOW-VALUES; otherwise "End Date - Year can NOT be empty..." (cursor EDTYYYY).
  Source: `app/cbl/CORPT00C.cbl:294-300 (PROCESS-ENTER-KEY)`.
- **VR-CORPT00C-08**: After normalization (BR-CORPT00C-04), SDTMM must be NUMERIC and not greater than `12`; otherwise "Start Date - Not a valid Month..." (cursor SDTMM).
  Source: `app/cbl/CORPT00C.cbl:329-336 (PROCESS-ENTER-KEY)`.
- **VR-CORPT00C-09**: After normalization (BR-CORPT00C-04), SDTDD must be NUMERIC and not greater than `31`; otherwise "Start Date - Not a valid Day..." (cursor SDTDD).
  Source: `app/cbl/CORPT00C.cbl:338-345 (PROCESS-ENTER-KEY)`.
- **VR-CORPT00C-10**: After normalization (BR-CORPT00C-04), SDTYYYY must be NUMERIC; otherwise "Start Date - Not a valid Year..." (cursor SDTYYYY).
  Source: `app/cbl/CORPT00C.cbl:347-353 (PROCESS-ENTER-KEY)`.
- **VR-CORPT00C-11**: After normalization (BR-CORPT00C-04), EDTMM must be NUMERIC and not greater than `12`; otherwise "End Date - Not a valid Month..." (cursor EDTMM).
  Source: `app/cbl/CORPT00C.cbl:355-362 (PROCESS-ENTER-KEY)`.
- **VR-CORPT00C-12**: After normalization (BR-CORPT00C-04), EDTDD must be NUMERIC and not greater than `31`; otherwise "End Date - Not a valid Day..." (cursor EDTDD).
  Source: `app/cbl/CORPT00C.cbl:364-371 (PROCESS-ENTER-KEY)`.
- **VR-CORPT00C-13**: After normalization (BR-CORPT00C-04), EDTYYYY must be NUMERIC; otherwise "End Date - Not a valid Year..." (cursor EDTYYYY).
  Source: `app/cbl/CORPT00C.cbl:373-379 (PROCESS-ENTER-KEY)`.

The rules are checked in ID order. Month `00` and day `00` pass VR-CORPT00C-08 to -13 and are caught
by VR-CORPT00C-14 and -15. The NUMERIC parts of VR-CORPT00C-08 to -13 cannot fail after normalization
(Q-CORPT00C-01), so VR-CORPT00C-10 and -13 can never be reported.

- **VR-CORPT00C-14**: The start date must be valid according to CSUTLDTC (format `YYYY-MM-DD`):
  severity `0000`, or message number `2513` (outside the supported range, accepted). Otherwise
  "Start Date - Not a valid date..." (cursor SDTMM). Source: `app/cbl/CORPT00C.cbl:388-406 (PROCESS-ENTER-KEY)`.
- **VR-CORPT00C-15**: The end date must pass the same check. Otherwise
  "End Date - Not a valid date..." (cursor EDTMM). Source: `app/cbl/CORPT00C.cbl:408-426 (PROCESS-ENTER-KEY)`.
- **VR-CORPT00C-16**: CONFIRM must not be blank. Otherwise "Please confirm to print the Monthly report...",
  "Please confirm to print the Yearly report...", or "Please confirm to print the Custom report..."
  (cursor CONFIRM). Source: `app/cbl/CORPT00C.cbl:464-474 (SUBMIT-JOB-TO-INTRDR)`.
- **VR-CORPT00C-17**: CONFIRM must be `Y`, `y`, `N`, or `n`. Otherwise `"<value>" is not a valid value
  to confirm...`, for example `"X" is not a valid value to confirm...` (cursor CONFIRM).
  Source: `app/cbl/CORPT00C.cbl:484-493 (SUBMIT-JOB-TO-INTRDR)`.

No rule checks that the start date is on or before the end date (Q-CORPT00C-03).

### Navigation and keys
- **NAV-CORPT00C-01**: IF `EIBCALEN = 0`, XCTL to `COSGN00C`.
  Source: `app/cbl/CORPT00C.cbl:172-174 (MAIN-PARA)`, `:540-551 (RETURN-TO-PREV-SCREEN)`.
- **NAV-CORPT00C-02**: First entry (context 0): set the context to 1 and show the empty form with the
  cursor on MONTHLY. Source: `app/cbl/CORPT00C.cbl:177-181 (MAIN-PARA)`.
- **NAV-CORPT00C-03**: ENTER validates the request and asks for, or acts on, the confirmation.
  Source: `app/cbl/CORPT00C.cbl:185-186 (MAIN-PARA)`.
- **NAV-CORPT00C-04**: PF3 always transfers by XCTL to `COMEN01C`.
  Source: `app/cbl/CORPT00C.cbl:187-189 (MAIN-PARA)`.
- **NAV-CORPT00C-05**: Any other AID (there is no PF4 here) shows "Invalid key pressed. Please see below..."
  with the cursor on MONTHLY; the entered values are sent back. Source: `app/cbl/CORPT00C.cbl:190-194 (MAIN-PARA)`.
- **NAV-CORPT00C-06**: Before every XCTL the program sets FROM-TRANID `CR00`, FROM-PROGRAM `CORPT00C`,
  and context 0. Source: `app/cbl/CORPT00C.cbl:540-551 (RETURN-TO-PREV-SCREEN)`.
- **NAV-CORPT00C-07**: Every turn that does not XCTL ends with RETURN TRANSID `CR00` and the 160-byte
  COMMAREA. Source: `app/cbl/CORPT00C.cbl:199-202 (MAIN-PARA)`, `:585-591 (RETURN-TO-CICS)`.

### Error handling
- **ERR-CORPT00C-01**: WRITEQ TD with any response other than NORMAL shows
  "Unable to Write TDQ (JOBS)..." (cursor MONTHLY), with a DISPLAY of RESP and RESP2. The lines already
  written stay in the queue (Q-CORPT00C-09). Source: `app/cbl/CORPT00C.cbl:525-535 (WIRTE-JOBSUB-TDQ)`.
- **ERR-CORPT00C-02**: The queue is defined with ERROROPTION(IGNORE), so an I/O error on the internal
  reader may be ignored by CICS and not reported to the program (inferred from the CSD definition).
  Source: `app/csd/CARDDEMO.CSD:499-504`.
- **ERR-CORPT00C-03**: The RESP of RECEIVE MAP is captured but never tested.
  Source: `app/cbl/CORPT00C.cbl:596-604 (RECEIVE-TRNRPT-SCREEN)`.
- **ERR-CORPT00C-04**: JCL errors and job failures (for example a missing procedure library or a GDG
  problem) happen after the online task has ended and are visible only in JES output.
  Source: `app/cbl/CORPT00C.cbl:81-127`; `app/proc/TRANREPT.prc`.

## Data effects
- **TDQ JOBS**: 17 records of 80 bytes per confirmed request, which become one JES job `TRNRPT00`.
- **Batch, through the job**: a new generation of `AWS.M2.CARDDEMO.TRANSACT.BKUP`, of
  `AWS.M2.CARDDEMO.TRANSACT.DALY`, and of `AWS.M2.CARDDEMO.TRANREPT` (the report) per request.
  TRANSACT, CARDXREF, TRANTYPE, and TRANCATG are only read.
- No VSAM file is opened by the online program.

## Quirks
- **Q-CORPT00C-01**: NUMVAL-C writes each date part back as digits before the checks, so the "not
  NUMERIC" conditions of VR-CORPT00C-08 to -13 can never be true and the Year messages can never
  appear. For input that is not a number (the NUM attribute normally stops letters at the terminal)
  the NUMVAL-C result is not defined by the language. Recommendation: fix (validate the raw text,
  then convert). Source: `app/cbl/CORPT00C.cbl:305-379`.
- **Q-CORPT00C-02**: A two-digit year such as `22` becomes `0022`, which CEEDAYS reports as out of range
  (2513), and the program accepts it (Q-CSUTLDTC-05). The job then runs for year 22. Recommendation:
  fix (require four digits in a plausible range). Source: `app/cbl/CORPT00C.cbl:313-315, 396-406`.
- **Q-CORPT00C-03**: The start date may be after the end date; the job is submitted and produces a
  report with no transactions. Recommendation: fix. Source: `app/cbl/CORPT00C.cbl:381-436`.
- **Q-CORPT00C-04**: Any character, not only `X` or `S`, selects a report type, and extra selections are
  ignored without a message. Recommendation: decide (radio-button style choice).
  Source: `app/cbl/CORPT00C.cbl:212-256`.
- **Q-CORPT00C-05**: "Monthly" and "Yearly" mean the current calendar month and year, including days
  that have not happened yet; there is no "previous month". Recommendation: decide.
  Source: `app/cbl/CORPT00C.cbl:213-255`.
- **Q-CORPT00C-06**: The report selects transactions by processing date (`TRAN-PROC-TS`), not by
  origination date. Records with a blank processing timestamp (every record of the initial TRANSACT
  load from DALYTRAN) never appear, and transactions added online carry whatever processing date the
  user typed (Q-COTRN02C-03). Recommendation: decide (confirm the business meaning of the range).
  Source: `app/cbl/CORPT00C.cbl:100-102`; `app/cbl/CBTRN03C.cbl:173-174`.
- **Q-CORPT00C-07**: The job stream is hard-coded: job name `TRNRPT00`, `CLASS=A`, `MSGCLASS=0`, the
  `AWS.M2.CARDDEMO` dataset prefix, and a JCLLIB statement labelled `JOBLIB` (it is not a JOBLIB DD).
  `NOTIFY=&SYSUID` resolves to the user ID under which the job is submitted, which is the CICS region
  user rather than the CardDemo user (inferred). Jobs with the same name may be held by JES until the
  previous one ends (inferred). The procedure member `TRANREPT` starts with `//REPROC PROC`, the same
  name as the nested procedure it runs in STEP01R. Recommendation: fix (replace with a parameterized
  job request to the target scheduler, passing start date, end date, and requester).
  Source: `app/cbl/CORPT00C.cbl:81-127`; `app/proc/TRANREPT.prc:1, 21`.
- **Q-CORPT00C-08**: Every request also creates a new TRANSACT backup generation and an extract
  generation, which may push older generations out of the GDG. Recommendation: decide (the target
  report should read the transaction store directly). Source: `app/proc/TRANREPT.prc:21-53`.
- **Q-CORPT00C-09**: The 17 WRITEQ TD calls are not atomic. A failure in the middle leaves a partial
  job in the internal reader, and two terminals submitting at the same time could interleave their
  lines in the shared queue (inferred; nothing serializes the writes). Recommendation: fix (submit the
  request as one unit). Source: `app/cbl/CORPT00C.cbl:496-535`.
- **Q-CORPT00C-10**: The success message says "submitted for printing", but no job ID, status, or
  report location is returned, and the report is a dataset, not a print. Recommendation: decide
  (return a request ID and let the user see status and download the report).
  Source: `app/cbl/CORPT00C.cbl:445-456`.
- **Q-CORPT00C-11**: Confirm `N` silently clears the whole form. Recommendation: decide.
  Source: `app/cbl/CORPT00C.cbl:480-483`.
- **Q-CORPT00C-12**: Because each send is followed by GO TO RETURN-TO-CICS, only the first error is
  shown. Recommendation: preserve the message order; implement as normal control flow.
  Source: `app/cbl/CORPT00C.cbl:556-591`.
- **Q-CORPT00C-13**: Leftovers: the TRANSACT file name and CVTRA05Y are never used; the no-erase send
  branch is never taken; `JOB-LINES OCCURS 1000` redefines a 17-line area, so without the `/*EOF`
  sentinel the loop would read past the job text. The procedure also writes `PROCESS ENTER KEY` to the
  log on every ENTER. Recommendation: drop. Source: `app/cbl/CORPT00C.cbl:40, 126-127, 146, 210, 562-578`.

## Test scenarios
Assumptions: the CICS date is 2022-06-15 unless stated. "The job" means the 17 records written to the
`JOBS` queue, which a test can capture by redefining the queue as an intrapartition or file-backed
queue.
- **TS-CORPT00C-01** (covers BR-CORPT00C-01, -02, VR-CORPT00C-16): Given MONTHLY = `X` and CONFIRM blank,
  when the user presses ENTER, then the message is "Please confirm to print the Monthly report...",
  MONTHLY still shows `X`, and nothing is written to `JOBS`.
- **TS-CORPT00C-02** (covers BR-CORPT00C-07, -08, -09): Given TS-CORPT00C-01, when the user types `Y` and
  presses ENTER, then 17 records are written, line 11 is `PARM-START-DATE,C'2022-06-01'`, line 12 is
  `PARM-END-DATE,C'2022-06-30'`, line 15 is `2022-06-01 2022-06-30`, line 17 is `/*EOF`, every field is
  blank, and the green message is "Monthly report submitted for printing ...".
- **TS-CORPT00C-03** (covers BR-CORPT00C-02): With the date 2022-12-10, Monthly gives `2022-12-01` to
  `2022-12-31`. With the date 2024-02-05, Monthly gives `2024-02-01` to `2024-02-29`.
- **TS-CORPT00C-04** (covers BR-CORPT00C-03): YEARLY = `X`, CONFIRM = `Y` gives `2022-01-01` to
  `2022-12-31` and "Yearly report submitted for printing ...".
- **TS-CORPT00C-05** (covers BR-CORPT00C-04, VR-CORPT00C-16): CUSTOM = `X`, start `06`/`01`/`2022`, end
  `06`/`30`/`2022`, CONFIRM blank gives "Please confirm to print the Custom report..."; with CONFIRM
  `Y` the job carries `2022-06-01` and `2022-06-30` and the message is
  "Custom report submitted for printing ...".
- **TS-CORPT00C-06** (covers VR-CORPT00C-02, -07): CUSTOM = `X` with the start month blank gives
  "Start Date - Month can NOT be empty...". With every part filled except the end year:
  "End Date - Year can NOT be empty...".
- **TS-CORPT00C-07** (covers VR-CORPT00C-08, -12): Start month `13` gives
  "Start Date - Not a valid Month...". A valid start and end day `32` give
  "End Date - Not a valid Day...".
- **TS-CORPT00C-08** (covers VR-CORPT00C-14, -15): Start `02`/`30`/`2022` gives
  "Start Date - Not a valid date..." with the cursor on the start month. A valid start and end
  `06`/`31`/`2022` give "End Date - Not a valid date...". Start month `00` gives
  "Start Date - Not a valid date...".
- **TS-CORPT00C-09** (covers BR-CORPT00C-04): Start month `6`, day `1`, and end day `30` with CONFIRM
  blank give the confirmation prompt, and the screen shows `06` and `01`.
- **TS-CORPT00C-10** (covers Q-CORPT00C-02): Start year `22` with an otherwise valid range is accepted
  and the job carries `0022-06-01`. Expected in the target system: rejected.
- **TS-CORPT00C-11** (covers Q-CORPT00C-03): Start `06`/`30`/`2022`, end `06`/`01`/`2022`, CONFIRM `Y`:
  the job is submitted. Expected in the target system: rejected with a range message.
- **TS-CORPT00C-12** (covers VR-CORPT00C-01): No type selected gives
  "Select a report type to print report...".
- **TS-CORPT00C-13** (covers VR-CORPT00C-17, BR-CORPT00C-06): MONTHLY = `X` and CONFIRM `X` give
  `"X" is not a valid value to confirm...`. CONFIRM `N` gives an empty form with no message and
  nothing written.
- **TS-CORPT00C-14** (covers NAV-CORPT00C-04, -05): PF3 shows the main menu. PF4 shows
  "Invalid key pressed. Please see below..." and keeps the entered values.
- **TS-CORPT00C-15** (covers BR-CORPT00C-10, Q-CORPT00C-06): Given TRANSACT loaded from
  `app/data/ASCII/dailytran.txt` (every processing timestamp blank), when a Yearly 2022 job runs, then
  the report has no transaction detail lines. Given TRANSACT after POSTTRAN has posted the daily file
  on a date in the range, then the report lists the posted transactions grouped by card.

## Open questions
- Should the report range apply to the processing date or the origination date (Q-CORPT00C-06)?
- What should the user see after submitting: a request ID, a status page, a downloadable report
  (Q-CORPT00C-10)?
- Should Monthly and Yearly mean the current period or the last complete period (Q-CORPT00C-05)?
