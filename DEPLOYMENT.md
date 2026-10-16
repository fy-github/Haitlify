# Haitlify 部署指南(Deployment)

本文档覆盖 `docker compose` 一键部署、首次登录、环境变量、数据备份与升级。本地开发启动方式见 `README.md`。

> **Compose identity compatibility (explicit migration procedure only):** Existing deployments currently use the fixed Compose project `cloudv4-api`, with database `ai_test_platform` and named volumes `mysql-data`, `redis-data`, and `minio-data`. A local directory rename does not migrate these identities. After moving the checkout, run `docker compose -p cloudv4-api -f /path/to/docker-compose.yml ...` or set `COMPOSE_PROJECT_NAME=cloudv4-api`; do not use `down -v` during the move. This literal identity is retained only for live migration compatibility, not as the new product/repository brand.

## 1. 一键部署(docker compose)

前置:Docker Engine 24+ / Docker Desktop,Docker Compose v2。

```bash
# 首次或升级后构建镜像并启动
docker compose up -d --build

# 查看各容器状态(mysql / redis / platform 应显示 healthy)
docker compose ps

# 查看平台日志(含初始管理员密码)
docker compose logs -f platform
```

启动 6 个服务,一键拉起:

| 服务 | 镜像 | 健康检查 | 说明 |
| --- | --- | --- | --- |
| `mysql` | mysql:8.4 | `mysqladmin ping` | 唯一被后端引用的数据库 |
| `redis` | redis:7.4 | `redis-cli ping` | 预留(后端当前未引用) |
| `minio` | minio/minio | — | 预留对象存储(后端当前未引用) |
| `web-runner` | 自构建 | — | Web 自动化执行器(node:test + Chromium) |
| `platform` | 自构建(Java 21) | TCP 8080 | 后端,`depends_on` mysql healthy |
| `web` | 自构建(nginx) | — | 前端入口,`depends_on` platform healthy |

## 2. 首次登录

平台首次启动时自动创建管理员账号,随机密码打印在平台容器日志中:

```bash
docker compose logs platform | grep -E "用户名|初始密码"
#  用户名: admin
#  初始密码: xxxxxxxxxxxx
```

浏览器打开 <http://localhost:38080> ,用该账号登录,**首次登录后必须修改密码**。

> 生产建议:部署前设置 `APP_SECRET_KEY` 并预置管理员?当前版本仅支持启动随机密码 + 首次强制改密,不提供环境变量注入初始密码。

## 3. 服务与端口

| 端口 | 服务 | 说明 |
| --- | --- | --- |
| `38080` | web(nginx) | **对外唯一入口**。SPA + 反代 `/api` → platform、`/artifacts`、`/fixtures` → web-runner |
| `38081` | platform | 后端直连,仅供调试,生产建议不对外暴露 |
| `19323` | web-runner | 执行器(nginx 已反代,可仅内网) |
| `3306` / `6379` / `9000`/`9001` | mysql / redis / minio | 数据与中间件,建议不对外暴露 |

> 端口刻意避开宿主常用端口(`80`/`8080`/`9323`/`5173`),防止与其他项目冲突。容器内端口仍为 `8080`(platform)/`9323`(web-runner),仅宿主映射调整。

前端通过相对路径访问后端与执行素材:`/api/**` → platform,`/artifacts`、`/fixtures` → web-runner(浏览器同源,无跨域)。

## 4. 环境变量

### 4.1 compose 层(`docker-compose.yml`)

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `APP_SECRET_KEY` | 空 | 敏感信息加密根密钥。生产**必须**设置 32 字节的 Base64(`openssl rand -base64 32`),留空则密钥明文落库并打 WARN |
| `APP_WEB_AUTOMATION_RUNNER_ENABLED` | `"true"` | 是否启用 Web 自动化执行 |
| `APP_WEB_AUTOMATION_RUNNER_BASE_URL` | `http://web-runner:9323` | 平台侧访问执行器地址(容器网络内) |

### 4.2 平台(`server/platform/src/main/resources/application.yml`)

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `APP_PORT` | `18080` | 后端端口(本地开发;compose 内显式设为 `8080` 容器内绑定) |
| `APP_DB_URL` | `jdbc:mysql://127.0.0.1:3306/ai_test_platform` | JDBC 连接串(compose 内置 `mysql:3306`、`utf8mb4`、`Asia/Shanghai`) |
| `APP_DB_USERNAME` / `APP_DB_PASSWORD` | `root` / `root` | 数据库账号 |
| `APP_VERSION` | `0.1.0-SNAPSHOT` | 健康检查与版本上报 |
| `APP_SECURITY_ENABLED` | `true` | 认证 + CSRF。**生产保持 true**,false 仅限本地测试 |
| `APP_JIRA_ENABLED` | `false` | Jira 集成开关 |
| `APP_JIRA_BASE_URL` / `APP_JIRA_USERNAME` / `APP_JIRA_TOKEN` | — | Jira 服务账号凭证(存库前经 `APP_SECRET_KEY` 加密) |
| `APP_JIRA_DEFAULT_AUTH_MODE` | `service-account` | Jira 认证方式 |
| `APP_JIRA_PROJECT_JQL_HAITLIFY_API` | `project = HAITLIFY ...` | Existing compatibility key; retain for deployed environments until an explicit configuration migration |
| `APP_FIGMA_ENABLED` / `APP_FIGMA_TOKEN` / `APP_FIGMA_BASE_URL` | 关 | Figma 原型导入凭证 |
| `APP_WEB_AUTOMATION_RUNNER_TIMEOUT_MS` | `120000` | 平台调用执行器超时 |

### 4.3 web-runner(`server/web-runner/server.mjs`)

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `APP_WEB_AUTOMATION_RUNNER_PORT` | `9323` | 监听端口 |
| `APP_WEB_AUTOMATION_RUNNER_HOST` | `127.0.0.1` | **compose 中必须设为 `0.0.0.0`**,否则容器内其它服务访问不到 |
| `APP_WEB_AUTOMATION_RUNNER_BROWSER_CHANNEL` | `chromium` | 浏览器通道 |
| `APP_WEB_AUTOMATION_RUNNER_HEADLESS` | `true` | 无头模式 |
| `APP_WEB_AUTOMATION_RUNNER_STRICT_STARTUP` | `false` | 启动时浏览器探测失败是否退出 |
| `APP_WEB_AUTOMATION_RUNNER_PUBLIC_URL` | `http://127.0.0.1:19323` | 返回给前端的素材/截图绝对 URL 前缀(compose 已设为宿主可直达的新宿主端口)。**远程部署需改为对外域名**(如 `https://your-host`),使产物 URL 经 nginx 同源返回 |
| `APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOSTS` / `..._ALLOW_PRIVATE_NETWORKS` | — | 登录态发现网络策略 |

## 5. 数据持久化与备份

数据全部落在 named volumes(`docker volume ls`),`docker compose down` 不删数据;`down -v` 才会清空。

| Volume | 内容 | 备份 |
| --- | --- | --- |
| `mysql-data` | 业务数据 + Flyway 迁移记录 | `docker compose exec mysql sh -c 'mysqldump -uroot -proot ai_test_platform' > backup.sql` |
| `redis-data` | 缓存(当前无引用) | RDB 落盘快照即可 |
| `minio-data` | 对象存储(当前无引用) | 桶镜像 / restic / mc mirror |

备份示例(mysql):

```bash
mkdir -p backups
docker compose exec -T mysql mysqldump -uroot -proot --single-transaction ai_test_platform > backups/$(date +%F).sql
```

恢复:

```bash
cat backups/2026-08-20.sql | docker compose exec -T mysql mysql -uroot -proot ai_test_platform
```

## 6. 升级

```bash
git pull                     # 拉取新版本
docker compose build         # 重建镜像(前端 nginx、platform、web-runner)
docker compose up -d         # 滚动重启;Flyway 自动执行新增迁移
```

注意事项:

- **已应用的迁移文件不可修改**,新变更一律新增 `V{n+1}__*.sql`。改旧迁移会使现有库校验失败。
  > 例外:V15/V16 的 TEXT 默认值从 `DEFAULT '{}'` 改为 `DEFAULT ('{}')`(MySQL 8 对 BLOB/TEXT/JSON 列的字面量默认值报错 1101)。旧语法在 MySQL 8.4 上**必然执行失败**,因此不存在"已用旧语法建库"的 MySQL 部署,升级无校验冲突;仅本地 H2 开发库需按 §6.1 处理。
- **`APP_SECRET_KEY` 一经启用不可更换**:改键后库内已加密的 Jira/Figma 凭证无法解密(启动即抛异常)。键值变化视为数据迁移。
- Flyway 迁移在 platform 启动时自动执行;失败会阻塞启动(依赖 mysql healthy 即前置就绪)。

### 6.1 本地 H2 开发库的迁移变更

本地开发(`--spring.profiles.active=local`)用 H2 文件库 `.local-data/`。若改动了已应用迁移的 SQL,启动会报 checksum 校验失败。处理:删除 `.local-data/ai-test-platform.*` 重建(初始管理员自动重建)。**不要用 Flyway `repair()` 修补,实测会清掉已应用迁移导致数据丢失。**

## 7. 安全清单

- [ ] `APP_SECRET_KEY` 已设置(`openssl rand -base64 32`),并妥善保管。
- [ ] 数据库账号非默认(root/root),已收紧 `APP_DB_*`。
- [ ] `web` 为唯一对外入口;`38081`/`19323`/`3306`/`6379` 不暴露公网。
- [ ] `APP_SECURITY_ENABLED` 保持 `true`。
- [ ] 生产走 HTTPS(在 nginx 前加反向代理或改 `web` 服务证书),并设置 `X-Forwarded-Proto`(nginx.conf 已透传)。

## 8. 快速验证部署是否就绪

```bash
curl -s http://127.0.0.1:38081/actuator/health # 后端直连健康:{"status":"UP",...}
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:38080/   # 前端(经 nginx):200
curl -s http://127.0.0.1:19323/health           # web-runner:{"status":"UP",...}
```
