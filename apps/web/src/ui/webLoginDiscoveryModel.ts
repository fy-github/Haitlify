import type { WebLoginDiscoveryCandidateRole } from '../domain/webLoginDiscoveryRepository'
import type { WebLoginDiscoveryCandidate } from '../domain/webLoginDiscoveryRepository'
import type { WebLoginDiscoverySnapshot } from '../domain/webLoginDiscoveryRepository'

export interface WebLoginDiscoveryDialogModel {
  webLoginDiscoveryDialogVisible: boolean
  closeWebLoginDiscovery: () => void
  webLoginDiscoveryConfirming: boolean
  webLoginDiscoverySnapshot: WebLoginDiscoverySnapshot | undefined
  confirmAndApplyWebLoginDiscovery: () => Promise<void>
  webLoginDiscoveryLoading: boolean
  canEdit: boolean
  startWebLoginDiscovery: () => Promise<void>
  webLoginDiscoverySelection: { usernameCandidateId: string; passwordCandidateId: string; submitCandidateId: string; }
  selectedWebLoginDiscoveryCandidate: (role: WebLoginDiscoveryCandidateRole) => WebLoginDiscoveryCandidate | undefined
}
