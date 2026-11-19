<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch, type ComponentPublicInstance } from 'vue'
import { ArrowRight, ChartNoAxesCombined, Play } from '@lucide/vue'
import { buildHomeWorkbench, type HomeProject, type HomeRun, type HomeWorkItem, type HomeWorkFilter, type HomeSection } from '../../domain/homeWorkbench'
import type { HomeNavigateTarget } from '../../domain/homeWorkbench'
export type { HomeNavigateTarget } from '../../domain/homeWorkbench'

export type HomeViewStatus = 'ready' | 'loading' | 'empty' | 'unavailable' | 'forbidden' | 'error'

const props = withDefaults(defineProps<{
  runs?: readonly HomeRun[]
  items?: readonly HomeWorkItem[]
  projects?: readonly HomeProject[]
  status?: HomeViewStatus
  errorMessage?: string
}>(), { runs: () => [], items: () => [], projects: () => [], status: 'ready', errorMessage: '' })
const emit = defineEmits<{ navigate: [target: HomeNavigateTarget]; retry: [] }>()
const project = ref('')
const workFilter = ref<HomeWorkFilter>('all')
const model = computed(() => buildHomeWorkbench({ runs: props.runs, items: props.items, project: project.value, filter: workFilter.value }))
const activeRuns = computed(() => model.value.runs)
const recent = computed(() => model.value.recent)
const pending = computed(() => model.value.pending)
const workKinds = { case: '用例', report: '报告', defect: '缺陷', project: '项目', task: '任务' }
const filters: { value: HomeWorkFilter; label: string }[] = [{ value: 'all', label: '全部' }, ...Object.entries(workKinds).map(([value, label]) => ({ value: value as HomeWorkFilter, label }))]
const displayTime = (timestamp?: string) => timestamp ? timestamp.replace('T', ' ').slice(0, 16) : '时间未提供'
const projectLabel = (value: string) => props.projects.find((item) => item.value === value)?.label || value || '未关联项目'
const projectPicker = ref<HTMLElement>()
const projectTrigger = ref<HTMLButtonElement>()
const projectOpen = ref(false)
const projectIndex = ref(0)
const projectOptions = computed(() => [{ value: '', label: '全部项目' }, ...props.projects])
const closeProjects = () => { projectOpen.value = false }
const openProjects = (index = Math.max(0, projectOptions.value.findIndex((item) => item.value === project.value))) => {
  projectIndex.value = index
  projectOpen.value = true
}
const selectProject = (value: string) => {
  project.value = value
  closeProjects()
  projectTrigger.value?.focus({ preventScroll: true })
}
const projectKeydown = (event: KeyboardEvent) => {
  if (event.key === 'Tab') { closeProjects(); return }
  if (event.key === 'Escape') { event.preventDefault(); closeProjects(); return }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End', 'Enter', ' '].includes(event.key)) return
  event.preventDefault()
  if (event.key === 'Home' || event.key === 'End') openProjects(event.key === 'Home' ? 0 : projectOptions.value.length - 1)
  else if (event.key === 'Enter' || event.key === ' ') {
    if (projectOpen.value) selectProject(projectOptions.value[projectIndex.value]!.value)
    else openProjects()
  } else if (!projectOpen.value) openProjects()
  else projectIndex.value = (projectIndex.value + (event.key === 'ArrowDown' ? 1 : -1) + projectOptions.value.length) % projectOptions.value.length
}
const outsideProject = (event: PointerEvent) => {
  if (!projectPicker.value?.contains(event.target as Node)) closeProjects()
}
onMounted(() => document.addEventListener('pointerdown', outsideProject))
onBeforeUnmount(() => document.removeEventListener('pointerdown', outsideProject))
watch(() => props.projects, () => {
  if (project.value && !props.projects.some((item) => item.value === project.value)) project.value = ''
  closeProjects()
})
const sectionElements: Partial<Record<HomeSection, HTMLElement>> = {}
const setSection = (section: HomeSection, element: Element | ComponentPublicInstance | null) => {
  if (element instanceof HTMLElement) sectionElements[section] = element
}
const focusSection = (section: HomeSection) => {
  const element = sectionElements[section]
  element?.focus({ preventScroll: true })
  // Scroll the owning workspace rather than searching/mutating the global document.
  if (element) {
    const container = element.closest('.workspace') as HTMLElement | null
    const box = element.getBoundingClientRect()
    const bounds = container?.getBoundingClientRect()
    if (container && bounds && (box.top < bounds.top || box.bottom > bounds.bottom)) container.scrollTop += box.top - bounds.top - 24
  }
}
defineExpose({ focusSection })
const primary = computed(() => activeRuns.value[0])
const secondary = computed(() => activeRuns.value.slice(1))
const percent = computed(() => {
  const run = primary.value
  if (!run || ['waiting_user', 'preparing', 'queued'].includes(run.state)
    || !Number.isSafeInteger(run.total) || !Number.isSafeInteger(run.completed)
    || run.total! <= 0 || run.completed! < 0 || run.completed! > run.total!) return undefined
  return Math.round(run.completed! / run.total! * 100)
})
const labels = { preparing: '准备中', queued: '排队中', running: '正在执行', pause_requested: '暂停中', paused: '已暂停', stop_requested: '停止中', waiting_user: '等待人工', completed: '已完成', failed: '执行失败', stopped: '已停止' }
</script>

<template>
  <section class="home-workbench" aria-labelledby="home-heading">
    <header class="home-page-head">
      <div><h1 id="home-heading">我的工作台</h1><p>先看运行进度，再接着完成手上的工作。</p></div>
      <div class="home-head-actions">
        <div ref="projectPicker" class="home-project-picker"><button ref="projectTrigger" type="button" class="home-project-trigger" data-testid="home-project-trigger" role="combobox" aria-label="按项目筛选" aria-haspopup="listbox" :aria-expanded="projectOpen" aria-controls="home-project-options" :aria-activedescendant="projectOpen ? `home-project-option-${projectIndex}` : undefined" @click="projectOpen ? closeProjects() : openProjects()" @keydown="projectKeydown" @blur="closeProjects"><span>{{ projectLabel(project) === '未关联项目' ? '全部项目' : projectLabel(project) }}</span><svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor"><path d="m7 10 5 5 5-5" /></svg></button><div v-if="projectOpen" id="home-project-options" class="home-project-options" role="listbox" aria-label="项目范围"><button v-for="(option, index) in projectOptions" :id="`home-project-option-${index}`" :key="option.value" type="button" class="home-project-option" :class="{ 'is-active': projectIndex === index }" role="option" :aria-selected="project === option.value" tabindex="-1" @pointerdown.prevent @click="selectProject(option.value)"><span>{{ option.label }}</span><span aria-hidden="true" class="home-project-check">✓</span></button></div></div>
        <button class="home-btn" @click="emit('navigate', { navKey: 'overview' })">项目概览 <ArrowRight aria-hidden="true" /></button>
      </div>
    </header>
    <div v-if="props.status === 'loading'" class="home-state-panel" data-home-state="loading" role="status" aria-live="polite" aria-busy="true">
      <strong>正在加载工作台数据</strong>
      <span>正在读取当前工作空间的运行与保存记录。</span>
    </div>
    <div v-else-if="props.status === 'unavailable'" class="home-state-panel" data-home-state="unavailable" role="status" aria-live="polite">
      <strong>工作台数据暂不可用</strong>
      <span>{{ props.errorMessage || '当前未接入可验证的工作台数据源，不显示推测的任务或统计。' }}</span>
      <button type="button" class="home-btn" data-testid="home-retry" @click="emit('retry')">重试加载</button>
    </div>
    <div v-else-if="props.status === 'forbidden'" class="home-state-panel" data-home-state="forbidden" role="status" aria-live="polite">
      <strong>没有权限查看工作台数据</strong>
      <span>请切换到有权访问当前项目的账号或联系管理员。</span>
    </div>
    <div v-else-if="props.status === 'error'" class="home-state-panel is-error" data-home-state="error" role="alert" aria-live="assertive">
      <strong>工作台加载失败</strong>
      <span>{{ props.errorMessage || '未能读取当前工作空间数据。' }}</span>
      <button type="button" class="home-btn" data-testid="home-retry" @click="emit('retry')">重试加载</button>
    </div>
    <div v-else-if="props.status === 'empty'" class="home-state-panel" data-home-state="empty" role="status" aria-live="polite">
      <strong>当前工作台暂无数据</strong>
      <span>完成一次执行或保存一条工作记录后，这里会显示可继续处理的内容。</span>
    </div>
    <div v-else class="home-layout" :class="{ 'is-empty': !primary }">
      <template v-for="section in primary ? ['runs', 'recent', 'pending'] : ['recent', 'runs', 'pending']" :key="section">
        <section v-if="section === 'runs'" id="home-runs-panel" :ref="(el) => setSection('runs', el)" class="home-runs" data-section="runs" tabindex="-1" aria-labelledby="home-runs-heading">
          <div class="home-section-head"><div class="home-section-title"><h2 id="home-runs-heading">进行中的任务</h2><span class="home-count home-run-count">{{ activeRuns.length }}</span></div><span class="home-caption">当前执行状态</span></div>
          <div v-if="primary" class="home-run-composition">
            <article class="home-run-feature">
              <div class="home-feature-top"><span class="home-state-label"><i />{{ labels[primary.state] }} · {{ primary.kind === 'web' ? 'Web' : 'API' }} 自动化</span></div>
              <h3>{{ primary.name }}</h3>
              <p class="home-feature-sub">{{ projectLabel(primary.project) }}<template v-if="primary.environmentName"> / {{ primary.environmentName }}</template></p>
              <p v-if="primary.state === 'waiting_user'" class="home-waiting-context">{{ primary.stage || '需要人工处理，尚未完成测试。' }}</p>
              <div class="home-progress-summary"><span><span>{{ primary.stage || (primary.state === 'running' ? '执行测试用例' : labels[primary.state]) }}</span><span v-if="percent !== undefined" class="home-progress-fraction">已完成 {{ primary.completed }} / {{ primary.total }} 项</span></span><strong v-if="percent !== undefined" class="home-progress-percentage">{{ percent }}<span>%</span></strong></div>
              <div v-if="percent !== undefined" class="home-progress" role="progressbar" aria-label="已完成用例数" :aria-valuenow="primary.completed" aria-valuemin="0" :aria-valuemax="primary.total"><span :style="{ width: `${percent}%` }" /></div>
              <div class="home-feature-footer"><p>任务尚未结束，当前结果不代表最终结论。</p><button class="home-btn" data-testid="home-open-primary-run" @click="emit('navigate', primary.target)">查看执行详情 <ArrowRight aria-hidden="true" /></button></div>
            </article>
            <div class="home-run-side">
              <article class="home-small-card">
                <template v-if="secondary.length"><div class="home-small-head"><span>{{ secondary[0]!.kind === 'web' ? 'Web' : 'API' }} 自动化</span><Play aria-hidden="true" /></div><h3>{{ secondary[0]!.name }}</h3><p>{{ projectLabel(secondary[0]!.project) }}<br>{{ secondary[0]!.stage || labels[secondary[0]!.state] }}</p><div class="home-small-bottom"><span class="home-badge">{{ labels[secondary[0]!.state] }}</span><button class="home-text-btn" @click="emit('navigate', secondary[0]!.target)">详情 <ArrowRight aria-hidden="true" /></button></div></template>
                <template v-else><div class="home-small-head">其他进行中的任务</div><h3>暂无其他任务</h3><p>只显示当前工作空间的真实执行状态。</p></template>
              </article>
              <article class="home-small-card home-result-summary"><div class="home-small-head"><span>主任务当前结果</span><ChartNoAxesCombined aria-hidden="true" /></div><div class="home-result-grid" :class="{ 'has-three': primary.passed !== undefined && primary.failed !== undefined && primary.error !== undefined }"><div v-if="primary.passed !== undefined" data-result><strong>{{ primary.passed }}</strong><span>已通过</span></div><div v-if="primary.failed !== undefined" data-result><strong class="is-bad">{{ primary.failed }}</strong><span>断言失败</span></div><div v-if="primary.error !== undefined" data-result><strong class="is-bad">{{ primary.error }}</strong><span>执行错误</span></div></div><p v-if="primary.passed === undefined && primary.failed === undefined && primary.error === undefined">当前执行源未提供结果计数。</p><p class="home-result-note">非最终结论</p></article>
            </div>
          </div>
          <div v-else class="home-empty"><h3>当前没有进行中的任务</h3><p>有执行任务时，这里显示真实运行进度。</p></div>
          <div v-if="secondary.length > 1" class="home-additional-runs" aria-label="更多进行中的任务"><button v-for="run in secondary.slice(1)" :key="run.id" type="button" class="home-text-btn" :data-additional-run="run.id" @click="emit('navigate', run.target)">{{ run.name }} · {{ labels[run.state] }} <ArrowRight aria-hidden="true" /></button></div>
        </section>
        <section v-else-if="section === 'recent'" id="home-recent-panel" :ref="(el) => setSection('recent', el)" class="home-recent" data-section="recent" tabindex="-1" aria-labelledby="home-recent-heading">
          <div class="home-section-head"><div class="home-section-title"><h2 id="home-recent-heading">继续上次工作</h2><span class="home-count home-recent-count">{{ recent.length }}</span></div><div class="home-recent-filters" role="group" aria-label="最近保存类型"><button v-for="filter in filters" :key="filter.value" type="button" :data-work-filter="filter.value" :aria-pressed="workFilter === filter.value" @click="workFilter = filter.value">{{ filter.label }}</button></div></div>
          <p class="home-caption">最近保存 · 尚无最近访问记录，以下按保存时间排列。</p>
          <table v-if="recent.length" aria-label="最近保存的工作"><thead><tr><th>名称 / 保存位置</th><th>类型</th><th>状态</th><th>保存时间</th></tr></thead><tbody><tr v-for="item in recent" :key="item.id" :data-work-id="item.id"><td><button type="button" class="home-item-name" @click="emit('navigate', item.target)">{{ item.name }}</button><span class="home-item-sub">{{ projectLabel(item.project) }}<template v-if="item.context"> · {{ item.context }}</template></span></td><td><span class="home-type">{{ workKinds[item.kind] }}</span></td><td><span v-if="item.statusLabel" class="home-badge" :class="`is-${item.tone || 'neutral'}`">{{ item.statusLabel }}</span><span v-else class="home-muted">—</span></td><td class="home-muted"><time :datetime="item.savedAt">{{ displayTime(item.savedAt) }}</time></td></tr></tbody></table>
          <template v-else><p class="home-empty-message">{{ workFilter === 'all' ? '暂无已保存的工作。保存用例或生成报告后，可从这里继续。' : '当前项目下暂无该类型的已保存记录，可切换“全部”。' }}</p><button class="home-text-btn" data-testid="home-open-cases" @click="emit('navigate', { navKey: 'cases' })">前往用例库 <ArrowRight aria-hidden="true" /></button></template>
        </section>
        <section v-else id="home-pending-panel" :ref="(el) => setSection('pending', el)" class="home-pending" data-section="pending" tabindex="-1" aria-labelledby="home-pending-heading">
          <div class="home-section-head"><div class="home-section-title"><h2 id="home-pending-heading">需要处理</h2><span class="home-count home-pending-count">{{ pending.length }}</span></div></div>
          <div v-if="pending.length" class="home-pending-body"><p class="home-pending-intro">当前工作空间 · 默认显示三条</p><div class="home-pending-list" role="region" aria-label="待处理事项，可滚动查看全部" tabindex="0"><article v-for="item in pending" :key="item.id" class="home-todo" :data-pending-id="item.id"><div class="home-todo-meta"><span class="home-badge" :class="`is-${item.tone || 'neutral'}`">{{ item.pending!.label }}</span><small>{{ projectLabel(item.project) }}</small></div><h3>{{ item.name }}</h3><div class="home-todo-footer"><span class="home-todo-time">{{ displayTime(item.savedAt) }}</span><button class="home-text-btn" type="button" @click="emit('navigate', item.target)">{{ item.pending!.actionLabel }} <ArrowRight aria-hidden="true" /></button></div></article></div><p class="home-pending-more">共 {{ pending.length }} 条 · {{ pending.length > 3 ? `向下滚动查看其余 ${pending.length - 3} 条` : '已显示全部' }}</p></div>
          <div v-else class="home-empty"><h3>当前没有待处理事项</h3><p>仅显示当前工作空间内有明确待处理状态的记录。</p></div>
        </section>
      </template>
    </div>
    <footer class="home-foot"><span>当前工作空间 · 不代表个人分配或最近访问记录。</span><button class="home-text-btn" data-testid="home-open-chain" @click="emit('navigate', { navKey: 'assistant' })">AI 助手 <ArrowRight aria-hidden="true" /></button></footer>
  </section>
</template>

<style src="../../styles/home-workbench.css"></style>
