import test from 'node:test'
import assert from 'node:assert/strict'
import {
  classifyRequestCriticality,
  createPageNetworkPolicy,
  isAllowedRequestUrl,
  normalizeAllowedOrigins,
  normalizeOrigin,
  sanitizeBlockedDiagnostic,
  sanitizeBlockedRequestDiagnostic
} from './page-network-policy.mjs'

test('normalizeOrigin only accepts clean http and https origins', () => {
  assert.equal(normalizeOrigin('https://example.com'), 'https://example.com')
  assert.equal(normalizeOrigin('HTTP://Example.com'), 'http://example.com')
  assert.equal(normalizeOrigin('https://user:pass@example.com'), '')
  assert.equal(normalizeOrigin('https://example.com/path'), '')
  assert.equal(normalizeOrigin('https://example.com?token=1'), '')
  assert.equal(normalizeOrigin('https://example.com/#frag'), '')
  assert.equal(normalizeOrigin('ftp://example.com'), '')
})

test('normalizeAllowedOrigins filters invalid and duplicate origins', () => {
  assert.deepEqual(normalizeAllowedOrigins([
    'https://example.com',
    'https://example.com/',
    'https://user:pass@example.com',
    'https://example.com/path',
    'http://api.example.com'
  ]), ['https://example.com', 'http://api.example.com'])
})

test('isAllowedRequestUrl allows only page resources by origin and exact request URLs', () => {
  const policy = {
    pageOrigins: ['https://app.example.com'],
    authOrigins: ['https://api.example.com'],
    allowedRequestUrls: ['https://api.example.com/auth/login']
  }

  assert.equal(isAllowedRequestUrl('https://app.example.com/', policy, 'document'), true)
  assert.equal(isAllowedRequestUrl('https://app.example.com/assets/app.js', policy, 'script'), true)
  assert.equal(isAllowedRequestUrl('https://app.example.com/assets/app.css', policy, 'stylesheet'), true)
  assert.equal(isAllowedRequestUrl('https://app.example.com/api/session', policy, 'xhr'), false)
  assert.equal(isAllowedRequestUrl('https://app.example.com/api/session', policy, 'fetch'), false)
  assert.equal(isAllowedRequestUrl('https://api.example.com/auth/login', policy, 'xhr'), true)
  assert.equal(isAllowedRequestUrl('https://api.example.com/auth/login/', policy, 'fetch'), true)
  assert.equal(isAllowedRequestUrl('https://api.example.com/auth/logout', policy, 'xhr'), false)
  assert.equal(isAllowedRequestUrl('https://api.example.com/admin', policy, 'xhr'), false)
  assert.equal(isAllowedRequestUrl('https://sibling.example.com/auth/login', policy, 'xhr'), false)
  assert.equal(isAllowedRequestUrl('https://api.example.com/auth/login?token=secret', policy, 'xhr'), false)
})

test('isAllowedRequestUrl requires exact query match and strips fragments only', () => {
  const policy = {
    allowedRequestUrls: ['https://api.example.com/auth/login/?token=a']
  }

  assert.equal(isAllowedRequestUrl('https://api.example.com/auth/login?token=a#frag', policy), true)
  assert.equal(isAllowedRequestUrl('https://api.example.com/auth/login/?token=a', policy), true)
  assert.equal(isAllowedRequestUrl('https://api.example.com/auth/login?token=b', policy), false)
})

test('isAllowedRequestUrl allows path-only matches only on configured page or auth origins', () => {
  const policy = {
    pageOrigins: ['https://app.example.com'],
    authOrigins: ['https://api.example.com'],
    allowedRequestPaths: ['/auth/login']
  }

  assert.equal(isAllowedRequestUrl('https://app.example.com/auth/login', policy, 'xhr'), true)
  assert.equal(isAllowedRequestUrl('https://api.example.com/auth/login?token=a', policy, 'fetch'), true)
  assert.equal(isAllowedRequestUrl('https://sibling.example.com/auth/login', policy, 'xhr'), false)
  assert.equal(isAllowedRequestUrl('https://sibling.example.com/auth/login?token=a', policy, 'fetch'), false)
})

test('explicit request origins allow page read traffic without allowing arbitrary mutations', () => {
  const policy = {
    pageOrigins: ['https://app.example.com'],
    allowedRequestOrigins: ['https://api.example.com']
  }
  assert.equal(isAllowedRequestUrl('https://api.example.com/api/v3/user', policy, 'xhr', 'GET'), true)
  assert.equal(isAllowedRequestUrl('https://api.example.com/api/v3/user', policy, 'xhr', 'OPTIONS'), true)
  assert.equal(isAllowedRequestUrl('https://api.example.com/api/v3/user', policy, 'xhr', 'POST'), false)
})

test('createPageNetworkPolicy keeps auth origins exact-path only while allowing page origins broadly', async () => {
  const policy = createPageNetworkPolicy({
    pageOrigins: ['https://app.example.com'],
    authOrigins: ['https://api.example.com'],
    allowedRequestPaths: ['/auth/login']
  })

  assert.equal(policy.isAllowedRequestUrl('https://app.example.com/assets/app.js', 'script'), true)
  assert.equal(policy.isAllowedRequestUrl('https://app.example.com/api/session', 'xhr'), false)
  assert.equal(policy.isAllowedRequestUrl('https://api.example.com/auth/login', 'xhr'), true)
  assert.equal(policy.isAllowedRequestUrl('https://api.example.com/auth/login/', 'fetch'), true)
  assert.equal(policy.isAllowedRequestUrl('https://api.example.com/auth/login?token=a', 'xhr'), true)
  assert.equal(policy.isAllowedRequestUrl('https://api.example.com/admin', 'xhr'), false)
  assert.equal(policy.isAllowedRequestUrl('https://sibling.example.com/auth/login', 'xhr'), false)

  const blocked = []
  const calls = []
  await policy.apply({
    route: async (_pattern, handler) => {
      const makeRoute = (url) => ({
        request: () => ({ url: () => url, resourceType: () => (url.includes('/assets/') ? 'script' : 'xhr') }),
        continue: async () => calls.push(['continue', url]),
        abort: async (reason) => calls.push(['abort', url, reason])
      })
      await handler(makeRoute('https://api.example.com/auth/login'))
      await handler(makeRoute('https://app.example.com/assets/app.js'))
      await handler(makeRoute('https://api.example.com/admin'))
      await handler(makeRoute('https://app.example.com/api/session'))
    }
  }, {}, (entry) => blocked.push(entry))

  assert.deepEqual(calls, [
    ['continue', 'https://api.example.com/auth/login'],
    ['continue', 'https://app.example.com/assets/app.js'],
    ['abort', 'https://api.example.com/admin', 'blockedbyclient'],
    ['abort', 'https://app.example.com/api/session', 'blockedbyclient']
  ])
  assert.equal(blocked[0].path, '/admin')
  assert.equal(blocked[0].failureType, 'network_blocked')
  assert.equal(blocked[0].origin, 'https://api.example.com')
  assert.equal(blocked[0].url, undefined)
  assert.equal(blocked[0].detail, undefined)
})

test('createPageNetworkPolicy records sanitized blocked reasons when assertAllowedUrl rejects', async () => {
  const policy = createPageNetworkPolicy({
    pageOrigins: ['https://app.example.com'],
    authOrigins: ['https://api.example.com'],
    allowedRequestPaths: ['/auth/login']
  })

  const blocked = []
  await policy.apply({
    route: async (_pattern, handler) => {
      await handler({
        request: () => ({
          url: () => 'https://api.example.com/admin?token=secret',
          resourceType: () => 'xhr'
        }),
        continue: async () => {},
        abort: async () => {}
      })
    }
  }, {}, (entry) => blocked.push(entry), async () => {
    throw new Error('origin/path not allowed: https://api.example.com/admin?token=secret locator=css=#password password=secret query=secret')
  })

  assert.equal(blocked.length, 1)
  assert.equal(blocked[0].failureType, 'network_blocked')
  assert.equal(blocked[0].origin, 'https://api.example.com')
  assert.equal(blocked[0].path, '/admin')
  assert.equal(typeof blocked[0].detail, 'string')
  assert.match(blocked[0].detail, /origin\/path not allowed/i)
  assert.equal(/[?#]/.test(blocked[0].detail), false)
  assert.equal(/token|password|locator|query|secret/i.test(blocked[0].detail), false)
  assert.equal(blocked[0].detail.length <= 160, true)
})

test('createPageNetworkPolicy requires auth origin membership for auth dependencies', () => {
  const policy = createPageNetworkPolicy({
    pageOrigins: ['https://app.example.com'],
    authOrigins: ['https://api.example.com'],
    allowedRequestUrls: ['https://api.example.com/auth/login?token=a']
  })

  assert.equal(policy.classifyRequestCriticality({
    url: 'https://api.example.com/auth/login?token=a',
    resourceType: 'xhr'
  }), 'auth_dependency')
  assert.equal(policy.classifyRequestCriticality({
    url: 'https://api.example.com/auth/login?token=b',
    resourceType: 'xhr'
  }), 'unknown')
  assert.equal(policy.classifyRequestCriticality({
    url: 'https://api.example.com/admin',
    resourceType: 'xhr'
  }), 'unknown')
  assert.equal(policy.classifyRequestCriticality({
    url: 'https://app.example.com/auth/login?token=a',
    resourceType: 'xhr'
  }), 'unknown')
})

test('createPageNetworkPolicy allows auth dependency when auth origins are unspecified', () => {
  const policy = createPageNetworkPolicy({
    allowedRequestUrls: ['https://api.example.com/auth/login?token=a']
  })

  assert.equal(policy.classifyRequestCriticality({
    url: 'https://api.example.com/auth/login?token=a',
    resourceType: 'xhr'
  }), 'auth_dependency')
})

test('classifyRequestCriticality separates auth dependency and business api signals', () => {
  const context = {
    targetUrl: 'https://app.example.com/#/login',
    authOrigins: ['https://api.example.com'],
    allowedRequestUrls: ['https://api.example.com/auth/login'],
    observedBusinessSignals: new Set(['/api/v1/profile'])
  }

  assert.equal(classifyRequestCriticality({
    url: 'https://api.example.com/auth/login',
    resourceType: 'xhr'
  }, context), 'auth_dependency')
  assert.equal(classifyRequestCriticality({
    url: 'https://app.example.com/api/v1/profile',
    resourceType: 'fetch'
  }, context), 'critical')
  assert.equal(classifyRequestCriticality({
    url: 'https://cdn.example.com/logo.svg',
    resourceType: 'image'
  }, context), 'optional')
})

test('sanitizeBlockedDiagnostic removes credentials and query strings', () => {
  const safe = sanitizeBlockedDiagnostic({
    url: 'https://user:secret@example.com/api/list?token=secret#frag',
    origin: 'https://example.com',
    path: '/api/list?token=secret',
    resourceType: 'xhr',
    criticality: 'critical',
    failureType: 'network_blocked',
    detail: `Authorization=Bearer secret cookie=session=abc responseBody={"token":"secret"} locator=css=#password password=secret query=secret ${'x'.repeat(240)}`
  })
  const { detail, ...rest } = safe
  assert.deepEqual(rest, {
    url: 'https://example.com/api/list',
    origin: 'https://example.com',
    path: '/api/list',
    resourceType: 'xhr',
    criticality: 'critical',
    failureType: 'network_blocked'
  })
  assert.match(detail, /Authorization=\[redacted\] cookie=\[redacted\] responseBody=\[redacted\]/)
  assert.equal(/token|password|locator|query|secret/i.test(detail), false)
  assert.equal(detail.length <= 160, true)
})

test('blocklist mode only blocks listed origins or exact URLs and lets everything else through', async () => {
  const policy = createPageNetworkPolicy({
    pageOrigins: ['https://app.example.com'],
    allowedRequestOrigins: ['https://ads.example.com', 'https://api.example.com/internal/write'],
    networkPolicyMode: 'blocklist'
  })
  assert.equal(policy.networkPolicyMode, 'blocklist')
  assert.equal(policy.isUserBlockedUrl('https://ads.example.com/any/path.js'), true)
  assert.equal(policy.isUserBlockedUrl('https://api.example.com/internal/write'), true)
  assert.equal(policy.isUserBlockedUrl('https://api.example.com/internal/write/more'), false)
  assert.equal(policy.isUserBlockedUrl('https://third.party.example/logo.png'), false)

  const calls = []
  const blocked = []
  await policy.apply({
    route: async (_pattern, handler) => {
      const makeRoute = (url) => ({
        request: () => ({ url: () => url, resourceType: () => 'xhr', method: () => 'POST' }),
        continue: async () => calls.push(['continue', url]),
        abort: async (reason) => calls.push(['abort', url, reason])
      })
      await handler(makeRoute('https://api.example.com/auth/login'))
      await handler(makeRoute('https://ads.example.com/track.js'))
      await handler(makeRoute('https://api.example.com/internal/write'))
      await handler(makeRoute('https://api.example.com/internal/write/more'))
    }
  }, {}, (entry) => blocked.push(entry))

  assert.deepEqual(calls, [
    ['continue', 'https://api.example.com/auth/login'],
    ['abort', 'https://ads.example.com/track.js', 'blockedbyclient'],
    ['abort', 'https://api.example.com/internal/write', 'blockedbyclient'],
    ['continue', 'https://api.example.com/internal/write/more']
  ])
  assert.equal(blocked.length, 2)
  assert.equal(blocked[0].origin, 'https://ads.example.com')
  assert.equal(blocked[1].path, '/internal/write')
  assert.equal(blocked.every((entry) => entry.failureType === 'network_blocked'), true)
})

test('blocklist mode with an empty list lets all requests through (default posture)', async () => {
  const policy = createPageNetworkPolicy({ pageOrigins: ['https://app.example.com'], networkPolicyMode: 'blocklist' })
  const calls = []
  await policy.apply({
    route: async (_pattern, handler) => {
      await handler({
        request: () => ({ url: () => 'https://third.party.example/cdn/logo.png', resourceType: () => 'image', method: () => 'GET' }),
        continue: async () => calls.push('continue'),
        abort: async () => calls.push('abort')
      })
    }
  }, {}, () => {})
  assert.deepEqual(calls, ['continue'])
})

test('absent mode keeps the strict allowlist posture', async () => {
  const policy = createPageNetworkPolicy({ pageOrigins: ['https://app.example.com'] })
  assert.equal(policy.networkPolicyMode, 'allowlist')
  const calls = []
  await policy.apply({
    route: async (_pattern, handler) => {
      await handler({
        request: () => ({ url: () => 'https://third.party.example/cdn/logo.png', resourceType: () => 'image', method: () => 'GET' }),
        continue: async () => calls.push('continue'),
        abort: async () => calls.push('abort')
      })
    }
  }, {}, () => {})
  assert.deepEqual(calls, ['abort'])
})

test('sanitizeBlockedRequestDiagnostic keeps only redacted blocked request fields', () => {
  assert.deepEqual(sanitizeBlockedRequestDiagnostic({
    url: 'https://user:secret@example.com/api/list?token=secret#frag',
    origin: 'https://example.com',
    path: '/api/list?token=secret',
    resourceType: 'xhr',
    criticality: 'critical',
    failureType: 'network_blocked',
    detail: 'should-not-appear'
  }), {
    origin: 'https://example.com',
    path: '/api/list',
    resourceType: 'xhr',
    criticality: 'critical',
    failureType: 'network_blocked'
  })
})
