> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Defect Jira Source Adapter Boundary Design

## Goal

Turn the `缺陷` module from a local-draft table into a unified issue-list surface that is structurally ready for future Jira API issues, while simplifying the left workspace rail so it only shows project grouping.

This slice must:

- keep the current local defect draft workflow working,
- make the right issue table render unified issue records instead of raw drafts,
- keep local draft rows editable,
- reserve a clear adapter boundary for future Jira API issue records,
- remove the left-pane `导出选中/筛选` and `删除选中/筛选` buttons,
- remove the left-pane gray summary blocks such as `缺陷草稿` and `已转 Jira`,
- move the left-pane project section upward to fill that space.

## Existing Context

The current defect page already has:

- a local defect draft repository,
- project grouping in the left pane,
- a unified `DefectIssueRecord` model for local drafts,
- a right-side defect table that now displays unified issue records,
- local draft editing, export, and delete actions in the main right-side workspace.

That means the UI is already halfway toward a merged issue list.
The next gap is not layout alone; it is the data-source boundary.

## Chosen Approach

Introduce an explicit issue-source adapter layer in `defectView.ts`, but keep the current slice local-only.

The unified issue list should be assembled in three steps:

1. normalize local drafts into issue records,
2. reserve a parallel normalization path for future Jira API issues,
3. merge issue records into one renderable list.

For now:

- only local draft issue records are produced,
- merge behavior is trivial because there is one source,
- the UI still renders through the merged list shape.

This keeps the current feature small while making future Jira integration a data-source addition rather than a page rewrite.

## Left Pane Simplification

The left `当前工作区` area should become a pure project navigator.

Remove:

- `导出选中/筛选`,
- `删除选中/筛选`,
- gray summary cards such as `缺陷草稿` and `已转 Jira`.

Keep:

- one grouped project list,
- `全部项目`,
- active project highlighting,
- per-project counts.

The export/delete actions already exist in the right-side defect toolbar and do not need duplicate entry points in the left pane.

## Unified Issue Source Boundary

### Local Draft Source

Local drafts remain the only actual data source in this slice.

Normalize them through a dedicated adapter function:

- `buildDefectIssueRecordsFromDrafts(...)`

This function should return unified issue records with:

- `sourceKind: 'draft'`,
- `sourceLabel: '本地草稿'`,
- display status text,
- Jira key when available,
- a pointer back to the original local draft for editing and deletion.

### Future Jira Source

Do not implement Jira API transport yet.

But reserve a parallel adapter contract such as:

- `buildDefectIssueRecordsFromJiraIssues(...)`

The design expectation is:

- Jira records will normalize into the same view-model shape,
- they will use `sourceKind: 'jira'`,
- they may be read-only or partially editable later,
- they can share the same project grouping and right-table rendering.

### Merge Boundary

Add one merged-list composition step such as:

- `mergeDefectIssueRecords(...)`

Current slice:

- input: local draft issue records only,
- output: same records.

Future slice:

- input: local draft issue records + Jira issue records,
- output: one unified render list.

This merge function should not solve deduplication yet.
The first purpose is only to formalize the boundary.

## Right Table Behavior

The right-side table should bind to the merged issue list rather than directly to filtered drafts.

Expected behavior:

- `来源` column is always visible,
- `Jira Key` column is always visible,
- rows still show `项目名称`,
- local draft rows remain inline-editable through their original draft payload,
- future Jira rows can be rendered in the same table without changing structure.

This slice should not yet add:

- Jira-only filters,
- Jira fetch buttons,
- read-only locking UI for Jira rows.

## Filtering And Selection

Filtering remains driven by existing defect filters:

- project,
- status,
- issue type,
- priority,
- assignee,
- keyword.

Current slice rule:

- filters still evaluate the local draft source,
- the merged issue list is produced from the filtered local drafts.

This is acceptable because Jira source data is not yet present.

Selection rule:

- table selection should still resolve back to local drafts for export/delete actions.

## Performance Direction

The page should continue to narrow visible data before rendering:

1. filter draft source,
2. build/merge issue list,
3. build left project groups,
4. render right table.

When Jira issues are introduced later, they can follow the same pipeline without forcing a different page structure.

## Non-Goals

This design does not include:

- actual Jira API requests,
- Jira credentials or auth,
- Jira create/update flows,
- local/backend persistence changes for defects,
- deduplication between local drafts and Jira issues,
- a new defect backend module,
- removing the right-side defect toolbar actions.

## Testing

### Domain Tests

Add tests for:

- draft-to-issue-record normalization with source labels,
- merged issue list composition for the current local-only case,
- project grouping still working from the merged issue list.

### UI Verification

Verify in the browser:

- left pane no longer shows duplicate action buttons,
- left pane no longer shows gray summary cards,
- project grouping moves upward and fills the space,
- right table shows `来源` and `Jira Key`,
- local draft rows remain editable.

## Notes

- The main architectural goal is not “connect Jira now”; it is “make Jira a data-source addition later.”
- Removing duplicate left-pane actions keeps the defect module quieter and reduces layout noise.
