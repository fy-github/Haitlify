> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# 阶段 6：局部 Repair 和 Locator 知识库版本化 验收记录

> 验收基线：`docs/reviews/2026-09-09-ai-assisted-web-automation-stabilization-plan.md` 阶段 6（702–768 行）：失败时局部修复而非重生成整条用例；repair 触发白名单；repair 流程（采集证据 → 页族一致 → 等价目标 → 候选 → 试运行 → proposal → 人工确认 → 写入知识库）；locator 版本化 revision；5 条验收测试。

## Status

实现已存在于既有代码（V10/V19/V20/V36 迁移 + `LocatorKnowledgeService`/`LocatorKnowledgeController` + runner `page-repair-policy.mjs`）；第十九轮独立复核 FAIL 后完成两项修复（`e500bef`，见「六」），**第二十轮复审判定 PASS**。

## 一、实现位置

- 平台：`ai/agentic/locator/` — `LocatorKnowledgeService`（触发白名单、候选浏览器复核、提案/复审/写入 revision、审计）、`LocatorKnowledgeController`（HTTP 入口）、`LocatorKnowledgeMaintenanceJob`、`LocatorKnowledgePersistenceFailure`。
- 迁移：`V10`（`ai_locator_knowledge_entries` + `history`）、`V19`（`history.revision`、`ai_locator_repair_proposals`、`ai_locator_repair_audit`）、`V20`（`ai_locator_repair_verifications`：服务端签发、一次性、可过期）、`V11`（confidence/hit/成功失败计数）。
- runner：`page-repair-policy.mjs`（闭集 repair action 枚举 + 安全类别 + 有界预算）、`page-explorer.mjs`（repairTrail、`unsafe_repair_category` 拒绝）、`page-locator-discovery.mjs`（AI repair 建议归一化 + 重试预算）。

## 二、逐条验收（5 条）

| # | 验收 | 证据 | 状态 |
| --- | --- | --- | --- |
| 1 | locator 失效但页面族一致时能生成 repair proposal | `LocatorRepairWorkflowTest.verifiesCandidateAgainstRealBrowserEvidenceBeforeProposal`（对候选做真实浏览器 discover 复核，`verified=true`、`pageFingerprintAfter=sha256:…`）+ `persistsPendingProposalAndOnlyAcceptUpdatesKnowledgeWithRevisionAndAudit`（生成 `pending_review` 提案，`revision=2`） | ✅ |
| 2 | 页面族不一致时拒绝 repair | `candidateVerificationRejectsWrongPageAndNonActionableEvidence`（`wrong_page`/`locator_not_actionable`，`correct_page=false`）；`proposalRejectsEnvironmentMismatch`（环境不一致 ⇒ 拒绝）；页族变化另有指纹不一致退役日志（ServiceTest） | ✅ |
| 3 | 业务断言失败不会自动改断言 | `rejectsRepairProposalForFailureCategoryOutsideStableAllowlist`（`business_assertion_failed` 被拒）；白名单恰为计划的 5 类：`locator_not_found / ambiguous_locator / element_not_visible / page_not_ready / assertion_target_not_found`（`LocatorKnowledgeService:50`），其余（含 auth/permission/mutating/data）全部拒绝 | ✅ |
| 4 | repair 成功不会静默覆盖原用例 | 提案落 `ai_locator_repair_proposals` 且 `status=pending_review` 期间 `ai_locator_knowledge_entries.locator` 保持旧值（同测试 109–110 行断言）；接受时才更新并写 `revision`+history+audit | ✅ |
| 5 | 人工确认后才写入长期知识库 | `acceptRepairProposal` 由服务端一次性 `verificationId` 约束（伪造布尔被拒、过期/已用被拒）；接受后写 `reviewed_by/reviewed_name`、history `repair_accept*`、audit `accept`；单次赢家（重复接受/先拒后受均拒）；触发方为已认证用户（`ProjectAccessService` 授权 + SecurityContext） | ✅ |

## 三、本轮差异

**无代码改动**：既有实现（及其 32 条相关测试）已覆盖计划要求；本阶段产出为上述逐条验收映射与边界说明。runner 侧 repair policy 与本轮无改动。

## 四、验证方式与实测结果

- 聚焦复跑（2026-10-08，HEAD = `fb0db3b`，代码同 `38af44e`）：`LocatorKnowledgeControllerTest` **2/2**、`LocatorKnowledgeServiceTest` **21/21**、`LocatorRepairWorkflowTest` **9/9**（先清理历史 surefire 报告）；日志含指纹变化退役、累计失败退役等真实行为输出。
- 平台全量：沿用同日阶段 5 门禁运行的 **555/0/0/5 + BUILD SUCCESS + 102 报告**（代码未变动）。
- runner / 前端：本阶段未改相关代码，沿用阶段 5 门禁结果（264/264、139 files/694）。

## 五、边界与已知限制（如实列出）

- **无人机界面**：前端目前没有 repair 提案/知识库的评审 UI（检索为空），人工确认走已认证的 HTTP API；产品化界面属阶段 7 范畴。
- **无自动触发接线**：执行失败不会由系统内部自动发起 repair——提案/复核均由外部（操作者/工具）经 API 发起；「失败类型白名单」约束的是**允许发起**的类别，而非自动触发。
- 复核失败分类 `wrong_page / locator_not_actionable` 属**复核结论**词汇，与触发白名单（5 类）是两套词汇，勿混。
- 候选复核依赖真实浏览器 discover（`WebAutomationLocatorDiscoveryService`），离线/无环境时无法完成复核——失败即不得生成提案（fail-closed）。

## 六、独立复核（第十九轮 FAIL 与修复）

- 第十九轮（`deleg_052dc756`，1004s，api_calls=69）判定 **FAIL**，两项缺陷：
  1. **（中高）失败复核仍可创建并接受提案**：`verifyRepairCandidate` 在发现结果 `matchedElementCount ≠ 1`（多元素命中）时已判 `locator_not_actionable`，但四个布尔列仍按单元素状态写成 true、且**无唯一性/计数维度** ⇒ `createRepairProposal` 与 `acceptRepairProposal` 均放行，知识库条目被失败候选改写（复现 A2.21 / A2.21b / A2.22）。
  2. **（中）accept 侧复检 300s TTL**：验证窗口只为创建设定，晚到（>5 分钟）的人工确认被无谓阻断且无刷新入口（A4.17）。
- 修复（本提交）：`V36` 迁移为 `ai_locator_repair_verifications` 增加 `matched_element_count`（存量行默认 0，fail-closed）；验证时写入真实计数；**创建与接受两侧都复检计数==1**；accept 不再复检过期时间（过期只约束创建，注释说明理由）；新增 3 条锁测：歧义计数不可创建、歧义计数不可接受且条目不改写、晚到人工确认成功。
- 修复后实测：聚焦 `LocatorRepairWorkflowTest`（9→**12/12**）、`LocatorKnowledgeServiceTest` 21/21、`LocatorKnowledgeControllerTest` 2/2；platform 全量 **Tests run: 558, Failures: 0, Errors: 0, Skipped: 5** + BUILD SUCCESS + 102 报告。
- 第十九轮 low/备注（如实记录，未随修）：白名单大小写敏感（fail-closed，可接受）；`update()` 的 history revision 走 DB 默认 1（未复用 max+1 语义，受 `confidenceLocked`/成员守卫限制）；AI 3 类 repair 闭集在 Java/JS 各一份（当前一致，存在漂移风险）；无事务环境下的部分更新竞态（生产 class 级 `@Transactional` 覆盖）。
- **第二十轮复审（`deleg_00e25eeb`，840s，api_calls=73）判定 PASS**，复核对象 `e500bef5e33f7c7b32b9577033262bbeebd84299`（判定书：`<synthetic-hermes-cache>/scratch/adv20/verifier/round20-verdict.md`）：
  - 自建夹具重跑第十九轮**原复现**：歧义计数（单元素 `matchedElementCount=2`）下建提案被拒（`IAE: …invalid, expired, or mismatched`）、计数失效后接受被拒（`ISE: …no longer valid`），且条目保持旧值、verification 未被消费、无 audit/history；
  - 计数边界 0/2/-1/NULL 在创建与接受两侧全部 fail-closed，唯一放行路径只剩直改 DB（需 DB 权限，API 无该字段）；存量行经 V36 实际 ALTER 后默认 0，影响面=部署瞬间 ≤300s 在途未用 verification（方向安全，如实记录）；
  - 晚到确认成功（创建后拨过期 ⇒ accept 通过），创建时过期仍拒、`used_at` 语义两侧正确；
  - **三条新锁测在父提交 `026eeea` 上恰按预期失败**（两条「父代码放行」+一条父源码 584 行旧 TTL 复检栈帧）= 有牙证明；
  - 回归子集全过（白名单恰 5 类、伪造/跨租户全拒、8 路创建与 4 路 accept 各 1 赢家、页族判定）；
  - 套件内 Flyway 实际应用 **V36**（`…now at version v36`）；platform 全量 **558/0/0/5 + BUILD SUCCESS + 102 报告**；runner **264/264**；前端 694（Node 26 开关对照，`git diff 026eeea..e500bef -- apps/web` = 0 行）。
  - 新增低观察（未随修）：测试夹具 `matched_element_count` default 1 与迁移 default 0 方向相反（仅夹具便利、无生产影响，后续小修可对齐）；其余同第十九轮 low 列表。
