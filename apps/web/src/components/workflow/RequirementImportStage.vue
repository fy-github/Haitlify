<script setup lang="ts">
import { CircleAlert, Bot, CircleCheck, Frame, FileText, Layers, ListChecks, Search, Square, Upload } from '@lucide/vue'
import { computed, nextTick, ref, watch } from 'vue'
import type { CaseDesignSourceView } from '../../domain/caseDesignSourceView'
import type { WorkflowAsyncStatus } from '../../domain/caseDesignWorkflow'
import type { AiAnalysisLogEntry } from '../../domain/aiAnalysisProgress'
import ResponsiveDrawer from './ResponsiveDrawer.vue'
import RequirementSourceList from './RequirementSourceList.vue'
import WorkflowStateView from './WorkflowStateView.vue'

const props = withDefaults(defineProps<{
  sources: CaseDesignSourceView[]
  analysisStatus: WorkflowAsyncStatus
  progressPercentage: number
  progressText: string
  errorMessage: string
  parsedItems?: Array<{ id: string; kindLabel: string; label: string }>
  completedSourceCount?: number
  totalSourceCount?: number
  analysisLog?: AiAnalysisLogEntry[]
  compact?: boolean
}>(), { parsedItems: () => [], completedSourceCount: 0, totalSourceCount: 0, analysisLog: () => [], compact: false })

const emit = defineEmits<{
  pickFiles: []
  importFigma: []
  retrySource: [name: string]
  replaceSource: [name: string]
  removeSource: [name: string]
  cancelAnalysis: []
}>()

const sourceDrawerOpen = ref(false)
const sourceImportLocked = computed(() => props.analysisStatus === 'running')
watch(() => props.compact, (compact) => {
  if (!compact) sourceDrawerOpen.value = false
})

const analysisLogScroll = ref<HTMLElement | null>(null)
const analysisLogExpanded = ref(true)
const shouldShowAnalysisLog = computed(() => props.analysisStatus !== 'idle' || props.analysisLog.length > 0)

const scrollAnalysisLogToLatest = async () => {
  if (!analysisLogExpanded.value) return
  await nextTick()
  const element = analysisLogScroll.value
  if (element) element.scrollTop = element.scrollHeight
}

watch(
  () => props.analysisLog.length,
  scrollAnalysisLogToLatest,
  { flush: 'post', immediate: true }
)
watch(
  analysisLogExpanded,
  (expanded) => {
    if (expanded) void scrollAnalysisLogToLatest()
  },
  { flush: 'post' }
)

const analysisLogIcon = (kind: AiAnalysisLogEntry['kind']) => {
  switch (kind) {
    case 'info': return Layers
    case 'chunk': return Search
    case 'draft': return ListChecks
    case 'call': return Bot
    case 'success': return CircleCheck
    case 'error': return CircleAlert
    default: return FileText
  }
}
</script>

<template>
  <section class="studio-import-stage" data-testid="requirement-import-stage">
    <button v-if="compact" type="button" class="studio-import-stage__source-toggle" data-testid="context-drawer-button" aria-controls="requirement-source-drawer" :aria-expanded="sourceDrawerOpen" @click="sourceDrawerOpen = true">查看需求来源（{{ sources.length }}）</button>
    <ResponsiveDrawer v-if="compact" v-model="sourceDrawerOpen" title="本次任务来源" size="min(82vw, 320px)" direction="ltr">
      <RequirementSourceList :sources="sources" @retry="emit('retrySource', $event)" @replace="emit('replaceSource', $event)" @remove="emit('removeSource', $event)" />
    </ResponsiveDrawer>
    <RequirementSourceList v-else :sources="sources" @retry="emit('retrySource', $event)" @replace="emit('replaceSource', $event)" @remove="emit('removeSource', $event)" />
    <div class="studio-import-stage__canvas">
      <WorkflowStateView v-if="sources.length === 0" variant="empty" title="还没有需求来源" description="添加需求文档、Figma 后开始解析" action-label="添加需求来源" @action="emit('pickFiles')" />
      <header><div><strong>添加需求来源</strong><span>多个来源可以共同参与本次分析</span></div><span>{{ sources.filter((source) => source.status === 'ready').length }} 个来源已就绪</span></header>
      <div class="studio-source-actions" :class="{ 'is-locked': sourceImportLocked }">
        <button type="button" data-testid="requirement-source-upload" :disabled="sourceImportLocked" @click="emit('pickFiles')"><Upload :size="18" /><strong>上传需求文档</strong><span>文档、表格、图片与原型截图</span></button>
        <button type="button" data-testid="requirement-source-figma" :disabled="sourceImportLocked" @click="emit('importFigma')"><Frame :size="18" /><strong>连接 Figma</strong><span>读取设计节点、文案与交互线索</span></button>
      </div>
      <div v-if="analysisStatus !== 'idle' || errorMessage" class="studio-analysis-status" role="status">
        <FileText :size="18" /><div><strong>{{ analysisStatus === 'failed' ? '内容解析未完成' : progressText }}</strong><span v-if="totalSourceCount > 0">已完成 {{ completedSourceCount }} / {{ totalSourceCount }} 个来源</span><span v-if="errorMessage">{{ errorMessage }}</span><el-progress v-else :percentage="progressPercentage" :status="analysisStatus === 'succeeded' ? 'success' : undefined" /></div>
        <button v-if="analysisStatus === 'running'" type="button" class="studio-analysis-status__cancel" data-testid="cancel-ai-analysis" @click="emit('cancelAnalysis')"><Square :size="13" aria-hidden="true" />取消分析</button>
      </div>
      <section v-if="shouldShowAnalysisLog" class="studio-analysis-log" data-testid="analysis-stream-log" aria-label="AI 分析实时日志">
        <button type="button" class="studio-analysis-log__toggle" :aria-expanded="analysisLogExpanded" @click="analysisLogExpanded = !analysisLogExpanded">
          <strong>分析进度日志</strong>
          <span>{{ analysisStatus === 'running' ? '实时刷新中' : `${analysisLog.length} 条记录` }}</span>
        </button>
        <ul v-show="analysisLogExpanded" ref="analysisLogScroll" class="studio-analysis-log__list" role="log" aria-live="polite" aria-relevant="additions text">
          <li v-if="analysisLog.length === 0" class="studio-analysis-log__line studio-analysis-log__line--info">
            <Bot :size="14" class="studio-analysis-log__icon" />
            <span>{{ analysisStatus === 'running' ? '等待 AI 返回处理事件…' : '暂无分析日志' }}</span>
          </li>
          <li v-for="entry in analysisLog" :key="entry.id" :class="`studio-analysis-log__line studio-analysis-log__line--${entry.kind}`">
            <component :is="analysisLogIcon(entry.kind)" :size="14" class="studio-analysis-log__icon" />
            <span>{{ entry.text }}</span>
          </li>
        </ul>
      </section>
      <section v-if="parsedItems.length" class="studio-parsed-items" aria-label="已解析内容"><header><strong>已解析内容</strong><span>{{ parsedItems.length }}</span></header><ul class="studio-parsed-items__list" data-testid="parsed-items-list"><li v-for="item in parsedItems" :key="item.id"><span>{{ item.kindLabel }}</span><strong>{{ item.label }}</strong></li></ul></section>
    </div>
  </section>
</template>
