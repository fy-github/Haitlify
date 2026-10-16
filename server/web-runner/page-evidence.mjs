import { createHash } from 'node:crypto'
import { resolveLocator, splitLocatorExactSuffix } from './locator-utils.mjs'
import { PAGINATION_SNAPSHOT_SOURCE, paginationSignature } from './pagination-signature.mjs'

/** 在页面里求值共享的签名源码（表达式形式，函数源码只有一份）。 */
const collectPaginationSnapshot = async (page) => page.evaluate(`(${PAGINATION_SNAPSHOT_SOURCE})()`)

// 首屏之外的证据目标：探索阶段会滚动页面，动作阶段的观测不会——若直接判 action_evidence_missing，
// 真实存在的元素会被报成「当前页面观测不到」（复核方 X5）。这里做一次有界滚动扫描再观测。
export const OBSERVATION_SCROLL_STEPS = 6
const normalizeObservedData = (result) => {
  if (!result || result.ok === false) return null
  const data = result.ok === true && result.data ? result.data : result
  return data && typeof data === 'object' ? data : null
}
export const sweepForEvidenceTarget = async (page, observe, evidenceId) => {
  for (let step = 0; step < OBSERVATION_SCROLL_STEPS; step += 1) {
    const atBottom = await page.evaluate((fraction) => {
      const height = Math.max(document.body?.scrollHeight || 0, document.documentElement?.scrollHeight || 0)
      window.scrollTo(0, Math.round(height * fraction))
      return window.scrollY + window.innerHeight >= height - 2
    }, (step + 1) / OBSERVATION_SCROLL_STEPS)
    await new Promise((resolve) => setTimeout(resolve, 150))
    let data = null
    try {
      data = normalizeObservedData(await observe())
    } catch {
      data = null
    }
    const pool = Array.isArray(data?.allElements) && data.allElements.length
      ? data.allElements
      : (Array.isArray(data?.elements) ? data.elements : [])
    if (pool.some((item) => item?.evidenceId === evidenceId)) return data
    if (atBottom) break
  }
  return null
}

const VIEWPORT_SAFE_SELECTOR = 'a, button, input, select, textarea, summary, h1, h2, h3, h4, [role], [data-testid], [data-test-id]'

// 阶段 3 元素采集预算：定向查询优先，其次按分区预算采集，超出预算必须返回 evidence_truncated。
export const DEFAULT_ELEMENT_BUDGET = 320
export const DEFAULT_PARTITION_BUDGET = 160
const FRAME_DEPTH_LIMIT = 2

const normalizeText = (value) => String(value || '')
  .trim()
  .replace(/\s+/g, ' ')

const normalizeKeyText = (value) => normalizeText(value)

const uniqueSorted = (values = []) => Array.from(new Set(values.filter(Boolean))).sort((left, right) => left.localeCompare(right))

const observedEvidenceId = (element = {}) => {
  const identity = JSON.stringify({
    ordinal: String(element.id || ''),
    locator: String(element.locator || ''),
    role: String(element.role || ''),
    tagName: String(element.tagName || element.tag || ''),
    label: String(element.label || element.accessibleName || element.text || ''),
    pageScope: String(element.pageScope || ''),
    rowContext: String(element.rowContext || '')
  })
  return `element-${createHash('sha256').update(identity).digest('hex').slice(0, 16)}`
}

const normalizeRoute = (value) => String(value || '')
  .replace(/[?#].*$/, '')
  .replace(/\/+$/, '') || '/'

const normalizeHashRoute = (value) => {
  const raw = String(value || '').replace(/^#/, '')
  if (!raw) return ''
  const route = raw.split(/[?#]/, 1)[0].replace(/^!/, '')
  return route ? `#${route}` : ''
}

export const normalizePageKey = (url) => {
  try {
    const parsed = new URL(String(url || ''))
    if (!['http:', 'https:'].includes(parsed.protocol)) return ''
    const origin = `${parsed.protocol}//${parsed.host}`.toLowerCase()
    const pathname = normalizeRoute(parsed.pathname)
    const hashRoute = normalizeHashRoute(parsed.hash)
    return `${origin}${pathname}${hashRoute}`
  } catch {
    return ''
  }
}

const buildTabKey = (tab = {}) => [
  normalizeKeyText(tab.role || 'tab'),
  normalizeKeyText(tab.label || tab.accessibleName || ''),
  tab.selected === true ? 'selected' : 'not-selected'
].join('|')

const buildOverlayKey = (overlay = {}) => [
  normalizeKeyText(overlay.type || ''),
  normalizeKeyText(overlay.text || ''),
  normalizeKeyText(overlay.locator || ''),
  overlay.blocking === true ? 'blocking' : 'non-blocking'
].join('|')

const buildElementKey = (element = {}) => [
  normalizeKeyText(element.locator || ''),
  normalizeKeyText(element.role || ''),
  normalizeKeyText(element.tagName || element.tag || ''),
  normalizeKeyText(element.pageScope || ''),
  normalizeKeyText(element.rowContext || '')
].join('|')

export const pageFingerprint = (observation = {}) => {
  const normalized = {
    pageKey: normalizePageKey(observation.pageKey || observation.url || ''),
    title: normalizeKeyText(observation.title || ''),
    headings: uniqueSorted((Array.isArray(observation.headings) ? observation.headings : []).map(normalizeKeyText)),
    breadcrumbs: uniqueSorted((Array.isArray(observation.breadcrumbs) ? observation.breadcrumbs : []).map(normalizeKeyText)),
    activeMenus: uniqueSorted((Array.isArray(observation.activeMenus) ? observation.activeMenus : []).map(normalizeKeyText)),
    tabs: uniqueSorted((Array.isArray(observation.tabs) ? observation.tabs : []).map(buildTabKey)),
    overlays: uniqueSorted((Array.isArray(observation.overlays) ? observation.overlays : []).filter((overlay) => overlay?.blocking === true).map(buildOverlayKey)),
    elements: uniqueSorted((Array.isArray(observation.elements) ? observation.elements : []).map(buildElementKey))
  }
  return createHash('sha256').update(JSON.stringify(normalized)).digest('hex')
}

const browserEvaluateElement = (options = {}) => {
  const viewportSafeSelector = 'a, button, input, select, textarea, summary, h1, h2, h3, h4, [role], [data-testid], [data-test-id]'
  // Defaults are inlined on purpose: page.evaluate() only ships this function source to the browser.
  const elementBudget = Number(options.budget) > 0 ? Math.floor(Number(options.budget)) : 320
  const partitionBudget = Number(options.partitionBudget) > 0 ? Math.floor(Number(options.partitionBudget)) : 160
  const frameDepthLimit = 2
  const targetedTerms = (Array.isArray(options.targetedTerms) ? options.targetedTerms : [])
    .map((term) => String(term || '').trim().toLowerCase())
    .filter(Boolean)
  const visible = (element) => {
    const style = getComputedStyle(element)
    const rect = element.getBoundingClientRect()
    return style.display !== 'none'
      && style.visibility !== 'hidden'
      && Number(style.opacity) !== 0
      && rect.width >= 2
      && rect.height >= 2
      && rect.bottom > 0
      && rect.right > 0
      && rect.top < innerHeight
      && rect.left < innerWidth
  }

  const text = (element) => String(element?.innerText || element?.textContent || '')
    .trim()
    .replace(/\s+/g, ' ')
    .slice(0, 200)

  const escape = (value) => globalThis.CSS?.escape ? globalThis.CSS.escape(value) : String(value).replace(/[^\w-]/g, '\\$&')

  const stableCssPath = (element, root) => {
    if (!element || element.nodeType !== 1) return undefined
    if (element.id) return `#${escape(element.id)}`
    const scope = root || document
    const parts = []
    let current = element
    while (current && current.nodeType === 1 && parts.length < 6) {
      // parentElement 在 ShadowRoot 下为 null：必须兼容 DocumentFragment(=shadow root)，
      // 否则同名兄弟 host 无法生成 :nth-of-type 区分，两个 host 会得到同一条路径。
      const parent = current.parentElement
        || (current.parentNode && current.parentNode.nodeType === 11 ? current.parentNode : null)
      let part = current.tagName.toLowerCase()
      const siblings = Array.from(parent?.children || []).filter((node) => node.tagName === current.tagName)
      if (siblings.length > 1) part += `:nth-of-type(${siblings.indexOf(current) + 1})`
      parts.unshift(part)
      const candidate = parts.join(' > ')
      if (scope.querySelectorAll(candidate).length === 1) return candidate
      if (!parent || parent.nodeType === 11) break
      current = parent
    }
    return undefined
  }

  const partitionFor = (element, fallback) => {
    if (element.closest('[role="dialog"],[aria-modal="true"]')) return 'dialog'
    if (element.closest('[role="tablist"],.ant-tabs-nav,.el-tabs-nav')) return 'tablist'
    if (element.closest('nav,aside,[role="menu"]')) return 'navigation'
    if (element.closest('table')) return 'table'
    if (element.closest('form')) return 'form'
    if (element.closest('main')) return 'main'
    return fallback || 'document'
  }

  const rowContext = (element) => {
    const row = element.closest('tr,[role="row"],li,[role="listitem"]')
    if (row) return text(row).slice(0, 240)
    const scope = element.closest('section,form,main,nav,aside,article')
    return text(scope).slice(0, 240)
  }

  const roleFor = (element) => {
    const explicitRole = String(element.getAttribute('role') || '').trim()
    if (explicitRole) return explicitRole
    if (element.matches('button')) return 'button'
    if (element.matches('a[href]')) return 'link'
    if (element.matches('input')) {
      const type = String(element.getAttribute('type') || 'text').toLowerCase()
      if (type === 'checkbox') return 'checkbox'
      if (type === 'radio') return 'radio'
      if (type === 'button' || type === 'submit' || type === 'reset') return 'button'
      return 'textbox'
    }
    if (element.matches('select')) return 'combobox'
    if (element.matches('textarea')) return 'textbox'
    if (element.matches('[role="tab"]')) return 'tab'
    return ''
  }

  const accessibleName = (element) => {
    const direct = String(
      element.getAttribute('aria-label')
      || element.getAttribute('title')
      || element.getAttribute('placeholder')
      || ''
    ).trim()
    if (direct) return direct.replace(/\s+/g, ' ').slice(0, 120)
    const labelledBy = element.getAttribute('aria-labelledby')
    if (labelledBy) {
      const names = labelledBy.split(/\s+/).map((id) => document.getElementById(id)?.innerText || document.getElementById(id)?.textContent || '').join(' ')
      const trimmed = String(names || '').trim().replace(/\s+/g, ' ')
      if (trimmed) return trimmed.slice(0, 120)
    }
    const labels = Array.from(element.labels || []).map((label) => text(label)).filter(Boolean).join(' ')
    if (labels) return labels.slice(0, 120)
    // 表单控件（input/textarea/select）没有真正的「内容文本」：把 option 文本当 accessible name，
    // 采集期证明的唯一性在回放期就失效（Playwright 按 ARIA 计算名字，无标签 select 名字为空 → 0 命中）。
    // 没有标签时让采集器退回 testId / css 路径策略。
    if (element.matches('input,textarea,select')) return ''
    const inner = text(element)
    if (inner) return inner.slice(0, 120)
    return ''
  }

  const candidateText = (element) => {
    if (element.matches('input,textarea,select')) {
      return String(element.getAttribute('placeholder') || '').trim().replace(/\s+/g, ' ').slice(0, 120)
    }
    return text(element).slice(0, 120)
  }

  const isBlockingOverlay = (element) => {
    const role = String(element.getAttribute('role') || '').toLowerCase()
    return element.getAttribute('aria-modal') === 'true'
      || role === 'dialog'
      || element.classList.contains('modal')
      || element.classList.contains('dialog')
      || element.classList.contains('overlay')
      || Array.from(element.classList || []).some((name) => /overlay|dialog|modal|loading|cookie|guide/i.test(name))
  }

  const overlaysByDocument = new Map()
  const overlaysFor = (doc) => {
    if (overlaysByDocument.has(doc)) return overlaysByDocument.get(doc)
    const nodes = Array.from(doc.querySelectorAll('[role="dialog"],[aria-modal="true"],.modal,.dialog,.overlay,[class*="overlay"],[class*="loading"],[class*="cookie"],[class*="guide"]'))
      .filter((element) => visible(element) && isBlockingOverlay(element))
    overlaysByDocument.set(doc, nodes)
    return nodes
  }

  const hitPoint = (element) => {
    const rect = element.getBoundingClientRect()
    return {
      x: Math.min(rect.right - 1, Math.max(rect.left + 1, rect.left + rect.width / 2)),
      y: Math.min(rect.bottom - 1, Math.max(rect.top + 1, rect.top + rect.height / 2))
    }
  }

  // document.elementFromPoint 只会返回 shadow host，open shadow root 内的元素会被误判为不可点击。
  // 这里沿着 shadow root 逐层下钻，拿到真正命中的节点（closed root 无法下钻，保持不可见）。
  const deepElementFromPoint = (doc, x, y) => {
    let hit = doc.elementFromPoint(x, y)
    let guard = 0
    while (hit && hit.shadowRoot && guard < 5) {
      const inner = hit.shadowRoot.elementFromPoint(x, y)
      if (!inner || inner === hit) break
      hit = inner
      guard += 1
    }
    return hit
  }

  // shadow-including 的包含判断：Node.contains 不跨 shadow 边界。
  const containsDeep = (host, node) => {
    if (!host || !node) return false
    if (host === node || host.contains(node)) return true
    let current = node
    let guard = 0
    while (current && guard < 5) {
      const root = typeof current.getRootNode === 'function' ? current.getRootNode() : null
      const rootHost = root && root.host ? root.host : null
      if (!rootHost) return false
      if (rootHost === host || host.contains(rootHost)) return true
      current = rootHost
      guard += 1
    }
    return false
  }

  const receivesEventsIn = (doc, element) => {
    if (!visible(element)) return false
    const style = getComputedStyle(element)
    if (style.pointerEvents === 'none') return false
    const point = hitPoint(element)
    const hit = deepElementFromPoint(doc, point.x, point.y)
    return Boolean(hit && containsDeep(element, hit))
  }

  const blockedByOverlayIn = (doc, element) => {
    const overlayNodes = overlaysFor(doc)
    if (!overlayNodes.length) return false
    const point = hitPoint(element)
    const hit = deepElementFromPoint(doc, point.x, point.y)
    const hitsOverlay = Boolean(hit && overlayNodes.some((overlay) => overlay === hit || containsDeep(overlay, hit)))
    return hitsOverlay && !overlayNodes.some((overlay) => containsDeep(overlay, element))
  }

  const matchesTargeted = (element) => {
    if (!targetedTerms.length) return false
    const haystack = [
      accessibleName(element),
      candidateText(element),
      roleFor(element),
      element.getAttribute('data-testid'),
      element.getAttribute('data-test-id'),
      element.getAttribute('aria-label'),
      element.getAttribute('placeholder')
    ].map((value) => String(value || '').toLowerCase()).filter(Boolean)
    return targetedTerms.some((term) => haystack.some((value) => value.includes(term)))
  }

  const collected = []
  const frames = []
  const closedShadowHosts = []
  const unresolvedShadowHosts = []
  const unresolvedShadowPaths = new Set()
  const closedShadowPaths = new Set()

  const describeElement = (element, meta) => {
    const role = roleFor(element)
    const accessible = accessibleName(element)
    const textValue = candidateText(element)
    const cssPath = stableCssPath(element, meta.root)
    const testId = element.getAttribute('data-testid') || element.getAttribute('data-test-id') || ''
    const row = element.closest('tr,[role="row"]')
    const scope = partitionFor(element, meta.hostScope)
    return {
      id: `element-${collected.length + 1}`,
      tagName: element.tagName.toLowerCase(),
      role,
      type: element.getAttribute('type') || '',
      testId,
      idValue: element.id ? escape(element.id) : '',
      accessibleName: accessible,
      accessibilityText: accessible || textValue,
      text: textValue,
      visible: true,
      enabled: !element.matches(':disabled,[aria-disabled="true"]'),
      receivesEvents: receivesEventsIn(meta.doc, element),
      coveredByBlockingOverlay: blockedByOverlayIn(meta.doc, element),
      semanticType: element.matches('h1,h2,h3,h4') ? 'heading'
        : element.matches('input,select,textarea') ? 'field'
          : element.matches('a,button,[role="button"]') ? 'action' : 'content',
      contextText: String(element.closest('td,li,form,section,main,nav,aside,article')?.innerText || '').trim().replace(/\s+/g, ' ').slice(0, 240),
      rowContext: rowContext(element),
      pageScope: scope,
      partition: scope,
      tableColumn: row ? String(element.closest('td,th')?.cellIndex ?? '') : '',
      cssPath,
      framePath: meta.framePath || '',
      frameUrl: meta.frameUrl || '',
      shadowPath: meta.shadowPath || '',
      domIndex: collected.length,
      targeted: matchesTargeted(element),
      testIdCount: testId ? Array.from(meta.root.querySelectorAll('[data-testid], [data-test-id]')).filter((node) => String(node.getAttribute('data-testid') || node.getAttribute('data-test-id') || '') === testId).length : 0,
      accessibleNameCount: accessible ? Array.from(meta.root.querySelectorAll(viewportSafeSelector)).filter((node) => visible(node) && roleFor(node) === role && accessibleName(node) === accessible).length : 0,
      textCount: textValue ? Array.from(meta.root.querySelectorAll(viewportSafeSelector)).filter((node) => visible(node) && candidateText(node) === textValue).length : 0,
      cssPathCount: cssPath ? meta.root.querySelectorAll(cssPath).length : 0
    }
  }

  const collectFrame = (frameElement, meta) => {
    const framePath = stableCssPath(frameElement, meta.root) || frameElement.id || 'iframe'
    // 嵌套 iframe 必须保留外层 frame 作用域：frame=#outer >> frame=#inner >> ...
    const composedFramePath = meta.framePath ? `${meta.framePath} >> ${framePath}` : framePath
    let frameDocument = null
    try {
      frameDocument = frameElement.contentDocument || frameElement.contentWindow?.document || null
    } catch {
      frameDocument = null
    }
    let accessibleDocument = null
    if (frameDocument && frameDocument.documentElement) {
      try {
        // Cross-origin documents throw on property access.
        void frameDocument.location.href
        accessibleDocument = frameDocument
      } catch {
        accessibleDocument = null
      }
    }
    if (!accessibleDocument) {
      frames.push({ framePath: composedFramePath, frameUrl: String(frameElement.getAttribute('src') || ''), supported: false, reason: 'unsupported_frame' })
      return
    }
    const frameUrl = String(accessibleDocument.location?.href || frameElement.getAttribute('src') || '')
    frames.push({ framePath: composedFramePath, frameUrl, supported: true, reason: '' })
    if ((meta.frameDepth || 0) >= frameDepthLimit) return
    walkContainer(accessibleDocument, {
      ...meta,
      doc: accessibleDocument,
      root: accessibleDocument,
      framePath: composedFramePath,
      frameUrl,
      frameDepth: (meta.frameDepth || 0) + 1
    })
  }

  const walkContainer = (container, meta) => {
    for (const child of Array.from(container.children || [])) {
      const tagName = String(child.tagName || '').toLowerCase()
      if (typeof child.matches === 'function' && child.matches(viewportSafeSelector) && visible(child)) {
        collected.push(describeElement(child, meta))
      }
      if (child.shadowRoot) {
        const hostPath = stableCssPath(child, meta.root)
        if (!hostPath) {
          // 不能再用父级 shadowPath 兜底：两个同名兄弟 host 会产出完全相同的 locator 并自称唯一。
          // 这里明确记为「无法唯一寻址」，子树不采集（fail-closed），并留下诊断。
          if (!unresolvedShadowPaths.has(child)) {
            unresolvedShadowPaths.add(child)
            unresolvedShadowHosts.push({ hostPath: '', tagName, reason: 'unresolved_shadow_scope' })
          }
          continue
        }
        const shadowPath = meta.shadowPath ? `${meta.shadowPath} >> ${hostPath}` : hostPath
        // 递归时 root 必须切到当前 shadow root：否则无 id 的嵌套 host 会在外层 document 里找不到路径，
        // 整棵子树被静默跳过（且不会产生任何 unsupported 诊断）。
        walkContainer(child.shadowRoot, { ...meta, shadowPath, root: child.shadowRoot, hostScope: partitionFor(child, meta.hostScope) })
        continue
      }
      if (tagName === 'iframe' || tagName === 'frame') {
        collectFrame(child, meta)
        continue
      }
      if (tagName.includes('-') && visible(child) && child.children.length === 0 && !String(child.textContent || '').trim()) {
        // 自定义元素但没有 light DOM 内容：只可能是 closed shadow root（或在外部无法观测的渲染）。
        const hostPath = stableCssPath(child, meta.root) || tagName
        if (!closedShadowPaths.has(hostPath)) {
          closedShadowPaths.add(hostPath)
          closedShadowHosts.push({ hostPath, tagName, reason: 'unsupported_shadow_dom' })
        }
      }
      walkContainer(child, meta)
    }
  }

  walkContainer(document, {
    doc: document,
    root: document,
    framePath: '',
    frameUrl: '',
    shadowPath: '',
    hostScope: '',
    frameDepth: 0
  })

  const total = collected.length
  const primary = collected.filter((element) => element.targeted)
  const secondary = collected.filter((element) => !element.targeted)
  // 定向元素同样受整体预算约束：否则一个宽泛的语义词（例如“按钮”）会让预算失效并返回成百上千条。
  const primaryKept = primary.slice(0, elementBudget)
  const perPartitionUsed = new Map()
  const withinPartitionBudget = []
  for (const element of secondary) {
    const used = perPartitionUsed.get(element.partition) || 0
    if (used >= partitionBudget) continue
    perPartitionUsed.set(element.partition, used + 1)
    withinPartitionBudget.push(element)
  }
  const room = Math.max(0, elementBudget - primaryKept.length)
  const keptIds = new Set([...primaryKept, ...withinPartitionBudget.slice(0, room)].map((element) => element.id))
  const elements = collected.filter((element) => keptIds.has(element.id))
  const partitions = {}
  for (const element of collected) {
    const bucket = partitions[element.partition] || (partitions[element.partition] = { total: 0, collected: 0 })
    bucket.total += 1
  }
  for (const element of elements) {
    const bucket = partitions[element.partition] || (partitions[element.partition] = { total: 0, collected: 0 })
    bucket.collected += 1
  }
  return {
    elements,
    diagnostics: {
      budget: elementBudget,
      partitionBudget,
      total,
      collected: elements.length,
      dropped: total - elements.length,
      targeted: primary.length,
      partitions,
      frames,
      closedShadowHosts,
      unresolvedShadowHosts
    }
  }
}

const elementScopePrefix = (element = {}) => {
  const framePrefix = element.framePath
    ? `${String(element.framePath).split(' >> ').map((segment) => segment.trim()).filter(Boolean).map((segment) => `frame=${segment}`).join(' >> ')} >> `
    : ''
  const shadowPrefix = element.shadowPath
    ? `${String(element.shadowPath).split(' >> ').map((segment) => segment.trim()).filter(Boolean).map((segment) => `css=${segment}`).join(' >> ')} >> `
    : ''
  return `${framePrefix}${shadowPrefix}`
}

const withMatchedLocator = (element) => {
  const testId = String(element.testId || '').trim()
  const role = String(element.role || '').trim()
  const accessibleName = String(element.accessibleName || '').trim()
  const text = String(element.text || '').trim()
  // 唯一性是按「精确名字/精确文本」证明的：locator 必须带上 exact 标记，
  // 否则 Playwright 的默认子串匹配会把「查询」匹配到「查询用户」，证据绑定的唯一性失效。
  const baseLocator = testId
    ? `testId=${testId}`
    : role && accessibleName && Number(element.accessibleNameCount || 0) === 1
      ? `role=${role}[name="${accessibleName.replaceAll('\\', '\\\\').replaceAll('"', '\\"')}", exact=true]`
      : text && Number(element.textCount || 0) === 1
        ? `text=${text}, exact=true`
        : element.cssPath
          ? `css=${element.cssPath}`
          : undefined
  const locator = baseLocator ? `${elementScopePrefix(element)}${baseLocator}` : undefined
  const matchedElementCount = baseLocator?.startsWith('testId=')
    ? Number(element.testIdCount || 0)
    : baseLocator?.startsWith('role=')
      ? Number(element.accessibleNameCount || 0)
      : baseLocator?.startsWith('text=')
        ? Number(element.textCount || 0)
        : baseLocator?.startsWith('css=')
          ? Number(element.cssPathCount || 0)
          : 0
  const strategy = String(baseLocator || '').split('=')[0] || 'css'
  // locatorSpec 必须与最终选中的 locator 同构：既不能声明没用的字段（例如 testId locator 却带 role/exact），
  // 也不能漏掉决定语义的 exact —— 平台会逐字比对序列化结果，不一致直接 fail-closed。
  const specFields = strategy === 'testId'
    ? (testId ? { testId } : {})
    : strategy === 'role'
      ? (role && accessibleName ? { role, name: accessibleName, exact: true } : {})
      : strategy === 'text'
        ? (text ? { text, exact: true } : {})
        : {}
  return locator ? {
    id: element.id,
    evidenceId: observedEvidenceId({ ...element, locator }),
    locator,
    locatorSpec: {
      strategy,
      ...specFields,
      ...(element.cssPath ? { selector: element.cssPath } : {}),
      framePath: element.framePath || '',
      shadowPath: element.shadowPath || '',
      ...(element.frameUrl ? { frameUrl: element.frameUrl } : {}),
      pageScope: element.pageScope || 'document'
    },
    framePath: element.framePath || '',
    shadowPath: element.shadowPath || '',
    frameUrl: element.frameUrl || '',
    partition: element.partition || element.pageScope || 'document',
    domIndex: Number(element.domIndex || 0),
    targeted: element.targeted === true,
    label: element.accessibleName || element.text || element.idValue || element.tagName,
    role: element.role || element.tagName,
    tagName: element.tagName,
    type: element.type || undefined,
    strategy: String(baseLocator || '').split('=')[0] || 'css',
    contextText: element.contextText,
    rowContext: element.rowContext,
    accessibleName: element.accessibleName,
    accessibilityText: element.accessibilityText,
    text: element.text,
    visible: element.visible,
    enabled: element.enabled,
    receivesEvents: element.receivesEvents,
    coveredByBlockingOverlay: element.coveredByBlockingOverlay,
    semanticType: element.semanticType,
    pageScope: element.pageScope,
    tableColumn: element.tableColumn,
    matchedElementCount
  } : undefined
}

export const collectVisibleElementsDetailed = async (page, options = {}) => {
  const raw = await page.evaluate(browserEvaluateElement, options)
  const descriptors = Array.isArray(raw) ? raw : (Array.isArray(raw?.elements) ? raw.elements : [])
  const elements = descriptors.map(withMatchedLocator).filter(Boolean)
  const diagnostics = raw && !Array.isArray(raw) ? (raw.diagnostics || {}) : {}
  return { elements, diagnostics }
}

export const collectVisibleElements = async (page, options = {}) => {
  const { elements } = await collectVisibleElementsDetailed(page, options)
  return elements
}

const collectObservationMetadata = async (page) => page.evaluate(() => {
  const visible = (element) => {
    const style = getComputedStyle(element)
    const rect = element.getBoundingClientRect()
    return style.display !== 'none'
      && style.visibility !== 'hidden'
      && Number(style.opacity) !== 0
      && rect.width >= 2
      && rect.height >= 2
      && rect.bottom > 0
      && rect.right > 0
      && rect.top < innerHeight
      && rect.left < innerWidth
  }

  const text = (element) => String(element?.innerText || element?.textContent || '')
    .trim()
    .replace(/\s+/g, ' ')
    .slice(0, 200)

  const breadcrumbs = Array.from(document.querySelectorAll('[aria-label*="breadcrumb" i],.breadcrumb,[class*="breadcrumb"]'))
    .filter(visible)
    .map(text)

  const activeMenus = Array.from(document.querySelectorAll('[role="menuitem"][aria-current="page"],[role="menuitem"][aria-selected="true"],.ant-menu-item-selected,.el-menu-item.is-active,[class*="menu-item-active"]'))
    .filter(visible)
    .map(text)

  const headings = Array.from(document.querySelectorAll('h1,h2,h3,h4')).filter(visible).map(text)

  const forbiddenTabLabel = (value) => ['确定', '确认', '提交', '保存', '删除', '停用', '启用', '开通', '支付', '发送', '上传']
    .some((term) => String(value || '').replace(/\s+/g, '').includes(term))

  const escape = (value) => globalThis.CSS?.escape ? globalThis.CSS.escape(value) : String(value).replace(/[^\w-]/g, '\\$&')

  const stableCssPath = (element) => {
    const scope = element.closest('[role="tablist"],.ant-tabs-nav,.el-tabs-nav')
    if (!scope) return undefined
    const parts = []
    let current = element
    while (current && current !== scope && current.nodeType === 1 && parts.length < 4) {
      const classes = Array.from(current.classList || []).filter(Boolean).sort().map((name) => `.${escape(name)}`).join('')
      const part = `${current.tagName.toLowerCase()}${classes}`
      parts.unshift(part)
      const candidate = parts.join(' > ')
      if (scope.querySelectorAll(candidate).length === 1 && document.querySelectorAll(candidate).length === 1) return candidate
      current = current.parentElement
    }
    return undefined
  }

  const stableTablistPath = (scope) => {
    if (!scope) return undefined
    if (scope.id) {
      const selector = `#${escape(scope.id)}`
      if (document.querySelectorAll(selector).length === 1) return `css=${selector}`
    }
    if (scope.matches('[role="tablist"]') && document.querySelectorAll('[role="tablist"]').length === 1) return 'css=[role="tablist"]'
    const classes = Array.from(scope.classList || []).filter(Boolean).sort().map((name) => `.${escape(name)}`).join('')
    if (classes) {
      const selector = `${scope.tagName.toLowerCase()}${classes}`
      if (document.querySelectorAll(selector).length === 1) return `css=${selector}`
    }
    return undefined
  }

  const tabNodes = Array.from(document.querySelectorAll('[role="tab"],.ant-tabs-tab,.el-tabs__item')).filter(visible)
  const visibleTabLabels = tabNodes.map(text)
  const accessibleTabLabels = tabNodes.map((element) => String(element.getAttribute('aria-label') || element.getAttribute('title') || '').trim())
  const tabLabels = tabNodes.map((_element, index) => visibleTabLabels[index] || accessibleTabLabels[index])
  const tabs = tabNodes.map((element, index) => {
    const label = tabLabels[index]
    const uniquenessLabel = visibleTabLabels[index] || accessibleTabLabels[index]
    const uniqueLabel = uniquenessLabel && (visibleTabLabels[index] ? visibleTabLabels : accessibleTabLabels).filter((candidate) => candidate === uniquenessLabel).length === 1
    const testId = element.getAttribute('data-testid') || element.getAttribute('data-test-id') || ''
    const role = element.getAttribute('role') || ''
    const cssPath = stableCssPath(element)
    const tablist = element.closest('[role="tablist"],.ant-tabs-nav,.el-tabs-nav')
    const scopedTabs = tablist ? tabNodes.filter((candidate) => candidate.closest('[role="tablist"],.ant-tabs-nav,.el-tabs-nav') === tablist) : []
    const uniqueScopedLabel = uniquenessLabel && scopedTabs.filter((candidate) => {
      const candidateIndex = tabNodes.indexOf(candidate)
      return (visibleTabLabels[index] ? visibleTabLabels[candidateIndex] : accessibleTabLabels[candidateIndex]) === uniquenessLabel
    }).length === 1
    const dangerousLabel = `${visibleTabLabels[index]} ${accessibleTabLabels[index]}`
    const tablistPath = stableTablistPath(tablist)
    const locator = element.id
      ? `css=#${escape(element.id)}`
      : testId
        ? `testId=${testId}`
        : role === 'tab' && uniqueLabel
          // 唯一性按「标签文本完全相等」证明：执行串必须带 exact，否则「查询」会命中「查询用户」。
          ? `role=tab[name="${label.replaceAll('\\', '\\\\').replaceAll('"', '\\"')}", exact=true]`
          : cssPath || (!forbiddenTabLabel(dangerousLabel) && uniqueScopedLabel && tablistPath ? `text=${label}@@scope=${tablistPath}` : undefined)
    return {
      label,
      accessibleName: accessibleTabLabels[index],
      role,
      selected: element.getAttribute('aria-selected') === 'true' || element.classList.contains('is-active'),
      enabled: !element.matches(':disabled,[aria-disabled="true"]'),
      locator,
      ambiguous: !locator && Boolean(label)
    }
  })

  const overlayNodes = Array.from(document.querySelectorAll('[role="dialog"],[aria-modal="true"],.modal,.dialog,.overlay,[class*="overlay"],[class*="loading"],[class*="cookie"],[class*="guide"]'))
    .filter(visible)
  const overlays = overlayNodes.map((element) => ({
    type: element.getAttribute('role') === 'dialog' || element.getAttribute('aria-modal') === 'true' ? 'dialog' : 'overlay',
    text: text(element),
    locator: element.id ? `css=#${CSS.escape(element.id)}` : undefined,
    blocking: element.getAttribute('aria-modal') === 'true' || element.getAttribute('role') === 'dialog'
  }))

  const frameDiagnostics = Array.from(document.querySelectorAll('iframe,frame')).map((element, index) => {
    const framePath = element.id ? `#${escape(element.id)}` : `iframe:nth-of-type(${index + 1})`
    let frameDocument = null
    try {
      frameDocument = element.contentDocument || null
      if (frameDocument) void frameDocument.location.href
    } catch {
      frameDocument = null
    }
    const accessible = Boolean(frameDocument && frameDocument.documentElement)
    return {
      framePath,
      frameUrl: accessible ? String(frameDocument.location.href) : String(element.getAttribute('src') || ''),
      supported: accessible,
      reason: accessible ? '' : 'unsupported_frame'
    }
  })
  const closedShadowHosts = Array.from(document.querySelectorAll('*')).filter((element) => {
    const tagName = String(element.tagName || '').toLowerCase()
    if (!tagName.includes('-') || !visible(element) || element.shadowRoot) return false
    const lightText = String(element.textContent || '').trim()
    // 两种形态都要算：① 自定义元素但 light DOM 无内容（只可能是 closed shadow root）；
    // ② light DOM 有文本、但渲染出来的文本不含它——说明渲染内容来自影子树（closed，所以外部读不到）。
    // 老版本只认 ①，宿主带回退文本/子节点时整页漏检，失败被降级成普通 action_evidence_missing。
    const hidesLightDom = Boolean(lightText) && String(element.innerText || '').trim() !== lightText
    return (element.children.length === 0 && !lightText) || hidesLightDom
  }).map((element) => ({
    hostPath: element.id ? `#${escape(element.id)}` : String(element.tagName || '').toLowerCase(),
    tagName: String(element.tagName || '').toLowerCase(),
    reason: 'unsupported_shadow_dom'
  }))
  const scrollContainers = Array.from(document.querySelectorAll('*')).filter((element) => element.scrollHeight > element.clientHeight + 20 && element.clientHeight > 40).length
  const hasNextPage = Array.from(document.querySelectorAll('button,a,[role="button"]')).some((element) => visible(element) && /^(下一页|下一步|Next)$/i.test(text(element)) && !element.matches(':disabled,[aria-disabled="true"]'))
  const hasInactiveTab = Array.from(document.querySelectorAll('[role="tab"],.ant-tabs-tab,.el-tabs__item')).some((element) => visible(element) && element.getAttribute('aria-selected') !== 'true' && !element.classList.contains('is-active'))
  const hasNavigation = Array.from(document.querySelectorAll('[role="menuitem"],nav a,nav button,aside a,aside button,.ant-menu-item,.el-menu-item,button[class*="menuSearchResult"]')).some(visible)
  const scrollOffset = Array.from(document.querySelectorAll('*')).reduce((sum, element) => sum + (element.scrollTop || 0), Math.round(window.scrollY))
  const bodyText = String(document.body?.innerText || '').slice(0, 500)
  return {
    url: location.href,
    title: document.title,
    scrollOffset,
    breadcrumbs,
    activeMenus,
    headings,
    overlays,
    tabs,
    scrollContainers,
    hasNextPage,
    hasInactiveTab,
    hasNavigation,
    unsupported: {
      iframes: frameDiagnostics.some((frame) => frame.supported !== true),
      shadowDom: closedShadowHosts.length > 0,
      frames: frameDiagnostics,
      closedShadowHosts
    },
    fingerprint: `${location.href}|${document.title}|${scrollOffset}|${scrollContainers}|${bodyText}`
  }
})

/**
 * 用 CDP 精确找出 closed shadow root 的宿主。
 * DOM 侧读不到 closed shadow root（element.shadowRoot 为 null），启发式只能猜：
 * 宿主是普通 <div>、或宿主带 light-DOM 回退内容时会漏检，失败随后被降级成普通
 * action_evidence_missing，而阶段 3 的口径要求把 shadow 缺失记成 unsupported_shadow_dom。
 * Chromium 的 CDP（DOM.getDocument + pierce:true）能看到 closed shadow root，因此这里做精确检测；
 * 非 Chromium 或 CDP 不可用时返回 null，调用方回退到启发式。
 */
export const detectClosedShadowHostsViaCdp = async (page) => {
  const context = typeof page?.context === 'function' ? page.context() : null
  if (!context || typeof context.newCDPSession !== 'function') return null
  let session
  try {
    session = await context.newCDPSession(page)
    const { root } = await session.send('DOM.getDocument', { depth: -1, pierce: true })
    const parentOf = new Map()
    const nodes = new Map()
    const framePathOf = new Map()
    const taggedName = (node) => String(node?.nodeName || '').toLowerCase()
    const pathFor = (node) => {
      const attributes = new Map()
      for (let index = 0; index < (node.attributes || []).length; index += 2) {
        attributes.set(String(node.attributes[index]), String(node.attributes[index + 1]))
      }
      const tag = taggedName(node)
      if (attributes.get('id')) return `#${attributes.get('id')}`
      if (!tag || tag === '#document') return tag
      const parent = nodes.get(parentOf.get(node.nodeId))
      const siblings = (parent?.children || []).filter((sibling) => taggedName(sibling) === tag)
      return siblings.length > 1 ? `${tag}:nth-of-type(${siblings.indexOf(node) + 1})` : tag
    }
    const walk = (node, parent, framePath) => {
      if (!node) return
      nodes.set(node.nodeId, node)
      framePathOf.set(node.nodeId, framePath)
      if (parent) parentOf.set(node.nodeId, parent.nodeId)
      for (const child of node.children || []) walk(child, node, framePath)
      // 同源 iframe / srcdoc：CDP 树里带 contentDocument，必须继续递归——否则 iframe 内的
      // closed shadow root 完全漏检，目标缺失会被降级成普通 action_evidence_missing。
      if (node.contentDocument) {
        const frameSelector = pathFor(node) || 'iframe'
        walk(node.contentDocument, node, framePath ? `${framePath} >> ${frameSelector}` : frameSelector)
      }
      // shadowRoots 里的节点才是影子树根，宿主是它当前这一层的 node
      for (const shadowRoot of node.shadowRoots || []) {
        nodes.set(shadowRoot.nodeId, shadowRoot)
        parentOf.set(shadowRoot.nodeId, node.nodeId)
        framePathOf.set(shadowRoot.nodeId, framePath)
        for (const child of shadowRoot.children || []) walk(child, shadowRoot, framePath)
      }
    }
    walk(root, null, '')
    const hosts = []
    for (const node of nodes.values()) {
      const closed = (node.shadowRoots || []).some((shadowRoot) => shadowRoot.shadowRootType === 'closed')
      if (!closed) continue
      const framePath = framePathOf.get(node.nodeId) || ''
      hosts.push({
        hostPath: pathFor(node),
        tagName: taggedName(node),
        reason: 'unsupported_shadow_dom',
        source: 'cdp',
        // 位于 iframe 内时给出 frame 前缀，便于定位与修复（空串表示主文档）。
        framePath
      })
    }
    return hosts
  } catch {
    return null
  } finally {
    await session?.detach().catch(() => {})
  }
}

export const observePageEvidence = async (page, options = {}) => {
  try {
    const metadata = await collectObservationMetadata(page)
    // 列表/翻页签名：与 DSL paginate 共用同一份源码与投影（pagination-signature.mjs）：
    // 同一份签名文本 → 两侧判定一致；loading 不进签名，单独暴露给调用方。
    const pagination = await collectPaginationSnapshot(page)
    metadata.listSignature = paginationSignature(pagination)
    metadata.paginationIndicator = String(pagination?.indicator || '')
    metadata.paginationIndicatorScope = String(pagination?.indicatorScoped === true)
    metadata.paginationLoading = pagination?.loading === true
    // 容器里只剩加载占位（判定「不得停在占位态上归因」，复核方 C3）：与 DSL 侧同一字段名。
    metadata.paginationPlaceholderOnly = pagination?.placeholderOnly === true
    const cdpClosedShadowHosts = await detectClosedShadowHostsViaCdp(page)
    if (Array.isArray(cdpClosedShadowHosts) && metadata.unsupported) {
      metadata.unsupported = {
        ...metadata.unsupported,
        // CDP 能看到整棵文档树（pierced shadow root + 同源 iframe 的 contentDocument），因此它是权威来源：
        // 启发式会误报「没有 shadow root 的自定义元素」（例如尚未 upgrade 的组件），必须被 CDP 结果剔除，
        // 否则页面上无关的「目标未观测到」会被分类成 unsupported_shadow_dom。
        shadowDom: cdpClosedShadowHosts.length > 0,
        closedShadowHosts: cdpClosedShadowHosts,
        closedShadowDetection: 'cdp'
      }
    } else if (metadata.unsupported) {
      // cdpClosedShadowHosts 为 null ⇒ 非 Chromium 或 CDP 不可用，只有启发式结论（可能漏检/误报）
      metadata.unsupported = { ...metadata.unsupported, closedShadowDetection: 'heuristic' }
    }
    const { elements, diagnostics } = await collectVisibleElementsDetailed(page, options)
    const hasBlockingOverlay = Array.isArray(metadata.overlays) && metadata.overlays.some((overlay) => overlay.blocking)
    const unsupported = {
      iframes: metadata.unsupported?.iframes === true,
      shadowDom: metadata.unsupported?.shadowDom === true,
      frames: Array.isArray(metadata.unsupported?.frames) ? metadata.unsupported.frames : [],
      closedShadowHosts: Array.isArray(metadata.unsupported?.closedShadowHosts) ? metadata.unsupported.closedShadowHosts : [],
      // cdp = 精确检测（能穿透 closed shadow root）；heuristic = 非 Chromium 回退，可能漏检。
      closedShadowDetection: metadata.unsupported?.closedShadowDetection === 'cdp' ? 'cdp' : 'heuristic',
      // shadow host 无法唯一寻址（例如同层同名兄弟 host）：子树没被采集，必须能被调用方看到。
      unresolvedShadowHosts: Array.isArray(diagnostics?.unresolvedShadowHosts) ? diagnostics.unresolvedShadowHosts : []
    }
    const observation = {
      ...metadata,
      unsupported,
      pageKey: normalizePageKey(metadata.url || page.url()),
      elements: hasBlockingOverlay ? [] : elements,
      allElements: elements
    }
    const fingerprint = metadata.fingerprint || pageFingerprint(observation)
    const stablePageFingerprint = pageFingerprint(observation)
    const dropped = Number(diagnostics?.dropped || 0)
    return {
      ...observation,
      elementsTruncated: dropped > 0
        ? {
          budget: Number(diagnostics.budget || 0),
          partitionBudget: Number(diagnostics.partitionBudget || 0),
          total: Number(diagnostics.total || 0),
          collected: Number(diagnostics.collected || 0),
          dropped,
          targeted: Number(diagnostics.targeted || 0),
          partitions: diagnostics.partitions || {}
        }
        : null,
      fingerprint,
      pageFingerprint: stablePageFingerprint
    }
  } catch (error) {
    return {
      url: typeof page?.url === 'function' ? page.url() : '',
      pageKey: '',
      observationStatus: 'failed',
      failureCategory: 'observation_failed',
      observationError: String(error?.message || error).slice(0, 200)
    }
  }
}

// Resolve the exact locator used by a step and inspect its live Playwright state.
// This deliberately refuses to select one element from an ambiguous locator.
export const resolveStepTargetEvidence = async (page, target) => {
  const parsed = parseEvidenceTarget(target)
  if (!parsed.locator) return withTargetState(parsed.description)

  let locator
  try {
    locator = resolveLocator(page, target)
  } catch {
    return withTargetState({ ...parsed.description, locator: String(target || '').trim() })
  }

  let count = 0
  try { count = await locator.count() } catch {}
  const unique = count === 1
  if (!unique) return withTargetState({ ...parsed.description, locator: String(target || '').trim(), matchedElementCount: count })

  let visible = false
  let enabled = false
  let receivesEvents = false
  try { visible = await locator.isVisible() } catch {}
  try { enabled = await locator.isEnabled() } catch { enabled = visible }
  try {
    const state = await locator.evaluate((element) => {
      // document.elementFromPoint 只返回 shadow host，open shadow root 内的元素会被误判为不可点击：
      // 这里沿 shadow root 逐层下钻，拿到真正命中的节点（closed root 无法下钻，保持 fail-closed）。
      const hitTestAt = (doc, x, y) => {
        let hit = doc.elementFromPoint(x, y)
        let guard = 0
        while (hit && hit.shadowRoot && guard < 5) {
          const inner = hit.shadowRoot.elementFromPoint(x, y)
          if (!inner || inner === hit) break
          hit = inner
          guard += 1
        }
        return hit
      }
      const style = getComputedStyle(element)
      const rect = element.getBoundingClientRect()
      const x = Math.min(rect.right - 1, Math.max(rect.left + 1, rect.left + rect.width / 2))
      const y = Math.min(rect.bottom - 1, Math.max(rect.top + 1, rect.top + rect.height / 2))
      const hit = hitTestAt(document, x, y)
      const inOwnShadow = Boolean(element.shadowRoot && hit && (hit === element.shadowRoot || element.shadowRoot.contains(hit)))
      return {
        receivesEvents: style.pointerEvents !== 'none' && Boolean(hit && (hit === element || element.contains(hit) || inOwnShadow))
      }
    })
    receivesEvents = state?.receivesEvents === true
  } catch {}
  return withTargetState({ ...parsed.description, locator: String(target || '').trim(), visible, enabled, receivesEvents, unique: true })
}

const withTargetState = (description = {}) => ({
  ...description,
  visible: description.visible === true,
  enabled: description.enabled === true,
  receivesEvents: description.receivesEvents === true,
  unique: description.unique === true
})

const parseEvidenceTarget = (target) => {
  const value = String(target || '').trim()
  const match = value.match(/^([a-zA-Z][\w-]*)=(.*)$/)
  const strategy = match?.[1]?.toLowerCase()
  const selector = match?.[2] || value
  if (strategy === 'role') {
    const roleMatch = value.match(/^role=([^\[\s]+)(?:\[name=(.*)\])?$/i)
    const namePart = roleMatch?.[2]
    const exactMatch = namePart?.match(/^(.*),\s*exact=(true|false)$/i)
    const name = namePart ? stripEvidenceQuotes((exactMatch ? exactMatch[1] : namePart).trim()) : undefined
    return {
      locator: true,
      description: {
        strategy: 'role',
        role: roleMatch?.[1] || selector,
        ...(name !== undefined ? { name } : {}),
        exact: exactMatch ? exactMatch[2].toLowerCase() === 'true' : true
      }
    }
  }
  if (strategy === 'css' || strategy === 'xpath') return { locator: true, description: { strategy, selector } }
  if (strategy === 'text') {
    const parsed = splitLocatorExactSuffix(selector)
    return {
      locator: true,
      description: { strategy: 'text', text: parsed.value, ...(parsed.exact === undefined ? {} : { exact: parsed.exact }) }
    }
  }
  if (strategy === 'testid') return { locator: true, description: { strategy: 'testId', testId: selector } }
  if (value.startsWith('//') || value.startsWith('.//') || value.startsWith('..//') || value.startsWith('(//')) {
    return { locator: true, description: { strategy: 'xpath', selector: value } }
  }
  return { locator: true, description: { strategy: 'css', selector: value } }
}

const stripEvidenceQuotes = (value) => value.startsWith('"') && value.endsWith('"')
  ? value.slice(1, -1)
  : value.startsWith("'") && value.endsWith("'")
    ? value.slice(1, -1)
    : value

const visibleLocatorCount = async (locator) => {
  try {
    const count = await locator.count()
    let visible = 0
    for (let index = 0; index < count; index += 1) {
      if (await locator.nth(index).isVisible()) visible += 1
    }
    return visible
  } catch {
    return 0
  }
}

const describeMatchedLocator = async (page, locator, candidate = {}) => {
  if (!locator) return undefined
  const count = await visibleLocatorCount(locator)
  if (count !== 1) return undefined
  const element = locator.first()
  const details = await element.evaluate((node) => {
    const text = (value) => String(value || '').trim().replace(/\s+/g, ' ')
    const escape = (value) => globalThis.CSS?.escape ? globalThis.CSS.escape(value) : String(value).replace(/[^\w-]/g, '\\$&')
    const visible = (element) => {
      const style = getComputedStyle(element)
      const rect = element.getBoundingClientRect()
      return style.display !== 'none'
        && style.visibility !== 'hidden'
        && Number(style.opacity) !== 0
        && rect.width >= 2
        && rect.height >= 2
        && rect.bottom > 0
        && rect.right > 0
        && rect.top < innerHeight
        && rect.left < innerWidth
    }
    const hitTest = (element) => {
      const rect = element.getBoundingClientRect()
      const x = Math.min(rect.right - 1, Math.max(rect.left + 1, rect.left + rect.width / 2))
      const y = Math.min(rect.bottom - 1, Math.max(rect.top + 1, rect.top + rect.height / 2))
      // 与采集路径一致：沿 open shadow root 下钻，否则 shadow 内元素会被误判为不可点击。
      let hit = document.elementFromPoint(x, y)
      let guard = 0
      while (hit && hit.shadowRoot && guard < 5) {
        const inner = hit.shadowRoot.elementFromPoint(x, y)
        if (!inner || inner === hit) break
        hit = inner
        guard += 1
      }
      const inOwnShadow = Boolean(element.shadowRoot && hit && (hit === element.shadowRoot || element.shadowRoot.contains(hit)))
      return Boolean(hit && (hit === element || element.contains(hit) || inOwnShadow))
    }
    const blockingOverlays = Array.from(document.querySelectorAll('[role="dialog"],[aria-modal="true"],.modal,.dialog,.overlay,[class*="overlay"],[class*="loading"],[class*="cookie"],[class*="guide"]'))
      .filter((element) => visible(element) && (element.getAttribute('aria-modal') === 'true' || element.getAttribute('role') === 'dialog'))
    const row = node.closest('tr,[role="row"],li,[role="listitem"]')
    const scope = node.closest('[role="dialog"],[aria-modal="true"]') ? 'dialog'
      : node.closest('[role="tablist"],.ant-tabs-nav,.el-tabs-nav') ? 'tablist'
        : node.closest('nav,aside,[role="menu"]') ? 'navigation'
          : node.closest('table') ? 'table'
            : node.closest('form') ? 'form'
              : node.closest('main') ? 'main'
                : 'document'
    return {
      pageScope: scope,
      rowContext: text(row?.innerText || row?.textContent || '').slice(0, 240),
      visible: visible(node),
      enabled: !node.matches(':disabled,[aria-disabled="true"]'),
      receivesEvents: hitTest(node),
      coveredByBlockingOverlay: Boolean(blockingOverlays.length && !blockingOverlays.some((overlay) => overlay.contains(node)) && !hitTest(node)),
      label: text(node.getAttribute('aria-label') || node.getAttribute('title') || node.innerText || node.textContent || '').slice(0, 120),
      accessibleName: text(node.getAttribute('aria-label') || node.getAttribute('title') || node.innerText || node.textContent || '').slice(0, 120),
      role: text(node.getAttribute('role') || (node.matches('button') ? 'button' : node.matches('a[href]') ? 'link' : node.matches('input') ? 'textbox' : node.tagName.toLowerCase())),
      tagName: node.tagName.toLowerCase(),
      type: node.getAttribute('type') || '',
      text: text(node.innerText || node.textContent || '').slice(0, 120),
      idValue: node.id ? escape(node.id) : '',
      testId: node.getAttribute('data-testid') || node.getAttribute('data-test-id') || '',
      cssPath: node.id ? `#${escape(node.id)}` : undefined
    }
  })
  if (!details.visible || !details.enabled || !details.receivesEvents || details.coveredByBlockingOverlay) return undefined
  let locatorForDetails
  let matchedElementCount = 0
  if (details.testId) {
    locatorForDetails = `testId=${details.testId}`
    matchedElementCount = count
  } else if (details.role && details.accessibleName) {
    const roleCount = await visibleLocatorCount(page.getByRole(details.role, { name: details.accessibleName, exact: true }))
    if (roleCount === 1) {
      // 唯一性是用 exact:true 证明的：执行串必须带上同样的 exact，否则「查询」会命中「查询用户」。
      locatorForDetails = `role=${details.role}[name="${details.accessibleName.replaceAll('\\', '\\\\').replaceAll('"', '\\"')}", exact=true]`
      matchedElementCount = roleCount
    }
  }
  if (!locatorForDetails && details.text) {
    const textCount = await visibleLocatorCount(page.getByText(details.text, { exact: true }))
    if (textCount === 1) {
      locatorForDetails = `text=${details.text}, exact=true`
      matchedElementCount = textCount
    }
  }
  if (!locatorForDetails && details.cssPath) {
    locatorForDetails = `css=${details.cssPath}`
    matchedElementCount = 1
  }
  if (!locatorForDetails && details.idValue) {
    locatorForDetails = `css=#${details.idValue}`
    matchedElementCount = 1
  }
  return locatorForDetails ? {
    pageScope: details.pageScope,
    rowContext: details.rowContext,
    visible: details.visible,
    enabled: details.enabled,
    receivesEvents: details.receivesEvents,
    coveredByBlockingOverlay: details.coveredByBlockingOverlay,
    label: details.label,
    accessibleName: details.accessibleName,
    role: details.role,
    tagName: details.tagName,
    type: details.type,
    text: details.text,
    idValue: details.idValue,
    testId: details.testId,
    cssPath: details.cssPath,
    locatorForDetails,
    matchedElementCount
  } : undefined
}

export const verifyHistoricalLocators = async (page, candidates = []) => {
  const results = []
  const normalized = Array.isArray(candidates) ? candidates : []
  const labelCounts = new Map()
  const roleLabelCounts = new Map()
  for (const candidate of normalized) {
    const key = normalizeKeyText(candidate?.label || candidate?.accessibleName || '')
    const role = normalizeKeyText(candidate?.role || '')
    if (!key) continue
    labelCounts.set(key, (labelCounts.get(key) || 0) + 1)
    if (role) roleLabelCounts.set(`${role}|${key}`, (roleLabelCounts.get(`${role}|${key}`) || 0) + 1)
  }

  for (const candidate of normalized) {
    if (!candidate?.entryId || !candidate?.locator) continue
    let resolved
    try { resolved = resolveLocator(page, candidate.locator) } catch { resolved = undefined }
    let match = await describeMatchedLocator(page, resolved, candidate)
    if (!match && candidate.role && candidate.label) {
      const roleLocator = page.getByRole(candidate.role, { name: candidate.label, exact: true })
      match = await describeMatchedLocator(page, roleLocator, candidate)
    }
    const label = normalizeKeyText(candidate.label || candidate.accessibleName || '')
    const role = normalizeKeyText(candidate.role || '')
    const duplicateRoleLabel = label && role && (roleLabelCounts.get(`${role}|${label}`) || 0) > 1
    const duplicateLabel = label && (labelCounts.get(label) || 0) > 1
    if (!match && candidate.role && candidate.label && !duplicateRoleLabel) {
      const roleLocator = page.getByRole(candidate.role, { name: candidate.label, exact: true })
      match = await describeMatchedLocator(page, roleLocator, candidate)
    }
    if (!match && candidate.label && !duplicateLabel) {
      const textLocator = page.getByText(candidate.label, { exact: true })
      match = await describeMatchedLocator(page, textLocator, candidate)
    }
    if (!match) continue
    results.push({
      entryId: candidate.entryId,
      locator: match.locatorForDetails,
      matchedElementCount: match.matchedElementCount,
      verificationMethod: candidate.locator && match.locatorForDetails === candidate.locator ? 'locator' : candidate.role && candidate.label ? 'role-name' : 'exact-label',
      pageScope: match.pageScope,
      rowContext: match.rowContext,
      visible: match.visible,
      enabled: match.enabled,
      receivesEvents: match.receivesEvents,
      coveredByBlockingOverlay: match.coveredByBlockingOverlay,
      pageKey: candidate.pageKey || match.pageKey,
      pageFingerprint: candidate.pageFingerprint || match.pageFingerprint
    })
  }
  return results
}
