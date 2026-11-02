# Dependencies

CardDemo has two dependency worlds. The root tooling needs only Python 3.10+ (standard library at runtime, pinned dev tools for lint and tests) and optionally GnuCOBOL. The modernized stack needs JDK 21, Maven, Node.js, SQL Server, RabbitMQ, and an Apache Airflow 3 virtual environment. This page lists what each part declares and where.

## Overview

| Area | Manifest | Ecosystem |
|:--|:--|:--|
| Backend (18 Maven modules) | `modernization/backend/pom.xml` and `modernization/backend/<module>/pom.xml` | Maven, Spring Boot 3.5 |
| Web app | `modernization/web/package.json`, `modernization/web/package-lock.json` | npm |
| End-to-end tests | `modernization/e2e/package.json`, `modernization/e2e/package-lock.json` | npm |
| Root tooling | `requirements-dev.in`, `requirements-dev.txt`, `pyproject.toml` | pip |
| Airflow | no manifest in the repository; installed per `modernization/README.md` | pip (separate venv) |
| CI | `.github/workflows/*.yml`, `.pre-commit-config.yaml` | GitHub Actions, pre-commit |
| Update bot | `.github/dependabot.yml` | Dependabot |

For how these tools are used day to day, see [Tooling](../how-to-contribute/tooling.md). For the processes that run on them, see [Deployment](../deployment.md).

## System components

From the prerequisites table in `modernization/README.md` (Ubuntu 24.04, installed with apt unless noted) and the root `Makefile`:

| Component | Version | Needed for |
|:--|:--|:--|
| OpenJDK | 21 (`java.version` in `modernization/backend/pom.xml`) | Backend build and all three jars |
| Maven | 3.8.7 | Backend build (`MVN ?= mvn -B -q` in `modernization/Makefile`) |
| Node.js / npm | 24 | Web build, vite preview, Playwright 1.64 with Chromium (`npx playwright install chromium`) |
| SQL Server 2025 Developer | 17.0 | Database, port 1433 on 127.0.0.1; `sqlcmd` from `mssql-tools18` in `/opt/mssql-tools18/bin` |
| RabbitMQ | 3.12.1 | Message queues, vhost `carddemo`, management plugin on 15672 |
| Apache Airflow | 3.3.2 | Batch orchestration, in a Python 3.12 venv at `~/.carddemo/airflow-venv` |
| GnuCOBOL | 3.1.2 | `cobc` for the parity harness and root `make syntax`/batch tests (`make setup-cobol` runs `apt-get install gnucobol`) |
| Python | 3.10+ (`requires-python` in `pyproject.toml`) | Root tooling, ops checks, verification scripts |

`.devcontainer/devcontainer.json` installs GnuCOBOL and runs `make setup`. It does not install the modernized stack components.

## Maven

The parent is `org.springframework.boot:spring-boot-starter-parent` 3.5.16 (`modernization/backend/pom.xml`). It manages the versions of Spring, `mssql-jdbc`, Flyway, Spring Batch, Spring AMQP, and the test libraries, so no module pins them. The parent pins two extra versions in `dependencyManagement`:

| Property | Version | Artifact |
|:--|:--|:--|
| `springdoc.version` | 2.8.17 | `org.springdoc:springdoc-openapi-starter-webmvc-ui` |
| `archunit.version` | 1.4.1 | `com.tngtech.archunit:archunit-junit5` |

Plugin settings in the parent: surefire runs with `-Xmx512m` and redirects test output to files; `maven-jar-plugin` uses `forceCreation` so Spring Boot never packs stale module jars.

### Per-module dependencies

Counts are direct `<dependency>` entries in each module pom, including test scope. "Internal" are other CardDemo modules.

| Module | Count | Internal (compile) | External | Test scope |
|:--|--:|:--|:--|:--|
| `common` | 7 | none | `spring-boot-starter-web`, `-security`, `-jdbc`, `-validation`, `mssql-jdbc`, `springdoc-openapi-starter-webmvc-ui` | `spring-boot-starter-test` |
| `schema` | 2 | none | `flyway-core`, `flyway-sqlserver` | none |
| `migration` | 3 | common, schema | none | `spring-boot-starter-test` |
| `test-support` | 6 | common, schema, migration | `flyway-core`, `spring-boot-starter-test`, `spring-security-test` | none (the module itself is used in test scope) |
| `iam` | 2 | common | none | test-support |
| `card` | 3 | common | none | iam, test-support |
| `account` | 4 | common, card, iam | none | test-support |
| `transaction` | 5 | common, card, refdata, iam | none | test-support |
| `billpay` | 6 | common, account, card, transaction | none | iam, test-support |
| `refdata` | 3 | common | none | iam, test-support |
| `reporting` | 5 | common, transaction, card, refdata | none | test-support |
| `cycle` | 6 | common, account, card, transaction, refdata | none | test-support |
| `dataexchange` | 6 | common, migration, account, card, transaction | none | test-support |
| `authorization` | 7 | common, account, card, migration | `spring-boot-starter-amqp` | iam, test-support |
| `inquiry` | 5 | common, account | `spring-boot-starter-amqp` | iam, test-support |
| `app` | 17 | common, schema, and all 11 context modules | `spring-boot-starter-actuator`, `flyway-core` | `archunit-junit5`, test-support |
| `batch` | 15 | common, schema, migration, cycle, reporting, refdata, dataexchange, authorization, iam, account, card, transaction | `spring-boot-starter-batch`, `flyway-core` | test-support |
| `billpay-service` | 7 | common, schema, iam, billpay | `spring-boot-starter-actuator`, `flyway-core` | test-support |

The three runnable modules use `spring-boot-maven-plugin` and set `finalName` to `carddemo-api`, `carddemo-batch`, and `carddemo-billpay-service`. `billpay-service` also runs `maven-failsafe-plugin` for its HTTP integration tests. The module graph and boundary rules are on [Modules](../modules/index.md).

## npm

Versions are the ranges in `package.json`; the lock files resolve them to the same numbers (checked for react, react-router, vite, typescript, and @playwright/test).

### `modernization/web/package.json` (`carddemo-web` 0.1.0)

| Package | Range | Kind |
|:--|:--|:--|
| `react` | ^19.3.1 | dependency |
| `react-dom` | ^19.3.1 | dependency |
| `react-router` | ^8.5.1 | dependency |
| `vite` | ^8.3.5 | dev |
| `@vitejs/plugin-react` | ^6.1.2 | dev |
| `typescript` | ^7.0.2 | dev |
| `@types/react` | ^19.3.0 | dev |
| `@types/react-dom` | ^19.3.0 | dev |
| `@types/node` | ^26.6.5 | dev |

Scripts: `dev` (vite), `build` (`tsc -p . && vite build`), `preview`, `typecheck`. See [Web app](../applications/web-app.md).

### `modernization/e2e/package.json` (`carddemo-e2e` 0.1.0)

| Package | Range | Kind |
|:--|:--|:--|
| `@playwright/test` | ^1.64.1 | dev |
| `@axe-core/playwright` | ^4.14.0 | dev |
| `typescript` | ^7.0.2 | dev |
| `@types/node` | ^26.6.5 | dev |

Scripts: `pretest` (`node scripts/seed.mjs`), `test` (`playwright test`), `typecheck`. See [End-to-end suite](../verification/e2e-suite.md).

## Python

### Root development tools

`requirements-dev.in` lists the direct tools; `requirements-dev.txt` pins them and every transitive package. `make setup` installs them into `.venv`.

| Package | Pinned version |
|:--|:--|
| `mypy` | 2.4.0 |
| `pre-commit` (`pre_commit`) | 4.7.0 |
| `pytest` | 9.1.1 |
| `pytest-cov` | 7.1.0 |
| `pytest-xdist` | 3.8.0 |
| `ruff` | 0.17.3 |

`requirements-dev.txt` has 26 pinned packages in total, including `coverage` 7.17.0, `PyYAML` 6.0.3, and `virtualenv` 21.15.0. The tooling in `tools/carddemo` imports only the standard library (as stated in the root `README.md`).

### Airflow virtual environment

Airflow 3.3.2 runs from `~/.carddemo/airflow-venv` (Python 3.12), put on the path by `modernization/airflow/env.sh` and used by `make test-airflow` through `AIRFLOW_PYTHON`. The repository has no requirements or constraints file for this venv, so the exact installed package set is not recorded. The DAGs in `modernization/airflow/dags/` import `airflow`; everything else they use is standard library.

### Verification and ops scripts

The scripts under `modernization/verification/` and `modernization/ops/check_stack.py` import only the standard library (`pathlib`, `argparse`, `subprocess`, `json`, `urllib`, `hashlib`, `xml`, and similar) plus sibling modules such as `refrun`, `javarun`, `compare`, and `make_datasets`. They run with the system `python3`.

## CI and hooks

| Item | Version | Where |
|:--|:--|:--|
| `actions/checkout` | v7.0.1 | `.github/workflows/*.yml` |
| `actions/setup-python` | v7.1.0 | `.github/workflows/*.yml` |
| `actions/upload-artifact` | v7.0.2 | `.github/workflows/*.yml` |
| `github/codeql-action` (init, analyze) | v4 | `.github/workflows/codeql.yml` |
| `EndBug/label-sync` | v2.3.3 | `.github/workflows/labels.yml` |
| gitleaks | 8.30.1 (`GITLEAKS_VERSION`, downloaded from GitHub releases) | `.github/workflows/ci.yml` |
| `pre-commit-hooks` | v6.0.0 | `.pre-commit-config.yaml` |
| `ruff-pre-commit` | v0.17.3 | `.pre-commit-config.yaml` |
| gitleaks hook | v8.30.1 | `.pre-commit-config.yaml` |

CI covers the root tooling only. The modernized suites (Maven, npm, Airflow, verification) are run locally through `modernization/Makefile`.

## Dependabot

`.github/dependabot.yml` defines two update streams, both weekly with a 7-day cooldown and labels `dependencies` and `tooling`:

| Ecosystem | Directory | Grouping |
|:--|:--|:--|
| `pip` | `/` | one group `dev-tools` matching `*` |
| `github-actions` | `/` | none |

The cooldown waits a week after a release before proposing it, so yanked or compromised versions are usually caught upstream first. Maven and npm manifests under `modernization/` are not covered by Dependabot.
