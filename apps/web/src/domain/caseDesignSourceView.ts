import type {
  PersistedRequirementSource,
  PersistedRequirementSourceKind
} from './caseDesignDraftRepository'

export type CaseDesignSourceKind = PersistedRequirementSourceKind
export type CaseDesignSourceStatus = 'ready' | 'error' | 'reattach'

export interface CaseDesignSourceView {
  name: string
  kind: CaseDesignSourceKind
  kindLabel: string
  status: CaseDesignSourceStatus
  detail: string
}

export const buildCaseDesignSourceView = (
  sources: PersistedRequirementSource[],
  errors: Record<string, string>
): CaseDesignSourceView[] => sources.map((source) => {
  const kind = source.kind
  if (errors[source.name]) {
    return { name: source.name, kind, kindLabel: kindLabel(kind), status: 'error', detail: errors[source.name] }
  }
  if (source.needsReattach) {
    return { name: source.name, kind, kindLabel: kindLabel(kind), status: 'reattach', detail: '需要重新选择原文件' }
  }
  return {
    name: source.name,
    kind,
    kindLabel: kindLabel(kind),
    status: 'ready',
    detail: kind === 'figma' ? '已连接' : '已就绪'
  }
})

const kindLabel = (kind: CaseDesignSourceKind): string => ({
  document: '文档',
  figma: 'Figma',
  openapi: 'OpenAPI'
}[kind])
