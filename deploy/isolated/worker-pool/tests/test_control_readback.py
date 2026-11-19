"""Real CLI/UDS tests; synthetic receipts are intentionally hostile peers."""
import json
import os
import pathlib
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
from test_protocol import grant

ROOT = pathlib.Path(__file__).resolve().parents[1]
from worker_test_paths import scratch_root
SCRATCH = scratch_root()


def snapshot(config, **changes):
    result = {k: v for k, v in config.items() if k != 'capability'}
    result.update(ok=True, role='mcp', ready=True, revoked=False, spent=False)
    result.update(changes)
    return result


class ControlReadbackTests(unittest.TestCase):
    def run_peer(self, payload, replies, args=(), mode=0o700):
        with tempfile.TemporaryDirectory(dir=SCRATCH, prefix='ctl-rb-') as tmp:
            path = str(pathlib.Path(tmp) / 'c.sock')
            server = socket.socket(socket.AF_UNIX)
            server.bind(path); os.chmod(path, mode); server.listen(4); server.settimeout(.2)
            seen = []
            stop = threading.Event()
            def serve():
                while not stop.is_set() and len(seen) < len(replies):
                    try: conn, _ = server.accept()
                    except socket.timeout: continue
                    with conn:
                        conn.settimeout(2)
                        with conn.makefile('rb') as stream:
                            seen.append(json.loads(stream.readline()))
                        conn.sendall(json.dumps(replies[len(seen)-1]).encode() + b'\n')
            thread = threading.Thread(target=serve); thread.start()
            try:
                proc = subprocess.run([sys.executable, str(ROOT/'provisioner.py'), 'control',
                    '--socket', path, '--role', 'mcp', *args], input=json.dumps(payload), text=True,
                    capture_output=True, timeout=5)
            finally:
                stop.set(); thread.join(3); server.close()
            self.assertFalse(thread.is_alive())
            return proc, seen

    def test_configure_ack_ready_but_readback_inactive_is_failure(self):
        config = grant()
        proc, seen = self.run_peer({'op':'configure','config':config},
            [snapshot(config), snapshot(config, ready=False)])
        self.assertNotEqual(proc.returncode, 0, 'contradictory readback must not report success')
        self.assertNotIn(config['capability'], proc.stdout + proc.stderr)
        self.assertEqual([m['op'] for m in seen], ['configure','status'])
        self.assertEqual(json.loads(proc.stdout)['reason'], 'control readback state mismatch')

    def test_configure_exact_identity_types_closed_schema_and_state(self):
        config = grant(); good = snapshot(config)
        variants = []
        for key in good:
            bad = dict(good); del bad[key]; variants.append(('missing-' + key, bad))
        for key, value in [('binding','other'), ('generation','other'), ('lease',2),
                           ('incarnation','other'), ('policy','other'), ('expiry',config['expiry']+1),
                           ('protocol','other'), ('role','provider'), ('revoked',True), ('spent',True),
                           ('ready',1), ('ok',1), ('lease',True), ('expiry',True),
                           ('revoked',0), ('spent',0), ('capability',config['capability']),
                           ('error',config['capability'])]:
            variants.append((key, dict(good, **{key:value})))
        for where in (0, 1):
            for name, bad in variants:
                with self.subTest(where=where, field=name):
                    replies = [good, good]; replies[where] = bad
                    proc, seen = self.run_peer({'op':'configure','config':config}, replies)
                    self.assertNotEqual(proc.returncode, 0)
                    self.assertNotIn(config['capability'], proc.stdout + proc.stderr)
                    self.assertEqual(sum(m['op']=='configure' for m in seen), 1)
                    self.assertEqual(json.loads(proc.stdout)['verdict'], 'FAIL')
                    if name in ('ready', 'revoked', 'spent') and type(bad.get(name)) is int:
                        self.assertEqual(json.loads(proc.stdout)['reason'], 'control receipt boolean type mismatch')

    def test_configure_success_is_exact_nonsecret_receipt(self):
        config = grant(); good = snapshot(config)
        proc, seen = self.run_peer({'op':'configure','config':config}, [good, good])
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertEqual(json.loads(proc.stdout), dict(good, readback=good))
        self.assertEqual([m['op'] for m in seen], ['configure','status'])

    def test_revoke_requires_expected_identity_and_positive_revocation(self):
        config = grant(); public = {k:v for k,v in config.items() if k != 'capability'}
        payload = {'op':'revoke','incarnation':config['incarnation'],'expected':public}
        good = snapshot(config, ready=False, spent=True, revoked=True)
        proc, seen = self.run_peer(payload, [good, good])
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertEqual(seen, [dict(payload, role='mcp'), {'op':'status'}])
        variants = [snapshot(config, ready=False), dict(good, incarnation='wrong'),
                    dict(good, binding='wrong'), dict(good, role='provider'),
                    dict(good, spent=False), dict(good, revoked=False),
                    dict(good, ready=True), {'ok':True,'ready':False}]
        for key in good:
            bad = dict(good); del bad[key]; variants.append(bad)
        variants += [dict(good, capability=config['capability']), dict(good, revoked=1)]
        for where in (0, 1):
            for bad in variants:
                with self.subTest(where=where, bad=bad):
                    replies = [good, good]; replies[where] = bad
                    proc, _ = self.run_peer(payload, replies)
                    self.assertNotEqual(proc.returncode, 0)
                    self.assertNotIn(config['capability'], proc.stdout + proc.stderr)
        for bad in ({'op':'revoke','incarnation':config['incarnation']},
                    dict(payload, incarnation='wrong'), dict(payload, unexpected=True)):
            proc, seen = self.run_peer(bad, [good, good])
            self.assertNotEqual(proc.returncode, 0)
            self.assertEqual(seen, [], 'invalid intent must be rejected before mutation')

    def test_status_requires_expected_identity_and_rejects_unsafe_receipts(self):
        config = grant(); public = {k:v for k,v in config.items() if k != 'capability'}
        good = snapshot(config); payload = {'op':'status','expected':public}
        proc, seen = self.run_peer(payload, [good, good])
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertEqual(seen, [{'op':'status'}, {'op':'status'}])
        for key, value in [('role','provider'), ('binding','other'), ('generation','other'),
                           ('lease',True), ('expiry',0), ('ready',False), ('spent',True),
                           ('capability',config['capability'])]:
            proc, _ = self.run_peer(payload, [good, dict(good, **{key:value})])
            self.assertNotEqual(proc.returncode, 0)
            self.assertNotIn(config['capability'], proc.stdout + proc.stderr)
        proc, seen = self.run_peer({'op':'status'}, [good, good])
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(seen, [])

    def test_invalid_intent_and_expiry_rejected_before_connect(self):
        config = grant(); good = snapshot(config)
        for payload in ({'op':'configure','config':dict(config, expiry=0)},
                        {'op':'configure','config':config,'extra':'ignored'},
                        {'op':'configure','config':dict(config, lease=True)},
                        {'op':'configure','config':dict(config, capability='bad')},
                        {'op':'wrong'}, [], None):
            proc, seen = self.run_peer(payload, [good, good])
            self.assertNotEqual(proc.returncode, 0)
            self.assertEqual(seen, [])
            self.assertNotIn(config['capability'], proc.stdout + proc.stderr)

    def test_non_owner_only_socket_rejected_before_send(self):
        config = grant(); good = snapshot(config)
        for mode in (0o777, 0o770, 0o707, 0o666):
            proc, seen = self.run_peer({'op':'configure','config':config}, [good, good], mode=mode)
            self.assertNotEqual(proc.returncode, 0)
            self.assertEqual(seen, [])
            self.assertEqual(json.loads(proc.stdout)['reason'], 'control socket ownership or mode unsafe')

    def test_expired_readback_rejected_even_when_both_claim_ready(self):
        from unittest.mock import patch
        from provisioner import verify_receipt
        from protocol import Rejected
        config = grant(); good = snapshot(config)
        public = {k:v for k,v in config.items() if k != 'capability'}
        with patch('protocol.time.time', return_value=config['expiry']):
            with self.assertRaisesRegex(Rejected, 'invalid expiry'):
                verify_receipt(good, public, 'mcp', False)

    def test_status_never_confuses_unconfigured_expired_and_revoked(self):
        from protocol import Lease
        from unittest.mock import patch
        config = grant(); lease = Lease()
        empty = lease.status()
        self.assertTrue(all(empty[k] is None for k in config if k != 'capability'))
        self.assertEqual([empty[k] for k in ('ready','spent','revoked')], [False,False,False])
        lease.configure(config)
        public = {k:v for k,v in config.items() if k != 'capability'}
        with patch('protocol.time.time', return_value=config['expiry']):
            self.assertEqual(lease.status(), dict(public, ready=False, spent=False, revoked=False))
        lease.revoke(public)
        self.assertEqual(lease.status(), dict(public, ready=False, spent=True, revoked=True))
        self.assertNotIn(config['capability'], json.dumps(lease.status()))


if __name__ == '__main__': unittest.main()
