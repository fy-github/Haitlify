import type {
  ParsedRequirementDocument,
  RequirementDesignDraftCase
} from './requirementDocumentRepository'
import type { GeneratedWebAutomationCase, WebAutomationDocumentType, WebAutomationStep } from './webAutomationCase'

export const buildDesignAutomationDraftCases = (
  payload: ParsedRequirementDocument
): GeneratedWebAutomationCase[] => {
  const designCases: GeneratedWebAutomationCase[] = []
  const sourceDocumentType = normalizeWebAutomationDocumentType(payload.documentType)
  const componentHints = payload.visualSummary?.componentHints || []
  const visibleComponentHints = componentHints.filter((hint) => !hint.includes('--'))
  const hierarchicalComponentHints = visibleComponentHints
    .filter((hint) => hint.includes(' > '))
    .sort((left, right) => right.length - left.length)

  const interactionHints = componentHints.filter((hint) => hint.includes('--'))
  interactionHints.forEach((hint, index) => {
    const draftCase = buildInteractionDraftCase(
      hint,
      hierarchicalComponentHints,
      payload,
      index + 1
    )
    if (draftCase) {
      designCases.push(draftCase)
    }
  })

  const structureHints = hierarchicalComponentHints.length >= 2
    ? hierarchicalComponentHints.slice(0, 2)
    : visibleComponentHints.slice(0, 2)
  const orderedStructureHints = [...structureHints].sort(compareStructureHint)

  if (orderedStructureHints.length >= 2) {
    designCases.push(
      buildGeneratedDraftCase({
        id: 'WEB-DESIGN-0002',
        project: payload.projectName || '默认项目',
        module: '设计稿结构',
        pageName: '设计稿结构',
        title: '设计稿-表单层级与关键组件可见',
        sourceFile: payload.fileName,
        sourceDocumentType,
        steps: orderedStructureHints.map((hint, index) =>
          step(`step-${index + 1}`, 'assertVisible', hint)
        )
      })
    )
  }

  return designCases
}

const buildInteractionDraftCase = (
  hint: string,
  hierarchicalComponentHints: string[],
  payload: ParsedRequirementDocument,
  sequence: number
): GeneratedWebAutomationCase | null => {
  const [sourcePart, actionPart] = hint.split(' --')
  const source = sourcePart?.trim() || '未命名组件'
  const trigger = actionPart?.split('-->')[0]?.trim() || ''
  const destination = actionPart?.split('-->')[1]?.split('[')[0]?.trim() || '目标页面'
  const sourcePath = pickBestComponentPath(source, hierarchicalComponentHints) || source
  const sourceDocumentType = normalizeWebAutomationDocumentType(payload.documentType)
  const id = `WEB-DESIGN-${String(sequence).padStart(4, '0')}`

  if (trigger === 'ON_CLICK' && destination === 'BACK') {
    return buildGeneratedDraftCase({
      id,
      project: payload.projectName || '默认项目',
      module: '设计稿交互',
      pageName: '设计稿流程',
      title: '设计稿-点击返回上一层',
      sourceFile: payload.fileName,
      sourceDocumentType,
      steps: [
        step('step-1', 'assertVisible', sourcePath),
        step('step-2', 'click', sourcePath),
        step('step-3', 'assertHidden', '当前页面')
      ]
    })
  }

  if (trigger === 'ON_HOVER') {
    return buildGeneratedDraftCase({
      id,
      project: payload.projectName || '默认项目',
      module: '设计稿交互',
      pageName: '设计稿流程',
      title: `设计稿-悬浮显示 ${destination}`,
      sourceFile: payload.fileName,
      sourceDocumentType,
      steps: [
        step('step-1', 'assertVisible', sourcePath),
        step('step-2', 'hover', sourcePath),
        step('step-3', 'waitForVisible', destination),
        step('step-4', 'assertVisible', destination)
      ]
    })
  }

  if (trigger === 'ON_CLICK' && hint.includes('[SCROLL_TO')) {
    return buildGeneratedDraftCase({
      id,
      project: payload.projectName || '默认项目',
      module: '设计稿交互',
      pageName: '设计稿流程',
      title: `设计稿-点击滚动到 ${destination}`,
      sourceFile: payload.fileName,
      sourceDocumentType,
      steps: [
        step('step-1', 'assertVisible', sourcePath),
        step('step-2', 'click', sourcePath),
        step('step-3', 'waitForVisible', destination),
        step('step-4', 'assertVisible', destination)
      ]
    })
  }

  if (trigger === 'ON_DRAG') {
    return buildGeneratedDraftCase({
      id,
      project: payload.projectName || '默认项目',
      module: '设计稿交互',
      pageName: '设计稿流程',
      title: `设计稿-拖拽打开 ${destination}`,
      sourceFile: payload.fileName,
      sourceDocumentType,
      steps: [
        step('step-1', 'assertVisible', sourcePath),
        step('step-2', 'hover', sourcePath),
        step('step-3', 'waitForVisible', destination),
        step('step-4', 'assertVisible', destination)
      ]
    })
  }

  if (trigger === 'AFTER_TIMEOUT') {
    return buildGeneratedDraftCase({
      id,
      project: payload.projectName || '默认项目',
      module: '设计稿交互',
      pageName: '设计稿流程',
      title: '设计稿-等待提示自动消失',
      sourceFile: payload.fileName,
      sourceDocumentType,
      steps: [
        step('step-1', 'assertVisible', sourcePath),
        step('step-2', 'assertHidden', sourcePath)
      ]
    })
  }

  if (trigger === 'ON_CLICK' && hint.includes('[CLOSE_OVERLAY')) {
    return buildGeneratedDraftCase({
      id,
      project: payload.projectName || '默认项目',
      module: '设计稿交互',
      pageName: '设计稿流程',
      title: '设计稿-关闭浮层',
      sourceFile: payload.fileName,
      sourceDocumentType,
      steps: [
        step('step-1', 'assertVisible', sourcePath),
        step('step-2', 'click', sourcePath),
        step('step-3', 'assertHidden', '当前浮层')
      ]
    })
  }

  if (trigger === 'ON_CLICK' && destination !== 'BACK') {
    return buildGeneratedDraftCase({
      id,
      project: payload.projectName || '默认项目',
      module: '设计稿交互',
      pageName: '设计稿流程',
      title: `设计稿-${source.includes('Button') ? '主按钮跳转到 ' : ''}${destination}`,
      sourceFile: payload.fileName,
      sourceDocumentType,
      steps: [
        step('step-1', 'assertVisible', sourcePath),
        step('step-2', 'click', sourcePath),
        step('step-3', 'waitForUrl', destination),
        step('step-4', 'assertUrl', destination)
      ]
    })
  }

  return null
}

const buildGeneratedDraftCase = (input: {
  id: string
  project: string
  module: string
  pageName: string
  title: string
  sourceFile: string
  sourceDocumentType: GeneratedWebAutomationCase['metadata']['sourceDocumentType']
  steps: WebAutomationStep[]
}): GeneratedWebAutomationCase => ({
  id: input.id,
  row: {
    caseId: input.id,
    project: input.project,
    module: input.module,
    pageName: input.pageName,
    title: input.title,
    caseType: '设计草案',
    precondition: '',
    startUrl: '/',
    browser: 'chromium',
    viewport: '1440x900',
    testData: '{}',
    steps: input.steps.map((item) => `${item.action} ${item.target}`).join('\n'),
    locators: input.steps.map((item) => item.target).join(', '),
    assertions: input.steps.filter((item) => item.action.startsWith('assert')).map((item) => `${item.action} ${item.target}`).join('\n'),
    waitStrategy: 'networkidle',
    screenshotStrategy: 'on_failure',
    priority: 'P1',
    executionResult: '未执行',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: '设计稿,自动化草案',
    sourceFile: input.sourceFile,
    remark: ''
  },
  metadata: {
    sourceFile: input.sourceFile,
    sourceDocumentType: input.sourceDocumentType,
    pageName: input.pageName,
    startUrl: '/',
    browser: 'chromium',
    viewport: '1440x900',
    steps: input.steps,
    locators: input.steps.map((item, index) => ({
      id: `locator-${index + 1}`,
      name: item.target,
      locatorType: 'text',
      value: toLocatorDraftValue(item).primary,
      fallbackValue: toLocatorDraftValue(item).fallback,
      confidence: toLocatorDraftValue(item).confidence,
      reason: toLocatorDraftValue(item).reason
    })),
    assertions: input.steps
      .filter((item) => item.action.startsWith('assert'))
      .map((item, index) => ({
        id: `assertion-${index + 1}`,
        name: item.name,
        type: item.action === 'assertUrl' ? 'url' : 'visible',
        target: item.target
      })),
    variables: {},
    runOrder: Number(input.id.replace('WEB-DESIGN-', '')) || 1
  }
})

const normalizeWebAutomationDocumentType = (value: string): WebAutomationDocumentType => {
  const knownTypes = new Set<WebAutomationDocumentType>([
    'word',
    'pdf',
    'markdown',
    'yaml',
    'json',
    'excel',
    'image',
    'figma',
    'text',
    'manual'
  ])
  return knownTypes.has(value as WebAutomationDocumentType) ? value as WebAutomationDocumentType : 'manual'
}

const step = (id: string, action: WebAutomationStep['action'], target: string): WebAutomationStep => ({
  id,
  name: `${action}-${id}`,
  action,
  targetType: action === 'waitForUrl' || action === 'assertUrl' ? 'url' : 'locator',
  target
})

const toLocatorDraftValue = (
  step: WebAutomationStep
): { primary: string; fallback?: string; confidence: 'high' | 'medium' | 'low'; reason: string } => {
  if (step.targetType === 'url') {
    return {
      primary: step.target,
      confidence: 'high',
      reason: 'URL 类目标直接使用路径或地址作为主候选'
    }
  }
  const leaf = step.target.split(' > ').slice(-1)[0]
  const slug = slugifyDesignName(leaf)
  const pathSlug = slugifyDesignName(step.target)
  const role = inferRoleDraft(leaf)
  const primary = role !== 'role=generic'
    ? `${role}[name="${leaf}"]`
    : `text=${leaf}`
  const fallbacks = [
    primary === `text=${leaf}` ? undefined : `text=${leaf}`,
    `testid=${slug}`,
    `css=[data-design-path="${pathSlug}"]`
  ].filter(Boolean)
  const confidence = inferLocatorConfidence(role)
  return {
    primary,
    confidence,
    reason: inferLocatorReason(role),
    ...(fallbacks.length ? { fallback: fallbacks.join(' | ') } : {})
  }
}

const inferRoleDraft = (leaf: string): string => {
  if (leaf.includes('Button')) return 'role=button'
  if (leaf.includes('Input')) return 'role=textbox'
  if (leaf.includes('Toast')) return 'role=status'
  if (leaf.includes('Tooltip')) return 'role=tooltip'
  if (leaf.includes('Tips')) return 'role=tooltip'
  if (leaf.includes('Card')) return 'role=region'
  if (leaf.includes('Drawer')) return 'role=dialog'
  if (leaf.includes('Jump')) return 'role=link'
  if (leaf.includes('Link')) return 'role=link'
  return 'role=generic'
}

const inferLocatorConfidence = (role: string): 'high' | 'medium' | 'low' => {
  if (role === 'role=status' || role === 'role=generic') return 'medium'
  return 'high'
}

const inferLocatorReason = (role: string): string => {
  if (role === 'role=button') return '按钮组件优先使用 role=button，文本与设计路径作为回退候选'
  if (role === 'role=textbox') return '输入框组件优先使用 role=textbox，文本与设计路径作为回退候选'
  if (role === 'role=link') return '链接类组件优先使用 role=link，文本与设计路径作为回退候选'
  if (role === 'role=tooltip') return '提示类组件优先使用 role=tooltip，文本与测试标识作为补充候选'
  if (role === 'role=dialog') return '抽屉或弹层优先使用 role=dialog，文本与设计路径作为回退候选'
  if (role === 'role=region') return '区域容器优先使用 role=region，文本与设计路径作为回退候选'
  if (role === 'role=status') return '提示类组件建议使用 role=status，文本与测试标识作为补充候选'
  return '暂未识别明确组件语义，先使用文本候选并保留 testid、css 回退'
}

const slugifyDesignName = (value: string): string =>
  value
    .toLowerCase()
    .replace(/[^a-z0-9\u4e00-\u9fa5]+/g, '-')
    .replace(/^-+|-+$/g, '')

const pickBestComponentPath = (componentName: string, candidates: string[]): string | undefined =>
  candidates.find((hint) => hint.endsWith(componentName))

const compareStructureHint = (left: string, right: string): number => {
  const leftRank = rankStructureHint(left)
  const rightRank = rankStructureHint(right)
  if (leftRank !== rightRank) return leftRank - rightRank
  return left.localeCompare(right)
}

const rankStructureHint = (value: string): number => {
  if (value.includes('Input')) return 0
  if (value.includes('Button')) return 1
  return 2
}

export const toRequirementDesignDraftCases = (
  cases: GeneratedWebAutomationCase[]
): RequirementDesignDraftCase[] =>
  cases.map((testCase) => ({
    title: testCase.row.title,
    module: testCase.row.module,
    pageName: testCase.row.pageName,
    caseType: testCase.row.caseType,
    steps: testCase.metadata.steps.map((step) => `${step.action} ${step.target}`)
  }))

export const finalizeDesignDraftCasesForLibrary = (
  cases: GeneratedWebAutomationCase[]
): GeneratedWebAutomationCase[] =>
  cases.map((testCase) =>
    testCase.row.caseType === '设计草案'
      ? {
          ...testCase,
          row: {
            ...testCase.row,
            caseType: '设计自动化',
            tags: testCase.row.tags.replace('设计稿,自动化草案', '设计稿,设计自动化')
          }
        }
      : testCase
  )
