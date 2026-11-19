"""New opt-in lifecycle only. Fixtures are not model output."""
import importlib.util
import json
import os
from pathlib import Path
import select
import signal
import subprocess
import sys
import tempfile
from worker_test_paths import scratch_root
import time
import unittest

POOL = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(POOL))

class BootstrapTests(unittest.TestCase):
    def test_refusal_matrix_never_imports_factory(self):
        generation='a'*32
        valid={'kind':'start_factory','generation':generation}
        cases=[dict(valid,generation='b'*32),dict(valid,kind='run'),dict(valid,extra=True),
               b'{"kind":"start_factory","kind":"start_factory","generation":"'+generation.encode()+b'"}\n',
               b'x'*4096, b'[[['*500, None]
        names=['wrong-generation','wrong-kind','extra-field','duplicate-key','oversize','deep-json','missing']
        for case_id, case in zip(names, cases):
            with self.subTest(case_id=case_id), tempfile.TemporaryDirectory(dir=scratch_root()) as tmp:
                marker=Path(tmp)/'imported'
                (Path(tmp)/'forbidden.py').write_text('from pathlib import Path\nPath('+repr(str(marker))+').touch()\n')
                now=time.monotonic_ns()
                p=subprocess.Popen([sys.executable,'-I','-S','-B',str(POOL/'worker_bootstrap.py'),'forbidden:factory',generation,
                    str(now+300_000_000),str(now+2_000_000_000),json.dumps([str(POOL),tmp])],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
                try:
                    self.assertEqual(json.loads(p.stdout.readline())['kind'],'spawn_waiting')
                    data=(json.dumps(case)+'\n').encode() if type(case) is dict else case
                    if data is None:
                        time.sleep(.4); data=b''
                    out,err=p.communicate(data,timeout=3)
                    self.assertEqual(p.returncode,23,'PERMIT_REJECTION')
                    self.assertFalse(marker.exists(),'INVALID_PERMIT_NO_IMPORT')
                    self.assertEqual(out+err,b'')
                finally:
                    if p.poll() is None: p.kill(); p.wait()

    def test_missing_permit_never_imports_factory(self):
        self.assertTrue((POOL / 'worker_bootstrap.py').exists(), 'BOOTSTRAP_REQUIRED')
        with tempfile.TemporaryDirectory(dir=scratch_root()) as tmp:
            marker = Path(tmp) / 'imported'
            (Path(tmp) / 'forbidden.py').write_text('from pathlib import Path\nPath(' + repr(str(marker)) + ').touch()\n')
            now = time.monotonic_ns()
            p = subprocess.Popen([sys.executable, '-I', '-S', '-B', str(POOL / 'worker_bootstrap.py'),
                'forbidden:factory', 'a'*32, str(now+400_000_000), str(now+2_000_000_000), json.dumps([str(POOL), tmp])],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                first = p.stdout.readline()
                self.assertEqual(json.loads(first), {'kind':'spawn_waiting','generation':'a'*32})
                out, err = p.communicate(timeout=3)
                self.assertEqual(p.returncode, 23)
                self.assertFalse(marker.exists(), 'PERMIT_BEFORE_IMPORT')
                self.assertEqual(out+err, b'')
            finally:
                if p.poll() is None: p.kill(); p.wait()

class ServeTests(unittest.TestCase):
    def test_real_entry_creates_only_fresh_home_and_passes_trusted_settings(self):
        with tempfile.TemporaryDirectory(dir=scratch_root()) as tmp:
            home = Path(tmp) / 'home'
            env = {'PATH': os.environ.get('PATH', ''), 'TMPDIR': tmp,
                   'POOL_STARTUP_SECONDS': '1', 'POOL_MAX_LIFETIME_SECONDS': '4',
                   'POOL_HOME': tmp, 'POOL_HERMES_HOME': str(home),
                   'POOL_MODEL': 'fixture', 'POOL_MCP_URL': 'http://edge/mcp',
                   'POOL_PROVIDER_URL': 'http://edge/v1'}
            code = ('import sys; sys.path[:0]=' + repr([str(POOL), str(POOL/'tests')]) +
                    '; from worker_lifecycle import serve; raise SystemExit(serve('
                    '"worker_lifecycle_fixture:home_config_factory",' +
                    repr([str(POOL), str(POOL/'tests')]) + '))')
            p = subprocess.Popen([sys.executable, '-I', '-S', '-B', '-c', code],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
            try:
                p.stdin.write((json.dumps({'jsonrpc':'2.0','id':1,'method':'session.create','params':{}})+'\n').encode()); p.stdin.flush()
                self.assertEqual(json.loads(p.stdout.readline())['result']['incarnation'], 'local-test')
                self.assertTrue((home / 'config.yaml').is_file(), 'FRESH_HOME_CONFIG')
                p.stdin.close(); p.wait(timeout=5)
            finally:
                if p.poll() is None: p.kill(); p.wait()

    def test_rpc_terminal_after_holding(self): self.rpc_case('fixture','complete')
    def test_rpc_exception_is_error(self): self.rpc_case('exception','error')
    def test_rpc_cancel(self): self.rpc_case('block','interrupted','cancel')
    def test_rpc_term(self): self.rpc_case('block','interrupted','term')
    def test_rpc_eof(self): self.rpc_case('block','interrupted','eof')
    def test_rpc_timeout(self): self.rpc_case('block','interrupted','timeout')

    def test_rpc_cancel_after_candidate_consumed_before_publication(self):
        script = (
            "import sys, time\n"
            "sys.path[:0] = " + repr([str(POOL), str(POOL/'tests')]) + "\n"
            "import worker_lifecycle as lc\n"
            "original = lc.Owner.receive\n"
            "state = {'delayed': False}\n"
            "def delayed(self):\n"
            "    if not state['delayed'] and self.phase == 'terminal_candidate':\n"
            "        state['delayed'] = True; time.sleep(1.2)\n"
            "    return original(self)\n"
            "lc.Owner.receive = delayed\n"
            "raise SystemExit(lc.serve('worker_lifecycle_fixture:factory'," +
            repr([str(POOL), str(POOL/'tests')]) + "))")
        self.rpc_delayed_cancel(script)

    def test_rpc_wrong_sid_before_publication_does_not_cancel(self):
        script = (
            "import sys, time\n"
            "sys.path[:0] = " + repr([str(POOL), str(POOL/'tests')]) + "\n"
            "import worker_lifecycle as lc\n"
            "original = lc.Owner.receive\n"
            "state = {'delayed': False}\n"
            "def delayed(self):\n"
            "    if not state['delayed'] and self.phase == 'terminal_candidate':\n"
            "        state['delayed'] = True; time.sleep(1.2)\n"
            "    return original(self)\n"
            "lc.Owner.receive = delayed\n"
            "raise SystemExit(lc.serve('worker_lifecycle_fixture:factory'," +
            repr([str(POOL), str(POOL/'tests')]) + "))")
        self.rpc_delayed_cancel(script, sid='wrong-session-id', expected='complete')

    def rpc_delayed_cancel(self, script, sid=None, expected='interrupted'):
        env = {'PATH':os.environ.get('PATH',''), 'TMPDIR':str(scratch_root()),
               'POOL_STARTUP_SECONDS':'1', 'POOL_MAX_LIFETIME_SECONDS':'4'}
        p = subprocess.Popen([sys.executable, '-I', '-S', '-B', '-c', script],
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, env=env, bufsize=0)
        def request(rid, method, params):
            p.stdin.write((json.dumps({'jsonrpc':'2.0','id':rid,'method':method,'params':params})+'\n').encode())
            p.stdin.flush()
        def response():
            data=b''; until=time.monotonic()+7
            while b'\n' not in data:
                if until-time.monotonic() <= 0:
                    rc = p.poll()
                    err = p.stderr.read(256).decode('ascii', 'replace') if rc is not None else ''
                    self.fail('RPC_DEADLINE rc=%r stderr_tail=%r' % (rc, err[-256:]))
                if select.select([p.stdout],[],[],.05)[0]: data += os.read(p.stdout.fileno(),1)
            return json.loads(data)
        try:
            request(1, 'session.create', {'cwd':'/workspace'})
            created=response()['result']
            request(2, 'prompt.submit', {'session_id':created['session_id'], 'text':'fixture'})
            self.assertEqual(response()['result'], {'status':'accepted'})
            time.sleep(.25)
            request(3, 'session.interrupt', {'session_id': sid or created['session_id']})
            frames=[]
            while True:
                if p.poll() is not None and not select.select([p.stdout],[],[],0)[0]: break
                try: frames.append(response())
                except AssertionError: break
            p.wait(timeout=7)
            events=[f for f in frames if f.get('method') == 'event']
            event_payloads=[f['params']['payload'] for f in events]
            statuses=[f.get('result',{}).get('status') for f in frames]
            if sid is None:
                self.assertIn('interrupted', statuses, 'CANCEL_ACK')
                self.assertEqual(event_payloads, [{'status':'interrupted','text':''}], 'CANCEL_EVENT')
            else:
                self.assertEqual(event_payloads, [{'status':'complete','text':'fixture-only'}], 'WRONG_SID_COMPLETE')
            self.assertEqual(p.returncode, 0)
            self.assertEqual(p.stderr.read(), b'')
        finally:
            if p.poll() is None: p.kill(); p.wait()
            for stream in (p.stdin,p.stdout,p.stderr): stream.close()

    def test_missing_holding_eof_has_no_terminal(self):
        env={'POOL_STARTUP_SECONDS':'1','POOL_MAX_LIFETIME_SECONDS':'3','TMPDIR':str(scratch_root())}
        code='import sys; sys.path[:0]='+repr([str(POOL),str(POOL/'tests')])+'; from worker_lifecycle import serve; raise SystemExit(serve("worker_lifecycle_fixture:missing_holding",'+repr([str(POOL),str(POOL/'tests')])+'))'
        p=subprocess.Popen([sys.executable,'-I','-S','-B','-c',code],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env)
        try:
            p.stdin.write((json.dumps({'jsonrpc':'2.0','id':1,'method':'session.create','params':{}})+'\n').encode()); p.stdin.flush()
            self.assertTrue(select.select([p.stdout],[],[],3)[0])
            created=json.loads(p.stdout.readline())['result']
            p.stdin.write((json.dumps({'jsonrpc':'2.0','id':2,'method':'prompt.submit','params':{'session_id':created['session_id'],'text':'fixture'}})+'\n').encode()); p.stdin.flush()
            self.assertTrue(select.select([p.stdout],[],[],3)[0])
            self.assertEqual(json.loads(p.stdout.readline())['result'],{'status':'accepted'})
            p.wait(timeout=5)
            self.assertEqual(p.stdout.read(),b'','MISSING_HOLDING_NO_TERMINAL')
            self.assertEqual(p.returncode,1,'MISSING_HOLDING_NONZERO')
        finally:
            if p.poll() is None: p.kill(); p.wait()
            for stream in (p.stdin,p.stdout,p.stderr): stream.close()

    def rpc_case(self,text,status,action=None):
        import worker_lifecycle as lc
        self.assertTrue(hasattr(lc, 'serve'), 'SERVE_REQUIRED')
        env = {'PATH':os.environ.get('PATH',''), 'TMPDIR':str(scratch_root()),
               'POOL_STARTUP_SECONDS':'1', 'POOL_MAX_LIFETIME_SECONDS':'4'}
        code = 'import sys; sys.path[:0]='+repr([str(POOL),str(POOL/'tests')])+'; from worker_lifecycle import serve; raise SystemExit(serve("worker_lifecycle_fixture:factory",'+repr([str(POOL),str(POOL/'tests')])+'))'
        p = subprocess.Popen([sys.executable,'-I','-S','-B','-c',code],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env,bufsize=0)
        def request(rid, method, params):
            p.stdin.write((json.dumps({'jsonrpc':'2.0','id':rid,'method':method,'params':params})+'\n').encode())
        def response():
            data=b''; until=time.monotonic()+5
            while b'\n' not in data:
                self.assertGreater(until-time.monotonic(),0,'RPC_DEADLINE')
                if select.select([p.stdout],[],[],.05)[0]:
                    chunk=os.read(p.stdout.fileno(),1)
                    self.assertTrue(chunk,'RPC_NOT_EOF')
                    data+=chunk
            return json.loads(data)
        try:
            request(1,'session.create',{'cwd':'/workspace'})
            created=response()['result']; self.assertIsNone(created['stored_session_id'])
            request(2,'prompt.submit',{'session_id':created['session_id'],'text':text})
            self.assertEqual(response()['result'],{'status':'accepted'},'ACK_BEFORE_TERMINAL')
            if action == 'cancel':
                request(3,'session.interrupt',{'session_id':created['session_id']})
                self.assertEqual(response()['result'],{'status':'interrupted'},'CANCEL_ACK')
            if action == 'term': p.send_signal(signal.SIGTERM)
            if action == 'eof': p.stdin.close()
            event=response()
            self.assertEqual(event['params']['payload'],{'status':status,'text':'fixture-only' if status=='complete' else ''},'HELD_COMPLETE')
            self.assertEqual(event['params']['seq'],1)
            p.stdin.close(); p.wait(timeout=5)
            self.assertEqual(p.returncode,0)
            self.assertEqual(p.stderr.read(),b'')
        finally:
            if p.poll() is None: p.kill(); p.wait()
            for stream in (p.stdin,p.stdout,p.stderr): stream.close()

class MutationClassifierTests(unittest.TestCase):
    def test_exact_identity_count_and_assertion_required(self):
        import copy
        from verify_worker_lifecycle_mutations import valid_result
        test='module.Class.test'
        good=dict(run=1,started=[test],failures=1,errors=0,skips=0,unexpected=0,expected=0,
                  records=[dict(id=test,kind='failure',exception='AssertionError',message='bad : EXPECTED')])
        self.assertTrue(valid_result(1,good,test,[test],'EXPECTED'))
        cases=[]
        for key,value in [('run',2),('started',['other']),('failures',2),('errors',1),('skips',1)]:
            bad=copy.deepcopy(good); bad[key]=value; cases.append((key,bad))
        for key,value in [('id','other'),('exception','ImportError'),('message','EXPECTED in unrelated traceback'),('kind','error')]:
            bad=copy.deepcopy(good); bad['records'][0][key]=value; cases.append((key,bad))
        bad=copy.deepcopy(good); bad['records']*=2; bad['failures']=2; cases.append(('duplicate',bad))
        for name,bad in cases:
            with self.subTest(case_id=name): self.assertFalse(valid_result(1,bad,test,[test],'EXPECTED'))
        self.assertFalse(valid_result(124,good,test,[test],'EXPECTED'))
        self.assertFalse(valid_result(0,good,test,[]))
        baseline=dict(good,failures=0,records=[])
        self.assertTrue(valid_result(0,baseline,test,[]))
        self.assertFalse(valid_result(1,baseline,test,[]))


class PublicationTests(unittest.TestCase):
    def test_queue_128_cancel_fails_closed(self): self.publication_case(128, 'overload')
    def test_queue_256_cancel_fails_closed(self): self.publication_case(256, 'overload')
    def test_queue_127_cancel_is_honored(self): self.publication_case(127, 'interrupted')
    def test_byte_cap_fails_closed(self): self.publication_case(1, 'overload', 'bytes')
    def test_byte_cap_minus_one_cancel(self): self.publication_case(1, 'interrupted', 'bytes_below')
    def test_time_cap_fails_closed(self): self.publication_case(1, 'overload', 'time')
    def test_health_write_uses_drain_deadline(self): self.publication_case(1, 'interrupted', 'deadline')
    def test_output_backpressure_is_bounded(self): self.publication_case(1, 'transport', 'backpressure')
    def test_empty_queue_completes(self): self.publication_case(0, 'complete', 'empty')
    def test_single_cancel(self): self.publication_case(0, 'interrupted')
    def test_wrong_session_does_not_cancel(self): self.publication_case(0, 'complete', 'wrong')
    def test_after_publication_is_late(self): self.publication_case(0, 'complete', 'published')
    def test_exact_frame_cap_empty_completes(self): self.publication_case(128, 'complete', 'no_cancel')
    def test_frame_cap_buffered_fragment_fails(self): self.publication_case(128, 'overload', 'fragment')
    def test_frame_cap_kernel_cancel_fails(self): self.publication_case(128, 'overload', 'kernel')
    def test_one_mib_queue_fails_closed(self): self.publication_case(1, 'overload', 'large')
    def test_slow_drip_at_time_barrier_fails(self): self.publication_case(1, 'overload', 'slowdrip')

    def publication_case(self, count, expected, mode='normal'):
        def pack(rid, method, params):
            return (json.dumps(dict(jsonrpc='2.0',id=rid,method=method,params=params),separators=(',',':'))+'\n').encode()
        with tempfile.TemporaryDirectory(dir=scratch_root()) as tmp:
            d = Path(tmp)
            script = 'import sys; sys.path[:0]='+repr([str(POOL),str(POOL/'tests')])+'; from worker_lifecycle_fixture import publication_server; raise SystemExit(publication_server('+repr(tmp)+','+repr(mode)+'))'
            p = subprocess.Popen([sys.executable,'-I','-S','-B','-c',script],stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,stderr=subprocess.PIPE,bufsize=0,
                env={'POOL_STARTUP_SECONDS':'1','POOL_MAX_LIFETIME_SECONDS':'8','TMPDIR':str(scratch_root())})
            def marker(name):
                end=time.monotonic()+4
                while not (d/(name+'.ready')).exists():
                    self.assertIsNone(p.poll(),'FIXTURE_SERVER_EXIT')
                    self.assertLess(time.monotonic(),end,'FIXTURE_BARRIER')
                    time.sleep(.001)
            def response():
                data=b''; end=time.monotonic()+4
                while not data.endswith(b'\n'):
                    self.assertLess(time.monotonic(),end,'FIXTURE_RESPONSE_TIMEOUT')
                    if select.select([p.stdout],[],[],.02)[0]:
                        part=os.read(p.stdout.fileno(),1)
                        self.assertTrue(part,'FIXTURE_RESPONSE_EOF')
                        data+=part
                return json.loads(data)
            try:
                p.stdin.write(pack(1,'session.create',{})); sid=response()['result']['session_id']
                p.stdin.write(pack(2,'prompt.submit',{'session_id':sid,'text':'fixture'}))
                self.assertEqual(response()['result'],{'status':'accepted'})
                marker('held')
                owned=json.loads((d/'owned.json').read_text())
                self.assertEqual(owned['candidate'],{'status':'complete','text':'fixture-only'})
                cancel=pack(3,'session.interrupt',{'session_id':'wrong' if mode=='wrong' else sid})
                payload=b''.join(pack(1000+i,'health',{}) for i in range(count))+cancel
                if mode=='kernel': (d/'readlimit').write_text(str(len(payload)-len(cancel)))
                if mode in ('empty','published'): payload=b''
                if mode=='no_cancel': payload=payload[:-len(cancel)]
                if mode=='fragment': payload=payload[:-1]
                if mode=='slowdrip': payload=pack(1000,'health',{})
                if mode in ('bytes','bytes_below','large'):
                    import fcntl
                    # Explicit capacity precondition: queue every byte BEFORE release.
                    target_capacity=1048576 if mode=='large' else 131072
                    capacity=fcntl.fcntl(p.stdin.fileno(),fcntl.F_SETPIPE_SZ,target_capacity)
                    self.assertGreaterEqual(capacity,target_capacity,'FIXTURE_PIPE_CAPACITY')
                    health=pack(1000,'health',{})
                    size=1048576-len(cancel) if mode=='large' else 65536 if mode=='bytes' else 65535-len(cancel)
                    payload=health[:-1]+b' '*(size-len(health))+b'\n'+cancel
                os.set_blocking(p.stdin.fileno(),False)
                sent=0
                while sent<len(payload): sent+=os.write(p.stdin.fileno(),payload[sent:])
                self.assertEqual(sent,len(payload),'FIXTURE_FULLY_QUEUED')
                start=time.monotonic(); (d/'held.go').touch()
                if mode in ('time','slowdrip'):
                    marker('write')
                    if mode=='slowdrip':
                        for byte in cancel:
                            self.assertEqual(os.write(p.stdin.fileno(),bytes([byte])),1)
                            time.sleep(.001)
                    (d/'write.go').touch()
                if mode=='published':
                    marker('published'); self.assertEqual(os.write(p.stdin.fileno(),cancel),len(cancel))
                    (d/'published.go').touch()
                if mode=='backpressure': p.wait(timeout=1)
                raw=b''; end=time.monotonic()+3
                while True:
                    self.assertLess(time.monotonic(),end,'PUBLICATION_BOUNDED')
                    if select.select([p.stdout],[],[],.02)[0]:
                        part=os.read(p.stdout.fileno(),65536)
                        if not part: break
                        raw+=part
                p.wait(timeout=1)
                if mode=='backpressure':
                    filler=json.loads((d/'filler.json').read_text())
                    self.assertGreater(filler,0,'FIXTURE_REAL_BACKPRESSURE')
                    self.assertEqual(raw[:filler],b' '*filler)
                    raw=raw[filler:]
                frames=[json.loads(line) for line in raw.splitlines()]
                clean=json.loads((d/'cleanup.json').read_text())
                self.assertEqual(clean['state'],'GREEN','RESOURCE_CLEANUP_INDEPENDENT')
                self.assertEqual(clean['fds'],{})
                self.assertEqual(clean['record'],{'pid':owned['pid'],'code':os.CLD_KILLED,'status':9,'source':'waitid'})
                with self.assertRaises(ProcessLookupError): os.kill(owned['pid'],0)
                events=[f['params']['payload'] for f in frames if f.get('method')=='event']
                ack=[f for f in frames if f.get('id')==3]
                if expected=='overload':
                    self.assertNotIn('complete',[e['status'] for e in events],'OVERLOAD_NO_COMPLETE')
                    self.assertEqual(p.returncode,1,'OVERLOAD_NONZERO')
                    self.assertEqual(ack,[],'UNREAD_CANCEL_NO_ACK')
                    self.assertEqual(events,[{'status':'error','text':''}],'OVERLOAD_SAFE_ERROR')
                elif expected=='complete':
                    self.assertEqual(events,[{'status':'complete','text':'fixture-only'}])
                    self.assertEqual(p.returncode,0)
                    if mode=='wrong': self.assertEqual(ack,[{'jsonrpc':'2.0','id':3,'error':{'code':-32001,'message':'worker denied'}}])
                    else: self.assertEqual(ack,[])
                elif expected=='transport':
                    self.assertEqual(events,[],'BACKPRESSURE_NO_COMPLETE')
                    self.assertEqual(ack,[])
                    self.assertEqual(p.returncode,1)
                else:
                    self.assertEqual(events,[{'status':'interrupted','text':''}],'BOUNDARY_CANCEL_EVENT')
                    self.assertEqual(ack,[{'jsonrpc':'2.0','id':3,'result':{'status':'interrupted'}}],'BOUNDARY_CANCEL_ACK')
                    self.assertEqual(p.returncode,0)
                if mode in ('deadline','backpressure','time'):
                    write=json.loads((d/'write.json').read_text())
                    self.assertLessEqual(write['deadline']-write['now'],25_000_000,'DRAIN_WRITE_SHARED_DEADLINE')
                self.assertEqual(p.stderr.read(),b'')
            finally:
                if p.poll() is None: p.kill(); p.wait()
                for stream in (p.stdin,p.stdout,p.stderr): stream.close()


if __name__ == '__main__': unittest.main()
