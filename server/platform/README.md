# Haitlify Backend

`server/platform` is the Java Spring Boot main platform backend for Haitlify.

## Legacy Agent transition

The inherited Agent code is scheduled for removal under the Hermes rebuild plan.
Legacy compatibility is not a release requirement. Removing the old Compose entry
does not itself remove or disable all inherited application code.

The Java package root is `com.haitlify.aitest.platform`; Maven coordinates are
`com.haitlify.aitest:platform`. Existing database names and applied Flyway migrations
remain unchanged to preserve deployed data and migration checksums.

Jira examples use the reserved `example.test` domain. Configure `APP_JIRA_BASE_URL`
and `APP_JIRA_PROJECT_JQL_HAITLIFY_API` for your own deployment.

## Current scope

- Health endpoint at `GET /api/system/health`
- Project registry APIs at `GET /api/projects` and `POST /api/projects`
- Case template APIs at `GET /api/projects/{projectId}/case-template/interface` and `PUT /api/projects/{projectId}/case-template/interface`
- Interface case library APIs at `GET /api/projects/{projectId}/interface-cases`, `POST /api/projects/{projectId}/interface-cases`, and `POST /api/projects/{projectId}/interface-cases/delete`
- Asynchronous Web automation proxy at `POST /api/web-automation/runs`, `GET /api/web-automation/runs/{runId}`, pause/resume/stop control routes, and `GET /api/web-automation/capabilities`
- Flyway migrations for the platform foundation, template config, and interface case library tables
- Portable environment-driven configuration for local, test, and later Linux container runtime

## API behavior notes

- Interface template responses include `updatedAt` when a project-specific template has been saved.
- Built-in default templates return `updatedAt: null`.
- Interface case library items preserve each case `savedAt` timestamp for local-first sync and conflict detection.
- Case save requests upsert by `libraryId`; ordering is derived from `metadata.runOrder`.
- Web automation create, query, and control requests are proxied to the independent Playwright runner. Create returns `202` immediately; total execution duration is not tied to the Java HTTP request timeout. If the runner is disabled the backend returns `501`; transport and Runner errors preserve actionable status details.
- `loopCount` is forwarded to the runner so API automation mock/local execution and real Web execution share the same repeat-run behavior.

## Local requirements

- Java 21
- Docker Desktop or another Docker runtime for MySQL

## Local deployment status

The legacy root Compose configuration has been removed. The replacement is the isolated stack under `deploy/isolated/` (see [deployment status](../../DEPLOYMENT.md)); it is **not yet verified on a target host** and must be started only through `scripts/preflight/start-isolated-stack.sh`. Do not start this backend against its inherited default database or reuse old platform volumes.

The source and isolated test suite remain available:

```bash
./mvnw test
```

Runtime startup requires the new isolated environment preflight first. Defaults below describe inherited source configuration, not approved connection settings for the new environment.

## Tests

Current controller coverage includes:

- system health
- project create/list
- case template get/save
- interface case library list/save/delete
- Web automation runner proxy enabled, disabled, and unavailable paths

## Private MCP session authorization

The private `/mcp` endpoint keeps an absolute, non-sliding session deadline:
`min(grant.expires_at, binding.expires_at, session creation time + 5 minutes)`.
`ExecutionBindingService.inspectAuthorizedGrant(String raw, String action)` returns
`AuthorizedGrant(Scope scope, Instant expiresAt)` from the same locked authorization
transaction; `expiresAt` is the earlier of the current grant and binding deadlines.
It preserves the existing lock order and ambient-transaction/reentry rejection.
The existing `withAuthorizedGrant` callback signatures remain unchanged.

The snapshot is not reusable authority: every request revalidates the bearer grant,
lease and current membership/actor/tenant/project authority. Tool calls additionally
use their existing authorization transactions. Revocation or a shortened deadline
rejects the next request, including at exact expiry. Requests and later expiry
extensions cannot renew an existing session. Sessions stay token/scope-bound, cache
only the token hash, and retain the 1,024-session ceiling and 120-tool-call budget.
This protocol behavior is not evidence of a real worker run or a healthy deployment.

## Environment

Copy values from `.env.example` into your local environment before starting the app.

```bash
export $(grep -v '^#' .env.example | xargs)
```

Default values:

- `APP_PORT=38082`
- `APP_VERSION=0.1.0-SNAPSHOT`
- `APP_DB_URL=jdbc:mysql://127.0.0.1:13306/haitlify_isolated?...`（隔离环境取值；改动会触发启动保护拒绝）
- `APP_DB_USERNAME=haitlify_app`
- `APP_DB_PASSWORD=`（空：没有默认口令，由 `deploy/isolated/.env` 提供）
- `APP_WEB_AUTOMATION_RUNNER_ENABLED=false`
- `APP_WEB_AUTOMATION_RUNNER_BASE_URL=http://127.0.0.1:19324`（隔离栈 web-runner 宿主端口）
- `APP_WEB_AUTOMATION_RUNNER_TIMEOUT_MS=120000` (short Runner command transport timeout, not total run duration)
