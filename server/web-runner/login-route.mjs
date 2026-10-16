// 「是否离开登录路由」的唯一实现。
//
// 两份实现曾给出相反结论：Runner（DSL）只比路由，而 discovery 侧逐字比较
// pathname+search+hash，于是 hash 路由登录失败（#/login → #/login?error=1）在 DSL 判失败、
// 在采集侧判成功（HTTP 200 + authenticated=true，证据取自登录页）。
//
// 判定口径（第十四轮定稿，三处实测形状同时成立）：
//   · origin 不同即离开；
//   · 两侧都带 `#/…` 路由 ⇒ 应用是 hash 路由，**无论挂在什么前缀**（`/hash-app#/login` →
//     `/hash-app#/project` 必须判离开），比较 hash 部分；hash 里的查询串
//     （`#/login?error=1` 的 `?error=1`）不属于路由；
//   · 只有一侧带 `#/…` 且其中一侧挂在根路径（`/`、`/index.html` 等）⇒ 仍按 hash 比较
//     （`/` → `/#/app` 是路由变化，会话失效回跳门依赖这条）；
//   · 其余情况只比 pathname：一侧带 `#/…` 而另一侧没有、且是深前缀时，是**页内锚点**，
//     不是另一条路由（复核方 C-3：`/spa-pw` 与 `/spa-pw#/list` 曾被判成离开，合法采集被整轮丢弃）。

const HASH_ROUTED_PATHS = new Set(['', '/', '/index.html', '/index.htm'])

const routePartOf = (value) => {
  const parsed = new URL(value)
  const path = (parsed.pathname || '/').replace(/\/+$/, '') || '/'
  const hashRoute = parsed.hash?.startsWith('#/')
    ? (parsed.hash.slice(1).split('?')[0].split('#')[0].replace(/\/+$/, '') || '/')
    : ''
  return { origin: parsed.origin, path, hashRoute }
}

export const loginRouteOf = (value) => {
  const { path, hashRoute } = routePartOf(value)
  return hashRoute || path
}

// 采集结束的**会话门**专用：只比 origin + pathname，忽略 hash。SPA 在采集期间本来就会
// 在 hash 路由间跳（`/spa-pw#/home` → `/spa-pw#/list`），把这种页内跳转当成「离开了起始路由」
// 会把合法采集判成 login_failed（复核方 C-3）。真正回跳登录页的判定交给证据内容门。
export const hasChangedRoutePath = (fromUrl, toUrl) => {
  if (!fromUrl || !toUrl) return false
  try {
    const from = new URL(fromUrl)
    const to = new URL(toUrl)
    if (from.origin !== to.origin) return true
    const fromPath = (from.pathname || '/').replace(/\/+$/, '') || '/'
    const toPath = (to.pathname || '/').replace(/\/+$/, '') || '/'
    return fromPath !== toPath
  } catch {
    return fromUrl !== toUrl
  }
}

// 登录流程的「等到离开登录路由」——DSL 的 waitForLoginExit 与采集侧 loginIfConfigured 共用这一份实现。
// 复核方 14 轮 A4-7a / C-①：采集侧曾经是**单发**检查，点击提交后 174ms 就判「没离开登录路由」，
// 对异步提交（XHR/fetch 成功后再跳转）是竞态 —— 同一个夹具在 DSL 侧（100ms 轮询）passed，两侧口径不一致。
// `pageLike` 只需要 `.url()` 与 `.waitForTimeout(ms)`。
export const waitForLoginRouteExit = async (pageLike, loginPageUrl, budgetMs, pollMs = 100) => {
  const budget = Number.isFinite(Number(budgetMs)) ? Math.max(0, Number(budgetMs)) : 0
  const deadline = Date.now() + budget
  if (hasLeftLoginRoute(loginPageUrl, pageLike.url())) return true
  while (Date.now() < deadline) {
    await pageLike.waitForTimeout(pollMs)
    if (hasLeftLoginRoute(loginPageUrl, pageLike.url())) return true
  }
  return false
}

export const hasLeftLoginRoute = (loginPageUrl, currentPageUrl) => {
  if (!loginPageUrl || !currentPageUrl) return false
  try {
    const login = routePartOf(loginPageUrl)
    const current = routePartOf(currentPageUrl)
    if (login.origin !== current.origin) return true
    if (login.hashRoute && current.hashRoute) return login.hashRoute !== current.hashRoute
    const rootMounted = HASH_ROUTED_PATHS.has(login.path) || HASH_ROUTED_PATHS.has(current.path)
    if (rootMounted && (login.hashRoute || current.hashRoute)) return login.hashRoute !== current.hashRoute
    return login.path !== current.path
  } catch {
    return loginPageUrl !== currentPageUrl
  }
}
