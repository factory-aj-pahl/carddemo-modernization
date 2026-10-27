"""Repository-wide gates that CI also runs: lint baseline, generated docs, AGENTS.md."""

from tools.carddemo import agentsmd, inventory
from tools.carddemo.__main__ import COMMANDS, main
from tools.carddemo.findings import apply_baseline, load_baseline
from tools.carddemo.rules import run_all
from tools.carddemo.source import Repository


def test_no_new_lint_findings():
    result = apply_baseline(run_all(Repository()), load_baseline())
    assert [f.format() for f in result.new] == []


def test_inventory_is_current():
    assert inventory.is_current(Repository()), "run `make docs` to regenerate docs/INVENTORY.md"


def test_agents_md_references_exist():
    assert agentsmd.validate(set(COMMANDS)) == []


def test_cli_lint_and_complexity(capsys):
    assert main(["lint"]) == 0
    assert main(["complexity", "--top", "3"]) == 0
    out = capsys.readouterr().out
    assert "0 new finding(s)" in out
    assert "3300-SETUP-SCREEN-ATTRS" in out


def test_cli_inventory_check_and_docs(capsys):
    assert main(["inventory", "--check"]) == 0
    assert main(["check-docs"]) == 0
