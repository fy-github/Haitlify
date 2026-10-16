import test from 'node:test'
import assert from 'node:assert/strict'
import {
  launchSelectedBrowser,
  normalizeBrowserId,
  probeBrowserCapabilities
} from './browser-launcher.mjs'

const createBrowser = (id, calls) => ({
  close: async () => calls.push(['close', id])
})

test('launchSelectedBrowser launches the exact Playwright engine and channel', async () => {
  const calls = []
  const engines = {
    chromium: {
      launch: async (options) => {
        calls.push(['launch', 'chromium', options])
        return createBrowser('chromium', calls)
      }
    },
    firefox: {
      launch: async (options) => {
        calls.push(['launch', 'firefox', options])
        return createBrowser('firefox', calls)
      }
    },
    webkit: {
      launch: async (options) => {
        calls.push(['launch', 'webkit', options])
        return createBrowser('webkit', calls)
      }
    }
  }

  await launchSelectedBrowser('chromium', { headless: true }, engines)
  await launchSelectedBrowser('chrome', { headless: true }, engines)
  await launchSelectedBrowser('msedge', { headless: false }, engines)
  await launchSelectedBrowser('firefox', { headless: true }, engines)
  await launchSelectedBrowser('webkit', { headless: true }, engines)

  assert.deepEqual(calls, [
    ['launch', 'chromium', { headless: true }],
    ['launch', 'chromium', { headless: true, channel: 'chrome' }],
    ['launch', 'chromium', { headless: false, channel: 'msedge' }],
    ['launch', 'firefox', { headless: true }],
    ['launch', 'webkit', { headless: true }]
  ])
})

test('launchSelectedBrowser rejects unknown browsers instead of falling back', async () => {
  assert.throws(
    () => launchSelectedBrowser('safari', {}, {}),
    /Unsupported browser: safari/
  )
})

test('launchSelectedBrowser enables Chromium local-network access only when explicitly requested', async () => {
  const calls = []
  const engines = {
    chromium: {
      launch: async (options) => {
        calls.push(['chromium', options])
        return createBrowser('chromium', [])
      }
    },
    firefox: {
      launch: async (options) => {
        calls.push(['firefox', options])
        return createBrowser('firefox', [])
      }
    }
  }

  await launchSelectedBrowser('chromium', {
    headless: true,
    args: ['--existing-flag'],
    allowLocalNetworkAccess: true
  }, engines)
  await launchSelectedBrowser('firefox', {
    headless: true,
    allowLocalNetworkAccess: true
  }, engines)

  assert.deepEqual(calls, [
    ['chromium', {
      headless: true,
      args: [
        '--existing-flag',
        '--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessRespectPreflightResults,BlockInsecurePrivateNetworkRequests'
      ]
    }],
    ['firefox', { headless: true }]
  ])
})

test('normalizeBrowserId keeps supported values and falls back only for empty defaults', () => {
  assert.equal(normalizeBrowserId('chrome'), 'chrome')
  assert.equal(normalizeBrowserId('msedge'), 'msedge')
  assert.equal(normalizeBrowserId('firefox'), 'firefox')
  assert.equal(normalizeBrowserId(''), 'chromium')
  assert.throws(() => normalizeBrowserId('follow-case'), /Unsupported browser: follow-case/)
})

test('probeBrowserCapabilities checks every browser independently', async () => {
  const calls = []
  const engines = {
    chromium: {
      launch: async (options = {}) => {
        calls.push(['launch', 'chromium', options])
        if (options.channel) throw new Error(`${options.channel} is not installed`)
        return createBrowser('chromium', calls)
      }
    },
    firefox: {
      launch: async (options) => {
        calls.push(['launch', 'firefox', options])
        return createBrowser('firefox', calls)
      }
    },
    webkit: {
      launch: async () => {
        throw new Error('Executable does not exist for webkit')
      }
    }
  }

  const result = await probeBrowserCapabilities({ headless: true }, engines)

  assert.deepEqual(result.browsers.map((item) => ({
    id: item.id,
    available: item.available
  })), [
    { id: 'chromium', available: true },
    { id: 'chrome', available: false },
    { id: 'msedge', available: false },
    { id: 'firefox', available: true },
    { id: 'webkit', available: false }
  ])
  assert.match(result.browsers.find((item) => item.id === 'webkit').recommendation, /playwright install webkit/)
  assert.match(result.browsers.find((item) => item.id === 'chrome').recommendation, /Google Chrome/)
  assert.match(result.browsers.find((item) => item.id === 'msedge').recommendation, /Microsoft Edge/)
  assert.equal(calls.filter(([action]) => action === 'close').length, 2)
})
