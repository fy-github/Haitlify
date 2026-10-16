import { describe, expect, it } from 'vitest'
import { mapDesignOriginFailureItems } from './designOriginFailureView'

describe('designOriginFailureView', () => {
  it('maps failed design-origin automation results into UI items', () => {
    const items = mapDesignOriginFailureItems([
      {
        caseId: 'WEB-AUTO-WEB-DESIGN-0001',
        sourceCaseId: 'WEB-DESIGN-0001',
        name: '设计稿-主按钮跳转',
        module: '设计稿交互',
        pageName: '设计稿流程',
        startUrl: '/design',
        status: 'failed',
        durationMs: 100,
        errorMessage: '断言失败：页面未跳转'
      },
      {
        caseId: 'WEB-AUTO-WEB-DESIGN-0002',
        sourceCaseId: 'WEB-DESIGN-0002',
        name: '设计稿-提示浮层',
        module: '设计稿交互',
        pageName: '设计稿流程',
        startUrl: '/design',
        status: 'error',
        durationMs: 80
      }
    ])

    expect(items).toEqual([
      {
        caseId: 'WEB-AUTO-WEB-DESIGN-0001',
        name: '设计稿-主按钮跳转',
        module: '设计稿交互',
        pageName: '设计稿流程',
        status: 'failed',
        errorMessage: '断言失败：页面未跳转'
      },
      {
        caseId: 'WEB-AUTO-WEB-DESIGN-0002',
        name: '设计稿-提示浮层',
        module: '设计稿交互',
        pageName: '设计稿流程',
        status: 'error',
        errorMessage: '执行异常'
      }
    ])
  })
})
