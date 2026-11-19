import fs from 'node:fs'
import path from 'node:path'
import { chromium, firefox, webkit } from 'playwright'
import {
  launchSelectedBrowser,
  normalizeBrowserId,
  probeBrowserCapabilities
} from './browser-launcher.mjs'
import { createRunJobManager } from './run-job-manager.mjs'
import { executeRunJobByBrowserGroups } from './run-executor.mjs'
import {
  createLoginDiscoveryManager,
  discoverLoginPage
} from './login-discovery.mjs'
import { createLoginDiscoveryNetworkPolicy } from './login-discovery-network-policy.mjs'
import { discoverPageLocators } from './page-locator-discovery.mjs'
import { createWebRunnerServer } from './web-runner-server.mjs'
import { registerPageDiagnostics, clearPageDiagnostics } from './page-diagnostics.mjs'
import { clearSessions, listSessions, startSessionCleanup } from './session-manager.mjs'
import { resolveAllowLocalNetworkAccess, resolveAllowPrivateNetworks } from './runner-env.mjs'

// 默认端口刻意避开宿主常用端口;compose 内由 APP_WEB_AUTOMATION_RUNNER_PORT 显式指定(容器内 9323)
const port = Number(process.env.APP_WEB_AUTOMATION_RUNNER_PORT || 19323)
const host = process.env.APP_WEB_AUTOMATION_RUNNER_HOST || '127.0.0.1'
const defaultBrowserId = process.env.APP_WEB_AUTOMATION_RUNNER_BROWSER_CHANNEL || 'chromium'
const browserHeadless = process.env.APP_WEB_AUTOMATION_RUNNER_HEADLESS !== 'false'
const allowLocalNetworkAccess = resolveAllowLocalNetworkAccess()
const strictStartup = process.env.APP_WEB_AUTOMATION_RUNNER_STRICT_STARTUP === 'true'
// 未配置 PUBLIC_URL 时不要写死容器内端口：证据链接按请求 Host 还原（见 web-runner-server.mjs）。
const configuredPublicBaseUrl = process.env.APP_WEB_AUTOMATION_RUNNER_PUBLIC_URL
  ? process.env.APP_WEB_AUTOMATION_RUNNER_PUBLIC_URL.replace(/\/$/, '')
  : ''
const publicBaseUrl = configuredPublicBaseUrl || `http://127.0.0.1:${port}`
const fixtureDir = path.resolve('fixtures')
const artifactDir = path.resolve('artifacts')
const engines = { chromium, firefox, webkit }
const jobManager = createRunJobManager()
const loginDiscoveryManager = createLoginDiscoveryManager()
const loginDiscoveryNetworkPolicy = createLoginDiscoveryNetworkPolicy({
  allowedHosts: process.env.APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOWED_HOSTS,
  allowPrivateNetworks: resolveAllowPrivateNetworks()
})
const runtimeState = {
  capabilities: {
    status: 'DEGRADED',
    checkedAt: '',
    browsers: []
  }
}

const server = createWebRunnerServer({
  jobManager,
  sessionsProvider: () => listSessions(),
  healthProvider: () => buildHealthResponse(),
  capabilitiesProvider: () => runtimeState.capabilities,
  executeJob: (job) => executeRunJob(job),
  loginDiscoveryManager,
  executeLoginDiscovery: (job) => executeLoginDiscoveryJob(job),
  executePageLocatorDiscovery: (input) => discoverPageLocators({
    ...input,
    browser: normalizeBrowserId(input.browser || defaultBrowserId)
  }, {
    assertAllowedUrl: (url) => loginDiscoveryNetworkPolicy.assertAllowedUrl(url),
    allowLocalNetworkAccess,
    artifactDir,
    publicBaseUrl,
    launchBrowser: (browserId, launchOptions) => launchSelectedBrowser(browserId, launchOptions, engines)
  }),
  staticFileHandler: serveStaticFile,
  publicBaseUrl: configuredPublicBaseUrl || null
})
const stopSessionCleanup = startSessionCleanup()

const main = async () => {
  fs.mkdirSync(artifactDir, { recursive: true })
  server.listen(port, host, () => {
    console.log(`web-runner listening on http://${host}:${port}`)
    void refreshCapabilities()
  })
}

const refreshCapabilities = async () => {
  runtimeState.capabilities = {
    ...await probeBrowserCapabilities({ headless: true }, engines),
    checkedAt: new Date().toISOString()
  }
  logCapabilityCheck(runtimeState.capabilities)

  if (runtimeState.capabilities.status !== 'UP' && strictStartup) {
    console.error('web-runner strict startup failed, exiting because APP_WEB_AUTOMATION_RUNNER_STRICT_STARTUP=true')
    shutdown()
  }
}

const shutdown = () => {
  stopSessionCleanup()
  clearSessions()
  server.close(() => process.exit(0))
}
process.once('SIGTERM', shutdown)
process.once('SIGINT', shutdown)

const buildHealthResponse = () => {
  const defaultCapability = runtimeState.capabilities.browsers.find((item) => item.id === defaultBrowserId)
  const availableCount = runtimeState.capabilities.browsers.filter((item) => item.available).length
  const checkingCapabilities = !runtimeState.capabilities.checkedAt
  return {
    status: checkingCapabilities ? 'UP' : (availableCount > 0 ? 'UP' : 'DEGRADED'),
    service: 'ai-test-platform-web-runner',
    defaultBrowser: defaultBrowserId,
    strictStartup,
    localNetworkAccess: allowLocalNetworkAccess,
    checkedAt: runtimeState.capabilities.checkedAt,
    message: checkingCapabilities
      ? 'Web Runner is listening; Playwright capability probe is in progress'
      : defaultCapability?.available
        ? `Playwright browser launch is ready (${defaultBrowserId})`
        : `${availableCount} browser drivers available; default ${defaultBrowserId} is unavailable`,
    recommendations: defaultCapability?.available || !defaultCapability?.recommendation
      ? (allowLocalNetworkAccess ? [] : ['当前未开启 Chromium 本地网络访问兼容模式；若登录页请求私网/本地 API，请以 APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS=true 重启 Runner。'])
      : [defaultCapability.recommendation]
  }
}

const executeRunJob = async (job) => {
  await executeRunJobByBrowserGroups(job, {
    defaultBrowserId,
    browserHeadless,
    allowLocalNetworkAccess,
    launchBrowser: (browserId, launchOptions) => launchSelectedBrowser(browserId, launchOptions, engines),
    registerPageDiagnostics,
    captureLiveScreenshot,
    captureFailureScreenshot
  })
}

const executeLoginDiscoveryJob = async (job) => {
  job.markRunning()
  const input = job.input()
  const result = await discoverLoginPage({
    ...input,
    browser: normalizeBrowserId(input.browser || defaultBrowserId)
  }, {
    discoveryId: job.id,
    artifactDir,
    publicBaseUrl,
    assertAllowedUrl: (url) => loginDiscoveryNetworkPolicy.assertAllowedUrl(url),
    allowLocalNetworkAccess,
    launchBrowser: (browserId, launchOptions) => launchSelectedBrowser(browserId, launchOptions, engines)
  })
  job.complete(result)
}

const captureLiveScreenshot = async (page, job) => {
  try {
    const fileName = `${sanitizeFileName(job.id)}-live-${Date.now()}.png`
    const filePath = path.join(artifactDir, fileName)
    await page.screenshot({ path: filePath, fullPage: false })
    job.updateScreenshot({
      url: `${publicBaseUrl}/artifacts/${fileName}`,
      capturedAt: new Date().toISOString()
    })
  } catch (error) {
    job.appendDiagnostic(`实时截图失败: ${error instanceof Error ? error.message : String(error)}`)
  }
}

const captureFailureScreenshot = async (page, caseId) => {
  try {
    const fileName = `${sanitizeFileName(caseId)}-${Date.now()}.png`
    const filePath = path.join(artifactDir, fileName)
    await page.screenshot({ path: filePath, fullPage: true })
    return `${publicBaseUrl}/artifacts/${fileName}`
  } catch {
    return undefined
  }
}

async function serveStaticFile(request, response, url) {
  const staticTarget = url.pathname.startsWith('/fixtures/')
    ? { root: fixtureDir, relative: url.pathname.slice('/fixtures/'.length), contentType: 'text/html; charset=utf-8' }
    : url.pathname.startsWith('/artifacts/')
      ? { root: artifactDir, relative: url.pathname.slice('/artifacts/'.length), contentType: 'image/png' }
      : undefined
  if (!staticTarget) return false

  const filePath = path.resolve(staticTarget.root, staticTarget.relative)
  if (!filePath.startsWith(`${staticTarget.root}${path.sep}`) || !fs.existsSync(filePath)) return false
  response.writeHead(200, { 'Content-Type': staticTarget.contentType })
  response.end(fs.readFileSync(filePath))
  return true
}

const logCapabilityCheck = (capabilities) => {
  for (const browser of capabilities.browsers) {
    const writer = browser.available ? console.log : console.error
    writer(`[startup-check] ${browser.label}: ${browser.available ? 'ready' : browser.message}`)
  }
}

const sanitizeFileName = (value) => String(value).replace(/[^\w.-]+/g, '_')

await main()
