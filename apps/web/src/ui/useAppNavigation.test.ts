import { describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick } from 'vue'
import { createAppNavigation } from './useAppNavigation'

describe('useAppNavigation', () => {
  it('derives initial navigation state from the current path', () => {
    const navigation = createAppNavigation({ pathname: '/automation/web' })

    expect(navigation.activeNav.value).toBe('automation')
    expect(navigation.automationKind.value).toBe('web')
  })

  it('keeps navigation state changes explicit', () => {
    const navigation = createAppNavigation({ pathname: '/' })
    const onChange = vi.fn()

    navigation.setActiveNav('reports', onChange)
    navigation.setAutomationKind('api', onChange)

    expect(navigation.activeNav.value).toBe('reports')
    expect(navigation.automationKind.value).toBe('api')
    expect(onChange).toHaveBeenCalledTimes(2)
  })

  it('rejects an old leave confirmation after a newer navigation intent', async () => {
    let release!: (confirmed: boolean) => void
    const navigation = createAppNavigation({ pathname: '/environments', guards: [{
      applies: (from, to) => from === 'environments' && to !== 'environments',
      capture: () => () => true,
      confirm: () => new Promise(resolve => { release = resolve })
    }] })
    const oldNavigation = vi.fn()
    navigation.navigateToNav('cases', 'skip', oldNavigation)
    navigation.navigateToNav('environments', 'skip')
    release(true)
    await Promise.resolve()
    expect(navigation.activeNav.value).toBe('environments')
    expect(oldNavigation).not.toHaveBeenCalled()
  })

  it('runs popstate side effects only after a guarded navigation is accepted', async () => {
    const location = { pathname: '/environments', href: 'https://app.example.test/environments' }
    const history = {
      pushState: vi.fn(),
      replaceState: vi.fn()
    }
    let release!: (confirmed: boolean) => void
    const afterPop = vi.fn()
    const navigation = createAppNavigation({ pathname: location.pathname, location, history, afterPop, guards: [{
      applies: (from, to) => from === 'environments' && to !== 'environments',
      capture: () => () => true,
      confirm: () => new Promise(resolve => { release = resolve })
    }] })

    location.pathname = '/automation/web'
    location.href = 'https://app.example.test/automation/web'
    navigation.handlePopState()
    expect(afterPop).not.toHaveBeenCalled()

    release(true)
    await Promise.resolve()
    expect(navigation.activeNav.value).toBe('automation')
    expect(navigation.automationKind.value).toBe('web')
    expect(afterPop).toHaveBeenCalledTimes(1)
  })

  it('restores the previous route when a popstate leave confirmation is cancelled', async () => {
    const location = { pathname: '/environments', href: 'https://app.example.test/environments' }
    const history = { pushState: vi.fn(), replaceState: vi.fn() }
    let release!: (confirmed: boolean) => void
    const afterPop = vi.fn()
    const navigation = createAppNavigation({ pathname: location.pathname, location, history, afterPop, guards: [{
      applies: (from, to) => from === 'environments' && to !== 'environments',
      capture: () => () => true,
      confirm: () => new Promise(resolve => { release = resolve })
    }] })

    location.pathname = '/automation/web'
    navigation.handlePopState()
    release(false)
    await Promise.resolve()

    expect(navigation.activeNav.value).toBe('environments')
    expect(afterPop).not.toHaveBeenCalled()
    expect(history.pushState).toHaveBeenCalledWith({}, '', '/environments')
  })

  it('preserves push, replace and skip history modes and Web monitor query fields', () => {
    const location = { pathname: '/', href: 'https://app.example.test/' }
    const updateLocation = (_state: unknown, _title: string, path: string) => {
      location.href = new URL(path, location.href).href
      location.pathname = new URL(location.href).pathname
    }
    const history = { pushState: vi.fn(updateLocation), replaceState: vi.fn(updateLocation) }
    const navigation = createAppNavigation({ pathname: '/', location, history })

    navigation.navigateToNav('reports')
    expect(history.pushState).toHaveBeenCalledWith({}, '', '/reports')
    navigation.navigateToNav('cases', 'replace')
    expect(history.replaceState).toHaveBeenCalledWith({}, '', '/cases')
    navigation.navigateToNav('automation', 'skip')
    expect(navigation.activeNav.value).toBe('automation')
    expect(history.pushState).toHaveBeenCalledTimes(1)
    location.pathname = '/automation/web'
    location.href = 'https://app.example.test/automation/web'
    navigation.writeWebMonitorQuery('monitor', 'run-1', 'replace')
    expect(history.replaceState).toHaveBeenLastCalledWith({}, '', '/automation/web?view=monitor&runId=run-1')
  })

  it('leaves the Web workspace when either the page or automation kind changes', async () => {
    const scope = effectScope()
    const onLeaveWebWorkspace = vi.fn()
    const navigation = scope.run(() => createAppNavigation({ pathname: '/automation/web', onLeaveWebWorkspace }))!
    try {
      navigation.setAutomationKind('api')
      await nextTick()
      expect(onLeaveWebWorkspace).toHaveBeenCalledTimes(1)

      navigation.setAutomationKind('web')
      await nextTick()
      expect(onLeaveWebWorkspace).toHaveBeenCalledTimes(1)

      navigation.setActiveNav('reports')
      await nextTick()
      expect(onLeaveWebWorkspace).toHaveBeenCalledTimes(2)
    } finally {
      scope.stop()
    }
  })
})
