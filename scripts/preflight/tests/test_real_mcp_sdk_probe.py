import importlib.util
import concurrent.futures
import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import textwrap
import threading
import time
import unittest
import unittest.mock


MODULE_PATH = Path(__file__).resolve().parents[1] / "real-mcp-sdk-probe.py"
spec = importlib.util.spec_from_file_location("real_mcp_sdk_probe", MODULE_PATH)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


FIXTURE = r'''
import os
from pathlib import Path
import signal
import socket
import sys
import time

mode, root_text = sys.argv[1:]
root = Path(root_text)
# The test retains the only writer capability. No PID is an authorization token.
control = os.open(root / "rescue.fifo", os.O_RDONLY | os.O_NONBLOCK)
def await_rescue():
    while True:
        try:
            request = os.read(control, 1)
        except BlockingIOError:
            request = None
        if request == b"x":
            sock.close()
            os.close(control)
            os._exit(0)
        time.sleep(0.05)

sock = socket.socket()
sock.bind(("127.0.0.1", 0))
sock.listen(1)
(root / "leader.pid").write_text(str(os.getpid()))
(root / "port").write_text(str(sock.getsockname()[1]))
if mode == "normal":
    sock.close()
    raise SystemExit(0)
if mode in {"descendant", "term-ignore"}:
    child = os.fork()
    if child == 0:
        if mode == "term-ignore":
            signal.signal(signal.SIGTERM, signal.SIG_IGN)
        (root / "descendant.pid").write_text(str(os.getpid()))
        await_rescue()
    sock.close()
    raise SystemExit(0)
if mode == "timeout":
    await_rescue()
raise SystemExit(2)
'''


class ProbeTestCase(unittest.TestCase):
    def assert_pid_absent(self, pid):
        # Tolerate transient observer failures and short reap latency under load.
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            try:
                if probe._ps_entry(pid) is None:
                    return
            except subprocess.CalledProcessError:
                pass
            time.sleep(0.05)
        self.assertIsNone(probe._ps_entry(pid), f"PID {pid} still exists")


class RealMcpSdkProbeProcessFixtures(ProbeTestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture_dir = Path(tempfile.mkdtemp(prefix="mcp-probe-fixture-"))
        cls.fixture = cls.fixture_dir / "process_fixture.py"
        cls.fixture.write_text(textwrap.dedent(FIXTURE), encoding="utf-8")

    @classmethod
    def tearDownClass(cls):
        subprocess.run(["/bin/rm", "-rf", str(cls.fixture_dir)], check=True)

    def run_fixture(self, mode, *, timeout=3.0):
        root = Path(tempfile.mkdtemp(prefix="mcp-probe-run-"))
        self.prepare_rescue(root)
        result = probe.supervise_command(
            [sys.executable, str(self.fixture), mode, str(root)],
            cwd=root,
            env={"PATH": os.defpath, "PYTHONPATH": ""},
            log_path=root / "runner.log",
            state_path=root / "state.json",
            timeout=timeout,
            grace=0.4,
        )
        result["root"] = root
        return result

    def prepare_rescue(self, root):
        # Private FIFO inode + held writer, not a reconnect using a historical path.
        # Supported fixtures have exactly one surviving cooperative reader (leader
        # or descendant). This is not rescue for arbitrary/uncooperative orphans.
        os.mkfifo(root / "rescue.fifo", 0o600)
        descriptor = os.open(root / "rescue.fifo", os.O_RDWR | os.O_NONBLOCK)
        if not hasattr(self, "_rescue_controls"):
            self._rescue_controls = {}
        self._rescue_controls[root] = descriptor
        self.addCleanup(self.rescue_own_fixture, root)

    def rescue_own_fixture(self, root):
        # Never signal: an already reaped/reused PID cannot redirect a FIFO write.
        root = Path(root)
        descriptor = getattr(self, "_rescue_controls", {}).pop(root, None)
        if descriptor is None:
            return {"status": "NO_CAPABILITY"}
        # Read-only evidence is saved BEFORE requesting fixture self-exit, so a
        # RED cleanup assertion is never masked by the rescue's later success.
        pids = [int(p.read_text()) for p in root.glob("*.pid")]
        state_path = root / "state.json"
        if state_path.exists():
            state = json.loads(state_path.read_text())
            pids.extend(value for key, value in state.items()
                        if key in {"anchor", "leader", "group"} and type(value) is int)
        pids = sorted(set(pids))
        port_file = root / "port"
        port = int(port_file.read_text()) if port_file.exists() else None
        before = {"pids": {str(pid): probe._ps_entry(pid) for pid in pids},
                  "port": probe._lookup_listening_port(port) if port else None}
        (root / "before-rescue.json").write_text(json.dumps(before, indent=2))
        try:
            os.write(descriptor, b"x")
            for pid in pids:
                self.assert_pid_absent(pid)
            if port:
                observation = probe._observe_listening_port(port)
                self.assertEqual(observation["status"], "ABSENT", observation)
        finally:
            os.close(descriptor)
        result = {"status": "SELF_EXIT_VERIFIED", "signals": []}
        (root / "after-rescue.json").write_text(json.dumps(result))
        return result

    def test_rescue_reaped_child_sends_no_signal(self):
        root = Path(tempfile.mkdtemp(prefix="mcp-rescue-reaped-"))
        child = subprocess.Popen(["/usr/bin/true"])
        child.wait(timeout=5)
        self.assert_pid_absent(child.pid)
        (root / "leader.pid").write_text(str(child.pid))
        (root / "state.json").write_text(json.dumps(
            {"anchor": child.pid, "leader": child.pid, "reaped": True}))
        with unittest.mock.patch.object(os, "kill") as kill, \
                unittest.mock.patch.object(os, "killpg") as killpg:
            self.rescue_own_fixture(root)
        kill.assert_not_called()
        killpg.assert_not_called()

    def test_rescue_unknown_or_reused_pid_has_no_authority(self):
        root = Path(tempfile.mkdtemp(prefix="mcp-rescue-unknown-"))
        # A real unrelated live process (this test process) models PID reuse;
        # neither a PID file nor convincing process metadata grants authority.
        (root / "leader.pid").write_text(str(os.getpid()))
        (root / "state.json").write_text(json.dumps(
            {"anchor": os.getpid(), "leader": os.getpid(), "reaped": False}))
        with unittest.mock.patch.object(os, "kill") as kill, \
                unittest.mock.patch.object(os, "killpg") as killpg:
            result = self.rescue_own_fixture(root)
        kill.assert_not_called()
        killpg.assert_not_called()
        self.assertEqual(result, {"status": "NO_CAPABILITY"})

    def test_completed_fixture_rescue_sends_no_signal(self):
        result = self.run_fixture("normal")
        self.assertEqual(result["status"], "CLEAN")
        self.assert_pid_absent(self.read_fixture_pid(result, "leader.pid"))
        with unittest.mock.patch.object(os, "kill") as kill, \
                unittest.mock.patch.object(os, "killpg") as killpg:
            self.rescue_own_fixture(result["root"])
            self.rescue_own_fixture(result["root"])
        kill.assert_not_called()
        killpg.assert_not_called()

    def test_intentional_red_orphan_self_exits_via_retained_control(self):
        root = Path(tempfile.mkdtemp(prefix="mcp-rescue-red-"))
        owner = self

        class IntentionalRed(unittest.TestCase):
            def runTest(self):
                owner.prepare_rescue(root)
                self.addCleanup(owner.rescue_own_fixture, root)
                child = subprocess.Popen(
                    [sys.executable, str(owner.fixture), "term-ignore", str(root)])
                child.wait(timeout=5)
                deadline = time.monotonic() + 5
                while not (root / "descendant.pid").exists() and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue((root / "descendant.pid").exists())
                self.assertTrue(probe._tcp_listening(int((root / "port").read_text())))
                self.fail("intentional RED before rescue")

        with unittest.mock.patch.object(os, "kill") as kill, \
                unittest.mock.patch.object(os, "killpg") as killpg:
            outcome = unittest.TestResult()
            IntentionalRed().run(outcome)
        self.assertEqual(len(outcome.failures), 1)
        self.assertIn("intentional RED before rescue", outcome.failures[0][1])
        self.assertEqual(outcome.errors, [])
        before = json.loads((root / "before-rescue.json").read_text())
        self.assertEqual(before["port"]["status"], "LISTENING")
        descendant = int((root / "descendant.pid").read_text())
        self.assertIsNotNone(before["pids"][str(descendant)])
        self.assert_pid_absent(descendant)
        self.assertEqual(json.loads((root / "after-rescue.json").read_text())["status"],
                         "SELF_EXIT_VERIFIED")
        kill.assert_not_called()
        killpg.assert_not_called()

    def read_fixture_pid(self, result, name):
        return int((result["root"] / name).read_text(encoding="utf-8"))

    def assert_port_closed(self, result):
        port = int((result["root"] / "port").read_text(encoding="utf-8"))
        self.assertFalse(probe._tcp_listening(port), f"port {port} still listens")

    def test_normal_exit_reaps_without_destructive_signal(self):
        result = self.run_fixture("normal")
        self.assertEqual(result["status"], "CLEAN")
        self.assertEqual(result["leader_exit"], 0)
        self.assertEqual(result["signals"], [])
        self.assert_port_closed(result)
        self.assert_pid_absent(self.read_fixture_pid(result, "leader.pid"))

    def test_leader_exit_with_live_descendant_cleans_owned_group(self):
        result = self.run_fixture("descendant")
        self.assertEqual(result["status"], "CLEAN")
        self.assertEqual(result["leader_exit"], 0)
        self.assertEqual([s["name"] for s in result["signals"]], ["SIGTERM"])
        self.assert_port_closed(result)
        self.assert_pid_absent(self.read_fixture_pid(result, "leader.pid"))
        self.assert_pid_absent(self.read_fixture_pid(result, "descendant.pid"))

    def test_term_resistant_descendant_requires_owned_escalation(self):
        result = self.run_fixture("term-ignore")
        self.assertEqual(result["status"], "CLEAN")
        self.assertEqual([s["name"] for s in result["signals"]], ["SIGTERM", "SIGKILL"])
        self.assert_port_closed(result)
        self.assert_pid_absent(self.read_fixture_pid(result, "descendant.pid"))

    def test_timeout_cleans_live_leader_and_returns_failure(self):
        result = self.run_fixture("timeout", timeout=0.25)
        self.assertEqual(result["status"], "FAILED")
        self.assertTrue(result["timed_out"])
        self.assertNotEqual(result["leader_exit"], 0)
        self.assertEqual([s["name"] for s in result["signals"]], ["SIGTERM"])
        self.assert_port_closed(result)
        self.assert_pid_absent(self.read_fixture_pid(result, "leader.pid"))

    def test_anchor_loss_does_not_signal_numeric_pid(self):
        sent = []
        snapshot = [{"pid": 424242, "ppid": os.getpid(), "pgid": 424242, "stat": "S"}]
        self.assertFalse(
            probe._signal_owned_anchor(
                424242,
                signal.SIGTERM,
                snapshot=snapshot,
                kill=lambda pid, sig: sent.append((pid, sig)),
            )
        )
        self.assertEqual(sent, [])

    def test_live_owned_leader_is_signalable_but_reaped_leader_is_not(self):
        leader = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"],
                                  start_new_session=True)
        sent = []
        try:
            self.assertTrue(probe._signal_owned_anchor(
                leader.pid, signal.SIGTERM,
                kill=lambda pid, sig: sent.append((pid, sig))))
            self.assertEqual(sent, [(leader.pid, signal.SIGTERM)])
        finally:
            leader.kill()
            leader.wait(timeout=10)
        self.assert_pid_absent(leader.pid)
        self.assertFalse(probe._signal_owned_anchor(
            leader.pid, signal.SIGTERM,
            kill=lambda pid, sig: sent.append((pid, sig))))
        self.assertEqual(len(sent), 1)

    def test_anchor_loss_leaves_uncertain_without_group_signals(self):
        root = Path(tempfile.mkdtemp(prefix="mcp-probe-anchor-loss-"))
        self.prepare_rescue(root)
        executor = concurrent.futures.ThreadPoolExecutor(max_workers=1)
        self.addCleanup(executor.shutdown)
        release = threading.Event()
        real_wait = subprocess.Popen.wait

        def held_wait(child, timeout=None):
            if "--anchor" in child.args:
                # This waiter is the sole owner; retain the unreaped child until
                # the test requests loss. No historical state PID authorizes kill.
                release.wait(timeout=20)
                child.kill()
            return real_wait(child, timeout=timeout)

        wait_patch = unittest.mock.patch.object(subprocess.Popen, "wait", held_wait)
        wait_patch.start()
        self.addCleanup(wait_patch.stop)
        self.addCleanup(release.set)
        future = executor.submit(
            probe.supervise_command,
            [sys.executable, str(self.fixture), "timeout", str(root)],
            cwd=root,
            env={"PATH": os.defpath, "PYTHONPATH": ""},
            log_path=root / "runner.log",
            state_path=root / "state.json",
            timeout=0.5,
            grace=0.4,
        )
        state_path = root / "state.json"
        deadline = time.monotonic() + 20
        state = {}
        while time.monotonic() < deadline:
            if state_path.exists():
                try:
                    state = json.loads(state_path.read_text(encoding="utf-8"))
                except json.JSONDecodeError:
                    state = {}
                # The fixture writes leader.pid only after exec, so wait for that start proof.
                if state.get("anchor") and state.get("leader") and (root / "leader.pid").exists():
                    break
            time.sleep(0.02)
        self.assertTrue(state.get("anchor"))
        self.assertTrue(state.get("leader"))
        self.assertTrue((root / "leader.pid").exists(), "fixture leader never started")
        release.set()
        result = future.result(timeout=30)
        self.assertEqual(result["status"], "UNCERTAIN")
        self.assertEqual(result["signals"], [])
        self.assertIsNone(result["leader_exit"])
        leader_pid = int((root / "leader.pid").read_text(encoding="utf-8"))
        self.assertIsNotNone(probe._ps_entry(leader_pid))
        self.assertTrue(probe._tcp_listening(int((root / "port").read_text(encoding="utf-8"))))
        self.rescue_own_fixture(root)

    def test_clean_cleanup_removes_copied_source_and_temp(self):
        root = Path(tempfile.mkdtemp(prefix="mcp-probe-finalize-"))
        source = root / "server/platform"
        temp = root / "tmp"
        source.mkdir(parents=True)
        temp.mkdir()
        (root / "receipt.json").write_text("{}", encoding="utf-8")
        result = probe.finalize_cleanup(root, source, temp, "CLEAN", [])
        self.assertEqual(result["cleanup"], "REMOVED")
        self.assertFalse(source.exists())
        self.assertFalse(temp.exists())
        self.assertTrue((root / "receipt.json").exists())

    def test_uncertain_cleanup_retains_source_and_tmp_evidence(self):
        root = Path(tempfile.mkdtemp(prefix="mcp-probe-finalize-"))
        source = root / "server/platform"
        temp = root / "tmp"
        source.mkdir(parents=True)
        temp.mkdir()
        result = probe.finalize_cleanup(root, source, temp, "UNCERTAIN", [])
        self.assertEqual(result["cleanup"], "RETAINED")
        self.assertIn("process cleanup not verified", result["reason"])
        self.assertTrue((source / "src").exists() or source.exists())
        self.assertTrue(temp.exists())

    def test_listening_port_blocks_deletion_even_after_clean_processes(self):
        root = Path(tempfile.mkdtemp(prefix="mcp-probe-finalize-"))
        source = root / "server/platform"
        temp = root / "tmp"
        source.mkdir(parents=True)
        temp.mkdir()
        result = probe.finalize_cleanup(root, source, temp, "CLEAN", [63456])
        self.assertEqual(result["cleanup"], "RETAINED")
        self.assertEqual(result["ports"], [63456])
        self.assertTrue(source.exists())
        self.assertTrue(temp.exists())

    def test_incomplete_gate_evidence_is_retained_even_with_clean_processes(self):
        root = Path(tempfile.mkdtemp(prefix="mcp-probe-finalize-"))
        source = root / "server/platform"
        temp = root / "tmp"
        (source / "target/surefire-reports").mkdir(parents=True)
        temp.mkdir()
        result = probe.finalize_cleanup(root, source, temp, "CLEAN", [], preserve=True)
        self.assertEqual(result["cleanup"], "RETAINED")
        self.assertIn("gate evidence incomplete", result["reason"])
        self.assertTrue((source / "target/surefire-reports").is_dir())

    def test_gate_never_passes_without_clean_cleanup_full_evidence_or_exit_zero(self):
        suites = [
            {"name": name, "tests": str(count), "failures": "0", "errors": "0", "skipped": "0"}
            for name, count in probe.EXPECTED_SUITES.items()
        ]
        full = {"sdk": {"protocol": "2025-11-25"}, "suites": suites,
                "tests": probe.EXPECTED_TOTAL_TESTS, "failures": 0,
                "errors": 0, "skipped": 0}
        self.assertIsNone(probe.gate_failure(cleanup_status="CLEAN",
                                             cleanup_result={"cleanup": "REMOVED"},
                                             exit_code=0, receipt=full,
                                             suite_count=len(suites)))
        cases = [
            ({"cleanup_status": "UNCERTAIN", "cleanup_result": {"cleanup": "RETAINED"},
              "exit_code": 0, "receipt": full, "suite_count": 4}, "cleanup"),
            ({"cleanup_status": "CLEAN", "cleanup_result": {"cleanup": "REMOVED"},
              "exit_code": 1, "receipt": full, "suite_count": len(suites)}, "exit"),
            ({"cleanup_status": "CLEAN", "cleanup_result": {"cleanup": "REMOVED"},
              "exit_code": 0, "receipt": {"skipped": 0}, "suite_count": len(suites)}, "evidence"),
            ({"cleanup_status": "CLEAN", "cleanup_result": {"cleanup": "REMOVED"},
              "exit_code": 0, "receipt": {"sdk": {}, "skipped": 1}, "suite_count": len(suites)}, "evidence"),
            ({"cleanup_status": "CLEAN", "cleanup_result": {"cleanup": "REMOVED"},
              "exit_code": 0, "receipt": full, "suite_count": len(suites) - 1}, "evidence"),
        ]
        for arguments, expected in cases:
            with self.subTest(expected=expected):
                self.assertIn(expected, probe.gate_failure(**arguments))

    def test_unverified_anchor_exit_is_uncertain_not_failed(self):
        clean_state = {"clean": True, "timed_out": False, "remaining": []}
        self.assertEqual(probe.supervision_status(clean_state, anchor_exit=0), "CLEAN")
        self.assertEqual(probe.supervision_status(clean_state, anchor_exit=1), "UNCERTAIN")
        self.assertEqual(probe.supervision_status({}, anchor_exit=0), "UNCERTAIN")
        dirty = {"clean": False, "timed_out": False, "remaining": []}
        self.assertEqual(probe.supervision_status(dirty, anchor_exit=0), "UNCERTAIN")
        timed = {"clean": True, "timed_out": True, "remaining": []}
        self.assertEqual(probe.supervision_status(timed, anchor_exit=0), "FAILED")

    def test_real_listening_ipv4_reports_socket_owner_without_connecting(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            port = listener.getsockname()[1]
            result = probe._lookup_listening_port(port)
        self.assertEqual(result["status"], "LISTENING")
        self.assertTrue(any(owner["pid"] == os.getpid() for owner in result["owners"]))
        self.assertTrue(any(str(port) in endpoint for owner in result["owners"]
                            for endpoint in owner["endpoints"]))

    def test_real_listening_ipv6_reports_owner_when_ipv6_is_available(self):
        try:
            listener = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("::1", 0))
            listener.listen(1)
        except OSError as exc:
            self.skipTest(f"IPv6 loopback unavailable: {exc}")
        with listener:
            result = probe._lookup_listening_port(listener.getsockname()[1])
        self.assertEqual(result["status"], "LISTENING")
        self.assertTrue(any(owner["pid"] == os.getpid() for owner in result["owners"]))

    def test_real_ipv6_wildcard_listener_reports_owner_when_ipv6_is_available(self):
        try:
            listener = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("::", 0))
            listener.listen(1)
        except OSError as exc:
            self.skipTest(f"IPv6 wildcard unavailable: {exc}")
        with listener:
            result = probe._lookup_listening_port(listener.getsockname()[1])
        self.assertEqual(result["status"], "LISTENING")
        self.assertTrue(any(owner["pid"] == os.getpid() for owner in result["owners"]))

    def test_real_wildcard_listener_is_not_treated_as_absent(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("0.0.0.0", 0))
            listener.listen(1)
            result = probe._lookup_listening_port(listener.getsockname()[1])
        self.assertEqual(result["status"], "LISTENING")
        self.assertTrue(any(owner["pid"] == os.getpid() for owner in result["owners"]))

    def test_closed_port_with_time_wait_is_absent_not_listening(self):
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("127.0.0.1", 0))
        server.listen(1)
        port = server.getsockname()[1]
        client = socket.create_connection(("127.0.0.1", port))
        accepted, _ = server.accept()
        accepted.close()
        client.close()
        server.close()
        result = probe._lookup_listening_port(port)
        self.assertEqual(result["status"], "ABSENT")

    def test_lookup_failure_is_unknown_and_never_absent(self):
        completed = subprocess.CompletedProcess(["lsof"], 2, "", "permission denied")
        with unittest.mock.patch.object(probe.subprocess, "run", return_value=completed):
            result = probe._lookup_listening_port(65431)
        self.assertEqual(result["status"], "UNKNOWN")
        self.assertEqual(result["exit"], 2)

    def test_successful_malformed_lsof_output_is_unknown_and_never_absent(self):
        completed = subprocess.CompletedProcess(["lsof"], 0, "garbage\n", "")
        with unittest.mock.patch.object(probe.subprocess, "run", return_value=completed):
            result = probe._lookup_listening_port(65434)
        self.assertEqual(result["status"], "UNKNOWN")
        self.assertEqual(result["reason"], "lsof output schema invalid")

    def test_external_listener_is_retained_and_never_killed(self):
        child = subprocess.Popen(
            [sys.executable, "-u", "-c",
             "import socket,time; s=socket.socket(); s.bind(('127.0.0.1',0)); s.listen(); print(s.getsockname()[1], flush=True); time.sleep(30)"],
            stdout=subprocess.PIPE, text=True, start_new_session=True)
        try:
            assert child.stdout is not None
            port = int(child.stdout.readline().strip())
            observed = probe._observe_listening_port(port, samples=2, interval=0)
            self.assertEqual(observed["status"], "LISTENING")
            self.assertTrue(any(owner["pid"] == child.pid for sample in observed["samples"]
                                for owner in sample["owners"]))
            root = Path(tempfile.mkdtemp(prefix="mcp-probe-external-port-"))
            source, temp = root / "source", root / "tmp"
            source.mkdir(); temp.mkdir()
            retained = probe.finalize_cleanup(root, source, temp, "CLEAN", [port],
                                              port_observations=[observed])
            self.assertEqual(retained["cleanup"], "RETAINED")
            self.assertTrue(child.poll() is None, "external listener was killed")
            self.assertTrue(source.exists())
            self.assertTrue(temp.exists())
            shutil.rmtree(root)
        finally:
            if child.stdout is not None:
                child.stdout.close()
            child.terminate()
            child.wait(timeout=5)

    def test_empty_success_or_warning_lsof_is_unknown_with_diagnostic(self):
        for stdout, stderr in (("", ""), ("", "lsof: WARNING: incomplete"),
                               ("p123\ncPython\nf3\nn*:65431\n", "lsof: WARNING: incomplete")):
            with self.subTest(stdout=stdout, stderr=stderr), \
                    unittest.mock.patch.object(probe.subprocess, "run", return_value=
                        subprocess.CompletedProcess(["lsof"], 0, stdout, stderr)):
                result = probe._lookup_listening_port(65431)
                self.assertEqual(result["status"], "UNKNOWN")
                self.assertEqual(result["stderr"], stderr)

    def test_tool_unavailable_is_unknown_and_never_absent(self):
        with unittest.mock.patch.object(probe.shutil, "which", return_value=None):
            result = probe._lookup_listening_port(65432)
        self.assertEqual(result["status"], "UNKNOWN")
        self.assertEqual(result["reason"], "lsof unavailable")

    def test_incomplete_or_invalid_lsof_owner_schema_is_unknown(self):
        for stdout in ("p123\n", "p123\ncPython\n", "p123\nn*:65431\n",
                       "p0\ncPython\nn*:65431\n", "p-1\ncPython\nn*:65431\n",
                       "p+123\ncPython\nn*:65431\n", "p123\ncPython\nnnonsense\n",
                       "p123\ncPython\nn*:65430\n", "p123\ncPython\nfwrong\nn*:65431\n",
                       "p123\ncPython\nn*:65431\np456\ncOther\n"):
            with self.subTest(stdout=stdout), \
                    unittest.mock.patch.object(probe.subprocess, "run", return_value=
                        subprocess.CompletedProcess(["lsof"], 0, stdout, "schema diagnostic")):
                result = probe._lookup_listening_port(65431)
                self.assertEqual(result["status"], "UNKNOWN")
                self.assertEqual(result["reason"], "lsof output schema invalid")
                self.assertEqual(result["stderr"], "schema diagnostic")

    def test_stable_absence_is_bounded_and_keeps_every_transition_sample(self):
        samples = iter([
            {"status": "LISTENING", "owners": [{"pid": 7001, "endpoints": ["*:65433"]}]},
            {"status": "ABSENT", "owners": []},
            {"status": "ABSENT", "owners": []},
        ])
        with unittest.mock.patch.object(probe, "_lookup_listening_port",
                                        side_effect=lambda port: next(samples)):
            result = probe._observe_listening_port(65433, samples=3, interval=0)
        self.assertEqual(result["status"], "ABSENT")
        self.assertTrue(result["stable_absent"])
        self.assertEqual(len(result["samples"]), 3)
        self.assertEqual(result["samples"][0]["owners"][0]["pid"], 7001)
        self.assertTrue(result["saw_listener"])

    def test_gate_rejects_missing_class_and_nonzero_failure_or_error_counts(self):
        suites = [
            {"name": name, "tests": str(count), "failures": "0", "errors": "0", "skipped": "0"}
            for name, count in probe.EXPECTED_SUITES.items()
        ]
        full = {"sdk": {"protocol": "2025-11-25"}, "suites": suites,
                "tests": 86, "failures": 0, "errors": 0, "skipped": 0}
        self.assertIsNone(probe.gate_failure(cleanup_status="CLEAN",
                                             cleanup_result={"cleanup": "REMOVED"},
                                             exit_code=0, receipt=full, suite_count=5))
        for mutation in ("missing", "failure", "error", "fake-total"):
            mutated = json.loads(json.dumps(full))
            if mutation == "missing":
                mutated["suites"] = mutated["suites"][:-1]
            elif mutation == "failure":
                mutated["suites"][0]["failures"] = "1"
            elif mutation == "error":
                mutated["suites"][0]["errors"] = "1"
            else:
                mutated["tests"] = 85
            with self.subTest(mutation=mutation):
                self.assertIn("evidence", probe.gate_failure(
                    cleanup_status="CLEAN", cleanup_result={"cleanup": "REMOVED"},
                    exit_code=0, receipt=mutated, suite_count=len(mutated["suites"])))


class RealMcpSdkProbeMavenIsolation(unittest.TestCase):
    def test_scratch_home_and_explicit_empty_settings_isolate_maven(self):
        root = Path(tempfile.mkdtemp(prefix="mcp-probe-maven-"))
        (root / "tmp").mkdir()
        (root / "home").mkdir()
        cache = Path(tempfile.mkdtemp(prefix="mcp-probe-cache-"))
        invocation = probe.maven_invocation(
            mvn="/usr/bin/true",
            root=root,
            source=root / "server/platform",
            python="/venv/bin/python",
            java_home="/jdk",
            repo_local=cache,
        )
        settings = Path(invocation["settings"])
        self.assertTrue(settings.exists())
        settings_root = probe.ET.fromstring(settings.read_text(encoding="utf-8"))
        self.assertTrue(settings_root.tag.endswith("settings"))
        self.assertEqual(list(settings_root), [])
        command = invocation["command"]
        for flag in ("-o", "-s", "-gs", "-Dmaven.repo.local=" + str(Path(cache).resolve())):
            self.assertIn(flag, command)
        self.assertEqual(command[command.index("-s") + 1], str(settings))
        self.assertEqual(command[command.index("-gs") + 1], str(settings))
        env = invocation["env"]
        self.assertEqual(env["HOME"], str(root / "home"))
        self.assertEqual(env["MAVEN_SKIP_RC"], "true")
        self.assertNotEqual(env["HOME"], str(Path.home()))
        arg_line = next(part for part in command if part.startswith("-DargLine="))
        self.assertIn("-Duser.home=" + str(root / "home"), arg_line)


FAKE_MVN = """#!/bin/sh
set -eu
mkdir -p target/surefire-reports
for entry in \
  com.haitlify.aitest.platform.assistant.mcp.McpPythonSdkTest:2 \
  com.haitlify.aitest.platform.assistant.mcp.McpHttpTest:12 \
  com.haitlify.aitest.platform.assistant.mcp.McpSocketTest:13 \
  com.haitlify.aitest.platform.assistant.generation.tool.GenerationDraftToolsTest:55 \
  com.haitlify.aitest.platform.assistant.mcp.McpPythonProcessCleanupTest:4; do
  name=${entry%%:*}; count=${entry##*:}; file=${name##*.}
  printf '<testsuite name="%s" tests="%s" failures="0" errors="0" skipped="0"/>\\n' "$name" "$count" \\
    > "target/surefire-reports/TEST-$file.xml"
done
printf 'REAL_MCP_SDK_RECEIPT {"protocol":"2025-11-25"}\\n'
"""


class RealMcpSdkProbeGateIntegration(ProbeTestCase):
    """Fake Maven tool fixture: exercises gate, receipt and cleanup without a JVM."""

    def test_fake_maven_gate_passes_then_removes_copied_evidence(self):
        base = Path(tempfile.mkdtemp(prefix="mcp-probe-gate-"))
        self.addCleanup(shutil.rmtree, base, True)
        hermes = base / "hermes"
        hermes.mkdir()
        (hermes / "pyproject.toml").write_text(
            '[project]\ndependencies = [\n    "mcp==2.0.0",\n    "httpx2==2.12.0",\n]\n',
            encoding="utf-8")
        subprocess.run(["git", "init", "-q", str(hermes)], check=True)
        subprocess.run(["git", "-C", str(hermes), "add", "pyproject.toml"], check=True)
        subprocess.run(["git", "-C", str(hermes), "-c", "user.email=t@example.invalid",
                        "-c", "user.name=t", "commit", "-qm", "init"], check=True)
        pin = subprocess.check_output(["git", "-C", str(hermes), "rev-parse", "HEAD"],
                                      text=True).strip()
        binaries = base / "bin"
        binaries.mkdir()
        fake_mvn = binaries / "mvn"
        fake_mvn.write_text(FAKE_MVN, encoding="utf-8")
        fake_mvn.chmod(0o755)
        cache = base / "cache"
        cache.mkdir()
        scratch = base / "scratch"
        scratch.mkdir()
        argv = [str(MODULE_PATH), "--python", sys.executable,
                "--hermes-source", str(hermes), "--scratch", str(scratch),
                "--java-home", str(base / "jdk"), "--maven-repo-local", str(cache)]
        environment = {"PATH": str(binaries) + os.pathsep + os.environ.get("PATH", "")}
        with unittest.mock.patch.dict(os.environ, environment), \
                unittest.mock.patch.object(probe, "PIN", pin), \
                unittest.mock.patch.object(sys, "argv", argv), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(probe.main(), 0)
        runs = list(scratch.glob("real-mcp-sdk-*"))
        self.assertEqual(len(runs), 1)
        run = runs[0]
        receipt = json.loads((run / "receipt.json").read_text(encoding="utf-8"))
        self.assertEqual(receipt["exit"], 0)
        self.assertEqual(receipt["tests"], probe.EXPECTED_TOTAL_TESTS)
        self.assertEqual(receipt["failures"], 0)
        self.assertEqual(receipt["errors"], 0)
        self.assertEqual(receipt["skipped"], 0)
        self.assertEqual(receipt["sdk"], {"protocol": "2025-11-25"})
        self.assertEqual(receipt["supervisor"]["status"], "CLEAN")
        self.assertEqual(receipt["cleanup"]["cleanup"], "REMOVED")
        self.assertEqual(receipt["ports"], {"observed": [], "listening": [],
                                            "unknown": [], "checks": []})
        self.assertFalse((run / "server/platform").exists())
        self.assertFalse((run / "tmp").exists())
        self.assert_pid_absent(receipt["supervisor"]["leader"])


if __name__ == "__main__":
    unittest.main()
