import { describe, expect, it } from 'vitest'
import { resolveNavDisplay, studioSidebarGroups } from './appSidebar'

describe('approved home contextual rail', () => {
  it('uses the three workbench sections and a separate assistant page entry', () => {
    const home = studioSidebarGroups.find(group => group.id === 'home')!
    expect(home.items.map(item => item.label)).toEqual(['运行进度', '待我处理', '最近工作', 'AI 助手'])
    expect(home.items.slice(0, 3).map(item => item.navKey)).toEqual(['home', 'home', 'home'])
    expect(home.items[3]?.navKey).toBe('assistant')
  })

  it('does not identify the home workbench as the retired assistant', () => {
    expect(resolveNavDisplay('home', 'api').label).toBe('我的工作台')
    expect(resolveNavDisplay('assistant', 'api').label).toBe('AI 助手')
  })
})
