import { describe, expect, it, vi } from 'vitest'
import {
  importAiFigmaFile,
  listAiFigmaNodes,
  startAiFigmaFileAnalysis
} from './aiFigmaRequirementRepository'

describe('aiFigmaRequirementRepository', () => {
  it('imports Figma link through AI Figma API', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({
      figmaFileId: 'figma-file-1',
      fileKey: 'File123',
      selectedNodeId: '1:2',
      fileName: 'Login Flow'
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    const result = await importAiFigmaFile({
      projectId: 7,
      figmaUrl: 'https://www.figma.com/design/File123/Login?node-id=1-2'
    }, { fetchImpl })

    const calls = fetchImpl.mock.calls as unknown as [string, RequestInit | undefined][]
    expect(calls[0]?.[0]).toBe('http://127.0.0.1:18080/api/ai/figma/import')
    expect(calls[0]?.[1]).toMatchObject({ method: 'POST' })
    expect(JSON.parse(String(calls[0]?.[1]?.body))).toMatchObject({ projectId: 7 })
    expect(result.fileKey).toBe('File123')
  })

  it('loads nodes and starts file analysis', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        items: [{ figmaNodeId: 'node-1', nodeId: '1:1', nodeName: 'Login', nodeType: 'FRAME', parentNodeId: '', componentName: '' }]
      }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        runId: 'run-1',
        status: 'succeeded',
        draftTestPointCount: 3
      }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    const nodes = await listAiFigmaNodes('figma-file-1', { fetchImpl })
    const run = await startAiFigmaFileAnalysis('figma-file-1', { fetchImpl })

    const calls = fetchImpl.mock.calls as unknown as [string, RequestInit | undefined][]
    expect(calls[0]?.[0]).toBe('http://127.0.0.1:18080/api/ai/figma/files/figma-file-1/nodes')
    expect(calls[1]?.[0]).toBe('http://127.0.0.1:18080/api/ai/figma/files/figma-file-1/analysis-runs')
    expect(nodes.items[0].nodeName).toBe('Login')
    expect(run.draftTestPointCount).toBe(3)
  })
})
