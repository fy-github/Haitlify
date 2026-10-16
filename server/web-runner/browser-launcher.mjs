export const SUPPORTED_BROWSERS = ['chromium', 'chrome', 'msedge', 'firefox', 'webkit']

const browserLabels = {
  chromium: 'Chromium',
  chrome: 'Google Chrome',
  msedge: 'Microsoft Edge',
  firefox: 'Firefox',
  webkit: 'WebKit'
}

const LOCAL_NETWORK_ACCESS_FLAG = '--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessRespectPreflightResults,BlockInsecurePrivateNetworkRequests'

export const normalizeBrowserId = (value) => {
  const browserId = String(value || '').trim().toLowerCase() || 'chromium'
  if (!SUPPORTED_BROWSERS.includes(browserId)) {
    throw new Error(`Unsupported browser: ${browserId}`)
  }
  return browserId
}

export const launchSelectedBrowser = (value, options = {}, engines = {}) => {
  const browserId = normalizeBrowserId(value)
  const { allowLocalNetworkAccess, ...launchOptions } = options
  if (browserId === 'firefox') {
    return engines.firefox.launch(launchOptions)
  }
  if (browserId === 'webkit') {
    return engines.webkit.launch(launchOptions)
  }
  const chromiumOptions = allowLocalNetworkAccess
    ? { ...launchOptions, args: [...(launchOptions.args || []), LOCAL_NETWORK_ACCESS_FLAG] }
    : launchOptions
  if (browserId === 'chrome' || browserId === 'msedge') {
    return engines.chromium.launch({ ...chromiumOptions, channel: browserId })
  }
  return engines.chromium.launch(chromiumOptions)
}

export const probeBrowserCapabilities = async (options = {}, engines = {}) => {
  const browsers = []
  for (const browserId of SUPPORTED_BROWSERS) {
    try {
      const browser = await launchSelectedBrowser(browserId, options, engines)
      await browser.close()
      browsers.push({
        id: browserId,
        label: browserLabels[browserId],
        available: true,
        recommendation: ''
      })
    } catch (error) {
      browsers.push({
        id: browserId,
        label: browserLabels[browserId],
        available: false,
        message: error instanceof Error ? error.message : String(error),
        recommendation: browserRecommendation(browserId)
      })
    }
  }
  return {
    status: browsers.some((item) => item.available) ? 'UP' : 'DEGRADED',
    browsers
  }
}

const browserRecommendation = (browserId) => {
  if (browserId === 'chrome') {
    return '请先安装 Google Chrome，或选择 Chromium。'
  }
  if (browserId === 'msedge') {
    return '请先安装 Microsoft Edge，或选择 Chromium。'
  }
  return `在 server/web-runner 目录执行: npx playwright install ${browserId}`
}
