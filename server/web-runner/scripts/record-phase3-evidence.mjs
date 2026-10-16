// 阶段 3 证据取证脚本（可复跑）：
//   node scripts/record-phase3-evidence.mjs
// 用真实 Chromium 跑「点击菜单 → 断言目标页标题」的跨页场景与 iframe/shadow 作用域场景，
// 把 runner 的原始响应写成平台测试资源：
//   server/platform/src/test/resources/ai/evidence/phase3-runner-response.json        （iframe + open shadow 作用域）
//   server/platform/src/test/resources/ai/evidence/phase3-runner-action-response.json （跨页 Action IR + pageSnapshots）
//   server/platform/src/test/resources/ai/evidence/phase3-runner-navigate-response.json（导航型跨页 + 地址断言）
//   server/platform/src/test/resources/ai/evidence/phase3-runner-plain-start-response.json（起始页无可采集元素）
//
// 这两个文件必须由本脚本生成，不允许手工编辑：平台测试直接消费原始响应，再走平台自己的
// WebStepPageEvidenceAssembler 组装页面证据，因此「fixture 手工拼两页」无法再掩盖生产链路的缺口。
// 响应里带 runnerImplementationHash：runner 源码改动后必须重新生成，否则测试会失败。
import http from 'node:http'
import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'
import { discoverPageLocators } from '../page-locator-discovery.mjs'
import { runnerImplementationHash } from './phase3-implementation-hash.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const evidenceDir = path.resolve(here, '../../platform/src/test/resources/ai/evidence')
const runnerResponsePath = path.join(evidenceDir, 'phase3-runner-response.json')
const runnerActionResponsePath = path.join(evidenceDir, 'phase3-runner-action-response.json')
const runnerNavigateResponsePath = path.join(evidenceDir, 'phase3-runner-navigate-response.json')
const runnerPlainResponsePath = path.join(evidenceDir, 'phase3-runner-plain-start-response.json')

// 固定端口：载荷里的 pageKey/pageUrl/frameUrl/pageFingerprint 都含端口，
// 用随机端口会导致「复跑必然改动 fixture」，让「由真实运行生成」无法通过重跑比对复核。
const RECORD_PORT = Number(process.env.PHASE3_RECORD_PORT || 41827)

const routes = {
  '/list': `<!doctype html>
    <nav><a id="menu-link" href="/detail">设备详情</a></nav>
    <h1 id="list-title">设备列表</h1>`,
  '/detail': '<!doctype html><h1 id="detail-title">设备详情页</h1>',
  // 没有任何可采集元素（无链接/按钮/标题/角色/测试 id）的页面：用来固化「空页面快照」的行为。
  '/plain': '<!doctype html><p>纯文本说明，没有任何可采集元素</p>',
  '/scoped': `<!doctype html>
    <div id="host"></div>
    <iframe id="inner" src="/scoped-frame"></iframe>
    <button id="outside">外部按钮</button>
    <script>
      const open = document.querySelector('#host').attachShadow({ mode: 'open' })
      open.innerHTML = '<button id="shadow-btn">影子按钮</button>'
    </script>`,
  '/scoped-frame': '<!doctype html><button id="inside">帧内按钮</button>'
}

const startServer = async () => {
  const server = http.createServer((request, response) => {
    const route = String(request.url || '/').split('?')[0]
    const html = routes[route]
    if (!html) {
      response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' })
      response.end('not found')
      return
    }
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(html)
  })
  await new Promise((resolve, reject) => {
    server.once('error', (error) => reject(new Error(`取证端口 ${RECORD_PORT} 不可用：${error.message}`)))
    server.listen(RECORD_PORT, '127.0.0.1', resolve)
  })
  return { server, origin: `http://127.0.0.1:${RECORD_PORT}` }
}

const launchBrowser = (_browserId, launchOptions) => chromium.launch(launchOptions)

// 计时类字段每次运行都会变，但不承载证据语义：落盘前归一化为 0，
// 这样「复跑取证脚本应当没有 diff」才是可复核的事实，而不是靠口头声明。
const VOLATILE_EVIDENCE_KEYS = new Set(['durationMs', 'remainingDurationMs'])
const stabilizeEvidencePayload = (value) => {
  if (Array.isArray(value)) return value.map(stabilizeEvidencePayload)
  if (value && typeof value === 'object') {
    const stabilized = {}
    for (const [key, entry] of Object.entries(value)) {
      stabilized[key] = VOLATILE_EVIDENCE_KEYS.has(key) && typeof entry === 'number' ? 0 : stabilizeEvidencePayload(entry)
    }
    return stabilized
  }
  return value
}

const run = async () => {
  const { server, origin } = await startServer()
  try {
    const listRun = await discoverPageLocators({
      sourcePointId: 'point-phase3-record',
      url: `${origin}/list`,
      baseUrl: origin,
      browser: 'chromium',
      headless: true,
      timeoutMs: 3_000,
      intent: { pageAliases: ['设备列表'], elementTerms: ['设备详情'] }
    }, { launchBrowser })

    const detailRun = await discoverPageLocators({
      sourcePointId: 'point-phase3-record',
      url: `${origin}/detail`,
      baseUrl: origin,
      browser: 'chromium',
      headless: true,
      timeoutMs: 3_000,
      intent: { pageAliases: ['设备详情页'], elementTerms: ['设备详情页'] }
    }, { launchBrowser })

    const menuEvidence = listRun.elements.find((entry) => entry.locator && entry.accessibleName === '设备详情')
    const titleEvidence = detailRun.elements.find((entry) => entry.locator && entry.text === '设备详情页')
    if (!menuEvidence || !titleEvidence) {
      throw new Error('未能采集到跨页场景所需的菜单/标题证据')
    }

    const actionRun = await discoverPageLocators({
      sourcePointId: 'point-phase3-record',
      url: `${origin}/list`,
      baseUrl: origin,
      browser: 'chromium',
      headless: true,
      timeoutMs: 5_000,
      intent: { pageAliases: ['设备列表'], elementTerms: ['设备详情'] },
      actions: [
        { id: 'open-detail', type: 'click', safety: 'readonly', target: { kind: 'evidence', evidenceId: menuEvidence.evidenceId } },
        { id: 'assert-detail-title', type: 'assertVisible', safety: 'readonly', target: { kind: 'evidence', evidenceId: titleEvidence.evidenceId } }
      ]
    }, { launchBrowser })

    const [openStep, assertStep] = actionRun.stepEvidence
    if (!openStep || !assertStep) throw new Error('跨页 Action IR 未产出两个 step evidence')
    if (openStep.before.pageKey === assertStep.before.pageKey) throw new Error('两个 step evidence 必须来自不同页面')
    const snapshotPageKeys = new Set((actionRun.pageSnapshots || []).map((snapshot) => snapshot.pageKey))
    if (snapshotPageKeys.size < 2) throw new Error(`pageSnapshots 必须覆盖两个页面，实际 ${snapshotPageKeys.size}`)

    // 同一轮真实运行里记录「iframe + open shadow」页面的原始 runner 响应，
    // 供平台侧断言 DTO 不会丢弃 evidenceId / locatorSpec / framePath / shadowPath。
    const scopedRun = await discoverPageLocators({
      sourcePointId: 'point-phase3-scoped',
      url: `${origin}/scoped`,
      baseUrl: origin,
      browser: 'chromium',
      headless: true,
      timeoutMs: 3_000,
      intent: { pageAliases: ['外部按钮'], elementTerms: ['帧内按钮', '影子按钮'] }
    }, { launchBrowser })
    const scopedFrameElement = (scopedRun.elements || []).find((entry) => entry.framePath === '#inner')
    const scopedShadowElement = (scopedRun.elements || []).find((entry) => entry.shadowPath === '#host')
    if (!scopedFrameElement || !scopedShadowElement) {
      throw new Error('未能采集到 iframe / open shadow 作用域证据')
    }

    // 导航型跨页（route 目标而不是点击）：run 里第 1 步没有任何元素引用，
    // 页面快照仍必须带上可用元素，否则平台会丢掉起始页并让用例编译失败。
    const navigateRun = await discoverPageLocators({
      sourcePointId: 'point-phase3-record',
      url: `${origin}/list`,
      baseUrl: origin,
      browser: 'chromium',
      headless: true,
      timeoutMs: 5_000,
      intent: { pageAliases: ['设备列表'], elementTerms: ['设备详情'] },
      actions: [
        { id: 'go-detail', type: 'navigate', safety: 'readonly', target: { kind: 'route', value: `${origin}/detail` } },
        { id: 'assert-detail-url', type: 'assertUrl', safety: 'readonly', target: { kind: 'route', value: `${origin}/detail` } },
        { id: 'assert-detail-title', type: 'assertVisible', safety: 'readonly', target: { kind: 'evidence', evidenceId: titleEvidence.evidenceId } }
      ]
    }, { launchBrowser })
    const navigateSnapshots = navigateRun.pageSnapshots || []
    const emptySnapshot = navigateSnapshots.find((snapshot) => !Array.isArray(snapshot.elements) || snapshot.elements.length === 0)
    if (emptySnapshot) throw new Error(`导航型跨页的页面快照不能为空：${emptySnapshot.pageKey}`)
    if (!(navigateSnapshots.length >= 2)) throw new Error(`导航型跨页必须有两页快照，实际 ${navigateSnapshots.length}`)
    const urlStep = (navigateRun.stepEvidence || []).find((step) => step.actionId === 'assert-detail-url')
    if (!urlStep || urlStep.execution.status !== 'passed') throw new Error('地址断言步骤必须产生 passed 的 step evidence')
    if (!navigateRun.unsupported || typeof navigateRun.unsupported !== 'object') throw new Error('取证响应必须带 unsupported 诊断字段（平台 DTO 需要它）')

    // 起始页没有任何可采集元素的极端情况：快照必须显式标记 elementsEmpty，
    // 平台据此保留该页证据（路由型步骤只需要页面身份），而不是静默少一页。
    const plainRun = await discoverPageLocators({
      sourcePointId: 'point-phase3-plain',
      url: `${origin}/plain`,
      baseUrl: origin,
      browser: 'chromium',
      headless: true,
      timeoutMs: 5_000,
      intent: { pageAliases: ['纯文本页'], elementTerms: ['设备详情'] },
      actions: [
        { id: 'plain-go-detail', type: 'navigate', safety: 'readonly', target: { kind: 'route', value: `${origin}/detail` } },
        { id: 'plain-assert-detail-url', type: 'assertUrl', safety: 'readonly', target: { kind: 'route', value: `${origin}/detail` } }
      ]
    }, { launchBrowser })
    const plainSnapshots = plainRun.pageSnapshots || []
    const plainStart = plainSnapshots.find((snapshot) => snapshot.pageKey.endsWith('/plain'))
    if (!plainStart) throw new Error('无元素页面的快照也必须被记录')
    if (plainStart.elements.length !== 0 || plainStart.elementsEmpty !== true) {
      throw new Error(`无元素页面必须显式标记 elementsEmpty，实际 ${JSON.stringify(plainStart.elements.length)}`)
    }

    const implementationHash = await runnerImplementationHash()
    await mkdir(evidenceDir, { recursive: true })
    await writeFile(runnerActionResponsePath, `${JSON.stringify({ ...stabilizeEvidencePayload(actionRun), runnerImplementationHash: implementationHash }, null, 2)}\n`, 'utf8')
    await writeFile(runnerNavigateResponsePath, `${JSON.stringify({ ...stabilizeEvidencePayload(navigateRun), runnerImplementationHash: implementationHash }, null, 2)}\n`, 'utf8')
    await writeFile(runnerPlainResponsePath, `${JSON.stringify({ ...stabilizeEvidencePayload(plainRun), runnerImplementationHash: implementationHash }, null, 2)}\n`, 'utf8')
    await writeFile(runnerResponsePath, `${JSON.stringify({ ...stabilizeEvidencePayload(scopedRun), runnerImplementationHash: implementationHash }, null, 2)}\n`, 'utf8')
    console.log(`PHASE3_RUNNER_ACTION_RESPONSE_WRITTEN path=${runnerActionResponsePath}`)
    console.log(`PHASE3_RUNNER_NAVIGATE_RESPONSE_WRITTEN path=${runnerNavigateResponsePath}`)
    console.log(`PHASE3_RUNNER_PLAIN_RESPONSE_WRITTEN path=${runnerPlainResponsePath}`)
    console.log(`PHASE3_RUNNER_RESPONSE_WRITTEN path=${runnerResponsePath}`)
    console.log(`menuEvidenceId=${menuEvidence.evidenceId} titleEvidenceId=${titleEvidence.evidenceId}`)
    console.log(`openStep.before.pageKey=${openStep.before.pageKey}`)
    console.log(`assertStep.before.pageKey=${assertStep.before.pageKey}`)
    console.log(`scopedFrameLocator=${scopedFrameElement.locator}`)
    console.log(`scopedShadowLocator=${scopedShadowElement.locator}`)
    console.log(`runnerImplementationHash=${implementationHash}`)
  } finally {
    await new Promise((resolve) => server.close(resolve))
  }
}

run().then(() => {
  console.log('PHASE3_RECORD_OK')
}).catch((error) => {
  console.error('PHASE3_RECORD_FAILED', error)
  process.exitCode = 1
})
