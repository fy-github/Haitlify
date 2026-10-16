> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Web 用例可靠性缺口修复方案

> For agentic workers: REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

Goal: 修复最终审核发现的网络放行、批次预算、步骤级证据追溯和运行态验证缺口，使 Web 用例流程满足历史素材优先、真实 UI 证据、确定性编译和可诊断恢复。

Architecture: Runner 是唯一浏览器和网络执行边界；Platform 负责批次预算、测试点隔离、证据汇总和 artifact 追溯；AI 只提供自然语言理解和受限语义扩展。所有可执行用例必须由当前页面 pageKey、pageFingerprint、sourcePointId 证据编译，失败测试点返回结构化阻塞结果并继续后续点。

Tech Stack: Node.js ESM、Playwright 1.61、Java 21、Spring Boot 4、Jackson 3、JdbcTemplate、Vue 3、TypeScript、Vitest、Docker Compose。

---

## Evidence From Current Review

- page-network-policy.mjs 的 isAllowedRequestUrl 在命中 pageOrigins 后放行整个 Origin，同源 XHR/fetch 没有资源类型级限制。
- WebCaseAgentDispatcher 逐点调用取证，只传单点 remainingDurationMs，没有 batchTotalDurationMs 或批次剩余账本。
- WebCaseCompilationService 有 ActionIntentIR，但生成 artifact 只有 case 级 evidenceIds，没有 step-level trace。
- 全仓库没有发现 context.tracing.start 或 context.tracing.stop 的受控 tracing 实现。
- 最终 HEAD 的重点测试通过，但最终完整 Platform/Web 套件、Docker health、真实浏览器登录后的工作区和 720px 视觉验收仍需重新执行。

## Non-Negotiable Constraints

- 不得通过放宽 Origin 白名单解决 network_blocked。
- 认证 XHR 只能作为认证依赖/readiness 信号，响应体、请求体、请求头、Cookie、Token 不得进入证据、prompt、artifact 或前端事件。
- 同源 XHR/fetch 也必须分类；未命中精确业务路径时 fail closed。
- 历史 Locator 只能作为候选；当前 DOM 必须唯一、可见、enabled、receivesEvents 且没有阻塞遮罩。
- 内容型用例必须有业务元素断言，不得用 assertUrl 兜底。
- 测试点失败不能中断后续点，也不能让后续点重新获得已消耗的批次预算。
- UI 只在既有 header/meta/timeline/tooltip 展示诊断，不改变 Studio Canvas 主布局、表格、抽屉、按钮尺寸和颜色 token。
- 不提交真实账号、密码、Cookie、Authorization、Token、截图或带敏感 query 的 URL。

## Data Contracts

### Batch Budget

字段：batchTotalDurationMs、batchStartedAt、batchRemainingDurationMs、pointBudget、sourcePointId。

Dispatcher 在批次开始时建立总预算；每次进入取证、重探或编译前重新计算剩余时间。pointBudget 等于 min(90000, batchRemainingDurationMs)。为 0 时该点进入 duration_exhausted，后续点仍按剩余批次预算继续判定。

### Step Trace

每个生成 artifact 必须保存 sourcePointId、pageKey、pageFingerprint、steps 和 expectedResultEvidenceIds。steps 中每项包含 stepIndex、naturalStep、intent、dsl、evidenceIds、pageFingerprint。一个自然语言步骤可对应多条 DSL，但所有 DSL 必须保留同一个 stepIndex。纯路由用例可以使用 assertUrl；内容型用例的 expectedResultEvidenceIds 不得为空。

## Task 1: Close Same-Origin XHR Network Bypass

Files:
- Modify server/web-runner/page-network-policy.mjs
- Modify server/web-runner/page-locator-discovery.mjs
- Modify server/web-runner/page-network-policy.test.mjs
- Modify server/web-runner/page-locator-discovery.test.mjs

- [x] 写红测：页面 Origin 的 document/script/style/image/font 资源继续允许；同源未配置的 /api/project/list 阻断；显式业务 URL 精确允许；认证 Origin 仅精确 allowedRequestUrls 允许。
- [x] 实现资源类型级白名单：XHR/fetch 不得因为 pageOrigins 命中而直接放行；document、script、静态资源走页面资源规则；认证路径保留 query 精确匹配和末尾斜杠规范化。
- [x] 验证 blocked entry 只含脱敏 origin/path/resourceType/criticality/failureType，不含 body、headers、Cookie、Token。
- [ ] 运行 Runner network/auth/origin 测试、git diff --check，并提交 fix(web): fail closed for unlisted same-origin requests。

## Task 2: Add Batch Budget And Point-Level Results

Files:
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentModels.java
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AgentWebCaseGenerationFacade.java
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java
- Modify server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcherTest.java
- Add server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentBatchBudgetTest.java

- [x] 写红测：两个点的第二点拿到扣减后的 remainingDurationMs；第一个点阻塞后第二点继续；预算耗尽时后续点得到 duration_exhausted；结果包含 successes 和 blocked。
- [x] 实现 BatchBudgetLedger，以单调时钟计算 elapsed/remaining；每次 resolveEvidence 和重探前传入剩余预算；批次结束按 successes/blocked 决定 succeed、blocked 或 partial。
- [ ] 保持旧请求构造器兼容；取消、停止、重试时清理 ledger，不保存凭据。
- [ ] 运行 focused Java tests、compile、git diff --check，并提交 feat(web): enforce bounded batch budgets and point results。

## Task 3: Persist Step-Level Evidence Trace

Files:
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebCaseCompilationService.java
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AgentWebCaseGenerationFacade.java
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentRunService.java
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentModels.java
- Modify apps/web/src/domain/webCaseAgentRepository.ts
- Modify apps/web/src/App.vue
- Add/modify Java and TypeScript trace tests

- [ ] 写红测：每个自然步骤都有 stepIndex、intent、DSL 行和 evidenceIds；内容型 expected result 至少一个业务 evidenceId；缺 trace、跨点 trace、跨 fingerprint trace 阻塞且不保存 artifact。
- [x] 实现 StepTrace 和 expectedResultEvidenceIds；编译器选择 Locator 时记录 evidenceId、pageFingerprint、sourcePointId。
- [x] saveArtifacts 将 trace 写入 candidate_json；Facade、repository、App metadata 透传；前端只在现有详情/时间线区域显示紧凑信息。
- [ ] 运行 focused Java/TS tests、Web build、git diff --check，并提交 feat(ai): persist step-level browser evidence trace。

## Task 4: Add Controlled Playwright Tracing

Files:
- Modify server/web-runner/page-locator-discovery.mjs
- Modify server/web-runner/web-runner-server.mjs
- Modify server/web-runner/session-manager.mjs
- Modify server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClient.java
- Add Runner tracing tests

- [x] 仅当 diagnosticMode 为 local 或 fixture 且 tracingEnabled=true 时开启 tracing；production、未知 mode 或关闭 flag 时不创建 trace。
- [x] 每个 context 生命周期只启动一次；success、blocked、timeout、captcha、resume、stop、exception 都 stop/flush。
- [x] trace 只保留脱敏 metadata；不保存 credentials、headers、cookies、原始 DOM 或截图；traceId opaque，TTL 不超过 session TTL。
- [ ] 运行 tracing/session/captcha/resume tests、Java focused、git diff --check，并提交 feat(web): add bounded local browser tracing。

## Task 5: Final Verification And Runtime Acceptance

Files:
- Modify only tests, fixtures, CI or verification docs。
- 不添加真实凭据、Cookie、Token、认证 URL 或截图。

- [ ] 在最终 HEAD 运行 Runner npm test、Platform ./mvnw clean test、Web npm test -- --run 和 npm run build，记录 tests/failures/errors/skipped/warnings。
- [ ] 运行 git diff --check 和敏感字段扫描；检查 artifact、event、prompt、handoff 没有敏感字段。
- [ ] Docker daemon 可用时启动 Compose，检查 platform、web-runner、web health，访问 http://127.0.0.1:38080/web-cases 验证刷新路径。
- [ ] 使用本地 fixture 验证 UI 登录、CAPTCHA handoff/resume、同源业务 API 阻断、历史 Locator、遮罩、Tab、滚动、分页和零 mutation。
- [ ] 使用 Browser/Playwright 验证桌面和 720px：document 无横向滚动，诊断文案省略，按钮不变形，文本不重叠；截图只写临时目录。
- [ ] 逐个关闭五个 rollout flag，确认不会绕过 Origin、证据门禁或诊断，最后恢复安全默认。
- [ ] 请求独立 code review 和 architect review；只推送当前分支，不合并 main。

## Acceptance Criteria

- 同源未列入白名单的 XHR/fetch 不会被 Origin 级规则放行。
- 每个批次有 batchTotalDurationMs，每个点使用扣减后的预算。
- 单点失败不阻断后续点，批次结果包含 successes/blocked。
- 每个自然步骤可追溯到 Action Intent、DSL、evidenceId 和 pageFingerprint。
- 内容型用例不能只生成 assertUrl。
- 历史 Locator 优先作为候选，但必须经过当前 UI DOM actionability 验证。
- handoff/resume 只在相同项目、环境、浏览器、页面身份和允许的人工接管状态下继续。
- tracing 只在 local/fixture 开启，生产不保存原始浏览器数据。
- Runner、Platform、Web 全量测试/构建、Docker health、fixture、720px 视觉和 rollback 均有最终 HEAD 证据。

## Rollback

1. 确定性编译异常时关闭 webDeterministicCaseCompilationEnabled，保留证据门禁和失败诊断。
2. 批次预算异常时关闭 batch budget enforcement，保留单点上限和点级阻塞隔离。
3. tracing 只影响 local/fixture，可关闭 tracingEnabled，不影响生产证据流程。
4. 网络策略误阻断时只回滚资源分类规则，不恢复任意 Origin 放行，优先补显式路径。
5. handoff 失败时关闭 resume UI，但保留 session TTL、页面身份校验和人工接管阻塞状态。
