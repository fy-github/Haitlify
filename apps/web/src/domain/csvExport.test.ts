import { describe, expect, it } from 'vitest'
import { interfaceCaseFields } from './caseTemplates'
import { exportCasesToCsv } from './csvExport'

describe('exportCasesToCsv', () => {
  it('exports Chinese headers and escapes CSV values', () => {
    const csv = exportCasesToCsv(
      interfaceCaseFields,
      [
        {
          caseId: 'API-0001',
          project: 'Haitlify',
          module: '项目,成员',
          interfaceName: '新增"成员"',
          method: 'POST',
          path: '/api/members',
          title: '正常请求\n成功',
          caseType: '正向',
          precondition: '',
          headers: '{}',
          pathParams: '{}',
          queryParams: '{}',
          body: '{}',
          steps: '1. 请求接口',
          expectedResult: '返回成功',
          assertions: '状态码为 200',
          priority: 'P1',
          executionResult: '未执行',
          executor: '',
          executedAt: '',
          defectId: '',
          tags: 'AI生成',
          sourceFile: 'Haitlify.openapi.json',
          remark: ''
        }
      ]
    )

    expect(csv.startsWith('\ufeff用例ID,项目,模块,接口名称')).toBe(true)
    expect(csv).toContain('"项目,成员"')
    expect(csv).toContain('"新增""成员"""')
    expect(csv).toContain('"正常请求\n成功"')
  })
})
