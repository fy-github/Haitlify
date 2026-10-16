> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Local Project Data Portability Design

## Scope

Add a versioned local project data package for the current frontend-first Phase A workbench.

The package includes:

- saved interface case library,
- interface field and case type template configuration,
- local API execution environment.

The package does not include uploaded OpenAPI source files, generated-but-unsaved preview cases, UI filters, or browser-only navigation state.

## Format

Use a JSON document with a stable marker and schema version:

- `kind`: `ai-test-platform.project-data`
- `version`: `1`
- `exportedAt`: ISO timestamp
- `payload.caseLibrary`
- `payload.templateConfig`
- `payload.executionEnvironment`

Import should validate the marker and version, then replace the three local data sections in browser storage. Unknown future fields are ignored so later versions can remain forward-compatible.

## UI

Add compact `备份数据` and `恢复数据` actions in the case library workspace toolbar. The case library is the safest first location because it is where users naturally expect persistent local data controls.

`备份数据` downloads a UTF-8 JSON file.
`恢复数据` accepts a JSON file and refreshes the in-memory case library, template configuration, and execution environment after import.

## Related UI Cleanup

Remove the decorative process canvas from the interface case generation page. Workflow and process visualization will later belong to `全景视图` and `图表`, not the urgent generation workspace.

## Import Button Reliability

Replace programmatic clicking of a `display:none` file input with a native file input overlay inside the visible import button area. This keeps the interaction inside a direct user gesture and avoids browser-specific suppression of hidden file input clicks.

## Testing

Add domain tests for:

- exporting all supported local data sections with version metadata,
- importing valid project data into storage,
- rejecting invalid package markers.

Keep existing parser, generator, repository, export, and build verification green.
