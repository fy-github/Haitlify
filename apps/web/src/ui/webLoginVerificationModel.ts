import type { WebAutomationRunSnapshot } from '../domain/webAutomationRunRepository'

export interface WebLoginVerificationDialogModel {
  webLoginVerificationVisible: boolean
  closeWebLoginVerification: () => Promise<void>
  webLoginVerificationRunning: boolean
  webLoginVerificationSnapshot: WebAutomationRunSnapshot | undefined
}
