# COACCT01: MQ-triggered listener that answers account inquiry requests from the VSAM account file

| Attribute | Value |
|:--|:--|
| Source | `app/app-vsam-mq/cbl/COACCT01.cbl` (620 lines), `PROGRAM-ID. COACCT01 IS INITIAL` |
| Module | app-vsam-mq |
| Kind | Online (CICS), MQ-triggered background task with no terminal; IBM MQ API calls (MQOPEN, MQGET, MQPUT, MQCLOSE) through the CICS-MQ adapter |
| Transaction ID / Job | CDRA (program `app/app-vsam-mq/csd/CRDDEMOM.csd:1-8` with `TRANSID(CDRA)`, `RELOAD(NO)`, description copied as `LIST CARDS`; transaction `:17-26`, PROFILE DFHCICST, `ACTION(BACKOUT)`; LIBRARY `CARDDLIB` = `AWS.M2.CARDDEMO.LOADLIB`, `:37-41`). Started by the CICS-MQ trigger monitor; the MQ process, initiation queue, and queue definitions are not in the repository. |
| BMS map | none |
| Copybooks | `CVACT01Y` (ACCOUNT-RECORD, ENT-ACCOUNT); IBM MQ `CMQGMOV` (MQGMO), `CMQPMOV` (MQPMO), `CMQMDV` (MQMD), `CMQODV` (MQOD), `CMQV` (constants), `CMQTML` (MQTM trigger message) |
| Calls / XCTL targets | `CALL 'MQOPEN'`, `'MQGET'`, `'MQPUT'`, `'MQCLOSE'`; no XCTL or LINK |
| Data stores | CICS file `ACCTDAT` (ENT-ACCOUNT, KSDS keyed by 11-digit account ID; `app/csd/CARDDEMO.CSD`): Read by key. MQ: triggered request queue (name from the trigger message): destructive GET under syncpoint; `CARD.DEMO.REPLY.ACCT`: PUT; `CARD.DEMO.ERROR`: PUT. Message layouts: ENT-MQ-ACCOUNT-INQUIRY. |

## Purpose
COACCT01 is a server program that answers account inquiries arriving as MQ messages. When a request queue
receives messages, MQ triggers transaction CDRA; the program reads every waiting request, looks up the account in
the VSAM account master (the same ACCTDAT file the online account screens use), and puts a fixed-format text reply
with the account status, balance, limits, and dates on the reply queue `CARD.DEMO.REPLY.ACCT`. Problems are reported
as text records on `CARD.DEMO.ERROR`. It is read-only with respect to business data. The module README describes the
CDRA transaction as the requester that sends the request and displays the reply; the code is the listener side only
(Q-COACCT01-01).

## Invocation and state
- Start: the CICS-MQ trigger monitor (CKTI) issues `START TRANSID(CDRA)` with the MQ trigger message (MQTM) as start
  data when the request queue's trigger condition is met. The program issues `EXEC CICS RETRIEVE INTO(MQTM)` and
  takes the request queue name from `MQTM-QNAME` (`COACCT01.cbl:191-198 (1000-CONTROL)`). Typed at a terminal, the
  RETRIEVE fails (ERR-COACCT01-02).
- No COMMAREA, no terminal I/O (the LINKAGE SECTION is empty). Nothing is carried across tasks. Within one task the
  program loops over messages; WORKING-STORAGE fields that are not reset per message keep their values (Q-COACCT01-04,
  Q-COACCT01-16).
- Connection: QMGR-HANDLE-CONN (used for MQOPEN) and MQ-HCONN (used for MQGET, MQPUT, MQCLOSE) are both 0, the
  CICS-MQ adapter's default connection. MQOD-OBJECTQMGRNAME is spaces (local queue manager).
- Queue status flags (`:13-23`): WS-REPLY-QUEUE-STS (88 REPLY-QUEUE-OPEN) is set when the **input** queue is open;
  WS-RESP-QUEUE-STS (88 RESP-QUEUE-OPEN) when the reply queue is open; WS-ERR-QUEUE-STS (88 ERR-QUEUE-OPEN) when the
  error queue is open (Q-COACCT01-13).
- Request message (REQUEST-MSG-COPY, `:109-112`; 1000-byte buffer):

| Bytes | Field | Picture | Meaning |
|:--|:--|:--|:--|
| 1-4 | WS-FUNC | X(04) | Function; only `INQA` is served |
| 5-15 | WS-KEY | 9(11) | Account ID, zoned decimal, zero-padded (for example `00000000001`) |
| 16-1000 | WS-FILLER | X(985) | Ignored |

- Reply message: always 1000 bytes of text (MQFMT-STRING), space-padded. For a found account, the first 252 bytes are
  WS-ACCT-RESPONSE (`:130-169`):

| Bytes | Content | Source field |
|:--|:--|:--|
| 1-13 | `ACCOUNT ID : ` | label |
| 14-24 | account ID, 11 digits | ACCT-ID 9(11) |
| 25-41 | `ACCOUNT STATUS : ` | label |
| 42 | status (`Y` / `N`) | ACCT-ACTIVE-STATUS |
| 43-52 | `BALANCE : ` | label |
| 53-64 | current balance, 12 characters, signed zoned (last character overpunched: `{` = +0, `A`-`I` = +1..+9, `}` = -0, `J`-`R` = -1..-9), 2 implied decimals | ACCT-CURR-BAL S9(10)V99 |
| 65-79 | `CREDIT LIMIT : ` | label |
| 80-91 | credit limit, same format | ACCT-CREDIT-LIMIT |
| 92-104 | `CASH LIMIT : ` | label |
| 105-116 | cash credit limit, same format | ACCT-CASH-CREDIT-LIMIT |
| 117-128 | `OPEN DATE : ` | label |
| 129-138 | open date `YYYY-MM-DD` | ACCT-OPEN-DATE |
| 139-150 | `EXPR DATE : ` | label |
| 151-160 | expiration date | ACCT-EXPIRAION-DATE |
| 161-172 | `REIS DATE : ` | label |
| 173-182 | reissue date | ACCT-REISSUE-DATE |
| 183-195 | `CREDIT BAL : ` | label |
| 196-207 | current-cycle credit, same numeric format | ACCT-CURR-CYC-CREDIT |
| 208-219 | `DEBIT BAL : ` | label |
| 220-231 | current-cycle debit, same numeric format | ACCT-CURR-CYC-DEBIT |
| 232-242 | `GROUP ID : ` | label |
| 243-252 | group ID | ACCT-GROUP-ID X(10) |

- Error record (MQ-ERR-DISPLAY, `:58-67`, 113 bytes, put as a 1000-byte message): MQ-ERROR-PARA X(25), 2 spaces,
  MQ-APPL-RETURN-MESSAGE X(25), 2 spaces, MQ-APPL-CONDITION-CODE 9(02), 2 spaces, MQ-APPL-REASON-CODE 9(05), 2 spaces,
  MQ-APPL-QUEUE-NAME X(48). It is initialized once per task (`:185`), not per error.

## Processing flow
1. 1000-CONTROL (`COACCT01.cbl:178-220`): clear INPUT-QUEUE-NAME, QMGR-NAME, QUEUE-MESSAGE; initialize MQ-ERR-DISPLAY;
   perform 2100-OPEN-ERROR-QUEUE (BR-COACCT01-01).
2. RETRIEVE the trigger message. IF NORMAL: INPUT-QUEUE-NAME = MQTM-QNAME, REPLY-QUEUE-NAME = `CARD.DEMO.REPLY.ACCT`
   (BR-COACCT01-02). OTHERWISE ERR-COACCT01-02 (report and end).
3. 2300-OPEN-INPUT-QUEUE and 2400-OPEN-OUTPUT-QUEUE (BR-COACCT01-03; ERR-COACCT01-03, -04).
4. 3000-GET-REQUEST once; then repeat 4000-MAIN-PROCESS (SYNCPOINT, then 3000-GET-REQUEST) UNTIL NO-MORE-MSGS
   (BR-COACCT01-04, -11).
5. 3000-GET-REQUEST (`:334-388`): MQGET with a 5-second wait. IF OK: save MsgId, CorrelId, ReplyToQ; copy the buffer
   to REQUEST-MESSAGE and REQUEST-MSG-COPY; perform 4000-PROCESS-REQUEST-REPLY; add 1 to MQ-MSG-COUNT. IF reason
   2033 (no message available): set NO-MORE-MSGS. OTHERWISE ERR-COACCT01-05.
6. 4000-PROCESS-REQUEST-REPLY (`:390-460`): clear REPLY-MESSAGE. IF WS-FUNC = `INQA` AND WS-KEY > 0: READ ACCTDAT;
   NORMAL -> account reply (BR-COACCT01-07); NOTFND -> not-found reply (BR-COACCT01-08); other -> ERR-COACCT01-06.
   OTHERWISE -> invalid-request reply (BR-COACCT01-09). Each reply is put by 4100-PUT-REPLY (BR-COACCT01-10).
7. 8000-TERMINATION (`:538-550`): close the open queues (5000, 5100, 5200), `EXEC CICS RETURN`, GOBACK
   (BR-COACCT01-13).

## Rules
### Business rules
- **BR-COACCT01-01**: The error queue `CARD.DEMO.ERROR` is opened first, with options MQOO-OUTPUT +
  MQOO-PASS-ALL-CONTEXT + MQOO-FAIL-IF-QUIESCING; on success its handle is saved and ERR-QUEUE-OPEN set. Source:
  `app/app-vsam-mq/cbl/COACCT01.cbl:289-314 (2100-OPEN-ERROR-QUEUE)`.
- **BR-COACCT01-02**: The request queue is the queue named in the trigger message (MQTM-QNAME); the reply queue is
  always `CARD.DEMO.REPLY.ACCT`, whatever the request's MQMD-REPLYTOQ says (saved in SAVE-REPLY2Q but never used,
  Q-COACCT01-02). Source: `app/app-vsam-mq/cbl/COACCT01.cbl:196-198 (1000-CONTROL)`, `:366`, `:371`.
- **BR-COACCT01-03**: The request queue is opened with MQOO-INPUT-SHARED + MQOO-SAVE-ALL-CONTEXT +
  MQOO-FAIL-IF-QUIESCING (several CDRA tasks may read it at once); the reply queue with MQOO-OUTPUT +
  MQOO-PASS-ALL-CONTEXT + MQOO-FAIL-IF-QUIESCING. Handles are saved in INPUT-QUEUE-HANDLE and OUTPUT-QUEUE-HANDLE.
  Source: `app/app-vsam-mq/cbl/COACCT01.cbl:222-253 (2300-OPEN-INPUT-QUEUE)`, `:255-287 (2400-OPEN-OUTPUT-QUEUE)`.
- **BR-COACCT01-04**: Each MQGET uses MQGMO-SYNCPOINT + MQGMO-FAIL-IF-QUIESCING + MQGMO-CONVERT + MQGMO-WAIT with
  MQGMO-WAITINTERVAL = 5000 ms, buffer length 1000, MQMD-MSGID = MQMI-NONE and MQMD-CORRELID = MQCI-NONE (next
  available message, any IDs); before the call `INITIALIZE REQUEST-MSG-COPY REPLACING NUMERIC BY ZEROES` sets only
  the numeric key WS-KEY to zero (WS-FUNC and WS-FILLER keep their previous contents; this is not observable,
  because a received message is copied over the whole group before WS-FUNC is read). The task
  ends when no message arrives within 5 seconds (reason MQRC-NO-MSG-AVAILABLE, 2033). Source:
  `app/app-vsam-mq/cbl/COACCT01.cbl:337-378 (3000-GET-REQUEST)`.
- **BR-COACCT01-05**: The request is interpreted positionally: bytes 1-4 function, bytes 5-15 account ID; the rest
  is ignored. The whole 1000-byte buffer is copied, including bytes beyond the received message length
  (Q-COACCT01-04). Source: `app/app-vsam-mq/cbl/COACCT01.cbl:369`, `:373`, `:109-112`.
- **BR-COACCT01-06**: When WS-FUNC = `INQA` AND WS-KEY > 0, the account is read:
  `EXEC CICS READ DATASET('ACCTDAT ') RIDFLD(WS-CARD-RID-ACCT-ID-X) KEYLENGTH(11) INTO(ACCOUNT-RECORD) LENGTH(300)
  RESP RESP2`, where the key is WS-KEY moved to the 11-digit WS-CARD-RID-ACCT-ID. The read is not for update.
  Source: `app/app-vsam-mq/cbl/COACCT01.cbl:393-404 (4000-PROCESS-REQUEST-REPLY)`.
- **BR-COACCT01-07**: RESP NORMAL -> ACCT-ID, ACCT-ACTIVE-STATUS, ACCT-CURR-BAL, ACCT-CREDIT-LIMIT,
  ACCT-CASH-CREDIT-LIMIT, ACCT-OPEN-DATE, ACCT-EXPIRAION-DATE, ACCT-REISSUE-DATE, ACCT-CURR-CYC-CREDIT,
  ACCT-CURR-CYC-DEBIT, ACCT-GROUP-ID are moved into WS-ACCT-RESPONSE, which becomes the reply text (layout in
  Invocation and state; ACCT-ADDR-ZIP is not returned). For sample account `00000000001` the reply starts
  `ACCOUNT ID : 00000000001ACCOUNT STATUS : YBALANCE : 00000001940{CREDIT LIMIT : 00000020200{CASH LIMIT :
  00000010200{OPEN DATE : 2014-11-20EXPR DATE : 2025-05-20REIS DATE : 2025-05-20CREDIT BAL : 00000000000{DEBIT BAL
  : 00000000000{GROUP ID : ` followed by spaces. Source: `app/app-vsam-mq/cbl/COACCT01.cbl:406-427 (4000-PROCESS-REQUEST-REPLY)`,
  `app/data/ASCII/acctdata.txt:1`.
- **BR-COACCT01-08**: RESP NOTFND -> reply text "INVALID REQUEST PARAMETERS ACCT ID : " + WS-KEY (11 digits), for
  example `INVALID REQUEST PARAMETERS ACCT ID : 00000099999`. Source:
  `app/app-vsam-mq/cbl/COACCT01.cbl:428-435 (4000-PROCESS-REQUEST-REPLY)`.
- **BR-COACCT01-09**: When the function is not `INQA` or the key is not greater than zero -> reply text "INVALID
  REQUEST PARAMETERS ACCT ID : " + WS-KEY + "FUNCTION : " + WS-FUNC, with no space between the key and `FUNCTION`
  (for example `INVALID REQUEST PARAMETERS ACCT ID : 00000000001FUNCTION : INQX`). Source:
  `app/app-vsam-mq/cbl/COACCT01.cbl:448-456 (4000-PROCESS-REQUEST-REPLY)`.
- **BR-COACCT01-10**: Reply PUT (4100-PUT-REPLY): buffer = REPLY-MESSAGE, length 1000; MQMD-MSGID = the request's
  MsgId; MQMD-CORRELID = the request's CorrelId (not its MsgId, Q-COACCT01-03); MQMD-FORMAT = MQFMT-STRING;
  MQMD-CODEDCHARSETID = MQCCSI-Q-MGR; all other MQMD fields (MsgType, Persistence, Expiry, Priority, ReplyToQ,
  Report, Encoding) are left as returned by the GET. PMO options MQPMO-SYNCPOINT + MQPMO-DEFAULT-CONTEXT +
  MQPMO-FAIL-IF-QUIESCING; put to the reply queue handle. Source:
  `app/app-vsam-mq/cbl/COACCT01.cbl:462-499 (4100-PUT-REPLY)`.
- **BR-COACCT01-11**: Unit of work: GET and PUT are under syncpoint. 4000-MAIN-PROCESS issues `EXEC CICS SYNCPOINT`
  before every GET after the first, so each request and its reply are committed together before the next request
  is read; the last pair is committed by the normal end of the task (`EXEC CICS RETURN`). Source:
  `app/app-vsam-mq/cbl/COACCT01.cbl:214-218 (1000-CONTROL)`, `:325-331 (4000-MAIN-PROCESS)`.
- **BR-COACCT01-12**: MQ-MSG-COUNT is incremented after each processed request and never used (Q-COACCT01-12).
  Source: `app/app-vsam-mq/cbl/COACCT01.cbl:375`.
- **BR-COACCT01-13**: Termination: IF REPLY-QUEUE-OPEN close the input queue; IF RESP-QUEUE-OPEN close the reply
  queue; IF ERR-QUEUE-OPEN close the error queue (each with MQCO-NONE); then `EXEC CICS RETURN` (no TRANSID) and
  GOBACK. Every error path ends here, so the task never continues after an error. Source:
  `app/app-vsam-mq/cbl/COACCT01.cbl:538-550 (8000-TERMINATION)`.
- **BR-COACCT01-14**: Error reporting (9000-ERROR): MQ-ERR-DISPLAY is moved to a 1000-byte buffer and put to the error
  queue with MQFMT-STRING, MQCCSI-Q-MGR, MQPMO-SYNCPOINT + MQPMO-DEFAULT-CONTEXT + MQPMO-FAIL-IF-QUIESCING, using the
  MQMD left by the last GET or PUT (so it carries the last request's MsgId and CorrelId). Being under syncpoint, the
  error message is committed only when the task ends normally. Source:
  `app/app-vsam-mq/cbl/COACCT01.cbl:501-537 (9000-ERROR)`.

### Validation rules
- **VR-COACCT01-01**: The function must be exactly `INQA` (upper case) and the account ID must be numeric and greater
  than zero; otherwise the invalid-request reply of BR-COACCT01-09 is sent. A non-numeric key compares unpredictably
  (Q-COACCT01-06). Source: `app/app-vsam-mq/cbl/COACCT01.cbl:393`, `:448-456`.
- **VR-COACCT01-02**: The account must exist in ACCTDAT; otherwise the not-found reply of BR-COACCT01-08. Source:
  `app/app-vsam-mq/cbl/COACCT01.cbl:428-435`.
- **VR-COACCT01-03**: The request must not exceed 1000 bytes; otherwise the GET fails (ERR-COACCT01-05,
  Q-COACCT01-08). No other validation (for example, of the requester or of the account status) is made.

### Navigation and keys
- **NAV-COACCT01-01**: No terminal and no keys. The task is started only by the trigger monitor (BR-COACCT01-02),
  processes messages until the queue stays empty for 5 seconds, and ends with `EXEC CICS RETURN` without a TRANSID
  (BR-COACCT01-13).

### Error handling
- **ERR-COACCT01-01**: Error-queue MQOPEN not OK -> MQ-APPL-CONDITION-CODE/REASON-CODE = the MQ codes,
  MQ-APPL-QUEUE-NAME = `CARD.DEMO.ERROR`, MQ-APPL-RETURN-MESSAGE = `ERR MQOPEN ERR`; `DISPLAY MQ-ERR-DISPLAY` (LE
  writes CICS DISPLAY output to a transient-data queue, normally CESE); 8000-TERMINATION. No request is read. Source:
  `app/app-vsam-mq/cbl/COACCT01.cbl:315-321 (2100-OPEN-ERROR-QUEUE)`.
- **ERR-COACCT01-02**: RETRIEVE RESP not NORMAL -> MQ-ERROR-PARA = `CICS RETREIVE` (misspelled), MQ-APPL-RETURN-MESSAGE
  = "RESP: " + RESP (8 digits) + RESP2 (8 digits, always `00000000`, Q-COACCT01-10) + "END" (exactly 25 characters),
  then 9000-ERROR and 8000-TERMINATION. Source: `app/app-vsam-mq/cbl/COACCT01.cbl:199-209 (1000-CONTROL)`.
- **ERR-COACCT01-03**: Input MQOPEN not OK -> codes, queue = request queue name, `INP MQOPEN ERR`; 9000-ERROR;
  8000-TERMINATION. Source: `app/app-vsam-mq/cbl/COACCT01.cbl:246-252 (2300-OPEN-INPUT-QUEUE)`.
- **ERR-COACCT01-04**: Reply MQOPEN not OK -> codes, queue = `CARD.DEMO.REPLY.ACCT`, `OUT MQOPEN ERR`; 9000-ERROR;
  8000-TERMINATION (closes the already open input and error queues). Source:
  `app/app-vsam-mq/cbl/COACCT01.cbl:280-286 (2400-OPEN-OUTPUT-QUEUE)`.
- **ERR-COACCT01-05**: MQGET with any completion code other than MQCC-OK and a reason other than 2033 (including
  MQCC-WARNING with 2080 truncated message, and 2016 get inhibited) -> codes, queue = request queue,
  `INP MQGET ERR:`; 9000-ERROR; 8000-TERMINATION. The task ends normally, committing earlier work. Source:
  `app/app-vsam-mq/cbl/COACCT01.cbl:380-387 (3000-GET-REQUEST)`.
- **ERR-COACCT01-06**: READ ACCTDAT RESP other than NORMAL or NOTFND (for example NOTOPEN 19, DISABLED 84, IOERR 17)
  -> MQ-APPL-CONDITION-CODE = RESP (last 2 digits), MQ-APPL-REASON-CODE = RESP2 (last 5 digits), queue = request
  queue, MQ-APPL-RETURN-MESSAGE = `ERROR WHILE READING ACCTF` (the 28-character literal "ERROR WHILE READING
  ACCTFILE" truncated to 25); 9000-ERROR; 8000-TERMINATION. No reply is sent, and the normal task end commits the
  GET, so the request is consumed (Q-COACCT01-07). Source: `app/app-vsam-mq/cbl/COACCT01.cbl:437-445 (4000-PROCESS-REQUEST-REPLY)`.
- **ERR-COACCT01-07**: Reply MQPUT not OK -> codes, queue = `CARD.DEMO.REPLY.ACCT`, `MQPUT ERR`; 9000-ERROR;
  8000-TERMINATION; the request is consumed without a reply. Source:
  `app/app-vsam-mq/cbl/COACCT01.cbl:492-498 (4100-PUT-REPLY)`.
- **ERR-COACCT01-08**: Error-queue MQPUT not OK -> codes, queue = `CARD.DEMO.ERROR`, `MQPUT ERR`; `DISPLAY
  MQ-ERR-DISPLAY`; 8000-TERMINATION. Source: `app/app-vsam-mq/cbl/COACCT01.cbl:529-535 (9000-ERROR)`.
- **ERR-COACCT01-09**: MQCLOSE not OK: on the input queue -> codes, queue = request queue, `MQCLOSE ERR`, then
  8000-TERMINATION again; on the reply queue -> the same but the queue name recorded is the **request** queue;
  neither is written to the error queue. On the error queue -> codes, queue `CARD.DEMO.ERROR`, `MQCLOSE ERR`,
  9000-ERROR, 8000-TERMINATION. Because the open flags are not reset, a persistent close failure re-enters the same
  close without end (Q-COACCT01-09). Source: `app/app-vsam-mq/cbl/COACCT01.cbl:552-619 (5000, 5100, 5200)`.
- **ERR-COACCT01-10**: There is no HANDLE ABEND. An abend (for example a data exception) ends the task with
  `ACTION(BACKOUT)`: the in-flight GET is backed out (the request returns to the queue with an incremented backout
  count) and uncommitted PUTs are discarded. The program never checks MQMD-BACKOUTCOUNT (Q-COACCT01-08).
- **ERR-COACCT01-11**: A completion code of MQCC-WARNING is treated as failure on every call (only MQCC-OK passes).

## Data effects
- VSAM ACCTDAT: read only, one keyed READ per valid `INQA` request; no update, no browse.
- MQ, in this order per request: destructive GET from the triggered request queue (syncpoint), PUT of one reply to
  `CARD.DEMO.REPLY.ACCT` (syncpoint), committed together by the next SYNCPOINT or the task end. Error paths PUT one
  record to `CARD.DEMO.ERROR` and end the task normally, which commits the consumed request and the error record.
- No Db2, no other files. Nothing is written to CICS logs except DISPLAY on two error paths.
- Interaction with the base application: shares the ACCTDAT file with COACTVWC, COACTUPC, COBIL00C and the batch
  programs (ENT-ACCOUNT); a reply reflects the committed VSAM state at read time, with no locking.
- Modern equivalent: a message listener (for example a JMS `@JmsListener` on the request queue, or an SQS/Kafka
  consumer) that replies to `JMSReplyTo` with `JMSCorrelationID` = the request `JMSMessageID`, a JSON body with
  decimal amounts and ISO dates, and a dead-letter queue after N failed deliveries; or a synchronous REST endpoint
  `GET /accounts/{accountId}` (the base account view already offers the same data).

## Quirks
- **Q-COACCT01-01**: The module README does not match the code: it names queues `CARDDEMO.REQUEST.QUEUE` and
  `CARDDEMO.RESPONSE.QUEUE`, describes CDRA as a terminal transaction that sends a request and displays the reply,
  and shows request/response layouts (`ACCT` type, 8-byte request ID, 300-byte account data) that no program uses.
  The code is a trigger-started listener using `CARD.DEMO.REPLY.ACCT` and `CARD.DEMO.ERROR`, function `INQA`, and the
  labelled text reply. The CSD has no MQCONN, MQMONITOR, or trigger process definitions. Recommendation: decide
  (treat the code as the contract; document the queue setup). Source: `app/app-vsam-mq/README.md`, `csd/CRDDEMOM.csd`.
- **Q-COACCT01-02**: MQMD-REPLYTOQ / ReplyToQMgr of the request are ignored; replies always go to the fixed local queue
  `CARD.DEMO.REPLY.ACCT`, so requesters cannot choose a temporary or remote reply queue. Recommendation: fix (honor
  ReplyToQ, default to the fixed queue). Source: `COACCT01.cbl:198`, `:366`, `:371`.
- **Q-COACCT01-03**: Correlation does not follow the MQ request/reply convention (reply CorrelId = request MsgId).
  The reply copies the request's CorrelId (MQCI-NONE unless the requester set one) and reuses the request's MsgId,
  so with default requester settings every reply has a zero CorrelId and the requester must match on MsgId or set
  its own CorrelId. The reply also keeps the request's MsgType, Report options, Expiry, and Persistence. Recommendation:
  fix (CorrelId = request MsgId, new MsgId, MsgType = MQMT-REPLY). Source: `COACCT01.cbl:364-372`, `:469-470`.
- **Q-COACCT01-04**: MQ-BUFFER is not cleared before each GET and MQ-DATA-LENGTH is never used, so bytes beyond the
  received length come from the previous request (or from uninitialized storage for the first one). A request
  shorter than 15 bytes gets part of its key from the previous message. Replies are always 1000 bytes. Recommendation:
  fix (use the data length; reject short messages). Source: `COACCT01.cbl:342`, `:352-373`, `:467-468`.
- **Q-COACCT01-05**: The reply is human-readable text without separators; amounts are signed zoned decimals with an
  overpunched last character and implied decimals (`00000001940{` = 194.00), which a non-COBOL consumer must decode;
  the code page is the queue manager's. Recommendation: decide (structured JSON or fixed layout with explicit sign
  and decimal point in the target).
- **Q-COACCT01-06**: WS-KEY is PIC 9(11) filled from raw message bytes; spaces or letters in bytes 5-15 make the
  `WS-KEY > ZEROES` test and the MOVE operate on invalid numeric data (result depends on compiler options and may be a
  data exception, ERR-COACCT01-10). Recommendation: fix (test NUMERIC first). Source: `COACCT01.cbl:111`, `:393-394`.
- **Q-COACCT01-07**: On a READ error, an MQPUT error, or an MQGET error, the program reports to the error queue and
  ends the task normally, which commits the consumed request; the requester gets no reply and waits for its timeout.
  Recommendation: fix (send an error reply, or roll back so the request is retried). Source: `COACCT01.cbl:437-445`,
  `:492-498`, `:538-550`.
- **Q-COACCT01-08**: No poison-message handling: a request longer than 1000 bytes fails the GET with 2080 and stays
  on the queue, so each new trigger starts a task that fails on it again; an abend backs a request out with no
  backout-threshold check. Remaining requests behind it are not processed by that task. Recommendation: fix (accept
  truncation or move to a backout queue after the threshold). Source: `COACCT01.cbl:342`, `:347-350`, `:380-387`.
- **Q-COACCT01-09**: Close failures recurse: 5000 and 5100 call 8000-TERMINATION, which calls them again because the
  open flags are still `Y`; 5200 does 9000-ERROR and 8000 again. A persistent close error loops until the task
  abends (stack or runaway). 5100 also records the request queue name instead of the reply queue name. Recommendation:
  fix (reset the flag before closing; report the right queue). Source: `COACCT01.cbl:538-619`, `:592`.
- **Q-COACCT01-10**: In the RETRIEVE error path the program moves RESP2 to itself instead of to its display field,
  so the reported RESP2 is always zero; the paragraph name is misspelled `CICS RETREIVE` (CODATE01 spells it
  correctly). Recommendation: fix. Source: `COACCT01.cbl:200-202`.
- **Q-COACCT01-11**: The READ error record puts the CICS RESP code in the MQ condition-code field (2 digits) and RESP2
  in the MQ reason-code field, and the message is truncated to `ERROR WHILE READING ACCTF`. Recommendation: fix
  (separate fields for CICS and MQ codes). Source: `COACCT01.cbl:439-443`.
- **Q-COACCT01-12**: MQ-MSG-COUNT has no VALUE clause and is incremented with ADD; with uninitialized storage (binary
  zeros) this can raise a data exception after the first request, depending on compiler options. The counter is
  never used. Recommendation: fix (initialize or drop). Source: `COACCT01.cbl:54`, `:375`.
- **Q-COACCT01-13**: Flag names are swapped: REPLY-QUEUE-OPEN means the input (request) queue is open and
  RESP-QUEUE-OPEN means the reply queue is open. Behavior is consistent. Recommendation: preserve behavior, rename.
  Source: `COACCT01.cbl:16-23`, `:245`, `:279`, `:540-545`.
- **Q-COACCT01-14**: Dead or redundant items: QUEUE-MESSAGE, SAVE-REPLY2Q, MQ-QUEUE, MQ-QUEUE-REPLY, QMGR-NAME,
  WS-DATE-TIME (initialized per request, never used), WS-CARD-RID-CARDNUM/-CUST-ID, ERROR-MESSAGE (only an
  intermediate copy), the empty LINKAGE SECTION, `IS INITIAL`; queues are opened with MQOO-PASS-ALL-CONTEXT but put
  with MQPMO-DEFAULT-CONTEXT, and the input queue's SAVE-ALL-CONTEXT is never used. Two different connection-handle
  variables (both 0) are used. Recommendation: drop in the target.
- **Q-COACCT01-15**: No security: any application that can put a message on the triggered queue receives any
  account's status, balance, and limits; the requester's identity in the MQMD context is not checked. Recommendation:
  fix (authenticate and authorize requesters in the target).
- **Q-COACCT01-16**: MQ-ERR-DISPLAY is initialized once per task, so an error record can carry a queue name or
  paragraph text from an earlier step, and MQ-ERROR-PARA is filled only on the RETRIEVE path (blank otherwise).
  Recommendation: fix. Source: `COACCT01.cbl:185`, `:200`.
- **Q-COACCT01-17**: The 5-second MQGET wait keeps each CDRA task alive after the last message; with
  MQOO-INPUT-SHARED and trigger type EVERY, many tasks can run in parallel. Recommendation: preserve (normal listener
  pattern); size the trigger settings in the target.

## Test scenarios
Unless stated otherwise: base VSAM ACCTDAT loaded from `app/data/ASCII/acctdata.txt` (account `00000000001`: status
`Y`, balance 194.00, credit limit 2020.00, cash limit 1020.00, opened 2014-11-20, expires and reissued 2025-05-20,
cycle credit and debit 0, group ID blank); queues `CARD.DEMO.REPLY.ACCT` and `CARD.DEMO.ERROR` defined; a request
queue with a trigger process for CDRA.
- **TS-COACCT01-01** (covers BR-COACCT01-04..07, -10, -11): Given request `INQA00000000001` with MsgId M1 and CorrelId
  C1, when it is put on the request queue, then CDRA starts and one 1000-byte reply appears on `CARD.DEMO.REPLY.ACCT`
  with MsgId M1, CorrelId C1, format MQSTR, text starting `ACCOUNT ID : 00000000001ACCOUNT STATUS : YBALANCE :
  00000001940{CREDIT LIMIT : 00000020200{`; the request is removed and the task ends after 5 idle seconds.
- **TS-COACCT01-02** (covers BR-COACCT01-08, VR-COACCT01-02): Given request `INQA00000099999` (no such account), then
  the reply text is `INVALID REQUEST PARAMETERS ACCT ID : 00000099999`.
- **TS-COACCT01-03** (covers BR-COACCT01-09, VR-COACCT01-01): Given request `INQX00000000001`, then the reply text is
  `INVALID REQUEST PARAMETERS ACCT ID : 00000000001FUNCTION : INQX`.
- **TS-COACCT01-04** (covers VR-COACCT01-01): Given request `INQA00000000000`, then the invalid-request reply with
  `FUNCTION : INQA`.
- **TS-COACCT01-05** (covers BR-COACCT01-11): Given three valid requests queued before the trigger, when CDRA runs, then
  three replies are produced in queue order and a SYNCPOINT is taken before the second and third GET.
- **TS-COACCT01-06** (covers BR-COACCT01-02, Q-COACCT01-02): Given a request whose MQMD-REPLYTOQ is `MY.REPLY.Q`, then
  the reply still goes to `CARD.DEMO.REPLY.ACCT`.
- **TS-COACCT01-07** (covers Q-COACCT01-03): Given a request with default CorrelId (MQCI-NONE), then the reply's
  CorrelId is all binary zeros and its MsgId equals the request's MsgId.
- **TS-COACCT01-08** (covers ERR-COACCT01-06, Q-COACCT01-07): Given ACCTDAT is closed (NOTOPEN), when a valid request
  arrives, then no reply is sent, `CARD.DEMO.ERROR` receives a record with return message `ERROR WHILE READING ACCTF`,
  condition code `19`, and the request is no longer on the queue.
- **TS-COACCT01-09** (covers ERR-COACCT01-05, Q-COACCT01-08): Given a 1200-byte request, when CDRA runs, then the error
  queue receives `INP MQGET ERR:` with reason `02080` and the request stays on the queue.
- **TS-COACCT01-10** (covers ERR-COACCT01-02, Q-COACCT01-10): Given CDRA is typed at a terminal, then RETRIEVE fails, and
  the error queue receives paragraph `CICS RETREIVE` with message `RESP: ` + the 8-digit RESP + `00000000END`.
- **TS-COACCT01-11** (covers ERR-COACCT01-01): Given `CARD.DEMO.ERROR` is not defined, when CDRA is triggered, then a
  DISPLAY line with `ERR MQOPEN ERR` and reason `02085` is written and no request is processed.
- **TS-COACCT01-12** (covers ERR-COACCT01-04): Given `CARD.DEMO.REPLY.ACCT` is put-inhibited at open time (or not
  defined), when CDRA is triggered, then the error queue receives `OUT MQOPEN ERR` with that queue name.
- **TS-COACCT01-13** (covers ERR-COACCT01-07): Given the reply queue becomes full after open, when a request is
  processed, then the error queue receives `MQPUT ERR` (reason `02053`) and the request is consumed without a reply.
- **TS-COACCT01-14** (covers Q-COACCT01-04): Given a request `INQA00000000002` followed by a request `INQA1`, then the
  second is read with key `1` + `0000000002`(the stale bytes 6-15 of the first) and answers for that value.
- **TS-COACCT01-15** (covers NAV-COACCT01-01, BR-COACCT01-13): Given the request queue is empty when CDRA starts, then
  the first GET waits 5 seconds, returns 2033, all three queues are closed, and the task ends without any PUT.

## Open questions
- What are the real request-queue name, trigger type (FIRST or EVERY), initiation queue, and process definition for
  CDRA? None are in the repository.
- Which side is the intended requester (the README's terminal transaction does not exist)? Is there a client that
  matches on MsgId?
- Should a READ error or an MQPUT failure leave the request on the queue for retry instead of consuming it?
- In the sample data, `A000000000` sits in ACCT-ADDR-ZIP and ACCT-GROUP-ID is blank; is the group ID expected to be
  filled (the reply returns the group, not the ZIP)?
- Is the zoned-decimal text format relied on by any consumer?
