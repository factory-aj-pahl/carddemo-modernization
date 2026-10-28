# Modernization entry points. Run from the repository root: make -C modernization <target>.
# Database targets need the secrets: set -a; . ~/.carddemo/secrets.env; set +a

PYTHON ?= python3
MVN ?= mvn -B -q

.PHONY: backend verify-batch verify-data verify-harness verify-ledger

backend:
	$(MVN) -f backend package -DskipTests

verify-batch: backend
	$(PYTHON) verification/parity/verify_batch.py $(if $(OUT),--out $(OUT)) $(if $(ONLY),--only $(ONLY))

verify-data: backend
	$(PYTHON) verification/data/verify_data.py $(if $(OUT),--out $(OUT))

verify-harness:
	$(PYTHON) verification/parity/qualify_harness.py $(if $(OUT),--out $(OUT))

verify-ledger:
	$(PYTHON) verification/ledger_check.py
