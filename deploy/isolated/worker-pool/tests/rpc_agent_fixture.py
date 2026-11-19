"""Local-only, importable subprocess factories. Never a production model."""
import json
import os
import pathlib
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
from gate import EXACT_TOOLS

_REAL_CHILD = None


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


def _listen(path):
    listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    listener.bind(str(path))
    listener.listen(1)
    return listener


def _serve_owner(listener, proc):
    """A test-only owner keeps a live Popen handle for cooperative rescue."""
    connection, _ = listener.accept()
    try:
        command = connection.recv(32)
        if command in (b'cleanup', b'kill-owned'):
            proc.kill()
            proc.wait()
            connection.sendall(b'cleaned')
        else:
            connection.sendall(b'ignored')
    finally:
        connection.close()
        listener.close()


def _owned_grandchild(directory):
    listener = _listen(pathlib.Path(directory) / 'fixture-rescue.sock')
    child = subprocess.Popen([sys.executable, '-c',
        'import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); '
        'print("ready",flush=True); time.sleep(120)'], stdout=subprocess.PIPE)
    assert child.stdout is not None
    print('ready ' + str(child.pid), flush=True)
    _serve_owner(listener, child)


def publish_pid_file(path, content):
    # exists() is the reader's ready signal: never expose a partial PID file.
    fd, name = tempfile.mkstemp(prefix='.' + path.name + '-', dir=path.parent)
    temporary = pathlib.Path(name)
    primary_error = None
    try:
        try:
            stream = os.fdopen(fd, 'w', encoding='utf-8')
        except BaseException as error:
            try:
                os.close(fd)
            except BaseException as close_error:
                raise error from close_error
            raise
        with stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except BaseException as error:
        primary_error = error
        raise
    finally:
        try:
            temporary.unlink(missing_ok=True)
        except BaseException as cleanup_error:
            if primary_error is None:
                raise
            raise primary_error from cleanup_error

def block_group(directory):
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    child = subprocess.Popen([sys.executable, __file__, '--owned-grandchild', str(directory)],
                             stdout=subprocess.PIPE, text=True)
    assert child.stdout is not None
    ready = child.stdout.readline()
    assert ready.startswith('ready ')
    child_pid = int(ready.split()[1])
    child.stdout.close()
    publish_pid_file(directory / 'pids', json.dumps([os.getpid(), child_pid]))
    while True:
        time.sleep(.1)

class FakeAgent:
    tools = [{'type': 'function', 'function': {'name': n}} for n in EXACT_TOOLS]
    valid_tool_names = EXACT_TOOLS

    def run_conversation(self, text):
        directory = pathlib.Path(os.environ['RPC_TEST_DIR'])
        with (directory / 'calls').open('a') as f:
            f.write(text + '\n')
        print('SECRET raw stdout', flush=True)
        os.write(2, b'SECRET raw stderr\n')
        if text == 'block':
            block_group(directory)
        if text == 'exception':
            raise ValueError('SECRET exception token')
        return {'final_response': 'reply', 'completed': text != 'error',
                'failed': text == 'error', 'interrupted': text == 'interrupted',
                'error': 'SECRET provider error' if text == 'error' else None,
                'messages': [{'content': 'SECRET transcript'}]}

    def interrupt(self):
        pass


class RescueChild:
    """Test-only held-owner rescue; no PID-file signal authority."""
    tools = [{'type': 'function', 'function': {'name': n}} for n in EXACT_TOOLS]
    valid_tool_names = EXACT_TOOLS

    def __init__(self, factory_ref):
        self._child = _REAL_CHILD(factory_ref)
        self.proc = self._child.proc
        self.stopped = self._child.stopped
        self.pending = self._child.pending
        self.buffer = self._child.buffer
        path = pathlib.Path(os.environ['RPC_RESCUE_SOCKET'])
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        listener.bind(str(path))
        listener.listen(1)
        self._rescue_path = path
        self._rescue_listener = listener
        self._rescue_stop = threading.Event()
        def serve():
            try:
                listener.settimeout(.1)
                while not self._rescue_stop.is_set():
                    try:
                        connection, _ = listener.accept()
                    except socket.timeout:
                        continue
                    try:
                        if connection.recv(32) in (b'cleanup', b'kill-owned'):
                            if self.proc.poll() is None:
                                os.killpg(self.proc.pid, signal.SIGKILL)
                            self.proc.wait()
                            connection.sendall(b'cleaned')
                            return
                    finally:
                        connection.close()
            except OSError:
                return
            finally:
                listener.close()
                path.unlink(missing_ok=True)
        threading.Thread(target=serve, daemon=True).start()

    def interrupt(self):
        if self.stopped:
            return
        self.stopped = True
        try:
            self._child.interrupt()
        finally:
            self._rescue_stop.set()
            self._rescue_listener.close()
            self._rescue_path.unlink(missing_ok=True)

def factory():
    directory = pathlib.Path(os.environ['RPC_TEST_DIR'])
    publish_pid_file(directory / 'initialized', str(os.getpid()))
    mode = os.environ.get('RPC_FACTORY_MODE')
    if mode in ('block', 'spawn-race', 'cleanup-race'):
        block_group(directory)
    if mode == 'exception':
        raise ValueError('SECRET initialization exception')
    if mode == 'badtools':
        agent = FakeAgent()
        agent.tools = []
        return agent
    return FakeAgent()

if __name__ == '__main__':
    if len(sys.argv) >= 2 and sys.argv[1] == '--owned-grandchild':
        _owned_grandchild(sys.argv[2])
        raise SystemExit(0)


    # Test-only deterministic delivery at otherwise narrow ownership races.
    mode = os.environ.get('RPC_FACTORY_MODE')
    if mode == 'spawn-race':
        original_popen = subprocess.Popen
        def popen_with_signal(*args, **kwargs):
            proc = original_popen(*args, **kwargs)
            until = time.monotonic() + 1
            while not (pathlib.Path(os.environ['RPC_TEST_DIR']) / 'pids').exists():
                if time.monotonic() >= until:
                    raise AssertionError('spawn race precondition missing')
                time.sleep(.01)
            os.kill(os.getpid(), signal.SIGTERM)
            return proc
        subprocess.Popen = popen_with_signal
    elif mode == 'cleanup-race':
        original_killpg = os.killpg
        def killpg_with_signal(*args):
            os.kill(os.getpid(), signal.SIGTERM)
            original_killpg(*args)
            os.kill(os.getpid(), signal.SIGTERM)
        os.killpg = killpg_with_signal
    import worker
    _REAL_CHILD = worker.Child
    worker.Child = RescueChild
    from worker import serve
    serve(factory_ref='rpc_agent_fixture:factory')
