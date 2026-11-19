#!/bin/bash
# 阳性对照（A 的另一半）：执行单元池打开，跑 RealModelDualUserUnitPoolEndToEndTest。
# 与 run-negative-control.sh 的唯一差别是 HAITLIFY_REAL_DUAL_USER_E2E_UNITS_ENABLED=true
# 且不提供 gateway-config-path / 临时 HERMES_HOME（单元化装配不需要它们）。
# 期望：通过。
set -uo pipefail

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
for name in HAITLIFY_REAL_DUAL_USER_E2E_BASE_PROFILE HAITLIFY_REAL_DUAL_USER_E2E_EVIDENCE_DIR HAITLIFY_REAL_DUAL_USER_E2E_PYTHON HAITLIFY_REAL_DUAL_USER_E2E_AGENT_DIR; do
  require_path "$name"
done
BASE_PROFILE="$HAITLIFY_REAL_DUAL_USER_E2E_BASE_PROFILE"
EVID="$HAITLIFY_REAL_DUAL_USER_E2E_EVIDENCE_DIR"
PY="$HAITLIFY_REAL_DUAL_USER_E2E_PYTHON"
AGENT_DIR="$HAITLIFY_REAL_DUAL_USER_E2E_AGENT_DIR"
if [[ ! -f "$BASE_PROFILE/config.yaml" || ! -f "$BASE_PROFILE/.env" || ! -x "$PY" || ! -d "$AGENT_DIR" ]]; then
  echo "required valid profile, executable Python and agent directory" >&2; exit 2
fi
for source in "$BASE_PROFILE" "$AGENT_DIR" "$PY"; do
  if path_overlaps "$source" "$EVID"; then
    echo "evidence directory overlaps a protected input" >&2; exit 2
  fi
done
OWNERSHIP="$EVID/.haitlify-run-dual-user-owned"
if [[ -e "$EVID" ]]; then
  [[ -d "$EVID" && -f "$OWNERSHIP" && "$(<"$OWNERSHIP")" == "haitlify-run-dual-user-v1" ]] || {
    echo "evidence directory is not an owned retry root" >&2; exit 2;
  }
else
  mkdir -p "$EVID" || exit 2
  printf '%s\n' 'haitlify-run-dual-user-v1' > "$OWNERSHIP" || exit 2
fi
rm -rf "$EVID/units" "$EVID/workspace"
mkdir -p "$EVID/units"

export HAITLIFY_REAL_DUAL_USER_E2E=true
export HAITLIFY_REAL_DUAL_USER_E2E_UNITS_ENABLED=true
export HAITLIFY_REAL_DUAL_USER_E2E_BASE_PROFILE="$BASE_PROFILE"
export HAITLIFY_REAL_DUAL_USER_E2E_UNITS_ROOT="$EVID/units"
export HAITLIFY_REAL_DUAL_USER_E2E_EVIDENCE_DIR="$EVID"
export HAITLIFY_REAL_DUAL_USER_E2E_PYTHON="$PY"
export HAITLIFY_REAL_DUAL_USER_E2E_AGENT_DIR="$AGENT_DIR"
export HAITLIFY_REAL_DUAL_USER_E2E_PORT=18081
unset HAITLIFY_REAL_DUAL_USER_E2E_DB_URL HAITLIFY_REAL_DUAL_USER_E2E_DB_USERNAME \
      HAITLIFY_REAL_DUAL_USER_E2E_DB_PASSWORD HAITLIFY_REAL_DUAL_USER_E2E_GATEWAY_CONFIG_PATH 2>/dev/null || true

echo "mode=positive-control(execution-units.enabled=true) port=18081 evidence=$EVID"
if lsof -nP -iTCP:18081 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "FATAL: port 18081 already in use" >&2; exit 4
fi

cd "$PLATFORM" || exit 9
rm -rf target/surefire-reports
./mvnw -o -Dtest=RealModelDualUserUnitPoolEndToEndTest -DfailIfNoTests=false test \
  > "$EVID/mvn-run.log" 2>&1
RC=$?
echo "mvn rc=$RC"
/usr/bin/python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
totals = dict(tests=0, failures=0, errors=0, skipped=0)
for path in glob.glob("target/surefire-reports/TEST-*.xml"):
    root = ET.parse(path).getroot()
    if root.tag != "testsuite":
        continue
    for key in totals:
        totals[key] += int(root.attrib.get(key, 0))
print("positive-control:", totals)
PY
STRAY_UNITS=$(ps -Ao command | grep -F "$EVID/units" | grep -v grep | wc -l)
echo "stray gateway subprocesses (units): $STRAY_UNITS"
exit $RC
