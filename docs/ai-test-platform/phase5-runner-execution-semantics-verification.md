> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# 阶段 5：Runner 执行语义修正 验收记录

> 验收基线：`docs/reviews/2026-09-09-ai-assisted-web-automation-stabilization-plan.md` 阶段 5（608–700 行）：让运行时行为符合 DSL 语义，并充分利用 Playwright 的自动等待和 actionability。

## Status

首轮实现完成，**已通过第十八轮独立对抗性复核（PASS，复核 commit `38af44e`；A/B 段逐条成立，无新增 high/medium）**。

## 一、实现位置

- `page-locator-discovery.mjs` `clickMatchingNavigation`（探索期菜单导航）：**DOM 只用于找候选与打分，点击一律走 Playwright locator**（`getByRole(name, exact:true)` → `getByText(exact:true)` 依次尝试，只在 count()==1 且可见时点击）；构造不出唯一定位器时**不点击**（等价于未命中，不再有原生 `element.click()` 绕过遮挡/禁用状态）。
- 5.2/5.3/5.4 的既有实现（本阶段核验，未改动）：`locator-utils.mjs` `parseRoleLocator`/`splitLocatorExactSuffix`（`role=button[name="查询", exact=true]`、`text=…, exact=true` 解析到 `getByRole/getByText({exact})`）；`runner-core.mjs` scrollIntoView 执行器（含可见等待）；分页节律与 `pagination_not_changed` 归因（`pagination-signature.mjs` + `runner-core.mjs`）。
- 5.5 readiness 契约（既有机制映射，未改动）：见「五」。

## 二、逐条验收（6 条）

| # | 验收 | 证据 | 状态 |
| --- | --- | --- | --- |
| 1 | 被 overlay 遮挡的元素不会被原生 click 绕过 | 采集层：动作目标解析对 `receivesEvents=false / coveredByBlockingOverlay` 直接返回 undefined（page-evidence 细节过滤）；执行层：IR click 门（`unique/visible/enabled/receivesEvents` 不全即拒，`Action IR click target is not actionable`）；**新增真实浏览器锁测** `acceptance 5-1`（全屏遮罩下对按钮 evidenceId 发起 click ⇒ 必须 reject） | ✅ |
| 2 | disabled 按钮不会被点击 | 同上层：`enabled=false` 的元素不能成为可解析动作目标；**新增真实浏览器锁测** `acceptance 5-2`（`<button disabled>` ⇒ 用其 evidenceId 发起 click ⇒ 必须 reject；对照按钮可正常采集） | ✅ |
| 3 | role locator 部分匹配不会误点相似按钮 | 执行串带 `exact=true`（唯一性由精确匹配证明）；`phase3-acceptance.test.mjs` 验收 8（「查询」vs「查询用户」两条限定器并存，非 exact 会命中 2 个） | ✅ |
| 4 | scrollIntoView 后目标可见 | `runner-core.mjs` 执行器（`scrollIntoViewIfNeeded` + 可见等待，单测覆盖）；discovery 真实浏览器场景含 IR 动作执行（阶段 4） | ✅ |
| 5 | 分页后 page number 或列表 fingerprint 必须变化 | 既有分页签名与节律归因（`pagination_not_changed`，含加载态/占位/回退判别与观察窗延伸，多轮复核加固） | ✅ |
| 6 | 慢接口页面不会在 loading 未完成时提前取证 | readiness/settle 机制（加载态翻转归因 + 双地板 + `business_api_failed` 诊断；多轮复核含「慢刷新」「遮罩常驻」夹具） | ✅ |

## 三、本轮行为变化（如实列出）

1. 探索期菜单导航点击由原生 `element.click()` 改为 Playwright locator 点击：被遮挡/禁用/不可见的候选不再可能被「绕过去」点击；自动等待与 actionability 由 Playwright 保证。
2. 当候选无法构造出**唯一** Playwright 定位器（role/text 两个口径都 count()≠1）时，该次导航**不点击**（行为等价于「未匹配到导航入口」），不再退回原生点击。
3. 既有分页/滚动/精确匹配语义未改动（本阶段只增锁测与文档）。

## 四、验证方式与实测结果（全量）

- **web-runner**：`npm test` **tests 264 / pass 264 / fail 0 / cancelled 0 / skipped 0**（新增两条真实浏览器验收锁测）。载荷重录两遍 md5 一致：`fbd1fd2a4e61f3f68f9572ff829dcf96` / `5e499c0835b08d06a738030b4a07878e` / `aaaef09bc0353adab02661040cafc6ff` / `21ff6b096bf73222a8c37751a348205a`；`runnerImplementationHash=b0ba0fc124877a4a97946c1e9b308746597aaf73ef063faacdfa2f14b8531db4`。
- **platform**：`./mvnw -o test` **Tests run: 555, Failures: 0, Errors: 0, Skipped: 5**，BUILD SUCCESS，102 份报告（消费新载荷）。
- **前端**：`npx vue-tsc --noEmit` exit 0；`npx vitest run` **139 files / 694 tests** 全过（本阶段未改前端代码）。
- 差异集：`page-locator-discovery.mjs`（净 +21 行）、`page-locator-discovery.test.mjs`（+97 行）、4 份载荷仅 `runnerImplementationHash` 行。

## 五、5.5 Readiness 契约映射与边界

计划中的默认优先级 → 现有机制的对应（均为既有实现，本阶段如实映射）：

1. 应用显式 ready 标记 → `page-evidence.mjs`：`[data-ready="true"],[data-state="ready"],[aria-busy="false"]` 的可见检测（`readyMarker`）。
2. 关键业务接口响应成功 → 只有**负向**接入：关键请求失败 ⇒ `failureReason=business_api_failed`（网络诊断 `criticalBlocked`）；**尚不是正向就绪门**（正向前缀等待未实现，如实记录）。
3. spinner/loading 消失 → 加载态翻转归因（遮罩/占位/spinner 双地板与观察窗）。
4. 目标 locator 可见且可操作 → Playwright actionability + 证据的 `visible/enabled/receivesEvents` 门。
5. 稳定语义 fingerprint → `pageFingerprint`/`shellFingerprint` 稳定性采样。
主路径不依赖固定 sleep：仅有有界的过渡等待（如菜单搜索 250ms、导航后 500ms、readiness 轮询 100ms）。

**边界（如实列出）**：
- IR click 门对「不可操作」统一报 `ambiguous_locator`（历史分类名，对 disabled/covered 不够精确；行为正确：点击前拒绝）。分类名调整涉及平台/前端词表，未在本阶段顺带改动。
- 5.5 的「关键业务接口响应成功」尚未作为正向就绪门实现（见上，仅负向诊断）。
- 探索期导航在「无法构造唯一 Playwright 定位器」时不点击（见三.2），可能损失个别自定义菜单的探索成功率；如需要，应通过扩展定位器口径而不是恢复原生点击来解决。

## 六、独立复核（第十八轮对抗性复核，判定 PASS）

- 复核对象：`38af44e7da1e00128c0a79a619cf425530278113`；复核方 `deleg_02b94295`（1 leaf，1242s，api_calls=103），自建夹具 + 新旧实现逐字节对照，未引用文档或对方说法；原始输出在 `<synthetic-hermes-cache>/scratch/adv18/verifier/`（含 REPORT.md）。
- **A 段全成立**：runner 主代码 10 处 `.click(` 全部为 Playwright locator click、无 dispatchEvent/MouseEvent；新旧对照证明**旧实现原生点击会绕过遮挡**（该场景新实现零点击、1514ms actionability 超时后不点击），`<a>`/`<li role=menuitem>`/`<button>`/无角色自定义 div 均走 Playwright；遮挡/禁用按钮以 evidenceId 发起 click 均在点击前 reject（`ambiguous_locator: not actionable`），其自建计数器全流程为 0；exact 口径两侧透传正确（查询/查询用户 1 vs 2）；滚动/分页/readiness 映射属实（关键接口只有负向 `business_api_failed`，全部等待为有界过渡或轮询）。
- **B 段逐字**：runner `264/264`（ok 88/89 为新锁测，freshness 未 stale）；platform `555/0/0/5` + BUILD SUCCESS + 102 报告；前端 `vue-tsc` exit 0 + `vitest` 在 Node 26 下需 `NODE_OPTIONS=--no-experimental-webstorage` 后 139/694 全过（环境性，见阶段 4 记录）；载荷 md5 四份逐一相同、`runnerImplementationHash` 独立重算一致；`git status` 无新增。
- **C 段：无新增 high/medium**。low 3 条（如实备忘）：① 两条新锁测未把「不点击」锁死——`window.__clicked` 只写不读（复核方插桩实跑显示拒绝发生在点击前、计数器为 0，但断言层面未固化；后续加固项）；② 标签在页面他处重复的自定义菜单新实现 fail-closed 不点击（上文三.2 已声明的取舍）；③ 本文档原「+24 净行」应为 +21（本提交修正）。informational 4 条：IR 滚动无显式事后可见断言、无后缀 role 描述 exact 默认口径、`followNavigationHint` 存在死参数/死代码、platform 某异步用例在高并发夹具下偶发 `NoSuchElementException`（隔离 6/6 通过，既有负载敏感竞态，与本轮无关）。
- 纪律说明：本 PASS 针对 `38af44e` 的代码；其后仅有文档修改（本记录），无代码改动。

## 七、后续加固落地（2026-10-08，闭合「六」C-① 备忘）

- **原问题**：两条锁测（acceptance 5-1/5-2）的夹具页把点击写进 `window.__clicked`，但断言层从不读取 ⇒「不点击」在断言层面未被锁死（复核方靠插桩实跑自证，非测试自证）。
- **加固方案（服务器可观测通道）**：夹具页的点击处理器改为 `location.href = '/clicked?el=<id>'`（导航必然被夹具服务器观测到，不依赖浏览器退出前的 flush），两条锁测在 reject 断言之后新增 `assert.deepEqual(clickedHits, [])`——对「可用/遮挡」目标的程序化点击（导航型）在契约/实现层必被该断言发现；对「**禁用**」目标物理上不可观测（disabled 控件不派发 click 事件，见第二十九轮 C2），由点击前门控 + force 禁令 + Playwright actionability 三层兜底。
- **通道有牙证明（探针 `<synthetic-hermes-cache>/scratch/adv28/probe-beacon-3.mjs`）**：对**可用**按钮发起成功 click ⇒ 服务器实收 `GET /clicked?el=go`（`CLICK_HITS=["/clicked?el=go"]`，PROBE-PASS）——证明「若发生点击必被观测」，零命中断言非空断言。**如实附记**：首版用 `navigator.sendBeacon` 实现，探针实测**成功点击也收不到**（浏览器进程退出丢弃 beacon）⇒ 否决并改用导航型观测，避免把空断言当成加固。
- **实测**：runner 全量 **266/266 / 0 fail**（本提交仅改 `page-locator-discovery.test.mjs`；hash 覆盖的三个实现文件未动，无需重录载荷）；platform/前端零改动。

### 通道正控制自检（第二十八轮 L3 加固，2026-10-09）

- 新增 `acceptance 5-3`：对「可用」控件发起成功 click，断言夹具服务器必观测到 `/clicked?el=go`。目的是**防止观测通道未来失效时 5-1/5-2 的零断言退化成空断言**（上一轮已由 unrecorded 探针客观复现过「通道被进程退出吞掉 ⇒ 真点击也零命中」的失效形态）。
- **实测**：runner 全量 **267/267 / 0 fail**（+1）；仅改测试文件，hash 覆盖的三个实现文件未动。

### 终审：第二十八轮定点复核判定 PASS（2026-10-08）

- 复核对象：`60785d0`；复核方 `deleg_2ef17ba8`（705s，api_calls=37），产物在 `<synthetic-hermes-cache>/scratch/adv28/verifier/`。
- A 段：反证矩阵全按设计方向（**必点击场景真身断言必然失败** ×5 稳定、副本翻断言在 reject 场景 fail 2/2、仓库原版 pass 2/2）；通道有牙独立复现（成功 click ⇒ 实收 `/clicked?el=go`），sendBeacon 对照确证「进程退出未 flush 才丢失」（与本文档否决记录逐字一致）；零点击语义敏感非恒定；范围仅 test+doc 两文件、三实现文件 blob SAME、hash 未变。
- B 段逐字：runner `266/266 / 0 fail`（ok 88/89 即 5-2/5-1，freshness ok 187）；`git status` 仅既存未跟踪项。
- C 段无 high/medium；low 3 条备忘：通道语义边界（观测「送达→导航」事件；force 已被禁、guard 在点击前 fail-closed，非当前漏报）、300ms 固定等待余量 ≥2446ms（非临界）、断言无通道自检（unrecorded 探针已客观复现真空失守形态——如需可加通道正控制）。informational：文档逐点核对无失实；reject 正则可考虑收窄（宽配在无错误时通过）。

### 终审：第二十九轮定点复核判定 PASS（2026-10-09）

- 复核对象：`9b6a9cd`；复核方 `deleg_a1ec6ff9`（839s），产物在 `<synthetic-hermes-cache>/scratch/adv29/verifier/`。
- A 段：5-3 依赖链逐环核对；**非恒绿**——通道被移除（handler 不导航）与通道被吞（sendBeacon 3/3、服务器不记录）时 5-3 均变红；原版连跑 **5/5 全绿**且仪器化证明真发生点击；5-1/5-2 语义逐字未改；hash 三实现文件 blob 未动。
- B 段：runner 全量 **267/267 / 0 fail（410.5s）**；评审期间复核方零写入仓库（其日志自证），同期工作树出现的改动被确认为**并行外部进程**所为，与评审对象无关。
- 残留处理（**已闭环**）：medium「通道绑定属复制粘贴约定，非共享实现」已修复为**单点共享工厂** `createClickTrackingFixture`（5-1/5-2/5-3 复用同一记录服务器 + 同一处理器注入）；绑定层加固见 §九（第三十一轮复核后的三层防御）。low 三条为观测物理边界与聚焦跑法说明，措辞已并入上节与下节。

## 八、第二十九轮 C 段闭环（2026-10-09）

- **C1（medium，已修复）**：把 5-1/5-2/5-3 的夹具收敛为单点共享工厂 `createClickTrackingFixture(fullHtml, clickableElementIds)`——记录服务器与点击处理器只有一份实现；三测只允许通过工厂构造页面（`<!--click-handlers-->` 注入点）。**反证（机制层）**：在副本中仅破坏工厂内一行（`location.href` → `void 0`），5-3 立即变红；修复前复核方用同类解耦实验（advE-decoupled）得到三测全绿——「弄坏工厂实现而 5-3 保持绿」在实现层/契约内已不可能。**绑定层缺口由第三十一轮复核发现并已在 §九 加固**（id 拼错/空 ids/注入点位移的一行级变体曾被复现出静默真空）。
- **C2/C3（low，措辞修正）**：导航型观测对「可用/遮挡」目标必被捕获；对「禁用」目标物理上不可观测（disabled 控件不派发 click 事件），由点击前门控 + force 禁令 + Playwright actionability 三层兜底——「七」节措辞已按此修正，不再声称「任何程序化点击都会使断言失败」。
- **C4（low，说明）**：聚焦单跑（`--test-name-pattern` 只含 5-1/5-2）时 5-3 不执行——第三十一轮后该场景由「回执 + 自触发探针」的每测自带守卫覆盖（31b C9 指正后更新）；全量/CI 整文件执行不受影响。
- **实测**：runner 全量 **267/267 / 0 fail**（共享工厂重构后；仅改测试文件，hash 覆盖的三个实现文件未动）。

## 九、第三十一轮复核（FAIL）与绑定层加固（2026-10-09）

- **复核对象** `d64c799`+`694d3d1`（产物 `<synthetic-hermes-cache>/scratch/adv31/verifier/`）。机制层结论：单点共享 ✓、缺注入点守卫 ✓、断工厂一行 ⇒ 5-3 红 ✓、语义未削弱 ✓、hash 三实现文件未动 ✓、全量 267/267 ✓。
- **判定 FAIL 的依据（绑定层一行级变体仍可静默真空）**：C1a（medium）5-2 的 ids 拼错 ⇒ 三测全绿而通道死亡；C1b（low）ids 为空数组 ⇒ 最安静的真空；C1c（medium）注入点移入 `<head>` ⇒ 脚本先于元素执行 ⇒ 同样真空。复核方以机械抽取工厂源码的探针实证三变体下通道物理死亡。
- **四层加固（本提交 + 31b 补强）**：① **静态校验**——ids 非空、无重复，且每个 id 必须作为独立属性（`id="x"`，非 `data-id="x"` 子串）真实存在（缺失即构造期抛错）；② 注入脚本以 **DOMContentLoaded 兜底绑定**（对注入点位置不敏感）；③ **绑定回执**——binder 自报的挂载清单随每次实例随机 **nonce** 经同源 `<img>` 子资源上报（discovery 的 networkPolicy 会拦截 fetch/xhr 类非关键请求，image 属其允许的同源类型）；④ **自触发探针**——binder 挂载后对每个可点击元素派发一次受控点击（探针监听器先手 `preventDefault`+`stopImmediatePropagation` 且用后自摘，不触发真实导航、不妨碍后续真实点击），每个探针必须真实到达服务器；`fixture.waitForHandlers()` 要求回执、nonce 校验与探针到齐三者同时成立，任一超时/不一致即抛错。三测均在各自**首个断言之前**显式 `await fixture.waitForHandlers()`（31b 指正后 5-3 的空断言次序已调整）；`clickedHits` 改为「未经验证即读取会抛错」的取值器（结构性守卫，调用方无法静默跳过）。
- **变体矩阵（修复后实测）**：ids 拼错 ⇒ **构造期红**（`ids not found in the page`，0.7ms）；空 ids ⇒ **构造期红**；`data-id` 伪装与重复 id ⇒ **构造期红**（31b 指正后收紧）；注入点移入 `<head>` ⇒ 经 DOMContentLoaded 正常绑定并回执 ⇒ 三测**合法全绿**（通道存活，探针实证）；探针层被拆（不派发自触发点击）⇒ 三测**全红**且报「self-test probes never reached the server for [...]」（有牙自证）；断工厂导航行 ⇒ 5-3 红（沿袭机制层反证）。「只弄坏 5-1/5-2 而 5-3 保持绿」在工厂契约（含四层）内已无已知路径。
- **实测**：三层加固后 runner 全量 **267/267 / 0 fail**；acceptance 5 组聚焦 3/3（1141/854/3010ms）。
- **第三十一轮补审（31b）**：判定 **PASS（附条件）**——C1a/C1b 构造期红、C1c 合法全绿且探针实证通道存活、回执对抗（元素先移除⇒红；无回执⇒3/3 超时红；断言未削弱；hash 三实现文件未动；全量 267/267 两跑逐字一致）全部达成。残余裁定：**「页面内容主动干扰」（伪造回执/探针、阉割 `addEventListener`、绑定后替换目标节点）与重写测试内容等价，不在工厂防线内**（本口径经复核方呈报后由我方裁定）；`nonce`+自触发探针把「回执为真而绑定为假」的被动类压死，主动类按上述边界声明。
- **第三十一轮补审 2（31c）**：判定 **PASS**——四层逐层有牙（拆探针⇒全红；删自摘⇒仅 5-3 红；改 nonce（回执侧/探针侧）⇒红；撤 DOMContentLoaded+head⇒红；回退静态校验⇒data-id 变体运行时红）；**varA6（朴素全量阉割 `addEventListener`）由 31b 的「3/3 静默全绿」变为「5-2 响亮红（探针缺失）」**（本轮关键增益）；结构性守卫（删 await⇒getter 抛错红，三测 await 均在首断言前）、断言未削弱、hash 未动、全量 267/267 两跑逐字一致。边界补充（31c C1/C2/C5）：`nonce` 位于注入内联脚本、页面脚本可读；「窃取 nonce 伪造」「选择性阉割（探针真、导航假）」与「绑定后节点替换（TOCTOU）」「绑定后启用/禁用切换」同属上述契约外边界；诊断文案已按 C3 分流（回执被拦而探针到达时提示通道本身被阻断）。
- **文档口径修正（C2）**：§七/§八「真空失守已不可能」类表述改为「实现层/契约内」精确口径，并按本节记录绑定层残余风险面（工厂契约外重写实现不在此列）。
