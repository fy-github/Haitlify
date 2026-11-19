#!/usr/bin/env python3
"""Sequential C/U mutation acceptance, local fixtures only; never edit production.

Run from any directory. Evidence persists in --output or a fresh scratch directory.
Every case needs a GREEN exact-test baseline, one unique anchor, a compiling
single replacement and the named assertion RED. Other failures are INVALID.
No Docker, provider, model, full-suite or parallel stress run is performed.
"""
import argparse
import ast
from collections import Counter
import difflib
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from typing import Any
from worker_test_paths import scratch_root

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_COUNTS = {"C": 35, "U": 20}
# Trusted inventory from the original tests, never learned from mutant output.
# C8 loops over control(AF_UNIX), data(AF_INET); C6 over mcp, provider.
# Include the complete test + subTest identity, not the assertion text or trace.
_C8_METHOD = 'test_c8_connection_failure_and_close_failure_restore_capacity'
_C6_METHOD = 'test_c6_upstream_lock_revoke_rejects_then_fences_both_roles'
def selected_test_identity(method):
    # unittest's display includes the method inside parentheses on newer
    # Python, but only module.class on 3.9. Format trusted metadata through
    # the same stdlib, never by parsing/renaming received failure records.
    cls = type('BoundedEdgeTests', (unittest.TestCase,), {
        '__module__': 'test_bounded_edge', method: lambda self: None})
    return str(cls(method))


_C8_IDENTITIES = tuple(
    f'{selected_test_identity(_C8_METHOD)} (plane={plane!r})'
    for plane in (socket.AF_UNIX, socket.AF_INET))
_C6_IDENTITIES = tuple(
    f'{selected_test_identity(_C6_METHOD)} (role={role!r})'
    for role in ('mcp', 'provider'))
EXPECTED_MULTI_FAILURE_CASES = {
    'C8-diagnostic': (_C8_METHOD, 'C8 fixed local diagnostic', _C8_IDENTITIES),
    'C6-forwarding-unlocked': (_C6_METHOD, 'C6 forwarding lock', _C6_IDENTITIES),
    'C8-close-fallback': (_C8_METHOD, 'C8 failed close', _C8_IDENTITIES),
    'C8-active-accounting': (_C8_METHOD, 'C8 active accounting', _C8_IDENTITIES),
}

C_CASES = [('C8-diagnostic',
  "self.last_error = 'connection failed'  # Fixed local diagnostic; never peer content.",
  'pass  # diagnostic removed',
  'test_c8_connection_failure_and_close_failure_restore_capacity',
  'C8 fixed local diagnostic'),
 ('C8-isolation',
  "self.last_error = 'connection failed'  # Fixed local diagnostic; never peer content.",
  'raise',
  'test_c8_handler_exception_keeps_same_fixed_workers',
  'C8 per-connection isolation'),
 ('C8-task-accounting',
  'self.pending.task_done()\n\n    def service_actions',
  'pass\n\n    def service_actions',
  'test_c8_handler_exception_keeps_same_fixed_workers',
  'C8 task accounting'),
 ('C8-invariant',
  'if any(not worker.is_alive() for pool in pools for worker in pool.workers):',
  'if False:',
  'test_c8_pool_invariant_failure_closes_listener',
  'C8 pool invariant'),
 ('C8-peer-pool',
  'data.peer_pools = (control,)',
  'data.peer_pools = ()',
  'test_c8_failed_control_pool_closes_both_planes',
  'C8 failed control pool must close Data'),
 ('C2-reject-close',
  "request.close()\n                self.last_error = 'connection failed'",
  "pass\n                self.last_error = 'connection failed'",
  'test_c2_control_capacity_rejection_and_recovery',
  'C2 full queue'),
 ('C1-backlog',
  'request_queue_size = 32',
  'request_queue_size = 5',
  'test_c1_six_connections_before_accept_and_backlog',
  'C1'),
 ('C2-unbounded-queue',
  'self.pending = queue.Queue(maxsize=self.queue_size)',
  'self.pending = queue.Queue(maxsize=0)',
  'test_c2_control_capacity_rejection_and_recovery',
  'C2 full queue'),
 ('C2-extra-workers',
  'worker_count = 8',
  'worker_count = 16',
  'test_c2_control_capacity_rejection_and_recovery',
  'C2 full queue'),
 ('C3-inactivity',
  'connection.settimeout(remaining(deadline))\n        part =',
  'connection.settimeout(1)\n        part =',
  'test_c3_absolute_frame_deadline_slow_drip',
  'C3 slow drip'),
 ('C3-queue-reset',
  'min(deadline, self.server.context.accepted_at + 1)',
  'min(deadline, time.monotonic() + 1)',
  'test_c3_queue_wait_consumes_frame_budget',
  'C3 queued frame'),
 ('C3-dispatch-reset',
  'self.pending.put_nowait((request, self.context.accepted_at))',
  'self.pending.put_nowait((request, time.monotonic()))',
  'test_c3_accept_timestamp_precedes_dispatch_delay',
  'C3 budget begins'),
 ('C3-closed-peer',
  'except OSError: address = None  # Closed peer may still have a complete buffered mutation.',
  'except OSError: raise',
  'test_c3_client_close_before_accept_is_not_rollback',
  'C3 pending accepted'),
 ('C4-unbounded-lock',
  'lease.lock.acquire(timeout=min(1, remaining(deadline)))',
  'lease.lock.acquire()',
  'test_c4_lock_contention_returns_without_mutating',
  'C4 bounded lock'),
 ('C4-late-commit',
  'remaining(deadline)  # Acquiring a lock is not permission for a late commit.',
  'pass  # removed post-acquire deadline',
  'test_c4_lock_acquired_after_deadline_never_commits',
  'C4 post-acquire'),
 ('C5-ack-budget',
  'min(deadline, time.monotonic() + .25)',
  'deadline',
  'test_c5_partial_ack_deadline_preserves_commit_and_releases_lock',
  'C5 absolute ACK'),
 ('C6-forwarding-unlocked',
  'with lease.lock:\n                    config = lease.active()',
  'if True:\n                    config = lease.active()',
  'test_c6_upstream_lock_revoke_rejects_then_fences_both_roles',
  'C6 forwarding lock'),
 ('C7-extra-workers',
  'worker_count = 4',
  'worker_count = 8',
  'test_c7_data_capacity_control_pool_independent',
  'C7 data queue'),
 ('C8-close-fallback',
  'try: request.close()\n                    except Exception: pass',
  'try: pass\n                    except Exception: pass',
  'test_c8_connection_failure_and_close_failure_restore_capacity',
  'C8 failed close'),
 ('C8-active-accounting',
  'with self.accounting: self.active -= 1',
  'with self.accounting: pass',
  'test_c8_connection_failure_and_close_failure_restore_capacity',
  'C8 active accounting'),
 ('C8-recursion',
  'except (ValueError, TypeError, AttributeError, OSError, RecursionError):',
  'except (ValueError, TypeError, AttributeError, OSError):',
  'test_c8_recursion_error_returns_safe_rejection',
  'C8 recursion'),
 ('C9-queue-age',
  'if time.monotonic() >= accepted_at + 3: continue',
  'if False: continue',
  'test_c9_data_queue_age_checked_only_when_worker_available',
  'C9 expired queue'),
 ('C9-keepalive',
  "protocol_version = 'HTTP/1.0'",
  "protocol_version = 'HTTP/1.1'",
  'test_c9_pipeline_keepalive_still_single_http10_request',
  'C9 single request'),
('F1-queue-fallback',
  'try: request.close()\n                except Exception: close_failed = True',
  'try: pass\n                except Exception: close_failed = True',
  'test_c8_shutdown_queued_close_failure_drains_every_owned_socket',
  'F1 queued close failure must not leak FD or task'),
 ('F1-queue-isolation',
  'except Exception: close_failed = True\n            finally: self.pending.task_done()',
  'except Exception: raise\n            finally: self.pending.task_done()',
  'test_c8_shutdown_uncloseable_queue_item_reports_failure_after_drain',
  'F1 uncloseable item must not strand other queued resources'),
 ('F1-queue-failure-report',
  'except Exception: close_failed = True\n            finally: self.pending.task_done()',
  'except Exception: pass\n            finally: self.pending.task_done()',
  'test_c8_shutdown_uncloseable_queue_item_reports_failure_after_drain',
  'F1 uncloseable resource cannot report safe completion'),
 ('F1-queue-diagnostic',
  "self.last_error = 'connection failed'\n                try: request.close()",
  'pass\n                try: request.close()',
  'test_c8_shutdown_queued_close_failure_drains_every_owned_socket',
  'F1 fixed close diagnostic'),
 ('F1-listener-fallback',
  'try: self.socket.close()\n            except Exception: close_failed = True',
  'try: pass\n            except Exception: close_failed = True',
  'test_c8_shutdown_listener_close_failure_still_drains_queue',
  'F1 listener close failure must not bypass queue cleanup'),
 ('F1-listener-isolation',
  'except Exception: close_failed = True\n        while True:',
  'except Exception: raise\n        while True:',
  'test_c8_shutdown_uncloseable_listener_reports_failure_after_drain',
  'F1 listener fallback failure must not strand remaining resources'),
 ('F1-listener-failure-report',
  'except Exception: close_failed = True\n        while True:',
  'except Exception: pass\n        while True:',
  'test_c8_shutdown_uncloseable_listener_reports_failure_after_drain',
  'F1 listener close failure cannot report safe completion'),
 ('F1-failure-report',
  "if close_failed: raise Rejected('connection cleanup failed') from None",
  'if close_failed: pass',
  'test_c8_shutdown_uncloseable_queue_item_reports_failure_after_drain',
  'F1 uncloseable resource cannot report safe completion'),
 ('F1-join-preserved',
  'for worker in self.workers: worker.join(timeout=.1)',
  'for worker in self.workers: pass',
  'test_c8_shutdown_queued_close_failure_drains_every_owned_socket',
  'F1 each original worker receives a bounded join'),
 ('F1-join-bounded',
  'for worker in self.workers: worker.join(timeout=.1)',
  'for worker in self.workers: worker.join(timeout=.4)',
  'test_c8_shutdown_queued_close_failure_drains_every_owned_socket',
  'F1 shutdown joins remain bounded'),
 ('F1-listener-exception-level',
  'try: super().server_close()\n        except Exception:',
  'try: super().server_close()\n        except OSError:',
  'test_c8_shutdown_listener_close_failure_still_drains_queue',
  'F1 listener close failure must not bypass queue cleanup'),
 ('F1-queue-exception-level',
  'try: self.close_request(request)\n'
  '            except Exception:\n'
  '                self.last_error',
  'try: self.close_request(request)\n            except OSError:\n                self.last_error',
  'test_c8_shutdown_uncloseable_queue_item_reports_failure_after_drain',
  'F1 uncloseable item must not strand other queued resources')]

U_CASES = [('u1-not-sent',
  "{'control_state':'NOT_SENT', 'quarantine_required':True}",
  "{'control_state':'UNKNOWN', 'quarantine_required':True}",
  'test_u1_before_send_not_sent_preserves_healthy_lease',
  'U_CONTROL_STATE'),
 ('u2-unknown',
  "if attempt is not None: attempt['control_state'] = 'UNKNOWN'",
  "if attempt is not None: attempt['control_state'] = 'NOT_SENT'",
  'test_u2_real_commit_lost_ack_and_bad_readback_no_replay',
  'U_CONTROL_STATE'),
 ('u3-mark-after-send',
  "            if attempt is not None: attempt['control_state'] = 'UNKNOWN'\n"
  '            sent = sock.send(frame[offset:])',
  '            sent = sock.send(frame[offset:])\n'
  "            if attempt is not None: attempt['control_state'] = 'UNKNOWN'",
  'test_u3_first_and_partial_send_failure_are_unknown',
  'U3_UNKNOWN_BEFORE_FIRST_SEND'),
 ('u3-input-budget',
  'deadline = min(deadline, time.monotonic() + 1.0)',
  'deadline = min(deadline, time.monotonic() + 5.0)',
  'test_u3_stdin_requires_eof_and_absolute_one_second',
  'U3_STDIN_ABSOLUTE_EOF'),
 ('u3-exchange-budget',
  'time.monotonic() + 4.0)',
  'time.monotonic() + 8.0)',
  'test_u3_ack_and_readback_slow_drip_absolute_exchange',
  'U3_EXCHANGE_ABSOLUTE'),
 ('u3-output-budget',
  '\n    deadline = min(deadline, time.monotonic() + .25)',
  '\n    deadline = min(deadline, time.monotonic() + 5.0)',
  'test_u3_stdout_backpressure_is_bounded_nonzero',
  'U3_STDOUT_BOUNDED'),
 ('u3-shared-budget',
  "status = control(args.socket, {'op':'status'}, attempt, deadline)",
  "status = control(args.socket, {'op':'status'}, attempt, time.monotonic()+CLI_SECONDS)",
  'test_u3_shared_cli_deadline_not_reset_between_exchanges',
  'U3_SHARED_CLI_BOUND'),
 ('u3-recursion',
  '(ValueError, OSError, TypeError, KeyError, RecursionError)',
  '(ValueError, OSError, TypeError, KeyError)',
  'test_u3_recursion_error_input_fails_safely',
  'U_FAILURE_SAFE_ENVELOPE'),
 ('u3-send-budget',
  'send_deadline = min(deadline, time.monotonic() + .25)',
  'send_deadline = min(deadline, time.monotonic() + .5)',
  'test_u3_send_budget_is_absolute_and_output_requires_full_write',
  'U3_SEND_ABSOLUTE_CALLS'),
 ('u3-partial-output',
  '            offset += sent\n        remaining(deadline)\n        return True',
  '            offset += sent\n            return True\n        remaining(deadline)\n        return True',
  'test_u3_send_budget_is_absolute_and_output_requires_full_write',
  'U3_OUTPUT_COMPLETE_REQUIRED'),
 ('u4-one-observation',
  "second = control(args.socket, {'op':'status'}, attempt, deadline)",
  'second = first',
  'test_u4_reconcile_exact_two_observations_never_admits',
  'U4_EXACT_TWO_STATUS'),
 ('u4-admission',
  'admission_allowed=False, reuse_allowed=False, readback=second',
  'admission_allowed=True, reuse_allowed=False, readback=second',
  'test_u4_reconcile_exact_two_observations_never_admits',
  'U4_NO_ADMISSION'),
 ('u4-reuse',
  'admission_allowed=False, reuse_allowed=False, readback=second',
  'admission_allowed=False, reuse_allowed=True, readback=second',
  'test_u4_reconcile_exact_two_observations_never_admits',
  'U4_NO_ADMISSION'),
 ('u4-expired-revoke',
  "expected = validate_public(intent['expected'], active=False)",
  "expected = validate_public(intent['expected'], active=True)",
  'test_u4_reconcile_exact_two_observations_never_admits',
  'U4_RECONCILE_SUCCESS'),
 ('u4-skip-first',
  '            verify_receipt(first, expected, args.role, revoked)',
  '            pass',
  'test_u4_reconcile_rejects_each_untrusted_observation',
  'U_FAILURE_EXIT'),
 ('u4-skip-second',
  '            verify_receipt(second, expected, args.role, revoked)',
  '            pass',
  'test_u4_reconcile_rejects_each_untrusted_observation',
  'U_FAILURE_EXIT'),
 ('u4-learn-peer-identity',
  '            verify_receipt(first, expected, args.role, revoked)',
  '            expected = {k:first[k] for k in PUBLIC_FIELDS}\n'
  '            verify_receipt(first, expected, args.role, revoked)',
  'test_u4_matching_wrong_peer_identity_never_becomes_expected',
  'U_FAILURE_EXIT'),
 ('u5-timeout-not-sent',
  "result = dict(verdict='FAIL', reason='invalid configuration, receipt, or ownership', **attempt)",
  "attempt['control_state'] = 'NOT_SENT'\n"
  "        result = dict(verdict='FAIL', reason='invalid configuration, receipt, or ownership', **attempt)",
  'test_u5_pending_accept_can_commit_after_unknown',
  'U_CONTROL_STATE'),
 ('u2-auto-replay',
  '            receipt = control(args.socket, payload, attempt, deadline)',
  '            try: receipt = control(args.socket, payload, attempt, deadline)\n'
  '            except (OSError, ValueError): receipt = control(args.socket, payload, attempt, deadline)',
  'test_u2_real_commit_lost_ack_and_bad_readback_no_replay',
  'U2_ONE_MUTATION'),
 ('u3-input-max',
  "if len(raw) > MAX: raise Rejected('control input too large')",
  'if len(raw) > MAX: pass',
  'test_u3_oversize_input_fails_safely',
  'U3_PARSE_REASON')]


# Load the existing tests without editing their files. Their legacy SCRATCH
# globals are fixture locations only; rebind them in memory for portability.
DRIVER = r"""
import contextlib, importlib, io, json, pathlib, sys, unittest
pool, scratch, module_name, class_name, method, failfast = sys.argv[1:]
sys.path[:0] = [pool, str(pathlib.Path(pool) / 'tests')]
module = importlib.import_module(module_name)
rebound = []
for name, item in list(sys.modules.items()):
    if name.startswith('test_') and hasattr(item, 'SCRATCH'):
        item.SCRATCH = scratch
        rebound.append(name)
suite = unittest.defaultTestLoader.loadTestsFromName(class_name + '.' + method, module)
result = unittest.TestResult()
result.failfast = failfast == '1'
output = io.StringIO()
with contextlib.redirect_stdout(output):
    suite.run(result)
def entries(items):
    return [dict(test=str(test), trace=trace) for test, trace in items]
print(json.dumps(dict(run=result.testsRun, failures=entries(result.failures),
    errors=entries(result.errors), skipped=[(str(t), why) for t, why in result.skipped],
    expected_failures=entries(result.expectedFailures),
    unexpected_successes=[str(t) for t in result.unexpectedSuccesses],
    test_stdout=output.getvalue(), fixture_scratch=scratch, rebound_modules=rebound)))
sys.exit(0 if result.wasSuccessful() else 1)
"""


def sha256(raw):
    return hashlib.sha256(raw).hexdigest()


def source_hashes(root):
    paths = list(root.rglob('*.py')) + [root / 'runtime-manifest.json']
    return {str(path.relative_to(root)): sha256(path.read_bytes())
            for path in sorted(paths) if '__pycache__' not in path.parts}


def save_json(path, value):
    temporary = path.with_suffix('.pending')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)


def run_test(pool, scratch, case, timeout, log):
    group, _, _, _, _, method, _ = case
    module, cls = ('test_bounded_edge', 'BoundedEdgeTests') if group == 'C' else (
        'test_control_uncertainty', 'UncertaintyTests')
    command = [sys.executable, '-B', '-c', DRIVER, str(pool), str(scratch),
               module, cls, method, '1' if group == 'U' else '0']
    record: dict[str, Any] = dict(command=command, exit_code=None, timed_out=False, result=None)
    start = time.monotonic()
    # A timeout is INVALID, never a detected mutation. Kill only this fresh
    # session's process group, before reaping its owned leader, then collect logs.
    with log.open('w') as stream:
        try:
            process = subprocess.Popen(command, cwd=pool, stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, text=True, start_new_session=True,
                env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1', TMPDIR=str(scratch),
                         WORKER_TEST_SCRATCH=str(scratch)))
            try:
                out, err = process.communicate(timeout=timeout)
            except subprocess.TimeoutExpired:
                record['timed_out'] = True
                os.killpg(process.pid, signal.SIGKILL)
                out, err = process.communicate()
            record.update(exit_code=process.returncode, stdout=out, stderr=err)
            stream.write('STDOUT\n' + out + '\nSTDERR\n' + err)
            try:
                record['result'] = json.loads(out)
            except ValueError:
                record['parse_error'] = 'test result was not JSON'
        except OSError as error:
            record['launch_error'] = repr(error)
            stream.write(repr(error))
    record.update(seconds=time.monotonic() - start, log=str(log))
    return record


def valid_result(run):
    if not isinstance(run, dict):
        return False
    data = run.get('result')
    return (run.get('timed_out') is False
            and run.get('launch_error') is None and 'parse_error' not in run
            and type(run.get('exit_code')) is int and run.get('exit_code') in (0, 1)
            and isinstance(data, dict)
            and type(data.get('run')) is int and data.get('run') == 1
            and all(isinstance(data.get(key), list) for key in (
                'failures', 'errors', 'skipped', 'expected_failures', 'unexpected_successes'))
            and not any(data[key] for key in (
                'errors', 'skipped', 'expected_failures', 'unexpected_successes'))
            and all(isinstance(item, dict)
                    and all(isinstance(item.get(key), str) and item[key]
                            for key in ('test', 'trace'))
                    for item in data['failures']))


def green(run):
    return valid_result(run) and run['exit_code'] == 0 and not run['result']['failures']


def classify(baseline, mutant, method, assertion, expected_failure_count=1):
    if (type(expected_failure_count) is not int or expected_failure_count <= 0
            or not isinstance(method, str) or not method
            or not isinstance(assertion, str) or not assertion):
        return 'INVALID', 'malformed classification expectation', []
    if not green(baseline):
        return 'INVALID', 'baseline not green', []
    if not valid_result(mutant):
        return 'INVALID', 'timeout/startup/error/skip/incomplete test result', []
    failures = mutant['result']['failures']
    expected_identities = next((identities for selected, marker, identities
        in EXPECTED_MULTI_FAILURE_CASES.values()
        if (selected, marker) == (method, assertion)), None)
    if ((expected_identities is None and expected_failure_count != 1)
            or (expected_identities is not None
                and expected_failure_count != len(expected_identities))):
        return 'INVALID', 'failure count does not match trusted inventory', []
    identities = [item['test'] for item in failures]
    if len(set(identities)) != len(identities):
        return 'INVALID', 'duplicate test/subtest identity', []
    if mutant['exit_code'] == 0 and not failures:
        return 'SURVIVED', 'selected test remained green', []
    if (expected_identities is not None
            and Counter(identities) != Counter(expected_identities)):
        return 'INVALID', 'missing or unexpected test/subtest identity', []
    # Match the actual assertion diagnostic, not merely marker text in source
    # lines echoed by a traceback. Unrelated FAILs never count as DETECTED.
    hits = [item for item in failures
            if item['test'].startswith(method + ' (')
            and '\nAssertionError:' in item['trace']
            and assertion in item['trace'].rsplit('\nAssertionError:', 1)[1]]
    if (mutant['exit_code'] == 1 and hits
            and len(hits) == len(failures) == expected_failure_count):
        return 'DETECTED', 'specified assertion RED', hits
    return 'INVALID', 'unexpected failure or exit code; specified assertion not isolated', hits


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scratch', type=Path,
                        default=scratch_root(),
                        help='short writable fixture path (Unix socket path limits apply)')
    parser.add_argument('--output', type=Path,
                        help='new evidence directory under scratch; never overwritten')
    parser.add_argument('--timeout', type=float, default=60,
                        help='per selected-test timeout, not a full-suite timeout (default 60s)')
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error('--timeout must be positive')
    scratch = args.scratch.expanduser().resolve()
    if scratch == ROOT or ROOT in scratch.parents:
        parser.error('scratch must be outside the worker-pool source tree')
    scratch.mkdir(parents=True, exist_ok=True)
    if args.output:
        out = args.output.expanduser().resolve()
        if scratch not in out.parents:
            parser.error('--output must be a new directory below --scratch')
        out.mkdir(parents=True, exist_ok=False)
    else:
        out = Path(tempfile.mkdtemp(prefix='bcm-', dir=scratch))
    cases = [(group, name, filename, old, new, test, assertion)
             for group, filename, source in (('C', 'edge.py', C_CASES),
                                             ('U', 'provisioner.py', U_CASES))
             for name, old, new, test, assertion in source]
    actual = dict(Counter(case[0] for case in cases))
    if actual != EXPECTED_COUNTS or len({case[1] for case in cases}) != len(cases):
        parser.error('case inventory changed or contains duplicate identifiers')
    expected_failure_counts = {case[1]: 1 for case in cases}
    expected_failure_identities: dict[str, Any] = {case[1]: None for case in cases}
    for name, (method, assertion, identities) in EXPECTED_MULTI_FAILURE_CASES.items():
        selected = [case for case in cases if case[1] == name]
        if (len(selected) != 1 or selected[0][5:] != (method, assertion)
                or len(identities) != 2 or len(set(identities)) != 2):
            parser.error('multi-failure case differs from the trusted inventory')
        expected_failure_counts[name] = len(identities)
        expected_failure_identities[name] = identities
    if set(expected_failure_counts) != {case[1] for case in cases}:
        parser.error('each case must have one explicit expected failure count')
    started = time.monotonic()
    initial_hashes = source_hashes(ROOT)
    manifest: dict[str, Any] = dict(schema='bounded-control-mutations.v1', state='RUNNING',
        source_root=str(ROOT), output=str(out), scratch=str(scratch),
        executable=sys.executable, python=sys.version, argv=sys.argv,
        expected_counts=EXPECTED_COUNTS, case_count=len(cases),
        sequential=True, per_test_timeout=args.timeout,
        source_hashes=initial_hashes, records=[], counts={}, exit_code=None,
        fixture_policy='Only imported test modules SCRATCH globals rebound; no test/source edits')
    save_json(out / 'manifest.json', manifest)
    print('EVIDENCE ' + str(out), flush=True)
    baseline_pool = out / 'baseline'
    shutil.copytree(ROOT, baseline_pool, ignore=shutil.ignore_patterns('__pycache__'))
    if source_hashes(baseline_pool) != initial_hashes:
        manifest.update(state='INVALID', reason='source changed while copying', exit_code=1)
        save_json(out / 'manifest.json', manifest)
        return 1
    for case in cases:
        group, name, filename, old, new, method, assertion = case
        case_dir = out / name
        case_dir.mkdir()
        original = (baseline_pool / filename).read_text()
        row: dict[str, Any] = dict(group=group, name=name, file=filename, test=method,
                   assertion=assertion, anchor=old, replacement=new,
                   anchor_count=original.count(old), source_sha256=sha256(original.encode()),
                   expected_failure_count=expected_failure_counts[name],
                   expected_failure_identities=expected_failure_identities[name],
                   status='INVALID', baseline=None, mutant=None, assertion_hits=[])
        try:
            row['baseline'] = run_test(baseline_pool, scratch, case, args.timeout,
                                       case_dir / 'baseline.log')
            if not green(row['baseline']):
                row['reason'] = 'baseline not green'
            elif row['anchor_count'] != 1 or old == new:
                row['reason'] = 'anchor must be unique and replacement must differ'
            else:
                mutated = original.replace(old, new, 1)
                row['mutant_sha256'] = sha256(mutated.encode())
                row['diff'] = ''.join(difflib.unified_diff(
                    original.splitlines(True), mutated.splitlines(True),
                    fromfile=filename, tofile=filename))
                (case_dir / 'mutation.diff').write_text(row['diff'])
                ast.parse(mutated, filename=filename)
                compile(mutated, filename, 'exec')
                with tempfile.TemporaryDirectory(prefix='candidate-', dir=out) as temp:
                    pool = Path(temp) / 'pool'
                    shutil.copytree(baseline_pool, pool,
                                    ignore=shutil.ignore_patterns('__pycache__'))
                    (pool / filename).write_text(mutated)
                    candidate_hashes = source_hashes(pool)
                    expected = dict(initial_hashes, **{filename: row['mutant_sha256']})
                    if candidate_hashes != expected:
                        raise ValueError('candidate differs beyond the single replacement')
                    row['mutant'] = run_test(pool, scratch, case, args.timeout,
                                             case_dir / 'mutant.log')
                expected_failure_count = expected_failure_counts[name]
                row['status'], row['reason'], row['assertion_hits'] = classify(
                    row['baseline'], row['mutant'], method, assertion,
                    expected_failure_count=expected_failure_count)
        except (SyntaxError, OSError, ValueError) as error:
            row['reason'] = type(error).__name__ + ': ' + str(error)
        manifest['records'].append(row)
        manifest['counts'] = {key: sum(r['status'] == key for r in manifest['records'])
                              for key in ('DETECTED', 'SURVIVED', 'INVALID')}
        save_json(case_dir / 'result.json', row)
        save_json(out / 'manifest.json', manifest)
        line = name + ' ' + row['status'] + ' ' + row.get('reason', '')
        with (out / 'run.log').open('a') as stream:
            stream.write(line + '\n')
        print(line, flush=True)
    final_hashes = source_hashes(ROOT)
    manifest.update(source_hashes_after=final_hashes,
                    source_unchanged=final_hashes == initial_hashes,
                    seconds=time.monotonic() - started)
    manifest['group_counts'] = {
        group: {key: sum(r['group'] == group and r['status'] == key
                        for r in manifest['records'])
                for key in ('DETECTED', 'SURVIVED', 'INVALID')}
        for group in EXPECTED_COUNTS}
    passed = (manifest['source_unchanged']
              and len(manifest['records']) == sum(EXPECTED_COUNTS.values())
              and manifest['counts']['DETECTED'] == len(cases))
    manifest.update(state='PASS' if passed else 'FAIL', exit_code=0 if passed else 1)
    save_json(out / 'manifest.json', manifest)
    summary = {key: manifest[key] for key in (
        'state', 'case_count', 'counts', 'group_counts', 'source_unchanged', 'seconds', 'exit_code')}
    with (out / 'run.log').open('a') as stream:
        stream.write(json.dumps(summary) + '\n')
    print(json.dumps(summary), flush=True)
    return manifest['exit_code']


if __name__ == '__main__':
    raise SystemExit(main())
