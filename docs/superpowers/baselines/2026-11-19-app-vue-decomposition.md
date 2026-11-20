# App.vue 拆分基线

日期：2026-11-19

## 工作区

- 分支：`main`
- 基线提交：`31ce987da7ea95664e6a79489925d001f4e121f3`
- 进入 Phase 0 时已确认工作区没有业务代码的 staged 或 unstaged 修改。
- 方案文件 `docs/superpowers/plans/2026-11-19-app-vue-decomposition.md` 已存在于基线提交中。
- 已在执行前保存工作区和索引二进制快照；快照内容为空，表示执行前没有待保护的代码差异。

## 基线命令

命令均在 `apps/web` 目录执行。

| 命令 | 结果 |
|---|---|
| `npm run test -- --run` | 通过：197 个测试文件，1584 个测试 |
| `npx vue-tsc --noEmit` | 通过 |
| `npm run build` | 通过 |

## 已知构建提示

- Lightning CSS 报告现有 `.jira-config-switch :deep(.el-switch__label)` 使用了非标准 pseudo-class 写法，建议使用 `::deep`；本次基线不修改。
- Vite 报告构建后 JavaScript chunk 超过 500 kB；本次基线不修改，不能归因于 App.vue 拆分。

## Phase 1 端口与环境试点

- 新增 `apps/web/src/ui/appPorts.ts`，定义 navigation/workspace/notice 三个窄端口和注入 key。
- 新增 `apps/web/src/ui/useEnvironmentController.ts`，承载 API/Web 环境库、编辑草稿、选择、新增、删除、保存和 dirty guard。
- Web 登录发现、登录验证、curl 导入和弹窗可见性仍由 App 协调，未混入首个 controller。
- `App.vue` 已使用 environment controller 提供的状态和核心命令；现有环境页 props/events 保持兼容。
- `src/ui/appPorts.test.ts`、`src/ui/useEnvironmentController.test.ts`、环境页和 App 回归测试通过：6 个端口/环境单测，17 个环境集成测试，65 个相关回归测试。
- `npx vue-tsc --noEmit` 通过。

## Phase 3 API/Web execution state

- 新增 `useApiExecutionController`，接管 API 运行参数、计数校验、确认快照、执行副作用、monitor、报告保存和 generation。
- 新增 `ApiExecutionPage.vue` 页面边界，保持现有 API slot、focus、scroll 和 `data-testid` 契约。
- 新增 `useWebExecutionController`，接管 Web 执行准备和 run 状态；Web 长任务函数暂时继续使用原 App handler，后续按同一端口模式迁移。
- 新增 `useAppNavigation`，接管初始导航状态和 API/Web automation kind；history/离开守卫仍由 App 保持。
- API/Web 相关 controller、App 集成和结构测试通过；全量基线验证：204 个测试文件，1603 个测试。
- `npx vue-tsc --noEmit` 和 `npm run build` 通过。

## Phase 4 cases provider

- `useCases` 已改为显式 `createCasesState` 加 App 级 `provide/inject`，移除 module-level singleton。
- `CasesView` 通过 provider 获取 controller，单元测试和视图集成测试可分别创建独立实例。
- 用例库、CasesView 和 App 集成相关 36 个测试通过，类型检查通过。

## Latest verification

- `npm run test -- --run`: 204 个测试文件通过，1603 个测试通过。
- `npx vue-tsc --noEmit`: 通过。
- `npm run build`: 通过。
- 构建仍保留基线中的 Lightning CSS `:deep` 提示和 chunk 体积提示。

## Phase 2 workspace/session

- 新增 `apps/web/src/ui/useWorkspaceRuntime.ts`，负责 workspace manager、owner 激活/失效、operation lease、operation id 和 guarded fetch。
- 新增 `apps/web/src/ui/useAppSession.ts`，负责认证 bootstrap、登录 owner 激活、登出、401 会话失效和卸载清理。
- App 保留 feature reset 清单，通过 session callback 执行；session 不 import cases、defects、reports 或 template。
- `useWorkspaceRuntime.test.ts`、`useAppSession.test.ts` 与认证/session/workspace App 测试通过：10 个测试文件，115 个测试。
- `npx vue-tsc --noEmit` 通过。

## 重构护栏

- 后续阶段出现的测试、类型或构建失败，先与本文件中的基线结果比较，再判断是否由本阶段引入。
- 基线提示保持单独记录，不在 App.vue 拆分过程中顺手修复。
- 每个阶段完成后更新本文件的验证记录，并形成独立版本控制节点。

## 2026-11-20 阶段核查

- 已建立阶段提交 `01cbbfd`；本轮将剩余页面/弹层拆分和测试迁移单独记录为下一节点。`App.vue` 共 161 行，脚本约 17 行；三个 controller 分别为 359、363、108 行，均未超过 400 行，也没有反向 import `App.vue` 或互相 import。
- `appWorkspace.ts` 仍为 951 行，承担跨 feature 组装、状态桥接和模型适配，包含较宽的 `presentation` 和仅在 test mode 创建的 `testAccess`。虽然不属于单个业务 controller，也不再直接位于 App，但仍是架构集中点，不能单凭 App 行数宣布整个依赖解耦已经完成。
- 21 个 App 测试文件中有 16 个通过 `getAppState` 读取测试专用暴露状态；App 测试不再直接访问 `setupState`，但测试仍依赖宽的内部状态接口。其他两个组件测试仍读取 `setupState`，不属于本次 App 测试迁移范围。`useCasesProvider.test.ts` 新增并存实例及卸载隔离回归。
- API 移动端计数布局测试改为读取实际样式归属 `ApiExecutionPage.vue`；环境页相关类型导入改为 `appPorts.ts`。所有结构扫描测试保留原断言，未通过删除测试规避失败。
- 本轮最终检查：`npm run test -- --run` 206 文件、1608 测试通过；`npx vue-tsc --noEmit` 和 `npm run build` 通过，`git diff --check` 通过。构建仍有基线已知的 `:deep` 和 chunk 大小提示。
- 尚未完成计划中更严格的测试迁移：API/Web controller 的运行副作用仍主要由 App 集成测试护航，未完全用 fake ports 独立覆盖；Web 全量生命周期及导航 URL/守卫边界测试仍需补齐。`getAppState` 需逐步收窄为 typed fixture 或 UI 断言，不将测试专用接口作为长期业务 API。

## 2026-11-20 执行边界补强

- API controller 新增确认前冻结用例/环境/参数、报告只保存一次、旧确认和 reset 后迟到响应不得写回的独立测试。
- Web run admission 新增项目查找期间快照冻结与 workspace lease 失效不发起 POST 的端口测试；生命周期新增轮询连续失败/恢复、404 保留证据、暂停/恢复、旧控制响应和响应任务 ID 不匹配的回归。修复了恢复、轮询和控制响应可接受错误任务 ID 的缺口。
- 导航新增 push/replace/skip、Web 监控 URL 与异步离开确认的 `popstate` 测试；`afterPop` 现在仅在确认通过、导航实际生效后执行，取消时保持旧页面且不启动错误工作区恢复。
- `npm run test -- --run`：207 文件、1622 测试通过；`npm run build`（含 `vue-tsc --noEmit`）通过，`git diff --check` 通过。构建的 `:deep` 和 chunk 提示与基线一致。
- 仍需逐步将 16 个通过 `getAppState` 访问宽测试接口的 App 测试迁往 controller 或页面行为边界；`appWorkspace.ts` 仍为 951 行，当前补强没有改变这两项架构风险。方案中对应迁移步骤保持未勾选。

## 2026-11-20 最终验收

- `appTestAccess.ts` 现在从 `createAppWorkspace` 推断测试接口类型，不再用 `Record<string, any>` 隐藏夹具错误；相关 App 集成测试补全实际必需的类型字段。`App.webRunStartSnapshot.test.ts` 和 `App.environmentEditorGuards.test.ts` 删除对整份 fixture 的 `as any`。
- Web run 的启动、恢复、轮询和控制规则已有独立端口测试；App 测试继续覆盖账号切换、URL 恢复、确认弹窗和真实页面组合。`LoginScreen.lifecycle.test.ts` 和 `UserManagementView.writeAuthority.test.ts` 改为组件事件及 DOM 行为断言；全库测试不再读取 Vue 私有 `setupState`。
- `appWorkspace.ts` 的返回值仅含页面展示对象和仅在 test mode 创建的测试接口，不再向外暴露未使用的所有 controllers、项目状态及导航内部对象。文件仍有 948 行，但承担的是每个 App 实例一次性的 feature 组装与窄端口接线；保留在此处的 UI 状态/副作用需要后续按实际变更风险持续评审，不能把行数下降当成行为解耦的唯一证据。
- `App.vue` 161 行（script setup 约 17 行）；三个执行/环境 controller 分别为 359、363、108 行；未出现 controller 反向 import App 或相互 import。计划中的所有步骤已验证，但现有 16 份 App 测试仍通过类型化测试入口访问内部状态，这是后续继续收窄测试契约的维护债务，不属于生产 API。
- `npm run test -- --run`：207 文件、1622 测试通过；`npm run build`（含 `vue-tsc --noEmit`）通过；`git diff --check` 通过。构建的 `:deep` 及 chunk 提示与基线一致。

## 2026-11-20 定向维护优化与页面验证

- `useAppNavigation` 现在持有 Web 工作台离开时的状态监听，组装根只提供清理监控 URL 和模式的窄回调。新增切换 automation kind / 导航时的 controller 测试。
- 环境页 App 测试改为验证真实侧边栏 `aria-current`，不再读取内部导航状态；Jira 旧请求被新请求覆盖的规则移至 `useJiraSettings.test.ts` 的假 lease 测试，App 保留读取失败、重试与加载状态的页面验证。读取 App 测试专用状态的文件由 16 降至 14 个。
- `npm run test -- --run`：208 文件、1623 测试通过；`npm run build`（含 `vue-tsc --noEmit`）通过；`git diff --check` 通过。构建仍只有基线已知的 `:deep` 和 chunk 体积提示。
- Vite 已在 `http://127.0.0.1:51731/` 运行。后端代理默认目标 `127.0.0.1:38082` 本次未运行，因此浏览器采用 Playwright 合成 API 响应（包含真实前端默认模板）验证桌面 1280×800、移动端 390×844：环境页 API/Web 切换、返回 API 执行准备、Jira 重新加载后新数据展示均通过；无页面脚本错误或横向溢出。此验证不代表真实后端联调或 Web Runner 执行通过。
