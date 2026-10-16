import type {
  GeneratedWebAutomationCase,
  SavedWebAutomationCase,
  WebAssertion,
  WebAutomationAction,
  WebAutomationStep,
  WebLocator
} from './webAutomationCase'

export type EditableWebAutomationCase = GeneratedWebAutomationCase | SavedWebAutomationCase

export const serializeWebAutomationSteps = (steps: WebAutomationStep[]): string =>
  steps.map((step) => {
    if (step.action === 'manual') {
      return step.target
    }
    if (step.action === 'input' && step.value !== undefined) {
      return `input ${step.target} = ${step.value}`
    }
    return `${step.action} ${step.target}`.trim()
  }).join('\n')

export const syncWebAutomationMetadataFromRow = <T extends EditableWebAutomationCase>(testCase: T): T => {
  const steps = parseSteps(testCase.row.steps)
  const locators = parseLocators(testCase.row.locators)
  const assertions = parseAssertions(testCase.row.assertions)

  return {
    ...testCase,
    metadata: {
      ...testCase.metadata,
      pageName: testCase.row.pageName,
      startUrl: testCase.row.startUrl,
      browser: normalizeBrowser(testCase.row.browser),
      viewport: testCase.row.viewport,
      steps,
      locators,
      assertions
    }
  }
}

const parseSteps = (value: string): WebAutomationStep[] =>
  value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line, index) => {
      const inputMatch = line.match(/^input\s+(.+?)\s*=\s*(.+)$/)
      if (inputMatch) {
        return {
          id: `step-${index + 1}`,
          name: `input-${index + 1}`,
          action: 'input' as const,
          targetType: 'locator' as const,
          target: inputMatch[1].trim(),
          value: inputMatch[2].trim()
        }
      }

      if (!isDslStepLine(line)) {
        return {
          id: `step-${index + 1}`,
          name: `manual-${index + 1}`,
          action: 'manual' as const,
          targetType: 'elementRef' as const,
          target: line
        }
      }

      const [actionToken, ...rest] = line.split(' ')
      const action = normalizeAction(actionToken)
      return {
        id: `step-${index + 1}`,
        name: `${actionToken}-${index + 1}`,
        action,
        targetType: action === 'goto' ? 'url' : action === 'manual' ? 'elementRef' : 'locator',
        target: rest.join(' ').trim()
      }
    })

const parseLocators = (value: string): WebLocator[] =>
  value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line, index) => ({
      id: `locator-${index + 1}`,
      name: line,
      locatorType: inferLocatorType(line),
      value: line
    }))

const parseAssertions = (value: string): WebAssertion[] =>
  value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line, index) => {
      const text = line.replace(/^assertVisible\s+/, '').trim()
      return {
        id: `assertion-${index + 1}`,
        name: `assertion-${index + 1}`,
        type: 'visible' as const,
        target: text
      }
    })

const normalizeAction = (value: string): WebAutomationAction => {
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
  return supported.includes(value as WebAutomationAction) ? value as WebAutomationAction : 'click'
}

const isDslStepLine = (line: string): boolean =>
  /^input\s+.+?\s*=\s*.+$/i.test(line)
  || /^(manual|goto|click|select|hover|waitForVisible|waitForUrl|assertText|assertUrl|assertVisible|assertHidden|scrollIntoView|paginate|screenshot)(\s+|$)/i.test(line)

const inferLocatorType = (value: string): WebLocator['locatorType'] => {
  const normalized = value.trim()
  const prefix = normalized.match(/^([a-zA-Z][\w-]*)=/)?.[1]?.toLowerCase()
  if (prefix === 'xpath') return 'xpath'
  if (prefix === 'text') return 'text'
  if (prefix === 'role') return 'role'
  if (prefix === 'testid') return 'testId'
  if (
    normalized.startsWith('//')
    || normalized.startsWith('.//')
    || normalized.startsWith('..//')
    || normalized.startsWith('(//')
  ) return 'xpath'
  return 'css'
}

const normalizeBrowser = (value: string): 'chromium' | 'chrome' | 'msedge' | 'firefox' | 'webkit' => {
  if (value === 'chrome' || value === 'msedge' || value === 'firefox' || value === 'webkit') return value
  return 'chromium'
}
