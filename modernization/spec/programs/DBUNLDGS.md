# DBUNLDGS: Unload the pending-authorization IMS database to two GSAM files

| Attribute | Value |
|:--|:--|
| Source | `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL` (366 lines) |
| Module | app-authorization-ims-db2-mq |
| Kind | Batch (IMS DL/I batch region, `CALL 'CBLTDLI'`) |
| Transaction ID / Job | JOB-UNLDGSAM (`app/app-authorization-ims-db2-mq/jcl/UNLDGSAM.JCL`), STEP01 `EXEC PGM=DFSRRC00,PARM='DLI,DBUNLDGS,DLIGSAMP,,,,,,,,,,,N'` |
| BMS map | none |
| Copybooks | IMSFUNCS (DL/I function codes), CIPAUSMY (PAUTSUM0 layout), CIPAUDTY (PAUTDTL1 layout), PAUTBPCB (DB PCB mask), PASFLPCB (GSAM PCB mask for summaries), PADFLPCB (GSAM PCB mask for details) |
| Calls / XCTL targets | CBLTDLI: GN (root), GNP (child) on the DB PCB; ISRT on each GSAM PCB |
| Data stores | IMS DBPAUTP0 via PSB DLIGSAMP, DB PCB PROCOPT=GOTP (read only; see PSB note). GSAM PASFLDBD (DD PASFILOP) Write, 100-byte fixed records. GSAM PADFLDBD (DD PADFILOP) Write, 200-byte fixed records. |

## Purpose
DBUNLDGS is a variant of PAUDBUNL that writes the unload through IMS GSAM databases instead of COBOL
files: every account summary goes to one GSAM file and every authorization detail to another. Unlike
PAUDBUNL, the detail records carry no parent account id, so the hierarchy is recoverable only from
the record order. The database itself is not changed.

## Invocation and state
- JCL UNLDGSAM (`app/app-authorization-ims-db2-mq/jcl/UNLDGSAM.JCL:26-52`): DFSRRC00 as a DL/I batch
  region, program DBUNLDGS, PSB DLIGSAMP, positional parameter 14 = `N` (DBRC=N in the standard DLI
  parameter order), IMSLOGR and IEFRDER DUMMY. Database DDs DDPAUTP0 and DDPAUTX0 allocated SHR.
  - PASFILOP -> `AWS.M2.CARDDEMO.PAUTDB.ROOT.GSAM`, `DISP=(OLD,KEEP,KEEP)`.
  - PADFILOP -> `AWS.M2.CARDDEMO.PAUTDB.CHILD.GSAM`, `DISP=(OLD,KEEP,KEEP)`.
  - Both datasets must already exist; no job in the repository creates them.
- PSB DLIGSAMP (`app/app-authorization-ims-db2-mq/ims/DLIGSAMP.PSB:18-23`), `CMPAT=NO` (no I/O PCB in
  a DLI region), three PCBs in this order:
  1. DB PCB PAUTBPCB on DBPAUTP0, PROCOPT=GOTP (get only, read without locking, T = on an invalid pointer the call is
     retried and then returns status GG instead of abending, which falls under ERR-DBUNLDGS-01/-02; P = path
     calls allowed), KEYLEN=14, segments
     PAUTSUM0 and PAUTDTL1.
  2. GSAM PCB on PASFLDBD, PROCOPT=LS (load, that is sequential output).
  3. GSAM PCB on PADFLDBD, PROCOPT=LS.
- GSAM DBDs: PASFLDBD (`app/app-authorization-ims-db2-mq/ims/PASFLDBD.DBD:22-27`) ACCESS=(GSAM,BSAM),
  input DD PASFILIP, output DD PASFILOP, RECORD=100, RECFM=F. PADFLDBD
  (`app/app-authorization-ims-db2-mq/ims/PADFLDBD.DBD:22-27`) the same with DDs PADFILIP / PADFILOP and
  RECORD=200.
- Linkage: `PROCEDURE DIVISION USING PAUTBPCB PASFLPCB PADFLPCB` (159-161) and
  `ENTRY 'DLITCBL' USING PAUTBPCB PASFLPCB PADFLPCB` (165-167) agree with the PSB order.
- Parameters: none (`ACCEPT PRM-INFO FROM SYSIN` commented out at line 188).
- Return code: 0 on normal end; 16 from 9999-ABEND (GOBACK).
- IMS opens and closes the GSAM datasets itself; the program has no COBOL files (the FD and OPEN /
  CLOSE code of PAUDBUNL is commented out, lines 26-48, 195-209, 340-353).

### Output record layouts
| GSAM file | Bytes | Content |
|:--|:--|:--|
| PASFILOP (PASFLDBD) | 1-100 | The PAUTSUM0 segment exactly as stored (CIPAUSMY) |
| PADFILOP (PADFLDBD) | 1-200 | The PAUTDTL1 segment exactly as stored (CIPAUDTY); no parent key |

## Processing flow
1. MAIN-PARA (164-179): 1000-INITIALIZE; PERFORM 2000-FIND-NEXT-AUTH-SUMMARY until
   WS-END-OF-ROOT-SEG = 'Y'; 4000-FILE-CLOSE; GOBACK.
2. 1000-INITIALIZE (182-213): accept date and day; DISPLAY 'STARTING PROGRAM DBUNLDGS::',
   '*-------------------------------------*', 'TODAYS DATE            :' + YYMMDD, ' '.
3. 2000-FIND-NEXT-AUTH-SUMMARY (216-259): clear the PCB status; DL/I "get the next root (PAUTSUM0) in
   database order" (GN, unqualified SSA `PAUTSUM0 `).
   1. IF status blank: add 1 to WS-NO-SUMRY-READ and WS-AUTH-SMRY-PROC-CNT; copy the summary to the
      working-storage OPFIL1-REC; clear and set ROOT-SEG-KEY = PA-ACCT-ID (neither is used further).
      IF PA-ACCT-ID is valid packed decimal: 3100-INSERT-PARENT-SEG-GSAM; clear WS-END-OF-CHILD-SEG;
      PERFORM 3000-FIND-NEXT-AUTH-DTL until WS-END-OF-CHILD-SEG = 'Y'. OTHERWISE the root and its
      children are skipped silently.
   2. IF status 'GB': set END-OF-AUTHDB and WS-END-OF-ROOT-SEG = 'Y'.
   3. IF status neither blank nor 'GB': DISPLAY 'AUTH SUM  GN FAILED  :' + status,
      'KEY FEEDBACK AREA    :' + PAUT-KEYFB; 9999-ABEND.
4. 3000-FIND-NEXT-AUTH-DTL (263-297): DL/I "get the next detail under the current root" (GNP,
   unqualified SSA `PAUTDTL1 `).
   1. IF status blank: set MORE-AUTHS; add 1 to WS-NO-SUMRY-READ and WS-AUTH-SMRY-PROC-CNT; copy the
      detail to CHILD-SEG-REC (unused); 3200-INSERT-CHILD-SEG-GSAM.
   2. IF status 'GE': WS-END-OF-CHILD-SEG = 'Y'; DISPLAY 'CHILD SEG FLAG GE : ' + 'Y'.
   3. IF status neither blank nor 'GE': DISPLAY 'GNP CALL FAILED  :' + status, 'KFB AREA IN CHILD:' +
      PAUT-KEYFB; 9999-ABEND.
   4. Clear PAUT-PCB-STATUS (295).
5. 3100-INSERT-PARENT-SEG-GSAM (300-317): GSAM "append this 100-byte record to the summary file" (ISRT
   on PASFLPCB with PENDING-AUTH-SUMMARY, no SSA). IF status not blank: DISPLAY 'GSAM PARENT FAIL :' +
   status, 'KFB AREA IN GSAM:' + PASFL-KEYFB; 9999-ABEND.
6. 3200-INSERT-CHILD-SEG-GSAM (319-336): GSAM "append this 200-byte record to the detail file" (ISRT on
   PADFLPCB with PENDING-AUTH-DETAILS). IF status not blank: DISPLAY 'GSAM PARENT FAIL :' + status
   (same text as the parent case), 'KFB AREA IN GSAM:' + PADFL-KEYFB; 9999-ABEND.
7. 4000-FILE-CLOSE (338-355): DISPLAY 'CLOSING THE FILE' only.
8. 9999-ABEND (357-366): DISPLAY 'DBUNLDGS ABENDING ...'; RETURN-CODE = 16; GOBACK.

## Rules
### Business rules
- **BR-DBUNLDGS-01**: Every root of DBPAUTP0 is visited once in root key order (ascending packed
  account id). Source: `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL:172-173 (MAIN-PARA)`,
  `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL:221-225 (2000-FIND-NEXT-AUTH-SUMMARY)`.
- **BR-DBUNLDGS-02**: When a root is read and its PA-ACCT-ID is valid packed decimal, its 100 bytes are
  appended unchanged to the summary GSAM file. Source:
  `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL:232-243 (2000-FIND-NEXT-AUTH-SUMMARY)`,
  `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL:302-304 (3100-INSERT-PARENT-SEG-GSAM)`.
- **BR-DBUNLDGS-03**: Right after each such root, every detail under it is read in key order (newest
  authorization first) and its 200 bytes are appended unchanged to the detail GSAM file, without the
  parent key. Source:
  `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL:244-246 (2000-FIND-NEXT-AUTH-SUMMARY)`,
  `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL:267-282 (3000-FIND-NEXT-AUTH-DTL)`,
  `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL:321-323 (3200-INSERT-CHILD-SEG-GSAM)`.
- **BR-DBUNLDGS-04**: When a root's PA-ACCT-ID is not valid packed decimal, neither it nor its children
  are written, without a message. Source:
  `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL:241-247 (2000-FIND-NEXT-AUTH-SUMMARY)`.
- **BR-DBUNLDGS-05**: 'CHILD SEG FLAG GE : Y' is displayed once per root written, including roots with
  no details (which add nothing to the detail file). Source:
  `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL:284-289 (3000-FIND-NEXT-AUTH-DTL)`.
- **BR-DBUNLDGS-06**: The run ends when the root GN returns 'GB'; 'CLOSING THE FILE' is displayed, no
  totals are printed, RC is 0. Source:
  `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL:249-252, 338-339`.
- **BR-DBUNLDGS-07**: At start the program displays 'STARTING PROGRAM DBUNLDGS::', a dashed line,
  'TODAYS DATE            :' + YYMMDD and a blank line; it takes no parameters. Source:
  `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL:185-192 (1000-INITIALIZE)`.

### Validation rules
- None produce a message. The only data check is the silent packed-numeric test in BR-DBUNLDGS-04.

### Navigation and keys
- Not applicable (batch).

### Error handling
- **ERR-DBUNLDGS-01**: Root GN status other than blank and 'GB' -> 'AUTH SUM  GN FAILED  :' + status,
  'KEY FEEDBACK AREA    :' + key feedback, 9999-ABEND (RC 16). Source:
  `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL:253-257 (2000-FIND-NEXT-AUTH-SUMMARY)`.
- **ERR-DBUNLDGS-02**: Child GNP status other than blank and 'GE' -> 'GNP CALL FAILED  :' + status,
  'KFB AREA IN CHILD:' + key feedback, 9999-ABEND (RC 16). Source:
  `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL:290-294 (3000-FIND-NEXT-AUTH-DTL)`.
- **ERR-DBUNLDGS-03**: Summary GSAM ISRT status not blank -> 'GSAM PARENT FAIL :' + status, 'KFB AREA IN
  GSAM:' + PASFL-KEYFB (100 bytes), 9999-ABEND (RC 16). Source:
  `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL:311-315 (3100-INSERT-PARENT-SEG-GSAM)`.
- **ERR-DBUNLDGS-04**: Detail GSAM ISRT status not blank -> 'GSAM PARENT FAIL :' + status, 'KFB AREA IN
  GSAM:' + PADFL-KEYFB (255 bytes), 9999-ABEND (RC 16). Source:
  `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL:330-334 (3200-INSERT-CHILD-SEG-GSAM)`.
- **ERR-DBUNLDGS-05**: 9999-ABEND displays 'DBUNLDGS ABENDING ...', sets RC 16 and ends with GOBACK;
  IMS then closes the GSAM files normally, keeping what was written. Source:
  `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL:357-363 (9999-ABEND)`.
- Dataset allocation or open problems for the GSAM DDs are detected by IMS, not by the program; how
  they surface (status on the first ISRT or an IMS abend) is not determined by the source.

## Data effects
- IMS DBPAUTP0: read only; nothing is changed.
- Summary GSAM file (PASFILOP): one 100-byte record per root with a valid key, in key order. The
  existing dataset content is replaced (GSAM load from the start of the dataset).
- Detail GSAM file (PADFILOP): one 200-byte record per detail of those roots, grouped by root in the
  same order, newest first within a root.

## Quirks
- **Q-DBUNLDGS-01**: The detail file has no parent account id. A detail can be tied to its summary only
  by walking both files in step and knowing how many details each root had, and the summary file
  carries no such count that is guaranteed to match (PA-APPROVED-AUTH-CNT and PA-DECLINED-AUTH-CNT are
  maintained separately by COPAUA0C and are not reduced by CBPAUP0C). The code builds the
  PAUDBUNL-style key prefix (ROOT-SEG-KEY) but never writes it. Recommendation: fix (write the
  parent key, or use PAUDBUNL). Source:
  `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL:236-239, 280-282`.
- **Q-DBUNLDGS-02**: The detail-file error message says 'GSAM PARENT FAIL :', the same text as the
  summary-file error, so the two cannot be told apart in the log. Recommendation: fix. Source:
  `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL:331`.
- **Q-DBUNLDGS-03**: Child reads are counted in the summary counters and no counter is displayed, so a
  run gives no record counts. Recommendation: fix. Source:
  `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL:234-235, 278-279`.
- **Q-DBUNLDGS-04**: Roots with an invalid packed key are dropped silently with their children (the
  sample database has one root with an X'40' key). Recommendation: decide.
- **Q-DBUNLDGS-05**: The GSAM output datasets are allocated `DISP=(OLD,KEEP,KEEP)`, must be created
  beforehand by a job that is not in the repository, and are kept even when the step ends with RC 16,
  holding a partial unload. Recommendation: decide (operational).
  Source: `app/app-authorization-ims-db2-mq/jcl/UNLDGSAM.JCL:36-40`.
- **Q-DBUNLDGS-06**: No program in the repository reads these GSAM files (PAUDBLOD reads the 206-byte
  PAUDBUNL format). Recommendation: decide (keep or retire the job).
- **Q-DBUNLDGS-07**: The GN error IF at line 253 has no END-IF and ends at the period on line 257; it
  behaves correctly. Recommendation: preserve.
- **Q-DBUNLDGS-08**: For GSAM the PCB key feedback area holds the record search argument (a binary
  record position), not a key, so 'KFB AREA IN GSAM:' prints unreadable bytes. Recommendation:
  preserve (cosmetic).
- **Q-DBUNLDGS-09**: As with PAUDBUNL, the job runs with DBRC=N, no log and SHR database DDs, so it can
  read the database while the online system updates it; the files hold full card numbers in clear
  text. Recommendation: decide.
- **Q-DBUNLDGS-10**: Working storage carries unused items (WS-PGMNAME 'IMSUNLOD', PRM-INFO, checkpoint id,
  the OPFIL1-REC / OPFIL2-REC images moved into working storage). Recommendation: preserve
  (cosmetic).

## Test scenarios
Sample IMS data as described in `modernization/spec/programs/PAUDBUNL.md` (21 valid roots, 202
details, one root with an X'40' key and no details).
- **TS-DBUNLDGS-01** (BR-01 to BR-06): Given DBPAUTP0 loaded with the sample data and both GSAM datasets
  pre-allocated, when UNLDGSAM runs, then the summary GSAM file holds 21 records in account order 1,
  5, 7, ..., 48, the detail GSAM file holds 202 records, 'CHILD SEG FLAG GE : Y' appears 21 times, and
  RC is 0.
- **TS-DBUNLDGS-02** (BR-03, Q-01): Given the same run, then the 50 details of account 7 are the 50
  consecutive detail records after those of accounts 1 and 5, and none of the 202 records contains an
  account id; the card number in bytes 21-36 of each record is the only business link (for account 7,
  card 4859452612877065, resolvable to the account through the base CCXREF file).
- **TS-DBUNLDGS-03** (BR-04): Given a root whose key bytes are X'404040404040', then neither file
  receives anything for it.
- **TS-DBUNLDGS-04** (ERR-04, Q-02): Given the detail-file ISRT returns a non-blank status, then 'GSAM
  PARENT FAIL :' + status, 'KFB AREA IN GSAM:' + feedback and 'DBUNLDGS ABENDING ...' are displayed and
  RC is 16; the records already written remain.
- **TS-DBUNLDGS-05** (ERR-01): Given the root GN returns a status other than blank or 'GB', then 'AUTH
  SUM  GN FAILED  :' + status and the key feedback line are displayed and RC is 16.
- **TS-DBUNLDGS-06** (BR-05): Given a root with no details, then one summary record is written, no
  detail record is written, and 'CHILD SEG FLAG GE : Y' is displayed.

## Open questions
- What consumes the GSAM unload, and how is the detail-to-summary relationship expected to be
  rebuilt?
- `MOVE PA-ACCT-ID TO ROOT-SEG-KEY` (line 239) runs before the `IS NUMERIC` test (line 241). Depending on
  the z/OS compiler options (for example NUMPROC), a packed-to-packed move may be generated as ZAP, which
  would raise a data exception (S0C7) on the sample root with key X'404040404040' before BR-DBUNLDGS-04 can
  skip it. GnuCOBOL 3.1.2 raises nothing. Which options is the production load module compiled with?
- Which job allocates `AWS.M2.CARDDEMO.PAUTDB.ROOT.GSAM` and `...CHILD.GSAM`?
- Should DBUNLDGS be kept at all, given PAUDBUNL produces a self-describing extract?

## Target mapping proposal (non-normative)
No ongoing role after migration. If an equivalent export is wanted, write the two tables proposed in
`modernization/spec/programs/COPAUA0C.md` to files and include `account_id` in the detail export so
the relationship does not depend on record order:
```sql
SELECT * FROM pending_auth_summary ORDER BY account_id;
SELECT account_id, d.* FROM pending_auth_detail d ORDER BY account_id, auth_key;
```
GSAM files map to plain sequential files or object storage; the GSAM ISRT becomes an append.
