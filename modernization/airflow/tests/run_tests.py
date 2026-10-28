"""Runs the Airflow tests and writes JUnit XML for verification/trace.py.

Each test case is named `<method> › <first docstring line>`, so the rule IDs at the start of a test's
docstring are its traceability tags (trace.py --junit modernization/airflow/tests/junit.xml).

Usage: ~/.carddemo/airflow-venv/bin/python modernization/airflow/tests/run_tests.py [--junit PATH]
"""

from __future__ import annotations

import argparse
import sys
import time
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent


class RecordingResult(unittest.TextTestResult):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.cases: list[tuple[unittest.TestCase, str, str, float]] = []
        self._started = 0.0

    def startTest(self, test):
        self._started = time.monotonic()
        super().startTest(test)

    def _record(self, test, status, detail=""):
        self.cases.append((test, status, detail, time.monotonic() - self._started))

    def addSuccess(self, test):
        super().addSuccess(test)
        self._record(test, "passed")

    def addFailure(self, test, err):
        super().addFailure(test, err)
        self._record(test, "failure", self._exc_info_to_string(err, test))

    def addError(self, test, err):
        super().addError(test, err)
        self._record(test, "error", self._exc_info_to_string(err, test))

    def addSkip(self, test, reason):
        super().addSkip(test, reason)
        self._record(test, "skipped", reason)


def case_name(test: unittest.TestCase) -> str:
    method = getattr(test, "_testMethodName", str(test))
    doc = test.shortDescription()
    return f"{method} › {doc}" if doc else method


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--junit", type=Path, default=HERE / "junit.xml")
    a = ap.parse_args(argv)
    suite = unittest.defaultTestLoader.discover(str(HERE), pattern="test_*.py")
    result = unittest.TextTestRunner(resultclass=RecordingResult, verbosity=2).run(suite)
    root = ET.Element("testsuite", name="airflow", tests=str(len(result.cases)),
                      failures=str(len(result.failures)), errors=str(len(result.errors)),
                      skipped=str(len(result.skipped)))
    for test, status, detail, seconds in result.cases:
        tc = ET.SubElement(root, "testcase", classname=type(test).__module__ + "." + type(test).__qualname__,
                           name=case_name(test), time=f"{seconds:.3f}")
        if status != "passed":
            ET.SubElement(tc, status, message=detail.splitlines()[-1] if detail else status).text = detail
    a.junit.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(root).write(a.junit, encoding="utf-8", xml_declaration=True)
    print(f"wrote {a.junit}")
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main())
