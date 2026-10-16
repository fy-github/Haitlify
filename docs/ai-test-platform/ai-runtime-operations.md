> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Haitlify AI Runtime 运维手册

> T15 交付物。所有「验证过什么」以运行中进程的 `/api/system/health`（gitSha/buildTime）与本文档记录的
> 环境/数据库/镜像为准；不得用截图、ping、mock 或「容器 healthy」代替业务完成。

> **Transition status:** This is an archival operational record for the existing platform runtime. The former self-built Agent is deprecated pending successful Hermes integration; Hermes-backed testing workspace migration is planned and is not implemented by this document.

## 1. 运行时身份与验证口径

| 项目 | 取值来源 |
|---|---|
| 镜像 SHA | 构建时 `--build-arg GIT_SHA=$(git rev-parse HEAD)`；经 OCI label `org.opencontainers.image.revision` 与进程内 `APP_GIT_SHA` 双落点 |
| 构建时间 | `--build-arg BUILD_TIME=$(date -u +%FT%TZ)`；同上（`APP_BUILD_TIME`） |
| 版本接口 | `GET /api/system/health` → `{status, service, version, gitSha, buildTime}` |
| 数据库 | MySQL 8.4（`ai_test_platform` 业务库）；迁移由 Flyway 管理，V1–V47 byte-exact，只允许新增版本 |

**发布前检查**：从目标环境直接读取 `/api/system/health`，把 gitSha 与拟发布提交比对——不比较 latest tag 的创建时间。

## 2. 模型与协议兼容矩阵（只列已验证项）

| 能力 | 通道 | 说明 |
|---|---|---|
| 文本结构化生成 | OpenAI 兼容 Chat Completions | 经统一 Runtime（`AiTaskExecutionFacade`），能力探针 + 协议编译器（CHAT_JSON_SCHEMA / JSON_MODE / PROMPT_JSON） |
| 视觉理解 | OpenAI 兼容 vision | 同一台账/预算/审计语义；图片大小与类型受配置约束 |
| 工具调用 | MCP（远程）+ 内建规则工具 | readOnly 由服务端元数据与管理员策略裁决；写操作需幂等键 + 人工门 |
| 未验证通道 | — | 明确标 `BLOCKED/UNKNOWN`，不使用 mock 补齐 |

## 3. 预算、监控与保留期

- **预算护栏**：`app.ai.budget.daily-token-limit` / `daily-call-limit`（默认 20M tokens / 20k calls/日，可按部署调整）。
  每次模型调用按 global→project→task 三 scope 预留（保守上界=prompt 字符数+4096），结算时未知 usage/未知价格按预留全额计入。
  预算不足 ⇒ 请求在发出前阻断，attempt 记 `BUDGET_EXCEEDED`。
- **监控**：`/api/ai/jobs/{jobId}` 审计（节点/attempt/事件）、`/api/ai/analysis-runs/{runId}/events?afterSeq=` 断线续接、
  `/api/ai/scheduler-metrics` 调度指标；运行时间线（前端）区分排队/调用/片段/工具/重试/人审，心跳不等于 token 进度。
- **保留期**：事件流与 attempt 记录为审计真相，默认长期保留；知识候选未发布（DRAFT）可清理；`ai_secret_backups` 仅在迁移窗口保留。

## 4. 秘密与权限

- 秘密只以引用（`enc:v1:`）存储；无根密钥（`APP_SECRET_KEY`）时**拒绝新增明文**并保持历史数据原样。
- 历史明文迁移：`AiSecretReferenceService.migrateLegacyPlaintext()`——加密备份进 `ai_secret_backups`、替换引用、逐行验证解密；
  报告只有计数。迁移过程不写任何秘密进日志。
- 资源读取对「不存在」与「无权」统一 `404 not_found`；后台执行按 run 的 actor 复核成员关系，撤权下一节点生效。
- 出站载荷（API/事件/快照/导出/模型消息）一律先过 `AiPayloadSanitizer`；唯一合法例外是传输层 Authorization。

## 5. 升级与回滚

```bash
# 构建（注入身份）
docker build -f server/platform/Dockerfile \
  --build-arg GIT_SHA="$(git rev-parse HEAD)" \
  --build-arg BUILD_TIME="$(date -u +%FT%TZ)" \
  -t haitlify-platform:"$(git rev-parse --short HEAD)" server/platform

# 升级
docker compose up -d platform
curl -s http://localhost:38081/api/system/health   # 核对 gitSha == 目标提交

# 回滚（旧镜像 + 旧 schema 兼容性检查）
docker compose stop platform
docker compose run --rm platform java -jar app.jar --dry-run-migrations || true
docker tag <previous-image> haitlify-platform:current && docker compose up -d platform
```

**迁移纪律**：Flyway 只前进不回退；回滚演练 = 用旧镜像对**迁移前备份**的独立库启动（V44–V47 均为纯新增表，旧镜像不读它们）。

## 6. 冒烟

```bash
SMOKE_USERNAME=<你本人账号> SMOKE_PASSWORD=*** SMOKE_PROJECT_ID=1 node scripts/ai-runtime-smoke.mjs http://localhost:38081
```

覆盖：健康与 SHA → 正式登录（凭据只来自环境变量）→ 创建合成 bug_analysis 任务 → 产物必须 `PENDING_HUMAN_CONFIRMATION`
（模型成功≠接受；真实失败如实 `BLOCKED` 并在报告中显示）→ 事件流 → 人工驳回 → 猜测 runId 统一 404。
退出码：0=pass，1=fail，2=缺凭据，3=诚实阻塞（按阻塞报告，不算通过）。

## 6.1 发布开关（灰度）

| 配置 | 语义 |
|---|---|
| `app.ai.runtime.disabled-task-types` | 逗号分隔的任务类型黑名单：命中者在发出任何模型请求前阻断，`retry_reason=runtime_disabled`，attempt 记 `RUNTIME_DISABLED` |
| `app.ai.runtime.disabled-projects` | 逗号分隔的项目 id 黑名单，同上 |

**纪律**：被禁范围只阻断、不旁路——不直连 gateway、不并行发送双份真实付费请求；旧任务随所属范围禁用而排队/阻塞，
恢复时天然重试。空配置（默认）= 全部启用。

## 6.2 迁移与备份恢复演练

```bash
bash scripts/ai-migration-drill.sh          # 备份→恢复→数量/关联校验→keyRef→清理
```

- 对真实 MySQL 执行 mysqldump 与恢复；比对 `ai_analysis_runs`/`ai_draft_test_points`/`ai_assistant_artifacts`/
  `ai_knowledge_documents`/`ai_run_events`/`ai_run_policies` 的数量与关键外键（draft point → run 无孤儿）；
- `credential_ref` 逐条核验：`enc:v1:` 密文引用可直接材料化；别名引用在无通道材料化时按 `BLOCKED` 如实报告；
- 演练库默认清理（`DROP_DRILL_DB=0` 保留）。生产演练用同流程对**脱敏备份**执行，并在验收台账记录计数快照。

**回滚**：Flyway 只前进；回滚 = 旧镜像 + 迁移前备份的独立库（V39–V47 均为新增表/列，旧镜像不读它们）。
演练证据（SHA、计数、时间）记入 T15 台账。

**演练发现（真实案例）**：`server/platform/.dockerignore` 早期版本用 `**/*synthetic_secret*` 排除凭证，误伤
`V25__mcp_secret_reference.sql`——镜像缺迁移文件，启动即 `FlywayValidateException`（Detected applied
migration not resolved locally: 25）。结论：构建上下文只允许显式路径/后缀的拒绝规则；发布前用
`docker run --rm <image> sh -c 'unzip -l /app/app.jar | grep -c db/migration'` 核对镜像内迁移数量。

## 7. 已知限制与失败处理

| 场景 | 行为 |
|---|---|
| 无可用模型通道 / 凭据不可材料化 | 运行记 `BLOCKED` + 真实原因；绝不显示「AI 完成」 |
| 结构化输出不合 schema | repair 至多一次；仍失败 ⇒ 失败并保留证据 |
| MCP 远端缺工具 / 超时 / 非 2xx | fail-closed，结果限长，密钥形态脱敏 |
| 预算不足 | 发出前阻断，`BUDGET_EXCEEDED` |
| Web 执行 | 只能由人工在受控站点启动 Runner；执行失败不得被评审分数覆盖 |
| 未验证协议/模型 | UI/API 标 `BLOCKED/UNKNOWN`，禁止用规则结果冒充模型成功 |
