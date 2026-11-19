import pathlib
import socket
import sys
import unittest
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from gate import EXACT_TOOLS
from worker import Session
import json
import os
import select
import subprocess
import tempfile
import time

POOL = pathlib.Path(__file__).resolve().parents[1]


def _private_command(directory, name, command, timeout=1.5):
    path = pathlib.Path(directory) / name
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.settimeout(timeout)
    try:
        sock.connect(str(path))
        sock.sendall(command.encode('ascii'))
        return sock.recv(32).decode('ascii')
    finally:
        sock.close()


def rescue_fixture(directory):
    """Use only live same-run handles/private channels; never a PID authority."""
    for name, command in (
            ('supervisor-rescue.sock', 'kill-owned'),
            ('fixture-rescue.sock', 'shutdown'),
            ('worker-rescue.sock', 'cleanup')):
        try:
            _private_command(directory, name, command)
        except (FileNotFoundError, ConnectionRefusedError, TimeoutError, OSError):
            pass

class Wire:
    def __init__(self, directory, lifetime='20', mode='normal'):
        self.directory = pathlib.Path(directory)
        env = dict(os.environ, PYTHONPATH=str(POOL) + os.pathsep + str(POOL / 'tests'),
                   RPC_TEST_DIR=directory, POOL_MAX_LIFETIME_SECONDS=lifetime, RPC_FACTORY_MODE=mode,
                   RPC_RESCUE_SOCKET=str(pathlib.Path(directory) / 'supervisor-rescue.sock'))
        self.proc = subprocess.Popen([sys.executable, str(POOL / 'tests/rpc_agent_fixture.py')],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, env=env)
        self.buf = b''

    def send(self, method, params, rid=1):
        self.proc.stdin.write((json.dumps({'jsonrpc':'2.0', 'id':rid,
                             'method':method, 'params':params}) + '\n').encode())
        self.proc.stdin.flush()

    def read(self, timeout=3):
        end = time.monotonic() + timeout
        while b'\n' not in self.buf:
            ready, _, _ = select.select([self.proc.stdout], [], [], max(0, end-time.monotonic()))
            if not ready:
                raise AssertionError('RPC response timed out')
            data = os.read(self.proc.stdout.fileno(), 65536)
            if not data:
                raise AssertionError('RPC EOF: ' + self.proc.stderr.read().decode())
            self.buf += data
        line, self.buf = self.buf.split(b'\n', 1)
        return json.loads(line)

    def close(self):
        rescue_fixture(self.directory)
        if not self.proc.stdin.closed:
            self.proc.stdin.close()
        try:
            self.proc.wait(timeout=3)
        finally:
            if self.proc.poll() is None:
                self.proc.kill()
                self.proc.wait()
            self.proc.stdout.close()
            self.proc.stderr.close()

class SubprocessTests(unittest.TestCase):
    def test_rescue_ignores_reaped_reused_and_unknown_pid_records(self):
        import unittest.mock
        with tempfile.TemporaryDirectory() as directory, \
                unittest.mock.patch('os.kill', side_effect=AssertionError('signal attempted')), \
                unittest.mock.patch('os.killpg', side_effect=AssertionError('group signal attempted')):
            pathlib.Path(directory, 'pids').write_text(json.dumps([1, os.getpid(), 999999999]))
            rescue_fixture(directory)

    def test_initialization_gate_failure_never_admits_prompt(self):
        for mode in ('badtools', 'exception'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                wire = Wire(directory, mode=mode)
                try:
                    wire.send('session.create', {})
                    response = wire.read()
                    self.assertIn('error', response)
                    self.assertNotIn('SECRET', json.dumps(response))
                    wire.send('health', {})
                    self.assertFalse(wire.read()['result']['ready'])
                    wire.send('prompt.submit', {'session_id':'fake', 'text':'hello'})
                    self.assertIn('error', wire.read())
                    self.assertFalse((pathlib.Path(directory) / 'calls').exists())
                finally:
                    wire.close()

    def test_health_and_eof_remain_live_during_initialization(self):
        with tempfile.TemporaryDirectory() as directory:
            wire = Wire(directory, mode='block')
            try:
                wire.send('session.create', {}, 'create')
                path = pathlib.Path(directory) / 'initialized'
                end = time.monotonic() + 2
                while not path.exists() and time.monotonic() < end:
                    time.sleep(.01)
                self.assertTrue(path.exists())
                pid = int(path.read_text())
                wire.send('health', {}, 'health')
                self.assertEqual(wire.read(), {'jsonrpc':'2.0','id':'health','result':{'alive':True,'ready':False}})
                wire.proc.stdin.close()
                wire.proc.wait(timeout=2)
                with self.assertRaises(ProcessLookupError):
                    os.kill(pid, 0)
            finally:
                wire.close()

    def test_entry_does_not_depend_on_pythonpath_or_cwd(self):
        with tempfile.TemporaryDirectory() as directory:
            env = dict(os.environ, RPC_TEST_DIR=directory, POOL_MAX_LIFETIME_SECONDS='5')
            env.pop('PYTHONPATH', None)
            code = ('import sys; sys.path[:0]=' + repr([str(POOL), str(POOL / 'tests')]) +
                    '; from worker import serve; serve(factory_ref="rpc_agent_fixture:factory")')
            proc = subprocess.Popen([sys.executable, '-c', code], stdin=subprocess.PIPE,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, cwd=directory)
            try:
                request = json.dumps({'jsonrpc':'2.0', 'id':1, 'method':'session.create', 'params':{}})+'\n'
                proc.stdin.write(request.encode())
                proc.stdin.flush()
                self.assertTrue(select.select([proc.stdout], [], [], 3)[0])
                line = proc.stdout.readline()
                self.assertTrue(line, 'worker exited without create receipt')
                result = json.loads(line)
                self.assertIn('session_id', result.get('result', {}))
            finally:
                proc.stdin.close()
                proc.wait(timeout=3)
                proc.stdout.close()
                proc.stderr.close()

    def test_interrupt_eof_close_and_deadline_reap_process_group(self):
        for action in ('session.interrupt', 'session.close', 'eof', 'deadline'):
            with self.subTest(action=action), tempfile.TemporaryDirectory() as directory:
                wire = Wire(directory, lifetime='1' if action == 'deadline' else '20')
                try:
                    wire.send('session.create', {})
                    sid = wire.read()['result']['session_id']
                    wire.send('prompt.submit', {'session_id':sid, 'text':'block'})
                    self.assertEqual(wire.read()['result']['status'], 'accepted')
                    path = pathlib.Path(directory) / 'pids'
                    end = time.monotonic() + 2
                    while not path.exists() and time.monotonic() < end:
                        time.sleep(.01)
                    self.assertTrue(path.exists(), 'fixture must create child before cleanup')
                    pids = json.loads(path.read_text())
                    for pid in pids:
                        os.kill(pid, 0)
                    wire.send('health', {}, 2)
                    self.assertEqual(wire.read()['result'], {'alive':True, 'ready':False})
                    start = time.monotonic()
                    if action == 'eof':
                        wire.proc.stdin.close()
                        wire.proc.wait(timeout=2)
                    elif action == 'deadline':
                        self.assertEqual(wire.read()['params']['payload']['status'], 'interrupted')
                        wire.proc.wait(timeout=2)
                    else:
                        wire.send(action, {'session_id':sid}, 3)
                        self.assertEqual(wire.read()['result']['status'],
                                         'interrupted' if action.endswith('interrupt') else 'closed')
                        event = wire.read()
                        self.assertEqual(event['params']['payload'], {'status':'interrupted', 'text':''})
                    self.assertLess(time.monotonic() - start, 2)
                    for pid in pids:
                        end = time.monotonic() + 2
                        while time.monotonic() < end:
                            state = subprocess.run(['ps','-o','stat=','-p',str(pid)], capture_output=True, text=True).stdout.strip()
                            if not state:
                                break
                            time.sleep(.02)
                        self.assertEqual(state, '', 'owned process remains: ' + str(pid))
                finally:
                    wire.close()

    def test_single_submission_retries_are_errors_not_cached_success(self):
        with tempfile.TemporaryDirectory() as directory:
            wire = Wire(directory)
            try:
                wire.send('session.create', {})
                sid = wire.read()['result']['session_id']
                params = {'session_id':sid, 'text':'block'}
                wire.send('prompt.submit', params, 'same')
                self.assertEqual(wire.read()['result']['status'], 'accepted')
                for rid in ('same', 'different'):
                    wire.send('prompt.submit', params, rid)
                    response = wire.read()
                    self.assertEqual(response['id'], rid)
                    self.assertIn('error', response)
                path = pathlib.Path(directory) / 'calls'
                end = time.monotonic()+2
                while not path.exists() and time.monotonic()<end:
                    time.sleep(.01)
                self.assertEqual(path.read_text().splitlines(), ['block'])
                wire.send('session.interrupt', {'session_id':sid})
                wire.read()
                wire.read()
                wire.send('prompt.submit', params, 'same')
                self.assertIn('error', wire.read())
            finally:
                wire.close()

    def test_result_error_interrupted_and_exception_are_sanitized(self):
        for text, status in [('error','error'), ('interrupted','interrupted'), ('exception','error')]:
            with self.subTest(text=text), tempfile.TemporaryDirectory() as directory:
                wire = Wire(directory)
                try:
                    wire.send('session.create', {})
                    sid = wire.read()['result']['session_id']
                    wire.send('prompt.submit', {'session_id':sid, 'text':text})
                    self.assertEqual(wire.read()['result']['status'], 'accepted')
                    event = wire.read()
                    self.assertEqual(event['params']['payload'], {'status':status, 'text':''})
                    self.assertNotIn('SECRET', json.dumps(event))
                    wire.proc.stdin.close()
                    wire.proc.wait(timeout=2)
                    self.assertEqual(wire.proc.stderr.read(), b'')
                finally:
                    wire.close()

    def test_closed_fields_unknown_method_and_oversize_prompt(self):
        with tempfile.TemporaryDirectory() as directory:
            wire = Wire(directory)
            try:
                for params in ({'cwd':'/state'}, {'cwd':'/workspace/'}, {'cwd':None}, {'model':'evil'}, {'expiry':9999999999}):
                    wire.send('session.create', params)
                    self.assertIn('error', wire.read())
                self.assertFalse((pathlib.Path(directory) / 'initialized').exists())
                wire.send('session.resume', {'session_id':'unknown'}, 'unknown')
                self.assertEqual(wire.read()['error']['code'], -32601)
                wire.send('session.create', {})
                sid = wire.read()['result']['session_id']
                for params in ({'session_id':sid, 'text':'x'*65537},
                               {'session_id':sid, 'text':'x', 'timeout':9999},
                               {'session_id':'wrong', 'text':'x'}):
                    wire.send('prompt.submit', params)
                    self.assertIn('error', wire.read())
                self.assertFalse((pathlib.Path(directory) / 'calls').exists())
                wire.send('prompt.submit', {'session_id':sid, 'text':'hello'})
                self.assertEqual(wire.read()['result']['status'], 'accepted')
                self.assertEqual(wire.read()['params']['payload']['status'], 'complete')
                wire.send('session.create', {})
                self.assertIn('error', wire.read())
            finally:
                wire.close()

    def test_oversize_unterminated_frame_kills_running_agent(self):
        with tempfile.TemporaryDirectory() as directory:
            wire = Wire(directory)
            try:
                wire.send('session.create', {})
                sid = wire.read()['result']['session_id']
                wire.send('prompt.submit', {'session_id':sid, 'text':'block'})
                wire.read()
                path = pathlib.Path(directory) / 'initialized'
                pid = int(path.read_text())
                wire.proc.stdin.write(b'x' * 1048576)
                wire.proc.stdin.flush()
                wire.proc.wait(timeout=2)
                with self.assertRaises(ProcessLookupError):
                    os.kill(pid, 0)
            finally:
                wire.close()

    def test_deadline_survives_stopped_child_and_full_prompt_pipe(self):
        import signal
        with tempfile.TemporaryDirectory() as directory:
            wire = Wire(directory, lifetime='1')
            try:
                wire.send('session.create', {})
                sid = wire.read()['result']['session_id']
                pid = int((pathlib.Path(directory) / 'initialized').read_text())
                os.kill(pid, signal.SIGSTOP)
                wire.send('prompt.submit', {'session_id':sid, 'text':'界'*65536})
                self.assertEqual(wire.read()['result']['status'], 'accepted')
                wire.send('health', {}, 'health')
                self.assertEqual(wire.read(timeout=2)['id'], 'health')
                self.assertEqual(wire.read(timeout=2)['params']['payload']['status'], 'interrupted')
                wire.proc.wait(timeout=2)
                with self.assertRaises(ProcessLookupError):
                    os.kill(pid, 0)
            finally:
                # Rescue is through the held same-run owner, never the PID file.
                rescue_fixture(directory)
                wire.close()

    def test_java_subset_create_ack_and_terminal_event(self):
        with tempfile.TemporaryDirectory() as directory:
            wire = Wire(directory)
            try:
                wire.send('health', {})
                self.assertFalse(wire.read()['result']['ready'])
                self.assertFalse((pathlib.Path(directory) / 'initialized').exists())
                wire.send('session.create', {'cwd':'/workspace'}, 'create')
                created = wire.read()['result']
                self.assertIsNone(created['stored_session_id'])
                sid = created['session_id']
                wire.send('prompt.submit', {'session_id':sid, 'text':'hello'}, 'submit')
                self.assertEqual(wire.read(), {'jsonrpc':'2.0', 'id':'submit', 'result':{'status':'accepted'}})
                self.assertEqual(wire.read(), {'jsonrpc':'2.0', 'method':'event', 'params':{
                    'session_id':sid, 'type':'message.complete', 'payload':{'status':'complete','text':'reply'}, 'seq':1}})
            finally:
                wire.close()

class Agent:
    tools = [{'type':'function', 'function':{'name': n}} for n in EXACT_TOOLS]
    valid_tool_names = EXACT_TOOLS
    def run_conversation(self, text):
        raise AssertionError('submit must not execute on the reader')
    def interrupt(self):
        pass

class AdmissionTests(unittest.TestCase):
    def test_malformed_result_flags_never_promote_success(self):
        from worker import terminal_payload
        for value in (False, 1, 'true', None):
            self.assertEqual(terminal_payload({'completed':value, 'final_response':'reply'}),
                             {'status':'error','text':''})
        for field in ('failed', 'partial'):
            for value in (0, '', [], 'false'):
                with self.subTest(field=field, value=value):
                    result = {'completed':True, 'final_response':'reply', field:value}
                    self.assertEqual(terminal_payload(result), {'status':'error','text':''})

    def test_trusted_lifetime_is_bounded_and_finite(self):
        from unittest.mock import patch
        from worker import lifetime_seconds, Denied
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(lifetime_seconds(), 300)
        for value in ('0', '301', '-1', 'inf', 'nan', '1.5', 'true'):
            with self.subTest(value=value), patch.dict(os.environ, {'POOL_MAX_LIFETIME_SECONDS':value}):
                with self.assertRaises(Denied):
                    lifetime_seconds()

    def test_submit_acknowledges_without_running_on_reader(self):
        session = Session(Agent, 'test')
        created = session.handle('session.create', {})
        ack = session.handle('prompt.submit', {'session_id':created['session_id'], 'text':'hello'})
        self.assertEqual(ack, {'status':'accepted'})
