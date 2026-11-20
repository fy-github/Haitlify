import { describe, expect, it, vi } from 'vitest'
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
})
