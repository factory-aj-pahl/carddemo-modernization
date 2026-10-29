"""Unit tests for the DAG helpers (business days, report retention). Run with the Airflow venv:
~/.carddemo/airflow-venv/bin/python -m unittest discover -s modernization/airflow/tests
"""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
from datetime import date
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "dags"))

import carddemo_batch  # noqa: E402


class FirstBusinessDayTest(unittest.TestCase):
    def test_first_weekday_of_the_month_is_the_first_business_day(self):
        self.assertTrue(carddemo_batch.is_first_business_day(date(2022, 7, 1), set()))  # Friday
        self.assertFalse(carddemo_batch.is_first_business_day(date(2022, 7, 4), set()))
        self.assertTrue(carddemo_batch.is_first_business_day(date(2022, 5, 2), set()))  # 1 May is a Sunday
        self.assertFalse(carddemo_batch.is_first_business_day(date(2022, 5, 1), set()))

    def test_a_holiday_moves_the_first_business_day(self):
        closed = {date(2022, 8, 1)}
        self.assertFalse(carddemo_batch.is_first_business_day(date(2022, 8, 1), closed))
        self.assertTrue(carddemo_batch.is_first_business_day(date(2022, 8, 2), closed))

    def test_holidays_come_from_the_environment_and_default_to_none(self):
        with mock.patch.dict(os.environ, {"CARDDEMO_HOLIDAYS": "2022-08-01, 2022-12-26"}):
            self.assertEqual(carddemo_batch.holidays(), {date(2022, 8, 1), date(2022, 12, 26)})
            self.assertTrue(carddemo_batch.is_first_business_day(date(2022, 8, 2)))
        with mock.patch.dict(os.environ, {}, clear=True):
            self.assertEqual(carddemo_batch.holidays(), set())


class InterestDueTest(unittest.TestCase):
    def test_interest_is_due_on_the_first_cycle_date_of_a_month_not_yet_calculated(self):
        due = carddemo_batch.interest_due
        closed = {date(2022, 7, 4)}
        self.assertTrue(due(date(2022, 7, 1), False, "2022-06", closed))
        self.assertTrue(due(date(2022, 7, 1), False, None, closed))
        self.assertTrue(due(date(2022, 7, 5), False, "2022-06", closed))
        self.assertFalse(due(date(2022, 7, 5), True, "2022-07", closed))
        self.assertFalse(due(date(2022, 7, 1), True, "2022-07", closed))
        self.assertFalse(due(date(2022, 7, 4), False, "2022-06", closed))
        self.assertFalse(due(date(2022, 7, 9), False, "2022-06", closed))
        self.assertFalse(due(date(2022, 7, 5), False, None, closed))

    def run_status(self, stdout: str, rc: int = 0) -> tuple[bool, str | None]:
        done = mock.Mock(returncode=rc, stdout=stdout, stderr="")
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(os.environ, {"CARDDEMO_OUTBOX": tmp}), \
                mock.patch.object(carddemo_batch.subprocess, "run", return_value=done) as run:
            result = carddemo_batch.interest_status("2022-07-05")
        self.assertEqual(run.call_args.args[0][-2:], ["INTSTATUS", "businessDate=2022-07-05"])
        return result

    def test_interest_status_reads_the_intstatus_line(self):
        self.assertEqual(self.run_status("INTEREST ALREADY CALCULATED FOR 2022-07\n"), (True, None))
        self.assertEqual(self.run_status("INTEREST NOT CALCULATED FOR 2022-07; LAST CALCULATED FOR 2022-06\n"),
                         (False, "2022-06"))
        self.assertEqual(self.run_status("INTEREST NOT CALCULATED FOR 2022-07; NO EARLIER MONTH CALCULATED\n"),
                         (False, None))
        with self.assertRaises(carddemo_batch.AirflowFailException):
            self.run_status("something else\n")
        with self.assertRaises(carddemo_batch.AirflowFailException):
            self.run_status("INVALID OR MISSING BUSINESS DATE\n", rc=12)


class ReportRetentionTest(unittest.TestCase):
    def test_only_the_last_ten_reports_are_kept(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(os.environ, {"CARDDEMO_OUTBOX": tmp}):
            for n in range(12):
                d = carddemo_batch.report_dir(f"manual__run{n:02d}")
                d.mkdir(parents=True)
                (d / "TRANREPT.txt").write_text("x")
                os.utime(d / "TRANREPT.txt", ns=(n * 10**9, n * 10**9))
            deleted = carddemo_batch.prune_reports()
            self.assertEqual(sorted(p.name for p in deleted), ["manual__run00", "manual__run01"])
            kept = sorted(p.name for p in (Path(tmp) / "reports").iterdir())
            self.assertEqual(kept, [f"manual__run{n:02d}" for n in range(2, 12)])

    def test_a_run_id_with_a_slash_is_refused(self):
        with self.assertRaises(Exception):
            carddemo_batch.report_dir("a/b")


class ExitCodeTest(unittest.TestCase):
    def run_with(self, rc: int, stderr: str, *args: str) -> str:
        done = mock.Mock(returncode=rc, stdout="", stderr=stderr)
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(os.environ, {"CARDDEMO_OUTBOX": tmp}), \
                mock.patch.object(carddemo_batch.subprocess, "run", return_value=done):
            if rc in carddemo_batch.SUCCESS:
                self.assertEqual(carddemo_batch.run_batch(*args), rc)
                return ""
            with self.assertRaises(carddemo_batch.AirflowFailException) as e:
                carddemo_batch.run_batch(*args)
            return str(e.exception)

    def test_rc16_of_an_authorization_program_is_its_9999_abend_not_a_usage_error(self):
        for program in ("CBPAUP0C", "PAUDBUNL", "PAUDBLOD", "DBUNLDGS"):
            self.assertEqual(self.run_with(16, "", program, "x=y"),
                             f"{program} ended with program return code 16 (9999-ABEND) (exit 16)")

    def test_rc16_with_the_usage_line_is_a_usage_error_for_every_command(self):
        usage = "carddemo-batch: PAUDBUNL needs rootFile and childFile\nusage: POSTTRAN ...\n"
        self.assertEqual(self.run_with(16, usage, "PAUDBUNL"), "PAUDBUNL usage error (exit 16)")
        self.assertEqual(self.run_with(16, "carddemo-batch: missing parameter dailyFile\nusage: POSTTRAN ...", "POSTTRAN"),
                         "POSTTRAN usage error (exit 16)")
        self.assertEqual(self.run_with(16, "", "POSTTRAN"), "POSTTRAN usage error (exit 16)")

    def test_other_codes_keep_their_labels(self):
        self.assertEqual(self.run_with(0, "", "CBPAUP0C"), "")
        self.assertEqual(self.run_with(4, "", "POSTTRAN"), "")
        self.assertEqual(self.run_with(8, "", "INTCALC"), "INTCALC refused (exit 8)")
        self.assertEqual(self.run_with(12, "", "POSTTRAN"), "POSTTRAN invalid date or date range (exit 12)")
        self.assertEqual(self.run_with(99, "", "CBPAUP0C"), "CBPAUP0C abend (exit 99)")
        self.assertEqual(self.run_with(3, "", "CBPAUP0C"), "CBPAUP0C failed (exit 3)")

    def test_rc12_of_cobtupdt_is_a_stopped_and_rolled_back_run(self):
        self.assertEqual(self.run_with(12, "", "COBTUPDT", "inputFile=x"), "COBTUPDT stopped and rolled back (exit 12)")


if __name__ == "__main__":
    unittest.main()
