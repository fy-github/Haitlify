> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Studio Canvas UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply the approved Studio Canvas light-theme shell to the Web requirement-to-case workflow first, while keeping the existing interface-case generation flow reachable and behaviorally unchanged.

**Architecture:** Add semantic theme tokens and small shell/workflow components around the existing `App.vue` state and actions. The phase boundary is explicit: only `generationKind === 'web_automation'` enters Studio Canvas; `generationKind === 'interface'` continues to use the existing top bar, control strip, OpenAPI import, generation, review, save, transfer, and export behavior. Keep parsing, AI, persistence, automation, and synchronization in current domain/repository modules; add pure view-model and draft-repository helpers only where the Web workflow needs derived state or recovery.

**Tech Stack:** Vue 3.5, TypeScript 5.9, Element Plus 2.10, Lucide Vue, Vitest 2.1, Vue Test Utils, jsdom, Playwright.

**Command working directory:** Unless a step explicitly says otherwise, run every `npm`, `npx`, Vitest, Vite, and Playwright command in this plan from `apps/web`. Run Git commands and repository-wide `rg` checks from the repository root.

---

## File Structure

### New theme and shell files

- `apps/web/src/theme/themePreference.ts`: theme preference parsing, resolution, and root-attribute installation.
- `apps/web/src/theme/themePreference.test.ts`: light fallback and future dark/system contract tests.
- `apps/web/src/styles/tokens.css`: Studio Canvas semantic color, spacing, radius, shadow, and Element Plus tokens.
- `apps/web/src/styles/studio-canvas.css`: cross-component shell, workflow, and responsive layout rules.
- `apps/web/src/ui/navigation/appSidebar.ts`: grouped navigation model and active-item mapping.
- `apps/web/src/ui/navigation/appSidebar.test.ts`: navigation labels, targets, and active-item tests.
- `apps/web/src/components/shell/PrimarySidebar.vue`: `56px / 168px` icon-and-label navigation.
- `apps/web/src/components/shell/PrimarySidebar.test.ts`: collapse, labels, active state, Tooltip title, and emitted target tests.
- `apps/web/src/components/shell/AppShell.vue`: global sidebar, top bar, context, canvas, and inspector slots.
- `apps/web/src/components/shell/AppShell.test.ts`: shell geometry-class tests.
- `apps/web/src/components/shell/GlobalTopbar.vue`: top-bar sidebar toggle and global project/search context.
- `apps/web/src/components/shell/GlobalTopbar.test.ts`: stable panel-toggle behavior and accessible labels.

### New workflow files

- `apps/web/src/domain/caseDesignWorkflow.ts`: pure five-stage workflow view model.
- `apps/web/src/domain/caseDesignWorkflow.test.ts`: unlock, fallback, primary-action, and status tests.
- `apps/web/src/domain/caseDesignDraftRepository.ts`: versioned local draft persistence with large-source reattach handling.
- `apps/web/src/domain/caseDesignDraftRepository.test.ts`: round-trip, invalid payload, quota, and source-cap tests.
- `apps/web/src/components/workflow/WorkflowHeader.vue`: task title, autosave state, stepper, and single primary action.
- `apps/web/src/components/workflow/WorkflowHeader.test.ts`: enabled-stage navigation and primary-action tests.
- `apps/web/src/domain/caseDesignSourceView.ts`: source-kind and per-source status mapping.
- `apps/web/src/domain/caseDesignSourceView.test.ts`: file-kind, success, failure, and reattach mapping tests.
- `apps/web/src/components/workflow/RequirementImportStage.vue`: combined document, Figma, and OpenAPI import/parse surface.
- `apps/web/src/components/workflow/RequirementImportStage.test.ts`: source list, local error, retry, and emitted action tests.
- `apps/web/src/domain/caseGenerationBatch.ts`: stable batching and generated-case ID offsets.
- `apps/web/src/domain/caseGenerationBatch.test.ts`: chunking and offset tests.
- `apps/web/src/components/workflow/AiGenerationStage.vue`: generation controls, progress, partial results, and content slots.
- `apps/web/src/components/workflow/AiGenerationStage.test.ts`: disabled, running, partial-success, and retry states.
- `apps/web/src/components/workflow/GeneratedWebCaseTable.vue`: reusable generated Web-case table for generation and review stages.
- `apps/web/src/components/workflow/GeneratedWebCaseTable.test.ts`: essential columns and row-action structure tests.
- `apps/web/src/domain/caseDesignReviewView.ts`: review score, unresolved conflict, warning, and selected-case summary.
- `apps/web/src/domain/caseDesignReviewView.test.ts`: review summary derivation tests.
- `apps/web/src/components/workflow/AiReviewInspector.vue`: contextual AI summary, scores, suggestions, conflicts, and actions.
- `apps/web/src/components/workflow/AiReviewInspector.test.ts`: selected case, empty state, and emitted review actions.
- `apps/web/src/components/workflow/CaseDesignContextPane.vue`: source and module context for generation and review stages.
- `apps/web/src/components/workflow/CaseDesignContextPane.test.ts`: source rendering and module-selection tests.
- `apps/web/src/domain/caseDesignDelivery.ts`: save/automation/execute preflight rules.
- `apps/web/src/domain/caseDesignDelivery.test.ts`: blocker, warning, environment, and save-first tests.
- `apps/web/src/components/workflow/CaseDeliveryStage.vue`: delivery mode, environment, risk summary, and final action.
- `apps/web/src/components/workflow/CaseDeliveryStage.test.ts`: save-only and save-and-run event tests.
- `apps/web/src/components/workflow/WorkflowStateView.vue`: loading, empty, filtered-empty, error, and partial-success variants.
- `apps/web/src/components/workflow/WorkflowStateView.test.ts`: action visibility and accessible status tests.
- `apps/web/src/components/workflow/ResponsiveDrawer.vue`: accessible Element Plus drawer wrapper shared by navigation, context, sources, and inspector.
- `apps/web/src/components/workflow/ResponsiveDrawer.test.ts`: model update, Escape-close, and labelled-dialog tests.
- `apps/web/src/components/workflow/RequirementSourceList.vue`: single reusable source list used inline or inside the import drawer.
- `apps/web/src/ui/useViewportMode.ts`: reactive wide, standard, compact, and narrow viewport mode.
- `apps/web/src/ui/useViewportMode.test.ts`: media-query subscription and cleanup tests.
- `apps/web/src/ui/useOnlineStatus.ts`: reactive browser online/offline state with cleanup.
- `apps/web/src/ui/useOnlineStatus.test.ts`: offline event and listener cleanup tests.

### New browser verification files

- `apps/web/playwright.config.ts`: Chromium desktop projects and Vite web server.
- `apps/web/e2e/studio-canvas.spec.ts`: shell, workflow, compact drawer, keyboard, and screenshot checks.
- `apps/web/e2e/fixtures/caseDesignDraft.ts`: deterministic import, review, and delivery draft snapshots.

### Existing files to modify

- `apps/web/package.json`: Vue component-test and Playwright dependencies/scripts.
- `apps/web/package-lock.json`: dependency lock updates produced by `npm install`.
- `apps/web/src/main.ts`: install theme preference before mount and load new styles in the correct order.
- `apps/web/src/App.vue:1-2055`: import new components/helpers; add workflow, error, delivery, viewport, and draft state.
- `apps/web/src/App.vue:5103-6250`: replace the old rail and Web requirement-flow card/dialog with the new shell and stage views.
- `apps/web/src/App.vue:2519-2670`: make automation execution accept an explicit delivered-case set.
- `apps/web/src/App.vue:3165-3245`: preserve save-first semantics and add optional execution after conversion.
- `apps/web/src/styles.css:1-1665`: remove old shell and requirement-flow rules only after new components are integrated.
- `apps/web/src/domain/webCaseImport.ts:65-120`: support generated-case ID offsets for batched AI output.
- `apps/web/src/domain/webCaseImport.test.ts`: verify offset IDs and preserve existing executability tests.
- `apps/web/src/appShellStructure.test.ts`: replace obsolete old-card/dialog assertions with new component-boundary assertions.
- `apps/web/src/domain/appNavigation.ts`: no route-key rewrite; existing paths remain stable.
- `DESIGN.md`: replace the old visual-language and shell decisions with the approved Studio Canvas baseline.

## Task 1: Add Vue Component-Test Support

**Files:**
- Modify: `apps/web/package.json`
- Modify: `apps/web/package-lock.json`
- Create: `apps/web/src/vueComponentHarness.test.ts`

- [ ] **Step 1: Add a failing jsdom mount test before the harness dependencies exist**

Create `apps/web/src/vueComponentHarness.test.ts`:

```ts
// @vitest-environment jsdom
import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

describe('Vue component test harness', () => {
  it('mounts Vue components in jsdom', () => {
    const Harness = defineComponent({
      template: '<button type="button">Studio Canvas</button>'
    })

    expect(mount(Harness).get('button').text()).toBe('Studio Canvas')
  })
})
```

- [ ] **Step 2: Run the harness test to verify the missing dependency failure**

Run:

```bash
npm test -- --run src/vueComponentHarness.test.ts
```

Expected: FAIL with an import-resolution error for `@vue/test-utils` or `jsdom`.

- [ ] **Step 3: Install the component-test dependencies**

Run from `apps/web`:

```bash
npm install --save-dev @vue/test-utils@^2.4.6 jsdom@^25.0.1
```

Expected: `package.json` and `package-lock.json` include `@vue/test-utils` and `jsdom` under `devDependencies`.

- [ ] **Step 4: Rerun the harness test**

Run:

```bash
npm test -- --run src/vueComponentHarness.test.ts
```

Expected: PASS with `1 passed`.

- [ ] **Step 5: Run the existing unit suite**

Run:

```bash
npm test -- --run
```

Expected: all pre-existing Vitest tests pass before UI extraction starts.

- [ ] **Step 6: Commit the test foundation**

```bash
git add apps/web/package.json apps/web/package-lock.json apps/web/src/vueComponentHarness.test.ts
git commit -m "test: add Vue component test harness"
```

## Task 2: Establish Theme Preference And Studio Canvas Tokens

**Files:**
- Create: `apps/web/src/theme/themePreference.test.ts`
- Create: `apps/web/src/theme/themePreference.ts`
- Create: `apps/web/src/styles/tokens.css`
- Modify: `apps/web/src/main.ts`

- [ ] **Step 1: Write failing theme-contract tests**

Create `apps/web/src/theme/themePreference.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import {
  THEME_PREFERENCE_STORAGE_KEY,
  installThemePreference,
  readThemePreference,
  resolveThemePreference
} from './themePreference'

class MemoryStorage implements Pick<Storage, 'getItem' | 'setItem'> {
  private values = new Map<string, string>()

  getItem(key: string): string | null {
    return this.values.get(key) ?? null
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value)
  }
}

describe('themePreference', () => {
  it('reads only supported preferences', () => {
    const storage = new MemoryStorage()
    storage.setItem(THEME_PREFERENCE_STORAGE_KEY, 'system')
    expect(readThemePreference(storage)).toBe('system')

    storage.setItem(THEME_PREFERENCE_STORAGE_KEY, 'purple')
    expect(readThemePreference(storage)).toBe('light')
  })

  it('falls back to light until a dark palette is available', () => {
    expect(resolveThemePreference('dark', { darkAvailable: false, systemPrefersDark: true })).toBe('light')
    expect(resolveThemePreference('system', { darkAvailable: false, systemPrefersDark: true })).toBe('light')
  })

  it('keeps the future dark and system interface explicit', () => {
    expect(resolveThemePreference('dark', { darkAvailable: true, systemPrefersDark: false })).toBe('dark')
    expect(resolveThemePreference('system', { darkAvailable: true, systemPrefersDark: true })).toBe('dark')
    expect(resolveThemePreference('system', { darkAvailable: true, systemPrefersDark: false })).toBe('light')
  })

  it('installs the resolved theme on the root element', () => {
    const storage = new MemoryStorage()
    storage.setItem(THEME_PREFERENCE_STORAGE_KEY, 'dark')
    const root = { dataset: {} as DOMStringMap }

    expect(installThemePreference({ storage, root, darkAvailable: false, systemPrefersDark: true })).toBe('light')
    expect(root.dataset.theme).toBe('light')
  })
})
```

- [ ] **Step 2: Run the theme test to verify it fails**

Run:

```bash
npm test -- --run src/theme/themePreference.test.ts
```

Expected: FAIL because `themePreference.ts` does not exist.

- [ ] **Step 3: Implement the theme preference contract**

Create `apps/web/src/theme/themePreference.ts`:

```ts
export type ThemePreference = 'light' | 'dark' | 'system'
export type ResolvedTheme = 'light' | 'dark'

export const THEME_PREFERENCE_STORAGE_KEY = 'ai-test-platform.theme-preference'

const supportedPreferences = new Set<ThemePreference>(['light', 'dark', 'system'])

export const readThemePreference = (
  storage: Pick<Storage, 'getItem'> = localStorage
): ThemePreference => {
  const value = storage.getItem(THEME_PREFERENCE_STORAGE_KEY)
  return supportedPreferences.has(value as ThemePreference) ? value as ThemePreference : 'light'
}

export const resolveThemePreference = (
  preference: ThemePreference,
  options: { darkAvailable: boolean; systemPrefersDark: boolean }
): ResolvedTheme => {
  if (!options.darkAvailable) return 'light'
  if (preference === 'dark') return 'dark'
  if (preference === 'system') return options.systemPrefersDark ? 'dark' : 'light'
  return 'light'
}

export const installThemePreference = (options: {
  storage?: Pick<Storage, 'getItem'>
  root?: { dataset: DOMStringMap }
  darkAvailable?: boolean
  systemPrefersDark?: boolean
} = {}): ResolvedTheme => {
  const storage = options.storage ?? localStorage
  const root = options.root ?? document.documentElement
  const systemPrefersDark = options.systemPrefersDark
    ?? window.matchMedia?.('(prefers-color-scheme: dark)').matches
    ?? false
  const resolved = resolveThemePreference(readThemePreference(storage), {
    darkAvailable: options.darkAvailable ?? false,
    systemPrefersDark
  })
  root.dataset.theme = resolved
  return resolved
}
```

- [ ] **Step 4: Add the complete light-theme token file**

Create `apps/web/src/styles/tokens.css`:

```css
:root,
:root[data-theme='light'] {
  --brand-primary: #6f52dd;
  --brand-primary-hover: #6247ce;
  --brand-primary-active: #543bb8;
  --brand-primary-soft: #eeeafd;
  --brand-primary-subtle: #faf8ff;
  --brand-primary-border: #d7cff4;
  --brand-primary-contrast: #ffffff;
  --focus-ring-color: #6f52dd;

  --status-success: #2aa589;
  --status-success-soft: #e6f7f1;
  --status-success-text: #1d7865;
  --status-warning: #e9ad55;
  --status-warning-soft: #fff3df;
  --status-warning-text: #76551f;
  --status-danger: #d95c72;
  --status-danger-soft: #fdecef;
  --status-danger-text: #a6374c;
  --status-info: #5b9ccb;
  --status-info-soft: #eaf4fb;
  --status-info-text: #356f98;

  --text-primary: #252831;
  --text-secondary: #5f6570;
  --text-muted: #686f7a;
  --text-disabled: #a9aeb7;
  --surface-page: #f4f5f8;
  --surface-panel: #ffffff;
  --surface-subtle: #fafbfc;
  --surface-raised: #ffffff;
  --border-subtle: #eceef1;
  --border-regular: #e3e5e9;
  --border-strong: #d7dae0;

  --radius-control: 6px;
  --radius-panel: 8px;
  --shadow-raised: 0 14px 40px rgba(29, 34, 44, 0.08);
  --motion-fast: 120ms;
  --motion-standard: 160ms;
  --font-sans: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;

  --sidebar-collapsed-width: 56px;
  --sidebar-expanded-width: 168px;
  --context-pane-width: 220px;
  --inspector-pane-width: 300px;

  --el-color-primary: var(--brand-primary);
  --el-color-primary-light-3: color-mix(in srgb, var(--brand-primary) 70%, white);
  --el-color-primary-light-5: color-mix(in srgb, var(--brand-primary) 50%, white);
  --el-color-primary-light-7: color-mix(in srgb, var(--brand-primary) 30%, white);
  --el-color-primary-light-9: var(--brand-primary-soft);
  --el-color-primary-dark-2: var(--brand-primary-active);
  --el-text-color-primary: var(--text-primary);
  --el-text-color-regular: var(--text-secondary);
  --el-text-color-secondary: var(--text-muted);
  --el-border-color: var(--border-regular);
  --el-border-color-light: var(--border-subtle);
  --el-fill-color-light: var(--surface-subtle);
  --el-bg-color: var(--surface-panel);
  --el-border-radius-base: var(--radius-control);
  --el-font-size-base: 13px;
}

@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    scroll-behavior: auto !important;
    transition-duration: 0.01ms !important;
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
  }
}
```

- [ ] **Step 5: Install the theme before mounting Vue**

Replace `apps/web/src/main.ts` with:

```ts
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import './styles.css'
import './styles/tokens.css'
import App from './App.vue'
import { installThemePreference } from './theme/themePreference'

installThemePreference()

createApp(App).use(ElementPlus, { locale: zhCn }).mount('#app')
```

- [ ] **Step 6: Run theme tests and the production build**

Run:

```bash
npm test -- --run src/theme/themePreference.test.ts
npm run build
```

Expected: theme tests PASS and `vite build` completes without TypeScript errors.

- [ ] **Step 7: Commit the theme foundation**

```bash
git add apps/web/src/theme apps/web/src/styles/tokens.css apps/web/src/main.ts
git commit -m "feat: add Studio Canvas theme tokens"
```

## Task 3: Build Grouped Sidebar Navigation

**Files:**
- Create: `apps/web/src/ui/navigation/appSidebar.test.ts`
- Create: `apps/web/src/ui/navigation/appSidebar.ts`
- Create: `apps/web/src/components/shell/PrimarySidebar.test.ts`
- Create: `apps/web/src/components/shell/PrimarySidebar.vue`
- Modify: `apps/web/src/App.vue:1-540`
- Modify: `apps/web/src/App.vue:5103-5140`

- [ ] **Step 1: Write failing navigation-model tests**

Create `apps/web/src/ui/navigation/appSidebar.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { activeSidebarItemId, appSidebarGroups } from './appSidebar'

describe('appSidebar', () => {
  it('uses the approved navigation groups and labels', () => {
    expect(appSidebarGroups.map((group) => group.label)).toEqual([
      '测试设计',
      '自动化',
      '质量洞察',
      '配置'
    ])
    expect(appSidebarGroups.flatMap((group) => group.items.map((item) => item.label))).toEqual([
      '智能用例设计',
      '用例库',
      'API 自动化',
      'Web 自动化',
      '执行报告',
      '缺陷管理',
      'AI 设置',
      '模板与字段'
    ])
  })

  it('distinguishes API and Web automation while retaining the existing route key', () => {
    expect(activeSidebarItemId('automation', 'api')).toBe('api-automation')
    expect(activeSidebarItemId('automation', 'web')).toBe('web-automation')
    expect(activeSidebarItemId('api', 'api')).toBe('case-design')
  })
})
```

- [ ] **Step 2: Run the navigation test to verify it fails**

Run:

```bash
npm test -- --run src/ui/navigation/appSidebar.test.ts
```

Expected: FAIL because `appSidebar.ts` does not exist.

- [ ] **Step 3: Implement the grouped navigation model**

Create `apps/web/src/ui/navigation/appSidebar.ts`:

```ts
import type { Component } from 'vue'
import {
  Archive,
  Bug,
  FileText,
  GitBranch,
  MonitorPlay,
  SlidersHorizontal,
  Sparkles,
  WandSparkles
} from 'lucide-vue-next'
import type { NavKey } from '../../domain/appNavigation'

export interface SidebarNavigationItem {
  id: string
  label: string
  navKey: NavKey
  icon: Component
  automationKind?: 'api' | 'web'
}

export interface SidebarNavigationGroup {
  id: string
  label: string
  items: SidebarNavigationItem[]
}

export const appSidebarGroups: SidebarNavigationGroup[] = [
  {
    id: 'test-design',
    label: '测试设计',
    items: [
      { id: 'case-design', label: '智能用例设计', navKey: 'api', icon: WandSparkles },
      { id: 'case-library', label: '用例库', navKey: 'cases', icon: Archive }
    ]
  },
  {
    id: 'automation',
    label: '自动化',
    items: [
      { id: 'api-automation', label: 'API 自动化', navKey: 'automation', automationKind: 'api', icon: GitBranch },
      { id: 'web-automation', label: 'Web 自动化', navKey: 'automation', automationKind: 'web', icon: MonitorPlay }
    ]
  },
  {
    id: 'quality',
    label: '质量洞察',
    items: [
      { id: 'reports', label: '执行报告', navKey: 'reports', icon: FileText },
      { id: 'defects', label: '缺陷管理', navKey: 'defects', icon: Bug }
    ]
  },
  {
    id: 'configuration',
    label: '配置',
    items: [
      { id: 'ai-config', label: 'AI 设置', navKey: 'aiConfig', icon: Sparkles },
      { id: 'templates', label: '模板与字段', navKey: 'settings', icon: SlidersHorizontal }
    ]
  }
]

const navItemByKey: Partial<Record<NavKey, string>> = {
  api: 'case-design',
  cases: 'case-library',
  reports: 'reports',
  defects: 'defects',
  aiConfig: 'ai-config',
  settings: 'templates'
}

export const activeSidebarItemId = (
  navKey: NavKey,
  automationKind: 'api' | 'web'
): string => {
  if (navKey === 'automation') return automationKind === 'web' ? 'web-automation' : 'api-automation'
  return navItemByKey[navKey] || ''
}
```

- [ ] **Step 4: Write failing component tests for expanded and collapsed states**

Create `apps/web/src/components/shell/PrimarySidebar.test.ts`:

```ts
// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PrimarySidebar from './PrimarySidebar.vue'
import { appSidebarGroups } from '../../ui/navigation/appSidebar'

describe('PrimarySidebar', () => {
  it('shows icons and text titles when expanded', () => {
    const wrapper = mount(PrimarySidebar, {
      props: {
        groups: appSidebarGroups,
        activeItemId: 'case-design',
        collapsed: false,
        badges: { 'case-library': 128 }
      }
    })

    expect(wrapper.text()).toContain('智能用例设计')
    expect(wrapper.text()).toContain('Web 自动化')
    expect(wrapper.text()).toContain('128')
    expect(wrapper.get('[aria-current="page"]').text()).toContain('智能用例设计')
  })

  it('hides titles but keeps accessible names when collapsed', () => {
    const wrapper = mount(PrimarySidebar, {
      props: {
        groups: appSidebarGroups,
        activeItemId: 'case-design',
        collapsed: true
      }
    })

    expect(wrapper.find('.studio-sidebar__label').exists()).toBe(false)
    expect(wrapper.get('button[aria-label="智能用例设计"]').attributes('title')).toBe('智能用例设计')
  })

  it('emits the selected navigation target', async () => {
    const wrapper = mount(PrimarySidebar, {
      props: {
        groups: appSidebarGroups,
        activeItemId: 'case-design',
        collapsed: false
      }
    })

    await wrapper.get('button[aria-label="Web 自动化"]').trigger('click')
    expect(wrapper.emitted('select')?.[0]?.[0]).toMatchObject({
      id: 'web-automation',
      navKey: 'automation',
      automationKind: 'web'
    })
  })
})
```

- [ ] **Step 5: Run the component test to verify it fails**

Run:

```bash
npm test -- --run src/components/shell/PrimarySidebar.test.ts
```

Expected: FAIL because `PrimarySidebar.vue` does not exist.

- [ ] **Step 6: Implement the sidebar component**

Create `apps/web/src/components/shell/PrimarySidebar.vue`:

```vue
<script setup lang="ts">
import type { SidebarNavigationGroup, SidebarNavigationItem } from '../../ui/navigation/appSidebar'

withDefaults(defineProps<{
  groups: SidebarNavigationGroup[]
  activeItemId: string
  collapsed: boolean
  badges?: Record<string, number>
}>(), {
  badges: () => ({})
})

const emit = defineEmits<{
  select: [item: SidebarNavigationItem]
}>()
</script>

<template>
  <aside class="studio-sidebar" :class="{ 'is-collapsed': collapsed }">
    <div class="studio-sidebar__brand">
      <span class="studio-sidebar__brand-mark">QA</span>
      <strong v-if="!collapsed">AI 测试平台</strong>
    </div>

    <nav aria-label="主导航" class="studio-sidebar__nav">
      <section v-for="group in groups" :key="group.id" class="studio-sidebar__group">
        <div v-if="!collapsed" class="studio-sidebar__group-label">{{ group.label }}</div>
        <button
          v-for="item in group.items"
          :key="item.id"
          type="button"
          class="studio-sidebar__item"
          :class="{ 'is-active': item.id === activeItemId }"
          :aria-label="item.label"
          :aria-current="item.id === activeItemId ? 'page' : undefined"
          :title="collapsed ? item.label : undefined"
          @click="emit('select', item)"
        >
          <component :is="item.icon" :size="17" aria-hidden="true" />
          <span v-if="!collapsed" class="studio-sidebar__label">{{ item.label }}</span>
          <span
            v-if="!collapsed && badges[item.id] !== undefined"
            class="studio-sidebar__badge"
          >
            {{ badges[item.id] }}
          </span>
        </button>
      </section>
    </nav>
  </aside>
</template>

<style scoped>
.studio-sidebar {
  width: var(--sidebar-expanded-width);
  min-width: var(--sidebar-expanded-width);
  height: 100vh;
  padding: 13px 10px 12px;
  overflow: hidden;
  border-right: 1px solid var(--border-subtle);
  background: var(--surface-subtle);
  transition: width var(--motion-standard) ease, min-width var(--motion-standard) ease;
}

.studio-sidebar.is-collapsed {
  width: var(--sidebar-collapsed-width);
  min-width: var(--sidebar-collapsed-width);
  padding-inline: 9px;
}

.studio-sidebar__brand {
  height: 36px;
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  padding: 0 7px;
  margin-bottom: 14px;
}

.studio-sidebar__brand-mark {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: 7px;
  color: var(--brand-primary-contrast);
  background: var(--text-primary);
  font-size: 10px;
  font-weight: 800;
}

.studio-sidebar__brand strong,
.studio-sidebar__label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.studio-sidebar__brand strong {
  font-size: 13px;
  font-weight: 680;
}

.studio-sidebar__group {
  margin-bottom: 12px;
}

.studio-sidebar__group-label {
  padding: 0 9px 5px;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 650;
}

.studio-sidebar__item {
  position: relative;
  width: 100%;
  height: 34px;
  padding: 0 9px;
  display: grid;
  grid-template-columns: 20px minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  border: 0;
  border-radius: var(--radius-control);
  color: var(--text-secondary);
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.is-collapsed .studio-sidebar__item {
  grid-template-columns: 20px;
  justify-content: center;
  padding: 0;
}

.studio-sidebar__item:hover {
  background: var(--surface-page);
}

.studio-sidebar__item:focus-visible {
  outline: 3px solid var(--focus-ring-color);
  outline-offset: 1px;
}

.studio-sidebar__item.is-active {
  color: var(--brand-primary);
  background: var(--brand-primary-soft);
  font-weight: 650;
}

.studio-sidebar__item.is-active::before {
  content: '';
  position: absolute;
  left: -10px;
  width: 2px;
  height: 18px;
  border-radius: 0 2px 2px 0;
  background: var(--brand-primary);
}

.studio-sidebar__badge {
  min-width: 19px;
  padding: 2px 5px;
  border-radius: 9px;
  color: var(--text-muted);
  background: var(--border-subtle);
  font-size: 10px;
  text-align: center;
}
</style>
```

- [ ] **Step 7: Integrate the sidebar without changing route keys**

In `apps/web/src/App.vue`, import the model and component:

```ts
import PrimarySidebar from './components/shell/PrimarySidebar.vue'
import {
  activeSidebarItemId,
  appSidebarGroups,
  type SidebarNavigationItem
} from './ui/navigation/appSidebar'
```

Add beside the existing navigation computed values:

```ts
const sidebarActiveItemId = computed(() => activeSidebarItemId(activeNav.value, automationKind.value))
const sidebarBadges = computed<Record<string, number>>(() => ({
  'case-library': savedCases.value.length,
  defects: defectIssuesForActiveProject.value.length
}))

const selectSidebarItem = (item: SidebarNavigationItem) => {
  if (item.automationKind) automationKind.value = item.automationKind
  navigateToNav(item.navKey)
}
```

Replace the old `<aside class="primary-rail">...</aside>` block with:

```vue
<PrimarySidebar
  :groups="appSidebarGroups"
  :active-item-id="sidebarActiveItemId"
  :collapsed="railCollapsed"
  :badges="sidebarBadges"
  @select="selectSidebarItem"
/>
```

Do not move the collapse command into `PrimarySidebar`; Task 4 places it in the global top bar.

- [ ] **Step 8: Run navigation, component, structure, and build checks**

Run:

```bash
npm test -- --run src/ui/navigation/appSidebar.test.ts src/components/shell/PrimarySidebar.test.ts src/appShellStructure.test.ts
npm run build
```

Expected: all named tests PASS and the production build succeeds.

- [ ] **Step 9: Commit the grouped sidebar**

```bash
git add apps/web/src/ui/navigation apps/web/src/components/shell/PrimarySidebar.vue apps/web/src/components/shell/PrimarySidebar.test.ts apps/web/src/App.vue
git commit -m "feat: add grouped Studio Canvas sidebar"
```

## Task 4: Add The Stable App Shell And Top-Bar Sidebar Control

**Files:**
- Create: `apps/web/src/components/shell/AppShell.test.ts`
- Create: `apps/web/src/components/shell/AppShell.vue`
- Create: `apps/web/src/components/shell/GlobalTopbar.test.ts`
- Create: `apps/web/src/components/shell/GlobalTopbar.vue`
- Create: `apps/web/src/styles/studio-canvas.css`
- Modify: `apps/web/src/main.ts`
- Modify: `apps/web/src/App.vue:5103-5470`

- [ ] **Step 1: Write failing shell and top-bar tests**

Create `apps/web/src/components/shell/AppShell.test.ts`:

```ts
// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AppShell from './AppShell.vue'

describe('AppShell', () => {
  it('keeps sidebar, top bar, context, canvas, and inspector in stable regions', () => {
    const wrapper = mount(AppShell, {
      props: {
        sidebarCollapsed: false,
        contextVisible: true,
        inspectorVisible: true
      },
      slots: {
        sidebar: '<div data-slot="sidebar" />',
        topbar: '<div data-slot="topbar" />',
        context: '<div data-slot="context" />',
        default: '<div data-slot="canvas" />',
        inspector: '<div data-slot="inspector" />'
      }
    })

    expect(wrapper.classes()).toContain('has-context')
    expect(wrapper.classes()).toContain('has-inspector')
    expect(wrapper.findAll('[data-slot]').map((node) => node.attributes('data-slot'))).toEqual([
      'sidebar',
      'topbar',
      'context',
      'canvas',
      'inspector'
    ])
  })
})
```

Create `apps/web/src/components/shell/GlobalTopbar.test.ts`:

```ts
// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import GlobalTopbar from './GlobalTopbar.vue'

describe('GlobalTopbar', () => {
  it('keeps the sidebar control at the beginning of the top bar', async () => {
    const wrapper = mount(GlobalTopbar, {
      props: {
        sidebarCollapsed: false,
        projectLabel: '订单服务',
        sectionLabel: '测试设计',
        searchValue: ''
      }
    })

    const firstButton = wrapper.get('button')
    expect(firstButton.attributes('aria-label')).toBe('收起侧边栏')
    await firstButton.trigger('click')
    expect(wrapper.emitted('toggleSidebar')).toHaveLength(1)
  })

  it('uses the matching accessible label when collapsed', () => {
    const wrapper = mount(GlobalTopbar, {
      props: {
        sidebarCollapsed: true,
        projectLabel: '订单服务',
        sectionLabel: '测试设计',
        searchValue: ''
      }
    })

    expect(wrapper.get('button').attributes('aria-label')).toBe('展开侧边栏')
  })
})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
npm test -- --run src/components/shell/AppShell.test.ts src/components/shell/GlobalTopbar.test.ts
```

Expected: FAIL because the shell components do not exist.

- [ ] **Step 3: Implement the shell slots**

Create `apps/web/src/components/shell/AppShell.vue`:

```vue
<script setup lang="ts">
defineProps<{
  sidebarCollapsed: boolean
  contextVisible: boolean
  inspectorVisible: boolean
}>()
</script>

<template>
  <div
    class="studio-app-shell"
    :class="{
      'is-sidebar-collapsed': sidebarCollapsed,
      'has-context': contextVisible,
      'has-inspector': inspectorVisible
    }"
  >
    <slot name="sidebar" />
    <section class="studio-app-shell__body">
      <header class="studio-app-shell__topbar">
        <slot name="topbar" />
      </header>
      <div class="studio-app-shell__workspace">
        <slot v-if="contextVisible" name="context" />
        <slot />
        <slot v-if="inspectorVisible" name="inspector" />
      </div>
    </section>
  </div>
</template>
```

- [ ] **Step 4: Implement the top-bar control with Lucide panel icons**

Create `apps/web/src/components/shell/GlobalTopbar.vue`:

```vue
<script setup lang="ts">
import { PanelLeftClose, PanelLeftOpen, Search } from 'lucide-vue-next'

withDefaults(defineProps<{
  sidebarCollapsed: boolean
  projectLabel: string
  sectionLabel: string
  searchValue: string
  searchPlaceholder?: string
  userInitials?: string
}>(), {
  searchPlaceholder: '搜索项目、用例或任务',
  userInitials: 'LY'
})

const emit = defineEmits<{
  toggleSidebar: []
  'update:searchValue': [value: string]
}>()
</script>

<template>
  <div class="studio-global-topbar">
    <div class="studio-global-topbar__context">
      <button
        type="button"
        class="studio-icon-button"
        :aria-label="sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'"
        :title="sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'"
        @click="emit('toggleSidebar')"
      >
        <component :is="sidebarCollapsed ? PanelLeftOpen : PanelLeftClose" :size="17" aria-hidden="true" />
      </button>
      <span class="studio-global-topbar__separator" aria-hidden="true" />
      <div class="studio-global-topbar__breadcrumb">
        <strong>{{ projectLabel }}</strong>
        <span>/ {{ sectionLabel }}</span>
      </div>
    </div>

    <div class="studio-global-topbar__tools">
      <label class="studio-global-search">
        <Search :size="15" aria-hidden="true" />
        <input
          :value="searchValue"
          type="search"
          :placeholder="searchPlaceholder"
          aria-label="全局搜索"
          @input="emit('update:searchValue', ($event.target as HTMLInputElement).value)"
        />
      </label>
      <span class="studio-global-topbar__avatar" aria-label="当前用户">{{ userInitials }}</span>
    </div>
  </div>
</template>
```

- [ ] **Step 5: Add the cross-component shell stylesheet**

Create `apps/web/src/styles/studio-canvas.css`:

```css
body {
  min-width: 0;
  color: var(--text-primary);
  background: var(--surface-page);
  font-family: var(--font-sans);
}

.studio-app-shell {
  width: 100%;
  height: 100vh;
  display: flex;
  overflow: hidden;
  background: var(--surface-page);
}

.studio-app-shell__body {
  min-width: 0;
  flex: 1 1 auto;
  display: grid;
  grid-template-rows: 48px minmax(0, 1fr);
}

.studio-app-shell__topbar {
  min-width: 0;
  border-bottom: 1px solid var(--border-subtle);
  background: var(--surface-panel);
}

.studio-app-shell__workspace {
  min-width: 0;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  overflow: hidden;
}

.studio-app-shell .resource-pane {
  height: 100%;
  min-height: 0;
}

.studio-app-shell.has-context .studio-app-shell__workspace {
  grid-template-columns: var(--context-pane-width) minmax(0, 1fr);
}

.studio-app-shell.has-context.has-inspector .studio-app-shell__workspace {
  grid-template-columns: var(--context-pane-width) minmax(0, 1fr) var(--inspector-pane-width);
}

.studio-app-shell.has-inspector:not(.has-context) .studio-app-shell__workspace {
  grid-template-columns: minmax(0, 1fr) var(--inspector-pane-width);
}

.studio-global-topbar {
  height: 48px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}

.studio-global-topbar__context,
.studio-global-topbar__tools {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 9px;
}

.studio-icon-button {
  width: 30px;
  height: 30px;
  padding: 0;
  display: grid;
  place-items: center;
  border: 1px solid transparent;
  border-radius: var(--radius-control);
  color: var(--text-secondary);
  background: transparent;
  cursor: pointer;
}

.studio-icon-button:hover {
  border-color: var(--border-regular);
  background: var(--surface-subtle);
}

.studio-icon-button:focus-visible,
.studio-global-search:focus-within {
  outline: 3px solid var(--focus-ring-color);
  outline-offset: 1px;
}

.studio-global-topbar__separator {
  width: 1px;
  height: 18px;
  background: var(--border-regular);
}

.studio-global-topbar__breadcrumb {
  min-width: 0;
  display: flex;
  gap: 5px;
  align-items: center;
  white-space: nowrap;
}

.studio-global-topbar__breadcrumb strong {
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
}

.studio-global-topbar__breadcrumb span {
  color: var(--text-muted);
  font-size: 12px;
}

.studio-global-search {
  width: 220px;
  height: 30px;
  padding: 0 9px;
  display: flex;
  align-items: center;
  gap: 7px;
  border: 1px solid var(--border-regular);
  border-radius: var(--radius-control);
  color: var(--text-muted);
  background: var(--surface-subtle);
}

.studio-global-search input {
  min-width: 0;
  flex: 1 1 auto;
  border: 0;
  outline: 0;
  color: var(--text-primary);
  background: transparent;
  font: inherit;
}

.studio-global-topbar__avatar {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: var(--brand-primary-active);
  background: var(--brand-primary-soft);
  font-size: 10px;
  font-weight: 700;
}

.studio-sync-notice {
  margin: 10px 14px 0;
  padding: 10px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border: 1px solid var(--status-warning);
  border-radius: var(--radius-control);
  color: var(--status-warning-text);
  background: var(--status-warning-soft);
}
```

Import it after the legacy stylesheet and semantic tokens in `apps/web/src/main.ts`; this order is mandatory because the current `styles.css` contains legacy `:root` variables that must not override the Studio Canvas token contract:

```ts
import './styles.css'
import './styles/tokens.css'
import './styles/studio-canvas.css'
```

- [ ] **Step 6: Integrate the shell around existing content**

In `apps/web/src/App.vue`, add:

```ts
import AppShell from './components/shell/AppShell.vue'
import GlobalTopbar from './components/shell/GlobalTopbar.vue'

const globalSearch = ref('')
const globalProjectLabel = computed(() =>
  activeProject.value?.title || requirementAnalysisProjectName.value || '默认项目'
)
```

Make the shell conversion through the following exact tag insertions; do not move or rewrite the contents of the existing resource pane or workspace in this task.

Replace the root opening tag:

```vue
<div class="app-shell" :class="{ 'rail-is-collapsed': railCollapsed }">
```

with:

```vue
<AppShell
  :sidebar-collapsed="railCollapsed"
  :context-visible="true"
  :inspector-visible="false"
>
```

Wrap the single `PrimarySidebar` created in Task 3 with the sidebar slot, then insert the top-bar slot immediately after it:

```vue
  <template #sidebar>
    <PrimarySidebar
      :groups="appSidebarGroups"
      :active-item-id="sidebarActiveItemId"
      :collapsed="railCollapsed"
      :badges="sidebarBadges"
      @select="selectSidebarItem"
    />
  </template>

  <template #topbar>
    <GlobalTopbar
      v-model:search-value="globalSearch"
      :sidebar-collapsed="railCollapsed"
      :project-label="globalProjectLabel"
      :section-label="currentNav.label"
      @toggle-sidebar="toggleRail"
    />
  </template>
```

Insert `<template #context>` immediately before the unique `<aside class="resource-pane">` opening tag and insert `</template>` immediately after that aside's matching closing tag. Leave the unique `<main class="workspace">` as the default slot by keeping it directly inside `AppShell`. Replace the root closing `</div>` immediately before the first application-level dialog with `</AppShell>`. The resulting template must contain exactly one `resource-pane`, one `main.workspace`, one `PrimarySidebar`, and one `GlobalTopbar`.

Delete the unique block beginning with `<div v-if="workspaceSyncConflict" class="sync-conflict-panel">` from the resource pane and insert this complete equivalent as the first child of `main.workspace`, before the active-nav templates:

```vue
<div v-if="workspaceSyncConflict" class="studio-sync-notice" role="status">
  <div class="sync-conflict-copy">
    <strong>本地数据较新</strong>
    <span>{{ workspaceSyncConflictText }}</span>
  </div>
  <div class="sync-conflict-actions">
    <el-button size="small" :loading="workspaceSyncLoading" @click="pushLocalWorkspaceToBackend">
      <Upload :size="14" />
      推送本地
    </el-button>
    <el-button size="small" plain :loading="workspaceSyncLoading" @click="reloadBackendWorkspace">
      <Download :size="14" />
      使用后端
    </el-button>
    <el-button
      class="studio-icon-button"
      text
      :disabled="workspaceSyncLoading"
      aria-label="关闭同步提示"
      title="关闭同步提示"
      @click="dismissWorkspaceSyncConflict"
    >
      <X :size="15" aria-hidden="true" />
    </el-button>
  </div>
</div>
```

- [ ] **Step 7: Run shell tests, the full unit suite, and build**

Run:

```bash
npm test -- --run src/components/shell/AppShell.test.ts src/components/shell/GlobalTopbar.test.ts
npm test -- --run
npm run build
```

Expected: all tests PASS and the app builds with one global shell.

- [ ] **Step 8: Commit the stable shell**

```bash
git add apps/web/src/components/shell/AppShell.vue apps/web/src/components/shell/AppShell.test.ts apps/web/src/components/shell/GlobalTopbar.vue apps/web/src/components/shell/GlobalTopbar.test.ts apps/web/src/styles/studio-canvas.css apps/web/src/main.ts apps/web/src/App.vue
git commit -m "feat: add stable Studio Canvas app shell"
```

## Task 5: Add Workflow State, Draft Persistence, And Task Header

**Files:**
- Create: `apps/web/src/domain/caseDesignWorkflow.test.ts`
- Create: `apps/web/src/domain/caseDesignWorkflow.ts`
- Create: `apps/web/src/domain/caseDesignDraftRepository.test.ts`
- Create: `apps/web/src/domain/caseDesignDraftRepository.ts`
- Create: `apps/web/src/components/workflow/WorkflowHeader.test.ts`
- Create: `apps/web/src/components/workflow/WorkflowHeader.vue`
- Modify: `apps/web/src/App.vue:478-1030`
- Modify: `apps/web/src/App.vue:5470-5578`

- [ ] **Step 1: Write failing workflow-view tests**

Create `apps/web/src/domain/caseDesignWorkflow.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import {
  buildCaseDesignWorkflowView,
  reviewRunStatusToWorkflowStatus
} from './caseDesignWorkflow'

describe('caseDesignWorkflow', () => {
  it('starts at import and exposes one parse command', () => {
    const view = buildCaseDesignWorkflowView({
      requestedStage: 'import',
      sourceCount: 2,
      analysisStatus: 'idle',
      confirmedDraftCount: 0,
      generationStatus: 'idle',
      generatedCaseCount: 0,
      reviewAttempted: false,
      reviewStatus: 'idle',
      pendingReviewCount: 0,
      saving: false
    })

    expect(view.activeStage).toBe('import')
    expect(view.primaryAction).toEqual({ label: '解析需求', disabled: false, loading: false })
    expect(view.stages.find((stage) => stage.key === 'parse')?.enabled).toBe(true)
  })

  it('falls back from a locked requested stage', () => {
    const view = buildCaseDesignWorkflowView({
      requestedStage: 'review',
      sourceCount: 1,
      analysisStatus: 'running',
      confirmedDraftCount: 0,
      generationStatus: 'idle',
      generatedCaseCount: 0,
      reviewAttempted: false,
      reviewStatus: 'idle',
      pendingReviewCount: 0,
      saving: false
    })

    expect(view.activeStage).toBe('parse')
    expect(view.primaryAction).toEqual({ label: '正在解析', disabled: true, loading: true })
  })

  it('locks delivery until review has been attempted', () => {
    const view = buildCaseDesignWorkflowView({
      requestedStage: 'deliver',
      sourceCount: 1,
      analysisStatus: 'succeeded',
      confirmedDraftCount: 4,
      generationStatus: 'succeeded',
      generatedCaseCount: 4,
      reviewAttempted: false,
      reviewStatus: 'idle',
      pendingReviewCount: 0,
      saving: false
    })

    expect(view.activeStage).toBe('review')
    expect(view.stages.find((stage) => stage.key === 'deliver')?.enabled).toBe(false)
  })

  it.each(['succeeded', 'failed'] as const)(
    'allows delivery after a terminal %s review attempt, with unresolved items remaining non-blocking',
    (reviewStatus) => {
      const view = buildCaseDesignWorkflowView({
        requestedStage: 'deliver',
        sourceCount: 1,
        analysisStatus: 'succeeded',
        confirmedDraftCount: 4,
        generationStatus: 'succeeded',
        generatedCaseCount: 4,
        reviewAttempted: true,
        reviewStatus,
        pendingReviewCount: 2,
        saving: false
      })

      expect(view.activeStage).toBe('deliver')
      expect(view.primaryAction.label).toBe('确认交付')
      expect(view.pendingReviewCount).toBe(2)
    }
  )

  it.each([
    ['queued', 'running'],
    ['running', 'running'],
    ['merge_ready', 'succeeded'],
    ['succeeded', 'succeeded'],
    ['failed', 'failed'],
    [undefined, 'idle']
  ] as const)('maps backend review status %s to %s', (status, expected) => {
    expect(reviewRunStatusToWorkflowStatus(status)).toBe(expected)
  })

  it('relocks delivery after a generated artifact invalidates the previous review', () => {
    const reviewed = {
      requestedStage: 'deliver' as const,
      sourceCount: 1,
      analysisStatus: 'succeeded' as const,
      confirmedDraftCount: 2,
      generationStatus: 'succeeded' as const,
      generatedCaseCount: 2,
      reviewAttempted: true,
      reviewStatus: 'succeeded' as const,
      pendingReviewCount: 0,
      saving: false
    }

    expect(buildCaseDesignWorkflowView(reviewed).activeStage).toBe('deliver')
    expect(buildCaseDesignWorkflowView({
      ...reviewed,
      reviewAttempted: false,
      reviewStatus: 'idle'
    }).activeStage).toBe('review')
  })

  it('keeps completed stages marked done when the user navigates backward', () => {
    const view = buildCaseDesignWorkflowView({
      requestedStage: 'import',
      sourceCount: 1,
      analysisStatus: 'succeeded',
      confirmedDraftCount: 4,
      generationStatus: 'succeeded',
      generatedCaseCount: 4,
      reviewAttempted: true,
      reviewStatus: 'succeeded',
      pendingReviewCount: 0,
      saving: false
    })

    expect(view.activeStage).toBe('import')
    expect(view.stages.map((stage) => [stage.key, stage.status])).toEqual([
      ['import', 'active'],
      ['parse', 'done'],
      ['generate', 'done'],
      ['review', 'done'],
      ['deliver', 'pending']
    ])
  })
})
```

- [ ] **Step 2: Run the workflow test to verify it fails**

Run:

```bash
npm test -- --run src/domain/caseDesignWorkflow.test.ts
```

Expected: FAIL because `caseDesignWorkflow.ts` does not exist.

- [ ] **Step 3: Implement the pure five-stage workflow model**

Create `apps/web/src/domain/caseDesignWorkflow.ts`:

```ts
export type CaseDesignStage = 'import' | 'parse' | 'generate' | 'review' | 'deliver'
export type WorkflowAsyncStatus = 'idle' | 'running' | 'succeeded' | 'failed'
export type WorkflowStageStatus = 'pending' | 'active' | 'done' | 'error'

export const reviewRunStatusToWorkflowStatus = (
  status: string | undefined
): WorkflowAsyncStatus => {
  if (!status) return 'idle'
  if (status === 'failed') return 'failed'
  if (status === 'merge_ready' || status === 'succeeded') return 'succeeded'
  return 'running'
}

export interface CaseDesignWorkflowInput {
  requestedStage: CaseDesignStage
  sourceCount: number
  analysisStatus: WorkflowAsyncStatus
  confirmedDraftCount: number
  generationStatus: WorkflowAsyncStatus
  generatedCaseCount: number
  reviewAttempted: boolean
  reviewStatus: WorkflowAsyncStatus
  pendingReviewCount: number
  saving: boolean
}

export interface CaseDesignStageView {
  key: CaseDesignStage
  label: string
  enabled: boolean
  status: WorkflowStageStatus
}

export interface CaseDesignWorkflowView {
  activeStage: CaseDesignStage
  stages: CaseDesignStageView[]
  primaryAction: {
    label: string
    disabled: boolean
    loading: boolean
  }
  pendingReviewCount: number
}

const stageDefinitions: Array<{ key: CaseDesignStage; label: string }> = [
  { key: 'import', label: '需求导入' },
  { key: 'parse', label: '内容解析' },
  { key: 'generate', label: 'AI 生成' },
  { key: 'review', label: '质量评审' },
  { key: 'deliver', label: '保存执行' }
]

export const buildCaseDesignWorkflowView = (
  input: CaseDesignWorkflowInput
): CaseDesignWorkflowView => {
  const enabled: Record<CaseDesignStage, boolean> = {
    import: true,
    parse: input.sourceCount > 0,
    generate: input.analysisStatus === 'succeeded' || input.confirmedDraftCount > 0,
    review: input.generatedCaseCount > 0,
    deliver: input.generatedCaseCount > 0
      && input.reviewAttempted
      && (input.reviewStatus === 'succeeded' || input.reviewStatus === 'failed')
  }
  const furthestEnabled = [...stageDefinitions].reverse().find((stage) => enabled[stage.key])?.key || 'import'
  const activeStage = enabled[input.requestedStage] ? input.requestedStage : furthestEnabled
  const completed: Record<CaseDesignStage, boolean> = {
    import: input.sourceCount > 0,
    parse: input.analysisStatus === 'succeeded',
    generate: input.generationStatus === 'succeeded' || input.generatedCaseCount > 0,
    review: input.reviewStatus === 'succeeded',
    deliver: false
  }

  const stages = stageDefinitions.map((stage): CaseDesignStageView => {
    let status: WorkflowStageStatus = stage.key === activeStage
      ? 'active'
      : completed[stage.key]
        ? 'done'
        : 'pending'
    if (stage.key === 'parse' && input.analysisStatus === 'failed') status = 'error'
    if (stage.key === 'generate' && input.generationStatus === 'failed') status = 'error'
    if (stage.key === 'review' && input.reviewStatus === 'failed') status = 'error'
    return { ...stage, enabled: enabled[stage.key], status }
  })

  return {
    activeStage,
    stages,
    primaryAction: primaryActionFor(activeStage, input),
    pendingReviewCount: input.pendingReviewCount
  }
}

const primaryActionFor = (
  activeStage: CaseDesignStage,
  input: CaseDesignWorkflowInput
): CaseDesignWorkflowView['primaryAction'] => {
  if (activeStage === 'import') {
    return { label: '解析需求', disabled: input.sourceCount === 0, loading: false }
  }
  if (activeStage === 'parse') {
    if (input.analysisStatus === 'running') return { label: '正在解析', disabled: true, loading: true }
    if (input.analysisStatus === 'failed') return { label: '重试解析', disabled: false, loading: false }
    return { label: '继续生成', disabled: input.analysisStatus !== 'succeeded', loading: false }
  }
  if (activeStage === 'generate') {
    return {
      label: input.generationStatus === 'failed' ? '重试生成' : '生成用例',
      disabled: input.confirmedDraftCount === 0 || input.generationStatus === 'running',
      loading: input.generationStatus === 'running'
    }
  }
  if (activeStage === 'review') {
    return {
      label: input.reviewStatus === 'running'
        ? '正在评审'
        : input.reviewStatus === 'failed'
          ? '重试评审'
          : '完成评审',
      disabled: input.generatedCaseCount === 0 || input.reviewStatus === 'running',
      loading: input.reviewStatus === 'running'
    }
  }
  return { label: '确认交付', disabled: input.generatedCaseCount === 0 || input.saving, loading: input.saving }
}
```

- [ ] **Step 4: Write failing draft-repository tests**

Create `apps/web/src/domain/caseDesignDraftRepository.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import {
  CASE_DESIGN_DRAFT_STORAGE_KEY,
  loadCaseDesignDraft,
  saveCaseDesignDraft,
  type CaseDesignDraftSnapshot
} from './caseDesignDraftRepository'

class MemoryStorage implements Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> {
  values = new Map<string, string>()
  getItem(key: string): string | null { return this.values.get(key) ?? null }
  setItem(key: string, value: string): void { this.values.set(key, value) }
  removeItem(key: string): void { this.values.delete(key) }
}

const snapshot = (): CaseDesignDraftSnapshot => ({
  version: 1,
  activeStage: 'generate',
  generationKind: 'web_automation',
  projectName: '订单服务',
  analysisRunId: 'run-1',
  analysisRun: null,
  requirementReviewRun: null,
  caseReviewRun: {
    runId: 'review-1',
    graphId: 'graph-1',
    status: 'merge_ready',
    nodeCount: 4
  },
  activeReviewStage: 'case',
  reviewAttempted: true,
  selectedCaseId: '',
  sources: [{ name: '退款需求.md', kind: 'document', text: '# 退款需求', needsReattach: false }],
  sourceErrors: {},
  extractedRequirementPoints: [],
  aiDraftPoints: [],
  generatedCases: [],
  generatedWebCases: [],
  analysisError: '',
  generationError: '',
  reviewError: '',
  review: {
    insight: { stage: 'case', summary: '', suggestions: [] },
    qualityScores: [],
    conflicts: [],
    findingDecisions: {}
  },
  updatedAt: '2026-07-16T10:00:00.000Z'
})

describe('caseDesignDraftRepository', () => {
  it('round-trips a versioned draft', () => {
    const storage = new MemoryStorage()
    expect(saveCaseDesignDraft(storage, snapshot()).saved).toBe(true)
    expect(loadCaseDesignDraft(storage)).toEqual(snapshot())
  })

  it('marks oversized source text for reattachment', () => {
    const storage = new MemoryStorage()
    saveCaseDesignDraft(storage, {
      ...snapshot(),
      sources: [{ name: 'large.docx', kind: 'document', text: 'x'.repeat(200_001), needsReattach: false }]
    })
    expect(loadCaseDesignDraft(storage)?.sources[0]).toEqual({
      name: 'large.docx',
      kind: 'document',
      text: '',
      needsReattach: true
    })
  })

  it('reports local-storage quota failures without destroying the last readable draft', () => {
    const storage = new MemoryStorage()
    expect(saveCaseDesignDraft(storage, snapshot()).saved).toBe(true)
    const throwingStorage = {
      getItem: storage.getItem.bind(storage),
      removeItem: storage.removeItem.bind(storage),
      setItem: () => { throw new DOMException('Quota exceeded', 'QuotaExceededError') }
    }

    expect(saveCaseDesignDraft(throwingStorage, snapshot())).toMatchObject({
      saved: false,
      error: 'Quota exceeded'
    })
    expect(loadCaseDesignDraft(storage)).toEqual(snapshot())
  })

  it('normalizes an older v1 draft without finding-level decisions', () => {
    const storage = new MemoryStorage()
    const legacy = snapshot()
    storage.setItem(CASE_DESIGN_DRAFT_STORAGE_KEY, JSON.stringify({
      ...legacy,
      review: {
        insight: legacy.review.insight,
        qualityScores: legacy.review.qualityScores,
        conflicts: legacy.review.conflicts
      }
    }))

    expect(loadCaseDesignDraft(storage)?.review.findingDecisions).toEqual({})
  })

  it('drops malformed finding decisions from a readable v1 draft', () => {
    const storage = new MemoryStorage()
    const persisted = snapshot()
    storage.setItem(CASE_DESIGN_DRAFT_STORAGE_KEY, JSON.stringify({
      ...persisted,
      review: {
        ...persisted.review,
        findingDecisions: {
          'TC-1': {
            'finding-valid': 'accepted',
            'finding-invalid': 'done'
          },
          'TC-2': 'accepted'
        }
      }
    }))

    expect(loadCaseDesignDraft(storage)?.review.findingDecisions).toEqual({
      'TC-1': { 'finding-valid': 'accepted' }
    })
  })

  it('ignores malformed or unsupported drafts', () => {
    const storage = new MemoryStorage()
    storage.setItem(CASE_DESIGN_DRAFT_STORAGE_KEY, '{bad json')
    expect(loadCaseDesignDraft(storage)).toBeUndefined()
    storage.setItem(CASE_DESIGN_DRAFT_STORAGE_KEY, JSON.stringify({ version: 2 }))
    expect(loadCaseDesignDraft(storage)).toBeUndefined()
  })
})
```

- [ ] **Step 5: Run the draft-repository test to verify it fails**

Run:

```bash
npm test -- --run src/domain/caseDesignDraftRepository.test.ts
```

Expected: FAIL because `caseDesignDraftRepository.ts` does not exist.

- [ ] **Step 6: Implement the versioned draft repository**

Create `apps/web/src/domain/caseDesignDraftRepository.ts`:

```ts
import type { AiAnalysisRun, AiDraftTestPoint } from './aiPlatformRepository'
import type { AiMultiAgentRunResponse } from './aiMultiAgentRunRepository'
import type {
  AgentConflictView,
  AiReviewInsightView,
  QualityScoreView
} from './aiMultiAgentRunView'
import type { GeneratedInterfaceCase } from './caseGenerator'
import type { CaseDesignStage } from './caseDesignWorkflow'
import type { RequirementPointDraft } from './webCaseImport'
import type { GeneratedWebAutomationCase } from './webAutomationCase'

export const CASE_DESIGN_DRAFT_STORAGE_KEY = 'ai-test-platform.case-design-draft.v1'
export const MAX_PERSISTED_SOURCE_TEXT_LENGTH = 200_000

export type PersistedRequirementSourceKind = 'document' | 'figma' | 'openapi'
export type CaseReviewDecision = 'pending' | 'accepted' | 'modified' | 'ignored'
export type CaseReviewFindingDecisions = Record<string, Record<string, CaseReviewDecision>>

const caseReviewDecisionValues: CaseReviewDecision[] = [
  'pending',
  'accepted',
  'modified',
  'ignored'
]

const normalizeCaseReviewFindingDecisions = (
  value: unknown
): CaseReviewFindingDecisions => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  const normalized: CaseReviewFindingDecisions = {}
  for (const [caseId, findings] of Object.entries(value)) {
    if (!findings || typeof findings !== 'object' || Array.isArray(findings)) continue
    const decisions: Record<string, CaseReviewDecision> = {}
    for (const [findingId, decision] of Object.entries(findings)) {
      if (caseReviewDecisionValues.includes(decision as CaseReviewDecision)) {
        decisions[findingId] = decision as CaseReviewDecision
      }
    }
    if (Object.keys(decisions).length > 0) normalized[caseId] = decisions
  }
  return normalized
}

export interface PersistedRequirementSource {
  name: string
  kind: PersistedRequirementSourceKind
  text: string
  needsReattach: boolean
}

export const inferRequirementSourceKind = (name: string): PersistedRequirementSourceKind => {
  const normalized = name.toLowerCase()
  if (normalized === 'figma-link.txt') return 'figma'
  if (normalized.includes('openapi') || normalized.endsWith('.yaml') || normalized.endsWith('.yml')) return 'openapi'
  return 'document'
}

export interface CaseDesignDraftSnapshot {
  version: 1
  activeStage: CaseDesignStage
  generationKind: 'interface' | 'web_automation'
  projectName: string
  analysisRunId: string
  analysisRun: AiAnalysisRun | null
  requirementReviewRun: AiMultiAgentRunResponse | null
  caseReviewRun: AiMultiAgentRunResponse | null
  activeReviewStage: 'requirement' | 'case'
  reviewAttempted?: boolean
  selectedCaseId: string
  sources: PersistedRequirementSource[]
  sourceErrors: Record<string, string>
  extractedRequirementPoints: RequirementPointDraft[]
  aiDraftPoints: AiDraftTestPoint[]
  generatedCases: GeneratedInterfaceCase[]
  generatedWebCases: GeneratedWebAutomationCase[]
  analysisError: string
  generationError: string
  reviewError: string
  review: {
    insight: AiReviewInsightView
    qualityScores: QualityScoreView[]
    conflicts: AgentConflictView[]
    findingDecisions: CaseReviewFindingDecisions
  }
  updatedAt: string
}

type DraftStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

export const loadCaseDesignDraft = (
  storage: DraftStorage = localStorage
): CaseDesignDraftSnapshot | undefined => {
  const raw = storage.getItem(CASE_DESIGN_DRAFT_STORAGE_KEY)
  if (!raw) return undefined
  try {
    const parsed = JSON.parse(raw) as Partial<CaseDesignDraftSnapshot>
    if (parsed.version !== 1 || !Array.isArray(parsed.sources)) return undefined
    if (!['import', 'parse', 'generate', 'review', 'deliver'].includes(parsed.activeStage || '')) return undefined
    if (!['interface', 'web_automation'].includes(parsed.generationKind || '')) return undefined
    if (typeof parsed.projectName !== 'string' || typeof parsed.analysisRunId !== 'string') return undefined
    if (!parsed.sources.every((source) =>
      source
      && typeof source.name === 'string'
      && ['document', 'figma', 'openapi'].includes(source.kind)
      && typeof source.text === 'string'
      && typeof source.needsReattach === 'boolean'
    )) return undefined
    if (!Array.isArray(parsed.generatedCases) || !Array.isArray(parsed.generatedWebCases)) return undefined
    if (!parsed.review || !Array.isArray(parsed.review.qualityScores) || !Array.isArray(parsed.review.conflicts)) return undefined
    return {
      ...parsed,
      review: {
        ...parsed.review,
        findingDecisions: normalizeCaseReviewFindingDecisions(
          parsed.review.findingDecisions
        )
      }
    } as CaseDesignDraftSnapshot
  } catch {
    return undefined
  }
}

export const saveCaseDesignDraft = (
  storage: DraftStorage,
  snapshot: CaseDesignDraftSnapshot
): { saved: boolean; needsReattach: string[]; error?: string } => {
  const needsReattach: string[] = []
  const sources = snapshot.sources.map((source) => {
    if (source.text.length <= MAX_PERSISTED_SOURCE_TEXT_LENGTH) return source
    needsReattach.push(source.name)
    return { name: source.name, kind: source.kind, text: '', needsReattach: true }
  })
  try {
    storage.setItem(CASE_DESIGN_DRAFT_STORAGE_KEY, JSON.stringify({ ...snapshot, sources }))
    return { saved: true, needsReattach }
  } catch (error) {
    return {
      saved: false,
      needsReattach,
      error: error instanceof Error ? error.message : '草稿保存失败'
    }
  }
}

export const clearCaseDesignDraft = (storage: DraftStorage = localStorage): void => {
  storage.removeItem(CASE_DESIGN_DRAFT_STORAGE_KEY)
}
```

- [ ] **Step 7: Write failing task-header tests**

Create `apps/web/src/components/workflow/WorkflowHeader.test.ts`:

```ts
// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WorkflowHeader from './WorkflowHeader.vue'

const stages = [
  { key: 'import' as const, label: '需求导入', enabled: true, status: 'done' as const },
  { key: 'parse' as const, label: '内容解析', enabled: true, status: 'done' as const },
  { key: 'generate' as const, label: 'AI 生成', enabled: true, status: 'active' as const },
  { key: 'review' as const, label: '质量评审', enabled: false, status: 'pending' as const },
  { key: 'deliver' as const, label: '保存执行', enabled: false, status: 'pending' as const }
]

describe('WorkflowHeader', () => {
  it('emits navigation only for enabled stages', async () => {
    const wrapper = mount(WorkflowHeader, {
      props: {
        title: '订单退款需求',
        saveLabel: '自动保存 · 刚刚',
        stages,
        primaryAction: { label: '生成用例', disabled: false, loading: false }
      }
    })

    await wrapper.get('button[data-stage="parse"]').trigger('click')
    await wrapper.get('button[data-stage="review"]').trigger('click')
    expect(wrapper.emitted('navigateStage')).toEqual([['parse']])
  })

  it('emits the single primary action', async () => {
    const wrapper = mount(WorkflowHeader, {
      props: {
        title: '订单退款需求',
        saveLabel: '自动保存 · 刚刚',
        stages,
        primaryAction: { label: '生成用例', disabled: false, loading: false }
      }
    })

    await wrapper.get('[data-testid="workflow-primary-action"]').trigger('click')
    expect(wrapper.emitted('primary')).toHaveLength(1)
  })

  it('keeps an optional draft command visually secondary', async () => {
    const wrapper = mount(WorkflowHeader, {
      props: {
        title: '订单退款需求',
        saveLabel: '自动保存 · 刚刚',
        stages,
        primaryAction: { label: '生成用例', disabled: false, loading: false },
        secondaryAction: { label: '新建草稿', disabled: false }
      }
    })
    await wrapper.get('[data-testid="workflow-secondary-action"]').trigger('click')
    expect(wrapper.emitted('secondary')).toHaveLength(1)
  })

  it('exposes a quiet workspace-mode exit without adding another primary action', async () => {
    const wrapper = mount(WorkflowHeader, {
      props: {
        title: '订单退款需求',
        saveLabel: '自动保存 · 刚刚',
        stages,
        primaryAction: { label: '生成用例', disabled: false, loading: false },
        modeAction: { label: '接口用例', disabled: false }
      }
    })
    await wrapper.get('[data-testid="workflow-mode-action"]').trigger('click')
    expect(wrapper.emitted('modeAction')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="workflow-primary-action"]')).toHaveLength(1)
  })
})
```

- [ ] **Step 8: Run the task-header test to verify it fails**

Run:

```bash
npm test -- --run src/components/workflow/WorkflowHeader.test.ts
```

Expected: FAIL because `WorkflowHeader.vue` does not exist.

- [ ] **Step 9: Implement the workflow header**

Create `apps/web/src/components/workflow/WorkflowHeader.vue`:

```vue
<script setup lang="ts">
import type { CaseDesignStage, CaseDesignStageView } from '../../domain/caseDesignWorkflow'

defineProps<{
  title: string
  saveLabel: string
  stages: CaseDesignStageView[]
  primaryAction: { label: string; disabled: boolean; loading: boolean }
  secondaryAction?: { label: string; disabled: boolean }
  modeAction?: { label: string; disabled: boolean }
}>()

const emit = defineEmits<{
  navigateStage: [stage: CaseDesignStage]
  primary: []
  secondary: []
  modeAction: []
}>()

const stageStatusLabel = (status: CaseDesignStageView['status']): string => ({
  pending: '未开始',
  active: '当前阶段',
  done: '已完成',
  error: '发生错误'
}[status])
</script>

<template>
  <header class="studio-workflow-header">
    <div class="studio-workflow-header__identity">
      <strong>{{ title }}</strong>
      <span role="status" aria-live="polite">{{ saveLabel }}</span>
    </div>
    <nav class="studio-workflow-header__steps" aria-label="测试设计流程">
      <button
        v-for="stage in stages"
        :key="stage.key"
        type="button"
        class="studio-workflow-step"
        :class="`is-${stage.status}`"
        :data-stage="stage.key"
        :disabled="!stage.enabled"
        :aria-current="stage.status === 'active' ? 'step' : undefined"
        :aria-label="`${stage.label}，${stageStatusLabel(stage.status)}`"
        @click="emit('navigateStage', stage.key)"
      >
        <span>{{ stage.status === 'done' ? '✓' : stage.label }}</span>
        <strong v-if="stage.status === 'done'">{{ stage.label }}</strong>
        <small>{{ stageStatusLabel(stage.status) }}</small>
      </button>
    </nav>
    <div class="studio-workflow-header__actions">
      <el-button
        v-if="modeAction"
        text
        data-testid="workflow-mode-action"
        :disabled="modeAction.disabled"
        @click="emit('modeAction')"
      >
        {{ modeAction.label }}
      </el-button>
      <el-button
        v-if="secondaryAction"
        plain
        data-testid="workflow-secondary-action"
        :disabled="secondaryAction.disabled"
        @click="emit('secondary')"
      >
        {{ secondaryAction.label }}
      </el-button>
      <el-button
        type="primary"
        data-testid="workflow-primary-action"
        :disabled="primaryAction.disabled"
        :loading="primaryAction.loading"
        @click="emit('primary')"
      >
        {{ primaryAction.label }}
      </el-button>
    </div>
  </header>
</template>
```

- [ ] **Step 10: Integrate workflow state and debounced draft persistence**

In `apps/web/src/App.vue`, import the new helpers and component:

```ts
import WorkflowHeader from './components/workflow/WorkflowHeader.vue'
import {
  buildCaseDesignWorkflowView,
  reviewRunStatusToWorkflowStatus,
  type CaseDesignStage,
  type WorkflowAsyncStatus
} from './domain/caseDesignWorkflow'
import {
  inferRequirementSourceKind,
  loadCaseDesignDraft,
  saveCaseDesignDraft,
  type CaseReviewDecision,
  type CaseReviewFindingDecisions,
  type PersistedRequirementSourceKind
} from './domain/caseDesignDraftRepository'
```

Extend the existing staged-source interface without breaking current import call sites:

```ts
interface StagedRequirementFile {
  name: string
  text: string
  kind?: PersistedRequirementSourceKind
  needsReattach?: boolean
}
```

Add the restored-draft refs through `caseDesignDraftTimer` after the current Web requirement refs. Place `caseDesignWorkflow`, `caseDesignSaveLabel`, `activeAiReviewInsight`, `persistCaseDesignDraft`, and the autosave `watch` immediately after the existing `aiReviewInsight` computed (currently near the other AI review computed values). This ordering is required because the autosave watch evaluates `activeAiReviewInsight` immediately and must not run before `aiReviewInsight` is declared:

```ts
const restoredCaseDesignDraft = loadCaseDesignDraft(localStorage)
const activeCaseDesignStage = ref<CaseDesignStage>(restoredCaseDesignDraft?.activeStage || 'import')
const caseDesignLastSavedAt = ref(restoredCaseDesignDraft?.updatedAt || '')
const caseDesignDraftSaveError = ref('')
const requirementSourceErrors = ref<Record<string, string>>(
  restoredCaseDesignDraft?.sourceErrors || {}
)
const aiAnalysisError = ref(restoredCaseDesignDraft?.analysisError || '')
const aiGenerationError = ref(restoredCaseDesignDraft?.generationError || '')
const caseReviewError = ref(
  restoredCaseDesignDraft?.reviewError
    || (restoredCaseDesignDraft?.caseReviewRun?.status === 'failed'
      ? restoredCaseDesignDraft.caseReviewRun.errorSummary || 'AI 质量评审未完成'
      : '')
)
const caseReviewAttempted = ref(
  restoredCaseDesignDraft?.reviewAttempted
    ?? Boolean(restoredCaseDesignDraft?.caseReviewRun || restoredCaseDesignDraft?.reviewError)
)
const restoredAiReviewInsight = ref(restoredCaseDesignDraft?.review.insight)
const caseReviewFindingDecisions = ref<CaseReviewFindingDecisions>(
  restoredCaseDesignDraft?.review.findingDecisions || {}
)
const caseDesignDraftPersistenceSuspended = ref(false)
let caseDesignDraftTimer: ReturnType<typeof setTimeout> | undefined

const caseReviewStatus = computed<WorkflowAsyncStatus>(() =>
  aiMultiAgentLoading.value && activeAiReviewStage.value === 'case'
    ? 'running'
    : caseReviewError.value
      ? 'failed'
      : reviewRunStatusToWorkflowStatus(caseAiReviewRun.value?.status)
)

const caseDesignWorkflow = computed(() => buildCaseDesignWorkflowView({
  requestedStage: activeCaseDesignStage.value,
  sourceCount: stagedRequirementFiles.value.length,
  analysisStatus: aiAnalysisRunning.value
    ? 'running'
    : aiAnalysisError.value
      ? 'failed'
      : aiAnalysisCompleted.value
        ? 'succeeded'
        : 'idle',
  confirmedDraftCount: aiDraftPoints.value.filter((point) =>
    point.reviewStatus === 'confirmed' || point.reviewStatus === 'modified'
  ).length,
  generationStatus: aiCaseGenerationRunning.value
    ? 'running'
    : aiGenerationError.value
      ? 'failed'
      : aiCaseGenerationCompleted.value
        ? 'succeeded'
        : 'idle',
  generatedCaseCount: generatedWebCases.value.length,
  reviewAttempted: caseReviewAttempted.value,
  reviewStatus: caseReviewStatus.value,
  pendingReviewCount: aiMultiAgentUnresolvedConflicts.value,
  saving: saveCasesLoading.value
}))

const caseDesignSaveLabel = computed(() =>
  caseDesignDraftSaveError.value
    ? `自动保存失败 · ${caseDesignDraftSaveError.value}`
    : caseDesignLastSavedAt.value
    ? `自动保存 · ${new Date(caseDesignLastSavedAt.value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`
    : '等待自动保存'
)

const activeAiReviewInsight = computed(() =>
  (aiReviewInsight.value.summary || aiReviewInsight.value.suggestions.length > 0)
    ? aiReviewInsight.value
    : restoredAiReviewInsight.value || { stage: '', summary: '', suggestions: [] }
)

const persistCaseDesignDraft = () => {
  if (caseDesignDraftPersistenceSuspended.value) return
  const updatedAt = new Date().toISOString()
  const result = saveCaseDesignDraft(localStorage, {
    version: 1,
    activeStage: activeCaseDesignStage.value,
    generationKind: generationKind.value,
    projectName: requirementAnalysisProjectName.value,
    analysisRunId: aiAnalysisRun.value?.runId || '',
    analysisRun: aiAnalysisRun.value || null,
    requirementReviewRun: requirementAiReviewRun.value || null,
    caseReviewRun: caseAiReviewRun.value || null,
    activeReviewStage: activeAiReviewStage.value,
    reviewAttempted: caseReviewAttempted.value,
    selectedCaseId: selectedCase.value?.id || '',
    sources: stagedRequirementFiles.value.map((source) => ({
      name: source.name,
      kind: source.kind || inferRequirementSourceKind(source.name),
      text: source.text,
      needsReattach: Boolean(source.needsReattach)
    })),
    sourceErrors: requirementSourceErrors.value,
    extractedRequirementPoints: extractedRequirementPoints.value,
    aiDraftPoints: aiDraftPoints.value,
    generatedCases: generatedCases.value,
    generatedWebCases: generatedWebCases.value,
    analysisError: aiAnalysisError.value,
    generationError: aiGenerationError.value,
    reviewError: caseReviewError.value,
    review: {
      insight: activeAiReviewInsight.value,
      qualityScores: aiMultiAgentQualityScores.value,
      conflicts: aiMultiAgentConflicts.value,
      findingDecisions: caseReviewFindingDecisions.value
    },
    updatedAt
  })
  caseDesignDraftSaveError.value = result.error || ''
  if (result.saved) caseDesignLastSavedAt.value = updatedAt
}

watch([
  activeCaseDesignStage,
  generationKind,
  requirementAnalysisProjectName,
  stagedRequirementFiles,
  requirementSourceErrors,
  extractedRequirementPoints,
  aiDraftPoints,
  generatedCases,
  generatedWebCases,
  aiAnalysisRun,
  requirementAiReviewRun,
  caseAiReviewRun,
  activeAiReviewStage,
  selectedCase,
  aiMultiAgentQualityScores,
  aiMultiAgentConflicts,
  activeAiReviewInsight,
  aiAnalysisError,
  aiGenerationError,
  caseReviewError,
  caseReviewAttempted,
  caseReviewFindingDecisions
], () => {
  if (caseDesignDraftPersistenceSuspended.value) return
  if (caseDesignDraftTimer) clearTimeout(caseDesignDraftTimer)
  caseDesignDraftTimer = setTimeout(persistCaseDesignDraft, 400)
}, { deep: true })

onBeforeUnmount(() => {
  if (caseDesignDraftTimer) clearTimeout(caseDesignDraftTimer)
})
```

During startup, restore the snapshot before backend workspace loading:

```ts
if (restoredCaseDesignDraft) {
  generationKind.value = restoredCaseDesignDraft.generationKind
  requirementAnalysisProjectName.value = restoredCaseDesignDraft.projectName
  stagedRequirementFiles.value = restoredCaseDesignDraft.sources.map(({ name, kind, text, needsReattach }) => ({
    name,
    kind,
    text,
    needsReattach
  }))
  extractedRequirementPoints.value = restoredCaseDesignDraft.extractedRequirementPoints
  aiDraftPoints.value = restoredCaseDesignDraft.aiDraftPoints
  generatedCases.value = restoredCaseDesignDraft.generatedCases
  generatedWebCases.value = restoredCaseDesignDraft.generatedWebCases
  aiAnalysisRun.value = restoredCaseDesignDraft.analysisRun || undefined
  aiAnalysisRunning.value = restoredCaseDesignDraft.analysisRun?.status === 'running'
  if (restoredCaseDesignDraft.analysisRun) {
    const run = restoredCaseDesignDraft.analysisRun
    aiAnalysisProgress.percentage = run.totalChunks > 0
      ? Math.round((run.completedChunks / run.totalChunks) * 100)
      : run.status === 'succeeded' ? 100 : 0
    aiAnalysisProgress.text = run.status === 'running'
      ? `正在恢复内容解析 · ${run.completedChunks}/${run.totalChunks} 分片`
      : run.status === 'succeeded'
        ? '内容解析已完成'
        : run.errorSummary || '内容解析未完成'
  }
  requirementAiReviewRun.value = restoredCaseDesignDraft.requirementReviewRun || undefined
  caseAiReviewRun.value = restoredCaseDesignDraft.caseReviewRun || undefined
  activeAiReviewStage.value = restoredCaseDesignDraft.activeReviewStage
  aiMultiAgentQualityScores.value = restoredCaseDesignDraft.review.qualityScores
  aiMultiAgentConflicts.value = restoredCaseDesignDraft.review.conflicts
  selectedCase.value = restoredCaseDesignDraft.generatedWebCases.find(
    (testCase) => testCase.id === restoredCaseDesignDraft.selectedCaseId
  ) || restoredCaseDesignDraft.generatedWebCases[0]
}
```

In the existing `loadMultiAgentReviewDetails`, preserve locally restored arrays when a refresh request is rejected. Replace each fallback-to-empty assignment with fulfilled-only assignment:

```ts
if (graph.status === 'fulfilled') {
  aiMultiAgentGraphNodes.value = graph.value.nodes
  if (requirementAiReviewRun.value?.runId === runId) {
    requirementAiReviewRun.value = {
      ...requirementAiReviewRun.value,
      status: graph.value.status,
      inputSnapshot: graph.value.inputSnapshot,
      outputSnapshot: graph.value.outputSnapshot,
      errorSummary: graph.value.errorSummary
    }
  }
  if (caseAiReviewRun.value?.runId === runId) {
    caseAiReviewRun.value = {
      ...caseAiReviewRun.value,
      status: graph.value.status,
      inputSnapshot: graph.value.inputSnapshot,
      outputSnapshot: graph.value.outputSnapshot,
      errorSummary: graph.value.errorSummary
    }
  }
}
if (handoffs.status === 'fulfilled') aiMultiAgentHandoffs.value = handoffs.value.items
if (conflicts.status === 'fulfilled') aiMultiAgentConflicts.value = conflicts.value.items
if (scores.status === 'fulfilled') aiMultiAgentQualityScores.value = scores.value.items
if (suggestions.status === 'fulfilled') aiMultiAgentAutomationSuggestions.value = suggestions.value.items
```

Retain the complete run-snapshot update code already inside the fulfilled `graph` branch; remove only the `else { aiMultiAgentGraphNodes.value = [] }` branch and the four `condition ? items : []` assignments. This makes offline restoration local-first instead of replacing valid cached review data with empty arrays.

After all setup functions are declared, refresh the persisted analysis run and the active review run during the existing `onMounted` callback. Use `Promise.allSettled` so an unavailable backend does not discard the locally restored draft:

```ts
const restoredReviewRun = restoredCaseDesignDraft?.activeReviewStage === 'case'
  ? restoredCaseDesignDraft.caseReviewRun
  : restoredCaseDesignDraft?.requirementReviewRun
void Promise.allSettled([
  restoredCaseDesignDraft?.analysisRunId
    ? refreshAiAnalysisRunData(restoredCaseDesignDraft.analysisRunId)
    : Promise.resolve(),
  restoredReviewRun?.runId
    ? loadMultiAgentReviewDetails(restoredReviewRun.runId)
    : Promise.resolve()
]).then((results) => {
  if (results[0].status === 'rejected') {
    aiAnalysisError.value = results[0].reason instanceof Error
      ? results[0].reason.message
      : '后端暂不可用，已恢复本地 AI 分析草稿'
  }
  if (results[1].status === 'rejected') {
    caseReviewError.value = results[1].reason instanceof Error
      ? results[1].reason.message
      : '后端暂不可用，已恢复本地评审结果'
  }
})
```

Inside the unique API-workspace `<section class="main-grid" ...>`, insert the header immediately before its first child, the existing `<div class="operation-panel" ...>`. This temporary exact anchor keeps Task 5 buildable; Task 10 moves the same single component into the final adjacent Studio Canvas branch:

```vue
<WorkflowHeader
  v-if="generationKind === 'web_automation'"
  :title="requirementAnalysisProjectName || '新建测试任务'"
  :save-label="caseDesignSaveLabel"
  :stages="caseDesignWorkflow.stages"
  :primary-action="caseDesignWorkflow.primaryAction"
  @navigate-stage="activeCaseDesignStage = $event"
  @primary="handleCaseDesignPrimaryAction"
/>
```

Add an explicit dispatcher that calls only existing actions:

```ts
const handleCaseDesignPrimaryAction = async () => {
  const stage = caseDesignWorkflow.value.activeStage
  if (stage === 'import') {
    activeCaseDesignStage.value = 'parse'
    await analyzeRequirementPointsWithAi()
    if (aiAnalysisCompleted.value) activeCaseDesignStage.value = 'generate'
    return
  }
  if (stage === 'parse') {
    if (aiAnalysisError.value) await retryCurrentAiAnalysisRun()
    if (aiAnalysisCompleted.value) activeCaseDesignStage.value = 'generate'
    return
  }
  if (stage === 'generate') {
    await generateWebCasesWithAi()
    if (generatedWebCases.value.length > 0) activeCaseDesignStage.value = 'review'
    return
  }
  if (stage === 'review') {
    if (caseReviewStatus.value === 'failed') {
      await startCaseAiReview()
      return
    }
    if (!caseAiReviewRun.value) {
      await startCaseAiReview()
      return
    }
    activeCaseDesignStage.value = 'deliver'
    return
  }
  await deliverGeneratedCases()
}

const deliverGeneratedCases = async () => {
  await saveCases()
}
```

Task 9 replaces this save-only implementation with the approved save, automation, and optional execution delivery flow. Tasks 6 through 8 update each existing action to set and clear the error refs used here.

- [ ] **Step 11: Run workflow, draft, header, and build checks**

Run:

```bash
npm test -- --run src/domain/caseDesignWorkflow.test.ts src/domain/caseDesignDraftRepository.test.ts src/components/workflow/WorkflowHeader.test.ts
npm run build
```

Expected: all named tests PASS and TypeScript confirms the integration types.

- [ ] **Step 12: Commit workflow state and draft recovery**

```bash
git add apps/web/src/domain/caseDesignWorkflow.ts apps/web/src/domain/caseDesignWorkflow.test.ts apps/web/src/domain/caseDesignDraftRepository.ts apps/web/src/domain/caseDesignDraftRepository.test.ts apps/web/src/components/workflow/WorkflowHeader.vue apps/web/src/components/workflow/WorkflowHeader.test.ts apps/web/src/App.vue
git commit -m "feat: add recoverable case design workflow"
```

## Task 6: Replace The Old Requirement Card With Source-Local Import And Parse UI

**Files:**
- Create: `apps/web/src/domain/caseDesignSourceView.test.ts`
- Create: `apps/web/src/domain/caseDesignSourceView.ts`
- Create: `apps/web/src/components/workflow/RequirementImportStage.test.ts`
- Create: `apps/web/src/components/workflow/RequirementImportStage.vue`
- Modify: `apps/web/src/App.vue:478-550`
- Modify: `apps/web/src/App.vue:1335-1680`
- Modify: `apps/web/src/App.vue:5579-5705`

- [ ] **Step 1: Write failing source-view tests**

Create `apps/web/src/domain/caseDesignSourceView.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { buildCaseDesignSourceView } from './caseDesignSourceView'

describe('caseDesignSourceView', () => {
  it('maps document, Figma, and OpenAPI sources', () => {
    expect(buildCaseDesignSourceView([
      { name: '退款需求.docx', kind: 'document', text: '需求', needsReattach: false },
      { name: 'figma-link.txt', kind: 'figma', text: 'https://figma.com/design/key', needsReattach: false },
      { name: 'schema.json', kind: 'openapi', text: '{}', needsReattach: false }
    ], {})).toEqual([
      { name: '退款需求.docx', kind: 'document', kindLabel: '文档', status: 'ready', detail: '已就绪' },
      { name: 'figma-link.txt', kind: 'figma', kindLabel: 'Figma', status: 'ready', detail: '已连接' },
      { name: 'schema.json', kind: 'openapi', kindLabel: 'OpenAPI', status: 'ready', detail: '已就绪' }
    ])
  })

  it('keeps errors and reattach requirements local to the affected source', () => {
    expect(buildCaseDesignSourceView([
      { name: 'large.docx', kind: 'document', text: '', needsReattach: true },
      { name: 'broken.pdf', kind: 'document', text: '', needsReattach: false }
    ], { 'broken.pdf': '文档解析失败' })).toEqual([
      { name: 'large.docx', kind: 'document', kindLabel: '文档', status: 'reattach', detail: '需要重新选择原文件' },
      { name: 'broken.pdf', kind: 'document', kindLabel: '文档', status: 'error', detail: '文档解析失败' }
    ])
  })
})
```

- [ ] **Step 2: Run the source-view test to verify it fails**

Run:

```bash
npm test -- --run src/domain/caseDesignSourceView.test.ts
```

Expected: FAIL because `caseDesignSourceView.ts` does not exist.

- [ ] **Step 3: Implement the source view**

Create `apps/web/src/domain/caseDesignSourceView.ts`:

```ts
import type {
  PersistedRequirementSource,
  PersistedRequirementSourceKind
} from './caseDesignDraftRepository'

export type CaseDesignSourceKind = PersistedRequirementSourceKind
export type CaseDesignSourceStatus = 'ready' | 'error' | 'reattach'

export interface CaseDesignSourceView {
  name: string
  kind: CaseDesignSourceKind
  kindLabel: string
  status: CaseDesignSourceStatus
  detail: string
}

export const buildCaseDesignSourceView = (
  sources: PersistedRequirementSource[],
  errors: Record<string, string>
): CaseDesignSourceView[] => sources.map((source) => {
  const kind = source.kind
  if (errors[source.name]) {
    return { name: source.name, kind, kindLabel: kindLabel(kind), status: 'error', detail: errors[source.name] }
  }
  if (source.needsReattach) {
    return { name: source.name, kind, kindLabel: kindLabel(kind), status: 'reattach', detail: '需要重新选择原文件' }
  }
  return {
    name: source.name,
    kind,
    kindLabel: kindLabel(kind),
    status: 'ready',
    detail: kind === 'figma' ? '已连接' : '已就绪'
  }
})

const kindLabel = (kind: CaseDesignSourceKind): string => ({
  document: '文档',
  figma: 'Figma',
  openapi: 'OpenAPI'
}[kind])
```

- [ ] **Step 4: Write failing import-stage component tests**

Create `apps/web/src/components/workflow/RequirementImportStage.test.ts`:

```ts
// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import RequirementImportStage from './RequirementImportStage.vue'

describe('RequirementImportStage', () => {
  it('renders combined source commands and source-local status', () => {
    const wrapper = mount(RequirementImportStage, {
      props: {
        sources: [
          { name: '退款需求.docx', kind: 'document', kindLabel: '文档', status: 'ready', detail: '已就绪' },
          { name: 'broken.pdf', kind: 'document', kindLabel: '文档', status: 'error', detail: '文档解析失败' }
        ],
        analysisStatus: 'idle',
        progressPercentage: 0,
        progressText: '等待解析',
        errorMessage: ''
      }
    })

    expect(wrapper.text()).toContain('上传需求文档')
    expect(wrapper.text()).toContain('连接 Figma')
    expect(wrapper.text()).toContain('导入 OpenAPI')
    expect(wrapper.text()).toContain('broken.pdf')
    expect(wrapper.text()).toContain('文档解析失败')
  })

  it('emits local retry and remove commands', async () => {
    const wrapper = mount(RequirementImportStage, {
      props: {
        sources: [{ name: 'broken.pdf', kind: 'document', kindLabel: '文档', status: 'error', detail: '文档解析失败' }],
        analysisStatus: 'failed',
        progressPercentage: 0,
        progressText: '解析失败',
        errorMessage: '请求超时'
      }
    })

    await wrapper.get('[data-testid="retry-source-broken.pdf"]').trigger('click')
    await wrapper.get('[data-testid="replace-source-broken.pdf"]').trigger('click')
    await wrapper.get('[data-testid="remove-source-broken.pdf"]').trigger('click')
    expect(wrapper.emitted('retrySource')).toEqual([['broken.pdf']])
    expect(wrapper.emitted('replaceSource')).toEqual([['broken.pdf']])
    expect(wrapper.emitted('removeSource')).toEqual([['broken.pdf']])
  })

  it('shows extracted sections, Figma nodes, and API operations as parsing progresses', () => {
    const wrapper = mount(RequirementImportStage, {
      props: {
        sources: [{ name: '退款需求.md', kind: 'document', kindLabel: '文档', status: 'ready', detail: '已就绪' }],
        analysisStatus: 'running',
        progressPercentage: 45,
        progressText: '正在解析',
        errorMessage: '',
        completedSourceCount: 1,
        totalSourceCount: 2,
        parsedItems: [
          { id: 'section-1', kindLabel: '章节', label: '退款状态回补' },
          { id: 'figma-1', kindLabel: 'Figma', label: '退款详情页' },
          { id: 'api-1', kindLabel: 'API', label: 'POST /refunds' }
        ]
      }
    })

    expect(wrapper.text()).toContain('退款状态回补')
    expect(wrapper.text()).toContain('退款详情页')
    expect(wrapper.text()).toContain('POST /refunds')
    expect(wrapper.text()).toContain('1 / 2')
  })
})
```

- [ ] **Step 5: Run the import-stage test to verify it fails**

Run:

```bash
npm test -- --run src/components/workflow/RequirementImportStage.test.ts
```

Expected: FAIL because `RequirementImportStage.vue` does not exist.

- [ ] **Step 6: Implement the import and parse stage**

Create `apps/web/src/components/workflow/RequirementImportStage.vue`:

```vue
<script setup lang="ts">
import { FileJson, FileText, Figma, RefreshCcw, Trash2, Upload } from 'lucide-vue-next'
import type { CaseDesignSourceView } from '../../domain/caseDesignSourceView'
import type { WorkflowAsyncStatus } from '../../domain/caseDesignWorkflow'

withDefaults(defineProps<{
  sources: CaseDesignSourceView[]
  analysisStatus: WorkflowAsyncStatus
  progressPercentage: number
  progressText: string
  errorMessage: string
  parsedItems?: Array<{ id: string; kindLabel: string; label: string }>
  completedSourceCount?: number
  totalSourceCount?: number
}>(), {
  parsedItems: () => [],
  completedSourceCount: 0,
  totalSourceCount: 0
})

const emit = defineEmits<{
  pickFiles: []
  importFigma: []
  pickOpenApi: []
  retrySource: [name: string]
  replaceSource: [name: string]
  removeSource: [name: string]
}>()
</script>

<template>
  <section class="studio-import-stage" data-testid="requirement-import-stage">
    <aside class="studio-import-stage__sources">
      <header>
        <strong>本次任务来源</strong>
        <span>{{ sources.length }}</span>
      </header>
      <div v-if="sources.length" class="studio-source-list">
        <article v-for="source in sources" :key="source.name" class="studio-source-item" :class="`is-${source.status}`">
          <span class="studio-source-item__kind">{{ source.kindLabel }}</span>
          <div>
            <strong>{{ source.name }}</strong>
            <span>{{ source.detail }}</span>
          </div>
          <div class="studio-source-item__actions">
            <button
              v-if="source.status !== 'ready'"
              type="button"
              :data-testid="`retry-source-${source.name}`"
              :aria-label="`重试 ${source.name}`"
              @click="emit('retrySource', source.name)"
            >
              <RefreshCcw :size="14" />
            </button>
            <button
              v-if="source.status !== 'ready'"
              type="button"
              :data-testid="`replace-source-${source.name}`"
              :aria-label="`替换 ${source.name}`"
              @click="emit('replaceSource', source.name)"
            >
              替换
            </button>
            <button
              type="button"
              :data-testid="`remove-source-${source.name}`"
              :aria-label="`移除 ${source.name}`"
              @click="emit('removeSource', source.name)"
            >
              <Trash2 :size="14" />
            </button>
          </div>
        </article>
      </div>
      <div v-else class="studio-import-stage__source-empty">还没有添加需求来源</div>
    </aside>

    <div class="studio-import-stage__canvas">
      <header>
        <div>
          <strong>添加需求来源</strong>
          <span>多个来源可以共同参与本次分析</span>
        </div>
        <span>{{ sources.filter((source) => source.status === 'ready').length }} 个来源已就绪</span>
      </header>

      <div class="studio-source-actions">
        <button type="button" @click="emit('pickFiles')">
          <Upload :size="18" />
          <strong>上传需求文档</strong>
          <span>文档、表格、图片与原型截图</span>
        </button>
        <button type="button" @click="emit('importFigma')">
          <Figma :size="18" />
          <strong>连接 Figma</strong>
          <span>读取设计节点、文案与交互线索</span>
        </button>
        <button type="button" @click="emit('pickOpenApi')">
          <FileJson :size="18" />
          <strong>导入 OpenAPI</strong>
          <span>补充接口、参数与响应结构</span>
        </button>
      </div>

      <div v-if="analysisStatus !== 'idle' || errorMessage" class="studio-analysis-status" role="status">
        <FileText :size="18" />
        <div>
          <strong>{{ analysisStatus === 'failed' ? '内容解析未完成' : progressText }}</strong>
          <span v-if="totalSourceCount > 0">已完成 {{ completedSourceCount }} / {{ totalSourceCount }} 个来源</span>
          <span v-if="errorMessage">{{ errorMessage }}</span>
          <el-progress
            v-else
            :percentage="progressPercentage"
            :status="analysisStatus === 'succeeded' ? 'success' : undefined"
          />
        </div>
      </div>

      <section v-if="parsedItems.length" class="studio-parsed-items" aria-label="已解析内容">
        <header><strong>已解析内容</strong><span>{{ parsedItems.length }}</span></header>
        <ul>
          <li v-for="item in parsedItems" :key="item.id">
            <span>{{ item.kindLabel }}</span>
            <strong>{{ item.label }}</strong>
          </li>
        </ul>
      </section>
    </div>
  </section>
</template>
```

- [ ] **Step 7: Refactor multi-source import to preserve successful and failed source rows**

In `apps/web/src/App.vue`, add:

```ts
import RequirementImportStage from './components/workflow/RequirementImportStage.vue'
import { buildCaseDesignSourceView } from './domain/caseDesignSourceView'

const caseDesignSourceView = computed(() => buildCaseDesignSourceView(
  stagedRequirementFiles.value.map((source) => ({
    name: source.name,
    kind: source.kind || inferRequirementSourceKind(source.name),
    text: source.text,
    needsReattach: Boolean(source.needsReattach)
  })),
  requirementSourceErrors.value
))

const caseDesignParsedItems = computed(() => [
  ...aiDraftPoints.value.map((point) => ({
    id: `requirement-${point.pointId}`,
    kindLabel: '需求点',
    label: point.title
  })),
  ...aiFigmaNodes.value.items.map((node) => ({
    id: `figma-${node.figmaNodeId}`,
    kindLabel: 'Figma',
    label: node.nodeName
  })),
  ...(parsed.value?.operations || []).map((operation) => ({
    id: `api-${operation.id}`,
    kindLabel: 'API',
    label: `${operation.method} ${operation.path}`
  }))
])

const readyRequirementSourceCount = computed(() =>
  caseDesignSourceView.value.filter((source) => source.status === 'ready').length
)

const pendingRequirementReplacement = ref('')
const analysisSourceProgress = reactive({
  completed: restoredCaseDesignDraft?.analysisRun?.status === 'succeeded'
    ? readyRequirementSourceCount.value
    : 0,
  total: readyRequirementSourceCount.value
})
```

In `caseDesignWorkflow`, replace `sourceCount: stagedRequirementFiles.value.length` with `sourceCount: readyRequirementSourceCount.value`. Replace the existing `canAnalyzeRequirements` computed with:

```ts
const canAnalyzeRequirements = computed(() => readyRequirementSourceCount.value > 0)
```

This is the preflight gate: unreadable, malformed, authorization-failed, and reattach-required rows stay visible but do not enable `解析需求`.

Replace `importWebCaseFiles` with source-local settlement:

```ts
const importWebCaseFiles = async (files: File[]) => {
  const settled = await Promise.allSettled(files.map(async (file) => ({
    file,
    payload: { name: file.name, text: await readRequirementFileText(file) }
  })))
  const payloads: StagedRequirementFile[] = []
  const successfulFiles: File[] = []
  const nextErrors = { ...requirementSourceErrors.value }

  settled.forEach((result, index) => {
    const file = files[index]
    if (result.status === 'fulfilled') {
      payloads.push({ ...result.value.payload, kind: 'document', needsReattach: false })
      successfulFiles.push(result.value.file)
      delete nextErrors[file.name]
    } else {
      nextErrors[file.name] = result.reason instanceof Error ? result.reason.message : '文件读取失败'
      payloads.push({ name: file.name, kind: 'document', text: '', needsReattach: false })
    }
  })

  const replacementTarget = pendingRequirementReplacement.value
  pendingRequirementReplacement.value = ''
  if (replacementTarget && successfulFiles.length > 0) {
    stagedRequirementFiles.value = stagedRequirementFiles.value.filter((source) => source.name !== replacementTarget)
    stagedRequirementSourceFiles.value = stagedRequirementSourceFiles.value.filter((source) => source.name !== replacementTarget)
    delete nextErrors[replacementTarget]
  }
  requirementSourceErrors.value = nextErrors
  if (payloads.length > 0) {
    generationKind.value = 'web_automation'
    upsertStagedRequirementSources(payloads, successfulFiles)
    requirementAnalysisProjectName.value = '默认项目'
    if (successfulFiles.length > 0) ElMessage.success(`已添加 ${successfulFiles.length} 个需求来源`)
  }
  if (successfulFiles.length === 0) ElMessage.error('所选需求文件均未能读取，失败项已保留以便替换')
}

const removeRequirementSource = (name: string) => {
  stagedRequirementFiles.value = stagedRequirementFiles.value.filter((source) => source.name !== name)
  stagedRequirementSourceFiles.value = stagedRequirementSourceFiles.value.filter((source) => source.name !== name)
  const nextErrors = { ...requirementSourceErrors.value }
  delete nextErrors[name]
  requirementSourceErrors.value = nextErrors
  resetAiRequirementAnalysis()
}

const retryRequirementSource = async (name: string) => {
  const original = stagedRequirementSourceFiles.value.find((file) => file.name === name)
  if (original) {
    await importWebCaseFiles([original])
    return
  }
  pendingRequirementReplacement.value = name
  await pickWebCaseFiles()
}

const replaceRequirementSource = async (name: string) => {
  pendingRequirementReplacement.value = name
  await pickWebCaseFiles()
}
```

Keep the existing interface-import behavior while adding a Web-workflow OpenAPI target. Replace `onFileSelected` and `pickOpenApiFiles` with these complete functions and add the helper/state beside them:

```ts
const openApiImportTarget = ref<'interface' | 'requirement'>('interface')

const importOpenApiRequirementFiles = async (files: File[]) => {
  const settled = await Promise.allSettled(files.map(async (file) => {
    const text = await file.text()
    return { file, text, json: JSON.parse(text) as unknown }
  }))
  const parsedPayloads: Array<{ name: string; json: unknown }> = []
  const sources: StagedRequirementFile[] = []
  const successfulFiles: File[] = []
  const nextErrors = { ...requirementSourceErrors.value }
  settled.forEach((result, index) => {
    const file = files[index]
    if (result.status === 'fulfilled') {
      parsedPayloads.push({ name: file.name, json: result.value.json })
      sources.push({ name: file.name, kind: 'openapi', text: result.value.text, needsReattach: false })
      successfulFiles.push(file)
      delete nextErrors[file.name]
    } else {
      sources.push({ name: file.name, kind: 'openapi', text: '', needsReattach: false })
      nextErrors[file.name] = result.reason instanceof Error ? result.reason.message : 'OpenAPI 读取失败'
    }
  })
  if (parsedPayloads.length > 0) importOpenApiPayloads(parsedPayloads)
  requirementSourceErrors.value = nextErrors
  generationKind.value = 'web_automation'
  upsertStagedRequirementSources(sources, successfulFiles)
}

const processOpenApiFiles = async (
  files: File[],
  target: 'interface' | 'requirement'
) => {
  if (target === 'requirement') {
    await importOpenApiRequirementFiles(files)
    return
  }
  await importOpenApiFiles(files)
}

const onFileSelected = async (event: Event) => {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  const target = openApiImportTarget.value
  openApiImportTarget.value = 'interface'
  if (files.length === 0) return
  try {
    await processOpenApiFiles(files, target)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导入失败')
  } finally {
    input.value = ''
  }
}

const pickOpenApiFiles = async (target: 'interface' | 'requirement' = 'interface') => {
  const chooserWindow = window as Window & {
    showOpenFilePicker?: (options?: unknown) => Promise<Array<{ getFile: () => Promise<File> }>>
  }

  if (typeof chooserWindow.showOpenFilePicker === 'function') {
    try {
      const handles = await chooserWindow.showOpenFilePicker({
        multiple: true,
        types: [{
          description: 'OpenAPI JSON',
          accept: { 'application/json': ['.json'] }
        }]
      })
      const files = await Promise.all(handles.map((handle) => handle.getFile()))
      if (files.length > 0) await processOpenApiFiles(files, target)
      return
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return
    }
  }

  openApiImportTarget.value = target
  openApiFileInput.value?.click()
}
```

In `promptFigmaDesignImport`, make the sentinel source kind explicit:

```ts
upsertStagedRequirementSources(
  [{ name: FIGMA_REQUIREMENT_FILE_NAME, kind: 'figma', text: requirementText, needsReattach: false }],
  [new File([requirementText], FIGMA_REQUIREMENT_FILE_NAME, { type: 'text/plain' })]
)
```

In `importStructuredAiFigma`, clear the source-local error on success and bind authorization/import failures to the Figma row:

```ts
delete requirementSourceErrors.value[FIGMA_REQUIREMENT_FILE_NAME]
requirementSourceErrors.value = { ...requirementSourceErrors.value }
```

Place those two lines after `reloadAiFigmaWorkspace(imported.figmaFileId)`. Replace the current catch body with:

```ts
} catch (error) {
  aiFigmaFile.value = undefined
  aiFigmaNodes.value = { items: [] }
  selectedAiFigmaNodeId.value = ''
  requirementSourceErrors.value = {
    ...requirementSourceErrors.value,
    [FIGMA_REQUIREMENT_FILE_NAME]: error instanceof Error
      ? error.message
      : 'Figma 授权或导入失败'
  }
} finally {
  aiFigmaLoading.value = false
}
```

Replace `requirementSourceFilesForAiUpload` and `analyzeRequirementPointsWithAi` so uploads settle per source and successful sources continue into the analysis run:

```ts
const requirementSourcesForAiUpload = (): Array<{
  source: StagedRequirementFile
  file: File
}> => {
  const originals = new Map(stagedRequirementSourceFiles.value.map((file) => [file.name, file]))
  return stagedRequirementFiles.value
    .filter((source) => !source.needsReattach && !requirementSourceErrors.value[source.name])
    .map((source) => ({
      source,
      file: originals.get(source.name)
        || new File([source.text], source.name, { type: 'text/plain' })
    }))
}

const analyzeRequirementPointsWithAi = async () => {
  const uploadCandidates = requirementSourcesForAiUpload()
  analysisSourceProgress.completed = 0
  analysisSourceProgress.total = uploadCandidates.length
  if (uploadCandidates.length === 0) {
    ElMessage.warning('请先添加至少一个可读取的需求来源')
    return
  }

  aiAnalysisRunning.value = true
  aiAnalysisError.value = ''
  setAiAnalysisProgress(8, '准备上传需求来源')
  try {
    const project = await ensureBackendProject(requirementAnalysisProjectName.value || '默认项目')
    setAiAnalysisProgress(22, `正在上传 ${uploadCandidates.length} 个需求来源`)
    const settled = await Promise.allSettled(uploadCandidates.map(async ({ source, file }) => {
      try {
        return { source, document: await uploadAiSourceDocument(project.id, file) }
      } finally {
        analysisSourceProgress.completed += 1
        setAiAnalysisProgress(
          Math.round((analysisSourceProgress.completed / analysisSourceProgress.total) * 40) + 10,
          `已完成 ${analysisSourceProgress.completed} / ${analysisSourceProgress.total} 个来源`
        )
      }
    }))
    const uploaded = []
    const nextErrors = { ...requirementSourceErrors.value }
    settled.forEach((result, index) => {
      const source = uploadCandidates[index].source
      if (result.status === 'fulfilled') {
        uploaded.push(result.value.document)
        delete nextErrors[source.name]
      } else {
        nextErrors[source.name] = result.reason instanceof Error
          ? result.reason.message
          : '来源上传失败'
      }
    })
    requirementSourceErrors.value = nextErrors
    aiSourceDocuments.value = uploaded
    if (uploaded.length === 0) throw new Error('所有需求来源均上传失败')

    setAiAnalysisProgress(55, `已上传 ${uploaded.length} 个来源，正在创建 AI 分析任务`)
    const run = await createAiAnalysisRun({
      projectId: project.id,
      documentIds: uploaded.map((document) => document.documentId),
      targetCaseKind: 'web_automation',
      templateId: generationTemplateId.value,
      analysisStrategy: 'full'
    })
    aiAnalysisRun.value = run
    if (run.status === 'failed') {
      await loadAiAnalysisDetails(run.runId)
      throw new Error(run.errorSummary || 'AI 分析需求失败')
    }
    setAiAnalysisProgress(84, '分析完成，正在加载草稿测试点')
    const draftList = await listAiDraftTestPoints(run.runId)
    aiDraftPoints.value = draftList.items
    await loadAiAnalysisDetails(run.runId)
    extractedRequirementPoints.value = draftList.items.map(aiDraftPointToRequirementPoint)
    generatedWebCases.value = []
    selectedCase.value = undefined
    setAiAnalysisProgress(100, `内容解析完成，共生成 ${draftList.items.length} 条草稿测试点`)
    setAiCaseGenerationProgress(0, '等待确认草稿测试点并生成用例')
    const failedCount = settled.length - uploaded.length
    if (failedCount > 0) {
      ElMessage.warning(`已解析 ${uploaded.length} 个来源，${failedCount} 个来源可在左侧单独重试`)
    } else {
      ElMessage.success(`AI 已生成 ${draftList.items.length} 条草稿测试点`)
    }
  } catch (error) {
    const message = presentAiWorkflowError(error, 'AI 分析需求失败')
    aiAnalysisError.value = message
    setAiAnalysisProgress(0, '内容解析未完成')
    ElMessage.error(message)
  } finally {
    aiAnalysisRunning.value = false
  }
}
```

- [ ] **Step 8: Replace the old requirement-flow card for import and parse stages**

Delete the old block beginning with:

```vue
<div v-if="showWebRequirementFlow" class="requirement-flow-panel web-flow-card">
```

and ending at its matching closing `</div>` after the third old flow step. Insert:

```vue
<RequirementImportStage
  v-if="generationKind === 'web_automation' && ['import', 'parse'].includes(caseDesignWorkflow.activeStage)"
  :sources="caseDesignSourceView"
  :analysis-status="aiAnalysisRunning ? 'running' : aiAnalysisError ? 'failed' : aiAnalysisCompleted ? 'succeeded' : 'idle'"
  :progress-percentage="aiAnalysisProgress.percentage"
  :progress-text="aiAnalysisProgress.text"
  :error-message="aiAnalysisError"
  :parsed-items="caseDesignParsedItems"
  :completed-source-count="analysisSourceProgress.completed"
  :total-source-count="analysisSourceProgress.total || readyRequirementSourceCount"
  @pick-files="pickWebCaseFiles"
  @import-figma="promptFigmaDesignImport"
  @pick-open-api="pickOpenApiFiles('requirement')"
  @retry-source="retryRequirementSource"
  @replace-source="replaceRequirementSource"
  @remove-source="removeRequirementSource"
/>
```

Set the shell context pane to hide for the new Web workflow because the stage owns its contextual source rail:

```ts
const shellContextVisible = computed(() => activeNav.value !== 'api' || generationKind.value === 'interface')
```

and pass `:context-visible="shellContextVisible"` to `AppShell`.

- [ ] **Step 9: Run source, component, workflow, and build checks**

Run:

```bash
npm test -- --run src/domain/caseDesignSourceView.test.ts src/components/workflow/RequirementImportStage.test.ts src/domain/caseDesignWorkflow.test.ts
npm run build
```

Expected: tests PASS; uploading one unreadable file no longer removes readable files.

- [ ] **Step 10: Commit import and parse migration**

```bash
git add apps/web/src/domain/caseDesignSourceView.ts apps/web/src/domain/caseDesignSourceView.test.ts apps/web/src/components/workflow/RequirementImportStage.vue apps/web/src/components/workflow/RequirementImportStage.test.ts apps/web/src/App.vue
git commit -m "feat: redesign requirement import and parsing"
```

## Task 7: Generate Cases In Batches And Show Partial Results

**Files:**
- Create: `apps/web/src/domain/caseGenerationBatch.test.ts`
- Create: `apps/web/src/domain/caseGenerationBatch.ts`
- Create: `apps/web/src/components/workflow/AiGenerationStage.test.ts`
- Create: `apps/web/src/components/workflow/AiGenerationStage.vue`
- Create: `apps/web/src/components/workflow/GeneratedWebCaseTable.test.ts`
- Create: `apps/web/src/components/workflow/GeneratedWebCaseTable.vue`
- Modify: `apps/web/src/domain/webCaseImport.ts:65-120`
- Modify: `apps/web/src/domain/webCaseImport.test.ts`
- Modify: `apps/web/src/domain/caseDesignDraftRepository.ts`
- Modify: `apps/web/src/App.vue:1914-1945`
- Modify: `apps/web/src/App.vue:5705-6215`

- [ ] **Step 1: Write failing batch and ID-offset tests**

Create `apps/web/src/domain/caseGenerationBatch.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { chunkCaseGenerationPointIds, nextCaseGenerationIdOffset } from './caseGenerationBatch'

describe('caseGenerationBatch', () => {
  it('splits point IDs into stable batches', () => {
    expect(chunkCaseGenerationPointIds(['p1', 'p2', 'p3', 'p4', 'p5'], 2)).toEqual([
      ['p1', 'p2'],
      ['p3', 'p4'],
      ['p5']
    ])
  })

  it('rejects invalid batch sizes', () => {
    expect(() => chunkCaseGenerationPointIds(['p1'], 0)).toThrow('batchSize must be greater than 0')
  })

  it('uses the greatest existing suffix instead of array length after deletion', () => {
    expect(nextCaseGenerationIdOffset(['WEB-AI-0001', 'WEB-AI-0003'])).toBe(3)
  })
})
```

Add this test to `apps/web/src/domain/webCaseImport.test.ts` after the existing AI-generated-case test:

```ts
it('offsets AI-generated case IDs across frontend batches', () => {
  const generated = buildGeneratedCasesFromAiGeneratedCases(
    [{
      sourcePointId: 'point-2',
      title: '第二批用例',
      module: '退款',
      caseKind: 'web_automation',
      pageName: '退款页',
      startUrl: '/refund',
      browser: 'chromium',
      viewport: '1440x900',
      precondition: '',
      objective: '验证第二批',
      steps: 'goto /refund\nassertVisible .refund',
      assertions: 'assertVisible .refund',
      expectedResult: '退款页可见',
      sourceFile: 'refund.md',
      sourceText: '第二批'
    }],
    [],
    'haitlify',
    { idOffset: 10 }
  )

  expect(generated.cases[0].id).toBe('WEB-AI-0011')
})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
npm test -- --run src/domain/caseGenerationBatch.test.ts src/domain/webCaseImport.test.ts
```

Expected: FAIL because batching and `idOffset` are not implemented.

- [ ] **Step 3: Implement stable batching**

Create `apps/web/src/domain/caseGenerationBatch.ts`:

```ts
export const chunkCaseGenerationPointIds = (
  pointIds: string[],
  batchSize = 10
): string[][] => {
  if (batchSize <= 0) throw new Error('batchSize must be greater than 0')
  const batches: string[][] = []
  for (let index = 0; index < pointIds.length; index += batchSize) {
    batches.push(pointIds.slice(index, index + batchSize))
  }
  return batches
}

export const nextCaseGenerationIdOffset = (ids: string[]): number =>
  ids.reduce((greatest, id) => {
    const suffix = Number(id.match(/\d+$/)?.[0] || 0)
    return Math.max(greatest, suffix)
  }, 0)
```

Change the `buildGeneratedCasesFromAiGeneratedCases` signature in `apps/web/src/domain/webCaseImport.ts`:

```ts
export const buildGeneratedCasesFromAiGeneratedCases = (
  cases: GeneratedAiCase[],
  sourcePoints: AiDraftTestPoint[],
  projectName: string,
  options: { idOffset?: number } = {}
): ImportedWebCaseBatch => ({
  projectName: projectName || '默认项目',
  cases: cases.map((testCase, index) => {
    const sourcePoint = sourcePoints.find((point) => point.pointId === testCase.sourcePointId)
    const steps = splitStepLines(testCase.steps)
    if (steps.length === 0) {
      throw new Error(`${testCase.sourcePointId || testCase.title} 缺少可执行 Web 步骤`)
    }
    const metadataSteps = steps.map((line, stepIndex) => parseStepLine(line, stepIndex))
    const manualStep = metadataSteps.find((step) => step.action === 'manual')
    if (manualStep) {
      throw new Error(`${testCase.sourcePointId || testCase.title} 包含不可执行步骤：${manualStep.target}`)
    }

    return buildGeneratedWebCase({
      id: `WEB-AI-${String((options.idOffset || 0) + index + 1).padStart(4, '0')}`,
      sourcePointId: testCase.sourcePointId,
      project: projectName || '默认项目',
      module: testCase.module || sourcePoint?.module || '默认模块',
      pageName: testCase.pageName,
      title: testCase.title,
      caseType: 'AI用例',
      startUrl: testCase.startUrl,
      browser: testCase.browser,
      viewport: testCase.viewport,
      sourceFile: testCase.sourceFile,
      sourceDocumentType: 'markdown',
      steps: metadataSteps,
      precondition: testCase.precondition,
      stepsText: steps.join('\n'),
      assertionsText: testCase.assertions,
      priority: sourcePoint?.priority || 'P1',
      remark: testCase.sourceText
    })
  })
})
```

Add optional source traceability to `WebAutomationCaseMetadata` in `apps/web/src/domain/webAutomationCase.ts`:

```ts
export interface WebAutomationCaseMetadata {
  sourcePointId?: string
  sourceFile: string
  sourceDocumentType: WebAutomationDocumentType
  pageName: string
  startUrl: string
  browser: WebAutomationBrowser
  viewport: string
  steps: WebAutomationStep[]
  locators: WebLocator[]
  assertions: WebAssertion[]
  variables: Record<string, string>
  runOrder: number
}
```

Replace `buildGeneratedWebCase` with the source-traceable version:

```ts
const buildGeneratedWebCase = (input: {
  id: string
  sourcePointId?: string
  project: string
  module: string
  pageName: string
  title: string
  caseType: string
  startUrl: string
  browser: string
  viewport: string
  sourceFile: string
  sourceDocumentType: WebAutomationDocumentType
  steps: WebAutomationStep[]
  precondition?: string
  stepsText?: string
  assertionsText?: string
  priority?: string
  remark?: string
}): GeneratedWebAutomationCase => ({
  id: input.id,
  row: {
    caseId: input.id,
    project: input.project,
    module: input.module,
    pageName: input.pageName,
    title: input.title,
    caseType: input.caseType,
    precondition: input.precondition || '',
    startUrl: input.startUrl,
    browser: input.browser,
    viewport: input.viewport,
    testData: '{}',
    steps: input.stepsText || input.steps.map((step) => `${step.action} ${step.target}`).join('\n'),
    locators: input.steps.filter(isLocatorBackedStep).map((step) => step.target).join(', '),
    assertions: input.assertionsText || input.steps
      .filter((step) => step.action.startsWith('assert'))
      .map((step) => `${step.action} ${step.target}`)
      .join('\n'),
    waitStrategy: 'networkidle',
    screenshotStrategy: 'on_failure',
    priority: input.priority || 'P1',
    executionResult: '未执行',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: `${input.module},Web自动化`,
    sourceFile: input.sourceFile,
    remark: input.remark || ''
  },
  metadata: {
    sourcePointId: input.sourcePointId,
    sourceFile: input.sourceFile,
    sourceDocumentType: input.sourceDocumentType,
    pageName: input.pageName,
    startUrl: input.startUrl,
    browser: normalizeBrowser(input.browser),
    viewport: input.viewport,
    steps: input.steps,
    locators: input.steps.filter(isLocatorBackedStep).map((step, index) => ({
      id: `locator-${index + 1}`,
      name: step.target,
      locatorType: 'css' as const,
      value: step.target
    })),
    assertions: input.steps.filter((step) => step.action.startsWith('assert')).map((step, index) => ({
      id: `assertion-${index + 1}`,
      name: step.name,
      type: step.action === 'assertVisible' ? 'visible' as const : 'text' as const,
      target: step.target
    })),
    variables: {},
    runOrder: Number(input.id.match(/\d+$/)?.[0]) || 1
  }
})
```

Extend the offset test with:

```ts
expect(generated.cases[0].metadata.sourcePointId).toBe('point-2')
```

- [ ] **Step 4: Write failing generation-stage component tests**

Create `apps/web/src/components/workflow/AiGenerationStage.test.ts`:

```ts
// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AiGenerationStage from './AiGenerationStage.vue'

describe('AiGenerationStage', () => {
  it('shows progress and partial results together', () => {
    const wrapper = mount(AiGenerationStage, {
      props: {
        running: true,
        progressPercentage: 50,
        progressText: '已完成 1 / 2 批',
        generatedCount: 10,
        expectedCount: 20,
        errorMessage: ''
      },
      slots: {
        controls: '<div>生成配置</div>',
        drafts: '<div>草稿测试点</div>',
        results: '<div>已生成结果</div>'
      }
    })

    expect(wrapper.text()).toContain('10 / 20')
    expect(wrapper.text()).toContain('已完成 1 / 2 批')
    expect(wrapper.text()).toContain('已生成结果')
  })

  it('emits retry without clearing partial results', async () => {
    const wrapper = mount(AiGenerationStage, {
      props: {
        running: false,
        progressPercentage: 50,
        progressText: '生成中断',
        generatedCount: 10,
        expectedCount: 20,
        errorMessage: '第二批生成失败'
      }
    })

    await wrapper.get('[data-testid="retry-generation"]').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })
})
```

- [ ] **Step 5: Run the generation-stage test to verify it fails**

Run:

```bash
npm test -- --run src/components/workflow/AiGenerationStage.test.ts
```

Expected: FAIL because `AiGenerationStage.vue` does not exist.

- [ ] **Step 6: Implement the generation-stage layout**

Create `apps/web/src/components/workflow/AiGenerationStage.vue`:

```vue
<script setup lang="ts">
defineProps<{
  running: boolean
  progressPercentage: number
  progressText: string
  generatedCount: number
  expectedCount: number
  errorMessage: string
}>()

const emit = defineEmits<{
  retry: []
}>()
</script>

<template>
  <section class="studio-generation-stage" data-testid="ai-generation-stage">
    <header class="studio-generation-stage__toolbar">
      <slot name="controls" />
      <div class="studio-generation-stage__count">
        <span>已生成 / 范围测试点</span>
        <strong>{{ generatedCount }} / {{ expectedCount }}</strong>
      </div>
    </header>

    <div v-if="running || progressPercentage > 0" class="studio-generation-stage__progress" role="status">
      <el-progress :percentage="progressPercentage" :status="generatedCount === expectedCount && expectedCount > 0 ? 'success' : undefined" />
      <span>{{ progressText }}</span>
    </div>

    <div v-if="errorMessage" class="studio-generation-stage__error" role="alert">
      <span>{{ errorMessage }}</span>
      <el-button data-testid="retry-generation" text type="primary" @click="emit('retry')">重试未完成批次</el-button>
    </div>

    <div class="studio-generation-stage__content">
      <section class="studio-generation-stage__drafts"><slot name="drafts" /></section>
      <section class="studio-generation-stage__results"><slot name="results" /></section>
    </div>
  </section>
</template>
```

- [ ] **Step 7: Write failing reusable generated Web-case table tests**

Create `apps/web/src/components/workflow/GeneratedWebCaseTable.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import source from './GeneratedWebCaseTable.vue?raw'

describe('GeneratedWebCaseTable structure', () => {
  it('keeps the compact review columns and row actions', () => {
    for (const label of ['用例ID', '模块', '页面', '测试场景', '类型', '步骤', '状态', '操作']) {
      expect(source).toContain(label)
    }
    expect(source).toContain('type="selection"')
    expect(source).toContain('class="studio-case-title-button"')
    expect(source).toContain("emit('select', $event)")
    expect(source).toContain("emit('selectionChange', $event)")
    expect(source).toContain("emit('edit', row)")
    expect(source).toContain("emit('remove', row)")
  })
})
```

- [ ] **Step 8: Run the generated-table test to verify it fails**

Run:

```bash
npm test -- --run src/components/workflow/GeneratedWebCaseTable.test.ts
```

Expected: FAIL because `GeneratedWebCaseTable.vue` does not exist.

- [ ] **Step 9: Implement the reusable generated Web-case table**

Create `apps/web/src/components/workflow/GeneratedWebCaseTable.vue`:

```vue
<script setup lang="ts">
import { Trash2 } from 'lucide-vue-next'
import type { GeneratedWebAutomationCase } from '../../domain/webAutomationCase'

const props = withDefaults(defineProps<{
  cases: GeneratedWebAutomationCase[]
  selectedCaseId?: string
  selectionEnabled?: boolean
}>(), {
  selectedCaseId: '',
  selectionEnabled: true
})

const emit = defineEmits<{
  select: [testCase: GeneratedWebAutomationCase]
  selectionChange: [cases: GeneratedWebAutomationCase[]]
  edit: [testCase: GeneratedWebAutomationCase]
  remove: [testCase: GeneratedWebAutomationCase]
}>()

const rowClassName = ({ row }: { row: GeneratedWebAutomationCase }) =>
  row.id === props.selectedCaseId ? 'is-selected-case' : ''
</script>

<template>
  <el-table
    class="studio-generated-web-table"
    :data="cases"
    height="100%"
    row-key="id"
    :row-class-name="rowClassName"
    @row-click="emit('select', $event)"
    @selection-change="emit('selectionChange', $event)"
  >
    <el-table-column v-if="selectionEnabled" type="selection" width="42" fixed />
    <el-table-column prop="row.caseId" label="用例ID" width="120" fixed />
    <el-table-column prop="row.module" label="模块" min-width="120" />
    <el-table-column prop="row.pageName" label="页面" min-width="140" />
    <el-table-column label="测试场景" min-width="260" show-overflow-tooltip>
      <template #default="{ row }">
        <button
          type="button"
          class="studio-case-title-button"
          :aria-label="`查看 ${row.row.caseId} ${row.row.title}`"
          @click.stop="emit('select', row)"
        >
          {{ row.row.title }}
        </button>
      </template>
    </el-table-column>
    <el-table-column prop="row.caseType" label="类型" width="110" />
    <el-table-column label="步骤" width="80">
      <template #default="{ row }">{{ row.metadata.steps.length }}</template>
    </el-table-column>
    <el-table-column prop="row.executionResult" label="状态" width="100" />
    <el-table-column label="操作" width="130" fixed="right">
      <template #default="{ row }">
        <el-button link @click.stop="emit('edit', row)">编辑</el-button>
        <el-button link type="danger" :aria-label="`删除 ${row.row.caseId}`" @click.stop="emit('remove', row)">
          <Trash2 :size="14" />
        </el-button>
      </template>
    </el-table-column>
  </el-table>
</template>
```

- [ ] **Step 10: Refactor AI generation to append completed batches**

Import batching in `apps/web/src/App.vue`:

```ts
import { chunkCaseGenerationPointIds, nextCaseGenerationIdOffset } from './domain/caseGenerationBatch'
import AiGenerationStage from './components/workflow/AiGenerationStage.vue'

const generationScopePointIds = ref<string[]>(restoredCaseDesignDraft?.generationScopePointIds || [])
const advancedGenerationVisible = ref(false)
const selectedGeneratedCaseIds = ref<string[]>(restoredCaseDesignDraft?.selectedGeneratedCaseIds || [])
const generationScopeOptions = computed(() => aiDraftPoints.value
  .filter((point) => point.reviewStatus === 'confirmed' || point.reviewStatus === 'modified')
  .map((point) => {
    const source = caseDesignSourceView.value.find((item) => item.name === point.sourceFile)
    return {
      value: point.pointId,
      label: `${source?.kindLabel || '需求'} · ${point.pageOrFeature || point.title}`
    }
  }))
const eligibleGenerationPointCount = computed(() => {
  if (generationScopePointIds.value.length === 0) return generationScopeOptions.value.length
  const available = new Set(generationScopeOptions.value.map((option) => option.value))
  return generationScopePointIds.value.filter((pointId) => available.has(pointId)).length
})
const updateGeneratedCaseSelection = (cases: GeneratedWebAutomationCase[]) => {
  selectedGeneratedCaseIds.value = cases.map((testCase) => testCase.id)
}

const invalidateCaseReview = () => {
  caseAiReviewRun.value = undefined
  caseReviewAttempted.value = false
  caseReviewError.value = ''
  caseReviewFindingDecisions.value = {}
  restoredAiReviewInsight.value = undefined
  if (activeAiReviewStage.value === 'case') {
    aiMultiAgentGraphNodes.value = []
    aiMultiAgentHandoffs.value = []
    aiMultiAgentConflicts.value = []
    aiMultiAgentQualityScores.value = []
    aiMultiAgentAutomationSuggestions.value = []
    aiMultiAgentMergeResult.value = undefined
  }
  if (activeCaseDesignStage.value === 'deliver') activeCaseDesignStage.value = 'review'
}

const removeGeneratedWebCase = (testCase: GeneratedWebAutomationCase) => {
  const next = generatedWebCases.value.filter((item) => item.id !== testCase.id)
  if (next.length === generatedWebCases.value.length) return
  generatedWebCases.value = next
  selectedGeneratedCaseIds.value = selectedGeneratedCaseIds.value.filter((id) => id !== testCase.id)
  if (selectedCase.value?.id === testCase.id) selectedCase.value = next[0]
  invalidateCaseReview()
}
```

Extend `CaseDesignDraftSnapshot` with optional UI selection fields so older v1 payloads remain readable:

```ts
generationScopePointIds?: string[]
selectedGeneratedCaseIds?: string[]
```

Add both arrays to the object passed to `saveCaseDesignDraft` and to the Task 5 autosave watch list:

```ts
generationScopePointIds: generationScopePointIds.value,
selectedGeneratedCaseIds: selectedGeneratedCaseIds.value,
```

Replace `generateWebCasesWithAi` with:

```ts
const generateWebCasesWithAi = async () => {
  const confirmedPoints = aiDraftPoints.value.filter((point) =>
    point.reviewStatus === 'confirmed' || point.reviewStatus === 'modified'
  )
  const selectedScope = new Set(generationScopePointIds.value)
  const eligiblePoints = selectedScope.size > 0
    ? confirmedPoints.filter((point) => selectedScope.has(point.pointId))
    : confirmedPoints
  if (eligiblePoints.length === 0) {
    ElMessage.warning('请先确认 AI 草稿测试点')
    return
  }

  const batches = chunkCaseGenerationPointIds(eligiblePoints.map((point) => point.pointId), 10)
  const accumulated = [...generatedWebCases.value]
  const completedSourceIds = new Set(accumulated.map((item) => item.metadata.sourcePointId).filter(Boolean))
  const remainingBatches = batches
    .map((batch) => batch.filter((pointId) => !completedSourceIds.has(pointId)))
    .filter((batch) => batch.length > 0)

  aiCaseGenerationRunning.value = true
  aiGenerationError.value = ''
  setAiCaseGenerationProgress(0, `准备生成 ${eligiblePoints.length} 条 AI 用例`)
  try {
    for (let index = 0; index < remainingBatches.length; index += 1) {
      const batch = remainingBatches[index]
      const response = await generateCasesFromAiDraftPoints(batch)
      const generated = buildGeneratedCasesFromAiGeneratedCases(
        response.items,
        aiDraftPoints.value,
        requirementAnalysisProjectName.value || '默认项目',
        { idOffset: nextCaseGenerationIdOffset(accumulated.map((testCase) => testCase.id)) }
      )
      accumulated.push(...generated.cases)
      if (generated.cases.length > 0) invalidateCaseReview()
      generatedWebCases.value = [...accumulated]
      selectedCase.value ||= generatedWebCases.value[0]
      const completed = index + 1
      setAiCaseGenerationProgress(
        Math.round((completed / remainingBatches.length) * 100),
        `已完成 ${completed} / ${remainingBatches.length} 批`
      )
    }
    setAiCaseGenerationProgress(100, `AI用例生成完成，共生成 ${accumulated.length} 条用例`)
    ElMessage.success(`已生成 ${accumulated.length} 条 Web 用例`)
  } catch (error) {
    const message = presentAiWorkflowError(error, 'AI 用例生成失败')
    aiGenerationError.value = message
    ElMessage.error(`${message}，已保留 ${accumulated.length} 条成功结果`)
  } finally {
    aiCaseGenerationRunning.value = false
  }
}
```

- [ ] **Step 11: Wrap draft analysis and the reusable table in the generation stage**

In the Web branch of `apps/web/src/App.vue`, render `AiGenerationStage` when `caseDesignWorkflow.activeStage === 'generate'`:

```vue
<AiGenerationStage
  v-if="generationKind === 'web_automation' && caseDesignWorkflow.activeStage === 'generate'"
  :running="aiCaseGenerationRunning"
  :progress-percentage="aiCaseGenerationProgress.percentage"
  :progress-text="aiCaseGenerationProgress.text"
  :generated-count="generatedWebCases.length"
  :expected-count="eligibleGenerationPointCount"
  :error-message="aiGenerationError"
  @retry="generateWebCasesWithAi"
>
  <template #controls>
    <el-select v-model="generationTemplateId" class="template-select" aria-label="用例模板">
      <el-option
        v-for="template in generationTemplateOptions"
        :key="template.value"
        :label="template.label"
        :value="template.value"
      />
    </el-select>
    <el-select
      v-model="generationScopePointIds"
      multiple
      collapse-tags
      clearable
      aria-label="生成范围与粒度"
      placeholder="全部已确认测试点"
    >
      <el-option
        v-for="option in generationScopeOptions"
        :key="option.value"
        :label="option.label"
        :value="option.value"
      />
    </el-select>
    <el-button plain @click="advancedGenerationVisible = true">高级设置</el-button>
  </template>
  <template #results>
    <GeneratedWebCaseTable
      :cases="filteredWebCases"
      :selected-case-id="selectedCase?.id"
      @select="selectedCase = $event"
      @selection-change="updateGeneratedCaseSelection"
      @edit="openWebEdit"
      @remove="removeGeneratedWebCase"
    />
  </template>
</AiGenerationStage>

<el-drawer
  v-model="advancedGenerationVisible"
  title="AI 高级设置"
  size="360px"
  append-to-body
>
  <dl class="studio-generation-advanced-summary">
    <div><dt>Provider</dt><dd>{{ aiConfig.provider || '未配置' }}</dd></div>
    <div><dt>Model</dt><dd>{{ aiConfig.model || '未配置' }}</dd></div>
    <div><dt>分析策略</dt><dd>沿用当前需求解析结果</dd></div>
  </dl>
  <el-button type="primary" plain @click="advancedGenerationVisible = false; navigateToNav('aiConfig')">
    前往 AI 设置
  </el-button>
</el-drawer>
```

Do not render a `generationKind` selector inside `AiGenerationStage`: this stage is Web-only in the approved first migration phase. Keep the existing generation-type selector unchanged inside the legacy `control-strip`; it remains the entry from the legacy interface workspace into the Web Studio workflow. Task 9 adds a persistent quiet `WorkflowHeader` mode action so every Studio stage can return to the legacy interface workspace without adding a second primary command.

Immediately after the closing `</template>` of `#controls` and before `<template #results>`, insert this complete drafts slot:

```vue
<template #drafts>
  <div class="case-filters studio-ai-draft-filters">
    <span class="filter-title">AI 草稿测试点</span>
    <el-select v-model="aiDraftFilter.module" placeholder="模块">
      <el-option label="全部模块" value="ALL" />
      <el-option
        v-for="module in aiDraftPointView.moduleOptions"
        :key="module.value"
        :label="`${module.value}（${module.count}）`"
        :value="module.value"
      />
    </el-select>
    <el-select v-model="aiDraftFilter.status" placeholder="状态">
      <el-option label="全部状态" value="ALL" />
      <el-option
        v-for="status in aiDraftPointView.statusOptions"
        :key="status.value"
        :label="`${aiDraftStatusText(status.value)}（${status.count}）`"
        :value="status.value"
      />
    </el-select>
    <el-select v-model="aiDraftFilter.confidence" placeholder="置信度">
      <el-option label="全部置信度" value="ALL" />
      <el-option label="高" value="high" />
      <el-option label="中" value="medium" />
      <el-option label="低" value="low" />
    </el-select>
    <el-select v-model="aiDraftFilter.sourceDocumentId" placeholder="来源">
      <el-option label="全部来源" value="ALL" />
      <el-option
        v-for="source in aiDraftPointView.sourceOptions"
        :key="source.value"
        :label="`${source.label}（${source.count}）`"
        :value="source.value"
      />
    </el-select>
    <el-button
      type="primary"
      plain
      :loading="aiDraftBulkConfirming"
      :disabled="aiDraftSelection.length === 0"
      @click="confirmSelectedAiDraftPoints"
    >
      <CheckCircle2 :size="16" />
      批量确认
    </el-button>
    <el-button
      :loading="aiDraftBulkConfirming"
      :disabled="aiDraftPointView.items.length === 0"
      @click="confirmAllVisibleAiDraftPoints"
    >
      全部确认
    </el-button>
  </div>

  <div
    v-if="aiFigmaFile"
    class="ai-figma-panel"
    v-loading="aiFigmaLoading"
  >
    <section class="ai-figma-section">
      <div class="ai-analysis-section-head">
        <strong>Figma 节点</strong>
        <span>{{ aiFigmaFile.fileKey }} / {{ aiFigmaFile.selectedNodeId || '全文件' }}</span>
      </div>
      <div class="ai-figma-actions">
        <el-select v-model="selectedAiFigmaNodeId" placeholder="选择节点">
          <el-option
            v-for="node in aiFigmaNodes.items"
            :key="node.figmaNodeId"
            :label="`${node.nodeName}（${node.nodeType}）`"
            :value="node.figmaNodeId"
          />
        </el-select>
        <el-button type="primary" plain @click="analyzeAiFigmaFile">分析文件</el-button>
        <el-button :disabled="!selectedAiFigmaNodeId" @click="analyzeSelectedAiFigmaNode">
          分析选中节点
        </el-button>
      </div>
      <div v-if="aiFigmaNodeTree.length" class="ai-figma-node-tree">
        <div v-for="root in aiFigmaNodeTree" :key="root.id" class="ai-figma-node">
          <strong>{{ root.label }}</strong>
          <span>{{ root.type }}</span>
          <div v-for="child in root.children" :key="child.id" class="ai-figma-node child">
            <span>{{ child.label }}</span>
            <small>{{ child.componentName || child.type }}</small>
          </div>
        </div>
      </div>
    </section>

    <section class="ai-figma-section">
      <div class="ai-analysis-section-head">
        <strong>Figma 草稿</strong>
        <span>{{ aiFigmaAnalysisRun?.status || '未分析' }}</span>
      </div>
      <div v-if="aiFigmaDraftPoints.length" class="ai-mini-rows">
        <div v-for="point in aiFigmaDraftPoints" :key="point.pointId" class="ai-mini-row">
          <span>{{ point.title }}</span>
          <el-tag size="small" effect="light">{{ point.reviewStatus }}</el-tag>
          <code>{{ figmaDraftEvidenceLabel({ evidence: point.metadata.evidence }) }}</code>
        </div>
      </div>
      <el-empty v-else description="暂无 Figma 草稿" :image-size="42" />
    </section>

    <section class="ai-figma-section">
      <div class="ai-analysis-section-head">
        <strong>设计变更</strong>
        <span>{{ aiFigmaChangeRows.length }}</span>
      </div>
      <div v-if="aiFigmaChangeRows.length" class="ai-mini-rows">
        <div
          v-for="change in aiFigmaChangeRows"
          :key="`${change.changeType}-${change.nodeId}`"
          class="ai-mini-row"
        >
          <el-tag size="small" effect="light">{{ change.changeType }}</el-tag>
          <span>{{ change.nodeName || change.nodeId }}</span>
          <code>{{ change.changedFields.join(', ') || '-' }}</code>
        </div>
      </div>
      <el-empty v-else description="暂无变更集" :image-size="42" />
    </section>

    <section class="ai-figma-section">
      <div class="ai-analysis-section-head">
        <strong>元素候选</strong>
        <span>{{ aiFigmaElementCandidates.items.length }}</span>
      </div>
      <div v-if="aiFigmaCandidateGroups.length" class="ai-mini-rows">
        <div v-for="group in aiFigmaCandidateGroups" :key="group.key" class="ai-mini-row">
          <span>{{ group.componentName }}</span>
          <el-tag size="small" effect="plain">{{ group.role }}</el-tag>
          <strong>{{ group.confirmedCount }} / {{ group.items.length }}</strong>
        </div>
      </div>
      <el-empty v-else description="暂无元素候选" :image-size="42" />
    </section>
  </div>

  <el-table
    v-if="aiDraftPointView.items.length > 0 && generatedWebCases.length === 0"
    class="case-table"
    :data="aiDraftPointView.items"
    height="100%"
    border
    row-key="pointId"
    @selection-change="(rows: AiDraftTestPoint[]) => (aiDraftSelection = rows)"
  >
    <el-table-column type="selection" width="48" fixed />
    <el-table-column prop="module" label="模块" min-width="120" />
    <el-table-column prop="title" label="测试点" min-width="240" />
    <el-table-column label="来源" min-width="180">
      <template #default="{ row }">
        <div class="source-cell">
          <strong>{{ row.sourceFile }}</strong>
          <span>{{ row.sourceChunkId }}</span>
        </div>
      </template>
    </el-table-column>
    <el-table-column label="视觉证据" min-width="190">
      <template #default="{ row }">
        <div v-if="aiDraftVisualEvidence(row)" class="visual-evidence-cell">
          <el-tag type="warning" effect="light">
            {{ aiDraftVisualEvidence(row)?.regionType }}
          </el-tag>
          <span>{{ aiDraftVisualCoordinateText(row) }}</span>
          <small v-if="aiDraftVisualEvidence(row)?.componentHint">
            {{ aiDraftVisualEvidence(row)?.componentHint }}
          </small>
        </div>
        <span v-else>-</span>
      </template>
    </el-table-column>
    <el-table-column label="置信度" width="110">
      <template #default="{ row }">
        <el-tag :type="aiDraftConfidenceTagType(row.confidenceScore)" effect="light">
          {{ aiDraftConfidenceText(row.confidenceScore) }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="状态" width="110">
      <template #default="{ row }">
        <el-tag :type="aiDraftStatusTagType(row.reviewStatus)" effect="light">
          {{ aiDraftStatusText(row.reviewStatus) }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="是否推断" width="110">
      <template #default="{ row }">
        {{ row.inferred ? '是' : '否' }}
      </template>
    </el-table-column>
    <el-table-column label="操作" width="190" fixed="right">
      <template #default="{ row }">
        <div class="table-actions">
          <el-button link @click.stop="confirmAiDraftPoint(row)">确认</el-button>
          <el-button link @click.stop="editConfirmAiDraftPoint(row)">编辑确认</el-button>
          <el-button link type="danger" @click.stop="rejectAiDraftPoint(row)">拒绝</el-button>
        </div>
      </template>
    </el-table-column>
  </el-table>
</template>
```

Delete the three original blocks after inserting the slot:

1. Delete the old AI-draft filter block beginning with:

```vue
<div class="case-filters" v-else>
  <span class="filter-title">AI 草稿测试点</span>
```

and ending at its matching `</div>` after the `全部确认` button.

2. Delete the old Figma block beginning with:

```vue
<div v-if="generationKind === 'web_automation' && aiFigmaFile" class="ai-figma-panel"
```

and ending at its matching `</div>` after the `元素候选` section.

3. Delete the old draft table beginning with:

```vue
<el-table
  v-else-if="aiDraftPointView.items.length > 0 && generatedWebCases.length === 0"
```

and ending at its matching `</el-table>` after the `拒绝` action column.

These opening directives intentionally change in the final slot:

- The filter is unconditional because `AiGenerationStage` already scopes it to the Web generation stage.
- The Figma panel uses `v-if="aiFigmaFile"` because the parent stage already enforces `generationKind === 'web_automation'`.
- The draft table uses an independent `v-if`; retaining the old `v-else-if` would incorrectly attach it to the Figma panel and hide the table whenever Figma data exists.

Delete the unique old generated Web-case table beginning with:

```vue
<el-table
  v-else-if="filteredWebCases.length > 0"
```

because `GeneratedWebCaseTable` replaces it in both generation and review.

Verify the final fragments before continuing:

```bash
rg -n 'studio-ai-draft-filters|class="ai-figma-panel"|:data="aiDraftPointView.items"|:data="filteredWebCases"' apps/web/src/App.vue
```

Expected: exactly one draft-filter block, one Figma panel, one AI draft table, and no legacy table bound directly to `filteredWebCases`.

- [ ] **Step 12: Reuse the generated table in the review stage**

Import `GeneratedWebCaseTable` in `App.vue` and render it as the review canvas:

```vue
<section
  v-if="generationKind === 'web_automation' && caseDesignWorkflow.activeStage === 'review'"
  class="studio-review-stage"
>
  <header class="studio-review-stage__filters">
    <el-select v-model="webPreviewFilter.module" aria-label="评审模块">
      <el-option label="全部模块" value="ALL" />
      <el-option v-for="module in webPreviewModules" :key="module.value" :label="module.value" :value="module.value" />
    </el-select>
    <el-select v-model="webPreviewFilter.caseType" aria-label="评审用例类型">
      <el-option label="全部类型" value="ALL" />
      <el-option v-for="caseType in webPreviewCaseTypes" :key="caseType.value" :label="caseType.value" :value="caseType.value" />
    </el-select>
    <el-input v-model="webPreviewFilter.keyword" clearable placeholder="搜索 Web 用例" />
  </header>
  <GeneratedWebCaseTable
    v-if="filteredWebCases.length > 0"
    :cases="filteredWebCases"
    :selected-case-id="selectedCase?.id"
    @select="selectedCase = $event"
    @selection-change="updateGeneratedCaseSelection"
    @edit="openWebEdit"
    @remove="removeGeneratedWebCase"
  />
</section>
```

Immediately after `.studio-review-stage`, insert this complete locator block. Its parent branch is already Web-only, so the final condition no longer repeats `generationKind`:

```vue
<div v-if="selectedDesignLocatorCards.length" class="design-locator-panel">
  <div class="design-locator-head">
    <strong>定位器候选</strong>
    <span>主候选、回退、置信度与推荐理由</span>
  </div>
  <div class="design-locator-list">
    <div v-for="locator in selectedDesignLocatorCards" :key="locator.id" class="design-locator-item">
      <div class="design-locator-item-head">
        <strong>{{ locator.name }}</strong>
        <div class="design-locator-item-tags">
          <el-tag
            size="small"
            :type="locator.confidence === 'high' ? 'success' : locator.confidence === 'medium' ? 'warning' : 'info'"
          >
            {{ locator.confidence }}
          </el-tag>
          <el-tag v-if="locator.confirmed" size="small" type="primary">已确认</el-tag>
        </div>
      </div>
      <div class="design-locator-primary">
        <code>{{ locator.primary }}</code>
        <div class="design-locator-actions">
          <el-button text @click="copyText(locator.primary, '已复制主候选定位器')">
            <Copy :size="14" />
          </el-button>
          <el-button text @click="toggleDesignLocatorConfirmed(locator.id)">
            {{ locator.confirmed ? '取消确认' : '确认主候选' }}
          </el-button>
        </div>
      </div>
      <div v-if="locator.fallback.length" class="design-locator-fallbacks">
        <button
          v-for="item in locator.fallback"
          :key="item"
          type="button"
          class="design-locator-fallback-chip"
          @click="copyText(item, '已复制回退候选定位器')"
        >
          {{ item }}
        </button>
      </div>
      <p>{{ locator.reason }}</p>
    </div>
  </div>
</div>
```

Delete the former block beginning with `<div v-if="generationKind === 'web_automation' && selectedDesignLocatorCards.length" class="design-locator-panel">`, then verify `rg -n 'class="design-locator-panel"' apps/web/src/App.vue` returns exactly one match.

- [ ] **Step 13: Run batching, table, component, and build checks**

Run:

```bash
npm test -- --run src/domain/caseGenerationBatch.test.ts src/domain/webCaseImport.test.ts src/components/workflow/AiGenerationStage.test.ts src/components/workflow/GeneratedWebCaseTable.test.ts
npm run build
```

Expected: tests PASS; a later batch failure leaves earlier generated cases visible.

- [ ] **Step 14: Commit incremental generation**

```bash
git add apps/web/src/domain/caseGenerationBatch.ts apps/web/src/domain/caseGenerationBatch.test.ts apps/web/src/domain/webCaseImport.ts apps/web/src/domain/webCaseImport.test.ts apps/web/src/domain/webAutomationCase.ts apps/web/src/domain/caseDesignDraftRepository.ts apps/web/src/components/workflow/AiGenerationStage.vue apps/web/src/components/workflow/AiGenerationStage.test.ts apps/web/src/components/workflow/GeneratedWebCaseTable.vue apps/web/src/components/workflow/GeneratedWebCaseTable.test.ts apps/web/src/App.vue
git commit -m "feat: show incremental AI case generation"
```

## Task 8: Replace The Review Dialog With A Contextual Inspector

**Files:**
- Create: `apps/web/src/domain/caseDesignReviewView.test.ts`
- Create: `apps/web/src/domain/caseDesignReviewView.ts`
- Create: `apps/web/src/components/workflow/AiReviewInspector.test.ts`
- Create: `apps/web/src/components/workflow/AiReviewInspector.vue`
- Create: `apps/web/src/components/workflow/CaseDesignContextPane.test.ts`
- Create: `apps/web/src/components/workflow/CaseDesignContextPane.vue`
- Modify: `apps/web/src/domain/aiMultiAgentRunView.test.ts`
- Modify: `apps/web/src/domain/aiMultiAgentRunView.ts`
- Modify: `apps/web/src/domain/caseDesignDraftRepository.test.ts`
- Modify: `apps/web/src/domain/caseDesignDraftRepository.ts`
- Modify: `apps/web/src/App.vue:900-930`
- Modify: `apps/web/src/App.vue:1682-1875`
- Modify: `apps/web/src/App.vue:5808-5938`

- [ ] **Step 1: Write a failing stable-finding normalization test**

In `apps/web/src/domain/aiMultiAgentRunView.test.ts`, replace the existing `normalizes AI review summary and supplement suggestions from run output` test with:

```ts
it('normalizes stable finding IDs and distinguishes coverage gaps from suggestions', () => {
  const output = {
    reviewStage: 'requirement',
    aiReview: {
      reviewSummary: '需求点缺少异常登录和权限边界。',
      supplementSuggestions: [
        {
          findingId: 'suggestion-login-failure',
          title: '补充登录失败提示校验',
          reason: '当前只覆盖成功链路',
          priority: 'P1'
        },
        '补充账号锁定后的恢复提示'
      ],
      missingPoints: [{
        id: 'gap-permission-page',
        title: '补充无权限访问页面',
        suggestion: '验证跳转到无权限页',
        priority: 'P0'
      }, {
        id: 'shared-review-id',
        title: '补充共享 ID 的覆盖缺口'
      }],
      suggestions: [{
        id: 'shared-review-id',
        title: '补充共享 ID 的改进建议'
      }]
    }
  }

  const first = buildAiReviewInsightView(output)
  const reordered = buildAiReviewInsightView({
    ...output,
    aiReview: {
      ...output.aiReview,
      supplementSuggestions: [...output.aiReview.supplementSuggestions].reverse()
    }
  })

  expect(first.stage).toBe('requirement')
  expect(first.summary).toBe('需求点缺少异常登录和权限边界。')
  expect(first.suggestions).toEqual(expect.arrayContaining([
    expect.objectContaining({
      findingId: 'coverage_gap:gap-permission-page',
      category: 'coverage_gap',
      title: '补充无权限访问页面'
    }),
    expect.objectContaining({
      findingId: 'suggestion:suggestion-login-failure',
      category: 'suggestion',
      title: '补充登录失败提示校验'
    })
  ]))
  expect(first.suggestions
    .filter((item) => item.findingId.endsWith('shared-review-id'))
    .map((item) => item.findingId)
    .sort()
  ).toEqual([
    'coverage_gap:shared-review-id',
    'suggestion:shared-review-id'
  ])
  expect(Object.fromEntries(first.suggestions.map((item) => [item.title, item.findingId])))
    .toEqual(Object.fromEntries(reordered.suggestions.map((item) => [item.title, item.findingId])))
})
```

- [ ] **Step 2: Run the finding-normalization test to verify it fails**

Run:

```bash
npm test -- --run src/domain/aiMultiAgentRunView.test.ts
```

Expected: FAIL because normalized review suggestions do not yet contain `findingId` or `category`.

- [ ] **Step 3: Implement stable, categorized review findings**

In `apps/web/src/domain/aiMultiAgentRunView.ts`, replace `AiReviewSuggestionView`, `buildAiReviewInsightView`, and `toAiReviewSuggestionView` with these complete definitions. Keep the other view helpers unchanged:

```ts
export type AiReviewFindingCategory = 'coverage_gap' | 'suggestion'

export interface AiReviewSuggestionView {
  findingId: string
  category: AiReviewFindingCategory
  title: string
  detail: string
  priority: string
}

export const buildAiReviewInsightView = (outputSnapshot?: Record<string, unknown>): AiReviewInsightView => {
  const review = asRecord(outputSnapshot?.aiReview) || asRecord(outputSnapshot) || {}
  const summary = firstText(
    review.reviewSummary,
    review.summary,
    review.conclusion,
    outputSnapshot?.errorSummary
  )
  const findingValues: Array<{ value: unknown; category: AiReviewFindingCategory }> = [
    ...asArray(review.missingPoints).map((value) => ({ value, category: 'coverage_gap' as const })),
    ...asArray(review.missingCases).map((value) => ({ value, category: 'coverage_gap' as const })),
    ...asArray(review.supplementSuggestions).map((value) => ({ value, category: 'suggestion' as const })),
    ...asArray(review.suggestions).map((value) => ({ value, category: 'suggestion' as const })),
    ...asArray(review.improvements).map((value) => ({ value, category: 'suggestion' as const }))
  ]
  return {
    stage: firstText(outputSnapshot?.reviewStage, review.reviewStage),
    summary,
    suggestions: findingValues
      .map(({ value, category }) => toAiReviewSuggestionView(value, category))
      .filter((item) => item.title || item.detail)
  }
}

const toAiReviewSuggestionView = (
  value: unknown,
  category: AiReviewFindingCategory
): AiReviewSuggestionView => {
  const record = asRecord(value)
  const title = typeof value === 'string'
    ? value.trim()
    : firstText(record?.title, record?.name, record?.point, record?.caseTitle, record?.description)
  const detail = record
    ? firstText(record.reason, record.suggestion, record.detail, record.expectedSupplement, record.expectedResult)
    : ''
  const priority = record ? firstText(record.priority, record.level, record.riskLevel) : ''
  const suppliedId = record ? firstText(record.findingId, record.suggestionId, record.id) : ''
  return {
    findingId: stableReviewFindingId(category, title, detail, priority, suppliedId),
    category,
    title,
    detail,
    priority
  }
}

const stableReviewFindingId = (
  category: AiReviewFindingCategory,
  title: string,
  detail: string,
  priority: string,
  suppliedId: string
): string => {
  const normalizedSuppliedId = suppliedId.trim()
  if (normalizedSuppliedId.startsWith(`${category}:`)) return normalizedSuppliedId
  if (normalizedSuppliedId) return `${category}:${encodeURIComponent(normalizedSuppliedId)}`
  return `${category}:${encodeURIComponent(
    [title, detail, priority]
      .map((item) => item.trim().toLowerCase())
      .join('\u001f')
  )}`
}
```

- [ ] **Step 4: Write failing review-summary tests**

Create `apps/web/src/domain/caseDesignReviewView.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { buildCaseDesignReviewView } from './caseDesignReviewView'

describe('caseDesignReviewView', () => {
  it('prioritizes pending coverage gaps and keeps finding decisions independent', () => {
    expect(buildCaseDesignReviewView({
      selectedCase: {
        id: 'TC-003',
        title: '退款处理超时后的状态回补',
        module: '退款',
        sourceFile: '退款需求.md'
      },
      qualityScores: [
        { qualityDimension: 'coverage', score: 0.92, scoreLevel: 'good' },
        { qualityDimension: 'testability', score: 0.74, scoreLevel: 'warning' }
      ],
      conflicts: [
        { conflictId: 'c1', conflictType: 'timeout', targetKey: 'TC-003', resolutionStatus: 'unresolved' },
        { conflictId: 'c2', conflictType: 'priority', targetKey: 'TC-004', resolutionStatus: 'resolved' }
      ],
      findings: [
        {
          findingId: 'coverage_gap:gap-refund-timeout',
          category: 'coverage_gap',
          title: '退款超时路径缺少异常回补用例',
          detail: '覆盖退款状态最终一致性',
          priority: 'P0'
        },
        {
          findingId: 'suggestion:suggestion-consistency',
          category: 'suggestion',
          title: '补充最终一致性检查',
          detail: '校验退款单状态',
          priority: 'P1'
        },
        {
          findingId: 'suggestion:suggestion-login',
          category: 'suggestion',
          title: '补充登录检查',
          detail: '校验登录态',
          priority: 'P2'
        }
      ],
      findingDecisions: {
        'TC-003': { 'suggestion:suggestion-consistency': 'accepted' }
      }
    })).toEqual({
      selectedCaseId: 'TC-003',
      selectedCaseTitle: '退款处理超时后的状态回补',
      selectedSourceFile: '退款需求.md',
      averageScore: 83,
      unresolvedConflictCount: 1,
      findingCount: 2,
      pendingFindingCount: 1,
      highRiskCount: 2,
      findings: [
        {
          findingId: 'coverage_gap:gap-refund-timeout',
          category: 'coverage_gap',
          title: '退款超时路径缺少异常回补用例',
          detail: '覆盖退款状态最终一致性',
          priority: 'P0',
          decision: 'pending'
        },
        {
          findingId: 'suggestion:suggestion-consistency',
          category: 'suggestion',
          title: '补充最终一致性检查',
          detail: '校验退款单状态',
          priority: 'P1',
          decision: 'accepted'
        }
      ],
      relevantConflictIds: ['c1'],
      relevantFindingIds: [
        'coverage_gap:gap-refund-timeout',
        'suggestion:suggestion-consistency'
      ],
      hasContent: true
    })
  })

  it('returns an empty inspector state without findings', () => {
    expect(buildCaseDesignReviewView({
      selectedCase: undefined,
      qualityScores: [],
      conflicts: [],
      findings: [],
      findingDecisions: {}
    }).hasContent).toBe(false)
  })
})
```

- [ ] **Step 5: Run the review-summary test to verify it fails**

Run:

```bash
npm test -- --run src/domain/caseDesignReviewView.test.ts
```

Expected: FAIL because `caseDesignReviewView.ts` does not exist.

- [ ] **Step 6: Implement the review summary**

Create `apps/web/src/domain/caseDesignReviewView.ts`:

```ts
import type {
  AgentConflictView,
  AiReviewSuggestionView,
  QualityScoreView
} from './aiMultiAgentRunView'
import type {
  CaseReviewDecision,
  CaseReviewFindingDecisions
} from './caseDesignDraftRepository'

export interface CaseDesignReviewViewInput {
  selectedCase?: {
    id: string
    title: string
    module: string
    sourceFile: string
  }
  qualityScores: QualityScoreView[]
  conflicts: AgentConflictView[]
  findings: AiReviewSuggestionView[]
  findingDecisions: CaseReviewFindingDecisions
}

export interface CaseDesignReviewFindingView extends AiReviewSuggestionView {
  decision: CaseReviewDecision
}

export interface CaseDesignReviewView {
  selectedCaseId: string
  selectedCaseTitle: string
  selectedSourceFile: string
  averageScore: number
  unresolvedConflictCount: number
  findingCount: number
  pendingFindingCount: number
  highRiskCount: number
  findings: CaseDesignReviewFindingView[]
  relevantConflictIds: string[]
  relevantFindingIds: string[]
  hasContent: boolean
}

export const buildCaseDesignReviewView = (
  input: CaseDesignReviewViewInput
): CaseDesignReviewView => {
  const selected = input.selectedCase
  const searchTerms = selected
    ? [selected.id, selected.title, selected.module, selected.sourceFile]
      .map((value) => value.trim().toLowerCase())
      .filter(Boolean)
    : []
  const relevantConflicts = selected
    ? input.conflicts.filter((conflict) => searchTerms.some((term) => conflict.targetKey.toLowerCase().includes(term)))
    : input.conflicts
  const relevantFindings = selected
    ? input.findings.filter((finding) => {
      if (finding.category === 'coverage_gap') return true
      const text = `${finding.title} ${finding.detail}`.toLowerCase()
      return searchTerms.some((term) => text.includes(term))
    })
    : input.findings
  const sortedConflicts = [...relevantConflicts].sort((left, right) =>
    Number(left.resolutionStatus !== 'unresolved') - Number(right.resolutionStatus !== 'unresolved')
  )
  const decisionsForCase = selected
    ? input.findingDecisions[selected.id] || {}
    : {}
  const sortedFindings = [...relevantFindings]
    .map((finding): CaseDesignReviewFindingView => ({
      ...finding,
      decision: decisionsForCase[finding.findingId] || 'pending'
    }))
    .sort((left, right) =>
      Number(left.decision !== 'pending') - Number(right.decision !== 'pending')
      || Number(left.category !== 'coverage_gap') - Number(right.category !== 'coverage_gap')
      || riskRank(left.priority) - riskRank(right.priority)
      || left.findingId.localeCompare(right.findingId)
    )
  const unresolvedConflictCount = sortedConflicts.filter(
    (conflict) => conflict.resolutionStatus === 'unresolved'
  ).length
  const pendingFindingCount = sortedFindings.filter(
    (finding) => finding.decision === 'pending'
  ).length

  return {
    selectedCaseId: selected?.id || '',
    selectedCaseTitle: selected?.title || '',
    selectedSourceFile: selected?.sourceFile || '',
    averageScore: input.qualityScores.length > 0
    ? Math.round((input.qualityScores.reduce((total, score) => total + score.score, 0) / input.qualityScores.length) * 100)
    : 0,
    unresolvedConflictCount,
    findingCount: sortedFindings.length,
    pendingFindingCount,
    highRiskCount: unresolvedConflictCount
      + sortedFindings.filter(
        (finding) => finding.decision === 'pending' && riskRank(finding.priority) <= 1
      ).length,
    findings: sortedFindings,
    relevantConflictIds: sortedConflicts.map((conflict) => conflict.conflictId),
    relevantFindingIds: sortedFindings.map((finding) => finding.findingId),
    hasContent: input.qualityScores.length > 0
      || sortedConflicts.length > 0
      || sortedFindings.length > 0
  }
}

const riskRank = (priority: string): number => {
  if (priority === 'P0') return 0
  if (priority === 'P1') return 1
  if (priority === 'P2') return 2
  return 3
}
```

- [ ] **Step 7: Write failing inspector component tests**

Create `apps/web/src/components/workflow/AiReviewInspector.test.ts`:

```ts
// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AiReviewInspector from './AiReviewInspector.vue'

const findings = [
  {
    findingId: 'coverage_gap:gap-refund-timeout',
    category: 'coverage_gap' as const,
    title: '退款超时路径缺少异常回补用例',
    detail: '覆盖退款状态最终一致性',
    priority: 'P0',
    decision: 'pending' as const
  },
  {
    findingId: 'suggestion:suggestion-consistency',
    category: 'suggestion' as const,
    title: '补充最终一致性检查',
    detail: '校验订单和退款单状态',
    priority: 'P1',
    decision: 'accepted' as const
  }
]

describe('AiReviewInspector', () => {
  it('shows the selected case and contextual review content', () => {
    const wrapper = mount(AiReviewInspector, {
      props: {
        selectedCaseTitle: '退款处理超时后的状态回补',
        selectedSourceFile: '退款需求.md',
        selectedCount: 1,
        highRiskCount: 2,
        pendingFindingCount: 1,
        averageScore: 92,
        summary: '存在 2 项待确认',
        findings,
        conflicts: [{ conflictId: 'c1', conflictType: 'timeout', targetKey: 'TC-003', resolutionStatus: 'unresolved' }],
        loading: false
      }
    })

    expect(wrapper.text()).toContain('退款处理超时后的状态回补')
    expect(wrapper.text()).toContain('92%')
    expect(wrapper.text()).toContain('退款超时路径缺少异常回补用例')
    expect(wrapper.text()).toContain('补充最终一致性检查')
  })

  it('emits refresh, ignore, and merge actions', async () => {
    const wrapper = mount(AiReviewInspector, {
      props: {
        selectedCaseTitle: '',
        selectedSourceFile: '',
        selectedCount: 2,
        highRiskCount: 1,
        pendingFindingCount: 0,
        averageScore: 0,
        summary: '',
        findings: [],
        conflicts: [{ conflictId: 'c1', conflictType: 'timeout', targetKey: 'TC-003', resolutionStatus: 'unresolved' }],
        loading: false
      }
    })

    await wrapper.get('[data-testid="refresh-review"]').trigger('click')
    await wrapper.get('[data-testid="ignore-conflicts"]').trigger('click')
    await wrapper.get('[data-testid="merge-review"]').trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    expect(wrapper.emitted('ignoreConflicts')).toHaveLength(1)
    expect(wrapper.emitted('merge')).toHaveLength(1)
  })

  it('emits source, per-finding, and bulk review decisions', async () => {
    const wrapper = mount(AiReviewInspector, {
      props: {
        selectedCaseTitle: '退款状态回补',
        selectedSourceFile: '退款需求.md',
        selectedCount: 2,
        highRiskCount: 1,
        pendingFindingCount: 1,
        averageScore: 88,
        summary: '1 项待确认',
        findings,
        conflicts: [{ conflictId: 'c1', conflictType: 'timeout', targetKey: 'TC-003', resolutionStatus: 'unresolved' }],
        loading: false
      }
    })

    await wrapper.get('[data-testid="locate-source"]').trigger('click')
    await wrapper.get('[data-testid="accept-finding-coverage_gap:gap-refund-timeout"]').trigger('click')
    await wrapper.get('[data-testid="ignore-conflict-c1"]').trigger('click')
    await wrapper.get('[data-testid="bulk-pending"]').trigger('click')
    expect(wrapper.emitted('locateSource')).toEqual([['退款需求.md']])
    expect(wrapper.emitted('decide')).toEqual([['coverage_gap:gap-refund-timeout', 'accepted']])
    expect(wrapper.emitted('ignoreConflict')).toEqual([['c1']])
    expect(wrapper.emitted('bulkDecide')).toEqual([['pending']])
  })
})
```

- [ ] **Step 8: Run the inspector test to verify it fails**

Run:

```bash
npm test -- --run src/components/workflow/AiReviewInspector.test.ts
```

Expected: FAIL because `AiReviewInspector.vue` does not exist.

- [ ] **Step 9: Implement the contextual inspector**

Create `apps/web/src/components/workflow/AiReviewInspector.vue`:

```vue
<script setup lang="ts">
import { RefreshCcw, Sparkles } from 'lucide-vue-next'
import type { AgentConflictView } from '../../domain/aiMultiAgentRunView'
import type { CaseReviewDecision } from '../../domain/caseDesignDraftRepository'
import type { CaseDesignReviewFindingView } from '../../domain/caseDesignReviewView'

defineProps<{
  selectedCaseTitle: string
  selectedSourceFile: string
  selectedCount: number
  highRiskCount: number
  pendingFindingCount: number
  averageScore: number
  summary: string
  findings: CaseDesignReviewFindingView[]
  conflicts: AgentConflictView[]
  loading: boolean
}>()

const emit = defineEmits<{
  refresh: []
  ignoreConflicts: []
  ignoreConflict: [conflictId: string]
  merge: []
  locateSource: [sourceFile: string]
  decide: [findingId: string, decision: CaseReviewDecision]
  bulkDecide: [decision: CaseReviewDecision]
}>()

const decisionLabel = (decision: CaseReviewDecision): string => ({
  pending: '待确认',
  accepted: '已采纳',
  modified: '已修改',
  ignored: '已忽略'
}[decision])
</script>

<template>
  <aside class="studio-review-inspector" aria-label="AI 质量评审">
    <header class="studio-review-inspector__header">
      <div><Sparkles :size="16" /><strong>AI 质量评审</strong></div>
      <button
        type="button"
        class="studio-icon-button"
        data-testid="refresh-review"
        aria-label="刷新 AI 评审"
        title="刷新 AI 评审"
        :disabled="loading"
        @click="emit('refresh')"
      >
        <RefreshCcw :size="15" />
      </button>
    </header>

    <div class="studio-review-inspector__selection">
      <span>当前用例</span>
      <strong>{{ selectedCaseTitle || '请选择一条用例' }}</strong>
      <el-button
        v-if="selectedSourceFile"
        text
        type="primary"
        data-testid="locate-source"
        @click="emit('locateSource', selectedSourceFile)"
      >
        定位来源 · {{ selectedSourceFile }}
      </el-button>
    </div>

    <div class="studio-review-inspector__score">
      <span>整体质量</span>
      <strong>{{ averageScore }}%</strong>
      <small>{{ highRiskCount }} 项高风险或待处理发现</small>
      <small>{{ pendingFindingCount }} 项评审内容待确认</small>
      <p v-if="summary">{{ summary }}</p>
    </div>

    <section>
      <header><strong>待处理冲突</strong><span>{{ conflicts.filter((item) => item.resolutionStatus === 'unresolved').length }}</span></header>
      <article v-for="conflict in conflicts" :key="conflict.conflictId">
        <strong>{{ conflict.conflictType }}</strong>
        <p>{{ conflict.targetKey }}</p>
        <el-tag size="small" :type="conflict.resolutionStatus === 'unresolved' ? 'warning' : 'success'">
          {{ conflict.resolutionStatus }}
        </el-tag>
        <el-button
          v-if="conflict.resolutionStatus === 'unresolved'"
          link
          :data-testid="`ignore-conflict-${conflict.conflictId}`"
          @click="emit('ignoreConflict', conflict.conflictId)"
        >
          忽略此冲突
        </el-button>
      </article>
    </section>

    <section>
      <header><strong>覆盖缺口与改进建议</strong><span>{{ findings.length }}</span></header>
      <article v-for="finding in findings" :key="finding.findingId">
        <div class="studio-review-inspector__finding-meta">
          <el-tag size="small" :type="finding.category === 'coverage_gap' ? 'danger' : 'info'" effect="plain">
            {{ finding.category === 'coverage_gap' ? '覆盖缺口' : '改进建议' }}
          </el-tag>
          <el-tag v-if="finding.priority" size="small" effect="plain">{{ finding.priority }}</el-tag>
          <span>{{ decisionLabel(finding.decision) }}</span>
        </div>
        <strong>{{ finding.title }}</strong>
        <p>{{ finding.detail }}</p>
        <div class="studio-review-inspector__finding-actions">
          <el-button
            link
            :data-testid="`accept-finding-${finding.findingId}`"
            @click="emit('decide', finding.findingId, 'accepted')"
          >
            采纳
          </el-button>
          <el-button link @click="emit('decide', finding.findingId, 'modified')">修改</el-button>
          <el-button link @click="emit('decide', finding.findingId, 'pending')">待确认</el-button>
          <el-button link @click="emit('decide', finding.findingId, 'ignored')">忽略</el-button>
        </div>
      </article>
      <el-empty v-if="findings.length === 0" description="当前没有评审内容" :image-size="42" />
    </section>

    <footer>
      <el-button
        v-if="selectedCount > 1"
        text
        data-testid="bulk-pending"
        @click="emit('bulkDecide', 'pending')"
      >
        批量标记待确认（{{ selectedCount }}）
      </el-button>
      <el-button v-if="selectedCount > 1" text @click="emit('bulkDecide', 'accepted')">
        批量采纳
      </el-button>
      <el-button
        text
        data-testid="ignore-conflicts"
        :disabled="!conflicts.some((item) => item.resolutionStatus === 'unresolved')"
        @click="emit('ignoreConflicts')"
      >
        忽略未处理冲突
      </el-button>
      <el-button type="primary" plain data-testid="merge-review" @click="emit('merge')">对齐补充</el-button>
    </footer>
  </aside>
</template>
```

- [ ] **Step 10: Write failing generation/review context-pane tests**

Create `apps/web/src/components/workflow/CaseDesignContextPane.test.ts`:

```ts
// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import CaseDesignContextPane from './CaseDesignContextPane.vue'

describe('CaseDesignContextPane', () => {
  it('renders source status and emits module selection', async () => {
    const wrapper = mount(CaseDesignContextPane, {
      props: {
        sources: [{ name: '退款需求.md', kind: 'document', kindLabel: '文档', status: 'ready', detail: '已就绪' }],
        modules: [{ value: '退款', count: 8 }, { value: '订单', count: 4 }],
        activeModule: '退款',
        activeSource: '退款需求.md'
      }
    })

    expect(wrapper.text()).toContain('退款需求.md')
    await wrapper.get('button[data-source="退款需求.md"]').trigger('click')
    await wrapper.get('button[data-module="订单"]').trigger('click')
    expect(wrapper.emitted('selectSource')).toEqual([['退款需求.md']])
    expect(wrapper.emitted('selectModule')).toEqual([['订单']])
  })
})
```

- [ ] **Step 11: Run the context-pane test to verify it fails**

Run:

```bash
npm test -- --run src/components/workflow/CaseDesignContextPane.test.ts
```

Expected: FAIL because `CaseDesignContextPane.vue` does not exist.

- [ ] **Step 12: Implement the generation/review context pane**

Create `apps/web/src/components/workflow/CaseDesignContextPane.vue`:

```vue
<script setup lang="ts">
import type { CaseDesignSourceView } from '../../domain/caseDesignSourceView'

defineProps<{
  sources: CaseDesignSourceView[]
  modules: Array<{ value: string; count: number }>
  activeModule: string
  activeSource: string
}>()

const emit = defineEmits<{
  selectSource: [source: string]
  selectModule: [module: string]
}>()
</script>

<template>
  <aside class="studio-case-context" aria-label="需求与模块上下文">
    <section>
      <header><strong>需求来源</strong><span>{{ sources.length }}</span></header>
      <div class="studio-case-context__sources">
        <button
          v-for="source in sources"
          :key="source.name"
          type="button"
          :data-source="source.name"
          :class="[`is-${source.status}`, { 'is-active': activeSource === source.name }]"
          :aria-pressed="activeSource === source.name"
          @click="emit('selectSource', source.name)"
        >
          <span>{{ source.kindLabel }}</span>
          <div><strong>{{ source.name }}</strong><small>{{ source.detail }}</small></div>
        </button>
      </div>
    </section>

    <section>
      <header><strong>模块</strong><span>{{ modules.length }}</span></header>
      <button
        type="button"
        data-module="ALL"
        :class="{ 'is-active': activeModule === 'ALL' }"
        :aria-pressed="activeModule === 'ALL'"
        @click="emit('selectModule', 'ALL')"
      >
        <span>全部模块</span>
        <b>{{ modules.reduce((total, module) => total + module.count, 0) }}</b>
      </button>
      <button
        v-for="module in modules"
        :key="module.value"
        type="button"
        :data-module="module.value"
        :class="{ 'is-active': activeModule === module.value }"
        :aria-pressed="activeModule === module.value"
        @click="emit('selectModule', module.value)"
      >
        <span>{{ module.value }}</span>
        <b>{{ module.count }}</b>
      </button>
    </section>
  </aside>
</template>
```

- [ ] **Step 13: Integrate the context pane and inspector**

In `apps/web/src/App.vue`, import:

```ts
import AiReviewInspector from './components/workflow/AiReviewInspector.vue'
import CaseDesignContextPane from './components/workflow/CaseDesignContextPane.vue'
import { buildCaseDesignReviewView } from './domain/caseDesignReviewView'
import type { AiReviewSuggestionView } from './domain/aiMultiAgentRunView'
```

Add:

```ts
const selectedReviewCase = computed(() => {
  const testCase = selectedCase.value
  if (!testCase || !('pageName' in testCase.row)) return undefined
  return {
    id: testCase.row.caseId,
    title: testCase.row.title,
    module: testCase.row.module,
    sourceFile: testCase.row.sourceFile
  }
})
const reviewPriorityForSeverity = (severity: string): string => {
  if (severity === 'critical') return 'P0'
  if (severity === 'high') return 'P1'
  return 'P2'
}
const caseDesignReviewFindings = computed<AiReviewSuggestionView[]>(() => {
  const findings: AiReviewSuggestionView[] = [
    ...activeAiReviewInsight.value.suggestions,
    ...aiCoverageView.value.warningRows.map((row) => ({
      findingId: `coverage_gap:coverage-warning:${row.key}`,
      category: 'coverage_gap' as const,
      title: row.section,
      detail: row.message,
      priority: 'P1'
    })),
    ...aiCoverageView.value.unclearQuestions.map((question) => ({
      findingId: `coverage_gap:coverage-question:${question.questionId}`,
      category: 'coverage_gap' as const,
      title: question.questionText,
      detail: question.sourceRef || '',
      priority: reviewPriorityForSeverity(question.severity)
    }))
  ]
  return [...new Map(findings.map((finding) => [finding.findingId, finding])).values()]
})
const caseDesignReviewView = computed(() => buildCaseDesignReviewView({
  selectedCase: selectedReviewCase.value,
  qualityScores: aiMultiAgentQualityScores.value,
  conflicts: aiMultiAgentConflicts.value,
  findings: caseDesignReviewFindings.value,
  findingDecisions: caseReviewFindingDecisions.value
}))
const relevantConflictIdSet = computed(() => new Set(caseDesignReviewView.value.relevantConflictIds))
const caseDesignRelevantConflicts = computed(() => aiMultiAgentConflicts.value.filter(
  (conflict) => relevantConflictIdSet.value.has(conflict.conflictId)
))
const activeCaseDesignSource = ref(selectedReviewCase.value?.sourceFile || '')
watch(selectedReviewCase, (testCase) => {
  if (testCase?.sourceFile) activeCaseDesignSource.value = testCase.sourceFile
})
const caseDesignActiveModule = computed(() =>
  caseDesignWorkflow.value.activeStage === 'generate'
    ? aiDraftFilter.module
    : webPreviewFilter.module
)
const selectedReviewCaseCount = computed(() =>
  selectedGeneratedCaseIds.value.length || (selectedReviewCase.value ? 1 : 0)
)
const shellInspectorVisible = computed(() =>
  activeNav.value === 'api'
  && generationKind.value === 'web_automation'
  && caseDesignWorkflow.value.activeStage === 'review'
)
const caseDesignContextVisible = computed(() =>
  activeNav.value === 'api'
  && generationKind.value === 'web_automation'
  && ['generate', 'review'].includes(caseDesignWorkflow.value.activeStage)
)

const selectCaseDesignModule = (module: string) => {
  if (caseDesignWorkflow.value.activeStage === 'generate') {
    aiDraftFilter.module = module
    return
  }
  webPreviewFilter.module = module
}

const selectCaseDesignSource = (sourceFile: string) => {
  activeCaseDesignSource.value = sourceFile
  if (caseDesignWorkflow.value.activeStage === 'generate') {
    const sourceOption = aiDraftPointView.value.sourceOptions.find((option) => option.label === sourceFile)
    if (sourceOption) aiDraftFilter.sourceDocumentId = sourceOption.value
    return
  }
  webPreviewFilter.keyword = sourceFile
}

const decideSelectedReviewFinding = (
  findingId: string,
  decision: CaseReviewDecision
) => {
  const testCase = selectedReviewCase.value
  if (!testCase) return
  caseReviewFindingDecisions.value = {
    ...caseReviewFindingDecisions.value,
    [testCase.id]: {
      ...(caseReviewFindingDecisions.value[testCase.id] || {}),
      [findingId]: decision
    }
  }
  if (decision === 'modified' && selectedCase.value && 'pageName' in selectedCase.value.row) {
    openWebEdit(selectedCase.value as GeneratedWebAutomationCase)
  }
}

const bulkDecideReviewCases = (decision: CaseReviewDecision) => {
  const selectedIds = new Set(
    selectedGeneratedCaseIds.value.length > 0
      ? selectedGeneratedCaseIds.value
      : selectedCase.value
        ? [selectedCase.value.id]
        : []
  )
  const next: CaseReviewFindingDecisions = Object.fromEntries(
    Object.entries(caseReviewFindingDecisions.value).map(([caseId, decisions]) => [
      caseId,
      { ...decisions }
    ])
  )

  generatedWebCases.value
    .filter((testCase) => selectedIds.has(testCase.id))
    .forEach((testCase) => {
      const caseId = testCase.row.caseId
      const view = buildCaseDesignReviewView({
        selectedCase: {
          id: caseId,
          title: testCase.row.title,
          module: testCase.row.module,
          sourceFile: testCase.row.sourceFile
        },
        qualityScores: aiMultiAgentQualityScores.value,
        conflicts: aiMultiAgentConflicts.value,
        findings: caseDesignReviewFindings.value,
        findingDecisions: next
      })
      const caseDecisions = { ...(next[caseId] || {}) }
      view.findings.forEach((finding) => {
        caseDecisions[finding.findingId] = decision
      })
      next[caseId] = caseDecisions
    })

  caseReviewFindingDecisions.value = next
}

const ignoreSingleReviewConflict = async (conflictId: string) => {
  try {
    await ignoreAiMultiAgentConflict(conflictId, '前端评审选择忽略该冲突')
    if (caseAiReviewRun.value) await loadMultiAgentReviewDetails(caseAiReviewRun.value.runId)
  } catch (error) {
    caseReviewError.value = error instanceof Error ? error.message : '处理评审冲突失败'
    ElMessage.error(caseReviewError.value)
  }
}
```

In the existing `saveEdit` Web-case branch, keep the saved-library branch unchanged. Replace the non-library tail after that branch with:

```ts
invalidateCaseReview()
editVisible.value = false
ElMessage.success('已更新当前 Web 用例')
return
```

This ensures appending a generated batch, deleting a generated case through `removeGeneratedWebCase`, or materially editing a generated case invalidates the previous case-review run and relocks delivery. Locator confirmation alone does not invalidate review because it changes executability metadata rather than the reviewed scenario or assertions.

Append these lines to the existing `resetAiReviewState` function so a new source scope cannot inherit review decisions from the previous task:

```ts
caseReviewAttempted.value = false
caseReviewFindingDecisions.value = {}
restoredAiReviewInsight.value = undefined
caseReviewError.value = ''
```

In the existing `startCaseAiReview`, after its generated-case guard and before `await startAiReview('case')`, record the attempt even when the backend request later fails:

```ts
caseReviewAttempted.value = true
```

Replace the `shellContextVisible` computed introduced in Task 6 with this definition; do not add a second `const`:

```ts
const shellContextVisible = computed(() =>
  activeNav.value !== 'api'
  || generationKind.value === 'interface'
  || caseDesignContextVisible.value
)
```

At the beginning of the existing `#context` slot, render the case-design context before the legacy resource pane:

```vue
<CaseDesignContextPane
  v-if="caseDesignContextVisible"
  :sources="caseDesignSourceView"
  :modules="aiDraftPointView.moduleOptions"
  :active-module="caseDesignActiveModule"
  :active-source="activeCaseDesignSource"
  @select-source="selectCaseDesignSource"
  @select-module="selectCaseDesignModule"
/>
```

Add `v-else` to the existing single `<aside class="resource-pane">` that immediately follows this component. Do not create a second resource pane.

Pass `:inspector-visible="shellInspectorVisible"` to `AppShell`. Add the inspector slot:

```vue
<template #inspector>
  <AiReviewInspector
    :selected-case-title="caseDesignReviewView.selectedCaseTitle"
    :selected-source-file="caseDesignReviewView.selectedSourceFile"
    :selected-count="selectedReviewCaseCount"
    :high-risk-count="caseDesignReviewView.highRiskCount"
    :pending-finding-count="caseDesignReviewView.pendingFindingCount"
    :average-score="caseDesignReviewView.averageScore"
    :summary="activeAiReviewInsight.summary"
    :findings="caseDesignReviewView.findings"
    :conflicts="caseDesignRelevantConflicts"
    :loading="aiMultiAgentLoading"
    @refresh="refreshMultiAgentReview"
    @ignore-conflicts="ignoreUnresolvedMultiAgentConflicts"
    @ignore-conflict="ignoreSingleReviewConflict"
    @merge="mergeMultiAgentReview"
    @locate-source="selectCaseDesignSource"
    @decide="decideSelectedReviewFinding"
    @bulk-decide="bulkDecideReviewCases"
  />
</template>
```

After `await loadMultiAgentReviewDetails(run.runId)` in `startAiReview`, replace the dialog-opening and unconditional success message with terminal-status-aware navigation:

```ts
restoredAiReviewInsight.value = undefined
activeCaseDesignStage.value = 'review'
const effectiveRun = stage === 'case' ? caseAiReviewRun.value || run : run
const effectiveStatus = reviewRunStatusToWorkflowStatus(effectiveRun.status)
if (stage === 'case' && effectiveStatus === 'failed') {
  caseReviewError.value = effectiveRun.errorSummary || 'AI 质量评审未完成'
  ElMessage.warning(caseReviewError.value)
} else if (stage === 'case' && effectiveStatus === 'running') {
  caseReviewError.value = ''
  ElMessage.info('用例阶段 AI 评审已启动')
} else {
  if (stage === 'case') caseReviewError.value = ''
  ElMessage.success(`${stage === 'case' ? '用例阶段' : '需求阶段'}AI评审已完成`)
}
```

In its catch block:

```ts
const message = error instanceof Error ? error.message : 'AI评审失败'
if (stage === 'case') caseReviewError.value = message
ElMessage.error(message)
```

Delete the complete `el-dialog` block with `v-model="aiReviewDialogVisible"`. Remove `aiReviewDialogVisible` and assignments that only open or close that dialog. Preserve the underlying review run, refresh, conflict, and merge actions.

Render `GeneratedWebCaseTable` as the main canvas when `caseDesignWorkflow.activeStage === 'review'`; keep its `@select="selectedCase = $event"` and `@selection-change="updateGeneratedCaseSelection"` bindings so row activation and bulk checkboxes update the inspector and its decision target set.

- [ ] **Step 14: Update obsolete structure assertions**

In `apps/web/src/appShellStructure.test.ts`, replace the test that requires `ai-review-dialog` with:

```ts
it('renders AI review in the contextual inspector instead of a dialog', () => {
  expect(appSource).toContain("import AiReviewInspector from './components/workflow/AiReviewInspector.vue'")
  expect(appSource).toContain('<template #inspector>')
  expect(appSource).toContain('<AiReviewInspector')
  expect(appSource).not.toContain('class="ai-review-dialog"')
  expect(appSource).not.toContain('v-model="aiReviewDialogVisible"')
})

it('invalidates case review whenever generated review artifacts change', () => {
  expect(appSource).toContain('const invalidateCaseReview = () => {')
  expect(appSource).toContain('if (generated.cases.length > 0) invalidateCaseReview()')
  expect(appSource).toContain('const removeGeneratedWebCase = (testCase: GeneratedWebAutomationCase) => {')
  expect(appSource.match(/@remove="removeGeneratedWebCase"/g)).toHaveLength(2)
  expect(appSource).toContain("invalidateCaseReview()\n    editVisible.value = false\n    ElMessage.success('已更新当前 Web 用例')")
  expect(appSource).toContain("if (activeCaseDesignStage.value === 'deliver') activeCaseDesignStage.value = 'review'")
})
```

- [ ] **Step 15: Run review, context, structure, and build checks**

Run:

```bash
npm test -- --run src/domain/aiMultiAgentRunView.test.ts src/domain/caseDesignDraftRepository.test.ts src/domain/caseDesignReviewView.test.ts src/components/workflow/AiReviewInspector.test.ts src/components/workflow/CaseDesignContextPane.test.ts src/appShellStructure.test.ts
npm run build
```

Expected: tests PASS; selecting a Web case updates the inline inspector and no review dialog remains.

- [ ] **Step 16: Commit contextual review**

```bash
git add apps/web/src/domain/aiMultiAgentRunView.ts apps/web/src/domain/aiMultiAgentRunView.test.ts apps/web/src/domain/caseDesignDraftRepository.ts apps/web/src/domain/caseDesignDraftRepository.test.ts apps/web/src/domain/caseDesignReviewView.ts apps/web/src/domain/caseDesignReviewView.test.ts apps/web/src/components/workflow/AiReviewInspector.vue apps/web/src/components/workflow/AiReviewInspector.test.ts apps/web/src/components/workflow/CaseDesignContextPane.vue apps/web/src/components/workflow/CaseDesignContextPane.test.ts apps/web/src/App.vue apps/web/src/appShellStructure.test.ts
git commit -m "feat: move AI review into contextual inspector"
```

## Task 9: Add Save-First Delivery And Optional Exact-Set Execution

**Files:**
- Create: `apps/web/src/domain/caseDesignDelivery.test.ts`
- Create: `apps/web/src/domain/caseDesignDelivery.ts`
- Create: `apps/web/src/components/workflow/CaseDeliveryStage.test.ts`
- Create: `apps/web/src/components/workflow/CaseDeliveryStage.vue`
- Modify: `apps/web/src/App.vue:2519-2670`
- Modify: `apps/web/src/App.vue:3165-3245`
- Modify: `apps/web/src/App.vue:3904-3925`
- Modify: `apps/web/src/appShellStructure.test.ts`

- [ ] **Step 1: Write failing delivery-preflight tests**

Create `apps/web/src/domain/caseDesignDelivery.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { buildCaseDesignDeliveryView } from './caseDesignDelivery'

describe('caseDesignDelivery', () => {
  it('allows save-only delivery without an execution environment', () => {
    expect(buildCaseDesignDeliveryView({
      mode: 'library',
      executeNow: false,
      generatedCount: 12,
      selectedAutomationCount: 0,
      executabilityIssues: [],
      reviewAttempted: true,
      reviewStatus: 'succeeded',
      reviewError: '',
      reviewRisks: [
        { id: 'conflict:c1', kind: 'conflict', priority: 'P0', message: '未处理冲突 · timeout · WEB-AI-0001' },
        { id: 'gap:g1', kind: 'coverage_gap', priority: 'P1', message: '覆盖缺口 · 退款超时异常路径' }
      ],
      environmentValid: false,
      environmentMessage: '请先选择运行环境'
    })).toEqual({
      canSubmit: true,
      primaryLabel: '保存到用例库',
      blockers: [],
      warnings: ['仍有 2 项评审风险'],
      riskItems: [
        { id: 'conflict:c1', kind: 'conflict', priority: 'P0', message: '未处理冲突 · timeout · WEB-AI-0001' },
        { id: 'gap:g1', kind: 'coverage_gap', priority: 'P1', message: '覆盖缺口 · 退款超时异常路径' }
      ]
    })
  })

  it('requires a valid environment only when immediate execution is selected', () => {
    expect(buildCaseDesignDeliveryView({
      mode: 'automation',
      executeNow: true,
      generatedCount: 12,
      selectedAutomationCount: 4,
      executabilityIssues: [],
      reviewAttempted: true,
      reviewStatus: 'succeeded',
      reviewError: '',
      reviewRisks: [],
      environmentValid: false,
      environmentMessage: 'Web 运行环境的应用 Base URL 不能为空'
    })).toEqual({
      canSubmit: false,
      primaryLabel: '保存并执行',
      blockers: ['Web 运行环境的应用 Base URL 不能为空'],
      warnings: [],
      riskItems: []
    })
  })

  it('blocks every delivery mode when there are no generated cases', () => {
    expect(buildCaseDesignDeliveryView({
      mode: 'automation',
      executeNow: false,
      generatedCount: 0,
      selectedAutomationCount: 0,
      executabilityIssues: [],
      reviewAttempted: true,
      reviewStatus: 'succeeded',
      reviewError: '',
      reviewRisks: [],
      environmentValid: true,
      environmentMessage: ''
    }).blockers).toEqual(['没有可交付的生成用例'])
  })

  it('blocks automation conversion when selected cases are not executable', () => {
    expect(buildCaseDesignDeliveryView({
      mode: 'automation',
      executeNow: false,
      generatedCount: 12,
      selectedAutomationCount: 2,
      executabilityIssues: ['WEB-AI-0003：步骤 2 使用 manual，不能自动执行'],
      reviewAttempted: true,
      reviewStatus: 'succeeded',
      reviewError: '',
      reviewRisks: [],
      environmentValid: true,
      environmentMessage: ''
    })).toMatchObject({
      canSubmit: false,
      blockers: ['WEB-AI-0003：步骤 2 使用 manual，不能自动执行']
    })
  })

  it('blocks delivery before review has been attempted', () => {
    expect(buildCaseDesignDeliveryView({
      mode: 'library',
      executeNow: false,
      generatedCount: 2,
      selectedAutomationCount: 0,
      executabilityIssues: [],
      reviewAttempted: false,
      reviewStatus: 'idle',
      reviewError: '',
      reviewRisks: [],
      environmentValid: false,
      environmentMessage: ''
    }).blockers).toContain('请先至少尝试一次 AI 质量评审')
  })

  it('allows a failed review attempt with an itemized warning', () => {
    const view = buildCaseDesignDeliveryView({
      mode: 'library',
      executeNow: false,
      generatedCount: 2,
      selectedAutomationCount: 0,
      executabilityIssues: [],
      reviewAttempted: true,
      reviewStatus: 'failed',
      reviewError: '评审服务暂不可用',
      reviewRisks: [],
      environmentValid: false,
      environmentMessage: ''
    })

    expect(view.canSubmit).toBe(true)
    expect(view.warnings).toContain('AI 质量评审未完成，允许带警告继续交付')
    expect(view.riskItems).toEqual([{
      id: 'review:failed',
      kind: 'review_failure',
      priority: 'P0',
      message: '评审服务暂不可用'
    }])
  })
})
```

- [ ] **Step 2: Run the delivery-preflight test to verify it fails**

Run:

```bash
npm test -- --run src/domain/caseDesignDelivery.test.ts
```

Expected: FAIL because `caseDesignDelivery.ts` does not exist.

- [ ] **Step 3: Implement delivery preflight rules**

Create `apps/web/src/domain/caseDesignDelivery.ts`:

```ts
import type { WorkflowAsyncStatus } from './caseDesignWorkflow'

export type CaseDesignDeliveryMode = 'library' | 'automation'
export type CaseDesignDeliveryRiskKind = 'review_failure' | 'conflict' | 'coverage_gap' | 'finding'

export interface CaseDesignDeliveryRiskItem {
  id: string
  kind: CaseDesignDeliveryRiskKind
  priority: string
  message: string
}

export interface CaseDesignDeliveryInput {
  mode: CaseDesignDeliveryMode
  executeNow: boolean
  generatedCount: number
  selectedAutomationCount: number
  executabilityIssues: string[]
  reviewAttempted: boolean
  reviewStatus: WorkflowAsyncStatus
  reviewError: string
  reviewRisks: CaseDesignDeliveryRiskItem[]
  environmentValid: boolean
  environmentMessage: string
}

export interface CaseDesignDeliveryView {
  canSubmit: boolean
  primaryLabel: string
  blockers: string[]
  warnings: string[]
  riskItems: CaseDesignDeliveryRiskItem[]
}

export const buildCaseDesignDeliveryView = (
  input: CaseDesignDeliveryInput
): CaseDesignDeliveryView => {
  const blockers: string[] = []
  const warnings: string[] = []
  const riskItems = [...input.reviewRisks].sort(compareDeliveryRisks)
  if (input.generatedCount === 0) blockers.push('没有可交付的生成用例')
  if (!input.reviewAttempted) blockers.push('请先至少尝试一次 AI 质量评审')
  if (input.reviewAttempted && input.reviewStatus === 'running') blockers.push('AI 质量评审仍在进行中')
  if (input.reviewAttempted && input.reviewStatus === 'idle') blockers.push('AI 质量评审尚未形成可交付结果')
  if (input.mode === 'automation' && input.generatedCount > 0 && input.selectedAutomationCount === 0) {
    blockers.push('请至少选择一条要转换的自动化用例')
  }
  if (input.mode === 'automation') blockers.push(...input.executabilityIssues)
  if (input.mode === 'automation' && input.executeNow && !input.environmentValid) {
    blockers.push(input.environmentMessage || '请先配置有效的运行环境')
  }
  if (input.reviewAttempted && input.reviewStatus === 'failed') {
    warnings.push('AI 质量评审未完成，允许带警告继续交付')
    riskItems.unshift({
      id: 'review:failed',
      kind: 'review_failure',
      priority: 'P0',
      message: input.reviewError || 'AI 质量评审失败，未能取得完整结果'
    })
  }
  if (riskItems.length > 0) warnings.push(`仍有 ${riskItems.length} 项评审风险`)
  return {
    canSubmit: blockers.length === 0,
    primaryLabel: input.mode === 'library'
      ? '保存到用例库'
      : input.executeNow
        ? '保存并执行'
        : '保存并转自动化',
    blockers,
    warnings,
    riskItems
  }
}

const compareDeliveryRisks = (
  left: CaseDesignDeliveryRiskItem,
  right: CaseDesignDeliveryRiskItem
): number => riskKindRank(left.kind) - riskKindRank(right.kind)
  || priorityRank(left.priority) - priorityRank(right.priority)
  || left.id.localeCompare(right.id)

const riskKindRank = (kind: CaseDesignDeliveryRiskKind): number => ({
  review_failure: 0,
  conflict: 1,
  coverage_gap: 2,
  finding: 3
}[kind])

const priorityRank = (priority: string): number => {
  if (priority === 'P0') return 0
  if (priority === 'P1') return 1
  if (priority === 'P2') return 2
  return 3
}
```

- [ ] **Step 4: Write failing delivery-component tests**

Create `apps/web/src/components/workflow/CaseDeliveryStage.test.ts`:

```ts
// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import CaseDeliveryStage from './CaseDeliveryStage.vue'

describe('CaseDeliveryStage', () => {
  it('switches between save-only and automation delivery', async () => {
    const wrapper = mount(CaseDeliveryStage, {
      props: {
        mode: 'library',
        automationKind: 'web',
        executeNow: false,
        generatedCount: 12,
        selectedAutomationCount: 12,
        environmentName: 'Web 测试环境',
        preflight: { canSubmit: true, primaryLabel: '保存到用例库', blockers: [], warnings: [], riskItems: [] }
      }
    })

    await wrapper.get('[data-testid="delivery-automation"]').trigger('click')
    expect(wrapper.emitted('update:mode')).toEqual([['automation']])
  })

  it('leaves confirmation to the single WorkflowHeader primary command', () => {
    const wrapper = mount(CaseDeliveryStage, {
      props: {
        mode: 'automation',
        automationKind: 'web',
        executeNow: true,
        generatedCount: 12,
        selectedAutomationCount: 8,
        environmentName: 'Web 测试环境',
        preflight: {
          canSubmit: true,
          primaryLabel: '保存并执行',
          blockers: [],
          warnings: ['仍有 2 项评审风险'],
          riskItems: [
            { id: 'conflict:c1', kind: 'conflict', priority: 'P0', message: '未处理冲突 · timeout · WEB-AI-0001' },
            { id: 'gap:g1', kind: 'coverage_gap', priority: 'P1', message: '覆盖缺口 · 退款超时异常路径' }
          ]
        }
      }
    })

    expect(wrapper.find('[data-testid="confirm-delivery"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('已选择 8 条转自动化')
    expect(wrapper.text()).toContain('未处理冲突 · timeout · WEB-AI-0001')
    expect(wrapper.text()).toContain('覆盖缺口 · 退款超时异常路径')
  })

  it('shows blockers that disable the header confirmation', () => {
    const wrapper = mount(CaseDeliveryStage, {
      props: {
        mode: 'automation',
        automationKind: 'web',
        executeNow: true,
        generatedCount: 12,
        selectedAutomationCount: 12,
        environmentName: '未配置',
        preflight: { canSubmit: false, primaryLabel: '保存并执行', blockers: ['请先选择 Web 运行环境'], warnings: [], riskItems: [] }
      }
    })

    expect(wrapper.text()).toContain('请先选择 Web 运行环境')
    expect(wrapper.get('[role="alert"]').text()).toContain('请先选择 Web 运行环境')
  })
})
```

- [ ] **Step 5: Run the delivery-component test to verify it fails**

Run:

```bash
npm test -- --run src/components/workflow/CaseDeliveryStage.test.ts
```

Expected: FAIL because `CaseDeliveryStage.vue` does not exist.

- [ ] **Step 6: Implement the delivery component**

Create `apps/web/src/components/workflow/CaseDeliveryStage.vue`:

```vue
<script setup lang="ts">
import { Archive, GitBranch, Play } from 'lucide-vue-next'
import type { CaseDesignDeliveryMode, CaseDesignDeliveryView } from '../../domain/caseDesignDelivery'

defineProps<{
  mode: CaseDesignDeliveryMode
  automationKind: 'api' | 'web'
  executeNow: boolean
  generatedCount: number
  selectedAutomationCount: number
  environmentName: string
  preflight: CaseDesignDeliveryView
}>()

const emit = defineEmits<{
  'update:mode': [mode: CaseDesignDeliveryMode]
  'update:executeNow': [value: boolean]
  configureEnvironment: []
}>()
</script>

<template>
  <section class="studio-delivery-stage" data-testid="case-delivery-stage">
    <header>
      <div>
        <strong>交付本次测试设计</strong>
        <span>{{ generatedCount }} 条生成用例</span>
      </div>
    </header>

    <div class="studio-delivery-options" role="radiogroup" aria-label="交付方式">
      <button
        type="button"
        role="radio"
        data-testid="delivery-library"
        :class="{ 'is-active': mode === 'library' }"
        :aria-checked="mode === 'library'"
        @click="emit('update:mode', 'library')"
      >
        <Archive :size="19" />
        <strong>保存到用例库</strong>
        <span>保留来源、AI 生成和评审记录</span>
      </button>
      <button
        type="button"
        role="radio"
        data-testid="delivery-automation"
        :class="{ 'is-active': mode === 'automation' }"
        :aria-checked="mode === 'automation'"
        @click="emit('update:mode', 'automation')"
      >
        <GitBranch :size="19" />
        <strong>转为 {{ automationKind === 'web' ? 'Web' : 'API' }} 自动化</strong>
        <span>保存后生成可执行用例</span>
      </button>
    </div>

    <div v-if="mode === 'automation'" class="studio-delivery-execution">
      <label>
        <input
          type="checkbox"
          :checked="executeNow"
          @change="emit('update:executeNow', ($event.target as HTMLInputElement).checked)"
        />
        <span><Play :size="15" /> 转换完成后立即执行</span>
      </label>
      <div>
        <span>已选择 {{ selectedAutomationCount }} 条转自动化</span>
        <span>运行环境</span>
        <strong>{{ environmentName || '未选择' }}</strong>
        <el-button text type="primary" @click="emit('configureEnvironment')">配置环境</el-button>
      </div>
    </div>

    <ul v-if="preflight.warnings.length" class="studio-delivery-warnings">
      <li v-for="warning in preflight.warnings" :key="warning">{{ warning }}</li>
    </ul>
    <section
      v-if="preflight.riskItems.length"
      class="studio-delivery-risk-summary"
      aria-labelledby="delivery-risk-summary-title"
    >
      <strong id="delivery-risk-summary-title">交付风险明细</strong>
      <ol>
        <li v-for="risk in preflight.riskItems" :key="risk.id">
          <el-tag
            size="small"
            :type="risk.priority === 'P0' || risk.priority === 'P1' ? 'danger' : 'warning'"
          >
            {{ risk.kind === 'coverage_gap' ? '覆盖缺口' : risk.priority }}
          </el-tag>
          <span>{{ risk.message }}</span>
        </li>
      </ol>
    </section>
    <ul v-if="preflight.blockers.length" class="studio-delivery-blockers" role="alert">
      <li v-for="blocker in preflight.blockers" :key="blocker">{{ blocker }}</li>
    </ul>

    <p class="studio-delivery-stage__hint">
      使用页面顶部的“{{ preflight.primaryLabel }}”完成交付。
    </p>
  </section>
</template>
```

- [ ] **Step 7: Make execution functions accept the exact delivered case set**

Change `startVisualWebAutomationRun` in `apps/web/src/App.vue` to accept cases and return success:

```ts
const startVisualWebAutomationRun = async (
  casesToRun: SavedWebAutomationRunCase[] = filteredWebAutomationCases.value
): Promise<boolean> => {
  if (casesToRun.length === 0) {
    ElMessage.warning('没有可执行的 Web 自动化用例')
    return false
  }
  const webRuntime = resolveWebRuntimeEnvironment()
  if (!webRuntime) return false

  webAutomationRunning.value = true
  activeWebRunCases.value = [...casesToRun]
  webRunDrawerVisible.value = true
  webRunConnectionState.value = 'polling'
  finalizedWebRunId = ''
  webRunPollToken += 1
  try {
    const created = await createWebAutomationRun(casesToRun, {
      mode: webAutomationRunMode.value,
      loopCount: webAutomationLoopCount.value,
      browserOverride: webRuntime.browser,
      runtime: webRuntime
    })
    activeWebRunId.value = created.runId
    applyWebRunSnapshot(created)
    void monitorActiveWebRun(created.runId)
    return true
  } catch (error) {
    webAutomationRunning.value = false
    webRunConnectionState.value = 'interrupted'
    const message = error instanceof WebAutomationRunRequestError
      ? error.toFallbackMessage()
      : error instanceof Error ? error.message : 'Web runner 不可用'
    ElMessage.error(message)
    return false
  }
}
```

Extract the API branch of `executeAllAutomationCases` into this explicit-set helper:

```ts
const executeApiAutomationCases = async (
  casesToRun: SavedAutomationCase[]
): Promise<boolean> => {
  if (casesToRun.length === 0) {
    ElMessage.warning('没有可执行的自动化用例')
    return false
  }
  const environment = resolveRunnableEnvironment()
  if (!environment) return false

  apiAutomationRunning.value = true
  apiAutomationProgress.completed = 0
  apiAutomationProgress.total = casesToRun.length
  apiAutomationProgress.runningCaseId = ''
  try {
    automationRunReport.value = await runAutomationCases(
      casesToRun,
      environment,
      {
        mode: automationRunMode.value,
        concurrencyLimit: automationConcurrencyLimit.value,
        responseBodyMatchThreshold: responseBodyMatchThreshold.value,
        loopCount: apiAutomationLoopCount.value
      },
      executeBrowserRequest,
      {
        onProgress: (progress) => {
          apiAutomationProgress.completed = progress.completed
          apiAutomationProgress.total = progress.total
          apiAutomationProgress.runningCaseId = progress.runningCaseId || ''
        }
      }
    )
    const reportRecord: SavedAutomationReport = {
      ...automationRunReport.value,
      reportId: `report-${Date.now()}`,
      name: `${activeApiExecutionEnvironment.value?.name || '默认环境'}-${new Date().toLocaleString('zh-CN', { hour12: false })}`,
      project: casesToRun[0]?.project || '全部项目',
      environmentName: activeApiExecutionEnvironment.value?.name || '默认环境',
      moduleScope: Array.from(new Set(casesToRun.map((testCase) => testCase.module))),
      createdAt: new Date().toISOString()
    }
    appendAutomationReport(localStorage, reportRecord)
    refreshAutomationReports()
    selectedReportProject.value = reportRecord.project
    selectedReportId.value = reportRecord.reportId
    ElMessage.success(`执行完成：通过 ${automationRunReport.value.summary.passed}，失败 ${automationRunReport.value.summary.failed}，错误 ${automationRunReport.value.summary.error}`)
    return true
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'API 自动化执行失败')
    return false
  } finally {
    apiAutomationRunning.value = false
    apiAutomationProgress.runningCaseId = ''
  }
}

const executeAllAutomationCases = async () => {
  if (automationKind.value === 'web') {
    await startVisualWebAutomationRun(filteredWebAutomationCases.value)
    return
  }
  const casesToRun = automationSelection.value.length > 0 ? automationSelection.value : filteredAutomationCases.value
  await executeApiAutomationCases(casesToRun)
}
```

- [ ] **Step 8: Integrate delivery state and save-first execution**

The delivery domain contract remains `api | web` compatible for later module migration, but this plan renders `CaseDeliveryStage` only inside the Web Studio branch. The legacy interface UI and its existing save/transfer commands remain behaviorally unchanged; extracting the API exact-set helper below must preserve the current `executeAllAutomationCases` behavior.

Import the delivery files in `apps/web/src/App.vue`:

```ts
import CaseDeliveryStage from './components/workflow/CaseDeliveryStage.vue'
import {
  buildCaseDesignDeliveryView,
  type CaseDesignDeliveryMode,
  type CaseDesignDeliveryRiskItem
} from './domain/caseDesignDelivery'
import { clearCaseDesignDraft } from './domain/caseDesignDraftRepository'
```

Add delivery state and preflight:

```ts
const deliveryMode = ref<CaseDesignDeliveryMode>('library')
const deliveryExecuteNow = ref(false)
const deliveryAutomationKind = computed<'api' | 'web'>(() =>
  generationKind.value === 'web_automation' ? 'web' : 'api'
)
const activeDeliveryEnvironment = computed(() =>
  deliveryAutomationKind.value === 'web'
    ? activeWebExecutionEnvironment.value
    : activeApiExecutionEnvironment.value
)
const deliveryEnvironmentValidation = computed(() =>
  deliveryAutomationKind.value === 'web'
    ? validateWebExecutionEnvironment(activeDeliveryEnvironment.value)
    : validateExecutionEnvironment(activeDeliveryEnvironment.value)
)
const selectedWebCasesForAutomation = computed(() => {
  const selectedIds = new Set(selectedGeneratedCaseIds.value)
  return selectedIds.size > 0
    ? generatedWebCases.value.filter((testCase) => selectedIds.has(testCase.id))
    : generatedWebCases.value
})
const deliveryExecutabilityIssues = computed(() => {
  if (deliveryAutomationKind.value !== 'web') return []
  const pendingLocatorIssues = selectedWebCasesForAutomation.value.flatMap((testCase) =>
    testCase.metadata.locators
      .filter((locator) => locator.confirmed === false)
      .map((locator) => `${testCase.row.caseId}：定位器 ${locator.name} 尚未确认`)
  )
  const prepared = generateWebAutomationCasesFromGeneratedCases(selectedWebCasesForAutomation.value)
  const conversionIssues = validateWebAutomationConversionCases(prepared).map(
    (issue) => `${issue.caseId}：${issue.message}`
  )
  return [...pendingLocatorIssues, ...conversionIssues]
})
const deliveryReviewRisks = computed<CaseDesignDeliveryRiskItem[]>(() => {
  const risks: CaseDesignDeliveryRiskItem[] = aiMultiAgentConflicts.value
    .filter((conflict) => conflict.resolutionStatus === 'unresolved')
    .map((conflict) => ({
      id: `conflict:${conflict.conflictId}`,
      kind: 'conflict' as const,
      priority: 'P0',
      message: `未处理冲突 · ${conflict.conflictType} · ${conflict.targetKey}`
    }))

  for (const testCase of generatedWebCases.value) {
    const view = buildCaseDesignReviewView({
      selectedCase: {
        id: testCase.row.caseId,
        title: testCase.row.title,
        module: testCase.row.module,
        sourceFile: testCase.row.sourceFile
      },
      qualityScores: aiMultiAgentQualityScores.value,
      conflicts: aiMultiAgentConflicts.value,
      findings: caseDesignReviewFindings.value,
      findingDecisions: caseReviewFindingDecisions.value
    })
    view.findings
      .filter((finding) => finding.decision === 'pending')
      .forEach((finding) => risks.push({
        id: finding.category === 'coverage_gap'
          ? `coverage:${finding.findingId}`
          : `${testCase.row.caseId}:${finding.findingId}`,
        kind: finding.category === 'coverage_gap' ? 'coverage_gap' : 'finding',
        priority: finding.priority || 'P2',
        message: finding.category === 'coverage_gap'
          ? `覆盖缺口 · ${finding.title}${finding.detail ? ` · ${finding.detail}` : ''}`
          : `${testCase.row.caseId} · ${finding.title}${finding.detail ? ` · ${finding.detail}` : ''}`
      }))
  }

  // Global coverage gaps can be pending for several cases, but appear once in the delivery summary.
  return [...new Map(risks.map((risk) => [risk.id, risk])).values()]
})
const caseDesignDeliveryView = computed(() => buildCaseDesignDeliveryView({
  mode: deliveryMode.value,
  executeNow: deliveryExecuteNow.value,
  generatedCount: generationKind.value === 'web_automation'
    ? generatedWebCases.value.length
    : generatedCases.value.length,
  selectedAutomationCount: deliveryAutomationKind.value === 'web'
    ? selectedWebCasesForAutomation.value.length
    : generatedCases.value.length,
  executabilityIssues: deliveryExecutabilityIssues.value,
  reviewAttempted: caseReviewAttempted.value,
  reviewStatus: caseReviewStatus.value,
  reviewError: caseReviewError.value,
  reviewRisks: deliveryReviewRisks.value,
  environmentValid: deliveryEnvironmentValidation.value.valid,
  environmentMessage: deliveryEnvironmentValidation.value.message || ''
}))

const caseDesignHeaderPrimaryAction = computed(() =>
  caseDesignWorkflow.value.activeStage === 'deliver'
    ? {
        label: caseDesignDeliveryView.value.primaryLabel,
        disabled: !caseDesignDeliveryView.value.canSubmit || saveCasesLoading.value,
        loading: saveCasesLoading.value
      }
    : caseDesignWorkflow.value.primaryAction
)
const caseDesignHeaderSecondaryAction = computed(() =>
  stagedRequirementFiles.value.length > 0 || generatedWebCases.value.length > 0
    ? { label: '新建草稿', disabled: saveCasesLoading.value }
    : undefined
)
```

Replace the temporary `deliverGeneratedCases` from Task 5 with:

```ts
const beginCaseDesignTask = () => {
  caseDesignDraftPersistenceSuspended.value = false
  caseDesignDraftSaveError.value = ''
}

const clearCompletedCaseDesignTask = () => {
  caseDesignDraftPersistenceSuspended.value = true
  if (caseDesignDraftTimer) clearTimeout(caseDesignDraftTimer)
  clearGeneratedCaseWorkspace()
  stagedRequirementFiles.value = []
  stagedRequirementSourceFiles.value = []
  requirementSourceErrors.value = {}
  resetAiRequirementAnalysis()
  aiAnalysisError.value = ''
  aiGenerationError.value = ''
  caseReviewError.value = ''
  caseReviewAttempted.value = false
  caseReviewFindingDecisions.value = {}
  selectedGeneratedCaseIds.value = []
  generationScopePointIds.value = []
  activeCaseDesignStage.value = 'import'
  caseDesignLastSavedAt.value = ''
  clearCaseDesignDraft(localStorage)
}

const startNewCaseDesignDraft = async () => {
  const confirmed = await confirmDangerAction(
    '新建测试设计草稿',
    '当前草稿已自动保存。确认清空当前画布并开始新任务吗？',
    '新建草稿'
  )
  if (!confirmed) return
  clearCompletedCaseDesignTask()
}

const deliverGeneratedCases = async () => {
  if (!caseDesignDeliveryView.value.canSubmit) return
  saveCasesLoading.value = true
  try {
    if (deliveryMode.value === 'library') {
      const saved = await persistGeneratedCasesToLibrary(false)
      if (!saved) return
      clearCompletedCaseDesignTask()
      navigateToNav('cases')
      ElMessage.success('已保存到用例库')
      return
    }

    if (deliveryAutomationKind.value === 'web') {
      generatedWebCases.value = finalizeDesignDraftCasesForLibrary(generatedWebCases.value)
    }
    const saved = await persistGeneratedCasesToLibrary(false)
    if (!saved) return

    if (deliveryAutomationKind.value === 'web') {
      const selectedIds = new Set(selectedWebCasesForAutomation.value.map((testCase) => testCase.id))
      const prepared = generateWebAutomationCasesFromGeneratedCases(
        generatedWebCases.value.filter((testCase) => selectedIds.has(testCase.id))
      )
      const preparedIds = new Set(prepared.map((testCase) => testCase.id))
      saveWebAutomationRunCases(localStorage, prepared)
      refreshAutomationCases()
      const exactCases = webAutomationCases.value.filter((testCase) => preparedIds.has(testCase.id))
      clearCompletedCaseDesignTask()
      automationKind.value = 'web'
      if (deliveryExecuteNow.value) {
        await startVisualWebAutomationRun(exactCases)
      } else {
        navigateToNav('automation')
      }
      return
    }

    const prepared = generateAutomationCasesFromInterfaceCases(generatedCases.value)
    const preparedIds = new Set(prepared.map((testCase) => testCase.id))
    saveAutomationCases(localStorage, prepared)
    refreshAutomationCases()
    const exactCases = automationCases.value.filter((testCase) => preparedIds.has(testCase.id))
    clearCompletedCaseDesignTask()
    automationKind.value = 'api'
    if (deliveryExecuteNow.value) {
      await executeApiAutomationCases(exactCases)
    } else {
      navigateToNav('automation')
    }
  } finally {
    saveCasesLoading.value = false
  }
}
```

Call `beginCaseDesignTask()` as the first statement in `importWebCaseFiles`, `importOpenApiRequirementFiles`, and `promptFigmaDesignImport`. This re-enables autosave only when the user explicitly starts a new task after delivery and prevents the Task 5 deep watcher from recreating an empty draft after `clearCompletedCaseDesignTask()`.

Replace the `WorkflowHeader` binding from Task 5:

```vue
:primary-action="caseDesignWorkflow.primaryAction"
```

with:

```vue
:primary-action="caseDesignHeaderPrimaryAction"
:secondary-action="caseDesignHeaderSecondaryAction"
:mode-action="{ label: '接口用例', disabled: saveCasesLoading }"
@secondary="startNewCaseDesignDraft"
@mode-action="generationKind = 'interface'"
```

Render the delivery stage when active:

```vue
<CaseDeliveryStage
  v-if="generationKind === 'web_automation' && caseDesignWorkflow.activeStage === 'deliver'"
  v-model:mode="deliveryMode"
  v-model:execute-now="deliveryExecuteNow"
  :automation-kind="deliveryAutomationKind"
  :generated-count="generatedWebCases.length"
  :selected-automation-count="selectedWebCasesForAutomation.length"
  :environment-name="activeDeliveryEnvironment?.name || ''"
  :preflight="caseDesignDeliveryView"
  @configure-environment="openEnvironmentEditor(undefined, deliveryAutomationKind)"
/>
```

Add this structural regression to `apps/web/src/appShellStructure.test.ts`:

```ts
it('keeps exactly one stage-specific primary command during delivery', () => {
  expect(appSource).toContain(':primary-action="caseDesignHeaderPrimaryAction"')
  expect(appSource).not.toContain('data-testid="confirm-delivery"')
  expect(appSource).not.toContain('@confirm="deliverGeneratedCases"')
})
```

- [ ] **Step 9: Run delivery, automation, and build checks**

Run:

```bash
npm test -- --run src/domain/caseDesignDelivery.test.ts src/components/workflow/CaseDeliveryStage.test.ts src/domain/apiAutomation.test.ts src/domain/webAutomation.test.ts src/appShellStructure.test.ts
npm run build
```

Expected: tests PASS; save-only ignores environment validity, while immediate execution requires a valid environment and runs only newly delivered cases.

- [ ] **Step 10: Commit save-first delivery**

```bash
git add apps/web/src/domain/caseDesignDelivery.ts apps/web/src/domain/caseDesignDelivery.test.ts apps/web/src/components/workflow/CaseDeliveryStage.vue apps/web/src/components/workflow/CaseDeliveryStage.test.ts apps/web/src/App.vue apps/web/src/appShellStructure.test.ts
git commit -m "feat: add save-first case delivery"
```

## Task 10: Add Responsive Drawers, Shared State Feedback, And Accessibility Checks

**Files:**
- Create: `apps/web/src/components/workflow/WorkflowStateView.test.ts`
- Create: `apps/web/src/components/workflow/WorkflowStateView.vue`
- Create: `apps/web/src/ui/useViewportMode.test.ts`
- Create: `apps/web/src/ui/useViewportMode.ts`
- Create: `apps/web/src/ui/useOnlineStatus.test.ts`
- Create: `apps/web/src/ui/useOnlineStatus.ts`
- Create: `apps/web/src/components/workflow/ResponsiveDrawer.test.ts`
- Create: `apps/web/src/components/workflow/ResponsiveDrawer.vue`
- Create: `apps/web/src/components/workflow/RequirementSourceList.vue`
- Create: `apps/web/src/components/workflow/RequirementSourceList.test.ts`
- Modify: `apps/web/src/components/workflow/RequirementImportStage.vue`
- Modify: `apps/web/src/components/workflow/WorkflowHeader.vue`
- Modify: `apps/web/src/components/shell/GlobalTopbar.vue`
- Modify: `apps/web/src/components/shell/GlobalTopbar.test.ts`
- Modify: `apps/web/src/styles/studio-canvas.css`
- Modify: `apps/web/src/appShellStructure.test.ts`

- [ ] **Step 1: Write failing shared-state tests**

Create `apps/web/src/components/workflow/WorkflowStateView.test.ts`:

```ts
// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WorkflowStateView from './WorkflowStateView.vue'

describe('WorkflowStateView', () => {
  it('uses alert semantics for errors and exposes a local retry', async () => {
    const wrapper = mount(WorkflowStateView, {
      props: {
        variant: 'error',
        title: '需求解析失败',
        description: '文档读取超时',
        actionLabel: '重试此项'
      }
    })

    expect(wrapper.attributes('role')).toBe('alert')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('action')).toHaveLength(1)
  })

  it('distinguishes first-use and filtered empty states', () => {
    const firstUse = mount(WorkflowStateView, {
      props: {
        variant: 'empty',
        title: '还没有需求来源',
        description: '添加文档、Figma 或 OpenAPI',
        actionLabel: '添加需求来源'
      }
    })
    const filtered = mount(WorkflowStateView, {
      props: {
        variant: 'filtered-empty',
        title: '没有符合条件的用例',
        description: '当前筛选条件没有匹配结果',
        actionLabel: '清除筛选'
      }
    })

    expect(firstUse.text()).toContain('添加需求来源')
    expect(filtered.text()).toContain('清除筛选')
  })

  it('announces offline local-first operation without presenting it as a blocking error', () => {
    const wrapper = mount(WorkflowStateView, {
      props: {
        variant: 'offline',
        title: '当前离线',
        description: '草稿仍会保存在本机，联网后可继续同步'
      }
    })

    expect(wrapper.attributes('role')).toBe('status')
    expect(wrapper.text()).toContain('草稿仍会保存在本机')
  })
})
```

- [ ] **Step 2: Run the shared-state test to verify it fails**

Run:

```bash
npm test -- --run src/components/workflow/WorkflowStateView.test.ts
```

Expected: FAIL because `WorkflowStateView.vue` does not exist.

- [ ] **Step 3: Implement the shared state component**

Create `apps/web/src/components/workflow/WorkflowStateView.vue`:

```vue
<script setup lang="ts">
import { AlertTriangle, Inbox, LoaderCircle, SearchX, WifiOff } from 'lucide-vue-next'
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  variant: 'loading' | 'empty' | 'filtered-empty' | 'error' | 'partial' | 'offline'
  title: string
  description: string
  actionLabel?: string
  progress?: number
}>(), {
  actionLabel: '',
  progress: 0
})

const emit = defineEmits<{
  action: []
}>()

const icon = computed(() => ({
  loading: LoaderCircle,
  empty: Inbox,
  'filtered-empty': SearchX,
  error: AlertTriangle,
  partial: AlertTriangle,
  offline: WifiOff
}[props.variant]))
const role = computed(() => props.variant === 'error' ? 'alert' : 'status')
</script>

<template>
  <section class="studio-workflow-state" :class="`is-${variant}`" :role="role">
    <component :is="icon" :size="24" aria-hidden="true" />
    <strong>{{ title }}</strong>
    <p>{{ description }}</p>
    <el-progress v-if="variant === 'loading'" :percentage="progress" />
    <el-button v-if="actionLabel" type="primary" plain @click="emit('action')">{{ actionLabel }}</el-button>
  </section>
</template>
```

- [ ] **Step 4: Write failing viewport-mode and first-visit sidebar tests**

Create `apps/web/src/ui/useViewportMode.test.ts`:

```ts
// @vitest-environment jsdom
import { defineComponent, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import {
  initialSidebarCollapsed,
  useViewportMode,
  viewportModeForWidth
} from './useViewportMode'

describe('useViewportMode', () => {
  it('maps approved desktop breakpoints', () => {
    expect(viewportModeForWidth(1600)).toBe('wide')
    expect(viewportModeForWidth(1280)).toBe('standard')
    expect(viewportModeForWidth(1024)).toBe('compact')
    expect(viewportModeForWidth(800)).toBe('narrow')
  })

  it('defaults a first compact visit to collapsed without overriding a stored preference', () => {
    expect(initialSidebarCollapsed(null, 1024)).toBe(true)
    expect(initialSidebarCollapsed(null, 1280)).toBe(false)
    expect(initialSidebarCollapsed('false', 1024)).toBe(false)
    expect(initialSidebarCollapsed('true', 1600)).toBe(true)
  })

  it('reacts to window resize and removes the listener on unmount', async () => {
    const add = vi.spyOn(window, 'addEventListener')
    const remove = vi.spyOn(window, 'removeEventListener')
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1280 })
    const Harness = defineComponent({
      setup() {
        return { mode: useViewportMode() }
      },
      template: '<span>{{ mode }}</span>'
    })
    const wrapper = mount(Harness)
    expect(wrapper.text()).toBe('standard')

    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024 })
    window.dispatchEvent(new Event('resize'))
    await nextTick()
    expect(wrapper.text()).toBe('compact')
    const resizeHandler = add.mock.calls.find(([name]) => name === 'resize')?.[1]
    wrapper.unmount()
    expect(remove).toHaveBeenCalledWith('resize', resizeHandler)
    add.mockRestore()
    remove.mockRestore()
  })
})
```

- [ ] **Step 5: Run viewport-mode tests to verify they fail**

Run:

```bash
npm test -- --run src/ui/useViewportMode.test.ts
```

Expected: FAIL because `useViewportMode.ts` does not exist.

- [ ] **Step 6: Implement reactive viewport mode and compact first-visit preference**

Create `apps/web/src/ui/useViewportMode.ts`:

```ts
import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

export type ViewportMode = 'wide' | 'standard' | 'compact' | 'narrow'

export const viewportModeForWidth = (width: number): ViewportMode => {
  if (width >= 1440) return 'wide'
  if (width >= 1180) return 'standard'
  if (width >= 900) return 'compact'
  return 'narrow'
}

export const initialSidebarCollapsed = (
  storedValue: string | null,
  width: number
): boolean => storedValue === null ? width < 1180 : storedValue === 'true'

export const useViewportMode = (): Ref<ViewportMode> => {
  const mode = ref<ViewportMode>(viewportModeForWidth(typeof window === 'undefined' ? 1440 : window.innerWidth))
  const update = () => { mode.value = viewportModeForWidth(window.innerWidth) }
  onMounted(() => window.addEventListener('resize', update, { passive: true }))
  onBeforeUnmount(() => window.removeEventListener('resize', update))
  return mode
}
```

- [ ] **Step 7: Write failing online-status and responsive-drawer tests**

Create `apps/web/src/ui/useOnlineStatus.test.ts`:

```ts
// @vitest-environment jsdom
import { defineComponent, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { useOnlineStatus } from './useOnlineStatus'

describe('useOnlineStatus', () => {
  it('reacts to offline and online events and cleans up listeners', async () => {
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: true })
    const remove = vi.spyOn(window, 'removeEventListener')
    const Harness = defineComponent({
      setup() { return { online: useOnlineStatus() } },
      template: '<span>{{ online }}</span>'
    })
    const wrapper = mount(Harness)
    expect(wrapper.text()).toBe('true')
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: false })
    window.dispatchEvent(new Event('offline'))
    await nextTick()
    expect(wrapper.text()).toBe('false')
    wrapper.unmount()
    expect(remove.mock.calls.some(([name]) => name === 'online')).toBe(true)
    expect(remove.mock.calls.some(([name]) => name === 'offline')).toBe(true)
    remove.mockRestore()
  })
})
```

Create `apps/web/src/components/workflow/ResponsiveDrawer.test.ts`:

```ts
// @vitest-environment jsdom
import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ResponsiveDrawer from './ResponsiveDrawer.vue'

const DrawerStub = defineComponent({
  props: ['modelValue', 'title'],
  emits: ['update:modelValue'],
  template: '<section role="dialog" :aria-label="title"><slot/><button data-close @click="$emit(\'update:modelValue\', false)">关闭</button></section>'
})

describe('ResponsiveDrawer', () => {
  it('provides a labelled dialog and forwards close state', async () => {
    const wrapper = mount(ResponsiveDrawer, {
      props: { modelValue: true, title: 'AI 质量评审' },
      slots: { default: '<button>评审动作</button>' },
      global: { stubs: { ElDrawer: DrawerStub } }
    })
    expect(wrapper.get('[role="dialog"]').attributes('aria-label')).toBe('AI 质量评审')
    await wrapper.get('[data-close]').trigger('click')
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
  })
})
```

Create `apps/web/src/components/workflow/RequirementSourceList.test.ts`:

```ts
// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import RequirementSourceList from './RequirementSourceList.vue'

describe('RequirementSourceList', () => {
  it('keeps retry, replace, and remove actions available in either presentation', async () => {
    const wrapper = mount(RequirementSourceList, {
      props: {
        sources: [{ name: 'broken.pdf', kind: 'document', kindLabel: '文档', status: 'error', detail: '读取失败' }]
      }
    })
    await wrapper.get('[data-testid="retry-source-broken.pdf"]').trigger('click')
    await wrapper.get('[data-testid="replace-source-broken.pdf"]').trigger('click')
    await wrapper.get('[data-testid="remove-source-broken.pdf"]').trigger('click')
    expect(wrapper.emitted('retry')).toEqual([['broken.pdf']])
    expect(wrapper.emitted('replace')).toEqual([['broken.pdf']])
    expect(wrapper.emitted('remove')).toEqual([['broken.pdf']])
  })
})
```

- [ ] **Step 8: Run the online-status and drawer tests to verify they fail**

Run:

```bash
npm test -- --run src/ui/useOnlineStatus.test.ts src/components/workflow/ResponsiveDrawer.test.ts src/components/workflow/RequirementSourceList.test.ts
```

Expected: FAIL because `useOnlineStatus.ts`, `ResponsiveDrawer.vue`, and `RequirementSourceList.vue` do not exist.

- [ ] **Step 9: Implement online status and the accessible drawer wrapper**

Create `apps/web/src/ui/useOnlineStatus.ts`:

```ts
import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

export const useOnlineStatus = (): Ref<boolean> => {
  const online = ref(typeof navigator === 'undefined' ? true : navigator.onLine)
  const setOnline = () => { online.value = true }
  const setOffline = () => { online.value = false }
  onMounted(() => {
    window.addEventListener('online', setOnline)
    window.addEventListener('offline', setOffline)
  })
  onBeforeUnmount(() => {
    window.removeEventListener('online', setOnline)
    window.removeEventListener('offline', setOffline)
  })
  return online
}
```

Create `apps/web/src/components/workflow/ResponsiveDrawer.vue`:

```vue
<script setup lang="ts">
withDefaults(defineProps<{
  modelValue: boolean
  title: string
  size?: string
  direction?: 'ltr' | 'rtl' | 'ttb' | 'btt'
}>(), {
  size: '360px',
  direction: 'rtl'
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="title"
    :size="size"
    :direction="direction"
    :aria-label="title"
    append-to-body
    modal
    close-on-press-escape
    @update:model-value="emit('update:modelValue', $event)"
  >
    <slot />
  </el-drawer>
</template>
```

- [ ] **Step 10: Extract the single reusable requirement-source list**

Create `apps/web/src/components/workflow/RequirementSourceList.vue`:

```vue
<script setup lang="ts">
import { RefreshCcw, Trash2 } from 'lucide-vue-next'
import type { CaseDesignSourceView } from '../../domain/caseDesignSourceView'

defineProps<{ sources: CaseDesignSourceView[] }>()
const emit = defineEmits<{
  retry: [name: string]
  replace: [name: string]
  remove: [name: string]
}>()
</script>

<template>
  <aside class="studio-import-stage__sources" aria-label="本次任务来源">
    <header><strong>本次任务来源</strong><span>{{ sources.length }}</span></header>
    <div v-if="sources.length" class="studio-source-list">
      <article v-for="source in sources" :key="source.name" class="studio-source-item" :class="`is-${source.status}`">
        <span class="studio-source-item__kind">{{ source.kindLabel }}</span>
        <div><strong>{{ source.name }}</strong><span>{{ source.detail }}</span></div>
        <div class="studio-source-item__actions">
          <button v-if="source.status !== 'ready'" type="button" :data-testid="`retry-source-${source.name}`" :aria-label="`重试 ${source.name}`" @click="emit('retry', source.name)">
            <RefreshCcw :size="14" aria-hidden="true" />
          </button>
          <button v-if="source.status !== 'ready'" type="button" :data-testid="`replace-source-${source.name}`" :aria-label="`替换 ${source.name}`" @click="emit('replace', source.name)">替换</button>
          <button type="button" :data-testid="`remove-source-${source.name}`" :aria-label="`移除 ${source.name}`" @click="emit('remove', source.name)">
            <Trash2 :size="14" aria-hidden="true" />
          </button>
        </div>
      </article>
    </div>
    <div v-else class="studio-import-stage__source-empty">还没有添加需求来源</div>
  </aside>
</template>
```

- [ ] **Step 11: Run the online-status, drawer, and source-list tests to verify they pass**

Run:

```bash
npm test -- --run src/ui/useOnlineStatus.test.ts src/components/workflow/ResponsiveDrawer.test.ts src/components/workflow/RequirementSourceList.test.ts
```

Expected: all three focused tests PASS.

- [ ] **Step 12: Make the source rail use the accessible responsive drawer**

Update `RequirementImportStage.vue` imports, keeping every existing prop from Task 6 including `parsedItems`:

```ts
import { ref, watch } from 'vue'
import { FileJson, FileText, Figma, Upload } from 'lucide-vue-next'
import ResponsiveDrawer from './ResponsiveDrawer.vue'
import RequirementSourceList from './RequirementSourceList.vue'

const props = withDefaults(defineProps<{
  sources: CaseDesignSourceView[]
  analysisStatus: WorkflowAsyncStatus
  progressPercentage: number
  progressText: string
  errorMessage: string
  parsedItems?: Array<{ id: string; kindLabel: string; label: string }>
  completedSourceCount?: number
  totalSourceCount?: number
  compact?: boolean
}>(), {
  parsedItems: () => [],
  completedSourceCount: 0,
  totalSourceCount: 0,
  compact: false
})

const sourceDrawerOpen = ref(false)
watch(() => props.compact, (compact) => {
  if (!compact) sourceDrawerOpen.value = false
})
```

Delete the source `<aside class="studio-import-stage__sources">...</aside>` that Task 6 placed inside `RequirementImportStage`. Insert this single-instance inline/drawer composition at the same position:

```vue
<button
  v-if="compact"
  type="button"
  class="studio-import-stage__source-toggle"
  data-testid="context-drawer-button"
  aria-controls="requirement-source-drawer"
  :aria-expanded="sourceDrawerOpen"
  @click="sourceDrawerOpen = true"
>
  查看需求来源（{{ sources.length }}）
</button>

<ResponsiveDrawer
  v-if="compact"
  v-model="sourceDrawerOpen"
  title="本次任务来源"
  size="min(82vw, 320px)"
  direction="ltr"
>
  <RequirementSourceList
    :sources="sources"
    @retry="emit('retrySource', $event)"
    @replace="emit('replaceSource', $event)"
    @remove="emit('removeSource', $event)"
  />
</ResponsiveDrawer>

<RequirementSourceList
  v-else
  :sources="sources"
  @retry="emit('retrySource', $event)"
  @replace="emit('replaceSource', $event)"
  @remove="emit('removeSource', $event)"
/>
```

- [ ] **Step 13: Integrate compact and narrow responsive behavior**

In `apps/web/src/App.vue`, import the responsive helpers and drawer:

```ts
import ResponsiveDrawer from './components/workflow/ResponsiveDrawer.vue'
import WorkflowStateView from './components/workflow/WorkflowStateView.vue'
import { initialSidebarCollapsed, useViewportMode } from './ui/useViewportMode'
import { useOnlineStatus } from './ui/useOnlineStatus'
```

Replace the original sidebar initialization with the first-visit-aware version:

```ts
const savedCollapsed = localStorage.getItem('ai-test-platform.sidebar-collapsed')
const railCollapsed = ref(initialSidebarCollapsed(savedCollapsed, window.innerWidth))
```

Add responsive state and close stale drawers whenever the stage or viewport changes:

```ts
const viewportMode = useViewportMode()
const isOnline = useOnlineStatus()
const isCompactDesktop = computed(() => viewportMode.value === 'compact' || viewportMode.value === 'narrow')
const isNarrowViewport = computed(() => viewportMode.value === 'narrow')
const narrowNavigationVisible = ref(false)
const compactContextVisible = ref(false)
const compactInspectorVisible = ref(false)
const inlineContextVisible = computed(() =>
  shellContextVisible.value && !(caseDesignContextVisible.value && isCompactDesktop.value)
)
const inlineInspectorVisible = computed(() => shellInspectorVisible.value && !isCompactDesktop.value)

watch([viewportMode, activeCaseDesignStage], () => {
  compactContextVisible.value = false
  compactInspectorVisible.value = false
  if (viewportMode.value !== 'narrow') narrowNavigationVisible.value = false
})

const toggleStudioNavigation = () => {
  if (isNarrowViewport.value) {
    narrowNavigationVisible.value = !narrowNavigationVisible.value
    return
  }
  toggleRail()
}

const globalLongTaskLabel = computed(() => {
  if (aiAnalysisRunning.value) return `内容解析 · ${aiAnalysisProgress.text}`
  if (aiCaseGenerationRunning.value) return `AI 生成 · ${aiCaseGenerationProgress.text}`
  if (webAutomationRunning.value) return 'Web 自动化执行中'
  if (apiAutomationRunning.value) return `API 自动化 · ${apiAutomationProgress.completed}/${apiAutomationProgress.total}`
  return ''
})

const openActiveLongTask = () => {
  if (aiAnalysisRunning.value || aiCaseGenerationRunning.value) {
    generationKind.value = 'web_automation'
    activeCaseDesignStage.value = aiAnalysisRunning.value ? 'parse' : 'generate'
    navigateToNav('api')
    return
  }
  navigateToNav('automation')
}
```

In the existing `GlobalTopbar.vue` `withDefaults(defineProps(...))` block, add:

```ts
taskStatusLabel?: string
```

and add `taskStatusLabel: ''` to its defaults. In the existing `defineEmits` block, add:

```ts
openTask: []
```

Insert this utility between global search and user identity:

```vue
<button
  v-if="taskStatusLabel"
  type="button"
  class="studio-global-task-status"
  aria-live="polite"
  @click="emit('openTask')"
>
  {{ taskStatusLabel }}
</button>
```

Add to `GlobalTopbar.test.ts`:

```ts
it('keeps a long task visible and recoverable after module navigation', async () => {
  const wrapper = mount(GlobalTopbar, {
    props: {
      sidebarCollapsed: false,
      projectLabel: '订单服务',
      sectionLabel: '执行报告',
      searchValue: '',
      taskStatusLabel: '内容解析 · 2/3'
    }
  })
  await wrapper.get('.studio-global-task-status').trigger('click')
  expect(wrapper.emitted('openTask')).toHaveLength(1)
})
```

Restructure the unique API workspace branch using these exact anchors.

Use the `<template v-if="activeNav === 'api'">` that occurs inside `<main class="workspace">`; do not change the earlier API branch inside the context/resource-pane slot.

Immediately after:

```vue
<template v-if="activeNav === 'api'">
```

insert:

```vue
<template v-if="generationKind === 'interface'">
```

Do not add individual `v-if` directives to the legacy `header.topbar`, `section.control-strip`, or `section.main-grid`. Keep those three complete sibling blocks, including all existing bindings and actions, inside this template without otherwise changing them.

Immediately after the matching closing `</section>` of:

```vue
<section class="main-grid" :class="{ 'main-grid-single': generationKind !== 'interface' }">
```

insert:

```vue
</template>

<section v-else class="studio-case-design-canvas">
```

The `v-else` must be the next element after the interface `<template>` so Vue attaches both branches deterministically.

Move the single existing `WorkflowHeader` created in Task 5 directly inside `.studio-case-design-canvas`. Immediately after its closing tag, insert:

```vue
<div class="studio-case-design-canvas__stage">
```

Move each existing Web root into this stage container exactly once and in this order, preserving all bindings established in Tasks 6–9:

1. `RequirementImportStage`
2. `AiGenerationStage`
3. the `el-drawer` whose model is `advancedGenerationVisible`
4. `section.studio-review-stage`
5. `div.design-locator-panel`
6. `CaseDeliveryStage`

Immediately after the matching closing tag of the single `CaseDeliveryStage`, insert:

```vue
  </div>
</section>
```

The next element must be the existing closing `</template>` for `activeNav === 'api'`, followed by the existing `activeNav === 'cases'` branch. The resulting branch topology is therefore:

```vue
<template v-if="activeNav === 'api'">
  <template v-if="generationKind === 'interface'">
    <!-- the unchanged legacy topbar, control-strip, and main-grid blocks -->
  </template>

  <section v-else class="studio-case-design-canvas">
    <!-- the single WorkflowHeader -->
    <div class="studio-case-design-canvas__stage">
      <!-- the six Web roots listed above, in order and without duplication -->
    </div>
  </section>
</template>
```

The comments above document topology only; do not insert them into `App.vue`.

Add the Studio workflow class to the existing main element:

```vue
<main
  class="workspace"
  :class="{ 'is-studio-case-design': activeNav === 'api' && generationKind === 'web_automation' }"
>
```

Pass `:context-visible="inlineContextVisible"` and `:inspector-visible="inlineInspectorVisible"` to `AppShell`. Replace the `#sidebar` slot with:

```vue
<template #sidebar>
  <PrimarySidebar
    v-if="!isNarrowViewport"
    :groups="appSidebarGroups"
    :active-item-id="sidebarActiveItemId"
    :collapsed="railCollapsed"
    :badges="sidebarBadges"
    @select="selectSidebarItem"
  />
</template>
```

Change `GlobalTopbar` to use the narrow-aware state and action:

```vue
<GlobalTopbar
  v-model:search-value="globalSearch"
  :sidebar-collapsed="isNarrowViewport ? !narrowNavigationVisible : railCollapsed"
  :project-label="globalProjectLabel"
  :section-label="currentNav.label"
  :task-status-label="globalLongTaskLabel"
  @toggle-sidebar="toggleStudioNavigation"
  @open-task="openActiveLongTask"
/>
```

Render functional narrow navigation as a drawer sibling after `AppShell`:

```vue
<ResponsiveDrawer
  v-model="narrowNavigationVisible"
  title="主导航"
  size="168px"
  direction="ltr"
>
  <PrimarySidebar
    :groups="appSidebarGroups"
    :active-item-id="sidebarActiveItemId"
    :collapsed="false"
    :badges="sidebarBadges"
    @select="selectSidebarItem($event); narrowNavigationVisible = false"
  />
</ResponsiveDrawer>
```

Pass `:compact="isCompactDesktop"` and `:parsed-items="caseDesignParsedItems"` to `RequirementImportStage`. As the first child of `main.workspace`, after the sync-conflict notice, render the offline local-first notice:

```vue
<WorkflowStateView
  v-if="!isOnline"
  class="studio-offline-notice"
  variant="offline"
  title="当前离线"
  description="草稿仍会保存在本机，联网后可继续同步和恢复长任务"
/>
```

At the top of the generation and review canvases add:

```vue
<el-button
  v-if="isCompactDesktop && caseDesignContextVisible"
  data-testid="context-pane-drawer-button"
  @click="compactContextVisible = true"
>
  查看需求与模块
</el-button>
<el-button
  v-if="isCompactDesktop && caseDesignWorkflow.activeStage === 'review'"
  data-testid="inspector-drawer-button"
  @click="compactInspectorVisible = true"
>
  查看 AI 评审
</el-button>
```

Render the same filtered context and inspector components through `ResponsiveDrawer`:

```vue
<ResponsiveDrawer
  v-model="compactInspectorVisible"
  title="AI 质量评审"
  size="min(88vw, 360px)"
>
  <AiReviewInspector
    :selected-case-title="caseDesignReviewView.selectedCaseTitle"
    :selected-source-file="caseDesignReviewView.selectedSourceFile"
    :selected-count="selectedReviewCaseCount"
    :high-risk-count="caseDesignReviewView.highRiskCount"
    :pending-finding-count="caseDesignReviewView.pendingFindingCount"
    :average-score="caseDesignReviewView.averageScore"
    :summary="activeAiReviewInsight.summary"
    :findings="caseDesignReviewView.findings"
    :conflicts="caseDesignRelevantConflicts"
    :loading="aiMultiAgentLoading"
    @refresh="refreshMultiAgentReview"
    @ignore-conflicts="ignoreUnresolvedMultiAgentConflicts"
    @ignore-conflict="ignoreSingleReviewConflict"
    @merge="mergeMultiAgentReview"
    @locate-source="selectCaseDesignSource"
    @decide="decideSelectedReviewFinding"
    @bulk-decide="bulkDecideReviewCases"
  />
</ResponsiveDrawer>

<ResponsiveDrawer
  v-model="compactContextVisible"
  title="需求与模块"
  size="min(82vw, 320px)"
  direction="ltr"
>
  <CaseDesignContextPane
    :sources="caseDesignSourceView"
    :modules="aiDraftPointView.moduleOptions"
    :active-module="caseDesignActiveModule"
    :active-source="activeCaseDesignSource"
    @select-source="selectCaseDesignSource"
    @select-module="selectCaseDesignModule"
  />
</ResponsiveDrawer>
```

- [ ] **Step 14: Add exact responsive CSS and focus rules**

Append to `apps/web/src/styles/studio-canvas.css`:

```css
.workspace.is-studio-case-design {
  min-width: 0;
  min-height: 0;
  display: block;
  padding: 0;
  overflow: hidden;
  background: var(--surface-page);
}

.studio-case-design-canvas {
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  container-type: inline-size;
  background: var(--surface-panel);
}

.studio-case-design-canvas__stage {
  min-width: 0;
  min-height: 0;
  position: relative;
  overflow: hidden;
}

.studio-sidebar__nav {
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
}

.studio-workflow-header {
  min-height: 76px;
  padding: 10px 17px;
  display: grid;
  grid-template-columns: 210px minmax(0, 1fr) auto;
  align-items: center;
  gap: 18px;
  border-bottom: 1px solid var(--border-subtle);
  background: var(--surface-panel);
}

.studio-workflow-header__identity strong,
.studio-workflow-header__identity span {
  display: block;
}

.studio-workflow-header__identity strong {
  font-size: 14px;
  font-weight: 680;
}

.studio-workflow-header__identity span {
  margin-top: 4px;
  color: var(--text-muted);
  font-size: 11px;
}

.studio-workflow-header__steps {
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(5, minmax(78px, 1fr));
  gap: 5px;
}

.studio-workflow-header__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 7px;
}

.studio-workflow-step {
  min-width: 0;
  padding: 8px;
  border: 0;
  border-top: 2px solid var(--border-regular);
  color: var(--text-muted);
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  text-align: left;
}

.studio-workflow-step:disabled {
  cursor: not-allowed;
  opacity: 0.58;
}

.studio-workflow-step.is-active {
  border-color: var(--brand-primary);
  color: var(--brand-primary);
  background: var(--brand-primary-soft);
}

.studio-workflow-step.is-done {
  border-color: var(--status-success);
  color: var(--status-success-text);
}

.studio-workflow-step.is-error {
  border-color: var(--status-danger);
  color: var(--status-danger);
}

.studio-workflow-step strong {
  margin-left: 5px;
  font-size: inherit;
}

.studio-workflow-step small {
  display: block;
  margin-top: 3px;
  color: currentColor;
  font-size: 10px;
}

.studio-workflow-step:focus-visible,
.studio-source-actions button:focus-visible,
.studio-source-item__actions button:focus-visible,
.studio-case-context button:focus-visible,
.studio-delivery-options > button:focus-visible,
.studio-case-title-button:focus-visible,
.studio-global-task-status:focus-visible,
.studio-import-stage__source-toggle:focus-visible {
  outline: 3px solid var(--focus-ring-color);
  outline-offset: 2px;
}

.studio-import-stage {
  position: relative;
  min-width: 0;
  min-height: 0;
  height: 100%;
  display: grid;
  grid-template-columns: 230px minmax(0, 1fr);
  overflow: hidden;
  background: var(--surface-panel);
}

.studio-import-stage__source-toggle {
  display: none;
}

.studio-import-stage__sources {
  min-width: 0;
  padding: 14px 12px;
  overflow: auto;
  border-right: 1px solid var(--border-subtle);
  background: var(--surface-subtle);
}

.studio-import-stage__sources > header,
.studio-import-stage__canvas > header,
.studio-generation-stage__toolbar,
.studio-delivery-stage > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.studio-import-stage__sources > header span,
.studio-import-stage__canvas > header span,
.studio-delivery-stage > header span {
  color: var(--text-muted);
  font-size: 11px;
}

.studio-source-list {
  margin-top: 11px;
  display: grid;
  gap: 7px;
}

.studio-import-stage__source-empty {
  margin-top: 12px;
  padding: 18px 10px;
  border: 1px dashed var(--border-regular);
  border-radius: var(--radius-control);
  color: var(--text-muted);
  text-align: center;
}

.studio-source-item {
  min-width: 0;
  padding: 9px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--border-regular);
  border-radius: var(--radius-control);
  background: var(--surface-panel);
}

.studio-source-item.is-error,
.studio-source-item.is-reattach {
  border-color: var(--status-danger);
  background: var(--status-danger-soft);
}

.studio-source-item__kind {
  padding: 4px 6px;
  border-radius: 4px;
  color: var(--brand-primary);
  background: var(--brand-primary-soft);
  font-size: 10px;
}

.studio-source-item strong,
.studio-source-item span {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.studio-source-item span {
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 10px;
}

.studio-source-item__actions {
  display: flex;
  gap: 4px;
}

.studio-source-item__actions button,
.studio-import-stage__sources > header button {
  min-width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: var(--radius-control);
  color: var(--text-secondary);
  background: transparent;
}

.studio-import-stage__canvas {
  min-width: 0;
  padding: 18px;
  overflow: auto;
}

.studio-source-actions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: 16px;
}

.studio-source-actions button {
  min-height: 112px;
  padding: 14px;
  display: grid;
  justify-items: start;
  align-content: start;
  gap: 7px;
  border: 1px solid var(--border-regular);
  border-radius: var(--radius-panel);
  color: var(--text-primary);
  background: var(--surface-panel);
  text-align: left;
}

.studio-source-actions button:hover {
  border-color: var(--brand-primary-border);
  background: var(--brand-primary-subtle);
}

.studio-source-actions button > svg {
  color: var(--brand-primary);
}

.studio-source-actions button span {
  color: var(--text-muted);
  font-size: 11px;
}

.studio-analysis-status,
.studio-generation-stage__progress,
.studio-generation-stage__error {
  margin-top: 12px;
  padding: 11px 12px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 9px;
  border: 1px solid var(--border-regular);
  border-radius: var(--radius-control);
  background: var(--surface-subtle);
}

.studio-parsed-items {
  margin-top: 14px;
  padding: 12px;
  border: 1px solid var(--border-regular);
  border-radius: var(--radius-panel);
  background: var(--surface-panel);
}

.studio-parsed-items > header,
.studio-parsed-items li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.studio-parsed-items ul {
  margin: 9px 0 0;
  padding: 0;
  list-style: none;
}

.studio-parsed-items li {
  padding: 7px 0;
  border-top: 1px solid var(--border-subtle);
}

.studio-parsed-items li span {
  color: var(--brand-primary);
  font-size: 10px;
}

.studio-generation-stage {
  min-width: 0;
  min-height: 0;
  height: 100%;
  padding: 14px;
  display: grid;
  grid-template-rows: auto auto auto minmax(0, 1fr);
  gap: 10px;
  overflow: hidden;
}

.studio-generation-stage__count {
  text-align: right;
}

.studio-generation-stage__count span,
.studio-generation-stage__count strong {
  display: block;
}

.studio-generation-stage__count span {
  color: var(--text-muted);
  font-size: 10px;
}

.studio-generation-stage__content {
  min-width: 0;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(320px, 0.85fr) minmax(480px, 1.4fr);
  gap: 10px;
  overflow: hidden;
}

.studio-generation-stage__drafts,
.studio-generation-stage__results {
  min-width: 0;
  min-height: 0;
  overflow: auto;
  border: 1px solid var(--border-regular);
  border-radius: var(--radius-panel);
  background: var(--surface-panel);
}

.studio-generation-stage__error {
  border-color: var(--status-danger);
  color: var(--status-danger-text);
  background: var(--status-danger-soft);
}

.studio-generated-web-table .el-table__row.is-selected-case > td {
  background: var(--brand-primary-subtle);
}

.studio-generated-web-table .el-table__row.is-selected-case > td:first-child {
  box-shadow: inset 2px 0 var(--brand-primary);
}

.studio-case-title-button {
  max-width: 100%;
  padding: 2px 0;
  overflow: hidden;
  border: 0;
  color: var(--text-primary);
  background: transparent;
  cursor: pointer;
  font: inherit;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.studio-generation-advanced-summary {
  display: grid;
  gap: 8px;
  margin: 0 0 16px;
}

.studio-generation-advanced-summary > div {
  padding: 10px;
  display: flex;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid var(--border-subtle);
}

.studio-generation-advanced-summary dt {
  color: var(--text-muted);
}

.studio-generation-advanced-summary dd {
  margin: 0;
  color: var(--text-primary);
  font-weight: 650;
}

.studio-review-stage {
  height: 100%;
  min-width: 0;
  min-height: 0;
  padding: 12px;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 10px;
  overflow: hidden;
}

.studio-review-stage__filters {
  display: flex;
  align-items: center;
  gap: 8px;
}

.studio-review-stage__filters .el-input {
  max-width: 260px;
}

.studio-review-inspector {
  min-width: 0;
  height: 100%;
  padding: 0 12px 14px;
  overflow: auto;
  border-left: 1px solid var(--border-subtle);
  background: var(--surface-subtle);
}

.studio-review-inspector__header {
  position: sticky;
  top: 0;
  z-index: 2;
  min-height: 50px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--border-subtle);
  background: var(--surface-subtle);
}

.studio-review-inspector__header > div {
  display: flex;
  align-items: center;
  gap: 7px;
}

.studio-review-inspector__selection,
.studio-review-inspector__score,
.studio-review-inspector section {
  margin-top: 12px;
  padding: 11px;
  border: 1px solid var(--border-regular);
  border-radius: var(--radius-panel);
  background: var(--surface-panel);
}

.studio-review-inspector__selection span,
.studio-review-inspector__selection strong,
.studio-review-inspector__score span,
.studio-review-inspector__score strong {
  display: block;
}

.studio-review-inspector__score {
  color: #fff;
  background: var(--text-primary);
}

.studio-review-inspector__score strong {
  margin-top: 4px;
  font-size: 24px;
}

.studio-review-inspector section > header {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.studio-review-inspector section article {
  padding: 8px 0;
  border-top: 1px solid var(--border-subtle);
}

.studio-review-inspector section article p {
  margin: 4px 0 7px;
  color: var(--text-muted);
  line-height: 1.5;
}

.studio-review-inspector__finding-meta,
.studio-review-inspector__finding-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.studio-review-inspector footer {
  padding-top: 12px;
  display: flex;
  justify-content: flex-end;
  gap: 7px;
}

.studio-case-context {
  min-width: 0;
  height: 100%;
  padding: 14px 11px;
  overflow: auto;
  border-right: 1px solid var(--border-subtle);
  background: var(--surface-subtle);
}

.studio-case-context section + section {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--border-subtle);
}

.studio-case-context section > header {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
  color: var(--text-muted);
  font-size: 11px;
}

.studio-case-context__sources {
  display: grid;
  gap: 6px;
}

.studio-case-context__sources > button {
  min-width: 0;
  width: 100%;
  padding: 8px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 7px;
  border: 1px solid transparent;
  border-radius: var(--radius-control);
  color: var(--text-primary);
  background: var(--surface-panel);
  text-align: left;
}

.studio-case-context__sources > button.is-active {
  border-color: var(--brand-primary-border);
  background: var(--brand-primary-soft);
}

.studio-case-context__sources > button > span {
  color: var(--brand-primary);
  font-size: 10px;
}

.studio-case-context__sources strong,
.studio-case-context__sources small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.studio-case-context__sources small {
  margin-top: 2px;
  color: var(--text-muted);
}

.studio-case-context section > button {
  width: 100%;
  height: 31px;
  padding: 0 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border: 0;
  border-radius: var(--radius-control);
  color: var(--text-secondary);
  background: transparent;
}

.studio-case-context section > button.is-active {
  color: var(--brand-primary);
  background: var(--brand-primary-soft);
}

.studio-delivery-stage {
  max-width: 920px;
  margin: 0 auto;
  padding: 24px;
}

.studio-delivery-options {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: 18px;
}

.studio-delivery-options > button {
  min-height: 120px;
  padding: 16px;
  display: grid;
  justify-items: start;
  align-content: start;
  gap: 8px;
  border: 1px solid var(--border-regular);
  border-radius: var(--radius-panel);
  color: var(--text-primary);
  background: var(--surface-panel);
  text-align: left;
}

.studio-delivery-options > button.is-active {
  border-color: var(--brand-primary);
  box-shadow: 0 0 0 2px var(--focus-ring-color);
}

.studio-delivery-options > button svg {
  color: var(--brand-primary);
}

.studio-delivery-options > button span,
.studio-delivery-execution > div > span {
  color: var(--text-muted);
  font-size: 11px;
}

.studio-delivery-execution {
  margin-top: 12px;
  padding: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border: 1px solid var(--border-regular);
  border-radius: var(--radius-control);
  background: var(--surface-subtle);
}

.studio-delivery-execution label,
.studio-delivery-execution label span,
.studio-delivery-execution > div {
  display: flex;
  align-items: center;
  gap: 7px;
}

.studio-delivery-warnings,
.studio-delivery-blockers {
  margin: 12px 0 0;
  padding: 10px 12px 10px 28px;
  border-radius: var(--radius-control);
}

.studio-delivery-warnings {
  color: var(--status-warning-text);
  background: var(--status-warning-soft);
}

.studio-delivery-risk-summary {
  margin-top: 12px;
  padding: 12px;
  border: 1px solid var(--status-warning);
  border-radius: var(--radius-control);
  background: var(--status-warning-soft);
}

.studio-delivery-risk-summary ol {
  margin: 8px 0 0;
  padding-left: 20px;
}

.studio-delivery-risk-summary li {
  margin-top: 6px;
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.studio-delivery-blockers {
  color: var(--status-danger-text);
  background: var(--status-danger-soft);
}

.studio-delivery-stage__hint {
  margin-top: 16px;
  color: var(--text-muted);
  text-align: right;
}

.studio-workflow-state {
  min-height: 180px;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 8px;
  padding: 24px;
  color: var(--text-muted);
  text-align: center;
}

.studio-workflow-state strong {
  color: var(--text-primary);
  font-size: 14px;
}

.studio-workflow-state p {
  max-width: 420px;
  margin: 0;
  line-height: 1.6;
}

.studio-workflow-state.is-error {
  color: var(--status-danger-text);
}

.studio-workflow-state.is-partial {
  color: var(--status-warning-text);
}

.studio-offline-notice {
  min-height: 0;
  margin: 10px 14px 0;
  padding: 9px 12px;
  grid-template-columns: auto auto minmax(0, 1fr);
  justify-content: start;
  border: 1px solid var(--status-info);
  border-radius: var(--radius-control);
  color: var(--status-info-text);
  background: var(--status-info-soft);
  text-align: left;
}

.studio-global-task-status {
  max-width: 240px;
  height: 30px;
  padding: 0 9px;
  overflow: hidden;
  border: 1px solid var(--brand-primary-border);
  border-radius: var(--radius-control);
  color: var(--brand-primary);
  background: var(--brand-primary-soft);
  cursor: pointer;
  font: inherit;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@container (max-width: 860px) {
  .studio-generation-stage__content {
    grid-template-columns: minmax(0, 1fr);
    overflow: auto;
  }

  .studio-generation-stage__drafts,
  .studio-generation-stage__results {
    min-height: 320px;
  }
}

@media (min-width: 1440px) {
  :root {
    --context-pane-width: 220px;
    --inspector-pane-width: 300px;
  }
}

@media (min-width: 1180px) and (max-width: 1439px) {
  :root {
    --context-pane-width: 196px;
    --inspector-pane-width: 280px;
  }

  .studio-global-search {
    width: 190px;
  }
}

@media (max-width: 1179px) {
  .studio-app-shell.has-context .studio-app-shell__workspace,
  .studio-app-shell.has-context.has-inspector .studio-app-shell__workspace,
  .studio-app-shell.has-inspector:not(.has-context) .studio-app-shell__workspace {
    grid-template-columns: minmax(0, 1fr);
  }

  .studio-global-search {
    width: 160px;
  }

  .studio-import-stage {
    grid-template-columns: minmax(0, 1fr);
  }

  .studio-import-stage__source-toggle {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: max-content;
    margin: 10px 14px 0;
    padding: 7px 10px;
    border: 1px solid var(--border-regular);
    border-radius: var(--radius-control);
    color: var(--text-secondary);
    background: var(--surface-panel);
  }

}

@media (max-width: 899px) {
  .studio-global-topbar__breadcrumb span,
  .studio-global-topbar__avatar {
    display: none;
  }

  .studio-workflow-header {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .studio-workflow-header__steps {
    grid-column: 1 / -1;
    overflow-x: auto;
  }

  .studio-global-search {
    display: none;
  }

  .studio-global-task-status {
    max-width: 150px;
  }

  .studio-source-actions,
  .studio-delivery-options {
    grid-template-columns: minmax(0, 1fr);
  }

  .studio-delivery-stage {
    padding: 16px;
  }

  .studio-delivery-execution {
    align-items: stretch;
    flex-direction: column;
  }

  .studio-generation-stage {
    padding: 10px;
  }
}
```

- [ ] **Step 15: Replace remaining legacy Web empty panes with shared states**

Import `WorkflowStateView` into `RequirementImportStage.vue`. Insert this first-use state as the first child of `.studio-import-stage__canvas`; keep the three source command cards after it as secondary alternatives:

```vue
<WorkflowStateView
  v-if="sources.length === 0"
  variant="empty"
  title="还没有需求来源"
  description="添加需求文档、Figma 或 OpenAPI 后开始解析"
  action-label="添加需求来源"
  @action="emit('pickFiles')"
/>
```

Inside `.studio-review-stage`, immediately after the `GeneratedWebCaseTable` from Task 7, add:

```vue
<WorkflowStateView
  v-else
  variant="filtered-empty"
  title="没有符合条件的用例"
  description="当前筛选条件没有匹配结果"
  action-label="清除筛选"
  @action="Object.assign(webPreviewFilter, { module: 'ALL', caseType: 'ALL', keyword: '' })"
/>
```

Keep source-local and batch-local errors inside their stage components instead of replacing them with global messages.

- [ ] **Step 16: Update structure assertions for the new shell and states**

At the top of `apps/web/src/appShellStructure.test.ts`, replace the existing `extractApiWorkspaceBlock` helper with a version that locates the workspace through its class attribute. The opening `<main>` becomes multiline in this task, so the old exact `<main class="workspace">` anchor is no longer valid:

```ts
const extractApiWorkspaceBlock = () => {
  const workspaceClass = 'class="workspace"'
  const workspaceClassIndex = appSource.indexOf(workspaceClass)
  expect(workspaceClassIndex).toBeGreaterThan(-1)

  const workspaceStart = appSource.lastIndexOf('<main', workspaceClassIndex)
  const workspaceOpeningEnd = appSource.indexOf('>', workspaceStart)
  expect(workspaceStart).toBeGreaterThan(-1)
  expect(workspaceOpeningEnd).toBeGreaterThan(workspaceClassIndex)

  const start = appSource.indexOf(`<template v-if="activeNav === 'api'">`, workspaceOpeningEnd)
  const end = appSource.indexOf(`<template v-else-if="activeNav === 'aiConfig'">`, start)
  expect(start).toBeGreaterThan(workspaceOpeningEnd)
  expect(end).toBeGreaterThan(start)

  return appSource.slice(start, end)
}
```

Add to `apps/web/src/appShellStructure.test.ts`:

```ts
it('uses the Studio Canvas shell and responsive contextual surfaces', () => {
  expect(appSource).toContain("import AppShell from './components/shell/AppShell.vue'")
  expect(appSource).toContain("import GlobalTopbar from './components/shell/GlobalTopbar.vue'")
  expect(appSource).toContain("import WorkflowStateView from './components/workflow/WorkflowStateView.vue'")
  expect(appSource).toContain('data-testid="inspector-drawer-button"')
  expect(appSource).toContain('data-testid="context-pane-drawer-button"')
  expect(appSource).toContain('v-model="narrowNavigationVisible"')
  expect(appSource).toContain('class="studio-offline-notice"')
  expect(appSource).toContain("import ResponsiveDrawer from './components/workflow/ResponsiveDrawer.vue'")
  expect(appSource).toContain(':sidebar-collapsed="isNarrowViewport ? !narrowNavigationVisible : railCollapsed"')
  expect(appSource).toContain('class="studio-sync-notice"')
  expect(appSource).not.toContain('class="rail-toggle icon-button"')
})
```

Replace the old `keeps requirement file selection...` and `places the Web requirement flow...` tests with:

```ts
it('renders requirement source actions inside the dedicated import stage', () => {
  expect(appSource).toContain("import RequirementImportStage from './components/workflow/RequirementImportStage.vue'")
  expect(appSource).toContain('<RequirementImportStage')
  expect(appSource).toContain('@pick-files="pickWebCaseFiles"')
  expect(appSource).toContain('@import-figma="promptFigmaDesignImport"')
  expect(appSource).toContain('@pick-open-api="pickOpenApiFiles(\'requirement\')"')
  expect(appSource).not.toContain('web-flow-card')
  expect(appSource).not.toContain('requirement-flow-steps')
})

it('keeps the OpenAPI interface picker and Web source importer as separate workflows', () => {
  expect(appSource).toContain('class="interface-upload-panel"')
  expect(appSource).toContain('选择 OpenAPI 文件')
  expect(appSource).toContain('<RequirementImportStage')
  expect(appSource).not.toContain('class="requirement-flow-head"')
})

it('keeps the legacy interface flow reachable outside the Web Studio canvas', () => {
  const apiBlock = extractApiWorkspaceBlock()
  const interfaceStart = apiBlock.indexOf(
    `<template v-if="generationKind === 'interface'">`
  )
  const studioStart = apiBlock.indexOf(
    '<section v-else class="studio-case-design-canvas">'
  )
  const nextNavStart = apiBlock.indexOf(
    `<template v-else-if="activeNav === 'cases'">`,
    studioStart
  )

  expect(interfaceStart).toBeGreaterThan(-1)
  expect(studioStart).toBeGreaterThan(interfaceStart)
  expect(nextNavStart).toBeGreaterThan(studioStart)

  const interfaceBlock = apiBlock.slice(interfaceStart, studioStart)
  const studioBlock = apiBlock.slice(studioStart, nextNavStart)

  expect(interfaceBlock.trimEnd()).toMatch(/<\/template>$/)
  expect(interfaceBlock).toContain('class="topbar"')
  expect(interfaceBlock).toContain('class="control-strip"')
  expect(interfaceBlock).toContain('class="main-grid"')
  expect(interfaceBlock).toContain('v-model="generationKind"')
  expect(interfaceBlock).toContain("{ label: '接口用例', value: 'interface' }")
  expect(interfaceBlock).toContain("{ label: 'Web 用例', value: 'web_automation' }")
  expect(interfaceBlock).toContain('class="interface-upload-panel"')
  expect(interfaceBlock).toContain('@click="pickOpenApiFiles')
  expect(interfaceBlock).toContain('@click="generateCases"')
  expect(interfaceBlock).toContain('@click="runAiReview"')
  expect(interfaceBlock).toContain('@click="saveCases"')
  expect(interfaceBlock).toContain('@click="transferGeneratedCasesToAutomation"')
  expect(interfaceBlock).toContain('@click="exportCsv"')

  expect(studioBlock).toContain('<WorkflowHeader')
  expect(studioBlock).toContain('<RequirementImportStage')
  expect(studioBlock).toContain('<AiGenerationStage')
  expect(studioBlock).toContain('<CaseDeliveryStage')
  expect(studioBlock).toContain(":mode-action=\"{ label: '接口用例', disabled: saveCasesLoading }\"")
  expect(studioBlock).toContain('@mode-action="generationKind = \'interface\'"')
  expect(studioBlock).not.toContain('v-model="generationKind"')
  expect(studioBlock).not.toContain('interface-upload-panel')
  expect(studioBlock).not.toContain("{ label: '接口用例', value: 'interface' }")
})
```

Delete the obsolete test that slices `requirement-flow-head`, and delete every remaining assertion that requires `web-flow-card`, `requirement-flow-steps`, or the old rail toggle.

- [ ] **Step 17: Run viewport, state, drawer, structure, accessibility, and build checks**

Run:

```bash
npm test -- --run src/components/workflow/WorkflowStateView.test.ts src/components/workflow/ResponsiveDrawer.test.ts src/components/workflow/RequirementSourceList.test.ts src/ui/useViewportMode.test.ts src/ui/useOnlineStatus.test.ts src/components/workflow/RequirementImportStage.test.ts src/components/workflow/WorkflowHeader.test.ts src/components/shell/GlobalTopbar.test.ts src/appShellStructure.test.ts
npm run build
```

Expected: tests PASS; no icon-only control in the new components lacks an accessible label.

- [ ] **Step 18: Commit responsive and state behavior**

```bash
git add apps/web/src/components/workflow/WorkflowStateView.vue apps/web/src/components/workflow/WorkflowStateView.test.ts apps/web/src/components/workflow/ResponsiveDrawer.vue apps/web/src/components/workflow/ResponsiveDrawer.test.ts apps/web/src/components/workflow/RequirementSourceList.vue apps/web/src/components/workflow/RequirementSourceList.test.ts apps/web/src/ui/useViewportMode.ts apps/web/src/ui/useViewportMode.test.ts apps/web/src/ui/useOnlineStatus.ts apps/web/src/ui/useOnlineStatus.test.ts apps/web/src/components/workflow/RequirementImportStage.vue apps/web/src/components/workflow/WorkflowHeader.vue apps/web/src/components/shell/GlobalTopbar.vue apps/web/src/components/shell/GlobalTopbar.test.ts apps/web/src/styles/studio-canvas.css apps/web/src/App.vue apps/web/src/appShellStructure.test.ts
git commit -m "feat: add responsive Studio Canvas states"
```

## Task 11: Add Browser Visual Regression, Remove Obsolete Styles, And Refresh Design Baseline

**Files:**
- Modify: `apps/web/package.json`
- Modify: `apps/web/package-lock.json`
- Create: `apps/web/playwright.config.ts`
- Create: `apps/web/e2e/fixtures/caseDesignDraft.ts`
- Create: `apps/web/e2e/studio-canvas.spec.ts`
- Create: `apps/web/e2e/studio-canvas.spec.ts-snapshots/*-chromium-darwin.png`
- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/styles.css`
- Delete: `apps/web/src/vueComponentHarness.test.ts`
- Modify: `DESIGN.md`

- [ ] **Step 1: Install Playwright and add the script**

Run from `apps/web`:

```bash
npm install --save-dev @playwright/test@^1.61.0 @axe-core/playwright@^4.10.2
npx playwright install chromium
```

Add to `apps/web/package.json` scripts:

```json
{
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc --noEmit && vite build",
    "test": "vitest",
    "test:e2e": "playwright test"
  }
}
```

Expected: `package-lock.json` records Playwright, axe-core, and Chromium is available locally.

- [ ] **Step 2: Add Playwright configuration**

Create `apps/web/playwright.config.ts`:

```ts
import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure'
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1440, height: 900 }
      }
    }
  ],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 4173',
    url: 'http://127.0.0.1:4173/',
    reuseExistingServer: true,
    timeout: 120_000
  }
})
```

- [ ] **Step 3: Create deterministic workflow and state fixtures**

Create `apps/web/e2e/fixtures/caseDesignDraft.ts`:

```ts
import type { AiAnalysisRun, AiDraftTestPoint } from '../../src/domain/aiPlatformRepository'
import type { CaseDesignDraftSnapshot } from '../../src/domain/caseDesignDraftRepository'
import type { GeneratedWebAutomationCase } from '../../src/domain/webAutomationCase'

export type CaseDesignFixtureVariant =
  | 'default'
  | 'first-empty'
  | 'loading'
  | 'local-error'
  | 'partial'

const projectName = '华东区域跨境订单履约与退款协同平台'
const sourceFile = '跨境订单退款状态回补与最终一致性需求说明.md'

const analysisRun = (status: 'running' | 'succeeded'): AiAnalysisRun => ({
  runId: 'analysis-fixture',
  projectId: 1,
  targetCaseKind: 'web_automation',
  templateId: 'web-default',
  analysisStrategy: 'full',
  status,
  workerStatus: status,
  coverageStatus: status === 'succeeded' ? 'complete' : 'running',
  totalChunks: 3,
  completedChunks: status === 'succeeded' ? 3 : 2,
  inputSummary: {},
  outputSummary: {},
  createdAt: '2026-07-16T13:00:00.000Z',
  updatedAt: '2026-07-16T14:30:00.000Z'
})

const draftPoint = (id: string, title: string, pageOrFeature: string): AiDraftTestPoint => ({
  pointId: id,
  runId: 'analysis-fixture',
  projectId: 1,
  sourceDocumentId: 'document-1',
  sourceChunkId: `chunk-${id}`,
  title,
  module: '退款与售后协同处理',
  pageOrFeature,
  caseKind: 'web_automation',
  testType: 'functional',
  priority: 'P1',
  objective: title,
  steps: '打开退款详情页\n检查订单与退款单状态',
  expectedResult: '两个状态最终一致',
  sourceFile,
  sourceText: title,
  confidenceScore: 0.91,
  inferred: false,
  reviewStatus: 'confirmed',
  metadata: {},
  createdAt: '2026-07-16T13:10:00.000Z',
  updatedAt: '2026-07-16T13:20:00.000Z'
})

const generatedWebCase = (
  sequence: number,
  title: string,
  path: string
): GeneratedWebAutomationCase => {
  const caseId = `WEB-AI-${String(sequence).padStart(4, '0')}`
  return {
    id: caseId,
    row: {
      caseId,
      project: projectName,
      module: '退款与售后协同处理',
      pageName: '跨境订单退款详情与状态追踪页面',
      title,
      caseType: 'AI用例',
      precondition: '跨境订单已支付且退款申请已受理',
      startUrl: path,
      browser: 'chromium',
      viewport: '1440x900',
      testData: '{}',
      steps: `goto ${path}\nassertVisible .refund-status`,
      locators: '.refund-status',
      assertions: 'assertVisible .refund-status',
      waitStrategy: 'networkidle',
      screenshotStrategy: 'on_failure',
      priority: 'P1',
      executionResult: '未执行',
      executor: '',
      executedAt: '',
      defectId: '',
      tags: '退款与售后协同处理,Web自动化',
      sourceFile,
      remark: '验证长链路状态最终一致'
    },
    metadata: {
      sourcePointId: `point-${sequence}`,
      sourceFile,
      sourceDocumentType: 'markdown',
      pageName: '跨境订单退款详情与状态追踪页面',
      startUrl: path,
      browser: 'chromium',
      viewport: '1440x900',
      steps: [
        { id: `${caseId}-step-1`, name: '打开退款页', action: 'goto', targetType: 'url', target: path },
        { id: `${caseId}-step-2`, name: '检查退款状态', action: 'assertVisible', targetType: 'locator', target: '.refund-status' }
      ],
      locators: [{ id: `${caseId}-locator-1`, name: '退款状态', locatorType: 'css', value: '.refund-status', confirmed: true }],
      assertions: [{ id: `${caseId}-assertion-1`, name: '退款状态可见', type: 'visible', target: '.refund-status' }],
      variables: {},
      runOrder: sequence
    }
  }
}

const generatedCases = [
  generatedWebCase(1, '退款处理超时后订单状态、退款单状态与支付渠道状态的最终一致性回补', '/refund/1'),
  generatedWebCase(2, '重复回调与网络重试同时发生时仅执行一次退款状态回补', '/refund/2')
]

export const caseDesignDraftFixture = (
  stage: CaseDesignDraftSnapshot['activeStage'],
  variant: CaseDesignFixtureVariant = 'default'
): CaseDesignDraftSnapshot => {
  const empty = variant === 'first-empty'
  const partial = variant === 'partial'
  const hasGenerated = stage === 'review' || stage === 'deliver' || partial
  const parsed = stage !== 'import' || variant === 'loading'
  return {
    version: 1,
    activeStage: stage,
    generationKind: 'web_automation',
    projectName,
    analysisRunId: '',
    analysisRun: parsed ? analysisRun(variant === 'loading' ? 'running' : 'succeeded') : null,
    requirementReviewRun: null,
    caseReviewRun: stage === 'review' || stage === 'deliver' ? {
      runId: 'review-fixture',
      graphId: 'review-graph-fixture',
      status: 'merge_ready',
      nodeCount: 4
    } : null,
    activeReviewStage: 'case',
    reviewAttempted: stage === 'review' || stage === 'deliver',
    selectedCaseId: hasGenerated ? generatedCases[0].id : '',
    sources: empty ? [] : [{ name: sourceFile, kind: 'document', text: '# 退款状态回补', needsReattach: false }],
    sourceErrors: variant === 'local-error' ? { [sourceFile]: '文档授权已过期，请替换或重新授权' } : {},
    extractedRequirementPoints: [],
    aiDraftPoints: parsed ? [
      draftPoint('point-1', '退款状态最终一致性', '退款详情'),
      draftPoint('point-2', '重复回调幂等处理', '支付回调')
    ] : [],
    generatedCases: [],
    generatedWebCases: hasGenerated ? generatedCases : [],
    analysisError: '',
    generationError: partial ? '第二批生成失败，已保留第一批结果' : '',
    reviewError: '',
    review: {
      insight: {
        stage: 'case',
        summary: '核心链路已覆盖，仍需确认退款状态回补时限。',
        suggestions: [{
          findingId: 'coverage_gap:refund-consistency',
          category: 'coverage_gap',
          title: '退款最终一致性覆盖缺口',
          detail: '退款模块需要校验订单与退款单状态一致',
          priority: 'P1'
        }]
      },
      qualityScores: [
        { qualityDimension: 'coverage', score: 0.92, scoreLevel: 'good' },
        { qualityDimension: 'testability', score: 0.84, scoreLevel: 'good' }
      ],
      conflicts: [{ conflictId: 'conflict-1', conflictType: 'timeout_definition', targetKey: 'WEB-AI-0001', resolutionStatus: 'unresolved' }],
      findingDecisions: {
        'WEB-AI-0001': { 'coverage_gap:refund-consistency': 'pending' },
        'WEB-AI-0002': { 'coverage_gap:refund-consistency': 'pending' }
      }
    },
    updatedAt: '2026-07-16T14:32:00.000Z'
  }
}
```

- [ ] **Step 4: Write browser interaction, accessibility, and screenshot-matrix tests**

Create `apps/web/e2e/studio-canvas.spec.ts`:

```ts
import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { CASE_DESIGN_DRAFT_STORAGE_KEY } from '../src/domain/caseDesignDraftRepository'
import {
  TEMPLATE_CONFIG_META_STORAGE_KEY,
  TEMPLATE_CONFIG_STORAGE_KEY,
  defaultTemplateConfig
} from '../src/domain/templateConfigRepository'
import {
  caseDesignDraftFixture,
  type CaseDesignFixtureVariant
} from './fixtures/caseDesignDraft'

const seedDraft = async (
  page: Page,
  stage: 'import' | 'parse' | 'generate' | 'review' | 'deliver',
  variant: CaseDesignFixtureVariant = 'default',
  syncConflict = false
) => {
  await page.addInitScript(({ key, value, templateKey, metaKey, template, conflict }) => {
    if (!localStorage.getItem(key)) localStorage.setItem(key, JSON.stringify(value))
    if (conflict) {
      if (!localStorage.getItem(templateKey)) localStorage.setItem(templateKey, JSON.stringify(template))
      if (!localStorage.getItem(metaKey)) {
        localStorage.setItem(metaKey, JSON.stringify({ updatedAt: '2026-07-16T15:00:00.000Z' }))
      }
    }
  }, {
    key: CASE_DESIGN_DRAFT_STORAGE_KEY,
    value: caseDesignDraftFixture(stage, variant),
    templateKey: TEMPLATE_CONFIG_STORAGE_KEY,
    metaKey: TEMPLATE_CONFIG_META_STORAGE_KEY,
    template: defaultTemplateConfig(),
    conflict: syncConflict
  })
}

const mockBackend = async (page: Page, syncConflict = false) => {
  const template = defaultTemplateConfig()
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/projects') {
      await route.fulfill({ json: { items: [{ id: 1, code: 'PROJECT', name: '默认项目' }] } })
      return
    }
    if (url.pathname === '/api/projects/1/case-template/interface') {
      await route.fulfill({
        json: {
          ...template,
          kind: 'interface',
          updatedAt: syncConflict ? '2026-07-15T10:00:00.000Z' : '2026-07-16T16:00:00.000Z'
        }
      })
      return
    }
    if (url.pathname === '/api/projects/1/interface-cases') {
      await route.fulfill({ json: { items: [], total: 0 } })
      return
    }
    await route.fulfill({ status: 503, json: { message: 'fixture backend unavailable' } })
  })
}

const openFixture = async (options: {
  page: Page
  width: number
  height: number
  stage: 'import' | 'parse' | 'generate' | 'review' | 'deliver'
  variant?: CaseDesignFixtureVariant
  syncConflict?: boolean
}) => {
  await options.page.setViewportSize({ width: options.width, height: options.height })
  await seedDraft(options.page, options.stage, options.variant, Boolean(options.syncConflict))
  await mockBackend(options.page, Boolean(options.syncConflict))
  await options.page.goto('/api')
  await expect(options.page.locator('.studio-case-design-canvas')).toBeVisible()
}

const expectNoAxeViolations = async (page: Page, selectors: string[]) => {
  const builder = new AxeBuilder({ page })
  for (const selector of selectors) builder.include(selector)
  const results = await builder.analyze()
  expect(results.violations).toEqual([])
}

test('wide shell covers expanded, collapsed, review, and delivery states', async ({ page }) => {
  await openFixture({ page, width: 1440, height: 900, stage: 'import' })
  await expect(page.locator('.studio-sidebar')).toHaveCSS('width', '168px')
  await expect(page).toHaveScreenshot('wide-import-expanded-1440.png')
  await page.getByRole('button', { name: '收起侧边栏' }).click()
  await expect(page.locator('.studio-sidebar')).toHaveCSS('width', '56px')
  await expect(page).toHaveScreenshot('wide-import-collapsed-1440.png')
  await page.reload()
  await expect(page.locator('.studio-sidebar')).toHaveCSS('width', '56px')

  await page.evaluate(({ key, value }) => localStorage.setItem(key, JSON.stringify(value)), {
    key: CASE_DESIGN_DRAFT_STORAGE_KEY,
    value: caseDesignDraftFixture('review')
  })
  await page.reload()
  await expect(page.getByLabel('需求与模块上下文')).toBeVisible()
  await expect(page.getByLabel('AI 质量评审')).toBeVisible()
  await expect(page).toHaveScreenshot('wide-review-context-inspector-1440.png')

  await page.evaluate(({ key, value }) => localStorage.setItem(key, JSON.stringify(value)), {
    key: CASE_DESIGN_DRAFT_STORAGE_KEY,
    value: caseDesignDraftFixture('deliver')
  })
  await page.reload()
  await expect(page.getByTestId('case-delivery-stage')).toBeVisible()
  await expect(page).toHaveScreenshot('wide-delivery-1440.png')
})

for (const stage of ['import', 'parse', 'generate', 'review', 'deliver'] as const) {
  test(`standard desktop renders ${stage} with long Chinese labels`, async ({ page }) => {
    await openFixture({ page, width: 1280, height: 800, stage })
    await expect(page.getByText('华东区域跨境订单履约与退款协同平台').first()).toBeVisible()
    if (stage === 'import') {
      const labelsFit = await page.locator('.studio-sidebar__label').evaluateAll(
        (labels) => labels.every((label) => label.scrollWidth <= label.clientWidth)
      )
      expect(labelsFit).toBe(true)
    }
    await expect(page).toHaveScreenshot(`standard-${stage}-1280.png`)
  })
}

test('compact desktop exposes context and inspector drawers and table overflow', async ({ page }) => {
  await openFixture({ page, width: 1024, height: 768, stage: 'review' })
  await expect(page.locator('.studio-sidebar')).toHaveCSS('width', '56px')

  const contextTrigger = page.getByTestId('context-pane-drawer-button')
  await contextTrigger.click()
  await expect(page.getByRole('dialog', { name: '需求与模块' })).toBeVisible()
  await expect(page).toHaveScreenshot('compact-context-drawer-1024.png')
  await page.keyboard.press('Escape')
  await expect(contextTrigger).toBeFocused()

  const inspectorTrigger = page.getByTestId('inspector-drawer-button')
  await inspectorTrigger.click()
  await expect(page.getByRole('dialog', { name: 'AI 质量评审' })).toBeVisible()
  await expect(page).toHaveScreenshot('compact-inspector-drawer-1024.png')
  await page.keyboard.press('Escape')

  const tableBody = page.locator('.studio-generated-web-table .el-scrollbar__wrap').first()
  expect(await tableBody.evaluate((element) => element.scrollWidth > element.clientWidth)).toBe(true)
  await tableBody.evaluate((element) => { element.scrollLeft = element.scrollWidth })
  await expect(page).toHaveScreenshot('compact-table-overflow-1024.png')
})

const stateCases: Array<{
  name: string
  stage: 'import' | 'parse' | 'generate' | 'review'
  variant: CaseDesignFixtureVariant
}> = [
  { name: 'first-empty', stage: 'import', variant: 'first-empty' },
  { name: 'loading', stage: 'parse', variant: 'loading' },
  { name: 'local-error', stage: 'import', variant: 'local-error' },
  { name: 'partial-success', stage: 'generate', variant: 'partial' }
]

for (const state of stateCases) {
  test(`captures ${state.name} workflow feedback`, async ({ page }) => {
    await openFixture({ page, width: 1280, height: 800, stage: state.stage, variant: state.variant })
    await expect(page).toHaveScreenshot(`state-${state.name}-1280.png`)
  })
}

test('captures filtered-empty feedback without clearing filters automatically', async ({ page }) => {
  await openFixture({ page, width: 1280, height: 800, stage: 'review' })
  await page.getByPlaceholder('搜索 Web 用例').fill('完全不存在的筛选关键字')
  await expect(page.getByText('没有符合条件的用例')).toBeVisible()
  await expect(page).toHaveScreenshot('state-filtered-empty-1280.png')
})

test('captures offline local-first feedback', async ({ page, context }) => {
  await openFixture({ page, width: 1280, height: 800, stage: 'import' })
  await context.setOffline(true)
  await expect(page.getByText('当前离线')).toBeVisible()
  await expect(page).toHaveScreenshot('state-offline-1280.png')
  await context.setOffline(false)
})

test('captures local-versus-backend sync conflict recovery', async ({ page }) => {
  await openFixture({ page, width: 1280, height: 800, stage: 'import', syncConflict: true })
  await expect(page.locator('.studio-sync-notice')).toBeVisible()
  await expect(page).toHaveScreenshot('state-sync-conflict-1280.png')
})

test('below 900px preserves navigation and basic commands through the navigation drawer', async ({ page }) => {
  await openFixture({ page, width: 800, height: 700, stage: 'import' })
  await expect(page.locator('.studio-app-shell > .studio-sidebar')).toHaveCount(0)
  await page.getByRole('button', { name: '展开侧边栏' }).click()
  const navigation = page.getByRole('dialog', { name: '主导航' })
  await expect(navigation).toBeVisible()
  await navigation.getByRole('button', { name: '用例库' }).click()
  await expect(page).toHaveURL(/\/cases$/)
})

test('legacy interface workspace keeps its resource-pane styling after cleanup', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  await mockBackend(page)
  await page.goto('/api')

  await expect(page.locator('.studio-case-design-canvas')).toHaveCount(0)
  await expect(page.locator('.interface-upload-panel')).toBeVisible()
  const resourcePane = page.locator('.resource-pane')
  await expect(resourcePane).toHaveCSS('border-right-style', 'solid')
  await expect(resourcePane).not.toHaveCSS('background-color', 'rgba(0, 0, 0, 0)')

  const workspaceBox = await page.locator('.studio-app-shell__workspace').boundingBox()
  const resourcePaneBox = await resourcePane.boundingBox()
  expect(workspaceBox).not.toBeNull()
  expect(resourcePaneBox).not.toBeNull()
  expect(resourcePaneBox!.y + resourcePaneBox!.height)
    .toBeLessThanOrEqual(workspaceBox!.y + workspaceBox!.height + 1)
})

test('delivery summary shows a shared coverage gap only once', async ({ page }) => {
  await openFixture({ page, width: 1280, height: 800, stage: 'deliver' })
  await expect(page.getByText(
    '覆盖缺口 · 退款最终一致性覆盖缺口 · 退款模块需要校验订单与退款单状态一致',
    { exact: true }
  )).toHaveCount(1)
})

test('whole Studio Canvas shell passes axe checks', async ({ page }) => {
  await openFixture({ page, width: 1440, height: 900, stage: 'import' })
  await expectNoAxeViolations(page, ['.studio-app-shell'])
})

test('the quiet mode action returns every Web stage to the legacy interface workspace', async ({ page }) => {
  await openFixture({ page, width: 1280, height: 800, stage: 'review' })
  const modeAction = page.getByTestId('workflow-mode-action')
  await modeAction.focus()
  await page.keyboard.press('Enter')
  await expect(page.locator('.studio-case-design-canvas')).toHaveCount(0)
  await expect(page.locator('.interface-upload-panel')).toBeVisible()
})

test('open responsive drawers restore focus and pass axe checks', async ({ page }) => {
  await openFixture({ page, width: 1024, height: 768, stage: 'review' })

  for (const drawer of [
    { triggerId: 'context-pane-drawer-button', title: '需求与模块' },
    { triggerId: 'inspector-drawer-button', title: 'AI 质量评审' }
  ]) {
    const trigger = page.getByTestId(drawer.triggerId)
    await trigger.click()

    const dialog = page.getByRole('dialog', { name: drawer.title })
    await expect(dialog).toBeVisible()
    await expectNoAxeViolations(page, ['[role="dialog"]'])

    await page.keyboard.press('Escape')
    await expect(dialog).not.toBeVisible()
    await expect(trigger).toBeFocused()
  }
})

test('review table and row actions support keyboard use and pass axe checks', async ({ page }) => {
  await openFixture({ page, width: 1280, height: 800, stage: 'review' })

  const caseAction = page.getByRole('button', { name: /查看 WEB-AI-0002/ })
  await caseAction.focus()
  await expect(caseAction).toBeFocused()
  await page.keyboard.press('Enter')

  const selectedRow = page
    .locator('.studio-generated-web-table tr.is-selected-case')
    .filter({ hasText: 'WEB-AI-0002' })
    .first()
  await expect(selectedRow).toBeVisible()

  const editAction = selectedRow.getByRole('button', { name: '编辑' })
  await editAction.focus()
  await page.keyboard.press('Enter')
  const editDialog = page.getByRole('dialog', { name: '编辑用例' })
  await expect(editDialog).toBeVisible()
  await expectNoAxeViolations(page, ['[role="dialog"]'])
  await page.keyboard.press('Escape')

  const removeAction = selectedRow.getByRole('button', { name: '删除 WEB-AI-0002' })
  await removeAction.focus()
  await expect(removeAction).toBeFocused()

  await expectNoAxeViolations(page, ['.studio-review-stage'])
})

test('workflow stepper supports keyboard navigation and passes axe checks', async ({ page }) => {
  await openFixture({ page, width: 1280, height: 800, stage: 'review' })

  const workflow = page.getByRole('navigation', { name: '测试设计流程' })
  await expect(
    workflow.getByRole('button', { name: '质量评审，当前阶段' })
  ).toHaveAttribute('aria-current', 'step')

  const importStep = workflow.getByRole('button', { name: '需求导入，已完成' })
  await importStep.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('requirement-import-stage')).toBeVisible()

  await expectNoAxeViolations(page, ['.studio-workflow-header__steps'])
})

test('delivery choices support keyboard use and pass axe checks', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await openFixture({ page, width: 1280, height: 800, stage: 'deliver' })

  const automation = page.getByRole('radio', { name: /转为 Web 自动化/ })
  await automation.focus()
  await page.keyboard.press('Space')
  await expect(automation).toHaveAttribute('aria-checked', 'true')

  const executeNow = page.getByRole('checkbox', { name: /转换完成后立即执行/ })
  await executeNow.focus()
  await page.keyboard.press('Space')
  await expect(executeNow).toBeChecked()

  const library = page.getByRole('radio', { name: /保存到用例库/ })
  await library.focus()
  await page.keyboard.press('Enter')
  await expect(library).toHaveAttribute('aria-checked', 'true')
  await expect(executeNow).not.toBeVisible()

  await expectNoAxeViolations(page, ['[data-testid="case-delivery-stage"]'])
})

// Accessibility and keyboard tests intentionally add no screenshot assertions.
// The approved visual baseline matrix remains exactly 19 PNG files.

test('central brand token updates custom and Element Plus primary surfaces', async ({ page }) => {
  await openFixture({ page, width: 1440, height: 900, stage: 'import' })
  const activeNav = page.locator('.studio-sidebar__item.is-active')
  const primary = page.getByTestId('workflow-primary-action')
  const before = await Promise.all([activeNav.evaluate((el) => getComputedStyle(el).color), primary.evaluate((el) => getComputedStyle(el).backgroundColor)])
  await page.evaluate(() => document.documentElement.style.setProperty('--brand-primary', '#0f766e'))
  const after = await Promise.all([activeNav.evaluate((el) => getComputedStyle(el).color), primary.evaluate((el) => getComputedStyle(el).backgroundColor)])
  expect(after[0]).not.toBe(before[0])
  expect(after[1]).not.toBe(before[1])
})
```

- [ ] **Step 5: Run browser tests to verify the screenshot baselines fail first**

First run the new browser tests without baselines:

```bash
npm run test:e2e
```

Expected: interaction and accessibility assertions run, and screenshot cases FAIL because the 19 approved baselines do not exist yet.

- [ ] **Step 6: Remove obsolete styles, then generate and inspect the final screenshot matrix**

In `apps/web/src/styles.css`, first replace this exact shared legacy block:

```css
.primary-rail,
.resource-pane {
  background: var(--surface-panel);
  border-right: 1px solid var(--border-soft);
}
```

with:

```css
.resource-pane {
  background: var(--surface-panel);
  border-right: 1px solid var(--border-soft);
}
```

This preserves the still-used resource pane declarations. Then delete the complete obsolete selector blocks rooted at:

```css
.app-shell
.app-shell.rail-is-collapsed
.primary-rail
.rail-brand
.rail-nav
.rail-item
.rail-toggle
.requirement-flow-panel
.web-flow-card
.requirement-flow-head
.requirement-flow-steps
.requirement-flow-step
.requirement-flow-step-marker
.requirement-flow-arrow
.requirement-flow-file-list
```

Retain both `.resource-pane` blocks, plus shared table, dialog, environment, automation, report, defect, and settings styles still used by unmigrated pages. Confirm no deleted selector remains in `App.vue`:

Also remove only the legacy `min-width: 1180px;` declaration from the existing `body` rule. Keep the remaining legacy body declarations that unmigrated modules still use; `studio-canvas.css` owns the new workflow surface.

```bash
rg -n 'class="app-shell"|class="primary-rail"|class="rail-brand|class="rail-item|web-flow-card|requirement-flow-step' apps/web/src/App.vue apps/web/src/styles.css
```

Expected: no old shell or Web flow matches.

Generate baselines only after the cleanup so the committed images represent the final CSS:

```bash
npm run test:e2e -- --update-snapshots
```

Expected: all browser tests pass and 19 PNG baselines are created under `apps/web/e2e/studio-canvas.spec.ts-snapshots/`.

Open all 19 images and verify the required matrix explicitly:

- `1440x900`: expanded import, collapsed import, review with both context panes, and delivery.
- `1280x800`: import, parse, generate, review, and delivery with long Chinese labels.
- `1024x768`: context drawer, inspector drawer, and horizontally scrolled table.
- Feedback states: loading, first-use empty, filtered-empty, local error, partial success, offline, and sync conflict.
- Every image: no blank canvas, clipped action labels, incoherent overlap, layout shift, or excessive brand color.

Then rerun without updating:

```bash
npm run test:e2e
```

Expected: PASS against the committed final baselines.

- [ ] **Step 7: Refresh the project design baseline**

Update `DESIGN.md` with these exact decisions:

```markdown
## Brand

- Personality: calm, precise, gentle, modern, and operational.
- Approved direction: Studio Canvas.
- Brand accent: configurable semantic token bundle; default `#6F52DD`.
- Status colors remain independent from the brand accent.

## Information architecture

- Primary sidebar: `56px` collapsed and `168px` expanded.
- Sidebar toggle: first command in the global top bar using `PanelLeftOpen` and `PanelLeftClose`.
- Core workflow: `需求导入 -> 内容解析 -> AI 生成 -> 质量评审 -> 保存 / 执行`.
- Context and inspector panes appear only for stages that need them and become drawers below `1180px`.

## Visual language

- Light theme first; `dark` and `system` remain contract values with safe light fallback until a dark palette is approved.
- Neutral surfaces dominate; brand color is limited to primary commands and AI focus.
- Standard radii are `6px` to `8px`; elevation is restrained.
- Typography and control sizes remain stable across desktop widths.
```

Merge this content into the existing matching sections rather than creating duplicate headings.

- [ ] **Step 8: Delete the temporary harness and run the complete verification suite**

Delete `apps/web/src/vueComponentHarness.test.ts`, then run:

```bash
npm test -- --run
npm run build
npm run test:e2e
git diff --check
```

Expected: all Vitest tests PASS, production build succeeds, Playwright screenshots match, and `git diff --check` reports no whitespace errors.

- [ ] **Step 9: Commit browser verification and final cleanup**

```bash
git add apps/web/package.json apps/web/package-lock.json apps/web/playwright.config.ts apps/web/e2e apps/web/src/styles.css apps/web/src/styles/studio-canvas.css DESIGN.md
git add -u apps/web/src/vueComponentHarness.test.ts
git commit -m "test: verify Studio Canvas workflow visually"
```

## Final Verification Checklist

- [ ] Run `npm test -- --run` from `apps/web`; expect all unit and component tests to pass.
- [ ] Run `npm run build` from `apps/web`; expect `vue-tsc` and Vite production build to pass.
- [ ] Run `npm run test:e2e` from `apps/web`; expect all Chromium interaction and screenshot tests to pass.
- [ ] Inspect all 19 `1440x900`, `1280x800`, `1024x768`, and feedback-state baselines for blank content, overflow, overlap, clipped long Chinese labels, or excessive brand color.
- [ ] Confirm changing only the central brand token bundle updates custom components and Element Plus primary styling.
- [ ] Confirm `dark` and `system` preferences safely resolve to `light` until dark tokens are implemented.
- [ ] Confirm the sidebar persists `56px / 168px` state and the toggle remains the first top-bar command.
- [ ] Confirm a first compact visit defaults to `56px`, a saved preference wins, and below `900px` navigation is available through the labelled drawer.
- [ ] Confirm source-local failure preserves other imported sources.
- [ ] Confirm batch generation failure preserves completed batches.
- [ ] Confirm review selection updates the contextual inspector.
- [ ] Confirm delivery stays locked until a case-quality review reaches `succeeded` or `failed`; a failed attempt can continue only with an explicit itemized warning.
- [ ] Confirm selected-case and bulk review decisions remain case-scoped, while conflicts, coverage gaps, and high-risk findings stay independently visible and prioritized.
- [ ] Confirm context and inspector drawers close on Escape, restore trigger focus, and reset after stage or viewport changes.
- [ ] Confirm axe reports no critical workflow violations and reduced-motion mode remains operable.
- [ ] Confirm loading, empty, filtered-empty, source-local error, partial success, offline, and sync-conflict states remain recoverable.
- [ ] Confirm save-only works without an environment.
- [ ] Confirm save-and-run validates the environment and executes only newly delivered cases.
- [ ] Confirm a failed execution does not remove cases already saved to the case library or automation library.
- [ ] Confirm unrelated case-library, automation, report, defect, AI configuration, and template pages remain reachable.
- [ ] Confirm the legacy interface-case workflow remains reachable outside the Web Studio Canvas, including OpenAPI import, generation, review, save, transfer, export, and visible `.resource-pane` styling.
