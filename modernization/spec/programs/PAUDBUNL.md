# PAUDBUNL: Unload the pending-authorization IMS database to two flat files

| Attribute | Value |
|:--|:--|
| Source | `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL` (317 lines) |
| Module | app-authorization-ims-db2-mq |
| Kind | Batch (IMS DL/I batch region, `CALL 'CBLTDLI'`) |
| Transaction ID / Job | JOB-UNLDPADB (`app/app-authorization-ims-db2-mq/jcl/UNLDPADB.JCL`), STEP01 `EXEC PGM=DFSRRC00,PARM='DLI,PAUDBUNL,PAUTBUNL,,,,,,,,,,,N'` |
| BMS map | none |
| Copybooks | IMSFUNCS (DL/I function codes), CIPAUSMY (PAUTSUM0 layout), CIPAUDTY (PAUTDTL1 layout), PAUTBPCB (DB PCB mask) |
| Calls / XCTL targets | CBLTDLI: GN (root), GNP (child) |
| Data stores | IMS DBPAUTP0 (HIDAM) via PSB PAUTBUNL, PCB PAUTBPCB, PROCOPT=GOTP (read only): PAUTSUM0 read sequentially, PAUTDTL1 read under each root. OPFILE1 (DD OUTFIL1) Write, 100-byte FB. OPFILE2 (DD OUTFIL2) Write, 206-byte FB. |

## Purpose
PAUDBUNL copies the whole pending-authorization database to two sequential files: one record per
account summary (root) and one record per authorization detail (child), each detail prefixed with its
parent's account id so the hierarchy can be rebuilt. The files are the input of PAUDBLOD (job
LOADPADB), so the pair is used to move or rebuild the IMS database. Nothing in the database is
changed.

## Invocation and state
- JCL UNLDPADB (`app/app-authorization-ims-db2-mq/jcl/UNLDPADB.JCL`):
  - STEP0 (25-36) IEFBR14 deletes `AWS.M2.CARDDEMO.PAUTDB.ROOT.FILEO` and
    `AWS.M2.CARDDEMO.PAUTDB.CHILD.FILEO` with `DISP=(OLD,DELETE,DELETE)`.
  - STEP01 (38-68) runs DFSRRC00 as a DL/I batch region (`DLI`), program PAUDBUNL, PSB PAUTBUNL.
    Positional parameter 14 is `N`, which in the standard DLI parameter order is DBRC=N (DBRC not
    used). IMSLOGR and IEFRDER are DUMMY (no IMS log). Database DDs DDPAUTP0 (`OEM.IMS.IMSP.PAUTHDB`)
    and DDPAUTX0 (`OEM.IMS.IMSP.PAUTHDBX`) are allocated SHR.
  - OUTFIL1 -> `AWS.M2.CARDDEMO.PAUTDB.ROOT.FILEO`, `DISP=(NEW,CATLG,DELETE)`, LRECL=100, RECFM=FB.
  - OUTFIL2 -> `AWS.M2.CARDDEMO.PAUTDB.CHILD.FILEO`, `DISP=(NEW,CATLG,DELETE)`, LRECL=206, RECFM=FB.
- PSB PAUTBUNL (`app/app-authorization-ims-db2-mq/ims/PAUTBUNL.PSB:18-21`): one DB PCB on DBPAUTP0,
  PROCOPT=GOTP (get only, read without locking, P = path calls allowed), KEYLEN=14, sensitive
  segments PAUTSUM0 (root) and PAUTDTL1 (child of PAUTSUM0); `CMPAT=NO`, so in a DLI region no I/O
  PCB is passed and the DB PCB is the only parameter.
- Linkage: `PROCEDURE DIVISION USING PAUTBPCB` (153) and `ENTRY 'DLITCBL' USING PAUTBPCB` (158) bind
  the same single PCB.
- Parameters: none (`ACCEPT PRM-INFO FROM SYSIN` is commented out at line 179).
- Return code: 0 on normal end (never set explicitly); 16 from 9999-ABEND, which ends with GOBACK
  (normal termination, not an abend).

### Output record layouts
| File | Bytes | Content |
|:--|:--|:--|
| OUTFIL1 (OPFIL1-REC, line 44) | 1-100 | The PAUTSUM0 segment exactly as stored (CIPAUSMY: PA-ACCT-ID S9(11) COMP-3 in bytes 1-6, PA-CUST-ID, statuses, limits, balances, counts, amounts, filler) |
| OUTFIL2 (OPFIL2-REC, lines 46-48) | 1-6 | ROOT-SEG-KEY S9(11) COMP-3 = parent PA-ACCT-ID |
| OUTFIL2 | 7-206 | CHILD-SEG-REC: the PAUTDTL1 segment exactly as stored (CIPAUDTY, key PA-AUTHORIZATION-KEY in bytes 1-8 of the segment) |

## Processing flow
1. MAIN-PARA (157-170): 1000-INITIALIZE; PERFORM 2000-FIND-NEXT-AUTH-SUMMARY until
   WS-END-OF-ROOT-SEG = 'Y'; 4000-FILE-CLOSE; GOBACK.
2. 1000-INITIALIZE (173-204): accept today's date (YYMMDD) and day (YYDDD). DISPLAY
   'STARTING PROGRAM PAUDBUNL::', '*-------------------------------------*',
   'TODAYS DATE            :' + YYMMDD, ' '. OPEN OUTPUT OPFILE1; IF status is not spaces or '00',
   DISPLAY 'ERROR IN OPENING OPFILE1:' + status and 9999-ABEND. OPEN OUTPUT OPFILE2; same check with
   'ERROR IN OPENING OPFILE2:'.
3. 2000-FIND-NEXT-AUTH-SUMMARY (207-249): clear the PCB status; DL/I "get the next root segment
   (PAUTSUM0) in database order, skipping any children not yet read" (GN with the unqualified SSA
   `PAUTSUM0 `).
   1. IF status is blank: add 1 to WS-NO-SUMRY-READ and WS-AUTH-SMRY-PROC-CNT; copy the segment to
      OPFIL1-REC; clear ROOT-SEG-KEY and CHILD-SEG-REC; set ROOT-SEG-KEY = PA-ACCT-ID.
      IF PA-ACCT-ID is valid packed numeric: WRITE OPFIL1-REC; clear WS-END-OF-CHILD-SEG; PERFORM
      3000-FIND-NEXT-AUTH-DTL until WS-END-OF-CHILD-SEG = 'Y'. OTHERWISE nothing is written and the
      root's children are not read.
   2. IF status is 'GB' (end of database): set END-OF-AUTHDB and WS-END-OF-ROOT-SEG = 'Y'.
   3. IF status is neither blank nor 'GB': DISPLAY 'AUTH SUM  GN FAILED  :' + status and
      'KEY FEEDBACK AREA    :' + PAUT-KEYFB; 9999-ABEND.
4. 3000-FIND-NEXT-AUTH-DTL (253-286): DL/I "get the next detail (PAUTDTL1) under the current root"
   (GNP with the unqualified SSA `PAUTDTL1 `).
   1. IF status is blank: set MORE-AUTHS; add 1 to WS-NO-SUMRY-READ and WS-AUTH-SMRY-PROC-CNT (see
      Q-PAUDBUNL-01); copy the detail to CHILD-SEG-REC; WRITE OPFIL2-REC (ROOT-SEG-KEY + detail).
   2. IF status is 'GE' (no more children under this root): set WS-END-OF-CHILD-SEG = 'Y'; DISPLAY
      'CHILD SEG FLAG GE : ' + 'Y'.
   3. IF status is neither blank nor 'GE': DISPLAY 'GNP CALL FAILED  :' + status and
      'KFB AREA IN CHILD:' + PAUT-KEYFB; 9999-ABEND.
   4. Clear PAUT-PCB-STATUS (284), so that when control returns to 2000 after the last child the
      status tests there see blank and do not report an error.
5. 4000-FILE-CLOSE (289-306): DISPLAY 'CLOSING THE FILE'. CLOSE OPFILE1; IF status is not spaces or
   '00', DISPLAY 'ERROR IN CLOSING 1ST FILE:' + status. CLOSE OPFILE2; same with
   'ERROR IN CLOSING 2ND FILE:'. No abend and no return code change.
6. 9999-ABEND (308-317): DISPLAY 'IMSUNLOD ABENDING ...'; RETURN-CODE = 16; GOBACK.

## Rules
### Business rules
- **BR-PAUDBUNL-01**: Every root segment of DBPAUTP0 is visited once, in root key order (ascending
  packed account id, through the HIDAM primary index). Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL:163-164 (MAIN-PARA)`,
  `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL:212-216 (2000-FIND-NEXT-AUTH-SUMMARY)`.
- **BR-PAUDBUNL-02**: When a root is read and its PA-ACCT-ID is valid packed decimal, its 100 bytes are
  written unchanged to OUTFIL1. Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL:223-233 (2000-FIND-NEXT-AUTH-SUMMARY)`.
- **BR-PAUDBUNL-03**: For each root written, every child detail under it is read in child key order
  (PAUT9CTS ascending, which is newest authorization first) and written to OUTFIL2 as the 6-byte
  packed parent account id followed by the 200-byte detail unchanged. Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL:228-236 (2000-FIND-NEXT-AUTH-SUMMARY)`,
  `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL:257-271 (3000-FIND-NEXT-AUTH-DTL)`.
- **BR-PAUDBUNL-04**: When a root's PA-ACCT-ID is not valid packed decimal, neither the root nor any of
  its children is written, and no message is produced. Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL:232-237 (2000-FIND-NEXT-AUTH-SUMMARY)`.
- **BR-PAUDBUNL-05**: A root with no children produces one OUTFIL1 record and no OUTFIL2 records. The
  line 'CHILD SEG FLAG GE : Y' is displayed once for every root written, with or without children.
  Source: `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL:273-278 (3000-FIND-NEXT-AUTH-DTL)`.
- **BR-PAUDBUNL-06**: Output order: OUTFIL1 holds roots in key order; OUTFIL2 holds child records grouped
  by parent in the same root order. Source: as BR-01 and BR-03.
- **BR-PAUDBUNL-07**: The run ends when the root GN returns 'GB'; both files are closed and the step
  ends with RC 0. No totals are displayed. Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL:239-242 (2000-FIND-NEXT-AUTH-SUMMARY)`,
  `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL:166-170 (MAIN-PARA)`.
- **BR-PAUDBUNL-08**: The program takes no parameters; at start it displays 'STARTING PROGRAM
  PAUDBUNL::', a dashed line, 'TODAYS DATE            :' + today as YYMMDD, and a blank line.
  Source: `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL:176-183 (1000-INITIALIZE)`.

### Validation rules
- None produce a message. The only data check is the silent packed-numeric test of PA-ACCT-ID in
  BR-PAUDBUNL-04.

### Navigation and keys
- Not applicable (batch).

### Error handling
- **ERR-PAUDBUNL-01**: OPEN OUTPUT OPFILE1 status other than spaces or '00' -> 'ERROR IN OPENING
  OPFILE1:' + status, then 9999-ABEND (RC 16). Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL:186-192 (1000-INITIALIZE)`.
- **ERR-PAUDBUNL-02**: OPEN OUTPUT OPFILE2 status other than spaces or '00' -> 'ERROR IN OPENING
  OPFILE2:' + status, then 9999-ABEND (RC 16). Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL:194-200 (1000-INITIALIZE)`.
- **ERR-PAUDBUNL-03**: Root GN status other than blank and 'GB' -> 'AUTH SUM  GN FAILED  :' + status,
  'KEY FEEDBACK AREA    :' + the 255-byte key feedback area, then 9999-ABEND (RC 16). Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL:243-247 (2000-FIND-NEXT-AUTH-SUMMARY)`.
- **ERR-PAUDBUNL-04**: Child GNP status other than blank and 'GE' -> 'GNP CALL FAILED  :' + status,
  'KFB AREA IN CHILD:' + key feedback area, then 9999-ABEND (RC 16). Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL:279-283 (3000-FIND-NEXT-AUTH-DTL)`.
- **ERR-PAUDBUNL-05**: The file status after each WRITE is not checked. Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL:233, 271`.
- **ERR-PAUDBUNL-06**: CLOSE status other than spaces or '00' -> 'ERROR IN CLOSING 1ST FILE:' +
  status or 'ERROR IN CLOSING 2ND FILE:' + status; processing continues and RC is unchanged. Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL:289-304 (4000-FILE-CLOSE)`.
- **ERR-PAUDBUNL-07**: 9999-ABEND displays 'IMSUNLOD ABENDING ...', sets RC 16 and returns with GOBACK
  without closing the files. Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL:308-314 (9999-ABEND)`.

## Data effects
- IMS DBPAUTP0: read only (PROCOPT=GOTP); nothing is changed, so there is no commit or rollback
  concern.
- OUTFIL1: one 100-byte record per root with a valid packed key.
- OUTFIL2: one 206-byte record per child of those roots.
- JCL: STEP0 deletes the previous output datasets; STEP01 creates and catalogs new ones. Because
  9999-ABEND ends the step normally, the `NEW,CATLG,DELETE` dispositions catalog the output even on
  RC 16, leaving partially written files.

## Quirks
- **Q-PAUDBUNL-01**: Each child read adds 1 to WS-NO-SUMRY-READ and WS-AUTH-SMRY-PROC-CNT (the summary
  counters), and none of the counters is ever displayed, so the run gives no record counts to
  reconcile with the load. Recommendation: fix (count roots and children separately and display
  them). Source: `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL:225-226, 268-269`.
- **Q-PAUDBUNL-02**: Roots whose key is not valid packed decimal are dropped silently together with
  their children. The sample database contains one such root (all bytes X'40'), so the unload has
  one root fewer than the database. Recommendation: decide (the record is garbage; a warning line
  would make the drop visible). Source: `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL:232-237`.
- **Q-PAUDBUNL-03**: The error IF at line 243 has no END-IF and is closed by the period at line 247.
  It behaves correctly as written. Recommendation: preserve.
- **Q-PAUDBUNL-04**: WRITE status is never checked; a write error that does not abend the step is
  ignored. Recommendation: fix.
- **Q-PAUDBUNL-05**: The abend message names 'IMSUNLOD' instead of PAUDBUNL (WS-PGMNAME is also
  'IMSUNLOD' and unused). Working storage carries unused items copied from CBPAUP0C (PRM-INFO, expiry
  days, checkpoint id 'RMAD', end-of-file flags). Recommendation: preserve (cosmetic).
  Source: `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL:54-90, 119-129, 311`.
- **Q-PAUDBUNL-06**: RC 16 is returned with GOBACK, so the partially written output datasets are
  cataloged; a following LOADPADB run would load an incomplete extract. Recommendation: decide.
- **Q-PAUDBUNL-07**: STEP0 deletes the outputs with `DISP=(OLD,DELETE,DELETE)`; when the datasets do not
  yet exist (first run) the job fails with a JCL error before STEP01 (see Q-UNLDPADB-01 in
  `modernization/spec/jobs.md`). Recommendation: preserve in the source; irrelevant in the target.
  Source: `app/app-authorization-ims-db2-mq/jcl/UNLDPADB.JCL:25-36`.
- **Q-PAUDBUNL-08**: The program clears PAUT-PCB-STATUS, which is in the IMS-owned PCB, after every GNP
  (line 284) and before every GN (line 212). The program relies on this to keep the end-of-children
  'GE' from being seen as an error in 2000. Recommendation: preserve (re-implementations should use
  local status variables).
- **Q-PAUDBUNL-09**: The job runs as a DL/I batch region with DBRC=N and no IMS log while the
  database datasets are allocated SHR. Nothing prevents it from running while the online system is
  updating DBPAUTP0, and PROCOPT GOTP reads without locks, so the extract may not be consistent.
  Recommendation: decide (operational: run with the database stopped or use an image copy).
- **Q-PAUDBUNL-10**: The output files hold full card numbers (PA-CARD-NUM) and customer ids in clear
  text. Recommendation: decide (data handling, see `docs/data-handling.md`).
- **Q-PAUDBUNL-11**: The 6-byte parent key on each child record is the only link between the two
  files, while the matching GSAM unload DBUNLDGS writes children without it; the two unload programs
  are not interchangeable as input to PAUDBLOD. Recommendation: preserve.

## Test scenarios
Sample IMS data: `app/app-authorization-ims-db2-mq/data/EBCDIC/AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat`
(a DFSURGU0 unload, not PAUDBUNL output) holds 21 valid roots (accounts 1, 5, 7, 13, 15, 16, 17, 18,
23, 29, 30, 31, 32, 33, 34, 38, 42, 45, 46, 47, 48) with 202 details in total, plus one root made of
X'40' bytes with no details.
- **TS-PAUDBUNL-01** (BR-01 to BR-04, BR-07): Given DBPAUTP0 loaded with the sample data, when UNLDPADB
  runs, then OUTFIL1 has 21 records in account order 1, 5, 7, ..., 48, OUTFIL2 has 202 records,
  'CHILD SEG FLAG GE : Y' appears 21 times, the X'40' root is absent from both files, and the step
  ends with RC 0.
- **TS-PAUDBUNL-02** (BR-03, BR-06): Given account 7 has 50 details, then OUTFIL2 contains 50
  consecutive records whose bytes 1-6 are X'00000000007C', positioned after the records of account 5
  and before those of account 13, in ascending key order (newest authorization first).
- **TS-PAUDBUNL-03** (BR-05): Given a root for account 99 with no details, then OUTFIL1 has its record,
  OUTFIL2 has none for it, and 'CHILD SEG FLAG GE : Y' is still displayed.
- **TS-PAUDBUNL-04** (BR-04, Q-02): Given a root whose first 6 bytes are X'404040404040', then nothing
  is written for it or its children and no message is displayed.
- **TS-PAUDBUNL-05** (ERR-02, ERR-07): Given OPEN of OUTFIL2 returns a status other than '00', then
  'ERROR IN OPENING OPFILE2:' + status and 'IMSUNLOD ABENDING ...' are displayed, RC is 16, and no
  record is written.
- **TS-PAUDBUNL-06** (ERR-03, Q-06): Given the root GN returns a status such as 'BA' (database
  unavailable) after 10 roots, then 'AUTH SUM  GN FAILED  :BA' and the key feedback line are
  displayed, RC is 16, and the 10 roots and their details already written remain in the cataloged
  output files.
- **TS-PAUDBUNL-07** (ERR-04): Given a GNP returns a status other than blank or 'GE', then 'GNP CALL
  FAILED  :' + status and 'KFB AREA IN CHILD:' + key feedback are displayed and RC is 16.
- **TS-PAUDBUNL-08** (Q-07): Given the FILEO datasets do not exist, when UNLDPADB is submitted, then
  STEP0 fails with a JCL error (dataset not found) and PAUDBUNL does not run.
- **TS-PAUDBUNL-09** (Q-01): Given any successful run, then no count of roots or details is printed.

## Open questions
- Which unload is the intended source for migration: DFSURGU0 (job DBPAUTP0, the format of the
  sample `.dat` file), PAUDBUNL, or DBUNLDGS?
- Should invalid roots be reported, repaired, or dropped as now?
- Is UNLDPADB meant to run only while the online IMS database is stopped?

## Target mapping proposal (non-normative)
With the tables proposed in `modernization/spec/programs/COPAUA0C.md` (section "Target mapping
proposal"), PAUDBUNL becomes a migration extract and has no ongoing role:
- Extract from IMS once with PAUDBUNL (or DFSURGU0), convert each OUTFIL1 record to a
  `pending_auth_summary` row and each OUTFIL2 record to a `pending_auth_detail` row with
  `account_id` taken from bytes 1-6. Conversion rules: COMP-3 fields to NUMERIC, binary halfword
  counts to SMALLINT, EBCDIC text to the target code page, `auth_key` = hex of the 8-byte segment
  key, `auth_ts` decoded from the 9-complement date and time (see COPAUA0C).
- Reject (and report) roots whose key is not valid packed decimal, matching BR-PAUDBUNL-04 but
  visibly.
- Reconcile counts: rows loaded = OUTFIL1 records and OUTFIL2 records.
- After migration the equivalent of an unload is a plain export:
  `SELECT * FROM pending_auth_summary ORDER BY account_id` and
  `SELECT * FROM pending_auth_detail ORDER BY account_id, auth_key`.
