import { joinBackendPath } from './backendBaseUrl'
import { authedFetch } from './http'
import { normalizeAiConfig, type AiConfig, type AiProviderConfig, type AiProviderId } from './aiConfigRepository'

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>
type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue }

export interface AiPlatformRepositoryOptions {
  baseUrl?: string
  fetchImpl?: FetchLike
  signal?: AbortSignal
}

export interface AiRuleConfig {
  version: number
  forcedHumanReview: boolean
  sourceEvidenceRequired: boolean
  inferredRequiresConfirmation: boolean
  allowedCaseKinds: JsonValue[]
  formalGenerationStatuses: JsonValue[]
  forbiddenPatterns: JsonValue[]
  coverageDimensions: JsonValue[]
  rules: JsonValue
  updatedAt?: string | null
}

export interface SaveAiRuleConfig {
  forcedHumanReview?: boolean
  sourceEvidenceRequired?: boolean
  inferredRequiresConfirmation?: boolean
  allowedCaseKinds?: JsonValue[]
  formalGenerationStatuses?: JsonValue[]
  forbiddenPatterns?: JsonValue[]
  coverageDimensions?: JsonValue[]
  rules?: JsonValue
}

export interface AiSkill {
  skillId: string
  name: string
  description: string
  tags: JsonValue
  applicableCaseKinds: JsonValue
  applicableFileTypes: JsonValue
  promptTemplate: string
  outputJsonSchema: JsonValue
  availableTools: JsonValue
  examples: JsonValue
  skillPath?: string
  enabled: boolean
  builtIn: boolean
  customized: boolean
  version: number
  updatedAt?: string | null
}

export interface SaveCustomAiSkill {
  skillId?: string
  name?: string
  description?: string
  tags?: JsonValue
  applicableCaseKinds?: JsonValue
  applicableFileTypes?: JsonValue
  promptTemplate?: string
  outputJsonSchema?: JsonValue
  availableTools?: JsonValue
  examples?: JsonValue
  enabled?: boolean
  skillKind?: string
  skillPath?: string
}

export interface SaveAiSkillOverride {
  name?: string
  description?: string
  tags?: JsonValue
  applicableCaseKinds?: JsonValue
  applicableFileTypes?: JsonValue
  promptTemplate?: string
  outputJsonSchema?: JsonValue
  availableTools?: JsonValue
  examples?: JsonValue
  enabled?: boolean
}

export interface AiAgent {
  agentId: string
  name: string
  description: string
  agentRole: string
  promptTemplate: string
  outputJsonSchema: JsonValue
  allowedTools: JsonValue
  enabled: boolean
  builtIn: boolean
  customized: boolean
  version: number
  updatedAt?: string | null
}

export interface SaveCustomAiAgent {
  agentId?: string
  name?: string
  description?: string
  agentRole?: string
  promptTemplate?: string
  outputJsonSchema?: JsonValue
  allowedTools?: JsonValue
  enabled?: boolean
}

export interface AiMcpServer {
  serverId: string
  name: string
  serverType: string
  endpoint: string
  authMode: string
  availableTools: JsonValue[]
  toolAllowlist: JsonValue[]
  projectScope: JsonValue
  writeOperationsAllowed: boolean
  timeoutMs: number
  enabled: boolean
  auditEnabled: boolean
  sandboxConfig: JsonValue
  updatedAt?: string | null
}

export interface SaveAiMcpServer {
  serverId?: string
  name?: string
  serverType?: string
  endpoint?: string
  authMode?: string
  availableTools?: JsonValue[]
  toolAllowlist?: JsonValue[]
  projectScope?: JsonValue
  writeOperationsAllowed?: boolean
  timeoutMs?: number
  enabled?: boolean
  auditEnabled?: boolean
  sandboxConfig?: JsonValue
}

export interface AiSourceDocument {
  documentId: string
  projectId: number
  fileName: string
  documentType: string
  contentHash: string
  extractedText: string
  parserVersion: string
  parseMetadata: JsonValue
  sourceFileMetadata: JsonValue
  actorId: string
  actorName: string
  createdAt: string
  updatedAt: string
}

export interface AiSourceChunk {
  chunkId: string
  documentId: string
  sourceFile: string
  sectionHeading?: string | null
  lineStart?: number | null
  lineEnd?: number | null
  chunkType: string
  contentHash: string
  contentText: string
  evidenceMetadata: JsonValue
}

export interface AiSourceChunkList {
  items: AiSourceChunk[]
}

export interface CreateAiAnalysisRunRequest {
  projectId: number
  documentIds: string[]
  targetCaseKind: string
  templateId: string
  analysisStrategy: string
}

export interface AiAnalysisRun {
  runId: string
  projectId: number
  targetCaseKind: string
  templateId: string
  analysisStrategy: string
  status: string
  workerStatus: string
  coverageStatus: string
  totalChunks: number
  completedChunks: number
  errorSummary?: string | null
  inputSummary: JsonValue
  outputSummary: JsonValue
  startedAt?: string | null
  heartbeatAt?: string | null
  finishedAt?: string | null
  createdAt: string
  updatedAt: string
}

export interface AiAnalysisRunList {
  items: AiAnalysisRun[]
}

export interface AiDraftTestPoint {
  pointId: string
  runId: string
  projectId: number
  sourceDocumentId: string
  sourceChunkId: string
  title: string
  module: string
  pageOrFeature: string
  caseKind: string
  testType: string
  priority: string
  objective: string
  steps: string
  expectedResult: string
  sourceFile: string
  sourceText: string
  confidenceScore: number
  inferred: boolean
  reviewStatus: string
  resultOrigin?: string
  acceptanceState?: string
  modelProfileVersion?: string
  fieldOrigins?: string[]
  metadata: JsonValue
  createdAt: string
  updatedAt: string
}

export interface AiDraftTestPointList {
  items: AiDraftTestPoint[]
}

export interface EditAiDraftTestPointRequest {
  title: string
  objective: string
  steps: string
  expectedResult: string
  comment?: string
}

export interface GeneratedAiCase {
  sourcePointId: string
  title: string
  module: string
  caseKind: string
  pageName: string
  startUrl: string
  browser: string
  viewport: string
  precondition: string
  objective: string
  steps: string
  assertions: string
  expectedResult: string
  naturalSteps: string
  naturalExpectedResult: string
  sourceFile: string
  sourceText: string
  environmentId?: string
  baseUrl?: string
  authenticationMode?: string
  pageKey?: string
  pageFingerprint?: string
  actions?: Array<{
    id: string
    type: string
    target?: Record<string, unknown> | string
    assertion?: Record<string, unknown> | string
    value?: Record<string, unknown> | string
    safety: string
    evidenceId?: string
    resolvedTarget?: Record<string, unknown> | string
    [key: string]: unknown
  }>
  stepTraces?: Array<{
    stepIndex: number
    naturalStep: string
    intent: string
    dsl: string[]
    evidenceIds: string[]
    pageFingerprint: string
    sourcePointId: string
  }>
  expectedResultEvidenceIds?: string[]
}

export interface GeneratedAiCaseList {
  items: GeneratedAiCase[]
}

export interface AiConnectionTestResult {
  success: boolean
  message: string
}

export interface AiCallRecord {
  recordId: string
  runId?: string | null
  projectId?: number | null
  providerType: string
  modelName: string
  status: string
  durationMs: number
  errorCode?: string | null
  errorMessage?: string | null
  tokenUsageInput?: number | null
  tokenUsageOutput?: number | null
  estimatedCost?: number | null
  createdAt: string
}

export interface AiCallRecordList {
  items: AiCallRecord[]
}

export interface AiRunEvent {
  runId: string
  occurredAt: string
  stage: string
  severity: string
  summary: string
  chunkIndex?: number | null
  chunkTotal?: number | null
  attemptIndex?: number | null
  providerId?: string
  providerType?: string
  model?: string
  durationMs?: number
  retryReason?: string
  strategySnapshotId?: string
}

export interface AiRunEventList {
  items: AiRunEvent[]
}

export interface AiRunPolicyRoute {
  position: number
  connectionId: string
  model: string
}

export interface AiRunPolicyWarning {
  code: string
  message: string
}

export interface AiRunPolicy {
  policyId: string
  scope: string
  taskType: string
  name: string
  enabled: boolean
  perCallTimeoutMs: number
  wholeRunDeadlineMs: number
  retryBudget: number
  maxInputChars: number
  routes: AiRunPolicyRoute[]
  warnings: AiRunPolicyWarning[]
  updatedAt?: string | null
}

export interface AiRunPolicyList {
  items: AiRunPolicy[]
}

export interface SaveAiRunPolicyRoute {
  position?: number
  connectionId?: string
  model?: string
}

export interface SaveAiRunPolicyRequest {
  name?: string
  enabled?: boolean
  perCallTimeoutMs?: number
  wholeRunDeadlineMs?: number
  retryBudget?: number
  maxInputChars?: number
  routes?: SaveAiRunPolicyRoute[]
}

export interface AiAnalysisCoverageSummary {
  runId: string
  summary: JsonValue
  warnings: JsonValue[]
}

export interface AiUnclearQuestion {
  questionId: string
  severity: string
  questionText: string
  sourceRef: string
  status: string
  createdAt?: string | null
  updatedAt?: string | null
}

export interface AiUnclearQuestionList {
  items: AiUnclearQuestion[]
}

export interface AiToolCallRecord {
  callId: string
  runId: string
  projectId?: number | null
  serverId: string
  toolName: string
  readOnly: boolean
  status: string
  requestSummary: JsonValue
  responseSummary: JsonValue
  errorMessage?: string | null
  actorId: string
  createdAt: string
}

export interface AiToolCallRecordList {
  items: AiToolCallRecord[]
}

export interface AiAnalysisWorkerResult {
  processed: boolean
  runId?: string | null
  status: string
  completedChunks: number
  errorMessage?: string | null
}

interface PlatformAiConfigResponse {
  enabled: boolean
  providerType: AiProviderId
  baseUrl: string
  model: string
  credentialConfigured: boolean
  maskedApiKey?: string | null
  requestTimeoutMs: number
  providers?: PlatformAiProviderConfig[]
  activeProviderId?: string | null
  activeModel?: string | null
  agentModelMap?: Record<string, string> | null
  agentProviderMap?: Record<string, string> | null
  commandTimeoutMs?: number | null
  updatedAt?: string | null
}

interface PlatformAiProviderConfig {
  id: string
  providerId: AiProviderId
  name: string
  baseURL?: string | null
  defaultModel?: string | null
  apiKey?: string | null
  enabled: boolean
  credentialConfigured?: boolean | null
  maskedApiKey?: string | null
  requestTimeoutMs?: number | null
  contextWindow?: number | null
  skipTLSVerify?: boolean | null
  customHeaders?: Record<string, string> | null
}

export const loadAiConfigFromPlatform = async (
  options: AiPlatformRepositoryOptions = {}
): Promise<AiConfig> =>
  toAiConfig(await requestJson<PlatformAiConfigResponse>(options, '/api/ai/config'))

export const saveAiConfigToPlatform = async (
  config: AiConfig,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiConfig> =>
  toAiConfig(await requestJson<PlatformAiConfigResponse>(options, '/api/ai/config', {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(toAiConfigRequest(config))
  }))

export const testAiConfigConnection = async (
  config: AiConfig,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiConnectionTestResult> =>
  requestJson<AiConnectionTestResult>(options, '/api/ai/config/test-connection', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(toAiConfigRequest(config))
  })

export const loadAiRules = async (options: AiPlatformRepositoryOptions = {}): Promise<AiRuleConfig> =>
  requestJson<AiRuleConfig>(options, '/api/ai/rules')

export const saveAiRules = async (
  rules: SaveAiRuleConfig,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiRuleConfig> =>
  requestJson<AiRuleConfig>(options, '/api/ai/rules', {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(rules)
  })

export const listAiSkills = async (options: AiPlatformRepositoryOptions = {}): Promise<AiSkill[]> =>
  requestJson<AiSkill[]>(options, '/api/ai/skills')

export const getAiSkill = async (
  skillId: string,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiSkill> =>
  requestJson<AiSkill>(options, `/api/ai/skills/${encodeURIComponent(skillId)}`)

export const createAiSkill = async (
  skill: SaveCustomAiSkill,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiSkill> =>
  requestJson<AiSkill>(options, '/api/ai/skills', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(skill)
  })

export const updateAiSkill = async (
  skillId: string,
  skill: SaveCustomAiSkill,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiSkill> =>
  requestJson<AiSkill>(options, `/api/ai/skills/${encodeURIComponent(skillId)}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(skill)
  })

export const saveAiSkillOverride = async (
  skillId: string,
  override: SaveAiSkillOverride,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiSkill> =>
  requestJson<AiSkill>(options, `/api/ai/skills/${encodeURIComponent(skillId)}/override`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(override)
  })

export const restoreAiSkillDefault = async (
  skillId: string,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiSkill> =>
  requestJson<AiSkill>(options, `/api/ai/skills/${encodeURIComponent(skillId)}/override`, {
    method: 'DELETE'
  })

export const listAiAgents = async (options: AiPlatformRepositoryOptions = {}): Promise<AiAgent[]> =>
  requestJson<AiAgent[]>(options, '/api/ai/agents')

export const createAiAgent = async (
  agent: SaveCustomAiAgent,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiAgent> =>
  requestJson<AiAgent>(options, '/api/ai/agents', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(agent)
  })

export const listAiMcpServers = async (options: AiPlatformRepositoryOptions = {}): Promise<AiMcpServer[]> =>
  requestJson<AiMcpServer[]>(options, '/api/ai/mcp-servers')

export const createAiMcpServer = async (
  server: SaveAiMcpServer,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiMcpServer> =>
  requestJson<AiMcpServer>(options, '/api/ai/mcp-servers', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(server)
  })

export const updateAiMcpServer = async (
  serverId: string,
  server: SaveAiMcpServer,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiMcpServer> =>
  requestJson<AiMcpServer>(options, `/api/ai/mcp-servers/${encodeURIComponent(serverId)}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(server)
  })

export const deleteAiMcpServer = async (
  serverId: string,
  options: AiPlatformRepositoryOptions = {}
): Promise<void> =>
  requestNoContent(options, `/api/ai/mcp-servers/${encodeURIComponent(serverId)}`, {
    method: 'DELETE'
  })

export const uploadAiSourceDocument = async (
  projectId: number,
  file: File,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiSourceDocument> => {
  const formData = new FormData()
  formData.set('file', file)
  const response = await fetcher(options)(
    joinBackendPath(`/api/ai/source-documents?projectId=${encodeURIComponent(String(projectId))}`, options.baseUrl),
    { method: 'POST', body: formData }
  )
  if (!response.ok) {
    throw new Error(uploadErrorMessage(response.status))
  }
  return await response.json() as AiSourceDocument
}

export interface AiSourceDocumentSummary {
  documentId: string
  fileName: string
  documentType: string
  parserVersion: string
  createdAt: string
}

export const fetchProjectSourceDocuments = (
  projectId: number,
  options: AiPlatformRepositoryOptions = {}
): Promise<{ items: AiSourceDocumentSummary[] }> =>
  requestJson<{ items: AiSourceDocumentSummary[] }>(
    options,
    `/api/ai/source-documents?projectId=${encodeURIComponent(String(projectId))}`
  )

export const getAiSourceDocument = async (
  documentId: string,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiSourceDocument> =>
  requestJson<AiSourceDocument>(options, `/api/ai/source-documents/${encodeURIComponent(documentId)}`)

export const listAiSourceChunks = async (
  documentId: string,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiSourceChunkList> =>
  requestJson<AiSourceChunkList>(options, `/api/ai/source-documents/${encodeURIComponent(documentId)}/chunks`)

export const createAiAnalysisRun = async (
  request: CreateAiAnalysisRunRequest,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiAnalysisRun> =>
  requestJson<AiAnalysisRun>(options, '/api/ai/analysis-runs', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(request)
  })

export const getAiAnalysisRun = async (
  runId: string,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiAnalysisRun> =>
  requestJson<AiAnalysisRun>(options, `/api/ai/analysis-runs/${encodeURIComponent(runId)}`)

export const listProjectAiAnalysisRuns = async (
  projectId: number,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiAnalysisRunList> =>
  requestJson<AiAnalysisRunList>(options, `/api/projects/${encodeURIComponent(String(projectId))}/ai-analysis-runs`)

export const retryAiAnalysisRun = async (
  runId: string,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiAnalysisRun> =>
  requestJson<AiAnalysisRun>(options, `/api/ai/analysis-runs/${encodeURIComponent(runId)}/retry`, {
    method: 'POST'
  })

export const cancelAiAnalysisRun = async (
  runId: string,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiAnalysisRun> =>
  requestJson<AiAnalysisRun>(options, `/api/ai/analysis-runs/${encodeURIComponent(runId)}/cancel`, {
    method: 'POST'
  })

export const resumeAiAnalysisRun = async (
  runId: string,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiAnalysisWorkerResult> =>
  requestJson<AiAnalysisWorkerResult>(options, `/api/ai/analysis-runs/${encodeURIComponent(runId)}/resume`, {
    method: 'POST'
  })

export const listAiDraftTestPoints = async (
  runId: string,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiDraftTestPointList> =>
  requestJson<AiDraftTestPointList>(options, `/api/ai/analysis-runs/${encodeURIComponent(runId)}/draft-test-points`)

export const editConfirmAiDraftTestPoint = async (
  pointId: string,
  request: EditAiDraftTestPointRequest,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiDraftTestPoint> =>
  requestJson<AiDraftTestPoint>(options, `/api/ai/draft-test-points/${encodeURIComponent(pointId)}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(request)
  })

export const confirmAiDraftTestPoint = async (
  pointId: string,
  comment = '',
  options: AiPlatformRepositoryOptions = {}
): Promise<AiDraftTestPoint> =>
  reviewAiDraftTestPoint(pointId, 'confirm', comment, options)

export const rejectAiDraftTestPoint = async (
  pointId: string,
  comment = '',
  options: AiPlatformRepositoryOptions = {}
): Promise<AiDraftTestPoint> =>
  reviewAiDraftTestPoint(pointId, 'reject', comment, options)

export const deleteAiDraftTestPoint = async (
  pointId: string,
  options: AiPlatformRepositoryOptions = {}
): Promise<void> => {
  await requestNoContent(options, `/api/ai/draft-test-points/${encodeURIComponent(pointId)}`, {
    method: 'DELETE'
  })
}

export const generateCasesFromAiDraftPoints = async (
  pointIds: string[],
  options: AiPlatformRepositoryOptions = {},
  locatorDiscovery?: WebLocatorDiscoveryContext
): Promise<GeneratedAiCaseList> =>
  requestJson<GeneratedAiCaseList>(options, '/api/ai/draft-test-points/generate-cases', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ pointIds, locatorDiscovery })
  })

export interface WebLocatorDiscoveryContext {
  environmentId?: string
  baseUrl: string
  browser: string
  viewport: string
  headless: boolean
  timeoutMs?: number
  waitUntil?: string
  authToken?: string
  extraHeaders?: Record<string, string>
  cookieOrigins?: string[]
  login?: {
    enabled: boolean
    url: string
    usernameLocator: string
    passwordLocator: string
    submitLocator: string
    username: string
    password: string
    successType: string
    successTarget: string
  }
}

export const listAiCallRecords = async (
  runId?: string,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiCallRecordList> => {
  const path = runId
    ? `/api/ai/call-records?runId=${encodeURIComponent(runId)}`
    : '/api/ai/call-records'
  return requestJson<AiCallRecordList>(options, path)
}

export const getAiAnalysisCoverageSummary = async (
  runId: string,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiAnalysisCoverageSummary> =>
  requestJson<AiAnalysisCoverageSummary>(options, `/api/ai/analysis-runs/${encodeURIComponent(runId)}/coverage-summary`)

export const listAiAnalysisUnclearQuestions = async (
  runId: string,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiUnclearQuestionList> =>
  requestJson<AiUnclearQuestionList>(options, `/api/ai/analysis-runs/${encodeURIComponent(runId)}/unclear-questions`)

export const listAiAnalysisToolCalls = async (
  runId: string,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiToolCallRecordList> =>
  requestJson<AiToolCallRecordList>(options, `/api/ai/analysis-runs/${encodeURIComponent(runId)}/tool-calls`)

export const getAiCallRecord = async (
  recordId: string,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiCallRecord> =>
  requestJson<AiCallRecord>(options, `/api/ai/call-records/${encodeURIComponent(recordId)}`)

export const listAiAnalysisRunEvents = async (
  runId: string,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiRunEventList> =>
  requestJson<AiRunEventList>(options, `/api/ai/analysis-runs/${encodeURIComponent(runId)}/events`)

export const listAiRunPolicies = async (
  options: AiPlatformRepositoryOptions = {}
): Promise<AiRunPolicyList> =>
  requestJson<AiRunPolicyList>(options, '/api/ai/run-policies')

export const saveAiRunPolicy = async (
  policyId: string,
  request: SaveAiRunPolicyRequest,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiRunPolicy> =>
  requestJson<AiRunPolicy>(options, `/api/ai/run-policies/${encodeURIComponent(policyId)}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(request)
  })

export const saveAiRunPolicyOverride = async (
  taskType: string,
  request: SaveAiRunPolicyRequest,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiRunPolicy> =>
  requestJson<AiRunPolicy>(options, `/api/ai/run-policies/override/${encodeURIComponent(taskType)}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(request)
  })

export const deleteAiRunPolicyOverride = async (
  taskType: string,
  options: AiPlatformRepositoryOptions = {}
): Promise<void> =>
  requestNoContent(options, `/api/ai/run-policies/override/${encodeURIComponent(taskType)}`, {
    method: 'DELETE'
  })

const reviewAiDraftTestPoint = async (
  pointId: string,
  action: 'confirm' | 'reject',
  comment: string,
  options: AiPlatformRepositoryOptions
): Promise<AiDraftTestPoint> =>
  requestJson<AiDraftTestPoint>(options, `/api/ai/draft-test-points/${encodeURIComponent(pointId)}/${action}`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ comment })
  })

const toAiConfig = (response: PlatformAiConfigResponse): AiConfig =>
  normalizeAiConfig({
    enabled: response.enabled,
    provider: response.providerType || 'openai-compatible',
    baseUrl: response.baseUrl || '',
    model: response.model || '',
    apiKey: '',
    credentialConfigured: response.credentialConfigured,
    maskedApiKey: response.maskedApiKey || undefined,
    requestTimeoutMs: Math.max(response.requestTimeoutMs || 0, 120000),
    providers: (response.providers || []).map(toAiProviderConfig),
    activeProviderId: response.activeProviderId || '',
    activeModel: response.activeModel || response.model || '',
    agentModelMap: response.agentModelMap || {},
    agentProviderMap: response.agentProviderMap || {},
    commandTimeoutMs: Math.max(response.commandTimeoutMs || response.requestTimeoutMs || 0, 120000),
    updatedAt: response.updatedAt || undefined
  })

const toAiProviderConfig = (provider: PlatformAiProviderConfig): AiProviderConfig => ({
  id: provider.id,
  providerId: provider.providerId || 'openai-compatible',
  name: provider.name || provider.providerId || 'Provider',
  baseURL: provider.baseURL || '',
  defaultModel: provider.defaultModel || '',
  apiKey: '',
  enabled: provider.enabled !== false,
  credentialConfigured: provider.credentialConfigured || false,
  maskedApiKey: provider.maskedApiKey || undefined,
  requestTimeoutMs: Math.max(provider.requestTimeoutMs || 0, 120000),
  contextWindow: provider.contextWindow || undefined,
  skipTLSVerify: provider.skipTLSVerify || false,
  customHeaders: provider.customHeaders || {}
})

const toAiConfigRequest = (config: AiConfig) => {
  const normalized = normalizeAiConfig(config)
  return {
    enabled: normalized.enabled,
    providerType: normalized.provider,
    baseUrl: normalized.baseUrl,
    model: normalized.model,
    apiKey: normalized.apiKey,
    requestTimeoutMs: normalized.requestTimeoutMs || 120000,
    providers: normalized.providers.map((provider) => ({
      id: provider.id,
      providerId: provider.providerId,
      name: provider.name,
      baseURL: provider.baseURL,
      defaultModel: provider.defaultModel,
      apiKey: provider.apiKey,
      enabled: provider.enabled,
      credentialConfigured: provider.credentialConfigured,
      requestTimeoutMs: provider.requestTimeoutMs || normalized.requestTimeoutMs || 120000,
      contextWindow: provider.contextWindow,
      skipTLSVerify: provider.skipTLSVerify,
      customHeaders: provider.customHeaders || {}
    })),
    activeProviderId: normalized.activeProviderId,
    activeModel: normalized.activeModel,
    agentModelMap: normalized.agentModelMap,
    agentProviderMap: normalized.agentProviderMap,
    commandTimeoutMs: normalized.commandTimeoutMs || normalized.requestTimeoutMs || 120000
  }
}

const requestJson = async <T>(
  options: AiPlatformRepositoryOptions,
  path: string,
  init?: RequestInit
): Promise<T> => {
  const requestInit = options.signal ? { ...init, signal: options.signal } : init
  const response = await fetcher(options)(joinBackendPath(path, options.baseUrl), requestInit)
  if (!response.ok) {
    const payload = await response.json().catch(() => undefined) as { message?: string } | undefined
    const suffix = payload?.message ? ` ${payload.message}` : ''
    throw new Error(`AI platform request failed: ${response.status}${suffix}`)
  }
  return await response.json() as T
}

const requestNoContent = async (
  options: AiPlatformRepositoryOptions,
  path: string,
  init?: RequestInit
): Promise<void> => {
  const requestInit = options.signal ? { ...init, signal: options.signal } : init
  const response = await fetcher(options)(joinBackendPath(path, options.baseUrl), requestInit)
  if (!response.ok) {
    throw new Error(`AI platform request failed: ${response.status}`)
  }
}

const fetcher = (options: AiPlatformRepositoryOptions): FetchLike => {
  if (options.fetchImpl) return options.fetchImpl
  if (typeof fetch !== 'undefined') return authedFetch
  throw new Error('Fetch API is not available')
}

const jsonHeaders = (): HeadersInit => ({
  'Content-Type': 'application/json'
})

const uploadErrorMessage = (status: number): string => {
  if (status === 413) {
    return 'AI source document upload failed: 413 文件过大，请压缩后重试'
  }
  return `AI source document upload failed: ${status}`
}
