#!/usr/bin/env python3
"""Control-only scratch mutations; never modify shared sources under review."""
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
from worker_test_paths import scratch_root
SCRATCH = scratch_root()
LOGS = pathlib.Path(tempfile.mkdtemp(prefix='worker-control-fix2-', dir=SCRATCH))
CASES = [
    ('skip-readback', 'provisioner.py', '            verify_receipt(status, expected, args.role, revoked)', '            pass'),
    ('skip-ack', 'provisioner.py', '            verify_receipt(receipt, expected, args.role, revoked)', '            pass'),
    ('open-receipt-fields', 'provisioner.py', 'set(result) != PUBLIC_FIELDS | STATE_FIELDS', 'not (PUBLIC_FIELDS | STATE_FIELDS) <= set(result)'),
    ('open-role', 'provisioner.py', "if result['role'] != role:", 'if False:'),
    ('open-identity', 'provisioner.py', 'for k in PUBLIC_FIELDS):', 'for k in ()):') ,
    ('open-bool-types', 'provisioner.py', "if any(type(result[k]) is not bool for k in ('ok', 'ready', 'revoked', 'spent')):", 'if False:'),
    ('open-state', 'provisioner.py', "if result['ready'] is not (not revoked) or result['revoked'] is not revoked or result['spent'] is not revoked:", 'if False:'),
    ('open-expiry', 'provisioner.py', '    validate_public({k: result[k] for k in PUBLIC_FIELDS}, active=not revoked)', '    pass'),
    ('open-mode', 'provisioner.py', 'stat.S_IMODE(info.st_mode) not in {0o600, 0o700}', 'False'),
    ('open-revoke-fence', 'protocol.py', "for k in FIELDS - {'capability'}):", 'for k in ()):') ,
    ('open-edge-role', 'edge.py', "type(message.get('role')) is not str or message['role'] != args.role", 'False'),
    ('strip-expected', 'provisioner.py', "                message['expected'] = expected", '                pass'),
    ('expired-revoke-intent', 'provisioner.py', "active=op != 'revoke'", 'active=True'),
    ('expired-revoke-receipt', 'provisioner.py', 'active=not revoked', 'active=True'),
    ('expired-revoke-edge', 'protocol.py', 'validate_public(expected, active=False)', 'validate_public(expected)'),
    ('open-revoke-lock', 'protocol.py', '    def revoke(self, expected):\n        with self.lock:',
        '    def revoke(self, expected):\n        from contextlib import nullcontext\n        with nullcontext():'),
    ('status-secret-echo', 'protocol.py', "for k in FIELDS - {'capability'}}", 'for k in FIELDS}'),
    ('status-no-expiry', 'protocol.py', "self.config['expiry'] > time.time()", 'True'),
    ('status-not-revoked', 'protocol.py', 'revoked=self.spent, spent=self.spent', 'revoked=False, spent=self.spent'),
    ('status-not-spent', 'protocol.py', 'revoked=self.spent, spent=self.spent', 'revoked=self.spent, spent=False'),
]

import difflib
import hashlib
import re

TARGETS = {
    'skip-readback': ('test_control_readback', 'test_configure_ack_ready_but_readback_inactive_is_failure', 'contradictory readback must not report success'),
    'skip-ack': ('test_control_readback', 'test_configure_exact_identity_types_closed_schema_and_state', 'self.assertNotEqual(proc.returncode, 0)'),
    'open-receipt-fields': ('test_control_readback', 'test_configure_exact_identity_types_closed_schema_and_state', 'self.assertNotEqual(proc.returncode, 0)'),
    'open-role': ('test_control_readback', 'test_configure_exact_identity_types_closed_schema_and_state', 'self.assertNotEqual(proc.returncode, 0)'),
    'open-identity': ('test_control_readback', 'test_configure_exact_identity_types_closed_schema_and_state', 'self.assertNotEqual(proc.returncode, 0)'),
    'open-bool-types': ('test_control_readback', 'test_configure_exact_identity_types_closed_schema_and_state', 'control receipt boolean type mismatch'),
    'open-state': ('test_control_readback', 'test_configure_ack_ready_but_readback_inactive_is_failure', 'contradictory readback must not report success'),
    'open-expiry': ('test_control_readback', 'test_expired_readback_rejected_even_when_both_claim_ready', 'Rejected not raised'),
    'open-mode': ('test_control_readback', 'test_non_owner_only_socket_rejected_before_send', 'self.assertNotEqual(proc.returncode, 0)'),
    'open-revoke-fence': ('test_control_atomic', 'test_rejected_lease_identity_preserves_secret_and_all_internal_state', 'Rejected not raised'),
    'open-edge-role': ('test_control_atomic', 'test_configure_wrong_role_or_unscoped_wire_never_mutates', 'self.assertEqual(wire(path, {\'op\':\'status\'}), before)'),
    'strip-expected': ('test_control_atomic', 'test_expired_exact_revoke_succeeds_but_status_never_claims_ready', 'self.assertEqual(result.returncode, 0, result.stdout)'),
    'expired-revoke-intent': ('test_control_atomic', 'test_expired_exact_revoke_succeeds_but_status_never_claims_ready', 'self.assertEqual(result.returncode, 0, result.stdout)'),
    'expired-revoke-receipt': ('test_control_atomic', 'test_expired_exact_revoke_succeeds_but_status_never_claims_ready', 'self.assertEqual(result.returncode, 0, result.stdout)'),
    'expired-revoke-edge': ('test_control_atomic', 'test_expired_exact_revoke_succeeds_but_status_never_claims_ready', 'self.assertEqual(result.returncode, 0, result.stdout)'),
    'open-revoke-lock': ('test_control_atomic', 'test_lease_identity_comparison_and_mutation_share_lock', 'revoke must not inspect or mutate outside lease lock'),
    'status-secret-echo': ('test_control_readback', 'test_status_never_confuses_unconfigured_expired_and_revoked', 'self.assertEqual(lease.status(), dict(public, ready=False, spent=False, revoked=False))'),
    'status-no-expiry': ('test_control_readback', 'test_status_never_confuses_unconfigured_expired_and_revoked', 'self.assertEqual(lease.status(), dict(public, ready=False, spent=False, revoked=False))'),
    'status-not-revoked': ('test_control_readback', 'test_status_never_confuses_unconfigured_expired_and_revoked', 'self.assertEqual(lease.status(), dict(public, ready=False, spent=True, revoked=True))'),
    'status-not-spent': ('test_control_readback', 'test_status_never_confuses_unconfigured_expired_and_revoked', 'self.assertEqual(lease.status(), dict(public, ready=False, spent=True, revoked=True))'),
}


def run_tests(pool, module, name):
    cmd = [sys.executable, '-B', '-m', 'unittest', 'discover', '-s', str(pool/'tests'),
           '-p', module+'.py', '-v']
    if name: cmd += ['-k', name, '-f']
    return subprocess.run(cmd, capture_output=True, text=True, timeout=90)


results = []
# Baseline every original control test, not just selected mutation assertions.
for module in ('test_control_readback', 'test_control_atomic'):
    baseline = run_tests(ROOT, module, '')
    text = baseline.stdout + baseline.stderr
    (LOGS/('baseline-'+module+'.log')).write_text(text)
    if baseline.returncode or not re.search(r'Ran [1-9][0-9]* tests?', text) or 'skipped=' in text:
        print('INVALID: baseline '+module, flush=True)
        raise SystemExit(1)
for name, filename, before, after in CASES:
    row = dict(name=name, status='INVALID', detected=False, exit_code=None)
    with tempfile.TemporaryDirectory(prefix='ctl-mut-', dir=SCRATCH) as tmp:
        pool = pathlib.Path(tmp) / 'pool'
        shutil.copytree(ROOT, pool, ignore=shutil.ignore_patterns('__pycache__'))
        file = pool / filename
        source = file.read_text()
        row['source_sha256'] = hashlib.sha256(source.encode()).hexdigest()
        row['anchor_count'] = source.count(before)
        module, test, assertion = TARGETS[name]
        row.update(target=test, assertion=assertion)
        if row['anchor_count'] != 1:
            row['reason'] = 'anchor count must equal one'
        else:
            mutated = source.replace(before, after)
            (LOGS/('mutation-'+name+'.diff')).write_text(''.join(difflib.unified_diff(source.splitlines(True), mutated.splitlines(True), fromfile=filename, tofile=filename)))
            try:
                compile(mutated, filename, 'exec')
                file.write_text(mutated)
                run = run_tests(pool, module, test)
                text = run.stdout + run.stderr
                (LOGS/('mutation-'+name+'.log')).write_text(text)
                row['exit_code'] = run.returncode
                blocks = text.split('======================================================================')
                hit = any(('FAIL: '+test+' ') in block and assertion in block and 'AssertionError' in block for block in blocks)
                invalid = ('ERROR:' in text or 'errors=' in text or 'skipped=' in text or
                           not re.search(r'Ran 1 test\b', text) or run.returncode not in (0, 1))
                row['status'] = 'INVALID' if invalid else ('DETECTED' if run.returncode==1 and hit else ('SURVIVED' if run.returncode==0 else 'INVALID'))
                row['detected'] = row['status']=='DETECTED'
            except (SyntaxError, subprocess.TimeoutExpired) as error:
                row['reason'] = type(error).__name__
    results.append(row)
    print(name+': '+row['status'], flush=True)
(LOGS/'control-readback-mutation-results.json').write_text(json.dumps(results, indent=2))
raise SystemExit(0 if all(r['detected'] for r in results) else 1)

