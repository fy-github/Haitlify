> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Defect Project Grouping And Jira-Ready View Design

## Goal

Refine the `缺陷` module so the left workspace rail becomes a project grouping navigator, while the right side stays a scalable defect issue list.

This slice must:

- align the left-side export/delete buttons cleanly,
- add stable spacing between those buttons,
- show project-name grouping in the left workspace area,
- let clicking a project name filter the right-side issue list to that project,
- keep the page ready for a future Jira API integration where local drafts and Jira issues are shown together.

## Existing Context

The current defect page already has:

- local defect draft storage in `defectRepository.ts`,
- filter helpers in `defectView.ts`,
- a right-side defect table with inline editing,
- a top filter bar including project filter,
- a left `当前工作区` area that still uses placeholder-style summary content.

That means the page already has the raw data and filtering behavior.
What is missing is a scalable project-first navigation model and a future-proof issue-list abstraction.

## Chosen Approach

Keep the current defect page structure, but introduce a view-model layer that separates:

1. project grouping for the left pane,
2. issue list records for the right pane.

In this slice:

- the view model is built only from local `DefectDraft` data,
- the UI treats those records as generic defect issues,
- the design reserves a merge point for future Jira API records.

This keeps the implementation small while avoiding a rewrite later when Jira-backed data arrives.

## Future-Ready Data Model

### Unified Issue View

Introduce a defect issue view model for rendering, not persistence.

It should represent one row in the right-side list regardless of source.

Required properties:

- stable `id`,
- `projectName`,
- issue source kind:
  - `draft`,
  - future `jira`,
- display status,
- summary,
- priority,
- assignee,
- issue type,
- issue key when available,
- source-specific editable fields where allowed.

In the current slice:

- all rows come from local drafts,
- the source kind is always `draft`.

Later Jira integration can append or merge:

- Jira API issues,
- source kind `jira`,
- read-only or partially editable rows depending on Jira capabilities.

### Project Group View

Introduce a compact project grouping model for the left pane.

Required properties:

- `value`: project name,
- `count`: number of visible issues for that project,
- `sources`: optional aggregate by source kind for future mixed data,
- optional active flag derived in the UI.

The left pane should also include:

- `全部项目`,
- total count across all visible issue records.

## Left Pane Behavior

### Button Layout

The two left-pane actions:

- `导出选中/筛选`,
- `删除选中/筛选`

should become a vertically stacked action group with:

- equal width,
- aligned left and right edges,
- consistent row height,
- visible spacing between buttons.

This change is purely layout refinement and does not change behavior.

### Project Grouping Navigation

The placeholder-style `失败自动化转缺陷` block should be replaced by a project grouping list.

Behavior:

1. Render `全部项目` first.
2. Render one item per project with count.
3. Highlight the active project selection.
4. Clicking a project item sets the right-side project filter.
5. Clicking `全部项目` resets the project filter to `ALL`.

This left-side navigation becomes the primary project filter surface.
The existing top filter bar project select can remain for compatibility, but the left pane is the faster navigation path for large issue sets.

## Right Pane Behavior

### Current Slice

The right table continues to render the current defect draft list, but through the new issue view model.

Expected behavior:

- selecting a project on the left filters the right table,
- the `项目名称` column remains visible,
- the user sees all issue rows for that project in one list.

### Future Jira API Slice

When Jira API integration is added later:

- Jira issues should be normalized into the same issue view model,
- the right table should support merged display of:
  - local drafts,
  - Jira issues,
- the source should be visible through a compact tag or status label,
- project grouping should aggregate both sources.

This slice should not implement Jira API calls now.
It should only avoid blocking them later.

## Performance Direction

The left project grouping should be computed once from the current issue records rather than repeatedly filtering in the template.

Expected rendering flow:

1. Build unified issue records.
2. Build project group counts from those records.
3. Filter the right-side issue list by active project selection and other filters.
4. Render only the filtered right-side list.

This supports future large Jira result sets better than treating the entire defect page as one ungrouped table.

## Editing Rules

Current local draft rows remain inline-editable.

Future Jira rows should be designed as one of:

- read-only by default, or
- editable only through a dedicated Jira-sync action.

This slice should not commit to Jira row inline editing.
It only needs a view-model shape that can distinguish source.

## Non-Goals

This design does not include:

- direct Jira API integration,
- Jira authentication,
- Jira issue creation or update through API,
- backend persistence changes for defects,
- merged deduplication logic between local drafts and Jira issues,
- pagination or virtualization for defect rows,
- replacing the existing top defect filters.

## Testing

### Domain Tests

Add tests for:

- building project grouping counts from current issue records,
- left-side project grouping order and `全部项目` aggregate,
- filtering unified issue records by selected project.

### UI Verification

Verify in the browser:

- left action buttons align and have visible spacing,
- left project groups render counts,
- clicking a project changes the right-side list,
- clicking `全部项目` restores all rows,
- the right table still shows project name and current draft editing behavior.

## Notes

- Keep this slice local-data-backed but Jira-ready.
- Prefer introducing a small domain helper rather than embedding grouping logic directly in `App.vue`.
- The important architectural move is not “show a project list”; it is “stop binding the defect page directly to one storage source.”
