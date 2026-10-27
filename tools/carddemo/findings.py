"""Findings and the baseline that records pre-existing ones.

The CardDemo sources intentionally mix coding styles, so existing findings are
recorded in lint-baseline.json and only new findings fail the check. For
metric rules the baseline also records the allowed value, so an existing hot
spot cannot get worse without failing the check.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

BASELINE_PATH = Path(__file__).with_name("lint-baseline.json")


@dataclass(frozen=True)
class Finding:
    rule: str
    path: str
    line: int
    message: str
    key: str = ""
    value: int | None = None

    @property
    def fingerprint(self) -> tuple[str, str, str]:
        return self.rule, self.path, self.key

    def format(self) -> str:
        return f"{self.path}:{self.line}: {self.rule} {self.message}"


@dataclass
class BaselineResult:
    new: list[Finding]
    known: list[Finding]
    stale: list[dict[str, Any]]


def load_baseline(path: Path = BASELINE_PATH) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    entries: list[dict[str, Any]] = json.loads(path.read_text())["findings"]
    return entries


def write_baseline(findings: list[Finding], path: Path = BASELINE_PATH) -> None:
    entries = []
    for f in sorted(findings, key=lambda f: f.fingerprint):
        entry: dict[str, Any] = {"rule": f.rule, "path": f.path, "key": f.key}
        if f.value is not None:
            entry["value"] = f.value
        entries.append(entry)
    path.write_text(json.dumps({"version": 1, "findings": entries}, indent=2) + "\n")


def apply_baseline(findings: list[Finding], baseline: list[dict[str, Any]]) -> BaselineResult:
    allowed = {(e["rule"], e["path"], e["key"]): e for e in baseline}
    new, known, matched = [], [], set()
    for f in findings:
        entry = allowed.get(f.fingerprint)
        if entry is None:
            new.append(f)
            continue
        matched.add(f.fingerprint)
        limit = entry.get("value")
        if limit is not None and f.value is not None and f.value > limit:
            new.append(Finding(f.rule, f.path, f.line, f"{f.message} (baseline allows {limit})", f.key, f.value))
        else:
            known.append(f)
    stale = [e for key, e in allowed.items() if key not in matched]
    return BaselineResult(new, known, stale)
