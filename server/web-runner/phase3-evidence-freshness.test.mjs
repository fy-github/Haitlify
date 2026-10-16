import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { RUNNER_IMPLEMENTATION_FILES, runnerImplementationHash } from './scripts/phase3-implementation-hash.mjs'

// 阶段 3 证据新鲜度：平台测试消费的载荷必须由当前 runner 实现生成。
// 这条测试是「stale fixture」的兜底：实现改了但没重跑取证脚本时，必须失败而不是继续用旧证据。
const here = path.dirname(fileURLToPath(import.meta.url))
const evidenceDir = path.resolve(here, '../platform/src/test/resources/ai/evidence')
const PAYLOADS = ['phase3-runner-response.json', 'phase3-runner-action-response.json', 'phase3-runner-navigate-response.json', 'phase3-runner-plain-start-response.json']

test('phase 3 evidence payloads were recorded with the current runner implementation', async () => {
  const expected = await runnerImplementationHash()
  assert.match(expected, /^[0-9a-f]{64}$/)
  for (const file of PAYLOADS) {
    const payload = JSON.parse(await readFile(path.join(evidenceDir, file), 'utf8'))
    assert.ok(payload.runnerImplementationHash, `${file} 缺少 runnerImplementationHash`)
    assert.equal(
      payload.runnerImplementationHash,
      expected,
      `${file} 已过期：请重跑 node scripts/record-phase3-evidence.mjs（参与哈希：${RUNNER_IMPLEMENTATION_FILES.join(', ')}）`
    )
  }
})
