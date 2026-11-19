#!/usr/bin/env bash
# 迁移/备份恢复演练（A4：目标已切到隔离栈，不再引用旧容器/旧库）。
#
# 步骤（全部对真实 MySQL 执行；输出即证据）：
#   1. 备份：mysqldump 当前业务库（结构+数据）
#   2. 恢复：恢复到独立演练库 <DB>_drill
#   3. 校验：助手账本/草稿/报告/对账表数量一致；旧 AI 表按「停机不停表」契约存在性一致
#   4. 关联抽查：attempt→operation、report→operation、reconciliation→operation 无孤儿
#   5. 报告：全部计数与比对结果打印；任何不一致非零退出
#
# 用法：bash scripts/ai-migration-drill.sh
# 环境：
#   MYSQL_CONTAINER  默认 haitlify-isolated-mysql
#   SRC_DB           默认 haitlify_isolated
#   DRILL_DB         默认 ${SRC_DB}_drill
#   DROP_DRILL_DB    默认 1（演练结束删除演练库）
#   口令优先取 MYSQL_ROOT_PASSWORD，否则从 deploy/isolated/.env 读 HAITLIFY_ISOLATED_MYSQL_ROOT_PASSWORD
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINER="${MYSQL_CONTAINER:-haitlify-isolated-mysql}"
SRC_DB="${SRC_DB:-haitlify_isolated}"
DRILL_DB="${DRILL_DB:-${SRC_DB}_drill}"
DRILL_DIR="${DRILL_DIR:-$(mktemp -d)}"
FAIL=0

if [ -z "${MYSQL_ROOT_PASSWORD:-}" ]; then
  ENV_FILE="${ENV_FILE:-$ROOT_DIR/deploy/isolated/.env}"
  if [ -f "$ENV_FILE" ]; then
    MYSQL_ROOT_PASSWORD="$(grep -E '^HAITLIFY_ISOLATED_MYSQL_ROOT_PASSWORD=' "$ENV_FILE" | tail -1 | cut -d= -f2-)"
  fi
fi
if [ -z "${MYSQL_ROOT_PASSWORD:-}" ]; then
  echo "BLOCKED 缺少 MySQL root 口令（导出 MYSQL_ROOT_PASSWORD 或准备 deploy/isolated/.env）" >&2
  exit 2
fi
# 口令只经环境变量进容器，不出现在命令行与日志里。
mysql_c() { docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$CONTAINER" mysql -uroot "$@" 2>/dev/null; }
mysql_sql() { mysql_c -N -e "$1"; }

echo "== 1/5 备份 $SRC_DB → $DRILL_DIR/backup.sql"
docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$CONTAINER" \
  mysqldump -uroot --single-transaction --routines --triggers "$SRC_DB" 2>/dev/null > "$DRILL_DIR/backup.sql"
echo "backup bytes: $(wc -c < "$DRILL_DIR/backup.sql" | tr -d ' ')"

echo "== 2/5 恢复到独立库 $DRILL_DB"
mysql_sql "drop database if exists $DRILL_DB; create database $DRILL_DB character set utf8mb4"
docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$CONTAINER" mysql -uroot "$DRILL_DB" 2>/dev/null < "$DRILL_DIR/backup.sql"
echo "restore done"

# 助手账本（A1–A3 新栈）：必须逐表数量一致。
ASSISTANT_TABLES="assistant_request_ledger assistant_operation_ledger assistant_attempt_ledger \
assistant_resource_ledger assistant_reconciliation_event assistant_draft assistant_report"
# 旧 AI 表按「停机不停表」保留：只校验存在性与数量一致，不校验业务语义。
LEGACY_TABLES="ai_analysis_runs ai_draft_test_points ai_assistant_artifacts ai_run_events ai_run_policies"

echo "== 3/5 数量与表存在性校验"
for table in $ASSISTANT_TABLES $LEGACY_TABLES; do
  src_exists=$(mysql_sql "select count(*) from information_schema.tables where table_schema='$SRC_DB' and table_name='$table'" | tr -d '[:space:]')
  dst_exists=$(mysql_sql "select count(*) from information_schema.tables where table_schema='$DRILL_DB' and table_name='$table'" | tr -d '[:space:]')
  if [ "${src_exists:-}" = "0" ] && [ "${dst_exists:-}" = "0" ]; then
    echo "INFO  $table: 两库均无此表"
    continue
  fi
  if [ "${src_exists:-}" != "${dst_exists:-}" ]; then
    echo "FAIL  $table: 表存在性不一致（src=$src_exists drill=$dst_exists）"
    FAIL=1
    continue
  fi
  src=$(mysql_sql "select count(*) from $SRC_DB.$table" | tr -d '[:space:]')
  dst=$(mysql_sql "select count(*) from $DRILL_DB.$table" | tr -d '[:space:]')
  if [ "$src" = "$dst" ]; then
    echo "PASS  $table: $src == $dst"
  else
    echo "FAIL  $table: src=$src drill=$dst"
    FAIL=1
  fi
done

echo "== 4/5 关键关联抽查"
check_orphans() {
  local label="$1" sql="$2"
  local orphans
  orphans=$(mysql_sql "$sql" | tr -d '[:space:]')
  if [ "${orphans:-0}" = "0" ]; then
    echo "PASS  $label: no orphans"
  else
    echo "FAIL  $label: $orphans orphans"
    FAIL=1
  fi
}
check_orphans "attempt → operation" \
  "select count(*) from $DRILL_DB.assistant_attempt_ledger a left join $DRILL_DB.assistant_operation_ledger o on a.operation_id = o.operation_id where o.operation_id is null"
check_orphans "report → operation" \
  "select count(*) from $DRILL_DB.assistant_report r left join $DRILL_DB.assistant_operation_ledger o on r.operation_id = o.operation_id where o.operation_id is null"
check_orphans "reconciliation → operation" \
  "select count(*) from $DRILL_DB.assistant_reconciliation_event e left join $DRILL_DB.assistant_operation_ledger o on e.operation_id = o.operation_id where o.operation_id is null"

echo "== 5/5 备份恢复演练报告"
if [ "$FAIL" = "0" ]; then
  echo "DRILL PASS — 备份可恢复、数量一致、关键关联完整（backup: $DRILL_DIR/backup.sql）"
else
  echo "DRILL FAIL — 见上方失败项"
fi
if [ "${DROP_DRILL_DB:-1}" = "1" ]; then
  mysql_sql "drop database if exists $DRILL_DB"
  echo "drill 库已清理（DROP_DRILL_DB=0 可保留）"
fi
exit "$FAIL"
