> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Defect Jira CSV Hardening Plan

> Scope: documentation-only planning artifact for the next implementation checkpoint.

## Goal

Stabilize the frontend-local defect workflow so failed API automation results can be reviewed, managed, exported as Jira CSV, and clearly traced from automation/report screens without starting backend persistence or direct Jira API integration.

## Current Baseline

- Failed or errored automation results can be transferred into the `缺陷` module.
- Defect drafts are stored in browser local storage.
- Defect drafts support inline editing for the first Jira CSV fields.
- Jira CSV export uses the first agreed eight columns:
  - `概要`
  - `问题类型`
  - `项目名称`
  - `优先级`
  - `经办人`
  - `描述`
  - `向外链接的问题（BLOCK）`
  - `自定义字段（ 故障分类）`
- Exporting marks drafts as `已导出 Jira CSV`.
- The data model already reserves `jiraFieldValues`, `jiraExportMode`, and `jiraIssueKey` for later expansion.

## Recommended Approach

Use an incremental hardening approach:

1. Keep the current local-storage defect repository.
2. Add small domain helpers for filtering, bulk operations, and Jira CSV field templates.
3. Wire the helpers into the existing Vue page with compact filters and batch actions.
4. Verify with unit tests, build checks, and real browser actions.

This keeps the next node small enough to finish and validate, while preserving the later upgrade path to backend persistence and Jira API adapters.

## Tasks

### Task 1: Defect View Filtering

Files:

- Create: `apps/web/src/domain/defectView.ts`
- Create: `apps/web/src/domain/defectView.test.ts`
- Modify: `apps/web/src/App.vue`

Steps:

- Add filters for project, Jira status, issue type, priority, assignee, and keyword.
- Build filtered defect lists in a domain helper instead of inline template filtering.
- Keep metric counts clear and cheap to compute.
- Ensure the table renders filtered results, not all drafts.

Acceptance:

- User can narrow defects by project/status/type/priority/assignee/keyword.
- Keyword search covers summary, description, source case id, Jira issue key, and fault category.
- Filtering does not remove selected rows silently without clearing stale selection.

### Task 2: Batch Export And Batch Delete

Files:

- Modify: `apps/web/src/domain/defectRepository.ts`
- Modify: `apps/web/src/domain/defectRepository.test.ts`
- Modify: `apps/web/src/App.vue`

Steps:

- Add repository helpers for deleting multiple defect drafts by id.
- Add export scope selection:
  - selected rows first,
  - otherwise current filtered scope.
- Add delete scope selection:
  - selected rows,
  - current filtered scope.
- Use the existing confirm dialog style for destructive and bulk export actions.

Acceptance:

- Selected defect drafts can be exported together.
- Current filtered defect drafts can be exported together when nothing is selected.
- Selected defect drafts can be deleted.
- Current filtered defect drafts can be deleted after confirmation.
- Export marks only exported drafts as `已导出 Jira CSV`.

### Task 3: Jira CSV Field Template Foundation

Files:

- Create: `apps/web/src/domain/jiraCsvTemplate.ts`
- Create: `apps/web/src/domain/jiraCsvTemplate.test.ts`
- Modify: `apps/web/src/domain/defectRepository.ts`
- Modify: `apps/web/src/domain/defectRepository.test.ts`

Steps:

- Define a default Jira CSV field template using the current eight columns.
- Map default fields to current `DefectDraft` properties.
- Allow additional configured fields to read from `jiraFieldValues`.
- Keep CSV escaping and UTF-8 BOM behavior unchanged.
- Do not expose a full field-template UI in this checkpoint unless it is needed for the export foundation.

Acceptance:

- Current exported CSV remains compatible with the agreed sample columns by default.
- Extra Jira fields can be exported from `jiraFieldValues` through the template model.
- Tests prove default and extended templates both export the expected headers and values.

### Task 4: Traceability Markers

Files:

- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/styles.css`

Steps:

- Make defect status badges easier to see in automation result rows and report result rows.
- Keep the row action available only for failed or errored results.
- Show transferred/exported/created status without requiring users to open the defect page.
- Avoid duplicate draft creation for the same source result by updating the existing draft.

Acceptance:

- Automation table shows whether a failed/error case is `待导出 Jira`, `已导出 Jira CSV`, or `已建 Jira`.
- Report result table shows the same status marker.
- Re-clicking `转缺陷` updates the same draft instead of adding a duplicate.

### Task 5: Browser Verification

Files:

- No source-only target; verify the running web app.

Steps:

- Start or reuse the local dev server.
- In the browser, import or reuse API data and automation cases.
- Run automation with at least one failing/error result.
- Transfer a failed/error result to defects.
- Edit fields in the defect table.
- Export Jira CSV.
- Confirm the exported status changes in the defect module and source result rows.
- Check that the temporary browser/debug process cleanup rule is followed after testing.

Acceptance:

- The path works through actual web clicks.
- No console errors appear during the defect workflow.
- Exported CSV filename includes the Jira/project context.
- The browser/debug cleanup check is complete.

## Verification Commands

Run from `apps/web`:

```bash
npm run test -- --run
npm run build
```

## Explicitly Deferred

- Direct Jira API issue creation.
- Jira credentials and auth management.
- Backend persistence for defect drafts.
- Full Jira field configuration UI.
- Jira webhook or status sync.
- Apifox strict automation import compatibility.

## Follow-Up After This Checkpoint

After this checkpoint is verified, the next planning branch should choose between:

1. Backend persistence foundation for projects, cases, automation cases, reports, and defects.
2. Strict Apifox automation export/import compatibility research and adapter work.
3. Performance/code-splitting work for the large frontend bundle and oversized `App.vue`.
