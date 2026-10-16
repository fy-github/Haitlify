export type AutomationGenerationCaseKind = 'interface' | 'web_automation'

export interface AutomationGenerationCase {
  id: string
  row: {
    project: string
    module: string
  }
}

export interface AutomationGenerationFilter {
  project: string
  module: string
}

export interface AutomationGenerationOption {
  value: string
  count: number
}

export interface AutomationGenerationScope<T extends AutomationGenerationCase> {
  projects: AutomationGenerationOption[]
  modules: AutomationGenerationOption[]
  cases: T[]
}

export const buildAutomationGenerationScope = <
  TInterface extends AutomationGenerationCase,
  TWeb extends AutomationGenerationCase
>(
  kind: AutomationGenerationCaseKind,
  interfaceCases: TInterface[],
  webCases: TWeb[],
  filter: AutomationGenerationFilter
): AutomationGenerationScope<TInterface | TWeb> => {
  const sourceCases: Array<TInterface | TWeb> = kind === 'web_automation' ? webCases : interfaceCases
  const projectCases = filter.project === 'ALL'
    ? sourceCases
    : sourceCases.filter((testCase) => testCase.row.project === filter.project)

  return {
    projects: collectOptions(sourceCases, 'project'),
    modules: collectOptions(projectCases, 'module'),
    cases: projectCases.filter((testCase) =>
      filter.module === 'ALL' || testCase.row.module === filter.module
    )
  }
}

const collectOptions = (
  cases: AutomationGenerationCase[],
  field: 'project' | 'module'
): AutomationGenerationOption[] => {
  const counts = new Map<string, number>()
  cases.forEach((testCase) => {
    const value = testCase.row[field].trim()
    if (!value) return
    counts.set(value, (counts.get(value) || 0) + 1)
  })
  return Array.from(counts, ([value, count]) => ({ value, count }))
}
