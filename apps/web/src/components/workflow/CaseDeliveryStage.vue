<script setup lang="ts">
import { ref } from 'vue'
import { Archive, GitBranch, Play } from '@lucide/vue'
import type { CaseDesignDeliveryMode, CaseDesignDeliveryView } from '../../domain/caseDesignDelivery'

defineProps<{
  mode: CaseDesignDeliveryMode
  automationKind: 'api' | 'web'
  executeNow: boolean
  generatedCount: number
  selectedAutomationCount: number
  environmentName: string
  preflight: CaseDesignDeliveryView
}>()

const emit = defineEmits<{
  'update:mode': [mode: CaseDesignDeliveryMode]
  'update:executeNow': [value: boolean]
  configureEnvironment: []
}>()

// 单选卡片键盘导航:方向键换选(roving 焦点跟随),非选中卡片移出 Tab 序。
const libraryRef = ref<HTMLButtonElement>()
const automationRef = ref<HTMLButtonElement>()
const modes: CaseDesignDeliveryMode[] = ['library', 'automation']

const onRadioKeydown = (event: KeyboardEvent, index: number): void => {
  const { key } = event
  let nextIndex = -1
  if (key === 'ArrowDown' || key === 'ArrowRight') nextIndex = (index + 1) % modes.length
  else if (key === 'ArrowUp' || key === 'ArrowLeft') nextIndex = (index - 1 + modes.length) % modes.length
  else return
  event.preventDefault()
  const nextMode = modes[nextIndex]
  emit('update:mode', nextMode)
  ;(nextMode === 'library' ? libraryRef.value : automationRef.value)?.focus()
}
</script>

<template>
  <section class="studio-delivery-stage" data-testid="case-delivery-stage">
    <header>
      <div>
        <strong>交付本次测试设计</strong>
        <span>{{ generatedCount }} 条生成用例</span>
      </div>
    </header>

    <div class="studio-delivery-options" role="radiogroup" aria-label="交付方式">
      <button
        type="button"
        role="radio"
        ref="libraryRef"
        data-testid="delivery-library"
        :class="{ 'is-active': mode === 'library' }"
        :aria-checked="mode === 'library'"
        :tabindex="mode === 'library' ? 0 : -1"
        @click="emit('update:mode', 'library')"
        @keydown="onRadioKeydown($event, 0)"
      >
        <span class="studio-delivery-option__icon" aria-hidden="true">
          <Archive :size="18" />
        </span>
        <strong>保存到用例库</strong>
        <span>保留来源、AI 生成和评审记录</span>
      </button>
      <button
        type="button"
        role="radio"
        ref="automationRef"
        data-testid="delivery-automation"
        :class="{ 'is-active': mode === 'automation' }"
        :aria-checked="mode === 'automation'"
        :tabindex="mode === 'automation' ? 0 : -1"
        @click="emit('update:mode', 'automation')"
        @keydown="onRadioKeydown($event, 1)"
      >
        <span class="studio-delivery-option__icon" aria-hidden="true">
          <GitBranch :size="18" />
        </span>
        <strong>转为 {{ automationKind === 'web' ? 'Web' : 'API' }} 自动化</strong>
        <span>保存后生成可执行用例</span>
      </button>
    </div>

    <div v-if="mode === 'automation'" class="studio-delivery-execution">
      <label>
        <input
          type="checkbox"
          :checked="executeNow"
          @change="emit('update:executeNow', ($event.target as HTMLInputElement).checked)"
        />
        <span><Play :size="15" /> 转换完成后立即执行</span>
      </label>
      <div>
        <span>已选择 {{ selectedAutomationCount }} 条转自动化</span>
        <span>运行环境</span>
        <strong>{{ environmentName || '未选择' }}</strong>
        <el-button text type="primary" @click="emit('configureEnvironment')">配置环境</el-button>
      </div>
    </div>

    <ul v-if="preflight.warnings.length" class="studio-delivery-warnings">
      <li v-for="warning in preflight.warnings" :key="warning">{{ warning }}</li>
    </ul>
    <section
      v-if="preflight.riskItems.length"
      class="studio-delivery-risk-summary"
      aria-labelledby="delivery-risk-summary-title"
    >
      <strong id="delivery-risk-summary-title">交付风险明细</strong>
      <ol>
        <li v-for="risk in preflight.riskItems" :key="risk.id">
          <el-tag
            size="small"
            :type="risk.priority === 'P0' || risk.priority === 'P1' ? 'danger' : 'warning'"
          >
            {{ risk.kind === 'coverage_gap' ? '覆盖缺口' : risk.priority }}
          </el-tag>
          <span>{{ risk.message }}</span>
        </li>
      </ol>
    </section>
    <ul v-if="preflight.blockers.length" class="studio-delivery-blockers" role="alert">
      <li v-for="blocker in preflight.blockers" :key="blocker">{{ blocker }}</li>
    </ul>

    <p class="studio-delivery-stage__hint">
      使用页面顶部的“{{ preflight.primaryLabel }}”完成交付。
    </p>
  </section>
</template>
