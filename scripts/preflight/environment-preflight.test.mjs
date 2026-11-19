/**
 * 隔离环境预检的对抗性测试集。
 *
 * 目的：预检脚本的「机制禁令 / 端点归一 / 允许清单 / 构建文件内容」在第五～十九届独立复核里反复被绕过，
 * 每次修复都只靠一次性手工探针，没有可重跑的用例集。这里把那些绕过形态固化成测试，
 * 使后续改动必须同时对负例（必须 FAIL）与正例（必须 PASS）负责。
 *
 * 运行：node --test scripts/preflight/environment-preflight.test.mjs
 *      （node v22 下不能传目录：`node --test scripts/preflight/` 会把目录当模块路径解析并报 MODULE_NOT_FOUND）
 * 依赖：本机 node ≥ 20 与 docker compose CLI（只做 config 解析，不访问 daemon、不起容器）。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, symlinkSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const PREFLIGHT_SOURCE = fileURLToPath(new URL('./environment-preflight.mjs', import.meta.url))
const REAL_REPO_ROOT = path.resolve(path.dirname(PREFLIGHT_SOURCE), '..', '..')
const REAL_COMPOSE = path.join(REAL_REPO_ROOT, 'deploy', 'isolated', 'compose.yaml')

const SECRETS = {
  HAITLIFY_ISOLATED_MYSQL_ROOT_PASSWORD: 'test-root-password-value-0123456789',
  HAITLIFY_ISOLATED_DB_PASSWORD: 'test-db-password-value-0123456789',
  HAITLIFY_ISOLATED_SECRET_KEY: 'dGVzdC1zZWNyZXQta2V5LXZhbHVlLTMyLWJ5dGVzIQ==',
}

/** 合成一个最小仓库：预检脚本副本 + 隔离栈定义（构建上下文相对路径与原栈一致，指向合成仓库内的同名目录）。 */
function makeRepo() {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'haitlify-preflight-test-'))
  const preflightDir = path.join(dir, 'scripts', 'preflight')
  mkdirSync(preflightDir, { recursive: true })
  copyFileSync(PREFLIGHT_SOURCE, path.join(preflightDir, 'environment-preflight.mjs'))
  for (const relative of ['server/platform', 'server/web-runner', 'apps/web']) {
    mkdirSync(path.join(dir, relative), { recursive: true })
    writeFileSync(path.join(dir, relative, 'Dockerfile'), 'FROM scratch\n')
  }
  mkdirSync(path.join(dir, 'deploy', 'isolated'), { recursive: true })
  // Hermes 执行单元的构建上下文（compose 里 hermes 服务的 context: ./hermes）。
  mkdirSync(path.join(dir, 'deploy', 'isolated', 'hermes'), { recursive: true })
  writeFileSync(path.join(dir, 'deploy', 'isolated', 'hermes', 'Dockerfile'), 'FROM scratch\n')
  writeFileSync(path.join(dir, 'deploy', 'isolated', 'hermes', 'bridge.py'), '# test stub\n')
  writeFileSync(path.join(dir, 'deploy', 'isolated', 'compose.yaml'), baselineCompose())
  const envLines = Object.entries(SECRETS).map(([key, value]) => `${key}=${value}`)
  writeFileSync(path.join(dir, 'deploy', 'isolated', '.env'), `${envLines.join('\n')}\n`)
  // 被 include/extends 引用的仓库内文件（这些用例要证明的是「机制被拒绝」，不是「文件不存在」）。
  writeFileSync(path.join(dir, 'deploy', 'isolated', 'outside-compose.yaml'),
    'services:\n  base:\n    image: busybox:1.36\n')
  // scan 模式的仓库级检查依赖 git 枚举文件。
  execFileSync('git', ['init', '-q'], { cwd: dir })
  return dir
}

function baselineCompose() {
  return readFileSync(REAL_COMPOSE, 'utf8')
}

function runPreflight(dir, args) {
  try {
    const stdout = execFileSync('node', ['scripts/preflight/environment-preflight.mjs', ...args], {
      cwd: dir,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    return { status: 0, stdout }
  } catch (error) {
    return {
      status: error.status ?? 1,
      stdout: error.stdout ?? '',
      stderr: error.stderr ?? '',
    }
  }
}

function check(dir, composeFile = 'deploy/isolated/compose.yaml') {
  return runPreflight(dir, [
    'check',
    '--compose', composeFile,
    '--env-file', 'deploy/isolated/.env',
    '--require-secrets',
    '--no-host-checks',
    '--out', path.join(os.tmpdir(), `haitlify-preflight-${Date.now()}-${Math.random()}.json`),
  ])
}

function writeVariant(dir, name, composeText, encoding) {
  const file = `deploy/isolated/${name}.yaml`
  const absolute = path.join(dir, file)
  if (encoding === 'utf16le-bom') {
    writeFileSync(absolute, Buffer.concat([Buffer.from([0xff, 0xfe]), Buffer.from(composeText, 'utf16le')]))
  } else if (encoding === 'utf16be-bom') {
    const swapped = Buffer.from(composeText, 'utf16le')
    swapped.swap16()
    writeFileSync(absolute, Buffer.concat([Buffer.from([0xfe, 0xff]), swapped]))
  } else if (encoding === 'cr-only') {
    writeFileSync(absolute, composeText.replace(/\n/g, '\r'))
  } else if (encoding === 'ls-separated') {
    writeFileSync(absolute, composeText.replace(/\n/g, '\u2028'))
  } else if (encoding === 'nel-separated') {
    writeFileSync(absolute, composeText.replace(/\n/g, '\u0085'))
  } else {
    writeFileSync(absolute, composeText)
  }
  return file
}

// 负例里要用到旧栈身份标识，但预检自身的 scan 面禁止这些字样出现（测试文件也在扫描面内），
// 因此这里按片段拼接，保证「扫描面里没有旧标识」这一性质对测试文件同样成立。
const LEGACY_PLATFORM = ['ai', 'test', 'platform'].join('-')
const LEGACY_DB = ['ai', 'test', 'platform'].join('_')
const LEGACY_PROJECT = ['cloud', 'v4'].join('')

const ANCHOR_RUNNER = '      APP_WEB_AUTOMATION_RUNNER_BASE_URL: http://web-runner:9323\n'
const ANCHOR_NETWORK = 'networks:\n  default:'

/** 负例：必须在 `check` 下 FAIL（退出码 1）。 */
const NEGATIVE_CASES = [
  ['env_file 明文', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    env_file:\n      - /etc/hosts\n`), '出现 env_file'],
  ['env_file 双引号 \\x 转义键名', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    "\\x65nv_file":\n      - /etc/hosts\n`), 'env-file-sources'],
  ['env_file 双引号 \\u 转义键名', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    "\\u0065nv_file":\n      - /etc/hosts\n`), 'env-file-sources'],
  ['include 明文', (c) => c.replace('services:', 'include:\n  - outside-compose.yaml\nservices:'), '出现 include'],
  ['include 转义键名', (c) => c.replace('services:', '"\\x69nclude":\n  - outside-compose.yaml\nservices:'), '出现 include'],
  ['extends 明文', (c) => c.replace('  web-runner:', '  web-runner:\n    extends:\n      file: outside-compose.yaml\n      service: base'), '出现 extends'],
  ['extends flow 写法', (c) => c.replace(ANCHOR_NETWORK, `  flow-svc: {image: busybox:1.36, container_name: haitlify-isolated-flow-svc, extends: {file: outside-compose.yaml, service: base}}\n\n${ANCHOR_NETWORK}`), '出现 extends'],
  ['未知环境变量键（env_file 注入效果）', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}      SMUGGLED_FROM_HOST_FILE: value\n`), '未声明的环境变量键'],
  ['预期之外的服务（include 效果）', (c) => c.replace(ANCHOR_NETWORK, `  injected-from-outside:\n    image: busybox:1.36\n\n${ANCHOR_NETWORK}`), '预期之外的服务'],
  ['端点半写 127.1', (c) => c.replace('http://web-runner:9323', 'http://127.1:19323'), '旧栈宿主端点'],
  ['端点整数 2130706433', (c) => c.replace('http://web-runner:9323', 'http://2130706433:19323'), '旧栈宿主端点'],
  ['端点十六进制 0x7f000001', (c) => c.replace('http://web-runner:9323', 'http://0x7f000001:19323'), '旧栈宿主端点'],
  ['端点混合十六进制 0x7f.0.0.1', (c) => c.replace('http://web-runner:9323', 'http://0x7f.0.0.1:19323'), '旧栈宿主端点'],
  ['端点 0（0.0.0.0）', (c) => c.replace('http://web-runner:9323', 'http://0:8080'), '旧栈宿主端点'],
  ['端点 IPv4-mapped 展开式', (c) => c.replace('http://web-runner:9323', 'http://[0:0:0:0:0:ffff:7f00:1]:19323'), '旧栈宿主端点'],
  ['端点 loopback 展开式', (c) => c.replace('http://web-runner:9323', 'http://[0:0:0:0:0:0:0:1]:19323'), '旧栈宿主端点'],
  ['端点端口前导零', (c) => c.replace('http://web-runner:9323', 'http://127.0.0.1:019323'), '旧栈宿主端点'],
  ['端点 host.docker.internal', (c) => c.replace('http://web-runner:9323', 'http://host.docker.internal:19323'), '旧栈宿主端点'],
  ['gpus', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    gpus: all\n`), 'container-hardening'],
  ['deploy 段设备预留', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    deploy:\n      resources:\n        reservations:\n          devices:\n            - capabilities: [gpu]\n`), 'container-hardening'],
  ['cpus/mem_limit 等资源键', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    cpus: 1.5\n    mem_limit: 512m\n    pids_limit: 100\n`), 'container-hardening'],
  ['volumes_from', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    volumes_from:\n      - container:${LEGACY_PLATFORM}-server\n`), 'container-hardening'],
  ['pid 命名空间共享', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    pid: container:${LEGACY_PLATFORM}-server\n`), 'container-hardening'],
  ['cgroup: host', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    cgroup: host\n`), 'container-hardening'],
  ['sysctls', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    sysctls:\n      net.ipv4.ip_forward: "1"\n`), 'container-hardening'],
  ['网络 link_local_ips', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    networks:\n      default:\n        link_local_ips:\n          - 169.254.169.254\n`), 'service-identity-and-topology'],
  ['网络内 mac_address', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    networks:\n      default:\n        mac_address: 02:42:ac:11:00:02\n`), 'service-identity-and-topology'],
  ['网络内 driver_opts', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    networks:\n      default:\n        driver_opts:\n          com.docker.network.endpoint.ifname: eth9\n`), 'service-identity-and-topology'],
  ['服务 dns 改写', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    dns:\n      - 8.8.8.8\n`), 'service-identity-and-topology'],
  ['服务名大小写抢占 MYSQL', (c) => c.replace(ANCHOR_NETWORK, `  MYSQL:\n    image: busybox:1.36\n\n${ANCHOR_NETWORK}`), 'identifiers'],
  ['服务名 LocalHost', (c) => c.replace(ANCHOR_NETWORK, `  LocalHost:\n    image: busybox:1.36\n\n${ANCHOR_NETWORK}`), 'identifiers'],
  ['别名 LocalHost', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    networks:\n      default:\n        aliases:\n          - LocalHost\n`), 'service-identity-and-topology'],
  ['profiles 空数组', (c) => c.replace(ANCHOR_NETWORK, `  ghost:\n    image: busybox:1.36\n    profiles: []\n\n${ANCHOR_NETWORK}`), '声明了 profiles'],
  ['profiles 引号键名', (c) => c.replace(ANCHOR_NETWORK, `  ghost:\n    image: busybox:1.36\n    "profiles": []\n\n${ANCHOR_NETWORK}`), '声明了 profiles'],
  ['profiles 转义键名', (c) => c.replace(ANCHOR_NETWORK, `  ghost:\n    image: busybox:1.36\n    "\\x70rofiles": []\n\n${ANCHOR_NETWORK}`), '声明了 profiles'],
  ['网络驱动 macvlan', (c) => c.replace('networks:\n  default:\n    name: haitlify-isolated-net\n', 'networks:\n  default:\n    name: haitlify-isolated-net\n    driver: macvlan\n    driver_opts:\n      parent: en0\n'), 'network-mode'],
  ['网络 ipam 指定网段', (c) => c.replace('networks:\n  default:\n    name: haitlify-isolated-net\n', 'networks:\n  default:\n    name: haitlify-isolated-net\n    ipam:\n      config:\n        - subnet: 172.18.0.0/16\n'), 'network-mode'],
  ['卷第三方驱动', (c) => c.replace('    name: haitlify-isolated-mysql-data\n', '    name: haitlify-isolated-mysql-data\n    driver: local-persist\n'), 'volume-driver-opts'],
  ['构建上下文远程 git（scp 形态）', (c) => c.replace(ANCHOR_NETWORK, `  b2:\n    build:\n      context: git@github.com:org/repo.git\n    image: alpine:3.20\n\n${ANCHOR_NETWORK}`), 'build-context'],
  ['构建上下文仓库外绝对路径', (c) => c.replace(ANCHOR_NETWORK, `  b3:\n    build:\n      context: /tmp/outside-context\n    image: alpine:3.20\n\n${ANCHOR_NETWORK}`), 'build-context'],
  ['build.privileged', (c) => c.replace(ANCHOR_NETWORK, `  b4:\n    build:\n      context: ./server/platform\n      privileged: true\n    image: alpine:3.20\n\n${ANCHOR_NETWORK}`), 'build.privileged'],
  ['build.network host', (c) => c.replace(ANCHOR_NETWORK, `  b5:\n    build:\n      context: ./server/platform\n      network: host\n    image: alpine:3.20\n\n${ANCHOR_NETWORK}`), 'build.network'],
  ['datasource 覆盖键', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}      SPRING_DATASOURCE_HIKARI_JDBC_URL: jdbc:mysql://127.0.0.1:3306/${LEGACY_DB}\n`), 'datasource-override-keys'],
  ['配置来源改写键', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}      SPRING_CONFIG_ADDITIONAL_LOCATION: "file:/tmp/evil-dir/"\n`), 'datasource-override-keys'],
  ['JVM 参数注入', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}      _JAVA_OPTIONS: "-Dprobe.marker=1"\n`), 'datasource-override-keys'],
  ['锚点前缀键 env_file（&a env_file:）', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    &hostenv env_file:\n      - /etc/hosts\n`), '出现 env_file'],
  ['标签键 env_file（!!str）', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    !!str env_file:\n      - /etc/hosts\n`), '出现 env_file'],
  ['显式键 env_file（? 形式）', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    ? env_file\n    :\n      - /etc/hosts\n`), '出现 env_file'],
  ['flow 引号内 " # " 后接 extends', (c) => c.replace(ANCHOR_NETWORK, `  flow-svc: {image: busybox:1.36, container_name: haitlify-isolated-flow-svc, labels: {probe: "keep # going"}, extends: {file: outside-compose.yaml, service: base}}\n\n${ANCHOR_NETWORK}`), '出现 extends'],
  ['mysql 声明 build（自造同名镜像）', (c) => c.replace('  mysql:\n    image: mysql:8.4\n', '  mysql:\n    image: mysql:8.4\n    build:\n      context: ../../server/platform\n'), 'mysql 声明 build'],
  ['platform 换成外部镜像', (c) => c.replace('  platform:\n    build:\n      context: ../../server/platform\n', '  platform:\n    image: evilmirror/platform:9.9\n'), '没有 build 段'],
  ['healthcheck 指向旧栈端点', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "curl -fsS http://host.docker.internal:19323/health || mysqladmin ping -h localhost"]'), '旧栈宿主端点'],
  ['build.args 里烘焙旧栈端点', (c) => c.replace('  platform:\n    build:\n      context: ../../server/platform\n', '  platform:\n    build:\n      context: ../../server/platform\n      args:\n        VITE_API_BASE_URL: http://host.docker.internal:19323\n'), '旧栈宿主端点'],
  ['端点宿主的百分号编码形式', (c) => c.replace('http://web-runner:9323', 'http://%31%32%37%2E%30%2E%30%2E1:19323'), '旧栈宿主端点'],
  ['两行式显式键 ? env_file', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    ? env_file\n    :\n      - /etc/hosts\n`), '出现 env_file'],
  ['标签在锚点前的 !!str &a env_file:', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    !!str &hostenv env_file:\n      - /etc/hosts\n`), '出现 env_file'],
  ['两行式显式键 ? extends', (c) => c.replace('  web-runner:', '  web-runner:\n    ? extends\n    :\n      {file: outside-compose.yaml, service: base}'), '出现 extends'],
  ['registry 前缀的镜像名 + build', (c) => c.replace('  platform:\n    build:\n      context: ../../server/platform\n', '  platform:\n    build:\n      context: ../../server/platform\n    image: haitlify-isolated-mirror.example:5000/platform:1\n'), '不得声明 image'],
  ['Docker Hub 命名空间形态的镜像名', (c) => c.replace('  platform:\n    build:\n      context: ../../server/platform\n', '  platform:\n    build:\n      context: ../../server/platform\n    image: haitlify-isolated-foo/bar:1\n'), '不得声明 image'],
  ['build.entitlements: network.host', (c) => c.replace('  platform:\n    build:\n      context: ../../server/platform\n', '  platform:\n    build:\n      context: ../../server/platform\n      entitlements:\n        - network.host\n'), 'build.entitlements'],
  ['build.cache_from 指向外部 registry', (c) => c.replace('  platform:\n    build:\n      context: ../../server/platform\n', '  platform:\n    build:\n      context: ../../server/platform\n      cache_from:\n        - type=registry,ref=evil.example/cache:latest\n'), 'build.cache_from'],
  ['healthcheck 用 /dev/tcp 指向旧端点', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "bash -c \'exec 3<>/dev/tcp/host.docker.internal/19323\' || true"]'), '非冒号写法'],
  ['healthcheck 用 nc 指向旧端点', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "nc -z host.docker.internal 19323 || true"]'), '非冒号写法'],
  ['healthcheck 用 -P 指向旧端点', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "mysqladmin ping -h host.docker.internal -P 3306"]'), '非冒号写法'],
  ['端点宿主全大写 HOST.DOCKER.INTERNAL', (c) => c.replace('http://web-runner:9323', 'http://HOST.DOCKER.INTERNAL:19323'), '旧栈宿主端点'],
  ['端点宿主大小写混合 LocalHost', (c) => c.replace('http://web-runner:9323', 'http://LocalHost:19323'), '旧栈宿主端点'],
  ['build.tags 把自造镜像改名成官方标签', (c) => c.replace('  platform:\n    build:\n      context: ../../server/platform\n', '  platform:\n    build:\n      context: ../../server/platform\n      tags:\n        - mysql:8.4\n'), 'build.tags'],
  ['build.dockerfile_inline', (c) => c.replace('  platform:\n    build:\n      context: ../../server/platform\n', '  platform:\n    build:\n      context: ../../server/platform\n      dockerfile_inline: "FROM alpine:3.20\n"\n'), 'build.dockerfile_inline'],
  ['build.ulimits 与 shm_size', (c) => c.replace('  platform:\n    build:\n      context: ../../server/platform\n', '  platform:\n    build:\n      context: ../../server/platform\n      ulimits:\n        nofile: 65536\n      shm_size: 1g\n'), 'build.ulimits'],
  ['机制经单 ! 标签隐藏（!myenv env_file:）', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    !myenv env_file:\n      - /etc/hosts\n`), '\\[FAIL\\] env-file-sources: .*(出现 env_file|解析层)'],
  ['healthcheck 用 --host/--port 指向旧端点', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "mysqladmin ping --host=host.docker.internal --port=19323"]'), '非冒号写法'],
  ['healthcheck 用 -P19323（无空格）指向旧端点', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "mysqladmin ping -h host.docker.internal -P19323"]'), '非冒号写法'],
  ['环境值用非冒号写法指向旧端点', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}      APP_WEB_AUTOMATION_RUNNER_HEALTHCHECK_NOTE: "nc -z host.docker.internal 19323"\n`), '非冒号写法'],
  ['healthcheck 带选项指向旧端点（--connect-timeout=300）', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "mysqladmin ping -h host.docker.internal --connect-timeout=300 -P 3306"]'), '非冒号/带选项写法'],
  ['healthcheck 端口写在主机之前（-P 19323 host）', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "nc -z -w 3 -P 19323 host.docker.internal"]'), '非冒号/带选项写法'],
  ['环境值带选项指向旧端点（--connect-timeout=30）', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}      APP_WEB_AUTOMATION_RUNNER_HEALTHCHECK_NOTE: "mysqladmin ping -h host.docker.internal --connect-timeout=30 -P 3306"\n`), '非冒号/带选项写法'],
  ['别名当键隐藏 include（x-k: &k include + 顶层 *k :）', (c) => c.replace('services:', 'x-k: &k include\n*k :\n  - outside-compose.yaml\nservices:'), '出现 include'],
  ['别名当键隐藏 extends', (c) => c.replace('  web-runner:', '  web-runner:\n    x-e: &e extends\n    *e :\n      {file: outside-compose.yaml, service: base}'), '出现 extends'],
  ['env_file 长格式 path + required:false（路径不存在）', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    env_file:\n      - path: /nonexistent/never.env\n        required: false\n`), '出现 env_file|解析层'],
  ['mysql 用 build 短语法（字符串形式）', (c) => c.replace('  mysql:\n    image: mysql:8.4\n', '  mysql:\n    image: mysql:8.4\n    build: ../../server/platform\n'), 'mysql 声明 build'],
  ['healthcheck 用十六进制宿主（-h 0xc0a80105）', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "mysqladmin ping -h 0xc0a80105 -P 3306"]'), '非冒号/带选项写法'],
  ['healthcheck 用整数宿主（-h 3232235781）', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "mysqladmin ping -h 3232235781 -P 3306"]'), '非冒号/带选项写法'],
  ['healthcheck 用零填充端口（-P 03306）', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "mysqladmin ping -h host.docker.internal -P 03306"]'), '非冒号/带选项写法'],
  ['healthcheck 用十六进制端口（--port=0xCEA）', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "mysqladmin ping -h host.docker.internal --port=0xCEA"]'), '非冒号/带选项写法'],
  ['发布端口绑 0.0.0.0', (c) => c.replace('      - "127.0.0.1:13306:3306"', '      - "0.0.0.0:13306:3306"'), '只允许 127.0.0.1'],
  ['发布端口绑局域网 IP', (c) => c.replace('      - "127.0.0.1:13306:3306"', '      - "192.168.1.5:13306:3306"'), '只允许 127.0.0.1'],
  ['发布端口长格式省略 host_ip', (c) => c.replace('      - "127.0.0.1:13306:3306"', '      - target: 3306\n        published: 13306'), '只允许 127.0.0.1'],
  ['env_file 长格式 required:true + 仓库内已存在文件', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    env_file:\n      - path: ../../deploy/isolated/.env\n        required: true\n`), '出现 env_file|解析层'],
  ['env_file 空数组', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    env_file: []\n`), '出现 env_file|解析层'],
  ['healthcheck 用 /dev/tcp 十进制整数宿主', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "exec 3<>/dev/tcp/3232235781/3306"]'), '非冒号/带选项写法'],
  ['healthcheck 用 nc 位置参数十进制整数宿主', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "nc -z -w 3 3232235781 3306"]'), '非冒号/带选项写法'],
  ['healthcheck 用 telnet 十六进制宿主', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "telnet 0xc0a80105 3306"]'), '非冒号/带选项写法'],
  ['环境值 /dev/tcp 十进制整数宿主', (c) => c.replace('http://web-runner:9323', "bash -c 'exec 3<>/dev/tcp/3232235781/3306'"), '非冒号/带选项写法'],
  ['环境值 @ 十进制整数宿主', (c) => c.replace('http://web-runner:9323', 'ssh deploy@3232235781 -p 3306'), '非冒号/带选项写法'],
  ['环境值 --resolve 十六进制宿主', (c) => c.replace('http://web-runner:9323', 'curl --resolve api.example.com:3306:0xc0a80105 https://api.example.com/'), '非冒号/带选项写法'],
  ['环境值短写点分宿主 192.168.1', (c) => c.replace('http://web-runner:9323', 'mysqladmin -h 192.168.1 -P 3306 ping'), '非冒号/带选项写法'],
  ['环境值短写点分宿主 10.0', (c) => c.replace('http://web-runner:9323', 'mysqladmin -h 10.0 --port=3306 ping'), '非冒号/带选项写法'],
  ['环境值点分混合十六进制宿主', (c) => c.replace('http://web-runner:9323', 'mysqladmin -h 0xc0.0xa8.0x01.0x05 -P 3306 ping'), '非冒号/带选项写法'],
  ['端口条目声明 app_protocol 子键', (c) => c.replace('      - "127.0.0.1:13306:3306"', '      - target: 3306\n        published: 13306\n        host_ip: 127.0.0.1\n        app_protocol: http'), '未允许的子键'],
  ['端口条目声明 name 子键', (c) => c.replace('      - "127.0.0.1:13306:3306"', '      - target: 3306\n        published: 13306\n        host_ip: 127.0.0.1\n        name: web'), '未允许的子键'],
  ['主机与端口拆到同一服务的不同值', (c) => c.replace('http://web-runner:9323', 'http://host.docker.internal/').replace('APP_WEB_AUTOMATION_RUNNER_ENABLED: "true"', 'APP_WEB_AUTOMATION_RUNNER_ENABLED: "3306"'), '合起来出现非 loopback 主机与旧栈端口'],
  ['主机与端口分别落在同服务的环境值与 healthcheck', (c) => c.replace("bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080'", 'mysqladmin ping --connect-timeout=300 --port=3306').replace('http://web-runner:9323', 'http://host.docker.internal/'), '合起来出现非 loopback 主机与旧栈端口'],
  ['healthcheck 十六进制宿主带前导零', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "mysqladmin ping -h 0x00c0a80105 -P 3306"]'), '非冒号/带选项写法'],
  ['healthcheck 十进制宿主 66051（0.1.2.3）', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "mysqladmin ping -h 66051 -P 3306"]'), '非冒号/带选项写法'],
  ['healthcheck 十进制宿主 16777215', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "mysqladmin ping -h 16777215 -P 3306"]'), '非冒号/带选项写法'],
  ['healthcheck 零填充端口 0003306', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "mysqladmin ping -h host.docker.internal -P 0003306"]'), '非冒号/带选项写法'],
  ['healthcheck 十六进制端口 0x000CEA', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "mysqladmin ping -h host.docker.internal --port=0x000CEA"]'), '非冒号/带选项写法'],
  ['healthcheck atoi 尾缀端口 3306.0', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "mysqladmin ping -h host.docker.internal --port=3306.0"]'), '非冒号/带选项写法'],
  ['聚合拆分：bracketed IPv4-mapped 宿主 + 端口', (c) => c.replace('http://web-runner:9323', 'http://[::ffff:c0a8:105]/').replace('APP_WEB_AUTOMATION_RUNNER_ENABLED: "true"', 'APP_WEB_AUTOMATION_RUNNER_ENABLED: "3306"'), '合起来出现非 loopback 主机与旧栈端口'],
  ['聚合拆分：百分号编码宿主 + 端口', (c) => c.replace('http://web-runner:9323', 'http://%31%39%32%2E%31%36%38%2E%31%2E%35/').replace('APP_WEB_AUTOMATION_RUNNER_ENABLED: "true"', 'APP_WEB_AUTOMATION_RUNNER_ENABLED: "3306"'), '合起来出现非 loopback 主机与旧栈端口'],
  ['APP_PORT 写成旧栈端口 3306', (c) => c.replace('      APP_PORT: 8080', '      APP_PORT: 3306'), 'APP_PORT'],
  ['APP_PORT 与服务映射容器端口不一致', (c) => c.replace('      APP_PORT: 8080', '      APP_PORT: 9090'), 'APP_PORT'],
  ['删掉 Runner 私网开关（未设置即开启）', (c) => c.replace('      APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS: "false"\n', ''), 'runner-egress-policy'],
  ['Runner 私网开关写 FALSE', (c) => c.replace('APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS: "false"', 'APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS: "FALSE"'), 'runner-egress-policy'],
  ['Runner 私网开关写 0', (c) => c.replace('APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS: "false"', 'APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS: "0"'), 'runner-egress-policy'],
  ['同号同协议重复发布', (c) => c.replace('      - "127.0.0.1:19324:9323"', '      - target: 9323\n        published: 19324\n        host_ip: 127.0.0.1\n      - target: 9324\n        published: 19324\n        host_ip: 127.0.0.1'), '同时发布宿主端口'],
  ['healthcheck 八进制点分宿主 0300.0250.01.05', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "mysqladmin ping -h 0300.0250.01.05 -P 3306"]'), '非冒号/带选项写法'],
  ['healthcheck 八进制点分宿主 0377.0.0.1', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "mysqladmin ping -h 0377.0.0.1 -P 3306"]'), '非冒号/带选项写法'],
  ['冒号形态八进制点分宿主', (c) => c.replace('http://web-runner:9323', 'http://0300.0250.01.05:3306/'), '旧栈宿主端点'],
  ['删掉登录发现私网开关', (c) => c.replace('      APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOW_PRIVATE_NETWORKS: "false"\n', ''), 'runner-egress-policy'],
  ['登录发现私网开关写 FALSE', (c) => c.replace('APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOW_PRIVATE_NETWORKS: "false"', 'APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOW_PRIVATE_NETWORKS: "FALSE"'), 'runner-egress-policy'],
  ['删掉 web-runner 服务', (c) => c.replace(/  web-runner:[\s\S]*?\n  hermes:\n/, '  hermes:\n'), 'required-services'],
  ['healthcheck 端口 11 位十六进制', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "mysqladmin ping -h host.docker.internal --port=0x000000000CEA"]'), '非冒号/带选项写法'],
  ['healthcheck 端口 13 位十进制', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "mysqladmin ping -h host.docker.internal --port=0000000003306"]'), '非冒号/带选项写法'],
  ['healthcheck 零填充十六进制段宿主', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "mysqladmin ping -h 0x00c0.0xa8.1.5 -P 3306"]'), '非冒号/带选项写法'],
  ['healthcheck 三段尾段载体 0.0.300', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "mysqladmin ping -h 0.0.300 -P 3306"]'), '非冒号/带选项写法'],
  ['healthcheck 三段八进制尾段 0.0.0400', (c) => c.replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '    healthcheck:\n      test: ["CMD-SHELL", "mysqladmin ping -h 0.0.0400 -P 3306"]'), '非冒号/带选项写法'],
  ['写入登录发现允许主机白名单（只能放宽，不放行）', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}      APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOSTS: "*.internal"\n`), '未声明的环境变量键'],
  ['构建上下文里没有 Dockerfile', (c) => c.replace('context: ../../apps/web', 'context: ./contexts/empty'), '构建文件不存在'],
  ['写入 web-runner 的登录发现允许主机白名单', (c) => c.replace('      APP_WEB_AUTOMATION_RUNNER_PORT: 9323\n', '      APP_WEB_AUTOMATION_RUNNER_PORT: 9323\n      APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOSTS: "*.internal"\n'), '未声明的环境变量键'],
  ['删掉 web-runner 服务时出站开关检查自身判 FAIL', (c) => c.replace(/  web-runner:[\s\S]*?\n  hermes:\n/, '  hermes:\n'), 'runner-egress-policy: web-runner 服务不存在'],
  ['project 名被改（project-name）', (c) => c.replace('name: haitlify-isolated', 'name: other-stack'), 'project-name'],
  ['宿主相对目录绑定挂载（mounts）', (c) => c.replace('  web-runner:\n', '  web-runner:\n    volumes:\n      - ./host-data:/data\n'), 'mounts'],
  ['库定义分叉（database-definition）', (c) => c.replace('MYSQL_DATABASE: haitlify_isolated', 'MYSQL_DATABASE: haitlify_other'), 'database-definition'],
  ['宿主 secrets 文件（host-file-sources）', (c) => c.replace('networks:\n  default:', 'secrets:\n  probe_secret:\n    file: ./probe.txt\n\nnetworks:\n  default:').replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    secrets:\n      - probe_secret\n`), 'host-file-sources'],
  ['跨服务共享命名卷（volume-isolation）', (c) => c.replace('      - type: bind\n        source: ./.preflight/isolated-preflight.json', '      - shared-data:/data\n      - type: bind\n        source: ./.preflight/isolated-preflight.json').replace('    ports:\n      - "127.0.0.1:19324:9323"', '    volumes:\n      - shared-data:/data\n    ports:\n      - "127.0.0.1:19324:9323"').replace('volumes:\n  mysql-data:', 'volumes:\n  shared-data:\n    name: haitlify-isolated-shared\n  mysql-data:'), 'volume-isolation'],
  ['端口段而非单一整数（published-ports-explicit）', (c) => c.replace('      - "127.0.0.1:13306:3306"', '      - "127.0.0.1:13306-13310:3306"'), 'published-ports-explicit'],
  ['镜像未固定 tag（image-pinning）', (c) => c.replace('image: mysql:8.4', 'image: mysql'), 'image-pinning'],
  ['Hermes 发布宿主端口（hermes-topology）', (c) => c.replace('    container_name: haitlify-isolated-hermes\n', '    container_name: haitlify-isolated-hermes\n    ports:\n      - "127.0.0.1:9420:9420"\n'), 'hermes-topology'],
  ['Hermes 宿主目录挂载（hermes-topology）', (c) => c.replace('      - type: volume\n        source: hermes-state\n        target: /state', '      - type: bind\n        source: ~/.hermes\n        target: /state'), 'hermes-topology'], // preflight:synthetic-personal-home
  ['platform 缺少环境标识（service-startup-guard）', (c) => c.replace('      APP_ENV_ID: haitlify-isolated\n', ''), 'service-startup-guard'],
  ['旧资源标识命中', (c) => c.replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}      LEGACY_NOTE: ${LEGACY_DB}\n`), 'environment-values'],
]

for (const [name, mutate, expectDetail] of NEGATIVE_CASES) {
  test(`拒绝：${name}`, () => {
    const dir = makeRepo()
    const file = writeVariant(dir, 'variant', mutate(baselineCompose()))
    const result = check(dir, file)
    assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
    // 断言「因哪个检查失败」：否则用例可能因其它检查碰巧 FAIL 而失去意义（第十轮复核指出）。
    if (expectDetail) {
      // 只在 FAIL 行里匹配：裸检查项名会被同名的 [PASS] 行命中，导致断言无鉴别力（第十三轮复核指出）。
      const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
      assert.match(failLines, new RegExp(expectDetail), `期望失败行包含 ${expectDetail}，实际失败行：\n${failLines}`)
    }
  })
}

test('拒绝：CR-only 行尾的 compose 里 env_file', () => {
  const dir = makeRepo()
  const text = baselineCompose().replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    env_file:\n      - /etc/hosts\n`)
  const file = writeVariant(dir, 'cronly', text, 'cr-only')
  const result = check(dir, file)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
    const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
    assert.match(failLines, /env_file|include|extends/, `期望 FAIL 行指出机制命中，实际：\n${failLines}`)
})

test('拒绝：整份 compose 为 UTF-16LE+BOM 且含 env_file', () => {
  const dir = makeRepo()
  const text = baselineCompose().replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    env_file:\n      - /etc/hosts\n`)
  const file = writeVariant(dir, 'utf16le', text, 'utf16le-bom')
  const result = check(dir, file)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
    const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
    assert.match(failLines, /env_file|include|extends/, `期望 FAIL 行指出机制命中，实际：\n${failLines}`)
})

test('拒绝：整份 compose 为 UTF-16BE+BOM 且含 include', () => {
  const dir = makeRepo()
  const text = baselineCompose().replace('services:', 'include:\n  - outside-compose.yaml\nservices:')
  const file = writeVariant(dir, 'utf16be', text, 'utf16be-bom')
  const result = check(dir, file)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
    const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
    assert.match(failLines, /env_file|include|extends/, `期望 FAIL 行指出机制命中，实际：\n${failLines}`)
})

test('拒绝：U+2028（LS）行分隔的 compose 里 env_file', () => {
  const dir = makeRepo()
  const text = baselineCompose().replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}    env_file:\n      - /etc/hosts\n`)
  const file = writeVariant(dir, 'ls-sep', text, 'ls-separated')
  const result = check(dir, file)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  assert.match(result.stdout, /出现 env_file/)
})

test('拒绝：U+0085（NEL）行分隔的 compose 里 include', () => {
  const dir = makeRepo()
  const text = baselineCompose().replace('services:', 'include:\n  - outside-compose.yaml\nservices:')
  const file = writeVariant(dir, 'nel-sep', text, 'nel-separated')
  const result = check(dir, file)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  assert.match(result.stdout, /出现 include/)
})

test('拒绝：符号链接构建上下文指向仓库外', () => {
  const dir = makeRepo()
  const outside = mkdtempSync(path.join(os.tmpdir(), 'haitlify-preflight-outside-'))
  writeFileSync(path.join(outside, 'Dockerfile'), 'FROM scratch\n')
  symlinkSync(outside, path.join(dir, 'deploy', 'isolated', 'ctx-link'), 'dir')
  const file = writeVariant(dir, 'symlink', baselineCompose().replace('context: ../../apps/web', 'context: ./ctx-link'))
  const result = check(dir, file)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  assert.match(result.stdout, /不在仓库内/)
})

test('通过：引号标量内部的 U+2028 不产生伪行边界', () => {
  const dir = makeRepo()
  const file = writeVariant(dir, 'ls-in-quote', baselineCompose()
    .replace('      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]', '      test: ["CMD", "mysqladmin", "ping", "-h", "line1\u2028profiles: []"]'))
  const result = check(dir, file)
  assert.equal(result.status, 0, `期望 PASS(0)，实际 ${result.status}\n${result.stdout}`)
})

test('通过：注释里的机制词与 loopback 值都不算违规', () => {
  const dir = makeRepo()
  const file = writeVariant(dir, 'benign', baselineCompose()
    .replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}      # 这里说明为什么不用 env_file / include / extends\n`))
  const result = check(dir, file)
  assert.equal(result.status, 0, `期望 PASS(0)，实际 ${result.status}\n${result.stdout}`)
})

test('通过：loopback 数值等价形态与旧栈端口并存不算命中', () => {
  const dir = makeRepo()
  const file = writeVariant(dir, 'loopback-equivalents', baselineCompose()
    .replace('    healthcheck:\n      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]',
      '    healthcheck:\n      test: ["CMD-SHELL", "nc -z 0x7f000001 19323 || nc -z 2130706433 19323 || nc -z 127.1 19323"]')
    .replace('http://web-runner:9323', 'http://127.1:19324'))
  const result = check(dir, file)
  assert.equal(result.status, 0, `期望 PASS(0)，实际 ${result.status}\n${result.stdout}`)
})

test('通过：同号 TCP/UDP 发布不算端口冲突', () => {
  const dir = makeRepo()
  const file = writeVariant(dir, 'tcp-udp', baselineCompose()
    .replace('      - "127.0.0.1:19324:9323"', '      - target: 9323\n        published: 19324\n        host_ip: 127.0.0.1\n      - target: 9324\n        published: 19324\n        host_ip: 127.0.0.1\n        protocol: udp'))
  const result = check(dir, file)
  assert.equal(result.status, 0, `期望 PASS(0)，实际 ${result.status}\n${result.stdout}`)
})

test('通过：非 loopback 主机配非旧栈端口不触发聚合规则', () => {
  const dir = makeRepo()
  const file = writeVariant(dir, 'host-no-legacy-port', baselineCompose()
    .replace('http://web-runner:9323', 'http://host.docker.internal:9323'))
  const result = check(dir, file)
  assert.equal(result.status, 0, `期望 PASS(0)，实际 ${result.status}\n${result.stdout}`)
})

test('通过：Runner 两个出站开关写未加引号的 YAML false 等价', () => {
  const dir = makeRepo()
  const file = writeVariant(dir, 'yaml-bool-false', baselineCompose()
    .replace('APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS: "false"', 'APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS: false')
    .replace('APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOW_PRIVATE_NETWORKS: "false"', 'APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOW_PRIVATE_NETWORKS: false'))
  const result = check(dir, file)
  assert.equal(result.status, 0, `期望 PASS(0)，实际 ${result.status}\n${result.stdout}`)
})


test('拒绝：web-runner 的 Dockerfile 用 ENV 写回被撤回的键', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}ENV APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOSTS="*.internal,*.local"\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /ENV\/ARG 写入 APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOSTS/, `实际失败行：\n${failLines}`)
})

test('拒绝：Dockerfile 的 ENV 写 SPRING_APPLICATION_JSON 指向旧栈端点', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'platform', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}ENV SPRING_APPLICATION_JSON='{"spring":{"datasource":{"url":"jdbc:mysql://192.168.1.5:3306/x"}}}'\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /ENV\/ARG 写入 SPRING_APPLICATION_JSON/, `实际失败行：\n${failLines}`)
})

test('拒绝：Dockerfile 里出现旧栈宿主端点', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'apps', 'web', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}ENV LEGACY_PROBE="curl http://10.0.0.7:19323/health"\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /旧栈宿主端点/, `实际失败行：\n${failLines}`)
})


test('拒绝：Dockerfile 的 ENV 反斜杠续行写入禁写键', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'platform', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}ENV APP_BUILD_OK=1 \\n    APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOSTS="*.internal"\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /ALLOWED_HOSTS|禁写键/, `实际失败行：\n${failLines}`)
})

test('拒绝：Dockerfile 的 CMD 用 --env-file 加载环境文件', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}COPY runner-extra.env /app/\nCMD ["node", "--env-file=/app/runner-extra.env", "server.mjs"]\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /--env-file/, `实际失败行：\n${failLines}`)
})

test('拒绝：Dockerfile 的 ENTRYPOINT 用 set -a + source 加载环境文件', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}ENTRYPOINT ["sh", "-c", "set -a; . /app/runner-extra.env; exec node server.mjs"]\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /用 set -a \/ allexport 导出环境/, `实际失败行：\n${failLines}`)
})

test('拒绝：Dockerfile 的 RUN 里出现禁写键（不限 ENV/ARG 行）', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}RUN printf 'export APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOSTS=*.internal\n' > /app/entry.sh\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /禁写键 APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOSTS/, `实际失败行：\n${failLines}`)
})

test('拒绝：Dockerfile 把出站开关写成 true', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}ENV APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS=true\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /出站开关/, `实际失败行：\n${failLines}`)
})

test('拒绝：Dockerfile 写 Spring 数据源池子键（锚定正则覆盖，非子串匹配）', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'platform', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}ENV SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=3\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE/, `实际失败行：\n${failLines}`)
})

test('通过：Dockerfile 把出站开关写成恰好 false（含续行）', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}ENV APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS=false \\\n    APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOW_PRIVATE_NETWORKS=false\n`)
  const result = check(dir)
  assert.equal(result.status, 0, `期望 PASS(0)，实际 ${result.status}\n${result.stdout}`)
})

test('拒绝：合成仓库的 .env 用占位口令（required-secrets）', () => {
  const dir = makeRepo()
  const envFile = path.join(dir, 'deploy', 'isolated', '.env')
  writeFileSync(envFile, 'HAITLIFY_ISOLATED_MYSQL_ROOT_PASSWORD=changeme\nHAITLIFY_ISOLATED_DB_PASSWORD=changeme\nHAITLIFY_ISOLATED_SECRET_KEY=changeme\n')
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /required-secrets/, `实际失败行：\n${failLines}`)
})

test('config-hash：--expect-hash 相符 PASS、不符 FAIL', () => {
  const dir = makeRepo()
  const base = ['check', '--compose', 'deploy/isolated/compose.yaml', '--env-file', 'deploy/isolated/.env',
    '--require-secrets', '--no-host-checks', '--out', path.join(os.tmpdir(), `haitlify-preflight-hash-${Date.now()}.json`)]
  const first = runPreflight(dir, base)
  assert.equal(first.status, 0, `基线期望 PASS(0)，实际 ${first.status}\n${first.stdout}`)
  const match = first.stdout.match(/configHash=([0-9a-f]{64})/)
  assert.ok(match, `期望输出里有 configHash，实际：\n${first.stdout}`)
  const ok = runPreflight(dir, [...base, '--expect-hash', match[1]])
  assert.equal(ok.status, 0, `相符哈希期望 PASS(0)，实际 ${ok.status}\n${ok.stdout}`)
  const bad = runPreflight(dir, [...base, '--expect-hash', '0'.repeat(64)])
  assert.equal(bad.status, 1, `不符哈希期望 FAIL(1)，实际 ${bad.status}\n${bad.stdout}`)
  const failLines = bad.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /config-hash/, `实际失败行：\n${failLines}`)
})

test('拒绝：Dockerfile 的 RUN 用 . 加载任意名字的文件', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}RUN . /app/runner.conf\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /source 或 \.|加载外部文件/, `实际失败行：\n${failLines}`)
})

test('拒绝：Dockerfile 的 ENTRYPOINT 用 sh -c 加 . 加载文件', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}ENTRYPOINT ["sh", "-c", ". /app/runner.conf; exec node server.mjs"]\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /加载外部文件/, `实际失败行：\n${failLines}`)
})

test('拒绝：Dockerfile 的 CMD 用 export $(cat …) 注入环境', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}CMD ["sh", "-c", "export $(cat /app/entry.sh); exec node server.mjs"]\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /命令替换|读取文件/, `实际失败行：\n${failLines}`)
})

test('拒绝：Dockerfile 用 NODE_OPTIONS --require 预加载脚本', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}ENV NODE_OPTIONS=--require /app/loader.js\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /NODE_OPTIONS 里用预加载标志|预加载/, `实际失败行：\n${failLines}`)
})

test('拒绝：Dockerfile 用 BASH_ENV 指定启动文件', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}ENV BASH_ENV=/app/startup.sh\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /BASH_ENV\/ENV/, `实际失败行：\n${failLines}`)
})

test('拒绝：Dockerfile 的 --env-file 被续行拆成两行', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}CMD ["node", \\\n  "--env-file=/app/runner.conf", "server.mjs"]\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /--env-file/, `实际失败行：\n${failLines}`)
})

test('拒绝：Dockerfile 用 ENV 的 space 形式把出站开关写成 true', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}ENV APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS true\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /出站开关/, `实际失败行：\n${failLines}`)
})

test('拒绝：Dockerfile 用 ARG 声明出站开关键名', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}ARG APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOW_PRIVATE_NETWORKS\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\\n').filter((line) => line.includes('[FAIL]')).join('\\n')
  assert.match(failLines, /出站开关/, `实际失败行：\n${failLines}`)
})

test('拒绝：compose build.args 的值里出现禁写键名', () => {
  const dir = makeRepo()
  const file = writeVariant(dir, 'args-key-name', baselineCompose()
    .replace('    build:\n      context: ../../server/platform', '    build:\n      context: ../../server/platform\n      args:\n        HOSTKEY: APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOSTS'))
  const result = check(dir, file)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /build\.args\.HOSTKEY 的值里出现禁写键名/, `实际失败行：\n${failLines}`)
})

test('原语：大位长数值宿主按低 32 位截断（与 macOS libc 一致）', () => {
  assert.equal(hostForm('0x0000000000000000c0a80105'), '192.168.1.5')
  assert.equal(hostForm('0x100000000000007f000001'), '127.0.0.1')
  assert.equal(hostForm('0377777777777'), '255.255.255.255')
  assert.equal(hostForm('4294967296'), '0.0.0.0')
})

test('拒绝：CMD 里 export 出站开关键为 true', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}CMD ["sh", "-c", "export APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS=true; exec node server.mjs"]\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /在非 ENV\/ARG 位置出现出站开关/, `实际失败行：\n${failLines}`)

})

test('拒绝：RUN 把出站开关键写进文件', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}RUN echo 'export APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOW_PRIVATE_NETWORKS=true' >> /app/e.sh\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /在非 ENV\/ARG 位置出现出站开关/, `实际失败行：\n${failLines}`)

})

test('拒绝：带引号的命令替换注入环境', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}CMD ["sh", "-c", "eval "$(cat /app/x)"; exec node server.mjs"]\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /命令替换/, `实际失败行：\n${failLines}`)

})

test('拒绝：非 cat 读取器（awk）注入环境', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}RUN export $(awk '{print}' /app/x)\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /命令替换/, `实际失败行：\n${failLines}`)

})

test('拒绝：node -r 预加载脚本', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}ENTRYPOINT ["node", "-r", "/app/loader.js", "server.mjs"]\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /node 预加载标志/, `实际失败行：\n${failLines}`)

})

test('拒绝：NODE_OPTIONS 用 --import', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}ENV NODE_OPTIONS=--import /app/loader.mjs\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /预加载/, `实际失败行：\n${failLines}`)

})

test('拒绝：heredoc 体内加载环境文件', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}RUN <<EOO\n. /app/x\nexec node server.mjs\nEOO\n`)
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /heredoc/, `实际失败行：\n${failLines}`)

})

test('拒绝：COPY 进来的入口脚本里加载外部环境', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}COPY entrypoint.sh /app/entrypoint.sh\nRUN chmod +x /app/entrypoint.sh\nENTRYPOINT ["/app/entrypoint.sh"]\n`)
  writeFileSync(path.join(dir, 'server', 'web-runner', 'entrypoint.sh'), 'set -a\n. /app/env\nexec node server.mjs\n')
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /入口脚本/, `实际失败行：\n${failLines}`)


})

test('拒绝：build.args 用出站开关键名作参数', () => {
  const dir = makeRepo()
  const file = writeVariant(dir, 'args-egress-key', baselineCompose()
    .replace('    build:\n      context: ../../server/platform', '    build:\n      context: ../../server/platform\n      args:\n        APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS: x'))
  const result = check(dir, file)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /build\.args\.APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS 用禁写键名作构建参数/, `实际失败行：\n${failLines}`)

})

test('通过：构建文件里的常见写法（cp -r . /srv/）', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}RUN cp -r . /srv/ && echo ok\n`)
  const result = check(dir)
  assert.equal(result.status, 0, `期望 PASS(0)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  
})

test('通过：构建文件里的常见写法（node . 执行目录）', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}RUN node . && echo ok\n`)
  const result = check(dir)
  assert.equal(result.status, 0, `期望 PASS(0)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  
})

test('通过：构建文件里的常见写法（jq . file）', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}RUN jq . /app/config.json\n`)
  const result = check(dir)
  assert.equal(result.status, 0, `期望 PASS(0)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  
})

test('通过：构建文件里的常见写法（cURL 安装 dotenv 包名）', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `${readFileSync(dockerfile, 'utf8')}RUN pip install python-dotenv && echo ok\n`)
  const result = check(dir)
  assert.equal(result.status, 0, `期望 PASS(0)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  
})

test('scan：仓库内出现个人 Hermes home 路径即命中（repo 检查）', () => {
  const dir = makeRepo()
  // 路径在测试文件里按片段拼接，避免测试自身被仓库级 scan 命中。
  const personalPath = ['/Users', 'someone', '.hermes', 'cache'].join('/')
  writeFileSync(path.join(dir, 'server', 'platform', 'notes.md'), `配置目录：${personalPath}\n`)
  execFileSync('git', ['add', '-A'], { cwd: dir })
  const result = runPreflight(dir, ['scan', '--json'])
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  assert.match(result.stdout, /hermes-home-abs/, `实际输出：\n${result.stdout}`)
})

test('scan：仅允许已知容器专属 home，同行额外个人路径仍拒绝', () => {
  const dir = makeRepo()
  const worker = path.join(dir, 'deploy/isolated/worker-pool')
  mkdirSync(worker, { recursive: true })
  writeFileSync(path.join(worker, 'Dockerfile.worker'),
    'ENV HOME=/home/worker HERMES_HOME=/home/worker/.hermes PYTHONDONTWRITEBYTECODE=1\n') // preflight:synthetic-personal-home
  writeFileSync(path.join(worker, 'provisioner.py'),
    "environment={'HOME':'/home/worker','HERMES_HOME':'/home/worker/.hermes',\n") // preflight:synthetic-personal-home
  assert.equal(runPreflight(dir, ['scan', '--json']).status, 0)
  writeFileSync(path.join(worker, 'Dockerfile.worker'),
    'ENV HOME=/home/worker HERMES_HOME=/home/worker/.hermes OTHER=/home/operator/.hermes\n') // preflight:synthetic-personal-home
  const rejected = runPreflight(dir, ['scan', '--json'])
  assert.equal(rejected.status, 1)
  assert.match(rejected.stdout, /hermes-home-abs/)
})

test('scan：容器 home 不可泛化为任意宿主路径白名单', () => {
  for (const value of ['/Users/operator/.hermes', '$HOME/.hermes', '~/.hermes', '/home/worker/.hermes']) { // preflight:synthetic-personal-home
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'apps/web/Dockerfile'), `ENV HERMES_HOME=${value}\n`)
    const result = runPreflight(dir, ['scan', '--json'])
    assert.equal(result.status, 1, '未知文件中的个人 home 必须继续被拒绝')
    assert.match(result.stdout, /hermes-home-/)
  }
})

test('scan：合成路径标记不能豁免其他文件', () => {
  const dir = makeRepo()
  writeFileSync(path.join(dir, 'server/platform/notes.md'),
    '/Users/operator/.hermes // preflight:synthetic-personal-home\n') // preflight:synthetic-personal-home
  const result = runPreflight(dir, ['scan', '--json'])
  assert.equal(result.status, 1)
  assert.match(result.stdout, /hermes-home-abs/)
})

test('scan：相同路径出现在非批准声明中仍拒绝', () => {
  for (const [name, text] of [
    ['Dockerfile.worker', 'RUN echo HERMES_HOME=/home/worker/.hermes > /host-path\n'], // preflight:synthetic-personal-home
    ['provisioner.py', "host_bind_mount={'HERMES_HOME':'/home/worker/.hermes',}\n"], // preflight:synthetic-personal-home
  ]) {
    const dir = makeRepo()
    const worker = path.join(dir, 'deploy/isolated/worker-pool')
    mkdirSync(worker, { recursive: true })
    writeFileSync(path.join(worker, name), text)
    const result = runPreflight(dir, ['scan', '--json'])
    assert.equal(result.status, 1)
    assert.match(result.stdout, /hermes-home-abs/)
  }
})

test('scan：已知测试文件中的任意代码不可借合成标记豁免', () => {
  const dir = makeRepo()
  writeFileSync(path.join(dir, 'scripts/preflight/environment-preflight.test.mjs'),
    'const runtimeHome = "/home/operator/.hermes"; // preflight:synthetic-personal-home\n') // preflight:synthetic-personal-home
  const result = runPreflight(dir, ['scan', '--json'])
  assert.equal(result.status, 1)
  assert.match(result.stdout, /hermes-home-abs/)
})

test('拒绝：入口脚本形态全表（COPY + ENTRYPOINT 直接执行按同一套规则扫描）', () => {
  const denyScripts = [
    ['行首 . 加载', '#!/bin/sh\n. /app/env\nexec node server.mjs\n'],
    ['source 加载', '#!/bin/sh\nsource /app/env\nexec node server.mjs\n'],
    ['命令替换读文件', '#!/bin/sh\nexport $(cat /app/env)\nexec node server.mjs\n'],
    ['引号内命令替换', '#!/bin/sh\neval \"$(cat /app/env)\"\nexec node server.mjs\n'],
    ['非 cat 读取器', '#!/bin/sh\nexport $(awk 1 /app/env)\nexec node server.mjs\n'],
    ['while read 重定向', '#!/bin/sh\nwhile read -r l; do export \"$l\"; done < /app/env\n'],
    ['set -o allexport', '#!/bin/sh\nset -o allexport\n. /app/env\nexec node server.mjs\n'],
    ['--env-file', '#!/bin/sh\nexec node --env-file=/app/extra.env server.mjs\n'],
    ['NODE_OPTIONS 预加载', '#!/bin/sh\nexport NODE_OPTIONS=--require=/app/loader.js\nexec node server.mjs\n'],
    ['BASH_ENV', '#!/bin/sh\nexport BASH_ENV=/app/startup.sh\nexec node server.mjs\n'],
    ['dotenv', '#!/bin/sh\ndotenv -e /app/extra.env -- node server.mjs\n'],
    ['字面出站开关', '#!/bin/sh\nexport APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS=true\nexec node server.mjs\n'],
  ]
  for (const [label, script] of denyScripts) {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, `FROM scratch\nCOPY entry.sh /app/entry.sh\nENTRYPOINT [\"/app/entry.sh\"]\n`)
    writeFileSync(path.join(dir, 'server', 'web-runner', 'entry.sh'), script)
    const result = check(dir)
    assert.equal(result.status, 1, `期望 FAIL(1)（${label}），实际 ${result.status}\n${result.stdout}`)
    const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
    assert.match(failLines, /入口脚本/, `实际失败行：\n${failLines}`)
  }
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, `FROM scratch\nCOPY entry.sh /app/entry.sh\nENTRYPOINT [\"/app/entry.sh\"]\n`)
  writeFileSync(path.join(dir, 'server', 'web-runner', 'entry.sh'), '#!/bin/sh\necho hello\nexec node server.mjs\n')
  assert.equal(check(dir).status, 0, '正常入口脚本不应被拒')
})

test('拒绝：入口脚本的提供方式（ADD/多源 COPY/目录 COPY/COPY --from/WORKDIR 相对路径）', () => {
  const denyDockerfiles = [
    ['ADD 同路径', 'FROM scratch\nADD entry.sh /app/entry.sh\nENTRYPOINT [\"/app/entry.sh\"]\n'],
    ['多源 COPY', 'FROM scratch\nCOPY entry.sh notes.md /app/\nENTRYPOINT [\"/app/entry.sh\"]\n'],
    ['目录 COPY', 'FROM scratch\nCOPY scripts/ /app/\nENTRYPOINT [\"/app/entry.sh\"]\n'],
    ['WORKDIR 相对路径入口', 'FROM scratch\nWORKDIR /app\nCOPY entry.sh ./entry.sh\nENTRYPOINT [\"sh\", \"-c\", \"./entry.sh\"]\n'],
  ]
  const script = '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'
  for (const [label, dockerfileText] of denyDockerfiles) {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'), dockerfileText)
    writeFileSync(path.join(dir, 'server', 'web-runner', 'entry.sh'), script)
    mkdirSync(path.join(dir, 'server', 'web-runner', 'scripts'), { recursive: true })
    writeFileSync(path.join(dir, 'server', 'web-runner', 'scripts', 'entry.sh'), script)
    const result = check(dir)
    assert.equal(result.status, 1, `期望 FAIL(1)（${label}），实际 ${result.status}\n${result.stdout}`)
    const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
    assert.match(failLines, /入口脚本/, `实际失败行（${label}）：\n${failLines}`)
  }
  const dir = makeRepo()
  writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'), 'FROM scratch\nCOPY --from=builder /app/entry.sh /app/entry.sh\nENTRYPOINT [\"/app/entry.sh\"]\n')
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)（COPY --from 不可见 ⇒ fail-closed），实际 ${result.status}`)
  assert.match(result.stdout, /预检阶段不可见/, `实际输出：\n${result.stdout}`)
})

test('拒绝：尾随空白的续行（反斜杠+空格/Tab）不改变判定', () => {
  const cases = [
    ['--env-file 拆到续行', 'CMD [\"node\", \\   \n  \"--env-file=/app/x\", \"s.mjs\"]\n', /使用 --env-file/],
    ['命令替换拆到续行', 'RUN echo a \\\t\n&& eval $(cat /app/x)\n', /命令替换/],
    ['preload 拆到续行', 'ENTRYPOINT [\"node\", \\ \n  \"-r\", \"/app/l.js\"]\n', /node 预加载标志/],
  ]
  for (const [label, extra, pattern] of cases) {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, `FROM scratch\n${extra}`)
    const result = check(dir)
    assert.equal(result.status, 1, `期望 FAIL(1)（${label}），实际 ${result.status}\n${result.stdout}`)
    const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
    assert.match(failLines, pattern, `实际失败行（${label}）：\n${failLines}`)
  }
})

test('拒绝：dotenv 在命令词位置 / space 形式出站开关被同行的 = 短路的形态', () => {
  const cases = [
    ['dotenv 命令词位置', 'RUN dotenv -e /app/x ls -la\n', /使用 dotenv/],
    ['npx dotenv', 'CMD [\"npx\", \"dotenv\", \"-e\", \"/app/x\"]\n', /使用 dotenv/],
    ['space 形式 + 无关 = 对', 'ENV APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS true PATH=/usr/local/bin\n', /出站开关/],
    ['space 形式 + 带引号值 + = 对', 'ENV APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS \"false\" X=1\n', /出站开关/],
  ]
  for (const [label, extra, pattern] of cases) {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, `FROM scratch\n${extra}`)
    const result = check(dir)
    assert.equal(result.status, 1, `期望 FAIL(1)（${label}），实际 ${result.status}\n${result.stdout}`)
    const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
    assert.match(failLines, pattern, `实际失败行（${label}）：\n${failLines}`)
  }
})

test('通过：-c 之后的普通实参、引号内的 <、无词边界的 set -a 不被误拦', () => {
  const benign = [
    'RUN grep -c source /app/notes.txt\n',
    'RUN tar -c . -f /tmp/app.tar\n',
    'RUN node -e \"console.log(1<2)\"\n',
    "RUN awk 'BEGIN{for(i=0;i<10;i++)print i}'\n",
    'RUN npm config set -a foo\n',
  ]
  for (const extra of benign) {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, `FROM scratch\n${extra}`)
    const result = check(dir)
    assert.equal(result.status, 0, `期望 PASS(0)，实际 ${result.status}\n${result.stdout}\n用例：${extra}`)
  }
})

test('行号：含续行的构建文件里，① ENV/ARG 与 ② 端点层都用物理行号', () => {
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, 'FROM scratch\nENV A=1 \\\n    B=2\nENV APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOSTS=x\nRUN echo hi\nRUN echo bye\nENV NOTE=\"curl http://10.0.0.7:19323/health\"\n')
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
  assert.match(failLines, /第 4 行 ENV\/ARG 写入 APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOSTS/, `实际失败行：\n${failLines}`)
  assert.match(failLines, /第 7 行出现旧栈宿主端点/, `实际失败行：\n${failLines}`)
})

test('拒绝：ADD 远程 URL 提供入口脚本（内容不可见 ⇒ fail-closed）', () => {
  const dir = makeRepo()
  writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'), 'FROM scratch\nADD https://example.test/entry.sh /app/entry.sh\nENTRYPOINT [\"/app/entry.sh\"]\n')
  const result = check(dir)
  assert.equal(result.status, 1, `期望 FAIL(1)，实际 ${result.status}\n${result.stdout}`)
  assert.match(result.stdout, /构建阶段|远程/, `实际输出：\n${result.stdout}`)
})

test('拒绝：界外的等价端口写法（18 位十六进制 / 28 位十进制）也命中', () => {
  for (const token of ['0x' + '0'.repeat(15) + 'CEA', '0'.repeat(24) + '3306']) {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, `FROM scratch\nRUN mysqladmin ping -h 192.168.1.5 --port=${token}\n`)
    const result = check(dir)
    assert.equal(result.status, 1, `期望 FAIL(1)（${token}），实际 ${result.status}`)
  }
})

test('拒绝：第二十三轮的 8 类不可见/绕过形态', () => {
  const cases = [
    // [标签, 追加的 Dockerfile 片段, 需要写入的上下文文件, 断言]
    ['COPY . dest 的目录源入口脚本', 'COPY . /app\nENTRYPOINT [\"/app/entry.sh\"]\n', { 'entry.sh': '#!/bin/sh\n. /app/env\n' }, /入口脚本/],
    ['COPY dir dest（无尾斜杠）的目录源', 'COPY scripts /app\nENTRYPOINT [\"/app/entry.sh\"]\n', { 'scripts/entry.sh': '#!/bin/sh\n. /app/env\n' }, /入口脚本/],
    ['键名跨续行拆开（ALLOWED_HOSTS）', 'ENV APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOS\\\nTS=*.internal\n', {}, /禁写键/],
    ['键名跨续行拆开（配置来源）', 'ENV SPRING_CONFIG_ADDITIONAL_LOCATIO\\\nN=file:/tmp/evil/\n', {}, /禁写键/],
    ['键名跨续行拆开（出站开关）', 'ENV APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCES\\\nS=true\n', {}, /出站开关/],
    ['RUN 写出入口脚本（无来源）', 'RUN printf \"%s\\n\" \"set -a\" > /app/entry.sh\nENTRYPOINT [\"/app/entry.sh\"]\n', {}, /不可见/],
    ['裸相对名入口（sh entry.sh）', 'WORKDIR /app\nCOPY entry.sh ./entry.sh\nENTRYPOINT [\"sh\", \"-c\", \"sh entry.sh\"]\n', { 'entry.sh': '#!/bin/sh\n. /app/env\n' }, /入口脚本/],
    ['变量间接入口', 'ENV APP_ENTRY=/app/entry.sh\nCOPY entry.sh /app/entry.sh\nCMD sh -c \"$APP_ENTRY\"\n', { 'entry.sh': '#!/bin/sh\n. /app/env\n' }, /变量间接/],
    ['RUN set -a && node（命令串起始）', 'RUN set -a && node server.mjs\n', {}, /set -a/],
    ['脚本行首 set -a + 续行 + . /x', 'COPY entry.sh /app/entry.sh\nENTRYPOINT [\"/app/entry.sh\"]\n', { 'entry.sh': '#!/bin/sh\nset -a \\\n. /app/env\n' }, /set -a/],
    ['脚本行首 ENV=（不被当成 Docker 指令）', 'COPY entry.sh /app/entry.sh\nENTRYPOINT [\"/app/entry.sh\"]\n', { 'entry.sh': '#!/bin/sh\nENV=/app/evil.sh\nsh -c \"node s.mjs\"\n' }, /启动文件变量/],
    ['入口脚本源指向仓库外（悬空符号链接）', 'COPY entry.sh /app/entry.sh\nENTRYPOINT [\"/app/entry.sh\"]\n', { 'symlink': 'entry.sh' }, /不可见|入口脚本/],
  ]
  for (const [label, extra, files, pattern] of cases) {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      if (content === 'entry.sh') symlinkSync('/opt/evil.sh', target)
      else writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, 1, `期望 FAIL(1)（${label}），实际 ${result.status}\n${result.stdout}`)
    const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
    assert.match(failLines, pattern, `实际失败行（${label}）：\n${failLines}`)
  }
})

test('通过：注释/引号里的键名提及、合法 ENV 组合、普通 -c 选项不被误拦', () => {
  const benign = [
    '# APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOSTS 未设置\n',
    'RUN echo \"SPRING_APPLICATION_JSON is not used\"\n',
    'ENV APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS=false OTHER=1\n',
    'RUN npm config set -a foo\n',
  ]
  for (const extra of benign) {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, `FROM scratch\n${extra}`)
    const result = check(dir)
    assert.equal(result.status, 0, `期望 PASS(0)，实际 ${result.status}\n${result.stdout}\n用例：${extra}`)
  }
})

test('拒绝：无扩展名入口脚本（脚本类判定不只 .sh）与 /bin 反向', () => {
  const deny = [
    ['ENTRYPOINT 数组无来源', 'ENTRYPOINT [\"/app/entry\"]\n'],
    ['CMD 绝对路径无来源', 'WORKDIR /app\nCMD [\"sh\", \"/app/entry\"]\n'],
    ['shell 形式无来源', 'CMD [\"sh\", \"-c\", \"exec /app/entry\"]\n'],
  ]
  for (const [label, extra] of deny) {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, `FROM scratch\n${extra}`)
    const result = check(dir)
    assert.equal(result.status, 1, `期望 FAIL(1)（${label}），实际 ${result.status}\n${result.stdout}`)
    assert.match(result.stdout, /不可见/, `实际输出（${label}）：\n${result.stdout}`)
  }
  // 系统二进制（/bin/sh）不算脚本类入口，不应误报
  const dir = makeRepo()
  const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
  writeFileSync(dockerfile, 'FROM scratch\nENTRYPOINT [\"/bin/sh\", \"-c\", \"node s.mjs\"]\n')
  assert.equal(check(dir).status, 0, '系统二进制不应被当作脚本类入口')
  // COPY 进来的无扩展名脚本要扫内容
  const bad = makeRepo()
  writeFileSync(path.join(bad, 'server', 'web-runner', 'Dockerfile'), 'FROM scratch\nCOPY entry /app/entry\nENTRYPOINT [\"/app/entry\"]\n')
  writeFileSync(path.join(bad, 'server', 'web-runner', 'entry'), '#!/bin/sh\n. /app/env\n')
  assert.equal(check(bad).status, 1, '无扩展名入口脚本的内容必须被扫')
  const good = makeRepo()
  writeFileSync(path.join(good, 'server', 'web-runner', 'Dockerfile'), 'FROM scratch\nCOPY entry /app/entry\nENTRYPOINT [\"/app/entry\"]\n')
  writeFileSync(path.join(good, 'server', 'web-runner', 'entry'), '#!/bin/sh\necho ok\n')
  assert.equal(check(good).status, 0, '良性无扩展名入口脚本不应被拒')
})

test('续行拼接与入口目标：buildkit 语义（键名跨行/缩进）+ 目录源深层 + 花括号变量', () => {
  // ① 不带缩进的键名拆开 ⇒ buildkit 粘成完整键名 ⇒ 必须 FAIL
  {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, 'FROM scratch\nENV APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOS\\\nTS=*.internal\n')
    const result = check(dir)
    assert.equal(result.status, 1, `期望 FAIL(1)（键名跨行），实际 ${result.status}\n${result.stdout}`)
    assert.match(result.stdout, /禁写键/, `实际输出：\n${result.stdout}`)
  }
  // ② 带缩进的续行是合法写法：buildkit 原样粘连（不去行首空白），键名被拆开 ⇒ 不构成越权，应 PASS
  {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, 'FROM scratch\nENV APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOS\\\n    TS=*.internal\n')
    const result = check(dir)
    assert.equal(result.status, 0, `期望 PASS(0)（缩进续行不粘键名），实际 ${result.status}\n${result.stdout}`)
  }
  // ③ 目录源按目录树展开到深层子目录
  {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, 'FROM scratch\nCOPY scripts /app\nENTRYPOINT [\"/app/deep/entry.sh\"]\n')
    mkdirSync(path.join(dir, 'server', 'web-runner', 'scripts', 'deep'), { recursive: true })
    writeFileSync(path.join(dir, 'server', 'web-runner', 'scripts', 'deep', 'entry.sh'), '#!/bin/sh\n. /app/env\n')
    const result = check(dir)
    assert.equal(result.status, 1, `期望 FAIL(1)（目录源深层），实际 ${result.status}\n${result.stdout}`)
    assert.match(result.stdout, /入口脚本/, `实际输出：\n${result.stdout}`)
  }
  // ④ 花括号变量形态的间接入口
  {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, 'FROM scratch\nCOPY entry.sh /app/entry.sh\nCMD [\"sh\", \"-c\", \"exec ${APP_ENTRY}\"]\n')
    writeFileSync(path.join(dir, 'server', 'web-runner', 'entry.sh'), '#!/bin/sh\necho ok\n')
    const result = check(dir)
    assert.equal(result.status, 1, `期望 FAIL(1)（花括号变量），实际 ${result.status}\n${result.stdout}`)
    assert.match(result.stdout, /变量间接/, `实际输出：\n${result.stdout}`)
  }
})

test('拒绝：第二十五轮 3 类 fail-open（扩展名后缀入口/--parents/--from+裸名兜底）', () => {
  const cases = [
    ['RUN 写出 .txt 入口 + COPY 的 env 文件', 'COPY envfile /app/env\nRUN printf \"#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\\n\" > /app/entry.txt && chmod +x /app/entry.txt\nENTRYPOINT [\"/app/entry.txt\"]\n', { envfile: 'x' }, /不可见/],
    ['RUN 写出 .conf 入口', 'RUN printf \"#!/bin/sh\\n. /app/env\\n\" > /app/entry.conf\nENTRYPOINT [\"/app/entry.conf\"]\n', {}, /不可见/],
    ['COPY --parents 目录源（保留源路径结构）', 'COPY --parents scripts/ /app/\nENTRYPOINT [\"/app/scripts/entry.sh\"]\n', { 'scripts/entry.sh': 'set -a\n. /app/env\n' }, /入口脚本/],
    ['--from + 裸相对名 + 本地同名良性文件（不得兜底）', 'COPY --from=builder e.sh /app/entry.sh\nWORKDIR /app\nCMD [\"sh\", \"entry.sh\"]\n', { 'entry.sh': '#!/bin/sh\necho ok\n' }, /不可见/],
    ['远程 ADD + 裸相对名 + 本地同名良性文件', 'ADD https://x.test/e.sh /app/entry.sh\nWORKDIR /app\nCMD [\"sh\", \"entry.sh\"]\n', { 'entry.sh': '#!/bin/sh\necho ok\n' }, /不可见/],
    ['字符类 glob（conf/[ab].sh）', 'COPY conf/[ab].sh /app/\nENTRYPOINT [\"/app/a.sh\"]\n', { 'conf/a.sh': 'set -a\n. /app/env\n' }, /入口脚本/],
    ['-DKEY= 形态（词边界带 -D 前缀）', 'RUN echo x -DSPRING_CONFIG_ADDITIONAL_LOCATION=file:/tmp/x/\n', {}, /禁写键/],
    ['JVM/Spring 命令行属性覆盖（-Dspring.*）', 'ENTRYPOINT [\"java\", \"-Dspring.datasource.url=jdbc:h2:mem:t\", \"-jar\", \"/app/app.jar\"]\n', {}, /JVM\/Spring 命令行参数/],
    ['--spring.* 形态', 'RUN java --spring.config.location=file:/tmp/x/ -version\n', {}, /JVM\/Spring 命令行参数/],
  ]
  for (const [label, extra, files, pattern] of cases) {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, 1, `期望 FAIL(1)（${label}），实际 ${result.status}\n${result.stdout}`)
    const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
    assert.match(failLines, pattern, `实际失败行（${label}）：\n${failLines}`)
  }
})

test('通过：基础镜像自带入口与「路径只是实参一部分」不再误拦', () => {
  const benign = [
    'CMD [\"/docker-entrypoint.sh\"]\n',
    'ENTRYPOINT [\"/usr/local/bin/docker-entrypoint.sh\"]\n',
    'CMD [\"/usr/local/bin/entry\"]\n',
    'ENTRYPOINT [\"/usr/bin/env\", \"node\", \"x.mjs\"]\n',
    'ENTRYPOINT [\"/bin/bash\"]\n',
    'ENTRYPOINT [\"java\", \"--spring.config.additional-location=file:/app/config/\", \"-jar\", \"/app/app.jar\"]\n',
    'CMD [\"/usr/sbin/nginx\", \"-g\", \"daemon off;\"]\n',
    'CMD [\"sh\", \"-c\", \"ln -s /var/lib/x /var/lib/y && exec node s.mjs\"]\n',
    'RUN npm run build\nCMD [\"node\", \"dist/server.js\"]\n',
    'COPY --from=build /workspace/target/app.jar /app/app.jar\nENTRYPOINT [\"java\", \"-jar\", \"/app/app.jar\"]\n',
  ]
  for (const extra of benign) {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, `FROM scratch\n${extra}`)
    const result = check(dir)
    assert.equal(result.status, 0, `期望 PASS(0)，实际 ${result.status}\n${result.stdout}\n用例：${extra}`)
  }
})

test('多阶段：构建阶段的 CMD/入口不判，最终阶段的判', () => {
  // 构建阶段有 shell 入口 + 最终阶段没有 ⇒ 不拦
  {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, 'FROM node:22 AS build\nWORKDIR /w\nCMD [\"sh\", \"-c\", \"exec /w/entry\"]\nFROM nginx:1.27\nCOPY --from=build /w/dist /usr/share/nginx/html\n')
    const result = check(dir)
    assert.equal(result.status, 0, `期望 PASS(0)（构建阶段 CMD 不判），实际 ${result.status}\n${result.stdout}`)
  }
  // 最终阶段有同类入口 ⇒ 拦
  {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, 'FROM node:22 AS build\nRUN echo build\nFROM alpine:3.20\nCMD [\"sh\", \"-c\", \"exec /app/entry\"]\n')
    const result = check(dir)
    assert.equal(result.status, 1, `期望 FAIL(1)（最终阶段入口不可见），实际 ${result.status}\n${result.stdout}`)
    assert.match(result.stdout, /不可见/, `实际输出：\n${result.stdout}`)
  }
})

test('拒绝：第二十六轮 7 类引用形态漏判（env 家族/内层引号/PATH 裸名/.. 穿越/跨阶段/--parents 短尾）', () => {
  const cases = [
    ['env sh -c exec（RUN 写出坏入口）', 'RUN printf \'#!/bin/sh\nset -a\n. /app/env\n\' > /app/entry\nCMD [\"env\", \"sh\", \"-c\", \"exec /app/entry\"]\n', {}, /不可见/],
    ['/usr/bin/env sh -c exec', 'RUN printf \'#!/bin/sh\\n. /app/env\\n\' > /app/entry\nCMD [\"/usr/bin/env\", \"sh\", \"-c\", \"exec /app/entry\"]\n', {}, /不可见/],
    ['JSON 内层双引号包路径', 'RUN printf \'#!/bin/sh\\n. /app/env\\n\' > /app/entry\nCMD [\"sh\", \"-c\", \"exec \\"/app/entry\\"\"]\n', {}, /不可见/],
    ['PATH/裸名首词入口', 'COPY entry.sh /app/entry.sh\nENV PATH=/app:$PATH\nENTRYPOINT [\"entry.sh\"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\\n'}, /入口脚本|不可见/],
    ['.. 穿越目标', 'COPY . /app\nENTRYPOINT [\"/app/../secret\"]\n', {'secret': '#!/bin/sh\necho ok\n'}, /不可见/],
    ['跨阶段 COPY 供最终阶段入口', 'FROM alpine AS build\nCOPY entry /app/entry\nFROM alpine\nENTRYPOINT [\"/app/entry\"]\n', {'entry': '#!/bin/sh\necho ok\n'}, /不可见/],
    ['--parents 短尾目标', 'COPY --parents entry.sh /app/\nENTRYPOINT [\"/app/x\"]\n', {'entry.sh': '#!/bin/sh\necho ok\n'}, /不可见/],
    ['env 直接执行裸名脚本（无 shell 词）', 'COPY entry.sh /app/entry.sh\nENV PATH=/app:$PATH\nENTRYPOINT [\"/usr/bin/env\", \"entry.sh\"]\n', { 'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n' }, /入口脚本|不可见/],
    ['env 绝对路径执行 COPY 进来的坏脚本', 'COPY entry.sh /app/entry.sh\nENTRYPOINT [\"/usr/bin/env\", \"/app/entry.sh\"]\n', { 'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n' }, /入口脚本/],
  ]
  for (const [label, extra, files, pattern] of cases) {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, 1, `期望 FAIL(1)（${label}），实际 ${result.status}\n${result.stdout}`)
    const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
    assert.match(failLines, pattern, `实际失败行（${label}）：\n${failLines}`)
  }
})

test('通过：第二十六轮收窄的 6 类过拦（COPY . ./--parents 相对 dest/链式 WORKDIR/cd/非入口 $VAR/--parents 文件源）', () => {
  const cases = [
    ['COPY . . + sh -c exec ./entry', 'WORKDIR /app\nCOPY . .\nENTRYPOINT [\"sh\", \"-c\", \"exec ./entry\"]\n', {'entry': '#!/bin/sh\necho ok\n'}],
    ['--parents + 相对 dest', 'WORKDIR /srv\nCOPY --parents scripts/ ./\nCMD [\"sh\", \"-c\", \"exec /srv/scripts/entry.sh\"]\n', {'scripts/entry.sh': '#!/bin/sh\necho ok\n'}],
    ['链式 WORKDIR + 裸名', 'WORKDIR /app\nWORKDIR sub\nCOPY e.sh /app/sub/e.sh\nCMD [\"sh\", \"e.sh\"]\n', {'e.sh': '#!/bin/sh\necho ok\n'}],
    ['sh -c cd /app && exec node', 'ENTRYPOINT [\"sh\", \"-c\", \"cd /app && exec node server.mjs\"]\n', {}],
    ['非入口位置的 $VAR（echo）', 'CMD [\"sh\", \"-c\", \"echo $APP_PORT && exec node server.mjs\"]\n', {}],
    ['解释器调用里的路径是数据（不收集）', 'CMD [\"sh\", \"-c\", \"exec node /app/dist/server.js\"]\n', {}],
    ['--parents 文件源（良性）', 'COPY --parents conf/app.conf /app/\nCMD [\"sh\", \"-c\", \"exec /app/conf/app.conf\"]\n', {'conf/app.conf': '#!/bin/sh\necho ok\n'}],
    ['目标经 .. 归一后落在基础镜像位置 ⇒ 不判', 'COPY . /app\nENTRYPOINT [\"/app/../usr/bin/foo\"]\n', {}],
  ]
  for (const [label, extra, files] of cases) {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, 0, `期望 PASS(0)（${label}），实际 ${result.status}\n${result.stdout}`)
  }
})

test('拒绝：第二十七轮漏判与构建写入面（JSON 数组 COPY/续行伪装 FROM/续行目标/../ 路径/粘连分隔符/包装器/关键字/裸名 PATH/glob 取反）', () => {
  const cases = [
    ['JSON 数组 COPY + /opt 目标', 'COPY ["entry.sh", "/opt/app/run"]\nENTRYPOINT ["/opt/app/run"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n'}, /入口脚本/],
    ['JSON 数组 ADD + 出站开关', 'ADD ["e.sh", "/usr/local/bin/entry"]\nCMD ["/usr/local/bin/entry"]\n', {'e.sh': 'export APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS=true\n'}, /入口脚本/],
    ['续行伪装 FROM（末尾）', 'COPY entry.sh /opt/app/run\nRUN echo x \\\nFROM alpine:3.20\nENTRYPOINT ["/opt/app/run"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n'}, /入口脚本/],
    ['续行伪装 FROM（中部）', 'COPY entry.sh /opt/app/run\nRUN echo a \\\nFROM alpine\nRUN echo b\nENTRYPOINT ["/opt/app/run"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n'}, /入口脚本/],
    ['ENTRYPOINT 目标写在续行上', 'CMD ["sh", \\\n  "-c", "exec /app/entry.sh"]\nRUN printf x > /app/entry.sh\n', {}, /不可见/],
    ['../ 相对路径入口（shell 形式）', 'WORKDIR /app/sub\nCOPY entry /app/entry\nCMD ["sh", "-c", "exec ../entry"]\n', {'entry': '#!/bin/sh\nset -a\n. /app/env\n'}, /入口脚本/],
    ['../ 相对路径入口（数组形式）', 'WORKDIR /app/sub\nCOPY entry /app/entry\nENTRYPOINT ["../entry"]\n', {'entry': '#!/bin/sh\nset -a\n. /app/env\n'}, /入口脚本/],
    ['粘连分隔符后的变量（;）', 'ENV ENTRY=/app/entry.sh\nCOPY entry.sh /app/entry.sh\nCMD ["sh", "-c", "id; $ENTRY"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n'}, /变量间接|入口脚本/],
    ['粘连分隔符后的变量（&&）', 'ENV ENTRY=/app/entry.sh\nCOPY entry.sh /app/entry.sh\nCMD ["sh", "-c", "id&& $ENTRY"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n'}, /变量间接|入口脚本/],
    ['dumb-init 包装后的入口', 'COPY entry.sh /usr/local/bin/entrypoint.sh\nENTRYPOINT ["/usr/bin/dumb-init", "--", "/usr/local/bin/entrypoint.sh"]\n', {'entry.sh': 'export APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS=true\n'}, /入口脚本/],
    ['tini 包装后的入口', 'COPY entry.sh /app/entry.sh\nENTRYPOINT ["/usr/bin/tini", "--", "/app/entry.sh"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n'}, /入口脚本/],
    ['if/then 关键字后的入口', 'COPY entry.sh /app/entry.sh\nCMD ["sh", "-c", "if true; then /app/entry.sh; fi"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n'}, /入口脚本/],
    ['for 循环里 . 加载', 'COPY a.sh /app/a.sh\nCMD ["sh", "-c", "for f in a.sh; do . /app/a.sh; done"]\n', {'a.sh': '#!/bin/sh\nset -a\n. /app/env\n'}, /入口脚本/],
    ['赋值前缀后的入口', 'COPY entry.sh /app/entry.sh\nCMD ["sh", "-c", "FOO=1 /app/entry.sh"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n'}, /入口脚本/],
    ['无扩展名裸名 + PATH（上下文有该文件）', 'WORKDIR /app\nCOPY entry /app/entry\nENV PATH=/app:$PATH\nCMD ["sh", "-c", "exec entry"]\n', {'entry': '#!/bin/sh\nset -a\n. /app/env\n'}, /入口脚本/],
    ['glob 取反 [!a]', 'COPY conf/[!a].sh /app/\nENTRYPOINT ["/app/b.sh"]\n', {'conf/b.sh': '#!/bin/sh\nset -a\n. /app/env\n'}, /入口脚本/],
    ['WORKDIR 让基础镜像位置成为构建写入面', 'WORKDIR /opt/app\nENTRYPOINT ["/opt/app/run"]\n', {}, /不可见/],
    ['COPY dest 让基础镜像位置成为构建写入面', 'COPY seed.txt /opt/app/seed.txt\nENTRYPOINT ["/opt/app/run"]\n', {'seed.txt': 'x\n'}, /不可见/],
    ['RUN 提到过的基础镜像位置', 'RUN printf x > /usr/local/bin/entry\nCMD ["/usr/local/bin/entry"]\n', {}, /不可见/],
    ['--from 与本地同名良性文件共存（不得取本地那份）', 'COPY --from=builder e.sh /app/entry.sh\nENTRYPOINT ["/app/entry.sh"]\n', {'e.sh': '#!/bin/sh\necho ok\n'}, /不可见/],
    ['幻影 heredoc：引号内的 <<TAG', 'COPY entry.sh /opt/app/run\nRUN echo "see <<NOTES for details"\nENTRYPOINT ["/opt/app/run"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'}, /入口脚本/],
    ['幻影 heredoc：注释里的 <<TAG', 'COPY entry.sh /opt/app/run\n# do not use <<HEREDOC here\nENTRYPOINT ["/opt/app/run"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'}, /入口脚本/],
    ['幻影 heredoc：LABEL 里的 <<TAG', 'COPY entry.sh /opt/app/run\nLABEL note="a <<B b"\nENTRYPOINT ["/opt/app/run"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'}, /入口脚本/],
    ['COPY-heredoc 空格缩进假结束符 + 体内 FROM', 'COPY entry.sh /opt/app/run\nCOPY <<TERM /opt/notes.txt\n TERM\nFROM scratch\nTERM\nENTRYPOINT ["/opt/app/run"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'}, /入口脚本/],
    ['su -c CMD USER（选项在前）', 'COPY entry.sh /app/entry.sh\nENTRYPOINT ["su", "-c", "/app/entry.sh", "app"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'}, /入口脚本/],
    ['setpriv 选项后 CMD', 'COPY entry.sh /app/entry.sh\nCMD ["setpriv", "--reuid=1000", "/app/entry.sh"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'}, /入口脚本/],
    ['SHELL 行承载加载器', 'SHELL ["/bin/sh", "-c", "set -a; . /app/env; exec /bin/sh -c \\"$0\\""]\nCMD node server.mjs\n', {}, /set -a|加载/],
    ['HEALTHCHECK 行承载加载器', 'HEALTHCHECK CMD sh -c "set -a; . /app/env; exec true"\nCMD ["node", "server.mjs"]\n', {}, /set -a|加载/],
    ['timeout 30 包装后的入口', 'COPY entry.sh /app/entry.sh\nCMD [\"sh\", \"-c\", \"timeout 30 /app/entry.sh\"]\n', { 'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n' }, /入口脚本/],
    ['gosu 包装后的入口', 'COPY entry.sh /app/entry.sh\nENTRYPOINT [\"/usr/sbin/gosu\", \"app\", \"/app/entry.sh\"]\n', { 'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n' }, /入口脚本/],
    ['/sbin/tini 路径形态', 'COPY entry.sh /app/entry.sh\nENTRYPOINT [\"/sbin/tini\", \"--\", \"/app/entry.sh\"]\n', { 'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n' }, /入口脚本/],    ['斜杠相对路径入口（sub/entry.sh）', 'COPY sub/entry.sh /app/sub/entry.sh\nENTRYPOINT ["sub/entry.sh"]\n', {'sub/entry.sh': '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'}, /入口脚本|不可见/],
    ['sh -c 里的斜杠相对路径', 'COPY bin/entry /bin/entry\nCMD ["sh", "-c", "bin/entry"]\n', {'bin/entry': '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'}, /入口脚本/],
    ['相对前缀包装器 usr/bin/dumb-init', 'COPY entry.sh /app/entry.sh\nENTRYPOINT ["usr/bin/dumb-init", "/app/entry.sh"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'}, /入口脚本/],
    ['COPY-heredoc 定界符含 -（EOF-2）', 'COPY entry.sh /app/entry.sh\nCOPY <<EOF-2 /app/notes.txt\nhello\nEOF-2\nENTRYPOINT ["/app/entry.sh"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'}, /入口脚本/],
    ['COPY-heredoc 定界符含 .（notes.txt）', 'COPY entry.sh /app/entry.sh\nCOPY <<notes.txt /app/notes.txt\nhello\nnotes.txt\nENTRYPOINT ["/app/entry.sh"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'}, /入口脚本/],
    ['应用模块里写禁写键（应当拦）', 'COPY server.mjs /app/server.mjs\nENTRYPOINT ["/app/server.mjs"]\n', {'server.mjs': 'process.env.APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS = "true"\n'}, /出站开关/],
    ['<<- 后带空白不是 heredoc（入口仍要扫）', 'COPY entry.sh /app/entry.sh\nCOPY <<- /app/f\nENTRYPOINT ["/app/entry.sh"]\n', {'entry.sh': 'export APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS=true\n'}, /入口脚本/],
    ['chroot . 后的真命令', 'COPY entry.sh /app/entry.sh\nENTRYPOINT ["chroot", ".", "/app/entry.sh"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'}, /入口脚本/],
    ['命令位 $0 间接入口', 'COPY entry.sh /app/entry.sh\nENTRYPOINT ["sh", "-c", "exec \\"$0\\"", "/app/entry.sh"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'}, /变量间接/],
    ['命令位 $@ 间接入口', 'COPY entry.sh /app/entry.sh\nCMD ["sh", "-c", "exec $@"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'}, /变量间接/],
    ['路径含 = 的入口（/srv/run=x）', 'COPY entry.sh /srv/run=x\nENTRYPOINT ["/srv/run=x"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'}, /入口脚本/],
    ['应用模块下标写白名单键', 'COPY app.py /app/app.py\nENTRYPOINT ["/app/app.py"]\n', {'app.py': 'import os\nos.environ[\'APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOSTS\']=\'1.2.3.4\'\n'}, /禁写键/],
    ['应用模块下标写配置来源键', 'COPY app.py /app/app.py\nENTRYPOINT ["/app/app.py"]\n', {'app.py': 'import os\nos.environ[\'SPRING_CONFIG_IMPORT\']=\'file:/etc/x\'\n'}, /禁写键/],
    ['未终止 heredoc（构建会失败）', 'COPY <<BAD /app/n.txt\nbody\nENTRYPOINT ["/app/entry.sh"]\n', {}, /unterminated|heredoc/],
    ['heredoc 直接提供入口脚本（内容要审）', 'COPY <<E /app/entry.sh\nset -a\n. /app/env\nE\nCMD ["/app/entry.sh"]\n', {}, /入口脚本/],
    ['相对形态 shell 家族（bin/sh）', 'COPY entry.sh /app/entry.sh\nCMD ["bin/sh", "-c", "exec /app/entry.sh"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'}, /入口脚本/],
    ['相对形态 usr/bin/env 直接执行', 'COPY entry.sh /app/entry.sh\nENTRYPOINT ["usr/bin/env", "/app/entry.sh"]\n', {'entry.sh': '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'}, /入口脚本/],
  ]
  for (const [label, extra, files, pattern] of cases) {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, 1, `期望 FAIL(1)（${label}），实际 ${result.status}\n${result.stdout}`)
    const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
    assert.match(failLines, pattern, `实际失败行（${label}）：\n${failLines}`)
  }
})

test('通过：第二十七轮不该误拦面（裸名基础镜像命令/dest 归一/cd 实参/包装器+良性脚本）', () => {
  const cases = [
    ['nginx 裸名（基础镜像命令，不应误拦）', 'CMD ["nginx", "-g", "daemon off;"]\n', {}],
    ['php-fpm 裸名', 'CMD ["php-fpm"]\n', {}],
    ['gunicorn + 模块参数', 'CMD ["gunicorn", "app.wsgi"]\n', {}],
    ['dest 归一化（/app/sub/../entry.sh）', 'COPY entry.sh /app/sub/../entry.sh\nENTRYPOINT ["/app/entry.sh"]\n', {'entry.sh': '#!/bin/sh\necho ok\n'}],
    ['cd 到非基础镜像目录后 exec（cd 实参不算入口）', 'CMD ["sh", "-c", "cd /app/data && exec node x.mjs"]\n', {}],
    ['tini 包装 + 良性脚本', 'COPY entry.sh /app/entry.sh\nENTRYPOINT ["/usr/bin/tini", "--", "/app/entry.sh"]\n', {'entry.sh': '#!/bin/sh\necho ok\n'}],
    ['busybox sh -c exec node（不应误拦）', 'CMD [\"busybox\", \"sh\", \"-c\", \"exec node server.mjs\"]\n', {}],
    ['gosu app /app/entry.sh（良性）', 'COPY entry.sh /app/entry.sh\nENTRYPOINT ["/usr/sbin/gosu", "app", "/app/entry.sh"]\n', {'entry.sh': '#!/bin/sh\necho ok\n'}],
    ['su app -c /app/entry.sh（良性）', 'COPY entry.sh /app/entry.sh\nENTRYPOINT ["su", "app", "-c", "/app/entry.sh"]\n', {'entry.sh': '#!/bin/sh\necho ok\n'}],
    ['tini node（解释器数据不扫）', 'COPY server.mjs /app/server.mjs\nENTRYPOINT ["/usr/bin/tini", "node", "/app/server.mjs"]\n', {'server.mjs': '// NOTE: dotenv deliberately not used\nconst a = 1\nif (a < 2) console.log("ok")\nconst c = 1 << 3\nconst ENV = "production"\n'}],
    ['su-exec app node（解释器数据不扫）', 'COPY server.mjs /app/server.mjs\nCMD ["su-exec", "app", "node", "/app/server.mjs"]\n', {'server.mjs': '// NOTE: dotenv deliberately not used\nconst a = 1\nif (a < 2) console.log("ok")\nconst c = 1 << 3\nconst ENV = "production"\n'}],
    ['tini 包装 + node 构建期产出（解释器数据不扫）', 'COPY package.json /app/package.json\nRUN npm ci && npm run build\nENTRYPOINT [\"/usr/bin/tini\", \"node\", \"/app/dist/server.mjs\"]\n', {}],
    ['chroot / CMD（目录实参）', 'COPY entry.sh /app/entry.sh\nENTRYPOINT ["chroot", "/", "/app/entry.sh"]\n', {'entry.sh': '#!/bin/sh\necho ok\n'}],
    ['chroot /srv/root CMD', 'COPY entry.sh /app/entry.sh\nENTRYPOINT ["chroot", "/srv/root", "/app/entry.sh"]\n', {'entry.sh': '#!/bin/sh\necho ok\n'}],
    ['COPY-heredoc 体里写 HTML', 'COPY <<HTML /app/index.html\n<html>\n<div>hi</div>\n</html>\nHTML\nCMD ["node", "server.mjs"]\n', {}],
    ['COPY-heredoc 体里写含 . /etc/profile 的脚本', 'COPY <<SHELLTXT /app/profile.sh\n. /etc/profile\nexec node server.mjs\nSHELLTXT\nCMD ["node", "server.mjs"]\n', {}],
    ['应用模块里的 if (a < b) / 1 << 3 / const ENV=', 'COPY server.mjs /app/server.mjs\nENTRYPOINT ["/app/server.mjs"]\n', {'server.mjs': '// NOTE: dotenv deliberately not used\nconst a = 1\nif (a < 2) console.log("ok")\nconst c = 1 << 3\nconst ENV = "production"\n'}],
    ['应用模块 .py 里的 ENV 赋值', 'COPY app.py /app/app.py\nENTRYPOINT ["/app/app.py"]\n', {'app.py': 'ENV = "production"\nprint(ENV)\n'}],
    ['gosu 用户名与同名目录', 'COPY data/seed.txt /app/seed.txt\nENTRYPOINT ["gosu", "appuser", "data"]\n', {'data/seed.txt': 'x\n'}],
    ['多定界符 heredoc 的两个体', 'COPY <<A <<B /app/f\nok\nA\n. /etc/profile\nB\nCMD ["node", "server.mjs"]\n', {}],
    ['heredoc 提供良性入口脚本', 'COPY <<E /app/entry.sh\n#!/bin/sh\necho ok\nE\nCMD ["/app/entry.sh"]\n', {}],
  ]
  for (const [label, extra, files] of cases) {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, 0, `期望 PASS(0)（${label}），实际 ${result.status}\n${result.stdout}`)
  }
})

test('通过：加固类键 read_only 与 tmpfs 被允许', () => {
  const dir = makeRepo()
  // 基线已带 read_only/tmpfs；这里再追加一个 tmpfs 挂点，验证加固类键不会被当成“未声明键”拒绝。
  const file = writeVariant(dir, 'hardening', baselineCompose()
    .replace('      - /dev/shm', '      - /dev/shm\n      - /run'))
  const result = check(dir, file)
  assert.equal(result.status, 0, `期望 PASS(0)，实际 ${result.status}\n${result.stdout}`)
})

test('通过：基线栈定义', () => {
  const dir = makeRepo()
  const result = check(dir)
  assert.equal(result.status, 0, `期望 PASS(0)，实际 ${result.status}\n${result.stdout}`)
})

test('通过：仓库内构建上下文与本地 .git 目录名', () => {
  const dir = makeRepo()
  mkdirSync(path.join(dir, 'deploy', 'isolated', 'contexts', 'my.git'), { recursive: true })
  // 新上下文里要有构建文件——构建文件内容检查（第十八轮）对缺失的 Dockerfile 是 fail-closed。
  writeFileSync(path.join(dir, 'deploy', 'isolated', 'contexts', 'my.git', 'Dockerfile'), 'FROM scratch\n')
  const file = writeVariant(dir, 'ok', baselineCompose().replace('context: ../../apps/web', 'context: ./contexts/my.git'))
  const result = check(dir, file)
  assert.equal(result.status, 0, `期望 PASS(0)，实际 ${result.status}\n${result.stdout}`)
})

test('通过：合法隔离端口不被误报', () => {
  const dir = makeRepo()
  const file = writeVariant(dir, 'ok-ports', baselineCompose()
    .replace('http://web-runner:9323', 'http://127.0.0.1:19324')
    .replace(ANCHOR_RUNNER, `${ANCHOR_RUNNER}      ISOLATED_DB_NOTE: mysql:3306\n      ISOLATED_DB_URL: jdbc:mysql://127.0.0.1:13306/haitlify_isolated\n`))
  const result = check(dir, file)
  assert.equal(result.status, 0, `期望 PASS(0)，实际 ${result.status}\n${result.stdout}`)
})

test('scan：UTF-16 的 8 种组合（LE/BE × BOM/无 BOM × 奇/偶）都命中旧标识', () => {
  const dir = makeRepo()
  const encDir = path.join(dir, 'deploy', 'enc')
  mkdirSync(encDir, { recursive: true })
  const text = `${LEGACY_PROJECT} ${LEGACY_DB}\n`
  const matrix = []
  for (const encoding of ['utf16le', 'utf16be']) {
    for (const bom of ['bom', 'nobom']) {
      for (const parity of ['even', 'odd']) {
        const name = `${encoding}-${bom}-${parity}.yaml`
        const body = Buffer.from(text, 'utf16le')
        if (encoding === 'utf16be') body.swap16()
        const prefix = bom === 'bom' ? Buffer.from(encoding === 'utf16le' ? [0xff, 0xfe] : [0xfe, 0xff]) : Buffer.alloc(0)
        const suffix = parity === 'odd' ? Buffer.from([0x58]) : Buffer.alloc(0)
        writeFileSync(path.join(encDir, name), Buffer.concat([prefix, body, suffix]))
        matrix.push(name)
      }
    }
  }
  const result = runPreflight(dir, ['scan', '--json'])
  assert.equal(result.status, 1, `期望 scan FAIL(1)，实际 ${result.status}`)
  const report = JSON.parse(result.stdout)
  const hitFiles = new Set(report.findings.map((finding) => path.basename(finding.file)))
  for (const name of matrix) {
    assert.ok(hitFiles.has(name), `scan 未命中 ${name}（findings: ${[...hitFiles].join(', ')}）`)
  }
})

// ==== 判定原语的直接单元断言（第十七轮复核：端到端用例无法区分这些层被拆掉） ====
import {
  canonicalHostForm as hostForm,
  collectLegacyPortTokens as portTokens,
  looseLegacyEndpointMatch as looseMatch,
  splitComposeLines as splitLines,
  decodingsForScan as decodings,
} from './environment-preflight.mjs'

test('原语：splitComposeLines 按 CR/CRLF/LF/NEL/LS/PS/VT/FF 切分', () => {
  assert.deepEqual(splitLines('a\rb'), ['a', 'b'])
  assert.deepEqual(splitLines('a\r\nb'), ['a', 'b'])
  assert.deepEqual(splitLines('a\u0085b'), ['a', 'b'])
  assert.deepEqual(splitLines('a\u2028b'), ['a', 'b'])
  assert.deepEqual(splitLines('a\u2029b'), ['a', 'b'])
  assert.deepEqual(splitLines('a\u000bb'), ['a', 'b'])
  assert.deepEqual(splitLines('a\u000cb'), ['a', 'b'])
})

test('原语：splitComposeLines 不切分引号标量内部（含 U+2028 与 \n 转义）', () => {
  assert.equal(splitLines('k: "a\u2028b"').length, 1)
  assert.equal(splitLines("k: 'a\u2028b'").length, 1)
  assert.equal(splitLines('k: "a\nb"').length, 1)
})

test('原语：decodingsForScan 覆盖 BOM 与非 BOM 的 UTF-16', () => {
  const le = Buffer.concat([Buffer.from([0xff, 0xfe]), Buffer.from('ab', 'utf16le')])
  assert.deepEqual(decodings(le).map((d) => d.encoding), ['utf16le'])
  assert.equal(decodings(le)[0].text, 'ab')
  const beBody = Buffer.from('ab', 'utf16le')
  beBody.swap16()
  const be = Buffer.concat([Buffer.from([0xfe, 0xff]), beBody])
  assert.deepEqual(decodings(be).map((d) => d.encoding), ['utf16be'])
  assert.equal(decodings(be)[0].text, 'ab')
  const noBom = Buffer.from('a\0b\0', 'latin1')
  assert.deepEqual(decodings(noBom).map((d) => d.encoding), ['latin1', 'latin1+utf16le', 'latin1+utf16be'])
  assert.deepEqual(decodings(Buffer.from('plain', 'utf8')).map((d) => d.encoding), ['utf8'])
})

test('原语：canonicalHostForm 按 inet_aton 语义归一', () => {
  assert.equal(hostForm('0300.0250.01.05'), '192.168.1.5')
  assert.equal(hostForm('0x00c0.0xa8.1.5'), '192.168.1.5')
  assert.equal(hostForm('0x00c0a80105'), '192.168.1.5')
  assert.equal(hostForm('3232235781'), '192.168.1.5')
  assert.equal(hostForm('0.0.300'), '0.0.1.44')
  assert.equal(hostForm('0.0.0400'), '0.0.1.0')
  assert.equal(hostForm('127.1'), '127.0.0.1')
  assert.equal(hostForm('3306.0'), null)
})

test('原语：collectLegacyPortTokens 按客户端读取语义归一端口', () => {
  assert.deepEqual(portTokens('--port=0003306'), ['3306'])
  assert.deepEqual(portTokens('--port=0x000CEA'), ['3306'])
  assert.deepEqual(portTokens('--port=3306.0'), ['3306'])
  assert.deepEqual(portTokens('timeout=3306m'), [])
  assert.deepEqual(portTokens('v19999'), [])
})

test('原语：looseLegacyEndpointMatch 的共现规则与 loopback 豁免', () => {
  assert.ok(looseMatch('nc -z -w 3 3232235781 3306'))
  assert.ok(looseMatch('curl "http://10.0.0.7:9323/health?max=8080"'))
  assert.equal(looseMatch('nc -z 127.0.0.1 19323'), null)
  assert.equal(looseMatch('exec 3<>/dev/tcp/0x7f000001/8080'), null)
})

// ── 第三十一轮复核：多阶段继承 / 续行 heredoc / 包装器 / 前缀词 / 应用模块口径 ──
test('第三十一轮：多阶段 FROM 继承、续行 heredoc、包装器与前缀词、应用模块口径', () => {
  const cases = [
    // FO-1：`FROM <stage>` 会继承前阶段的文件系统，只取「最后一个 FROM 之后」会漏掉继承来的入口脚本。
    ['多阶段 FROM <stage> 继承的入口脚本',
      'FROM scratch AS build\nCOPY entry.sh /usr/local/bin/docker-entrypoint.sh\nFROM build\nCMD ["/usr/local/bin/docker-entrypoint.sh"]\n',
      { 'entry.sh': '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n' }, 1, /入口脚本/],
    ['对照：最终阶段是独立基础镜像（基础镜像自带入口不误判）',
      'FROM scratch AS build\nCOPY ok.sh /opt/ok.sh\nFROM alpine:3.20\nCMD ["/docker-entrypoint.sh"]\n',
      { 'ok.sh': '#!/bin/sh\necho ok\n' }, 0, null],
    // FO-2：定界符只出现在续行上
    ['续行拆开的 COPY heredoc + ENV=',
      'COPY \\\n<<E /app/entry.sh\n#!/bin/sh\nENV=/app/env\nE\nCMD ["/app/entry.sh"]\n',
      {}, 1, /ENV=|启动文件变量/],
    ['对照：不拆开的同一内容仍 FAIL',
      'COPY <<E /app/entry.sh\n#!/bin/sh\nENV=/app/env\nE\nCMD ["/app/entry.sh"]\n',
      {}, 1, /ENV=|启动文件变量/],
    // FO-3：白名单外的 init/降权包装器
    ['start-stop-daemon --exec 后的入口',
      'COPY entry.sh /app/entry.sh\nCMD ["start-stop-daemon","--start","--exec","/app/entry.sh"]\n',
      { 'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n' }, 1, /入口脚本/],
    ['s6-setuidgid 后的入口',
      'COPY entry.sh /app/entry.sh\nCMD ["s6-setuidgid","app","/app/entry.sh"]\n',
      { 'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n' }, 1, /入口脚本/],
    // FO-4：包装器模式下变量间接必须用 effective 命令位判定
    ['su -c $ENTRY（用户名已消费命令位）',
      'ENV ENTRY=/app/entry.sh\nCOPY entry.sh /app/entry.sh\nCMD ["sh","-c","exec su app -c $ENTRY"]\n',
      { 'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n' }, 1, /变量间接/],
    ['gosu app $ENTRY',
      'ENV ENTRY=/app/entry.sh\nCOPY entry.sh /app/entry.sh\nCMD ["sh","-c","exec gosu app $ENTRY"]\n',
      { 'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n' }, 1, /变量间接/],
    ['timeout 30s $ENTRY',
      'ENV ENTRY=/app/entry.sh\nCOPY entry.sh /app/entry.sh\nCMD ["sh","-c","exec timeout 30s $ENTRY"]\n',
      { 'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n' }, 1, /变量间接/],
    // FO-5：执行前缀词
    ['command 前缀后的入口',
      'COPY entry.sh /app/entry.sh\nCMD ["sh","-c","command /app/entry.sh"]\n',
      { 'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n' }, 1, /入口脚本/],
    ['time 前缀后的入口',
      'COPY entry.sh /app/entry.sh\nCMD ["sh","-c","time /app/entry.sh"]\n',
      { 'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n' }, 1, /入口脚本/],
    ['! 前缀后的入口',
      'COPY entry.sh /app/entry.sh\nCMD ["sh","-c","! /app/entry.sh"]\n',
      { 'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n' }, 1, /入口脚本/],
    ['exec -a NAME 后的入口',
      'COPY entry.sh /app/entry.sh\nCMD ["sh","-c","exec -a svc /app/entry.sh"]\n',
      { 'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n' }, 1, /入口脚本/],
    ['xargs 后的入口',
      'COPY entry.sh /app/entry.sh\nCMD ["sh","-c","xargs /app/entry.sh"]\n',
      { 'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n' }, 1, /入口脚本/],
    // OB-1：heredoc 提供的脚本按扩展名分类
    ['heredoc 提供的 .py 不套 shell 规则',
      'COPY <<E /app/app.py\n#!/usr/bin/env python3\nif (a < b):\n    print(1)\nE\nENTRYPOINT ["/app/app.py"]\n',
      {}, 0, null],
    ['对照：heredoc 提供的 .sh 仍要被审',
      'COPY <<E /app/entry.sh\nset -a\n. /app/env\nE\nCMD ["/app/entry.sh"]\n',
      {}, 1, /入口脚本/],
    // OB-2：包装器找到真命令后退出包装器模式
    ['gosu 后的多余实参不误收',
      'COPY entry.sh /app/entry.sh\nCOPY extra.sh /app/extra.sh\nCMD ["gosu","app","/app/entry.sh","/app/extra.sh"]\n',
      { 'entry.sh': '#!/bin/sh\necho ok\n', 'extra.sh': '#!/bin/sh\nset -a\n. /app/env\n' }, 0, null],
    // OB-3：`<<-'E'` 的引号不是标签的一部分
    ['<<-\'E\' 不判未终止',
      'COPY <<-\'E\' /app/notes.txt\n\thello\nE\nCMD ["node","server.mjs"]\n',
      {}, 0, null],
    // OB-4：应用模块的注释里提及键名不算
    ['应用模块注释里提及键名',
      'COPY server.mjs /app/server.mjs\nENTRYPOINT ["/app/server.mjs"]\n',
      { 'server.mjs': '// note: SPRING_CONFIG_IMPORT: not used here\nconsole.log(1)\n' }, 0, null],
    ['对照：应用模块真的赋值仍拦',
      'COPY server.mjs /app/server.mjs\nENTRYPOINT ["/app/server.mjs"]\n',
      { 'server.mjs': 'process.env.SPRING_CONFIG_IMPORT = "file:/etc/x"\n' }, 1, /禁写键/],
    // OB-5：dest 归一成 `/` 时的前缀比较
    ['COPY x ./（无 WORKDIR）',
      'COPY entry.sh ./\nCMD ["/entry.sh"]\n',
      { 'entry.sh': '#!/bin/sh\necho ok\n' }, 0, null],
    ['对照：COPY x ./ 的脚本有 set -a',
      'COPY entry.sh ./\nCMD ["/entry.sh"]\n',
      { 'entry.sh': '#!/bin/sh\nset -a\n. /app/env\n' }, 1, /入口脚本/],
  ]
  for (const [label, extra, files, expected, pattern] of cases) {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, expected, `期望 exit=${expected}（${label}），实际 ${result.status}\n${result.stdout}`)
    if (pattern) {
      // 只匹配 [FAIL] 行（第三十二轮 D4）：PASS/WARN 详情含同样子串不算命中。
      const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
      assert.match(failLines, pattern, `期望 FAIL 行命中 ${pattern}（${label}）\n${result.stdout}`)
    }
  }
})

// ── 第三十二轮复核：包装器选项语义、ARG 阶段、dest 归一、阶段覆盖、注释口径 ──
test('第三十二轮：包装器选项值/位置实参、换行分隔、ARG 阶段、阶段覆盖与注释口径', () => {
  const badScript = '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'
  const goodScript = '#!/bin/sh\necho ok\n'
  const cases = [
    // FO-A：`--exec=PATH` 的内联值就是被执行的命令
    ['--exec=PATH 内联值',
      'COPY entry.sh /app/entry.sh\nCMD ["start-stop-daemon","--start","--exec=/app/entry.sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    // FO-B：位置实参包装器的第一个路径是目录/锁文件
    ['envdir DIR CMD',
      'COPY entry.sh /app/entry.sh\nCMD ["envdir","/usr/local/etc","/app/entry.sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    ['chpst -e DIR CMD',
      'COPY entry.sh /app/entry.sh\nCMD ["chpst","-e","/run/conf","/app/entry.sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    ['start-stop-daemon --chdir DIR --exec CMD',
      'COPY entry.sh /app/entry.sh\nCMD ["start-stop-daemon","--start","--chdir","/usr/local/app","--exec","/app/entry.sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    // FO-C：带值选项吃掉的是它的值，不是命令
    ['env -u HOME CMD',
      'COPY entry.sh /app/entry.sh\nCMD ["env","-u","HOME","/app/entry.sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    ['sh -o errexit CMD',
      'COPY entry.sh /app/entry.sh\nCMD ["sh","-o","errexit","/app/entry.sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    // FO-D：换行是命令分隔符
    ['sh -c 里的换行后新命令',
      'COPY entry.sh /app/entry.sh\nCMD ["sh","-c","id\\n/app/entry.sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    // FO-E：FROM ${ARG} 按 ARG 默认值解析，继承仍成立
    ['FROM ${BASE} 继承入口',
      'FROM scratch AS base\nCOPY entry.sh /usr/local/bin/docker-entrypoint.sh\nARG BASE=base\nFROM ${BASE}\nCMD ["/usr/local/bin/docker-entrypoint.sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    // FO-F：包装器名单（setsid/flock/chrt）
    ['setsid CMD',
      'COPY entry.sh /app/entry.sh\nCMD ["setsid","/app/entry.sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    ['flock FILE CMD',
      'COPY entry.sh /app/entry.sh\nCMD ["flock","/tmp/lock","/app/entry.sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    ['chrt -r 1 CMD',
      'COPY entry.sh /app/entry.sh\nCMD ["chrt","-r","1","/app/entry.sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    // OB-a：--parents 的源与目标尾先归一
    ['COPY --parents ./entry.sh /',
      'COPY --parents ./entry.sh /\nCMD ["/entry.sh"]\n',
      { 'entry.sh': goodScript }, 0, null],
    // OB-b：续行 + heredoc 只登记一次（不能伪报未终止）
    ['续行 heredoc 不重复登记',
      'COPY <<E \\\n/app/notes.txt\nhello\nE\nCMD ["node","server.mjs"]\n',
      {}, 0, null],
    // FO-2 正例：heredoc 体是数据（体里的 RUN/--env-file 不参与判定）
    ['续行 heredoc 体当数据',
      'COPY \\\n<<E /app/notes.md\nRUN --env-file /x\n. /app/env\nE\nCMD ["node","server.mjs"]\n',
      {}, 0, null],
    // OB-c：后阶段覆盖同一 dest ⇒ 只审最终那份
    ['后阶段覆盖前阶段的 COPY',
      'FROM scratch AS base\nCOPY bad.sh /opt/e.sh\nFROM base\nCOPY good.sh /opt/e.sh\nENTRYPOINT ["/opt/e.sh"]\n',
      { 'bad.sh': badScript, 'good.sh': goodScript }, 0, null],
    // OB-d：后阶段覆写 ENTRYPOINT ⇒ 被覆写的不审
    ['后阶段覆写 ENTRYPOINT',
      'FROM scratch AS base\nCOPY old.sh /opt/old.sh\nENTRYPOINT ["/opt/old.sh"]\nFROM base\nCOPY good.sh /opt/good.sh\nENTRYPOINT ["/opt/good.sh"]\n',
      { 'old.sh': badScript, 'good.sh': goodScript }, 0, null],
    // OB-e：注释里的 KEY= 只是提及
    ['Dockerfile 注释里的 KEY=',
      'COPY entry.sh /app/entry.sh\n# SPRING_CONFIG_IMPORT=file:/etc/x\nCMD ["/app/entry.sh"]\n',
      { 'entry.sh': goodScript }, 0, null],
    ['应用模块注释/docstring 里的 KEY:',
      'COPY server.mjs /app/server.mjs\nENTRYPOINT ["/app/server.mjs"]\n',
      { 'server.mjs': '// note: SPRING_CONFIG_IMPORT: not used\nconst x = 1\n' }, 0, null],
  ]
  for (const [label, extra, files, expected, pattern] of cases) {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, expected, `期望 exit=${expected}（${label}），实际 ${result.status}\n${result.stdout}`)
    if (pattern) {
      // 只匹配 [FAIL] 行：PASS/WARN 详情里出现同样子串不能算命中（第三十二轮 D4）。
      const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
      assert.match(failLines, pattern, `期望 FAIL 行命中 ${pattern}（${label}）\n${result.stdout}`)
    }
  }
})

// ── 第三十三轮复核：按工具的选项语义、内联 --exec 裸名、ENTRYPOINT 继承、build.args、引号内注释、同阶段覆盖、多行续行 ──
test('第三十三轮：选项按工具生效、ENTRYPOINT 继承、build.args、引号内 # 与同阶段覆盖', () => {
  const badScript = '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'
  const goodScript = '#!/bin/sh\necho ok\n'
  const cases = [
    // F1：shell 的单字母开关不带值，不能吃掉命令
    ['bash -e CMD',
      'COPY entry.sh /app/entry.sh\nCMD ["bash","-e","/app/entry.sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    ['sh -u CMD',
      'COPY entry.sh /app/entry.sh\nCMD ["sh","-u","/app/entry.sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    ['flock -n LOCK CMD',
      'COPY entry.sh /app/entry.sh\nCMD ["flock","-n","/tmp/lock","/app/entry.sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    ['flock LOCK -n CMD',
      'COPY entry.sh /app/entry.sh\nCMD ["flock","/tmp/lock","-n","/app/entry.sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    // F2：`--exec=` 的裸名按「上下文里真有同名文件」判定
    ['--exec=entry.sh（WORKDIR 下的裸名）',
      'WORKDIR /app\nCOPY entry.sh /app/entry.sh\nCMD ["start-stop-daemon","--start","--exec=entry.sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    ['对照：--exec=nginx（上下文无此文件）',
      'COPY entry.sh /app/entry.sh\nCMD ["start-stop-daemon","--start","--exec=nginx"]\n',
      { 'entry.sh': goodScript }, 0, null],
    // F3：CMD 不重置继承的 ENTRYPOINT
    ['子阶段只写 CMD，父 ENTRYPOINT 仍生效',
      'FROM scratch AS base\nCOPY e.sh /opt/e.sh\nENTRYPOINT ["/opt/e.sh"]\nFROM base\nCMD ["x"]\n',
      { 'e.sh': badScript }, 1, /入口脚本/],
    ['对照：子阶段覆写 ENTRYPOINT（旧的不审）',
      'FROM scratch AS base\nCOPY old.sh /opt/old.sh\nENTRYPOINT ["/opt/old.sh"]\nFROM base\nCOPY good.sh /opt/good.sh\nENTRYPOINT ["/opt/good.sh"]\n',
      { 'old.sh': badScript, 'good.sh': goodScript }, 0, null],
    // F5：引号里的 `#` 不是注释
    ['引号内 # 之后的真实赋值仍要拦',
      'COPY entry.sh /app/entry.sh\nRUN echo "a # b" >> /tmp/x; echo SPRING_CONFIG_IMPORT=file:/etc/evil >> /etc/environment\nCMD ["/app/entry.sh"]\n',
      { 'entry.sh': goodScript }, 1, /禁写键|配置来源/],
    // O1：同阶段后一次 COPY 覆盖前一次
    ['同阶段同 dest 的两次 COPY（后者胜）',
      'COPY bad.sh /opt/e.sh\nCOPY good.sh /opt/e.sh\nENTRYPOINT ["/opt/e.sh"]\n',
      { 'bad.sh': badScript, 'good.sh': goodScript }, 0, null],
    // O2：三行续行的 heredoc 不重复登记
    ['三行续行的 heredoc',
      'COPY <<E \\\n/app/notes\\\n.txt\nhello\nE\nCMD ["node","server.mjs"]\n',
      {}, 0, null],
  ]
  for (const [label, extra, files, expected, pattern] of cases) {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, expected, `期望 exit=${expected}（${label}），实际 ${result.status}\n${result.stdout}`)
    if (pattern) {
      const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
      assert.match(failLines, pattern, `期望 FAIL 行命中 ${pattern}（${label}）\n${result.stdout}`)
    }
  }

  // F4：compose build.args 覆盖 Dockerfile 里的 ARG 默认值 ⇒ FROM 的继承链必须跟着变
  {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'),
      'FROM scratch AS base\nCOPY e.sh /usr/local/bin/docker-entrypoint.sh\nARG BASE_IMAGE=scratch\nFROM ${BASE_IMAGE}\nCMD ["/usr/local/bin/docker-entrypoint.sh"]\n')
    writeFileSync(path.join(dir, 'server', 'web-runner', 'e.sh'), badScript)
    const compose = readFileSync(path.join(dir, 'deploy', 'isolated', 'compose.yaml'), 'utf8')
    const patched = compose.replace(/(\n  web-runner:[\s\S]*?\n    build:\n)/, '$1      args:\n        BASE_IMAGE: base\n')
    assert.notEqual(patched, compose, 'compose 里没找到 web-runner.build 段，用例需要更新')
    writeFileSync(path.join(dir, 'deploy', 'isolated', 'args-compose.yaml'), patched)
    const result = check(dir, 'deploy/isolated/args-compose.yaml')
    assert.equal(result.status, 1, `期望 build.args 解析后继承生效（exit=1），实际 ${result.status}\n${result.stdout}`)
    const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
    assert.match(failLines, /入口脚本/)
  }
})

// ── 第三十四轮复核：多条 ENTRYPOINT/CMD、+o 与开关选项、目录 dest 覆盖、ENTRYPOINT 重置继承 CMD、必然失败的构建 ──
test('第三十四轮：多条入口指令只取最后一条、开关选项不吞命令、目录 dest 合并、空 FROM 与 space 形式', () => {
  const badScript = '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'
  const goodScript = '#!/bin/sh\necho ok\n'
  const cases = [
    // N1：同一阶段多条 ENTRYPOINT/CMD，只有最后一条生效
    ['两条 ENTRYPOINT（最后一条是坏的）',
      'COPY good.sh /app/good.sh\nCOPY entry.sh /app/entry.sh\nENTRYPOINT ["/app/good.sh"]\nENTRYPOINT ["/app/entry.sh"]\n',
      { 'good.sh': goodScript, 'entry.sh': badScript }, 1, /入口脚本/],
    ['两条 CMD（最后一条是好的）',
      'COPY good.sh /app/good.sh\nCOPY entry.sh /app/entry.sh\nCMD ["/app/entry.sh"]\nCMD ["/app/good.sh"]\n',
      { 'good.sh': goodScript, 'entry.sh': badScript }, 0, null],
    // N2：`+o` 与「开关」不是带值选项
    ['bash +o posix CMD',
      'COPY entry.sh /app/entry.sh\nCMD ["bash","+o","posix","/app/entry.sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    ['nsenter -t 1 -m CMD',
      'COPY entry.sh /app/entry.sh\nCMD ["nsenter","-t","1","-m","/app/entry.sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    ['dumb-init --single-child CMD',
      'COPY entry.sh /app/entry.sh\nCMD ["dumb-init","--single-child","/app/entry.sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    ['su --command=PATH',
      'COPY entry.sh /app/entry.sh\nCMD ["su","--command=/app/entry.sh","app"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    // N3：目录 dest 下不同文件名并存，不能整 dest 覆盖
    ['同目录 dest 的两份不同文件',
      'COPY ok.sh /app/\nCOPY other.sh /app/\nENTRYPOINT ["/app/ok.sh"]\n',
      { 'ok.sh': goodScript, 'other.sh': goodScript }, 0, null],
    // N4：设置 ENTRYPOINT 会清空继承的 CMD
    ['子阶段 ENTRYPOINT 清空父阶段 CMD',
      'FROM scratch AS base\nCOPY evil.sh /opt/evil.sh\nCMD ["/opt/evil.sh"]\nFROM base\nCOPY good.sh /opt/good.sh\nENTRYPOINT ["/opt/good.sh"]\n',
      { 'evil.sh': badScript, 'good.sh': goodScript }, 0, null],
    // N5：必然失败的构建不能 PASS
    ['space 形式含多个无 = 的词（buildkit 会拒绝）',
      'COPY entry.sh /app/entry.sh\nENV APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS=false keep\nCMD ["/app/entry.sh"]\n',
      { 'entry.sh': goodScript }, 1, /space 形式|buildkit/],
  ]
  for (const [label, extra, files, expected, pattern] of cases) {
    const dir = makeRepo()
    const dockerfile = path.join(dir, 'server', 'web-runner', 'Dockerfile')
    writeFileSync(dockerfile, `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, expected, `期望 exit=${expected}（${label}），实际 ${result.status}\n${result.stdout}`)
    if (pattern) {
      const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
      assert.match(failLines, pattern, `期望 FAIL 行命中 ${pattern}（${label}）\n${result.stdout}`)
    }
  }

  // N5：compose build.args 传空串 ⇒ FROM 解析为空 ⇒ 构建必然失败
  {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'),
      'ARG BASE_IMAGE=scratch\nFROM ${BASE_IMAGE}\nCMD ["node","server.mjs"]\n')
    const compose = readFileSync(path.join(dir, 'deploy', 'isolated', 'compose.yaml'), 'utf8')
    const patched = compose.replace(/(\n  web-runner:[\s\S]*?\n    build:\n)/, '$1      args:\n        BASE_IMAGE: ""\n')
    assert.notEqual(patched, compose, 'compose 里没找到 web-runner.build 段')
    writeFileSync(path.join(dir, 'deploy', 'isolated', 'empty-args-compose.yaml'), patched)
    const result = check(dir, 'deploy/isolated/empty-args-compose.yaml')
    assert.equal(result.status, 1, `期望空 FROM 被拒（exit=1），实际 ${result.status}\n${result.stdout}`)
    const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
    assert.match(failLines, /FROM 基镜像为空/)
  }
})

// ── 第三十五轮复核：继承 CMD、非文本后缀、shell 名 basename、ADD tar /、长选项缩写、chpst 开关、ENV 旧格式 ──
test('第三十五轮：继承的 CMD 必须送审、入口后缀与 basename 不改判定、ADD tar / 与选项缩写', () => {
  const badScript = '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'
  const goodScript = '#!/bin/sh\necho ok\n'
  const cases = [
    // FO-1：没有自己的 CMD 时，最近祖先的 CMD 就是运行时命令
    ['父阶段 CMD 被继承（子阶段只有 RUN）',
      'FROM scratch AS base\nCOPY entry.sh /app/entry.sh\nCMD ["/app/entry.sh"]\nFROM base\nRUN echo runtime\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    ['ENTRYPOINT + CMD 同阶段、子阶段为空',
      'FROM scratch AS base\nCOPY entry.sh /app/entry.sh\nENTRYPOINT ["/bin/sh","-c"]\nCMD ["/app/entry.sh"]\nFROM base\nRUN echo runtime\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    // FO-2：改成二进制后缀仍会被 execve
    ['入口文件名带 .bin 后缀',
      'COPY entry.sh /app/entry.bin\nENTRYPOINT ["/app/entry.bin"]\n',
      { 'entry.sh': badScript }, 1, /非文本文件|入口脚本/],
    // FO-3：basename 是 shell 名但不在基础镜像目录
    ['入口 /app/sh（basename 是 shell 名）',
      'COPY entry.sh /app/sh\nENTRYPOINT ["/app/sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    ['入口 /app/env（basename 是 env）',
      'COPY entry.sh /app/env\nENTRYPOINT ["/app/env"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    // FO-5：GNU 长选项缩写
    ['su --comm=PATH（缩写）',
      'COPY entry.sh /app/entry.sh\nCMD ["su","--comm=/app/entry.sh","app"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    ['start-stop-daemon --exe=PATH（缩写）',
      'COPY entry.sh /app/entry.sh\nCMD ["start-stop-daemon","--start","--exe=/app/entry.sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    // FO-6：chpst 的 -1 是「关 stdout」开关，不是带值选项
    ['chpst -1 CMD',
      'COPY entry.sh /app/entry.sh\nCMD ["chpst","-1","/app/entry.sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本/],
    // OB-1：ENV/ARG 旧格式「整段剩余都是值」是合法写法
    ['ENV 旧格式带空格的值',
      'COPY ok.sh /app/ok.sh\nENV SOME_SETTING some value\nENTRYPOINT ["/app/ok.sh"]\n',
      { 'ok.sh': goodScript }, 0, null],
    ['ARG 旧格式带空格的值',
      'ARG A some value\nCOPY ok.sh /app/ok.sh\nENTRYPOINT ["/app/ok.sh"]\n',
      { 'ok.sh': goodScript }, 0, null],
    // OB-2：子阶段继承父阶段 WORKDIR
    ['子阶段继承父阶段 WORKDIR（入口是相对路径，只有继承才解析得到来源）',
      'FROM scratch AS base\nWORKDIR /srv\nCOPY ok.sh /srv/ok.sh\nFROM base\nCOPY other.sh ./\nENTRYPOINT ["./ok.sh"]\n',
      { 'ok.sh': goodScript, 'other.sh': goodScript }, 0, null],
  ]
  for (const [label, extra, files, expected, pattern] of cases) {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'), `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, expected, `期望 exit=${expected}（${label}），实际 ${result.status}\n${result.stdout}`)
    if (pattern) {
      const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
      assert.match(failLines, pattern, `期望 FAIL 行命中 ${pattern}（${label}）\n${result.stdout}`)
    }
  }

  // FO-4：`ADD rootfs.tar /` 写入整根，基础镜像目录里的入口不再算「自带」
  {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'),
      'FROM scratch\nADD rootfs.tar /\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n')
    const tarHeader = Buffer.alloc(1024)
    tarHeader.write('ustar', 257, 'latin1')
    writeFileSync(path.join(dir, 'server', 'web-runner', 'rootfs.tar'), tarHeader)
    const result = check(dir)
    assert.equal(result.status, 1, `期望 ADD tar / 让 /usr/local/bin 计入写入面（exit=1），实际 ${result.status}\n${result.stdout}`)
  }
})

// ── 第三十六轮复核：shell 名要看写入面、ADD 按内容嗅探、内联命令选项、chpst -/、fail-closed 承重 ──
test('第三十六轮：shell 名与写入面、ADD 内容嗅探、内联命令选项、fail-closed 承重', () => {
  const badScript = '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'
  const goodScript = '#!/bin/sh\necho ok\n'
  const cases = [
    // F-1：基础镜像目录里被 COPY 写过的位置不再当「解释器」
    ['/opt/dash（构建写过 /opt）', 'COPY entry.sh /opt/dash\nENTRYPOINT ["/opt/dash"]\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    ['/usr/local/bin/sh（构建写过该目录）', 'COPY entry.sh /usr/local/bin/sh\nENTRYPOINT ["/usr/local/bin/sh"]\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    ['./sh（相对路径不是解释器）', 'COPY entry.sh ./sh\nENTRYPOINT ["./sh"]\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    // F-3：内联的长选项也可能携带命令
    ['su --session-command=PATH', 'COPY entry.sh /app/entry.sh\nCMD ["su","--session-command=/app/entry.sh","app"]\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    ['env --split-string=PATH', 'COPY entry.sh /app/entry.sh\nCMD ["env","--split-string=/app/entry.sh"]\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    // F-4：chpst 的 `-/` 带目录值，不是入口
    ['chpst -/ /jail CMD', 'COPY ok.sh /app/ok.sh\nCMD ["chpst","-/","/jail","/app/ok.sh"]\n', { 'ok.sh': goodScript }, 0, null],
    // T-2：良性脚本改成 .bin 也要 fail-closed（内容无法静态审计）
    ['良性脚本改名 .bin 仍 fail-closed', 'COPY ok.sh /app/entry.bin\nENTRYPOINT ["/app/entry.bin"]\n', { 'ok.sh': goodScript }, 1, /非文本文件/],
  ]
  for (const [label, extra, files, expected, pattern] of cases) {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'), `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, expected, `期望 exit=${expected}（${label}），实际 ${result.status}\n${result.stdout}`)
    if (pattern) {
      const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
      assert.match(failLines, pattern, `期望 FAIL 行命中 ${pattern}（${label}）\n${result.stdout}`)
    }
  }

  // F-2：ADD 的归档判定按内容（无后缀真 tar / zstd）
  {
    const plain = makeRepo()
    writeFileSync(path.join(plain, 'server', 'web-runner', 'Dockerfile'),
      'FROM scratch\nADD rootfs /\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n')
    const tarHeader = Buffer.alloc(1024)
    tarHeader.write('ustar', 257, 'latin1')
    writeFileSync(path.join(plain, 'server', 'web-runner', 'rootfs'), tarHeader)
    const tarResult = check(plain)
    assert.equal(tarResult.status, 1, `期望无后缀 tar 被识别（exit=1），实际 ${tarResult.status}\n${tarResult.stdout}`)

    const zstd = makeRepo()
    writeFileSync(path.join(zstd, 'server', 'web-runner', 'Dockerfile'),
      'FROM scratch\nADD rootfs.tar.zst /\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n')
    const zstdHeader = Buffer.from([0x28, 0xb5, 0x2f, 0xfd, 0x00, 0x00, 0x00, 0x00])
    writeFileSync(path.join(zstd, 'server', 'web-runner', 'rootfs.tar.zst'), zstdHeader)
    const zstdResult = check(zstd)
    assert.equal(zstdResult.status, 1, `期望 zstd 归档被识别（exit=1），实际 ${zstdResult.status}\n${zstdResult.stdout}`)
  }
})

// ── 第三十七轮复核：RUN 写入面、相对路径 shell、glob 归档、V7 tar、引号 dest、短选项粘连、ADD/COPY 解包边界 ──
test('第三十七轮：写入面与 RUN、相对 shell 名、globs、V7 tar、引号与粘连选项、ADD/COPY 边界', () => {
  const badScript = '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'
  const goodScript = '#!/bin/sh\necho ok\n'
  const cases = [
    // N-1：RUN 写出的路径同样属于写入面
    ['RUN 写 /opt/dash 后直接执行', 'RUN cp /app/entry.sh /opt/dash && chmod 755 /opt/dash\nENTRYPOINT ["/opt/dash"]\n', { 'entry.sh': badScript }, 1, /入口脚本|不可见/],
    // N-1b：ADD 归档到 / ⇒ 整根写入面，基础镜像目录里的 shell 名也不算自带
    ['ADD 归档 / 后执行 /usr/local/bin/sh', 'ADD rootfs.tar /\nENTRYPOINT ["/usr/local/bin/sh"]\n', {}, 1, /入口脚本|不可见/],
    // N-2：相对路径按 workdir 解析后落在写入面，就不再是解释器
    ['WORKDIR + COPY bin/sh 后执行 bin/sh', 'WORKDIR /app\nCOPY entry.sh bin/sh\nENTRYPOINT ["bin/sh"]\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    // N-6：短选项粘连的命令值
    ['su -cPATH 粘连', 'COPY entry.sh /app/entry.sh\nCMD ["su","-c/app/entry.sh","app"]\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    ['env -SPATH 粘连', 'COPY entry.sh /app/entry.sh\nCMD ["env","-S/app/entry.sh"]\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    // N-5：shell 形式的引号要剥离
    ['COPY 引号 dest', 'COPY "entry.sh" "/opt/dash"\nENTRYPOINT ["/opt/dash"]\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    // 过拦：ADD zip 不解包、COPY tar 不解包
    ['ADD zip /（不解包）', 'ADD app.zip /\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n', { 'app.zip': 'PK-not-really\n' }, 0, null],
    ['COPY tar /（不解包）', 'COPY rootfs.tar /\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n', { 'rootfs.tar': 'not-a-tar\n' }, 0, null],
  ]
  for (const [label, extra, files, expected, pattern] of cases) {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'), `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, expected, `期望 exit=${expected}（${label}），实际 ${result.status}\n${result.stdout}`)
    if (pattern) {
      const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
      assert.match(failLines, pattern, `期望 FAIL 行命中 ${pattern}（${label}）\n${result.stdout}`)
    }
  }

  // N-3：glob 源命中的归档
  {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'),
      'FROM scratch\nADD rootfs* /\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n')
    const tarHeader = Buffer.alloc(1024)
    tarHeader.write('ustar', 257, 'latin1')
    writeFileSync(path.join(dir, 'server', 'web-runner', 'rootfs.tar'), tarHeader)
    const result = check(dir)
    assert.equal(result.status, 1, `期望 glob 归档被识别（exit=1），实际 ${result.status}\n${result.stdout}`)
  }

  // N-4：V7 tar（无 ustar magic，仅头校验和）
  {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'),
      'FROM scratch\nADD rootfs /\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n')
    const v7 = Buffer.alloc(512)
    v7.write('rootfs/\0', 0, 'latin1')
    v7.write('0000644\0', 100, 'latin1')
    v7.write('0000000\0', 108, 'latin1')
    v7.write('0000000\0', 116, 'latin1')
    v7.write('00000000000\0', 124, 'latin1')
    v7.write('00000000000\0', 136, 'latin1')
    v7.write('        ', 148, 'latin1')
    v7.write('\0', 156, 'latin1')
    let sum = 0
    for (let index = 0; index < 512; index += 1) sum += v7[index]
    v7.write(`${sum.toString(8).padStart(6, '0')}\0 `, 148, 'latin1')
    writeFileSync(path.join(dir, 'server', 'web-runner', 'rootfs'), v7)
    const result = check(dir)
    assert.equal(result.status, 1, `期望 V7 tar 被识别（exit=1），实际 ${result.status}\n${result.stdout}`)
  }

  // N-5：shell 形式引号的 glob/归档组合
  {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'),
      'FROM scratch\nADD "rootfs.tar" "/"\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n')
    const tarHeader = Buffer.alloc(1024)
    tarHeader.write('ustar', 257, 'latin1')
    writeFileSync(path.join(dir, 'server', 'web-runner', 'rootfs.tar'), tarHeader)
    const result = check(dir)
    assert.equal(result.status, 1, `期望引号形式的归档仍被识别（exit=1），实际 ${result.status}\n${result.stdout}`)
  }

  // 覆盖补齐：gzip / xz / bzip2 头 + 目录源到 /
  {
    const variants = [
      ['gzip', Buffer.from([0x1f, 0x8b, 0x08, 0x00])],
      ['xz', Buffer.concat([Buffer.from([0xfd]), Buffer.from('7zXZ\0', 'latin1')])],
      ['bzip2', Buffer.from('BZh9', 'latin1')],
    ]
    for (const [label, header] of variants) {
      const dir = makeRepo()
      writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'),
        'FROM scratch\nADD rootfs /\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n')
      writeFileSync(path.join(dir, 'server', 'web-runner', 'rootfs'), header)
      const result = check(dir)
      assert.equal(result.status, 1, `期望 ${label} 归档被识别（exit=1），实际 ${result.status}\n${result.stdout}`)
    }

    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'),
      'FROM scratch\nADD rootfs /\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n')
    mkdirSync(path.join(dir, 'server', 'web-runner', 'rootfs', 'usr', 'local', 'bin'), { recursive: true })
    writeFileSync(path.join(dir, 'server', 'web-runner', 'rootfs', 'usr', 'local', 'bin', 'entry.sh'), badScript)
    const result = check(dir)
    assert.equal(result.status, 1, `期望目录源到 / 被识别（exit=1），实际 ${result.status}\n${result.stdout}`)
  }
})

// ── 第三十八轮复核：裸 shell 名、RUN 目录目标、带空格引号 dest、变量 dest、SHELL/HEALTHCHECK、聚合短选项 ──
test('第三十八轮：写入面与裸 shell 名、RUN 目录目标、引号/变量 dest、SHELL 与 HEALTHCHECK、聚合短选项', () => {
  const badScript = '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'
  const goodScript = '#!/bin/sh\necho ok\n'
  const cases = [
    // 裸 shell 名按 WORKDIR 解析后落在写入面
    ['WORKDIR + COPY sh 后执行裸名 sh', 'WORKDIR /usr/local/bin\nCOPY entry.sh sh\nENTRYPOINT ["sh"]\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    ['ADD 归档 / 后执行裸名 sh', 'ADD rootfs.tar /\nCMD ["sh","-c","exec /app/entry.sh"]\n', {}, 1, /入口脚本|不可见/],
    // RUN 只提到目录 / 相对路径
    ['RUN install -t 目录', 'RUN install -m 755 /app/entry.sh -t /usr/local/bin\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n', {}, 1, /不可见/],
    ['RUN 相对路径写 /opt/dash', 'RUN install -m 755 /app/entry.sh opt/dash\nENTRYPOINT ["/opt/dash"]\n', {}, 1, /不可见/],
    // 带空格的引号 dest / 变量 dest
    ['引号 dest 带空格', 'COPY entry.sh "/opt/my dash"\nENTRYPOINT ["/opt/my dash"]\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    ['反斜杠转义空格的 dest', 'COPY entry.sh /opt/my\\ dash\nENTRYPOINT ["/opt/my dash"]\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    ['变量 dest', 'ENV APPDIR=/opt\nCOPY entry.sh $APPDIR/dash\nENTRYPOINT ["/opt/dash"]\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    // SHELL / HEALTHCHECK 直接执行构建写出的脚本
    ['SHELL 指向 COPY 进来的脚本', 'COPY entry.sh /app/sh\nSHELL ["/app/sh","-c"]\nCMD echo hi\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    ['HEALTHCHECK 指向 COPY 进来的脚本', 'COPY entry.sh /app/entry.sh\nHEALTHCHECK CMD ["/app/entry.sh"]\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    // 聚合短选项
    ['env -iS 聚合短选项', 'COPY entry.sh /app/entry.sh\nCMD ["env","-iS/app/entry.sh"]\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    ['su -pc 聚合短选项', 'COPY entry.sh /app/entry.sh\nCMD ["su","-pc/app/entry.sh","app"]\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    // 过拦修复：WORKDIR 不是写入面
    ['WORKDIR /usr + 基础镜像 shell', 'WORKDIR /usr\nCMD ["bin/sh","-c","echo hi"]\n', {}, 0, null],
    // 过拦修复：远程 ADD 到 /
    ['远程 ADD 到 /（只写一个已知文件名）', 'ADD https://example.com/rootfs.zip /\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n', {}, 0, null],
  ]
  for (const [label, extra, files, expected, pattern] of cases) {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'), `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, expected, `期望 exit=${expected}（${label}），实际 ${result.status}\n${result.stdout}`)
    if (pattern) {
      const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
      assert.match(failLines, pattern, `期望 FAIL 行命中 ${pattern}（${label}）\n${result.stdout}`)
    }
  }

  // 多段 glob 的归档
  {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'),
      'FROM scratch\nADD rootfs/*/*.tar /\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n')
    mkdirSync(path.join(dir, 'server', 'web-runner', 'rootfs', 'x'), { recursive: true })
    const tarHeader = Buffer.alloc(1024)
    tarHeader.write('ustar', 257, 'latin1')
    writeFileSync(path.join(dir, 'server', 'web-runner', 'rootfs', 'x', 'a.tar'), tarHeader)
    const result = check(dir)
    assert.equal(result.status, 1, `期望多段 glob 归档被识别（exit=1），实际 ${result.status}\n${result.stdout}`)
  }

  // 有符号校验和的 V7 tar（UTF-8 文件名）
  {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'),
      'FROM scratch\nADD rootfs /\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n')
    const v7 = Buffer.alloc(512)
    v7.write('\xc3\xa9-dir/\0', 0, 'latin1')
    v7.write('0000644\0', 100, 'latin1')
    v7.write('0000000\0', 108, 'latin1')
    v7.write('0000000\0', 116, 'latin1')
    v7.write('00000000000\0', 124, 'latin1')
    v7.write('00000000000\0', 136, 'latin1')
    v7.write('        ', 148, 'latin1')
    v7.write('\0', 156, 'latin1')
    let signed = 0
    for (let index = 0; index < 512; index += 1) {
      const byte = v7[index]
      signed += byte > 127 ? byte - 256 : byte
    }
    v7.write(`${(signed >>> 0).toString(8).padStart(6, '0')}\0 `, 148, 'latin1')
    writeFileSync(path.join(dir, 'server', 'web-runner', 'rootfs'), v7)
    const result = check(dir)
    assert.equal(result.status, 1, `期望有符号校验和的 V7 tar 被识别（exit=1），实际 ${result.status}\n${result.stdout}`)
  }

  // 目录源到 / 必须真正打开根写入面（此前零承重）
  {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'),
      'FROM scratch\nADD rootfs /\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n')
    mkdirSync(path.join(dir, 'server', 'web-runner', 'rootfs', 'usr', 'local', 'bin'), { recursive: true })
    writeFileSync(path.join(dir, 'server', 'web-runner', 'rootfs', 'usr', 'local', 'bin', 'entry.sh'), badScript)
    const result = check(dir)
    assert.equal(result.status, 1, `期望目录源打开根写入面（exit=1），实际 ${result.status}\n${result.stdout}`)
  }
})

// ── 第三十八轮补：裸名 + 归档到 /、RUN cp 到 WORKDIR、归档只看内容 ──
test('第三十八轮补：裸名整根写入面、RUN cp 到 .、归档只看内容、--from 不本地解包', () => {
  const badScript = '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'
  const goodScript = '#!/bin/sh\necho ok\n'
  const ustar = () => {
    const header = Buffer.alloc(1024)
    header.write('ustar', 257, 'latin1')
    return header
  }
  const cases = [
    // 裸 sh + 归档到 /：整根写入面里的 sh 可能是包里的脚本
    ['ADD 真归档 / + 裸名 sh -c', 'ADD rootfs.tar /\nCMD ["sh","-c","exec /app/entry.sh"]\n',
      { 'rootfs.tar': ustar() }, 1, /入口脚本|不可见/],
    // RUN cp 到当前 WORKDIR
    ['RUN cp 到 . 后执行 ./entry.sh', 'WORKDIR /usr/local/bin\nRUN cp /app/entry.sh .\nENTRYPOINT ["./entry.sh"]\n',
      {}, 1, /不可见/],
    // 归档只看内容：纯文本叫 rootfs.tar 不会被解包
    ['纯文本 rootfs.tar 到 /', 'ADD rootfs.tar /\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n',
      { 'rootfs.tar': 'this is not a tar\n' }, 0, null],
    // --from 的 ADD 不用本地同名文件
    // `--from` 的内容不可审计 ⇒ 落到 `/` 时整根都可能是它写的（第四十一轮 FO-2 更正了这里的期望）
    ['ADD --from=build 到 /', 'ADD --from=build rootfs.tar /\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n',
      { 'rootfs.tar': ustar() }, 1, /不可见/],
    // 目录源到 / 仍然打开整根写入面（真目录、真脚本）
    ['目录源到 / + 基础镜像入口', 'ADD approot /\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n',
      { 'approot/usr/local/bin/entry.sh': badScript, 'approot/readme.txt': 'x\n' }, 1, /入口脚本|不可见/],
  ]
  for (const [label, extra, files, expected, pattern] of cases) {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'), `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, expected, `期望 exit=${expected}（${label}），实际 ${result.status}\n${result.stdout}`)
    if (pattern) {
      const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
      assert.match(failLines, pattern, `期望 FAIL 行命中 ${pattern}（${label}）\n${result.stdout}`)
    }
  }
  // 良性对照：规则不应把普通基础镜像命令当入口
  {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'),
      'FROM scratch\nCOPY ok.sh /app/ok.sh\nCMD ["node","server.mjs"]\n')
    writeFileSync(path.join(dir, 'server', 'web-runner', 'ok.sh'), goodScript)
    const result = check(dir)
    assert.equal(result.status, 0, `期望 node 应用命令 PASS，实际 ${result.status}\n${result.stdout}`)
  }
})

// ── 第三十九轮复核：PATH 解析、变量展开、SHELL/HEALTHCHECK 全形态、RUN 写裸名、未解析 dest、过拦 ──
test('第三十九轮：PATH 裸名、变量展开全形态、SHELL/HEALTHCHECK、RUN 写裸名、未解析 dest 与过拦', () => {
  const badScript = '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'
  const goodScript = '#!/bin/sh\necho ok\n'
  const cases = [
    // FO-A：裸名由 PATH 解析，不只是 WORKDIR
    ['ENV PATH + COPY sh + 裸名', 'ENV PATH=/app\nCOPY entry.sh /app/sh\nCMD ["sh"]\n', { 'entry.sh': badScript }, 1, /入口脚本|不可见/],
    ['精确 COPY 到 PATH 目录 + 裸名', 'COPY entry.sh /usr/local/bin/sh\nENTRYPOINT ["sh"]\n', { 'entry.sh': badScript }, 1, /入口脚本|不可见/],
    ['解释器名被 COPY 成脚本', 'COPY entry.sh /usr/local/bin/node\nCMD ["node","/app/server.mjs"]\n', { 'entry.sh': badScript }, 1, /入口脚本|不可见/],
    // FO-B：变量展开的四种写法
    ['ENV space 形式变量 dest', 'ENV APPDIR /usr/local/bin\nCOPY entry.sh $APPDIR/entry.sh\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    ['ENV 多赋值取第二个', 'ENV A=/opt B=/usr/local/bin\nCOPY entry.sh $B/entry.sh\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    ['${X:-default} 作 dest', 'COPY entry.sh ${APPDIR:-/usr/local/bin}/entry.sh\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    ['WORKDIR 变量', 'ENV D=/usr/local/bin\nWORKDIR $D\nCOPY entry.sh ./entry.sh\nENTRYPOINT ["./entry.sh"]\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    // FO-C：SHELL / HEALTHCHECK 的裸名与 shell 形式
    ['SHELL 裸名', 'COPY entry.sh /usr/local/bin/sh\nSHELL ["sh","-c"]\nCMD echo hi\n', { 'entry.sh': badScript }, 1, /入口脚本|不可见/],
    ['HEALTHCHECK shell 形式', 'COPY entry.sh /app/entry.sh\nHEALTHCHECK CMD /app/entry.sh\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    // FO-D：RUN 把裸名写到写入面
    ['RUN install 写裸名', 'WORKDIR /usr/local/bin\nRUN install -m 755 /tmp/evil sh\nCMD ["sh"]\n', {}, 1, /不可见/],
    ['RUN ln -s 写裸名', 'RUN ln -s /tmp/evil /usr/local/bin/sh\nCMD ["sh"]\n', {}, 1, /不可见/],
    // 未解析变量的 dest ⇒ 写入面不可知
    ['未解析变量 dest', 'ARG APPDIR\nCOPY entry.sh $APPDIR/entry.sh\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n', { 'entry.sh': badScript }, 1, /不可见/],
    // 过拦：chmod / 目录源不含该文件 / ustar magic 校验和无效
    ['RUN chmod 基础镜像工具', 'RUN chmod +x /usr/local/bin/tool\nCMD ["/usr/local/bin/tool"]\n', {}, 0, null],
    ['ADD 目录源到 / 但目录里没有该入口', 'ADD approot /\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n', { 'approot/readme.txt': 'x\n' }, 0, null],
  ]
  for (const [label, extra, files, expected, pattern] of cases) {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'), `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, expected, `期望 exit=${expected}（${label}），实际 ${result.status}\n${result.stdout}`)
    if (pattern) {
      const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
      assert.match(failLines, pattern, `期望 FAIL 行命中 ${pattern}（${label}）\n${result.stdout}`)
    }
  }

  // ustar magic 但校验和无效 ⇒ Docker 不会解包，不应打开整根写入面
  {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'),
      'FROM scratch\nADD rootfs /\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n')
    const fake = Buffer.alloc(1024)
    fake.write('ustar', 257, 'latin1')
    fake.write('0000000\0', 148, 'latin1') // 校验和明显不对
    writeFileSync(path.join(dir, 'server', 'web-runner', 'rootfs'), fake)
    const result = check(dir)
    assert.equal(result.status, 0, `期望校验和无效的 ustar 不算归档（exit=0），实际 ${result.status}\n${result.stdout}`)
  }
  // 目录源到 / 的「成员判定」承重：`sh` 真的在源目录里时不能再当解释器
  {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'),
      'FROM scratch\nADD approot /\nENTRYPOINT ["sh"]\n')
    mkdirSync(path.join(dir, 'server', 'web-runner', 'approot', 'usr', 'local', 'bin'), { recursive: true })
    writeFileSync(path.join(dir, 'server', 'web-runner', 'approot', 'usr', 'local', 'bin', 'sh'), badScript)
    const result = check(dir)
    assert.equal(result.status, 1, `期望目录源里的 sh 被审（exit=1），实际 ${result.status}\n${result.stdout}`)
  }
  // 对照：真实 ustar 校验和 ⇒ 仍要打开整根写入面
  {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'),
      'FROM scratch\nADD rootfs /\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n')
    const real = Buffer.alloc(1024)
    real.write('rootfs/\0', 0, 'latin1')
    real.write('0000644\0', 100, 'latin1')
    real.write('0000000\0', 108, 'latin1')
    real.write('0000000\0', 116, 'latin1')
    real.write('00000000000\0', 124, 'latin1')
    real.write('00000000000\0', 136, 'latin1')
    real.write('        ', 148, 'latin1')
    real.write('\0', 156, 'latin1')
    real.write('ustar', 257, 'latin1')
    let sum = 0
    for (let index = 0; index < 512; index += 1) sum += real[index]
    real.write(`${sum.toString(8).padStart(6, '0')}\0 `, 148, 'latin1')
    writeFileSync(path.join(dir, 'server', 'web-runner', 'rootfs'), real)
    const result = check(dir)
    assert.equal(result.status, 1, `期望真实 ustar 打开整根写入面（exit=1），实际 ${result.status}\n${result.stdout}`)
  }
})

// ── 第四十轮复核：.sh 裸名 PATH、PATH 的 $PATH/相对段/空格写法、--from 裸名、RUN 写目录、HEALTHCHECK 包装、写动词 ──
test('第四十轮：.sh 裸名按 PATH、PATH 展开、--from 裸名、RUN 写目录、HEALTHCHECK 包装与过拦', () => {
  const badScript = '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'
  const goodScript = '#!/bin/sh\necho ok\n'
  const cases = [
    // F1：.sh 裸名按 PATH 解析（诱饵在 /entry.sh 不算）
    ['PATH + .sh 裸名 + 良性诱饵', 'ENV PATH=/app\nCOPY good.sh /entry.sh\nCOPY entry.sh /app/entry.sh\nENTRYPOINT ["entry.sh"]\n',
      { 'good.sh': goodScript, 'entry.sh': badScript }, 1, /入口脚本/],
    ['PATH + .sh 裸名（良性）', 'ENV PATH=/app\nCOPY good.sh /app/entry.sh\nENTRYPOINT ["entry.sh"]\n',
      { 'good.sh': goodScript }, 0, null],
    // F3：PATH 的 $PATH 自引用 / 相对段 / 空格写法 / 多行
    ['PATH 含 $PATH 自引用', 'ENV PATH=/app/bin:$PATH\nCOPY entry.sh /usr/local/bin/foo\nENTRYPOINT ["foo"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本|不可见/],
    ['PATH 相对段按 WORKDIR', 'WORKDIR /app\nENV PATH=bin\nCOPY entry.sh bin/sh\nCMD ["sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本|不可见/],
    ['PATH 旧式空格写法 + $PATH', 'ENV PATH /opt/bin:$PATH\nCOPY entry.sh /opt/bin/foo\nENTRYPOINT ["foo"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本|不可见/],
    ['两行 PATH 后者引用前者', 'ENV PATH=/app\nENV PATH=$PATH:/opt\nCOPY entry.sh /app/sh\nCMD ["sh"]\n',
      { 'entry.sh': badScript }, 1, /入口脚本|不可见/],
    // F2：--from / 远程来源提供的 shell 裸名
    ['--from 提供的 sh', 'COPY --from=build /payload /usr/local/bin/sh\nCMD ["sh"]\n', {}, 1, /不可见/],
    ['远程 ADD 提供的 sh', 'ADD https://example.com/payload /usr/local/bin/sh\nCMD ["sh"]\n', {}, 1, /不可见/],
    // F4：RUN 写目录（不带文件名）
    ['RUN tar 解到 PATH 目录', 'RUN tar -xf /tmp/payload.tgz -C /usr/local/bin\nCMD ["sh"]\n', {}, 1, /不可见/],
    ['RUN curl -o 写绝对入口', 'RUN curl -fsSL https://example.com/x -o /usr/local/bin/sh\nCMD ["sh"]\n', {}, 1, /不可见/],
    // F5：HEALTHCHECK / SHELL 的包装形式
    ['HEALTHCHECK shell 形式内层脚本', 'COPY entry.sh /app/entry.sh\nHEALTHCHECK CMD sh -c /app/entry.sh\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    ['HEALTHCHECK /bin/sh -c 内层脚本', 'COPY entry.sh /app/entry.sh\nHEALTHCHECK CMD /bin/sh -c /app/entry.sh\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    // O1/O3/O4 过拦
    ['RUN 只读 + 2>/dev/null', 'RUN ls -l /usr/local/bin/tool 2>/dev/null\nENTRYPOINT ["/usr/local/bin/tool"]\n', {}, 0, null],
    ['RUN --version >/tmp/v', 'RUN /usr/local/bin/tool --version >/tmp/v 2>&1\nENTRYPOINT ["/usr/local/bin/tool"]\n', {}, 0, null],
    ['无关未解析 dest + 基础镜像入口', 'COPY x $UNDEFINED/e\nENTRYPOINT ["/usr/sbin/nginx"]\n', { 'x': 'x\n' }, 0, null],
    // WORKDIR 展开的判别用例（良性：不展开会被判 $D 不可知）
    ['WORKDIR 变量展开（良性）', 'ENV D=/srv\nWORKDIR $D\nCOPY ok.sh ./e.sh\nENTRYPOINT ["./e.sh"]\n', { 'ok.sh': goodScript }, 0, null],
  ]
  for (const [label, extra, files, expected, pattern] of cases) {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'), `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, expected, `期望 exit=${expected}（${label}），实际 ${result.status}\n${result.stdout}`)
    if (pattern) {
      const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
      assert.match(failLines, pattern, `期望 FAIL 行命中 ${pattern}（${label}）\n${result.stdout}`)
    }
  }

  // ARG 无默认值 + compose build.args 供值（承重：删掉该分支会变红）
  {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'),
      'ARG APPDIR\nCOPY entry.sh $APPDIR/entry.sh\nENTRYPOINT ["/usr/local/bin/entry.sh"]\n')
    writeFileSync(path.join(dir, 'server', 'web-runner', 'entry.sh'), badScript)
    const compose = readFileSync(path.join(dir, 'deploy', 'isolated', 'compose.yaml'), 'utf8')
    const patched = compose.replace(/(\n  web-runner:[\s\S]*?\n    build:\n)/, '$1      args:\n        APPDIR: /usr/local/bin\n')
    assert.notEqual(patched, compose, 'compose 里没找到 web-runner.build 段')
    writeFileSync(path.join(dir, 'deploy', 'isolated', 'args-compose.yaml'), patched)
    const result = check(dir, 'deploy/isolated/args-compose.yaml')
    assert.equal(result.status, 1, `期望 build.args 供值时审到入口脚本（exit=1），实际 ${result.status}\n${result.stdout}`)
  }
})

// ── 第四十一轮复核：HEALTHCHECK 命令位、裸名 PATH 优先、--from 到 /、RUN 写入候选、&> 重定向、ENV/ARG 优先级 ──
test('第四十一轮：HEALTHCHECK 命令位与包装器、裸名 PATH 优先、--from 整根、RUN 写入候选、重定向与 ENV 优先', () => {
  const badScript = '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'
  const goodScript = '#!/bin/sh\necho ok\n'
  const cases = [
    // OB-1：标准 HTTP healthcheck 不能被判死
    ['HEALTHCHECK curl HTTP', 'COPY ok.sh /app/ok.sh\nHEALTHCHECK CMD curl -f http://localhost:9999/health\n', { 'ok.sh': goodScript }, 0, null],
    ['HEALTHCHECK wget -qO-', 'HEALTHCHECK CMD wget -qO- http://localhost:9999/health\n', {}, 0, null],
    ['HEALTHCHECK sh -c curl', 'HEALTHCHECK CMD sh -c "curl -f http://localhost:9999/health"\n', {}, 0, null],
    ['HEALTHCHECK sh -c node 探针', 'HEALTHCHECK CMD sh -c "node /app/probe.mjs"\n', {}, 0, null],
    // FO-4：包装器后面才是真命令
    ['HEALTHCHECK timeout 包装', 'COPY entry.sh /app/entry.sh\nHEALTHCHECK CMD timeout 5 /app/entry.sh\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    // OB-2：`install.sh` 不能命中裸名 `sh`
    ['RUN curl … install.sh | sh', 'RUN curl -fsSL https://example.com/install.sh | sh\nCMD ["sh"]\n', {}, 0, null],
    ['RUN apt-get install bash', 'RUN apt-get install -y bash\nCMD ["sh"]\n', {}, 0, null],
    // FO-1：裸名 PATH 优先于 WORKDIR 诱饵
    ['WORKDIR 诱饵 + PATH 上的 sh', 'WORKDIR /app\nCOPY good.sh sh\nCOPY entry.sh /usr/local/bin/sh\nCMD ["sh"]\n',
      { 'good.sh': goodScript, 'entry.sh': badScript }, 1, /入口脚本|不可见/],
    // FO-2：--from 整根
    ['--from 目录到 /', 'COPY --from=build /bin/ /\nCMD ["sh"]\n', {}, 1, /不可见/],
    // FO-3：`.sh` 的 PATH 候选含 RUN 写入
    ['RUN 写 .sh + PATH + 诱饵', 'RUN install -m 755 /tmp/evil /usr/local/bin/entry.sh\nCOPY good.sh /entry.sh\nENV PATH=/usr/local/bin\nENTRYPOINT ["entry.sh"]\n',
      { 'good.sh': goodScript }, 1, /不可见/],
    // FO-5：`&>` / `2>` 也是重定向
    ['RUN &> 写基础镜像工具', 'RUN echo x &> /usr/local/bin/tool\nENTRYPOINT ["/usr/local/bin/tool"]\n', {}, 1, /不可见/],
    ['RUN 2> 写基础镜像工具', 'RUN echo x 2> /usr/local/bin/tool\nENTRYPOINT ["/usr/local/bin/tool"]\n', {}, 1, /不可见/],
    // O4：ENV 覆盖同名 ARG（承重：若 ARG 胜，dest 会是 /evil → 入口不可见 → FAIL）
    ['ENV 覆盖同名 ARG', 'ARG D=/evil\nENV D=/app\nCOPY ok.sh /app/e.sh\nENTRYPOINT ["/app/e.sh"]\n',
      { 'ok.sh': goodScript }, 0, null],
  ]
  for (const [label, extra, files, expected, pattern] of cases) {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'), `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, expected, `期望 exit=${expected}（${label}），实际 ${result.status}\n${result.stdout}`)
    if (pattern) {
      const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
      assert.match(failLines, pattern, `期望 FAIL 行命中 ${pattern}（${label}）\n${result.stdout}`)
    }
  }
})

// ── 第四十二轮复核：HEALTHCHECK 只审「被构建写过的路径」、包装器/内联选项、引号管道、包管理器、裸名只看 PATH ──
test('第四十二轮：HEALTHCHECK 审计规则、内联选项、引号管道、包管理器与裸名 PATH', () => {
  const badScript = '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'
  const goodScript = '#!/bin/sh\necho ok\n'
  const cases = [
    // F4/F5：嵌套与包装载荷也要审到
    ['HEALTHCHECK 嵌套 sh -c', 'COPY entry.sh /app/entry.sh\nHEALTHCHECK CMD sh -c \'sh -c "exec /app/entry.sh"\'\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    ['HEALTHCHECK env 前缀', 'COPY entry.sh /app/entry.sh\nHEALTHCHECK CMD sh -c "env FOO=1 /app/entry.sh"\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    ['HEALTHCHECK timeout 包装', 'COPY entry.sh /app/entry.sh\nHEALTHCHECK CMD sh -c "timeout 5 /app/entry.sh"\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    // F2：包装器的命令类内联选项
    ['HEALTHCHECK su --command=', 'COPY entry.sh /app/entry.sh\nHEALTHCHECK CMD su --command=/app/entry.sh app\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    ['HEALTHCHECK start-stop-daemon --exec=', 'COPY entry.sh /app/entry.sh\nHEALTHCHECK CMD start-stop-daemon --start --exec=/app/entry.sh\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    // F1b：HEALTHCHECK 裸名也要按 PATH，且不能被诱饵洗白
    ['HEALTHCHECK 裸名 + PATH 诱饵', 'ENV PATH=/app\nCOPY good.sh /entry.sh\nCOPY entry.sh /app/entry.sh\nHEALTHCHECK CMD entry.sh\n',
      { 'good.sh': goodScript, 'entry.sh': badScript }, 1, /入口脚本/],
    // F7：RUN 写入的解释器
    ['SHELL 裸名 + RUN 写入', 'RUN install -m 755 /tmp/evil /usr/local/bin/sh\nSHELL ["sh","-c"]\nCMD echo hi\n', {}, 1, /不可见/],
    // F8：引号与转义管道不是分隔符
    ['RUN 引号内的管道', 'RUN cp "/tmp/a|b" sh\nCMD ["sh"]\n', {}, 1, /不可见/],
    ['RUN 转义管道', 'RUN cp /tmp/a\\|b sh\nCMD ["sh"]\n', {}, 1, /不可见/],
    // O1：包管理器安装不是写文件
    ['apt-get install bash + CMD bash', 'RUN apt-get install -y bash\nCMD ["bash"]\n', {}, 0, null],
    ['apt-get install bash + 良性入口', 'RUN apt-get install -y --no-install-recommends bash\nCOPY ok.sh /app/ok.sh\nENTRYPOINT ["bash","/app/ok.sh"]\n', { 'ok.sh': goodScript }, 0, null],
    // O3：裸名只看 PATH，WORKDIR 里的同名文件不会被执行
    ['WORKDIR 同名文件 + 裸名', 'WORKDIR /app\nCOPY hack sh\nCMD ["sh"]\n', { 'hack': badScript }, 0, null],
    // F6：远程 ADD 到 / 的目标要带前导斜杠
    ['远程 ADD 到 / 的入口', 'ADD https://example.com/entry.sh /\nENTRYPOINT ["/entry.sh"]\n', {}, 1, /不可见/],
  ]
  for (const [label, extra, files, expected, pattern] of cases) {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'), `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, expected, `期望 exit=${expected}（${label}），实际 ${result.status}\n${result.stdout}`)
    if (pattern) {
      const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
      assert.match(failLines, pattern, `期望 FAIL 行命中 ${pattern}（${label}）\n${result.stdout}`)
    }
  }
})

// ── 第四十三轮复核：组合短选项、变量间接、PATH 目录污染、数据路径、包管理器变体、env -S、裸名只看 PATH ──
test('第四十三轮：组合短选项与变量间接、目录污染、数据路径、包管理器变体、env -S 与裸名 PATH', () => {
  const badScript = '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'
  const goodScript = '#!/bin/sh\necho ok\n'
  const cases = [
    // 组合短选项里的 c
    ['sh -euc 载荷', 'COPY entry.sh /app/entry.sh\nHEALTHCHECK CMD sh -euc "exec /app/entry.sh"\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    ['["sh","-ec",载荷]', 'COPY entry.sh /app/entry.sh\nHEALTHCHECK CMD ["sh","-ec","exec /app/entry.sh"]\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    // 粘连分号与 env -S
    ['-c 载荷里粘连分号', 'COPY entry.sh /app/entry.sh\nHEALTHCHECK CMD sh -c "/app/entry.sh; echo done"\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    ['SHELL env -S', 'COPY entry.sh /app/sh\nSHELL ["env","-S","/app/sh -c"]\nCMD echo hi\n', { 'entry.sh': badScript }, 1, /入口脚本/],
    // 变量间接
    ['HEALTHCHECK 变量间接', 'ENV ENTRY=/app/entry.sh\nCOPY entry.sh /app/entry.sh\nHEALTHCHECK CMD sh -c "exec $ENTRY"\n', { 'entry.sh': badScript }, 1, /变量间接/],
    // P1：同目录其它文件被写过，不能连坐
    ['PATH 目录污染（java）', 'RUN curl -fsSL https://example.com/wait-for-it.sh -o /usr/local/bin/wait-for-it.sh\nCMD ["java","-jar","/app/app.jar"]\n', {}, 0, null],
    ['PATH 目录污染（python3）', 'RUN ln -s /opt/tool /usr/local/bin/tool\nCMD ["python3","-V"]\n', {}, 0, null],
    // 数据路径不是入口
    ['HEALTHCHECK 数据路径', 'COPY ok.sh /app/ok.sh\nHEALTHCHECK CMD test -f /app/.ready\n', { 'ok.sh': goodScript }, 0, null],
    ['HEALTHCHECK --log 数据路径', 'COPY ok.sh /app/ok.sh\nHEALTHCHECK CMD /app/ok.sh --log=/app/logs/probe.log\n', { 'ok.sh': goodScript }, 0, null],
    // 包管理器变体
    ['apt-get -y install', 'RUN apt-get -y install bash\nCMD ["bash"]\n', {}, 0, null],
    ['DEBIAN_FRONTEND 前缀', 'RUN DEBIAN_FRONTEND=noninteractive apt-get install -y bash\nCMD ["bash"]\n', {}, 0, null],
    ['apt update && apt-get install', 'RUN apt update && apt-get -y install bash\nCMD ["bash"]\n', {}, 0, null],
    ['sudo apt-get install', 'RUN sudo apt-get install -y bash\nCMD ["bash"]\n', {}, 0, null],
    // 裸名只看 PATH
    ['WORKDIR 同名非 shell 文件', 'WORKDIR /app\nCOPY entry /app/entry\nCMD ["entry"]\n', { 'entry': goodScript }, 0, null],
    // O2：envdir 后面才是脚本
    ['HEALTHCHECK envdir 良性', 'COPY ok.sh /app/ok.sh\nHEALTHCHECK CMD envdir /etc/x /app/ok.sh\n', { 'ok.sh': goodScript }, 0, null],
    // F6：远程 ADD 到 / 的 docker-entrypoint（基础镜像目录名）
    ['远程 ADD docker-entrypoint.sh 到 /', 'ADD https://example.com/docker-entrypoint.sh /\nENTRYPOINT ["/docker-entrypoint.sh"]\n', {}, 1, /不可见/],
    // F7：RUN 写入的解释器 + SHELL
    ['SHELL 裸名 + RUN 写入（无其它过拦）', 'RUN install -m 755 /tmp/evil /usr/local/bin/sh\nSHELL ["sh","-c"]\nCMD echo hi\n', {}, 1, /不可见/],
  ]
  for (const [label, extra, files, expected, pattern] of cases) {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'), `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, expected, `期望 exit=${expected}（${label}），实际 ${result.status}\n${result.stdout}`)
    if (pattern) {
      const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
      assert.match(failLines, pattern, `期望 FAIL 行命中 ${pattern}（${label}）\n${result.stdout}`)
    }
  }
})

// ── 第四十四轮复核：RUN 文本归一、归档到子目录、-C 父目录、7z、HEALTHCHECK 认 RUN 写入、边界与数据路径 ──
test('第四十四轮：RUN 文本归一与解包目录、HEALTHCHECK 认 RUN 写入、边界与数据路径', () => {
  const badScript = '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'
  const goodScript = '#!/bin/sh\necho ok\n'
  const ustar = () => {
    const header = Buffer.alloc(1024)
    header.write('rootfs/', 0, 'latin1')
    header.write('0000644\0', 100, 'latin1')
    header.write('0000000\0', 108, 'latin1')
    header.write('0000000\0', 116, 'latin1')
    header.write('00000000000\0', 124, 'latin1')
    header.write('00000000000\0', 136, 'latin1')
    header.write('        ', 148, 'latin1')
    header.write('\0', 156, 'latin1')
    header.write('ustar', 257, 'latin1')
    let sum = 0
    for (let index = 0; index < 512; index += 1) sum += header[index]
    header.write(`${sum.toString(8).padStart(6, '0')}\0 `, 148, 'latin1')
    return header
  }
  const cases = [
    // FO-1：RUN 里的引号/字符类 glob 要归一
    ['RUN 引号写 sh', 'RUN cp /tmp/evil /usr/local/bin/\'sh\'\nSHELL ["sh","-c"]\n', {}, 1, /不可见/],
    ['RUN 字符类写 sh', 'RUN install -m755 /tmp/e /usr/bin/[s]h\nSHELL ["sh","-c"]\n', {}, 1, /不可见/],
    // FO-3：解包到父目录
    ['tar -C 父目录', 'RUN tar -xzf /tmp/a.tgz -C /usr/local\nSHELL ["sh","-c"]\n', {}, 1, /不可见/],
    // FO-4：7z
    ['7z 解包到目录', 'RUN 7z x /tmp/a.7z -o/usr/local/bin\nSHELL ["sh","-c"]\n', {}, 1, /不可见/],
    // FO-5：HEALTHCHECK 也认 RUN 写入
    ['HEALTHCHECK 认 RUN 写入', 'RUN printf "#!/bin/sh\\nexec /app/e\\n" > /usr/local/bin/probe\nHEALTHCHECK CMD /usr/local/bin/probe\n', {}, 1, /不可见/],
    // OB-1：`sh.bak` 不能命中 `sh`
    ['sh.bak 不连坐', 'RUN cp x /usr/local/bin/sh.bak\nSHELL ["sh","-c"]\n', {}, 0, null],
    // OB-3：数据路径不送审
    ['HEALTHCHECK jar 数据', 'COPY app.jar /app/\nHEALTHCHECK CMD test -s /app/app.jar\n', { 'app.jar': 'PK\n' }, 0, null],
    // OB-4：数据位变量不算间接
    ['HEALTHCHECK 数据位变量', 'HEALTHCHECK CMD test -f /app/$MARKER\n', {}, 0, null],
    // OB-5：microdnf
    ['microdnf install bash', 'RUN microdnf install -y bash\nCMD ["bash"]\n', {}, 0, null],
    // FO-2：归档解到目录（真 ustar）
    ['ADD 归档到 /usr/local/', 'ADD rootfs.tar /usr/local/\nSHELL ["sh","-c"]\n', { 'rootfs.tar': ustar() }, 1, /不可见/],
  ]
  for (const [label, extra, files, expected, pattern] of cases) {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'), `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, expected, `期望 exit=${expected}（${label}），实际 ${result.status}\n${result.stdout}`)
    if (pattern) {
      const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
      assert.match(failLines, pattern, `期望 FAIL 行命中 ${pattern}（${label}）\n${result.stdout}`)
    }
  }
})

// ── 第四十五轮复核：写动词归一、解包目录同义写法、无显式目录、bsdtar/gtar、xargs、RUN 变量 ──
test('第四十五轮：写动词归一、解包目录变体、无显式目录、解包器别名、xargs 与 RUN 变量', () => {
  const badScript = '#!/bin/sh\nset -a\n. /app/env\nexec node server.mjs\n'
  const goodScript = '#!/bin/sh\necho ok\n'
  const cases = [
    // 写动词被引号/反斜杠拆开
    ['RUN c\\p 写 sh', 'RUN c\\p /tmp/evil /usr/local/bin/sh\nSHELL ["sh","-c"]\n', {}, 1, /不可见/],
    ['RUN c"p" 写 sh', 'RUN c"p" /tmp/evil /usr/local/bin/sh\nSHELL ["sh","-c"]\n', {}, 1, /不可见/],
    // 字符类含 ]
    ['RUN []s]h 字符类', 'RUN install -m755 /tmp/e /usr/bin/[]s]h\nSHELL ["sh","-c"]\n', {}, 1, /不可见/],
    // 解包目录变体
    ['tar -C/usr/local', 'RUN tar -xzf /tmp/a.tgz -C/usr/local\nSHELL ["sh","-c"]\n', {}, 1, /不可见/],
    ['tar --directory=/usr/local', 'RUN tar -xzf /tmp/a.tgz --directory=/usr/local\nSHELL ["sh","-c"]\n', {}, 1, /不可见/],
    ['unzip -d/usr/local', 'RUN unzip /tmp/a.zip -d/usr/local\nSHELL ["sh","-c"]\n', {}, 1, /不可见/],
    ['7z -o/usr/local/bin', 'RUN 7z x /tmp/a.7z -o/usr/local/bin\nSHELL ["sh","-c"]\n', {}, 1, /不可见/],
    // 没有显式目录 ⇒ 解到 WORKDIR（这里是 /）
    ['tar 无 -C（cwd=/）', 'RUN tar -xzf /tmp/rootfs.tgz\nSHELL ["sh","-c"]\n', {}, 1, /不可见/],
    // 解包器别名
    ['bsdtar', 'RUN bsdtar -xzf /tmp/a.tgz -C /usr/local\nSHELL ["sh","-c"]\n', {}, 1, /不可见/],
    // xargs 前缀
    ['HEALTHCHECK xargs $ENTRY', 'ENV ENTRY=/app/entry.sh\nCOPY entry.sh /app/entry.sh\nHEALTHCHECK CMD xargs $ENTRY\n', { 'entry.sh': badScript }, 1, /变量间接/],
    // 过拦：数据路径不当入口
    ['HEALTHCHECK 数据路径（test -f）', 'RUN install -m 755 /tmp/e /app/probe\nHEALTHCHECK CMD test -f /app/probe\n', {}, 0, null],
    ['HEALTHCHECK 数据路径（grep）', 'RUN install -m 755 /tmp/e /app/probe\nHEALTHCHECK CMD grep -q ok /app/probe\n', {}, 0, null],
  ]
  for (const [label, extra, files, expected, pattern] of cases) {
    const dir = makeRepo()
    writeFileSync(path.join(dir, 'server', 'web-runner', 'Dockerfile'), `FROM scratch\n${extra}`)
    for (const [rel, content] of Object.entries(files)) {
      const target = path.join(dir, 'server', 'web-runner', rel)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, content)
    }
    const result = check(dir)
    assert.equal(result.status, expected, `期望 exit=${expected}（${label}），实际 ${result.status}\n${result.stdout}`)
    if (pattern) {
      const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
      assert.match(failLines, pattern, `期望 FAIL 行命中 ${pattern}（${label}）\n${result.stdout}`)
    }
  }
})

// ── 运行时护栏：A0-E 验收口径（清单级正确性 + 运行时护栏）的强制面 ──
test('拒绝：运行时护栏缺失（cap_drop/read_only/root user/其它 security_opt）', () => {
  const cases = [
    ['platform 缺 cap_drop', (c) => c.replace('    cap_drop:\n      - ALL\n', ''), /cap_drop/],
    ['platform 缺 read_only', (c) => c.replace('    read_only: true\n', ''), /read_only/],
    ['platform 以 root 运行', (c) => c.replace('user: "10001:10001"', 'user: "0:0"'), /root/],
    ['security_opt 含其它值', (c) => c.replace('no-new-privileges:true', 'seccomp:unconfined'), /security_opt|no-new-privileges/],
  ]
  for (const [label, mutate, pattern] of cases) {
    const dir = makeRepo()
    const mutated = mutate(baselineCompose())
    assert.notEqual(mutated, baselineCompose(), `${label}：替换锚点没命中`)
    const file = writeVariant(dir, `runtime-${label.replace(/[^\w]/g, '-')}`, mutated)
    const result = check(dir, file)
    assert.equal(result.status, 1, `期望 exit=1（${label}），实际 ${result.status}\n${result.stdout}`)
    const failLines = result.stdout.split('\n').filter((line) => line.includes('[FAIL]')).join('\n')
    assert.match(failLines, pattern, `期望 FAIL 行命中 ${pattern}（${label}）\n${result.stdout}`)
  }
})
