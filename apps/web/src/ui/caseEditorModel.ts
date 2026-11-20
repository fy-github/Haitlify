import type { GeneratedInterfaceCase } from '../domain/caseGenerator'
import type { InterfaceCaseRow } from '../domain/caseGenerator'
import type { GeneratedWebAutomationCase } from '../domain/webAutomationCase'
import type { SavedWebAutomationCase } from '../domain/webAutomationCase'
import type { SavedInterfaceCase } from '../domain/caseRepository'
import type { ExecutionEnvironmentLibrary } from '../domain/executionEnvironmentRepository'
import type { FieldType } from '../domain/caseTemplates'

export interface CaseEditorDrawerModel {
  editVisible: boolean
  selectedCase: GeneratedInterfaceCase | GeneratedWebAutomationCase | SavedInterfaceCase | SavedWebAutomationCase | undefined
  visibleEditableFields: { key: string; label: string; type: FieldType; required: boolean; visible: boolean; exportable: boolean; aiGenerated: boolean; defaultValue?: string | undefined; }[]
  isMultilineField: (type: FieldType) => boolean
  editRow: { caseId: string; project: string; module: string; interfaceName: string; method: string; path: string; title: string; caseType: string; precondition: string; headers: string; pathParams: string; queryParams: string; body: string; steps: string; expectedResult: string; expectedResponseBody?: string | undefined; actualResponseBody?: string | undefined; assertions: string; priority: string; executionResult: string; executor: string; executedAt: string; defectId: string; tags: string; sourceFile: string; remark: string; }
  saveEdit: () => Promise<void>
  webEditRow: { caseId: string; project: string; module: string; pageName: string; title: string; naturalSteps?: string | undefined; naturalExpectedResult?: string | undefined; environmentId?: string | undefined; caseType: string; precondition: string; startUrl: string; browser: string; viewport: string; testData: string; steps: string; locators: string; assertions: string; waitStrategy: string; screenshotStrategy: string; retryCount?: number | string | undefined; priority: string; executionResult: string; executor: string; executedAt: string; defectId: string; tags: string; sourceFile: string; remark: string; }
  webExecutionEnvironmentLibrary: ExecutionEnvironmentLibrary
  applySelectedWebEnvironmentToEditRow: () => void
}
