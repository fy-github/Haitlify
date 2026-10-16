> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Jira Read-Only Defect Proxy Design

## Goal

Add a backend-proxied, read-only Jira issue query path for the `缺陷` module so the page can display Jira issues alongside local defect drafts without binding the frontend directly to Jira web-session APIs.

This slice must also improve the current defect table usability by:

- displaying the `描述` field in the list,
- adding an `编辑` action in the operation column for local draft rows.

## Chosen Approach

Use a backend proxy in `server/platform` as the only Jira integration surface for the frontend.

Architecture:

1. frontend requests our backend,
2. backend queries Jira with a service account,
3. backend normalizes Jira responses,
4. frontend merges Jira issues into the existing unified issue list.

The API surface should reserve a later user-session mode, but the current implementation only supports service-account reads.

## Why Not Frontend Direct Jira Calls

The captured Jira traffic is a browser-session-oriented endpoint that depends on:

- cookies,
- XSRF/session state,
- same-origin browser context,
- page-internal request conventions.

That is too fragile for a product integration.

The backend proxy improves:

- authentication isolation,
- CORS simplicity,
- auditability,
- future cache/retry control,
- adapter stability if Jira endpoints change.

## Backend Contract

### Frontend-Facing API

Add a read-only backend endpoint:

- `GET /api/jira/issues`

Query params:

- `projectName` - required local project display name,
- `keyword` - optional keyword for summary/issue key narrowing,
- `startAt` - optional pagination start,
- `maxResults` - optional page size,
- `authMode` - optional, defaults to `service-account`, reserves `user-session`.

Response shape:

- `authMode`
- `items`
- `total`

The frontend should only call `service-account` mode in this slice.

### Jira-Facing API

Do not rely on the browser-internal `issueTable` endpoint as the primary product contract.

Prefer Jira’s standard search REST API through the backend proxy.

Use a project-scoped JQL mapping so local project names do not need to equal Jira project keys.

Configuration should support:

- Jira base URL,
- enabled flag,
- default auth mode,
- service-account username,
- service-account token/password,
- per-project JQL mapping.

## Authentication Strategy

### Current Slice

Implement:

- `service-account`

Reserve but do not implement:

- `user-session`

Design rule:

- the backend service interface must accept an auth mode,
- unsupported `user-session` mode should fail cleanly or return a controlled unsupported result,
- the frontend should not depend on `user-session` behavior yet.

## Frontend Data Flow

The defect page should:

1. keep local defect drafts as the durable editable source,
2. fetch Jira issues through a new frontend repository adapter,
3. normalize Jira responses into `JiraIssueSource[]`,
4. convert them into unified `DefectIssueRecord[]`,
5. merge them with local draft issue records,
6. render the merged table.

Current rule for scale and safety:

- only fetch Jira issues for the currently selected project,
- when `全部项目` is selected, skip Jira fetch and keep the table on local records only.

This avoids broad high-latency aggregate requests in the first slice.

## Unified Issue Model Rules

### Local Draft Rows

- source kind: `draft`
- source label: `本地草稿`
- editable
- export/delete actions apply

### Jira Rows

- source kind: `jira`
- source label: `Jira`
- read-only
- no local export/delete mutation
- no inline edit

## Defect Table Refinement

Add:

- visible `描述` column
- `编辑` action in the operation column

Behavior:

- local draft rows can open a defect-edit dialog,
- Jira rows should not show an active edit action in this slice,
- description should be visible in the table even before opening edit.

The goal is to make the current local defect workflow practical while Jira issues remain read-only.

## Non-Goals

This design does not include:

- direct Jira issue creation,
- Jira issue update,
- Jira status transition,
- Jira credential UI,
- frontend direct Jira calls,
- backend persistence for defects,
- `全部项目` full-Jira aggregation,
- deduplication between local drafts and Jira issues.

## Testing

### Backend

Add tests for:

- projectName to JQL mapping,
- service-account query execution,
- unsupported `user-session` mode handling,
- response mapping from Jira payload to backend DTO.

### Frontend

Add tests for:

- Jira issue normalization into unified records,
- merged issue list composition,
- read-only Jira row behavior,
- table action availability by source kind.

### Browser Verification

Verify:

- selected project can show Jira-sourced rows,
- `来源` shows `本地草稿` and future `Jira`,
- `描述` column is visible,
- local rows have `编辑`,
- Jira rows remain read-only.
