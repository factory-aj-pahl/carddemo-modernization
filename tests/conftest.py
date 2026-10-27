import os
import shutil
from pathlib import Path

import pytest

from tools.carddemo.source import Repository

REQUIRE_COBC = os.environ.get("CARDDEMO_REQUIRE_COBC") == "1"


def cobol(*lines: str) -> str:
    """Build fixed-format source: '*' lines become comments, others start in column 8."""
    out = []
    for line in lines:
        if line.startswith("*"):
            out.append("      " + line)
        else:
            out.append("       " + line)
    return "\n".join(out) + "\n"


@pytest.fixture
def make_repo(tmp_path: Path):
    def _make(files: dict[str, str]) -> Repository:
        for rel, text in files.items():
            path = tmp_path / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        return Repository(tmp_path)

    return _make


def pytest_collection_modifyitems(config, items):
    if shutil.which(os.environ.get("COBC", "cobc")) or REQUIRE_COBC:
        return
    skip = pytest.mark.skip(reason="GnuCOBOL (cobc) is not installed; set CARDDEMO_REQUIRE_COBC=1 to fail instead")
    for item in items:
        if "cobc" in item.keywords:
            item.add_marker(skip)
