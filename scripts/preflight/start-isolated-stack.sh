#!/usr/bin/env bash
# Haitlify 隔离环境唯一受支持的启动入口（计划 §7.1，A0-E 交付物）。
#
# 用法：
#   scripts/preflight/start-isolated-stack.sh check     # 只做预检，不启动
#   scripts/preflight/start-isolated-stack.sh verify    # 复验当前配置 hash 与预检结果一致
#   scripts/preflight/start-isolated-stack.sh runtime   # 断言运行时护栏（read_only/cap_drop/no-new-privileges/非 root）
#   scripts/preflight/start-isolated-stack.sh start     # check → verify → up -d
#   scripts/preflight/start-isolated-stack.sh status    # 容器状态 + 预检新旧程度
#   scripts/preflight/start-isolated-stack.sh stop      # 停止容器，不删除卷/网络/镜像
#
# 纪律：
#   - 预检未通过或 env 文件缺失时拒绝启动；
#   - 启动前后各记录一次 docker 资源清单（.preflight/），便于与同机旧资源对照；
#   - 不执行 down -v / prune / rm，不删除任何卷、网络、镜像；回读确认属于人工操作。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PREFLIGHT_JS="$ROOT_DIR/scripts/preflight/environment-preflight.mjs"
COMPOSE_FILE="$ROOT_DIR/deploy/isolated/compose.yaml"
ENV_FILE="${HAITLIFY_ISOLATED_ENV_FILE:-$ROOT_DIR/deploy/isolated/.env}"
STATE_DIR="${HAITLIFY_ISOLATED_STATE_DIR:-$ROOT_DIR/deploy/isolated/.preflight}"
PREFLIGHT_JSON="$STATE_DIR/isolated-preflight.json"
INVENTORY_AFTER="$STATE_DIR/inventory-after-start.json"

die() { echo "拒绝执行: $*" >&2; exit 1; }

command -v node >/dev/null 2>&1 || die "缺少 node（预检与启动入口都需要）"
command -v docker >/dev/null 2>&1 || die "缺少 docker"

require_env_file() {
  [ -f "$ENV_FILE" ] || die "缺少环境文件 ${ENV_FILE}；先复制 deploy/isolated/.env.example 为 deploy/isolated/.env 并填入真实值（不入库）"
}

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

run_check() {
  require_env_file
  mkdir -p "$STATE_DIR"
  node "$PREFLIGHT_JS" check --compose "$COMPOSE_FILE" --env-file "$ENV_FILE" --out "$PREFLIGHT_JSON" --require-secrets
}

stored_hash() {
  [ -f "$PREFLIGHT_JSON" ] || die "没有预检结果 ${PREFLIGHT_JSON}；先运行 check"
  node -e '
    const fs = require("node:fs");
    const payload = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
    if (payload.verdict !== "PASS") { console.error("预检结果 verdict 不是 PASS：" + payload.verdict); process.exit(1); }
    if (!payload.resolvedConfigHash) { console.error("预检结果缺少 resolvedConfigHash"); process.exit(1); }
    process.stdout.write(payload.resolvedConfigHash);
  ' "$PREFLIGHT_JSON"
}

cmd_check() {
  run_check
}

cmd_verify() {
  local hash
  hash="$(stored_hash)"
  node "$PREFLIGHT_JS" check --compose "$COMPOSE_FILE" --env-file "$ENV_FILE" --out "$PREFLIGHT_JSON" \
    --require-secrets --expect-hash "$hash" --no-host-checks
}

# 运行时护栏断言（A0-E 验收口径）：文本层只保证清单级正确性，真正的隔离边界靠这些运行时约束。
# 启动后直接问 docker（而不是相信 compose 文件）：read_only / cap_drop / no-new-privileges / 非 root。
cmd_assert_runtime() {
  local app_services="haitlify-isolated-platform haitlify-isolated-web-runner haitlify-isolated-web"
  local failed=0
  for name in haitlify-isolated-mysql $app_services; do
    if ! docker inspect "$name" >/dev/null 2>&1; then
      echo "运行时护栏: 容器 ${name} 不存在（尚未创建？）" >&2
      failed=1
      continue
    fi
    local readonly capdrop secopt user
    readonly="$(docker inspect -f '{{.HostConfig.ReadonlyRootfs}}' "$name")"
    capdrop="$(docker inspect -f '{{json .HostConfig.CapDrop}}' "$name")"
    secopt="$(docker inspect -f '{{json .HostConfig.SecurityOpt}}' "$name")"
    user="$(docker inspect -f '{{.Config.User}}' "$name")"
    case " ${app_services} " in
      *" ${name} "*)
        if [ "${readonly}" != "true" ]; then
          echo "运行时护栏: ${name} 根文件系统不是只读（ReadonlyRootfs=${readonly}）" >&2
          failed=1
        fi
        if [ -z "${user}" ] || [ "${user}" = "0" ] || [ "${user%%:*}" = "0" ] || [ "${user}" = "root" ]; then
          echo "运行时护栏: ${name} 以 root 运行（User='${user}'）" >&2
          failed=1
        fi
        ;;
    esac
    case "${capdrop}" in
      *ALL*) : ;;
      *) echo "运行时护栏: ${name} 未 cap_drop ALL（CapDrop=${capdrop}）" >&2; failed=1 ;;
    esac
    case "${secopt}" in
      *no-new-privileges*) : ;;
      *) echo "运行时护栏: ${name} 未启用 no-new-privileges（SecurityOpt=${secopt}）" >&2; failed=1 ;;
    esac
  done
  if [ "${failed}" -ne 0 ]; then
    echo "拒绝：运行时护栏未满足；隔离栈不算启动成功（容器可能仍在运行，请人工处理，不要用 down -v）。" >&2
    return 1
  fi
  echo "运行时护栏: OK（mysql/app 全部 cap_drop=ALL + no-new-privileges；应用容器只读根 + 非 root）"
}

cmd_start() {
  # 已在运行就不要重跑 start：严格预检会把「端口被自己占用」判成 FAIL，
  # 并把 isolated-preflight.json 覆盖成失败结果（运行中的容器不受影响，但下一次 start 会看到脏结果）。
  if [ -n "$(docker ps -q -f 'name=^haitlify-isolated-platform$' 2>/dev/null)" ]; then
    echo "隔离栈已在运行；如需重启，请先执行 $0 stop 再 start（本次不做任何改动）。" >&2
    return 1
  fi
  run_check
  local hash
  hash="$(stored_hash)"
  # 复验：确认启动前解析到的配置与刚写入的预检结果 hash 一致（防止预检与启动之间配置被改动）。
  node "$PREFLIGHT_JS" check --compose "$COMPOSE_FILE" --env-file "$ENV_FILE" --out "$PREFLIGHT_JSON" \
    --require-secrets --expect-hash "$hash" --no-host-checks >/dev/null
  echo "预检通过，config hash=${hash:0:12}…，开始启动隔离栈（project=haitlify-isolated）"
  HAITLIFY_ISOLATED_PREFLIGHT_HASH="$hash" compose up -d
  # 命名卷首次创建时归 root，而 hermes 以 10001 运行且容器内无 CAP_CHOWN：
  # 不在宿主侧修正的话，Hermes 永远写不了自己的 HERMES_HOME（config/session/附件）。
  # 只在属主不对时做一次；容器本身仍是只读根 + cap_drop ALL。
  if docker volume inspect haitlify-isolated-hermes-state >/dev/null 2>&1; then
    owner="$(docker run --rm -u 0 -v haitlify-isolated-hermes-state:/state --entrypoint sh \
      haitlify-isolated-hermes:latest -c 'stat -c %u /state' 2>/dev/null || echo 10001)"
    if [ "$owner" != "10001" ]; then
      docker run --rm -u 0 -v haitlify-isolated-hermes-state:/state --entrypoint sh \
        haitlify-isolated-hermes:latest -c 'chown -R 10001:10001 /state' >/dev/null
      echo "已修正 hermes 状态卷属主为 10001（原本 ${owner}）"
    fi
  fi
  HAITLIFY_ISOLATED_PREFLIGHT_HASH="$hash" node "$PREFLIGHT_JS" inventory --out "$INVENTORY_AFTER" >/dev/null \
    || echo "警告: 启动后资源清单未记录（docker 不可用？）"
  # 容器创建后立刻断言运行时护栏：这一步失败即视为启动失败（配置漂移 / 宿主策略被改）。
  local waited=0
  until docker inspect haitlify-isolated-platform >/dev/null 2>&1; do
    sleep 2
    waited=$((waited + 2))
    if [ "$waited" -ge 30 ]; then break; fi
  done
  cmd_assert_runtime
  echo "启动请求已提交；容器健康状态用 status 查看，不要用“已 up”代替业务验证。"
}

cmd_status() {
  require_env_file
  compose ps
  if [ -f "$PREFLIGHT_JSON" ]; then
    local hash
    hash="$(stored_hash)"
    echo "预检结果: ${PREFLIGHT_JSON}（hash=${hash:0:12}…）"
    echo "复验当前配置是否仍与预检一致：scripts/preflight/start-isolated-stack.sh verify"
  else
    echo "尚无预检结果文件；当前配置未经过预检。"
  fi
}

cmd_stop() {
  require_env_file
  compose stop
  echo "容器已停止；卷、网络、镜像未删除。"
}

case "${1:-}" in
  check) cmd_check ;;
  verify) cmd_verify ;;
  start) cmd_start ;;
  status) cmd_status ;;
  runtime) cmd_assert_runtime ;;
  stop) cmd_stop ;;
  down | prune | rm)
    die "本入口不提供 ${1}：删除容器/卷/网络/镜像需要人工确认与单独授权"
    ;;
  *)
    echo "用法: $0 {check|verify|runtime|start|status|stop}" >&2
    exit 2
    ;;
esac
