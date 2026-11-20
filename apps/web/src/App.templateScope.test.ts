import { getAppState } from './ui/appTestAccess'
// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { defaultTemplateConfig, updateCaseField, saveTemplateConfig } from './domain/templateConfigRepository'
let wrapper:ReturnType<typeof mount>|undefined
afterEach(()=>{wrapper?.unmount();vi.restoreAllMocks();vi.unstubAllGlobals()})
it('App exposes one persistent recovery strip for adapter conflicts, sharing the Settings controller',async()=>{
 vi.resetModules(); localStorage.clear(); sessionStorage.clear(); window.history.replaceState({},'','/settings')
 const A=defaultTemplateConfig(); let remote=A
 const prefix='ai-test-platform.workspace.user%3A1.'
 saveTemplateConfig({setItem:(k:string,v:string)=>localStorage.setItem(prefix+k,v)} as Storage,A,{updatedAt:'2026-01-01T00:00:00Z'})
 const f=vi.fn(async(url:string)=>new Response(JSON.stringify(url.includes('/auth/me')?{id:1,username:'a',role:'ADMIN'}:url==='/api/projects'?{items:[{id:1,name:'默认项目',code:'PROJECT'}]}:url.includes('/case-template/')?{...remote,updatedAt:'2026-01-01T00:00:00Z'}:url.endsWith('/interface-cases')?{items:[]}:{})))
 vi.stubGlobal('fetch',f)
 const {default:App}=await import('./App.vue'); wrapper=mount(App as never,{global:{plugins:[ElementPlus]}})
 await flushPromises(); const s=getAppState(wrapper)
 await vi.waitFor(()=>expect(s.templateOperationAdapter.confirmed).not.toBeNull())
 await vi.waitFor(()=>expect(s.templateOperationAdapter.recovery.phase).toBe('idle'))
 remote=updateCaseField(A,'project',{label:'C'})
 s.templateConfigModule.patchField('project',{label:'B'})
 await vi.waitFor(()=>expect(s.templateConfigModule.saveStatus.fields.state).toBe('failed'))
 expect(wrapper.findAll('[data-template-recovery]')).toHaveLength(1)
 expect(wrapper.get('[data-template-recovery]').text()).toContain('采用后端')
 const recovery=wrapper.get('[data-template-recovery]')
 expect(recovery.find('[data-recovery-group="recover"]').findAll('button')).toHaveLength(3)
 expect(recovery.find('[data-recovery-group="decision"]').findAll('button')).toHaveLength(2)
 expect(recovery.find('[data-recovery-group="decision"]').attributes('data-risk')).toBe('destructive')
 expect(s.templateConfig.interfaceFields.find((f:any)=>f.key==='project')?.label).toBe('B')
 expect(f.mock.calls.filter(([u,init]:any)=>u.includes('/case-template/')&&init?.method==='PUT')).toHaveLength(0)
 vi.spyOn(ElMessageBox,'confirm').mockResolvedValue('confirm' as never)
 const take=wrapper.get('[data-template-recovery]').findAll('button').find(b=>b.text().includes('采用后端'))!
 await take.trigger('click')
 await vi.waitFor(()=>expect(s.templateConfig.interfaceFields.find((f:any)=>f.key==='project')?.label).toBe('C'))
 expect(wrapper.find('[data-template-recovery]').exists()).toBe(false)
 const heldFetch=f.getMockImplementation()!;let late!:(r:Response)=>void
 f.mockImplementationOnce(()=>new Promise(ok=>{late=ok}))
 s.templateConfigModule.patchField('project',{label:'will expire'})
 await vi.waitFor(()=>expect(late).toBeTypeOf('function'))
 s.beginWorkspaceOperation('workspace')
 expect(s.templateConfigModule.saveStatus.fields.state).toBe('failed')
 const owned={...localStorage};late(new Response(JSON.stringify({items:[{id:1,name:'默认项目',code:'PROJECT'}]})));await flushPromises()
 expect({...localStorage}).toEqual(owned)
 f.mockImplementation(heldFetch)
 const {exportProjectData}=await import('./domain/projectDataRepository')
 const pack=exportProjectData(s.workspaceStorage.storage)
 pack.payload.templateConfig=updateCaseField(A,'project',{label:'imported'})
 const input={files:[{text:async()=>JSON.stringify(pack)}],value:'file'}
 await s.onProjectDataSelected({target:input} as unknown as Event); await flushPromises()
 expect(s.templateConfigModule.saveStatus.fields.state).toBe('failed')
 expect(localStorage.getItem(prefix+'ai-test-platform.template-config-pending.v1')).toContain('imported')
 expect(s.templateConfig.interfaceFields.find((f:any)=>f.key==='project')?.label).toBe('imported')
})
