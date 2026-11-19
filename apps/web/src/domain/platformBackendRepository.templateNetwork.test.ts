// @vitest-environment jsdom
import { expect, it, vi } from 'vitest'
import * as backend from './platformBackendRepository'
import { defaultTemplateConfig } from './templateConfigRepository'
const reply = (v: unknown) => new Response(JSON.stringify(v))
it('template network rejects absent authority time instead of mirroring a client clock', async () => {
  const f = vi.fn(async (url:string) => reply(url === '/api/projects' ? {items:[{id:1,name:'P',code:'P'}]} : defaultTemplateConfig()))
  const call = (backend as any).fetchTemplateConfigFromBackend
  let failed = false
  try {
    if (call) await call({ projectName:'P',fetch:f, isCurrent:()=>true },()=>{})
    else await backend.loadTemplateConfigWithBackend(localStorage,'P',{fetchImpl:f})
  } catch (e) { failed = (e as Error).name === 'TemplateBackendContractError' }
  expect(failed).toBe(true)
  expect(localStorage.length).toBe(0)
})
it('PUT with an explicit different config is rejected rather than silently confirming the target',async()=>{
 const target=defaultTemplateConfig();const different=defaultTemplateConfig();different.caseTypes[0].name='remote rewrite'
 const f=vi.fn(async(url:string)=>reply(url==='/api/projects'?{items:[{id:1,name:'P',code:'P'}]}:{...different,updatedAt:'2026-01-01T00:00:00Z'}))
 const call=(backend as any).putTemplateConfigToBackend
 let rejected=false;try{await call({projectName:'P',fetch:f,isCurrent:()=>true},target,()=>{})}catch(e){rejected=(e as Error).name==='TemplateBackendContractError'}
 expect(rejected).toBe(true)
})
it('checks the same operation after response JSON and never dispatches a later stage', async () => {
  let current = true
  const gate = () => { if (!current) throw Error('expired') }
  const f = vi.fn(async () => ({ok:true,json:async()=>{ current=false; return {items:[{id:1,name:'P',code:'P'}]} }} as Response))
  const call = (backend as any).fetchTemplateConfigFromBackend
  let failed = false
  try { if(call) await call({projectName:'P',fetch:f,isCurrent:()=>true},gate); else await backend.loadTemplateConfigWithBackend(localStorage,'P',{fetchImpl:f}) } catch { failed=true }
  expect(failed).toBe(true); expect(f).toHaveBeenCalledTimes(1); expect(localStorage.length).toBe(0)
})
