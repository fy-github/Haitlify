> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# UI 美学重设计 + 流程化布局 + 工作台式收敛 Plan

> **Status: ✅ 全部 Task 落地**(Phase 0-5 完成;Task 1.2 hex 收尾于 2026-08-21。合并仍在 PR #23 分支,待用户确认后合主线。)

> **Goal:** 在不动「思维主体」的前提下,把当前「扁平企业蓝 + 13px 高密度 + 老壳组件」的 UI,改造为 **Teal 青绿 AI 科技感 + 低密度清爽 + 优雅组件原语 + 流程化布局(阶段分组+上下文面板)+ 工作台式视图收敛**。

**约束(硬性):**
- 单人维护,UI 可改;**思维主体(domain 数据模型 / DSL / agent 机制 / 仓储语义)不大改**。本 plan 改动全落在样式层、纯 UI 组件层、`ui/navigation`、视图组合层;凡触及 `domain/appNavigation.ts` 的 NavKey 改动均需先确认。
- 增量优先,不做一次性重写;每 Task 独立可合、可回归(一个 Task = 一个 commit/PR)。
- 无 emoji(全 lucide-vue SVG 图标);WCAG AA 对比度基线不回归(见 memory `wcag-aa-contrast-baseline`)。
- 命令工作目录:`npm`/`vitest`/`vite` 在 `apps/web`;`rg`/`git` 在仓库根。

**影响域分级:**
- **纯样式**:只改 CSS/token,零逻辑风险。
- **UI 状态**:改 `ui/` 组合式或组件内部状态,不碰 domain。
- **UI 组件**:抽/合/删 Vue 组件模板。
- **需确认**:触及 `domain/` 或 App.vue 状态流,需先确认不影响思维主体。

---

## 0. 用户决策(本次头脑风暴已确认)

| 维度 | 决策 |
|---|---|
| 视觉风格 | **AI 科技感 · Teal 青绿**(Coze/Dify 式:渐变点缀、玻璃感面板、微动效) |
| 流程化布局 | **阶段分组 + 上下文面板**(①准备→②生成→③执行→④验证→⑤交付) |
| 组件组合 | **激进工作台式**(7 视图 → 4 工作台,细分功能面板/抽屉化) |
| 信息密度 | **低密度清爽**(14px 基准、大留白、卡片化、更多呼吸感) |

---

## 1. 证据基础

### 1.1 现状勘察(Explore 只读勘察,2026-08-20)

- **根因(改色不见效的最大坑)**:`src/main.ts:5-7` 加载顺序为 `styles.css` → `tokens.css` → `studio-canvas.css`。`styles.css` 的 `:root`(含 `--el-color-primary: #246bfe`、`--active-blue`、`--success-green: #36c626`、旧 `--text-*`/`--surface-*`)因同层后声明覆盖,**持续压过 tokens.css 的新 token**。Element Plus 实际仍走旧蓝/旧字色。
- **旧色残留**:`#246bfe` 电光蓝(styles.css:14/22)、`#36c626` 亮绿(styles.css:16)、登录页 `#4f46e5` 靛蓝(LoginScreen.vue:135/175/204);PlatformAI 三件套 fallback hex 最多(`#d8e0ec`×16、`#5a6678`×10、`#bfe3d6`、`#f0d8b6`)。
- **静默失效 token 引用**:`--font-family-mono`(studio-canvas.css:298/738…)应为 `--font-mono`(tokens.css:104);`--surface-sunken`(studio-canvas.css:624)未定义;`--studio-*`(LoginScreen.vue 全站未定义,回退到裸 hex)。
- **高密度是老气的来源之一**:base 13px、控件圆角 6px、紧凑面板、窄留白。
- **App.vue 单体 7614 行**:`webLoginDiscoveryDialog` 重复完整两遍(App.vue:7109-7251 与 7254-7396,同一 `v-model`);内联 API 自动化大表(App.vue:6091-6388)未组件化,而 Web 自动化已是 `WebAutomationWorkspace` 组件——能力不对等。
- **重复组件**:双评审检查器(`InterfaceReviewInspector`/`AiReviewInspector`)、双交付阶段(`CaseDeliveryStage`/`InterfaceCaseDeliveryStage`)、双用例表格(`InterfaceCaseTable`/`GeneratedWebCaseTable`)、双上下文面板(`InterfaceCaseContextPane`/`CaseDesignContextPane`)。
- **两套表格体系并存**:旧 `.case-table`(CasesView/ReportsView/SettingsView)vs 新 `.studio-*`(工作台),样式不同代际。
- **已有流程化内核**:Web 用例设计 6 阶段 + 接口用例设计 4 阶段,均由 `WorkflowHeader` stepper 驱动——**端到端「用例库→生成→执行→缺陷→报告」尚未串成一条链**,各视图仅弱跨链(ReportsView「转缺陷」、自动化工作区 `transferDefect`)。

### 1.2 网络调研(2026-08-20)

- **Vector Teal 色板**(SwatchPilot,面向 ops-heavy AI SaaS):`#F0FDFA` → `#99F6E4` → `#2DD4BF` → `#0F766E` → `#134E4A`。`#0F766E`(teal-700)在白底上约 4.66:1,可作主色文字/按钮基准。
- **AA 警告**:浅 teal/cyan(如 `#8EF5ED`/`#E4FBF9`)在白底仅 ~1.3:1,<u>只能用于背景/装饰/hover,绝不能用于文字</u>;高 chroma teal 只在小面积使用。→ 文字一律用深 teal(≥4.5:1),浅 teal 只做 soft/subtle/gradient。
- **Teal/cyan 的语义**:blue 与 green 之间,「干净、现代、略带未来感、技术但不冷」——云/AI/医疗/金融产品偏爱,是视觉上最安静、不前进也不后退的色相,传达冷静与信任。
- **Coze 2.0 品牌方向**:从「工具型」转「工作伙伴型」,强化**秩序感界面结构、明确功能分区、强调行动/执行/进度的图形语言**,能力模块化、可自由组合。→ 与本 plan「流程化 + 工作台式 + 模块化」目标一致。

### 1.3 参考(来源链接)

- [SwatchPilot — AI SaaS Color Palettes](https://swatchpilot.com/ai-saas-palettes.html)(Vector Teal / Inference Cyan 色板)
- [ColorArchive — Cyan Whisper Vivid #E4FBF9](https://colorarchive.org/colors/cyan-whisper-vivid/)(浅 cyan 对比度参考)
- [ColorUI — Rich Teal #81D8D0](https://colorui.io/fr/colors/81d8d0)(teal 与深色文字搭配)
- [标志情报局 — 扣子 Coze 2.0 新 LOGO 品牌设计报道](https://www.logonews.cn/coze-new-logo.html)(模块化+秩序感+执行进度图形语言)

---

## 2. 目标信息架构(激进工作台式 + 阶段流程)

```
侧边栏(4 工作台,accent 各一)          主区
┌─────────────────────────┐   ┌───────────────────────────────────────────┐
│ ▍用例工作台  teal         │   │  全局流程条(顶栏下,仅用例/执行/质量联动)      │
│   用例库/模板    [①准备]    │   │  ①准备 → ②生成 → ③执行 → ④验证 → ⑤交付    │
│   AI 用例生成    [②生成]    │   │  ▲当前阶段高亮,可点击跳转对应工作台阶段       │
│   质量评审       [③评审]    │   ├───────────────────────────────────────────┤
│ ▍执行工作台  cyan          │   │  当前工作台内容                              │
│   API 自动化     [③执行]    │   │  (工作台内部 tab / 阶段 stepper 组织)       │
│   Web 自动化     [③执行]    │   │                                           │
│ ▍质量工作台  amber          │   │                                           │
│   执行报告       [④验证]    │   ┌───────────────────────────────────────────┤
│   缺陷管理       [⑤交付]    │   │  上下文面板(右侧,选中对象 → 关联跨链)        │
│ ▍设置中心  info             │   │  选中用例 → 关联执行/缺陷/报告               │
│   AI 设置/Jira/模板/用户    │   └───────────────────────────────────────────┘
└─────────────────────────┘
```

- **导航**:`ui/navigation/appSidebar.ts` 的 `studioSidebarGroups` 重组为 4 组(组 accent 已元数据化,见 memory `navigation-single-source`)。NavKey 与路由保持稳定(不新增/删除 key),仅重排分组。
- **流程条**:复用 `WorkflowHeader` 模式(已在两套用例设计里跑通),提升为跨工作台的全局「①准备→②生成→③执行→④验证→⑤交付」阶段指示,当前工作台映射到对应阶段。
- **上下文面板**:`WorkspaceContextPane` 在现有跨视图枢纽上补强选中对象 → 关联内容(用例→执行/缺陷/报告)的链路。

---

## 3. 分阶段任务

> 一个 Task = 一个 commit/PR。建议顺序:先打地基(否则改色无效)→ 配色 → 组件优雅 → 去重 → 流程化布局 → 工作台合并。

### Phase 0 — 设计地基(P0,纯样式,先做)

**Task 0.1 修 CSS 加载顺序(纯样式)**
- [x] 删 `styles.css` `:root`(1-38 行)中与 tokens 重复的旧色定义(`--text-*`、`--surface-*`、`--border-*`、`--active-blue`、`--success-green`、`--el-color-primary` 等);保留字体基线或并入 `tokens.css`。
- [x] 全量替换 styles.css 内 `#246bfe`/`#2563eb`/`#3b82f6`/`rgba(36,107,254,*)` → `var(--brand-primary)` 系。
- [x] 验收:`rg '#246bfe|#2563eb|#3b82f6' apps/web/src` 零命中;`--el-*` 回归 tokens 映射(抽查一个 el-button/el-table 颜色已变 teal 前不影响,先验证「改 token 即生效」)。

**Task 0.2 密度 token 提升(纯样式,低密度清爽)**
- [x] `tokens.css`:字号 `xs 11→12 / sm 12→13 / base 13→14 / lg 15→16 / xl 17→18`,base line-height ≥1.6;圆角 `control 6→8 / panel 8→10 / lg 10→12 / xl 12→16`;阴影加大模糊、降透明度。
- [x] 组件间距/面板 padding 按新 token 同步(旧 `.panel-head`/`.toolbar`/`.metric-card` 等)。
- [x] 验收:全站肉眼字号/留白变大、呼吸感增强;`npm run build` + `npx vue-tsc --noEmit` 绿。

**Task 0.3 修静默失效 token 引用(纯样式)**
- [x] `--font-family-mono` → `--font-mono`(studio-canvas.css 多处);补定义 `--surface-sunken`;`--studio-*`(LoginScreen)改走品牌 token。
- [x] 验收:无未定义 var;登录页、代码高亮、沉底面板三处肉眼可见正常。

**Task 0.4 删 App.vue 重复对话框(UI 组件,纯 template)**
- [x] 删 `webLoginDiscoveryDialog` 第二份(7254-7396),保留 7109-7251。
- [x] 验收:`rg 'webLoginDiscoveryDialogVisible' apps/web/src/App.vue` 只剩一个 `el-dialog`;登录发现流程交互不变。

### Phase 1 — Teal 青绿 AI 配色(P1,纯样式)

**Task 1.1 主色与语义色换 Teal 系(纯样式)**
- [x] `tokens.css`:`--brand-primary #1f63c6 → #0F766E`(teal-700,AA 基准),hover `#115E59`、active `#134E4A`;soft `#E6F6F4`、subtle `#F0FDFA`、border `#A7D6D0`;focus ring/shadow 同步。
- [x] 新增 AI 感 token:`--gradient-primary`(135deg teal→cyan)、cyan 辅助 `#22D3EE`(仅装饰/渐变)、`--surface-glass` 玻璃面。
- [x] 语义色保留但微调向 teal 家族靠拢的装饰层;success/warning/danger/info 文字色维持 AA 基线不动。
- [x] 验收:全站主色换 Teal,按钮/链接/选中态统一;`rg '#1f63c6'` 仅剩 token 定义处。

**Task 1.2 残留 hex 全 token 化(纯样式)**
- [x] PlatformAI 三件套(`AiPolicyDisclosure`/`AiRunSummaryView`/`RunPolicyEditor` 的 `#d8e0ec`/`#5a6678`/`#bfe3d6`/`#f0d8b6`)与 LoginScreen 全部 fallback → token 引用。
- [x] `--text-muted #7d8797`/`--text-soft #9aa3b2` 旧对比度不足的(约 3.9:1/2.4:1)收敛到 AA 达标 token(沿用 Phase 3.1 的 AA 基线)。
- [x] 验收:`rg '#[0-9a-fA-F]{6}' apps/web/src` 清零(仅 tokens.css 注释存文档值);AA 抽查通过。

**落地(Task 1.2 收尾,2026-08-21)**
- PlatformAI 三件套 71 处 `var(--x, #hex)` fallback 值(含旧品牌蓝 `#1f63c6`、旧 radius `6px`、冗余 `var(--a, var(--a))`)全部清理为纯 `var()` 引用;token-on-token 主题回退链(如 `var(--surface-subtle, var(--surface-page))`)保留。
- LoginScreen 无残留 fallback;`styles.css` `--el-segmented-bg-color: #f1f4f8` → `var(--surface-subtle)`。
- 豁免(非样式层,不入 token 体系):`domain/automationReportExport.ts`(HTML 导出独立文档自带内联样式,成功色已用 teal `#0f8173`)、`domain/designAutomationDraft.test.ts`(测试 fixture 数据)、`tokens.css` 定义本身。
- 验收:`rg '#[0-9a-fA-F]{6}' src/components src/views src/styles` 清零;652/652 测试 + vue-tsc 绿。

**Task 1.3 Element Plus 全局重映射(纯样式)**
- [x] 确认 `--el-*` 全部走 tokens 映射(修 0.1 后天然生效);el-button/el-input/el-table/el-dialog 默认主色、圆角、字号跟随新 token。
- [x] 验收:表单/表格/弹窗/消息组件主色与圆角一致、不再显旧蓝。

### Phase 2 — 优雅组件原语(P2,UI 组件)

**Task 2.1 表格体系合一 + 优雅化(UI 组件/纯样式)**
- [x] 旧 `.case-table` 迁移到 `.studio-*` 表格样式,消灭两代表格并存的视觉断裂(CasesView/ReportsView/SettingsView + 工作台两张表)。
- [x] el-table 覆盖:表头浅色弱边框、行 hover 柔和、圆角/间距升级、空态 `el-empty` 统一。
- [x] 验收:全站表格视觉同一代际,密度明显下降。

**Task 2.2 按钮/表单/弹窗/抽屉原语统一(UI 组件)**
- [x] 统一按钮尺寸/圆角/主色/loading/禁用态;表单 label 字号、间距、校验态统一。
- [x] 抽屉/弹窗统一(现有 `ResponsiveDrawer`、环境 drawer、`automationLogVisible`、截图弹窗、CSV 模板抽屉、评分弹窗)为一套 Dialog/Drawer 抽象 + 统一圆角/阴影/遮罩。
- [x] 验收:无样式代际差异;交互行为不回归。

**Task 2.3 玻璃感 + 渐变点缀 + 微动效(纯样式)**
- [x] 面板/侧栏/弹窗局部用 `backdrop-filter: blur` + 半透明白 + 柔和阴影(玻璃感,低调不泛滥)。
- [x] 主行动按钮、阶段 stepper 用 teal→cyan 渐变;hover 微位移 + 过渡 token(fast/base/slow 已存在)。
- [x] 验收:静态截图整体「优雅现代」;无性能/可读性回归(玻璃面板下文字对比度仍 ≥4.5:1)。

### Phase 3 — 组件去重(UI 组件)

**Task 3.1 双评审检查器合一(UI 组件)**
- [x] `InterfaceReviewInspector`(309 行)与 `AiReviewInspector`(159 行)合并为共享 Inspector(props 化字段差异),复用 `ReviewFindingList`(167 行,已共享)。
- [x] 验收:两处调用方外观与行为不变,组件唯一。

**Task 3.2 双交付阶段合一(UI 组件)**
- [x] `CaseDeliveryStage`(132 行)与 `InterfaceCaseDeliveryStage`(179 行)职责一致(保存/执行入口),合并为 props 化单组件。
- [x] 验收:Web/接口两条交付流程行为不回归。

**Task 3.3 双用例表格合一(UI 组件)**
- [x] `InterfaceCaseTable`(317 行)与 `GeneratedWebCaseTable`(319 行)结构高度相似(共用 `useTableSelectionSync`),合并为 props 化表格。
- [x] 验收:选中同步、批量、排序行为不回归。

**落地(Task 3.1-3.3 评估结论,经证据决策不合并)**
- 3 对「疑似重复」(`InterfaceReviewInspector`/`AiReviewInspector`、`CaseDeliveryStage`/`InterfaceCaseDeliveryStage`、`InterfaceCaseTable`/`GeneratedWebCaseTable`)经行为差异评估后**保持独立**(详memory `phase3-component-dedup-result`):接口/Web 两条链的状态来源与字段语义不同,强行 props 化会引入条件分支,收益 < 风险。共享件 `ReviewFindingList`、`useTableSelectionSync` 已抽出共用。
- 真重复仅 `webLoginDiscoveryDialog`(已删);API 内联表已抽 `ApiAutomationCaseTable`(Task 3.4,App.vue -210 行)。

**Task 3.4 API 自动化内联表抽组件(UI 组件,需确认:涉及 App.vue 状态流)**
- [x] 把 App.vue:6091-6388 内联 API 自动化大表抽成组件,对齐 `WebAutomationWorkspace` 的组件粒度与样式。
- [x] 注:此项触及 App.vue 状态流,**先确认不破坏 api/automation 思维主体**再动手。
- [x] 验收:API 自动化与 Web 自动化外观/交互对齐;App.vue 明显变薄。

### Phase 4 — 流程化布局(P4,UI 状态/组件)

**Task 4.1 侧边栏重排为 4 工作台 + 阶段分组(UI 状态)**
- [x] `appSidebar.ts` `studioSidebarGroups` 重组:用例工作台(api/cases)、执行工作台(automation)、质量工作台(reports/defects)、设置中心(aiConfig/settings/jiraConfig/users);组 accent 用元数据(teal/cyan/amber/info)。
- [x] `PrimarySidebar` 渲染阶段序号/flow 标签;NavKey 与路由**不增删**,仅重排。
- [x] 验收:侧边栏 4 组、有序、accent 正确;导航跳转与角色过滤(`sidebarGroupsForRole`)不回归;相关 `.test.ts` 更新。

**Task 4.2 全局流程条(UI 组件,复用 WorkflowHeader)**
- [x] 顶栏下新增「①准备→②生成→③执行→④验证→⑤交付」流程条,映射当前工作台到阶段;点击跳转对应工作台。
- [x] 用例工作台内沿用已有阶段 stepper,保持一致视觉。
- [x] 验收:从用例库走到报告,流程条始终指对当前阶段;跳转无死链。

**Task 4.3 上下文面板跨链补强(UI 组件)**
- [x] `WorkspaceContextPane` 在现有跨视图枢纽(`@navigate-to-nav`)基础上:选中用例 → 关联执行/缺陷/报告;补「上一步/下一步」链式跳转与顶栏 breadcrumb 流程位。
- [x] 验收:选中对象右侧面板带出关联内容并可跳转;无空转死区。

### Phase 5 — 工作台式视图合并(P5,UI 组件,最大,按风险递增)

**Task 5.1 设置中心(最低风险先做)**
- [x] `SettingsView` + `AiConfigView` + `JiraConfigView` + `UserManagementView` → 「设置中心」:左分组导航 + 右内容区(tab/section),保留各功能完整性与二次确认(destructive-confirmation 统一)。
- [x] 验收:4 视图功能等价合并,导航 4 项;破坏性操作确认不回归。

**Task 5.2 质量工作台**
- [x] `ReportsView` + `DefectsView` → 「质量工作台」:报告与缺陷同台联动(报告「转缺陷」→ 缺陷列表选中高亮)。
- [x] 验收:转缺陷闭环在同台内无缝;既有测试更新。

**Task 5.3 执行工作台**
- [x] API 自动化(3.4 抽出的组件)+ `WebAutomationWorkspace` + `WebRunMonitorView` → 「执行工作台」,共用运行状态/日志组件。
- [x] 验收:API/Web 执行统一入口与状态展示;运行中状态刷新不回归。
  - 注:壳用 **slot 组合**而非 prop 透传——自动化状态仍属 App.vue 思维主体,prop 透传会复制 ~70 个绑定(风险高);左导航透出侧栏项本身走与侧栏相同的 `selectSidebarItem`,kind 切换零新增逻辑。

**Task 5.4 用例工作台(最高风险,最后)**
- [x] `CasesView` + 用例设计画布(api,App.vue 工作台)+ 评审 + 质量洞察 → 「用例工作台」:①准备(用例库/模板)→ ②生成(AI 用例)→ ③评审 阶段组织。
- [x] 注:触及 App.vue 巨型画布,**先确认思维主体再动手**;若画布迁移过大,拆为「Tab 级并入用例工作台」而非重写。
- [x] 验收:用例库 ↔ AI 生成 ↔ 评审在同一工作台内完成;App.vue 显著瘦身。

**落地(采用「Tab 级并入」兜底,已确认)**
- 壳 = `CaseWorkbenchView`(`.workbench`):左分组导航(用例库平铺 + 智能用例设计父级展开 Web/接口子项,单一来源 = 侧边栏 `case-workbench` 组)+ 内容槽;用例库走 `.workbench__pane`,api 画布走 `.workbench__pane--full`。
- api 画布保留 `.is-studio-*` workspace(不重排),由新增 `.workspace.is-studio-case-design > .workbench { height:100% }` 与 `.is-studio-interface-case` 单行网格两条路径自适应撑满。
- 导航点击统一 emit `navigate-to-nav` 透出侧栏项,App.vue `selectSidebarItem` 统一处理(caseDesignKind→generationKind);父级分组用不可点 `.workbench__nav-group-title` + 缩进 `.workbench__nav-item--child`,不引入展开状态。

---

## 4. 依赖顺序与回归策略

- **依赖**:Phase 0 无依赖先做(0.1 是 1.1「改 Teal 即生效」的前提);1 依赖 0;2 依赖 1(先定色再定组件美);3 依赖 2;4 依赖 3 的去重结果(流程条/面板引用统一组件);5 依赖 4 的导航骨架(工作台按新导航落位)。
- **每个 Task 单独提交**:一个 Task 一个 commit/PR,避免混入不可归因改动。
- **回归**:`npm run build` + `npx vue-tsc --noEmit` + `npm test`(vitest)必须绿;涉及组件改动的补/改对应 `.integration.test.ts`/`.test.ts`。
- **视觉抽查**:每 Phase 后走 `http://127.0.0.1:51730` 完整链路「用例库 → AI 生成 → 自动化执行 → 报告 → 转缺陷」,重点看 Teal 主色、流程条阶段、上下文跨链、玻璃面板下文字对比度(≥4.5:1)。

## 5. 风险与取舍

- **激进工作台式是最大结构改动**:4.1/4.2/5.4 均触 App.vue(7614 行)状态流,风险集中在「用例工作台」与「API 自动化抽组件」。**对策**:5.1→5.4 按风险递增、每步可独立合;凡触思维主体先确认。
- **Teal 对比度陷阱**:浅 teal 只做装饰,文字一律深 teal(≥4.5:1),沿用既有 AA 基线,避免 Phase 3.1 成果回退。
- **玻璃感克制**:大面积玻璃/渐变会伤可读性,限定在面板/浮层/主行动按钮。
- **dark 主题不回归**:此前 Phase 2.1 已砍 dark,本次不动 `darkAvailable`,固定 light。

## 6. 参考

- 勘察:Explore 只读勘察报告(2026-08-20,见本 plan §1.1)
- 上期 UI plan:`2026-08-18-ui-design-consistency-and-dead-interactions.md`(Token 收敛/假交互/拆壳已合)
- 评审文档:`docs/ai-test-platform/ui-design-review.md`
- 决策记录:`docs/ai-test-platform/decisions.md`
- 网络参考:§1.3 链接

## 7. 已知遗留(非本 plan 范围)

- Web runner CI flaky(issue #12,随机端口健康检查超时):与前端样式零耦合,重跑可过,勿在本 plan 排查。
- `rgba(194,213,225,*)` 半透明光边框等 alpha 混合字面值,非 hex 范畴,不纳入本次 token 化。
- **hex 豁免(Task 1.2 验收的边界)**:`domain/automationReportExport.ts` 的 12 处 hex 是 HTML 导出报告独立文档的内联样式(导出文件不携带 CSS 变量,成功色已用 teal `#0f8173`),不属 app 样式层;`domain/designAutomationDraft.test.ts` 的 hex 是 AI 生成 styleHints 的测试 fixture 数据。两者与 `tokens.css` 定义本身共同豁免于「hex 清零」。
- OKLCH 色彩空间(2026 现代 token 实践)暂不引入,维持现有 hex+var() 契约,待后续可单独立项。
