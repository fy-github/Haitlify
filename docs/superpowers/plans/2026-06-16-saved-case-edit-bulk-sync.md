> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Saved Case Edit And Bulk Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add backend best-effort sync for saved-case edit drawer saves, `批量修改字段`, and `字段赋值` so saved-case maintenance matches the rest of the persistence-backed case-library workflow.

**Architecture:** Reuse the existing local-first repository adapter. Add one reusable helper in `platformBackendRepository.ts` that syncs the current local snapshot for one or more affected projects through the existing saved-case save endpoint, then wire `App.vue` saved-case edit and bulk-maintenance handlers to call it after local mutation.

**Tech Stack:** Vue 3, TypeScript, Vite, Vitest, Element Plus.

---

### Task 1: Project-Scoped Saved-Case Resync Helper

**Files:**
- Modify: `apps/web/src/domain/platformBackendRepository.ts`
- Test: `apps/web/src/domain/platformBackendRepository.test.ts`

- [ ] **Step 1: Write the failing tests**

Add two new tests near the existing delete/order sync coverage:

```ts
it('syncs the current local snapshot for one edited saved-case project', async () => {
  const storage = new MemoryStorage()
  saveGeneratedCases(storage, [
    makeGeneratedCase({
      id: 'API-0001',
      sourceOperationId: 'getProjectTree',
      path: '/v4/pm/project-tree',
      title: '本地编辑后的标题',
      runOrder: 1
    })
  ], {
    savedAt: '2026-06-16T10:00:00.000Z'
  })

  let saveBody: { cases: SavedInterfaceCase[] } | undefined
  const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
    if (input.endsWith('/api/projects')) {
      return jsonResponse({ items: [{ id: 7, code: 'HAITLIFY_API', name: 'Haitlify API' }], total: 1 })
    }
    if (input.endsWith('/api/projects/7/interface-cases')) {
      saveBody = JSON.parse(String(init?.body))
      return jsonResponse({ inserted: 0, updated: 1, total: 1 })
    }
    throw new Error(`Unexpected request ${input}`)
  })

  const result = await syncSavedCaseProjectsWithBackend(storage, ['Haitlify API'], { fetchImpl })

  expect(result.source).toBe('backend')
  expect(saveBody?.cases).toHaveLength(1)
  expect(saveBody?.cases[0].row.title).toBe('本地编辑后的标题')
})

it('partitions bulk saved-case sync by affected project and falls back to local on backend failure', async () => {
  const storage = new MemoryStorage()
  saveGeneratedCases(storage, [
    makeGeneratedCase({
      id: 'API-0001',
      sourceOperationId: 'getProjectTree',
      path: '/v4/pm/project-tree',
      title: 'haitlify 批量修改后',
      runOrder: 1
    }),
    makeGeneratedCase({
      id: 'API-0002',
      project: 'Ucenter API',
      sourceFile: 'UcenterAPI.openapi.json',
      sourceOperationId: 'listUsers',
      path: '/ucenter/users',
      title: 'Ucenter 批量修改后',
      runOrder: 1
    })
  ], {
    savedAt: '2026-06-16T10:00:00.000Z'
  })

  const fetchImpl = vi.fn((input: string) => {
    if (input.endsWith('/api/projects')) {
      return jsonResponse({
        items: [
          { id: 7, code: 'HAITLIFY_API', name: 'Haitlify API' },
          { id: 8, code: 'UCENTER_API', name: 'Ucenter API' }
        ],
        total: 2
      })
    }
    return Promise.reject(new Error(`offline ${input}`))
  })

  const result = await syncSavedCaseProjectsWithBackend(storage, ['Haitlify API', 'Ucenter API'], { fetchImpl })

  expect(result.source).toBe('local')
  expect(listSavedCases(storage)).toHaveLength(2)
})
```

- [ ] **Step 2: Run the repository tests and verify RED**

Run:

```bash
npm test -- src/domain/platformBackendRepository.test.ts --run
```

Expected:

- FAIL because `syncSavedCaseProjectsWithBackend` does not exist yet.

- [ ] **Step 3: Write the minimal repository implementation**

Add a helper like this to `apps/web/src/domain/platformBackendRepository.ts`:

```ts
export const syncSavedCaseProjectsWithBackend = async (
  storage: Storage,
  projectNames: string[],
  options: PlatformBackendOptions = {}
): Promise<BackendSyncResult<{ projects: number }>> => {
  const uniqueProjects = Array.from(new Set(projectNames.filter(Boolean)))
  if (uniqueProjects.length === 0) return { source: 'local' }

  try {
    for (const projectName of uniqueProjects) {
      const project = await ensureBackendProject(projectName, options)
      const projectCases = listSavedCases(storage).filter((testCase) => projectNameForSavedCase(testCase) === projectName)
      await requestJson<SaveCasesResult>(
        options,
        `/api/projects/${project.id}/interface-cases`,
        {
          method: 'POST',
          headers: jsonHeaders(),
          body: JSON.stringify({ cases: projectCases })
        }
      )
    }
    return { source: 'backend', value: { projects: uniqueProjects.length } }
  } catch {
    return { source: 'local' }
  }
}
```

Keep it local-first:

- no rollback,
- no new backend endpoint,
- no local mutation inside this helper.

- [ ] **Step 4: Run the repository tests and verify GREEN**

Run:

```bash
npm test -- src/domain/platformBackendRepository.test.ts --run
```

Expected:

- PASS
- the new tests and the existing backend sync tests all stay green.

- [ ] **Step 5: Commit the repository helper**

```bash
git add apps/web/src/domain/platformBackendRepository.ts apps/web/src/domain/platformBackendRepository.test.ts
git commit -m "feat: sync saved case edits by project"
```

### Task 2: Wire Saved-Case Edit And Bulk Maintenance

**Files:**
- Modify: `apps/web/src/App.vue`

- [ ] **Step 1: Update imports and make saved-case persistence async**

Import the new helper from `platformBackendRepository.ts` and make the saved-case maintenance paths await backend sync:

```ts
import {
  deleteSavedCasesWithBackend,
  loadSavedCasesWithBackend,
  loadTemplateConfigWithBackend,
  saveGeneratedCasesWithBackend,
  saveTemplateConfigWithBackend,
  syncCaseOrderingWithBackend,
  syncSavedCaseProjectsWithBackend
} from './domain/platformBackendRepository'
```

Change signatures:

```ts
const saveEdit = async () => {
  // ...
}

const persistUpdatedLibraryCases = async (cases: SavedInterfaceCase[]) => {
  // ...
}
```

- [ ] **Step 2: Wire saved-case edit drawer sync**

Update `saveEdit` so saved-case edits still mutate local storage first, then sync the affected project:

```ts
if (selectedLibraryId.value) {
  deleteSavedCase(localStorage, selectedLibraryId.value)
  saveGeneratedCases(localStorage, [selectedCase.value], {
    savedAt: new Date().toISOString()
  })
  refreshSavedCases()

  const savedProject = selectedCase.value.row.project || '默认项目'
  const result = await syncSavedCaseProjectsWithBackend(localStorage, [savedProject])
  ElMessage.success(result.source === 'backend' ? '已更新当前用例，并同步到后端' : '已更新当前用例')
} else {
  ElMessage.success('已更新当前用例')
}
```

Do not change generated-but-unsaved case editing behavior.

- [ ] **Step 3: Wire bulk maintenance sync**

Make `persistUpdatedLibraryCases` compute affected projects from the updated cases and sync after local replacement:

```ts
const persistUpdatedLibraryCases = async (cases: SavedInterfaceCase[]) => {
  deleteSavedCases(localStorage, cases.map((item) => item.libraryId))
  saveGeneratedCases(localStorage, cases.map((testCase) => applyInterfaceFieldTemplate(testCase, interfaceFields.value)))
  refreshSavedCases()
  librarySelection.value = []

  const projectNames = Array.from(new Set(cases.map((testCase) => testCase.row.project || '默认项目')))
  return await syncSavedCaseProjectsWithBackend(localStorage, projectNames)
}
```

Then update callers:

```ts
const bulkResult = applyBulkCaseFieldUpdate(bulkTargetCases.value, {
  ...bulkEditForm
})
const syncResult = await persistUpdatedLibraryCases(bulkResult.cases)
ElMessage.success(
  syncResult.source === 'backend'
    ? `已批量修改 ${bulkResult.updated} 条用例，并同步到后端`
    : `已批量修改 ${bulkResult.updated} 条用例`
)
```

and:

```ts
const fieldResult = applyJsonFieldAssignment(bulkTargetCases.value, {
  ...fieldAssignmentForm
})
const syncResult = await persistUpdatedLibraryCases(fieldResult.cases)
ElMessage.success(
  syncResult.source === 'backend'
    ? `已更新 ${fieldResult.updated} 条用例字段，并同步到后端`
    : `已更新 ${fieldResult.updated} 条用例字段`
)
```

Keep existing warning and zero-update guards unchanged.

- [ ] **Step 4: Run frontend verification commands**

Run:

```bash
npm test -- src/domain/platformBackendRepository.test.ts --run
npm test -- --run
npm run build
```

Expected:

- all web tests pass,
- build exits `0`,
- existing Vite chunk-size warning may remain.

- [ ] **Step 5: Commit the App wiring**

```bash
git add apps/web/src/App.vue
git commit -m "feat: sync saved case maintenance changes"
```

### Task 3: Decision Log And Browser Verification

**Files:**
- Modify: `docs/ai-test-platform/decisions.md`

- [ ] **Step 1: Record the new persistence rule**

Append a short decision under the backend sync section describing:

```md
- Saved case maintenance now also syncs to backend after local mutation:
  - single saved-case edit from the edit drawer,
  - `批量修改字段`,
  - `字段赋值`.
- These flows partition by `row.project` and reuse `POST /api/projects/{projectId}/interface-cases`.
- Backend failure keeps the local result and falls back to local-only success messaging.
```

- [ ] **Step 2: Verify the UI in the browser**

Manual smoke check targets:

- `/cases`
- edit one saved case and save it,
- open `批量修改字段`,
- open `字段赋值`,
- confirm the actions still complete and the UI remains stable.

Expected:

- no runtime errors,
- success messaging appears,
- case-library table refreshes after mutation.

- [ ] **Step 3: Commit docs and verification state**

```bash
git add docs/ai-test-platform/decisions.md
git commit -m "docs: record saved case maintenance sync"
```

- [ ] **Step 4: Final git and status check**

Run:

```bash
git status --short
git log --oneline -3
```

Expected:

- clean worktree,
- three focused commits for repository helper, App wiring, and decision log.
