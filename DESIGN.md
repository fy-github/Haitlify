# Haitlify Design

> **Transition note:** This document is an archival design baseline. The existing business and Runner surfaces remain supported. The former self-built Agent is deprecated pending successful Hermes integration; Hermes-backed testing workspace migration is planned, not implemented.

## Source of truth

- Status: Active draft
- Last refreshed: 2026-07-21
- Primary product surfaces: AI generation workspace, OpenAPI import wizard, generated case preview, case template settings, case library, API automation, reports, defects, backend sync.
- Evidence reviewed:
  - `docs/ai-test-platform/decisions.md`
  - `docs/superpowers/specs/2026-06-10-ai-test-platform-design.md`
  - `docs/superpowers/specs/2026-06-15-case-template-response-body-design.md`
  - `docs/superpowers/specs/2026-06-15-case-library-delete-order-sync-design.md`
  - `docs/superpowers/specs/2026-06-15-backend-preferred-loading-design.md`
  - `.superpowers/brainstorm/54834-1781070989/content/ui-style-apifox-workspace-v3.html`
  - `.superpowers/brainstorm/54834-1781070989/content/sidebar-collapse-behavior.html`
  - `流程参考图ScreenShot_2026-06-10_122423_285.png`

## Brand

- Personality: calm, precise, gentle, modern, and operational.
- Approved direction: Studio Canvas.
- Brand accent: configurable semantic token bundle; default deep blue `#2854A6`.
- Status colors remain independent from the brand accent.
- Trust signals: clear data lineage, visible review status, predictable export behavior, compact operational controls.
- Avoid: old heavy enterprise component styling, decorative dashboard cards, marketing-style hero layouts, large empty visual areas, and one-note palettes.

## Product goals

- Goals:
  - Generate usable Chinese test cases from requirements, prototypes, and Apifox/OpenAPI files.
  - Help testers review, adjust, save, and export cases quickly.
  - Preserve executable metadata for later API automation.
  - Let API automation drafts run locally enough to validate request shape, response status, response body coverage, and report/defect handoff before the full backend runner exists.
  - Migrate durable Phase A data from browser-local storage toward the Java backend without making the app unusable when the backend is offline.
- Non-goals:
  - Full spreadsheet editing in Phase A.
  - Heavy distributed automated execution engine in Phase A.
  - MeterSphere fork or clone.
- Success signals:
  - Users can import synthetic OpenAPI examples, select interfaces, generate cases, review them, save them, convert them to automation drafts, run a lightweight execution pass, and export Chinese case/report/defect artifacts.

## Personas and jobs

- Primary personas: QA engineers, test leads, backend/API developers assisting test design.
- User jobs:
  - Convert API definitions into test case tables.
  - Convert requirement/prototype inputs into functional cases.
  - Review AI-generated coverage gaps before saving.
  - Export cases for manual review and later automation.
- Key contexts of use: internal enterprise QA workflows, dense desktop workbench, long-lived project libraries.

## Information architecture

- Primary sidebar: `56px` collapsed and `168px` expanded.
- Sidebar toggle: first command in the global top bar using `PanelLeftOpen` and `PanelLeftClose`.
- Core workflow: `需求导入 -> 内容解析 -> AI 生成 -> 质量评审 -> 保存 / 执行`.
- Context and inspector panes appear only for stages that need them and become drawers below `1180px`.
- Primary navigation:
  - First-level left sidebar with collapsible icon-only and expanded full-name states.
  - Secondary resource pane for project/module/spec/interface lists.
  - Main workspace for import, generation, preview, review, and case editing.
- Core routes/screens:
  - Project workspace.
  - AI case generation.
  - OpenAPI/Apifox import.
  - Generated case preview.
  - Case library.
  - API automation.
  - Automation reports.
  - Defect/Jira CSV drafts.
  - Template and case type settings.
- Content hierarchy:
  - Project context first.
  - Input source and generation task second.
  - Generated cases and review findings third.
  - Export/save actions always visible near the current workflow stage.

## Design principles

- Principle 1: Tool-first density. The UI should prioritize scanning, filtering, and repeated action.
- Principle 2: Review before trust. AI output should expose source, coverage, and review state before saving.
- Principle 3: Configurable defaults. Chinese headers and case types have defaults but remain editable.
- Tradeoffs:
  - Prefer clear enterprise workflows over expressive visual decoration.
  - Prefer focused single-case editing in Phase A over complex spreadsheet behavior.

## Visual language

- Light theme first; `dark` and `system` remain contract values with safe light fallback until a dark palette is approved.
- Neutral surfaces dominate; brand color is limited to primary commands and AI focus.
- Standard radii are `6px` to `8px`; elevation is restrained.
- Typography and control sizes remain stable across desktop widths.
- Color: white and light neutral surfaces, blue active states, green status indicators, restrained supporting colors for navigation icons.
- Typography: compact, readable enterprise UI text. No viewport-scaled type. Letter spacing stays at 0.
- Spacing/layout rhythm: dense but breathable. Thin dividers and stable panes.
- Shape/radius/elevation: 8px or less for standard cards and controls unless a component demands otherwise. Soft, restrained shadows.
- Motion: subtle transitions for sidebar collapse, panel selection, and loading states. Respect reduced motion.
- Imagery/iconography: use icons for navigation/actions. Colored square icons are acceptable for first-level nav. The reference image's red rectangles are annotations, not style.

## Components

- Existing components to reuse: none yet; first implementation should establish shared layout, navigation, table, filter bar, wizard, and detail panel components.
- New/changed components:
  - Collapsible primary sidebar.
  - Secondary resource pane.
  - OpenAPI import and interface selection wizard.
  - Generation granularity selector.
  - Generated case table.
  - AI review findings panel.
  - Field template editor.
  - Case type dictionary editor.
  - API automation table and environment drawer.
  - Automation report history and result log drawer.
  - Defect draft table and Jira CSV template drawer.
  - Backend sync adapter for templates and interface case library.
- Variants and states:
  - Sidebar collapsed and expanded.
  - Table loading, empty, filtered-empty, error, dirty-edited, selected.
  - AI review pending, running, completed, skipped, failed.
  - Export pending, completed, failed.
- Token/component ownership: define reusable frontend tokens for color, radius, spacing, shadow, pane widths, and table density when the frontend project is created.

## Accessibility

- Target standard: pragmatic WCAG 2.1 AA direction for internal enterprise UI.
- Keyboard/focus behavior: navigation, table controls, dialogs, filters, and form fields must be reachable by keyboard.
- Contrast/readability: active states and status pills must remain readable on light backgrounds.
- Screen-reader semantics: use semantic buttons, navigation regions, form labels, table headers, and dialog titles.
- Reduced motion and sensory considerations: sidebar and loading transitions should not be essential to comprehension.

## Responsive behavior

- Supported breakpoints/devices: desktop web first for Phase A.
- Layout adaptations:
  - Desktop: primary sidebar, secondary pane, main workspace.
  - Narrow screens: may defer full mobile optimization; preserve functional access rather than pixel parity.
- Touch/hover differences:
  - Collapsed sidebar uses hover tooltips on desktop.
  - Tooltips should not be the only way to discover labels when the sidebar is expanded.

## Interaction states

- Loading: show task progress, estimated counts, and whether AI review is running.
- Empty: explain what input or filter state is missing in concise operational language.
- Error: show recoverable actions, such as re-upload, retry generation, or export again.
- Success: show saved/exported counts and next available action.
- Disabled: disabled actions must indicate missing prerequisite when practical.
- Offline/slow network: backend-backed template and case-library data must fall back to local storage without blocking the workspace. Long generation and review tasks should be resumable or at least status-visible in later iterations.

## Content voice

- Tone: concise, operational, and specific.
- Terminology:
  - Use `项目`, not `所属系统`.
  - Use `模块`, not `所属模块`.
  - Use `用例ID`, `执行结果`, `执行人`, `执行时间`, `缺陷ID`, `标签`.
- Microcopy rules:
  - Prefer action labels that name the result: `生成用例`, `AI 复核`, `保存到用例库`, `导出表格`.
  - Avoid explaining product features in large visible text blocks.

## Implementation constraints

- Framework/styling system: Vue 3 + TypeScript + Element Plus, with custom styling toward the approved workflow workspace.
- Design-token constraints: define stable pane widths, icon button sizes, table row heights, and spacing tokens early.
- Performance constraints: large OpenAPI files require filtering, selection, and estimated case count before generation.
- Persistence constraints: current Phase A writes remain local-first, then best-effort sync to the Java backend. Startup can prefer backend-loaded template and current-project case-library data. When newer local template or case-library data is kept, the UI must show a compact conflict notice with actions to push local data or reload backend data.
- Compatibility constraints: macOS development, Linux runtime, UTF-8 Chinese content, no hard-coded local paths.
- Test/screenshot expectations: after frontend implementation begins, verify key desktop screens with browser screenshots and check for text overflow or incoherent overlap.

## Open questions

- [ ] Choose first AI provider for development / owner: product+engineering / impact: generation adapter implementation.
- [ ] Decide first-login scope / owner: engineering / impact: auth scaffolding.
- [ ] Confirm whether first export must be `.xlsx` or CSV can be accepted for the earliest internal test / owner: product / impact: export adapter priority.
- [ ] Verify strict Apifox automation import format before replacing the current draft export adapter / owner: engineering / impact: external-tool compatibility.
