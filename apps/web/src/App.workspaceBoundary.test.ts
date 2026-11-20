import { getAppState } from './ui/appTestAccess'
// @vitest-environment jsdom
import {afterEach,it,expect,vi} from 'vitest'
import {mount,flushPromises} from '@vue/test-utils'
import ElementPlus from 'element-plus'
const A={id:1,username:'a',displayName:'A',role:'EDITOR',mustChangePassword:false},B={...A,id:2,username:'b',displayName:'B'}
const reply=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status})
const deferred=()=>{let resolve!:(v:Response)=>void;const promise=new Promise<Response>(yes=>resolve=yes);return {promise,resolve}}
const jira=(name:string)=>({enabled:true,baseUrl:'https://'+name+'.invalid',username:name,credentialConfigured:true,projectJql:{[name]:'project='+name}})
afterEach(()=>vi.unstubAllGlobals())
it.each(['reauthenticate','unmount'])('workspace boundary: pending old Jira success must not write after %s',async boundary=>{
 vi.resetModules();localStorage.clear();sessionStorage.clear();window.history.replaceState({},'','/')
 const old=deferred();let jiraCalls=0
 vi.stubGlobal('fetch',vi.fn(async(url:string)=>{
  if(url.includes('/auth/me'))return reply(A)
  if(url.includes('/auth/login'))return reply(B)
  if(url==='/expire')return reply({},401)
  if(url.includes('/api/jira/config'))return ++jiraCalls===1?old.promise:reply(jira('new'))
  return reply({})
 }))
 const {default:App}=await import('./App.vue');const http=await import('./domain/http')
 const w=mount(App as never,{global:{plugins:[ElementPlus]}});const s=getAppState(w)
 try{
  await flushPromises();expect(jiraCalls).toBe(1)
  if(boundary==='reauthenticate'){
   await http.authedFetch('/expire');await flushPromises();await w.get('[name="username"]').setValue('b');await w.get('[name="password"]').setValue('valid-password');await w.get('form').trigger('submit');await flushPromises()
   expect(s.currentUser.id).toBe(2);expect(s.jiraConfigForm.username).toBe('new')
  }else w.unmount()
  const before=JSON.stringify({form:s.jiraConfigForm,loading:s.jiraConfigLoading})
  old.resolve(reply(jira('old')));await flushPromises()
  console.log('WORKSPACE_BOUNDARY',boundary,{before,after:JSON.stringify({form:s.jiraConfigForm,loading:s.jiraConfigLoading})})
  expect(JSON.stringify({form:s.jiraConfigForm,loading:s.jiraConfigLoading})).toBe(before)
 }finally{if(boundary!=='unmount')w.unmount()}
})
