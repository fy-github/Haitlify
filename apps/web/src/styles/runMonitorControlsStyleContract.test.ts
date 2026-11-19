import { describe, expect, it } from 'vitest'
// @ts-expect-error Test-only Node API is outside the browser type set.
import { readFileSync, existsSync } from 'node:fs'
declare const process: { cwd: () => string }
const read = (name: string): string => readFileSync(`${process.cwd()}/src/${name}`, 'utf8')
const cssPath = `${process.cwd()}/src/styles/run-monitor-controls.css`
const css: string = existsSync(cssPath) ? readFileSync(cssPath, 'utf8') : ''
const api = read('components/automation/ApiRunMonitorView.vue')
const web = read('components/automation/WebRunMonitorView.vue')
const rule = (selector: string): string => {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`(?:^|\\n)${escaped}\\s*\\{([^}]*)\\}`).exec(css)?.[1] || ''
}
describe('run-monitor controls: measured API-v3 and Web-v1 regions', () => {
  it('loads only through the two monitor components, not a global entrypoint', () => {
    expect(api).toContain('@import "../../styles/run-monitor-controls.css";')
    // External unscoped style-src is cached by path, so keep its block index unique.
    expect(api).not.toContain('<style src="../../styles/run-monitor-controls.css">')
    expect(web).toContain('<style src="../../styles/run-monitor-controls.css"></style>')
    expect(read('main.ts')).not.toContain('run-monitor-controls.css')
  })
  it('uses the drawer footer primary, not the preparation toolbar primary', () => {
    const primary = rule('.api-monitor-drawer .api-monitor-drawer-actions button,\n.web-ed-detail-drawer .web-ed-detail__foot .web-ed-btn--primary')
    for (const token of ['background: #54d9b5', 'color: #184333', 'border-color: #48cbaa']) expect(primary).toContain(token)
    const footer = rule('.api-monitor-drawer .api-monitor-drawer-actions button,\n.web-ed-detail-drawer .web-ed-detail__foot .web-ed-btn')
    for (const token of ['border-radius: 18px', 'padding: 8px 12px', 'font-size: 11px', 'font-weight: 400']) expect(footer).toContain(token)
  })
  it('keeps Web monitor header secondary and log detail in its own pale-green variant', () => {
    const header = rule('.web-ed-monitor .web-ed-monitor__actions button')
    for (const token of ['background: #f4f7f5', 'color: #5b7764', 'border: 1px solid #e1ebe4', 'border-radius: 19px', 'font-weight: 400']) expect(header).toContain(token)
    const details = rule('.web-ed-monitor .web-ed-monitor__details')
    for (const token of ['background: #edf6ef', 'color: #477151', 'border: 0', 'border-radius: 17px', 'padding: 6px 11px']) expect(details).toContain(token)
    expect(rule('.web-ed-detail-drawer .web-ed-detail__foot .web-ed-btn,\n.web-ed-detail-drawer .web-ed-detail__foot .web-ed-btn:is(:hover, :focus-visible)')).toContain('background: #f5f7f6')
  })
  it('uses the archive active queue and mobile pane tokens without a shadow', () => {
    expect(rule('.web-ed-monitor .web-ed-monitor__queue-item.is-active')).toContain('background: #edf6f0')
    const active = rule('.web-ed-monitor .web-ed-monitor__pane-nav button[aria-pressed="true"]')
    for (const token of ['background: #fff', 'color: #376147', 'box-shadow: none']) expect(active).toContain(token)
  })
  it('keeps hover and keyboard focus flat but identifiable', () => {
    expect(css).toContain('filter: brightness(0.96)')
    expect(css).toContain('outline: none')
    expect(css).toContain('box-shadow: none')
    expect(css).not.toMatch(/linear-gradient|outline:\s*[1-9]|box-shadow:\s*(?!none)[0-9]/)
    expect(css).toContain(':focus-visible')
    expect(css).toContain(':hover:not(:disabled)')
  })
  it('uses 44px mobile drawer controls and bounds the teleported drawer', () => {
    const media = css.slice(css.indexOf('@media (max-width: 640px)'))
    for (const token of ['min-height: 44px', 'width: 100% !important', 'max-width: 100vw', 'min-width: 44px']) expect(media).toContain(token)
    expect(rule('.web-ed-detail-drawer .web-ed-drawer__close')).toContain('padding: 1px 6px')
  })
  it('does not change monitor event wiring, state gates, identity or disabled semantics', () => {
    expect(api).toContain('v-if="monitor.status === \'completed\'"')
    expect(api).toContain(':disabled="!selectedRow"')
    for (const line of [':disabled="controlLoading === \'pause\'"', ':disabled="controlLoading === \'resume\'"', ':disabled="controlLoading === \'stop\'"', "emit('openLog', row.caseId as string)", "emit('transferDefect', detailRow.caseId)"]) expect(web).toContain(line)
    expect(api).not.toMatch(/emit\('(pause|resume|stop)'/)
    expect(web).toContain("const terminal = computed(() => ['completed', 'stopped', 'failed'].includes(props.snapshot.status))")
  })
})
