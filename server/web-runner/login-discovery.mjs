import path from 'node:path'

const USERNAME_TERMS = /(?:user(?:name)?|account|email|mobile|phone|login|用户名|用户|账号|帐户|邮箱|手机|手机号)/i
const PASSWORD_TERMS = /(?:pass(?:word)?|pwd|密码|口令)/i
const SUBMIT_TERMS = /(?:sign\s*in|log\s*in|login|continue|submit|登录|登陆|进入系统|立即进入)/i
const SEARCH_TERMS = /(?:search|keyword|query|搜索|查询|关键字)/i

export const analyzeLoginElements = (elements = []) => {
  const candidates = {
    username: rankCandidates(elements, 'username'),
    password: rankCandidates(elements, 'password'),
    submit: rankCandidates(elements, 'submit')
  }
  return {
    candidates,
    recommended: {
      username: candidates.username[0],
      password: candidates.password[0],
      submit: candidates.submit[0]
    }
  }
}

export const createLoginDiscoveryManager = (options = {}) => {
  const jobs = new Map()
  const idFactory = options.idFactory || (() => crypto.randomUUID())

  return {
    create(input) {
      const job = createLoginDiscoveryJob(idFactory(), sanitizeLoginDiscoveryInput(input))
      jobs.set(job.id, job)
      return job
    },
    get(id) {
      return jobs.get(id)
    }
  }
}

const sanitizeLoginDiscoveryInput = (input = {}) => ({
  url: input.url,
  browser: input.browser,
  viewport: input.viewport,
  headless: input.headless,
  timeoutMs: input.timeoutMs,
  waitUntil: input.waitUntil
})

export const discoverLoginPage = async (input, options) => {
  const browser = await options.launchBrowser(input.browser, {
    headless: input.headless ?? true,
    allowLocalNetworkAccess: options.allowLocalNetworkAccess === true
  })
  let context
  let page
  try {
    context = await browser.newContext({
      viewport: normalizeViewport(input.viewport)
    })
    page = await context.newPage()
    let blockedNavigationError
    if (options.assertAllowedUrl) {
      await options.assertAllowedUrl(input.url)
      await context.route('**/*', async (route) => {
        const request = route.request()
        if (!request.isNavigationRequest() || request.frame() !== page.mainFrame()) {
          await route.continue()
          return
        }
        try {
          await options.assertAllowedUrl(request.url())
          await route.continue()
        } catch (error) {
          blockedNavigationError = error
          await route.abort('blockedbyclient')
        }
      })
    }
    const timeout = Math.max(1_000, Number(input.timeoutMs || 20_000))
    page.setDefaultTimeout(timeout)
    page.setDefaultNavigationTimeout(timeout)
    try {
      await page.goto(input.url, {
        waitUntil: input.waitUntil || 'domcontentloaded',
        timeout
      })
    } catch (error) {
      if (blockedNavigationError) throw blockedNavigationError
      throw error
    }
    if (options.assertAllowedUrl) await options.assertAllowedUrl(page.url())
    await page.waitForTimeout(300)

    const elements = await collectVisibleLoginElements(page)
    const analysis = analyzeLoginElements(elements)
    await redactRecommendedInputValues(page, analysis.recommended)
    await highlightRecommendedElements(page, analysis.recommended)
    const fileName = `login-discovery-${sanitizeFileName(options.discoveryId)}-${Date.now()}.png`
    const filePath = path.join(options.artifactDir, fileName)
    await page.screenshot({ path: filePath, fullPage: false })

    const diagnostics = []
    if (!analysis.recommended.username) diagnostics.push('未识别到可信的账号输入框')
    if (!analysis.recommended.password) diagnostics.push('未识别到密码输入框')
    if (!analysis.recommended.submit) diagnostics.push('未识别到登录按钮')

    return {
      pageUrl: page.url(),
      screenshotUrl: `${options.publicBaseUrl}/artifacts/${fileName}`,
      ...analysis,
      diagnostics
    }
  } finally {
    await page?.close?.().catch(() => {})
    await context?.close?.().catch(() => {})
    await browser.close().catch(() => {})
  }
}

const createLoginDiscoveryJob = (id, input) => {
  const createdAt = new Date().toISOString()
  let status = 'queued'
  let startedAt
  let updatedAt = createdAt
  let endedAt
  let result = {
    candidates: { username: [], password: [], submit: [] },
    recommended: {},
    diagnostics: []
  }
  let confirmed

  const touch = () => {
    updatedAt = new Date().toISOString()
  }

  const snapshot = () => ({
    discoveryId: id,
    status,
    createdAt,
    startedAt,
    updatedAt,
    endedAt,
    request: {
      url: input.url,
      browser: input.browser,
      viewport: input.viewport
    },
    ...result,
    ...(confirmed ? { confirmed } : {})
  })

  return {
    id,
    input: () => input,
    snapshot,
    markRunning() {
      status = 'running'
      startedAt = new Date().toISOString()
      touch()
      return snapshot()
    },
    complete(value) {
      result = value || {}
      status = 'completed'
      endedAt = new Date().toISOString()
      touch()
      return snapshot()
    },
    fail(error) {
      status = 'failed'
      endedAt = new Date().toISOString()
      result = {
        ...result,
        diagnostics: [error instanceof Error ? error.message : String(error)]
      }
      touch()
      return snapshot()
    },
    confirm(selection = {}) {
      if (status !== 'completed') throw new Error('Login discovery is not completed')
      const username = findCandidate(result, 'username', selection.usernameCandidateId)
      const password = findCandidate(result, 'password', selection.passwordCandidateId)
      const submit = findCandidate(result, 'submit', selection.submitCandidateId)
      confirmed = {
        usernameCandidateId: username.id,
        usernameLocator: username.locator,
        passwordCandidateId: password.id,
        passwordLocator: password.locator,
        submitCandidateId: submit.id,
        submitLocator: submit.locator,
        confirmedAt: new Date().toISOString()
      }
      touch()
      return snapshot()
    }
  }
}

const findCandidate = (result, role, candidateId) => {
  const candidate = result.candidates?.[role]?.find((item) => item.id === candidateId)
  if (!candidate) throw new Error(`Unknown ${role} candidate`)
  return candidate
}

const rankCandidates = (elements, role) => elements
  .map((element) => scoreElement(element, role))
  .filter(Boolean)
  .sort((left, right) => right.confidence - left.confidence || left.domIndex - right.domIndex)
  .slice(0, 5)

const scoreElement = (element, role) => {
  const tagName = String(element.tagName || '').toLowerCase()
  const type = String(element.type || '').toLowerCase()
  const autocomplete = String(element.autocomplete || '').toLowerCase()
  const descriptor = [
    element.id,
    element.name,
    element.placeholder,
    element.ariaLabel,
    element.label,
    element.text
  ].filter(Boolean).join(' ')
  const reasons = []
  let score = 0

  if (role === 'username') {
    if (tagName !== 'input' || !['', 'text', 'email', 'tel'].includes(type)) return undefined
    if (SEARCH_TERMS.test(descriptor)) return undefined
    if (autocomplete === 'username') {
      score += 55
      reasons.push('autocomplete=username')
    } else if (autocomplete === 'email') {
      score += 35
      reasons.push('autocomplete=email')
    }
    if (USERNAME_TERMS.test(descriptor)) {
      score += 38
      reasons.push('账号语义匹配')
    }
    if (type === 'email' || type === 'tel') score += 12
    if (score < 20) return undefined
  }

  if (role === 'password') {
    if (tagName !== 'input') return undefined
    if (type === 'password') {
      score += 75
      reasons.push('type=password')
    }
    if (autocomplete === 'current-password') {
      score += 25
      reasons.push('autocomplete=current-password')
    }
    if (PASSWORD_TERMS.test(descriptor)) score += 20
    if (score < 40) return undefined
  }

  if (role === 'submit') {
    const isButton = tagName === 'button' || element.role === 'button' || (tagName === 'input' && ['submit', 'button'].includes(type))
    if (!isButton) return undefined
    if (type === 'submit') {
      score += 35
      reasons.push('提交按钮')
    }
    if (SUBMIT_TERMS.test(descriptor)) {
      score += 55
      reasons.push('登录语义匹配')
    }
    if (element.formHasPassword) score += 20
    if (element.accessibleNameCount > 1) reasons.push('同名按钮，使用唯一 CSS 路径')
    if (score < 25) return undefined
  }

  const locator = buildLocator(element, role)
  if (!locator) return undefined
  return {
    id: `${role}-${element.domIndex}`,
    role,
    domIndex: element.domIndex,
    locator,
    strategy: locator.split('=')[0],
    label: candidateLabel(element, role),
    confidence: Math.min(100, score),
    reason: reasons.join('、') || '可见表单控件'
  }
}

const buildLocator = (element, role) => {
  if (element.testId) return `testid=${element.testId}`
  if (element.id) return `css=#${cssEscape(element.id)}`
  if (element.name) {
    return `css=${String(element.tagName || 'input').toLowerCase()}[name="${attributeEscape(element.name)}"]`
  }
  const accessibleName = String(element.ariaLabel || element.text || element.label || '').trim()
  if (role === 'submit' && accessibleName && Number(element.accessibleNameCount || 1) === 1) {
    // 唯一性按精确可访问名证明（accessibleNameCount === 1）：执行串必须带 exact，
    // 否则 Playwright 的子串匹配会命中「登录日志」这类同前缀元素。
    return `role=button[name="${accessibleName.replaceAll('\\', '\\\\').replaceAll('"', '\\"')}", exact=true]`
  }
  return element.cssPath ? `css=${element.cssPath}` : undefined
}

const candidateLabel = (element, role) => String(
  element.label || element.placeholder || element.ariaLabel || element.text || element.name || element.id || role
).trim()

const collectVisibleLoginElements = (page) => page.evaluate(() => {
  const nodes = Array.from(document.querySelectorAll('input, button, [role="button"]'))
  const isVisible = (element) => {
    const style = getComputedStyle(element)
    const rect = element.getBoundingClientRect()
    return style.display !== 'none'
      && style.visibility !== 'hidden'
      && Number(style.opacity) !== 0
      && rect.width >= 2
      && rect.height >= 2
  }
  const buttonAccessibleName = (element) => String(
    element.getAttribute('aria-label')
    || element.innerText
    || (element.tagName === 'INPUT' && ['submit', 'button'].includes(String(element.getAttribute('type') || '').toLowerCase())
      ? element.getAttribute('value')
      : '')
    || ''
  ).trim()
  const cssEscape = (value) => globalThis.CSS?.escape ? globalThis.CSS.escape(value) : String(value).replace(/[^\w-]/g, '\\$&')
  const cssPath = (element) => {
    if (element.id) return `#${cssEscape(element.id)}`
    const parts = []
    let current = element
    while (current && current.nodeType === 1) {
      if (current.id) {
        parts.unshift(`#${cssEscape(current.id)}`)
        const selector = parts.join(' > ')
        if (document.querySelectorAll(selector).length === 1) return selector
        parts.shift()
      }
      let part = current.tagName.toLowerCase()
      const siblings = Array.from(current.parentElement?.children || []).filter((item) => item.tagName === current.tagName)
      if (siblings.length > 1) part += `:nth-of-type(${siblings.indexOf(current) + 1})`
      parts.unshift(part)
      const selector = parts.join(' > ')
      if (document.querySelectorAll(selector).length === 1) return selector
      current = current.parentElement
    }
    return parts.join(' > ')
  }

  return nodes.flatMap((element, domIndex) => {
    if (!isVisible(element)) return []
    const type = element.getAttribute('type') || ''
    const labels = element.labels ? Array.from(element.labels).map((label) => label.innerText).join(' ') : ''
    const form = element.closest('form')
    const accessibleName = buttonAccessibleName(element)
    const accessibleNameCount = accessibleName
      ? nodes.filter((candidate) => isVisible(candidate) && buttonAccessibleName(candidate) === accessibleName).length
      : 0
    element.setAttribute('data-ai-login-index', String(domIndex))
    return [{
      domIndex,
      tagName: element.tagName.toLowerCase(),
      type,
      role: element.getAttribute('role') || '',
      id: element.id || '',
      name: element.getAttribute('name') || '',
      testId: element.getAttribute('data-testid') || '',
      placeholder: element.getAttribute('placeholder') || '',
      autocomplete: element.getAttribute('autocomplete') || '',
      ariaLabel: element.getAttribute('aria-label') || '',
      accessibleNameCount,
      label: labels.trim(),
      text: String(
        element.innerText
        || (element.tagName === 'INPUT' && ['submit', 'button'].includes(type.toLowerCase())
          ? element.getAttribute('value')
          : '')
        || ''
      ).trim(),
      formHasPassword: Boolean(form?.querySelector('input[type="password"]')),
      cssPath: cssPath(element)
    }]
  })
})

const highlightRecommendedElements = (page, recommended) => page.evaluate((items) => {
  const colors = { username: '#2563eb', password: '#c2418c', submit: '#16845b' }
  for (const [role, item] of Object.entries(items || {})) {
    if (!item) continue
    const element = document.querySelector(`[data-ai-login-index="${item.domIndex}"]`)
    if (!element) continue
    element.style.outline = `3px solid ${colors[role]}`
    element.style.outlineOffset = '2px'
    const rect = element.getBoundingClientRect()
    const badge = document.createElement('div')
    badge.textContent = role === 'username' ? '账号' : role === 'password' ? '密码' : '登录按钮'
    badge.style.cssText = `position:fixed;z-index:2147483647;left:${Math.max(0, rect.left)}px;top:${Math.max(0, rect.top - 24)}px;padding:3px 7px;border-radius:4px;background:${colors[role]};color:white;font:12px sans-serif;pointer-events:none;`
    document.body.appendChild(badge)
  }
}, recommended)

const redactRecommendedInputValues = (page, recommended) => page.evaluate((items) => {
  for (const role of ['username', 'password']) {
    const item = items?.[role]
    if (!item) continue
    const element = document.querySelector(`[data-ai-login-index="${item.domIndex}"]`)
    if (!(element instanceof HTMLInputElement) && !(element instanceof HTMLTextAreaElement)) continue
    element.value = ''
    element.removeAttribute('value')
  }
}, recommended)

const normalizeViewport = (value) => {
  const [width, height] = String(value || '1440x900').split('x').map(Number)
  return Number.isFinite(width) && Number.isFinite(height)
    ? { width, height }
    : { width: 1440, height: 900 }
}

const cssEscape = (value) => String(value).replace(/[^\w-]/g, (char) => `\\${char}`)
const attributeEscape = (value) => String(value).replaceAll('\\', '\\\\').replaceAll('"', '\\"')
const sanitizeFileName = (value) => String(value).replace(/[^\w.-]+/g, '_')
