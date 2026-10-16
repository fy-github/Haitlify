> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# 完整 AI 平台改造计划第二轮复审

**评审对象：** [主计划](./2026-09-16-ai-platform-completion-plan.md)、[逐项实施清单](./2026-09-16-ai-platform-implementation-tasks.md)

**评审依据：** 当前仓库源码、修订后的计划、lemon-TestPilot、OpenAI 官方 Responses API 迁移文档、LiteLLM 的模型路由/usage 实现、OpenAI Agents SDK 的运行器实现。

**裁决：有条件通过（APPROVED WITH GATES）。**

修订后的计划已经具备实施价值，可以开始 M0，但不能把“计划完成”当成“完整 AI 已完成”。只有 T-Review-1 到 T-Review-5 和 A01a–A20 全部获得真实证据后，才允许宣称完整 AI 交付。

## 本轮确认通过的内容

### 1. 依赖关系已可执行

此前 T-Review-1 要求 Model Profile、协议 Adapter 和 Runtime，但又放在 T01/T02 之前，形成循环。当前版本已修正为：

```text
T00 基线
→ T01 连接/模型/快照最小 seam
→ T02 Chat/Responses 协议 seam
→ T-Review-1 真实 Runtime invocation
→ T-Review-2 兼容矩阵
→ T-Review-3 统一产物接受门禁
→ T-Review-4 scheduler 契约
→ T03/T04/T05/T06…
```

T-Review-1 只验证真实模型调用和业务产物；持久 lease/recovery 由 T-Review-4 验证，不再在两个任务中实现两套 scheduler。

### 2. 外部模型不可用不再阻塞整个产品

计划已将 A01 拆成：

- `A01a`：Chat/Responses 两个协议的本地 contract fixture；
- `A01b`：当前部署至少一个任务所需协议+能力档案的真实业务成功。

这符合 OpenAI 官方文档对“端点、输入、输出 item、structured output 字段分别迁移”的要求，也避免把某个内部网关的 channel 状态当作项目代码能力。未开通的 GPT-5.6-sol 或 Responses 必须显示 `BLOCKED/UNKNOWN`，不能伪装支持；如果没有任何真实档案通过 A01b，M0 必须停止，不能靠 mock 或规则结果继续扩展。

### 3. 真实 AI 与规则/工具/人工结果已有统一语义

T-Review-3 已提前定义 `MODEL_GENERATED`、`RULE_EXTRACTED`、`TOOL_OBSERVED`、`HUMAN_EDITED`、`DEGRADED`、`BLOCKED`、`ACCEPTED`、`EXECUTED`，并要求字段级 `MODEL_PROPOSED/RULE_COMPUTED/TOOL_OBSERVED/HUMAN_REQUIRED`。这正好解决当前 Figma mapper、MCP echo、多 Agent 直接 succeeded 和固定质量分数造成的假成功。

### 4. 质量评测不再只有百分比

T-Review-5 已要求 gold set、正常/边界/缺证据/恶意样例、证据引用、禁止结论、schema correctness、evidence precision、coverage recall、hallucination rate、人评和版本绑定。T13 也改为依赖该 Gate，避免先造报表再补质量依据。

## 仍必须在实施前写死的条件

这些不是新的架构方向，而是执行时不能留成口头约定的数值或合同。应在对应 Gate 的文档/测试中落地。

| 条件 | 必须写入 | 失败后果 |
|---|---|---|
| 真实垂直切片 | 固定 taskType、脱敏输入 hash、schema、成功产物字段和人工确认操作 | M0 停止，不进入 MCP/Figma/七类助手 |
| Provider Matrix | 每个单元格的 probe、profile/config 版本、requestId、usage、latency 和结论 | 档案只能 UNKNOWN/BLOCKED，不能入路由 |
| Runtime 接受门禁 | 状态机转移和字段来源强制测试 | 任何规则/空响应/echo 结果不得交付 |
| Scheduler | claim SQL、lease TTL、renew/recovery interval、并发和 shutdown 数值 | 不得宣称支持重启恢复 |
| Gold Set | 七类任务各自案例清单、标注 rubric、分歧 adjudication、阈值 | 不得宣称模型质量改善 |
| 外部通道 | 至少一个当前任务所需协议+能力的真实成功档案 | 只能交付诊断和 BLOCKED，不得继续大规模真实调用 |

## 外部实现对照后的剩余风险

### A. 计划仍然很大，必须坚持 Gate 顺序

LiteLLM 将 provider/model group、deployment health、latency、usage/cost 分开；OpenAI Agents SDK 将 session、max turns、resume context 和 usage 归入运行器。计划已经吸收这些思想，但 T01–T15 的总面仍远大于 lemon-TestPilot。任何实现者若跳过 M0 直接并行做 MCP、Figma 和七类助手，仍会得到大量接口和少量真实 AI。

### B. Responses 适配不能只换 URL

官方文档要求同时改变 endpoint、输入/输出读取和 structured output 位置；Responses 的 `output` 是 typed items，不能继续只读 `choices[0].message.content`。T02/T03 必须以 fixture 断言这一点，不能只做一个 `ResponsesAdapter` 类名。

### C. 参考项目的降级体验不能照搬为成功语义

lemon-TestPilot 的 provider 具有 timeout/maxTokens 配置，模型失败后回退本地规则；但其工作台部分结果来自本地 `buildOutput`，AI 空内容会回退为 `{}`。我们可以借鉴输入完整度、provider 参数和降级提示，却必须显示 `RULE_EXTRACTED/DEGRADED`，不能在平台历史中写成模型成功。

### D. 评测需要控制人工偏差和数据泄漏

Gold Set 不能作为生产知识直接提供给模型；标注者应盲评模型档案，评测集版本和生产知识版本分离。重复运行只能用于测稳定性，不能把重复样本当作独立质量提升证据。

### E. 安全与真实调用要同时验证

计划已列项目隔离和 SecretStore，但真实垂直切片必须从已认证 actor/project 上下文启动，不能以 `system-dev` 或测试绕过身份。真实模型请求的日志只保留 hash/ref/summary；失败响应、模型拒绝、MCP 返回都要经过脱敏扫描。

## 复审后的可执行放行路径

### Gate 0：M0 进入条件

- 工作区、数据库、Docker image revision 和远程 endpoint 记录完毕；
- T00 台账建立；
- T01/T02 的最小 seam 有单元测试；
- 至少有一个用户授权的真实 connection/profile，或者明确记录 M0 外部阻塞。

### Gate 1：真实 AI 可用

- T-Review-1 真实 invocation 成功；
- A01a contract 全部通过；
- A01b 至少一个真实档案通过；
- 输出原产地为 MODEL_GENERATED，schema/evidence/project 都有效；
- 失败重放能产生 BLOCKED/DEGRADED，而不是空成功。

### Gate 2：可运营

- T-Review-2 矩阵可重跑；
- T-Review-3 接受门禁已被至少两个不同业务入口复用；
- T-Review-4 双 worker/JVM kill/取消/幂等测试通过；
- 503 channel、空 content、refusal、incomplete、429 均能分类并显示。

### Gate 3：业务完整

- T07/T10 的需求→测试点→用例→人工确认→执行证据闭环通过；
- T09 的真实 MCP 和最小 Agent DAG 被至少一个 QA 任务实际调用；
- T12 七类 QA 任务各有真实模型或明确规则节点、schema、人工门禁；
- T-Review-5/T13 质量评测通过；
- T14/T15 权限、迁移、回滚和部署证据通过。

## 最终复审结论

**可以按修订后的计划开工，但只能从 T00→T01/T02→T-Review-1 开始。**

该计划现在足以把需求转化为真实 AI 功能，因为它规定了真实模型调用、协议适配、能力验证、产物来源、业务验收、长任务运行和质量评测的证据链；它不能保证外部 provider 永远提供某个模型 channel，这一点必须由 Provider Matrix 持续反映，而不是由代码假设。

达到完整 AI 的必要条件是：至少一个真实模型档案已在当前环境完成业务闭环，所有声称支持的协议/能力有 contract 或 live evidence，七类任务和多模态/Web/MCP 入口没有假成功，权限/预算/取消/恢复/审计/Gold Set 证据全部通过。只完成 Runtime、只切换模型名、只提高 timeout、只让容器 healthy，都不足以达到用户需求。

## 计划修订记录

- 修复 T00/T01/T02 与 T-Review-1 的依赖循环；
- 将真实 invocation 与持久 scheduler 验证分离；
- 保留两个协议 contract 要求，取消两个外部可用模型的硬性前提；
- 将 R01–R16、A01a–A20 和 M0–M4 放行语义同步；
- 将 review gate 的失败停点、外部 channel 限制、认证上下文和真实业务 evidence 写入最终路径。
