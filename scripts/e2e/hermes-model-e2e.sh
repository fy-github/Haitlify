#!/usr/bin/env bash
# 平台级 Hermes 真实模型 E2E：
#   1) 用一份隔离的状态目录起一个临时 Hermes（同样的模型配置，独立 /state），把 9420 映射到本机 19420；
#   2) 用平台自己的 HermesAssistantRuntime 提交一条真实 prompt；
#   3) 无论成功失败都回收临时容器与临时状态目录。
# 前置：隔离栈已启动、hermes 容器内已有可用模型配置（/state/config.yaml）。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERMES_CONTAINER="${HERMES_CONTAINER:-haitlify-isolated-hermes}"
E2E_IMAGE="${E2E_IMAGE:-haitlify-isolated-hermes:latest}"
PORT="${HAITLIFY_HERMES_E2E_PORT:-19420}"
NAME="haitlify-hermes-e2e"
STATE_DIR="${TMPDIR:-/tmp}/haitlify-hermes-e2e-state"

cleanup() {
  docker rm -fv "$NAME" >/dev/null 2>&1 || true
  rm -rf "$STATE_DIR"
}
trap cleanup EXIT

if ! docker ps --format '{{.Names}}' | grep -qx "$HERMES_CONTAINER"; then
  echo "hermes 容器未运行：$HERMES_CONTAINER" >&2
  exit 1
fi

cleanup
mkdir -p "$STATE_DIR"
# 只复制配置（含模型凭据），不复制会话/日志；临时目录随脚本删除。
docker exec "$HERMES_CONTAINER" sh -c 'cat /state/config.yaml' > "$STATE_DIR/config.yaml"
chmod 600 "$STATE_DIR/config.yaml"
docker run --rm -u 0 -v "$STATE_DIR:/state" --entrypoint sh "$E2E_IMAGE" \
  -c 'chown -R 10001:10001 /state && chmod 600 /state/config.yaml' >/dev/null

# 与 compose 里的 hermes 服务同构：bridge 入口 + 同样的环境变量；只换独立的状态目录与宿主端口。
docker run -d --rm --name "$NAME" -p "127.0.0.1:${PORT}:9420" \
  --tmpfs /tmp:uid=10001,gid=10001,mode=1777 \
  -e HERMES_HOME=/state -e HERMES_BRIDGE_HOST=0.0.0.0 -e HERMES_BRIDGE_PORT=9420 \
  -v "$STATE_DIR:/state" "$E2E_IMAGE" >/dev/null

for _ in $(seq 1 40); do
  if (exec 3<>"/dev/tcp/127.0.0.1/${PORT}") 2>/dev/null; then exec 3>&-; break; fi
  sleep 1
done

if [ -z "$(docker ps -q -f name=^${NAME}$)" ]; then
  echo "临时 Hermes 未运行，日志：" >&2
  docker logs --tail 40 "$NAME" >&2 || true
  exit 1
fi

cd "$ROOT_DIR/server/platform"
LOG="${TMPDIR:-/tmp}/haitlify-hermes-e2e-surefire.log"
HAITLIFY_HERMES_E2E=true HAITLIFY_HERMES_E2E_PORT="$PORT" \
  ./mvnw -o test -Dtest=HermesModelEndToEndTest > "$LOG" 2>&1 || { tail -40 "$LOG" >&2; exit 1; }

# 只拿到 mvn 退出码不够：跳过也是 0。必须确认真的跑了 1 条、且没有被 skip。
REPORT="$ROOT_DIR/server/platform/target/surefire-reports/com.haitlify.aitest.platform.assistant.runtime.HermesModelEndToEndTest.txt"
[ -f "$REPORT" ] || { echo "缺少 surefire 报告：$REPORT" >&2; exit 1; }
grep -q "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0" "$REPORT" || {
  echo "E2E 未按预期执行（可能被跳过）：" >&2; cat "$REPORT" >&2; exit 1;
}
echo "PASS: 平台 runtime → Hermes 网关 → 真实模型提交成功（1 test / 0 skipped）"
