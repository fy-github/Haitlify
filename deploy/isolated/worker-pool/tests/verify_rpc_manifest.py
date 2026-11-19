#!/usr/bin/env python3
"""Check/emit RPC runtime fingerprints from pinned git blobs, never working files.

Usage: python3 tests/verify_rpc_manifest.py --source /path/to/hermes [--emit]
--emit prints deterministic JSON for review; default checks the shipped manifest.
No imports, source execution, network or working-tree writes.
"""
import argparse
import hashlib
import json
import pathlib
import subprocess

PIN = 'd923a5eb487a17c0a68801bba9ef0f56c81040a8'
FILES = ('run_agent.py', 'model_tools.py', 'agent/agent_init.py',
         'agent/turn_api_request.py', 'agent/turn_facade.py', 'agent/turn_context.py',
         'agent/conversation_loop.py', 'agent/chat_completion_helpers.py',
         'tools/mcp_tool_discovery.py', 'agent/turn_finalizer.py')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=pathlib.Path)
    parser.add_argument('--emit', action='store_true')
    args = parser.parse_args()
    manifest = {name: hashlib.sha256(subprocess.check_output(
        ['git', '-C', str(args.source), 'show', PIN + ':' + name])).hexdigest() for name in FILES}
    if args.emit:
        print(json.dumps(manifest, indent=2))
    else:
        shipped = pathlib.Path(__file__).resolve().parents[1] / 'runtime-manifest.json'
        assert json.loads(shipped.read_text()) == manifest, 'shipped manifest differs from pinned RPC runtime files'
        print('PASS: pinned git blobs match all ' + str(len(manifest)) + ' runtime fingerprints')


if __name__ == '__main__':
    main()
