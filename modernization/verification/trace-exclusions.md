# Trace exclusions

Owed IDs that have no test of their own because the ledger row they cite makes the COBOL artifact
platform-specific (PLATFORM, whose modern equivalent is covered by a passing test) or dead code
(NOT-PORTED). Read by `verification/trace.py`.

| ID | ledger row | reason |
|:--|:--|:--|
| ERR-COPAUA0C-01 | Q-COPAUA0C-15 | MQOPEN of the request queue per task has no counterpart: a long-lived RabbitMQ listener consumes the request queue; a broker that is down is a container restart, not an M001 record. |
| ERR-COPAUA0C-11 | Q-COPAUA0C-15 | MQCLOSE at the end of a task has no counterpart for the long-lived listener, so there is no M005 warning. |
| TS-PAUDBLOD-09 | Q-PAUDBLOD-01 | The DLITCBL entry that binds only the I/O PCB has no counterpart: the load writes the tables directly, so the first root insert cannot fail for that reason. |
