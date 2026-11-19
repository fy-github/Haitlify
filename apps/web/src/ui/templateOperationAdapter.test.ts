// @vitest-environment jsdom
import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { templateHashSync } from './templateOperationAdapter'
import { createWorkspaceStorage } from './workspaceStorage'
import { defaultTemplateConfig, saveTemplateConfig, updateCaseField, TEMPLATE_CONFIG_META_STORAGE_KEY as META, TEMPLATE_CONFIG_PENDING_STORAGE_KEY as PENDING } from '../domain/templateConfigRepository'
import { createLocalTemplateConfig as useTemplateConfig } from './useTemplateConfig'
import { loadTemplateConfigWithBackend, saveTemplateConfigWithBackend } from '../domain/platformBackendRepository'
const timestamp = '2026-01-01T00:00:00.000Z'
const clone = <T>(v:T):T => JSON.parse(JSON.stringify(v))
it.each(['','abc','中文🚀'.repeat(100)])('synchronous marker SHA matches WebCrypto %s',async raw=>{
 const digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(raw))
 expect(templateHashSync(raw)).toBe(Array.from(new Uint8Array(digest),b=>b.toString(16).padStart(2,'0')).join(''))
})
beforeEach(()=>localStorage.clear())
async function setup() {
 const ws=createWorkspaceStorage(localStorage); ws.activate('A'); const authority=ws.captureStrict(()=>true)!
 const A=defaultTemplateConfig(); saveTemplateConfig(authority.storage,A,{updatedAt:timestamp})
 let remote=clone(A); const puts:unknown[]=[]
 const fetch=vi.fn(async (url:string,init?:RequestInit)=>{
  if(url==='/api/projects') return new Response(JSON.stringify({items:[{id:1,name:'P',code:'P'}]}))
  if(init?.method==='PUT') { puts.push(JSON.parse(init.body as string)); remote=JSON.parse(init.body as string) }
  return new Response(JSON.stringify({...remote,updatedAt:timestamp}))
 })
 const path='./templateOperationAdapter'
 const mod=await import(/* @vite-ignore */path).catch(()=>null)
 const adapter=mod?.createTemplateOperationAdapter({beginTransport:()=>({fetch,projectName:'P',isCurrent:()=>true})})
 adapter?.bind(authority)
 return {ws,authority,A,puts,fetch,adapter,setRemote:(v:typeof A)=>{remote=v},
  load:()=>adapter ? adapter.load() : loadTemplateConfigWithBackend(authority.storage,'P',{fetchImpl:fetch}),
  save:(v:typeof A)=>adapter ? adapter.save(v) : saveTemplateConfigWithBackend(authority.storage,'P',v,{fetchImpl:fetch})}
}
it('workspace hook uses the adapter and adoption performs zero storage writes', async()=>{
 const h=await setup(); const A=await h.load()
 const writes=vi.spyOn(Storage.prototype,'setItem')
 const s=useTemplateConfig({storage:h.ws.storage,identity:()=>h.authority,operationAdapter:h.adapter} as any)
 expect(writes).not.toHaveBeenCalled()
 ;(s as any).adoptSavedConfig({...h.adapter.producerEnvelope(),kind:'backend',config:A,rawPair:h.adapter.rawPair,mirror:'done',confirmedUpdatedAt:timestamp})
 expect(writes).not.toHaveBeenCalled()
 s.patchField('project',{label:'B'}); await vi.waitFor(()=>expect(s.saveStatus.fields.state).toBe('saved'))
 expect(h.puts).toHaveLength(1)
 writes.mockRestore()
})
it('GET A then edit B keeps confirmed raw A in pending, PUTs B and confirms with server time', async()=>{
 const h=await setup(); await h.load(); const original=h.authority.storage.getItem(META)
 const B=updateCaseField(h.A,'project',{label:'B'})
 await h.save(B)
 expect(h.puts).toEqual([B]); expect(h.authority.storage.getItem(META)).toBe(original)
 expect(h.authority.storage.getItem(PENDING)).toBeNull()
 expect(h.adapter?.confirmed?.config).toEqual(B)
})
it('refresh discard restores marker baseline A rather than pending working B',async()=>{
 const h=await setup();await h.load();h.fetch.mockRejectedValueOnce(Error('offline'));await expect(h.save(updateCaseField(h.A,'project',{label:'B'}))).rejects.toThrow()
 h.adapter.bind(h.authority)
 const s=useTemplateConfig({storage:h.ws.storage,identity:()=>h.authority,operationAdapter:h.adapter,confirmDanger:async()=>true} as any)
 await s.discardUnsaved()
 expect(s.templateConfig.value).toEqual(h.A);expect(h.authority.storage.getItem(PENDING)).toBeNull()
})
it('server receipt retry mirrors frozen B without overwriting working D',async()=>{
 const h=await setup();await h.load();let release!:()=>void;const originalFetch=h.fetch.getMockImplementation()!
 h.fetch.mockImplementation(async(u:string,i?:RequestInit)=>{if(i?.method==='PUT')await new Promise<void>(ok=>{release=ok});return originalFetch(u,i)})
 const s=useTemplateConfig({storage:h.ws.storage,identity:()=>h.authority,operationAdapter:h.adapter} as any)
 s.patchField('project',{label:'B'});await vi.waitFor(()=>expect(release).toBeTypeOf('function'));s.patchCaseType('positive',{name:'D'})
 const originalSet=Storage.prototype.setItem;const spy=vi.spyOn(Storage.prototype,'setItem').mockImplementation(function(this:Storage,k:string,v:string){if(k.endsWith(META))throw Error('quota');return originalSet.call(this,k,v)})
 release();await vi.waitFor(()=>expect(s.saveStatus.types.state).toBe('failed'));spy.mockRestore()
 await s.retryMirror()
 expect(h.puts).toHaveLength(1);expect(s.templateConfig.value.caseTypes[0].name).toBe('D');expect(s.saveStatus.types.state).toBe('failed')
 expect(JSON.parse(h.authority.storage.getItem('ai-test-platform.template-config.v1')!).caseTypes[0].name).not.toBe('D')
})
it.each(['changed-preview','changed-intent','cancel'])('push rejects %s without PUT',async boundary=>{
 const h=await setup();await h.load();const B=updateCaseField(h.A,'project',{label:'B'});h.setRemote(updateCaseField(h.A,'project',{label:'C'}));await expect(h.save(B)).rejects.toThrow()
 let current=true
 const p=h.adapter.pushLocal(B,async()=>{if(boundary==='changed-preview')h.setRemote(updateCaseField(h.A,'project',{label:'other C'}));if(boundary==='changed-intent')current=false;return boundary!=='cancel'},()=>current)
 await p.catch(()=>null);expect(h.puts).toEqual([])
})
it.each(['missing','http','json'])('invalid backend %s never confirms or invents time',async failure=>{
 const h=await setup();await h.load();const before=h.authority.storage.getItem(META)
 const impl=h.fetch.getMockImplementation()!
 h.fetch.mockImplementation(async(u:string,i?:RequestInit)=>u.includes('/case-template/')? failure==='missing'?new Response(JSON.stringify(h.A)):failure==='http'?new Response('',{status:503}):{ok:true,json:async()=>{throw Error('bad json')}} as unknown as Response:impl(u,i))
 await expect(h.save(updateCaseField(h.A,'project',{label:'B'}))).rejects.toThrow()
 expect(h.authority.storage.getItem(META)).toBe(before);expect(h.authority.storage.getItem(PENDING)).not.toBeNull();expect(h.puts).toEqual([])
})
it('take-backend stale barrier never clears edits made while GET is pending',async()=>{
 const h=await setup();await h.load();let done!:(r:Response)=>void
 const s=useTemplateConfig({storage:h.ws.storage,identity:()=>h.authority,operationAdapter:h.adapter,confirmDanger:async()=>true} as any)
 h.fetch.mockImplementationOnce(()=>new Promise(ok=>{done=ok}));const taking=s.reconcile('take-backend')
 await vi.waitFor(()=>expect(done).toBeTypeOf('function'));const before={...localStorage}
 s.patchField('project',{label:'new intent'});s.openFieldDraft();done(new Response(JSON.stringify({items:[{id:1,name:'P',code:'P'}]})))
 await taking;await flushPromises()
 expect(s.templateConfig.value.interfaceFields.find(f=>f.key==='project')?.label).toBe('new intent');expect(s.fieldDraft.value).not.toBeNull()
 expect(s.saveStatus.fields.state).not.toBe('saving');expect({...localStorage}).toEqual(before)
})
it('take-backend barrier waits for active save and never interleaves storage writes',async()=>{
 const h=await setup();await h.load();let done!:(r:Response)=>void;h.fetch.mockImplementationOnce(()=>new Promise(ok=>{done=ok}))
 const s=useTemplateConfig({storage:h.ws.storage,identity:()=>h.authority,operationAdapter:h.adapter,confirmDanger:async()=>true} as any)
 s.patchField('project',{label:'B'});await vi.waitFor(()=>expect(done).toBeTypeOf('function'))
 const taking=s.reconcile('take-backend');await flushPromises()
 done(new Response(JSON.stringify({items:[{id:1,name:'P',code:'P'}]})));await taking
 expect(s.saveStatus.fields.state).toBe('idle');expect(s.templateConfig.value.interfaceFields.find(f=>f.key==='project')?.label).toBe('B')
})
it('whole-config fields B then types D remain serial through PUT and mirror',async()=>{
 const h=await setup();await h.load();const original=h.fetch.getMockImplementation()!;let release!:()=>void;let puts=0
 h.fetch.mockImplementation(async(u:string,init?:RequestInit)=>{if(init?.method==='PUT'&&++puts===1)await new Promise<void>(ok=>{release=ok});return original(u,init)})
 const s=useTemplateConfig({storage:h.ws.storage,identity:()=>h.authority,operationAdapter:h.adapter} as any)
 s.patchField('project',{label:'B'});await vi.waitFor(()=>expect(release).toBeTypeOf('function'))
 s.patchCaseType('positive',{name:'D'});s.patchField('project',{label:'latest B'});s.patchCaseType('positive',{name:'latest D'})
 expect(h.puts).toHaveLength(0);release();await vi.waitFor(()=>expect(s.saveStatus.types.state).toBe('saved'))
 expect(h.puts).toHaveLength(2)
 expect((h.puts[0] as any).caseTypes[0].name).not.toBe('latest D')
 expect((h.puts[1] as any).caseTypes[0].name).toBe('latest D')
 expect((h.puts[1] as any).interfaceFields.find((f:any)=>f.key==='project').label).toBe('latest B')
 expect(s.templateConfig.value.caseTypes[0].name).toBe('latest D')
})
it('untrusted backend adoption cannot clear working edits or manufacture confirmation',async()=>{
 const h=await setup();await h.load()
 const s=useTemplateConfig({storage:h.ws.storage,identity:()=>h.authority,operationAdapter:h.adapter} as any)
 const before=JSON.stringify(s.templateConfig.value), raw={...localStorage}
 ;(s as any).adoptSavedConfig({...h.adapter.producerEnvelope(),kind:'backend',config:updateCaseField(h.A,'project',{label:'forged'}),rawPair:h.adapter.rawPair,mirror:'done',confirmedUpdatedAt:'2026-01-01T00:00:00Z'})
 expect(JSON.stringify(s.templateConfig.value)).toBe(before);expect({...localStorage}).toEqual(raw);expect(s.saveStatus.fields.state).toBe('failed')
})
it('dynamic workspace facade without identity fails closed instead of using a static fallback',async()=>{
 const ws=createWorkspaceStorage(localStorage);ws.activate('A');const persist=vi.fn(async(c:any)=>c)
 const s=useTemplateConfig({storage:ws.storage,persistTemplateConfig:persist})
 s.patchField('project',{label:'unsafe'});await flushPromises()
 expect(persist).not.toHaveBeenCalled();expect(s.saveStatus.fields.state).toBe('failed')
})
it('explicit retry uses refresh baseline A and clears a transient network failure',async()=>{
 const h=await setup(); await h.load();h.fetch.mockRejectedValueOnce(Error('offline'))
 const s=useTemplateConfig({storage:h.ws.storage,identity:()=>h.authority,operationAdapter:h.adapter} as any)
 s.patchField('project',{label:'retry B'});await vi.waitFor(()=>expect(s.saveStatus.fields.state).toBe('failed'))
 s.retrySave();await vi.waitFor(()=>expect(s.saveStatus.fields.state).toBe('saved'))
 expect(h.puts).toHaveLength(1)
})
it('reset and subsequent editing use the same writer while preserving remote A',async()=>{
 const h=await setup(); await h.load()
 const s=useTemplateConfig({storage:h.ws.storage,identity:()=>h.authority,operationAdapter:h.adapter,confirmDanger:async()=>true} as any)
 await s.resetConfig(); s.patchField('project',{label:'after reset'})
 await vi.waitFor(()=>expect(s.saveStatus.fields.state).toBe('saved'))
 expect(h.puts).toHaveLength(1); expect(h.adapter.confirmed.config.interfaceFields.find((f:any)=>f.key==='project').label).toBe('after reset')
})
it('single request deadline settles a never returning fetch and fences its late response',async()=>{
 const h=await setup(); await h.load(); let done!:(r:Response)=>void
 h.fetch.mockImplementationOnce(()=>new Promise(ok=>{done=ok}))
 const s=useTemplateConfig({storage:h.ws.storage,identity:()=>h.authority,operationAdapter:h.adapter} as any)
 vi.useFakeTimers()
 s.patchField('project',{label:'B'}); await flushPromises(); expect(done).toBeTypeOf('function')
 await vi.advanceTimersByTimeAsync(30001); await flushPromises()
 expect(s.saveStatus.fields.state).toBe('failed'); expect(h.adapter.recovery.reason).toBe('unknown-result')
 const before={...localStorage}; done(new Response(JSON.stringify({items:[{id:1,name:'P',code:'P'}]})))
 await flushPromises(); expect({...localStorage}).toEqual(before); vi.useRealTimers()
})
it('refresh detects inconsistent and invalid pending synchronously without writes or requests',async()=>{
 const h=await setup(); await h.load(); h.fetch.mockRejectedValueOnce(Error('offline'))
 await expect(h.save(updateCaseField(h.A,'project',{label:'B'}))).rejects.toThrow()
 h.authority.storage.setItem('ai-test-platform.template-config.v1',JSON.stringify(h.A))
 const before={...localStorage}, calls=h.fetch.mock.calls.length
 h.adapter.bind(h.authority)
 expect(h.adapter.recovery.reason).toBe('pending-inconsistent'); expect({...localStorage}).toEqual(before); expect(h.fetch).toHaveBeenCalledTimes(calls)
 h.authority.storage.setItem(PENDING,'  {broken pending '); h.adapter.bind(h.authority)
 expect(h.adapter.recovery.reason).toBe('pending-invalid')
})
it('workspace discard waits for active B and restores B rather than the older A',async()=>{
 const h=await setup(); await h.load(); let done!:(r:Response)=>void
 h.fetch.mockImplementationOnce(()=>new Promise(ok=>{done=ok}))
 const s=useTemplateConfig({storage:h.ws.storage,identity:()=>h.authority,operationAdapter:h.adapter,confirmDanger:async()=>true} as any)
 s.patchField('project',{label:'B'}); await vi.waitFor(()=>expect(done).toBeTypeOf('function'))
 const discard=Promise.resolve(s.discardUnsaved())
 done(new Response(JSON.stringify({items:[{id:1,name:'P',code:'P'}]}))); await discard
 await vi.waitFor(()=>expect(s.saveStatus.fields.state).toBe('idle'))
 expect(s.templateConfig.value.interfaceFields.find(f=>f.key==='project')?.label).toBe('B')
})
it('import writes pending without meta and restores exact prior bad pending if marker fails',async()=>{
 const h=await setup(); await h.load(); const B=updateCaseField(h.A,'project',{label:'imported'})
 await h.adapter.importConfig?.(B)
 expect(h.authority.storage.getItem(PENDING)).not.toBeNull(); expect(h.adapter.recovery.reason).toBe('unconfirmed')
 expect(h.puts).toEqual([])
 const before={...localStorage}; const original=Storage.prototype.setItem; const spy=vi.spyOn(Storage.prototype,'setItem')
 let failed=false
 spy.mockImplementation(function(this:Storage,k:string,v:string){ if(k.endsWith(PENDING)&&!failed){failed=true;throw Error('quota')}return original.call(this,k,v) })
 await expect(h.adapter.importConfig?.(h.A)).rejects.toThrow()
 spy.mockRestore(); expect({...localStorage}).toEqual(before)
})
it('load in flight never overwrites newer edits and its successor waits for the same writer',async()=>{
 const h=await setup(); let done!:(r:Response)=>void
 h.fetch.mockImplementationOnce(()=>new Promise(ok=>{done=ok}))
 const s=useTemplateConfig({storage:h.ws.storage,identity:()=>h.authority,operationAdapter:h.adapter} as any)
 const loading=s.loadBackend(); await vi.waitFor(()=>expect(done).toBeTypeOf('function'))
 s.patchField('project',{label:'D'}); expect(s.saveStatus.fields.state).toBe('saving')
 done(new Response(JSON.stringify({items:[{id:1,name:'P',code:'P'}]}))); await loading
 await vi.waitFor(()=>expect(s.saveStatus.fields.state).toBe('saved'))
 expect(s.templateConfig.value.interfaceFields.find(f=>f.key==='project')?.label).toBe('D'); expect(h.puts).toHaveLength(1)
})
it('reload is read only, take-backend requires confirmation and restores a valid backend baseline',async()=>{
 const h=await setup(); await h.load(); h.setRemote(updateCaseField(h.A,'project',{label:'C'}))
 await expect(h.save(updateCaseField(h.A,'project',{label:'B'}))).rejects.toThrow()
 const before={...localStorage}, calls=h.fetch.mock.calls.length
 await h.adapter.reloadBaseline?.()
 expect({...localStorage}).toEqual(before); expect(h.fetch).toHaveBeenCalledTimes(calls)
 const taken=await h.adapter.takeBackend?.(async()=>true,()=>true)
 expect(taken?.interfaceFields.find((f:any)=>f.key==='project').label).toBe('C')
 expect(h.puts).toEqual([]); expect(h.authority.storage.getItem(PENDING)).toBeNull()
})
it.each(['dispose','owner','workspace'])('no saving survives %s and late responses write nothing',async boundary=>{
 const h=await setup(); await h.load(); let done!:(r:Response)=>void
 h.fetch.mockImplementationOnce(()=>new Promise(ok=>{done=ok}))
 const s=useTemplateConfig({storage:h.ws.storage,identity:()=>h.authority,operationAdapter:h.adapter} as any)
 s.patchField('project',{label:'B'}); await vi.waitFor(()=>expect(done).toBeTypeOf('function'))
 if(boundary==='owner') h.ws.activate('other')
 if(boundary==='dispose') (s as any).dispose?.()
 if(boundary==='workspace') (s as any).invalidateWorkspace?.()
 if(boundary==='owner') (s as any).invalidateWorkspace?.()
 expect(s.saveStatus.fields.state).toBe('failed')
 const before={...localStorage}; done(new Response(JSON.stringify({items:[{id:1,name:'P',code:'P'}]})))
 await flushPromises(); await flushPromises()
 expect({...localStorage}).toEqual(before); expect(h.puts).toHaveLength(0); expect(s.saveStatus.fields.state).toBe('failed')
})
it('mirror failure retains a server receipt and retryMirror writes B only without another PUT',async()=>{
 const h=await setup(); await h.load(); const B=updateCaseField(h.A,'project',{label:'B'})
 const original=Storage.prototype.setItem; const set=vi.spyOn(Storage.prototype,'setItem')
 set.mockImplementation(function(this:Storage,k:string,v:string){ if(k.endsWith(META)) throw Error('quota'); return original!.call(this,k,v) })
 await expect(h.save(B)).rejects.toThrow(); expect(h.adapter.recovery.reason).toBe('storage'); expect(h.puts).toHaveLength(1)
 set.mockRestore(); const receipt=h.adapter.receipt
 expect(receipt?.targetRaw).toContain('B')
 await h.adapter.retryMirror?.(receipt?.receiptId)
 expect(h.puts).toHaveLength(1); expect(h.adapter.recovery.reason).toBeNull(); expect(h.authority.storage.getItem(PENDING)).toBeNull()
})
it('explicit push permit only overwrites a stable preview, idempotent GET B never PUTs',async()=>{
 const h=await setup(); await h.load(); const B=updateCaseField(h.A,'project',{label:'B'}), C=updateCaseField(h.A,'project',{label:'C'})
 h.setRemote(C); await expect(h.save(B)).rejects.toThrow()
 let confirmed=false
 if(h.adapter.pushLocal) await h.adapter.pushLocal(B,async()=>{confirmed=true;return true},()=>true)
 expect(confirmed).toBe(true); expect(h.puts).toEqual([B])
 h.puts.length=0; await h.save(B); expect(h.puts).toEqual([])
})
it('refresh preserves pending B, recovers A from marker and requires explicit retry',async()=>{
 const h=await setup(); await h.load(); h.fetch.mockRejectedValueOnce(Error('offline'))
 const B=updateCaseField(h.A,'project',{label:'B'}); await expect(h.save(B)).rejects.toThrow()
 const before={...localStorage}; h.adapter.bind(h.authority)
 expect({...localStorage}).toEqual(before); expect(h.adapter.recovery.reason).toBe('unconfirmed')
 await expect(h.adapter.save(B)).rejects.toThrow()
 await h.adapter.retrySave?.(B)
 expect(h.puts).toEqual([B]); expect(h.authority.storage.getItem(PENDING)).toBeNull()
})
it('GET C blocks ordinary save and ordinary retry rather than overwriting remote', async()=>{
 const h=await setup(); await h.load(); h.setRemote(updateCaseField(h.A,'project',{label:'C'}))
 const B=updateCaseField(h.A,'project',{label:'B'})
 let failed=false; try { await h.save(B) } catch { failed=true }
 expect(failed).toBe(true); expect(h.puts).toEqual([])
 const marker=JSON.parse(h.authority.storage.getItem(PENDING)!)
 expect(JSON.parse(marker.confirmedBaselineRaw.config)).toEqual(h.A)
 expect(JSON.parse(marker.targetConfigRaw)).toEqual(B)
 expect(h.adapter?.recovery.reason).toBe('conflict')
})
