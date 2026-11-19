"""Strict HTTP chunked-framing boundaries against a real loopback edge process.

Only owned loopback sockets. No model, provider, credential or Docker use.
"""
import http.server
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

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from test_protocol import grant

SCRATCH = os.environ.get('WORKER_TEST_SCRATCH', '/tmp')
MCP_BODY = json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'tools/list', 'params': {}}).encode()


class Upstream(http.server.BaseHTTPRequestHandler):
    seen = None

    def do_POST(self):
        self.__class__.seen.append(self.rfile.read(int(self.headers['Content-Length'])))
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', '2')
        self.end_headers()
        self.wfile.write(b'{}')

    def log_message(self, *args):
        pass


def head(*headers):
    lines = [b'POST /mcp HTTP/1.1', b'Host: localhost',
             b'Content-Type: application/json', b'Accept: application/json, text/event-stream',
             b'Connection: close']
    lines.extend(headers)
    return b'\r\n'.join(lines) + b'\r\n\r\n'


def chunk(size_line, payload, terminator=b'0\r\n\r\n'):
    return size_line + b'\r\n' + payload + b'\r\n' + terminator


class EdgeFramingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.upstream = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
        cls.upstream_thread = threading.Thread(target=cls.upstream.serve_forever, daemon=True)
        cls.upstream_thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.upstream.shutdown()
        cls.upstream.server_close()
        cls.upstream_thread.join()

    def setUp(self):
        Upstream.seen = []
        self.tmp = tempfile.TemporaryDirectory(dir=SCRATCH)
        self.control = str(pathlib.Path(self.tmp.name) / 'control.sock')
        self.proc = subprocess.Popen(
            [sys.executable, '-u', str(ROOT / 'edge.py'), '--role', 'mcp',
             '--listen', '127.0.0.1', '--port', '0', '--control', self.control,
             '--target', f'http://127.0.0.1:{self.upstream.server_port}/mcp',
             '--local-test', '--model', 'fixture'],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self.port = json.loads(self.proc.stdout.readline())['port']
            configured = subprocess.run(
                [sys.executable, str(ROOT / 'provisioner.py'), 'control',
                 '--socket', self.control, '--role', 'mcp'],
                input=json.dumps({'op': 'configure', 'config': grant()}),
                text=True, capture_output=True, timeout=5)
            self.assertEqual(configured.returncode, 0, configured.stdout + configured.stderr)
        except BaseException:
            self.tearDown()
            raise

    def tearDown(self):
        self.proc.terminate()
        try:
            self.proc.wait(timeout=5)
        finally:
            self.proc.stdout.close()
            self.proc.stderr.close()
            self.tmp.cleanup()

    def exchange(self, payload, timeout=10):
        sock = socket.create_connection(('127.0.0.1', self.port), timeout=timeout)
        try:
            sock.sendall(payload)
            raw = b''
            while b'\r\n\r\n' not in raw:
                part = sock.recv(4096)
                if not part:
                    break
                raw += part
            return int(raw.split(b' ', 2)[1]) if raw.startswith(b'HTTP/') else None
        finally:
            sock.close()

    def assert_rejected(self, payload, message):
        status = self.exchange(payload)
        self.assertEqual(status, 403, message)
        self.assertEqual(Upstream.seen, [], message + ': upstream must not be reached')

    # --- chunk-size grammar: 1*HEXDIG only -------------------------------------------
    def test_non_hex_chunk_sizes_are_rejected(self):
        """Each spelling is numerically the payload length, so only the grammar can reject it."""
        size = len(MCP_BODY)
        hexed = b'%x' % size
        underscored = hexed[:-1] + b'_' + hexed[-1:]
        for size_line in (b'+%x' % size, b'0x%x' % size, b'0X%x' % size, underscored,
                          b' %x' % size, b'%x ' % size, b'%x\t' % size):
            with self.subTest(size_line=size_line):
                self.assert_rejected(
                    head(b'Transfer-Encoding: chunked')
                    + chunk(size_line, MCP_BODY, b'0\r\n\r\n'),
                    'chunk size must be 1*HEXDIG')

    def test_oversized_chunk_size_is_rejected(self):
        self.assert_rejected(head(b'Transfer-Encoding: chunked')
                             + chunk(b'f' * 40, MCP_BODY),
                             'a chunk size wider than the frame budget is refused')

    def test_empty_chunk_size_line_is_rejected(self):
        self.assert_rejected(head(b'Transfer-Encoding: chunked') + b'\r\n' + MCP_BODY,
                             'an empty chunk size is not a zero chunk')

    # --- chunk extensions and framing still legal ------------------------------------
    def test_chunk_extension_and_trailer_are_accepted(self):
        payload = head(b'Transfer-Encoding: chunked') + chunk(
            b'%x;name=value' % len(MCP_BODY), MCP_BODY, b'0\r\nX-Checksum: abc\r\n\r\n')
        self.assertEqual(self.exchange(payload), 200)
        self.assertEqual(Upstream.seen, [MCP_BODY])

    def test_content_length_request_is_unchanged(self):
        payload = head(b'Content-Length: %d' % len(MCP_BODY)) + MCP_BODY
        self.assertEqual(self.exchange(payload), 200)
        self.assertEqual(Upstream.seen, [MCP_BODY])

    # --- trailer bounds --------------------------------------------------------------
    def test_trailer_count_is_bounded(self):
        """A complete valid body plus too many trailer lines must still be refused."""
        trailers = b''.join(b'X-%d: v\r\n' % i for i in range(64))
        self.assert_rejected(head(b'Transfer-Encoding: chunked')
                             + chunk(b'%x' % len(MCP_BODY), MCP_BODY,
                                     b'0\r\n' + trailers + b'\r\n'),
                             'trailer line count must be bounded')

    def test_trailer_bytes_are_bounded(self):
        trailers = b''.join(b'X-%d: %s\r\n' % (i, b'v' * 3000) for i in range(8))
        self.assert_rejected(head(b'Transfer-Encoding: chunked')
                             + chunk(b'%x' % len(MCP_BODY), MCP_BODY,
                                     b'0\r\n' + trailers + b'\r\n'),
                             'trailer byte total must be bounded')

    def test_trailer_without_colon_is_rejected(self):
        self.assert_rejected(head(b'Transfer-Encoding: chunked')
                             + chunk(b'%x' % len(MCP_BODY), MCP_BODY,
                                     b'0\r\nnot-a-header\r\n\r\n'),
                             'malformed trailer refused')

    # --- ambiguous / truncated framing ----------------------------------------------
    def test_transfer_encoding_with_content_length_is_rejected(self):
        self.assert_rejected(head(b'Transfer-Encoding: chunked',
                                  b'Content-Length: %d' % len(MCP_BODY)) + MCP_BODY,
                             'TE plus CL is ambiguous smuggling framing')

    def test_duplicate_content_length_is_rejected(self):
        self.assert_rejected(head(b'Content-Length: 2', b'Content-Length: 2') + b'{}',
                             'duplicate Content-Length is ambiguous')

    def test_truncated_chunk_is_rejected(self):
        status = self.exchange(head(b'Transfer-Encoding: chunked') + b'8\r\nabc')
        self.assertIn(status, (None, 403), 'truncated chunk must never be forwarded')
        self.assertEqual(Upstream.seen, [])

    # --- shared absolute request deadline --------------------------------------------
    def test_slow_drip_body_hits_the_absolute_deadline(self):
        sock = socket.create_connection(('127.0.0.1', self.port), timeout=15)
        started = time.monotonic()
        try:
            sock.sendall(head(b'Transfer-Encoding: chunked') + b'4\r\n')
            for byte in b'abcd':
                time.sleep(1.5)
                sock.sendall(bytes([byte]))
            raw = b''
            while b'\r\n\r\n' not in raw:
                part = sock.recv(4096)
                if not part:
                    break
                raw += part
            status = int(raw.split(b' ', 2)[1]) if raw.startswith(b'HTTP/') else None
        finally:
            sock.close()
        elapsed = time.monotonic() - started
        self.assertEqual(status, 403, 'a drip slower than the request deadline must be refused')
        self.assertLess(elapsed, 8.5, 'the refusal must come from the absolute deadline, not idle')
        self.assertEqual(Upstream.seen, [])


if __name__ == '__main__':
    unittest.main()
