import test from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import { readFile } from 'node:fs/promises'
import { chromium } from 'playwright'
import { discoverPageLocators } from './page-locator-discovery.mjs'

const fixturePath = new URL('./fixtures/icc-readonly-exploration.html', import.meta.url)
const contractPath = new URL('../platform/src/test/resources/icc-exploration-contract.json', import.meta.url)

const listen = async (server) => {
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  return `http://127.0.0.1:${address.port}`
}

const close = (server) => new Promise((resolve) => server.close(resolve))

const createFixtureServers = async (context) => {
  let authHits = 0
  let externalHits = 0
  const auth = http.createServer((request, response) => {
    authHits += 1
    response.writeHead(request.url === '/auth/login' ? 200 : 404, {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': request.headers.origin || '*'
    })
    response.end(request.url === '/auth/login' ? '{"ok":true}' : '{"ok":false}')
  })
  const external = http.createServer((_request, response) => {
    externalHits += 1
    response.writeHead(200, { 'Content-Type': 'image/gif' })
    response.end('blocked')
  })
  const authOrigin = await listen(auth)
  const externalOrigin = await listen(external)
  const fixture = await readFile(fixturePath, 'utf8')
  const app = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(fixture)
  })
  const appOrigin = await listen(app)
  context.after(async () => {
    await close(app)
    await close(auth)
    await close(external)
  })
  return { appOrigin, authOrigin, externalOrigin, get authHits() { return authHits }, get externalHits() { return externalHits } }
}

const launch = (_browserId, launchOptions) => chromium.launch(launchOptions)

test('ICC exploration contract contains only redacted origins and labels', async () => {
  const contract = JSON.parse(await readFile(contractPath, 'utf8'))
  assert.deepEqual(Object.keys(contract).sort(), ['labels', 'origins'])
  assert.deepEqual(contract.origins, {
    web: 'https://app-dev.example.test',
    login: 'https://app-dev.example.test',
    auth: 'https://api.app-dev.example.test'
  })
  assert.deepEqual(contract.labels, {
    module: '威胁情报',
    route: '#/open/threat-intelligence',
    readonlyTab: '只读详情',
    statusAction: '查看状态',
    statusState: '只读',
    mutationCounter: 'mutation-counter'
  })
  assert.doesNotMatch(JSON.stringify(contract), /credential|username|password|token|cookie|secret/i)
})

test('local ICC fixture explores read-only target with auth allowlist and no mutation', async (context) => {
  const servers = await createFixtureServers(context)
  const url = `${servers.appOrigin}/?authOrigin=${encodeURIComponent(servers.authOrigin)}&externalOrigin=${encodeURIComponent(servers.externalOrigin)}#/project`
  const allowedOrigins = new Set([servers.appOrigin, servers.authOrigin])
  const expectedFixtureSearch = `?authOrigin=${encodeURIComponent(servers.authOrigin)}&externalOrigin=${encodeURIComponent(servers.externalOrigin)}`
  const snapshot = await discoverPageLocators({
    url,
    baseUrl: servers.appOrigin,
    allowedRequestUrls: [`${servers.authOrigin}/auth/login`],
    browser: 'chromium',
    viewport: '900x600',
    headless: true,
    navigationHints: ['威胁情报'],
    routeHints: ['#/open/threat-intelligence'],
    intent: {
      pageAliases: ['威胁情报'],
      routeHints: ['#/open/threat-intelligence'],
      elementTerms: ['查看状态'],
      stateTerms: ['只读'],
      expectedTexts: ['只读']
    }
  }, {
    assertAllowedUrl: async (value) => {
      const parsed = new URL(value)
      assert.equal(allowedOrigins.has(parsed.origin), true, `unexpected origin ${parsed.origin}`)
      assert.equal(parsed.username, '')
      assert.equal(parsed.password, '')
      if (parsed.origin === servers.appOrigin) assert.equal(parsed.search, expectedFixtureSearch)
      if (parsed.origin === servers.authOrigin) assert.equal(parsed.search, '')
    },
    launchBrowser: launch
  })

  assert.equal(servers.authHits, 1)
  assert.equal(servers.externalHits, 0)
  assert.equal(snapshot.pageUrl.includes('#/open/threat-intelligence'), true)
  assert.equal(snapshot.status, 'matched')
  assert.equal(snapshot.exploration.status, 'matched')
  assert.ok(snapshot.exploration.rounds.length <= 5)
  assert.ok(snapshot.exploration.durationMs <= 90_000)
  assert.ok(snapshot.exploration.scores.pageScore >= 20)
  assert.ok(snapshot.exploration.scores.elementScore >= 25)
  assert.ok(snapshot.exploration.scores.stateScore >= 10)
  assert.ok(snapshot.exploration.scores.totalScore >= 70)
  assert.deepEqual(snapshot.exploration.rounds.map((round) => round.action?.type).filter(Boolean), [
    'menu-search',
    'dismiss-overlay',
    'scroll-container',
    'switch-tab'
  ])
  assert.equal(snapshot.exploration.rounds.some((round) => round.observation?.overlays?.some((overlay) => overlay.blocking)), true)
  assert.equal(snapshot.exploration.rounds.some((round) => round.observation?.scrollContainers > 0), true)
  const statusActions = snapshot.finalObservation.elements.filter((element) => element.label === '查看状态')
  assert.equal(statusActions.length, 2)
  assert.deepEqual(statusActions.map((element) => element.rowContext).sort(), [
    '设备 A · 只读 · 在线 查看状态',
    '设备 B · 只读 · 离线 查看状态'
  ])
  assert.equal(snapshot.finalObservation.elements.find((element) => element.locator === 'testId=mutation-counter')?.text, '0')
  assert.equal(snapshot.exploration.rounds.some((round) => round.actionFailure), false)
  assert.equal(snapshot.exploration.networkBlocked.some((entry) => entry.failureType === 'network_blocked'), true)
  assert.equal(snapshot.exploration.networkBlocked.some((entry) => /[?#]/.test(entry.url)), false)
  assert.equal(snapshot.exploration.network.authDependencies.some((entry) => entry.origin === servers.authOrigin && entry.path === '/auth/login'), true)
  assert.equal(snapshot.exploration.network.authDependencies.every((entry) => Object.keys(entry).sort().join(',') === 'criticality,failureType,origin,path,resourceType'), true)
  assert.equal(snapshot.exploration.network.authDependencies.every((entry) => entry.origin === servers.authOrigin && entry.path === '/auth/login'), true)
  assert.equal(snapshot.exploration.network.authDependencies.some((entry) => 'url' in entry), false)
  assert.equal(snapshot.exploration.network.authDependencies.some((entry) => 'detail' in entry), false)
})

test('blocklist mode explores the same ICC fixture with no allowlist at all (default posture)', async (context) => {
  // 用户要求：默认不拦截任何请求——黑名单模式下即便不配置任何条目，登录 POST 与页面资源也应放行。
  const servers = await createFixtureServers(context)
  const url = `${servers.appOrigin}/?authOrigin=${encodeURIComponent(servers.authOrigin)}&externalOrigin=${encodeURIComponent(servers.externalOrigin)}#/project`
  const expectedFixtureSearch = `?authOrigin=${encodeURIComponent(servers.authOrigin)}&externalOrigin=${encodeURIComponent(servers.externalOrigin)}`
  const snapshot = await discoverPageLocators({
    url,
    baseUrl: servers.appOrigin,
    browser: 'chromium',
    viewport: '900x600',
    headless: true,
    networkPolicyMode: 'blocklist',
    navigationHints: ['威胁情报'],
    routeHints: ['#/open/threat-intelligence'],
    intent: {
      pageAliases: ['威胁情报'],
      routeHints: ['#/open/threat-intelligence'],
      elementTerms: ['查看状态'],
      stateTerms: ['只读'],
      expectedTexts: ['只读']
    }
  }, {
    assertAllowedUrl: async (value) => {
      const parsed = new URL(value)
      assert.equal((parsed.origin === servers.appOrigin || parsed.origin === servers.authOrigin) || parsed.origin === servers.externalOrigin, true)
      if (parsed.origin === servers.appOrigin) assert.equal(parsed.search, expectedFixtureSearch)
    },
    launchBrowser: launch
  })

  assert.equal(servers.authHits, 1)
  assert.equal(snapshot.status, 'matched')
  assert.equal(snapshot.exploration.status, 'matched')
  assert.ok(snapshot.exploration.scores.totalScore >= 70)
})

test('missing local ICC target exhausts with an explicit bounded failure category', async (context) => {
  const servers = await createFixtureServers(context)
  const url = `${servers.appOrigin}/?authOrigin=${encodeURIComponent(servers.authOrigin)}&scenario=missing#/project`
  const snapshot = await discoverPageLocators({
    url,
    baseUrl: servers.appOrigin,
    allowedRequestUrls: [`${servers.authOrigin}/auth/login`],
    browser: 'chromium',
    viewport: '900x600',
    headless: true,
    navigationHints: ['威胁情报'],
    routeHints: ['#/open/threat-intelligence'],
    intent: { pageAliases: ['威胁情报'], elementTerms: ['查看状态'], stateTerms: ['只读'] },
    budget: { maxRounds: 5, maxDurationMs: 90_000 }
  }, {
    assertAllowedUrl: async (value) => {
      assert.equal(new URL(value).origin === servers.appOrigin || new URL(value).origin === servers.authOrigin, true)
    },
    launchBrowser: launch
  })

  assert.equal(snapshot.status, 'exhausted')
  assert.equal(snapshot.exploration.failureReason, 'round_budget_exhausted')
  assert.ok(snapshot.exploration.rounds.length <= 5)
  assert.ok(snapshot.exploration.durationMs <= 90_000)
  assert.equal(snapshot.exploration.rounds.some((round) => round.action?.type === 'switch-tab'), true)
  assert.equal(snapshot.exploration.rounds.at(-1)?.actionFailure, undefined)
})

/*
 * The deterministic local-fixture acceptance above is part of the normal CI
 * npm test suite. Only a real ICC login acceptance is manual-dev-only and must
 * stay out of CI: run the Web Runner against the existing local Web
 * environment with runtime-injected credentials and an explicit
 * allowedRequestUrls entry for https://api.app-dev.example.test/auth/login. Never
 * put credentials in this fixture, contract, command line, or test output;
 * verify only the read-only route and evidence target, then record a redacted report.
 */
