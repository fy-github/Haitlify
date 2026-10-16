> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Web Automation Studio Workbench Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current generic Web automation page and duplicated execution drawer with the approved Studio Canvas workbench, explicit selected-or-filtered execution scope, readiness preflight, and a recoverable full-canvas live monitor.

**Architecture:** Add one pure display adapter for execution scope and preflight, then compose focused Vue components under `components/automation`. `App.vue` remains the only orchestration owner for local repositories, environment persistence, run creation, polling, reports, defects, URL state, and session recovery; workbench and monitor consume one active snapshot and one poller.

**Tech Stack:** Vue 3 `<script setup>`, TypeScript, Element Plus, Lucide Vue, CSS custom properties from Studio Canvas, Vitest + Vue Test Utils, Playwright, axe-core.

---

## File Map

- Create `apps/web/src/domain/webAutomationWorkspaceView.ts`: pure effective-scope, module-count, preflight, and run-summary display adapters.
- Create `apps/web/src/domain/webAutomationWorkspaceView.test.ts`: scope precedence, preflight blocking, browser capability, and count-consistency tests.
- Create `apps/web/src/domain/webAutomationRunSession.ts`: active-run session key, monitor query parsing, and single-poller coordination.
- Create `apps/web/src/domain/webAutomationRunSession.test.ts`: recovery precedence, stale query cleanup, and duplicate-poller prevention.
- Create `apps/web/src/components/automation/WebAutomationTaskHeader.vue`: literal page title, compact metadata, history/generate commands, and overflow export command.
- Create `apps/web/src/components/automation/WebAutomationTaskHeader.test.ts`: header labels and emitted command contract.
- Create `apps/web/src/components/automation/WebRunPreparationBar.vue`: environment, browser, repeat count, login readiness, responsive-pane commands, and primary execution action.
- Create `apps/web/src/components/automation/WebRunPreparationBar.test.ts`: controlled inputs, disabled reason, and emitted command contract.
- Create `apps/web/src/components/automation/WebAutomationScopePane.vue`: single project selector and module-count scope.
- Create `apps/web/src/components/automation/WebAutomationScopePane.test.ts`: controlled project/module selection and count rendering.
- Create `apps/web/src/components/automation/WebAutomationCaseTable.vue`: table-local filters, controlled row selection, execution readiness, and compact row actions.
- Create `apps/web/src/components/automation/WebAutomationCaseTable.test.ts`: selection, bulk selection, filtering events, columns, empty states, and row actions.
- Create `apps/web/src/components/automation/WebRunPreflightPanel.vue`: idle readiness checks, blocking explanations, and local repair events.
- Create `apps/web/src/components/automation/WebRunPreflightPanel.test.ts`: status text/icon contract and repair events.
- Create `apps/web/src/components/automation/WebRunMonitorSummary.vue`: active or terminal run progress, screenshot thumbnail, current step, diagnostics, and monitor entry.
- Create `apps/web/src/components/automation/WebRunMonitorSummary.test.ts`: shared-snapshot rendering and monitor entry behavior.
- Create `apps/web/src/components/automation/WebRunMonitorView.vue`: full-canvas live screenshot, timeline, diagnostics, summary counts, and run controls.
- Create `apps/web/src/components/automation/WebRunMonitorView.test.ts`: lifecycle controls, waiting states, timeline phases, and return/rerun commands.
- Create `apps/web/src/components/automation/WebAutomationWorkspace.vue`: workbench composition, responsive drawers, table-scroll preservation hooks, and contextual-pane switching.
- Create `apps/web/src/components/automation/WebAutomationWorkspace.test.ts`: workbench composition and compact drawer behavior.
- Modify `apps/web/src/App.vue`: add controlled Web selection, hide the outer context pane for Web automation, replace old Web markup, integrate query/session recovery, and remove both duplicate run drawers.
- Modify `apps/web/src/appShellStructure.test.ts`: protect the Web workspace boundary and removal of legacy drawer/switch/metric markup.
- Modify `apps/web/src/automationExecutionLayout.test.ts`: retain API behavior and assert the Web path delegates to the new workspace.
- Modify `apps/web/src/styles/studio-canvas.css`: workbench and full-monitor layouts, responsive breakpoints, focus, reduced motion, and increased contrast.
- Create `apps/web/e2e/fixtures/webAutomationWorkspace.ts`: deterministic cases, environments, capabilities, run snapshots, and API routing.
- Reuse `docs/superpowers/design-assets/web-automation/03-final-workbench-detail.png` as the deterministic nonblank browser screenshot response in Playwright fixtures.
- Modify `apps/web/e2e/studio-canvas.spec.ts`: workbench, scope, preflight, monitoring, recovery, responsive, keyboard, and accessibility journeys.
- Modify `apps/web/e2e/studio-canvas.spec.ts-snapshots/`: approved Web automation screenshots only; preserve unrelated Studio Canvas snapshots.
- Create `docs/superpowers/design-assets/web-automation/04-implemented-workbench-1440.png`: final wide implementation reference.
- Create `docs/superpowers/design-assets/web-automation/05-implemented-monitor-1440.png`: final full-monitor implementation reference.

## Guardrails

- Do not change Runner or Spring HTTP contracts.
- Do not redesign API automation, reports, defects, environments, case library, sidebar, or global shell.
- Preserve all pre-existing uncommitted Studio Canvas changes and snapshot files.
- Keep `App.vue` as orchestration owner; presentational components must not call repositories or mutate storage.
- Use one effective-scope selector for the execution payload, primary button label, and preflight case count.
- Use one polling loop across workbench and monitor views.
- Use `var(--brand-primary)` for primary commands, `var(--accent-teal)` for readiness, and `var(--accent-amber)` for warnings; do not introduce a purple primary color.

---

### Task 1: Effective Scope And Preflight Adapter

**Files:**

- Create: `apps/web/src/domain/webAutomationWorkspaceView.ts`
- Create: `apps/web/src/domain/webAutomationWorkspaceView.test.ts`

- [ ] **Step 1: Write the failing adapter tests**

Create fixtures with two filtered cases, one hidden selected ID, one selected visible ID, an available Chromium capability, and a valid Web environment. Assert the exact public behavior:

```ts
import { describe, expect, it } from 'vitest'
import type { SavedWebAutomationRunCase } from './webAutomationRepository'
import {
  buildWebAutomationPreflight,
  buildWebAutomationScope,
  collectWebAutomationModuleCounts
} from './webAutomationWorkspaceView'

const cases = [
  { id: 'WEB-1', project: '订单', module: '退款', browser: 'chromium', steps: [{ action: 'goto' }] },
  { id: 'WEB-2', project: '订单', module: '查询', browser: 'chromium', steps: [{ action: 'click' }] }
] as SavedWebAutomationRunCase[]

describe('webAutomationWorkspaceView', () => {
  it('uses selected visible rows before filtered rows and keeps every count aligned', () => {
    const selected = buildWebAutomationScope(cases, ['WEB-hidden', 'WEB-2'])
    expect(selected.source).toBe('selected')
    expect(selected.caseIds).toEqual(['WEB-2'])
    expect(selected.actionLabel).toBe('执行选中 1 条')

    const filtered = buildWebAutomationScope(cases, [])
    expect(filtered.source).toBe('filtered')
    expect(filtered.caseIds).toEqual(['WEB-1', 'WEB-2'])
    expect(filtered.actionLabel).toBe('执行当前 2 条')
  })

  it('disables empty scope with an explicit reason', () => {
    expect(buildWebAutomationScope([], ['WEB-hidden'])).toMatchObject({
      count: 0,
      canExecute: false,
      actionLabel: '执行当前 0 条',
      blockingReason: '当前筛选结果中没有可执行用例'
    })
  })

  it('maps runner, browser, environment, login, teardown and executable checks', () => {
    const scope = buildWebAutomationScope(cases, [])
    const preflight = buildWebAutomationPreflight({
      scope,
      capabilities: {
        status: 'UP',
        checkedAt: '2026-07-27T09:00:00.000Z',
        browsers: [{ id: 'chromium', label: 'Chromium', available: true, recommendation: '' }]
      },
      browserOverride: 'follow-case',
      environmentName: '测试环境',
      environmentValidation: { valid: true },
      login: { enabled: false, valid: true },
      logout: { enabled: false, valid: true }
    })

    expect(preflight.canExecute).toBe(true)
    expect(preflight.effectiveCount).toBe(2)
    expect(preflight.items.map((item) => item.id)).toEqual([
      'runner', 'browser', 'environment', 'login', 'teardown', 'cases'
    ])
    expect(preflight.items.every((item) => item.status === 'ready')).toBe(true)
  })

  it('blocks unavailable browsers, invalid environments and manual steps', () => {
    const manualCases = [{ ...cases[0], steps: [{ action: 'manual' }] }] as SavedWebAutomationRunCase[]
    const preflight = buildWebAutomationPreflight({
      scope: buildWebAutomationScope(manualCases, []),
      capabilities: {
        status: 'DEGRADED',
        checkedAt: '2026-07-27T09:00:00.000Z',
        browsers: [{ id: 'chromium', label: 'Chromium', available: false, recommendation: '安装 Chromium' }]
      },
      browserOverride: 'follow-case',
      environmentName: '测试环境',
      environmentValidation: { valid: false, message: '请输入 Web 应用地址' },
      login: { enabled: true, valid: false, message: '登录配置不完整' },
      logout: { enabled: true, valid: true }
    })

    expect(preflight.canExecute).toBe(false)
    expect(preflight.blockingReason).toBe('安装 Chromium')
    expect(preflight.items.find((item) => item.id === 'cases')?.detail).toContain('1 条不可执行')
  })

  it('collects module counts for the active project', () => {
    expect(collectWebAutomationModuleCounts(cases)).toEqual([
      { value: 'ALL', label: '全部模块', count: 2 },
      { value: '退款', label: '退款', count: 1 },
      { value: '查询', label: '查询', count: 1 }
    ])
  })
})
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd apps/web
npm test -- --run src/domain/webAutomationWorkspaceView.test.ts
```

Expected: FAIL because `webAutomationWorkspaceView.ts` does not exist.

- [ ] **Step 3: Implement the pure adapter**

Export these exact types and functions:

```ts
import type { EnvironmentValidationResult } from './executionEnvironmentRepository'
import type { WebAutomationCapabilities } from './webAutomationRunRepository'
import type { SavedWebAutomationRunCase } from './webAutomationRepository'

export type WebAutomationScopeSource = 'selected' | 'filtered'
export type WebPreflightStatus = 'ready' | 'warning' | 'blocked'
export type WebPreflightItemId = 'runner' | 'browser' | 'environment' | 'login' | 'teardown' | 'cases'

export interface WebAutomationEffectiveScope {
  source: WebAutomationScopeSource
  cases: SavedWebAutomationRunCase[]
  caseIds: string[]
  count: number
  actionLabel: string
  canExecute: boolean
  blockingReason: string
}

export interface WebPreflightItem {
  id: WebPreflightItemId
  label: string
  status: WebPreflightStatus
  detail: string
  repairAction?: 'retry-capabilities' | 'edit-environment' | 'edit-login' | 'clear-filters'
}

export interface WebAutomationPreflight {
  items: WebPreflightItem[]
  effectiveCount: number
  canExecute: boolean
  blockingReason: string
}

export interface WebAutomationPreflightInput {
  scope: WebAutomationEffectiveScope
  capabilities?: WebAutomationCapabilities
  browserOverride: string
  environmentName: string
  environmentValidation: EnvironmentValidationResult
  login: { enabled: boolean; valid: boolean; message?: string }
  logout: { enabled: boolean; valid: boolean; message?: string }
}

export const buildWebAutomationScope = (
  filteredCases: SavedWebAutomationRunCase[],
  selectedIds: string[]
): WebAutomationEffectiveScope => {
  const visibleIds = new Set(filteredCases.map((item) => item.id))
  const selectedVisible = new Set(selectedIds.filter((id) => visibleIds.has(id)))
  const source: WebAutomationScopeSource = selectedVisible.size > 0 ? 'selected' : 'filtered'
  const cases = source === 'selected'
    ? filteredCases.filter((item) => selectedVisible.has(item.id))
    : filteredCases
  const count = cases.length
  return {
    source,
    cases,
    caseIds: cases.map((item) => item.id),
    count,
    actionLabel: source === 'selected' ? `执行选中 ${count} 条` : `执行当前 ${count} 条`,
    canExecute: count > 0,
    blockingReason: count > 0 ? '' : '当前筛选结果中没有可执行用例'
  }
}

export const collectWebAutomationModuleCounts = (cases: SavedWebAutomationRunCase[]) => {
  const counts = new Map<string, number>()
  cases.forEach((item) => counts.set(item.module, (counts.get(item.module) || 0) + 1))
  return [
    { value: 'ALL', label: '全部模块', count: cases.length },
    ...Array.from(counts, ([value, count]) => ({ value, label: value, count }))
  ]
}
```

Complete the file with this fixed-order preflight implementation:

```ts
const stateLabel = (enabled: boolean, ready: string, skipped: string) => enabled ? ready : skipped

export const buildWebAutomationPreflight = (
  input: WebAutomationPreflightInput
): WebAutomationPreflight => {
  const capabilitiesById = new Map(
    (input.capabilities?.browsers || []).map((browser) => [browser.id, browser])
  )
  const requiredBrowsers = input.browserOverride === 'follow-case'
    ? Array.from(new Set(input.scope.cases.map((item) => item.browser)))
    : [input.browserOverride]
  const unavailableBrowser = requiredBrowsers
    .map((id) => capabilitiesById.get(id))
    .find((browser) => !browser?.available)
  const manualCaseCount = input.scope.cases.filter((item) =>
    item.steps.some((step) => step.action === 'manual')
  ).length

  const items: WebPreflightItem[] = [
    input.capabilities
      ? {
          id: 'runner',
          label: 'Runner 服务',
          status: input.capabilities.status === 'UP' ? 'ready' : 'warning',
          detail: input.capabilities.status === 'UP' ? 'Runner 已连接' : 'Runner 部分能力不可用',
          repairAction: input.capabilities.status === 'UP' ? undefined : 'retry-capabilities'
        }
      : {
          id: 'runner',
          label: 'Runner 服务',
          status: 'blocked',
          detail: 'Runner 未连接',
          repairAction: 'retry-capabilities'
        },
    unavailableBrowser
      ? {
          id: 'browser',
          label: '浏览器能力',
          status: 'blocked',
          detail: unavailableBrowser.recommendation || `${unavailableBrowser.label} 不可用`,
          repairAction: 'retry-capabilities'
        }
      : {
          id: 'browser',
          label: '浏览器能力',
          status: 'ready',
          detail: requiredBrowsers.length ? `${requiredBrowsers.join('、')} 可用` : '跟随用例配置'
        },
    input.environmentValidation.valid
      ? {
          id: 'environment',
          label: '执行环境',
          status: 'ready',
          detail: `${input.environmentName} 已就绪`
        }
      : {
          id: 'environment',
          label: '执行环境',
          status: 'blocked',
          detail: input.environmentValidation.message || '执行环境无效',
          repairAction: 'edit-environment'
        },
    input.login.valid
      ? {
          id: 'login',
          label: '自动登录',
          status: 'ready',
          detail: stateLabel(input.login.enabled, '登录配置已就绪', '无需自动登录')
        }
      : {
          id: 'login',
          label: '自动登录',
          status: 'blocked',
          detail: input.login.message || '登录配置不完整',
          repairAction: 'edit-login'
        },
    input.logout.valid
      ? {
          id: 'teardown',
          label: '环境清理',
          status: 'ready',
          detail: stateLabel(input.logout.enabled, '退出步骤已就绪', '无需自动清理')
        }
      : {
          id: 'teardown',
          label: '环境清理',
          status: 'blocked',
          detail: input.logout.message || '退出步骤不完整',
          repairAction: 'edit-environment'
        },
    input.scope.count === 0
      ? {
          id: 'cases',
          label: '执行用例',
          status: 'blocked',
          detail: input.scope.blockingReason,
          repairAction: 'clear-filters'
        }
      : manualCaseCount > 0
        ? {
            id: 'cases',
            label: '执行用例',
            status: 'blocked',
            detail: `${manualCaseCount} 条不可执行，请先补全自动化步骤`
          }
        : {
            id: 'cases',
            label: '执行用例',
            status: 'ready',
            detail: `${input.scope.count} 条用例可执行`
          }
  ]
  const firstBlocked = items.find((item) => item.status === 'blocked')
  return {
    items,
    effectiveCount: input.scope.count,
    canExecute: input.scope.canExecute && !firstBlocked,
    blockingReason: firstBlocked?.detail || ''
  }
}
```

A missing capability response is blocked with `Runner 未连接`; `DEGRADED` is a nonblocking warning only when every required browser is available. The override browser wins unless it is `follow-case`, in which case every distinct case browser is verified. A case with a `manual` step blocks the whole explicit scope so the action label, preflight count, and eventual payload never disagree.

- [ ] **Step 4: Run the focused test and type check**

Run:

```bash
cd apps/web
npm test -- --run src/domain/webAutomationWorkspaceView.test.ts
npm run build
```

Expected: focused Vitest PASS and `vue-tsc --noEmit` plus Vite build exit 0.

- [ ] **Step 5: Commit Task 1**

```bash
git add apps/web/src/domain/webAutomationWorkspaceView.ts \
  apps/web/src/domain/webAutomationWorkspaceView.test.ts
git commit -m "feat: derive web automation execution readiness"
```

---

### Task 2: Task Header And Execution Preparation Bar

**Files:**

- Create: `apps/web/src/components/automation/WebAutomationTaskHeader.vue`
- Create: `apps/web/src/components/automation/WebAutomationTaskHeader.test.ts`
- Create: `apps/web/src/components/automation/WebRunPreparationBar.vue`
- Create: `apps/web/src/components/automation/WebRunPreparationBar.test.ts`

- [ ] **Step 1: Write failing component contract tests**

Mount the two missing components with controlled props. For the header, assert literal `Web 自动化`, `12 条可执行`, `3 个模块`, latest-run text, and emissions for `openHistory`, `generateCases`, and `exportCases`. For the preparation bar, assert environment/browser/repeat values, `登录态已就绪`, primary label `执行选中 3 条`, disabled reason text, and emissions for `updateEnvironmentId`, `updateBrowser`, `updateLoopCount`, `editEnvironment`, `openScope`, `openContext`, and `execute`:

```ts
await wrapper.get('[aria-label="Web 执行环境"]').setValue('web-env-2')
await wrapper.get('[aria-label="浏览器选择"]').setValue('firefox')
await wrapper.get('[aria-label="重复次数"]').setValue(2)
await wrapper.get('[data-testid="execute-web-automation"]').trigger('click')

expect(wrapper.emitted('updateEnvironmentId')).toEqual([['web-env-2']])
expect(wrapper.emitted('updateBrowser')).toEqual([['firefox']])
expect(wrapper.emitted('updateLoopCount')).toEqual([[2]])
expect(wrapper.emitted('execute')).toEqual([[]])
```

Add a blocked-state assertion:

```ts
expect(wrapper.get('[data-testid="execute-web-automation"]').attributes('disabled')).toBeDefined()
expect(wrapper.get('[data-testid="web-execution-blocking-reason"]').text()).toBe('请输入 Web 应用地址')
```

- [ ] **Step 2: Run both tests and verify RED**

Run:

```bash
cd apps/web
npm test -- --run \
  src/components/automation/WebAutomationTaskHeader.test.ts \
  src/components/automation/WebRunPreparationBar.test.ts
```

Expected: FAIL because both components are missing.

- [ ] **Step 3: Implement `WebAutomationTaskHeader`**

Use this controlled contract:

```ts
defineProps<{
  executableCount: number
  moduleCount: number
  latestRunLabel: string
}>()

const emit = defineEmits<{
  openHistory: []
  generateCases: []
  exportCases: []
}>()
```

Render a single unframed header with `h1` text `Web 自动化`, one metadata line, text buttons for `执行历史` and `从用例库生成`, and an Element Plus dropdown triggered by a `MoreHorizontal` icon button with tooltip and `aria-label="更多操作"`. The dropdown contains one `导出用例` command. Do not render an eyebrow, breadcrumb, metric card, API/Web switch, or large hero heading.

- [ ] **Step 4: Implement `WebRunPreparationBar`**

Use this controlled contract:

```ts
import type { WebRunBrowserOption } from '../../domain/webAutomationRunView'

defineProps<{
  environmentId: string
  environments: Array<{ value: string; label: string }>
  browser: string
  browsers: WebRunBrowserOption[]
  loopCount: number
  loginReadyLabel: string
  actionLabel: string
  canExecute: boolean
  blockingReason: string
  compact: boolean
}>()

const emit = defineEmits<{
  updateEnvironmentId: [value: string]
  updateBrowser: [value: string]
  updateLoopCount: [value: number]
  editEnvironment: []
  openScope: []
  openContext: []
  execute: []
}>()
```

Render labelled Element Plus selects for environment and browser, an input-number constrained to `1..20`, a text-and-icon login readiness indicator, a settings icon button with tooltip, and the deep-blue primary action. When `compact` is true, include `筛选范围` and `执行检查` commands that open the responsive drawers. Put `blockingReason` in a persistent `aria-live="polite"` line adjacent to the action, and keep the action visible when controls wrap.

- [ ] **Step 5: Run tests, type check, and commit Task 2**

Run:

```bash
cd apps/web
npm test -- --run \
  src/components/automation/WebAutomationTaskHeader.test.ts \
  src/components/automation/WebRunPreparationBar.test.ts
npm run build
```

Expected: both component files PASS and the production build exits 0.

```bash
git add apps/web/src/components/automation/WebAutomationTaskHeader.vue \
  apps/web/src/components/automation/WebAutomationTaskHeader.test.ts \
  apps/web/src/components/automation/WebRunPreparationBar.vue \
  apps/web/src/components/automation/WebRunPreparationBar.test.ts
git commit -m "feat: add web automation task controls"
```

---

### Task 3: Controlled Scope Pane And Case Table

**Files:**

- Create: `apps/web/src/components/automation/WebAutomationScopePane.vue`
- Create: `apps/web/src/components/automation/WebAutomationScopePane.test.ts`
- Create: `apps/web/src/components/automation/WebAutomationCaseTable.vue`
- Create: `apps/web/src/components/automation/WebAutomationCaseTable.test.ts`

- [ ] **Step 1: Write failing scope-pane tests**

Mount with two projects and three module count rows. Assert the project appears once, module names and counts render, the active module has `aria-current="true"`, and controlled events carry only the requested values:

```ts
await wrapper.get('[aria-label="Web 自动化项目"]').setValue('支付平台')
await wrapper.get('[data-testid="web-scope-module-退款"]').trigger('click')

expect(wrapper.emitted('updateProject')).toEqual([['支付平台']])
expect(wrapper.emitted('updateModule')).toEqual([['退款']])
expect(wrapper.props('project')).toBe('订单服务')
expect(wrapper.props('module')).toBe('ALL')
```

- [ ] **Step 2: Write failing case-table tests**

Use three `SavedWebAutomationRunCase` fixtures and mount with one selected ID. Assert the exact default columns `选择`, `用例`, `页面`, `浏览器 / 分辨率`, `执行状态`, `操作`; assert that project and module controls do not exist in the table; and assert controlled selection/filter/action events:

```ts
await wrapper.get('[aria-label="选择 Web 用例 WEB-2"]').setValue(true)
await wrapper.get('[data-testid="select-filtered-web-cases"]').trigger('click')
await wrapper.get('[aria-label="Web 用例来源"]').setValue('design')
await wrapper.get('[aria-label="Web 用例状态"]').setValue('failed')
await wrapper.get('[aria-label="搜索 Web 用例"]').setValue('退款')
await wrapper.get('[aria-label="查看 Web 用例 WEB-1 日志"]').trigger('click')

expect(wrapper.emitted('toggleCase')).toEqual([[cases[1], true]])
expect(wrapper.emitted('selectFiltered')).toEqual([[]])
expect(wrapper.emitted('updateSource')).toEqual([['design']])
expect(wrapper.emitted('updateStatus')).toEqual([['failed']])
expect(wrapper.emitted('updateKeyword')).toEqual([['退款']])
expect(wrapper.emitted('openLog')).toEqual([[cases[0]]])
```

Add tests for:

```ts
expect(wrapper.text()).toContain('已选 1 条')
expect(wrapper.find('[aria-label="Web 自动化项目"]').exists()).toBe(false)
expect(wrapper.find('[aria-label="Web 自动化模块"]').exists()).toBe(false)
expect(wrapper.get('[data-testid="clear-web-case-selection"]').attributes('disabled')).toBeUndefined()
```

Mount `emptyKind="no-cases"` and assert one `从用例库生成` command. Mount `emptyKind="filtered"` and assert one `清除筛选` command while the filter controls remain present.

- [ ] **Step 3: Run both tests and verify RED**

Run:

```bash
cd apps/web
npm test -- --run \
  src/components/automation/WebAutomationScopePane.test.ts \
  src/components/automation/WebAutomationCaseTable.test.ts
```

Expected: FAIL because the components do not exist.

- [ ] **Step 4: Implement the scope pane**

Use this contract:

```ts
defineProps<{
  project: string
  projects: Array<{ value: string; label: string; count: number }>
  module: string
  modules: Array<{ value: string; label: string; count: number }>
}>()

const emit = defineEmits<{
  updateProject: [value: string]
  updateModule: [value: string]
}>()
```

Render a quiet un-nested surface with heading `执行范围`, one labelled project select, and module buttons with text plus numeric counts. Use `aria-current` for the selected module and tooltips only for truncated labels. Changing project remains an emitted intent; `App.vue` will reset the module after accepting it.

- [ ] **Step 5: Implement the controlled case table**

Use this public contract:

```ts
import type { SavedWebAutomationRunCase } from '../../domain/webAutomationRepository'
import type { WebAutomationRunStatus } from '../../domain/webAutomation'

export type WebCaseStatusFilter = 'ALL' | WebAutomationRunStatus | 'pending'

defineProps<{
  cases: SavedWebAutomationRunCase[]
  selectedIds: string[]
  source: 'ALL' | 'design' | 'default'
  status: WebCaseStatusFilter
  keyword: string
  resultStatusById: Record<string, WebAutomationRunStatus | 'pending'>
  emptyKind: 'none' | 'no-cases' | 'filtered'
}>()

const emit = defineEmits<{
  toggleCase: [testCase: SavedWebAutomationRunCase, checked: boolean]
  selectFiltered: []
  clearSelected: []
  updateSource: [value: 'ALL' | 'design' | 'default']
  updateStatus: [value: WebCaseStatusFilter]
  updateKeyword: [value: string]
  clearFilters: []
  generateCases: []
  openLog: [testCase: SavedWebAutomationRunCase]
  transferDefect: [testCase: SavedWebAutomationRunCase]
  deleteCase: [testCase: SavedWebAutomationRunCase]
}>()
```

Use native controlled checkboxes inside `el-table`, keyed by `id`. Combine ID and name in the primary case cell; put page and start URL in the page cell; put browser and viewport together; show status with text and icon as well as color. Keep log, defect, and delete as compact icon buttons using Lucide icons and tooltips. Show selection-only bulk commands in the toolbar and never place project/module filters there. Expose table scroll through the root `.web-automation-case-table__scroll` element so `App.vue` can record and restore `scrollTop`.

- [ ] **Step 6: Run tests, type check, and commit Task 3**

Run:

```bash
cd apps/web
npm test -- --run \
  src/components/automation/WebAutomationScopePane.test.ts \
  src/components/automation/WebAutomationCaseTable.test.ts \
  src/domain/webAutomationWorkspaceView.test.ts
npm run build
```

Expected: focused tests PASS and the production build exits 0.

```bash
git add apps/web/src/components/automation/WebAutomationScopePane.vue \
  apps/web/src/components/automation/WebAutomationScopePane.test.ts \
  apps/web/src/components/automation/WebAutomationCaseTable.vue \
  apps/web/src/components/automation/WebAutomationCaseTable.test.ts
git commit -m "feat: add web automation scope and case table"
```

---

### Task 4: Preflight And Monitor Summary

**Files:**

- Create: `apps/web/src/components/automation/WebRunPreflightPanel.vue`
- Create: `apps/web/src/components/automation/WebRunPreflightPanel.test.ts`
- Create: `apps/web/src/components/automation/WebRunMonitorSummary.vue`
- Create: `apps/web/src/components/automation/WebRunMonitorSummary.test.ts`

- [ ] **Step 1: Write failing preflight-panel tests**

Mount a `WebAutomationPreflight` containing ready, warning, and blocked items plus `message="上次执行已中断"`. Assert the visible notice, labels, text details, non-color status labels, accessible live summary, and exact local repair events:

```ts
await wrapper.get('[data-testid="repair-preflight-runner"]').trigger('click')
await wrapper.get('[data-testid="repair-preflight-environment"]').trigger('click')

expect(wrapper.emitted('retryCapabilities')).toEqual([[]])
expect(wrapper.emitted('editEnvironment')).toEqual([[]])
expect(wrapper.get('[aria-live="polite"]').text()).toContain('暂不可执行')
```

Assert every row contains a Lucide status icon with `aria-hidden="true"` and visible `已就绪`, `需注意`, or `需处理` text.

- [ ] **Step 2: Write failing monitor-summary tests**

Mount one running `WebAutomationRunSnapshot` and `buildWebRunView(snapshot)`. Assert run status, `1 / 4` progress, screenshot alt text, current case, current step, latest diagnostic, `正在重连`, and the `进入实时监控` emission. Mount a terminal snapshot and assert its summary stays visible and still allows monitor entry and rerun.

```ts
await wrapper.get('[data-testid="enter-web-run-monitor"]').trigger('click')
await wrapper.get('[data-testid="rerun-web-automation"]').trigger('click')
expect(wrapper.emitted('enterMonitor')).toEqual([[]])
expect(wrapper.emitted('rerun')).toEqual([[]])
```

- [ ] **Step 3: Run both tests and verify RED**

Run:

```bash
cd apps/web
npm test -- --run \
  src/components/automation/WebRunPreflightPanel.test.ts \
  src/components/automation/WebRunMonitorSummary.test.ts
```

Expected: FAIL because both components are missing.

- [ ] **Step 4: Implement the preflight panel**

Use this contract:

```ts
import type { WebAutomationPreflight } from '../../domain/webAutomationWorkspaceView'

defineProps<{
  preflight: WebAutomationPreflight
  message?: string
}>()

const emit = defineEmits<{
  retryCapabilities: []
  editEnvironment: []
  editLogin: []
  clearFilters: []
}>()
```

Render an optional local error/interruption banner, heading `执行前检查`, the effective count, and one row per fixed-order item. Map `ready` to `CheckCircle2`, `warning` to `TriangleAlert`, and `blocked` to `CircleX`; include the textual state in each row. Map repair actions directly to the four declared emissions and do not open a modal from this component.

- [ ] **Step 5: Implement the monitor summary**

Use this contract:

```ts
import type { ReturnTypeOfBuildWebRunView } from '../../domain/webAutomationRunView'
import type { WebAutomationRunSnapshot } from '../../domain/webAutomationRunRepository'

defineProps<{
  snapshot: WebAutomationRunSnapshot
  view: ReturnTypeOfBuildWebRunView
  connectionLabel: string
  elapsed: string
}>()

const emit = defineEmits<{
  enterMonitor: []
  rerun: []
  openReport: []
}>()
```

Before creating the component, add and export this alias beside `buildWebRunView`:

```ts
export type ReturnTypeOfBuildWebRunView = ReturnType<typeof buildWebRunView>
```

Render the latest screenshot as a thumbnail when present; otherwise show phase-specific waiting text. Show lifecycle label, connection label, elapsed time, completed/total cases, current case and step, and only the latest diagnostic. Keep `进入实时监控` explicit and visually stronger than `重新执行`, but do not reuse the page-level primary execution action. Show `查看报告` after a report-producing terminal snapshot and emit `openReport` without navigating internally.

- [ ] **Step 6: Run tests, type check, and commit Task 4**

Run:

```bash
cd apps/web
npm test -- --run \
  src/components/automation/WebRunPreflightPanel.test.ts \
  src/components/automation/WebRunMonitorSummary.test.ts \
  src/domain/webAutomationRunView.test.ts
npm run build
```

Expected: focused tests PASS and the production build exits 0.

```bash
git add apps/web/src/domain/webAutomationRunView.ts \
  apps/web/src/components/automation/WebRunPreflightPanel.vue \
  apps/web/src/components/automation/WebRunPreflightPanel.test.ts \
  apps/web/src/components/automation/WebRunMonitorSummary.vue \
  apps/web/src/components/automation/WebRunMonitorSummary.test.ts
git commit -m "feat: add web run preflight and summary"
```

---

### Task 5: Full-Canvas Live Monitor

**Files:**

- Create: `apps/web/src/components/automation/WebRunMonitorView.vue`
- Create: `apps/web/src/components/automation/WebRunMonitorView.test.ts`

- [ ] **Step 1: Write the failing full-monitor tests**

Mount a running snapshot and shared `buildWebRunView(snapshot)` result. Assert all required surfaces and controls:

```ts
expect(wrapper.get('h1').text()).toBe('实时监控')
expect(wrapper.text()).toContain('run-1')
expect(wrapper.text()).toContain('Chromium')
expect(wrapper.text()).toContain('环境准备')
expect(wrapper.text()).toContain('用例执行')
expect(wrapper.text()).toContain('环境清理')
expect(wrapper.get('img[alt="Web 自动化实时页面截图"]').attributes('src')).toBe(snapshot.latestScreenshotUrl)
expect(wrapper.text()).toContain(snapshot.currentCase?.name)
expect(wrapper.text()).toContain(snapshot.currentStep?.name)
expect(wrapper.text()).toContain(snapshot.currentPageUrl)
expect(wrapper.text()).toContain('通过 1')
expect(wrapper.text()).toContain('运行中 1')
expect(wrapper.text()).toContain('待执行 2')
```

Exercise all commands:

```ts
await wrapper.get('[aria-label="返回 Web 自动化工作台"]').trigger('click')
await wrapper.get('[data-testid="pause-web-run"]').trigger('click')
await wrapper.get('[data-testid="stop-web-run"]').trigger('click')
expect(wrapper.emitted('returnWorkbench')).toEqual([[]])
expect(wrapper.emitted('pause')).toEqual([[]])
expect(wrapper.emitted('stop')).toEqual([[]])
```

Mount queued-without-screenshot and no-timeline states and assert `等待浏览器启动` plus the current phase. Mount paused, completed, failed, and interrupted inputs and assert the exact allowed controls and `重新执行` behavior.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd apps/web
npm test -- --run src/components/automation/WebRunMonitorView.test.ts
```

Expected: FAIL because `WebRunMonitorView.vue` does not exist.

- [ ] **Step 3: Implement the full monitor contract**

Use the same snapshot and view types as the summary:

```ts
defineProps<{
  snapshot: WebAutomationRunSnapshot
  view: ReturnTypeOfBuildWebRunView
  connectionState: 'idle' | 'polling' | 'retrying' | 'interrupted'
  connectionLabel: string
  elapsed: string
  controlLoading: 'pause' | 'resume' | 'stop' | ''
}>()

const emit = defineEmits<{
  returnWorkbench: []
  pause: []
  resume: []
  stop: []
  rerun: []
  openLog: [caseId: string]
  openReport: []
  transferDefect: [caseId: string]
}>()
```

Render one canvas, not a drawer or modal. The header contains a back icon button, run identity, lifecycle, connection state, elapsed time, browser, and allowed controls. The body uses the latest screenshot as the largest surface, followed by current case/step/URL/action/timestamp details; the adjacent timeline groups setup, test, and teardown phases from `view.timeline`. Compute visible case counts from `snapshot.summary` and `snapshot.progress`: passed, failed, error, running as at most one nonterminal in-progress case, and pending as the remaining nonnegative count. Render completed result rows below the live detail; each row exposes `查看日志`, terminal runs expose `查看报告`, and failed/error rows expose `转缺陷`. Emit only case IDs so `App.vue` can resolve them against `activeWebRunCases`. Keep the last good screenshot and timeline visible when connection state is retrying or interrupted. Add one polite live region that announces only lifecycle or connection label changes.

- [ ] **Step 4: Add accessibility and stable-state styling inside the component**

Use Lucide icons for back, pause, play, stop, rotate, screenshot, and diagnostics. Give every icon-only control an accessible name and tooltip. Use fixed grid tracks and `minmax(0, 1fr)` so screenshot loading and long URLs do not resize the canvas. Use `overflow-wrap: anywhere` for URLs and diagnostics. Do not animate progress or timeline movement when `prefers-reduced-motion: reduce` is active.

- [ ] **Step 5: Run the focused test, existing run-view test, and build**

Run:

```bash
cd apps/web
npm test -- --run \
  src/components/automation/WebRunMonitorView.test.ts \
  src/domain/webAutomationRunView.test.ts
npm run build
```

Expected: both test files PASS and the production build exits 0.

- [ ] **Step 6: Commit Task 5**

```bash
git add apps/web/src/components/automation/WebRunMonitorView.vue \
  apps/web/src/components/automation/WebRunMonitorView.test.ts
git commit -m "feat: add full web run monitor"
```

---

### Task 6: Compose The Responsive Workbench

**Files:**

- Create: `apps/web/src/components/automation/WebAutomationWorkspace.vue`
- Create: `apps/web/src/components/automation/WebAutomationWorkspace.test.ts`

- [ ] **Step 1: Write the failing composition tests**

Stub all seven child components and mount the workspace in wide workbench mode. Assert one task header, one preparation bar, one scope pane, one case table, and exactly one contextual child: preflight without a snapshot or monitor summary with a snapshot.

```ts
expect(wrapper.findComponent(WebAutomationTaskHeader).exists()).toBe(true)
expect(wrapper.findComponent(WebRunPreparationBar).exists()).toBe(true)
expect(wrapper.findComponent(WebAutomationScopePane).exists()).toBe(true)
expect(wrapper.findComponent(WebAutomationCaseTable).exists()).toBe(true)
expect(wrapper.findComponent(WebRunPreflightPanel).exists()).toBe(true)
expect(wrapper.findComponent(WebRunMonitorSummary).exists()).toBe(false)
```

Mount with `mode="monitor"` and a snapshot, then assert only `WebRunMonitorView` occupies the canvas and that its `returnWorkbench`, `pause`, `resume`, `stop`, and `rerun` events are forwarded unchanged.

Mount with `compact=true`, trigger `openScope` and `openContext` from the preparation-bar stub, and assert two labelled `ResponsiveDrawer` instances appear while the inline side panes do not. Close and reopen each drawer to prove the components retain controlled values.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd apps/web
npm test -- --run src/components/automation/WebAutomationWorkspace.test.ts
```

Expected: FAIL because `WebAutomationWorkspace.vue` does not exist.

- [ ] **Step 3: Implement the workspace composition**

Define `mode: 'workbench' | 'monitor'`, `compact: boolean`, and grouped view-model props for the child contracts:

```ts
interface WebAutomationWorkspaceProps {
  mode: 'workbench' | 'monitor'
  compact: boolean
  header: {
    executableCount: number
    moduleCount: number
    latestRunLabel: string
  }
  preparation: {
    environmentId: string
    environments: Array<{ value: string; label: string }>
    browser: string
    browsers: WebRunBrowserOption[]
    loopCount: number
    loginReadyLabel: string
    actionLabel: string
    canExecute: boolean
    blockingReason: string
  }
  scope: {
    project: string
    projects: Array<{ value: string; label: string; count: number }>
    module: string
    modules: Array<{ value: string; label: string; count: number }>
  }
  table: {
    cases: SavedWebAutomationRunCase[]
    selectedIds: string[]
    source: 'ALL' | 'design' | 'default'
    status: WebCaseStatusFilter
    keyword: string
    resultStatusById: Record<string, WebAutomationRunStatus | 'pending'>
    emptyKind: 'none' | 'no-cases' | 'filtered'
  }
  preflight: WebAutomationPreflight
  statusMessage: string
  run?: {
    snapshot: WebAutomationRunSnapshot
    view: ReturnTypeOfBuildWebRunView
    connectionState: 'idle' | 'polling' | 'retrying' | 'interrupted'
    connectionLabel: string
    elapsed: string
    controlLoading: 'pause' | 'resume' | 'stop' | ''
  }
}
```

Declare and forward explicit events for every child intent: header commands; preparation changes; project/module changes; table selection/filter/row actions; preflight repairs; monitor entry/return; run controls; result log; report; and defect conversion. Pass `statusMessage` to the preflight banner when no snapshot is active. Do not use a generic event name or pass a callback prop.

In workbench mode render:

```vue
<section class="web-automation-workspace" data-testid="web-automation-workspace">
  <WebAutomationTaskHeader />
  <WebRunPreparationBar />
  <div class="web-automation-workspace__grid">
    <WebAutomationScopePane v-if="!compact" />
    <WebAutomationCaseTable />
    <WebRunPreflightPanel v-if="!compact && !run" />
    <WebRunMonitorSummary v-else-if="!compact && run" ref="monitorEntry" />
  </div>
  <ResponsiveDrawer v-if="compact" v-model="scopeDrawerVisible" title="执行范围">
    <WebAutomationScopePane v-bind="scope" />
  </ResponsiveDrawer>
  <ResponsiveDrawer
    v-if="compact"
    v-model="contextDrawerVisible"
    :title="run ? '运行监控' : '执行检查'"
  >
    <WebRunMonitorSummary v-if="run" v-bind="run" ref="monitorEntry" />
    <WebRunPreflightPanel v-else :preflight="preflight" :message="statusMessage" />
  </ResponsiveDrawer>
</section>
```

In monitor mode render only `WebRunMonitorView` under the workspace root. Keep `scopeDrawerVisible` and `contextDrawerVisible` as local UI refs. Expose `focusMonitorEntry()` and `getTableScrollElement()` with `defineExpose` so `App.vue` can restore focus and scroll without owning drawer state.

- [ ] **Step 4: Add component-local stable layout styles**

Use an unframed canvas. At wide size use `minmax(176px, 208px) minmax(560px, 1fr) minmax(280px, 304px)`; at standard desktop use `176px minmax(520px, 1fr) 280px`. The table remains the only horizontally scrollable region. No cards may wrap the three regions and no card may be nested inside the preflight or monitor summary.

- [ ] **Step 5: Run focused component tests and build**

Run:

```bash
cd apps/web
npm test -- --run src/components/automation
npm run build
```

Expected: all automation component tests PASS and the production build exits 0.

- [ ] **Step 6: Commit Task 6**

```bash
git add apps/web/src/components/automation/WebAutomationWorkspace.vue \
  apps/web/src/components/automation/WebAutomationWorkspace.test.ts
git commit -m "feat: compose web automation workbench"
```

---

### Task 7: Integrate Recovery, Selection, And One Poller In `App.vue`

**Files:**

- Create: `apps/web/src/domain/webAutomationRunSession.ts`
- Create: `apps/web/src/domain/webAutomationRunSession.test.ts`
- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/appShellStructure.test.ts`
- Modify: `apps/web/src/automationExecutionLayout.test.ts`

- [ ] **Step 1: Write failing session-coordinator tests**

Test recovery input precedence and duplicate polling without browser globals:

```ts
import { describe, expect, it } from 'vitest'
import {
  WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY,
  createWebRunPollCoordinator,
  resolveWebRunRecovery
} from './webAutomationRunSession'

describe('webAutomationRunSession', () => {
  it('prefers a monitor query run over the stored active run', () => {
    expect(resolveWebRunRecovery('?view=monitor&runId=query-run', 'stored-run')).toEqual({
      mode: 'monitor',
      runId: 'query-run'
    })
    expect(WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY).toBe('ai-test-platform.web-active-run.v1')
  })

  it('recovers a stored run into the workbench when no monitor query exists', () => {
    expect(resolveWebRunRecovery('', 'stored-run')).toEqual({ mode: 'workbench', runId: 'stored-run' })
    expect(resolveWebRunRecovery('?view=monitor', '')).toEqual({ mode: 'workbench', runId: '' })
  })

  it('allows only one polling owner for the same active run', () => {
    const coordinator = createWebRunPollCoordinator()
    const first = coordinator.start('run-1')
    const duplicate = coordinator.start('run-1')
    const replacement = coordinator.start('run-2')

    expect(first.shouldStart).toBe(true)
    expect(duplicate.shouldStart).toBe(false)
    expect(coordinator.isCurrent('run-1', first.token)).toBe(false)
    expect(replacement.shouldStart).toBe(true)
    expect(coordinator.isCurrent('run-2', replacement.token)).toBe(true)
    coordinator.stop('run-2')
    expect(coordinator.start('run-2').shouldStart).toBe(true)
  })
})
```

- [ ] **Step 2: Implement the session and poll coordinator**

Create this complete state API:

```ts
export const WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY = 'ai-test-platform.web-active-run.v1'

export const resolveWebRunRecovery = (search: string, storedRunId: string) => {
  const params = new URLSearchParams(search)
  const queryRunId = params.get('view') === 'monitor' ? params.get('runId')?.trim() || '' : ''
  return queryRunId
    ? { mode: 'monitor' as const, runId: queryRunId }
    : { mode: 'workbench' as const, runId: storedRunId.trim() }
}

export const createWebRunPollCoordinator = () => {
  let activeRunId = ''
  let token = 0
  return {
    start(runId: string) {
      if (activeRunId === runId) return { shouldStart: false, token }
      activeRunId = runId
      token += 1
      return { shouldStart: true, token }
    },
    isCurrent(runId: string, candidateToken: number) {
      return activeRunId === runId && token === candidateToken
    },
    stop(runId?: string) {
      if (runId && activeRunId !== runId) return
      activeRunId = ''
      token += 1
    }
  }
}
```

- [ ] **Step 3: Update structure tests before changing `App.vue`**

Require one `WebAutomationWorkspace` import and render for the Web branch, and protect legacy removal:

```ts
expect(appSource).toContain("import WebAutomationWorkspace from './components/automation/WebAutomationWorkspace.vue'")
expect(appSource).toContain('<WebAutomationWorkspace')
expect(appSource).not.toContain('v-model="webRunDrawerVisible"')
expect(appSource).not.toContain('class="automation-metric-grid"')
expect(appSource).not.toContain('v-model="automationKind" size="small"')
expect((appSource.match(/class="web-run-visual-drawer"/g) || [])).toHaveLength(0)
```

Update `automationExecutionLayout.test.ts` to assert the existing API controls, threshold, API progress, and API table remain in the API branch while Web delegates to the workspace. Remove assertions tied to the old Web drawer and compact filter row.

- [ ] **Step 4: Run the session and structure tests for RED**

Run:

```bash
cd apps/web
npm test -- --run \
  src/domain/webAutomationRunSession.test.ts \
  src/appShellStructure.test.ts \
  src/automationExecutionLayout.test.ts
```

Expected: session tests PASS after Step 2; structure tests FAIL because `App.vue` still contains the old Web page and duplicate drawers.

- [ ] **Step 5: Add controlled Web workbench state and derived props**

Add state beside the current Web run refs:

```ts
const webAutomationSelectedIds = ref<string[]>([])
const webAutomationStatusFilter = ref<'ALL' | WebAutomationRunStatus | 'pending'>('ALL')
const webAutomationWorkspaceMode = ref<'workbench' | 'monitor'>('workbench')
const webAutomationTableScrollTop = ref(0)
const webRunCreationError = ref('')
const webRunInterruptionMessage = ref('')
const webAutomationWorkspaceRef = ref<InstanceType<typeof WebAutomationWorkspace>>()
const webRunPollCoordinator = createWebRunPollCoordinator()
```

Extend `filteredWebAutomationCases` with `webAutomationStatusFilter`, then derive all shared values through the Task 1 adapter:

```ts
const effectiveWebAutomationScope = computed(() =>
  buildWebAutomationScope(filteredWebAutomationCases.value, webAutomationSelectedIds.value)
)

const webAutomationPreflight = computed(() => buildWebAutomationPreflight({
  scope: effectiveWebAutomationScope.value,
  capabilities: webRunCapabilities.value,
  browserOverride: runnableWebRuntime.value?.browser || 'follow-case',
  environmentName: activeWebExecutionEnvironment.value?.name || '未选择',
  environmentValidation: validateWebExecutionEnvironment(activeWebExecutionEnvironment.value),
  login: resolveWebLoginReadiness(activeWebExecutionEnvironment.value),
  logout: resolveWebLogoutReadiness(activeWebExecutionEnvironment.value)
}))

const webRunStatusMessage = computed(() =>
  webRunCreationError.value || webRunInterruptionMessage.value
)
```

Implement `resolveWebLoginReadiness` and `resolveWebLogoutReadiness` next to `resolveWebRuntimeEnvironment` using the existing normalization helper:

```ts
const resolveWebLoginReadiness = (environment?: SavedExecutionEnvironment) => {
  const login = normalizeWebExecutionRuntimeConfig(environment?.web).login
  if (!login.enabled) return { enabled: false, valid: true }
  const complete = [
    login.loginUrl,
    login.usernameLocator,
    login.passwordLocator,
    login.submitLocator,
    login.username,
    login.password,
    login.successTarget
  ].every((value) => value.trim())
  return {
    enabled: true,
    valid: complete,
    message: complete ? undefined : '登录配置不完整，请补全页面、定位器、账号和成功判定'
  }
}

const resolveWebLogoutReadiness = (environment?: SavedExecutionEnvironment) => {
  const logout = normalizeWebExecutionRuntimeConfig(environment?.web).logout
  if (!logout.enabled) return { enabled: false, valid: true }
  const complete = logout.steps.length > 0 && logout.steps.every((step) => step.target.trim())
  return {
    enabled: true,
    valid: complete,
    message: complete ? undefined : '退出登录配置不完整，请补全后置步骤目标'
  }
}
```

The helpers return readiness metadata only. They never expose usernames, passwords, headers, cookies, or variables through component props.

- [ ] **Step 6: Wire scope, filters, selection, and scroll preservation**

Add handlers that replace arrays rather than mutating component props:

```ts
const toggleWebAutomationCase = (testCase: SavedWebAutomationRunCase, checked: boolean) => {
  const next = new Set(webAutomationSelectedIds.value)
  checked ? next.add(testCase.id) : next.delete(testCase.id)
  webAutomationSelectedIds.value = Array.from(next)
}

const selectFilteredWebAutomationCases = () => {
  webAutomationSelectedIds.value = filteredWebAutomationCases.value.map((item) => item.id)
}

const pruneWebAutomationSelection = () => {
  const visible = new Set(filteredWebAutomationCases.value.map((item) => item.id))
  webAutomationSelectedIds.value = webAutomationSelectedIds.value.filter((id) => visible.has(id))
}
```

Call `pruneWebAutomationSelection()` after project or module changes, and after deleting cases. Preserve selection across source/status/keyword filtering only through the adapter's visible-ID intersection; clearing those filters restores still-existing selected IDs. Clear `webRunCreationError` when selection, filters, environment, browser, or repeat count changes so a resolved failure is not left onscreen. Before entering monitor mode read the table scroll element, and after returning set its `scrollTop` from `webAutomationTableScrollTop` inside `nextTick`.

- [ ] **Step 7: Change run creation to use the shared effective scope and preflight**

Replace the default parameter on `startVisualWebAutomationRun`. Its first lines must be:

```ts
const startVisualWebAutomationRun = async (
  explicitCases?: SavedWebAutomationRunCase[]
): Promise<boolean> => {
  const casesToRun = explicitCases || effectiveWebAutomationScope.value.cases
  if (!explicitCases && !webAutomationPreflight.value.canExecute) {
    webRunCreationError.value = webAutomationPreflight.value.blockingReason
    return false
  }
```

Keep the existing repository payload and report behavior. Remove `webRunDrawerVisible.value = true`. On success, clear both local run messages, set the active run ID in `sessionStorage`, keep `webAutomationWorkspaceMode` as `workbench`, show the monitor summary in place, and call the single poller. On creation failure, keep filters, selection, configuration, and mode unchanged while exposing `webRunCreationError` through `statusMessage` near preparation/preflight.

- [ ] **Step 8: Implement query navigation, recovery, and one active poller**

Add monitor query helpers that preserve the existing automation pathname:

```ts
const writeWebMonitorQuery = (mode: 'workbench' | 'monitor', runId = '', historyMode: 'push' | 'replace' = 'push') => {
  const url = new URL(window.location.href)
  if (mode === 'monitor' && runId) {
    url.searchParams.set('view', 'monitor')
    url.searchParams.set('runId', runId)
  } else {
    url.searchParams.delete('view')
    url.searchParams.delete('runId')
  }
  window.history[historyMode === 'replace' ? 'replaceState' : 'pushState']({}, '', `${url.pathname}${url.search}`)
}
```

Replace the token-only polling entry with the coordinator. Calling it twice for the same run must return without creating another loop. A different run invalidates the earlier token. Keep bounded retry behavior and the last good snapshot. Release the coordinator in the polling loop's `finally` block only when its run ID/token is still current, so a stopped loop can later reconnect to the same run. A 404 clears the session key, sets `webRunInterruptionMessage` to `执行已中断：Runner 中已找不到当前任务`, marks connection interrupted, retains local evidence, returns to workbench when no snapshot exists, and removes stale monitor query state with `replaceState` without creating a run.

On mount, resolve query plus `sessionStorage` with `resolveWebRunRecovery`, fetch the candidate exactly once through `getWebAutomationRun`, apply its snapshot, and start polling only if it is nonterminal. If the query requested monitor mode, show the monitor only after validation succeeds. On popstate, synchronize both navigation and monitor mode. On unmount, stop the coordinator.

- [ ] **Step 9: Replace the Web template and remove legacy markup**

Keep the current API automation markup under `automationKind === 'api'`. Render `WebAutomationWorkspace` for `automationKind === 'web'` with grouped props and explicit events. Hide the shell's outer context pane when `activeNav === 'automation' && automationKind === 'web'`; API automation keeps its existing context pane.

Delete:

- the four automation metric cards from the Web branch;
- the in-page API/Web segmented switch from the Web branch;
- duplicate project/module controls from the center toolbar;
- both `el-drawer.web-run-visual-drawer` blocks;
- `webRunDrawerVisible` state and `openWebRunDrawer()`;
- drawer-only CSS selectors no longer consumed by any page.

Wire history, generation, export, environment edit, log, defect, delete, pause, resume, stop, rerun, and report navigation to the existing handlers. For monitor result events, resolve the case ID against `activeWebRunCases` before calling `openWebAutomationLog` or `transferWebAutomationResultToDefect`; ignore an ID that is no longer present. `openActiveWebRunReport` selects the report created by `finalizeWebRunSnapshot` and navigates through the existing reports page. Entering monitor writes `view=monitor&runId=<id>` and does not change the run. Returning removes only the monitor query, restores scroll, then calls `focusMonitorEntry()`.

When the user selects API automation or leaves the automation module, remove `view` and `runId` from the URL with `replaceState` and return the Web workspace mode to `workbench`; keep the active run ID, snapshot, and poller alive so returning to Web automation preserves execution context.

- [ ] **Step 10: Run integration-focused tests and build**

Run:

```bash
cd apps/web
npm test -- --run \
  src/domain/webAutomationWorkspaceView.test.ts \
  src/domain/webAutomationRunSession.test.ts \
  src/components/automation \
  src/appShellStructure.test.ts \
  src/automationExecutionLayout.test.ts \
  src/webRunDrawerStructure.test.ts
npm run build
```

Expected: all focused tests PASS. Rename `webRunDrawerStructure.test.ts` to `webRunMonitorStructure.test.ts` during this step and change its assertions to the workspace/monitor boundary; no test or production source should reference `webRunDrawerVisible` or `web-run-visual-drawer`.

- [ ] **Step 11: Commit Task 7**

```bash
git add apps/web/src/domain/webAutomationRunSession.ts \
  apps/web/src/domain/webAutomationRunSession.test.ts \
  apps/web/src/App.vue \
  apps/web/src/appShellStructure.test.ts \
  apps/web/src/automationExecutionLayout.test.ts \
  apps/web/src/webRunDrawerStructure.test.ts \
  apps/web/src/webRunMonitorStructure.test.ts
git commit -m "feat: integrate recoverable web automation workbench"
```

When staging the renamed structure test, omit whichever old/new path does not exist; verify `git status --short` shows one rename or one delete plus one add, not an unrelated deletion.

---

### Task 8: Studio Canvas Styling And Responsive Behavior

**Files:**

- Modify: `apps/web/src/styles/studio-canvas.css`
- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/components/automation/*.vue`

- [ ] **Step 1: Add exact responsive layout assertions to component tests**

Assert stable class hooks rather than computed JSDOM layout:

```ts
expect(wrapper.get('[data-testid="web-automation-workspace"]').classes()).toContain('is-compact')
expect(wrapper.findAllComponents(ResponsiveDrawer)).toHaveLength(2)
expect(wrapper.get('[data-testid="execute-web-automation"]').exists()).toBe(true)
```

Add monitor tests proving long Chinese case names, URLs, and diagnostics remain in dedicated elements with wrapping classes. Add reduced-motion and high-contrast CSS source assertions in `appShellStructure.test.ts`:

```ts
expect(studioCss).toContain('@media (prefers-reduced-motion: reduce)')
expect(studioCss).toContain('@media (prefers-contrast: more)')
expect(studioCss).toContain('.web-automation-workspace')
expect(studioCss).toContain('.web-run-monitor-view')
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
cd apps/web
npm test -- --run src/components/automation src/appShellStructure.test.ts
```

Expected: new responsive/style hook assertions FAIL before the CSS and class bindings are complete.

- [ ] **Step 3: Implement the approved light Studio Canvas visual system**

Use existing semantic tokens only. Apply:

```css
.web-automation-workspace {
  display: grid;
  min-width: 0;
  min-height: 0;
  height: 100%;
  grid-template-rows: auto auto minmax(0, 1fr);
  gap: 12px;
  padding: 16px 18px 18px;
  color: var(--text-regular);
  background: var(--surface-page);
}

.web-automation-workspace__grid {
  display: grid;
  min-width: 0;
  min-height: 0;
  grid-template-columns: minmax(176px, 208px) minmax(560px, 1fr) minmax(280px, 304px);
  gap: 12px;
}
```

Use `var(--surface-panel)` only on the scope, table, preflight, and summary surfaces; keep their radius at `var(--radius-control)` or at most 8px. Use borders rather than heavy shadows. Use deep blue for primary execute/monitor controls, teal for ready/pass, amber for warning/pause, rose for failure/stop, and neutral gray for pending. Never use purple as the primary or a decorative gradient/orb.

- [ ] **Step 4: Implement the four responsive bands**

At `1180px..1439px`, use `176px minmax(520px, 1fr) 280px` and horizontal table overflow. At `900px..1179px`, set `compact=true` from the existing viewport mode, show the center table plus preparation bar, and move scope/context into `ResponsiveDrawer`. Below `900px`, stack preparation controls, keep the execution action full width and visible, allow the case table to scroll horizontally, and stack full monitor screenshot, timeline, and details. Do not scale font size with viewport width.

- [ ] **Step 5: Implement focus, motion, and contrast behavior**

Give native and Element Plus interactive elements `:focus-visible` outlines using `var(--brand-primary-focus)`. Ensure every status has icon plus text. Under reduced motion, disable transition and animation for the new workbench and monitor descendants. Under increased contrast, strengthen border colors and outlines without changing layout. Use `aria-live` only for blocking reason and lifecycle/connection transitions, not every polling update.

- [ ] **Step 6: Run focused tests and build**

Run:

```bash
cd apps/web
npm test -- --run src/components/automation src/appShellStructure.test.ts
npm run build
```

Expected: all focused tests PASS and the production build exits 0.

- [ ] **Step 7: Commit Task 8**

```bash
git add apps/web/src/styles/studio-canvas.css \
  apps/web/src/App.vue \
  apps/web/src/components/automation
git commit -m "style: align web automation with Studio Canvas"
```

---

### Task 9: Browser Journeys, Visual Baselines, And Completion Verification

**Files:**

- Create: `apps/web/e2e/fixtures/webAutomationWorkspace.ts`
- Modify: `apps/web/e2e/studio-canvas.spec.ts`
- Modify: `apps/web/e2e/studio-canvas.spec.ts-snapshots/`
- Create: `docs/superpowers/design-assets/web-automation/04-implemented-workbench-1440.png`
- Create: `docs/superpowers/design-assets/web-automation/05-implemented-monitor-1440.png`

- [ ] **Step 1: Build deterministic Web automation browser fixtures**

Export `openWebAutomationFixture(page, options)` with these exact variants:

```ts
export type WebAutomationFixtureVariant =
  | 'idle-ready'
  | 'preflight-blocked'
  | 'creation-failed'
  | 'running'
  | 'partial-result'
  | 'retrying'
  | 'interrupted'
  | 'completed'

export interface OpenWebAutomationFixtureOptions {
  width: number
  height: number
  variant: WebAutomationFixtureVariant
  restoreRunId?: string
  monitorQuery?: boolean
}
```

Seed three runnable cases under `WEB_AUTOMATION_LIBRARY_STORAGE_KEY`, including one long Chinese case title; seed one valid Chromium environment under `WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY`; and optionally seed `WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY`. Route:

```ts
GET  /api/web-automation/capabilities
POST /api/web-automation/runs
GET  /api/web-automation/runs/run-fixture
POST /api/web-automation/runs/run-fixture/pause
POST /api/web-automation/runs/run-fixture/resume
POST /api/web-automation/runs/run-fixture/stop
GET  /web-automation-fixture/run-fixture.png
```

Serve the last route from `docs/superpowers/design-assets/web-automation/03-final-workbench-detail.png` with `contentType: 'image/png'`, so screenshot and pixel checks use a committed nonblank asset. Record the POST body in a fixture callback so tests can assert the submitted case IDs. Fulfill other existing `/api/**` requests with the same known 503 fixture behavior already accepted by the runtime-error guard.

Navigate to `/automation`, click the sidebar button named `Web 自动化`, and wait for `[data-testid="web-automation-workspace"]`.

- [ ] **Step 2: Add the wide execution and monitor journey**

At `1440x900`, assert:

```ts
await expect(page.getByRole('heading', { name: 'Web 自动化' })).toBeVisible()
await expect(page.getByText('执行前检查')).toBeVisible()
await expect(page.getByRole('button', { name: '执行当前 3 条' })).toBeEnabled()
await expect(page.getByLabel('Web 自动化项目')).toHaveCount(1)
await expect(page.getByText('用例总数')).toHaveCount(0)
```

Select two visible rows and assert the button becomes `执行选中 2 条`. Start the run, assert the captured POST contains exactly those two case IDs, and assert the page stays in workbench mode while the right pane changes to monitor summary. Click `进入实时监控`, assert `view=monitor&runId=run-fixture`, the full monitor screenshot has nonzero natural dimensions, and a center-region pixel sample is not uniform. Return and assert keyword, selection count, scroll position, run summary, and focus on the monitor-entry command are preserved.

Take these approved snapshots:

```ts
await expect(page).toHaveScreenshot('web-automation-workbench-1440.png')
await expect(page).toHaveScreenshot('web-automation-monitor-1440.png')
```

- [ ] **Step 3: Add recovery and failure-state journeys**

Add separate tests that:

- reload with `sessionStorage` active run and confirm one GET recovery plus one polling sequence, never a POST;
- load `/automation?view=monitor&runId=run-fixture` and enter monitor only after GET validation;
- return 404 for a stored run, then assert the session key and stale monitor query are cleared while the page shows `执行已中断` and keeps the last local evidence when one exists;
- keep selected cases and filters after a blocked preflight or POST creation failure;
- retain the last screenshot during retrying and interrupted connection states;
- show passed, failed, error, running, and pending counts for partial and terminal snapshots.

For a failed terminal result, open its log, exercise `转缺陷`, and use `查看报告` to reach the existing report detail. These checks protect the existing evidence and defect workflow after both legacy drawers are removed.

Use request counters to assert at most one concurrent GET poll is pending across workbench/monitor transitions.

- [ ] **Step 4: Add responsive and accessibility journeys**

At `1280x800`, assert the three-column layout remains, long Chinese labels wrap or truncate without covering controls, the table scroll container has `scrollWidth > clientWidth`, and selected/filtered execution labels remain visible.

At `1024x768`, assert collapsed sidebar behavior, no inline scope/preflight panes, and working `执行范围` plus `执行检查`/`运行监控` drawers. Enter full monitor and assert it replaces the canvas rather than opening a dialog.

At `390x844`, assert stacked environment/browser/repeat controls, a full-width executable primary action, filter access, monitor access, and no document-level horizontal overflow:

```ts
expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
```

Run `expectNoAxeViolations` against the workspace and monitor roots. Keyboard through preparation, table selection, monitor entry, pause/stop, and return; assert visible focus and restoration. Emulate reduced motion and assert no new workbench/monitor element has a nonzero animation duration.

- [ ] **Step 5: Run the new browser tests and inspect RED failures before updating images**

Run:

```bash
cd apps/web
npx playwright test e2e/studio-canvas.spec.ts --grep "Web automation workbench"
```

Expected: functional assertions PASS; screenshot assertions FAIL until the new approved baselines are written.

- [ ] **Step 6: Update only Web automation snapshots and inspect them**

Run:

```bash
cd apps/web
npx playwright test e2e/studio-canvas.spec.ts \
  --grep "Web automation workbench" \
  --update-snapshots
```

Open every newly written Web automation snapshot and compare it against:

```text
docs/superpowers/design-assets/web-automation/03-final-workbench-detail.png
docs/superpowers/specs/2026-07-27-web-automation-studio-workbench-design.md
```

Reject snapshots with clipped text, nested cards, purple primary controls, missing monitor screenshot, overlapping drawers, hidden execution action, or a return-state jump. Do not update unrelated Studio Canvas snapshots.

- [ ] **Step 7: Save final implementation references**

With the development server active, capture the verified `1440x900` idle workbench and full monitor to:

```text
docs/superpowers/design-assets/web-automation/04-implemented-workbench-1440.png
docs/superpowers/design-assets/web-automation/05-implemented-monitor-1440.png
```

Use Playwright with `deviceScaleFactor: 1`, wait for the fixture screenshot to finish loading, and assert image natural width/height plus nonuniform canvas pixels before saving. These references supplement rather than replace `03-final-workbench-detail.png`.

- [ ] **Step 8: Run the complete frontend verification gate**

Run each command separately:

```bash
cd apps/web
npm test -- --run
npm run test:e2e
npm run build
cd ../..
git diff --check
```

Expected: complete Vitest suite exits with zero failures; complete Playwright suite exits with zero failures and no unexpected runtime errors or accessibility violations; `vue-tsc --noEmit` and Vite build succeed; `git diff --check` prints no errors.

- [ ] **Step 9: Start the review server and report the URL**

Run:

```bash
cd apps/web
npm run dev -- --host 127.0.0.1 --port 5174
```

If port `5174` is already occupied, select the next free local port. Keep the server running and report the exact `/automation` URL for visual review. The earlier design-reference server on `56204` may remain available because it serves a different purpose.

- [ ] **Step 10: Commit Task 9**

```bash
git add apps/web/e2e/fixtures/webAutomationWorkspace.ts \
  apps/web/e2e/studio-canvas.spec.ts \
  apps/web/e2e/studio-canvas.spec.ts-snapshots \
  docs/superpowers/design-assets/web-automation/04-implemented-workbench-1440.png \
  docs/superpowers/design-assets/web-automation/05-implemented-monitor-1440.png
git commit -m "test: verify web automation workbench"
```

---

## Final Acceptance Checklist

- [ ] Sidebar remains the only API automation/Web automation module switch.
- [ ] Web automation shows no metric-card strip and no duplicated project/module filters.
- [ ] Default view reads as `选择用例 -> 配置环境 -> 执行 -> 查看实时结果`.
- [ ] Selected visible rows take precedence; otherwise current filtered rows execute.
- [ ] Primary action, preflight, and submitted payload use one effective scope.
- [ ] Idle right pane shows runner, browser, environment, login, teardown, and executable-count checks.
- [ ] Starting a run stays in the workbench and swaps preflight for monitor summary.
- [ ] Full monitor is an in-canvas view with live screenshot, timeline, diagnostics, counts, and controls.
- [ ] Returning restores scope, filters, selection, table scroll, active run, latest snapshot, and keyboard focus.
- [ ] Query/session recovery validates the run and never creates a replacement run.
- [ ] Workbench/monitor changes never create a second poller.
- [ ] API automation and all existing report/defect/environment behavior remain intact.
- [ ] Wide, standard, compact, and narrow layouts match the approved Studio Canvas visual language.
- [ ] Primary actions are deep blue; readiness is teal; warnings are amber; purple is not the primary palette.
- [ ] New implementation screenshots are committed beside the preserved approved design references.
