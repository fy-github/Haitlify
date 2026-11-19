"""Real local edge + CLI authorization regressions; no upstream/model requests."""
import contextlib
import json
import pathlib
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest

from test_protocol import grant

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRATCH = '/Users/lify/.hermes/cache/scratch'


def public(config):
    return {k: v for k, v in config.items() if k != 'capability'}


def wire(path, payload):
    with socket.socket(socket.AF_UNIX) as sock:
        sock.settimeout(5)
        sock.connect(path)
        sock.sendall(json.dumps(payload).encode() + b'\n')
        with sock.makefile('rb') as stream:
            return json.loads(stream.readline())


def cli(path, payload, role='mcp'):
    return subprocess.run([sys.executable, '-B', str(ROOT/'provisioner.py'),
        'control', '--socket', path, '--role', role], input=json.dumps(payload),
        text=True, capture_output=True, timeout=5)


@contextlib.contextmanager
def edge():
    with tempfile.TemporaryDirectory(prefix='ctl-at-', dir=SCRATCH) as tmp:
        path = str(pathlib.Path(tmp)/'s')
        proc = subprocess.Popen([sys.executable, '-B', '-u', str(ROOT/'edge.py'),
            '--role', 'mcp', '--listen', '127.0.0.1', '--port', '0', '--control', path,
            '--target', 'http://127.0.0.1:1/mcp', '--model', 'fixture', '--local-test'],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            assert json.loads(proc.stdout.readline())['mode'] == 'LOCAL-TEST-NOT-ISOLATED'
            yield path
        finally:
            proc.terminate(); proc.wait(timeout=5)
            proc.stdout.close(); proc.stderr.close()


class ControlAtomicTests(unittest.TestCase):
    def test_rejected_lease_identity_preserves_secret_and_all_internal_state(self):
        from protocol import Lease, Rejected
        config = grant(); lease = Lease(); expected = lease.configure(config)
        variants = ['abc123', dict(expected, extra=True)]
        for key in expected:
            bad = dict(expected); del bad[key]; variants.append(bad)
        for key, value in [('binding','other'), ('generation','other'), ('lease',2),
                           ('incarnation','other'), ('protocol','other'), ('policy','other'),
                           ('expiry',config['expiry']+1), ('lease',True), ('lease',1.0),
                           ('expiry',float(config['expiry']))]:
            variants.append(dict(expected, **{key:value}))
        for bad in variants:
            with self.subTest(identity=bad):
                before = (dict(lease.config), lease.spent, lease.status())
                with self.assertRaises(Rejected): lease.revoke(bad)
                self.assertEqual((lease.config, lease.spent, lease.status()), before)
                self.assertEqual(lease.active(), config)

    def test_lease_identity_comparison_and_mutation_share_lock(self):
        from protocol import Lease, Rejected
        config = grant(); lease = Lease(); expected = lease.configure(config)
        entered = threading.Event(); completed = threading.Event(); errors = []
        def revoke():
            entered.set()
            try: lease.revoke(expected)
            except Rejected as error: errors.append(str(error))
            finally: completed.set()
        with lease.lock:
            thread = threading.Thread(target=revoke); thread.start()
            self.assertTrue(entered.wait(1))
            self.assertFalse(completed.wait(.1), 'revoke must not inspect or mutate outside lease lock')
            # Emulate a concurrent owner transition while holding the same lock.
            lease.config['generation'] = 'new-generation'
            before = dict(lease.config)
        thread.join(2)
        self.assertFalse(thread.is_alive())
        self.assertEqual(errors, ['stale lease identity'])
        self.assertEqual(lease.config, before)
        self.assertFalse(lease.spent)

        checked = threading.Event(); release = threading.Event(); acquired = threading.Event()
        observed = []
        class PausingIdentity(dict):
            def __getitem__(self, key):
                if key == 'binding':
                    checked.set()
                    if not release.wait(2): raise AssertionError('comparison barrier timed out')
                return super().__getitem__(key)
        # The stored identity is read by the exact comparison, after validation.
        lease.config = PausingIdentity(config)
        errors.clear(); completed.clear()
        thread = threading.Thread(target=revoke); thread.start()
        self.assertTrue(checked.wait(1))
        def contender():
            with lease.lock:
                acquired.set()
                observed.append((lease.spent, lease.config['capability']))
        other = threading.Thread(target=contender); other.start()
        try: self.assertFalse(acquired.wait(.1), 'identity check must remain locked through mutation')
        finally: release.set(); thread.join(2); other.join(2)
        self.assertFalse(thread.is_alive()); self.assertFalse(other.is_alive())
        self.assertEqual(errors, []); self.assertTrue(acquired.is_set())
        self.assertEqual(observed, [(True, '')])
        self.assertTrue(lease.spent); self.assertEqual(lease.config['capability'], '')

    def test_concurrent_real_edge_wrong_requests_cannot_revoke(self):
        from concurrent.futures import ThreadPoolExecutor
        config = grant(); expected = public(config)
        with edge() as path:
            self.assertEqual(cli(path, {'op':'configure','config':config}).returncode, 0)
            before = wire(path, {'op':'status'})
            good = dict(op='revoke', role='mcp', incarnation=config['incarnation'], expected=expected)
            requests = [dict(good, expected=dict(expected, generation='other')),
                        dict(good, role='provider'), {'op':'status'}] * 8
            with ThreadPoolExecutor(max_workers=6) as pool:
                results = list(pool.map(lambda request: wire(path, request), requests))
            for request, result in zip(requests, results):
                self.assertEqual(result, before if request['op']=='status' else {'ok':False,'ready':False})
            self.assertEqual(wire(path, {'op':'status'}), before)
            final = dict(before, ready=False, revoked=True, spent=True)
            with ThreadPoolExecutor(max_workers=6) as pool:
                results = list(pool.map(lambda _: wire(path, good), range(12)))
            self.assertEqual(results, [final]*12)
            self.assertEqual(wire(path, {'op':'status'}), final)

    def test_expired_exact_revoke_succeeds_but_status_never_claims_ready(self):
        config = dict(grant(), expiry=int(time.time())+2)
        expected = public(config)
        with edge() as path:
            self.assertEqual(cli(path, {'op':'configure','config':config}).returncode, 0)
            time.sleep(max(0, config['expiry'] - time.time()) + .05)
            before = wire(path, {'op':'status'})
            self.assertEqual([before[k] for k in ('ready','revoked','spent')], [False,False,False])
            status = cli(path, {'op':'status','expected':expected})
            self.assertEqual(status.returncode, 1)
            self.assertEqual(json.loads(status.stdout)['reason'], 'invalid expiry')
            result = cli(path, {'op':'revoke','incarnation':config['incarnation'],'expected':expected})
            self.assertEqual(result.returncode, 0, result.stdout)
            after = dict(before, ready=False, revoked=True, spent=True)
            self.assertEqual(json.loads(result.stdout), dict(after, readback=after))
            self.assertEqual(wire(path, {'op':'status'}), after)
            self.assertNotIn(config['capability'], result.stdout + result.stderr)

    def test_revoke_ack_before_expiry_and_real_readback_after_expiry(self):
        config = dict(grant(), expiry=int(time.time())+3)
        expected = public(config)
        with edge() as path, tempfile.TemporaryDirectory(prefix='ctl-delay-', dir=SCRATCH) as tmp:
            self.assertEqual(cli(path, {'op':'configure','config':config}).returncode, 0)
            relay_path = str(pathlib.Path(tmp)/'s')
            with socket.socket(socket.AF_UNIX) as relay:
                relay.bind(relay_path)
                pathlib.Path(relay_path).chmod(0o700)
                relay.listen(2); relay.settimeout(5)
                seen = []; errors = []
                def serve():
                    try:
                        for i in range(2):
                            conn, _ = relay.accept()
                            with conn:
                                conn.settimeout(5)
                                with conn.makefile('rb') as stream:
                                    request = json.loads(stream.readline())
                                if i == 1:
                                    time.sleep(max(0, config['expiry'] - time.time())+.05)
                                response = wire(path, request)
                                seen.append((request['op'], time.time()))
                                conn.sendall(json.dumps(response).encode()+b'\n')
                    except Exception as error:
                        errors.append(error)
                thread = threading.Thread(target=serve); thread.start()
                try:
                    result = cli(relay_path, {'op':'revoke','incarnation':config['incarnation'],'expected':expected})
                finally:
                    thread.join(6)
                self.assertFalse(thread.is_alive()); self.assertEqual(errors, [])
                self.assertEqual([op for op, _ in seen], ['revoke','status'])
                self.assertLess(seen[0][1], config['expiry'])
                self.assertGreaterEqual(seen[1][1], config['expiry'])
                self.assertEqual(result.returncode, 0, result.stdout)
                final = dict(expected, ok=True, role='mcp', ready=False, revoked=True, spent=True)
                self.assertEqual(json.loads(result.stdout), dict(final, readback=final))
                self.assertEqual(wire(path, {'op':'status'}), final)
                self.assertNotIn(config['capability'], result.stdout + result.stderr)

    def test_expired_identity_still_rejected_for_configure_and_active(self):
        from protocol import Lease, Rejected
        from unittest.mock import patch
        config = grant(); lease = Lease(); lease.configure(config)
        with patch('protocol.time.time', return_value=config['expiry']):
            with self.assertRaises(Rejected): Lease().configure(config)
            with self.assertRaises(Rejected): lease.active()
            lease.revoke(public(config))
            self.assertEqual(lease.config['capability'], '')
            self.assertEqual(lease.status(), dict(public(config), ready=False, spent=True, revoked=True))

    def test_wrong_role_and_each_identity_cli_never_mutate_real_edge(self):
        config = grant()
        expected = public(config)
        variants = [('role', expected, 'provider')]
        for key, value in [('binding', 'other'), ('generation', 'other'), ('lease', 2),
                           ('incarnation', 'other'), ('protocol', 'other'),
                           ('policy', 'other'), ('expiry', config['expiry']+1),
                           ('lease', True), ('lease', 1.0), ('expiry', float(config['expiry']))]:
            variants.append((key, dict(expected, **{key:value}), 'mcp'))
        for name, identity, role in variants:
            with self.subTest(field=name, role=role), edge() as path:
                configured = cli(path, {'op':'configure', 'config':config})
                self.assertEqual(configured.returncode, 0, configured.stdout)
                before = wire(path, {'op':'status'})
                result = cli(path, {'op':'revoke', 'incarnation':identity['incarnation'],
                                    'expected':identity}, role)
                self.assertEqual(result.returncode, 1)
                self.assertNotIn(config['capability'], result.stdout + result.stderr)
                self.assertEqual(wire(path, {'op':'status'}), before,
                                 'failure must leave full state unchanged, not revoke then reject receipt')

    def test_wire_rejects_missing_unknown_wrong_role_and_identity_before_mutation(self):
        config = grant(); expected = public(config)
        good = dict(op='revoke', role='mcp', incarnation=config['incarnation'], expected=expected)
        variants = [{'op':'revoke', 'incarnation':config['incarnation']}, dict(good, extra=True),
                    dict(good, role='provider'), dict(good, role=True), dict(good, incarnation='other')]
        for key in good:
            bad = dict(good); del bad[key]; variants.append(bad)
        for key in expected:
            bad = dict(expected); del bad[key]; variants.append(dict(good, expected=bad))
        for key, value in [('binding','other'), ('generation','other'), ('lease',2),
                           ('incarnation','other'), ('protocol','other'), ('policy','other'),
                           ('expiry',config['expiry']+1), ('lease',True), ('lease',1.0),
                           ('expiry',float(config['expiry'])), ('capability',config['capability'])]:
            variants.append(dict(good, expected=dict(expected, **{key:value})))
        with edge() as path:
            self.assertEqual(cli(path, {'op':'configure','config':config}).returncode, 0)
            before = wire(path, {'op':'status'})
            for bad in variants:
                with self.subTest(request=bad):
                    receipt = wire(path, bad)
                    self.assertEqual(receipt, {'ok':False, 'ready':False})
                    self.assertEqual(wire(path, {'op':'status'}), before)
                    self.assertNotIn(config['capability'], json.dumps(receipt))
            self.assertEqual(wire(path, good), dict(before, ready=False, spent=True, revoked=True))

    def test_configure_wrong_role_or_unscoped_wire_never_mutates(self):
        config = grant()
        with edge() as path:
            before = wire(path, {'op':'status'})
            result = cli(path, {'op':'configure','config':config}, 'provider')
            self.assertEqual(result.returncode, 1)
            self.assertEqual(wire(path, {'op':'status'}), before)
            for bad in ({'op':'configure','config':config},
                        {'op':'configure','config':config,'role':'mcp','extra':True}):
                self.assertFalse(wire(path, bad)['ok'])
                self.assertEqual(wire(path, {'op':'status'}), before)
            self.assertEqual(cli(path, {'op':'configure','config':config}).returncode, 0)


if __name__ == '__main__': unittest.main()
