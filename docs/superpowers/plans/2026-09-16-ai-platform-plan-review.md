> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# 完整 AI 平台改造计划评审

**评审对象：**

- [完整 AI 平台改造计划](./2026-09-16-ai-platform-completion-plan.md)
- [完整 AI 平台逐项实施计划](./2026-09-16-ai-platform-implementation-tasks.md)

**评审日期：** 2026-09-16

**评审结论：REVISE，暂不建议按当前版本直接实施。**

**修订状态：** 本次用户要求已将评审提出的五个前置 Gate 合并回主计划和实施清单。新的实施起点是 T-Review-1–T-Review-5；在这些 Gate 全部通过前，不得扩展大范围 MCP、Figma 和七类 QA 助手。

当前计划的方向正确，已经把模型协议、能力探针、结构化输出、持久任务、证据链、MCP、权限、配额和 QA 工作流纳入范围。但它仍然有两个问题：一是首期放行标准对外部协议和模型可用性绑定过紧；二是若按任务文字执行，仍可能实现出一个“有 Runtime 框架、但业务入口和质量闭环没有真实产物”的系统。完成下面的阻塞修订后，计划才适合进入实施。

## 评审依据

本次评审同时对照了仓库源码、计划引用的 lemon-TestPilot，以及公开实现的核心模式：

- OpenAI 官方 Responses 迁移指南说明，Responses 和 Chat Completions 的输入、输出 item、结构化输出字段和流式事件不同；reasoning 模型在 Responses 中有更完整的使用方式。参考：[Migrate to the Responses API](https://developers.openai.com/api/docs/guides/migrate-to-responses)。
- LiteLLM 的公开实现把请求模型、选中模型、provider、model group、usage 和 cost 分开记录，并按 deployment 健康与延迟选择路由。参考：[spend tracking](https://github.com/BerriAI/litellm/blob/main/litellm/proxy/spend_tracking/spend_tracking_utils.py)、[lowest latency router](https://github.com/BerriAI/litellm/blob/main/litellm/router_strategy/lowest_latency.py)。
- OpenAI Agents SDK 的公开实现把 `max_turns`、session、resume context、usage snapshot 和取消作为运行器能力，而不是业务调用方自行拼装。参考：[run.py](https://github.com/openai/openai-agents-python/blob/main/src/agents/run.py)。
- lemon-TestPilot 的 [provider 配置和 AI 调用](https://github.com/summerliu90/lemon-TestPilot/blob/d185e077861f58f4fda71e9ad5763463e69407c3/server.mjs#L28-L113) 提供了每 provider 的 temperature、timeout、maxTokens；[前端分析入口](https://github.com/summerliu90/lemon-TestPilot/blob/d185e077861f58f4fda71e9ad5763463e69407c3/src/main.tsx#L116-L120) 在模型失败时回退到本地规则。

这些参考实现不能证明某个内部网关或模型通道可用。它们只用于检查架构责任是否放在正确的 Runtime 层。

## 阻塞问题

### 1. “必须两个真实协议和两个可用模型”会把外部依赖误当成产品完成条件

主计划第 4.2 节和 A01 要求 Chat Completions 与 Responses 各有一个实测可用模型，M1 才能放行。当前 `ai.example.invalid` 已经实测出现 `No available channel for model: gpt-5.6-sol`，Responses 也无法证明可用。产品能否交付不应取决于同一个内部网关是否同时开通两个协议。

必须改为三层标准：

1. Runtime 必须实现两个协议 adapter 的契约和 fixture 测试；
2. 每个部署必须至少有一个“任务所需协议+能力”通过业务级验证；
3. 某个协议/模型未开通只能将对应档案标为 `BLOCKED/UNKNOWN`，不能阻止已验证模型的业务发布，也不能显示该档案可用。

A01 应拆成 `A01a`（两个协议本地 contract）和 `A01b`（当前环境中至少一个真实档案成功）。M1 的放行条件应去掉“两个协议各一个可用模型”。

### 2. 计划没有明确“业务成功”的单一判定器

计划有 schema pass、引用有效率和人工覆盖率，但没有把一个 QA 任务从输入到最终产物定义成可机械判定的结果。现在仓库中存在 Figma 直接 mapper 成功、MCP 回显 `executed=true`、多 Agent 直接写 succeeded、质量评分固定规则等占位路径；计划提到了这些缺口，却没有给每个路径统一的 `executionKind/resultOrigin/acceptanceState` 强制约束。

必须增加全局结果契约：

```text
MODEL_GENERATED   = 至少一次 Runtime attempt 返回非空、完成、契约有效的模型结果
RULE_EXTRACTED   = 只允许来源解析/确定性算法，不得标模型成功
TOOL_OBSERVED    = 工具返回可验证结果，包含 toolCallId/evidenceId
HUMAN_EDITED     = 人工修改过的版本，保留 parentArtifactId
DEGRADED         = 有产物但跳过了声明的能力/节点，必须显示缺口
BLOCKED           = 缺证据、无模型通道、无权限或能力不支持，不能进入交付
ACCEPTED         = 人工确认通过；不等于模型成功
EXECUTED         = 用户启动后得到 Runner/接口执行证据；不等于生成成功
```

所有入口都必须拥有同一个 `ArtifactAcceptanceService` 或等价深模块。没有这个单一判定器，T07/T09/T10 仍可能分别定义“成功”。

### 3. T09 的真实 MCP 与多 Agent 范围过大，但缺少最小垂直闭环

计划同时要求 MCP transport、工具 schema、网络安全、多 Agent DAG、Skill、记忆和经验学习。若按当前顺序做，很容易先完成接口和表，再留下无法服务用户的半成品。

必须将 T09 拆成：

- T09a：一个真实只读 MCP fixture，从 `tools/list` 到 `tools/call` 得到远端 nonce 并审计；
- T09b：一个真实 MODEL 节点+一个真实 RULE 节点+一个 HUMAN gate 的最小 DAG；
- T09c：再扩展其他工具、Agent 和记忆。

T09a/T09b 必须先被 T12 的一个 `bug_analysis` 或 `log_triage` 案例调用成功，才能继续扩展工具数量。计划应明确“表和 controller 存在不算 MCP/Agent 完成”。

### 4. T06 的持久 Runtime 仍缺少 worker 并发、排队和可观察性落地细节

计划列了 lease、fence、job/node/attempt，但没有锁定以下实现选择：领取使用哪条 SQL、lease 续租频率、失败重启扫描入口、同一 project 的并发上限、全局队列上限、优先级、公平性，以及 worker 关闭时如何停止新领取。

必须补充：

- `claimNextJob` 的事务与 `SELECT ... FOR UPDATE SKIP LOCKED` 或等价 CAS 方案；
- lease TTL/renew interval/recovery interval 的具体默认值；
- 每 project/actor/task 的并发额度与等待状态；
- 应用启动后的 recovery job 与 graceful shutdown；
- queue wait、lease age、attempt duration、TTFB、blocked count 的指标和告警阈值。

否则 A08 的 JVM kill 测试无法落实，只有表结构没有恢复能力。

### 5. 计划要求“tokenizer/上下文估算”，但没有选择可验证的估算策略

计划第 4.6 节要求按模型 tokenizer 或保守估计分块。不同 provider 的 tokenizer 不一致，Java 服务不能假设所有 modelId 都可本地精确计数。若没有明确策略，T08 会继续出现上下文超限或把超限误报为模型失败。

必须指定优先级：

1. profile 提供 verified context window 和 provider token endpoint 时使用真实结果；
2. 有已验证 tokenizer adapter 时使用 adapter；
3. 否则使用字符/字节保守预算，并将 `tokenEstimateKind=CONSERVATIVE` 写入快照；
4. 到达安全余量时提前分块，禁止发送“试试看”；
5. provider 返回 context error 只允许一次降低 chunk 的重新规划，归因不能叫限流。

### 6. T13 的质量门槛有数字，但没有 Gold Set、评分 rubric 和人工一致性定义

“20 个案例”“30+ 输出”“关键需求覆盖率 90%”本身不是质量标准。没有金标准、标注人、评分字段和冲突处理，执行后只能得到一组看起来精确的数字。

必须增加：

- 每类任务至少 10 个 gold cases，其中正常/边界/缺证据/恶意输入按固定比例；
- gold 中的 source/evidence、必测 coverage、禁止结论、允许变体；
- schema correctness、evidence precision、coverage recall、hallucination rate、human acceptance、latency/cost 的评分公式；
- 两名人工标注时的分歧处理；
- 模型/提示词/数据集/规则版本绑定；
- 质量门槛按任务类型配置，不能用一个 90% 覆盖率代替全部质量。

### 7. “完整 QA 助手”尚未定义真实模型与确定性计算的责任边界

T12 已列 Bug、日志、SQL、回归、报告、Prompt 等入口，但没有明确哪些字段必须来自模型、哪些必须由代码计算、哪些只能人工填写。例如测试报告 pass rate、回归范围、SQL 性能结论和上线结论都存在高风险误生成。

必须在 task catalog 中为每字段标记：`MODEL_PROPOSED/RULE_COMPUTED/TOOL_OBSERVED/HUMAN_REQUIRED`。至少满足：

- pass rate 只能来自已执行用例；
- SQL 只做静态建议，除非有显式只读工具和查询证据；
- 回归 caseId 必须来自真实 case library；
- 上线结论永远是 HUMAN_REQUIRED；
- Bug 根因必须有 evidence/contradiction；
- Prompt 评测必须保存实际请求/响应 hash 与模型版本，规则结果与人工结果分开。

### 8. 计划缺少 Provider Contract Matrix 和“模型切换证明”

计划有 model profile，但没有一张可直接执行的兼容矩阵。仅有 `capabilities` 字段不够，必须按 provider/protocol/profile 填写：

| 能力 | Chat | Responses | 当前网关 | GPT-5.5 | GPT-5.6-sol |
|---|---|---|---|---|---|
| 非空文本 | 需验证 | 需验证 | 当前有空响应证据 | 已有历史成功 | 当前 blocked/unknown |
| JSON schema | 需验证 | 需验证 | 需业务探针 | 需实测 | 需实测 |
| reasoning | 需验证 | 需验证 | 需模型通道 | 需实测 | 需实测 |
| tool calls | 需验证 | 需验证 | 需 MCP/函数适配 | 需实测 | 需实测 |
| max output 参数 | profile 映射 | profile 映射 | 不猜字段 | 实测 | 实测 |

切换证明必须采用同一脱敏输入、同一 skill/schema、同一 endpoint+credentialRef、同一协议或显式不同协议，产生可比较的 success/blocked/latency/usage/schema/evidence 记录。只看连接成功、模型列表或 1 token ping 不算。

## 非阻塞但必须修正的问题

1. **对外部通道探测说明过度**：主计划写了“至少两个协议各一个实测可用模型”，改为“代码支持+环境至少一个真实档案”。
2. **MCP 首期不应承诺过宽**：先固定 Streamable HTTP 只读 fixture，stdio/写入行为后置。
3. **M1–M4 的人日估算偏低**：160 个 Java AI 源文件、约 160 个 AI 类别入口、23 个 migration 和前端 E2E 的全量迁移，不应按 35–60 人日作为可靠承诺。计划应改成里程碑证据预算，每个里程碑完成后重新估算。
4. **“新增 SDK 先验证”还不够**：需要明确依赖决策记录、许可证扫描、离线构建和安全更新责任。
5. **文档写出了官方 Responses 对比，但没有把公司网关的 Responses compatibility 当成未知**：UI 必须显示 provider-specific capability，不能凭 OpenAI 官方能力继承内部兼容网关能力。
6. **质量评分和成本估算仍有占位风险**：计划虽指出当前固定分数，但应把固定评分代码列入 stop-ship 搜索清单，禁止新 Runtime 调用它作为模型质量结果。

## 必须增加的修订任务

### T-Review-1：Runtime 最小真实垂直切片

在 T01/T02 前增加一个 3–5 天的切片：一个连接、一个模型 profile、一个脱敏需求、一个 schema、一个 Runtime job、一个真实 attempt、一个测试点 artifact、一个人工确认。它必须通过同一环境的真实 endpoint，并能准确显示 `MODEL_GENERATED/BLOCKED/DEGRADED`。失败时停在这里，不先扩展 MCP、Figma 和七类助手。

### T-Review-2：Provider Contract Matrix 与真实模型验收

将上述矩阵落入 `docs/ai-test-platform/provider-compatibility-matrix.md`，每个单元格有 probe 命令/fixture、时间、profile version、requestId 和结论。GPT-5.6-sol 当前没有成功 evidence 时，必须标为 blocked/unknown；不能因计划设计完而变成 supported。

### T-Review-3：结果原产地和 acceptance gate

将 `ArtifactAcceptanceService` 与字段级 `MODEL_PROPOSED/RULE_COMPUTED/TOOL_OBSERVED/HUMAN_REQUIRED` 提到 T03 之前，所有业务任务先使用它再迁移。否则 T07–T12 会重复实现“成功”语义。

### T-Review-4：Job scheduler operational contract

在 T06 前写定 SQL claim、lease、renew、recovery、queue concurrency、shutdown、metrics 的数值和 integration tests。没有这些，不能声称支持长任务恢复。

### T-Review-5：Gold Set 与质量 rubric

在 T13 前增加数据集格式、标注规则、人工一致性、评分公式和报表 schema；先用 10 个 gold cases 验证评分稳定，再扩展数量。

## 外部实现对照后的最终判断

### 通过的部分

- 统一 Runtime、显式协议、能力探针、模型档案方向正确。
- 计划已经明确不能把模型不可用伪装成成功，且覆盖了取消、恢复、权限、审计和证据链。
- 对 MCP、Web 取证和人工评审设置了比 lemon-TestPilot 更严格的真实执行边界。
- 对 Responses 和 Chat 的输出差异有正确认识；官方文档明确说明应更换端点、输入/输出结构和 structured output 字段。

### 不通过的部分

- 当前版本不能保证“按计划执行后就一定满足完整 AI”，因为 T01–T15 仍有过大的并行面，缺少先证实的真实垂直切片。
- 计划把两个可用协议作为阶段放行条件，受外部 provider 通道控制，容易让项目被错误阻塞。
- 质量验收的数字目前没有 gold set/rubric，不能证明 AI 结果真的有质量。
- MCP 和多 Agent 任务仍可能先做成表/接口/fixture，缺少业务任务强制串联门禁。
- 当前计划只承诺“能力可验证”，没有定义“支持模型切换的最低兼容矩阵”作为发布物。

## 评审裁决

**REVISE，不能原样执行。**

修订顺序必须是：

1. 先加入 T-Review-1，证明一个真实 Runtime 垂直闭环；
2. 再加入 T-Review-2，建立 provider/model/protocol 兼容矩阵；
3. 将 T-Review-3 提前到 T03 前，统一真实产物判定；
4. 将 T-Review-4 写成 T06 的硬性实施契约；
5. 将 T-Review-5 写入 T13 的数据集与评分门槛；
6. 把 A01 放行条件改为“两个协议 contract + 当前环境至少一个真实档案”，不是“两个协议各一个外部可用模型”；
7. 只有上述五项通过，才继续 T08–T15 的大范围扩展。

按这个顺序修订后，计划才具备“真实 AI 功能”的证据门槛：模型真的调用过、输出真的通过契约、来源真的可追溯、工具真的返回过结果、任务真的可恢复、错误真的能解释、规则降级真的不会伪装成模型成功。

## 修订后的放行判断

评审建议已落实为以下计划变化：

- 新增 T-Review-1：最小真实 Runtime 垂直切片；
- 新增 T-Review-2：Provider Contract Matrix 与模型切换证明；
- 新增 T-Review-3：统一产物原产地和 Acceptance Gate；
- 新增 T-Review-4：Job Scheduler operational contract；
- 新增 T-Review-5：Gold Set 与质量评分 rubric；
- 将 R01–R12 扩展为 R01–R16；
- 将 A01 拆为 A01a（协议 contract）和 A01b（当前环境真实档案）；
- 将 M0 改为真实切片与基线门禁；
- 删除“两个协议各一个外部可用模型”作为发布前提，改为“两个 contract + 当前部署至少一个真实业务档案”。

修订后的计划才允许进入 T00 及后续实施；T-Review-1–T-Review-4 是 M0 前置门禁，T-Review-5 是质量评测放行门禁。
