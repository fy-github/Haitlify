> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Web Automation Visual Run Control Design

## Goal

Replace the synchronous Web Runner request with a controllable asynchronous run. Users can open a right-side execution drawer, select an available browser, observe case and step progress, pause at a step boundary, resume, or stop while preserving evidence collected so far.

## Confirmed Product Decisions

- The execution surface is a wide right-side drawer so the Web case table remains visible.
- Pause finishes the current Playwright operation and blocks before the next step starts.
- Stop waits for the active Playwright operation to finish or time out, then closes the browser context at the same safe step boundary and retains completed logs and the last screenshot. A paused run stops immediately.
- The environment stores a default browser. The run toolbar can either follow case/environment settings or override all selected cases for that run.
- Browser choices are: follow case, Chromium, Google Chrome, Microsoft Edge, Firefox, and WebKit.
- The visual preview refreshes from step screenshots. It is not a video stream.

## Root Cause

The current frontend, Spring proxy, and Runner use one synchronous `POST /runs` request. The Runner returns only after every case finishes, so the Spring HTTP client timeout is also the maximum batch execution time. Increasing `APP_WEB_AUTOMATION_RUNNER_TIMEOUT_MS` delays the symptom but does not support progress, pause, stop, or reconnecting to an active run.

The current Runner also imports only Playwright Chromium and treats `APP_WEB_AUTOMATION_RUNNER_BROWSER_CHANNEL` as a process-wide setting. Although Web environments and cases contain a `browser` field, Firefox and WebKit selections do not affect browser launch.

## Architecture

### Runner Job Manager

`server/web-runner` owns an in-memory job manager. Creating a run validates the payload, allocates a run ID, stores a sanitized snapshot, starts an asynchronous worker, and immediately returns HTTP 202.

Each run owns:

- input cases and non-synthetic_secret runtime settings;
- a private runtime object containing credentials and variables;
- lifecycle status and control flags;
- current case, current step, and aggregate progress;
- completed case results, step logs, diagnostics, and latest screenshot;
- browser process, context, and page handles while active;
- creation, start, update, and end timestamps.

Jobs are retained for one hour after reaching a terminal state. The manager keeps at most 50 terminal jobs and removes the oldest first. Active jobs are never evicted. Runner restart ends in-memory jobs; the frontend reports them as unavailable instead of fabricating a result.

### Platform Proxy

The Spring backend remains a typed proxy. It creates jobs, fetches snapshots, forwards controls, and exposes Runner capabilities. Create and control requests use a short transport timeout because none of them wait for browser execution.

The existing runner timeout setting remains useful for connection and command transport failures, but no longer limits total execution duration.

### Frontend Poller

The frontend starts a run, opens the execution drawer, and polls the run snapshot approximately every 500 milliseconds while the job is active. Polling stops at a terminal status or when the drawer is closed. Closing the drawer does not stop execution. Reopening the active run resumes polling.

Only one poll is in flight at a time. Temporary polling failures use bounded backoff and retain the last good snapshot. A missing job after Runner restart is shown as an interrupted run with a retry action.

## HTTP Contract

### Runner and Mirrored Platform Routes

- `GET /capabilities`: returns Runner readiness and per-browser availability.
- `POST /runs`: creates a job and returns `202 { runId, status, createdAt }`.
- `GET /runs/{runId}`: returns a sanitized run snapshot.
- `POST /runs/{runId}/pause`: records a pause request.
- `POST /runs/{runId}/resume`: resumes a paused or pause-requested job.
- `POST /runs/{runId}/stop`: records a stop request and returns the latest snapshot.

The Spring routes use the existing `/api/web-automation` prefix.

### Run State

Run lifecycle values are:

- `queued`
- `running`
- `pause_requested`
- `paused`
- `stop_requested`
- `stopped`
- `completed`
- `failed`

`completed`, `stopped`, and `failed` are terminal. A completed run still uses existing per-case `passed`, `failed`, and `error` results.

The state flow is:

```text
queued -> running -> completed
                  -> failed
running -> pause_requested -> paused -> running
running|pause_requested|paused -> stop_requested -> stopped
```

The worker checks control state before every case and every step. A pause waiter resolves only after resume or stop. Stop closes the active page, context, and browser after the current step boundary and marks pending cases as stopped.

## Browser Selection

### Browser IDs

- `chromium`: Playwright bundled Chromium.
- `chrome`: Playwright Chromium with the `chrome` channel.
- `msedge`: Playwright Chromium with the `msedge` channel.
- `firefox`: Playwright bundled Firefox.
- `webkit`: Playwright bundled WebKit.

The Runner imports `chromium`, `firefox`, and `webkit`. A browser launcher resolves the requested ID to the correct engine and optional channel. It never silently falls back to another browser.

`GET /capabilities` probes each choice independently and returns availability plus a concise installation recommendation. The execution toolbar disables unavailable choices. The selected run override takes precedence over case browser, which takes precedence over environment browser, which defaults to Chromium.

## Snapshot and Evidence

Each poll snapshot contains:

- run ID, lifecycle status, selected browser, and timing;
- total/completed case counts and total/completed step counts;
- current case ID/name and current step ID/name/action/phase;
- current page URL;
- latest screenshot URL and capture timestamp;
- completed step logs and case results;
- console errors, page errors, failed requests, and terminal error details;
- flags describing whether pause, resume, and stop are currently allowed.

The Runner captures a viewport screenshot after each completed step and on failure. Capture errors are recorded as diagnostics but do not change the test result. Existing full-page failure screenshots remain attached to the failed result.

The snapshot must not include login usernames, passwords, auth tokens, cookies, variable values, or input step values. Logs include action and target but redact synthetic_secret-bearing values.

## Execution Drawer

The Web execution toolbar adds a primary `可视化执行` button. Starting an ordinary Web run also opens the same drawer so there is one execution model.

The drawer contains:

1. Header: run status, elapsed time, selected browser, and close action.
2. Browser toolbar: `跟随用例`, Chromium, Chrome, Edge, Firefox, and WebKit with unavailable options disabled.
3. Progress summary: case count, step count, current phase, and progress bar.
4. Main split area: latest browser screenshot on the left and ordered setup/test step timeline on the right.
5. Detail strip: current URL, current action, latest diagnostic, and screenshot timestamp.
6. Footer controls: pause while running, resume while paused, stop while active, and rerun after a terminal state.

Closing the drawer leaves the run active. The Web case table continues to show updated row statuses. A compact active-run indicator reopens the drawer.

## Error Handling

- Runner unavailable before create: show the existing startup/health recommendation.
- Browser unavailable: reject create with the unavailable browser ID and installation recommendation.
- Runner command timeout: keep the last snapshot and allow retrying the command.
- Polling interruption: show `连接中断` without changing the Runner job state; retry with bounded backoff.
- Runner restart or unknown run ID: mark the frontend view `执行已中断` and preserve the last known logs locally for the current page session.
- Stop during pause: stop immediately because no Playwright operation is active.
- Current step timeout or assertion failure: preserve existing failed-case behavior and continue according to the run mode.

## Scope

This iteration supports sequential execution and repeat count with full controls. Existing `parallel` mode remains accepted by the contract but executes through the current sequential worker until isolated browser contexts and parallel control semantics are designed separately.

No database persistence, distributed Runner coordination, live video streaming, or mid-operation process suspension is included.

## Test Strategy

### Runner

- State transition unit tests for create, pause, resume, stop, and terminal runs.
- Browser launcher tests for Chromium, Chrome, Edge, Firefox, and WebKit without fallback.
- Sanitization tests proving secrets and input values do not enter snapshots.
- Integration test with the local login fixture proving live snapshots, step-boundary pause/resume, stop, and nonblank screenshots.

### Platform

- Mock Runner tests for every new route, HTTP method, status code, and typed response.
- Timeout test proving run creation returns before the browser job completes.
- Error mapping tests for unavailable browsers and missing run IDs.

### Frontend

- Repository tests for create, poll, pause, resume, stop, capabilities, and retryable polling errors.
- View-model tests for lifecycle labels, progress, allowed controls, browser availability, and terminal states.
- Rendered browser verification for opening the drawer, choosing a browser, observing progress, pausing, resuming, stopping, closing/reopening, and preserving the case table layout.

## Acceptance Criteria

- Starting a long Web run returns a run ID without hitting the backend execution timeout.
- The user can select any available supported browser and the Runner launches that exact engine/channel.
- The drawer visibly advances through setup and test steps with a nonblank latest screenshot.
- Pause takes effect before the next step, resume continues it, and stop closes the browser and retains evidence.
- Closing and reopening the drawer does not stop or duplicate the run.
- Login credentials and runtime secrets never appear in snapshots, logs, or frontend state derived from polling responses.
