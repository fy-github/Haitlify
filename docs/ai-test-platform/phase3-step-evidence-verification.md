> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# 阶段 3：步骤级 Browser Evidence 验收记录

对应评审文档：`docs/reviews/2026-09-09-ai-assisted-web-automation-stabilization-plan.md`（阶段 3，第 402-529 行）。

结论：**验收 1-6 全部通过，且证据链在生产代码路径上成立**（不是只在测试 fixture 里成立）。
本文件记录两轮独立评审改判 FAIL 之后的修复：第一轮 2 个 P0（生产证据链）+ 8 项 P1/P2，以及第二轮对抗性复核指出的残余问题（跨页导航型步骤、同名无 id shadow host、exact 同类不一致、运行期复用拒绝过宽、取证复跑留改动）。

## 一、实现位置

| 能力 | 位置 |
| --- | --- |
| 每个 action 的 before/after 观察 | `server/web-runner/page-locator-discovery.mjs`（`executeActionIr`） |
| 按页面的步骤快照（跨页证据） | 同上（`recordPageSnapshot` → 响应字段 `pageSnapshots`） |
| 执行动作后等待明确 postcondition | 同上（`waitForActionPostcondition`：click/navigate/goto/paginate 等页面变化，其余等观测稳定） |
| 无变化动作的显式诊断 | 同上（`execution.warning = 'no_page_change'`） |
| 定向 + 分区 + 预算的元素采集 | `server/web-runner/page-evidence.mjs`（`browserEvaluateElement`；定向元素同样受整体预算约束） |
| 超预算诊断 `evidence_truncated` | 同上（`diagnostics` → 观察对象 `elementsTruncated`） |
| iframe 采集与 frame 作用域 locator | 同上（`collectFrame`；嵌套时逐段合成 `frame=#outer >> frame=#inner >> …`） |
| open shadow root 递归采集 | 同上（`walkContainer` 递归 `shadowRoot`，逐段合成 `css=#host >> …`，无 id 的嵌套 host 也成立） |
| shadow 内元素可执行性 | 同上（`deepElementFromPoint`：沿 open shadow root 下钻，采集/动作解析/历史 locator 复核三条 closure 都使用） |
| closed shadow root → `unsupported_shadow_dom` | 同上（`closedShadowHosts` 诊断）+ `resolveObservedActionTarget` 分类（带 `scopeUncertain`） |
| unreliable frame → `unsupported_frame` | 同上（`unsupported.frames[].supported=false`）+ `resolveObservedActionTarget` 分类（带 `scopeUncertain`） |
| 结构化 locator（含作用域）持久化 | `withMatchedLocator` 输出 `locatorSpec`（与 locator 同构：`exact`、`framePath`、`shadowPath`） |
| **页面证据的生产装配** | `server/platform/.../ai/draft/WebStepPageEvidenceAssembler.java`（写入 `sourcePointId`、按 `pageSnapshots` 补齐跨页证据、按页面切分 `stepEvidence`） |
| 编译器消费结构化 locator | `server/platform/.../WebCaseCompilationService.serializeLocatorSpec`（逐段作用域前缀 + `exact`，未知策略 fail-closed） |
| 步骤证据通过平台 DTO | `WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse.stepEvidence / pageSnapshots` |
| DSL 校验接受作用域 locator | `WebAutomationCaseValidator.LOCATOR` |

## 二、逐条验收

| # | 验收 | 证据 |
| --- | --- | --- |
| 1 | 点击菜单后断言标题，生成两个不同 step evidence | `phase3-acceptance.test.mjs`：真实跨页导航（`/list` → `/detail`），两个 step evidence 的 `before.pageKey` 不同，菜单证据来自点击前页面、标题证据来自点击后页面 |
| 2 | 初始页没有最终标题仍能编译 | `WebCaseCompilationServicePhase3EvidenceTest`：证据由 `WebStepPageEvidenceAssembler` 从 runner 原始响应装配（列表页 elements 不含标题 locator），编译通过 |
| 3 | 最终页没有初始菜单仍能编译 | 同上：详情页 elements 不含菜单 locator；编译产物包含 `click <menu>` 与 `assertVisible <title>`，两条 step trace 的 pageFingerprint 分别等于两页证据 |
| 4 | DOM 第 150 个元素仍能采集 | `phase3-acceptance.test.mjs`：200 个按钮的页面上 `testId=deep-150` 被采集；预算 30 + 定向词时仍保留该元素，并返回 `elementsTruncated` |
| 5 | iframe 可用 frame locator 绑定，或明确 `unsupported_frame` | 同源 iframe 内按钮被采集（`frame=#inner >> …`，可被 `resolveLocator` 唯一解析）；**嵌套 iframe** 逐段保留外层作用域（`frame=#outer >> frame=#inner >> …`，验收 5c）；跨域 iframe 记为 `supported=false / reason=unsupported_frame`；Action IR 目标不可观测时抛 `unsupported_frame`（带 `scopeUncertain`） |
| 6 | shadow DOM 可采集，或明确 `unsupported_shadow_dom` | open shadow root 内按钮被采集（`css=#host >> …`）且**可真正执行 click**（验收 6c，含 `receivesEvents` 与 postcondition 断言）；**无 id 的嵌套 open shadow host** 逐层合成路径（验收 6d）；closed shadow host 记为 `unsupported_shadow_dom`；Action IR 目标不可观测时抛 `unsupported_shadow_dom` |

postcondition：`phase3-acceptance.test.mjs` 用 400ms 异步更新的页面验证 —— 旧实现会立刻记录未更新的 after 状态，现在等待 `settled` 且 `pageChanged=true`；预期变化但没有变化时记 `no_page_change` + `warning`（验收 9）。

验收 1 的覆盖面在本轮补全为两类跨页形态：**点击型**（点菜单 → 目标页标题，验收 1/7）与**导航型**（第 1 步是 `navigate` route 目标、没有任何元素引用，验收 10）。两类都必须为起始页与目标页各留一份非空快照，否则编译器无法为跨页步骤找到它所在页面的证据。

## 三、第三轮评审提出、本轮修复的问题

| 级别 | 问题 | 修复 | 回归测试 |
| --- | --- | --- | --- |
| P0 | 生产链路写入的页面证据没有 `sourcePointId`（编译器按它分组，真实链路会归到空测试点） | `WebStepPageEvidenceAssembler.appendDiscoveryBundles` 显式写入；服务端不再自行拼装 | `WebCaseCompilationServicePhase3EvidenceTest`（每个 bundle 断言 `sourcePointId`） |
| P0 | 跨页 step evidence 只保存最终页面（跨页点击在生产链路无法编译） | runner 输出按页面的 `pageSnapshots`；平台按页面装配第二条证据，并按页面切分 `stepEvidence` | 验收 7（live runner）+ `assemblesProductionPageEvidenceAndCompilesTheCrossPageCase` |
| P1 | open shadow 内元素采集了但不可执行（`document.elementFromPoint` 只返回 host） | 三条 closure 统一沿 shadow root 下钻（`deepElementFromPoint` / `hitTestAt`），host 自身的 shadow 命中也算命中 | 验收 6c（真实 click 执行 + postcondition） |
| P1 | 无 id 的嵌套 open shadow host 静默漏采 | `walkContainer` 递归时切换 `root` 到当前 shadow root，逐段合成 `shadowPath` | 验收 6d |
| P1 | 嵌套 iframe 丢外层 frame 作用域 | `collectFrame` 逐段合成 `framePath`；`elementScopePrefix` 逐段加前缀；Java 序列化端同步逐段加前缀 | 验收 5c + 平台 locator/locatorSpec 一致性检查 |
| P1 | locator 声明唯一但执行串缺 `exact=true`（`查询` 会命中 `查询用户`） | 采集、历史 locator 复核、Java 序列化三处都在精确匹配成立时输出 `exact=true`；`locatorSpec` 与 locator 同构 | 验收 8（`查询`/`查询用户` 场景 + 非 exact 反例） |
| P1 | evidenceId 未与页面身份绑定 | 运行期：同一 evidenceId 不能跨页面复用（`observedTargets`，报 `action_evidence_mismatch`）；编译期：目标证据必须存在于该步骤 `before` 状态所在页面的证据里 | `rejectsATargetEvidenceThatBelongsToAnotherPage` |
| P2 | 定向采集可绕过整体预算（无截断诊断） | 定向元素同样受 `elementBudget` 约束，丢弃量进入 `elementsTruncated` | 验收 4b |
| P2 | 无变化 click 记 passed | 仍记 `passed`（只读发现不做业务断言），但显式带 `warning: no_page_change` | 验收 9 |
| P2 | unsupported 分类是页面级推断却不着痕迹 | 错误信息说明「目标可能在不可观测子树里，也可能就是不在可观测页面」，并带 `scopeUncertain: true` + `unsupportedScopes` | 验收 5b / 6b |
| P2 | 平台测试消费已提交 JSON，存在 stale fixture 风险 | 载荷内写入 `runnerImplementationHash`（runner 三个实现文件的哈希），runner 侧测试逐字比对；平台测试改为消费 runner 原始响应并走生产装配 | `phase3-evidence-freshness.test.mjs` |

## 三之二、第二轮对抗性复核提出、本轮修复的问题

复核结论（`deleg_8765e296`）：10 项里 8 项真闭环；本轮修复以下残余。

| 级别 | 问题 | 修复 | 回归测试 |
| --- | --- | --- | --- |
| P0（残余） | 导航/路由型跨页步骤仍丢起始页：`recordPageSnapshot` 只记录「被引用的元素」，导航步骤没有元素引用 → 起始页快照为空 → 平台装配器按空快照丢弃该页 → 编译直接失败 | 快照在没有引用元素时记录有界元素集合（`pageSnapshotElementLimit = 20`，优先定向元素），保证每页快照都有可用元素 | 验收 10（导航型 run 的两页快照均非空 + 导航 step evidence） |
| P1（残余） | 同一 shadow root 下两个同名无 id host：`stableCssPath` 用 `parentElement`（ShadowRoot 直接子节点为 null）→ 路径退化 → 两条证据 locator 完全相同、`matchedElementCount` 谎报 1、回放 count=2、Action IR 被 `ambiguous_locator` 拒绝 | `stableCssPath` 沿 `parentNode` 上行（ShadowRoot 的 `children` 可用），兄弟用 `:nth-of-type` 区分；取不到唯一 host 路径时**不再回退父级 shadowPath**，而是记 `unresolvedShadowHosts` 诊断并跳过该子树（fail-closed），`resolveObservedActionTarget` 据此返回 `unsupported_shadow_dom`（带 `scopeUncertain`） | 验收 6e（两条证据 locator 不同且各自唯一；前置事实：ShadowRoot 直接子节点 `parentElement === null`）、验收 6f（深度超过可解析上限的 host：深层子树不采集且进诊断，同一 shadow root 里可寻址的元素仍照常采集） |
| 连带发现（P0 级） | 同一 Action IR 动作被两个执行器执行：探索轮次的 `chooseSafeAction` 直接返回 Action IR 动作，`performSafeAction` 把结构化 target `String()` 成 `[object Object]` → 页面真的被导航到 `/[object%20Object]`（同源检查拦不住相对路径）→ 最终页、pageKey、派生证据全部被污染 | `chooseSafeAction` 只接旧式动作（无 `id` 且非结构化 target）；`navigationTargetFromAction` 显式取 `url`/`target.value`，缺失或非 URL 形状时抛 `unsupported_action_target` | `action ir actions stay with the action executor…`、`a structured navigation target is never stringified into a url`、验收 10（`pageUrl` 与所有 `pageKey` 不含 `[object`） |
| P1（残余） | exact 同类不一致：tab locator（`page-evidence.mjs`）与登录提交按钮 locator（`login-discovery.mjs`）声明唯一却不带 `exact`，且 `resolveTabLocator` 的专用正则不接受 `, exact=true` 后缀（声明与执行不一致） | 两处输出都带 `, exact=true`；`resolveTabLocator` 接受并按下标后缀执行（无后缀沿用历史精确语义）；登录/标签三条既有断言同步到新串 | 验收 12（`查询`/`查询用户`：带 exact 唯一、非 exact 命中 2 个；名称含引号/反斜杠的 tab 也能解析并点击） |
| P2（残余） | 运行期跨页 evidenceId 复用拒绝过宽（同页复用同一 evidence 也被拒） | `observedTargets` 记录「evidenceId → 首次观察页」，只在**跨页面**复用时拒绝；同页复用允许 | 验收 11（同一按钮连续两次 click 均 passed） |
| P2（残余） | 取证脚本复跑留改动（131 insertions/131 deletions），「复跑不留改动」声明为假 | 固定取证端口（`41827`，可用 `PHASE3_RECORD_PORT` 覆盖）+ 落盘前归一化易变计时字段（`durationMs`/`remainingDurationMs` → 0） | 两次连续取证用 `shasum -a 256` 比对（`REPLAY_BYTE_STABLE=yes`） |
| 连带修复 | 路由型意图的 `targetTerm` 是 URL 而不是 evidenceId，编译器拿它做一致性比对会把正确的导航证据判成 `action_evidence_mismatch`；导航步骤又用执行起点 URL 编译（把「去详情页」编译成「打开列表页」） | `ROUTE_ONLY_INTENTS`（`navigate_page`/`assert_url`/`wait_for_url`/`screenshot`）跳过元素证据比对；导航编译优先使用动作自己的 URL（`urlLikeTerm`，仅接受 URL 形状，否则回退起点；该助手在第三轮复核修复中改名为 `routeTargetTerm`/`looksLikeRouteTarget`，见「三之三」） | `assemblesNavigateCrossPageEvidenceAndCompilesTheGotoToTheActionUrl`（断言产物含 `goto <动作 URL>` 且不含 `goto <起点>`） |

## 三之三、第三轮对抗性复核提出、本轮修复的问题（复核 commit `658df47`）

复核结论（`deleg_32e7da12`）：claim 1（导航型跨页不再丢起始页）与 claim 3（同名无 id shadow host fail-closed）**verified=true**；**claim 2（navigate 目标 fail-open）verified=false**，另有 4 条残余。

| 级别 | 问题 | 修复 | 回归测试 |
| --- | --- | --- | --- |
| P0（claim 2） | `executeActionIr` 的 navigate 分支仍用 `String(action.target?.value \|\| action.target \|\| '')` 直接 `new URL` + `page.goto`，绕过了上一轮新增的 `navigationTargetFromAction`：`route.value='[object Object]'` → 真实请求 `/[object%20Object]`、`'not a url'` → `/not%20a%20url`，两次都记 `execution.status='passed'` | 导航目标解析收敛为一条入口 `resolveNavigationUrl`（取 `url`/`target.value` → 拒绝字符串化产物与 `javascript:`/`data:` 等 scheme → `new URL(value, page.url())` → 同源校验），三条执行/校验入口（`validateRequestedActions`、`executeActionIr` 的 navigate/goto、`performSafeAction` 的 direct-navigation）全部改走它；地址断言（`assertUrl`）的期望值同样按此校验。平台侧 `ActionIrValidator.requireRouteTarget` 从「只校验非空」升级为拒绝 `[object` 与危险 scheme（计划期就挡住，运行期仍 fail-closed 兜底） | runner `an action ir navigate step fails closed instead of opening a garbage address`（HTTP 侧记录请求路径，断言从未请求垃圾地址；`[object Object]`/`javascript:` → `unsupported_action_target`，缺 `value` → `invalid_action_ir`）、`a relative route value is accepted while string artefacts and schemes are rejected`；平台 `structuredActionValidationRejectsStringifiedRouteTargetsButKeepsRelativePaths` |
| P1（残余 R1，方向相反） | 目标解析过窄：合法的同源相对路径 `detail`、`users/detail`、`?q=1` 被 `navigationTargetFromAction` 拒绝 | 策略改为「宽进严出」：只要能被 `new URL(value, page.url())` 解析、同源、scheme 合法就接受；平台侧 `urlLikeTerm` → `looksLikeRouteTarget`/`routeTargetTerm`（绝对地址、`/ # ? ./ ../` 前缀、ASCII 路径片段），两侧口径一致 | 同上两个用例（覆盖 `detail`/`users/detail`/`?q=1`/`#/detail`/`./detail`/`../detail`） |
| P1（残余 R2） | 平台 `assert_url` 用 `executionStartUrl` 编译：「断言已到详情页」被静默编译成「断言还在列表页」（与 navigate 分支同型） | `assert_url` 分支改用 `routeTargetTerm(intent.targetTerm())`，缺失才回退起点 URL | `assemblesNavigateCrossPageEvidenceAndCompilesTheGotoToTheActionUrl`（断言产物含 `assertUrl <动作地址>` 且不含 `assertUrl <起点>`；取证脚本的导航型 run 新增 `assert-detail-url` 步骤） |
| P1（残余 R3） | 起始页 0 个可采集元素时该页快照为空 → 平台装配器丢页 → 跨页用例编译失败 `step_evidence_missing(go-detail)`（失败信息误导） | runner 对空元素页显式标记 `elementsEmpty: true` + `elementsEmptyReason`；`appendStepDerivedBundles` 不再丢弃空元素页，保留 bundle 并带诊断——路由型步骤只需要页面身份，元素型步骤会在解析 locator 时拿到明确错误 | 新增第 4 个真实载荷 `phase3-runner-plain-start-response.json`（在无元素静态页 `/plain` 上真实取证）+ 平台 `keepsAnEmptyStartPageBundleSoRouteStepsStillCompile`；`skipsRedundantOrUnidentifiedPageSnapshots` 更新为新行为 |
| P2（残余 R4） | runner 的步骤级分类（`unsupported_frame`/`unsupported_shadow_dom`/`unsupported_action_target`/`invalid_action_ir`/`action_evidence_mismatch`/`assertion_failed`/`observation_failed`/`settle_timeout`）不在平台稳定表 → `normalizeCategory` 归一化成空串，被降级成「runner 调用异常」；平台 DTO 丢 `unsupported` 诊断（`frames`/`closedShadowHosts`/`unresolvedShadowHosts`） | 稳定表补齐 runner 全部步骤级/Action IR 分类；runner 响应与平台 `PageLocatorDiscoveryResponse` 增加 `unsupported` 透传；前端失败分类标签与类型补齐 | `PlaywrightRunClientTest.keepsStepLevelEvidenceCategoriesInsteadOfDroppingThem`、`WebAutomationLocatorDiscoveryEvidencePayloadTest.preservesUnsupportedDiagnosticsAndPageSnapshotsFromTheRunnerPayload` |

## 三之四、第四轮对抗性复核期间自查发现并修复的缺陷（同为边界信息丢失/误分类）

第四轮复核（`deleg_f5dfedb9`）的探针命中以下问题，全部由自查复现、定位、修复，并在真实 HTTP 边界上留证。

| 级别 | 问题 | 复现 | 修复 | 回归测试 |
| --- | --- | --- | --- | --- |
| P0 | `runner-core.mjs` 的 `const headerValue = (headers = {}, name)` 默认值只对 `undefined` 生效；平台 DTO 会把未配置的可选字段序列化成 `null`（`extraHeaders: null`）→ `Object.entries(null)` 抛 `TypeError: Cannot convert undefined or null to object` → 整个取证请求以 HTTP 503 返回，平台再降级成 `WebAutomationRunnerUnavailableException`（真实原因完全丢失） | `curl -X POST …/page-locator-discovery -d '{"…","extraHeaders":null,…}'` → `{"message":"Cannot convert undefined or null to object"}`；直调栈指向 `runner-core.mjs:324 headerValue` ← `buildBrowserContextCookies` ← `discoverPageLocators` | `Object.entries(headers || {})`（显式 null 兜底） | `runner-core.test.mjs`：`buildBrowserContextCookies tolerates an explicit null extraHeaders from the platform DTO`；HTTP 侧复测 `extraHeaders: null` → **200 + 真实取证载荷** |
| P1 | runner 的 HTTP 错误层只读 `error.category`：fail-closed 错误只设 `failureType`（`unsupported_action_target`/`unsupported_frame`/`unsupported_shadow_dom`/`assertion_failed`/`action_evidence_missing`/`action_evidence_mismatch` 等）→ 这些分类被丢成 503「runner 不可用」，平台的分类稳定表也永远拿不到它们 | `curl … -d '{"actions":[{"id":"go-bad","type":"navigate","target":{"kind":"route","value":"[object Object]"}}]}'` → 旧：`{"message":"navigation target is not a URL: [object Object]"}`（无 category、503） | HTTP 层 `safeCategory(error?.category \|\| error?.failureType)`；`EVIDENCE_CATEGORIES` 补齐步骤级/Action IR 分类 | `web-runner-server.test.mjs`：`keeps a failureType-only error classified instead of a generic 503`；HTTP 复测 → **422 `{"message":"…","category":"unsupported_action_target","retryable":false}`**；平台侧实测 `WebAutomationEvidenceException category=[unsupported_action_target]`（旧为 `WebAutomationRunnerUnavailableException`） |
| P1 | DSL 校验层与编译/执行层口径不一致：`WebAutomationCaseValidator.isUrlTarget` 只认 `/` 开头与绝对地址，把编译器已认可的合法同源相对路由（`detail`、`users/detail`）判成「缺少合法 URL 目标」 | 平台探针：`assertUrl /detail` 通过、`assertUrl detail` 报 `缺少合法 URL 目标` | 与编译器 `looksLikeRouteTarget` 对齐（`/`、`http(s)://`、`{{`、`*`、ASCII 相对路径、`?`/`#`/`./`/`../` 前缀）；仍拒绝含空白/中文的自然语言描述 | `WebAutomationCaseValidatorTest`：`keepsRelativeRouteTargetsValidWhileRejectingProse` |

复核方三条 platform 探针（`platform.navigateGarbage` / `platform.closedShadow` / `platform.dto.noActions`）此前**全部**卡在上述 null 崩溃上，因此那一轮它们对 DTO/分类的结论无效；修复后重跑：`platform.dto.noActions` 返回 `unsupported={"iframes":…,"frames":[],"closedShadowHosts":[],"unresolvedShadowHosts":[]}`（诊断确实到达 DTO），`platform.closedShadow` 得到 `WebAutomationEvidenceException category=[action_evidence_missing]`。

（复核方 `R3.plain-start` 探针报出的 `action_evidence_mismatch` 不是缺陷：它把测试点写成 `point-phase3-record`，而该载荷的步骤证据带的是 `sourcePointId=point-phase3-plain`——测试点与步骤证据不一致必须 fail-closed；用 `point-phase3-plain` 编译成功。）

## 三之五、第四轮对抗性复核（`deleg_f5dfedb9`，复核 `6e443b2`/`0d8ac45`）判 FAIL 的三条残余，本轮修复

第四轮复核结论：claim 2（对象型非法目标）、R1、R3、R4 与我方两处边界修复（`5b2accc`）经其独立复现确认为真；但它另找到三条残余缺陷，判定 FAIL。三条都在本轮修复：

| 级别 | 问题（复核方复现） | 为什么是真缺陷 | 修复 |
| --- | --- | --- | --- |
| high | 字符串型描述文字仍 fail-open：`route.value='not a url'` → HTTP 200 + `stepEvidence[0].execution.status='passed'`，页面服务真实收到 `GET /not%20a%20url`；`'威胁情报'` → `/%E5%A8%81%E8%83%81%E6%83%85%E6%8A%A5`；`'a b'` → `/a%20b`。同类问题在 explore/legacy（无 id）`direct-navigation` 入口同样存在（轮次记 `ok`，失败原因被引向 `observation_stagnated`） | 「宽进严出」只做了拒绝字符串化产物与危险 scheme，没有拒绝**描述文字**：浏览器会把空格/中文百分号编码成合法 URL，于是「把页面名当成地址」被静默执行并记通过；且平台 `looksLikeRouteTarget` 拒绝同一个值 → 层间判定相反 | runner 新增 `isRouteShaped`（与平台同口径）：含空白、纯自然语言（无 `/ # ? ./ ../` 前缀、非 ASCII 路径片段）一律 `unsupported_action_target`；`resolveNavigationUrl` 默认要求路由形状，IR 执行入口（navigate/goto/assertUrl）与 `validateActionIr` 全部覆盖；legacy `direct-navigation` 允许裸标签但**必须**被 route hints/history 证明，否则同样 fail-closed |
| medium | `assert_url` 仍可静默错编译：结构化动作目标 `'设备详情页'` → 编译产物 `goto …/detail` + `assertUrl …/list`，`success=true`、`errors=[]`（断言从「已到详情页」变成「还在列表页」） | 回退到 `executionStartUrl` 对**结构化**动作是语义替换，不是兜底；报错比静默改变断言含义安全 | 平台侧：`intent.structured()` 且 `routeTargetTerm` 为空 → 不再回退，直接编译失败（`action_evidence_mismatch: …（intent=assert_url, targetTerm='设备详情页'）`）；`navigate_page`/`wait_for_url` 同样处理；`ActionIrValidator.looksLikeRouteTarget` 与 runner 同口径，计划期就拦下 |
| medium | closed shadow root 漏检：宿主是普通 `<div>`、或宿主带 light-DOM 回退文本/子节点时 `closedShadowHosts=[]`、`shadowDom=false`，失败被降级成普通 `action_evidence_missing`（阶段 3 口径要求记成 `unsupported_shadow_dom`） | DOM 侧读不到 closed shadow root（`element.shadowRoot === null`），启发式（自定义元素 + 空 + 可见）天然漏检 | 新增 CDP 精确检测 `detectClosedShadowHostsViaCdp`（`DOM.getDocument{pierce:true}` 能看到 closed shadow root，逐宿主给出 `hostPath`/`tagName`/`source:'cdp'`），与启发式结果合并；非 Chromium/CDP 不可用时回退启发式并在 `unsupported.closedShadowDetection` 标明 `cdp`/`heuristic`；启发式本身补「light-DOM 文本与渲染文本不一致」形态 |

复核方同时确认的两处已闭环（其原文结论）：`5b2accc` 的显式 `null` 兜底（整包 null 字段 → 200 + 真实取证载荷）与 DSL 校验器口径对齐（`assertUrl detail` 通过、中文/空白仍拒）。

**复核方计数差异说明**：其全量数字为 554/0/0/5，我方为 544/0/0/5，差的 10 条是它自建的两个临时探针类（`Adv4CompileProbeTest` 6 条 + `Adv4RunnerCategoryProbeTest` 4 条）在 `target/test-classes` 里的**残留 .class**——删除源码不会删除已编译类，surefire 仍会执行。两侧 `failures=0/errors=0` 一致。

## 三之六、第五轮对抗性复核（`deleg_475407a3`，复核 `921d369`）判 FAIL 的 7 条残余，本轮修复

第五轮复核经独立复现确认：验收 1、2（跨页两个不同 step evidence、初始页无最终标题仍可编译）为真；结构化动作 fail-closed、CDP closed shadow 检测、合法相对路径不过度拒绝、legacy 自然语言路径、DSL 校验器词表完整性、跨页复用拒绝都与自测一致；它自跑 runner 全量 `EXIT=0`、platform 全量 `EXIT=0`、`phase3-acceptance.test.mjs` 20/20、取证脚本 `PHASE3_RECORD_OK`。它另找到 7 条同类残余（信息丢失 / 静默降级 / 层间口径不一致），逐条修复：

| 级别 | 问题（复核方复现） | 为什么是真缺陷 | 修复 |
| --- | --- | --- | --- |
| medium | legacy `direct-navigation` 的 hint 匹配是对**百分号编码后的地址**做子串匹配：`routeHints:['detail']` + target `'detail page'` 真的 `GET /detail%20page`；且两个闸门看到的 hint 集合不同（`validateRequestedActions` 用 `routeHints \|\| navigationHints`，`performSafeAction` 只用 `routeHints \|\| normalizedRouteHints(intent)`）——同一动作校验期放行、执行期被拒 | 命中 hint 只能说明「这个标签对应一条已知路由」，导航目标必须是**那条路由**；两闸门口径不一致会让同一动作在两个阶段得到相反结论 | 新增 `resolveLegacyNavigationTarget`：路由形状 → 直接解析；否则用紧凑比较（去空白/斜杠）在 routeHints+history 里找标签→路由映射，命中则导航到该 hint 指向的路由，未命中 fail-closed。两个闸门改为读同一份 `legacyNavigationHints`（routeHints + navigationHints + 计划路由线索） |
| medium | 同源 iframe（含 `srcdoc`）内的 closed shadow root 漏检：CDP 树里有 `contentDocument` 但不递归，`closedShadowHosts=[]`，失败被降级成 `action_evidence_missing`（阶段 3 要求记 `unsupported_shadow_dom`） | iframe 内的封闭 shadow 同样是封闭 shadow，漏检会改变失败分类与验收 6 的判定 | `detectClosedShadowHostsViaCdp` 递归 `contentDocument`；条目带 `framePath`（如 `#inner-frame`），`hostPath` 仍相对其所在文档 |
| low | CDP 结果没有剔除启发式假阳性：没有 shadow root（未 upgrade 或从未 attach）的自定义元素被报成 closed 宿主 → 无关的目标缺失被误判成 `unsupported_shadow_dom` | 分类错误比漏检更难排查，且会掩盖真实失败原因 | CDP 可用时以其为权威结果，启发式仅在 CDP 不可用时回退；`unsupported.closedShadowDetection` 明标 `cdp`／`heuristic` |
| medium | IR `waitForUrl` 不匹配或超时抛裸 `Error`（无 `failureType`/`category`）→ runner HTTP 层映射成 503「runner 不可用」，业务断言失败被降级成基础设施异常 | 分类丢失会让平台把断言失败当环境故障，重试策略、失败报告与「runner 不可用」告警全跟着错 | `waitForUrlMatch` 的超时/空期望错误统一带 `failureType=category='assertion_failed'` |
| medium | 失败响应丢弃全部步骤证据：`error.stepEvidence` 有值但只有成功响应才序列化，一次「第一步 passed、第二步 failed」的取证在返回体里看不到任何 step evidence | 阶段 3 的核心产物就是逐动作 before/action/after，而最需要证据的恰恰是失败用例 | 服务器 `safeErrorDetails` 透传 `stepEvidence` 与 `pageSnapshots`；discovery 的错误对象同时挂 `pageSnapshots` |
| medium | DSL `/runs` 空期望值恒真：`assertUrl ''` → `currentUrl.includes('')` 为真 → 步骤记 `passed`；`assertText` 空期望、`waitForUrl` 空期望同样立即“通过” | 空期望不构成断言，记 passed 等于把「没验证」当「验证通过」 | `urlMatchesExpected` 对空期望返回 false；`waitForUrlMatch` 空期望直接抛 `assertion_failed`；`assertUrl`/`assertText` 空期望抛 `assertion_failed` |
| low | `assert` 别名形状（`assertion.type=asserturl`/`assert_url`）的计划期校验走 `requireEvidenceTarget` 而非 `requireRouteTarget`，且 `targetTerm=''` 让报错定位不到原值 | 同一语义的两种形状必须走同一条闸门；报错不带原值等于让人对着计划猜 | `ActionIrValidator` 把 `asserturl`/`assert_url` 归入 `requireRouteTarget`，断言类型词表补 snake_case；编译器 `buildActionIntentIR` 在顶层 target 缺失时读 `assertion.target`/`semanticTarget`（普通 `{type:'assertUrl'}` 形状仍读顶层，避免把普通形状读空）；runner 侧同步：`canonicalAssertionType` 归一别名、执行映射使用规范动作类型（此前会拼出 `assertAssert_url`）、`assert_url` 别名同样要求路由形状 |

## 三之七、第六轮对抗性复核（`deleg_7afe6bc9`，复核 `ab91f2b`）判 FAIL 的 D1–D8，本轮修复

第六轮复核经独立复现确认：**6 条验收全部 pass**（复核方自建夹具 `127.0.0.1:19515` + runner `19512`，跨页 IR 返回 200 且 `stepEvidence` 两条分属 `/list`、`/detail`），**第五轮 7 条残余全部 verified pass**，自跑 runner 全量 `218/218 fail 0`、platform 全量 `EXIT=0`。它另提出 8 条同类缺陷（D1/D2 为 medium），逐条处置：

| 编号 | 问题（复核方复现或代码路径） | 为什么是真缺陷 | 修复 |
| --- | --- | --- | --- |
| D1 medium | 元素型动作带 `{kind:'route'}` 目标：平台 `ActionIrValidator` 放行，runner 解析器对 route 目标返回 `undefined`，执行期 7 处解引用抛 `TypeError`；该错误无分类 → HTTP 503「runner 不可用」，`stepEvidence`/`pageSnapshots` 一起丢 | 分类丢失 + 证据丢失 + 类型错误被当成基础设施故障，三个问题叠在同一路径上 | 四层同改：① 平台 `requireEvidenceTarget` 明确拒绝 `kind:'route'`；② runner `validateActionIr` 同样按动作类型拒绝 route 目标；③ 执行器新增 `requireElementTarget` 兜底（不依赖校验完整性）；④ 每动作 catch 给未分类错误兜底分类 `action_failed`（runner `EVIDENCE_CATEGORIES` 补齐），服务器 503 分支也序列化 details |
| D2 medium | DSL `assertUrl` 用 `currentUrl.includes(expectedUrl)` 子串匹配：`startUrl=/listing` + `assertUrl /list` 断言**通过**（`http://host/list` 是 `http://host/listing` 的前缀子串） | 与第五轮修掉的 `''` 空期望同族：地址断言在错误路由上静默通过 | `assertUrl` 改走 `urlMatchesExpected`（origin + 路由精确比较，`/list` 允许 `/list/detail` 嵌套、拒绝 `/listing`），与 `waitForUrl` 完全同一口径 |
| D3 low | 值来源词表不对称：平台接受 `testdata`/`runtimevariable`/`maskedsecret`，runner 只解析 `literal` → 平台放行的计划在取证期 422 | 层间口径不一致会把「计划问题」伪装成「runner 问题」 | runner `resolveActionValue` 对齐词表：`literal`/`testdata` 用内联值；`runtimevariable`/`maskedsecret` 从请求 `variables`/`maskedSecrets` 解析；拿不到来源时抛分类错误并指名到变量（`invalid_action_ir` + key），绝不把 key 当值填入。平台 `value.kind` 词表保持不变（编译期把运行时变量编成 `{{变量}}`），并有定向用例锁两份词表 |
| D4 low | 登录成功判定目标为空时被吞成「通过」：`setup-login-success` 的空目标先生成步骤，底层错误被宽松 catch 吞掉，且 `runtime.loginPageUrl` 缺失时 `hasLeftLoginRoute(undefined, …)` 直接为真 | 登录失败会静默继续（fail-open），且该分支的「成功」没有任何可判定的语义 | 空判定目标改为可判定的 `waitForLoginExit`：等页面离开登录路由，超时抛 `assertion_failed`；`loginPageUrl` 未知时直接抛「无法验证」而不是放行；非空判定目标的宽松语义保留，但同样要求登录页地址已知 |
| D5 low | DSL `goto` 空目标被 Playwright 的 `baseURL` 解析成环境首页，步骤记 `passed` | 与 `assertUrl ''` 同族：没指定地址被当成「打开环境首页」 | `goto` 解析后目标为空即抛 `invalid_action_ir`（同时补 `{{变量}}` 缺失的分类：`Missing runtime variable` 现在带 `failureType`） |
| D6 low | `/runs` 证据清洗按 key 屏蔽 `value`，把**路由目标**的 `value`（地址）一起剔掉：`stepEvidence.resolvedTarget` 只剩 `{kind:'route',visible:…}` | 证据链断裂：复核方拿不到「点击前/后在哪条路由」 | `isRouteTargetObject` 判定：`kind==='route'` 且 `value` 是字符串时保留（地址与 `pageKey` 同级可见），其余 `value`（输入值）继续屏蔽；`runtime`/`variables` 等敏感键照旧整表剔除 |
| D7 low | 平台 `PlaywrightRunClient.safeEvidenceDetails` 不转发 `stepEvidence`/`pageSnapshots`：runner 失败响应已带这两字段，平台映射成 `WebAutomationEvidenceException` 时丢掉 | 平台侧阻塞报告只剩分类，看不到失败那一步的 before/after | 按字段白名单转发步骤证据并限长（20 条），非白名单字段（如可能夹带输入值的字段）不透出；未分类失败的 503 分支同样把 details 透出到 `WebAutomationRunErrorResponse` |
| D8 low | `paginate` 分类不一致（复核方据代码路径推断 IR 侧为 `observation_failed`、DSL 侧为 `pagination_not_changed`） | 同一语义两种分类会让统计与重试策略对不上 | 实测澄清：IR 与 DSL 的「点击后列表未变化」**本来就都是** `pagination_not_changed`（`waitForObservedPageChange` 的 throw 分类即此，复核方引用的 `observation_failed` 属于「观测本身失败」的另一条分支）。本轮把该分类同时补进平台 `STABLE_CATEGORIES` 与 runner `EVIDENCE_CATEGORIES`，并补 runtime 用例锁住行为 |

同族加固（复核方未提，自查发现）：`resolveStepTargetEvidence` 在「匹配数 ≠ 1」时统一带 `matchedElementCount`，`ambiguous_locator` 的消息由笼统的「不再唯一」改为「不可解析为恰好一个元素（matched N）」——count=0 与 count=2 的诊断不再混同。

## 三之八、第七轮对抗性复核（`deleg_26620a50`，复核 `1178433`）新增缺陷 N1–N10，本轮修复

第七轮复核经独立复现确认：**6 条验收全部「通过（独立复现）」**（复核方自建夹具 `127.0.0.1:19411`/`19412` + 真实 Chromium：跨页 `/list`→`/detail` 两条 `stepEvidence` + 2 条 `pageSnapshots`；`Adv7CompileProbe` B1/B2/B6 编译初始页无标题、最终页无菜单的用例；`/deep` 162/201 与 `deep-150`；同源 iframe 200 与跨源 `unsupported_frame`；open shadow 200 与 closed shadow `unsupported_shadow_dom`），**D1–D8 逐条「已闭环」**，自跑 runner `227/227 fail 0`、platform `551/0/0/5 EXIT=0`（与本轮记录一致）。它另提 10 条新缺陷（N1/N2/N3 为 high），逐条处置：

| 编号 | 问题（复核方复现或代码路径） | 为什么是真缺陷 | 修复 |
| --- | --- | --- | --- |
| N1 high | DSL `paginate` 的 `waitForFunction` 谓词引用了模块作用域的 `readPaginationSnapshot`：Playwright 只序列化谓词本体，浏览器侧抛 `ReferenceError`，被 catch 一律报成 `pagination_not_changed`——**真实翻页也判失败**；单测桩住 `waitForFunction` 恰好掩盖了它 | 观察手段自身报错被当成业务结论，且方向是「把成功判失败」 | 谓词改为自带全部实现（内联列表/页码/loading 逻辑）；catch 只在 `error.name === 'TimeoutError'`（或 Playwright 的 `Timeout <n>ms exceeded`）时判 `pagination_not_changed`，其余错误按 `observation_failed` 抛出；单测桩改为真实 `TimeoutError`，并新增真实浏览器用例（真翻页 passed / 不变化 failed） |
| N2 high | DSL 动作派发只有 `if` 链、无 default：未知动作名（拼错、错拼大小写）走完链路静默 `return`，整步记 **passed**；平台词表放行的别名（`scroll_into_view`、`wait_for_visible`）runner 未实现，同样静默通过 | 「什么都没做但全绿」：平台说支持、执行器不执行 | runner 侧建立规范动作集合 + 别名归一（`scroll_into_view`/`wait_for_visible`/`wait_for_url`/`assert_*` → 驼峰），派发前归一；未命中即抛分类错误 `unsupported_action`（两侧词表同步补该分类）。真实 DSL 复测：别名两例 passed、`clickk` failed（`Unsupported step action: clickk`） |
| N3 high | 登录成功判定按整串 URL 比较：登录失败跳 `/login?error=1`、或 GET 表单提交后停在 `/login-query?username=…`（仅 query 变化）都算「已离开登录路由」→ 记 passed | 与 D4 同族的 fail-open，只是更隐蔽（路径没变） | `hasLeftLoginRoute` 改为只比 origin + 路由（`#/…` 用 hash 路由），query/hash 参数差异不再算离开；登录页地址未知即失败。真实 DSL 复测：GET 表单回同一路径带参 → 步骤 **failed**（`login did not leave the login route: still on "…/login-query?…"`，消息脱敏） |
| N4 medium | `startUrl` 为空时自动补的 `goto` 取「被选中的目标页 bundle」：跨页用例从**最终页**开始（`goto /detail`），随后去点只存在于 `/list` 的菜单，编译仍 `success=true` | 产物不可执行却报编译成功 | 新增 `resolveFallbackStartPageUrl`：按计划动作顺序取第一个动作 `before.pageUrl`，取不到才退回选中 bundle；跨页定向用例加断言 `steps` 以 `goto http://127.0.0.1:41827/list` 开头 |
| N5 medium | IR `paginate` 的变化检测用 `pageFingerprint`，而指纹的元素键不含文本：只换行数据的真实翻页 before/after 指纹完全相同 → 报 `pagination_not_changed` | 与 N1 同类：真实成功被报成失败 | 观测里新增浏览器侧计算的 `listSignature`（列表/表格可见文本 + 当前页码，有界 4000）并计入 `pageState.contentSignature`；IR 翻页比较与 postcondition 的「是否变化」都改看它（不扩大共享 `pageFingerprint`，避免影响 handoff/resume 的相等判定）。真实 IR 复测：`before="1|第一页行一"` → `after="2|第二页行一"`，200 |
| N6 medium | 平台 `ActionIrValidator` 接受 `runtimevariable`/`maskedsecret`，但采集请求 DTO 没有变量字段：该计划只能在不带来源的情况下下发，runner 必 422，且异常在取证循环内无 catch → 整批运行失败 | 计划级问题伪装成基础设施故障 | 采集前新增 `DiscoveryValueSources.unsupportedDiscoveryValueSource`：指名第一个需要运行时值来源的动作并抛 `WebExplorationBlockedException`（该点 blocked，其余点继续，原因含 `value_source_unavailable`）；定向用例锁「内联 literal/无值动作照常下发」 |
| N7 low | 分类规范化把 runner 的稳定分类折叠：`unsafe_action`/`locator_persistence` → `unsupported_dom`、`navigation_failed` → `wrong_page`（平台 `PlaywrightRunClient`、`WebCaseAgentDispatcher`、前端 `webCaseAgentRepository` 三处同款） | 诊断失真，并触发错误的「需要人工接管」建议 | 三处同改：稳定分类原样保留，只折叠不在稳定表里的历史词（`page_error`/`no_safe_action`/`unsafe_repair_category`）；用例改为断言分类不被改写 |
| N8 low | `testdata` 只给 `key`（指向外部数据集）时，两侧都把键名当值填入：页面收到的是 `deviceName` 这样的键名，步骤却 passed | 静默填错数据 | 两侧 fail-closed：runner `resolveActionValue` 要求内联 `value`（否则 `invalid_action_ir` + 指名键）；平台 `validateValue` 与编译器 `structuredValueSource` 同步要求内联 `value`。真实 IR 复测：422 `invalid_action_ir`，消息 `Action IR testdata value has no inline value: deviceName` |
| N9 low | 采集器给无标签 `<select>` 用 option 文本合成 accessible name 并据此生成 `role=combobox[name="路由器 交换机"]`；回放期 Playwright 按 ARIA 计算名字为空 → 0 命中（采集期「唯一」与回放不一致） | 证据里承诺的唯一性在回放期不成立 | 表单控件（`input`/`textarea`/`select`）在名字链里不再回退到内容文本，无标签时退回 `testId`/CSS。真实 IR 复测：`accessibleName=""`、`locator="css=#kind"`、用该证据 `select` 成功 200（同一证据可回放） |
| N10 low | 脱敏把单字符步骤值也当 synthetic_secret：报文里所有同名子串被替换（`e[REDACTED]ceeded`、`#does-not-e[REDACTED]ist`） | 失败诊断正是用来定位问题的，被打成乱码等于没有 | `collectSecrets`/`redactText` 加长度下限（≥4 才进脱敏集合与替换）；`authToken`/密码等长值照旧。真实 DSL 复测：1 字符值 + 后续失败步骤 → 消息完整可读（`waiting for locator('#does-not-exist')`） |

同族加固（复核方未提，自查发现）：`WebCaseAgentDispatcher.normalizeFailureCategory` 与前端 `webCaseAgentRepository.normalizeFailureCategory` 各有同一份折叠表——N7 的三处一起改，避免「平台改了、前端仍显示旧分类」。

## 三之九、第八轮对抗性复核（`deleg_4d5ec36f`，复核 `8e37289`）判 FAIL 的 F1–F6，本轮修复

第八轮复核独立复验的结论：**N1、N2、N4、N5、N6、N8、N9、N10 共 8 条判「已修复」**；**N3、N7 只判「部分修复」**（各差一处）；**6 条验收与抽样的 D1–D8 全部仍成立**（复核方自跑 runner `233/233 fail 0`、platform `552/0/0/5 exit 0`、web `139 files / 687 tests exit 0`，与本轮记录一致）。它另提 6 条新缺陷（F1 为 high），逐条处置：

| 编号 | 问题（复核方复现） | 为什么是真缺陷 | 修复 |
| --- | --- | --- | --- |
| F1 high（N3 残余） | hash 路由的登录失败仍算成功：`#/login` → `#/login?error=1` 只差 `?…`，`routeOf` 把 hash 里的查询串一起比较 → `hasLeftLoginRoute` 返回真 → `setup-login-success` 记 passed，整条用例「通过」而应用从未离开登录页（复核方配对证明：登录门通过后第一个步骤观察到 `…/hash-app-fail#/login?error=1`，随后 `assertUrl …#/project` 失败） | 同 D4/N3 的 fail-open：登录失败被吞成通过 | `routeOf` 对 hash 路由只取 `#` 之后、`?` 之前的片段（与 pathname 侧一致）；新增单测锁三态：`#/login?error=1` 未离开、`#/login` 未离开、`#/project` 已离开 |
| F2 medium（N7 残余） | 分类折叠的第 4 处：`WebCaseAgentWorkspace.vue` 仍把 `navigation_failed` 折成「页面不匹配」、`unsafe_action`/`locator_persistence` 折成「DOM 不支持」并附带「需人工接管」；新分类（`unsupported_action`/`action_failed`/`pagination_not_changed`）无中文标签、直接显示英文 | 工作台是用户唯一能看到诊断的地方，折叠＝把「跳转失败/动作有风险」误报成「DOM 不支持 + 人工接管」 | 该处折叠表与三处服务端/数据层对齐（只保留 `page_error`/`no_safe_action`/`unsafe_repair_category` → `unsupported_dom`、`network_blocked*` → 未知）；补齐 8 个缺失标签；组件用例（jsdom 挂真实组件）锁 `navigation_failed`→「页面跳转失败」、`unsafe_action`→「动作存在风险，未执行」且**不含**「需人工接管」 |
| F3 medium（N5 残余） | 采集侧签名有界策略过窄：只取前 8 个容器 + 4000 字符，`rowContext` 又只截 240 字符 → 第 9 个列表容器的真实翻页（8 个侧边栏 `<ul>` 在前）被判「未变化」→ IR 422 `pagination_not_changed`（真实成功被报失败） | 有界策略把「看不见的变化」当「没变化」 | 签名改为「全部容器（上限 500）各取一份 32 位摘要，前 100 个摘要进正文、其余合成尾部摘要」——签名长度与容器数量解耦，第 9 个、第 300 个容器的变化都能看见；新增真实浏览器用例（8 个侧边栏 + 长段落 + 第 9 个分页 `<ul>`）锁 IR passed |
| F4 medium（N5 衍生） | 反向误判：页面自己在刷新（时钟、自动刷新的表格）时，只要签名窗口内有任何文本变化就算「已变化」→ 点了没反应的翻页被判通过（IR 200 `pageChanged=true`；DSL passed） | 把「不归因于这次点击的变化」当成动作成功，是 fail-open | 两处都加「点击前两次采样」探测：页面自己在变时只认**页码指示器**变化（内容变化不归因于点击）；签名内的时间戳/时钟/UUID 归一化（`<time>`/`<clock>`/`<id>`）；`loading` 不参与比较但为真时谓词直接返回 false。新增真实浏览器用例（`setInterval` 自更新的列表 + 不响应的翻页按钮）锁 DSL 与 IR 都判 `pagination_not_changed` |
| F5 low（N5 衍生） | 同一页面采集期「已变化」、回放期「未变化」：IR 的容器集合含 `ul, ol`，DSL 谓词只认 table 系（`table tbody`/`[role=table]`/`.el-table__body`/`.ant-table-tbody`） | 采集期的证明在回放期不成立，等于证据不可回放 | 抽成 `pagination-signature.mjs` 单一来源：容器集合、易变文本归一化、摘要算法、谓词只在 `PAGINATION_SNAPSHOT_SOURCE`/`PAGINATION_PREDICATE_SOURCE` 定义一次；DSL 用 `materializePaginationPredicate()` 提交函数（字符串形式在 Playwright 里是「反复求值的表达式」，传 `(arg) => …` 只会得到恒真的函数对象——这条在修 F4 时被单测抓住），采集侧用 `page.evaluate(\`(${PAGINATION_SNAPSHOT_SOURCE})()\`)`。新增真实浏览器用例锁「同一个 `<ul>` 翻页，DSL 与 IR 都 passed」 |
| F6 low | 前端仍宣传 runner 无法执行的 `upload` 动作（联合类型 + 两处归一化 + 两条 DSL 正则），导入 `upload …` 行后 runner 硬失败且只有英文报错 | 两侧词表在**前端边缘**仍不一致 | 前端四处一并移除 `upload`；该行退回人工步骤（`manual`/`elementRef`，不下发执行），并新增用例锁「不再产出 `upload` 动作、该行是人工步骤」 |

## 三之十、第九轮对抗性复核（`deleg_e39f7c1b`，复核 `7e5dc48`）判 FAIL 的 X1–X5，本轮修复

第九轮复核独立复验的结论：**F2、F3、F5、F6 判「已修复」**（**F1 连带 N3 只判「修了一半」**——只修了 DSL 那一份实现）；**N1、N2、N4、N5、N6、N8、N9、N10 与 6 条验收、抽样的 D1–D8 全部仍成立**（复核方自跑 runner `238/238 fail 0`、platform `552/0/0/5 exit 0`、web `139 files / 694 tests exit 0`，与本轮记录一致；platform 首跑另有一条与本 commit 无关的 flake `AiMcpToolExecutorTest…expected:<success> but was:<blocked>`，单跑 5/5 通过、全量复跑亦绿）。它另提 5 条新缺陷（1 high / 3 medium / 1 low），逐条处置：

| 编号 | 问题（复核方复现） | 为什么是真缺陷 | 修复 |
| --- | --- | --- | --- |
| X1 high（F1/N3 残余） | 同一判定有两份实现：`runner-core.mjs` 已只比路由，`page-locator-discovery.mjs` 仍逐字比较 `pathname+search+hash` → 同一 hash 路由登录失败（`#/login` → `#/login?error=1`）在 DSL 判失败、在采集侧判成功（HTTP 200 + `authenticated=true`，证据取自登录页） | 采集侧 fail-open：登录没成功却继续取证，且两侧结论相反 | 抽 `server/web-runner/login-route.mjs` 为「是否离开登录路由」的唯一实现（hash 路由只取 `#` 之后、`?` 之前），`runner-core.mjs` 与 `page-locator-discovery.mjs` 共同 import；新增真实浏览器用例锁「discovery 侧 hash 登录失败必须失败」 |
| X2 medium（F4 残余） | F4 只覆盖刷新周期小于 250ms 预采样窗口的页面：2s/3s/5s 轮询列表上「点了没反应的翻页」在 DSL 与 IR 两侧都判通过（真实 HTTP + 真实 Chromium 复现） | 页面自身的周期刷新被归因给这次点击（fail-open） | 变化被接受后做**归因复核**：内容型变化（页码指示器没变）必须在一个安静窗口（`paginationQuietWindowMs` = max(3.5s, 步骤超时)，上限 10s）内保持稳定；若「这次变了的容器」再次被改写，则判 `pagination_not_changed`。DSL 与 IR 共用 `paginationChangedParts`/`paginationKeepsChanging` |
| X3 medium | 「共用签名」把 `loading` 计入了 IR 的变化签名、DSL 谓词不计 → 同一页面同一次死点击 **DSL failed、IR 200 passed** | 两侧口径再次漂移 + IR 侧 fail-open | 签名投影 `paginationSignature` 只含 `[indicator, head, digests, tail, count]`；`loading` 作为独立的 `paginationLoading` 交给调用方，IR 在 loading 为真时不判定变化（与 DSL 谓词的 `if (snapshot.loading) return false` 同口径） |
| X4 medium | 共用的易变文本归一化把「页间差异只由时钟/时间戳形状文本构成」的真实翻页在两侧都判未变化（`12:01/12:02 → 12:03/12:04`）——真实成功被报成失败，属 `7e5dc48` 引入的回归 | 过度归一化把真实变化抹平成「没变化」 | 摘要按**原文**计算（去掉归一化）；「页面自己在变」改由「点击前两次采样 + 变化后的安静窗口」判定。新增用例锁时钟形状文本的真实翻页两侧都通过 |
| X5 low | IR 动作执行期的观测不滚动视口（采集/探索阶段才滚动）→ 首屏之外的证据目标报 `action_evidence_missing`，真实存在的元素被报成「观测不到」（非本 commit 引入） | fail-closed 方向上的假失败：真元素被判不可观测 | `page-evidence.mjs` 新增 `sweepForEvidenceTarget`：证据目标未在观测中命中时，先做一次有界滚动扫描（6 步、每步观测）再重试；命中后的观测同时用于 `resolvedTarget`、`before` 证据与 postcondition 比较。新增用例 + HTTP 复测锁 200 passed |

HTTP 级复测（`<synthetic-hermes-cache>/scratch/adv10/http-round9-checks.mjs`；真实 runner `127.0.0.1:19472` + 真实 Chromium + 独立夹具页 `127.0.0.1:19471`），实测：

| 场景 | 修复前（复核方实测） | 修复后（本机实测） |
| --- | --- | --- |
| discovery 侧 hash 路由登录失败（`#/login` → `#/login?error=1`） | HTTP **200** + `authenticated=true`（证据取自登录页） | HTTP **422** `login_failed`：`当前 URL: ".../hash-fail#/login"` |
| 同场景对照：`#/login` → `#/project` | 200 | **200**，`elements` 正常采集 |
| DSL `paginate` 于 **2s 周期**自刷新列表 + 不响应按钮 | 步骤 **passed** | 步骤 **failed**：`pagination_not_changed: page.waitForFunction: Timeout 3000ms exceeded.` |
| IR 同场景 | 200 passed | **422** `pagination_not_changed` |
| DSL `paginate` 于 loading 遮罩翻转的静态列表 | failed（与 IR 相反） | **failed** `pagination_not_changed`（与 IR 同结论） |
| IR 同场景 | **200 passed**（loading 算变化） | **422** `pagination_not_changed` |
| DSL/IR `paginate`：页间差异只由时钟形状文本构成 | 两侧都判 `pagination_not_changed`（真实成功被报失败） | DSL **passed**；IR **200 passed**（`after` = `12:03 A 12:04 B`） |
| IR 首屏之外的证据目标（1400px 占位之后） | 422 `action_evidence_missing` | **200 passed** |
| 对照：静态页死翻页（DSL） | failed | **failed** `pagination_not_changed` |
| 对照：真实表格翻页（IR） | 200 | **200 passed**（指示器 `1 → 2`） |

## 三之十一、第十轮对抗性复核（`deleg_8742e5f8`，复核 `78f8df2`）判 FAIL 的 D1–D3，本轮修复

复核方结论（其亲测）：基线全绿（web-runner 244/244、phase3 定向 + freshness 21/21、platform 552/0/0/5、前端 139/694 + `vue-tsc` exit 0、`record-phase3-evidence.mjs` 复跑 4 份载荷 md5 前后一致），6 条验收与 F/N/D/X 族测试全部成立，但新增 3 条残余缺陷；其中 D1 是 `78f8df2` 新引入的回归。三条都属「失败被吞成通过 / 真实成功被报成失败 / 两侧口径不一致」三族。

### D1（HIGH｜真实成功被报成失败，本 commit 新引入）：真实翻页 + 异步取数被判 `pagination_not_changed`

- 复核方证据：`p7` 的 E1（点击后先出「加载中」、数据晚到，DOM 最终已在第 2 页）在 DSL 与 IR 两侧都被判 `pagination_not_changed`；`p9` 用 `.el-pager li` 作指示器时首个匹配元素文本不变（`INDICATOR_BEFORE="1"` → `AFTER="1"`）同样误伤；`p10` 用 `git archive 7e5dc48` 抽出的修复前实现跑同一夹具为 `passed` ⇒ 确诊为第九轮「固定安静窗口」引入的回归。
- 根因：第九轮把归因做成「变化之后再安静观察一个窗口（`max(3.5s, 步骤超时)`，上限 10s）」，并要求内容型变化**只认指示器**（`requireIndicator`）——两者都把「页面还在变」等同于「变化不是这次点击造成的」，而真实异步翻页恰恰是「先出一个加载/占位变化，数据再晚到，然后停住」。
- 修复：归因改为**按变化的时间节律**判定，两侧共用一份实现（`pagination-signature.mjs` 新增 `paginationSettleTracker` / `paginationSettleHorizonMs` / `paginationTrackedChange` / `paginationIsLoading`）：
  - 判定式：`距最后一次变化静默 ≥ max(1 000ms, 1.25 × 观察到的最长变化间隔)`；
  - 观察上限：DSL 取步骤超时（无 `timeoutMs` 时取页面默认超时 = 用例超时），IR 取请求等待预算，两者都封顶 15s；
  - 页码指示器变化仍直接归因（有明确目标页标记时无需等静默）；
  - 超时报文带**节律诊断**：`观察到 N 次变化（最大间隔 ~Xms），需要静默 ~Yms 才能归因`；
  - 删除「点击前 250ms 两次预采样 → 只认指示器」这条旁路（`requireIndicator` 及 IR 侧预采样一并移除），它正是误杀真实异步翻页的路径。
- 复核方探针复跑（同一批探针，直接 import 仓库模块）：`E1_REAL_ASYNC_RELOAD_NO_INDICATOR={"status":"passed","durationMs":2340,"finalText":"第2页 行1|第2页 行2"}`、`E2`/`E3` 亦 passed；`DSL_ELPAGER_ASYNC=passed`；`p3` 的 C5/C6/C7（真实点击 + 页面自刷新，delay 400/2000/3000ms）全部 `passed`。
- 新增测试：`page-locator-discovery.test.mjs`「a real asynchronous page turn is attributed to the click」、`runner-core.test.mjs`「a real asynchronous page turn is attributed to the click (stub level)」。

### D2（HIGH｜X1 未闭环）：`successTarget` 为空时采集侧登录不校验，登录失败被当成功

- 复核方证据：`p1`（空目标 + 失败登录）采集侧 `outcome=resolved`、`authenticated=true`、证据取自登录页，而同一份配置在 DSL 侧 `assertion_failed`（`login did not leave the login route`）；`p4`（cookie 门）失败登录同样 `authenticated=true`。
- 根因：`page-locator-discovery.mjs` 的登录分支是 `if (login.successTarget) { …校验… } return` —— 没有目标时**根本不进入** `login-route.mjs` 的判定；`authenticated` 也只等于「有会话」。
- 修复（三处）：
  1. 空目标同样必须「离开登录路由」，判定仍只有 `login-route.mjs` 一份实现；
  2. 采集结束前补一道登录门：`finalObservation.readiness` 明确 `ready=false + auth_context_lost`（复用会话过期、cookie 门未过、被挡回登录页）时抛 `login_failed`，不产出登录页证据；
  3. `authenticated` 改为「应用真的可用」：`有会话 ∧ ¬(readiness.ready===false ∧ failureReason==='auth_context_lost')`。
- 复核方探针复跑：`DISCOVERY_RESULT={"outcome":"rejected","category":"login_failed", …}`（修复前为 `resolved`）。
- 新增测试：`page-locator-discovery.test.mjs`「discovery requires leaving the login route when no success target is configured」（断言分类 `login_failed`）。

### D3（MEDIUM｜X2 未闭环）：DSL 安静窗口固定 3.5s，慢刷新死点击仍判通过，且与 IR 口径不一致

- 复核方证据：`p3` 的 C3（周期 5s）/C4（周期 8s）死点击 DSL 判 `passed`；`p5` 同一页面同一次死点击 `DSL_DEAD_CLICK_NO_TIMEOUT=passed` 而 `IR_DEAD_CLICK_TIMEOUT_20000=rejected`。
- 根因：`paginationQuietWindowMs(undefined)=3500` 是固定值，挡不住长于窗口的刷新周期；IR 侧窗口取 `max(3.5s, min(timeoutMs,10s))`，同一页面两侧口径不同。
- 修复：静默要求随**实测节律**放大（`1.25 × 最长间隔`），窗口与等待预算挂钩（DSL 步骤/用例超时、IR 请求预算，封顶 15s），两侧同一判据；超时报文带节律诊断（同 D1）。
- 复核方探针复跑：`C1/C2/C3/C4`（周期 1.2/3/5/8s）全部 `pagination_not_changed`，`C8`（带 `timeoutMs=5000`）同样判失败；`p5` 两侧一致（DSL `failed:pagination_not_changed`、IR 2s/20s 都 `rejected`）。
- 新增测试：`runner-core.test.mjs`「executeStep paginate does not attribute a self-updating page to the click」、「a slow self-refreshing page is not attributed to the click (stub level)」（周期 1.5s，断言必须拒绝）。

### 本轮仍然存在的边界（据实列出）

- 页面自刷新周期长于等待预算时（例如用例超时 15s 以内、而页面 20s 轮询），一次自刷新仍可能被算作「点击生效」——静默是时间证据，不是页面属性；与上一轮的差别是**两侧同一判据、同一条诊断**，且死点击在 1.2s–8s 全周期带内都被拦下。
- 归因证据只覆盖「分页容器 + 页码指示器」两处；数据在其他区域异步渲染不会影响判定，也不会被当成翻页成功。
- 「加载中」占位若不在 `PAGINATION_LOADING_SELECTOR`（`.loading`/`aria-busy`/`.el-loading-mask` 等）内，`after` 证据可能停在占位文本（`p7` 的 E2/E3 实测如此）——这属于证据质量问题，不改变通过/失败判定。

## 三之十二、第十一轮对抗性复核（`deleg_68018d1a`，复核 `7eb8386`）判 FAIL 的 D1/D3 残余 + C1–C6，本轮修复

复核方结论（其亲测）：B 部分全部成立（web-runner 247/247、phase3 定向 + freshness 21/21、platform 552/0/0/5、前端 139/694 + `vue-tsc` exit 0、`record-phase3-evidence.mjs` 复跑 4 份载荷字节稳定），D2（空 `successTarget` 登录）判定为**真修复**；但 D1、D3 只修了一半，并新增 1 条 HIGH + 4 条 MEDIUM + 1 条 LOW。六条都落在「失败被吞成通过 / 真实成功被报成失败 / 两侧口径不一致」三族。

### C1（HIGH｜真实成功被报成失败，本 commit 新引入）：数据晚到 ≥ 约 6.7s 的真实翻页必被判失败

- 复核方证据：`compare-probe.mjs` 同夹具 8s 晚到 —— head `failed`、`78f8df2` `passed`；`dsl-probe.mjs a1b` 扫描 6.0s `passed` / 6.5s `passed` / **6.8s `failed`**（同一页面因后端快慢在通过/失败间跳动的硬阈值）；`A1-real-async-3s-timeout5000` 亦 `failed`（DOM 已是第 2 页）。
- 根因：`requirement = 1.25 × gapMax`，而 `gapMax` 把「点击 → 首次变化」的时延也算作节律 ⇒ 数据越晚到、要求静默越久（≈ 数据时延 × 2.25 才能归因），超过预算就必判失败；两侧观察上限还不同（DSL 15s / IR 夹到 10s）。
- 修复（三段判据，删掉「时延进节律」这条错算）：
  1. **点击响应**：点击后 1s 窗口内出现**加载态翻转**，或 ≤ `PAGINATION_IMMEDIATE_RESPONSE_MS`（300ms）的**立即变化** ⇒ 这次点击有响应，此后只需静默地板 `PAGINATION_SETTLE_FLOOR_MS = 1s` 就归因 —— 晚到的数据不再被节律项拖过预算；
  2. **晚到的纯内容变化按「无响应」处理** —— 它和「一次恰好落在此刻的自刷新」在观测上不可区分（A5 的真实点击必须 passed、C-slow-refresh 相位扫描的死点击必须 failed，靠这条两头成立）⇒ 必须静默 `PAGINATION_UNSIGNALED_QUIET_MS = 11s`，且不短于 `1.1 × 最大间隔`；
  3. **兜底**：静默 ≥ `max(1s, 1.1 × 最大间隔)`。
  观察上限两侧统一为 `paginationSettleHorizonMs(预算)` = `min(20s, max(地板, 预算))`（`PAGINATION_SETTLE_CAP_MS = 20s`）。
- 复核方探针复跑（同一批探针，直接 import 仓库模块）：见下「探针复跑结果」。

### C2（MEDIUM｜两侧口径不一致）：IR 预算被夹到 10s，同一页面同配置两侧相反

- 复核方证据：`CROSS-dsl-async-5s-default30000` passed（11.4s）vs `IR-CROSS-async-5s-timeout30000` rejected（10.5s）。
- 根因：`page-locator-discovery.mjs` 的 `Math.min(10_000, timeout)` 与 DSL 的 15s 上限不是同一条规则。
- 修复：删除 IR 侧的 10s 夹取，两侧同用 `paginationSettleHorizonMs`；IR 的预算取请求等待预算、DSL 取步骤/用例超时。

### C3（MEDIUM｜失败被吞成通过，本 commit 的行为变化）：变化又回退到点击前，仍判 passed

- 复核方证据：`REVERT-0.4s/1.2s` head `passed`（after 证据就是「原样」）、旧版 `failed`；`C-revert-0.4s/0.8s` 同样 passed。
- 根因：`paginationTrackedChange` 把「变回原样」当成又一次新变化记进节律 ⇒ 静默满足后直接放行，且从不校验最终内容是否仍与点击前不同。
- 修复：新增 `paginationContentUnchanged(before, current)`（比较 `head/digests/tail/count`，不含指示器）——**变化过又回到点击前的内容 ⇒ 立即 `pagination_not_changed`**，报文含「回退到点击前的状态」。地板 1s 同时保证回退有足够的观察窗。

### C4（MEDIUM｜失败被吞成通过；既有、两版同结论）：与翻页无关的指示器变化把死点击判 passed

- 复核方证据：`C2-dsl-button-gets-is-active`（被点按钮自己加 `.is-active`）、`C2-dsl-carousel-active`（轮播 `.is-active` 每 1.2s 换）、`C2-dsl-wizard-aria-current`（无关 `aria-current="page"` 步骤指示器）三例 DSL 全 passed，IR 侧 4/4 resolved —— 因为「页码指示器一变就直接归因」对**任何**命中通用指示器选择器的元素生效。
- 修复：**删除「指示器一变就归因」这条快捷路径** —— 归因只看**列表内容**是否变化（指示器仍进签名与证据，供人核对）；缺少内容变化时，无论指示器怎么动都必须攒够静默。复核方 A 部分的 `.el-pager li` / `.ant-pagination-item` / `aria-current` 真实翻页用例不依赖这条快捷路径（它们有内容变化 + 点击响应），仍然 passed。
  （第一版修复尝试加的是「指示器必须位于分页容器内」的作用域判定，实测被复核方夹具的类名（`carousel-pagination` 之类）绕开 —— 类名与 aria 文案都不可信，故改为不看指示器。）

### C5（MEDIUM｜真实成功被报成失败 + 两侧不一致，本 commit 新引入）：结束前的登录门把含密码框 + 「退出登录」的业务页整轮丢弃

- 复核方证据：`MIN-password-input-plus-logout-button`（改密/凭据类页面，未配置登录）被拒 `login_failed`，去掉「退出登录」文本或去掉密码框都正常；同一页面 DSL 侧 `passed`；`GATE-old78-shellpwd-no-login` 修复前是 `resolved`。
- 根因：采集结束前的登录门只看 `readiness.ready===false && failureReason==='auth_context_lost'`，而 `loginShell` 的判定包含「可见密码型输入 + 标题/控件文本含登录字样」（改密页、用户管理页顶栏的「退出登录」都会命中），与 `login` 是否配置无关。
- 修复（两层条件，缺一不可）：这道门只在**用例配置了登录**（`input.login?.enabled`）**且当前仍在登录路由上**（`!hasLeftLoginRoute(input.login?.url || input.url, page.url())`）时启用 —— 只按「配置了登录」还不够：一个真实跑过登录步骤、随后进入改密页/用户管理页的用例同样含密码框 + 「退出登录」，readiness 也会判成 `loginShell`。`authenticated` 用同一个条件（未配置登录、或已离开登录路由时不得报「会话丢失」）。
- 新增真实 Chromium 用例 `a configured login does not reject a business page that merely looks like a login shell`：登录步骤真实执行成功 → 进入含密码框 + 「退出登录」的业务页 ⇒ 必须 `resolved` 且采到业务元素（旧的结束门会整轮丢弃）。

### C6（LOW｜语义边界）：`paginationSettleHorizonMs(0)` 走上限而负值走地板

- 修复：非有限值或 ≤ 0 一律视为「未配置」⇒ 取上限，一条规则（`paginationSettleHorizonMs`）。

### 本轮收敛过程（两个只有实测才能暴露的坑）

- **探针必须与谓词等待并行**：第一版把「点击响应」的探测放在 `waitForFunction` 之后 —— 带遮罩的异步翻页在数据到达前谓词不会满足，于是遮罩翻转（真正的响应）根本探测不到，3s/8s 晚到又被判「无响应」，只能等 11s 长静默甚至超预算（实测 A1 `3s+timeoutMs=5000` failed）。改为把谓词等待挂成 promise、探测循环与它并行后，响应在 ≤1s 内判定，晚到的数据只需地板静默（A1 六个用例全部 passed，`3s+timeoutMs=5000` 4.1s 通过）。
- **不得停在加载占位态上归因**：探测到「容器里只剩一句加载占位」时若立刻按「有响应」走地板静默，先出占位、随后回滚到原样的夹具会在回滚前被放行（真实 Chromium 回退用例首跑 `not ok 135 - a change that reverts to the pre-click content is not attributed to the click`）。修复：快照单列 `placeholderOnly`（`paginationPlaceholderOnly`），两侧归因都要求当前快照**不处于占位态**，回滚因此必被观察到；顺带关掉上一轮遗留的限制 (c)（`after` 证据不再停在占位文本上）。

### 复核方探针复跑结果（`<synthetic-hermes-cache>/scratch/adv11-review/adv12/*.log`，脚本直接 import 仓库模块）

- `dsl-a1` **6/6 passed**（0.5s / 1s / 3s / 3s+`timeoutMs=5000` / 8s / 8s+`timeoutMs=20000`）；`dsl-a1b` **4/4**（6.0/6.5/6.8s 扫描 + 两次波次）；`dsl-a2` **4/4**（`.el-pager li` 首元素文本 1→1 的真实翻页 / 分页器类名被换 / `.ant-pagination-item` / `aria-current` 对照）。
- `dsl-a3` **8/8 按期望 failed**（1.5/3/5/8s 周期死点击 × 有无 `timeoutMs`，含 20s 预算）；`phase.log` 相位扫描 **6/6 全部 failed**（含上一轮唯一漏判的 `PHASE-2s`），`REVERT-0.4s/1.2s` failed。
- `dsl-c` 9 例 = 2 passed / 7 failed：`C-nomask-placeholder-1s/8s`（无遮罩、容器内占位 ⇒ 复核方残留 (c)）现在 passed；`C-revert-0.4s/1.2s`、`C-revert-0.4s/0.8s`、`C-slow-refresh-8s-phase2s`（±`timeoutMs`）、`C-same-text-mutation-200ms`、`C-loading-forever`（±`timeoutMs`）全部 failed。
- `dsl-c2` **4/4 failed**（按钮 `.is-active`、轮播 `.is-active`、向导 `aria-current="page"`、慢速轮播）；`dsl-cross` 3/3 passed；`dsl-a5` 3 passed + 2 按期望 failed（静态死点击）。
- IR 侧：`ir-a1` **5/5 resolved**（含 8s 晚到）；`ir-c2` **4/4 rejected**；`ir-cross` 3 resolved + 1 rejected（死刷新）。
- `min-trigger.log`：`MIN-password-input-plus-logout-button`、`MIN-textinput-name-password` 由 `rejected/login_failed` 变为 **resolved**（`MIN-login-text-only-no-password` 对照保持 resolved）；`MIN-dsl-same-page` 报的是一条定位符超时 —— 该探针的 DSL 子用例 `assertVisible testid=save` 用的元素只存在于另一个夹具页（探针自身的夹具错配），与登录门禁无关。`extra.log`：`GATE-head-shellpwd-no-login`(±`reuseSession`) 均 **resolved**，`GATE-head-login-ok-reuse-session` 仍 `authenticated=true`。
- `cadence.log`：`horizon(0)=horizon(null)=horizon(undefined)=20000`、`horizon(1)=1000` —— C6 修正后只剩一条规则（≤0 或非有限 = 未配置）。

### 本轮仍然存在的边界（据实列出）

- 页面自刷新周期长于 `PAGINATION_UNSIGNALED_QUIET_MS`（11s），或自刷新相位恰好落在点击后 1s 响应窗口内时，一次自刷新仍可能被算作「点击生效」——静默是时间证据，不是页面属性。
- 观察上限 20s：数据晚于约 19s 的真实翻页会判失败（可用更大的步骤/用例超时，但不会超过 20s 上限）。
- 归因只看「列表容器内容是否变化」：页面把目标数据渲染在非容器区域（不匹配 `PAGINATION_CONTAINER_SELECTOR`）时，真实翻页会被判 `pagination_not_changed`（fail-closed，宁可失败不误报成功）。
- `authenticated` 在**未传 `reuseSession`** 的运行中恒为 `false`（会话对象只在复用路径创建，属既有语义）：生产固定 `reuseSession=true`，故线上路径不受影响；本轮新增的登录门用例因此只断言「未被误拒 + 采到业务元素」，复用路径由复核方 `extra-probe` 的 `GATE-*-reuse` 场景覆盖。

## 三之十三、第十二轮对抗性复核（`deleg_865a93a0`，复核 `340a766`）判 FAIL 的 2 high + 3 medium + 2 low，本轮修复

第十二轮复核的 B 部分（runner / phase3 定向 / 载荷稳定性 / platform / 前端）五套门禁逐字复现全部成立；A/C 部分给出 2 high + 3 medium + 2 low。本轮把归因判据收敛成**三段响应模型**（两侧同一份 `pagination-signature.mjs`）：

| 响应类型 | 判定方式 | 静默地板 |
| --- | --- | --- |
| 加载态响应 | 点击后 1s 窗口内加载态翻转（遮罩/占位/spinner，含点击**同拍**就出现的） | `PAGINATION_LOADING_FLOOR_MS` = 1s |
| 同步内容响应 | 点击**同拍**改了 DOM（同拍快照紧跟在 `click()` 返回之后） | `PAGINATION_CONTENT_FLOOR_MS` = 2.5s |
| 无响应 | 异步出现的纯内容变化 / 自刷新 | `PAGINATION_UNSIGNALED_QUIET_MS` = 11s |

### high-1（失败被吞成通过，本 commit 引入）：点击后 ≤~130ms 落地的自刷新冒充「点击响应」
- 复核方夹具：点击处理函数只安排自刷新（点击本身零变化），相位 100/250/500ms、周期 1.5–10.5s，`timeoutMs` 6000–20000。
- 旧判据把「点击后 ≤300ms 的立即变化」当作点击响应 ⇒ 自刷新冒充响应 ⇒ 只需再静默 1s ⇒ 两侧 passed/resolved。
- 修复：**删除「立即变化」这条响应**。响应只认（a）加载态翻转（b）**点击同拍同步改 DOM**——替换 `immediateSeen` 的语义后，异步出现的变化一律走 11s 长静默，自刷新周期 ≤10.5s 时永远攒不出来。
- 锁测：`page-locator-discovery.test.mjs` 新增「死点击 + 相位 100ms 自刷新 ⇒ failed」DSL 与 IR 两条真实 Chromium 用例。

### high-2（失败被吞成通过 + D2 类回归）：会话失效回跳不再被登录门拒绝
- 复核方夹具：服务端持久失效标志 + `reuseSession=true` ⇒ 证据页在登录路由（`#/login?error=1` / `#/auth/signin?expired=1`），而应用已把实时 URL 重定向回 `#/app`。
- 根因一：C5 收窄后的门读**实时** `page.url()`，证据却来自 `finalObservation` ⇒ 门被跳过；根因二：只比对 URL 路由，回跳目标 ≠ 配置登录路由时门必然关闭。
- 修复：门改用**证据页**（readiness 自己的 `pageKey`）与**起始 URL 的路由**比较（`hasLeftLoginRoute(input.url, evidencePageUrl)`）⇒ 证据页不是起始页 ⇒ fail-closed（`login_failed`）。业务页/起始页的 C5 场景不受影响（证据页 == 起始页 ⇒ 门关闭）。
- `authenticated` 同步改用同一判据；未复用会话但登录流程真的走通时不再报「未认证」（low-3）。

### medium-1（失败被吞成通过）：非占位形状的临时数据 + 回滚晚于地板静默 ⇒ 判 passed
- 复核方夹具：点击后 `setTimeout(0)` 写入「临时数据 A」、1200/1500/2000ms 回滚到点击前内容。
- 修复：**同步内容响应地板 2.5s**（> 回滚点 2s、< A2 的 4000 与对照 _turn_ 的 6000）⇒ 回滚在归因前被回退检查抓住；异步出现的临时数据（250ms 等）没有响应标记 ⇒ 直接走 11s 长静默。
- 封边：占位形状的回滚保持原判据（占位态不得归因）；`rollbackFastEarly`（250ms 出现/1200ms 回滚）两侧 failed。

### medium-2（真实成功被报成失败）：无加载态的晚到真实翻页被 20s 观察上限误杀
- 复核方夹具：无遮罩、数据 6s/10s 到达、`timeoutMs=20000` ⇒ 「数据到达 + 11s 长静默」超出 20s 上限。
- 修复：① 观察上限 `PAGINATION_SETTLE_CAP_MS` 20s → **30s**；② 观察窗**延伸**：预算到点后若最近仍有变化且归因仍可达成，观察到「该变化 + 所需静默」为止（硬上限 30s），无变化则按预算结束。两侧同一条 `observationEnded` 规则。

### medium-3（两侧口径不一致）：浏览器侧 evaluate 抛错的分类
- DSL 侧此前的异常没有分类（`category` 为空），IR 侧是 `observation_failed`。
- 修复：`runner-core.mjs` 新增 `evaluatePaginationSnapshot` 包装（点击前/同拍/监测循环三处）⇒ 统一 `observation_failed`，原样保留 cause。

### low-1（响应窗口实为 ~920ms）/ low-2（`timeoutMs<0` 两侧不一致）/ low-3（未复用会话 authenticated=false）
- low-1：检测循环按 200ms 采样，960ms 的遮罩翻转可能落到 1000ms 之后的第一个样本上 ⇒ 加 `PAGINATION_CLICK_RESPONSE_SLACK_MS`（150ms）采样宽限。
- low-2：两侧预算推导统一走 `paginationSettleHorizonMs`（0/负数/NaN/非数字一律取上限；DSL 未配置时取页面默认超时）。
- low-3：`authenticated` 在「配置了登录、未复用会话、但登录流程确实走通」时取 true（`loginCompleted`）。

### 第二电池暴露的两处（同日三次收紧，均有实测证据）
- **验收与结束判定在同一拍翻转**：`settled()` 用 `>=`、`observationEnded()` 用 `<`，两者在同一毫秒互换；循环条件先判结束 ⇒ 归因刚成立的那一拍直接退出（实测：无遮罩数据 6s/10s 两批与 10s 单批跑到「lastAt + 需静默」仍报 `failed`）。改为每轮**先验收、再判结束**。
- **整页导航被当成翻页结果**：观察窗延伸之后，点击后跳走的新文档会在静默到期时被判成功（IR 侧实测 `resolved`/`passed`，DSL 侧 `failed` ⇒ 两侧不一致且 fail-open）。新增**观测哨兵**：点击前在页面 `window` 上打标记，整页导航会丢掉它；丢失即两侧同一 `pagination_not_changed`（报文写明「页面已导航离开，被观测的列表不在原文档里」）。SPA 的 hash 路由切换不丢哨兵，不受影响。
- **带遮罩且「遮罩常驻不隐藏」仍按 fail-closed 处理**（复核方 C-rest 探针的 `expect` 字段写「passed（点击有响应⇒地板静默）」，但其第 12 轮报告把同一形状列为正确结果：「遮罩常驻不隐藏 → `pagination_not_changed`（fail-closed）」）。两条理由：① 归因时遮罩仍可见，after 证据会停在遮罩态，违反复核方 A1 的「after 证据不是占位文本/遮罩态」；② 内容变化发生在加载态之内，不能作为归因时刻（与 X3、C3 同口径）。

### 电池复跑发现的两处过严 + 一处不一致（同日二次收紧，有实测证据）
- **登录门过粗**：只按「证据页 ≠ 起始路由 + `auth_context_lost`」判定，会在 readiness 样本过期（停在登录阶段）时误杀合法流程——A4-iii（空 `successTarget`、hash 路由登录成功）与 A5b（配置登录 + 复用会话、会话有效）的期望都是 resolved，实测却出现 1/3 被拒。门条件补 **`!loginCompleted`**：本轮凭据登录真的离开了登录路由才算过；复用会话（`loginCompleted=false`）与登录后会话立即失效的路径仍 fail-closed（high-2 的两条回跳用例在复跑里都按期望 rejected）。
- **谓词超时阻塞验收**：观察窗延伸后，「数据 10s 到 + 11s 静默」在 ~21s 才能归因，而 `waitForFunction` 的预算仍是步骤预算（20s）⇒ 谓词 pending/超时会把这次归因挡掉（实测：无遮罩 10s 单批仍 failed）。验收改为只按观测状态判定；谓词保留作失败诊断与交叉验证，不再阻塞。
- **IR 侧 `timeoutMs ≤ 0` 归一不一致**：DSL 侧 `-1` 回落页面默认超时（30s），IR 侧 `Number(input.timeoutMs || 20_000)` 把 `-1` 当 -1 再用 `Math.max(1000, -1)` 夹成 1s ⇒ 同一配置两侧相反（实测 DSL passed / IR rejected）。IR 侧改为 `>0 ? max(1000, v) : PAGINATION_SETTLE_CAP_MS`。

### 本轮收敛过程（实测才暴露的坑，别再用阈值去「补」）
- **「立即变化」这条响应本质上是可伪造的**：自刷新只要相位对齐到点击后 ≤130ms，就能冒充点击响应。只有「点击同拍改了 DOM」或「页面显式给出加载态」才能把响应与页面自身节奏分开——前者是同步语义（同拍快照在 `click()` 返回后立刻取，往返 ~2–15ms），后者是 DOM 语义。
- **sync 与 async 的边界是采样间隔**：点击后 ≤~10ms 落地的自刷新在观测上等同点击自身的同步效果（残余，见下）。
- **预算上限不能低于「数据 + 长静默」**：无加载态的真实慢翻页走 11s 长静默，观察窗必须能覆盖「数据到达 + 11s」。

### 本轮仍然存在的边界（据实列出）
- 既不同步、又无加载态、且页面**持续自刷新**的真实翻页会 fail-closed（与「相位对齐自刷新」在观测上不可区分）；反向地，自刷新周期 >11s 时一次自刷新可能被算作点击生效。
- 点击后 ≤ 采样间隔（~10ms）内落地的自刷新会被当成同步内容变化。
- 观察窗延伸会使死点击类失败最长跑到 30s（硬上限），比步骤预算长——这是 medium-2 与 high-1 同时成立的代价（两者的观测差异只有「变化 + 静默」的时间结构）。

## 三之十四、第十三轮对抗性复核（`deleg_acdf1fa3`，复核 `32a71e1`）判 FAIL 的 A4/A5 + C-1…C-9，本轮修复

判定原文：B 部分（runner 255/255、phase3 21/21、载荷两遍 md5 与评审前逐字节一致、platform 552/0/0/5、前端 139 files/694 + vue-tsc 0、族抽样 32/32）逐字成立；A1/A2/A3/A6 成立；**A4 因 C-1、A5 因 C-2 不成立**；C 部分 4 medium + 3 low（61 个两侧用例中 57 一致）。

### 逐条修复（每条都先有复核方的确定性复现，再改代码）

- **C-1（medium）IR 用上一轮样本验收 ⇒ 遮罩常驻被吞成 resolved**：`waitForObservedPageChange` 顶部的验收只查上一轮样本的占位态、不查加载态，加载分支也不清 `lastResult`（夹具：点击同拍改内容 + 1000/1500ms 起出现永不隐藏的遮罩 ⇒ IR `resolved`、DSL `failed`，新旧对照证明是本轮新引入）。修复：验收追加 `!lastCurrent.paginationLoading`，加载分支无条件 `lastResult = null`。锁测：`a mask that never hides is not accepted as the click response (DSL + IR)`。
- **C-2（medium）`timeoutMs = Infinity` 在 IR 入口被判死且未分类**：`Number(x) > 0` 对 `Infinity` 为真，`Math.max(1000, Infinity)` 直接进 `page.goto`（`Timeout Infinityms exceeded`，99ms，分类为空）。修复：两侧统一 `Number.isFinite(...) && > 0`，超出定时器上限（2^31-1）夹紧；非正值/非有限值取观察上限 30s（新增 `PAGINATION_MAX_TIMEOUT_MS`）。
- **C-3（medium）登录门把「页内 hash」当成另一条路由**：`/spa-pw` 与 `/spa-pw#/list`、甚至 `#/home`→`#/list` 的页内跳转都被判「离开了登录路由」，复用会话 + 密码框业务页 + 页内 hash 导航的合法采集被整轮丢弃（假失败）。修复：**采集结束的会话门改比 origin + pathname（忽略 hash）**（新增 `hasChangedRoutePath`），hash 变化的「回跳登录页」判定交给证据内容门；`hasLeftLoginRoute`（登录流程的离开判定）保持 hash 感知并在任意前缀下成立。锁测：`login route comparison does not treat an in-page hash as a different route`。
- **C-4/C-5（medium）凭据登录被挡回、同路由登录视图 ⇒ resolved + 采到登录页元素**：`!loginCompleted` 短路了整条路由门；同路由渲染登录视图时 `endedAwayFromStart` 为假、门被跳过。修复：新增**证据内容门**（复核方给出的方向）——配置了登录且 readiness 非 ready 时，若证据元素里同时有「凭据输入」与「恰好是登录字样」的控件（`登录/登陆/立即登录/login/sign in`…）即 fail-closed。元素字段以 `accessibleName` / `semanticType` / `type` 为准（首版误读 `name`，复跑发现门未生效后修正）；业务页里的「修改密码 / 保存 / 退出登录 / 用户中心」不命中（复核方 A2-7/A2-9/A2-11 仍必须 resolved，已按其实测元素名核对）。判定抽成纯函数 `evidenceLooksLikeLoginShell` 并锁测。
- **C-6（medium）观测哨兵误杀真实整页导航翻页**：`href=?page=2` 型服务端翻页必然整页导航 ⇒ 哨兵丢失 ⇒ 两侧即时失败（88/271ms，而 DOM 证明翻页已发生）。修复：哨兵丢失时先检查新文档——容器内容签名与点击前不同且容器数 >0 ⇒ 认定真实导航式翻页，归因并返回；否则（跳走、刷新回原样、页面自己清标记）仍按 `pagination_not_changed` 失败。锁测：`a navigation-style page turn is attributed while jumping away is not (DSL + IR)`。
- **C-8（low）回退消息**：最后一次采样已回到点击前状态时，失败报文优先用「回退」措辞（与 IR 同语义、同分类），不再落到谓词超时措辞。
- **C-9（low）超大超时的定时器溢出**：翻页自身的点击与诊断谓词超时夹在 `PAGINATION_MAX_TIMEOUT_MS`（2^31-1）以内，消除 `TimeoutOverflowWarning` 与未分类错误。
- **C-7（low，保留残余）**：哨兵被页面自行清除时按同一规则处置——新文档里内容不同 ⇒ 视为真实翻页（与 C-6 同一条），内容没有变化 ⇒ 仍以「页面已导航离开」措辞失败（两侧一致）；标记法与整页导航在「没有内容可比对」时不可分。

## 三之十五、第十四轮对抗性复核（`deleg_9b94dbe4`，复核 `e46ca0c`）判 FAIL 的 A4-7a + C-①…C-⑤，本轮修复

判定原文：B 部分 6 项（runner 259/259、phase3 21/21、载荷 md5 与独立重算一致、platform 552/0/0/5、前端 139/694 + vue-tsc 0、F/N/X/D 族抽样）逐字成立；A1/A2/A3/A5/A6 成立；**A4 因 A4-7a 不成立**；C 部分 2 medium + 3 low。

### 逐条修复（每条都先有复核方的确定性复现，再改代码）

- **C-① / A4-7a（medium｜真实成功被报成失败 + 两侧口径不一致）空 `successTarget` 的登录成功判定对异步提交是单发竞态**：采集侧在提交点击后**单发**检查一次 `hasLeftLoginRoute`（复核方实测 174ms 即判 `login_failed`），而 DSL 侧 `waitForLoginExit` 是 100ms 轮询至 5s —— 同一个异步跳转夹具（XHR 成功后 ~350ms 改 `location`）DSL `passed`、采集侧 `rejected`。修复：新增共享实现 `waitForLoginRouteExit(pageLike, loginPageUrl, budgetMs, pollMs=100)`（`login-route.mjs`），DSL 的 `waitForLoginExit` 与采集侧 `loginIfConfigured` 都改用它（预算：DSL `max(250, min(30000, timeoutMs))`，采集 `max(1000, min(5000, timeout))`），两侧判定与等待都只有一份来源。锁测：`discovery waits for an asynchronous login redirect when no success target is configured`（真实浏览器夹具，跳转在 350ms 后发生）+ `waitForLoginRouteExit` 三向单测。
- **C-②（medium｜失败被吞成通过）`ready===true` 的登录壳 fail-open**：两道登录门与 `authenticated` 都以 `readiness.ready === false` 为前置；无密码框的登录墙（短信/扫码登录，文本命中 intent 词表时 readiness 会判 ready=true）返回登录墙元素 + `authenticated=true`，对照页（intent 不命中 ⇒ ready=false）则正确 `rejected`。修复：**证据内容门不再要求 readiness 非 ready** —— 只要求「配置了登录 + 证据元素是登录表单（凭据输入 + 恰好是登录字样的控件）」，`authenticated` 同步继承该判定。
- **C-③（low）变化与回退同拍时落到原始 Playwright 超时文案**：1000ms 内容变化 + 同拍 1000ms 回退时采样看不到中间态（`changed=false`），回退措辞分支不命中，失败报文落到 `page.waitForFunction: Timeout 6000ms exceeded`。修复：回退分支的条件从 `changed && 内容回到点击前` 扩为 `内容回到点击前 && (changed || 谓词观测到过变化)` —— 谓词（浏览器侧并行等待）知道变化发生过，这种同拍回退同样是回退。
- **C-⑤（low）重登修复成功后的 payload 仍带陈旧 readiness**：最后一次观测发生在重登之前时，`finalObservation.readiness` 停在登录路由（`failureReason='auth_context_lost'`），与同一条 payload 里的 `authenticated=true` 自相矛盾。修复：出口处若本轮出现过 `reauthenticate` 修复轮，重采一次 readiness 写回 `finalObservation.readiness`。
- **C-④（low，保留残余｜两侧一致，非本轮引入）**：整页导航到「无关但带列表」的页面（如 `/blank-list`、`?page=2` 返回错误页）被两侧一致判成翻页成功 —— 与真实导航式翻页在「新文档有列表且内容不同」这一层不可分，属既有设计盲区，本轮如实保留。

## 三之十六、第十五轮对抗性复核（`deleg_aed448f3`，复核 `b2d2740`）判 FAIL 的 A-4（C-⑤ 修复不可达），本轮修复

判定原文：B 部分全部门禁逐字成立（npm 260/260、discovery 79/79、runner-core 44/44、phase3 21/21、载荷 md5 与独立重算一致、platform 552/0/0/5、前端 139/694 + vue-tsc 0）；A 组除 A-4 外全部成立（A4-7a 本轮已修复：350ms 异步 ⇒ DSL passed/IR resolved，父子对照证明上轮缺陷；A1/A2/A3/A5/A6 旧族回归通过）；C 组专项无新增 high（C-① 边界 fail-closed、C-② 三个必需元素集全部被挡、C-③ 1200/1500ms 回退报文含「回退」、C-5 五例两侧分类一致）。**唯一 FAIL：A-4 / C-⑤ —— 重采 readiness 的修复在生产路径不可达（medium）。**

### 逐条修复

- **A-4 / C-⑤（medium｜修复=死代码 + 载荷自相矛盾）**：重采触发挂在 `input.rounds` 上；证据链——平台 DTO `WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest` 无 `rounds` 字段，`web-runner-server.mjs:65` → `server.mjs` 直通请求体，`run-executor.mjs`/`web-runner-server.mjs` 全文无 `rounds` ⇒ **生产永不触发**，复核方 v1（生产形态）实测仍给出 `readiness={"ready":false,"failureReason":"auth_context_lost","pageKey":"…/login"}` 而最终页是 `/app`。修复：改为**采集内部真实发生过重登**的构造 —— `reauthenticate` 动作成功后置位 `reauthenticatedDuringRun`，出口处按此重采 readiness 写回 `finalObservation.readiness`。锁测（真实浏览器、端到端重登链路：登录 → 菜单点击 → `/kill` 会话失效 302 → `reauthenticate` 重登成功 → 最终页）：`a re-authenticated capture reports readiness for the final page, not the login detour`，断言 `authenticated=true`、`pageUrl` 与 `readiness.pageKey` 都指向最终页、`readiness.ready=true`。
- **已知残余（如实保留，复核方本轮明确归类为「方向收窄」而非新增缺陷）**：C-② 的**纯扫码墙（无任何输入）**仍 fail-open —— 证据内容门要求「凭据输入 + 恰好是登录字样的控件」，纯二维码登录墙既无输入也无密码框，不命中；本轮方向是「凭据输入类登录墙全部被挡」，纯扫码墙需要「全部控件都是登录类」这一类新判据，尚未有可靠的可观测信号，暂不引入以避免误杀稀疏业务页。C-① 低危（缺方法 TypeError、NaN/负/0 预算单发检查、poll 超窗 ≤1 poll、页面关闭分类为空）与 C-③ 同拍措辞、C-④ 设计盲区照旧如实保留。

## 四、验证方式与实测结果

真实浏览器（Chromium）验收：

```
cd server/web-runner && node --test --test-concurrency=1 phase3-acceptance.test.mjs
```

实测：20/20 通过（`# tests 20 / # pass 20 / # fail 0`），覆盖验收 1、1b、4、4b、5、5b、5c、6、6b、6c、6d、6e、6f、7、8、9、10、11、12、13。freshness 用例与之一并跑为 21/21。

全量回归：

| 套件 | 命令 | 实测 |
| --- | --- | --- |
| web-runner | `cd server/web-runner && npm test` | 见「七、最近一次全量结果」 |
| platform | `cd server/platform && ./mvnw test` | 见「七、最近一次全量结果」 |

取证脚本（生成平台测试用的真实证据，可复跑、复跑字节稳定，且强制新鲜度）：

```
cd server/web-runner && node scripts/record-phase3-evidence.mjs
```

取证端口固定为 `41827`（可被 `PHASE3_RECORD_PORT` 覆盖），落盘前会把易变计时字段（`durationMs`/`remainingDurationMs`）归一化为 0，因此连续两次取证的四个载荷 `shasum -a 256` 完全一致（`REPLAY_BYTE_STABLE=yes`）；「复跑不留改动」是可复核事实而不是声明。

输出：
- `server/platform/src/test/resources/ai/evidence/phase3-runner-response.json`（iframe + open shadow 的原始 runner 响应）
- `server/platform/src/test/resources/ai/evidence/phase3-runner-action-response.json`（跨页 Action IR 的原始 runner 响应 + `pageSnapshots` + `runnerImplementationHash`）
- `server/platform/src/test/resources/ai/evidence/phase3-runner-navigate-response.json`（**导航型**跨页 run：第 1 步是 route 导航、无元素引用，两页快照都带元素；含 `assert-detail-url` 地址断言步骤）
- `server/platform/src/test/resources/ai/evidence/phase3-runner-plain-start-response.json`（**无元素起始页** run：起始页没有任何可采集元素，快照带 `elementsEmpty: true` + `elementsEmptyReason`）

平台侧测试：

```
cd server/platform && ./mvnw -q -Dtest='WebCaseCompilationServiceTest,WebCaseCompilationServicePhase3EvidenceTest,WebAutomationLocatorDiscoveryEvidencePayloadTest,WebAutomationCaseValidatorTest' test
```

## 五、结构化 locator 的实现口径

稳定化计划建议长期证据保存结构化 locator（`{strategy, role, name, exact, scope}`）。实现采用同构但更显式的形式（与可执行 locator 逐字对应）：

```json
{
  "strategy": "role",
  "role": "button",
  "name": "帧内按钮",
  "exact": true,
  "selector": "#inside",
  "framePath": "#inner",
  "shadowPath": "",
  "frameUrl": "…/scoped-frame",
  "pageScope": "document"
}
```

差异点：祖先作用域用 `framePath` / `shadowPath` 表达（而不是计划里的嵌套 `scope` 对象），因为 runner 采集、`resolveLocator` 执行、编译器序列化、DSL 校验四处都使用同一条链式字符串（`frame=#inner >> role=button[name="帧内按钮", exact=true]`），序列化结果可由测试直接断言；`pageScope` 表示 main/form/dialog/navigation/table 分区，避免与祖先 `scope` 同名歧义。

## 六、边界与已知限制

1. 取证页面是本地静态页（真实 Chromium、真实 HTTP），不是内网 haitlify 应用；内网页面上的元素规模、iframe/shadow 结构与预算取值仍需在真实环境复核。
2. 异步 postcondition 采用「稳定观测」判定（连续两次指纹一致且有变化），超时记录为 `settle_timeout` / `no_page_change`，不判失败；`no_page_change` 会带 `warning`（需要更强断言的场景应在测试点里显式加 `assertVisible/assertText`）。
3. closed shadow root 的检测：Chromium 下用 CDP（`DOM.getDocument{pierce:true}`）精确枚举，`unsupported.closedShadowDetection=cdp`；非 Chromium 或 CDP 不可用时回退启发式（自定义元素 + 无 light DOM 内容 + 可见，或 light-DOM 文本与渲染文本不一致），此时标 `heuristic` 并可能漏检。Playwright 无法穿透 closed shadow root，因此这类目标只能返回明确错误。
4. 元素预算默认值：整体 320、单分区 160（`DEFAULT_ELEMENT_BUDGET` / `DEFAULT_PARTITION_BUDGET`），可由调用方通过 `elementBudget`/`partitionBudget` 覆盖；被丢弃的元素只出现在诊断里，不再静默截断。
5. `evidenceId` 是内容派生的（含 locator 串），因此**页面无关**：同一元素出现在两个页面会得到同一个 ID。跨页面的强制点在编译期（`findMatchingStepPage` + 「目标必须属于当前页面证据」），运行期只拒绝「同一 evidenceId 跨页面复用同一个步骤」；locator 串格式一旦变化，所有 evidenceId 都会重算，所以测试必须现场取证、禁止硬编码。
6. `unsupported_frame` / `unsupported_shadow_dom` 是「目标不可观测 + 页面存在不可观测子树」的推断，带 `scopeUncertain: true` 供调用方区分；无法在外部证实目标确实位于该子树内。
7. 「起始页 0 个可采集元素」这条分支（验收 13）在生产路径的可达性尚未证实：装配前有 `scores().matched()` 闸门（`AiDraftTestPointService`，生产预算 `minimumTotalScore=70`），录制载荷自身得分 0、复核方实测 live 场景 45 分，均低于该闸门；本条验收以定向测试（`keepsAnEmptyStartPageBundleSoRouteStepsStillCompile`）+ 第 4 个真实载荷证明，不代表生产流量会走到。
8. 生产 AI 规划器是否会产出「非路由形状」的 navigate/assertUrl 目标尚无真实计划样本可证；现在这类值会在计划期被 `ActionIrValidator` 拦下（编译期/执行期另有同口径守卫），行为是**响亮的编译失败**而不是静默错编译。

## 七、最近一次全量结果（2026-09-25，第十五轮复核 FAIL 的 A-4（C-⑤ 修复不可达）修复后，即第十六轮修复）

- web-runner 全量：**tests 261 / pass 261 / fail 0 / cancelled 0 / skipped 0**（`npm test`，在载荷重录**之后**运行；本轮首次链跑同样把 npm 放在重录之前，freshness 按设计报 `已过期`，顺序纠正后全绿）。分包：`page-locator-discovery.test.mjs` **80/80**（本轮新增 1 条端到端重登锁测：登录 → 菜单 → `/kill` 会话失效 302 → `reauthenticate` 重登 → 最终页，断言 `pageUrl`/`readiness.pageKey` 一致且 `ready=true`；夹具登录用纯 `location` 跳转——页面侧 `fetch` 型登录在本仓库夹具下实测不会完成，`waitForUrlMatch` 超时 2×25s）、`runner-core.test.mjs` **44/44**。
- 阶段 3 定向：`phase3-acceptance.test.mjs` + `phase3-evidence-freshness.test.mjs` **21/21**（`runnerImplementationHash=e35f7179dcd81f48e5a80f4010dad67e43dec4a4899908966b68beb4c504864e`；连录两遍 4 份载荷 md5 一致：`62c3ced6f14db70a7624b244aa1b8a64` / `620c4729db1dd0b2816932c938f50586` / `bfdb5f8946a43971c085862652b33043` / `730a6653e7a0f8a58a84b3fe4eb331fa`）。
- platform 全量：**tests=552 / failures=0 / errors=0 / skipped=5**（BUILD SUCCESS，报告 102 份）。前端：`npx vue-tsc --noEmit` exit 0；`npx vitest run` **139 files / 694 tests** 全过。
- 复核方探针复跑（第十五轮复核方 11 个探针脚本，直接 import 仓库模块；日志 `<synthetic-hermes-cache>/scratch/adv16/fin-*.log`）：**`p-a4-reauth` v1（生产形态、不带 `input.rounds`）⇒ `readiness={"ready":true,"failureReason":"","pageKey":"…/app"}`、`authenticated=true`、修复轮序列 `["menu_search","reauthenticate",""]`** —— A-4 关闭；`p-a1-async-login`、`p-a2-login-gates`、`p-a2b-walls`、`p-a3-fallback`、`p-a5-oldfamilies`、`p-a6-phases` 全部 exit 0；`p-c5-consistency` **5/5 两侧分类一致（`allConsistent=true`，含 C-④ 共享盲区与回退措辞）**；`p-c1-edges` 边界族按预期 fail-closed（并发调用互不影响、`hasLeftLoginRoute` 空/垃圾输入为 false、页面被关闭时抛出（分类为空＝已知低危））；`p-a5b-hash` hash 路由登录失败/成功两条判因正确。

### 历史记录：第十五轮修复时点（提交 `b2d2740`，第十四轮复核 FAIL 的 A4-7a + C-①…C-⑤ 修复后）

- web-runner 全量：**tests 260 / pass 260 / fail 0 / cancelled 0 / skipped 0**（`npm test`，`runner_exit=0`）。分包：`page-locator-discovery.test.mjs` **79/79**（本轮新增 1 条真实浏览器锁测：空 `successTarget` + 350ms 异步跳转的登录必须 resolved）、`runner-core.test.mjs` **44/44**（新增 `waitForLoginRouteExit` 三向单测：立即离开 / 两轮后离开 / 预算内始终不离开）。
- 阶段 3 定向：`phase3-acceptance.test.mjs` + `phase3-evidence-freshness.test.mjs` **21/21**（`runnerImplementationHash=29bb20d258221341afed6dbe190163abfc26627cc03d92ef19b5d471d5af0489`；连录两遍 4 份载荷 md5 一致：`ab8d9940d120d97055c53cb216bef66b` / `6ab499b121c30c89d519a26766f05c99` / `f1f5052b170d0b884a548296fb410663` / `3f5e40b666a6648eabb9b98c3b12f7fc`）。注意流程顺序：**必须先重录载荷再跑 phase3 测试** —— 本轮首次链跑把测试放在重录之前，freshness 按设计报了 4 份载荷 `已过期`（哈希 `92d8a1b8…` → `29bb20d2…`），顺序纠正后全绿。
- platform 全量：**tests=552 / failures=0 / errors=0 / skipped=5**（BUILD SUCCESS，报告 102 份；运行前删除历史 surefire 报告与 `target/test-classes` 里的临时探针类）。
- 前端：`npx vue-tsc --noEmit` exit 0；`npx vitest run` **139 files / 694 tests** 全过。
- 复核方探针复跑（**第十四轮复核方自带的 22 个探针脚本**，直接 import 仓库模块、测的是本轮代码；日志 `<synthetic-hermes-cache>/scratch/adv15/r15-*.log` 与 `/tmp/adv15/*.log`）：`a47-async-login` **v1（空 successTarget + 异步提交）⇒ resolved（377ms；修复前 rejected@174ms）**、v2/v3/v4/v5 同判；`c2-pathname` **p5（ready=true 手机号登录壳）⇒ rejected/login_failed（修复前 resolved）**；`a6` r1a/r1b 报文含「回退」；`a1`（陈旧样本）ok 6/6、`a2`（Infinity 族）ok 11/11、`a3a4`（登录门 19 例）ok 16/16（A4-4/5/6 业务页对照全 resolved、A4-7a/A4-7b 合法登录 resolved、A4-10 登录失败 rejected）、`a5`（导航哨兵）ok 4/4。
- 如实记录两条非绿项：①`a6` 的 r1c「变化与回退同拍、采样与谓词都观测不到中间态」保留原始超时措辞——没有可观测证据时不宜臆造「回退」，属可观测性边界；②`c-family` 的 c2a–d 端到端为 resolved（证据页是业务内容、readiness 正常），探针内写死的 `expect: rejected` 是探针期望而非行为缺陷，且**在第十四轮复核方自己的原始输出（`<synthetic-hermes-cache>/scratch/adv14/c-family.jsonl`）中同样是 resolved/ok:false**，与回执「已排除的同族怀疑」一致，非本轮引入。

### 历史记录：第十四轮修复时点（提交 `e46ca0c`，第十三轮复核 FAIL 的 A4/A5 + C-1…C-9 修复后）

- web-runner 全量：**tests 259 / pass 259 / fail 0 / cancelled 0 / skipped 0**（`npm test`，`runner_exit=0`）。分包：`page-locator-discovery.test.mjs` **78/78**（本轮新增 3 条锁测：遮罩常驻两侧同判、导航式翻页两侧同判、登录壳纯函数 `evidenceLooksLikeLoginShell`）、`runner-core.test.mjs` **44/44**（新增路由比较锁测：`hasLeftLoginRoute` 六向 + hash 前缀向 + `hasChangedRoutePath` 四向）。
- platform 全量：**tests=552 / failures=0 / errors=0 / skipped=5**（BUILD SUCCESS，报告 102 份；运行前删除历史 surefire 报告与 `target/test-classes` 里的临时探针类；在重录四个 phase3 载荷之后运行）。
- 阶段 3 定向：`phase3-acceptance.test.mjs` + `phase3-evidence-freshness.test.mjs` **21/21**（`runnerImplementationHash=92d8a1b88c277e572700b0b54d0b17e86f118eca1df8ad36fb56af3ada2170e6`；连录两遍载荷 md5 一致：`c23218b090c4a82d1fc522a46ec2c8d9` / `62c3ef8f74988273d19842df414ffa2b` / `52e2c4f6a2d66b6194ee0c40432ae6c4` / `e42007c9efdf575b6f600efe8a298196`）。
- 复核方探针复跑（第十三轮复核方自带的 11 组探针，直接 import 仓库模块、测的是本轮代码；日志 `<synthetic-hermes-cache>/scratch/adv13/r14-*.log`）：A4 控制例 **DSL 7/7 + IR 7/7 不符预期=0**（含「内容先变 + 遮罩常驻」⇒ 两侧同判 `pagination_not_changed`，after 证据不再带着 loading 被当成功）；A5 的 `timeoutMs=Infinity` / JSON `1e400` / `"Infinity"` / `2^31-1` / `12000000000` **两侧同判 passed/resolved**（不再出现 `Timeout Infinityms exceeded` 未分类），观测失败两侧同分类 `observation_failed`、导航离开两组同分类；`c2a` 真·导航式翻页 **DSL 3/3 passed + IR resolved**（DOM 行确为第 2 页），`c2d` `location.reload()` 型两侧同 `pagination_not_changed`；`c3c`（复用会话 + 密码框业务页 + 页内 hash 导航）**resolved + authenticated=true**，`c3f-1`（凭据登录被挡回登录页）与 `A2-5c`（同路由登录视图）**rejected/login_failed**，`A2-7` / `A2-9` / `A2-10` / `A2-11` 仍 resolved（无误杀）；`p7` 的 `c1a/c1d` passed、`c1b` 回退 failed；`p11` 的 `c4a` 回退两侧同判 failed。
- 说明：`p2-a2` 探针在「建立会话后立刻复用同一会话」的用例上受 runner 会话租约限制返回 `session_busy`（该族第十三轮的评分证据来自 p2b/p2c/p2d 探针；本轮已补跑）。
- 前端：`npx vue-tsc --noEmit` exit 0；`npx vitest run` **139 files / 694 tests** 全过。

### 历史记录：第十三轮修复时点（提交 `32a71e1`，第十二轮复核 FAIL 的 2 high + 3 medium + 2 low 修复后）

- web-runner 全量：**tests 255 / pass 255 / fail 0 / cancelled 0 / skipped 0**（`npm test`，`runner_exit=0`）。分包：`page-locator-discovery.test.mjs` **75/75**（本轮新增 2 条真实 Chromium 死点击锁测：DSL 与 IR 各一条「死点击 + 100ms 相位自刷新 ⇒ `failed`/`pagination_not_changed`」）、`runner-core.test.mjs` **43/43**、`page-evidence.test.mjs` 10/10、`run-job-manager.test.mjs` 6/6。
- platform 全量：**tests=552 / failures=0 / errors=0 / skipped=5**（BUILD SUCCESS，报告 102 份；运行前删除历史 surefire 报告与 `target/test-classes` 里的临时探针类；在重录四个 phase3 载荷之后运行）。
- 阶段 3 定向：`phase3-acceptance.test.mjs` + `phase3-evidence-freshness.test.mjs` **21/21**（`runnerImplementationHash=9561b679c9fe3626db7f47d0b3ab470301f0f7bb4e2ebab74c702a031ae32e76`；连录两遍 4 份载荷 md5 完全一致：`bae87bc976ae4138bb443a2f75d1ccd0` / `5e032e06f3e278118e8c160d47f4a34f` / `c4d7265847fcffd09eeb8db64023cdee` / `615c8b9c986481524eaa160b9a8a7e71`）。
- 复核方探针复跑（第十二轮复核方自带探针里与本轮改动相关的 14 组，脚本直接 import 仓库模块、测的是本轮代码；日志在 `<synthetic-hermes-cache>/scratch/adv13/b2-*.log`）：pC1 死点击全周期 6/6 `failed`（含相位 100/250ms、周期 10500ms 两盏灯边界）+ 2 条对照按期望；pC1b 相位 120–220ms **6/6 failed**；pC3b 回滚窗 **6/6 failed**；pA6 8 条全按期望（含加载态常驻、占位回滚、短文本非加载词 passed）；pA1 DSL 12/12 passed（含遮罩 900/960ms）+ IR 7/7 resolved；pA2 3 resolved + C4 陷阱 2/2 failed；pA45 三套登录面按期望（A5b1/A5d/A4-iii/跨源 SSO resolved；A4-i/A5c1/A5c2/A5c3 与两条会话失效回跳 rejected）；C-rest 的「导航离开」DSL 与 IR 两侧同为 `pagination_not_changed`、无遮罩晚到两条 passed、C6 `timeoutMs=-1/0` 两侧一致（非正值取上限 30s）；A3 相位扫描 **26/26 failed**；`pC-late-chunks` 遮罩在数据到达时隐藏 passed / 遮罩常驻 fail-closed。
- 前端：`npx vue-tsc --noEmit` exit 0；`npx vitest run` **139 files / 694 tests** 全过。

### 历史记录：第十一轮修复时点（提交 `7eb8386`）

- web-runner 全量：**tests 253 / pass 253 / fail 0 / cancelled 0 / skipped 0**（`npm test`，`runner_exit=0`）。分包：`page-locator-discovery.test.mjs` **73/73**、`runner-core.test.mjs` **43/43**（本轮两侧各新增 1 条「变化回退到点击前内容」用例；discovery 侧另新增 1 条「配置了登录时业务页不被误判成登录失败」用例）、`page-evidence.test.mjs` 10/10、`run-job-manager.test.mjs` 6/6。
- platform 全量：**tests=552 / failures=0 / errors=0 / skipped=5**（BUILD SUCCESS，报告 102 份；运行前已删除历史 surefire 报告与 `target/test-classes` 里的临时探针类；本轮在重录四个 phase3 载荷之后运行，`WebAutomationLocatorDiscoveryEvidencePayloadTest` 消费的是新载荷）。
- 阶段 3 定向：`phase3-acceptance.test.mjs` + `phase3-evidence-freshness.test.mjs` **21/21**（本轮重录载荷，`runnerImplementationHash=c0d12abeaa6707dffc26961b1af53bec133fed0cab51c73d72f39f400f4c0707`；连录两遍 4 份载荷 md5 完全一致：`cc4729f6453ebf7b41dc3c1b887d358d` / `33a8449c25ecf40327d7d71e661360a4` / `1ed325a95cbac39b336aa10ad890280b` / `d4ab2271116ebef8776b0be3118e9f9f`）。
- 复核方探针复跑（最终代码）：见「三之十二 / 复核方探针复跑结果」——DSL `a1` 6/6、`a1b` 4/4、`a2` 4/4、`a5` 3 passed + 2 按期望 failed、`c` 2 passed + 7 按期望 failed、`c2` 0/4（4 条全 failed）、`cross` 3/3、`a3` 9/9 按期望 failed；IR `a1` 5/5、`c2` 0/4、`cross` 3 resolved + 1 rejected；相位扫描 6/6 failed；C5 类页面 resolved；`extra` 的 GATE 页面 resolved 且 `authenticated=true`。
- 前端：`npx vue-tsc --noEmit` exit 0；`npx vitest run` **139 files / 694 tests** 全过。

### 历史记录：第十轮修复时点（提交 `7eb8386`）

- web-runner 全量：**tests 247 / pass 247 / fail 0 / cancelled 0 / skipped 0**（`npm test`，`runner_exit=0`）。分包：`page-locator-discovery.test.mjs` **70/70**、`runner-core.test.mjs` **40/40**（本轮各新增 1 条真实异步翻页用例；discovery 侧另新增「空 `successTarget` 登录必须判失败」1 条）。
- platform 全量：**tests=552 / failures=0 / errors=0 / skipped=5**（BUILD SUCCESS，`platform_exit=0`；报告文件 102 份。计数按 surefire 报告聚合，已在运行前删除全部历史报告文件与 `target/test-classes` 里的临时探针类；本轮在重录四个 phase3 载荷之后运行）。
- 阶段 3 定向：`phase3-acceptance.test.mjs` + `phase3-evidence-freshness.test.mjs` **21/21**（本轮重录载荷，`runnerImplementationHash=3873275a0aba1dc4958c7abdc112edeaf72b5fc22e4a1fe6c597263cbb328f01`；复跑两遍，4 份载荷 md5 前后完全一致：`a451bae062b9ae50b9e98beb93a218d9`/`c5246d41a6c0dde679c67bc77c95e94e`/`e98cf4f6cbf474a673a02332fb269d51`/`92400d426ca8a7aba805350c7e435ffa`，合并 md5 `cbd164c2c1e2a0a0b56464d092d64549`）；`page-evidence.test.mjs` **10/10**；`web-runner-server.test.mjs` 失败带证据断言仍通过；`run-job-manager.test.mjs` **6/6**。
- 复核方探针复跑（第十轮复核方自带的 D1–D3 复现脚本，脚本直接 import 仓库模块，不依赖 runner 服务；脚本与日志在 `<synthetic-hermes-cache>/scratch/adv10-review/*.round11.log`）：`p7` E1（真实异步翻页，修复前两侧都判失败）`{"status":"passed","durationMs":2340,"finalText":"第2页 行1|第2页 行2"}`、E2/E3 亦 passed；`p9`（`.el-pager li` 指示器 + 异步数据）`DSL_ELPAGER_ASYNC=passed`；`p3` C1–C4（周期 1.2/3/5/8s 的死点击）全部 `pagination_not_changed`（报文带节律诊断），C5–C7（真实点击 + 页面自刷新，delay 400/2000/3000ms）全部 passed，C8（`timeoutMs=5000`）failed；`p5` DSL `failed:pagination_not_changed` 且 IR 2s/20s 均 `rejected`（两侧一致）；`p1` 采集侧 `{"outcome":"rejected","category":"login_failed"}`（修复前为 `resolved`）。

### 历史记录：第八轮（F1–F6）修复时的 HTTP 复测（脚本 `<synthetic-hermes-cache>/scratch/adv9/http-round8-checks.mjs`；runner `127.0.0.1:19452` + 夹具页 `19451`）

  | 场景 | 修复前（复核方实测） | 修复后（本机实测） |
  | --- | --- | --- |
  | DSL 登录失败（hash 路由 `#/login` → `#/login?error=1`，只多 `?error=1`） | 登录等待 **passed**（整条用例通过而应用从未离开登录页） | 登录等待 **failed**：`原始等待错误：login did not leave the login route: still on "…/hash-fail#/login?error=1"`（消息脱敏），`failedSteps=["waitForUrl"]` |
  | 同场景对照：登录成功（`#/login` → `#/project`） | passed | **passed**，后续 `assertVisible 项目视图` 也 passed（无 failed step） |
  | DSL `paginate` 于**页面自更新**的列表（`setInterval` 每 80ms 换行文本）+ 不响应的按钮 | 步骤 **passed**（变化被算作点击成功） | 步骤 **failed**：`pagination_not_changed: page.waitForFunction: Timeout 3000ms exceeded.` |
  | IR 同场景 | 200，`pageChanged=true` | **422** `pagination_not_changed`（`before` `自动行 1` → `after` `自动行 43`，指示器未变 ⇒ 不归因于点击） |
  | IR `paginate`：真实分页列表在第 9 个容器（前面 8 个侧边栏 `<ul>` + 长段落） | 422 `pagination_not_changed`（真实成功被报失败） | **200 passed**（`digests` 第 9 项 `f69789fb` → `f6978e3c`） |
  | IR `paginate` 于 `<ul>` 列表翻页 | 200 passed（但与回放不一致） | **200 passed**，同页 DSL `paginate` 也 **passed**（两侧一致） |
  | N5 回归：表格行数据真实翻页（DSL） | — | **passed** |

- 取证复跑：本轮重录四个载荷后 `runnerImplementationHash` = `3873275a0aba1dc4958c7abdc112edeaf72b5fc22e4a1fe6c597263cbb328f01`，freshness 21/21 通过；`git diff --stat` 显示 4 份载荷各只有 1 行变化，且 `grep -v runnerImplementationHash` 后无任何语义差异（录制的场景不经过 paginate 路径）。上一轮的载荷差异（第九轮）集中在 `listSignature` 由整份快照 JSON 改为投影 `[indicator, head, digests, tail, count]`、`loading` 移出签名并新增 `paginationLoading`。
- 已知限制（本轮更新）：见「三之十一」末尾三条 —— （a）页面自刷新周期长于等待预算时，一次自刷新仍可能被算作「点击生效」（现在两侧同一判据、同一条节拍诊断，且 1.2s–8s 周期带内的死点击全部被拦下）；（b）归因证据只覆盖分页容器与页码指示器；（c）不在 `PAGINATION_LOADING_SELECTOR` 内的「加载中」占位可能成为 `after` 证据（证据质量问题，不改变通过/失败判定）。
- 前端：`npx vue-tsc --noEmit` exit 0；`npx vitest run` **139 files / 694 tests** 全过（本轮新增：工作台分类渲染 6 例 —— `navigation_failed`→「页面跳转失败」、`unsafe_action`/`locator_persistence` 不再折叠且不再显示「需人工接管」、`unsupported_action`/`pagination_not_changed`/`action_failed` 有中文标签；导入 `upload …` 行退化为人工步骤 1 例）。
- 已知限制（**第九轮时点，已在第十轮修复**，见「三之十一」）：签名按「每个容器一份摘要」且摘要按原文计算；当时仍存在的边界是——（a）页面自更新时只认页码指示器，某分页控件没有可识别指示器时真实翻页会被判 `pagination_not_changed`；（b）内容型变化要经过固定安静窗口（max(3.5s, 步骤超时)，上限 10s）才归因，**刷新周期长于该窗口的页面**仍可能把自刷新误判成点击成功；（c）时钟形状文本算真实变化。其中（a）与（b）正是第十轮复核 D1/D3 判 FAIL 的两处，本轮改为节律判据后已修复。

历史基线（第九轮复核修复后、提交 `78f8df2` 时点）：web-runner 244/244、platform 552/0/0/5、前端 139/694。第十轮复核在这批代码上判 FAIL 的 D1–D3 已在本轮全部修复，见「三之十一」。

历史基线（第八轮复核修复后、提交 `7e5dc48` 时点）：web-runner 238/238、platform 552/0/0/5、前端 139/694。第九轮复核在这批代码上判 FAIL 的 X1–X5 已在下一轮全部修复，见「三之十」。

历史基线（第七轮复核修复后、提交 `8e37289` 时点）：web-runner 233/233、platform 552/0/0/5、前端 139/687。第八轮复核在这批代码上判 FAIL 的 F1–F6 已在本轮全部修复，见「三之九」。

历史基线（第五轮复核修复后、提交 `ab91f2b` 时点）：web-runner 218/218、platform 547/0/0/5。

### 历史记录：第七轮修复时的 HTTP 复测（脚本 `<synthetic-hermes-cache>/scratch/adv8/http-round7-checks.mjs` 与 `n3-probe.mjs`；runner `127.0.0.1:19452`/`19462` + 夹具页 `19451`/`19461`）

  | 场景 | 修复前 | 修复后（实测） |
  | --- | --- | --- |
  | DSL `paginate` 点击后**只换表格行文本**（真实翻页） | 步骤 **failed**（谓词在浏览器侧 `ReferenceError`，被报成 `pagination_not_changed`） | 步骤 **passed**（`tbody` 文本确认为「第二页行一」） |
  | DSL `paginate` 点击后列表不变（对照组） | failed | **failed**：`pagination_not_changed: page.waitForFunction: Timeout 30000ms exceeded.` |
  | DSL 平台词表别名 `wait_for_visible` / `scroll_into_view` | 步骤 **passed**（静默空转） | **passed**（真实执行：等待可见 + 滚动进视口） |
  | DSL 未知动作名 `clickk` | 步骤 **passed**（静默空转） | **failed**：`Unsupported step action: clickk`（分类 `unsupported_action`） |
  | DSL 登录成功判定：GET 表单提交后仍停在 `/login-query?…`（仅 query 变化） | 登录成功等待 **passed** | **failed**：`原始等待错误：login did not leave the login route: still on "…/login-query?…"`（消息脱敏） |
  | DSL 单字符步骤值 + 后续失败步骤（脱敏可读性） | 消息被打成 `#does-not-e[REDACTED]ist` | **failed** 且消息完整可读：`waiting for locator('#does-not-exist')` |
  | IR `paginate` 点击后只换行文本（真实翻页） | 422 `pagination_not_changed` | **200**，`stepEvidence[0].before.contentSignature="1|第一页行一"` → `after="2|第二页行一"` |
  | IR `select` 作用于无标签 `<select>`（采集器证据） | 采集期名来自 option 文本、回放期 0 命中 | 采集 `accessibleName=""`、`locator="css=#kind"`；用该证据 `select` → **200** |
  | IR `fill` 值来源 `{kind:'testdata', key:'deviceName'}`（无内联值） | 把键名填入页面，步骤 passed | **422** `invalid_action_ir`：`Action IR testdata value has no inline value: deviceName` |

- 取证复跑：四个载荷连续两次 `shasum -a 256` 一致（`BYTE_STABLE=yes`），`runnerImplementationHash=e8c6947ef037bf350fa219bfd2df23d699531b29326baf9d2007aa0b271e82ff`。
- 前端：`npx vue-tsc --noEmit` exit 0；`npm test -- --run` **139 files / 687 tests** 全过（含本轮新增的分类保留用例：`navigation_failed`/`unsafe_action`/`locator_persistence`/`unsupported_action` 原样保留，`page_error` 仍折叠为 `unsupported_dom`）。
- 已知限制（该时点，**均已在第八轮复核后修复**）：`contentSignature` 只覆盖前 8 个容器、4000 字符；页面自更新时任何文本变化都算「已变化」；hash 路由登录失败仍算离开登录页；工作台 UI 仍折叠分类且缺新分类标签；前端仍宣传 runner 未实现的 `upload`。

历史基线（第六轮复核修复后、提交 `1178433` 时点）：web-runner 227/227、platform 551/0/0/5。第七轮复核在这批代码上判 FAIL 的 6 条验收 + D1–D8 全部复现为 pass/闭环，另提的 N1–N10 已在第七轮修复，见「三之八」。

### 历史记录：第六轮修复时的 HTTP 复测（脚本 `<synthetic-hermes-cache>/scratch/adv7/http-round6-checks.mjs`；runner `127.0.0.1:19432` + 夹具页 `19431`）

  | 场景 | 修复前 | 修复后（实测） |
  | --- | --- | --- |
  | IR 元素型动作带 `{kind:'route'}` 目标 | `TypeError` → **503**，无分类、无证据 | **422** `{"category":"invalid_action_ir"}`，消息含动作 id 与原因 |
  | IR `select` 作用于 `input`（未分类的 Playwright 错误） | **503**，`stepEvidence` 全丢 | **422** `{"category":"action_failed"}`，`stepEvidence=["select-on-input:failed/action_failed"]`，含 `before` |
  | IR `paginate` 点击后列表不变 | — | **422** `{"category":"pagination_not_changed"}`，`stepEvidence=["next-page:failed"]` |
  | IR `select` + `value.kind=runtimevariable`（请求不带变量） | 平台放行 → 取证期失败且原因不明 | **422** `invalid_action_ir`，消息指名到变量 `kindOption` |
  | 同上（请求带 `variables.kindOption=b`） | — | **200**（真实选择成功，值来源可解析） |
  | DSL `goto /listing` + `assertUrl /list` | 步骤 **passed**（子串匹配） | 步骤 **failed**：`assertUrl failed: expected "/list" in "http://127.0.0.1:19431/listing"` |
  | DSL `assertUrl /list` + `assertUrl http://…/list`（对照组） | passed | **passed**（不过度拒绝，绝对值/相对值同口径） |
  | DSL `goto ''` | 步骤 **passed**（被当成打开 baseUrl） | 步骤 **failed**：`goto requires a target URL` |
  | DSL 登录 `successTarget=''`（登录页始终不变） | 登录成功等待被吞成**通过** | 登录成功等待 **failed**：`原始等待错误：login did not leave the login route: still on "…/login"`（消息经脱敏） |

- 取证复跑：四个载荷连续两次 `shasum -a 256` 一致（`REPLAY_BYTE_STABLE=yes`），`runnerImplementationHash=a271f126f8afd93d0b8f3181dbf99c007bdfe182b42888b556e498f5a84aada0`。
- 前端：`npx vue-tsc --noEmit` exit 0；`npm test -- --run` **139 files / 686 tests** 全过（该基线为更早时点，本轮未改前端）。
- 已知限制（第六轮时点，**已在第七轮复核后修复**）：无标签 `<select>` 的采集器 role locator 在 Playwright 侧可访问名不同源，`resolveStepTargetEvidence` 会以 `ambiguous_locator` + `matchedElementCount=0` fail-closed。本轮改为采集期不把内容文本当表单控件的名字（退回 `testId`/CSS），同一证据可直接回放。

历史基线（第五轮复核修复后、提交 `ab91f2b` 时点）：web-runner 218/218、platform 547/0/0/5。第六轮复核在这批代码上判 FAIL 的 D1–D8 已全部修复，见「三之七」；第七轮复核提出的 N1–N10 见「三之八」。

修复过程中出现过的测试自身缺陷（非产品缺陷），都已修正并复跑：`acceptance 9` 按 `testId === ''` 查找元素（描述符缺省是 `undefined`）；`page-locator-discovery.test.mjs` 两条用例硬编码内容派生的 evidenceId；本轮一条 tab 用例硬编码了不带 `exact` 的 locator、三条登录用例断言了不带 `exact` 的提交按钮；平台用例按数组下标取步骤证据（新增地址断言步骤后下标位移），已改为按 `actionId` 查找。

历史基线（第一轮修复后、提交 `672c403` 时点）：web-runner 200/200、platform 538/0/0/5。第二轮复核在这批代码上判 FAIL 的 2 项残余（导航型跨页、同名无 id shadow host）与 3 条残余，已在本轮全部修复，见「三之二」。

## 八、终审：第十六轮独立复核判定 PASS（2026-09-25）

- 复核对象：`966ad48c2b4c6abaa3545c9296930b35e0d6c2c8`（第十六轮修复，父提交 `b2d2740`）；复核方 `deleg_1e6ab7df`（1 leaf，2868.87s，api_calls=218），自建夹具 + 真实 runner，结论全部来自其亲手执行的工具输出。
- 判定：**PASS —— A 段全成立、B 段全部门禁逐字成立、C 段无新增 high/medium。** 这是本阶段十六轮对抗性复核中第一次通过。
- A 段要点：A-1/A-4 生产形态（载荷键 = 平台 DTO 字段集，无 `rounds`）三形态（menu-kill / session-consumed-302 / multi-reauth）全部 `ready=true / pageKey=最终页 / authenticated=true`；**父提交对照决定性翻转**（父：`ready=false / auth_context_lost / pageKey=登录路由` + `authenticated=true`）；反向面：重登后又被挡回登录墙 ⇒ 仍 `login_failed`（未被洗白）、重登失败 ⇒ 分类不变；A4-7a 350ms/1200ms 两侧 passed/resolved，6s 超预算两侧同判失败（分类名不同，如实报告）；C-② 三个元素集全部被挡且业务页对照不误杀；C-③/C-④ 与既定口径一致；旧族 A1/A2/A3/A5/A6 回归通过（含 A2 Infinity 13/13、A3 hash 路由与 302/软 200 会话门 7/7、A6 相位 100/500ms 两侧 fail-closed）。
- B 段实测：`npm test` **261/261**（discovery 80/80、runner-core 44/44）；载荷连录两遍 md5 三处一致 + `runnerImplementationHash=e35f7179…` 与其独立重算一致；phase3 21/21；platform 552/0/0/5 + BUILD SUCCESS + 102 报告；前端 vue-tsc exit 0 + vitest 139/694；F/N/X/D 族抽样 9/9；`git status` 无新增。
- C 段要点：置位与重采边界全部按预期（多次重登、重登后仍在原路由、重登失败、重采自身超时 ⇒ 出口多等实测 5033ms 且分类不变）；无重登时零额外等待（279ms vs 10699ms 对照）；**锁测有牙**（同一测试打到父提交即失败于 `readiness.ready === true`）；两侧分类 8 组中 7 组一致（唯一不一致为对相位敏感的陈旧样本族，已在既有口径内）。
- 两条 low 观察（均非本轮引入，按复核方建议**不顺手修改**）：L1 `exploration.readiness` 副本仍停留登录路由（主消费者 `WebStepPageEvidenceAssembler.applyPageIdentity` 已读 `finalObservation`；兜底与 `AiDraftTestPointService.java:947` 的 `ready = readiness==null || ready==false` 为反向表达式，换新样本会翻转 `CONFIRMED_PAGE_MISS` 门，故保持现状并记录）；L2 DSL 与 IR 的「点击前窗口」宽度不同（DSL 在点击前一刻取快照，IR 的 `beforeState` 取自动作前观测，窗口更宽）——结构性观察，未证明具体误归因实例。
- 已知残余照旧（均经复核方归类为非阻塞）：纯扫码墙（无输入）fail-open；同拍变化+回退落到原始超时措辞；整页导航到「无关但带列表」页面两侧一致判成功（设计盲区）；页面被关闭时分类为空。
