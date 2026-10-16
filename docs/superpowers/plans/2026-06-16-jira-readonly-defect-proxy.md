> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Jira Read-Only Defect Proxy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a backend-proxied, service-account-backed Jira read path for the defect page, reserve a later user-session mode, and improve the defect table with visible description and an edit action for local draft rows.

**Architecture:** Build a narrow Jira proxy in `server/platform`, then add a frontend Jira repository adapter and merge Jira issues into the existing unified defect issue list. Keep Jira rows read-only and keep local drafts as the editable/exportable source of truth.

**Tech Stack:** Spring Boot, Java 21, Vue 3, TypeScript, Vite, Vitest, Element Plus.

---

### Task 1: Improve The Current Defect Table UX

**Files:**
- Modify: `apps/web/src/App.vue`

- [ ] **Step 1: Add a visible description column**

In the defect table, add a `描述` column before the operation column using the current local draft description:

```vue
<el-table-column label="描述" min-width="280">
  <template #default="{ row }">
    <el-input
      type="textarea"
      :rows="3"
      :model-value="row.sourceKind === 'draft' ? row.draft.description : row.summary"
      :disabled="true"
    />
  </template>
</el-table-column>
```

For this step, read-only display is enough.

- [ ] **Step 2: Add an edit action for local draft rows**

In the operation column:

```vue
<el-button
  v-if="row.sourceKind === 'draft'"
  link
  @click="openDefectEdit(row.draft)"
>
  编辑
</el-button>
```

Keep the existing delete action for local draft rows only.
Do not show editable actions for future Jira rows.

- [ ] **Step 3: Add a defect edit dialog state**

Add a dedicated defect-edit state in `App.vue`:

```ts
const defectEditVisible = ref(false)
const editingDefect = ref<DefectDraft>()
const defectEditForm = reactive({
  summary: '',
  projectName: '',
  issueType: '',
  priority: '',
  assignee: '',
  description: '',
  faultCategory: '',
  blocksIssueKey: ''
})
```

Add:

```ts
const openDefectEdit = (draft: DefectDraft) => {
  editingDefect.value = draft
  Object.assign(defectEditForm, {
    summary: draft.summary,
    projectName: draft.projectName,
    issueType: draft.issueType,
    priority: draft.priority,
    assignee: draft.assignee,
    description: draft.description,
    faultCategory: draft.faultCategory,
    blocksIssueKey: draft.blocksIssueKey
  })
  defectEditVisible.value = true
}

const saveDefectEdit = () => {
  if (!editingDefect.value) return
  updateDefectField(editingDefect.value, { ...defectEditForm })
  defectEditVisible.value = false
  ElMessage.success('已更新缺陷草稿')
}
```

- [ ] **Step 4: Render the defect edit dialog**

Add a new `el-dialog` or `el-drawer` near the existing defect/Jira dialogs with the same field set used in the table.

Keep it compact and reuse the current `drawer-actions` style.

- [ ] **Step 5: Verify the current frontend remains green**

Run:

```bash
npm test -- --run
npm run build
```

Expected:

- PASS
- build exits `0`

- [ ] **Step 6: Commit the defect table UX changes**

```bash
git add apps/web/src/App.vue
git commit -m "feat: improve defect table editing"
```

### Task 2: Add The Backend Jira Read Proxy

**Files:**
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/jira/JiraIssueController.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/jira/JiraIssueService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/jira/JiraIssueClient.java`
- Modify: `server/platform/src/main/resources/application.yml`
- Modify: `server/platform/src/test/resources/application-test.yml`
- Test: `server/platform/src/test/java/com/haitlify/aitest/platform/jira/JiraIssueControllerTest.java`

- [ ] **Step 1: Write failing backend controller tests**

Create a controller test proving:

- `GET /api/jira/issues?projectName=Haitlify API&authMode=service-account` returns a normalized payload,
- `authMode=user-session` returns a controlled unsupported response.

- [ ] **Step 2: Run backend test and verify RED**

Run:

```bash
./mvnw -Dtest=JiraIssueControllerTest test
```

Expected:

- FAIL because the Jira issue controller/service do not exist yet.

- [ ] **Step 3: Add configuration and DTOs**

Add config fields under `application.yml`, for example:

```yaml
app:
  jira:
    enabled: ${APP_JIRA_ENABLED:false}
    base-url: ${APP_JIRA_BASE_URL:}
    default-auth-mode: ${APP_JIRA_DEFAULT_AUTH_MODE:service-account}
    service-account:
      username: ${APP_JIRA_USERNAME:}
      token: ${APP_JIRA_TOKEN:}
    project-jql:
      Haitlify API: project = HAITLIFY order by created DESC
```

Define DTOs for:

- query,
- response,
- normalized issue item,
- auth mode.

- [ ] **Step 4: Add a minimal Jira service and unsupported user-session branch**

Implement:

- `service-account` mode only,
- `user-session` returns unsupported for now,
- client method signature already accepts auth mode.

The service can return empty data when Jira is disabled.

- [ ] **Step 5: Run backend test and verify GREEN**

Run:

```bash
./mvnw -Dtest=JiraIssueControllerTest test
```

Expected:

- PASS

- [ ] **Step 6: Commit the backend Jira read proxy**

```bash
git add server/platform/src/main/java/com/haitlify/aitest/platform/jira server/platform/src/main/resources/application.yml server/platform/src/test/resources/application-test.yml server/platform/src/test/java/com/haitlify/aitest/platform/jira/JiraIssueControllerTest.java
git commit -m "feat: add jira read-only proxy"
```

### Task 3: Add The Frontend Jira Query Adapter

**Files:**
- Create: `apps/web/src/domain/jiraIssueRepository.ts`
- Create: `apps/web/src/domain/jiraIssueRepository.test.ts`
- Modify: `apps/web/src/domain/defectView.ts`
- Modify: `apps/web/src/App.vue`

- [ ] **Step 1: Write failing frontend adapter tests**

Cover:

- backend Jira issue payload normalization,
- `buildDefectIssueRecordsFromJiraIssues(...)`,
- merge order of local drafts + Jira issues,
- read-only Jira rows.

- [ ] **Step 2: Run frontend target tests and verify RED**

Run:

```bash
npm test -- src/domain/defectView.test.ts src/domain/jiraIssueRepository.test.ts --run
```

Expected:

- FAIL because the Jira repository and real Jira issue normalization do not exist yet.

- [ ] **Step 3: Implement the frontend Jira adapter**

Add:

- a repository that fetches `/api/jira/issues`,
- a `JiraIssueSource[]` mapper,
- a computed fetch path that only runs for the selected project and only when not `ALL`.

- [ ] **Step 4: Merge Jira issues into the defect issue list**

Update the merged issue-list flow so:

```ts
mergeDefectIssueRecords(
  buildDefectIssueRecordsFromDrafts(filteredDefectDrafts.value),
  buildDefectIssueRecordsFromJiraIssues(jiraIssues.value)
)
```

Jira rows should:

- render in the same table,
- show `来源 = Jira`,
- remain read-only,
- not be included in export/delete selection behavior.

- [ ] **Step 5: Run frontend verification**

Run:

```bash
npm test -- --run
npm run build
```

Expected:

- PASS

- [ ] **Step 6: Commit the frontend Jira adapter**

```bash
git add apps/web/src/domain/jiraIssueRepository.ts apps/web/src/domain/jiraIssueRepository.test.ts apps/web/src/domain/defectView.ts apps/web/src/App.vue
git commit -m "feat: load jira issues in defect view"
```

### Task 4: Record The New Jira Read Boundary And Verify The Browser

**Files:**
- Modify: `docs/ai-test-platform/decisions.md`

- [ ] **Step 1: Record the Jira read-proxy rule**

Append a short decision:

```md
- The defect module now uses a backend Jira read proxy rather than frontend direct Jira requests.
- Service-account mode is the only implemented Jira auth path in the current slice.
- User-session mode is reserved in the API contract but not yet implemented.
- The defect page merges Jira issue rows into the unified issue list as read-only records.
```

- [ ] **Step 2: Verify the browser UI**

Manual smoke check:

- open `/defects`,
- confirm the right table shows `描述`,
- confirm local draft rows have `编辑`,
- confirm Jira rows (or mocked Jira rows) render as read-only,
- confirm `来源` and `Jira Key` still display correctly.

Current implementation note:

- the defect page now merges candidate project names from imported projects, case library, automation, and reports into the defect project filter so Jira-only projects can still appear even when there is no local defect draft yet.
- `createDefectsFromResults(...)` now prefers the real source project name before the fallback `jiraProjectName` input, so report-derived defects keep their original project instead of being overwritten by `IKOS`.

### Defect Module Pause Handoff

Status as of 2026-06-16:

- The defect module should pause here. Do not continue broad defect-module implementation until the next planning pass confirms scope.
- `main` contains the Jira read-only proxy slice and the unified defect issue-list slice through `feat: load readonly jira issues in defect view`.
- The defect page left rail is intentionally simplified to project selection/navigation. The previous left-side export/delete buttons and the gray summary blocks (`缺陷草稿`, `已转 Jira`) have been removed from this area.
- The defect project filter should behave like the case-library left project selector: `全部项目` is a selectable dropdown-style option, and specific projects are selectable categories.
- Jira issues are currently read-only in the UI. Local defect drafts remain editable/deletable/exportable.
- Jira integration uses backend proxy first. `service-account` is the implemented fallback path; `user-session` passthrough is reserved in the API contract for future development.

Verified implementation points already covered:

- The Jira proxy exposes `GET /api/jira/issues`.
- `authMode=service-account` returns normalized Jira issue data.
- `authMode=user-session` returns a controlled unsupported response for now.
- Frontend Jira records normalize into the same defect issue view model as local drafts.
- The defect issue table includes `描述`, `来源`, `Jira Key`, and an edit action for draft rows.
- Project options merge issue-backed projects and candidate projects from imported OpenAPI data, case library data, automation data, and report data.
- Defects created from UcenterAPI reports now preserve the source project before falling back to the selected Jira project name, avoiding accidental `IKOS` assignment.

Known remaining work for the next defect-module pass:

- Re-run a real browser smoke test in the same browser/localStorage context the user uses. The prior in-app browser smoke did not share the user's seeded localStorage, so visual confirmation of seeded drafts was limited by tooling context.
- Confirm that the left project dropdown displays real project names after the user's latest test data is present, especially projects that only appear through report/import/case-library sources and not local defect drafts.
- Confirm that selecting a project on the left only queries and renders Jira issues for that project on the right, preserving performance when Jira grows large.
- Add a safe seeded fixture or debug-only import path for defect drafts so future browser smoke tests do not depend on manual localStorage setup.
- Implement real Jira service-account querying against the configured Jira endpoint only after credentials and safe environment boundaries are confirmed. Do not use captured production cookies or run mutating operations against the production Jira host.
- Keep the future `user-session` auth mode interface reserved, but implement it in a separate plan once user identity/cookie/token forwarding rules are agreed.
- Decide whether Jira description should map from Jira rendered fields, plain text fields, or a backend-normalized fallback before enabling production Jira descriptions.
- Add pagination or lazy loading on the Jira proxy response before enabling large project-wide Jira queries.
- Add integration tests for project-scoped Jira query parameters and UI read-only behavior once the production query contract is finalized.

- [ ] **Step 3: Commit docs and verification state**

```bash
git add docs/ai-test-platform/decisions.md docs/superpowers/specs/2026-06-16-jira-readonly-defect-proxy-design.md docs/superpowers/plans/2026-06-16-jira-readonly-defect-proxy.md
git commit -m "docs: record jira readonly defect proxy"
```
