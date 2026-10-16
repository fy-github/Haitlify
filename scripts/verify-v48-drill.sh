#!/usr/bin/env bash
# 在一次性演练库上验证 V48 语法与效果（结构复制 → 应用 → 检查默认值 → 清理）。
set -euo pipefail
C=ai-test-platform-mysql
q() { docker exec -i "$C" sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" $1" 2>/dev/null; }

q "-e 'drop database if exists drill_v48; create database drill_v48'"
docker exec -i "$C" sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --no-data ai_test_platform' 2>/dev/null \
  | docker exec -i "$C" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" drill_v48' 2>/dev/null
docker exec -i "$C" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" drill_v48' 2>/dev/null \
  < "$(git rev-parse --show-toplevel)/server/platform/src/main/resources/db/migration/V48__drop_default_development_actor.sql"
echo "v48-applied"
q "-N drill_v48 -e \"select 'system-dev-defaults-remaining', count(*) from information_schema.columns where table_schema='drill_v48' and column_default like '%system-dev%'\""
q "-N drill_v48 -e \"select 'system-defaults', count(*) from information_schema.columns where table_schema='drill_v48' and column_default = 'system'\""
q "-e 'drop database drill_v48'"
echo "drill-v48-clean"
