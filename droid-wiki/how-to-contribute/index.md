# How to contribute

This section explains how work is picked up, how a change gets reviewed and merged, and what "done" means for the two halves of the repository: the COBOL application with its root tooling, and the modernized stack under `modernization/`. The sub-pages cover the day-to-day workflow, the test suites, debugging, and the tools behind each command.

## Sub-pages

| Page | Read it when |
|:--|:--|
| [Development workflow](development-workflow.md) | You are about to make your first change and want the branch, change, test, PR cycle |
| [Testing](testing.md) | You need to run a suite or write a new test |
| [Debugging](debugging.md) | Something fails to start, a job exits non-zero, or a test behaves oddly |
| [Patterns and conventions](patterns-and-conventions.md) | You are writing code and want to match the house style |
| [Tooling](tooling.md) | You want to know what a `make` target, Maven setting, or workflow actually does |

New to the repository? Start with [Getting started](../overview/getting-started.md) and [Architecture](../overview/architecture.md).

## Finding work

Issues are the entry point. `.github/ISSUE_TEMPLATE/config.yml` disables blank issues, so every issue uses one of two forms:

| Template | Labels applied | Asks for |
|:--|:--|:--|
| `.github/ISSUE_TEMPLATE/bug_report.yml` | `bug`, `triage` | Module, program/job/file, where it happened (z/OS, emulator, local GnuCOBOL, code reading), steps, expected vs actual (return codes, abend codes, file status, screen messages), commit, masked job log |
| `.github/ISSUE_TEMPLATE/feature_request.yml` | `enhancement`, `triage` | The modernization or learning scenario, the proposed change, module, and whether a record layout, file key, or COMMAREA changes |

The bug form warns against pasting real customer data, card numbers, or credentials, and the config links security reports to the AWS vulnerability reporting page instead of a public issue.

Labels are defined in `.github/labels.yml` and synced to GitHub by `.github/workflows/labels.yml`. The ones that matter when looking for work:

| Label | Meaning |
|:--|:--|
| `good first issue` | Small, well-scoped task for new contributors |
| `help wanted` | Maintainers would welcome a contribution |
| `triage` | New issue that a maintainer has not reviewed yet |
| `module: base`, `module: authorization`, `module: transaction-type`, `module: vsam-mq` | Which part of `app/` the issue concerns |
| `breaking-change` | Changes a record layout, file key, or interface other systems rely on |
| `tooling` | `tools/carddemo`, tests, CI, or developer setup |

`CONTRIBUTING.md` asks you to open an issue to discuss any significant work before starting it.

## Pull request process

`CONTRIBUTING.md` describes a fork-based flow: fork, change only what the contribution needs (no mass reformatting), make sure `make check` passes, commit with clear messages, open a pull request and answer the template, then follow up on CI failures.

The pull request template (`.github/pull_request_template.md`) has four parts:

1. **What and why**, with the issue it fixes (`Fixes #123`).
2. **Areas touched**: base COBOL/copybooks/BMS, JCL/PROCs/scheduler, an optional module, sample data or record layouts (with the README dataset table updated), or tooling/tests/CI/docs.
3. **Verification** checkboxes: `make lint` (no new findings, or baseline changes explained), `make syntax`, `make test`, `make docs-check` (after `make docs` if programs or copybooks changed), and whether it was tested on a mainframe or emulator. CICS, Db2, IMS, and MQ changes must say what could not be tested locally.
4. **Data handling**: no PII displayed, logged, or added to new outputs (`docs/data-handling.md`).

The template is written for the COBOL half. A pull request that touches `modernization/` should also list the modernization checks it ran (see the definition of done below); there is no separate template for it.

Label the pull request with `bug`, `enhancement`, `documentation`, `tooling`, or `breaking-change`. The release workflow groups generated release notes by these labels (`.github/release.yml`); `skip-changelog` leaves a pull request out.

### Which branch to target

`CONTRIBUTING.md` says to work against `main`, and the CI workflow runs on pushes to `main` and on every pull request (`.github/workflows/ci.yml`). The modernization lives on the `modernization` branch, which `main` does not contain. Changes to `modernization/` therefore go to `modernization`, following the topic-branch flow described in [Development workflow](development-workflow.md#branch-strategy).

## Review expectations

`.github/CODEOWNERS` assigns every path to one owner:

```
* @factory-aj-pahl
```

so every pull request requests that review. `CONTRIBUTING.md` adds that every pull request is reviewed by the code owners. Reviewers look for the things the tooling cannot fully judge:

- For COBOL: fixed-format columns, member naming, no PII in DISPLAY, module COPY boundaries, and any `make baseline` change explained in the description (`AGENTS.md`).
- For the modern code: the behavior matches the spec or a ledger decision, `@Covers` tags name only rules the assertions check, the module boundaries hold, and no secret or PII leaks into code, logs, or fixtures ([Patterns and conventions](patterns-and-conventions.md)).

## Definition of done

### COBOL, copybooks, JCL, and root tooling

A change under `app/`, `tools/`, `tests/`, `docs/`, or `.github/` is done when:

| Check | Source |
|:--|:--|
| `make check` passes (`lint-python`, `typecheck`, `lint`, `syntax`, `docs-check`, `coverage`) | root `Makefile`, `CONTRIBUTING.md` |
| `make lint` reports no new findings. Use `make baseline` only for findings you accept on purpose, and explain why in the pull request. Metric rules (complexity, file size, duplicate length) cannot grow past the values in `tools/carddemo/lint-baseline.json` | `AGENTS.md` |
| `make docs` was run after adding, removing, or renaming programs or copybooks, and `make docs-check` passes | `AGENTS.md` |
| A copybook that describes a dataset was changed together with the data under `app/data` and the README dataset table; `tests/integration/test_data_layouts.py` checks the three agree | `AGENTS.md` |
| Behavior changes in a locally runnable batch program have a test in `tests/integration/test_batch_jobs.py` | `.factory/skills/carddemo-cobol-change/SKILL.md` |
| Debt markers link an issue (`TODO(#123): ...`), and nothing displays card numbers, CVVs, or SSNs | `AGENTS.md` (CD060, CD070) |
| Tooling coverage stays at or above 90 percent | `pyproject.toml` |
| CICS, Db2, IMS, and MQ changes state in the pull request that mainframe testing is needed | `.factory/skills/carddemo-cobol-change/SKILL.md` |

### Modernization

A change under `modernization/` is done when:

| Check | Why |
|:--|:--|
| A behavior change starts in the spec (`modernization/spec/programs/<PROGRAM>.md`) or in a ledger row (`modernization/decisions/ledger-online.md`, `modernization/decisions/ledger-batch-data.md`) before the code | The ledger decides where the target differs from the COBOL; when the COBOL and a decision disagree, the decision wins (`modernization/decisions/README.md`) |
| `make -C modernization verify-spec` and `verify-ledger` pass after a spec or ledger edit | `modernization/verification/spec_check.py` and `modernization/verification/ledger_check.py` exit 1 on any gap |
| New or changed tests carry `@Covers` (Java) or start their title or docstring with the rule IDs (Playwright, Airflow) | `modernization/verification/trace.py` only counts tagged tests |
| The relevant suites pass: `test-backend` for any backend module, `test-web` for `web/` or `e2e/`, `test-e2e` for screen behavior, `test-airflow` for DAGs, `verify-batch` for batch jobs, `verify-data`, `verify-cycle`, `verify-report` for their areas | `modernization/Makefile`, see [Testing](testing.md) |
| `make -C modernization trace` was regenerated from the new test reports and shows no uncovered owed ID, failed test, or skipped tagged test | `modernization/verification/traceability.md` is committed; the last commit on the branch regenerated it ("Regenerate traceability after the late review fixes") |
| No secrets in tracked files | `modernization/verification/scan_secrets.py` scans every tracked file for the values in `~/.carddemo/secrets.env`; gitleaks runs in CI and as a pre-commit hook |

None of the modernization suites run in CI. The workflows cover only the root checks and gitleaks ([Tooling](tooling.md#github-workflows)), so the author runs them locally and says which ran in the pull request.

## Related pages

- [Traceability](../verification/traceability.md)
- [Fidelity ledger](../verification/fidelity-ledger.md)
- [Security](../security.md)
- [Maintainers](../maintainers.md)
