# Specification

Active contributors: factory-aj-pahl

`modernization/spec/` restates every COBOL program, copybook, JCL job, and schedule of CardDemo in structured English with stable IDs. It is the intermediate representation the rebuild was written from: code, tests, and ledger rows cite spec IDs, not COBOL line numbers.

## Purpose

The spec records what the COBOL *does*, including defects. Where code looks wrong it is still described exactly and flagged as a quirk (Q- ID) so that the [fidelity ledger](fidelity-ledger.md) can decide whether to preserve or fix it (`modernization/spec/README.md`). The README sets the bar: "a reader must be able to re-implement the program from this file alone without reading the COBOL."

## Directory layout

```
modernization/spec/
  README.md        ID conventions and the program and job templates (109 lines)
  entities.md      data dictionary: records, fields, keys, formats, sample-data findings (1,204 lines)
  jobs.md          every JCL job, proc, control card, CA7/Control-M schedule (1,791 lines)
  components.md    synthesis: metrics, bounded contexts, CRUD matrix, interactions (596 lines)
  programs/        one <MEMBER>.md per COBOL program, 44 files (14,071 lines)
```

## Identifier conventions

From `modernization/spec/README.md`:

| Prefix | Meaning | Example |
|:--|:--|:--|
| `BR-<MEMBER>-nn` | Business rule (decision or computation that affects data or outcome) | `BR-COBIL00C-03` |
| `VR-<MEMBER>-nn` | Input validation rule (field check that produces a message) | `VR-COACTUPC-17` |
| `NAV-<MEMBER>-nn` | Navigation or key handling (PF keys, XCTL, return) | `NAV-COBIL00C-02` |
| `ERR-<MEMBER>-nn` | Error or file-status handling | `ERR-CBTRN02C-04` |
| `Q-<MEMBER>-nn` | Quirk: suspected defect, dead code, race, surprising behavior | `Q-COBIL00C-01` |
| `TS-<MEMBER>-nn` | Test scenario derived from the source (Given/When/Then) | `TS-COBIL00C-05` |
| `ENT-<NAME>` | Entity in `entities.md` | `ENT-ACCOUNT` |
| `JOB-<NAME>` | JCL job in `jobs.md` | `JOB-POSTTRAN` |

Rules:

- An ID is **defined** where it appears in bold (`**BR-COBIL00C-03**`). Both `modernization/verification/spec_check.py` and `modernization/verification/trace.py` find definitions with the regex `\*\*(BR|VR|NAV|ERR|Q|TS)-...\*\*`, so bold is significant.
- IDs are stable: never renumber, only append.
- The member part can also be a job or document namespace, not only a program: `Q-ENT-02` (entities.md), `Q-INTCALC-01` and `Q-TRANREPT-03` (jobs.md), `Q-READCARD-01`.
- Every rule cites its source as `path:line-range (PARAGRAPH-NAME)`, and user-visible messages are quoted verbatim, including spacing and trailing dots.

## Summary metrics

Section 1 of `modernization/spec/components.md`:

| Measure | Value |
|:--|--:|
| COBOL programs specified | 44 |
| Base application programs | 31 (17 online CICS, 12 batch, 2 called subroutines: CSUTLDTC, CBSTM03B) |
| Optional module programs | 13 (authorization 8, transaction-type Db2 3, VSAM-MQ 2) |
| COBOL source lines covered | 30,181 (base 20,656; authorization 4,344; transaction-type Db2 4,037; VSAM-MQ 1,144) |
| Entities | 36 |
| JCL jobs | 46 (base 38, modules 8) |
| Unique specification IDs | 2,191 |
| BR / VR / NAV / ERR | 444 / 171 / 150 / 287 |
| Quirks (Q-) | 611 (programs 506, entities.md 42, jobs.md 63) |
| Test scenarios (TS-) | 528 (programs 526, jobs.md 2) |

The Q- and TS- totals in that table lag the files. Counting bold definitions across `modernization/spec/*.md` and `modernization/spec/programs/*.md` at commit `467e0f0` gives 621 Q- and 534 TS- IDs (BR, VR, NAV, and ERR match). The ledgers have 621 rows, and the traceability matrix owes 534 TS- IDs, so those two follow the current files.

The same section breaks the counts down by bounded context (BC-1 IAM and Navigation through BC-11 MQ Inquiry). Section 3 of `components.md` assigns each program to exactly one context; `spec_check.py` enforces that, and the Maven modules follow the same split (see [Modules](../modules/index.md)).

## The other spec files

| File | What it holds |
|:--|:--|
| `modernization/spec/entities.md` | Entity index, core VSAM entities, shared working-storage and interface entities, module entities, VSAM dataset catalog, relationships, data-format rules (overpunch, packed decimal), sample data findings, encoding findings, proposed relational schema notes, and the `Q-ENT-*` quirks |
| `modernization/spec/jobs.md` | Section 1 inventory table (ID, member, class, programs, where it is scheduled, purpose, modern equivalent), job entries by class, procedures and control cards, schedules, dataset catalogue, restart and recovery semantics, a recommended Airflow mapping, and a whole-schedule Mermaid graph. Its "Step DISP" rules are what the parity harness's abnormal-disposition step reproduces |
| `modernization/spec/components.md` | Summary metrics, entities index, bounded contexts, CRUD matrix, interactions, cross-cutting concerns, quirk triage, and a selective conversion guide. It defines no new rule |

For how the jobs map onto the mainframe batch, see [Batch and JCL](../mainframe/batch-and-jcl.md).

## How to read a program file

Every `modernization/spec/programs/<MEMBER>.md` follows the template in `modernization/spec/README.md`:

| Section | Content |
|:--|:--|
| Heading `# <MEMBER>: <purpose>` | `spec_check.py` requires exactly this heading form |
| Attribute table | Source path and line count, module, kind (online, batch, subroutine), transaction ID or job, BMS map, copybooks, calls and XCTL targets, data stores with access mode and key |
| Purpose | Two to five sentences in business terms |
| Invocation and state | How the program is entered, which COMMAREA fields it reads, state carried across turns; for batch, DD names, parameters, return codes |
| Screen | Online only: a 24x80 text mock-up and a field table (BMS name, length, attributes) |
| Processing flow | Numbered structured English by paragraph name |
| Rules | Business rules, validation rules, navigation and keys, error handling, each a bold ID |
| Data effects | Records created, changed, or deleted and in what order; transaction boundaries |
| Quirks | Bold Q- IDs, each with a recommendation (preserve, fix, decide) |
| Test scenarios | Bold TS- IDs in Given/When/Then form, each listing the rules it covers |
| Open questions | What the source does not settle |

Example from `modernization/spec/programs/COBIL00C.md`:

- **BR-COBIL00C-03** says the new transaction ID is the highest `TRAN-ID` in TRANSACT plus 1, found by a STARTBR at HIGH-VALUES and one READPREV, zero-padded to 16 digits. It cites `app/cbl/COBIL00C.cbl:212-219 (PROCESS-ENTER-KEY)` and `:472-496 (READPREV-TRANSACT-FILE)`.
- **Q-COBIL00C-01** flags the same "highest key + 1" as a race (two payments can compute the same ID) with "Recommendation: fix". The ledger row for it in `modernization/decisions/ledger-online.md` is FIX: "The same locked shared counter as Q-COTRN02C-01. Concurrent payments never collide."
- **TS-COBIL00C-01** "(covers BR-COBIL00C-01, -03, -04, ...)" walks a payment on sample account 1 from the confirmation prompt to the new transaction record. A test that asserts this scenario is tagged `TS-COBIL00C-01`; see the `slice.spec.ts` entry on the [End-to-end suite](e2e-suite.md) page.

Reading tips:

- Start with the attribute table and Processing flow, then use the Rules list as the checklist. The Java code cites the same IDs in comments and test names (see [Patterns and conventions](../how-to-contribute/patterns-and-conventions.md)).
- Before you change behavior, check the Q- entries and then the ledger row for each one. The spec says what COBOL does; the ledger says what the target does.
- A TS- scenario's "covers" list shows which rules a single test is expected to exercise. `trace.py` owes every TS- ID separately from the rules it covers.

## Checks on the spec

`make -C modernization verify-spec` runs `modernization/verification/spec_check.py`. It builds its owed sets from the COBOL tree at the start commit `a3e87a4`, not from the spec:

- programs from `docs/INVENTORY.md` at that commit, each needing `spec/programs/<M>.md` with heading `# <M>:`;
- copybooks in `app/cpy` and the module `cpy` and `dcl` directories, each named somewhere in the spec;
- JCL members in `app/jcl` and module `jcl` directories, each with an inventory row in jobs.md section 1 and a job heading;
- message literals that online programs MOVE to a message field, or declare as 88-level values of one, each present verbatim somewhere in the spec;
- IDs defined only once, and references (`BR-...`, `ENT-...`, `JOB-...`) that resolve;
- each program in exactly one bounded context in components.md section 3.

It prints one summary line per check and exits 1 on any problem. See [Traceability](traceability.md) for how the spec IDs become owed tests.

## Key source files

| File | Purpose |
|:--|:--|
| `modernization/spec/README.md` | ID conventions, program and job templates |
| `modernization/spec/components.md` | Metrics, bounded contexts, CRUD matrix |
| `modernization/spec/entities.md` | Data dictionary and data quirks |
| `modernization/spec/jobs.md` | JCL jobs, schedules, restart semantics |
| `modernization/spec/programs/COACTUPC.md` | Largest program spec (COACTUPC is 4,236 COBOL lines) |
| `modernization/verification/spec_check.py` | Completeness and consistency check |

Related: [Migration context](../background/migration-context.md), [Glossary](../overview/glossary.md).
