#!/usr/bin/env bash
# 跨版本回滚演练（带数据恢复）：
#   A. 用旧镜像（默认 haitlify-isolated-platform:v70）对「已升级到新版本」的库启动 → 必须被 Flyway 拒绝（证明不能原地回滚）；
#   B. 把旧版本备份恢复到演练库 → 旧镜像启动 healthy、表计数与备份一致（证明「恢复 + 回滚」这条路可行）。
# 全程只操作演练库 haitlify_rollback_drill 与临时容器，不动现行库和现行服务。
#
# 用法：bash scripts/rollback-with-restore-drill.sh [旧镜像标签，默认 v70]
# 环境：MYSQL（默认 haitlify-isolated-mysql）、DRILL_DB（默认 haitlify_rollback_drill）、
#       DRILL_PORT（默认 38092）、ENV_FILE（默认 deploy/isolated/.env）
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OLD_TAG="${1:-${OLD_IMAGE_TAG:-v70}}"
OLD_IMAGE="${OLD_IMAGE:-haitlify-isolated-platform:${OLD_TAG}}"
NEW_IMAGE="${NEW_IMAGE:-haitlify-isolated-platform:latest}"
# 隔离启动保护把库名钉死为 haitlify_isolated，所以演练用一个**独立的临时 MySQL**（不碰现行库/容器）。
# 启动保护同时钉死库名与主机端口（只允许 mysql:3306/127.0.0.1:13306/localhost:13306），
# 所以演练用**独立网络 + 名为 mysql 的临时实例**，与现行栈完全隔离，也不复用其库。
# 容器名必须带前缀（绝不能叫 mysql：那会误删宿主上任何同名容器），
# 用网络别名满足启动保护对 mysql:3306 的固定要求。
MYSQL="haitlify-rollback-mysql"
MYSQL_IMAGE="${ROLLBACK_MYSQL_IMAGE:-mysql:8.4}"
NET="${DRILL_NETWORK:-haitlify-rollback-net}"
DB="haitlify_isolated"
PORT="${DRILL_PORT:-38092}"
NAME="haitlify-rollback-restore-drill"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/deploy/isolated/.env}"

[ -f "$ENV_FILE" ] || { echo "BLOCKED 缺少 $ENV_FILE" >&2; exit 2; }
if [ -z "${MYSQL_ROOT_PASSWORD:-}" ]; then
  MYSQL_ROOT_PASSWORD="$(grep -E '^HAITLIFY_ISOLATED_MYSQL_ROOT_PASSWORD=' "$ENV_FILE" | tail -1 | cut -d= -f2-)"
fi
[ -n "${MYSQL_ROOT_PASSWORD:-}" ] || { echo "BLOCKED 缺少 MySQL root 口令" >&2; exit 2; }
DB_PASSWORD="$(grep -E '^HAITLIFY_ISOLATED_DB_PASSWORD=' "$ENV_FILE" | tail -1 | cut -d= -f2-)"
[ -n "$DB_PASSWORD" ] || { echo "BLOCKED 缺少 HAITLIFY_ISOLATED_DB_PASSWORD" >&2; exit 2; }

fail() { echo "FAIL: $*" >&2; exit 1; }
# 只回收「本次创建」的资源：宿主上可能本来就有同名容器/网络，不能一概删除。
OWNED_LABEL="haitlify.drill=rollback-restore"
CREATED_NET=no
owned() { docker inspect "$1" --format '{{index .Config.Labels "haitlify.drill"}}' 2>/dev/null | grep -qx rollback-restore; }
cleanup() {
  # -v：临时容器用的是匿名卷，不带 -v 每跑一次就留一个孤儿卷。
  if owned "$NAME"; then docker rm -fv "$NAME" >/dev/null 2>&1 || true; fi
  if owned "$MYSQL"; then docker rm -fv "$MYSQL" >/dev/null 2>&1 || true; fi
  if [ "$CREATED_NET" = "yes" ]; then docker network rm "$NET" >/dev/null 2>&1 || true; fi
  rm -f "${TMPDIR:-/tmp}/v70-backup.sql"
}
trap cleanup EXIT

# 阶段之间只回收应用容器；MySQL 与网络要留到演练结束。
stop_app() { if owned "$NAME"; then docker rm -fv "$NAME" >/dev/null 2>&1 || true; fi; }

start_mysql() {
  if docker network inspect "$NET" >/dev/null 2>&1; then
    echo "复用已存在的网络 ${NET}（结束时不会删除）"
  else
    docker network create --label "$OWNED_LABEL" "$NET" >/dev/null
    CREATED_NET=yes
  fi
  if owned "$MYSQL"; then docker rm -f "$MYSQL" >/dev/null 2>&1 || true; fi
  docker run -d --name "$MYSQL" --label "$OWNED_LABEL" --network "$NET" --network-alias mysql \
    -e MYSQL_ROOT_PASSWORD="$MYSQL_ROOT_PASSWORD" -e MYSQL_DATABASE="$DB" "$MYSQL_IMAGE" >/dev/null
  for _ in $(seq 1 60); do
    if docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL" mysql -uroot -e 'select 1' >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  docker logs --tail 30 "$MYSQL" || true
  fail "临时 MySQL 未就绪"
}

docker image inspect "$OLD_IMAGE" >/dev/null 2>&1 || fail "旧镜像不存在：$OLD_IMAGE"
docker image inspect "$NEW_IMAGE" >/dev/null 2>&1 || fail "新镜像不存在：$NEW_IMAGE"

# 同一个镜像时只能验证「备份 + 恢复」的管道，跨版本语义（新 schema 拒绝旧应用）无从验证。
# 这种情况显式降级，并在结论里写清楚，绝不冒充跨版本回滚已通过。
SAME_IMAGE=no
if [ "$(docker image inspect "$OLD_IMAGE" --format '{{.Id}}')" = "$(docker image inspect "$NEW_IMAGE" --format '{{.Id}}')" ]; then
  SAME_IMAGE=yes
  echo "注意：新旧镜像相同，本次只验证备份/恢复管道，不验证跨版本行为。"
fi

start_mysql
root_sql() { docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL" mysql -uroot -N -e "$1"; }
db_sql() { docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL" mysql -uroot "$DB" -N -e "$1"; }

PREFLIGHT_FILE="$ROOT_DIR/deploy/isolated/.preflight/isolated-preflight.json"
[ -f "$PREFLIGHT_FILE" ] || fail "缺少预检结果 $PREFLIGHT_FILE"

run_app() { # $1=image $2=tag
  if owned "$NAME"; then docker rm -f "$NAME" >/dev/null 2>&1 || true; fi
  docker run -d --name "$NAME" --label "$OWNED_LABEL" --network "$NET" \
    -e APP_PORT=8080 -e APP_GIT_SHA="$2" -e APP_ASSISTANT_RECONCILE_ENABLED=false \
    -e APP_ENV_ID=haitlify-isolated \
    -e APP_ENV_PREFLIGHT_HASH="$(node -e "process.stdout.write(require('$PREFLIGHT_FILE').resolvedConfigHash||'')")" \
    -e APP_ENV_PREFLIGHT_RESULT=/app/preflight/isolated-preflight.json \
    -e APP_DB_URL="jdbc:mysql://mysql:3306/${DB}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai" \
    -e APP_DB_USERNAME=haitlify_app \
    -e APP_DB_PASSWORD="$DB_PASSWORD" \
    -e APP_SECRET_KEY="$(grep -E '^HAITLIFY_ISOLATED_SECRET_KEY=' "$ENV_FILE" | tail -1 | cut -d= -f2-)" \
    -e APP_WEB_AUTOMATION_RUNNER_ENABLED=false \
    -v "$PREFLIGHT_FILE:/app/preflight/isolated-preflight.json:ro" \
    -p "127.0.0.1:${PORT}:8080" "$1" >/dev/null
}

wait_health() {
  local h=""
  for _ in $(seq 1 45); do
    h="$(curl -s --max-time 2 "http://127.0.0.1:$PORT/api/system/health" || true)"
    echo "$h" | grep -q '"status":"UP"' && { echo "$h"; return 0; }
    docker ps -q -f "name=^${NAME}$" >/dev/null || break
    sleep 2
  done
  return 1
}

echo "== 0/5 准备演练库（临时 MySQL） =="
root_sql "create user if not exists 'haitlify_app'@'%' identified by '$DB_PASSWORD'; grant all on \`$DB\`.* to 'haitlify_app'@'%'; flush privileges;"

echo "== 1/5 旧镜像对全新库迁移（得到「旧版本备份」的基线） =="
run_app "$OLD_IMAGE" "$OLD_TAG"
wait_health >/dev/null || { docker logs --tail 40 "$NAME" || true; fail "旧镜像在全新库上未就绪"; }
V_OLD="$(db_sql 'select max(cast(version as unsigned)) from flyway_schema_history where success=1')"
COUNTS_OLD="$(db_sql "select concat((select count(*) from assistant_operation_ledger),'/',(select count(*) from assistant_draft))")"
echo "旧版本 schema=$V_OLD counts=$COUNTS_OLD"
docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL" mysqldump -uroot --single-transaction --routines "$DB" > "$TMPDIR/v70-backup.sql" || fail "备份失败"
echo "备份大小 $(wc -c < "$TMPDIR/v70-backup.sql") 字节"
stop_app

echo "== 2/5 升级到新版本 =="
run_app "$NEW_IMAGE" latest
wait_health >/dev/null || { docker logs --tail 40 "$NAME" || true; fail "新镜像升级未就绪"; }
V_NEW="$(db_sql 'select max(cast(version as unsigned)) from flyway_schema_history where success=1')"
echo "升级后 schema=$V_NEW"
if [ "$SAME_IMAGE" = "no" ]; then
  [ "$V_NEW" -gt "$V_OLD" ] || fail "新版本没有推进 schema（$V_OLD → ${V_NEW}）"
fi
stop_app

echo "== 3/5 原地回滚：旧镜像直接跑在新 schema 上（必须被拒绝） =="
if [ "$SAME_IMAGE" = "yes" ]; then
  echo "跳过：新旧同镜像，无法构造「旧应用 + 新 schema」场景（跨版本结论仍为未验证）"
else
run_app "$OLD_IMAGE" "$OLD_TAG"
if wait_health >/dev/null; then
  docker logs --tail 20 "$NAME" || true
  cleanup
  fail "旧镜像竟然跑在了新 schema 上：回滚兼容性假设不成立，需要重新评估"
fi
echo "旧镜像被拒绝（符合预期：Flyway 检测到未解析的已应用迁移）"
docker logs --tail 6 "$NAME" 2>&1 | sed 's/^/  /' || true
stop_app
fi

echo "== 4/5 恢复旧版本备份后回滚 =="
root_sql "drop database if exists \`$DB\`; create database \`$DB\` character set utf8mb4;"
docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL" mysql -uroot "$DB" < "$TMPDIR/v70-backup.sql" || fail "恢复备份失败"
root_sql "create user if not exists 'haitlify_app'@'%' identified by '$DB_PASSWORD'; grant all on \`$DB\`.* to 'haitlify_app'@'%'; flush privileges;"
run_app "$OLD_IMAGE" "$OLD_TAG"
wait_health >/dev/null || { docker logs --tail 40 "$NAME" || true; fail "恢复备份后旧镜像仍未就绪"; }
COUNTS_RESTORED="$(db_sql "select concat((select count(*) from assistant_operation_ledger),'/',(select count(*) from assistant_draft))")"
V_RESTORED="$(db_sql 'select max(cast(version as unsigned)) from flyway_schema_history where success=1')"
echo "恢复后 schema=$V_RESTORED counts=$COUNTS_RESTORED"
[ "$COUNTS_RESTORED" = "$COUNTS_OLD" ] || fail "恢复后计数不一致：$COUNTS_OLD → $COUNTS_RESTORED"
[ "$V_RESTORED" = "$V_OLD" ] || fail "恢复后 schema 不一致：$V_OLD → $V_RESTORED"

echo "== 5/5 回收 =="
cleanup
trap - EXIT
if [ "$SAME_IMAGE" = "yes" ]; then
  echo "PASS（仅备份/恢复管道）：同镜像下备份→升级→恢复→启动，计数与 schema 一致；跨版本回滚仍未验证。"
else
  echo "PASS: 跨版本回滚演练通过——旧镜像不能原地跑新 schema（被拒绝），恢复旧备份后旧镜像可正常启动且数据一致。"
fi
