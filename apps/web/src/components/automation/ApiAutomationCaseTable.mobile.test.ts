import { describe, expect, it } from 'vitest'
import source from './ApiAutomationCaseTable.vue?raw'

// Fixed columns consume 468px before any data at a 351px mobile table width.
// Retain the complete table and ordinary horizontal scrolling instead.
describe('API case table narrow viewport contract', () => {
  it('releases both fixed edges only below the mobile breakpoint', () => {
    const mobile = source.match(/@media\s*\(max-width:\s*768px\)\s*\{([\s\S]*)/)
    expect(mobile, 'scoped mobile override must exist').not.toBeNull()
    for (const side of ['left', 'right']) {
      expect(mobile![1]).toContain(`.el-table-fixed-column--${side}`)
    }
    expect(mobile![1]).toMatch(/position:\s*static\s*!important/)
    expect(mobile![1]).toMatch(/box-shadow:\s*none\s*!important/)
    expect(source).toContain('width="280" fixed="right"')
    expect(source).toContain('width="140" fixed')
  })
})
