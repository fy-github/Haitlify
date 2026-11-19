> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# AI 质量评分 Rubric（T-Review-5）

适用：`server/platform` 的 Gold Set 评测（`AiGoldSetService` / `AiEvaluationService`）。
原则：**所有指标由确定性代码从执行事实计算**——报告中不得出现固定 0.8/0.9 之类的写死分数；规则评分、模型输出事实、人工评审三者分列存储，不得混写。

## 一、Gold Set 包（`gold-set.jsonl`）—— 已随 A3 退役删除（2026-10-21）

- 位置：`server/platform/src/test/resources/ai/evaluation/gold-set.jsonl`（发布套件同格式）。
- 首行可为包头部：`{"kind":"package","version":"...","rubric":"docs/ai-test-platform/ai-quality-rubric.md"}`。
- 每行一个案例，字段：`caseId`、`taskType`（必须是任务目录 `AiTaskCatalog` 中的类型）、`category`、`tier`、`source`（脱敏来源说明）、`inputs`、`outputSchema`、`evidenceIds`、`requiredItems`（必测 coverage）、`forbiddenClaims`（禁止结论）、`allowedVariants`（允许变体）。
- 固定比例（下限，**负例只增不减**）：
  - 每类任务 ≥10 例；每类中 `normal ≥ 4`、`boundary ≥ 2`、`no_evidence ≥ 2`、`malicious ≥ 2`；
  - `no_evidence`/`malicious` 案例必须携带禁止结论清单；非无证据案例必须携带证据集。
- 增加/修改案例必须走评审：**不得修改既有断言、不得减少负例、不得把规则结果混入模型分数**（校验器强制下限）。

## 二、指标公式（对某 taskType 的 N 次运行，N=案例数×重复次数，发布套件重复 3 次）

| 指标 | 公式 | 说明 |
|---|---|---|
| schemaCorrectness | schema 通过运行数 / N | 对照案例 `outputSchema`，经 T03 校验引擎（与运行时同一语义） |
| evidencePrecision | 无无效引用运行数 / N | 引用必须来自本案例证据集；跨项目/编造引用判无效；另报原始无效引用计数 `invalidCitationTokens` 供定位 |
| coverageRecall | 必达项覆盖率的均值 | 覆盖率=命中 requiredItems 数 / requiredItems 总数 |
| hallucinationRate | 命中禁止结论的运行数 / N | 输出文本出现 `forbiddenClaims` 任一条目即命中 |
| executionAvailability | attempt 成功运行数 / N | 失败/超时/无通道不计入 schema 分母外的任何“通过” |
| stability | 同一 caseId 的各次运行在 schema/citations/coverage/forbidden 四项上逐位一致 | 发布套件重复 3 次验证；不稳定案例单列 |

版本写入（持久化于 `ai_quality_evaluation_runs.versions_json`）：gold-set 包版本、rubric 引用、profile、skill、规则版本（`AiFailureClassifier.RULE_VERSION`）。模型/提示词版本经 profile/skill 与其快照版本追溯。

## 三、阈值与门槛

- 按 taskType 配置：`app.ai.evaluation.thresholds`，格式 `task=schema:0.9,evidence:0.9,coverage:0.8,hallucination:0.05;default=...`；未配置的任务回落到 `default`。
- 默认：`schema ≥ 0.9`、`evidence ≥ 0.9`、`coverage ≥ 0.8`、`hallucination ≤ 0.05`、stability 必须成立。**不得用一个覆盖率代替全部质量**——四项逐一判定。
- 低于门槛：输出 `disable-version-recommended`（依据逐条列出）并持久化 `gate_json`；**绝不自动禁用**，禁用 profile/skill 版本由独立操作执行（`applied` 恒为 false，除非后续版本引入显式运维命令）。

## 四、人工评审（人评与规则分数分开存储）

- 标注者只看**盲化载荷**（`annotationPayload`）：caseId、category、判定事实、输出摘要（sha-256）——**不含**档案名/模型名/skill 名。
- 两位标注者的结论不一致 ⇒ `disagreement=true`，必须由第三方 `adjudicate` 给出最终裁定；裁决前该案例不得计入通过。
- 人评存储于 `ai_quality_annotations` / `ai_quality_adjudications`（V49）；规则分数在 `ai_quality_evaluation_runs/cases`。禁止互写。

## 五、运行与稳定性

- 发布套件：每案例重复 3 次（stability 判定）；调整 skill/model/profile 后须与旧基线对比（`AiEvaluationService.compare` 只给指标 delta）。
- 失败样例可定位：caseId → outcome（schema 错误含 JSON Pointer / invalidCitations / 命中禁语）→ `outputSha256`；不落盘原始输出。
- 成本：评测运行经统一 facade 发出真实模型请求并计入预算——按需在独立预算窗口运行。
