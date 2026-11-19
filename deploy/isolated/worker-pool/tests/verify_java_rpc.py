#!/usr/bin/env python3
"""Compile current real Java callers; loopback TCP->stdio is TEST ONLY.

No production bridge, model, credentials, Docker, or database. Requires JDK 21+
and the listed existing local Maven jars; never downloads dependencies.
Adapted from the independent review's seven-scenario harness.
"""
import argparse
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
from worker_test_paths import scratch_root

TESTS = pathlib.Path(__file__).resolve().parent
REPO = TESTS.parents[3]
JARS = [
    'tools/jackson/core/jackson-core/3.1.4/jackson-core-3.1.4.jar',
    'tools/jackson/core/jackson-databind/3.1.4/jackson-databind-3.1.4.jar',
    'com/fasterxml/jackson/core/jackson-annotations/2.21/jackson-annotations-2.21.jar',
    'org/slf4j/slf4j-api/2.0.18/slf4j-api-2.0.18.jar',
    'org/springframework/spring-beans/7.0.8/spring-beans-7.0.8.jar',
    'org/springframework/spring-context/7.0.8/spring-context-7.0.8.jar',
    'org/springframework/boot/spring-boot-autoconfigure/4.1.0/spring-boot-autoconfigure-4.1.0.jar',
]
MODES = ('hello', 'error', 'failed', 'partial', 'exception', 'interrupt', 'cwd')


def scenario(mode, root, cp):
    directory = root / mode
    directory.mkdir()
    env = {'PATH': '/usr/bin:/bin', 'PYTHONPATH': os.pathsep.join((str(TESTS.parent), str(TESTS))),
           'PYTHONDONTWRITEBYTECODE': '1', 'PROBE_DIR': str(directory),
           'POOL_MAX_LIFETIME_SECONDS': '8', 'HOME': str(directory), 'TMPDIR': str(root)}
    proc = subprocess.Popen([sys.executable, '-c',
        'from worker import serve; serve("java_probe_agent:factory")'], env=env, cwd=directory,
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    listener = socket.socket()
    listener.bind(('127.0.0.1', 0))
    listener.listen(1)
    listener.settimeout(12)
    frames, errors = [], []

    def bridge():
        try:
            conn, _ = listener.accept()
            conn.settimeout(10)
            def forward():
                try:
                    while True:
                        data = conn.recv(65536)
                        if not data:
                            break
                        proc.stdin.write(data)
                        proc.stdin.flush()
                except (OSError, ValueError) as exc:
                    errors.append(repr(exc))
                finally:
                    proc.stdin.close()
            thread = threading.Thread(target=forward, daemon=True)
            thread.start()
            try:
                while True:
                    data = proc.stdout.readline()
                    if not data:
                        break
                    frames.append(json.loads(data))
                    conn.sendall(data)
            finally:
                thread.join(2)
                conn.close()
        except Exception as exc:
            errors.append(repr(exc))

    bridge_thread = threading.Thread(target=bridge, daemon=True)
    bridge_thread.start()
    try:
        java = subprocess.run(['java', '-Xmx64m', '-cp', cp, 'JavaWorkerProbe',
            str(listener.getsockname()[1]), mode], capture_output=True, text=True, timeout=12)
        bridge_thread.join(3)
        proc.wait(timeout=3)
        assert not bridge_thread.is_alive(), 'test bridge did not stop'
        assert not errors, errors
        assert java.returncode == 0, (java.stdout, java.stderr)
        assert proc.returncode == 0, proc.returncode
        assert proc.stderr.read() == b'', 'worker diagnostics leaked'
        assert 'SECRET' not in json.dumps(frames), frames
        calls = directory / 'calls'
        if mode == 'cwd':
            assert not calls.exists()
            assert len(frames) == 1 and 'error' in frames[0]
        else:
            assert [json.loads(x) for x in calls.read_text().splitlines()] == [
                'block' if mode == 'interrupt' else mode]
            assert frames[1]['result'] == {'status': 'accepted'}, frames
            events = [f for f in frames if f.get('method') == 'event']
            assert len(events) == 1, events
            expected = 'complete' if mode == 'hello' else 'interrupted' if mode == 'interrupt' else 'error'
            assert events[0]['params']['payload'] == {
                'status': expected, 'text': 'verified reply' if mode == 'hello' else ''}, events
        ids = [int((directory / 'leader').read_text())] if (directory / 'leader').exists() else []
        if (directory / 'pids').exists():
            ids = json.loads((directory / 'pids').read_text())
        states = {}
        until = time.monotonic() + 2
        while time.monotonic() < until:
            states = {pid: subprocess.run(['ps', '-o', 'pid=,ppid=,pgid=,stat=', '-p', str(pid)],
                capture_output=True, text=True).stdout.strip() for pid in ids}
            if not any(states.values()):
                break
            time.sleep(.02)
        assert not any(states.values()), states
        return {'mode': mode, 'exit_code': java.returncode, 'stdout': java.stdout,
                'wire_frames': frames, 'pid_readback': states}
    finally:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
        leader = directory / 'leader'
        if leader.exists():
            try:
                os.killpg(int(leader.read_text()), signal.SIGKILL)
            except ProcessLookupError:
                pass
        listener.close()
        bridge_thread.join(2)
        for stream in (proc.stdin, proc.stdout, proc.stderr):
            stream.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--m2', type=pathlib.Path, default=pathlib.Path.home() / '.m2/repository',
                        help='local Maven cache (operator-default home); override for CI')
    parser.add_argument('--scratch', type=pathlib.Path, default=scratch_root())
    args = parser.parse_args()
    args.scratch = args.scratch.expanduser().resolve()
    args.scratch.mkdir(parents=True, exist_ok=True)
    jars = [args.m2 / name for name in JARS]
    missing = [str(p) for p in jars if not p.is_file()]
    if missing:
        raise SystemExit('Missing local jars (no download attempted): ' + repr(missing))
    cp = os.pathsep.join(map(str, jars))
    with tempfile.TemporaryDirectory(prefix='java-worker-rpc-', dir=args.scratch) as tmp:
        root = pathlib.Path(tmp)
        classes = root / 'classes'
        classes.mkdir()
        source = REPO / 'server/platform/src/main/java/com/haitlify/aitest/platform/assistant'
        sources = [source / name for name in ('hermes/HermesGateway.java',
                   'hermes/HermesTcpGateway.java', 'runtime/HermesAssistantRuntime.java')]
        subprocess.run(['javac', '-cp', cp, '-d', str(classes), *map(str, sources),
                        str(TESTS / 'JavaWorkerProbe.java')], check=True, timeout=30)
        results = []
        for mode in MODES:
            result = scenario(mode, root, str(classes) + os.pathsep + cp)
            results.append(result)
            print(json.dumps(result), flush=True)
        assert len(results) == 7
        print('PASS: 7 real Java caller / local agent-fixture scenarios (not model acceptance)')


if __name__ == '__main__':
    main()
