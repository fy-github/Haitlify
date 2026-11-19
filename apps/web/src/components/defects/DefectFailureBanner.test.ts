// @vitest-environment jsdom
import { ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'

type Outcome = 'confirmed-no-write' | 'uncertain-commit' | 'committed-navigation-failed'

let wrapper: VueWrapper | undefined

const mountBanner = async (outcome: Outcome, rawOnly = false, message = '业务层失败信息') => {
  vi.resetModules()
  const state = {
    defectFailure: ref({ outcome, code: 'test', message }),
    defectTemplateCandidate: ref(null),
    defectRawStore: ref<string | null>(outcome === 'confirmed-no-write' ? '原始存储文本' : null),
    exportDefectRawStore: vi.fn(),
    defectRecoveryCandidate: ref(rawOnly || outcome === 'committed-navigation-failed' ? null : { operation: 'create' }),
    retryDefectMutation: vi.fn(),
    exportDefectRecoveryCandidate: vi.fn(),
    discardDefectRecoveryCandidate: vi.fn()
  }
  vi.doMock('../../ui/useDefects', () => ({ useDefects: () => state }))
  const { default: DefectFailureBanner } = await import('./DefectFailureBanner.vue')
  wrapper = mount(DefectFailureBanner)
  return wrapper
}

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.doUnmock('../../ui/useDefects')
})

describe('DefectFailureBanner outcome hierarchy', () => {
  it('does not promise a recoverable candidate when only raw storage text is available', async () => {
    const banner = await mountBanner('confirmed-no-write', true)
    expect(banner.get('[data-testid="defect-failure-impact"]').text()).not.toContain('候选数据仍可恢复')
    expect(banner.get('[data-testid="defect-failure-next-step"]').text()).toContain('原始存储文本')
    expect(banner.find('[data-action="retry-defect-mutation"]').exists()).toBe(false)
    expect(banner.find('[data-action="export-defect-raw"]').exists()).toBe(true)
  })

  it('removes the candidate-only retry promise from the raw-only original error', async () => {
    const original = '未保存：草稿读取失败：存储内容损坏，写入已停止。请保留候选后重试。'
    const banner = await mountBanner('confirmed-no-write', true, original)
    const wholeBanner = banner.get('[data-testid="defect-failure-banner"]').text()
    expect(wholeBanner).toContain('未保存：草稿读取失败：存储内容损坏，写入已停止。')
    expect(wholeBanner).not.toContain('请保留候选后重试。')
    expect(wholeBanner).toContain('本次操作未写入存储。')
    expect(wholeBanner).toContain('先下载原始存储文本，核对并修复读取问题。')
  })

  it('keeps candidate recovery copy when a candidate exists', async () => {
    const banner = await mountBanner('confirmed-no-write', false, '未保存：写入已停止。请保留候选后重试。')
    const wholeBanner = banner.get('[data-testid="defect-failure-banner"]').text()
    expect(wholeBanner).toContain('请保留候选后重试。')
    expect(wholeBanner).toContain('候选数据仍可恢复')
    expect(wholeBanner).toContain('保留需要的数据后重试保存。')
    expect(banner.find('[data-action="retry-defect-mutation"]').exists()).toBe(true)
  })

  it.each([
    ['confirmed-no-write', 'error'],
    ['uncertain-commit', 'warning'],
    ['committed-navigation-failed', 'info']
  ] as const)('exposes outcome %s as message level %s', async (outcome, level) => {
    const banner = await mountBanner(outcome)
    expect(banner.get('[data-testid="defect-failure-banner"]').attributes('data-message-level')).toBe(level)
  })

  it('makes confirmed-no-write explicit and keeps retry-save primary', async () => {
    const banner = await mountBanner('confirmed-no-write')

    expect(banner.get('[data-testid="defect-failure-title"]').text()).toContain('未保存')
    expect(banner.get('[data-testid="defect-failure-impact"]').text()).toContain('未写入')
    expect(banner.get('[data-testid="defect-failure-next-step"]').text()).toContain('重试保存')
    expect(banner.get('[data-action="retry-defect-mutation"]').text()).toBe('重试保存')
    expect(banner.get('[data-action="retry-defect-mutation"]').classes()).toContain('is-primary')
    expect(banner.find('[data-action="export-defect-raw"]').exists()).toBe(true)
    expect(banner.find('[data-action="export-defect-candidate"]').exists()).toBe(true)
    expect(banner.find('[data-action="discard-defect-candidate"]').exists()).toBe(true)
  })

  it('makes uncertain-commit a verification step instead of an ordinary save retry', async () => {
    const banner = await mountBanner('uncertain-commit')

    expect(banner.get('[data-testid="defect-failure-title"]').text()).toContain('待核对')
    expect(banner.get('[data-testid="defect-failure-impact"]').text()).toContain('可能已经写入')
    expect(banner.get('[data-testid="defect-failure-next-step"]').text()).toContain('先核对保存结果')
    expect(banner.get('[data-action="retry-defect-mutation"]').text()).toBe('核对保存结果')
    expect(banner.get('[data-action="retry-defect-mutation"]').classes()).toContain('is-primary')
    expect(banner.get('[data-action="retry-defect-mutation"]').text()).not.toContain('重试保存')
  })

  it('makes committed-navigation-failed say data is saved and offers only navigation retry', async () => {
    const banner = await mountBanner('committed-navigation-failed')

    expect(banner.get('[data-testid="defect-failure-title"]').text()).toContain('已保存')
    expect(banner.get('[data-testid="defect-failure-impact"]').text()).toContain('数据已保存')
    expect(banner.get('[data-testid="defect-failure-next-step"]').text()).toContain('仅重试跳转')
    expect(banner.get('[data-action="retry-defect-mutation"]').text()).toBe('仅重试跳转')
    expect(banner.get('[data-action="retry-defect-mutation"]').classes()).toContain('is-primary')
    expect(banner.find('[data-action="export-defect-candidate"]').exists()).toBe(false)
    expect(banner.find('[data-action="discard-defect-candidate"]').exists()).toBe(false)
  })
})
