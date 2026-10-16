export type WebAutomationDocumentType =
  | 'word'
  | 'pdf'
  | 'markdown'
  | 'yaml'
  | 'json'
  | 'excel'
  | 'image'
  | 'figma'
  | 'text'
  | 'manual'
export type WebAutomationBrowser = 'chromium' | 'chrome' | 'msedge' | 'firefox' | 'webkit'
export type WebAutomationAction =
  | 'manual'
  | 'goto'
  | 'click'
  | 'input'
  | 'select'
  | 'hover'
  | 'waitForVisible'
  | 'waitForUrl'
  | 'assertText'
  | 'assertUrl'
  | 'assertVisible'
  | 'assertHidden'
  | 'scrollIntoView'
  | 'paginate'
  | 'screenshot'

export interface WebAutomationActionIR {
  id: string
  type: string
  target?: Record<string, unknown> | string
  assertion?: Record<string, unknown> | string
  value?: Record<string, unknown> | string
  safety: string
  evidenceId?: string
  resolvedTarget?: Record<string, unknown> | string
  [key: string]: unknown
}

export interface WebAutomationCaseRow {
  caseId: string
  project: string
  module: string
  pageName: string
  title: string
  naturalSteps?: string
  naturalExpectedResult?: string
  environmentId?: string
  caseType: string
  precondition: string
  startUrl: string
  browser: string
  viewport: string
  testData: string
  steps: string
  locators: string
  assertions: string
  waitStrategy: string
  screenshotStrategy: string
  retryCount?: number | string
  priority: string
  executionResult: string
  executor: string
  executedAt: string
  defectId: string
  tags: string
  sourceFile: string
  remark: string
}

export interface WebAutomationStep {
  id: string
  name: string
  action: WebAutomationAction
  targetType: 'url' | 'locator' | 'elementRef'
  target: string
  value?: string
  timeoutMs?: number
  screenshot?: 'none' | 'on_failure' | 'always'
}

export interface WebLocator {
  id: string
  name: string
  locatorType: 'css' | 'xpath' | 'text' | 'role' | 'testId'
  value: string
  fallbackValue?: string
  confidence?: 'high' | 'medium' | 'low'
  reason?: string
  confirmed?: boolean
}

export interface WebAssertion {
  id: string
  name: string
  type: 'visible' | 'hidden' | 'text' | 'url'
  target: string
  expectedValue?: string
}

export interface WebCaseStepTrace {
  stepIndex: number
  naturalStep: string
  intent: string
  dsl: string[]
  evidenceIds: string[]
  pageFingerprint: string
  sourcePointId: string
}

export interface WebAutomationCaseMetadata {
  sourceFile: string
  sourceDocumentType: WebAutomationDocumentType
  pageName: string
  startUrl: string
  browser: WebAutomationBrowser
  viewport: string
  steps: WebAutomationStep[]
  locators: WebLocator[]
  assertions: WebAssertion[]
  variables: Record<string, string>
  runOrder: number
  agentRunId?: string
  agentSourcePointId?: string
  agentEnvironmentId?: string
  agentBaseUrl?: string
  agentAuthenticationMode?: string
  agentEvidenceIds?: string[]
  agentPageKey?: string
  agentPageFingerprint?: string
  agentStepTraces?: WebCaseStepTrace[]
  agentExpectedResultEvidenceIds?: string[]
  artifactId?: string
  artifactRevision?: number
  artifactResultOrigin?: string
  artifactAcceptanceState?: string
  artifactModelProfileVersion?: string
  artifactFieldOrigins?: string[]
  artifactEvidenceIds?: string[]
  artifactParentArtifactId?: string
  artifactIdempotencyKey?: string
  agentActions?: WebAutomationActionIR[]
}

export interface GeneratedWebAutomationCase {
  id: string
  row: WebAutomationCaseRow
  metadata: WebAutomationCaseMetadata
}

export interface SavedWebAutomationCase extends GeneratedWebAutomationCase {
  libraryId: string
  savedAt: string
}

export const buildWebAutomationLibraryId = (testCase: GeneratedWebAutomationCase): string =>
  [
    testCase.metadata.sourceFile,
    testCase.metadata.pageName,
    testCase.row.startUrl,
    testCase.row.caseType
  ].join(':')
