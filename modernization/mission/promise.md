# CardDemo full-stack modernization: promise

Status: approved; in execution.

## Objective

Modernize the whole CardDemo mainframe application into a runnable modern stack, working
through an explicit structured natural-language intermediate form:

1. The COBOL, BMS, copybooks, JCL, and schedules are restated as a structured natural-language
   specification (`modernization/spec/`). That representation is reasoned over to identify
   entities, components, modules, and interactions, and the target architecture is derived from it.
2. Green screens become a React + TypeScript web app, online logic becomes a Spring Boot service,
   batch becomes Spring Batch jobs orchestrated by Apache Airflow, VSAM/Db2/IMS data moves to
   Microsoft SQL Server, and MQ moves to RabbitMQ.
3. Behavior is the same as the COBOL except for the approved deviations in the fidelity ledger.
4. The system is organized by bounded context so any single module (for example bill payment) can
   be converted, built, and run on its own.

## Scope (D-SCOPE)

Everything in the repository's application tree:

- Base application: 17 online programs and 21 BMS screens in total with the modules, CSUTLDTC,
  13 batch programs, 38 JCL members, CA7/Control-M schedules, all sample data in `app/data/ASCII`.
- Optional module `app/app-transaction-type-db2` (COTRTLIC, COTRTUPC, COBTUPDT, DDL, seed data).
- Optional module `app/app-authorization-ims-db2-mq` (COPAUA0C, COPAUS0C-2C, CBPAUP0C, PAUDBUNL,
  PAUDBLOD, DBUNLDGS, IMS DBDs, Db2 DDL, sample IMS data).
- Optional module `app/app-vsam-mq` (COACCT01, CODATE01).

The owed program set is the 44 programs listed in `docs/INVENTORY.md` at the start commit; the
owed job set is the 46 jobs in `modernization/spec/jobs.md`.

## Required properties

| ID | Property |
|:--|:--|
| P-SPEC | `modernization/spec/` restates all 44 programs, all entities, all jobs and schedules, and the component synthesis in the format of `spec/README.md`. Every branch that changes data, output, a message, or navigation maps to a rule; rules and messages are accurate to the COBOL. |
| P-ARCH | `modernization/architecture.md` describes the target architecture derived from the spec, and the implementation follows it (or the document is updated with the reason). |
| P-UI | A React + TypeScript single-page app implements every online screen in scope (21 BMS maps) with the same fields, flows, validation messages (verbatim), and F-key meanings as keyboard shortcuts, in a modern clean layout (D-UI), keyboard operable with labelled inputs. |
| P-API | A Spring Boot (Java 21) REST API implements every BR, VR, NAV, and ERR rule of the online programs as specified, adjusted only by the fidelity ledger; it publishes an OpenAPI description. |
| P-MODULES | Each bounded context in `spec/components.md` is a separate Maven module that reaches other contexts only through their published service interfaces (enforced by an automated architecture test). Bill payment is demonstrated as a standalone deployable microservice that passes its own tests. |
| P-DATA | SQL Server 2025 schema is created by versioned migrations. A loader loads all sample data (base ASCII files, Db2 seed data, IMS authorization sample data) with correct decoding of signed and packed fields; an exporter writes the original fixed-width layout back and the round trip matches the source byte for byte (excluding documented padding normalizations). |
| P-BATCH | Every batch program in scope has a Spring Batch (or Spring) job equivalent. For programs GnuCOBOL can run, outputs (files, reports, rejects, statements, return codes, job-log counts) and resulting table contents match the GnuCOBOL reference on the same inputs, where the reference is the unmodified COBOL plus the ledger's reference patches. |
| P-SCHED | Airflow DAGs replace the recurring JCL: the daily cycle in runbook order (posting, then statements) with interest on the first business day of each month between them (D-CYCLE as amended by D-INTEREST), an on-demand transaction-report DAG started from the report screen, and DAGs for the remaining recurring jobs (authorization purge with expiry parameter per D-PURGE, transaction-type maintenance). Setup JCL becomes migrations and loader commands. Daily and month-start cycle runs reach the same end state as the reference chain. The transaction table keeps full history; statements cover only the current cycle (D-HISTORY). |
| P-MQ | Authorization requests and the account/date inquiries work over RabbitMQ request/reply queues (correlation id = request message id, reply-to honored) and over REST (D-MQ). |
| P-SEC | Server-side sessions, BCrypt password hashes, admin/user roles enforced in the API (D-AUTH); regular users see only linked accounts and their cards, transactions, bill payments, pending authorizations, and report rows (D-USER-ACCT, D-REPORT); card numbers and SSNs are masked on screens and job-log listings; no password is ever returned to the client; one generic sign-on failure message, 15-minute lockout after 5 failures, 15-minute idle timeout, and an audit log of sign-ons and user/account/card changes (D-SEC-EXTRAS). Sample accounts are linked round-robin to the sample regular users (D-SEED). |
| P-RUN | One documented command sequence on this machine builds, migrates, loads, and starts the stack; the existing repository checks (`make check`) still pass; `app/` is unchanged. |

## Designated sources of truth

| Property | Governing source | Version | Precedence |
|:--|:--|:--|:--|
| Behavior of every program | COBOL, BMS, copybooks, JCL in `app/` | Git commit a3e87a4 (start of mission; `app/` must stay unchanged) | Highest, except where the fidelity ledger approves a deviation |
| Approved deviations | `modernization/decisions/` (README + ledgers) | Identity recorded at approval | Overrides the COBOL for the listed Q- items only |
| Restated behavior used by implementers | `modernization/spec/` | Identity recorded at approval; corrections allowed when shown wrong against the COBOL | Below the COBOL: a disagreement is a spec defect |
| Batch expected outputs | GnuCOBOL 3.1.2 runs with `-std=ibm -fsign=EBCDIC` of the unmodified COBOL plus ledger reference patches, on the agreed inputs | Built from the start commit | Defines byte-level expected outputs |
| Sample data | `app/data/ASCII` (base), module seed data and IMS sample data | Start commit | ASCII set is the baseline where ASCII and EBCDIC disagree |
| Daily cycle order | `docs/runbooks/batch-operations.md` order (D-CYCLE), with interest monthly (D-INTEREST) | Start commit | Over CA7 and Control-M definitions |

## Non-goals

- Running anything on a mainframe or emulator; z/OS compile JCL.
- Production-scale performance, high availability, cloud deployment, containers (Docker is not
  available here). The stack runs on this machine at sample-data scale.
- A pixel-faithful 3270 look (D-UI chose a modern layout).
- Fixing cosmetic quirks: message and label text stays verbatim (D-COSMETIC).
- A real card network or external MQ client; a test publisher stands in for the authorization source.
- PDF rendering of statements (TXT2PDF1), FTP transfer (FTPJCL), the internal reader, and CICS
  file open/close jobs: these have no target equivalent (`spec/jobs.md` class c/d).
- Changing the existing COBOL tooling, its tests, or `AGENTS.md` (the 38-vs-43 reject finding is
  reported, not fixed in the old tooling).

## Binding constraints and boundaries (approved 2026-10-28)

- Technologies: React + TypeScript; Spring Boot on Java 21; Spring Batch; Apache Airflow; SQL Server
  2025 Developer; RabbitMQ.
- Installs (sudo apt): OpenJDK 21, Maven, SQL Server 2025 Developer (systemd, port 1433, memory
  capped at 2 GB), RabbitMQ (5672, management 15672). Airflow in a Python venv (port 8080).
  API on 8081, web on 5173. All services bind to 127.0.0.1 only (SQL Server and RabbitMQ were
  reconfigured to loopback after install).
- Git: local branch `modernization`, local commits only, never push.
- `app/` is not modified. The verification harness (GnuCOBOL reference runner, reference patches as
  patch files applied to temporary copies, comparator, dataset generator) is a repository deliverable
  under `modernization/verification/`; generated run outputs and evidence live in the mission directory. Secrets live only in
  `~/.carddemo/secrets.env` and never in the repository, logs, or responses.
- Heavy jobs (SQL Server, Airflow, builds, browser tests) run one at a time where memory requires it.

## Consequential defaults and assumptions (object if wrong)

Further defaults DF-1 to DF-13 are recorded in `modernization/decisions/README.md` and are part of
this agreement.

- A-1 Admins keep the COBOL admin menu; their API scope is unrestricted (DF-10). Regular-menu screens
  are regular-user functions scoped to linked accounts.
- A-2 Out-of-scope access returns the same message as "not found", so existence is not leaked.
- A-3 Account-to-user links are maintained by admins on the user add and update screens (new field).
- A-4 Transaction types and categories have one system of record (SQL Server tables) used by both
  the reference-data screens and the report; the TRANEXTR extract is no longer needed.
- A-5 The report screen shows the run status and lets the user download the report file.
- A-6 The reference reject count for the sample POSTTRAN run is 38 (mainframe sign semantics), not
  the 43 in `AGENTS.md`.
- A-7 Online programs cannot run here, so their correctness is judged against the spec and the
  COBOL source, not by executing them.

## User decisions

Recorded in `modernization/decisions/README.md` (D-SCOPE ... D-INACTIVE). The UI look (D-UI-LOOK), bill-payment receipt panel (D-RECEIPT), and 11-digit account IDs (D-ACCTID) were approved on the prototype `ui-prototype`. Pending:

None. The fidelity ledger was approved with the final plan.

## Approval record

- 2026-10-28T16:00:13Z: the user approved the final plan ("Approve plan and start execution"): this promise, the
  incorporated decisions and fidelity ledger (git 825b06c:modernization/decisions tree
  866fc632cd03d2152e6eb4ffc1d7510f9973969a), the spec (tree 2e5daa4b28905a8b195f51659eb438576b285124),
  the architecture (blob 291de3f6000ee60b85a868cdb4e8009a6d49c773), and the initial checks.json.
  Interpretations presented with the approval: CVV/PAN/SSN kept in clear at rest (Q-ENT-33); the purge
  does not change credit or cash balances (Q-CBPAUP0C-01, DF-13); INTCALC refuses a second completed run
  in the same month (exit 8); the sample POSTTRAN reference reject count is 38 (A-6).
