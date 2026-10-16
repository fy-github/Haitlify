import { describe, expect, it } from 'vitest'
import { automationKindFromPath, automationPathFor, caseDesignKindFromPath, caseDesignPathFor, defaultAutomationNavKind, defaultNavKey, navKeyFromPath, navPathFor } from './appNavigation'

describe('appNavigation', () => {
  it('maps major modules to stable paths', () => {
    expect(navPathFor('api')).toBe('/api')
    expect(caseDesignPathFor('interface')).toBe('/api')
    expect(caseDesignPathFor('web_automation')).toBe('/web-cases')
    expect(navPathFor('cases')).toBe('/cases')
    expect(navPathFor('automation')).toBe('/automation/api')
    expect(automationPathFor('api')).toBe('/automation/api')
    expect(automationPathFor('web')).toBe('/automation/web')
    expect(navPathFor('reports')).toBe('/reports')
    expect(navPathFor('settings')).toBe('/settings')
    expect(navPathFor('aiConfig')).toBe('/ai-config')
  })

  it('parses known paths and normalizes trailing slashes', () => {
    expect(navKeyFromPath('/api')).toBe('api')
    expect(navKeyFromPath('/web-cases/')).toBe('api')
    expect(caseDesignKindFromPath('/api/')).toBe('interface')
    expect(caseDesignKindFromPath('/web-cases/')).toBe('web_automation')
    expect(navKeyFromPath('/cases/')).toBe('cases')
    expect(navKeyFromPath('/automation')).toBe('automation')
    expect(navKeyFromPath('/automation/api')).toBe('automation')
    expect(navKeyFromPath('/automation/web/')).toBe('automation')
    expect(navKeyFromPath('/reports')).toBe('reports')
    expect(navKeyFromPath('/settings/')).toBe('settings')
    expect(navKeyFromPath('/ai-config')).toBe('aiConfig')
  })

  it('falls back to the default module for unknown or root paths', () => {
    expect(navKeyFromPath('/')).toBe(defaultNavKey)
    expect(navKeyFromPath('/unknown')).toBe(defaultNavKey)
    expect(navKeyFromPath('/api/child')).toBe(defaultNavKey)
  })

  it('parses automation kind from split automation paths with the legacy route falling back to API', () => {
    expect(automationKindFromPath('/automation/api')).toBe('api')
    expect(automationKindFromPath('/automation/web/')).toBe('web')
    expect(automationKindFromPath('/automation')).toBe(defaultAutomationNavKind)
    expect(automationKindFromPath('/unknown')).toBe(defaultAutomationNavKind)
  })
})
