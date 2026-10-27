import pytest

from tools.carddemo import rules
from tools.carddemo.source import Repository

from ..conftest import cobol


def _rules(repo: Repository, rule) -> list:
    return list(rule(repo))


def _program(name: str, *body: str, program_id: str | None = None) -> str:
    return cobol(
        "IDENTIFICATION DIVISION.",
        f"PROGRAM-ID. {program_id or name}.",
        "DATA DIVISION.",
        "WORKING-STORAGE SECTION.",
        *body,
    )


def test_tabs_and_wide_records(make_repo):
    repo = make_repo({"app/jcl/JOB1.jcl": "//JOB1 JOB\n//\tSTEP\n" + "//" + "X" * 80 + "\n"})
    found = _rules(repo, rules.check_record_format)
    assert [(f.rule, f.line) for f in found] == [("CD001", 2), ("CD002", 3)]


def test_member_names_and_program_id(make_repo):
    repo = make_repo(
        {
            "app/cbl/GOODPGM.cbl": _program("GOODPGM"),
            "app/cbl/BADPGM.cbl": _program("BADPGM", program_id="OTHERPGM"),
            "app/cpy/TOOLONGNAME.cpy": cobol("01 A PIC X."),
            "app/jcl/lower.jcl": "//LOWER JOB\n",
        }
    )
    found = {(f.rule, f.path) for f in _rules(repo, rules.check_naming)}
    assert found == {
        ("CD011", "app/cbl/BADPGM.cbl"),
        ("CD010", "app/cpy/TOOLONGNAME.cpy"),
        ("CD010", "app/jcl/lower.jcl"),
    }


def test_large_sources(make_repo, monkeypatch):
    monkeypatch.setattr(rules, "MAX_SOURCE_LINES", 5)
    monkeypatch.setattr(rules, "MAX_TRACKED_FILE_BYTES", 100)
    repo = make_repo({"app/cbl/BIG.cbl": _program("BIG", *["01 A PIC X."] * 5), "diagrams/huge.png": "x" * 101})
    found = {(f.rule, f.path) for f in _rules(repo, rules.check_size)}
    assert ("CD021", "app/cbl/BIG.cbl") in found
    assert ("CD022", "diagrams/huge.png") in found


def test_complexity_and_unreachable_rules(make_repo, monkeypatch):
    monkeypatch.setattr(rules, "MAX_PARAGRAPH_COMPLEXITY", 2)
    repo = make_repo(
        {
            "app/cbl/CPLX.cbl": _program(
                "CPLX",
                "01 A PIC X.",
                "PROCEDURE DIVISION.",
                "MAIN-PARA.",
                "    IF A = 'X' AND A = 'Y' DISPLAY 'Z' END-IF",
                "    GOBACK.",
                "DEAD-PARA.",
                "    EXIT.",
            )
        }
    )
    found = {(f.rule, f.key, f.value) for f in _rules(repo, rules.check_procedures)}
    assert found == {("CD020", "MAIN-PARA", 3), ("CD030", "DEAD-PARA", None)}


def test_copybook_usage_and_module_boundaries(make_repo):
    repo = make_repo(
        {
            "app/cbl/BASEPGM.cbl": _program(
                "BASEPGM", "COPY BASECPY.", "COPY MODCPY.", "COPY DFHAID.", "COPY MISSING."
            ),
            "app/cpy/BASECPY.cpy": cobol("01 B PIC X."),
            "app/cpy/UNUSEDCP.cpy": cobol("01 U PIC X."),
            "app/app-one/cbl/ONEPGM.cbl": _program("ONEPGM", "COPY MODCPY.", "COPY BASECPY.", "COPY TWOCPY."),
            "app/app-one/cpy/MODCPY.cpy": cobol("01 M PIC X."),
            "app/app-two/cpy/TWOCPY.cpy": cobol("01 T PIC X."),
        }
    )
    found = {(f.rule, f.path, f.key) for f in _rules(repo, rules.check_copybooks)}
    assert found == {
        ("CD040", "app/cbl/BASEPGM.cbl", "MODCPY"),
        ("CD040", "app/app-one/cbl/ONEPGM.cbl", "TWOCPY"),
        ("CD032", "app/cbl/BASEPGM.cbl", "MISSING"),
        ("CD031", "app/cpy/UNUSEDCP.cpy", "UNUSEDCP"),
    }


def test_sql_include_counts_as_copybook_use(make_repo):
    repo = make_repo(
        {
            "app/app-db2/cbl/DBPGM.cbl": _program(
                "DBPGM", "    EXEC SQL INCLUDE DCLTAB END-EXEC.", "    EXEC SQL INCLUDE SQLCA END-EXEC."
            ),
            "app/app-db2/dcl/DCLTAB.dcl": cobol("01 DCLTAB.", "   05 COL1 PIC X."),
        }
    )
    assert _rules(repo, rules.check_copybooks) == []


@pytest.mark.parametrize(
    ("text", "flagged"),
    [
        ("* TODO fix this later", True),
        ("* FIXME: wrong rounding", True),
        ("* TODO(#42) handle leap years", False),
        ("* HACK(CARD-17) temporary", False),
        ("01 WS-CODE PIC XXX.", False),
    ],
)
def test_debt_markers_need_issue_links(make_repo, text, flagged):
    repo = make_repo({"app/cbl/DEBT.cbl": _program("DEBT", text)})
    assert bool(_rules(repo, rules.check_debt_markers)) is flagged


def test_pii_display(make_repo):
    repo = make_repo(
        {
            "app/cpy/CARDREC.cpy": cobol("01 CARD-RECORD.", "   05 CARD-NUM PIC X(16).", "   05 CARD-NAME PIC X(20)."),
            "app/cbl/SHOWPII.cbl": _program(
                "SHOWPII",
                "COPY CARDREC.",
                "01 WS-COUNT PIC 9.",
                "PROCEDURE DIVISION.",
                "    DISPLAY 'COUNT ' WS-COUNT",
                "    DISPLAY 'CARD: '",
                "            CARD-NUM",
                "    DISPLAY CARD-RECORD",
                "    DISPLAY 'CARD-NUM IN A LITERAL IS FINE'",
                "    GOBACK.",
            ),
        }
    )
    found = [(f.line, f.key) for f in _rules(repo, rules.check_pii_display)]
    assert [key for _, key in found] == ["CARD-NUM", "CARD-RECORD"]


def test_run_all_is_sorted(make_repo):
    repo = make_repo({"app/cpy/ZUNUSED.cpy": cobol("01 Z PIC X."), "app/cpy/AUNUSED.cpy": cobol("01 A PIC X.")})
    paths = [f.path for f in rules.run_all(repo)]
    assert paths == sorted(paths)
