> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# AI Test Platform Decisions

This file is the live local record for decisions agreed during brainstorming.
Project rule: after a requirement, design choice, field contract, or project rule is agreed, update this file immediately so context compaction does not lose it.

## Formal Design Documents

- Formal product/technical spec:
  - `/path/to/Haitlify/docs/superpowers/specs/2026-06-10-ai-test-platform-design.md`
- Repo-level UI/UX design source of truth:
  - `/path/to/Haitlify/DESIGN.md`

These documents were created after the brainstorming decisions were accepted. Future implementation planning should use them as the stable source of truth, while this file remains the running decision log.

## Phase A Implementation Start

Implementation started from the urgent use-case and API-test-related workflow.

Local environment finding:

- Node.js/npm are available.
- Java Runtime and Maven are not currently available in this environment.

Implementation decision:

- Build the first usable Phase A workbench under `/path/to/Haitlify/apps/web`.
- Use Vue 3 + TypeScript + Vite for the immediate UI.
- Keep OpenAPI parsing, interface case generation, and CSV export in framework-independent TypeScript domain modules.
- This lets the team use Apifox/OpenAPI JSON to generate Chinese interface test cases immediately.
- Spring Boot backend remains the target architecture and will later replace the frontend-local generation path through API endpoints.
- Non-urgent modules can appear in the UI as backend-pending placeholders.

Follow-up implementation decision:

- Add a frontend-local case library before backend persistence is ready.
- The generated case preview `保存` action writes cases into browser local storage.
- Saved cases are deduplicated by source file, source operation, path, and case type so repeated saves update existing cases instead of creating duplicates.
- Add a first-level `用例库` navigation entry.
- The `用例库` view supports project/module/type/keyword filtering, single-case editing, deletion, and Chinese CSV export.
- The saved records retain hidden executable metadata so the later Spring Boot backend and Apifox-compatible export adapter can reuse the same shape.

Template configuration implementation decision:

- Add a frontend-local `模板配置` module before backend project-level settings are ready.
- Interface test case fields can be edited in the UI:
  - rename Chinese header,
  - change field type,
  - toggle visible,
  - toggle export,
  - toggle AI generation,
  - toggle required,
  - move up/down,
  - add/delete custom fields.
- Interface case types can be edited in the UI:
  - rename,
  - enable/disable,
  - change applicability,
  - add/delete custom types.
- Current configuration is stored in browser local storage and can be reset to system defaults.
- Generated preview, saved case library table, and CSV export read from the current field template.
- Disabled case types are excluded from generation selection. Renamed built-in case types keep their built-in generation logic but display the configured name.

API execution preparation implementation decision:

- Add an API execution request builder before full backend execution is ready.
- Saved interface cases can be combined with a local execution environment:
  - base URL,
  - Bearer token,
  - extra headers as JSON.
- The builder resolves path parameters, query parameters, headers, request body, expected status, and assertions from hidden executable metadata.
- The case library can preview a single saved case as an executable cURL command.
- The case library can export the currently filtered saved cases as a shell script containing cURL commands.
- This is an execution-preparation step, not the final backend runner. Browser-based direct execution is deferred because target APIs may require internal network access, authentication context, or CORS handling.

AI review implementation decision:

- Replace the early placeholder review message with a frontend-local rule-based case review module.
- Review generated interface cases before saving by checking:
  - whether every selected interface has the enabled case types expected by the current template,
  - whether assertion rules are empty or too weak,
  - whether expected results are too brief for review and execution.
- Findings should include stable IDs, severity levels, scope, machine-readable codes, and Chinese messages.
- The UI should render findings as severity tags so later AI-provider review, backend persistence, and review history can reuse the same shape.
- This is the first local review baseline. Later AI model review can augment or replace the rule engine through the same review-result contract.

Local data portability implementation decision:

- Add a versioned frontend-local project data backup package for Phase A.
- The first package format is JSON with:
  - `kind: ai-test-platform.project-data`,
  - `version: 1`,
  - `exportedAt`,
  - saved interface case library,
  - template configuration,
  - API execution environment.
- The package intentionally excludes uploaded OpenAPI source files, generated-but-unsaved preview cases, UI filters, and sidebar state.
- Importing a package replaces those three local browser storage sections and refreshes the in-memory UI state.
- This gives the current localStorage implementation a portable migration path before backend persistence is introduced.

Interface generation page layout decision:

- Remove the decorative process canvas from the interface case generation page.
- Future process/workflow visualization belongs to `全景视图` and `图表`, not the urgent interface case generation workbench.
- Keep the interface generation page focused on import, filtering, granularity selection, generation, review, save, and export.

Usability refinement decision:

- OpenAPI import must use a native label/file-input interaction instead of a hidden input triggered by JavaScript, because real browser clicks must reliably open the file chooser.
- The frontend should support multiple imported OpenAPI projects in one session.
  - Importing a file creates or updates an imported project by source file.
  - The API generation workspace can switch the active project and only shows that project's interfaces/modules.
  - The case library can switch/filter by project before filtering modules and case types.
- Rule review output must not stretch the page indefinitely.
  - Show a compact summary by default.
  - Put detailed findings behind an expandable, internally scrollable list.
- Generated interface cases should be convertible into API automation case drafts.
  - First target is an Apifox-oriented draft JSON shape.
  - Later work can replace the draft exporter with a strict Apifox import adapter.

AI review remediation decision:

- AI review should only display findings that need action.
- Passing review summaries such as `review_passed` should not be displayed in the review panel.
- Missing case type findings should be actionable from the review panel.
- The first remediation action is one-click supplement generation for missing case types.
  - The supplement action should generate the missing case type for the affected interface.
  - It should avoid duplicating an already generated case for the same interface and case type.
- Long-content generated case columns should be wider by default:
  - 前置条件,
  - 请求头,
  - 路径参数,
  - 查询参数,
  - 请求体,
  - 测试步骤,
  - 预期结果,
  - 断言规则.

API automation workspace implementation decision:

- The secondary sidebar must never stretch the page when there are many projects or modules.
  - The sidebar header stays fixed.
  - Project lists use a capped internal scroll area.
  - Module lists take the remaining height and scroll internally.
- Generated interface cases and saved library cases can be converted into API automation drafts.
- API automation drafts support project, module, method, and keyword filtering.
- The first executable automation slice reuses the local execution environment:
  - Base URL,
  - Bearer token,
  - extra headers as JSON.
- Each automation draft can preview an executable cURL command.
- The filtered automation drafts can be exported as a cURL shell script.
- The filtered automation drafts can be exported as a versioned Apifox-oriented draft package:
  - `kind: ai-test-platform.apifox-automation-draft`,
  - `version: 1`,
  - `target: apifox`.
- This is still a draft adapter, not a strict Apifox import file contract. Later work should replace or augment it with a verified Apifox import adapter after confirming Apifox's accepted automated-test import schema.

API automation execution implementation decision:

- API automation should support real execution from the automation workspace before the backend runner is ready.
- The first frontend execution runner supports three modes:
  - sequential: run cases in table/input order one by one,
  - concurrent: run all cases at once,
  - module: run modules in order while running cases inside the same module concurrently.
- Concurrent and module execution use a configurable concurrency limit.
  - Default concurrency is 8.
  - The UI allows 1 to 50 concurrent requests.
- The runner compares actual HTTP status with expected status:
  - matching status is passed,
  - mismatched status is failed,
  - request exceptions are error.
- Execution results are shown in the automation table with status, actual status, and duration.
- Failed or errored automation runs should expose an in-app execution log view.
  - The first log view should show:
    - executed request URL,
    - request headers,
    - request body,
    - assertions,
    - response body,
    - error message,
    - expected/actual status and duration.
- Execution reports can be exported as JSON for traceability before a full report module exists.
- Browser execution is an immediate Phase A utility. Internal APIs, CORS, network restrictions, and credential handling may require a later backend execution service.
- Real API execution should support copied request context from browser/curl, not only `Base URL + Bearer Token`.
  - The local execution environment now keeps structured extra headers and a raw request header/Cookie text area.
  - Raw text accepts JSON headers, plain `Header: value` lines, copied `curl -H` lines, and copied `curl -b/--cookie` lines.
  - Absolute operation URLs are executed as-is instead of being prefixed by Base URL.
  - During local Vite development, execution should prefer a local proxy endpoint so Cookie, Origin, Referer, and other real request context can be sent more like curl.
  - Browser direct execution remains a fallback, but it cannot manually set browser-forbidden headers such as Cookie/Origin/Referer.
  - The later Linux/backend runner should replace the Vite proxy with a server-side execution service using the same request contract.
- API automation environment configuration should follow the Postman/MeterSphere pattern instead of occupying the main execution page.
  - The automation page should show only a compact runtime selector and an `环境配置` action.
  - Environment details live in a drawer with editable environments.
  - Each environment contains name, Base URL, auth mode, and user-defined enabled/disabled parameters.
  - Parameter rows support Header, Cookie, and variable types with custom name/value/description.
  - Execution, cURL preview, and export must validate that an environment is selected and Base URL is configured before running.
  - Bearer auth requires a token; enabled parameters require a name.
  - Copied curl header/cookie lines can be parsed into environment parameters.
- MeterSphere is a product reference for future API automation depth:
  - test scenarios/plans,
  - parameterization,
  - assertions,
  - execution reports,
  - environment/resource management.
  The current phase intentionally implements only the small slice needed for immediate execution.
- API automation should be managed as a reusable saved set instead of only a transient generated draft list.
  - Generating from the case library should open a dialog first so the user can choose project and module scope.
  - Re-generating the same source cases should update existing saved automation cases instead of duplicating them.
  - Saved automation cases should support:
    - single delete,
    - delete by current filtered scope,
    - selection-based execution.
- The temporary `从当前用例生成` and header-level `cURL` actions are removed from the automation workspace.

Fullscreen layout refinement decision:

- The main API generation workspace must fill the available viewport width and height.
- The interface list and generated case table should consume the remaining panel height instead of using fixed short heights.
- Bottom panel borders should visually align with the top panel styling; large blank lower areas inside panels are considered a layout bug.
- Project switching in the secondary sidebar should use a compact select control instead of rendering every project as a vertical list.
  This keeps module lists usable when many projects exist while preserving the existing sidebar style.
- Each top-level functional module should have a stable direct-open path:
  - `/api`
  - `/cases`
  - `/automation`
  - `/settings`
  - and equivalent direct paths for the remaining primary nav modules.
- Opening `/` should normalize to the default module path `/api`.
- Sidebar navigation clicks and in-app programmatic jumps should keep the URL in sync with the current major module.

Case library performance decision:

- The case library should be optimized for very large local datasets.
- Avoid repeated `savedCases.filter(...)` scans inside templates for project/module counts.
- Precompute searchable/indexed records and facet counts in a dedicated domain helper instead of recomputing ad hoc in the view.
- The library table should render paged data instead of the entire filtered dataset at once.
  - Initial page size is 50.
  - User-selectable page sizes are 50, 100, and 200.
- Export, conversion, and metrics may still operate on the full filtered dataset, but visible table rendering should stay paged.
- Pagination UI should use Chinese wording, including:

Defect project grouping decision:

- The `缺陷` page left workspace area should use project grouping as the primary navigation surface instead of placeholder text.
- The left-side `导出选中/筛选` and `删除选中/筛选` duplicate entry points were removed from the workspace rail, leaving the right-side toolbar as the only action surface.
- Gray left-rail summary cards such as `缺陷草稿` and `已转 Jira` were also removed so the project grouping list can move upward and occupy the rail directly.
- Clicking a left-side project group should filter the right-side issue list to that project by sharing the existing project filter state.
- The defect page should move toward a unified issue view model so future Jira API issues can be merged with local defect drafts in the same page structure.
- The current slice keeps local defect drafts as the only data source while reserving a `sourceKind` distinction for later Jira integration.
- The right-side defect table now renders through a unified issue-list boundary instead of binding directly to raw drafts.
  Local draft rows still edit through their original draft payload, while future Jira rows can join through a parallel source adapter.
  - total count,
  - previous/next,
  - page-size selector wording.

Case workflow refinement decision:

- Saving generated interface cases should keep the user on the current generation page instead of forcing a jump into the case library.
- The save action should show a loading state.
- Generated cases should remain available on the current page until the user explicitly regenerates or edits them; switching imported projects should not silently wipe the current generated result set.
- Sending generated cases to API automation should first persist them into the case library, then build the automation draft.
- The case library filter toolbar should include a `转自动化` action that converts the current filtered result set into automation drafts.
- The case edit drawer should render all configured interface fields instead of only a fixed subset.
- Case library pagination and controls should use Chinese display text.
- The case library should support bulk deletion of saved cases, including future project/module scoped deletion flows.
- The current phase case library UI should support:
  - selection-based batch delete,
  - delete by current filtered scope.

AI review wording decision:

- User-facing `AI复核` wording should be renamed to `AI评审`.
- After AI review runs, generated cases should be tagged with `AI已评审` for later filtering and recognition.

Sidebar usability cleanup decision:

- The secondary sidebar should not show read-only gray summary cards under the primary action buttons for:
  - interface case generation,
  - case library,
  - API automation.
- Removing these summary cards should let the project selector and module list move upward.
- The OpenAPI import input should cover the whole import button as a transparent native file input so real browser clicks reliably open the file picker.

Debug environment cleanup decision:

- After each browser-debugging or automation-debugging session, check whether the temporary Chrome/Playwright window processes are still running.
- If residual debug browser processes remain, close only the temporary debug/browser automation process tree and keep the user's normal Chrome profile/processes untouched.
- Treat this cleanup check as part of the debugging done-definition so leftover automation windows do not affect later tests.

## Version Control Setup

Repository initialization decision:

- `/path/to/Haitlify` should be initialized as the root Git repository for the AI test platform.
- Source code, design documents, OpenAPI sample files, and the provided UI reference image should be version-controlled.
- Local agent/tool artifacts should be ignored:
  - `.codex/`
  - `.omo/`
  - `.omx/`
  - `.playwright-mcp/`
  - `.superpowers/`
  - `temp/`
- Frontend dependency/build artifacts should be ignored:
  - `node_modules/`
  - `dist/`
  - `*.tsbuildinfo`
- `redoc-yaml-converter-master-Golutra/` already contains its own `.git` directory and should not be added into the new root repository by default.

## Source Material Reviewed

- `/path/to/reference-materials/【项目】测试用例管理系统（源码+论文）.html`
  - Traditional test case lifecycle management platform.
  - Key capabilities: project/version/module management, test case CRUD, import/export, task assignment, execution status, role permissions, audit logs, recycle bin, statistics, enterprise WeCom notifications.
- `/path/to/reference-materials/告别搬运！自研AI用例平台：支持原型图识图，脑图Excel全在线编写.html`
  - AI test case generation platform.
  - Key capabilities: PRD text to test cases, prototype image/PDF recognition, prompt templates, online XMind, online table editing, OpenAPI-style structured generation, future automation integration.
- `/path/to/Haitlify/Haitlify.openapi.json`
  - OpenAPI 3.0.1.
  - 112 paths, about 120 operations.
  - Used as an Apifox/OpenAPI import example for Haitlify API test case generation.
- `/path/to/Haitlify/UcenterAPI.openapi.json`
  - OpenAPI 3.0.1.
  - 97 paths, about 107 operations.
  - Used as an Apifox/OpenAPI import example for Ucenter API test case generation.

## Product Roadmap Decision

The product will be built in phases:

1. Phase A: AI generation first.
   - Generate structured test cases from PRD/requirement text.
   - Generate structured test cases from prototype images and PDFs.
   - Import Apifox-exported OpenAPI JSON and generate interface test cases.
   - Provide preview, editing, and saving of generated cases.
2. Phase B: Test case management foundation.
   - Add project, version, module, case set, assignment, execution, statistics, permissions, audit logs, and recycle bin.
3. Phase C: Advanced Excel-like editing later.
   - Full online spreadsheet behavior, advanced copy/paste, shortcuts, field mapping, and deeper XMind support are deferred.

## OpenAPI / Apifox Compatibility

First version will support standard OpenAPI 3.0.1 JSON exported from Apifox.

The importer should parse:

- `info.title`, `info.version`, `info.description`
- `tags`
- `paths`
- HTTP method
- operation summary / operationId
- path parameters
- query parameters
- header parameters
- request body schema/examples
- response schema/examples

Generated interface test cases should include:

- Positive cases
- Missing required parameter cases
- Parameter type error cases
- Boundary value cases
- Missing authentication cases
- Insufficient permission cases
- Data not found cases
- Duplicate submission cases
- Business rule exception cases

## Output Strategy

Chosen option: generate both human-readable test case tables and future executable interface case data.

First version should prioritize structured Chinese test case tables for review, manual execution, and export.
The data model should reserve fields needed for later executable interface tests, such as request data, assertion rules, variables, and dependency handling.

## Interface Test Case Table Headers

Default interface test case headers:

- 用例ID
- 项目
- 模块
- 接口名称
- 请求方法
- 请求路径
- 用例标题
- 用例类型
- 前置条件
- 请求头
- 路径参数
- 查询参数
- 请求体
- 测试步骤
- 预期结果
- 断言规则
- 优先级
- 执行结果
- 执行人
- 执行时间
- 缺陷ID
- 标签
- 来源文件
- 备注

Column naming decisions:

- Use `项目`, not `所属系统`.
- Use `模块`, not `所属模块`.

## Functional Test Case Table Headers

Default functional test case headers:

- 用例ID
- 项目
- 模块
- 功能点
- 用例标题
- 用例类型
- 前置条件
- 测试步骤
- 预期结果
- 优先级
- 执行结果
- 执行人
- 执行时间
- 缺陷ID
- 标签
- 来源类型
- 备注

## Default Case Type Dictionary

Default case types:

- 正向
- 必填缺失
- 参数类型错误
- 边界值
- 鉴权缺失
- 权限不足
- 数据不存在
- 重复提交
- 业务规则异常

## Configuration Decision

Chosen option: system defaults plus project-level customization.

The platform must not hard-code table headers or case types.

First version should support:

- Built-in default field templates for interface test cases and functional test cases.
- Project-level custom field templates copied from defaults.
- Add, edit, delete, hide/show, and reorder fields.
- Configure field data type, required flag, export flag, default value, and whether AI should generate the field.
- Built-in default case type dictionary.
- Project-level custom case types.
- Add, rename, disable, and configure case types.
- Configure case type applicability: interface cases, functional cases, or all cases.

## Current Recommendation

Use the configurable template model from the start, but keep the first UI simple:

- System default templates are available immediately.
- Each project can copy and customize templates.
- Each AI generation task chooses a template and selected case types.
- Execution-only fields can default to `未执行` or remain empty until Phase B execution flow is implemented.

## External Project Research

Research date: 2026-06-10.

Candidate projects reviewed:

- MeterSphere
  - Repository: `https://github.com/metersphere/metersphere`
  - Strengths:
    - Open-source continuous testing platform.
    - Has AI assistant capabilities for functional and interface cases.
    - Includes test management, API testing, interface definitions, mock, scenario automation, reports, team collaboration, and plugin system.
    - Tech stack is close to the product direction: Spring Boot, Vue, MySQL, Kafka, MinIO, Redis, Docker, JMeter.
  - Risks:
    - Large platform with high customization cost.
    - License has secondary-development obligations and restrictions, including logo/copyright constraints and GPLv3-style obligations.
    - Direct fork may not fit a company-specific AI generation workflow, configurable Chinese headers, Apifox-focused export adapter, and lightweight Phase A scope.
- Kiwi TCMS
  - Repository: `https://github.com/kiwitcms/Kiwi`
  - Strengths:
    - Mature open-source test management system with manual/automated testing support, bug tracker integration, access control, reports, and API layer.
  - Risks:
    - Python/Django test-management product, not focused on AI generation or OpenAPI/Apifox interface test generation.
    - GPL-2.0 license creates adoption constraints.
- Schemathesis
  - Repository: `https://github.com/schemathesis/schemathesis`
  - Strengths:
    - Actively maintained OpenAPI/GraphQL API testing tool.
    - Generates inputs from schemas, adapts to responses, and can chain operations into workflows.
    - Good future candidate for automated execution/fuzzing engine integration.
  - Risks:
    - It is an API testing engine, not a test case management platform or AI test case table generator.
- EvoMaster
  - Repository: `https://github.com/WebFuzzing/EvoMaster`
  - Strengths:
    - Open-source AI-driven system-level test generation tool for REST/GraphQL/RPC.
    - Can generate effective regression test suites.
  - Risks:
    - Heavier automation/fuzzing tool, not a lightweight AI test platform foundation.
    - LGPL-3.0 considerations.
- Dredd
  - Repository: `https://github.com/apiaryio/dredd`
  - Strengths:
    - Language-agnostic API description validation against backend implementations.
  - Risks:
    - Validation tool, not a test management/AI generation platform.

Current recommendation:

- Do not directly fork an existing project as the product foundation.
- Create a new backend project for this AI test platform.
- Use external projects as references or later integrations:
  - MeterSphere for product/module boundary inspiration.
  - Schemathesis as a future OpenAPI execution/fuzzing engine candidate.
  - EvoMaster as a later advanced automation research candidate.

Reasoning:

- No reviewed project simultaneously satisfies the agreed Phase A requirements:
  - configurable Chinese field templates,
  - configurable case type dictionary,
  - OpenAPI/Apifox import to structured Chinese cases,
  - AI review before saving,
  - internal executable metadata model,
  - future Apifox export adapter,
  - lightweight first-phase implementation.
- Directly basing on MeterSphere would provide many features but introduces heavy codebase, product-shape, and license constraints.

## MeterSphere Reference Strategy

MeterSphere is considered a strong product reference, not a codebase foundation.

Confirmed reference points to borrow conceptually:

- Product layering
  - Reference the `system -> organization -> project` collaboration model.
  - Our first version can simplify to `workspace/tenant -> project` if organization management is not needed immediately.
- AI-assisted testing
  - Reference the idea of AI-assisted functional and interface case generation.
  - Our implementation remains centered on configurable Chinese field templates, OpenAPI/Apifox import, and AI review before saving.
- Test management lifecycle
  - Reference the flow from case library to test plan/execution/defect/report.
  - Our Phase A stops at generation, review, preview, save, and export.
  - Phase B adds assignment, execution, status, statistics, and defect linkage.
- API testing capabilities
  - Reference interface definition, debug, mock, scenario automation, and reports as long-term directions.
  - Our Phase A only stores executable metadata and supports future Apifox-compatible export.
- Extension model
  - Reference the idea of an extension/plugin boundary.
  - Our first practical version should use internal adapter interfaces:
    - OpenAPI importer adapter
    - AI model provider adapter
    - export adapter
    - future execution-engine adapter
- Technology inspiration
  - MeterSphere uses Spring Boot, Vue, MySQL, Redis, MinIO, Kafka, Docker, and JMeter.
  - Our first backend should stay lighter than MeterSphere and add infrastructure only when the phase requires it.

Design principle:

- Borrow product architecture ideas from MeterSphere.
- Avoid copying source code, UI assets, brand, or license-bound materials.
- Keep our Phase A narrow and purpose-built.

## Technology Stack Direction

Decision: choose an enterprise-oriented backend stack and develop incrementally.

Backend recommendation:

- Java 21 LTS
- Spring Boot 3.5.x as the conservative enterprise baseline for first implementation
- Modular monolith first, not microservices
- Maven or Gradle; Maven is acceptable for enterprise familiarity
- MySQL as primary relational database
- Redis only when needed for caching, locks, async status, or later execution orchestration
- MinIO/S3-compatible object storage for uploaded OpenAPI files, prototype images, PDFs, and export artifacts when file persistence becomes necessary
- Spring Security for authentication/authorization
- Spring Actuator + Micrometer for health checks, metrics, and observability
- Flyway or Liquibase for database migrations

Accepted architecture refinement:

- The main platform backend should stay on Java + Spring Boot.
  - It owns projects, templates, case libraries, automation cases, execution reports, defects, permissions, audit trails, exports, and system configuration.
  - It is the system of record and should provide the stable API contract consumed by the Vue frontend.
- Python is accepted as the future AI capability service, not the primary business backend.
  - It should focus on LLM orchestration, prompt experiments, OCR, prototype/PDF understanding, document parsing, vector retrieval, and AI review/generation enhancements.
  - The Java backend should call it through a clear provider/service adapter so AI providers can be replaced later.
- Go is accepted as a future optional execution node, not the first backend foundation.
  - It is suitable for high-concurrency API execution, distributed runner agents, internal-network proxy execution, and lightweight Linux deployment nodes.
  - The first implementation can keep execution in the Java backend or a simple server-side runner contract, then introduce Go when concurrency or deployment isolation makes it necessary.
- The recommended long-term layout is:
  - `apps/web`: Vue 3 frontend.
  - `server/platform`: Java Spring Boot main platform.
  - `services/ai-worker`: Python AI/OCR/document-processing service, added when AI capabilities move beyond local rules.
  - `runners/api-runner`: Go API execution worker, added when high-concurrency or distributed execution is required.

Rationale:

- The product is intended for enterprise use and gradual long-term development.
- Spring Boot aligns better with MeterSphere-style enterprise product architecture than a lightweight Python-only backend.
- Spring Boot provides production-ready operations capabilities such as health checks, metrics, and externalized configuration.
- A modular monolith keeps Phase A simple while preserving clear boundaries for later split-out services.
- Java 21 LTS is a conservative baseline. Spring Boot 4 is already stable, but first implementation should use Spring Boot 3.5.x unless dependency validation shows Boot 4 compatibility is clean.
- A Python-only backend would fit AI experiments but is weaker as the long-term system of record for enterprise test management, permissions, audits, and workflow-heavy data.
- A Go-only backend would fit execution performance and deployment simplicity, but the current priority is business modeling, configuration, reporting, and management workflows rather than raw throughput.
- A mixed architecture keeps the first backend practical while preserving future AI and execution-engine upgrade paths.

Priority implementation areas:

1. Test case and case library capabilities.
2. API automation-related data model and OpenAPI/Apifox import.
3. AI generation and AI review.
4. Preview, editing, save, and export.
5. Later: execution plans, automated execution engine, reports, and Apifox-compatible automation export.

Initial backend module boundary:

- `core-project`
  - Projects, members, project-level settings.
- `case-template`
  - Field templates, case type dictionaries, project-level overrides.
- `openapi-import`
  - OpenAPI/Apifox parsing, imported spec management, interface grouping and selection.
- `ai-generation`
  - Prompt templates, provider adapters, generation granularity, AI review.
- `case-library`
  - Generated case persistence, visible fields, executable metadata, filtering, single-case edits.
- `export-adapter`
  - Chinese Excel/CSV export first; later Apifox-compatible automation export.
- `api-automation`
  - Reserved module for future execution plans, run records, assertions, variable extraction, and engine adapters.

Frontend direction:

- Vue 3 + TypeScript + Element Plus is preferred if the team wants to stay closer to MeterSphere-style enterprise UI.
- React + Ant Design is also viable, but the current reference direction leans Vue.

UI direction note:

- First UI style exploration round was rejected by the user.
- Rejected directions:
  - Quiet operations dashboard
  - Quality lab
  - Developer console
- Next style exploration should move away from generic enterprise dashboard styling and focus more on concrete product references and workflows:
  - Apifox-like API workspace
  - test management workbench
  - R&D quality control workspace
  - dense, practical, tool-first layouts rather than decorative dashboard cards.
- Second direction decision:
  - User accepted the `Apifox-like API workspace` information architecture, but rejected the old-looking component styling.
  - New visual reference provided by user:
    - `/path/to/Haitlify/流程参考图ScreenShot_2026-06-10_122423_285.png`
  - Style should move toward a modern Feishu/Lark-like workflow workspace:
    - narrow left app navigation with colored square icons,
    - secondary list pane,
    - large white process/canvas area,
    - lightweight flow nodes connected by thin curves,
    - detail panel/card below or beside selected node,
    - blue active states,
    - small green status dots and compact status pills,
    - thin dividers, soft shadows, restrained borders,
    - high-density tables and forms without old heavy components.
  - Red rectangles in the reference image are annotations, not part of the visual style.
  - Final accepted style:
    - Modern interface workflow workspace.
    - Keep Apifox-like workspace information architecture.
    - Use Feishu/Lark-like process workspace component styling from the provided reference image.
  - Primary left navigation requirement:
    - The first-level sidebar must support collapsed and expanded states.
    - Collapsed state shows concise icons only.
    - Expanded state has enough width to show full feature names.
    - This should be a real layout behavior, not only a visual trick.

Architecture principle:

- Prioritize test case generation and API automation foundations.
- Avoid overbuilding organization hierarchy, real-time collaboration, full Excel-like editing, and heavy execution orchestration in Phase A.
- Keep extension seams for AI providers, import formats, export formats, and execution engines.

## Portability Requirements

Development may happen on macOS, but the system should run on Linux in later environments.

Portability decisions:

- Use Docker Compose for local dependencies.
  - MySQL, Redis, and MinIO should run in containers during development.
  - Avoid relying on services installed directly on macOS.
- Keep application runtime portable.
  - Backend uses Java 21 LTS.
  - Build should produce a Linux-runnable Spring Boot artifact or container image.
  - Frontend build should be static assets that can run behind Nginx or another Linux web server.
- Avoid OS-specific paths.
  - No hard-coded `<user-home>/...` paths in application configuration.
  - Use environment variables and relative workspace paths.
  - Store uploaded files through an object storage abstraction rather than local filesystem assumptions.
- Keep scripts cross-platform where practical.
  - Prefer Maven/Gradle and npm/pnpm scripts over shell-only workflows.
  - If shell scripts are needed, keep them POSIX-compatible and test them on Linux.
- Normalize file handling.
  - Treat filenames and extensions case-sensitively because Linux is case-sensitive.
  - Ensure UTF-8 everywhere for Chinese headers and generated content.
  - Avoid assumptions about macOS line endings or filesystem behavior.
- Configuration model:
  - Use `.env` or environment variables for database, Redis, MinIO, AI provider, and upload settings.
  - Keep separate profiles: `local`, `test`, `prod`.
  - Do not commit secrets.
- Containerization:
  - Add Dockerfile for backend.
  - Add Dockerfile or static Nginx image for frontend when frontend is created.
  - Add `docker-compose.yml` for development dependencies and optional full local stack.
- Database migration:
  - Use Flyway or Liquibase so schema is reproducible across Mac and Linux.
- File storage:
  - Prefer MinIO/S3-compatible storage for uploaded OpenAPI files, images, PDFs, and export artifacts.
  - Local filesystem storage can exist only as a development adapter.

Implementation principle:

- Develop incrementally on macOS, but every feature should be designed as if it will run in Linux containers.

## OpenAPI Generation Granularity

Default generation strategy: risk-based coverage.

The default should not be hard-coded as the only behavior. The web UI must provide an entry for selecting the generation granularity level when creating an AI generation task.

Generation granularity levels:

- Basic coverage
  - Generate fewer cases for quick review.
  - Typical cases: positive, missing required fields, missing authentication.
- Risk-based coverage
  - Default level.
  - Generate base cases for every interface, then add cases according to HTTP method, parameters, authentication, permission, business keywords, and response/error hints.
  - Typical cases: positive, missing required fields, parameter type errors, boundary values, missing authentication, insufficient permission, data not found, duplicate submission, and business rule exceptions.
- Full matrix coverage
  - Generate richer combinations for deeper testing.
  - Useful for high-risk modules, but may create a large number of cases and should not be the default.

The granularity setting should be available at least at generation-task level. Project-level default granularity can be added so each project can preselect its normal strategy while still allowing per-task override.

## OpenAPI Generation Scope

The platform must support both generation scopes:

- Generate from the entire imported OpenAPI/Apifox file.
  - Useful for first-time full coverage or when importing a small API file.
  - The UI should show an estimated case count before generation.
- Generate from selected modules/tags and selected interfaces.
  - Useful for large API files such as haitlify and UcenterAPI.
  - Users should be able to filter by tag/module, HTTP method, path keyword, and interface summary keyword.
  - Users should be able to select all interfaces in a module or select individual interfaces.

Recommended default flow: after importing an OpenAPI file, show an interface selection step grouped by module/tag, while still providing a clear "generate all" action.

## Generated Case Preview And Editing

First version preview page should include:

- Chinese table headers based on the selected field template.
- Filter by project, module, case type, priority, execution result, tag, and keyword.
- Group or collapse cases by module/tag.
- Single-case editing before saving.
- Delete unwanted generated cases before saving.
- Show generated case count and selected case count.
- Export preview results after generation.
- AI review / second-pass optimization before saving.

Batch editing is deferred to a later phase.

Deferred batch editing examples:

- Bulk update priority, tags, executor, execution result, or module.
- Batch delete by filter.
- Copy/paste multi-row editing.
- Excel-like keyboard-driven editing.

## AI Review Before Saving

Generated cases should support an AI review step before saving to the case library.

The review should check for gaps such as:

- Missing authentication cases
- Missing permission or tenant-isolation cases
- Missing required-field cases
- Missing parameter type error cases
- Missing boundary-value cases
- Missing data-not-found cases
- Missing duplicate submission or idempotency cases
- Missing business-rule exception cases
- Weak or vague expected results
- Missing response assertions
- Inconsistent case titles, priorities, or tags

The first version should allow users to:

- Run AI review after generation.
- See review findings grouped by module/interface/case type.
- Accept suggested new cases.
- Apply suggested edits to existing cases.
- Ignore suggestions and save as-is.

AI review trigger decision:

- Use configurable behavior.
- Project-level setting defines the default behavior.
- Each generation task can temporarily override the project default.
- Recommended default: run AI review automatically after generation for normal-sized tasks.
- Users should be able to disable automatic review for large files or quick drafts to avoid long waits.

## Apifox Automation Export Compatibility

Later phases should support exporting generated interface test cases in a format that can be imported into Apifox for automated interface testing.

Design requirement:

- Keep the internal data model richer than the Chinese table so export adapters can map cases to external tools.
- Preserve executable fields for interface automation:
  - method
  - path
  - base URL / environment reference
  - headers
  - path parameters
  - query parameters
  - request body
  - preconditions
  - variable extraction
  - assertions
  - dependency/order metadata
  - expected status code and response body assertions
- Add an export adapter layer instead of binding the core case model directly to Apifox.
- Before implementation, verify the current official Apifox import/export format and automated test case import capability.

Planned export targets:

- Chinese Excel/CSV case table for review and manual execution.
- Internal JSON case model for persistence and future execution.
- Apifox-compatible automation export in a later phase after official format verification.

## Internal Case Model

Chosen scope: recommended executable metadata range, with extension points for later optimization.

The case model should have two layers:

1. Visible Chinese table fields
   - Used for review, editing, manual execution, and Excel/CSV export.
   - Based on the selected field template.
2. Internal executable metadata
   - Persisted with each interface test case.
   - Used later for automated execution and Apifox-compatible export adapters.

Internal executable metadata should include:

- `sourceSpecId`
- `sourceFile`
- `sourceOperationId` / `operationId`
- `tag` / module
- `method`
- `path`
- `baseUrlRef`
- `environmentId`
- `authProfileId`
- `contentType`
- `headers`
- `pathParams`
- `queryParams`
- `body`
- `expectedStatus`
- `assertions`
- `expectedBodyRules`
- `variableExtractions`
- `dependsOnCaseIds`
- `runOrder`
- `generationMeta`
- `reviewMeta`
- `exportMeta`

Future extension points should allow adding:

- pre-request scripts
- post-request scripts
- retry strategy
- test data sources
- run plan / suite metadata
- report aggregation metadata
- external tool mapping metadata

Do not make the first implementation automation-heavy, but avoid a table-only model that would block later automation.

## Template-Aware Generation And Response Body Matching

Decision date: 2026-06-15.

Template field visibility is part of the generation contract, not only table presentation.

- Hidden interface fields are cleared from newly generated rows.
- Hidden executable request fields also clear their matching metadata before saving or converting to automation:
  - `headers`
  - `pathParams`
  - `queryParams`
  - `body`
  - `expectedResponseBody`
- Case editing renders only currently visible template fields.
- Existing stored cases remain compatible even when the new fields are missing.

Two built-in interface fields were added:

- `expectedResponseBody` / `预期返回体`
- `actualResponseBody` / `实际返回体`

OpenAPI import should read 2xx response `example`, `examples`, or JSON schema samples into `expectedResponseBody`.

API automation now supports response body parameter-path matching:

- It compares expected response body paths with actual response body paths.
- It does not require values to be equal in this phase.
- The pass threshold is adjustable from 0 to 100 percent.
- The default threshold is 100 percent.
- Failure messages should show the match ratio and missing response body paths.

Case-library maintenance includes:

- Conditional bulk field update over the selected rows, or the current filtered result when nothing is selected.
- JSON field assignment for `pathParams`, `queryParams`, and `body`.
- Field assignment scans existing JSON paths and writes changes back to row data and executable metadata.

## Template Migration And Delete Safety

Decision date: 2026-06-15.

- Older saved template configurations should be migrated once to include the built-in `预期返回体` and `实际返回体` fields.
- The migration is schema-based so later user deletions are respected after the migration version is saved.
- Delete actions in the UI should require an explicit confirmation dialog styled in the same restrained enterprise theme as the rest of the app.
- When automation cases are regenerated from the case library, the current in-memory execution state and log drawer should be cleared to avoid showing stale statuses.
- When automation cases are deleted, only the matching current execution results should be removed from the in-memory execution state.

## Report Navigation And UI Density

Decision date: 2026-06-15.

- The reports module should present projects first, then reveal the history-report column only after a project is selected.
- Before a project is selected, the detail area should show a placeholder instead of a report history list.
- The history-report list should only contain reports that belong to the selected project, with optional environment and keyword filtering inside that scope.
- For performance, report/project counts and automation case project-module counts should be built from indexed view helpers rather than repeated inline filtering in `App.vue`.
- The API case-generation page should remove the estimated-count chip beside the generate button.
- Dense filter rows should be grouped into left-side filters and right-side actions, and action columns should be right-aligned with narrower widths.

## Save Transfer And Export Flow

Decision date: 2026-06-15.

- After generated interface cases are saved, the current generated-case workspace should be cleared, and the success message should direct the user to the case library.
- The interface case-generation page should keep a `转自动化` action.
- `转自动化` should first save the current generated cases into the case library, then generate automation cases from those same cases, and finally navigate to the automation module.
- Automation execution should expose a lightweight real-time progress indicator with completed count, total count, and the currently running case id when available.
- Exported case filenames should include the current project name or selected project scope.

## Defect To Jira Flow

Decision date: 2026-06-15.

- Failed or errored automation results can be transferred into the `缺陷` module as Jira export drafts.
- The first implementation uses Jira CSV export, modeled after the existing sample CSV columns:
  - `概要`
  - `问题类型`
  - `项目名称`
  - `优先级`
  - `经办人`
  - `描述`
  - `向外链接的问题（BLOCK）`
  - `自定义字段（ 故障分类）`
- Drafts should keep an explicit Jira sync status:
  - `待导出 Jira`
  - `已导出 Jira CSV`
  - `已建 Jira`
- The defect data model should reserve `jiraFieldValues` and export mode metadata so later work can support all Jira fields and direct Jira API issue creation without replacing the current draft flow.
- Jira CSV export fields should now be managed through a frontend-local export template:
  - the current eight Jira CSV columns remain the default preset,
  - users can enable or disable export columns,
  - users can rename export headers,
  - users can add custom Jira fields backed by `jiraFieldValues`,
  - the template is stored in browser local storage,
  - export should use only enabled fields from the current template.

## Next Planning Checkpoint

Decision date: 2026-06-15.

The next implementation checkpoint should not start backend persistence or direct Jira API integration yet.

Focus this checkpoint on stabilizing the frontend-local defect and Jira CSV workflow:

- Verify the real browser path:
  - run API automation,
  - transfer failed or errored results to defects,
  - edit Jira draft fields,
  - export Jira CSV,
  - confirm exported status is reflected in the UI.
- Make the defect module practical for daily use:
  - filter by project, status, issue type, priority, assignee, and keyword,
  - export selected drafts or the current filtered scope,
  - delete selected drafts or the current filtered scope,
  - keep delete/export confirmations in the existing restrained enterprise style.
- Make Jira CSV fields extensible:
  - keep the current eight-column Jira CSV as the default preset,
  - add a field-template concept for Jira CSV export,
  - preserve a place for additional Jira fields without changing the defect draft core model,
  - keep future Jira API creation behind an adapter boundary.
- Improve traceability:
  - show an obvious defect/Jira status marker on automation results and report result rows after transfer,
  - avoid creating duplicate defect drafts for the same source automation result,
  - keep links from defect drafts back to source automation case/report data where available.
- Verification for this checkpoint should include:
  - targeted defect repository tests,
  - export-field-template tests,
  - full `npm run test -- --run`,
  - `npm run build`,
  - real web interaction verification in the browser.

Out of scope for this checkpoint:

- Direct Jira API issue creation.
- Backend database persistence.
- Full Jira field coverage UI.
- Apifox strict automation import format verification.

## Backend Platform Foundation

Decision date: 2026-06-15.

- `server/platform` is now the first Java Spring Boot backend foundation for the AI test platform.
- This slice intentionally stays narrow and only establishes:
  - Spring Boot application bootstrap,
  - health endpoint,
  - project creation/list API foundation,
  - Flyway migration bootstrap,
  - portable environment-driven configuration,
  - Docker Compose local dependencies for MySQL, Redis, and MinIO.
- Current module boundary implementation starts with the `core-project` responsibility because it is the smallest persistence-oriented entry point that future template, case-library, automation, report, and defect modules can attach to.
- Tests were added first for:
  - application context bootstrap,
  - `GET /api/system/health`,
  - `POST /api/projects` and `GET /api/projects`.
- Local verification on the current machine now passes after installing Java 21.
  - `server/platform/mvnw -v` resolves Maven 3.9.9 with Java 21.
  - `server/platform/mvnw test` runs the backend foundation tests against the H2 test profile.
  - The verified foundation covers application bootstrap, health API, project creation/listing, and Flyway migration startup.

## Backend Template And Case Library Persistence

Decision date: 2026-06-15.

- `server/platform` now includes the first backend persistence slice for template configuration and interface case library data.
- Project-level interface template APIs were added:
  - `GET /api/projects/{projectId}/case-template/interface`
  - `PUT /api/projects/{projectId}/case-template/interface`
- The template response preserves the frontend-local shape:
  - `kind`
  - `schemaVersion`
  - `interfaceFields`
  - `caseTypes`
- Default interface template data includes the current Chinese field headers, including `预期返回体` and `实际返回体`.
- Interface case library APIs were added:
  - `GET /api/projects/{projectId}/interface-cases`
  - `POST /api/projects/{projectId}/interface-cases`
- The case-library API preserves the current frontend-local saved-case shape:
  - `id`
  - `libraryId`
  - `savedAt`
  - `row`
  - `metadata`
- Saving a case with the same `projectId + libraryId` updates the existing record instead of creating duplicates.
- `V2__template_and_case_library.sql` creates:
  - `case_template_configs`
  - `interface_cases`
- JSON payloads are stored as text in this first slice to preserve flexible frontend metadata while the product model is still evolving.
- Verification now covers:
  - project bootstrap/listing,
  - health endpoint,
  - default template retrieval,
  - project template override persistence,
  - interface case save/list/update behavior.

## Frontend Backend Sync Adapter

Decision date: 2026-06-15.

- The Vue frontend now has a backend sync adapter for the first persistence-backed workflow.
- The adapter keeps localStorage as the immediate source of resilience and tries to sync to the Java backend when available.
- Backend project resolution is currently lightweight:
  - derive a portable project code from the current project name,
  - list backend projects,
  - reuse a matching project by code or name,
  - create the project if it does not exist.
- Template configuration save now writes localStorage first, then attempts:
  - `PUT /api/projects/{projectId}/case-template/interface`
- Generated interface case save now writes localStorage first, then attempts:
  - `POST /api/projects/{projectId}/interface-cases`
- Saved interface case deletion now writes localStorage first, then attempts:
  - `POST /api/projects/{projectId}/interface-cases/delete`
- Deletion sync is grouped by saved case `row.project` so one UI action can safely delete cases from multiple backend projects.
- Saved interface case ordering now remains local-first and syncs the affected project back through:
  - `POST /api/projects/{projectId}/interface-cases`
- Ordering is treated as project-scoped in this slice.
  Cross-project row moves from the `全部项目` view are blocked with a user-facing warning.
- App startup now attempts backend-preferred loading for the current project:
  - template configuration is loaded from `GET /api/projects/{projectId}/case-template/interface`,
  - saved interface cases are loaded from `GET /api/projects/{projectId}/interface-cases`.
- Backend-loaded saved interface cases replace local cases for the same project only.
  Local cases from other projects are preserved.
- Empty backend case lists do not clear local browser cases in this slice.
  This avoids data loss for newly created backend projects before conflict handling exists.
- Minimal startup conflict protection is now in place:
  - template configuration keeps local data when local `updatedAt` is newer than backend `updatedAt`,
  - case-library loading keeps local project cases when the latest local `savedAt` is newer than the latest backend `savedAt`,
  - conflict results are returned from the sync adapter as `source: conflict`.
- The startup conflict slice is now user-visible:
  - the workspace shows a compact `本地数据较新` notice when local template or current-project case-library data wins,
  - the notice can push local data to backend,
  - the notice can explicitly reload backend data with local-overwrite confirmation,
  - the notice can be dismissed without changing current local data.
- If backend sync fails, the UI keeps working from localStorage without blocking the user.
- Saved case maintenance now also syncs to backend after local mutation:
  - single saved-case edit from the edit drawer,
  - `批量修改字段`,
  - `字段赋值`.
- These flows partition by `row.project` and reuse `POST /api/projects/{projectId}/interface-cases`.
- Backend failure keeps the local result and falls back to local-only success messaging.
- The backend allows local Vite frontend origins through CORS for `/api/**`:
  - `http://127.0.0.1:5173`
  - `http://localhost:5173`
- This is the first migration step from frontend-local data to backend persistence.
  Current conflict handling is project-scoped, compact, and action-oriented, without merge UI.

## Web Automation Case Library Roadmap

Decision date: 2026-06-22.

- Web automation should become a first-class case kind instead of being stored as API/interface rows with extra columns.
- The case kind model should expand to include:
  - `interface`,
  - `functional`,
  - `web_automation`.
- Template configuration must distinguish Web automation fields from API/interface fields.
  Web automation fields include browser, URL, page, viewport, structured steps, locators, wait strategy, screenshot strategy, and page assertions.
- The case-library workflow should stay consistent with API automation:
  - generate from source material,
  - preview and review,
  - save into the case library,
  - convert into automation cases,
  - execute,
  - report,
  - transfer failures into defects.
- The execution fields and runner contract are Web-specific and should not reuse API request fields.
- The first version can be simple, but must establish the model boundary:
  - Web automation template kind,
  - Web saved-case model,
  - Markdown/YAML generation,
  - multi-kind case library,
  - Web automation workspace with mock execution.
- The second version should add real Playwright execution through a backend runner contract, Web environments, step logs, screenshots, and normalized Web reports.
- The third version should add MeterSphere-style reusable element library concepts, Word/PDF extraction, and reusable instruction/scenario composition.
- The fourth version should close operational gaps:
  - scheduled runs,
  - artifact retention,
  - trace review,
  - defect integration hardening,
  - permissions,
  - audit,
  - stale local data cleanup.
- MeterSphere is a product reference for UI testing concepts such as element library, UI automation scenarios, and reports.
  Do not copy MeterSphere implementation or license-bound code.
- shadcn/ui is a visual interaction reference for data tables, sidebars, tabs, dialogs, drawers, filters, badges, and row actions.
  The implementation stack remains Vue 3, Element Plus, TypeScript, and Spring Boot.

## Self-Hosted Test Agent AI Integration

Decision date: 2026-07-03.

- AI capability should be implemented as a platform-owned testing-domain Agent, not by embedding Codex, Claude Code, opencode, or similar coding-agent CLIs as the runtime core of the product.
- External coding Agents can still assist platform development, but product runtime AI must stay behind the platform's own AI Gateway, permissions, review status, traceability, cost control, and audit boundaries.
- The first runtime shape should be:
  - frontend upload and analysis actions,
  - backend document parsing,
  - rule-based extraction tools,
  - backend AI Gateway,
  - `Requirement Test Point Analysis Agent`,
  - draft test-point review,
  - confirmed or modified test points generate formal cases.
- The first Agent should be `需求测试点分析 Agent`.
  It is a controlled workflow-style Agent, not a fully autonomous coding Agent.
- AI output must never enter the formal case library directly.
  AI-generated test points start as review drafts and must pass human confirmation.
- Formal case generation may only use test points with review status:
  - `confirmed`,
  - `modified`.
- AI-created but unreviewed test points remain blocked from formal case-library entry:
  - `ai_generated`,
  - `reviewing`,
  - `rejected`.
- The normal rule-based requirement analysis path remains important.
  It should become deterministic tool input for AI rather than being removed.
- V1 should use backend orchestration first:
  - parse document,
  - run rule-based extraction,
  - assemble AI input,
  - call the configured model,
  - validate structured JSON,
  - save draft review items.
- True model-native tool calling can be added in V2 after the first workflow is stable.
- AI configuration must be its own navigation module, not part of template configuration.
- AI configuration should support:
  - enable/disable AI,
  - provider type,
  - Base URL,
  - API Key or credential reference,
  - text model,
  - vision model,
  - embedding model,
  - timeout,
  - max input/output length,
  - retry count,
  - request logging policy,
  - document-content sending policy,
  - connection test.
- The AI configuration module should include:
  - model configuration,
  - Agent configuration,
  - rule configuration,
  - Skill configuration,
  - MCP configuration,
  - call records.
- Agent rules are hard platform constraints, not only prompt text.
  They should cover mandatory review, source evidence, AI-inferred pending-confirmation flags, allowed case kinds, forbidden output patterns, priority rules, field mapping, coverage dimensions, case-library entry rules, and sensitive-document sending policy.
- Built-in Skills must be editable, but the UI must clearly mark them as `内置`.
- Editing a built-in Skill should create an override instead of destroying the original built-in definition.
- Edited built-in Skills should show `内置 / 已自定义`, support restore-to-default, and preserve audit information.
- The Skill model should distinguish:
  - built-in Skill,
  - built-in Skill with override,
  - custom Skill.
- MCP configuration should be a first-class entry for later tool connections such as Figma, Jira, Confluence, GitHub/GitLab, Apifox/Postman, read-only databases, document parsing services, OCR/vision services, Web automation runners, internal knowledge bases, and object storage.
- MCP write operations should default to disabled unless explicitly enabled for a trusted tool and project scope.
- Known implementation gaps and required solutions are recorded in the detailed design document and must be handled during implementation:
  - backend credential safety with masked config responses,
  - system/project/task configuration scope resolution,
  - built-in Skill override governance,
  - backend-enforced rule gates,
  - draft-review persistence separate from the formal case library,
  - requirement evidence and source traceability,
  - AI call records and audit,
  - structured output JSON Schema validation and repair,
  - long-document chunking without silent truncation,
  - MCP safety with default-disabled write operations.
- Difficulty handling strategy:
  - V1 should use a fixed backend workflow service instead of a complex Agent runtime,
  - OpenAI-compatible AI Gateway should be built behind provider-neutral contracts,
  - editable built-in Skills should use override persistence and restore-to-default,
  - review confirmation should be implemented as a dedicated draft status flow,
  - image/UI/Figma understanding should reserve evidence fields in V1 and defer deep vision/Figma analysis to later versions.
- Provider adapter order:
  - OpenAI-compatible provider first,
  - Claude API adapter second,
  - vision model adapter later,
  - local/private model adapter for sensitive data later.
- The generation page should keep two analysis modes:
  - `分析需求`: deterministic rule-based analysis,
  - `AI分析需求`: AI Gateway plus testing Agent, using rule extraction as context.
- AI-generated test points must preserve source traceability including source file, section/page, original text or visual reference, extraction method, confidence, inferred flag, and review status.
- Images and UI designs require visual analysis beyond OCR.
  The platform should reserve fields and service boundaries for layout hierarchy, components, forms, actions, style hints, and interaction intent.
- Future Figma integration should prefer structured design data such as frames, components, variants, prototype flows, design tokens, and auto-layout over screenshot-only recognition.
- Detailed design record:
  - `/path/to/Haitlify/docs/superpowers/specs/2026-07-03-self-hosted-test-agent-ai-integration-design.md`
