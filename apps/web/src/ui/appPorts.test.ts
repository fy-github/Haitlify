import { ref } from 'vue'
import {
  navigationPortKey,
  noticePortKey,
  workspacePortKey,
  type NavigationPort,
  type NoticePort,
  type WorkspacePort
} from './appPorts'

describe('app ports', () => {
  it('exposes distinct injection keys for each cross-feature capability', () => {
    expect(typeof navigationPortKey).toBe('symbol')
    expect(typeof workspacePortKey).toBe('symbol')
    expect(typeof noticePortKey).toBe('symbol')
    expect(navigationPortKey).not.toBe(workspacePortKey)
    expect(workspacePortKey).not.toBe(noticePortKey)
  })

  it('keeps port contracts narrow and command-oriented', () => {
    const navigation: NavigationPort = {
      currentNav: ref('home'),
      navigate: () => undefined
    }
    const workspace: WorkspacePort = {
      ownerId: ref(undefined),
      authenticated: ref(false),
      begin: () => () => false,
      fetch: async () => new Response(null, { status: 401 })
    }
    const notice: NoticePort = {
      info: () => undefined,
      warning: () => undefined,
      error: () => undefined
    }

    expect(navigation.currentNav.value).toBe('home')
    expect(workspace.begin('probe')()).toBe(false)
    expect(notice.info).toBeTypeOf('function')
  })
})
