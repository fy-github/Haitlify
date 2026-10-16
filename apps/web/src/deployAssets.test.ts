// 部署资产契约锁：用户实测 2MB 需求文档被 nginx 默认 1m 直接 413，
// 修复后 nginx 上限必须显式设置且不低于平台 multipart 上限（否则两处会再分叉）。
// This test runs in Vitest's Node environment; the application tsconfig only includes browser types.
// @ts-expect-error Node's test-only filesystem API is intentionally not part of the browser type set.
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

describe('deploy assets', () => {
  it('nginx 上传体积上限必须显式设置且不低于平台 multipart max-file-size', () => {
    const nginx = readFileSync(new URL('../nginx.conf', import.meta.url), 'utf8')
    const nginxMatch = nginx.match(/^[ \t]*client_max_body_size[ \t]+(\d+)m/m)
    expect(
      nginxMatch,
      'nginx.conf 必须显式设置 client_max_body_size（默认 1m 会把数 MB 的文档在上游之前直接 413）'
    ).toBeTruthy()

    const platform = readFileSync(
      new URL('../../../server/platform/src/main/resources/application.yml', import.meta.url),
      'utf8'
    )
    const platformMatch = platform.match(/max-file-size:\s*(\d+)MB/)
    expect(platformMatch, 'platform application.yml 必须声明 multipart max-file-size').toBeTruthy()

    const nginxLimitMb = Number(nginxMatch![1])
    const platformLimitMb = Number(platformMatch![1])
    expect(nginxLimitMb).toBeGreaterThanOrEqual(platformLimitMb)
  })
})
