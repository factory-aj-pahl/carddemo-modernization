# CardDemo structured natural-language specification

This directory is the intermediate representation of CardDemo used for modernization. Every
COBOL program, BMS screen, copybook, JCL job, and schedule is restated here in structured
English. Target-architecture decisions and generated code trace back to the IDs defined here,
not to the COBOL directly.

The specification describes what the source *does*, including defects and oddities. It does
not describe what the source should do. Where the code looks wrong, the behavior is still
recorded exactly as written and flagged as a quirk (see `Q-` IDs below), so a person can decide
whether to preserve or fix it.

## Files

| File | Contents |
|:--|:--|
| `entities.md` | Data dictionary: every record layout, field meaning, key, relationship, and data-format rule |
| `programs/<MEMBER>.md` | One file per COBOL program (base and optional modules) |
| `jobs.md` | Every JCL job, procedure, control card, and the CA7/Control-M schedules as a dependency graph |
| `components.md` | Synthesis: modules, bounded contexts, CRUD matrix, interactions, cross-cutting concerns |

## Identifier conventions

| Prefix | Meaning | Example |
|:--|:--|:--|
| `BR-<MEMBER>-nn` | Business rule (decision or computation that affects data or outcome) | `BR-COBIL00C-03` |
| `VR-<MEMBER>-nn` | Input validation rule (field check that produces a message) | `VR-COACTUPC-17` |
| `NAV-<MEMBER>-nn` | Navigation or key-handling rule (PF keys, XCTL, return) | `NAV-COBIL00C-02` |
| `ERR-<MEMBER>-nn` | Error or file-status handling | `ERR-CBTRN02C-04` |
| `Q-<MEMBER>-nn` | Quirk: suspected defect, dead code, race, or surprising behavior | `Q-COBIL00C-01` |
| `TS-<MEMBER>-nn` | Test scenario derived from the source (Given/When/Then) | `TS-COBIL00C-05` |
| `ENT-<NAME>` | Entity in `entities.md` | `ENT-ACCOUNT` |
| `JOB-<NAME>` | JCL job in `jobs.md` | `JOB-POSTTRAN` |

IDs are stable: never renumber; append new ones.

Every rule cites its source as `path:line-range (PARAGRAPH-NAME)`, for example
`app/cbl/COBIL00C.cbl:183-196 (PROCESS-ENTER-KEY)`. User-visible messages are quoted verbatim,
including spacing, punctuation, and trailing dots.

## Program file template (`programs/<MEMBER>.md`)

```markdown
# <MEMBER>: <one-line purpose>

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/<MEMBER>.cbl` (<n> lines) |
| Module | base / app-<module> |
| Kind | Online (CICS) / Batch / Subroutine |
| Transaction ID / Job | e.g. CB00 / JOB-POSTTRAN |
| BMS map | mapset / map, or none |
| Copybooks | list |
| Calls / XCTL targets | list |
| Data stores | file or table, access (Read / Browse / Write / Rewrite / Delete), key used |

## Purpose
Two to five sentences, in business terms.

## Invocation and state
How the program is entered (XCTL from which programs, JCL step, MQ trigger), what it reads from
COMMAREA (`COCOM01Y` fields and any program-specific extension), and what state it carries across
pseudo-conversational turns. For batch: DD names, parameters, return codes it sets.

## Screen (online programs only)
A text mock-up of the 24x80 screen with fields, then a field table:

| Field | BMS name | Len | In/Out | Attributes | Meaning |

## Processing flow
Numbered structured English describing the main flow and each branch. Refer to paragraphs
by name. Use "IF / OTHERWISE" and "FOR EACH" wording, not COBOL syntax.

## Rules
### Business rules
- **BR-<MEMBER>-01**: When <condition>, then <effect>. Source: `path:lines (PARA)`.
### Validation rules
- **VR-<MEMBER>-01**: <field> must <condition>; otherwise message "<verbatim>" and cursor on <field>. Source: ...
### Navigation and keys
- **NAV-<MEMBER>-01**: PF3 -> ... Source: ...
### Error handling
- **ERR-<MEMBER>-01**: On <file status / RESP> ... message "<verbatim>". Source: ...

## Data effects
Exactly which records are created, changed (which fields, how computed), or deleted, and in
what order. Note transactional boundaries (SYNCPOINT, READ UPDATE / REWRITE pairing).

## Quirks
- **Q-<MEMBER>-01**: what the code does, why it looks surprising, and its consequence.
  Recommendation: preserve / fix / decide. Source: ...

## Test scenarios
- **TS-<MEMBER>-01** (covers BR-.., VR-..): Given <data state, using sample data keys where
  possible>, when <input>, then <screen message / output / data change>.

## Open questions
Anything the source does not settle.
```

Every IF, EVALUATE branch, and file-status branch that changes data, output, a message, or
navigation must map to at least one rule. Coverage must be exhaustive: a reader must be able to
re-implement the program from this file alone without reading the COBOL.

## Job entry template (in `jobs.md`)

For each job: `JOB-<NAME>` heading, purpose, steps table (step, program or utility, parameters
or control cards, DD to dataset mapping with DISP), condition-code logic, datasets created and
deleted, GDG usage, restart notes, and the schedule predecessors and successors from CA7 and
Control-M. End with a Mermaid dependency graph of the whole schedule.
