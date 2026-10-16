> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Platform AI Studio Canvas UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle the existing Platform AI workbench into the approved Studio Canvas layout without changing AI routing, provider persistence, analysis execution, or backend contracts.

**Architecture:** Preserve the existing `platformAiPage`, provider editor, run-policy component, and repository calls. Replace only their presentational composition: one workbench header, one framed work surface per local page, dense toolbar/table patterns, and responsive right-side drawers. All styles consume existing semantic Studio Canvas tokens and Web automation sizing conventions.

**Tech Stack:** Vue 3 composition API, Element Plus, Lucide Vue, Vitest, existing Studio Canvas CSS tokens.

---

### Task 1: Protect the Platform AI UI boundary

**Files:**
- Modify: `apps/web/src/appShellStructure.test.ts`
- Modify: `apps/web/src/App.vue:7756-7964`

- [ ] **Step 1: Write the structural expectations before visual changes**

Add assertions that the AI configuration branch contains the four local pages, one `platform-ai-workbench` root, one `platform-ai-workbench__header`, the `platform-ai-surface` work areas, and the right-side `ResponsiveDrawer`. Assert it does not reintroduce legacy `AI 配置表单`, `AI 总开关`, or `ai-provider-console` markup.

```ts
expect(mainBlock).toContain('platformAiPages')
expect(mainBlock).toContain('data-testid="platform-ai-policies"')
expect(mainBlock).toContain('data-testid="platform-ai-connections"')
expect(mainBlock).toContain('data-testid="platform-ai-diagnostics"')
expect(mainBlock).toContain('data-testid="platform-ai-rules"')
expect(mainBlock).toContain('<ResponsiveDrawer v-model="connectionEditorOpen"')
expect(mainBlock).not.toContain('AI 配置表单')
expect(mainBlock).not.toContain('AI 总开关')
expect(mainBlock).not.toContain('ai-provider-console')
```

- [ ] **Step 2: Run the focused structure test and verify the current branch shape**

Run: `cd apps/web && npm test -- --run src/appShellStructure.test.ts`

Expected: PASS. The test confirms the visual work remains isolated from routing and backend behavior.

- [ ] **Step 3: Keep the page markup semantic and visual-only**

In the AI configuration template, add only classes, data attributes, accessible labels, and UI-local detail selection needed by the new canvas. Do not change `saveAiSettings`, `testAiConnection`, provider repository calls, policy requests, or analysis worker functions.

- [ ] **Step 4: Re-run the focused structure test**

Run: `cd apps/web && npm test -- --run src/appShellStructure.test.ts`

Expected: PASS.

### Task 2: Apply the shared Studio Canvas shell and connection editor treatment

**Files:**
- Modify: `apps/web/src/styles/studio-canvas.css:after existing AI settings block`
- Modify: `apps/web/src/App.vue:7756-7872`

- [ ] **Step 1: Add Platform AI workbench CSS that reuses Web case geometry**

Define `.platform-ai-workbench` as a two-row canvas with a 70px white header and a `surface-page` body. Define a three-track header: 190px identity, flexible local navigation, and actions. Use the existing `--brand-primary`, `--border-default`, `--surface-panel`, `--radius-panel`, and `--radius-control` tokens; do not introduce gradients, literal theme colors, or new brand values.

```css
.platform-ai-workbench {
  display: grid;
  min-width: 0;
  min-height: 0;
  grid-template-rows: 70px minmax(0, 1fr);
  background: var(--surface-page);
}

.platform-ai-workbench__header {
  display: grid;
  grid-template-columns: minmax(190px, .78fr) minmax(360px, 2.4fr) auto;
  gap: 18px;
  padding: 10px 16px;
  background: var(--surface-panel);
  border-bottom: 1px solid var(--border-default);
}
```

- [ ] **Step 2: Style the local navigation and command area**

Make local navigation use the Web case stage treatment: a flat 2px top border active indicator and `--brand-primary-subtle` active surface. Set actions and toolbar controls to 30px/32px with 6px radius. Status uses a dot plus text and no banner.

- [ ] **Step 3: Style the model connection table and edit drawer**

Make `.platform-ai-surface` one 8px framed work surface, `.platform-ai-toolbar` a 52px toolbar, and `.platform-ai-table` a dense borderless table inside that frame. Make the connection drawer use 16px/20px header-body rhythm, vertical divider-separated sections, 32px form controls, and a sticky 56px footer. Masked credential text is supporting copy only.

- [ ] **Step 4: Verify the connection page manually in a browser**

Run the web development server on an available port, open `/ai-config`, select `模型连接`, open `编辑`, and verify: no legacy side rail, no nested cards, right drawer is 520px maximum, inputs do not overflow, and the footer remains visible while scrolling.

### Task 3: Align the policy editor and advanced settings canvas

**Files:**
- Modify: `apps/web/src/components/platformAi/RunPolicyEditor.vue`
- Modify: `apps/web/src/styles/studio-canvas.css`

- [ ] **Step 1: Make the policy editor fit the single framed work surface**

Keep all existing policy emits and request construction. Adjust only the component's scoped CSS so the toolbar is 52px high, the policy list has a 3px active left line, rows are at least 72px, route tiles are rectangular 160px-220px resources, and the selected canvas does not become a nested bordered card.

```css
.run-policy-editor__row.is-active {
  border-left-color: var(--brand-primary);
  background: var(--brand-primary-subtle);
}

.run-policy-tile {
  width: clamp(160px, 18vw, 220px);
  border-radius: var(--radius-control);
  box-shadow: none;
}
```

- [ ] **Step 2: Preserve the route-chain interaction without altering policy data**

Keep click-to-edit, add fallback, remove fallback, and Save event signatures. Add `aria-expanded` to each route tile and ensure the compact edit controls remain keyboard reachable. Do not add drag persistence or change route ordering APIs in this UI-only iteration.

- [ ] **Step 3: Style rules and extensions as a contextual sub-workspace**

Use a 220px extension list with the Web environment selection treatment. Tables and rule controls remain in the remaining canvas, use one divider between list/detail, and use the same 32px controls. Do not change Agent, Skill, MCP, or rule API calls.

- [ ] **Step 4: Run focused structure tests**

Run: `cd apps/web && npm test -- --run src/appShellStructure.test.ts`

Expected: PASS.

### Task 4: Complete responsive and visual verification

**Files:**
- Modify: `apps/web/src/styles/studio-canvas.css`
- Test: `apps/web/src/appShellStructure.test.ts`

- [ ] **Step 1: Add narrow-width rules scoped only to Platform AI**

At 1100px, make local navigation horizontally scrollable and preserve actions. At 760px, place local navigation on a second row, make the workbench header auto-height, stack the policy list above its editor, and use full-width drawers capped by viewport insets. Do not modify global shell breakpoints.

- [ ] **Step 2: Add CSS guard assertions to the existing structure test**

Read `studio-canvas.css` in `appShellStructure.test.ts` and assert the Platform AI CSS contains `grid-template-rows: 70px minmax(0, 1fr)`, a 52px toolbar, `border-left: 3px solid transparent` for resource rows, `max-width: calc(100vw - 16px)` for the drawer, and no `radial-gradient` or `linear-gradient` in the Platform AI CSS block.

- [ ] **Step 3: Run focused tests and production build**

Run:

```bash
cd apps/web
npm test -- --run src/appShellStructure.test.ts
npm run build
```

Expected: both commands exit 0.

- [ ] **Step 4: Capture desktop and narrow screenshots**

At 1440px and 900px widths, inspect all four local pages plus the connection drawer. Confirm the page follows the Web case theme: quiet neutral canvas, deep-blue primary action, no gradient/hero panel, no duplicate navigation, no overflow, and no clipped text.

- [ ] **Step 5: Commit only the UI implementation files**

Stage only `apps/web/src/App.vue`, `apps/web/src/components/platformAi/RunPolicyEditor.vue`, `apps/web/src/styles/studio-canvas.css`, and `apps/web/src/appShellStructure.test.ts` after reviewing the diff for unrelated existing worktree changes. Do not stage backend, routing, analysis-progress, or other pre-existing edits.
