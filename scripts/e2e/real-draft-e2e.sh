#!/usr/bin/env bash
# 真实业务纵链 E2E 的运行入口（默认 CI 不跑）：
#   真实模型 → 真实 stdio 网关 → 平台签发的 MCP 租约 → 真实 draft_create → 草稿落库。
#
# 做法与 scripts/e2e/hermes-model-e2e.sh 同构：用一份**临时 HERMES_HOME**（只复制 profile 的
# config.yaml 与 .env，均 0600）起真实的 Hermes TUI gateway 子进程，跑完就回收。
# 该临时 HOME 同时是「网关配置文件」的落点——平台写的条目与子进程读的配置必须是同一个文件。
#
# 用法：
#   scripts/e2e/real-draft-e2e.sh            # 跑一次
#   KEEP_HOME=1 scripts/e2e/real-draft-e2e.sh  # 保留临时 HOME（调试用，默认清理）
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# isolated-inputs: canonicalize and validate every path before any write, copy or rm.
canonical_path() {
  local path="$1" parent suffix resolved
  while [[ "$path" == */ && "$path" != / ]]; do path="${path%/}"; done
  if [[ -e "$path" ]]; then
    if [[ -d "$path" ]]; then
      (cd -P "$path" && pwd -P)
    else
      /bin/realpath "$path"
    fi
    return
  fi
  parent="$path"
  while [[ ! -e "$parent" && "$parent" != / ]]; do
    parent="${parent%/*}"; [[ -z "$parent" ]] && parent=/
  done
  [[ -d "$parent" ]] || return 1
  suffix="${path#"$parent"}"
  resolved="$(cd -P "$parent" && pwd -P)" || return 1
  printf '%s%s\n' "$resolved" "$suffix"
}
require_path() {
  local name="$1" value="${!1:-}" canonical
  if [[ -z "$value" || "$value" != /* || "$value" == .. || "$value" == ../* || "$value" == */../* || "$value" == */.. ]]; then
    echo "required absolute isolated path: $name" >&2; exit 2
  fi
  canonical="$(canonical_path "$value")" || { echo "cannot canonicalize path: $name" >&2; exit 2; }
  [[ "$canonical" != / ]] || { echo "refusing filesystem root: $name" >&2; exit 2; }
  printf -v "$name" '%s' "$canonical"
}
path_overlaps() {
  [[ "$1" == "$2" || "$1" == "$2/"* || "$2" == "$1/"* ]]
}
for name in HAITLIFY_REAL_DRAFT_E2E_PROFILE HAITLIFY_REAL_DRAFT_E2E_SCRATCH HAITLIFY_REAL_DRAFT_E2E_EVIDENCE_DIR HAITLIFY_REAL_DRAFT_E2E_AGENT_DIR HAITLIFY_REAL_DRAFT_E2E_PYTHON; do
  require_path "$name"
done
PROFILE_DIR="$HAITLIFY_REAL_DRAFT_E2E_PROFILE"
SCRATCH="$HAITLIFY_REAL_DRAFT_E2E_SCRATCH"
EVIDENCE_DIR="$HAITLIFY_REAL_DRAFT_E2E_EVIDENCE_DIR"
AGENT_DIR="$HAITLIFY_REAL_DRAFT_E2E_AGENT_DIR"
PYTHON="$HAITLIFY_REAL_DRAFT_E2E_PYTHON"
HOME_DIR="$SCRATCH/hermes-home"
if [[ ! -f "$PROFILE_DIR/config.yaml" || ! -x "$PYTHON" || ! -d "$AGENT_DIR" ]]; then
  echo "required valid profile, executable Python and agent directory" >&2; exit 2
fi
for source in "$PROFILE_DIR" "$AGENT_DIR" "$PYTHON"; do
  if path_overlaps "$source" "$SCRATCH" || path_overlaps "$source" "$EVIDENCE_DIR"; then
    echo "output directory overlaps a protected input" >&2; exit 2
  fi
done
if path_overlaps "$SCRATCH" "$EVIDENCE_DIR"; then
  echo "scratch and evidence directories overlap" >&2; exit 2
fi
SCRATCH_OWNER="$SCRATCH/.haitlify-real-draft-e2e-owned"
if [[ -e "$SCRATCH" ]]; then
  [[ -d "$SCRATCH" && -f "$SCRATCH_OWNER" && "$(<"$SCRATCH_OWNER")" == "haitlify-real-draft-e2e-v1" ]] || {
    echo "scratch directory is not an owned retry root" >&2; exit 2;
  }
else
  mkdir -p "$SCRATCH" || exit 2
  printf '%s\n' 'haitlify-real-draft-e2e-v1' > "$SCRATCH_OWNER" || exit 2
fi
EVIDENCE_OWNER="$EVIDENCE_DIR/.haitlify-real-draft-e2e-evidence-owned"
if [[ -e "$EVIDENCE_DIR" ]]; then
  [[ -d "$EVIDENCE_DIR" && -f "$EVIDENCE_OWNER" && "$(<"$EVIDENCE_OWNER")" == "haitlify-real-draft-e2e-evidence-v1" ]] || {
    echo "evidence directory is not an owned retry root" >&2; exit 2;
  }
else
  mkdir -p "$EVIDENCE_DIR" || exit 2
  printf '%s\n' 'haitlify-real-draft-e2e-evidence-v1' > "$EVIDENCE_OWNER" || exit 2
fi
PORT="${HAITLIFY_REAL_DRAFT_E2E_PORT:-18080}"
LOG="$EVIDENCE_DIR/mvn-run.log"

MYSQL_CONTAINER="${HAITLIFY_REAL_DRAFT_E2E_MYSQL_CONTAINER:-haitlify-isolated-mysql}"
DB_URL="${HAITLIFY_REAL_DRAFT_E2E_DB_URL:-}"
DB_USER="${HAITLIFY_REAL_DRAFT_E2E_DB_USERNAME:-haitlify_app}"
DB_PASSWORD=""

# 只从 deploy/isolated/.env 解析 PB 密码这一个键：不 source 整个文件、不 export 到全局、不打印。
# （compose 里 MYSQL_USER/MYSQL_PASSWORD 就是这一对；凭据绝不进代码、不进命令行、不进证据。）
read_isolated_db_password() {
  awk -F= '$1=="HAITLIFY_ISOLATED_DB_PASSWORD" { sub(/^[^=]*=/, ""); print; exit }' \
    "$ROOT_DIR/deploy/isolated/.env" 2>/dev/null | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'\$//"
}

# 起 Maven 之前的连接自检：库不可达 / 凭据无效就明确失败（退出码 2），不要变成一屏 Flyway 栈。
preflight_mysql() {
  local out="$EVIDENCE_DIR/14-mysql-preflight.txt"
  {
    echo "db.url=$DB_URL"
    echo "db.user=$DB_USER"
    echo "db.password=<解析自 deploy/isolated/.env 的 HAITLIFY_ISOLATED_DB_PASSWORD；不落盘、不打印>"
    echo "mysql.container=$MYSQL_CONTAINER"
  } > "$out"

  if ! nc -z 127.0.0.1 13306 >/dev/null 2>&1; then
    echo "MySQL 不可达：宿主 127.0.0.1:13306 无监听（先起 haitlify-isolated-mysql）" | tee -a "$out" >&2
    return 2
  fi
  echo "host.tcp=127.0.0.1:13306 reachable" >> "$out"

  # 密码通过 MYSQL_PWD 环境变量传给容器内的 mysql 客户端：
  # `docker exec -e MYSQL_PWD` 不带值 → 从 docker CLI 的环境继承，因此不会出现在任何进程命令行里。
  local probe
  probe=$(MYSQL_PWD="$DB_PASSWORD" docker exec -e MYSQL_PWD -i "$MYSQL_CONTAINER" \
      mysql -u"$DB_USER" -h127.0.0.1 -P3306 -N --force \
      -e "select concat('server.version=', version()); select concat('database=', database());" \
      haitlify_isolated 2>&1)
  if [ -z "$probe" ] || ! printf '%s' "$probe" | grep -q "server.version="; then
    echo "凭据/连通自检失败（库不可达或凭据无效）：$probe" | tee -a "$out" >&2
    return 2
  fi
  printf '%s\n' "$probe" | tee -a "$out"

  local flyway
  flyway=$(MYSQL_PWD="$DB_PASSWORD" docker exec -e MYSQL_PWD -i "$MYSQL_CONTAINER" \
      mysql -u"$DB_USER" -h127.0.0.1 -P3306 -N --force -e "
        select concat('flyway.history_table_exists=', count(*))
          from information_schema.tables
         where table_schema='haitlify_isolated' and table_name='flyway_schema_history';
        select concat('flyway.applied_success_count=', count(*))
          from flyway_schema_history where success=1;
        select concat('flyway.last_applied=', version, ' (', description, ')')
          from flyway_schema_history order by installed_rank desc limit 1;
        select concat('flyway.tables=', count(*))
          from information_schema.tables where table_schema='haitlify_isolated';" \
      haitlify_isolated 2>&1)
  printf '%s\n' "$flyway" | tee -a "$out"
  return 0
}

cleanup() {
  # 子进程由 surefire JVM 的 shutdown hook 结束；这里只兜底清理临时 HOME。
  if [ "${KEEP_HOME:-0}" != "1" ]; then
    rm -rf "$HOME_DIR"
  fi
  rm -f "$SCRATCH/.haitlify-mcp-"*.tmp 2>/dev/null || true
}


if [ ! -f "$PROFILE_DIR/config.yaml" ]; then
  echo "profile 缺失：$PROFILE_DIR/config.yaml" >&2
  exit 1
fi
if [ ! -x "$PYTHON" ]; then
  echo "Hermes 解释器缺失：$PYTHON" >&2
  exit 1
fi
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "端口 $PORT 已被占用（E2E 需要可预测的 public-base-url）" >&2
  exit 1
fi

mkdir -p "$EVIDENCE_DIR" || exit 2
trap cleanup EXIT

# 真库模式：先解出凭据并做连接自检；失败就退出 2（不跑 Flyway，不留半启动状态）。
if [ -n "$DB_URL" ]; then
  DB_PASSWORD="${HAITLIFY_REAL_DRAFT_E2E_DB_PASSWORD:-}"
  if [ -z "$DB_PASSWORD" ]; then
    DB_PASSWORD="$(read_isolated_db_password)"
  fi
  if [ -z "$DB_PASSWORD" ]; then
    echo "真库模式需要密码：deploy/isolated/.env 里没有 HAITLIFY_ISOLATED_DB_PASSWORD" >&2
    exit 2
  fi
  if ! preflight_mysql; then
    exit 2
  fi
fi

rm -rf "$HOME_DIR"
mkdir -p "$HOME_DIR" "$SCRATCH"
chmod 700 "$HOME_DIR"
cp "$PROFILE_DIR/config.yaml" "$HOME_DIR/config.yaml"
chmod 600 "$HOME_DIR/config.yaml"
if [ -f "$PROFILE_DIR/.env" ]; then
  cp "$PROFILE_DIR/.env" "$HOME_DIR/.env"
  chmod 600 "$HOME_DIR/.env"
fi
mkdir -p "$HOME_DIR/workspace"

cd "$ROOT_DIR/server/platform"
HAITLIFY_REAL_DRAFT_E2E=true \
HAITLIFY_REAL_DRAFT_E2E_HERMES_HOME="$HOME_DIR" \
HAITLIFY_REAL_DRAFT_E2E_EVIDENCE_DIR="$EVIDENCE_DIR" \
HAITLIFY_REAL_DRAFT_E2E_PYTHON="$PYTHON" \
HAITLIFY_REAL_DRAFT_E2E_AGENT_DIR="$AGENT_DIR" \
HAITLIFY_REAL_DRAFT_E2E_PORT="$PORT" \
HAITLIFY_REAL_DRAFT_E2E_DB_URL="$DB_URL" \
HAITLIFY_REAL_DRAFT_E2E_DB_USERNAME="$DB_USER" \
HAITLIFY_REAL_DRAFT_E2E_DB_PASSWORD="$DB_PASSWORD" \
HERMES_HOME="$HOME_DIR" \
  ./mvnw -o test -Dtest=RealModelDraftLeaseEndToEndTest > "$LOG" 2>&1
STATUS=$?

# 网关 stderr 摘要（平台按 debug 落日志）：只留 [hermes stderr] 行，并把租约 URL 脱敏。
# 先把证据收完再判定：失败路径的 stderr 往往才是唯一线索。
grep -a "\[hermes stderr\]" "$LOG" | sed -E 's#/mcp-lease/[A-Za-z0-9_-]{43}#/mcp-lease/<redacted>#g' \
  > "$EVIDENCE_DIR/07-gateway-stderr-mvn.txt" || true

# 网关自身的日志（HERMES_HOME/logs）：[gateway-exit]/[gateway-crash] 这类关键行在这里。
if [ -d "$HOME_DIR/logs" ]; then
  mkdir -p "$EVIDENCE_DIR/09-hermes-logs"
  cp -R "$HOME_DIR/logs/." "$EVIDENCE_DIR/09-hermes-logs/" 2>/dev/null || true
  find "$EVIDENCE_DIR/09-hermes-logs" -type f -exec \
    sed -i '' -E 's#/mcp-lease/[A-Za-z0-9_-]{43}#/mcp-lease/<redacted>#g' {} \; 2>/dev/null || true

  # 模型到底调了哪些工具：网关自己的 agent.log 里逐条记着 tool_executor 的完成事件。
  grep -a "agent.tool_executor: tool mcp__" "$HOME_DIR/logs/agent.log" 2>/dev/null \
    | sed -E 's#/mcp-lease/[A-Za-z0-9_-]{43}#/mcp-lease/<redacted>#g' \
    > "$EVIDENCE_DIR/10-mcp-tool-calls.txt" || true
fi

REPORT="$ROOT_DIR/server/platform/target/surefire-reports/com.haitlify.aitest.platform.assistant.conversation.RealModelDraftLeaseEndToEndTest.txt"
[ -f "$REPORT" ] || { echo "缺少 surefire 报告：$REPORT" >&2; tail -40 "$LOG" >&2; exit 1; }
grep -q "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0" "$REPORT" || {
  echo "E2E 未通过（或被跳过）：" >&2
  cat "$REPORT" >&2
  exit 1
}

echo "PASS: 真实模型经租约调用 draft_create 并落库（1 test / 0 skipped）"
echo "证据目录：$EVIDENCE_DIR"
echo "退出码：$STATUS"
