"""Executed ONLY inside a credential-free git archive by worker-pool-probe.py.
Uses real AIAgent init/selection and turn boundary; denies ALL socket connects.
This does not test a model or the full provider transport.
"""
import json
import os
import pathlib
import socket
import sys

sys.path.insert(0, os.environ['POOL_MODULE'])
from gate import EXACT_TOOLS, GateRejected, guarded_agent_class, verify_runtime
verify_runtime(pathlib.Path.cwd(), json.loads((pathlib.Path(os.environ['POOL_MODULE'])/'runtime-manifest.json').read_text()))

attempts = []
def deny_connect(*args, **kwargs):
    attempts.append('connect')
    raise AssertionError('network forbidden in pinned probe')
socket.socket.connect = deny_connect
socket.create_connection = deny_connect

from run_agent import AIAgent
from tools.registry import registry
from agent.turn_context import _collect_pre_llm_call_context
from unittest.mock import patch
from types import SimpleNamespace
probe_agent = SimpleNamespace(session_id='probe', model='fixture')
with patch('hermes_cli.lifecycle.invoke_hook', side_effect=RuntimeError('deny-probe')):
    swallowed = _collect_pre_llm_call_context(probe_agent, effective_task_id='probe', turn_id='probe',
        original_user_message='no model', messages=[], conversation_history=[])
assert swallowed == '', 're-evaluate hook semantics on upgrade'
print('upstream pre_llm_call rejection swallowed: confirmed; NOT used as security gate')
# Execute the exact loader function from the pin without importing the unrelated
# gateway UI module (its clean archive currently has a missing Path annotation).
import ast
import contextlib
server_tree = ast.parse(pathlib.Path('tui_gateway/server.py').read_text())
loader_nodes = [n for n in server_tree.body if isinstance(n, ast.FunctionDef)
                and n.name in ('_load_enabled_toolsets', '_resolve_explicit_toolsets', '_with_session_toolsets')]
namespace = dict(os=os, contextlib=contextlib, _tui_notice=lambda x: None)
exec(compile(ast.Module(body=loader_nodes, type_ignores=[]), 'pinned-toolset-loader', 'exec'), namespace)
os.environ['HERMES_TUI_TOOLSETS'] = 'misspelled-toolset'
fallback = namespace['_load_enabled_toolsets']('cli')
assert fallback != ['misspelled-toolset'], fallback

# Deterministic discovery fixture registers schemas via the real registry. No invented
# provider reply; handlers are never called. MCP transport is a separate local test.
for name in sorted(EXACT_TOOLS):
    registry.register(name=name, toolset='mcp_haitlify',
        schema={'name': name, 'description': 'local schema fixture',
                'parameters': {'type': 'object', 'properties': {}}},
        handler=lambda **kwargs: 'unused')

Guarded = guarded_agent_class(AIAgent)
kwargs = dict(model='fixture-model', provider='custom', api_mode='chat_completions',
    api_key='NOT-A-CREDENTIAL', base_url='http://127.0.0.1:1/v1',
    skip_context_files=True, skip_memory=True, skip_background_review=True,
    quiet_mode=True, max_iterations=1, cwd=os.getcwd())
for selection in (['misspelled-toolset'], [], None):
    try:
        Guarded(enabled_toolsets=selection, **kwargs)
    except GateRejected:
        pass
    else:
        raise AssertionError('bad selection admitted')
baseline = AIAgent(enabled_toolsets=['mcp_haitlify'], **kwargs)
print('effective baseline:', [t['function']['name'] for t in baseline.tools])
a = Guarded(enabled_toolsets=['mcp_haitlify'], **kwargs)
assert {t['function']['name'] for t in a.tools} == EXACT_TOOLS
# Exercise the real request-assembly override without sending any request.
assembled = a._build_api_kwargs([{'role':'user','content':'no network probe'}])
assert {t['function']['name'] for t in assembled['tools']} == EXACT_TOOLS
print('assembled fields:', sorted(assembled))
# Mutate final effective snapshot AFTER successful real initialization. Turn entry
# must reject synchronously, before the real upstream facade executes anything.
for change in ('extra', 'missing', 'dispatch'):
    old_tools, old_names = a.tools, a.valid_tool_names
    if change == 'extra':
        a.tools = a.tools + [{'type': 'function', 'function': {'name': 'terminal'}}]
    elif change == 'missing':
        a.tools = a.tools[:-1]
    else:
        a.valid_tool_names = {'terminal'}
    try:
        a.run_conversation('must never reach model')
    except GateRejected:
        pass
    else:
        raise AssertionError('turn gate failed')
    a.tools, a.valid_tool_names = old_tools, old_names
assert not attempts, attempts
print(json.dumps({'verdict': 'PASS', 'scope': 'real AIAgent init + outer turn gate; no model',
                  'network_attempts': len(attempts), 'tools': sorted(EXACT_TOOLS),
                  'typo_fallback': fallback}, sort_keys=True))
