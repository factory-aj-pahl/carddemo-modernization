# CODATE01: MQ-triggered listener that answers every request message with the CICS system date and time

| Attribute | Value |
|:--|:--|
| Source | `app/app-vsam-mq/cbl/CODATE01.cbl` (524 lines), `PROGRAM-ID. CODATE01 IS INITIAL` |
| Module | app-vsam-mq |
| Kind | Online (CICS), MQ-triggered background task with no terminal; IBM MQ API calls (MQOPEN, MQGET, MQPUT, MQCLOSE) through the CICS-MQ adapter |
| Transaction ID / Job | CDRD (program `app/app-vsam-mq/csd/CRDDEMOM.csd:9-16` with `TRANSID(CDRD)`, `RELOAD(NO)`, description copied as `LIST CARDS`; transaction `:27-36`, PROFILE DFHCICST, `ACTION(BACKOUT)`; LIBRARY `CARDDLIB` = `AWS.M2.CARDDEMO.LOADLIB`, `:37-41`). Started by the CICS-MQ trigger monitor; the MQ process, initiation queue, and queue definitions are not in the repository. |
| BMS map | none |
| Copybooks | IBM MQ only: `CMQGMOV` (MQGMO), `CMQPMOV` (MQPMO), `CMQMDV` (MQMD), `CMQODV` (MQOD), `CMQV` (constants), `CMQTML` (MQTM trigger message). No application copybook. |
| Calls / XCTL targets | `CALL 'MQOPEN'`, `'MQGET'`, `'MQPUT'`, `'MQCLOSE'`; `EXEC CICS ASKTIME`, `FORMATTIME`, `RETRIEVE`, `SYNCPOINT`, `RETURN`; no XCTL or LINK |
| Data stores | No files, no Db2. MQ: triggered request queue (name from the trigger message): destructive GET under syncpoint; `CARD.DEMO.REPLY.DATE`: PUT; `CARD.DEMO.ERROR`: PUT. Message layouts: ENT-MQ-ACCOUNT-INQUIRY (shared request copy; date reply described in `modernization/spec/entities.md:838`). |

## Purpose
CODATE01 is a server program that answers "what is the system date and time" requests arriving as MQ messages.
When a request queue receives messages, MQ triggers transaction CDRD; the program reads every waiting message and,
for each one, puts a fixed text reply with the CICS region's current local date and time on the reply queue
`CARD.DEMO.REPLY.DATE`. The content of the request is not examined. Problems are reported as text records on
`CARD.DEMO.ERROR`. It reads and changes no business data. It is a copy of the COACCT01 listener skeleton with the
account lookup replaced by a clock read. The module README describes CDRD as the terminal requester that sends the
request and displays the reply, with `DATE`-typed request and response layouts; the code is the listener side only
and uses neither layout (Q-CODATE01-01).

## Invocation and state
- Start: the CICS-MQ trigger monitor (CKTI) issues `START TRANSID(CDRD)` with the MQ trigger message (MQTM) as start
  data when the request queue's trigger condition is met. The program issues `EXEC CICS RETRIEVE INTO(MQTM)` and
  takes the request queue name from `MQTM-QNAME` (`CODATE01.cbl:140-147 (1000-CONTROL)`). Typed at a terminal, the
  RETRIEVE fails (ERR-CODATE01-02).
- No COMMAREA, no terminal I/O (the LINKAGE SECTION is empty). Nothing is carried across tasks. Within one task the
  program loops over messages; WORKING-STORAGE fields that are not reset per message keep their values
  (Q-CODATE01-12).
- Connection: QMGR-HANDLE-CONN (used for MQOPEN) and MQ-HCONN (used for MQGET, MQPUT, MQCLOSE) are both 0, the
  CICS-MQ adapter's default connection. MQOD-OBJECTQMGRNAME is spaces (local queue manager).
- Queue status flags (`:13-23`): WS-REPLY-QUEUE-STS (88 REPLY-QUEUE-OPEN) is set when the **input** queue is open;
  WS-RESP-QUEUE-STS (88 RESP-QUEUE-OPEN) when the reply queue is open; WS-ERR-QUEUE-STS (88 ERR-QUEUE-OPEN) when the
  error queue is open (Q-CODATE01-11).
- Request message: any content from 0 to 1000 bytes. The buffer is copied into REQUEST-MESSAGE and into
  REQUEST-MSG-COPY (`:109-112`: WS-FUNC X(04), WS-KEY 9(11), WS-FILLER X(985), the same layout COACCT01 uses for
  `INQA` requests), but no statement reads either copy (BR-CODATE01-05).
- Reply message: always 1000 bytes of text (MQFMT-STRING), space-padded. The first 46 bytes are:

| Bytes | Content | Source |
|:--|:--|:--|
| 1-14 | `SYSTEM DATE : ` | literal |
| 15-24 | date `mm-dd-yyyy` (month first, `-` separators) | WS-MMDDYYYY X(10) from FORMATTIME MMDDYYYY DATESEP('-') |
| 25-38 | `SYSTEM TIME : ` | literal |
| 39-46 | time `hh:mm:ss` (24-hour, `:` separators) | WS-TIME X(8) from FORMATTIME TIME TIMESEP |
| 47-1000 | spaces | REPLY-MESSAGE cleared per request |

  Example: `SYSTEM DATE : 03-21-2024SYSTEM TIME : 14:05:09` followed by 954 spaces. There is no separator between
  the date and the `SYSTEM TIME` label and no time zone (Q-CODATE01-03).
- Error record (MQ-ERR-DISPLAY, `:58-67`, 113 bytes, put as a 1000-byte message): MQ-ERROR-PARA X(25), 2 spaces,
  MQ-APPL-RETURN-MESSAGE X(25), 2 spaces, MQ-APPL-CONDITION-CODE 9(02), 2 spaces, MQ-APPL-REASON-CODE 9(05), 2 spaces,
  MQ-APPL-QUEUE-NAME X(48). It is initialized once per task (`:134`), not per error.

## Processing flow
1. 1000-CONTROL (`CODATE01.cbl:127-169`): clear INPUT-QUEUE-NAME, QMGR-NAME, QUEUE-MESSAGE; initialize MQ-ERR-DISPLAY;
   perform 2100-OPEN-ERROR-QUEUE (BR-CODATE01-01).
2. RETRIEVE the trigger message. IF NORMAL: INPUT-QUEUE-NAME = MQTM-QNAME, REPLY-QUEUE-NAME = `CARD.DEMO.REPLY.DATE`
   (BR-CODATE01-02). OTHERWISE ERR-CODATE01-02 (report and end).
3. 2300-OPEN-INPUT-QUEUE and 2400-OPEN-OUTPUT-QUEUE (BR-CODATE01-03; ERR-CODATE01-03, -04).
4. 3000-GET-REQUEST once; then repeat 4000-MAIN-PROCESS (SYNCPOINT, then 3000-GET-REQUEST) UNTIL NO-MORE-MSGS
   (BR-CODATE01-04, -09).
5. 3000-GET-REQUEST (`:283-337`): MQGET with a 5-second wait. IF OK: save MsgId, CorrelId, ReplyToQ; copy the buffer
   to REQUEST-MESSAGE and REQUEST-MSG-COPY; perform 4000-PROCESS-REQUEST-REPLY; add 1 to MQ-MSG-COUNT. IF reason
   2033 (no message available): set NO-MORE-MSGS. OTHERWISE ERR-CODATE01-05.
6. 4000-PROCESS-REQUEST-REPLY (`:339-364`): clear REPLY-MESSAGE and WS-DATE-TIME; ASKTIME; FORMATTIME; build the reply
   text (BR-CODATE01-06, -07); perform 4100-PUT-REPLY (BR-CODATE01-08).
7. 8000-TERMINATION (`:442-454`): close the open queues (5000, 5100, 5200), `EXEC CICS RETURN`, GOBACK
   (BR-CODATE01-11).

## Rules
### Business rules
- **BR-CODATE01-01**: The error queue `CARD.DEMO.ERROR` is opened first, with options MQOO-OUTPUT +
  MQOO-PASS-ALL-CONTEXT + MQOO-FAIL-IF-QUIESCING; on success its handle is saved in ERROR-QUEUE-HANDLE and
  ERR-QUEUE-OPEN set. Source: `app/app-vsam-mq/cbl/CODATE01.cbl:238-263 (2100-OPEN-ERROR-QUEUE)`,
  `:136 (1000-CONTROL)`.
- **BR-CODATE01-02**: The request queue is the queue named in the trigger message (MQTM-QNAME); the reply queue is
  always `CARD.DEMO.REPLY.DATE`, whatever the request's MQMD-REPLYTOQ says (saved in SAVE-REPLY2Q but never used,
  Q-CODATE01-04). Source: `app/app-vsam-mq/cbl/CODATE01.cbl:145-147 (1000-CONTROL)`, `:315`, `:320`.
- **BR-CODATE01-03**: The request queue is opened with MQOO-INPUT-SHARED + MQOO-SAVE-ALL-CONTEXT +
  MQOO-FAIL-IF-QUIESCING (several CDRD tasks may read it at once); the reply queue with MQOO-OUTPUT +
  MQOO-PASS-ALL-CONTEXT + MQOO-FAIL-IF-QUIESCING. Handles are saved in INPUT-QUEUE-HANDLE and OUTPUT-QUEUE-HANDLE.
  Source: `app/app-vsam-mq/cbl/CODATE01.cbl:171-202 (2300-OPEN-INPUT-QUEUE)`, `:204-236 (2400-OPEN-OUTPUT-QUEUE)`.
- **BR-CODATE01-04**: Each MQGET uses MQGMO-SYNCPOINT + MQGMO-FAIL-IF-QUIESCING + MQGMO-CONVERT + MQGMO-WAIT with
  MQGMO-WAITINTERVAL = 5000 ms, buffer length 1000, MQMD-MSGID = MQMI-NONE and MQMD-CORRELID = MQCI-NONE (next
  available message, any IDs). The task ends when no message arrives within 5 seconds (reason
  MQRC-NO-MSG-AVAILABLE, 2033). Source: `app/app-vsam-mq/cbl/CODATE01.cbl:283-327 (3000-GET-REQUEST)`.
- **BR-CODATE01-05**: The request content is not interpreted. Every message that the GET returns with MQCC-OK,
  including an empty message or one with any function code or key, receives exactly one date reply. The buffer is
  copied to REQUEST-MESSAGE and REQUEST-MSG-COPY, and neither is read afterwards (Q-CODATE01-02). Source:
  `app/app-vsam-mq/cbl/CODATE01.cbl:312-324 (3000-GET-REQUEST)`, `:339-364 (4000-PROCESS-REQUEST-REPLY)`.
- **BR-CODATE01-06**: The time is taken once per request, at processing time: `EXEC CICS ASKTIME ABSTIME(WS-ABS-TIME)`
  then `EXEC CICS FORMATTIME ABSTIME(WS-ABS-TIME) MMDDYYYY(WS-MMDDYYYY) DATESEP('-') TIME(WS-TIME) TIMESEP`.
  MMDDYYYY with DATESEP('-') yields `mm-dd-yyyy`; TIME with TIMESEP and no separator character yields `hh:mm:ss`
  (default separator `:`, 24-hour clock). The values are the CICS region's local date and time. Neither command has
  a RESP option (ERR-CODATE01-09). Source: `app/app-vsam-mq/cbl/CODATE01.cbl:340-353 (4000-PROCESS-REQUEST-REPLY)`.
- **BR-CODATE01-07**: Reply text = `'SYSTEM DATE : '` + WS-MMDDYYYY + `'SYSTEM TIME : '` + WS-TIME, all DELIMITED BY
  SIZE, into REPLY-MESSAGE (cleared to spaces first), giving the 46-byte layout in Invocation and state, for example
  `SYSTEM DATE : 03-21-2024SYSTEM TIME : 14:05:09`. Source:
  `app/app-vsam-mq/cbl/CODATE01.cbl:355-360 (4000-PROCESS-REQUEST-REPLY)`.
- **BR-CODATE01-08**: Reply PUT (4100-PUT-REPLY): buffer = REPLY-MESSAGE, length 1000; MQMD-MSGID = the request's
  MsgId; MQMD-CORRELID = the request's CorrelId (not its MsgId, Q-CODATE01-05); MQMD-FORMAT = MQFMT-STRING;
  MQMD-CODEDCHARSETID = MQCCSI-Q-MGR; all other MQMD fields (MsgType, Persistence, Expiry, Priority, ReplyToQ,
  Report, Encoding) are left as returned by the GET. PMO options MQPMO-SYNCPOINT + MQPMO-DEFAULT-CONTEXT +
  MQPMO-FAIL-IF-QUIESCING; put to the reply queue handle. Source:
  `app/app-vsam-mq/cbl/CODATE01.cbl:366-390 (4100-PUT-REPLY)`.
- **BR-CODATE01-09**: Unit of work: GET and PUT are under syncpoint. 4000-MAIN-PROCESS issues `EXEC CICS SYNCPOINT`
  before every GET after the first, so each request and its reply are committed together before the next request
  is read; the last pair is committed by the normal end of the task (`EXEC CICS RETURN`). Source:
  `app/app-vsam-mq/cbl/CODATE01.cbl:163-167 (1000-CONTROL)`, `:274-280 (4000-MAIN-PROCESS)`.
- **BR-CODATE01-10**: MQ-MSG-COUNT is incremented after each processed request and never used (Q-CODATE01-10).
  Source: `app/app-vsam-mq/cbl/CODATE01.cbl:54`, `:324`.
- **BR-CODATE01-11**: Termination: IF REPLY-QUEUE-OPEN close the input queue; IF RESP-QUEUE-OPEN close the reply
  queue; IF ERR-QUEUE-OPEN close the error queue (each with MQCO-NONE); then `EXEC CICS RETURN` (no TRANSID) and
  GOBACK. Every error path ends here, so the task never continues after an error. Source:
  `app/app-vsam-mq/cbl/CODATE01.cbl:442-454 (8000-TERMINATION)`.
- **BR-CODATE01-12**: Error reporting (9000-ERROR): MQ-ERR-DISPLAY is moved to a 1000-byte buffer (via ERROR-MESSAGE)
  and put to the error queue with MQFMT-STRING, MQCCSI-Q-MGR, MQPMO-SYNCPOINT + MQPMO-DEFAULT-CONTEXT +
  MQPMO-FAIL-IF-QUIESCING, using the MQMD left by the last GET or PUT (so it carries the last request's MsgId and
  CorrelId). Being under syncpoint, the error message is committed only when the task ends normally. Source:
  `app/app-vsam-mq/cbl/CODATE01.cbl:405-441 (9000-ERROR)`.

### Validation rules
- **VR-CODATE01-01**: No validation of the request content: function, key, length (including zero), format, and
  requester identity are not checked; the README's `REQUEST-TYPE 'DATE'` is not required (Q-CODATE01-02). Source:
  `app/app-vsam-mq/cbl/CODATE01.cbl:312-324`, `:339-364`.
- **VR-CODATE01-02**: The request must not exceed 1000 bytes and must be convertible under MQGMO-CONVERT; otherwise
  the GET returns a warning or error that is treated as failure (ERR-CODATE01-05, Q-CODATE01-07). Source:
  `app/app-vsam-mq/cbl/CODATE01.cbl:291`, `:296-299`, `:312`, `:329-335`.

### Navigation and keys
- **NAV-CODATE01-01**: No terminal and no keys. The task is started only by the trigger monitor (BR-CODATE01-02),
  processes messages until the queue stays empty for 5 seconds, and ends with `EXEC CICS RETURN` without a TRANSID
  (BR-CODATE01-11).

### Error handling
- **ERR-CODATE01-01**: Error-queue MQOPEN not OK -> MQ-APPL-CONDITION-CODE/REASON-CODE = the MQ codes,
  MQ-APPL-QUEUE-NAME = `CARD.DEMO.ERROR`, MQ-APPL-RETURN-MESSAGE = `ERR MQOPEN ERR`; `DISPLAY MQ-ERR-DISPLAY` (LE
  writes CICS DISPLAY output to a transient-data queue, normally CESE); 8000-TERMINATION. No request is read. Source:
  `app/app-vsam-mq/cbl/CODATE01.cbl:264-270 (2100-OPEN-ERROR-QUEUE)`.
- **ERR-CODATE01-02**: RETRIEVE RESP not NORMAL -> MQ-ERROR-PARA = `CICS RETRIEVE`, MQ-APPL-RETURN-MESSAGE =
  "RESP: " + RESP (8 digits) + RESP2 (8 digits, always `00000000`, Q-CODATE01-09) + "END" (exactly 25 characters),
  then 9000-ERROR and 8000-TERMINATION. Source: `app/app-vsam-mq/cbl/CODATE01.cbl:148-158 (1000-CONTROL)`.
- **ERR-CODATE01-03**: Input MQOPEN not OK -> codes, queue = request queue name, `INP MQOPEN ERR`; 9000-ERROR;
  8000-TERMINATION. Source: `app/app-vsam-mq/cbl/CODATE01.cbl:195-201 (2300-OPEN-INPUT-QUEUE)`.
- **ERR-CODATE01-04**: Reply MQOPEN not OK -> codes, queue = `CARD.DEMO.REPLY.DATE`, `OUT MQOPEN ERR`; 9000-ERROR;
  8000-TERMINATION (closes the already open input and error queues). Source:
  `app/app-vsam-mq/cbl/CODATE01.cbl:229-235 (2400-OPEN-OUTPUT-QUEUE)`.
- **ERR-CODATE01-05**: MQGET with any completion code other than MQCC-OK and a reason other than 2033 (including
  MQCC-WARNING with 2080 truncated message or 2110 format error, and 2016 get inhibited) -> codes, queue = request
  queue, `INP MQGET ERR:`; 9000-ERROR; 8000-TERMINATION. The task ends normally, committing earlier work. With 2080
  the message stays on the queue; with a conversion warning the message has been removed and the commit consumes it
  without a reply. Source: `app/app-vsam-mq/cbl/CODATE01.cbl:329-335 (3000-GET-REQUEST)`.
- **ERR-CODATE01-06**: Reply MQPUT not OK -> codes, queue = `CARD.DEMO.REPLY.DATE`, `MQPUT ERR`; 9000-ERROR;
  8000-TERMINATION; the request is consumed without a reply (Q-CODATE01-06). Source:
  `app/app-vsam-mq/cbl/CODATE01.cbl:396-402 (4100-PUT-REPLY)`.
- **ERR-CODATE01-07**: Error-queue MQPUT not OK -> codes, queue = `CARD.DEMO.ERROR`, `MQPUT ERR`; `DISPLAY
  MQ-ERR-DISPLAY`; 8000-TERMINATION. Source: `app/app-vsam-mq/cbl/CODATE01.cbl:433-439 (9000-ERROR)`.
- **ERR-CODATE01-08**: MQCLOSE not OK: on the input queue -> codes, queue = request queue, `MQCLOSE ERR`, then
  8000-TERMINATION again; on the reply queue -> the same but the queue name recorded is the **request** queue;
  neither is written to the error queue. On the error queue -> codes, queue `CARD.DEMO.ERROR`, `MQCLOSE ERR`,
  9000-ERROR, 8000-TERMINATION. Because the open flags are not reset, a persistent close failure re-enters the same
  close without end (Q-CODATE01-08). Source: `app/app-vsam-mq/cbl/CODATE01.cbl:456-523 (5000, 5100, 5200)`.
- **ERR-CODATE01-09**: ASKTIME and FORMATTIME have no RESP option and there is no HANDLE CONDITION, so any exception
  condition raised by them (FORMATTIME INVREQ, for example) takes the CICS default action and abends the task
  (ERR-CODATE01-10). In practice these commands do not fail with the supplied operands. Source:
  `app/app-vsam-mq/cbl/CODATE01.cbl:343-353`.
- **ERR-CODATE01-10**: There is no HANDLE ABEND. An abend ends the task with `ACTION(BACKOUT)`: the in-flight GET is
  backed out (the request returns to the queue with an incremented backout count) and uncommitted PUTs are
  discarded. The program never checks MQMD-BACKOUTCOUNT (Q-CODATE01-07).
- **ERR-CODATE01-11**: A completion code of MQCC-WARNING is treated as failure on every call (only MQCC-OK passes).

## Data effects
- No files, no Db2, no business data. The literal `LIT-ACCTFILENAME` = `ACCTDAT ` is declared but never used
  (Q-CODATE01-13); CODATE01 does not read the account file.
- MQ, in this order per request: destructive GET from the triggered request queue (syncpoint), PUT of one reply to
  `CARD.DEMO.REPLY.DATE` (syncpoint), committed together by the next SYNCPOINT or the task end. Error paths PUT one
  record to `CARD.DEMO.ERROR` and end the task normally, which commits the consumed request and the error record.
- Nothing is written to CICS logs except DISPLAY on two error paths.
- Interaction with the rest of CardDemo: none at the data level. It shares the error queue `CARD.DEMO.ERROR` and the
  listener structure with COACCT01 (CDRA); the two use different reply queues.

## Quirks
- **Q-CODATE01-01**: The module README does not match the code: it names queues `CARDDEMO.REQUEST.QUEUE` and
  `CARDDEMO.RESPONSE.QUEUE`, describes CDRD as a terminal transaction that sends a request and displays the reply,
  and shows DATE-REQUEST-MSG (`REQUEST-TYPE 'DATE'`, 8-byte REQUEST-ID) and DATE-RESPONSE-MSG (`RESPONSE-TYPE`,
  `RESPONSE-ID`, 10-byte SYSTEM-DATE) layouts that no program uses. The code is a trigger-started listener that
  ignores the request, replies on `CARD.DEMO.REPLY.DATE` with a labelled date-and-time text, and reports errors on
  `CARD.DEMO.ERROR`. The CSD has no MQCONN, MQMONITOR, or trigger process definitions. Recommendation: decide (treat
  the code as the contract; document the queue setup). Source: `app/app-vsam-mq/README.md:53-84`, `:99-112`,
  `app/app-vsam-mq/csd/CRDDEMOM.csd`.
- **Q-CODATE01-02**: The request is never inspected. Any message on the triggered queue, including an empty one or an
  account request (`INQA...`) put on the wrong queue, gets a date reply; REQUEST-MSG-COPY with WS-FUNC and WS-KEY is a
  leftover from COACCT01. Recommendation: decide (accept any request, or require a request type such as `DATE` and
  send an error reply otherwise). Source: `CODATE01.cbl:109-112`, `:318-323`.
- **Q-CODATE01-03**: The reply is human-readable text: US month-first date `mm-dd-yyyy`, time `hh:mm:ss` with no
  separator after the date, no seconds fraction, and no time zone or UTC offset (it is the CICS region's local time,
  so consumers in other zones or across daylight-saving changes cannot interpret it unambiguously). The 1000-byte
  reply is padded with spaces; the code page is the queue manager's. Recommendation: decide (ISO 8601 timestamp with
  offset, for example `2024-03-21T14:05:09-05:00`, in a structured body in the target; keep the legacy text only if a
  consumer depends on it). Source: `CODATE01.cbl:347-360`.
- **Q-CODATE01-04**: MQMD-REPLYTOQ / ReplyToQMgr of the request are ignored; replies always go to the fixed local
  queue `CARD.DEMO.REPLY.DATE`, so requesters cannot choose a temporary or remote reply queue, and concurrent
  requesters share one reply queue. Recommendation: fix (honor ReplyToQ, default to the fixed queue). Source:
  `CODATE01.cbl:147`, `:315`, `:320`, `:384`.
- **Q-CODATE01-05**: Correlation does not follow the MQ request/reply convention (reply CorrelId = request MsgId).
  The reply copies the request's CorrelId (MQCI-NONE unless the requester set one) and reuses the request's MsgId,
  so with default requester settings every reply has a zero CorrelId and the requester must match on MsgId or set
  its own CorrelId. The reply also keeps the request's MsgType, Report options, Expiry, and Persistence.
  Recommendation: fix (CorrelId = request MsgId, new MsgId, MsgType = MQMT-REPLY). Source: `CODATE01.cbl:313-321`,
  `:373-374`.
- **Q-CODATE01-06**: On an MQPUT error or an MQGET conversion warning, the program reports to the error queue and ends
  the task normally, which commits the consumed request; the requester gets no reply and waits for its timeout.
  Recommendation: fix (roll back so the request is retried, or send an error reply). Source: `CODATE01.cbl:329-335`,
  `:396-402`, `:442-454`.
- **Q-CODATE01-07**: No poison-message handling: a request longer than 1000 bytes fails the GET with 2080 (no
  MQGMO-ACCEPT-TRUNCATED-MSG) and stays on the queue, so each new trigger starts a task that fails on it again and
  the requests behind it are not served; an abend backs a request out with no backout-threshold check.
  Recommendation: fix (accept truncation, since the content is not used, or move to a backout queue after the
  threshold). Source: `CODATE01.cbl:291`, `:296-299`, `:329-335`.
- **Q-CODATE01-08**: Close failures recurse: 5000 and 5100 call 8000-TERMINATION, which calls them again because the
  open flags are still `Y`; 5200 does 9000-ERROR and 8000 again. A persistent close error loops until the task
  abends. 5100 also records the request queue name instead of the reply queue name. Recommendation: fix (reset the
  flag before closing; report the right queue). Source: `CODATE01.cbl:442-523`, `:496`.
- **Q-CODATE01-09**: In the RETRIEVE error path the program moves RESP2 to itself instead of to its display field,
  so the reported RESP2 is always zero. (The paragraph text is spelled `CICS RETRIEVE` here, unlike COACCT01's
  `CICS RETREIVE`.) Recommendation: fix. Source: `CODATE01.cbl:149-151`.
- **Q-CODATE01-10**: MQ-MSG-COUNT has no VALUE clause and is incremented with ADD; with uninitialized storage (binary
  zeros) this can raise a data exception after the first request, depending on compiler options. The counter is
  never used. Recommendation: fix (initialize or drop). Source: `CODATE01.cbl:54`, `:324`.
- **Q-CODATE01-11**: Flag names are swapped: REPLY-QUEUE-OPEN means the input (request) queue is open and
  RESP-QUEUE-OPEN means the reply queue is open. Behavior is consistent. Recommendation: preserve behavior, rename.
  Source: `CODATE01.cbl:16-23`, `:194`, `:228`, `:444-449`.
- **Q-CODATE01-12**: MQ-ERR-DISPLAY is initialized once per task, so an error record can carry a queue name from an
  earlier step, and MQ-ERROR-PARA is filled only on the RETRIEVE path (blank otherwise). Recommendation: fix.
  Source: `CODATE01.cbl:134`, `:149`.
- **Q-CODATE01-13**: Dead or redundant items copied from COACCT01: LIT-ACCTFILENAME (`ACCTDAT `), WS-RESP-CD,
  WS-REAS-CD, REQUEST-MSG-COPY and REQUEST-MESSAGE (filled, never read), QUEUE-MESSAGE, SAVE-REPLY2Q, MQ-QUEUE,
  MQ-QUEUE-REPLY, QMGR-NAME, ERROR-MESSAGE (only an intermediate copy), the empty LINKAGE SECTION, `IS INITIAL`;
  queues are opened with MQOO-PASS-ALL-CONTEXT but put with MQPMO-DEFAULT-CONTEXT, and the input queue's
  SAVE-ALL-CONTEXT is never used. Two connection-handle variables (both 0) are used. The CSD program description is
  `LIST CARDS`. Recommendation: drop in the target. Source: `CODATE01.cbl:105-120`, `CRDDEMOM.csd:9-10`.
- **Q-CODATE01-14**: No security or throttling: any application that can put on the triggered queue gets a
  1000-byte reply per message on a shared reply queue, so a flood of requests can fill `CARD.DEMO.REPLY.DATE` (and
  then every reply fails, ERR-CODATE01-06). The data itself is not sensitive. Recommendation: decide (authenticate
  or rate-limit in the target, or replace with a local clock call).
- **Q-CODATE01-15**: The time is read when the request is processed, not when it was sent; with a backlog or a
  5-second idle wait the reply can trail the request time. Each reply in one task carries its own time.
  Recommendation: preserve (document as server processing time).
- **Q-CODATE01-16**: The 5-second MQGET wait keeps each CDRD task alive after the last message; with
  MQOO-INPUT-SHARED and trigger type EVERY, many tasks can run in parallel. Recommendation: preserve (normal listener
  pattern); size the trigger settings in the target.

## Test scenarios
Unless stated otherwise: queues `CARD.DEMO.REPLY.DATE` and `CARD.DEMO.ERROR` defined; a request queue with a trigger
process for CDRD; the CICS region clock at local time 2024-03-21 14:05:09.
- **TS-CODATE01-01** (covers BR-CODATE01-04..09): Given request `DATE` with MsgId M1 and CorrelId C1, when it is put on
  the request queue, then CDRD starts and one 1000-byte reply appears on `CARD.DEMO.REPLY.DATE` with MsgId M1,
  CorrelId C1, format MQSTR, text `SYSTEM DATE : 03-21-2024SYSTEM TIME : 14:05:09` followed by 954 spaces; the
  request is removed and the task ends after 5 idle seconds.
- **TS-CODATE01-02** (covers BR-CODATE01-05, VR-CODATE01-01, Q-CODATE01-02): Given a zero-length request, then the same
  date reply is sent.
- **TS-CODATE01-03** (covers BR-CODATE01-05, Q-CODATE01-02): Given request `INQA00000000001`, then a date reply (not an
  account reply) is sent on `CARD.DEMO.REPLY.DATE`.
- **TS-CODATE01-04** (covers BR-CODATE01-06): Given the region clock at 2024-01-05 09:03:07, then the reply text is
  `SYSTEM DATE : 01-05-2024SYSTEM TIME : 09:03:07` (leading zeros, 24-hour clock).
- **TS-CODATE01-05** (covers BR-CODATE01-06, Q-CODATE01-15): Given two requests queued before the trigger and the
  clock advancing between them, then each reply carries the time at which it was processed.
- **TS-CODATE01-06** (covers BR-CODATE01-09): Given three requests queued before the trigger, when CDRD runs, then three
  replies are produced in queue order and a SYNCPOINT is taken before the second and third GET.
- **TS-CODATE01-07** (covers BR-CODATE01-02, Q-CODATE01-04): Given a request whose MQMD-REPLYTOQ is `MY.REPLY.Q`, then
  the reply still goes to `CARD.DEMO.REPLY.DATE`.
- **TS-CODATE01-08** (covers Q-CODATE01-05): Given a request with default CorrelId (MQCI-NONE), then the reply's
  CorrelId is all binary zeros and its MsgId equals the request's MsgId.
- **TS-CODATE01-09** (covers ERR-CODATE01-05, Q-CODATE01-07): Given a 1200-byte request, when CDRD runs, then the error
  queue receives `INP MQGET ERR:` with reason `02080` and the request stays on the queue.
- **TS-CODATE01-10** (covers ERR-CODATE01-02, Q-CODATE01-09): Given CDRD is typed at a terminal, then RETRIEVE fails,
  and the error queue receives paragraph `CICS RETRIEVE` with message `RESP: ` + the 8-digit RESP + `00000000END`.
- **TS-CODATE01-11** (covers ERR-CODATE01-01): Given `CARD.DEMO.ERROR` is not defined, when CDRD is triggered, then a
  DISPLAY line with `ERR MQOPEN ERR` and reason `02085` is written and no request is processed.
- **TS-CODATE01-12** (covers ERR-CODATE01-04): Given `CARD.DEMO.REPLY.DATE` is not defined, when CDRD is triggered, then
  the error queue receives `OUT MQOPEN ERR` with queue name `CARD.DEMO.REPLY.DATE` and the request stays on the
  queue.
- **TS-CODATE01-13** (covers ERR-CODATE01-06, Q-CODATE01-06): Given the reply queue becomes full after open, when a
  request is processed, then the error queue receives `MQPUT ERR` (reason `02053`) and the request is consumed
  without a reply.
- **TS-CODATE01-14** (covers NAV-CODATE01-01, BR-CODATE01-11): Given the request queue is empty when CDRD starts, then
  the first GET waits 5 seconds, returns 2033, all three queues are closed, and the task ends without any PUT.

## Target mapping proposal (non-normative)
- Simplest: drop the service; consumers read their own clock (or an NTP-synchronized source). If a server-side clock
  is required, expose `GET /system/date-time` returning `{"timestamp": "2024-03-21T14:05:09-05:00"}`.
- If the asynchronous contract must be kept: a message listener (JMS `@JmsListener`, SQS or Kafka consumer) that
  replies to `JMSReplyTo` (default `CARD.DEMO.REPLY.DATE`) with `JMSCorrelationID` = the request `JMSMessageID`, an
  ISO 8601 timestamp with offset, and a dead-letter queue after N failed deliveries. A compatibility formatter can
  produce the legacy 46-byte text (`SYSTEM DATE : mm-dd-yyyy` + `SYSTEM TIME : hh:mm:ss`, space-padded to 1000 bytes)
  for any consumer that parses it.
- Share one listener framework with the COACCT01 replacement (queue handling, error reporting, correlation) instead
  of duplicating it.

## Open questions
- What are the real request-queue name, trigger type (FIRST or EVERY), initiation queue, and process definition for
  CDRD? None are in the repository.
- Is there a requester (the README's terminal transaction does not exist)? Does it match replies on MsgId?
- Which time zone is the CICS region clock set to, and do consumers rely on the `mm-dd-yyyy` text format?
- Should requests that are not date requests (for example `INQA` messages put on the wrong queue) be rejected?
- Is the service still needed at all, or was it only a demonstration of the MQ pattern?
