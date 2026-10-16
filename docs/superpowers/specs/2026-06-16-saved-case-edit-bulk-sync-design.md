> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Saved Case Edit And Bulk Sync Design

## Goal

Close the remaining case-library backend persistence gap for already-saved interface cases.

This slice adds best-effort backend sync for:

- single saved-case edit from the edit drawer,
- `批量修改字段`,
- `字段赋值`.

The user experience should stay aligned with the existing migration strategy:

- local update first,
- backend sync second,
- backend failure must not block the user.

## Existing Context

The current Phase A app already syncs these saved-case actions to the Java backend:

- generated case save,
- saved-case delete,
- saved-case ordering,
- backend-preferred startup loading,
- startup conflict notice for newer local data.

Two saved-case maintenance paths still stop at localStorage only:

- `saveEdit` in `apps/web/src/App.vue`,
- `persistUpdatedLibraryCases` used by `批量修改字段` and `字段赋值`.

That creates an inconsistency: a user can load persistence-backed cases from the backend, edit them locally, refresh later, and still get older backend data.

## Chosen Approach

Keep the existing local-first sync strategy and extend it to saved-case editing and bulk maintenance.

Flow:

1. Update local saved cases immediately.
2. Determine which backend projects were affected from the updated saved cases.
3. For each affected project, read that project's current saved cases from local storage.
4. Reuse the existing backend save endpoint to upsert the full current project snapshot.
5. If all backend sync calls succeed, report backend success.
6. If any backend sync call fails, keep the local result and report local fallback.

This approach keeps the app resilient when the backend is offline and matches the behavior users already see for save, delete, and reorder.

## Alternatives Considered

### 1. Local-first per-project resync

Recommended.

- Smallest change from the current repository adapter.
- Reuses the existing `POST /api/projects/{projectId}/interface-cases` contract.
- Keeps offline-friendly local editing behavior.
- Preserves the current rule that the backend stores project-scoped case-library data.

### 2. Backend-first saved-case edits

Not chosen for this slice.

- Would make the backend the stronger source of truth.
- Would also change the current user expectation that edits are immediate and resilient offline.
- Would require larger UI error-state work for edit drawer and bulk dialogs.

### 3. New dedicated patch or bulk-update backend APIs

Not chosen for this slice.

- Could be more explicit long term.
- Expands scope into new backend contracts and controller/service behavior.
- Unnecessary while the existing save endpoint already supports upsert-by-`libraryId`.

## Backend Contract

No new backend API is added in this slice.

The frontend continues to use:

- `POST /api/projects/{projectId}/interface-cases`

Request body shape remains:

```json
{
  "cases": [
    {
      "id": "API-0001",
      "libraryId": "Haitlify.openapi.json:getProjectTree:/v4/pm/project-tree:正向",
      "savedAt": "2026-06-16T10:00:00.000Z",
      "row": {
        "caseId": "API-0001",
        "project": "Haitlify API"
      },
      "metadata": {
        "runOrder": 1
      }
    }
  ]
}
```

Why this is enough:

- existing save behavior already upserts by `projectId + libraryId`,
- bulk field maintenance mutates case content, not the storage identity,
- syncing the full current project snapshot also keeps project ordering aligned.

## Frontend Behavior

### Repository Adapter

Add one repository helper dedicated to post-local-mutation project sync.

Responsibility:

- accept one or more affected project names,
- resolve or create each backend project,
- read the current local saved cases for each affected project,
- send that project snapshot to the existing backend save API,
- return one compact success/fallback status to the caller.

Expected status contract:

- `backend`: every affected project synced successfully,
- `local`: at least one project fell back to local-only state.

This helper should not perform the local write itself.
Local writes already happen in `App.vue` and should stay there for this slice.

### Single Saved-Case Edit

Applies only when editing an already-saved case from the case-library edit drawer.

Flow:

1. Apply row edits.
2. Regenerate executable metadata from the row.
3. Apply the current field template.
4. Replace the saved local record and refresh the case library.
5. Sync the affected project to the backend.

User messaging:

- backend success: `已更新当前用例，并同步到后端`
- local fallback: `已更新当前用例`

Unsaved generated preview cases remain unchanged.
This slice does not change the behavior for editing generated-but-unsaved cases.

### Bulk Field Maintenance

Applies to:

- `批量修改字段`,
- `字段赋值`.

Flow:

1. Compute the updated saved-case set from the current selection or filtered scope.
2. Replace those local records immediately.
3. Clear selection and refresh the case library.
4. Sync all affected projects to the backend.

Affected project rule:

- derive project from `savedCase.row.project`,
- fallback to `默认项目`.

User messaging:

- backend success:
  - `已批量修改 X 条用例，并同步到后端`
  - `已更新 X 条用例字段，并同步到后端`
- local fallback:
  - `已批量修改 X 条用例`
  - `已更新 X 条用例字段`

### Project Partitioning

Bulk maintenance can span multiple projects when the current filter is `全部项目`.

Rule:

- local mutation still happens in one UI action,
- backend sync must partition by project,
- each affected project is synced independently using its own backend project record.

This matches the existing delete-sync rule and avoids mixing cases from different projects into one backend write.

## Failure Behavior

- Local mutation remains the durable first step.
- Backend failure must not revert local edits.
- No new conflict notice is needed for this slice because the operation is pushing newer local data outward, not choosing between local and backend startup state.
- Existing startup conflict handling remains unchanged.

## Non-Goals

This design does not include:

- automation library backend sync,
- defect or report backend persistence,
- backend-first editing,
- new patch or batch-edit APIs in `server/platform`,
- merge UI for concurrent local/backend edits,
- server-side version checks for edit conflicts.

## Testing

### Domain Tests

Add repository sync tests for:

- syncing one edited project through the existing save endpoint,
- syncing multiple affected projects after one bulk action,
- keeping local edits when backend sync fails.

### App Verification

Verify these flows manually in the browser:

- edit and save one already-saved case,
- run `批量修改字段` on a filtered set,
- run `字段赋值` on a filtered set,
- confirm success/fallback messages stay consistent with backend availability.

### Regression Verification

- `npm test -- --run` in `apps/web`
- `npm run build` in `apps/web`

## Notes

- Keep this slice small and symmetric with the existing repository adapter style.
- Prefer one reusable project-sync helper over ad hoc fetch logic in multiple `App.vue` handlers.
- Do not expand scope into automation or defect persistence until the saved-case workflow is fully consistent end to end.
