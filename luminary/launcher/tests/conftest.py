import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from luminary_app.paths import Paths  # noqa: E402


@pytest.fixture
def paths(tmp_path):
    p = Paths(tmp_path / "launcher")
    p.base.mkdir()
    p.ensure()
    return p


FIXTURES = Path(__file__).resolve().parent / "fixtures"
