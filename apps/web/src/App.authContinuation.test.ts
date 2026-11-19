// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
const oldUser = { id:73,username:'old',displayName:'OLD-ACTOR',role:'EDITOR',mustChangePassword:false }
const newUser = { ...oldUser,id:74,username:'new',displayName:'NEW-ACTOR' }
const reply = (body:unknown,status=200) => new Response(JSON.stringify(body),{status})
const deferred = () => { let resolve!:(r:Response)=>void; const promise = new Promise<Response>(r=>{resolve=r}); return {resolve,promise} }
afterEach(()=>vi.unstubAllGlobals())
const init = async (fetcher:ReturnType<typeof vi.fn>) => {
 vi.resetModules();localStorage.clear();sessionStorage.clear();window.history.replaceState({},'','/');vi.stubGlobal('fetch',fetcher)
 const {default:App}=await import('./App.vue'); const http=await import('./domain/http')
 const wrapper=mount(App as never,{global:{plugins:[ElementPlus],stubs:{ElDialog:{props:['modelValue'],template:'<section v-if="modelValue"><slot/><slot name="footer"/></section>'}}}})
 return {wrapper,http}
}
const signIn = async (w:ReturnType<typeof mount>) => {
 await w.get('[name="username"]').setValue('new');await w.get('[name="password"]').setValue('test-password');await w.get('form').trigger('submit');await flushPromises()
 expect(w.findComponent({name:'GlobalTopbar'}).props('userDisplayName')).toBe('NEW-ACTOR')
}
it.each(['success','401','500'])('ignores stale bootstrap %s after a new login is accepted',async outcome=>{
 const old=deferred()
 const {wrapper:w,http}=await init(vi.fn(async(url:string)=>{
  if(url.includes('/api/auth/me'))return old.promise
  if(url.includes('/api/auth/login'))return reply(newUser)
  if(url==='/expire-current')return reply({},401)
  return reply({})
 }))
 try {
  await http.authedFetch('/expire-current');await flushPromises()
  await signIn(w)
  old.resolve(outcome==='success'?reply(oldUser):reply({},Number(outcome)));await flushPromises()
  expect(w.find('[name="username"]').exists()).toBe(false)
  expect(w.findComponent({name:'GlobalTopbar'}).props('userDisplayName')).toBe('NEW-ACTOR')
 }finally{w.unmount()}
})
it('ignores stale successful password update after a new login',async()=>{
 const old=deferred()
 const {wrapper:w,http}=await init(vi.fn(async(url:string)=>{
  if(url.includes('/api/auth/me'))return reply(oldUser)
  if(url.includes('/api/auth/password'))return old.promise
  if(url.includes('/api/auth/login'))return reply(newUser)
  if(url==='/expire-current')return reply({},401)
  return reply({})
 }))
 try{
  await flushPromises();w.findComponent({name:'GlobalTopbar'}).vm.$emit('change-password');await flushPromises()
  await w.get('input[placeholder="当前密码"]').setValue('current-pass')
  await w.get('input[placeholder="新密码（至少 8 位）"]').setValue('next-password')
  await w.get('input[placeholder="确认新密码"]').setValue('next-password')
  await w.findAll('button').find(b=>b.text().includes('确认修改'))!.trigger('click');await flushPromises()
  await http.authedFetch('/expire-current');await flushPromises();await signIn(w)
  old.resolve(reply(oldUser));await flushPromises()
  expect(w.findComponent({name:'GlobalTopbar'}).props('userDisplayName')).toBe('NEW-ACTOR')
 }finally{w.unmount()}
})
it('ignores old login success after another login session is accepted',async()=>{
 const old=deferred()
 const {wrapper:w}=await init(vi.fn(async(url:string,options?:RequestInit)=>{
  if(url.includes('/api/auth/me'))return reply({},401)
  if(url.includes('/api/auth/login'))return JSON.parse(String(options?.body)).username==='old'?old.promise:reply(newUser)
  return reply({})
 }))
 try{
  await flushPromises();await w.get('[name="username"]').setValue('old');await w.get('[name="password"]').setValue('test-password');await w.get('form').trigger('submit')
  const {login}=await import('./domain/authRepository');const accepted=await login('new','test-password')
  w.findComponent({name:'LoginScreen'}).vm.$emit('authenticated',accepted);await flushPromises()
  old.resolve(reply(oldUser));await flushPromises()
  expect(w.findComponent({name:'GlobalTopbar'}).props('userDisplayName')).toBe('NEW-ACTOR')
 }finally{w.unmount()}
})
it.each([200, 401, 500])('ignores stale logout %s completion after reauthentication',async status=>{
 const old=deferred()
 const {wrapper:w,http}=await init(vi.fn(async(url:string)=>{
  if(url.includes('/api/auth/me'))return reply(oldUser)
  if(url.includes('/api/auth/logout'))return old.promise
  if(url.includes('/api/auth/login'))return reply(newUser)
  if(url==='/expire-current')return reply({},401)
  return reply({})
 }))
 try{
  await flushPromises();w.findComponent({name:'GlobalTopbar'}).vm.$emit('logout');await flushPromises()
  await http.authedFetch('/expire-current');await flushPromises();await signIn(w)
  old.resolve(reply({}, status));await flushPromises()
  expect(w.find('[name="username"]').exists()).toBe(false)
  expect(w.findComponent({name:'GlobalTopbar'}).props('userDisplayName')).toBe('NEW-ACTOR')
 }finally{w.unmount()}
})
