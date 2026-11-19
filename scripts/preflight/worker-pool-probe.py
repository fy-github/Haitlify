#!/usr/bin/env python3
"""Read-only pinned-source probe. Archives tracked source, NEVER copies host config.
No Docker, credential access, or model calls. All child socket connects denied.
"""
import argparse
import io
import json
import os
import pathlib
import subprocess
import tarfile
import tempfile

PIN = 'd923a5eb487a17c0a68801bba9ef0f56c81040a8'
p = argparse.ArgumentParser()
p.add_argument('--source', required=True)
p.add_argument('--python', required=True)
p.add_argument('--scratch', default=str(pathlib.Path.home() / '.hermes/cache/scratch'))
a = p.parse_args()
source = pathlib.Path(a.source).resolve()
commit = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
if commit != PIN:
    raise SystemExit('BLOCKED: runtime commit differs')
root = pathlib.Path(__file__).resolve().parents[2] / 'deploy/isolated/worker-pool'
with tempfile.TemporaryDirectory(prefix='worker-pool-probe-', dir=a.scratch) as temp:
    work = pathlib.Path(temp)
    tree, home = work / 'source', work / 'home'
    tree.mkdir(); home.mkdir()
    hermes_home = home / '.hermes'
    hermes_home.mkdir()
    (hermes_home / 'config.yaml').write_text('tools:\n  tool_search:\n    enabled: "off"\n')
    archive = subprocess.check_output(['git', '-C', str(source), 'archive', PIN])
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        tar.extractall(tree, filter='data')
    # No importing code from the operator checkout, which contains .env autoloads.
    env = {'PATH': '/usr/bin:/bin', 'HOME': str(home), 'HERMES_HOME': str(home / '.hermes'),
           'TMPDIR': str(work), 'PYTHONPATH': str(tree), 'POOL_MODULE': str(root),
           'PYTHONDONTWRITEBYTECODE': '1', 'HERMES_IGNORE_RULES': '1'}
    result = subprocess.run([str(pathlib.Path(a.python).absolute()), '-s',
        str(root / 'tests/probe_pinned_runtime.py')], cwd=tree, env=env, timeout=120,
        text=True, capture_output=True)
    print(result.stdout)
    print(result.stderr)
    raise SystemExit(result.returncode)
