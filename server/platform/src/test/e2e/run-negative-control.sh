#!/bin/bash
# 负向对照（A）：把执行单元池关掉，跑**同一个**用例 RealModelDualUserUnitPoolEndToEndTest。
#
# 只切属性，不改用例断言：
#   app.assistant.conversation.execution-units.enabled = false   （= 装配退回 SingleGatewayUnits）
# 另外两个属性是「单进程形状能启动」的必要条件，仍然只指向临时目录，绝不指向真实的 ~/.hermes：
#   HERMES_HOME                                            = <证据目录>/neg-home
#   app.assistant.conversation.mcp-lease.gateway-config-path = <证据目录>/neg-home/config.yaml（基准 profile 的副本）
#
# 期望结果：**必然失败**。脚本退出码 0 = 如预期失败；5 = 意外通过（说明用例没有区分能力）。
set -uo pipefail

ROOT=/Users/lify/project/Haitlify
PLATFORM="$ROOT/server/platform"
BASE_PROFILE="$HOME/.hermes/profiles/haitlify-e2e"
EVID="$HOME/.hermes/cache/scratch/dual-user-e2e/negative-control"
NEG_HOME="$EVID/neg-home"
PY="$HOME/.hermes/hermes-agent/venv/bin/python"
AGENT_DIR="$HOME/.hermes/hermes-agent"
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home

mkdir -p "$EVID"
rm -rf "$EVID/units" "$EVID/workspace" "$NEG_HOME"
mkdir -p "$EVID/units" "$NEG_HOME"

# 临时 HERMES_HOME：基准 profile 的两个文件各复制一份（0600），网关子进程与平台写条目都只碰这份副本。
cp "$BASE_PROFILE/config.yaml" "$NEG_HOME/config.yaml"
cp "$BASE_PROFILE/.env" "$NEG_HOME/.env"
chmod 600 "$NEG_HOME/config.yaml" "$NEG_HOME/.env"
{
  echo "negHome=$NEG_HOME"
  echo "negHomeConfigSha256Before=$(shasum -a 256 "$NEG_HOME/config.yaml" | cut -d' ' -f1)"
  echo "negHomeUnderHermes=$([[ "$NEG_HOME" == "$HOME/.hermes/"* ]] && echo yes || echo no)"
  echo "realUserConfigTouched=no（平台只写 negHome 里的副本）"
} > "$EVID/neg-00-environment.txt"

export HERMES_HOME="$NEG_HOME"
export HAITLIFY_REAL_DUAL_USER_E2E=true
export HAITLIFY_REAL_DUAL_USER_E2E_UNITS_ENABLED=false
export HAITLIFY_REAL_DUAL_USER_E2E_GATEWAY_CONFIG_PATH="$NEG_HOME/config.yaml"
export HAITLIFY_REAL_DUAL_USER_E2E_BASE_PROFILE="$BASE_PROFILE"
export HAITLIFY_REAL_DUAL_USER_E2E_UNITS_ROOT="$EVID/units"
export HAITLIFY_REAL_DUAL_USER_E2E_EVIDENCE_DIR="$EVID"
export HAITLIFY_REAL_DUAL_USER_E2E_PYTHON="$PY"
export HAITLIFY_REAL_DUAL_USER_E2E_AGENT_DIR="$AGENT_DIR"
export HAITLIFY_REAL_DUAL_USER_E2E_PORT=18083

echo "mode=negative-control(execution-units.enabled=false) port=18083 evidence=$EVID"
if lsof -nP -iTCP:18083 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "FATAL: port 18083 already in use" >&2; exit 4
fi
echo "preflight: unit dirs before run: $(ls -1 "$EVID/units" 2>/dev/null | wc -l | tr -d ' ')"

cd "$PLATFORM" || exit 9
rm -rf target/surefire-reports
./mvnw -o -Dtest=RealModelDualUserUnitPoolEndToEndTest -DfailIfNoTests=false test \
  > "$EVID/mvn-run.log" 2>&1
RC=$?
echo "mvn rc=$RC"

# 失败点原文：surefire 报告里的 failure/error message + 前 80 行堆栈。
python3 - "$EVID" <<'PY'
import glob, sys, xml.etree.ElementTree as ET, html, os
evid = sys.argv[1]
out = []
totals = dict(tests=0, failures=0, errors=0, skipped=0)
for path in glob.glob("target/surefire-reports/TEST-*.xml"):
    root = ET.parse(path).getroot()
    if root.tag != "testsuite":
        continue
    for key in totals:
        totals[key] += int(root.attrib.get(key, 0))
    out.append(f"testsuite name={root.attrib.get('name')} tests={root.attrib.get('tests')} "
               f"failures={root.attrib.get('failures')} errors={root.attrib.get('errors')} "
               f"skipped={root.attrib.get('skipped')}")
    for case in root.iter("testcase"):
        for kind in ("failure", "error"):
            for node in case.findall(kind):
                out.append("")
                out.append(f"=== {kind.upper()} in {case.attrib.get('name')} ===")
                out.append(f"type={node.attrib.get('type')}")
                out.append(f"message={node.attrib.get('message')}")
                body = (node.text or "").strip().splitlines()
                out.extend(body[:60])
print("\n".join(out))
print("TOTALS", totals)
open(os.path.join(evid, "failure-point.txt"), "w").write("\n".join(out) + "\n")
PY

# 单进程形状下条目可能残留在副本里：先把 URL 脱敏再留证，然后删掉整个临时 home。
python3 - "$NEG_HOME" "$EVID" <<'PY'
import re, sys, os, hashlib
neg_home, evid = sys.argv[1], sys.argv[2]
path = os.path.join(neg_home, "config.yaml")
if os.path.isfile(path):
    text = open(path).read()
    redacted = re.sub(r"/mcp-lease/[A-Za-z0-9_-]{43}", "/mcp-lease/<redacted>", text)
    servers = [line for line in redacted.splitlines() if "mcp_servers" in line or "url:" in line]
    open(os.path.join(evid, "neg-05-neg-home-config-after.txt"), "w").write(
        "phase=after\nunit=<single gateway: neg-home>\n"
        f"leaseIdsInConfig={len(re.findall(r'/mcp-lease/<redacted>', redacted))}\n"
        + "interesting lines (redacted):\n" + "\n".join(servers) + "\n")
PY

rm -rf "$NEG_HOME" "$EVID/units" "$EVID/workspace"
echo "cleanup: negHomeRemoved=$([[ -e "$NEG_HOME" ]] && echo no || echo yes) unitsLeft=$(ls -1 "$EVID/units" 2>/dev/null | wc -l | tr -d ' ')"
echo "stray gateway subprocesses (neg-home): $(ps -Ao command | grep -F "$NEG_HOME" | grep -v grep | wc -l | tr -d ' ')"

if grep -q "^\[INFO\] BUILD SUCCESS" "$EVID/mvn-run.log"; then
  echo "UNEXPECTED-PASS: 池关掉后用例仍然通过 —— 该用例没有区分能力"
  exit 5
fi
echo "EXPECTED-FAILURE: 池关掉后用例失败（失败点见 $EVID/failure-point.txt）"
exit 0
