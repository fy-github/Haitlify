// @vitest-environment jsdom
import { beforeEach, expect, it } from 'vitest'
import * as repo from './templateConfigRepository'
const keys = { config: repo.TEMPLATE_CONFIG_STORAGE_KEY, meta: repo.TEMPLATE_CONFIG_META_STORAGE_KEY, pending: 'ai-test-platform.template-config-pending.v1' }
const raw = () => Object.fromEntries(Object.entries(keys).map(([k,v]) => [k, localStorage.getItem(v)]))
beforeEach(() => localStorage.clear())
it.each([null, '  {broken pending\n'])('restores exact absent and invalid bytes, including pending %s', pending => {
  const target = { config: null, meta: ' {bad meta ', pending }
  for (const k of Object.values(keys)) localStorage.setItem(k, 'dirty')
  const calls: string[] = []
  const s = { getItem: (k: string) => localStorage.getItem(k), setItem: (k:string,v:string) => { calls.push('set:'+k); localStorage.setItem(k,v) }, removeItem: (k:string) => { calls.push('remove:'+k); localStorage.removeItem(k) } } as Storage
  const restore = (repo as any).restoreTemplateConfigRawPair
  if (restore) expect(restore(s, target).ok).toBe(true)
  else repo.saveTemplateConfig(s, repo.defaultTemplateConfig())
  expect(raw()).toEqual(target)
  expect(calls.map(s => s.slice(s.indexOf(':')+1))).toEqual([keys.meta, keys.config, keys.pending])
})
it.each(['pending','config'])('pending partial %s preserves confirmed meta and stops at the failed step',step=>{
 const before={config:'A',meta:'  {"updatedAt":"2026-01-01T00:00:00Z"}',pending:null}
 const data=new Map(Object.entries(keys).map(([k,v])=>[v,before[k as keyof typeof before]]));const calls:string[]=[]
 const s={getItem:(k:string)=>data.get(k)??null,setItem:(k:string,v:string)=>{calls.push(k);if(k===keys[step as keyof typeof keys])throw Error('quota');data.set(k,v)},removeItem:(k:string)=>data.delete(k)} as unknown as Storage
 const m={markerVersion:1,operationId:'op',writerSeq:1,confirmedBaselineRaw:before,confirmedFingerprint:'A',targetConfigRaw:'B',targetRawHash:'hash'}
 const report=(repo as any).markTemplateConfigPending(s,m,()=>{},before)
 expect(report).toMatchObject({ok:false,failedAt:step,completed:step==='pending'?[]:['pending'],remaining:step==='pending'?['pending','config']:['config']})
 expect(data.get(keys.meta)).toBe(before.meta);expect(data.get(keys.config)).toBe('A')
})
it.each(['config','meta','pending','readback'])('mirror partial %s never reports confirmed success',step=>{
 const data=new Map<string,string|null>([[keys.config,'B'],[keys.meta,'old'],[keys.pending,'marker']]);let writes=0
 const s={getItem:(k:string)=>{if(step==='readback'&&writes===3)throw Error('read denied');return data.get(k)??null},setItem:(k:string,v:string)=>{if(k===keys[step as keyof typeof keys])throw Error('quota');data.set(k,v);writes++},removeItem:(k:string)=>{if(k===keys[step as keyof typeof keys])throw Error('quota');data.delete(k);writes++}} as unknown as Storage
 const report=(repo as any).mirrorConfirmedTemplateConfig(s,'B','2026-01-01T00:00:00Z',()=>{},{config:'B',meta:'old',pending:'marker'})
 expect(report.ok).toBe(false);expect(report.failedAt).toBe(step)
 expect(report.completed).toEqual(step==='config'?[]:step==='meta'?['config']:step==='pending'?['config','meta']:['config','meta','pending'])
})
it('restore stops at config failure with a typed partial report', () => {
  const calls: string[] = []
  const s = { getItem: () => null, removeItem: (k:string) => { calls.push(k); if(k === keys.config) throw Error('quota') } } as unknown as Storage
  const result = (repo as any).restoreTemplateConfigRawPair?.(s,{ config:null,meta:null,pending:null })
  expect(result).toMatchObject({ok:false,failedAt:'config',completed:['meta'],remaining:['config','pending']})
  expect(calls).toEqual([keys.meta,keys.config])
})
