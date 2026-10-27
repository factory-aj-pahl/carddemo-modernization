from tools.carddemo import agentsmd


def test_reports_missing_targets_subcommands_and_paths(tmp_path):
    (tmp_path / "Makefile").write_text("lint:\n\techo lint\ntest: lint\n")
    (tmp_path / "tools").mkdir()
    doc = tmp_path / "AGENTS.md"
    doc.write_text(
        "Run `make lint`, then `make deploy`.\n"
        "Use `python3 -m tools.carddemo lint` or `python3 -m tools.carddemo explode`.\n"
        "See `tools/` and `docs/missing.md`, `app/<module>/cbl`, and `app/cbl/*.cbl`.\n"
    )
    problems = agentsmd.validate({"lint"}, doc, tmp_path)
    assert problems == [
        "`make deploy` is documented but the Makefile has no such target",
        "`python3 -m tools.carddemo explode` is documented but no such subcommand exists",
        "path `docs/missing.md` is referenced but does not exist",
    ]


def test_missing_file(tmp_path):
    assert agentsmd.validate(set(), tmp_path / "AGENTS.md", tmp_path) == ["AGENTS.md is missing"]
