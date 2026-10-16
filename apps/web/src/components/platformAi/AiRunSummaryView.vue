<script setup lang="ts">
import { computed } from 'vue'
import {
  TriangleAlert,
  CircleCheck,
  Clock,
  ListChecks,
  RefreshCw,
  Route
} from '@lucide/vue'

type SummaryLike = Record<string, any> | null | undefined

const props = withDefaults(defineProps<{
  summary?: any
  status?: string
}>(), { summary: null, status: '' })

const summaryObject = computed<SummaryLike>(() => {
  const value = props.summary
  return value && typeof value === 'object' && !Array.isArray(value) ? value as SummaryLike : null
})
const present = computed(() => Boolean(summaryObject.value && Object.keys(summaryObject.value).length > 0))
const terminalReason = computed(() => String(summaryObject.value?.terminalReason ?? props.status ?? ''))
const succeeded = computed(() => terminalReason.value === 'succeeded' || props.status === 'succeeded')
const chunks = computed(() => summaryObject.value?.chunks ?? {})
const generatedTestPoints = computed(() => Number(summaryObject.value?.generatedTestPoints ?? summaryObject.value?.draftTestPointCount ?? 0))
const fallbackReasons = computed<string[]>(() => {
  const labels: Record<string, string> = {
    timeout: '请求超时',
    rate_limited: '模型调用被限流',
    upstream_error: '上游服务异常',
    transient_network: '网络连接异常',
    auth_denied: '鉴权失败',
    model_or_endpoint_not_found: '模型或地址不存在',
    invalid_request: '请求无效',
    output_validation: '结构化输出校验失败'
  }
  return Array.isArray(summaryObject.value?.fallbackReasons)
    ? summaryObject.value.fallbackReasons.map((reason: unknown) => labels[String(reason).toLowerCase()] || String(reason))
    : []
})
const validation = computed(() => summaryObject.value?.validationResult ?? {})
const retryCommand = computed(() => summaryObject.value?.retryCommand ?? {})
const attemptCount = computed(() => Array.isArray(summaryObject.value?.attempts) ? summaryObject.value.attempts.length : Number(summaryObject.value?.toolCallCount ?? 0))

const reasonLabel = computed(() => {
  switch (terminalReason.value) {
    case 'succeeded': return '分析完成'
    case 'run_deadline_exceeded': return '超过整体运行时限'
    case 'budget_exhausted': return '已耗尽可用重试'
    case 'timeout': return '请求超时'
    case 'rate_limited': return '模型调用被限流'
    case 'upstream_error': return '上游服务异常'
    case 'transient_network': return '网络连接异常'
    case 'auth_denied': return '鉴权失败'
    case 'model_or_endpoint_not_found': return '模型或地址不存在'
    case 'invalid_request': return '请求无效'
    case 'output_validation': return '结构化输出校验失败'
    case 'no_route': return '未配置可用路由'
    case 'cancelled': return '已取消'
    default: return terminalReason.value || '已结束'
  }
})
</script>

<template>
  <section v-if="present" class="ai-run-summary" data-testid="ai-run-summary" :class="succeeded ? 'ai-run-summary--succeeded' : 'ai-run-summary--failed'">
    <header class="ai-run-summary__header">
      <span class="ai-run-summary__header-icon"><component :is="succeeded ? CircleCheck : TriangleAlert" :size="16" aria-hidden="true" /></span>
      <div><strong>运行结果摘要</strong><span>{{ succeeded ? '任务已完成' : '任务未完成，可从失败位置继续' }}</span></div>
      <span class="ai-run-summary__reason">{{ reasonLabel }}</span>
    </header>
    <div class="ai-run-summary__content">
      <dl class="ai-run-summary__metrics">
        <div class="ai-run-summary__metric">
          <dt><ListChecks :size="14" aria-hidden="true" /> 已完成分片</dt>
          <dd>{{ chunks.completed ?? 0 }}<small>/ {{ chunks.total ?? 0 }}</small></dd>
        </div>
        <div class="ai-run-summary__metric">
          <dt><RefreshCw :size="14" aria-hidden="true" /> 路由切换</dt>
          <dd>{{ chunks.retried ?? 0 }}</dd>
        </div>
        <div class="ai-run-summary__metric">
          <dt><TriangleAlert :size="14" aria-hidden="true" /> 失败分片</dt>
          <dd>{{ chunks.failed ?? 0 }}</dd>
        </div>
        <div class="ai-run-summary__metric">
          <dt><Route :size="14" aria-hidden="true" /> 模型调用</dt>
          <dd>{{ attemptCount }}</dd>
        </div>
        <div class="ai-run-summary__metric">
          <dt><ListChecks :size="14" aria-hidden="true" /> 生成测试点</dt>
          <dd>{{ generatedTestPoints }}</dd>
        </div>
      </dl>
      <div class="ai-run-summary__details">
        <div v-if="fallbackReasons.length > 0" class="ai-run-summary__row">
          <span class="ai-run-summary__row-label">异常原因</span>
          <ul class="ai-run-summary__reasons"><li v-for="reason in fallbackReasons" :key="reason">{{ reason }}</li></ul>
        </div>
        <div class="ai-run-summary__row">
          <span class="ai-run-summary__row-label">结构化校验</span>
          <span class="ai-run-summary__validation" :class="{ 'is-passed': validation.valid }">{{ validation.valid ? '通过' : '未通过' }}<template v-if="validation.repairTriggered">，已触发修复</template></span>
        </div>
        <div class="ai-run-summary__row ai-run-summary__retry">
          <Clock :size="14" aria-hidden="true" />
          <span v-if="retryCommand.safe">可从失败分片继续解析</span>
          <span v-else class="ai-run-summary__retry-unsafe">当前失败原因不建议直接重试，请先修正配置</span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.ai-run-summary {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 15px 16px;
  border-radius: 8px;
  border: 1px solid var(--border-subtle);
  background: var(--surface-raised);
  box-shadow: 0 8px 22px rgba(38, 53, 75, .045);
  font-size: 13px;
  color: var(--text-secondary);
}
.ai-run-summary--succeeded { border-color: var(--color-success-border); }
.ai-run-summary--failed { border-color: var(--color-danger-border); }
.ai-run-summary__header { display: flex; align-items: center; gap: 9px; color: var(--text-primary); }
.ai-run-summary__header > div { display: grid; gap: 2px; }
.ai-run-summary__header strong { font-size: 13px; font-weight: var(--font-weight-semibold); }
.ai-run-summary__header > div > span { color: var(--text-tertiary); font-size: 11px; }
.ai-run-summary__header-icon { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 6px; background: var(--surface-sunken, var(--surface-page)); color: var(--text-tertiary); }
.ai-run-summary--succeeded .ai-run-summary__header-icon { color: var(--color-success); background: var(--color-success-soft); }
.ai-run-summary--failed .ai-run-summary__header-icon { color: var(--color-danger); background: var(--color-danger-soft); }
.ai-run-summary__reason { margin-left: auto; color: var(--text-secondary); font-size: 11px; font-weight: var(--font-weight-medium); }
.ai-run-summary__content { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(210px, .85fr); gap: 18px; }
.ai-run-summary__metrics { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); margin: 0; border: 1px solid var(--border-subtle); border-radius: 6px; overflow: hidden; }
.ai-run-summary__metric { display: flex; min-width: 0; flex-direction: column; gap: 5px; padding: 11px 10px; border-right: 1px solid var(--border-subtle); }
.ai-run-summary__metric:last-child { border-right: 0; }
.ai-run-summary__metric dt { display: flex; align-items: center; gap: 4px; color: var(--text-tertiary); font-size: 11px; font-weight: 400; white-space: nowrap; }
.ai-run-summary__metric dd { margin: 0; color: var(--text-primary); font-size: 15px; font-weight: var(--font-weight-semibold); line-height: 1.15; }
.ai-run-summary__metric dd small { color: var(--text-tertiary); font-size: 11px; font-weight: 500; }
.ai-run-summary__details { display: flex; flex-direction: column; justify-content: center; gap: 8px; padding-left: 18px; border-left: 1px solid var(--border-subtle); }
.ai-run-summary__row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; min-width: 0; }
.ai-run-summary__row-label { flex: none; color: var(--text-tertiary); min-width: 54px; font-size: 11px; }
.ai-run-summary__reasons { display: flex; flex-wrap: wrap; gap: 6px; min-width: 0; margin: 0; padding: 0; list-style: none; color: var(--color-warning); }
.ai-run-summary__reasons li { overflow: hidden; max-width: 100%; text-overflow: ellipsis; white-space: nowrap; }
.ai-run-summary__reasons li::before { content: '•'; margin-right: 4px; }
.ai-run-summary__validation { color: var(--color-danger); }
.ai-run-summary__validation.is-passed { color: var(--color-success); }
.ai-run-summary__retry { color: var(--text-secondary); font-size: 12px; }
.ai-run-summary__retry-unsafe { color: var(--color-danger); }
@media (max-width: 820px) {
  .ai-run-summary__content { grid-template-columns: minmax(0, 1fr); gap: 14px; }
  .ai-run-summary__details { padding-top: 14px; padding-left: 0; border-top: 1px solid var(--border-subtle); border-left: 0; }
}
@media (max-width: 560px) {
  .ai-run-summary { padding: 14px; }
  .ai-run-summary__metrics { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .ai-run-summary__metric:nth-child(3) { border-right: 0; }
  .ai-run-summary__metric:nth-child(n + 4) { border-top: 1px solid var(--border-subtle); }
}
</style>
