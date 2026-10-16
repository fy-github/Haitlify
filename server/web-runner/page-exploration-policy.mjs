import {
  SAFE_REPAIR_ACTIONS,
  SAFE_REPAIR_CATEGORIES,
  isKnownRepairCategory,
  normalizeRepairActionType,
  normalizeRepairBudget,
  normalizeRepairCategory,
  shouldAttemptReauthenticate,
  shouldRetrySameFailure
} from './page-repair-policy.mjs'

export const EXPLORATION_DEFAULTS = Object.freeze({
  maxRounds: 5,
  maxDurationMs: 90_000,
  maxSameFailureRetries: 2,
  maxLoginRetries: 1,
  maxReloads: 1,
  maxPaginationSteps: 3,
  minimumTotalScore: 70,
  minimumElementScore: 25
})
export {
  SAFE_REPAIR_ACTIONS,
  SAFE_REPAIR_CATEGORIES,
  isKnownRepairCategory,
  normalizeRepairActionType,
  normalizeRepairBudget,
  normalizeRepairCategory,
  shouldAttemptReauthenticate,
  shouldRetrySameFailure
} from './page-repair-policy.mjs'

const GENERIC_TERMS = new Set([
  '项目',
  '设备',
  '应用',
  '系统',
  '服务',
  '用户',
  '首页',
  '管理',
  '页面',
  '列表'
])
const FORBIDDEN_ACTIONS = ['确定', '确认', '提交', '保存', '删除', '停用', '启用', '开通', '支付', '发送', '上传']
const SYNONYMS = new Map([
  ['停用', ['停用', '关闭', '禁用']],
  ['重新开通', ['重新开通', '恢复', '启用']],
  ['删除', ['删除', '移除']]
])

const toList = (value) => {
  if (Array.isArray(value)) return value
  return value === undefined || value === null ? [] : [value]
}

const normalizeTerms = (value, predicate = Boolean) => Array.from(new Set(
  toList(value).map(normalizeText).filter(predicate)
))

export const normalizeText = (value) => String(value ?? '')
  .trim()
  .toLowerCase()
  .replace(/\s+/g, '')
  .replace(/[，。；、：:（）()【】\[\]]/g, '')
  .replace(/(?:业务列表页|业务页面|列表页面|列表页|页面|模块|业务)$/g, '')

export const normalizeIntent = (intent = {}) => {
  const source = intent && typeof intent === 'object' ? intent : {}
  const explicitNegativeTerms = normalizeTerms(
    Object.hasOwn(source, 'explicitNegativeTerms') ? source.explicitNegativeTerms : source.negativeTerms
  )
  return {
    pageAliases: normalizeTerms(source.pageAliases, (term) => term.length >= 2),
    routeHints: normalizeTerms(source.routeHints),
    elementTerms: normalizeTerms(source.elementTerms, (term) => term.length >= 2 && !GENERIC_TERMS.has(term)),
    actionTerms: normalizeTerms(source.actionTerms),
    stateTerms: normalizeTerms(source.stateTerms),
    expectedTexts: normalizeTerms(source.expectedTexts),
    explicitNegativeTerms,
    negativeTerms: normalizeTerms([
      ...explicitNegativeTerms,
      ...GENERIC_TERMS
    ])
  }
}

export const expandIntentTerms = (terms = []) => Array.from(new Set(
  normalizeTerms(terms).flatMap((term) => SYNONYMS.get(term) || [term])
))

export const isForbiddenBusinessAction = (label) => {
  const normalizedLabel = normalizeText(label)
  return FORBIDDEN_ACTIONS.some((term) => normalizedLabel.includes(term))
}

const elementText = (element) => {
  if (element === undefined || element === null) return ''
  if (typeof element !== 'object') return normalizeText(element)
  return normalizeText([
    element.label,
    element.accessibleName,
    element.text,
    element.role,
    element.tableColumn,
    element.rowContext,
    element.contextText
  ].filter(Boolean).join(' '))
}

const elementContextText = (element) => {
  if (!element || typeof element !== 'object') return ''
  return normalizeText([
    element.tableColumn,
    element.rowContext,
    element.contextText
  ].filter(Boolean).join(' '))
}

const isNegativeElement = (element, negativeTerms, intent = {}) => {
  const descriptor = elementText(element)
  if (!negativeTerms.some((term) => term && descriptor.includes(term))) return false
  // A matching row is stronger evidence than incidental global-navigation copy
  // in a surrounding cell/context summary.
  const rowContext = normalizeText(element?.rowContext)
  const rowSignals = [...toList(intent.elementTerms), ...toList(intent.stateTerms)]
    .map(normalizeText)
    .filter(Boolean)
  if (rowContext && rowSignals.some((term) => rowContext.includes(term))) {
    const primary = normalizeText([
      element?.label,
      element?.accessibleName,
      element?.text,
      element?.role,
      element?.tableColumn
    ].filter(Boolean).join(' '))
    return negativeTerms.some((term) => term && primary.includes(term))
  }
  return true
}

const isNegativeText = (value, negativeTerms) => {
  const text = normalizeText(value)
  return negativeTerms.some((term) => term && text.includes(term))
}

const pageSignals = (observation, intent) => [
  {
    text: normalizeText(observation.url),
    terms: [...intent.pageAliases, ...intent.routeHints]
  },
  ...[
    observation.title,
    ...toList(observation.breadcrumbs),
    ...toList(observation.activeMenus),
    ...toList(observation.headings)
  ].map((value) => ({ text: normalizeText(value), terms: intent.pageAliases }))
]

export const scoreObservation = (rawIntent = {}, rawObservation = {}, rawBudget = {}, preferredLocators = []) => {
  const intent = normalizeIntent(rawIntent)
  const observation = rawObservation && typeof rawObservation === 'object' ? rawObservation : {}
  const budget = { ...EXPLORATION_DEFAULTS, ...(rawBudget || {}) }
  const pageScore = Math.min(40, pageSignals(observation, intent).reduce((score, signal) => (
    signal.terms.some((term) => term && signal.text.includes(term)) ? score + 20 : score
  ), 0))

  const expandedElements = expandIntentTerms(intent.elementTerms)
  const preferred = new Set(toList(preferredLocators).map((value) => String(value || '')).filter(Boolean))
  const elements = toList(observation.elements)
  const eligibleElements = elements.filter((element) => !isNegativeElement(element, intent.explicitNegativeTerms, intent))
  const relevantElements = eligibleElements.filter((element) => {
    const descriptor = elementText(element)
    return expandedElements.some((term) => descriptor.includes(term))
      || (element?.historicalVerified === true && (!preferred.size || preferred.has(element?.locator)))
  })
  const elementScore = Math.min(40, relevantElements.length * 25)

  const contextText = normalizeText([
    ...eligibleElements.map(elementContextText),
    ...[
      ...toList(observation.stateTexts),
      observation.stateText
    ].filter((value) => !isNegativeText(value, intent.explicitNegativeTerms))
  ].filter(Boolean).join(' '))
  const stateTerms = Array.from(new Set([...intent.stateTerms, ...intent.expectedTexts]))
  const stateScore = Math.min(20, stateTerms.reduce((score, term) => (
    contextText.includes(term) ? score + 10 : score
  ), 0))
  const totalScore = pageScore + elementScore + stateScore

  return {
    pageScore,
    elementScore,
    stateScore,
    totalScore,
    relevantElements,
    matched: totalScore >= budget.minimumTotalScore && elementScore >= budget.minimumElementScore
  }
}
