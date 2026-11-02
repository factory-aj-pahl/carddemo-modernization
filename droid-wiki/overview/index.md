# CardDemo overview

CardDemo is an AWS sample credit card management application written for the mainframe (COBOL, CICS, VSAM, JCL, with optional Db2, IMS, and MQ modules). This repository holds that mainframe application, local Python tooling that lints and runs it without a mainframe, and, on the `modernization` branch, a full rebuild of the same application as a web app, a Spring Boot API, Spring Batch jobs, and Airflow schedules that is checked against the original COBOL.

## What the application does

CardDemo simulates the back office of a small card issuer. Two kinds of users sign on:

- **Regular users** view and update accounts and customers, list and update credit cards, browse and add transactions, pay a bill, request transaction reports, and (with the optional modules) view pending authorizations.
- **Administrators** maintain users and, with the transaction-type module, maintain transaction types.

A nightly batch cycle posts the day's card transactions, rejects ones that fail validation, calculates monthly interest, and prints account statements. The sample data under `app/data/ASCII` and `app/data/EBCDIC` is synthetic: 50 accounts, cards, and customers, plus a daily transaction file of 300 records.

The original purpose of the code is to exercise migration and analysis tools, so the COBOL deliberately mixes styles and carries real defects. The modernization keeps that in mind: every quirk is recorded and decided before it is preserved or fixed (see [Fidelity ledger](../verification/fidelity-ledger.md)).

## The two halves of the repository

| Half | Where | What it is |
|:--|:--|:--|
| Mainframe application | `app/`, `samples/`, `scripts/`, `docs/` | The product as shipped by AWS: 44 COBOL programs (31 base, 13 in optional modules), 21 BMS screens, 46 JCL jobs, CA7 and Control-M schedules, sample data |
| Local tooling | `tools/carddemo`, `tests/`, root `Makefile` | Python 3.10+ static analysis (rules CD001-CD070), inventory generator, GnuCOBOL syntax checks and batch runner |
| Modernized stack | `modernization/` | React SPA, Spring Boot API (modular monolith of 11 bounded contexts), Spring Batch jar, Airflow 3 DAGs, SQL Server schema, RabbitMQ messaging, standalone bill-payment service |
| Modernization contract and proof | `modernization/spec/`, `modernization/decisions/`, `modernization/verification/`, `modernization/e2e/` | Structured specification of every program, the fidelity ledger, batch parity against GnuCOBOL, Playwright suite, rule traceability |

## Who uses it

- **Migration engineers and tool vendors** use the mainframe half as a realistic test subject. The root `README.md` explains how to install it on z/OS.
- **Contributors to the COBOL** use the root tooling (`make check`, `make run-batch`) to change programs safely without a mainframe. See [Local tooling](../mainframe/local-tooling.md).
- **Readers of the modernization** use `modernization/` as a worked example of a spec-first, parity-checked conversion from CICS/VSAM/JCL to a web stack. Start at [Architecture](architecture.md) and [Applications](../applications/index.md).

## Quick links

| I want to... | Go to |
|:--|:--|
| Understand how the pieces fit | [Architecture](architecture.md) |
| Run the modern stack or the COBOL batch locally | [Getting started](getting-started.md) |
| Decode a term like COMMAREA, XCTL, or POSTTRAN | [Glossary](glossary.md) |
| See what each runnable process does | [Applications](../applications/index.md) |
| Follow a business capability end to end | [Features](../features/index.md) |
| Find the Java code for a COBOL program | [Modules](../modules/index.md) |
| Learn how parity with the COBOL is proven | [Verification](../verification/index.md) |
| Read about the original COBOL programs and jobs | [Mainframe application](../mainframe/index.md) |
| Call the API or the message queues | [API](../api/index.md) |
| Make a change and get it reviewed | [How to contribute](../how-to-contribute/index.md) |

## Project status

The mainframe application is version 1.0 of the AWS sample (Apache 2.0, see `LICENSE`). The modernized stack was built in October 2026 on the `modernization` branch: the target architecture in `modernization/architecture.md` was approved on 2026-10-28, and the branch carries the specification, the decisions, the implementation, and its verification evidence. It runs on a single machine and binds every service to 127.0.0.1; there is no cloud deployment in the repository.
