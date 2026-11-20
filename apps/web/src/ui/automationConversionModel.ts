import type { SavedWebAutomationCase } from '../domain/webAutomationCase'
import type { SavedInterfaceCase } from '../domain/caseRepository'
import type { AutomationGenerationScope } from '../domain/automationGenerationScope'

export interface AutomationConversionDialogModel {
  automationGenerateVisible: boolean
  canEdit: boolean
  confirmGenerateAutomationFromLibrary: () => void
  automationGenerateProject: string
  automationGenerationScope: AutomationGenerationScope<SavedInterfaceCase | SavedWebAutomationCase>
  automationGenerateModule: string
}
