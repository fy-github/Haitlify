> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Backend Preferred Loading Design

## Goal

Add a small backend-preferred startup loading step for the persistence-backed Phase A data that already has backend APIs:

- interface case template configuration,
- saved interface case library for the current project.

The page should still work when the Java backend is unavailable.

## Chosen Approach

Keep the current local-first write behavior, but load backend data first on app startup when available.

Startup flow:

1. Resolve the current backend project name.
2. Try to load template configuration from the backend.
3. Try to load saved interface cases for that project from the backend.
4. If backend data is returned, mirror it into local storage and refresh Vue state.
5. If the backend is unavailable, keep local storage data.
6. If the backend case list is empty, keep local case-library data for now.

## Data Rules

### Template Configuration

Template configuration already has `loadTemplateConfigWithBackend`.

Rule:

- Backend response replaces local template configuration.
- Backend failure falls back to local template configuration.

This is acceptable because the backend template API always returns either a saved project template or the backend default template.

### Case Library

Add `loadSavedCasesWithBackend`.

Rule:

- Backend cases for the current project replace local cases for the same project.
- Local cases from other projects stay untouched.
- Empty backend case lists do not clear local cases in this slice.
- Backend failure falls back to local cases.

Reason:

- The backend does not yet have conflict detection.
- An empty backend project may be newly created by project resolution.
- Clearing local browser data from an empty backend response would be too risky before conflict handling exists.

## Current Project Scope

This slice loads one project only.

Project name source stays consistent with the current sync adapter:

- active imported OpenAPI project,
- first generated case project,
- selected case-library project,
- `默认项目` fallback.

It does not scan all backend projects.

## Non-Goals

This design does not include:

- conflict detection,
- merge UI,
- multi-project backend preload,
- backend-first saving,
- automation-library backend persistence,
- defect/report backend persistence.

## Testing

### Domain Tests

- Loading backend cases writes them into local storage.
- Loading backend cases keeps other local projects.
- Empty backend cases keep local storage unchanged.
- Backend failure falls back to local storage.

### App Verification

- `App.vue` calls backend loading during `onMounted`.
- Loaded template config refreshes selected case types.
- Loaded case library refreshes the case-library table state.
