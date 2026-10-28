# Modernization entry points. Run from the repository root: make -C modernization <target>.
# Database targets need the secrets: set -a; . ~/.carddemo/secrets.env; set +a

PYTHON ?= python3
MVN ?= mvn -B -q

AIRFLOW_PYTHON ?= $(HOME)/.carddemo/airflow-venv/bin/python

.PHONY: backend verify-batch verify-cycle verify-ledger test-airflow

backend:
	$(MVN) -f backend package -DskipTests

verify-batch: backend
	$(PYTHON) verification/parity/verify_batch.py $(if $(OUT),--out $(OUT)) $(if $(ONLY),--only $(ONLY))

verify-cycle: backend
	$(PYTHON) verification/cycle/verify_cycle.py $(if $(OUT),--out $(OUT))

verify-ledger:
	$(PYTHON) verification/ledger_check.py

# DAG and helper unit tests; JUnit XML (rule IDs in the names) in airflow/tests/junit.xml for trace.py --junit.
test-airflow:
	$(AIRFLOW_PYTHON) airflow/tests/run_tests.py
