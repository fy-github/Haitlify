<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useAssistantChain } from '../../ui/useAssistantChain'
import GenerationAdmissionForm from '../assistant/GenerationAdmissionForm.vue'
import type { CaseSpec } from '../../domain/assistantChainRepository'
import type { AuthUser } from '../../domain/authRepository'
import { listBackendProjects, type BackendProject } from '../../domain/platformBackendRepository'

const props = defineProps<{
  user?: AuthUser | null
}>()

const projects = ref<BackendProject[]>([])
const selectedProjectId = ref('')
const projectError = ref('')
const project = computed(() => projects.value.find(item => String(item.id) === selectedProjectId.value))
const positiveId = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0
const contextError = computed(() => {
  if (!props.user?.username || !positiveId(props.user.id)) return '认证上下文缺失：需要认证接口返回真实用户 ID，不能使用默认操作人。'
  if (!positiveId(project.value?.id) || !positiveId(project.value?.tenantId)) return '请选择具有有效租户信息的项目；上下文缺失时禁止提交。'
  return ''
})
watch(() => props.user, async (user, _old, onCleanup) => {
  let active = true
  onCleanup(() => { active = false })
  projects.value = []
  selectedProjectId.value = ''
  projectError.value = ''
  if (!user?.username) return
  try {
    const loaded = await listBackendProjects()
    if (active) projects.value = loaded
  } catch (value) {
    if (active) projectError.value = value instanceof Error ? value.message : String(value)
  }
}, { immediate: true, flush: 'sync' })
const requirementRef = ref('manual://assistant-chain')
const requirementText = ref('')
const specText = ref(JSON.stringify({
  id: 'case-ui',
  name: 'UI 草稿',
  startUrl: 'https://example.com/',
  steps: [
    { id: 's1', action: 'goto', target: 'https://example.com/', value: null },
    { id: 's2', action: 'assertVisible', target: '.ant-tabs-tab-btn:text-is("联系信息")', value: null }
  ]
}, null, 2))
// 清理证据必须是结构化 JSON（含 artifactUrl / readBack / verifiedBy 之一），
// 一句「已清理」会被服务端拒绝：这里默认填「回读为空」这一可核验结论。
const evidence = ref('{"readBack":"absent"}')
const parseError = ref('')

const chain = useAssistantChain({
  get tenantId() { return project.value?.tenantId },
  get actorId() { return props.user?.id },
  get projectId() { return project.value?.id },
  pollIntervalMs: 1000,
  maxPolls: 60
})

const draft = chain.draft
const operation = chain.operation
const report = chain.report
const error = chain.error
const busy = chain.busy
const resources = chain.resources
const waiting = chain.waiting
const isWaitingUser = chain.isWaitingUser
const waitingReason = ref('需要本人完成 MFA / 人工登录')
const waitingTimeout = ref(30)

type ReportTotals = { total?: number; passed?: number; failed?: number; error?: number; runTotal?: number }
type ReportBody = {
  executionState?: string
  summary?: ReportTotals
  diagnostics?: {
    stepTotals?: ReportTotals
    unknownAttempts?: Array<{ generation?: number; runId?: string | null }>
    failedAttempts?: Array<{ generation?: number; outcome?: string; failed?: number | null; error?: number | null }>
    note?: string
  }
  attempts?: Array<{ generation?: number; outcome?: string; summary?: ReportTotals }>
  resources?: Array<{ kind?: string; cleanupState?: string }>
}

const parsedReport = computed<ReportBody | null>(() => {
  if (!report.value?.bodyJson) return null
  try {
    const value: unknown = JSON.parse(report.value.bodyJson)
    return value && typeof value === 'object' ? value as ReportBody : null
  } catch {
    return null
  }
})
/** 后端正文把通过/失败放 diagnostics.stepTotals，把总数放在各代 attempt.summary；两处都要用上。 */
const attemptTotals = computed<ReportTotals>(() =>
  (parsedReport.value?.attempts ?? []).reduce<ReportTotals>((totals, attempt) => {
    const summary = attempt.summary
    if (!summary) return totals
    return {
      total: (totals.total ?? 0) + (summary.total ?? summary.runTotal ?? 0),
      passed: (totals.passed ?? 0) + (summary.passed ?? 0),
      failed: (totals.failed ?? 0) + (summary.failed ?? 0),
      error: (totals.error ?? 0) + (summary.error ?? 0)
    }
  }, {}))
const reportTotals = computed<ReportTotals>(() => {
  const body = parsedReport.value
  if (!body) return {}
  const steps = body.diagnostics?.stepTotals
  const summary = body.summary
  const attempts = attemptTotals.value
  return {
    total: steps?.total ?? summary?.total ?? summary?.runTotal ?? attempts.total,
    passed: steps?.passed ?? summary?.passed ?? attempts.passed,
    failed: steps?.failed ?? summary?.failed ?? attempts.failed,
    error: steps?.error ?? summary?.error ?? attempts.error
  }
})
const formatDeadline = (value: string): string => value
  .replace('T', ' ')
  .replace(/\\.\\d+Z$/, '')
  .replace(/Z$/, '')

const TERMINAL_STATES = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN']
const executionState = computed(() => operation.value?.executionState ?? '')
/** 报告是终态快照：等待中、执行中、已接受都不能再生成，避免必然 409 的按钮。 */
const reportGenerationBlock = computed(() => {
  if (!operation.value) return '还没有执行记录'
  if (isWaitingUser.value) return '等待中不能生成报告：补齐后需重新执行到终态'
  if (!TERMINAL_STATES.includes(executionState.value)) return `执行未到终态（${executionState.value}），不能生成报告`
  if (operation.value.reportState === 'ACCEPTED') return '报告已接受，不能重新生成；如需更正请新起一次执行'
  return ''
})
const canMakeReport = computed(() => !busy.value && !!operation.value && !reportGenerationBlock.value)
/** 报告正文带的是生成时的资源快照：清理后再接受会被服务端拒（快照过期），这里先拦住并提示重新生成。 */
const reportResourceStale = computed(() => {
  const snapshot = parsedReport.value?.resources
  if (!Array.isArray(snapshot)) return false
  const keys = (items: Array<{ kind?: string; state?: string }>) =>
    items.map(item => `${item.kind ?? ''}:${item.state ?? ''}`).sort().join('|')
  const current = (operation.value?.resources ?? []).map(item => ({ kind: item.resourceKind, state: item.cleanupState }))
  return keys(snapshot.map(item => ({ kind: item.kind, state: item.cleanupState }))) !== keys(current)
})
const canAcceptReport = computed(() => !busy.value && !!report.value
  && operation.value?.reportState !== 'ACCEPTED' && !reportResourceStale.value)
const unresolvedGenerations = computed(() =>
  (parsedReport.value?.diagnostics?.unknownAttempts ?? [])
    .map(item => item.generation)
    .filter((generation): generation is number => typeof generation === 'number'))

const spec = computed<CaseSpec | null>(() => {
  try {
    const parsed = JSON.parse(specText.value) as CaseSpec
    parseError.value = ''
    return parsed
  } catch (value) {
    parseError.value = value instanceof Error ? value.message : String(value)
    return null
  }
})

const create = async () => {
  if (!spec.value) return
  await chain.createDraft(spec.value, requirementRef.value, requirementText.value || spec.value.name)
}
const confirm = () => chain.confirm()
const reject = () => chain.reject()
const revise = async () => {
  if (!spec.value) return
  await chain.reviseDraft(spec.value)
}
const execute = () => chain.execute()
const refresh = () => chain.refresh()
const makeReport = () => chain.makeReport()
const accept = () => chain.decideReport(true)
const clean = (resourceId: string) => chain.cleanResource(resourceId, evidence.value)
const unrecoverable = (resourceId: string) => chain.markUnrecoverable(resourceId, '人工判定不可恢复')
const beginWaiting = () => chain.beginWaiting(waitingReason.value, waitingTimeout.value)
const resumeWaiting = () => chain.resumeFromWaiting()
const abandonWaiting = () => chain.abandonWaiting('用户在助手链路放弃等待')
</script>

<template>
  <section class="chain">
    <header class="chain__head">
      <h1>助手开发链路</h1>
      <p class="hint">需求受理与手工调试独立；当前不是完整 AI 生成入口。</p>
    </header>

    <div class="panel">
      <label>项目
        <select v-model="selectedProjectId" data-testid="assistant-project">
          <option value="">请选择项目</option>
          <option v-for="item in projects" :key="item.id" :value="String(item.id)">{{ item.name }} · {{ item.code }}</option>
        </select>
      </label>
      <p v-if="projectError" class="error">{{ projectError }}</p>
      <p v-if="contextError" class="error" role="alert">{{ contextError }}</p>
    </div>
    <GenerationAdmissionForm :user="user" :project="project" />

    <h3>手工 JSON 调试</h3>
    <p class="hint">草稿 → 确认 → 执行 → 报告 → 资源收尾；独立开发调试区，不使用上方受理记录，也不代表模型生成。</p>
    <div class="chain__grid">
      <div class="panel">
        <label>需求引用 <input v-model="requirementRef" /></label>
        <label>需求原文 <textarea v-model="requirementText" rows="2" /></label>
        <label>用例结构（JSON）
          <textarea v-model="specText" rows="14" spellcheck="false" />
        </label>
        <p v-if="parseError" class="error">JSON 解析失败：{{ parseError }}</p>
        <div class="actions">
          <button :disabled="busy || !!contextError" @click="create">新建草稿</button>
          <button :disabled="busy || !draft" @click="confirm">确认</button>
          <button :disabled="busy || !draft" @click="reject">驳回</button>
          <button :disabled="busy || !draft" @click="revise">派生修订</button>
          <button :disabled="busy || !draft || draft.state !== 'CONFIRMED'" @click="execute">执行</button>
          <button :disabled="busy || !operation" @click="refresh">刷新</button>
        </div>
      </div>

      <div class="panel">
        <p v-if="error" class="error">{{ error }}</p>
        <dl>
          <dt>草稿</dt>
          <dd>{{ draft ? `${draft.draftId} · r${draft.revision ?? 1} · ${draft.state}` : '—' }}</dd>
          <dt>执行</dt>
          <dd>{{ operation ? `${operation.executionState} · 报告 ${operation.reportState}` : '—' }}</dd>
          <dt>报告</dt>
          <dd>{{ report ? `${report.reportId} · r${report.revision}` : '—' }}</dd>
        </dl>

        <section v-if="report" class="report-details" data-testid="assistant-report-details" aria-label="报告正文详情">
          <h3>报告摘要</h3>
          <p v-if="!parsedReport" class="error" role="alert">报告正文不可解析，不能把报告编号当作执行事实。</p>
          <dl v-else class="report-details__facts">
            <dt>执行事实</dt>
            <dd>{{ parsedReport.executionState || '—' }}</dd>
            <dt>总执行</dt>
            <dd>{{ reportTotals.total ?? '—' }}</dd>
            <dt>通过</dt>
            <dd>{{ reportTotals.passed ?? '—' }}</dd>
            <dt>失败</dt>
            <dd>{{ reportTotals.failed ?? '—' }}</dd>
            <dt>错误</dt>
            <dd>{{ reportTotals.error ?? '—' }}</dd>
          </dl>
          <p v-if="parsedReport?.diagnostics?.stepTotals" class="muted">以上统计来自报告 diagnostics.stepTotals，不代表真实 Runner 以外的推断。</p>
          <p v-if="unresolvedGenerations.length" data-report-unresolved="true" role="alert" class="error">
            <span v-for="generation in unresolvedGenerations" :key="generation">第 {{ generation }} 代结果无法确认（UNKNOWN）；</span>
            {{ parsedReport?.diagnostics?.note || '有代次结果无法确认，不能读作全部通过' }}
          </p>
          <p v-if="reportResourceStale" data-testid="report-stale-notice" class="error" role="alert">
            报告正文的资源快照已过期，请重新生成后再接受。
          </p>
        </section>

        <section v-if="isWaitingUser" class="waiting" data-testid="assistant-waiting-user">
          <strong>等待用户处理</strong>
          <p class="muted">
            {{ waiting?.reason || '助手需要用户完成登录/验证后继续' }}
            <template v-if="waiting?.deadlineAt"> · 截止 {{ waiting.deadlineAt }}</template>
          </p>
          <p class="muted">等待中不能生成报告；补齐后回到待派发，必须重新执行一次。</p>
          <div class="actions">
            <button :disabled="busy" @click="resumeWaiting">我已补齐，回到待执行</button>
            <button :disabled="busy" @click="abandonWaiting">放弃等待</button>
          </div>
        </section>
        <section v-else class="waiting-request">
          <label>等待原因 <input v-model="waitingReason" /></label>
          <label>等待时长（分钟，1–1440） <input v-model.number="waitingTimeout" type="number" min="1" max="1440" /></label>
          <div class="actions">
            <button :disabled="busy || !operation" @click="beginWaiting">标记等待用户</button>
          </div>
        </section>

        <div class="actions">
          <button data-testid="report-action-make" :disabled="!canMakeReport" @click="makeReport">生成报告</button>
          <button data-testid="report-action-accept" :disabled="!canAcceptReport" @click="accept">接受报告</button>
        </div>
        <p v-if="operation && reportGenerationBlock" data-testid="report-generation-block" class="muted" role="status">{{ reportGenerationBlock }}</p>

        <h3>资源</h3>
        <ul class="resources">
          <li v-for="item in resources" :key="item.resourceId">
            <span>
              <strong>{{ item.resourceKind }} · {{ item.cleanupState }}</strong>
              <small v-if="item.cleanupExecutor" class="resource-detail">资源清理责任：{{ item.cleanupExecutor }}</small>
              <small v-if="item.deadlineAt" class="resource-detail">截止 {{ formatDeadline(item.deadlineAt) }}</small>
            </span>
            <button
              v-if="['ALLOCATED', 'CLEANING', 'MANUAL_REQUIRED'].includes(item.cleanupState)"
              :disabled="busy"
              @click="clean(item.resourceId)"
            >标记已清理</button>
            <button
              v-if="item.cleanupState === 'MANUAL_REQUIRED'"
              :disabled="busy"
              @click="unrecoverable(item.resourceId)"
            >判不可恢复</button>
          </li>
          <li v-if="!resources.length" class="muted">暂无资源登记</li>
        </ul>
        <label>清理证据 <input v-model="evidence" /></label>
      </div>
    </div>
  </section>
</template>

<style scoped>
.chain { grid-row: 1 / -1; height: 100%; min-width: 0; min-height: 0; overflow-y: auto; padding: 20px; color: var(--text-primary); }
.chain *, .chain *::before, .chain *::after { box-sizing: border-box; }
.chain__head h1 { margin: 0 0 4px; font-size: 20px; font-weight: 600; }
.hint { margin: 0 0 14px; font-size: 12px; color: var(--text-secondary); overflow-wrap: anywhere; }
.chain__grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; min-width: 0; }
.panel { min-width: 0; border: 1px solid var(--border-default); border-radius: 10px; padding: 14px; background: var(--surface-raised); }
label { display: flex; flex-direction: column; gap: 4px; min-width: 0; font-size: 12px; margin-bottom: 8px; }
input, textarea, select { min-width: 0; max-width: 100%; width: 100%; font: inherit; font-size: 12px; padding: 8px 10px; border: 1px solid var(--border-default); border-radius: 6px; background: var(--surface-raised); color: var(--text-primary); }
textarea { resize: vertical; }
.actions { display: flex; flex-wrap: wrap; gap: 8px; margin: 10px 0; }
button { max-width: 100%; font: inherit; font-size: 12px; padding: 7px 12px; border: 1px solid var(--border-default); border-radius: 18px; background: var(--surface-raised); color: var(--text-regular); cursor: pointer; }
button:not(:disabled):hover { filter: brightness(.96); }
button:focus-visible, input:focus-visible, textarea:focus-visible, select:focus-visible { outline: none; box-shadow: none; background: var(--surface-subtle); }
button:focus-visible { filter: brightness(.9); }
button:disabled { opacity: .5; cursor: not-allowed; }
dl { display: grid; grid-template-columns: 64px minmax(0, 1fr); gap: 6px 10px; font-size: 12px; margin: 0 0 10px; }
dt { color: var(--text-secondary); }
dd { margin: 0; word-break: break-all; }
.resources { list-style: none; padding: 0; margin: 0 0 10px; display: flex; flex-direction: column; gap: 6px; font-size: 12px; }
.resources li { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 8px; border-bottom: 1px dashed var(--border-default); padding-bottom: 4px; overflow-wrap: anywhere; }
.error { color: var(--color-danger); font-size: 12px; overflow-wrap: anywhere; }
.waiting { border: 1px solid var(--border-default); border-radius: 8px; padding: 10px; margin: 10px 0; }
.waiting-request { margin: 10px 0; }
.muted { color: var(--text-secondary); }
@media (max-width: 768px) {
  .chain { padding: 16px 12px; }
  .chain__grid { grid-template-columns: minmax(0, 1fr); }
  button, input, select { min-height: 40px; }
}
</style>
