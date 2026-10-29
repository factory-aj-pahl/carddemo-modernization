"""Scans the content of every tracked file (git ls-files) for the values in the secrets file and for
credential patterns. Prints file:line and the kind of finding, never the matched text; exit 1 on a finding.

Usage: python3 modernization/verification/scan_secrets.py [--secrets ~/.carddemo/secrets.env] [--repo DIR]
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

PATTERNS = {
    "private key block": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    "AWS access key id": re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"),
    "GitHub token": re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,}\b"),
    "Slack token": re.compile(r"\bxox[abpors]-[A-Za-z0-9-]{10,}"),
    "bearer token literal": re.compile(r"Bearer\s+[A-Za-z0-9._-]{30,}"),
    "password in a URL": re.compile(r"[a-z][a-z0-9+.-]*://[^\s:/@]+:[^\s@/]{3,}@", re.I),
    "password in a JDBC URL": re.compile(r"jdbc:[^\s\"']*;password=[^;\s\"'$]", re.I),
    # key = literal value; values that are placeholders or environment references are allowed below.
    "password assignment": re.compile(
        r"(?i)\b[\w.-]*(?:password|passwd|secret|api[_-]?key|token)[\w.-]*\s*[:=]\s*[\"']?([^\s\"'#,;)]{6,})"),
}
ALLOWED_VALUES = re.compile(
    r"^(?:\$.*|%\(.*|<[^>]*>|\*+|x+|changeme|PASSWORD|e2e-stub(?:-token)?|null|None|true|false|"
    r"process\.env\..*|os\.environ.*|ENV\[.*|System\.getenv.*|props\..*|params\..*|args\..*|self\..*|this\..*|"
    r"inputs\..*|[A-Za-z_][\w.]*\(.*|[A-Za-z_]\w*\[.*|[A-Z_][A-Z0-9_]+|[a-z][a-zA-Z0-9]*|[A-Z][a-z]+|[(!].*)$")


def secret_values(path: Path) -> dict[str, str]:
    out = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        v = v.strip().strip("'\"")
        if v:
            out[k.strip()] = v
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--secrets", type=Path, default=Path.home() / ".carddemo" / "secrets.env")
    ap.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    a = ap.parse_args(argv)
    secrets = secret_values(a.secrets)
    files = subprocess.run(["git", "-C", str(a.repo), "ls-files", "-z"], capture_output=True, check=True).stdout.split(b"\0")
    findings, scanned, scanned_bytes = [], 0, 0
    for name in (f.decode() for f in files if f):
        p = a.repo / name
        if not p.is_file():
            continue
        data = p.read_bytes()
        scanned += 1
        scanned_bytes += len(data)
        for key, value in secrets.items():
            if value.encode() in data:
                findings.append(f"{name}: value of {key} from the secrets file")
        if b"\0" in data[:4096]:
            continue  # binary: only the exact secret values are searched
        text = data.decode("utf-8", "replace")
        for n, line in enumerate(text.splitlines(), start=1):
            for kind, rx in PATTERNS.items():
                for m in rx.finditer(line):
                    if kind == "password assignment" and ALLOWED_VALUES.match(m.group(1)):
                        continue
                    findings.append(f"{name}:{n}: {kind}")
    for f in findings:
        print(f)
    print(f"secret scan: {scanned} tracked files ({scanned_bytes} bytes), {len(secrets)} secret values "
          f"({', '.join(sorted(secrets))}), {len(PATTERNS)} credential patterns: {len(findings)} findings")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
