"""Entry/factory boundary; optional pinned-native probes never invoke a model.

Fixture unit tests alone do not prove native compatibility. Native tests require explicit
WORKER_PINNED_SOURCE, WORKER_NATIVE_PYTHON and WORKER_NATIVE_SITE paths. The separate
zero-attempt audit preserves metadata DNS diagnostics, not an acceptance blocker for
the initial-toolset contract (nor evidence of kernel/network isolation).
"""
import contextlib
import inspect
import json
import os
import pathlib
import stat
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import worker
from gate import EXACT_TOOLS

from worker_test_paths import scratch_root
SCRATCH = scratch_root()
DEFAULT_MCP_URL = 'http://mcp-edge:8080/mcp'
DEFAULT_PROVIDER_URL = 'http://provider-edge:8080/v1'


@contextlib.contextmanager
def fixture_runtime(discovered=None, agent_tools=None):
    """Stand-in for the pinned Hermes runtime. gate.py itself stays real."""
    calls, agents = [], []
    tools = [{'type': 'function', 'function': {'name': n}}
             for n in (agent_tools if agent_tools is not None else EXACT_TOOLS)]

    class AIAgent:
        def __init__(self, **kwargs):
            self.kwargs = kwargs
            self.tools = list(tools)
            self.valid_tool_names = {t['function']['name'] for t in self.tools}
            agents.append(self)

    run_agent = types.ModuleType('run_agent')
    run_agent.AIAgent = AIAgent
    tools_pkg = types.ModuleType('tools')
    tools_pkg.__path__ = []
    discovery = types.ModuleType('tools.mcp_tool_discovery')

    def register_mcp_servers(servers):
        calls.append(servers)
        return list(discovered if discovered is not None else EXACT_TOOLS)

    discovery.register_mcp_servers = register_mcp_servers
    with tempfile.TemporaryDirectory(dir=SCRATCH) as tmp:
        source = pathlib.Path(tmp) / 'runtime'
        source.mkdir()
        (source / 'run_agent.py').write_text('')
        with patch.dict(sys.modules, {'run_agent': run_agent, 'tools': tools_pkg,
                                      'tools.mcp_tool_discovery': discovery}), \
                patch('importlib.util.find_spec',
                      return_value=types.SimpleNamespace(origin=str(source / 'run_agent.py'))), \
                patch('gate.verify_runtime'):
            yield calls, agents


@contextlib.contextmanager
def generated_home(extra_config=None):
    """Real generated default-entry HOME, produced by worker.main() itself."""
    with tempfile.TemporaryDirectory(dir=SCRATCH) as tmp:
        root = pathlib.Path(tmp) / 'worker'
        root.mkdir(mode=0o700)
        env = {'HOME': str(root), 'HERMES_HOME': str(root / '.hermes'),
               'POOL_INCARNATION': 'review', 'POOL_MODEL': 'fixture'}
        with patch.object(worker, 'WORKER_HOME', str(root)), patch.dict(os.environ, env), \
                patch.object(worker, 'serve') as serve:
            worker.main()
        config = root / '.hermes' / 'config.yaml'
        if extra_config is not None:
            config.write_text(json.dumps(extra_config))
        yield config, env
        del serve


def native_factory_probe(mode, force_all_tools=False):
    """Child-only probe: real pin/registration/AIAgent; discovery transport is a fixture.

    Run with -I -S -B, empty HOME, explicit source/dependency paths, no credentials.
    Hooks precede all native imports. They observe/block attempts, not sandbox Python.
    """
    import collections
    import traceback
    events = []

    def audit(event, args):
        if ((event.startswith('socket.') and event != 'socket.__new__') or
                event in {'subprocess.Popen', 'os.system', 'os.exec', 'os.posix_spawn',
                          'os.fork', 'os.forkpty', 'sqlite3.connect'}):
            events.append({'event': event, 'origin': traceback.format_stack(limit=35)[:-1]})
            raise OSError('native probe denies I/O')

    sys.addaudithook(audit)
    result = {'mode': mode, 'discovery': 'transport fixture; native registration and constructor',
              'flags': {'isolated': sys.flags.isolated, 'no_site': sys.flags.no_site,
                        'dont_write_bytecode': sys.flags.dont_write_bytecode}}
    try:
        import hashlib
        source = worker.pinned_runtime()
        result['native_source'] = str(source)
        result['source_hashes'] = {name: hashlib.sha256((source / name).read_bytes()).hexdigest()
                                   for name in ('run_agent.py', 'agent/agent_init.py',
                                                'model_tools.py', 'tools/mcp_tool_registration.py')}
        result['worker_sha256'] = hashlib.sha256(pathlib.Path(worker.__file__).read_bytes()).hexdigest()
        result['python'] = sys.version
        result['executable'] = sys.executable
        worker.write_home_config(pathlib.Path(os.environ['HERMES_HOME']))
        import tools.mcp_tool_discovery as discovery
        from tools.mcp_tool_registration import _Candidate, _register_candidates
        from tools.mcp_tool_schema import mcp_prefixed_tool_name
        from tools.registry import registry

        def no_dispatch(**kwargs):
            raise AssertionError('probe must never dispatch a tool')

        def fixture(servers):
            result['discovery_config'] = servers
            candidates = []
            for short in ('generation_context', 'draft_create', 'draft_get'):
                name = mcp_prefixed_tool_name('haitlify', short)
                candidates.append(_Candidate(name, short, {
                    'name': name, 'description': 'discovery transport fixture',
                    'parameters': {'type': 'object', 'properties': {}}}, no_dispatch))
            names = _register_candidates('haitlify', candidates, check_fn=lambda: True,
                                         scope=lambda: None, lazy=False)
            result['registered_toolsets'] = sorted({registry.get_toolset_for_tool(n) for n in names})
            return names

        discovery.register_mcp_servers = fixture
        import run_agent
        original = run_agent.AIAgent.__init__

        def observed(self, *args, **kwargs):
            if force_all_tools:
                # Native negative: None really means all, not an invented tool list.
                kwargs['enabled_toolsets'] = None
            result['enabled_toolsets'] = kwargs['enabled_toolsets']
            original(self, *args, **kwargs)
            result['initial_tool_names'] = sorted(t['function']['name'] for t in self.tools)
            result['environment_probe'] = self._environment_probe
            result['constructor_api_calls'] = self.session_api_calls

        # Observe, never substitute, the native constructor and tool selection.
        run_agent.AIAgent.__init__ = observed
        session = worker.Session(getattr(worker, mode), 'native-probe')
        result['session_created'] = False
        try:
            session.handle('session.create', {})
        finally:
            result['session_created'] = session.sid is not None
        agent = session.agent
        result['tools'] = sorted(t['function']['name'] for t in agent.tools)
        result['dispatch'] = sorted(agent.valid_tool_names)
        payload = agent._build_api_kwargs([{'role': 'user', 'content': 'no model'}])
        result['assembled_tools'] = sorted(t['function']['name'] for t in payload['tools'])
        from gate import GateRejected
        rejected = []
        for change in ('extra', 'missing', 'duplicate', 'dispatch'):
            old_tools, old_names = agent.tools, agent.valid_tool_names
            if change == 'extra':
                agent.tools = old_tools + [{'type': 'function', 'function': {'name': 'terminal'}}]
            elif change == 'missing':
                agent.tools = old_tools[:-1]
            elif change == 'duplicate':
                agent.tools = old_tools[:2] + old_tools[:1]
            else:
                agent.valid_tool_names = {'terminal'}
            try:
                agent.run_conversation('must reject before native turn')
            except GateRejected:
                rejected.append(change)
            finally:
                agent.tools, agent.valid_tool_names = old_tools, old_names
        result['turn_rejections'] = rejected
    except Exception as exc:
        result['factory_error'] = {'type': type(exc).__name__, 'text': str(exc)}
    finally:
        thread = getattr(sys.modules.get('tools.env_probe'), '_PROBE_THREAD', None)
        if thread:
            thread.join(timeout=3)
        result['probe_thread_alive'] = bool(thread and thread.is_alive())
        result['attempts'] = events
        result['event_counts'] = dict(collections.Counter(e['event'] for e in events))
        print('NATIVE_RESULT=' + json.dumps(result))


@unittest.skipUnless(os.environ.get('WORKER_PINNED_SOURCE'),
                     'requires explicit credential-free pinned source and native dependencies')
class NativeFactoryTests(unittest.TestCase):
    """Not replaced by fixture_runtime: run separately with an actual Hermes pin."""
    def probe(self, mode, force_all_tools=False):
        source = pathlib.Path(os.environ['WORKER_PINNED_SOURCE']).resolve()
        self.assertFalse((source / '.env').exists())
        executable = os.environ['WORKER_NATIVE_PYTHON']
        site = os.environ['WORKER_NATIVE_SITE']
        with tempfile.TemporaryDirectory(dir=SCRATCH) as tmp:
            home = pathlib.Path(tmp) / 'home'
            hermes = home / '.hermes'
            hermes.mkdir(parents=True)
            env = {'HOME': str(home), 'HERMES_HOME': str(hermes), 'POOL_MODEL': 'fixture',
                   'PATH': '/usr/bin:/bin', 'TMPDIR': tmp, 'PYTHONDONTWRITEBYTECODE': '1'}
            script = ('import sys, runpy; sys.path[:0] = ' + repr([str(source), site]) +
                      '; ns = runpy.run_path(' + repr(str(pathlib.Path(__file__).resolve())) +
                      '); ns["native_factory_probe"](' + repr(mode) + ', ' + repr(force_all_tools) + ')')
            proc = subprocess.run([executable, '-I', '-S', '-B', '-c', script],
                                  env=env, cwd=tmp, capture_output=True, text=True, timeout=60)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        rows = [line.removeprefix('NATIVE_RESULT=') for line in proc.stdout.splitlines()
                if line.startswith('NATIVE_RESULT=')]
        self.assertEqual(len(rows), 1, proc.stdout + proc.stderr)
        result = json.loads(rows[0])
        evidence = os.environ.get('WORKER_NATIVE_EVIDENCE')
        if evidence:
            suffix = '-all-tools' if force_all_tools else ''
            pathlib.Path(evidence, mode + suffix + '.json').write_text(json.dumps(result, indent=2))
        self.assertEqual(result['flags'], {'isolated': 1, 'no_site': 1, 'dont_write_bytecode': 1})
        return result

    def test_real_factories_disable_environment_probe(self):
        for mode in ('factory', 'lifecycle_factory'):
            with self.subTest(factory=mode):
                result = self.probe(mode)
                self.assertIsNone(result.get('factory_error'), result.get('factory_error'))
                self.assertIs(result['environment_probe'], False)
                self.assertEqual(result['event_counts'].get('subprocess.Popen', 0), 0)
                self.assertFalse(result['probe_thread_alive'])
                for field in ('initial_tool_names', 'tools', 'dispatch', 'assembled_tools'):
                    self.assertEqual(result[field], sorted(EXACT_TOOLS), field)
                self.assertEqual(result['turn_rejections'], ['extra', 'missing', 'duplicate', 'dispatch'])
                self.assertEqual(result['constructor_api_calls'], 0)

    def test_native_initialization_zero_network_process_attempts(self):
        """Diagnostic oracle, not toolset acceptance: retain blocked metadata DNS attempts."""
        for mode in ('factory', 'lifecycle_factory'):
            with self.subTest(factory=mode):
                result = self.probe(mode)
                # urllib3's import-time IPv6 loopback availability bind is local,
                # not an outbound request. Preserve its trace in the raw evidence.
                attempts = {k: v for k, v in result['event_counts'].items() if k != 'socket.bind'}
                self.assertEqual(attempts, {}, 'initialization attempted network/process/DB I/O')

    def test_real_factories_select_only_registered_tools_at_construction(self):
        for mode in ('factory', 'lifecycle_factory'):
            with self.subTest(factory=mode):
                result = self.probe(mode)
                self.assertIsNone(result.get('factory_error'), result.get('factory_error'))
                self.assertEqual(result['registered_toolsets'], ['mcp-haitlify'])
                self.assertEqual(result['initial_tool_names'], sorted(EXACT_TOOLS))
                self.assertEqual(len(result['initial_tool_names']), 3)
                self.assertEqual(result['enabled_toolsets'], ['mcp-haitlify'])
                self.assertIs(result['session_created'], True)
                for field in ('tools', 'dispatch', 'assembled_tools'):
                    self.assertEqual(result[field], sorted(EXACT_TOOLS), field)
                self.assertEqual(result['turn_rejections'], ['extra', 'missing', 'duplicate', 'dispatch'])
                self.assertEqual(result['constructor_api_calls'], 0)
                self.assertEqual(result['discovery_config'], {'haitlify': {
                    'url': DEFAULT_MCP_URL, 'strict_redirect_headers': True}})

    def test_real_factories_reject_native_all_tools_before_session_receipt(self):
        for mode in ('factory', 'lifecycle_factory'):
            with self.subTest(factory=mode):
                result = self.probe(mode, force_all_tools=True)
                self.assertIsNone(result['enabled_toolsets'])
                self.assertGreater(len(result['initial_tool_names']), 3)
                self.assertTrue(EXACT_TOOLS < set(result['initial_tool_names']))
                self.assertEqual(result.get('factory_error'), {
                    'type': 'GateRejected',
                    'text': 'effective tools must be exactly the three business MCP tools'})
                self.assertIs(result['session_created'], False)
                self.assertEqual(result['constructor_api_calls'], 0)


class DefaultEntryTests(unittest.TestCase):
    def test_generated_home_config_is_accepted_by_default_factory(self):
        """F1: main()'s own generated config must satisfy the factory's closed-config gate."""
        with generated_home() as (config, env):
            written = json.loads(config.read_text())
            self.assertEqual(written, {
                'tools': {'tool_search': {'enabled': 'off'}},
                'platform_toolsets': {'cli': ['mcp-haitlify']},
                'compression': {'enabled': False},
                'agent': {'environment_probe': False},
            }, 'default entry must generate the closed config its factory requires')
            self.assertEqual(stat.S_IMODE(config.stat().st_mode), 0o600)
            with fixture_runtime() as (calls, agents), patch.dict(os.environ, env):
                worker.factory()
        self.assertEqual(len(calls), 1, 'default factory must complete one MCP discovery pass')
        self.assertEqual(len(agents), 1)
        self.assertEqual(set(calls[0]), {'haitlify'})

    def test_default_factory_rejects_config_with_non_owner_mode(self):
        for mode in (0o644, 0o400, 0o700, 0o660, 0o1600):
            with self.subTest(mode=oct(mode)), generated_home() as (config, env):
                os.chmod(config, mode)
                with fixture_runtime() as (calls, agents), patch.dict(os.environ, env), \
                        patch('worker.os.open', wraps=os.open) as opened:
                    with self.assertRaisesRegex(worker.Denied,
                                                '^explicit closed tool configuration required$'):
                        worker.factory()
                    opened.assert_not_called()
                self.assertEqual(calls, [])
                self.assertEqual(agents, [])

    def test_default_factory_rejects_config_not_owned_by_effective_user(self):
        with generated_home() as (config, env):
            current_uid = os.geteuid()
            self.assertEqual(config.lstat().st_uid, current_uid)
            with fixture_runtime() as (calls, _), patch.dict(os.environ, env), \
                    patch('worker.os.geteuid', return_value=current_uid + 1), \
                    patch('worker.os.open', wraps=os.open) as opened:
                with self.assertRaises(worker.Denied):
                    worker.factory()
                opened.assert_not_called()
            self.assertEqual(calls, [])

    def test_default_factory_rejects_config_symlink(self):
        for mode in (0o600, 0o644):
            with self.subTest(mode=oct(mode)), generated_home() as (config, env), \
                    tempfile.TemporaryDirectory(dir=SCRATCH) as tmp:
                target = pathlib.Path(tmp) / 'target'
                target.write_text(json.dumps(worker.home_config()))
                os.chmod(target, mode)
                config.unlink()
                config.symlink_to(target)
                with fixture_runtime() as (calls, _), patch.dict(os.environ, env), \
                        patch('worker.os.open', wraps=os.open) as opened:
                    with self.assertRaises(worker.Denied):
                        worker.factory()
                    opened.assert_not_called()
                self.assertEqual(calls, [])

    def test_default_factory_rejects_config_directory(self):
        with generated_home() as (config, env):
            config.unlink()
            config.mkdir(mode=0o600)
            with fixture_runtime() as (calls, _), patch.dict(os.environ, env), \
                    patch('worker.os.open', wraps=os.open) as opened:
                with self.assertRaises(worker.Denied):
                    worker.factory()
                opened.assert_not_called()
            self.assertEqual(calls, [])

    def test_default_factory_rejects_config_fifo(self):
        with generated_home() as (config, env):
            config.unlink()
            os.mkfifo(config, 0o600)
            # A read of a FIFO without a writer hangs; bound the real read boundary
            # in a subprocess rather than leaving the test runner blocked forever.
            script = ('import worker\n'
                      'try: worker.closed_home_config()\n'
                      'except worker.Denied: pass\n'
                      'else: raise AssertionError("FIFO accepted")\n')
            try:
                result = subprocess.run([sys.executable, '-B', '-c', script], cwd=ROOT,
                                        env=dict(os.environ, **env), capture_output=True,
                                        text=True, timeout=3)
            except subprocess.TimeoutExpired:
                self.fail('config FIFO was opened and blocked rather than denied')
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_default_factory_rejects_unreadable_or_invalid_config_without_path_leak(self):
        for content in (None, '{', '\ufffd', '[]'):
            with self.subTest(content=content), generated_home() as (config, env):
                if content is None:
                    config.unlink()
                else:
                    config.write_text(content)
                with fixture_runtime() as (calls, agents), patch.dict(os.environ, env):
                    with self.assertRaisesRegex(worker.Denied,
                                                '^explicit closed tool configuration required$'):
                        worker.factory()
                self.assertEqual(calls, [])
                self.assertEqual(agents, [])

    def test_config_open_revalidates_changed_metadata_and_identity(self):
        real_open = os.open
        for change in ('mode', 'owner', 'replace', 'symlink', 'fifo'):
            with self.subTest(change=change), generated_home() as (config, env):
                calls = []

                def raced_open(path, flags, *args, **kwargs):
                    calls.append(flags)
                    if change == 'mode':
                        os.chmod(config, 0o644)
                    elif change in ('replace', 'symlink'):
                        target = config.with_name('replacement')
                        with target.open('w') as stream:
                            json.dump(worker.home_config(), stream)
                        os.chmod(target, 0o600)
                        if change == 'replace':
                            target.replace(config)
                        else:
                            config.unlink()
                            config.symlink_to(target)
                    elif change == 'fifo':
                        config.unlink()
                        os.mkfifo(config, 0o600)
                        # Assert nonblocking before opening to keep regressions bounded.
                        self.assertTrue(flags & os.O_NONBLOCK)
                    return real_open(path, flags, *args, **kwargs)

                current_uid = os.geteuid()
                uid_values = [current_uid, current_uid + 1] if change == 'owner' else None
                uid = patch('worker.os.geteuid', side_effect=uid_values,
                            return_value=current_uid)
                with patch.dict(os.environ, env), uid, \
                        patch('worker.os.open', side_effect=raced_open), \
                        patch('worker.json.load', wraps=json.load) as read_config:
                    with self.assertRaisesRegex(worker.Denied,
                                                '^explicit closed tool configuration required$'):
                        worker.closed_home_config()
                    read_config.assert_not_called()
                self.assertEqual(len(calls), 1)
                self.assertTrue(calls[0] & os.O_NOFOLLOW)

    def test_opened_descriptor_must_still_be_a_regular_file(self):
        # Isolate the descriptor type check from the independent inode check.
        # A real FIFO replacement above exercises the complete filesystem path.
        with generated_home() as (config, env):
            info = config.stat()
            changed = types.SimpleNamespace(st_mode=stat.S_IFIFO | 0o600,
                                            st_uid=info.st_uid, st_dev=info.st_dev,
                                            st_ino=info.st_ino)
            with patch.dict(os.environ, env), patch('worker.os.fstat', return_value=changed):
                with self.assertRaisesRegex(worker.Denied,
                                            '^explicit closed tool configuration required$'):
                    worker.closed_home_config()

    def test_home_config_generator_does_not_overwrite_existing_path(self):
        with generated_home() as (config, _):
            original = config.read_bytes()
            with self.assertRaises(FileExistsError):
                worker.write_home_config(config.parent)
            self.assertEqual(config.read_bytes(), original)

    def test_default_factory_sends_no_credential_and_ignores_redirect_environment(self):
        """F1/F2: the default entry keeps fixed edge destinations and holds no capability."""
        token = 'A' * 43
        with generated_home() as (_, env), tempfile.TemporaryDirectory(dir=SCRATCH) as tmp:
            bearer = pathlib.Path(tmp) / 'bearer'
            bearer.write_text(token + '\n')
            os.chmod(bearer, 0o600)
            hostile = dict(env, POOL_MCP_URL='http://elsewhere.example:9999/mcp',
                           POOL_PROVIDER_URL='http://elsewhere.example:9999/v1',
                           POOL_MCP_BEARER_FILE=str(bearer))
            with fixture_runtime() as (calls, agents), patch.dict(os.environ, hostile):
                worker.factory()
        mcp = calls[0]['haitlify']
        self.assertEqual(mcp['url'], DEFAULT_MCP_URL)
        self.assertNotIn('authorization', {k.lower() for k in (mcp.get('headers') or {})})
        self.assertNotIn(token, json.dumps(calls[0]))
        self.assertEqual(agents[0].kwargs['base_url'], DEFAULT_PROVIDER_URL)
        self.assertEqual(agents[0].kwargs['enabled_toolsets'], ['mcp-haitlify'])
        self.assertNotIn(token, json.dumps(agents[0].kwargs))

    def test_default_factory_refuses_config_without_closed_fields(self):
        """A missing/extra closed-config field still fails closed at the default entry."""
        for broken in ({'tools': {'tool_search': {'enabled': 'on'}}},
                       {'platform_toolsets': {'cli': ['mcp-haitlify', 'terminal']}}):
            with self.subTest(broken=broken), generated_home(broken) as (_, env):
                with fixture_runtime() as (calls, _), patch.dict(os.environ, env):
                    with self.assertRaises(worker.Denied):
                        worker.factory()
                self.assertEqual(calls, [])


class LifecycleFactoryTests(unittest.TestCase):
    def lifecycle_home(self, config):
        handle = tempfile.TemporaryDirectory(dir=SCRATCH)
        self.addCleanup(handle.cleanup)
        home = pathlib.Path(handle.name)
        worker.write_home_config(home)
        (home / 'config.yaml').write_text(json.dumps(config))
        return home

    def test_lifecycle_factory_requires_the_generated_closed_home(self):
        """The opt-in factory keeps the strict generated-home precondition."""
        closed = {'tools': {'tool_search': {'enabled': 'off'}},
                  'platform_toolsets': {'cli': ['mcp-haitlify']},
                  'compression': {'enabled': False}, 'agent': {'environment_probe': False}}
        missing = {k: v for k, v in closed.items() if k != 'agent'}
        env = {'POOL_MODEL': 'fixture'}
        for broken in (missing, dict(closed, agent={'environment_probe': True}),
                       dict(missing, environment_probe=False)):
            home = self.lifecycle_home(broken)
            with self.subTest(home=broken), fixture_runtime() as (calls, _), \
                    patch.dict(os.environ, dict(env, HERMES_HOME=str(home))):
                with self.assertRaises(worker.Denied):
                    worker.lifecycle_factory()
            self.assertEqual(calls, [])
        home = self.lifecycle_home(closed)
        with fixture_runtime() as (calls, _), \
                patch.dict(os.environ, dict(env, HERMES_HOME=str(home))):
            worker.lifecycle_factory()
        self.assertEqual(len(calls), 1)

    def test_lifecycle_factory_uses_trusted_slot_urls_and_still_no_credential(self):
        """F2: explicit slot endpoints are honoured; the worker still holds no token."""
        closed = {'tools': {'tool_search': {'enabled': 'off'}},
                  'platform_toolsets': {'cli': ['mcp-haitlify']},
                  'compression': {'enabled': False}, 'agent': {'environment_probe': False}}
        token = 'B' * 43
        home = self.lifecycle_home(closed)
        with tempfile.TemporaryDirectory(dir=SCRATCH) as tmp:
            bearer = pathlib.Path(tmp) / 'bearer'
            bearer.write_text(token)
            os.chmod(bearer, 0o600)
            env = {'POOL_MODEL': 'fixture', 'HERMES_HOME': str(home),
                   'POOL_MCP_URL': 'http://127.0.0.1:18080/mcp',
                   'POOL_PROVIDER_URL': 'http://127.0.0.1:18081/v1',
                   'POOL_MCP_BEARER_FILE': str(bearer)}
            with fixture_runtime() as (calls, agents), patch.dict(os.environ, env):
                worker.lifecycle_factory()
        mcp = calls[0]['haitlify']
        self.assertEqual(mcp['url'], 'http://127.0.0.1:18080/mcp')
        self.assertNotIn('authorization', {k.lower() for k in (mcp.get('headers') or {})})
        self.assertEqual(agents[0].kwargs['base_url'], 'http://127.0.0.1:18081/v1')
        self.assertNotIn(token, json.dumps([calls, agents[0].kwargs]))

    def test_lifecycle_entry_selects_the_explicit_factory(self):
        """The opt-in entry must not silently reuse the default entry's factory."""
        import worker_lifecycle
        default = inspect.signature(worker_lifecycle.serve).parameters['factory_ref'].default
        self.assertEqual(default, 'worker:lifecycle_factory')
        self.assertIsNot(worker.factory, worker.lifecycle_factory)

    def test_lifecycle_prepares_the_same_closed_home_as_the_default_entry(self):
        """One generator: the lifecycle home cannot drift from the default entry's home."""
        import worker_lifecycle
        with tempfile.TemporaryDirectory(dir=SCRATCH) as tmp:
            fresh = pathlib.Path(tmp) / 'slot-home'
            with patch.dict(os.environ, {'POOL_HERMES_HOME': str(fresh)}):
                worker_lifecycle.prepare_empty_home()
                written = json.loads((fresh / 'config.yaml').read_text())
                self.assertEqual(written, worker.home_config())
                self.assertEqual(stat.S_IMODE((fresh / 'config.yaml').stat().st_mode), 0o600)
                with patch.dict(os.environ, {'HERMES_HOME': str(fresh)}):
                    self.assertEqual(worker.closed_home_config(), worker.home_config())
            # A reused home is refused rather than rewritten in place.
            with patch.dict(os.environ, {'POOL_HERMES_HOME': str(fresh)}):
                with self.assertRaises(worker_lifecycle.Blocked):
                    worker_lifecycle.prepare_empty_home()


class CapabilityBoundaryTests(unittest.TestCase):
    def test_worker_sources_never_read_a_capability_file(self):
        """F2: no worker-side module may resolve a bearer/capability file."""
        for name in ('worker.py', 'worker_lifecycle.py', 'worker_bootstrap.py'):
            text = (ROOT / name).read_text()
            with self.subTest(module=name):
                self.assertTrue('POOL_MCP_BEARER_FILE' not in text, name + ' resolves a capability file')
                self.assertTrue('BEARER_FILE' not in text, name + ' names a bearer file')
                self.assertTrue('Authorization' not in text, name + ' builds an auth header')


if __name__ == '__main__':
    unittest.main()
