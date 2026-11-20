# App.vue God Component Decomposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `apps/web/src/App.vue` 从承载所有业务副作用的上帝组件收敛为认证门禁、应用壳和功能组装根，同时保持现有导航、工作区隔离、API/Web 执行和环境管理行为。

**Architecture:** 沿用现有 `ui/` 与 `components/views/` 目录，不新增第三套 `features/` 目录约定。每个业务域由 controller composable 持有自己的响应式状态和副作用；跨域能力通过窄端口传递，组件树使用 `provide/inject` 共享 App 创建的实例。`App.vue` 只组装 session、navigation、feature controller、页面和全局弹层，不直接实现业务请求、运行轮询或环境保存。

**Tech Stack:** Vue 3 Composition API、TypeScript、Vite、Vitest、Vue Test Utils、Element Plus、现有 `domain/*` repository 和 `ui/*` composable。

---

## 现状、约束与完成标准

现状基线来自 `apps/web/src/App.vue`：`script setup` 约 4027 行，模板约 1519 行，包含认证/工作区生命周期、手写导航、API 执行、Web 执行、Jira 配置、环境编辑、缺陷、报告和用例库桥接。

必须保持的行为包括：

- 认证失效、登出、换账号后不能继续写入旧账号的 workspace 数据。
- Web run 的创建、轮询、恢复、暂停、恢复执行和停止行为保持不变。
- API monitor 的准备页/监控页切换、运行计数和报告入口保持不变。
- 环境编辑草稿脏检查、离开确认、API/Web 环境选择和保存行为保持不变。
- 手写导航协议和现有 URL 结构保持不变；本计划不引入 vue-router。
- 现有 `domain/*` 纯逻辑模块不因本次拆分迁移到组件层。

最终验收采用以下规则：

- `App.vue` 的 `script setup` 目标不超过 500 行；超过时必须说明保留原因。
- 单个 controller 超过 400 行触发再次按状态机或子域拆分的评审，不机械按行截断。
- controller 不得 import `App.vue`，不得直接 import 另一个 controller。
- 新增业务测试不读取 `wrapper.vm.$.setupState`；现有测试逐阶段迁移。
- App 集成测试保留对组装行为的覆盖；测试数量不作为“只减不增”的硬指标。
- `App.vue` 不再直接包含运行轮询、Jira 请求、环境保存和认证状态清理实现。

## 状态归属和接口约定

不要创建一个包含所有状态的“大 AppContext”。使用 `apps/web/src/ui/appPorts.ts` 定义窄端口：

```ts
import type { ComputedRef, Ref } from 'vue'
import type { NavKey } from '../domain/appNavigation'

export type OperationLease = () => boolean
export type NavHistoryMode = 'push' | 'replace' | 'skip'

export interface NavigationPort {
  currentNav: Readonly<Ref<NavKey>>
  navigate(next: NavKey, mode?: NavHistoryMode): void
}

export interface WorkspacePort {
  ownerId: Readonly<Ref<string | undefined>>
  authenticated: Readonly<Ref<boolean>>
  begin(resource: string, templateLease?: boolean): OperationLease
  fetch(input: string, init?: RequestInit): Promise<Response>
}

export interface NoticePort {
  info(message: string): void
  warning(message: string): void
  error(message: string): void
}
```

状态归属固定如下：

| 状态 | 所有者 |
|---|---|
| 用户、session epoch、workspace storage、请求代次 | `useAppSession` 和 `useWorkspaceRuntime` |
| `activeNav`、历史记录、离开确认、`popstate` | `useAppNavigation` |
| `automationKind`、API/Web 工作台模式 | 执行工作台 controller |
| `automationSelection`、API monitor、API report | `useApiExecutionController` |
| Web run、轮询、恢复、暂停、停止 | `useWebExecutionController` |
| API/Web 环境库、编辑草稿、环境保存 | `useEnvironmentController` |
| Web 登录发现和登录验证 | 初期保留在 App；环境 controller 稳定后单独抽取 |
| `activeProjectId` | OpenAPI/项目工作区状态 |
| `requirementAnalysisProjectName` | 助手或用例生成状态 |

controller factory 接收窄端口，组件层可以通过 `provide/inject` 获取 App 创建的实例；controller 单元测试直接传 fake port，不通过挂载整个 App 验证内部状态。

## Task 1: 建立可回滚基线和重构台账

**Files:**

- Create: `docs/superpowers/baselines/2026-11-19-app-vue-decomposition.md`
- Inspect: `apps/web/package.json`
- Inspect: `apps/web/package-lock.json`
- Inspect: `apps/web/src/App*.test.ts`
- Inspect: `apps/web/src/appShellStructure.test.ts`
- Inspect: `apps/web/src/actionBarLayout.test.ts`

- [x] **Step 1: 保存完整工作区快照**

  在第一次修改代码前记录 `git status --short --untracked-files=all`、`git diff --binary HEAD` 和 staged diff。工作区有未跟踪文件时，使用包含未跟踪文件的快照方式；不能只使用默认不会保存未跟踪文件的 `git stash create`。

- [x] **Step 2: 运行前端基线检查**

  在 `apps/web` 执行：

  ```bash
  npm run test -- --run
  npx vue-tsc --noEmit
  npm run build
  ```

  将每个命令的退出码、失败测试、失败原因和环境前提写入 `docs/superpowers/baselines/2026-11-19-app-vue-decomposition.md`。基线可以存在既有失败，但每个既有失败必须有明确记录。

- [x] **Step 3: 建立测试分类清单**

  将 App 测试分为三类并记录到基线文档：

  1. 必须保留的行为集成测试，例如认证、workspace 隔离、Web run 恢复和 API monitor。
  2. 迁移到 controller 的业务状态测试。
  3. 只约束当前源码位置的结构测试，例如 `actionBarLayout.test.ts`。

- [x] **Step 4: 记录基线版本**

  保存当前 HEAD、工作区快照位置和基线命令输出。每个后续阶段完成后形成一个独立版本控制节点；不把重构阶段和无关业务改动混在同一节点中。

## Task 2: 定义窄端口和 controller 测试方式

**Files:**

- Create: `apps/web/src/ui/appPorts.ts`
- Create: `apps/web/src/ui/appPorts.test.ts`
- Modify: `apps/web/src/App.vue:303-318, 3685-3715`
- Modify: `apps/web/src/components/shell/AppShell.vue` only if a typed port is required by the shell

- [x] **Step 1: 写端口契约测试**

  测试 fake `WorkspacePort` 的 operation lease：新 lease 使同 resource 的旧 lease 失效；不同 resource 的 lease 互不影响；未认证时所有 lease 返回 `false`。

- [x] **Step 2: 定义窄接口**

  `appPorts.ts` 只放跨域所需的类型和注入 key，不存业务数据、不创建 singleton、不调用 `window`；`NavKey` 从 `domain/appNavigation.ts` 引入，当前位于 `App.vue` 的 `NavHistoryMode` 在此处统一定义并由 App 删除本地副本。

- [x] **Step 3: 定义 controller 返回值约定**

  每个 controller 返回明确的 readonly view state 和命令函数，例如：

  ```ts
  export interface EnvironmentController {
    kind: Readonly<Ref<'api' | 'web'>>
    library: Readonly<ComputedRef<ExecutionEnvironmentLibrary>>
    editing: SavedExecutionEnvironment
    draftDirty: Readonly<ComputedRef<boolean>>
    selectEnvironment(id: string): void
    addEnvironment(): void
    saveEnvironment(): void
    discardDraft(): void
  }
  ```

  实际接口只暴露页面需要的字段，禁止把整个 App 的 refs 作为返回值。

- [x] **Step 4: 运行端口测试并形成版本节点**

  ```bash
  npm run test -- --run src/ui/appPorts.test.ts
  ```

  验证通过后记录该阶段变更，确认没有新增对 `App.vue` 的反向依赖。

## Task 3: 以环境库和编辑器作为首个垂直试点

**Files:**

- Create: `apps/web/src/ui/useEnvironmentController.ts`
- Create: `apps/web/src/ui/useEnvironmentController.test.ts`
- Modify: `apps/web/src/App.vue:607-795, 2933-3149, 4518-4548, 4724-5119`
- Modify: `apps/web/src/components/views/EnvironmentManagementView.vue` only for typed view-model props/events
- Modify: `apps/web/src/App.environmentEditorGuards.test.ts`
- Modify: `apps/web/src/App.environmentsRoute.test.ts`
- Modify: `apps/web/src/App.webRunStartSnapshot.test.ts` only where it asserts environment navigation

- [x] **Step 1: 写环境 controller 的失败测试**

  覆盖以下行为：初始化绑定 active environment；切换环境时 draft 未变更则直接切换；draft 有变更且确认取消时保留原 draft；保存后 dirty 变为 `false`；删除 active environment 后 active id 指向有效环境；API 与 Web 使用各自 storage key。

- [x] **Step 2: 抽环境库和编辑草稿状态**

  将 `executionEnvironmentLibrary`、`webExecutionEnvironmentLibrary`、`editingEnvironment`、`environmentEditorTarget`、draft comparable/dirty、选择/新增/删除/保存/取消逻辑移入 `useEnvironmentController`。复用现有 `executionEnvironmentRepository.ts` 的 clone、normalize、validate 和 build 函数。

- [x] **Step 3: 保留登录发现和登录验证在原边界**

  `startWebLoginDiscovery`、`confirmAndApplyWebLoginDiscovery`、`verifyEditingWebEnvironmentLogin` 及对应弹窗先通过 controller 暴露的最小命令调用，暂不把 Web 长任务和登录 session 状态混入首个 controller。

- [x] **Step 4: 让环境页面消费 view model**

  `EnvironmentManagementView.vue` 继续只处理目录、搜索和 slot；编辑器通过 props 接收 controller 的 view model，通过 emits 发送 `select`、`save`、`discard` 等命令，不直接 import repository 或 App。

- [x] **Step 5: 迁移环境测试**

  将纯状态和保存规则放入 `useEnvironmentController.test.ts`；保留 `App.environmentEditorGuards.test.ts` 验证真实 App 的导航、确认和页面组合。结构扫描测试只更新到新的文件归属，不删除行为断言。

- [x] **Step 6: 运行试点验证并形成版本节点**

  ```bash
  npm run test -- --run src/ui/useEnvironmentController.test.ts src/App.environmentEditorGuards.test.ts src/App.environmentsRoute.test.ts src/App.webRunStartSnapshot.test.ts
  npx vue-tsc --noEmit
  ```

  只有环境库、编辑器和现有导航行为通过后，才进入 session 抽取。

## Task 4: 抽取认证和 workspace 生命周期

**Files:**

- Create: `apps/web/src/ui/useAppSession.ts`
- Create: `apps/web/src/ui/useWorkspaceRuntime.ts`
- Create: `apps/web/src/ui/useAppSession.test.ts`
- Create: `apps/web/src/ui/useWorkspaceRuntime.test.ts`
- Modify: `apps/web/src/App.vue:3685-4026`
- Modify: `apps/web/src/App.authLifecycle.test.ts`
- Modify: `apps/web/src/App.authSession.test.ts`
- Modify: `apps/web/src/App.authContinuation.test.ts`
- Modify: `apps/web/src/App.workspaceBoundary.test.ts`
- Modify: `apps/web/src/App.workspacePaths.test.ts`

- [x] **Step 1: 写 session operation lease 测试**

  覆盖 bootstrap 竞态、登出使旧请求失效、切换用户使旧 owner 失效、卸载使后台操作失效，以及同一 resource 的后发操作拒绝旧响应写回。

- [x] **Step 2: 抽 `useWorkspaceRuntime`**

  将 `workspaceOperations`、`beginWorkspaceOperation`、`workspaceFetch`、workspace storage capture/invalidate 和 owner 校验移入 runtime。runtime 不知道 defects、reports、cases 等具体模块，只返回 lease、fetch 和失效通知。

- [x] **Step 3: 抽 `useAppSession`**

  将 `bootstrapAuth`、`onLoginSuccess`、`handleLogout`、`handleSessionExpired` 和 change-password 生命周期移入 session。Session 通过 `onWorkspaceReset` 和 `onAuthenticated` 两个窄 callback 通知 App，不直接 import 任意业务 controller。

- [x] **Step 4: 建立显式 reset 清单**

  App 仍负责调用各 feature 的 `reset`，但将调用表集中为 `resetFeatureState()`，逐项覆盖 template、cases、defects、reports、API run、Web run、environment、Jira、dialogs。新 feature 加入时必须增加 reset 测试。

- [x] **Step 5: 迁移认证和隔离测试**

  controller 测试验证 lease 和 owner；App 测试验证真实登录/登出组合、换账号不读旧 owner 的持久化 key、旧请求不能写回新账号。

- [x] **Step 6: 运行 session 验证并形成版本节点**

  ```bash
  npm run test -- --run src/ui/useAppSession.test.ts src/ui/useWorkspaceRuntime.test.ts src/App.authLifecycle.test.ts src/App.authSession.test.ts src/App.authContinuation.test.ts src/App.workspaceBoundary.test.ts src/App.workspacePaths.test.ts
  npx vue-tsc --noEmit
  ```

## Task 5: 抽取 API 执行 controller 和页面

**Files:**

- Create: `apps/web/src/ui/useApiExecutionController.ts`
- Create: `apps/web/src/ui/useApiExecutionController.test.ts`
- Create: `apps/web/src/components/views/ApiExecutionPage.vue`
- Modify: `apps/web/src/App.vue:382-440, 1958-2132, 4205-4465`
- Modify: `apps/web/src/components/views/ExecutionWorkbenchView.vue` only for the typed API slot contract
- Modify: `apps/web/src/App.apiMonitor.integration.test.ts`
- Modify: `apps/web/src/App.integration.test.ts`
- Modify: `apps/web/src/actionBarLayout.test.ts`
- Modify: `apps/web/src/appShellStructure.test.ts` if it references moved source text

- [x] **Step 1: 写 API controller 测试**

  覆盖运行快照冻结、循环次数校验、并发/顺序参数、monitor 事件顺序、旧 run generation 丢弃迟到结果、执行完成后报告保存和运行期间环境抽屉关闭。

- [x] **Step 2: 抽 API 执行状态机**

  将 `apiRunSnapshot`、`apiRunMonitor`、`apiAutomationRunning`、progress、确认和 `executeApiAutomationCases` 迁入 controller。controller 只通过 `WorkspacePort`、执行 repository、报告 repository 和 `NoticePort` 工作。

- [x] **Step 3: 移动 API 页面模板**

  将 API topbar、筛选区、action bar、`ApiAutomationCaseTable` 和运行配置抽屉移入 `ApiExecutionPage.vue`。保留当前事件语义和 `data-testid`，不要在同一阶段更改 UI 文案或布局。

- [x] **Step 4: 迁移测试到行为边界**

  API controller 测试不挂载 App；App monitor 集成测试只验证页面导航、真实 controller 注入和报告入口。`actionBarLayout.test.ts` 改为扫描 `ApiExecutionPage.vue` 或改为运行时断言。

- [x] **Step 5: 运行 API 验证并形成版本节点**

  ```bash
  npm run test -- --run src/ui/useApiExecutionController.test.ts src/App.apiMonitor.integration.test.ts src/App.integration.test.ts src/actionBarLayout.test.ts
  npx vue-tsc --noEmit
  ```

## Task 6: 抽取 Web 执行 controller 和页面

**Files:**

- Create: `apps/web/src/ui/useWebExecutionController.ts`
- Create: `apps/web/src/ui/useWebExecutionController.test.ts`
- Create: `apps/web/src/components/views/WebExecutionPage.vue`
- Modify: `apps/web/src/App.vue:426-441, 1449-1956, 4466-4515`
- Modify: `apps/web/src/components/automation/WebAutomationWorkspace.vue` only for a stable typed view-model/event contract
- Modify: `apps/web/src/App.webRunStartSnapshot.test.ts`
- Modify: `apps/web/src/App.integration.test.ts`

- [x] **Step 1: 写 Web controller 测试**

  覆盖 create run 的 frozen request、环境快照、poll coordinator、连续失败进入 retrying、终态报告保存、浏览器刷新恢复、run id 不匹配清理、pause/resume/stop 的代次校验和旧响应丢弃。

- [x] **Step 2: 抽 Web run 状态和命令**

  将 `activeWebRunId`、snapshot、cases、capabilities、connection state、control loading、recovery、monitor/workbench 状态以及对应命令迁入 controller。保留现有 `domain/webAutomationRunSession.ts` 和 `domain/webAutomationRunRepository.ts` 的职责，不把轮询实现放回组件。

- [x] **Step 3: 固化 Web workspace view model**

  `WebAutomationWorkspace.vue` 只接收 `header`、`preparation`、`scope`、`table`、`preflight`、`run`、`suites` 等 view model，并发送现有事件。事件 handler 由 `WebExecutionPage.vue` 绑定 controller 命令，App 不再列出全部 Web 事件。

- [x] **Step 4: 迁移 Web 测试**

  将 run 生命周期和恢复规则放入 controller 测试；保留 App 测试验证 URL 恢复、页面组合和账号边界。原有 `setupState` 访问改为 controller 测试或页面行为测试。

- [x] **Step 5: 运行 Web 验证并形成版本节点**

  ```bash
  npm run test -- --run src/ui/useWebExecutionController.test.ts src/App.webRunStartSnapshot.test.ts src/App.integration.test.ts src/components/automation/WebAutomationWorkspace.test.ts
  npx vue-tsc --noEmit
  ```

## Task 7: 抽取导航、路由内容和全局弹层

**Files:**

- Create: `apps/web/src/ui/useAppNavigation.ts`
- Create: `apps/web/src/ui/useAppNavigation.test.ts`
- Create: `apps/web/src/components/AppRouteView.vue`
- Create: `apps/web/src/components/AppOverlays.vue`
- Modify: `apps/web/src/App.vue:1135-1332, 3671-3683, 4030-5547`
- Modify: `apps/web/src/domain/appNavigation.test.ts` only when a pure navigation contract is expanded
- Modify: `apps/web/src/App.help.test.ts`
- Modify: `apps/web/src/appShellStructure.test.ts`
- Modify: `apps/web/src/actionBarLayout.test.ts`

- [x] **Step 1: 写导航行为测试**

  覆盖 path 到 `NavKey` 的转换、API/Web automation path、push/replace/skip、`popstate`、Jira/environment draft 离开确认，以及登录状态变化时的导航恢复。

- [x] **Step 2: 抽 `useAppNavigation`**

  将 `activeNav`、`automationKind` 的 URL 同步、`navigateToNav`、`syncNavWithLocation`、`handlePopState` 和离开守卫迁入 navigation。保留现有 `domain/appNavigation.ts` 的纯函数和 URL 协议。

- [x] **Step 3: 抽 `AppRouteView`**

  `AppRouteView.vue` 只根据 navigation view model 选择 `CasesView`、`HomeView`、`AssistantChainView`、`OverviewView`、`SettingsCenterView`、`QualityWorkbenchView`、API/Web execution page 和 environment page；不创建业务请求和 watcher。

- [x] **Step 4: 抽 `AppOverlays`**

  将用例编辑、curl 预览、环境编辑、登录发现、登录验证、自动化日志、生成对话框和修改密码弹窗移入 `AppOverlays.vue`。通过 typed props/emits 传递状态和命令，保留现有 `data-testid`。

- [x] **Step 5: 运行壳层验证并形成版本节点**

  ```bash
  npm run test -- --run src/ui/useAppNavigation.test.ts src/App.help.test.ts src/appShellStructure.test.ts src/actionBarLayout.test.ts
  npx vue-tsc --noEmit
  ```

## Task 8: 消除 `useCases` module-level singleton

**Files:**

- Modify: `apps/web/src/ui/useCases.ts:83-85, 685-688`
- Create: `apps/web/src/ui/useCasesProvider.ts`
- Create: `apps/web/src/ui/useCasesProvider.test.ts`
- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/components/views/CasesView.vue:16-77`
- Modify: `apps/web/src/ui/useCases.test.ts`
- Modify: `apps/web/src/components/views/CasesView.integration.test.ts`

- [x] **Step 1: 暴露显式 `createCasesState`**

  将 cases state 创建函数变成可测试的显式 factory；它接收 storage 和窄端口，不再依赖模块级可变 singleton。

- [x] **Step 2: 建立 App 级 provider**

  App 创建一个 cases controller 实例，通过 `InjectionKey<CasesController>` 提供给 `CasesView`。`CasesView` 只通过 `useCasesProvider()` 获取实例，缺少 provider 时立即抛出明确错误。

- [x] **Step 3: 保留独立单元测试能力**

  `useCases.test.ts` 直接调用 `createCasesState`；`CasesView.integration.test.ts` 使用测试 provider；App 集成测试只验证 provider 被正确组装。

- [x] **Step 4: 验证账号切换和组件卸载**

  确认卸载一个 App 后不会影响另一个 App 实例，换账号不会复用旧 singleton 的 library state，且现有 cases 行为不变。

- [x] **Step 5: 运行 cases 验证并形成版本节点**

  ```bash
  npm run test -- --run src/ui/useCases.test.ts src/ui/useCasesProvider.test.ts src/components/views/CasesView.integration.test.ts
  npx vue-tsc --noEmit
  ```

## Task 9: 收敛 App、迁移剩余测试并做最终验收

**Files:**

- Modify: `apps/web/src/App.vue`
- Modify: all remaining `apps/web/src/App*.test.ts` files that read `setupState`
- Modify: `apps/web/src/components/**/*.test.ts` when a moved source contract is referenced
- Modify: `docs/superpowers/baselines/2026-11-19-app-vue-decomposition.md`

- [x] **Step 1: 收敛 App 的唯一职责**

  `App.vue` 最终只保留：认证状态门禁、AppShell 组装、controller 创建/provider、route view、overlays、全局错误和卸载清理。删除业务 repository 调用、执行轮询、环境保存、Jira 保存和跨模块计算。

- [x] **Step 2: 迁移所有新增测试边界**

  新增 controller 测试使用 fake ports；页面测试使用 props/events；App 测试只验证跨模块行为。现有测试如果仍需读取 setup state，优先暴露 typed controller fixture 或改为用户行为，不把更多内部 ref 加回 App。

- [x] **Step 3: 做结构扫描和依赖反向检查**

  执行以下检查：

  ```bash
  rg -n "from ['\"]\.\/App\.vue|from ['\"].*use.*Controller" apps/web/src/ui apps/web/src/components
  rg -n "setupState|\$\.setupState" apps/web/src --glob '*.test.ts'
  wc -l apps/web/src/App.vue apps/web/src/ui/use*Controller.ts
  ```

  第一条不能出现 controller 对 App 或 controller 之间的直接依赖；第二条只能保留明确标记为遗留迁移护栏的测试；第三条用于验收行数目标。

- [x] **Step 4: 执行完整验证**

  ```bash
  npm run test -- --run
  npx vue-tsc --noEmit
  npm run build
  ```

  与 Task 1 基线比较：既有失败不得扩大；新增失败必须定位到本次变更并修复后再结束。

- [x] **Step 5: 更新台账并形成最终版本节点**

  在基线文档中记录最终行数、controller 数量、遗留 setupState 测试、所有结构测试迁移情况和完整验证结果。每个阶段的版本节点都应只包含该阶段拆分内容，不能混入无关业务改动。

## 不在本计划中的事项

- 不在本次拆分中引入 vue-router；等 `App.vue` 稳定后另行评估。
- 不重写现有 `domain/*` repository，不改变后端 API 契约。
- 不在环境试点阶段抽取 Web 登录发现和登录验证；它们属于后续独立任务。
- 不通过删除结构测试来制造“测试通过”；结构测试要迁移为新文件归属或改成运行时行为测试。
- 不把所有状态塞进 Pinia、单一 `AppContext` 或新的 module-level singleton。
