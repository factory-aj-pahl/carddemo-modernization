from tools.carddemo.duplicates import find_duplicates

from ..conftest import cobol

SHARED = [f"    MOVE {i} TO WS-COUNTER-{i % 7}" for i in range(40)]


def test_detects_block_shared_by_two_programs(make_repo):
    repo = make_repo(
        {
            "app/cbl/ONE.cbl": cobol("PROCEDURE DIVISION.", "    DISPLAY 'ONE'", *SHARED),
            "app/cbl/TWO.cbl": cobol("PROCEDURE DIVISION.", "    DISPLAY 'TWO'", "* comments are ignored", *SHARED),
        }
    )
    [finding] = find_duplicates(repo)
    assert finding.rule == "CD050"
    assert finding.path == "app/cbl/ONE.cbl"
    assert finding.value == 40
    assert finding.key.startswith("app/cbl/TWO.cbl:")


def test_ignores_short_matches_and_generated_bms_copybooks(make_repo):
    repo = make_repo(
        {
            "app/cbl/ONE.cbl": cobol(*SHARED[:20]),
            "app/cbl/TWO.cbl": cobol(*SHARED[:20]),
            "app/cpy-bms/MAP1.CPY": cobol(*SHARED),
            "app/cpy-bms/MAP2.CPY": cobol(*SHARED),
        }
    )
    assert find_duplicates(repo) == []


def test_whitespace_differences_still_match(make_repo):
    spaced = [line.replace(" TO ", "    TO   ") for line in SHARED]
    repo = make_repo({"app/cbl/ONE.cbl": cobol(*SHARED), "app/cbl/TWO.cbl": cobol(*spaced)})
    assert len(find_duplicates(repo)) == 1
