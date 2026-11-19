# Private draft MCP adapter (disabled by default)

`app.assistant.mcp.enabled` defaults to `false` in the bean declaration. No live configuration is changed.
The endpoint is **exactly** `<contextPath>/mcp`, handled by a dedicated `@Order(-100)` terminal security filter, not MVC.
The chain always exists, including when ordinary `app.security.enabled=false`. Disabled requests still require a valid grant before returning 404; missing/invalid credentials return 401.

## Wire contract

- Fixed MCP **2025-11-25**, JSON-RPC 2.0, Streamable HTTP JSON response mode. No SDK or agent/model loop.
- Every method requires exactly one `Authorization: Bearer <execution grant>` and a fresh `generation_context` authorization. Cookie headers and any query string are rejected. An Origin header is always rejected (403); only non-browser/no-Origin clients are supported.
- `POST`: `Content-Type: application/json` (optional UTF-8 charset), `Accept: application/json, text/event-stream`.
- `initialize` negotiates to 2025-11-25, advertises only `tools`, returns `Mcp-Session-Id`. Send `notifications/initialized` (no id), receive 202 and empty body, then call tools.
- Subsequent POSTs require the session header. The version header may be omitted because the fixed negotiated session version is known; an unsupported header is 400.
- `ping` returns `{}`; `tools/list` returns exactly `generation_context`, `draft_create`, `draft_get`.
- `tools/call` requires `name`; `arguments` may be omitted (normalized to `{}`), but explicit null is invalid. Context/get accept only empty arguments; create requires `{spec: <object>}`. Never submit a JSON string as `spec`.
- `tools/list` and `tools/call` accept protocol `params._meta` objects (including string/number progressToken and extension metadata). Metadata is not forwarded into business arguments or authorization; no progress notifications are promised. Business argument extra fields remain rejected. No cursors are issued; supplied cursors remain invalid.
- Every tool calls the existing `GenerationDraftTools` method, which independently authorizes its own action and transaction. The adapter never wraps or nests an authorization callback.
- Unknown method: -32601; unknown tool/malformed call: -32602. Draft invalid-spec/not-found/conflict/unavailable errors return `isError:true` with fixed messages. Authentication failure remains HTTP 401/403.
- Notifications never receive JSON-RPC response ids. Unsupported notifications and unsolicited responses are rejected with HTTP 400.
- GET/DELETE/other methods return 405 after authentication, Origin and protocol-header validation. No SSE, resumability, server requests, resources, prompts, sampling, tasks, or OAuth discovery.

## Bounds and isolation

Actual body reads stop at 65,537 bytes and reject more than 64 KiB, including chunked requests. UTF-8 decoding, JSON duplicate keys, trailing tokens, depth (32), number length (128), object envelope and id types are strict. Batches are unsupported. Ids are carried as Jackson tree nodes, not floating-point/coerced strings.

Sessions use 256-bit secure random ids and store token SHA-256 plus complete authorized scope/binding/lease. Their absolute expiry is the minimum of grant expiry, binding expiry (returned by the same locked authorization transaction), and session creation plus five minutes; it does not slide or extend when a grant is extended. Maximum 1,024 concurrent session records; overflow is 503. Every request revalidates current authority: expired/revoked authorization returns 401 before session lookup; expired/unknown/cross-token sessions with otherwise valid authorization return 404. Each session accepts at most 120 tool calls, then 429. This is a local session budget, not distributed/per-actor DoS protection; deployment still requires private-network access and ingress connection/time/rate limits. No deployment is included.

The machine chain disables CSRF, security-context persistence, request cache, browser login/logout and session creation. It neither reads nor writes browser sessions and does not fall through to MVC. All adapter responses are `Cache-Control: no-store`; fixed errors do not echo raw tokens, sessions, request content or SQL.

Draft schema is closed at every object level. The existing service additionally enforces 32 KiB specs, <=100 steps, exact authorized URL origin, no fill/input/select/secrets, and transactional same-payload idempotency. Schema is not a network SSRF, redirect, runner or acceptance policy. This endpoint creates DRAFTs only, never confirms, executes, publishes or invokes a model.

## Second path: `/mcp-lease/{leaseId}` (capability never leaves the process)

`app.assistant.mcp.lease.enabled` defaults to `false`. When false the lease path **does not exist**: every method answers 404 and `McpLeaseRegistry` is not created at all (no place in the JVM holds a grant token on its behalf).

When enabled, a caller (a model gateway) is configured with only `https://<platform>/mcp-lease/<leaseId>`:

- `leaseId` is 43 base64url chars from 256 `SecureRandom` bits. It is not derived from any business id; every issuance is independent, and re-arming a conversation rotates the id instead of minting a second capability.
- The real grant token never travels. `Authorization`, `Cookie` and any query string are rejected (401) on this path, and an `Origin` header is 403. The path itself is the only credential.
- The shim resolves the lease to the token in memory and then runs **the same** `McpEndpoint` code: same authorization snapshot, session binding/limits, TTL, protocol validation, tool authorization and error statuses. There is no second, weaker validation.
- Unknown, expired and revoked leases are indistinguishable: 401 with the fixed body `{"error":"MCP request rejected"}`. Forged paths (wrong length, extra segment, matrix parameter) are 404; wrong methods are 405 after authentication.
- Nothing is persisted or logged: the registry keys entries by SHA-256 of the lease id, `toString()` is redacted, and the SQL tables keep only the grant hash this design already used.
- Bounds: effective deadline is `min(now + ttl, grant expiry)`; `ttl-seconds` defaults to 300 and is capped at 5 minutes; capacity defaults to 256 and exhaustion fails closed (`LeaseUnavailableException`), never falling back to a shared or longer-lived credential.
- Revocation is platform-internal (`McpLeaseRegistry.revoke` / `AssistantConversationLeaseProvisioner.disarm`), never an HTTP surface.

The lease path is intentionally independent of `app.assistant.mcp.enabled`: closing raw `/mcp` to everyone while exposing only per-conversation lease URLs is the normal production shape.

`app.assistant.conversation.mcp-lease.enabled` (default `false`) is the only producer. Per turn it issues a normal grant for that turn's generation/binding (`ExecutionBindingService.issueGrant`), registers the lease in memory, and keeps the resulting URL in `AssistantConversationLeaseProvisioner`. The URL is never returned in an HTTP response and lease provisioning never changes the turn's ledger state: without an ACTIVE binding the turn simply gets no MCP endpoint, with the same ledger outcome it would have had otherwise.

### Turn ordering: admission → one gateway permit → arm → configure → reload → dispatch

The lease is armed **before** the prompt is dispatched (not after the turn is accepted). Arming after dispatch is a
real-model defect, not a cosmetic one: Hermes reads the MCP server entry when it builds the session for a turn, so a
URL that appears only after `prompt.submit` describes the *previous* turn.

The harder problem is that this read is a **binding, not a lookup**: the entry has exactly one value for the whole
gateway, and the session keeps whatever that value was at the moment it was created. Overwriting the entry for
another turn or another conversation is therefore **a capability mismatch, not a degradation**: the session that
already read the old value, or the session that reads the new value, ends up holding a
`/mcp-lease/<leaseId>` that authorises a *different* turn's generation (`draft_create` writes into someone else's
requirement). Revoking the lease afterwards is too late by construction — the URL was already read. "The stale URL
would only get 401" is **not** a mitigation for overwriting an entry that another session is about to read, and this
document does not claim it as one; 401 is only what an unknown/expired/revoked lease answers.

Because the entry is global, the platform serialises the MCP part of a turn behind a **gateway-wide single permit**
(`AssistantConversationService` + `GatewayMcpPermit`, one holder per JVM):

- **Holder only**: the turn that holds the permit may arm its lease and write the entry. The permit is taken
  *before* arming and held until that turn reaches a terminal state (`COMPLETED`/`UNKNOWN`/`FAILED`), including the
  reply-timeout path, a failed dispatch, an acceptance-window `Error`, and the shutdown sweep. Release is a single
  idempotent primitive called from structural `finally` blocks only, and it only releases its own holder — a
  non-holder's cleanup can never free somebody else's permit.
- **Terminal state**: the holder clears the entry with `apply(null)` **while it still holds the permit**, and only
  then releases it. That order is load-bearing in both directions: releasing first would let the next turn
  arm/configure, and the late `null` would then wipe *its* entry; not clearing at all would leave the entry
  pointing at a dead lease until the next turn or the TTL.
- **Non-holder → explicit refusal**: a submit that cannot take the permit does **not** arm, does **not** write the
  entry (not even `null`), and is **not dispatched**, so no gateway session is created. It fails as `FAILED` with
  HTTP 503, a fixed detail containing `MCP_PERMIT_BUSY`, and the same fixed trace in-process. "Runs without tools"
  would **not** be a safe degradation and is not what happens: the entry is bound when the session is created, so
  such a session would bind the **holder's** URL — the same capability mismatch in the other direction. The client
  retries with a new `requestId`; the refused one stays `FAILED` in the ledger and is never dispatched later.

The exact order on the submit path (`AssistantConversationService.submitAccepted`):

1. **Local admission** — ledger/handle gates only, no wire call and no lease touched. They run first on purpose:
   arming revokes the same conversation's previous lease, so a turn that is about to be rejected by the ledger must
   not be allowed to arm (that would cut the endpoint the running turn is still using).
2. **Gateway permit** — taken before arming; see above.
3. **Arm** — `AssistantConversationLeaseProvisioner.arm(conversationId, actorId, projectId, requestId)` mints (or
   rotates) this turn's lease and returns `<base>/mcp-lease/<leaseId>`.
4. **Configure** — the URL is handed to the injected `GatewayMcpConfigurator`, whose implementation writes the
   gateway MCP entry **first** and triggers `reload.mcp` **afterwards** (`GatewayMcpLeaseConfigurator` +
   `GatewayMcpControl`). Reloading before writing would reload the old entry and still look successful.
5. **Dispatch** — `session.create`/`session.resume` + `prompt.submit`, which is when the gateway actually reads
   that entry.

On the terminal state the order is: ledger first (`COMPLETED`/`UNKNOWN`/`FAILED`), then `apply(null)` while the
permit is still held, then the permit release, then the in-process concurrency gates (`activeTurns`/`liveTurns`).
The reply consumer, the timeout path, the acceptance-window `Error` exit and the shutdown sweep all leave through
that one sequence, so a finished turn never leaves the entry bound to its own lease.

Failures at each step stay local to that turn's MCP availability:

- **No endpoint** (no ACTIVE binding for this requestId, arming failed, or the lease wiring is off): the *holder*
  calls the configurator with `null`, which **clears** the entry. It is never "skip the call" and never "write the
  previous turn's URL": skipping leaves the old URL in place, and rewriting it hands the model a capability that
  belongs to a finished turn. A *non-holder* never reaches this step at all — it was refused before arming.
- **Permit busy**: see above. The turn ends `FAILED` (a determinate failure: not one frame reached the gateway),
  returns 503 with the fixed `MCP_PERMIT_BUSY` detail, and never creates a session. It is deliberately not
  `UNKNOWN`: an `UNKNOWN` row would make the conversation look like it "may have reached the wire" and every later
  turn of that conversation would be refused for lack of a session handle.
- **Configurator throws**: the turn is still dispatched (only the MCP tools are lost), the ledger is untouched, and
  the arm is revoked (the endpoint nobody can reach is a capability, not a convenience). A fixed trace
  `MCP_CONFIGURE_FAILED` is kept in-process for reconciliation
  (`AssistantConversationService.mcpTrace(conversationId, requestId)`; it never contains the URL, a lease id or a
  token). A configure failure must not be reported as a successful configuration.
- **Dispatch fails** (`queued`, protocol error, transport error): the lease for that turn is revoked immediately,
  so no orphan endpoint survives the failed turn; the permit is released on the same exit.

`app.assistant.conversation.mcp-lease.configure-gateway` (default `false`) is the switch for step 4. When it is off
the configurator is not even injected and the service never calls it — a gateway entry is never touched. When it is
on, the deployment must supply **both** a `GatewayMcpControl` bean (write + reload) **and** a
`GatewayMcpConfigurator` that is the `GatewayMcpLeaseConfigurator`; otherwise startup fails. Both checks exist
because the old check only looked at the bean type: a hand-registered `url -> { }` implementation passed it, wrote
nothing, and the platform reported "configured" while the gateway kept the previous URL.

### The real control: `ConfigFileGatewayMcpControl` + `TcpGatewayReloader` (off by default)

`app.assistant.conversation.mcp-lease.gateway-config-path` has **no default**. Only when it is set does the context
register `ConfigFileGatewayMcpControl` and the `GatewayMcpLeaseConfigurator` that wraps it; a `GatewayReloader` bean
is then mandatory (startup fails without it). That bean is the real `TcpGatewayReloader`, and it is **transport
agnostic** — it only needs "send one RPC, get the raw reply", so both transports reuse the same judgement (the `T`
is historical, not a scope). Which gateway it talks to is decided by assembly:

- `app.assistant.hermes.transport=tcp` → `HermesTcpGateway::rpc`: the existing connection, newline-delimited framing
  and pending table (no second protocol; `client.capabilities` is still advertised on connect).
- `app.assistant.hermes.transport=stdio` (**and the default when unset**, matching `HermesTuiGateway`'s
  `matchIfMissing`) → `HermesTuiGateway::rpc`: the same child process, framing and pending/id table used for
  `session.create` / `prompt.submit`. That is the real deployment path (there is no `socat` in a default host), so
  stdio is a first-class landing point, not a fallback.

`rpc(method, params, timeout)` in both clients has the same contract: it returns the **raw** envelope (`error` is not
thrown — the reloader needs "explicitly refused" apart from "transport broken"), it takes a **per-call budget**
(`gateway-reload-timeout-seconds`, default 60 s: a reload is a global shutdown + rediscover), and it reuses the
client's own framing/pending map instead of a second protocol. On stdio a timeout fails **only that call** and drops
its pending entry (a slow reload does not poison a healthy child process); EOF fails every in-flight request. Neither
path may block other in-flight calls: the write lock covers "start + write + register pending" only, never the wait.

Two things stay off by default: without `gateway-config-path` no control, configurator **or** reloader bean is
registered (nothing touches a gateway file), and `configure-gateway` is still `false`. Setting the path without a
reload landing point (e.g. an unknown `transport` value) still fails at startup rather than writing a file nobody
reloads — that fail-fast is deliberate.

The control does exactly four things, in this order:

1. **Write one entry** — it reads the given file (never creates it, never follows a symlink), replaces **one** entry
   under the **top-level** `mcp_servers` mapping (`mcp_servers.<name>`, `<name>` defaults to `haitlify`) by line
   surgery, then writes through a same-directory temp file (`0600`) + `fsync` + atomic `rename` (`0600` on the
   result). The entry is replaced **as a whole** with `{url: <lease URL>}`: a leftover `auth`/`headers`/`command`
   from an earlier turn or a hand edit would otherwise hand the next session another still-valid credential. Every
   other server entry (e.g. `mcp-atlassian`, `figma`), every unrelated top-level key, comments and indentation are
   left byte-for-byte. The rewritten text is re-parsed and must equal "old model with one entry changed" before it is
   written; anything it cannot explain (flow-style parents, duplicate keys, a missing `mcp_servers` section, an entry
   without a `url`, a file changed by another process since the read) fails with a reason code and leaves the file
   untouched. The legacy `mcp.servers` layout is refused for the same reason: the gateway does not read it, so
   writing there would be a silent no-op that still looks configured.
2. **Reload** — through the injected `GatewayReloader`, i.e. `reload.mcp` with `confirm: true` and a 60 s budget
   (a reload shuts down and rediscovers **every** MCP server, not just this entry, and it is not scoped per
   session). Success is `status == "reloaded"` **and** a non-blank `loaded_rev`; `coalesced: true` is reported as
   such. `confirm_required` is a **successful RPC that reloaded nothing** and is therefore an explicit failure
   (`CONFIRM_REQUIRED`), never a success. A write that failed leaves no pending write, so `reload()` is refused:
   reloading the old file would look like success.
3. **Read back** — the file is re-read and, if a `GatewayMcpReceipt` bean exists, the gateway receipt is compared
   too. Both must show this turn's URL (or both must show no entry). "reload returned" is not "the entry is mine".
4. **Roll back** — on a failed reload or a read-back mismatch the previous entry is restored (removed if there was
   none) and reloaded again. If the restore write or the second reload fails, the current state is left alone and a
   redacted `ROLLBACK_FAILED` exception asks the caller to revoke the lease; `reload()` never reports success in that
   state.

Concurrency inside one instance is serialised by one lock, and a reload whose write was superseded by another
thread's write is **refused** (`CROSSED_RELOAD`, or `NO_WRITE_PENDING` if the other sequence already finished) rather
than reloading or rolling back somebody else's entry. This is
per JVM; the deployment-level permit item below is still required for replicas.
Exceptions, `toString()` and logs carry a fixed reason code only — no URL, lease id or token, and external exception
messages are deliberately not chained (they may print the URL).


### One requestId per turn, rotation, and why expiry needs a new binding

- Each turn is armed under `(conversationId, requestId)`, so a new turn never receives the previous turn's URL; arming a new turn revokes every lease of that conversation first.
- Arming the **same** requestId again while its lease is still resolvable rotates only the opaque id: same grant, same generation/binding, same (never extended) deadline, and one grant per binding (`uq_assistant_execution_grant_binding`) still holds. That makes a submit retry safe without accumulating reusable capabilities.
- Once a lease has **expired** (≤ 5 minutes) or the platform has **restarted** (the registry is in-memory), the same binding cannot be re-signed: the grant unique key already has a row for it, so `issueGrant` fails and the turn has no endpoint. Recovery therefore requires a **new binding** (a new generation/requestId), i.e. a new turn; extending or re-minting a lease on the same binding is out of scope by design, not an oversight. Until then the turn simply has no MCP endpoint: step 4 clears the entry (`apply(null)`) *before* dispatch, so that turn's session is created with no MCP server at all (an unknown/revoked lease answers 401, which is a fact about that lease, not a mitigation for a mis-bound entry).

### Still required before a real model may call `draft_create`

1. Gateway MCP server config value must be the lease URL (the only place a lease id appears outside the JVM's memory), never a grant.
2. A **real-environment** run of `TcpGatewayReloader`: the reloader now sends `reload.mcp` over the existing gateway
   client (`confirm: true`, 60 s budget) and refuses anything but `status == "reloaded"` with a non-blank
   `loaded_rev`, but it is still proven only against stubs — a loopback TCP JSON-RPC stub for `transport=tcp` and a
   throwaway stdio child process (`fake-tui-gateway.mjs`) for `transport=stdio`, which covers the success path, a
   `confirm_required` refusal, an RPC error frame, a timeout and an EOF. What is missing is the deployment half:
   a config file that actually has a top-level `mcp_servers` section (the control refuses to create one), a gateway
   whose `approvals.mcp_reload_confirm` policy is compatible with an automated `confirm: true`, and one observed
   turn that shows the reloaded entry reaching `session.create`. Until then treat "configured" as "written and
   reloaded", not "the model used our lease".
3. Wiring (already in place, still off by default): set `gateway-config-path` +
   `APP_ASSISTANT_CONVERSATION_MCP_LEASE_GATEWAY_CONFIG_PATH` on the deployment and `configure-gateway=true`. With
   the switch on, startup fails unless **both** beans exist and the configurator is the lease implementation (a
   hand-registered no-op is rejected instead of silently keeping the previous entry).
4. **Concurrency policy — a parallel conversation is refused, not degraded.** What the permit guarantees: only one
   turn per JVM may arm/configure/dispatch; a second submit is refused (`FAILED` + HTTP 503 + `MCP_PERMIT_BUSY`)
   *before* it arms, writes the entry or creates a session, and the holder keeps the permit until its terminal state.
   That removes both mismatch directions: "A configures, B overwrites, A's session is created against **B's** lease"
   and "B's session is created while A's entry is present and inherits **A's** URL". It also means the product
   allows **exactly one MCP-capable turn at a time** per JVM; a turn longer than the lease TTL keeps MCP closed for
   every other conversation for that whole time.
   Required before multi-user rollout — **both**, not either:
   - **per-session MCP entries**: one entry per session/conversation (a gateway that accepts a per-session server
     list). That is what actually makes concurrent conversations possible; until it exists, a parallel turn must be
     refused rather than run against somebody else's capability;
   - **a deployment-level permit**: the permit is **per JVM**, while two replicas share one gateway configuration
     file, so per-JVM exclusion alone cannot serialise multiple replicas.
   A refusal is a normal retryable outcome (new `requestId`), not an operator alarm.
5. Lease lifetime vs turn lifetime: TTL is capped at 5 minutes, so a turn longer than that loses tool access
   mid-flight. Either keep turns shorter than the TTL or give the real phase a re-provisioning path that starts from
   a **new** binding (see above). Because the permit is held until the turn's terminal state, a turn that outlives
   its TTL also blocks MCP for every other conversation for that whole time — one more reason a real deployment needs
   either per-session entries or a shorter-permit rule than "the whole turn".
6. Observability: `MCP_CONFIGURED` / `MCP_NO_ENDPOINT` / `MCP_CONFIGURE_FAILED` / `MCP_PERMIT_BUSY` /
   `MCP_REVOKED` are in-process traces keyed by `(conversationId, requestId)`, holding fixed constants only
   (no URL, no lease id) and bounded to 256 entries (oldest evicted). Production reconciliation still needs them
   exported somewhere durable — the ledger deliberately records nothing about MCP, and these traces die with the
   process.
   `MCP_PERMIT_BUSY` means the submit was **refused** (no dispatch, no session); the refusal is also on the ledger
   row as `FAILED` with a fixed detail, so it does not depend on this in-process table alone.

## Verification

MCP tests use real H2 migrations, real lease allocation/grants and real internal services. MockMvc checks the security split; a localhost random-port Tomcat test sends real HTTP/1.1 including unknown-length/chunked bodies. No live database, model, worker or operator credential is used. Run from a scratch copy with the real sibling `../web-runner/runner-core.mjs` fixture when running the whole suite.

Authoritative protocol sources:
- https://modelcontextprotocol.io/specification/2025-11-25/basic/transports
- https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle
- https://modelcontextprotocol.io/specification/2025-11-25/server/tools

This is a constrained private MCP endpoint, not an OAuth-compliant public MCP server or a model end-to-end acceptance result.
