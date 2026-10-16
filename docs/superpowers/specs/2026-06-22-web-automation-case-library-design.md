> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Web Automation Case Library Design

> **Status:** Planning only. This document defines the target shape for adding Web automation test cases to the existing AI test platform. Do not start implementation from this document alone; use the paired roadmap plan first.

**Goal:** Let the platform generate, save, edit, execute, report, and defect-link Web automation test cases while keeping the execution workflow consistent with API automation and keeping Web-specific fields separate from API fields.

**Architecture:** Add `web_automation` as a first-class case kind beside `interface` and `functional`. Keep shared lifecycle concepts such as generation, review, case library, automation library, execution environment, report, and defect transfer, but split Web automation field templates, execution steps, environments, and runner contracts from API automation.

**References:**

- MeterSphere UI testing documents describe UI testing as element library, UI automation, and test reports, with multi-level modules to organize UI automation scenarios.
- MeterSphere product materials position UI testing around Selenium browser automation and reusable low-code scripts.
- shadcn/ui is used as a visual and interaction reference for data tables, sidebars, tabs, dialogs, drawers, filters, and row actions. The current Vue + Element Plus stack remains unchanged.

---

## 1. Product Boundary

The first implementation should be intentionally small, but the model must already support the final lifecycle:

```text
Word/PDF/MD/YAML/business docs
  -> document extraction
  -> AI structured Web automation case generation
  -> preview and human review
  -> save to case library
  -> generate Web automation runnable cases
  -> choose Web execution environment
  -> execute
  -> report
  -> failed result to defect
```

The flow should match API automation at the workflow level:

- select project and module,
- generate or save cases,
- convert saved cases into automation cases,
- execute selected/filtered cases,
- show execution status and log,
- export report,
- transfer failed or errored results into defects.

The fields and execution model must not match API automation one-to-one. Web automation needs browser, URL, viewport, locator, wait, screenshot, and page assertion concepts that do not belong in API cases.

## 2. Case Kinds And Templates

Extend the current case kind model:

```ts
export type CaseKind = 'interface' | 'functional' | 'web_automation'
```

Template configuration should evolve from one interface field list into per-kind field lists:

```ts
interface TemplateConfig {
  schemaVersion?: number
  fieldsByKind: {
    interface: CaseField[]
    functional: CaseField[]
    web_automation: CaseField[]
  }
  caseTypes: CaseTypeDefinition[]
}
```

During migration, existing `interfaceFields` remains supported and is converted into `fieldsByKind.interface`.

Default Web automation fields:

- `caseId`: 用例ID
- `project`: 项目
- `module`: 模块
- `pageName`: 页面
- `title`: 用例标题
- `caseType`: 用例类型
- `precondition`: 前置条件
- `startUrl`: 访问地址
- `browser`: 浏览器
- `viewport`: 视口
- `testData`: 测试数据
- `steps`: 操作步骤
- `locators`: 元素定位
- `assertions`: 断言规则
- `waitStrategy`: 等待策略
- `screenshotStrategy`: 截图策略
- `priority`: 优先级
- `executionResult`: 执行结果
- `executor`: 执行人
- `executedAt`: 执行时间
- `defectId`: 缺陷ID
- `tags`: 标签
- `sourceFile`: 来源文件
- `remark`: 备注

Case type applicability should include Web automation:

```ts
type CaseTypeApplicability = 'interface' | 'functional' | 'web_automation' | 'all'
```

## 3. Web Automation Case Model

Saved Web automation cases should be a separate shape:

```ts
interface GeneratedWebAutomationCase {
  row: Record<string, string>
  metadata: WebAutomationCaseMetadata
}

interface WebAutomationCaseMetadata {
  sourceFile: string
  sourceDocumentType: 'word' | 'pdf' | 'markdown' | 'yaml' | 'manual'
  pageName: string
  startUrl: string
  browser: 'chromium' | 'firefox' | 'webkit'
  viewport: string
  steps: WebAutomationStep[]
  locators: WebLocator[]
  assertions: WebAssertion[]
  variables: Record<string, string>
  runOrder: number
}
```

The Web step model is structured. Natural language is allowed in the preview, but saved automation cases must include machine-readable steps:

```ts
type WebAutomationAction =
  | 'goto'
  | 'click'
  | 'input'
  | 'select'
  | 'hover'
  | 'upload'
  | 'waitForVisible'
  | 'waitForUrl'
  | 'assertText'
  | 'assertUrl'
  | 'assertVisible'
  | 'assertHidden'
  | 'screenshot'

interface WebAutomationStep {
  id: string
  name: string
  action: WebAutomationAction
  targetType: 'url' | 'locator' | 'elementRef'
  target: string
  value?: string
  timeoutMs?: number
  screenshot?: 'none' | 'on_failure' | 'always'
}
```

Version 1 can store inline locators. Version 2 adds a reusable element library and maps `elementRef` to centralized locators.

## 4. Generation From Files

The generator should accept:

- Markdown: parse headings, tables, lists, and code blocks directly in the frontend first.
- YAML: parse structured test definitions directly and normalize to Web automation cases.
- Word: extract text and tables through a backend document extractor in a later version.
- PDF: extract text and tables through a backend document extractor in a later version.

Generation pipeline:

```text
Upload document
  -> identify document type
  -> extract normalized sections
  -> map sections to project/module/page/flow
  -> AI generate structured Web automation cases
  -> validate required Web step fields
  -> preview
  -> edit
  -> save to case library
```

YAML is the preferred deterministic first input because it avoids ambiguous document extraction:

```yaml
project: Ucenter
module: 登录
pageName: 登录页
startUrl: /login
cases:
  - title: 用户名密码登录成功
    steps:
      - action: goto
        targetType: url
        target: /login
      - action: input
        targetType: locator
        target: '#username'
        value: synthetic_user
      - action: input
        targetType: locator
        target: '#password'
        value: '${password}'
      - action: click
        targetType: locator
        target: 'button[type=submit]'
      - action: assertVisible
        targetType: locator
        target: '.dashboard'
```

## 5. Case Library UX

The case library should support multiple case kinds:

- `接口用例`
- `功能用例`
- `Web 自动化用例`

The left project/module layout should remain consistent with the existing case-library structure. The main table should switch columns based on the selected case kind and template.

Borrow these visual patterns from shadcn-style components while staying on Element Plus:

- compact sidebar project/module selector,
- toolbar filters above the table,
- data table with column visibility, row actions, and bulk actions,
- drawer for detailed editing,
- tabs inside the drawer for base info, steps, locators, assertions, and metadata.

## 6. Web Automation Workspace

Add a separate automation mode for Web automation rather than mixing it into API automation rows.

Workspace layout:

- left: project/module selector,
- top toolbar: environment, run mode, concurrency, generate from case library,
- main table: Web automation cases,
- row action: edit, preview steps, execute, delete,
- report/log drawer: step logs, screenshots, error, variables.

Run modes should mirror API automation:

- sequential,
- concurrent,
- module.

Concurrency should be lower than API by default because browser contexts are heavier:

- default: `2`,
- allowed range: `1` to `10`.

## 7. Web Execution Environment

Web environments are not API environments. They need separate fields:

- environment name,
- base URL,
- browser type,
- headless,
- viewport width and height,
- default timeout,
- storage state strategy,
- global variables,
- screenshot strategy,
- video strategy,
- retry count.

Version 1 can keep this frontend-local with mock execution. Version 2 introduces a backend runner API.

## 8. Runner Contract

The runner should be backend-owned or worker-owned, not browser-front-end-owned, once real execution starts.

Preferred execution engine:

- Playwright for new implementation because it is modern, cross-browser, and has strong trace/screenshot/video support.
- Selenium compatibility can be added later if MeterSphere-style ecosystem compatibility is required.

Runner API shape:

```http
POST /api/web-automation/runs
GET /api/web-automation/runs/{runId}
GET /api/web-automation/runs/{runId}/artifacts/{artifactId}
```

The frontend should not depend on Playwright internals. It sends normalized cases and environment settings; the backend returns normalized execution reports.

## 9. Reports And Defects

Web automation reports should match API automation report summary concepts:

- total,
- passed,
- failed,
- error,
- duration,
- startedAt,
- endedAt.

Web-specific report detail:

- step status,
- step duration,
- resolved locator,
- screenshot path,
- video path,
- trace path,
- error message,
- console logs,
- network summary.

Failed/error Web results should become defect drafts with:

- source kind: `web_automation`,
- project,
- module,
- page,
- case title,
- failed step,
- error message,
- screenshot/trace links when available.

## 10. Version Roadmap Summary

V1 should prove that Web automation is a first-class case kind and can be saved and managed. Execution can be mocked.

V2 should add real Playwright execution, Web environments, step logs, screenshots, and reports.

V3 should add element library, document extraction for Word/PDF, stronger AI generation, and reusable instructions/scenarios.

V4 should complete enterprise readiness with scheduled runs, distributed workers, permissions, audit, report retention, trace artifacts, and Jira integration hardening.

The version boundary is deliberate:

- V1 validates the data model, template separation, case-library UX, and conversion workflow.
- V2 validates the execution contract and report data model with real browser runs.
- V3 reduces authoring and maintenance cost through elements, document extraction, and reusable instructions.
- V4 closes operational gaps so the module can be used by a team over time.

Do not skip the V1 model separation just because execution is mocked. The most expensive later rewrite would be discovering that Web automation had been stored as API rows with extra columns.
