#!/usr/bin/env python3
"""Fixed-destination trusted edge. Separate Unix management and HTTP data planes.
Local test mode is loopback only and NOT a sandbox. Production requires host firewall.
No redirects, environmental proxies, caller-selected URLs or forwarded auth headers.
"""
import argparse
import http.client
import ipaddress
import json
import os
import pathlib
import queue
import re
import socketserver
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlsplit
from gate import require_tools
from protocol import Lease, Rejected

MAX = 1024 * 1024
# Shared absolute budget for one request body/framing read. Independent of the socket
# idle timeout, so a sub-idle drip cannot hold a data worker for the server lifetime.
BODY_DEADLINE_SECONDS = 5.0
MAX_CHUNK_LINE = 4096      # one chunk-size line or one trailer line
MAX_TRAILER_LINES = 32     # trailer header lines after the final chunk
MAX_TRAILER_BYTES = 8192   # total trailer bytes after the final chunk
CHUNK_SIZE = re.compile(rb'[0-9A-Fa-f]{1,16}')  # RFC 7230 chunk-size: 1*HEXDIG

def strict_json(raw):
    def pairs(items):
        result = {}
        for k, v in items:
            if k in result: raise Rejected('duplicate key')
            result[k] = v
        return result
    return json.loads(raw, object_pairs_hook=pairs,
                      parse_constant=lambda value: (_ for _ in ()).throw(Rejected('nonfinite')))


def remaining(deadline):
    value = deadline - time.monotonic()
    if value <= 0: raise Rejected('deadline')
    return value


def read_frame(connection, deadline):
    raw = bytearray()
    while len(raw) <= MAX:
        connection.settimeout(remaining(deadline))
        part = connection.recv(min(65536, MAX + 1 - len(raw)))
        if not part: raise Rejected('truncated frame')
        raw.extend(part)
        if b'\n' in part:
            raw = raw[:raw.index(b'\n') + 1]
            if len(raw) > MAX: raise Rejected('frame size')
            return bytes(raw)
        if len(raw) > MAX: raise Rejected('frame size')
    raise Rejected('frame size')


def write_frame(connection, raw, deadline):
    pending = memoryview(raw)
    while pending:
        connection.settimeout(remaining(deadline))
        count = connection.send(pending)
        if count <= 0: raise Rejected('closed writer')
        pending = pending[count:]


def read_deadline(deadline):
    """Remaining budget for the shared request deadline; the only per-read timeout."""
    left = deadline - time.monotonic()
    if left <= 0: raise Rejected('request deadline')
    return left


def read_line(request, limit, deadline, error):
    """One CRLF-terminated line, bounded by `limit` and by the absolute request deadline.

    Reads one buffered byte at a time: `read1(n)` may return a whole coalesced packet, and
    consuming body bytes as part of a line would corrupt the frame. The deadline is
    re-checked between reads, so a sub-idle drip cannot extend the request.
    """
    line = bytearray()
    while not line.endswith(b'\r\n'):
        if len(line) > limit: raise Rejected(error)
        request.connection.settimeout(read_deadline(deadline))
        part = request.rfile.read1(1)
        if not part: raise Rejected(error)
        line += part
    if len(line) > limit + 2: raise Rejected(error)
    return bytes(line[:-2])


def read_exact(request, count, deadline, error):
    """Exactly `count` bytes under the same absolute deadline, one socket read at a time."""
    data = bytearray()
    while len(data) < count:
        request.connection.settimeout(read_deadline(deadline))
        part = request.rfile.read1(count - len(data))
        if not part: raise Rejected(error)
        data.extend(part)
    return bytes(data)

def read_http_body(request, deadline=None):
    """Read one bounded HTTP body under a shared absolute request deadline.

    Content-Length and chunked framing stay supported. Chunk size is exactly 1*HEXDIG
    (no sign, 0x prefix, underscores or padding), and trailers are bounded in both lines
    and bytes, so framing cost cannot outlive MAX or the request deadline.
    """
    if deadline is None:
        deadline = time.monotonic() + BODY_DEADLINE_SECONDS
    transfer = request.headers.get_all('Transfer-Encoding', [])
    lengths = request.headers.get_all('Content-Length', [])
    if len(transfer) > 1 or len(lengths) > 1:
        raise Rejected('ambiguous framing')
    if transfer:
        if transfer[0].strip().lower() != 'chunked' or lengths:
            raise Rejected('unsupported framing')
        raw = bytearray()
        while True:
            line = read_line(request, MAX_CHUNK_LINE, deadline, 'invalid chunk size')
            size_text = line.split(b';', 1)[0]  # chunk-ext is appended after ';'
            if not CHUNK_SIZE.fullmatch(size_text):
                raise Rejected('invalid chunk size')
            size = int(size_text, 16)
            if len(raw) + size > MAX:
                raise Rejected('body size')
            if size == 0:
                # Consume trailers, but do not accept another request in this frame.
                trailer_lines = trailer_bytes = 0
                while True:
                    trailer = read_line(request, MAX_CHUNK_LINE, deadline, 'invalid chunk trailer')
                    if not trailer:
                        return bytes(raw)
                    trailer_lines += 1
                    trailer_bytes += len(trailer) + 2
                    if (trailer_lines > MAX_TRAILER_LINES or trailer_bytes > MAX_TRAILER_BYTES
                            or b':' not in trailer):
                        raise Rejected('invalid chunk trailer')
            raw.extend(read_exact(request, size, deadline, 'truncated chunk'))
            if read_exact(request, 2, deadline, 'truncated chunk') != b'\r\n':
                raise Rejected('truncated chunk')
    if not lengths or not lengths[0].isdigit() or not 0 < int(lengths[0]) <= MAX:
        raise Rejected('body size')
    return read_exact(request, int(lengths[0]), deadline, 'truncated body')


class BoundedDispatch:
    """Fixed workers; queue entries own only a socket and its accept timestamp."""
    worker_count = 8
    queue_size = 8

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.pending = queue.Queue(maxsize=self.queue_size)
        self.active = 0
        self.accounting = threading.Lock()
        self.stopping = threading.Event()
        self.context = threading.local()
        self.workers = [threading.Thread(target=self._work, daemon=True)
                        for _ in range(self.worker_count)]
        for worker in self.workers: worker.start()

    def get_request(self):
        request, address = super().get_request()
        self.context.accepted_at = time.monotonic()
        return request, address

    def process_request(self, request, client_address):
        try:
            self.pending.put_nowait((request, self.context.accepted_at))
        except queue.Full:
            try: self.close_request(request)
            except Exception:
                request.close()
                self.last_error = 'connection failed'

    def _work(self):
        while not self.stopping.is_set():
            try: request, accepted_at = self.pending.get(timeout=.05)
            except queue.Empty: continue
            with self.accounting: self.active += 1
            self.context.accepted_at = accepted_at
            try:
                if time.monotonic() >= accepted_at + 3: continue
                try: address = request.getpeername()
                except OSError: address = None  # Closed peer may still have a complete buffered mutation.
                self.finish_request(request, address)
            except Exception:
                self.last_error = 'connection failed'  # Fixed local diagnostic; never peer content.
            finally:
                try: self.shutdown_request(request)
                except Exception:
                    try: request.close()
                    except Exception: pass
                finally:
                    with self.accounting: self.active -= 1
                    self.pending.task_done()

    def service_actions(self):
        pools = (self,) + getattr(self, 'peer_pools', ())
        if any(not worker.is_alive() for pool in pools for worker in pool.workers):
            self.server_close()
            raise Rejected('worker pool failed')

    def server_close(self):
        close_failed = False
        self.stopping.set()
        try: super().server_close()
        except Exception:
            self.last_error = 'connection failed'
            try: self.socket.close()
            except Exception: close_failed = True
        while True:
            try: request, _ = self.pending.get_nowait()
            except queue.Empty: break
            try: self.close_request(request)
            except Exception:
                self.last_error = 'connection failed'
                try: request.close()
                except Exception: close_failed = True
            finally: self.pending.task_done()
        for worker in self.workers: worker.join(timeout=.1)
        if close_failed: raise Rejected('connection cleanup failed') from None


class ControlServer(BoundedDispatch, socketserver.UnixStreamServer):
    request_queue_size = 32


class DataServer(BoundedDispatch, HTTPServer):
    worker_count = 4
    queue_size = 4
    request_queue_size = 16


def serve(args):
    target = urlsplit(args.target)
    path = '/mcp' if args.role == 'mcp' else '/v1/chat/completions'
    if target.username or target.password or target.query or target.fragment or target.path != path:
        raise Rejected('fixed destination path required')
    if args.local_test:
        if args.listen != '127.0.0.1' or target.hostname != '127.0.0.1' or target.scheme != 'http':
            raise Rejected('test mode is loopback only')
    elif not args.internal_test and target.scheme != 'https':
        raise Rejected('production upstream requires TLS')
    if not target.hostname or not args.model:
        raise Rejected('target and model required')
    provider_key = pathlib.Path(args.key_file).read_text().strip() if args.role == 'provider' else ''
    if args.role == 'provider' and (not provider_key or '\n' in provider_key or '\r' in provider_key):
        raise Rejected('provider key missing')
    lease = Lease()

    class Data(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.0'
        # Idle bound for the request line/headers; the body/framing read below uses one
        # shared ABSOLUTE deadline instead of relying on this idle timeout.
        timeout = BODY_DEADLINE_SECONDS
        def log_message(self, *args): pass
        def reply(self, status, body):
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers(); self.wfile.write(body)
        def do_POST(self):
            deadline = time.monotonic() + BODY_DEADLINE_SECONDS
            try:
                self.connection.settimeout(BODY_DEADLINE_SECONDS)
                if self.path != path:
                    raise Rejected('path or framing')
                body = read_http_body(self, deadline)
                payload = strict_json(body)
                if not isinstance(payload, dict): raise Rejected('object required')
                # Lock spans forwarding: completed revoke receipt fences later upstream sends.
                with lease.lock:
                    config = lease.active()
                    if args.role == 'provider':
                        require_tools(payload.get('tools'))
                        if payload.get('model') != args.model or payload.get('stream', False) is not False:
                            raise Rejected('model or streaming denied')
                        if set(payload) - {'model','messages','tools','tool_choice','temperature','max_tokens','max_completion_tokens','stream','parallel_tool_calls','reasoning_effort'}:
                            raise Rejected('unknown provider field')
                        secret = provider_key
                    else:
                        if payload.get('jsonrpc') != '2.0' or payload.get('method') not in {
                            'initialize','notifications/initialized','ping','tools/list','tools/call'}:
                            raise Rejected('unsupported MCP method')
                        if payload['method'] == 'tools/call':
                            params = payload.get('params', {})
                            if not isinstance(params, dict) or params.get('name') not in {'generation_context','draft_create','draft_get'}:
                                raise Rejected('tool denied')
                        secret = config['capability']
                    connection_type = http.client.HTTPSConnection if target.scheme == 'https' else http.client.HTTPConnection
                    conn = connection_type(target.hostname, target.port, timeout=10)
                    headers = {'Authorization': 'Bearer ' + secret, 'Content-Type':'application/json',
                               'Accept':'application/json, text/event-stream'}
                    for name in ('Mcp-Session-Id', 'MCP-Protocol-Version'):
                        if args.role == 'mcp' and self.headers.get(name): headers[name] = self.headers[name]
                    try:
                        conn.request('POST', path, body, headers)
                        response = conn.getresponse()
                        result = response.read(MAX + 1)
                        if len(result) > MAX or 300 <= response.status < 400:
                            raise Rejected('upstream response refused')
                        self.send_response(response.status)
                        for name in ('Content-Type', 'Mcp-Session-Id', 'MCP-Protocol-Version'):
                            value = response.getheader(name)
                            if value: self.send_header(name, value)
                        self.send_header('Content-Length', str(len(result)))
                        self.send_header('Cache-Control','no-store'); self.end_headers(); self.wfile.write(result)
                    finally:
                        conn.close()
            except (ValueError, TypeError, KeyError, OSError, http.client.HTTPException):
                self.reply(403, b'{"error":"edge denied"}')
        def do_GET(self): self.reply(403, b'{"error":"edge denied"}')
        do_CONNECT = do_PUT = do_DELETE = do_PATCH = do_GET

    class Control(socketserver.StreamRequestHandler):
        def handle(self):
            deadline = self.server.context.accepted_at + 3
            try:
                raw = read_frame(self.connection, min(deadline, self.server.context.accepted_at + 1))
                message = strict_json(raw)
                remaining(deadline)
                op = message.get('op')
                if not lease.lock.acquire(timeout=min(1, remaining(deadline))):
                    raise Rejected('lock deadline')
                try:
                    remaining(deadline)  # Acquiring a lock is not permission for a late commit.
                    if op in ('configure', 'revoke') and (
                            type(message.get('role')) is not str or message['role'] != args.role):
                        raise Rejected('control role mismatch')
                    if op == 'configure' and set(message) == {'op','role','config'}:
                        lease.configure(message['config'])
                    elif op == 'revoke' and set(message) == {'op','role','incarnation','expected'}:
                        expected = message['expected']
                        if (not isinstance(expected, dict) or type(message['incarnation']) is not str
                                or message['incarnation'] != expected.get('incarnation')):
                            raise Rejected('control incarnation mismatch')
                        lease.revoke(expected)
                    elif op == 'status' and set(message) == {'op'}:
                        pass
                    else: raise Rejected('control denied')
                    result = dict(lease.status(), ok=True, role=args.role)
                finally:
                    lease.lock.release()
            except (ValueError, TypeError, AttributeError, OSError, RecursionError):
                result = dict(ok=False, ready=False)
            write_frame(self.connection, json.dumps(result).encode() + b'\n',
                        min(deadline, time.monotonic() + .25))

    # Never unlink an existing socket: that may belong to another live incarnation.
    old_umask = os.umask(0o077)
    try: control = ControlServer(args.control, Control)
    finally: os.umask(old_umask)
    thread = threading.Thread(target=control.serve_forever, daemon=True); thread.start()
    data = DataServer((args.listen, args.port), Data)
    data.peer_pools = (control,)
    print(json.dumps({'port':data.server_port, 'mode':'LOCAL-TEST-NOT-ISOLATED' if args.local_test else 'REQUIRES-HOST-FIREWALL'}), flush=True)
    try: data.serve_forever()
    finally:
        data.server_close(); control.shutdown(); control.server_close(); thread.join()
        pathlib.Path(args.control).unlink(missing_ok=True)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--role', choices=['mcp','provider'], required=True)
    parser.add_argument('--listen', required=True)
    parser.add_argument('--port', type=int, default=8080)
    parser.add_argument('--control', required=True)
    parser.add_argument('--target', required=True)
    parser.add_argument('--key-file', default='')
    parser.add_argument('--model', required=True)
    parser.add_argument('--local-test', action='store_true')
    parser.add_argument('--internal-test', action='store_true',
                        help='test-only: permit HTTP to a peer on the private Docker network')
    serve(parser.parse_args())
