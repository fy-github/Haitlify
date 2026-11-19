#!/usr/bin/env python3
"""Five original single-site preflight mutations; exact existing tests, scratch only.

The normal full regression gate remains separate. No full-suite 30-second run,
no changes to original assertions, no Docker/model/provider/deployment actions.
"""
import importlib.util
import pathlib
import sys

root = pathlib.Path(__file__).resolve().parents[2] / 'deploy/isolated/worker-pool'
cases = [
    ('tools-open', 'gate.py', '    try:\n        names =', '    return EXACT_TOOLS\n    try:\n        names ='),
    ('provider-gate-open', 'edge.py', "                        require_tools(payload.get('tools'))", '                        pass'),
    ('rpc-extra-open', 'worker.py', "set(params) != keys[method]", 'False'),
    ('cleanup-fence-open', 'provisioner.py', "item['labels'] != expected", 'False'),
    ('lease-reuse-open', 'protocol.py', 'self.config is not None or self.spent', 'False'),
]
INVENTORY = ('tools-open', 'provider-gate-open', 'rpc-extra-open',
             'cleanup-fence-open', 'lease-reuse-open')


def main():
    spec = importlib.util.spec_from_file_location('strict_legacy_mutations', root / 'tests/verify_rpc_mutations.py')
    if spec is None or spec.loader is None:
        raise RuntimeError('strict evidence engine unavailable')
    engine = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(engine)
    oracle = engine.oracle
    targets = {
        # Python 3.9 attributes a failed context exit to the final body line;
        # 3.10+ attributes it to the with statement. Accept one exact frame only.
        'tools-open': oracle('test_gate.GateTests.test_gate_refuses_missing_extra_duplicate_and_typo',
                             22 if sys.version_info < (3, 10) else 21,
                             r'GateRejected not raised', {'bad': None}, {'bad': None}),
        'provider-gate-open': oracle('test_edge.EdgeTests.test_process_configure_deny_proxy_and_cleanup',
                                     131, r'200 != 403', {'role': 'provider', 'bad': []}, {'role': 'provider'}),
        # The extra-field scenario is prompt.submit timeout=9999 at L257,
        # not session.create L247: the latter is independently fenced in serve.
        'rpc-extra-open': oracle('test_worker_rpc.SubprocessTests.test_closed_fields_unknown_method_and_oversize_prompt',
                                 257, r"'error' not found in .*"),
        'cleanup-fence-open': oracle('test_provisioner.ProvisionerTests.test_plan_scoped_isolation_and_fenced_cleanup',
                                     31, r'Rejected not raised', {'labels': {}}),
        'lease-reuse-open': oracle('test_protocol.ProtocolTests.test_closed_config_and_fencing',
                                   29, r'Rejected not raised'),
    }
    selected = [dict(name=name, file=filename, before=before, after=after,
                     anchors=[before], oracle=targets[name]) for name, filename, before, after in cases]
    # Dynamic session_id cannot be hardcoded: validate the other exact params
    # against recorded runtime sid in the shared evidence engine.
    targets['rpc-extra-open']['params_scenario'] = {'text': 'x', 'timeout': 9999}
    return engine.run_gate(root, selected, INVENTORY, 'preflight', 30)


if __name__ == '__main__':
    raise SystemExit(main())
