// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { effectScope } from 'vue'
import { useTemplateConfig, createLocalTemplateConfig } from './useTemplateConfig'
import { createTemplateOperationAdapter, configFromRaw } from './templateOperationAdapter'
import { createWorkspaceStorage } from './workspaceStorage'
import { defaultTemplateConfig, saveTemplateConfig, updateCaseField, readTemplateConfigRawPair, TEMPLATE_CONFIG_STORAGE_KEY as C, TEMPLATE_CONFIG_META_STORAGE_KEY as M, TEMPLATE_CONFIG_PENDING_STORAGE_KEY as P } from '../domain/templateConfigRepository'
import { advanceAuthSessionEpoch } from '../domain/http'
const stamp='2026-01-01T00:00:00.000Z'
const tick=async()=>{for(let i=0;i<5;i++)await flushPromises()}
const deferred=<T>()=>{let resolve!:(v:T)=>void;let reject!:(e?:unknown)=>void;const promise=new Promise<T>((r,j)=>{resolve=r;reject=j});return {resolve,reject,promise}}
const cleanup:Array<()=>void>=[]
afterEach(()=>{cleanup.splice(0).forEach(f=>f());vi.restoreAllMocks()})
async function setup(){
 localStorage.clear();const ws=createWorkspaceStorage(localStorage);ws.activate('A');const authority=ws.captureStrict(()=>true)!;const A=defaultTemplateConfig();saveTemplateConfig(authority.storage,A,{updatedAt:stamp});let remote=A
 const fetch=vi.fn(async(u:string,i?:RequestInit)=>{if(u==='/api/projects')return new Response(JSON.stringify({items:[{id:1,name:'P',code:'P'}]}));if(i?.method==='PUT')remote=JSON.parse(i.body as string);return new Response(JSON.stringify({...remote,updatedAt:stamp}))})
 const adapter=createTemplateOperationAdapter({beginTransport:()=>({fetch,projectName:'P',isCurrent:()=>true})});adapter.bind(authority);await adapter.load()
 const deps={storage:ws.storage,identity:()=>ws.captureStrict(()=>true),operationAdapter:adapter,confirmDanger:async()=>true}
 const s=useTemplateConfig(deps);cleanup.push(()=>s.dispose());return {s,deps,adapter,ws,authority,A,fetch}
}
it('B1 repeated shared initialization retains consumers and fences the prior dependency writer',async()=>{
 const h=await setup(),d=deferred<Response>();h.fetch.mockImplementationOnce(()=>d.promise);h.s.patchField('project',{label:'old B'});await tick()
 const replacement=createTemplateOperationAdapter({beginTransport:()=>({fetch:h.fetch,projectName:'P',isCurrent:()=>true})});replacement.bind(h.authority)
 const next=useTemplateConfig({...h.deps,operationAdapter:replacement});expect(next).toBe(h.s);expect(useTemplateConfig()).toBe(h.s)
 const before={...localStorage};d.resolve(new Response(JSON.stringify({items:[{id:1,name:'P',code:'P'}]})));await tick();expect({...localStorage}).toEqual(before)
 const scope=effectScope();scope.run(()=>expect(useTemplateConfig()).toBe(h.s));scope.stop();expect(next.saveStatus.fields.state).not.toBe('saving')
})
it.each(['save','load'])('B2 %s completion settles waiting discard',async mode=>{
 const h=await setup(),d=deferred<Response>();h.fetch.mockImplementationOnce(()=>d.promise)
 const loading=mode==='load'?h.s.loadBackend():Promise.resolve(h.s.patchField('project',{label:'B'}));await tick()
 let settled=false;const discarding=Promise.resolve(h.s.discardUnsaved()).then(()=>settled=true);await tick()
 d.resolve(new Response(JSON.stringify({items:[{id:1,name:'P',code:'P'}]})));await loading;await tick()
 expect(settled).toBe(true);await discarding;expect(h.s.saveStatus.fields.state).not.toBe('saving')
})
it.each(['dispose','invalidateWorkspace','bindWorkspace','adopt'])('B2 %s immediately releases waiters; late finally cannot release a newer lease',async boundary=>{
 const h=await setup(),old=deferred<Response>();h.fetch.mockImplementationOnce(()=>old.promise);h.s.patchField('project',{label:'B'});await tick()
 let settled=false;const discarding=Promise.resolve(h.s.discardUnsaved()).then(()=>settled=true);await tick()
 if(boundary==='adopt')h.s.adoptSavedConfig({...h.adapter.producerEnvelope(),kind:'local',config:configFromRaw(h.adapter.rawPair!.config),rawPair:h.adapter.rawPair!})
 else h.s[boundary as 'dispose'|'invalidateWorkspace'|'bindWorkspace']()
 await tick();expect(settled).toBe(true);await discarding
 if(boundary==='dispose') {old.resolve(new Response('{}'));return}
 h.s.bindWorkspace();const fresh=deferred<Response>();h.fetch.mockImplementationOnce(()=>fresh.promise);const loading=h.s.loadBackend();await tick()
 old.resolve(new Response(JSON.stringify({items:[{id:1,name:'P',code:'P'}]})));await tick()
 let nextSettled=false;const next=Promise.resolve(h.s.discardUnsaved()).then(()=>nextSettled=true);await tick();expect(nextSettled).toBe(false)
 fresh.resolve(new Response(JSON.stringify({items:[{id:1,name:'P',code:'P'}]})));await loading;await tick();expect(nextSettled).toBe(true);await next
})
it.each(['clear','conflict','local','import','backend'])('B3 stale %s envelope cannot mutate B memory, drafts or status',async kind=>{
 const h=await setup(),stale=h.adapter.producerEnvelope();h.ws.activate('B');h.s.bindWorkspace();h.s.openFieldDraft();const before=JSON.stringify({config:h.s.templateConfig.value,status:h.s.saveStatus,draft:h.s.fieldDraft.value});const raw={...localStorage}
 const event={...stale,kind,config:updateCaseField(h.A,'project',{label:'stale A'}),rawPair:h.adapter.rawPair!,mirror:'done',confirmedUpdatedAt:stamp}
 expect(h.adapter.acceptsAdoption(event)).toBe(false);h.s.adoptSavedConfig(event as any)
 expect(JSON.stringify({config:h.s.templateConfig.value,status:h.s.saveStatus,draft:h.s.fieldDraft.value})).toBe(before);expect({...localStorage}).toEqual(raw)
})
it.each(['local','import'])('B3 current %s rejects mismatched raw/config and missing envelope',async kind=>{
 const h=await setup(),before=JSON.stringify(h.s.templateConfig.value)
 const event={...h.adapter.producerEnvelope(),kind,config:updateCaseField(h.A,'project',{label:'not raw'}),rawPair:h.adapter.rawPair!,mirror:'done'}
 expect(h.adapter.acceptsAdoption(event)).toBe(false);h.s.adoptSavedConfig(event as any);expect(JSON.stringify(h.s.templateConfig.value)).toBe(before);expect(h.s.saveStatus.fields.state).toBe('failed')
 h.s.adoptSavedConfig({kind:'clear',config:updateCaseField(h.A,'project',{label:'no envelope'})} as any);expect(JSON.stringify(h.s.templateConfig.value)).toBe(before)
})
it('B4 independent local factory does not replace the shared consumer and persists unknown-A marker before config',async()=>{
 const h=await setup();localStorage.setItem(M,'  legacy-meta  ');const writes:string[]=[];const original=Storage.prototype.setItem
 const spy=vi.spyOn(Storage.prototype,'setItem').mockImplementation(function(this:Storage,k,v){writes.push(k);return original.call(this,k,v)})
 const local=createLocalTemplateConfig({storage:localStorage});local.patchField('project',{label:'local B'});await tick();spy.mockRestore()
 expect(useTemplateConfig()).toBe(h.s);expect(local).not.toBe(h.s);expect(writes).toEqual([P,C]);expect(localStorage.getItem(M)).toBe('  legacy-meta  ')
 const marker=JSON.parse(localStorage.getItem(P)!);expect(marker).toMatchObject({markerVersion:1,confirmedBaselineRaw:null,confirmedFingerprint:null,targetConfigRaw:localStorage.getItem(C)});expect(marker.targetRawHash).toMatch(/^[a-f0-9]{64}$/)
 const restarted=createLocalTemplateConfig({storage:localStorage});expect(restarted.saveStatus.fields.state).toBe('failed');expect(restarted.saveStatus.types.state).toBe('failed');expect(restarted.recovery.reason).toBe('baseline-unknown');expect(local.saveStatus.fields.state).toBe('failed')
})
it.each(['pending','config','readback'])('B4 local %s failure reports exact partial and never backend confirmation',async boundary=>{
 localStorage.clear();const data=new Map<string,string>();const writes:string[]=[];let wroteConfig=false
 const storage={getItem:(k:string)=>{if(boundary==='readback'&&wroteConfig)throw Error('readback');return data.get(k)??null},setItem:(k:string,v:string)=>{if((boundary==='pending'&&k===P)||(boundary==='config'&&k===C))throw Error('quota');data.set(k,v);writes.push(k);if(k===C)wroteConfig=true},removeItem:(k:string)=>data.delete(k)} as unknown as Storage
 const local=createLocalTemplateConfig({storage});local.patchField('project',{label:'B'});await tick()
 const completed=boundary==='pending'?[]:boundary==='config'?['pending']:['pending','config']
 expect(local.recovery?.partial).toMatchObject({ok:false,failedAt:boundary,completed,remaining:boundary==='pending'?['pending','config']:boundary==='config'?['config']:[]})
 expect(writes).toEqual(completed.map(k=>k==='pending'?P:C));expect(local.saveStatus.fields.state).toBe('failed');expect(data.get(M)).toBeUndefined()
})
it.each(['construct','bind'])('B5 pending %s reload reads B without replacing confirmed-A discard bytes',async boundary=>{
 const h=await setup(),A=readTemplateConfigRawPair(h.authority.storage);h.fetch.mockRejectedValueOnce(Error('offline'));await h.adapter.save(updateCaseField(h.A,'project',{label:'B'})).catch(()=>{})
 let s=h.s
 if(boundary==='construct'){h.adapter.bind(h.authority);s=createLocalTemplateConfig(h.deps)}else s.bindWorkspace()
 const before={...localStorage},calls=h.fetch.mock.calls.length
 await s.reloadBaseline();expect({...localStorage}).toEqual(before);expect(h.fetch).toHaveBeenCalledTimes(calls);expect(s.saveStatus.fields.state).toBe('failed');expect(s.templateConfig.value).toEqual(configFromRaw(h.authority.storage.getItem(C)))
 await s.discardUnsaved();expect(readTemplateConfigRawPair(h.authority.storage)).toEqual(A);expect(s.templateConfig.value).toEqual(h.A);expect(s.saveStatus.fields.state).toBe('idle')
})
it('B5 unknown-A pending reload cannot guess a discard baseline',async()=>{
 const h=await setup();h.s.dispose();const ws=createWorkspaceStorage(localStorage);ws.activate('unknown');const authority=ws.captureStrict(()=>true)!;const local=createLocalTemplateConfig({storage:authority.storage});local.patchField('project',{label:'unknown B'});await tick();expect(authority.storage.getItem(P)).not.toBeNull()
 const adapter=createTemplateOperationAdapter({beginTransport:()=>({fetch:h.fetch,projectName:'P',isCurrent:()=>true})});adapter.bind(authority);const s=createLocalTemplateConfig({storage:ws.storage,identity:()=>authority,operationAdapter:adapter,confirmDanger:async()=>true});const before={...localStorage};await s.reloadBaseline();await s.discardUnsaved()
 expect({...localStorage}).toEqual(before);expect(s.saveStatus.fields.state).toBe('failed')
})
it.each(['load','save','take-backend','push-local','import'])('B2 disposal settles adapter %s promise without network or deadline',async mode=>{
 const h=await setup(),d=deferred<Response>();h.fetch.mockImplementationOnce(()=>d.promise)
 let settled=false
 const operation=mode==='load'?h.adapter.load():mode==='save'?h.adapter.save(updateCaseField(h.A,'project',{label:'B'})):mode==='take-backend'?h.s.reconcile('take-backend'):mode==='push-local'?h.s.reconcile('push-local'):h.s.importConfig(h.A)
 const result=Promise.resolve(operation).then(()=>settled=true,()=>settled=true);await tick();h.s.dispose();await tick();expect(settled).toBe(true);expect(h.adapter.recovery.phase).toBe('idle');await result
 const before={...localStorage};d.resolve(new Response(JSON.stringify({items:[{id:1,name:'P',code:'P'}]})));await tick();expect({...localStorage}).toEqual(before)
})
it('B2 older reset finally cannot clear a newer owner recovery barrier',async()=>{
 const h=await setup(),release=deferred<void>(),reset=h.adapter.reset
 const spy=vi.spyOn(h.adapter,'reset').mockImplementation(async current=>{const report=await reset(current);await release.promise;return report})
 const resetting=h.s.resetConfig();await tick();h.s.bindWorkspace();const response=deferred<Response>();h.fetch.mockImplementationOnce(()=>response.promise);const taking=h.s.reconcile('take-backend');await tick();release.resolve();await resetting;spy.mockRestore()
 h.s.patchField('project',{label:'new D'});await tick();expect(h.s.saveStatus.fields.state).toBe('saving')
 response.resolve(new Response(JSON.stringify({items:[{id:1,name:'P',code:'P'}]})));await taking;await tick();expect(h.s.saveStatus.fields.state).not.toBe('saving')
})
it('B1 identical shared deps initialization does not cancel a valid in-flight save',async()=>{
 const h=await setup(),response=deferred<Response>();h.fetch.mockImplementationOnce(()=>response.promise);h.s.patchField('project',{label:'B'});await tick();expect(useTemplateConfig(h.deps)).toBe(h.s);response.resolve(new Response(JSON.stringify({items:[{id:1,name:'P',code:'P'}]})));await tick();expect(h.s.saveStatus.fields.state).toBe('saved')
})
it('B2 disposed standalone controller never invokes an injected writer again',async()=>{
 localStorage.clear();const persist=vi.fn(async c=>c);const local=createLocalTemplateConfig({storage:localStorage,persistTemplateConfig:persist});local.dispose();local.patchField('project',{label:'after disposal'});await tick();expect(persist).not.toHaveBeenCalled();expect(local.saveStatus.fields.state).toBe('failed')
})
it.each(['invalidate','adopt'])('B2 %s releases hook lease synchronously even when injected persistence never settles',async boundary=>{
 localStorage.clear();const pending:Array<{config:any,resolve:(v:any)=>void}>=[];const persist=vi.fn(config=>new Promise<any>(resolve=>pending.push({config,resolve})));const s=createLocalTemplateConfig({storage:localStorage,persistTemplateConfig:persist})
 s.patchField('project',{label:'old B'});expect(persist).toHaveBeenCalledTimes(1)
 if(boundary==='invalidate')s.invalidateWorkspace();else s.adoptSavedConfig({...s.producerEnvelope(),kind:'local',config:defaultTemplateConfig(),rawPair:readTemplateConfigRawPair(localStorage)})
 s.patchField('project',{label:'new C'});expect(persist).toHaveBeenCalledTimes(2)
 pending[0].resolve(pending[0].config);await tick();s.patchCaseType('positive',{name:'queued D'});expect(persist).toHaveBeenCalledTimes(2)
 pending[1].resolve(pending[1].config);await tick();expect(persist).toHaveBeenCalledTimes(3);pending[2].resolve(pending[2].config);await tick();s.dispose()
})
it('B2 old hook finally cannot release a synchronously started new load lease',async()=>{
 const h=await setup(),old=deferred<Response>(),fresh=deferred<Response>();h.fetch.mockImplementationOnce(()=>old.promise);h.s.patchField('project',{label:'old B'});await tick()
 h.s.bindWorkspace();h.fetch.mockImplementationOnce(()=>fresh.promise);const loading=h.s.loadBackend();let settled=false;const discarding=Promise.resolve(h.s.discardUnsaved()).then(()=>settled=true);await tick();expect(settled).toBe(false)
 fresh.resolve(new Response(JSON.stringify({items:[{id:1,name:'P',code:'P'}]})));await loading;await tick();expect(settled).toBe(true);await discarding;old.resolve(new Response('{}'))
})
it('B2 old adapter finally cannot clear a new lease started synchronously after bind',async()=>{
 const h=await setup(),old=deferred<Response>(),fresh=deferred<Response>();h.fetch.mockImplementationOnce(()=>old.promise)
 const prior=h.adapter.load().catch(()=>null);await tick();h.adapter.bind(h.authority);h.fetch.mockImplementationOnce(()=>fresh.promise);const next=h.adapter.load().catch(()=>null);await tick();expect(h.adapter.recovery.phase).toBe('running')
 fresh.resolve(new Response(JSON.stringify({items:[{id:1,name:'P',code:'P'}]})));await next;await prior;old.resolve(new Response('{}'))
})
it('B3 matching local legacy input is adopted from normalized raw, not the caller shape',async()=>{
 const h=await setup(),legacy=JSON.parse(JSON.stringify(h.A));legacy.schemaVersion=2;delete legacy.fieldsByKind
 h.s.adoptSavedConfig({...h.adapter.producerEnvelope(),kind:'local',config:legacy,rawPair:h.adapter.rawPair!});expect(h.s.templateConfig.value).toEqual(configFromRaw(h.authority.storage.getItem(C)));expect(h.s.templateConfig.value.fieldsByKind).toBeDefined()
})
it('B3 adapter import result itself equals normalized written bytes for legacy schema2',async()=>{
 const h=await setup(),legacy=JSON.parse(JSON.stringify(h.A));legacy.schemaVersion=2;delete legacy.fieldsByKind
 const imported=await h.adapter.importConfig(legacy);expect(imported).toEqual(configFromRaw(h.authority.storage.getItem(C)));expect(imported.schemaVersion).toBe(3);expect(imported.fieldsByKind).toBeDefined()
})
it('B5 reload of externally invalid pending cannot reuse a formerly confirmed A',async()=>{
 const h=await setup();h.fetch.mockRejectedValueOnce(Error('offline'));await h.adapter.save(updateCaseField(h.A,'project',{label:'B'})).catch(()=>{});h.s.bindWorkspace();await h.s.reloadBaseline();expect(h.adapter.pendingRestorePoint).not.toBeNull()
 h.authority.storage.setItem(P,'invalid marker');const before={...localStorage};await h.s.reloadBaseline();await h.s.discardUnsaved();expect({...localStorage}).toEqual(before);expect(h.s.saveStatus.fields.state).toBe('failed')
})
it('B3 adapter rejects missing conflict envelope even though previews do not adopt',async()=>{
 const h=await setup();expect(h.adapter.acceptsAdoption({kind:'conflict',config:h.A})).toBe(false)
})
it('B4 shared local reinitialization remains failed with the original pending marker',async()=>{
 localStorage.clear();const s=useTemplateConfig({storage:localStorage});s.patchField('project',{label:'B'});await tick();const before=localStorage.getItem(P);expect(before).not.toBeNull()
 const fresh=useTemplateConfig({storage:localStorage});expect(fresh.saveStatus.fields.state).toBe('failed');expect(localStorage.getItem(P)).toBe(before);fresh.dispose()
})

it('R01 retryMirror captures owner and every publish exit is a no-op after bind',async()=>{
 const h=await setup();const original=Storage.prototype.setItem
 const quota=vi.spyOn(Storage.prototype,'setItem').mockImplementation(function(this:Storage,k,v){if(k.endsWith(M))throw Error('quota');return original.call(this,k,v)})
 h.s.patchField('project',{label:'retry B'});await tick();expect(h.adapter.recovery.reason).toBe('storage');expect(h.adapter.receipt).not.toBeNull();quota.mockRestore()
 const retry=h.s.retryMirror();h.ws.activate('B');h.s.bindWorkspace();const before=JSON.stringify({status:h.s.saveStatus,config:h.s.templateConfig.value,draft:h.s.fieldDraft.value})
 await retry;await tick();expect(JSON.stringify({status:h.s.saveStatus,config:h.s.templateConfig.value,draft:h.s.fieldDraft.value})).toBe(before)
})

it('R01 retryMirror late success is also fenced after owner bind',async()=>{
 const h=await setup();const original=Storage.prototype.setItem
 const quota=vi.spyOn(Storage.prototype,'setItem').mockImplementation(function(this:Storage,k,v){if(k.endsWith(M))throw Error('quota');return original.call(this,k,v)})
 h.s.patchField('project',{label:'retry B'});await tick();quota.mockRestore();const done=deferred<void>();const retryMirror=vi.spyOn(h.adapter,'retryMirror').mockImplementation(async()=>done.promise as never)
 const retry=h.s.retryMirror();await tick();h.ws.activate('B');h.s.bindWorkspace();const rawPair=vi.spyOn(h.adapter,'rawPair','get');rawPair.mockClear();const before=JSON.stringify({status:h.s.saveStatus,config:h.s.templateConfig.value,draft:h.s.fieldDraft.value});done.resolve();await retry;await tick();expect(retryMirror).toHaveBeenCalledTimes(1);expect(rawPair).not.toHaveBeenCalled();expect(JSON.stringify({status:h.s.saveStatus,config:h.s.templateConfig.value,draft:h.s.fieldDraft.value})).toBe(before)
})

it('R01 retryMirror late rejection is also fenced after owner bind',async()=>{
 const h=await setup();const original=Storage.prototype.setItem
 const quota=vi.spyOn(Storage.prototype,'setItem').mockImplementation(function(this:Storage,k,v){if(k.endsWith(M))throw Error('quota');return original.call(this,k,v)})
 h.s.patchField('project',{label:'retry B'});await tick();quota.mockRestore();const done=deferred<never>();const retryMirror=vi.spyOn(h.adapter,'retryMirror').mockImplementation(async()=>done.promise as never)
 const retry=h.s.retryMirror();await tick();h.ws.activate('B');h.s.bindWorkspace();const before=JSON.stringify({status:h.s.saveStatus,config:h.s.templateConfig.value,draft:h.s.fieldDraft.value});done.reject(Error('late retry failure'));await retry;await tick();expect(retryMirror).toHaveBeenCalledTimes(1);expect(JSON.stringify({status:h.s.saveStatus,config:h.s.templateConfig.value,draft:h.s.fieldDraft.value})).toBe(before)
})

it('R01 same-epoch owner loss fences retryMirror success before baseline capture or UI publish',async()=>{
 const h=await setup();const original=Storage.prototype.setItem
 const quota=vi.spyOn(Storage.prototype,'setItem').mockImplementation(function(this:Storage,k,v){if(k.endsWith(M))throw Error('quota');return original.call(this,k,v)})
 h.s.patchField('project',{label:'retry B'});await tick();expect(h.adapter.recovery.reason).toBe('storage');quota.mockRestore()
 const rawPair=vi.spyOn(h.adapter,'rawPair','get');const before=JSON.stringify({status:h.s.saveStatus,config:h.s.templateConfig.value,draft:h.s.fieldDraft.value})
 const retry=h.s.retryMirror();queueMicrotask(()=>h.ws.activate('B'));await retry;await tick()
 expect(rawPair).not.toHaveBeenCalled();expect(JSON.stringify({status:h.s.saveStatus,config:h.s.templateConfig.value,draft:h.s.fieldDraft.value})).toBe(before)
})

it('R01 same-epoch owner loss fences retryMirror catch without changing status or error',async()=>{
 const h=await setup();const original=Storage.prototype.setItem
 const quota=vi.spyOn(Storage.prototype,'setItem').mockImplementation(function(this:Storage,k,v){if(k.endsWith(M))throw Error('quota');return original.call(this,k,v)})
 h.s.patchField('project',{label:'retry B'});await tick();quota.mockRestore();const done=deferred<never>();const retryMirror=vi.spyOn(h.adapter,'retryMirror').mockImplementation(async()=>done.promise as never)
 const rawPair=vi.spyOn(h.adapter,'rawPair','get');const retry=h.s.retryMirror();await tick();h.ws.activate('B');const before=JSON.stringify({status:h.s.saveStatus,config:h.s.templateConfig.value,draft:h.s.fieldDraft.value});done.reject(Error('late retry failure'));await retry;await tick()
 expect(retryMirror).toHaveBeenCalledTimes(1);expect(rawPair).not.toHaveBeenCalled();expect(JSON.stringify({status:h.s.saveStatus,config:h.s.templateConfig.value,draft:h.s.fieldDraft.value})).toBe(before)
})

it('R01 auth epoch loss fences retryMirror before baseline capture or status publish',async()=>{
 const h=await setup();const original=Storage.prototype.setItem
 const quota=vi.spyOn(Storage.prototype,'setItem').mockImplementation(function(this:Storage,k,v){if(k.endsWith(M))throw Error('quota');return original.call(this,k,v)})
 h.s.patchField('project',{label:'retry B'});await tick();quota.mockRestore();const rawPair=vi.spyOn(h.adapter,'rawPair','get');const before=JSON.stringify({status:h.s.saveStatus,config:h.s.templateConfig.value,draft:h.s.fieldDraft.value})
 const retry=h.s.retryMirror();advanceAuthSessionEpoch();await retry;await tick()
 expect(rawPair).not.toHaveBeenCalled();expect(JSON.stringify({status:h.s.saveStatus,config:h.s.templateConfig.value,draft:h.s.fieldDraft.value})).toBe(before)
})

it('R01 disposed retryMirror completion cannot publish or capture a baseline',async()=>{
 const h=await setup();const original=Storage.prototype.setItem
 const quota=vi.spyOn(Storage.prototype,'setItem').mockImplementation(function(this:Storage,k,v){if(k.endsWith(M))throw Error('quota');return original.call(this,k,v)})
 h.s.patchField('project',{label:'retry B'});await tick();quota.mockRestore();const done=deferred<void>();vi.spyOn(h.adapter,'retryMirror').mockImplementation(async()=>done.promise as never)
 const rawPair=vi.spyOn(h.adapter,'rawPair','get');const retry=h.s.retryMirror();await tick();h.s.dispose();const afterDispose=JSON.stringify({status:h.s.saveStatus,config:h.s.templateConfig.value,draft:h.s.fieldDraft.value});rawPair.mockClear();done.resolve();await retry;await tick()
 expect(rawPair).not.toHaveBeenCalled();expect(JSON.stringify({status:h.s.saveStatus,config:h.s.templateConfig.value,draft:h.s.fieldDraft.value})).toBe(afterDispose)
})

it('R01 adopted retryMirror completion cannot publish or capture a baseline',async()=>{
 const h=await setup();const original=Storage.prototype.setItem;const confirmedRaw=readTemplateConfigRawPair(h.authority.storage)
 const quota=vi.spyOn(Storage.prototype,'setItem').mockImplementation(function(this:Storage,k,v){if(k.endsWith(M))throw Error('quota');return original.call(this,k,v)})
 h.s.patchField('project',{label:'retry B'});await tick();quota.mockRestore();const done=deferred<void>();vi.spyOn(h.adapter,'retryMirror').mockImplementation(async()=>done.promise as never)
 const retry=h.s.retryMirror();await tick();h.s.adoptSavedConfig({...h.adapter.producerEnvelope(),kind:'backend',config:configFromRaw(confirmedRaw.config),rawPair:confirmedRaw,mirror:'done',confirmedUpdatedAt:stamp});const afterAdopt=JSON.stringify({status:h.s.saveStatus,config:h.s.templateConfig.value,draft:h.s.fieldDraft.value});const rawPair=vi.spyOn(h.adapter,'rawPair','get');rawPair.mockClear();done.resolve();await retry;await tick()
 expect(rawPair).not.toHaveBeenCalled();expect(JSON.stringify({status:h.s.saveStatus,config:h.s.templateConfig.value,draft:h.s.fieldDraft.value})).toBe(afterAdopt)
})

it('R01 retryMirror remains valid after binding a new owner and succeeds for that owner',async()=>{
 const h=await setup();const bAuthorityConfig=updateCaseField(h.A,'project',{label:'retry B'});h.ws.activate('B');const bAuthority=h.ws.captureStrict(()=>true)!;saveTemplateConfig(bAuthority.storage,h.A,{updatedAt:stamp});h.s.bindWorkspace()
 const originalFetch=h.fetch.getMockImplementation()!;h.fetch.mockImplementation(async(u:string,i?:RequestInit)=>u.includes('/case-template/')&&!i?.method?new Response(JSON.stringify({...bAuthorityConfig,updatedAt:stamp})):originalFetch(u,i))
 const original=Storage.prototype.setItem;const quota=vi.spyOn(Storage.prototype,'setItem').mockImplementation(function(this:Storage,k,v){if(k.endsWith(M))throw Error('quota');return original.call(this,k,v)})
 h.s.patchField('project',{label:'retry B'});await tick();expect(h.adapter.recovery.reason).toBe('storage');quota.mockRestore();await h.s.retryMirror();await tick()
 expect(h.adapter.recovery.reason).toBeNull();expect(h.s.saveStatus.fields.error).toBe('本地缓存已恢复，请显式重试保存最新编辑')
})

it('R02 unreadable owner clear resets memory and drafts without writing or inventing baseline',async()=>{
 const h=await setup();h.s.openFieldDraft();h.s.fieldDraft.value!.label='private A draft';h.s.templateConfig.value.interfaceFields[0].label='private A value';h.ws.activate('B')
 const original=Storage.prototype.getItem;const reads=vi.spyOn(Storage.prototype,'getItem').mockImplementation(function(this:Storage,k){if(k.startsWith('ai-test-platform.workspace.B.'))throw Error('unreadable B');return original.call(this,k)})
 const before={...localStorage};h.s.bindWorkspace();expect({...localStorage}).toEqual(before);expect(h.s.fieldDraft.value).toBeNull();expect(h.s.templateConfig.value).toEqual(defaultTemplateConfig());expect(h.s.saveStatus.fields.state).toBe('failed');expect(h.s.saveStatus.types.state).toBe('failed');expect(h.adapter.rawPair).toBeNull();expect(h.adapter.confirmed).toBeNull();reads.mockRestore()
})

it('R03 read failure exposes recovery and reloadBaseline recovers without dropping working memory',async()=>{
 const h=await setup();const original=Storage.prototype.getItem
 const denied=vi.spyOn(Storage.prototype,'getItem').mockImplementation(function(this:Storage,k){if([C,M,P].some(key=>k.endsWith(key)))throw new DOMException('denied','SecurityError');return original.call(this,k)})
 h.s.patchField('project',{label:'private working'});await tick();expect(h.s.recovery.reason).toBe('unreadable');expect(h.s.saveStatus.fields.state).toBe('failed');expect(h.s.templateConfig.value.interfaceFields.find(f=>f.key==='project')?.label).toBe('private working')
 denied.mockRestore();await h.s.reloadBaseline();expect(h.s.recovery.reason).toBeNull();expect(h.s.templateConfig.value).toEqual(defaultTemplateConfig());expect(h.s.saveStatus.fields.state).not.toBe('saving')
})
