"""Lifecycle ownership tests: deterministic seams are not real OS failures."""
import importlib.util
import os
import signal
from pathlib import Path
import sys
import time
import unittest
from unittest.mock import patch

POOL = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(POOL))

class CleanupTests(unittest.TestCase):
    def test_fd_close_baseexception_attempts_each_fd_and_sticks(self):
        import worker_lifecycle as lc
        ledger = lc.FDLedger()
        r, w = os.pipe()
        ledger.add('read', r); ledger.add('write', w)
        calls = []
        real_close = os.close
        def faulty(fd):
            calls.append(fd)
            if fd == r:
                raise BaseException('injected non-OSError close')
            return real_close(fd)
        with patch.object(lc.os, 'close', faulty):
            ledger.close_all()
        self.assertEqual(calls, [r, w], 'CLOSE_BASEEXCEPTION_BOTH')
        self.assertTrue(ledger.failed, 'CLOSE_BASEEXCEPTION_STICKY')
        self.assertEqual(ledger.fds, {}, 'CLOSE_BASEEXCEPTION_CONSUMED')
        real_close(r)


    def test_fd_close_consumed_before_error(self):
        self.assertTrue((POOL/'worker_lifecycle.py').exists(), 'OWNER_REQUIRED')
        import worker_lifecycle as lc
        ledger = lc.FDLedger()
        r, w = os.pipe()
        ledger.add('read', r); ledger.add('write', w)
        calls = []
        real_close = os.close
        def faulty(fd):
            calls.append(fd)
            real_close(fd)
            if fd == r: raise OSError(5, 'injected after close')
        with patch.object(lc.os, 'close', faulty):
            ledger.close_all(); ledger.close_all()
        self.assertEqual(calls, [r, w], 'CLOSE_ONCE_AND_BOTH')
        self.assertTrue(ledger.failed, 'CLOSE_ERROR_STICKY')

    def test_platform_rejected_before_spawn(self):
        import worker_lifecycle as lc
        with patch.object(lc.sys, 'platform', 'darwin'), patch.object(lc.subprocess.Popen, '__init__') as spawn:
            with self.assertRaises(lc.Blocked): lc.preflight(1, 2)
            spawn.assert_not_called()

    def test_unverified_machine_rejected_before_spawn(self):
        import platform
        import worker_lifecycle as lc
        with patch.object(lc.sys, 'version_info', (3,14,7)), \
                patch.object(platform, 'machine', return_value='x86_64'), \
                patch.object(platform, 'libc_ver', return_value=('glibc', '2.41')), \
                patch.object(lc.subprocess.Popen, '__init__') as spawn:
            with self.assertRaises(lc.Blocked): lc.preflight(1, 2)
            spawn.assert_not_called()

    def test_unverified_subprocess_source_rejected_before_spawn(self):
        import platform
        import worker_lifecycle as lc
        import tempfile
        from worker_test_paths import scratch_root
        with tempfile.NamedTemporaryFile(dir=scratch_root()) as source, \
                patch.object(lc.sys, 'version_info', (3,14,7)), \
                patch.object(platform, 'machine', return_value='aarch64'), \
                patch.object(platform, 'libc_ver', return_value=('glibc', '2.41')), \
                patch.object(lc.subprocess, '__file__', source.name), \
                patch.object(lc.subprocess.Popen, '__init__') as spawn:
            with self.assertRaises(lc.Blocked): lc.preflight(1, 2)
            spawn.assert_not_called()

class LinuxOwnerTests(unittest.TestCase):
    def owner(self):
        import worker_lifecycle as lc
        lc.preflight(1,4)
        return lc.Owner('worker_lifecycle_fixture:factory',[str(POOL),str(POOL/'tests')],1,time.monotonic_ns()+4_000_000_000)

    def test_owner_close_baseexception_is_failed_and_deadline_is_finalized(self):
        import worker_lifecycle as lc
        owner = self.owner()
        owner.launch(); owner.await_kind('spawn_waiting')
        victim = owner.ledger.fds['input']
        other = owner.ledger.fds['output']
        calls = []
        real_close = lc.os.close
        def faulty(fd):
            calls.append(fd)
            if fd == victim:
                raise ValueError('injected non-OSError close')
            return real_close(fd)
        with patch.object(lc.os, 'close', faulty):
            result = owner.cleanup()
        self.assertEqual(result, 'FAILED', 'CLOSE_BASEEXCEPTION_OWNER_FAILED')
        self.assertEqual(owner.state, 'FAILED', 'CLOSE_BASEEXCEPTION_NOT_CLEANING')
        self.assertIsNotNone(owner.final_deadline, 'CLOSE_BASEEXCEPTION_NO_FINAL_DEADLINE')
        self.assertEqual(calls, [victim, other], 'CLOSE_BASEEXCEPTION_OWNER_BOTH')
        self.assertEqual(owner.ledger.fds, {})

    def test_constructor_eio_retains_partial_no_permit(self):
        import worker_lifecycle as lc
        owner = self.owner()
        original = lc.SpawnOnly._close_pipe_fds
        def fault(proc,*args):
            original(proc,*args)
            raise OSError(5,'injected post-super')
        with patch.object(lc.SpawnOnly,'_close_pipe_fds',fault):
            with self.assertRaises(OSError): owner.launch()
        self.assertIsNotNone(owner.proc, 'PARTIAL_STRONG_REFERENCE')
        self.assertFalse(owner.permitted,'CONSTRUCTOR_NO_PERMIT')
        self.assertEqual(owner.state,'FAILED','CONSTRUCTOR_FAILED')
        self.assertEqual(owner.exit_record['status'],signal.SIGKILL)
        self.assertEqual(owner.ledger.fds,{})
        self.assertFalse(owner.owned)

    def test_permission_failure_reaps_after_eof_without_success(self):
        import worker_lifecycle as lc
        owner = self.owner(); owner.launch(); owner.await_kind('spawn_waiting')
        with patch.object(lc.os,'killpg',side_effect=PermissionError(1,'injected')):
            self.assertEqual(owner.cleanup(),'FAILED','PERMISSION_NOT_GREEN')
        self.assertEqual(owner.signals,1,'PERMISSION_NO_RETRY')
        self.assertEqual(owner.ledger.fds,{})
        # Cleanup must still perform sole-owner bounded reap after close caused EOF.
        self.assertIsNotNone(owner.exit_record,'FAILED_PATH_EXACT_REAP')
        self.assertEqual(owner.cleanup(),'FAILED')

    def test_close_error_after_kill_is_sticky(self):
        import worker_lifecycle as lc
        owner=self.owner(); owner.launch(); owner.await_kind('spawn_waiting')
        original=lc.os.close; names=[]
        def fault(fd):
            names.append(fd); original(fd)
            raise OSError(5,'injected after close')
        with patch.object(lc.os,'close',fault):
            self.assertEqual(owner.cleanup(),'FAILED','OWNER_CLOSE_NOT_GREEN')
        self.assertEqual(len(names),2,'BOTH_PARENT_ENDPOINTS')
        self.assertIsNotNone(owner.exit_record)
        self.assertEqual(owner.signals,1)

    def test_large_encoded_candidate(self):
        import worker_lifecycle as lc
        for text,char in [('astral','\U0001f642'),('cjk','中'),('surrogate','\ud800')]:
            with self.subTest(text=text):
                owner=self.owner(); owner.launch()
                try:
                    owner.await_kind('spawn_waiting'); owner.permit(); owner.await_kind('ready')
                    owner.run(text); owner.await_kind('holding')
                    self.assertEqual(owner.candidate,{'status':'complete','text':char*65536},'ENCODED_CHARACTER_BOUND')
                    self.assertEqual(owner.cleanup(),'GREEN')
                finally: owner.cleanup()

    def test_waitid_is_only_owned_reaper(self):
        import worker_lifecycle as lc
        import gc
        calls=[]
        def profile(frame,event,arg):
            if event == 'c_call' and getattr(arg,'__name__','') in ('waitpid','waitid'): calls.append(arg.__name__)
        owner=self.owner()
        sys.setprofile(profile)
        try:
            owner.launch(); owner.await_kind('spawn_waiting'); owner.cleanup()
            proc=owner.proc
            self.assertIsNone(proc.returncode)
            owner.proc=None; del proc; gc.collect()
        finally: sys.setprofile(None)
        self.assertNotIn('waitpid',calls,'NO_OWNED_WAITPID')
        self.assertIn('waitid',calls)

    def test_held_requires_exact_turn_and_schema(self):
        import worker_lifecycle as lc
        from worker_bootstrap import encode, ProtocolError
        for frame in ({'kind':'holding','turn':True}, {'kind':'holding','turn':1,'extra':1}, {'kind':'holding','turn':2}):
            owner=self.owner(); r,w=os.pipe(); owner.ledger.add('output',r)
            owner.phase='holding'
            frame['generation']=owner.generation
            os.write(w,encode(frame))
            try:
                with self.assertRaises(ProtocolError,msg='STRICT_HOLDING'): owner.receive()
            finally: os.close(w); owner.ledger.close_all()

    def test_candidate_alone_never_held(self):
        from worker_bootstrap import encode
        owner=self.owner(); r,w=os.pipe(); owner.ledger.add('output',r)
        owner.phase='terminal_candidate'
        os.write(w,encode({'kind':'terminal_candidate','generation':owner.generation,'turn':1,
                           'payload':{'status':'complete','text':'fixture-only'}}))
        try:
            owner.receive()
            self.assertEqual(owner.phase,'holding','CANDIDATE_NOT_HELD')
        finally: os.close(w); owner.ledger.close_all()

    def test_unknown_pid_never_signals(self):
        import worker_lifecycle as lc
        owner=self.owner(); owner.failed=True
        with patch.object(lc.os,'kill') as kill, patch.object(lc.os,'killpg') as group:
            self.assertEqual(owner.cleanup(),'FAILED','UNKNOWN_NOT_GREEN')
            kill.assert_not_called(); group.assert_not_called()
        self.assertEqual(owner.signals,0)

    def test_reap_timeout_no_resignal(self):
        import worker_lifecycle as lc
        owner=self.owner(); owner.launch(); owner.await_kind('spawn_waiting')
        real=lc.os.waitid
        def waitid(kind,pid,flags):
            if flags & os.WNOWAIT: return real(kind,pid,flags)
            return None
        with patch.object(lc.os,'waitid',waitid):
            self.assertEqual(owner.cleanup(),'FAILED','REAP_TIMEOUT_NOT_GREEN')
        self.assertEqual(owner.signals,1,'TIMEOUT_NO_RESIGNAL')
        self.assertIsNone(owner.exit_record)
        # Independent fixture reap after recording FAILED; never rewrite verdict.
        record=real(os.P_PID,owner.pid,os.WEXITED|os.WNOHANG)
        self.assertIsNotNone(record)
        self.assertEqual(record.si_status,signal.SIGKILL)
        self.assertEqual(owner.cleanup(),'FAILED')

    def test_expired_before_launch_never_spawns(self):
        import worker_lifecycle as lc
        owner=self.owner(); owner.deadline=time.monotonic_ns()-1
        with patch.object(lc.subprocess.Popen,'__init__',side_effect=lc.Blocked('spawn reached')) as spawn:
            with self.assertRaises(lc.Blocked,msg='EXPIRED_NO_SPAWN'): owner.launch()
            self.assertEqual(spawn.call_count,0,'EXPIRED_NO_SPAWN')
        self.assertEqual(owner.ledger.fds,{})

    def test_native_nocldwait_refused_before_spawn(self):
        import ctypes
        import worker_lifecycle as lc
        libc=ctypes.CDLL(None,use_errno=True)
        old=ctypes.create_string_buffer(256); current=ctypes.create_string_buffer(256)
        self.assertEqual(libc.sigaction(signal.SIGCHLD,None,old),0)
        ctypes.memmove(current,old,256)
        flags=ctypes.c_int.from_buffer(current,136)
        flags.value |= 2  # Linux/glibc SA_NOCLDWAIT, target ABI only
        try:
            self.assertEqual(libc.sigaction(signal.SIGCHLD,current,None),0)
            with self.assertRaises(lc.Blocked,msg='NATIVE_NOCLDWAIT_REJECTED'): lc.preflight(1,4)
        finally: self.assertEqual(libc.sigaction(signal.SIGCHLD,old,None),0)

    def test_owned_holding_cleanup(self):
        import worker_lifecycle as lc
        self.assertTrue(hasattr(lc, 'Owner'), 'LIFECYCLE_OWNER_REQUIRED')
        lc.preflight(1, 4)
        owner = lc.Owner('worker_lifecycle_fixture:factory', [str(POOL), str(POOL/'tests')], 1, time.monotonic_ns()+4_000_000_000)
        try:
            owner.launch()
            self.assertTrue(owner.owned, 'OWNED_BEFORE_PERMIT')
            owner.await_kind('spawn_waiting')
            owner.permit()
            owner.await_kind('ready')
            owner.run('fixture')
            owner.await_kind('terminal_candidate')
            owner.await_kind('holding')
            self.assertEqual(owner.candidate, {'status':'complete','text':'fixture-only'})
            self.assertEqual(owner.cleanup(), 'GREEN', 'HOLDING_CLEANUP_GREEN')
            self.assertEqual(owner.exit_record['status'], signal.SIGKILL, 'EXACT_KILLED_REAP')
            self.assertEqual(owner.proc.returncode, None)
            self.assertEqual(owner.cleanup(), 'GREEN')
            self.assertEqual(owner.signals, 1, 'ONE_DESTRUCTIVE_SIGNAL')
        finally:
            owner.cleanup()

if __name__ == '__main__': unittest.main()
