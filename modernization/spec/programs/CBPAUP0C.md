# CBPAUP0C: Batch purge of expired pending authorizations from the IMS database

| Attribute | Value |
|:--|:--|
| Source | `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl` (386 lines) |
| Module | app-authorization-ims-db2-mq |
| Kind | Batch (IMS BMP, EXEC DLI) |
| Transaction ID / Job | JOB CBPAUP0J (`app/app-authorization-ims-db2-mq/jcl/CBPAUP0J.jcl`), STEP01 `EXEC PGM=DFSRRC00,PARM='BMP,CBPAUP0C,PSBPAUTB'` |
| BMS map | none |
| Copybooks | CIPAUSMY (PAUTSUM0), CIPAUDTY (PAUTDTL1) |
| Calls / XCTL targets | EXEC DLI GN, GNP, DLET, CHKP (PCB 2 of PSBPAUTB; PCB 1 is the I/O PCB in a BMP) |
| Data stores | IMS DBPAUTP0 via PSB PSBPAUTB (PROCOPT=AP): PAUTSUM0 read sequentially (GN) and Delete; PAUTDTL1 read under parent (GNP) and Delete. SYSIN parameter card (ACCEPT). |

## Purpose
CBPAUP0C walks the whole pending-authorization database and deletes authorization details older
than a given number of days. While doing so it reduces the summary's approved or declined counts
and amounts in memory, and when no approved authorizations remain for an account it deletes the
account's summary segment (which also deletes any remaining details). It takes IMS checkpoints and
prints run totals.

## Invocation and state
- JCL CBPAUP0J STEP01 runs DFSRRC00 as a BMP with program CBPAUP0C and PSB PSBPAUTB. SYSIN card:
  `00,00001,00001,Y`.
- Parameter layout PRM-INFO, read with `ACCEPT PRM-INFO FROM SYSIN`
  (`app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:98-108`):

| Columns | Field | Picture | Meaning | Default when invalid |
|:--|:--|:--|:--|:--|
| 1-2 | P-EXPIRY-DAYS | 9(2) | Age in days at which a detail is purged | 5 when not numeric |
| 4-8 | P-CHKP-FREQ | X(5) | Summaries processed between checkpoints | '5' when spaces, zeros or low-values |
| 10-14 | P-CHKP-DIS-FREQ | X(5) | Checkpoints between "CHKP SUCCESS" messages | '10' when spaces, zeros or low-values |
| 16 | P-DEBUG-FLAG | X(1) | 'Y' prints DEBUG lines | anything but 'Y' becomes 'N' |

- PROCEDURE DIVISION USING IO-PCB-MASK, PGM-PCB-MASK; EXEC DLI uses PCB(2).
- Today's date: `ACCEPT CURRENT-DATE FROM DATE` (YYMMDD) and `ACCEPT CURRENT-YYDDD FROM DAY`
  (YYDDD).
- Return code: 0 on normal end (not set explicitly); 16 from 9999-ABEND, which ends with GOBACK
  (not an abend).
- Checkpoint id is always 'RMAD0000' (the counter is never incremented); there is no XRST, so the
  program cannot be restarted from a checkpoint.

## Processing flow
1. MAIN-PARA (136-180): 1000-INITIALIZE; 2000-FIND-NEXT-AUTH-SUMMARY (first root).
2. WHILE no error and not end of database:
   1. 3000-FIND-NEXT-AUTH-DTL (first child).
   2. WHILE more details: 4000-CHECK-IF-EXPIRED; IF qualified for delete, 5000-DELETE-AUTH-DTL;
      3000-FIND-NEXT-AUTH-DTL.
   3. IF PA-APPROVED-AUTH-CNT <= 0 (the condition is written twice): 6000-DELETE-AUTH-SUMMARY.
   4. IF the summaries-processed counter > P-CHKP-FREQ (compared as text, Q-CBPAUP0C-04):
      9000-TAKE-CHECKPOINT and reset the counter to 0.
   5. 2000-FIND-NEXT-AUTH-SUMMARY.
3. 9000-TAKE-CHECKPOINT (final); DISPLAY the totals; GOBACK.
4. 1000-INITIALIZE (183-210): accept dates and SYSIN; DISPLAY, in order, 'STARTING PROGRAM CBPAUP0C::',
   '*-------------------------------------*', 'CBPAUP0C PARM RECEIVED :' + PRM-INFO (the raw 17-byte
   card, before defaults), 'TODAYS DATE            :' + YYDDD, and ' ' (one space); apply the
   defaults in the parameter table.
5. 2000-FIND-NEXT-AUTH-SUMMARY (216-242): IF debug, DISPLAY 'DEBUG: AUTH SMRY READ : ' + count.
   DL/I "get next summary (root) segment in database order". Status blank: summaries read + 1,
   processed + 1, remember the account id. GB: end of database. OTHER: DISPLAY 'AUTH SUMMARY READ
   FAILED  :' + status and 'SUMMARY READ BEFORE ABEND :' + count; 9999-ABEND.
6. 3000-FIND-NEXT-AUTH-DTL (248-272): IF debug, DISPLAY 'DEBUG: AUTH DTL READ : ' + count. DL/I
   "get next detail under the current summary". Blank: more details, details read + 1. GE or GB: no
   more details. OTHER: DISPLAY 'AUTH DETAIL READ FAILED  :' + status, 'SUMMARY AUTH APP ID      :'
   + account, 'DETAIL READ BEFORE ABEND :' + count; 9999-ABEND.
7. 4000-CHECK-IF-EXPIRED (277-298): auth date = 99999 - PA-AUTH-DATE-9C (YYDDD); day difference =
   CURRENT-YYDDD - auth date. IF difference >= expiry days: qualified; IF response code '00',
   approved count - 1 and approved amount - PA-APPROVED-AMT; OTHERWISE declined count - 1 and
   declined amount - PA-TRANSACTION-AMT (in memory). OTHERWISE not qualified.
8. 5000-DELETE-AUTH-DTL (303-323): IF debug, DISPLAY 'DEBUG: AUTH DTL DLET : ' + account. DL/I
   "delete the detail just retrieved". Blank: details deleted + 1. OTHER: DISPLAY 'AUTH DETAIL
   DELETE FAILED :' + status, 'AUTH APP ID               :' + account; 9999-ABEND.
9. 6000-DELETE-AUTH-SUMMARY (328-347): IF debug, DISPLAY 'DEBUG: AUTH SMRY DLET : ' + account. DL/I
   "delete the summary segment" (IMS also deletes all its remaining children). Blank: summaries
   deleted + 1. OTHER: DISPLAY 'AUTH SUMMARY DELETE FAILED :' + status, 'AUTH APP ID                :'
   + account; 9999-ABEND.
10. 9000-TAKE-CHECKPOINT (352-372): `EXEC DLI CHKP ID('RMAD0000')`. Blank: checkpoints + 1; IF
    checkpoints >= P-CHKP-DIS-FREQ (text compare), reset to 0 and DISPLAY 'CHKP SUCCESS: AUTH COUNT -
    ' + summaries read + ', APP ID - ' + account. OTHER: DISPLAY 'CHKP FAILED: DIBSTAT - ' + status +
    ', REC COUNT - ' + summaries read + ', APP ID - ' + account; 9999-ABEND.
11. 9999-ABEND (377-383): DISPLAY 'CBPAUP0C ABENDING ...'; RETURN-CODE = 16; GOBACK.

Final totals printed (171-178):
```
 
*-------------------------------------*
# TOTAL SUMMARY READ  :<count>
# SUMMARY REC DELETED :<count>
# TOTAL DETAILS READ  :<count>
# DETAILS REC DELETED :<count>
*-------------------------------------*
 
```

## Rules
### Business rules
- **BR-CBPAUP0C-01**: Expiry days come from SYSIN columns 1-2 when numeric, else 5. Source:
  `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:196-200 (1000-INITIALIZE)`.
- **BR-CBPAUP0C-02**: Checkpoint frequency defaults to 5 and display frequency to 10 when blank, zero
  or low-values; the debug flag is 'Y' or else 'N'. Source:
  `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:201-209 (1000-INITIALIZE)`.
- **BR-CBPAUP0C-03**: Every summary in the database is visited in root key order (ascending
  account id), and every detail under it in key order (newest first). Source:
  `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:140-167 (MAIN-PARA)`.
- **BR-CBPAUP0C-04**: A detail's age in days = today's YYDDD - (99999 - PA-AUTH-DATE-9C), a plain
  numeric difference of YYDDD values. It is purged when age >= expiry days (so expiry 00 purges every
  detail dated today or earlier). Source:
  `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:280-285 (4000-CHECK-IF-EXPIRED)`.
- **BR-CBPAUP0C-05**: For each purged detail, the in-memory summary is adjusted: approved (response
  '00') -> approved count - 1 and approved amount - approved amount; otherwise declined count - 1 and
  declined amount - transaction amount. Credit balance and cash balance are not adjusted. Source:
  `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:287-293 (4000-CHECK-IF-EXPIRED)`.
- **BR-CBPAUP0C-06**: The summary is deleted when its in-memory approved count is zero or less after
  its details are processed, regardless of declined counts or remaining details. Otherwise the
  summary is left exactly as stored (it is never replaced). Source:
  `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:156-158 (MAIN-PARA)`,
  `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:328-347 (6000-DELETE-AUTH-SUMMARY)`.
- **BR-CBPAUP0C-07**: A checkpoint is requested after a summary when the processed counter is greater
  than P-CHKP-FREQ, and always once at the end. Source:
  `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:160-164,169 (MAIN-PARA)`.
- **BR-CBPAUP0C-08**: Run totals are DISPLAYed at the end: summaries read, summaries deleted,
  details read, details deleted. Source: `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:171-178 (MAIN-PARA)`.
- **BR-CBPAUP0C-09**: When P-DEBUG-FLAG = 'Y', DISPLAY before each summary GN 'DEBUG: AUTH SMRY READ : '
  + summaries read so far, before each detail GNP 'DEBUG: AUTH DTL READ : ' + details read so far,
  before each detail DLET 'DEBUG: AUTH DTL DLET : ' + PA-ACCT-ID, and before each summary DLET
  'DEBUG: AUTH SMRY DLET : ' + PA-ACCT-ID. Source:
  `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:219-221, 251-253, 306-308, 331-333`.
- **BR-CBPAUP0C-10**: After each successful CHKP the checkpoint counter is incremented; IF it is >=
  P-CHKP-DIS-FREQ (text comparison, Q-CBPAUP0C-04) it is reset to 0 and 'CHKP SUCCESS: AUTH COUNT - '
  + summaries read + ', APP ID - ' + WS-CURR-APP-ID is displayed. Source:
  `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:358-364 (9000-TAKE-CHECKPOINT)`.

### Validation rules
- **VR-CBPAUP0C-01**: P-EXPIRY-DAYS must be numeric; otherwise 5 is used silently. Source:
  `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:196-200 (1000-INITIALIZE)`.
- **VR-CBPAUP0C-02**: P-CHKP-FREQ and P-CHKP-DIS-FREQ are only checked for blank, zero or
  low-values; other non-numeric text is accepted. Source:
  `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:201-206 (1000-INITIALIZE)`.

### Navigation and keys
- Not applicable (batch).

### Error handling
- **ERR-CBPAUP0C-01**: Root GN status other than blank and GB -> 'AUTH SUMMARY READ FAILED  :' +
  status, 'SUMMARY READ BEFORE ABEND :' + summaries read, then ERR-CBPAUP0C-06 (RC 16). Source:
  `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:228-241`.
- **ERR-CBPAUP0C-02**: Detail GNP status other than blank, GE, GB -> 'AUTH DETAIL READ FAILED  :' +
  status, 'SUMMARY AUTH APP ID      :' + PA-ACCT-ID, 'DETAIL READ BEFORE ABEND :' + details read,
  then ERR-CBPAUP0C-06 (RC 16). Source: `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:259-271`.
- **ERR-CBPAUP0C-03**: Detail DLET non-blank status -> 'AUTH DETAIL DELETE FAILED :' + status,
  'AUTH APP ID               :' + PA-ACCT-ID, then ERR-CBPAUP0C-06 (RC 16). Source:
  `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:315-321`.
- **ERR-CBPAUP0C-04**: Summary DLET non-blank status -> 'AUTH SUMMARY DELETE FAILED :' + status,
  'AUTH APP ID                :' + PA-ACCT-ID, then ERR-CBPAUP0C-06 (RC 16). Source:
  `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:340-346`.
- **ERR-CBPAUP0C-05**: CHKP non-blank status -> 'CHKP FAILED: DIBSTAT - ' + status + ', REC COUNT - '
  + count + ', APP ID - ' + account, RC 16. Source:
  `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:358-370`.
- **ERR-CBPAUP0C-06**: 9999-ABEND sets RC 16 and returns normally (GOBACK); it does not abend or
  roll back. Source: `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:377-383`.

## Data effects
- PAUTDTL1 segments with age >= expiry days are deleted, one DLET each.
- PAUTSUM0 segments whose approved count (after in-memory adjustment) is <= 0 are deleted; IMS
  cascades the delete to every remaining PAUTDTL1 under them (unexpired and declined ones included).
- PAUTSUM0 segments that are kept are not modified: their counts, amounts and balances still include
  the purged details.
- Commit points: each CHKP, and the end of the BMP. Because 9999-ABEND ends with GOBACK (normal
  termination), deletes made before an error are committed.

## Quirks
- **Q-CBPAUP0C-01**: The summary is never replaced, so the count and amount adjustments in
  4000-CHECK-IF-EXPIRED are lost; kept summaries overstate approved and declined counts and amounts,
  and PA-CREDIT-BALANCE is never reduced, so purging does not restore available credit in COPAUA0C.
  The module README describes the purge as adjusting available credit. Recommendation: decide.
  Source: `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:277-298`.
- **Q-CBPAUP0C-02**: The delete condition tests PA-APPROVED-AUTH-CNT twice
  (`PA-APPROVED-AUTH-CNT <= 0 AND PA-APPROVED-AUTH-CNT <= 0`); the declined count is probably meant
  for the second test. As written, an account with no approved authorizations is deleted together
  with all of its declined details, even unexpired ones. Same finding as Q-ENT-42 in `entities.md`
  (an IMS DLET of the root also deletes every remaining child segment). Recommendation: fix. Source:
  `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:156`.
- **Q-CBPAUP0C-03**: Age is the difference of YYDDD numbers. Across a year end it is far too large
  (24001 - 23365 = 636 for a one-day-old item, so it is purged early); across a century end it is
  negative and the item is never purged. See also Q-ENT-27 (2-digit-year authorization keys).
  Recommendation: fix.
- **Q-CBPAUP0C-04**: The checkpoint test compares a PIC 9(8) counter with the PIC X(5) parameter, which
  COBOL performs as a text comparison of "0000nnnn" with "00001   ". With the JCL value 00001 the
  checkpoint is taken only after 1000 summaries; with the default '5' (stored as "5    ") it is never
  reached, leaving only the final checkpoint. The display-frequency test has the same problem, so
  'CHKP SUCCESS' is printed only after 1000 checkpoints. Recommendation: fix.
- **Q-CBPAUP0C-05**: The summary DLET follows a GNP that returned GE. Under DL/I rules, a delete must
  follow the retrieval of the segment with no intervening call on the PCB, so this DLET may be
  rejected (status DJ) and the run would end with RC 16 at the first summary to delete. This needs
  verification on IMS. Recommendation: decide (verify, then fix by re-reading the root with GU before
  DLET). Source: `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:153-158,335-338`.
- **Q-CBPAUP0C-06**: After CHKP the program continues with an unqualified GN. IMS documentation
  states that database position for full-function databases is not kept across a checkpoint; if so,
  the next GN restarts from the first root and summaries are processed again. This needs
  verification. Recommendation: decide.
- **Q-CBPAUP0C-07**: The checkpoint id never changes ('RMAD0000') and there is no XRST, so a failed
  run cannot be restarted from its last checkpoint; it is simply rerun. Recommendation: preserve or
  decide.
- **Q-CBPAUP0C-08**: 9999-ABEND ends normally with RC 16 instead of abending; IMS commits the
  deletes done so far rather than backing them out. Recommendation: decide.
- **Q-CBPAUP0C-09**: The JCL passes expiry 00, which purges every detail on every run, and debug Y,
  which prints a line per read and delete. Recommendation: decide (operational setting).
- **Q-CBPAUP0C-10**: The purge age uses the detail key date (CICS insert date in COPAUA0C), not the
  request's original date. Recommendation: preserve.

## Test scenarios
Sample IMS data (DFSURGU0 unload `app/app-authorization-ims-db2-mq/data/EBCDIC/AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat`):
21 account summaries (accounts 1, 5, 7, 13, 15, 16, 17, 18, 23, 29, 30, 31, 32, 33, 34, 38, 42, 45,
46, 47, 48), 202 details, all approved ('00') with match status 'P', dated YYDDD 23292 to 24096
(2023-10-19 to 2024-04-05; key dates 76707 to 75903). (Correction: the range was given as 23299 to
23320; the unload's detail keys and PA-AUTH-ORIG-DATE values span 2023-10-19 to 2024-04-05.)
Account 7 has 50 approved details. The unload also holds one
unusable root whose key and amounts are EBCDIC spaces (X'40' in every byte except 1-based bytes 7-22, which are X'F0';
correction: this said "bytes 6-21", the 0-based offsets): its key is not valid packed decimal and its
approved count reads as 16448 (X'4040'); it has no details.
- **TS-CBPAUP0C-01** (BR-04 to BR-08): Given the sample database and SYSIN `00,00001,00001,Y` run in
  2025, when CBPAUP0J runs, then every detail is deleted, every valid summary's approved count reaches 0 so
  those 21 summaries are deleted (subject to Q-CBPAUP0C-05), the all-spaces root is kept (approved
  count 16448 > 0), and the totals show summaries read 22, summaries deleted 21, details read 202,
  details deleted 202, RC 0. (Target note: the migration rejects the all-spaces root because its key
  is not valid packed decimal, so the target run reads 21 summaries: totals 21, 21, 202, 202.)
- **TS-CBPAUP0C-02** (BR-04): Given today is 23325 and SYSIN `05,00001,00001,N`, when the job runs,
  then details dated 23320 (age 5) are deleted and details dated 23321 or later would be kept.
- **TS-CBPAUP0C-03** (Q-CBPAUP0C-01): Given account 7 has 50 approved details of which 10 are expired,
  when the job runs, then 10 details are deleted and the stored summary still shows approved count
  50, approved amount 74.79 and credit balance 74.79. (Target note: under the ledger FIX for
  Q-CBPAUP0C-01 the kept summary is saved with the purged approvals taken out: approved count 40,
  approved amount 74.79 less the 10 purged amounts, credit balance 74.79 unchanged.)
- **TS-CBPAUP0C-04** (Q-CBPAUP0C-02): Given an account whose summary has approved count 0 and three
  unexpired declined details, when the job runs, then the summary and all three details are deleted.
- **TS-CBPAUP0C-05** (VR-01, BR-02): Given SYSIN `XX,     ,     ,N`, then expiry 5, checkpoint
  frequency '5', display frequency '10', debug off, and 'CBPAUP0C PARM RECEIVED :XX,     ,     ,N'
  is displayed.
- **TS-CBPAUP0C-06** (Q-CBPAUP0C-03): Given a detail inserted on 2023-12-31 (23365) and today
  2024-01-01 (24001) with expiry 5, then the detail is deleted (age computed as 636).
- **TS-CBPAUP0C-07** (ERR-03, ERR-06): Given a DLET returns a non-blank status, then 'AUTH DETAIL
  DELETE FAILED :' + status and 'CBPAUP0C ABENDING ...' are displayed and the step ends with RC 16;
  deletes before the failure remain.
- **TS-CBPAUP0C-08** (Q-CBPAUP0C-04): Given SYSIN checkpoint frequency 00001 and 21 summaries, then no
  intermediate checkpoint is taken and only the final CHKP is issued.

## Open questions
- Should the summary be replaced with the adjusted counts and should PA-CREDIT-BALANCE be reduced
  by purged approvals (README: "adjustment of available credit")?
- Was the second condition meant to be PA-DECLINED-AUTH-CNT <= 0?
- Which expiry value is intended for production (the JCL uses 00)?
- Does CBPAUP0C run in the CA7/Control-M schedules? (Not determined from this module.)

## Target mapping proposal (non-normative)
With the tables proposed in `modernization/spec/programs/COPAUA0C.md`:
```sql
-- purge expired details
DELETE FROM pending_auth_detail
 WHERE auth_ts < CURRENT_DATE - (:expiry_days * INTERVAL '1 day');
-- recompute summaries from remaining details (if the team decides to fix Q-CBPAUP0C-01)
UPDATE pending_auth_summary s SET
  approved_count  = (SELECT COUNT(*) FROM pending_auth_detail d WHERE d.account_id = s.account_id AND d.resp_code = '00'),
  approved_amount = (SELECT COALESCE(SUM(approved_amt),0) FROM pending_auth_detail d WHERE d.account_id = s.account_id AND d.resp_code = '00'),
  declined_count  = (SELECT COUNT(*) FROM pending_auth_detail d WHERE d.account_id = s.account_id AND d.resp_code <> '00'),
  declined_amount = (SELECT COALESCE(SUM(transaction_amt),0) FROM pending_auth_detail d WHERE d.account_id = s.account_id AND d.resp_code <> '00');
-- remove empty summaries (as-coded rule: approved_count <= 0)
DELETE FROM pending_auth_summary WHERE approved_count <= 0;
```
Run in batches with periodic commits (replacing CHKP), using real dates so the year-end problem
disappears. Preserving the as-coded behavior exactly would instead mean: delete expired details,
leave kept summaries unchanged, and delete summaries whose stored approved count minus the number of
purged approved details is <= 0 (with ON DELETE CASCADE).
