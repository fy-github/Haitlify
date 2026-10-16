import test from 'node:test'
import assert from 'node:assert/strict'
import {
  createSession,
  createHandoff,
  destroyHandoff,
  destroyHandoffSession,
  destroySession,
  getHandoff,
  getSession,
  handoffCount,
  handoffState,
  listSessions,
  sessionState,
  sessionCount,
  touchSession,
  acquireSessionLease,
  releaseSessionLease
} from './session-manager.mjs'

test('session manager isolates sessions by environment and expires them', () => {
  const context = { close: async () => {} }
  const session = createSession({ projectId: 1, environmentId: 'env-1', browser: 'chromium', context, page: {} }, 20)

  assert.equal(getSession(session.sessionId, { projectId: 1, environmentId: 'env-1', browser: 'chromium' })?.sessionId, session.sessionId)
  assert.equal(touchSession(session.sessionId, { projectId: 1, environmentId: 'env-1', browser: 'chromium' }, 20), true)
  assert.equal(getSession(session.sessionId, { projectId: 2, environmentId: 'env-1', browser: 'chromium' }), undefined)
  assert.equal(sessionState(session.sessionId, { projectId: 1, environmentId: 'env-1', browser: 'chromium' }), 'active')
  assert.equal(sessionCount(), 1)
  destroySession(session.sessionId)
  assert.equal(sessionCount(), 0)
})

test('session manager never exposes expired sessions', async () => {
  const context = { close: async () => {} }
  const session = createSession({ projectId: 1, environmentId: 'env-expired', browser: 'chromium', context, page: {} }, 1)
  await new Promise((resolve) => setTimeout(resolve, 5))
  assert.equal(getSession(session.sessionId, { projectId: 1, environmentId: 'env-expired', browser: 'chromium' }), undefined)
})

test('session manager requires project and environment when a session key is supplied', () => {
  const session = createSession({
    projectId: 1,
    environmentId: 'env-isolated',
    browser: 'chromium',
    sessionKey: 'shared-key',
    context: { close: async () => {} },
    page: {}
  }, 1_000)

  assert.equal(getSession(session.sessionId, {
    projectId: 2,
    environmentId: 'env-isolated',
    sessionKey: 'shared-key',
    browser: 'chromium'
  }), undefined)
  assert.equal(getSession(session.sessionId, {
    projectId: 1,
    environmentId: 'env-isolated',
    sessionKey: 'shared-key',
    browser: 'chromium'
  })?.sessionId, session.sessionId)
  destroySession(session.sessionId)
})

test('session manager serializes access with a lease', () => {
  const session = createSession({
    projectId: 1,
    environmentId: 'env-lease',
    browser: 'chromium',
    context: { close: async () => {} },
    page: {}
  }, 1_000)
  const key = { projectId: 1, environmentId: 'env-lease', browser: 'chromium' }
  const first = acquireSessionLease(session.sessionId, key, 'op-1')
  const second = acquireSessionLease(session.sessionId, key, 'op-2')
  assert.equal(first.ok, true)
  assert.deepEqual(second, { ok: false, reason: 'busy' })
  assert.equal(releaseSessionLease(session.sessionId, 'op-1'), true)
  assert.equal(acquireSessionLease(session.sessionId, key, 'op-2').ok, true)
  releaseSessionLease(session.sessionId, 'op-2')
  destroySession(session.sessionId)
})

test('session manager returns a stable session_busy reason while leased', () => {
  const session = createSession({
    projectId: 2,
    environmentId: 'env-busy',
    browser: 'chromium',
    context: { close: async () => {} },
    page: {}
  }, 1_000)
  const key = { projectId: 2, environmentId: 'env-busy', browser: 'chromium' }
  assert.equal(acquireSessionLease(session.sessionId, key, 'owner-a').ok, true)
  assert.deepEqual(acquireSessionLease(session.sessionId, key, 'owner-b'), { ok: false, reason: 'busy' })
  releaseSessionLease(session.sessionId, 'owner-a')
  destroySession(session.sessionId)
})

test('handoff manager validates key, expires and destroys paired sessions', () => {
  const context = { close: async () => {} }
  const session = createSession({ projectId: 1, environmentId: 'env-handoff', browser: 'chromium', context, page: {} }, 1_000)
  const handoff = createHandoff({
    sessionId: session.sessionId,
    projectId: 1,
    environmentId: 'env-handoff',
    browser: 'chromium',
    status: 'manual_required',
    runId: 'run-1',
    sourcePointId: 'point-1',
    pageKey: 'https://example.test/login#step-1',
    pageUrl: 'https://example.test/login?token=secret#step-1',
    pageFingerprint: 'fingerprint-1',
    authToken: 'token-secret',
    password: 'password-secret',
    extraHeaders: {
      Authorization: 'Bearer token-secret',
      Cookie: 'session=cookie-secret'
    },
    cookieOrigins: ['https://example.test'],
    input: { raw: 'should-not-persist' }
  }, 1_000)

  assert.equal(handoffCount(), 1)
  assert.deepEqual(Object.keys(handoff).sort(), [
    'browser',
    'createdAt',
    'environmentId',
    'expiresAt',
    'handoffId',
    'lastUsedAt',
    'pageFingerprint',
    'pageKey',
    'pageUrlPath',
    'projectId',
    'runId',
    'sessionId',
    'sourcePointId',
    'status'
  ])
  assert.equal(handoff.runId, 'run-1')
  assert.equal(handoff.sourcePointId, 'point-1')
  assert.equal(handoff.pageKey, 'https://example.test/login#step-1')
  assert.equal(handoff.pageUrlPath, '/login')
  assert.equal(handoff.pageFingerprint, 'fingerprint-1')
  assert.equal('input' in handoff, false)
  assert.equal(JSON.stringify(handoff).includes('secret'), false)
  assert.equal(JSON.stringify(handoff).includes('Authorization'), false)
  assert.equal(JSON.stringify(handoff).includes('Cookie'), false)
  assert.equal(handoffState(handoff.handoffId, { projectId: 1, environmentId: 'env-handoff', browser: 'chromium' }), 'active')
  assert.equal(handoffState(handoff.handoffId, {
    projectId: 1,
    environmentId: 'env-handoff',
    browser: 'chromium',
    pageKey: 'https://example.test/login#step-1',
    pageUrlPath: '/login',
    pageFingerprint: 'fingerprint-1'
  }), 'active')
  assert.equal(handoffState(handoff.handoffId, {
    projectId: 1,
    environmentId: 'env-handoff',
    browser: 'chromium',
    pageKey: 'https://example.test/login#step-1',
    pageUrlPath: '/other',
    pageFingerprint: 'fingerprint-1'
  }), 'mismatch')
  assert.equal(handoffState(handoff.handoffId, {
    projectId: 1,
    environmentId: 'env-handoff',
    browser: 'chromium',
    pageKey: 'https://example.test/login#step-1',
    pageUrlPath: '/login',
    pageFingerprint: 'fingerprint-2'
  }), 'mismatch')
  assert.equal(getHandoff(handoff.handoffId, { projectId: 1, environmentId: 'env-handoff', browser: 'chromium' })?.handoffId, handoff.handoffId)
  assert.equal(getHandoff(handoff.handoffId, {
    projectId: 1,
    environmentId: 'env-handoff',
    browser: 'chromium',
    pageKey: 'https://example.test/login#step-1',
    pageUrlPath: '/login',
    pageFingerprint: 'fingerprint-1'
  })?.handoffId, handoff.handoffId)
  assert.equal(getHandoff(handoff.handoffId, {
    projectId: 1,
    environmentId: 'env-handoff',
    browser: 'chromium',
    pageKey: 'https://example.test/login#step-1',
    pageUrlPath: '/login',
    pageFingerprint: 'fingerprint-2'
  }), undefined)
  assert.equal(getHandoff(handoff.handoffId, { projectId: 1, environmentId: 'env-handoff', browser: 'chromium' })?.input, undefined)
  assert.equal(getHandoff(handoff.handoffId, { projectId: 2, environmentId: 'env-handoff', browser: 'chromium' }), undefined)
  destroySession(session.sessionId)

  const secondSession = createSession({ projectId: 2, environmentId: 'env-handoff-2', browser: 'chromium', context, page: {} }, 1_000)
  const secondHandoff = createHandoff({ sessionId: secondSession.sessionId, projectId: 2, environmentId: 'env-handoff-2', browser: 'chromium', runId: 'run-2' }, 1)
  destroyHandoffSession(secondHandoff.handoffId)
  destroySession(secondSession.sessionId)
  assert.equal(handoffCount(), 0)
  assert.equal(sessionCount(), 0)
  destroyHandoff(handoff.handoffId)
})

test('handoff manager preserves hash routes while stripping query strings', () => {
  const context = { close: async () => {} }
  const session = createSession({ projectId: 3, environmentId: 'env-hash', browser: 'chromium', context, page: {} }, 1_000)
  const handoff = createHandoff({
    sessionId: session.sessionId,
    projectId: 3,
    environmentId: 'env-hash',
    browser: 'chromium',
    pageUrl: 'https://example.test/?from=menu#/logs?tab=active&token=secret'
  }, 1_000)

  assert.equal(handoff.pageUrlPath, '/#/logs')
  assert.notEqual(handoff.pageUrlPath, '/')
  destroyHandoffSession(handoff.handoffId)
})

test('listSessions exposes sanitized metadata of live sessions only', () => {
  const context = { close: async () => {} }
  const session = createSession({ projectId: 3, environmentId: 'env-list', browser: 'firefox', sessionKey: 'k', pageKey: 'http://example.com/x', context, page: {} }, 60_000)
  const expiring = createSession({ projectId: 4, environmentId: 'env-dead', browser: 'chromium', context, page: {} }, 1)

  const listed = listSessions(Date.now() + 5)
  assert.equal(listed.some((item) => item.sessionId === expiring.sessionId), false)
  const view = listed.find((item) => item.sessionId === session.sessionId)
  assert.equal(view.projectId, 3)
  assert.equal(view.environmentId, 'env-list')
  assert.equal(view.browser, 'firefox')
  assert.equal(view.leased, false)
  assert.equal(view.pageKey, 'http://example.com/x')
  // 字段白名单：只允许列表展示所需的元数据，page/context/browserInstance 绝不可外泄。
  assert.deepEqual(Object.keys(view).sort(), ['browser', 'createdAt', 'environmentId', 'expiresAt', 'lastUsedAt', 'leased', 'pageKey', 'projectId', 'sessionId', 'state'])

  assert.equal(acquireSessionLease(session.sessionId, { projectId: 3, environmentId: 'env-list', browser: 'firefox' }, 'op-list', Date.now()).ok, true)
  assert.equal(listSessions().find((item) => item.sessionId === session.sessionId)?.leased, true)
  releaseSessionLease(session.sessionId, 'op-list')

  destroySession(session.sessionId)
  destroySession(expiring.sessionId)
})

test('destroySession closes the browser even when the session only carries context/page handles', () => {
  // 用户核查（2026-10-14）：取证/接管路径创建的会话不记 browserInstance，销毁时只关了 page/context，
  // Chromium 进程常驻（实测 12 个会话被销毁、51 个浏览器实例零减少）。销毁必须沿 context/page 兜底关浏览器。
  const closed = { browser: 0, context: 0, page: 0, fallback: 0 }
  const browserHandle = { close: async () => { closed.browser += 1 } }
  const context = {
    browser: () => browserHandle,
    close: async () => { closed.context += 1 }
  }
  const page = {
    context: () => context,
    close: async () => { closed.page += 1 }
  }
  const session = createSession({ projectId: 1, environmentId: 'env-fallback', browser: 'chromium', context, page })
  destroySession(session.sessionId)
  assert.equal(closed.page, 1)
  assert.equal(closed.context, 1)
  assert.equal(closed.browser, 1)

  const secondClosed = { browser: 0 }
  const pageOnlyBrowser = { close: async () => { secondClosed.browser += 1 } }
  const pageOnlyContext = { close: async () => {} }
  const pageOnlyPage = { context: () => ({ browser: () => pageOnlyBrowser }), close: async () => {} }
  const second = createSession({ projectId: 1, environmentId: 'env-fallback-2', browser: 'chromium', context: pageOnlyContext, page: pageOnlyPage })
  destroySession(second.sessionId)
  assert.equal(secondClosed.browser, 1)

  const thirdClosed = { instance: 0, fallback: 0 }
  const instanceBrowser = { close: async () => { thirdClosed.instance += 1 } }
  const alsoAHandle = { close: async () => { thirdClosed.fallback += 1 } }
  const third = createSession({ projectId: 1, environmentId: 'env-fallback-3', browser: 'chromium', browserInstance: instanceBrowser, context: { browser: () => alsoAHandle, close: async () => {} }, page: {} })
  destroySession(third.sessionId)
  assert.equal(thirdClosed.instance, 1)
  assert.equal(thirdClosed.fallback, 0)
})
