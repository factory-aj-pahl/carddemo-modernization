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


if __name__ == "__main__":
    unittest.main()
