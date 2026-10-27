import pytest

from tools.carddemo.copybook import item_length, parse_records, picture_digits, record_length
from tools.carddemo.source import SourceFile

from ..conftest import cobol


@pytest.mark.parametrize(
    ("picture", "expected"),
    [("X(25)", (25, 0)), ("S9(10)V99", (12, 12)), ("9(04)", (4, 4)), ("XXX", (3, 0)), ("-9(7).99", (11, 9))],
)
def test_picture_digits(picture, expected):
    assert picture_digits(picture) == expected


@pytest.mark.parametrize(
    ("picture", "usage", "length"),
    [
        ("X(25)", None, 25),
        ("S9(10)V99", None, 12),
        ("S9(10)V99", "COMP-3", 7),
        ("S9(9)V99", "PACKED-DECIMAL", 6),
        ("9(4)", "COMP", 2),
        ("9(9)", "BINARY", 4),
        ("S9(11)", "COMP", 8),
        ("9(9)", "COMP-5", 4),
    ],
)
def test_item_length(picture, usage, length):
    assert item_length(picture, usage) == length


def _source(tmp_path, text):
    path = tmp_path / "REC.cpy"
    path.write_text(text)
    return SourceFile(path, tmp_path)


def test_occurs_redefines_and_inherited_usage(tmp_path):
    src = _source(
        tmp_path,
        cobol(
            "* sample layout",
            "01  ARR-ARRAY-REC.",
            "    05  ARR-ACCT-ID                PIC 9(11).",
            "    05  ARR-ACCT-BAL OCCURS 5  TIMES.",
            "      10  ARR-ACCT-CURR-BAL        PIC S9(10)V99.",
            "      10  ARR-ACCT-CURR-CYC-DEBIT  PIC S9(10)V99",
            "                                   USAGE IS COMP-3.",
            "    05  ARR-ALT REDEFINES ARR-ACCT-ID PIC X(11).",
            "    05  ARR-PACKED COMP-3.",
            "      10  ARR-P1                   PIC S9(5).",
            "    05  ARR-FLAG                   PIC X VALUE 'Y'.",
            "      88  ARR-FLAG-ON              VALUE 'Y'.",
        ),
    )
    # 11 + 5 * (12 + 7) + 3 + 1; the REDEFINES and 88-level add nothing.
    assert record_length(src) == 11 + 95 + 3 + 1


def test_multiple_records_selectable(tmp_path):
    src = _source(tmp_path, cobol("01  A-REC.", "    05 A1 PIC X(10).", "01  B-REC.", "    05 B1 PIC X(20)."))
    assert [r.name for r in parse_records(src)] == ["A-REC", "B-REC"]
    assert record_length(src, "B-REC") == 20


def test_record_length_without_records_raises(tmp_path):
    with pytest.raises(ValueError):
        record_length(_source(tmp_path, cobol("* only a comment")))
