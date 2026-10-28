# Modernization entry points. Run from the repository root: make -C modernization <target>.
# Database targets need the secrets: set -a; . ~/.carddemo/secrets.env; set +a

PYTHON ?= python3
MVN ?= mvn -B -q

AIRFLOW_PYTHON ?= $(HOME)/.carddemo/airflow-venv/bin/python

.PHONY: backend verify-batch verify-cycle verify-data verify-harness verify-ledger verify-spec test-airflow trace

backend:
	$(MVN) -f backend package -DskipTests

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
