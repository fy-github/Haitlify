"""Bounded edge gates. Only owned loopback/Unix sockets, no external services."""
import contextlib
import copy
import http.client
import json
import pathlib
import socket
import socketserver
import sys
import tempfile
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
from unittest.mock import call, patch

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import edge
from protocol import Lease
from test_protocol import grant
from test_control_atomic import public, wire

SCRATCH = '/Users/lify/.hermes/cache/scratch'


def wait_for(predicate, timeout=3):
    end = time.monotonic() + timeout
    while not predicate():
        if time.monotonic() >= end:
            raise AssertionError('barrier condition timed out')
        time.sleep(.005)


@contextlib.contextmanager
def running(role='mcp', target_port=1, accept_gate=None):
    lease = Lease()
    servers = {}
    errors = []
    original_serve = socketserver.BaseServer.serve_forever
    original_accept = socketserver.BaseServer._handle_request_noblock
    def serve(server, *args, **kwargs):
        name = 'control' if server.address_family == socket.AF_UNIX else 'data'
        servers[name] = server
        return original_serve(server, poll_interval=.01)
    def accept(server):
        if accept_gate is not None and server.address_family == socket.AF_UNIX:
            accept_gate[0].set()
            if not accept_gate[1].wait(8):
                raise AssertionError('accept barrier timed out')
        return original_accept(server)
    with tempfile.TemporaryDirectory(prefix='be-', dir=SCRATCH) as tmp:
        path = str(pathlib.Path(tmp)/'s')
        key = pathlib.Path(tmp)/'key'; key.write_text('LOCAL-FIXTURE-KEY')
        suffix = '/mcp' if role == 'mcp' else '/v1/chat/completions'
        args = SimpleNamespace(role=role, target=f'http://127.0.0.1:{target_port}{suffix}',
            local_test=True, listen='127.0.0.1', port=0, model='fixture', control=path, key_file=str(key))
        def run():
            try: edge.serve(args)
            except BaseException as error: errors.append(error)
        with patch.object(edge, 'Lease', return_value=lease), patch.object(edge, 'print', create=True), \
                patch.object(socketserver.BaseServer, 'serve_forever', serve), \
                patch.object(socketserver.BaseServer, '_handle_request_noblock', accept):
            thread = threading.Thread(target=run); thread.start()
            try:
                wait_for(lambda: len(servers) == 2 or errors)
                if errors and sys.exc_info()[0] is None: raise errors[0]
                yield SimpleNamespace(lease=lease, control=servers['control'], data=servers['data'], path=path)
            finally:
                if accept_gate: accept_gate[1].set()
                if 'data' in servers: servers['data'].shutdown()
                thread.join(8)
                if thread.is_alive(): raise AssertionError('owned edge did not stop')
                if errors and sys.exc_info()[0] is None: raise errors[0]


@contextlib.contextmanager
def upstream_barrier():
    entered = threading.Event(); release = threading.Event(); seen = []
    class Handler(edge.BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_POST(self):
            seen.append((self.path, self.rfile.read(int(self.headers['Content-Length']))))
            entered.set()
            if not release.wait(6): raise AssertionError('upstream barrier timed out')
            self.send_response(200); self.send_header('Content-Length','2'); self.end_headers(); self.wfile.write(b'{}')
    server = edge.HTTPServer(('127.0.0.1',0), Handler)
    thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval':.01}); thread.start()
    try: yield server, entered, release, seen
    finally: release.set(); server.shutdown(); server.server_close(); thread.join(3)


def data_frame(role='mcp', keepalive=False):
    if role == 'mcp': body = {'jsonrpc':'2.0','method':'tools/list','id':1}
    else:
        from gate import EXACT_TOOLS
        body = {'model':'fixture','messages':[], 'tools':[{'type':'function','function':{'name':n}} for n in EXACT_TOOLS]}
    raw = json.dumps(body).encode()
    path = '/mcp' if role == 'mcp' else '/v1/chat/completions'
    return (f'POST {path} HTTP/1.1\r\nHost: localhost\r\nContent-Length: {len(raw)}\r\nConnection: '
            + ('keep-alive' if keepalive else 'close') + '\r\n\r\n').encode()+raw


class BoundedEdgeTests(unittest.TestCase):
    def shutdown_close_probe(self, *, listener_fault=False, fallback_fault=False,
                             listener_fallback_fault=False, error_type: type[Exception] = OSError):
        """Read real owned FDs before rescue; keep all eight workers occupied."""
        release = threading.Event(); accepted = []; clients = []; invoked = []
        fault_enabled = threading.Event()
        class CloseFault:
            def __init__(self, sock): self.sock = sock
            def __getattr__(self, name): return getattr(self.sock, name)
            def close(self):
                fails = listener_fallback_fault if self.sock is listener else fallback_fault
                if fails and fault_enabled.is_set():
                    raise error_type('private fallback failure')
                self.sock.close()
        class Hold(socketserver.BaseRequestHandler):
            def handle(self):
                invoked.append(self.request)
                release.wait(5)
        class Tracking(edge.ControlServer):
            def get_request(self):
                request, address = super().get_request()
                accepted.append(request)
                # Exactly one queued socket has a failing direct close.
                return (CloseFault(request) if len(accepted) == 9 else request), address
        with tempfile.TemporaryDirectory(prefix='close-', dir=SCRATCH) as tmp:
            server = Tracking(str(pathlib.Path(tmp)/'s'), Hold)
            listener = server.socket
            thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval':.01})
            thread.start()
            def snapshot():
                return dict(listener_fd=listener.fileno(), queue=server.pending.qsize(),
                    unfinished=server.pending.unfinished_tasks, active=server.active,
                    live_workers=sum(w.is_alive() for w in server.workers),
                    owned_open=sum(s.fileno() >= 0 for s in accepted))
            observed = {}
            try:
                for i in range(16):
                    client = socket.socket(socket.AF_UNIX); client.settimeout(2)
                    clients.append(client); client.connect(server.server_address)
                    wait_for(lambda: len(accepted) >= i+1)
                    if i < 8: wait_for(lambda: len(invoked) >= i+1)
                wait_for(lambda: server.pending.qsize() == 8)
                self.assertEqual(snapshot(), dict(listener_fd=listener.fileno(), queue=8,
                    unfinished=16, active=8, live_workers=8, owned_open=16), 'F1 full 8 active / 8 queued fixture')
                server.shutdown(); thread.join(2)
                self.assertFalse(thread.is_alive())
                fault_enabled.set()
                started = time.monotonic()
                with contextlib.ExitStack() as faults:
                    joins = [faults.enter_context(patch.object(worker, 'join', wraps=worker.join))
                             for worker in server.workers]
                    faults.enter_context(patch.object(server, 'close_request',
                        side_effect=error_type('private queued close failure')))
                    if listener_fault:
                        faults.enter_context(patch.object(socketserver.TCPServer, 'server_close',
                            side_effect=error_type('private listener close failure')))
                        server.socket = CloseFault(listener)
                    try: server.server_close()
                    except Exception as error:
                        observed['error'] = (type(error).__name__, str(error))
                    else: observed['error'] = None
                observed['join_calls'] = [spy.call_args_list == [call(timeout=.1)] for spy in joins]
                observed['elapsed'] = time.monotonic()-started
                observed['held'] = snapshot()
                observed['diagnostic'] = getattr(server, 'last_error', None)
                fault_enabled.clear(); server.socket = listener
                release.set()
                for worker in server.workers: worker.join(2)
                observed['before_rescue'] = snapshot()
            finally:
                fault_enabled.clear(); server.socket = listener; release.set()
                if thread.is_alive(): server.shutdown(); thread.join(2)
                # Rescue is independent of the implementation under test.
                server.stopping.set()
                try: server.server_close()
                except Exception: pass
                while True:
                    try: request, _ = server.pending.get_nowait()
                    except edge.queue.Empty: break
                    try: request.close()
                    finally: server.pending.task_done()
                for request in accepted: request.close()
                for client in clients: client.close()
                listener.close()
                for worker in server.workers: worker.join(2)
                observed['after_rescue'] = snapshot()
                print('F1_RESOURCE_READBACK ' + json.dumps(observed, sort_keys=True))
            self.assertEqual(observed['after_rescue'], dict(listener_fd=-1, queue=0,
                unfinished=0, active=0, live_workers=0, owned_open=0), 'F1 owned rescue complete')
            return observed

    def test_c8_shutdown_queued_close_failure_drains_every_owned_socket(self):
        observed = self.shutdown_close_probe()
        self.assertEqual(observed['before_rescue'], dict(listener_fd=-1, queue=0,
            unfinished=0, active=0, live_workers=0, owned_open=0), 'F1 queued close failure must not leak FD or task')
        self.assertIsNone(observed['error'], 'F1 successful fallback completes shutdown')
        self.assertEqual(observed['diagnostic'], 'connection failed', 'F1 fixed close diagnostic')
        self.assertEqual(observed['held'], dict(listener_fd=-1, queue=0,
            unfinished=8, active=8, live_workers=8, owned_open=8), 'F1 drain finishes without waiting for active handlers')
        self.assertLess(observed['elapsed'], 2, 'F1 shutdown joins remain bounded')
        self.assertEqual(observed['join_calls'], [True]*8, 'F1 each original worker receives a bounded join')

    def test_c8_shutdown_uncloseable_queue_item_reports_failure_after_drain(self):
        observed = self.shutdown_close_probe(fallback_fault=True, error_type=RuntimeError)
        self.assertEqual(observed['before_rescue'], dict(listener_fd=-1, queue=0,
            unfinished=0, active=0, live_workers=0, owned_open=1), 'F1 uncloseable item must not strand other queued resources')
        self.assertEqual(observed['error'], ('Rejected', 'connection cleanup failed'), 'F1 uncloseable resource cannot report safe completion')
        self.assertEqual(observed['diagnostic'], 'connection failed', 'F1 failed fallback diagnostic is fixed')
        self.assertLess(observed['elapsed'], 2, 'F1 failed fallback joins remain bounded')

    def test_c8_shutdown_listener_close_failure_still_drains_queue(self):
        observed = self.shutdown_close_probe(listener_fault=True, error_type=RuntimeError)
        self.assertEqual(observed['before_rescue'], dict(listener_fd=-1, queue=0,
            unfinished=0, active=0, live_workers=0, owned_open=0), 'F1 listener close failure must not bypass queue cleanup')
        self.assertIsNone(observed['error'], 'F1 listener fallback closes real descriptor')
        self.assertEqual(observed['diagnostic'], 'connection failed', 'F1 listener diagnostic is fixed')
        self.assertLess(observed['elapsed'], 2, 'F1 listener fallback joins remain bounded')

    def test_c8_shutdown_uncloseable_listener_reports_failure_after_drain(self):
        observed = self.shutdown_close_probe(listener_fault=True, listener_fallback_fault=True,
                                             error_type=RuntimeError)
        self.assertGreaterEqual(observed['before_rescue']['listener_fd'], 0, 'F1 failed listener close is not fabricated as closed')
        self.assertEqual({k:v for k,v in observed['before_rescue'].items() if k != 'listener_fd'},
            dict(queue=0, unfinished=0, active=0, live_workers=0, owned_open=0),
            'F1 listener fallback failure must not strand remaining resources')
        self.assertEqual(observed['error'], ('Rejected', 'connection cleanup failed'), 'F1 listener close failure cannot report safe completion')
        self.assertEqual(observed['diagnostic'], 'connection failed', 'F1 listener fallback failure diagnostic is fixed')
        self.assertEqual(observed['join_calls'], [True]*8, 'F1 failures cannot skip bounded joins')

    def test_c1_six_connections_before_accept_and_backlog(self):
        entered, release = threading.Event(), threading.Event()
        clients = []
        with running(accept_gate=(entered, release)) as env:
            config = grant(); env.lease.configure(config)
            expected = dict(public(config), ok=True, role='mcp', ready=True, revoked=False, spent=False)
            try:
                def connect(_):
                    sock = socket.socket(socket.AF_UNIX); sock.settimeout(2)
                    try: sock.connect(env.path)
                    except OSError as error: sock.close(); return error
                    return sock
                with ThreadPoolExecutor(max_workers=6) as pool:
                    outcomes = list(pool.map(connect, range(6)))
                clients.extend(item for item in outcomes if isinstance(item, socket.socket))
                self.assertEqual(len(clients), 6, 'C1 six connects must succeed without retry')
                self.assertTrue(entered.wait(1))
                self.assertEqual(env.control.request_queue_size, 32, 'C1 listen backlog')
                release.set()
                for sock in clients: sock.sendall(b'{"op":"status"}\n')
                for sock in clients:
                    with sock.makefile('rb') as stream:
                        self.assertEqual(json.loads(stream.readline()), expected)
            finally:
                release.set()
                for sock in clients: sock.close()

    def test_c2_control_capacity_rejection_and_recovery(self):
        release = threading.Event(); clients = []; accepted = []; invoked = []
        original = socketserver.BaseServer.finish_request
        original_get = socketserver.UnixStreamServer.get_request
        def tracked_get(server):
            result = original_get(server); accepted.append(result[0]); return result
        def blocked(server, request, address):
            invoked.append(threading.get_ident())
            if not release.wait(4): raise AssertionError('worker barrier expired')
            return original(server, request, address)
        with running() as env:
            env.lease.configure(grant()); before = copy.deepcopy((env.lease.config, env.lease.spent, env.lease.status()))
            with patch.object(env.control, 'finish_request', lambda r, a: blocked(env.control, r, a)), \
                    patch.object(socketserver.UnixStreamServer, 'get_request', tracked_get):
                try:
                    for i in range(16):
                        sock = socket.socket(socket.AF_UNIX); sock.settimeout(2); sock.connect(env.path)
                        sock.sendall(b'{"op":"status"}\n'); clients.append(sock)
                        wait_for(lambda: len(accepted) >= i+1)
                        if i < 8: wait_for(lambda: len(invoked) >= i+1)
                    extra = socket.socket(socket.AF_UNIX); extra.settimeout(.5); clients.append(extra)
                    with patch.object(env.control, 'close_request', side_effect=OSError('fixture close failure')):
                        extra.connect(env.path)
                        wait_for(lambda: len(accepted) == 17)
                        try: closed = extra.recv(1) == b''
                        except ConnectionResetError: closed = True
                        except socket.timeout: closed = False
                    self.assertTrue(closed, 'C2 full queue must close excess connection immediately')
                    self.assertEqual(len(set(invoked)), 8, 'C2 fixed worker high water')
                    self.assertEqual(env.control.pending.qsize(), 8)
                    self.assertEqual(env.control.active, 8)
                    self.assertEqual(len(env.control.workers), 8)
                    self.assertLessEqual(sum(sock.fileno() >= 0 for sock in accepted), 16, 'C2 owned FD bound')
                    self.assertEqual((env.lease.config, env.lease.spent, env.lease.status()), before)
                finally:
                    release.set()
                    for sock in clients: sock.close()
            wait_for(lambda: getattr(env.control, 'active', 0) == 0)
            self.assertTrue(wire(env.path, {'op':'status'})['ready'])

    def test_c3_absolute_frame_deadline_slow_drip(self):
        with running() as env:
            env.lease.configure(grant()); before = copy.deepcopy(env.lease.config)
            with socket.socket(socket.AF_UNIX) as sock:
                sock.settimeout(.1); sock.connect(env.path)
                start = time.monotonic(); ended = False
                while time.monotonic() - start < 1.4:
                    try:
                        sock.sendall(b' ')
                        if sock.recv(4096) is not None: ended = True; break
                    except socket.timeout: pass
                    except OSError: ended = True; break
                self.assertTrue(ended, 'C3 slow drip must finish within absolute frame budget')
                self.assertLess(time.monotonic()-start, 1.4)
            self.assertEqual(env.lease.config, before)
            self.assertTrue(wire(env.path, {'op':'status'})['ready'])

    def test_c4_lock_contention_returns_without_mutating(self):
        with running() as env:
            config = grant(); env.lease.configure(config)
            before = copy.deepcopy((env.lease.config, env.lease.spent))
            requests = [{'op':'status'}, {'op':'configure','role':'mcp','config':config},
                dict(op='revoke',role='mcp',incarnation=config['incarnation'],expected=public(config))]
            clients = []
            with env.lease.lock:
                try:
                    start = time.monotonic()
                    for request in requests:
                        sock = socket.socket(socket.AF_UNIX); sock.settimeout(1.5); sock.connect(env.path)
                        sock.sendall(json.dumps(request).encode()+b'\n'); clients.append(sock)
                    for sock in clients:
                        try: response = sock.recv(4096)
                        except socket.timeout: response = b''
                        self.assertEqual(response, b'{"ok": false, "ready": false}\n', 'C4 bounded lock rejection')
                    self.assertLess(time.monotonic()-start, 1.5)
                    self.assertEqual((env.lease.config, env.lease.spent), before)
                finally:
                    for sock in clients: sock.close()
            self.assertEqual((env.lease.config, env.lease.spent), before)

    def test_c4_lock_acquired_after_deadline_never_commits(self):
        with running() as env:
            real_lock = env.lease.lock; entered = threading.Event(); release = threading.Event()
            class DelayedAcquire:
                def acquire(self, **kwargs):
                    entered.set(); release.wait(5); return real_lock.acquire(**kwargs)
                def release(self): real_lock.release()
                def __enter__(self): return real_lock.__enter__()
                def __exit__(self, *args): return real_lock.__exit__(*args)
            env.lease.lock = DelayedAcquire()
            config = grant()
            with socket.socket(socket.AF_UNIX) as sock:
                sock.settimeout(4); sock.connect(env.path)
                sock.sendall(json.dumps(dict(op='configure',role='mcp',config=config)).encode()+b'\n')
                try:
                    self.assertTrue(entered.wait(1)); time.sleep(3.05)
                finally: release.set()
                try: sock.recv(4096)
                except OSError: pass
            wait_for(lambda: env.control.active == 0)
            self.assertIsNone(env.lease.config, 'C4 post-acquire deadline must prohibit late commit')
            self.assertFalse(env.lease.spent)

    def test_c5_partial_ack_deadline_preserves_commit_and_releases_lock(self):
        entered = threading.Event(); sends = []; original_get = socketserver.UnixStreamServer.get_request
        with running() as env:
            class SlowAck:
                def __init__(self, sock): self.sock = sock
                def __getattr__(self, key): return getattr(self.sock, key)
                def sendall(self, value):
                    entered.set(); time.sleep(.8)
                def send(self, value):
                    entered.set(); sends.append(time.monotonic()); time.sleep(.06); return 1
            def get(server):
                sock, address = original_get(server); return SlowAck(sock), address
            with patch.object(socketserver.UnixStreamServer, 'get_request', get):
                with socket.socket(socket.AF_UNIX) as sock:
                    sock.connect(env.path); config = grant(); start = time.monotonic()
                    sock.sendall(json.dumps(dict(op='configure',role='mcp',config=config)).encode()+b'\n')
                    self.assertTrue(entered.wait(1))
                    acquired = env.lease.lock.acquire(timeout=.1)
                    self.assertTrue(acquired, 'C5 ACK must not retain lease lock')
                    if acquired: env.lease.lock.release()
                    self.assertEqual(env.lease.config, config, 'C5 commit survives lost ACK')
                    time.sleep(.4)
                    self.assertEqual(env.control.active, 0, 'C5 absolute ACK deadline releases worker')
                    self.assertLess(time.monotonic()-start, .65)
            self.assertGreater(len(sends), 1, 'C5 exercises partial writes')
            self.assertTrue(wire(env.path, {'op':'status'})['ready'])

    def test_c7_data_capacity_control_pool_independent(self):
        clients = []; invoked = []; release = threading.Event()
        with upstream_barrier() as (up, up_entered, up_release, seen), running(target_port=up.server_port) as env:
            env.lease.configure(grant()); before = copy.deepcopy((env.lease.config, env.lease.spent))
            original = env.data.finish_request
            def blocked(request, address):
                invoked.append(threading.get_ident()); release.wait(4); return original(request, address)
            with patch.object(env.data, 'finish_request', blocked):
                try:
                    for i in range(8):
                        sock = socket.create_connection(env.data.server_address, timeout=2)
                        sock.sendall(data_frame()); clients.append(sock)
                        if i < 4: wait_for(lambda: len(invoked) >= i+1)
                    time.sleep(.05)
                    extra = socket.create_connection(env.data.server_address, timeout=.5); clients.append(extra)
                    try: extra.sendall(data_frame())
                    except OSError: pass
                    try: closed = extra.recv(1) == b''
                    except ConnectionResetError: closed = True
                    except socket.timeout: closed = False
                    self.assertTrue(closed, 'C7 data queue full closes excess without forwarding')
                    self.assertEqual(env.data.request_queue_size, 16)
                    self.assertEqual(env.data.active, 4)
                    self.assertEqual(env.data.pending.qsize(), 4)
                    self.assertEqual(len(set(invoked)), 4)
                    self.assertEqual(len(env.data.workers), 4)
                    self.assertTrue(wire(env.path, {'op':'status'})['ready'])
                    self.assertEqual((env.lease.config, env.lease.spent), before)
                    self.assertFalse(set(env.data.workers) & set(env.control.workers))
                    self.assertEqual(seen, [], 'C7 refused work has no upstream side effect')
                finally:
                    up_release.set(); release.set()
                    for sock in clients:
                        try:
                            while sock.recv(4096): pass
                        except OSError: pass
                        sock.close()
            wait_for(lambda: getattr(env.data, 'active', 0) == 0)
            self.assertEqual(len(seen), 8, 'C7 only eight admitted requests reach real upstream')

    def test_c9_data_queue_age_checked_only_when_worker_available(self):
        clients = []; entered = []; release = threading.Event()
        with running() as env:
            original = env.data.finish_request
            def blocked(request, address):
                entered.append(request); release.wait(5); return original(request, address)
            with patch.object(env.data, 'finish_request', blocked):
                try:
                    for i in range(8):
                        sock = socket.create_connection(env.data.server_address, timeout=2)
                        sock.sendall(b'GET / HTTP/1.0\r\n\r\n'); clients.append(sock)
                        if i < 4: wait_for(lambda: len(entered) >= i+1)
                    wait_for(lambda: env.data.pending.qsize() == 4)
                    time.sleep(3.1)
                    self.assertEqual(env.data.pending.qsize(), 4, 'C9 does not promise bounded queue residence')
                    release.set()
                    wait_for(lambda: env.data.pending.unfinished_tasks == 0)
                    self.assertEqual(len(entered), 4, 'C9 expired queue entries never invoke handler')
                finally:
                    release.set()
                    for sock in clients: sock.close()

    def test_c8_connection_failure_and_close_failure_restore_capacity(self):
        with running() as env:
            for server in (env.control, env.data):
                with self.subTest(plane=server.address_family):
                    original_close = server.close_request; original_finish = server.finish_request
                    owned = []; calls = []; workers = tuple(server.workers)
                    def broken_finish(request, address):
                        owned.append(request); raise RuntimeError('fixture handler failure')
                    def broken_close(request):
                        calls.append(request); raise OSError('fixture close failure')
                    with patch.object(server, 'finish_request', broken_finish), patch.object(server, 'close_request', broken_close):
                        for _ in range(server.worker_count):
                            sock = socket.socket(server.address_family); sock.settimeout(2)
                            sock.connect(server.server_address); sock.close()
                        wait_for(lambda: len(calls) == server.worker_count)
                        wait_for(lambda: server.pending.unfinished_tasks == 0)
                    try:
                        self.assertEqual(server.active, 0, 'C8 active accounting restored after exception')
                        self.assertEqual(getattr(server, 'last_error', None), 'connection failed', 'C8 fixed local diagnostic')
                        self.assertTrue(all(w.is_alive() for w in workers), 'C8 original workers survive')
                        self.assertTrue(all(s.fileno() == -1 for s in owned), 'C8 failed close still closes owned socket')
                        self.assertEqual(tuple(server.workers), workers)
                        if server is env.control:
                            self.assertTrue(wire(env.path, {'op':'status'})['ok'])
                        else:
                            conn = http.client.HTTPConnection(*env.data.server_address, timeout=2)
                            conn.request('GET','/'); response = conn.getresponse()
                            self.assertEqual(response.status,403); response.read(); conn.close()
                    finally:
                        for request in owned: request.close()

    def test_c6_upstream_lock_revoke_rejects_then_fences_both_roles(self):
        for role in ('mcp','provider'):
            with self.subTest(role=role), upstream_barrier() as (up, entered, release, seen), running(role, up.server_port) as env:
                config = grant(); env.lease.configure(config)
                revoke = dict(op='revoke',role=role,incarnation=config['incarnation'],expected=public(config))
                sock = socket.create_connection(env.data.server_address, timeout=3)
                try:
                    sock.sendall(data_frame(role)); self.assertTrue(entered.wait(2))
                    start = time.monotonic()
                    self.assertEqual(wire(env.path, revoke), {'ok':False,'ready':False}, 'C6 forwarding lock must not be bypassed')
                    self.assertLess(time.monotonic()-start, 1.5)
                    self.assertFalse(env.lease.spent); self.assertEqual(env.lease.config, config)
                    from test_control_atomic import cli
                    uncertain = cli(env.path, {k:v for k,v in revoke.items() if k != 'role'}, role)
                    self.assertEqual(uncertain.returncode, 1, 'C6 CLI cannot claim successful revoke')
                    self.assertEqual(json.loads(uncertain.stdout)['control_state'], 'UNKNOWN', 'C6 CLI reports uncertain mutation')
                    self.assertNotIn(config['capability'], uncertain.stdout+uncertain.stderr)
                    release.set()
                    while sock.recv(4096): pass
                    result = wire(env.path, revoke)
                    self.assertEqual(result, dict(public(config),ok=True,role=role,ready=False,revoked=True,spent=True))
                    with socket.create_connection(env.data.server_address, timeout=2) as later:
                        later.sendall(data_frame(role)); response = b''
                        while True:
                            chunk = later.recv(4096)
                            if not chunk: break
                            response += chunk
                        self.assertIn(b'403', response)
                    self.assertEqual(len(seen), 1, 'C6 successful revoke fences later upstream requests')
                finally: release.set(); sock.close()

    def test_c9_pipeline_keepalive_still_single_http10_request(self):
        with patch.object(edge.BaseHTTPRequestHandler, 'protocol_version', 'HTTP/1.1'), upstream_barrier() as (up, entered, release, seen), running(target_port=up.server_port) as env:
            env.lease.configure(grant()); release.set()
            with socket.create_connection(env.data.server_address, timeout=2) as sock:
                sock.sendall(data_frame(keepalive=True)*2)
                response = b''; closed = True
                while True:
                    try: chunk = sock.recv(4096)
                    except socket.timeout: closed = False; break
                    if not chunk: break
                    response += chunk
                self.assertTrue(closed, 'C9 single request connection must close')
                self.assertTrue(response.startswith(b'HTTP/1.0 200'), 'C9 frozen HTTP/1.0 response')
                self.assertEqual(response.count(b'HTTP/1.'),1)
            self.assertEqual(len(seen),1,'C9 pipeline never forwards second request')

    def test_c3_queue_wait_consumes_frame_budget(self):
        release = threading.Event(); entered = []; clients = []
        with running() as env:
            original = env.control.finish_request
            def blocked(request, address):
                entered.append(request)
                if len(entered) <= 8: release.wait(3)
                return original(request, address)
            with patch.object(env.control, 'finish_request', blocked):
                try:
                    for i in range(8):
                        sock = socket.socket(socket.AF_UNIX); sock.settimeout(2); sock.connect(env.path)
                        sock.sendall(b'{"op":"status"}\n'); clients.append(sock)
                        wait_for(lambda: len(entered) >= i+1)
                    with socket.socket(socket.AF_UNIX) as queued:
                        queued.settimeout(2); queued.connect(env.path)
                        queued.sendall(json.dumps(dict(op='configure',role='mcp',config=grant())).encode()+b'\n')
                        wait_for(lambda: env.control.pending.qsize() == 1)
                        time.sleep(1.1); release.set()
                        self.assertEqual(json.loads(queued.recv(4096)), {'ok':False,'ready':False}, 'C3 queued frame retains accept deadline')
                    self.assertIsNone(env.lease.config)
                finally:
                    release.set()
                    for sock in clients: sock.close()

    def test_c8_malformed_frames_and_http_do_not_drain_pool(self):
        with running() as env:
            env.lease.configure(grant()); before = copy.deepcopy((env.lease.config,env.lease.spent))
            workers = tuple(env.control.workers+env.data.workers)
            for raw in (b'not-json\n', b'{"op":"status","op":"revoke"}\n', b'['*2000+b']'*2000+b'\n', b'x'*(edge.MAX+1), b'{"op":'):
                with socket.socket(socket.AF_UNIX) as sock:
                    sock.settimeout(2); sock.connect(env.path)
                    try: sock.sendall(raw); sock.shutdown(socket.SHUT_WR); response = sock.recv(4096)
                    except OSError: response = b''
                    self.assertNotIn(b'"ok": true', response)
                self.assertTrue(wire(env.path,{'op':'status'})['ready'])
            for raw in (b'INVALID HTTP REQUEST\r\n\r\n', b'POST /mcp HTTP/1.0\r\nContent-Length: 4000\r\n\r\n'+b'['*2000+b']'*2000):
                with socket.create_connection(env.data.server_address, timeout=2) as sock:
                    sock.sendall(raw); sock.shutdown(socket.SHUT_WR)
                    try:
                        while sock.recv(4096): pass
                    except OSError: pass
                wait_for(lambda: env.data.pending.unfinished_tasks == 0)
            self.assertEqual((env.lease.config,env.lease.spent),before)
            self.assertTrue(all(worker.is_alive() for worker in workers))
            wait_for(lambda: env.control.pending.unfinished_tasks == env.data.pending.unfinished_tasks == 0)
            self.assertEqual(env.control.active+env.data.active,0)

    def test_c3_accept_timestamp_precedes_dispatch_delay(self):
        entered = threading.Event(); release = threading.Event()
        with running() as env:
            def verify(request, address):
                entered.set(); release.wait(3); return True
            with patch.object(env.control, 'verify_request', verify):
                with socket.socket(socket.AF_UNIX) as sock:
                    sock.settimeout(2); sock.connect(env.path)
                    sock.sendall(json.dumps(dict(op='configure',role='mcp',config=grant())).encode()+b'\n')
                    try:
                        self.assertTrue(entered.wait(1)); time.sleep(1.1)
                    finally: release.set()
                    self.assertEqual(json.loads(sock.recv(4096)), {'ok':False,'ready':False}, 'C3 budget begins at accept, not dispatch')
            self.assertIsNone(env.lease.config)

    def assert_c8_parse_rejection(self, raw):
        """Exercise the real handler and read back lease/capacity after rejection."""
        with running() as env:
            env.lease.configure(grant())
            before = copy.deepcopy((env.lease.config, env.lease.spent))
            workers = tuple(env.control.workers)
            with socket.socket(socket.AF_UNIX) as sock:
                sock.settimeout(2); sock.connect(env.path)
                sock.sendall(raw + b'\n')
                self.assertEqual(sock.recv(4096), b'{"ok": false, "ready": false}\n',
                                 'C8 recursion is a safe rejection')
            wait_for(lambda: env.control.pending.unfinished_tasks == 0)
            self.assertEqual((env.lease.config, env.lease.spent), before)
            recovery = wire(env.path, {'op':'status'})
            self.assertTrue(recovery['ok'])
            self.assertTrue(recovery['ready'])
            wait_for(lambda: env.control.pending.unfinished_tasks == 0)
            self.assertEqual((env.control.active, env.control.pending.qsize()), (0, 0))
            self.assertEqual(tuple(env.control.workers), workers)
            self.assertTrue(all(worker.is_alive() for worker in workers))
            self.assertEqual((env.lease.config, env.lease.spent), before)

    def test_c8_deep_json_returns_safe_rejection(self):
        # Keep the real 2000-level input. Python 3.9 raises RecursionError;
        # 3.14 returns a list, rejected later by Control's message.get path.
        # This is a safety/recovery test, not an arbitrary-depth CPU bound.
        self.assert_c8_parse_rejection(b'['*2000+b']'*2000)

    def test_c8_recursion_error_returns_safe_rejection(self):
        raw = b'{"op":"status","recursion_fixture":true}'
        original = edge.json.loads
        observed = []
        def exhausted_parser(data, *args, **kwargs):
            if data == raw + b'\n':
                observed.append(sorted(kwargs))
                raise RecursionError('TEST-CAPABILITY-NOT-A-REAL-TOKEN')
            return original(data, *args, **kwargs)
        # Inject only the parser failure: strict_json, Control.handle, UDS I/O,
        # fixed worker accounting and the recovery status handler are real.
        with patch.object(edge.json, 'loads', exhausted_parser):
            self.assert_c8_parse_rejection(raw)
        self.assertEqual(observed, [['object_pairs_hook', 'parse_constant']])

    def test_c3_client_close_before_accept_is_not_rollback(self):
        entered = threading.Event(); release = threading.Event()
        with running(accept_gate=(entered, release)) as env:
            config = grant()
            with socket.socket(socket.AF_UNIX) as sock:
                sock.connect(env.path)
                sock.sendall(json.dumps(dict(op='configure',role='mcp',config=config)).encode()+b'\n')
                self.assertTrue(entered.wait(1))
            release.set()
            end = time.monotonic()+1
            while env.lease.config is None and time.monotonic()<end: time.sleep(.01)
            self.assertEqual(env.lease.config, config, 'C3 pending accepted bytes may commit after client closes')

    def test_c8_pool_invariant_failure_closes_listener(self):
        with tempfile.TemporaryDirectory(prefix='fatal-', dir=SCRATCH) as tmp:
            class Fatal(socketserver.BaseRequestHandler):
                def handle(self): raise SystemExit('owned fixture fatal')
            server = edge.ControlServer(str(pathlib.Path(tmp)/'s'), Fatal)
            errors = []
            def loop():
                try: server.serve_forever(poll_interval=.01)
                except Exception as error: errors.append(type(error).__name__)
            thread = threading.Thread(target=loop)
            with patch.object(threading, 'excepthook', lambda args: None):
                thread.start()
                try:
                    with socket.socket(socket.AF_UNIX) as sock: sock.connect(server.server_address)
                    end = time.monotonic()+.5
                    while thread.is_alive() and time.monotonic()<end: time.sleep(.01)
                    self.assertFalse(thread.is_alive(), 'C8 pool invariant loss fails service closed')
                    self.assertEqual(server.socket.fileno(), -1, 'C8 failed pool listener closed')
                    self.assertEqual(errors, ['Rejected'])
                finally:
                    if thread.is_alive(): server.shutdown()
                    server.server_close(); thread.join(2)

    def test_c8_handler_exception_keeps_same_fixed_workers(self):
        with running() as env:
            for server in (env.control, env.data):
                original = tuple(server.workers); entered = threading.Event()
                def fail(request, address): entered.set(); raise RuntimeError('private exception text')
                with patch.object(server, 'finish_request', fail):
                    with socket.socket(server.address_family) as sock:
                        sock.connect(server.server_address)
                        self.assertTrue(entered.wait(1))
                        time.sleep(.1)
                self.assertTrue(all(w.is_alive() for w in original), 'C8 per-connection isolation preserves every worker')
                self.assertEqual(server.pending.unfinished_tasks, 0, 'C8 task accounting completes')
                self.assertEqual(server.active, 0)
                self.assertEqual(tuple(server.workers), original)

    def test_c3_parser_delay_never_starts_late_mutation(self):
        original = edge.strict_json
        with running() as env:
            def delayed(raw):
                message = original(raw); time.sleep(3.05); return message
            with patch.object(edge, 'strict_json', delayed):
                with socket.socket(socket.AF_UNIX) as sock:
                    sock.settimeout(4); sock.connect(env.path)
                    sock.sendall(json.dumps(dict(op='configure', role='mcp',config=grant())).encode()+b'\n')
                    self.assertEqual(sock.recv(4096), b'', 'C3 no response after absolute server budget')
            self.assertIsNone(env.lease.config, 'C3 parsing cannot authorize a late mutation')

    def test_import_keeps_shared_strict_json_and_has_no_io(self):
        import subprocess
        script = """import sys,socket,threading
from unittest.mock import patch
sys.path.insert(0, sys.argv[1])
with patch.object(socket,'socket',side_effect=AssertionError('socket on import')), patch.object(threading.Thread,'start',side_effect=AssertionError('thread on import')):
 import edge
 assert edge.strict_json('{"ok":true}') == {'ok':True}
 for raw in ('{"x":1,"x":2}', '{"x":NaN}'):
  try: edge.strict_json(raw)
  except edge.Rejected: pass
  else: raise AssertionError('strict parser changed')
"""
        result = subprocess.run([sys.executable,'-B','-c',script,str(ROOT)],capture_output=True,text=True,timeout=3)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(result.stdout,'')

    def test_c8_failed_control_pool_closes_both_planes(self):
        with patch.object(threading, 'excepthook', lambda args: None):
            with self.assertRaisesRegex(edge.Rejected, 'worker pool failed'):
                with running() as env:
                    def fatal(request, address): raise SystemExit('fixture fatal')
                    with patch.object(env.control, 'finish_request', fatal):
                        with socket.socket(socket.AF_UNIX) as sock: sock.connect(env.path)
                        end = time.monotonic()+1
                        while env.data.socket.fileno() != -1 and time.monotonic()<end: time.sleep(.01)
                        self.assertEqual(env.data.socket.fileno(), -1, 'C8 failed control pool must close Data too')


if __name__ == '__main__': unittest.main()
