import { reactive } from 'vue'
import { fetchTemplateConfigFromBackend, putTemplateConfigToBackend } from '../domain/platformBackendRepository'
import { defaultTemplateConfig, loadTemplateConfig, markTemplateConfigPending, mirrorConfirmedTemplateConfig,
  readTemplateConfigRawPair, serializeTemplateTarget, equalTemplateRaw, restoreTemplateConfigRawPair, removeTemplateConfigKeys, type TemplatePendingV1, type TemplateConfig, type TemplateConfigRawPair,
  type TemplateWriteReport } from '../domain/templateConfigRepository'
import type { TemplateAuthority, TemplateOperation, TemplateTransport } from './workspaceStorage'

export class TemplateOperationAbortedError extends Error {
  constructor(message = '模板工作区已失效') { super(message); this.name = 'TemplateOperationAbortedError' }
}
export class TemplateAdapterError extends Error {
  constructor(public reason: string, message: string, public partial?: TemplateWriteReport) { super(message); this.name = 'TemplateAdapterError' }
}
const canonical = (value: unknown): string => {
  if (value === null || typeof value !== 'object') return JSON.stringify(value)
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`
  return `{${Object.keys(value).sort().map(k => `${JSON.stringify(k)}:${canonical((value as Record<string, unknown>)[k])}`).join(',')}}`
}
// Synchronous SHA-256 keeps construction/reload classification read-only and immediate.
export const templateHashSync = (raw: string): string => {
  const bytes = new TextEncoder().encode(raw)
  const size = Math.ceil((bytes.length + 9) / 64) * 64
  const buffer = new Uint8Array(size); buffer.set(bytes); buffer[bytes.length] = 128
  const view = new DataView(buffer.buffer)
  view.setUint32(size - 8, Math.floor(bytes.length / 0x20000000)); view.setUint32(size - 4, bytes.length * 8)
  const primes: number[] = []
  for (let n = 2; primes.length < 64; n++) if (primes.every(p => p * p > n || n % p !== 0)) primes.push(n)
  const fractional = (x: number) => ((x - Math.floor(x)) * 0x100000000) >>> 0
  const h = primes.slice(0, 8).map(p => fractional(Math.sqrt(p)))
  const k = primes.map(p => fractional(Math.cbrt(p)))
  const rotate = (x: number, n: number) => (x >>> n) | (x << (32 - n))
  for (let offset = 0; offset < size; offset += 64) {
    const w = new Uint32Array(64)
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(offset + i * 4)
    for (let i = 16; i < 64; i++) {
      const x = w[i-15], y = w[i-2]
      w[i] = w[i-16] + (rotate(x,7)^rotate(x,18)^(x>>>3)) + w[i-7] + (rotate(y,17)^rotate(y,19)^(y>>>10))
    }
    let [a,b,c,d,e,f,g,z] = h
    for (let i = 0; i < 64; i++) {
      const t1 = (z + (rotate(e,6)^rotate(e,11)^rotate(e,25)) + ((e&f)^(~e&g)) + k[i] + w[i]) >>> 0
      const t2 = ((rotate(a,2)^rotate(a,13)^rotate(a,22)) + ((a&b)^(a&c)^(b&c))) >>> 0
      z=g;g=f;f=e;e=(d+t1)>>>0;d=c;c=b;b=a;a=(t1+t2)>>>0
    }
    for (const [i,v] of [a,b,c,d,e,f,g,z].entries()) h[i] = (h[i]+v)>>>0
  }
  return h.map(v=>v.toString(16).padStart(8,'0')).join('')
}
export const templateHash = async (raw: string): Promise<string> => templateHashSync(raw)
export const templateFingerprintSync = (config: TemplateConfig): string => templateHashSync(canonical(JSON.parse(serializeTemplateTarget(config))))
export const templateFingerprint = async (config: TemplateConfig): Promise<string> => templateFingerprintSync(config)
export const configFromRaw = (raw: string | null): TemplateConfig => loadTemplateConfig({ getItem: () => raw } as unknown as Storage)
const validRawConfig = (raw: string | null): boolean => {
  if (raw === null) return true
  try { const v = JSON.parse(raw); return Array.isArray(v?.interfaceFields) && Array.isArray(v?.caseTypes) } catch { return false }
}
interface Baseline { raw: TemplateConfigRawPair; config: TemplateConfig; fingerprint: string }
export interface TemplateAdapterDeps { beginTransport: () => TemplateTransport }

/** One lease spans pending, GET, optional PUT, mirror and readback. */
export function createTemplateOperationAdapter(deps: TemplateAdapterDeps) {
  let identity: TemplateAuthority | null = null
  let readBaseline: TemplateConfigRawPair | null = null
  let confirmed: Baseline | null = null
  let pendingRestorePoint: TemplateConfigRawPair | null = null
  let writerSeq = 0
  let active: { seq: number; op: TemplateOperation; cancel: () => void } | null = null
  let adoptSeq = 0
  let disposed = false
  const settleLease = (seq: number | undefined, cancel = false) => {
    if (!active || active.seq !== seq) return
    const owned = active
    active = null; recovery.phase = 'idle'
    if (cancel) owned.cancel()
  }
  let receipt: { receiptId: string; authority: TemplateAuthority; targetRaw: string; target: TemplateConfig; updatedAt: string; expected: TemplateConfigRawPair; project: string } | null = null
  const recovery = reactive({ reason: null as string | null, phase: 'idle', blocked: false, partial: undefined as TemplateWriteReport | undefined,
    identityUsable: false, owner: '', project: '', hasPending: false })
  const fail = (reason: string, message: string, partial?: TemplateWriteReport): never => {
    Object.assign(recovery, { reason, blocked: true, partial })
    throw new TemplateAdapterError(reason, message, partial)
  }
  const classify = () => {
    pendingRestorePoint = null
    recovery.hasPending = !!readBaseline && readBaseline.pending !== null
    if (!readBaseline?.pending) {
      Object.assign(recovery, { reason: null, blocked: false })
      return
    }
    let reason = 'unconfirmed'
    try {
      const m = JSON.parse(readBaseline.pending) as TemplatePendingV1
      if (m.markerVersion !== 1 || typeof m.operationId !== 'string' || !Number.isSafeInteger(m.writerSeq)
        || typeof m.targetConfigRaw !== 'string' || templateHashSync(m.targetConfigRaw) !== m.targetRawHash) reason = 'pending-invalid'
      else if (!m.confirmedBaselineRaw || m.confirmedBaselineRaw.pending !== null) reason = 'baseline-unknown'
      else if (!validRawConfig(m.confirmedBaselineRaw.config) || templateFingerprintSync(configFromRaw(m.confirmedBaselineRaw.config)) !== m.confirmedFingerprint) reason = 'pending-invalid'
      else {
        confirmed = { raw: m.confirmedBaselineRaw, config: configFromRaw(m.confirmedBaselineRaw.config), fingerprint: m.confirmedFingerprint! }
        pendingRestorePoint = m.confirmedBaselineRaw
        if (m.targetConfigRaw !== readBaseline.config) reason = 'pending-inconsistent'
      }
    } catch { reason = 'pending-invalid' }
    Object.assign(recovery, { reason, blocked: true })
  }
  const bind = (authority: TemplateAuthority | null) => {
    adoptSeq++; settleLease(active?.seq, true); identity = authority; confirmed = null
    Object.assign(recovery, { reason: null, blocked: false, partial: undefined, identityUsable: authority?.state() === 'usable', owner: authority?.owner ?? '', phase: 'idle' })
    try {
      readBaseline = authority ? readTemplateConfigRawPair(authority.storage) : null
      recovery.hasPending = readBaseline?.pending !== null && readBaseline !== null
      if (!authority) Object.assign(recovery, { reason: 'identity', blocked: true })
      else classify()
    } catch { readBaseline = null; Object.assign(recovery, { reason: 'unreadable', blocked: true }) }
  }
  const run = async <T>(task: (op: TemplateOperation, gate: () => void) => Promise<T>, transport?: TemplateTransport): Promise<T> => {
    if (disposed || active || !identity || identity.state() !== 'usable') throw new TemplateOperationAbortedError()
    if (!readBaseline) return fail('unreadable', '本地缓存不可读取；请重新读取本地')
    const seq = ++writerSeq
    const op = identity.beginOperation('all', null, adoptSeq, 0, transport ?? deps.beginTransport())
    if (!op) throw new TemplateOperationAbortedError()
    let cancel!: () => void
    const cancelled = new Promise<never>((_, reject) => { cancel = () => reject(new TemplateOperationAbortedError()) })
    active = { seq, op, cancel }; recovery.phase = 'running'; recovery.project = op.transport.projectName
    let expired = false
    let timer: ReturnType<typeof setTimeout> | undefined
    const deadline = new Promise<never>((_, reject) => { timer = setTimeout(() => { expired = true; reject(new TemplateAdapterError('unknown-result', '模板请求超时，后端结果未知；请显式核对后恢复')) }, 30000) })
    const gate = () => {
      if (expired || disposed || op.authority !== identity || op.authority.state() !== 'usable' || op.adoptSeq !== adoptSeq || active?.seq !== seq || !op.transport.isCurrent()) throw new TemplateOperationAbortedError()
    }
    try { gate(); return await Promise.race([task(op, gate), deadline, cancelled]) }
    catch (e) {
      if (op.adoptSeq === adoptSeq) {
        if (expired) Object.assign(recovery, { reason: 'unknown-result', blocked: true })
        else if (e instanceof TemplateOperationAbortedError) Object.assign(recovery, { reason: 'identity', blocked: true, identityUsable: identity?.state() === 'usable' })
        else if (!(e instanceof TemplateAdapterError)) Object.assign(recovery, { reason: 'backend', blocked: true })
      }
      throw e
    } finally { clearTimeout(timer); settleLease(seq) }
  }
  const checked = (report: TemplateWriteReport, gate: () => void): TemplateConfigRawPair => {
    gate()
    if (!report.ok) { readBaseline = null; return fail('storage', '本地配置/元数据同步未完成，修改仍保留在当前界面', report) }
    readBaseline = report.rawPair; recovery.hasPending = report.rawPair.pending !== null
    return report.rawPair
  }
  const mirror = async (op: TemplateOperation, gate: () => void, target: TemplateConfig, raw: string, updatedAt: string) => {
    gate()
    receipt = { receiptId: crypto.randomUUID(), authority: op.authority, targetRaw: raw, target, updatedAt, expected: readBaseline!, project: op.transport.projectName }
    const report = mirrorConfirmedTemplateConfig(op.authority.storage, raw, updatedAt, gate, readBaseline!)
    gate()
    if (!report.ok) {
      // The completed prefix is exact even if final readback failed. Never infer success.
      const expected = { ...receipt.expected }
      for (const k of report.completed) expected[k] = k === 'config' ? raw : k === 'meta' ? JSON.stringify({ updatedAt }) : null
      receipt.expected = expected
    }
    const pair = checked(report, gate)
    const fingerprint = await templateFingerprint(target); gate()
    confirmed = { raw: pair, config: target, fingerprint }
    Object.assign(recovery, { reason: null, blocked: false, partial: undefined })
    return target
  }
  const load = (transport?: TemplateTransport) => run(async (op, gate) => {
    const fetched = await fetchTemplateConfigFromBackend(op.transport, gate); gate()
    if (readBaseline!.pending !== null) return fail('unconfirmed', '本地模板未确认，请显式恢复')
    const local = configFromRaw(readBaseline!.config)
    if (!validRawConfig(readBaseline!.config) || await templateFingerprint(local) !== await templateFingerprint(fetched.config)) return fail('conflict', '模板配置与后端不一致；请显式选择本地或后端')
    gate()
    return mirror(op, gate, fetched.config, serializeTemplateTarget(fetched.config), fetched.updatedAt)
  }, transport)
  const save = (target: TemplateConfig, explicitRetry = false) => run(async (op, gate) => {
    if (recovery.blocked) {
      if (!explicitRetry || !['backend', 'unconfirmed'].includes(recovery.reason ?? '')) return fail(recovery.reason ?? 'unconfirmed', '本地模板尚未确认，请显式恢复')
      const observed = readTemplateConfigRawPair(op.authority.storage)
      if (!readBaseline || !equalTemplateRaw(observed, readBaseline)) return fail('ownership', '模板未确认标记已改变')
      if (observed.pending !== null) {
        try {
          const m = JSON.parse(observed.pending) as TemplatePendingV1
          if (m.markerVersion !== 1 || typeof m.operationId !== 'string' || !Number.isSafeInteger(m.writerSeq)
            || typeof m.targetConfigRaw !== 'string' || await templateHash(m.targetConfigRaw) !== m.targetRawHash) return fail('pending-invalid', '本地未确认标记无效')
          gate()
          if (m.targetConfigRaw !== observed.config) return fail('pending-inconsistent', '本地模板与未确认标记不一致')
          if (!m.confirmedBaselineRaw || m.confirmedBaselineRaw.pending !== null || !validRawConfig(m.confirmedBaselineRaw.config)) return fail('baseline-unknown', '确认基线未知，请显式采用后端或覆盖')
          const config = configFromRaw(m.confirmedBaselineRaw.config)
          const fingerprint = await templateFingerprint(config); gate()
          if (fingerprint !== m.confirmedFingerprint) return fail('pending-invalid', '确认基线校验失败')
          confirmed = { raw: m.confirmedBaselineRaw, config, fingerprint }
        } catch(e) { if (e instanceof TemplateAdapterError || e instanceof TemplateOperationAbortedError) throw e; return fail('pending-invalid', '本地未确认标记无效') }
      }
      recovery.blocked = false
    }
    const raw = serializeTemplateTarget(target)
    const hash = await templateHash(raw); gate()
    readBaseline = checked(markTemplateConfigPending(op.authority.storage, { markerVersion: 1,
      operationId: crypto.randomUUID(), writerSeq, confirmedBaselineRaw: confirmed?.raw ?? null,
      confirmedFingerprint: confirmed?.fingerprint ?? null, targetConfigRaw: raw, targetRawHash: hash }, gate, readBaseline!), gate)
    const fetched = await fetchTemplateConfigFromBackend(op.transport, gate); gate()
    const G = await templateFingerprint(fetched.config), B = await templateFingerprint(target); gate()
    if (G === B) return mirror(op, gate, target, raw, fetched.updatedAt)
    if (!confirmed || G !== confirmed.fingerprint) return fail('conflict', '后端模板已改变，本地修改未确认；重试不能覆盖后端')
    const receipt = await putTemplateConfigToBackend(op.transport, target, gate); gate()
    return mirror(op, gate, target, raw, receipt.updatedAt)
  })
  const pushLocal = async (target: TemplateConfig, confirm: (summary: string) => Promise<boolean>, currentIntent: () => boolean) => {
    // Intent does not acquire a writer or advance workspace generation.
    const owner = identity, sequence = adoptSeq, rawPair = readBaseline, frozen = JSON.parse(serializeTemplateTarget(target)) as TemplateConfig
    if (!owner || !rawPair || active || owner.state() !== 'usable') throw new TemplateOperationAbortedError()
    const preview = await run((op, gate) => fetchTemplateConfigFromBackend(op.transport, gate))
    if (owner !== identity || sequence !== adoptSeq || !currentIntent()) throw new TemplateOperationAbortedError('覆盖确认已过期')
    const previewFingerprint = await templateFingerprint(preview.config), targetFingerprint = await templateFingerprint(frozen)
    const summary = `账号 ${owner.owner} · 项目 ${recovery.project}\n本地完整模板（字段及类型）：${serializeTemplateTarget(frozen)}\n将覆盖的后端模板：${serializeTemplateTarget(preview.config)}`
    if (!await confirm(summary)) return null
    if (owner !== identity || sequence !== adoptSeq || !currentIntent()) throw new TemplateOperationAbortedError('覆盖确认已过期')
    const pushPermit = Object.freeze({ owner, sequence, targetFingerprint, previewFingerprint, rawPair })
    return run(async (op, gate) => {
      const intentGate = () => { gate(); if (!currentIntent() || pushPermit.owner !== identity || pushPermit.sequence !== adoptSeq) throw new TemplateOperationAbortedError('覆盖确认已过期') }
      intentGate()
      if (!equalTemplateRaw(readTemplateConfigRawPair(op.authority.storage), pushPermit.rawPair)) return fail('ownership', '本地标记已改变，请重新确认')
      const fetched = await fetchTemplateConfigFromBackend(op.transport, intentGate); intentGate()
      const G = await templateFingerprint(fetched.config); intentGate()
      if (G !== targetFingerprint && G !== previewFingerprint) return fail('conflict', '后端预览已改变，请重新确认覆盖')
      const raw = serializeTemplateTarget(frozen), hash = await templateHash(raw); intentGate()
      checked(markTemplateConfigPending(op.authority.storage, { markerVersion: 1, operationId: crypto.randomUUID(), writerSeq,
        confirmedBaselineRaw: confirmed?.raw ?? null, confirmedFingerprint: confirmed?.fingerprint ?? null,
        targetConfigRaw: raw, targetRawHash: hash }, intentGate, readBaseline!), intentGate)
      const receipt = G === targetFingerprint ? fetched : await putTemplateConfigToBackend(op.transport, frozen, intentGate)
      intentGate(); return mirror(op, intentGate, frozen, raw, receipt.updatedAt)
    })
  }
  const retryMirror = async (receiptId: string) => {
    const owned = receipt
    if (!owned || owned.receiptId !== receiptId || owned.authority !== identity || recovery.reason !== 'storage') throw new TemplateOperationAbortedError('本地镜像凭据已过期')
    if (!identity || identity.state() !== 'usable') throw new TemplateOperationAbortedError()
    const observed = readTemplateConfigRawPair(identity.storage)
    if (!equalTemplateRaw(observed, owned.expected)) return fail('ownership', '镜像标记已改变，不能重试')
    readBaseline = observed
    return run(async (op, gate) => {
      if (op.transport.projectName !== owned.project) throw new TemplateOperationAbortedError('镜像项目已改变')
      return mirror(op, gate, owned.target, owned.targetRaw, owned.updatedAt)
    })
  }
  const reloadBaseline = () => {
    if (active || !identity || identity.state() !== 'usable' || disposed) throw new TemplateOperationAbortedError()
    try {
      readBaseline = readTemplateConfigRawPair(identity.storage)
      classify()
      return readBaseline
    } catch(e) { readBaseline = null; return fail('unreadable', '本地缓存不可读取') }
  }
  const markUnreadable = () => {
    readBaseline = null
    confirmed = null
    pendingRestorePoint = null
    Object.assign(recovery, { reason: 'unreadable', blocked: true, identityUsable: identity?.state() === 'usable', owner: identity?.owner ?? '', hasPending: false })
  }
  const takeBackend = async (confirm: () => Promise<boolean>, currentIntent: () => boolean) => {
    const owner = identity, sequence = adoptSeq
    if (!await confirm()) return null
    if (owner !== identity || sequence !== adoptSeq || !currentIntent()) throw new TemplateOperationAbortedError('采用后端确认已过期')
    if (!readBaseline) reloadBaseline()
    return run(async (op, gate) => {
      const intentGate = () => { gate(); if (!currentIntent()) throw new TemplateOperationAbortedError('采用后端确认已过期') }
      const fetched = await fetchTemplateConfigFromBackend(op.transport, intentGate); intentGate()
      return mirror(op, intentGate, fetched.config, serializeTemplateTarget(fetched.config), fetched.updatedAt)
    })
  }
  const importConfig = (config: TemplateConfig) => run(async (op, gate) => {
    const before = readTemplateConfigRawPair(op.authority.storage)
    if (!equalTemplateRaw(before, readBaseline!)) return fail('ownership', '本地模板已改变，不能导入')
    const raw = serializeTemplateTarget(config), hash = await templateHash(raw); gate()
    const marker: TemplatePendingV1 = { markerVersion: 1, operationId: crypto.randomUUID(), writerSeq,
      confirmedBaselineRaw: confirmed?.raw ?? null, confirmedFingerprint: confirmed?.fingerprint ?? null, targetConfigRaw: raw, targetRawHash: hash }
    const report = markTemplateConfigPending(op.authority.storage, marker, gate, before)
    gate()
    if (!report.ok) {
      const expected = { ...before }
      // Reconstruct only this lease's completed prefix, including config when
      // the final readback failed; never adopt bytes changed by another writer.
      if (report.completed.includes('pending')) expected.pending = JSON.stringify(marker)
      if (report.completed.includes('config')) expected.config = raw
      const rollback = restoreTemplateConfigRawPair(op.authority.storage, before, gate, expected)
      gate()
      if (!rollback.ok) return fail('storage', '模板导入失败且回滚未完成', rollback)
      readBaseline = rollback.rawPair
      return fail('unconfirmed', '模板导入失败，已恢复导入前三键', report)
    }
    checked(report, gate); classify(); recovery.reason = 'unconfirmed'; recovery.blocked = true
    return configFromRaw(raw)
  })
  const reset = (currentIntent: () => boolean) => run(async(op, gate) => {
    const owned = () => { gate(); if(!currentIntent())throw new TemplateOperationAbortedError('恢复默认确认已过期') }
    const report=removeTemplateConfigKeys(op.authority.storage,owned,readBaseline!)
    checked(report, owned); receipt=null; recovery.reason=null;recovery.blocked=false
    return report
  })
  const restore = (target: TemplateConfigRawPair | null) => run(async (op, gate) => {
    if (!target) return fail('baseline-unknown', '没有可恢复的精确本地快照')
    const raw = checked(restoreTemplateConfigRawPair(op.authority.storage, target, gate, readBaseline!), gate)
    Object.assign(recovery, { reason: raw.pending === null ? null : 'unconfirmed', blocked: raw.pending !== null })
    receipt = null
    return raw
  })
  const authorityKey = () => identity ? JSON.stringify([identity.owner,identity.generation,identity.epoch]) : ''
  const producerEnvelope = () => ({authorityKey:authorityKey(),writerSeq,adoptSeq})
  const matchesEnvelope = (event: {authorityKey?:string;writerSeq?:number|null;adoptSeq?:number}) => !disposed && event.authorityKey===authorityKey() && event.writerSeq===writerSeq && event.adoptSeq===adoptSeq
  const acceptsAdoption = (event: {authorityKey?:string;writerSeq?:number|null;adoptSeq?:number;config:TemplateConfig;rawPair?:TemplateConfigRawPair|'unreadable';kind:string;mirror?:string}) => {
    if(!matchesEnvelope(event))return false
    if(event.kind==='clear' && event.rawPair==='unreadable')return !!identity && identity.state()==='usable' && !readBaseline && recovery.reason==='unreadable'
    if(event.kind==='clear' && !identity)return !event.rawPair && !readBaseline
    if(identity?.state()!=='usable')return false
    if(event.kind==='conflict')return true
    if(event.kind==='backend'&&event.mirror==='done')return !!confirmed&&event.rawPair!=='unreadable'&&!!event.rawPair&&equalTemplateRaw(event.rawPair,confirmed.raw)&&templateFingerprintSync(event.config)===confirmed.fingerprint
    if(event.rawPair==='unreadable'||!readBaseline||!event.rawPair||!equalTemplateRaw(event.rawPair,readBaseline))return false
    return event.kind==='backend'||templateFingerprintSync(event.config)===templateFingerprintSync(configFromRaw(event.rawPair.config))
  }
  const invalidate = () => { adoptSeq++; settleLease(active?.seq, true); receipt = null; recovery.phase = 'idle'; recovery.blocked = true; recovery.reason = 'workspace-superseded' }
  const supersedeAdoption = () => { adoptSeq++; settleLease(active?.seq, true); recovery.phase = 'idle' }
  const dispose = () => { disposed = true; invalidate() }
  return { producerEnvelope, matchesEnvelope, acceptsAdoption, supersedeAdoption, bind, load, save, pushLocal, retryMirror, reloadBaseline, markUnreadable, takeBackend, importConfig, restore, reset, invalidate, dispose, get receipt() { return receipt }, retrySave: (target: TemplateConfig) => save(target, true), recovery, get confirmed() { return confirmed }, get pendingRestorePoint() { return pendingRestorePoint }, get rawPair() { return readBaseline },
    get authority() { return identity } }
}
export type TemplateOperationAdapter = ReturnType<typeof createTemplateOperationAdapter>
