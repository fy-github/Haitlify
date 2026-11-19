#!/usr/bin/env node
/**
 * Haitlify 隔离环境预检（计划 §7.1 A0-E 交付物，唯一受支持的启动入口的第一步）。
 *
 * 扫描范围（scan 模式，如实口径）：
 *   ① 配置/启动面（见 SCAN_SURFACES）内禁止旧资源标识：目录按磁盘实际内容遍历（含被 .gitignore 的文件，
 *      但不递归 node_modules/target/.git，这些会被记入 skipped）；
 *   ② 个人 Hermes home 路径的检查范围 = git 可枚举的文件（.gitignore 掉的路径不在内）；
 *   ③ 所有内容都按 BOM 识别的编码解码后扫描（UTF-8 / UTF-16LE / UTF-16BE），含 NUL 的内容再按
 *      latin1 与（偶数长度时）UTF-16LE 各扫一遍；非 UTF-8 与未读取/跳过的条目逐条列在 skipped 中；
 *   ④ 解析 compose 时启用全部 profile（--profile '*'），并要求栈定义里没有声明 profiles（原始文本层面）——
 *      否则未激活的 profile 服务会对预检完全隐身（第五轮复核实测）；
 *   ⑤ 栈定义禁止 env_file/include/extends 三类机制：判定前先把 compose 文件按「BOM/NUL 多解码 + YAML 双引号
 *      转义解码」归一成多个文本形态，再按键位匹配（第八轮复核实测：只看原始 utf8 文本会被转义键名与整份
 *      UTF-16 文件绕过；解析结果又会把 env_file 路径整条丢弃，因此还额外用允许清单兜住其效果——
 *      解析后的服务集、服务级键集与环境变量键集都必须落在 ALLOWED_* 常量内）。
 *
 * 用法：
 *   node scripts/preflight/environment-preflight.mjs check [options]
 *   node scripts/preflight/environment-preflight.mjs scan  [options]
 *   node scripts/preflight/environment-preflight.mjs inventory [options]
 *
 * check：用 `docker compose config` 解析隔离栈定义（该命令不访问 daemon），
 *        校验 project/container/volume/network/database 命名与挂载、卷 driver_opts、network_mode、
 *        发布端口、镜像固定、数据源主机/端口白名单、Spring 数据源覆盖键、必需凭据，
 *        产出绑定配置 hash 的预检结果文件；任何不匹配即失败。
 * scan ：① 在「配置/启动面」内禁止旧资源标识（范围见 SCAN_SURFACES；已登记待拆除的旧运维脚本、
 *        品牌元数据不在其中，由 A0 拆除清单跟踪）；
 *        ② 除 docs/ 历史归档外，仓库内任何文件禁止出现个人 Hermes home 路径。
 * inventory：记录 docker 资源清单（供启动前后对照），不修改任何资源。
 *
 * 退出码：0 = PASS，1 = FAIL（有不匹配），2 = BLOCKED（无法解析/无法验证）。
 *
 * 纪律：
 *   - 只读：脚本不启动、不停止、不删除任何容器/卷/网络/镜像；
 *   - 预检结果文件只写结构摘要与 hash；凭据值只参与“是否存在/是否占位”判断，不落盘、不打印；
 *     URL 内嵌凭据（user:pw@host）在输出与结果文件中一律遮蔽为 «redacted»@（有断言覆盖）；
 *   - hash 基于「脱敏后的解析结果」，因此路径与凭据变化不会伪造通过，结构性变更会使旧 preflight 失效；
 *   - 无法验证（缺 docker compose、缺 env 文件等）时返回 BLOCKED，不返回 PASS。
 */
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { closeSync, existsSync, mkdirSync, openSync, readdirSync, readFileSync, readSync, realpathSync, statSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = path.resolve(HERE, '..', '..')

const EXPECTED_PROJECT = 'haitlify-isolated'
const EXPECTED_IDENTIFIER_PREFIX = 'haitlify-isolated'
const EXPECTED_DB_NAME = 'haitlify_isolated'
// 允许的数据源目标（主机 → 允许端口）：只认 compose 服务名 mysql，或宿主侧隔离端口。
// 注意：`haitlify-isolated-mysql` 是容器名而不是服务名，容器名可被同网其它容器抢占，因此不在白名单内。
const ALLOWED_DB_TARGETS = new Map([
  ['mysql', [3306]],
  ['127.0.0.1', [13306]],
  ['localhost', [13306]],
])
// 数据源白名单里的主机名集合：container_name / 别名 / 服务名都不允许占用它们（mysql 服务自身除外）。
const DB_HOST_NAMES = new Set(['mysql', 'haitlify-isolated-mysql'])
// 服务名只允许与保留名/白名单主机名做大小写不敏感冲突检查（含 localhost —— 它也在数据源白名单里）。
const RESERVED_DB_HOST_NAMES = new Set([...ALLOWED_DB_TARGETS.keys(), ...DB_HOST_NAMES])
// 服务里任何形式的运行时/资源/命名空间控制声明都拒绝（隔离栈不需要；`read_only`/`tmpfs` 属加固，不禁）。
const FORBIDDEN_SERVICE_KEYS = [
  'privileged', 'cap_add', 'devices', 'security_opt', 'device_cgroup_rules',
  'pid', 'ipc', 'userns_mode', 'cgroup', 'uts', 'cgroup_parent', 'cgroupns_mode',
  'runtime', 'sysctls', 'group_add', 'oom_score_adj', 'storage_opt', 'blkio_config',
  'mac_address', 'use_api_socket', 'volumes_from', 'isolation',
  // 第七轮复核：设备/资源控制同族键（GPU、设备预留、实时调度、OOM、ulimits、deploy 段）此前全放行；
  // 第八轮复核补齐 cpus/cpu_quota/cpu_period/cpuset/mem_limit/mem_reservation/memswap_limit/pids_limit/shm_size。
  'gpus', 'deploy', 'cpu_rt_runtime', 'oom_kill_disable', 'ulimits', 'cpu_shares', 'mem_swappiness',
  'cpus', 'cpu_quota', 'cpu_period', 'cpuset', 'mem_limit', 'mem_reservation', 'memswap_limit',
  'pids_limit', 'shm_size',
]
const MYSQL_URL = /^jdbc:mysql:(?:[a-z]+:)?\/\/([^/]+)\/([^?;]+)/i
// 隔离栈的「声明面」允许清单（第八轮复核：机制被藏住时，其效果必须仍能现形）。
// 服务集合、服务级键集合与环境变量键集合都是这份栈定义的静态事实；栈定义变更时必须同步这里。
const EXPECTED_SERVICES = new Set(['mysql', 'platform', 'web-runner', 'web', 'hermes'])
const ALLOWED_SERVICE_KEYS = new Set([
  'image', 'build', 'container_name', 'restart', 'depends_on', 'environment',
  'command', 'ports', 'volumes', 'healthcheck', 'networks',
  // 加固类键：明确允许（文档与实现一致）。`cap_drop`/`security_opt`/`user` 由 runtime-hardening
  // 检查进一步约束取值（security_opt 只接受 no-new-privileges，user 不得为 root）。
  'read_only', 'tmpfs', 'cap_drop', 'security_opt', 'user',
])
// 构建子键允许清单：其余（tags/dockerfile_inline/privileged/network/ssh/extra_hosts/entitlements/
// cache_from/cache_to/ulimits/shm_size/labels/no_cache/target/…）一律拒绝。
// `build.args` 的**值**由 value-surface 检查覆盖。
const ALLOWED_BUILD_KEYS = new Set(['context', 'dockerfile', 'args'])
const ALLOWED_ENVIRONMENT_KEYS = new Map([
  ['mysql', new Set(['MYSQL_ROOT_PASSWORD', 'MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD'])],
  ['platform', new Set([
    'APP_PORT', 'APP_ENV_ID', 'APP_ENV_PREFLIGHT_HASH', 'APP_ENV_PREFLIGHT_RESULT', 'APP_DB_URL',
    'APP_DB_USERNAME', 'APP_DB_PASSWORD', 'APP_SECRET_KEY',
    'APP_WEB_AUTOMATION_RUNNER_ENABLED', 'APP_WEB_AUTOMATION_RUNNER_BASE_URL',
    // Hermes 执行单元（独立容器）：平台用 TCP JSON-RPC 连它，不拿 docker socket。
    'APP_ASSISTANT_HERMES_ENABLED', 'APP_ASSISTANT_HERMES_TRANSPORT',
    'APP_ASSISTANT_HERMES_HOST', 'APP_ASSISTANT_HERMES_PORT',
  ])],
  ['web-runner', new Set([
    'APP_WEB_AUTOMATION_RUNNER_PORT', 'APP_WEB_AUTOMATION_RUNNER_HOST',
    'APP_WEB_AUTOMATION_RUNNER_BROWSER_CHANNEL', 'APP_WEB_AUTOMATION_RUNNER_HEADLESS',
    'APP_WEB_AUTOMATION_RUNNER_STRICT_STARTUP', 'APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS',
    // 第十六轮复核：这两个键此前不在允许清单里 ⇒ 栈定义里既无法收紧（写它就被 env-file-sources 拒），
    // 又没有取值判据。现在只放行 BOOLEAN 那个（由 runner-egress-policy 强制为 false）。
    // 第十七轮复核纠正：…LOGIN_DISCOVERY_ALLOWED_HOSTS **不放行**——未设置（空列表）才是这个维度的最窄状态，
    // 任何写入都只会放宽（`*.internal`/`*.local`/私网字面量会短路登录发现的私网判定，实测），因此维持拒绝。
    'APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOW_PRIVATE_NETWORKS',
    'APP_WEB_AUTOMATION_RUNNER_PUBLIC_URL',
  ])],
  ['web', new Set([])],
  ['hermes', new Set([
    'HERMES_HOME', 'HERMES_BRIDGE_HOST', 'HERMES_BRIDGE_PORT', 'HERMES_GATEWAY_CMD',
  ])],
])
// 出站开关键（Runner 侧「未设置即开启」语义）：compose 与构建文件都要求它们被显式关闭，
// 构建文件里**只允许**写成恰好 false（写成别的取值或出现在非 ENV/ARG 行即拒绝）。
const EGRESS_KEYS = [
  'APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS',
  'APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOW_PRIVATE_NETWORKS',
]
// 构建文件里**任何位置**都不允许出现的键：登录发现允许主机白名单（写入只会放宽）与配置来源/JVM 参数键。
const BUILD_FORBIDDEN_ANYWHERE_KEYS = [
  'APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOSTS',
  'SPRING_APPLICATION_JSON',
  'SPRING_CONFIG_IMPORT',
  'SPRING_CONFIG_ADDITIONAL_LOCATION',
  'SPRING_CONFIG_ADDITIONALLOCATION',
  'SPRING_CONFIG_LOCATION',
  'JAVA_TOOL_OPTIONS',
  'JDK_JAVA_OPTIONS',
  '_JAVA_OPTIONS',
]

// 直接设置 Spring 数据源/Flyway 属性、或改写配置来源（import/location/application-json/JVM 参数）
// 都会绕开 APP_DB_URL 与隔离校验。
const DATASOURCE_OVERRIDE_KEY = /^spring[._]?(datasource|flyway)|^spring[._]?config[._]?(import|additional[._-]?location|location)|^spring[._]?application[._]?json|^(java[._]?tool[._]?options|jdk[._]?java[._]?options|_?java[._]?options)/i
// 旧栈宿主端点：不得出现在任何服务的环境变量里（例如把 Runner 指回旧端口）。
// 主机形态包含 loopback、host.docker.internal、IPv6 loopback 与任意 IPv4/网关地址（第五轮复核实测的漏报面）。
// 第十轮复核：主机名解析大小写不敏感（RFC 4343），因此这里必须用 i 标志（HOST.DOCKER.INTERNAL / LocalHost 曾绕过）。
const LEGACY_ENDPOINT = /(127\.0\.0\.1|localhost|0\.0\.0\.0|host\.docker\.internal|\[::1\]|(?:[a-zA-Z0-9-]+\.)+(?:internal|local)|\d{1,3}(?:\.\d{1,3}){3}):(3306|6379|9000|9001|8080|18080|19323|38080|38081|38091)\b/i
// 远程/非路径形态的构建上下文（buildx 的 git://、https://#ref、docker-image://）：不在仓库内也绕不过检查。
const REMOTE_CONTEXT = /^[a-z][a-z0-9+.-]*:\/\//i

/**
 * 判断 build.context / additional_contexts 的值是否为「非本地路径」形态。
 * 第六/七轮复核实测：只认 `scheme://` 会漏掉 `git@github.com:org/repo.git` 与 `github.com/org/repo.git#ref`
 * ——compose 保留原串（按远端处理）而 path.resolve 会把它当成仓库内相对路径。
 * 已存在且形态明确的本地路径（绝对路径、`./`/`../` 开头）不做远端猜测，交给 insideRepo/存在性检查
 * （第七轮复核：`./contexts/my.git` 这类合法本地目录曾被 `.git$` 规则误判）。
 */
function remoteContextReason(raw, composeDir) {
  const value = String(raw).trim()
  if (REMOTE_CONTEXT.test(value)) return '远程引用（scheme://）'
  if (path.isAbsolute(value) || value.startsWith('.')) return null
  if (existsSync(path.resolve(composeDir, value))) return null
  if (/\.git(?:#.*)?$/i.test(value)) return '疑似 git 仓库地址（无 scheme 的 .git 形态）'
  if (/^[^\s@/\\]+@[^\s:/\\]+:/.test(value)) return '疑似 scp 风格 git 地址（user@host:path）'
  if (/^[a-z0-9-]+(?:\.[a-z0-9-]+)+\//i.test(value)) return '疑似主机名开头的远端路径'
  return '既非绝对路径也不以 ./ 或 ../ 开头，且在仓库内不存在（无法证实是本地上下文）'
}

function hexGroupsToIpv4(text) {
  const parts = String(text).split(':').filter((part) => part !== '')
  if (parts.length !== 2 || parts.some((part) => !/^[0-9a-f]{1,4}$/.test(part))) return null
  const value = (parseInt(parts[0], 16) << 16) + parseInt(parts[1], 16)
  return [value >>> 24, (value >>> 16) & 255, (value >>> 8) & 255, value & 255].join('.')
}

/** 把 IPv6 的 `::` 缩写展开成 8 组（`a:b::c` 形式），失败返回 null。 */
function expandIpv6Groups(text) {
  const halves = String(text).split('::')
  if (halves.length > 2) return null
  const head = halves[0] ? halves[0].split(':') : []
  const tail = halves.length === 2 && halves[1] ? halves[1].split(':') : []
  const missing = 8 - head.length - tail.length
  if (halves.length === 2 && missing < 1) return null
  const groups = halves.length === 2 ? [...head, ...Array(missing).fill('0'), ...tail] : head
  if (groups.length !== 8 || groups.some((group) => !/^[0-9a-fA-F]{1,4}$/.test(group))) return null
  return groups.map((group) => parseInt(group, 16))
}

/** 末段是点分四段时先转成两个十六进制组，便于统一展开。 */
function foldDottedTail(inner) {
  const match = String(inner).match(/^(.*:)(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})$/)
  if (!match) return String(inner)
  const octets = match[2].split('.').map(Number)
  if (octets.some((n) => !Number.isInteger(n) || n < 0 || n > 255)) return String(inner)
  return `${match[1]}${((octets[0] << 8) | octets[1]).toString(16)}:${((octets[2] << 8) | octets[3]).toString(16)}`
}

/** 括号内 IPv6 若是 loopback/未指定/IPv4-mapped 的等价形式，返回可被旧端点正则匹配的宿主写法。 */
function bracketedHostEquivalence(inner) {
  const groups = expandIpv6Groups(foldDottedTail(inner))
  if (!groups) return null
  const zeroPrefix = groups.slice(0, 6).every((group) => group === 0)
  if (zeroPrefix) {
    if (groups[6] === 0 && groups[7] === 0) return '0.0.0.0'
    if (groups[6] === 0 && groups[7] === 1) return '127.0.0.1'
    return null
  }
  if (groups[0] === 0 && groups[1] === 0 && groups[2] === 0 && groups[3] === 0
      && groups[4] === 0 && groups[5] === 0xffff) {
    const octets = [(groups[6] >> 8) & 255, groups[6] & 255, (groups[7] >> 8) & 255, groups[7] & 255]
    if (octets[0] === 127) return '127.0.0.1'
    if (octets.every((octet) => octet === 0)) return '0.0.0.0'
    return octets.join('.')
  }
  return null
}

/** 把任意精度整数字符串按 2^32 取模（低 32 位），避免 double 舍入。 */
function low32(value) {
  return Number(BigInt(value) & 0xffffffffn)
}

/** 宿主位置上的十六进制/整数/短写写法（`0x7f000001`、`2130706433`、`127.1`、`0`）→ 点分四段；否则 null。 */
function canonicalHostForm(host) {
  const text = String(host)
  // 单 token 十六进制：按**字符串**取低 8 位十六进制再解析（严格的低 32 位截断，与 macOS libc 一致）。
  // 第二十轮复核：21 位以上 libc 仍按低 32 位截断接受，因此上限放到 32 位十六进制。
  if (/^0[xX][0-9a-fA-F]{1,128}$/.test(text)) {
    const hex = text.slice(2)
    const low = hex.length > 8 ? hex.slice(-8) : hex
    const value = parseInt(low, 16)
    return [value >>> 24, (value >>> 16) & 255, (value >>> 8) & 255, value & 255].join('.')
  }
  const parts = text.split('.')
  if (parts.length > 4) return null
  // 段宽：先把 token 收宽（≤12 位十六进制 / ≤20 位十进制），位置性宽度约束由下面按段数判定。
  // 第十八/十九轮复核实测：`1.2.65535`（2 段末段 24 位）、`0xc0.0xa80105`、`192.052000405`、
  // `1.0x000000ffffff`（12 位十六进制段）、`1.00000000000000000001`（20 位十进制段）都是 libc 合法宿主。
  if (parts.some((part) => !/^(?:0[xX][0-9a-fA-F]{1,64}|\d{1,128})$/.test(part))) return null
  // 点分段按 libc inet_aton 语义：段可十进制、八进制（前导 0）或十六进制（0x）。
  const numbers = parts.map((part) => {
    if (/^0[xX]/.test(part)) {
      const hex = part.slice(2)
      return parseInt(hex.length > 8 ? hex.slice(-8) : hex, 16)
    }
    if (/^0[0-7]+$/.test(part)) {
      // 前导 0 且全是 0–7 ⇒ 八进制；末段可超 32 位，按低 32 位取（第二十轮复核：13 位八进制 libc 也接受）。
      return low32(`0o${part}`)
    }
    // 纯十进制段：超过 2^32 时 libc 同样按低 32 位截断（`4294967296` = 0.0.0.0）。
    return /^\d{1,128}$/.test(part) ? low32(part) : Number(part)
  })
  if (numbers.some((n) => !Number.isSafeInteger(n) || n < 0)) return null
  if (parts.length === 1) {
    const value = numbers[0]
    if (value > 4294967295) return null
    return [value >>> 24, (value >>> 16) & 255, (value >>> 8) & 255, value & 255].join('.')
  }
  // inet_aton 的 2/3 段形式里**末段承载剩余字节**（2 段=24 位、3 段=16 位），
  // 因此 `0.0.300` 是 0.0.1.44、`0.0.0400` 是 0.0.1.0、`1.2.65535` 是 1.2.255.255。
  const width = parts.length === 2 ? 0xffffff : parts.length === 3 ? 0xffff : 255
  if (numbers.slice(0, -1).some((n) => n > 255)) return null
  if (numbers[numbers.length - 1] > width) return null
  const tail = numbers[numbers.length - 1]
  const bytes = parts.length === 2
    ? [(tail >>> 16) & 255, (tail >>> 8) & 255, tail & 255]
    : parts.length === 3
      ? [(tail >>> 8) & 255, tail & 255]
      : [tail]
  const expanded = parts.length === 4
    ? numbers
    : [...numbers.slice(0, -1), ...bytes]
  while (expanded.length < 4) expanded.push(0)
  return expanded.slice(0, 4).join('.')
}

/** 解码百分号编码（`%31%32%37` 客户端会当 `127`）：第九轮复核实测整段编码可绕过旧端点检查。 */
function decodePercentEncoding(text) {
  return String(text).replace(/(?:%[0-9a-fA-F]{2})+/g, (match) => {
    try {
      return decodeURIComponent(match)
    } catch {
      return match
    }
  })
}

const LOOSE_NAMED_HOST_RE = /(localhost|host\.docker\.internal|(?:[a-z0-9-]+\.)+(?:internal|local))/gi
// 数值宿主候选：**任意位置**的十六进制/整数/短写点分/混合十六进制 token，都先归一到点分四段再看是否 loopback。
// 第十四轮复核实测：按「-h/--host=/@ 前缀」收集会被 `/dev/tcp/3232235781/3306`、`nc … 3232235781 3306`、
// `--resolve api:3306:0xc0a80105`、`-h 192.168.1`、`0xc0.0xa8.0x01.0x05` 绕过，因此不再依赖前缀。
// 纯十进制且 ≤65535 的 token 视为「像端口」，不作宿主候选（避免把 `-P 3306` 里的端口当成主机）。
const LOOSE_NUMERIC_TOKEN_RE = /(?<![\w.])(\d{1,128}|0[xX][0-9a-fA-F]{1,128}|(?:0[xX][0-9a-fA-F]{1,64}|\d{1,128})(?:\.(?:0[xX][0-9a-fA-F]{1,64}|\d{1,128})){1,3})(?![\w.])/g
// 端口 token：十六进制（含前导零/超 4 位）、零填充十进制（任意位数）与 atoi 语义的尾缀形式（`3306.0`）
// 都按「客户端会读成什么」归一后再与旧栈端口集合比对（第十五轮复核实测这三类此前全部漏判）。
const LOOSE_PORT_TOKEN_RE = /(?<![\d.])(0[xX][0-9a-fA-F]{1,64}|\d{3,128}(?:\.\d+)?)(?![\w])/g

function collectLegacyPortTokens(source) {
  const tokens = []
  for (const match of String(source).matchAll(LOOSE_PORT_TOKEN_RE)) {
    const raw = match[1]
    // 与 atoi/strtol 同语义：十六进制整体解析；带尾缀的只取整数部分。
    const value = /^0[xX]/.test(raw) ? parseInt(raw.slice(2), 16) : Number(raw.split('.')[0])
    if (!Number.isSafeInteger(value) || value < 1 || value > 65535) continue
    if (LEGACY_HOST_PORTS.includes(value)) tokens.push(String(value))
  }
  return tokens
}

function collectNonLoopbackHostTokens(source) {
  const hosts = []
  for (const match of String(source).matchAll(LOOSE_NAMED_HOST_RE)) {
    const lowered = match[1].toLowerCase()
    if (lowered === 'localhost') continue
    hosts.push(match[1])
  }
  for (const match of String(source).matchAll(LOOSE_NUMERIC_TOKEN_RE)) {
    const token = match[1]
    // 单一十进制 token（无点、非十六进制）：落在 IPv4 整数可能区间才作宿主候选。
    // 前导零且全为 0–7 的按 libc 的**八进制**语义算（`037777777777` = 255.255.255.255）；
    // 含 8/9 的（口令里常见的 `…0123456789`）语义歧义、且会持续误报，不作宿主候选。
    let numericValue
    if (/^\d+$/.test(token)) {
      if (token.length > 1 && token.startsWith('0')) {
        if (!/^0[0-7]+$/.test(token)) continue // 含 8/9：语义歧义 + 口令误报面，不作宿主候选
        numericValue = low32(`0o${token}`)
      } else {
        // 十进制：超过 2^32 时 libc 也按低 32 位截断（`4294967296` = 0.0.0.0），因此统一取低 32 位。
        numericValue = low32(token)
      }
      if (numericValue <= 65535) continue // 截断后像端口（含 0.0.0.0 系）的不作宿主候选
    } else if (/^0[xX][0-9a-fA-F]+$/.test(token)) {
      const hex = token.slice(2)
      const low = hex.length > 8 ? hex.slice(-8) : hex
      numericValue = parseInt(low, 16)
      if (numericValue <= 65535) continue // 纯十六进制且值 ≤65535 的更像端口/掩码，不作宿主候选
    }
    const canonical = canonicalHostForm(token)
    if (!canonical) continue
    if (canonical === '0.0.0.0' || canonical.startsWith('127.')) continue
    hosts.push(`${token}（${canonical}）`)
  }
  return hosts
}

/**
 * 非冒号写法的端点匹配：同一值里同时出现**非 loopback 主机 token**（具名主机/点分四段/宿主位置上的
 * 十六进制与整数形式）与**旧栈端口 token**（十进制/零填充/十六进制）即算命中（fail closed 方向）。
 * loopback 在容器内指向容器自身、且本栈禁止 host/宿主网络模式，因此不作数。
 */
function looseLegacyEndpointMatch(text) {
  const source = String(text)
  const hosts = collectNonLoopbackHostTokens(source)
  if (hosts.length === 0) return null
  const ports = collectLegacyPortTokens(source)
  if (ports.length === 0) return null
  return `同一值里同时出现主机 ${hosts[0]} 与旧栈端口 ${ports[0]}（非冒号/带选项写法）`
}

/**
 * 归一化环境值里的端点写法：端口前导零（`:03306` 客户端会当 3306）、IPv6 的展开/mapped/loopback 形式
 * （`[0:0:0:0:0:0:0:1]`、`[0:0:0:0:0:ffff:7f00:1]`、`[::ffff:127.0.0.1]`）、十六进制/整数/短写宿主
 * （`0x7f000001`、`0x7f.0.0.1`、`2130706433`、`127.1`、`0`）与百分号编码宿主
 * ——第六～九轮复核实测这些等价写法都能绕过旧端点检查。
 */
function normalizeLegacyEndpointText(text) {
  let out = decodePercentEncoding(text)
  out = out.replace(/\[([0-9a-fA-F:.]+)\]/g, (match, inner) => bracketedHostEquivalence(inner) ?? match)
  out = out.replace(/:0+(?=\d)/g, ':')
  return out.replace(
    // 宿主 token：十六进制放宽到 ≤20 位（第十九轮复核实测 `0x100000000:19323` 因 >8 位不被归一，
  // 导致同为 0.0.0.0 的等价写法里只有它被豁免、与冒号形态口径不一致），点分段与 canonicalHostForm 的宽度对齐。
    /(^|[^\w.:-])(0[xX][0-9a-fA-F]{1,128}|(?:0[xX][0-9a-fA-F]{1,64}|\d{1,128})(?:\.(?:0[xX][0-9a-fA-F]{1,64}|\d{1,128})){0,3})(?=:\d{1,128}\b)/g,
    (match, prefix, host) => {
      const canonical = canonicalHostForm(host)
      return canonical ? `${prefix}${canonical}` : match
    },
  )
}

/** 递归遍历任意 JSON 值，对每个字符串值做检测（第九轮复核：healthcheck.test / build.args 等值面此前零检查）。 */
function walkStrings(value, path, visit) {
  if (typeof value === 'string') {
    visit(path, value)
    return
  }
  if (Array.isArray(value)) {
    value.forEach((entry, index) => walkStrings(entry, `${path}[${index}]`, visit))
    return
  }
  if (value && typeof value === 'object') {
    for (const [key, entry] of Object.entries(value)) {
      walkStrings(entry, path ? `${path}.${key}` : key, visit)
    }
  }
}

// 栈定义本身不允许出现的机制：env_file（宿主文件→容器环境变量，且解析结果会把路径丢弃）、
// include/extends（可从仓库外文件引入服务或继承定义）。第八轮复核实测的绕过根因是「原始文本 ≠ YAML 值空间」：
// ① 双引号键转义（`"\x65nv_file"` 在 YAML 里就是 env_file）；② 整份文件存成 UTF-16(带 BOM)；
// ③ flow 写法（`svc: {…, extends: {file: …}}`）里键不在行首。因此判定前先做「值空间归一」：
// 按 BOM/NUL 的多种解码各扫一遍（复用 decodingsForScan），再解掉 YAML 双引号转义，最后按「键位」匹配。
const FORBIDDEN_STACK_MECHANISMS = [
  { id: 'env_file', note: 'env_file 会把宿主文件内容注入容器环境变量，且解析结果会丢弃该路径' },
  { id: 'include', note: 'include 会从（可能是仓库外的）另一个 compose 文件引入服务' },
  { id: 'extends', note: 'extends 会从（可能是仓库外的）另一个 compose 文件继承服务定义' },
]
// 键位判定要容忍 YAML 的「键前缀」写法（锚点 `&name`、标签 `!!str`/`!myenv`/`%TAG` 句柄 `!e!k`，任意顺序可重复）
// 与显式键（`?`）——第九～十一轮复核实测这些写法都能让机制生效而躲过朴素的行首/flow 键位匹配；
// 显式键还可以「`? env_file` 与 `:` 分列两行」，因此额外匹配不带冒号的纯键名行。
// 注：别名当键（`*mech :`）在文本层无法还原出真实键名，由解析层 --no-env-resolution 视图负责。
const KEY_PREFIX_PATTERN = '(?:(?:[&*][^\\s:]+|!{1,3}[^\\s]*)\\s+)*'
const MECHANISM_KEY_POSITION = new RegExp(`(?:^\\s*|[{,]\\s*)${KEY_PREFIX_PATTERN}(?:\\?\\s*)?["']?(env_file|include|extends)["']?\\s*:`)
const MECHANISM_KEY_TOKEN = new RegExp(`(?:^\\s*|[{,]\\s*)${KEY_PREFIX_PATTERN}(?:\\?\\s*)?["']?(env_file|include|extends)["']?\\s*$`)
const PROFILES_KEY_POSITION = new RegExp(`(?:^\\s*|[{,]\\s*)${KEY_PREFIX_PATTERN}(?:\\?\\s*)?["']?profiles["']?\\s*:`)
// 词级判定：机制名还可以作为「取值」出现（`x-k: &k include` 这种锚点取值 + 服务里 `*k :` 当键的写法，
// 文本层用键位/前缀都看不出真实键名）。隔离栈定义里不允许出现这三个词本身——键位、取值、锚点皆算。
// 第十二轮复核实测：仅靠键位判定时，别名当键的 include/extends 可在两层都不可见（解析层视图从不表达这两类机制）。
const MECHANISM_ANY_WORD = /(?:^|[\s"'[{}:,])["']?(env_file|include|extends)["']?(?=[\s"'\]{}:,]|$)/

/** 解掉 YAML 双引号标量里的转义，使 `"\x65nv_file"` 与 `"env_file"` 在判定时等价。 */
function decodeYamlEscapes(text) {
  return String(text)
    .replace(/\\x([0-9a-fA-F]{2})/g, (match, hex) => String.fromCharCode(parseInt(hex, 16)))
    .replace(/\\u([0-9a-fA-F]{4})/g, (match, hex) => String.fromCharCode(parseInt(hex, 16)))
    .replace(/\\U([0-9a-fA-F]{8})/g, (match, hex) => String.fromCodePoint(parseInt(hex, 16)))
}

/** compose 文件所有可能的文本形态（BOM/NUL 解码 + 转义解码），用于机制判定。 */
function composeTextVariants(buffer) {
  const variants = new Set()
  for (const { text } of decodingsForScan(buffer)) {
    // 去掉 BOM 与 NUL：UTF-16 被按 latin1 解码时字符间会夹 NUL，逐字符夹 NUL 也要能匹配。
    variants.add(text)
    variants.add(text.replace(/\u0000/g, ''))
    variants.add(decodeYamlEscapes(text))
    variants.add(decodeYamlEscapes(text.replace(/\u0000/g, '')))
  }
  return [...variants]
}

/** 行切分：覆盖 CR/LF/NEL/LS/PS/VT/FF，且**不切分引号标量内部**（否则会把值文本当成键位命中，产生伪行边界误报）。 */
function splitComposeLines(text) {
  const source = String(text)
  const lines = []
  let current = ''
  let quote = null
  for (let index = 0; index < source.length; index += 1) {
    const char = source[index]
    if (quote) {
      current += char
      if (char === '\\' && quote === '"') {
        current += source[index + 1] ?? ''
        index += 1
        continue
      }
      if (char === quote) quote = null
      continue
    }
    if (char === '"' || char === "'") {
      quote = char
      current += char
      continue
    }
    if (/[\r\n\u0085\u2028\u2029\u000b\u000c]/.test(char)) {
      // CRLF 视为一个换行（否则会产生空行、并让 FAIL 文案里的行号在 CRLF 文件上偏移）。
      if (char === '\r' && source[index + 1] === '\n') index += 1
      lines.push(current)
      current = ''
      continue
    }
    current += char
  }
  lines.push(current)
  return lines
}

/**
 * 去掉注释部分：只有不在引号内的 ` #` 才视为行内注释起点。
 * 第九轮复核实测：`"…# env_file…"` 这类引号内 ` #` 会让朴素截断把机制键一并切掉（漏报）。
 */
function stripLineComment(line) {
  const trimmed = line.trim()
  if (!trimmed || trimmed.startsWith('#')) return ''
  let quote = null
  for (let index = 0; index < line.length; index += 1) {
    const char = line[index]
    if (quote) {
      if (char === quote) quote = null
      continue
    }
    if (char === '"' || char === "'") {
      quote = char
      continue
    }
    if (char === '#' && index > 0 && /\s/.test(line[index - 1])) return line.slice(0, index)
  }
  return line
}

// 旧栈身份（project/容器/卷/库名）：新环境配置面出现即失败。
const FORBIDDEN_TOKENS = ['cloudv4', 'ai-test-platform', 'ai_test_platform', 'aitest-platform']
// 旧栈宿主端口：新环境发布端口复用即视为可能劫持/连接旧资源。
const LEGACY_HOST_PORTS = [3306, 6379, 9000, 9001, 8080, 18080, 19323, 38080, 38081, 38091]
const REQUIRED_SECRET_VARS = [
  'HAITLIFY_ISOLATED_MYSQL_ROOT_PASSWORD',
  'HAITLIFY_ISOLATED_DB_PASSWORD',
  'HAITLIFY_ISOLATED_SECRET_KEY',
]
const PLACEHOLDER_VALUE = /(CHANGE_ME|CHANGE-ME|changeme|placeholder|example|TODO)/i
const SECRET_KEY = /(PASSWORD|SECRET|TOKEN|CREDENTIAL)/i
const PERSONAL_HOME_PATTERNS = [
  { id: 'hermes-home-tilde', re: /~\/\.hermes(\/|\b)/ },
  { id: 'hermes-home-env', re: /\$\{?HOME\}?\/\.hermes(\/|\b)/ },
  { id: 'hermes-home-abs', re: /\/(?:Users|home)\/[^/\s'"`]+\/\.hermes(\/|\b)/ },
]
// scan 模式的目标面：新环境配置、启动入口与 CI（历史归档 docs/**、旧运维脚本不在本检查范围，
// 其遗留引用由 A0 拆除依赖清单登记，见 docs/superpowers/a0/）。
const SCAN_SURFACES = [
  'deploy',
  'scripts/preflight',
  '.github/workflows',
  'server/platform/src/main/resources/application.yml',
  'server/platform/.env.example',
]

const DEFAULT_COMPOSE = 'deploy/isolated/compose.yaml'
const DEFAULT_ENV_FILE = 'deploy/isolated/.env'
// 状态目录放在 compose 文件同级，便于以只读方式挂进平台容器做「哈希 ↔ 预检结果」绑定校验。
const DEFAULT_OUT = 'deploy/isolated/.preflight/isolated-preflight.json'
// 允许的唯一宿主绑定挂载：预检结果的只读挂载（精确路径 + 精确目标 + read_only）。
const PREFLIGHT_RESULT_MOUNT_TARGET = '/app/preflight/isolated-preflight.json'
// 数据存储服务只允许官方 MySQL 8.4 镜像（可带 digest）——名字合法不代表背后的进程合法。
const DATABASE_IMAGE = /^mysql:8\.4(?:@sha256:[0-9a-f]{64})?$/
// mysql 服务允许的启动参数（只允许字符集/排序规则两个服务端开关）。
const ALLOWED_MYSQL_COMMAND = /^(?:--(?:character-set-server|collation-server)=\S+)(?:\s+--(?:character-set-server|collation-server)=\S+)*$/
const RESERVED_SERVICE_NAMES = new Set(['mysql', 'platform', 'web-runner', 'web', 'hermes', `${EXPECTED_IDENTIFIER_PREFIX}-mysql`])

function isReservedOrServiceName(value, services) {
  const name = String(value ?? '').toLowerCase()
  return RESERVED_SERVICE_NAMES.has(name) || Object.keys(services ?? {}).some((service) => service.toLowerCase() === name)
}
// 凭据内嵌在 URL 或裸 host:port 里时（user:pw@…），异常信息与结果文件都不得带出原值。
const URL_USERINFO = /(\b[a-z][a-z0-9+.-]*:\/\/)[^/@\s]+@/gi
const BARE_USERINFO = /([^\s@/]+)@(?=[^\s@/]*:\d+|\d{1,3}(?:\.\d{1,3}){3})/g

function redactCredentials(text) {
  return String(text ?? '')
    .replace(URL_USERINFO, '$1«redacted»@')
    .replace(BARE_USERINFO, '«redacted»@')
}

// 失败详情里会原样打印解析出的字段（command/extra_hosts/env 值等），
// 因此还要把「键名像凭据」的赋值统一遮蔽；只保留键名与分隔符。
const SECRET_ASSIGNMENT = /([A-Za-z0-9_]*(?:PASSWORD|PASSWD|PWD|SECRET|TOKEN|API_?KEY|PRIVATE_KEY|ACCESS_KEY)[A-Za-z0-9_]*)(["']?\s*[:=]\s*["']?)("([^"]*)"|'([^']*)'|[^\s,;}\]]+)/gi

function maskSecretValues(text) {
  return String(text ?? '').replace(SECRET_ASSIGNMENT, (match, key, separator) => `${key}${separator}«redacted»`)
}

function sanitizeDetail(text) {
  return maskSecretValues(redactCredentials(text))
}

function parseArgs(argv) {
  const [mode = 'check', ...rest] = argv
  const opts = {
    mode,
    composeFile: DEFAULT_COMPOSE,
    envFile: DEFAULT_ENV_FILE,
    out: DEFAULT_OUT,
    requireSecrets: false,
    hostChecks: true,
    allowMountPrefixes: [],
    expectHash: null,
    json: false,
  }
  for (let i = 0; i < rest.length; i += 1) {
    const arg = rest[i]
    const next = () => {
      i += 1
      if (i >= rest.length) throw new Error(`缺少参数值: ${arg}`)
      return rest[i]
    }
    if (arg === '--compose') opts.composeFile = next()
    else if (arg === '--env-file') opts.envFile = next()
    else if (arg === '--out') opts.out = next()
    else if (arg === '--allow-mount-prefix') opts.allowMountPrefixes.push(path.resolve(next()))
    else if (arg === '--expect-hash') opts.expectHash = next().toLowerCase()
    else if (arg === '--require-secrets') opts.requireSecrets = true
    else if (arg === '--no-host-checks') opts.hostChecks = false
    else if (arg === '--json') opts.json = true
    else throw new Error(`未知参数: ${arg}`)
  }
  return opts
}

function rel(p) {
  return path.relative(REPO_ROOT, path.resolve(p)) || '.'
}

function run(cmd, args, { allowFailure = false } = {}) {
  try {
    const stdout = execFileSync(cmd, args, { cwd: REPO_ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
    return { ok: true, stdout }
  } catch (error) {
    const detail = `${error.stdout || ''}${error.stderr || ''}`.trim() || error.message
    if (!allowFailure) return { ok: false, detail }
    return { ok: false, detail }
  }
}

function readEnvFile(envFile) {
  const absolute = path.resolve(REPO_ROOT, envFile)
  if (!existsSync(absolute)) return { ok: false, absolute }
  const values = {}
  for (const rawLine of readFileSync(absolute, 'utf8').split('\n')) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#')) continue
    const eq = line.indexOf('=')
    if (eq <= 0) continue
    values[line.slice(0, eq).trim()] = line.slice(eq + 1).trim()
  }
  return { ok: true, absolute, values }
}

function stableStringify(value) {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`
  if (value && typeof value === 'object') {
    const keys = Object.keys(value).sort()
    return `{${keys.map((k) => `${JSON.stringify(k)}:${stableStringify(value[k])}`).join(',')}}`
  }
  return JSON.stringify(value ?? null)
}

function redactResolved(resolved) {
  const clone = structuredClone(resolved)
  for (const service of Object.values(clone.services ?? {})) {
    const env = service.environment
    if (env && typeof env === 'object') {
      for (const [key, value] of Object.entries(env)) {
        if (SECRET_KEY.test(key) && value) env[key] = '«redacted»'
      }
    }
  }
  return clone
}

function resolveCompose(opts) {
  // --profile '*' 让所有 profile 门控的服务都进入解析结果：否则未激活 profile 的服务对预检完全隐身
  // （第五轮复核实测：profile 门控的 privileged/host 网络服务在默认解析里根本不出现）。
  const args = ['compose', '--profile', '*', '--env-file', path.resolve(REPO_ROOT, opts.envFile), '-f', path.resolve(REPO_ROOT, opts.composeFile), 'config', '--format', 'json']
  const result = run('docker', args)
  if (!result.ok) return { ok: false, detail: result.detail.replace(/\s+/g, ' ').slice(0, 600) }
  try {
    return { ok: true, resolved: JSON.parse(result.stdout) }
  } catch (error) {
    return { ok: false, detail: `无法解析 docker compose config 输出: ${error.message}` }
  }
}

/**
 * 第二视图：`config --no-env-resolution` 会把 `env_file` 的**路径**保留在解析结果里
 * （普通视图会把路径整条丢弃、只把内容内联进 environment）。第十一轮复核实测：该视图与文本层无关，
 * 别名当键、单 `!` 标签、%TAG 缩写、显式键、LS/NEL 行分隔、UTF-16 等写法都会被解析器归一后在这里现形，
 * extends/include 继承来的 env_file 也会出现在对应服务上。因此它是「机制是否被调用」的结构性证据。
 * 若运行器上的 compose 不支持该 flag（unknown flag）或解析失败 → 返回 BLOCKED（fail closed）。
 */
function resolveComposeWithoutEnvResolution(opts) {
  const args = ['compose', '--profile', '*', '--env-file', path.resolve(REPO_ROOT, opts.envFile), '-f', path.resolve(REPO_ROOT, opts.composeFile), 'config', '--no-env-resolution', '--format', 'json']
  const result = run('docker', args)
  if (!result.ok) {
    return { ok: false, unsupported: /unknown flag|unknown option/i.test(result.detail), detail: result.detail.replace(/\s+/g, ' ').slice(0, 400) }
  }
  try {
    return { ok: true, resolved: JSON.parse(result.stdout) }
  } catch (error) {
    return { ok: false, detail: `无法解析 docker compose config --no-env-resolution 输出: ${error.message}` }
  }
}

function parseJdbcUrl(url) {
  const match = MYSQL_URL.exec(url)
  if (!match) return null
  const targets = match[1]
    .split(',')
    .map((target) => target.trim().toLowerCase())
    .filter(Boolean)
  if (targets.length === 0) return null
  return { database: match[2], targets }
}

function splitTarget(target) {
  const index = target.lastIndexOf(':')
  if (index < 0) return { host: target, port: 3306 }
  return { host: target.slice(0, index), port: Number(target.slice(index + 1)) }
}

function jdbcProblems(serviceName, key, value) {
  const label = `${serviceName}.${key}`
  const parsed = parseJdbcUrl(value)
  if (parsed === null) {
    return [`${label} 无法解析为 jdbc:mysql 地址（fail closed）：${String(value).split(/[?;]/)[0]}`]
  }
  const problems = []
  if (parsed.database.toLowerCase() !== EXPECTED_DB_NAME) {
    problems.push(`${label} 目标库为 ${parsed.database}，期望 ${EXPECTED_DB_NAME}`)
  }
  for (const target of parsed.targets) {
    const { host, port } = splitTarget(target)
    const allowedPorts = ALLOWED_DB_TARGETS.get(host)
    if (!allowedPorts || !allowedPorts.includes(port)) {
      problems.push(`${label} 主机/端口 ${target} 不是隔离目标（允许 mysql:3306 / 127.0.0.1:13306 / localhost:13306；容器名不参与白名单）`)
    }
  }
  return problems
}

function publishedPorts(service) {
  const ports = []
  for (const entry of service.ports ?? []) {
    // 只接受单一整数端口；范围/表达式（"3300-3400"）会让旧端口与冲突检查失效，另行报错。
    if (entry && typeof entry === 'object' && entry.published != null && /^\d+$/.test(String(entry.published))) {
      ports.push(Number(entry.published))
    }
  }
  return ports
}

function publishedPortEntries(service) {
  const entries = []
  for (const entry of service.ports ?? []) {
    entries.push({
      published: entry && typeof entry === 'object' && entry.published != null ? String(entry.published).trim() : '',
      protocol: entry && typeof entry === 'object' && entry.protocol != null ? String(entry.protocol).trim().toLowerCase() : 'tcp',
      hostIp: entry && typeof entry === 'object' && entry.host_ip != null ? String(entry.host_ip).trim() : '',
      keys: entry && typeof entry === 'object' ? Object.keys(entry) : [],
    })
  }
  return entries
}

/** 词法+realpath 双重判定：候选路径是否落在仓库内（符号链接按 realpath）。 */
function insideRepo(candidate) {
  const absolute = path.resolve(String(candidate))
  let real = absolute
  try {
    real = realpathSync(absolute)
  } catch {
    // 不存在时退回词法路径（后续 existsSync 会单独报「无法证实」）。
  }
  const root = realpathSync(REPO_ROOT)
  return real === root || real.startsWith(`${root}${path.sep}`)
}

// 构建文本（Dockerfile 或入口脚本）的判定规则集：Dockerfile 与 COPY 进来的入口脚本共用同一套规则，
// 避免两处实现漂移（第二十二轮复核：入口脚本只吃到 3 条规则族，与文档「同一套规则」不符）。
function splitPhysicalLines(text) {
  return String(text).split(/\r\n|[\n\r\u0085\u2028\u2029\u000b\u000c]/)
}

/** 把物理行按 Dockerfile/buildkit 语义拼成逻辑行：反斜杠（后可有空白）在行尾即为续行。 */
function toLogicalLines(physicals) {
  const logical = []
  for (let index = 0; index < physicals.length; index += 1) {
    const entry = physicals[index]
    const physical = typeof entry === 'string' ? entry : entry.text
    const lineNo = typeof entry === 'string' ? index + 1 : entry.line
    const previous = logical[logical.length - 1]
    if (previous && /\\[ \t]*$/.test(previous.text)) {
      // buildkit 语义：去掉反斜杠与其后空白后**直接粘连**（不插分隔符）——
      // 插空格会让「键名跨续行拆开」的写法（`ENV KEY_PART` + `\` + `_REST=1`）在门里拼不回完整键名。
      previous.text = `${previous.text.replace(/\\[ \t]*$/, '')}${physical}`
    } else {
      logical.push({ text: physical, line: lineNo })
    }
  }
  return logical
}

/** 引号感知地剥行注释：引号内的 `#`/`//` 不是注释（第三十三轮 F5）。 */
function stripLineCommentQuoted(line, { slashes = false } = {}) {
  let quote = null
  for (let index = 0; index < line.length; index += 1) {
    const ch = line[index]
    if (quote) {
      if (ch === '\\') { index += 1; continue }
      if (ch === quote) quote = null
      continue
    }
    if (ch === '"' || ch === "'") { quote = ch; continue }
    if (ch === '#' && (index === 0 || /\s/.test(line[index - 1]))) return line.slice(0, index)
    if (slashes && ch === '/' && line[index + 1] === '/') return line.slice(0, index)
  }
  return line
}

/** 去掉引号包裹的片段（用于「重定向字符」这类可能出现在程序文本里的判定）。 */
function stripQuoted(text) {
  return String(text).replace(/"[^"]*"/g, ' ').replace(/'[^']*'/g, ' ')
}

/**
 * 对一段构建文本给出违规描述列表。
 * @param {string} text 文本内容（已解码）
 * @param {{ dockerfileSyntax?: boolean }} options dockerfileSyntax=true 时额外判定 ENV/ARG 键位
 */
/** 引号外、且只在 RUN/COPY/ADD 指令上的 `<<` 才算 heredoc 定界符（返回 { tag, chomp }）。 */
/** 一行上的**所有** heredoc 定界符（buildkit 支持 `COPY <<A <<B /dest`）。 */
function heredocBodyDelimiters(raw) {
  const trimmed = raw.trim()
  if (!/^(?:RUN|COPY|ADD)\b/i.test(trimmed)) return []
  const found = []
  let quote = null
  for (let index = 0; index < raw.length; index += 1) {
    const ch = raw[index]
    if (quote) {
      if (ch === '\\') {
        index += 1
        continue
      }
      if (ch === quote) quote = null
      continue
    }
    if (ch === '"' || ch === "'") {
      quote = ch
      continue
    }
    if (ch === '<' && raw[index + 1] === '<') {
      const match = raw.slice(index + 2).match(/^(?:-([^\s<]+)|'([^'<]*)'|"([^"<]*)"|([^\s<]+))/)
      if (match) {
        // `<<-'E'`：chomp 形式后面也可能带引号（buildkit 接受），引号不属于标签。
        const tag = (match[1] ?? match[2] ?? match[3] ?? match[4] ?? '').replace(/^['"]|['"]$/g, '')
        // 单独的 `-`（`<<- /x` 这种带空白的写法）在 buildkit 里不是 heredoc ⇒ 不登记。
        if (tag && tag !== '-') found.push({ tag, chomp: match[1] !== undefined })
      }
      index += 1
    }
  }
  return found
}

function heredocBodyDelimiter(raw) {
  const trimmed = raw.trim()
  if (!/^(?:RUN|COPY|ADD)\b/i.test(trimmed)) return null
  let quote = null
  for (let index = 0; index < raw.length; index += 1) {
    const ch = raw[index]
    if (quote) {
      if (ch === '\\') {
        index += 1
        continue
      }
      if (ch === quote) quote = null
      continue
    }
    if (ch === '"' || ch === "'") {
      quote = ch
      continue
    }
    if (ch === '<' && raw[index + 1] === '<') {
      // buildkit 按词法切分：定界符必须与 `<<` 在**同一个词**里（`<<-EOF` 合法，`<<- EOF` 不是 heredoc）。
      const match = raw.slice(index + 2).match(/^(-?)(?:'([^'<]*)'|"([^"<]*)"|([^\s<]+))/)
      if (!match) return null
      const tag = match[2] ?? match[3] ?? match[4]
      if (!tag) return null
      return { tag, chomp: match[1] === '-' }
    }
  }
  return null
}

function buildTextViolations(text, options = {}) {
  const violations = new Set()
  const rawPhysicals = splitPhysicalLines(text)
  // 跳过 heredoc 体（COPY/ADD/RUN 的 heredoc 体是**数据**：写 HTML/XML/含 `<<` 的文本不该被当指令或脚本审）。
  const physicals = []
  const heredocQueue = []
  const heredocBodies = new Map()
  // 续行必须先拼完再判定 heredoc：`COPY \\` 换行 `<<E /app/x` 的定界符只在拼好的逻辑行上可见（第三十一轮复核 FO-2）。
  let continuation = null
  for (let lineIndex = 0; lineIndex < rawPhysicals.length; lineIndex += 1) {
    const raw = rawPhysicals[lineIndex]
    if (continuation !== null) {
      continuation.text = `${continuation.text.replace(/\\[ \t]*$/, '')}${raw}`
      if (!/\\[ \t]*$/.test(raw)) {
        if (!raw.trim().startsWith('#')) {
          const delimiters = heredocBodyDelimiters(continuation.text)
          if (delimiters.length > 0) heredocQueue.push(...delimiters)
        }
        physicals.push({ text: continuation.text, line: continuation.line })
        continuation = null
      }
      continue
    }
    if (heredocQueue.length > 0) {
      const current = heredocQueue[0]
      const candidate = current.chomp ? raw.replace(/^\t+/, '') : raw
      if (candidate === current.tag) {
        heredocQueue.shift()
        continue
      }
      const list = heredocBodies.get(current.tag) ?? []
      list.push(raw)
      heredocBodies.set(current.tag, list)
      continue
    }
    if (/\\[ \t]*$/.test(raw) && !raw.trim().startsWith('#')) {
      continuation = { text: raw, line: lineIndex + 1 }
      continue
    }
    const delimiters = heredocBodyDelimiters(raw)
    if (delimiters.length > 0) heredocQueue.push(...delimiters)
    physicals.push({ text: raw, line: lineIndex + 1 })
  }
  if (continuation !== null) physicals.push({ text: continuation.text, line: continuation.line })
  if (heredocQueue.length > 0) {
    violations.add(`heredoc 缺少结束标签 ${heredocQueue.map((item) => item.tag).join('、')}（构建会失败：unterminated heredoc）`)
  }
  const logical = toLogicalLines(physicals)
  const shellishOf = (body) => {
    // 归一化（顺序重要）：先在**引号仍完整**的原文上把 `-c '…'` / `"-c","…"` 视作命令边界换成 ` ; `，
    // 再把引号/逗号/方括号换成空格。这样 `sh -c 'set -a; . /x'` 里的命令词能识别，
    // 而 `grep -c source f`、`tar -c . f` 这类「-c 之后不是引号」的普通选项不会造出假边界（第二十二轮复核的过拦面）。
    const normalized = body
      .replace(/["']\s*-c\s*["']|(?:^|\s)-c\s*["']/g, ' ; ')
      .replace(/[\[\]{},"']/g, ' ')
    return ` ${normalized.trim()} `
  }
  // ③ 键名字面判定：注释/说明里的「提及」不算命中，只有**真正的键名赋值**（`KEY=…`、`-DKEY=…`）或
  // ENV/ARG 的键位（含 space 形式）才算——与 DEPLOYMENT ③ 的措辞一致（第二十三轮复核纠正）。
  const keyAssigned = (key) => {
    const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const assignRe = new RegExp(`(^|[^A-Za-z0-9_])${escaped}\\s*=`)
    const assignDashRe = new RegExp(`(^|[\\s"'\\[=-])-[Dd]${escaped}\\s*=`)
    return logical.some(({ text: line }) => {
      // 注释行/行尾注释里的 `KEY=` 只是提及，不是赋值（第三十二轮 OB-e）。
      const codeLine = stripLineCommentQuoted(line, { slashes: options.appModule })
      if (options.dockerfileSyntax) {
        const envMatch = line.match(/^\s*(?:ENV|ARG)\s+(.+)$/i)
        if (envMatch) {
          const payload = envMatch[1].trim()
          const firstWord = payload.split(/\s+/)[0] ?? ''
          if (firstWord === key) return true
          if (firstWord.includes('=')) return new RegExp(`(^|[\\s"'\\[,])${escaped}\\s*=`).test(payload)
          return false
        }
      }
      return assignRe.test(codeLine) || assignDashRe.test(codeLine)
    })
  }
  for (const key of BUILD_FORBIDDEN_ANYWHERE_KEYS) {
    if (keyAssigned(key)) violations.add(`出现禁写键 ${key}`)
  }
  for (const { text: line, line: lineNo } of logical) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#')) continue
    // 只有 Dockerfile 语法下才把行首词当指令名剥掉：脚本模式（入口脚本）里 `ENV=/app/x`、`ADD=x` 这类
    // POSIX 启动文件变量赋值必须原样判定（第二十三轮复核：否则 `ENV=` 禁令被指令词剥离吃掉）。
    const isDockerInstruction = options.dockerfileSyntax
      && /^(?:RUN|CMD|ENTRYPOINT|ENV|ARG|COPY|ADD|LABEL|WORKDIR|VOLUME|EXPOSE|USER|SHELL|HEALTHCHECK|ONBUILD|STOPSIGNAL)\b/i.test(trimmed)
    const body = isDockerInstruction ? trimmed.replace(/^\s*[A-Za-z]+\b/i, ' ') : line
    const bodyForStructure = options.stripQuotesForRedirection === false ? body : stripQuoted(body)
    const label = `第 ${lineNo} 行 ${trimmed.slice(0, 60)}…`
    // ENV/ARG 键位（仅 Dockerfile 语法）
    if (options.dockerfileSyntax) {
      const envMatch = trimmed.match(/^(?:ENV|ARG)\s+(.+)$/i)
      if (envMatch) {
        const payload = envMatch[1]
        const pairs = []
        const firstWord = payload.trim().split(/\s+/)[0] ?? ''
        if (firstWord.includes('=')) {
          const pairPattern = /([A-Za-z_][A-Za-z0-9_.]*)\s*=\s*("([^"]*)"|'([^']*)'|(\S+))?/g
          for (const match of payload.matchAll(pairPattern)) {
            pairs.push([match[1], (match[3] ?? match[4] ?? match[5] ?? '').trim()])
          }
          // `KEY=value extra`：多出来的无 `=` 词同样会让 buildkit 的 parseNameVal 报错（N5）。
          const leftover = payload.replace(pairPattern, ' ').trim()
          if (leftover) violations.add(`第 ${lineNo} 行 ENV/ARG 含无 = 的多余词（${leftover.slice(0, 40)}）：buildkit 会拒绝该构建`)
        } else {
          // buildkit 语义：首词不含 `=` ⇒ 旧格式「键=首词、值=整行剩余」（值原样保留，含引号）。
          const rest = payload.trim().slice(firstWord.length).trim()
          // `ENV <key> <value>` 的旧格式里，**整段剩余都是值**（含空格与引号）——这是 Docker 的合法写法，
          // 不能判失败（第三十五轮 OB-1 更正了本轮早先的错误规则）。
          pairs.push([firstWord, rest])
        }
        for (const [key, value] of pairs) {
          if (EGRESS_KEYS.includes(key)) {
            if (value !== 'false') violations.add(`第 ${lineNo} 行 ENV/ARG 把出站开关 ${key} 写成 ${value || '(空)'}：只允许恰好 false`)
          } else if (key === 'APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOSTS') {
            violations.add(`第 ${lineNo} 行 ENV/ARG 写入 ${key}（该值只会放宽登录发现的私网判定）`)
          } else if (DATASOURCE_OVERRIDE_KEY.test(key)) {
            violations.add(`第 ${lineNo} 行 ENV/ARG 写入 ${key}（数据源/配置来源覆盖键）`)
          }
        }
      }
    }
    // 旧栈端点（冒号与非冒号/等价宿主写法）
    const endpointHit = line.match(LEGACY_ENDPOINT) ?? normalizeLegacyEndpointText(line).match(LEGACY_ENDPOINT)
    if (endpointHit) violations.add(`第 ${lineNo} 行出现旧栈宿主端点（${endpointHit[0]}）`)
    const looseHit = looseLegacyEndpointMatch(line) ?? looseLegacyEndpointMatch(normalizeLegacyEndpointText(line))
    if (looseHit && !endpointHit) violations.add(`第 ${lineNo} 行出现非冒号写法的旧栈宿主端点（${looseHit}）`)
    // 结构化禁令（仅命令类指令与脚本行）
    // SHELL 决定 shell 形式 CMD/ENTRYPOINT 的启动 shell，HEALTHCHECK 也会在容器里执行 ⇒ 都要过结构化禁令。
    const commandish = options.appModule
      ? false // 应用模块（.mjs/.js/.py…）不套 shell 规则：`if (a < b)`、`1 << 3`、`const ENV=` 都不是加载通道
      : (!isDockerInstruction || /^(?:RUN|CMD|ENTRYPOINT|ENV|SHELL|HEALTHCHECK)\b/i.test(trimmed))
    if (!commandish) {
      // 应用模块只判「禁写键名」这一类（出站开关/白名单键/配置来源键被赋值即拒）——它们与环境解释器无关。
      if (options.appModule) {
        // 应用模块：禁写键名（出站开关/白名单键/配置来源与 JVM 键）被**赋值**即拒——
        // 下标形态（`os.environ['KEY']='…'`）、JSON 形态（`"KEY": …`）、普通赋值（`KEY = …`）都算（第三十轮复核）。
        // 注释里的**提及**不算命中（`// note: KEY: not used`）——先剥掉行注释再判（第三十一轮复核 OB-4）。
        const code = stripLineCommentQuoted(body, { slashes: true })
        for (const key of EGRESS_KEYS) {
          if (code.includes(key)) violations.add(`${label} 在非 ENV/ARG 位置出现出站开关 ${key}`)
        }
        for (const key of BUILD_FORBIDDEN_ANYWHERE_KEYS) {
          const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
          if (new RegExp(`${escaped}["'\\]]*\\s*[:=]\\s*\\S`).test(code)) {
            violations.add(`${label} 在非 ENV/ARG 位置出现禁写键 ${key}`)
          }
        }
      }
      continue
    }
    const shellish = shellishOf(body)
    if (/\$\(|`/.test(body)) violations.add(`${label} 用了命令替换（$()/反引号）：可把文件内容读进命令或环境`)
    if (/<</.test(bodyForStructure) || /<\s*[^\s<=(\d]/.test(bodyForStructure) || /<\s*\(/.test(bodyForStructure)) {
      violations.add(`${label} 用了输入重定向/heredoc/进程替换：可从文件读入内容`)
    }
    if (/--env-file\b/i.test(body)) violations.add(`${label} 使用 --env-file 加载环境文件`)
    if (/(?:^|[\s"'\[=])-D(?:spring|java)[.\w]*=/i.test(body) || /--spring[.\w]*=/i.test(body)) {
      violations.add(`${label} 用 JVM/Spring 命令行参数覆盖配置（-Dspring.* / --spring.*）`)
    }
    const shellTrim = shellish.trim()
    if (/(?:^|[;&|(])\s*set\s+-[a-zA-Z]*a[a-zA-Z]*\b/.test(shellish)
      || /^set\s+-[a-zA-Z]*a[a-zA-Z]*\b/.test(shellTrim)
      || /(?:^|[;&|(]|\s)set\s+-o\s+allexport\b/.test(shellish)) {
      violations.add(`${label} 用 set -a / allexport 导出环境`)
    }
    if (/(?:^|[;&|(])\s*(?:source\b|\.)\s+\S/.test(shellish) || /^\s*(?:source\b|\.)\s+\S/.test(shellish.trim())) {
      violations.add(`${label} 用 source 或 . 加载外部文件`)
    }
    if (/\b(?:node|nodejs)\b[^&|]*?(?:--require\b|-r\b|--import\b|--loader\b|--experimental-loader\b)/i.test(body)) {
      violations.add(`${label} 用 node 预加载标志（-r/--require/--import/--loader）`)
    }
    if (/NODE_OPTIONS/i.test(body) && /--require\b|-r\b|--import\b|--loader\b/i.test(body)) {
      violations.add(`${label} 在 NODE_OPTIONS 里用预加载标志`)
    }
    if (/\bBASH_ENV\s*=/.test(body) || /(?:^|[\s"'\[,])ENV=/.test(body)) violations.add(`${label} 设置 shell 启动文件变量（BASH_ENV/ENV）`)
    if (/(?:^|[;&|(]\s*|\s)(?:npx\s+)?dotenv\b/i.test(shellish)) violations.add(`${label} 使用 dotenv 加载环境文件`)
    for (const key of EGRESS_KEYS) {
      if (!/^\s*(?:ENV|ARG)\b/i.test(trimmed) && body.includes(key)) {
        violations.add(`${label} 在非 ENV/ARG 位置出现出站开关 ${key}`)
      }
    }
  }
  return [...violations]
}

/**
 * 收集「被 ENTRYPOINT/CMD **直接执行**的脚本」并判定其内容是否可见（第二十六轮复核后重写关键部分）。
 *
 * 判定模型：
 *  - **只分析最终构建阶段**（最后一个 FROM 之后）的 ENTRYPOINT/CMD/RUN/COPY/ADD/WORKDIR——
 *    前面阶段的 CMD 不运行、前面阶段 COPY 的文件也不在最终镜像里（否则会把「不是将运行的那份」当成可见）。
 *  - **命令词判定**：把指令尾巴摊平成词序列（JSON 数组按数组展开、每个字符串再按空白切词、去掉包裹引号），
 *    跳过选项/`exec`/shell 词；`cd <目录>` 的实参不算入口；含 `$` 的词**只有在命令位**（`exec` 之后、或整条命令的首词）才算「变量间接 ⇒ 不可见」。
 *  - **直接执行**：命令位上的词若是路径（整 token，含 `=` 的不算）或脚本名（`*.sh` 等）即作为入口目标；
 *    解释器调用（`node x.mjs`、`java -jar …`）不按脚本审；`env`（含 `/usr/bin/env` 等绝对形态）按 shell 家族处理。
 *  - **不可见 ⇒ fail-closed**：目标落在构建写入面（最终阶段的 COPY/ADD dest 或 WORKDIR 之下、或被某条 RUN 提到过）
 *    或不在已知基础镜像位置（`/bin|/sbin|/usr|/lib*|/etc|/var|/opt|/run|/docker-entrypoint*`）⇒ FAIL；其余视为基础镜像自带文件。
 *    目标先 `posix.normalize`（`..` 穿越会离开 dest 前缀 ⇒ 不再算「在 dest 之下」）。
 *  - **来源解析**：目录源按上下文真实类型展开（含 `COPY . dest` 与 `COPY dir dest`），文件源按 basename 落位，
 *    `--parents` 保留源路径结构（文件源=源路径本身、目录源=目标相对路径），glob 支持 `*`/`?`/`[abc]`；
 *    `--from=`、远程 URL、源不存在或指向仓库外 ⇒ 不可见；解析出的候选必须 `insideRepo`。
 */
// 归档判定按**内容**而不是文件名：Docker 的 ADD 对本地源做 tar 嗅探，`ADD rootfs /` 也能解包（第三十六轮 F-2）。
function looksLikeArchive(absolutePath) {
  let fd
  try {
    fd = openSync(absolutePath, 'r')
    const head = Buffer.alloc(512)
    const read = readSync(fd, head, 0, 512, 0)
    if (read >= 2 && head[0] === 0x1f && head[1] === 0x8b) return true            // gzip
    if (read >= 4 && head[0] === 0x28 && head[1] === 0xb5 && head[2] === 0x2f && head[3] === 0xfd) return true // zstd
    if (read >= 6 && head[0] === 0xfd && head.toString('latin1', 1, 6) === '7zXZ\u0000') return true          // xz
    if (read >= 3 && head.toString('latin1', 0, 3) === 'BZh') return true          // bzip2
    if (read >= 262 && head.toString('latin1', 257, 262) === 'ustar') {
      // ustar magic 不等于合法 tar：校验和字段能解析时要求它匹配（否则 Docker 的 Go tar reader 会拒绝）。
      const field = head.toString('latin1', 148, 156).replace(/\0.*$/, '').trim()
      if (!/^[0-7]+$/.test(field)) return true
      const expected = parseInt(field, 8)
      let unsigned = 0
      let signedSum = 0
      for (let index = 0; index < 512; index += 1) {
        const byte = (index >= 148 && index < 156) ? 0x20 : head[index]
        unsigned += byte
        signedSum += byte > 127 ? byte - 256 : byte
      }
      return unsigned === expected || signedSum === expected
    }
    // V7 tar 没有 magic：校验和字段（148..155，八进制，求和时按空格计）能识别（N-4）。
    if (read >= 512) {
      const checksumField = head.toString('latin1', 148, 156).replace(/\0.*$/, '').trim()
      if (/^[0-7]+$/.test(checksumField)) {
        const expected = parseInt(checksumField, 8)
        let unsigned = 0
        let signed = 0
        for (let index = 0; index < 512; index += 1) {
          const byte = (index >= 148 && index < 156) ? 0x20 : head[index]
          unsigned += byte
          signed += byte > 127 ? byte - 256 : byte
        }
        // Go/bsdtar 同时接受无符号与有符号求和（UTF-8 文件名常触发后者，第三十八轮 N-4）。
        if (unsigned === expected || signed === expected) return true
      }
    }
    return false
  } catch {
    return false
  } finally {
    if (fd !== undefined) closeSync(fd)
  }
}

function collectCopiedEntrypoints(dockerfileText, contextDir, composeBuildArgs = {}) {
  const physicals = splitPhysicalLines(dockerfileText)
  const RUN_WRITE_VERB = /\b(?:cp|install|mv|ln|tee|dd|bsdtar|gtar|tar|unzip|7z|curl|wget|git|rsync|scp|sed)\b/
  // RUN 的写入按**简单命令段**判定：`curl … | sh` 的 `sh` 是解释器不是写入（第四十一轮 OB-2/FO-6）。
  // 引号内的 `|` / `;` 不是分隔符（第四十二轮 F8）：先用占位符替换引号内容，切段后再还原。
  // shell 层归一：去引号/反斜杠，`[s]h` 这类单字符类还原成 `sh`（第四十四轮 FO-1）。
  const normalizeRunText = (text) => text
    .replace(/\\([^\n])/g, '$1')
    .replace(/['"]/g, '')
    // `[s]h` 与 `[]s]h`（字符类里第一个字符是 `]`）都要还原成 `sh`（第四十五轮 8）。
    .replace(/\[(\]?[^\]]*)\]/g, (match, body) => body.replace(/^\]/, ''))
  // 解包目录的各种写法：`-C /x`、`-C/x`、`--directory=/x`、`--directory /x`、`-xC /x`、`-d/x`、`-o/x`（第四十五轮 2/3）。
  const extractDirOf = (line, expand = (value) => value) => {
    const normalized = normalizeRunText(line)
    const match = normalized.match(/(?:^|\s)(?:--directory|-[A-Za-z]*C|-d|-o)(?:=|\s*)([^\s'"]+)/)
    if (!match) return null
    const value = expand(match[1].replace(/^['"]|['"]$/g, ''))
    if (!value || value === '-') return null
    return value
  }
  // RUN 里的 `$VAR`（同一 Dockerfile 的 ENV/ARG）也要展开后再判写入面（第四十五轮 6）。
  const expandRunVars = (text) => text.replace(/\$\{?([A-Za-z_][A-Za-z0-9_]*)\}?/g, (match, name) => buildVars.get(name) ?? match)
  const runSegments = (line) => {
    const quoted = []
    const masked = line
      .replace(/\\([|;])/g, (match, char) => { quoted.push(char); return `\u0000${quoted.length - 1}\u0000` })
      .replace(/(['"])(.*?)\1/g, (match) => {
        quoted.push(match)
        return `\u0000${quoted.length - 1}\u0000`
      })
    return masked.split(/\|\||&&|;|\|/).map((segment) => segment.trim()
      .replace(/\u0000(\d+)\u0000/g, (match, index) => quoted[Number(index)])).filter(Boolean)
  }
  const segmentWritesName = (segment, name) => {
    // `apt-get install bash` / `apk add bash` 是包管理器，不是把文件写进镜像当前目录（第四十二轮 O1）。
    // 包管理器可能带 `sudo`/环境变量前缀/`-y` 前置/`apt update &&`（第四十三轮）：只要段里同时出现工具名与 install/add 就不算写文件。
    const commandText = normalizeRunText(segment).replace(/^\s*RUN\b/i, '').trim()
    // microdnf/tdnf 等变体也算包管理器（第四十四轮 OB-5）。
    if (/\b(?:apt|apt-get|apk|yum|dnf|microdnf|tdnf|zypper|pip3?|npm|yarn|gem|go)\b/i.test(commandText)
      && /\b(?:install|add|get)\b/i.test(commandText)) return false
    const normalizedSegment = expandRunVars(normalizeRunText(segment))
    if (!RUN_WRITE_VERB.test(normalizedSegment)) return false
    const pattern = new RegExp(`(^|[\\s'"=:])${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}([\\s'"]|$)`)
    return pattern.test(normalizedSegment)
  }

  // 逻辑行（与 buildkit 一致：反斜杠续行去掉反斜杠与尾随空白后粘连），并跳过 heredoc 体——
  // 否则「续行/heredoc 体里以 FROM 开头的行」会被误当成新阶段（第二十七轮复核的绕过面）。
  const logicalLines = []
  const heredocQueue = []
  const heredocBodies = new Map()
  let continuation = null
  for (let i = 0; i < physicals.length; i += 1) {
    const raw = physicals[i]
    if (continuation !== null) {
      continuation.text = `${continuation.text.replace(/\\[ \t]*$/, '')}${raw}`
      if (!/\\[ \t]*$/.test(raw)) {
        // 只在逻辑行**拼完**时登记定界符（多行续行也只会登记一次）。
        if (!raw.trim().startsWith('#')) {
          const delimiters = heredocBodyDelimiters(continuation.text)
          if (delimiters.length > 0) heredocQueue.push(...delimiters)
        }
        logicalLines.push({ text: continuation.text, line: continuation.line })
        continuation = null
      }
      continue
    }
    if (heredocQueue.length > 0) {
      const current = heredocQueue[0]
      const candidate = current.chomp ? raw.replace(/^\t+/, '').replace(/\r$/, '') : raw.replace(/\r$/, '')
      if (candidate === current.tag) {
        heredocQueue.shift()
        continue
      }
      const list = heredocBodies.get(current.tag) ?? []
      list.push(raw)
      heredocBodies.set(current.tag, list)
      continue
    }
    if (/\\[ \t]*$/.test(raw) && !raw.trim().startsWith('#')) {
      // 定界符只在逻辑行拼完后登记一次（否则 `COPY <<E \\` 会被登记两次 ⇒ 伪「未终止」，OB-b）。
      continuation = { text: raw, line: i + 1 }
      continue
    }
    if (!raw.trim().startsWith('#')) {
      const delimiters = heredocBodyDelimiters(raw)
      if (delimiters.length > 0) heredocQueue.push(...delimiters)
    }
    logicalLines.push({ text: raw, line: i + 1 })
  }
  if (continuation !== null) logicalLines.push({ text: continuation.text, line: continuation.line })
  // 未终止的 heredoc：构建必然失败 ⇒ fail-closed（并避免「门 PASS 而 buildkit 报错」）。
  const unterminatedHeredocs = heredocQueue.map((item) => item.tag)
  const instructions = logicalLines.map((entry) => entry.text)
  // 阶段模型：`FROM <stage>` 会继承之前阶段的文件系统 ⇒ 只取「最后一个 FROM 之后」会漏掉继承来的写入面
  // （第三十一轮复核 FO-1：`FROM build` 继承的 COPY 入口脚本被当基础镜像自带文件放行）。
  // `FROM ${BASE}`：用同文件里 ARG 的默认值解析（第三十二轮 FO-E）；解析不出来就当字面量（不继承）。
  const argDefaults = new Map()
  for (const line of instructions) {
    const declared = line.match(/^\s*ARG\s+([A-Za-z_][A-Za-z0-9_]*)=(\S+)\s*$/i)
    if (declared) argDefaults.set(declared[1], declared[2].replace(/^['"]|['"]$/g, ''))
  }
  const resolveBuildVar = (value) => value.replace(/\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)/g,
    (all, braced, plain) => {
      const name = braced ?? plain
      // compose `build.args` 的值优先于 Dockerfile 里的 ARG 默认值（第三十三轮 F4）。
      const fromCompose = composeBuildArgs?.[name]
      if (fromCompose !== undefined && fromCompose !== null) return String(fromCompose)
      return argDefaults.get(name) ?? all
    })
  const stageStarts = []
  for (let i = 0; i < instructions.length; i += 1) {
    if (/^\s*FROM\b/i.test(instructions[i])) stageStarts.push(i)
  }
  if (stageStarts.length === 0) stageStarts.push(0)
  const stages = stageStarts.map((start, index) => {
    const end = index + 1 < stageStarts.length ? stageStarts[index + 1] : instructions.length
    const head = instructions[start] ?? ''
    const aliasMatch = head.match(/\bAS\s+([A-Za-z0-9_.-]+)\s*$/i)
    const baseMatch = head.match(/^\s*FROM\s+(?:--platform=\S+\s+)?(\S+)/i)
    return {
      index, start, end,
      alias: aliasMatch ? aliasMatch[1].toLowerCase() : '',
      base: baseMatch ? resolveBuildVar(baseMatch[1]).toLowerCase() : ''
    }
  })
  // `FROM ${BASE}` 解析为空（例如 compose build.args 传了空串）⇒ 构建必然失败（第三十四轮 N5）。
  const emptyBaseStages = stages.filter((stage) => !stage.base)
  const byAlias = new Map(stages.filter((stage) => stage.alias).map((stage) => [stage.alias, stage.index]))
  const contributing = new Set([stages.length - 1])
  let cursor = stages.length - 1
  while (cursor >= 0) {
    const base = stages[cursor].base
    if (!base || !byAlias.has(base)) break
    const parent = byAlias.get(base)
    if (contributing.has(parent)) break
    contributing.add(parent)
    cursor = parent
  }
  const orderedStages = [...contributing].sort((a, b) => a - b)
  const finalStage = orderedStages.flatMap((idx) => instructions.slice(stages[idx].start, stages[idx].end))
  const finalOnly = instructions.slice(stages[stages.length - 1].start, stages[stages.length - 1].end)
  // Docker 语义：CMD 只提供默认参数，**不重置**继承来的 ENTRYPOINT；只有 ENTRYPOINT 会重置它（第三十三轮 F3）。
  // 因此分别回溯：最终阶段没有 ENTRYPOINT 就取最近祖先的 ENTRYPOINT；CMD 同样逐层回溯。
  // Docker 语义（第三十四轮 N1/N4）：同一阶段里**多条** ENTRYPOINT/CMD 只有**最后一条**生效；
  // CMD 不重置继承的 ENTRYPOINT，但**设置 ENTRYPOINT 会清空继承的 CMD**；两者各自回溯到最近声明的阶段。
  const ownLast = (stageIdx, keyword) => {
    const hits = instructions.slice(stages[stageIdx].start, stages[stageIdx].end)
      .filter((line) => new RegExp(`^\\s*${keyword}\\b`, 'i').test(line))
    return hits.length ? hits[hits.length - 1] : null
  }
  let entrypointStageIdx = -1
  for (const idx of orderedStages.slice().reverse()) {
    if (ownLast(idx, 'ENTRYPOINT')) { entrypointStageIdx = idx; break }
  }
  const entrypointLine = entrypointStageIdx >= 0 ? ownLast(entrypointStageIdx, 'ENTRYPOINT') : null
  let cmdLine = ownLast(stages.length - 1, 'CMD')
  if (!cmdLine) {
    // 没有自己的 CMD 时回溯：有 ENTRYPOINT 就只找「该阶段及其之后」（更早的 CMD 已被 ENTRYPOINT 清空）；
    // 完全没有 ENTRYPOINT 时，最近祖先的 CMD 就是实际运行的命令（第三十五轮 FO-1 —— 此前的守卫让它永不回溯）。
    const floor = entrypointStageIdx >= 0 ? entrypointStageIdx : -1
    for (const idx of orderedStages.slice().reverse()) {
      if (idx < floor) break
      const hit = ownLast(idx, 'CMD')
      if (hit) { cmdLine = hit; break }
    }
  }
  const entryStage = [entrypointLine, cmdLine].filter(Boolean)
  const dests = []
  const invisible = []
  const NON_TEXT_EXT = /\.(?:jar|war|ear|class|bin|so|dll|dylib|zip|tar|gz|tgz|bz2|xz|7z|png|jpe?g|gif|ico|pdf|woff2?|ttf|otf|node)$/i
  const BASE_IMAGE_DIR = /^\/(?:bin|sbin|usr|lib[A-Za-z0-9_]*|run|docker-entrypoint[^/]*)(?:\/|$)/
  // shell 家族：裸名、相对路径（bin/sh）与绝对路径（/usr/bin/env）都算（第二十九/三十轮复核）
  // 裸名（sh/bash/env…）算 shell；带路径的只有落在**已知基础镜像目录**里才算（`/bin/sh`），
  // 否则 `/app/sh`、`/app/env` 这类入口脚本会被当 shell 词吞掉（第三十五轮 FO-3）。
  const SHELL_BASENAME = /^(?:ba|z|k|a|da)?sh$|^env$/
  const SCRIPT_NAME = /^[\w.-]+\.(?:sh|bash|zsh|ksh)$/
  const SHELL_KEYWORD = /^(?:do|done|then|else|elif|fi|if|while|until|for|case|esac|in|\{|\()$/i
  // init/包装器：其后跟的才是真正的入口命令
  // 第三十一轮复核 FO-3：`start-stop-daemon --exec …` / `s6-setuidgid user …` 也是「其后才是真命令」的包装器；
  // 不在白名单时首词被当裸名收走、命令位丢失，后续路径既不被收集也不判不可见（静默 PASS）。
  const WRAPPER_WORD = /^(?:[\w./-]+\/)?(?:tini|dumb-init|catatonit|s6-svscan|s6-setuidgid|s6-applyuidgid|s6-envdir|start-stop-daemon|chpst|setuidgid|softlimit|envdir|runsv|runsvdir|sv|supervisord|gosu|su-exec|setpriv|timeout|stdbuf|nohup|nice|chroot|busybox|su|setsid|flock|chrt|taskset|numactl|strace|unshare|nsenter|ionice|cpulimit|setarch|prlimit|watch)$/i
  // 这些包装器的**第一个位置实参**是目录/锁文件（`envdir DIR CMD`、`flock FILE CMD`），不是入口（第三十二轮 FO-B）。
  const POSITIONAL_ARG_WRAPPER = /^(?:[\w./-]+\/)?(?:envdir|flock)$/i
  // 带值选项必须**按工具**判定（第三十三轮 F1）：同一字母在不同工具里语义相反——
  // `bash -e`/`sh -u`/`bash -n`/`bash -s`/`bash -p` 是开关（不带值），`env -u NAME`/`sh -o errexit`/
  // `flock -w SECONDS`/`chpst -e DIR` 才带值。跨工具共用一张表会把 `bash -e CMD` 的命令吃掉。
  const VALUE_OPTIONS_BY_TOOL = new Map([
    ['shell', [/^(?:-o|\+o|--rcfile|--init-file)$/i]],
    ['env', [/^(?:-u|-C|--unset|--chdir)$/i]],
    ['su', [/^(?:-c|-s|-g|-G|-u|-p|--command|--shell|--group|--supp-group|--user|--whitelist-environment)$/i]],
    ['setpriv', [/^(?:--reuid|--regid|--groups|--inh-caps|--ambient-caps|--bounding-set|--securebits|--selinux-label|--apparmor-profile|--landlock-access|--landlock-rule|--pdeathsig)$/i]],
    ['timeout', [/^(?:-s|-k|--signal|--kill-after)$/i]],
    ['nice', [/^(?:-n|--adjustment)$/i]],
    ['chpst', [/^(?:-u|-e|-l|-L|-n|-t|-b|-C|-\/|--root)$/i]],
    ['start-stop-daemon', [/^(?:--chdir|--chroot|--chuid|--user|--group|--pidfile|--name|--startas|--retry|--wait|--nicelevel|--umask|--exec|--signal|--output|--procsched)$/i]],
    ['flock', [/^(?:-w|-E|--timeout|--conflict-exit-code|--lock-file)$/i]],
    ['s6-setuidgid', []],
    ['s6-applyuidgid', [/^(?:-u|-g|-G)$/i]],
    ['setuidgid', []],
    ['softlimit', [/^(?:-m|-d|-s|-l|-a|-r|-o|-f|-p|-n)$/i]],
    ['envdir', []],
    ['chroot', [/^(?:--userspec|--groups)$/i]],
    ['setsid', []],
    ['chrt', [/^(?:-p|--pid|-P|--sched-priority)$/i]],
    ['taskset', [/^(?:-p|--pid)$/i]],
    ['numactl', [/^(?:--physcpubind|--cpunodebind|--membind|--preferred|--interleave)$/i]],
    ['strace', [/^(?:-o|-p|-e|-s|-u|-E|-I|-b|-a)$/i]],
    ['unshare', [/^(?:-s|--setgroups|--map-user|--map-group|--map-users|--map-groups)$/i]],
    ['nsenter', [/^(?:-t|--target|--setuid|--setgid)$/i]],
    ['ionice', [/^(?:-c|-n|-p)$/i]],
    ['cpulimit', [/^(?:-l|-p|-e)$/i]],
    ['setarch', []],
    ['prlimit', [/^(?:--pid|--nofile|--nproc|--as|--core|--cpu|--fsize|--data|--stack|--memlock|--nofile)$/i]],
    ['watch', [/^(?:-n|--interval)$/i]],
    ['gosu', []],
    ['su-exec', []],
    ['dumb-init', []],
    ['tini', []],
    ['catatonit', []],
    ['busybox', []],
    ['supervisord', [/^(?:-c|-u|-l|-j|-p)$/i]],
    ['runsv', []],
    ['runsvdir', []],
    ['sv', []],
    ['s6-svscan', []],
    ['stdbuf', [/^(?:-i|-o|-e)$/i]],
    ['nohup', []],
  ])
  // GNU 长选项允许无歧义缩写：`--comm=` 就是 `--command=`、`--exe=` 就是 `--exec=`（第三十五轮 FO-5）。
  const LONG_OPTION_CANON = ['--command', '--session-command', '--split-string', '--exec', '--startas', '--chdir', '--chroot', '--chuid', '--user', '--group', '--unset', '--setenv', '--timeout', '--signal', '--kill-after', '--interval', '--target', '--single-child']
  // 这些选项的内联值**就是要执行的命令**（`su --session-command=…`、`env --split-string=…`）。
  const COMMAND_VALUED_LONG = /^--(?:exec|startas|command|session-command|split-string)$/i
  const canonicalLongOption = (option) => {
    if (!option.startsWith('--')) return option
    const hits = LONG_OPTION_CANON.filter((candidate) => candidate.startsWith(option))
    return hits.length === 1 ? hits[0] : option
  }
  const toolKeyOf = (word) => {
    const base = word.split('/').pop().toLowerCase()
    if (VALUE_OPTIONS_BY_TOOL.has(base)) return base
    if (/^(?:ba|z|k|a|da)?sh$/.test(base) || base === 'env') return base === 'env' ? 'env' : 'shell'
    return ''
  }
  // 执行前缀词：它们自己不是入口，但**保持**命令位（`command /app/entry.sh`、`time /app/entry.sh`、`! /app/entry.sh`、`xargs /app/entry.sh`）。
  const COMMAND_PREFIX = /^(?:command|builtin|time|!|xargs)$/i
  // chroot 的下一个词是目录实参（不是命令）⇒ 吃掉它。
  const CHROOT_WORD = /^(?:[\w./-]+\/)?chroot$/i
  // 包装器（gosu/su/su-exec/chroot/setpriv/timeout…）之后的词**逐个**都按命令位候选评估：
  // 用户名/目录/数字实参通常不在构建上下文里（裸名）或是目录（不是脚本），不会误收；真正的命令则会被收到。
  // 第二十八轮复核：`su -c CMD USER`、`setpriv --opt… CMD` 这类「选项在前的写法」靠「跳过一个词」会吃掉真正的命令。
  // 命令位上的裸名（无扩展名）按脚本候选收集（`exec entry`），但解释器/常用工具名要排除。
  const BARE_COMMAND = /^[\w.-]+$/
  const INTERPRETER_WORD = /^(?:node|nodejs|npm|npx|yarn|pnpm|bun|deno|python|python3|pip|pip3|java|javac|mvn|gradle|ruby|perl|php|go|cargo|dotnet|sh|bash|zsh|ksh|ash|dash|env|exec|grep|sed|awk|cat|echo|printf|true|false|test|ls|cp|mv|rm|mkdir|ln|chmod|chown|tar|unzip|curl|wget|sleep|tail|head|touch|set|export|source|tee|jq|git|apk|apt|apt-get|yum|dnf|pipenv|make|gcc|cc|ld|openssl|stat|readlink|basename|dirname|find|sort|uniq|tr|cut|wc|diff|patch|useradd|groupadd|adduser)$/i
  const normalizeTarget = (value) => {
    const normalized = path.posix.normalize(value)
    return normalized.startsWith('/') ? normalized : `/${normalized}`
  }
  // `COPY x ./`（无 WORKDIR）的 dest 归一成 `/`：此时不能用 `${dest}/` 做前缀（会变成 `//`），
  // 否则目标永远匹配不到、合法 Dockerfile 被判「无 COPY 来源」（第三十一轮复核 OB-5）。
  const relTo = (target, dest) => {
    if (dest === '/') return target.startsWith('/') ? target.slice(1) : null
    if (target === dest) return ''
    return target.startsWith(`${dest}/`) ? target.slice(dest.length + 1) : null
  }
  const isUnder = (target, dest) => relTo(target, dest) !== null
  // shell 形式的分词器：引号与反斜杠转义的空格都算一个 token（第三十八轮：`COPY entry.sh "/opt/my dash"`）。
  const splitShellTokens = (text) => {
    const tokens = []
    let current = ''
    let quote = ''
    for (let index = 0; index < text.length; index += 1) {
      const ch = text[index]
      if (quote) {
        if (ch === quote) quote = ''
        else if (ch === '\\' && quote === '"' && index + 1 < text.length) { current += text[index + 1]; index += 1 }
        else current += ch
        continue
      }
      if (ch === '"' || ch === "'") { quote = ch; continue }
      if (ch === '\\' && index + 1 < text.length) {
        if (/\s/.test(text[index + 1])) { current += text[index + 1]; index += 1; continue }
        current += ch
        continue
      }
      if (/\s/.test(ch)) {
        if (current) { tokens.push(current); current = '' }
        continue
      }
      current += ch
    }
    if (current) tokens.push(current)
    return tokens
  }
  // glob 按**路径段**逐级展开（`rootfs/*/*.tar` 也要能命中），最多 6 段、每段最多 4096 项。
  const globSegmentMatcher = (segment) => {
    let pattern = ''
    for (let index = 0; index < segment.length; index += 1) {
      const ch = segment[index]
      if (ch === '*') pattern += '.*'
      else if (ch === '?') pattern += '.'
      else if (ch === '[') {
        const close = segment.indexOf(']', index + 1)
        if (close > index) {
          const body = segment.slice(index + 1, close)
          pattern += `[${body.startsWith('!') ? `^${body.slice(1)}` : body}]`
          index = close
        } else pattern += '\\['
      } else pattern += ch.replace(/[.+^${}()|[\]\\*?]/, '\\$&')
    }
    return new RegExp(`^${pattern}$`)
  }
  const expandGlob = (raw) => {
    if (!/[*?[]/.test(raw)) return [raw]
    if (raw.startsWith('/')) return [raw]
    const segments = raw.split('/').slice(0, 6)
    let current = ['']
    for (const segment of segments) {
      const next = []
      for (const base of current) {
        if (!/[*?[]/.test(segment)) {
          next.push(base ? `${base}/${segment}` : segment)
          continue
        }
        let entries
        try {
          entries = readdirSync(path.resolve(contextDir, base || '.'))
        } catch {
          continue
        }
        const matcher = globSegmentMatcher(segment)
        for (const entry of entries.slice(0, 4096)) {
          if (!matcher.test(entry)) continue
          next.push(base ? `${base}/${entry}` : entry)
        }
      }
      current = next
    }
    return current
  }
  // PATH 解析：按顺序应用同文件里的 `ENV PATH=…` / `ENV PATH …`，`$PATH` 引用上一次的值，
  // 相对段按 WORKDIR 解析，最后回退到 Docker 默认 PATH（第三十九轮 FO-A + 第四十轮 F3）。
  const DEFAULT_PATH = ['/usr/local/sbin', '/usr/local/bin', '/usr/sbin', '/usr/bin', '/sbin', '/bin']
  const pathSegments = (fallbackWorkdir) => {
    let current = DEFAULT_PATH.join(':')
    for (const line of finalStage) {
      const declared = line.match(/^\s*ENV\s+PATH(?:\s*=\s*|\s+)(.+)$/i)
      if (!declared) continue
      let value = declared[1].trim().replace(/^['"]|['"]$/g, '')
      value = value.replace(/\$\{PATH\}|\$PATH\b/g, current)
      if (value.trim()) current = value
    }
    return current.split(':').filter(Boolean).map((segment) => (segment.startsWith('/') ? segment : path.posix.join(fallbackWorkdir || '/', segment)))
  }
  let workdir = ''
  // 按**阶段顺序**收集，并记录条目所属阶段：后阶段往同一 dest 的写入覆盖前阶段（OB-c）。
  const copyEntries = []
  const buildVars = new Map()
  const argVars = new Map()
  const rootDirSources = []
  const archiveDirs = []
  const unresolvedDests = []
  let rootWriteSurface = false
  let copyGroup = 0
  const stageLines = orderedStages.map((idx) => ({ idx, lines: instructions.slice(stages[idx].start, stages[idx].end) }))
  for (const { idx: stageIndex, lines: stageLinesOfStage } of stageLines) {
    for (const physical of stageLinesOfStage) {
    const fromLine = physical.trim().match(/^FROM\b(.*)$/i)
    if (fromLine) {
      const base = (fromLine[1].match(/^\s*(?:--platform=\S+\s+)?(\S+)/i) ?? [])[1]
      const inherits = base && byAlias.has(resolveBuildVar(base).toLowerCase()) && contributing.has(byAlias.get(resolveBuildVar(base).toLowerCase()))
      if (!inherits) workdir = ''
      continue
    }
    // ENV/ARG 的三种写法都要进变量表：`K=v`、`K=v K2=v2`、`K value with spaces`；
    // 只有 `ARG K` 时用 compose build.args 的值（第三十九轮 FO-B）。
    const envLine = physical.trim().match(/^(?:ENV|ARG)\s+(.+)$/i)
    if (envLine) {
      const payload = envLine[1].trim()
      // ENV 覆盖同名 ARG（Docker 口径）：分两个表存，展开时 ENV 优先（第四十轮 O4）。
      const target = /^ARG\b/i.test(physical.trim()) ? argVars : buildVars
      if (payload.includes('=')) {
        for (const match of payload.matchAll(/([A-Za-z_][A-Za-z0-9_]*)\s*=\s*("([^"]*)"|'([^']*)'|(\S+))/g)) {
          target.set(match[1], match[3] ?? match[4] ?? match[5] ?? '')
        }
      } else {
        const words = payload.split(/\s+/).filter(Boolean)
        if (words.length >= 2) target.set(words[0], words.slice(1).join(' ').replace(/^['\"]|['\"]$/g, ''))
        else if (words.length === 1 && composeBuildArgs && Object.prototype.hasOwnProperty.call(composeBuildArgs, words[0])) {
          target.set(words[0], String(composeBuildArgs[words[0]] ?? ''))
        }
      }
    }
    const instruction = physical.trim().match(/^(COPY|ADD)\b(.*)$/i)
    if (instruction) {
      copyGroup += 1
      const rest = instruction[2]
      const fromMatch = rest.match(/--from=\S+/i)
      const parents = /--parents\b/i.test(rest)
      const remote = /(?:^|\s)(?:https?|git|ssh):\/\/|(?:^|\s)git@/i.test(rest)
      const withoutFlags = rest.replace(/--\S+(?:=\S+)?\s*/g, ' ').trim()
      // JSON 数组形式（`COPY ["a.sh", "/opt/app/run"]`）——第二十七轮复核的绕过面：不解析就整条放行。
      let parts = splitShellTokens(withoutFlags)
      if (withoutFlags.startsWith('[')) {
        try {
          const parsed = JSON.parse(withoutFlags)
          if (Array.isArray(parsed) && parsed.length >= 2 && parsed.every((item) => typeof item === 'string')) parts = parsed.map((item) => item.replace(/^['"]|['"]$/g, ''))
        } catch {
          // 解析失败保持按空白切词（fail-closed 方向：dest 不会被正确登记）
        }
      }
      if (parts.length >= 2) {
        const rawDestBeforeVars = parts[parts.length - 1]
        // 支持 `${X}`、`$X`、`${X:-default}`（第三十九轮 FO-B）。
        const rawDest = rawDestBeforeVars.replace(/\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}|\$([A-Za-z_][A-Za-z0-9_]*)/g,
          (match, braced, fallback, bare) => buildVars.get(braced ?? bare) ?? argVars.get(braced ?? bare) ?? fallback ?? match)
        // dest 是文件还是目录：带尾斜杠或多源 ⇒ 目录（名字取源 basename）；否则 dest 本身就是文件名。
        if (rawDest.includes('$')) unresolvedDests.push(rawDest)
        const destIsDir = /\/$/.test(rawDest) || parts.length > 2
        let dest = rawDest
        if (!dest.startsWith('/')) dest = path.posix.join(workdir || '/', dest)
        dest = normalizeTarget(dest).replace(/\/$/, '') || '/'
        dests.push(dest)
        // 往某个目录里 COPY 一个文件，等于构建写入了那个目录 ⇒ 目录也算「构建写入面」。
        const destDir = path.posix.dirname(dest)
        if (destDir && destDir !== '/' && destDir !== '.') dests.push(destDir)
        // `ADD rootfs.tar /`（或目录源到 `/`）：整根都可能被写入 ⇒ 不能再把已知基础镜像目录当"自带文件"（FO-4）。
        if (/^ADD$/i.test(instruction[1])) {
          // COPY 从不解包；只有 ADD 对**本地**归档做解包（zip 也不解）（第三十七轮过拦修正）。
          const rootSource = fromMatch || remote ? false : parts.slice(0, -1).some((item) => {
            // 只看**内容**：扩展名不可信（纯文本叫 `rootfs.tar` 不会被 Docker 解包，远程 URL 也不解包）。
            // glob 源（`ADD rootfs* /`）要先展开，否则永远命中不了（第三十七轮 N-3）。
            for (const expanded of expandGlob(item)) {
              const absolute = path.resolve(contextDir, expanded)
              let present = false
              try {
                const stat = statSync(absolute)
                present = true
                // 目录源到 `/`：只在目标真在源目录里时才算被写入（避免 `ADD etcroot /` 连带拦住所有基础镜像入口）。
                if (stat.isDirectory()) { rootDirSources.push(expanded); return false }
              } catch {
                // 不存在：下面按 fail-closed 处理
              }
              // 本地源不存在时构建必然失败，门不能 PASS（与「未终止 heredoc ⇒ FAIL」同口径）；
              // 存在则**只看内容**，扩展名不算（纯文本叫 rootfs.tar 不会被 ADD 解包）。
              if (!present) return true
              if (looksLikeArchive(absolute)) {
                // ADD 归档到 `/` 以外的目录：解包内容未知 ⇒ 该目录及以下都算写入面（第四十四轮 FO-2）。
                if (dest !== '/') archiveDirs.push(dest)
                return dest === '/'
              }
            }
            return false
          })
          if (rootSource && dest === '/') rootWriteSurface = true
        }
        const rawSources = parts.slice(0, -1).flatMap((item) => item.split(',').map((one) => one.trim()))
        for (const rawSource of rawSources) {
          if (rawSource.startsWith('<<')) {
            // heredoc 直接提供文件内容：登记其体文本，供入口脚本内容扫描（内容就在 Dockerfile 里，并非不可见）。
            const tag = rawSource.replace(/^<<-?/, '').replace(/^['"]|['"]$/g, '')
            copyEntries.push({ stage: stageIndex, group: copyGroup, dest, source: rawSource, isDir: false, parents, heredocText: (heredocBodies.get(tag) ?? []).join('\n'), name: path.posix.basename(dest) })
            continue
          }
          if (fromMatch || remote) {
            // `--from` 的内容未知 ⇒ dest `/` 表示整根都可能是它写的；远程 URL 只写一个已知文件名（不解包），
            // 因此把目标精确到 `dest + basename(url)`（第四十一轮 FO-2 的边界）。
            const remoteDest = remote && !fromMatch
              ? (destIsDir
                ? normalizeTarget(`${dest === '/' ? '' : dest}/${path.posix.basename(rawSource.split(/[?#]/)[0]) || ''}`)
                : dest)
              : dest
            invisible.push({ dest: remoteDest, source: rawSource, reason: fromMatch ? fromMatch[0] : '远程 URL 源' })
            continue
          }
          for (const source of expandGlob(rawSource)) {
            const resolved = path.resolve(contextDir, source)
            let stat = null
            try {
              stat = statSync(resolved)
            } catch {
              stat = null
            }
            if (stat === null || !insideRepo(resolved)) {
              invisible.push({ dest, source, reason: '在构建上下文里不存在或指向仓库外' })
              continue
            }
            copyEntries.push({
              stage: stageIndex, group: copyGroup, dest, source, isDir: stat.isDirectory(), parents,
              name: destIsDir ? path.posix.basename(source) : path.posix.basename(dest)
            })
          }
        }
      }
      continue
    }
    const wd = physical.trim().match(/^WORKDIR\s+(.+)$/i)
    if (wd) {
      const raw = wd[1].trim().replace(/^["']|["']$/g, '').replace(/\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}|\$([A-Za-z_][A-Za-z0-9_]*)/g,
        (match, braced, fallback, bare) => buildVars.get(braced ?? bare) ?? argVars.get(braced ?? bare) ?? fallback ?? match)
      workdir = raw.startsWith('/') ? normalizeTarget(raw).replace(/\/$/, '') : normalizeTarget(path.posix.join(workdir || '/', raw)).replace(/\/$/, '')
    }
    }
  }
  // 覆盖粒度是**文件**不是目录：`COPY a.sh /app/` + `COPY b.sh /app/` 两份都要在（第三十四轮 N3）；
  // 只有同名文件的后一次写入才覆盖前一次（OB-c / O1）。目录源与 --parents 按目标逐一解析，不做整 dest 去重。
  const copyMap = new Map()
  const winningByName = new Map()
  const rankOf = (entry) => [entry.stage, entry.group]
  const laterThan = (a, b) => a[0] > b[0] || (a[0] === b[0] && a[1] > b[1])
  const fileEntryName = (entry) => {
    if (entry.isDir || entry.parents) return null
    return entry.name ?? path.posix.basename(entry.source)
  }
  for (const entry of copyEntries) {
    const name = fileEntryName(entry)
    const key = name === null ? null : `${entry.dest}\u0000${name}`
    const list = copyMap.get(entry.dest) ?? []
    if (key !== null) {
      const previousRank = winningByName.get(key)
      if (previousRank && !laterThan(rankOf(entry), previousRank)) continue
      winningByName.set(key, rankOf(entry))
      // 同名条目替换，保留同 dest 下其它文件名
      for (let index = list.length - 1; index >= 0; index -= 1) {
        if (list[index].__name === name) list.splice(index, 1)
      }
      list.push({ __name: name, source: entry.source, isDir: entry.isDir, parents: entry.parents, heredocText: entry.heredocText })
      copyMap.set(entry.dest, list)
      continue
    }
    list.push({ source: entry.source, isDir: entry.isDir, parents: entry.parents, heredocText: entry.heredocText })
    copyMap.set(entry.dest, list)
  }
  const directTargets = new Set()
  const bareCandidates = new Set()
  const execBareCandidates = new Set()

  const indirect = new Set()
  for (const physical of entryStage) {
    const entry = physical.trim().match(/^(ENTRYPOINT|CMD)\b(.*)$/i)
    if (!entry) continue
    const tail = entry[2].trim()
    let argv = null
    const jsonPart = tail.match(/^\[(.*)\]$/s)
    if (jsonPart) {
      try {
        const parsed = JSON.parse(`[${jsonPart[1]}]`)
        if (Array.isArray(parsed) && parsed.every((item) => typeof item === 'string')) argv = parsed
      } catch {
        argv = null
      }
    }
    if (!argv) argv = tail.replace(/[\[\]]/g, ' ').split(/\s+/).filter(Boolean)
    // 摊平成词序列：每个实参再去包裹引号、按空白切词。
    const words = []
    const fromJson = Boolean(jsonPart && argv)
    for (const arg of argv) {
      const unquoted = arg.replace(/^["']|["']$/g, '')
      // JSON 数组里**以 `/` 开头的实参是一个带空格的路径**（`["/opt/my dash"]`），不能再按空白切（第三十八轮 N-5）。
      if (fromJson && unquoted.startsWith('/') && /\s/.test(unquoted)) { words.push(unquoted); continue }
      // 分隔符也要切出来：`id; $ENTRY`、`id&& $ENTRY` 这类粘连写法必须能重置命令位。
      // `\n`/`\r` 必须作为**分隔符 token**切出来：`sh -c "id\n/app/entry.sh"` 的第二行是新命令（第三十二轮 FO-D）。
      for (const rawWord of unquoted.split(/(\n|\r|;|&&|\|\||\|)|\s+/)) {
        const word = (rawWord ?? '').replace(/^["']+|["']+$/g, '')
        if (word) words.push(word)
      }
    }
    let commandPosition = true
    let previousWasCd = false
    let wrapperMode = false
    let skipNextWord = false
    let previousWordWasExec = false
    let positionalArgsLeft = 0
    let currentTool = ''
    for (const word of words) {
      if (skipNextWord) { skipNextWord = false; continue }
      // `exec -a NAME CMD`：-a 的实参是进程名，不是命令 ⇒ 跳过它（FO-5）。
      if (word === '-a' && previousWordWasExec) { skipNextWord = true; previousWordWasExec = false; continue }
      if (word === '&&' || word === '||' || word === ';' || word === '|' || word === '&' || word === '--' || word === '\n' || word === '\r') {
        commandPosition = true
        previousWasCd = false
        wrapperMode = false
        positionalArgsLeft = 0
        continue
      }
      // `+o` 这类 `+` 开头的选项也要识别（`bash +o posix CMD`）——N2。
      if (word.startsWith('+')) {
        const plusOptions = VALUE_OPTIONS_BY_TOOL.get(currentTool) ?? []
        if (plusOptions.some((pattern) => pattern.test(word))) skipNextWord = true
        continue
      }
      if (word.startsWith('-')) {
        // 任意 `--opt=value` 内联形态都不消费下一个词；命令类选项的值要按命令扫（N2：`su --command=…`）。
        const inlineLongRaw = word.match(/^--([A-Za-z][A-Za-z-]*)=/)
        const canonicalInline = inlineLongRaw ? canonicalLongOption(`--${inlineLongRaw[1]}`) : ''
        if (inlineLongRaw && !COMMAND_VALUED_LONG.test(canonicalInline)) continue
        // `--exec=PATH` / `--startas=PATH` / `--command=PATH`（含 GNU 缩写）：内联值就是要执行的命令。
        const inlineExec = inlineLongRaw ? word.replace(/^--[A-Za-z][A-Za-z-]*=/, '').trim() : null
        if (inlineExec !== null && COMMAND_VALUED_LONG.test(canonicalInline)) {
          const value = inlineExec.replace(/^['"]|['"]$/g, '')
          // 含路径分隔符 → 直接当路径；裸名 → 与命令位裸名同口径（只有上下文里真有同名文件才算）。
          if (value.includes('/')) directTargets.add(normalizeTarget(value.startsWith('/') ? value : path.posix.join(workdir || '/', value)))
          else if (BARE_COMMAND.test(value)) execBareCandidates.add(value)
          commandPosition = false
          continue
        }
        // 短选项粘连值：`su -c/app/entry.sh`、`env -S/app/entry.sh` 的命令就贴在选项后面（N-6）。
        // 聚合短选项：`-iS/app/entry.sh`、`-pc/app/entry.sh`——值贴在**最后一个带值字母**之后。
        const clusterShort = word.match(/^-([A-Za-z]{2,})(.*)$/)
        if (clusterShort && /^(?:su|shell|env)$/.test(currentTool)) {
          const flags = clusterShort[1]
          const tail = clusterShort[2]
          const cut = [...flags].findIndex((ch) => ch === 'c' || ch === 'S')
          if (cut >= 0) {
            const attached = flags.slice(cut + 1) + tail
            if (attached) {
              const value = attached.replace(/^['"]|['"]$/g, '')
              if (value.includes('/')) directTargets.add(normalizeTarget(value.startsWith('/') ? value : path.posix.join(workdir || '/', value)))
              else if (BARE_COMMAND.test(value)) bareCandidates.add(value)
              commandPosition = false
              continue
            }
          }
        }
        const attachedShort = word.match(/^-([A-Za-z])(.+)$/)
        if (attachedShort && /^(?:c|S)$/.test(attachedShort[1]) && /^(?:su|shell|env)$/.test(currentTool)) {
          const value = attachedShort[2].replace(/^['"]|['"]$/g, '')
          if (value.includes('/')) directTargets.add(normalizeTarget(value.startsWith('/') ? value : path.posix.join(workdir || '/', value)))
          else if (BARE_COMMAND.test(value)) bareCandidates.add(value)
          commandPosition = false
          continue
        }
        const canonicalWord = canonicalLongOption(word)
        // `--exec PATH`：下一个词是命令。
        if (/^--(?:exec|startas)$/i.test(canonicalWord)) { commandPosition = true; continue }
        if (/^--(?:command|session-command|split-string)$/i.test(canonicalWord)) { commandPosition = true; continue }
        // `-c`（shell/su 的命令串）：下一个词是命令，不是选项值。
        if (/^-c$/.test(word) || /^--command$/.test(word)) { commandPosition = true; previousWordWasExec = false; continue }
        // 只有**当前工具**的带值选项才消费下一个词（F1）。
        const toolOptions = VALUE_OPTIONS_BY_TOOL.get(currentTool) ?? []
        if (toolOptions.some((pattern) => pattern.test(canonicalWord))) skipNextWord = true
        continue
      }
      if (word === 'exec' || word === '.' || word === 'source') {
        commandPosition = true
        previousWasCd = false
        previousWordWasExec = word === 'exec'
        continue
      }
      previousWordWasExec = false
      // 执行前缀词（command/time/!/xargs）：它们不是入口，但命令位保持。
      if (COMMAND_PREFIX.test(word)) {
        commandPosition = true
        previousWasCd = false
        continue
      }
      if (word === 'cd') {
        previousWasCd = true
        commandPosition = true
        continue
      }
      if (previousWasCd) {
        previousWasCd = false
        continue
      }
      // 裸名算解释器；带路径的要「按 workdir 解析后不在构建写入面」才算解释器——
      // 写入面包含 COPY/ADD 的 dest、`ADD 归档 /` 的整根、以及 RUN 里被提到过的路径（第三十六轮 F-1 + 第三十七轮 N-1/N-2）。
      const resolvedWord = word.startsWith('/') ? word : path.posix.normalize(path.posix.join(workdir || '/', word))
      // RUN 里的写入可能只提到目录（`install … -t /usr/local/bin`）或相对路径（cwd=`/` 时写 `opt/dash`）。
    
      const stripLeadingSlashes = (text) => text.replace(/(^|[\s'"=:])[/]+/g, '$1')
      const bareResolved = resolvedWord.replace(/^\//, '')
      const dirBare = path.posix.dirname(bareResolved)
      const runMentionsWord = finalStage.some((line) => {
        if (!/^\s*RUN\b/i.test(line)) return false
        const redirectIntoWord = new RegExp(`(?:^|[^2&])>\\s*['\"]?${resolvedWord.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`)
        const normalizedRun = normalizeRunText(line)
        if (!RUN_WRITE_VERB.test(normalizedRun) && !redirectIntoWord.test(normalizedRun)) return false
        // 短裸名不能做子串匹配（`install.sh` 含 `sh`）；只认完整解析路径，或词本身是路径。
        if (line.includes(resolvedWord) || (word.includes('/') && line.includes(word))) return true
        const normalized = expandRunVars(stripLeadingSlashes(line))
        // 按**简单命令段**判定：`curl …/install.sh | sh` 里的 `sh` 是管道右侧的解释器，不算写入。
        if (runSegments(normalized).some((segment) => segmentWritesName(segment, bareResolved))) return true
        if (RUN_WRITE_VERB.test(line) && dirBare !== '.' && normalized.includes(dirBare)) {
          if (/\b(?:tar|unzip|7z)\b/.test(line)) return true
          if (new RegExp(`(^|[\\s'"=:/])${path.posix.basename(bareResolved).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}([\\s'"]|$)`).test(normalized)) return true
        }
        if (/\b(?:cp|install|mv|ln|tar|unzip)\b[^\n]*\s\.{1,2}\/?\s*$/.test(line) && workdir) {
          return path.posix.dirname(bareResolved) === workdir.replace(/^\//, '')
        }
        return false
      })
      const pathDirs = pathSegments(workdir)
      const pathName = word.includes('/') ? path.posix.basename(word) : word
      const pathCandidates = pathDirs.map((dir) => normalizeTarget(path.posix.join(dir, pathName)))
      // 目录**源**要按「这个文件真的在源目录里」判定，否则 `COPY . .` 会让 WORKDIR 下任何名字都算“写过”。
      const dirProvides = (entry, target) => {
        if (!entry.isDir) return false
        const relative = relTo(target, entry.dest)
        if (relative === null || relative === '') return false
        try {
          return statSync(path.resolve(contextDir, entry.source, relative)).isFile()
        } catch {
          return false
        }
      }
      const writtenTarget = (target) => copyEntries.some((entry) => entry.dest === target
        || (entry.parents && entry.dest !== '/' && normalizeTarget(`${entry.dest}/${entry.source}`) === target)
        || (!entry.parents && entry.name && entry.dest !== '/' && `${entry.dest}/${entry.name}` === target)
        || dirProvides(entry, target))
        || invisible.some((item) => item.dest === '/' || item.dest === target || isUnder(target, item.dest))
      const runDirWrites = (candidate) => {
        const bareCandidate = candidate.replace(/^\//, '')
        const dirCandidate = path.posix.dirname(bareCandidate)
        return finalStage.some((line) => {
          if (!/^\s*RUN\b/i.test(line)) return false
          const normalized = expandRunVars(stripLeadingSlashes(line))
          if (RUN_WRITE_VERB.test(line)
            && new RegExp(`${candidate.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?![\\w.-])`).test(line)) return true
          if (runSegments(normalized).some((segment) => segmentWritesName(segment, bareCandidate))) return true
          if (!RUN_WRITE_VERB.test(line) || dirCandidate === '.' || !normalized.includes(dirCandidate)) return false
          if (/\b(?:tar|bsdtar|gtar|unzip|7z)\b/.test(line)) return true
          return segmentWritesName(line, path.posix.basename(bareCandidate))
        })
      }
      const pathWriteHit = pathCandidates.find((candidate) => writtenTarget(candidate) || runDirWrites(candidate))
      // RUN 把**这个名字**写到某处（`install … sh`、`cp … sh`、`ln -s … sh`）⇒ 裸名不再是解释器。
      const runWritesName = finalStage.some((line) => {
        if (!/^\s*RUN\b/i.test(line) || !RUN_WRITE_VERB.test(line)) return false
        const name = path.posix.basename(word)
        if (!name || name.includes('/')) return false
        return runSegments(line).some((segment) => segmentWritesName(segment, name))
      })
      const writtenByBuild = rootWriteSurface
        || runMentionsWord
        || runWritesName
        || writtenTarget(resolvedWord)
        || Boolean(pathWriteHit)
      // 裸名同样要看「按 WORKDIR 解析后是否落在写入面」：`WORKDIR /usr/local/bin` + `COPY entry.sh sh` + `ENTRYPOINT ["sh"]`
      // 执行的是写进去的脚本，不是解释器（第三十八轮 N-1 残口）。
      const isShellWord = SHELL_BASENAME.test(path.posix.basename(word))
        && !/^\.{1,2}\//.test(word)
        && !writtenByBuild
        && (!word.startsWith('/') || BASE_IMAGE_DIR.test(word))
      if (isShellWord || SHELL_KEYWORD.test(word) || WRAPPER_WORD.test(word)) {
        const key = toolKeyOf(word)
        if (key) currentTool = key
        if (WRAPPER_WORD.test(word)) {
          wrapperMode = true
          positionalArgsLeft = POSITIONAL_ARG_WRAPPER.test(word) ? 1 : 0
        }
        if (CHROOT_WORD.test(word)) previousWasCd = true
        commandPosition = true
        continue
      }
      // 位置实参包装器的第一个路径/裸名是参数（`envdir /usr/local/etc CMD`、`flock /tmp/lock CMD`）。
      if (wrapperMode && positionalArgsLeft > 0) {
        positionalArgsLeft -= 1
        continue
      }
      // 包装器之后若出现解释器（`tini node /app/server.mjs`），其后的词是**数据**，退出包装器模式。
      // 但若这个名字按 WORKDIR 解析后正是构建写出来的文件（`COPY entry.sh sh` + `ENTRYPOINT ["sh"]`），
      // 它就是被执行的入口，不能当解释器跳过（第三十八轮 N-1）。
      if (INTERPRETER_WORD.test(word) && !writtenByBuild) {
        wrapperMode = false
        commandPosition = false
        continue
      }
      // 包装器模式下：用户名/目录/数字/选项这类词不改变 wrapperMode，真正的命令（路径或脚本名）会被收集。
      const effectiveCommandPosition = commandPosition || wrapperMode
      // shell 的赋值前缀（`FOO=1 /app/entry.sh`）与包装器的纯数字实参（`timeout 30 CMD`）不打断命令位。
      if (/^[A-Za-z_][A-Za-z0-9_]*=/.test(word)) continue
      if (/^\d+$/.test(word)) continue
      // 变量间接：包装器模式下用户名/实参已把 commandPosition 置 false，必须用 effective 位置判定，
      // 否则 `su app -c $ENTRY`、`exec gosu app $ENTRY`、`exec timeout 30s $ENTRY` 全部静默放行（FO-4）。
      if (/\$\{?[A-Za-z0-9_@*#?!$-]/.test(word)) {
        if (effectiveCommandPosition) indirect.add(`${word}（整条命令：${tail.slice(0, 50)}）`)
        commandPosition = false
        continue
      }
      // 只有**命令位**上的路径/脚本名才算入口：解释器调用（`node /app/x.js`）里的路径是数据，不收集。
      // 含 `/` 且不是选项的词都算路径（`/x`、`./x`、`../x`、`bin/tini`、`sub/entry.sh`、甚至 `/srv/run=x`）。
      // 选项形态（`-Dk=/x`、`--opt=/x`）仍被上面的 `-` 分支排除。
      const isPath = !word.startsWith('-') && word.includes('/')
      if (effectiveCommandPosition && isPath) {
        // 相对路径（`./x`、`../x`）按当前 WORKDIR 解析；绝对路径原样归一。
        const value = word.startsWith('/') ? word : path.posix.join(workdir || '/', word)
        directTargets.add(normalizeTarget(value))
        wrapperMode = false
      } else if (effectiveCommandPosition && SCRIPT_NAME.test(word)) {
        // 带扩展名的裸脚本名同样由 PATH 解析（`ENV PATH=/app` + `ENTRYPOINT ["entry.sh"]` 执行的是 /app/entry.sh）：
        // 取第一个「构建写过」的候选，否则退回 WORKDIR（第四十轮 F1）。
        const scriptCandidates = [...pathDirs.map((dir) => normalizeTarget(path.posix.join(dir, word))), normalizeTarget(`/${word}`)]
        directTargets.add(scriptCandidates.find((candidate) => writtenTarget(candidate) || runDirWrites(candidate))
          ?? normalizeTarget(workdir ? `${workdir}/${word}` : `/${word}`))
        wrapperMode = false
      } else if (effectiveCommandPosition && BARE_COMMAND.test(word) && (!INTERPRETER_WORD.test(word) || writtenByBuild)) {
        // 无扩展名裸名（靠 PATH 解析）：先看按 WORKDIR 解析后是不是构建写出来的**具体文件**
        // （`WORKDIR /usr/local/bin` + `COPY entry.sh sh` + `ENTRYPOINT ["sh"]`），是则直接当入口；
        // 否则只有上下文里确实存在同名文件时才当入口，避免把 `CMD ["nginx", …]` 误拦。
        const bareResolvedPath = normalizeTarget(path.posix.join(workdir || '/', word))
        // PATH 优先于 WORKDIR：Docker 用 PATH 查找裸名，WORKDIR 里的同名文件只是诱饵（第四十一轮 FO-1）。
        // 晋升只看 **PATH**（`pathCandidates` 里还含 WORKDIR 解析值，那个不参与晋升）。
        const barePathHit = pathSegments(workdir)
          .map((dir) => normalizeTarget(path.posix.join(dir, word)))
          .find((candidate) => writtenTarget(candidate) || runDirWrites(candidate))
        // Docker 只按 PATH 查找裸名：WORKDIR 里的同名文件不会被执行（第四十二轮 O3）。
        if (barePathHit) directTargets.add(barePathHit)
        else bareCandidates.add(word)
        // 裸名可能是包装器的用户名实参（`gosu app /app/entry.sh`）⇒ 不在这里退出包装器模式；
        // 真正的入口（路径/脚本名）被收集时才退出（OB-2）。
      }
      commandPosition = false
    }
  }
  // RUN 里的写入可能只写到目录（`install … -t /usr/local/bin`）或用相对路径（cwd=`/` 时 `install … opt/dash`），
  // 只做整串 `includes(target)` 会漏（第三十八轮 N-1/③④）。写动词 + 目录前缀同样算写入面。

  const stripLeadSlashes = (text) => text.replace(/(^|[\s'"=:])[/]+/g, '$1')
  const mentionsInRun = (target) => {
    const bare = target.replace(/^\//, '')
    const dir = path.posix.dirname(bare)
    return finalStage.some((line) => {
      if (!/^\s*RUN\b/i.test(line)) return false
      // `chmod +x /usr/local/bin/tool`、`chown` 只是改元数据，不算写入（第三十九轮过拦）。
      const redirectInto = new RegExp(`(?:&>|2>|(?:^|[^&])>)\\s*['\"]?${target.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`)
      const normalizedLine = expandRunVars(normalizeRunText(line))
      const writes = RUN_WRITE_VERB.test(normalizedLine) || redirectInto.test(normalizedLine)
      if (!writes) return false
      const targetPattern = new RegExp(`${target.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?![\\w.-])`)
      if (targetPattern.test(line) || targetPattern.test(normalizeRunText(line))) return true
      const normalized = stripLeadSlashes(line)
      // 按**简单命令段**判定：`curl …/install.sh | sh` 里的 `sh` 是管道右侧的解释器，不算写入（第四十一轮 OB-2）。
      if (runSegments(normalized).some((segment) => segmentWritesName(segment, bare))) return true
      // 目录级写入只在「目标的 basename 也出现在这条 RUN 里」时才算：
      // `install … -t /usr/local/bin` 写的是同名文件；`curl -o /usr/local/bin/wait-for-it.sh` 不会写 java（第四十三轮 P1）。
      // 解包器 + `-C/-d DIR`：内容可能落在 DIR 的任意子目录（第四十四轮 FO-3）。
      if (/\b(?:tar|bsdtar|gtar|unzip|7z)\b/.test(line)) {
        const extractDirRaw = extractDirOf(line, (value) => buildVars.get(value.replace(/^\$\{?|\}?$/g, '')) ?? value)
        // 没有显式目录 ⇒ 解到当前 WORKDIR（cwd）；`-C /` ⇒ 整根。
        const extractDir = extractDirRaw === null ? (workdir || '/') : normalizeTarget(extractDirRaw)
        if (extractDir === '/' || isUnder(target, extractDir)) return true
      }
      if (RUN_WRITE_VERB.test(line) && dir !== '.' && normalized.includes(dir)) {
        // 解包器把未知内容写进目录 ⇒ 整目录都算写入面；其它动词只有写到同名文件才算（第四十三轮 P1）。
        if (/\b(?:tar|bsdtar|gtar|unzip|7z)\b/.test(line)) return true
        if (new RegExp(`(^|[\\s'"=:/])${path.posix.basename(bare).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}([\\s'"]|$)`).test(normalized)) return true
      }
      // `RUN cp … .`：目标是当前 WORKDIR（第三十八轮 run-dir 残口）。
      if (/\b(?:cp|install|mv|ln|tar|unzip)\b[^\n]*\s\.{1,2}\/?\s*$/.test(line) && workdir) {
        return path.posix.dirname(bare) === workdir.replace(/^\//, '')
      }
      return false
    })
  }
  // dest 为 `/` 时不参与「写入面」的宽匹配：`WORKDIR /` 或 `COPY x ./` 不该让任意绝对目标都算写入面。
  const rootDirProvides = (target) => {
    const relative = relTo(target, '/')
    if (relative === null) return false
    return rootDirSources.some((source) => {
      try {
        return statSync(path.resolve(contextDir, source, relative)).isFile()
      } catch {
        return false
      }
    })
  }
  // RUN 写出的裸名（`install … sh`）解析出的目标也要算写入面：COPY/ADD 之外没有 dest 可依赖。
  const runWrittenTargets = new Set()
  const unresolvedMatches = (target) => unresolvedDests.some((dest) => path.posix.basename(dest) === path.posix.basename(target))
  const underBuildSurface = (target) => rootWriteSurface || unresolvedMatches(target) || runWrittenTargets.has(target)
    || archiveDirs.some((dir) => isUnder(target, dir))
    || rootDirProvides(target) || dests.some((dest) => dest !== '/' && isUnder(target, dest)) || mentionsInRun(target)
  const pathDirsForBare = pathSegments(workdir)
  const dirSourceProvides = (entry, target) => {
    if (!entry.isDir) return false
    const relative = relTo(target, entry.dest)
    if (relative === null || relative === '') return false
    try {
      return statSync(path.resolve(contextDir, entry.source, relative)).isFile()
    } catch {
      return false
    }
  }
  const writtenTarget = (target) => copyEntries.some((entry) => entry.dest === target
    || (entry.parents && entry.dest !== '/' && normalizeTarget(`${entry.dest}/${entry.source}`) === target)
    || (!entry.parents && entry.name && entry.dest !== '/' && `${entry.dest}/${entry.name}` === target)
    || dirSourceProvides(entry, target))
    || invisible.some((item) => item.dest === '/' || item.dest === target || isUnder(target, item.dest))
  const barePathCandidates = (resolvedBare) => {
    const name = path.posix.basename(resolvedBare)
    return [resolvedBare, ...pathDirsForBare.map((dir) => normalizeTarget(path.posix.join(dir, name)))]
  }
  // 与 mentionsInRun 同口径：整路径、写动词 + 目录前缀、以及裸名作为独立 token（`install … sh`）。
  const runWritesTarget = (target) => {
    if (mentionsInRun(target)) return true
    const name = path.posix.basename(target)
    return finalStage.some((line) => {
      if (!/^\s*RUN\b/i.test(line) || !RUN_WRITE_VERB.test(line)) return false
      return new RegExp(`(^|[\\s'"=:])${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}([\\s'"]|$)`).test(line)
    })
  }
  // SHELL / HEALTHCHECK 的审计规则（第四十二轮重构）：不再猜「哪个 token 是命令」——
  // 把整条指令里所有像路径的 token（含 `-c` 载荷、包装器之后）都取出来，
  // **只有被构建写过的**才当入口送审；其余（curl/wget/node/基础镜像工具、URL）不动。
  // 这样 `HEALTHCHECK CMD curl -f http://…` 不会被误判，而 `sh -c "exec /app/entry.sh"` 会被审到。
  for (const line of finalStage) {
    const isShellLine = /^\s*SHELL\s+/i.test(line)
    const isHealthLine = /^\s*HEALTHCHECK\b/i.test(line)
    if (!isShellLine && !isHealthLine) continue
    const text = line.replace(/^\s*(?:SHELL|HEALTHCHECK)\b/i, '').replace(/^[\s\S]*?\bCMD\b/i, '').trim()
    if (!text) continue
    let tokens = null
    if (text.startsWith('[')) {
      try {
        const parsed = JSON.parse(text)
        if (Array.isArray(parsed) && parsed.length && parsed.every((item) => typeof item === 'string')) tokens = parsed
      } catch {
        tokens = null
      }
    }
    if (!tokens) tokens = splitShellTokens(text.replace(/^\[/, '').replace(/\]$/, ''))
    // `-c` 载荷再摊一层（`sh -c "exec /app/entry.sh"`），最多 3 层，防止无限展开。
    const expanded = tokens.flatMap((token) => (/(?:^|\s).*\s/.test(token) ? splitShellTokens(token) : [token]))
    for (let depth = 0; depth < 3; depth += 1) {
      const flagIndex = expanded.findIndex((token) => token === '--command'
        || (/^-[A-Za-z]{1,4}$/.test(token) && !token.startsWith('--') && token.slice(1).includes('c')))
      if (flagIndex < 0 || flagIndex + 1 >= expanded.length) break
      const payload = expanded.slice(flagIndex + 1).join(' ')
      if (!/\s/.test(payload)) break
      expanded.splice(flagIndex + 1, expanded.length - flagIndex - 1, ...splitShellTokens(payload))
    }
    let commandSeen = false
    for (const rawToken of expanded) {
      let token = rawToken.replace(/^['"]|['"]$/g, '').replace(/[;|&]+$/, '').replace(/^[;|&]+/, '')
      if (!token || token.includes('://') || /^[A-Za-z_][A-Za-z0-9_]*=/.test(token)) continue
      // `--exec=/app/x`、`--command=/app/x`：内联值就是命令（第四十二轮 r08）。
      // `--exec=/path` 等命令类内联选项的值，无论是否已在命令位都算命令（第四十五轮）。
      const forceCommand = /^--(?:exec|startas|command|session-command|split-string)=/i.test(rawToken)
      const inlineValue = token.match(/^--[A-Za-z][A-Za-z-]*=(.+)$/)
      if (inlineValue) token = inlineValue[1]
      // `-c/app/entry.sh`：粘连形式（第四十四轮 FO-6）。
      const gluedShort = token.match(/^-[A-Za-z]{1,4}(\/.*)$/)
      if (gluedShort) token = gluedShort[1]
      if (!token || token.startsWith('-')) continue
      // `timeout 5 CMD` 的数字是包装器实参，不是命令（第四十五轮）。
      if (/^\d+$/.test(token)) continue
      // `su app -c CMD`：`-c` 之后重新进入命令位。
      if (/^-[A-Za-z]{0,4}c[A-Za-z]{0,4}$/.test(token) && !token.startsWith('--')) { commandSeen = false; continue }
      // 先记下「当前 token 是否在命令位」，再决定是否推进 commandSeen（第四十五轮）。
      if (!commandSeen && /^(?:exec|command|!|then|do|else)$/.test(token)) continue
      const atCommandPosition = !commandSeen
      if (atCommandPosition && /\$\{?[A-Za-z0-9_@*#?!$-]/.test(token)) { indirect.add(`${token}（SHELL/HEALTHCHECK）`); continue }
      // 只有 shell/包装器本身不算「命令已出现」；`test`/`echo`/`curl` 这类就是命令，其参数是数据。
      const NON_SCRIPT_COMMAND = /^(?:sh|bash|zsh|ksh|ash|dash|env|exec|command|sudo|time|timeout|tini|dumb-init|su|gosu|setpriv|chroot|xargs)$/i
      if (!NON_SCRIPT_COMMAND.test(path.posix.basename(token))) commandSeen = true
      if (token.includes('/')) {
        const target = normalizeTarget(token.startsWith('/') ? token : path.posix.join(workdir || '/', token))
        // 只认**精确文件**被写过（COPY dest / RUN 写同名 / 不可见来源）或整根写入面；
        // 不能用 `underBuildSurface` 的目录前缀——`COPY ok.sh /app/ok.sh` 会让 `test -f /app/.ready` 被判死（第四十三轮）。
        // 只审脚本类入口：`test -s /app/app.jar`、`--log=/app/x.log` 这类数据/产物不当入口（第四十四轮 OB-3）。
        const baseName = path.posix.basename(target)
        const looksScript = SCRIPT_NAME.test(baseName) || !/^[^.]+\./.test(baseName) && !baseName.startsWith('.')
        // 只在**命令位**审：`test -f /app/probe` 里的路径是数据（第四十五轮过拦）。
        if ((atCommandPosition || forceCommand) && looksScript && (writtenTarget(target) || runWritesTarget(target) || rootWriteSurface
          || archiveDirs.some((dir) => isUnder(target, dir)))) directTargets.add(target)
        continue
      }
      if (!BARE_COMMAND.test(token)) continue
      // 裸名：按 PATH 解析，命中构建写过的候选才审（与 ENTRYPOINT 同口径）。
      // 所有命中都要审：只看第一个会把 `/usr/local/sbin/sh`（基础镜像自带）当结论，漏掉真正被写的 `/usr/local/bin/sh`。
      const hits = pathSegments(workdir)
        .map((dir) => normalizeTarget(path.posix.join(dir, token)))
        .filter((candidate) => writtenTarget(candidate) || runWritesTarget(candidate)
          || rootWriteSurface || archiveDirs.some((dir) => isUnder(candidate, dir)))
      for (const hit of hits) directTargets.add(hit)
    }
  }

  for (const candidate of new Set([...bareCandidates, ...execBareCandidates])) {
    // 裸名在构建上下文里**按名字**存在 ⇒ 它的镜像路径 = 当前 WORKDIR + 名字（与 COPY 的 dest 对齐）。
    const resolvedBare = normalizeTarget(workdir ? path.posix.join(workdir, candidate) : `/${candidate}`)
    const contextPath = path.resolve(contextDir, candidate)
    let bareStat = null
    try {
      bareStat = statSync(contextPath)
    } catch {
      bareStat = null
    }
    // 上下文里没有同名文件：看 PATH 解析的落点有没有被构建写过（`COPY entry.sh /usr/local/bin/sh` + `ENTRYPOINT ["sh"]`），
    // 以及 RUN 有没有把这个名字写到写入面里（`RUN install … /usr/local/bin/sh`、`RUN ln -s … sh`）（第三十九轮 FO-A/FO-D）。
    // 只看 PATH 目录（不含 WORKDIR 解析值）：`COPY hack sh` 落到的 /app/sh 不会被执行（第四十二轮 p15）。
    const pathHit = pathDirsForBare.map((dir) => normalizeTarget(path.posix.join(dir, candidate)))
      .find((target) => writtenTarget(target) || runWritesTarget(target))
    if (pathHit) {
      runWrittenTargets.add(pathHit)
      directTargets.add(pathHit)
      continue
    }
    // 构建把**整根**都解包了（`ADD 归档 /`）⇒ 这个名字可能是包里的脚本（第三十八轮 N-1b）。
    if (rootWriteSurface) { directTargets.add(resolvedBare); continue }
    // `--exec=entry.sh`：语义上是路径，允许按 WORKDIR + 上下文同名文件解析（第三十三轮 F2）。
    if (execBareCandidates.has(candidate) && bareStat !== null && bareStat.isFile() && insideRepo(contextPath)) {
      directTargets.add(resolvedBare)
    }
  }
  const sources = new Set()
  const unscannable = new Set()
  const heredocScripts = []
  for (const stage of emptyBaseStages) {
    unscannable.add(`第 ${stage.start + 1} 行的 FROM 基镜像为空（构建必然失败：空 FROM）`)
  }
  for (const tag of unterminatedHeredocs) {
    unscannable.add(`heredoc 缺少结束标签 ${tag}（构建会失败：unterminated heredoc）`)
  }
  const resolveCandidate = (relative) => {
    const candidate = path.resolve(contextDir, relative)
    if (!insideRepo(candidate) || !existsSync(candidate)) return null
    return candidate
  }
  for (const target of directTargets) {
    // 非文本扩展名只跳过**内容扫描**，不跳过可见性判定：`entry.sh` 改名成 `entry.bin` 一样能被 execve（FO-2）。
    const nonText = NON_TEXT_EXT.test(target)
    let hasVisible = false
    let hasInvisible = false
    for (const [dest, sourceList] of copyMap) {
      const tail = relTo(target, dest)
      if (tail === null) continue
      for (const source of sourceList) {
        if (source.heredocText !== undefined) {
          hasVisible = true
          heredocScripts.push({ target, text: source.heredocText })
          continue
        }
        if (source.parents) {
          const normalizedTail = path.posix.normalize(tail).replace(/^\.\//, '')
          const normalizedSource = path.posix.normalize(source.source).replace(/^\.\//, '')
          if (!source.isDir && normalizedTail !== normalizedSource) continue
          const relative = source.isDir ? tail : source.source
          if (!relative) continue
          const candidate = resolveCandidate(relative)
          if (candidate) {
            hasVisible = true
            if (!nonText) sources.add(candidate)
          }
          continue
        }
        if (source.isDir) {
          const base = source.source.replace(/\/$/, '').replace(/^\.$/, '')
          const relative = tail ? path.posix.join(base, tail) : ''
          if (!relative) continue
          const candidate = resolveCandidate(relative)
          if (candidate) {
            hasVisible = true
            sources.add(candidate)
          }
          continue
        }
        if (tail === '' || tail === path.posix.basename(source.source)) {
          const candidate = resolveCandidate(source.source)
          if (candidate) {
            hasVisible = true
            if (!nonText) sources.add(candidate)
          }
        }
      }
    }
    for (const item of invisible) {
      // `--from`/远程源落到 `/` ⇒ 整根内容不可知（不能当基础镜像自带）；本地目录源到 `/` 由 rootDirProvides 精确判定。
      if (item.dest === '/' || isUnder(target, item.dest)) hasInvisible = true
    }
    if (hasInvisible) {
      const reason = invisible.find((i) => isUnder(target, i.dest))?.reason
      unscannable.add(`ENTRYPOINT/CMD 直接执行的 ${target} 由不可见来源提供（${reason}）：内容在预检阶段不可见`)
      continue
    }
    if (hasVisible) {
      // 可见但内容不是文本（.bin/.so/.jar…）：静态审计不了 ⇒ fail-closed（FO-2）。
      if (nonText) unscannable.add(`ENTRYPOINT/CMD 直接执行的 ${target} 是非文本文件（${path.posix.extname(target) || '无扩展名'}）：内容无法静态审计`)
      continue
    }
    if (underBuildSurface(target) || !BASE_IMAGE_DIR.test(target)) {
      unscannable.add(`ENTRYPOINT/CMD 直接执行的 ${target} 既无 COPY/ADD 来源、构建上下文里也不存在（内容在预检阶段不可见）`)
    }
  }
  for (const item of indirect) {
    unscannable.add(`ENTRYPOINT/CMD 用变量间接指定入口（${item}）：内容在预检阶段不可见`)
  }
  return { sources: [...sources], unscannable: [...unscannable], heredocScripts }
}

function checkResolved(opts, resolved, envFileValues, mechanismView) {
  const checks = []
  const record = (id, status, detail) => checks.push({ id, status, detail: sanitizeDetail(detail) })
  const composeDir = path.dirname(path.resolve(REPO_ROOT, opts.composeFile))
  const tokenHit = (text) => FORBIDDEN_TOKENS.find((token) => text.toLowerCase().includes(token))

  if (resolved.name !== EXPECTED_PROJECT) {
    record('project-name', 'FAIL', `project 名为 ${resolved.name}，期望 ${EXPECTED_PROJECT}`)
  } else {
    record('project-name', 'PASS', `project = ${EXPECTED_PROJECT}`)
  }

  const services = resolved.services ?? {}
  const identifierProblems = []
  const lowerServiceNames = new Map()
  for (const name of Object.keys(services)) {
    const lower = name.toLowerCase()
    if (lowerServiceNames.has(lower)) {
      identifierProblems.push(`服务名 ${name} 与 ${lowerServiceNames.get(lower)} 仅大小写不同：Docker DNS 记录小写化时会形成同名解析持有者`)
    }
    lowerServiceNames.set(lower, name)
    // 服务名本身也不允许与保留名/数据源白名单主机名仅大小写不同（例如服务名 MYSQL / LocalHost）。
    if (name !== lower && (RESERVED_DB_HOST_NAMES.has(lower) || RESERVED_SERVICE_NAMES.has(lower))) {
      identifierProblems.push(`服务名 ${name} 与保留名 ${lower} 仅大小写不同：可形成 mysql/localhost 等保留名的第二个解析持有者`)
    }
  }
  // 服务声明 profiles 会让未激活时的解析结果里完全没有该服务（预检看不见）——隔离栈不需要 profiles，声明即失败。
  // 空数组 profiles: [] 不会进入解析结果，因此还要在原始文本层面查一遍。
  for (const [name, service] of Object.entries(services)) {
    if (service.profiles != null) {
      identifierProblems.push(`${name} 声明 profiles：未激活时该服务不会出现在解析结果里，预检无法覆盖`)
    }
  }
  const rawComposeLines = composeTextVariants(readFileSync(path.resolve(REPO_ROOT, opts.composeFile))).flatMap((text) => splitComposeLines(text))
  rawComposeLines.forEach((line, index) => {
    if (PROFILES_KEY_POSITION.test(stripLineComment(line))) {
      identifierProblems.push(`栈定义第 ${index + 1} 行声明了 profiles：预检按全部 profile 生效解析，隔离栈不应使用 profiles`)
    }
  })
  for (const [name, service] of Object.entries(services)) {
    const containerName = service.container_name ?? ''
    if (tokenHit(name) || (containerName && tokenHit(containerName))) {
      identifierProblems.push(`service ${name} / container_name ${containerName} 命中旧资源标识`)
    }
    if (containerName && !containerName.startsWith(`${EXPECTED_IDENTIFIER_PREFIX}-`)) {
      identifierProblems.push(`container_name ${containerName} 未使用 ${EXPECTED_IDENTIFIER_PREFIX}- 前缀`)
    }
    // container_name 同样会进入同网 DNS：不允许占用数据源白名单主机名或别的服务名。
    if (containerName && name !== 'mysql' && RESERVED_DB_HOST_NAMES.has(containerName.toLowerCase())) {
      identifierProblems.push(`container_name ${containerName} 与数据源白名单主机名冲突：同网其它容器可据此抢占平台的数据源目标解析`)
    }
    const clashingService = Object.keys(services)
        .find((other) => other !== name && other.toLowerCase() === containerName.toLowerCase())
    if (containerName && clashingService) {
      identifierProblems.push(`container_name ${containerName} 与另一个服务名 ${clashingService} 冲突`)
    }
  }
  for (const [key, volume] of Object.entries(resolved.volumes ?? {})) {
    const name = typeof volume === 'object' && volume ? volume.name : null
    if (tokenHit(key) || (name && tokenHit(name))) identifierProblems.push(`volume ${key} 命中旧资源标识`)
    if (!name || !String(name).startsWith(`${EXPECTED_IDENTIFIER_PREFIX}-`)) {
      identifierProblems.push(`volume ${key} 未显式命名 ${EXPECTED_IDENTIFIER_PREFIX}- 前缀（禁止匿名/项目前缀隐式命名）`)
    }
  }
  for (const [key, network] of Object.entries(resolved.networks ?? {})) {
    const name = typeof network === 'object' && network ? network.name : null
    if (tokenHit(key) || (name && tokenHit(name))) identifierProblems.push(`network ${key} 命中旧资源标识`)
    if (name && !String(name).startsWith(`${EXPECTED_IDENTIFIER_PREFIX}-`)) {
      identifierProblems.push(`network ${name} 未使用 ${EXPECTED_IDENTIFIER_PREFIX}- 前缀`)
    }
  }
  record('identifiers', identifierProblems.length ? 'FAIL' : 'PASS', identifierProblems.length ? identifierProblems.join('；') : '容器/卷/网络命名均在隔离前缀内')

  const envProblems = []
  const overrideProblems = []
  const endpointProblems = []
  // 端点检查独立成项（第十三轮复核：这些失败此前记在 datasource-override-keys 名下，语义不符）。
  // 逐值判定之外再做**按服务聚合**判定：主机与端口拆到同一服务的不同值里也拦得住
  // （例如 `..._BASE_URL: http://host.docker.internal/` 与另一个值为 `--port=3306`）。
  const serviceTexts = new Map()
  // 聚合判定前先剥掉**允许的隔离目标**（`mysql:3306`、`127.0.0.1:13306` 等）：3306 属于旧栈端口集合，
  // 但在这个栈里是 mysql 容器的自身端口，若不剥离会让「平台连自己的库」被聚合规则误判。
  const ALLOWED_TARGET_STRIP = /(?:localhost|127\.0\.0\.1)[:/]\d{2,5}|mysql:3306/gi
  const collectServiceText = (name, value) => {
    if (value == null) return
    const text = typeof value === 'string' ? value : JSON.stringify(value)
    serviceTexts.set(name, `${serviceTexts.get(name) ?? ''} ${text}`)
  }
  for (const [name, service] of Object.entries(services)) {
    for (const [key, value] of Object.entries(service.environment ?? {})) {
      const text = String(value ?? '')
      // APP_PORT 是容器自身端口（192.168.1.x/8080 这类「宿主:容器」里的容器侧），属于栈的合法自身声明，
      // 收进聚合文本会与本栈容器端口（8080/3306）混淆，这里跳过；它的值由别的检查负责。
      if (!/^APP_PORT$/i.test(key)) collectServiceText(name, text)
      const hit = tokenHit(text)
      if (hit) envProblems.push(`${name}.${key} 的值命中旧资源标识（${hit}）`)
      if (/^jdbc:/i.test(text)) envProblems.push(...jdbcProblems(name, key, text))
      if (DATASOURCE_OVERRIDE_KEY.test(key)) {
        overrideProblems.push(`${name}.${key} 直接设置 Spring 数据源/Flyway 属性或改写配置来源：必须只经 APP_DB_URL 配置，否则会绕开隔离校验`)
      }
      const normalizedText = normalizeLegacyEndpointText(text)
      const legacyMatch = text.match(LEGACY_ENDPOINT) ?? normalizedText.match(LEGACY_ENDPOINT)
      if (legacyMatch) {
        endpointProblems.push(`${name}.${key} 的值指向旧栈宿主端点（${legacyMatch[0]}）：隔离环境不得使用旧栈端口`)
      }
      // 环境值也要过非冒号形态（第十一轮复核实测：environment 值此前完全不经过 LOOSE 检查）。
      const looseHit = looseLegacyEndpointMatch(text) ?? looseLegacyEndpointMatch(normalizedText)
      if (looseHit && !legacyMatch) {
        endpointProblems.push(`${name}.${key} 的值以非冒号写法指向旧栈宿主端点（${looseHit}）：隔离环境不得使用旧栈端口`)
      }
    }
    collectServiceText(name, service.healthcheck?.test)
    collectServiceText(name, service.command)
    collectServiceText(name, service.entrypoint)
    collectServiceText(name, service.build?.args)
    // 聚合文本也要过等价归一（第十五轮复核实测：bracketed IPv4-mapped 与百分号编码宿主写在单个值里会被拦，
    // 拆到同一服务的两个值就漏判——因为聚合此前只跑原始文本）。
    const aggregateText = (serviceTexts.get(name) ?? '').replace(ALLOWED_TARGET_STRIP, ' ')
    const aggregateHit = looseLegacyEndpointMatch(aggregateText)
      ?? looseLegacyEndpointMatch(normalizeLegacyEndpointText(aggregateText))
    if (aggregateHit) {
      endpointProblems.push(`${name} 的多个值合起来出现非 loopback 主机与旧栈端口（${aggregateHit}）：拆分到不同值的等价写法同样被拒`)
    }
  }
  record('environment-values', envProblems.length ? 'FAIL' : 'PASS', envProblems.length ? envProblems.join('；') : `环境变量无旧资源引用，MySQL URL 只指向 ${EXPECTED_DB_NAME} 的隔离目标`)
  // 第十五轮复核：Runner 的私网开关此前只受**键名**允许清单约束，取值从不校验——而 runner 侧语义是
  // `env[KEY] !== 'false'` 即**开启**（默认开），因此删掉这一行、写成 "FALSE"/"0" 一律会静默放宽出站范围。
  // 第十六轮复核：runner 有**两个**这种语义的出站开关（登录发现私网放行同族），且删掉整个 web-runner 服务时
  // 本检查会空洞通过 ⇒ 两个开关都要求显式声明且恰为小写 false，并要求 web-runner 服务存在（见 required-services）。
  const egressProblems = []
  const runnerService = services['web-runner']
  if (!runnerService) {
    // 第十七轮复核：服务缺失时不能只靠 required-services 兜底——本项自身也必须判 FAIL，
    // 否则它会打印「两个出站开关都已显式关闭」这种无承载者的肯定结论。
    egressProblems.push('web-runner 服务不存在：无法断言出站开关状态（针对缺失服务的性质不成立）')
  } else {
    const env = runnerService.environment ?? {}
    for (const key of EGRESS_KEYS) {
      if (!Object.prototype.hasOwnProperty.call(env, key)) {
        egressProblems.push(`web-runner 未声明 ${key}：Runner 侧语义是「未设置即开启」，删行会静默放宽出站范围`)
      } else if (String(env[key]) !== 'false') {
        egressProblems.push(`web-runner 的 ${key}=${String(env[key])}：隔离栈必须恰为小写 false（Runner 只认小写 false 才关闭）`)
      }
    }
  }
  record('runner-egress-policy', egressProblems.length ? 'FAIL' : 'PASS', egressProblems.length ? egressProblems.join('；') : 'Runner 两个出站开关（本地网络访问、登录发现私网放行）在栈定义里都显式关闭（恰为 false），不会因删行/大小写/其它取值静默放宽出站范围')
  // 第十六轮复核：删除整个 web-runner 服务后，上面那条检查会因服务不存在而空洞通过（并打印「已显式关闭」）。
  // 因此这里统一要求预期服务集合必须齐全——服务不被声明时，绑定在它上面的任何性质都不该被宣称成立。
  const missingServices = [...EXPECTED_SERVICES].filter((name) => !services[name])
  record('required-services', missingServices.length ? 'FAIL' : 'PASS', missingServices.length ? `栈定义缺少预期服务：${missingServices.join('、')}（针对缺失服务的一切检查都不成立）` : `预期服务齐全：${[...EXPECTED_SERVICES].join('、')}`)
  record('legacy-endpoints', endpointProblems.length ? 'FAIL' : 'PASS', endpointProblems.length ? endpointProblems.join('；') : '环境值里没有指向旧栈宿主端点的写法（含冒号与非冒号/带选项/等价宿主写法）')

  // 第九轮复核：旧端点检查此前只覆盖 environment 值——healthcheck.test（容器内会真正执行的命令）、
  // command、entrypoint、build.args（会把值烘焙进镜像，永不出现在运行时环境里）等值面全部零拦截。
  // 这里对所有字符串值做一遍（归一后）旧端点检测与旧资源标识检测。
  const surfaceProblems = []
  const visitValue = (path, value) => {
    const hit = tokenHit(value)
    if (hit) surfaceProblems.push(`${path} 的值命中旧资源标识（${hit}）`)
    const normalized = normalizeLegacyEndpointText(value)
    const legacy = value.match(LEGACY_ENDPOINT) ?? normalized.match(LEGACY_ENDPOINT)
    if (legacy) surfaceProblems.push(`${path} 的值指向旧栈宿主端点（${legacy[0]}）：隔离环境不得使用旧栈端口`)
    // 非冒号等价写法（/dev/tcp、nc、-P、带选项间隔等）：第十一/十二轮复核实测这些形态逃过上一版检测。
    const loose = looseLegacyEndpointMatch(value) ?? looseLegacyEndpointMatch(normalized)
    if (loose) surfaceProblems.push(`${path} 的值以非冒号写法指向旧栈宿主端点（${loose}）：隔离环境不得使用旧栈端口`)
  }
  for (const [name, service] of Object.entries(services)) {
    for (const [key, value] of Object.entries(service)) {
      if (key === 'environment') continue // 已由 environment-values / datasource-override-keys 覆盖
      walkStrings(value, `${name}.${key}`, visitValue)
    }
  }
  for (const kind of ['volumes', 'networks']) {
    for (const [key, entry] of Object.entries(resolved[kind] ?? {})) {
      walkStrings(entry, `${kind}.${key}`, visitValue)
    }
  }
  record('value-surface', surfaceProblems.length ? 'FAIL' : 'PASS', surfaceProblems.length ? surfaceProblems.join('；') : '服务定义（含 healthcheck/command/build.args 等）与卷/网络的字符串值里没有旧资源标识或旧栈端点')
  record('datasource-override-keys', overrideProblems.length ? 'FAIL' : 'PASS', overrideProblems.length ? overrideProblems.join('；') : '没有直接覆盖 Spring 数据源属性的环境变量')

  const portProblems = []
  const seen = new Map()
  // 端口条目的子键集合也要落在允许清单内（第十四轮复核：`app_protocol`/`name` 这类条目标键此前不被任何一层检查）。
  const ALLOWED_PORT_ENTRY_KEYS = ['target', 'published', 'host_ip', 'protocol', 'mode']
  for (const [name, service] of Object.entries(services)) {
    // 第十五轮复核：APP_PORT 的值此前零校验（裸端口不触发共现规则、聚合又按键名跳过），而它是容器自身端口。
    // 这里要求它等于该服务发布映射里的容器侧 target，从而排除 `APP_PORT: 3306` 这类借自身端口名义写旧栈端口。
    const appPort = service.environment?.APP_PORT
    if (appPort != null) {
      const targets = new Set()
      for (const entry of service.ports ?? []) {
        if (entry && typeof entry === 'object' && entry.target != null) targets.add(String(entry.target).trim())
      }
      const text = String(appPort).trim()
      if (!/^\d+$/.test(text)) {
        portProblems.push(`${name} 的 APP_PORT=${text} 不是单一整数端口`)
      } else if (targets.size > 0 && !targets.has(text)) {
        portProblems.push(`${name} 的 APP_PORT=${text} 与服务发布映射的容器侧端口 ${[...targets].join('/')} 不一致`)
      }
    }
    for (const entry of publishedPortEntries(service)) {
      for (const key of entry.keys) {
        if (!ALLOWED_PORT_ENTRY_KEYS.includes(key)) {
          portProblems.push(`${name} 的端口条目声明了未允许的子键 ${key}（允许：${ALLOWED_PORT_ENTRY_KEYS.join('/')}）`)
        }
      }
      // 去重按「端口 + 协议」：同号 TCP/UDP 并存互不冲突（第十四轮复核指出此前的过拦）。
      const published = entry.published
      if (/^\d+$/.test(published)) {
        const key = `${published}/${entry.protocol}`
        if (LEGACY_HOST_PORTS.includes(Number(published))) {
          portProblems.push(`${name} 发布宿主端口 ${published} 属于旧栈端口`)
        }
        if (seen.has(key)) portProblems.push(`${name} 与 ${seen.get(key)} 同时发布宿主端口 ${key}`)
        else seen.set(key, name)
      }
      // 第十三轮复核：宿主侧暴露范围此前完全不被检查——`host_ip` 缺省时 Docker 绑 0.0.0.0（整个局域网可达），
      // 而显式写 0.0.0.0 / 局域网 IP 与写 127.0.0.1 在门里毫无区别。隔离栈只允许本机回环绑定。
      if (entry.hostIp !== '127.0.0.1') {
        portProblems.push(`${name} 发布端口 ${published || '?'} 的宿主绑定地址为 ${entry.hostIp || '(未显式绑定，Docker 默认绑 0.0.0.0)'}：隔离栈只允许 127.0.0.1（避免暴露到局域网）`)
      }
    }
  }
  record('ports', portProblems.length ? 'FAIL' : 'PASS', portProblems.length ? portProblems.join('；') : `发布端口 ${[...seen.keys()].sort().join(', ')} 未复用旧栈端口、互不冲突，且都只绑 127.0.0.1`)

  const mountProblems = []
  // 解析结果里挂载点引用的是 compose 顶层的卷 key，顶层卷条目才带真实 name，两者都算已声明。
  const declaredVolumes = new Set()
  for (const [key, volume] of Object.entries(resolved.volumes ?? {})) {
    declaredVolumes.add(key)
    const name = typeof volume === 'object' && volume ? volume.name : null
    if (name) declaredVolumes.add(String(name))
  }
  for (const [name, service] of Object.entries(services)) {
    for (const mount of service.volumes ?? []) {
      const type = mount.type ?? 'volume'
      const source = mount.source ?? ''
      if (type === 'bind') {
        const resolvedSource = path.resolve(composeDir, source)
        // 唯一的例外：预检结果只读挂载（compose 文件同级 .preflight/ 下的固定文件名 + 固定目标 + read_only）。
        const isPreflightResultMount = resolvedSource === path.join(composeDir, '.preflight', 'isolated-preflight.json')
          && mount.target === PREFLIGHT_RESULT_MOUNT_TARGET
          && mount.read_only === true
        if (isPreflightResultMount) {
          continue // 唯一的例外：预检结果只读挂载（精确路径 + 精确目标 + read_only）
        }
        const allowed = opts.allowMountPrefixes.some((prefix) => resolvedSource.startsWith(prefix))
        if (/\.hermes(\/|$)/.test(resolvedSource)) mountProblems.push(`${name} 绑定挂载 ${resolvedSource} 指向 Hermes home`)
        else if (!allowed) mountProblems.push(`${name} 含宿主绑定挂载 ${source}（默认不允许；只有预检结果可用固定路径只读挂载，其他需 --allow-mount-prefix 显式放行）`)
      } else if (type === 'volume') {
        if (source && !declaredVolumes.has(source) && !source.startsWith(EXPECTED_IDENTIFIER_PREFIX)) {
          mountProblems.push(`${name} 使用未声明的卷 ${source}`)
        }
      } else {
        mountProblems.push(`${name} 存在 ${type} 类型挂载，需人工确认`)
      }
    }
  }
  record('mounts', mountProblems.length ? 'FAIL' : 'PASS', mountProblems.length ? mountProblems.join('；') : '仅使用显式命名卷，无宿主个人目录挂载')

  // 卷名合法不代表数据落点合法：driver_opts 可把命名卷写成宿主目录绑定，driver 可把数据交给第三方驱动。
  const volumeProblems = []
  for (const [key, volume] of Object.entries(resolved.volumes ?? {})) {
    if (!volume || typeof volume !== 'object') continue
    const driverOpts = volume.driver_opts
    if (driverOpts && Object.keys(driverOpts).length > 0) {
      volumeProblems.push(`卷 ${key} 声明了 driver_opts（${Object.keys(driverOpts).join('/')}）：数据落点可被指向任意宿主路径，需人工确认`)
    }
    if (volume.driver != null && String(volume.driver) !== '' && String(volume.driver) !== 'local') {
      volumeProblems.push(`卷 ${key} 使用驱动 ${volume.driver}：数据落点由第三方驱动决定（只允许 local）`)
    }
  }
  record('volume-driver-opts', volumeProblems.length ? 'FAIL' : 'PASS', volumeProblems.length ? volumeProblems.join('；') : '命名卷未使用 driver_opts 或第三方驱动改写宿主落点')

  const networkProblems = []
  for (const [name, service] of Object.entries(services)) {
    const mode = service.network_mode
    if (mode == null) continue
    const normalized = String(mode).toLowerCase()
    if (normalized === 'host') {
      networkProblems.push(`${name} 使用 network_mode: host：容器共享宿主网络栈，可直接访问宿主上的旧服务，且发布端口规则失效`)
    } else if (String(mode) !== `${EXPECTED_IDENTIFIER_PREFIX}-net`) {
      networkProblems.push(`${name} 使用 network_mode: ${mode}，只允许隔离网络 ${EXPECTED_IDENTIFIER_PREFIX}-net`)
    }
  }
  // 网络层的落点同样可被改写：driver（macvlan 等）可把容器直接接到物理网卡，driver_opts 可钉到既有宿主桥，
  // ipam 可指定旧栈网段（第六轮复核实测这三项全部放行）。
  for (const [key, network] of Object.entries(resolved.networks ?? {})) {
    if (!network || typeof network !== 'object') continue
    if (network.driver != null && String(network.driver) !== '' && String(network.driver) !== 'bridge') {
      networkProblems.push(`网络 ${key} 使用驱动 ${network.driver}：只允许默认 bridge（macvlan 等可让容器直接进入宿主物理网络）`)
    }
    const driverOpts = network.driver_opts
    if (driverOpts && Object.keys(driverOpts).length > 0) {
      networkProblems.push(`网络 ${key} 声明 driver_opts（${JSON.stringify(driverOpts)}）：可把隔离网络钉到既有宿主网桥`)
    }
    const ipamConfig = network.ipam?.config
    if (Array.isArray(ipamConfig) && ipamConfig.length > 0) {
      networkProblems.push(`网络 ${key} 声明 ipam（${JSON.stringify(ipamConfig)}）：可指定旧栈网段或与旧栈冲突的地址空间`)
    }
  }
  record('network-mode', networkProblems.length ? 'FAIL' : 'PASS', networkProblems.length ? networkProblems.join('；') : '未使用 host/外部网络模式，隔离网络未改写驱动、driver_opts 或 ipam')

  const databaseDefinition = []
  const mysqlDatabase = (services.mysql?.environment ?? {}).MYSQL_DATABASE
  if (mysqlDatabase && String(mysqlDatabase) !== EXPECTED_DB_NAME) {
    databaseDefinition.push(`mysql.MYSQL_DATABASE=${mysqlDatabase} 与期望库 ${EXPECTED_DB_NAME} 不一致（会额外建库并与应用目标分叉）`)
  }
  // 网络也可以 external：预建网络的成员不可验证，与 external 卷同一立场，禁止。
  for (const [key, network] of Object.entries(resolved.networks ?? {})) {
    if (network && typeof network === 'object' && network.external) {
      databaseDefinition.push(`网络 ${key} 声明 external: true：预建网络的成员不可验证（可含旧资源或抢名容器）`)
    }
  }
  record('database-definition', databaseDefinition.length ? 'FAIL' : 'PASS', databaseDefinition.length ? databaseDefinition.join('；') : `库定义与应用数据源一致（${EXPECTED_DB_NAME}），网络非 external`)

  // 门校验的是「名字」，因此必须同时约束名字背后的服务身份与名字能否被改写（第二轮复核实测的绕过面）。
  const topologyProblems = []
  const database = services.mysql
  if (!database) {
    topologyProblems.push('缺少 mysql 服务')
  } else {
    const image = String(database.image ?? '')
    if (!DATABASE_IMAGE.test(image)) {
      topologyProblems.push(`mysql 服务镜像为 ${image || '(空)'}，只允许官方 mysql:8.4（可带 digest）：指向旧库的代理/自定义镜像不得占用该服务名`)
    }
    const mysqlCommand = Array.isArray(database.command) ? database.command.join(' ') : String(database.command ?? '')
    if (mysqlCommand && !ALLOWED_MYSQL_COMMAND.test(mysqlCommand.trim())) {
      topologyProblems.push(`mysql 服务 command 含未允许的参数：${mysqlCommand.trim()}（只允许 --character-set-server / --collation-server）`)
    }
  }
  for (const [name, service] of Object.entries(services)) {
    // 第九轮复核：门只比对 image 字符串 ⇒ mysql 加一个 build 段就能从仓库内代码构建并打上 mysql:8.4 标签；
    // 非 mysql 服务则可以直接换成任意第三方镜像。这里把「谁提供进程」钉死。
    const hasBuild = service.build != null && typeof service.build === 'object'
    const image = service.image == null ? '' : String(service.image)
    if (name === 'mysql') {
      if (hasBuild) {
        topologyProblems.push('mysql 声明 build：会从仓库内代码构建并打上同名标签，绕过「只允许官方镜像」的身份约束')
      }
    } else {
      if (!hasBuild) {
        topologyProblems.push(`${name} 没有 build 段：非 mysql 服务必须由仓库内构建产生，不得直接使用外部镜像（${image || '未声明 image'}）`)
      }
      if (image) {
        // 第十轮复核：前缀规则挡不住「前缀合法但带 registry/命名空间」的镜像名
        // （haitlify-isolated-mirror.example:5000/platform:1、haitlify-isolated-foo/bar:1），
        // 而 compose 在 build+image 且未写 pull_policy 时**先拉取再回落构建**（--dry-run 实证）。
        // 因此非 mysql 服务一律不得声明 image：镜像名交给 compose 按项目名自动生成（haitlify-isolated-<service>）。
        topologyProblems.push(`${name} 声明 image: ${image}：非 mysql 服务的镜像必须由本栈构建，不得声明 image（否则 Compose 会先尝试拉取该名字对应的外部镜像）`)
      }
    }
    if (service.entrypoint != null) {
      topologyProblems.push(`${name} 覆盖 entrypoint：可以换掉实际进程（含转发器/非本应用进程）`)
    }
    if (service.command != null && name !== 'mysql') {
      const command = Array.isArray(service.command) ? service.command.join(' ') : String(service.command)
      topologyProblems.push(`${name} 覆盖 command：${command}`)
    }
    if (service.extra_hosts && Object.keys(service.extra_hosts).length > 0) {
      topologyProblems.push(`${name} 声明 extra_hosts（${JSON.stringify(service.extra_hosts)}）：可改写被允许主机名的解析结果`)
    }
    if (Array.isArray(service.links) && service.links.length > 0) topologyProblems.push(`${name} 声明 links`)
    if (Array.isArray(service.external_links) && service.external_links.length > 0) {
      topologyProblems.push(`${name} 声明 external_links：引用本栈之外的外部容器`)
    }
    for (const [networkName, networkConfig] of Object.entries(service.networks ?? {})) {
      if (networkName !== 'default') {
        topologyProblems.push(`${name} 接入非隔离网络 ${networkName}`)
      }
      const config = networkConfig && typeof networkConfig === 'object' ? networkConfig : {}
      const aliases = config.aliases ?? []
      for (const alias of aliases) {
        // Docker 的 DNS 记录会做小写化，因此别名比较必须大小写不敏感（第三轮复核发现大写可绕过）；
        // 第七轮复核：别名路径此前只查保留服务名，未查数据源白名单主机名（如 LocalHost）。
        if (isReservedOrServiceName(alias, services) || RESERVED_DB_HOST_NAMES.has(String(alias).toLowerCase())) {
          topologyProblems.push(`${name} 在网络 ${networkName} 上用别名 ${alias} 抢占保留服务名（同网其他容器可被解析到该代理）`)
        }
      }
      // 第七/八轮复核：服务级网络接入面（静态地址、link-local 路由、优先级、接口名、MAC、per-endpoint 驱动选项）。
      for (const key of ['link_local_ips', 'ipv4_address', 'ipv6_address', 'priority', 'mac_address', 'driver_opts', 'interface_name', 'gw_priority']) {
        const value = config[key]
        if (value != null && (Array.isArray(value) ? value.length > 0 : String(value).length > 0)) {
          topologyProblems.push(`${name} 在网络 ${networkName} 上声明 ${key}: ${JSON.stringify(value)}（可静态占位、加宿主 link-local 路由或改写接口/驱动选项）`)
        }
      }
    }
    // 解析配置改写与 link-local 也可在服务级声明。
    for (const key of ['dns', 'dns_search', 'dns_opt', 'link_local_ips']) {
      const value = service[key]
      if (value != null && (Array.isArray(value) ? value.length > 0 : String(value).length > 0)) {
        topologyProblems.push(`${name} 声明 ${key}: ${JSON.stringify(value)}（隔离栈不需要改写解析配置或加 link-local 路由）`)
      }
    }
    if (service.hostname != null) {
      // 容器 hostname 会进入同网 DNS 记录，同样可用于抢占服务名解析。
      topologyProblems.push(`${name} 声明 hostname: ${service.hostname}（会进入同网 DNS 记录且隔离栈不需要自定义 hostname）`)
    }
  }
  record('service-identity-and-topology', topologyProblems.length ? 'FAIL' : 'PASS', topologyProblems.length ? topologyProblems.join('；') : 'mysql 由官方镜像提供、无 entrypoint/command 覆盖、无 extra_hosts/别名抢占/外部引用')

  const hardeningProblems = []
  const isDeclared = (value) => {
    if (value == null) return false
    if (Array.isArray(value)) return value.length > 0
    if (typeof value === 'object') return Object.keys(value).length > 0
    if (typeof value === 'boolean') return value
    if (typeof value === 'string') return value.length > 0
    return true
  }
  // `security_opt` 是唯一允许的加固类声明：只接受 `no-new-privileges[:true]`（其余仍拒）。
  const isNoNewPrivileges = (value) => Array.isArray(value) && value.length > 0
    && value.every((item) => /^no-new-privileges(?::true)?$/i.test(String(item).trim()))
  for (const [name, service] of Object.entries(services)) {
    for (const key of FORBIDDEN_SERVICE_KEYS) {
      if (!isDeclared(service[key])) continue
      if (key === 'security_opt' && isNoNewPrivileges(service[key])) continue
      const value = service[key]
      const rendered = typeof value === 'object' ? JSON.stringify(value) : String(value)
      hardeningProblems.push(`${name} 声明 ${key}: ${rendered}`)
    }
  }
  record('container-hardening', hardeningProblems.length ? 'FAIL' : 'PASS', hardeningProblems.length ? hardeningProblems.join('；') : `未声明 privileged/权限与设备类（cap_add/devices/security_opt/device_cgroup_rules）、命名空间类（pid/ipc/userns_mode/cgroup/uts/cgroup_parent）或运行时控制类（runtime/sysctls/group_add/volumes_from/…）声明`)

  // 运行时护栏（A0-E 验收口径）：文本层只保证清单级正确性，真正的隔离边界由这些运行时约束兜底。
  // 要求：所有服务 cap_drop=ALL + no-new-privileges；应用服务（platform/web-runner/web）只读根 + 非 root 用户 + 可写 /tmp。
  const runtimeProblems = []
  const APP_SERVICES = ['platform', 'web-runner', 'web', 'hermes']
  for (const [name, service] of Object.entries(services)) {
    const caps = Array.isArray(service.cap_drop) ? service.cap_drop.map((item) => String(item).toUpperCase()) : []
    if (!caps.includes('ALL')) runtimeProblems.push(`${name} 未 cap_drop: ALL`)
    if (!isNoNewPrivileges(service.security_opt)) runtimeProblems.push(`${name} 未声明 security_opt: no-new-privileges:true`)
    // 非 root 对四个服务都要求（mysql 也必须以镜像自带用户运行，否则需要 setuid/setgid）。
    const user = String(service.user ?? '').trim()
    if (!user) runtimeProblems.push(`${name} 未声明非 root user`)
    else {
      const uid = user.split(':')[0]
      if (uid === '0' || uid === 'root') runtimeProblems.push(`${name} 以 root 运行（user: ${user}）`)
    }
    if (!APP_SERVICES.includes(name)) continue
    if (service.read_only !== true) runtimeProblems.push(`${name} 未声明 read_only: true`)
    const tmpfs = Array.isArray(service.tmpfs) ? service.tmpfs.map((item) => String(item)) : []
    if (!tmpfs.some((item) => item === '/tmp' || item.startsWith('/tmp:'))) runtimeProblems.push(`${name} 未给 /tmp 提供 tmpfs（只读根下无法写临时文件）`)
  }
  record('runtime-hardening', runtimeProblems.length ? 'FAIL' : 'PASS', runtimeProblems.length
    ? runtimeProblems.join('；')
    : '运行时护栏齐全：所有服务 cap_drop=ALL + no-new-privileges；platform/web-runner/web/hermes 只读根 + 非 root + /tmp tmpfs')

  // Hermes 执行单元的拓扑约束：不发布端口、只挂命名卷到 /state、构建参数只允许基础镜像。
  const hermes = services.hermes
  if (hermes) {
    const problems = []
    if (hermes.ports && Object.keys(hermes.ports).length > 0) problems.push('hermes 不允许发布宿主端口（只能在同一 isolated 网络内访问）')
    const volumes = Array.isArray(hermes.volumes) ? hermes.volumes : []
    for (const volume of volumes) {
      const type = String(volume?.type ?? '')
      if (type !== 'volume') problems.push(`hermes 只允许命名卷（发现 ${type || '未声明'} 挂载）`)
      if (String(volume?.target ?? '') !== '/state') problems.push(`hermes 卷必须挂到 /state（发现 ${volume?.target}）`)
      const source = String(volume?.source ?? '')
      if (source.includes('.hermes') || source.startsWith('/') || source.startsWith('~')) {
        problems.push(`hermes 卷来源不允许是宿主路径或 .hermes：${source}`)
      }
    }
    const buildArgs = Object.keys(hermes.build?.args ?? {})
    for (const key of buildArgs) {
      if (key !== 'HERMES_BASE_IMAGE') problems.push(`hermes 构建参数只允许 HERMES_BASE_IMAGE（发现 ${key}）`)
    }
    const context = String(hermes.build?.context ?? '')
    if (!/(^|\/)hermes$/.test(context)) {
      problems.push(`hermes 的构建上下文必须指向 deploy/isolated/hermes（发现 ${context || '未声明'}）`)
    }
    const dockerfile = hermes.build?.dockerfile
    if (dockerfile && !['Dockerfile', 'hermes/Dockerfile'].includes(String(dockerfile))) {
      problems.push(`hermes 的 Dockerfile 只允许 hermes/Dockerfile（发现 ${dockerfile}）`)
    }
    record('hermes-topology', problems.length ? 'FAIL' : 'PASS', problems.length
      ? problems.join('；')
      : 'Hermes 执行单元：无宿主端口、命名卷挂 /state、构建参数只有基础镜像')
  }

  const hostFileProblems = []
  // 隔离栈不使用 secrets/configs：既能挂载仓库外文件（file:），也能用 inline content 挂成
  // application.yml 之类间接改写运行配置——只查 file: 挡不住 content 形态（第三轮复核实测）。
  for (const kind of ['secrets', 'configs']) {
    for (const key of Object.keys(resolved[kind] ?? {})) {
      hostFileProblems.push(`栈定义声明了 ${kind}.${key}：隔离栈不使用 ${kind}（file: 可读入仓库外文件，content: 可挂成配置文件间接改写运行参数）`)
    }
    for (const [name, service] of Object.entries(services)) {
      const usage = service[kind]
      const used = Array.isArray(usage) ? usage.length > 0 : usage != null
      if (used) hostFileProblems.push(`${name} 引用 ${kind}：隔离栈不使用 ${kind}`)
    }
  }
  record('host-file-sources', hostFileProblems.length ? 'FAIL' : 'PASS', hostFileProblems.length ? hostFileProblems.join('；') : '未声明 secrets/configs，也无服务引用')

  const volumeSharingProblems = []
  for (const [key, volume] of Object.entries(resolved.volumes ?? {})) {
    if (volume && typeof volume === 'object' && volume.external) {
      volumeSharingProblems.push(`卷 ${key} 声明 external: true：引用预建卷，数据来源不可验证（新卷名也可能装旧数据）`)
    }
  }
  const volumeUsers = new Map()
  for (const [name, service] of Object.entries(services)) {
    for (const mount of service.volumes ?? []) {
      if ((mount.type ?? 'volume') !== 'volume') continue
      const key = String(mount.source ?? '')
      volumeUsers.set(key, [...(volumeUsers.get(key) ?? []), name])
    }
  }
  for (const [key, users] of volumeUsers) {
    if (users.length > 1) {
      volumeSharingProblems.push(`卷 ${key} 被多个服务同时挂载（${users.join('、')}）：面向不可信内容执行的服务不得与平台共享可写卷`)
    }
  }
  record('volume-isolation', volumeSharingProblems.length ? 'FAIL' : 'PASS', volumeSharingProblems.length ? volumeSharingProblems.join('；') : '无 external 卷、无跨服务共享卷')

  // 机制判定：先做值空间归一（多编码解码 + YAML 转义解码），再按「键位」匹配。
  // 第八轮复核实测：仅按原始 utf8 文本逐行匹配会被转义键名（"\x65nv_file"）、整份 UTF-16 文件与 flow 写法绕过。
  const envFileProblems = []
  const composeBytes = readFileSync(path.resolve(REPO_ROOT, opts.composeFile))
  const composeVariants = composeTextVariants(composeBytes)
  const seenMechanismHits = new Set()
  for (const text of composeVariants) {
    splitComposeLines(text).forEach((line, index) => {
      const considered = stripLineComment(line)
      if (!considered) return
      const match = considered.match(MECHANISM_KEY_POSITION) ?? considered.match(MECHANISM_KEY_TOKEN) ?? considered.match(MECHANISM_ANY_WORD)
      if (!match) return
      const key = `${match[1]}|${index + 1}`
      if (seenMechanismHits.has(key)) return
      seenMechanismHits.add(key)
      const mechanism = FORBIDDEN_STACK_MECHANISMS.find((entry) => entry.id === match[1])
      envFileProblems.push(`栈定义第 ${index + 1} 行出现 ${mechanism.id}：${mechanism.note}（隔离栈定义不允许该机制）`)
    })
  }
  // 解析后模型的允许清单：机制被「值空间归一」之外的写法藏住时，其效果仍会在这里现形
  // （env_file 注入的未知环境变量键、include/extends 引入的未知服务或键）。
  for (const name of Object.keys(services)) {
    if (!EXPECTED_SERVICES.has(name)) {
      envFileProblems.push(`解析结果出现预期之外的服务 ${name}：只有 ${[...EXPECTED_SERVICES].join('、')} 才属于隔离栈（include/extends 引入的服务会在这里现形）`)
    }
  }
  for (const [name, service] of Object.entries(services)) {
    for (const key of Object.keys(service)) {
      if (ALLOWED_SERVICE_KEYS.has(key)) continue
      // 解析器会把镜像默认值写成 `entrypoint: null`／`command: null` 之类的空键，空值不算「声明」。
      if (service[key] == null) continue
      if (Array.isArray(service[key]) && service[key].length === 0) continue
      if (typeof service[key] === 'object' && Object.keys(service[key]).length === 0) continue
      envFileProblems.push(`${name} 出现未声明的服务级键 ${key}（服务级键集合必须与栈定义一致）`)
    }
    const allowedEnvironment = ALLOWED_ENVIRONMENT_KEYS.get(name)
    if (!allowedEnvironment) continue
    for (const key of Object.keys(service.environment ?? {})) {
      if (allowedEnvironment.has(key)) continue
      envFileProblems.push(`${name} 出现未声明的环境变量键 ${key}（env_file 注入的键会在这里现形；若是栈定义新增，请同步更新预检里的 ALLOWED_ENVIRONMENT_KEYS）`)
    }
  }
  // 解析层结构性检查（第十一轮复核提出的方案，第十二轮复核实测有效）：
  // `config --no-env-resolution` 视图会保留 env_file 路径，且与文本层无关——别名当键/单 ! 标签/%TAG 缩写/
  // 显式键/LS·NEL 行分隔/UTF-16/插值路径/长格式 path+required:false 等写法都被解析器归一后在这里现形，
  // extends/include 继承来的 env_file 也出现在对应服务上。
  // 注意：include/extends **本身**不会出现在任何解析视图里（compose 在序列化前就把它们解析掉），
  // 因此这两类机制只能靠文本层的词级判定，不能声称由本视图覆盖（第十二轮复核指出的过度声明）。
  const mechanismViolations = []
  for (const [name, service] of Object.entries(mechanismView?.services ?? {})) {
    const declared = service?.env_file
    if (declared == null) continue
    const entries = Array.isArray(declared) ? declared : [declared]
    if (entries.length === 0) continue
    mechanismViolations.push(`${name} 声明 env_file（解析层 --no-env-resolution 视图）：${JSON.stringify(declared)}`)
  }
  envFileProblems.push(...mechanismViolations)
  record('env-file-sources', envFileProblems.length ? 'FAIL' : 'PASS', envFileProblems.length ? envFileProblems.join('；') : '文本层词级判定未命中 env_file/include/extends；解析层 --no-env-resolution 视图里没有 env_file 声明（这是 env_file 的结构性判据）；解析结果的服务集、服务级键集与环境变量键集都在允许清单内')

  const buildProblems = []
  for (const [name, service] of Object.entries(services)) {
    const build = service.build && typeof service.build === 'object' ? service.build : null
    if (!build) continue
    // 仓库根自身也算「在仓库内」（== REPO_ROOT 时 startsWith(REPO_ROOT + sep) 为 false，会误报）。
    // 第十轮复核：符号链接可以指向仓库外，纯词法 path 判定看不出，因此存在时按 realpath 判定。
    // （判定实现已提到模块级 insideRepo()，内容检查那一项也要用。）
    const context = build.context
    const contextRaw = context == null ? '' : String(context).trim()
    const contextReason = contextRaw ? remoteContextReason(contextRaw, composeDir) : null
    if (contextReason) {
      buildProblems.push(`${name} 的 build.context 不是本地路径（${contextRaw}）：${contextReason}，镜像内容来自仓库外`)
    } else if (contextRaw) {
      const absolute = path.resolve(composeDir, contextRaw)
      if (!insideRepo(absolute)) {
        buildProblems.push(`${name} 的 build.context 不在仓库内（${absolute}）：镜像内容不可验证`)
      } else if (!existsSync(absolute)) {
        buildProblems.push(`${name} 的 build.context 在仓库内不存在（${absolute}）：无法证实是本地上下文`)
      }
    }
    // 构建子键改为允许清单（第十一轮复核实测的旁路：`build.tags: ["mysql:8.4"]` 会让自造镜像改名成
    // 官方镜像标签，且在 mysql 容器创建之前完成改名；`build.dockerfile_inline` 会让「dockerfile 在仓库内」失效）。
    for (const key of Object.keys(build)) {
      if (ALLOWED_BUILD_KEYS.has(key)) continue
      if (!isDeclared(build[key])) continue
      buildProblems.push(`${name} 的 build.${key}: ${JSON.stringify(build[key])}（构建子键未在允许清单内，只允许 ${[...ALLOWED_BUILD_KEYS].join('/')}）`)
    }
    // 第二轮/第四轮/第五轮/第六轮复核：只查 context 不够——dockerfile、additional_contexts（含 git://、
    // docker-image://、git@host:path、host/path.git 等远程形态）同样能把仓库外内容带进镜像。
    if (build.dockerfile) {
      const dockerfile = String(build.dockerfile)
      const base = contextRaw && !contextReason ? path.resolve(composeDir, contextRaw) : composeDir
      const absolute = path.isAbsolute(dockerfile) ? dockerfile : path.resolve(base, dockerfile)
      if (!insideRepo(absolute)) {
        buildProblems.push(`${name} 的 build.dockerfile 不在仓库内（${absolute}）`)
      }
    }
    for (const [key, value] of Object.entries(build.additional_contexts ?? {})) {
      const raw = String(value).trim()
      const reason = remoteContextReason(raw, composeDir)
      if (reason) {
        buildProblems.push(`${name} 的 build.additional_contexts.${key} 不是本地路径（${raw}）：${reason}，构建时会把仓库外内容带进镜像`)
        continue
      }
      const absolute = path.resolve(composeDir, raw)
      if (!insideRepo(absolute)) {
        buildProblems.push(`${name} 的 build.additional_contexts.${key} 不在仓库内（${absolute}）`)
      } else if (!existsSync(absolute)) {
        buildProblems.push(`${name} 的 build.additional_contexts.${key} 在仓库内不存在（${absolute}）：无法证实是本地上下文`)
      }
    }
  }
  record('build-context', buildProblems.length ? 'FAIL' : 'PASS', buildProblems.length ? buildProblems.join('；') : '构建上下文、dockerfile 与 additional_contexts 都是仓库内的本地路径')

  // 第十八轮复核：预检此前从不读任何 Dockerfile——compose 不声明被撤回的键时，构建上下文里的
  // `ENV APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOSTS=…`（或 SPRING_APPLICATION_JSON）会照旧生效，
  // 门却 PASS。这里对被引用的 Dockerfile 做内容检查（ENV/ARG 行里的禁写键 + 全文的旧栈标识/端点）。
  const buildFileProblems = []
  for (const [name, service] of Object.entries(services)) {
    const build = service.build
    if (!build || typeof build !== 'object') continue
    // 构建参数面（第二十轮复核：compose `build.args` 的**值面**只查旧标识/端点，不查键名，
    // 因此键名可以作为参数值被带进 Dockerfile、再用 printf %s 拼出来写进 env 文件）。
    const buildArgs = build.args && typeof build.args === 'object' ? build.args : {}
    for (const [argKey, argValue] of Object.entries(buildArgs)) {
      const keyText = String(argKey)
      const valueText = argValue == null ? '' : String(argValue)
      if (EGRESS_KEYS.includes(keyText) || keyText === 'APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOSTS' || DATASOURCE_OVERRIDE_KEY.test(keyText)) {
        buildFileProblems.push(`${name} 的 build.args.${keyText} 用禁写键名作构建参数`)
      }
      for (const key of [...EGRESS_KEYS, ...BUILD_FORBIDDEN_ANYWHERE_KEYS]) {
        if (valueText.includes(key)) buildFileProblems.push(`${name} 的 build.args.${keyText} 的值里出现禁写键名 ${key}`)
      }
    }
    const contextRaw = build.context == null ? '' : String(build.context)
    const reason = contextRaw ? remoteContextReason(contextRaw, composeDir) : ''
    if (!contextRaw || reason) continue
    const contextDir = path.resolve(composeDir, contextRaw)
    if (!insideRepo(contextDir)) continue
    const dockerfileRaw = build.dockerfile == null ? 'Dockerfile' : String(build.dockerfile)
    const dockerfilePath = path.isAbsolute(dockerfileRaw) ? dockerfileRaw : path.resolve(contextDir, dockerfileRaw)
    if (!insideRepo(dockerfilePath)) continue // 已由 build-context 报过
    if (!existsSync(dockerfilePath)) {
      buildFileProblems.push(`${name} 的构建文件不存在（${path.relative(REPO_ROOT, dockerfilePath)}）：无法证实镜像不含禁写环境变量`)
      continue
    }
    let buffer
    try {
      buffer = readFileSync(dockerfilePath)
    } catch (error) {
      buildFileProblems.push(`${name} 的构建文件无法读取（${path.relative(REPO_ROOT, dockerfilePath)}）：${error.message}`)
      continue
    }
    const relative = path.relative(REPO_ROOT, dockerfilePath)
    for (const decoding of decodingsForScan(buffer)) {
      for (const violation of buildTextViolations(decoding.text, { dockerfileSyntax: true })) {
        buildFileProblems.push(`${name} 的构建文件 ${relative}（${decoding.encoding} 解码）：${violation}`)
      }
    }
    // 被 ENTRYPOINT/CMD 直接执行的、经 COPY/ADD 带入镜像的脚本：与 Dockerfile 同一套规则（第二十二轮复核修正）。
    const entrypointScan = collectCopiedEntrypoints(buffer.toString('utf8'), contextDir, service.build?.args ?? {})
    for (const item of entrypointScan.unscannable) buildFileProblems.push(`${name} 的构建文件 ${relative}：${item}`)
    for (const sourcePath of entrypointScan.sources) {
      if (!insideRepo(sourcePath) || !existsSync(sourcePath)) continue
      let scriptBuffer
      try {
        scriptBuffer = readFileSync(sourcePath)
      } catch {
        continue
      }
      const scriptRel = path.relative(REPO_ROOT, sourcePath)
      // 应用模块（.mjs/.js/.py…）只做「禁写键名」判定：它们不是 shell 脚本，套 shell 规则会误拦 `if (a < b)` 这类正常写法。
      const appModule = /\.(?:mjs|cjs|js|jsx|ts|tsx|mts|cts|py|rb|pl|php|go|rs|java|cs|kt|scala|lua|r)$/i.test(sourcePath)
      for (const decoding of decodingsForScan(scriptBuffer)) {
        for (const violation of buildTextViolations(decoding.text, { dockerfileSyntax: false, appModule })) {
          buildFileProblems.push(`入口脚本 ${scriptRel}（${decoding.encoding} 解码）：${violation}`)
        }
      }
    }
    // heredoc 直接提供内容的入口脚本（`COPY <<E /app/entry.sh`）：内容就在构建文件里，直接按脚本审。
    for (const item of entrypointScan.heredocScripts ?? []) {
      // heredoc 提供的脚本同样按扩展名分类：`.py`/`.mjs` 是应用模块，套 shell 规则会把 `if (a < b)` 误拦（OB-1）。
      const heredocAppModule = /\.(?:mjs|cjs|js|jsx|ts|tsx|mts|cts|py|rb|pl|php|go|rs|java|cs|kt|scala|lua|r)$/i.test(item.target)
      for (const violation of buildTextViolations(item.text, { dockerfileSyntax: false, appModule: heredocAppModule })) {
        buildFileProblems.push(`入口脚本（heredoc ${item.target}）：${violation}`)
      }
    }
  }
  record('build-file-contents', buildFileProblems.length ? 'FAIL' : 'PASS', buildFileProblems.length ? buildFileProblems.join('；') : '被引用的构建文件（Dockerfile）里没有禁写的 ENV/ARG 键，也没有旧栈宿主端点（历史品牌字串不在此项判定，由 A0 拆除清单跟踪）')

  // 裸容器端口（只写容器端口）会让宿主端口随机分配，端口范围/表达式会让旧端口与冲突检查失效。
  const exposedProblems = []
  for (const [name, service] of Object.entries(services)) {
    for (const entry of service.ports ?? []) {
      if (!entry || typeof entry !== 'object') continue
      if (entry.published == null) {
        exposedProblems.push(`${name} 只声明容器端口 ${entry.target} 未显式发布宿主端口（会随机映射）`)
      } else if (!/^\d+$/.test(String(entry.published))) {
        exposedProblems.push(`${name} 的发布端口 "${entry.published}" 不是单一整数端口：端口范围/表达式会让「未复用旧栈端口」「端口互不冲突」「宿主端口空闲」三条检查失效`)
      }
    }
  }
  record('published-ports-explicit', exposedProblems.length ? 'FAIL' : 'PASS', exposedProblems.length ? exposedProblems.join('；') : '所有端口都显式声明了单一整数宿主端口')

  const imageProblems = []
  for (const [name, service] of Object.entries(services)) {
    const image = service.image
    if (!image) continue
    const hasDigest = image.includes('@sha256:')
    const lastSegment = image.slice(image.lastIndexOf('/') + 1)
    const hasTag = lastSegment.includes(':')
    if (!hasDigest && !hasTag) imageProblems.push(`${name} 镜像 ${image} 未固定 tag/digest`)
    else if (image.endsWith(':latest')) imageProblems.push(`${name} 镜像 ${image} 使用 latest`)
  }
  record('image-pinning', imageProblems.length ? 'FAIL' : 'PASS', imageProblems.length ? imageProblems.join('；') : '所有镜像均带显式 tag 或 digest')

  const guardProblems = []
  const platform = services.platform
  if (!platform) guardProblems.push('缺少 platform 服务')
  else {
    const env = platform.environment ?? {}
    if (env.APP_ENV_ID !== EXPECTED_PROJECT) guardProblems.push(`platform 未声明 APP_ENV_ID=${EXPECTED_PROJECT}`)
    if (!('APP_ENV_PREFLIGHT_HASH' in env)) {
      guardProblems.push('platform 未声明 APP_ENV_PREFLIGHT_HASH（启动保护所需）')
    } else if (String(env.APP_ENV_PREFLIGHT_HASH ?? '') !== '') {
      // 硬编码的哈希形状合法也无效：预检哈希必须由启动入口在预检通过后注入，
      // 直接 compose up 时它必须为空，否则平台会带着伪造的"预检结果"通过启动保护。
      guardProblems.push('platform 的 APP_ENV_PREFLIGHT_HASH 在栈定义里被写死：必须保持由启动入口注入（解析结果应为空）')
    }
  }
  record('service-startup-guard', guardProblems.length ? 'FAIL' : 'PASS', guardProblems.length ? guardProblems.join('；') : 'platform 声明了环境标识，且预检哈希只允许由启动入口注入')

  const secretChecks = []
  for (const key of REQUIRED_SECRET_VARS) {
    const value = envFileValues[key]
    if (!value) secretChecks.push(`${key} 缺失`)
    else if (PLACEHOLDER_VALUE.test(value)) secretChecks.push(`${key} 仍是占位值`)
    else if (value.length < 16) secretChecks.push(`${key} 长度不足 16`)
  }
  if (opts.requireSecrets) {
    record('required-secrets', secretChecks.length ? 'FAIL' : 'PASS', secretChecks.length ? secretChecks.join('；') : `${REQUIRED_SECRET_VARS.length} 项必需凭据均已配置且非占位`)
  } else if (secretChecks.length) {
    record('required-secrets', 'WARN', `${secretChecks.join('；')}（未启用 --require-secrets，仅记录）`)
  } else {
    record('required-secrets', 'PASS', '必需凭据已配置且非占位')
  }

  return checks
}

function hostPortCheck(ports) {
  const unique = [...new Set(ports)].sort((a, b) => a - b)
  if (unique.length === 0) return { status: 'PASS', detail: '无发布端口', busy: [] }
  if (process.platform === 'darwin') {
    const probe = run('lsof', ['-nP', '-iTCP', '-sTCP:LISTEN'], { allowFailure: true })
    if (!probe.ok) return { status: 'SKIP', detail: `lsof 不可用，宿主端口占用未验证: ${probe.detail}`, busy: [] }
    const busy = unique.filter((port) => new RegExp(`[:.]${port}\\b`).test(probe.stdout))
    return busy.length
      ? { status: 'FAIL', detail: `宿主端口已被占用: ${busy.join(', ')}`, busy }
      : { status: 'PASS', detail: `宿主端口空闲: ${unique.join(', ')}`, busy }
  }
  if (process.platform === 'linux') {
    const probe = run('ss', ['-ltn'], { allowFailure: true })
    if (!probe.ok) return { status: 'SKIP', detail: `ss 不可用，宿主端口占用未验证: ${probe.detail}`, busy: [] }
    const busy = unique.filter((port) => new RegExp(`[:.]${port}\\s`).test(probe.stdout))
    return busy.length
      ? { status: 'FAIL', detail: `宿主端口已被占用: ${busy.join(', ')}`, busy }
      : { status: 'PASS', detail: `宿主端口空闲: ${unique.join(', ')}`, busy }
  }
  return { status: 'SKIP', detail: `平台 ${process.platform} 未实现宿主端口占用检查`, busy: [] }
}

function dockerInventory() {
  const probe = run('docker', ['info', '--format', '{{.ServerVersion}}'], { allowFailure: true })
  if (!probe.ok) return { available: false, detail: probe.detail.replace(/\s+/g, ' ').slice(0, 300) }
  const list = (args) => {
    const result = run('docker', args, { allowFailure: true })
    return result.ok ? result.stdout.split('\n').map((line) => line.trim()).filter(Boolean) : []
  }
  return {
    available: true,
    serverVersion: probe.stdout.trim(),
    containers: list(['ps', '-a', '--format', '{{.Names}} | {{.Image}} | {{.Status}}']),
    volumes: list(['volume', 'ls', '--format', '{{.Name}}']),
    networks: list(['network', 'ls', '--format', '{{.Name}}']),
    images: list(['image', 'ls', '--format', '{{.Repository}}:{{.Tag}}']),
  }
}

function writeJson(outPath, payload) {
  const absolute = path.resolve(REPO_ROOT, outPath)
  mkdirSync(path.dirname(absolute), { recursive: true })
  writeFileSync(absolute, `${JSON.stringify(payload, null, 2)}\n`, 'utf8')
  return absolute
}

/**
 * 文本扫描用的解码列表：识别 UTF-16 BOM；对含 NUL 的内容同时按 latin1 与（偶数长度时）UTF-16LE 各扫一遍，
 * 避免无 BOM 的 UTF-16 文本被 NUL 隔断而整体漏检（第四/五轮复核实测）。
 */
function decodingsForScan(buffer) {
  if (buffer.length >= 2 && buffer[0] === 0xff && buffer[1] === 0xfe) {
    return [{ text: buffer.subarray(2).toString('utf16le'), encoding: 'utf16le' }]
  }
  if (buffer.length >= 2 && buffer[0] === 0xfe && buffer[1] === 0xff) {
    const body = buffer.subarray(2)
    // 总长为奇数时末尾那个字节无法配对，先截掉再解（第七轮复核实测：奇数长度的 BE 文本曾整体漏检）。
    const aligned = body.length % 2 === 0 ? body : body.subarray(0, body.length - 1)
    const swapped = Buffer.from(aligned)
    swapped.swap16()
    return [{ text: swapped.toString('utf16le'), encoding: 'utf16be' }]
  }
  if (buffer.includes(0)) {
    const decodings = [{ text: buffer.toString('latin1'), encoding: 'latin1' }]
    const evenLength = buffer.length % 2 === 0 ? buffer : buffer.subarray(0, buffer.length - 1)
    if (evenLength.length > 0) {
      // 无 BOM 的 UTF-16LE 与 UTF-16BE 都要扫（第六轮：只扫 LE 方向时 BE 整体漏检；
      // 第七轮：总字节数为奇数的文本会被跳过，因此截掉末尾一个字节后再解一遍）。
      decodings.push({ text: evenLength.toString('utf16le'), encoding: 'latin1+utf16le' })
      const swapped = Buffer.from(evenLength)
      swapped.swap16()
      decodings.push({ text: swapped.toString('utf16le'), encoding: 'latin1+utf16be' })
    }
    return decodings
  }
  return [{ text: buffer.toString('utf8'), encoding: 'utf8' }]
}

function walkFiles(target, skipped) {
  const absolute = path.resolve(REPO_ROOT, target)
  if (!existsSync(absolute)) return []
  const relPath = path.relative(REPO_ROOT, absolute)
  let stats
  try {
    stats = statSync(absolute)
  } catch (error) {
    skipped.push({ file: relPath, reason: `unreadable:${error.code ?? 'unknown'}` })
    return []
  }
  if (stats.isFile()) return [absolute]
  if (!stats.isDirectory()) {
    skipped.push({ file: relPath, reason: 'unsupported-entry' })
    return []
  }
  let entries
  try {
    entries = readdirSync(absolute, { withFileTypes: true })
  } catch (error) {
    skipped.push({ file: relPath, reason: `unreadable-dir:${error.code ?? 'unknown'}` })
    return []
  }
  const files = []
  for (const entry of entries) {
    const child = path.join(absolute, entry.name)
    const relChild = path.relative(REPO_ROOT, child)
    if (entry.name === 'node_modules' || entry.name === 'target' || entry.name === '.git') {
      // 不递归这些目录（第三方/构建产物），但如实记录，而不是静默跳过。
      skipped.push({ file: relChild, reason: 'excluded-directory' })
      continue
    }
    if (entry.isSymbolicLink()) {
      skipped.push({ file: relChild, reason: 'symlink' })
      continue
    }
    if (entry.isDirectory()) {
      files.push(...walkFiles(relChild, skipped))
    } else if (entry.isFile()) {
      files.push(child)
    } else {
      skipped.push({ file: relChild, reason: 'unsupported-entry' })
    }
  }
  return files
}

function scanSurfaces(opts) {
  const findings = []
  const skipped = []
  const seen = new Set()
  const record = (surface, file, line, message) => {
    const key = `${surface}|${file}|${line}|${message}`
    if (seen.has(key)) return
    seen.add(key)
    findings.push({ surface, file, line, message })
  }
  const selfPath = fileURLToPath(import.meta.url)

  for (const surface of SCAN_SURFACES) {
    for (const file of walkFiles(surface, skipped)) {
      if (file === selfPath) continue // 模式定义文件本身必须包含被禁止的标识
      const relPath = path.relative(REPO_ROOT, file)
      let buffer
      try {
        buffer = readFileSync(file)
      } catch (error) {
        skipped.push({ file: relPath, reason: `unreadable:${error.code ?? 'unknown'}` })
        continue
      }
      const decodings = decodingsForScan(buffer)
      for (const { text, encoding } of decodings) {
        if (encoding !== 'utf8') skipped.push({ file: relPath, reason: `scanned as ${encoding}` })
        text.split('\n').forEach((line, index) => {
          const hit = FORBIDDEN_TOKENS.find((token) => line.toLowerCase().includes(token))
          if (hit) record(surface, relPath, index + 1, `命中旧资源标识 ${hit}`)
        })
      }
    }
  }

  const tracked = run('git', ['ls-files', '--cached', '--others', '--exclude-standard'], { allowFailure: true })
  if (!tracked.ok) {
    return { findings, skipped, blocked: `无法枚举仓库文件: ${tracked.detail}` }
  }
  for (const relPath of tracked.stdout.split('\n').map((l) => l.trim()).filter(Boolean)) {
    if (relPath.startsWith('docs/')) continue // 历史归档允许保留原始事实
    const absolute = path.resolve(REPO_ROOT, relPath)
    if (absolute === selfPath || !existsSync(absolute)) continue
    let buffer
    try {
      buffer = readFileSync(absolute)
    } catch (error) {
      skipped.push({ file: relPath, reason: `unreadable:${error.code ?? 'unknown'}` })
      continue
    }
    const decodings = decodingsForScan(buffer)
    for (const { text, encoding } of decodings) {
      if (encoding !== 'utf8') skipped.push({ file: relPath, reason: `scanned as ${encoding}` })
      text.split('\n').forEach((line, index) => {
        for (const pattern of PERSONAL_HOME_PATTERNS) {
          if (pattern.re.test(line)) record('repo', relPath, index + 1, `命中个人 Hermes home 路径（${pattern.id}）`)
        }
      })
    }
  }
  return { findings, skipped, blocked: null }
}

function main() {
  let opts
  try {
    opts = parseArgs(process.argv.slice(2))
  } catch (error) {
    console.error(`参数错误: ${error.message}`)
    process.exit(2)
  }

  if (opts.mode === 'scan') {
    const { findings, skipped, blocked } = scanSurfaces(opts)
    if (opts.json) {
      console.log(JSON.stringify({ mode: 'scan', verdict: blocked ? 'BLOCKED' : findings.length ? 'FAIL' : 'PASS', findings, skipped, blocked }, null, 2))
    } else {
      console.log(`隔离面扫描：${findings.length} 处命中（跳过 ${skipped.length} 条（同一文件的多编码解码各占一条）；.gitignore 掉的路径不在扫描集）`)
      for (const finding of findings) console.log(`  - [${finding.surface}] ${finding.file}:${finding.line} ${finding.message}`)
      for (const entry of skipped) console.log(`  - [skipped] ${entry.file}（${entry.reason}）`)
      if (blocked) console.log(`BLOCKED: ${blocked}`)
    }
    process.exit(blocked ? 2 : findings.length ? 1 : 0)
  }

  if (opts.mode === 'inventory') {
    const inventory = dockerInventory()
    console.log(JSON.stringify({ mode: 'inventory', generatedAt: new Date().toISOString(), inventory }, null, 2))
    if (opts.out && opts.out !== DEFAULT_OUT) writeJson(opts.out, { schemaVersion: 1, generatedAt: new Date().toISOString(), inventory })
    process.exit(inventory.available ? 0 : 2)
  }

  if (opts.mode !== 'check') {
    console.error(`未知模式: ${opts.mode}`)
    process.exit(2)
  }

  const envFile = readEnvFile(opts.envFile)
  if (!envFile.ok) {
    const message = `缺少环境文件 ${rel(opts.envFile)}：复制 deploy/isolated/.env.example 为 deploy/isolated/.env 并填入真实值（该文件不入库）`
    console.error(`BLOCKED: ${message}`)
    process.exit(2)
  }

  const resolvedResult = resolveCompose(opts)
  if (!resolvedResult.ok) {
    console.error(`BLOCKED: 无法解析 ${opts.composeFile}：${resolvedResult.detail}`)
    process.exit(2)
  }

  const resolved = resolvedResult.resolved
  const mechanismView = resolveComposeWithoutEnvResolution(opts)
  if (!mechanismView.ok && !mechanismView.unsupported) {
    console.error(`BLOCKED: 无法取得 env_file 结构化视图（config --no-env-resolution）：${mechanismView.detail}`)
    process.exit(2)
  }
  if (!mechanismView.ok && mechanismView.unsupported) {
    let version = ''
    try {
      version = execFileSync('docker', ['compose', 'version', '--short'], { encoding: 'utf8' }).trim()
    } catch {
      version = '(docker compose version 不可用)'
    }
    console.error(`BLOCKED: 本机 compose（${version}）不支持 config --no-env-resolution，无法在解析层证明 env_file 未被调用：${mechanismView.detail}`)
    process.exit(2)
  }
  const checks = checkResolved(opts, resolved, envFile.values, mechanismView.resolved)
  const publishedPortValues = Object.values(resolved.services ?? {}).flatMap(publishedPorts)

  let hash = null
  if (opts.expectHash) {
    hash = createHash('sha256').update(stableStringify(redactResolved(resolved))).digest('hex')
    checks.push({
      id: 'config-hash',
      status: hash === opts.expectHash ? 'PASS' : 'FAIL',
      detail: hash === opts.expectHash ? `配置 hash 与预检结果一致（${hash.slice(0, 12)}…）` : `配置 hash 已变化：当前 ${hash.slice(0, 12)}…，预检结果 ${opts.expectHash.slice(0, 12)}…`,
    })
  }

  let portCheck = { status: 'SKIP', detail: '未执行（--no-host-checks）', busy: [] }
  let inventory = { available: false, detail: '未执行（--no-host-checks）' }
  if (opts.hostChecks) {
    portCheck = hostPortCheck(publishedPortValues)
    inventory = dockerInventory()
  }
  checks.push({ id: 'host-port-availability', status: portCheck.status, detail: portCheck.detail })
  checks.push({
    id: 'resource-inventory',
    status: inventory.available ? 'PASS' : 'WARN',
    detail: inventory.available
      ? `容器 ${inventory.containers.length} / 卷 ${inventory.volumes.length} / 网络 ${inventory.networks.length} / 镜像 ${inventory.images.length}（启动前快照）`
      : `未采集 docker 资源清单: ${inventory.detail}`,
  })

  hash = hash ?? createHash('sha256').update(stableStringify(redactResolved(resolved))).digest('hex')
  const failures = checks.filter((check) => check.status === 'FAIL')
  const warnings = checks.filter((check) => check.status === 'WARN' || check.status === 'SKIP')
  const verdict = failures.length ? 'FAIL' : 'PASS'

  const payload = {
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    composeFile: rel(opts.composeFile),
    envFile: rel(opts.envFile),
    project: resolved.name,
    resolvedConfigHash: hash,
    verdict,
    checks,
    publishedHostPorts: [...new Set(publishedPortValues)].sort((a, b) => a - b),
    inventory,
  }
  const written = writeJson(opts.out, payload)

  console.log(`隔离环境预检：${verdict}`)
  console.log(`  compose=${payload.composeFile}  env=${payload.envFile}  project=${payload.project}`)
  console.log(`  configHash=${hash}`)
  for (const check of checks) console.log(`  [${check.status}] ${check.id}: ${check.detail}`)
  console.log(`  结果文件: ${rel(written)}`)
  if (verdict === 'FAIL') console.log('结论：不满足隔离要求，禁止启动。')

  process.exit(verdict === 'PASS' ? 0 : 1)
}

// 直接运行时才执行 CLI：被测用例集以模块方式 import 这些判定原语，做「解码/行切分/归一」层的直接单元断言
// （第十七轮复核：端到端用例无法区分这些层被拆掉，因为解析层与其它文本路径会照样报 FAIL）。
// 判定用 realpath 比较，且出错时按「导入」处理（不执行 CLI）——第十八轮复核指出：按「直接运行」处理会让
// `NODE_OPTIONS=--import <本文件> node <不存在的 argv[1]>` 这类退化调用意外执行 CLI（并可能写默认结果文件）。
const invokedDirectly = (() => {
  if (!process.argv[1]) return false
  try {
    return realpathSync(path.resolve(process.argv[1])) === realpathSync(fileURLToPath(import.meta.url))
  } catch {
    return false
  }
})()
if (invokedDirectly) main()

export {
  canonicalHostForm,
  collectLegacyPortTokens,
  collectCopiedEntrypoints,
  buildTextViolations,
  looseLegacyEndpointMatch,
  normalizeLegacyEndpointText,
  splitComposeLines,
  decodingsForScan,
}
