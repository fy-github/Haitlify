import type { GeneratedWebAutomationCase, WebLocator } from './webAutomationCase'

export interface DesignLocatorCard {
  id: string
  name: string
  primary: string
  fallback: string[]
  confidence: 'high' | 'medium' | 'low'
  reason: string
  confirmed: boolean
}

export interface FigmaElementCandidate {
  candidateId: string
  componentKey: string
  componentName: string
  nodeId: string
  nodeName: string
  role: string
  suggestedLocatorName: string
  status: 'pending' | 'confirmed' | 'rejected'
  evidence: Record<string, unknown>
}

export const designLocatorCards = (testCase: GeneratedWebAutomationCase | undefined): DesignLocatorCard[] => {
  if (!testCase) return []
  return testCase.metadata.locators.map(toDesignLocatorCard)
}

export const figmaElementCandidateCards = (candidates: FigmaElementCandidate[]): DesignLocatorCard[] =>
  candidates
    .filter((candidate) => candidate.status !== 'rejected')
    .map((candidate) => ({
      id: candidate.candidateId,
      name: candidate.componentName || candidate.nodeName,
      primary: `role=${candidate.role}[name="${candidate.suggestedLocatorName}"]`,
      fallback: [`testid=${candidate.suggestedLocatorName}`, `figma-node=${candidate.nodeId}`],
      confidence: candidate.status === 'confirmed' ? 'high' : 'medium',
      reason: `Figma 组件 ${candidate.componentName || candidate.nodeName} ${candidate.status === 'confirmed' ? '已确认，可作为元素库候选' : '待人工确认'}`,
      confirmed: candidate.status === 'confirmed'
    }))

const toDesignLocatorCard = (locator: WebLocator): DesignLocatorCard => ({
  id: locator.id,
  name: locator.name,
  primary: locator.value,
  fallback: locator.fallbackValue ? locator.fallbackValue.split(' | ').map((item) => item.trim()).filter(Boolean) : [],
  confidence: locator.confidence || 'low',
  reason: locator.reason || '待人工确认候选定位器',
  confirmed: locator.confirmed || false
})
