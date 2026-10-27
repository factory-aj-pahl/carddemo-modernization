# Developer entry points for the CardDemo tooling. Run `make help` for a summary.
# Every target works from a fresh clone after `make setup`; COBOL compile and
# batch targets also need GnuCOBOL (`make setup-cobol` on Debian or Ubuntu).

PYTHON ?= $(if $(wildcard .venv/bin/python),.venv/bin/python,python3)
CARDDEMO := $(PYTHON) -m tools.carddemo
PROGRAM ?= CBTRN02C
REPORTS := reports

.DEFAULT_GOAL := help
.PHONY: help setup setup-cobol lint lint-all baseline complexity lint-python format typecheck \
	syntax test test-unit coverage docs docs-check check-docs run-batch check clean

help:
	@echo "setup        create .venv, install pinned dev tools, install git hooks"
	@echo "setup-cobol  install GnuCOBOL with apt (needs sudo)"
	@echo "check        everything CI runs: lint, typecheck, syntax, docs-check, coverage"
	@echo "lint         COBOL/JCL static analysis; fails only on findings not in the baseline"
	@echo "lint-all     list every finding, including accepted baseline entries"
	@echo "baseline     accept the current findings (review the diff before committing)"
	@echo "complexity   rank paragraphs by cyclomatic complexity"
	@echo "lint-python  ruff lint and format check for tools/ and tests/"
	@echo "format       apply ruff formatting to tools/ and tests/"
	@echo "typecheck    mypy --strict on tools/carddemo"
	@echo "syntax       GnuCOBOL syntax check of the batch programs"
	@echo "test         full pytest suite (unit + integration)"
	@echo "test-unit    unit tests only, no GnuCOBOL needed"
	@echo "coverage     pytest with coverage gate, JUnit and coverage XML in $(REPORTS)/"
	@echo "docs         regenerate docs/INVENTORY.md"
	@echo "docs-check   fail if docs/INVENTORY.md or AGENTS.md are stale"
	@echo "run-batch    compile and run PROGRAM=$(PROGRAM) on the sample data"
	@echo "clean        remove local build, batch, and report output"

setup:
	python3 -m venv .venv
	.venv/bin/pip install --disable-pip-version-check -q -r requirements-dev.txt
	-.venv/bin/pre-commit install

setup-cobol:
	sudo apt-get update
	sudo apt-get install -y --no-install-recommends gnucobol

lint:
	$(CARDDEMO) lint

lint-all:
	$(CARDDEMO) lint --all

baseline:
	$(CARDDEMO) lint --update-baseline

complexity:
	$(CARDDEMO) complexity --top 25

lint-python:
	$(PYTHON) -m ruff check tools tests
	$(PYTHON) -m ruff format --check tools tests

format:
	$(PYTHON) -m ruff format tools tests

typecheck:
	$(PYTHON) -m mypy

syntax:
	$(CARDDEMO) syntax --require-cobc

test:
	$(PYTHON) -m pytest -n auto

test-unit:
	$(PYTHON) -m pytest -n auto tests/unit

coverage:
	mkdir -p $(REPORTS)
	$(PYTHON) -m pytest -n auto --cov --cov-report=term --cov-report=xml:$(REPORTS)/coverage.xml \
		--junitxml=$(REPORTS)/junit.xml

docs:
	$(CARDDEMO) inventory

docs-check:
	$(CARDDEMO) inventory --check
	$(CARDDEMO) check-docs

check-docs: docs-check

run-batch:
	$(CARDDEMO) run-batch $(PROGRAM) --workdir .batch-runs/$(PROGRAM)

check: lint-python typecheck lint syntax docs-check coverage

clean:
	rm -rf .batch-runs $(REPORTS) .coverage .coverage.* htmlcov .pytest_cache .mypy_cache .ruff_cache
