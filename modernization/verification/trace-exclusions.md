# Traceability exclusions

Owed IDs that `trace.py` resolves through a ledger row instead of a test of their own. Each row names a
PLATFORM row (whose modern equivalent is itself covered by a passing test, or which has no modern counterpart), a NOT-PORTED row, or a
C-/B- convention (covered by passing tests). Columns: owed ID, resolving ledger row, reason.

| Owed ID | Resolved by | Reason |
|:--|:--|:--|
| ERR-CBACT04C-01 | B-ABEND | INTCALC opens no files: its inputs and outputs are tables. A table that cannot be read or written abends at first use with the COBOL read or write message (`CycleFailuresTest` INTCALC cases). |
| ERR-CBACT04C-08 | B-ABEND | No files to close; each account's unit of work is committed or rolled back as a whole (B-UOW), and a failure takes B-ABEND. |
| TS-CBTRN02C-10 | B-ABEND | The category balances are a table with no open step; an unreadable table abends at the first posting that reads it, with `ERROR READING TRANSACTION BALANCE FILE` (`CycleFailuresTest.posttran_abends_when_category_balances_cannot_be_read_written_or_rewritten`). |
| TS-CBTRN01C-05 | Q-CBTRN01C-04 | PLATFORM: the job needs only the daily file, the cross-reference, and the accounts, so a missing card file no longer stops it (`CycleFailuresTest.tranverify_needs_only_the_daily_file_the_cross_reference_and_the_accounts`). |
| ERR-CBSTM03A-01 | B-ABEND | CREASTMT's inputs are tables with no open step; an unreadable table abends with the read message of that input (`CycleFailuresTest` CREASTMT cases). Statement file open failures are ERR-CBSTM03A-09. |
| TS-CBSTM03A-09 | B-ABEND | As ERR-CBSTM03A-01: an unreadable cross-reference gives `ERROR READING XREFFILE` and `ABENDING PROGRAM` (`CycleFailuresTest.creastmt_abends_when_the_cross_reference_cannot_be_read`). |
| ERR-CBSTM03A-07 | B-ABEND | No input files to close; the statement run commits or rolls back as a whole (Q-CREASTMT-04). |
| BR-CBSTM03A-01 | Q-CBSTM03A-01 | PLATFORM: no control-block walk; the job log lists the input and output locations (`CycleFailuresTest.creastmt_logs_its_input_and_output_locations_instead_of_the_dd_names`). |
| TS-CBSTM03A-12 | Q-CBSTM03A-01 | PLATFORM: as BR-CBSTM03A-01; SYSOUT has no `Running JCL` or `DD Names from TIOT` lines. |
| BR-CBSTM03B-01 | Q-CBSTM03B-04 | PLATFORM (Q-CBSTM03B-02): no DD-name dispatch; four typed lookups (transactions, cross-reference, customer by ID, account by ID). |
| TS-CBSTM03B-04 | Q-CBSTM03B-04 | PLATFORM (Q-CBSTM03B-02): an unknown DD name cannot be expressed. |
| TS-CBSTM03B-05 | Q-CBSTM03B-04 | PLATFORM (Q-CBSTM03B-01): the lookups are read-only; a write operation does not exist. |
| TS-CBSTM03B-06 | Q-CBSTM03B-04 | PLATFORM: a lookup failure is reported by the statement job, not by the lookup (`CycleFailuresTest.a_customer_lookup_failure_is_reported_by_the_statement_job_not_by_the_lookup`). |
| BR-COACCT01-12 | Q-COACCT01-12 | NOT-PORTED: the unused message counter has no counterpart. |
| ERR-COACCT01-02 | Q-COACCT01-10 | PLATFORM: the listener is not started by a trigger message, so there is no RETRIEVE and no RETRIEVE error. |
| TS-COACCT01-10 | Q-COACCT01-10 | PLATFORM: as ERR-COACCT01-02; the inquiry cannot be started from a terminal. |
| ERR-COACCT01-03 | Q-COACCT01-17 | PLATFORM: no per-task queue open. The application declares the queues (`InquiryMessagingConfig`) and the long-lived listener container consumes them; a broker or queue failure is retried by the container, not reported per request. |
| ERR-COACCT01-05 | Q-COACCT01-17 | PLATFORM: no polling GET; the broker pushes deliveries to the long-lived consumer. The only request-level GET failure with a counterpart, the oversized request, is Q-COACCT01-08 (invalid-request reply, `mq-roundtrip`). |
| ERR-COACCT01-09 | Q-COACCT01-09 | PLATFORM: queues are not opened or closed per task; channel close errors are handled by the AMQP client, which reconnects instead of re-entering a close (`AccountInquiryMessagingTest.a_closed_broker_connection_is_recovered_and_the_next_request_is_answered`). |
| ERR-COACCT01-11 | Q-COACCT01-17 | PLATFORM: AMQP operations have no warning completion code; a publish or consume either succeeds or raises an error handled under Q-COACCT01-07. |
| BR-CODATE01-10 | Q-CODATE01-10 | NOT-PORTED: the unused message counter has no counterpart. |
| BR-CODATE01-12 | Q-CODATE01-16 | PLATFORM: no syncpoint-scoped MQPUT with an inherited MQMD; the error record is published to `CARD.DEMO.ERROR` by the long-lived consumer with its own message properties (error record content is covered under Q-CODATE01-06 and Q-CODATE01-12). |
| ERR-CODATE01-02 | Q-CODATE01-09 | PLATFORM: as ERR-COACCT01-02. |
| TS-CODATE01-10 | Q-CODATE01-09 | PLATFORM: as TS-COACCT01-10. |
| ERR-CODATE01-03 | Q-CODATE01-16 | PLATFORM: as ERR-COACCT01-03. |
| ERR-CODATE01-05 | Q-CODATE01-16 | PLATFORM: as ERR-COACCT01-05, except that an oversized date request gets a normal date reply (Q-CODATE01-07, `mq-roundtrip`). |
| ERR-CODATE01-08 | Q-CODATE01-08 | PLATFORM: as ERR-COACCT01-09 (`DateInquiryMessagingTest.a_closed_broker_connection_is_recovered_and_the_next_request_is_answered`). |
| ERR-CODATE01-11 | Q-CODATE01-16 | PLATFORM: as ERR-COACCT01-11. |
| ERR-COPAUA0C-01 | Q-COPAUA0C-15 | MQOPEN of the request queue per task has no counterpart: a long-lived RabbitMQ listener consumes the request queue; a broker that is down is a container restart, not an M001 record. |
| ERR-COPAUA0C-11 | Q-COPAUA0C-15 | MQCLOSE at the end of a task has no counterpart for the long-lived listener, so there is no M005 warning. |
| TS-PAUDBLOD-09 | Q-PAUDBLOD-01 | The DLITCBL entry that binds only the I/O PCB has no counterpart: the load writes the tables directly, so the first root insert cannot fail for that reason. |
