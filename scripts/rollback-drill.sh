#!/usr/bin/env bash
# 回滚演练（A4：目标为隔离栈）：上一稳定镜像在「现行隔离库」上独立启动 →
# /api/system/health 自证 SHA → 历史计数核对（演练前后零变化）→ 回收演练容器。
# 全程不动正在运行的平台服务。
#
# 用法：bash scripts/rollback-drill.sh [镜像标签/短 SHA，默认取环境 ROLLBACK_IMAGE_TAG]
# 说明：镜像里的 APP_GIT_SHA 默认是构建参数（本机镜像实测为 unknown），因此演练容器由脚本
#       注入 APP_GIT_SHA=<tag>，health 回读的是「本次配置」而非镜像内容哈希；镜像内容另打印 digest。
# 环境：
#   ROLLBACK_IMAGE_TAG  要演练的镜像标签（必填，或用第一个参数）
#   LIVE                现行平台容器名（默认 haitlify-isolated-platform）
#   MYSQL               现行 MySQL 容器名（默认 haitlify-isolated-mysql）
#   SRC_DB              现行库名（默认 haitlify_isolated）
#   DRILL_PORT          演练容器宿主端口（默认 38091）
#   DRILL_NETWORK       演练容器网络（默认 haitlify-isolated-net）
#   口令：优先 MYSQL_ROOT_PASSWORD，否则读 deploy/isolated/.env
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE_TAG="${1:-${ROLLBACK_IMAGE_TAG:-}}"
[ -n "$IMAGE_TAG" ] || { echo "BLOCKED 需要回滚镜像标签：bash scripts/rollback-drill.sh <tag> 或导出 ROLLBACK_IMAGE_TAG" >&2; exit 2; }
IMAGE="${ROLLBACK_IMAGE:-haitlify-isolated-platform:${IMAGE_TAG}}"
# 兼容旧命名（历史栈镜像名不同）：显式传 ROLLBACK_IMAGE 覆盖即可。
LIVE="${LIVE:-haitlify-isolated-platform}"
NAME="haitlify-rollback-drill"
PORT="${DRILL_PORT:-38091}"
NET="${DRILL_NETWORK:-haitlify-isolated-net}"
MYSQL="${MYSQL:-haitlify-isolated-mysql}"
SRC_DB="${SRC_DB:-haitlify_isolated}"

if [ -z "${MYSQL_ROOT_PASSWORD:-}" ]; then
  ENV_FILE="${ENV_FILE:-$ROOT_DIR/deploy/isolated/.env}"
  [ -f "$ENV_FILE" ] && MYSQL_ROOT_PASSWORD="$(grep -E '^HAITLIFY_ISOLATED_MYSQL_ROOT_PASSWORD=' "$ENV_FILE" | tail -1 | cut -d= -f2-)"
fi
[ -n "${MYSQL_ROOT_PASSWORD:-}" ] || { echo "BLOCKED 缺少 MySQL root 口令" >&2; exit 2; }

fail() { echo "FAIL: $*" >&2; exit 1; }
# 中途失败也不能留下演练容器。
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
q() { docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL" mysql -uroot "$SRC_DB" -N -e "$1" 2>/dev/null; }

echo "== 0/5 前置检查 =="
docker image inspect "$IMAGE" >/dev/null 2>&1 || fail "镜像 $IMAGE 不存在"
docker rm -f "$NAME" >/dev/null 2>&1 || true

# 只看助手账本表（旧 AI 表按契约保留，但回滚演练关心的是新栈数据是否被动过）。
CLAUSE="select concat((select count(*) from assistant_operation_ledger),'/',(select count(*) from assistant_attempt_ledger),'/',(select count(*) from assistant_draft),'/',(select count(*) from assistant_report),'/',(select count(*) from assistant_reconciliation_event))"
BEFORE="$(q "$CLAUSE")"
FLYWAY_BEFORE="$(q 'select version from flyway_schema_history order by installed_rank desc limit 1')"
echo "before operation/attempt/draft/report/reconcile = $BEFORE ; flyway 最新 = $FLYWAY_BEFORE"

echo "== 1/5 用运行容器的真实配置启动旧镜像（宿主端口 ${PORT}）=="
[ -n "$(docker ps -q -f "name=^${LIVE}$")" ] || fail "现行容器 $LIVE 未运行，无法复用其运行配置"
ENVFLAGS=()
while IFS= read -r line; do
  case "$line" in
    APP_DB_URL=*|APP_DB_USERNAME=*|APP_DB_PASSWORD=*|APP_SECRET_KEY=*|APP_WEB_AUTOMATION_RUNNER_ENABLED=*|APP_WEB_AUTOMATION_RUNNER_BASE_URL=*|APP_HERMES_*=*|APP_ENV_ID=*|APP_ENV_PREFLIGHT_HASH=*|APP_ENV_PREFLIGHT_RESULT=*) ENVFLAGS+=(-e "$line") ;;
  esac
done < <(docker inspect "$LIVE" --format '{{range .Config.Env}}{{println .}}{{end}}')

PREFLIGHT_FILE="$ROOT_DIR/deploy/isolated/.preflight/isolated-preflight.json"
[ -f "$PREFLIGHT_FILE" ] || fail "缺少预检结果 ${PREFLIGHT_FILE}（先跑 start-isolated-stack.sh check）"
docker run -d --name "$NAME" --network "$NET" ${ENVFLAGS[@]+"${ENVFLAGS[@]}"} \
  -e APP_PORT=8080 \
  -e "APP_GIT_SHA=$IMAGE_TAG" \
  -e APP_ASSISTANT_RECONCILE_ENABLED=false \
  -v "$PREFLIGHT_FILE:/app/preflight/isolated-preflight.json:ro" \
  -p "127.0.0.1:${PORT}:8080" "$IMAGE" >/dev/null

echo "== 2/5 等待 health 就绪 =="
H=""
for _ in $(seq 1 60); do
  H="$(curl -s --max-time 2 "http://127.0.0.1:$PORT/api/system/health" || true)"
  echo "$H" | grep -q '"status":"UP"' && break
  sleep 2
done
echo "health: $H"
echo "$H" | grep -q '"status":"UP"' || { docker logs --tail 40 "$NAME" || true; fail "旧镜像未就绪（见上方容器日志）"; }
# SHA 来自本容器的 APP_GIT_SHA（配置级自证，不等于镜像内容哈希）；镜像内容以 digest 另记。
echo "$H" | grep -q "\"gitSha\":\"${IMAGE_TAG}\"" || { docker logs --tail 20 "$NAME" || true; fail "health 未回读本次注入的 gitSha=${IMAGE_TAG}"; }
echo "image digest: $(docker image inspect "$IMAGE" --format '{{index .RepoDigests 0}}' 2>/dev/null || docker image inspect "$IMAGE" --format '{{.Id}}')"

echo "== 3/5 演练后计数核对（应零变化）=="
AFTER="$(q "$CLAUSE")"
FLYWAY_AFTER="$(q 'select version from flyway_schema_history order by installed_rank desc limit 1')"
echo "after  operation/attempt/draft/report/reconcile = $AFTER ; flyway 最新 = $FLYWAY_AFTER"
[ "$BEFORE" = "$AFTER" ] || fail "计数不一致：$BEFORE → $AFTER"
[ "$FLYWAY_BEFORE" = "$FLYWAY_AFTER" ] || fail "flyway 历史被演练容器改动"

echo "== 4/5 历史数据可读抽样（只读）=="
q "select count(*) from assistant_operation_ledger" >/dev/null || fail "助手账本不可读"
echo "助手账本可读 OK"

echo "== 5/5 回收演练容器 =="
cleanup
trap - EXIT
echo "PASS: 回滚演练通过——旧镜像（health 自证 ${IMAGE_TAG}）在现行隔离库启动正常，数据零变化。"
