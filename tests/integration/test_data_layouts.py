"""The sample datasets, the README dataset table, and the copybooks must agree."""

import re

import pytest

from tools.carddemo.batch import DATA_DIR, DATASETS, fixed_records
from tools.carddemo.copybook import record_length
from tools.carddemo.source import REPO_ROOT, Repository

EBCDIC_DIR = REPO_ROOT / "app" / "data" / "EBCDIC"
README_ROW_RE = re.compile(
    r"^\s*\|\s*(AWS\.M2\.CARDDEMO\.[A-Z0-9.]+)\s*\|[^|]*\|\s*([A-Z0-9]+)\s*\|\s*FB\s*\|\s*(\d+)\s*\|"
)

# EBCDIC dataset -> copybook that describes one record.
EBCDIC_LAYOUTS = {
    "AWS.M2.CARDDEMO.USRSEC.PS": "CSUSR01Y",
    "AWS.M2.CARDDEMO.ACCTDATA.PS": "CVACT01Y",
    "AWS.M2.CARDDEMO.ACCDATA.PS": "CVACT01Y",
    "AWS.M2.CARDDEMO.CARDDATA.PS": "CVACT02Y",
    "AWS.M2.CARDDEMO.CUSTDATA.PS": "CVCUS01Y",
    "AWS.M2.CARDDEMO.CARDXREF.PS": "CVACT03Y",
    "AWS.M2.CARDDEMO.DALYTRAN.PS": "CVTRA06Y",
    "AWS.M2.CARDDEMO.DALYTRAN.PS.INIT": "CVTRA06Y",
    "AWS.M2.CARDDEMO.DISCGRP.PS": "CVTRA02Y",
    "AWS.M2.CARDDEMO.TRANCATG.PS": "CVTRA04Y",
    "AWS.M2.CARDDEMO.TRANTYPE.PS": "CVTRA03Y",
    "AWS.M2.CARDDEMO.TCATBALF.PS": "CVTRA01Y",
    "AWS.M2.CARDDEMO.EXPORT.DATA.PS": "CVEXPORT",
}


@pytest.fixture(scope="module")
def repo():
    return Repository()


def _length(repo, copybook: str) -> int:
    return record_length(repo.resolve_copybook(copybook, "base"))


def test_every_ebcdic_dataset_has_a_layout():
    files = {p.name for p in EBCDIC_DIR.iterdir() if p.name.startswith("AWS.")}
    assert files == set(EBCDIC_LAYOUTS)


@pytest.mark.parametrize(("dataset", "copybook"), sorted(EBCDIC_LAYOUTS.items()))
def test_ebcdic_file_is_whole_records(repo, dataset, copybook):
    size = (EBCDIC_DIR / dataset).stat().st_size
    length = _length(repo, copybook)
    assert size > 0 and size % length == 0, f"{dataset} is {size} bytes, not a multiple of {copybook} ({length})"


def test_readme_dataset_table_matches_copybooks(repo):
    rows = [README_ROW_RE.match(line) for line in (REPO_ROOT / "README.md").read_text().splitlines()]
    rows = [m.groups() for m in rows if m]
    assert len(rows) >= 10
    for dataset, copybook, documented in rows:
        assert _length(repo, copybook) == int(documented), f"README length for {dataset} disagrees with {copybook}"


@pytest.mark.parametrize("dd", sorted(DATASETS))
def test_ascii_samples_fit_their_copybook(repo, dd):
    dataset = DATASETS[dd]
    assert _length(repo, dataset.copybook) == dataset.record_length
    data = fixed_records(dataset)
    assert len(data) % dataset.record_length == 0
    count = len(data) // dataset.record_length
    keys = [data[i * dataset.record_length :][: dataset.key_length or 1] for i in range(count)]
    if dataset.indexed:
        assert len(set(keys)) == count, f"{dataset.source} has duplicate KSDS keys"


def test_every_ascii_sample_is_known():
    known = {d.source for d in DATASETS.values()}
    extra = {"discgrp.txt", "trancatg.txt", "trantype.txt"}
    assert {p.name for p in DATA_DIR.iterdir()} == known | extra
