<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { TriangleAlert, ChevronRight, Plus, Route, Save, Trash2 } from '@lucide/vue'
import type { AiProviderConfig } from '../../domain/aiConfigRepository'
import type { AiRunPolicy, SaveAiRunPolicyRequest, SaveAiRunPolicyRoute } from '../../domain/aiPlatformRepository'

const props = withDefaults(defineProps<{
  policies?: AiRunPolicy[] | null
  providers?: AiProviderConfig[] | null
  loading?: boolean
  error?: string
}>(), { policies: () => [], providers: () => [], loading: false })

const emit = defineEmits<{
  save: [policyId: string, request: SaveAiRunPolicyRequest]
  saveOverride: [taskType: string, request: SaveAiRunPolicyRequest]
  createOverride: [taskType: string]
  deleteOverride: [taskType: string]
  viewConnections: []
  refresh: []
}>()

interface RouteRow { connectionId: string; model: string }
interface PolicyDraft {
  name: string
  enabled: boolean
  perCallTimeoutMs: number
  wholeRunDeadlineMs: number
  retryBudget: number
  maxInputChars: number
  routes: RouteRow[]
}

const drafts = reactive<Record<string, PolicyDraft>>({})
const selectedPolicyId = ref<string | null>(null)
const editingRouteIndex = ref<number | null>(null)

const taskTypeLabel = (taskType: string): string => ({
  requirement_analysis: '需求分析',
  web_case_generation: 'Web 用例生成',
  api_case_generation: '接口用例生成'
} as Record<string, string>)[taskType] ?? taskType

const providerOptions = computed(() => (props.providers ?? []).map((provider) => ({
  id: provider.id,
  label: `${provider.name || provider.providerId}${provider.enabled ? '' : '（已禁用）'}`,
  defaultModel: provider.defaultModel
})))

const providerName = (connectionId: string): string =>
  providerOptions.value.find((option) => option.id === connectionId)?.label ?? connectionId

const ensureDraft = (policy: AiRunPolicy): PolicyDraft => {
  if (!drafts[policy.policyId]) {
    drafts[policy.policyId] = {
      name: policy.name,
      enabled: policy.enabled,
      perCallTimeoutMs: policy.perCallTimeoutMs,
      wholeRunDeadlineMs: policy.wholeRunDeadlineMs,
      retryBudget: policy.retryBudget,
      maxInputChars: policy.maxInputChars,
      routes: policy.routes
        .slice()
        .sort((left, right) => left.position - right.position)
        .map((route) => ({ connectionId: route.connectionId, model: route.model }))
    }
  }
  return drafts[policy.policyId]
}

const orderedPolicies = computed(() => {
  const order = ['requirement_analysis', 'web_case_generation', 'api_case_generation']
  return (props.policies ?? [])
    .slice()
    .sort((left, right) => {
      const taskDelta = order.indexOf(left.taskType) - order.indexOf(right.taskType)
      if (taskDelta !== 0) return taskDelta
      return (left.scope === 'default' ? 0 : 1) - (right.scope === 'default' ? 0 : 1)
    })
})

const taskTypesWithOverride = computed(
  () => new Set((props.policies ?? []).filter((policy) => policy.scope === 'task_override').map((policy) => policy.taskType))
)
const defaultPolicyCount = computed(() => (props.policies ?? []).filter((policy) => policy.scope === 'default').length)

type PolicyRow = PolicyDraft & { policyId: string; scope: string; taskType: string; warnings: { code: string; message: string }[] }

const rows = computed<PolicyRow[]>(() => orderedPolicies.value.map((policy) => {
  const draft = ensureDraft(policy)
  return { policyId: policy.policyId, scope: policy.scope, taskType: policy.taskType, warnings: policy.warnings, ...draft }
}))

watch(() => props.policies, () => {
  const current = new Set((props.policies ?? []).map((policy) => policy.policyId))
  for (const key of Object.keys(drafts)) {
    if (!current.has(key)) delete drafts[key]
  }
  if (!selectedPolicyId.value || !rows.value.some((row) => row.policyId === selectedPolicyId.value)) {
    selectedPolicyId.value = rows.value[0]?.policyId ?? null
    editingRouteIndex.value = null
  }
}, { immediate: true, deep: true })

const selectedRow = computed<PolicyRow | null>(() => rows.value.find((row) => row.policyId === selectedPolicyId.value) ?? rows.value[0] ?? null)

const inheritanceLabel = (row: PolicyRow): string => {
  if (row.scope === 'task_override') return '任务覆盖'
  return taskTypesWithOverride.value.has(row.taskType) ? '已被覆盖' : '默认策略'
}

const routePreview = (row: PolicyRow): string =>
  row.routes.map((route) => route.model || providerName(route.connectionId) || '未配置').join(' → ') || '未配置路由'

const timeoutPreview = (row: PolicyRow): string =>
  `${row.perCallTimeoutMs === 0 ? '单次不限时' : `单次 ${Math.round(row.perCallTimeoutMs / 1000)}s`} · ${row.wholeRunDeadlineMs === 0 ? '整次不限时' : `整次 ${Math.round(row.wholeRunDeadlineMs / 60000)}m`}`

const routeConfigured = (route: RouteRow): boolean => Boolean(route.connectionId && route.model)

const addRoute = (row: PolicyRow) => {
  const firstProvider = providerOptions.value[0]
  row.routes.push({ connectionId: firstProvider?.id ?? '', model: firstProvider?.defaultModel ?? '' })
  editingRouteIndex.value = row.routes.length - 1
}
const removeRoute = (row: PolicyRow, index: number) => {
  row.routes.splice(index, 1)
  editingRouteIndex.value = null
}

const buildRequest = (draft: PolicyRow): SaveAiRunPolicyRequest => ({
  name: draft.name,
  enabled: draft.enabled,
  perCallTimeoutMs: draft.perCallTimeoutMs,
  wholeRunDeadlineMs: draft.wholeRunDeadlineMs,
  retryBudget: draft.retryBudget,
  maxInputChars: draft.maxInputChars,
  routes: draft.routes.map<SaveAiRunPolicyRoute>((route, index) => ({
    position: index,
    connectionId: route.connectionId,
    model: route.model
  }))
})

const save = (draft: PolicyRow) => {
  editingRouteIndex.value = null
  const request = buildRequest(draft)
  if (draft.scope === 'task_override') emit('saveOverride', draft.taskType, request)
  else emit('save', draft.policyId, request)
}
</script>

<template>
  <section class="run-policy-editor" data-testid="run-policy-editor">
    <header class="run-policy-editor__toolbar">
      <div>
        <strong><Route :size="15" aria-hidden="true" /> 默认策略</strong>
        <span>{{ defaultPolicyCount }} 个任务继承</span>
      </div>
      <button type="button" class="run-policy-editor__refresh" @click="emit('refresh')">刷新</button>
    </header>

    <p v-if="loading && rows.length === 0" class="run-policy-editor__empty">正在加载运行策略…</p>
    <div v-else-if="rows.length === 0" class="run-policy-editor__empty">
      <span>{{ error || '尚未配置默认策略，无法开始分析。' }}</span>
      <button v-if="error" type="button" class="run-policy-chain__link" @click="emit('refresh')">重新加载运行策略</button>
    </div>

    <div v-else class="run-policy-editor__layout">
      <aside class="run-policy-editor__list" aria-label="策略列表">
        <button
          v-for="row in rows"
          :key="row.policyId"
          type="button"
          class="run-policy-editor__row"
          :class="{ 'is-active': row.policyId === selectedPolicyId, 'is-override': row.scope === 'task_override' }"
          @click="selectedPolicyId = row.policyId; editingRouteIndex = null"
        >
          <span class="run-policy-editor__row-name">
            {{ taskTypeLabel(row.taskType) }}
            <span class="run-policy-editor__row-tag">{{ inheritanceLabel(row) }}</span>
          </span>
          <small class="run-policy-editor__row-route">{{ routePreview(row) }}</small>
          <small class="run-policy-editor__row-timeout">{{ timeoutPreview(row) }}</small>
          <ChevronRight :size="14" class="run-policy-editor__row-chevron" aria-hidden="true" />
        </button>
      </aside>

      <section v-if="selectedRow" class="run-policy-editor__canvas" data-testid="run-policy-card">
        <div class="run-policy-editor__fields">
          <label>策略名称<el-input v-model="selectedRow.name" size="small" /></label>
          <label>适用任务<el-input :model-value="taskTypeLabel(selectedRow.taskType)" size="small" disabled /></label>
        </div>

        <div class="run-policy-chain" data-testid="run-policy-chain">
          <div class="run-policy-chain__label">路由顺序（主路由在前）</div>
          <div class="run-policy-chain__nodes">
            <template v-for="(route, index) in selectedRow.routes" :key="index">
              <div
                class="run-policy-tile"
                :class="{ 'is-ready': routeConfigured(route), 'is-attention': !routeConfigured(route) }"
                role="button"
                :tabindex="editingRouteIndex === index ? -1 : 0"
                :aria-expanded="editingRouteIndex === index"
                @click="editingRouteIndex = editingRouteIndex === index ? null : index"
                @keydown.enter.prevent="editingRouteIndex = editingRouteIndex === index ? null : index"
                @keydown.space.prevent="editingRouteIndex = editingRouteIndex === index ? null : index"
              >
                <span class="run-policy-tile__role">{{ index === 0 ? '主模型' : `备用 ${index}` }}</span>
                <strong>{{ route.model || '未设置模型' }}</strong>
                <span class="run-policy-tile__provider">{{ providerName(route.connectionId) }}</span>
                <span class="run-policy-tile__dot" aria-hidden="true"></span>
                <div v-if="editingRouteIndex === index" class="run-policy-tile__edit" @click.stop>
                  <el-select v-model="route.connectionId" placeholder="选择模型连接" size="small">
                    <el-option v-for="option in providerOptions" :key="option.id" :label="option.label" :value="option.id" />
                  </el-select>
                  <el-input v-model="route.model" placeholder="模型名称" size="small" />
                  <button v-if="index > 0" type="button" class="run-policy-tile__remove" aria-label="移除路由" @click.stop="removeRoute(selectedRow, index)">
                    <Trash2 :size="13" aria-hidden="true" />
                  </button>
                </div>
              </div>
              <span v-if="index < selectedRow.routes.length - 1" class="run-policy-chain__arrow" aria-hidden="true">→</span>
            </template>
            <button
              type="button"
              class="run-policy-chain__add"
              :disabled="providerOptions.length === 0"
              data-testid="run-policy-add-route"
              @click="addRoute(selectedRow)"
            >
              <Plus :size="13" aria-hidden="true" /> {{ selectedRow.routes.length === 0 ? '配置主模型' : '添加备用模型' }}
            </button>
          </div>
          <p v-if="providerOptions.length === 0" class="run-policy-chain__hint">
            请先新增并保存一个可用的模型连接。
            <button type="button" class="run-policy-chain__link" data-testid="run-policy-view-connections" @click="emit('viewConnections')">前往模型连接</button>
          </p>
        </div>

        <div class="run-policy-guardrail">
          <label>单次调用（0=不限时）<el-input-number v-model="selectedRow.perCallTimeoutMs" :min="0" :step="10000" size="small" controls-position="right" /></label>
          <label>整次任务（0=不限时）<el-input-number v-model="selectedRow.wholeRunDeadlineMs" :min="0" :step="60000" size="small" controls-position="right" /></label>
          <label>最多重试<el-input-number v-model="selectedRow.retryBudget" :min="0" :max="5" size="small" controls-position="right" /></label>
          <p class="run-policy-guardrail__help">401/403、模型不存在、请求无效等错误不会触发回退</p>
        </div>

        <ul v-if="selectedRow.warnings.length > 0" class="run-policy-editor__warnings" data-testid="run-policy-warnings">
          <li v-for="warning in selectedRow.warnings" :key="warning.code"><TriangleAlert :size="13" aria-hidden="true" /> {{ warning.message }}</li>
        </ul>

        <footer class="run-policy-editor__foot">
          <el-switch v-model="selectedRow.enabled" active-text="启用策略" />
          <div class="run-policy-editor__foot-actions">
            <el-button v-if="selectedRow.scope === 'default' && !taskTypesWithOverride.has(selectedRow.taskType)" size="small" data-testid="run-policy-add-override" @click="emit('createOverride', selectedRow.taskType)">添加本任务覆盖</el-button>
            <el-button v-if="selectedRow.scope === 'task_override'" type="danger" plain size="small" data-testid="run-policy-delete-override" @click="emit('deleteOverride', selectedRow.taskType)">删除本任务覆盖</el-button>
            <el-button type="primary" size="small" @click="save(selectedRow)"><Save :size="14" aria-hidden="true" /> 保存策略</el-button>
          </div>
        </footer>
      </section>
    </div>
  </section>
</template>

<style scoped>
.run-policy-editor { display: grid; height: 100%; min-height: 100%; grid-template-rows: 52px minmax(0, 1fr); }
.run-policy-editor__toolbar { display: flex; min-height: 52px; align-items: center; justify-content: space-between; gap: 12px; padding: 8px 12px; border-bottom: 1px solid var(--border-subtle); }
.run-policy-editor__toolbar strong { display: inline-flex; align-items: center; gap: 6px; color: var(--text-primary); font-size: 14px; }
.run-policy-editor__toolbar span { margin-left: 8px; color: var(--text-secondary); font-size: 12px; }
.run-policy-editor__refresh { min-height: 30px; background: var(--surface-raised); border: 1px solid var(--border-default); border-radius: var(--radius-control); padding: 0 10px; cursor: pointer; color: var(--text-regular); font: inherit; font-size: 12px; }
.run-policy-editor__refresh:hover { color: var(--brand-primary-active); border-color: var(--brand-primary-border); background: var(--brand-primary-subtle); }
.run-policy-editor__empty { display: grid; min-height: 180px; margin: 0; padding: 24px; place-items: center; align-content: center; color: var(--text-tertiary); font-size: 13px; }
.run-policy-editor__layout { display: grid; min-width: 0; min-height: 0; grid-template-columns: 220px minmax(0, 1fr); }
.run-policy-editor__list { min-width: 0; display: flex; flex-direction: column; gap: 0; padding: 8px 0; background: var(--surface-subtle, var(--surface-page)); border-right: 1px solid var(--border-default); }
.run-policy-editor__row {
  display: flex; flex-direction: column; gap: 3px; text-align: left; min-height: 72px;
  padding: 12px 28px 12px 14px; background: none; border: none; border-left: 3px solid transparent;
  border-radius: 0 6px 6px 0; cursor: pointer; position: relative;
}
.run-policy-editor__row:hover { background: var(--surface-hover, var(--surface-page)); }
.run-policy-editor__row.is-active { border-left-color: var(--brand-primary); background: var(--brand-primary-subtle); }
.run-policy-editor__row-name { display: flex; align-items: center; gap: 6px; font-weight: 600; color: var(--text-primary); font-size: 13px; }
.run-policy-editor__row-tag { font-size: 11px; font-weight: 400; color: var(--text-tertiary); }
.run-policy-editor__row.is-override .run-policy-editor__row-tag { color: var(--color-warning); }
.run-policy-editor__row-route { color: var(--text-secondary); font-size: 12px; }
.run-policy-editor__row-timeout { color: var(--text-tertiary); font-size: 11px; }
.run-policy-editor__row-chevron { position: absolute; right: 10px; top: 50%; transform: translateY(-50%); color: var(--text-tertiary); }

.run-policy-editor__canvas { min-width: 0; display: flex; flex-direction: column; gap: 18px; padding: 16px; overflow: auto; }
.run-policy-editor__fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
.run-policy-editor__fields label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--text-secondary); }

.run-policy-chain__label { font-size: 12px; color: var(--text-secondary); margin-bottom: 8px; }
.run-policy-chain__nodes { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.run-policy-tile {
  position: relative; display: flex; flex-direction: column; gap: 3px;
  width: clamp(160px, 18vw, 220px); min-height: 78px; padding: 10px 12px; border-radius: var(--radius-control);
  border: 1px solid var(--border-subtle); background: var(--surface-raised);
  cursor: pointer;
}
.run-policy-tile:hover { border-color: var(--brand-primary-border); background: var(--surface-hover, var(--surface-page)); }
.run-policy-tile:focus-visible { outline: 3px solid var(--brand-primary-focus); outline-offset: 2px; }
.run-policy-tile.is-ready { border-color: var(--color-success-border); }
.run-policy-tile.is-attention { border-color: var(--color-warning-border); }
.run-policy-tile__role { font-size: 11px; color: var(--text-tertiary); }
.run-policy-tile strong { font-size: 13px; color: var(--text-primary); }
.run-policy-tile__provider { font-size: 11px; color: var(--text-secondary); }
.run-policy-tile__dot { width: 7px; height: 7px; border-radius: 50%; background: var(--text-tertiary); }
.run-policy-tile.is-ready .run-policy-tile__dot { background: var(--color-success); }
.run-policy-tile.is-attention .run-policy-tile__dot { background: var(--color-warning); }
.run-policy-tile__edit { display: flex; flex-direction: column; gap: 6px; margin-top: 6px; padding-top: 8px; border-top: 1px dashed var(--border-subtle); }
.run-policy-tile__remove { align-self: flex-end; background: none; border: none; color: var(--color-danger); cursor: pointer; padding: 2px; }
.run-policy-chain__arrow { color: var(--text-tertiary); font-size: 14px; }
.run-policy-chain__add { display: inline-flex; align-items: center; gap: 4px; background: none; border: 1px dashed var(--border-subtle); border-radius: var(--radius-control); padding: 8px 12px; cursor: pointer; color: var(--brand-primary); font: inherit; font-size: 12px; }
.run-policy-chain__add:disabled { color: var(--text-tertiary); cursor: not-allowed; }
.run-policy-chain__hint { margin: 8px 0 0; color: var(--text-tertiary); font-size: 12px; }
.run-policy-chain__link { margin-left: 4px; padding: 0; color: var(--brand-primary); background: none; border: 0; cursor: pointer; font: inherit; }
.run-policy-chain__link:hover { text-decoration: underline; }

.run-policy-guardrail { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 14px; padding-top: 12px; border-top: 1px solid var(--border-subtle); }
.run-policy-guardrail label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--text-secondary); }
.run-policy-guardrail__help { flex: 1; min-width: 200px; margin: 0; color: var(--text-tertiary); font-size: 11px; }

.run-policy-editor__warnings { list-style: none; margin: 0; padding: 10px 12px; background: var(--color-warning-soft); border: 1px solid var(--color-warning-border); border-radius: var(--radius-control); display: flex; flex-direction: column; gap: 4px; }
.run-policy-editor__warnings li { display: flex; align-items: center; gap: 6px; color: var(--color-warning); font-size: 13px; }
.run-policy-editor__foot { position: sticky; bottom: -16px; display: flex; justify-content: space-between; align-items: center; gap: 12px; min-height: 56px; margin-top: auto; padding: 11px 0 0; background: var(--surface-panel, var(--surface-raised)); border-top: 1px solid var(--border-subtle); }
.run-policy-editor__foot-actions { display: flex; gap: 8px; }

@media (max-width: 900px) {
  .run-policy-editor__layout { grid-template-columns: minmax(0, 1fr); }
  .run-policy-editor__list { flex-direction: row; flex-wrap: wrap; border-right: 0; border-bottom: 1px solid var(--border-default); }
  .run-policy-editor__row { flex: 1; min-width: 200px; }
}
</style>
