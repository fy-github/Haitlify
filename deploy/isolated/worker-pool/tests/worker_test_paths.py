"""Shared fixture root, independent of the operator's home directory.

Keep configured roots short when tests create Unix-domain sockets. Temporary
children are exclusively created by tempfile; this shared parent is not removed.
"""
import os
from pathlib import Path
import tempfile


def scratch_root():
    root = Path(os.environ.get('WORKER_TEST_SCRATCH') or os.environ.get('TMPDIR')
                or tempfile.gettempdir()).expanduser().resolve()
    root.mkdir(parents=True, exist_ok=True)
    return root