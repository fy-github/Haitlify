> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# AI Test Platform Design

Status: Draft for user review  
Date: 2026-06-10  
Owner: lify + Codex

## 1. Purpose

Build an enterprise-oriented AI test platform that first solves test case generation and API automation foundations, then grows into test case management and execution.

The first implementation focuses on:

- PRD or requirement text to functional test cases.
- Prototype image or PDF to functional test cases.
- Apifox-exported OpenAPI JSON to interface test cases.
- Chinese structured case tables with configurable headers.
- AI review before saving.
- Future-ready executable metadata for API automation and Apifox-compatible export.

## 2. Source Evidence

Reviewed local references:

- `/path/to/reference-materials/【项目】测试用例管理系统（源码+论文）.html`
- `/path/to/reference-materials/告别搬运！自研AI用例平台：支持原型图识图，脑图Excel全在线编写.html`
- `/path/to/Haitlify/Haitlify.openapi.json`
- `/path/to/Haitlify/UcenterAPI.openapi.json`
- `/path/to/Haitlify/流程参考图ScreenShot_2026-06-10_122423_285.png`
- `/path/to/Haitlify/docs/ai-test-platform/decisions.md`

External projects considered:

- MeterSphere: product and module reference only, not codebase foundation.
- Kiwi TCMS: mature TCM reference, not AI/OpenAPI focused.
- Schemathesis, EvoMaster, Dredd: useful later as API execution or validation engines, not platform foundations.

## 3. Roadmap

### Phase A: AI Generation First

Implement the narrow but valuable workflow:

1. Create or choose a project.
2. Import PRD text, prototype image/PDF, or Apifox/OpenAPI JSON.
3. Select generation scope and granularity.
4. Generate Chinese structured test cases.
5. Preview, filter, edit single cases, and delete unwanted cases.
6. Run AI review or apply the project/task review setting.
7. Save to case library.
8. Export Chinese table files.

Phase A must reserve automation metadata for interface cases but should not build a full execution engine.

### Phase B: Test Case Management Foundation

Add project/version/module/case set, assignment, execution result tracking, statistics, permissions, audit logs, recycle bin, and defect linkage.

### Phase C: Advanced Editing And Automation

Add advanced Excel-like editing, deeper XMind support, execution plans, automated API execution, reporting, and Apifox-compatible automated test export.

## 4. Non-goals For First Implementation

- Do not fork MeterSphere or another full testing platform.
- Do not build microservices in Phase A.
- Do not implement full online spreadsheet behavior in Phase A.
- Do not build a heavy API execution engine in Phase A.
- Do not bind the internal model directly to Apifox.
- Do not hard-code machine-specific paths such as `<user-home>/...`.

## 5. Core Product Modules

Use a modular monolith with clear internal boundaries:

- `core-project`: projects, members, project-level settings.
- `case-template`: field templates and case type dictionaries.
- `openapi-import`: OpenAPI/Apifox parsing, imported specs, interface grouping and filtering.
- `ai-generation`: prompt templates, provider adapters, generation granularity, AI review.
- `case-library`: saved cases, visible fields, executable metadata, filtering, single-case edits.
- `export-adapter`: Chinese Excel/CSV first, Apifox-compatible export later.
- `api-automation`: reserved for future execution plans, assertions, variable extraction, and engine adapters.

## 6. OpenAPI And Apifox Compatibility

First version supports standard OpenAPI 3.0.1 JSON exported from Apifox.

The importer must parse:

- `info.title`, `info.version`, `info.description`
- `tags`
- `paths`
- HTTP method
- operation summary and `operationId`
- path parameters
- query parameters
- header parameters
- request body schema and examples
- response schema and examples

The UI must support both:

- Full-file generation.
- Selected tag/module/interface generation.

Filtering before generation:

- Tag/module.
- HTTP method.
- Path keyword.
- Summary keyword.

Before generation, the UI should show an estimated case count so large imports are visible before work starts.

## 7. Generation Granularity

Generation granularity must be configurable from the generation task UI. A project-level default can be added later, but the first usable entry must exist at task level.

Default: risk-based coverage.

Levels:

- Basic coverage: positive cases, missing required fields, missing authentication.
- Risk-based coverage: default. Generate base cases for every interface, then add cases based on method, parameters, authentication, permission, business keywords, and response/error hints.
- Full matrix coverage: deeper combinations for high-risk modules. This may generate many cases and must not be the default.

## 8. Default Case Types

Built-in case types:

- 正向
- 必填缺失
- 参数类型错误
- 边界值
- 鉴权缺失
- 权限不足
- 数据不存在
- 重复提交
- 业务规则异常

Case types must support project-level customization:

- Add.
- Rename.
- Disable.
- Configure applicability: interface, functional, or all.

## 9. Chinese Table Field Templates

Headers are Chinese by default, but they must not be hard-coded. Use system defaults plus project-level customization.

Field templates must support:

- Add, edit, delete.
- Hide/show.
- Reorder.
- Field data type.
- Required flag.
- Export flag.
- Default value.
- Whether AI should generate the field.

### Interface Case Default Headers

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

### Functional Case Default Headers

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

Naming decisions:

- Use `项目`, not `所属系统`.
- Use `模块`, not `所属模块`.

## 10. Internal Case Model

The case model has two layers:

1. Visible Chinese table fields.
2. Hidden executable metadata for interface cases.

Visible fields serve review, editing, manual execution, and Chinese export.

Executable metadata serves later API automation and external export adapters. It should include:

- `sourceSpecId`
- `sourceFile`
- `sourceOperationId` or `operationId`
- `tag` or module
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

Future extension points:

- Pre-request scripts.
- Post-request scripts.
- Retry strategy.
- Test data sources.
- Run plans or suites.
- Report aggregation metadata.
- External tool mapping metadata.

## 11. Generated Case Preview

The first preview page should include:

- Chinese table headers based on the selected template.
- Filters by project, module, case type, priority, execution result, tag, and keyword.
- Grouping/collapse by module or tag.
- Single-case edit.
- Delete generated cases before saving.
- Generated, filtered, and selected counts.
- Export preview.
- AI review before save.

Batch editing is deferred. Examples deferred to later phases:

- Bulk update priority, tags, executor, execution result, or module.
- Batch delete by filter.
- Multi-row copy/paste.
- Excel-like keyboard editing.

## 12. AI Review Before Saving

Generated cases must support an AI review step before saving to the case library.

The review checks:

- Missing authentication cases.
- Missing permission or tenant-isolation cases.
- Missing required-field cases.
- Missing parameter type error cases.
- Missing boundary-value cases.
- Missing data-not-found cases.
- Missing duplicate submission or idempotency cases.
- Missing business-rule exception cases.
- Weak expected results.
- Missing response assertions.
- Inconsistent titles, priorities, or tags.

User actions:

- Accept suggested new cases.
- Apply suggested edits to existing cases.
- Ignore suggestions.
- Save as-is.

Review trigger:

- Project-level setting defines the default behavior.
- Each generation task can override the default.
- Recommended default: automatically review normal-sized tasks.
- Users can disable automatic review for large files or quick drafts.

## 13. Export Strategy

Initial export targets:

- Chinese Excel/CSV case table.
- Internal JSON model for persistence/debugging if useful during development.

Later export target:

- Apifox-compatible automation export.

Design rule: keep an export adapter layer. The core case model must not be shaped only around Apifox. Before implementing Apifox automation export, verify the current official Apifox import/export format and automated test case import capability.

## 14. Technology Stack

Backend:

- Java 21 LTS.
- Spring Boot 3.5.x conservative baseline.
- Maven preferred for enterprise familiarity.
- Modular monolith.
- MySQL primary database.
- Redis only when needed.
- MinIO/S3-compatible object storage when file persistence is needed.
- Spring Security.
- Spring Actuator + Micrometer.
- Flyway or Liquibase.

Backend architecture refinement:

- Use Java + Spring Boot as the main platform backend and system of record.
- Add a Python AI service later for LLM orchestration, OCR, prototype/PDF understanding, document parsing, vector retrieval, and AI review/generation enhancements.
- Add a Go API runner later only when high-concurrency execution, distributed runner agents, or internal-network proxy execution require a separate lightweight execution node.
- Keep the first backend as a modular monolith. Do not start with microservices.
- Keep adapter boundaries for AI providers, document/OCR workers, API execution engines, importers, and exporters so Python and Go services can be introduced without reshaping the core case and automation model.

Recommended future repository layout:

- `apps/web`: Vue 3 frontend.
- `server/platform`: Java Spring Boot main platform.
- `services/ai-worker`: Python AI/OCR/document-processing service.
- `runners/api-runner`: Go API execution worker.

Frontend:

- Vue 3.
- TypeScript.
- Element Plus, customized toward a modern workflow workspace style.

Development/deployment:

- Docker Compose for local dependencies.
- Backend packaged as jar/container.
- Frontend built as static assets, later served behind Nginx or equivalent.
- Profiles: `local`, `test`, `prod`.
- UTF-8 throughout.

## 15. Portability Requirements

The system may be developed on macOS, but it must be designed for Linux runtime.

Requirements:

- No hard-coded local absolute paths.
- Environment variables for database, Redis, MinIO, AI provider, upload, and export settings.
- Case-sensitive filename assumptions.
- POSIX-compatible scripts when scripts are necessary.
- Prefer Maven/npm scripts over OS-specific shell behavior.
- Do not commit secrets.
- Use database migrations so schema is reproducible.
- Prefer object storage abstraction over direct local filesystem dependence.

## 16. UI Direction

Accepted direction: modern interface workflow workspace.

Information architecture:

- Keep an Apifox-like workspace pattern.
- First-level navigation is separate from secondary resource list.
- Workbench is tool-first and dense, not a marketing page or decorative dashboard.

Visual language:

- Feishu/Lark-like process/workflow workspace.
- Narrow left navigation with colored square icons.
- Secondary list/resource pane.
- Large white workflow/canvas area.
- Lightweight flow nodes with thin curves.
- Selected node detail panel/card.
- Blue active states.
- Green status dots and compact status pills.
- Thin dividers, restrained borders, soft shadows.
- High-density tables and forms without old heavy component styling.

Reference image:

- `/path/to/Haitlify/流程参考图ScreenShot_2026-06-10_122423_285.png`
- Red rectangles in the image are annotations, not part of the UI style.

First-level sidebar:

- Supports collapsed and expanded states.
- Collapsed: concise icons only.
- Expanded: wide enough to show full feature names.
- Manual toggle.
- Remember user preference.
- Collapsed hover tooltip shows full function name.
- Main workspace adapts to sidebar width.

## 17. First Implementation Slice

Recommended first coding slice after this spec is approved:

1. Create backend and frontend project skeletons with portable local configuration.
2. Add database migrations for project, field template, case type, imported spec, generated case, and metadata tables.
3. Implement OpenAPI import for `Haitlify.openapi.json` and `UcenterAPI.openapi.json`.
4. Implement template and case type defaults.
5. Implement an interface generation task flow with deterministic mock/rule-based output first, behind an AI provider adapter.
6. Build the import/generation/preview UI shell using the approved workspace style.
7. Add export of generated Chinese table data.

AI provider integration can be introduced through an adapter without blocking core schema and UI work.

## 18. Acceptance Criteria

Phase A foundation is acceptable when:

- The app can import both local OpenAPI sample files.
- The UI can filter/select modules and interfaces before generation.
- The generation task can choose Basic, Risk-based, or Full matrix coverage, with Risk-based default.
- Generated interface cases use the default Chinese headers.
- Functional case templates exist with the agreed Chinese headers.
- Field templates and case types are configurable at project level.
- Preview supports filtering, grouping, single-case edit, delete, counts, and export preview.
- AI review is represented as a configurable step before save.
- Saved interface cases preserve executable metadata.
- The UI uses the accepted modern workflow workspace direction.
- The first-level sidebar can collapse to icons and expand to full names.
- Local development does not depend on macOS-only paths or services.

## 19. Open Questions

- Which AI provider will be used first in the development environment.
- Whether user authentication in the first local build should be real login or development-only placeholder.
- Whether Excel export must be `.xlsx` from day one or whether CSV is acceptable for the earliest internal test.

These questions do not block schema, import, template, preview, and UI shell implementation.
