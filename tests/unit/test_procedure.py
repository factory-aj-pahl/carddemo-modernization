from tools.carddemo import procedure

from ..conftest import cobol

HEADER = (
    "IDENTIFICATION DIVISION.",
    "PROGRAM-ID. TESTPGM.",
    "DATA DIVISION.",
    "WORKING-STORAGE SECTION.",
    "01 WS-A PIC X.",
    "PROCEDURE DIVISION.",
)


def _model(make_repo, *body, extra=None):
    files = {"app/cbl/TESTPGM.cbl": cobol(*HEADER, *body)}
    files.update(extra or {})
    repo = make_repo(files)
    return procedure.build(repo, repo.programs[0])


def test_complexity_counts_decisions_but_not_literals_or_when_other(make_repo):
    model = _model(
        make_repo,
        "MAIN-PARA.",
        "    IF WS-A = 'IF AND OR' AND WS-A = 'B'",
        "       PERFORM UNTIL WS-A = 'X' OR WS-A = 'Y'",
        "       END-PERFORM",
        "    END-IF",
        "    EVALUATE TRUE",
        "      WHEN WS-A = 'Q' CONTINUE",
        "      WHEN OTHER CONTINUE",
        "    END-EVALUATE",
        "    GOBACK.",
    )
    # IF, AND, UNTIL, OR, one WHEN => 1 + 5
    assert model.names["MAIN-PARA"].complexity == 6


def test_unreachable_after_goback(make_repo):
    model = _model(
        make_repo,
        "PERFORM 1000-WORK.",
        "GOBACK.",
        "1000-WORK.",
        "    DISPLAY 'WORK'.",
        "1000-EXIT.",
        "    EXIT.",
        "9999-ABEND.",
        "    GOBACK.",
        "9999-EXIT.",
        "    EXIT.",
    )
    # A plain PERFORM returns after 1000-WORK, so its EXIT paragraph is dead too.
    assert [p.name for p in model.unreachable()] == ["1000-EXIT", "9999-ABEND", "9999-EXIT"]


def test_thru_range_returns_at_its_end_and_go_to_falls_through(make_repo):
    model = _model(
        make_repo,
        "PERFORM 1000-START THRU 1000-EXIT.",
        "GO TO 3000-FINISH.",
        "1000-START.",
        "    DISPLAY 'A'.",
        "1000-MIDDLE.",
        "    DISPLAY 'B'.",
        "1000-EXIT.",
        "    EXIT.",
        "2000-NEVER.",
        "    GOBACK.",
        "3000-FINISH.",
        "    DISPLAY 'C'.",
        "3000-AFTER.",
        "    GOBACK.",
    )
    assert [p.name for p in model.unreachable()] == ["2000-NEVER"]
    assert {"1000-MIDDLE", "3000-AFTER"} <= model.reachable


def test_sequential_entry_falls_into_first_paragraph(make_repo):
    model = _model(make_repo, "DISPLAY 'START'.", "1000-FIRST.", "    DISPLAY 'A'.", "1000-SECOND.", "    GOBACK.")
    assert model.unreachable() == []


def test_paragraphs_from_copybooks_resolve_references(make_repo):
    model = _model(
        make_repo,
        "PERFORM YYYY-SHARED.",
        "GOBACK.",
        "COPY SHAREDP.",
        "8000-CALLBACK.",
        "    DISPLAY 'CALLED FROM COPYBOOK'.",
        extra={"app/cpy/SHAREDP.cpy": cobol("YYYY-SHARED.", "    PERFORM 8000-CALLBACK.", "    EXIT.")},
    )
    assert "YYYY-SHARED" in model.names
    assert model.names["YYYY-SHARED"].origin == "app/cpy/SHAREDP.cpy"
    assert model.unreachable() == []


def test_section_performs_its_paragraphs(make_repo):
    model = _model(
        make_repo,
        "PERFORM A-SECTION.",
        "STOP RUN.",
        "A-SECTION SECTION.",
        "A-ONE.",
        "    DISPLAY 'ONE'.",
        "A-TWO.",
        "    DISPLAY 'TWO'.",
        "B-SECTION SECTION.",
        "B-ONE.",
        "    DISPLAY 'NEVER'.",
    )
    assert [p.name for p in model.unreachable()] == ["B-SECTION", "B-ONE"]


def test_copybook_without_procedure_division_has_no_model(make_repo):
    repo = make_repo({"app/cbl/DATAONLY.cbl": cobol("IDENTIFICATION DIVISION.", "PROGRAM-ID. DATAONLY.")})
    assert procedure.build(repo, repo.programs[0]) is None
