> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Defect Jira Source Adapter Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the defect page render through a unified issue-list boundary that is ready for future Jira API records, while simplifying the left workspace rail into a pure project navigator.

**Architecture:** Keep local defect drafts as the only real source for now, but formalize three stages in `defectView.ts`: draft normalization, merged issue composition, and project grouping from the merged issue list. Then bind `App.vue` to the merged list and remove the redundant left-pane defect actions and summary cards.

**Tech Stack:** Vue 3, TypeScript, Vite, Vitest, Element Plus.

---

### Task 1: Formalize The Jira-Ready Issue Source Boundary

**Files:**
- Modify: `apps/web/src/domain/defectView.ts`
- Modify: `apps/web/src/domain/defectView.test.ts`

- [ ] **Step 1: Write the failing tests**

Extend `apps/web/src/domain/defectView.test.ts` with one more test that proves merged issue composition stays stable:

```ts
it('merges local draft issue records into the current unified issue list', () => {
  const drafts = [
    makeDraft('1', { projectName: 'haitlify' }),
    makeDraft('2', { projectName: 'Ucenter', jiraIssueKey: 'IKOS-22' })
  ]

  const issues = buildDefectIssueRecordsFromDrafts(drafts)
  const merged = mergeDefectIssueRecords(issues)

  expect(merged.map((issue) => ({
    id: issue.id,
    sourceKind: issue.sourceKind,
    sourceLabel: issue.sourceLabel,
    issueKey: issue.issueKey
  }))).toEqual([
    { id: '1', sourceKind: 'draft', sourceLabel: '本地草稿', issueKey: '' },
    { id: '2', sourceKind: 'draft', sourceLabel: '本地草稿', issueKey: 'IKOS-22' }
  ])
})
```

- [ ] **Step 2: Run the defect view tests and verify RED**

Run:

```bash
npm test -- src/domain/defectView.test.ts --run
```

Expected:

- FAIL because `buildDefectIssueRecordsFromDrafts` and/or `mergeDefectIssueRecords` do not exist yet.

- [ ] **Step 3: Add the adapter-boundary helpers**

Refactor `apps/web/src/domain/defectView.ts` so the current export becomes an alias around the more explicit source adapter:

```ts
export const buildDefectIssueRecordsFromDrafts = (drafts: DefectDraft[]): DefectIssueRecord[] =>
  drafts.map((draft) => ({
    id: draft.defectId,
    projectName: draft.projectName,
    sourceKind: 'draft',
    sourceLabel: '本地草稿',
    status: draft.status,
    displayStatus: displayStatusForDraft(draft),
    summary: draft.summary,
    priority: draft.priority,
    assignee: draft.assignee,
    issueType: draft.issueType,
    issueKey: draft.jiraIssueKey || '',
    draft
  }))

export const mergeDefectIssueRecords = (...groups: DefectIssueRecord[][]): DefectIssueRecord[] =>
  groups.flat()

export const buildDefectIssueRecords = (drafts: DefectDraft[]): DefectIssueRecord[] =>
  mergeDefectIssueRecords(buildDefectIssueRecordsFromDrafts(drafts))
```

Do not add Jira transport or a `jira` row implementation yet.

- [ ] **Step 4: Run the defect view tests and verify GREEN**

Run:

```bash
npm test -- src/domain/defectView.test.ts --run
```

Expected:

- PASS
- all defect view tests stay green.

- [ ] **Step 5: Commit the adapter boundary**

```bash
git add apps/web/src/domain/defectView.ts apps/web/src/domain/defectView.test.ts
git commit -m "feat: add defect issue source adapter boundary"
```

### Task 2: Simplify The Left Pane And Bind The Right Table To The Unified List

**Files:**
- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/styles.css`

- [ ] **Step 1: Remove duplicate left-pane actions and gray summaries**

In the defect left workspace pane, remove:

- `导出选中/筛选`,
- `删除选中/筛选`,
- `缺陷草稿` summary block,
- `已转 Jira` summary block.

Leave only the project grouping list in that pane.

- [ ] **Step 2: Move the project grouping list upward**

Keep the current project grouping list, but make it the first visible section inside the defect left pane.

The pane should effectively become:

```vue
<div class="pane-body pane-body-scroll">
  <div class="resource-list resource-list-bordered resource-list-fill defect-project-groups">
    ...
  </div>
</div>
```

Do not reintroduce a new placeholder block.

- [ ] **Step 3: Bind the right table to the merged issue list**

Keep:

- `filteredDefectDrafts` for local draft filtering,
- `activeDefectScope` for export/delete actions.

Render the table from:

```ts
const filteredDefectIssues = computed(() => buildDefectIssueRecords(filteredDefectDrafts.value))
```

and keep `@selection-change` mapped back to `row.draft`.

- [ ] **Step 4: Keep local-draft edit behavior intact**

Local draft rows should continue to use:

```ts
updateDefectField(row.draft, { ... })
removeDefect(row.draft)
```

Do not add conditional Jira row locking yet.
This slice only preserves the current local-draft edit behavior through the new view-model surface.

- [ ] **Step 5: Run regression verification**

Run:

```bash
npm test -- src/domain/defectView.test.ts --run
npm test -- --run
npm run build
```

Expected:

- all defect view tests pass,
- full web tests pass,
- build exits `0`,
- existing Vite chunk-size warning may remain.

- [ ] **Step 6: Commit the page simplification and unified binding**

```bash
git add apps/web/src/App.vue apps/web/src/styles.css
git commit -m "feat: simplify defect workspace rail"
```

### Task 3: Record The New Boundary And Verify The UI

**Files:**
- Modify: `docs/ai-test-platform/decisions.md`

- [ ] **Step 1: Record the new defect page boundary**

Append a short decision:

```md
- The defect page now renders through a unified issue list boundary instead of binding the right table directly to raw local drafts.
- The current unified list is still populated only from local drafts.
- The left defect workspace rail is now a pure project navigator with no duplicate export/delete actions or summary cards.
- Future Jira API issues should enter the page through a parallel source adapter and merge into the same issue list.
```

- [ ] **Step 2: Verify the browser UI**

Manual smoke check:

- open `/defects`,
- confirm the left pane no longer shows export/delete buttons,
- confirm the left pane no longer shows gray summary cards,
- confirm the project list starts higher in the pane,
- confirm the right table shows `来源` and `Jira Key`,
- confirm local draft rows still allow inline editing.

Expected:

- no runtime errors,
- left pane is visually quieter,
- right issue list still behaves correctly for local draft rows.

- [ ] **Step 3: Commit docs and verification state**

```bash
git add docs/ai-test-platform/decisions.md docs/superpowers/specs/2026-06-16-defect-jira-source-adapter-boundary-design.md docs/superpowers/plans/2026-06-16-defect-jira-source-adapter-boundary.md
git commit -m "docs: record defect issue source boundary"
```

- [ ] **Step 4: Final branch status check**

Run:

```bash
git status --short
git log --oneline -3
```

Expected:

- clean worktree,
- three focused commits for adapter boundary, page simplification, and docs.
