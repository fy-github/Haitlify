> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Minimal Sync Conflict Design

## Goal

Prevent backend-preferred startup loading from silently overwriting newer local browser edits.

This is the first conflict-handling slice. It protects data but does not add a merge UI yet.

## Policy

Use timestamp comparison during backend-preferred loading.

- If backend data is newer or local data has no timestamp, mirror backend data into local storage.
- If local data is newer, keep local data and report `conflict`.
- If backend is unavailable, keep local data and report `local`.

## Template Configuration

Template configuration gets lightweight local metadata:

- storage key: `ai-test-platform.template-config-meta.v1`
- field: `updatedAt`

Backend template responses include `updatedAt` for saved project templates. Backend default templates may omit it.

Conflict rule:

- local `updatedAt` > backend `updatedAt`: keep local template and return `source: "conflict"`.
- otherwise load backend template and update local metadata.

## Case Library

Saved cases already have `savedAt`.

Conflict rule for the current project:

- compare the latest local project `savedAt` with the latest backend project `savedAt`.
- local latest > backend latest: keep local project cases and return `source: "conflict"`.
- otherwise replace local project cases with backend cases.

## Non-Goals

- no merge UI,
- no per-field template merge,
- no per-case merge,
- no server-side version checks,
- no user-facing conflict drawer yet.

## Follow-Up

The next conflict slice was to expose a compact UI state showing that local data was kept because it is newer than backend data, with actions to push local data or reload backend data.

This is now implemented in the Phase A web app as a compact workspace notice with `推送本地`, `使用后端`, and dismiss actions.
