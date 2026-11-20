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
})
