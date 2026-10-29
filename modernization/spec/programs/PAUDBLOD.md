# PAUDBLOD: Load the pending-authorization IMS database from two flat files

| Attribute | Value |
|:--|:--|
| Source | `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL` (369 lines) |
| Module | app-authorization-ims-db2-mq |
| Kind | Batch (IMS BMP, `CALL 'CBLTDLI'`) |
| Transaction ID / Job | JOB-LOADPADB (`app/app-authorization-ims-db2-mq/jcl/LOADPADB.JCL`), STEP01 `EXEC PGM=DFSRRC00,PARM='BMP,PAUDBLOD,PSBPAUTB'` |
| BMS map | none |
| Copybooks | IMSFUNCS (DL/I function codes), CIPAUSMY (PAUTSUM0 layout), CIPAUDTY (PAUTDTL1 layout), PAUTBPCB (DB PCB mask) |
| Calls / XCTL targets | CBLTDLI: ISRT (root), GU (root by key), ISRT (child) |
| Data stores | INFILE1 (DD INFILE1) Read sequential, 100 bytes. INFILE2 (DD INFILE2) Read sequential, 206 bytes. IMS DBPAUTP0 via PSB PSBPAUTB (PROCOPT=AP): PAUTSUM0 Insert and Get Unique by ACCNTID; PAUTDTL1 Insert. |

## Purpose
PAUDBLOD rebuilds the pending-authorization database from the two files written by PAUDBUNL. It
first inserts every account summary (root) from the first file, then inserts every authorization
detail from the second file under the summary whose account id prefixes the record. Segments that
already exist are reported and skipped, so the load can be rerun.

## Invocation and state
- JCL LOADPADB (`app/app-authorization-ims-db2-mq/jcl/LOADPADB.JCL:26-50`): DFSRRC00 as a BMP
  (runs against the online IMS control region, so the database DDs DDPAUTP0 and DDPAUTX0 are
  commented out at lines 40-41), program PAUDBLOD, PSB PSBPAUTB.
  - INFILE1 -> `AWS.M2.CARDDEMO.PAUTDB.ROOT.FILEO` (SHR), the OUTFIL1 output of PAUDBUNL.
  - INFILE2 -> `AWS.M2.CARDDEMO.PAUTDB.CHILD.FILEO` (SHR), the OUTFIL2 output of PAUDBUNL.
- PSB PSBPAUTB (`app/app-authorization-ims-db2-mq/ims/PSBPAUTB.psb:17-20`): one DB PCB on DBPAUTP0,
  PROCOPT=AP (all functions: get, insert, replace, delete), KEYLEN=14, segments PAUTSUM0 and
  PAUTDTL1, `CMPAT=YES`. In a BMP IMS passes the I/O PCB first, then the DB PCB.
- Linkage: `PROCEDURE DIVISION USING IO-PCB-MASK PAUTBPCB` (164-165) expects both PCBs, but
  `ENTRY 'DLITCBL' USING PAUTBPCB` (171) names only one (see Q-PAUDBLOD-01).
- Input record layouts:

| File | Bytes | Content |
|:--|:--|:--|
| INFILE1 (INFIL1-REC, line 44) | 1-100 | PAUTSUM0 segment image (CIPAUSMY), key PA-ACCT-ID packed in bytes 1-6 |
| INFILE2 (INFIL2-REC, lines 46-48) | 1-6 | ROOT-SEG-KEY S9(11) COMP-3: parent account id |
| INFILE2 | 7-206 | CHILD-SEG-REC: PAUTDTL1 segment image (CIPAUDTY), key in bytes 1-8 of the segment |

- Parameters: none (PRM-INFO is declared but never read).
- Return code: 0 on normal end (never set explicitly); 16 from 9999-ABEND, which ends with GOBACK.
- No checkpoints are taken; the whole load is one unit of work committed at normal program end.

## Processing flow
1. MAIN-PARA (169-187): DISPLAY 'STARTING PAUDBLOD'; 1000-INITIALIZE; PERFORM 2000-READ-ROOT-SEG-FILE
   until END-ROOT-SEG-FILE = 'Y'; PERFORM 3000-READ-CHILD-SEG-FILE until END-CHILD-SEG-FILE = 'Y';
   4000-FILE-CLOSE; GOBACK.
2. 1000-INITIALIZE (190-219): accept today's date and day; DISPLAY
   '*-------------------------------------*', 'TODAYS DATE            :' + YYMMDD, ' '. OPEN INPUT
   INFILE1; IF status not spaces or '00', DISPLAY 'ERROR IN OPENING INFILE1:' + status and
   9999-ABEND. OPEN INPUT INFILE2; same with 'ERROR IN OPENING INFILE2:'.
3. 2000-READ-ROOT-SEG-FILE (222-240): READ INFILE1.
   - IF status spaces or '00': copy the record into PENDING-AUTH-SUMMARY; 2100-INSERT-ROOT-SEG.
   - OTHERWISE IF status '10' (end of file): END-ROOT-SEG-FILE = 'Y'.
   - OTHERWISE: DISPLAY 'ERROR READING ROOT SEG INFILE' and continue with the next READ.
4. 2100-INSERT-ROOT-SEG (242-265): DL/I "insert this summary as a new root segment" (ISRT with the
   unqualified SSA `PAUTSUM0 `; IMS places it by its key). DISPLAY ' *******************************'
   twice (lines 248 and 252, around commented-out diagnostics).
   - IF status blank: DISPLAY 'ROOT INSERT SUCCESS    '.
   - IF status 'II' (a root with this key already exists): DISPLAY 'ROOT SEGMENT ALREADY IN DB';
     the existing root is left unchanged.
   - IF status neither blank nor 'II': DISPLAY 'ROOT INSERT FAILED  :' + status; 9999-ABEND.
5. 3000-READ-CHILD-SEG-FILE (269-291): READ INFILE2.
   - IF status spaces or '00': IF ROOT-SEG-KEY is valid packed decimal, move it to the qualified SSA
     key value, copy CHILD-SEG-REC into PENDING-AUTH-DETAILS, and 3100-INSERT-CHILD-SEG. OTHERWISE
     the record is skipped silently.
   - OTHERWISE IF status '10': END-CHILD-SEG-FILE = 'Y'.
   - OTHERWISE: DISPLAY 'ERROR READING CHILD SEG INFILE' and continue.
6. 3100-INSERT-CHILD-SEG (292-316): clear the PCB status; DL/I "get the root whose ACCNTID equals the
   record's account id" (GU with SSA `PAUTSUM0(ACCNTID EQ` + 6-byte packed key + `)`); DISPLAY
   '***************************' twice.
   - IF status blank: DISPLAY 'GU CALL TO ROOT SEG SUCCESS'; 3200-INSERT-IMS-CALL; then (nested in
     the same IF, line 310) IF status neither blank nor 'II', DISPLAY 'ROOT GU CALL FAIL:' + status,
     'KFB AREA IN CHILD:' + key feedback, 9999-ABEND.
   - IF status not blank (for example 'GE', root not found): nothing else happens; the detail is not
     inserted and no message is displayed.
7. 3200-INSERT-IMS-CALL (318-339): DL/I "insert this detail under the root just retrieved" (ISRT with
   the unqualified SSA `PAUTDTL1 `; parentage comes from the preceding GU).
   - IF status blank: DISPLAY 'CHILD SEGMENT INSERTED SUCCESS'.
   - IF status 'II' (a detail with this key already exists under the root): DISPLAY 'CHILD SEGMENT
     ALREADY IN DB'.
   - IF status neither blank nor 'II': DISPLAY 'INSERT CALL FAIL FOR CHILD:' + status and
     'KFB AREA IN CHILD:' + key feedback; 9999-ABEND.
8. 4000-FILE-CLOSE (341-358): DISPLAY 'CLOSING THE FILE'; CLOSE INFILE1, IF status not spaces or '00'
   DISPLAY 'ERROR IN CLOSING 1ST FILE:' + status; CLOSE INFILE2, same with
   'ERROR IN CLOSING 2ND FILE:'.
9. 9999-ABEND (360-369): DISPLAY 'IMS LOAD ABENDING ...'; RETURN-CODE = 16; GOBACK.

## Rules
### Business rules
- **BR-PAUDBLOD-01**: All root records are processed before any child record, in file order. Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:177-181 (MAIN-PARA)`.
- **BR-PAUDBLOD-02**: Each root record is inserted byte-for-byte as a PAUTSUM0 segment; no field is
  validated, converted or recomputed (counts, amounts and balances are loaded as they are, and the
  key is not checked for valid packed decimal). Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:228-230 (2000-READ-ROOT-SEG-FILE)`,
  `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:244-247 (2100-INSERT-ROOT-SEG)`.
- **BR-PAUDBLOD-03**: When a root key already exists (status 'II'), 'ROOT SEGMENT ALREADY IN DB' is
  displayed and the stored root is kept unchanged; the run continues. A successful insert displays
  'ROOT INSERT SUCCESS    '. Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:253-258 (2100-INSERT-ROOT-SEG)`.
- **BR-PAUDBLOD-04**: A child record is processed only when its 6-byte parent key is valid packed
  decimal; otherwise it is skipped with no message. Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:274-282 (3000-READ-CHILD-SEG-FILE)`.
- **BR-PAUDBLOD-05**: For each processed child record the parent root is retrieved by key (GU
  ACCNTID EQ parent key); on success 'GU CALL TO ROOT SEG SUCCESS' is displayed and the 200-byte
  detail is inserted unchanged under that root, keeping its original key and therefore its
  newest-first order. Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:296-309 (3100-INSERT-CHILD-SEG)`,
  `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:321-324 (3200-INSERT-IMS-CALL)`.
- **BR-PAUDBLOD-06**: When the parent root is not found (or the GU returns any non-blank status), the
  child is not inserted and nothing is displayed except the two asterisk lines; the run continues.
  Source: `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:305-314 (3100-INSERT-CHILD-SEG)`.
- **BR-PAUDBLOD-07**: When a child key already exists under the root (status 'II'), 'CHILD SEGMENT
  ALREADY IN DB' is displayed and the stored detail is kept; a successful insert displays 'CHILD
  SEGMENT INSERTED SUCCESS'. Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:326-331 (3200-INSERT-IMS-CALL)`.
- **BR-PAUDBLOD-08**: Children are inserted under existing roots even when the root itself was skipped
  as a duplicate, so a rerun or a load into a populated database merges new details into existing
  accounts. Source: as BR-03 and BR-05.
- **BR-PAUDBLOD-09**: Console output per record: for a root, two lines ' *******************************'
  and one result line; for a child, two lines '***************************' and, on success, 'GU CALL
  TO ROOT SEG SUCCESS' plus one result line. No totals are displayed. Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:248-258, 300-306, 326-331`.
- **BR-PAUDBLOD-10**: The program takes no parameters; it displays 'STARTING PAUDBLOD', a dashed
  line, 'TODAYS DATE            :' + YYMMDD and a blank line at start. Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:173, 193-198 (MAIN-PARA, 1000-INITIALIZE)`.

### Validation rules
- None produce a message. The only data check is the silent packed-numeric test of the child
  record's parent key (BR-PAUDBLOD-04).

### Navigation and keys
- Not applicable (batch).

### Error handling
- **ERR-PAUDBLOD-01**: OPEN INPUT INFILE1 status other than spaces or '00' -> 'ERROR IN OPENING
  INFILE1:' + status, 9999-ABEND (RC 16). Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:201-207 (1000-INITIALIZE)`.
- **ERR-PAUDBLOD-02**: OPEN INPUT INFILE2 status other than spaces or '00' -> 'ERROR IN OPENING
  INFILE2:' + status, 9999-ABEND (RC 16). Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:209-215 (1000-INITIALIZE)`.
- **ERR-PAUDBLOD-03**: READ INFILE1 status other than spaces, '00' and '10' -> 'ERROR READING ROOT SEG
  INFILE'; no abend, the loop reads again. Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:231-236 (2000-READ-ROOT-SEG-FILE)`.
- **ERR-PAUDBLOD-04**: READ INFILE2 status other than spaces, '00' and '10' -> 'ERROR READING CHILD SEG
  INFILE'; no abend, the loop reads again. Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:283-288 (3000-READ-CHILD-SEG-FILE)`.
- **ERR-PAUDBLOD-05**: Root ISRT status other than blank and 'II' -> 'ROOT INSERT FAILED  :' + status,
  9999-ABEND (RC 16). Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:259-262 (2100-INSERT-ROOT-SEG)`.
- **ERR-PAUDBLOD-06**: Child ISRT status other than blank and 'II' -> 'INSERT CALL FAIL FOR CHILD:' +
  status, 'KFB AREA IN CHILD:' + key feedback, 9999-ABEND (RC 16). Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:332-336 (3200-INSERT-IMS-CALL)`.
- **ERR-PAUDBLOD-07**: The check 'ROOT GU CALL FAIL:' + status / 'KFB AREA IN CHILD:' is reached only
  after a successful GU and a child ISRT that ended with blank or 'II' (any other status already
  abended in 3200), so it can never fire. Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:310-314 (3100-INSERT-CHILD-SEG)`.
- **ERR-PAUDBLOD-08**: CLOSE status other than spaces or '00' -> 'ERROR IN CLOSING 1ST FILE:' + status
  or 'ERROR IN CLOSING 2ND FILE:' + status; display only. Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:341-356 (4000-FILE-CLOSE)`.
- **ERR-PAUDBLOD-09**: 9999-ABEND displays 'IMS LOAD ABENDING ...', sets RC 16 and ends with GOBACK.
  The BMP terminates normally, so IMS commits every segment inserted before the error. Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:360-366 (9999-ABEND)`.

## Data effects
- PAUTSUM0: one insert per INFILE1 record whose key is not already present.
- PAUTDTL1: one insert per INFILE2 record whose parent key is valid packed decimal, whose parent root
  exists, and whose detail key is not already present under that parent.
- Existing segments are never replaced or deleted.
- Unit of work: the whole run (no CHKP). Normal end and the RC 16 path both commit; only a real abend
  (for example a program check) would back out the inserts.

## Quirks
- **Q-PAUDBLOD-01**: The PROCEDURE DIVISION expects (I/O PCB, DB PCB) but the `ENTRY 'DLITCBL'` names
  only the DB PCB mask. If the load module is entered through DLITCBL, PAUTBPCB is bound to the first
  PCB IMS passes, which in a BMP is the I/O PCB, and every database call would be issued against the
  I/O PCB and fail (first symptom: 'ROOT INSERT FAILED  :' and RC 16). If it is entered through the
  program name, the binding is correct. The link-edit entry point is not in the repository.
  Recommendation: decide (verify, then make both USING lists `IO-PCB-MASK PAUTBPCB`). Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:164-171`.
- **Q-PAUDBLOD-02**: The GU result IF at line 305 has no END-IF before the second IF at line 310, so the
  error branch is nested inside the success branch. As a result a child whose parent root does not
  exist is dropped silently, and the 'ROOT GU CALL FAIL:' error is unreachable (ERR-PAUDBLOD-07).
  Recommendation: fix (report orphan children and count them). Source:
  `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL:305-314`.
- **Q-PAUDBLOD-03**: A READ error other than end of file is displayed and the loop simply reads again;
  a persistent error repeats the message without end, and a transient one silently loses the record.
  Recommendation: fix (abend on read errors).
- **Q-PAUDBLOD-04**: No checkpoints are taken in the BMP, so all inserts and their locks are held until
  the end of the run; on the RC 16 path the partial load is committed. Recommendation: decide.
- **Q-PAUDBLOD-05**: Duplicates ('II') are skipped, not replaced, so reloading a file with changed data
  does not update the database, and a loaded summary can disagree with its loaded children.
  Recommendation: preserve (rerun safety) or decide.
- **Q-PAUDBLOD-06**: Summary values are loaded as stored and are not reconciled with the children (for
  example approved count against the number of approved details); COPAUA0C later overwrites
  PA-CASH-BALANCE with 0 on every approval (Q-COPAUA0C-17). Recommendation: preserve.
- **Q-PAUDBLOD-07**: Root records are not checked for a valid packed key, unlike child records and unlike
  PAUDBUNL. A root with an X'40' key would be inserted; the sample database contains such a root
  (key and amounts X'40'), which may have entered this way. Recommendation: fix (reject invalid keys).
- **Q-PAUDBLOD-08**: A GU is issued for every child record, even when consecutive children have the same
  parent. Recommendation: preserve (performance only).
- **Q-PAUDBLOD-09**: No counts are kept or displayed, and the console gets three or four lines per
  record, so a large load produces a very long SYSOUT with no summary. Recommendation: fix.
- **Q-PAUDBLOD-10**: Working storage carries unused items copied from CBPAUP0C (WS-PGMNAME 'IMSUNLOD',
  PRM-INFO, expiry days, checkpoint id, end-of-file flags WS-END-OF-INFILE1/2). Recommendation: preserve (cosmetic).
- **Q-PAUDBLOD-11**: LOADPADB reads the PAUDBUNL output format only; the sample data file in the repo
  is a DFSURGU0 unload (job DBPAUTP0) and the DBUNLDGS GSAM child file has no parent key, so neither
  can be loaded by this program. Recommendation: preserve.

## Test scenarios
Inputs are the UNLDPADB outputs of the sample database (21 roots, 202 child records; see
`modernization/spec/programs/PAUDBUNL.md`). Q-PAUDBLOD-01 is assumed not to occur unless stated.
- **TS-PAUDBLOD-01** (BR-01 to BR-05): Given an empty DBPAUTP0, when LOADPADB runs, then 21 'ROOT
  INSERT SUCCESS    ' lines, 202 'GU CALL TO ROOT SEG SUCCESS' and 202 'CHILD SEGMENT INSERTED
  SUCCESS' lines are displayed, account 7 has 50 details in their original key order, and RC is 0.
  (INFILE2 as produced by PAUDBUNL under GnuCOBOL, where every child carries its parent key; see
  Q-PAUDBUNL-12.)
- **TS-PAUDBLOD-02** (BR-03, BR-07, Q-05): Given the database already holds the same data, when LOADPADB
  runs again, then 21 'ROOT SEGMENT ALREADY IN DB' and 202 'CHILD SEGMENT ALREADY IN DB' lines are
  displayed, nothing changes, and RC is 0.
- **TS-PAUDBLOD-03** (BR-06, Q-02): Given INFILE2 contains a record with parent key X'00000000099C'
  (account 99) and INFILE1 has no root for account 99, then the GU fails, only the two asterisk lines
  are displayed for it, the detail is not inserted, and RC is 0.
- **TS-PAUDBLOD-04** (BR-04): Given an INFILE2 record whose bytes 1-6 are X'404040404040', then it is
  skipped with no output at all.
- **TS-PAUDBLOD-05** (BR-08): Given account 7 already exists with 50 details and INFILE2 has one more
  detail for account 7 with a new key, then 'ROOT SEGMENT ALREADY IN DB' is shown for the root and the
  new detail is inserted (account 7 then has 51 details); the stored summary is unchanged.
- **TS-PAUDBLOD-06** (ERR-01): Given the INFILE1 dataset cannot be opened, then 'ERROR IN OPENING
  INFILE1:' + status and 'IMS LOAD ABENDING ...' are displayed, RC is 16, and nothing is loaded.
- **TS-PAUDBLOD-07** (ERR-05, ERR-09): Given the 11th root insert returns a status other than blank or
  'II', then 'ROOT INSERT FAILED  :' + status and 'IMS LOAD ABENDING ...' are displayed, RC is 16, the
  first 10 roots stay in the database, and no child is loaded.
- **TS-PAUDBLOD-08** (ERR-06): Given a child ISRT returns a status other than blank or 'II', then
  'INSERT CALL FAIL FOR CHILD:' + status and 'KFB AREA IN CHILD:' + key feedback are displayed and RC
  is 16; earlier inserts remain.
- **TS-PAUDBLOD-09** (Q-PAUDBLOD-01): Given the load module's entry point is DLITCBL, when LOADPADB runs,
  then the first root ISRT is issued against the I/O PCB, fails with a non-blank status, and the run
  ends with 'ROOT INSERT FAILED  :' + status and RC 16.
- **TS-PAUDBLOD-10** (Q-07): Given INFILE1 contains a record of 100 X'40' bytes, then it is inserted as a
  root with an invalid packed key and 'ROOT INSERT SUCCESS    ' is displayed.

## Open questions
- Which entry point is used at link-edit (program name or DLITCBL), and does the job work as shipped?
- Should orphan child records and invalid root keys be rejected with messages and counts?
- Is LOADPADB intended for initial loads only, or also for merging into a live database (BR-08)?
- Should loaded summaries be recomputed from their children?

## Target mapping proposal (non-normative)
With the tables proposed in `modernization/spec/programs/COPAUA0C.md`:
```sql
-- phase 1: roots (II = skip)
INSERT INTO pending_auth_summary (account_id, customer_id, ...)
VALUES (:account_id, :customer_id, ...)
ON CONFLICT (account_id) DO NOTHING;
-- phase 2: children; the FK replaces the GU (orphans raise an FK error instead of being dropped)
INSERT INTO pending_auth_detail (account_id, auth_key, auth_ts, ...)
VALUES (:parent_account_id, :auth_key, :auth_ts, ...)
ON CONFLICT (account_id, auth_key) DO NOTHING;
```
Commit in batches (for example every 1000 rows) instead of one unit of work. To preserve the
as-coded orphan behavior exactly, filter children with `WHERE EXISTS (SELECT 1 FROM
pending_auth_summary s WHERE s.account_id = :parent_account_id)` instead of relying on the FK.
Report counts of inserted, duplicate, orphan and invalid-key records.
