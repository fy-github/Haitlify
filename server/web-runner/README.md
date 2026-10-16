# AI Test Platform Web Runner

`server/web-runner` is the lightweight local Web automation runner process for V2 Web execution.

## Current scope

- `GET /health` for startup and readiness checks
- `GET /capabilities` for per-browser driver availability
- `POST /runs` to create an asynchronous Web automation run
- `GET /runs/{runId}` to query live progress, screenshots, logs, and diagnostics
- `POST /runs/{runId}/pause`, `/resume`, and `/stop` for step-boundary controls
- repeat execution through `loopCount` with a guarded range of `1` to `100`
- launches the exact requested Chromium, Chrome, Edge, Firefox, or WebKit driver
- executes a small first slice of Web actions:
  - `goto`
  - `click`
  - `input`
  - `assertVisible`
  - `assertHidden`
- retains completed asynchronous jobs in memory for one hour, up to 50 terminal jobs

## Run request shape

```json
{
  "mode": "sequential",
  "loopCount": 2,
  "browserOverride": "firefox",
  "cases": []
}
```

When `loopCount` is greater than `1`, the runner expands each case per loop and suffixes the result `caseId` with `#1`, `#2`, and so on.

## Local startup

```bash
cd server/web-runner
NPM_CONFIG_CACHE=/private/tmp/ai-test-platform-npm-cache npm install
npx playwright install chromium firefox webkit
node server.mjs
```

Default address:

- `http://127.0.0.1:19323` (non-common port to avoid host conflicts; Docker compose overrides to container-internal `9323`)

You can override the port:

```bash
APP_WEB_AUTOMATION_RUNNER_PORT=19324 node server.mjs
```

The Runner binds to `127.0.0.1` by default. Exposing it on another interface must be explicit:

```bash
APP_WEB_AUTOMATION_RUNNER_HOST=0.0.0.0 node server.mjs
```

Login discovery blocks private, loopback, and link-local targets by default. Allow only the internal hosts required by the test environment:

```bash
APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOSTS=app-dev.example.test,*.internal.example.com node server.mjs
```

Private-network access for login discovery is allowed by default (trusted internal test environments). Disable it in controlled environments:

```bash
APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOW_PRIVATE_NETWORKS=false node server.mjs
```

You can also override the default browser and headless mode:

```bash
APP_WEB_AUTOMATION_RUNNER_BROWSER_CHANNEL=chromium
APP_WEB_AUTOMATION_RUNNER_HEADLESS=true
node server.mjs
```

When a public Web entry calls a private-network API, Chromium may enforce Local Network Access checks. The compatibility flag is enabled by default for trusted internal test environments; disable it explicitly if needed:

```bash
APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS=false node server.mjs
```

If you want the process to fail fast when the browser environment is not ready:

```bash
APP_WEB_AUTOMATION_RUNNER_STRICT_STARTUP=true node server.mjs
```

## Java backend integration

`server/platform` uses these env vars:

- `APP_WEB_AUTOMATION_RUNNER_ENABLED`
- `APP_WEB_AUTOMATION_RUNNER_BASE_URL`
- `APP_WEB_AUTOMATION_RUNNER_TIMEOUT_MS`

Example:

```bash
export APP_WEB_AUTOMATION_RUNNER_ENABLED=true
export APP_WEB_AUTOMATION_RUNNER_BASE_URL=http://127.0.0.1:19323
export APP_WEB_AUTOMATION_RUNNER_TIMEOUT_MS=5000
```

`APP_WEB_AUTOMATION_RUNNER_TIMEOUT_MS` controls short create/query/control transport requests. It no longer limits the total browser run duration.

## Health check

```bash
curl http://127.0.0.1:19323/health
curl http://127.0.0.1:19323/capabilities
```

Expected healthy response:

```json
{
  "status": "UP",
  "service": "ai-test-platform-web-runner",
  "defaultBrowser": "chromium",
  "message": "Playwright browser launch is ready (chromium)"
}
```

The capabilities response reports each driver independently. Chromium, Firefox, and WebKit use Playwright-managed binaries; Chrome and Edge require the corresponding system browser.

Typical recovery actions:

```bash
cd server/web-runner
npx playwright install chromium
```

Or switch to Playwright-managed Chromium explicitly:

```bash
APP_WEB_AUTOMATION_RUNNER_BROWSER_CHANNEL=chromium node server.mjs
```

## Notes

- Keep this runner as a separate process from the Java backend so browser dependencies and service restart behavior stay isolated.
- When the runner is not started, the Java backend returns a controlled unavailable response.
- The current runner defaults to Playwright Chromium to reduce local browser-channel drift.
- `/health` performs a startup environment check and exposes recommendations when browser launch is not ready.
- Runs are in memory. Restarting the Runner interrupts active jobs and clears retained snapshots.

## Docker startup

If the local macOS browser environment is restricted, prefer the containerized runner:

```bash
docker compose up -d web-runner
curl http://127.0.0.1:19323/health
```

This container uses the official Playwright image and isolates browser startup from the host desktop process rules.
