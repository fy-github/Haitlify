import { resolveLocator, urlMatchesExpected, waitForUrlMatch } from './locator-utils.mjs'
import { hasLeftLoginRoute, waitForLoginRouteExit } from './login-route.mjs'
import {
  armPaginationProbe,
  materializePaginationPredicate,
  materializePaginationSnapshot,
  PAGINATION_CLICK_RESPONSE_MS,
  PAGINATION_CLICK_RESPONSE_SLACK_MS,
  PAGINATION_MAX_TIMEOUT_MS,
  PAGINATION_SETTLE_CAP_MS,
  paginationContentUnchanged,
  paginationIsLoading,
  paginationNavigatedAwayError,
  paginationPlaceholderOnly,
  paginationProbeLost,
  paginationSettleHorizonMs,
  paginationSettleTracker,
  paginationSignature,
  paginationTrackedChange
} from './pagination-signature.mjs'

// DSL 动作词表：executeStep 只有 if 链，未命中的动作名会静默 return（整步记 passed）。
// 未知动作名（拼写错误、平台放行但 runner 未实现的别名）必须 fail-closed 并分类。
const STEP_ACTION_ALIASES = {
  scroll_into_view: 'scrollIntoView',
  wait_for_visible: 'waitForVisible',
  wait_for_url: 'waitForUrl',
  wait_for_text: 'waitForText',
  wait_for_hidden: 'waitForHidden',
  wait_for_network_idle: 'waitForNetworkIdle',
  assert_visible: 'assertVisible',
  assert_hidden: 'assertHidden',
  assert_text: 'assertText',
  assert_url: 'assertUrl',
  assert_enabled: 'assertEnabled',
  assert_disabled: 'assertDisabled'
}
const SUPPORTED_STEP_ACTIONS = new Set([
  'manual', 'goto', 'navigate', 'click', 'input', 'fill', 'select', 'hover', 'paginate', 'screenshot',
  'scrollIntoView', 'waitForVisible', 'waitForUrl', 'waitForText', 'waitForHidden', 'waitForNetworkIdle',
  'assertVisible', 'assertHidden', 'assertText', 'assertUrl',
  'assertEnabled', 'assertDisabled'
])

export const canonicalStepAction = (action) => {
  const raw = String(action || '').trim()
  if (!raw || SUPPORTED_STEP_ACTIONS.has(raw)) return raw
  return STEP_ACTION_ALIASES[raw.toLowerCase()] || raw
}

// 浏览器侧观测失败（evaluate 抛错，例如页面在归因前导航离开）必须原样分类为 observation_failed：
// 复核方 medium-3 —— 此前 DSL 侧这条例外没有分类（category 为空），而 IR 侧是 observation_failed，
// 同一故障两侧分类不一致，平台的失败分类/重试策略会因此分叉。
const evaluatePaginationSnapshot = async (page) => {
  try {
    return await page.evaluate(materializePaginationSnapshot())
  } catch (error) {
    throw Object.assign(new Error(`observation_failed: ${error?.message || error}`), {
      failureType: 'observation_failed',
      category: 'observation_failed',
      cause: error
    })
  }
}

export const executeStep = async (page, step, runtime = {}) => {
  const canonicalAction = canonicalStepAction(step?.action)
  if (!SUPPORTED_STEP_ACTIONS.has(canonicalAction)) {
    throw Object.assign(new Error(`Unsupported step action: ${String(step?.action || '(empty)')}`), {
      failureType: 'unsupported_action',
      category: 'unsupported_action'
    })
  }
  step = { ...step, action: canonicalAction }
  if (step.action === 'manual') {
    return
  }
  if (step.action === 'goto' || step.action === 'navigate') {
    const targetUrl = resolveRuntimeUrl(step.target, runtime)
    // 空目标不能被静默替换成环境入口：Playwright 的 baseURL 会把 '' 解析成 baseUrl 并记 passed，
    // 等于「没指定地址」被当成「打开环境首页」——与 assertUrl/assertText 空期望恒真是同一类缺陷。
    if (!String(targetUrl || '').trim()) {
      throw Object.assign(new Error(`${step.action} requires a target URL`), { failureType: 'invalid_action_ir', category: 'invalid_action_ir' })
    }
    await page.goto(targetUrl, {
      waitUntil: runtime.waitUntil || 'domcontentloaded',
      ...buildStepTimeout(step)
    })
    if (step.id === 'setup-login-goto') {
      runtime.loginPageUrl = page.url()
    }
    return
  }
  if (step.action === 'click') {
    await (await resolveClickLocator(page, step)).click(buildStepTimeout(step))
    return
  }
  if (step.action === 'input' || step.action === 'fill') {
    await resolveLocator(page, step.target).fill(resolveStepValue(step.value, runtime), buildStepTimeout(step))
    return
  }
  if (step.action === 'select') {
    await resolveLocator(page, step.target).selectOption(resolveStepValue(step.value, runtime), buildStepTimeout(step))
    return
  }
  if (step.action === 'hover') {
    await resolveLocator(page, step.target).hover(buildStepTimeout(step))
    return
  }
  if (step.action === 'scrollIntoView') {
    const locator = resolveLocator(page, step.target)
    await locator.scrollIntoViewIfNeeded(buildStepTimeout(step))
    await locator.waitFor({ state: 'visible', ...buildStepTimeout(step) })
    return
  }
  if (step.action === 'paginate') {
    const locator = resolveLocator(page, step.target)
    // 观测哨兵：整页导航会丢掉这个标记 ⇒ 被观测的列表已不在原文档，不能把新文档算成翻页结果
    // （复核方 C-rest：点击后 400ms 跳 /blank，DSL 与 IR 必须同一分类、不能 fail-open）。
    await armPaginationProbe(page)
    const before = await evaluatePaginationSnapshot(page)
    const clickAt = Date.now()
    // 时限归一（复核方 C-9）：超大/非有限值会让 Node 定时器溢出（>2^31-1 被压成 1ms）并产生未分类错误；
    // 翻页自身的操作（点击与诊断谓词）封顶在观察上限，语义与观察窗一致。
    const opTimeout = (() => {
      const value = Number(buildStepTimeout(step).timeout)
      if (!Number.isFinite(value) || value <= 0) return PAGINATION_SETTLE_CAP_MS
      return Math.min(Math.max(1_000, value), PAGINATION_MAX_TIMEOUT_MS)
    })()
    await locator.click({ timeout: opTimeout })
    // 点击响应判定（复核方 12 轮收敛的三段模型）：
    //   · 加载态响应：点击后 1s 窗口内加载态翻转（含点击**同拍**就出现的遮罩/占位/spinner）；
    //   · 同步内容响应：点击**同拍**改了 DOM（处理函数自己写的内容）—— 异步出现的纯内容变化与
    //     「相位对齐到点击的自刷新」在观测上不可区分，只能按无响应处理（复核方 high-1：相位
    //     100/250/500ms 的死点击自刷新必须 failed；A2 与对照 _turn_ 的同步翻页必须 passed）；
    //   · 两者都没有 ⇒ 无响应，需要 11s 长静默。
    // 同拍快照紧跟在 click() 返回之后（一次 evaluate 往返 ~2–15ms），不与谓词等待串行
    // （复核方 C1：把响应探测放到谓词之后会错过遮罩翻转，3s/8s 晚到全失败）。
    const budget = Number.isFinite(step.timeoutMs) && Number(step.timeoutMs) > 0
      ? Number(step.timeoutMs)
      : (typeof page.getDefaultTimeout === 'function' ? page.getDefaultTimeout() : undefined)
    const horizon = clickAt + paginationSettleHorizonMs(budget)
    const justAfter = await evaluatePaginationSnapshot(page)
    if (paginationProbeLost(before, justAfter)) {
      // 整页导航：新文档里出现了不同的列表内容 ⇒ 这是一次真实的服务端翻页（复核方 C-6：href=?page=2 型
      // 必须在两侧 passed）；没内容（跳走、刷新回原样）⇒ 不把新文档当成翻页结果。
      if (Number(justAfter?.count || 0) > 0 && !paginationContentUnchanged(before, justAfter)) return
      throw paginationNavigatedAwayError(String(step.target || '').trim())
    }
    let responseKind = ''
    if (paginationIsLoading(justAfter) !== paginationIsLoading(before)) responseKind = 'loading'
    else if (!paginationContentUnchanged(before, justAfter)) responseKind = 'content'
    const predicateWait = page
      .waitForFunction(materializePaginationPredicate(), { before: paginationSignature(before) }, { timeout: opTimeout })
      .then(() => ({ ok: true, error: null }), (error) => ({ ok: false, error }))
    // 归因（复核方 D1/D3 + C1–C4，12 轮收敛后的三段模型）：响应类型决定静默地板，两侧共用同一个 tracker。
    //   · 加载态响应 ⇒ 地板 1s（数据 3s/8s 晚到必须 passed）；
    //   · 同步内容响应 ⇒ 地板 2.5s（短于它的「临时数据→回滚」会被回退检查抓住，medium-1）；
    //   · 无响应 ⇒ 至少 11s（自刷新周期 ≤10.5s 永远攒不出来，high-1）；
    //   · 内容又回退到点击前 ⇒ 立即失败（C3）。
    // 监测循环与谓词等待**并行**：谓词在加载态下不会满足，串行等它就看不到「占位 → 回滚」这段过程，
    // DSL 侧就会以超时（而不是「回退」）失败，与两侧一致的 C3 要求不符（真实 Chromium 用例实测）。
    const settle = paginationSettleTracker({ startedAt: clickAt })
    let tracked = null
    let changed = false
    let loadingNow = paginationIsLoading(before)
    let lastSeen = before
    let predicateOutcome = null
    predicateWait.then((outcome) => { predicateOutcome = outcome })
    // 观察何时结束（两侧同一条规则，复核方 medium-2）：预算到点后，若最近仍有变化且归因还可能达成
    // （距该变化还差静默），继续观察到「该变化 + 所需静默」为止；没有任何变化则按预算结束。
    // 硬上限仍是 PAGINATION_SETTLE_CAP_MS（30s），保证任何页面都能终止。
    const observationEnded = () => {
      if (Date.now() >= clickAt + PAGINATION_SETTLE_CAP_MS) return true
      if (Date.now() < horizon) return false
      return !(settle.count > 0 && Date.now() - settle.lastAt < settle.requirement(responseKind))
    }
    for (;;) {
      // 验收必须先于「观察结束」判定：settled 用 `>=`、结束判定用 `<`，两者在同一拍翻转，先判结束
      // 就等于永远走不到验收（复核方 medium-2 实测：无遮罩真实慢翻页跑到「数据到达 + 需静默」仍报 failed）。
      if (changed && !loadingNow && settle.settled(Date.now(), responseKind)) return
      if (observationEnded()) break
      const current = await evaluatePaginationSnapshot(page)
      if (paginationProbeLost(before, current)) {
        // 整页导航：新文档里出现了不同的列表内容 ⇒ 真实的服务端翻页（复核方 C-6），归因并返回；
        // 否则（跳走、刷新回原样、页面自己清了标记）⇒ 不把新文档当成翻页结果。
        if (Number(current?.count || 0) > 0 && !paginationContentUnchanged(before, current)) return
        throw paginationNavigatedAwayError(String(step.target || '').trim())
      }
      lastSeen = current
      // 加载态翻转（含采样宽限）⇒ 升级为加载态响应（复核方 low-1：900/960ms 的遮罩翻转必须 passed）。
      if (responseKind !== 'loading'
        && Date.now() - clickAt <= PAGINATION_CLICK_RESPONSE_MS + PAGINATION_CLICK_RESPONSE_SLACK_MS
        && paginationIsLoading(current) !== paginationIsLoading(before)) {
        responseKind = 'loading'
      }
      // 加载态（含容器内的短占位文本）：不算「内容变化」，但要记住页面确实变过 —— 随后回到点击前的
      // 内容就是回滚（C3）；占位本身也永远不能成为归因时刻（下一行的 returned 判断）。
      loadingNow = paginationIsLoading(current)
      if (loadingNow) {
        if (paginationPlaceholderOnly(current)) changed = true
      } else if (!paginationContentUnchanged(before, current)) {
        // 只认**内容**变化：指示器（点击态/轮播/向导步骤，即使类名带 pagination）单独变化不是翻页证据
        // （复核方 C4：删掉「指示器一变就归因」的快捷路径，A2 的分页器用例靠内容变化+点击响应通过）。
        const next = paginationTrackedChange(before, current)
        if (next !== tracked) {
          tracked = next
          changed = true
          settle.record(Date.now())
        }
      } else if (changed) {
        // 变化过又回到点击前的状态（先出占位/临时数据再回滚）：不算翻页成功（C3）。
        throw Object.assign(
          new Error(`pagination_not_changed: ${String(step.target || '').trim()} 点击后列表变化又回退到点击前的状态，最终 DOM 与点击前相同`),
          { failureType: 'pagination_not_changed', category: 'pagination_not_changed' }
        )
      }
      if (predicateOutcome && !predicateOutcome.ok) {
        const error = predicateOutcome.error
        const isTimeout = error?.name === 'TimeoutError' || /Timeout \d+ms exceeded/.test(String(error?.message || ''))
        // 非超时错误（例如浏览器侧谓词抛错）立即失败并原样分类，别被后面的「未变化」盖掉。
        if (!isTimeout) {
          throw Object.assign(error instanceof Error ? error : new Error(String(error)), {
            failureType: error?.failureType || 'observation_failed',
            category: error?.category || error?.failureType || 'observation_failed'
          })
        }
      }
      // 归因需要：列表内容确实变过、当前不在加载/占位态、静默达到要求。谓词是同一判据的浏览器侧实现，
      // 用于失败诊断与交叉验证，但**不阻塞**验收：谓词的超时预算是步骤预算，而观察窗可能（按 medium-2）
      // 延伸到预算之后；用谓词的 pending/超时挡住验收会把「数据 10s 到 + 11s 静默」误杀。
      if (changed && !loadingNow && settle.settled(Date.now(), responseKind)) return
      await page.waitForTimeout(200)
    }
    // 谓词可能刚刚超时（预算与观察上限常常相等），给它一个很短的宽限期，好让失败信息带上原始超时。
    if (!predicateOutcome) {
      predicateOutcome = await Promise.race([
        predicateWait,
        new Promise((resolve) => setTimeout(() => resolve(null), 300))
      ])
    }
    // 回退消息优先（复核方 C-8，14 轮 low-③ 扩展）：最后一次采样已回到点击前的状态时，用「回退」而不是
    // 谓词超时措辞，与 IR 侧同一个语义、同一个分类。`predicateOutcome.ok` 说明浏览器侧确实观测到过变化 ——
    // 变化与回退落在同一个采样间隔内（如 1000ms 变化 + 同拍 1000ms 回退）时采样看不到「变化过」的中间态，
    // 只有谓词知道它发生过；这种情况同样是回退，不能落到原始 Playwright 超时文案。
    if (paginationContentUnchanged(before, lastSeen) && (changed || predicateOutcome?.ok === true)) {
      throw Object.assign(
        new Error(`pagination_not_changed: ${String(step.target || '').trim()} 点击后列表变化又回退到点击前的状态，最终 DOM 与点击前相同`),
        { failureType: 'pagination_not_changed', category: 'pagination_not_changed' }
      )
    }
    if (predicateOutcome && !predicateOutcome.ok) {
      const error = predicateOutcome.error
      const isTimeout = error?.name === 'TimeoutError' || /Timeout \d+ms exceeded/.test(String(error?.message || ''))
      if (!isTimeout) {
        throw Object.assign(error instanceof Error ? error : new Error(String(error)), {
          failureType: error?.failureType || 'observation_failed',
          category: error?.category || error?.failureType || 'observation_failed'
        })
      }
      throw Object.assign(new Error(`pagination_not_changed: ${error instanceof Error ? error.message : String(error)}`), {
        failureType: 'pagination_not_changed',
        category: 'pagination_not_changed',
        cause: error
      })
    }
    throw Object.assign(
      new Error(`pagination_not_changed: ${String(step.target || '').trim()} 点击后列表未变化或仍在自行变化（页面自更新），不归因于本次点击（${settle.describe(responseKind)}）`),
      { failureType: 'pagination_not_changed', category: 'pagination_not_changed' }
    )
  }
  if (step.action === 'waitForVisible') {
    if (isLoginSuccessWithoutTarget(step, runtime)) return waitForLoginExit(page, runtime, step)
    try {
      await resolveLocator(page, step.target).waitFor({ state: 'visible', ...buildStepTimeout(step) })
    } catch (error) {
      if (canTolerateLoginSuccessTimeout(step, runtime, page)) return
      throw error
    }
    return
  }
  if (step.action === 'waitForUrl') {
    if (isLoginSuccessWithoutTarget(step, runtime)) return waitForLoginExit(page, runtime, step)
    try {
      await waitForUrlMatch(page, resolveRuntimeUrl(step.target, runtime), buildStepTimeout(step))
    } catch (error) {
      if (canTolerateLoginSuccessTimeout(step, runtime, page)) return
      throw error
    }
    return
  }
  if (step.action === 'waitForText') {
    // 动态页：等目标元素自己出现指定文本，而不是「元素在就算过」。
    const expected = String(resolveStepValue(step.value, runtime) ?? '').trim()
    if (!expected) {
      throw Object.assign(new Error('waitForText 需要一个非空期望文本'), { failureType: 'invalid_step' })
    }
    await resolveLocator(page, step.target).filter({ hasText: expected }).first()
      .waitFor({ state: 'visible', ...buildStepTimeout(step) })
    return
  }
  if (step.action === 'waitForHidden') {
    await resolveLocator(page, step.target).waitFor({ state: 'hidden', ...buildStepTimeout(step) })
    return
  }
  if (step.action === 'waitForNetworkIdle') {
    // 只在明确需要时用：网络空闲是「无请求 500ms」，对长轮询页面会一直不空闲。
    await page.waitForLoadState('networkidle', buildStepTimeout(step))
    return
  }
  if (step.action === 'assertVisible') {
    await resolveLocator(page, step.target).waitFor({ state: 'visible', ...buildStepTimeout(step) })
    return
  }
  if (step.action === 'assertHidden') {
    await resolveLocator(page, step.target).waitFor({ state: 'hidden', ...buildStepTimeout(step) })
    return
  }
  if (step.action === 'assertEnabled' || step.action === 'assertDisabled') {
    const locator = resolveLocator(page, step.target)
    // 与 assertVisible 同口径：控件必须在页面上真实可见，隐藏元素的 disabled 状态不构成证据。
    await locator.waitFor({ state: 'visible', ...buildStepTimeout(step) })
    const enabled = await locator.isEnabled()
    if (step.action === 'assertEnabled' && !enabled) {
      throw Object.assign(new Error('assertEnabled failed: target is disabled'), { failureType: 'assertion_failed', category: 'assertion_failed' })
    }
    if (step.action === 'assertDisabled' && enabled) {
      throw Object.assign(new Error('assertDisabled failed: target is enabled'), { failureType: 'assertion_failed', category: 'assertion_failed' })
    }
    return
  }
  if (step.action === 'assertText') {
    const text = await resolveLocator(page, step.target).textContent(buildStepTimeout(step))
    const expectedText = resolveStepValue(step.value, runtime)
    // 空期望值必须 fail-closed：`text.includes('')` 恒真，断言会静默“通过”。
    if (!String(expectedText || '').trim()) {
      throw Object.assign(new Error('assertText failed: expected text is empty'), { failureType: 'assertion_failed' })
    }
    if (!text?.includes(expectedText)) {
      throw new Error(`assertText failed: expected "${expectedText}" in "${text || ''}"`)
    }
    return
  }
  if (step.action === 'assertUrl') {
    const currentUrl = page.url()
    const expectedUrl = resolveRuntimeUrl(step.target, runtime)
    if (!String(expectedUrl || '').trim()) {
      throw Object.assign(new Error('assertUrl failed: expected URL is empty'), { failureType: 'assertion_failed', category: 'assertion_failed' })
    }
    // 子串匹配是 fail-open：编译产物断言绝对地址（'http://host/list'），而它是同源 'http://host/listing'
    // 的前缀子串 → 在错误路由上静默通过。与 IR 路径统一走 urlMatchesExpected（origin + 路由精确比较）。
    if (!urlMatchesExpected(currentUrl, expectedUrl)) {
      throw Object.assign(new Error(`assertUrl failed: expected "${expectedUrl}" in "${currentUrl}"`), { failureType: 'assertion_failed', category: 'assertion_failed' })
    }
    return
  }
  if (step.action === 'screenshot') {
    await page.screenshot({ fullPage: true })
    return
  }
}

const isLoginSuccessWithoutTarget = (step, runtime = {}) =>
  step?.id === 'setup-login-success' && !String(resolveRuntimeValueForLogin(step, runtime)).trim()

const resolveRuntimeValueForLogin = (step, runtime = {}) => substituteRuntimeVariables(String(step?.target || ''), runtime.variables || {})

// 没有显式成功判定时的可判定条件：页面必须离开登录路由。之前这里靠「步骤失败 + 宽松吞掉」兜底，
// 而宽松判定在 runtime.loginPageUrl 缺失时直接成立（无法证明还在登录页就当成功），登录失败会静默继续。
const waitForLoginExit = async (page, runtime = {}, step = {}) => {
  const loginPageUrl = String(runtime.loginPageUrl || '').trim()
  if (!loginPageUrl) {
    throw Object.assign(new Error('login success cannot be verified: setup-login-goto did not record the login page URL'), { failureType: 'assertion_failed', category: 'assertion_failed' })
  }
  const timeout = Number(step.timeoutMs) > 0 ? Number(step.timeoutMs) : 5_000
  const deadline = Math.max(250, Math.min(30_000, timeout))
  // 等待实现与采集侧共用 login-route.mjs 的 waitForLoginRouteExit（复核方 14 轮 C-①：两侧口径必须同源）。
  const left = await waitForLoginRouteExit(page, loginPageUrl, deadline)
  if (left) return
  throw Object.assign(new Error(`login did not leave the login route: still on "${page.url()}"`), { failureType: 'assertion_failed', category: 'assertion_failed' })
}

// 配置了成功判定但判定超时：只有当登录页地址已知、且页面确实离开登录路由时才容忍
// （登录页地址未知 ⇒ 无法判定 ⇒ 必须抛出，不能默认成功）。
const canTolerateLoginSuccessTimeout = (step, runtime = {}, page) =>
  step?.id === 'setup-login-success'
  && String(runtime.loginPageUrl || '').trim() !== ''
  && hasLeftLoginRoute(runtime.loginPageUrl, page.url())

// 「是否离开登录路由」只按路由判定（唯一实现在 login-route.mjs）：pathname（或 hash 路由）
// 不同才算离开。之前把 query/hash 的任意差异都当成「已离开」，登录失败后跳 /login?error=1
// （或表单 GET 回同一路径带参数）会被判成功；宁可保守失败（配了 successTarget 时另走显式判定），
// 也不能把失败吞成通过。

export const formatStepFailureMessage = (step, error, failedRequests = [], runtime = {}) => {
  const originalMessage = error instanceof Error ? error.message : '步骤执行失败'
  if (step?.id !== 'setup-login-success') return originalMessage
  const loginRequestFailure = failedRequests.find((item) => /\/auth\/login(?:\b|[/?#])/i.test(item))
  const localNetworkAccessFailure = failedRequests.some((item) => /local address space|private network|local network|ERR_FAILED/i.test(item))
  return [
    localNetworkAccessFailure ? '浏览器本地网络访问被拦截：请使用 APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS=true 启动可信 Web runner，或将认证 API 配置为同源/公开网络。' : '',
    loginRequestFailure ? `登录请求失败：${loginRequestFailure}` : '',
    formatRuntimeLoginDiagnostic(runtime.login),
    `原始等待错误：${originalMessage}`
  ].filter(Boolean).join('\n')
}

const formatRuntimeLoginDiagnostic = (login = {}) => {
  if (!login?.enabled) return '自动登录未启用：当前用例需要认证时会被登录页拦截。'
  return [
    '自动登录配置：',
    `loginUrl=${login.loginUrl || ''}`,
    `usernameLocator=${login.usernameLocator || ''}`,
    `passwordLocator=${login.passwordLocator || ''}`,
    `submitLocator=${login.submitLocator || ''}`,
    `successType=${login.successType || 'url'}`,
    `successTarget=${login.successTarget || ''}`
  ].join(' ')
}

export const buildBrowserContextOptions = (runtime = {}, item = {}) => {
  const extraHTTPHeaders = buildExtraHTTPHeaders(runtime)
  return {
    ...(runtime.baseUrl ? { baseURL: String(runtime.baseUrl).trim() } : {}),
    viewport: normalizeViewport(runtime.viewport || item.viewport),
    ...(Object.keys(extraHTTPHeaders).length ? { extraHTTPHeaders } : {})
  }
}

export const buildBrowserContextCookies = (runtime = {}) => {
  const cookieHeader = headerValue(runtime.extraHeaders, 'cookie')
  if (!cookieHeader || !runtime.baseUrl) return []
  const cookieUrls = [
    String(runtime.baseUrl).trim(),
    ...(Array.isArray(runtime.cookieOrigins) ? runtime.cookieOrigins : [])
  ]
    .filter((item) => /^https?:\/\//i.test(item))
    .map((item) => new URL(item).origin)
  const uniqueCookieUrls = Array.from(new Set(cookieUrls))
  if (!uniqueCookieUrls.length) return []

  return [
    ...String(cookieHeader || '')
      .split(';')
      .map((part) => part.trim())
      .filter(Boolean)
      .flatMap((cookie) => expandCookieForUrls(cookie, uniqueCookieUrls))
  ]
}

const expandCookieForUrls = (cookie, urls) => {
  const separatorIndex = cookie.indexOf('=')
  const parsedCookie = {
    name: separatorIndex > -1 ? cookie.slice(0, separatorIndex).trim() : cookie,
    value: separatorIndex > -1 ? cookie.slice(separatorIndex + 1).trim() : ''
  }
  return urls.map((url) => ({ ...parsedCookie, url }))
    .filter((cookie) => cookie.name)
}

export const buildRunnableSteps = (item = {}, runtime = {}) => {
  const setupSteps = shouldUseEnvironmentLogin(item, runtime)
    ? buildLoginSetupSteps(runtime)
    : []
  const testSteps = buildCaseTestSteps(item, runtime)
  return [...setupSteps, ...testSteps]
}

export const buildCaseTestSteps = (item = {}, runtime = {}) =>
  buildTestSteps(item, runtime).map((step) => ({
    ...step,
    phase: 'test'
  }))

const buildTestSteps = (item = {}, runtime = {}) => {
  const steps = Array.isArray(item.steps) ? item.steps : []
  const caseStartUrl = String(item.startUrl || '').trim()
  const startUrl = caseStartUrl || (
    shouldUseEnvironmentLogin(item, runtime)
      ? ''
      : String(runtime.baseUrl || '').trim()
  )
  if (!startUrl || steps[0]?.action === 'goto') return steps
  return [
    {
      id: 'open-start-url',
      name: caseStartUrl ? '打开用例访问地址' : '打开运行环境入口',
      action: 'goto',
      targetType: 'url',
      target: startUrl
    },
    ...steps
  ]
}

export const buildLoginSetupSteps = (runtime = {}) => {
  const login = runtime.login || {}
  if (!login.enabled) return []
  return [
    {
      id: 'setup-login-goto',
      name: '打开登录页',
      action: 'goto',
      targetType: 'url',
      target: String(login.loginUrl || '').trim(),
      phase: 'setup'
    },
    {
      id: 'setup-login-username',
      name: '输入登录账号',
      action: 'input',
      targetType: 'locator',
      target: String(login.usernameLocator || '').trim(),
      value: String(login.username || ''),
      phase: 'setup'
    },
    {
      id: 'setup-login-password',
      name: '输入登录密码',
      action: 'input',
      targetType: 'locator',
      target: String(login.passwordLocator || '').trim(),
      value: String(login.password || ''),
      phase: 'setup'
    },
    {
      id: 'setup-login-submit',
      name: '提交登录',
      action: 'click',
      targetType: 'locator',
      target: String(login.submitLocator || '').trim(),
      scopeLocator: String(login.passwordLocator || '').trim(),
      phase: 'setup'
    },
    {
      id: 'setup-login-success',
      name: '等待登录成功',
      action: login.successType === 'visible' ? 'waitForVisible' : 'waitForUrl',
      targetType: login.successType === 'visible' ? 'locator' : 'url',
      target: String(login.successTarget || '').trim(),
      phase: 'setup'
    }
  ]
}

export const buildLogoutTeardownSteps = (runtime = {}) => {
  const logout = runtime.logout || {}
  if (!logout.enabled || !Array.isArray(logout.steps)) return []
  return logout.steps.map((step, index) => ({
    ...step,
    id: String(step.id || `teardown-${index + 1}`),
    name: String(step.name || `退出登录步骤 ${index + 1}`),
    target: String(step.target || '').trim(),
    phase: 'teardown'
  }))
}

export const normalizeViewport = (value) => {
  const [width, height] = String(value || '1440x900').split('x').map(Number)
  return Number.isFinite(width) && Number.isFinite(height)
    ? { width, height }
    : { width: 1440, height: 900 }
}

const buildExtraHTTPHeaders = (runtime = {}) => {
  const headers = {}
  for (const [name, value] of Object.entries(runtime.extraHeaders || {})) {
    const normalizedName = String(name).trim()
    if (normalizedName.toLowerCase() === 'cookie' || !isValidHeaderName(normalizedName)) continue
    headers[normalizedName] = String(value)
  }
  if (runtime.authToken && !headerValue(headers, 'authorization')) {
    headers.Authorization = `Bearer ${runtime.authToken}`
  }
  return headers
}

const isValidHeaderName = (name) =>
  /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/.test(name)

// 默认参数只对 undefined 生效：显式 null（平台 DTO 里未配置的可选字段就是 null）会让
// Object.entries 抛 TypeError，整个取证请求被降级成「runner 不可用」。这里显式兜底空对象。
const headerValue = (headers, name) => {
  const entry = Object.entries(headers || {}).find(([key]) => key.toLowerCase() === name)
  return entry?.[1]
}

const shouldUseEnvironmentLogin = (item = {}, runtime = {}) =>
  Boolean(runtime.login?.enabled) && item.authenticationMode !== 'skip'

const buildStepTimeout = (step = {}) =>
  Number.isFinite(step.timeoutMs) && Number(step.timeoutMs) > 0
    ? { timeout: Math.max(1, Number(step.timeoutMs)) }
    : {}

const resolveStepValue = (value, runtime = {}) => substituteRuntimeVariables(String(value || ''), runtime.variables || {})

const substituteRuntimeVariables = (value, variables = {}) =>
  String(value).replace(/{{\s*([\w.-]+)\s*}}/g, (_match, variableName) => {
    if (!Object.hasOwn(variables, variableName)) {
      // 缺变量必须带分类：裸 Error 会让整个 run 以未分类错误冒泡（HTTP 层只能退化成通用失败）。
      throw Object.assign(new Error(`Missing runtime variable: ${variableName}`), { failureType: 'invalid_action_ir', category: 'invalid_action_ir' })
    }
    return String(variables[variableName] ?? '')
  })

const resolveRuntimeUrl = (target, runtime = {}) => {
  const resolvedTarget = substituteRuntimeVariables(String(target || '').trim(), runtime.variables || {})
  if (!resolvedTarget) return resolvedTarget
  if (/^[a-z]+:\/\//i.test(resolvedTarget)) return resolvedTarget
  const baseUrl = String(runtime.baseUrl || '').trim()
  if (!baseUrl) return resolvedTarget
  try {
    const parsedBaseUrl = new URL(baseUrl)
    if (resolvedTarget.startsWith('#/')) {
      parsedBaseUrl.hash = resolvedTarget.slice(1)
      return parsedBaseUrl.toString()
    }
    if (resolvedTarget.startsWith('/') && !resolvedTarget.startsWith('/#/') && parsedBaseUrl.hash.startsWith('#/')) {
      parsedBaseUrl.hash = resolvedTarget
      return parsedBaseUrl.toString()
    }
    return new URL(resolvedTarget, baseUrl).toString()
  } catch {
    return resolvedTarget
  }
}

const resolveClickLocator = async (page, step) => {
  const locator = resolveLocator(page, step.target)
  if (step.id !== 'setup-login-submit' || !step.scopeLocator) return locator
  if (await locator.count() <= 1) return locator

  const scopeAnchor = resolveLocator(page, step.scopeLocator)
  if (await scopeAnchor.count() !== 1) return locator
  const form = scopeAnchor.locator('xpath=ancestor::form[1]')
  if (await form.count() !== 1) return locator
  const scopedLocator = resolveLocator(form, step.target)
  return await scopedLocator.count() === 1 ? scopedLocator : locator
}
