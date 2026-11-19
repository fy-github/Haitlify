"""U1-U5: local CLI/UDS evidence, never sandbox/admission evidence."""
import contextlib
import copy
import io
import json
import os
import pathlib
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from test_protocol import grant
from test_control_atomic import edge, public, wire
from test_control_readback import snapshot
from test_bounded_edge import running, wait_for

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRATCH = '/Users/lify/.hermes/cache/scratch'


def command(path, operation='control'):
    return [sys.executable, '-B', str(ROOT/'provisioner.py'), operation,
            '--socket', path, '--role', 'mcp']


def cli(path, payload, operation='control'):
    return subprocess.run(command(path, operation), input=json.dumps(payload),
                          text=True, capture_output=True, timeout=11)


@contextlib.contextmanager
def peer(responder, count=2, paused=None):
    with tempfile.TemporaryDirectory(dir=SCRATCH, prefix='u-peer-') as tmp:
        path = str(pathlib.Path(tmp)/'s')
        server = socket.socket(socket.AF_UNIX)
        server.bind(path); os.chmod(path, 0o700); server.listen(8); server.settimeout(.1)
        stop = threading.Event(); seen = []; errors = []
        def run():
            try:
                while not stop.is_set() and len(seen) < count:
                    if paused is not None and not paused.wait(.05): continue
                    try: conn, _ = server.accept()
                    except socket.timeout: continue
                    with conn:
                        conn.settimeout(1)
                        with conn.makefile('rb') as stream:
                            request = json.loads(stream.readline())
                        seen.append(request)
                        responder(conn, request, len(seen)-1, stop)
            except Exception as error: errors.append(repr(error))
        thread = threading.Thread(target=run); thread.start()
        try: yield path, seen, errors
        finally:
            stop.set(); thread.join(2); server.close()
            if thread.is_alive(): raise AssertionError('peer thread leaked')
            if errors: raise AssertionError(errors)


class UncertaintyTests(unittest.TestCase):
    def failure(self, proc, state, secret='TEST-CAPABILITY-NOT-A-REAL-TOKEN'):
        self.assertEqual(proc.returncode, 1, 'U_FAILURE_EXIT')
        self.assertTrue(proc.stdout, 'U_FAILURE_SAFE_ENVELOPE')
        result = json.loads(proc.stdout)
        self.assertEqual(result['verdict'], 'FAIL', 'U_FAILURE_VERDICT')
        self.assertEqual(result.get('control_state'), state, 'U_CONTROL_STATE')
        self.assertIs(result.get('quarantine_required'), True, 'U_QUARANTINE_REQUIRED')
        self.assertNotIn(secret, proc.stdout + proc.stderr, 'U_NO_SECRET')
        return result

    def test_u1_before_send_not_sent_preserves_healthy_lease(self):
        config = grant()
        with edge() as path:
            self.assertEqual(cli(path, {'op':'configure', 'config':config}).returncode, 0)
            before = wire(path, {'op':'status'})
            for payload in ({'op':'wrong'}, {'op':'configure', 'config':dict(config, lease=True)}):
                self.failure(cli(path, payload), 'NOT_SENT')
            os.chmod(path, 0o777)
            self.failure(cli(path, {'op':'status', 'expected':public(config)}), 'NOT_SENT')
            os.chmod(path, 0o700)
            self.assertEqual(wire(path, {'op':'status'}), before, 'U1_HEALTHY_UNCHANGED')
        with tempfile.TemporaryDirectory(dir=SCRATCH, prefix='u1-') as tmp:
            path = str(pathlib.Path(tmp)/'s')
            with socket.socket(socket.AF_UNIX) as sock:
                sock.bind(path); os.chmod(path, 0o700)
            self.failure(cli(path, {'op':'configure','config':config}), 'NOT_SENT')

    def test_u3_stdin_requires_eof_and_absolute_one_second(self):
        for drip in (False, True):
            with self.subTest(drip=drip):
                proc = subprocess.Popen(command('/unused'), stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                stop = threading.Event()
                def feed():
                    try:
                        proc.stdin.write(b'{}'); proc.stdin.flush()
                        while drip and not stop.wait(.08):
                            proc.stdin.write(b' '); proc.stdin.flush()
                    except (BrokenPipeError, ValueError): pass
                thread = threading.Thread(target=feed); start = time.monotonic(); thread.start()
                try:
                    try: proc.wait(timeout=1.8)
                    except subprocess.TimeoutExpired:
                        self.fail('U3_STDIN_ABSOLUTE_EOF: stdin remained open beyond 1s budget')
                    self.assertLess(time.monotonic()-start, 1.8, 'U3_STDIN_ABSOLUTE_EOF')
                    self.failure(subprocess.CompletedProcess([], proc.returncode,
                        proc.stdout.read().decode(), proc.stderr.read().decode()), 'NOT_SENT')
                finally:
                    stop.set(); thread.join(1)
                    if proc.poll() is None: proc.kill(); proc.wait()
                    try: proc.stdin.close()
                    except BrokenPipeError: pass
                    proc.stdout.close(); proc.stderr.close()

    def test_u3_ack_and_readback_slow_drip_absolute_exchange(self):
        config = grant(); good = snapshot(config)
        for slow_index in (0, 1):
            def respond(conn, request, index, stop):
                if index != slow_index:
                    conn.sendall(json.dumps(good).encode()+b'\n'); return
                while not stop.wait(.08):
                    try: conn.sendall(b' ')
                    except OSError: return
            with self.subTest(slow=slow_index), peer(respond) as (path, seen, _):
                proc = subprocess.Popen(command(path), stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                start = time.monotonic()
                try:
                    try: out, err = proc.communicate(json.dumps({'op':'configure','config':config}).encode(), timeout=4.8)
                    except subprocess.TimeoutExpired:
                        self.fail('U3_EXCHANGE_ABSOLUTE: slow peer extended exchange beyond 4s')
                    self.assertLess(time.monotonic()-start, 4.8, 'U3_EXCHANGE_ABSOLUTE')
                    self.failure(subprocess.CompletedProcess([], proc.returncode, out.decode(), err.decode()), 'UNKNOWN')
                    self.assertEqual(sum(m['op']=='configure' for m in seen), 1, 'U3_NO_REPLAY')
                finally:
                    if proc.poll() is None: proc.kill(); proc.communicate()

    def test_u3_stdout_backpressure_is_bounded_nonzero(self):
        config = grant()
        for success in (False, True):
            with self.subTest(success=success), edge() as path:
                read_fd, write_fd = os.pipe()
                os.set_blocking(write_fd, False)
                try:
                    while True: os.write(write_fd, b'x'*4096)
                except BlockingIOError: pass
                os.set_blocking(write_fd, True)
                proc = subprocess.Popen(command(path), stdin=subprocess.PIPE, stdout=write_fd,
                                        stderr=subprocess.PIPE)
                start = time.monotonic()
                try:
                    proc.stdin.write(json.dumps({'op':'configure','config':config} if success else {'op':'wrong'}).encode())
                    proc.stdin.close()
                    try: proc.wait(timeout=1.2)
                    except subprocess.TimeoutExpired: self.fail('U3_STDOUT_BOUNDED: process hung flushing stdout')
                    self.assertEqual(proc.returncode, 1, 'U3_STDOUT_NONZERO')
                    self.assertLess(time.monotonic()-start, 1.2, 'U3_STDOUT_BOUNDED')
                    self.assertNotIn(config['capability'], proc.stderr.read().decode(), 'U3_STDOUT_NO_SECRET')
                    self.assertEqual(wire(path, {'op':'status'})['ready'], success, 'U3_OUTPUT_NOT_ROLLBACK')
                finally:
                    if proc.poll() is None: proc.kill(); proc.wait()
                    proc.stderr.close(); os.close(write_fd); os.close(read_fd)

    def test_u4_reconcile_exact_two_observations_never_admits(self):
        for operation in ('configure', 'revoke'):
            config = grant()
            if operation == 'revoke': config['expiry'] = int(time.time())-1
            good = snapshot(config, ready=operation=='configure', revoked=operation=='revoke', spent=operation=='revoke')
            def respond(conn, request, index, stop): conn.sendall(json.dumps(good).encode()+b'\n')
            with self.subTest(operation=operation), peer(respond) as (path, seen, _):
                result = cli(path, {'operation':operation, 'expected':public(config)}, 'reconcile')
                self.assertEqual(result.returncode, 0, 'U4_RECONCILE_SUCCESS: '+result.stdout+result.stderr)
                data = json.loads(result.stdout)
                self.assertEqual(data, dict(control_state='VERIFIED', verified_state='ACTIVE' if operation=='configure' else 'REVOKED',
                    admission_allowed=False, reuse_allowed=False, readback=good), 'U4_NO_ADMISSION')
                self.assertEqual(seen, [{'op':'status'}, {'op':'status'}], 'U4_EXACT_TWO_STATUS')

    def test_u2_real_commit_lost_ack_and_bad_readback_no_replay(self):
        for operation in ('configure', 'revoke'):
            for fault in ('lost_ack', 'bad_ack', 'lost_readback', 'bad_readback', 'type_ack', 'type_readback', 'truncated_ack', 'truncated_readback'):
                with self.subTest(operation=operation, fault=fault), edge() as target:
                    config = grant(); expected = public(config)
                    if operation == 'revoke':
                        self.assertEqual(cli(target, {'op':'configure','config':config}).returncode, 0)
                    payload = {'op':'configure','config':config} if operation=='configure' else dict(op='revoke', incarnation=config['incarnation'], expected=expected)
                    def respond(conn, request, index, stop):
                        response = wire(target, request)
                        if (fault=='lost_ack' and index==0) or (fault=='lost_readback' and index==1): return
                        if (fault=='bad_ack' and index==0) or (fault=='bad_readback' and index==1):
                            response['generation'] = 'wrong'
                        if (fault=='type_ack' and index==0) or (fault=='type_readback' and index==1):
                            response['ready'] = 1
                        if (fault=='truncated_ack' and index==0) or (fault=='truncated_readback' and index==1):
                            conn.sendall(b'{"ok":true'); return
                        conn.sendall(json.dumps(response).encode()+b'\n')
                    with peer(respond) as (path, seen, _):
                        self.failure(cli(path, payload), 'UNKNOWN')
                        self.assertEqual(sum(m['op']==operation for m in seen), 1, 'U2_ONE_MUTATION')
                    final = snapshot(config, ready=operation=='configure', revoked=operation=='revoke', spent=operation=='revoke')
                    self.assertEqual(wire(target, {'op':'status'}), final, 'U2_REAL_COMMIT')

    def test_u3_first_and_partial_send_failure_are_unknown(self):
        import provisioner
        for partial in (False, True):
            attempt = {'control_state':'NOT_SENT'}; calls = []
            class SocketSeam:
                def __enter__(self): return self
                def __exit__(self, *args): pass
                def settimeout(self, value): pass
                def connect(self, path): pass
                def send(self, data):
                    calls.append(attempt['control_state'])
                    if partial and len(calls)==1: return 3
                    raise OSError('TEST-CAPABILITY-NOT-A-REAL-TOKEN')
            with self.subTest(partial=partial), tempfile.TemporaryDirectory(dir=SCRATCH, prefix='u-send-') as tmp:
                path = str(pathlib.Path(tmp)/'s')
                with socket.socket(socket.AF_UNIX) as real:
                    real.bind(path); os.chmod(path, 0o700)
                    with patch.object(provisioner.socket, 'socket', return_value=SocketSeam()):
                        with self.assertRaises(OSError): provisioner.control(path, {'op':'status'}, attempt)
                self.assertEqual(calls, ['UNKNOWN']*(2 if partial else 1), 'U3_UNKNOWN_BEFORE_FIRST_SEND')
                self.assertEqual(attempt['control_state'], 'UNKNOWN', 'U3_PARTIAL_SEND_UNKNOWN')

    def test_u4_reconcile_rejects_each_untrusted_observation(self):
        for operation in ('configure', 'revoke'):
            config = grant(); revoked = operation=='revoke'
            good = snapshot(config, ready=not revoked, revoked=revoked, spent=revoked)
            variants = []
            for key in good:
                missing = dict(good); del missing[key]; variants.append(missing)
            for key in public(config):
                variants.append(dict(good, **{key: 'wrong' if type(good[key]) is str else True}))
            variants += [dict(good, role='provider'), dict(good, ready=revoked),
                         dict(good, revoked=not revoked), dict(good, spent=not revoked),
                         dict(good, ok=1), dict(good, ready=1), dict(good, revoked=0),
                         dict(good, spent=0), dict(good, capability=config['capability']),
                         {'ok':True, 'ready':False}]
            for where in (0, 1):
                for bad in variants:
                    def respond(conn, request, index, stop):
                        conn.sendall(json.dumps(bad if index==where else good).encode()+b'\n')
                    with self.subTest(operation=operation, where=where, bad=bad), peer(respond) as (path, seen, _):
                        self.failure(cli(path, {'operation':operation,'expected':public(config)}, 'reconcile'), 'UNKNOWN')
                        self.assertEqual(seen, [{'op':'status'}, {'op':'status'}], 'U4_EXACT_TWO_STATUS')
        for operation in ('configure', 'revoke'):
            config = dict(grant(), expiry=int(time.time())-1)
            expired = snapshot(config, ready=False)
            def respond(conn, request, index, stop): conn.sendall(json.dumps(expired).encode()+b'\n')
            with peer(respond) as (path, seen, _):
                self.failure(cli(path, {'operation':operation,'expected':public(config)}, 'reconcile'), 'UNKNOWN')
                self.assertEqual(len(seen), 2, 'U4_EXPIRED_NOT_REVOKED')

    def test_u5_pending_accept_can_commit_after_unknown(self):
        import signal
        config = grant()
        with tempfile.TemporaryDirectory(dir=SCRATCH, prefix='u5-') as tmp:
            path = str(pathlib.Path(tmp)/'s'); log = pathlib.Path(tmp)/'commits'
            # Instrument only real successful Lease.configure, not a fake server.
            bootstrap = "import sys,runpy,protocol,pathlib; original=protocol.Lease.configure; log=pathlib.Path(sys.argv.pop(1)); exec('def counted(self, config):\\n result=original(self, config)\\n with log.open(\"a\") as stream: stream.write(\"configure\\\\n\")\\n return result'); protocol.Lease.configure=counted; runpy.run_path(sys.argv[0], run_name='__main__')"
            args = [sys.executable, '-B', '-u', '-c', bootstrap, log.as_posix(), str(ROOT/'edge.py'),
                    '--role','mcp','--listen','127.0.0.1','--port','0','--control',path,
                    '--target','http://127.0.0.1:1/mcp','--model','fixture','--local-test']
            # -c's argv[0] is -c; bootstrap normalizes the script argv explicitly.
            bootstrap = bootstrap.replace("log=pathlib.Path(sys.argv.pop(1));", "log=pathlib.Path(sys.argv.pop(1)); sys.argv.pop(0);")
            args[4] = bootstrap
            proc = subprocess.Popen(args, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                self.assertEqual(json.loads(proc.stdout.readline())['mode'], 'LOCAL-TEST-NOT-ISOLATED')
                os.kill(proc.pid, signal.SIGSTOP)
                # waitpid WUNTRACED acknowledges the exact owned process is stopped.
                self.assertTrue(os.WIFSTOPPED(os.waitpid(proc.pid, os.WUNTRACED)[1]))
                pending = threading.Event()
                def relay(conn, request, index, stop):
                    # Hold the upstream connection open after CLI timeout, just as
                    # a transport relay may. No accept or mutation has happened.
                    with socket.socket(socket.AF_UNIX) as upstream:
                        upstream.settimeout(7); upstream.connect(path)
                        upstream.sendall(json.dumps(request).encode()+b'\n'); pending.set()
                        with upstream.makefile('rb') as stream: reply = stream.readline()
                        try: conn.sendall(reply)
                        except OSError: pass
                with peer(relay, count=1) as (relay_path, seen, _):
                    start = time.monotonic()
                    result = cli(relay_path, {'op':'configure','config':config})
                    try:
                        self.failure(result, 'UNKNOWN')
                        self.assertTrue(pending.is_set(), 'U5_FRAME_PENDING_BEFORE_ACCEPT')
                        self.assertLess(time.monotonic()-start, 4.8, 'U5_PENDING_BUDGET')
                        self.assertFalse(log.exists(), 'U5_NO_ACCEPT_BEFORE_RELEASE')
                    finally:
                        os.kill(proc.pid, signal.SIGCONT)
                    end = time.monotonic()+2
                    while not log.exists() and time.monotonic()<end: time.sleep(.01)
                    self.assertTrue(log.exists(), 'U5_LATE_COMMIT_REQUIRED')
                    self.assertEqual(log.read_text().splitlines(), ['configure'], 'U5_ONE_LATE_COMMIT')
                    self.assertEqual(len(seen), 1, 'U5_ONE_MUTATION_SEND')
                self.assertEqual(wire(path, {'op':'status'}), snapshot(config), 'U5_REAL_LATE_STATE')
                result = cli(path, {'operation':'configure','expected':public(config)}, 'reconcile')
                self.assertEqual(result.returncode, 0, 'U5_RECONCILE')
                data = json.loads(result.stdout)
                self.assertEqual((data['admission_allowed'], data['reuse_allowed']), (False, False), 'U5_NO_ADMISSION')
                self.assertEqual(log.read_text().splitlines(), ['configure'], 'U5_RECONCILE_READ_ONLY')
            finally:
                if proc.poll() is None:
                    os.kill(proc.pid, signal.SIGCONT); proc.terminate(); proc.wait(timeout=3)
                proc.stdout.close(); proc.stderr.close()

    def assert_u3_input_rejection(self, raw, reason=None, parser_mode=None):
        # Only observe json.loads (or make it raise for one sentinel input).
        # The shared strict_json, stdin reader, CLI main and exception handler
        # remain real in the child process. Diagnostics go to an owned file,
        # never to the safe stdout/stderr envelope being tested.
        bootstrap = '''import json, pathlib, sys
import edge, provisioner
mode = sys.argv.pop(1)
trace = pathlib.Path(sys.argv.pop(1))
original = edge.json.loads
observed = []
def observed_loads(data, *args, **kwargs):
    event = dict(hooks=sorted(kwargs))
    try:
        if mode == 'recursion' and data == b'{"recursion_fixture":"TEST-CAPABILITY-NOT-A-REAL-TOKEN"}':
            raise RecursionError('TEST-CAPABILITY-NOT-A-REAL-TOKEN')
        value = original(data, *args, **kwargs)
        event['returned'] = type(value).__name__
        return value
    except Exception as error:
        event['raised'] = type(error).__name__
        raise
    finally:
        observed.append(event)
        trace.write_text(json.dumps(observed))
edge.json.loads = observed_loads
raise SystemExit(provisioner.main())
'''
        with running() as env, tempfile.TemporaryDirectory(dir=SCRATCH, prefix='u-parse-') as tmp:
            config = grant(); env.lease.configure(config)
            before = copy.deepcopy((env.lease.config, env.lease.spent))
            workers = tuple(env.control.workers)
            args = command(env.path)
            trace = pathlib.Path(tmp)/'parser.json'
            if parser_mode:
                args = [sys.executable, '-B', '-c', bootstrap, parser_mode, str(trace)] + args[3:]
            result = subprocess.run(args, cwd=ROOT, input=raw, capture_output=True, timeout=2)
            data = self.failure(subprocess.CompletedProcess([], result.returncode,
                result.stdout.decode(), result.stderr.decode()), 'NOT_SENT')
            if parser_mode:
                events = json.loads(trace.read_text())
                hooks = ['object_pairs_hook', 'parse_constant']
                if parser_mode == 'recursion':
                    self.assertEqual(events, [dict(hooks=hooks, raised='RecursionError')])
                elif events == [dict(hooks=hooks, raised='RecursionError')]:
                    reason = 'invalid configuration, receipt, or ownership'
                else:
                    # The real decoder succeeded, so the real control_intent
                    # object check, not a recursion catch, must reject the list.
                    self.assertEqual(events, [dict(hooks=hooks, returned='list')])
                    reason = 'control intent object required'
            self.assertEqual(data['reason'], reason, 'U3_PARSE_REASON')
            self.assertEqual(data, dict(verdict='FAIL', reason=reason,
                control_state='NOT_SENT', quarantine_required=True), 'U3_PARSE_SAFE_ENVELOPE')
            self.assertEqual(result.stderr, b'', 'U3_PARSE_SAFE_NO_TRACEBACK')
            self.assertEqual((env.lease.config, env.lease.spent), before, 'U3_PARSE_LEASE_UNCHANGED')
            self.assertTrue(wire(env.path, {'op':'status'})['ready'], 'U3_PARSE_RECOVERY')
            wait_for(lambda: env.control.pending.unfinished_tasks == 0)
            self.assertEqual((env.control.active, env.control.pending.qsize()), (0, 0))
            self.assertEqual(tuple(env.control.workers), workers)
            self.assertTrue(all(worker.is_alive() for worker in workers))
            self.assertEqual((env.lease.config, env.lease.spent), before, 'U3_PARSE_LEASE_UNCHANGED')

    def test_u3_oversize_input_fails_safely(self):
        self.assert_u3_input_rejection(b' '*1048577, 'control input too large')

    def test_u3_recursion_error_input_fails_safely(self):
        self.assert_u3_input_rejection(
            b'{"recursion_fixture":"TEST-CAPABILITY-NOT-A-REAL-TOKEN"}',
            'invalid configuration, receipt, or ownership', parser_mode='recursion')

    def test_u3_deep_input_fails_safely(self):
        # Preserve the original input and observe its actual parse path:
        # 3.9 raises RecursionError; 3.14 returns a list. Neither authorizes
        # control, and neither establishes an arbitrary-depth CPU limit.
        self.assert_u3_input_rejection(b'['*2000+b'0'+b']'*2000, parser_mode='observe')

    def test_u3_shared_cli_deadline_not_reset_between_exchanges(self):
        config = grant(); good = snapshot(config)
        def respond(conn, request, index, stop):
            if stop.wait(.30 if index == 0 else .65): return
            try: conn.sendall(json.dumps(good).encode()+b'\n')
            except OSError: pass
        with peer(respond) as (path, seen, _):
            # Scale only the outer budget; real exchanges and OS I/O stay intact.
            args = [sys.executable, '-B', '-c',
                    'import provisioner; provisioner.CLI_SECONDS=.55; raise SystemExit(provisioner.main())',
                    'control', '--socket', path, '--role', 'mcp']
            start = time.monotonic()
            result = subprocess.run(args, cwd=ROOT, input=json.dumps({'op':'configure','config':config}),
                                    text=True, capture_output=True, timeout=2)
            self.assertEqual(result.returncode, 1, 'U3_SHARED_CLI_DEADLINE')
            self.assertLess(time.monotonic()-start, .80, 'U3_SHARED_CLI_BOUND')
            self.assertEqual(seen, [dict(op='configure',config=config,role='mcp'), {'op':'status'}], 'U3_SHARED_TWO_EXCHANGES')
            self.assertNotIn(config['capability'], result.stdout+result.stderr, 'U3_SHARED_NO_SECRET')

    def test_u1_zero_send_on_validation_ownership_connect_failure(self):
        import provisioner
        for fault in ('ownership', 'connect'):
            attempt = {'control_state':'NOT_SENT'}; sends = []
            class SocketSeam:
                def __enter__(self): return self
                def __exit__(self, *args): pass
                def settimeout(self, value): self.timeout = value
                def connect(self, path):
                    self_test.assertLessEqual(self.timeout, .5, 'U1_CONNECT_CAP')
                    raise OSError('connect failed')
                def send(self, data): sends.append(data)
            self_test = self
            with tempfile.TemporaryDirectory(dir=SCRATCH, prefix='u1-seam-') as tmp:
                path = str(pathlib.Path(tmp)/'s')
                with socket.socket(socket.AF_UNIX) as real:
                    real.bind(path); os.chmod(path, 0o777 if fault=='ownership' else 0o700)
                    with patch.object(provisioner.socket, 'socket', return_value=SocketSeam()):
                        with self.assertRaises((OSError, ValueError)): provisioner.control(path, {'op':'status'}, attempt)
                self.assertEqual(sends, [], 'U1_ZERO_SEND')
                self.assertEqual(attempt['control_state'], 'NOT_SENT', 'U1_NOT_SENT')

    def test_u3_send_budget_is_absolute_and_output_requires_full_write(self):
        import provisioner
        attempt = {'control_state':'NOT_SENT'}; clock = [100.0]; observed = []
        class SocketSeam:
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def settimeout(self, value): observed.append(value)
            def connect(self, path): pass
            def send(self, data): clock[0] += .1; return 1
        with tempfile.TemporaryDirectory(dir=SCRATCH, prefix='u-budget-') as tmp:
            path = str(pathlib.Path(tmp)/'s')
            with socket.socket(socket.AF_UNIX) as real:
                real.bind(path); os.chmod(path, 0o700)
                with patch.object(provisioner.socket, 'socket', return_value=SocketSeam()), patch.object(provisioner.time, 'monotonic', side_effect=lambda:clock[0]):
                    with self.assertRaisesRegex(ValueError, 'deadline'): provisioner.control(path, {'op':'status'}, attempt, 110)
            self.assertEqual(len(observed), 4, 'U3_SEND_ABSOLUTE_CALLS')
            self.assertAlmostEqual(observed[-1], .05, msg='U3_SEND_REMAINING')
        read_fd, write_fd = os.pipe()
        class Output:
            def fileno(self): return write_fd
        try:
            clock[0] = 100; writes = []
            def partial(fd, raw):
                writes.append(len(raw)); clock[0] += .1; return 1
            with patch.object(provisioner.sys, 'stdout', Output()), patch.object(provisioner.os, 'write', side_effect=partial), patch.object(provisioner.time, 'monotonic', side_effect=lambda:clock[0]):
                self.assertIs(provisioner.write_output({'ok':True}, 110), False, 'U3_OUTPUT_COMPLETE_REQUIRED')
            self.assertEqual(len(writes), 3, 'U3_OUTPUT_SINGLE_BUDGET')
        finally: os.close(read_fd); os.close(write_fd)

    def test_u4_matching_wrong_peer_identity_never_becomes_expected(self):
        config = grant()
        for key in public(config):
            wrong = snapshot(config, **{key: config[key]+1 if type(config[key]) is int else 'other'})
            def respond(conn, request, index, stop): conn.sendall(json.dumps(wrong).encode()+b'\n')
            with self.subTest(key=key), peer(respond) as (path, seen, _):
                self.failure(cli(path, {'operation':'configure','expected':public(config)}, 'reconcile'), 'UNKNOWN')


if __name__ == '__main__': unittest.main()
