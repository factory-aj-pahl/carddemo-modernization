# Maintainers

Who owns and who recently changed each part of CardDemo. Every path has one official owner through `.github/CODEOWNERS`; the recent contributors come from git history, with bots excluded.

## Ownership rules

`.github/CODEOWNERS` has a single rule, `* @factory-aj-pahl`, so every pull request requests review from that account regardless of the files it touches. There are no per-directory owners. The review process is described in [How to contribute](how-to-contribute/index.md).

History comes from two places:

- The upstream AWS sample (`app/`, `samples/`, `scripts/`, `diagrams/`) is on `origin/main`, which has 32 commits; the newest is from 2025-10-16.
- `modernization/`, `docs/`, `tools/`, `tests/`, `.github/`, and the root `Makefile` and `AGENTS.md` exist only on the `modernization` branch. They were added between 2026-10-27 and 2026-10-29.

Contributor names were taken from `git log origin/main --format='%an|%ad' --date=short -- <path>` for upstream paths and `git log --format='%an|%ad' --date=short -- <path>` for branch-only paths. Bot authors (names ending in `[bot]`, and "Amazon GitHub Automation") are excluded. Names are listed by how often they appear, at most three per row.

## Ownership table

| Subsystem | Paths | Official owners | Recent contributors | Last activity |
|:--|:--|:--|:--|:--|
| Mainframe base application | `app/cbl`, `app/cpy`, `app/bms`, `app/cpy-bms`, `app/jcl`, `app/proc`, `app/ctl`, `app/catlg` | @factory-aj-pahl | Sanjay Rao, Arunkumar Selvam, vikas020487 | 2025-10-16 upstream; 2026-10-27 on this branch (agent-readiness tooling commit) |
| Optional modules | `app/app-authorization-ims-db2-mq`, `app/app-transaction-type-db2`, `app/app-vsam-mq` | @factory-aj-pahl | Arunkumar Selvam | 2025-08-03 upstream; 2026-10-27 on this branch |
| Sample data | `app/data` | @factory-aj-pahl | Arunkumar Selvam, Abhijit Kshirsagar, ian | 2025-10-16 |
| Schedulers | `app/scheduler` | @factory-aj-pahl | Arunkumar Selvam | 2025-10-10 |
| Samples and mainframe scripts | `samples`, `scripts` | @factory-aj-pahl | Arunkumar Selvam, Sanjay Rao | 2025-08-03 |
| Documentation | `docs` | @factory-aj-pahl | factory-aj-pahl | 2026-10-27 |
| Root tooling and tests | `tools/carddemo`, `tests`, `Makefile`, `AGENTS.md`, `pyproject.toml` | @factory-aj-pahl | factory-aj-pahl | 2026-10-27 |
| CI and repository settings | `.github` | @factory-aj-pahl | factory-aj-pahl | 2026-10-27 |
| Modernized backend | `modernization/backend` | @factory-aj-pahl | factory-aj-pahl | 2026-10-29 |
| Web app | `modernization/web` | @factory-aj-pahl | factory-aj-pahl | 2026-10-29 |
| End-to-end tests | `modernization/e2e` | @factory-aj-pahl | factory-aj-pahl | 2026-10-29 |
| Airflow | `modernization/airflow` | @factory-aj-pahl | factory-aj-pahl | 2026-10-29 |
| Verification | `modernization/verification` | @factory-aj-pahl | factory-aj-pahl | 2026-10-29 |
| Specification | `modernization/spec` | @factory-aj-pahl | factory-aj-pahl | 2026-10-29 |
| Fidelity ledger and decisions | `modernization/decisions` | @factory-aj-pahl | factory-aj-pahl | 2026-10-29 |
| Ops scripts | `modernization/ops` | @factory-aj-pahl | factory-aj-pahl | 2026-10-29 |
| Modernization entry points | `modernization/Makefile`, `modernization/README.md` | @factory-aj-pahl | factory-aj-pahl | 2026-10-29 |

## Notes

- The upstream authors (Sanjay Rao, Arunkumar Selvam, and others) wrote the original COBOL application but are not code owners on this branch. Changes to `app/` are reviewed by @factory-aj-pahl like any other path.
- Commits on the modernization branch are co-authored with an automation account. That account is excluded above, as required for bots.
- For the history behind these dates, see [Lore](lore.md). For which pages describe each subsystem, see [Mainframe application](mainframe/index.md) and [Modules](modules/index.md).
