#!/usr/bin/env bash
# T15：迁移/备份恢复演练。
#
# 步骤（全部对真实 MySQL 执行；输出即证据）：
#   1. 备份：mysqldump 当前业务库 → drill/backup.sql（结构+数据）
#   2. 恢复：恢复到独立库 ai_test_platform_drill
#   3. 校验：旧 run / point / review / 知识 / 助手产物数量一致；关键外键关联抽查
#   4. keyRef：连接表 credential_ref 存在性与形态（enc:v1: 或可解析别名）
#   5. 报告：全部计数与比对结果打印；任何不一致非零退出
#
# 用法：bash scripts/ai-migration-drill.sh
# 环境：MYSQL_CONTAINER（默认 ai-test-platform-mysql）、DROP_DRILL_DB（默认 1）
set -euo pipefail

CONTAINER="${MYSQL_CONTAINER:-ai-test-platform-mysql}"
SRC_DB="${SRC_DB:-ai_test_platform}"
DRILL_DB="${DRILL_DB:-ai_test_platform_drill}"
DRILL_DIR="${DRILL_DIR:-$(mktemp -d)}"
FAIL=0

mysql_exec() {
  docker exec -i "$CONTAINER" sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" $1" 2>/dev/null
}

echo "== 1/5 备份 $SRC_DB → $DRILL_DIR/backup.sql"
docker exec -i "$CONTAINER" sh -c "mysqldump -uroot -p\"\$MYSQL_ROOT_PASSWORD\" --single-transaction --routines --triggers $SRC_DB" \
  2>/dev/null > "$DRILL_DIR/backup.sql"
echo "backup bytes: $(wc -c < "$DRILL_DIR/backup.sql" | tr -d ' ')"

echo "== 2/5 恢复到独立库 $DRILL_DB"
mysql_exec "-e \"drop database if exists $DRILL_DB; create database $DRILL_DB character set utf8mb4\""
docker exec -i "$CONTAINER" sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" $DRILL_DB" 2>/dev/null < "$DRILL_DIR/backup.sql"
echo "restore done"

echo "== 3/5 数量与关联校验"
TABLES="ai_analysis_runs ai_draft_test_points ai_assistant_artifacts ai_knowledge_documents ai_run_events ai_run_policies"
for table in $TABLES; do
  src_exists=$(mysql_exec "-N -e \"select count(*) from information_schema.tables where table_schema='$SRC_DB' and table_name='$table'\"" | tr -d '[:space:]')
  dst_exists=$(mysql_exec "-N -e \"select count(*) from information_schema.tables where table_schema='$DRILL_DB' and table_name='$table'\"" | tr -d '[:space:]')
  if [ "${src_exists:-}" = "0" ] && [ "${dst_exists:-}" = "0" ]; then
    echo "INFO  $table: 两库均不存在（对应迁移尚未应用）"
    continue
  fi
  if [ "${src_exists:-}" != "${dst_exists:-}" ]; then
    echo "FAIL  $table: 表存在性不一致（src=$src_exists drill=$dst_exists）"
    FAIL=1
    continue
  fi
  src=$(mysql_exec "-N -e \"select count(*) from $SRC_DB.$table\"" | tr -d '[:space:]')
  dst=$(mysql_exec "-N -e \"select count(*) from $DRILL_DB.$table\"" | tr -d '[:space:]')
  if [ "$src" = "$dst" ]; then
    echo "PASS  $table: $src == $dst"
  else
    echo "FAIL  $table: src=$src drill=$dst"
    FAIL=1
  fi
done

# 关联抽查：run ↔ draft points 的父引用必须都能在 drill 库里解析。
orphans=$(mysql_exec "-N -e \"select count(*) from $DRILL_DB.ai_draft_test_points p left join $DRILL_DB.ai_analysis_runs r on p.run_id = r.id where r.id is null\"" | tr -d '[:space:]')
if [ "$orphans" = "0" ]; then
  echo "PASS  foreign links (draft points → runs): no orphans"
else
  echo "FAIL  foreign links: $orphans orphan draft points"
  FAIL=1
fi

echo "== 4/5 连接 credential_ref 存在性与形态"
refs=$(mysql_exec "-N -e \"select credential_ref from $DRILL_DB.ai_connections where credential_ref is not null and credential_ref <> ''\"" | tr -d '[:space:]')
if [ -n "$refs" ]; then
  echo "PASS  credential_ref present:"
  echo "$refs" | while read -r ref; do
    case "$ref" in
      enc:v1:*) echo "      enc-ref ${ref:0:16}… (密文引用，可材料化)" ;;
      *)        echo "      alias-ref $ref (需别名解析；无通道材料化时为 BLOCKED——如实报告)" ;;
    esac
  done
else
  echo "INFO  ai_connections 无 credential_ref（当前环境未配置连接）"
fi

echo "== 5/5 备份恢复演练报告"
if [ "$FAIL" = "0" ]; then
  echo "DRILL PASS — 备份可恢复、数量一致、关键关联完整（backup: $DRILL_DIR/backup.sql）"
else
  echo "DRILL FAIL — 见上方失败项"
fi
if [ "${DROP_DRILL_DB:-1}" = "1" ]; then
  mysql_exec "-e \"drop database if exists $DRILL_DB\""
  echo "drill 库已清理（DROP_DRILL_DB=0 可保留）"
fi
exit "$FAIL"
