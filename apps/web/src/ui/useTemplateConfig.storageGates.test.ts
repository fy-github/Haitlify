// @vitest-environment jsdom
import { expect,it,vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { useTemplateConfig } from './useTemplateConfig'
import { defaultTemplateConfig,saveTemplateConfig,type TemplateConfig } from '../domain/templateConfigRepository'
const actions=['retry','discard','fieldDraft','reset','deleteField','deleteType','deleteTemplate'] as const
it.each(actions.flatMap(action=>['baseline','prewrite'].map(failure=>({action,failure}))))('$failure read failure fences $action before any write or persist',async({action,failure})=>{
 localStorage.clear();const c=defaultTemplateConfig();c.caseTemplates.push({id:'custom',name:'C',kind:'interface',builtIn:false,enabled:true,fieldKeys:[]});saveTemplateConfig(localStorage,c)
 let reject=failure==='baseline';const writes:string[]=[]
 const storage={getItem:(k:string)=>{if(reject)throw Error('read denied');return localStorage.getItem(k)},setItem:(k:string,v:string)=>{writes.push(k);localStorage.setItem(k,v)},removeItem:(k:string)=>{writes.push(k);localStorage.removeItem(k)}} as Storage
 const persist=vi.fn(async(c:TemplateConfig)=>c)
 const s=useTemplateConfig({storage,persistTemplateConfig:persist,confirmDanger:async()=>true})
 reject=true
 if(action==='retry')s.retrySave()
 if(action==='discard')await s.discardUnsaved()
 if(action==='fieldDraft'){s.openFieldDraft();Object.assign(s.fieldDraft.value!,{key:'new_field',label:'N'});s.submitFieldDraft()}
 if(action==='reset')await s.resetConfig()
 if(action==='deleteField')await s.removeField('project')
 if(action==='deleteType')await s.removeCaseType('positive')
 if(action==='deleteTemplate')await s.removeCaseTemplate(c.caseTemplates.find(t=>t.id==='custom')!)
 await flushPromises()
 expect(writes).toEqual([]);expect(persist).not.toHaveBeenCalled();expect(s.saveState.value).toBe('failed')
})
