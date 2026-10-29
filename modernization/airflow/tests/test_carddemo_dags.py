"""Structure and schedule of the daily cycle DAG (B-CYCLE, D-INTEREST). Needs the Airflow venv:
~/.carddemo/airflow-venv/bin/python modernization/airflow/tests/run_tests.py
"""

from __future__ import annotations

import atexit
import os
import shutil
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

# Importing Airflow creates a configuration in AIRFLOW_HOME; keep a bare test run away from ~/airflow.
if "AIRFLOW_HOME" not in os.environ:
    os.environ["AIRFLOW_HOME"] = tempfile.mkdtemp(prefix="carddemo-airflow-test-")
    atexit.register(shutil.rmtree, os.environ["AIRFLOW_HOME"], True)
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "dags"))

import carddemo_daily_cycle  # noqa: E402
from airflow.sdk.exceptions import AirflowFailException, AirflowSkipException  # noqa: E402
from airflow.timetables.base import DataInterval, TimeRestriction  # noqa: E402
from airflow.timetables.trigger import CronTriggerTimetable  # noqa: E402

DAG = carddemo_daily_cycle.daily_cycle


def branch_with_status(day: str, calculated: bool, last_month: str | None) -> list[str]:
    with mock.patch.object(carddemo_daily_cycle, "interest_status", return_value=(calculated, last_month)) as status:
        result = DAG.get_task("month_start").python_callable(day)
    status.assert_called_once_with(day)
    return result


class DailyCycleDagTest(unittest.TestCase):
    def test_interest_runs_only_on_the_first_business_day_between_posting_and_statements(self):
        """Q-INTCALC-02 B-CYCLE: INTCALC only on the first business day of a month, after POSTTRAN, before CREASTMT"""
        with mock.patch.dict(os.environ, {"CARDDEMO_HOLIDAYS": ""}):
            self.assertEqual(branch_with_status("2022-07-01", False, "2022-06"), ["INTCALC", "CREASTMT"])
            self.assertEqual(branch_with_status("2022-07-04", True, "2022-07"), ["CREASTMT"])
            self.assertEqual(branch_with_status("2022-05-02", False, "2022-04"), ["INTCALC", "CREASTMT"])
            self.assertEqual(branch_with_status("2022-06-10", False, None), ["CREASTMT"])
        with mock.patch.dict(os.environ, {"CARDDEMO_HOLIDAYS": "2022-08-01"}):
            self.assertEqual(branch_with_status("2022-08-01", False, "2022-07"), ["CREASTMT"])
            self.assertEqual(branch_with_status("2022-08-02", False, "2022-07"), ["INTCALC", "CREASTMT"])
        self.assertEqual(DAG.get_task("POSTTRAN").upstream_task_ids, {"business_date", "BACKUP"})
        self.assertEqual(DAG.get_task("month_start").upstream_task_ids, {"business_date", "POSTTRAN"})
        self.assertEqual(DAG.get_task("INTCALC").upstream_task_ids, {"business_date", "month_start"})
        self.assertEqual(DAG.get_task("CREASTMT").upstream_task_ids, {"business_date", "month_start", "INTCALC"})
        self.assertEqual(DAG.get_task("CREASTMT").trigger_rule, "none_failed_min_one_success")

    def test_a_missed_first_business_day_moves_the_interest_to_the_next_cycle_date_of_the_month(self):
        """B-CYCLE Q-CBACT04C-08: INTCALC on the month's first cycle date without interest; skipped once calculated"""
        with mock.patch.dict(os.environ, {"CARDDEMO_HOLIDAYS": "2022-07-04"}):
            # 2022-07-01 was not run: the next issued date charges July.
            self.assertEqual(branch_with_status("2022-07-05", False, "2022-06"), ["INTCALC", "CREASTMT"])
            self.assertEqual(branch_with_status("2022-07-29", False, "2022-05"), ["INTCALC", "CREASTMT"])
            # July already charged: later dates, and a rerun of the first business day, skip INTCALC (no exit 8).
            self.assertEqual(branch_with_status("2022-07-06", True, "2022-07"), ["CREASTMT"])
            self.assertEqual(branch_with_status("2022-07-01", True, "2022-07"), ["CREASTMT"])
            # Not business days: a holiday or a weekend date given by hand never charges interest.
            self.assertEqual(branch_with_status("2022-07-04", False, "2022-06"), ["CREASTMT"])
            self.assertEqual(branch_with_status("2022-07-02", False, "2022-06"), ["CREASTMT"])
            # A database without any calculated month (fresh load or migration) waits for the first business day.
            self.assertEqual(branch_with_status("2022-07-05", False, None), ["CREASTMT"])

    def test_the_cycle_has_no_combtran_step(self):
        """Q-COMBTRAN-02 Q-COMBTRAN-01: no COMBTRAN merge or reload; interest rows go straight into the transactions"""
        self.assertEqual(set(DAG.task_ids), {"business_date", "BACKUP", "POSTTRAN", "month_start", "INTCALC", "CREASTMT"})


def utc(*args: int) -> datetime:
    return datetime(*args, tzinfo=timezone.utc)


class DailyCycleScheduleTest(unittest.TestCase):
    def test_the_cycle_is_scheduled_at_22_utc_monday_to_friday_and_created_paused(self):
        """B-CYCLE: one scheduled run per weekday at 22:00 UTC, no catchup, paused until an operator unpauses it"""
        tt = DAG.timetable
        self.assertEqual((tt.expression, str(tt.timezone)), ("0 22 * * 1-5", "UTC"))
        self.assertFalse(DAG.catchup)
        self.assertTrue(DAG.is_paused_upon_creation)
        self.assertIsNone(DAG.start_date)
        self.assertEqual(DAG.max_active_runs, 1)
        core = CronTriggerTimetable(tt.expression, timezone=str(tt.timezone))
        last = DataInterval(utc(2022, 6, 30, 22), utc(2022, 6, 30, 22))  # Thursday
        runs = []
        for _ in range(6):
            info = core.next_dagrun_info(last_automated_data_interval=last,
                                         restriction=TimeRestriction(earliest=None, latest=None, catchup=True))
            runs.append(info.logical_date)
            last = info.data_interval
        self.assertEqual(runs, [utc(2022, 7, 1, 22), utc(2022, 7, 4, 22), utc(2022, 7, 5, 22), utc(2022, 7, 6, 22),
                                utc(2022, 7, 7, 22), utc(2022, 7, 8, 22)])

    def test_a_scheduled_run_posts_its_logical_date_and_skips_holidays_and_weekends(self):
        """B-CYCLE: the scheduled run of a business date posts that date; a holiday run is skipped, not failed"""
        resolve = carddemo_daily_cycle.resolve_business_date
        with mock.patch.dict(os.environ, {"CARDDEMO_HOLIDAYS": "2022-07-04"}):
            self.assertEqual(resolve({"business_date": None}, utc(2022, 7, 1, 22)), "2022-07-01")
            self.assertEqual(resolve({}, utc(2022, 7, 5, 22)), "2022-07-05")
            with self.assertRaises(AirflowSkipException):
                resolve({"business_date": None}, utc(2022, 7, 4, 22))
            with self.assertRaises(AirflowSkipException):
                resolve({}, utc(2022, 7, 2, 22))
            with self.assertRaises(AirflowFailException):
                resolve({}, None)

    def test_a_manual_run_posts_the_business_date_param_as_given(self):
        """B-CYCLE: the business_date param is used as given, even on a holiday or a weekend"""
        resolve = carddemo_daily_cycle.resolve_business_date
        with mock.patch.dict(os.environ, {"CARDDEMO_HOLIDAYS": "2022-07-04"}):
            self.assertEqual(resolve({"business_date": "2022-06-10"}, None), "2022-06-10")
            self.assertEqual(resolve({"business_date": "2022-07-04"}, utc(2022, 7, 6, 22)), "2022-07-04")
            self.assertEqual(resolve({"business_date": "2022-07-02"}, None), "2022-07-02")
        self.assertIn("business_date", DAG.params)


if __name__ == "__main__":
    unittest.main()
