> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Web 用例 Agent 可靠性与流程收敛实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Web 用例流程收敛为“历史素材优先、真实浏览器 UI 复核、确定性编译、失败可诊断和人工可接管”，减少重复 AI 调用和互相覆盖的取证流程，同时保持现有 Studio Canvas 页面风格、布局和组件尺寸。

**Architecture:** Platform 负责测试点、自然语言计划、历史素材候选和严格结果校验；Web Runner 负责唯一的浏览器执行边界、登录会话、页面状态识别、只读修复动作和当前 DOM 证据；AI 只负责自然语言理解、候选排序和有限修复建议，不能直接产生未经浏览器验证的 Locator 或执行权限。用例生成改为结构化中间模型到 DSL 的确定性编译，不能生成合格证据的测试点进入可诊断阻塞或人工接管，不输出猜测用例。

**Tech Stack:** Node.js ESM、Playwright 1.61、Java 21、Spring Boot 4、Jackson 3、JdbcTemplate、Vue 3、TypeScript、Element Plus、Vitest、Node test runner、Docker Compose。

**Spec:** docs/superpowers/plans/2026-09-01-web-exploration-resilience.md、DESIGN.md、Playwright 官方 Locators/Actionability/Trace Viewer 文档。

## Global Constraints

- 当前页面继续使用 Vue 3 + TypeScript + Element Plus 和既有 Studio Canvas token，不更换组件库。
- 保持现有页面主布局、表格列、筛选栏、侧滑栏、按钮位置、颜色 token、6-8px 卡片圆角和现有断点；只增加必要的诊断文本或 tooltip。
- 素材取证的 Locator 证据必须来自 Playwright 当前浏览器页面的可见 DOM；浏览器登录过程中自然产生的认证请求不是业务 API 取证，但 Platform 不得直接调用业务 API 读取列表、详情或元素。
- 历史素材按项目、环境、页面、可信度排序，作为候选先验；历史 Locator 只有被当前浏览器 DOM 验证后才算本次证据。
- 页面未登录、未就绪、被关键网络阻断、被遮罩、处于错误路由、尚未完成滚动或分页时，不得降低历史 Locator 可信度。
- 只允许只读动作：wait_ready、dismiss_safe_overlay、menu_search、same_origin_navigation、switch_readonly_tab、scroll_container、paginate、reobserve、reauthenticate。
- 禁止自动点击或提交确认、确定、提交、保存、删除、停用、启用、开通、支付、发送、上传等业务变更动作。
- 默认探索预算保持每测试点最多 5 轮、90 秒；重新认证最多 1 次；分页最多 3 步；同一失败类别连续失败最多 2 次。
- AI 不得修改 Origin 白名单、预算、禁止动作、最低评分、Cookie、Authorization、密码、原始 Locator 或可执行 DSL。
- 诊断只保留脱敏后的 Origin、路径、资源类型、页面路径、失败类别、修复轨迹和评分；不得保存查询参数、请求体、请求头、Cookie、Token、密码、原始 DOM 或截图。
- 完成条件必须由测试、构建、fixture、健康检查和工作区状态共同证明，不能只依据 AI 或人工口头确认。

## Current Problems To Remove

- 将 AiDraftTestPointService 中“首轮 AI 正式用例 -> 浏览器取证 -> grounded AI -> 语义修复 -> 再次浏览器取证”的重复链路收敛为“一次自然语言计划 + 一次有界浏览器取证 + 一次确定性编译”，只有编译校验失败时才允许一次受限重探。
- 删除没有当前页面证据时自动补 assertUrl 的 addRecoverableAssertions 和 deriveAssertion 兜底逻辑；内容型目标不能被降级成 URL 断言。
- 统一 validateBrowserGroundedOutput 与 WebAutomationCaseValidator.validateEvidenceBoundCase，改为按 pageUrl、pageFingerprint、sourcePointId 绑定证据。
- 将以 URL 作为唯一 key 的取证目标合并改为 sourcePointId + normalizedTargetUrl，禁止不同测试点共享第一条测试点的意图和历史候选。
- 将 searchEvidence 与 searchCandidates 的重复 SQL 和候选逐条查询合并为一次候选查询。
- 将 Runner 重复的网络分类、页面 readiness、历史 Locator 验证和安全动作映射拆成单一职责模块。
- UI 侧只保留一个错误状态来源和一个诊断渲染入口，不新增独立的自动修复面板或大块说明卡片。

## Visual Invariants

- 不修改现有 Studio Canvas 的 DOM 层级、主工作区网格、表格列定义、筛选栏位置、侧滑栏宽度和按钮顺序，除非某个状态字段缺失导致功能无法使用。
- 继续使用现有 CSS token、Element Plus 组件、6-8px 卡片圆角、既有字体和断点；新增诊断只放在既有 meta/status/timeline/tooltip 区域。
- 桌面视图保持现有表格高度与底部间距；窄屏视图不得新增横向滚动，不得让诊断文本遮挡操作按钮。
- 每次前端改动都使用现有页面截图或 Playwright DOM 尺寸断言，验证 header、filter、table、drawer 和 action bar 的稳定尺寸。

## File Map

- Create server/web-runner/page-evidence.mjs：可见 DOM 快照、页面身份、历史 Locator 当前页面验证、页面/元素证据模型。
- Create server/web-runner/page-network-policy.mjs：Origin 校验、请求关键性分类、脱敏阻断记录。
- Create server/web-runner/page-repair-policy.mjs：安全修复类别、失败类别到动作映射、预算和重复失败规则。
- Modify server/web-runner/page-locator-discovery.mjs：只保留 Runner 编排、会话登录、调用上述模块并返回统一证据响应。
- Modify server/web-runner/page-explorer.mjs 与 page-exploration-policy.mjs：使用统一修复状态模型和页面级证据评分。
- Create tests page-evidence.test.mjs、page-network-policy.test.mjs、page-repair-policy.test.mjs；扩展既有 Runner fixture。
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationLocatorDiscoveryService.java、server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClient.java、server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationRunController.java：统一请求、响应和结构化失败契约。
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/locator/LocatorKnowledgeService.java：历史候选查询、UI 复核评分和退役策略。
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java、server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AgentWebCaseGenerationFacade.java、server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java：收敛流程、点级隔离、确定性编译和诊断。
- Create server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebCaseCompilationService.java；modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebAutomationCaseValidator.java：中间模型编译和统一验证。
- Modify apps/web/src/domain/webCaseAgentRepository.ts、apps/web/src/components/workflow/WebCaseAgentWorkspace.vue：只增加紧凑诊断显示；必要时调整 apps/web/src/components/workflow/NaturalLanguageCaseGenerationStage.vue、apps/web/src/components/workflow/AutomationCompileStage.vue 的状态文案，不改变布局。
- Preserve DESIGN.md 中的 Studio Canvas、token、密度、圆角、抽屉和响应式约束。

## Data Contracts

### Historical Candidate

~~~json
{
  "entryId": "locator-opaque-id",
  "projectId": 1,
  "environmentId": "env-1",
  "locator": "role=button[name=\"查询\"]",
  "label": "查询",
  "role": "button",
  "type": "button",
  "confidenceScore": 92,
  "pageUrl": "https://example.test/#/logs",
  "pageFingerprint": "sha256:..."
}
~~~

### Browser Evidence

~~~json
{
  "source": "browserDiscovery",
  "sourcePointId": "point-1",
  "projectId": 1,
  "environmentId": "env-1",
  "pageUrl": "https://example.test/#/logs",
  "pageFingerprint": "sha256:...",
  "authenticated": true,
  "readiness": { "ready": true, "failureReason": "", "durationMs": 840 },
  "elements": [
    {
      "locator": "role=button[name=\"查询\"]",
      "label": "查询",
      "role": "button",
      "visible": true,
      "enabled": true,
      "receivesEvents": true,
      "matchedElementCount": 1,
      "pageScope": "main",
      "rowContext": "设备 A · 在线",
      "historicalEntryIds": ["locator-opaque-id"]
    }
  ],
  "historicalMatchedIds": ["locator-opaque-id"],
  "historicalRejectedIds": [],
  "exploration": {
    "status": "matched",
    "failureReason": "",
    "repairTrail": []
  }
}
~~~

### Action Intent IR

自然语言计划不能直接驱动 DSL。AI 只能输出不含 Locator、URL、Origin、凭据和可执行命令的动作意图；Runner/Platform 编译器负责把意图绑定到同一 `sourcePointId + pageKey` 的浏览器证据。

~~~json
{
  "sourcePointId": "point-1",
  "stepIndex": 1,
  "intent": "assert_visible",
  "targetTerm": "告警日志",
  "scopeTerm": "日志 Tab 区域",
  "expectedState": "visible",
  "assertionKind": "business_element"
}
~~~

允许的 `intent` 仅为 `navigate_page`、`wait_ready`、`click_readonly`、`input_non_secret`、`select_readonly`、`assert_visible`、`assert_hidden`、`assert_text`、`scroll_to_target`、`paginate_readonly`。`click_readonly` 必须绑定 Runner 观测到的安全 Tab、菜单或分页控件；任何未绑定证据的意图都生成阻塞结果。

### Page Key And Fingerprint Contract

`pageKey` 固定为 `{normalizedOrigin, normalizedPath, normalizedHashRoute}`：去掉 query/fragment 中的敏感参数，保留 hash 路由的路径部分；不把 title 或可变业务数据拼入 URL。`pageFingerprint` 由稳定页面身份和可交互元素集合计算：`pageKey + title + headings + breadcrumbs + activeMenus + tabs(role/name/selected) + overlays(blocking) + elements(locator/role/tag/pageScope/rowContext)`，字段先规范化、去重、排序后再 SHA-256。

相同 Locator 只有在 `projectId + environmentId + sourcePointId + pageKey + pageFingerprint` 全部一致、元素唯一可见且 `enabled && receivesEvents && !coveredByBlockingOverlay` 时才可复用。

### Batch Budget And Point Result Contract

批次必须携带 `batchTotalDurationMs`；Runner 为会话建立、页面导航、单点取证分别维护预算，并在每次调用前扣减已消耗时间。证据收集返回 `successes[]` 和 `blocked[]`，单点失败不得通过异常中止批次。每个阻塞项至少包含 `sourcePointId、category、retryable、rounds、durationMs、repairTrail、network`。

### Manual Handoff State Contract

CAPTCHA 或重复认证失败进入 `manual_required` 状态并暂停同一 Browser Context，返回一次性 opaque `handoffId`。新增 `POST /api/web-automation/evidence/{handoffId}/resume`，只能由创建该任务的 project/environment/browser 组合恢复；恢复后重新执行两次 readiness 观察，失败则销毁 Context。handoff TTL 为 5 分钟，超时、停止任务或项目不匹配立即销毁会话，禁止跨任务复用。

### Compilation Intermediate Model

~~~json
{
  "sourcePointId": "point-1",
  "pageUrl": "https://example.test/#/logs",
  "naturalSteps": ["进入日志页面", "查看告警日志 Tab"],
  "naturalExpectedResult": "告警日志 Tab 可见",
  "actions": [
    { "action": "goto", "target": "/logs", "evidencePageUrl": "https://example.test/#/logs" },
    { "action": "assertVisible", "target": "role=tab[name=\"告警日志\"]", "evidenceId": "locator-2" }
  ]
}
~~~

编译器只接受当前证据中存在的 evidenceId、Locator、pageUrl，并将 naturalSteps 顺序映射为动作顺序。没有当前证据的测试点不能进入 GeneratedCaseArtifact，只能生成带失败类别和修复轨迹的阻塞结果。

## Task 1: Establish A Red Baseline And Inventory Redundant Paths

**Files:**
- Modify server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointReviewTest.java
- Modify server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/WebAutomationCaseValidatorEvidenceTest.java
- Modify server/web-runner/page-locator-discovery.test.mjs
- Modify server/web-runner/page-explorer.test.mjs
- Inspect server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java

- [ ] Step 1: 在 server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/WebAutomationCaseValidatorEvidenceTest.java 中构造“展示设备状态”目标和 `assertUrl /devices` 的 Web case，断言返回现有语义错误且编译结果不进入 artifact。
- [ ] Step 2: 在同一测试类中构造 pageUrl 为 `/devices` 和 `/users` 的两组相同 Locator，断言 `/devices` 用例引用 `/users` 证据时失败。
- [ ] Step 3: 在 server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointReviewTest.java 中构造两个确认点共用 `/project` 但目标文案不同的计划，使用 ArgumentCaptor 断言两次 discovery 请求的 sourcePointId、intent 和 candidates 不互相覆盖。
- [ ] Step 4: 在 server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/locator/LocatorKnowledgeServiceTest.java 中插入候选并调用 validationState=NOT_ELIGIBLE，断言 execution_failure_count、confidence_score 和 status 完全不变。
- [ ] Step 5: 运行失败测试。
~~~bash
cd server/platform && ./mvnw -q -Dtest=AiDraftTestPointReviewTest,WebAutomationCaseValidatorEvidenceTest test
cd ../../server/web-runner && node --test page-locator-discovery.test.mjs page-explorer.test.mjs
~~~
预期：新增断言因现有缺口失败。
- [ ] Step 6: 只提交红测。
~~~bash
git add server/platform/src/test server/web-runner/page-locator-discovery.test.mjs server/web-runner/page-explorer.test.mjs
git commit -m "test(web): pin down UI evidence and compilation gaps"
~~~

## Task 2: Centralize The Browser Evidence Model

**Files:**
- Create server/web-runner/page-evidence.mjs
- Modify server/web-runner/page-locator-discovery.mjs
- Modify server/web-runner/page-explorer.mjs
- Create server/web-runner/page-evidence.test.mjs

**Interfaces:**
- collectVisibleElements(page): Promise<PageElementEvidence[]>
- observePageEvidence(page): Promise<PageObservation>
- verifyHistoricalLocators(page, candidates): Promise<HistoricalVerification[]>
- pageFingerprint(observation): string
- normalizePageKey(url): PageKey

- [ ] Step 1: 把当前 DOM 采集逻辑移到 page-evidence.mjs，保留 testId、唯一 role/name、唯一精确文本、稳定 CSS 的优先级。
- [ ] Step 2: 增加 page identity：去查询参数的 URL、title、headings、breadcrumbs、activeMenus、visibleText、overlays、tabs、scrollContainers、hasNextPage、unsupported。
- [ ] Step 3: 历史 Locator 先精确解析并要求唯一可见、enabled、receivesEvents 且未被阻塞遮罩覆盖；失败后仅在不重复时按 role/name 或精确 label 等价复核，并返回 matchedElementCount、rowContext、pageScope、verificationMethod。
- [ ] Step 4: 测试精确命中、Locator 变体、重复 label、隐藏元素和遮罩。
- [ ] Step 5: 所有调用方切换后删除 page-locator-discovery.mjs 的重复采集函数。
- [ ] Step 6: 提交。
~~~bash
git add server/web-runner/page-evidence.mjs server/web-runner/page-evidence.test.mjs server/web-runner/page-locator-discovery.mjs server/web-runner/page-explorer.mjs
git commit -m "refactor(web): centralize browser evidence model"
~~~

## Task 3: Separate UI Authentication Dependencies From Evidence APIs

**Files:**
- Create server/web-runner/page-network-policy.mjs
- Modify server/web-runner/page-locator-discovery.mjs
- Modify server/web-runner/web-runner-server.mjs
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClient.java
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationRunController.java
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationEvidenceException.java
- Test server/platform/src/test/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClientTest.java

- [ ] Step 1: 提取严格 Origin 校验，只接受无 userinfo、path、query、fragment 的 HTTP/HTTPS Origin。
- [ ] Step 2: 将 UI 点击登录产生的 XHR/fetch 记录为认证依赖；其响应体永远不进入元素证据或 AI prompt。业务 API 响应只可作为 readiness 信号。
- [ ] Step 3: 识别的登录/取证失败返回 422 和 category、retryable、pageUrl、network、repairTrail；503 只保留给 Runner 传输/健康失败。
- [ ] Step 4: 检测可见 CAPTCHA 标记（`iframe[src*="captcha"]`、`[class*="captcha" i]`、`[id*="captcha" i]`、包含验证码语义且可见的控件），进入 `manual_required`，暂停同一 Browser Context；由 `handoffId` 恢复后重新执行 readiness，不允许 AI 解题或绕过。
- [ ] Step 5: 在请求和响应模型中加入 `sourcePointId、projectId、environmentId、pageFingerprint`，并用 schema 测试确认未知敏感字段不会被序列化到 Platform 或 AI prompt。
- [ ] Step 6: 覆盖缺失认证 Origin、验证码、非法 Origin、可选资源、关键认证/API 和脱敏测试；额外验证认证 Origin 仅放行配置路径，不放行整个跨域 Origin。
- [ ] Step 7: 提交。
~~~bash
git add server/web-runner/page-network-policy.mjs server/web-runner/page-network-policy.test.mjs server/web-runner/page-locator-discovery.mjs server/web-runner/web-runner-server.mjs server/platform/src/main/java server/platform/src/test/java
git commit -m "fix(web): separate UI auth dependencies from evidence APIs"
~~~

## Task 4: Unify Readiness And Bounded Repair

**Files:**
- Create server/web-runner/page-repair-policy.mjs
- Modify server/web-runner/page-explorer.mjs
- Modify server/web-runner/page-locator-discovery.mjs
- Modify server/web-runner/page-exploration-policy.mjs
- Test server/web-runner/page-repair-policy.test.mjs
- Test server/web-runner/page-explorer.test.mjs
- Test server/web-runner/page-locator-discovery.test.mjs

- [ ] Step 1: 定义一个闭合动作枚举和一个旧动作映射，未知 AI 类别在 dispatch 前拒绝。
- [ ] Step 2: readiness 需要间隔至少 100ms 的两次相同 `pageKey + shellFingerprint` 观察，并满足目标 alias/route、heading、业务元素或成功的关键 readiness 信号之一；登录表单、错误页、空壳页均判定为未就绪，响应体不作为证据。
- [ ] Step 3: 每个测试点记录 round、remainingDurationMs、pageFingerprint、lastFailureCategory、sameFailureCount、reauthenticateCount 和 repairTrail。
- [ ] Step 4: 指纹未变化且动作预算耗尽时停止重复动作；重新认证最多一次；明确登录失败后不重新认证。
- [ ] Step 5: `collectBrowserLocatorEvidence` 改为返回 `successes[]/blocked[]`，每个结果携带 sourcePointId；一个点阻塞时继续后续点，批次结束后再由 Dispatcher 汇总状态。
- [ ] Step 6: 增加批次总预算扣减测试，证明 20 个点不会各自重新获得 90 秒预算。
- [ ] Step 7: 增加错误路由、延迟 shell、遮罩、隐藏菜单、只读 Tab、滚动、分页、重复行、过期会话、CAPTCHA、可选资源和关键 API fixture；保证 mutation counter 为零，并验证覆盖层关闭动作不会触发业务 mutation。
- [ ] Step 8: 提交。
~~~bash
git add server/web-runner/page-repair-policy.mjs server/web-runner/page-repair-policy.test.mjs server/web-runner/page-explorer.mjs server/web-runner/page-locator-discovery.mjs server/web-runner/page-exploration-policy.mjs server/web-runner/*test.mjs
git commit -m "feat(web): unify readiness and bounded repair state"
~~~

## Task 5: Preserve Historical Materials And Make Confidence Evidence-Based

**Files:**
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/locator/LocatorKnowledgeService.java
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationLocatorDiscoveryService.java
- Modify corresponding tests

**Interfaces:**
- searchCandidates(projectId, environmentId, moduleName, pageName, keyword): List<LocatorKnowledgeCandidate>
- recordBrowserValidation(candidateIds, matchedIds, validationState): void
- validationState: NOT_ELIGIBLE、MATCHED、CONFIRMED_PAGE_MISS

- [ ] Step 1: 一次 SQL 返回 entryId、Locator、label、role、type、confidence、page URL 和 page fingerprint，并按环境、最近验证、可信度、采集时间排序。
- [ ] Step 2: 候选查询不再使用 AI/计划指纹退役素材；当前浏览器页面指纹才是权威。
- [ ] Step 3: 将可信度不低于 50 的前 20 个候选传入 Runner；候选必须携带 pageUrl/pageFingerprint/projectId/environmentId，历史 label 只用于排序，不能替代当前 DOM。
- [ ] Step 4: 页面未认证、未 ready、遮罩、关键/未知网络阻断或探索未完成时使用 NOT_ELIGIBLE，不改变评分。
- [ ] Step 5: 当前 DOM 唯一命中且 actionability 校验通过使用 MATCHED，可信度 +3；目标页完整探索且 readiness、网络和页面身份均合格后仍未命中使用 CONFIRMED_PAGE_MISS，可信度 -10；连续两次合格失败后 stale。评分变更必须通过事务和并发测试。
- [ ] Step 6: 增加数据库测试覆盖评分升降、非合格不变、退役和 Locator 变体。
- [ ] Step 7: 提交。
~~~bash
git add server/platform/src/main/java server/platform/src/test/java
git commit -m "fix(ai): make locator confidence depend on browser validation"
~~~

## Task 6: Simplify Natural-Language Planning And Deterministic Compilation

**Files:**
- Create server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebCaseCompilationService.java
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AgentWebCaseGenerationFacade.java
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebAutomationCaseValidator.java
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java
- Test server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/WebCaseCompilationServiceTest.java
- Test server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/WebAutomationCaseValidatorEvidenceTest.java
- Test server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointReviewTest.java

**Interfaces:**
- planNaturalCases(points, rules, memories): NaturalWebCasePlanBatch
- buildActionIntentIR(naturalPlan): ActionIntentBatch
- compileEvidenceBoundCase(plan, browserEvidence): CompilationResult
- validateCaseCoverage(cases, confirmedPointIds): ValidationResult
- validatePageScopedEvidence(case, evidenceByPage): ValidationResult

- [ ] Step 1: AI 只生成每个 confirmed sourcePointId 的自然语言计划：title、module、pageName、startUrl、naturalSteps[]、naturalExpectedResult 和语义目标词，不生成 Locator 或 DSL；随后由确定性 `buildActionIntentIR` 生成带 `stepIndex`、`intent`、`targetTerm`、`scopeTerm`、`expectedState` 的动作意图。
- [ ] Step 2: 删除 addRecoverableAssertions 和 deriveAssertion；缺断言必须失败，不追加泛化 assertUrl。
- [ ] Step 3: 编译器只按 Action Intent IR 的 stepIndex 顺序，从同一 sourcePointId、pageKey、pageFingerprint 的证据选择动作和 Locator；一个自然语言步骤产生多个 DSL 动作时必须保留同一 stepIndex 的 trace，不允许静默丢弃。
- [ ] Step 4: 内容型目标必须使用 assertVisible、assertHidden 或 assertText 断言业务元素；assertUrl 只允许路线型目标。
- [ ] Step 5: 用 pageUrl + pageFingerprint + sourcePointId 校验 Locator，禁止跨页或跨点复用。
- [ ] Step 6: 测试缺少测试点、重复测试点、缺少证据、跨页 Locator、只含 assertUrl、自然语言步骤顺序错乱、步骤缺失、预期缺少业务断言、同名/多行元素歧义和非法 DSL；断言每个自然语言步骤及预期均有 evidenceId trace。
- [ ] Step 7: 提交。
~~~bash
git add server/platform/src/main/java server/platform/src/test/java
git commit -m "feat(ai): compile web cases from page-scoped evidence"
~~~

## Task 7: Bound AI Semantic Expansion

**Files:**
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationSemanticExpansionService.java
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationIntentBuilder.java
- Modify server/web-runner/page-repair-policy.mjs
- Test server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/WebExplorationSemanticExpansionServiceTest.java
- Test server/web-runner/page-repair-policy.test.mjs

- [ ] Step 1: 先做确定性语义提取：页面别名、引号 UI 文案、元素词、状态词、动作词、路线提示和负向词。
- [ ] Step 2: AI 扩展开关默认关闭；每点最多一次调用、5 秒超时、8 个页面别名、8 个元素词、6 个状态词、4 个动作词、每个来源 2400 字符、合计 8000 字符。
- [ ] Step 3: 只接受语义数组、一个安全 repair category 和短 reason；拒绝 Origin、带查询 URL、Locator、DSL、凭据和变更动作。
- [ ] Step 4: 仅允许 wait_ready、reobserve、reauthenticate 建议；菜单、Tab、URL、滚动目标必须由 Runner 从当前 DOM 决定。
- [ ] Step 5: 测试 disabled、config_missing、timeout、malformed_output、provider_failure 和成功扩展。
- [ ] Step 6: 提交。
~~~bash
git add server/platform/src/main/java server/platform/src/test/java server/web-runner/page-repair-policy.mjs
git commit -m "fix(ai): keep semantic expansion non-authoritative"
~~~

## Task 8: Improve Diagnostics Without Changing UI Style

**Files:**
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationRunController.java
- Modify apps/web/src/domain/webCaseAgentRepository.ts
- Modify apps/web/src/components/workflow/WebCaseAgentWorkspace.vue
- Test apps/web/src/domain/webCaseAgentRepository.test.ts
- Test apps/web/src/components/workflow/WebCaseAgentWorkspace.test.ts

- [ ] Step 1: 使用稳定失败类别：auth_context_lost、login_failed、captcha_required、page_not_ready、wrong_page、network_blocked_critical、network_blocked_unknown、business_api_failed、element_not_found、ambiguous_locator、unsafe_overlay、unsupported_dom、duration_exhausted、manual_review_required。
- [ ] Step 2: 只在现有 Agent workspace header、meta row、failure label、event timeline 和 tooltip 展示诊断，不新增卡片、大说明区、布局行或新色板。
- [ ] Step 3: CAPTCHA、unsupported DOM、重复认证失败时显示紧凑人工接管提示，隐藏凭据和原始 Locator。
- [ ] Step 4: 测试关键/可选网络显示、人工接管、轨迹截断、无查询参数/凭据/Locator 泄露；使用现有桌面和 720px 断点渲染测试，确认不新增横向滚动和文本重叠。
- [ ] Step 5: 提交。
~~~bash
git add server/platform/src/main/java apps/web/src/domain apps/web/src/components/workflow
git commit -m "feat(web): show bounded browser evidence diagnostics"
~~~

## Task 9: Remove Obsolete Flow Code And Preserve Compatibility

**Files:**
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AgentWebCaseGenerationFacade.java
- Modify server/web-runner/page-locator-discovery.mjs
- Modify server/web-runner/page-exploration-policy.mjs
- Modify only status wiring in apps/web/src/components/workflow/NaturalLanguageCaseGenerationStage.vue
- Modify only status wiring in apps/web/src/components/workflow/AutomationCompileStage.vue

- [ ] Step 1: 先启用新编译器 shadow 模式，保留旧 grounded/repair 输出但不落库；对代表性 fixture 比较 sourcePointId 覆盖率、自然步骤覆盖率、业务断言覆盖率、跨页引用和失败分类。连续两轮完整套件及 ICC 只读验收均无回归后，才删除重复断言恢复、重复 evidence flatten、重复 action maps 和 URL-only fallback；保留兼容构造器和请求别名直到全量测试证明无调用。
- [ ] Step 2: 保持自然语言用例人工确认、自动化编译当前页执行、表格/筛选/抽屉/按钮尺寸不变。
- [ ] Step 3: 增加阶段流转契约测试：confirmed points -> natural plan -> manual confirmation -> browser evidence -> automation compile -> validation，禁止自动跳回前一阶段和重复生成。
- [ ] Step 4: 搜索 obsolete symbols。
~~~bash
rg -n "addRecoverableAssertions|deriveAssertion|flattenEvidenceElements|observedLocators|putIfAbsent\\(" server/platform server/web-runner apps/web
~~~
每个剩余匹配必须是有测试保护的兼容路径。
- [ ] Step 5: 提交。
~~~bash
git add server/platform/src/main/java server/web-runner apps/web/src/components/workflow
git commit -m "refactor(web): remove duplicate evidence and generation fallbacks"
~~~

## Task 10: Add Flags, Trace Diagnostics And Manual Session Handoff

**Files:**
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationSemanticExpansionService.java
- Modify server/platform/src/main/resources/application.yml
- Modify server/platform/src/main/resources/application-local.yml
- Modify server/web-runner/server.mjs
- Test server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/AiDraftExplorationBudgetTest.java
- Test server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/WebExplorationSemanticExpansionServiceTest.java
- Test server/web-runner/web-runner-server.test.mjs

- [ ] Step 1: 每点读取一次以下配置，缺失时使用安全默认：
~~~text
webMultiRoundExplorationEnabled=true
webEvidenceSessionReuseEnabled=true
webCriticalityAwareNetworkPolicyEnabled=true
webExplorationSemanticExpansionEnabled=false
webDeterministicCaseCompilationEnabled=true
~~~
- [ ] Step 2: 记录 rounds、durationMs、status、failureCategory、网络阻断计数、repairActionCount、matchedElementCount、历史候选/命中/拒绝计数和 forbiddenActionCount。
- [ ] Step 3: 仅在 local/fixture 诊断模式开启 Playwright tracing；生产不保留截图、快照或原始 DOM。
- [ ] Step 4: 通过同一个 Runner Context 支持人工完成登录/CAPTCHA；创建 `handoffId` 并暂停任务，恢复接口校验 projectId/environmentId/browser/TTL 后重新执行两次 readiness，再使用 opaque sessionId 继续只读取证；补充并发、超时、停止和跨项目复用失败测试。
- [ ] Step 5: 分别关闭每个 flag 验证不会削弱 Origin 校验、证据门禁或诊断。
- [ ] Step 6: 提交。
~~~bash
git add server/platform/src/main/java server/platform/src/main/resources server/web-runner/server.mjs server/web-runner/*test.mjs
git commit -m "feat(web): add controlled rollout and manual browser handoff"
~~~

## Task 11: Full Verification, CI Stability And Delivery

**Files:**
- Modify only tests、fixture、CI 配置或验证所需文档。
- 不添加真实 ICC 凭据、Cookie、截图、认证 URL 或 Token。

- [ ] Step 1: 运行聚焦测试。
~~~bash
cd server/web-runner
npm test -- --test-name-pattern='evidence|historical|critical|optional|auth|captcha|ICC|repair'
cd ../platform
./mvnw -q -Dtest=LocatorKnowledgeServiceTest,AiDraftTestPointReviewTest,WebAutomationCaseValidatorEvidenceTest,WebCaseAgentDispatcherTest,PlaywrightRunClientTest,WebExplorationSemanticExpansionServiceTest test
cd ../../apps/web
npm test -- --run src/domain/webCaseAgentRepository.test.ts src/components/workflow/WebCaseAgentWorkspace.test.ts src/components/workflow/NaturalLanguageCaseGenerationStage.test.ts src/components/workflow/AutomationCompileStage.test.ts
npm run build
~~~
- [ ] Step 2: 运行完整套件。
~~~bash
cd server/web-runner && npm test
cd ../platform && ./mvnw -q test
cd ../../apps/web && npm test -- --run && npm run build
~~~
记录准确的测试数、失败数、错误数、跳过数和已有 bundle 警告。
- [ ] Step 3: 执行确定性 ICC 只读验收：UI 登录、显式认证 Origin、CAPTCHA 人工接管、可选资源、目标页面 readiness、历史候选命中、Locator 变体、遮罩、只读 Tab、滚动、分页、零 mutation 和点级隔离。
- [ ] Step 4: 运行安全检查。
~~~bash
cd /path/to/Haitlify
git diff --check
rg -n "force:\\s*true|rawPayload|Cookie:|Authorization:|access_token|password=|secretKey=" server/web-runner server/platform/src/main/java apps/web/src -g '*.mjs' -g '*.java' -g '*.vue' -g '*.ts' | rg -v 'test|placeholder|redacted|type|field' || true
git status --short
~~~
- [ ] Step 5: 重建并检查服务。
~~~bash
docker compose build platform web-runner web
docker compose up -d platform web-runner web
curl --fail http://127.0.0.1:38081/actuator/health
curl --fail http://127.0.0.1:19323/health
curl --fail -I http://127.0.0.1:38080/web-cases
docker compose ps
~~~
核对镜像中包含新的 evidence/network/repair 模块和 deterministic compiler。
- [ ] Step 6: 使用 Playwright 截取现有 Web 用例工作区、自然语言生成阶段和自动化编译阶段的桌面/窄屏截图，检查表格高度、筛选栏、侧滑栏圆角、按钮宽度、文本省略和底部留白没有超出 DESIGN.md 既有约束。
- [ ] Step 7: 逐个执行 rollback flag 并恢复安全默认。
- [ ] Step 8: 请求独立代码/架构审查并只推送当前分支，不合并 main。

## Acceptance Criteria

- 每个可执行 Web 用例恰好对应一个已确认 sourcePointId。
- 每个 `naturalSteps[index]` 都有对应的 Action Intent IR 和 DSL trace；每个 `naturalExpectedResult` 都至少绑定一个业务元素断言 evidenceId。
- 每个 Locator 都来自当前浏览器 DOM，或是历史候选并在同一 DOM 中明确验证。
- 历史素材按可信度优先，但未经过当前 UI 验证不能作为事实。
- 登录、CAPTCHA、网络、错误页面、readiness、遮罩、重复元素、虚拟列表和不支持 DOM 有独立类别。
- 网络响应只用于 readiness 信号，不能成为元素证据或 AI prompt 内容。
- 内容型用例不能只使用 assertUrl 或全局导航控件。
- 缺少证据时不生成可执行用例，只生成带诊断的阻塞结果。
- 证据按 pageUrl、pageFingerprint、sourcePointId 隔离，不能跨页/跨点复用。
- 请求、响应、候选、事件和编译产物均保留 projectId/environmentId/sourcePointId；缺任一字段时阻塞，不允许退回 URL 或页面名作为唯一 key。
- 当前 UI 命中提高可信度；确认页面缺失降低可信度；基础设施失败不改变可信度。
- 一个测试点阻塞时后续测试点继续执行。
- 批次总耗时受 batchTotalDurationMs 约束，单点预算从批次剩余时间扣减，不允许每个点重新获得完整预算。
- 素材取证阶段不执行业务变更动作。
- CAPTCHA/人工接管必须通过同一 Browser Context 的 handoffId 恢复，并验证 TTL、项目环境隔离和恢复后的 readiness。
- 现有 Studio Canvas 页面布局、表格样式、筛选位置、抽屉圆角、按钮尺寸和响应式行为不变。
- Runner、Platform、前端测试/构建、ICC fixture、Docker health、hygiene 和 rollback 全部通过。

## Rollback Procedure

1. 出现确定性编译回归时先关闭 webDeterministicCaseCompilationEnabled，但保留证据校验和诊断。
2. 关闭 webExplorationSemanticExpansionEnabled 回到确定性意图提取。
3. 关闭 webEvidenceSessionReuseEnabled，要求重新认证并保留 auth_context_lost 诊断。
4. 关闭 webMultiRoundExplorationEnabled，保留单轮证据和失败类别。
5. 除非确认策略实现有缺陷，否则保持 webCriticalityAwareNetworkPolicyEnabled=true，不得通过关闭 Origin 校验解决问题。
6. 重跑登录、历史候选、readiness、网络分类、证据编译、重复 Locator、ICC fixture 和禁止动作测试。
7. 仅在 flag 回滚验证完成后回退导致问题的行为提交。

## External References

- Playwright Locators: https://playwright.dev/docs/locators
- Playwright Auto-waiting and Actionability: https://playwright.dev/docs/actionability
- Playwright Trace Viewer: https://playwright.dev/docs/trace-viewer
- WebArena realistic Web agent benchmark: https://arxiv.org/abs/2307.13854

Playwright 官方建议优先使用角色、标签和用户可感知文本定位，并在操作前检查可见、稳定、未遮挡和已启用状态。WebArena 等研究表明，真实网站中的长流程、认证、导航和动态页面仍会让通用大模型失败，因此本计划将 AI 限制在语义理解和候选排序，将执行、证据和最终 DSL 编译交给确定性代码。
