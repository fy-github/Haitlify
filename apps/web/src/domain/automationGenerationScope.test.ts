import { describe, expect, it } from 'vitest'
import { buildAutomationGenerationScope } from './automationGenerationScope'

const interfaceCases = [
  { id: 'api-1', row: { project: 'API 项目', module: '接口模块' } }
]
const webCases = [
  { id: 'web-1', row: { project: 'Web 项目', module: '登录' } },
  { id: 'web-2', row: { project: 'Web 项目', module: '用户管理' } }
]

describe('automationGenerationScope', () => {
  it('uses Web cases for projects, modules, and count in Web mode', () => {
    const scope = buildAutomationGenerationScope('web_automation', interfaceCases, webCases, {
      project: 'Web 项目',
      module: '登录'
    })

    expect(scope.projects).toEqual([{ value: 'Web 项目', count: 2 }])
    expect(scope.modules).toEqual([
      { value: '登录', count: 1 },
      { value: '用户管理', count: 1 }
    ])
    expect(scope.cases.map((item) => item.id)).toEqual(['web-1'])
  })

  it('keeps interface conversion scope independent from Web cases', () => {
    const scope = buildAutomationGenerationScope('interface', interfaceCases, webCases, {
      project: 'ALL',
      module: 'ALL'
    })

    expect(scope.projects).toEqual([{ value: 'API 项目', count: 1 }])
    expect(scope.cases.map((item) => item.id)).toEqual(['api-1'])
  })
})
