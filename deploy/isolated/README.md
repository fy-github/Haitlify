# 隔离环境（deploy/isolated）

计划 §7.1 要求的 A0-E 交付物：新仓专用 Compose 定义 + 唯一受支持的启动入口。

## 启动

```bash
cp deploy/isolated/.env.example deploy/isolated/.env   # 填入真实值；该文件不入库
scripts/preflight/start-isolated-stack.sh check        # 只做预检
scripts/preflight/start-isolated-stack.sh start        # 预检通过后启动
scripts/preflight/start-isolated-stack.sh status
scripts/preflight/start-isolated-stack.sh stop         # 不删除卷/网络/镜像
```

`start` 的顺序是：解析 compose → 校验隔离要求 → 写入绑定配置 hash 的预检结果 → 复验 hash 未变 → `up -d`。
任何一步不通过都不会启动服务；预检结果会以只读方式挂进平台容器，平台在以下任一情况都会拒绝启动：
缺少环境标识、缺少预检结果或 `verdict` 非 PASS、哈希与预检结果不一致、数据源不是隔离目标
（库 `haitlify_isolated`，主机/端口只允许 `mysql:3306`、`127.0.0.1:13306`、`localhost:13306`；
容器名不参与白名单）、或数据源实际生效的池配置被间接改写。见 `DEPLOYMENT.md`。

## 隔离边界

| 维度 | 取值 |
|---|---|
| Compose project | `haitlify-isolated` |
| 容器名前缀 | `haitlify-isolated-*` |
| 网络 / 卷 | `haitlify-isolated-net` / `haitlify-isolated-mysql-data` |
| 数据库 | `haitlify_isolated`（应用账号 `haitlify_app`） |
| 宿主端口 | 13306 / 19324 / 38082 / 38083（不复用旧栈端口；**只绑 `127.0.0.1`**，不对局域网开放） |
| 凭据 | 只从 `deploy/isolated/.env` 读取，无默认口令 |
| Hermes 执行单元 | **尚未定义**：拓扑在 Gate A0 由实测确定；加入时不得挂载任何个人目录或 Hermes home |

## 边界说明

- 预检只读：不启动、不停止、不删除容器/卷/网络/镜像；资源清单快照写在 `.preflight/`（不入库）。
- `deploy/isolated/*` 之外的历史部署说明、旧运维脚本不构成本环境的启动指引。
- 通过预检不等于 A0-E 整体通过：还需在目标主机实跑（宿主端口占用检查、资源清单对照）并完成 Hermes 执行单元的隔离方案。
