// Structural guards that survived the A3 retirement of the case-design page.
// Only assertions whose subject still exists in the current shell are kept here;
// the case-design/Studio Canvas assertions were retired together with their code.
// This test runs in Vitest's Node environment; the application tsconfig only
// includes browser types.
// @ts-expect-error Node's test-only filesystem API is intentionally not part of the browser type set.
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import appSource from './App.vue?raw'

const studioCanvasStyles = readFileSync(new URL('./styles/studio-canvas.css', import.meta.url), 'utf8')

describe('App shell structure', () => {
  it('keeps the local-first offline notice in the shell', () => {
    expect(appSource).toContain('class="studio-offline-notice"')
    expect(appSource).toContain('data-notice-level="info"')
    expect(appSource).toContain('离线模式')
    expect(appSource).toContain('联网后可继续同步')
    expect(appSource).toContain('v-model="narrowNavigationVisible"')
    expect(appSource).toContain(':sidebar-collapsed="isNarrowViewport ? !narrowNavigationVisible : railCollapsed"')
  })

  it('reserves a workspace grid row when the sync conflict notice is its first child', () => {
    expect(appSource).toContain('<div v-if="workspaceSyncConflict" class="studio-sync-notice"')
    expect(studioCanvasStyles).toMatch(
      /\.workspace:has\(> \.studio-sync-notice\)\s*\{\s*grid-template-rows:\s*auto 58px auto 1fr;/
    )
  })

  it('derives the topbar sync state with conflicts taking priority', () => {
    expect(appSource).toContain("const workspaceSyncState = computed<'synced' | 'syncing' | 'conflict'>(() => {")
    expect(appSource).toContain("if (workspaceSyncConflict.value) return 'conflict'")
    expect(appSource).toContain("if (workspaceSyncLoading.value) return 'syncing'")
    expect(appSource).toContain(':sync-state="workspaceSyncState"')
  })

  it('keeps the Web execution environment drawer runtime parameters', () => {
    const drawerStart = appSource.indexOf('v-model="environmentVisible"')
    const drawerEnd = appSource.indexOf('v-model="automationLogVisible"', drawerStart)
    const drawerBlock = appSource.slice(drawerStart, drawerEnd)

    for (const label of ['Web 访问入口', '浏览器', '视口', 'Headless', '默认等待超时', '页面加载超时', '等待页面状态', '失败重试次数']) {
      expect(drawerBlock).toContain(label)
    }
    expect(drawerBlock).toContain('认证方式')
    expect(drawerBlock).toContain('自动登录')
    expect(drawerBlock).not.toContain('<strong>高级登录态</strong>')
    expect(drawerBlock).toContain("environmentEditorKind === 'web'")
    expect(drawerBlock).toContain('运行参数')
    expect(drawerBlock).toContain('@click="saveEditingEnvironment()"')
  })

  it('allows editing Web automation cases with runtime environment settings', () => {
    const editStart = appSource.indexOf('v-model="editVisible"')
    const editEnd = appSource.indexOf('v-model="curlVisible"', editStart)
    // The block must stay bounded: a missing sentinel used to degrade this into
    // "whole rest of file", where every label matched outside the drawer.
    expect(editStart).toBeGreaterThan(-1)
    expect(editEnd).toBeGreaterThan(editStart)
    const editBlock = appSource.slice(editStart, editEnd)

    for (const label of ['运行环境', '应用访问地址', '浏览器', '视口', '等待策略', '截图策略', '失败重试次数']) {
      expect(editBlock).toContain(label)
    }
    expect(editBlock).toContain('applySelectedWebEnvironmentToEditRow')
    expect(editBlock).toContain('webExecutionEnvironmentLibrary.environments')
  })
})
