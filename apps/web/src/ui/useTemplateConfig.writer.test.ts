// @vitest-environment jsdom
import { expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { useTemplateConfig } from './useTemplateConfig'
import { defaultTemplateConfig, saveTemplateConfig, type TemplateConfig } from '../domain/templateConfigRepository'
const clone = <T>(v:T):T => JSON.parse(JSON.stringify(v))
it('standalone local writer does not claim backend saved without an injected persistence contract',async()=>{
 localStorage.clear();const s=useTemplateConfig({storage:localStorage})
 s.patchField('project',{label:'local only'});await flushPromises()
 expect(s.saveStatus.fields.state).toBe('failed');expect(s.saveStatus.fields.error).toContain('未确认')
 expect(localStorage.getItem('ai-test-platform.template-config.v1')).toContain('local only')
})
it.each(['removeField','removeCaseType','removeCaseTemplate'])('confirmation exception in %s is cancellation and preserves a live save',async action=>{
 localStorage.clear();let done!:(c:TemplateConfig)=>void
 const s=useTemplateConfig({storage:localStorage,persistTemplateConfig:c=>new Promise(ok=>{done=ok}),confirmDanger:async()=>{throw Error('dialog closed')}})
 s.patchField('project',{label:'B'});const B=clone(s.templateConfig.value)
 const p=action==='removeField'?s.removeField('project'):action==='removeCaseType'?s.removeCaseType('positive'):s.removeCaseTemplate({id:'custom',name:'C',kind:'interface',builtIn:false,enabled:true,fieldKeys:[]})
 await expect(p).resolves.toBeUndefined();done(B);await flushPromises();expect(s.saveStatus.fields.state).toBe('saved')
})
it('reset reports meta removal partial and never throws or claims success',async()=>{
 localStorage.clear(); saveTemplateConfig(localStorage,defaultTemplateConfig())
 const original=Storage.prototype.removeItem
 const s=useTemplateConfig({storage:localStorage,confirmDanger:async()=>true})
 const spy=vi.spyOn(Storage.prototype,'removeItem').mockImplementation(function(this:Storage,k:string){if(k.includes('-meta.'))throw Error('quota');original.call(this,k)})
 let result:any; await expect(s.resetConfig().then(v=>{result=v})).resolves.toBeUndefined()
 spy.mockRestore()
 expect(result).toMatchObject({ok:false,failedAt:'meta',completed:['config']})
 expect(s.saveStatus.fields.state).toBe('failed'); expect(s.saveStatus.fields.error).toContain('元数据残留')
})
it('cancelling confirmation does not steal an active saving attempt', async () => {
 localStorage.clear(); let done!: (c:TemplateConfig)=>void; let cancel!: (v:boolean)=>void
 const s=useTemplateConfig({storage:localStorage,persistTemplateConfig:c=>new Promise(ok=>{done=ok}),confirmDanger:()=>new Promise(ok=>{cancel=ok})})
 s.patchField('project',{label:'B'}); const B=clone(s.templateConfig.value)
 const intent=s.removeField('project'); cancel(false); await intent
 done(B); await flushPromises()
 expect(s.saveStatus.fields.state).toBe('saved')
})
it('discard restores three absent keys without synthesizing config or meta', async () => {
 localStorage.clear()
 const s=useTemplateConfig({storage:localStorage,persistTemplateConfig:async c=>{saveTemplateConfig(localStorage,c);throw Error('offline')}})
 s.patchField('project',{label:'B'}); await flushPromises(); s.discardUnsaved()
 expect(localStorage.getItem('ai-test-platform.template-config.v1')).toBeNull()
 expect(localStorage.getItem('ai-test-platform.template-config-meta.v1')).toBeNull()
 expect(localStorage.getItem('ai-test-platform.template-config-pending.v1')).toBeNull()
})
it('whole config single writer merges fields and types without a stale response overwrite', async () => {
  localStorage.clear(); saveTemplateConfig(localStorage,defaultTemplateConfig())
  const pending: Array<{config:TemplateConfig; done:(v:TemplateConfig)=>void}> = []
  const persist = vi.fn((config:TemplateConfig) => new Promise<TemplateConfig>(done => pending.push({config:clone(config),done})))
  const s = useTemplateConfig({storage:localStorage,persistTemplateConfig:persist})
  s.patchField('project',{label:'B'}); s.patchCaseType('positive',{name:'D'})
  expect(pending.length).toBe(1)
  pending[0].done(pending[0].config); await flushPromises()
  expect(s.templateConfig.value.caseTypes[0].name).toBe('D')
  expect(s.saveStatus.types.state).toBe('saving')
  expect(pending.length).toBe(2)
  expect(pending[1].config.interfaceFields.find(f=>f.key==='project')?.label).toBe('B')
  expect(pending[1].config.caseTypes[0].name).toBe('D')
  pending[1].done(pending[1].config); await flushPromises()
  expect(s.saveStatus.fields.state).toBe('saved'); expect(s.saveStatus.types.state).toBe('saved')
})
