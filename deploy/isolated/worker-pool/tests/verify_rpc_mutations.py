#!/usr/bin/env python3
"""Strict legacy RPC mutation gate; scratch only, no deployment/model calls.

Eight original logical mutations are retained. create-gate is explicitly THREE
unique sites and cwd TWO; neither is advertised as single-site. Their combined
text must equal the original unrestricted replacement (no lost effects).
This module also supplies the evidence engine for the five preflight cases.
"""
import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
CASES = [
    ('ack', "return {'status':'accepted'}", "return {'status':'complete'}"),
    ('event-envelope', "'method':'event', 'params':", "'method':'message.complete', 'params':"),
    ('single-submit', "if self.submitted: raise Denied('submission already consumed')", 'if False: pass'),
    ('create-gate', '        require_tools(agent.tools)', '        pass'),
    ('strict-result', "result.get('completed') is not True", 'False'),
    ('bounded-lifetime', 'not 1 <= int(value) <= MAX_LIFETIME', 'int(value) < 1'),
    ('cwd', "params == {'cwd':'/workspace'}", "set(params) == {'cwd'}"),
    ('supervisor-sigterm', 'signal.signal(signal.SIGTERM, request_stop)',
     'signal.signal(signal.SIGTERM, signal.SIG_DFL)'),
]
RPC_INVENTORY = ('ack', 'event-envelope', 'single-submit', 'create-gate',
                 'strict-result', 'bounded-lifetime', 'cwd', 'supervisor-sigterm')
MULTI_SITES = {
    'create-gate': [
        '            agent = self.factory()\n            require_tools(agent.tools)',
        "        agent = getattr(importlib.import_module(module), name)()\n        require_tools(agent.tools)",
        "        text = json.loads(line)['text']\n        require_tools(agent.tools)",
    ],
    'cwd': [
        "        if method == 'session.create' and (not params or params == {'cwd':'/workspace'}) and self.agent is None:",
        "                                    not (not params or params == {'cwd':'/workspace'})):",
    ],
}


def oracle(test, line, message, locals_expected=None, subtest=None, function=None):
    module, cls, method = test.split('.')
    return dict(test=test, file=module + '.py', function=function or method,
                line=line, message=message, locals=locals_expected or {}, subtest=subtest)


RPC_ORACLES = {
    'ack': oracle('test_worker_rpc.SubprocessTests.test_java_subset_create_ack_and_terminal_event',
                  286, r".*complete.*accepted.*"),
    'event-envelope': oracle('test_worker_rpc.SubprocessTests.test_java_subset_create_ack_and_terminal_event',
                             287, r".*message\.complete.*event.*"),
    'single-submit': oracle('test_worker_rpc.SubprocessTests.test_single_submission_retries_are_errors_not_cached_success',
                           173, r"'error' not found in .*", {'rid': 'same'}),
    'create-gate': oracle('test_worker_rpc.SubprocessTests.test_initialization_gate_failure_never_admits_prompt',
                         63, r"'error' not found in .*", {'mode': 'badtools'}, {'mode': 'badtools'}),
    'strict-result': oracle('test_worker_rpc.AdmissionTests.test_malformed_result_flags_never_promote_success',
                           304, r".*complete.*error.*", {'value': False}),
    'bounded-lifetime': oracle('test_worker_rpc.AdmissionTests.test_trusted_lifetime_is_bounded_and_finite',
                              320, r'Denied not raised', {'value': '301'}, {'value': '301'}),
    'cwd': oracle('test_worker_rpc.SubprocessTests.test_closed_fields_unknown_method_and_oversize_prompt',
                  211, r"'error' not found in .*", {'params': {'cwd': '/state'}}),
    'supervisor-sigterm': oracle('test_worker_signals.SignalTests.test_sigterm_during_running_turn_reaps_ignoring_group',
                                67, r'True is not false : owned processes remain: .*',
                                {'phase': 'run', 'action': 'sigterm'}, function='exercise'),
}

# Existing tests and assertions execute unedited. Capture real exception types,
# frames and selected loop locals (not just FAIL text). Failfast stops only after
# the first failure; every GREEN baseline executes the whole original method.
DRIVER = r'''
import importlib, json, pathlib, sys, traceback, unittest
pool, test_name, evidence = sys.argv[1:]
sys.path[:0] = [pool, str(pathlib.Path(pool) / 'tests')]
module, cls, method = test_name.split('.')
suite = unittest.defaultTestLoader.loadTestsFromName(cls + '.' + method, importlib.import_module(module))
local_keys = ('sid', 'params', 'mode', 'role', 'bad', 'labels', 'value', 'rid', 'phase', 'action')
def safe(value):
    try: json.dumps(value); return value
    except (TypeError, ValueError): return repr(value)
class Evidence(unittest.TextTestResult):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.started, self.stopped, self.details = [], [], []
    def startTest(self, test):
        self.started.append(test.id()); super().startTest(test)
    def stopTest(self, test):
        self.stopped.append(test.id()); super().stopTest(test)
    def capture(self, test, err, kind):
        typ, value, tb = err
        frames = []
        while tb:
            f = tb.tb_frame
            if pathlib.Path(f.f_code.co_filename).name.startswith('test_'):
                frames.append(dict(file=pathlib.Path(f.f_code.co_filename).name,
                    function=f.f_code.co_name, line=tb.tb_lineno,
                    locals={k:safe(f.f_locals[k]) for k in local_keys if k in f.f_locals}))
            tb = tb.tb_next
        parent = getattr(test, 'test_case', test)
        self.details.append(dict(kind=kind, test=parent.id(),
            subtest={k:safe(v) for k,v in test.params.items()} if hasattr(test, 'params') else None,
            exception=typ.__module__ + '.' + typ.__qualname__, message=str(value), frames=frames,
            trace=''.join(traceback.format_exception(*err))))
    def addFailure(self, test, err):
        self.capture(test, err, 'failure'); super().addFailure(test, err)
    def addError(self, test, err):
        self.capture(test, err, 'error'); super().addError(test, err)
    def addSubTest(self, test, subtest, err):
        if err is not None:
            self.capture(subtest, err, 'failure' if issubclass(err[0], test.failureException) else 'error')
        super().addSubTest(test, subtest, err)
result = unittest.TextTestRunner(verbosity=2, failfast=True, resultclass=Evidence).run(suite)
pathlib.Path(evidence).write_text(json.dumps(dict(run=result.testsRun, started=result.started,
    stopped=result.stopped, failures=len(result.failures), errors=len(result.errors),
    skipped=len(result.skipped), expected_failures=len(result.expectedFailures),
    unexpected_successes=len(result.unexpectedSuccesses), details=result.details), indent=2))
sys.exit(0 if result.wasSuccessful() else 1)
'''


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def hashes(root):
    return {str(p.relative_to(root)): sha(p.read_bytes()) for p in sorted(root.rglob('*'))
            if p.is_file() and '__pycache__' not in p.parts and p.suffix != '.pyc'}


def save(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def process_table():
    result = subprocess.run(['ps', '-axo', 'pid=,ppid=,pgid=,lstart=,stat='],
                            capture_output=True, text=True, timeout=3, check=True)
    table = {}
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) >= 9:
            table[int(parts[0])] = dict(parent=int(parts[1]), group=int(parts[2]),
                                       identity=' '.join(parts[3:8]), state=parts[8])
    return table


def track_owned(table, leader, owned):
    parents = {leader} | {pid for pid, info in owned.items()
                              if table.get(pid, {}).get('identity') == info['identity']}
    while True:
        added = {pid for pid, info in table.items() if info['parent'] in parents} - parents
        if not added:
            break
        parents |= added
    for pid in parents:
        if pid in table:
            owned.setdefault(pid, table[pid])


def cleanup_owned(owned):
    """Only recorded PID identities/groups; never a global kill. Read back."""
    table = process_table()
    live = {pid: info for pid, info in owned.items()
            if table.get(pid, {}).get('identity') == info['identity']}
    signals = []
    for pid, info in live.items():
        try:
            # Only group leaders known to belong to this invocation.
            if info['group'] == pid:
                os.killpg(pid, signal.SIGKILL)
                signals.append(['group', pid])
            else:
                os.kill(pid, signal.SIGKILL)
                signals.append(['pid', pid])
        except ProcessLookupError:
            pass
    until = time.monotonic() + 2
    remaining = live
    while remaining and time.monotonic() < until:
        time.sleep(.05)
        table = process_table()
        remaining = {pid: info for pid, info in live.items()
                     if table.get(pid, {}).get('identity') == info['identity']}
    return dict(before=live, signals=signals, remaining=remaining)


def run_test(pool, target, directory, timeout):
    directory.mkdir()
    evidence = directory / 'test.json'
    command = [sys.executable, '-B', '-c', DRIVER, str(pool), target, str(evidence)]
    record: dict[str, Any] = dict(command=command, returncode=None, timed_out=False, result=None)
    owned = {}
    start = time.monotonic()
    with (directory / 'stdout.log').open('w') as out, (directory / 'stderr.log').open('w') as err:
        try:
            proc = subprocess.Popen(command, cwd=pool, stdout=out, stderr=err,
                start_new_session=True, env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1',
                    TMPDIR=str(Path.home() / '.hermes/cache/scratch')))
            record['pid'] = proc.pid
            while proc.poll() is None:
                track_owned(process_table(), proc.pid, owned)
                if time.monotonic() - start >= timeout:
                    record['timed_out'] = True
                    record['timeout_cleanup'] = cleanup_owned(owned)
                    # poll/wait reaps our own leader; cannot count a watchdog RED.
                    proc.wait(timeout=3)
                    break
                time.sleep(.05)
            record['returncode'] = proc.wait(timeout=3)
        except (OSError, subprocess.SubprocessError) as exc:
            record['launch_or_watchdog_error'] = repr(exc)
            if owned:
                record['error_cleanup'] = cleanup_owned(owned)
        finally:
            if owned:
                # Original tests retain their own finally/rescue. Any additional
                # rescue here is evidence of INVALID, not a successful gate.
                record['post_test_cleanup'] = cleanup_owned(owned)
    record['owned_processes'] = owned
    record['seconds'] = time.monotonic() - start
    record['logs'] = {k: str(directory / (k + '.log')) for k in ('stdout', 'stderr')}
    if evidence.exists():
        try:
            record['result'] = json.loads(evidence.read_text())
        except ValueError as exc:
            record['parse_error'] = str(exc)
    save(directory / 'process.json', record)
    return record


def valid(run, target):
    if not isinstance(run, dict):
        return False
    data = run.get('result')
    return (not run.get('timed_out') and not run.get('launch_or_watchdog_error')
            and not run.get('post_test_cleanup', {}).get('before')
            and run.get('returncode') in (0, 1) and isinstance(data, dict)
            and data.get('run') == 1 and data.get('started') == [target]
            and data.get('stopped') == [target]
            and all(data.get(k) == 0 for k in ('errors', 'skipped', 'expected_failures', 'unexpected_successes'))
            and isinstance(data.get('details'), list)
            and data.get('failures') == len(data['details']))


def green(run, target):
    return valid(run, target) and run['returncode'] == 0 and run['result']['failures'] == 0


def classify(baseline, mutant, expected):
    target = expected.get('test')
    if not green(baseline, target):
        return 'INVALID', 'baseline not exactly GREEN'
    if not valid(mutant, target):
        return 'INVALID', 'timeout/startup/error/skip/count/cleanup or incomplete evidence'
    data = mutant['result']
    if green(mutant, target):
        return 'SURVIVED', 'exact original test executed GREEN'
    if mutant['returncode'] != 1 or len(data['details']) != 1:
        return 'INVALID', 'unexpected exit or failure count'
    failure = data['details'][0]
    frames = failure['frames']
    frame = frames[-1] if frames else {}
    matched = (failure['kind'] == 'failure' and failure['test'] == target
        and failure['exception'] == 'builtins.AssertionError'
        and failure['subtest'] == expected['subtest']
        and re.fullmatch(expected['message'], failure['message'], re.S) is not None
        and all(frame.get(k) == expected[k] for k in ('file', 'function', 'line'))
        and all(k in frame.get('locals', {}) and type(frame['locals'][k]) is type(v)
                and frame['locals'][k] == v for k, v in expected['locals'].items()))
    if 'params_scenario' in expected:
        local = frame.get('locals', {})
        wanted = dict(expected['params_scenario'], session_id=local.get('sid'))
        matched = matched and isinstance(local.get('sid'), str) and local.get('params') == wanted
    return ('DETECTED', 'exact AssertionError/frame/subscenario with exit1') if matched else (
        'INVALID', 'unrelated failure or wrong assertion/subscenario')


def mutate(original, case, directory):
    before, after = case['before'], case['after']
    anchors = case['anchors']
    if original.count(before) != len(anchors) or before == after:
        raise ValueError('legacy effect cardinality changed')
    text = original
    sites = []
    for number, anchor in enumerate(anchors, 1):
        if original.count(anchor) != 1 or text.count(anchor) != 1 or anchor.count(before) != 1:
            raise ValueError('each declared site must have exactly one unique anchor/effect')
        replacement = anchor.replace(before, after, 1)
        updated = text.replace(anchor, replacement, 1)
        diff = ''.join(difflib.unified_diff(text.splitlines(True), updated.splitlines(True),
                                          fromfile=case['file'], tofile=case['file']))
        compile(updated, case['file'], 'exec')
        (directory / ('site-%d.diff' % number)).write_text(diff)
        sites.append(dict(site=number, anchor=anchor, replacement=replacement, anchor_count=1,
                          before_sha256=sha(text.encode()), after_sha256=sha(updated.encode()),
                          diff=diff, compiled=True))
        text = updated
    if text != original.replace(before, after):
        raise ValueError('joint mutation lost or added legacy effects')
    diff = ''.join(difflib.unified_diff(original.splitlines(True), text.splitlines(True),
                                      fromfile=case['file'], tofile=case['file']))
    (directory / 'mutation.diff').write_text(diff)
    compile(text, case['file'], 'exec')
    return text, sites, diff


def run_gate(root, cases, inventory, group, timeout):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, help='new directory below ~/.hermes/cache/scratch')
    args = parser.parse_args()
    scratch = Path.home() / '.hermes/cache/scratch'
    scratch.mkdir(parents=True, exist_ok=True)
    if args.output:
        out = args.output.expanduser().resolve()
        if scratch.resolve() not in out.parents:
            parser.error('output must be below scratch')
        out.mkdir(parents=True, exist_ok=False)
    else:
        out = Path(tempfile.mkdtemp(prefix='legacy-' + group + '-', dir=scratch))
    if tuple(c['name'] for c in cases) != tuple(inventory) or len(set(inventory)) != len(inventory):
        parser.error('declared legacy inventory missing, reordered, or duplicated')
    initial = hashes(root)
    manifest: dict[str, Any] = dict(schema='strict-legacy-mutations.v1', group=group, inventory=list(inventory),
        expected_count=len(inventory), source_hashes=initial, sequential=True,
        per_test_timeout=timeout, executable=sys.executable, python=sys.version,
        runner_sha256=sha(Path(sys.argv[0]).resolve().read_bytes()), records=[], exit_code=None)
    save(out / 'manifest.json', manifest)
    print('EVIDENCE ' + str(out), flush=True)
    baseline_pool = out / 'baseline'
    shutil.copytree(root, baseline_pool, ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
    snapshot_ok = hashes(baseline_pool) == initial
    for case in cases:
        directory = out / case['name']; directory.mkdir()
        row = dict(case, status='INVALID', mutation_mode='single-site' if len(case['anchors']) == 1 else 'multi-site',
                   site_count=len(case['anchors']), baseline=None, mutant=None)
        try:
            if not snapshot_ok or hashes(root) != initial or hashes(baseline_pool) != initial:
                raise ValueError('source or baseline snapshot drift')
            row['baseline'] = run_test(baseline_pool, case['oracle']['test'], directory / 'baseline', timeout)
            if not green(row['baseline'], case['oracle']['test']):
                row['reason'] = 'baseline not exactly GREEN'
            else:
                pool = directory / 'pool'
                shutil.copytree(baseline_pool, pool, ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
                original = (pool / case['file']).read_text()
                changed, row['sites'], row['diff'] = mutate(original, case, directory)
                row.update(before_sha256=sha(original.encode()), after_sha256=sha(changed.encode()), compiled=True)
                (pool / case['file']).write_text(changed)
                candidate_hashes = dict(initial, **{case['file']: row['after_sha256']})
                if hashes(pool) != candidate_hashes:
                    raise ValueError('candidate changed outside declared mutation')
                row['mutant'] = run_test(pool, case['oracle']['test'], directory / 'mutant', timeout)
                if hashes(pool) != candidate_hashes:
                    raise ValueError('candidate drift during test')
                row['status'], row['reason'] = classify(row['baseline'], row['mutant'], case['oracle'])
        except (ValueError, OSError, SyntaxError, subprocess.SubprocessError) as exc:
            row['reason'] = type(exc).__name__ + ': ' + str(exc)
        manifest['records'].append(row)
        save(directory / 'result.json', row)
        save(out / 'manifest.json', manifest)
        print(case['name'] + ': ' + row['status'] + ' ' + row.get('reason', ''), flush=True)
    manifest['counts'] = {key: sum(r['status'] == key for r in manifest['records'])
                          for key in ('DETECTED', 'SURVIVED', 'INVALID')}
    manifest['source_hashes_after'] = hashes(root)
    manifest['source_unchanged'] = manifest['source_hashes_after'] == initial
    passed = (manifest['source_unchanged'] and len(manifest['records']) == len(inventory)
              and manifest['counts']['DETECTED'] == len(inventory))
    manifest.update(exit_code=0 if passed else 1, state='PASS' if passed else 'FAIL')
    save(out / 'manifest.json', manifest)
    print(json.dumps({k: manifest[k] for k in ('state', 'counts', 'source_unchanged', 'exit_code')}), flush=True)
    return manifest['exit_code']


def rpc_cases():
    return [dict(name=name, file='worker.py', before=before, after=after,
                 anchors=MULTI_SITES.get(name, [before]), oracle=RPC_ORACLES[name])
            for name, before, after in CASES]


if __name__ == '__main__':
    raise SystemExit(run_gate(ROOT, rpc_cases(), RPC_INVENTORY, 'rpc', 25))
