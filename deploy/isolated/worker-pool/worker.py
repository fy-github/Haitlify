#!/usr/bin/env python3
"""Restricted application JSON-RPC entry over stdin/stdout, not a full TUI gateway.
One process, one session, one lease. EOF permanently ends the incarnation.
"""
import json
import os
import pathlib
import re
import sys
import select
import signal
import stat
import subprocess
import time
import uuid
from gate import EXACT_TOOLS, guarded_agent_class, require_tools

# Empty per-slot home root fixed by the image/provisioner. Kept as one constant so the
# default-entry regression test can drive the real main() without writing an image path.
WORKER_HOME = '/home/worker'
DEFAULT_MCP_URL = 'http://mcp-edge:8080/mcp'
DEFAULT_PROVIDER_URL = 'http://provider-edge:8080/v1'

class Denied(ValueError): pass

class Session:
    def __init__(self, factory, incarnation):
        self.factory, self.incarnation = factory, incarnation
        self.agent = None
        self.sid = None
        self.spent = False
        self.submitted = False
        self.pending_text = None

    def handle(self, method, params):
        if not isinstance(params, dict): raise Denied('params must be object')
        if method == 'health' and not params:
            return {'alive':True, 'ready':self.agent is not None and not self.spent and not self.submitted}
        if self.spent: raise Denied('incarnation spent')
        if method == 'session.create' and (not params or params == {'cwd':'/workspace'}) and self.agent is None:
            agent = self.factory()
            require_tools(agent.tools)
            if agent.valid_tool_names != EXACT_TOOLS: raise Denied('dispatch mismatch')
            self.agent = agent
            self.sid = uuid.uuid4().hex
            return {'session_id':self.sid, 'stored_session_id':None, 'incarnation':self.incarnation}
        keys = {'prompt.submit':{'session_id','text'}, 'session.interrupt':{'session_id'},
                'session.close':{'session_id'}}
        if method not in keys or set(params) != keys[method] or self.agent is None or params.get('session_id') != self.sid:
            raise Denied('method, fields or session denied')
        if method == 'session.interrupt':
            self.agent.interrupt(); return {'status':'interrupted'}
        if method == 'session.close':
            self.disconnect(); return {'status':'closed'}
        text = params['text']
        if not isinstance(text, str) or not text.strip() or len(text) > 65536:
            raise Denied('invalid prompt')
        if self.submitted: raise Denied('submission already consumed')
        require_tools(self.agent.tools)
        self.submitted = True
        self.pending_text = text
        return {'status':'accepted'}

    def disconnect(self):
        self.spent = True
        if self.agent is not None: self.agent.interrupt()


def home_config():
    """Single source of truth for the generated empty-home configuration.

    The default entry, the opt-in lifecycle entry and both factories read this one dict,
    so a generated home can never contradict the closed-config gate again.
    """
    return {'tools': {'tool_search': {'enabled': 'off'}},
            'platform_toolsets': {'cli': ['mcp-haitlify']},
            'compression': {'enabled': False},
            'agent': {'environment_probe': False}}


def write_home_config(home):
    """Create config.yaml exactly once, owner-only from the first byte (no 0644 window)."""
    data = json.dumps(home_config()).encode()
    fd = os.open(str(home / 'config.yaml'), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        os.write(fd, data)
    finally:
        os.close(fd)


def closed_home_config():
    """Only the application-generated closed config is accepted.

    No tool, toolset, URL, profile, cwd, provider credential, capability or probe setting
    is configurable from the worker home. Exact equality: an extra operator key is a
    configuration channel this entry does not have.
    """
    denied = 'explicit closed tool configuration required'
    try:
        path = pathlib.Path(os.environ['HERMES_HOME']) / 'config.yaml'
        info = path.lstat()  # Never accept the metadata of a symlink's target.
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid()
                or stat.S_IMODE(info.st_mode) != 0o600):
            raise Denied(denied)
        # No-follow closes the lstat/open symlink window; nonblocking prevents a
        # replacement FIFO from hanging the worker before fstat can reject it.
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        try:
            opened = os.fstat(fd)
            if (not stat.S_ISREG(opened.st_mode) or opened.st_uid != os.geteuid()
                    or stat.S_IMODE(opened.st_mode) != 0o600
                    or (opened.st_dev, opened.st_ino) != (info.st_dev, info.st_ino)):
                raise Denied(denied)
            with os.fdopen(fd, 'r', encoding='utf-8', closefd=False) as stream:
                config = json.load(stream)
        finally:
            os.close(fd)
        if config != home_config():
            raise Denied(denied)
        return config
    except (OSError, ValueError, KeyError, AttributeError, RecursionError):
        # Filesystem/parser errors must not expose the home path or config content.
        # Missing POSIX primitives fail closed; production preflight stays Linux-only.
        raise Denied(denied) from None


def pinned_runtime():
    """Locate and pin the image runtime; refuse a source dotenv or any unpinned file."""
    import importlib.util
    from gate import verify_runtime
    spec = importlib.util.find_spec('run_agent')
    if spec is None or not spec.origin: raise Denied('runtime absent')
    source = pathlib.Path(spec.origin).parent
    manifest = json.loads((pathlib.Path(__file__).parent / 'runtime-manifest.json').read_text())
    verify_runtime(source, manifest)
    if (source / '.env').exists(): raise Denied('image contains source dotenv')
    return source


def trusted_url(value, suffix, error):
    """A trusted slot endpoint: absolute http(s) origin plus the one fixed route."""
    if not re.fullmatch(r'https?://[^\s/]+(?::[0-9]+)?' + suffix, value):
        raise Denied(error)
    return value


def discover_mcp_tools(mcp_url):
    """Discover the business tools through the fixed MCP edge.

    The worker sends NO credential — no grant, token or authorization sentinel. Only the
    MCP edge holds the business capability and injects it upstream from its trusted lease,
    so a compromised worker has nothing to replay. The edge never forwards caller auth.
    """
    from tools.mcp_tool_discovery import register_mcp_servers
    names = register_mcp_servers({'haitlify': {'url': mcp_url, 'strict_redirect_headers': True}})
    if frozenset(names) != EXACT_TOOLS or len(names) != 3:
        raise Denied('MCP discovery differs')
    audit_file = os.environ.get('POOL_DISCOVERY_AUDIT_FILE')
    if audit_file:
        if not re.fullmatch(r'/[A-Za-z0-9._/-]{1,200}', audit_file):
            raise Denied('invalid discovery audit path')
        pathlib.Path(audit_file).write_text(json.dumps({'names': sorted(names)}, separators=(',', ':')))
    return names


def factory():
    """Default entry factory: the original closed contract, unchanged in kind.

    Fixed trusted edge destinations only (never an environment-selected endpoint) and no
    worker-side credential. The post-init tool set must already be exactly the three
    business tools, so a wider initial set is rejected rather than narrowed after init.
    """
    # Identity/capability are never in the worker environment; only trusted non-secret
    # model selection. No configurable tools, URLs, profiles, cwd or provider credentials.
    pinned_runtime()
    closed_home_config()
    discover_mcp_tools(DEFAULT_MCP_URL)
    from run_agent import AIAgent
    return guarded_agent_class(AIAgent)(model=os.environ['POOL_MODEL'], provider='custom',
        api_mode='chat_completions', api_key='EDGE-NONSECRET-SENTINEL',
        base_url=DEFAULT_PROVIDER_URL, enabled_toolsets=['mcp-haitlify'],
        skip_memory=True, skip_context_files=True, skip_background_review=True,
        quiet_mode=True, cwd='/workspace', fallback_model=None, max_iterations=20)


def lifecycle_factory():
    """Opt-in lifecycle factory: explicitly configured by the trusted slot environment.

    Separate from `factory` on purpose: the lifecycle entry may point at the slot's own
    edges, while the default entry may not be redirected at all. Both hold no capability.
    """
    pinned_runtime()
    closed_home_config()
    mcp_url = trusted_url(os.environ.get('POOL_MCP_URL', DEFAULT_MCP_URL), '/mcp',
                          'trusted MCP URL required')
    provider_url = trusted_url(os.environ.get('POOL_PROVIDER_URL', DEFAULT_PROVIDER_URL),
                               '/v1', 'trusted provider URL required')
    discover_mcp_tools(mcp_url)
    from run_agent import AIAgent
    return guarded_agent_class(AIAgent)(model=os.environ['POOL_MODEL'], provider='custom',
        api_mode='chat_completions', api_key='EDGE-NONSECRET-SENTINEL',
        base_url=provider_url, enabled_toolsets=['mcp-haitlify'],
        skip_memory=True, skip_context_files=True, skip_background_review=True,
        quiet_mode=True, cwd='/workspace', fallback_model=None, max_iterations=20)


def main():
    if os.environ.get('HERMES_HOME') != WORKER_HOME + '/.hermes' or os.environ.get('HOME') != WORKER_HOME:
        raise SystemExit('BLOCKED: empty per-slot HOME required')
    if not re.fullmatch('[a-zA-Z0-9-]{1,64}', os.environ.get('POOL_INCARNATION','')):
        raise SystemExit('BLOCKED: incarnation required')
    # Configuration is generated inside an empty tmpfs, never copied from host.
    home = pathlib.Path(WORKER_HOME + '/.hermes')
    if home.exists(): raise SystemExit('BLOCKED: reused HOME')
    home.mkdir(mode=0o700)
    write_home_config(home)
    serve()


MAX_FRAME = 1048576
MAX_TEXT = 65536
MAX_LIFETIME = 300


def terminal_payload(result):
    """Pinned turn_finalizer dict, not the RPC receipt or a transcript dump."""
    if not isinstance(result, dict):
        return {'status':'error', 'text':''}
    if result.get('interrupted') is True:
        return {'status':'interrupted', 'text':''}
    text = result.get('final_response')
    if (result.get('completed') is not True
            or any(result.get(flag) is not None and result.get(flag) is not False
                   for flag in ('failed', 'partial'))
            or result.get('error') is not None
            or not isinstance(text, str) or not text.strip() or len(text) > MAX_TEXT):
        return {'status':'error', 'text':''}
    return {'status':'complete', 'text':text}


def child_main(factory_ref):
    # Fresh interpreter: no forked runtime/threads or pickled factory closures.
    # Only this private descriptor carries protocol; suppress upstream native and
    # Python stdout/stderr, including raw exception details.
    output = os.dup(1)
    os.set_inheritable(output, False)
    with open(os.devnull, 'wb') as sink:
        os.dup2(sink.fileno(), 1)
        os.dup2(sink.fileno(), 2)
    def send(value):
        data = (json.dumps(value) + '\n').encode()
        while data:
            data = data[os.write(output, data):]
    try:
        import importlib
        module, name = factory_ref.split(':')
        agent = getattr(importlib.import_module(module), name)()
        require_tools(agent.tools)
        if agent.valid_tool_names != EXACT_TOOLS:
            raise Denied('dispatch mismatch')
        send({'ready':True})
        line = sys.stdin.buffer.readline(MAX_FRAME + 1)
        if not line or len(line) > MAX_FRAME:
            return
        text = json.loads(line)['text']
        require_tools(agent.tools)
        if agent.valid_tool_names != EXACT_TOOLS:
            raise Denied('dispatch mismatch')
        send({'terminal':terminal_payload(agent.run_conversation(text))})
    except BaseException:
        send({'terminal':{'status':'error', 'text':''}})
    finally:
        os.close(output)


class Child:
    tools = [{'type':'function', 'function':{'name':n}} for n in EXACT_TOOLS]
    valid_tool_names = EXACT_TOOLS

    def __init__(self, factory_ref):
        self.proc = subprocess.Popen(
            [sys.executable, '-c', 'import sys; sys.path[:] = ' + repr(sys.path) +
             '; from worker import child_main; child_main(' + repr(factory_ref) + ')'],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            start_new_session=True)
        os.set_blocking(self.proc.stdin.fileno(), False)
        self.buffer = b''
        self.pending = b''
        self.stopped = False

    def interrupt(self):
        if self.stopped:
            return
        self.stopped = True
        # Kill the owned group even after leader exit: no child tool survives a
        # completed/failed turn. Escaped groups require the deployment sandbox.
        try:
            os.killpg(self.proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        self.proc.wait(timeout=1)
        self.proc.stdin.close()
        self.proc.stdout.close()


def lifetime_seconds():
    # Trusted process config ONLY; never RPC/model params or prompt text.
    value = os.environ.get('POOL_MAX_LIFETIME_SECONDS', str(MAX_LIFETIME))
    if not re.fullmatch(r'[0-9]+', value) or not 1 <= int(value) <= MAX_LIFETIME:
        raise Denied('invalid trusted lifetime')
    return int(value)


def serve(factory_ref='worker:factory'):
    """POSIX stdio host. Factory override is an in-process test seam, not RPC.

    Create waits asynchronously for gated initialization; the reader stays live.
    Admission and execution are separated by flushing the prompt ACK first.
    """
    if os.name != 'posix':
        raise Denied('POSIX process groups required')
    deadline = time.monotonic() + lifetime_seconds()
    session = Session(None, os.environ.get('POOL_INCARNATION', 'local-test'))
    child = None
    creating = None
    terminal_sent = False
    incoming = b''
    stopping = False
    previous_sigterm = None
    os.set_blocking(sys.stdout.fileno(), False)

    def request_stop(signum, frame):
        # No raising or cleanup here: signals can arrive inside Popen before
        # ownership is published, or during killpg/wait/pipe close. Repeated
        # signals only set a flag; the owning loop performs unified cleanup.
        nonlocal stopping
        stopping = True

    def emit(frame):
        data = (json.dumps(frame) + '\n').encode()
        until = min(deadline + .25, time.monotonic() + .25)
        while data:
            if stopping:
                raise BrokenPipeError('supervisor stopping')
            if time.monotonic() >= until:
                raise BrokenPipeError('output stalled')
            _, writable, _ = select.select([], [sys.stdout], [], max(0, until-time.monotonic()))
            if not writable:
                raise BrokenPipeError('output stalled')
            try:
                data = data[os.write(sys.stdout.fileno(), data):]
            except BlockingIOError:
                continue

    def reply(rid, result):
        emit({'jsonrpc':'2.0', 'id':rid, 'result':result})

    def error(rid, code=-32001):
        emit({'jsonrpc':'2.0', 'id':rid, 'error':{'code':code, 'message':'worker denied'}})

    def finish(payload):
        nonlocal terminal_sent
        if session.submitted and not terminal_sent:
            terminal_sent = True
            session.spent = True
            emit({'jsonrpc':'2.0', 'method':'event', 'params':{
                'session_id':session.sid, 'type':'message.complete', 'payload':payload, 'seq':1}})

    try:
        previous_sigterm = signal.signal(signal.SIGTERM, request_stop)
        while not stopping and time.monotonic() < deadline:
            readers = [sys.stdin]
            if child is not None and not child.stopped:
                readers.append(child.proc.stdout)
            writers = [child.proc.stdin] if child is not None and not child.stopped and child.pending else []
            ready, writable, _ = select.select(readers, writers, [], min(.05, max(0, deadline-time.monotonic())))
            if stopping or time.monotonic() >= deadline:
                continue
            # Client control wins over a not-yet-published child result.
            if sys.stdin in ready:
                chunk = os.read(sys.stdin.fileno(), 65536)
                if not chunk:
                    break
                incoming += chunk
                while b'\n' in incoming:
                    if stopping or time.monotonic() >= deadline:
                        break
                    line, incoming = incoming.split(b'\n', 1)
                    if len(line) + 1 > MAX_FRAME:
                        return
                    rid = None
                    try:
                        from edge import strict_json
                        frame = strict_json(line)
                        if (not isinstance(frame, dict) or set(frame) != {'jsonrpc','id','method','params'}
                                or frame['jsonrpc'] != '2.0' or type(frame['id']) not in (int, str)
                                or not isinstance(frame['method'], str) or not isinstance(frame['params'], dict)):
                            raise Denied('bad envelope')
                        rid, method, params = frame['id'], frame['method'], frame['params']
                        if method not in {'health','session.create','prompt.submit','session.interrupt','session.close'}:
                            error(rid, -32601)
                            continue
                        if method == 'session.create':
                            if (child is not None or session.spent or
                                    not (not params or params == {'cwd':'/workspace'})):
                                raise Denied('create denied')
                            child = Child(factory_ref)
                            creating = rid
                            continue
                        result = session.handle(method, params)
                        reply(rid, result)
                        if method == 'prompt.submit':
                            child.pending = (json.dumps({'text':session.pending_text}) + '\n').encode()
                            session.pending_text = None
                        elif method in {'session.interrupt','session.close'}:
                            finish({'status':'interrupted','text':''})
                            session.spent = True
                    except (BrokenPipeError, OSError):
                        raise
                    except Exception:
                        error(rid)
                if len(incoming) >= MAX_FRAME:
                    break
            if stopping:
                break
            if child is not None and not child.stopped and child.proc.stdin in writable:
                try:
                    sent = os.write(child.proc.stdin.fileno(), child.pending)
                    child.pending = child.pending[sent:]
                except BlockingIOError:
                    pass
            if child is not None and not child.stopped and child.proc.stdout in ready:
                chunk = os.read(child.proc.stdout.fileno(), 65536)
                child.buffer += chunk
                while b'\n' in child.buffer:
                    line, child.buffer = child.buffer.split(b'\n', 1)
                    message = json.loads(line)
                    if message.get('ready') is True and creating is not None:
                        session.factory = lambda: child
                        result = session.handle('session.create', {})
                        reply(creating, result)
                        creating = None
                    elif 'terminal' in message:
                        child.interrupt()
                        if creating is not None:
                            error(creating)
                            creating = None
                            session.spent = True
                        else:
                            finish(message['terminal'])
                if len(child.buffer) > MAX_FRAME or not chunk:
                    child.interrupt()
                    if creating is not None:
                        error(creating)
                        creating = None
                        session.spent = True
                    finish({'status':'error','text':''})
        else:
            if child is not None:
                child.interrupt()
            if creating is not None:
                error(creating)
            finish({'status':'interrupted','text':''})
    except (BrokenPipeError, OSError):
        pass
    finally:
        try:
            if child is not None:
                child.interrupt()
            session.spent = True
        finally:
            if previous_sigterm is not None:
                # Python resets callable handlers during interpreter teardown.
                # Once cleanup is complete, ignore repeated TERM through exit.
                signal.signal(signal.SIGTERM, signal.SIG_IGN if stopping else previous_sigterm)


if __name__ == '__main__':
    main()
