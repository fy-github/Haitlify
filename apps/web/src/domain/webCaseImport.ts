import type {
  GeneratedWebAutomationCase,
  WebAutomationDocumentType,
  WebAutomationAction,
  WebAutomationStep,
  WebCaseStepTrace,
  WebAutomationActionIR
} from './webAutomationCase'
import type { AiDraftTestPoint, GeneratedAiCase } from './aiPlatformRepository'

export interface WebCaseImportInput {
  name: string
  text: string
}

export interface ImportedWebCaseBatch {
  projectName: string
  cases: GeneratedWebAutomationCase[]
}

export interface ImportedWebCaseSummaryOption {
  value: string
  count: number
}

export interface ImportedWebCaseSummary {
  modules: ImportedWebCaseSummaryOption[]
  caseTypes: ImportedWebCaseSummaryOption[]
  pages: ImportedWebCaseSummaryOption[]
}

export const formatNaturalLanguageText = (value: string | undefined): string => {
  const lines = (value || '')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
  return lines
    .map((line, index) => `${index + 1}. ${line.replace(/^\d+\s*[.)、]\s*/, '')}`)
    .join('\n')
}

interface ParsedYamlCase {
  title: string
  caseType: string
  steps: Array<{
    action: string
    targetType: string
    target: string
    value?: string
  }>
}

interface ParsedYamlDocument {
  project: string
  module: string
  pageName: string
  startUrl: string
  browser: string
  viewport: string
  cases: ParsedYamlCase[]
}

interface ParsedMarkdownCase {
  title: string
  module: string
  pageName: string
  startUrl: string
  browser: string
  viewport: string
  caseType: string
  steps: string[]
}

interface RequirementPoint {
  project: string
  module: string
  pageName: string
  title: string
  sourceFile: string
  sourceDocumentType: WebAutomationDocumentType
}

export type RequirementPointDraft = RequirementPoint

export const importWebCaseDocuments = (files: WebCaseImportInput[]): ImportedWebCaseBatch => {
  const imported = files.map(importWebCaseDocument)
  return {
    projectName: imported.find((item) => item.projectName)?.projectName || '默认项目',
    cases: imported.flatMap((item) => item.cases)
  }
}

export const extractRequirementPointsFromDocuments = (
  files: WebCaseImportInput[]
): { projectName: string; points: RequirementPointDraft[] } => {
  const points = files.flatMap(extractRequirementPointsFromDocument)
  return {
    projectName: points[0]?.project || '默认项目',
    points
  }
}

export const buildGeneratedCasesFromRequirementPoints = (
  points: RequirementPointDraft[]
): ImportedWebCaseBatch => ({
  projectName: points[0]?.project || '默认项目',
  cases: points.map((point, index) =>
    buildGeneratedWebCase({
      id: `WEB-${String(index + 1).padStart(4, '0')}`,
      project: point.project,
      module: point.module,
      pageName: point.pageName,
      title: point.title,
      caseType: '需求测试点',
      startUrl: '/',
      browser: 'chromium',
      viewport: '1440x900',
      sourceFile: point.sourceFile,
      sourceDocumentType: point.sourceDocumentType,
      steps: [
        {
          id: 'step-1',
          name: 'review-requirement',
          action: 'waitForVisible',
          targetType: 'locator',
          target: point.title
        },
        {
          id: 'step-2',
          name: 'assert-requirement',
          action: 'assertVisible',
          targetType: 'locator',
          target: point.title
        }
      ]
    })
  )
})

export const buildGeneratedCasesFromAiGeneratedCases = (
  cases: GeneratedAiCase[],
  sourcePoints: AiDraftTestPoint[],
  projectName: string,
  idOffset = 0
): ImportedWebCaseBatch => ({
  projectName: projectName || '默认项目',
  cases: cases.map((testCase, index) => {
    const sourcePoint = sourcePoints.find((point) => point.pointId === testCase.sourcePointId)
    const naturalSteps = firstNonDslText(testCase.naturalSteps, sourcePoint?.steps)
    const naturalExpectedResult = firstNonDslText(testCase.naturalExpectedResult, sourcePoint?.expectedResult)
    if (!naturalSteps) {
      throw new Error(`${testCase.sourcePointId || testCase.title} 缺少自然语言步骤，不能进入 Web 自动化评审列表`)
    }
    if (!naturalExpectedResult) {
      throw new Error(`${testCase.sourcePointId || testCase.title} 缺少自然语言预期，不能进入 Web 自动化评审列表`)
    }
    const steps = splitStepLines(testCase.steps)
    if (steps.length === 0) {
      throw new Error(`${testCase.sourcePointId || testCase.title} 缺少可执行 Web 步骤`)
    }
    const metadataSteps = steps.map((line, stepIndex) => parseStepLine(line, stepIndex))
    const manualStep = metadataSteps.find((step) => step.action === 'manual')
    if (manualStep) {
      throw new Error(`${testCase.sourcePointId || testCase.title} 包含不可执行步骤：${manualStep.target}`)
    }

    const generated = buildGeneratedWebCase({
      id: `WEB-AI-${String(idOffset + index + 1).padStart(4, '0')}`,
      project: projectName || '默认项目',
      module: testCase.module || sourcePoint?.module || '默认模块',
      pageName: testCase.pageName,
      title: testCase.title,
      caseType: 'AI用例',
      startUrl: testCase.startUrl,
      browser: testCase.browser,
      viewport: testCase.viewport,
      sourceFile: testCase.sourceFile,
      sourceDocumentType: 'markdown',
      steps: metadataSteps,
      precondition: testCase.precondition,
      stepsText: steps.join('\n'),
      assertionsText: testCase.assertions,
      naturalSteps,
      naturalExpectedResult,
      priority: sourcePoint?.priority || 'P1',
      remark: testCase.sourceText
    })
    const candidate = testCase as GeneratedAiCase & { environmentId?: string; baseUrl?: string; authenticationMode?: string; pageKey?: string; pageFingerprint?: string; stepTraces?: WebCaseStepTrace[]; expectedResultEvidenceIds?: string[]; actions?: WebAutomationActionIR[] }
    generated.row.environmentId = candidate.environmentId
    generated.metadata.agentEnvironmentId = candidate.environmentId
    generated.metadata.agentBaseUrl = candidate.baseUrl
    generated.metadata.agentAuthenticationMode = candidate.authenticationMode
    generated.metadata.agentPageKey = candidate.pageKey
    generated.metadata.agentPageFingerprint = candidate.pageFingerprint
    generated.metadata.agentStepTraces = candidate.stepTraces
    generated.metadata.agentExpectedResultEvidenceIds = candidate.expectedResultEvidenceIds
    generated.metadata.agentActions = candidate.actions
    return generated
  })
})

export const buildNaturalLanguageCasesFromAgentArtifacts = (
  artifacts: Array<{ sourcePointId: string; candidateJson: Record<string, unknown> }>,
  sourcePoints: AiDraftTestPoint[],
  projectName: string,
  idOffset = 0
): ImportedWebCaseBatch => ({
  projectName: projectName || '默认项目',
  cases: artifacts.map((artifact, index) => {
    const candidate = artifact.candidateJson
    const browser = String(candidate.browser || '')
    const viewport = String(candidate.viewport || '')
    const environmentId = String(candidate.environmentId || '')
    const sourcePoint = sourcePoints.find((point) => point.pointId === artifact.sourcePointId)
    const naturalSteps = firstNonDslText(String(candidate.naturalSteps || ''), sourcePoint?.steps)
    const naturalExpectedResult = firstNonDslText(String(candidate.naturalExpectedResult || ''), sourcePoint?.expectedResult)
    const generated = buildGeneratedWebCase({
      id: `WEB-PLAN-${String(idOffset + index + 1).padStart(4, '0')}`,
      project: projectName || '默认项目',
      module: String(candidate.module || sourcePoint?.module || '默认模块'),
      pageName: String(candidate.pageName || '未命名页面'),
      title: String(candidate.title || sourcePoint?.title || '自然语言 Web 用例'),
      caseType: 'AI自然语言用例',
      startUrl: String(candidate.startUrl || '/'),
      browser,
      viewport,
      environmentId,
      sourceFile: String(candidate.sourceFile || ''),
      sourceDocumentType: 'markdown',
      steps: [],
      precondition: String(candidate.precondition || ''),
      stepsText: '',
      assertionsText: '',
      naturalSteps,
      naturalExpectedResult,
      priority: sourcePoint?.priority || 'P1',
      actions: Array.isArray(candidate.actions) ? candidate.actions as WebAutomationActionIR[] : undefined,
    })
    const actions = Array.isArray(candidate.actions) ? candidate.actions as WebAutomationActionIR[] : undefined
    if (actions?.length) generated.metadata.agentActions = actions
    generated.metadata.agentEnvironmentId = environmentId
    generated.metadata.agentBaseUrl = String(candidate.baseUrl || '')
    generated.metadata.agentAuthenticationMode = String(candidate.authenticationMode || '')
    generated.metadata.agentPageKey = String(candidate.pageKey || '')
    generated.metadata.agentPageFingerprint = String(candidate.pageFingerprint || '')
    generated.metadata.agentStepTraces = Array.isArray(candidate.stepTraces) ? candidate.stepTraces as WebCaseStepTrace[] : undefined
    generated.metadata.agentExpectedResultEvidenceIds = Array.isArray(candidate.expectedResultEvidenceIds) ? candidate.expectedResultEvidenceIds as string[] : undefined
    return generated
  })
})

export const importWebCaseDocument = (file: WebCaseImportInput): ImportedWebCaseBatch => {
  const extension = file.name.toLowerCase().split('.').pop() || ''

  if (extension === 'yaml' || extension === 'yml') {
    return importYamlWebCaseDocument(file)
  }
  if (extension === 'md' || extension === 'markdown') {
    return importMarkdownWebCaseDocument(file)
  }
  if (extension === 'json') {
    return importJsonRequirementDocument(file)
  }
  if (isRequirementDocumentExtension(extension)) {
    return importRequirementDocument(file, documentTypeFromExtension(extension))
  }

  throw new Error(`暂不支持的文件类型: ${file.name}`)
}

export const summarizeImportedWebCases = (cases: GeneratedWebAutomationCase[]): ImportedWebCaseSummary => ({
  modules: summarizeBy(cases, (testCase) => testCase.row.module),
  caseTypes: summarizeBy(cases, (testCase) => testCase.row.caseType),
  pages: summarizeBy(cases, (testCase) => testCase.row.pageName)
})

const importYamlWebCaseDocument = (file: WebCaseImportInput): ImportedWebCaseBatch => {
  const parsed = parseYamlDocument(file.text)
  return {
    projectName: parsed.project,
    cases: parsed.cases.map((testCase, index) =>
      buildGeneratedWebCase({
        id: `WEB-${String(index + 1).padStart(4, '0')}`,
        project: parsed.project,
        module: parsed.module,
        pageName: parsed.pageName,
        title: testCase.title,
        caseType: testCase.caseType || '正向',
        startUrl: parsed.startUrl,
        browser: parsed.browser || 'chromium',
        viewport: parsed.viewport || '1440x900',
        sourceFile: file.name,
        sourceDocumentType: 'yaml',
        steps: testCase.steps.map((step, stepIndex) => ({
          id: `step-${stepIndex + 1}`,
          name: `${step.action}-${stepIndex + 1}`,
          action: normalizeAction(step.action),
          targetType: normalizeTargetType(step.targetType),
          target: step.target,
          ...(step.value ? { value: step.value } : {})
        }))
      })
    )
  }
}

const importMarkdownWebCaseDocument = (file: WebCaseImportInput): ImportedWebCaseBatch => {
  const parsed = parseMarkdownDocument(file.text)
  return {
    projectName: parsed.projectName,
    cases: parsed.cases.map((testCase, index) =>
      buildGeneratedWebCase({
        id: `WEB-${String(index + 1).padStart(4, '0')}`,
        project: parsed.projectName,
        module: testCase.module,
        pageName: testCase.pageName,
        title: testCase.title,
        caseType: testCase.caseType || '正向',
        startUrl: testCase.startUrl,
        browser: testCase.browser || 'chromium',
        viewport: testCase.viewport || '1440x900',
        sourceFile: file.name,
        sourceDocumentType: 'markdown',
        steps: testCase.steps.map((step, stepIndex) => parseMarkdownStep(step, stepIndex))
      })
    )
  }
}

const importJsonRequirementDocument = (file: WebCaseImportInput): ImportedWebCaseBatch => {
  try {
    const parsed = JSON.parse(file.text) as Record<string, unknown>
    const project = textValue(parsed.project) || textValue(parsed.projectName) || '默认项目'
    const module = textValue(parsed.module) || '默认模块'
    const pageName = textValue(parsed.pageName) || textValue(parsed.page) || '未命名页面'
    const rawRequirements = Array.isArray(parsed.requirements)
      ? parsed.requirements
      : Array.isArray(parsed.testPoints)
        ? parsed.testPoints
        : Array.isArray(parsed.cases)
          ? parsed.cases
          : []
    const points = rawRequirements.map((item): RequirementPoint => ({
      project,
      module: textValue((item as Record<string, unknown>)?.module) || module,
      pageName: textValue((item as Record<string, unknown>)?.pageName) || textValue((item as Record<string, unknown>)?.page) || pageName,
      title: typeof item === 'string'
        ? item
        : textValue((item as Record<string, unknown>)?.title)
          || textValue((item as Record<string, unknown>)?.name)
          || textValue((item as Record<string, unknown>)?.requirement)
          || '未命名需求测试点',
      sourceFile: file.name,
      sourceDocumentType: 'json'
    }))
    return buildRequirementCases(file, points.length ? points : extractRequirementPoints(file, 'json'))
  } catch {
    return importRequirementDocument(file, 'json')
  }
}

const importRequirementDocument = (
  file: WebCaseImportInput,
  sourceDocumentType: WebAutomationDocumentType
): ImportedWebCaseBatch =>
  buildRequirementCases(file, extractRequirementPoints(file, sourceDocumentType))

const buildRequirementCases = (
  file: WebCaseImportInput,
  points: RequirementPoint[]
): ImportedWebCaseBatch =>
  buildGeneratedCasesFromRequirementPoints(
    points.map((point) => ({
      ...point,
      sourceFile: point.sourceFile || file.name
    }))
  )

const extractRequirementPointsFromDocument = (file: WebCaseImportInput): RequirementPointDraft[] => {
  const extension = file.name.toLowerCase().split('.').pop() || ''

  if (extension === 'yaml' || extension === 'yml') {
    const parsed = parseYamlDocument(file.text)
    return parsed.cases.map((testCase) => ({
      project: parsed.project,
      module: parsed.module,
      pageName: parsed.pageName,
      title: testCase.title,
      sourceFile: file.name,
      sourceDocumentType: 'yaml'
    }))
  }
  if (extension === 'md' || extension === 'markdown') {
    const parsed = parseMarkdownDocument(file.text)
    return parsed.cases.map((testCase) => ({
      project: parsed.projectName,
      module: testCase.module,
      pageName: testCase.pageName,
      title: testCase.title,
      sourceFile: file.name,
      sourceDocumentType: 'markdown'
    }))
  }
  if (extension === 'json') {
    try {
      const parsed = JSON.parse(file.text) as Record<string, unknown>
      const project = textValue(parsed.project) || textValue(parsed.projectName) || '默认项目'
      const module = textValue(parsed.module) || '默认模块'
      const pageName = textValue(parsed.pageName) || textValue(parsed.page) || '未命名页面'
      const rawRequirements = Array.isArray(parsed.requirements)
        ? parsed.requirements
        : Array.isArray(parsed.testPoints)
          ? parsed.testPoints
          : Array.isArray(parsed.cases)
            ? parsed.cases
            : []
      if (rawRequirements.length > 0) {
        return rawRequirements.map((item): RequirementPointDraft => ({
          project,
          module: textValue((item as Record<string, unknown>)?.module) || module,
          pageName: textValue((item as Record<string, unknown>)?.pageName) || textValue((item as Record<string, unknown>)?.page) || pageName,
          title: typeof item === 'string'
            ? item
            : textValue((item as Record<string, unknown>)?.title)
              || textValue((item as Record<string, unknown>)?.name)
              || textValue((item as Record<string, unknown>)?.requirement)
              || '未命名需求测试点',
          sourceFile: file.name,
          sourceDocumentType: 'json'
        }))
      }
    } catch {
      // Fall through to generic extraction
    }
  }

  return extractRequirementPoints(file, isRequirementDocumentExtension(extension) ? documentTypeFromExtension(extension) : 'text')
}

const extractRequirementPoints = (
  file: WebCaseImportInput,
  sourceDocumentType: WebAutomationDocumentType
): RequirementPoint[] => {
  const rows = file.text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
  const project = rows.find((line) => /^项目[:：,]/.test(line))?.replace(/^项目[:：,]/, '').split(',')[0].trim() || '默认项目'
  let module = rows.find((line) => /^模块[:：,]/.test(line))?.replace(/^模块[:：,]/, '').split(',')[0].trim() || '默认模块'
  let pageName = rows.find((line) => /^页面[:：,]/.test(line))?.replace(/^页面[:：,]/, '').split(',')[0].trim() || '未命名页面'

  const points: RequirementPoint[] = []
  const seen = new Set<string>()
  for (const row of rows) {
    if (/^模块[:：]/.test(row)) {
      module = row.replace(/^模块[:：]/, '').trim() || module
      continue
    }
    if (/^页面[:：]/.test(row)) {
      pageName = row.replace(/^页面[:：]/, '').trim() || pageName
      continue
    }

    const csvCells = row.split(',').map((cell) => cell.trim()).filter(Boolean)
    const title = row.match(/^(测试点|需求|场景|用例)[:：]\s*(.+)$/)?.[2]
      || (csvCells.length >= 4 && !/^项目$/i.test(csvCells[0]) ? csvCells[csvCells.length - 1] : '')
      || row.match(/^\d+[.)、]\s*(.+)$/)?.[1]
      || (/^[-*]\s+/.test(row) ? row.replace(/^[-*]\s+/, '') : '')
    if (!title || isIgnoredRequirementTitle(title)) continue

    const dedupeKey = `${project}::${module}::${pageName}::${title}`
    if (seen.has(dedupeKey)) continue
    seen.add(dedupeKey)

    points.push({
      project: csvCells.length >= 4 ? csvCells[0] || project : project,
      module: csvCells.length >= 4 ? csvCells[1] || module : module,
      pageName: csvCells.length >= 4 ? csvCells[2] || pageName : pageName,
      title,
      sourceFile: file.name,
      sourceDocumentType
    })
  }

  if (points.length) return points
  return [{
    project,
    module,
    pageName,
    title: sourceDocumentType === 'image'
      ? `根据图片 ${file.name} 提取需求测试点`
      : `根据 ${file.name} 提取需求测试点`,
    sourceFile: file.name,
    sourceDocumentType
  }]
}

const parseYamlDocument = (text: string): ParsedYamlDocument => {
  const lines = text
    .split('\n')
    .map((line) => line.replace(/\r/g, ''))
    .filter((line) => line.trim())

  const readScalar = (key: string, fallback = '') =>
    lines.find((line) => line.startsWith(`${key}:`))?.split(':').slice(1).join(':').trim() || fallback

  const cases: ParsedYamlCase[] = []
  let currentCase: ParsedYamlCase | undefined
  let currentStep: ParsedYamlCase['steps'][number] | undefined

  for (const rawLine of lines) {
    const line = rawLine.trim()
    if (line.startsWith('- title:')) {
      currentCase = {
        title: line.replace('- title:', '').trim(),
        caseType: '正向',
        steps: []
      }
      cases.push(currentCase)
      currentStep = undefined
      continue
    }
    if (!currentCase) continue

    if (line.startsWith('caseType:')) {
      currentCase.caseType = line.replace('caseType:', '').trim()
      continue
    }
    if (line.startsWith('- action:')) {
      currentStep = {
        action: line.replace('- action:', '').trim(),
        targetType: 'locator',
        target: ''
      }
      currentCase.steps.push(currentStep)
      continue
    }
    if (!currentStep) continue

    if (line.startsWith('targetType:')) {
      currentStep.targetType = line.replace('targetType:', '').trim()
    } else if (line.startsWith('target:')) {
      currentStep.target = trimYamlValue(line.replace('target:', '').trim())
    } else if (line.startsWith('value:')) {
      currentStep.value = trimYamlValue(line.replace('value:', '').trim())
    }
  }

  return {
    project: readScalar('project', '默认项目'),
    module: readScalar('module', '默认模块'),
    pageName: readScalar('pageName', '未命名页面'),
    startUrl: readScalar('startUrl', '/'),
    browser: readScalar('browser', 'chromium'),
    viewport: readScalar('viewport', '1440x900'),
    cases
  }
}

const parseMarkdownDocument = (text: string): { projectName: string; cases: ParsedMarkdownCase[] } => {
  const lines = text
    .split('\n')
    .map((line) => line.replace(/\r/g, '').trim())
    .filter(Boolean)

  const projectName = lines.find((line) => line.startsWith('# '))?.replace(/^# /, '').trim() || '默认项目'
  let currentModule = '默认模块'
  let currentCase: ParsedMarkdownCase | undefined
  const cases: ParsedMarkdownCase[] = []

  for (const line of lines) {
    if (line.startsWith('## 模块:')) {
      currentModule = line.replace('## 模块:', '').trim()
      continue
    }
    if (line.startsWith('### 用例:')) {
      currentCase = {
        title: line.replace('### 用例:', '').trim(),
        module: currentModule,
        pageName: '未命名页面',
        startUrl: '/',
        browser: 'chromium',
        viewport: '1440x900',
        caseType: '正向',
        steps: []
      }
      cases.push(currentCase)
      continue
    }
    if (!currentCase) continue

    if (line.startsWith('- 页面:')) {
      currentCase.pageName = line.replace('- 页面:', '').trim()
    } else if (line.startsWith('- 地址:')) {
      currentCase.startUrl = line.replace('- 地址:', '').trim()
    } else if (line.startsWith('- 用例类型:')) {
      currentCase.caseType = line.replace('- 用例类型:', '').trim()
    } else if (line.startsWith('- 浏览器:')) {
      currentCase.browser = line.replace('- 浏览器:', '').trim()
    } else if (line.startsWith('- 视口:')) {
      currentCase.viewport = line.replace('- 视口:', '').trim()
    } else if (/^\d+\.\s/.test(line)) {
      currentCase.steps.push(line.replace(/^\d+\.\s/, '').trim())
    }
  }

  return { projectName, cases }
}

const parseStepLine = (line: string, index: number): WebAutomationStep => {
  if (/^(input|select|assertText)\s+.+?\s*=\s*.+$/i.test(line)) {
    const [, actionToken = '', target = '', value = ''] = line.match(/^(input|select|assertText)\s+(.+?)\s*=\s*(.+)$/i) || []
    return {
      id: `step-${index + 1}`,
      name: `${actionToken}-${index + 1}`,
      action: normalizeAction(actionToken),
      targetType: 'locator',
      target: target.trim(),
      value: value.trim()
    }
  }
  if (/^(manual|goto|click|hover|waitForVisible|waitForUrl|assertUrl|assertVisible|assertHidden|scrollIntoView|paginate|screenshot)(?:\s+|$)/i.test(line)) {
    const [actionToken, ...rest] = line.split(' ')
    const target = rest.join(' ').trim()
    const action = normalizeAction(actionToken)
    return {
      id: `step-${index + 1}`,
      name: `${actionToken}-${index + 1}`,
      action,
      targetType: ['goto', 'waitForUrl', 'assertUrl'].includes(action) ? 'url' : action === 'manual' ? 'elementRef' : 'locator',
      target
    }
  }
  if (line.startsWith('打开 ')) {
    return {
      id: `step-${index + 1}`,
      name: `goto-${index + 1}`,
      action: 'goto',
      targetType: 'url',
      target: line.replace('打开 ', '').trim()
    }
  }
  if (line.startsWith('点击 ')) {
    return {
      id: `step-${index + 1}`,
      name: `click-${index + 1}`,
      action: 'click',
      targetType: 'locator',
      target: line.replace('点击 ', '').trim()
    }
  }
  if (line.startsWith('断言可见 ')) {
    return {
      id: `step-${index + 1}`,
      name: `assertVisible-${index + 1}`,
      action: 'assertVisible',
      targetType: 'locator',
      target: line.replace('断言可见 ', '').trim()
    }
  }
  if (line.startsWith('输入 ')) {
    const [, target = '', value = ''] = line.match(/^输入\s+(.+?)\s*=\s*(.+)$/) || []
    return {
      id: `step-${index + 1}`,
      name: `input-${index + 1}`,
      action: 'input',
      targetType: 'locator',
      target: target.trim(),
      value: value.trim()
    }
  }

  return {
    id: `step-${index + 1}`,
    name: `step-${index + 1}`,
    action: 'manual',
    targetType: 'elementRef',
    target: line
  }
}

const parseMarkdownStep = (line: string, index: number): WebAutomationStep =>
  parseStepLine(line, index)

const buildGeneratedWebCase = (input: {
  id: string
  project: string
  module: string
  pageName: string
  title: string
  caseType: string
  startUrl: string
  browser: string
  viewport: string
  sourceFile: string
  sourceDocumentType: WebAutomationDocumentType
  steps: WebAutomationStep[]
  precondition?: string
  stepsText?: string
  assertionsText?: string
  naturalSteps?: string
  naturalExpectedResult?: string
  priority?: string
  remark?: string
  actions?: WebAutomationActionIR[]
  environmentId?: string
  agentEvidenceIds?: string[]
  agentPageKey?: string
  agentPageFingerprint?: string
  agentStepTraces?: WebCaseStepTrace[]
  agentExpectedResultEvidenceIds?: string[]
}): GeneratedWebAutomationCase => ({
  id: input.id,
  row: {
    caseId: input.id,
    project: input.project,
    module: input.module,
    pageName: input.pageName,
    title: input.title,
    ...(input.naturalSteps ? { naturalSteps: formatNaturalLanguageText(input.naturalSteps) } : {}),
    ...(input.naturalExpectedResult ? { naturalExpectedResult: formatNaturalLanguageText(input.naturalExpectedResult) } : {}),
    caseType: input.caseType,
    precondition: input.precondition || '',
    startUrl: input.startUrl,
    browser: input.browser,
    viewport: input.viewport,
    testData: '{}',
    steps: input.stepsText || input.steps.map((step) => `${step.action} ${step.target}`).join('\n'),
    locators: input.steps
      .filter(isLocatorBackedStep)
      .map((step) => step.target)
      .join(', '),
    assertions: input.assertionsText || input.steps
      .filter((step) => step.action.startsWith('assert'))
      .map((step) => `${step.action} ${step.target}`)
      .join('\n'),
    waitStrategy: 'networkidle',
    screenshotStrategy: 'on_failure',
    priority: input.priority || 'P1',
    executionResult: '未执行',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: `${input.module},Web自动化`,
    sourceFile: input.sourceFile,
    remark: input.remark || ''
  },
  metadata: {
    sourceFile: input.sourceFile,
    sourceDocumentType: input.sourceDocumentType,
    pageName: input.pageName,
    startUrl: input.startUrl,
    browser: normalizeBrowser(input.browser),
    viewport: input.viewport,
    steps: input.steps,
    locators: input.steps
      .filter(isLocatorBackedStep)
      .map((step, index) => ({
        id: `locator-${index + 1}`,
        name: step.target,
        locatorType: 'css' as const,
        value: step.target
      })),
    assertions: input.steps
      .filter((step) => step.action.startsWith('assert'))
      .map((step, index) => ({
        id: `assertion-${index + 1}`,
        name: step.name,
        type: step.action === 'assertVisible' ? 'visible' as const : 'text' as const,
        target: step.target
      })),
    variables: {},
    runOrder: Number(input.id.replace('WEB-', '')) || 1,
    ...(input.agentEvidenceIds?.length ? { agentEvidenceIds: input.agentEvidenceIds } : {}),
    ...(input.agentPageKey ? { agentPageKey: input.agentPageKey } : {}),
    ...(input.agentPageFingerprint ? { agentPageFingerprint: input.agentPageFingerprint } : {}),
    ...(input.agentStepTraces ? { agentStepTraces: input.agentStepTraces } : {}),
    ...(input.agentExpectedResultEvidenceIds ? { agentExpectedResultEvidenceIds: input.agentExpectedResultEvidenceIds } : {}),
    ...(input.actions?.length ? { agentActions: input.actions } : {})
  }
})

const normalizeAction = (value: string): WebAutomationAction => {
  const action = value.trim()
  const supported: WebAutomationAction[] = [
    'manual',
    'goto',
    'click',
    'input',
    'select',
    'hover',
    'waitForVisible',
    'waitForUrl',
    'assertText',
    'assertUrl',
    'assertVisible',
    'assertHidden',
    'scrollIntoView',
    'paginate',
    'screenshot'
  ]
  return supported.includes(action as WebAutomationAction) ? action as WebAutomationAction : 'click'
}

const isLocatorBackedStep = (step: WebAutomationStep): boolean =>
  step.targetType !== 'url' && step.action !== 'manual'

const normalizeTargetType = (value: string): 'url' | 'locator' | 'elementRef' => {
  if (value === 'url' || value === 'elementRef') return value
  return 'locator'
}

const normalizeBrowser = (value: string): 'chromium' | 'chrome' | 'msedge' | 'firefox' | 'webkit' => {
  if (value === 'chrome' || value === 'msedge' || value === 'firefox' || value === 'webkit') return value
  return 'chromium'
}

const splitStepLines = (value: string): string[] =>
  value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)

const DSL_PREFIXES = [
  'goto ',
  'click ',
  'input ',
  'select ',
  'hover ',
  'waitForVisible ',
  'waitForUrl ',
  'assertVisible ',
  'assertHidden ',
  'scrollIntoView ',
  'paginate ',
  'assertText ',
  'assertUrl ',
  'screenshot '
]

const isDslOnlyText = (value: string | undefined): boolean => {
  const lines = splitStepLines(value || '')
  return lines.length > 0 && lines.every((line) => DSL_PREFIXES.some((prefix) => line.startsWith(prefix)))
}

const firstNonDslText = (...values: Array<string | undefined>): string => {
  for (const value of values) {
    const normalized = (value || '').trim()
    if (normalized && !isDslOnlyText(normalized)) return normalized
  }
  return ''
}

const firstGotoTarget = (steps: WebAutomationStep[]): string =>
  steps.find((step) => step.action === 'goto' && step.targetType === 'url')?.target || ''

const trimYamlValue = (value: string): string =>
  value.replace(/^['"]|['"]$/g, '')

const textValue = (value: unknown): string =>
  typeof value === 'string' ? value.trim() : ''

const isIgnoredRequirementTitle = (title: string): boolean =>
  /^(项目|module|模块|page|页面)/i.test(title)
  || /^[一二三四五六七八九十]+[、.．]/.test(title)
  || /^第[一二三四五六七八九十\d]+[章节部分条]/.test(title)

const isRequirementDocumentExtension = (extension: string): boolean =>
  [
    'pdf',
    'doc',
    'docx',
    'xls',
    'xlsx',
    'csv',
    'txt',
    'png',
    'jpg',
    'jpeg',
    'webp',
    'gif',
    'bmp'
  ].includes(extension)

const documentTypeFromExtension = (extension: string): WebAutomationDocumentType => {
  if (extension === 'pdf') return 'pdf'
  if (extension === 'doc' || extension === 'docx') return 'word'
  if (extension === 'xls' || extension === 'xlsx' || extension === 'csv') return 'excel'
  if (['png', 'jpg', 'jpeg', 'webp', 'gif', 'bmp'].includes(extension)) return 'image'
  return 'text'
}

const summarizeBy = (
  cases: GeneratedWebAutomationCase[],
  pick: (testCase: GeneratedWebAutomationCase) => string
): ImportedWebCaseSummaryOption[] => {
  const counts = new Map<string, number>()
  for (const testCase of cases) {
    const value = pick(testCase)
    counts.set(value, (counts.get(value) || 0) + 1)
  }
  return Array.from(counts.entries()).map(([value, count]) => ({ value, count }))
}
