"""Application-owned gate; never use Hermes' fail-open lifecycle hooks as authorization."""
EXACT_TOOLS = frozenset('mcp__haitlify__' + n for n in ('generation_context', 'draft_create', 'draft_get'))


class GateRejected(ValueError):
    pass


def require_tools(tools):
    try:
        names = [t['function']['name'] for t in tools if t['type'] == 'function']
        if len(tools) != 3 or len(names) != 3 or frozenset(names) != EXACT_TOOLS:
            raise GateRejected('effective tools must be exactly the three business MCP tools')
    except (TypeError, KeyError) as exc:
        raise GateRejected('missing or malformed effective tools') from exc
    return frozenset(names)


def verify_runtime(root, manifest):
    import hashlib
    import pathlib
    root = pathlib.Path(root).resolve()
    if not isinstance(manifest, dict) or not manifest:
        raise GateRejected('runtime manifest missing')
    for name, expected in manifest.items():
        path = (root / name).resolve()
        if not path.is_relative_to(root) or not path.is_file():
            raise GateRejected('runtime file absent or escaped')
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise GateRejected('runtime source pin mismatch')


def guarded_agent_class(base, post_init_filter=None):
    """Outer subclass, not a Hermes fork. Check after init and BEFORE the turn façade.

    Also check the assembled main API request. External provider edge independently checks
    every outbound body. Do not catch/recover GateRejected in the entrypoint.
    """
    class GuardedAgent(base):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            if post_init_filter is not None:
                self.tools = post_init_filter(self.tools)
                self.valid_tool_names = {tool['function']['name'] for tool in self.tools}
            require_tools(self.tools)

        def run_conversation(self, *args, **kwargs):
            require_tools(self.tools)
            if self.valid_tool_names != EXACT_TOOLS:
                raise GateRejected('effective dispatch names differ')
            return super().run_conversation(*args, **kwargs)

        def _build_api_kwargs(self, *args, **kwargs):
            require_tools(self.tools)
            payload = super()._build_api_kwargs(*args, **kwargs)
            require_tools(payload.get('tools'))
            return payload
    return GuardedAgent
