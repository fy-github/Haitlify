> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Interface Case Studio Canvas Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the legacy interface-case workspace with the approved four-stage Studio Canvas workflow while preserving OpenAPI import, generation, review, save, automation transfer, export, and row operations.

**Architecture:** Keep all domain actions and repository paths in `App.vue`, but derive interface-stage state in a focused view-model and render it through the existing `WorkflowHeader`. Use the existing `AppShell` context and inspector slots for the desktop three-column composition, reuse `ResponsiveDrawer` at compact widths, and extract interface context, table, review, and delivery presentation into small components. The implementation must use the committed design assets in `docs/superpowers/design-assets/interface-case/` as the visual source of truth.

**Tech Stack:** Vue 3 Composition API, TypeScript, Element Plus, Lucide Vue, Vitest, Vue Test Utils, Playwright, axe-core.

---

## File Map

**Create**

- `apps/web/src/domain/interfaceCaseWorkflow.ts`: pure four-stage interface workflow view-model.
- `apps/web/src/domain/interfaceCaseWorkflow.test.ts`: stage enablement, status, and primary-action tests.
- `apps/web/src/components/workflow/InterfaceCaseContextPane.vue`: OpenAPI sources, endpoint selection, filters, template, and granularity.
- `apps/web/src/components/workflow/InterfaceCaseContextPane.test.ts`: context rendering and emitted action tests.
- `apps/web/src/components/workflow/InterfaceCaseTable.vue`: generated-case toolbar, table selection, fixed operation column, and empty state.
- `apps/web/src/components/workflow/InterfaceCaseTable.test.ts`: toolbar, filter, row-action, and accessibility tests.
- `apps/web/src/components/workflow/InterfaceReviewInspector.vue`: interface review summary and current findings.
- `apps/web/src/components/workflow/InterfaceReviewInspector.test.ts`: finding action and severity tests.
- `apps/web/src/components/workflow/InterfaceCaseDeliveryStage.vue`: save, API automation transfer, and CSV export summary.
- `apps/web/src/components/workflow/InterfaceCaseDeliveryStage.test.ts`: non-blocking unreviewed warning and delivery action tests.

**Modify**

- `apps/web/src/components/workflow/WorkflowHeader.vue`: accept shared string stage keys so Web and interface workflows use the same component.
- `apps/web/src/components/workflow/WorkflowHeader.test.ts`: verify interface labels use the same status and navigation behavior.
- `apps/web/src/App.vue`: replace the legacy interface branch, wire interface stage state, context/inspector slots, drawers, and existing actions.
- `apps/web/src/appShellStructure.test.ts`: assert the old interface layout is gone and shared Studio Canvas structure is used.
- `apps/web/src/styles/studio-canvas.css`: interface canvas, context, table toolbar, inspector, delivery, and compact layouts.
- `apps/web/e2e/studio-canvas.spec.ts`: replace legacy-interface assertions with four-stage interface workflow, accessibility, and screenshot coverage.
- `docs/superpowers/specs/2026-07-22-interface-case-studio-canvas-design.md`: already amended to state that unreviewed generated cases remain saveable.

**Reference Only**

- `docs/superpowers/design-assets/interface-case/02-information-architecture.png`
- `docs/superpowers/design-assets/interface-case/03-stage-behavior.png`
- `docs/superpowers/design-assets/interface-case/04-visual-responsive.png`
- `apps/web/src/domain/caseReview.ts`
- `apps/web/src/domain/caseGenerator.ts`
- `apps/web/src/domain/openapiParser.ts`
- `apps/web/src/domain/openapiProjectRepository.ts`

---

### Task 1: Add The Interface Workflow View-Model And Share WorkflowHeader Stage Keys

**Files:**

- Create: `apps/web/src/domain/interfaceCaseWorkflow.ts`
- Create: `apps/web/src/domain/interfaceCaseWorkflow.test.ts`
- Modify: `apps/web/src/components/workflow/WorkflowHeader.vue`
- Modify: `apps/web/src/components/workflow/WorkflowHeader.test.ts`

- [ ] **Step 1: Write the failing interface workflow tests**

Create tests with the exact stage contract:

```ts
import { describe, expect, it } from 'vitest'
import { buildInterfaceCaseWorkflowView } from './interfaceCaseWorkflow'

const base = {
  requestedStage: 'import' as const,
  importedSourceCount: 0,
  parsedOperationCount: 0,
  selectedOperationCount: 0,
  generatedCaseCount: 0,
  reviewAttempted: false,
  saving: false
}

describe('interfaceCaseWorkflow', () => {
  it('uses the approved four interface stages', () => {
    const view = buildInterfaceCaseWorkflowView(base)
    expect(view.stages.map(({ key, label }) => ({ key, label }))).toEqual([
      { key: 'import', label: '导入接口' },
      { key: 'generate', label: '配置生成' },
      { key: 'review', label: 'AI 评审' },
      { key: 'deliver', label: '保存 / 交付' }
    ])
  })

  it('allows delivery after generation without requiring AI review', () => {
    const view = buildInterfaceCaseWorkflowView({
      ...base,
      requestedStage: 'deliver',
      importedSourceCount: 1,
      parsedOperationCount: 8,
      selectedOperationCount: 3,
      generatedCaseCount: 12
    })
    expect(view.activeStage).toBe('deliver')
    expect(view.primaryAction).toEqual({ label: '保存用例', disabled: false, loading: false })
    expect(view.reviewWarning).toBe('尚未运行 AI 评审，仍可保存或交付')
  })

  it('makes review complete after an attempt even when findings remain', () => {
    const view = buildInterfaceCaseWorkflowView({
      ...base,
      requestedStage: 'review',
      importedSourceCount: 1,
      parsedOperationCount: 8,
      selectedOperationCount: 3,
      generatedCaseCount: 12,
      reviewAttempted: true
    })
    expect(view.stages.find((stage) => stage.key === 'review')?.status).toBe('active')
    expect(view.primaryAction.label).toBe('继续交付')
  })
})
```

- [ ] **Step 2: Run the workflow test and verify it fails**

Run:

```bash
cd apps/web
npm test -- --run src/domain/interfaceCaseWorkflow.test.ts
```

Expected: FAIL because `interfaceCaseWorkflow.ts` does not exist.

- [ ] **Step 3: Implement the pure interface workflow view-model**

Create these public types and function:

```ts
import type { WorkflowStageStatus } from './caseDesignWorkflow'

export type InterfaceCaseStage = 'import' | 'generate' | 'review' | 'deliver'

export interface InterfaceCaseWorkflowInput {
  requestedStage: InterfaceCaseStage
  importedSourceCount: number
  parsedOperationCount: number
  selectedOperationCount: number
  generatedCaseCount: number
  reviewAttempted: boolean
  saving: boolean
}

export interface InterfaceCaseStageView {
  key: InterfaceCaseStage
  label: string
  enabled: boolean
  status: WorkflowStageStatus
}

export interface InterfaceCaseWorkflowView {
  activeStage: InterfaceCaseStage
  stages: InterfaceCaseStageView[]
  primaryAction: { label: string; disabled: boolean; loading: boolean }
  reviewWarning: string
}
```

Use these rules in `buildInterfaceCaseWorkflowView`:

- `import` is always enabled.
- `generate` is enabled when at least one operation was parsed.
- `review` and `deliver` are enabled when at least one case was generated.
- `deliver` does not require `reviewAttempted`.
- Completed states are source imported, cases generated, and review attempted.
- Primary actions are `导入 OpenAPI`, `继续配置`, `生成用例`, `运行 AI 评审`, `继续交付`, and `保存用例` according to the active stage.
- `reviewWarning` is non-empty only when generated cases exist and review has not been attempted.

- [ ] **Step 4: Generalize WorkflowHeader stage keys without changing its DOM or CSS classes**

Replace its imported stage types with a local structural type:

```ts
export interface WorkflowHeaderStage {
  key: string
  label: string
  enabled: boolean
  status: 'pending' | 'active' | 'done' | 'error'
}

defineProps<{
  title: string
  saveLabel: string
  stages: WorkflowHeaderStage[]
  primaryAction: { label: string; disabled: boolean; loading: boolean }
  secondaryAction?: { label: string; disabled: boolean }
  modeAction?: { label: string; disabled: boolean }
}>()

const emit = defineEmits<{
  navigateStage: [stage: string]
  primary: []
  secondary: []
  modeAction: []
}>()
```

Keep the existing `studio-workflow-header`, `studio-workflow-header__steps`, and `studio-workflow-step` markup unchanged.

- [ ] **Step 5: Add a WorkflowHeader regression test for interface stages**

Mount the header with the four interface stages and assert:

```ts
expect(wrapper.get('[aria-label="测试设计流程"]').findAll('button')).toHaveLength(4)
await wrapper.get('button[aria-label="AI 评审，当前阶段"]').trigger('click')
expect(wrapper.emitted('navigateStage')?.[0]).toEqual(['review'])
expect(wrapper.get('button[aria-label="AI 评审，当前阶段"]').attributes('aria-current')).toBe('step')
```

- [ ] **Step 6: Run focused tests**

Run:

```bash
cd apps/web
npm test -- --run src/domain/interfaceCaseWorkflow.test.ts src/components/workflow/WorkflowHeader.test.ts
```

Expected: both files PASS.

- [ ] **Step 7: Commit Task 1**

```bash
git add apps/web/src/domain/interfaceCaseWorkflow.ts apps/web/src/domain/interfaceCaseWorkflow.test.ts apps/web/src/components/workflow/WorkflowHeader.vue apps/web/src/components/workflow/WorkflowHeader.test.ts
git commit -m "feat: add interface case workflow stages"
```

---

### Task 2: Build The Interface Context Pane

**Files:**

- Create: `apps/web/src/components/workflow/InterfaceCaseContextPane.vue`
- Create: `apps/web/src/components/workflow/InterfaceCaseContextPane.test.ts`

- [ ] **Step 1: Write failing component tests for sources, filtering, and endpoint selection**

Use one imported source and two parsed operations. Assert:

```ts
expect(wrapper.get('[aria-label="接口与生成配置"]').exists()).toBe(true)
expect(wrapper.text()).toContain('orders-v3.json')
expect(wrapper.text()).toContain('POST /api/orders')
expect(wrapper.text()).toContain('GET /api/orders/{id}')
await wrapper.get('[data-testid="interface-import-button"]').trigger('click')
expect(wrapper.emitted('importOpenApi')).toHaveLength(1)
await wrapper.get('[data-testid="select-filtered-operations"]').trigger('click')
expect(wrapper.emitted('selectFiltered')).toHaveLength(1)
```

Also verify the component emits updates for path keyword, summary keyword, method, template ID, granularity, and individual operation selection.

- [ ] **Step 2: Run the component test and verify it fails**

Run:

```bash
cd apps/web
npm test -- --run src/components/workflow/InterfaceCaseContextPane.test.ts
```

Expected: FAIL because the component does not exist.

- [ ] **Step 3: Implement the context pane with explicit props and events**

Use this contract:

```ts
defineProps<{
  sources: Array<{ id: string; title: string; sourceFile: string; operationCount: number }>
  activeSourceId: string
  operations: ParsedOperation[]
  selectedOperationIds: string[]
  module: string
  pathKeyword: string
  summaryKeyword: string
  method: HttpMethod | 'ALL'
  templateId: string
  templateOptions: Array<{ value: string; label: string }>
  granularity: GenerationGranularity
}>()

const emit = defineEmits<{
  importOpenApi: []
  selectSource: [id: string]
  updateModule: [value: string]
  updatePathKeyword: [value: string]
  updateSummaryKeyword: [value: string]
  updateMethod: [value: HttpMethod | 'ALL']
  updateTemplateId: [value: string]
  updateGranularity: [value: GenerationGranularity]
  toggleOperation: [operation: ParsedOperation, checked: boolean]
  selectFiltered: []
  clearSelected: []
}>()
```

Render, in order: source selector/import command, filter controls, template, granularity segmented control, selection summary, all/clear commands, and the scrollable operation list. Use Lucide `Upload`, `Search`, and `CheckSquare` icons, and use semantic labels rather than visual-only placeholders.

- [ ] **Step 4: Run focused tests**

Run:

```bash
cd apps/web
npm test -- --run src/components/workflow/InterfaceCaseContextPane.test.ts
```

Expected: PASS.

- [ ] **Step 5: Commit Task 2**

```bash
git add apps/web/src/components/workflow/InterfaceCaseContextPane.vue apps/web/src/components/workflow/InterfaceCaseContextPane.test.ts
git commit -m "feat: add interface case context pane"
```

---

### Task 3: Build The Generated Interface Case Table And Operation Bar

**Files:**

- Create: `apps/web/src/components/workflow/InterfaceCaseTable.vue`
- Create: `apps/web/src/components/workflow/InterfaceCaseTable.test.ts`

- [ ] **Step 1: Write failing tests for the approved toolbar and fixed operation column**

Mount two `GeneratedInterfaceCase` rows and assert:

```ts
expect(wrapper.get('[data-testid="interface-case-toolbar"]').exists()).toBe(true)
expect(wrapper.get('[aria-label="搜索标题或接口路径"]').exists()).toBe(true)
expect(wrapper.text()).toContain('已选择 0 条')
expect(wrapper.text()).toContain('操作')
expect(wrapper.get('[aria-label="上移 API-001"]').exists()).toBe(true)
expect(wrapper.get('[aria-label="下移 API-001"]').exists()).toBe(true)
expect(wrapper.get('[aria-label="编辑 API-001"]').exists()).toBe(true)
expect(wrapper.get('[aria-label="删除 API-001"]').exists()).toBe(true)
```

Trigger search, filter, selection, activate, move, edit, and remove events. Assert the empty state emits `clearFilters` rather than mutating filters internally.

- [ ] **Step 2: Run the table test and verify it fails**

Run:

```bash
cd apps/web
npm test -- --run src/components/workflow/InterfaceCaseTable.test.ts
```

Expected: FAIL because the component does not exist.

- [ ] **Step 3: Implement the table contract**

Use these props and events:

```ts
defineProps<{
  cases: GeneratedInterfaceCase[]
  visibleFields: CaseField[]
  selectedIds: string[]
  keyword: string
  module: string
  caseType: string
  priority: string
  reviewStatus: string
  loading?: boolean
}>()

const emit = defineEmits<{
  selectionChange: [ids: string[]]
  updateKeyword: [value: string]
  updateModule: [value: string]
  updateCaseType: [value: string]
  updatePriority: [value: string]
  updateReviewStatus: [value: string]
  clearFilters: []
  activate: [testCase: GeneratedInterfaceCase]
  move: [testCase: GeneratedInterfaceCase, offset: -1 | 1]
  edit: [testCase: GeneratedInterfaceCase]
  remove: [testCase: GeneratedInterfaceCase]
  bulkReview: []
}>()
```

The toolbar contains search, module, type, priority, review status, selected count, `批量评审`, and a menu button for secondary batch commands. The Element Plus table has a selection column, visible configured fields, and a `fixed="right"` operation column. Use `ArrowUp`, `ArrowDown`, `Pencil`, and `Trash2`; every icon button receives a case-ID-specific `aria-label` and tooltip.

- [ ] **Step 4: Add stable selection synchronization**

Follow the `GeneratedWebCaseTable.vue` pattern: hold a typed `tableRef`, call `clearSelection`, then `toggleRowSelection` for IDs present in `selectedIds` after case/filter updates. Do not let filtering silently clear the parent selection.

- [ ] **Step 5: Run focused tests**

Run:

```bash
cd apps/web
npm test -- --run src/components/workflow/InterfaceCaseTable.test.ts
```

Expected: PASS.

- [ ] **Step 6: Commit Task 3**

```bash
git add apps/web/src/components/workflow/InterfaceCaseTable.vue apps/web/src/components/workflow/InterfaceCaseTable.test.ts
git commit -m "feat: add interface case result table"
```

---

### Task 4: Build The Interface Review Inspector And Delivery Stage

**Files:**

- Create: `apps/web/src/components/workflow/InterfaceReviewInspector.vue`
- Create: `apps/web/src/components/workflow/InterfaceReviewInspector.test.ts`
- Create: `apps/web/src/components/workflow/InterfaceCaseDeliveryStage.vue`
- Create: `apps/web/src/components/workflow/InterfaceCaseDeliveryStage.test.ts`

- [ ] **Step 1: Write failing inspector tests**

Pass high, medium, low, and info findings and assert the summary, severity text, scope, and message are visible. Trigger these events:

```ts
expect(wrapper.emitted('review')).toHaveLength(1)
expect(wrapper.emitted('supplementMissing')).toHaveLength(1)
expect(wrapper.emitted('locateFinding')?.[0]).toEqual([finding])
expect(wrapper.emitted('ignoreFinding')?.[0]).toEqual([finding.id])
```

The inspector must expose `aria-label="AI 质量评审"`, a text status, and no color-only severity indicators.

- [ ] **Step 2: Write failing delivery tests**

Assert generated counts, review warning, and actions:

```ts
expect(wrapper.text()).toContain('12 条接口用例待交付')
expect(wrapper.text()).toContain('尚未运行 AI 评审，仍可保存或交付')
expect(wrapper.get('[data-testid="interface-save-action"]').attributes('disabled')).toBeUndefined()
await wrapper.get('[data-testid="interface-save-action"]').trigger('click')
await wrapper.get('[data-testid="interface-transfer-action"]').trigger('click')
await wrapper.get('[data-testid="interface-export-action"]').trigger('click')
expect(wrapper.emitted('save')).toHaveLength(1)
expect(wrapper.emitted('transfer')).toHaveLength(1)
expect(wrapper.emitted('export')).toHaveLength(1)
```

- [ ] **Step 3: Run both tests and verify they fail**

Run:

```bash
cd apps/web
npm test -- --run src/components/workflow/InterfaceReviewInspector.test.ts src/components/workflow/InterfaceCaseDeliveryStage.test.ts
```

Expected: FAIL because both components are missing.

- [ ] **Step 4: Implement InterfaceReviewInspector**

Use `CaseReviewFinding[]`, the existing review summary counts, `reviewing`, and `reviewAttempted` as props. Provide `review`, `supplementMissing`, `locateFinding`, and `ignoreFinding` events. `missing_case_type` findings show “补充缺失用例”; assertion/expected-result findings show “定位用例” so the existing edit path remains the correction mechanism. Ignoring a finding only affects local presentation and must not mutate generated cases.

- [ ] **Step 5: Implement InterfaceCaseDeliveryStage**

Use props `{ generatedCount, reviewAttempted, reviewFindingCount, saving }`. Render one unframed delivery section with a summary, non-blocking warning, primary save command, and secondary transfer/export commands. Emit `save`, `transfer`, and `export`; do not call repositories inside the component.

- [ ] **Step 6: Run focused tests**

Run:

```bash
cd apps/web
npm test -- --run src/components/workflow/InterfaceReviewInspector.test.ts src/components/workflow/InterfaceCaseDeliveryStage.test.ts
```

Expected: PASS.

- [ ] **Step 7: Commit Task 4**

```bash
git add apps/web/src/components/workflow/InterfaceReviewInspector.vue apps/web/src/components/workflow/InterfaceReviewInspector.test.ts apps/web/src/components/workflow/InterfaceCaseDeliveryStage.vue apps/web/src/components/workflow/InterfaceCaseDeliveryStage.test.ts
git commit -m "feat: add interface review and delivery surfaces"
```

---

### Task 5: Replace The Legacy Interface Workspace In App.vue

**Files:**

- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/appShellStructure.test.ts`

- [ ] **Step 1: Replace legacy structure assertions with failing Studio Canvas assertions**

Update `appShellStructure.test.ts` so the interface block must contain:

```ts
expect(interfaceBlock).toContain('class="studio-interface-case-canvas"')
expect(interfaceBlock).toContain('<WorkflowHeader')
expect(interfaceBlock).toContain('<InterfaceCaseTable')
expect(appSource).toContain('<InterfaceCaseContextPane')
expect(appSource).toContain('<InterfaceReviewInspector')
expect(interfaceBlock).toContain('<InterfaceCaseDeliveryStage')
expect(interfaceBlock).not.toContain('class="topbar"')
expect(interfaceBlock).not.toContain('class="control-strip"')
expect(interfaceBlock).not.toContain('class="main-grid"')
expect(interfaceBlock).not.toContain('class="interface-upload-panel"')
```

Also assert `WorkflowHeader` is imported once and shared by both `generationKind` branches.

- [ ] **Step 2: Run the structure test and verify it fails**

Run:

```bash
cd apps/web
npm test -- --run src/appShellStructure.test.ts
```

Expected: FAIL because the legacy interface `topbar`, `control-strip`, and `main-grid` still exist.

- [ ] **Step 3: Add interface workflow state and computed values**

Import the four components and interface workflow model. Add:

```ts
const activeInterfaceCaseStage = ref<InterfaceCaseStage>('import')
const interfaceReviewAttempted = ref(false)
const ignoredInterfaceReviewFindingIds = ref<string[]>([])
const selectedInterfaceCaseIds = ref<string[]>([])
const interfaceReviewStatusFilter = ref('ALL')

const interfaceCaseWorkflow = computed(() => buildInterfaceCaseWorkflowView({
  requestedStage: activeInterfaceCaseStage.value,
  importedSourceCount: importedProjects.value.length,
  parsedOperationCount: parsed.value?.operations.length || 0,
  selectedOperationCount: selectedOperationIds.value.length,
  generatedCaseCount: generatedCases.value.length,
  reviewAttempted: interfaceReviewAttempted.value,
  saving: saveCasesLoading.value
}))
```

Derive the visible interface findings by excluding ignored IDs. Reset review attempt, ignored findings, and selected generated IDs whenever `generateCases` replaces the generated collection.

- [ ] **Step 4: Add typed interface stage handlers that call existing business actions**

Implement:

```ts
const navigateInterfaceCaseStage = (stage: string) => {
  if (['import', 'generate', 'review', 'deliver'].includes(stage)) {
    activeInterfaceCaseStage.value = stage as InterfaceCaseStage
  }
}

const handleInterfacePrimaryAction = async () => {
  const stage = interfaceCaseWorkflow.value.activeStage
  if (stage === 'import') {
    if (importedProjects.value.length === 0) await pickOpenApiFiles()
    else activeInterfaceCaseStage.value = 'generate'
    return
  }
  if (stage === 'generate') {
    generateCases()
    if (generatedCases.value.length > 0) activeInterfaceCaseStage.value = 'review'
    return
  }
  if (stage === 'review') {
    if (!interfaceReviewAttempted.value) {
      runAiReview()
      interfaceReviewAttempted.value = true
    } else {
      activeInterfaceCaseStage.value = 'deliver'
    }
    return
  }
  await saveCases()
}
```

Keep `saveCases`, `transferGeneratedCasesToAutomation`, `exportCsv`, `moveGeneratedCase`, `openEdit`, `deleteCase`, and `supplementMissingCases` unchanged except for UI-state resets after successful completion.

- [ ] **Step 5: Route desktop context and inspector through AppShell slots**

Before the generic `resource-pane`, render `InterfaceCaseContextPane` when `activeNav === 'api' && generationKind === 'interface'`. Before the Web `AiReviewInspector`, render `InterfaceReviewInspector` for the interface review stage.

Update visibility rules:

```ts
const interfaceContextVisible = computed(() =>
  activeNav.value === 'api' && generationKind.value === 'interface'
)
const interfaceInspectorVisible = computed(() =>
  interfaceContextVisible.value && interfaceCaseWorkflow.value.activeStage === 'review'
)
const inlineContextVisible = computed(() =>
  shellContextVisible.value && !(isCompactDesktop.value && (caseDesignContextVisible.value || interfaceContextVisible.value))
)
const inlineInspectorVisible = computed(() =>
  !isCompactDesktop.value && (shellInspectorVisible.value || interfaceInspectorVisible.value)
)
```

At compact widths, open the same `ResponsiveDrawer` component from explicit `接口与配置` and `AI 评审` buttons.

- [ ] **Step 6: Replace the entire legacy interface template branch**

The replacement shell is:

```vue
<section v-if="generationKind === 'interface'" class="studio-interface-case-canvas">
  <WorkflowHeader
    :title="interfaceCaseTaskTitle"
    :save-label="interfaceCaseSaveLabel"
    :stages="interfaceCaseWorkflow.stages"
    :primary-action="interfaceCaseWorkflow.primaryAction"
    :secondary-action="interfaceCaseSecondaryAction"
    :mode-action="{ label: 'Web 用例', disabled: saveCasesLoading }"
    @navigate-stage="navigateInterfaceCaseStage"
    @primary="handleInterfacePrimaryAction"
    @secondary="startNewInterfaceCaseTask"
    @mode-action="generationKind = 'web_automation'"
  />
  <div class="studio-interface-case-canvas__stage">
    <WorkflowStateView
      v-if="interfaceCaseWorkflow.activeStage === 'import'"
      variant="empty"
      title="导入 OpenAPI 开始接口用例设计"
      description="导入后可筛选接口并配置生成策略"
      action-label="导入 OpenAPI"
      @action="pickOpenApiFiles"
    />
    <InterfaceCaseTable
      v-else-if="['generate', 'review'].includes(interfaceCaseWorkflow.activeStage) && generatedCases.length"
      :cases="filteredCases"
      :visible-fields="visibleInterfaceFields"
      :selected-ids="selectedInterfaceCaseIds"
      :keyword="previewFilter.keyword"
      :module="previewFilter.module"
      :case-type="previewFilter.caseType"
      :priority="previewFilter.priority"
      :review-status="interfaceReviewStatusFilter"
      @selection-change="selectedInterfaceCaseIds = $event"
      @activate="selectedCase = $event"
      @move="moveGeneratedCase"
      @edit="openEdit"
      @remove="deleteCase"
      @bulk-review="runAiReview"
    />
    <WorkflowStateView
      v-else-if="interfaceCaseWorkflow.activeStage === 'generate'"
      variant="empty"
      title="选择接口并生成用例"
      description="使用左侧接口与生成配置选择范围"
    />
    <InterfaceCaseDeliveryStage
      v-else
      :generated-count="generatedCases.length"
      :review-attempted="interfaceReviewAttempted"
      :review-finding-count="visibleInterfaceReviewFindings.length"
      :saving="saveCasesLoading"
      @save="saveCases"
      @transfer="transferGeneratedCasesToAutomation"
      @export="exportCsv"
    />
  </div>
</section>
```

Render `WorkflowStateView` for first-use and filtered-empty states. Render `InterfaceCaseTable` during generate/review when cases exist. Render `InterfaceCaseDeliveryStage` only for `deliver`. Do not retain the legacy `topbar`, `control-strip`, `main-grid`, `operation-panel`, inline `review-panel`, or `interface-upload-panel` markup.

- [ ] **Step 7: Run structure and component tests**

Run:

```bash
cd apps/web
npm test -- --run src/appShellStructure.test.ts src/domain/interfaceCaseWorkflow.test.ts src/components/workflow/InterfaceCaseContextPane.test.ts src/components/workflow/InterfaceCaseTable.test.ts src/components/workflow/InterfaceReviewInspector.test.ts src/components/workflow/InterfaceCaseDeliveryStage.test.ts
```

Expected: all files PASS.

- [ ] **Step 8: Commit Task 5**

```bash
git add apps/web/src/App.vue apps/web/src/appShellStructure.test.ts
git commit -m "feat: migrate interface cases to Studio Canvas"
```

---

### Task 6: Apply The Approved Interface Canvas And Responsive Styling

**Files:**

- Modify: `apps/web/src/styles/studio-canvas.css`
- Test: `apps/web/src/appShellStructure.test.ts`

- [ ] **Step 1: Add failing structural style assertions**

Load `studio-canvas.css` in the structure test and assert it defines:

```ts
expect(studioStyles).toContain('.studio-interface-case-canvas')
expect(studioStyles).toContain('.studio-interface-case-toolbar')
expect(studioStyles).toContain('.studio-interface-case-table')
expect(studioStyles).toContain('.studio-interface-review-inspector')
expect(studioStyles).toContain('@media (max-width: 1100px)')
```

- [ ] **Step 2: Run the structure test and verify it fails**

Run:

```bash
cd apps/web
npm test -- --run src/appShellStructure.test.ts
```

Expected: FAIL on the new interface selectors.

- [ ] **Step 3: Add the interface canvas styles using semantic tokens**

Implement these layout rules:

```css
.studio-interface-case-canvas {
  display: grid;
  min-width: 0;
  min-height: 0;
  height: 100%;
  grid-template-rows: 70px minmax(0, 1fr);
  background: var(--surface-page);
}

.studio-interface-case-canvas > .studio-workflow-header {
  border: 0;
  border-bottom: 1px solid var(--border-default);
  border-radius: 0;
}

.studio-interface-case-canvas__stage {
  min-width: 0;
  min-height: 0;
  padding: 14px 16px 16px;
  overflow: hidden;
}
```

Reuse the Web header selectors rather than duplicating stage colors. Use `var(--brand-primary)` and related semantic tokens for primary/AI states; use `var(--color-success)`, `var(--color-warning)`, and `var(--color-danger)` for status.

- [ ] **Step 4: Style the context, toolbar, table, inspector, and delivery surfaces**

Match the committed PNGs:

- Context width remains the AppShell `--context-panel-width` (`220px`).
- Inspector uses `--inspector-panel-width` (`300px`).
- Main results surface has one border, `6px` to `8px` radius, and no nested card shadows.
- Toolbar is fixed-height, horizontally scrollable when needed, and uses compact Element Plus controls.
- Table fills remaining height; the right operation column stays visible.
- Typography uses existing `--font-size-*` tokens and weights from 400 through 620.

- [ ] **Step 5: Add compact and narrow behavior**

At `max-width: 1100px`, keep the WorkflowHeader responsive behavior already used by Web, hide inline interface context/inspector through AppShell state, show drawer triggers, and keep the table horizontally scrollable. At `max-width: 900px`, do not reduce table text below the base table size; preserve commands through drawers and the horizontally scrolling stepper.

- [ ] **Step 6: Run structure tests and production build**

Run:

```bash
cd apps/web
npm test -- --run src/appShellStructure.test.ts
npm run build
```

Expected: structure test PASS and production build exits 0 with no TypeScript errors.

- [ ] **Step 7: Commit Task 6**

```bash
git add apps/web/src/styles/studio-canvas.css apps/web/src/appShellStructure.test.ts
git commit -m "style: match interface Studio Canvas design"
```

---

### Task 7: Add Interface Workflow Browser And Accessibility Coverage

**Files:**

- Modify: `apps/web/e2e/studio-canvas.spec.ts`
- Use fixture: `<workspace>/studio-canvas-ui-redesign/Haitlify.openapi.json`

- [ ] **Step 1: Replace the legacy interface test with a new Studio Canvas entry test**

Change the test named `legacy interface workspace keeps its resource-pane styling after cleanup` into `interface workflow uses the shared Studio Canvas header and context pane`. Seed `generationKind: 'interface'`, open `/api`, and assert:

```ts
await expect(page.locator('.studio-interface-case-canvas')).toBeVisible()
await expect(page.locator('.studio-case-design-canvas')).toHaveCount(0)
await expect(page.getByRole('navigation', { name: '测试设计流程' })).toBeVisible()
await expect(page.getByRole('complementary', { name: '接口与生成配置' })).toBeVisible()
await expect(page.locator('.topbar, .control-strip, .main-grid')).toHaveCount(0)
```

- [ ] **Step 2: Add an OpenAPI import and four-stage journey**

Use the hidden input with `setInputFiles('../../Haitlify.openapi.json')`, then:

1. Confirm at least 100 parsed operations appear.
2. Navigate to `配置生成`.
3. Select filtered operations and click `生成用例`.
4. Assert the toolbar and fixed operation column appear.
5. Navigate to `AI 评审`, run the review, and assert the interface inspector appears.
6. Navigate to `保存 / 交付` and assert save, transfer, and export remain enabled.

Do not execute save or transfer in the visual test; backend behavior already has focused unit coverage and the browser test should not destroy its fixture state.

- [ ] **Step 3: Add desktop visual baselines**

Capture these screenshots at the fixed date and mocked backend used by the existing spec:

```ts
await expect(page).toHaveScreenshot('interface-import-1440.png')
await expect(page).toHaveScreenshot('interface-generate-table-1280.png')
await expect(page).toHaveScreenshot('interface-review-inspector-1440.png')
await expect(page).toHaveScreenshot('interface-delivery-1280.png')
```

Compare each image manually against `02-information-architecture.png` and `03-stage-behavior.png` before accepting the baseline.

- [ ] **Step 4: Add compact drawer and table-overflow coverage**

At `1024x768`, assert the inline context and inspector are absent, open `接口与配置` and `AI 评审` drawers, press Escape, and verify focus returns to each trigger. Scroll the interface table body horizontally and capture `interface-compact-drawers-1024.png` and `interface-compact-table-1024.png`.

- [ ] **Step 5: Add keyboard and axe assertions**

Use `expectNoAxeViolations` on `.studio-interface-case-canvas`, both open dialogs, and the review stage. Focus and activate the stage buttons plus `上移`, `下移`, `编辑`, and `删除` action buttons by keyboard; verify case-specific accessible names.

- [ ] **Step 6: Run only the interface browser tests and inspect screenshots**

Run:

```bash
cd apps/web
npm run test:e2e -- --grep "interface"
```

Expected: all interface tests PASS; new baseline PNGs are nonblank and have no overlap, clipped action labels, or missing drawers.

- [ ] **Step 7: Commit Task 7**

```bash
git add apps/web/e2e/studio-canvas.spec.ts apps/web/e2e/studio-canvas.spec.ts-snapshots
git commit -m "test: verify interface Studio Canvas workflow"
```

---

### Task 8: Run Full Regression And Confirm Design Fidelity

**Files:**

- Verify all modified files.
- Reference: `docs/superpowers/design-assets/interface-case/README.md`

- [ ] **Step 1: Run the full Vitest suite**

Run:

```bash
cd apps/web
npm test -- --run
```

Expected: all test files and tests PASS with no skipped interface workflow tests.

- [ ] **Step 2: Run the production build separately**

Run:

```bash
cd apps/web
npm run build
```

Expected: `vue-tsc --noEmit` and Vite production build both exit 0.

- [ ] **Step 3: Run the complete Playwright suite**

Run:

```bash
cd apps/web
npm run test:e2e
```

Expected: all Web and interface Studio Canvas scenarios PASS, including axe checks and screenshot comparisons.

- [ ] **Step 4: Perform the visual comparison checklist**

Open implementation screenshots beside the committed design PNGs and verify:

- The interface stepper uses the same WorkflowHeader top-line treatment as Web.
- The result toolbar and fixed operation column are present.
- Deep blue `#2854A6` comes from `--brand-primary`.
- The desktop composition is context + central table + review inspector.
- The 1024px composition uses drawers without removing the table toolbar/actions.
- Fonts remain soft, labels fit, and no card-inside-card treatment appears.

- [ ] **Step 5: Check the worktree and final diff**

Run:

```bash
git status --short
git diff --check HEAD~7..HEAD
```

Expected: no uncommitted source changes and no whitespace errors. Generated Playwright artifacts outside committed baselines must not be staged.

- [ ] **Step 6: Commit any final test-only baseline update**

Only if baseline metadata changed after the full run:

```bash
git add apps/web/e2e/studio-canvas.spec.ts-snapshots
git commit -m "test: refresh interface Studio Canvas baselines"
```

If no files changed, do not create an empty commit.
