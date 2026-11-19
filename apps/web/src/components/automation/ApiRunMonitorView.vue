<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { apiMonitorCounts, apiMonitorLogs, apiMonitorStatusLabel, type ApiRunMonitor } from '../../domain/apiRunMonitor'

const props = defineProps<{ monitor: ApiRunMonitor }>()
const emit = defineEmits<{ returnPreparation: []; openReport: [] }>()
const selectedKey = ref(props.monitor.rows[0]?.caseId || '')
const mobilePane = ref<'queue' | 'log'>('log')
const detailVisible = ref(false)
const detailOpener = ref<HTMLElement>()
const selectedRow = computed(() => props.monitor.rows.find(row => row.caseId === selectedKey.value))
const counts = computed(() => apiMonitorCounts(props.monitor))
const logs = computed(() => selectedRow.value ? apiMonitorLogs(selectedRow.value) : [])
const mode = computed(() => ({ sequential: '顺序', concurrent: '并发', module: '按模块' }[props.monitor.snapshot.options.mode]))
const loopCount = computed(() => props.monitor.rows.length / (props.monitor.snapshot.cases.length || 1))
const heading = ref<HTMLElement>()
const selectRow = (caseId: string) => { selectedKey.value = caseId; mobilePane.value = 'log' }
const openDetail = (event: MouseEvent) => { detailOpener.value = event.currentTarget as HTMLElement; detailVisible.value = true }
const closeDetail = async () => { detailVisible.value = false; await nextTick(); detailOpener.value?.focus({ preventScroll: true }) }
const formatValue = (value: unknown) => value === undefined ? '未提供' : JSON.stringify(value, null, 2)
const time = (value?: string) => value ? new Date(value).toLocaleTimeString('zh-CN', { hour12: false }) : '—'
watch(() => props.monitor, () => {
  selectedKey.value = props.monitor.rows[0]?.caseId || ''
  mobilePane.value = 'log'; detailVisible.value = false
})
defineExpose({ focusHeading: () => heading.value?.focus({ preventScroll: true }) })
</script>

<template>
  <section class="api-run-monitor" data-testid="api-run-monitor" aria-label="API 运行监控">
    <header class="api-monitor-heading">
      <div>
        <h1 ref="heading" tabindex="-1">API 运行监控</h1>
        <p>{{ monitor.snapshot.environmentName || '默认环境' }} · {{ monitor.snapshot.scopeLabel }} · {{ monitor.snapshot.cases.length }} 条用例</p>
      </div>
      <div class="api-monitor-actions">
        <button type="button" data-testid="api-monitor-back" @click="emit('returnPreparation')">返回执行准备</button>
        <button v-if="monitor.status === 'completed'" type="button" @click="emit('openReport')">查看报告</button>
      </div>
    </header>
    <section class="api-monitor-summary" aria-label="执行进度">
      <div class="api-monitor-summary-top">
        <span class="api-monitor-status" role="status">{{ monitor.status === 'running' ? '执行中' : monitor.status === 'completed' ? '执行已结束' : '执行异常' }}</span>
        <div class="api-monitor-counts">
          <span>已完成 <b>{{ counts.completed }} / {{ monitor.rows.length }}</b></span>
          <span>通过 <b>{{ counts.passed }}</b></span>
          <span class="failed">失败 {{ counts.failed }}</span>
          <span>错误 {{ counts.error }}</span><span>运行中 {{ counts.running }}</span><span>待执行 {{ counts.pending }}</span><span v-if="counts.unreturned">结果未回传 {{ counts.unreturned }}</span>
        </div>
      </div>
      <div class="api-monitor-bar" :class="{ 'is-complete': monitor.status === 'completed' }" role="progressbar" aria-label="用例执行次数" :aria-valuenow="monitor.progress.completed" aria-valuemin="0" :aria-valuemax="monitor.progress.total">
        <span :style="{ width: `${monitor.progress.total ? monitor.progress.completed / monitor.progress.total * 100 : 0}%` }" />
      </div>
      <p class="api-monitor-meta">{{ mode }} · 循环 {{ loopCount }} 次 · {{ monitor.rows.length }} 次执行 · 返回体一致度 {{ monitor.snapshot.options.responseBodyMatchThreshold ?? 100 }}%{{ monitor.snapshot.options.mode === 'concurrent' || monitor.snapshot.options.mode === 'module' ? ` · 并发 ${monitor.snapshot.options.concurrencyLimit ?? 8}` : '' }} · {{ monitor.status === 'running' ? '尚未生成最终结论。' : '结束不等于全部通过。' }}</p>
      <p v-if="monitor.errorMessage" class="api-monitor-error" role="alert">{{ monitor.errorMessage }}</p>
    </section>
    <div class="api-monitor-mobile-nav" role="group" aria-label="监控区域切换">
      <button type="button" :aria-pressed="mobilePane === 'queue'" data-testid="api-monitor-pane-queue" @click="mobilePane = 'queue'">执行队列</button>
      <button type="button" :aria-pressed="mobilePane === 'log'" data-testid="api-monitor-pane-log" @click="mobilePane = 'log'">执行日志</button>
    </div>
    <div class="api-monitor-split" :data-mobile-pane="mobilePane" data-testid="api-monitor-split">
      <section class="api-monitor-queue" data-testid="api-monitor-queue" aria-label="执行队列">
        <header class="api-monitor-panel-head"><h2>执行队列</h2><small>{{ monitor.rows.length }} 次执行</small></header>
        <div class="api-monitor-queue-scroll">
          <button v-for="row in monitor.rows" :key="row.caseId" type="button" class="api-monitor-queue-item" :class="{ active: selectedKey === row.caseId }" :aria-pressed="selectedKey === row.caseId" :data-case-id="row.caseId" :data-status="row.status" data-testid="api-monitor-queue-row" @click="selectRow(row.caseId)">
            <span class="api-monitor-queue-top"><span>{{ row.caseId }} · 第 {{ row.loop }} 轮</span><span class="api-monitor-queue-state" :class="row.status">{{ apiMonitorStatusLabel(row.status) }}</span></span>
            <h3 :title="row.testCase.name">{{ row.testCase.name }}</h3>
            <p>{{ row.testCase.method }} {{ row.testCase.path }}</p>
          </button>
        </div>
      </section>
      <section class="api-monitor-log" data-testid="api-monitor-log" aria-label="执行日志">
        <header class="api-monitor-panel-head"><div><h2>执行日志</h2><p class="api-monitor-log-head-name">{{ selectedRow?.caseId }} · 第 {{ selectedRow?.loop }} 轮</p></div><button type="button" class="api-monitor-log-details" :disabled="!selectedRow" data-testid="api-monitor-open-detail" @click="openDetail">请求与断言详情 ↗</button></header>
        <div class="api-monitor-log-scroll" tabindex="0" role="region" aria-label="所选用例日志">
          <div v-for="(line, index) in logs" :key="index" class="api-monitor-log-line"><span class="api-monitor-log-time">{{ time(line.at) }}</span><span :class="{ bad: line.error }">{{ line.text }}</span></div>
        </div>
      </section>
    </div>
    <footer class="api-monitor-footer"><span>前端本次执行记录 · 队列来自确认快照 · 结果随实际执行返回</span></footer>
    <el-drawer v-model="detailVisible" class="api-monitor-drawer" size="min(740px, calc(100vw - 48px))" :with-header="false" append-to-body aria-label="API 请求与断言详情" @closed="detailOpener?.focus({ preventScroll: true })">
      <div v-if="selectedRow" class="api-monitor-drawer-inner" data-testid="api-monitor-detail">
        <header class="api-monitor-drawer-header"><div><p>{{ selectedRow.caseId }} · 第 {{ selectedRow.loop }} 轮</p><h2>{{ selectedRow.testCase.name }}</h2></div><button type="button" aria-label="关闭请求详情" @click="closeDetail">×</button></header>
        <div class="api-monitor-drawer-scroll">
          <p class="api-monitor-drawer-note">{{ apiMonitorStatusLabel(selectedRow.status) }} · 仅显示本次执行实际记录；未完成的用例没有请求快照与响应。</p>
          <section class="api-monitor-detail-section"><h3>请求</h3><pre v-if="selectedRow.result" class="api-monitor-detail-code">{{ formatValue(selectedRow.result.request) }}</pre><p v-else class="api-monitor-detail-empty">{{ selectedRow.status === 'pending' ? '尚未开始执行，没有请求记录。' : selectedRow.status === 'unreturned' ? '执行异常结束，没有回传请求记录。' : '正在执行，尚无实际请求快照。' }}</p></section>
          <section class="api-monitor-detail-section"><h3>响应</h3><template v-if="selectedRow.result"><p class="api-monitor-result-status">实际状态：{{ selectedRow.result.actualStatus === undefined ? '未收到响应状态' : `HTTP ${selectedRow.result.actualStatus}` }} · 耗时 {{ selectedRow.result.durationMs }} ms</p><pre v-if="selectedRow.result.actualStatus !== undefined" class="api-monitor-detail-code">{{ formatValue(selectedRow.result.actualResponseBody !== undefined ? selectedRow.result.actualResponseBody : selectedRow.result.responseBody) }}</pre><p v-else class="api-monitor-detail-empty">请求错误，没有响应体。</p></template><p v-else class="api-monitor-detail-empty">{{ selectedRow.status === 'pending' ? '尚未开始执行，没有响应。' : selectedRow.status === 'unreturned' ? '执行异常结束，没有回传响应。' : '正在执行，尚无响应。' }}</p></section>
          <section class="api-monitor-detail-section"><h3>断言与返回体一致度</h3><pre class="api-monitor-detail-code">期望状态：{{ selectedRow.testCase.expectedStatus }}
返回体一致度阈值：{{ monitor.snapshot.options.responseBodyMatchThreshold ?? 100 }}%
{{ selectedRow.testCase.assertions.join('\n') || '未配置附加断言' }}</pre><p v-if="!selectedRow.result" class="api-monitor-detail-empty">尚无断言执行结果。</p><template v-else><p class="api-monitor-result-status">执行结论：{{ apiMonitorStatusLabel(selectedRow.result.status) }}</p><p v-if="selectedRow.result.responseBodyMatch" class="api-monitor-result-status">返回体参数一致度：{{ selectedRow.result.responseBodyMatch.ratio }}%</p><pre v-if="selectedRow.result.errorMessage" class="api-monitor-detail-code api-monitor-error">{{ selectedRow.result.errorMessage }}</pre></template></section>
          <section class="api-monitor-detail-section"><h3>来源用例</h3><p class="api-monitor-result-status">{{ selectedRow.testCase.sourceCaseId }} · {{ selectedRow.testCase.project }} / {{ selectedRow.testCase.module }}</p><p class="api-monitor-result-status">开始 {{ selectedRow.startedAt || '未开始' }} · 结束 {{ selectedRow.endedAt || '未结束' }}</p></section>
        </div>
        <footer class="api-monitor-drawer-actions"><p>本次执行实际记录</p><button type="button" @click="closeDetail">关闭详情</button></footer>
      </div>
    </el-drawer>
  </section>
</template>

<style src="./api-run-monitor.css"></style>
<style>
/* Keep the shared CSS out of the external style-src descriptor cache. */
@import "../../styles/run-monitor-controls.css";
</style>
