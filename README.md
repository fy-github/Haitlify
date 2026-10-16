# Haitlify AI Test Workspace

Haitlify 是面向 QA 团队的 AI 测试工作区，支持接口 / Web 用例的生成、评审、质量闭环与回归对比。当前工作区仍以既有业务与 Runner 能力为基础；Hermes-backed testing workspace 是目标架构，正在规划迁移，尚不宣称已完成 Hermes 集成。

> 内部工具优先、后期商业化。当前为单人维护的单体仓库。历史设计与验收文档保留其原始时间线，并不等同于当前实现状态。

## 当前架构状态

- **已实现**：`apps/web` 前端工作台、`server/platform` 业务后端与 `server/web-runner` Web 自动化 Runner；既有业务、API/用例与 Runner 功能继续保留。
- **规划中**：以 Hermes 为后续 AI 运行时与协作边界的后端测试工作区迁移。迁移完成前，旧的自建 Agent 只作兼容路径并标记为 deprecated，不应被描述为 Hermes。
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

## 快速启动

前置:JDK 21、Node 18+、Docker。

```bash
# 1. 启动依赖(MySQL / Redis / MinIO / web-runner)
docker compose up -d

# 2. 启动后端(首次启动会打印初始管理员账号与密码到日志)
cd server/platform && ./mvnw spring-boot:run

# 3. 启动前端
cd apps/web && npm install && npm run dev
```

本地直接启动 Web runner 时，Chromium 本地网络兼容模式（放行登录页对内网/本地认证 API 的访问）默认开启，适用于受信任的本地/内网测试环境；如需在受控环境收紧，可显式关闭：

```bash
cd server/web-runner
APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS=false npm run dev
```

该开关只作用于受信任的本地/内网测试环境；生产环境应优先让登录页与认证 API 使用同源或正确配置的公开 HTTPS 网络。

打开 <http://localhost:51730>,用后端日志中打印的初始账号登录(首次登录后必须修改密码)。

## 配置

后端环境变量(完整定义见 `server/platform/src/main/resources/application.yml`):

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `APP_PORT` | `18080` | 后端端口(本地开发;docker 容器内由 compose 显式设为 8080) |
| `APP_DB_URL` | `jdbc:mysql://127.0.0.1:3306/ai_test_platform` | 数据库连接 |
| `APP_DB_USERNAME` / `APP_DB_PASSWORD` | `root` / `root` | 数据库账号 |
| `APP_SECRET_KEY` | — | 敏感信息(如 Jira 密钥)加密存储的根密钥 |
| `APP_JIRA_*` | — | Jira 集成凭证与配置 |

前端开发代理(`apps/web/vite.config.ts`):`/api` → docker 栈后端宿主端口 `127.0.0.1:38081`(纯本地后端为 `18080`,可用 `VITE_API_PROXY_TARGET` 覆盖);`/artifacts`、`/fixtures` → web-runner 宿主端口 `127.0.0.1:19323`(`VITE_RUNNER_PROXY_TARGET` 可覆盖)。端口刻意避开宿主常用端口,防止与其他项目冲突。

## 测试与 CI

```bash
cd server/platform && ./mvnw test   # 后端单测
cd apps/web && npm test             # 前端单测
cd apps/web && npm run build        # vue-tsc 类型检查 + 构建
```

CI(`.github/workflows/ci.yml`)三个作业:Backend(Maven)、Frontend(typecheck + vitest + build)、Web runner(node:test + Chromium)。

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
- `DEPLOYMENT.md` — 部署指南(compose 一键部署、首次登录、环境变量、备份、升级)
