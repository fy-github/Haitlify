#!/usr/bin/env python3
"""Finite single-site mutations in scratch copies; timeout/error is INVALID."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from worker_test_paths import scratch_root

ROOT=Path(__file__).resolve().parents[1]
CASES=[
 ('fd-baseexception','worker_lifecycle.py',"except BaseException:\n            self.failed = True\n    def close_all", "except BaseException:\n            self.failed = False\n    def close_all",'test_worker_cleanup_evidence.CleanupTests.test_fd_close_baseexception_attempts_each_fd_and_sticks','CLOSE_BASEEXCEPTION_STICKY'),
 ('expired-spawn','worker_lifecycle.py',"if time.monotonic_ns() >= self.deadline: raise Blocked('expired before spawn')","if False: raise Blocked('expired before spawn')",'test_worker_cleanup_evidence.LinuxOwnerTests.test_expired_before_launch_never_spawns','EXPIRED_NO_SPAWN'),
 ('candidate-holding','worker_lifecycle.py',"self.candidate = frame['payload']; self.phase = 'holding'","self.candidate = frame['payload']; self.phase = 'held'",'test_worker_cleanup_evidence.LinuxOwnerTests.test_candidate_alone_never_held','CANDIDATE_NOT_HELD'),
 ('native-disposition','worker_lifecycle.py','or action.flags & 2:', 'or False:', 'test_worker_cleanup_evidence.LinuxOwnerTests.test_native_nocldwait_refused_before_spawn','NATIVE_NOCLDWAIT_REJECTED'),
 ('close-sticky','worker_lifecycle.py','if self.ledger.failed or time.monotonic_ns() >= self.clean_deadline:', 'if time.monotonic_ns() >= self.clean_deadline:', 'test_worker_cleanup_evidence.LinuxOwnerTests.test_close_error_after_kill_is_sticky','OWNER_CLOSE_NOT_GREEN'),
 ('failed-reap','worker_lifecycle.py','if self.failed and self.pid is not None and constructor_exit is None and self.exit_record is None:', 'if False:', 'test_worker_cleanup_evidence.LinuxOwnerTests.test_permission_failure_reaps_after_eof_without_success','FAILED_PATH_EXACT_REAP'),
 ('strict-turn','worker_lifecycle.py',"or frame['generation'] != self.generation or type(frame['turn']) is not int or frame['turn'] != 1):", "or frame['generation'] != self.generation or frame['turn'] != 1):", 'test_worker_cleanup_evidence.LinuxOwnerTests.test_held_requires_exact_turn_and_schema','STRICT_HOLDING'),
 ('permit-gate','worker_bootstrap.py',"if not control(permit, 'start_factory', generation): raise ProtocolError('permit')", "if False: raise ProtocolError('permit')", 'test_worker_lifecycle_handshake.BootstrapTests.test_refusal_matrix_never_imports_factory','INVALID_PERMIT_NO_IMPORT'),
 ('publication-cancel','worker_lifecycle.py',"                    publication_uncertain = not drain_publication_control(until)\n", "                    publication_uncertain = False\n",'test_worker_lifecycle_handshake.ServeTests.test_rpc_cancel_after_candidate_consumed_before_publication','CANCEL_ACK'),
 ('subprocess-hash','worker_lifecycle.py',"    if subprocess_hash != AUDITED_SUBPROCESS_SHA256:\n", "    if False:\n",'test_worker_cleanup_evidence.CleanupTests.test_unverified_subprocess_source_rejected_before_spawn','Blocked not raised'),
]

PUBLICATION='test_worker_lifecycle_handshake.PublicationTests.'
CASES += [
 ('frame-cap','worker_lifecycle.py', 'return not incoming and not select.select([0], [], [], 0)[0]', 'return True', PUBLICATION+'test_queue_128_cancel_fails_closed','OVERLOAD_NO_COMPLETE'),
 ('frame-cap-256','worker_lifecycle.py', 'return not incoming and not select.select([0], [], [], 0)[0]', 'return True', PUBLICATION+'test_queue_256_cancel_fails_closed','OVERLOAD_NO_COMPLETE'),
 ('byte-cap','worker_lifecycle.py', 'return False  # exact byte/time cap is conservatively uncertain', 'return True  # mutant', PUBLICATION+'test_byte_cap_fails_closed','OVERLOAD_NO_COMPLETE'),
 ('time-cap','worker_lifecycle.py', 'return False  # exact byte/time cap is conservatively uncertain', 'return True  # mutant', PUBLICATION+'test_time_cap_fails_closed','OVERLOAD_NO_COMPLETE'),
 ('drain-write','worker_lifecycle.py', 'reply(rid, session.handle(method, params), end)', 'reply(rid, session.handle(method, params), until)', PUBLICATION+'test_health_write_uses_drain_deadline','DRAIN_WRITE_SHARED_DEADLINE'),
 ('kernel-remaining','worker_lifecycle.py', 'return not incoming and not select.select([0], [], [], 0)[0]', 'return not incoming', PUBLICATION+'test_frame_cap_kernel_cancel_fails','OVERLOAD_NO_COMPLETE'),
 ('buffered-fragment','worker_lifecycle.py', 'return not incoming and not select.select([0], [], [], 0)[0]', 'return not select.select([0], [], [], 0)[0]', PUBLICATION+'test_frame_cap_buffered_fragment_fails','OVERLOAD_NO_COMPLETE'),
]

# Machine-readable unittest result, not a substring classifier over traceback text.
RUNNER = '''
import unittest, json, sys
class Result(unittest.TextTestResult):
    def __init__(self,*args,**kwargs):
        super().__init__(*args,**kwargs); self.records=[]; self.started=[]
    def startTest(self,test):
        self.started.append(test.id()); super().startTest(test)
    def record(self,test,err,kind):
        self.records.append({'id':test.id(),'kind':kind,'exception':err[0].__name__,'message':str(err[1])})
    def addFailure(self,test,err):
        self.record(test,err,'failure'); super().addFailure(test,err)
    def addError(self,test,err):
        self.record(test,err,'error'); super().addError(test,err)
    def addSubTest(self,test,subtest,err):
        if err is not None: self.record(subtest,err,'failure' if issubclass(err[0],test.failureException) else 'error')
        super().addSubTest(test,subtest,err)
r=unittest.TextTestRunner(verbosity=2,resultclass=Result).run(unittest.defaultTestLoader.loadTestsFromName(TEST))
print('RESULT_JSON='+json.dumps({'run':r.testsRun,'started':r.started,'failures':len(r.failures),'errors':len(r.errors),'skips':len(r.skipped),'unexpected':len(r.unexpectedSuccesses),'expected':len(r.expectedFailures),'records':r.records}))
sys.exit(not r.wasSuccessful())
'''

def valid_result(exit_code, result, test, identities, assertion=None):
    if not isinstance(result,dict): return False
    if set(result) != {'run','started','failures','errors','skips','unexpected','expected','records'}: return False
    if result['run'] != 1 or result['started'] != [test]: return False
    if any(result[k] != 0 for k in ('errors','skips','unexpected','expected')): return False
    records=result['records']
    if result['failures'] != len(identities) or len(records) != len(identities): return False
    if exit_code != (1 if identities else 0): return False
    if sorted(r['id'] for r in records) != sorted(identities) or len(set(r['id'] for r in records)) != len(records): return False
    return all(r['kind']=='failure' and r['exception']=='AssertionError' and
               (r['message']==assertion or r['message'].endswith(' : '+assertion)) for r in records)


def main():
    if sys.platform != 'linux':
        print('BLOCKED: mutation runtime must be Linux development target'); return 2
    out=Path(tempfile.mkdtemp(prefix='worker-lifecycle-mutations-', dir=scratch_root()))
    results=[]
    files=['worker.py','gate.py','worker_bootstrap.py','worker_lifecycle.py']
    tests=['test_worker_cleanup_evidence.py','test_worker_lifecycle_handshake.py','worker_lifecycle_fixture.py',
           'worker_test_paths.py']
    for name,filename,old,new,test,assertion in CASES:
        row={'name':name,'test':test,'assertion':assertion}
        for phase in ('baseline','mutant'):
            folder=out/(name+'-'+phase); folder.mkdir(); (folder/'tests').mkdir()
            for f in files: shutil.copy2(ROOT/f,folder/f)
            for f in tests: shutil.copy2(ROOT/'tests'/f,folder/'tests'/f)
            if phase=='mutant':
                path=folder/filename; text=path.read_text()
                if text.count(old)!=1: raise RuntimeError('mutation anchor not unique')
                path.write_text(text.replace(old,new))
            script='import sys; sys.path[:0]='+repr([str(folder),str(folder/'tests')])+'; TEST='+repr(test)+'\n'+RUNNER
            try:
                p=subprocess.run([sys.executable,'-I','-S','-B','-c',script],capture_output=True,text=True,timeout=15)
                log=p.stdout+p.stderr
                (folder/'run.log').write_text(log)
                row[phase]={'exit':p.returncode,'log':str(folder/'run.log'),'output':log}
                identities=([test+" (case_id="+repr(case)+")" for case in ('wrong-generation','wrong-kind','extra-field')]
                            if name=='permit-gate' else [test])
                rows=[line[len('RESULT_JSON='):] for line in p.stdout.splitlines() if line.startswith('RESULT_JSON=')]
                result=json.loads(rows[0]) if len(rows)==1 else None
                good=valid_result(p.returncode,result,test,[] if phase=='baseline' else identities,assertion)
                row[phase].update(valid=good,result=result)
            except subprocess.TimeoutExpired:
                row[phase]={'exit':124,'valid':False,'timeout':True}
        row['verdict']='DETECTED' if all(row[x]['valid'] for x in ('baseline','mutant')) else 'INVALID_OR_SURVIVED'
        results.append(row)
    report={'results':results,'hashes':{f:hashlib.sha256((ROOT/f).read_bytes()).hexdigest() for f in files}}
    (out/'results.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(report,indent=2)); print('evidence='+str(out))
    return 0 if all(r['verdict']=='DETECTED' for r in results) else 1

if __name__=='__main__': raise SystemExit(main())
