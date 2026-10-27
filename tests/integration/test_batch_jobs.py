"""Compile batch programs with GnuCOBOL and run them against the sample data."""

from pathlib import Path

import pytest

from tools.carddemo import batch
from tools.carddemo.source import Repository

pytestmark = pytest.mark.cobc

REJECT_RECORD = 430  # 350-byte DALYTRAN record + 80-byte validation trailer


def _sample_keys(dd: str, key_length: int | None = None) -> list[str]:
    dataset = batch.DATASETS[dd]
    data = batch.fixed_records(dataset)
    width = key_length or dataset.key_length
    return [data[i : i + width].decode() for i in range(0, len(data), dataset.record_length)]


@pytest.fixture(scope="module")
def repo():
    return Repository()


def test_batch_programs_pass_gnucobol_syntax_check(repo):
    cobc = batch.find_cobc()
    failures = {}
    for source in batch.syntax_candidates(repo):
        proc = batch.syntax_check(repo, source, cobc)
        if proc.returncode:
            failures[source.rel] = proc.stdout
    assert len(batch.syntax_candidates(repo)) >= 15
    assert failures == {}


# CBACT03C and CBCUS01C DISPLAY each record both in their read paragraph and in
# the main loop, so every record appears twice in SYSOUT.
@pytest.mark.parametrize(
    ("program", "dd", "copies"),
    [
        ("CBACT02C", "CARDFILE", 1),
        ("CBACT03C", "XREFFILE", 2),
        ("CBCUS01C", "CUSTFILE", 2),
    ],
)
def test_listing_jobs_print_every_record_in_key_order(tmp_path: Path, program, dd, copies):
    result = batch.run_job(program, tmp_path)
    assert result.returncode == 0, result.output
    lines = result.output.splitlines()
    assert lines[0] == f"START OF EXECUTION OF PROGRAM {program}"
    assert lines[-1] == f"END OF EXECUTION OF PROGRAM {program}"
    key_length = batch.DATASETS[dd].key_length
    printed = [line[:key_length] for line in lines[1:-1] if len(line) >= key_length]
    assert printed == [key for key in sorted(_sample_keys(dd)) for _ in range(copies)]


def test_posting_job_posts_valid_and_rejects_overlimit_transactions(tmp_path: Path):
    result = batch.run_job("CBTRN02C", tmp_path)
    # RC=4 is the program's documented signal that some transactions were rejected.
    assert result.returncode == 4, result.output
    assert "TRANSACTIONS PROCESSED :000000300" in result.output
    assert "TRANSACTIONS REJECTED  :000000043" in result.output

    rejects = result.files["DALYREJS"].read_bytes()
    assert len(rejects) == 43 * REJECT_RECORD
    records = [rejects[i : i + REJECT_RECORD] for i in range(0, len(rejects), REJECT_RECORD)]
    reasons = {r[350:354].decode() + " " + r[354:].decode().strip() for r in records}
    assert reasons == {"0102 OVERLIMIT TRANSACTION"}

    daily_ids = set(_sample_keys("DALYTRAN", key_length=16))
    assert {r[:16].decode() for r in records} <= daily_ids
    assert result.files["TRANFILE"].stat().st_size > 0


def test_posting_job_is_repeatable(tmp_path: Path):
    first = batch.run_job("CBTRN02C", tmp_path / "first")
    second = batch.run_job("CBTRN02C", tmp_path / "second")
    assert first.files["DALYREJS"].read_bytes() == second.files["DALYREJS"].read_bytes()


def test_unknown_job_is_rejected(tmp_path: Path):
    with pytest.raises(KeyError):
        batch.run_job("COSGN00C", tmp_path)
