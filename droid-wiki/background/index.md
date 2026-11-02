# Background

These pages explain why the modernized CardDemo looks the way it does: the decisions behind its shape, the traps that catch new contributors, and how the rebuild was carried out. Read them after the [architecture overview](../overview/architecture.md) when a behavior seems odd and you want to know whether it is deliberate.

## Overview

CardDemo has two halves. The original COBOL, CICS, VSAM, and JCL application lives under `app/` and is not changed by the modernization. The rebuild under `modernization/` reproduces it as a React web app, a Spring Boot API, Spring Batch jobs run by Airflow, SQL Server, and RabbitMQ. Almost every unusual behavior in the rebuild traces back to one of three sources:

- a user decision (`D-` ID) or recorded default (`DF-` ID) in `modernization/decisions/README.md`,
- a convention row (`B-` for batch, `C-` for online) in `modernization/decisions/ledger-batch-data.md` or `modernization/decisions/ledger-online.md`,
- a section of `modernization/architecture.md`, which was approved on 2026-10-28.

When `modernization/architecture.md` and a decision disagree, the decision governs (`modernization/architecture.md:3-5`).

## Pages in this section

| Page | What it covers |
|:--|:--|
| [Design decisions](design-decisions.md) | The major architectural choices, each with its context, decision, consequence, and source ID |
| [Pitfalls](pitfalls.md) | Behaviors that surprise new contributors, with the file that proves each one |
| [Migration context](migration-context.md) | How the modernization was run: spec, ledger, architecture, modules, verification, review fixes |

## Related pages

- [Fidelity ledger](../verification/fidelity-ledger.md) explains the decision values (FIX, PRESERVE, CHANGE, PLATFORM, NOT-PORTED) in detail.
- [Batch parity](../verification/batch-parity.md) shows how batch outputs are compared with the original COBOL.
- [Cleanup opportunities](../cleanup-opportunities.md) lists what is left to tidy.
- [Lore](../lore.md) tells the longer history of the repository.
