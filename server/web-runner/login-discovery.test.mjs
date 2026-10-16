import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { chromium } from 'playwright'
import {
  analyzeLoginElements,
  createLoginDiscoveryManager,
  discoverLoginPage
} from './login-discovery.mjs'

const elements = [
  {
    domIndex: 0,
    tagName: 'input',
    type: 'text',
    id: 'search',
    name: 'keyword',
    placeholder: '搜索',
    autocomplete: '',
    ariaLabel: '搜索',
    label: ''
  },
  {
    domIndex: 1,
    tagName: 'input',
    type: 'text',
    id: 'username',
    name: 'username',
    placeholder: '请输入账号',
    autocomplete: 'username',
    ariaLabel: '',
    label: '账号'
  },
  {
    domIndex: 2,
    tagName: 'input',
    type: 'password',
    id: '',
    name: 'password',
    placeholder: '请输入密码',
    autocomplete: 'current-password',
    ariaLabel: '',
    label: '密码'
  },
  {
    domIndex: 3,
    tagName: 'button',
    type: 'submit',
    id: '',
    name: '',
    text: '登录',
    ariaLabel: '登录',
    label: ''
  },
  {
    domIndex: 4,
    tagName: 'input',
    type: 'email',
    id: '',
    name: 'email',
    placeholder: '邮箱',
    autocomplete: '',
    ariaLabel: '',
    label: ''
  }
]

test('analyzeLoginElements ranks semantic login controls and builds executable locators', () => {
  const result = analyzeLoginElements(elements)

  assert.equal(result.recommended.username.locator, 'css=#username')
  assert.equal(result.recommended.password.locator, 'css=input[name="password"]')
  assert.equal(result.recommended.submit.locator, 'role=button[name="登录", exact=true]')
  assert.ok(result.recommended.username.confidence > result.candidates.username.at(-1).confidence)
  assert.equal(result.candidates.username.some((candidate) => candidate.label.includes('搜索')), false)
})

test('duplicate login button names use a unique css path instead of an ambiguous role locator', () => {
  const result = analyzeLoginElements([
    {
      domIndex: 0,
      tagName: 'button',
      type: 'button',
      text: '登录',
      ariaLabel: '',
      label: '',
      formHasPassword: true,
      accessibleNameCount: 2,
      cssPath: 'main > form:nth-of-type(1) > button'
    },
    {
      domIndex: 1,
      tagName: 'button',
      type: 'button',
      text: '登录',
      ariaLabel: '',
      label: '',
      formHasPassword: false,
      accessibleNameCount: 2,
      cssPath: 'main > form:nth-of-type(2) > button'
    }
  ])

  assert.equal(result.recommended.submit.locator, 'css=main > form:nth-of-type(1) > button')
  assert.equal(result.candidates.submit[1].locator, 'css=main > form:nth-of-type(2) > button')
})

test('login discovery manager keeps async snapshots and confirms only returned candidates', () => {
  const manager = createLoginDiscoveryManager({ idFactory: () => 'discovery-1' })
  const job = manager.create({
    url: 'https://example.com/login',
    browser: 'chromium',
    username: 'must-not-be-stored',
    password: 'must-not-be-stored'
  })

  assert.equal(job.snapshot().status, 'queued')
  assert.equal(JSON.stringify(job.input()).includes('must-not-be-stored'), false)
  assert.deepEqual(job.snapshot().candidates, { username: [], password: [], submit: [] })
  assert.deepEqual(job.snapshot().recommended, {})
  assert.deepEqual(job.snapshot().diagnostics, [])
  job.markRunning()
  job.complete({
    pageUrl: 'https://example.com/login',
    screenshotUrl: 'http://127.0.0.1:9323/artifacts/login.png',
    ...analyzeLoginElements(elements),
    diagnostics: []
  })

  const completed = job.snapshot()
  assert.equal(completed.status, 'completed')
  assert.equal(completed.recommended.username.locator, 'css=#username')
  assert.throws(() => job.confirm({ usernameCandidateId: 'unknown' }), /candidate/i)

  const confirmed = job.confirm({
    usernameCandidateId: completed.recommended.username.id,
    passwordCandidateId: completed.recommended.password.id,
    submitCandidateId: completed.recommended.submit.id
  })
  assert.equal(confirmed.confirmed.usernameLocator, 'css=#username')
  assert.equal(confirmed.confirmed.passwordLocator, 'css=input[name="password"]')
  assert.equal(confirmed.confirmed.submitLocator, 'role=button[name="登录", exact=true]')
})

test('login discovery forwards local-network compatibility to the browser launcher', async () => {
  let received
  await assert.rejects(() => discoverLoginPage({ url: 'https://app-dev.example.test/#/login', browser: 'chromium' }, {
    discoveryId: 'local-network',
    artifactDir: os.tmpdir(),
    publicBaseUrl: 'http://127.0.0.1:19323',
    allowLocalNetworkAccess: true,
    launchBrowser: async (_browserId, options) => {
      received = options
      throw new Error('stop after launch options')
    }
  }), /stop after launch options/)
  assert.equal(received.allowLocalNetworkAccess, true)
})

test('real login discovery never returns prefilled account or password values', async (context) => {
  const artifactDir = await fs.mkdtemp(path.join(os.tmpdir(), 'login-discovery-'))
  const server = http.createServer((request, response) => {
    const variant = request.url?.includes('omega') ? 'OMEGA' : 'ALPHA'
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html>
      <label>账号 <input name="username" autocomplete="username" value="USER-${variant}"></label>
      <label>密码 <input name="password" type="password" value="SECRET-${variant}"></label>
      <input type="submit" value="登录">
    `)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(async () => {
    await new Promise((resolve) => server.close(resolve))
    await fs.rm(artifactDir, { recursive: true, force: true })
  })
  const address = server.address()
  assert.ok(address && typeof address !== 'string')

  const discoverVariant = (variant) => discoverLoginPage({
      url: `http://127.0.0.1:${address.port}/${variant.toLowerCase()}`,
      browser: 'chromium',
      viewport: '900x600',
      headless: true
    }, {
      discoveryId: `privacy-${variant.toLowerCase()}`,
      artifactDir,
      publicBaseUrl: 'http://127.0.0.1:9323',
      launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions)
    })
  const alpha = await discoverVariant('ALPHA')
  const omega = await discoverVariant('OMEGA')

  const serialized = JSON.stringify({ alpha, omega })
  assert.equal(serialized.includes('USER-ALPHA'), false)
  assert.equal(serialized.includes('SECRET-ALPHA'), false)
  assert.equal(serialized.includes('USER-OMEGA'), false)
  assert.equal(serialized.includes('SECRET-OMEGA'), false)
  assert.equal(alpha.recommended.submit.locator, 'role=button[name="登录", exact=true]')

  const alphaScreenshot = await fs.readFile(path.join(artifactDir, path.basename(new URL(alpha.screenshotUrl).pathname)))
  const omegaScreenshot = await fs.readFile(path.join(artifactDir, path.basename(new URL(omega.screenshotUrl).pathname)))
  assert.deepEqual(alphaScreenshot, omegaScreenshot)
})

test('real login discovery produces an executable unique locator for duplicate login buttons', async (context) => {
  const artifactDir = await fs.mkdtemp(path.join(os.tmpdir(), 'login-discovery-duplicate-'))
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html>
      <main>
        <form>
          <input name="username" autocomplete="username">
          <input name="password" type="password">
          <div><div><div><div>
            <button type="button" onclick="document.body.dataset.clicked='password'">登录</button>
          </div></div></div></div>
        </form>
        <form>
          <input name="mobile" placeholder="手机号">
          <input name="smsCode" placeholder="短信验证码">
          <div><div><div><div>
            <button type="button" onclick="document.body.dataset.clicked='sms'">登录</button>
          </div></div></div></div>
        </form>
      </main>
    `)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(async () => {
    await new Promise((resolve) => server.close(resolve))
    await fs.rm(artifactDir, { recursive: true, force: true })
  })
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const url = `http://127.0.0.1:${address.port}/login`

  const result = await discoverLoginPage({
    url,
    browser: 'chromium',
    viewport: '900x600',
    headless: true
  }, {
    discoveryId: 'duplicate-login-buttons',
    artifactDir,
    publicBaseUrl: 'http://127.0.0.1:9323',
    launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions)
  })

  assert.match(result.recommended.submit.locator, /^css=/)
  assert.match(result.recommended.submit.reason, /同名按钮/)
  assert.notEqual(result.candidates.submit[0].locator, result.candidates.submit[1].locator)

  const browser = await chromium.launch({ headless: true })
  const page = await browser.newPage()
  try {
    await page.goto(url)
    const locator = page.locator(result.recommended.submit.locator.slice(4))
    assert.equal(await locator.count(), 1)
    await locator.click()
    assert.equal(await page.evaluate(() => document.body.dataset.clicked), 'password')
  } finally {
    await browser.close()
  }
})
