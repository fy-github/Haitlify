// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import { useReports } from './useReports'
import type { SavedAutomationReport } from '../domain/automationReportRepository'
import { buildAutomationReportExportArtifact } from '../domain/automationReportExport'

vi.mock('./useDefects', () => ({ useDefects: () => ({}) }))
vi.mock('element-plus', () => ({ ElMessage: { warning: vi.fn(), success: vi.fn(), info: vi.fn() } }))

const report: SavedAutomationReport = {
  reportId: 'print-fixture', name: '合成打印报告', project: '合成项目', environmentName: '隔离环境', moduleScope: [],
  createdAt: '2026-11-11T00:00:00Z', mode: 'sequential', startedAt: '', endedAt: '',
  summary: { total: 0, passed: 0, failed: 0, error: 0 }, results: []
}
const state = () => useReports({ storage: window.localStorage })
const popup = () => ({
  opener: window as unknown,
  document: { write: vi.fn(), close: vi.fn() },
  focus: vi.fn(), print: vi.fn(), close: vi.fn()
})

afterEach(() => { vi.restoreAllMocks(); vi.clearAllMocks() })

describe('report PDF print window', () => {
  it('obtains a writable window under native noopener return semantics', () => {
    const child = popup()
    vi.spyOn(window, 'open').mockImplementation((_url, _target, features) =>
      /(?:noopener|noreferrer)/.test(features || '') ? null : child as unknown as Window)
    state().exportAutomationReport(report, 'pdf')
    expect(child.document.write).toHaveBeenCalledWith(buildAutomationReportExportArtifact(report, 'pdf').content)
    expect(ElMessage.warning).not.toHaveBeenCalled()
  })

  it('severs opener before writing sanitized content and prints once without a deferred timer', () => {
    const child = popup()
    const calls: string[] = []
    child.document.write.mockImplementation((content: string) => {
      expect(child.opener).toBeNull()
      expect(content).toContain('合成打印报告')
      calls.push('write')
    })
    child.document.close.mockImplementation(() => { calls.push('document-close') })
    child.focus.mockImplementation(() => { calls.push('focus') })
    child.print.mockImplementation(() => { calls.push('print') })
    vi.spyOn(window, 'open').mockReturnValue(child as unknown as Window)
    state().exportAutomationReport(report, 'pdf')
    expect(calls).toEqual(['write', 'document-close', 'focus', 'print'])
    expect(child.print).toHaveBeenCalledTimes(1)
    expect(child.close).not.toHaveBeenCalled()
  })

  it('shows the blocked warning only when no window was returned', () => {
    vi.spyOn(window, 'open').mockReturnValue(null)
    expect(() => state().exportAutomationReport(report, 'pdf')).not.toThrow()
    expect(ElMessage.warning).toHaveBeenCalledWith('浏览器拦截了打印窗口，请允许弹窗后重试')
  })

  it('reports an open failure without throwing or claiming the popup was blocked', () => {
    vi.spyOn(window, 'open').mockImplementation(() => { throw new Error('synthetic open failure') })
    expect(() => state().exportAutomationReport(report, 'pdf')).not.toThrow()
    expect(ElMessage.warning).toHaveBeenCalledExactlyOnceWith('报告打印失败，请重试或改用 HTML 导出')
  })

  it('does not write report content when opener assignment is silently ignored', () => {
    const child = popup()
    Object.defineProperty(child, 'opener', { get: () => window, set() {} })
    vi.spyOn(window, 'open').mockReturnValue(child as unknown as Window)
    state().exportAutomationReport(report, 'pdf')
    expect(child.document.write).not.toHaveBeenCalled()
    expect(child.print).not.toHaveBeenCalled()
    expect(child.close).toHaveBeenCalledTimes(1)
    expect(ElMessage.warning).toHaveBeenCalledWith('报告打印失败，请重试或改用 HTML 导出')
  })

  it('retains the failure feedback when closing a failed print window also throws', () => {
    const child = popup()
    child.document.write.mockImplementation(() => { throw new Error('synthetic write failure') })
    child.close.mockImplementation(() => { throw new Error('synthetic close failure') })
    vi.spyOn(window, 'open').mockReturnValue(child as unknown as Window)
    expect(() => state().exportAutomationReport(report, 'pdf')).not.toThrow()
    expect(ElMessage.warning).toHaveBeenCalledExactlyOnceWith('报告打印失败，请重试或改用 HTML 导出')
  })

  it('fails closed when opener isolation fails, without writing or printing', () => {
    const child = popup()
    Object.defineProperty(child, 'opener', { set() { throw new Error('isolation failed') } })
    vi.spyOn(window, 'open').mockReturnValue(child as unknown as Window)
    expect(() => state().exportAutomationReport(report, 'pdf')).not.toThrow()
    expect(child.document.write).not.toHaveBeenCalled()
    expect(child.print).not.toHaveBeenCalled()
    expect(child.close).toHaveBeenCalledTimes(1)
    expect(ElMessage.warning).toHaveBeenCalledWith('报告打印失败，请重试或改用 HTML 导出')
  })

  it.each(['write', 'document-close', 'focus', 'print'])('closes the incomplete popup and reports a %s failure', (step) => {
    const child = popup()
    const target = step === 'write' ? child.document.write : step === 'document-close' ? child.document.close : step === 'focus' ? child.focus : child.print
    target.mockImplementation(() => { throw new Error('synthetic print failure') })
    vi.spyOn(window, 'open').mockReturnValue(child as unknown as Window)
    expect(() => state().exportAutomationReport(report, 'pdf')).not.toThrow()
    expect(child.close).toHaveBeenCalledTimes(1)
    expect(ElMessage.warning).toHaveBeenCalledWith('报告打印失败，请重试或改用 HTML 导出')
  })
})
