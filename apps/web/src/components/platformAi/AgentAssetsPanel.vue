<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { CircleAlert, Check, FilePenLine, Pencil, Plus, RefreshCcw, Save, Trash2, CircleX } from '@lucide/vue'
import {
  confirmAgentMemory,
  createAgentMemory,
  createLocatorKnowledge,
  deleteAgentMemory,
  disableAgentMemory,
  getAgentRules,
  listAgentMemories,
  listLocatorKnowledge,
  markLocatorKnowledgeStale,
  publishAgentRule,
  saveAgentRuleDraft,
  updateAgentMemory,
  updateLocatorKnowledge,
  type AgentMemory,
  type LocatorKnowledgeEntry
} from '../../domain/webCaseAgentRepository'

const props = defineProps<{ projectId?: number }>()
const emit = defineEmits<{ ensureProject: [] }>()
const loading = ref(false)
const savingRules = ref(false)
const loadError = ref('')
const ruleContent = ref('')
const phaseRules = ref<Record<string, string>>({})
const version = ref(0)
const locators = ref<LocatorKnowledgeEntry[]>([])
const activeMemories = ref<AgentMemory[]>([])
const pendingMemories = ref<AgentMemory[]>([])
const assetTab = ref<'rules' | 'locators' | 'memories'>('rules')
const editingRules = ref(false)
const locatorDialog = ref(false)
const memoryDialog = ref(false)
const editingLocatorId = ref<string | null>(null)
const editingMemoryId = ref<string | null>(null)
const savingAsset = ref(false)
const locatorForm = ref({ projectId: 0, environmentId: '', moduleName: '', pageName: '', pageUrl: '', naturalName: '', locator: '', roleName: '', accessibleName: '', elementType: '' })
const memoryForm = ref({ projectId: 0, sourceRunId: '', moduleName: '', category: 'generation_delivery', content: '' })
const assetTabs: Array<{ key: 'rules' | 'locators' | 'memories'; label: string }> = [
  { key: 'rules', label: '规则' }, { key: 'locators', label: 'Locator 知识' }, { key: 'memories', label: '经验记忆' }
]
const phaseItems = computed(() => {
  const labels = ['环境预检', '浏览器取证', '用例规划', '分点生成', '确定性校验', '交付沉淀']
  const descriptions = ['确认规则快照、运行环境与任务输入均可安全使用。', '仅采集当前真实浏览器页面的可验证元素。', '每个测试点先形成可追溯的执行意图。', '按测试点生成完整的 Web Runner DSL 候选。', '校验断言、浏览器证据和 DSL 执行规范。', '追加已通过用例，并沉淀可确认的经验。']
  return Object.entries(phaseRules.value).map(([key], index) => ({ key, index, label: labels[index] || key, description: descriptions[index] || '阶段规则已配置' }))
})
const locatorGroups = computed(() => Object.entries(locators.value.reduce<Record<string, LocatorKnowledgeEntry[]>>((groups, item) => {
  const key = `${item.moduleName || '未分组'} · ${item.pageName || '未命名页面'}`
  ;(groups[key] ||= []).push(item)
  return groups
}, {})))
const successRate = (item: { executionSuccessCount: number; executionFailureCount: number }) => {
  const total = item.executionSuccessCount + item.executionFailureCount
  return total ? `${Math.round(item.executionSuccessCount / total * 100)}%` : '暂无执行'
}

const refresh = async () => {
  loading.value = true
  loadError.value = ''
  try {
    const rules = await getAgentRules(props.projectId)
    ruleContent.value = rules.contentText
    phaseRules.value = rules.phaseRules
    version.value = rules.version
    if (props.projectId) {
      const [knowledge, active, pending] = await Promise.all([
        listLocatorKnowledge(props.projectId), listAgentMemories(props.projectId), listAgentMemories(props.projectId, true)
      ])
      locators.value = knowledge.items
      activeMemories.value = active
      pendingMemories.value = pending
    }
  } catch (error) {
    const detail = error instanceof Error ? error.message : ''
    loadError.value = /404/.test(detail)
      ? '当前后端尚未加载 Agent 资产接口，请重启 Platform 服务后重试。'
      : detail || 'Agent 资产加载失败，请检查服务连接后重试。'
  } finally { loading.value = false }
}
const saveRules = async () => {
  savingRules.value = true
  try {
    const draft = await saveAgentRuleDraft(ruleContent.value, phaseRules.value, props.projectId)
    const published = await publishAgentRule(draft.version, props.projectId)
    version.value = published.version
  } finally { savingRules.value = false }
}
const confirmMemory = async (memory: AgentMemory, scope: AgentMemory['scopeType'] = 'PROJECT') => {
  if (!props.projectId) return
  await confirmAgentMemory(memory.memoryId, scope, scope === 'PROJECT' ? [props.projectId] : [])
  await refresh()
}
const disableMemory = async (memory: AgentMemory) => { await disableAgentMemory(memory.memoryId); await refresh() }
const openLocator = (item?: LocatorKnowledgeEntry) => {
  editingLocatorId.value = item?.entryId || null
  locatorForm.value = item ? { projectId: props.projectId || item.projectId || 0, environmentId: item.environmentId || '', moduleName: item.moduleName || '', pageName: item.pageName || '', pageUrl: item.pageUrl || '', naturalName: item.naturalName || '', locator: item.locator || '', roleName: item.roleName || '', accessibleName: item.accessibleName || '', elementType: item.elementType || '' } : { projectId: props.projectId || 0, environmentId: '', moduleName: '', pageName: '', pageUrl: '', naturalName: '', locator: '', roleName: '', accessibleName: '', elementType: '' }
  locatorDialog.value = true
}
const saveLocator = async () => {
  if (!props.projectId || !locatorForm.value.locator.trim()) return
  savingAsset.value = true
  try { if (editingLocatorId.value) await updateLocatorKnowledge(editingLocatorId.value, locatorForm.value); else await createLocatorKnowledge(locatorForm.value); locatorDialog.value = false; await refresh() } finally { savingAsset.value = false }
}
const staleLocator = async (item: LocatorKnowledgeEntry) => { await markLocatorKnowledgeStale(item.entryId); await refresh() }
const openMemory = (item?: AgentMemory) => {
  editingMemoryId.value = item?.memoryId || null
  memoryForm.value = item ? { projectId: props.projectId || item.projectId || 0, sourceRunId: '', moduleName: item.moduleName || '', category: item.category || 'generation_delivery', content: item.content || '' } : { projectId: props.projectId || 0, sourceRunId: '', moduleName: '', category: 'manual', content: '' }
  memoryDialog.value = true
}
const saveMemory = async () => {
  if (!props.projectId || !memoryForm.value.content.trim()) return
  savingAsset.value = true
  try { if (editingMemoryId.value) await updateAgentMemory(editingMemoryId.value, memoryForm.value); else await createAgentMemory(memoryForm.value); memoryDialog.value = false; await refresh() } finally { savingAsset.value = false }
}
const removeMemory = async (memory: AgentMemory) => { await deleteAgentMemory(memory.memoryId); await refresh() }
watch(() => props.projectId, refresh)
onMounted(refresh)
</script>

<template>
  <section class="agent-assets-panel" data-testid="agent-assets-panel">
    <header class="agent-assets-panel__head">
      <div class="agent-assets-panel__title"><span class="agent-assets-panel__eyebrow">Agent workspace</span><strong>Web Case Agent 资产</strong><span>规则、浏览器证据与可确认经验，在任务之间保持一致。</span></div>
      <div class="agent-assets-panel__actions"><el-button size="small" :loading="loading" @click="refresh"><RefreshCcw :size="14" />刷新</el-button><el-button v-if="!projectId" size="small" type="primary" @click="emit('ensureProject')">绑定当前项目</el-button></div>
    </header>
    <div class="agent-assets-panel__metrics"><span><b>v{{ version || '—' }}</b>规则版本</span><span><b>{{ locators.length }}</b>已采集元素</span><span><b>{{ pendingMemories.length }}</b>待确认经验</span></div>
    <nav class="agent-assets-panel__tabs"><button v-for="item in assetTabs" :key="item.key" :class="{ active: assetTab === item.key }" @click="assetTab = item.key">{{ item.label }}</button></nav>
    <div v-if="assetTab === 'rules'" class="agent-assets-panel__body">
      <div v-if="loading && !ruleContent" class="agent-assets-panel__state">正在加载 Agent 规则…</div>
      <div v-else-if="loadError" class="agent-assets-panel__state is-error"><CircleAlert :size="17" /><div><strong>无法加载 Agent 规则</strong><span>{{ loadError }}</span></div><el-button size="small" @click="refresh">重新加载</el-button></div>
      <template v-else>
      <div class="agent-assets-panel__note"><FilePenLine :size="15" />编辑后会先创建草稿，再发布为 v{{ version + 1 }}；运行中的任务继续使用启动时快照。</div>
      <section v-if="!editingRules" class="agent-assets-panel__rule-overview">
        <header><div><strong>当前生效规则</strong><span>Web Case Agent · 全局范围 · v{{ version }}</span></div><el-button type="primary" size="small" @click="editingRules = true"><FilePenLine :size="14" />编辑规则</el-button></header>
        <div class="agent-assets-panel__phases"><article v-for="item in phaseItems" :key="item.key"><span>{{ String(item.index + 1).padStart(2, '0') }}</span><div><strong>{{ item.label }}</strong><p>{{ item.description }}</p></div></article></div>
      </section>
      <template v-else><div class="agent-assets-panel__editor-layout"><aside><span>执行约束</span><strong>AGENT.md</strong><small>全局规则</small><div><b>6</b> 个执行阶段</div></aside><el-input v-model="ruleContent" class="agent-assets-panel__rule-editor" type="textarea" :rows="13" aria-label="AGENT 规则" /></div><footer><span>当前版本 v{{ version }}</span><div><el-button size="small" @click="editingRules = false">取消</el-button><el-button type="primary" size="small" :loading="savingRules" @click="saveRules"><Save :size="14" />发布新版本</el-button></div></footer></template>
      </template>
    </div>
    <div v-else-if="!projectId" class="agent-assets-panel__empty">绑定项目后可查看该项目已采集的 Locator 与经验记忆。</div>
    <div v-else-if="assetTab === 'locators'" class="agent-assets-panel__body">
      <div class="agent-assets-panel__toolbar"><div><strong>Locator 素材</strong><span>优先复用已验证定位，缺失时才通过浏览器采集</span></div><el-button type="primary" size="small" @click="openLocator()"><Plus :size="14" />新增素材</el-button></div>
      <div v-for="[group, items] in locatorGroups" :key="group" class="agent-assets-panel__locator-group"><header><strong>{{ group }}</strong><span>{{ items.length }} 个元素</span></header><div class="agent-assets-panel__locator-columns"><span>元素与页面</span><span>定位器</span><span>质量</span><span>状态</span><span>操作</span></div><div v-for="item in items" :key="item.entryId" class="agent-assets-panel__locator"><div class="agent-assets-panel__locator-summary"><strong :title="item.naturalName || item.roleName">{{ item.naturalName || item.roleName || '未命名元素' }}</strong><small :title="item.pageUrl">{{ item.source || 'browser' }} · {{ item.pageUrl || '未记录页面' }}</small></div><code :title="item.locator">{{ item.locator }}</code><div class="agent-assets-panel__quality"><strong>{{ item.confidenceScore }}%</strong><span>命中 {{ item.hitCount }} · {{ successRate(item) }}</span></div><div><el-tag size="small" :type="item.status === 'active' ? 'success' : 'warning'">{{ item.status }}</el-tag><el-tag v-if="item.confidenceLocked" size="small" type="info">已锁定</el-tag></div><div class="agent-assets-panel__row-actions"><el-button v-if="!item.confidenceLocked" text size="small" @click="openLocator(item)"><Pencil :size="13" />编辑</el-button><el-button v-if="item.status === 'active'" text size="small" type="warning" @click="staleLocator(item)"><CircleX :size="13" />停用</el-button></div></div></div>
      <el-empty v-if="!locators.length" description="暂无浏览器采集的页面元素" :image-size="48" />
    </div>
    <div v-else class="agent-assets-panel__body">
      <div class="agent-assets-panel__toolbar"><div><strong>经验记忆</strong><span>管理生成规则、页面经验与人工确认的可复用知识</span></div><el-button type="primary" size="small" @click="openMemory()"><Plus :size="14" />新增记忆</el-button></div>
      <section class="agent-assets-panel__memory-section" v-if="pendingMemories.length"><header><strong>待确认</strong><span>确认后才会作为后续生成参考</span></header><article v-for="memory in pendingMemories" :key="memory.memoryId" class="agent-assets-panel__memory"><p>{{ memory.content }}</p><small>{{ memory.category }} · {{ memory.moduleName || '通用' }}</small><div class="agent-assets-panel__memory-meta"><span>命中 {{ memory.hitCount }} · 执行 {{ successRate(memory) }} · 置信度 {{ memory.confidenceScore }}%</span><el-tag v-if="memory.confidenceLocked" size="small" type="info">已锁定</el-tag></div><div><el-button size="small" type="primary" @click="confirmMemory(memory)"><Check :size="13" />确认到当前项目</el-button><el-button v-if="!memory.confidenceLocked" size="small" @click="openMemory(memory)"><Pencil :size="13" />编辑</el-button><el-button v-if="!memory.confidenceLocked" text size="small" type="danger" @click="removeMemory(memory)"><Trash2 :size="13" />删除</el-button></div></article></section>
      <section class="agent-assets-panel__memory-section"><header><strong>已生效</strong><span>当前项目的可复用经验</span></header><article v-for="memory in activeMemories" :key="memory.memoryId" class="agent-assets-panel__memory"><p>{{ memory.content }}</p><small>{{ memory.category }} · {{ memory.moduleName || '通用' }}</small><div class="agent-assets-panel__memory-meta"><span>命中 {{ memory.hitCount }} · 执行 {{ successRate(memory) }} · 置信度 {{ memory.confidenceScore }}%</span><el-tag v-if="memory.confidenceLocked" size="small" type="info">已锁定</el-tag></div><div><el-button v-if="!memory.confidenceLocked" size="small" @click="openMemory(memory)"><Pencil :size="13" />编辑</el-button><el-button size="small" text type="warning" @click="disableMemory(memory)">停用</el-button><el-button text size="small" type="danger" @click="removeMemory(memory)"><Trash2 :size="13" />删除</el-button></div></article><el-empty v-if="!activeMemories.length" description="暂无已确认经验" :image-size="42" /></section>
    </div>
    <el-dialog v-model="locatorDialog" :title="editingLocatorId ? '编辑 Locator 素材' : '新增 Locator 素材'" width="620px" destroy-on-close>
      <div class="agent-assets-panel__form-grid"><el-input v-model="locatorForm.environmentId" placeholder="环境 ID" /><el-input v-model="locatorForm.moduleName" placeholder="模块名称" /><el-input v-model="locatorForm.pageName" placeholder="页面名称" /><el-input v-model="locatorForm.pageUrl" placeholder="页面 URL" /><el-input v-model="locatorForm.naturalName" placeholder="元素自然名称" /><el-input v-model="locatorForm.locator" placeholder="Locator（支持 css/xpath）" /><el-input v-model="locatorForm.roleName" placeholder="角色" /><el-input v-model="locatorForm.accessibleName" placeholder="可访问名称" /><el-input v-model="locatorForm.elementType" placeholder="元素类型" /></div>
      <template #footer><el-button @click="locatorDialog = false">取消</el-button><el-button type="primary" :loading="savingAsset" @click="saveLocator">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="memoryDialog" :title="editingMemoryId ? '编辑经验记忆' : '新增经验记忆'" width="560px" destroy-on-close>
      <div class="agent-assets-panel__form-grid"><el-input v-model="memoryForm.moduleName" placeholder="所属模块（可选）" /><el-input v-model="memoryForm.category" placeholder="分类" /><el-input v-model="memoryForm.content" type="textarea" :rows="6" placeholder="经验内容" /></div>
      <template #footer><el-button @click="memoryDialog = false">取消</el-button><el-button type="primary" :loading="savingAsset" @click="saveMemory">保存</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.agent-assets-panel { color: var(--text-regular); }
.agent-assets-panel__head { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; padding: 4px 2px 15px; }
.agent-assets-panel__title { display: grid; gap: 3px; }.agent-assets-panel__title strong { color: var(--text-primary); font-size: 14px; font-weight: 600; line-height: 1.25; }.agent-assets-panel__title > span:last-child { color: var(--text-secondary); font-size: var(--font-size-xs); line-height: 1.35; }.agent-assets-panel__eyebrow { color: var(--brand-primary); font-size: 10px; font-weight: var(--font-weight-semibold); letter-spacing: .08em; text-transform: uppercase; }
.agent-assets-panel__actions { display: flex; flex: none; gap: 6px; }.agent-assets-panel__actions :deep(.el-button) { min-height: 30px; border-radius: var(--radius-control); font-size: var(--font-size-xs); }
.agent-assets-panel__metrics { display: grid; grid-template-columns: repeat(3, 1fr); margin-bottom: 14px; overflow: hidden; border: 1px solid var(--border-subtle); border-radius: var(--radius-control); background: var(--surface-subtle); }.agent-assets-panel__metrics span { padding: 10px 12px; color: var(--text-secondary); font-size: 10px; line-height: 1.35; }.agent-assets-panel__metrics span + span { border-left: 1px solid var(--border-subtle); }.agent-assets-panel__metrics b { margin-right: 5px; color: var(--text-primary); font-size: 13px; font-weight: var(--font-weight-semibold); }
.agent-assets-panel__tabs { display: flex; gap: 2px; border-bottom: 1px solid var(--border-default); }.agent-assets-panel__tabs button { min-height: 37px; border: 0; border-bottom: 2px solid transparent; padding: 8px 13px; color: var(--text-secondary); background: transparent; cursor: pointer; font: inherit; font-size: var(--font-size-xs); }.agent-assets-panel__tabs button:hover { color: var(--brand-primary-active); background: var(--surface-hover); }.agent-assets-panel__tabs button.active { border-color: var(--brand-primary); color: var(--brand-primary-active); font-weight: 600; }
.agent-assets-panel__body { padding: 16px 2px 4px; }.agent-assets-panel__note { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; padding: 9px 11px; border: 1px solid var(--border-subtle); border-radius: var(--radius-control); color: var(--text-secondary); background: var(--surface-subtle); font-size: 12px; }.agent-assets-panel__note svg { color: var(--brand-primary); }.agent-assets-panel__rule-overview { border: 1px solid var(--border-default); border-radius: var(--radius-control); overflow: hidden; }.agent-assets-panel__rule-overview > header { display: flex; align-items: center; justify-content: space-between; padding: 13px 14px; background: var(--surface-raised); border-bottom: 1px solid var(--border-subtle); }.agent-assets-panel__rule-overview > header div { display: grid; gap: 3px; }.agent-assets-panel__rule-overview > header strong { color: var(--text-primary); font-size: 12px; }.agent-assets-panel__rule-overview > header span { color: var(--text-secondary); font-size: 10px; }.agent-assets-panel__phases { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); background: var(--surface-subtle); }.agent-assets-panel__phases article { display: flex; min-height: 92px; gap: 10px; padding: 13px 14px; border-bottom: 1px solid var(--border-subtle); }.agent-assets-panel__phases article:nth-child(odd) { border-right: 1px solid var(--border-subtle); }.agent-assets-panel__phases article:nth-last-child(-n + 2) { border-bottom: 0; }.agent-assets-panel__phases article > span { color: var(--brand-primary); font-size: 10px; font-weight: var(--font-weight-semibold); }.agent-assets-panel__phases article div { min-width: 0; }.agent-assets-panel__phases article strong { display: block; color: var(--text-primary); font-size: 11px; font-weight: 600; text-transform: capitalize; line-height: 1.35; }.agent-assets-panel__phases article p { display: -webkit-box; margin: 6px 0 0; overflow: hidden; color: var(--text-secondary); font-size: 10px; line-height: 1.45; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }.agent-assets-panel__editor-layout { display: grid; grid-template-columns: 168px minmax(0, 1fr); min-height: 365px; border: 1px solid var(--border-default); border-radius: var(--radius-control); overflow: hidden; }.agent-assets-panel__editor-layout aside { display: grid; align-content: start; gap: 5px; padding: 15px 13px; background: var(--surface-subtle); border-right: 1px solid var(--border-subtle); }.agent-assets-panel__editor-layout aside span { color: var(--text-secondary); font-size: 10px; }.agent-assets-panel__editor-layout aside strong { color: var(--text-primary); font-size: 12px; }.agent-assets-panel__editor-layout aside small { color: var(--brand-primary-active); font-size: 10px; }.agent-assets-panel__editor-layout aside div { margin-top: 12px; padding-top: 11px; border-top: 1px solid var(--border-subtle); color: var(--text-secondary); font-size: 10px; }.agent-assets-panel__editor-layout aside b { color: var(--text-primary); font-size: 13px; }.agent-assets-panel__rule-editor :deep(.el-textarea__inner) { min-height: 363px !important; padding: 13px 14px; color: var(--text-regular); background: var(--surface-raised); border: 0; border-radius: 0; box-shadow: none; font-family: var(--font-mono); font-size: 12px; line-height: 1.65; resize: vertical; }.agent-assets-panel__body footer { display: flex; align-items: center; justify-content: space-between; margin-top: 10px; color: var(--text-secondary); font-size: 11px; }.agent-assets-panel__state { display: flex; min-height: 260px; align-items: center; justify-content: center; gap: 10px; color: var(--text-secondary); font-size: 12px; }.agent-assets-panel__state.is-error { min-height: auto; justify-content: flex-start; padding: 14px; border: 1px solid var(--color-warning-border); border-radius: var(--radius-control); color: var(--status-warning-text); background: var(--surface-subtle); }.agent-assets-panel__state.is-error > div { display: grid; flex: 1; gap: 3px; }.agent-assets-panel__state.is-error strong { color: var(--text-primary); font-size: 12px; }.agent-assets-panel__state.is-error span { color: var(--text-secondary); font-size: 11px; }
.agent-assets-panel__empty { padding: 76px 0; color: var(--text-secondary); text-align: center; }.agent-assets-panel__toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }.agent-assets-panel__toolbar div { display: grid; gap: 3px; }.agent-assets-panel__toolbar strong { color: var(--text-primary); font-size: 12px; }.agent-assets-panel__toolbar span { color: var(--text-secondary); font-size: 10px; }.agent-assets-panel__locator-group { margin-bottom: 12px; overflow: hidden; border: 1px solid var(--border-subtle); border-radius: var(--radius-control); background: var(--surface-raised); }.agent-assets-panel__locator-group > header { display: flex; align-items: center; justify-content: space-between; padding: 9px 12px; background: var(--surface-subtle); }.agent-assets-panel__locator-group strong { color: var(--text-primary); font-size: 11px; font-weight: 600; }.agent-assets-panel__locator-group header span { color: var(--text-secondary); font-size: 10px; }.agent-assets-panel__locator-columns, .agent-assets-panel__locator { display: grid; grid-template-columns: minmax(200px, .85fr) minmax(280px, 1.55fr) 104px 104px 128px; gap: 14px; align-items: center; }.agent-assets-panel__locator-columns { min-height: 32px; padding: 0 12px; color: var(--text-placeholder); background: var(--surface-subtle); border-bottom: 1px solid var(--border-subtle); font-size: 10px; }.agent-assets-panel__locator { min-height: 56px; padding: 7px 12px; border-bottom: 1px solid var(--border-subtle); color: var(--text-regular); font-size: 11px; transition: background-color var(--motion-base) ease; }.agent-assets-panel__locator:last-child { border-bottom: 0; }.agent-assets-panel__locator:hover { background: var(--surface-subtle); }.agent-assets-panel__locator-summary { display: grid; min-width: 0; gap: 3px; }.agent-assets-panel__locator-summary strong { overflow: hidden; color: var(--text-regular); text-overflow: ellipsis; white-space: nowrap; font-size: 11px; font-weight: 600; }.agent-assets-panel__locator small, .agent-assets-panel__memory small { overflow: hidden; color: var(--text-secondary); text-overflow: ellipsis; white-space: nowrap; font-size: 10px; }.agent-assets-panel__locator code { display: block; min-width: 0; overflow: hidden; padding: 4px 6px; border: 1px solid var(--surface-page); border-radius: 3px; color: var(--brand-primary); text-overflow: ellipsis; white-space: nowrap; background: var(--surface-page); font-size: 10px; line-height: 1.35; }.agent-assets-panel__quality { display: grid; gap: 2px; }.agent-assets-panel__quality strong { color: var(--text-primary); font-size: 11px; }.agent-assets-panel__quality span, .agent-assets-panel__memory-meta span { color: var(--text-secondary); font-size: 10px; }.agent-assets-panel__locator .el-tag + .el-tag { margin-top: 3px; }.agent-assets-panel__row-actions { display: flex; align-items: center; justify-content: flex-end; gap: 2px; white-space: nowrap; }.agent-assets-panel__row-actions :deep(.el-button) { padding-inline: 5px; }
.agent-assets-panel__memory-section { margin-bottom: 18px; }.agent-assets-panel__memory-section > header { display: flex; align-items: baseline; gap: 8px; margin-bottom: 8px; }.agent-assets-panel__memory-section > header strong { color: var(--text-primary); font-size: 12px; font-weight: 600; }.agent-assets-panel__memory-section > header span { color: var(--text-secondary); font-size: 10px; }.agent-assets-panel__memory { display: grid; gap: 7px; margin: 8px 0; padding: 11px; border: 1px solid var(--border-subtle); border-radius: var(--radius-control); background: var(--surface-canvas); }.agent-assets-panel__memory p { margin: 0; color: var(--text-regular); font-size: 12px; line-height: 1.6; }.agent-assets-panel__memory > div { display: flex; flex-wrap: wrap; gap: 4px; }.agent-assets-panel__memory-meta { align-items: center; justify-content: space-between; padding-top: 5px; border-top: 1px solid var(--border-subtle); }.agent-assets-panel__form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }.agent-assets-panel__form-grid :deep(.el-textarea) { grid-column: 1 / -1; }
@media (max-width: 860px) { .agent-assets-panel__locator-columns { display: none; }.agent-assets-panel__locator { grid-template-columns: minmax(0, 1fr) auto; gap: 7px 10px; padding-block: 10px; }.agent-assets-panel__locator code { grid-column: 1 / -1; }.agent-assets-panel__row-actions { grid-column: 1 / -1; justify-content: flex-start; } }
@media (max-width: 680px) { .agent-assets-panel__head { flex-direction: column; }.agent-assets-panel__editor-layout { grid-template-columns: 1fr; }.agent-assets-panel__editor-layout aside { display: none; }.agent-assets-panel__metrics span { padding-inline: 8px; }.agent-assets-panel__form-grid { grid-template-columns: 1fr; } }
</style>
