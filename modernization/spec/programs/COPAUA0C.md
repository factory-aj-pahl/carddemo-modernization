# COPAUA0C: Authorize card purchases arriving on an MQ request queue and record them as pending authorizations in IMS

| Attribute | Value |
|:--|:--|
| Source | `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl` (1026 lines) |
| Module | app-authorization-ims-db2-mq |
| Kind | Online (CICS), MQ-triggered background task with no terminal |
| Transaction ID / Job | CP00 (`app/app-authorization-ims-db2-mq/csd/CRDDEMO2.csd:11-15,59-60`, PROFILE DFHCICST, ACTION(BACKOUT)); started by the CICS MQ trigger monitor when a message arrives on the request queue |
| BMS map | none |
| Copybooks | CCPAURQY (request), CCPAURLY (response), CCPAUERY (error log record), CIPAUSMY (IMS root PAUTSUM0), CIPAUDTY (IMS child PAUTDTL1), CVACT03Y (card cross-reference), CVACT01Y (account), CVCUS01Y (customer), CMQODV, CMQMDV, CMQV, CMQTML, CMQPMOV, CMQGMOV (IBM MQ) |
| Calls / XCTL targets | MQOPEN, MQGET, MQPUT1, MQCLOSE; EXEC DLI SCHD / TERM / GU / REPL / ISRT; EXEC CICS RETRIEVE, READ, SYNCPOINT, ASKTIME, FORMATTIME, WRITEQ TD, RETURN. No XCTL or LINK. |
| Data stores | MQ request queue (name from trigger message): destructive Get, no syncpoint. MQ reply queue (MQMD-REPLYTOQ of each request): MQPUT1. VSAM CCXREF: Read by card number. VSAM ACCTDAT: Read by account id. VSAM CUSTDAT: Read by customer id. IMS DBPAUTP0 through PSB PSBPAUTB, PCB 1: PAUTSUM0 GU / REPL / ISRT by ACCNTID; PAUTDTL1 ISRT under ACCNTID. TD queue CSSL: Write (error log). CARDDAT and CARDAIX names are declared (lines 37-38) but never used. |

## Purpose
COPAUA0C is the card authorization engine. Each MQ request carries one purchase authorization
request in comma-separated text. The program looks up the card in the base cross-reference
file, reads the account and customer, reads the account's pending-authorization summary from
IMS, approves the purchase if the amount fits within the available credit, and sends a
comma-separated reply to the requester's reply queue. For every card that exists in the
cross-reference it then updates the IMS summary counters and balance and inserts an IMS detail
segment recording the authorization, so that the online screens (COPAUS0C, COPAUS1C) and the
purge job (CBPAUP0C) can see it.

## Invocation and state
- Started by transaction CP00. The program issues `EXEC CICS RETRIEVE INTO(MQTM) NOHANDLE`; on
  NORMAL it takes the request queue name from `MQTM-QNAME` and saves `MQTM-TRIGGERDATA` (saved
  but never used). `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:233-240 (1000-INITIALIZE)`.
- The module README names the queues `AWS.M2.CARDDEMO.PAUTH.REQUEST` and
  `AWS.M2.CARDDEMO.PAUTH.REPLY`; the program itself never hard-codes either name. The reply
  queue is whatever each request's `MQMD-REPLYTOQ` contains.
- No COMMAREA is used (the 4096-byte LINKAGE area at line 215 is never referenced). Nothing is
  carried across tasks. Within one task, WORKING-STORAGE flags and record areas persist from one
  message to the next, and several of them are not reset (see Q-COPAUA0C-01 to Q-COPAUA0C-04).
- Connection handles W01-HCONN-REQUEST and W02-HCONN-REPLY are zero (the CICS-MQ adapter default
  connection).
- One task processes at most 501 messages (see BR-COPAUA0C-02), then ends; it also ends when no
  message arrives within 5 seconds.

### Request message (CCPAURQY), comma-delimited text, up to 500 bytes
Fields in order (`app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:354-374 (2100-EXTRACT-REQUEST-MSG)`):

| # | Field | Picture | Meaning |
|:--|:--|:--|:--|
| 1 | PA-RQ-AUTH-DATE | X(6) | Request date YYMMDD (stored as PA-AUTH-ORIG-DATE) |
| 2 | PA-RQ-AUTH-TIME | X(6) | Request time HHMMSS (stored as PA-AUTH-ORIG-TIME, and echoed as the authorization id code) |
| 3 | PA-RQ-CARD-NUM | X(16) | Card number (key into CCXREF) |
| 4 | PA-RQ-AUTH-TYPE | X(4) | Authorization type |
| 5 | PA-RQ-CARD-EXPIRY-DATE | X(4) | Card expiry MMYY (not checked) |
| 6 | PA-RQ-MESSAGE-TYPE | X(6) | Message type |
| 7 | PA-RQ-MESSAGE-SOURCE | X(6) | Message source |
| 8 | PA-RQ-PROCESSING-CODE | 9(6) | Processing code |
| 9 | transaction amount | X(13) into WS-TRANSACTION-AMT-AN, then FUNCTION NUMVAL into PA-RQ-TRANSACTION-AMT and WS-TRANSACTION-AMT S9(10)V99 | Purchase amount |
| 10 | PA-RQ-MERCHANT-CATAGORY-CODE | X(4) | MCC |
| 11 | PA-RQ-ACQR-COUNTRY-CODE | X(3) | Acquirer country |
| 12 | PA-RQ-POS-ENTRY-MODE | 9(2) | POS entry mode |
| 13 | PA-RQ-MERCHANT-ID | X(15) | Merchant id |
| 14 | PA-RQ-MERCHANT-NAME | X(22) | Merchant name |
| 15 | PA-RQ-MERCHANT-CITY | X(13) | Merchant city |
| 16 | PA-RQ-MERCHANT-STATE | X(2) | Merchant state |
| 17 | PA-RQ-MERCHANT-ZIP | X(9) | Merchant ZIP |
| 18 | PA-RQ-TRANSACTION-ID | X(15) | Requester's transaction id |

### Reply message (CCPAURLY), comma-delimited text
`card-num(16),transaction-id(15),auth-id-code(6),resp-code(2),resp-reason(4),approved-amt(14),`
with a trailing comma; the amount is edited `-zzzzzzzzz9.99` (leading spaces).
`app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:720-731 (6000-MAKE-DECISION)`.

### Error log record (CCPAUERY), written to TD queue CSSL
ERR-DATE X(6) YYMMDD, ERR-TIME X(6) HHMMSS, ERR-APPLICATION X(8) = 'CP00', ERR-PROGRAM X(8) =
'COPAUA0C', ERR-LOCATION X(4), ERR-LEVEL X(1) (L log, I info, W warning, C critical),
ERR-SUBSYSTEM X(1) (A app, C CICS, I IMS, D Db2, M MQ, F file), ERR-CODE-1 X(9), ERR-CODE-2 X(9),
ERR-MESSAGE X(50), ERR-EVENT-KEY X(20).

## Processing flow
1. MAIN-PARA (lines 220-227): perform 1000-INITIALIZE, 2000-MAIN-PROCESS, 9000-TERMINATE, then
   `EXEC CICS RETURN` (no TRANSID).
2. 1000-INITIALIZE (230-247): RETRIEVE the trigger message; IF NORMAL, set the request queue
   name from MQTM-QNAME. Set the wait interval to 5000 ms. Perform 1100-OPEN-REQUEST-QUEUE
   (MQOPEN with MQOO-INPUT-SHARED; on failure log M001 critical, which ends the task). Perform
   3100-READ-REQUEST-MQ for the first message.
3. 3100-READ-REQUEST-MQ (386-432): MQGET with NO-SYNCPOINT + WAIT (5 s) + CONVERT +
   FAIL-IF-QUIESCING, MsgId and CorrelId reset to none, format MQSTR, buffer 500 bytes.
   - IF completion OK: save the request's CORRELID and REPLYTOQ.
   - OTHERWISE IF reason is MQRC-NO-MSG-AVAILABLE: set "no more messages".
   - OTHERWISE: log M003 critical (ends the task).
4. 2000-MAIN-PROCESS (323-345): WHILE messages are available and the loop-end flag is not set:
   1. 2100-EXTRACT-REQUEST-MSG: split the message on commas into the 18 request fields; convert
      the amount with NUMVAL.
   2. 5000-PROCESS-AUTH (step 5).
   3. Add 1 to the processed count; `EXEC CICS SYNCPOINT`; mark the PSB as not scheduled (the
      syncpoint releases it).
   4. IF processed count > 500: set loop end. OTHERWISE perform 3100-READ-REQUEST-MQ.
5. 5000-PROCESS-AUTH (438-466):
   1. Set decision = approve. Perform 1200-SCHEDULE-PSB (SCHD PSBPAUTB NODHABEND; IF status TC
      "scheduled more than once", TERM and SCHD again; IF status OK set PSB scheduled, OTHERWISE
      log I001 critical).
   2. Set card-found-in-xref and account-found to yes.
   3. 5100-READ-XREF-RECORD: READ CCXREF with the request card number.
      - NORMAL: card found.
      - NOTFND: card not found, account not found; log A001 warning (processing continues).
      - OTHER: log C001 critical (ends the task).
   4. IF card found in xref:
      - 5200-READ-ACCT-RECORD: READ ACCTDAT with XREF-ACCT-ID. NORMAL: account found.
        NOTFND: account not found, log A002 warning. OTHER: C002 critical.
      - 5300-READ-CUST-RECORD: READ CUSTDAT with XREF-CUST-ID. NORMAL: customer found. NOTFND:
        customer not found, log A003 warning. OTHER: C003 critical.
      - 5500-READ-AUTH-SUMMRY: DL/I "get unique the summary (root) segment whose ACCNTID equals
        XREF-ACCT-ID". Status blank or FW: summary found. GE: summary not found. OTHER: I002
        critical.
      - 5600-READ-PROFILE-DATA: does nothing.
   5. 6000-MAKE-DECISION (step 6).
   6. 7100-SEND-RESPONSE (step 7).
   7. IF card found in xref: 8000-WRITE-AUTH-TO-DB = 8400-UPDATE-SUMMARY then 8500-INSERT-AUTH
      (steps 8 and 9).
6. 6000-MAKE-DECISION (657-732):
   1. Reply card number = request card number; reply transaction id = request transaction id;
      reply auth id code = request AUTH-TIME.
   2. IF summary found: available = PA-CREDIT-LIMIT - PA-CREDIT-BALANCE; IF amount > available,
      decline with reason flag "insufficient fund".
      OTHERWISE IF account found: available = ACCT-CREDIT-LIMIT - ACCT-CURR-BAL; IF amount >
      available, decline with "insufficient fund".
      OTHERWISE decline (no reason flag set).
   3. IF declined: response code '05', approved amount 0. OTHERWISE response code '00',
      approved amount = request amount.
   4. Reason = '0000'. IF declined, evaluate in this order: card not in xref OR account not
      found OR customer not found -> '3100'; insufficient fund -> '4100'; card not active ->
      '4200'; account closed -> '4300'; card fraud -> '5100'; merchant fraud -> '5200';
      otherwise '9000'.
   5. Build the reply text with STRING ... WITH POINTER WS-RESP-LENGTH into W02-PUT-BUFFER.
7. 7100-SEND-RESPONSE (738-780): MQPUT1 to the saved reply queue name, message type REPLY,
   CorrelId = saved request CorrelId, MsgId none, ReplyToQ blank, non-persistent, expiry 50
   (tenths of a second = 5 seconds), format MQSTR, options NO-SYNCPOINT + DEFAULT-CONTEXT, length
   = WS-RESP-LENGTH. IF completion not OK: log M004 critical (ends the task before any IMS write).
8. 8400-UPDATE-SUMMARY (798-848):
   1. IF summary not found: initialize the summary area (numeric fields zero, others spaces),
      PA-ACCT-ID = XREF-ACCT-ID, PA-CUST-ID = XREF-CUST-ID.
   2. PA-CREDIT-LIMIT = ACCT-CREDIT-LIMIT; PA-CASH-LIMIT = ACCT-CASH-CREDIT-LIMIT (always).
   3. IF approved: approved count + 1; approved amount + approved amount; credit balance +
      approved amount; cash balance = 0.
      OTHERWISE: declined count + 1; declined amount + PA-TRANSACTION-AMT (the detail work
      area, see Q-COPAUA0C-04).
   4. IF summary found: DL/I "replace the summary segment just retrieved". OTHERWISE DL/I
      "insert a new summary root". IF status not blank/FW: log I003 critical.
9. 8500-INSERT-AUTH (854-933):
   1. ASKTIME; FORMATTIME into YYDDD, HHMMSS and milliseconds (CICS region local time).
   2. PA-AUTH-DATE-9C = 99999 - YYDDD; PA-AUTH-TIME-9C = 999999999 - (HHMMSS x 1000 + ms).
   3. Copy request fields into the detail (orig date, orig time, card, type, expiry, message
      type, source, processing code, transaction amount, MCC, country, POS mode, merchant id,
      name, city, state, zip, transaction id) and reply fields (auth id code, response code,
      reason, approved amount).
   4. Match status = 'P' (pending) IF approved, OTHERWISE 'D' (declined). Fraud flag and fraud
      report date = spaces. PA-ACCT-ID = XREF-ACCT-ID.
   5. DL/I "insert a detail segment under the summary whose ACCNTID equals PA-ACCT-ID". IF
      status not blank/FW: log I004 critical.
10. 9000-TERMINATE (940-948): IF PSB scheduled, `EXEC DLI TERM`. 9100-CLOSE-REQUEST-QUEUE: IF
    the queue is open, MQCLOSE; IF that fails log M005 warning.
11. 9500-LOG-ERROR (983-1011): ASKTIME/FORMATTIME YYMMDD and time; fill application, program,
    date, time; WRITEQ TD CSSL (NOHANDLE). IF level is critical: 9990-END-ROUTINE = 9000-TERMINATE
    then `EXEC CICS RETURN`, which ends the task immediately.

## Rules
### Business rules
- **BR-COPAUA0C-01**: The request queue name is the one in the trigger message (MQTM-QNAME). When
  RETRIEVE does not return NORMAL, the name stays uninitialized and the open fails (M001).
  Source: `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:233-240 (1000-INITIALIZE)`,
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:255-284 (1100-OPEN-REQUEST-QUEUE)`.
- **BR-COPAUA0C-02**: The task keeps reading messages, waiting up to 5000 ms for each, until no
  message arrives or the processed count exceeds 500. The limit check happens after the count is
  incremented, so up to 501 messages are processed per task. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:242,323-345 (2000-MAIN-PROCESS)`.
- **BR-COPAUA0C-03**: Each request is parsed by splitting on commas into the 18 fields in the
  order listed above; the amount text (up to 13 characters) is converted with NUMVAL. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:351-380 (2100-EXTRACT-REQUEST-MSG)`.
- **BR-COPAUA0C-04**: For each message the decision starts as approve and the card-found and
  account-found flags start as yes. The customer-found, summary-found and decline-reason flags are
  not reset. Source: `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:441-446 (5000-PROCESS-AUTH)`.
- **BR-COPAUA0C-05**: Account, customer, and summary are read only when the card is found in the
  cross-reference. Account id and customer id come from the cross-reference record, not from the
  request. Source: `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:448-457 (5000-PROCESS-AUTH)`.
- **BR-COPAUA0C-06**: When a summary exists, available credit = summary credit limit - summary
  credit balance. The purchase is declined with reason "insufficient fund" when amount > available;
  amount equal to available is approved. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:665-671 (6000-MAKE-DECISION)`.
- **BR-COPAUA0C-07**: When no summary exists but the account was read, available credit = account
  credit limit - account current balance, with the same comparison. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:672-679 (6000-MAKE-DECISION)`.
- **BR-COPAUA0C-08**: When there is no summary and no account, the purchase is declined.
  Source: `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:680-682 (6000-MAKE-DECISION)`.
- **BR-COPAUA0C-09**: Declined: response code '05', approved amount 0. Approved: response code
  '00', approved amount = full requested amount (no partial approvals). Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:685-696 (6000-MAKE-DECISION)`.
- **BR-COPAUA0C-10**: Reason code is '0000' for approvals. For declines, first match wins: card
  not in xref, account not found, or customer not found -> '3100'; insufficient fund -> '4100';
  card not active -> '4200'; account closed -> '4300'; card fraud -> '5100'; merchant fraud ->
  '5200'; otherwise '9000'. Only '3100', '4100' and '9000' are reachable (Q-COPAUA0C-08). Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:698-718 (6000-MAKE-DECISION)`.
- **BR-COPAUA0C-11**: The reply echoes the request card number and transaction id; the
  authorization id code is the request time HHMMSS. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:660-662 (6000-MAKE-DECISION)`.
- **BR-COPAUA0C-12**: The reply is text `card,txnid,authid,resp,reason,amount,` (trailing comma,
  amount edited `-zzzzzzzzz9.99`), put with MQPUT1 to the request's reply-to queue as message type
  REPLY, CorrelId copied from the request, non-persistent, expiry 5 seconds, format MQSTR, outside
  syncpoint. Source: `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:720-731 (6000-MAKE-DECISION)`,
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:741-766 (7100-SEND-RESPONSE)`.
- **BR-COPAUA0C-13**: The reply is sent before any IMS update. IMS is updated (approved and
  declined alike) only when the card is in the cross-reference; unknown cards leave no record.
  Source: `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:459-465 (5000-PROCESS-AUTH)`.
- **BR-COPAUA0C-14**: Summary maintenance: a new summary is initialized with zeros and the xref
  account and customer ids. Credit limit and cash limit are refreshed from the account record on
  every authorization. Approved: approved count +1, approved amount + approved amount, credit
  balance + approved amount, cash balance set to 0. Declined: declined count +1, declined amount +
  PA-TRANSACTION-AMT. Existing summary is replaced; new summary is inserted. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:798-834 (8400-UPDATE-SUMMARY)`.
- **BR-COPAUA0C-15**: Detail key: PA-AUTH-DATE-9C = 99999 - current YYDDD and PA-AUTH-TIME-9C =
  999999999 - (HHMMSS x 1000 + milliseconds), from the CICS clock at insert time. Because both are
  inverted, ascending key order under a summary is newest first. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:857-875 (8500-INSERT-AUTH)`.
- **BR-COPAUA0C-16**: The detail stores every request field (with request date/time as
  PA-AUTH-ORIG-DATE / PA-AUTH-ORIG-TIME) plus the reply auth id code, response code, reason and
  approved amount. Source: `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:877-900 (8500-INSERT-AUTH)`.
- **BR-COPAUA0C-17**: Detail match status is 'P' (pending) for approvals and 'D' (declined) for
  declines; fraud flag and fraud report date are blank. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:902-909 (8500-INSERT-AUTH)`.
- **BR-COPAUA0C-18**: Each message is its own unit of work: after a message is processed the
  program issues SYNCPOINT, committing IMS and recoverable resources. The MQGET and MQPUT1 are
  outside syncpoint, so the request is consumed and the reply is visible immediately regardless of
  the commit. Source: `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:334-337 (2000-MAIN-PROCESS)`,
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:389 (3100-READ-REQUEST-MQ)`,
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:753-754 (7100-SEND-RESPONSE)`.
- **BR-COPAUA0C-19**: Every logged error writes one CCPAUERY record to TD queue CSSL with the date
  YYMMDD, time HHMMSS, application 'CP00' and program 'COPAUA0C'. Critical errors then end the task
  (DLI TERM if scheduled, MQCLOSE, CICS RETURN); warnings continue. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:983-1023 (9500-LOG-ERROR, 9990-END-ROUTINE)`.

### Validation rules
- None. The program does not validate the request: amount sign and format, card expiry, card
  status, account status, number of fields, and field lengths are all accepted as received
  (UNSTRING truncates or pads silently). See Q-COPAUA0C-08 and Q-COPAUA0C-12.

### Navigation and keys
- **NAV-COPAUA0C-01**: No terminal and no XCTL. The task ends with `EXEC CICS RETURN` (no
  TRANSID) after the loop, or immediately after a critical error; the next trigger event starts a
  new CP00 task. Source: `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:220-227 (MAIN-PARA)`,
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:1016-1023 (9990-END-ROUTINE)`.

### Error handling
- **ERR-COPAUA0C-01**: MQOPEN of the request queue fails -> location 'M001', critical, subsystem M,
  code-1 = completion code, code-2 = reason, message "REQ MQ OPEN ERROR"; task ends. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:270-283 (1100-OPEN-REQUEST-QUEUE)`.
- **ERR-COPAUA0C-02**: MQGET fails with any reason other than no-message-available -> 'M003',
  critical, subsystem C (CICS), codes = completion/reason, message "FAILED TO READ REQUEST MQ",
  event key PA-CARD-NUM; task ends. No-message-available ends the loop normally with no log.
  Source: `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:410-431 (3100-READ-REQUEST-MQ)`.
- **ERR-COPAUA0C-03**: PSB schedule fails (after one TERM/retry on TC) -> 'I001', critical,
  subsystem I, code-1 = DL/I status, message "IMS SCHD FAILED"; task ends. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:292-318 (1200-SCHEDULE-PSB)`.
- **ERR-COPAUA0C-04**: CCXREF NOTFND -> 'A001', warning, subsystem A, message "CARD NOT FOUND IN
  XREF", event key = card number; processing continues. On the first message of a task, or when
  the previous message found no authorization summary, the request is declined '05'/'3100'. If the
  previous message in the same task found a summary, the decision uses that stale summary and can
  approve; see Q-COPAUA0C-02.
  Other RESP -> 'C001', critical, subsystem C, codes = RESP/RESP2, message "FAILED TO READ XREF
  FILE"; task ends without a reply. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:487-513 (5100-READ-XREF-RECORD)`,
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:441-457 (5000-PROCESS-AUTH)`,
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:665-671 (6000-MAKE-DECISION)`.
- **ERR-COPAUA0C-05**: ACCTDAT NOTFND -> 'A002', warning, message "ACCT NOT FOUND IN XREF",
  event key = account id. Other RESP -> 'C002', critical, message "FAILED TO READ ACCT FILE".
  Source: `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:535-561 (5200-READ-ACCT-RECORD)`.
- **ERR-COPAUA0C-06**: CUSTDAT NOTFND -> 'A003', warning, message "CUST NOT FOUND IN XREF",
  event key = customer id. Other RESP -> 'C003', critical, message "FAILED TO READ CUST FILE".
  Source: `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:583-609 (5300-READ-CUST-RECORD)`.
- **ERR-COPAUA0C-07**: Summary GU status other than blank, FW or GE -> 'I002', critical, message
  "IMS GET SUMMARY FAILED", event key PA-CARD-NUM; task ends without a reply. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:626-640 (5500-READ-AUTH-SUMMRY)`.
- **ERR-COPAUA0C-08**: MQPUT1 of the reply fails -> 'M004', critical, subsystem M, message
  "FAILED TO PUT ON REPLY MQ"; task ends before IMS is updated. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:767-779 (7100-SEND-RESPONSE)`.
- **ERR-COPAUA0C-09**: Summary REPL/ISRT status other than blank or FW -> 'I003', critical,
  message "IMS UPDATE SUMRY FAILED"; task ends after the reply was already sent. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:835-847 (8400-UPDATE-SUMMARY)`.
- **ERR-COPAUA0C-10**: Detail ISRT status other than blank or FW (for example II for a duplicate
  key) -> 'I004', critical, message "IMS INSERT DETL FAILED"; task ends. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:920-932 (8500-INSERT-AUTH)`.
- **ERR-COPAUA0C-11**: MQCLOSE fails -> 'M005', warning, message "FAILED TO CLOSE REQUEST MQ".
  Source: `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:953-977 (9100-CLOSE-REQUEST-QUEUE)`.
- **ERR-COPAUA0C-12**: The CSSL WRITEQ uses NOHANDLE; a failure to log is ignored. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:1001-1006 (9500-LOG-ERROR)`.

## Data effects
Per message, in this order:
1. Request message removed from the request queue at MQGET time (no syncpoint, not recoverable).
2. Reply message put to the reply queue (no syncpoint).
3. IF card in xref: IMS PAUTSUM0 for XREF-ACCT-ID replaced (exists) or inserted (new): fields
   PA-CREDIT-LIMIT, PA-CASH-LIMIT (from ACCTDAT), and either PA-APPROVED-AUTH-CNT,
   PA-APPROVED-AUTH-AMT, PA-CREDIT-BALANCE, PA-CASH-BALANCE (approved) or PA-DECLINED-AUTH-CNT,
   PA-DECLINED-AUTH-AMT (declined). New summaries also get PA-ACCT-ID and PA-CUST-ID; PA-AUTH-STATUS
   and PA-ACCOUNT-STATUS are blank and never set.
4. IF card in xref: IMS PAUTDTL1 inserted under that summary with the inverted timestamp key.
5. SYNCPOINT commits 3 and 4 together.
No base VSAM file (CCXREF, ACCTDAT, CUSTDAT) is ever updated; ACCT-CURR-BAL is not changed by an
approval. On a critical error after step 3, the task ends with DLI TERM and CICS RETURN, both of
which commit, so a summary update can be committed without its detail (Q-COPAUA0C-07).

## Quirks
- **Q-COPAUA0C-01**: The STRING pointer WS-RESP-LENGTH (VALUE 1) is never reset, and the reply
  buffer is never cleared. The first reply is 63 characters and MQPUT1 sends WS-RESP-LENGTH = 64
  bytes (one stray byte). The second message in the same task appends at position 64 and sends
  127 bytes containing both replies; the third sends 190 bytes; the fourth overflows the 200-byte
  buffer and sends 201 bytes (one byte past the buffer); every later reply repeats that same
  201-byte content. A requester reading the first fields of its reply sees the first message's
  answer. Recommendation: fix (reset pointer to 1 and clear the buffer per message; send length
  pointer - 1). Source: `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:46,720-731,756`.
- **Q-COPAUA0C-02**: The summary-found flag is set only inside 5500, which runs only when the card
  is in the xref. For an unknown card, the flag keeps the previous message's value. If the previous
  message found a summary, 6000 computes available credit from that other account's summary (still
  in PENDING-AUTH-SUMMARY) and can approve ('00', reason '0000') a card that does not exist. No
  IMS record is written for it. Recommendation: fix. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:441-457,665-671`.
- **Q-COPAUA0C-03**: When ACCTDAT returns NOTFND, ACCOUNT-RECORD still holds the previous
  message's account (or its initial content), and 8400 copies that record's credit and cash
  limits into this account's summary. Recommendation: fix. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:810-811 (8400-UPDATE-SUMMARY)`.
- **Q-COPAUA0C-04**: For a decline, the declined amount is incremented by PA-TRANSACTION-AMT from
  the detail work area, which 8500 fills only later. It therefore adds the previous message's
  transaction amount (or the initial content on the first message), not this request's amount.
  Recommendation: fix (use WS-TRANSACTION-AMT). Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:819-821`.
- **Q-COPAUA0C-05**: Once a summary exists, available credit ignores the base account balance and
  uses only PA-CREDIT-BALANCE, which only ever grows (approvals add to it; nothing in this module
  or the base batch reduces it, and CBPAUP0C does not rewrite the summary). Credit limits are
  refreshed from ACCTDAT each time, but balances are not reconciled with ACCT-CURR-BAL.
  Recommendation: decide. Source: `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:665-679,813-818`.
- **Q-COPAUA0C-06**: The request is consumed (MQGET outside syncpoint) and the reply is put
  (outside syncpoint) before IMS is updated. If the IMS update fails or the region fails, the
  requester has an approval with no pending record, and the request cannot be reprocessed.
  Recommendation: decide (at-most-once semantics may be intended for a 5-second reply SLA).
- **Q-COPAUA0C-07**: Critical errors end the task through 9000-TERMINATE (DLI TERM) and CICS
  RETURN with no SYNCPOINT ROLLBACK. Both TERM and normal task end commit, so after an I004 the
  already replaced or inserted summary is committed without its detail, and earlier messages are
  of course already committed. Recommendation: fix (roll back on critical errors). Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:940-948,1016-1023`.
- **Q-COPAUA0C-08**: Reason flags for card not active, account closed, card fraud and merchant
  fraud are never set, so reasons '4200', '4300', '5100', '5200' cannot occur. Card expiry,
  card active status (CARDDAT, declared but unused), account active status and expiry dates are not
  checked. 5600-READ-PROFILE-DATA is an empty stub. Recommendation: decide.
- **Q-COPAUA0C-09**: A customer-not-found condition does not cause a decline (only the reason code
  of an otherwise declined request changes): when the account has enough credit the request is
  approved even though CUSTDAT has no customer. When it is declined for insufficient funds, the
  reason is '3100', not '4100', because the not-found test is evaluated first. Recommendation:
  decide.
- **Q-COPAUA0C-10**: The decline-reason flag is never reset between messages. With the current
  evaluation order this has no visible effect, but it becomes visible if more decline paths are
  added. Recommendation: fix.
- **Q-COPAUA0C-11**: The detail key has millisecond resolution taken from the CICS clock at insert
  time. Two authorizations for the same account in the same millisecond (for example from two
  concurrent CP00 tasks) give status II, which is a critical I004 error that ends the task.
  Recommendation: decide.
- **Q-COPAUA0C-12**: Negative or zero amounts are not rejected. A negative amount is approved
  (it is never greater than available credit) and decreases PA-CREDIT-BALANCE and
  PA-APPROVED-AUTH-AMT. Non-numeric amount text produces whatever NUMVAL returns. Recommendation: fix.
- **Q-COPAUA0C-13**: ERR-EVENT-KEY for M003, I002, I003, I004 and M004 is PA-CARD-NUM from the detail
  work area, which belongs to the previous message (or is blank). A001 and C001 put the full card
  number into the CSSL log, which conflicts with the repository rule against logging card numbers
  (CD070, `docs/data-handling.md`). Recommendation: fix.
- **Q-COPAUA0C-14**: The reply has a trailing comma and an amount with leading spaces
  (`-zzzzzzzzz9.99`). Consumers must tolerate both. Recommendation: preserve (contract) or decide.
- **Q-COPAUA0C-15**: The limit WS-REQSTS-PROCESS-LIMIT is 500 but the test is "greater than", so 501
  messages are processed. Messages left on the queue wait for the next trigger event (depends on
  the queue's trigger type). Recommendation: preserve.
- **Q-COPAUA0C-16**: M003 (an MQ failure) is tagged subsystem 'C' (CICS) instead of 'M'.
  Recommendation: fix.
- **Q-COPAUA0C-17**: Every approval sets PA-CASH-BALANCE to 0, discarding any value loaded by
  PAUDBLOD. Recommendation: decide.
- **Q-COPAUA0C-18**: The PSB is scheduled once per message and released by the per-message
  SYNCPOINT. When a request's card is not in the xref the PSB is still scheduled though no DL/I
  call is made. Recommendation: preserve.

## Test scenarios
Sample data: base `app/data/ASCII/cardxref.txt`, `app/data/ASCII/acctdata.txt`,
`app/data/ASCII/custdata.txt`; IMS sample `app/app-authorization-ims-db2-mq/data/EBCDIC/AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat`.
Card 4859452612877065 -> customer 7, account 7 (ACCT credit limit 2065.00, cash limit 264.00,
current balance 193.00); IMS summary for account 7: credit limit 2065.00, cash limit 264.00, credit
balance 74.79, 50 approved totalling 74.79, 0 declined. Card 0923877193247330 -> customer 2,
account 2 (credit limit 6130.00, cash limit 5448.00, current balance 158.00), no IMS summary.
Example request (amount 100.00):
`231026,143015,4859452612877065,0100,1226,1234  ,102030,000000,100.00,5442,USA,01,MERCH0000000001,Amazon.com,Wilmington,DE,19801,TXN000000000001`.

- **TS-COPAUA0C-01** (BR-03, BR-06, BR-09, BR-11, BR-12, BR-14, BR-16, BR-17): Given the account 7
  data above and a fresh CP00 task, when the example request is the first message, then the reply
  is `4859452612877065,TXN000000000001,143015,00,0000,        100.00,`; the account 7 summary
  becomes approved count 51, approved amount 174.79, credit balance 174.79, cash balance 0.00,
  credit limit 2065.00, cash limit 264.00; one PAUTDTL1 is inserted with orig date 231026, orig time
  143015, response '00', reason '0000', approved amount 100.00, match status 'P', fraud blank.
- **TS-COPAUA0C-02** (BR-06 boundary): Given the account 7 summary (available 2065.00 - 74.79 =
  1990.21), when the amount is 1990.21, then the reply response is '00'; when the amount is 1990.22,
  then the reply is response '05', reason '4100', amount `          0.00` and the summary declined
  count becomes 1.
- **TS-COPAUA0C-03** (BR-07, BR-14): Given card 0923877193247330 with no IMS summary, when the
  amount is 5972.00 (= 6130.00 - 158.00), then the reply is '00' and a new PAUTSUM0 for account 2 is
  inserted with customer id 2, credit limit 6130.00, cash limit 5448.00, approved count 1, approved
  amount 5972.00, credit balance 5972.00. When the amount is 5972.01, then the reply is '05'/'4100'
  and the new summary has declined count 1 and approved count 0.
- **TS-COPAUA0C-04** (BR-08, BR-10, BR-13, ERR-04): Given a fresh task, when the first message has
  card 9999999999999999 (not in CCXREF), then the reply is response '05', reason '3100', amount 0.00;
  a CSSL record with location 'A001', level 'W', message "CARD NOT FOUND IN XREF" is written; IMS is
  not changed.
- **TS-COPAUA0C-05** (Q-COPAUA0C-02): Given TS-COPAUA0C-01 just ran in the same task, when the next
  message has card 9999999999999999 and amount 10.00, then (as coded) the reply response is '00'
  and reason '0000' even though the card does not exist.
- **TS-COPAUA0C-06** (Q-COPAUA0C-01): Given two messages are queued, when one CP00 task processes
  both, then the first reply is 64 bytes and the second reply is 127 bytes whose first 63
  characters are the first reply.
- **TS-COPAUA0C-07** (BR-15): Given the CICS clock is 2023-10-26 14:30:15.123 (YYDDD 23299), when a
  detail is inserted, then PA-AUTH-DATE-9C = 76700 and PA-AUTH-TIME-9C = 856984876, and the new
  detail is the first child returned by GNP under its summary.
- **TS-COPAUA0C-08** (BR-02): Given 600 messages on the request queue, when CP00 runs once, then 501
  are processed and 99 remain. Given the queue is empty for 5 seconds, then the task closes the queue
  and ends with no CSSL record.
- **TS-COPAUA0C-09** (ERR-08, BR-13): Given the reply-to queue does not exist, when a message for
  card 4859452612877065 is processed, then CSSL gets 'M004' "FAILED TO PUT ON REPLY MQ", the task
  ends, the request message is gone, and the summary is unchanged.
- **TS-COPAUA0C-10** (ERR-03): Given PSBPAUTB cannot be scheduled, when a message arrives, then CSSL
  gets 'I001' "IMS SCHD FAILED" and the task ends without a reply.
- **TS-COPAUA0C-11** (Q-COPAUA0C-09): Given a card whose xref customer id is missing from CUSTDAT
  but whose account has available credit, when an amount within the credit is requested, then the
  reply is '00' and CSSL gets 'A003' "CUST NOT FOUND IN XREF".
- **TS-COPAUA0C-12** (Q-COPAUA0C-12): Given account 7, when the amount is -50.00, then the reply is
  '00' with amount `        -50.00,` and the summary credit balance decreases by 50.00.

## Open questions
- Who sets match status 'M' (matched) and 'E' (expired)? No program in the repository does; the
  base posting job CBTRN02C does not read IMS.
- Are the unreachable reasons 4200/4300/5100/5200 and the profile stub planned features or dead
  code? Should card expiry and account status be checked?
- Should PA-CREDIT-BALANCE be reconciled with ACCT-CURR-BAL (for example when transactions post)?
- Is the at-most-once MQ handling (consume and reply before commit) intended?
- What time zone is the CICS region clock? It drives the detail key, the purge age (CBPAUP0C) and
  the fraud timestamp (COPAUS2C).
- What trigger type (FIRST, EVERY, DEPTH) is defined for the request queue? The definition is not
  in the repository.
- Is the trailing comma in the reply part of the external contract?

## Target mapping proposal (non-normative)
This section proposes a relational and messaging target. It does not change the behavior above.

### IMS DBPAUTP0 to relational tables
DBPAUTP0 is HIDAM over VSAM (`app/app-authorization-ims-db2-mq/ims/DBPAUTP0.dbd:18-37`), primary
index DBPAUTX0 on the root key, no secondary indexes. Root PAUTSUM0 (100 bytes, key ACCNTID packed
6 bytes, unique) has one child type PAUTDTL1 (200 bytes, key PAUT9CTS 8 bytes, unique under its
parent).

```sql
CREATE TABLE pending_auth_summary (
  account_id        NUMERIC(11)   PRIMARY KEY,      -- PA-ACCT-ID / ACCNTID; logical FK to account
  customer_id       NUMERIC(9)    NOT NULL,         -- PA-CUST-ID
  auth_status       CHAR(1),                        -- PA-AUTH-STATUS (never set by any program)
  account_status_1  CHAR(2), account_status_2 CHAR(2), account_status_3 CHAR(2),
  account_status_4  CHAR(2), account_status_5 CHAR(2),  -- PA-ACCOUNT-STATUS OCCURS 5 (never set)
  credit_limit      NUMERIC(11,2) NOT NULL DEFAULT 0,
  cash_limit        NUMERIC(11,2) NOT NULL DEFAULT 0,
  credit_balance    NUMERIC(11,2) NOT NULL DEFAULT 0,
  cash_balance      NUMERIC(11,2) NOT NULL DEFAULT 0,
  approved_count    SMALLINT      NOT NULL DEFAULT 0,  -- S9(4) COMP
  declined_count    SMALLINT      NOT NULL DEFAULT 0,
  approved_amount   NUMERIC(11,2) NOT NULL DEFAULT 0,
  declined_amount   NUMERIC(11,2) NOT NULL DEFAULT 0,
  row_version       INTEGER       NOT NULL DEFAULT 0   -- optimistic locking replaces IMS hold
);

CREATE TABLE pending_auth_detail (
  account_id        NUMERIC(11)   NOT NULL REFERENCES pending_auth_summary ON DELETE CASCADE,
  auth_key          CHAR(16)      NOT NULL,  -- hex of PAUT9CTS (date-9C 5 digits + time-9C 9 digits)
  auth_ts           TIMESTAMP(3)  NOT NULL,  -- decoded: YYDDD = 99999 - date-9C; HHMMSSmmm = 999999999 - time-9C
  orig_date         CHAR(6), orig_time CHAR(6),
  card_num          CHAR(16)      NOT NULL,
  auth_type         CHAR(4), card_expiry_mmyy CHAR(4), message_type CHAR(6), message_source CHAR(6),
  auth_id_code      CHAR(6), resp_code CHAR(2) NOT NULL, resp_reason CHAR(4) NOT NULL,
  processing_code   CHAR(6),
  transaction_amt   NUMERIC(12,2), approved_amt NUMERIC(12,2),
  mcc               CHAR(4), acqr_country CHAR(3), pos_entry_mode SMALLINT,
  merchant_id       CHAR(15), merchant_name VARCHAR(22), merchant_city VARCHAR(13),
  merchant_state    CHAR(2), merchant_zip VARCHAR(9), transaction_id CHAR(15),
  match_status      CHAR(1) CHECK (match_status IN ('P','D','E','M')),
  fraud_flag        CHAR(1) CHECK (fraud_flag IN (' ','F','R')),
  fraud_rpt_date    CHAR(8),                  -- 'MM/DD/YY' as stored; or DATE after cleanup
  PRIMARY KEY (account_id, auth_key)
);
CREATE INDEX ix_pad_newest ON pending_auth_detail (account_id, auth_ts DESC);
```
Keeping `auth_key` preserves the IMS uniqueness rule and the newest-first ordering
(ascending `auth_key` = descending `auth_ts`). The existing Db2 table CARDDEMO.AUTHFRDS
(`app/app-authorization-ims-db2-mq/ddl/AUTHFRDS.ddl`) can be kept as is, or folded into a
`fraud_report` table keyed by (card_num, auth_ts).

### DL/I calls to SQL
| DL/I in this program | SQL equivalent |
|:--|:--|
| SCHD PSBPAUTB / TERM | open / close a connection or transaction |
| GU PAUTSUM0 WHERE ACCNTID = :a | `SELECT ... FROM pending_auth_summary WHERE account_id = :a FOR UPDATE` |
| REPL PAUTSUM0 | `UPDATE pending_auth_summary SET ... WHERE account_id = :a` |
| ISRT PAUTSUM0 | `INSERT INTO pending_auth_summary ...` (or a single UPSERT) |
| ISRT PAUTSUM0 WHERE ACCNTID = :a / PAUTDTL1 | `INSERT INTO pending_auth_detail (account_id, ...) VALUES (:a, ...)` (duplicate key = II) |
| SYNCPOINT | `COMMIT` |

### MQ to REST or broker
- Option A, synchronous REST: `POST /authorizations` with a JSON body holding the 18 request fields
  (amount as decimal, not text); response 200 with `{cardNum, transactionId, authIdCode, respCode,
  reasonCode, approvedAmount}`. A 5-second client timeout matches the reply expiry. Use
  `transactionId` as an idempotency key, which also removes the request-loss window of Q-COPAUA0C-06.
- Option B, message broker request/reply (IBM MQ, Amazon MQ, SQS, or Kafka): keep the request and
  reply queues, carry the correlation id in a header, set the reply time-to-live to 5 seconds, and
  publish the reply only after the database commit (transactional outbox) if the team decides to
  change Q-COPAUA0C-06.
- The CSSL error log maps to structured application logs with the same fields (location,
  level, subsystem, codes, message, masked event key).
