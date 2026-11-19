import { describe, expect, it } from 'vitest'
import { automationKindFromPath, automationPathFor, caseDesignKindFromPath, caseDesignPathFor, defaultAutomationNavKind, defaultNavKey, navKeyFromPath, navPathFor } from './appNavigation'

describe('appNavigation', () => {
  it('maps major modules to stable paths', () => {
    expect(navPathFor('api')).toBe('/cases')
    expect(caseDesignPathFor('interface')).toBe('/cases')
    expect(caseDesignPathFor('web_automation')).toBe('/cases/web')
    expect(navPathFor('cases')).toBe('/cases')
    expect(navPathFor('webCases')).toBe('/cases/web')
    expect(navPathFor('automation')).toBe('/automation/api')
    expect(automationPathFor('api')).toBe('/automation/api')
    expect(automationPathFor('web')).toBe('/automation/web')
    expect(navPathFor('reports')).toBe('/reports')
    expect(navPathFor('settings')).toBe('/settings')
    expect(navPathFor('assistant')).toBe('/assistant')
  })

  it('parses known paths and normalizes trailing slashes', () => {
    expect(navKeyFromPath('/environments/')).toBe('environments')
    expect(navKeyFromPath('/api')).toBe('cases')
    expect(navKeyFromPath('/web-cases/')).toBe('webCases')
    expect(navKeyFromPath('/cases/web/')).toBe('webCases')
    expect(caseDesignKindFromPath('/api/')).toBe('interface')
    expect(caseDesignKindFromPath('/web-cases/')).toBe('web_automation')
    expect(navKeyFromPath('/cases/')).toBe('cases')
    expect(navKeyFromPath('/automation')).toBe('automation')
    expect(navKeyFromPath('/automation/api')).toBe('automation')
    expect(navKeyFromPath('/automation/web/')).toBe('automation')
    expect(navKeyFromPath('/reports')).toBe('reports')
    expect(navKeyFromPath('/settings/')).toBe('settings')
    expect(navKeyFromPath('/ai-config')).toBe('assistant')
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
