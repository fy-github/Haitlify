> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Platform AI Routing And Run Observability Design

> **Status:** Design approved in discussion. This document defines the next implementation boundary; it does not change the existing AI runtime by itself.

**Decision date:** 2026-07-30.

## Goal

Make platform AI predictable for a shared test platform. Requirement analysis and case generation must run as backend-owned asynchronous jobs, use an explicit model fallback policy, and expose understandable live progress plus a durable run summary.

The configuration experience must separate connection details from business-task behavior. A user starting analysis should know which strategy will run without having to understand API keys, provider records, or agent mappings.

## Confirmed Runtime Direction

The product runtime is a platform-owned AI gateway backed by API providers. It is not driven by a user's local Codex CLI or Claude Code session.

```text
User starts requirement analysis
  -> Backend creates run and immutable strategy snapshot
  -> Chunk planner prepares bounded input
  -> AI gateway tries primary route
  -> retry eligible failure: next configured API provider/model
  -> validate and persist draft output
  -> frontend polls persisted run events and summary
```

Codex CLI and Claude Code are deferred to a separately installed local AI Runner for developer-only activities such as local debugging. A browser must not directly execute a user's CLI, and a server must not impersonate an individual CLI login.

## Information Architecture

Rename the current configuration area to **Platform AI** and organize it into three pages.

### 1. Run Policies

This is the first page and is the normal administrator entry point.

- A default policy defines an ordered route: primary provider/model followed by zero or more fallback provider/models.
- A task inherits the default policy unless it has an explicit override.
- Initial supported task types are requirement analysis, Web case generation, and API case generation.
- Each policy contains a per-call timeout, whole-run deadline, retry budget, and input-size/chunk budget.
- A policy displays validation warnings when it references a disabled provider, missing credential, or unavailable model.

Example:

```text
Requirement analysis
  Primary: production OpenAI gateway / gpt-5.5
  Fallback 1: production OpenAI gateway / gpt-5.4
  Fallback 2: private compatible gateway / approved-model
  Per-call timeout: 90 seconds
  Whole-run deadline: 15 minutes
```

### 2. Model Connections

This page owns provider setup only: provider type, endpoint, credential reference, available/default model, connection timeout, and connection test. API keys remain server-side and are returned only as a masked value.

The existing multiple-provider storage is retained and becomes the backing data for policies. The current global `activeProviderId`, `activeModel`, and agent maps are migrated to one default policy, so existing configurations remain usable. They are not presented as the primary user workflow.

### 3. Runs And Diagnostics

This page shows persisted runs, route snapshots, attempts, failures, and output summaries. Connection tests remain in Model Connections; task failures are diagnosed here.

## Studio Canvas UI Specification

### Visual Intent

Platform AI is an operational configuration workbench, not a dashboard and not a generic cloud-console form. It uses the approved Web case Studio Canvas language so moving between Web cases and AI administration feels like changing tasks in one product.

The current page must remove the secondary `AI status / Provider / Model` rail, the duplicated connection-status card, the `AI configuration form` wrapper card, and the mixed technical tab strip. These elements create three competing navigation systems before a user can make a single change.

The page uses only one semantic accent: the current configurable deep-blue brand token. Success is green, attention is amber, error is rose, and information is sky. Color communicates state; it is not used as a large surface fill.

Do not use gradients, oversized pills, decorative metric cards, or a blue-tinted page background. The default surfaces are `surface-page`, `surface-panel`, and `surface-raised`; borders use `border-default` and `border-subtle`. Standard radii are 6px for controls and 8px for panels. Shadows are reserved for drawers and dialogs.

Typography matches the Web case page:

- Page title: 15px, weight 600.
- Section title: 13px, weight 600.
- Standard content: 12px to 13px, weight 400 to 500.
- Supporting copy and timestamps: 11px to 12px.
- Avoid the current English-first headings such as `AI Settings` and `Active Provider`; Chinese labels are primary, with protocol/model identifiers retained only where technically useful.

### Page Shell

The global sidebar and global top bar remain unchanged. `平台 AI` becomes the first-viewport title inside the workspace, with no additional permanent local left rail.

```text
Global shell
  Global top bar
  Platform AI canvas
    70px workbench header
    40px local navigation
    52px contextual toolbar when the active page needs commands
    active work area
```

The workbench header reuses the Web case header rhythm:

```text
平台 AI
团队共享的模型策略、连接与运行诊断

[运行策略] [模型连接] [运行与诊断] [规则与扩展]     [连接测试] [保存变更]
```

- The left identity block is 190px minimum. It has the title, a single-line description, and no duplicated readiness card.
- Local navigation sits in the header's middle track. It uses the same top-border active treatment as the Web case stage selector: 2px active deep-blue line, soft brand surface, no rounded tab containers.
- The right actions use 30px controls. `连接测试` is secondary; `保存变更` is the only primary command and becomes enabled only for unsaved changes. A compact status text immediately before actions reads `已保存 14:32` or `有未保存变更`.
- The header is 70px at desktop widths, a white panel surface, and has one bottom border. It must not become a floating card.
- The canvas body uses `surface-page`, 14px top and 16px horizontal inset, then one 8px-radius work surface. Do not put cards inside that work surface unless rendering repeated list rows or independent transient details.

At widths below 1100px, the local navigation remains horizontally scrollable and actions remain on the first row. At widths below 760px, the title and actions occupy the first row while the navigation occupies a second 40px row. Desktop is the primary target; tables may scroll horizontally rather than compress columns below readable width.

### Shared Component Mapping

| Platform AI element | Web case reference | Required behavior |
|---|---|---|
| Workbench header | `studio-workflow-header` | 70px white band, bottom divider, deep-blue active cue, compact actions |
| Local page navigation | `studio-workflow-step` | Flat top-border selection, not rounded tab pills |
| Contextual command row | `interface-case-toolbar` | 52px high, 32px controls, one divider, optional horizontal scroll |
| Dense list/table | `interface-case-table` | One framed 8px surface, 52px toolbar then scrollable body, no internal shadow |
| Resource/status row | Web environment list item | 3px left selection indicator, subtle hover, semantic dot/tag, fixed row height |
| Edit surface | Web environment drawer | Right drawer with a 16px/20px header-body rhythm and sticky 56px footer |
| Confirmation dialog | Web automation dialog | 520px maximum, quiet white surface, no colored hero block |
| Empty/loading state | `studio-workflow-state` | Centered explanation and a single next action; retain surrounding workbench frame |

### 1. Run Policies Page

`运行策略` is the default local page because it describes business behavior rather than credentials. It has no provider rail and no split list/editor layout.

The framed work surface has three stacked regions:

1. **Policy toolbar, 52px.** Left: `默认策略` title and `3 个任务继承` supporting count. Right: compact `新建策略` command for administrators. It never contains Save, Delete, Reload, and Test at the same time.
2. **Policy list, minimum 72px per row.** Each row has a 3px left status line, task name, inheritance label, route preview, timeout preview, and a right-aligned chevron. The default policy row is selected on first load.
3. **Selected-policy canvas.** It is a flat editing region separated by one divider, not a nested card. It shows `策略名称` and `适用任务` in a 2-column field grid, followed by a route chain and a compact guardrail row.

The route chain is the distinctive component:

```text
[ 主模型 ]  gpt-5.5 / 生产 OpenAI 网关  ──>  [ 备用 1 ]  gpt-5.4  ──>  [ 备用 2 ]  私有网关模型
```

- Each route node is a 160px to 220px rectangular resource tile, never a pill. It contains provider name, model, and a small semantic readiness dot.
- Connecting arrows are thin neutral lines; they do not use gradients or decoration.
- `添加备用模型` is a small outlined button after the final node. Reordering uses a dedicated grip/drag interaction with keyboard alternatives, not text buttons repeated inside each node.
- Clicking a node opens the route editor drawer. The inline canvas remains read-oriented, so policy scanning does not become a dense form.
- The guardrail row below the chain uses three 32px controls: `单次调用 90 秒`, `整次任务 15 分钟`, `最多重试 3 次`. A muted help line explains that 401/403 and invalid-model errors do not trigger fallback.

The initial task mapping is visible as a simple table below the selected policy: `需求分析`, `Web 用例生成`, and `接口用例生成`. Each shows either `继承默认策略` or a named override. Overrides open the same policy editor rather than a separate form implementation.

### 2. Model Connections Page

`模型连接` owns endpoints, credentials, and connection verification. It does not contain route order, task mappings, Agent mappings, or global AI switches.

The page uses a single framed resource table:

```text
模型连接                                              [新增连接]
名称                 类型                 默认模型      状态       最近检测        操作
生产 OpenAI 网关     OpenAI Compatible    gpt-5.5       正常       2 分钟前        编辑
私有推理网关         OpenAI Compatible    approved-…    需关注     从未检测        编辑
```

- Toolbar: search by name/model on the left; `新增连接` on the right. Search uses the existing 220px toolbar input treatment.
- Rows are 56px minimum. Status is an icon plus label, not tag color alone: `正常`, `未配置`, `检测失败`, `已停用`.
- The `使用中` indicator is plain supporting text such as `被 2 条策略引用`; it opens a filtered policy list. Deleting a referenced connection is unavailable in the row menu and requires explicit replacement from the drawer.
- API keys are never part of the table and never rendered in a detail preview.

#### Connection Editor Drawer

Selecting `新增连接` or `编辑` opens a 520px right drawer. It must reuse the Web automation drawer anatomy rather than invent a different modal form.

```text
编辑模型连接                                                   [Close]
连接的密钥仅保存在服务端
────────────────────────────────────────────────────────────────
基础信息
名称                  Provider 类型
生产 OpenAI 网关      OpenAI Compatible

连接参数
Base URL
API Key               已保存：****et6F

模型与限制
默认模型              单次调用超时              上下文窗口

                 [连接测试]                     [取消] [保存连接]
```

- Header: 16px title and 12px supporting copy; one bottom divider.
- Drawer body: `surface-page` background with vertical sections separated by dividers, not separate colored cards.
- Fields use the Web automation 32px input/select controls and 11px to 12px labels. `Base URL` and `API Key` span the full content width. API key is empty on edit unless being replaced; saved state is masked.
- The provider type selection happens before protocol-specific fields. Changing type with existing unsaved credentials requires confirmation.
- `连接测试` first validates local inputs, then presents inline outcome below the action: status icon, duration, model checked, and a redacted diagnostic reason. It never saves implicitly.
- Footer is sticky, 56px, with `取消` and `保存连接` right-aligned. A destructive `停用/删除` action sits on the left only for saved connections and carries a confirmation dialog.

### 3. Runs And Diagnostics Page

This page replaces the current raw `调用记录` tab. It is a run-oriented operational view, not an unfiltered transport log.

The main table uses the same `interface-case-table` density:

```text
运行与诊断      [全部任务] [全部状态] [最近 7 天] [搜索 run ID / 需求]       [导出]
任务            进度             实际路由             状态         用时        开始时间
退款需求分析    7/7 · 128 点     GPT-5.5 → GPT-5.4    已完成       4m 12s      14:03
登录用例生成    3/8              GPT-5.5              进行中       1m 48s      14:20
```

- Progress combines count plus label, for example `3/8 · 正在分析第 4 片段`; it is never color-only.
- Route summary displays the real route that was attempted, making a fallback such as `gpt-5.4` understandable instead of surprising.
- Selecting a row opens a 560px right run drawer. It has four vertically ordered sections: run summary, effective policy snapshot, attempt timeline, and output/retry result.
- The timeline uses small semantic dots and short Chinese summaries. It auto-scrolls only while the viewer remains at the latest event; manual scrolling pauses auto-follow and exposes a `回到最新` icon button.
- Completion summary is always visible above the timeline: input/chunk counts, generated point count, retry/fallback count, elapsed time, and terminal reason. A final error cannot hide this summary.
- `重试失败片段` appears only when the worker has recorded a safe retry scope. It is the only destructive-cost command and requires a compact confirmation describing the exact number of calls it may create.

### 4. Rules And Extensions Page

Agent, review rules, Skills, and MCP remain available but are intentionally visually secondary. They no longer appear next to model connection management.

The page starts with a 52px local toolbar and then a left-aligned, 220px context list inside the work surface: `评审规则`, `Agent`, `Skill`, `MCP`. This uses the same resource-list item style as the Web environment drawer: 3px active border, subtle hover, no card stack.

The selected extension detail uses a simple table or form in the remaining canvas. Built-in versus customized state is shown through concise text and a small semantic label. MCP write access is an explicit switch in the editor, defaults to off, and includes an explanatory warning row. These advanced pages must not display Provider, API Key, route timeout, or call history fields.

### States And Interaction Rules

- **Ready:** Header status shows a green dot and `已就绪`; it does not add a full-width green banner.
- **Needs attention:** Header shows amber `需处理`. The exact blocking item appears in the relevant policy or connection row, with a one-click path to edit it.
- **Unsaved changes:** Header text changes to `有未保存变更`; only `保存变更` gains the deep-blue primary treatment. Local editor drawers have their own `保存连接` command and do not enable the page-level Save until they have been closed successfully.
- **Testing:** The connection row and drawer action show a spinner plus `正在检测`; other resource rows remain usable. Test results are not mistaken for a saved state.
- **Empty:** The first empty policy state explains that a default policy is required before starting analysis and offers `新建默认策略`. An empty diagnostics state offers filters reset, not a misleading setup button.
- **Permission denied:** Render a read-only canvas with policy/connection names and masked configuration status. Hide mutation actions rather than rendering controls that fail after click.
- **Keyboard and accessibility:** Every icon-only command has a tooltip and accessible label. Drawers trap focus, Escape closes only when no unsaved changes exist, and table rows expose a keyboard-operable detail action.

### Removal And Migration Of Existing UI

The implementation removes these visual concepts from the AI settings page:

- the workspace-local status rail (`AI 状态`, `Provider`, `Model`);
- the full-width `接入状态` panel;
- the `AI 配置表单 / V1 生产接入面板` heading and outer card;
- the single tab strip that mixes models, Agents, rules, Skills, MCP, and raw calls;
- the blue gradient Provider hero, the full-page Provider editor, and the global AI on/off switch.

The data currently exposed by those elements is preserved in the new routes, connections, diagnostics, and extensions pages. The old global switch migrates to policy readiness: no enabled policy with a valid primary route means Platform AI is not ready. This prevents contradictory states such as `AI 总开关开启` while the selected provider is disabled.

## Analysis Page Experience

The requirement-analysis page has two distinct signals during a run.

1. A compact effective-policy disclosure above the primary action, for example: `Platform AI ready - gpt-5.5, 2 fallbacks`. It is read-only for ordinary users and links to the route details.
2. A live run panel after start. It contains a current semantic summary, progress counters, and an auto-scrolling detailed event list. The detailed list can be collapsed without hiding the current summary.

Example live panel:

```text
Analysing requirement - 3 / 7 chunks
Current: Extracting refund flow and boundary conditions
Route: gpt-5.5 timed out; continuing with gpt-5.4

14:03:12  Parsed 18 document pages
14:03:15  Analysing chunk 3 of 7
14:04:46  Primary model timed out
14:04:47  Switched to fallback: gpt-5.4
14:05:09  Chunk 3 completed: 12 test points identified
```

On completion or failure, the panel produces a durable summary:

- parsed input size and chunk count;
- completed, retried, and failed chunk counts;
- actual provider/model attempts and their durations;
- fallback reasons;
- generated test-point count and validation result;
- a scoped retry command when retry is safe.

The summary is generated from backend events and call records, rather than relying on the model to emit its own narrative.

## Failure And Timeout Policy

There are separate deadlines with separate meanings.

| Boundary | Purpose | Default behavior |
|---|---|---|
| Per-call timeout | Avoid one model call hanging a run | Mark attempt timed out and try the next route |
| Whole-run deadline | Prevent a background run from consuming resources indefinitely | Stop remaining work and persist a terminal partial/failure summary |
| Frontend request | Create a run only | Return `runId` promptly; never wait for model completion |

Retry eligibility:

- Retry/fallback: timeout, temporary network failure, 429, and 5xx.
- Do not retry/fallback blindly: 401/403, invalid API key, invalid endpoint, model not found, invalid request, or structured-output validation failure that needs repair.
- Structured-output repair is a separately recorded bounded operation. It must not look like an unexplained model fallback.

Retries operate at the smallest safe unit, normally one failed chunk. A retry must retain the run ID, policy snapshot, and idempotency information, so successful chunks are not regenerated or duplicated.

## Event And Audit Contract

Every run event contains at least:

```text
runId, timestamp, stage, severity, summary,
chunkIndex, chunkTotal, attemptIndex,
providerId, providerType, model, durationMs, retryReason
```

`summary` is a short Chinese user-facing statement. Sensitive request text, API keys, authorization headers, and provider response bodies are never written into the visible event stream.

Call records continue to hold the detailed technical audit data, linked to the same configuration snapshot. This makes a display such as `gpt-5.4` explainable: it was an actual fallback attempt, not an unexpected invisible model change.

## Roles And Safety

- Platform administrators can edit connections and run policies.
- Project users can start runs and view the effective policy plus records authorized for their projects.
- Project users cannot see or edit credentials.
- A connection used by an active policy cannot be silently deleted; the UI must show affected policies and require a replacement or explicit disablement.
- Existing immutable run snapshots remain readable after a policy or connection changes.

## Delivery Sequence

1. Introduce policy data and migrate the current active provider/model configuration to a single default policy.
2. Add bounded fallback execution and distinct call/run deadline handling to the backend worker.
3. Persist normalized run events and completion summaries; render the current summary plus live detailed log in requirement analysis.
4. Replace the Provider-first configuration screen with Run Policies, Model Connections, and Runs And Diagnostics while retaining the approved Studio Canvas visual direction.
5. Add optional per-task policy overrides after the default policy is stable.
6. Design the local AI Runner as a separate future integration; it is out of scope for this delivery.

## Verification

Backend tests cover policy snapshot creation, eligible fallback, non-retryable authorization failure, per-call timeout, whole-run deadline, per-chunk retry, and preservation of successful chunks.

Frontend tests cover policy readiness, policy disclosure before run, streaming/polling event rendering, auto-scroll behavior, completion summary rendering, and sensitive-data redaction. Browser verification covers the normal success state, fallback state, configuration-error state, and terminal failure state.
