import { computed, reactive, ref } from 'vue'
import { listSavedAutomationCases, type SavedAutomationCase } from '../domain/automationRepository'
import { listSavedWebAutomationRunCases, type SavedWebAutomationRunCase } from '../domain/webAutomationRepository'
import { type WebAutomationRunStatus } from '../domain/webAutomation'
import { buildAutomationGenerationScope } from '../domain/automationGenerationScope'
import { buildWebAutomationScope } from '../domain/webAutomationWorkspaceView'
import { type SavedInterfaceCase } from '../domain/caseRepository'
import { buildAutomationCaseIndex, collectAutomationMethods, collectAutomationModules, filterAutomationRecords } from '../domain/automationView'
import type { WebCaseStatusFilter } from '../components/automation/WebAutomationCaseTable.vue'
import { isDesignOriginAutomationCase } from '../domain/designOrigin'
import type { SavedWebAutomationCase } from '../domain/webAutomationCase'
import type { Ref } from 'vue'
import type { ComputedRef } from 'vue'
import type { CaseDesignNavKind } from '../domain/appNavigation'
import type { SavedCaseLibraryItem } from '../domain/caseLibraryView'

interface Dependencies {
  localStorage: Storage
  webAutomationStatusFilter: Ref<WebCaseStatusFilter, WebCaseStatusFilter>
  webAutomationResultStatus: (testCase: SavedWebAutomationRunCase) => WebAutomationRunStatus | "pending"
  webAutomationSelectedIds: Ref<string[], string[]>
  libraryCaseKind: Ref<CaseDesignNavKind, CaseDesignNavKind>
  filteredLibraryCases: ComputedRef<SavedInterfaceCase[]>
  filteredWebLibraryCases: ComputedRef<SavedCaseLibraryItem[]>
  automationGenerateProject: Ref<string, string>
  automationGenerateModule: Ref<string, string>
}

export function createExecutionCatalog(deps: Dependencies) {

const automationCases = ref<SavedAutomationCase[]>(listSavedAutomationCases(deps.localStorage))

const webAutomationCases = ref<SavedWebAutomationRunCase[]>(listSavedWebAutomationRunCases(deps.localStorage))

const automationFilter = reactive({
  project: 'ALL',
  module: 'ALL',
  method: 'ALL',
  source: 'ALL' as 'ALL' | 'design' | 'default',
  keyword: ''
})

const automationCaseIndex = computed(() => buildAutomationCaseIndex(automationCases.value))

const automationProjects = computed(() => automationCaseIndex.value.projectOptions)

const webAutomationProjects = computed(() => collectWebAutomationProjects(webAutomationCases.value))

const automationProjectRecords = computed(() =>
  automationFilter.project === 'ALL'
    ? automationCaseIndex.value.records
    : automationCaseIndex.value.records.filter((record) => record.project === automationFilter.project)
)

const webAutomationProjectCases = computed(() =>
  automationFilter.project === 'ALL'
    ? webAutomationCases.value
    : webAutomationCases.value.filter((testCase) => testCase.project === automationFilter.project)
)

const automationModules = computed(() => collectAutomationModules(automationProjectRecords.value))

const automationMethods = computed(() => collectAutomationMethods(automationProjectRecords.value))

const filteredAutomationRecords = computed(() =>
  filterAutomationRecords(automationCaseIndex.value.records, automationFilter)
)

const filteredAutomationCases = computed(() => filteredAutomationRecords.value.map((record) => record.caseItem))

const filteredWebAutomationCases = computed(() =>
  webAutomationCases.value.filter((testCase) => {
    if (automationFilter.project !== 'ALL' && testCase.project !== automationFilter.project) return false
    if (automationFilter.module !== 'ALL' && testCase.module !== automationFilter.module) return false
    const source = isDesignOriginAutomationCase(testCase.sourceCaseId) ? 'design' : 'default'
    if (automationFilter.source !== 'ALL' && source !== automationFilter.source) return false
    if (deps.webAutomationStatusFilter.value !== 'ALL' && deps.webAutomationResultStatus(testCase) !== deps.webAutomationStatusFilter.value) return false
    const keyword = automationFilter.keyword.trim().toLowerCase()
    if (!keyword) return true
    return [
      testCase.id,
      testCase.sourceCaseId,
      testCase.name,
      testCase.pageName,
      testCase.startUrl,
      testCase.module,
      testCase.project
    ].join(' ').toLowerCase().includes(keyword)
  })
)

const effectiveWebAutomationScope = computed(() =>
  buildWebAutomationScope(filteredWebAutomationCases.value, deps.webAutomationSelectedIds.value)
)

const automationGenerationScope = computed(() => buildAutomationGenerationScope(
  deps.libraryCaseKind.value,
  deps.filteredLibraryCases.value,
  deps.filteredWebLibraryCases.value as SavedWebAutomationCase[],
  {
    project: deps.automationGenerateProject.value,
    module: deps.automationGenerateModule.value
  }
))

const refreshAutomationCases = () => {
  automationCases.value = listSavedAutomationCases(deps.localStorage)
  webAutomationCases.value = listSavedWebAutomationRunCases(deps.localStorage)
}

const resetAutomationFilters = () => {
  automationFilter.project = 'ALL'
  automationFilter.module = 'ALL'
  automationFilter.method = 'ALL'
  automationFilter.keyword = ''
}

const collectWebAutomationProjects = (cases: SavedWebAutomationRunCase[]) => {
  const counts = new Map<string, number>()
  for (const testCase of cases) {
    counts.set(testCase.project, (counts.get(testCase.project) || 0) + 1)
  }
  return Array.from(counts.entries()).map(([value, count]) => ({ value, count }))
}

const reset = () => {
  Object.assign(automationFilter, { project: 'ALL', module: 'ALL', method: 'ALL', source: 'ALL', keyword: '' })
  refreshAutomationCases()
}
return {
  reset,
  automationCases,
  webAutomationCases,
  automationFilter,
  automationCaseIndex,
  automationProjects,
  webAutomationProjects,
  automationProjectRecords,
  webAutomationProjectCases,
  automationModules,
  automationMethods,
  filteredAutomationRecords,
  filteredAutomationCases,
  filteredWebAutomationCases,
  effectiveWebAutomationScope,
  automationGenerationScope,
  refreshAutomationCases,
  resetAutomationFilters,
  collectWebAutomationProjects,
}
}
