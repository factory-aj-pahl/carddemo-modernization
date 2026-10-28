"""Structure of the daily cycle DAG (B-CYCLE, D-INTEREST). Needs the Airflow venv:
~/.carddemo/airflow-venv/bin/python modernization/airflow/tests/run_tests.py
"""

from __future__ import annotations

import atexit
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

# Importing Airflow creates a configuration in AIRFLOW_HOME; keep a bare test run away from ~/airflow.
if "AIRFLOW_HOME" not in os.environ:
    os.environ["AIRFLOW_HOME"] = tempfile.mkdtemp(prefix="carddemo-airflow-test-")
    atexit.register(shutil.rmtree, os.environ["AIRFLOW_HOME"], True)
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "dags"))

import carddemo_daily_cycle  # noqa: E402

DAG = carddemo_daily_cycle.daily_cycle


class DailyCycleDagTest(unittest.TestCase):
    def test_interest_runs_only_on_the_first_business_day_between_posting_and_statements(self):
        """Q-INTCALC-02 B-CYCLE: INTCALC only on the first business day of a month, after POSTTRAN, before CREASTMT"""
        branch = DAG.get_task("month_start")
        with mock.patch.dict(os.environ, {"CARDDEMO_HOLIDAYS": ""}):
            self.assertEqual(branch.python_callable("2022-07-01"), ["INTCALC", "CREASTMT"])
            self.assertEqual(branch.python_callable("2022-07-04"), ["CREASTMT"])
            self.assertEqual(branch.python_callable("2022-05-02"), ["INTCALC", "CREASTMT"])
        with mock.patch.dict(os.environ, {"CARDDEMO_HOLIDAYS": "2022-08-01"}):
            self.assertEqual(branch.python_callable("2022-08-01"), ["CREASTMT"])
            self.assertEqual(branch.python_callable("2022-08-02"), ["INTCALC", "CREASTMT"])
        self.assertEqual(DAG.get_task("POSTTRAN").upstream_task_ids, {"business_date", "BACKUP"})
        self.assertEqual(branch.upstream_task_ids, {"business_date", "POSTTRAN"})
        self.assertEqual(DAG.get_task("INTCALC").upstream_task_ids, {"business_date", "month_start"})
        self.assertEqual(DAG.get_task("CREASTMT").upstream_task_ids, {"business_date", "month_start", "INTCALC"})
        self.assertEqual(DAG.get_task("CREASTMT").trigger_rule, "none_failed_min_one_success")

    def test_the_cycle_has_no_combtran_step(self):
        """Q-COMBTRAN-02 Q-COMBTRAN-01: no COMBTRAN merge or reload; interest rows go straight into the transactions"""
        self.assertEqual(set(DAG.task_ids), {"business_date", "BACKUP", "POSTTRAN", "month_start", "INTCALC", "CREASTMT"})


if __name__ == "__main__":
    unittest.main()
