"""No Docker, credentials or model calls. Stdlib test runner."""
import importlib.util
import pathlib
import os
import sys
import unittest
from worker_test_paths import scratch_root
ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))


class GateTests(unittest.TestCase):
    def test_gate_refuses_missing_extra_duplicate_and_typo(self):
        self.assertTrue((ROOT / 'gate.py').is_file(), 'missing fail-closed gate')
        from gate import EXACT_TOOLS, require_tools, GateRejected
        good = [{'type': 'function', 'function': {'name': n}} for n in EXACT_TOOLS]
        self.assertEqual(require_tools(good), EXACT_TOOLS)
        for bad in (None, [], good[:-1], good + good[:1],
                    good + [{'type': 'function', 'function': {'name': 'terminal'}}],
                    [{'type': 'function', 'function': {'name': 'mcp_haitlify_typo'}}]):
            with self.subTest(bad=bad), self.assertRaises(GateRejected):
                require_tools(bad)


    def test_outer_gate_stops_before_base_turn(self):
        from gate import EXACT_TOOLS, GateRejected, guarded_agent_class
        calls = []
        good = [{'type':'function','function':{'name':n}} for n in EXACT_TOOLS]
        class Base:
            def __init__(self): self.tools, self.valid_tool_names = list(good), EXACT_TOOLS
            def run_conversation(self, text): calls.append(text); return 'base-entered'
            def _build_api_kwargs(self): return {'tools':self.outbound}
        agent = guarded_agent_class(Base)()
        self.assertEqual(agent.run_conversation('valid'), 'base-entered')
        agent.tools = good + [{'type':'function','function':{'name':'terminal'}}]
        with self.assertRaises(GateRejected): agent.run_conversation('blocked')
        self.assertEqual(calls, ['valid'])
        agent.tools = good
        agent.outbound = good[:-1]
        with self.assertRaises(GateRejected): agent._build_api_kwargs()
        agent.outbound = good
        self.assertEqual(agent._build_api_kwargs()['tools'], good)


    def test_runtime_pin_refuses_mismatch_and_absent_manifest(self):
        import tempfile
        import json
        import hashlib
        import gate
        self.assertTrue(hasattr(gate, 'verify_runtime'), 'runtime pin validation absent')
        with tempfile.TemporaryDirectory(dir=scratch_root()) as tmp:
            root = pathlib.Path(tmp)
            (root/'run_agent.py').write_text('fixture')
            manifest = {'run_agent.py':hashlib.sha256(b'fixture').hexdigest()}
            gate.verify_runtime(root, manifest)
            (root/'run_agent.py').write_text('changed')
            with self.assertRaises(gate.GateRejected): gate.verify_runtime(root, manifest)
            for bad in ({}, {'../outside':'0'*64}, {'missing':'0'*64}):
                with self.assertRaises(gate.GateRejected): gate.verify_runtime(root, bad)


if __name__ == '__main__':
    unittest.main()
