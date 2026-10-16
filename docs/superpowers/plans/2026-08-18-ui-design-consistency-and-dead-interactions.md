> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# UI 设计一致性收敛与假交互拆除 Plan

> **Status: ✅ 全部完成**(2026-08-20。Phase 0 见 PR #2/#3;Phase 1 见 PR #4;Phase 2/3 见「拆壳」系列;Token 收敛收尾见 PR #5 字重、PR #6 A+B 灰阶、PR #7 C 类。硬编码 hex 已清零,仅 tokens.css 注释存文档值。)

> **Goal:** 落地「前端 UI 设计评审」结论(见 `docs/ai-test-platform/ui-design-review.md`),按投入产出比分阶段收敛设计系统一致性、拆除假交互与伪数据,不触碰「思维主体」。

**约束(硬性):**
- 单人维护,UI 可改;**思维主体(domain 数据模型 / DSL / agent 机制 / 仓储语义)不大改**。本 plan 所有改动均落在样式层、纯 UI 组件层、UI 状态层(`ui/`);凡触及 `domain/` 的任务单独标注「需确认」。
- 增量优先,不做一次性重写;每阶段独立可合、可回归。
- 无 emoji(全 lucide-vue-next SVG 图标);推荐有数据/理论支撑后再动手。
- 命令工作目录:`npm`/`vitest`/`vite` 在 `apps/web` 下执行;`rg`/`git` 在仓库根执行。

**影响域分级:**
- **纯样式**:只改 CSS/颜色值,零逻辑风险。
- **UI 状态**:改 `ui/` 组合式或组件内部状态,不碰 domain。
- **UI 组件**:抽/合 Vue 组件模板。
- **需确认**:触及 `domain/` 或 App.vue 状态流,需先确认不影响思维主体。

---

## Phase 0 — 令牌收敛 + 假交互拆除(P0,零 domain 风险,先做)

### Task 0.1 令牌单一来源(纯样式)

- [x] 改 `apps/web/src/styles.css` 的 `:root`(1-38 行):删除被 `tokens.css` 覆盖的颜色/间距 token 定义(`--text-*`、`--surface-*`、`--border-*`、`--active-blue`、`--success-green`、`--el-color-primary` 等),**保留**字体基线(`font-family`/`font-size`/`letter-spacing`/`text-rendering`/`-webkit-font-smoothing`)或把字体基线一并迁到 `tokens.css`。
- [x] 全量替换 `styles.css` 内 `#246bfe` / `#2563eb` / `#3b82f6` / `rgba(36,107,254,*)` → `var(--brand-primary)` / `var(--brand-primary-shadow)`。
- [x] 在 `tokens.css` 补定义缺失 token:`--color-primary`(指向 `--brand-primary`)、`--text-tertiary`、`--surface-sunken`。
- [x] 修复静默失效:`.log-card > a`(`styles.css:2489`)、`.studio-source-actions.is-locked`(`studio-canvas.css:623`)、`.platform-ai-status.is-stopped`(`studio-canvas.css:3655`)。
- [x] 验收:`rg '#246bfe|#2563eb|#3b82f6|rgba\(36,107,254' apps/web/src` 零命中;锁定/停止/链接三态肉眼可见恢复;`npm run build` + `npx vue-tsc --noEmit` 通过;深浅两套视觉抽查无回归。

### Task 0.2 拆除假交互与伪数据(UI 状态 / UI 组件)

- [x] 顶栏全局搜索(`GlobalTopbar.vue` + `App.vue:491/5661`):短期降级为只读或隐藏,或接入站内导航匹配(用 `studioSidebarGroups` 过滤跳转)。推荐先降级,避免死输入。
- [x] 事件时间线 `open-details`(`AutomationCompileStage.vue:41` 传 `() => undefined`):实现定位到用例,或把 `WebCaseAgentWorkspace` 事件行改为不可交互 `div` 并去掉 hover/pointer。
- [x] `useAiConfig.ts:204-279` 伪造「内置」行:改为 `el-empty` 空态,或加 disabled + 「内置(本地展示)」角标。
- [x] 验收:无死输入、无可点无反应、无伪造数据行;相关组件测试(`GlobalTopbar`/`WebCaseAgentWorkspace`/`useAiConfig` 若有)更新或新增。

---

## Phase 1 — 一致性迁移 + 结构拆分(P1,UI 层为主)

### Task 1.1 Web 家族 hex → 语义 token(纯样式)

- [x] 把以下裸 hex 收敛到 `--brand-primary` / `--status-danger` / `--color-success`:`AiGenerationStage.vue:102/113/128`、`WebCaseAgentWorkspace.vue:233/276`、`GeneratedWebCaseTable.vue:176/203`、`NaturalLanguageCaseGenerationStage.vue:49/55`、`WebCaseQualityInsightDialog.vue:104`、`AiReviewInspector.vue:144`。品牌蓝统一 `#1f63c6`。
- [x] 验收:`rg '#[0-9a-fA-F]{6}' apps/web/src/components/workflow` 中无 token 已覆盖语义色(成功/危险/品牌蓝)的残留。

### Task 1.2 侧边栏 accent 色元数据化(UI 组件)

- [x] 删 `PrimarySidebar.vue:325-343` 的 `nth-child` 染色;在 `appSidebar.ts` 的 `SidebarGroup`/`SidebarItem` 加 `accent?: string`(token 名),组件显式读取。
- [x] 验收:增删/重排分组不再静默改色;侧边栏视觉不变。

### Task 1.3 导航单一权威(需确认:轻触 App.vue 状态 + domain 类型)

- [x] 以 `appSidebar.ts` 的 `studioSidebarGroups` 为唯一导航数据源;删除 `App.vue:463` 的 `navItems` 或改为由其派生;`currentNav` 兜底从硬编码 `navItems[3]` 改为显式 `defaultNavKey`。
- [x] 补齐 6 个无入口 NavKey(`requirements/jiraConfig/schedule/versions/charts/panorama`)的入口,或明确标记「后端待接入」并从侧边栏/类型里移除。
- [x] 注:此项触及 `domain/appNavigation.ts`(仅 NavKey 类型)与 App.vue 导航状态,**先确认不破坏 api/automation 的思维主体**再动手。

### Task 1.4 拆 App.vue 巨型组件(UI 组件,纯 template 抽取)

- [x] 把 `App.vue` `#context` 插槽(5703-5999)抽成 `WorkspaceContextPane.vue`;canvas 主区 `v-if` 链按 `activeNav` 用动态组件分发。
- [x] 抽通用「项目 select + 模块按钮列表」组件,消除八个分支的结构重复。
- [x] 注:纯 template 抽取,不迁移任何 domain 逻辑/状态;`<script setup>` 的 5633 行状态暂不动。

### Task 1.5 组件去重(UI 组件)

- [x] `AiReviewInspector` 与 `InterfaceReviewInspector` 合并为共享发现列表组件(数据形状通过泛型/prop 适配)。
- [x] 「阶段 1 测试设计 / 阶段 2 自动化落地」两阶段概念统一为单一组件(文案/视觉取一)。
- [x] el-table 选中同步逻辑抽共享 composable,统一健壮度。

---

## Phase 2 — 健壮性(P2)

### Task 2.1 暗色主题二选一(需确认:涉及主题机制)

- [x] 要么在 `tokens.css` 补 `:root[data-theme='dark']` 全套令牌 + 把 `darkAvailable` 置 true + 加 `matchMedia('(prefers-color-scheme: dark)')` 监听;要么按 roadmap 明确砍掉 dark 分支,避免「看似开了暗色实则没有」的假状态。**先记录决策**再执行。

### Task 2.2 破坏性操作确认统一(UI 状态)

- [x] 为 `JiraConfigView` 删 JQL 映射、`SettingsView` 删模板/字段/类型、`AiConfigView` 删连接、`DefectsView` 删除选中/筛选补齐二次确认,对齐 `CasesView` 的 `confirmDanger`。

### Task 2.3 单例 deps 默认 no-op 修正(UI 状态)

- [x] `useCases.ts:91` `confirmDanger ?? (async()=>false)`、`useReports.ts:49` `downloadTextFile ?? (()=>{})` 等默认值改为显式抛出或提示,不再静默降级。

### Task 2.4 可访问性补齐(纯样式 / UI 组件)

- [x] 焦点环对比度提到 ≥3:1;旧壳 `styles.css` 补 `:focus-visible`。
- [x] `GlobalTopbar.vue:76` 把 `aria-live` 移出按钮,改为 `span role=status`。
- [x] `CaseDeliveryStage` 单选卡片补方向键 + roving tabindex。
- [x] `CasesView`/`SettingsView` icon-only 移序按钮补 `aria-label`/tooltip;`LoginScreen` 提交按钮补 `:focus-visible`。

---

## Phase 3 — 打磨(P3,低优先)

- [x] 提升 10-11px 次要文本、`--text-soft` 正文对比度至 AA。
- [x] `AiConfigView.vue:315` placeholder 去除 `<workspace>/skills/...` 本机路径泄露。
- [x] `WebCaseAgentWorkspace` 头部英文状态码 running/succeeded/blocked → 中文。
- [x] `.bulk-*` 类名语义错位重命名;非标字重 520/650、`.18s` 过渡改走 token。

---

## 依赖顺序与回归策略

- **依赖**:Phase 0 无依赖,可立即做;Phase 1 的 Task 1.1/1.2 依赖 Task 0.1 令牌收敛;Task 1.3/1.4/1.5 相互独立,但建议在 0.1 之后(拆组件前先把 token 收口,避免迁移两遍)。
- **每个 Task 单独提交**:一个 Task 一个 commit/PR,避免混入不可归因的改动。
- **回归**:前端三件套 `npm run build` + `npx vue-tsc --noEmit` + `npm test`(vitest)必须绿;涉及组件改动的补/改对应 `.test.ts`。
- **视觉抽查**:每 Phase 完成后在 `http://127.0.0.1:5173` 走一遍接口用例 → 智能用例设计(Web) → 自动化 → AI 设置,重点看锁定/停止/链接/严重度配色与空态。

## 参考

- 评审文档:`docs/ai-test-platform/ui-design-review.md`
- 决策记录:`docs/ai-test-platform/decisions.md`

## 已知遗留(非本 plan 范围)

- Web runner CI 的 `real Chromium login run supports visual snapshots pause resume and stop` 是既有 flaky 测试:本地 mock server 健康检查随机超时(历次端口 46359/43053/33481),并发/慢 runner 上复现,重跑可过;与前端样式改动零耦合(web-runner node:test 不 import 前端 CSS)。如需修,单独开 issue。
- `rgba(194,213,225,*)` 半透明光边框(WebCaseAgentWorkspace / QualityInsightDialog 边框)仍是字面值,属 alpha 混合非 hex 范畴,未纳入 token 化。
