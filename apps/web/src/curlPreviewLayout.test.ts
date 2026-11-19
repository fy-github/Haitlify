// @ts-expect-error Test-only Node API is outside the browser type set.
import { readFileSync } from 'node:fs'
import { parse } from '@vue/compiler-sfc'
import { describe, expect, it } from 'vitest'
declare const process: { cwd: () => string }

// Source contract supplements (does not replace) real-App browser geometry.
const source = readFileSync(`${process.cwd()}/src/App.vue`, 'utf8')
const template = parse(source).descriptor.template!.content

describe('cURL preview drawer responsive width', () => {
  it('caps only its 620px desktop surface at the viewport width', () => {
    const owners = template.match(/<el-drawer\b[^>]*class="curl-preview-drawer"[^>]*>/g)
    expect(owners).toHaveLength(1)
    expect(owners![0]).toContain('size="min(620px, 100vw)"')
    expect(owners![0]).toContain('v-model="curlVisible"')
  })
})
