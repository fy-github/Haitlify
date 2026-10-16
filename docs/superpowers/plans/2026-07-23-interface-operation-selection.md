> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Interface Operation Selection Workspace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move imported OpenAPI operations from the narrow context pane into a compact central table, while updating the default breadcrumb and resizing the generation-granularity control.

**Architecture:** Add a presentation-only `InterfaceOperationTable` that receives filtered operations and controlled selection state. Narrow `InterfaceCaseContextPane` to filters and generation configuration, and let `App.vue` decide whether the central stage shows the import empty state, operation selection table, generated cases, or delivery. Existing parsing, filtering, generation, review, and persistence functions remain the single source of business behavior.

**Tech Stack:** Vue 3 `<script setup>`, TypeScript, Element Plus, Lucide Vue, Vitest + Vue Test Utils, Playwright, axe-core, semantic Studio Canvas CSS tokens.

---

## File Map

- Create `apps/web/src/components/workflow/InterfaceOperationTable.vue`: central imported-operation table and selection commands.
- Create `apps/web/src/components/workflow/InterfaceOperationTable.test.ts`: component contract, selection, accessibility, and filtering-state tests.
- Modify `apps/web/src/components/workflow/InterfaceCaseContextPane.vue`: retain filters and generation configuration only; compact the segmented control.
- Modify `apps/web/src/components/workflow/InterfaceCaseContextPane.test.ts`: protect the narrowed left-pane contract and generation-granularity behavior.
- Modify `apps/web/src/App.vue`: breadcrumb fallback, central-stage state switching, and operation-table event wiring.
- Modify `apps/web/src/appShellStructure.test.ts`: protect the new component boundaries and stage conditions.
- Modify `apps/web/src/styles/studio-canvas.css`: central operation-table sizing and responsive behavior.
- Modify `apps/web/e2e/studio-canvas.spec.ts`: browser journey, keyboard, axe, and compact behavior.
- Modify `apps/web/e2e/studio-canvas.spec.ts-snapshots/`: refresh affected interface baselines.

---

### Task 1: Build The Central Interface Operation Table

**Files:**

- Create: `apps/web/src/components/workflow/InterfaceOperationTable.vue`
- Create: `apps/web/src/components/workflow/InterfaceOperationTable.test.ts`

- [ ] **Step 1: Write failing component tests**

Create two `ParsedOperation` fixtures and mount the missing component with one selected ID. Assert the toolbar, columns, selection count, accessible checkbox names, and command events:

```ts
// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import type { ParsedOperation } from '../../domain/openapiParser'
import InterfaceOperationTable from './InterfaceOperationTable.vue'

const operations: ParsedOperation[] = [
  {
    id: 'get /v4/pm/project-tree',
    module: '项目树',
    method: 'GET',
    path: '/v4/pm/project-tree',
    summary: '获取项目树入口节点',
    operationId: 'getProjectTree',
    parameters: [],
    responseStatuses: ['200']
  },
  {
    id: 'post /v4/pm/project-tree/group',
    module: '项目树',
    method: 'POST',
    path: '/v4/pm/project-tree/group',
    summary: '创建项目分组',
    operationId: 'createProjectGroup',
    parameters: [],
    responseStatuses: ['201']
  }
]

describe('InterfaceOperationTable', () => {
  it('renders imported operations and controlled selection state', () => {
    const wrapper = mount(InterfaceOperationTable, {
      props: { operations, selectedIds: [operations[0].id] },
      global: { plugins: [ElementPlus] }
    })

    expect(wrapper.get('[data-testid="interface-operation-table"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('2 个接口')
    expect(wrapper.text()).toContain('已选 1 个')
    expect(wrapper.text()).toContain('请求方法')
    expect(wrapper.text()).toContain('接口名称')
    expect(wrapper.text()).toContain('/v4/pm/project-tree')
    expect((wrapper.get('[aria-label="选择接口 获取项目树入口节点"]').element as HTMLInputElement).checked).toBe(true)
  })

  it('emits row and batch selection commands without mutating props', async () => {
    const selectedIds = [operations[0].id]
    const wrapper = mount(InterfaceOperationTable, {
      props: { operations, selectedIds },
      global: { plugins: [ElementPlus] }
    })

    await wrapper.get('[aria-label="选择接口 创建项目分组"]').setValue(true)
    await wrapper.get('[data-testid="select-filtered-interface-operations"]').trigger('click')
    await wrapper.get('[data-testid="clear-interface-operation-selection"]').trigger('click')

    expect(wrapper.emitted('toggleOperation')).toEqual([[operations[1], true]])
    expect(wrapper.emitted('selectFiltered')).toEqual([[]])
    expect(wrapper.emitted('clearSelected')).toEqual([[]])
    expect(selectedIds).toEqual([operations[0].id])
  })
})
```

- [ ] **Step 2: Run the component test and capture RED**

Run:

```bash
cd apps/web
npm test -- --run src/components/workflow/InterfaceOperationTable.test.ts
```

Expected: FAIL because `InterfaceOperationTable.vue` does not exist.

- [ ] **Step 3: Implement the controlled table contract**

Use this public contract:

```ts
const props = withDefaults(defineProps<{
  operations: ParsedOperation[]
  selectedIds: string[]
  loading?: boolean
}>(), { loading: false })

const emit = defineEmits<{
  toggleOperation: [operation: ParsedOperation, checked: boolean]
  selectFiltered: []
  clearSelected: []
}>()

const selectedIdSet = computed(() => new Set(props.selectedIds))
```

Render one root surface:

```vue
<section class="interface-operation-table" data-testid="interface-operation-table">
  <header class="interface-operation-table__toolbar">
    <div>
      <strong>接口列表</strong>
      <span>{{ operations.length }} 个接口</span>
      <span>已选 {{ selectedIds.length }} 个</span>
    </div>
    <div class="interface-operation-table__commands">
      <button
        type="button"
        data-testid="clear-interface-operation-selection"
        :disabled="selectedIds.length === 0"
        @click="emit('clearSelected')"
      >清空选择</button>
      <button
        type="button"
        data-testid="select-filtered-interface-operations"
        :disabled="operations.length === 0"
        @click="emit('selectFiltered')"
      >全选当前结果</button>
    </div>
  </header>

  <el-table :data="operations" :aria-busy="loading" row-key="id" height="100%">
    <el-table-column label="" width="48" fixed="left">
      <template #default="{ row }">
        <input
          type="checkbox"
          :aria-label="`选择接口 ${row.summary}`"
          :checked="selectedIdSet.has(row.id)"
          @change="emit('toggleOperation', row, ($event.target as HTMLInputElement).checked)"
        >
      </template>
    </el-table-column>
    <el-table-column prop="method" label="请求方法" width="108" />
    <el-table-column prop="module" label="模块" min-width="120" show-overflow-tooltip />
    <el-table-column prop="summary" label="接口名称" min-width="220" show-overflow-tooltip />
    <el-table-column prop="path" label="请求路径" min-width="260" show-overflow-tooltip />
  </el-table>
</section>
```

Keep the explicit command labels visible and add these scoped styles so the root is the only bordered surface:

```css
.interface-operation-table {
  display: grid;
  min-width: 0;
  min-height: 0;
  grid-template-rows: 48px minmax(0, 1fr);
  overflow: hidden;
  background: var(--surface-panel);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-control);
  box-shadow: none;
}

.interface-operation-table__toolbar,
.interface-operation-table__commands {
  display: flex;
  align-items: center;
  gap: 8px;
}

.interface-operation-table__toolbar {
  justify-content: space-between;
  min-width: 0;
  padding: 0 12px;
  color: var(--text-secondary);
  font-size: var(--font-size-sm);
}

.interface-operation-table__toolbar > div:first-child {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 10px;
}

.interface-operation-table__toolbar strong {
  color: var(--text-primary);
  font-weight: 600;
}

.interface-operation-table__commands button {
  min-height: 30px;
  padding: 0 9px;
  color: var(--text-regular);
  background: var(--surface-raised);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-control);
  cursor: pointer;
  font: inherit;
}

.interface-operation-table__commands button:focus-visible,
.interface-operation-table input[type='checkbox']:focus-visible {
  outline: 3px solid var(--brand-primary-focus);
  outline-offset: 2px;
}

.interface-operation-table__commands button:disabled {
  color: var(--text-disabled);
  cursor: not-allowed;
}

.interface-operation-table :deep(.el-table) {
  min-width: 760px;
  color: var(--text-regular);
  font-size: var(--font-size-sm);
}
```

- [ ] **Step 4: Run the focused test and production type check**

Run:

```bash
cd apps/web
npm test -- --run src/components/workflow/InterfaceOperationTable.test.ts
npm run build
```

Expected: component test PASS and `vue-tsc --noEmit` exits 0.

- [ ] **Step 5: Commit Task 1**

```bash
git add apps/web/src/components/workflow/InterfaceOperationTable.vue \
  apps/web/src/components/workflow/InterfaceOperationTable.test.ts
git commit -m "feat: add central interface operation table"
```

---

### Task 2: Narrow The Context Pane And Compact The Granularity Control

**Files:**

- Modify: `apps/web/src/components/workflow/InterfaceCaseContextPane.vue`
- Modify: `apps/web/src/components/workflow/InterfaceCaseContextPane.test.ts`

- [ ] **Step 1: Rewrite the context-pane test to require filters and configuration only**

Delete the `ParsedOperation` fixture and remove `operations` and `selectedOperationIds` from the mount props. Replace operation-list assertions with:

```ts
expect(wrapper.text()).toContain('筛选')
expect(wrapper.text()).toContain('生成配置')
expect(wrapper.text()).not.toContain('接口列表')
expect(wrapper.find('[data-testid="select-filtered-operations"]').exists()).toBe(false)
expect(wrapper.find('[data-testid="clear-selected-operations"]').exists()).toBe(false)
expect(wrapper.find('[aria-label="接口列表"]').exists()).toBe(false)
expect(wrapper.get('[aria-label="生成粒度"]').classes()).toContain('studio-interface-context-pane__granularity')
```

Keep the existing module/path/summary/method/template/granularity interactions and these event assertions:

```ts
expect(wrapper.emitted('updateModule')).toEqual([['订单']])
expect(wrapper.emitted('updatePathKeyword')).toEqual([['/api/orders']])
expect(wrapper.emitted('updateSummaryKeyword')).toEqual([['查询']])
expect(wrapper.emitted('updateMethod')).toEqual([['GET']])
expect(wrapper.emitted('updateTemplateId')).toEqual([['smoke']])
expect(wrapper.emitted('updateGranularity')).toEqual([['risk']])
expect(wrapper.emitted('toggleOperation')).toBeUndefined()
expect(wrapper.emitted('selectFiltered')).toBeUndefined()
expect(wrapper.emitted('clearSelected')).toBeUndefined()
```

Replace the old empty-operation test with a contract test that mounts the pane without any operation props:

```ts
it('keeps only filters and generation configuration in the context pane', () => {
  const wrapper = mountPane()

  expect(wrapper.text()).toContain('筛选')
  expect(wrapper.text()).toContain('生成配置')
  expect(wrapper.text()).not.toContain('接口列表')
  expect(wrapper.find('[aria-label="接口列表"]').exists()).toBe(false)
})
```

- [ ] **Step 2: Run the context-pane test and capture RED**

Run:

```bash
cd apps/web
npm test -- --run src/components/workflow/InterfaceCaseContextPane.test.ts
```

Expected: FAIL because the component still requires operation props and renders the left-side interface list.

- [ ] **Step 3: Remove operation-list responsibilities**

Delete these props:

```ts
operations: ParsedOperation[]
selectedOperationIds: string[]
```

Delete these events and helpers:

```ts
toggleOperation: [operation: ParsedOperation, checked: boolean]
selectFiltered: []
clearSelected: []

const selectedIds = computed(...)
const onOperationChange = (...)
```

Remove the entire `studio-interface-context-pane__operations` section. Remove unused `computed` and `ParsedOperation` imports plus dead operation-list styles.

Also remove `<span>{{ operations.length }}</span>` from the filter header. Delete the now-unused style blocks for `__operations`, `__actions`, `__list`, `__operation`, `__operation-text`, and `__method`, plus the pane-local button rules that existed only for operation batch actions.

- [ ] **Step 4: Add the compact granularity class and scoped token styles**

Update the segmented control:

```vue
<el-segmented
  class="studio-interface-context-pane__granularity"
  aria-label="生成粒度"
  :model-value="granularity"
  :options="granularityOptions"
  @update:model-value="emit('updateGranularity', $event as GenerationGranularity)"
/>
```

Add scoped styles that win after Element Plus styles:

```css
.studio-interface-context-pane__granularity {
  width: 100%;
  min-height: 32px;
  padding: 2px;
  border-radius: var(--radius-control);
}

.studio-interface-context-pane__granularity :deep(.el-segmented__group) {
  width: 100%;
}

.studio-interface-context-pane__granularity :deep(.el-segmented__item) {
  min-width: 0;
  min-height: 28px;
  flex: 1 1 0;
  padding: 0 6px;
  font-size: var(--font-size-xs);
}
```

Keep letter spacing at `0` and do not introduce viewport-scaled font sizes.

- [ ] **Step 5: Run focused tests and build**

Run:

```bash
cd apps/web
npm test -- --run src/components/workflow/InterfaceCaseContextPane.test.ts
npm run build
```

Expected: tests PASS and build exits 0.

- [ ] **Step 6: Commit Task 2**

```bash
git add apps/web/src/components/workflow/InterfaceCaseContextPane.vue \
  apps/web/src/components/workflow/InterfaceCaseContextPane.test.ts
git commit -m "style: simplify interface generation context"
```

---

### Task 3: Integrate Operation Selection Into The Interface Workflow

**Files:**

- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/appShellStructure.test.ts`

- [ ] **Step 1: Add failing structure assertions for the breadcrumb and stage rendering**

Add assertions that protect the default label and central operation table:

```ts
expect(appSource).toContain("? '智能用例设计'")
expect(appSource).toContain("import InterfaceOperationTable from './components/workflow/InterfaceOperationTable.vue'")
expect(interfaceBlock).toContain('<InterfaceOperationTable')
expect(interfaceBlock).toContain(':operations="filteredOperations"')
expect(interfaceBlock).toContain(':selected-ids="selectedOperationIds"')
expect(interfaceBlock).toContain('@toggle-operation="toggleOperation"')
expect(interfaceBlock).toContain('@select-filtered="selectFilteredOperations"')
expect(interfaceBlock).toContain('@clear-selected="clearSelectedOperations"')
expect(appSource).not.toContain(':operations="filteredOperations"\n        :selected-operation-ids="selectedOperationIds"')
expect(appSource).toContain('selectedOperationIds.value = Array.from(new Set([')
expect(appSource).toContain('...(parsed.value?.operations || [])')
expect(interfaceBlock).toContain("interfaceCaseWorkflow.activeStage === 'import' && importedProjects.length === 0")
expect(interfaceBlock).toContain('v-else-if="interfaceOperationSelectionEmpty"')
expect(interfaceBlock).toContain('v-else-if="interfaceOperationSelectionVisible"')
```

Delete the previous `continues from an imported OpenAPI source` assertions for `interfaceImportState`; the imported-ready state is being replaced by the operation table.

- [ ] **Step 2: Run the structure test and capture RED**

Run:

```bash
cd apps/web
npm test -- --run src/appShellStructure.test.ts
```

Expected: FAIL because the new component and breadcrumb fallback are absent.

- [ ] **Step 3: Update the default project label without affecting Web automation**

Replace `studioProjectLabel` with an explicit branch:

```ts
const studioProjectLabel = computed(() => {
  if (activeNav.value !== 'api' || globalProjectLabel.value !== '默认项目') {
    return globalProjectLabel.value
  }
  return generationKind.value === 'interface' ? '智能用例设计' : '订单服务'
})
```

Keep `studioSectionLabel` unchanged so the second breadcrumb segment remains `用例生成` for the interface workspace.

- [ ] **Step 4: Import and render `InterfaceOperationTable`**

Add the import beside the other workflow components. Remove the now-unused `interfaceImportState` computed and create these view-state computeds:

```ts
const interfaceOperationSelectionVisible = computed(() =>
  importedProjects.value.length > 0
  && generatedCases.value.length === 0
  && ['import', 'generate'].includes(interfaceCaseWorkflow.value.activeStage)
)

const interfaceOperationSelectionEmpty = computed(() =>
  interfaceOperationSelectionVisible.value && filteredOperations.value.length === 0
)
```

Replace the imported-ready `WorkflowStateView` with ordered states:

```vue
<WorkflowStateView
  v-if="interfaceCaseWorkflow.activeStage === 'import' && importedProjects.length === 0"
  variant="empty"
  title="导入 OpenAPI 开始接口用例设计"
  description="导入后可筛选接口并配置生成策略"
  action-label="导入 OpenAPI"
  @action="pickOpenApiFiles"
/>

<WorkflowStateView
  v-else-if="interfaceOperationSelectionEmpty"
  variant="filtered-empty"
  title="没有符合条件的接口"
  description="当前筛选条件没有匹配结果"
  action-label="清除筛选"
  @action="resetInterfaceOperationFilters"
/>

<InterfaceOperationTable
  v-else-if="interfaceOperationSelectionVisible"
  :operations="filteredOperations"
  :selected-ids="selectedOperationIds"
  @toggle-operation="toggleOperation"
  @select-filtered="selectFilteredOperations"
  @clear-selected="clearSelectedOperations"
/>
```

Add a dedicated import-filter reset helper rather than reusing `resetInterfaceCaseFilters`, which controls generated-case filters:

```ts
const resetInterfaceOperationFilters = () => {
  importFilter.module = 'ALL'
  importFilter.method = 'ALL'
  importFilter.pathKeyword = ''
  importFilter.summaryKeyword = ''
}
```

Wire the empty state to the exact helper name used in the script.

- [ ] **Step 5: Preserve global operation selection across filtering**

Make `全选当前结果` additive and make generation resolve all globally selected operations from the parsed document, not only visible filtered rows:

```ts
const selectedOperationIdSet = computed(() => new Set(selectedOperationIds.value))
const selectedOperations = computed(() =>
  (parsed.value?.operations || []).filter((operation) => selectedOperationIdSet.value.has(operation.id))
)

const selectFilteredOperations = () => {
  selectedOperationIds.value = Array.from(new Set([
    ...selectedOperationIds.value,
    ...filteredOperations.value.map((operation) => operation.id)
  ]))
}
```

This keeps hidden selections counted and included when generating cases. `clearSelectedOperations` remains the only batch command that clears the global set.

- [ ] **Step 6: Remove operation props and events from both context-pane instances**

For desktop and compact drawer instances, remove:

```vue
:operations="filteredOperations"
:selected-operation-ids="selectedOperationIds"
@toggle-operation="toggleOperation"
@select-filtered="selectFilteredOperations"
@clear-selected="clearSelectedOperations"
```

Keep all filter/template/granularity bindings unchanged.

- [ ] **Step 7: Run integration tests and build**

Run:

```bash
cd apps/web
npm test -- --run \
  src/appShellStructure.test.ts \
  src/domain/interfaceCaseWorkflow.test.ts \
  src/components/workflow/InterfaceCaseContextPane.test.ts \
  src/components/workflow/InterfaceOperationTable.test.ts
npm run build
```

Expected: all focused tests PASS and build exits 0.

- [ ] **Step 8: Commit Task 3**

```bash
git add apps/web/src/App.vue apps/web/src/appShellStructure.test.ts
git commit -m "feat: show imported operations in interface canvas"
```

---

### Task 4: Apply Studio Canvas And Responsive Styling

**Files:**

- Modify: `apps/web/src/styles/studio-canvas.css`
- Modify: `apps/web/src/appShellStructure.test.ts`

- [ ] **Step 1: Add failing style-contract assertions**

Load `studio-canvas.css` in the existing structure test and assert real rendered selectors:

```ts
expect(studioCanvasStyles).toContain('.studio-interface-case-canvas__stage > .interface-operation-table')
expect(studioCanvasStyles).toContain('.interface-operation-table__toolbar')
expect(studioCanvasStyles).toContain('@media (max-width: 1100px)')
expect(studioCanvasStyles).toContain('overflow-x: auto')
```

Do not add unused alias selectors solely to satisfy string assertions.

- [ ] **Step 2: Run the structure test and capture RED**

Run:

```bash
cd apps/web
npm test -- --run src/appShellStructure.test.ts
```

Expected: FAIL because the central table stage selector does not exist.

- [ ] **Step 3: Add central-stage sizing**

Add semantic-token rules:

```css
.studio-interface-case-canvas__stage > .interface-operation-table {
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
}

.studio-interface-case-canvas .interface-operation-table__toolbar {
  flex: 0 0 48px;
  border-bottom: 1px solid var(--border-subtle);
}
```

Ensure the table root is the only bordered surface. Toolbar, table, and empty/loading state must not create nested cards or shadows.

- [ ] **Step 4: Add compact behavior**

Inside `@media (max-width: 1100px)`:

```css
.studio-interface-case-canvas__stage > .interface-operation-table {
  overflow: hidden;
}

.studio-interface-case-canvas .interface-operation-table .el-table__body-wrapper,
.studio-interface-case-canvas .interface-operation-table .el-scrollbar__wrap {
  overflow-x: auto;
}
```

At `max-width: 900px`, preserve `var(--font-size-sm)` for body cells and keep batch commands visible. Do not hide the checkbox or request-path column; horizontal scrolling is the fallback.

- [ ] **Step 5: Run structure tests and build**

Run:

```bash
cd apps/web
npm test -- --run src/appShellStructure.test.ts
npm run build
```

Expected: PASS with no TypeScript errors.

- [ ] **Step 6: Commit Task 4**

```bash
git add apps/web/src/styles/studio-canvas.css apps/web/src/appShellStructure.test.ts
git commit -m "style: fit interface selection workspace"
```

---

### Task 5: Update Browser, Accessibility, And Visual Coverage

**Files:**

- Modify: `apps/web/e2e/studio-canvas.spec.ts`
- Modify: `apps/web/e2e/studio-canvas.spec.ts-snapshots/interface-import-1440-chromium-darwin.png`
- Modify: `apps/web/e2e/studio-canvas.spec.ts-snapshots/interface-generate-table-1280-chromium-darwin.png`
- Modify only the two listed interface snapshots; do not update unrelated baselines.

- [ ] **Step 1: Update the import journey assertions**

After `setInputFiles('../../Haitlify.openapi.json')`, replace imported-ready copy assertions with:

```ts
const operationTable = page.getByTestId('interface-operation-table')
await expect(operationTable).toBeVisible()
await expect(page.getByText('智能用例设计', { exact: true })).toBeVisible()
await expect(page.getByText('用例生成', { exact: true })).toBeVisible()
await expect(page.getByRole('complementary', { name: '接口与生成配置' })).not.toContainText('接口列表')
await expect(operationTable.getByText('接口列表', { exact: true })).toBeVisible()
```

Assert the granularity control's bounding box is no taller than 34px and all three items fit its width without overflow:

```ts
const granularity = page.getByLabel('生成粒度')
const granularityBox = await granularity.boundingBox()
expect(granularityBox?.height).toBeLessThanOrEqual(34)
await expect(granularity.locator('[role="radio"]')).toHaveCount(3)
for (const item of await granularity.locator('[role="radio"]').all()) {
  await expect(item).toBeVisible()
}
```

- [ ] **Step 2: Cover selection behavior in the central table**

Use the left context filters, then assert the central table updates. Calculate operation and checkbox counts dynamically:

```ts
const visibleRows = operationTable.locator('.el-table__body tbody tr')
await expect.poll(() => visibleRows.count()).toBeGreaterThan(0)
const rowCount = await visibleRows.count()
await expect(operationTable.locator('input[type="checkbox"]')).toHaveCount(rowCount)
```

Activate a row checkbox with the keyboard, click `全选当前结果`, change the filter, and verify the global selected count does not decrease simply because rows became hidden. Click `清空选择` and verify it becomes zero.

- [ ] **Step 3: Protect pre-generation stage behavior**

Navigate to `配置生成` and assert `InterfaceOperationTable` remains visible until `生成用例` is activated. After generation completes, assert the operation table is absent and `InterfaceCaseTable` toolbar is visible.

- [ ] **Step 4: Add axe and compact checks**

Run `expectNoAxeViolations` against the operation table stage. At `1024x768`, assert the context is in a drawer while the operation table remains central. Scroll the table horizontally and verify the path column becomes visible without page-level overflow.

- [ ] **Step 5: Refresh and inspect affected snapshots**

Run:

```bash
cd apps/web
npm run test:e2e -- --grep interface --update-snapshots --output=/tmp/haitlify-interface-operation-update
```

Inspect both changed PNGs against the approved layout. Confirm:

- `智能用例设计 / 用例生成` appears in the top bar.
- Left pane has filters/configuration only.
- Central table appears immediately after import.
- Granularity control is compact and labels fit.
- No overlap, clipped command, nested card, or blank table exists.

- [ ] **Step 6: Re-run interface E2E without snapshot updates**

Run:

```bash
cd apps/web
npm run test:e2e -- --grep interface --output=/tmp/haitlify-interface-operation-verify
```

Expected: all interface scenarios PASS without changing snapshots.

- [ ] **Step 7: Commit Task 5**

```bash
git add apps/web/e2e/studio-canvas.spec.ts \
  apps/web/e2e/studio-canvas.spec.ts-snapshots
git commit -m "test: verify central interface operation selection"
```

---

### Task 6: Run Full Regression And Final Visual Review

**Files:**

- Verify all modified files.
- Reference: `docs/superpowers/specs/2026-07-22-interface-operation-selection-design.md`
- Reference: `docs/superpowers/design-assets/interface-case/02-information-architecture.png`
- Reference: `docs/superpowers/design-assets/interface-case/03-stage-behavior.png`
- Reference: `docs/superpowers/design-assets/interface-case/04-visual-responsive.png`

- [ ] **Step 1: Run all Vitest tests**

```bash
cd apps/web
npm test -- --run
```

Expected: all test files and tests PASS.

- [ ] **Step 2: Run production type checking and build**

```bash
cd apps/web
npm run build
```

Expected: `vue-tsc --noEmit` and Vite both exit 0.

- [ ] **Step 3: Run the complete Playwright suite**

```bash
cd apps/web
npm run test:e2e -- --output=/tmp/haitlify-interface-operation-full-e2e
```

Expected: all Web and interface Studio Canvas scenarios PASS, including axe and screenshot comparisons.

- [ ] **Step 4: Perform the final visual checklist**

Compare the implementation with the three referenced, tracked design images at 1440px, 1280px, and 1024px. Do not regenerate, replace, or delete the design assets. Verify:

- Default breadcrumb reads `智能用例设计 / 用例生成`.
- Left pane contains only filters and generation configuration.
- Granularity control is 32-34px high with three equal, legible segments.
- Imported operations fill the central workspace before generation.
- Generated cases replace operations after generation.
- Table headers, checkboxes, batch commands, names, and paths do not overlap.
- Compact context drawer does not remove the central operation table.
- Deep blue remains sourced from `--brand-primary`.

- [ ] **Step 5: Check repository cleanliness**

```bash
git status --short
git diff --check 571b50f..HEAD
```

Expected: no uncommitted source changes, no Playwright output in the worktree, and no whitespace errors.

- [ ] **Step 6: Commit baseline-only changes only if the full run changed them**

If no snapshots changed, do not create an empty commit. If legitimate baseline changes remain:

```bash
git add apps/web/e2e/studio-canvas.spec.ts-snapshots
git commit -m "test: refresh interface operation selection baselines"
```
