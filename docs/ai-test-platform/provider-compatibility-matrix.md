> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Provider Compatibility Matrix

本表只接受可复现的 contract 或 live business evidence。未执行单元格保持 `UNKNOWN`，失败记录为 `BLOCKED`，不得根据模型名称推断支持。

## 当前 Gate 状态

T-Review-1：**PASSED**（2026-09-18，真实业务闭环）。

- Live run：`live-vertical-20260918`
- Latest repeat probe：2026-09-18，latency `7016 ms`，provider requestId：`resp_0d5796ad35eb0a42016aad214d14f087d0a30142e1e0598d3e`
- Model switch comparison：same input hash，`gpt-5.6-sol` request `resp_0d5796ad35eb0a42016aad214d14f087d0a30142e1e0598d3e` / `7016 ms`；`gpt-5.5` request `resp_0d5824a88c21edee016aad2154dc8087d18027b0241adec4c7` / `6632 ms`；两者均 `HTTP 200 / stop / usageKnown=true / schema=PASS / evidence=PASS`，`livePasses=2`。
- Project / actor：`2 / synthetic_user`
- Input：固定脱敏需求，原始 prompt hash：`7eef855b0fbbcdfae58b5ae27f66553c92b23a558ebba823c6f72ae9ea19e9ae`
- Input evidence：`ev-live-1`
- Connection：`legacy-default` v1，credentialRef 未暴露
- Profile：`legacy-default-profile` v1
- Configured model：`gpt-5.6-sol`
- Configured protocol：`CHAT_COMPLETIONS`
- Endpoint：`https://ai.example.invalid/v1`
- HTTP / finish：`200 / stop`（provider response object 为 `chat.completion`，request id 前缀为 `resp_`）
- Provider requestId：`resp_0869527238dbf30d016aad1347823c87d0a9e5f4cf914f8450`
- Usage：known，input `59`，output `223`；reasoning usage `41`（provider 原始 usage 观察）
- Schema：`PASS`；返回 2 条测试点，字段和 evidence scope 有效
- Artifact：`MODEL_GENERATED`，artifact `artifact-3808cf7e-8e74-4a37-bfc6-9895936e92b1`
- Human review：`synthetic_user` 确认，最终状态 `ACCEPTED`
- 数据库回读：`ai_runtime_vertical_runs`、`ai_runtime_vertical_attempts`、`ai_runtime_vertical_artifacts` 已验证

| Protocol | Profile | Capability | Contract evidence | Live business evidence | Conclusion |
|---|---|---|---|---|---|
| Chat Completions | legacy-default-profile v1 / gpt-5.6-sol | text | `AiProtocolTransportTest`：请求序列化、可选参数省略、错误协议路径 | T-Review-1 live run；configured CHAT seam 收到真实业务响应 | `LIVE_BUSINESS_PASS` |
| Responses | fixture profile | text/json_schema | `AiProtocolTransportTest`：typed output、reasoning 忽略、usage、HTTP fixture | 当前部署未使用该 profile 做付费业务调用 | `CONTRACT_PASS / LIVE_UNKNOWN` |

T-Review-1 真实闭环证据：脱敏输入 → 一次真实 provider attempt → 非空完成响应 → schema/evidence 校验 → `MODEL_GENERATED` artifact → 人工确认 → 数据库回读。规则结果、mock、ping、容器 healthy 未作为通过依据。

## 失败与限制

- Responses 当前只有本地 contract fixture，不能声称当前部署的 Responses live business support。
- `gpt-5.6-sol` 的本次成功只证明该 endpoint、credentialRef、配置版本、模型档案和该输入在本次观察中成功；不证明永久可用。
- 当前旧 `ai_system_configs` 已映射为最小 legacy connection/profile，后续 T-Review-2 需要把 profile/config 版本化管理和矩阵记录工具化。
- T-Review-2：**PASSED**（两个真实 profile 使用同一脱敏输入完成 live business comparison；两者均通过 schema/evidence/usage/HTTP/finish 检查）。
- 业务 artifact 已完成人工确认，但尚未接入正式需求分析入口的全量消费者迁移；不得据此宣称完整 AI 已交付。