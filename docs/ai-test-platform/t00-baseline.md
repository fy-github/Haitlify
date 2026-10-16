> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# T00 基线台账

- 环境：haitlify-API，分支 `ui/beauty-redesign-home`，执行分支当前 HEAD 由 `git rev-parse HEAD` 确认。
- 后端：Spring Boot 4.1 / Java 21 / Jackson 3 / JDBC / Flyway。
- 数据库：Docker MySQL 8.4，Flyway 已应用到 V23；platform 容器 health `UP`。
- 真实模型档案：T-Review-1 已于 2026-09-18 通过，证据详见 `provider-compatibility-matrix.md`。
- 已验证真实档案：legacy-default / legacy-default-profile v1 / gpt-5.6-sol，当前 live business protocol 为 Chat-compatible endpoint；Responses 仍只有 contract fixture。
- 已验证闭环：脱敏需求 → 真实 attempt → schema/evidence PASS → `MODEL_GENERATED` artifact → synthetic_user 人工确认 → `ACCEPTED` 回读。
- 放行规则：未通过的协议/能力仍标记 `UNKNOWN/BLOCKED`；禁止以 mock、规则结果、ping 或容器健康状态代替真实业务 Gate。
- 范围限制：T-Review-1 通过只放行后续底座 Review Gate，不代表完整 AI、全入口迁移、Responses live、MCP、七类 QA 助手或长任务恢复已完成。
