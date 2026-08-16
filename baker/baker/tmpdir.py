"""Directory temporanee semplici e scrivibili.

Su alcune sandbox Windows `tempfile.TemporaryDirectory()` crea cartelle che
poi risultano non scrivibili. Qui serve solo uno scratch dir locale: lo si
crea esplicitamente e si rimuove a fine uso.
"""

from __future__ import annotations

import os
import shutil
import tempfile
import uuid
from pathlib import Path

TMPDIR_ENV = "BAKER_TMPDIR"


def create_temp_dir(prefix: str = "tmp") -> Path:
    base = Path(os.environ.get(TMPDIR_ENV) or tempfile.gettempdir())
    base.mkdir(parents=True, exist_ok=True)
    while True:
        path = base / f"{prefix}{uuid.uuid4().hex}"
        try:
            path.mkdir()
            return path
        except FileExistsError:  # pragma: no cover
            continue


class ScratchDirectory:
    """Compatibile quanto basta con `tempfile.TemporaryDirectory()`."""

    def __init__(self, prefix: str = "tmp"):
        self._path = create_temp_dir(prefix)
        self.name = str(self._path)

    def __enter__(self) -> str:
        return self.name

    def __exit__(self, exc_type, exc, tb) -> None:
        self.cleanup()

    def cleanup(self) -> None:
        shutil.rmtree(self._path, ignore_errors=True)
