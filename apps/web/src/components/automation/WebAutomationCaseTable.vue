<script setup lang="ts">
import { computed } from 'vue'
import { TriangleAlert, Bug, CircleCheck, Clock3, ScrollText, Trash2, CircleX } from '@lucide/vue'
import type { WebAutomationRunStatus } from '../../domain/webAutomation'
import type { SavedWebAutomationRunCase } from '../../domain/webAutomationRepository'

export type WebCaseStatusFilter = 'ALL' | WebAutomationRunStatus | 'pending'

const props = defineProps<{
  cases: SavedWebAutomationRunCase[]
  selectedIds: string[]
  project: string
  projects: Array<{ value: string; label: string; count: number }>
  module: string
  modules: Array<{ value: string; label: string; count: number }>
  source: 'ALL' | 'design' | 'default'
  status: WebCaseStatusFilter
  keyword: string
  resultStatusById: Record<string, WebAutomationRunStatus | 'pending'>
  emptyKind: 'none' | 'no-cases' | 'filtered'
}>()

const emit = defineEmits<{
  toggleCase: [testCase: SavedWebAutomationRunCase, checked: boolean]
  selectFiltered: []
  clearSelected: []
  updateProject: [value: string]
  updateModule: [value: string]
  updateSource: [value: 'ALL' | 'design' | 'default']
  updateStatus: [value: WebCaseStatusFilter]
  updateKeyword: [value: string]
  clearFilters: []
  generateCases: []
  openLog: [testCase: SavedWebAutomationRunCase]
  transferDefect: [testCase: SavedWebAutomationRunCase]
  deleteCase: [testCase: SavedWebAutomationRunCase]
}>()

const selectedIdSet = computed(() => new Set(props.selectedIds))
const displayCases = computed(() => {
  const seen = new Set<string>()
  return props.cases.filter(({ id }) => {
    if (seen.has(id)) return false
    seen.add(id)
    return true
  })
})
const resultStatus = (testCase: SavedWebAutomationRunCase) => props.resultStatusById[testCase.id] ?? 'pending'
const statusMeta = {
  pending: { label: '未执行', icon: Clock3 },
  passed: { label: '通过', icon: CircleCheck },
  failed: { label: '失败', icon: CircleX },
  error: { label: '错误', icon: TriangleAlert }
} as const
const onToggle = (testCase: SavedWebAutomationRunCase, event: Event) =>
  emit('toggleCase', testCase, (event.target as HTMLInputElement).checked)
</script>

<template>
  <section class="web-automation-case-table" data-testid="web-automation-case-table">
    <header class="web-automation-case-table__toolbar" data-testid="web-case-table-toolbar">
      <div class="web-automation-case-table__filters" data-testid="web-case-table-filters">
        <el-select :model-value="project" aria-label="Web 自动化项目" popper-class="web-automation-popper" @update:model-value="emit('updateProject', $event)">
          <el-option
            v-for="option in projects"
            :key="option.value"
            :value="option.value"
            :label="option.label"
          />
        </el-select>
        <el-select :model-value="module" aria-label="Web 自动化模块" popper-class="web-automation-popper" @update:model-value="emit('updateModule', $event)">
          <el-option
            v-for="option in modules"
            :key="option.value"
            :value="option.value"
            :label="option.label"
          />
        </el-select>
        <el-select ref="statusSelect" :model-value="status" aria-label="Web 用例状态" popper-class="web-automation-popper" @update:model-value="emit('updateStatus', $event)">
          <el-option label="全部状态" value="ALL" /><el-option label="未执行" value="pending" /><el-option label="通过" value="passed" /><el-option label="失败" value="failed" /><el-option label="错误" value="error" />
        </el-select>
        <el-input
          class="web-automation-case-table__keyword"
          :model-value="keyword"
          aria-label="搜索 Web 用例名称、页面或 URL"
          placeholder="搜索用例名称、页面或 URL"
          clearable
          @update:model-value="emit('updateKeyword', $event)"
        />
      </div>
      <div class="web-automation-case-table__commands" data-testid="web-case-table-commands">
        <button type="button" data-testid="select-filtered-web-cases" :disabled="displayCases.length === 0" @click="emit('selectFiltered')">全选当前结果</button>
        <button type="button" data-testid="clear-web-case-selection" :disabled="selectedIds.length === 0" @click="emit('clearSelected')">清空选择</button>
      </div>
    </header>

    <div v-if="emptyKind !== 'none'" class="web-automation-case-table__empty">
      <div class="web-automation-case-table__empty-card">
        <strong>{{ emptyKind === 'no-cases' ? '暂无可执行 Web 用例' : '当前筛选没有匹配用例' }}</strong>
        <span>{{ emptyKind === 'no-cases' ? '可以先创建一批自动化用例，再选择范围执行。' : '调整项目、模块、状态或关键词后继续查看结果。' }}</span>
        <button v-if="emptyKind === 'no-cases'" type="button" class="is-primary" data-testid="generate-web-cases" @click="emit('generateCases')">从用例库生成</button>
        <button v-else type="button" data-testid="clear-web-case-filters" @click="emit('clearFilters')">清除筛选</button>
      </div>
    </div>
    <div v-else class="web-automation-case-table__scroll">
      <table>
        <thead><tr><th>选择</th><th>用例</th><th>页面</th><th>浏览器 / 分辨率</th><th>执行状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="testCase in displayCases" :key="testCase.id">
            <td><input type="checkbox" :aria-label="`选择 Web 用例 ${testCase.id}`" :checked="selectedIdSet.has(testCase.id)" @change="onToggle(testCase, $event)"></td>
            <td><strong>{{ testCase.id }}</strong><span>{{ testCase.name }}</span></td>
            <td><strong>{{ testCase.pageName }}</strong><span>{{ testCase.startUrl }}</span></td>
            <td><strong>{{ testCase.browser }}</strong><span>{{ testCase.viewport }}</span></td>
            <td>
              <span class="web-automation-case-table__status" :class="`is-${resultStatus(testCase)}`">
                <component :is="statusMeta[resultStatus(testCase)].icon" :size="15" aria-hidden="true" />{{ statusMeta[resultStatus(testCase)].label }}
              </span>
            </td>
            <td><div class="web-automation-case-table__actions">
              <el-tooltip content="查看日志"><button type="button" :aria-label="`查看 Web 用例 ${testCase.id} 日志`" @click="emit('openLog', testCase)"><ScrollText :size="16" /></button></el-tooltip>
              <el-tooltip v-if="resultStatus(testCase) === 'failed' || resultStatus(testCase) === 'error'" content="转为缺陷"><button type="button" :aria-label="`将 Web 用例 ${testCase.id} 转为缺陷`" @click="emit('transferDefect', testCase)"><Bug :size="16" /></button></el-tooltip>
              <el-tooltip content="删除用例"><button type="button" :aria-label="`删除 Web 用例 ${testCase.id}`" @click="emit('deleteCase', testCase)"><Trash2 :size="16" /></button></el-tooltip>
            </div></td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.web-automation-case-table {
  container-type: inline-size;
  display: grid;
  grid-template-rows: auto minmax(180px, 1fr);
  min-width: 0;
  min-height: 260px;
  overflow: hidden;
  color: var(--text-regular);
  background: var(--surface-raised);
  border: 1px solid var(--border-regular);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-soft);
}
.web-automation-case-table__toolbar { display: flex; min-height: 58px; flex-wrap: wrap; align-items: center; gap: 8px 10px; overflow: visible; padding: 8px 14px; background: var(--surface-raised); border-bottom: 1px solid var(--border-soft); font-size: 12px; }
.web-automation-case-table__filters { display: grid; flex: 1 1 520px; min-width: min(100%, 360px); max-width: 760px; grid-template-columns: 120px 120px 120px minmax(170px, 1fr); gap: 7px; }
.web-automation-case-table__filters :deep(.el-select__wrapper), .web-automation-case-table__filters :deep(.el-input__wrapper) { min-height: 30px; padding-inline: 9px; border-radius: var(--radius-md); background: rgba(255, 255, 255, 0.92); box-shadow: 0 0 0 1px rgba(183, 210, 202, 0.78) inset; }
.web-automation-case-table__filters :deep(.el-select__selected-item), .web-automation-case-table__filters :deep(.el-input__inner) { font-size: 12px; line-height: 1.2; }
.web-automation-case-table__keyword :deep(.el-input__wrapper.is-focus),
.web-automation-case-table__keyword :deep(.el-input__wrapper:hover) {
  box-shadow:
    0 0 0 1px rgba(111, 170, 159, 0.76) inset,
    0 8px 20px rgba(15, 23, 42, 0.07);
}
.web-automation-case-table__keyword :deep(.el-input__inner:focus-visible) { outline: none; }
.web-automation-case-table__keyword :deep(.el-input__inner::placeholder) { color: var(--text-placeholder); }
.web-automation-case-table__commands { display: flex; flex: 0 0 auto; gap: 6px; white-space: nowrap; }
.web-automation-case-table__commands button, .web-automation-case-table__empty button { min-height: 30px; padding: 0 11px; color: var(--text-regular); white-space: nowrap; background: rgba(255, 255, 255, 0.84); border: 1px solid rgba(183, 210, 202, 0.82); border-radius: 11px; box-shadow: none; cursor: pointer; font-size: 11px; line-height: 1.2; }
.web-automation-case-table__commands button { flex: 0 0 auto; }
.web-automation-case-table__commands button:not(:disabled):hover, .web-automation-case-table__empty button:not(.is-primary):hover { color: var(--brand-primary); background: var(--surface-raised); border-color: rgba(124, 190, 178, 0.8); box-shadow: 0 8px 20px rgba(15, 118, 110, 0.07); }
.web-automation-case-table__commands button:disabled { color: var(--text-placeholder); cursor: not-allowed; }
.web-automation-case-table__empty { display: grid; place-items: center; min-height: 180px; margin: 12px; background: linear-gradient(180deg, rgba(249, 253, 251, 0.84), rgba(255, 255, 255, 0.98)); border: 1px dashed rgba(163, 213, 206, 0.82); border-radius: var(--radius-lg); }
.web-automation-case-table__empty-card { display: grid; justify-items: center; gap: 8px; max-width: 340px; padding: 20px 22px; text-align: center; background: rgba(255, 255, 255, 0.82); border: 1px solid rgba(214, 228, 224, 0.76); border-radius: var(--radius-lg); box-shadow: var(--shadow-soft); }
.web-automation-case-table__empty-card strong { color: var(--text-primary); font-size: var(--font-size-sm); font-weight: 600; line-height: 1.45; }
.web-automation-case-table__empty-card span { color: var(--text-secondary); font-size: var(--font-size-xs); line-height: 1.55; }
.web-automation-case-table__empty button.is-primary { color: var(--text-inverse); background: linear-gradient(180deg, var(--brand-primary), var(--brand-primary-active)); border-color: var(--brand-primary); box-shadow: 0 10px 22px rgba(15, 118, 110, 0.18); }
.web-automation-case-table__scroll { min-width: 0; min-height: 0; margin: 0 12px 12px; overflow: auto; background: var(--surface-raised); border: 1px solid rgba(210, 226, 221, 0.86); border-radius: var(--radius-lg); box-shadow: 0 1px 0 rgba(255, 255, 255, 0.8) inset; }
table { width: 100%; min-width: 920px; border-collapse: collapse; table-layout: fixed; font-size: var(--font-size-sm); }
th, td { padding: 7px 10px; text-align: left; vertical-align: middle; border-bottom: 1px solid rgba(223, 234, 231, 0.9); }
th { color: var(--text-secondary); font-size: 12px; font-weight: 600; background: linear-gradient(180deg, rgba(244, 250, 248, 0.96), rgba(249, 253, 251, 0.98)); }
th:first-child { width: 58px; } th:nth-child(2) { width: 190px; } th:nth-child(3) { width: 250px; } th:nth-child(4) { width: 150px; } th:nth-child(5) { width: 110px; } th:last-child { width: 116px; }
td strong, td span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
td strong { color: var(--text-primary); font-size: 12px; font-weight: 600; line-height: 16px; } td > span { margin-top: 2px; color: var(--text-secondary); font-size: 12px; line-height: 15px; }
.web-automation-case-table__status { display: inline-flex; align-items: center; gap: 5px; }
.web-automation-case-table__status.is-pending { color: var(--text-secondary); } .web-automation-case-table__status.is-passed { color: var(--status-success); } .web-automation-case-table__status.is-failed, .web-automation-case-table__status.is-error { color: var(--status-danger); }
.web-automation-case-table__actions { display: flex; gap: 3px; }
.web-automation-case-table__actions button { display: inline-grid; width: 28px; height: 28px; place-items: center; padding: 0; color: var(--text-secondary); background: transparent; border: 0; border-radius: 8px; cursor: pointer; }
.web-automation-case-table__actions button:hover { color: var(--brand-primary); background: var(--brand-primary-subtle); box-shadow: 0 0 0 1px var(--brand-primary-border) inset; }
input[type='checkbox'] {
  appearance: none;
  display: inline-grid;
  width: 16px;
  height: 16px;
  place-content: center;
  margin: 0;
  background: var(--surface-raised);
  border: 1px solid var(--border-strong);
  border-radius: 4px;
  cursor: pointer;
  transition: background-color var(--motion-fast) var(--ease-standard), border-color var(--motion-fast) var(--ease-standard), box-shadow var(--motion-fast) var(--ease-standard);
}
input[type='checkbox']::after {
  width: 4px;
  height: 8px;
  border: solid var(--text-inverse);
  border-width: 0 2px 2px 0;
  content: '';
  transform: rotate(45deg) scale(0);
  transform-origin: center;
  transition: transform var(--motion-fast) var(--ease-standard);
}
input[type='checkbox']:hover { border-color: var(--brand-primary); box-shadow: 0 0 0 3px var(--brand-primary-subtle); }
input[type='checkbox']:checked { background: var(--brand-primary); border-color: var(--brand-primary); }
input[type='checkbox']:checked::after { transform: rotate(45deg) scale(1); }
button:focus-visible, input[type='checkbox']:focus-visible { outline: 3px solid var(--brand-primary-focus); outline-offset: 2px; }
@container (max-width: 920px) { .web-automation-case-table__toolbar { align-items: stretch; } .web-automation-case-table__filters { flex-basis: 100%; grid-template-columns: 120px 120px minmax(120px, 1fr); } .web-automation-case-table__filters :deep(.web-automation-case-table__keyword) { grid-column: 1 / -1; } }
@container (max-width: 620px) { .web-automation-case-table__filters { grid-template-columns: 1fr; } .web-automation-case-table__filters :deep(.el-input) { grid-column: 1 / -1; } }
@media (max-width: 1000px) { .web-automation-case-table__toolbar { align-items: stretch; } .web-automation-case-table__filters { flex: 1 1 100%; grid-template-columns: 120px 120px minmax(120px, 1fr); } .web-automation-case-table__filters :deep(.web-automation-case-table__keyword) { grid-column: 1 / -1; } }
</style>
