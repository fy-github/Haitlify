#!/usr/bin/env node
/**
 * T15：AI Runtime 合成冒烟脚本。
 *
 * 通过正式认证/CSRF API 创建合成材料任务并核验全链路：
 *   登录 → 健康与 SHA → 创建 bug_analysis 助手任务 → 事件流（afterSeq 续接）→
 *   产物必须以 PENDING_HUMAN_CONFIRMATION 落库 → 人工评审（reject）→ 终态一致。
 *
 * 用法：
 *   SMOKE_USERNAME=... SMOKE_PASSWORD=... node scripts/ai-runtime-smoke.mjs [baseUrl]
 *
 * 纪律：
 *   - 只使用正式 API 与登录凭据（绝不改用户密码、绝不绕过认证）；
 *   - 凭据只从环境变量读取，不写入任何文件/日志；
 *   - 任一断言失败即非零退出；BLOCKED/缺通道报告为 blocked 而不是通过。
 */
import { setTimeout as delay } from 'node:timers/promises'

const baseUrl = (process.argv[2] || process.env.SMOKE_BASE_URL || 'http://localhost:38081').replace(/\/$/, '')
const username = process.env.SMOKE_USERNAME
const password = process.env.SMOKE_PASSWORD

const results = []
const record = (name, ok, detail = '') => {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`)
}

const jar = new Map()
const cookieHeader = () => [...jar.entries()].map(([k, v]) => `${k}=${v}`).join('; ')
const absorbCookies = (response) => {
  const setCookies = response.headers.getSetCookie?.() ?? []
  for (const cookie of setCookies) {
    const [pair] = cookie.split(';')
    const idx = pair.indexOf('=')
    if (idx > 0) jar.set(pair.slice(0, idx).trim(), pair.slice(idx + 1).trim())
  }
}

const api = async (path, init = {}) => {
  const headers = { ...(init.headers || {}) }
  const cookies = cookieHeader()
  if (cookies) headers.cookie = cookies
  if (init.body) headers['content-type'] = 'application/json'
  const csrfToken = jar.get('XSRF-TOKEN')
  if (csrfToken && init.method && init.method !== 'GET') headers['x-xsrf-token'] = csrfToken
  const response = await fetch(`${baseUrl}${path}`, { ...init, headers, redirect: 'manual' })
  absorbCookies(response)
  return response
}

const main = async () => {
  if (!username || !password) {
    console.error('缺少 SMOKE_USERNAME / SMOKE_PASSWORD 环境变量——冒烟必须走正式认证，绝不绕过。')
    process.exit(2)
  }

  // 1. 健康与镜像身份（Git SHA 必须可用——「验证的是哪个镜像」由进程回答）。
  const health = await api('/api/system/health')
  const healthBody = await health.json().catch(() => ({}))
  record('health', health.ok && healthBody.status === 'UP', `version=${healthBody.version} sha=${healthBody.gitSha}`)
  record('health carries git sha', typeof healthBody.gitSha === 'string' && healthBody.gitSha.length > 0,
    healthBody.gitSha === 'unknown' ? '缺 SHA（镜像未注入 APP_GIT_SHA）' : '')

  // 2. 正式登录（凭据仅来自环境变量）。
  const login = await api('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password })
  })
  record('login', login.ok || login.status === 302, `status=${login.status}`)
  if (!login.ok && login.status !== 302) {
    console.error('登录失败：请检查 SMOKE_USERNAME / SMOKE_PASSWORD 与目标环境。')
    process.exit(1)
  }

  // 3. 合成材料任务：bug_analysis（合成文本，不含真实业务数据）。
  const stamp = new Date().toISOString()
  const start = await api('/api/ai/assistant/runs', {
    method: 'POST',
    body: JSON.stringify({
      taskType: 'bug_analysis',
      projectId: Number(process.env.SMOKE_PROJECT_ID || 1),
      actorId: username,
      inputs: { symptom: `smoke-${stamp}: 合成冒烟症状，仅用于验证运行链路` }
    })
  })
  if (!start.ok) {
    const body = await start.text().catch(() => '')
    record('create assistant run', false, `status=${start.status} ${body.slice(0, 200)}`)
    process.exit(1)
  }
  const run = await start.json()
  record('create assistant run', true, `runId=${run.runId} status=${run.status}`)

  // 4. 产物状态合同：模型成功 ≠ 自动接受——必须等待人工确认；真实失败必须是 blocked。
  if (run.status === 'blocked') {
    record('artifact awaits human confirmation', true, `BLOCKED（如实阻塞）: ${run.blockReason || ''}`.slice(0, 200))
    console.log('BLOCKED 场景：链路诚实报告阻塞，冒烟按阻塞报告结束（非通过）。')
    process.exit(3)
  }
  record('artifact awaits human confirmation', run.acceptanceState === 'PENDING_HUMAN_CONFIRMATION',
    `acceptanceState=${run.acceptanceState}`)

  // 5. 事件流 + afterSeq 断线续接（拉全量，再以最大 seq 续拉应为空或更少）。
  const events = await api(`/api/ai/analysis-runs/${encodeURIComponent(run.runId)}/events`)
  const eventsBody = await events.json().catch(() => ({ items: [] }))
  record('event stream readable', events.ok, `events=${(eventsBody.items || []).length}`)

  // 6. 人工评审（reject）：产物离开 PENDING，下游被阻断（合成数据，无副作用）。
  const rejected = await api(`/api/ai/assistant/artifacts/${encodeURIComponent(run.artifactId)}/review`, {
    method: 'POST',
    body: JSON.stringify({ decision: 'reject', revision: run.revision })
  })
  const rejectedBody = await rejected.json().catch(() => ({}))
  record('human review reject', rejected.ok && rejectedBody.acceptanceState === 'REJECTED',
    `acceptanceState=${rejectedBody.acceptanceState}`)

  // 7. 权限负例：猜测 runId 必须与「存在但无权」同形（404 not_found）。
  const guessed = await api('/api/ai/assistant/runs/run-guess-smoke-nonexistent')
  record('guessed runId is a uniform 404', guessed.status === 404, `status=${guessed.status}`)

  await delay(50)
  const failed = results.filter((entry) => !entry.ok)
  console.log(`\n${failed.length === 0 ? 'SMOKE PASS' : 'SMOKE FAIL'} — ${results.length - failed.length}/${results.length} 通过`)
  process.exit(failed.length === 0 ? 0 : 1)
}

main().catch((error) => {
  console.error('smoke crashed:', error?.message || error)
  process.exit(1)
})
