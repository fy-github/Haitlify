import { describe, expect, it, vi } from 'vitest'
import { defaultAiConfig } from './aiConfigRepository'
import {
  confirmAiDraftTestPoint,
  createAiAgent,
  createAiAnalysisRun,
  createAiSkill,
  deleteAiDraftTestPoint,
  deleteAiRunPolicyOverride,
  getAiAnalysisCoverageSummary,
  listAiCallRecords,
  listAiAnalysisToolCalls,
  listAiAgents,
  listAiAnalysisUnclearQuestions,
  listAiMcpServers,
  listAiSkills,
  listAiDraftTestPoints,
  loadAiConfigFromPlatform,
  rejectAiDraftTestPoint,
  restoreAiSkillDefault,
  resumeAiAnalysisRun,
  saveAiConfigToPlatform,
  saveAiRules,
  saveAiSkillOverride,
  testAiConfigConnection,
  updateAiSkill,
  uploadAiSourceDocument,
  fetchProjectSourceDocuments
} from './aiPlatformRepository'

const jsonResponse = (body: unknown, init: ResponseInit = {}) =>
  Promise.resolve(new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init
  }))

describe('aiPlatformRepository', () => {
  it('saves system AI config with the raw API key only in the backend request', async () => {
    const fetchImpl = vi.fn((_input: string, _init?: RequestInit) => jsonResponse({
      enabled: true,
      providerType: 'openai-compatible',
      baseUrl: 'https://api.example.com/v1',
      model: 'gpt-4.1',
      credentialConfigured: true,
      maskedApiKey: '****demo',
      requestTimeoutMs: 45000,
      updatedAt: '2026-07-03T10:00:00Z'
    }))

    const result = await saveAiConfigToPlatform({
      ...defaultAiConfig(),
      enabled: true,
      provider: 'openai-compatible',
      baseUrl: 'https://api.example.com/v1',
      model: 'gpt-4.1',
      apiKey: 'sk-demo',
      requestTimeoutMs: 45000
    }, { fetchImpl })

    expect(fetchImpl).toHaveBeenCalledWith('http://127.0.0.1:18080/api/ai/config', expect.objectContaining({
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' }
    }))
    expect(JSON.parse(String(fetchImpl.mock.calls[0]?.[1]?.body))).toMatchObject({
      enabled: true,
      providerType: 'openai-compatible',
      baseUrl: 'https://api.example.com/v1',
      model: 'gpt-4.1',
      apiKey: 'sk-demo',
      requestTimeoutMs: 45000,
      activeProviderId: 'default',
      activeModel: 'gpt-4.1',
      providers: [expect.objectContaining({
        id: 'default',
        providerId: 'openai-compatible',
        baseURL: 'https://api.example.com/v1',
        defaultModel: 'gpt-4.1',
        apiKey: 'sk-demo'
      })]
    })
    expect(result.apiKey).toBe('')
    expect(result.maskedApiKey).toBe('****demo')
    expect(result.credentialConfigured).toBe(true)
  })

  it('loads system AI config with a masked credential marker', async () => {
    const fetchImpl = vi.fn((_input: string, _init?: RequestInit) => jsonResponse({
      enabled: true,
      providerType: 'openai-compatible',
      baseUrl: 'https://api.example.com/v1',
      model: 'gpt-4.1',
      credentialConfigured: true,
      maskedApiKey: '****demo',
      requestTimeoutMs: 30000,
      updatedAt: '2026-07-03T10:00:00Z'
    }))

    const result = await loadAiConfigFromPlatform({ fetchImpl })

    expect(fetchImpl).toHaveBeenCalledWith('http://127.0.0.1:18080/api/ai/config', undefined)
    expect(result).toMatchObject({
      enabled: true,
      provider: 'openai-compatible',
      baseUrl: 'https://api.example.com/v1',
      model: 'gpt-4.1',
      apiKey: '',
      credentialConfigured: true,
      maskedApiKey: '****demo',
      requestTimeoutMs: 120000,
      activeProviderId: 'default',
      activeModel: 'gpt-4.1',
      providers: [expect.objectContaining({
        id: 'default',
        providerId: 'openai-compatible',
        baseURL: 'https://api.example.com/v1',
        defaultModel: 'gpt-4.1',
        maskedApiKey: '****demo'
      })],
      updatedAt: '2026-07-03T10:00:00Z'
    })
  })

  it('tests connection and updates rules through backend endpoints', async () => {
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      if (input.endsWith('/api/ai/config/test-connection')) {
        expect(init?.method).toBe('POST')
        expect(JSON.parse(String(init?.body))).toMatchObject({
          providerType: 'openai-compatible',
          apiKey: 'sk-demo'
        })
        return jsonResponse({ success: true, message: 'ok' })
      }
      expect(input).toBe('http://127.0.0.1:18080/api/ai/rules')
      expect(init?.method).toBe('PUT')
      expect(JSON.parse(String(init?.body))).toMatchObject({
        inferredRequiresConfirmation: true,
        allowedCaseKinds: ['web_automation']
      })
      return jsonResponse({
        version: 2,
        forcedHumanReview: true,
        sourceEvidenceRequired: true,
        inferredRequiresConfirmation: true,
        allowedCaseKinds: ['web_automation'],
        formalGenerationStatuses: ['confirmed', 'modified'],
        forbiddenPatterns: [],
        coverageDimensions: [],
        rules: {},
        updatedAt: '2026-07-03T10:00:00Z'
      })
    })

    await expect(testAiConfigConnection({
      ...defaultAiConfig(),
      enabled: true,
      provider: 'openai-compatible',
      baseUrl: 'https://api.example.com/v1',
      model: 'gpt-4.1',
      apiKey: 'sk-demo'
    }, { fetchImpl })).resolves.toEqual({ success: true, message: 'ok' })
    await expect(saveAiRules({
      inferredRequiresConfirmation: true,
      allowedCaseKinds: ['web_automation']
    }, { fetchImpl })).resolves.toMatchObject({ version: 2 })
  })

  it('sends provider preset model when testing an OpenAI connection with an empty active model', async () => {
    const fetchImpl = vi.fn((_input: string, init?: RequestInit) => {
      expect(JSON.parse(String(init?.body))).toMatchObject({
        providerType: 'openai',
        baseUrl: 'https://api.openai.com/v1',
        model: 'gpt-4.1',
        activeModel: 'gpt-4.1',
        providers: [expect.objectContaining({
          providerId: 'openai',
          baseURL: 'https://api.openai.com/v1',
          defaultModel: 'gpt-4.1'
        })]
      })
      return jsonResponse({ success: true, message: 'ok' })
    })

    await expect(testAiConfigConnection({
      ...defaultAiConfig(),
      enabled: true,
      activeProviderId: 'openai-main',
      activeModel: '',
      providers: [{
        id: 'openai-main',
        providerId: 'openai',
        name: '',
        baseURL: '',
        defaultModel: '',
        apiKey: 'sk-demo',
        enabled: true
      }]
    }, { fetchImpl })).resolves.toEqual({ success: true, message: 'ok' })
  })

  it('loads skills and persists or restores built-in skill overrides', async () => {
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      if (input.endsWith('/api/ai/skills') && !init) {
        return jsonResponse([skillResponse({ customized: false })])
      }
      if (input.endsWith('/api/ai/skills/requirement-test-point-analysis/override') && init?.method === 'PUT') {
        expect(JSON.parse(String(init.body))).toMatchObject({
          promptTemplate: '请输出测试点',
          enabled: true
        })
        return jsonResponse(skillResponse({ customized: true, promptTemplate: '请输出测试点' }))
      }
      expect(input).toBe('http://127.0.0.1:18080/api/ai/skills/requirement-test-point-analysis/override')
      expect(init?.method).toBe('DELETE')
      return jsonResponse(skillResponse({ customized: false }))
    })

    await expect(listAiSkills({ fetchImpl })).resolves.toHaveLength(1)
    await expect(saveAiSkillOverride('requirement-test-point-analysis', {
      promptTemplate: '请输出测试点',
      enabled: true
    }, { fetchImpl })).resolves.toMatchObject({ customized: true })
    await expect(restoreAiSkillDefault('requirement-test-point-analysis', { fetchImpl })).resolves.toMatchObject({ customized: false })
  })

  it('deletes a task-specific run policy override', async () => {
    const fetchImpl = vi.fn((_input: string, _init?: RequestInit) => Promise.resolve(new Response(null, { status: 204 })))

    await expect(deleteAiRunPolicyOverride('requirement_analysis', { fetchImpl })).resolves.toBeUndefined()
    expect(fetchImpl).toHaveBeenCalledWith(
      'http://127.0.0.1:18080/api/ai/run-policies/override/requirement_analysis',
      { method: 'DELETE' }
    )
  })

  it('deletes a draft test point without parsing an empty 204 response', async () => {
    const fetchImpl = vi.fn((_input: string, _init?: RequestInit) => Promise.resolve(new Response(null, { status: 204 })))

    await expect(deleteAiDraftTestPoint('point-1', { fetchImpl })).resolves.toBeUndefined()
    expect(fetchImpl).toHaveBeenCalledWith(
      'http://127.0.0.1:18080/api/ai/draft-test-points/point-1',
      { method: 'DELETE' }
    )
  })

  it('creates and updates custom skills with explicit skill paths', async () => {
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      if (input.endsWith('/api/ai/skills') && init?.method === 'POST') {
        expect(JSON.parse(String(init.body))).toMatchObject({
          skillId: 'custom-web-login-skill',
          name: 'Web登录补充Skill',
          skillPath: '/opt/haitlify/skills/web/login.md'
        })
        return jsonResponse(skillResponse({
          skillId: 'custom-web-login-skill',
          name: 'Web登录补充Skill',
          builtIn: false,
          skillPath: '/opt/haitlify/skills/web/login.md'
        }))
      }
      expect(input).toBe('http://127.0.0.1:18080/api/ai/skills/custom-web-login-skill')
      expect(init?.method).toBe('PUT')
      expect(JSON.parse(String(init?.body))).toMatchObject({
        skillPath: '/opt/haitlify/skills/web/login-v2.md'
      })
      return jsonResponse(skillResponse({
        skillId: 'custom-web-login-skill',
        name: 'Web登录补充Skill',
        builtIn: false,
        skillPath: '/opt/haitlify/skills/web/login-v2.md',
        version: 2
      }))
    })

    const created = await createAiSkill({
      skillId: 'custom-web-login-skill',
      name: 'Web登录补充Skill',
      skillPath: '/opt/haitlify/skills/web/login.md',
      promptTemplate: '请补充登录Web用例',
      enabled: true
    }, { fetchImpl })
    const updated = await updateAiSkill('custom-web-login-skill', {
      skillPath: '/opt/haitlify/skills/web/login-v2.md'
    }, { fetchImpl })

    expect(created.skillPath).toBe('/opt/haitlify/skills/web/login.md')
    expect(updated.skillPath).toBe('/opt/haitlify/skills/web/login-v2.md')
  })

  it('loads and creates agents with skill lookup metadata', async () => {
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      if (input.endsWith('/api/ai/agents') && !init) {
        return jsonResponse([agentResponse()])
      }
      expect(input).toBe('http://127.0.0.1:18080/api/ai/agents')
      expect(init?.method).toBe('POST')
      expect(JSON.parse(String(init?.body))).toMatchObject({
        agentId: 'custom-web-review',
        allowedTools: {
          skillPath: '/opt/haitlify/skills/web/review.md',
          skillIds: ['custom-web-login-skill']
        }
      })
      return jsonResponse(agentResponse({
        agentId: 'custom-web-review',
        name: '自定义Web评审Agent',
        builtIn: false,
        allowedTools: {
          skillPath: '/opt/haitlify/skills/web/review.md',
          skillIds: ['custom-web-login-skill']
        }
      }))
    })

    await expect(listAiAgents({ fetchImpl })).resolves.toHaveLength(1)
    await expect(createAiAgent({
      agentId: 'custom-web-review',
      name: '自定义Web评审Agent',
      agentRole: 'web_review',
      promptTemplate: '根据指定Skill评审Web用例',
      allowedTools: {
        skillPath: '/opt/haitlify/skills/web/review.md',
        skillIds: ['custom-web-login-skill']
      },
      enabled: true
    }, { fetchImpl })).resolves.toMatchObject({
      agentId: 'custom-web-review',
      allowedTools: {
        skillPath: '/opt/haitlify/skills/web/review.md'
      }
    })
  })

  it('loads MCP servers with write operations disabled by default', async () => {
    const fetchImpl = vi.fn((_input: string, _init?: RequestInit) => jsonResponse([{
      serverId: 'context-mcp',
      name: '上下文 MCP',
      serverType: 'http',
      endpoint: 'http://127.0.0.1:9000',
      authMode: 'none',
      availableTools: [],
      toolAllowlist: [],
      projectScope: {},
      writeOperationsAllowed: false,
      timeoutMs: 30000,
      enabled: false,
      auditEnabled: true,
      sandboxConfig: {},
      updatedAt: '2026-07-03T10:00:00Z'
    }]))

    await expect(listAiMcpServers({ fetchImpl })).resolves.toEqual([
      expect.objectContaining({ serverId: 'context-mcp', writeOperationsAllowed: false })
    ])
  })

  it('uploads source documents and posts analysis run options', async () => {
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      if (input.endsWith('/api/ai/source-documents?projectId=12')) {
        return jsonResponse({
          documentId: 'doc-1',
          projectId: 12,
          fileName: '需求.pdf',
          documentType: 'pdf',
          contentHash: 'hash-1',
          extractedText: '测试点',
          parserVersion: 'v1',
          parseMetadata: {},
          sourceFileMetadata: {},
          actorId: 'system-dev',
          actorName: 'Development User',
          createdAt: '2026-07-03T10:00:00Z',
          updatedAt: '2026-07-03T10:00:00Z'
        })
      }
      expect(JSON.parse(String(init?.body))).toEqual({
        projectId: 12,
        documentIds: ['doc-1'],
        targetCaseKind: 'interface',
        templateId: 'default',
        analysisStrategy: 'full'
      })
      return jsonResponse({
        runId: 'run-1',
        projectId: 12,
        targetCaseKind: 'interface',
        templateId: 'default',
        analysisStrategy: 'full',
        status: 'succeeded',
        workerStatus: 'completed',
        coverageStatus: 'covered',
        totalChunks: 1,
        completedChunks: 1,
        errorSummary: '',
        inputSummary: {},
        outputSummary: {},
        startedAt: '2026-07-03T10:00:00Z',
        finishedAt: '2026-07-03T10:00:01Z',
        createdAt: '2026-07-03T10:00:00Z',
        updatedAt: '2026-07-03T10:00:01Z'
      })
    })

    const file = new File(['fake'], '需求.pdf', { type: 'application/pdf' })
    const source = await uploadAiSourceDocument(12, file, { fetchImpl })
    const run = await createAiAnalysisRun({
      projectId: 12,
      documentIds: [source.documentId],
      targetCaseKind: 'interface',
      templateId: 'default',
      analysisStrategy: 'full'
    }, { fetchImpl })

    expect(fetchImpl.mock.calls[0]?.[0]).toBe('http://127.0.0.1:18080/api/ai/source-documents?projectId=12')
    expect(fetchImpl.mock.calls[0]?.[1]?.body).toBeInstanceOf(FormData)
    expect(fetchImpl.mock.calls[1]?.[0]).toBe('http://127.0.0.1:18080/api/ai/analysis-runs')
    expect(run.runId).toBe('run-1')
  })

  it('explains payload too large errors for source document uploads', async () => {
    const fetchImpl = vi.fn(async () => new Response('Payload Too Large', { status: 413 }))
    const file = new File(['fake'], '需求.pdf', { type: 'application/pdf' })

    await expect(uploadAiSourceDocument(12, file, { fetchImpl })).rejects.toThrow(
      'AI source document upload failed: 413 文件过大，请压缩后重试'
    )
  })

  it('lists the project source documents through the directory endpoint', async () => {
    const fetchImpl = vi.fn(async (_input: string, _init?: RequestInit) => new Response(JSON.stringify({
      items: [{
        documentId: 'doc-1', fileName: '需求.docx', documentType: 'word',
        parserVersion: 'requirement-document-parser-v1', createdAt: '2026-10-12T08:00:00Z'
      }]
    }), { status: 200 }))

    const listed = await fetchProjectSourceDocuments(12, { fetchImpl })
    expect(fetchImpl.mock.calls[0]?.[0]).toBe('http://127.0.0.1:18080/api/ai/source-documents?projectId=12')
    expect(listed.items[0]?.documentId).toBe('doc-1')
    expect(listed.items[0]?.fileName).toBe('需求.docx')
  })

  it('loads draft points and sends confirm or reject actions to backend', async () => {
    const fetchImpl = vi.fn((input: string, _init?: RequestInit) => {
      if (input.endsWith('/api/ai/analysis-runs/run-1/draft-test-points')) {
        return jsonResponse({
          items: [{
            pointId: 'point-1',
            runId: 'run-1',
            projectId: 12,
            sourceDocumentId: 'doc-1',
            sourceChunkId: 'chunk-1',
            title: '登录成功',
            module: '登录',
            pageOrFeature: '登录页',
            caseKind: 'interface',
            testType: 'positive',
            priority: 'P1',
            objective: '验证登录成功',
            steps: '提交正确账号密码',
            expectedResult: '返回 token',
            sourceFile: '需求.pdf',
            sourceText: '登录成功',
            confidenceScore: 0.91,
            inferred: false,
            reviewStatus: 'ai_generated',
            metadata: {},
            createdAt: '2026-07-03T10:00:00Z',
            updatedAt: '2026-07-03T10:00:00Z'
          }]
        })
      }
      return jsonResponse({
        pointId: 'point-1',
        runId: 'run-1',
        projectId: 12,
        sourceDocumentId: 'doc-1',
        sourceChunkId: 'chunk-1',
        title: '登录成功',
        module: '登录',
        pageOrFeature: '登录页',
        caseKind: 'interface',
        testType: 'positive',
        priority: 'P1',
        objective: '验证登录成功',
        steps: '提交正确账号密码',
        expectedResult: '返回 token',
        sourceFile: '需求.pdf',
        sourceText: '登录成功',
        confidenceScore: 0.91,
        inferred: false,
        reviewStatus: input.endsWith('/reject') ? 'rejected' : 'confirmed',
        metadata: {},
        createdAt: '2026-07-03T10:00:00Z',
        updatedAt: '2026-07-03T10:00:02Z'
      })
    })

    const points = await listAiDraftTestPoints('run-1', { fetchImpl })
    const confirmed = await confirmAiDraftTestPoint('point-1', '可生成', { fetchImpl })
    const rejected = await rejectAiDraftTestPoint('point-1', '范围外', { fetchImpl })

    expect(points.items).toHaveLength(1)
    expect(fetchImpl.mock.calls[1]?.[0]).toBe('http://127.0.0.1:18080/api/ai/draft-test-points/point-1/confirm')
    expect(JSON.parse(String(fetchImpl.mock.calls[1]?.[1]?.body))).toEqual({ comment: '可生成' })
    expect(confirmed.reviewStatus).toBe('confirmed')
    expect(fetchImpl.mock.calls[2]?.[0]).toBe('http://127.0.0.1:18080/api/ai/draft-test-points/point-1/reject')
    expect(JSON.parse(String(fetchImpl.mock.calls[2]?.[1]?.body))).toEqual({ comment: '范围外' })
    expect(rejected.reviewStatus).toBe('rejected')
  })

  it('loads run coverage summary unclear questions and tool calls', async () => {
    const fetchImpl = vi.fn((input: string, _init?: RequestInit) => {
      if (input.endsWith('/api/ai/analysis-runs/run-1/coverage-summary')) {
        return jsonResponse({
          runId: 'run-1',
          summary: { totalDraftPoints: 2 },
          warnings: ['Skipped chunk chunk-2: max_chunks_exceeded']
        })
      }
      if (input.endsWith('/api/ai/analysis-runs/run-1/unclear-questions')) {
        return jsonResponse({
          items: [{
            questionId: 'question-1',
            severity: 'high',
            questionText: '缺少异常状态',
            sourceRef: 'chunk-2',
            status: 'open',
            createdAt: '2026-07-03T10:00:00Z',
            updatedAt: '2026-07-03T10:00:00Z'
          }]
        })
      }
      return jsonResponse({
        items: [{
          callId: 'tool-call-1',
          runId: 'run-1',
          projectId: 12,
          serverId: 'mcp-docs',
          toolName: 'searchDocs',
          readOnly: true,
          status: 'succeeded',
          requestSummary: {},
          responseSummary: {},
          errorMessage: '',
          actorId: 'system-dev',
          createdAt: '2026-07-03T10:00:00Z'
        }]
      })
    })

    const coverage = await getAiAnalysisCoverageSummary('run-1', { fetchImpl })
    const questions = await listAiAnalysisUnclearQuestions('run-1', { fetchImpl })
    const toolCalls = await listAiAnalysisToolCalls('run-1', { fetchImpl })

    expect(fetchImpl.mock.calls.map((call) => call[0])).toEqual([
      'http://127.0.0.1:18080/api/ai/analysis-runs/run-1/coverage-summary',
      'http://127.0.0.1:18080/api/ai/analysis-runs/run-1/unclear-questions',
      'http://127.0.0.1:18080/api/ai/analysis-runs/run-1/tool-calls'
    ])
    expect(coverage.warnings).toEqual(['Skipped chunk chunk-2: max_chunks_exceeded'])
    expect(questions.items[0].severity).toBe('high')
    expect(toolCalls.items[0].toolName).toBe('searchDocs')
  })

  it('posts run resume requests', async () => {
    const fetchImpl = vi.fn((_input: string, init?: RequestInit) => {
      expect(init?.method).toBe('POST')
      return jsonResponse({
        processed: true,
        runId: 'run-1',
        status: 'succeeded',
        completedChunks: 2,
        errorMessage: ''
      })
    })

    const result = await resumeAiAnalysisRun('run-1', { fetchImpl })

    expect(fetchImpl).toHaveBeenCalledWith('http://127.0.0.1:18080/api/ai/analysis-runs/run-1/resume', {
      method: 'POST'
    })
    expect(result.status).toBe('succeeded')
  })

  it('scopes call records to a run when a runId is provided', async () => {
    const fetchImpl = vi.fn((_input: string) => jsonResponse({ items: [] }))
    await listAiCallRecords('run-1', { fetchImpl })
    expect(fetchImpl).toHaveBeenCalledWith('http://127.0.0.1:18080/api/ai/call-records?runId=run-1', undefined)

    await listAiCallRecords(undefined, { fetchImpl })
    expect(fetchImpl).toHaveBeenLastCalledWith('http://127.0.0.1:18080/api/ai/call-records', undefined)
  })
})

const skillResponse = (patch: Partial<{
  skillId: string
  name: string
  builtIn: boolean
  customized: boolean
  promptTemplate: string
  skillPath: string
  version: number
}> = {}) => ({
  skillId: patch.skillId || 'requirement-test-point-analysis',
  name: patch.name || '需求测试点分析',
  description: '分析需求并生成测试点',
  tags: ['requirements'],
  applicableCaseKinds: ['web_automation'],
  applicableFileTypes: ['pdf', 'docx'],
  promptTemplate: patch.promptTemplate || '默认提示词',
  outputJsonSchema: {},
  availableTools: [],
  examples: patch.skillPath ? { skillPath: patch.skillPath } : [],
  skillPath: patch.skillPath || '',
  enabled: true,
  builtIn: patch.builtIn ?? true,
  customized: patch.customized ?? false,
  version: patch.version ?? (patch.customized ? 2 : 1),
  updatedAt: '2026-07-03T10:00:00Z'
})

const agentResponse = (patch: Partial<{
  agentId: string
  name: string
  builtIn: boolean
  allowedTools: unknown
}> = {}) => ({
  agentId: patch.agentId || 'requirement-understanding',
  name: patch.name || '需求理解 Agent',
  description: '理解需求并拆分测试上下文',
  agentRole: 'requirement_understanding',
  promptTemplate: '默认 Agent 提示词',
  outputJsonSchema: {},
  allowedTools: patch.allowedTools || [],
  enabled: true,
  builtIn: patch.builtIn ?? true,
  customized: false,
  version: 1,
  updatedAt: '2026-07-03T10:00:00Z'
})
