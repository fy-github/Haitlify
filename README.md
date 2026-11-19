# Haitlify AI Test Workspace

Haitlify 是面向 QA 团队的 AI 测试工作区，支持接口 / Web 用例的生成、评审、质量闭环与回归对比。当前工作区仍以既有业务与 Runner 能力为基础；Hermes-backed testing workspace 是目标架构，正在规划迁移，尚不宣称已完成 Hermes 集成。

> 公开开发、社区协作，采用 Apache-2.0 许可证。当前为单人维护的单体仓库。历史设计与验收文档保留其原始时间线，并不等同于当前实现状态。

## 开源协作与许可

- **许可证**：[Apache License 2.0](LICENSE)。允许按许可证条款商用、修改与分发，包含明确的贡献者专利授权；不提供担保，也不授予商标使用权。
- **贡献指南**：[CONTRIBUTING.md](CONTRIBUTING.md)，包含开发验证、PR 要求、隐私规则及第三方材料的授权边界。
- **问题与建议**：[GitHub Issues](https://github.com/fy-github/Haitlify/issues)。请使用合成数据，公开日志和附件必须脱敏。
- **安全漏洞**：[SECURITY.md](SECURITY.md)；请通过 [私密漏洞报告](https://github.com/fy-github/Haitlify/security/advisories/new) 提交，不在公开 Issue 中披露利用细节。

除另有声明的第三方材料外，项目自有代码按 Apache-2.0 发布；第三方依赖、转载资料及原有归属声明继续遵循各自条款，不因本仓库公开而自动重新授权。当前仍在开发中，公开源码不等于已完成生产验收。

## 当前架构状态

- **已实现**：`apps/web` 前端工作台、`server/platform` 业务后端与 `server/web-runner` Web 自动化 Runner；既有业务、API/用例与 Runner 功能继续保留。
- **规划中**：按 Hermes 主助手架构重建，可删除旧自研 AI 并重构 UI，不保留 Legacy 回退。遗留源码尚在拆除准备阶段，不应被描述为 Hermes。
- **文档边界**：`docs/` 中的历史方案、计划和验收记录是 archival design/evidence；它们保留原始事实，不构成“当前 Hermes 已实现”的证明。

## 架构总览

```
┌─────────────────────┐      ┌──────────────────────┐      ┌──────────────────┐
│  apps/web (Vue 3)    │ /api │  server/platform      │ /run │ server/web-runner │
│  Vite + Element Plus ├─────►│  Java 21 + Spring Boot├─────►│ node:test +       │
│  前端工作台            │      │  MySQL + Redis + MinIO│      │ Chromium 执行器    │
└─────────────────────┘      └──────────────────────┘      └──────────────────┘
```

- **apps/web** — Vue 3 + TypeScript + Vite + Element Plus + lucide-vue-next。AI 工作台、质量评审、用例库、报表、Jira 配置等。
- **server/platform** — Java 21 + Spring Boot 后端。既有 AI agent(需求分析 / 用例生成 / 评审 / 编译，deprecated，待 Hermes 集成成功后迁移)、质量闭环(评分 / 回归 / 洞察)、Jira 集成、认证、密钥加密。
- **server/web-runner** — 独立执行器,`node:test` + Chromium 执行 Web 自动化,产出截图 / DOM 结构指纹 / 页面证据。

## 开发与部署状态

旧 Compose 启动配置已从新仓移除；替代者是 `deploy/isolated/` 下的隔离栈（**已交付、尚未在目标主机验收**，只能经 `scripts/preflight/start-isolated-stack.sh` 启动，不能直接 `docker compose up`）。原容器、数据库地址和 Runner 端口仅代表遗留实现，不应作为新架构的启动目标。

新环境必须先通过独立资源预检，详见 [部署状态](DEPLOYMENT.md) 和 [重建计划](docs/superpowers/plans/2026-10-16-hermes-primary-assistant-and-upgrade-sync.md)。不要按历史文档恢复旧服务，也不要直接用遗留默认数据库配置启动后端。当前允许进行源码检查和隔离测试，不表示真实业务环境已就绪。

## 遗留源码配置（不是新部署参数）

后端环境变量(完整定义见 `server/platform/src/main/resources/application.yml`):

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `APP_PORT` | `38082` | 后端端口(本地开发默认取隔离栈宿主端口;docker 容器内由 compose 显式设为 8080) |
| `APP_DB_URL` | `jdbc:mysql://127.0.0.1:13306/haitlify_isolated` | 数据库连接(默认指向隔离栈宿主端口;改动会触发启动保护拒绝) |
| `APP_DB_USERNAME` / `APP_DB_PASSWORD` | `haitlify_app` / 空 | 数据库账号(无默认口令;由 `deploy/isolated/.env` 提供) |
| `APP_SECRET_KEY` | — | 敏感信息(如 Jira 密钥)加密存储的根密钥 |
| `APP_JIRA_*` | — | Jira 集成凭证与配置 |

前端开发代理(`apps/web/vite.config.ts`):`/api` → 隔离栈后端宿主端口 `127.0.0.1:38082`(可用 `VITE_API_PROXY_TARGET` 覆盖);`/artifacts`、`/fixtures` → web-runner 宿主端口 `127.0.0.1:19324`(`VITE_RUNNER_PROXY_TARGET` 可覆盖)。端口刻意避开宿主常用端口,防止与其他项目冲突。

## 测试与 CI

```bash
cd server/platform && ./mvnw test   # 后端单测
cd apps/web && npm test             # 前端单测
cd apps/web && npm run build        # vue-tsc 类型检查 + 构建
```

CI(`.github/workflows/ci.yml`)六个作业:Backend(Maven)、Backend MySQL contract、Live gates(手动触发)、Frontend(typecheck + vitest + build)、Web runner(node:test + Chromium)、Isolated environment guard(隔离环境预检 + 配置面扫描)。

> 已知:web-runner CI 存在 flaky(随机端口健康检查超时,与代码无关,重跑即过,见 issue #12)。

## 目录结构

```
apps/web          前端工作台
server/platform   后端(domain / agent / quality / security …)
server/web-runner Web 自动化执行器
docs              参考资料(部分为外部文章存档)
DESIGN.md         架构与设计
CLAUDE.md         项目规则
```

## 相关文档

- `DESIGN.md` — 架构与设计说明
- `CLAUDE.md` — 项目规则(证据优先的推荐、思维主体不大改、一次 Task 一次 commit 等)
- `DEPLOYMENT.md` — 旧 Compose 退役、新环境预检要求及当前部署状态
