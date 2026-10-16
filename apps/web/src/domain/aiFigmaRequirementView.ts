export interface AiFigmaNodeView {
  figmaNodeId: string
  nodeId: string
  nodeName: string
  nodeType: string
  parentNodeId: string
  componentName: string
}

export interface AiFigmaNodeTreeItem {
  id: string
  label: string
  type: string
  componentName: string
  children: AiFigmaNodeTreeItem[]
}

export interface AiFigmaDraftEvidence {
  sourceType: string
  fileKey: string
  nodeId: string
  nodeName: string
  nodeType: string
  componentName: string
}

export interface AiFigmaDraftPointView {
  resultOrigin?: string
  acceptanceState?: string
  evidence?: AiFigmaDraftEvidence
}

export interface AiFigmaChangeRow {
  changeType: 'added' | 'changed' | 'removed' | string
  nodeId: string
  nodeName: string
  changedFields: string[]
}

export interface AiFigmaElementCandidateView {
  candidateId: string
  componentKey: string
  componentName: string
  nodeId: string
  nodeName: string
  role: string
  suggestedLocatorName: string
  status: 'pending' | 'confirmed' | 'rejected'
}

export interface AiFigmaElementCandidateGroup {
  key: string
  componentName: string
  role: string
  pendingCount: number
  confirmedCount: number
  items: AiFigmaElementCandidateView[]
}

export const buildFigmaNodeTree = (nodes: AiFigmaNodeView[]): AiFigmaNodeTreeItem[] => {
  const items = new Map<string, AiFigmaNodeTreeItem>()
  for (const node of nodes) {
    items.set(node.nodeId, {
      id: node.nodeId,
      label: node.nodeName,
      type: node.nodeType,
      componentName: node.componentName,
      children: []
    })
  }
  const roots: AiFigmaNodeTreeItem[] = []
  for (const node of nodes) {
    const item = items.get(node.nodeId)
    if (!item) continue
    const parent = node.parentNodeId ? items.get(node.parentNodeId) : undefined
    if (parent) parent.children.push(item)
    else roots.push(item)
  }
  return roots
}

export const figmaDraftEvidenceLabel = (point: AiFigmaDraftPointView): string => {
  const evidence = point.evidence
  if (!evidence) return 'Figma 证据'
  return [evidence.fileKey, evidence.nodeName, evidence.componentName].filter(Boolean).join(' · ')
}

export const sortFigmaChangeRows = (rows: AiFigmaChangeRow[]): AiFigmaChangeRow[] => {
  const priority: Record<string, number> = { added: 0, changed: 1, removed: 2 }
  return [...rows].sort((left, right) =>
    (priority[left.changeType] ?? 9) - (priority[right.changeType] ?? 9)
    || left.nodeId.localeCompare(right.nodeId)
  )
}

export const groupFigmaElementCandidates = (candidates: AiFigmaElementCandidateView[]): AiFigmaElementCandidateGroup[] => {
  const groups = new Map<string, AiFigmaElementCandidateGroup>()
  for (const candidate of candidates) {
    const key = candidate.componentKey || `${candidate.role}:${candidate.componentName}`
    const group = groups.get(key) || {
      key,
      componentName: candidate.componentName,
      role: candidate.role,
      pendingCount: 0,
      confirmedCount: 0,
      items: []
    }
    group.items.push(candidate)
    group.pendingCount = group.items.filter((item) => item.status === 'pending').length
    group.confirmedCount = group.items.filter((item) => item.status === 'confirmed').length
    groups.set(key, group)
  }
  return [...groups.values()]
}
