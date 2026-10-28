# COPAUS2C: Record a fraud report or removal for an authorization in the Db2 table AUTHFRDS

| Attribute | Value |
|:--|:--|
| Source | `app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl` (244 lines) |
| Module | app-authorization-ims-db2-mq |
| Kind | Subroutine (CICS program invoked by LINK; Db2) |
| Transaction ID / Job | Runs under CPVD (caller's task). The CSD defines PROGRAM(COPAUS2C) with TRANSID(CPVD) (`app/app-authorization-ims-db2-mq/csd/CRDDEMO2.csd:32-36`); Db2 access through DB2TRAN CPVDTRAN -> DB2ENTRY AWS01PLN, PLAN(AWS01PLN) (lines 69-77) |
| BMS map | none |
| Copybooks | CIPAUDTY (in the LINKAGE COMMAREA); SQL INCLUDE SQLCA; SQL INCLUDE AUTHFRDS (DCLGEN `app/app-authorization-ims-db2-mq/dcl/AUTHFRDS.dcl`) |
| Calls / XCTL targets | none (returns to the LINKing program, COPAUS1C) |
| Data stores | Db2 CARDDEMO.AUTHFRDS (`app/app-authorization-ims-db2-mq/ddl/AUTHFRDS.ddl`, primary key CARD_NUM, AUTH_TS; unique index XAUTHFRD CARD_NUM ASC, AUTH_TS DESC in `app/app-authorization-ims-db2-mq/ddl/XAUTHFRD.ddl`): Insert; on duplicate, Update by primary key |

## Purpose
COPAUS2C keeps a Db2 record of authorizations that a user has reported as fraud, or whose fraud
report was removed. COPAUS1C passes the full authorization detail and the action ('F' report or
'R' remove). COPAUS2C stamps today's date into the detail's fraud report date, inserts a row with
all authorization fields into CARDDEMO.AUTHFRDS, or, if a row for the same card and timestamp
already exists, updates its fraud flag and report date. It returns a success or failure status and
a message. It does not commit; the caller commits or rolls back.

## Invocation and state
- Invoked by `EXEC CICS LINK PROGRAM('COPAUS2C') COMMAREA(WS-FRAUD-DATA)` from COPAUS1C
  MARK-AUTH-FRAUD (`app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:248-252`).
- DFHCOMMAREA (`app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl:74-86`), 272 bytes:

| Field | Picture | Direction | Meaning |
|:--|:--|:--|:--|
| WS-ACCT-ID | 9(11) | In | Account id |
| WS-CUST-ID | 9(9) | In | Customer id |
| WS-FRAUD-AUTH-RECORD | CIPAUDTY, 200 bytes | In/Out | PAUTDTL1 image; PA-FRAUD-RPT-DATE is set on output |
| WS-FRD-ACTION | X(1) | In | 'F' report fraud, 'R' remove fraud |
| WS-FRD-UPDATE-STATUS | X(1) | Out | 'S' success, 'F' failed |
| WS-FRD-ACT-MSG | X(50) | Out | Result message |

- No state is kept between calls. The program ends with `EXEC CICS RETURN`.

### AUTHFRDS columns and their sources
| Column | Type | Source |
|:--|:--|:--|
| CARD_NUM | CHAR(16) NOT NULL | PA-CARD-NUM |
| AUTH_TS | TIMESTAMP NOT NULL | built from PA-AUTH-ORIG-DATE and PA-AUTH-TIME-9C (BR-COPAUS2C-02) |
| AUTH_TYPE | CHAR(4) | PA-AUTH-TYPE |
| CARD_EXPIRY_DATE | CHAR(4) | PA-CARD-EXPIRY-DATE |
| MESSAGE_TYPE | CHAR(6) | PA-MESSAGE-TYPE |
| MESSAGE_SOURCE | CHAR(6) | PA-MESSAGE-SOURCE |
| AUTH_ID_CODE | CHAR(6) | PA-AUTH-ID-CODE |
| AUTH_RESP_CODE | CHAR(2) | PA-AUTH-RESP-CODE |
| AUTH_RESP_REASON | CHAR(4) | PA-AUTH-RESP-REASON |
| PROCESSING_CODE | CHAR(6) | PA-PROCESSING-CODE |
| TRANSACTION_AMT | DECIMAL(12,2) | PA-TRANSACTION-AMT |
| APPROVED_AMT | DECIMAL(12,2) | PA-APPROVED-AMT |
| MERCHANT_CATAGORY_CODE | CHAR(4) | PA-MERCHANT-CATAGORY-CODE |
| ACQR_COUNTRY_CODE | CHAR(3) | PA-ACQR-COUNTRY-CODE |
| POS_ENTRY_MODE | SMALLINT | PA-POS-ENTRY-MODE |
| MERCHANT_ID | CHAR(15) | PA-MERCHANT-ID |
| MERCHANT_NAME | VARCHAR(22) | PA-MERCHANT-NAME, length always 22 |
| MERCHANT_CITY | CHAR(13) | PA-MERCHANT-CITY |
| MERCHANT_STATE | CHAR(2) | PA-MERCHANT-STATE |
| MERCHANT_ZIP | CHAR(9) | PA-MERCHANT-ZIP |
| TRANSACTION_ID | CHAR(15) | PA-TRANSACTION-ID |
| MATCH_STATUS | CHAR(1) | PA-MATCH-STATUS |
| AUTH_FRAUD | CHAR(1) | WS-FRD-ACTION |
| FRAUD_RPT_DATE | DATE | CURRENT DATE (Db2 server date) |
| ACCT_ID | DECIMAL(11) | WS-ACCT-ID |
| CUST_ID | DECIMAL(9) | WS-CUST-ID |

## Processing flow
1. MAIN-PARA (89-220):
   1. ASKTIME and FORMATTIME MMDDYY with DATESEP (NOHANDLE) give today's date "MM/DD/YY";
      move it to PA-FRAUD-RPT-DATE in the COMMAREA record.
   2. Build WS-AUTH-TS "YY-MM-DD HH.MI.SSmmm000": YY, MM, DD from PA-AUTH-ORIG-DATE (YYMMDD);
      HH, MI, SS, mmm from WS-AUTH-TIME = 999999999 - PA-AUTH-TIME-9C (digits 1-2, 3-4, 5-6, 7-9).
   3. Move all detail fields, the action, account and customer to the DCLGEN host variables;
      MERCHANT-NAME length = 22.
   4. INSERT INTO CARDDEMO.AUTHFRDS with AUTH_TS = TIMESTAMP_FORMAT(:AUTH-TS,
      'YY-MM-DD HH24.MI.SSNNNNNN') and FRAUD_RPT_DATE = CURRENT DATE.
   5. IF SQLCODE = 0: status 'S', message "ADD SUCCESS".
      OTHERWISE IF SQLCODE = -803 (duplicate key): FRAUD-UPDATE.
      OTHERWISE: status 'F', message " SYSTEM ERROR DB2: CODE:" + SQLCODE + ", STATE: " + SQLSTATE.
   6. `EXEC CICS RETURN`.
2. FRAUD-UPDATE (221-244): UPDATE CARDDEMO.AUTHFRDS SET AUTH_FRAUD = action, FRAUD_RPT_DATE =
   CURRENT DATE WHERE CARD_NUM = card AND AUTH_TS = the same TIMESTAMP_FORMAT value. IF SQLCODE = 0:
   status 'S', message "UPDT SUCCESS". OTHERWISE status 'F', message " UPDT ERROR DB2: CODE:" +
   SQLCODE + ", STATE: " + SQLSTATE.

## Rules
### Business rules
- **BR-COPAUS2C-01**: The fraud report date returned to the caller is today's CICS date as
  'MM/DD/YY'. Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl:91-101 (MAIN-PARA)`.
- **BR-COPAUS2C-02**: The Db2 authorization timestamp is the original request date (YYMMDD from
  the request) combined with the time of day recovered from the IMS key (999999999 -
  PA-AUTH-TIME-9C = CICS insert time HHMMSSmmm), with microseconds = milliseconds x 1000, parsed with
  'YY-MM-DD HH24.MI.SSNNNNNN'. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl:103-114,171-172 (MAIN-PARA)`.
- **BR-COPAUS2C-03**: A new row stores every authorization field, the action as AUTH_FRAUD, the Db2
  CURRENT DATE as FRAUD_RPT_DATE, and the account and customer ids. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl:113-198 (MAIN-PARA)`.
- **BR-COPAUS2C-04**: Insert succeeds -> status 'S', "ADD SUCCESS". Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl:199-201 (MAIN-PARA)`.
- **BR-COPAUS2C-05**: When the row already exists (SQLCODE -803), only AUTH_FRAUD and
  FRAUD_RPT_DATE are updated; the other columns keep their first-insert values. Update succeeds ->
  status 'S', "UPDT SUCCESS". Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl:203-204,221-232 (MAIN-PARA, FRAUD-UPDATE)`.
- **BR-COPAUS2C-06**: A removal ('R') for an authorization that was never reported inserts a new
  row with AUTH_FRAUD 'R'. Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl:137,141-198`.
- **BR-COPAUS2C-07**: The program never commits or rolls back; COPAUS1C does. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl:218-219 (MAIN-PARA)`.

### Validation rules
- None. The input record and action are not checked.

### Navigation and keys
- **NAV-COPAUS2C-01**: Returns to the caller with `EXEC CICS RETURN`; no screen. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl:218-219 (MAIN-PARA)`.

### Error handling
- **ERR-COPAUS2C-01**: Insert SQLCODE other than 0 and -803 -> status 'F', message " SYSTEM ERROR
  DB2: CODE:" + SQLCODE edited +9(06) + ", STATE: " + SQLSTATE edited +9(09). Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl:205-214 (MAIN-PARA)`.
- **ERR-COPAUS2C-02**: Update SQLCODE other than 0 (including +100, row not found) -> status 'F',
  message " UPDT ERROR DB2: CODE:" + SQLCODE + ", STATE: " + SQLSTATE. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl:233-242 (FRAUD-UPDATE)`.
- **ERR-COPAUS2C-03**: ASKTIME and FORMATTIME use NOHANDLE; a failure leaves the date blank
  without an error. Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl:91-100 (MAIN-PARA)`.

## Data effects
One row of CARDDEMO.AUTHFRDS inserted, or the existing row's AUTH_FRAUD and FRAUD_RPT_DATE
updated. The COMMAREA record's PA-FRAUD-RPT-DATE is set to today's 'MM/DD/YY' in every case,
including failures. Changes are uncommitted until the caller's SYNCPOINT.

## Quirks
- **Q-COPAUS2C-01**: AUTH_TS mixes two clocks: the date is the requester's original date
  (PA-AUTH-ORIG-DATE) and the time is the CICS insert time from the IMS key. In the sample data the
  request time is 14:17:49 while the key time is 09:17:49.523, so the stored timestamp
  (2023-11-16 09:17:49.523) matches neither the request instant nor the insert instant, and a
  request near midnight can get a timestamp up to a day off. Recommendation: decide.
- **Q-COPAUS2C-02**: The 2-digit year is resolved by Db2's 'YY' rule (current century), not by a
  defined window. Recommendation: preserve for current data, decide for the target.
- **Q-COPAUS2C-03**: Two different authorizations of the same card with the same orig date and the
  same inserted time-of-day (from different days or accounts) would map to the same primary key,
  and the second report would update the first's row. Recommendation: decide.
- **Q-COPAUS2C-04**: SQLSTATE (5 characters, may contain letters) is moved to a numeric-edited
  field +9(09), so letters do not display correctly. Recommendation: fix.
- **Q-COPAUS2C-05**: The success messages "ADD SUCCESS" and "UPDT SUCCESS" are never shown;
  COPAUS1C replaces them with its own messages. Recommendation: preserve.
- **Q-COPAUS2C-06**: MERCHANT_NAME is always stored as a 22-character VARCHAR including trailing
  spaces. Recommendation: fix (trim) or preserve.
- **Q-COPAUS2C-07**: The schema name CARDDEMO is hard-coded in the SQL. Recommendation: preserve.

## Test scenarios
Sample detail: account 7, card 4859452612877065, PA-AUTH-ORIG-DATE 231116, PA-AUTH-TIME-9C
908250476 (time 09:17:49.523), customer 7.
- **TS-COPAUS2C-01** (BR-01 to BR-04): Given no AUTHFRDS row for card 4859452612877065, when
  COPAUS2C is linked with action 'F' on 2024-03-05, then a row is inserted with CARD_NUM
  4859452612877065, AUTH_TS 2023-11-16-09.17.49.523000, AUTH_FRAUD 'F', FRAUD_RPT_DATE 2024-03-05,
  ACCT_ID 7, CUST_ID 7; the COMMAREA returns status 'S', message "ADD SUCCESS", and
  PA-FRAUD-RPT-DATE "03/05/24".
- **TS-COPAUS2C-02** (BR-05): Given the row from TS-COPAUS2C-01, when linked with action 'R' on
  2024-03-06, then AUTH_FRAUD becomes 'R', FRAUD_RPT_DATE 2024-03-06, status 'S', "UPDT SUCCESS".
- **TS-COPAUS2C-03** (BR-06): Given no row, when linked with action 'R', then a row with AUTH_FRAUD
  'R' is inserted.
- **TS-COPAUS2C-04** (ERR-01): Given the table is unavailable (SQLCODE -904), when linked, then
  status 'F' and the message starts " SYSTEM ERROR DB2: CODE:-000904, STATE: ".
- **TS-COPAUS2C-05** (BR-07): Given COPAUS1C issues SYNCPOINT ROLLBACK after the call, then the
  inserted row is not present.

## Open questions
- Which instant should AUTH_TS represent (request time or insert time), and in which time zone?
- Should the Db2 table keep history (one row per action) rather than the latest action only?
- The module README names the Db2 plan DB201PLN while the CSD uses AWS01PLN; which is correct?

## Target mapping proposal (non-normative)
- Keep AUTHFRDS as a relational table `auth_fraud` with the same columns, primary key
  (card_num, auth_ts), and a descending index on auth_ts; add `account_id` foreign key to the
  pending_auth_summary table proposed in `modernization/spec/programs/COPAUA0C.md` if desired.
- INSERT then UPDATE on -803 becomes one UPSERT:
  `INSERT ... ON CONFLICT (card_num, auth_ts) DO UPDATE SET auth_fraud = EXCLUDED.auth_fraud,
  fraud_rpt_date = CURRENT_DATE`.
- If the pending detail table carries a real `auth_ts`, reference it directly
  (`pending_auth_detail.account_id, auth_key`) instead of re-deriving the timestamp, which removes
  Q-COPAUS2C-01 and Q-COPAUS2C-03.
- As a service: an internal function called inside the same database transaction as the detail
  update (no separate network call), returning `{status, message, reportDate}`.
