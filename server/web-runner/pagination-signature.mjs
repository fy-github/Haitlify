// 列表/翻页变化的唯一签名实现。
//
// 为什么必须共用一份：IR（证据采集）与 DSL（用例回放）曾各自实现容器集合与比较语义，
// 同一页面在采集期被证明「已变化」、回放期却被判「未变化」（复核方 H3）。签名源码在这里
// 定义一次，两条路径都从它派生：采集侧用 `page.evaluate(表达式)`，DSL 侧把物化后的函数交给
// `page.waitForFunction`，语义不会漂移。
//
// 归因口径（每一条都有真实浏览器用例锁定）：
//   1) 只换行数据的真实翻页必须算「已变化」→ 容器文本摘要；
//   2) 第 9 个及之后的容器变化也要看得见 → 全部容器各取摘要（上限 500，前 100 进正文，
//      其余合成尾部摘要），签名长度与容器数量解耦；
//   3) 「页面自己在刷新」不能把死点击伪装成成功，真实异步翻页（点击 → 加载态 → 数据晚到）
//      也不能被误判成失败 → 判据分三段（12 轮对抗性复核收敛后的模型）：
//        · **加载态响应**：点击后 1s 窗口内出现加载态翻转（遮罩/占位/spinner，含点击**同拍**就出现的）
//          ⇒ 这次点击确实有响应，之后只需静默地板 `PAGINATION_LOADING_FLOOR_MS`（1s）就归因
//          （复核方 C1：3s/8s 晚到、显式 timeoutMs=5000 的用例都必须 passed）；
//        · **同步内容响应**：点击**同拍**改了 DOM（处理函数自己写的内容）⇒ 静默地板
//          `PAGINATION_CONTENT_FLOOR_MS`（2.5s）——「临时数据 → 回滚」的回滚点都落在 2s 以内，
//          会在归因前被回退检查抓住（复核方 medium-1）；而真实立即翻页（A2 timeoutMs=4000、
//          对照 _turn_ timeoutMs=6000）仍在预算内 passed；
//        · **无响应**：异步出现的纯内容变化与「相位对齐到点击的自刷新」在观测上不可区分 ⇒
//          必须静默 `PAGINATION_UNSIGNALED_QUIET_MS`（11s），自刷新周期 ≤10.5s 时永远攒不出来
//          （复核方 high-1：相位 100/250/500ms 的死点击自刷新全部 failed）；
//        · 观察窗：预算到点后，若最近仍有变化且归因还可能达成，观察到「该变化 + 所需静默」为止，
//          硬上限 `PAGINATION_SETTLE_CAP_MS`（复核方 medium-2：无加载信号的 10s 晚到在 20s 预算下
//          曾被误杀）。
//   4) 点击造成的变化又**回退**到点击前的内容（先出占位/临时数据再回滚）不算成功（复核方 C3）；
//   5) 加载中不判定变化 → loading 不进签名（`paginationSignature` 只投影
//      indicator/head/digests/tail/count），由调用方单独判断；容器内只有一句短占位文本
//      （`加载中…` 之类）也算加载态，否则 after 证据会停在占位；
//   6) 页码指示器**不参与归因**（复核方 C4）：按钮点击态 `.is-active`、轮播 `.is-active`、
//      向导 `aria-current="page"` 都可能命中通用指示器选择器，甚至类名里带 pagination；
//      归因只看列表内容是否变化（指示器仍进签名与证据，供人核对）。
//
// 注意：摘要按**原文**计算。曾有版本把 ISO 时间/时钟/UUID 归一化后再摘要，结果
// 「页间差异只由时钟形状文本构成」的真实翻页在两侧都被判未变化（复核方 X4）。
//
// 已知残余（时间证据的极限，别再用更长的窗口去「补」）：
//   · 异步内容变化与「相位对齐的自刷新」在观测上不可区分：只有「点击同拍改了 DOM」或「有加载态」
//     才能把响应与页面自身节奏分开。既不同步、又无加载态、且页面持续自刷新的真实翻页会 fail-closed；
//     反过来，自刷新周期长于 11s 时，一次自刷新也可能被算作点击生效（两个方向都是这条极限）；
//   · 点击后 ≤ 采样间隔（~10ms）内落地的自刷新会被当成同步内容变化（high-1 的相位 100ms 起全部可分）；
//   · 观察上限 `PAGINATION_SETTLE_CAP_MS`（30s）之外的变化看不到，数据晚于约 19s 的真实翻页
//     会判失败（预算由步骤/用例超时决定，可放大）。

export const PAGINATION_CONTAINER_SELECTOR = 'table tbody, [role="table"], [role="list"], .el-table__body, .ant-table-tbody, ul, ol'
export const PAGINATION_INDICATOR_SELECTOR = '[aria-current="page"], .active, .is-active, .el-pager li, .ant-pagination-item'
const PAGINATION_LOADING_SELECTOR = '[aria-busy="true"], .loading, .is-loading, .el-loading-mask, .ant-spin-spinning'
// 分页容器范围（只进快照与证据元数据，**不参与归因判定**：复核方 C4 证明类名/aria 文案都不可信）。
const PAGINATION_INDICATOR_SCOPE_SELECTOR = [
  '[class*="pagination" i]',
  '[class*="pager" i]',
  '[class*="paginate" i]',
  '.el-pager',
  '.ant-pagination',
  '[aria-label*="分页"]',
  '[aria-label*="页码"]',
  '[aria-label*="pagination" i]'
].join(', ')
// 容器里只放了一句短的「加载中」占位（复核方夹具 <li class="holder">加载中…</li>）：也算加载态，
// 否则 after 证据会停在占位文本上。用锚定+短文本，避免把正文里出现 "loading" 的列表误判成加载。
const PAGINATION_PLACEHOLDER_PATTERN = '^(加载中|载入中|正在加载|请稍候|加载中\\.\\.\\.|loading|loading\\.\\.\\.)[.\\u2026\\s]*$'

export const PAGINATION_SETTLE_FLOOR_MS = 1_000
export const PAGINATION_SETTLE_FACTOR = 1.1
/** 在这段窗口内出现的**加载态翻转**都算「这次点击的响应」（复核方 D3 相位扫描 + low-1 采样宽限）。 */
export const PAGINATION_CLICK_RESPONSE_MS = 1_000
/** 采样宽限：检测循环按 200ms 采样，960ms 的加载态翻转可能落到 1000ms 之后的第一个样本上
 *  （复核方 low-1：900/960ms 翻转的真实翻页必须 passed）。 */
export const PAGINATION_CLICK_RESPONSE_SLACK_MS = 150
/** 加载态响应（点击后出现遮罩/占位/spinner）后的静默地板。 */
export const PAGINATION_LOADING_FLOOR_MS = 1_000
/** 点击**同拍同步**内容变化（点击处理函数自己改的 DOM）后的静默地板：必须长于「临时数据再回滚」的
 *  常见窗口（复核方 medium-1：临时数据 0ms 出现、1200/1500/2000ms 回滚都必须 failed），
 *  又短于 A2 的 timeoutMs=4000 与对照 _turn_ 的 timeoutMs=6000（真实立即翻页必须 passed）。 */
export const PAGINATION_CONTENT_FLOOR_MS = 2_500
/** 点击后完全无响应时要求的静默（挡慢刷新死点击：自刷新周期 ≤10.5s 时永远攒不出来，复核方 high-1）。 */
export const PAGINATION_UNSIGNALED_QUIET_MS = 11_000
/** 观察上限（两侧同一条规则）：预算未配置/非正时用它，配置了则夹在 [地板, 上限]。
 *  30s：无遮罩的真实慢翻页（数据 6s/10s 到）走无响应路径，需要「数据到达 + 11s 长静默」才归因
 *  （复核方 medium-2：10s 单批在 20s 上限下被误杀）。 */
export const PAGINATION_SETTLE_CAP_MS = 30_000
/** 观测哨兵：点击前在 window 上打一个标记，整页导航会丢掉它 ⇒ 「被观测的列表已不在原文档」。
 *  只用于拒绝「把新文档当成翻页结果」（复核方 C-rest：点击后 400ms 跳 /blank，两侧必须同一分类）。 */
export const PAGINATION_PROBE_FLAG = '__cv4PaginationObserver'
/** Node/浏览器的定时器上限（2^31-1 ms）。更大的超时会被压成 1ms 并抛 TimeoutOverflowWarning，
 *  产生未分类错误（复核方 C-9）；两侧入口统一把超时夹在这条线以内。 */
export const PAGINATION_MAX_TIMEOUT_MS = 2_147_483_647
export const paginationSettleHorizonMs = (budget) => {
  const value = Number(budget)
  if (!Number.isFinite(value) || value <= 0) return PAGINATION_SETTLE_CAP_MS
  return Math.min(PAGINATION_SETTLE_CAP_MS, Math.max(PAGINATION_SETTLE_FLOOR_MS, value))
}

// 浏览器侧快照（自包含源码，不引用任何模块作用域符号）。
export const PAGINATION_SNAPSHOT_SOURCE = `() => {
  const digest = (value) => {
    let hash = 5381
    for (let index = 0; index < value.length; index += 1) hash = ((hash << 5) + hash + value.charCodeAt(index)) >>> 0
    return hash.toString(16)
  }
  const text = (element) => String(element && (element.innerText || element.textContent) || '').trim().replace(/\\\\\\\\s+/g, ' ')
  const containers = []
  for (const element of document.querySelectorAll(${JSON.stringify(PAGINATION_CONTAINER_SELECTOR)})) {
    const value = text(element)
    if (!value) continue
    containers.push({ digest: digest(value), text: value.slice(0, 400) })
    if (containers.length >= 500) break
  }
  const indicatorElement = Array.from(document.querySelectorAll(${JSON.stringify(PAGINATION_INDICATOR_SELECTOR)})).find((element) => text(element))
  const indicator = indicatorElement ? text(indicatorElement) : ''
  const indicatorScoped = Boolean(indicatorElement && indicatorElement.closest(${JSON.stringify(PAGINATION_INDICATOR_SCOPE_SELECTOR)}))
  const visible = (element) => {
    const style = getComputedStyle(element)
    return style.display !== 'none' && style.visibility !== 'hidden'
  }
  const spinner = Array.from(document.querySelectorAll(${JSON.stringify(PAGINATION_LOADING_SELECTOR)})).some(visible)
  const placeholder = new RegExp(${JSON.stringify(PAGINATION_PLACEHOLDER_PATTERN)}, 'i')
  const placeholderOnly = containers.length > 0 && containers.every((container) => container.text.length <= 24 && placeholder.test(container.text))
  return {
    probe: window[${JSON.stringify(PAGINATION_PROBE_FLAG)}] === 1,
    indicator,
    indicatorScoped,
    head: containers.slice(0, 4).map((item) => item.text).join(' ~ '),
    digests: containers.slice(0, 100).map((item) => item.digest).join(','),
    tail: digest(containers.slice(100).map((item) => item.digest).join(',')),
    count: containers.length,
    loading: spinner || placeholderOnly,
    // 「容器里只剩一句加载态占位」单列：判据用它来**拒绝在占位态上归因**（复核方 C3：
    // 先出占位再回滚到原样会被当成一次新的变化，最终 DOM 与点击前相同却算成功）。
    placeholderOnly
  }
}`

// 签名文本：快照的投影，loading 不在其中（否则加载态切换会被当成列表变化）。
export const paginationSignature = (snapshot) => JSON.stringify([
  String(snapshot?.indicator || ''),
  String(snapshot?.head || ''),
  String(snapshot?.digests || ''),
  String(snapshot?.tail || ''),
  Number(snapshot?.count || 0)
])

export const paginationIndicatorOf = (snapshot) => String(snapshot?.indicator || '')
export const paginationIsLoading = (snapshot) => snapshot?.loading === true
// 占位态：容器里只剩加载占位。归因不得停在占位态上（此时数据还没到，回滚型失败会被吞掉）。
export const paginationPlaceholderOnly = (snapshot) => snapshot?.placeholderOnly === true

/** 打观测哨兵（可选加固：失败只是少了这层保护，不影响归因主流程）。 */
export const armPaginationProbe = async (page) => {
  try {
    await page.evaluate((flag) => { window[flag] = 1 }, PAGINATION_PROBE_FLAG)
  } catch {
    // 页面在导航中/被关闭：忽略。
  }
}
/** 哨兵是否还在（IR 侧直接问页面，不依赖观测负载）。求值失败按「已丢失」处理：fail-closed。 */
export const isPaginationProbeAlive = async (page) => {
  try {
    return (await page.evaluate((flag) => window[flag] === 1, PAGINATION_PROBE_FLAG)) === true
  } catch {
    return false
  }
}
/** 点击前有哨兵、之后没有 ⇒ 页面整页导航离开，观测对象（被点击的列表）已不在原文档。 */
export const paginationProbeLost = (before, current) => before?.probe === true && current?.probe === false
export const paginationNavigatedAwayError = (label = '') => Object.assign(
  new Error(`pagination_not_changed: ${String(label || '').trim()} 点击后页面已导航离开，被观测的列表不在原文档里`),
  { failureType: 'pagination_not_changed', category: 'pagination_not_changed' }
)

/** 内容（不含页码指示器）是否与点击前一致——用于识别「变化又回退」。 */
export const paginationContentUnchanged = (before, current) => String(before?.head || '') === String(current?.head || '')
  && String(before?.digests || '') === String(current?.digests || '')
  && String(before?.tail || '') === String(current?.tail || '')
  && Number(before?.count || 0) === Number(current?.count || 0)

// 内容型变化的最小判别：哪几个容器（或尾部/数量）变了。
export const paginationChangedParts = (before, after) => {
  const beforeDigests = String(before?.digests || '').split(',')
  const afterDigests = String(after?.digests || '').split(',')
  const indices = []
  for (let index = 0; index < Math.max(beforeDigests.length, afterDigests.length); index += 1) {
    if (beforeDigests[index] !== afterDigests[index]) indices.push(index)
  }
  return {
    indices,
    tail: String(before?.tail || '') !== String(after?.tail || ''),
    count: Number(before?.count || 0) !== Number(after?.count || 0)
  }
}

/** 从签名文本还原出可比较的各部分（IR 侧的观测只带签名文本）。 */
export const paginationPartsOf = (signature) => {
  try {
    const parsed = JSON.parse(String(signature || ''))
    if (!Array.isArray(parsed) || parsed.length < 5) return null
    return { indicator: String(parsed[0] || ''), head: String(parsed[1] || ''), digests: String(parsed[2] || ''), tail: String(parsed[3] || ''), count: Number(parsed[4] || 0) }
  } catch {
    return null
  }
}

/**
 * 「这次变化之后又变了什么」的可比较指纹：只看相对点击前发生变化的那几个容器（外加尾部/数量），
 * 取它们当前的摘要值。同一个容器被再次改写、改回旧值、或又有容器加入变化，指纹都会变。
 */
export const paginationTrackedChange = (before, current) => {
  const changed = paginationChangedParts(before, current)
  const digests = String(current?.digests || '').split(',')
  return JSON.stringify([
    changed.indices,
    changed.tail,
    changed.count,
    changed.indices.map((index) => digests[index] || '')
  ])
}

/**
 * 变化节律跟踪器（两侧共用）。
 * - `record()`：观察到一次新的变化
 * - `requirement(responded)`：需要静默多久才能归因
 *   · 点击有响应（立即变化 ≤300ms 或加载态翻转）⇒ max(地板 1s, 1.1 × 最大间隔)
 *   · 点击无响应（慢刷新死点击、或只有晚到的内容变化）⇒ 至少 `PAGINATION_UNSIGNALED_QUIET_MS`（11s）
 * - `settled(now, responded)`：距最后一次变化已静默 ≥ requirement
 * - `describe()`：给失败信息带上节律（几次变化、间隔多大、还差多少静默）
 */
export const paginationSettleTracker = ({ startedAt = Date.now() } = {}) => ({
  startedAt,
  lastAt: 0,
  count: 0,
  gapMax: 0,
  record(now = Date.now()) {
    const previous = this.lastAt || this.startedAt
    this.gapMax = Math.max(this.gapMax, Math.max(0, now - previous))
    this.lastAt = now
    this.count += 1
  },
  requirement(responseKind = '') {
    // 加载态响应（点击后出现遮罩/占位/spinner）⇒ 响应本身就是归因证据，只需静默地板（数据再慢也照样
    // 归因：复核方 C1 的 3s/8s 晚到，以及显式 timeoutMs=5000 的 3s 用例都必须 passed）。
    if (responseKind === 'loading') return PAGINATION_LOADING_FLOOR_MS
    // 同步内容响应（点击同拍改了 DOM）⇒ 更长的地板：短于它的「临时数据→回滚」会被回退检查抓住
    // （复核方 medium-1），长于它的真实立即翻页仍在预算内通过（A2 timeoutMs=4000、对照 timeoutMs=6000）。
    if (responseKind === 'content') {
      const cadence = Math.round(PAGINATION_SETTLE_FACTOR * this.gapMax)
      return Math.max(PAGINATION_CONTENT_FLOOR_MS, Math.min(PAGINATION_SETTLE_CAP_MS, cadence))
    }
    // 无响应 ⇒ 慢刷新死点击 / 只有晚到的内容变化：至少静默 11s，且不短于观察到的变化节律（复核方 D3）。
    const cadence = Math.round(PAGINATION_SETTLE_FACTOR * this.gapMax)
    return Math.max(PAGINATION_SETTLE_FLOOR_MS, Math.min(PAGINATION_SETTLE_CAP_MS, cadence), PAGINATION_UNSIGNALED_QUIET_MS)
  },
  settled(now = Date.now(), responseKind = '') {
    return this.count > 0 && now - this.lastAt >= this.requirement(responseKind)
  },
  describe(responseKind = '') {
    const summary = responseKind === 'loading'
      ? '点击有响应（加载态翻转）'
      : responseKind === 'content'
        ? '点击有响应（同拍同步内容变化）'
        : '点击后无响应（只有晚到的变化或自刷新）'
    return `观察到 ${this.count} 次变化（最大间隔 ~${Math.round(this.gapMax)}ms，${summary}），需要静默 ~${this.requirement(responseKind)}ms 才能归因`
  }
})

// DSL 侧谓词（同样自包含）：签名相对点击前发生变化、且当前不是加载态，即视为「页面变了」。
// 归因（这次变化是不是点击造成的）不在这里做：它需要观察变化节律，放在两段式的稳定复核里。
export const PAGINATION_PREDICATE_SOURCE = `(arg) => {
  const snapshot = (${PAGINATION_SNAPSHOT_SOURCE})()
  if (snapshot.loading) return false
  const signature = JSON.stringify([snapshot.indicator, snapshot.head, snapshot.digests, snapshot.tail, snapshot.count])
  return signature !== arg.before
}`

export const materializePaginationSnapshot = () => new Function(`return (${PAGINATION_SNAPSHOT_SOURCE})`)()
export const materializePaginationPredicate = () => new Function(`return (${PAGINATION_PREDICATE_SOURCE})`)()
