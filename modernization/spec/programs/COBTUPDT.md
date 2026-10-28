# COBTUPDT: Batch add, update, and delete of Db2 transaction types from a sequential change file

| Attribute | Value |
|:--|:--|
| Source | `app/app-transaction-type-db2/cbl/COBTUPDT.cbl` (237 lines) |
| Module | app-transaction-type-db2 |
| Kind | Batch (Db2, run under the TSO terminal monitor program IKJEFT01 with `DSN RUN`) |
| Transaction ID / Job | JOB-MNTTRDB2, STEP1 `EXEC PGM=IKJEFT01,REGION=0M`, SYSTSIN `DSN SYSTEM(DAZ1)` / `RUN PROGRAM(COBTUPDT) PLAN(CARDDEMO)` (`app/app-transaction-type-db2/jcl/MNTTRDB2.jcl:21-30`) |
| BMS map | none |
| Copybooks | SQL INCLUDE `SQLCA`, `DCLTRTYP` (`app/app-transaction-type-db2/dcl/DCLTRTYP.dcl`; included but its host variables are not used) |
| Calls / XCTL targets | none |
| Data stores | Sequential input file INPFILE (DD `INPFILE`, 53-byte fixed records): Read. Db2 `CARDDEMO.TRANSACTION_TYPE` (ENT-DB2-TRAN-TYPE): Insert, Update (`TR_DESCRIPTION` by `TR_TYPE`), Delete (by `TR_TYPE`). |

## Purpose
COBTUPDT applies a file of maintenance records to the Db2 transaction-type reference table. Each record says
add, update, or delete a type code, or is a comment. The program applies the records one by one, writes a line
to the job log for every record and every result, and sets return code 4 if anything failed. It is the batch
counterpart of the online screens COTRTLIC (CTLI) and COTRTUPC (CTTU). The job and its schedule are described in
JOB-MNTTRDB2 (`modernization/spec/jobs.md`, Q-MNTTRDB2-01).

## Invocation and state
- Run by JOB-MNTTRDB2 STEP1 under IKJEFT01: `DSN SYSTEM(DAZ1)` connects to Db2 subsystem DAZ1 and `RUN
  PROGRAM(COBTUPDT) PLAN(CARDDEMO)` runs the program with plan CARDDEMO (the same plan the CICS programs use, so
  the COBTUPDT package must be bound into it). STEPLIB holds the Db2 exit and load libraries and
  `AWS.M2.CARDDEMO.LOADLIB`. The INPFILE DD points to the placeholder DSN `INPFILE` (Q-MNTTRDB2-01).
- Input record (FD `TR-RECORD`, `RECORDING MODE F`, read `INTO` WS-INPUT-REC, `COBTUPDT.cbl:39-46`, `:71-77`):

| Columns | Field | Picture | Meaning |
|:--|:--|:--|:--|
| 1 | INPUT-REC-TYPE | X(01) | Action: `A` add, `U` update, `D` delete, `*` comment (upper case only) |
| 2-3 | INPUT-REC-NUMBER | X(02) | Transaction type code (the job comment says numeric; not checked) |
| 4-53 | INPUT-REC-DESC | X(50) | Description (used by `A` and `U`) |

- Output: DISPLAY lines only (no SYSOUT DD in the JCL; under IKJEFT01 they normally go to the TSO output,
  SYSTSPRT). Return code: RETURN-CODE is set to 4 by 9999-ABEND on any error and is never reset, so the step ends
  with RC 0 when every record succeeded and RC 4 when at least one failed. The value passed back through DSN
  and IKJEFT01 is described in JOB-MNTTRDB2.
- State: LASTREC (`Y` at end of file), WS-RETURN-MSG X(80) (initial spaces, never cleared, Q-COBTUPDT-02),
  WS-VAR-SQLCODE `----9`, WS-INF-STATUS X(02).
- Transaction scope: there is no COMMIT or ROLLBACK statement. All changes form one unit of work that Db2
  commits when the program ends normally (STOP RUN under DSN RUN) and rolls back if the step abends
  (Q-COBTUPDT-03).

## Processing flow
1. 0001-OPEN-FILES (`COBTUPDT.cbl:82-89`): OPEN INPUT TR-RECORD. IF the file status is `00` DISPLAY "OPEN FILE OK"
   OTHERWISE DISPLAY "OPEN FILE NOT OK" and continue anyway (ERR-COBTUPDT-01). Control falls through into the
   next paragraph.
2. 1001-READ-NEXT-RECORDS (`:91-99`): perform 1002-READ-RECORDS once; then UNTIL LASTREC = `Y`: perform
   1003-TREAT-RECORD, then 1002-READ-RECORDS. Then 2001-CLOSE-STOP (CLOSE TR-RECORD, `:234-236`) and STOP RUN.
3. 1002-READ-RECORDS (`:100-107`): READ NEXT INTO WS-INPUT-REC; AT END move `Y` to LASTREC. IF not end of file,
   DISPLAY "PROCESSING   " followed by the 53-byte record.
4. 1003-TREAT-RECORD (`:109-130`): EVALUATE the action byte: `A` -> DISPLAY "ADDING RECORD", 10031-INSERT-DB;
   `U` -> DISPLAY "UPDATING RECORD", 10032-UPDATE-DB; `D` -> DISPLAY "DELETING RECORD", 10033-DELETE-DB; `*` ->
   DISPLAY "IGNORING COMMENTED LINE"; any other value -> message "ERROR: TYPE NOT VALID", 9999-ABEND.
5. 10031 / 10032 / 10033 run one SQL statement each and evaluate SQLCODE (BR-COBTUPDT-02..04, ERR-COBTUPDT-02..05).
6. 9999-ABEND (`:230-233`): DISPLAY WS-RETURN-MSG; RETURN-CODE = 4; return to the caller paragraph (processing
   continues with the next record; despite its name nothing abends).

## Rules
### Business rules
- **BR-COBTUPDT-01**: Records are processed strictly in file order, one SQL statement per non-comment record, with
  no look-ahead, sorting, or de-duplication; a later record sees the effect of an earlier one in the same run
  (same unit of work). Source: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:91-99 (1001-READ-NEXT-RECORDS)`.
- **BR-COBTUPDT-02**: Action `A` inserts a new row:
  ```sql
  INSERT INTO CARDDEMO.TRANSACTION_TYPE
  (
  TR_TYPE,
  TR_DESCRIPTION
  )
  VALUES
  (
  :INPUT-REC-NUMBER,
  :INPUT-REC-DESC
  )
  ```
  `TR_TYPE` = columns 2-3 as is; `TR_DESCRIPTION` = columns 4-53 as a fixed 50-character value (trailing spaces
  kept, BR-COBTUPDT-05). SQLCODE 0 -> DISPLAY "RECORD INSERTED SUCCESSFULLY". Source:
  `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:132-164 (10031-INSERT-DB)`.
- **BR-COBTUPDT-03**: Action `U` updates the description of an existing type:
  ```sql
  UPDATE CARDDEMO.TRANSACTION_TYPE
     SET TR_DESCRIPTION = :INPUT-REC-DESC
   WHERE TR_TYPE = :INPUT-REC-NUMBER
  ```
  SQLCODE 0 -> DISPLAY "RECORD UPDATED SUCCESSFULLY". An update of a missing type is an error
  (ERR-COBTUPDT-03), not an insert (unlike COTRTUPC, BR-COTRTUPC-17). Source:
  `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:166-195 (10032-UPDATE-DB)`.
- **BR-COBTUPDT-04**: Action `D` deletes a type:
  ```sql
  DELETE FROM CARDDEMO.TRANSACTION_TYPE
   WHERE TR_TYPE = :INPUT-REC-NUMBER
  ```
  SQLCODE 0 -> DISPLAY "RECORD DELETED SUCCESSFULLY". Category rows are not deleted first, so the FK `ON DELETE
  RESTRICT` of ENT-DB2-TRAN-TYPE-CATEGORY rejects the delete of any type that still has categories
  (ERR-COBTUPDT-05). Source: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:196-226 (10033-DELETE-DB)`.
- **BR-COBTUPDT-05**: Host variables are plain X(02) and X(50) fields (not the DCLGEN VARCHAR structure), so
  `TR_DESCRIPTION` is always stored with length 50 including trailing spaces, and a blank description is stored
  as 50 spaces (accepted by `NOT NULL`). Source: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:71-77`, `:145-146`, `:173-174`.
- **BR-COBTUPDT-06**: Action `*` is a comment: DISPLAY "IGNORING COMMENTED LINE", no SQL, no return-code change.
  Source: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:120-121 (1003-TREAT-RECORD)`.
- **BR-COBTUPDT-07**: Return code: 0 when no record failed, 4 when one or more failed (sticky). Source:
  `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:230-233 (9999-ABEND)`.

### Validation rules
- **VR-COBTUPDT-01**: The action byte must be exactly `A`, `U`, `D`, or `*` (upper case); otherwise DISPLAY "ERROR:
  TYPE NOT VALID" (possibly followed by leftover text, Q-COBTUPDT-02), RC 4, record skipped. Blank lines and
  lower-case actions are errors. Source: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:122-128 (1003-TREAT-RECORD)`.
- No other field is validated: the type code is not checked for being numeric, two digits, or non-zero, and the
  description is not checked for content (Q-COBTUPDT-05). Db2 enforces only the primary key and NOT NULL.

### Navigation and keys
- Not applicable (batch).

### Error handling
- **ERR-COBTUPDT-01**: OPEN status other than `00` (for example `35` DD or dataset missing, `39` record length not
  53) -> DISPLAY "OPEN FILE NOT OK" and processing continues; each READ then fails with status `47` (not opened),
  which is not an end-of-file condition, so LASTREC never becomes `Y` and the loop repeats without end
  (Q-COBTUPDT-01). Source: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:82-89`, `:93-96`, `:100-103`.
- **ERR-COBTUPDT-02**: INSERT SQLCODE < 0 (for example -803 duplicate key) -> message "Error accessing:
  TRANSACTION_TYPE table. SQLCODE:" + SQLCODE in `----9` form (for example ` -803`), 9999-ABEND (DISPLAY, RC 4),
  next record. Source: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:154-162 (10031-INSERT-DB)`.
- **ERR-COBTUPDT-03**: UPDATE SQLCODE +100 -> message "No records found." (over any longer previous message,
  Q-COBTUPDT-02), RC 4. UPDATE SQLCODE < 0 -> "Error accessing: TRANSACTION_TYPE table. SQLCODE:" + code, RC 4.
  Source: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:180-193 (10032-UPDATE-DB)`.
- **ERR-COBTUPDT-04**: DELETE SQLCODE +100 -> "No records found.", RC 4. Source:
  `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:210-214 (10033-DELETE-DB)`.
- **ERR-COBTUPDT-05**: DELETE SQLCODE < 0 (for example -532 when categories exist) -> "Error accessing:
  TRANSACTION_TYPE table. SQLCODE:" + code, RC 4. Source: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:216-224 (10033-DELETE-DB)`.
- **ERR-COBTUPDT-06**: A positive SQLCODE other than +100 (a warning) matches no WHEN in any paragraph: no
  message, no success line, RC unchanged; the change stands. INSERT never returns +100. Source:
  `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:151-163`, `:177-194`, `:207-225`.
- **ERR-COBTUPDT-07**: SQLCODE -911 (deadlock or timeout with rollback) is reported like any other negative code,
  but Db2 has already rolled back the whole unit of work, that is, every change applied earlier in the run; the
  program goes on and the later records are committed at the end (Q-COBTUPDT-04). Source:
  `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:154-162 (10031-INSERT-DB)`,
  `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:185-193 (10032-UPDATE-DB)`,
  `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:216-224 (10033-DELETE-DB)`,
  `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:230-233 (9999-ABEND)`; no COMMIT or ROLLBACK statement
  exists in the program.
- No SQL error stops the run; there is no Db2 connection check (a failing `DSN` connection stops the step before
  the program runs).

## Data effects
- For each `A` / `U` / `D` record: at most one row of `CARDDEMO.TRANSACTION_TYPE` inserted, changed
  (`TR_DESCRIPTION` only), or deleted, in file order. `TRANSACTION_TYPE_CATEGORY` is never touched.
- One unit of work for the whole file, committed at normal program end (no intermediate COMMIT). With the
  tablespace defined `LOCKSIZE TABLE` (JOB-CREADB21, `ctl/DB2CREAT.ctl`), the first change takes a table lock that
  is held until the end of the run, blocking CTLI/CTTU readers and writers (Q-COBTUPDT-03).
- The VSAM file TRANTYPE (ENT-TRAN-TYPE) used by CBTRN03C and the base programs is not updated; the change reaches it
  only through JOB-TRANEXTR and a VSAM reload (Q-TRANEXTR-01).
- The job is not idempotent: rerunning the same file makes every `A` fail with -803 and every `D` fail with +100
  (Q-MNTTRDB2-01).
- Modern equivalent: a small batch task or admin API that applies a change file in one transaction with
  validation of every record first, an explicit result report (counts of added, updated, deleted, rejected), and
  upsert or "skip if identical" semantics for reruns. The online screens usually make it unnecessary.

## Quirks
- **Q-COBTUPDT-01**: If the OPEN fails, the program only displays "OPEN FILE NOT OK" and keeps going; READ then
  fails with status 47 forever (AT END never triggers), and each iteration displays "PROCESSING   " plus a blank
  record and "ERROR: TYPE NOT VALID". The job card has `TIME=1440` (no CPU limit), so the step loops until the
  output limit or an operator cancel. The same happens for any non-end-of-file READ error. Recommendation: fix
  (stop with a non-zero RC when OPEN or READ fails). Source: `COBTUPDT.cbl:82-89`, `:100-103`, `jcl/MNTTRDB2.jcl:2`.
- **Q-COBTUPDT-02**: WS-RETURN-MSG (X(80)) is never cleared, and STRING writes only as many characters as it
  produces. A shorter message after a longer one keeps the old tail, for example "No records found." after a
  -803 error displays "No records found.TRANSACTION_TYPE table. SQLCODE: -803". Recommendation: fix (clear the
  field before each STRING). Source: `COBTUPDT.cbl:61-62`, `:123-127`, `:155-161`, `:181-183`, `:211-213`.
- **Q-COBTUPDT-03**: No intermediate commits. All changes are one unit of work committed only at program end,
  and with `LOCKSIZE TABLE` the whole TRANSACTION_TYPE table stays locked for the entire run, so CTLI (COTRTLIC)
  and CTTU (COTRTUPC) wait and get -911 ("Could not lock record for update", ERR-COTRTUPC-03), and a step abend
  loses every change. Recommendation: decide (one transaction per file is acceptable for a small reference table;
  if kept, document the lock window; otherwise commit per record or per batch with restart logic).
- **Q-COBTUPDT-04**: Because errors never stop the run, a -911 in the middle silently rolls back all earlier
  changes while the later records are applied and committed; the job log still shows "RECORD ... SUCCESSFULLY"
  for the rolled-back records. Recommendation: fix (stop on -911/-913 or on any negative SQLCODE and roll back).
- **Q-COBTUPDT-05**: No input validation: the type code is stored as given (` 5`, `5 `, `AB`, `00` are all
  accepted) and descriptions may contain any characters, including lower case and punctuation that COTRTUPC would
  reject (VR-COTRTUPC-06). Rows written here may then be unchangeable through COTRTUPC without retyping.
  Recommendation: fix (apply the same rules as the online edit: 01-99, two digits, letters/digits/spaces or a
  decided character set).
- **Q-COBTUPDT-06**: Descriptions are stored padded to 50 characters (BR-COBTUPDT-05), the same as COTRTLIC and
  COTRTUPC (Q-COTRTUPC-12). Recommendation: decide (trim in the target).
- **Q-COBTUPDT-07**: Warnings (positive SQLCODE other than +100) are silent, and the error messages do not contain
  the type code; only the preceding "PROCESSING" line identifies the record. There is no end-of-run summary or
  count. Recommendation: fix (structured result report).
- **Q-COBTUPDT-08**: The FD requires 53-byte records (`RECORDING MODE F`, 1 + 2 + 50). A typical 80-byte card-image
  input dataset gives an OPEN length conflict (status 39) and then Q-COBTUPDT-01. Recommendation: decide (document
  LRECL=53 or define an 80-byte record with filler).
- **Q-COBTUPDT-09**: Dead or odd code: the DCLTRTYP host variables are included but unused; WS-INF-STAT1/2 are
  never tested; `EXIT.` statements inside ordinary paragraphs act as no-ops; the paragraph after STOP RUN logic is
  reached only by PERFORM; the VALUE clauses on the FD record have no effect; the procedure division relies on
  falling through from 0001-OPEN-FILES into 1001-READ-NEXT-RECORDS. 9999-ABEND does not abend. Recommendation:
  drop in the target. Source: `COBTUPDT.cbl:54`, `:67-69`, `:82-99`, `:230-233`.
- **Q-COBTUPDT-10**: The program shares plan CARDDEMO with the CICS transactions, so a rebind of either side
  affects the other. Recommendation: decide (separate packages/collections in the target environment).

## Test scenarios
Unless stated otherwise: Db2 loaded by JOB-CREADB21 with the 7 seed types (`01`-`07`) and 18 seed categories
(each of `01`-`07` has categories); INPFILE is a 53-byte fixed-length file.
- **TS-COBTUPDT-01** (covers BR-COBTUPDT-02, -05, -07): Given input `A08INTEREST`, when the job runs, then the log shows
  "OPEN FILE OK", "PROCESSING   A08INTEREST...", "ADDING RECORD", "RECORD INSERTED SUCCESSFULLY", row (`08`,
  `INTEREST` + 42 spaces) exists, RC 0.
- **TS-COBTUPDT-02** (covers BR-COBTUPDT-03): Given TS-01 applied, when input `U08INTEREST CHARGE` runs, then
  "UPDATING RECORD", "RECORD UPDATED SUCCESSFULLY", and the description of `08` changes.
- **TS-COBTUPDT-03** (covers BR-COBTUPDT-04): Given type `08` without categories, when input `D08` runs, then
  "DELETING RECORD", "RECORD DELETED SUCCESSFULLY", row removed.
- **TS-COBTUPDT-04** (covers ERR-COBTUPDT-02): Given the seed, when input `A01PURCHASE` runs, then "Error accessing:
  TRANSACTION_TYPE table. SQLCODE: -803" and RC 4; other records in the file are still applied.
- **TS-COBTUPDT-05** (covers ERR-COBTUPDT-03, Q-COBTUPDT-02): Given input `A01PURCHASE` followed by `U99X`, when the job
  runs, then the second message is "No records found.TRANSACTION_TYPE table. SQLCODE: -803" and RC 4.
- **TS-COBTUPDT-06** (covers ERR-COBTUPDT-04): Given the seed, when input `D99` runs, then "No records found." and RC 4.
- **TS-COBTUPDT-07** (covers ERR-COBTUPDT-05, BR-COBTUPDT-04): Given the seed, when input `D01` runs, then the delete
  is rejected by the FK ("Error accessing: TRANSACTION_TYPE table. SQLCODE: -532") and RC 4.
- **TS-COBTUPDT-08** (covers BR-COBTUPDT-06): Given input `*THIS IS A COMMENT`, when the job runs, then "IGNORING
  COMMENTED LINE" and RC 0.
- **TS-COBTUPDT-09** (covers VR-COBTUPDT-01): Given input lines `a09TEST` and a blank line, when the job runs, then each
  shows "ERROR: TYPE NOT VALID" and RC 4.
- **TS-COBTUPDT-10** (covers Q-COBTUPDT-05): Given input `AABPUNCT-UATION.`, when the job runs, then type `AB` with
  that description is inserted successfully.
- **TS-COBTUPDT-11** (covers BR-COBTUPDT-01): Given input `A09TEMP`, `U09TEMPORARY`, `D09`, when the job runs, then
  three success lines appear and type `09` does not exist afterwards.
- **TS-COBTUPDT-12** (covers ERR-COBTUPDT-01, Q-COBTUPDT-01): Given the INPFILE DD points to a missing or 80-byte
  dataset, when the job runs, then "OPEN FILE NOT OK" is displayed and the step loops displaying "ERROR: TYPE NOT
  VALID" until cancelled.
- **TS-COBTUPDT-13** (covers Q-COBTUPDT-03): Given a file of many records and a CTTU user saving a change while the job
  runs, when the save is made, then CTTU waits on the table lock and reports "Could not lock record for update"
  if the lock timeout expires; the batch changes all appear at job end.
- **TS-COBTUPDT-14** (covers BR-COBTUPDT-07): Given an empty INPFILE, when the job runs, then only "OPEN FILE OK" is
  displayed and RC 0.

## Open questions
- How is the program RC surfaced through `DSN RUN` and IKJEFT01 (the SYSTSIN has no `END`), and do the schedulers
  test it?
- Should a batch file be all-or-nothing (today's single unit of work, but without stopping on errors) or applied
  record by record?
- What are the intended validation rules for batch input (same as COTRTUPC)?
- What LRECL does the real INPFILE have (the program requires 53)?
- Where do DISPLAY lines go on the target system (no SYSOUT DD in the JCL)?
