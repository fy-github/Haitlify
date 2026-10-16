> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Case Library Delete And Order Sync Design

## Goal

Extend the existing local-first backend sync strategy from save-only into two more case-library actions:

- deleting saved interface cases,
- syncing project-scoped case order changes.

The user experience should stay the same: local updates happen immediately, backend sync is best-effort, and backend unavailability must not block case-library operations.

## Chosen Approach

Use the existing local-first pattern as-is:

- mutate local storage first,
- attempt backend sync after the local write succeeds,
- if backend sync fails, keep the local result and avoid interrupting the user.

This keeps the current migration strategy consistent with template save sync and generated-case save sync.

## Alternatives Considered

### 1. Local-first incremental sync

Recommended.

- Smallest change from the current implementation.
- Reuses the existing backend project lookup/creation helper.
- Keeps the app usable when `server/platform` is down.

### 2. Backend-first case library mutations

Not chosen for this slice.

- Would make backend the clearer source of truth.
- Also requires loading, empty-state, error, and fallback changes that are outside this narrow scope.

### 3. Full conflict-aware sync now

Not chosen for this slice.

- Would require `updatedAt` or version fields, merge rules, and user-visible conflict messaging.
- Better handled after backend-first loading exists.

## Backend Contract

### Delete API

Add a batch delete endpoint for saved interface cases:

- `POST /api/projects/{projectId}/interface-cases/delete`

Request body:

```json
{
  "libraryIds": [
    "Haitlify.openapi.json:getProjectTree:/v4/pm/project-tree:正向"
  ]
}
```

Response body:

```json
{
  "deleted": 1,
  "total": 3
}
```

Design notes:

- The endpoint accepts one or many `libraryId` values so single delete and bulk delete share the same contract.
- `libraryId` is not used as a path segment because it can contain `/` and `:`.
- Missing `libraryId` values are ignored so repeated delete requests stay idempotent enough for retry/fallback use.

### Order Sync

Do not add a new backend reorder API for this slice.

Reuse the existing save endpoint:

- `POST /api/projects/{projectId}/interface-cases`

The frontend sends the current saved cases for one project in the desired order. The backend continues to upsert by `libraryId` and updates `metadata.runOrder` for existing records.

## Frontend Behavior

### Delete Sync

Applies to:

- single case delete,
- delete selected cases,
- delete current filter scope.

Flow:

1. Capture the affected saved cases before local deletion.
2. Delete from local storage immediately.
3. Group deleted cases by project name from `row.project`.
4. For each project group:
   - resolve or create the backend project with `ensureBackendProject`,
   - call the batch delete API with that group's `libraryId` values.
5. Refresh local view state regardless of backend result.

User messaging:

- backend success: `已删除 X 条，并同步到后端`
- local fallback: `已删除 X 条`

### Order Sync

Applies to saved case-library row move up/down actions only.

Flow:

1. Reorder local saved cases first.
2. Persist local order with `saveCaseOrdering`.
3. Determine the affected project from the moved case.
4. Read the current saved cases for that project from local storage.
5. Sync only that project's ordered cases back to the existing backend save API.

User messaging:

- backend success: `已更新排序，并同步到后端`
- local fallback: `已更新排序`

### Project Partitioning Rule

Delete and order sync must partition by project instead of using the current page-level project selector.

Why:

- the local case library can contain cases from multiple projects,
- bulk delete can span multiple projects,
- backend case records are stored under a single backend project.

Project name source:

- primary: `savedCase.row.project`
- fallback: `默认项目`

## Ordering Constraint

This slice treats case ordering as project-scoped, not globally cross-project.

Rule:

- if the current visible move would cross into a different project, block the move and show a warning asking the user to filter to a single project before sorting.

Reason:

- backend persistence currently has no global order across projects,
- syncing only per-project order keeps backend and local semantics aligned for this slice.

## Non-Goals

This design does not include:

- backend-first initial loading,
- conflict detection or merge resolution,
- template conflict handling,
- saved case edit-drawer sync,
- bulk field edit sync,
- automation-library delete/order sync.

## Testing

### Backend

- add controller/service test coverage for batch delete by `libraryId`,
- verify deleting a missing `libraryId` does not fail,
- verify save-list ordering still returns cases sorted by updated `runOrder`.

### Frontend

- add repository sync tests for:
  - grouped delete across multiple projects,
  - delete fallback when backend is unavailable,
  - project-scoped order sync using existing save API,
  - order sync fallback when backend is unavailable.

### UI Smoke Check

- single delete,
- delete selected cases,
- delete current filter scope,
- move up/down inside one project,
- blocked cross-project sort in `全部项目` view.
