import http.client
import json
import pathlib
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from test_protocol import grant
from gate import EXACT_TOOLS

ROOT = pathlib.Path(__file__).resolve().parents[1]
from worker_test_paths import scratch_root
SCRATCH = scratch_root()
class EdgeTests(unittest.TestCase):
    def test_chunked_mcp_request_is_forwarded_after_configuration(self):
        """The pinned MCP SDK may use chunked Streamable HTTP bodies."""
        seen = []
        class Upstream(BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers['Content-Length']))
                seen.append(json.loads(body))
                self.send_response(200); self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', '2'); self.end_headers(); self.wfile.write(b'{}')
            def log_message(self, *args): pass
        upstream = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
        thread = threading.Thread(target=upstream.serve_forever, daemon=True); thread.start()
        try:
            with tempfile.TemporaryDirectory(dir=SCRATCH) as tmp:
                control = str(pathlib.Path(tmp) / 'control.sock')
                proc = subprocess.Popen([sys.executable, '-u', str(ROOT / 'edge.py'),
                    '--role', 'mcp', '--listen', '127.0.0.1', '--port', '0', '--control', control,
                    '--target', f'http://127.0.0.1:{upstream.server_port}/mcp', '--local-test', '--model', 'fixture'],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                try:
                    address = json.loads(proc.stdout.readline())
                    config = grant()
                    configured = subprocess.run([sys.executable, str(ROOT / 'provisioner.py'),
                        'control', '--socket', control, '--role', 'mcp'],
                        input=json.dumps({'op':'configure','config':config}), text=True,
                        capture_output=True, timeout=5)
                    self.assertEqual(configured.returncode, 0, configured.stdout + configured.stderr)
                    body = json.dumps({'jsonrpc':'2.0','id':1,'method':'tools/list','params':{}}).encode()
                    conn = http.client.HTTPConnection('127.0.0.1', address['port'], timeout=3)
                    conn.request('POST', '/mcp', body, {
                        'Content-Type':'application/json', 'Authorization':'Bearer forged',
                        'Transfer-Encoding':'chunked', 'Accept':'application/json, text/event-stream'},
                        encode_chunked=True)
                    response = conn.getresponse()
                    self.assertEqual(response.status, 200, response.read())
                    response.read(); conn.close()
                    self.assertEqual(seen, [{'jsonrpc':'2.0','id':1,'method':'tools/list','params':{}}])
                finally:
                    proc.terminate(); proc.wait(timeout=5); proc.stdout.close(); proc.stderr.close()
        finally:
            upstream.shutdown(); upstream.server_close(); thread.join()

    def test_process_configure_deny_proxy_and_cleanup(self):
        self.assertTrue((ROOT / 'edge.py').exists(), 'trusted edge missing')
        seen = []
        class Upstream(BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers['Content-Length']))
                seen.append((self.path, dict(self.headers), json.loads(body)))
                self.send_response(200); self.end_headers(); self.wfile.write(b'{"ok":true}')
            def log_message(self, *args): pass
        upstream = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
        thread = threading.Thread(target=upstream.serve_forever, daemon=True); thread.start()
        try:
            for role in ('mcp', 'provider'):
                with self.subTest(role=role), tempfile.TemporaryDirectory(dir=SCRATCH) as tmp:
                    control = str(pathlib.Path(tmp) / 'control.sock')
                    key = pathlib.Path(tmp) / 'key'; key.write_text('DUMMY-PROVIDER-KEY')
                    proc = subprocess.Popen([sys.executable, '-u', str(ROOT / 'edge.py'),
                        '--role', role, '--listen', '127.0.0.1', '--port', '0', '--control', control,
                        '--target', f'http://127.0.0.1:{upstream.server_port}/' + ('mcp' if role == 'mcp' else 'v1/chat/completions'),
                        '--local-test', '--key-file', str(key), '--model', 'fixture'],
                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                    try:
                        address = json.loads(proc.stdout.readline())
                        self.assertEqual(address['mode'], 'LOCAL-TEST-NOT-ISOLATED')
                        def request(path, body):
                            conn = http.client.HTTPConnection('127.0.0.1', address['port'], timeout=3)
                            conn.request('POST', path, json.dumps(body), {'Content-Type':'application/json', 'Authorization':'Bearer forged'})
                            res = conn.getresponse(); status = res.status; data = res.read(); conn.close()
                            return status, data
                        def command(body):
                            with socket.socket(socket.AF_UNIX) as sock:
                                sock.settimeout(3); sock.connect(control)
                                sock.sendall(json.dumps(body).encode() + b'\n')
                                return json.loads(sock.makefile('rb').readline())
                        path = '/mcp' if role == 'mcp' else '/v1/chat/completions'
                        body = {'jsonrpc':'2.0','id':1,'method':'tools/list','params':{}} if role == 'mcp' else {
                            'model':'fixture','messages':[], 'tools':[{'type':'function','function':{'name':n}} for n in EXACT_TOOLS]}
                        before = len(seen)
                        self.assertEqual(request(path, body)[0], 403)
                        self.assertFalse(command({'op':'status'})['ready'])
                        config = grant()
                        def cli_command(payload):
                            completed = subprocess.run([sys.executable, str(ROOT / 'provisioner.py'),
                                'control', '--socket', control, '--role', role],
                                input=json.dumps(payload), text=True, capture_output=True, timeout=5)
                            self.assertNotIn(config['capability'], completed.stdout + completed.stderr)
                            return completed
                        configured = cli_command({'op':'configure','config':config})
                        self.assertEqual(configured.returncode, 0, configured.stdout + configured.stderr)
                        receipt = json.loads(configured.stdout)
                        readback = receipt.pop('readback')
                        self.assertEqual(receipt, readback)
                        self.assertTrue(receipt['ready']); self.assertNotIn('capability', json.dumps(receipt))
                        expected = {k: v for k, v in config.items() if k != 'capability'}
                        expected.update(ok=True, role=role, ready=True, revoked=False, spent=False)
                        self.assertEqual(receipt, expected)
                        self.assertEqual(command({'op':'status'}), expected)
                        self.assertEqual(pathlib.Path(control).stat().st_mode & 0o777, 0o700)
                        cli = subprocess.run([sys.executable, str(ROOT / 'provisioner.py'), 'control', '--socket', control, '--role', role],
                            input=json.dumps({'op':'status','expected':{k:v for k,v in config.items() if k != 'capability'}}), text=True, capture_output=True, timeout=5)
                        self.assertEqual(cli.returncode, 0, cli.stderr)
                        self.assertTrue(json.loads(cli.stdout)['readback']['ready'])
                        self.assertEqual(request('/configure', body)[0], 403)
                        self.assertEqual(request(path + '?url=http://other', body)[0], 403)
                        if role == 'provider':
                            valid_reasoning = dict(body, reasoning_effort='low')
                            self.assertEqual(request(path, valid_reasoning)[0], 200)
                            before += 1
                            for bad in ([], body['tools'][:-1], body['tools'] + [{'type':'function','function':{'name':'terminal'}}]):
                                self.assertEqual(request(path, dict(body, tools=bad))[0], 403)
                        else:
                            self.assertEqual(request(path, dict(body, method='unknown'))[0], 403)
                        self.assertEqual(len(seen), before)
                        self.assertEqual(request(path, body)[0], 200)
                        self.assertEqual(len(seen), before + 1)
                        self.assertEqual(seen[-1][0], path)
                        expected = 'Bearer ' + (grant()['capability'] if role == 'mcp' else 'DUMMY-PROVIDER-KEY')
                        self.assertEqual(seen[-1][1]['Authorization'], expected)
                        public = {k:v for k,v in config.items() if k != 'capability'}
                        self.assertFalse(command({'op':'revoke','role':role,'incarnation':'stale',
                                                  'expected':dict(public, incarnation='stale')})['ok'])
                        self.assertNotEqual(cli_command({'op':'revoke','incarnation':'stale','expected':public}).returncode, 0)
                        revoked_cli = cli_command({'op':'revoke','incarnation':'abc123','expected':public})
                        self.assertEqual(revoked_cli.returncode, 0, revoked_cli.stdout + revoked_cli.stderr)
                        revoked = {k: v for k, v in config.items() if k != 'capability'}
                        revoked.update(ok=True, role=role, ready=False, revoked=True, spent=True)
                        self.assertEqual(command({'op':'status'}), revoked)
                        self.assertEqual(request(path, body)[0], 403)
                        self.assertFalse(command({'op':'configure','role':role,'config':grant()})['ok'])
                        self.assertEqual(len(seen), before + 1)
                    finally:
                        proc.terminate(); proc.wait(timeout=5)
                        proc.stdout.close(); proc.stderr.close()
        finally:
            upstream.shutdown(); upstream.server_close(); thread.join()

if __name__ == '__main__': unittest.main()
