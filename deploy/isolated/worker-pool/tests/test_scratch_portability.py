"""Portable fixture roots; no model, provider or container calls."""
import json
import os
import shutil
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


TESTS = Path(__file__).resolve().parent


class ScratchPortabilityTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which('node'), 'browser path smoke requires Node.js')
    def test_browser_probe_evidence_is_retained_and_only_own_scratch_removed(self):
        # Exercise the actual path setup/cleanup without missing archival UI inputs.
        probe = TESTS.parents[3] / 'apps/web/tests/controls-closeout.browser.mjs'
        script = '''import fs from 'node:fs/promises'; import path from 'node:path';
import os from 'node:os'; import assert from 'node:assert/strict';
const source = await fs.readFile(process.argv[1], 'utf8');
const init = source.slice(source.indexOf('let scratch'), source.indexOf('const baselineNames'));
const create = source.match(/ scratch = await fs.mkdtemp[^\\n]+/)[0];
const remove = source.match(/  if\\(scratch\\) await fs.rm[^\\n]+/)[0];
await new Function('fs','path','os','process','assert', 'return (async()=>{' + init + create + `
 await fs.writeFile(path.join(evidence,'keep'), 'retained');
 const owned = scratch;` + remove + `
 await assert.rejects(fs.stat(owned), {code:'ENOENT'});
 assert.equal(await fs.readFile(path.join(evidence,'keep'),'utf8'),'retained');
 console.log(evidence);
})()`)(fs,path,os,process,assert);
'''
        with tempfile.TemporaryDirectory() as tmp:
            env = dict(os.environ, TMPDIR=tmp, CONTROLS_EVIDENCE_DIR=str(Path(tmp) / 'explicit'))
            for explicit in (True, False):
                if not explicit:
                    env.pop('CONTROLS_EVIDENCE_DIR')
                proc = subprocess.run(['node', '--input-type=module', '-e', script, str(probe)],
                                      env=env, text=True, capture_output=True, check=True)
                evidence = Path(proc.stdout.strip())
                self.assertTrue(evidence.is_relative_to(Path(tmp)))
                self.assertEqual((evidence / 'keep').read_text(), 'retained')

    def test_helper_uses_tmpdir_then_platform_default_and_keeps_parent(self):
        with tempfile.TemporaryDirectory() as tmp:
            env = dict(os.environ, HOME=str(Path(tmp) / 'absent'), PYTHONPATH=str(TESTS))
            env.pop('WORKER_TEST_SCRATCH', None)
            root = Path(tmp) / 'new-tmp'
            env['TMPDIR'] = str(root)
            script = '''import json, tempfile
from worker_test_paths import scratch_root
root = scratch_root()
with tempfile.TemporaryDirectory(dir=root) as child:
    assert root in __import__('pathlib').Path(child).parents
print(json.dumps([str(root), root.is_dir()]))
'''
            proc = subprocess.run([sys.executable, '-B', '-c', script], env=env,
                                  text=True, capture_output=True, check=True)
            self.assertEqual(json.loads(proc.stdout), [str(root.resolve()), True])
            env.pop('TMPDIR')
            subprocess.run([sys.executable, '-B', '-c',
                'from worker_test_paths import scratch_root; import tempfile; '
                'from pathlib import Path; assert scratch_root() == Path(tempfile.gettempdir()).resolve()'],
                env=env, text=True, capture_output=True, check=True)

    def test_fixture_modules_share_explicit_new_scratch_without_home(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / 'new' / 'scratch'
            env = dict(os.environ, WORKER_TEST_SCRATCH=str(root), HOME=str(Path(tmp) / 'absent'))
            env['PYTHONPATH'] = os.pathsep.join((str(TESTS), str(TESTS.parent)))
            script = '''import importlib, json, pathlib
names = ['test_control_atomic', 'test_control_readback', 'test_bounded_edge',
         'test_control_uncertainty', 'test_edge', 'test_edge_framing', 'test_worker_entry']
print(json.dumps([str(pathlib.Path(importlib.import_module(n).SCRATCH).resolve()) for n in names]))
'''
            proc = subprocess.run([sys.executable, '-B', '-c', script], env=env,
                                  text=True, capture_output=True, check=True)
            self.assertEqual(json.loads(proc.stdout), [str(root.resolve())] * 7)
            self.assertTrue(root.is_dir(), 'new explicit scratch must be created')


if __name__ == '__main__':
    unittest.main()