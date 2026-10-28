# COPAUS1C: Show one pending authorization in detail and let the user mark or unmark it as fraud

| Attribute | Value |
|:--|:--|
| Source | `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl` (604 lines) |
| Module | app-authorization-ims-db2-mq |
| Kind | Online (CICS), pseudo-conversational |
| Transaction ID / Job | CPVD (`app/app-authorization-ims-db2-mq/csd/CRDDEMO2.csd:25-29,39-40`, PROFILE DFHCICST; DB2TRAN CPVDTRAN maps CPVD to DB2ENTRY AWS01PLN, lines 69-77) |
| BMS map | mapset COPAU01, map COPAU1A (`app/app-authorization-ims-db2-mq/bms/COPAU01.bms`, symbolic map `app/app-authorization-ims-db2-mq/cpy-bms/COPAU01.cpy`, COPAU1AI / COPAU1AO) |
| Copybooks | COCOM01Y (+ CDEMO-CPVD-INFO extension), COPAU01, COTTL01Y, CSDAT01Y, CSMSG01Y, CSMSG02Y, CIPAUSMY, CIPAUDTY, DFHAID, DFHBMSCA |
| Calls / XCTL targets | LINK COPAUS2C (fraud record in Db2); XCTL COPAUS0C (PF3, or EIBCALEN = 0). EXEC DLI SCHD / TERM / GU / GNP / REPL; EXEC CICS SYNCPOINT and SYNCPOINT ROLLBACK. |
| Data stores | IMS DBPAUTP0 through PSB PSBPAUTB, PCB 1: PAUTSUM0 GU by ACCNTID; PAUTDTL1 GNP qualified by PAUT9CTS, GNP unqualified (next), REPL (fraud flag and date). Db2 CARDDEMO.AUTHFRDS indirectly through COPAUS2C (Insert or Update). |

## Purpose
COPAUS1C is the "View Authorization Details" screen. It shows every field of the authorization
selected on COPAUS0C: card, date and time, response and reason, amounts, POS data, card expiry,
match and fraud status, and merchant details. PF8 moves to the next (older) authorization of the
same account. PF5 toggles the fraud flag: it records or updates a fraud report row in Db2 through
COPAUS2C and, if that succeeds, rewrites the IMS detail with the new flag and report date, then
commits both.

## Invocation and state
- Entered by XCTL from COPAUS0C with CDEMO-PGM-CONTEXT = 0 (enter), CDEMO-ACCT-ID = account,
  CDEMO-CUST-ID = customer (from COPAUS0C's xref read) and CDEMO-CPVD-PAU-SELECTED = selected
  detail key. COPAUS1C's extension CDEMO-CPVD-INFO (`app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:110-120`)
  overlays COPAUS0C's CDEMO-CPVS-INFO byte for byte, so CDEMO-CPVD-PAU-SELECTED receives
  CDEMO-CPVS-PAU-SELECTED. COPAUS1C adds CDEMO-CPVD-FRAUD-DATA X(100), which it clears on every
  turn and never uses otherwise.
- EIBCALEN = 0: the COMMAREA is initialized and the program XCTLs to COPAUS0C.
- Each turn ends with `EXEC CICS RETURN TRANSID('CPVD') COMMAREA(CARDDEMO-COMMAREA)`. The only
  state carried is the COMMAREA; CDEMO-CPVD-PAU-SELECTED is updated by PF8 and PF5.
- LINK COMMAREA to COPAUS2C (WS-FRAUD-DATA, 272 bytes,
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:93-104`): WS-FRD-ACCT-ID 9(11), WS-FRD-CUST-ID
  9(9), WS-FRAUD-AUTH-RECORD X(200) (a PAUTDTL1 image), WS-FRD-ACTION X(1) ('F' report, 'R'
  remove), WS-FRD-UPDATE-STATUS X(1) ('S' success, 'F' failed), WS-FRD-ACT-MSG X(50).

## Screen
```
Tran: CPVD          AWS Mainframe Modernization (TITLE01)        Date: mm/dd/yy
Prog: COPAUS1C      CardDemo (TITLE02)                           Time: hh:mm:ss

                          View Authorization Details


 Card #: 9999999999999999    Auth Date: mm/dd/yy      Auth Time: hh:mm:ss

 Auth Resp: A   Resp Reason: 0000-APPROVED          Auth Code: 999999

 Amount: -zzzzzzz9.99   POS Entry Mode: 99           Source   : xxxxxx

 MCC Code: 9999      Card Exp. Date: mm/yy        Auth Type: xxxx

 Tran Id: xxxxxxxxxxxxxxx   Match Status: P     Fraud Status: F-mm/dd/yy

 Merchant Details -----------------------------------------------------------

 Name: xxxxxxxxxxxxxxxxxxxxxx         Merchant ID: xxxxxxxxxxxxxxx

 City: xxxxxxxxxxxxx                  State: xx   Zip: xxxxxxxxx

<ERRMSG, red, 78>
 F3=Back  F5=Mark/Remove Fraud  F8=Next Auth
```

| Field | BMS name | Len | In/Out | Attributes | Meaning |
|:--|:--|:--|:--|:--|:--|
| Transaction | TRNNAME | 4 | Out | (1,7) ASKIP blue | 'CPVD' |
| Title 1 / 2 | TITLE01 / TITLE02 | 40 | Out | (1,21) / (2,21) ASKIP yellow | CCDA-TITLE01 / CCDA-TITLE02 |
| Date / Time | CURDATE / CURTIME | 8 | Out | (1,71) / (2,71) ASKIP blue | Current MM/DD/YY and HH:MM:SS |
| Program | PGMNAME | 8 | Out | (2,7) ASKIP blue | 'COPAUS1C' |
| Card # | CARDNUM | 16 | Out | (7,11) ASKIP pink; cursor placed here | PA-CARD-NUM |
| Auth Date | AUTHDT | 10 | Out | (7,43) ASKIP pink | PA-AUTH-ORIG-DATE YYMMDD shown MM/DD/YY |
| Auth Time | AUTHTM | 10 | Out | (7,68) ASKIP pink | PA-AUTH-ORIG-TIME HHMMSS shown HH:MM:SS |
| Auth Resp | AUTHRSP | 1 | Out | (9,14) ASKIP; color set green for 'A', red for 'D' | 'A' when response code '00', else 'D' |
| Resp Reason | AUTHRSN | 20 | Out | (9,32) ASKIP blue | reason code + '-' + description (15 characters) |
| Auth Code | AUTHCD | 6 | Out | (9,68) ASKIP blue | PA-PROCESSING-CODE (not the auth id code) |
| Amount | AUTHAMT | 12 | Out | (11,11) ASKIP blue | PA-APPROVED-AMT, -zzzzzzz9.99 |
| POS Entry Mode | POSEMD | 4 | Out | (11,46) ASKIP blue | PA-POS-ENTRY-MODE |
| Source | AUTHSRC | 10 | Out | (11,68) ASKIP blue | PA-MESSAGE-SOURCE |
| MCC Code | MCCCD | 4 | Out | (13,13) ASKIP blue | PA-MERCHANT-CATAGORY-CODE |
| Card Exp. Date | CRDEXP | 5 | Out | (13,42) ASKIP blue | PA-CARD-EXPIRY-DATE MMYY shown MM/YY |
| Auth Type | AUTHTYP | 14 | Out | (13,64) ASKIP blue | PA-AUTH-TYPE |
| Tran Id | TRNID | 15 | Out | (15,12) ASKIP blue | PA-TRANSACTION-ID |
| Match Status | AUTHMTC | 1 | Out | (15,46) ASKIP red | PA-MATCH-STATUS |
| Fraud Status | AUTHFRD | 10 | Out | (15,67) ASKIP red | 'F-' or 'R-' + PA-FRAUD-RPT-DATE, or '-' |
| Name | MERNAME | 25 | Out | (19,9) ASKIP blue | PA-MERCHANT-NAME |
| Merchant ID | MERID | 15 | Out | (19,55) ASKIP blue | PA-MERCHANT-ID |
| City | MERCITY | 25 | Out | (21,9) ASKIP blue | PA-MERCHANT-CITY |
| State | MERST | 2 | Out | (21,49) ASKIP blue | PA-MERCHANT-STATE |
| Zip | MERZIP | 10 | Out | (21,61) ASKIP blue | PA-MERCHANT-ZIP |
| Message | ERRMSG | 78 | Out | (23,1) ASKIP BRT FSET red | WS-MESSAGE |

The map has no input fields; only the attention key matters.

## Processing flow
1. MAIN-PARA (157-206): error flag off, erase = yes, message blank.
   1. IF EIBCALEN = 0: initialize the COMMAREA, TO-PROGRAM = COPAUS0C, RETURN-TO-PREV-SCREEN (XCTL).
   2. OTHERWISE copy the COMMAREA and clear CDEMO-CPVD-FRAUD-DATA.
      - IF not reenter: set reenter; PROCESS-ENTER-KEY; SEND-AUTHVIEW-SCREEN.
      - OTHERWISE RECEIVE the map (NOHANDLE) and evaluate the key:
        - ENTER: PROCESS-ENTER-KEY; send.
        - PF3: TO-PROGRAM = COPAUS0C; RETURN-TO-PREV-SCREEN.
        - PF5: MARK-AUTH-FRAUD; send.
        - PF8: PROCESS-PF8-KEY; send.
        - OTHER: PROCESS-ENTER-KEY; message CCDA-MSG-INVALID-KEY; send.
   3. RETURN TRANSID CPVD with the COMMAREA.
2. PROCESS-ENTER-KEY (208-228): clear the output map. IF CDEMO-ACCT-ID is numeric AND
   CDEMO-CPVD-PAU-SELECTED is not spaces or low-values: READ-AUTH-RECORD for that account and
   key; IF the PSB is scheduled, mark it not scheduled and SYNCPOINT. OTHERWISE set the error flag
   (no message). Then POPULATE-AUTH-DETAILS.
3. READ-AUTH-RECORD (431-491): SCHEDULE-PSB; DL/I "get unique the summary whose ACCNTID equals the
   account". Blank/FW: found. GE or GB: EOF. OTHER: error flag, message, send. IF found: DL/I "get
   next detail under this summary whose PAUT9CTS equals the key" with the same outcome handling.
4. READ-NEXT-AUTH-RECORD (493-518): DL/I "get next detail under the current summary" (the one
   after the current position). Blank/FW: found; GE/GB: EOF; OTHER: error flag, message, send.
5. PROCESS-PF8-KEY (268-289): READ-AUTH-RECORD (current), READ-NEXT-AUTH-RECORD; IF the PSB is
   scheduled, SYNCPOINT. IF EOF: erase = no, message "Already at the last Authorization...".
   OTHERWISE CDEMO-CPVD-PAU-SELECTED = the new key and POPULATE-AUTH-DETAILS.
6. MARK-AUTH-FRAUD (230-266):
   1. READ-AUTH-RECORD for CDEMO-ACCT-ID and CDEMO-CPVD-PAU-SELECTED.
   2. IF the detail's fraud flag is 'F' (confirmed): set it to 'R' (removed) and action = 'R'.
      OTHERWISE (blank or 'R'): set it to 'F' and action = 'F'.
   3. Copy the detail (with the new flag) into WS-FRAUD-AUTH-RECORD, account = CDEMO-ACCT-ID,
      customer = CDEMO-CUST-ID. LINK COPAUS2C with WS-FRAUD-DATA (NOHANDLE).
   4. IF the LINK RESP is NORMAL: IF COPAUS2C returned status 'S', UPDATE-AUTH-DETAILS; OTHERWISE
      message = COPAUS2C's WS-FRD-ACT-MSG and SYNCPOINT ROLLBACK.
      OTHERWISE (LINK failed): SYNCPOINT ROLLBACK, no message.
   5. CDEMO-CPVD-PAU-SELECTED = detail key; POPULATE-AUTH-DETAILS.
7. UPDATE-AUTH-DETAILS (520-552): move WS-FRAUD-AUTH-RECORD (which now carries the report date set
   by COPAUS2C) back to the detail; DISPLAY 'RPT DT: ' and the report date; DL/I "replace the
   detail just retrieved". IF OK: SYNCPOINT; message "AUTH FRAUD REMOVED..." when the flag is 'R',
   otherwise "AUTH MARKED FRAUD...". OTHERWISE: SYNCPOINT ROLLBACK, error flag, message
   " System error while FRAUD Tagging, ROLLBACK||" + status, send.
8. POPULATE-AUTH-DETAILS (291-358), only IF the error flag is off: fill every detail field as in
   the field table. Response reason: binary search of the reason table on PA-AUTH-RESP-REASON;
   found -> code + '-' + description; not found -> '9999-ERROR'.
9. SEND-AUTHVIEW-SCREEN (373-396): header (CPVD, COPAUS1C, titles, date, time), ERRMSG = message,
   cursor on CARDNUM, SEND MAP with or without ERASE.

## Rules
### Business rules
- **BR-COPAUS1C-01**: The detail displayed is identified by CDEMO-ACCT-ID (summary ACCNTID) and
  CDEMO-CPVD-PAU-SELECTED (detail PAUT9CTS). Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:211-216 (PROCESS-ENTER-KEY)`,
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:436-469 (READ-AUTH-RECORD)`.
- **BR-COPAUS1C-02**: Display formatting: date YYMMDD -> MM/DD/YY; time HHMMSS -> HH:MM:SS; amount =
  approved amount (-zzzzzzz9.99); Auth Resp 'A' in green when response code is '00', otherwise 'D'
  in red; card expiry MMYY -> MM/YY; Auth Code shows the processing code. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:295-342 (POPULATE-AUTH-DETAILS)`.
- **BR-COPAUS1C-03**: Reason description table (code: description): 0000 APPROVED; 3100 INVALID
  CARD; 4100 INSUFFICNT FUND; 4200 CARD NOT ACTIVE; 4300 ACCOUNT CLOSED; 4400 EXCED DAILY LMT; 5100
  CARD FRAUD; 5200 MERCHANT FRAUD; 5300 LOST CARD; 9000 UNKNOWN. Shown as code + '-' + first 15
  characters of the description; an unknown code shows "9999-ERROR". Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:57-73 (WS-DECLINE-REASON-TABLE)`,
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:319-328 (POPULATE-AUTH-DETAILS)`.
- **BR-COPAUS1C-04**: Fraud status shows the flag, '-', and the 8-character report date when the
  flag is 'F' or 'R'; otherwise '-'. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:344-350 (POPULATE-AUTH-DETAILS)`.
- **BR-COPAUS1C-05**: PF5 toggles the fraud flag: 'F' becomes 'R' (action remove); blank or 'R'
  becomes 'F' (action report). Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:236-242 (MARK-AUTH-FRAUD)`.
- **BR-COPAUS1C-06**: The fraud change is applied in Db2 first (COPAUS2C inserts or updates the
  AUTHFRDS row and sets the report date to today as MM/DD/YY in the record image); only on success
  is the IMS detail replaced with the new flag and report date, and the CICS unit of work (IMS and
  Db2 together) is committed. Any failure rolls back both. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:244-262 (MARK-AUTH-FRAUD)`,
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:520-552 (UPDATE-AUTH-DETAILS)`.
- **BR-COPAUS1C-07**: Success messages: "AUTH FRAUD REMOVED..." after removal, "AUTH MARKED
  FRAUD..." after report. Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:532-538 (UPDATE-AUTH-DETAILS)`.
- **BR-COPAUS1C-08**: PF8 shows the next detail under the same summary in key order, which is the
  next older authorization; there is no backward key. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:268-289 (PROCESS-PF8-KEY)`.
- **BR-COPAUS1C-09**: After any read the PSB is released with SYNCPOINT before the screen is sent
  (ENTER and PF8 paths). Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:218-221,276-279`.

### Validation rules
- **VR-COPAUS1C-01**: CDEMO-ACCT-ID must be numeric and CDEMO-CPVD-PAU-SELECTED must not be spaces
  or low-values; otherwise the error flag is set and the screen is sent with only the header and no
  message. Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:211-225 (PROCESS-ENTER-KEY)`.

### Navigation and keys
- **NAV-COPAUS1C-01**: EIBCALEN = 0 -> XCTL COPAUS0C with an initialized COMMAREA. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:165-169 (MAIN-PARA)`.
- **NAV-COPAUS1C-02**: First entry (context enter) -> display the selected detail. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:173-177 (MAIN-PARA)`.
- **NAV-COPAUS1C-03**: ENTER -> re-read and redisplay the same detail. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:181-183 (MAIN-PARA)`.
- **NAV-COPAUS1C-04**: PF3 -> XCTL COPAUS0C with FROM-TRANID 'CPVD', FROM-PROGRAM 'COPAUS1C',
  context 0 (enter); COPAUS0C then rebuilds its first page for CDEMO-ACCT-ID. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:184-186 (MAIN-PARA)`,
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:360-370 (RETURN-TO-PREV-SCREEN)`.
- **NAV-COPAUS1C-05**: PF5 -> toggle fraud, redisplay with the result message. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:187-189 (MAIN-PARA)`.
- **NAV-COPAUS1C-06**: PF8 -> next authorization, or "Already at the last Authorization..." sent
  without erase (the previous detail stays on the terminal). Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:190-192,281-288`.
- **NAV-COPAUS1C-07**: Any other key -> redisplay the current detail with CCDA-MSG-INVALID-KEY
  "Invalid key pressed. Please see below...         ". Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:193-197 (MAIN-PARA)`.
- **NAV-COPAUS1C-08**: Every turn that does not XCTL ends with RETURN TRANSID 'CPVD' and the
  COMMAREA. Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:202-205 (MAIN-PARA)`.

### Error handling
- **ERR-COPAUS1C-01**: PSB schedule failure (after one TERM and retry on TC) -> error flag, " System
  error while scheduling PSB: Code:" + status, send. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:574-603 (SCHEDULE-PSB)`.
- **ERR-COPAUS1C-02**: Summary GU status other than blank, FW, GE, GB -> error flag, " System error
  while reading Auth Summary: Code:" + status, send. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:445-462 (READ-AUTH-RECORD)`.
- **ERR-COPAUS1C-03**: Detail GNP (qualified) status other than blank, FW, GE, GB -> error flag,
  " System error while reading Auth Details: Code:" + status, send. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:471-488 (READ-AUTH-RECORD)`.
- **ERR-COPAUS1C-04**: Next-detail GNP status other than blank, FW, GE, GB -> error flag, " System
  error while reading next Auth: Code:" + status, send. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:500-517 (READ-NEXT-AUTH-RECORD)`.
- **ERR-COPAUS1C-05**: COPAUS2C returns status 'F' -> its message is shown (for example " SYSTEM
  ERROR DB2: CODE:" + SQLCODE + ", STATE: " + SQLSTATE) and the unit of work is rolled back. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:253-259 (MARK-AUTH-FRAUD)`.
- **ERR-COPAUS1C-06**: LINK to COPAUS2C fails (EIBRESP not NORMAL) -> rollback with no message.
  Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:260-262 (MARK-AUTH-FRAUD)`.
- **ERR-COPAUS1C-07**: IMS REPL fails -> SYNCPOINT ROLLBACK (also undoing the Db2 change), error
  flag, " System error while FRAUD Tagging, ROLLBACK||" + status, send. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:539-551 (UPDATE-AUTH-DETAILS)`.
- **ERR-COPAUS1C-08**: Summary or detail not found (GE/GB) produces no message; see Q-COPAUS1C-02.
  Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:449-451,475-477 (READ-AUTH-RECORD)`.

## Data effects
- ENTER, PF8, other keys: read-only.
- PF5 (one CICS unit of work): Db2 CARDDEMO.AUTHFRDS row for (card, auth timestamp) inserted with
  AUTH_FRAUD = action, or, if it already exists (-803), AUTH_FRAUD and FRAUD_RPT_DATE updated (see
  COPAUS2C). Then IMS PAUTDTL1 replaced with PA-AUTH-FRAUD = 'F' or 'R' and PA-FRAUD-RPT-DATE =
  today 'MM/DD/YY'. SYNCPOINT commits both; any failure issues SYNCPOINT ROLLBACK.
- COMMAREA: CDEMO-CPVD-PAU-SELECTED changes on PF8 and is re-set on PF5.
- The program DISPLAYs "RPT DT: " + report date to the CICS job log on each successful PF5 path.

## Quirks
- **Q-COPAUS1C-01**: When COPAUS2C fails or the LINK fails, the work area already holds the toggled
  flag and the screen shows it (for example "F-" with the old report date) even though nothing was
  saved. After a LINK failure no message explains this. Recommendation: fix. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:236-265`.
- **Q-COPAUS1C-02**: When the summary or detail is not found (GE/GB), no message is set and the
  error flag stays off, so POPULATE-AUTH-DETAILS displays whatever the detail work area contains
  (initial working storage on a new task). PF5 in that state still toggles and LINKs COPAUS2C with
  that content. Recommendation: fix.
- **Q-COPAUS1C-03**: The toggle is 'F' -> 'R' -> 'F'. Removing and re-reporting is allowed without
  limit; each step rewrites the Db2 row and the report date. Recommendation: preserve or decide.
- **Q-COPAUS1C-04**: The "Auth Code" label shows the processing code (PA-PROCESSING-CODE), not the
  authorization id code (PA-AUTH-ID-CODE). Recommendation: decide.
- **Q-COPAUS1C-05**: The Amount field shows the approved amount; a declined authorization shows
  0.00. Recommendation: decide.
- **Q-COPAUS1C-06**: The report date is an 8-character 'MM/DD/YY' string in IMS but a DATE (CURRENT
  DATE) in Db2. Recommendation: preserve in IMS, decide for the target.
- **Q-COPAUS1C-07**: Error paragraphs send the screen immediately and processing continues, so the
  map can be sent twice in one turn. Recommendation: fix.
- **Q-COPAUS1C-08**: VR-COPAUS1C-01 failures show an empty screen with no message.
  Recommendation: fix.
- **Q-COPAUS1C-09**: DISPLAY 'RPT DT: ' writes to the CICS log on every successful fraud update.
  Recommendation: fix (remove or route to structured logging).
- **Q-COPAUS1C-10**: CDEMO-CPVD-FRAUD-DATA (100 bytes) is cleared every turn but never used; it
  lengthens the COMMAREA beyond COPAUS0C's layout. Recommendation: preserve.
- **Q-COPAUS1C-11**: In the reason table, 4400, 5300 and 9000 are listed, but COPAUA0C can only
  produce 0000, 3100, 4100 and 9000. Recommendation: preserve.

## Test scenarios
Sample data: account 7, newest detail (key PA-AUTH-DATE-9C 76679, PA-AUTH-TIME-9C 908250476):
card 4859452612877065, orig date 231116, orig time 141749, type '0100', expiry '1123', source
'102030', response '00', reason '0000', processing code 000000, amounts 1.89, MCC 5442, POS mode
00, merchant id 123501000675423, name "Bestbuy.com", city "Wilmington", state DE, ZIP 19801,
transaction id "40c56bdf89a74c1", match 'P', fraud blank.
- **TS-COPAUS1C-01** (BR-01 to BR-04, NAV-02): Given COPAUS0C passes account 7 and that key, when
  COPAUS1C starts, then the screen shows Card # 4859452612877065, Auth Date 11/16/23, Auth Time
  14:17:49, Auth Resp 'A' in green, Resp Reason "0000-APPROVED", Auth Code "000000", Amount
  "        1.89", POS Entry Mode "00", Source "102030", MCC Code 5442, Card Exp. Date "11/23", Auth
  Type "0100", Tran Id "40c56bdf89a74c1", Match Status 'P', Fraud Status "-", merchant Bestbuy.com,
  123501000675423, Wilmington, DE, 19801.
- **TS-COPAUS1C-02** (BR-05 to BR-07): Given the same detail with a blank fraud flag and Db2
  available, when PF5 is pressed on 2024-03-05, then an AUTHFRDS row is inserted with AUTH_FRAUD 'F',
  the IMS detail gets fraud 'F' and report date "03/05/24", both are committed, and the screen shows
  Fraud Status "F-03/05/24" and message "AUTH MARKED FRAUD...".
- **TS-COPAUS1C-03** (BR-05, BR-07): Given TS-COPAUS1C-02 has run, when PF5 is pressed again, then
  the AUTHFRDS row is updated to AUTH_FRAUD 'R', the IMS flag becomes 'R', and the message is "AUTH
  FRAUD REMOVED...".
- **TS-COPAUS1C-04** (ERR-05, Q-COPAUS1C-01): Given the Db2 insert fails with SQLCODE -904, when PF5
  is pressed, then the unit of work is rolled back, the message is " SYSTEM ERROR DB2: CODE:-000904,
  STATE: " followed by the edited SQLSTATE, IMS is unchanged, and the screen nevertheless shows
  Fraud Status "F-" followed by spaces.
- **TS-COPAUS1C-05** (BR-08, NAV-06): Given the newest detail of account 7 is shown, when PF8 is
  pressed, then the detail with orig time 141741 (transaction id "d347996bc29447e") is shown and
  CDEMO-CPVD-PAU-SELECTED holds its key. Given the oldest of the 50 details is shown, when PF8 is
  pressed, then "Already at the last Authorization..." appears and the old detail stays on screen.
- **TS-COPAUS1C-06** (NAV-04): Given any detail is shown, when PF3 is pressed, then XCTL to COPAUS0C,
  which shows page 1 for account 7.
- **TS-COPAUS1C-07** (NAV-07): Given any detail is shown, when PA1 or PF2 is pressed, then the same
  detail is redisplayed with "Invalid key pressed. Please see below...         ".
- **TS-COPAUS1C-08** (VR-01, Q-COPAUS1C-08): Given a COMMAREA with CDEMO-CPVD-PAU-SELECTED blank,
  when COPAUS1C starts, then only the header is shown, with no message.
- **TS-COPAUS1C-09** (NAV-01): Given CPVD is typed on a clear screen, then XCTL to COPAUS0C.
- **TS-COPAUS1C-10** (BR-03): Given a detail with reason '4100', then Resp Reason shows
  "4100-INSUFFICNT FUND" and Auth Resp 'D' in red; given reason '1234', then "9999-ERROR".

## Open questions
- Should "Auth Code" show the authorization id code rather than the processing code?
- Should removal ('R') be allowed to flip back to 'F', and should repeated toggles keep history?
- Is DISPLAY 'RPT DT: ' a leftover debug statement?

## Target mapping proposal (non-normative)
Uses the tables proposed in `modernization/spec/programs/COPAUA0C.md` plus the existing Db2 table.
- Read detail: `SELECT ... FROM pending_auth_detail WHERE account_id = :acct AND auth_key = :key`.
- Next: `... WHERE account_id = :acct AND auth_key > :key ORDER BY auth_key FETCH FIRST 1 ROW ONLY`.
- PF5 in one database transaction: `INSERT INTO auth_fraud ... ON CONFLICT (card_num, auth_ts) DO
  UPDATE SET auth_fraud = :action, fraud_rpt_date = CURRENT_DATE`, then
  `UPDATE pending_auth_detail SET fraud_flag = :action, fraud_rpt_date = :today
  WHERE account_id = :acct AND auth_key = :key`; commit or roll back together. If IMS and Db2
  both become one relational database, the two-phase CICS unit of work becomes a single local
  transaction.
- REST: `GET /accounts/{acct}/pending-authorizations/{key}`,
  `GET .../{key}/next`, `POST .../{key}/fraud-toggle` (or `PUT .../{key}/fraud` with the target
  state, which is safer than a toggle under retries).
