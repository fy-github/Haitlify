import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { chromium } from 'playwright'
import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import os from 'node:os'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'

// Isolated component fixture. No App mount, env files, proxy, API or storage.
const web = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const repo = path.resolve(web, '../..')
// Only this unique root is disposable; never recursively remove an operator path.
let scratch
const phase = process.argv[2] || 'measure'
const evidence = process.env.CONTROLS_EVIDENCE_DIR
 ? path.resolve(process.env.CONTROLS_EVIDENCE_DIR)
 : await fs.mkdtemp(path.join(os.tmpdir(), 'haitlify-controls-evidence-'))
await fs.mkdir(evidence, { recursive: true })
const baselineNames = {
  settings: 'haitlify-settings-v2', jira: 'haitlify-jira-config-v1', users: 'haitlify-users-v1',
  cases: 'recovered/haitlify-cases-v3', reports: 'recovered/haitlify-reports-v1',
  defects: 'recovered/haitlify-defects-v1', home: 'recovered/haitlify-home-v5-font-reverted'
}
const files = ['src/components/shell/GlobalTopbar.vue', 'src/components/ui/DataPagination.vue', 'src/styles/shell.css', 'src/styles/pagination.css', 'src/styles/controls.css']
// Optional exact-byte pre-edit source overlay: mutation checks never alter the worktree.
const overlayRoot = process.env.CONTROLS_SOURCE_OVERLAY
const overlay = new Map()
if(overlayRoot) for(const file of files) overlay.set(path.join(web,file),await fs.readFile(path.join(overlayRoot,file),'utf8'))
const imports = [...(await fs.readFile(path.join(web, 'src/main.ts'), 'utf8')).matchAll(/import ['"]([^'"]+\.css)['"]/g)].map((m) => m[1])
const entry = `
import { createApp, h, ref } from 'vue'
import ElementPlus from 'element-plus'
${imports.map((x) => `import ${JSON.stringify('/@fs/' + (x.startsWith('.') ? path.resolve(web, 'src', x) : path.resolve(web, 'node_modules', x)))}`).join('\n')}
import '/@fs/${web}/src/styles/defect-design.css'
import GlobalTopbar from '/@fs/${web}/src/components/shell/GlobalTopbar.vue'
import DataPagination from '/@fs/${web}/src/components/ui/DataPagination.vue'
const kind = new URLSearchParams(location.search).get('kind') || 'cases'
const areas = [{id:'home',label:'我的工作台'},{id:'cases',label:'用例库'},{id:'execution',label:'测试执行'},{id:'quality',label:'质量交付'},{id:'overview',label:'团队 / 项目概览'},{id:'settings',label:'设置中心'}]
window.__events = []
createApp({setup() {
 const page = ref(1), size = ref(8), search = ref(''), disabled = ref(false)
 window.__setDisabled = value => { disabled.value = value }
 window.__setPager = (p, s, total) => {page.value=p; size.value=s; window.__total.value=total}
 const total = ref(56); window.__total=total
 const button = (label, primary=false, extra={}) => kind==='settings'&&extra.footer ? h('button',{type:'button',class:'s-drawer-btn'+(primary?' primary':'' )},label) : kind==='defects' ? h('button',{type:'button',disabled:primary&&disabled.value,class:'btn '+(primary?'primary':'light')+(extra.footer?' small-btn':''),...extra},label) : h(ElementPlus.ElButton || 'el-button', {type:primary?'primary':undefined,disabled:primary&&disabled.value, ...extra}, () => label)
 return () => h('div', {class:'studio-app-shell'}, [h('div',{class:'studio-app-shell__topbar'},h(GlobalTopbar, {sidebarCollapsed:false,projectLabel:'隔离 UI fixture',sectionLabel:kind,searchValue:search.value,userDisplayName:'UI 测试账号',userInitials:'我',primaryAreas:areas,activePrimaryId:'settings', 'onUpdate:searchValue':v=>search.value=v,onLogout:()=>window.__events.push('logout'),onChangePassword:()=>window.__events.push('change-password'),onOpenAssistant:()=>window.__events.push('assistant')})),
 h('main',{class:'workspace workbench__pane',style:'margin-left:'+ (innerWidth>899?97:0) +'px; flex:1; min-height:0; overflow:auto; background:white'},[
 h('div',{class:'fixture-label',style:'font-size:11px;color:#65746c;padding:8px 24px'},'隔离组件 fixture · 非后台数据'),
 h('header',{class:'settings-page-head '+({settings:'template-head',jira:'jira-head',users:'user-head',cases:'cases-head',reports:'report-head',defects:'page-head',home:'home-head'}[kind])},[
 h('div',[h('h1',kind),h('p','相同 1440 / 390 CSS 视口 · 真实生产样式')]),h('div',{class:kind==='defects'?'toolbar head-actions':'toolbar'},[button('刷新'),button(({settings:'新增字段',jira:'保存配置',users:'新建用户',cases:'导出',reports:'导出报告',defects:'编辑草稿',home:'操作'}[kind]),true)])]),
 kind==='jira'?h('section',{class:'j-card j-mappings'},h('div',{class:'j-section-head'},[h('h2','项目映射'),button('新增映射')])):null,
 h('section',{class:({reports:'report-page',defects:'defect-page',users:'user-panel',cases:'cv-list',settings:'settings-body'}[kind]) || ''},[
 h('div',{style:'height:220px;background:#fafcfb;display:grid;place-items:center;color:#809086'},'仅用于对照控件，不加载业务数据'),
 kind==='cases'?h(DataPagination,{page:page.value,pageSize:size.value,total:total.value,pageSizes:[8,50,100], 'onUpdate:page':v=>{page.value=v;window.__events.push(['page',v])},'onUpdate:pageSize':v=>{size.value=v;window.__events.push(['size',v])}}):
 ['reports','defects','users'].includes(kind)?h('footer',{class:kind==='users'?'user-panel__footer':'r-pagination'},[h('span','共 56 条 · 第 1 / 7 页'),h('div',{class:kind==='users'?'user-pager':undefined},[h('button',{disabled:true},'上一页'),h('span',{class:kind==='users'?'user-pager__index':undefined},'1 / 7'),h('button',{},'下一页')])]):null
 ]),
 h('div',{class:kind==='users'?'user-drawer':kind==='defects'?'defect-template-drawer':'fixture-drawer'},h('div',{class:'drawer-actions',style:'padding:16px 24px'},[button('取消',false,{footer:true}),button('保存',true,{footer:true})])),
 h('button',{id:'outside',style:'margin:12px'},'外点测试')
 ])])
}}).use(ElementPlus).mount('#app')
// Match page-specific selector ancestry without hiding or replacing production rules.
if(kind==='reports') document.querySelector('.workbench__pane').classList.add('report-page')
if(kind==='defects') document.querySelector('.workbench__pane').classList.add('defect-page')
`
// Use explicit imports for the real EP buttons (plugin does not expose ElButton).
const fixtureEntry = entry.replace("import ElementPlus from", "import { ElButton } from 'element-plus'\nimport ElementPlus from").replace("ElementPlus.ElButton || 'el-button'", 'ElButton')
let server, browser
const result = { phase, viewports: [{width:1440,height:900},{width:390,height:844}], baseline:{}, actual:{}, assertions:[], errors:[], blockedRequests:[], sourcehash:{}, mainStyleOrder:imports, scope:'Component fixture + production CSS; no authenticated/backend/App E2E. Account popup has no expanded archival original.' }
const sha = (x) => crypto.createHash('sha256').update(x).digest('hex')
for (const f of files) result.sourcehash[f] = sha(await fs.readFile(path.join(web,f)))
for (const file of ['src/main.ts','src/styles/defect-design.css','src/components/shell/GlobalTopbar.controls.test.ts','src/components/shell/GlobalTopbar.test.ts','src/components/ui/DataPagination.controls.test.ts','src/components/ui/DataPagination.test.ts','tests/controls-closeout.browser.mjs','tests/controls-closeout.vitest.config.mjs',...imports.filter(x=>x.startsWith('.')).map(x=>path.join('src',x))]) result.sourcehash[file] = sha(await fs.readFile(path.join(web,file)))
for(const [kind,name] of Object.entries(baselineNames)) result.sourcehash['baseline/'+name] = sha(await fs.readFile(path.join(repo,'docs/design-baselines/haitlify',name,'index.html')))
if(overlayRoot){result.sourceOverlay=overlayRoot;for(const file of files)result.sourcehash[file]=sha(overlay.get(path.join(web,file)))}
const measure = async (page, selector) => {
 const l=page.locator(selector).first()
 if(!await l.count()) return null
 return l.evaluate(el=>{const s=getComputedStyle(el),r=el.getBoundingClientRect(); return {text:el.textContent.trim().replace(/\s+/g,' '),rect:{x:r.x,y:r.y,width:r.width,height:r.height,right:r.right,bottom:r.bottom},visible:r.width>0&&r.height>0&&s.display!=='none',styles:Object.fromEntries(['display','minHeight','height','width','padding','gap','color','backgroundColor','borderTopColor','borderTopWidth','borderColor','borderWidth','borderRadius','fontSize','fontFamily','fontWeight','lineHeight','opacity','outlineStyle','outlineWidth','boxShadow','filter','transform','flexWrap','overflowX','order','marginLeft'].map(k=>[k,s[k]])),disabled:el.disabled??false,tabIndex:el.tabIndex}})
}
const shot = async (page, name, selector) => {
 const full=path.join(evidence,name+'.png'); await page.screenshot({path:full});
 if(selector&&await page.locator(selector).first().isVisible().catch(()=>false)) await page.locator(selector).first().screenshot({path:path.join(evidence,name+'-crop.png')})
}
const states = async(page,selector,name)=>{
 if(!await page.locator(selector).first().isVisible().catch(()=>false)) return null
 const loc=page.locator(selector).first(), out={default:await measure(page,selector)}
 if(!out.default.disabled){
  await loc.hover(); await page.waitForTimeout(160); out.hover=await measure(page,selector)
  if(phase.startsWith('final')) await shot(page,name+'-hover')
  await page.mouse.down(); await page.waitForTimeout(80); out.active=await measure(page,selector)
  if(phase.startsWith('final')) await shot(page,name+'-active')
  await page.mouse.move(0,0); await page.mouse.up()
  await page.keyboard.press('Tab'); await loc.focus(); await page.waitForTimeout(120); out.focus=await measure(page,selector)
  await shot(page,name+'-focus')
  await loc.evaluate(e=>e.blur())
 }
 return out
}
try {
 scratch = await fs.mkdtemp(path.join(os.tmpdir(), 'haitlify-controls-closeout-'))
 server=await createServer({configFile:false,root:web,envDir:false,cacheDir:path.join(scratch,'vite-cache'),plugins:[{name:'exact-byte-controls-overlay',enforce:'pre',load(id){if(!id.includes('?')&&overlay.has(id))return overlay.get(id)}},vue(),{name:'isolated-controls-fixture',resolveId(id){if(id==='/__controls-fixture.js')return path.join(web,'controls-fixture-entry.js')},load(id){if(id===path.join(web,'controls-fixture-entry.js'))return fixtureEntry},configureServer(s){s.middlewares.use((req,res,next)=>{if(req.url?.startsWith('/__controls-fixture')&&!req.url?.startsWith('/__controls-fixture.js')){res.setHeader('Content-Type','text/html');res.end('<!doctype html><meta name="viewport" content="width=device-width, initial-scale=1"><div id="app"></div><script type="module" src="/__controls-fixture.js"></script>');return}next()})}}],server:{host:'127.0.0.1',port:0,proxy:{},fs:{allow:[web,repo,scratch]}},logLevel:'error'})
 await server.listen()
 const address=server.httpServer.address(), origin='http://127.0.0.1:'+address.port
 result.server={port:address.port,host:'127.0.0.1',envDir:false,proxy:{},configFile:false,cacheDir:path.join(scratch,'vite-cache')}
 browser=await chromium.launch({headless:true})
 for(const viewport of result.viewports){
  const context=await browser.newContext({viewport,deviceScaleFactor:1,reducedMotion:'reduce'})
  await context.route('**/*',route=>{const u=new URL(route.request().url()); if(u.origin!==origin){result.blockedRequests.push(u.origin+u.pathname);return route.abort()} if(/^\/(api|artifacts|fixtures|__api-test-platform)(\/|$)/.test(u.pathname)){result.blockedRequests.push(u.pathname);return route.abort()} return route.continue()})
  const page=await context.newPage();page.on('pageerror',e=>result.errors.push(e.message))
  for(const [kind,name] of Object.entries(baselineNames)){
   const key=kind+'-'+viewport.width
   await page.goto(origin+'/@fs/'+path.join(repo,'docs/design-baselines/haitlify',name,'index.html'))
   await page.waitForTimeout(200)
   result.baseline[key]={topbar:await measure(page,'.topbar'),assistant:await measure(page,'.assistant-entry'),search:await measure(page,'.top-right .search'),bell:await measure(page,'.top-right .icon-button'),avatar:await measure(page,'.top-right .avatar'),tools:await measure(page,'.top-right'),pagination:await measure(page,kind==='cases'?'.pagination':'.r-pagination'),pagerButton:await measure(page,kind==='cases'?'.page-actions button':'.r-pagination button'),pagerActions:await measure(page,kind==='cases'?'.page-actions':'.r-pagination>div'),current:await measure(page,'.current-page'),headerPrimary:await measure(page,'.content > .page-head .btn.primary, .library-head .btn.primary, .users-main .page-head .btn.primary, .jira-main .page-head .btn.primary, .settings-main .page-head .btn.primary'),headerSecondary:await measure(page,'.content > .page-head .btn:not(.primary), .page-head .head-actions .btn:not(.primary)'),mappingButton:await measure(page,'.j-mappings .j-section-head>.btn'),smallPrimary:await measure(page,'.drawer-actions .small-btn.primary'),scrollWidth:await page.evaluate(()=>({width:innerWidth,scrollWidth:document.documentElement.scrollWidth}))}
   await shot(page,'baseline-'+key,'.topbar')
   if(result.baseline[key].pagination?.visible) await page.locator(kind==='cases'?'.pagination':'.r-pagination').first().screenshot({path:path.join(evidence,'baseline-'+key+'-pagination.png')})
   if(kind==='cases') result.baseline[key].pagerStates=await states(page,'#next-page','baseline-'+key+'-pager')
   if(kind==='settings') result.baseline[key].assistantStates=await states(page,'.assistant-entry','baseline-'+key+'-assistant')
   if(['settings','jira','users','cases','reports','defects'].includes(kind)) result.baseline[key].buttonStates=await states(page,'.content > .page-head .btn:not(.primary), .page-head .head-actions .btn:not(.primary)','baseline-'+key+'-button')
   if(result.baseline[key].headerPrimary?.visible) result.baseline[key].primaryStates=await states(page,'.content > .page-head .btn.primary, .page-head .head-actions .btn.primary','baseline-'+key+'-primary')
   if(['settings','users','defects'].includes(kind)){
    const candidates={settings:'#field-drawer',users:'#user-drawer',defects:'#template-drawer'}
    const dialog=page.locator(candidates[kind]);if(await dialog.count()){
     await dialog.evaluate(el=>el.showModal());await page.waitForTimeout(100)
     result.baseline[key].smallPrimary=await measure(page,candidates[kind]+' .drawer-actions .small-btn.primary')
     result.baseline[key].smallSecondary=await measure(page,candidates[kind]+' .drawer-actions .small-btn:not(.primary)')
     await shot(page,'baseline-'+key+'-drawer-buttons')
     await dialog.evaluate(el=>el.close())
    }
   }
   await page.goto(origin+'/__controls-fixture?kind='+kind)
   await page.locator('.studio-global-topbar__avatar').waitFor(); await page.waitForTimeout(250)
   const target={topbar:await measure(page,'.studio-global-topbar'),assistant:await measure(page,'.studio-assistant-entry'),search:await measure(page,'.studio-global-topbar__search'),bell:await measure(page,'.studio-global-topbar__icon'),avatar:await measure(page,'.studio-global-topbar__avatar'),tools:await measure(page,'.studio-topbar__tools'),pagination:await measure(page,kind==='cases'?'.dp':kind==='users'?'.user-panel__footer':'.r-pagination'),pagerButton:await measure(page,kind==='cases'?'.dp-btn':kind==='users'?'.user-pager button':'.r-pagination button'),pagerActions:await measure(page,kind==='cases'?'.dp-actions':kind==='users'?'.user-pager':'.r-pagination>div'),current:await measure(page,'.dp-btn.is-current'),size:await measure(page,'.dp-size select'),headerPrimary:await measure(page,'.settings-page-head .toolbar .el-button--primary, .defect-page .settings-page-head .toolbar .btn.primary'),headerSecondary:await measure(page,'.settings-page-head .toolbar .el-button:not(.el-button--primary), .defect-page .settings-page-head .toolbar .btn.light'),mappingButton:await measure(page,'.j-section-head .el-button'),smallPrimary:await measure(page,'.drawer-actions .el-button--primary, .drawer-actions .s-drawer-btn.primary'),scrollWidth:await page.evaluate(()=>({width:innerWidth,scrollWidth:document.documentElement.scrollWidth})),toolDomOrder:await page.locator('.studio-global-topbar button').evaluateAll(es=>es.map(e=>e.getAttribute('aria-label')||e.textContent.trim()))}
   result.actual[key]=target
   await shot(page,'actual-'+key,'.studio-global-topbar')
   if(target.pagination?.visible) await page.locator(kind==='cases'?'.dp':kind==='users'?'.user-panel__footer':'.r-pagination').first().screenshot({path:path.join(evidence,'actual-'+key+'-pagination.png')})
   if(['settings','jira','users','cases','reports','defects'].includes(kind)) target.buttonStates=await states(page,'.settings-page-head .toolbar .el-button:not(.el-button--primary), .defect-page .settings-page-head .toolbar .btn.light','actual-'+key+'-button')
   if(target.headerPrimary?.visible) target.primaryStates=await states(page,'.settings-page-head .toolbar .el-button--primary, .defect-page .settings-page-head .toolbar .btn.primary','actual-'+key+'-primary')
   await page.evaluate(()=>window.__setDisabled(true));await page.waitForTimeout(100)
   target.disabledPrimary=await measure(page,'.settings-page-head .toolbar .el-button--primary, .defect-page .settings-page-head .toolbar .btn.primary')
   await shot(page,'actual-'+key+'-disabled-button');await page.evaluate(()=>window.__setDisabled(false))
   if(['settings','users','defects'].includes(kind)){
    target.smallSecondary=await measure(page,'.drawer-actions .el-button:not(.el-button--primary), .drawer-actions .btn:not(.primary), .drawer-actions .s-drawer-btn:not(.primary)')
    if(kind==='defects')target.smallPrimary=await measure(page,'.drawer-actions .btn.primary')
    target.smallPrimaryStates=await states(page,'.drawer-actions .el-button--primary, .drawer-actions .s-drawer-btn.primary, .drawer-actions .btn.primary','actual-'+key+'-drawer-primary')
    target.smallSecondaryStates=await states(page,'.drawer-actions .el-button:not(.el-button--primary), .drawer-actions .s-drawer-btn:not(.primary), .drawer-actions .btn:not(.primary)','actual-'+key+'-drawer-secondary')
   }
   if(kind==='cases'){
    target.pagerStates=await states(page,'.dp-actions > .dp-btn:last-of-type','actual-'+key+'-pager')
    await page.locator('.dp-actions > .dp-btn:last-of-type').hover();await page.keyboard.press('Tab');await page.locator('.dp-actions > .dp-btn:last-of-type').focus();await page.waitForTimeout(100)
    target.hoveredKeyboardFocus=await measure(page,'.dp-actions > .dp-btn:last-of-type');await page.mouse.move(0,0);await page.locator('.dp-actions > .dp-btn:last-of-type').evaluate(el=>el.blur())
    target.sizeStates=await states(page,'.dp-size select','actual-'+key+'-size')
    await page.locator('.dp-size select').click(); await shot(page,'actual-'+key+'-size-open'); await page.keyboard.press('Escape')
    target.pagerKeyboard={}
    await page.locator('.dp-btn').filter({hasText:/^2$/}).focus();await page.keyboard.press('Enter');target.pagerKeyboard.enter=await page.locator('.dp-btn.is-current').textContent()
    await page.locator('.dp-btn').filter({hasText:/^3$/}).focus();await page.keyboard.press('Space');target.pagerKeyboard.space=await page.locator('.dp-btn.is-current').textContent()
    await page.evaluate(()=>window.__setPager(4,8,56)); await page.waitForTimeout(100)
    target.allNumbers=await page.locator('.dp-btn').evaluateAll(es=>es.filter(e=>/^\d+$/.test(e.textContent.trim())).map(e=>({text:e.textContent.trim(),display:getComputedStyle(e).display,rect:e.getBoundingClientRect().toJSON()})))
    target.middle=await measure(page,'.dp-btn.is-current');await shot(page,'actual-'+key+'-all-numbers','.dp')
    await page.locator('.dp-size select').selectOption('50');target.pagerEvents=await page.evaluate(()=>window.__events)
   }
   if(kind==='settings'){
    target.assistantStates=await states(page,'.studio-assistant-entry','actual-'+key+'-assistant')
    await page.locator('.studio-global-topbar__avatar').click(); await page.waitForTimeout(200)
    target.account={popper:await measure(page,'.studio-account-popper, .el-dropdown__popper'),item:await measure(page,'.el-dropdown-menu__item:not(.is-disabled)'),items:await page.locator('.el-dropdown-menu__item').allTextContents()}
    await shot(page,'actual-'+key+'-account','.el-dropdown__popper')
    await page.keyboard.press('Escape'); await page.waitForTimeout(150); target.account.escClosed=await page.locator('.el-dropdown__popper').isHidden()
    await page.locator('.studio-global-topbar__avatar').click();await page.waitForTimeout(150);await page.locator('#outside').click();await page.waitForTimeout(150);target.account.outsideClosed=await page.locator('.el-dropdown__popper').isHidden()
    await page.locator('.studio-global-topbar__avatar').focus();await page.keyboard.press('Enter');await page.waitForTimeout(150)
    target.account.keyboardOpened=await page.locator('.studio-account-popper').isVisible()
    await page.keyboard.press('ArrowDown');await page.keyboard.press('ArrowDown');await page.keyboard.press('Enter');await page.waitForTimeout(150)
    target.account.keyboardEvents=await page.evaluate(()=>window.__events)
    if(await page.locator('.studio-account-popper').isVisible())await page.keyboard.press('Escape')
    await page.locator('.studio-global-topbar__search-toggle').click();await page.waitForTimeout(100)
    target.searchOpened=await measure(page,'.studio-global-topbar__search input')
    await shot(page,'actual-'+key+'-search-open');await page.keyboard.press('Escape')
    target.searchEscClosed=await page.locator('.studio-global-topbar__search input').isHidden()
   }
  }
  await context.close()
 }
 const slice = process.env.CONTROLS_SLICE || 'all'
 const check=(name,run)=>{if(slice==='topbar'&&!/topbar|tool order|account popup|no page errors/.test(name))return;if(slice==='pagination'&&!/pagination|numbers|size|selected border|pager|no page errors/.test(name))return;if(slice==='buttons'&&!/button|no page errors/.test(name))return;try{run();result.assertions.push({name,pass:true})}catch(e){result.assertions.push({name,pass:false,message:e.message})}}
 if(phase!=='measure'&&!phase.startsWith('before')){
  for(const width of [1440,390]){
   const a=result.actual['settings-'+width], b=result.baseline['settings-'+width]
   check('topbar assistant desktop/mobile '+width,()=>{for(const p of ['minHeight','padding','fontSize','color','backgroundColor','borderRadius'])assert.equal(a.assistant.styles[p],b.assistant.styles[p],p)})
   check('visible tool order and DOM order '+width,()=>{
    assert(a.assistant.rect.x<a.search.rect.x&&a.search.rect.x<a.bell.rect.x&&a.bell.rect.x<a.avatar.rect.x,'tools must share AI → search → bell → avatar reading order')
    const order=a.toolDomOrder; assert(order.indexOf('AI 助手')<order.indexOf('搜索'),'DOM AI before search');assert(order.indexOf('搜索')<order.indexOf('通知'));assert(order.indexOf('通知')<order.indexOf('当前用户 UI 测试账号'))
   })
   check('account popup tokens, existing actions, dismissal '+width,()=>{assert.deepEqual(a.account.items.map(x=>x.trim()),['UI 测试账号','修改密码','退出登录']);assert.equal(a.account.popper.styles.borderRadius,'16px');assert.equal(a.account.item.styles.fontSize,'11px');assert.equal(a.account.item.styles.color,'rgb(86, 101, 93)');assert(a.account.escClosed&&a.account.outsideClosed);assert(a.account.keyboardOpened);assert(a.account.keyboardEvents.includes('change-password'));assert(a.searchEscClosed);assert(a.searchOpened.rect.x>=0&&a.searchOpened.rect.right<=width)})
   check('cases pagination per-archive density '+width,()=>{const x=result.actual['cases-'+width],y=result.baseline['cases-'+width];for(const prop of ['padding','minHeight'])assert.equal(x.pagination.styles[prop],y.pagination.styles[prop],prop);for(const prop of ['minHeight','padding','fontSize','lineHeight','borderRadius','color','backgroundColor'])assert.equal(x.pagerButton.styles[prop],y.pagerButton.styles[prop],prop)})
   check('pagination keyboard and non-outline focus '+width,()=>{const x=result.actual['cases-'+width];assert.equal(x.pagerKeyboard.enter.trim(),'2');assert.equal(x.pagerKeyboard.space.trim(),'3');assert.equal(x.pagerStates.focus.styles.outlineStyle,'none');assert.notEqual(x.pagerStates.focus.styles.backgroundColor,x.pagerStates.default.styles.backgroundColor);assert.equal(x.pagerStates.focus.styles.borderColor,x.pagerStates.default.styles.borderColor);assert.equal(x.sizeStates.focus.styles.outlineStyle,'none');assert.notEqual(x.sizeStates.focus.styles.backgroundColor,x.sizeStates.default.styles.backgroundColor);for(const state of ['default','hover','active'])assert.equal(x.sizeStates[state].styles.borderColor,x.pagerButton.styles.borderColor)})
   check('pagination focus remains visible under hover '+width,()=>{const x=result.actual['cases-'+width];assert.notEqual(x.hoveredKeyboardFocus.styles.backgroundColor,x.pagerStates.hover.styles.backgroundColor);assert.equal(x.hoveredKeyboardFocus.styles.borderColor,x.pagerStates.default.styles.borderColor)})
   check('mobile numbers remain visible '+width,()=>{const x=result.actual['cases-'+width];assert.equal(x.allNumbers.length,7);for(const n of x.allNumbers){assert.notEqual(n.display,'none');assert(n.rect.x>=0&&n.rect.right<=width,'page '+n.text+' within viewport')}assert(x.scrollWidth.scrollWidth<=width)})
   check('size events preserved '+width,()=>{const events=result.actual['cases-'+width].pagerEvents;assert(events.some(e=>e[0]==='size'&&e[1]===50));assert(events.some(e=>e[0]==='page'&&e[1]===1))})
   check('selected border is unchanged '+width,()=>{const x=result.actual['cases-'+width];assert.equal(x.middle.styles.borderColor,x.pagerButton.styles.borderColor)})
   for(const kind of ['reports','defects','users']) check('page-specific '+kind+' pager '+width,()=>{const x=result.actual[kind+'-'+width],y=result.baseline[kind+'-'+width];for(const prop of ['padding','minHeight','fontSize'])assert.equal(x.pagination.styles[prop],y.pagination.styles[prop],prop);for(const prop of ['minHeight','padding','fontSize','lineHeight','borderRadius','color','opacity'])assert.equal(x.pagerButton.styles[prop],y.pagerButton.styles[prop],prop);assert(x.pagination.rect.right<=width&&x.pagerActions.rect.right<=width,'pager within viewport')})
   for(const kind of ['settings','jira','users','cases','reports','defects']) {
    check(kind+' header button variants '+width,()=>{const x=result.actual[kind+'-'+width],y=result.baseline[kind+'-'+width];for(const control of ['headerPrimary','headerSecondary','mappingButton']){if(!y[control])continue;assert(x[control],control+' present');for(const prop of ['minHeight','padding','fontSize','lineHeight','fontWeight','borderRadius','backgroundColor','color','borderColor'])assert.equal(x[control].styles[prop],y[control].styles[prop],control+' '+prop)}})
    check(kind+' button hover active focus '+width,()=>{const x=result.actual[kind+'-'+width].buttonStates,y=result.baseline[kind+'-'+width].buttonStates;assert(x&&y);assert.equal(x.hover.styles.transform,'none');assert.equal(x.hover.styles.borderColor,x.default.styles.borderColor);assert.equal(x.hover.styles.backgroundColor,x.default.styles.backgroundColor);assert.equal(x.focus.styles.outlineStyle,'none');assert.equal(x.focus.styles.borderColor,x.default.styles.borderColor);assert(x.focus.styles.filter!=='none'||x.focus.styles.backgroundColor!==x.default.styles.backgroundColor,'visible non-outline focus')})
    if(result.baseline[kind+'-'+width].primaryStates) check(kind+' primary button stable border hover focus '+width,()=>{const x=result.actual[kind+'-'+width].primaryStates;for(const state of ['hover','active','focus']){assert.equal(x[state].styles.borderColor,x.default.styles.borderColor,state+' border');assert.equal(x[state].styles.transform,'none',state+' transform')}assert.equal(x.hover.styles.backgroundColor,x.default.styles.backgroundColor);assert.equal(x.focus.styles.outlineStyle,'none');assert(x.focus.styles.filter!=='none'||x.focus.styles.backgroundColor!==x.default.styles.backgroundColor,'visible focus')})
    check(kind+' disabled button retains variant '+width,()=>{const x=result.actual[kind+'-'+width];assert(x.disabledPrimary.disabled);assert.equal(x.disabledPrimary.styles.opacity,'0.45');assert.equal(x.disabledPrimary.styles.backgroundColor,x.headerPrimary.styles.backgroundColor);assert.equal(x.disabledPrimary.styles.borderColor,x.headerPrimary.styles.borderColor)})
    if(['settings','users','defects'].includes(kind)) check(kind+' drawer button density '+width,()=>{const x=result.actual[kind+'-'+width],y=result.baseline[kind+'-'+width];for(const control of ['smallPrimary','smallSecondary']){assert(x[control]&&y[control],control+' present');for(const prop of ['minHeight','padding','fontSize','lineHeight','fontWeight','borderRadius','backgroundColor','color','borderColor'])assert.equal(x[control].styles[prop],y[control].styles[prop],control+' '+prop)}})
    if(['settings','users','defects'].includes(kind)) check(kind+' drawer button hover focus '+width,()=>{const x=result.actual[kind+'-'+width];for(const control of ['smallPrimaryStates','smallSecondaryStates']){const s=x[control];for(const state of ['hover','active','focus']){assert.equal(s[state].styles.borderColor,s.default.styles.borderColor,control+' '+state+' border');assert.equal(s[state].styles.transform,'none',control+' '+state+' transform')}assert.equal(s.focus.styles.outlineStyle,'none');assert(s.focus.styles.filter!=='none'||s.focus.styles.backgroundColor!==s.default.styles.backgroundColor,'visible focus')}})
   }
  }
 }
 check('no page errors / no backend or external requests',()=>{assert.deepEqual(result.errors,[]);assert.deepEqual(result.blockedRequests,[])})
} catch(e){result.errors.push(e.stack); process.exitCode=2} finally {
 try {
  if(browser) await browser.close()
  if(server) await server.close()
 } finally {
  if(scratch) await fs.rm(scratch, { recursive: true, force: true })
 }
 result.sourcehashAfter={}
 for(const [file,hash] of Object.entries(result.sourcehash)){
  const source=file.startsWith('baseline/')?path.join(repo,'docs/design-baselines/haitlify',file.slice(9),'index.html'):path.join(web,file)
  const after=overlayRoot&&files.includes(file)?sha(overlay.get(path.join(web,file))):sha(await fs.readFile(source))
  result.sourcehashAfter[file]=after
  if(after!==hash)result.errors.push('Source drift during verification: '+file)
 }
 if(result.errors.length)process.exitCode=2
 let portReleased=false
 if(result.server){try{await fetch('http://127.0.0.1:'+result.server.port,{signal:AbortSignal.timeout(500)})}catch{portReleased=true}}
 result.cleanup={browserClosed:true,serverClosed:true,portReleased}
 result.exitCode=process.exitCode|| (result.assertions.some(x=>!x.pass)?1:0)
 await fs.writeFile(path.join(evidence,'measured.json'),JSON.stringify(result,null,2))
 console.log(JSON.stringify({evidence,exitCode:result.exitCode,assertions:result.assertions,errors:result.errors,blockedRequests:result.blockedRequests},null,2))
 process.exitCode=result.exitCode
}
