# Modernization entry points. Run from the repository root: make -C modernization <target>.
# Database targets need the secrets: set -a; . ~/.carddemo/secrets.env; set +a

PYTHON ?= python3
MVN ?= mvn -B -q

.PHONY: backend verify-batch verify-ledger verify-spec trace

backend:
	$(MVN) -f backend package -DskipTests

verify-batch: backend
	$(PYTHON) verification/parity/verify_batch.py $(if $(OUT),--out $(OUT)) $(if $(ONLY),--only $(ONLY))

verify-ledger:
	$(PYTHON) verification/ledger_check.py

verify-spec:
	$(PYTHON) verification/spec_check.py

# Run after the backend and e2e suites; BATCH= points at a verify-batch results.json.
trace:
	$(PYTHON) verification/trace.py $(if $(BATCH),--batch-results $(BATCH))
