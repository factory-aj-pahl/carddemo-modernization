# Tooling

This page describes the build and check tools of both halves: the root Makefile and Python configuration for the COBOL tooling, the modernization Makefile, Maven, npm, the Airflow virtual environment, and the GitHub workflows. CI covers only the root checks and a secret scan; the modernization suites run locally.

For the internals of `tools/carddemo` (lint rules, inventory, syntax check, batch runner), see [Local tooling](../mainframe/local-tooling.md). For versions of every dependency, see [Dependencies](../reference/dependencies.md).

## Overview

| Area | Entry point | Configuration |
|:--|:--|:--|
| COBOL tooling (Python) | root `Makefile` | `pyproject.toml`, `requirements-dev.in`, `requirements-dev.txt`, `.pre-commit-config.yaml`, `.env.example`, `.devcontainer/devcontainer.json` |
| Modern stack | `modernization/Makefile` (`make -C modernization <target>`) | `modernization/ops/stack-env.sh`, `~/.carddemo/secrets.env` |
| Backend | Maven, `modernization/backend/pom.xml` | Module `pom.xml` files |
| Web and e2e | npm, `modernization/web/package.json`, `modernization/e2e/package.json` | `modernization/web/vite.config.ts`, `modernization/e2e/playwright*.config.ts` |
| Airflow | `~/.carddemo/airflow-venv` | `modernization/airflow/env.sh` |
| CI | `.github/workflows/*.yml` | `.github/dependabot.yml`, `.github/release.yml`, `.github/labels.yml` |

## Root Makefile

The Makefile uses `.venv/bin/python` when it exists, otherwise `python3` (override with `PYTHON=`). `make help` prints the list.

| Target | Runs |
|:--|:--|
| `setup` | Create `.venv`, install `requirements-dev.txt`, `pre-commit install` |
| `setup-cobol` | `sudo apt-get install gnucobol` |
| `check` | `lint-python typecheck lint syntax docs-check coverage` (what CI runs) |
| `lint` / `lint-all` / `baseline` | `tools.carddemo lint` / `lint --all` / `lint --update-baseline` |
| `complexity` | `tools.carddemo complexity --top 25` |
| `lint-python` / `format` | `ruff check` and `ruff format --check` on `tools` and `tests` / `ruff format` |
| `typecheck` | `mypy` (strict, on `tools/carddemo`) |
| `syntax` | `tools.carddemo syntax --require-cobc` |
| `test` / `test-unit` | `pytest -n auto` / `pytest -n auto tests/unit` |
| `coverage` | pytest with `--cov`, `reports/coverage.xml`, `reports/junit.xml` |
| `docs` / `docs-check` (alias `check-docs`) | `tools.carddemo inventory` / `inventory --check` and `check-docs` |
| `run-batch` | `tools.carddemo run-batch $(PROGRAM) --workdir .batch-runs/$(PROGRAM)` (default `CBTRN02C`) |
| `clean` | Remove `.batch-runs`, `reports`, coverage and tool caches |

### Python configuration

`pyproject.toml` declares the package `carddemo-tooling` (Python 3.10 or newer) and configures:

| Tool | Settings |
|:--|:--|
| pytest | `testpaths = ["tests"]`, `-ra --strict-markers --durations=10`, the `cobc` marker |
| coverage | Source `tools/carddemo`, branch coverage, parallel mode, `fail_under = 90` |
| ruff | Line length 120, target py310, excludes `app`, `samples`, `scripts`; rule sets E, F, W, I, N, UP, B, SIM, PL, RUF with four Pylint refactor rules ignored (PLR0913, PLR2004, PLR0911, PLR0912) |
| mypy | `strict = true`, `warn_unreachable = true`, files `tools/carddemo` |

`requirements-dev.in` lists the direct tools (mypy, pre-commit, pytest, pytest-cov, pytest-xdist, ruff); `requirements-dev.txt` pins them and every transitive dependency (for example `ruff==0.17.3`, `pytest==9.1.1`, `mypy==2.4.0`). The header of `requirements-dev.in` shows how to regenerate the pins. `CONTRIBUTING.md` asks contributors not to adopt a dependency version until it has been out for 7 days, and keeps `tools/carddemo` on the standard library only.

### Pre-commit, devcontainer, and `.env.example`

- `.pre-commit-config.yaml`: generic hygiene hooks (whitespace fixers skip the mainframe directories), ruff, gitleaks v8.30.1, and two local hooks that run the CardDemo lint and the docs checks. See [Development workflow](development-workflow.md#pre-commit-hooks).
- `.devcontainer/devcontainer.json`: the `mcr.microsoft.com/devcontainers/python:1-3.12-bookworm` image with the GitHub CLI, installs GnuCOBOL and runs `make setup` after creation, sets `CARDDEMO_REQUIRE_COBC=1`, and configures VS Code with ruff, Python, mypy, and a COBOL extension, rulers at columns 7, 72, and 80, and trailing-whitespace trimming off.
- `.env.example`: documents `COBC`, `CARDDEMO_REQUIRE_COBC`, `PYTHON`, and `PROGRAM`, and explains that the `scripts/` mainframe helpers expect an FTP tunnel on localhost:2121 with credentials in `~/.netrc`, never in the repository. `.env` itself is git-ignored.

## Modernization Makefile

Run from the repository root with `make -C modernization <target>`. Stack targets source `modernization/ops/stack-env.sh`, which reads `~/.carddemo/secrets.env`; verification targets expect the secrets already exported. Maven runs as `mvn -B -q`.

| Target | Runs |
|:--|:--|
| `backend` | `mvn -f backend package -DskipTests` |
| `setup` | `backend`, then `npm ci` and `npm run build` in `web`, `npm ci` in `e2e` |
| `db-reset` | `ops/db-reset.sh`: drop and create `CARDDEMO_DB`, map `carddemo_app`, run Flyway |
| `load` | `ops/load.sh`: sample data, USRSEC users, IMS sample |
| `up` / `down` | `ops/up.sh` (Airflow, API, web, stack check) / `ops/down.sh` |
| `status` | `ops/check_stack.py` |
| `smoke` | Playwright with `playwright.smoke.config.ts` against the running stack |
| `billpay` | `ops/billpay.sh`: standalone bill-payment service on 8091 |
| `test-backend` | `mvn -f backend verify` |
| `test-billpay` | `mvn -f backend -pl billpay-service -am verify` |
| `test-e2e` | `npm --prefix e2e test` |
| `test-web` | `npm run typecheck` in `web` and `e2e` |
| `test-airflow` | `airflow/tests/run_tests.py` with `$(HOME)/.carddemo/airflow-venv/bin/python` |
| `verify-batch` | `backend`, then `verification/parity/verify_batch.py` (`OUT=`, `ONLY=`) |
| `verify-data` | `backend`, then `verification/data/verify_data.py` (`OUT=`) |
| `verify-cycle` | `backend`, then `verification/cycle/verify_cycle.py` (`OUT=`) |
| `verify-report` | `verification/schedule/verify_report.py` with the stack env (`OUT=`) |
| `verify-harness` | `verification/parity/qualify_harness.py` (`OUT=`) |
| `verify-ledger` / `verify-spec` | `verification/ledger_check.py` / `verification/spec_check.py` |
| `trace` | `verification/trace.py` (`BATCH=` points at a `verify-batch` `results.json`) |

`modernization/verification/scan_secrets.py` has no Makefile target; run it with `python3 modernization/verification/scan_secrets.py`.

## Maven

`modernization/backend/pom.xml` is the parent of all 18 modules (`common`, `schema`, `migration`, `test-support`, the eleven context modules, `app`, `batch`, `billpay-service`).

| Setting | Value | Notes |
|:--|:--|:--|
| Parent | `spring-boot-starter-parent` 3.5.16 | Manages Spring, Jackson, JUnit, Flyway, and SQL Server driver versions |
| Java | 21 | `java.version` |
| springdoc-openapi | 2.8.17 (`springdoc-openapi-starter-webmvc-ui`) | Declared by `common`, so every service gets it; serves `/v3/api-docs` |
| ArchUnit | 1.4.1 (`archunit-junit5`) | Used by `app` for `ModuleBoundariesTest` |
| Surefire | `-Xmx512m`, `redirectTestOutputToFile` | Test output in `target/surefire-reports/<class>-output.txt` |
| `maven-jar-plugin` | `forceCreation=true` | Without it an unchanged module jar is not rebuilt and Spring Boot keeps stale dependency jars inside the executable jar |

Three modules use `spring-boot-maven-plugin` to build executable jars: `app` (`carddemo-api.jar`), `batch` (`carddemo-batch.jar`), and `billpay-service` (`carddemo-billpay-service.jar`). `billpay-service` also binds the plugin's `start` and `stop` goals around failsafe so its HTTP tests run against the packaged jar on port 8092 ([Testing](testing.md#surefire-and-failsafe)).

## npm

| Package | Scripts | Main dependencies |
|:--|:--|:--|
| `modernization/web/package.json` (`carddemo-web`) | `dev` (vite), `build` (`tsc -p . && vite build`), `preview`, `typecheck` | React 19, react-router 8, Vite 8, TypeScript 7 |
| `modernization/e2e/package.json` (`carddemo-e2e`) | `pretest` (`node scripts/seed.mjs`), `test` (`playwright test`), `typecheck` | `@playwright/test` 1.64, `@axe-core/playwright` |

`modernization/web/vite.config.ts` binds dev and preview servers to 127.0.0.1 on `CARDDEMO_WEB_PORT` (default 5173) with `strictPort: true`, so a busy port is an error rather than a silent move to another port. It proxies `/api` and `/actuator` to `CARDDEMO_API_URL` (default `http://127.0.0.1:8081`). The e2e package has three Playwright configs: `playwright.config.ts` (full suite), `playwright.smoke.config.ts`, and `playwright.report.config.ts` (used by `verify-report`).

## Airflow virtual environment

Airflow 3.3.2 lives in a Python 3.12 venv at `~/.carddemo/airflow-venv`, outside the repository (`modernization/README.md`). The repository does not contain a requirements file for it. `modernization/airflow/env.sh` puts the venv on `PATH`, sets `AIRFLOW_HOME` to `~/.carddemo/airflow`, points the DAGs folder at `modernization/airflow/dags`, turns off example DAGs, and configures the simple auth manager user `carddemo`. Source it before any `airflow` command. `modernization/airflow/start.sh` and `stop.sh` manage the api-server, scheduler, and dag-processor. See [Airflow](../applications/airflow.md).

## GitHub workflows

| Workflow | Trigger | What it does |
|:--|:--|:--|
| `.github/workflows/ci.yml` | Push to `main`, every pull request, manual | Job `checks` on ubuntu-24.04, Python 3.12, GnuCOBOL from apt, `CARDDEMO_REQUIRE_COBC=1`: runs `make lint-python typecheck lint syntax docs-check coverage` one by one, writes a timing table to the job summary, uploads `reports/`. Job `secrets` runs gitleaks 8.30.1 over the full history with `--redact` |
| `.github/workflows/codeql.yml` | Push to `main`, pull requests, weekly (Tuesday 03:41 UTC) | CodeQL `security-extended` for `python` and `actions`. CodeQL has no COBOL support; the COBOL security checks are lint rule CD070 and gitleaks |
| `.github/workflows/flaky-tests.yml` | Weekly (Monday 04:17 UTC), manual | Runs the root pytest suite 5 times and fails if any test both passed and failed across runs |
| `.github/workflows/labels.yml` | Push to `main` touching `.github/labels.yml`, manual | Syncs labels with `EndBug/label-sync` without deleting others |
| `.github/workflows/release.yml` | Push of a `v*` tag | Packages the sources with `git archive` and creates a GitHub release with generated notes |

Supporting files:

- `.github/dependabot.yml`: weekly pip and GitHub Actions updates, a 7-day cooldown, labels `dependencies` and `tooling`, all pip updates grouped as `dev-tools`. There are no Maven or npm entries, so the modernization dependencies are not updated by Dependabot.
- `.github/release.yml`: release-note sections by label (Breaking changes, New features, Bug fixes, Documentation, Tooling and CI, Other), excluding `skip-changelog`.

### What CI does not cover

No workflow builds or tests anything under `modernization/`: there is no Maven, npm, Playwright, Airflow, or parity job. The only CI check that sees modernization files is gitleaks (and CodeQL for the Python verification scripts). Contributors run the modernization suites locally and report them in the pull request ([How to contribute](index.md#definition-of-done)).

## Related pages

- [Local tooling](../mainframe/local-tooling.md)
- [Configuration](../reference/configuration.md)
- [Deployment](../deployment.md)
- [Testing](testing.md)
