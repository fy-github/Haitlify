#!/bin/bash
# 同 tenant + 同 project、两个不同 actor 的真实模型纵链（B）。
# 用法: ./run-same-project.sh h2 | ./run-same-project.sh mysql
# 凭据只从 deploy/isolated/.env 读进环境变量，绝不回显、绝不写进日志/命令行。
set -uo pipefail

MODE="${1:-h2}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../../.." && pwd)"
PLATFORM="$ROOT/server/platform"
# isolated-inputs: canonicalize and validate every path before any write, copy or rm.
canonical_path() {
  local path="$1" parent leaf suffix resolved
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
case "$MODE" in h2|mysql) ;; *) echo "required mode: h2 or mysql" >&2; exit 2;; esac
for name in HAITLIFY_REAL_SAME_PROJECT_E2E_BASE_PROFILE HAITLIFY_REAL_SAME_PROJECT_E2E_EVIDENCE_DIR HAITLIFY_REAL_SAME_PROJECT_E2E_PYTHON HAITLIFY_REAL_SAME_PROJECT_E2E_AGENT_DIR; do
  require_path "$name"
done
BASE_PROFILE="$HAITLIFY_REAL_SAME_PROJECT_E2E_BASE_PROFILE"
EVID="$HAITLIFY_REAL_SAME_PROJECT_E2E_EVIDENCE_DIR"
PY="$HAITLIFY_REAL_SAME_PROJECT_E2E_PYTHON"
AGENT_DIR="$HAITLIFY_REAL_SAME_PROJECT_E2E_AGENT_DIR"
if [[ ! -f "$BASE_PROFILE/config.yaml" || ! -f "$BASE_PROFILE/.env" || ! -x "$PY" || ! -d "$AGENT_DIR" ]]; then
  echo "required valid profile, executable Python and agent directory" >&2; exit 2
fi
for source in "$BASE_PROFILE" "$AGENT_DIR" "$PY"; do
  if path_overlaps "$source" "$EVID"; then
    echo "evidence directory overlaps a protected input" >&2; exit 2
  fi
done
OWNERSHIP="$EVID/.haitlify-run-same-project-owned"
if [[ -e "$EVID" ]]; then
  [[ -d "$EVID" && -f "$OWNERSHIP" && "$(<"$OWNERSHIP")" == "haitlify-run-same-project-v1" ]] || {
    echo "evidence directory is not an owned retry root" >&2; exit 2
  }
else
  mkdir -p "$EVID" || exit 2
  printf '%s\n' 'haitlify-run-same-project-v1' > "$OWNERSHIP" || exit 2
fi
rm -rf "$EVID/units" "$EVID/workspace"
mkdir -p "$EVID/units"

export HAITLIFY_REAL_SAME_PROJECT_E2E=true
export HAITLIFY_REAL_SAME_PROJECT_E2E_BASE_PROFILE="$BASE_PROFILE"
export HAITLIFY_REAL_SAME_PROJECT_E2E_UNITS_ROOT="$EVID/units"
export HAITLIFY_REAL_SAME_PROJECT_E2E_EVIDENCE_DIR="$EVID"
export HAITLIFY_REAL_SAME_PROJECT_E2E_PYTHON="$PY"
export HAITLIFY_REAL_SAME_PROJECT_E2E_AGENT_DIR="$AGENT_DIR"

if [ "$MODE" = "mysql" ]; then
  export HAITLIFY_REAL_SAME_PROJECT_E2E_PORT=18084
  export HAITLIFY_REAL_SAME_PROJECT_E2E_DB_URL='jdbc:mysql://127.0.0.1:13306/haitlify_isolated?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8'
  export HAITLIFY_REAL_SAME_PROJECT_E2E_DB_USERNAME=haitlify_app
  DB_PASS=$(grep -E '^HAITLIFY_ISOLATED_DB_PASSWORD=' "$ROOT/deploy/isolated/.env" | head -1 | cut -d= -f2-)
  if [ -z "${DB_PASS:-}" ]; then echo "FATAL: deploy/isolated/.env 缺少 HAITLIFY_ISOLATED_DB_PASSWORD" >&2; exit 3; fi
  export HAITLIFY_REAL_SAME_PROJECT_E2E_DB_PASSWORD="$DB_PASS"
  unset DB_PASS
else
  export HAITLIFY_REAL_SAME_PROJECT_E2E_PORT=18083
  unset HAITLIFY_REAL_SAME_PROJECT_E2E_DB_URL HAITLIFY_REAL_SAME_PROJECT_E2E_DB_USERNAME \
        HAITLIFY_REAL_SAME_PROJECT_E2E_DB_PASSWORD 2>/dev/null || true
fi

echo "mode=$MODE port=$HAITLIFY_REAL_SAME_PROJECT_E2E_PORT evidence=$EVID"
for p in 18083 18084; do
  if lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then
    case "$p:$MODE" in 18083:h2|18084:mysql) echo "FATAL: port $p already in use" >&2; exit 4;; esac
  fi
done
echo "preflight: unit dirs before run: $(ls -1 "$EVID/units" 2>/dev/null | wc -l | tr -d ' ')"

cd "$PLATFORM" || exit 9
rm -rf target/surefire-reports
./mvnw -o -Dtest=RealModelSameProjectDualActorUnitPoolEndToEndTest -DfailIfNoTests=false test \
  > "$EVID/mvn-run.log" 2>&1
RC=$?
echo "mvn rc=$RC"
/usr/bin/python3 - "$MODE" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
tests = failures = errors = skipped = 0
for path in glob.glob("target/surefire-reports/TEST-*.xml"):
    root = ET.parse(path).getroot()
    if root.tag != "testsuite":
        continue
    tests += int(root.attrib.get("tests", 0))
    failures += int(root.attrib.get("failures", 0))
    errors += int(root.attrib.get("errors", 0))
    skipped += int(root.attrib.get("skipped", 0))
print(f"{sys.argv[1]}: tests={tests} failures={failures} errors={errors} skipped={skipped}")
PY
echo "evidence files:"
ls -1 "$EVID" | head -40
exit $RC
