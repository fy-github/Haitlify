# Independent worker pool — local slice, production BLOCKED

This directory is independent of `deploy/isolated/compose.yaml` and its existing
preflight rules. **No existing deployment, Java code, Hermes fork, personal config,
credential store or running container was modified.**

## What actually runs

- `provisioner.py plan`: validates a closed, digest-pinned pool specification and
  renders a per-slot Compose model plus **stopped-resource** prepare commands.
- `provisioner.py control --socket PATH --role mcp|provider`: host-only Unix-socket
  configure/status/revoke client; JSON comes from stdin (capabilities never in argv).
  Validates exact expected identity, role and state in both ack and status readback;
  unsafe/contradictory receipts fail nonzero without echoing peer content or retrying.
  No management HTTP route exists on the worker network.
- `edge.py`: real stdlib HTTP + separate owner-only mode-0700 Unix management socket
  process (created under umask 0077; verified by local process tests). The control
  client requires a real socket owned by its effective UID, mode 0700 or 0600.
  `--local-test` permits **only 127.0.0.1 HTTP upstream/listener**. Tests start real
  temporary proxy and stub upstream processes/servers, configure, forward, deny,
  revoke, then terminate only those test-owned processes.
- `worker.py`: restricted application JSON-RPC stdio entry using an upstream
  `AIAgent` subclass. No TUI command/config/profile/browser/admin RPC forwarding.
  Health never creates an agent. Only one session/incarnation, no resume or automatic
  respawn. EOF spends the in-process lease. See limitations below.
- `provisioner.py cleanup --dry-run`: emits removals **only** for full IDs whose
  complete pool/slot/incarnation labels match. No prune, broad deletion or real
  Docker client is implemented. `apply` always returns **BLOCKED, exit 2**.

**This is not a production host provisioner completion claim.** Docker create,
start, inspect/readback, firewall application and durable cleanup are intentionally
not executable in this slice. The supplied inventory is a dry-run fixture, not
trusted runtime inventory. The placeholder image digests are not available images.

## New configure protocol (application-owned, not Hermes)

Send on the trusted edge Unix socket:

```json
{"op":"configure","role":"mcp","config":{
  "protocol":"haitlify.worker.configure.v1",
  "binding":"binding-a", "generation":"generation-a", "lease":1,
  "incarnation":"abc123", "policy":"mcp-three-v1",
  "expiry":1900000000,
  "capability":"REPLACE-WITH-APPLICATION-MINTED-CAPABILITY"
}}
```

The timestamp above is illustrative, not an accepted live grant: expiry must be
strictly future and no more than one hour away. Unknown/missing fields, bool-as-int,
wrong policy and malformed IDs fail. Configure is single-use; no reconfiguration
following revoke. Revoke requires the exact full public identity and target role.
Configure/revoke wire requests require `role`; the edge checks its own role and
all expected revoke fields with exact types inside the same `lease.lock` as the
mutation and receipt snapshot. Unknown/missing fields fail closed; there is no
incarnation-only revoke path. Both edge instances must
receive the same server-derived binding/generation/lease/incarnation; **the
production caller that orchestrates and atomically admits this pair is missing**.
The edge holds authority in memory and never sends it in control receipts.
Successful ack/status contains only `ok`, `role`, `ready`, `revoked`, `spent` and
all seven public configure fields (everything except `capability`). Unconfigured
status has null identity, `ready=false`, `revoked=false`, `spent=false`; expiration
retains identity but sets `ready=false`; revoke retains identity and sets
`ready=false`, `revoked=true`, `spent=true`. Thus not-ready alone never proves revoke.

For the CLI, configure derives expected public identity from `config`; status and
revoke require an additional `expected` object containing **exactly** those seven
public fields, supplied from the trusted grant, not copied blindly from the peer.
Example stdin shapes: `{"op":"status","expected":{...}}` and
`{"op":"revoke","incarnation":"abc123","expected":{...}}` (expand the public
fields above; ellipses are not literal JSON). CLI configure stdin omits `role`;
the required `--role` supplies the target role for configure/revoke wire requests.
Revoke sends `expected` unchanged; only status omits it on the wire. Both
ack and readback must match every expected field with exact types and role;
configure/status must be active, while revoke must positively prove revoked/spent.
Missing/extra fields (including secret echoes), wrong boolean types and any mismatch
return a fixed safe diagnostic and exit 1. Configure and active-status verification
require a future expiry. CLI status is an active-readiness assertion: expired status
fails with `invalid expiry` (or state mismatch if expiry occurs during readback),
never a ready receipt. Raw wire status remains diagnostic with retained identity and
`ready=false`, `revoked=false`, `spent=false` after natural expiry. Revoke validates
identity structure without requiring a live grant, so an exact expired revoke and
an ack/readback spanning expiry can succeed only with `revoked=true`, `spent=true`,
`ready=false`. It cannot reactivate authority. Uncertain control outcomes still
require quarantine/cleanup; not-ready alone does not certify revocation.
There is no automatic configure retry and no pair-atomic admission implementation.

### Bounded control batch only — not worker cleanup acceptance

This local batch changes only control dispatch/budgets and uncertainty handling,
with a separate fixed Data pool as the minimum number-bound companion. It does
**not** authorize deployment, admission or slot reuse. That control-only batch changed
neither `worker.py` nor `protocol.py`; the later default-entry/factory and
chunked-framing changes are separate slices, described in their own sections, and are
not covered by that control acceptance. **Worker cleanup remains BLOCKED:** the known process-group
`EPERM` path can report cleanup success without proving all descendants gone.
An exited/zombie leader alone cannot resolve that ambiguity. The historical RPC
cleanup tests below do not close it; this control batch does not implement the
proposed cleanup lifecycle or deadline-tail design. Durable quarantine and host
cleanup are still absent, and `apply` remains **BLOCKED / exit 2**.

| Resource / operation | Implemented bound and boundary |
|---|---|
| Control dispatch | 8 fixed workers, queue 8, listen backlog 32 |
| Data dispatch | Separate 4 fixed workers, queue 4, listen backlog 16 |
| Overflow | Close the rejected connection; no handler/mutation for that queue rejection |
| Control frame | 1 MiB maximum; absolute 1 s from application `accept` return, including queue/dispatch delay |
| Control transaction | Absolute 3 s from `accept` return; lock acquire at most 1 s or remaining budget; deadline rechecked after parse and acquisition |
| Control ACK | Lock released after the atomic mutation/snapshot; all partial writes share at most 250 ms, also capped by the original 3 s deadline |
| CLI `control` / `reconcile` | Shared monotonic 10 s budget from post-argument initialization, including input, both exchanges and output |
| CLI stdin | EOF required; at most 1 MiB and 1 s, capped by shared budget |
| Each CLI exchange | At most 4 s total; connect at most 500 ms, all send calls share 250 ms, reads use remaining exchange/shared time |
| CLI stdout | Nonbuffered full-write requirement, at most 250 ms within shared budget; incomplete/unwritable output exits nonzero |
| Request body/framing | One shared **absolute 5 s** deadline from `do_POST` entry (not the socket idle timeout); every buffered read re-checks the remainder |
| Chunk size | Exactly RFC 7230 `1*HEXDIG`, at most 16 digits; `+`, `-`, `0x`, `_`, padding and whitespace are refused with a fixed local reason |
| Trailers | At most 32 lines and 8,192 bytes after the final chunk, each line at most 4,096 bytes; overflow is refused, never partially accepted |

Kernel backlog residence is **outside** the accept-anchored edge clock. A request
already buffered there may commit once after the CLI times out or closes; client
timeout is not rollback. Python JSON parsing and scheduler delays are not
hard-preemptible; the code checks elapsed budgets before proceeding. These are
cooperative monotonic I/O/commit checks, not hard realtime guarantees.

The queue-age check discards entries older than 3 s **when a worker becomes
available**, not via an independent queue sweeper. The request **body and chunked
framing** now carry one shared absolute deadline (5 s, re-checked between buffered
reads), so a drip that stays under the socket idle timeout can no longer hold a data
worker; the request line and header phase keep only the idle bound, and queue
residence still has no total lifetime bound. That header/queue remainder is a known
residual, not a bounded-request claim. Socket/worker/queue counts
are bounded by the dispatch design; this is not a system-wide FD peak guarantee.
Data still holds the same `lease.lock` across upstream network I/O and response
forwarding. A long-held lock makes control fail within its budget, **not revoke
immediately or successfully**. No unlocked forwarding or forced revoke is added.
A completed, verified revoke fences later sends; in-flight effects need separate
reconciliation. HTTP remains one request per connection with an HTTP/1.0 response,
including when the client requests keepalive or pipelines a second request.
Per-connection exceptions preserve accounting and fixed worker capacity; detected
worker loss closes service listeners, not an already-running upstream effect.

Control failure envelopes contain a fixed local reason, `control_state` and
`quarantine_required:true`, without echoing input/peer secrets:

- `NOT_SENT`: failure before the first send invocation. This is not proof that a
  previously configured lease is revoked or safe to reuse.
- `UNKNOWN`: set immediately **before** the first send syscall, including a failing
  first send, partial send, lost ACK or failed/mismatched readback. No mutation is
  automatically replayed. Normal successful control receipt shape is unchanged.
- `quarantine_required` is a caller obligation, **not an implemented quarantine**.
  The trusted host/caller owns holding the slot out of admission/reuse and arranging
  separately authorized quarantine/cleanup. No durable owner workflow exists here.

An explicit read-only observation is available:

```sh
python3 deploy/isolated/worker-pool/provisioner.py reconcile --socket PATH --role mcp
# stdin must end at EOF:
# {"operation":"configure" or "revoke","expected":{all seven trusted public fields}}
# The comment above describes the shape, not literal JSON to send.
```

`reconcile` accepts only `operation` and `expected`, never capability, and sends
exactly two original wire `status` requests on the successful two-exchange path
(transport failure can stop it earlier). It validates both against the caller's
trusted expected identity, exact role/types and requested state; it never learns
expected identity from matching peers. `configure` reconciliation requires live
ACTIVE observations. An expired exact revoked/spent lease can be REVOKED;
expired-but-unrevoked/not-ready is not revocation proof. Success reports
`control_state:VERIFIED`, `verified_state:ACTIVE|REVOKED`, and always
`admission_allowed:false`, `reuse_allowed:false`. It performs no configure/revoke,
retry, deletion, durable quarantine or pair-atomic admission.

#### Reproduce the bounded-control mutation gate

From repository root (no scratch runner source or fixed user path required):

```sh
python3 -B deploy/isolated/worker-pool/tests/verify_bounded_control_mutations.py
# Optional: --scratch SHORT_WRITABLE_PATH --output NEW_DIRECTORY_UNDER_SCRATCH
```

The standalone entry runs all **35 C + 20 U cases sequentially** (including 12
shutdown-failure regressions in C), not a parallel
stress run or a substitute for the original full-suite/Java/manifest gates. It
copies the frozen source to scratch, runs one exact GREEN baseline per case and
applies a single unique-anchor mutation to another scratch copy. Imported test
modules' legacy `SCRATCH` fixture globals are rebound in memory; production and
test source files are not rewritten. Default scratch is `~/.hermes/cache/scratch`;
keep a custom path short enough for Unix-domain socket names. Each selected test
has a 60 s harness timeout, not the obsolete full-suite 30 s limit.

The printed evidence directory retains `manifest.json`, `run.log`, source hashes,
per-case baseline/mutant logs and exit codes, unified diffs, anchor counts and
matched assertion tracebacks. `DETECTED` requires the specified assertion's actual
failure diagnostic in that exact test, with no errors/skips or unrelated failures.
Syntax/startup/dependency/timeouts, a red baseline or unrelated FAIL are `INVALID`;
a valid still-green mutant is `SURVIVED`. Either prevents exit 0. Counts and source
immutability are checked programmatically. The separate preflight and RPC runners
now also require exact GREEN baselines and specified assertion failures, rather
than arbitrary FAIL output. They retain five and eight logical cases respectively;
RPC `create-gate` explicitly mutates three separately validated sites and `cwd`
two, preserving their original combined effects. All other legacy cases remain
single-site. These mutation gates do not replace full regression or independent
review, and do not close the worker cleanup or production blockers.

Only MCP edge injects capability, only on its fixed `/mcp` route; provider edge injects
its own separately mounted provider key. Caller Authorization is discarded.
MCP service remains responsible for authoritative business grant revalidation.

The worker's **MCP client** sends no Authorization header — no grant, token or
placeholder sentinel — and reads no bearer/capability file. The provider SDK uses
`EDGE-NONSECRET-SENTINEL` solely as its nonsecret API-key placeholder; it is not a
provider credential. The MCP client target is the edge URL
and nothing else; the pinned SDK treats `headers` as optional
(`tools/mcp_tool_transport.py: _http_endpoint` / `headers or None`), so discovery
works without one. A `POOL_MCP_BEARER_FILE`-style variable is neither required,
accepted, nor forwarded to the lifecycle child. That keeps the raw capability in edge
memory only, where a compromised worker has nothing to replay; the trade-off is that
worker identity is the network attachment, so the host firewall is load-bearing.

Worker receives only model + incarnation (not capability, provider secret or
control directory). Dedicated per-slot identity comes from the network attachment,
not a caller-supplied authorization header. Local processes alone do not enforce
that identity; **local-test is NOT a sandbox or proof of OS isolation**.

## Fixed effective tool gate and real source evidence

Pinned source: Hermes **v0.22.2**, commit
`d923a5eb487a17c0a68801bba9ef0f56c81040a8`.

Inspected actual implementation:

- `tui_gateway/server.py:2048-2098`: invalid explicit toolset falls back to configured
  tools; on config/fold failure can return `None` (all tools).
- `agent/turn_context.py:798-852`: `pre_llm_call` exceptions are caught, logged and
  converted into empty context. The probe reproduces this. **Not an authorization
  hook.** `turn_api_request.py` also catches middleware/pre-request exceptions.
- `agent/agent_init.py:2435-2461`: clients, tool snapshot, memory/context engine,
  then injected tools. Gate is after real construction, not just config parsing.
- `tools.tool_search.enabled: off` is required. An initial real probe without this
  produced `tool_search`, `tool_describe`, `tool_call`, not the business tools.

The final set must be exactly:

```
mcp__haitlify__generation_context
mcp__haitlify__draft_create
mcp__haitlify__draft_get
```

`gate.py` checks final initialized schemas, dispatch-name equality, **before**
calling the upstream turn façade, and after main request assembly. Exceptions
propagate from the outer entry, rather than being swallowed by lifecycle hooks.
The fixed provider edge independently checks **every** outbound body; missing,
extra, duplicate or misspelled tools cannot reach its upstream. Auxiliary requests
without those tools are rejected as well, not silently bypassed. No fallback route.
`runtime-manifest.json` fingerprints the inspected critical source files (not the
entire image); image digest/provenance review is still required.

`worker-pool-probe.py` archives only pinned tracked git source into fresh scratch,
sets empty HOME/HERMES_HOME and an allowlisted environment, imports the real
AIAgent, registers deterministic tool schema fixtures through its real registry,
and rejects all socket connects. It exercises actual agent initialization and
request assembly plus negative turn-entry mutations, **without making a model
request**. It does not prove MCP discovery or a full successful conversation.
It also found a clean-source TUI import error (`methods_onboarding.py` missing
`Path`); no upstream patch was made. The loader fallback probe executes the exact
AST-extracted loader with test-only dependencies; it is not a successful TUI boot.
The selected worker bypasses that UI module and embeds AIAgent directly.

## Isolation templates and deliberately blocked gates

Rendered worker: dedicated slot internal bridge, fresh tmpfs HOME/workspace,
non-root, read-only root, all capabilities dropped, no-new-privileges, bounded CPU/
memory/PIDs, no mounts, no published ports, no restart. Each edge gets only its
own control directory; only provider edge gets its secret file. No Docker socket
in worker or platform; only trusted host may eventually operate pool resources.

An internal Docker bridge **does not by itself block the host gateway or peers**.
`network.nft.template` describes bridge + host INPUT/FORWARD filtering. It is
**not parsed/applied/tested on Linux**, and the Compose render intentionally has
no edge egress attachment: it is closed/incomplete, not a working production
network. Before enabling real start, the parent implementation must:

1. Bind interfaces, fixed worker/edge IPs and dedicated per-slot egress networks
   to inspected label+incarnation IDs; reject host-route/subnet collisions.
2. Install and read back firewall rules before start; constrain TLS destinations,
   DNS/IP pinning and certificates. Validate Docker/nft hook ordering. Never flush
   unrelated host rules. Do not give edges a shared DB/Runner/platform network.
3. Verify denial of host/gateway, DB, Runner, metadata, arbitrary Internet,
   other slots and same-tenant/different-user/project destinations; verify permitted
   MCP/provider flows. Inspect root/caps/mounts/network membership and old-resource
   inventory before/after. This requires a separately authorized Linux drill.
4. Wire production binding allocation to pair configure/readback then admission,
   revocation, expiry, owner disconnect, generation fencing and durable tombstones.
   Revoke receipts fence subsequent sends; in-flight effects remain reconciliation
   work. On control/configure uncertainty, destroy/quarantine—not auto retry/reuse.
5. Implement bounded host create/inspect/remove with immediate ownership recheck,
   exact IDs, no prune, durable cleanup/retry state, and actual deletion readback.

### Restricted RPC boundary / selected Java wire compatibility

This is an **application-owned POSIX stdio adapter**, not the full official TUI
Gateway. Its closed methods are:

| Method | Exact params | Receipt / meaning |
|---|---|---|
| `health` | `{}` | `alive` and `ready`; never spawns |
| `session.create` | `{}` or `{"cwd":"/workspace"}` | live `session_id`, `stored_session_id:null`, incarnation |
| `prompt.submit` | `{session_id,text}` | `{"status":"accepted"}`, flushed before dispatch |
| `session.interrupt` | `{session_id}` | `{"status":"interrupted"}` after group kill/reap |
| `session.close` | `{session_id}` | `{"status":"closed"}` after group kill/reap |

No arbitrary cwd (including `/state`, `/workspace/`, or null), model/tool/expiry
fields, resume, configuration, approval or command RPC is accepted. Unknown
methods return `-32601`; other refusals have sanitized `worker denied` errors.
Frames are capped at 1 MiB before JSON parsing; prompts/final text at 65,536
characters. An oversized or truncated transport closes rather than recovering a
possibly ambiguous stream. Same-ID retries and new-ID second submissions both
return errors, **not cached success**, and cannot invoke the agent again.

Create initializes the real native `AIAgent` in a fresh interpreter/process group;
the parent reader remains responsive to health/EOF during initialization. The
create receipt is emitted only after effective-tool and dispatch-name gates pass.
Before that receipt there is **no valid session ID**. Cancel pending create by
closing the client connection (the stdio owner must forward disconnect as EOF).
`session.close` / `session.interrupt` are valid **only after create succeeds**;
invented or absent IDs are refused, not initialization-cancel commands. Trusted
supervisor SIGTERM and the startup deadline also cancel initialization. These
paths kill the same owned group even when initialization spawned descendants.
`ready` means a gated local agent with its one submission still unused. It does
**not** prove provider readiness, MCP business authorization, grant lifetime or
model success. The host must persist the returned live binding before a later
lifecycle step sends the prompt; this worker cannot enforce the host transaction.

For a completed turn or session interrupt/close, exactly one final event follows
an admitted prompt when transport remains usable (supervisor shutdown/EOF is not
a terminal-event delivery guarantee):

```json
{"jsonrpc":"2.0","method":"event","params":{"session_id":"LIVE_ID","type":"message.complete","payload":{"status":"complete","text":"actual final_response"},"seq":1}}
```

The pinned native `agent/turn_finalizer.py` returns `final_response`, `completed`,
`failed`, `partial`, `interrupted` (and optionally `error`), not an RPC ACK. Only
strict completed success with nonempty text becomes `complete`; failed/malformed
results and exceptions become `error`, interruptions become `interrupted`, both
with empty text. Raw exceptions, native stdout/stderr and transcript/usage fields
are not forwarded. No stored-session or turn identifier is fabricated.
The finalizer is included in `runtime-manifest.json`. Reproduce/check all critical
fingerprints from the fixed commit's git blobs (never dirty working files):
`python3 deploy/isolated/worker-pool/tests/verify_rpc_manifest.py --source /path/to/hermes`.
Add `--emit` to print the deterministic manifest for review; it does not overwrite it.

Compatibility evidence: Java `HermesTcpGateway.createSession/submitPrompt` sends
these `cwd` / `session_id,text` fields; `HermesAssistantRuntime.submit` recognizes
`accepted` and nullable stored/turn IDs; `awaitFinalText` consumes this exact
`event` / `message.complete` / payload status/text shape. The reproducible harness
compiles the repository's current `HermesGateway`, `HermesTcpGateway` and
`HermesAssistantRuntime` with `javac`, then runs seven scenarios: success, error,
failed, partial, exception, interrupt and rejected `/state` cwd. It also asserts
single invocation, ACK-before-event, nullable durable/turn IDs and exact-PID cleanup.
**No Java code or production wiring is changed here**: Java configured with
`/state` must be changed by the integrating caller to `/workspace`. Run from repo root:

```sh
python3 deploy/isolated/worker-pool/tests/verify_java_rpc.py
```

Requires JDK 21+ and the script's listed local Maven jars (`--m2` may override the
cache); missing dependencies fail explicitly, with no downloads. Builds and
fixture state use isolated scratch. Its loopback TCP-to-stdio relay is **test-only,
not a production bridge**. The Java callers and worker are real; the agent is a
deterministic fixture, **not a model chain or real-provider acceptance**.

The trusted process setting `POOL_MAX_LIFETIME_SECONDS` is an integer 1–300,
default 300, measured monotonically from adapter startup (including idle/create).
Model/RPC input cannot extend it. The nonblocking supervisor services health/EOF
in initialization and running phases; interrupt/close require a created session.
It remains responsive while model work blocks, including a full child-input pipe.
EOF, oversized frames, deadline, interrupt/close and supervisor SIGTERM kill the owned process group;
leader reaping is bounded to one second and output waits to 250 ms. Local tests
verify a SIGTERM-ignoring agent and its descendant disappear with exact PID `ps`
readback, including pending-create EOF/deadline/SIGTERM, repeated SIGTERM and
signals during child publication/cleanup. The signal handler only requests stop:
it cannot raise inside `Popen` or reenter cleanup; group kill/reap stays owned by
the supervisor loop. Once cleanup finishes after SIGTERM, further SIGTERM is
ignored through interpreter teardown. Termination/disconnect does not promise a
final event or pending-create receipt. This is POSIX-only,
not a Windows/fork-portability claim. It is **not** containment for malicious
`setsid` escape, supervisor SIGKILL/host failure or production container cleanup;
those remain host sandbox/cleanup gates.

**Lease-expiry propagation is BLOCKED**: there is no trusted grant-expiry input
wired from control to this worker yet. The fixed startup cap does not assert
synchronization with the edge grant expiry. Do not accept an expiry from a prompt
or RPC to fill that gap. The edges remain authoritative at forwarding time.

Edge currently supports bounded non-streaming provider Chat Completions only;
SSE provider streaming and alternate providers are refused. MCP POST handshake/
list/call forwarding exists, but real Hermes MCP SDK negotiation through this
edge and the platform endpoint remains unverified. No actual provider API was
contacted. Docker `apply` remains **BLOCKED** and real isolation is unverified.

## Opt-in Linux lifecycle entry (development slice; production BLOCKED)

`worker_lifecycle.py` is a **new explicit entry**, not a replacement for
`worker.py`, its default `serve`, deployment templates or original regression gates.
It reuses `worker.Session`, `lifetime_seconds`, `terminal_payload` and the existing
effective-tool validator. It does not implement a second agent loop. The separate
`worker_bootstrap.py` starts with `-I -S -B`, uses stdlib-only private pipe I/O and
loads the factory only after a closed-schema, generation-bound parent permit.

### Two explicit factories (default entry vs opt-in lifecycle)

The default entry and the lifecycle entry no longer share one implicit factory
contract; a new factory requirement must not silently change how `python3 worker.py`
starts:

| Entry | Factory | Endpoints | Credential |
|---|---|---|---|
| `worker.py` (Dockerfile/provisioner default) | `worker:factory` | Fixed `http://mcp-edge:8080/mcp` and `http://provider-edge:8080/v1`; environment URLs are ignored | None, ever |
| `worker_lifecycle.py` (opt-in) | `worker:lifecycle_factory` | `POOL_MCP_URL` / `POOL_PROVIDER_URL`, each still required to match the one fixed route | None, ever |

Both factories require the **same generated closed home** and no capability. Only the
lifecycle factory honours the explicit slot endpoints. Both select `mcp-haitlify`
at construction and reject an initial tool set other than the exact three business
tools, without post-init narrowing. Neither entry reads a bearer
file, and the lifecycle child environment whitelist no longer carries one.

`worker.home_config()` is the single generator: `worker.main()` and
`worker_lifecycle.prepare_empty_home()` both write exactly that dict through
`write_home_config()` (`O_CREAT|O_EXCL`, mode 0600 from the first byte, so there is no
world-readable creation window), and `closed_home_config()` accepts exact equality
only — an extra operator key is a configuration channel these entries do not have.
The generated CLI toolset and both factories select `mcp-haitlify` (the native
registration group; server alias `haitlify`), not `mcp_haitlify`. The shared config
sets `agent.environment_probe: false`, matching the pinned `agent_init.py` reader;
a root-level `environment_probe` does not disable the native probe. No context
capacity, model, provider or credential setting is changed by this repair.

`tests/test_worker_entry.py` pins the generated fields literally. Its optional
`NativeFactoryTests` additionally run a fresh actual pinned AIAgent constructor,
native MCP candidate registration/tool selection and request assembly. Only the
MCP discovery transport is replaced with three local schema candidates; no model
or business handler is called. Supply `WORKER_PINNED_SOURCE` (credential-free git
archive), `WORKER_NATIVE_PYTHON` (literal venv executable), `WORKER_NATIVE_SITE`
(explicit dependency directory) and `WORKER_TEST_SCRATCH`; optional
`WORKER_NATIVE_EVIDENCE` retains audit traces. Missing native inputs produce
explicit skips, not compatibility evidence. The older `probe_pinned_runtime.py`
uses an invented registry group and is not evidence for factory compatibility.
Both constructors' initial snapshots must already equal the exact three tools. The
native negative forces `enabled_toolsets=None` through the real constructor, observes
the wider native set, and requires rejection before a session/create receipt.

Keep the native zero-attempt audit as diagnostics, not a toolset acceptance blocker:
`agent.environment_probe: false` suppresses the environment-probe subprocess,
but the pinned context/metadata initialization can still attempt DNS. Hooks block
and retain those attempts; the strict zero-network/process test must fail rather
than silently allowing DNS or inserting a fictitious context capacity. An import-time
urllib3 IPv6 loopback availability bind is recorded separately, not called outbound
traffic. These tests do not prove real MCP transport, model readiness, OS isolation
or side-effect-free lifecycle initialization. Kernel/network allowlist enforcement
and real MCP/provider-edge flows remain separate, unverified acceptance gates.

Trusted `POOL_STARTUP_SECONDS` is REQUIRED (integer seconds, positive and no greater
than `POOL_MAX_LIFETIME_SECONDS`); it has no default and cannot come from RPC.
Existing lifetime rules remain 1–300 seconds. Startup does not reset on activity.
The developer fixture can be invoked on the selected Linux/CPython 3.14.7 target:

```sh
POOL_STARTUP_SECONDS=1 POOL_MAX_LIFETIME_SECONDS=4 \
  python3 -B deploy/isolated/worker-pool/tests/worker_lifecycle_fixture.py
# stdin uses the existing JSON-RPC wire. Output is explicitly fixture-only.
python3 -B -m unittest discover -s deploy/isolated/worker-pool/tests \
  -p test_worker_lifecycle_handshake.py -v
python3 -B -m unittest discover -s deploy/isolated/worker-pool/tests \
  -p test_worker_cleanup_evidence.py -v
python3 -B deploy/isolated/worker-pool/tests/verify_worker_lifecycle_mutations.py
```

`python3 -B worker_lifecycle.py` selects `worker:lifecycle_factory` (the explicitly
configured factory), but **real
factory/dependency/HOME loading through this new entry is NOT integrated or
accepted**. No model, provider, MCP or context-capacity policy was changed to make
fixtures pass. This entry is not wired into the launcher or provisioner. Tests fail
rather than silently skip on unsupported systems; macOS/default Python 3.9 remains
BLOCKED. Local macOS bootstrap-only tests are development probes, not platform
support. Current preflight permits only the verified Linux aarch64/glibc 2.41
CPython 3.14.7 tuple and exact audited `subprocess.py` SHA256
`6628ffdd65c093a6c08cae01ffe82877d3ced515aac9e7be0cff16512c30a7d9`, plus the declared 64-bit glibc
sigaction ABI, waitid/WNOWAIT, default native SIGCHLD without SA_NOCLDWAIT, one
Python thread and no pending Popen reaper list. Native/thread reaper exclusion
beyond this narrow supervisor still needs independent call-graph/syscall review.

The owner retains a partial `SpawnOnly` before construction and keeps caller-owned
raw fds in a consume-before-close ledger. Owned cleanup has one destructive group
signal, non-consuming live observation, sole-owner bounded exact waitid reap,
SIGKILL exit verification, post-reap ESRCH and both endpoint closes. Candidate is
not terminal: holding and GREEN resource cleanup are required before publication.
Protocol outcome and resource cleanup remain separate; injected permission/close
errors cannot become success after natural EOF. Cleanup uses the fixed
`min(cleanup-entry, execution-deadline)+1.5s` budget; final delivery shares one 250ms
window. These cooperative checks cannot interrupt native process construction or
kernel close. Delivery failure exits nonzero and is not proof that cleanup failed.

Before publication, control draining has a shared **128-frame / 65,536-byte /
25ms** budget, capped by the final-delivery deadline. Health and denial writes use
that same drain deadline, not a fresh 250ms window. Already-buffered input counts
against the byte budget and is processed before another nonblocking read. At the
frame cap, any buffered remainder (even a fragment) or a zero-time readiness check
on stdin makes the outcome uncertain; there is no extra read after exhaustion.
Exactly 128 consumed frames may succeed only with an empty buffer and no kernel
readiness. Exact byte/time exhaustion conservatively fails even if the queue may
already be empty. Caps are not silently raised and no request is replayed.

An uncertain drain **must not publish `complete`**: it exits 1 and, if output is
still usable, emits the existing fixed `error`/empty-text terminal payload. An I/O
failure can instead terminate without a final event. No interrupt/close ACK is
invented for an unconsumed request (uncertainty suppresses even pending stop ACKs).
Resource cleanup may independently remain GREEN. Fully queued requests are not
reclassified as late just because the budget ran out. A normal incomplete open
frame below all caps is not an admitted request; EOF with a fragment fails. Input
after the final local publication decision cannot revoke an already-selected
payload; this boundary is before the event bytes become visible to the client.
The deadline checks bound cooperative I/O, not Python parsing or OS scheduling.

New tests queue controls behind a real HELD barrier; they cover 127/128/256 health
frames plus cancel, byte-cap/below-cap queues, buffered versus kernel remainder,
real 1MiB input, time-barrier/slow-drip and actual stdout backpressure. These are
lifecycle fixtures, not a real MCP/model acceptance claim. The mutation runner
retains its original ten sites and adds publication-specific sites. Classification
requires a green baseline, exactly one started test, exact failure/subtest IDs and
counts, and the specified AssertionError diagnostic. Duplicate identities,
unrelated failures, errors, skips and timeouts cannot pass the gate.

Unknown/no-PID/internal-errpipe disposition remains FAILED, with no guessed PID or
dangerous group signal. A pre-OWNED known direct child may get one exact-PID kill,
but that path never claims group GREEN. The audited constructor exec-error waitpid
is a separate pre-OWNED exception, not an alternative owned reaper. There is no
implemented durable external-owner handoff. The authorized Docker test harness
uses exact labelled container deletion/readback as a **test-only** fallback;
container removal does not turn a FAILED worker result GREEN.

Still BLOCKED: production admission/reuse and durable host ownership, escaped or
arbitrary descendants, supervisor SIGKILL/crash recovery, native construction hard
timeouts, real factory/MCP/model compatibility, original full regression and Java
caller gates on this entry, complete H1–H12 stress/race coverage, and fresh independent
implementation review. Passing these new fixture tests does not supersede any old
control/RPC/manifest gate or certify a production OS/Python support matrix.

## Reproduce (no Docker / model)

From repository root:

```sh
python3 -m unittest discover -s deploy/isolated/worker-pool/tests -v
python3 scripts/preflight/worker-pool-mutations.py
python3 deploy/isolated/worker-pool/tests/verify_rpc_mutations.py
# Harness requires Python 3.11.8+ (tar extraction data filter):
/Users/lify/.hermes/hermes-agent/venv/bin/python scripts/preflight/worker-pool-probe.py \
  --source /Users/lify/.hermes/hermes-agent \
  --python /Users/lify/.hermes/hermes-agent/venv/bin/python
python3 deploy/isolated/worker-pool/provisioner.py plan \
  --config deploy/isolated/worker-pool/pool.example.json
python3 deploy/isolated/worker-pool/provisioner.py apply
# Last command must exit 2 / BLOCKED. Do not run generated Docker commands.
```

Tests own ephemeral ports/processes and use clearly synthetic secrets. They prove
local policy/protocol behavior, **not actual container isolation PASS**, real MCP
business effects, successful model execution, or deployment readiness. Parent
independent review is still required.
