#!/usr/bin/env python3
"""Opt-in Linux lifecycle owner. Not production admission or an external owner."""
import os
from pathlib import Path
import hashlib
import platform
import signal
import subprocess
import sys
import threading
import tempfile

class Blocked(RuntimeError): pass

AUDITED_MACHINE = 'aarch64'
AUDITED_GLIBC = '2.41'
AUDITED_SUBPROCESS_SHA256 = '6628ffdd65c093a6c08cae01ffe82877d3ced515aac9e7be0cff16512c30a7d9'

class FDLedger:
    def __init__(self):
        self.fds = {}
        self.failed = False
        self.closed = []
    def add(self, name, fd):
        self.fds[name] = fd
        return fd
    def close(self, name):
        fd = self.fds.pop(name, None)  # consume before close: never retry an integer
        if fd is None: return
        try:
            os.close(fd)
            self.closed.append(name)
        except BaseException:
            self.failed = True
    def close_all(self):
        for name in list(self.fds): self.close(name)

def preflight(startup, lifetime):
    if (sys.platform != 'linux' or sys.implementation.name != 'cpython'
            or sys.version_info[:3] != (3, 14, 7)):
        raise Blocked('BLOCKED: Linux CPython 3.14.7 development target required')
    if (type(startup) not in (int,float) or not 0 < startup <= lifetime <= 300):
        raise Blocked('BLOCKED: explicit startup budget within lifetime required')
    if not all(hasattr(os, n) for n in ('waitid','WNOWAIT','WNOHANG','WEXITED','P_PID','CLD_KILLED')):
        raise Blocked('BLOCKED: observer unavailable')
    if signal.getsignal(signal.SIGCHLD) != signal.SIG_DFL or threading.active_count() != 1 or subprocess._active:
        raise Blocked('BLOCKED: sole-reaper precondition')
    # Linux exports ignored/caught dispositions, including native-installed ignore.
    fields = dict(line.split(':',1) for line in Path('/proc/self/status').read_text().splitlines() if ':' in line)
    mask = 1 << (signal.SIGCHLD-1)
    if int(fields['SigIgn'],16)&mask or int(fields['SigCgt'],16)&mask:
        raise Blocked('BLOCKED: SIGCHLD disposition')
    # Explicit Linux/glibc 64-bit development ABI, not a portable ctypes promise.
    import ctypes
    import platform
    if (platform.machine() != AUDITED_MACHINE or platform.libc_ver() != ('glibc', AUDITED_GLIBC)
            or ctypes.sizeof(ctypes.c_void_p) != 8):
        raise Blocked('BLOCKED: unaudited sigaction ABI')
    try:
        subprocess_hash = hashlib.sha256(Path(subprocess.__file__).read_bytes()).hexdigest()
    except (OSError, TypeError):
        raise Blocked('BLOCKED: audited subprocess source unavailable')
    if subprocess_hash != AUDITED_SUBPROCESS_SHA256:
        raise Blocked('BLOCKED: unaudited subprocess source')
    class Sigaction(ctypes.Structure):
        _fields_ = [('handler',ctypes.c_void_p),('mask',ctypes.c_ulong*16),
                    ('flags',ctypes.c_int),('restorer',ctypes.c_void_p)]
    action = Sigaction()
    libc = ctypes.CDLL(None,use_errno=True)
    if libc.sigaction(signal.SIGCHLD,None,ctypes.byref(action)) != 0 or action.handler is not None or action.flags & 2:
        raise Blocked('BLOCKED: native SIGCHLD disposition')
    # Query without consuming any child and before spawning the first one.
    try:
        os.waitid(os.P_PID, os.getpid(), os.WEXITED|os.WNOHANG|os.WNOWAIT)
    except ChildProcessError: pass
    else: raise Blocked('BLOCKED: observer precondition')
    for fd in (0,1,2): os.fstat(fd)

import json
import select
import time
import uuid
from worker_bootstrap import ProtocolError, encode, strict, control, payload_valid, MAX_FRAME

def prepare_empty_home():
    """Create the one-use, application-owned Hermes home for this slot."""
    from worker import write_home_config
    home_value = os.environ.get('POOL_HERMES_HOME')
    generated = home_value is None
    if generated:
        root = os.environ.get('TMPDIR', tempfile.gettempdir())
        home_value = tempfile.mkdtemp(prefix='worker-hermes-', dir=root)
    if (not isinstance(home_value, str) or not os.path.isabs(home_value)
            or home_value in ('/', '/tmp') or (not generated and Path(home_value).exists())):
        raise Blocked('fresh per-slot HERMES_HOME required')
    home = Path(home_value)
    if not generated:
        home.mkdir(mode=0o700)
    # Same generated dict the default entry writes and both factories gate on: 0600 by
    # construction (O_EXCL), no capability or operator key can be placed here.
    write_home_config(home)
    os.environ['POOL_HERMES_HOME'] = str(home)
    return str(home)

class SpawnOnly(subprocess.Popen):
    def __del__(self): pass
    def forbidden(self, *args, **kwargs): raise RuntimeError('owner waitid only')
    poll = wait = communicate = send_signal = terminate = kill = __enter__ = __exit__ = forbidden
    _wait = _internal_poll = _try_wait = forbidden
    def _handle_exitstatus(self, status, *args, **kwargs):
        self.constructor_exit = {'pid':self.pid, 'raw_status':status, 'source':'constructor_exec_error'}
        return super()._handle_exitstatus(status, *args, **kwargs)

class Owner:
    def __init__(self, ref, roots, startup, deadline):
        self.ref, self.roots = ref, roots
        self.startup, self.deadline = startup, deadline
        self.generation = uuid.uuid4().hex
        self.ledger = FDLedger()
        self.proc = None
        self.pid = None
        self.owned = False
        self.failed = False
        self.state = 'NEW'
        self.phase = 'spawn_waiting'
        self.buffer = b''
        self.pending = b''
        self.candidate = None
        self.exit_record = None
        self.signals = 0
        self.permitted = False
        self.start_deadline = None
        self.clean_deadline = None
        self.final_deadline = None
        self.events = []

    def note(self, event):
        self.events.append((event, time.monotonic_ns()))

    def launch(self):
        # All checks happen before any pipe/spawn. No callback or factory import here.
        if time.monotonic_ns() >= self.deadline: raise Blocked('expired before spawn')
        preflight(self.startup, max(self.startup, (self.deadline-time.monotonic_ns())/1e9))
        self.start_deadline = min(self.deadline, time.monotonic_ns()+int(self.startup*1e9))
        self.state = 'CONSTRUCTING'
        try:
            r,w = os.pipe(); self.ledger.add('child_read',r); self.ledger.add('input',w)
            r,w = os.pipe(); self.ledger.add('output',r); self.ledger.add('child_write',w)
            self.ledger.add('null',os.open(os.devnull, os.O_WRONLY))
            self.proc = object.__new__(SpawnOnly)  # strong reference BEFORE __init__
            argv = [sys.executable,'-I','-S','-B',str(Path(__file__).with_name('worker_bootstrap.py')),
                    self.ref,self.generation,str(self.start_deadline),str(self.deadline),json.dumps(self.roots)]
            # Only the lifecycle entry's explicitly trusted, non-secret slot settings cross
            # the bootstrap boundary.  Do not inherit operator credentials, proxies, or PATH.
            child_env = {
                'HOME': os.environ.get('POOL_HOME') or os.environ.get('HOME', '/nonexistent'),
                'HERMES_HOME': os.environ.get('POOL_HERMES_HOME') or os.environ.get('HERMES_HOME', ''),
                'TMPDIR': os.environ.get('TMPDIR', '/tmp'),
            }
            for name in ('POOL_MODEL', 'POOL_MCP_URL', 'POOL_PROVIDER_URL', 'POOL_RUNTIME_ROOTS', 'POOL_DISCOVERY_AUDIT_FILE'):
                value = os.environ.get(name)
                if value is not None:
                    child_env[name] = value
            if not child_env['HERMES_HOME']:
                raise Blocked('fresh per-slot HERMES_HOME required')
            subprocess.Popen.__init__(self.proc, argv, stdin=self.ledger.fds['child_read'],
                stdout=self.ledger.fds['child_write'],stderr=self.ledger.fds['null'],
                bufsize=0,close_fds=True,start_new_session=True,
                env=child_env)
            self.pid = self.proc.pid
            for name in ('child_read','child_write','null'): self.ledger.close(name)
            if self.ledger.failed: raise ProtocolError('close')
            if type(self.pid) is not int or self.pid <= 1: raise ProtocolError('pid')
            observed = self.observe()
            if observed is not None or os.getpgid(self.pid) != self.pid or os.getsid(self.pid) != self.pid:
                raise ProtocolError('identity')
            for name in ('input','output'): os.set_blocking(self.ledger.fds[name],False)
            self.owned = True
            self.state = 'OWNED'
            self.note('owned')
        except BaseException:
            self.failed = True
            self.state = 'SPAWN_UNCERTAIN'
            if self.proc is not None:
                pid = getattr(self.proc,'pid',None)
                if type(pid) is int and pid > 1: self.pid = pid
            for name in ('child_read','child_write','null'): self.ledger.close(name)
            self.cleanup()
            raise

    def observe(self):
        return os.waitid(os.P_PID,self.pid,os.WEXITED|os.WNOHANG|os.WNOWAIT)

    def permit(self):
        if not self.owned or self.phase != 'permit' or self.permitted or time.monotonic_ns() >= self.start_deadline:
            raise ProtocolError('permit-state')
        self.permitted = True
        self.pending = encode({'kind':'start_factory','generation':self.generation},4095)
        self.phase = 'ready'
        self.note('permit')

    def run(self, text):
        if self.phase != 'idle' or self.pending: raise ProtocolError('run-state')
        self.pending = encode({'kind':'run','generation':self.generation,'turn':1,'text':text})
        self.phase = 'terminal_candidate'

    def flush(self):
        if not self.pending: return
        try:
            count = os.write(self.ledger.fds['input'], self.pending)
            if count == 0: raise ProtocolError('zero-write')
            self.pending = self.pending[count:]
        except (BlockingIOError,InterruptedError): pass

    def receive(self):
        limit = MAX_FRAME if self.phase == 'terminal_candidate' else 4095
        try: chunk = os.read(self.ledger.fds['output'],min(65536,limit+1-len(self.buffer)))
        except (BlockingIOError,InterruptedError): return []
        if not chunk: raise ProtocolError('eof')
        self.buffer += chunk
        found = []
        while b'\n' in self.buffer:
            line,self.buffer = self.buffer.split(b'\n',1)
            limit = MAX_FRAME if self.phase == 'terminal_candidate' else 4095
            if len(line)+1 > limit: raise ProtocolError('size')
            frame = strict(line)
            phase = self.phase
            if phase in ('spawn_waiting','ready'):
                if not control(frame,phase,self.generation) or time.monotonic_ns() >= self.start_deadline:
                    raise ProtocolError('control')
                self.phase = 'permit' if phase == 'spawn_waiting' else 'idle'
            elif phase in ('terminal_candidate','holding'):
                keys = {'kind','generation','turn'} | ({'payload'} if phase == 'terminal_candidate' else set())
                if (type(frame) is not dict or set(frame) != keys or frame['kind'] != phase
                        or frame['generation'] != self.generation or type(frame['turn']) is not int or frame['turn'] != 1):
                    raise ProtocolError('terminal-schema')
                if phase == 'terminal_candidate':
                    if not payload_valid(frame['payload']): raise ProtocolError('payload')
                    self.candidate = frame['payload']; self.phase = 'holding'
                else: self.phase = 'held'
            else: raise ProtocolError('extra-frame')
            self.note(phase); found.append(phase)
        limit = MAX_FRAME if self.phase == 'terminal_candidate' else 4095
        if len(self.buffer) >= limit or (self.phase == 'held' and self.buffer): raise ProtocolError('trailing')
        return found

    def await_kind(self, kind):
        while True:
            if any(e[0] == kind for e in self.events): return
            until = self.start_deadline if kind in ('spawn_waiting','ready') else self.deadline
            if time.monotonic_ns() >= until: raise ProtocolError('deadline')
            rd,wr,_ = select.select([self.ledger.fds['output']], [self.ledger.fds['input']] if self.pending else [],[],.01)
            if wr: self.flush()
            if rd: self.receive()

    def cleanup(self):
        if self.clean_deadline is not None: return self.state
        self.clean_deadline = min(time.monotonic_ns(),self.deadline)+1_500_000_000
        self.state = 'CLEANING'
        self.pending = b''
        killed = False
        absent = False
        constructor_exit = getattr(self.proc,'constructor_exit',None)
        try:
            if self.pid is None or constructor_exit is not None: self.failed = True
            elif time.monotonic_ns() < self.clean_deadline:
                observed = self.observe()
                self.note('observe')
                if observed is None:
                    self.signals += 1
                    # Pre-OWNED failure permits only an exact direct-child signal.
                    if self.owned: os.killpg(self.pid,signal.SIGKILL)
                    else: os.kill(self.pid,signal.SIGKILL)
                    killed = True
                    self.note('signal')
                else: self.failed = True
                while time.monotonic_ns() < self.clean_deadline:
                    try: record = os.waitid(os.P_PID,self.pid,os.WEXITED|os.WNOHANG)
                    except InterruptedError: continue
                    if record is not None:
                        if record.si_pid != self.pid: raise ProtocolError('wrong-pid')
                        self.exit_record = {'pid':record.si_pid,'code':record.si_code,'status':record.si_status,'source':'waitid'}
                        self.note('reap')
                        break
                    time.sleep(min(.005,max(0,(self.clean_deadline-time.monotonic_ns())/1e9)))
                if self.exit_record is None: self.failed = True
                elif killed and self.owned:
                    if self.exit_record['code'] != os.CLD_KILLED or self.exit_record['status'] != signal.SIGKILL:
                        self.failed = True
                    while time.monotonic_ns() < self.clean_deadline:
                        try: os.killpg(self.pid,0)
                        except ProcessLookupError: absent = True; self.note('absent'); break
                        time.sleep(.005)
        except (OSError,ProtocolError):
            self.failed = True
        finally:
            # Each fd gets one independent attempt, even after any other failure.
            try:
                self.ledger.close_all()
            except BaseException:
                self.failed = True
            self.note('closed')
            # Failure is absorbing, but EOF may let a trusted direct child exit.
            # Only this owner may reap it, within the SAME cleanup deadline.
            if self.failed and self.pid is not None and constructor_exit is None and self.exit_record is None:
                while time.monotonic_ns() < self.clean_deadline:
                    try: record = os.waitid(os.P_PID,self.pid,os.WEXITED|os.WNOHANG)
                    except InterruptedError: continue
                    except OSError: break
                    if record is not None:
                        if record.si_pid == self.pid:
                            self.exit_record = {'pid':record.si_pid,'code':record.si_code,'status':record.si_status,'source':'waitid'}
                            self.note('failure-reap')
                        break
                    time.sleep(.005)
            if self.ledger.failed or time.monotonic_ns() >= self.clean_deadline: self.failed = True
            if not (killed and absent and self.owned): self.failed = True
            self.state = 'FAILED' if self.failed else 'GREEN'
            self.final_deadline = min(time.monotonic_ns(),self.clean_deadline)+250_000_000
        return self.state


def serve(factory_ref='worker:lifecycle_factory', roots=None):
    """Explicit development entry; the default worker entry keeps its own factory.

    This entry selects the explicitly configured lifecycle factory, not the default
    entry's fixed-destination factory. Neither one receives a capability.
    """
    from worker import Session, Denied, lifetime_seconds
    from worker_bootstrap import write
    from gate import EXACT_TOOLS
    try:
        lifetime = lifetime_seconds()
        raw = os.environ.get('POOL_STARTUP_SECONDS','')
        # Trusted configuration only; no default and no RPC budget input.
        if not raw or not raw.isascii() or not raw.isdigit(): raise Blocked('startup required')
        startup = int(raw)
        preflight(startup,lifetime)
    except (Blocked, ValueError, OSError):
        os.write(2,b'BLOCKED: lifecycle preflight\n')
        return 2
    deadline = time.monotonic_ns()+lifetime*1_000_000_000
    roots = roots or [str(Path(__file__).resolve().parent)]
    runtime_roots = os.environ.get('POOL_RUNTIME_ROOTS')
    if runtime_roots:
        try:
            extra_roots = json.loads(runtime_roots)
        except (TypeError, ValueError):
            raise Blocked('runtime roots must be JSON')
        if (type(extra_roots) is not list or
                any(type(root) is not str or not os.path.isabs(root) for root in extra_roots)):
            raise Blocked('absolute runtime roots required')
        roots = list(dict.fromkeys([*roots, *extra_roots]))
    prepare_empty_home()
    session = Session(None,os.environ.get('POOL_INCARNATION','local-test'))
    owner = None
    creating = None
    stop_receipt = None
    stopping = False
    protocol_failed = False
    incoming = b''
    code = 0
    def stop(signum,frame):
        nonlocal stopping
        stopping = True
    previous = signal.signal(signal.SIGTERM,stop)
    os.set_blocking(0,False); os.set_blocking(1,False)
    def emit(frame, until=None):
        write(1,encode(frame),until if until is not None else min(deadline,time.monotonic_ns()+250_000_000))
    def reply(rid,result,until=None): emit({'jsonrpc':'2.0','id':rid,'result':result},until)
    def error(rid,until=None,number=-32001): emit({'jsonrpc':'2.0','id':rid,'error':{'code':number,'message':'worker denied'}},until)
    def parse_client(line):
        frame = strict(line)
        if (type(frame) is not dict or set(frame) != {'jsonrpc','id','method','params'}
                or frame['jsonrpc'] != '2.0' or type(frame['id']) not in (int,str)
                or type(frame['method']) is not str or type(frame['params']) is not dict):
            raise Denied('envelope')
        return frame
    def drain_publication_control(until):
        """Bounded, non-blocking read before the candidate publication point."""
        nonlocal incoming, stopping, stop_receipt, protocol_failed
        end = min(until, time.monotonic_ns()+25_000_000)
        frames = 0
        total = len(incoming)
        while True:
            if time.monotonic_ns() >= end or total >= 65536:
                return False  # exact byte/time cap is conservatively uncertain
            if frames >= 128:
                # No read after exhaustion. Buffered fragments or kernel readiness
                # (including EOF) make completeness unprovable, so fail closed.
                return not incoming and not select.select([0], [], [], 0)[0]
            if b'\n' not in incoming:
                try:
                    chunk = os.read(0, min(65536-total, MAX_FRAME+1-len(incoming)))
                except BlockingIOError:
                    return True  # incomplete open frame is not an admitted control
                except InterruptedError:
                    continue
                if not chunk:
                    if incoming: protocol_failed = True
                    return not incoming
                incoming += chunk
                total += len(chunk)
                # Recheck ALL bounds before parsing or any response write.
                continue
            while b'\n' in incoming and frames < 128 and time.monotonic_ns() < end:
                line, incoming = incoming.split(b'\n', 1)
                if len(line)+1 > MAX_FRAME: raise ProtocolError('client-size')
                rid = None
                try:
                    frame = parse_client(line)
                    rid, method, params = frame['id'], frame['method'], frame['params']
                    if method in ('session.interrupt','session.close'):
                        if session.sid is None or params != {'session_id':session.sid}:
                            raise Denied('session')
                        stop_receipt = (rid, 'interrupted' if method == 'session.interrupt' else 'closed')
                        stopping = True
                    elif method == 'health':
                        reply(rid, session.handle(method, params), end)
                    else:
                        raise Denied('publication')
                except Denied:
                    error(rid, end)
                frames += 1
            if len(incoming) >= MAX_FRAME: raise ProtocolError('client-size')
    class Proxy:
        tools = [{'type':'function','function':{'name':n}} for n in EXACT_TOOLS]
        valid_tool_names = EXACT_TOOLS
    try:
        while not stopping and time.monotonic_ns() < deadline:
            if owner is not None and owner.phase in ('spawn_waiting','permit','ready') and time.monotonic_ns() >= owner.start_deadline:
                protocol_failed = True; break
            readers = [0]+([owner.ledger.fds['output']] if owner is not None else [])
            writers = [owner.ledger.fds['input']] if owner is not None and owner.pending else []
            rd,wr,_ = select.select(readers,writers,[],.01)
            # Process client control before child progress, including simultaneous readiness.
            if 0 in rd:
                chunk = os.read(0,min(65536,MAX_FRAME+1-len(incoming)))
                if not chunk:
                    if incoming: protocol_failed = True
                    stopping = True; break
                incoming += chunk
                while b'\n' in incoming:
                    line,incoming = incoming.split(b'\n',1)
                    if len(line)+1 > MAX_FRAME: raise ProtocolError('client-size')
                    rid = None
                    try:
                        frame = parse_client(line)
                        rid,method,params = frame['id'],frame['method'],frame['params']
                        if method == 'session.create':
                            if owner is not None or session.spent or not (not params or params == {'cwd':'/workspace'}): raise Denied('create')
                            owner = Owner(factory_ref,roots,startup,deadline)
                            creating = rid
                            owner.launch()
                        elif method in ('session.interrupt','session.close'):
                            if session.sid is None or params != {'session_id':session.sid}: raise Denied('session')
                            stop_receipt = (rid, 'interrupted' if method == 'session.interrupt' else 'closed')
                            stopping = True; break
                        elif method in ('health','prompt.submit'):
                            result = session.handle(method,params)
                            reply(rid,result)
                            if method == 'prompt.submit':
                                owner.run(session.pending_text); session.pending_text = None
                        else: error(rid,number=-32601)
                    except Denied: error(rid)
                if len(incoming) >= MAX_FRAME: raise ProtocolError('client-size')
            if stopping or time.monotonic_ns() >= deadline: break
            if owner is not None:
                if owner.ledger.fds['input'] in wr: owner.flush()
                if owner.ledger.fds['output'] in rd:
                    kinds = owner.receive()
                    if 'spawn_waiting' in kinds:
                        if stopping or time.monotonic_ns() >= deadline: break
                        owner.permit()
                    if 'ready' in kinds:
                        session.factory = Proxy
                        result = session.handle('session.create',{})
                        reply(creating,result); creating = None
                    if owner.phase == 'held': break
    except (ProtocolError,OSError,ValueError,RecursionError):
        protocol_failed = True
        code = 1
    finally:
        if owner is not None:
            cleaned = owner.cleanup()
            until = owner.final_deadline
            if cleaned != 'GREEN': code = 1
            try:
                publication_interrupted = stopping or time.monotonic_ns() >= deadline
                publication_uncertain = False
                if cleaned == 'GREEN' and not protocol_failed and session.submitted:
                    publication_uncertain = not drain_publication_control(until)
                    # This is the publication linearization point: later input
                    # cannot retroactively revoke the already-held candidate.
                    publication_interrupted = stopping or time.monotonic_ns() >= deadline
                if creating is not None: error(creating,until)
                if protocol_failed:
                    code = 1
                elif cleaned == 'GREEN':
                    if publication_uncertain: code = 1
                    if stop_receipt is not None and not publication_uncertain: reply(stop_receipt[0],{'status':stop_receipt[1]},until)
                    if session.submitted:
                        payload = ({'status':'error','text':''} if publication_uncertain else
                                   {'status':'interrupted','text':''} if publication_interrupted else owner.candidate)
                        if not publication_interrupted and owner.phase != 'held':
                            code = 1
                        elif payload_valid(payload):
                            emit({'jsonrpc':'2.0','method':'event','params':{'session_id':session.sid,
                                'type':'message.complete','payload':payload,'seq':1}},until)
                        else: code = 1
            except (ProtocolError,OSError,ValueError): code = 1
        session.spent = True
        signal.signal(signal.SIGTERM, signal.SIG_IGN if stopping else previous)
    return code

if __name__ == '__main__':
    raise SystemExit(serve())
