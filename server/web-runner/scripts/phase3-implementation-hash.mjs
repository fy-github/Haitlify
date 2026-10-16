// 阶段 3 证据新鲜度：
// 平台测试消费的落地载荷（phase3-runner-*.json）必须由「当前 runner 实现」生成。
// 这里把参与取证的实现文件做成一个哈希，写进载荷，任何实现改动都会让测试失败，
// 从而强制重跑 node scripts/record-phase3-evidence.mjs（避免消费过期 fixture）。
import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const runnerDir = path.resolve(here, '..')

export const RUNNER_IMPLEMENTATION_FILES = ['page-evidence.mjs', 'page-locator-discovery.mjs', 'locator-utils.mjs']

export const runnerImplementationHash = async () => {
  const hash = createHash('sha256')
  for (const file of RUNNER_IMPLEMENTATION_FILES) {
    hash.update(file)
    hash.update(await readFile(path.join(runnerDir, file)))
  }
  return hash.digest('hex')
}
