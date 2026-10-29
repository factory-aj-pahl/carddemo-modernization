"""Schedules the target ports instead of the CA7, Control-M, and runbook definitions (B-CYCLE, D-CYCLE). Needs the
Airflow venv: ~/.carddemo/airflow-venv/bin/python modernization/airflow/tests/run_tests.py
"""

from __future__ import annotations

import ast
import atexit
import importlib
import os
import re
import shutil
import sys
import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path
from unittest import mock

if "AIRFLOW_HOME" not in os.environ:
    os.environ["AIRFLOW_HOME"] = tempfile.mkdtemp(prefix="carddemo-airflow-test-")
    atexit.register(shutil.rmtree, os.environ["AIRFLOW_HOME"], True)
DAGS = Path(__file__).resolve().parents[1] / "dags"
sys.path.insert(0, str(DAGS))

from airflow.sdk import DAG  # noqa: E402

DAG_FILES = sorted(p for p in DAGS.glob("carddemo_*.py") if p.name != "carddemo_batch.py")


def all_dags() -> dict[str, DAG]:
    found = {}
    for path in DAG_FILES:
        module = importlib.import_module(path.stem)
        dags = [v for v in vars(module).values() if isinstance(v, DAG)]
        if not dags:
            # A DAG file that only calls its @dag factory keeps no module-level DAG; build one from the factory.
            dags = [getattr(module, path.stem)()]
        for value in dags:
            found[value.dag_id] = value
    return found


def string_literals(path: Path) -> set[str]:
    tree = ast.parse(path.read_text())
    docs = {id(n.body[0].value) for n in ast.walk(tree)
            if isinstance(n, (ast.Module, ast.FunctionDef, ast.ClassDef)) and n.body
            and isinstance(n.body[0], ast.Expr) and isinstance(n.body[0].value, ast.Constant)}
    return {n.value for n in ast.walk(tree)
            if isinstance(n, ast.Constant) and isinstance(n.value, str) and id(n) not in docs}


CYCLE_TASKS = {"business_date", "BACKUP", "POSTTRAN", "month_start", "INTCALC", "CREASTMT"}
NOT_PORTED_STEPS = {"CLOSEFIL", "OPENFIL", "WAITSTEP", "TRANBKP", "COMBTRAN", "TRANIDX"}
RELOAD_COMMANDS = {"load", "loaddb2", "reset", "TRANTYPE", "TRANCATG", "TCATBALF", "DISCGRP", "ACCTFILE", "CARDFILE",
                   "CUSTFILE", "XREFFILE", "TRANFILE", "DUSRSECJ"}


class TargetScheduleTest(unittest.TestCase):
    def setUp(self):
        self.dags = all_dags()
        self.cycle = self.dags["carddemo_daily_cycle"]

    def test_one_ordered_cycle_replaces_the_ca7_trigger_chains(self):
        """Q-CA7-01 Q-CA7-05 Q-CONTROLM-01 Q-CONTROLM-06 Q-COBSWAIT-05: one acyclic cycle in runbook order, no file close, open, or wait steps"""
        self.assertEqual(set(self.cycle.task_ids), CYCLE_TASKS)
        order = [t.task_id for t in self.cycle.topological_sort()]
        self.assertEqual([t for t in order if t in {"BACKUP", "POSTTRAN", "month_start", "INTCALC", "CREASTMT"}],
                         ["BACKUP", "POSTTRAN", "month_start", "INTCALC", "CREASTMT"])
        for dag in self.dags.values():
            self.assertFalse(NOT_PORTED_STEPS & set(dag.task_ids), dag.dag_id)
        for path in DAG_FILES:
            self.assertFalse(NOT_PORTED_STEPS & string_literals(path), path.name)

    def test_no_schedule_reloads_master_or_reference_data(self):
        """Q-CA7-02 Q-CA7-03 Q-RUNBOOK-02 Q-CONTROLM-04: the cycle backs up and posts by appending; no DAG reloads masters, reference data, or category balances"""
        self.assertEqual(self.cycle.get_task("POSTTRAN").upstream_task_ids, {"business_date", "BACKUP"})
        for path in DAG_FILES:
            self.assertFalse(RELOAD_COMMANDS & string_literals(path), path.name)

    def test_interest_runs_once_a_month_inside_the_cycle(self):
        """Q-RUNBOOK-01 Q-CONTROLM-02 Q-CONTROLM-03: INTCALC on the first business date of each month, then CREASTMT, in the daily cycle"""
        branch = self.cycle.get_task("month_start")
        with mock.patch.dict(os.environ, {"CARDDEMO_HOLIDAYS": ""}):
            for month in range(1, 13):
                first = date(2022, month, 1)
                days = [first + timedelta(n) for n in range(31) if (first + timedelta(n)).month == month]
                with_interest = [d for d in days if d.weekday() < 5 and "INTCALC" in branch.python_callable(d.isoformat())]
                self.assertEqual(len(with_interest), 1, month)
                self.assertIn("CREASTMT", branch.python_callable(with_interest[0].isoformat()))
        self.assertIn("INTCALC", self.cycle.get_task("CREASTMT").upstream_task_ids)
        self.assertEqual({d.dag_id for d in self.dags.values() if "INTCALC" in d.task_ids}, {"carddemo_daily_cycle"})

    def test_transaction_type_maintenance_runs_on_request_only(self):
        """Q-CONTROLM-05 Q-CONTROLM-07: MNTTRDB2 has no schedule and is never a prerequisite of the cycle"""
        maint = self.dags["carddemo_trantype_maintenance"]
        self.assertIsNone(maint.schedule)
        self.assertIn("MNTTRDB2", maint.task_ids)
        self.assertNotIn("MNTTRDB2", self.cycle.task_ids)
        self.assertIsNone(self.dags["carddemo_transaction_report"].schedule)

    def test_no_job_definition_holds_clear_text_credentials(self):
        """Q-FTPJCL-01: no host, user ID, or password literal in a DAG; the batch jar reads its settings from the environment"""
        secret = re.compile(r"(?i)(password|passwd|pwd|secret|token|user(name)?)\s*[=:]\s*\S+|ftp://|\bftp\b")
        for path in [*DAG_FILES, DAGS / "carddemo_batch.py"]:
            for literal in string_literals(path):
                self.assertIsNone(secret.search(literal), f"{path.name}: {literal!r}")
            self.assertNotRegex(path.read_text(), r"(?i)password\s*=\s*['\"]", path.name)


if __name__ == "__main__":
    unittest.main()
