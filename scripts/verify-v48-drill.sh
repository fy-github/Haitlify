#!/usr/bin/env bash
# 在一次性演练库上验证 V48 语法与效果（结构复制 → 应用 → 检查默认值 → 清理）。
# A4：目标已切到隔离栈（不再引用旧容器/旧库）。
#
# 环境：
#   MYSQL_CONTAINER  默认 haitlify-isolated-mysql
#   SRC_DB           默认 haitlify_isolated
#   DRILL_DB         默认 drill_v48
#   口令：优先 MYSQL_ROOT_PASSWORD，否则读 deploy/isolated/.env
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
C="${MYSQL_CONTAINER:-haitlify-isolated-mysql}"
SRC_DB="${SRC_DB:-haitlify_isolated}"
DRILL_DB="${DRILL_DB:-drill_v48}"
MIGRATION="$ROOT_DIR/server/platform/src/main/resources/db/migration/V48__drop_default_development_actor.sql"

if [ -z "${MYSQL_ROOT_PASSWORD:-}" ]; then
  ENV_FILE="${ENV_FILE:-$ROOT_DIR/deploy/isolated/.env}"
  [ -f "$ENV_FILE" ] && MYSQL_ROOT_PASSWORD="$(grep -E '^HAITLIFY_ISOLATED_MYSQL_ROOT_PASSWORD=' "$ENV_FILE" | tail -1 | cut -d= -f2-)"
fi
[ -n "${MYSQL_ROOT_PASSWORD:-}" ] || { echo "BLOCKED 缺少 MySQL root 口令" >&2; exit 2; }
[ -f "$MIGRATION" ] || { echo "BLOCKED 迁移文件不存在：$MIGRATION" >&2; exit 2; }

q() { docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$C" mysql -uroot "$@" 2>/dev/null; }
dump() { docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$C" mysqldump -uroot --single-transaction --no-data "$1" 2>/dev/null; }
load() { docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$C" mysql -uroot "$1" 2>/dev/null; }

q -e "drop database if exists $DRILL_DB; create database $DRILL_DB"
dump "$SRC_DB" | load "$DRILL_DB"
load "$DRILL_DB" < "$MIGRATION"
echo "v48-applied"
REMAINING="$(q -N "$DRILL_DB" -e "select count(*) from information_schema.columns where table_schema='$DRILL_DB' and column_default like '%system-dev%'" | tr -d '[:space:]')"
echo "system-dev-defaults-remaining $REMAINING"
q -N "$DRILL_DB" -e "select 'system-defaults', count(*) from information_schema.columns where table_schema='$DRILL_DB' and column_default = 'system'"
q -e "drop database $DRILL_DB"
if [ "${REMAINING:-x}" != "0" ]; then
  echo "drill-v48-FAIL: system-dev 默认值仍有 $REMAINING 列残留" >&2
  exit 1
fi
echo "drill-v48-clean"
