import { getAuthSessionEpoch } from '../domain/http'

export type TemplateAuthorityState = 'usable' | 'superseded' | 'unusable'
export interface TemplateTransport {
  fetch: (input: string, init?: RequestInit) => Promise<Response>
  projectName: string
  isCurrent: () => boolean
}
export interface TemplateOperation {
  readonly authority: TemplateAuthority
  readonly transport: TemplateTransport
  readonly section: 'fields' | 'types' | 'all'
  readonly attemptId: number | null
  readonly adoptSeq: number
  readonly startRevision: number
}
export interface TemplateAuthority {
  readonly owner: string
  readonly generation: number
  readonly epoch: number
  readonly storage: Storage
  state(): TemplateAuthorityState
  beginOperation(section: TemplateOperation['section'], attemptId: number | null, adoptSeq: number,
    startRevision: number, transport: TemplateTransport): TemplateOperation | null
}

/** Unowned legacy keys are deliberately NOT migrated or removed. */
export const isWorkspaceStorage = (storage: Storage): boolean => workspaceFacades.has(storage)
const workspaceFacades = new WeakSet<Storage>()
export function createWorkspaceStorage(backing: Storage) {
  let owner: string | undefined
  let epoch = -1
  const current = () => owner !== undefined && epoch === getAuthSessionEpoch()
  const capture = (isCurrent: () => boolean = current): Storage => {
    const prefix = owner === undefined ? undefined : `ai-test-platform.workspace.${encodeURIComponent(owner)}.`
    const capturedEpoch = epoch
    const valid = () => prefix !== undefined && capturedEpoch === getAuthSessionEpoch() && isCurrent()
    const keys = () => valid() ? Array.from({ length: backing.length }, (_, i) => backing.key(i)!).filter(key => key.startsWith(prefix!)) : []
    return {
      get length() { return keys().length },
      key: index => keys()[index]?.slice(prefix!.length) ?? null,
      getItem: key => valid() ? backing.getItem(prefix + key) : null,
      setItem: (key, value) => { if (valid()) backing.setItem(prefix + key, value) },
      removeItem: key => { if (valid()) backing.removeItem(prefix + key) },
      clear: () => { for (const key of keys()) backing.removeItem(key) }
    }
  }
  const storage: Storage = {
    get length() { return capture().length },
    key: index => capture().key(index),
    getItem: key => capture().getItem(key),
    setItem: (key, value) => capture().setItem(key, value),
    removeItem: key => capture().removeItem(key),
    clear: () => capture().clear()
  }
  workspaceFacades.add(storage)
  let generation = 0
  const captureStrict = (isCurrent: () => boolean): TemplateAuthority | null => {
    if (owner === undefined || epoch !== getAuthSessionEpoch() || !isCurrent()) return null
    const captured = { owner, generation, epoch }
    const prefix = `ai-test-platform.workspace.${encodeURIComponent(owner)}.`
    const state = (): TemplateAuthorityState => {
      if (owner === undefined) return 'unusable'
      if (owner !== captured.owner) return 'superseded'
      if (generation !== captured.generation) return 'superseded'
      if (captured.epoch !== getAuthSessionEpoch()) return 'superseded'
      return 'usable'
    }
    const valid = () => state() === 'usable'
    const keys = () => valid() ? Array.from({ length: backing.length }, (_, i) => backing.key(i)!).filter(k => k.startsWith(prefix)) : []
    const strictStorage: Storage = {
      get length() { return keys().length },
      key: i => keys()[i]?.slice(prefix.length) ?? null,
      getItem: k => valid() ? backing.getItem(prefix + k) : null,
      setItem: (k, v) => { if (valid()) backing.setItem(prefix + k, v) },
      removeItem: k => { if (valid()) backing.removeItem(prefix + k) },
      clear: () => { for (const k of keys()) { if (valid()) backing.removeItem(k) } }
    }
    const authority: TemplateAuthority = Object.freeze({ ...captured, storage: strictStorage, state,
      beginOperation(section, attemptId, adoptSeq, startRevision, transport) {
        if (!valid() || !transport.isCurrent()) return null
        return Object.freeze({ authority, transport: Object.freeze({ ...transport }), section, attemptId, adoptSeq, startRevision })
      }
    } as TemplateAuthority)
    return authority
  }
  return {
    storage,
    capture,
    captureStrict,
    activate: (identity?: string) => { owner = identity; epoch = getAuthSessionEpoch(); generation += 1 },
    invalidate: () => { owner = undefined; epoch = -1; generation += 1 }
  }
}
