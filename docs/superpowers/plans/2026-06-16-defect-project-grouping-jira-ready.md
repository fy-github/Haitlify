> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Defect Project Grouping And Jira-Ready View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the defect page left pane into a project-grouping navigator, align the left-side export/delete actions, and prepare the right-side issue list structure for future merged local-draft and Jira issue display.

**Architecture:** Add a small defect issue view-model helper in `defectView.ts`, then bind the defect page to project groups and issue records instead of directly to raw drafts. Keep the current local draft data as the only source for now, but structure the helper so Jira API records can later normalize into the same shape.

**Tech Stack:** Vue 3, TypeScript, Vite, Vitest, Element Plus.

---

### Task 1: Defect Issue View Model And Project Grouping

**Files:**
- Modify: `apps/web/src/domain/defectView.ts`
- Modify: `apps/web/src/domain/defectView.test.ts`

- [ ] **Step 1: Write the failing tests**

Extend `apps/web/src/domain/defectView.test.ts` with tests for issue normalization and project grouping:

```ts
it('builds defect issue records and project groups from local drafts', () => {
  const drafts = [
    makeDraft('1', { projectName: 'haitlify' }),
    makeDraft('2', { projectName: 'haitlify', jiraIssueKey: 'IKOS-22', status: 'created_in_jira' }),
    makeDraft('3', { projectName: 'Ucenter' })
  ]

  const issues = buildDefectIssueRecords(drafts)
  const groups = buildDefectProjectGroups(issues)

  expect(issues.map((issue) => issue.sourceKind)).toEqual(['draft', 'draft', 'draft'])
  expect(groups).toEqual([
    { value: 'haitlify', count: 2 },
    { value: 'Ucenter', count: 1 }
  ])
})

it('filters defect issue records by selected project while preserving ALL behavior', () => {
  const issues = buildDefectIssueRecords([
    makeDraft('1', { projectName: 'haitlify' }),
    makeDraft('2', { projectName: 'Ucenter' })
  ])

  expect(filterDefectIssuesByProject(issues, 'haitlify').map((issue) => issue.id)).toEqual(['1'])
  expect(filterDefectIssuesByProject(issues, 'ALL').map((issue) => issue.id)).toEqual(['1', '2'])
})
```

- [ ] **Step 2: Run the defect view tests and verify RED**

Run:

```bash
npm test -- src/domain/defectView.test.ts --run
```

Expected:

- FAIL because `buildDefectIssueRecords`, `buildDefectProjectGroups`, or `filterDefectIssuesByProject` do not exist yet.

- [ ] **Step 3: Write the minimal defect view helper implementation**

Add to `apps/web/src/domain/defectView.ts`:

```ts
export interface DefectIssueRecord {
  id: string
  projectName: string
  sourceKind: 'draft'
  status: DefectDraftStatus
  summary: string
  priority: string
  assignee: string
  issueType: string
  issueKey: string
  draft: DefectDraft
}

export const buildDefectIssueRecords = (drafts: DefectDraft[]): DefectIssueRecord[] =>
  drafts.map((draft) => ({
    id: draft.defectId,
    projectName: draft.projectName,
    sourceKind: 'draft',
    status: draft.status,
    summary: draft.summary,
    priority: draft.priority,
    assignee: draft.assignee,
    issueType: draft.issueType,
    issueKey: draft.jiraIssueKey || '',
    draft
  }))

export const buildDefectProjectGroups = (issues: DefectIssueRecord[]): DefectFilterOption[] => {
  const counts = new Map<string, number>()
  for (const issue of issues) {
    counts.set(issue.projectName, (counts.get(issue.projectName) || 0) + 1)
  }
  return Array.from(counts.entries()).map(([value, count]) => ({ value, count }))
}

export const filterDefectIssuesByProject = (
  issues: DefectIssueRecord[],
  project: string
): DefectIssueRecord[] => (project === 'ALL' ? issues : issues.filter((issue) => issue.projectName === project))
```

Keep source kind hard-coded to `draft` in this slice.
Do not add Jira transport or persistence logic here.

- [ ] **Step 4: Run the defect view tests and verify GREEN**

Run:

```bash
npm test -- src/domain/defectView.test.ts --run
```

Expected:

- PASS
- the new issue-record and grouping tests are green.

- [ ] **Step 5: Commit the defect view model helper**

```bash
git add apps/web/src/domain/defectView.ts apps/web/src/domain/defectView.test.ts
git commit -m "feat: add defect project grouping model"
```

### Task 2: Defect Page Left Pane And Project Navigation

**Files:**
- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/styles.css`

- [ ] **Step 1: Add computed issue records and project groups**

Update `App.vue` imports:

```ts
import {
  buildDefectFilterOptions,
  buildDefectIssueRecords,
  buildDefectMetrics,
  buildDefectProjectGroups,
  filterDefectDrafts,
  filterDefectIssuesByProject
} from './domain/defectView'
```

Add computed state:

```ts
const defectIssueRecords = computed(() => buildDefectIssueRecords(defectDrafts.value))
const defectProjectGroups = computed(() => buildDefectProjectGroups(defectIssueRecords.value))
const defectIssuesForActiveProject = computed(() =>
  filterDefectIssuesByProject(defectIssueRecords.value, defectFilter.project)
)
```

Do not replace `filteredDefectDrafts` yet.
Use the issue records first for left-pane grouping and then keep the table fed by current filtered drafts for minimal risk.

- [ ] **Step 2: Replace the left-pane placeholder with project grouping**

In the left `activeNav === 'defects'` resource pane:

- keep the two action buttons,
- wrap them in a stacked action group,
- replace the placeholder block with a grouped project list.

The new markup should follow the existing resource-list style:

```vue
<div class="resource-list resource-list-bordered resource-list-fill">
  <div class="resource-section-title">项目</div>
  <button
    class="resource-item"
    :class="{ active: defectFilter.project === 'ALL' }"
    @click="defectFilter.project = 'ALL'"
  >
    <span class="resource-item-label">全部项目</span>
    <b>{{ defectIssueRecords.length }}</b>
  </button>
  <button
    v-for="project in defectProjectGroups"
    :key="project.value"
    class="resource-item"
    :class="{ active: defectFilter.project === project.value }"
    @click="defectFilter.project = project.value"
  >
    <span class="resource-item-label">{{ project.value }}</span>
    <b>{{ project.count }}</b>
  </button>
</div>
```

- [ ] **Step 3: Align the left-pane action buttons**

In `styles.css`, add a defect-specific left-pane button group such as:

```css
.defect-pane-actions {
  display: grid;
  gap: 10px;
}

.defect-pane-actions .full-button {
  width: 100%;
}
```

Use this class in the left defect pane so:

- the two buttons align,
- both fill the same width,
- spacing is visually obvious.

- [ ] **Step 4: Keep right-side project filtering in sync with left navigation**

Do not remove the right-side top project filter.
Instead, let both controls share `defectFilter.project`.

Expected result:

- left click updates the right table,
- right-side select still works,
- both stay visually synchronized because they bind the same state.

- [ ] **Step 5: Run focused UI regression checks**

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

- [ ] **Step 6: Commit the defect page navigation changes**

```bash
git add apps/web/src/App.vue apps/web/src/styles.css
git commit -m "feat: group defects by project"
```

### Task 3: Jira-Ready Notes And Browser Verification

**Files:**
- Modify: `docs/ai-test-platform/decisions.md`

- [ ] **Step 1: Record the new defect page rule**

Append a short decision describing:

```md
- The defect page now uses project grouping as the primary left-pane navigation surface.
- Left-pane project groups currently aggregate local defect drafts only.
- The right-side issue list is being shaped toward a future merged view of:
  - local defect drafts,
  - Jira API issues.
- Current slice keeps local drafts as the only data source while reserving a source-kind distinction for later Jira integration.
```

- [ ] **Step 2: Verify the defect page in the browser**

Manual smoke check:

- open `/defects`,
- confirm the two left-pane action buttons are aligned and spaced,
- confirm project groups render in the left pane,
- click one project group and verify the right table filters,
- click `全部项目` and verify the full list returns,
- confirm the `项目名称` column remains visible on the right.

Expected:

- no runtime errors,
- left project grouping behaves like a project filter,
- page layout feels stable and scan-friendly.

- [ ] **Step 3: Commit docs and browser verification state**

```bash
git add docs/ai-test-platform/decisions.md
git commit -m "docs: record defect project grouping"
```

- [ ] **Step 4: Final branch status check**

Run:

```bash
git status --short
git log --oneline -3
```

Expected:

- clean worktree,
- three focused commits for helper, page wiring, and decision log.
