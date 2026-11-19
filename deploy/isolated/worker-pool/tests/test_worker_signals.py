"""Real POSIX supervisor/descendant cleanup, no model or external network."""
import json
import os
import pathlib
import signal
import subprocess
import tempfile
import time
import unittest
from test_worker_rpc import Wire, rescue_fixture
from worker_test_paths import scratch_root

class SignalTests(unittest.TestCase):
    def test_signal_rescue_has_no_historical_pid_group_kill(self):
        import ast
        tree = ast.parse(pathlib.Path(__file__).read_text())
        self.assertFalse(any(isinstance(node, ast.Call)
                             and isinstance(node.func, ast.Attribute)
                             and node.func.attr == 'killpg'
                             for node in ast.walk(tree)))

    def exercise(self, phase, action, mode=None):
        with tempfile.TemporaryDirectory(prefix='worker-signals-',
                dir=scratch_root()) as directory:
            wire = Wire(directory, lifetime='2', mode=mode or ('block' if phase == 'init' else 'normal'))
            pids = []
            try:
                wire.send('session.create', {}, 'create')
                if phase == 'run':
                    sid = wire.read()['result']['session_id']
                    wire.send('prompt.submit', {'session_id': sid, 'text': 'block'})
                    self.assertEqual(wire.read()['result']['status'], 'accepted')
                path = pathlib.Path(directory) / 'pids'
                until = time.monotonic() + 1.5
                while not path.exists() and time.monotonic() < until:
                    time.sleep(.01)
                self.assertTrue(path.exists(), 'both ignoring processes must exist before cancellation')
                pids = json.loads(path.read_text())
                self.assertEqual(len(pids), 2)
                if action != 'self':
                    for pid in pids:
                        os.kill(pid, 0)
                if phase == 'init' and action != 'self':
                    # No session id exists until create succeeds: no new cancel RPC.
                    for method in ('session.close', 'session.interrupt'):
                        wire.send(method, {'session_id': 'not-issued'}, method)
                        response = wire.read()
                        self.assertEqual(response['id'], method)
                        self.assertIn('error', response)
                    wire.send('health', {}, 'health')
                    self.assertFalse(wire.read()['result']['ready'])
                if action == 'eof':
                    wire.proc.stdin.close()
                elif action == 'sigterm':
                    wire.proc.terminate()
                elif action == 'repeated':
                    for _ in range(20):
                        if wire.proc.poll() is not None:
                            break
                        wire.proc.send_signal(signal.SIGTERM)
                        time.sleep(.002)
                wire.proc.wait(timeout=3)
                # Exact PID readback, not just supervisor exit or os.kill success.
                states = {}
                until = time.monotonic() + 2
                while time.monotonic() < until:
                    states = {pid: subprocess.run(
                        ['ps', '-o', 'pid=,ppid=,pgid=,stat=', '-p', str(pid)],
                        capture_output=True, text=True, check=False).stdout.strip() for pid in pids}
                    if not any(states.values()):
                        break
                    time.sleep(.02)
                print(json.dumps({'phase': phase, 'action': action,
                                  'supervisor': wire.proc.returncode, 'pid_readback': states}), flush=True)
                self.assertFalse(any(states.values()), 'owned processes remain: ' + repr(states))
                self.assertEqual(wire.proc.returncode, 0, 'SIGTERM must use controlled cleanup')
            finally:
                # Rescue only through the same-run private fixture channels.  The
                # assertions above run before this cleanup, so a mutant cannot be
                # turned green by the rescue.
                rescue_fixture(directory)
                wire.close()

    def test_sigterm_during_running_turn_reaps_ignoring_group(self):
        self.exercise('run', 'sigterm')

    def test_sigterm_during_initialization_reaps_ignoring_group(self):
        self.exercise('init', 'sigterm')

    def test_repeated_sigterm_does_not_interrupt_cleanup(self):
        for phase in ('init', 'run'):
            with self.subTest(phase=phase):
                self.exercise(phase, 'repeated')

    def test_initialization_eof_cancels_pending_create(self):
        self.exercise('init', 'eof')

    def test_initialization_deadline_reaps_group(self):
        self.exercise('init', 'deadline')

    def test_sigterm_before_child_ownership_is_published(self):
        self.exercise('init', 'self', 'spawn-race')

    def test_sigterm_inside_group_cleanup_is_not_reentrant(self):
        self.exercise('init', 'sigterm', 'cleanup-race')
