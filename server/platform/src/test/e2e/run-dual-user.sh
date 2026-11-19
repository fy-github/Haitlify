#!/bin/bash
# 阳性对照（A 的另一半）：执行单元池打开，跑 RealModelDualUserUnitPoolEndToEndTest。
# 与 run-negative-control.sh 的唯一差别是 HAITLIFY_REAL_DUAL_USER_E2E_UNITS_ENABLED=true
# 且不提供 gateway-config-path / 临时 HERMES_HOME（单元化装配不需要它们）。
# 期望：通过。
set -uo pipefail

ROOT=/Users/lify/project/Haitlify
PLATFORM="$ROOT/server/platform"
EVID="$HOME/.hermes/cache/scratch/dual-user-e2e/positive-control"
BASE_PROFILE="$HOME/.hermes/profiles/haitlify-e2e"
PY="$HOME/.hermes/hermes-agent/venv/bin/python"
AGENT_DIR="$HOME/.hermes/hermes-agent"
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home

mkdir -p "$EVID"
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
python3 - <<'PY'
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
echo "stray gateway subprocesses (units): $(ps -Ao command | grep -F "$EVID/units" | grep -v grep | wc -l | tr -d ' ')"
exit $RC
