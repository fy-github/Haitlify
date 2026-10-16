export const waitForUrlMatch = async (page, expectedUrl, options = {}) => {
  // 空期望值必须 fail-closed：之前 `if (!expected) return true` 会让 `waitForUrl` 立即“成功”，
  // 断言实际上什么都没验证（同一类问题在 assertUrl/assertText 的 includes('') 上也存在）。
  if (!String(expectedUrl || '').trim()) {
    throw Object.assign(new Error('waitForUrl failed: expected URL is empty'), { failureType: 'assertion_failed', category: 'assertion_failed' })
  }
  try {
    await page.waitForFunction(
      ({ expectedUrl }) => {
        const normalizeRoute = (url) => {
          const rawRoute = url.hash?.startsWith('#/')
            ? url.hash.slice(1)
            : url.pathname
          const queryIndex = rawRoute.search(/[?#]/)
          const route = queryIndex > -1 ? rawRoute.slice(0, queryIndex) : rawRoute
          return route.replace(/\/+$/, '') || '/'
        }
        const routeMatches = (currentRoute, expectedRoute) =>
          currentRoute === expectedRoute
          || (expectedRoute !== '/' && currentRoute.startsWith(`${expectedRoute}/`))
        const current = String(window.location.href || '').trim()
        const expected = String(expectedUrl || '').trim()
        if (!expected) return false
        try {
          const currentParsed = new URL(current)
          const expectedParsed = new URL(expected, currentParsed)
          return currentParsed.origin === expectedParsed.origin
            && routeMatches(normalizeRoute(currentParsed), normalizeRoute(expectedParsed))
        } catch {
          return current.includes(expected)
        }
      },
      { expectedUrl },
      options
    )
  } catch (error) {
    const message = error instanceof Error ? error.message : 'URL 等待超时'
    const currentUrl = typeof page.url === 'function' ? page.url() : ''
    const authRedirectHint = isLoginRedirect(currentUrl)
      ? '目标页面被登录页拦截：当前浏览器未通过认证或自动登录未生效，请检查 Web 运行环境的自动登录配置、账号密码、登录按钮定位器和登录成功判定目标。'
      : ''
    // 分类必须带上：否则抛出的是裸 Error，runner HTTP 层按「无分类」映射成 503 runner 不可用，
    // 平台侧把断言失败降级成基础设施异常。
    throw Object.assign(new Error([
      `waitForUrl failed: expected current URL to include "${expectedUrl}".`,
      currentUrl ? `current URL: "${currentUrl}".` : '',
      authRedirectHint,
      message
    ].filter(Boolean).join(' ')), { failureType: 'assertion_failed', category: 'assertion_failed' })
  }
}

export const urlMatchesExpected = (currentUrl, expectedUrl) => {
  const current = String(currentUrl || '').trim()
  const expected = String(expectedUrl || '').trim()
  // 同 waitForUrlMatch：空期望值不构成「匹配」，否则断言恒真。
  if (!expected) return false
  try {
    const currentParsed = new URL(current)
    const expectedParsed = new URL(expected, currentParsed)
    return currentParsed.origin === expectedParsed.origin
      && routeMatches(normalizeRoute(currentParsed), normalizeRoute(expectedParsed))
  } catch {
    return current.includes(expected)
  }
}

export const resolveLocator = (page, target) => {
  // Supports frame scoping (`frame=#frame >> ...`) and locator chaining (` >> `), which is how
  // step evidence binds targets inside iframes and open shadow roots. Plain locators are unchanged.
  const rawTarget = String(target || '').trim()
  const { root, rest } = resolveFrameScopedRoot(page, rawTarget)
  const segments = splitLocatorChain(rest)
  const tail = segments.length ? segments.pop() : rest
  let base = root
  for (const segment of segments) base = base.locator(segment)
  return resolveSingleLocator(base, tail)
}

const resolveFrameScopedRoot = (page, value) => {
  let root = page
  let rest = value
  let guard = 0
  while (guard < 5) {
    const match = rest.match(/^frame=([\s\S]+?)\s*>>\s*([\s\S]+)$/)
    if (!match) break
    root = root.frameLocator(stripEnginePrefix(match[1]))
    rest = match[2].trim()
    guard += 1
  }
  return { root, rest }
}

const stripEnginePrefix = (value) => {
  const match = String(value || '').trim().match(/^([a-zA-Z][\w-]*)=([\s\S]*)$/)
  return match && match[1].toLowerCase() === 'css' ? match[2].trim() : String(value || '').trim()
}

const splitLocatorChain = (value) => {
  const segments = []
  let current = ''
  let depth = 0
  let quote = ''
  const text = String(value || '')
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index]
    if (quote) {
      if (char === quote && text[index - 1] !== '\\') quote = ''
      current += char
      continue
    }
    if (char === '"' || char === "'") {
      quote = char
      current += char
      continue
    }
    if (char === '[' || char === '(') depth += 1
    if (char === ']' || char === ')') depth = Math.max(0, depth - 1)
    if (depth === 0 && char === '>' && text[index + 1] === '>' && /\s/.test(text[index - 1] || ' ') && /\s/.test(text[index + 2] || ' ')) {
      segments.push(current.trim())
      current = ''
      index += 1
      continue
    }
    current += char
  }
  if (current.trim()) segments.push(current.trim())
  return segments.filter(Boolean)
}

const resolveSingleLocator = (page, target) => {
  const locatorTarget = String(target || '').trim()
  const locatorPrefix = locatorTarget.match(/^([a-zA-Z][\w-]*)=(.*)$/)
  const prefix = locatorPrefix?.[1]?.toLowerCase()
  const value = locatorPrefix?.[2]
  if (prefix === 'css') return page.locator(value)
  if (prefix === 'xpath') return page.locator(`xpath=${value}`)
  if (prefix === 'text') {
    // text=…, exact=true：采集器按精确文本证明唯一性，执行时必须用同样的匹配口径。
    const parsed = splitLocatorExactSuffix(value)
    return parsed.exact === undefined ? page.getByText(parsed.value) : page.getByText(parsed.value, { exact: parsed.exact })
  }
  if (prefix === 'testid') return page.getByTestId(value)
  if (prefix === 'role') {
    const parsed = parseRoleLocator(locatorTarget)
    return page.getByRole(parsed.role, parsed.options)
  }
  if (isBareXPath(locatorTarget)) return page.locator(`xpath=${locatorTarget}`)
  return page.locator(locatorTarget)
}

export const splitLocatorExactSuffix = (value) => {
  const raw = String(value || '')
  const match = raw.match(/^(.*),\s*exact=(true|false)$/i)
  return match ? { value: match[1], exact: match[2].toLowerCase() === 'true' } : { value: raw, exact: undefined }
}

const isBareXPath = (value) =>
  value.startsWith('//')
  || value.startsWith('.//')
  || value.startsWith('..//')
  || value.startsWith('(//')

const normalizeRoute = (url) => {
  const rawRoute = url.hash?.startsWith('#/')
    ? url.hash.slice(1)
    : url.pathname
  const queryIndex = rawRoute.search(/[?#]/)
  const route = queryIndex > -1 ? rawRoute.slice(0, queryIndex) : rawRoute
  return route.replace(/\/+$/, '') || '/'
}

const routeMatches = (currentRoute, expectedRoute) =>
  currentRoute === expectedRoute
  || (expectedRoute !== '/' && currentRoute.startsWith(`${expectedRoute}/`))

const isLoginRedirect = (currentUrl) => {
  if (!currentUrl) return false
  try {
    const parsed = new URL(currentUrl)
    const route = parsed.hash?.startsWith('#/')
      ? parsed.hash.slice(1)
      : parsed.pathname
    return /^\/login(?:[/?#]|$)/i.test(route)
      || /[?&]from=/.test(parsed.hash || parsed.search)
  } catch {
    return /(?:^|[#/])login(?:[/?#]|$)/i.test(String(currentUrl))
  }
}

const parseRoleLocator = (value) => {
  const match = value.match(/^role=([^\[\s]+)(?:\[name=(.+?)(?:,\s*exact=(true|false))?\])?$/i)
  if (!match) return { role: value.slice(value.indexOf('=') + 1), options: {} }
  return {
    role: match[1],
    options: match[2]
      ? { name: stripQuotes(match[2].trim()), ...(match[3] ? { exact: match[3].toLowerCase() === 'true' } : {}) }
      : {}
  }
}

const stripQuotes = (value) =>
  value.startsWith('"') && value.endsWith('"')
    ? value.slice(1, -1)
    : value.startsWith("'") && value.endsWith("'")
      ? value.slice(1, -1)
      : value
