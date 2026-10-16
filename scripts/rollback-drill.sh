#!/usr/bin/env bash
# 回滚演练：上一稳定镜像在“现行库”上独立启动 → /api/system/health 自证 SHA →
# 历史计数核对（演练前后零变化）→ 回收演练容器。全程不动正在运行的服务。
#
# 用法：bash scripts/rollback-drill.sh [镜像标签/短 SHA，默认 540a740]
# 说明：镜像构建时已把 APP_GIT_SHA 编入镜像配置，health 自证；脚本不注入 SHA。
set -euo pipefail

IMAGE_TAG="${1:-540a740}"
IMAGE="haitlify-api-platform:${IMAGE_TAG}"
LIVE="ai-test-platform-server"
NAME="rollback-drill"
PORT="${DRILL_PORT:-38091}"
NET="haitlify-api_default"
MYSQL="ai-test-platform-mysql"

fail() { echo "FAIL: $*" >&2; exit 1; }
q() { docker exec -i "$MYSQL" sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" ai_test_platform -N -e \"$1\"" 2>/dev/null; }

echo "== 0/5 前置检查 =="
docker image inspect "$IMAGE" >/dev/null 2>&1 || fail "镜像 $IMAGE 不存在"
docker rm -f "$NAME" >/dev/null 2>&1 || true

CLAUSE="select concat((select count(*) from ai_assistant_artifacts),'/',(select count(*) from ai_draft_test_points),'/',(select count(*) from ai_analysis_runs),'/',(select count(*) from ai_agent_memories))"
BEFORE="$(q "$CLAUSE")"
FLYWAY_BEFORE="$(q 'select version from flyway_schema_history order by installed_rank desc limit 1')"
echo "before 产物/草稿点/运行/知识 = $BEFORE ; flyway 最新 = $FLYWAY_BEFORE"

echo "== 1/5 用运行容器的真实配置启动旧镜像（宿主端口 ${PORT}）=="
ENVFLAGS=()
while IFS= read -r line; do
  case "$line" in
    APP_DB_URL=*|APP_DB_USERNAME=*|APP_DB_PASSWORD=*|APP_SECRET_KEY=*|APP_WEB_AUTOMATION_RUNNER_ENABLED=*|APP_WEB_AUTOMATION_RUNNER_BASE_URL=*) ENVFLAGS+=(-e "$line") ;;
  esac
done < <(docker inspect "$LIVE" --format '{{range .Config.Env}}{{println .}}{{end}}')

docker run -d --name "$NAME" --network "$NET" "${ENVFLAGS[@]}" -e APP_PORT=8080 -p "$PORT:8080" "$IMAGE" >/dev/null

echo "== 2/5 等待 health 就绪 =="
H=""
for _ in $(seq 1 60); do
  H="$(curl -s --max-time 2 "http://localhost:$PORT/api/system/health" || true)"
  echo "$H" | grep -q '"status":"UP"' && break
  sleep 2
done
echo "health: $H"
echo "$H" | grep -q '"status":"UP"' || { docker logs --tail 30 "$NAME" || true; fail "旧镜像未就绪"; }
echo "$H" | grep -q "$IMAGE_TAG" || fail "health 未回读旧 SHA（期望包含 ${IMAGE_TAG}）"

echo "== 3/5 演练后计数核对（应零变化）=="
AFTER="$(q "$CLAUSE")"
FLYWAY_AFTER="$(q 'select version from flyway_schema_history order by installed_rank desc limit 1')"
echo "after  产物/草稿点/运行/知识 = $AFTER ; flyway 最新 = $FLYWAY_AFTER"
[ "$BEFORE" = "$AFTER" ] || fail "计数不一致：$BEFORE → $AFTER"
[ "$FLYWAY_BEFORE" = "$FLYWAY_AFTER" ] || fail "flyway 历史被演练容器改动"

echo "== 4/5 历史数据可读抽样（只读）=="
q "select count(*) from ai_assistant_artifacts" >/dev/null || fail "产物表不可读"
echo "产物表可读 OK"

echo "== 5/5 回收演练容器 =="
docker rm -f "$NAME" >/dev/null
echo "PASS: 回滚演练通过——旧镜像（health 自证 ${IMAGE_TAG}）在现行库启动正常，数据零变化。"
