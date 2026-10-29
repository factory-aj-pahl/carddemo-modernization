# Modernization entry points. Run from the repository root: make -C modernization <target>.
# Stack targets read the secrets from ~/.carddemo/secrets.env themselves (ops/stack-env.sh); the
# verification targets need them in the environment: set -a; . ~/.carddemo/secrets.env; set +a
# README.md has the full run-from-scratch sequence.

SHELL := /bin/bash

PYTHON ?= python3
MVN ?= mvn -B -q

AIRFLOW_PYTHON ?= $(HOME)/.carddemo/airflow-venv/bin/python

STACK_ENV := . ops/stack-env.sh &&

.PHONY: setup db-reset load up down status smoke billpay test-backend test-billpay test-e2e test-web verify-report
.PHONY: backend verify-batch verify-cycle verify-data verify-harness verify-ledger verify-spec test-airflow trace

backend:
	$(MVN) -f backend package -DskipTests

# ---- Run the stack (README.md) -------------------------------------------------------------------------------

# Build everything: backend jars (API, batch, billpay-service), web app (web/dist), e2e dependencies.
setup: backend
	npm --prefix web ci --no-audit --no-fund
	npm --prefix web run build
	npm --prefix e2e ci --no-audit --no-fund

# Drop and re-create the stack database (CARDDEMO_DB, default carddemo) and migrate it.
db-reset:
	ops/db-reset.sh

# Load the sample data: base ASCII files, users and account links, IMS authorization sample.
load:
	ops/load.sh

# Start Airflow (8080), the API (8081), and the web app (5173), then check them; idempotent.
up:
	ops/up.sh

# Stop everything up and billpay started.
down:
	ops/down.sh

status:
	$(STACK_ENV) python3 ops/check_stack.py

# Smoke flow in the browser against the running stack.
smoke:
	$(STACK_ENV) cd e2e && npx playwright test -c playwright.smoke.config.ts

# The standalone bill-payment service alone on CARDDEMO_BILLPAY_PORT (8091); stop with make down.
billpay:
	ops/billpay.sh

# ---- Tests ---------------------------------------------------------------------------------------------------
# Backend and e2e suites clean and reload their own database (CARDDEMO_TEST_DB / CARDDEMO_E2E_DB, default
# carddemo_test), never the stack database unless you point them at it.

test-backend:
	$(MVN) -f backend verify

# Builds billpay-service with its dependencies, starts it alone (port 8092), and runs its HTTP tests.
test-billpay:
	$(MVN) -f backend -pl billpay-service -am verify

# The e2e suite starts its own API and web app on CARDDEMO_API_PORT / CARDDEMO_WEB_PORT (default 8081 / 5173):
# run it with the stack down or on other ports.
test-e2e:
	npm --prefix e2e test

test-web:
	npm --prefix web run typecheck
	npm --prefix e2e run typecheck

# schedule-cycle (c): browser report runs on the real Airflow against the GnuCOBOL reference. Stack must be up.
verify-report:
	$(STACK_ENV) $(PYTHON) verification/schedule/verify_report.py $(if $(OUT),--out $(OUT))

verify-batch: backend
	$(PYTHON) verification/parity/verify_batch.py $(if $(OUT),--out $(OUT)) $(if $(ONLY),--only $(ONLY))

verify-data: backend
	$(PYTHON) verification/data/verify_data.py $(if $(OUT),--out $(OUT))

verify-harness:
	$(PYTHON) verification/parity/qualify_harness.py $(if $(OUT),--out $(OUT))

verify-cycle: backend
	$(PYTHON) verification/cycle/verify_cycle.py $(if $(OUT),--out $(OUT))

verify-ledger:
	$(PYTHON) verification/ledger_check.py

verify-spec:
	$(PYTHON) verification/spec_check.py

# Run after the backend and e2e suites; BATCH= points at a verify-batch results.json.
trace:
	$(PYTHON) verification/trace.py $(if $(BATCH),--batch-results $(BATCH))

# DAG and helper unit tests; JUnit XML (rule IDs in the names) in airflow/tests/junit.xml for trace.py --junit.
test-airflow:
	$(AIRFLOW_PYTHON) airflow/tests/run_tests.py
