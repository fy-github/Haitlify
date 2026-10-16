> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Case Library Delete And Order Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the current local-first backend sync so saved interface case deletion and project-scoped ordering are synced to `server/platform` when the backend is available.

**Architecture:** Keep local storage as the immediate write target, then best-effort sync the backend after local mutation. Add one backend batch-delete endpoint, reuse the existing backend save endpoint for order sync, and keep frontend grouping by `row.project` inside the repository adapter.

**Tech Stack:** Vue 3, TypeScript, Vitest, Spring Boot, MockMvc, Spring Data JDBC.

---

## File Structure

- Modify `server/platform/src/test/java/com/haitlify/aitest/platform/caselibrary/CaseLibraryControllerTest.java` to pin delete and ordering behavior.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/caselibrary/CaseLibraryController.java` to expose a batch delete action.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/caselibrary/CaseLibraryService.java` to delete by `libraryId` under one `projectId`.
- Modify `apps/web/src/domain/platformBackendRepository.test.ts` to pin frontend local-first delete/order sync and fallback behavior.
- Modify `apps/web/src/domain/platformBackendRepository.ts` to add grouped delete and project-scoped order sync helpers.
- Modify `apps/web/src/App.vue` to call the new helpers from delete and move actions.
- Modify `docs/ai-test-platform/decisions.md` to record the completed sync-scope decision.

---

### Task 1: Backend Batch Delete Contract

**Files:**
- Modify: `server/platform/src/test/java/com/haitlify/aitest/platform/caselibrary/CaseLibraryControllerTest.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/caselibrary/CaseLibraryController.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/caselibrary/CaseLibraryService.java`

- [ ] **Step 1: Write failing backend delete test**

Add a MockMvc test that saves two cases, deletes one existing `libraryId` plus one missing `libraryId`, then verifies only one record remains:

```java
@Test
void deletesInterfaceCasesByLibraryIdAndIgnoresMissingIds() throws Exception {
    long projectId = createProject("CASEDEL");

    mockMvc.perform(post("/api/projects/{projectId}/interface-cases", projectId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(casesPayload("API-0001", "Haitlify.openapi.json:getProjectTree:/v4/pm/project-tree:正向", 2)
                .replace("]", """
                    ,
                    {
                      "id": "API-0002",
                      "libraryId": "Haitlify.openapi.json:createProject:/v4/pm/projects:正向",
                      "savedAt": "2026-06-15T09:31:00Z",
                      "row": {
                        "caseId": "API-0002",
                        "project": "Haitlify API",
                        "module": "项目管理",
                        "title": "创建项目-正常请求成功",
                        "caseType": "正向"
                      },
                      "metadata": {
                        "sourceFile": "Haitlify.openapi.json",
                        "sourceOperationId": "createProject",
                        "runOrder": 1
                      }
                    }
                  ]""")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2));

    mockMvc.perform(post("/api/projects/{projectId}/interface-cases/delete", projectId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "libraryIds": [
                    "Haitlify.openapi.json:getProjectTree:/v4/pm/project-tree:正向",
                    "missing-library-id"
                  ]
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted").value(1))
        .andExpect(jsonPath("$.total").value(1));

    mockMvc.perform(get("/api/projects/{projectId}/interface-cases", projectId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].libraryId").value("Haitlify.openapi.json:createProject:/v4/pm/projects:正向"))
        .andExpect(jsonPath("$.total").value(1));
}
```

- [ ] **Step 2: Run backend target test and verify RED**

Run:

```bash
cd /path/to/Haitlify/server/platform
./mvnw -Dtest=CaseLibraryControllerTest test
```

Expected: FAIL with `404` or no handler for `/interface-cases/delete`.

- [ ] **Step 3: Implement backend delete endpoint**

Add `@PostMapping("/delete")` in `CaseLibraryController`:

```java
@PostMapping("/delete")
DeleteInterfaceCasesResponse delete(
    @PathVariable Long projectId,
    @RequestBody DeleteInterfaceCasesRequest request
) {
    return caseLibraryService.delete(projectId, request);
}
```

Add service behavior and request/response records in `CaseLibraryService`:

```java
DeleteInterfaceCasesResponse delete(Long projectId, DeleteInterfaceCasesRequest request) {
    List<String> deletingIds = request.libraryIds() == null ? List.of() : request.libraryIds();
    if (deletingIds.isEmpty()) {
        int total = repository.findByProjectIdOrderByRunOrderAscSavedAtDesc(projectId).size();
        return new DeleteInterfaceCasesResponse(0, total);
    }

    List<InterfaceCaseRecord> current = repository.findByProjectIdOrderByRunOrderAscSavedAtDesc(projectId);
    List<InterfaceCaseRecord> deleting = current.stream()
        .filter(item -> deletingIds.contains(item.getLibraryId()))
        .toList();
    repository.deleteAll(deleting);

    int total = repository.findByProjectIdOrderByRunOrderAscSavedAtDesc(projectId).size();
    return new DeleteInterfaceCasesResponse(deleting.size(), total);
}

record DeleteInterfaceCasesRequest(
    List<String> libraryIds
) {
}

record DeleteInterfaceCasesResponse(
    int deleted,
    int total
) {
}
```

- [ ] **Step 4: Run backend target test and verify GREEN**

Run:

```bash
cd /path/to/Haitlify/server/platform
./mvnw -Dtest=CaseLibraryControllerTest test
```

Expected: PASS.

---

### Task 2: Frontend Repository Delete And Order Sync

**Files:**
- Modify: `apps/web/src/domain/platformBackendRepository.test.ts`
- Modify: `apps/web/src/domain/platformBackendRepository.ts`

- [ ] **Step 1: Write failing frontend sync tests**

Add tests for:

- `deleteSavedCasesWithBackend` removes local cases first, groups backend calls by `row.project`, and falls back locally when fetch fails.
- `syncCaseOrderingWithBackend` sends only the moved case's project cases to the existing save endpoint.

Use the existing `MemoryStorage`, `jsonResponse`, and `makeCase()` helpers.

Expected assertions:

```ts
expect(result.source).toBe('backend')
expect(result.deleted).toBe(2)
expect(listSavedCases(storage)).toHaveLength(0)
expect(deleteBody.libraryIds).toEqual([
  'Haitlify.openapi.json:getProjectTree:/v4/pm/project-tree:正向'
])
```

and:

```ts
expect(result.source).toBe('backend')
expect(saveBody.cases.map((item: { libraryId: string }) => item.libraryId)).toEqual([
  movedCase.libraryId,
  otherCloudCase.libraryId
])
```

- [ ] **Step 2: Run frontend target test and verify RED**

Run:

```bash
cd /path/to/Haitlify/apps/web
npm test -- src/domain/platformBackendRepository.test.ts --run
```

Expected: FAIL because the new exported helpers do not exist.

- [ ] **Step 3: Implement frontend sync helpers**

Add exports to `platformBackendRepository.ts`:

```ts
export interface DeleteCasesSyncResult {
  source: 'backend' | 'local'
  deleted: number
}

export const deleteSavedCasesWithBackend = async (
  storage: Storage,
  cases: SavedInterfaceCase[],
  options: PlatformBackendOptions = {}
): Promise<DeleteCasesSyncResult> => {
  const libraryIds = cases.map((testCase) => testCase.libraryId)
  const deleted = deleteSavedCases(storage, libraryIds)

  try {
    for (const [projectName, projectCases] of groupSavedCasesByProject(cases)) {
      const project = await ensureBackendProject(projectName, options)
      await requestJson<{ deleted: number; total: number }>(
        options,
        `/api/projects/${project.id}/interface-cases/delete`,
        {
          method: 'POST',
          headers: jsonHeaders(),
          body: JSON.stringify({ libraryIds: projectCases.map((testCase) => testCase.libraryId) })
        }
      )
    }
    return { source: 'backend', deleted }
  } catch {
    return { source: 'local', deleted }
  }
}

export const syncCaseOrderingWithBackend = async (
  storage: Storage,
  projectName: string,
  options: PlatformBackendOptions = {}
): Promise<BackendSyncResult<SaveCasesResult>> => {
  const projectCases = listSavedCases(storage).filter((testCase) => projectNameForSavedCase(testCase) === projectName)

  try {
    const project = await ensureBackendProject(projectName, options)
    const result = await requestJson<SaveCasesResult>(
      options,
      `/api/projects/${project.id}/interface-cases`,
      {
        method: 'POST',
        headers: jsonHeaders(),
        body: JSON.stringify({ cases: projectCases })
      }
    )
    return { source: 'backend', value: result }
  } catch {
    return { source: 'local' }
  }
}
```

Also add:

```ts
const projectNameForSavedCase = (testCase: SavedInterfaceCase): string =>
  testCase.row.project || '默认项目'

const groupSavedCasesByProject = (cases: SavedInterfaceCase[]): Map<string, SavedInterfaceCase[]> => {
  const groups = new Map<string, SavedInterfaceCase[]>()
  for (const testCase of cases) {
    const projectName = projectNameForSavedCase(testCase)
    groups.set(projectName, [...(groups.get(projectName) || []), testCase])
  }
  return groups
}
```

- [ ] **Step 4: Run frontend target test and verify GREEN**

Run:

```bash
cd /path/to/Haitlify/apps/web
npm test -- src/domain/platformBackendRepository.test.ts --run
```

Expected: PASS.

---

### Task 3: Wire UI Delete And Ordering Calls

**Files:**
- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/domain/platformBackendRepository.test.ts`

- [ ] **Step 1: Add focused tests for cross-project order guard helper**

If the guard is implemented as a small exported helper, test that moving from one project row to another returns `false` and same-project move returns `true`.

If the guard stays inside `App.vue`, verify it manually in the UI smoke check and keep the repository tests as the automated coverage.

- [ ] **Step 2: Wire delete actions**

Replace local-only delete calls with the new backend-aware helper:

```ts
const syncDeleteLibraryCases = async (cases: SavedInterfaceCase[]) => {
  const result = await deleteSavedCasesWithBackend(localStorage, cases)
  refreshSavedCases()
  librarySelection.value = []
  return result
}
```

Use it from:

- `deleteLibraryCase`,
- `deleteSelectedLibraryCases`,
- `deleteLibraryCasesByScope`.

- [ ] **Step 3: Wire ordering action**

Update `moveLibraryCase` to:

- block cross-project visible moves,
- save local order first,
- call `syncCaseOrderingWithBackend(localStorage, projectName)`,
- show backend/local fallback message.

Core guard:

```ts
if (projectNameForLibraryCase(currentOrder[sourceIndex]) !== projectNameForLibraryCase(currentOrder[targetIndex])) {
  ElMessage.warning('请先筛选到单个项目后再调整排序')
  return
}
```

- [ ] **Step 4: Run frontend build/typecheck**

Run:

```bash
cd /path/to/Haitlify/apps/web
npm run build
```

Expected: PASS.

---

### Task 4: Decisions And Final Verification

**Files:**
- Modify: `docs/ai-test-platform/decisions.md`

- [ ] **Step 1: Update decision log**

Append the decision that this slice completes local-first delete sync and project-scoped ordering sync, while backend-first loading and conflicts remain next-phase work.

- [ ] **Step 2: Run frontend tests**

Run:

```bash
cd /path/to/Haitlify/apps/web
npm test -- --run
```

Expected: all Vitest tests pass.

- [ ] **Step 3: Run backend tests**

Run:

```bash
cd /path/to/Haitlify/server/platform
./mvnw test
```

Expected: all backend tests pass.

- [ ] **Step 4: Run frontend build**

Run:

```bash
cd /path/to/Haitlify/apps/web
npm run build
```

Expected: typecheck and Vite build pass. Existing large chunk warnings are acceptable if no errors occur.

- [ ] **Step 5: Report status with evidence**

Include:

- changed files,
- exact verification commands,
- pass/fail counts,
- any remaining non-goals or risks.

---

## Self-Review

- Spec coverage: backend batch delete, frontend local-first delete sync, project-scoped order sync, project partitioning, fallback behavior, and non-goals are each covered by tasks.
- Placeholder scan: no `TBD`, `TODO`, or `implement later` steps remain.
- Type consistency: helper names are `deleteSavedCasesWithBackend`, `syncCaseOrderingWithBackend`, and `projectNameForSavedCase`; UI can use a local `projectNameForLibraryCase` wrapper for display logic.
