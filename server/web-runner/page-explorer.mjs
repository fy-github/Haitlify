import {
  EXPLORATION_DEFAULTS,
  normalizeIntent,
  scoreObservation,
  normalizeText,
  normalizeRepairCategory,
  SAFE_REPAIR_CATEGORIES
} from './page-exploration-policy.mjs'
import { pageFingerprint } from './page-evidence.mjs'
import {
  normalizeRepairBudget,
  shouldRetrySameFailure
} from './page-repair-policy.mjs'

const normalizedBudget = (rawBudget) => {
  const source = rawBudget && typeof rawBudget === 'object' ? rawBudget : {}
  const budget = { ...EXPLORATION_DEFAULTS, ...source, ...normalizeRepairBudget(source) }
  for (const [key, defaultValue] of Object.entries(EXPLORATION_DEFAULTS)) {
    const value = Number(source[key])
    budget[key] = Number.isFinite(value) && value >= 0 ? value : defaultValue
  }
  budget.maxRounds = Math.floor(budget.maxRounds)
  return budget
}

const toList = (value) => {
  if (Array.isArray(value)) return value
  return value === undefined || value === null ? [] : [value]
}

const observationSignature = (observation, scores) => {
  const normalizedTexts = Array.from(new Set([
    ...toList(observation?.stateTexts).map((value) => normalizeText(value)).filter(Boolean),
    normalizeText(observation?.stateText)
  ])).sort((left, right) => left.localeCompare(right))
  const relevantCandidates = scores.relevantElements.map((element) => ({
    locator: element?.locator || '',
    label: element?.label || '',
    rowContext: element?.rowContext || '',
    contextText: element?.contextText || '',
    accessibleName: element?.accessibleName || '',
    accessibilityText: element?.accessibilityText || element?.accessibleText || '',
    text: element?.text || ''
  })).sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right)))
  return JSON.stringify([
    observation?.url || '',
    observation?.fingerprint || observation?.pageFingerprint || pageFingerprint(observation),
    normalizedTexts,
    relevantCandidates
  ])
}

const actionSummary = (action) => {
  const type = action?.type || 'unknown-action'
  const target = action?.target || action?.locator
  return target ? `${type}:${target}` : type
}

const failureKey = (value) => value === undefined || value === null || value === '' ? undefined : String(value)

export const runPageExploration = async (request, adapter) => {
  const sourceRequest = request && typeof request === 'object' ? request : {}
  const sourceAdapter = adapter && typeof adapter === 'object' ? adapter : {}
  const intent = normalizeIntent(sourceRequest.intent)
  const budget = normalizedBudget(sourceRequest.budget)
  const observe = typeof sourceAdapter.observe === 'function' ? (...args) => sourceAdapter.observe(...args) : async () => ({})
  const chooseAction = typeof sourceAdapter.chooseAction === 'function' ? (...args) => sourceAdapter.chooseAction(...args) : async () => undefined
  const performAction = typeof sourceAdapter.performAction === 'function' ? (...args) => sourceAdapter.performAction(...args) : async () => undefined
  const clock = typeof sourceAdapter.now === 'function' ? sourceAdapter.now.bind(sourceAdapter) : Date.now
  let lastTime = Number(clock())
  if (!Number.isFinite(lastTime)) lastTime = 0
  const now = () => {
    const reading = Number(clock())
    if (Number.isFinite(reading)) lastTime = Math.max(lastTime, reading)
    return lastTime
  }
  const startedAt = lastTime
  const rounds = []
  const repairTrail = []
  const sameFailureCounts = new Map()
  const inferredFailureTypes = new Map()
  let reauthenticateCount = 0
  let previousSignature
  let previousFingerprint = ''
  let lastFailureCategory = ''

  const result = async (fields) => {
    const durationMs = Math.max(0, now() - startedAt)
    let adapterTrail
    let trailError
    if (typeof sourceAdapter.navigationTrail === 'function') {
      try {
        adapterTrail = await sourceAdapter.navigationTrail()
      } catch (error) {
        trailError = String(error?.message || error)
      }
    }
    const lastRound = rounds.at(-1)
    return {
      ...fields,
      sourcePointId: sourceRequest.sourcePointId,
      rounds,
      finalObservation: fields.finalObservation ?? lastRound?.observation,
      scores: fields.scores ?? lastRound?.scores,
      pageFingerprint: fields.pageFingerprint ?? lastRound?.pageFingerprint,
      navigationTrail: Array.isArray(adapterTrail)
        ? adapterTrail
        : rounds.filter((round) => round.action).map((round) => actionSummary(round.action)),
      durationMs,
      remainingDurationMs: Math.max(0, budget.maxDurationMs - durationMs),
      repairTrail,
      lastFailureCategory,
      sameFailureCount: fields.sameFailureCount ?? lastRound?.sameFailureCount ?? Math.max(0, ...sameFailureCounts.values()),
      reauthenticateCount: fields.reauthenticateCount ?? reauthenticateCount,
      ...(trailError ? { trailError } : {})
    }
  }

  for (let round = 1; round <= budget.maxRounds; round += 1) {
    if (now() - startedAt >= budget.maxDurationMs) return result({ status: 'exhausted', failureReason: 'duration_exhausted' })

    const observation = await observe()
    const currentFingerprint = String(observation?.pageFingerprint || pageFingerprint(observation) || observation?.fingerprint || observation?.url || '')
    if (repairTrail.length && repairTrail.at(-1).changedFingerprint === null) {
      repairTrail[repairTrail.length - 1].changedFingerprint = previousFingerprint !== currentFingerprint
    }
    const scores = scoreObservation(intent, observation, budget, sourceRequest.preferredEvidenceLocators)
    const entry = {
      round,
      observation,
      scores,
      sourcePointId: sourceRequest.sourcePointId,
      pageFingerprint: currentFingerprint,
      lastFailureCategory,
      sameFailureCount: sameFailureCounts.get(lastFailureCategory) || 0,
      reauthenticateCount,
      remainingDurationMs: Math.max(0, budget.maxDurationMs - (now() - startedAt)),
      repairTrail: repairTrail.map((item) => ({ ...item }))
    }
    rounds.push(entry)
    if (scores.matched) return result({ status: 'matched', finalObservation: observation, scores })

    const signature = observationSignature(observation, scores)
    if (signature === previousSignature) return result({ status: 'exhausted', failureReason: 'observation_stagnated' })
    previousSignature = signature

    const observationFailure = failureKey(observation?.failureCategory || observation?.failureReason)
    if (observationFailure) lastFailureCategory = observationFailure
    const action = await chooseAction({ intent, budget, rounds, observation, scores, lastFailureCategory })
    if (!action) return result({ status: 'blocked', failureReason: 'no_safe_action' })
    const repairCategory = normalizeRepairCategory(action?.repairCategory || action?.category || action?.type)
    if (!repairCategory || !SAFE_REPAIR_CATEGORIES.includes(repairCategory)) {
      return result({ status: 'blocked', failureReason: 'unsafe_repair_category' })
    }
    entry.action = action
    entry.repairCategory = repairCategory
    repairTrail.push({ category: repairCategory, round, changedFingerprint: null })

    const actionType = failureKey(action?.type) || 'unknown-action'
    let currentFailureKey = failureKey(action?.failureType) || inferredFailureTypes.get(actionType) || actionType
    const previousFailureCount = sameFailureCounts.get(currentFailureKey) || 0
    entry.sameFailureCount = previousFailureCount
    if (!shouldRetrySameFailure(previousFailureCount, budget) && inferredFailureTypes.has(actionType)) {
      return result({ status: 'exhausted', failureReason: 'same_failure_limit', sameFailureCount: previousFailureCount, reauthenticateCount })
    }
    if (actionType === 'reauthenticate') {
      reauthenticateCount += 1
      entry.reauthenticateCount = reauthenticateCount
    }

    try {
      await performAction(action)
    } catch (error) {
      const typedFailure = failureKey(error?.failureType)
      if (!typedFailure) throw error
      currentFailureKey = typedFailure
      const currentFailureCount = (sameFailureCounts.get(currentFailureKey) || 0) + 1
      sameFailureCounts.set(currentFailureKey, currentFailureCount)
      inferredFailureTypes.set(actionType, currentFailureKey)
      entry.actionFailure = { failureType: currentFailureKey, message: String(error?.message || error) }
      lastFailureCategory = currentFailureKey
      entry.sameFailureCount = currentFailureCount
      entry.lastFailureCategory = lastFailureCategory
      entry.reauthenticateCount = reauthenticateCount
      repairTrail[repairTrail.length - 1].changedFingerprint = null
      if (currentFailureCount > budget.maxSameFailureRetries) return result({ status: 'exhausted', failureReason: 'same_failure_limit', sameFailureCount: currentFailureCount, reauthenticateCount })
    }
    previousFingerprint = currentFingerprint
    entry.lastFailureCategory = lastFailureCategory
    entry.sameFailureCount = sameFailureCounts.get(lastFailureCategory) || entry.sameFailureCount || 0
    entry.reauthenticateCount = reauthenticateCount
  }

  return result({ status: 'exhausted', failureReason: 'round_budget_exhausted' })
}
