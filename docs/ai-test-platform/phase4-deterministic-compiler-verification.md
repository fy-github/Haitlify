> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# 阶段 4：确定性 DSL Compiler 验收记录

> 验收基线：`docs/reviews/2026-09-09-ai-assisted-web-automation-stabilization-plan.md` 阶段 4（533–607 行）。
> 与阶段 3 的接续关系：阶段 3 建立「每个动作有 before/action/after 证据」，阶段 4 让编译器只消费 Action IR + Step Evidence，不再对自然语言步骤做任何猜测。

## Status

第一轮实现完成，**已通过第十七轮独立对抗性复核（PASS，复核 commit `9296de16`；A/B 段逐条成立，无新增 high/medium）**。

## 一、实现位置

- `WebCaseCompilationService.buildActionIntentIR`：只接受结构化 `actions`。缺失或非数组 ⇒ 产出 `invalid_action_ir`（消息：缺少结构化 actions），`naturalSteps` 等自然语言字段仅用于展示与追溯。
- `WebCaseCompilationService.buildStructuredActionIntents` / `structuredType` / `compileIntent`：新增 `assertEnabled`/`assertDisabled` 意图、DSL 行与断言分类；`isSupportedAction` 词表同步。
- `ActionIrValidator`：`SUPPORTED_TYPES`、`LOCATOR_TYPES`、assert 包装形态的 assertion.type 词表、`validateSafety` 只读集合、`isAssertionType` 同步扩展。
- `WebAutomationCaseValidator`：DSL 动词表、断言动作表、locator 白名单、「至少一个断言」判定与报错文案同步扩展。
- `AiDraftTestPointService`：模型指令中的动作词表、DSL 动作说明、`WEB_DSL_COMMAND_PREFIXES`、元素断言识别与断言提取同步扩展。
- `server/web-runner/runner-core.mjs`：DSL 别名（`assert_enabled`/`assert_disabled`）、`SUPPORTED_STEP_ACTIONS`、`executeStep` 执行器。
- `server/web-runner/page-locator-discovery.mjs`：IR 类型表、断言类型/别名表、`ACTION_IR_TYPE_ALIASES`、真实浏览器执行分支。

## 二、逐条验收（8 条）

| # | 验收 | 证据 | 状态 |
| --- | --- | --- | --- |
| 1 | 编译结果使用用户选择的 browser 和 viewport | `withCompileContext`（discovery 覆盖 plan，blank 时保留 plan）+ `planCarriesRuntimeCompileContextWithoutReplacingStructuredActions`、`compileContextDoesNotErasePersistedPlanContextWhenRequestFieldsAreBlank`、`compilationCarriesExplicitExecutionContextIntoGeneratedCase`（env/baseUrl/browser/viewport/authMode 全量落地） | ✅ |
| 2 | 同一 label 多元素时编译失败，不取第一个 | 编译期：`structuredCompilationReportsMissingTargetEvidenceWithoutLabelOrLocatorFallback`；运行期：`matches.length > 1 ⇒ ambiguous_locator`（带 `matchedElementCount`） | ✅ |
| 3 | 没有 step evidence 的 action 编译失败 | `structuredCompilationReportsMissingActionEvidenceByStableStepError` + `validateStructuredStepEvidence` 唯一绑定 | ✅ |
| 4 | 跨页面 locator 编译失败 | `structuredCompilationReportsActionEvidenceMismatchForWrongPageFingerprint`（每个动作只认其 before 状态所在页面的证据；跨页流程允许，但逐动作绑定） | ✅ |
| 5 | `fill` 缺少 value 来源编译失败 | `structuredActionValidationRequiresValueSourceForFillAndSelect` + `ActionIrValidator.validateValue` | ✅ |
| 6 | `mutating` 动作默认编译失败 | safety=mutating/dangerous 的 click ⇒ `unsafe_action` ⇒ 空编译 ⇒ `action_evidence_mismatch…未能编译成 DSL`（fail-closed）；**本轮新增**：无 `actions` 的旧自然语言路径同样 fail-closed（`invalid_action_ir`） | ✅ |
| 7 | `scrollIntoView` 能执行真实滚动 | runner-core 执行器单测（真实调用 `scrollIntoViewIfNeeded`）；discovery 真实浏览器场景含 IR 动作执行。第一期的 `assertEnabled`/`assertDisabled` 同批实现：runner-core 正反两面单测 + discovery 真实浏览器场景 + 平台编译/校验形状测试 | ✅ |
| 8 | `assertText` 的 expected 来自结构化字段 | `structuredActionsUseEvidenceIdsAndKeepAssertTextExpectedSeparateFromTarget`（expected 与 target 分离；空 expected fail-closed 在 runner 侧） | ✅ |

## 三、本轮行为变化（不向后兼容项，如实列出）

1. **无 `actions`（缺失或非数组）的计划不再被编译**：返回 `invalid_action_ir`（此前会按自然语言步骤分类意图并猜测目标）。生产提示词本就声明「actions 是自动化编译的唯一主输入」，本次将编译器行为与提示词契约对齐。旧的自然语言编排辅助（`classifyIntent`/`selectTargetTerm`/`selectScopeTerm`/`selectExpectedState`/`valueSource`/`assertionKind` 及 `intentBuilder` 字段）已删除。
2. **新增 DSL 动词 `assertEnabled <locator>` / `assertDisabled <locator>`**：目标必须唯一且可见（与 assertVisible 同口径，隐藏元素的 disabled 状态不构成证据）；状态与期望不符 ⇒ `assertion_failed`。
3. 受影响的 9 个旧测试已迁移为结构化 actions（保持各自原断言对象：跨页、页指纹、路由断言、相对/hash startUrl、无 id 证据等）。

## 四、验证方式与实测结果（全量）

- **web-runner**：`npm test` **tests 262 / pass 262 / fail 0 / cancelled 0 / skipped 0**（新增 runner-core 执行器正反用例；discovery 真实浏览器场景新增 `assertEnabled` 动作且通过）。载荷重录两遍 md5 一致；`runnerImplementationHash=7dafbafccc750eaace396d96553d0f20d1d093bd5de15cb52ed46f3d1e7fdd0c`。
- **platform**：`./mvnw -o test` **Tests run: 555, Failures: 0, Errors: 0, Skipped: 5**，BUILD SUCCESS，102 份 surefire 报告（运行前清理历史报告与 `target/test-classes` 的临时探针类）。
- **前端**：`npx vue-tsc --noEmit` exit 0；`npx vitest run` **139 files / 694 tests** 全过（exit 0）。复核方在 Node v26.7.0 下曾复现 9 files / 16 tests 失败，根因实测为环境性：Node 26 的实验性 `localStorage` 全局遮蔽 vitest 4.1.11 的 jsdom 注入（与本提交无关——本轮 0 个 `apps/` 文件改动）；加 `NODE_OPTIONS=--localstorage-file=…` 后 `139/694` 全过、exit 0。本机复跑（Node v22）无需该开关。

## 五、边界与已知限制（如实列出）

- 沿用阶段 3 的非阻塞残余：纯扫码墙（无输入）fail-open、同拍变化+回退落到原始超时措辞、整页导航到「无关但带列表」页面两侧一致判成功、页面被关闭时分类为空。
- `assertEnabled`/`assertDisabled` 的「先可见再判 enabled」为刻意选择：隐藏元素的可用状态不构成可复核证据（fail-closed）；如产品需要覆盖「隐藏但 disabled」的语义，需要新增独立动词而不是放宽本动词。
- Compile Context 中的 `allowedActionSafety`（允许动作安全等级集合）尚未作为显式上下文字段接线。当前语义为「mutating/dangerous 一律不编译」，比计划中的最小实现更严格（fail-closed）；若产品需要「按环境放行 mutating」，需要新增该上下文字段并同步 DTO/前端，待定。

## 六、独立复核（第十七轮对抗性复核，判定 PASS）

- 复核对象：`9296de16dc4f06f4f4739ca12e3d7e5a13a56652`；复核方 `deleg_c59a690e`（1 leaf，1385s，api_calls=147），自建 Java 探针 + 真实 Chromium 探针，未引用文档或对方说法；原始输出在 `<synthetic-hermes-cache>/scratch/adv17/verifier/`。
- **A 段全成立**：IR-only 可达性（全仓库唯一生产链 `compileWebCases ← facade.compile ← dispatcher`，无 actions 一律 fail-closed；被删 NL 辅助引用数=0；`!structuredPlan` 分支读码+动态双证不可达）；新动词四链十种形态全接受、正反两面交叉断言均 `assertion_failed`（failureType+category 双设）、不可见目标 fail-closed；9 个迁移测试断言对象保留（3 处轻微放宽均有替代覆盖：goto 断言随行为变化、case 级 evidenceIds→空但 trace 级仍在、错误匹配器变宽）；mutating 三种形态 fail-closed 且无静默成功路径；label 歧义运行期 `ambiguous_locator (matched 2)`。
- **B 段逐字**：runner `262/262`（freshness 未 stale）；platform `555/0/0/5` + BUILD SUCCESS + 102 报告；前端见「四」；载荷哈希独立重算一致、连录两遍逐字节稳定；`git status` 无新增。
- **C 段：无新增 high/medium**。5 条 low 观察（如实备忘，均非阻塞）：① NL 删除后仍留有不可达的 `!structuredPlan` 分支与遗留辅助（死代码清理待办）；② actions 空数组与缺失的结果一致（均 fail-closed）但失败分类不同；③ runner 直调 fill 无 value 会静默填空串（生产管线被编译闸门拦截）；④ enabled 的「先可见再判」口径与本文档一致；⑤ 本文档原「见下节补记」无对应小节（本提交补齐）。
- 纪律说明：本 PASS 针对 `9296de16` 的代码；其后仅有文档修改（本记录），无代码改动。
