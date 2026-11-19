<script setup lang="ts">
import { computed, type Component } from 'vue'
import { BarChart3, CalendarClock, FolderKanban, Users } from '@lucide/vue'

export type OverviewStatus = 'unavailable' | 'loading' | 'empty' | 'forbidden' | 'error' | 'ready'

export interface OverviewCard {
  id: string
  title: string
  description: string
  dependencies: string[]
  value?: string
}

/** 规划中的模块:只声明范围与状态,不编造统计数字。 */
const plannedBlocks: OverviewCard[] = [
  {
    id: 'projects',
    title: '项目健康度',
    description: '按项目汇总用例规模、执行通过率与阻塞项;需要接入执行报告与缺陷的真实聚合接口。',

    dependencies: ['执行报告聚合', '缺陷统计']
  },
  {
    id: 'team',
    title: '团队负载',
    description: '按成员展示负责用例、执行任务与待回归缺陷;依赖成员与任务的归属关系。',

    dependencies: ['成员归属', '任务分配']
  },
  {
    id: 'trend',
    title: '质量趋势',
    description: '按周展示通过率与逃逸缺陷趋势;需要连续版本的执行数据。',

    dependencies: ['历史执行数据']
  },
  {
    id: 'plan',
    title: '版本节奏',
    description: '展示当前版本的提测、回归与发布节点;依赖版本排期数据源。',

    dependencies: ['版本排期']
  }
]

const props = withDefaults(defineProps<{
  status?: OverviewStatus
  cards?: readonly OverviewCard[]
  errorMessage?: string
  availabilityChecked?: boolean
}>(), {
  status: 'unavailable',
  cards: () => [],
  errorMessage: '概览数据暂时不可用,请稍后重试。',
  availabilityChecked: false
})

const emit = defineEmits<{ retry: [] }>()

const cards = computed(() => props.cards.length > 0 ? props.cards : plannedBlocks)
const iconById: Record<string, Component> = {
  projects: FolderKanban,
  team: Users,
  trend: BarChart3,
  plan: CalendarClock
}
const statusLabel: Record<OverviewStatus, string> = {
  unavailable: '未接入数据',
  loading: '正在加载',
  empty: '暂无数据',
  forbidden: '无权查看',
  error: '加载失败',
  ready: '数据已接入'
}
</script>

<template>
  <section class="workbench overview-page" aria-label="团队 / 项目概览" :data-overview-state="props.status">
    <div class="workbench__content">
      <div class="workbench__pane">
        <header class="settings-page-head overview-head">
          <div>
            <span class="settings-page-kicker">团队 / 项目概览</span>
            <h1>团队 / 项目概览</h1>
            <p>用于跨项目的质量与进度汇总。数据状态会明确区分,不以占位数字代替真实统计。</p>
          </div>
          <div class="toolbar">
            <span class="overview-status" :data-status="props.status">{{ statusLabel[props.status] }}</span>
          </div>
        </header>

        <section class="library-workspace overview-body">
          <div v-if="props.status === 'unavailable'" class="overview-state-panel" data-testid="overview-unavailable" role="status" aria-live="polite">
            <strong>未接入真实统计数据</strong>
            <span>当前只保留页面结构和数据依赖说明,不会显示来源不明的项目、团队或质量数字。</span>
            <span v-if="props.availabilityChecked" data-testid="overview-availability-checked">项目访问检查已完成；统计数据源仍未接入，不代表团队或质量统计已可用。</span>
            <button type="button" class="overview-state-panel__action" data-testid="overview-retry" @click="emit('retry')">重新检查数据可用性</button>
          </div>
          <div v-else-if="props.status === 'loading'" class="overview-state-panel" data-testid="overview-loading" role="status" aria-live="polite" aria-busy="true">
            <strong>正在检查概览数据可用性</strong>
            <span>正在检查当前工作空间的项目访问；不代表已接入团队或质量汇总。</span>
          </div>
          <div v-else-if="props.status === 'empty'" class="overview-state-panel" data-testid="overview-empty" role="status" aria-live="polite">
            <strong>暂无可用概览数据</strong>
            <span>当前工作空间还没有满足概览口径的执行、缺陷或版本记录。</span>
          </div>
          <div v-else-if="props.status === 'forbidden'" class="overview-state-panel" data-testid="overview-forbidden" role="status" aria-live="polite">
            <strong>没有权限查看团队 / 项目概览</strong>
            <span>项目访问被拒绝，无法继续检查。请联系管理员确认访问权限；此结果不代表已检查团队或质量统计权限。</span>
            <button type="button" class="overview-state-panel__action" data-testid="overview-retry" @click="emit('retry')">重新检查权限</button>
          </div>
          <div v-else-if="props.status === 'error'" class="overview-state-panel is-error" data-testid="overview-error" role="alert" aria-live="assertive">
            <strong>概览数据加载失败</strong>
            <span>{{ props.errorMessage }}</span>
            <button type="button" class="overview-state-panel__action" data-testid="overview-retry" @click="emit('retry')">重试加载</button>
          </div>

          <div class="overview-grid">
            <article v-for="block in cards" :key="block.id" class="overview-card" :data-card-state="props.status">
              <component :is="iconById[block.id] || FolderKanban" :size="18" aria-hidden="true" class="overview-card__icon" />
              <h2>{{ block.title }}</h2>
              <strong v-if="props.status === 'ready' && block.value" class="overview-card__value" data-testid="overview-metric-value">{{ block.value }}</strong>
              <p>{{ block.description }}</p>
              <div class="overview-card__meta">
                <span class="overview-tag">{{ props.status === 'ready' ? (block.value ? '已接入' : '暂无数据') : '规划中' }}</span>
                <span class="overview-deps">依赖:{{ block.dependencies.join(' · ') }}</span>
              </div>
            </article>
          </div>
        </section>
      </div>
    </div>
  </section>
</template>
