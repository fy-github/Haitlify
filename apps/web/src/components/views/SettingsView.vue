<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useTemplateConfig } from '../../ui/useTemplateConfig'
import type { CaseKind, CaseTypeDefinition, FieldType } from '../../domain/caseTemplates'

const props = withDefaults(defineProps<{
  /** 设计稿把「模板字段」和「用例类型」拆成两个独立页面 */
  section?: 'fields' | 'types'
}>(), { section: 'fields' })

const emit = defineEmits<{
  openSection: [section: 'fields' | 'types']
}>()

const {
  templateConfig,
  selectedTemplate,
  selectedTemplateFields,
  selectedTemplateId,
  allCaseTypes,
  fieldDraft,
  caseTypeDraft,
  openFieldDraft,
  cancelFieldDraft,
  submitFieldDraft,
  openCaseTypeDraft,
  cancelCaseTypeDraft,
  submitCaseTypeDraft,
  createCaseTemplate,
  resetConfig,
  renameCaseTemplate,
  removeCaseTemplate,
  moveField,
  patchField,
  removeField,
  patchCaseType,
  removeCaseType,
  saveStatusFor,
  retrySave,
  discardUnsaved
} = useTemplateConfig()

const kindLabels: Record<CaseKind | 'all', string> = {
  all: '全部类别',
  interface: '接口用例',
  web_automation: 'Web 用例',
  app_automation: 'APP 用例',
  ai_test: 'AI 用例',
  functional: '功能用例',
  performance: '性能用例'
}

const typeLabels: Record<FieldType, string> = {
  text: '文本',
  textarea: '多行文本',
  select: '选择',
  datetime: '时间',
  tags: '标签',
  json: 'JSON'
}

const fieldTypeOptions = (Object.keys(typeLabels) as FieldType[]).map((value) => ({ value, label: typeLabels[value] }))
const scopeOptions = (Object.keys(kindLabels) as Array<CaseKind | 'all'>).map((value) => ({ value, label: kindLabels[value] }))

const fieldKeyword = ref('')
const templateKeyword = ref('')
const typeKeyword = ref('')
const typeScope = ref<CaseKind | 'all' | 'all-filter'>('all-filter')

const filteredFields = computed(() => {
  const keyword = fieldKeyword.value.trim().toLowerCase()
  if (!keyword) return selectedTemplateFields.value
  return selectedTemplateFields.value.filter((field) =>
    `${field.key} ${field.label}`.toLowerCase().includes(keyword)
  )
})

const fieldIndexOf = (key: string): number => selectedTemplateFields.value.findIndex((field) => field.key === key)

const filteredTemplates = computed(() => {
  const keyword = templateKeyword.value.trim().toLowerCase()
  if (!keyword) return templateConfig.value.caseTemplates
  return templateConfig.value.caseTemplates.filter((template) => template.name.toLowerCase().includes(keyword))
})

/** 用例类型覆盖全部类别(interface / web / app / ai / functional / performance / all)。 */
const filteredTypes = computed(() => {
  const keyword = typeKeyword.value.trim().toLowerCase()
  return allCaseTypes.value.filter((caseType) => {
    const scopeMatched = typeScope.value === 'all-filter' || caseType.appliesTo === typeScope.value
    const keywordMatched = !keyword || `${caseType.id} ${caseType.name}`.toLowerCase().includes(keyword)
    return scopeMatched && keywordMatched
  })
})

const fieldCellLabelOf = (key: string): string =>
  selectedTemplateFields.value.find((field) => field.key === key)?.label || key

const patchFieldType = (key: string, type: FieldType): void => patchField(key, { type })
const patchFieldLabel = (key: string, label: string, input?: HTMLInputElement): void => {
  const next = label.trim()
  // 空表头不写库,把输入框退回当前已保存值,避免界面与配置不一致
  if (!next) {
    if (input) input.value = fieldCellLabelOf(key)
    return
  }
  patchField(key, { label: next })
}
const patchFieldVisible = (key: string, visible: boolean): void => patchField(key, { visible })
const patchFieldExportable = (key: string, exportable: boolean): void => patchField(key, { exportable })
const patchFieldAiGenerated = (key: string, aiGenerated: boolean): void => patchField(key, { aiGenerated })
const patchFieldRequired = (key: string, required: boolean): void => patchField(key, { required })

const patchCaseTypeName = (id: string, name: string, input?: HTMLInputElement): void => {
  const next = name.trim()
  if (!next) {
    if (input) input.value = templateConfig.value.caseTypes.find((caseType) => caseType.id === id)?.name || id
    return
  }
  patchCaseType(id, { name: next })
}
const patchCaseTypeAppliesTo = (id: string, appliesTo: CaseTypeDefinition['appliesTo']): void => patchCaseType(id, { appliesTo })
const patchCaseTypeEnabled = (id: string, enabled: boolean): void => patchCaseType(id, { enabled })

const resetTypeFilter = (): void => {
  typeScope.value = 'all-filter'
  typeKeyword.value = ''
}

const selectTemplate = (templateId: string): void => {
  selectedTemplateId.value = templateId
  fieldKeyword.value = ''
}

const isFields = computed(() => props.section === 'fields')
const templateKindLabel = computed(() => kindLabels[selectedTemplate.value?.kind ?? 'interface'])

/** 设计稿的保存条:只报当前页面自己的状态,失败/降级时不假装保存成功。 */
const saveStatus = computed(() => saveStatusFor(props.section))
const saveStatusText = computed(() => {
  const status = saveStatus.value
  if (status.state === 'saving') return '正在自动保存…'
  if (status.state === 'failed') return `保存失败：${status.error} · 修改仅保留在当前界面`
  if (status.state === 'saved') return `已自动保存${status.savedAt ? ` · ${status.savedAt}` : ''}`
  return ''
})
const isSaveFailed = computed(() => saveStatus.value.state === 'failed')

// 抽屉的焦点:打开时进入第一个输入框,关闭后回到页头触发按钮
const fieldKeyInput = ref<HTMLInputElement | null>(null)
const typeIdInput = ref<HTMLInputElement | null>(null)
/** 页头标题:抽屉关闭且触发按钮随分节消失时的焦点落点。 */
const pageHeading = ref<HTMLElement | null>(null)

/** 抽屉是模态:Tab / Shift+Tab 在抽屉内循环,不把焦点漏到背后的表格。 */
const trapTab = (event: KeyboardEvent, container: HTMLElement | null): void => {
  if (!container) return
  const focusables = Array.from(
    container.querySelectorAll<HTMLElement>('button, input, select, textarea, a[href], [tabindex]:not([tabindex="-1"])')
  ).filter((element) => !element.hasAttribute('disabled'))
  if (!focusables.length) return
  const first = focusables[0]
  const last = focusables[focusables.length - 1]
  const active = document.activeElement as HTMLElement | null
  const inside = active !== null && container.contains(active)
  if (!event.shiftKey && (!inside || active === last)) {
    event.preventDefault()
    first.focus()
  } else if (event.shiftKey && (!inside || active === first)) {
    event.preventDefault()
    last.focus()
  }
}

const focusTrigger = (selector: string): void => {
  const trigger = document.querySelector<HTMLElement>(selector)
  trigger?.focus({ preventScroll: true })
}

/**
 * 分节切换:两个分节复用同一个组件实例,而抽屉挂在 `v-if/v-else` 分节之外。
 * 不主动收掉草稿,抽屉会被带到另一张页面上,在类型页确认就会把字段写进 interface。
 * 这里统一「取消草稿 + 把焦点交给新分节入口」。
 */
const sectionEntrySelector = (section: 'fields' | 'types'): string =>
  section === 'fields' ? '[data-action="add-field"]' : '[data-action="add-type"]'

watch(() => props.section, async (next, previous) => {
  if (next === previous) return
  const hadDraft = Boolean(fieldDraft.value || caseTypeDraft.value)
  cancelFieldDraft()
  cancelCaseTypeDraft()
  if (!hadDraft) return
  await nextTick()
  const entry = document.querySelector<HTMLElement>(sectionEntrySelector(next))
  // 抽屉的触发按钮随分节消失时要有个确定的落点,不能把焦点丢在 body 上。
  if (entry) entry.focus({ preventScroll: true })
  else pageHeading.value?.focus({ preventScroll: true })
}, { flush: 'post' })

watch(fieldDraft, async (draft) => {
  if (draft) {
    await nextTick()
    fieldKeyInput.value?.focus()
  } else {
    focusTrigger('[data-action="add-field"]')
  }
})
watch(caseTypeDraft, async (draft) => {
  if (draft) {
    await nextTick()
    typeIdInput.value?.focus()
  } else {
    focusTrigger('[data-action="add-type"]')
  }
})
</script>

<template>
  <header class="settings-page-head template-head">
    <div>
      <span class="settings-page-kicker">设置中心</span>
      <h1 ref="pageHeading" tabindex="-1">{{ isFields ? '模板字段' : '用例类型' }}</h1>
      <p v-if="isFields">配置类别共享字段；不修改个人显示列偏好或 Jira CSV 映射。</p>
      <p v-else>维护类型名称、适用类别与启用状态；不代表对应执行能力已上线。</p>
    </div>
    <div class="toolbar">
      <template v-if="isFields">
        <el-button @click="createCaseTemplate">复制为自定义模板</el-button>
        <el-button type="primary" data-action="add-field" @click="openFieldDraft">新增字段</el-button>
        <el-button @click="resetConfig">恢复默认配置</el-button>
      </template>
      <template v-else>
        <el-button type="primary" data-action="add-type" @click="openCaseTypeDraft">新增类型</el-button>
        <el-button @click="resetConfig">恢复默认配置</el-button>
      </template>
    </div>
  </header>

  <section v-if="isFields" class="s-workspace" aria-label="模板字段设置">
    <aside class="s-directory">
      <header>
        <h2>用例模板</h2>
      </header>
      <label>
        <input v-model="templateKeyword" type="search" placeholder="搜索模板" aria-label="搜索模板" />
      </label>
      <nav aria-label="模板目录">
        <button
          v-for="option in filteredTemplates"
          :key="option.id"
          type="button"
          :class="{ active: option.id === selectedTemplate?.id }"
          :aria-current="option.id === selectedTemplate?.id ? 'true' : undefined"
          @click="selectTemplate(option.id)"
        >
          <strong>{{ option.name }}</strong>
          <small>{{ option.builtIn ? '内置' : '自定义' }} · {{ kindLabels[option.kind] }}</small>
        </button>
      </nav>
      <footer>同类别共享字段定义；切换模板不表示字段独立。</footer>
    </aside>

    <section class="s-field-panel">
      <header class="s-panel-title">
        <div>
          <h2>{{ selectedTemplate?.name || '用例模板' }} · 字段设置</h2>
          <p>影响范围：{{ templateKindLabel }}类别的全部模板共享字段（非当前模板独立副本）</p>
        </div>
        <div v-if="selectedTemplate && !selectedTemplate.builtIn" class="s-panel-tools">
          <button
            type="button"
            aria-label="重命名模板"
            @click="selectedTemplate && renameCaseTemplate(selectedTemplate.id, `${selectedTemplate.name}（自定义）`)"
          >
            重命名
          </button>
          <button type="button" aria-label="删除模板" @click="selectedTemplate && removeCaseTemplate(selectedTemplate)">
            删除模板
          </button>
        </div>
      </header>

      <div class="s-toolbar">
        <label class="s-search">
          <input v-model="fieldKeyword" type="search" aria-label="搜索字段" placeholder="搜索字段 Key 或中文名称" />
        </label>
        <span>{{ filteredFields.length }} / {{ selectedTemplateFields.length }} 字段</span>
      </div>

      <div class="s-scroll" tabindex="0" role="region" aria-label="字段设置表，可横向和纵向滚动">
        <table class="s-table s-fields-table">
          <thead>
            <tr>
              <th>排序</th>
              <th>字段 Key</th>
              <th>中文表头</th>
              <th>类型</th>
              <th>显示</th>
              <th>导出</th>
              <th>AI 生成</th>
              <th>必填</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="field in filteredFields" :key="field.key">
              <td>
                <button
                  type="button"
                  class="s-move"
                  :disabled="fieldIndexOf(field.key) <= 0"
                  :aria-label="`上移 ${field.key}`"
                  @click="moveField(field.key, -1)"
                >
                  ↑
                </button>
                <button
                  type="button"
                  class="s-move"
                  :disabled="fieldIndexOf(field.key) >= selectedTemplateFields.length - 1"
                  :aria-label="`下移 ${field.key}`"
                  @click="moveField(field.key, 1)"
                >
                  ↓
                </button>
              </td>
              <td>{{ field.key }}</td>
              <td>
                <input
                  type="text"
                  :value="field.label"
                  :aria-label="`${field.key} 中文表头`"
                  @change="patchFieldLabel(field.key, ($event.target as HTMLInputElement).value, $event.target as HTMLInputElement)"
                />
              </td>
              <td>
                <el-select
                  :model-value="field.type"
                  size="small"
                  aria-label="字段类型"
                  @change="(value: FieldType) => patchFieldType(field.key, value)"
                >
                  <el-option v-for="option in fieldTypeOptions" :key="option.value" :label="option.label" :value="option.value" />
                </el-select>
              </td>
              <td class="s-flag">
                <input
                  type="checkbox"
                  :checked="field.visible"
                  :aria-label="`${field.key} 显示`"
                  @change="patchFieldVisible(field.key, ($event.target as HTMLInputElement).checked)"
                />
              </td>
              <td class="s-flag">
                <input
                  type="checkbox"
                  :checked="field.exportable"
                  :aria-label="`${field.key} 导出`"
                  @change="patchFieldExportable(field.key, ($event.target as HTMLInputElement).checked)"
                />
              </td>
              <td class="s-flag">
                <input
                  type="checkbox"
                  :checked="field.aiGenerated"
                  :aria-label="`${field.key} AI生成`"
                  @change="patchFieldAiGenerated(field.key, ($event.target as HTMLInputElement).checked)"
                />
              </td>
              <td class="s-flag">
                <input
                  type="checkbox"
                  :checked="field.required"
                  :aria-label="`${field.key} 必填`"
                  @change="patchFieldRequired(field.key, ($event.target as HTMLInputElement).checked)"
                />
              </td>
              <td>
                <button type="button" class="s-table-delete" @click="removeField(field.key)">删除</button>
              </td>
            </tr>
            <tr v-if="!filteredFields.length">
              <td colspan="9" class="s-empty">
                <strong>没有匹配字段</strong>
                <button type="button" class="s-link" @click="fieldKeyword = ''">清除关键词</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <footer class="s-panel-footer">
        <span>修改后自动保存 · 表内可滚动</span>
        <a class="s-panel-link" href="/settings/case-types" @click.prevent="emit('openSection', 'types')">管理用例类型 ↗</a>
      </footer>
    </section>
  </section>

  <section v-else class="s-types-page" aria-label="用例类型设置">
    <div class="s-type-filters">
      <el-select v-model="typeScope" size="small" aria-label="类型适用范围筛选" class="s-scope-filter">
        <el-option label="全部适用范围" value="all-filter" />
        <el-option v-for="option in scopeOptions" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <label class="s-search">
        <input v-model="typeKeyword" type="search" aria-label="搜索类型" placeholder="搜索类型名称或 ID" />
      </label>
      <button type="button" class="s-reset" @click="resetTypeFilter">重置</button>
    </div>

    <section class="s-types-box">
      <header class="s-box-head">
        <h2>用例类型</h2>
        <span>{{ filteredTypes.length }} / {{ allCaseTypes.length }} 类型</span>
      </header>
      <div class="s-scroll" tabindex="0" role="region" aria-label="用例类型设置，可滚动">
        <table class="s-table s-types-table">
          <thead>
            <tr>
              <th>类型 ID</th>
              <th>类型名称</th>
              <th>适用范围</th>
              <th>启用</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="caseType in filteredTypes" :key="caseType.id">
              <td>{{ caseType.id }}</td>
              <td>
                <input
                  type="text"
                  :value="caseType.name"
                  :aria-label="`${caseType.id} 类型名称`"
                  @change="patchCaseTypeName(caseType.id, ($event.target as HTMLInputElement).value, $event.target as HTMLInputElement)"
                />
              </td>
              <td>
                <el-select
                  :model-value="caseType.appliesTo"
                  size="small"
                  aria-label="适用范围"
                  @change="(value: CaseTypeDefinition['appliesTo']) => patchCaseTypeAppliesTo(caseType.id, value)"
                >
                  <el-option v-for="option in scopeOptions" :key="option.value" :label="option.label" :value="option.value" />
                </el-select>
              </td>
              <td class="s-flag">
                <input
                  type="checkbox"
                  :checked="caseType.enabled"
                  :aria-label="`启用 ${caseType.id}`"
                  @change="patchCaseTypeEnabled(caseType.id, ($event.target as HTMLInputElement).checked)"
                />
              </td>
              <td>
                <button type="button" class="s-table-delete" @click="removeCaseType(caseType.id)">删除</button>
              </td>
            </tr>
            <tr v-if="!filteredTypes.length">
              <td colspan="5" class="s-empty">
                <strong>没有匹配类型</strong>
                <button type="button" class="s-link" @click="resetTypeFilter">重置筛选</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="s-panel-footer">
        <span>“全部”适用于所有类别；不混淆类别与执行能力。</span>
        <a class="s-panel-link" href="/settings" @click.prevent="emit('openSection', 'fields')">返回模板字段 ↗</a>
      </footer>
    </section>
  </section>

  <footer class="s-savebar">
    <span class="s-save-status" data-save-status :data-state="saveStatus.state" role="status" aria-live="polite">{{ saveStatusText }}</span>
    <div class="s-save-actions">
      <button v-if="isSaveFailed" type="button" data-action="retry-save" @click="retrySave(props.section)">重试保存</button>
      <button v-if="isSaveFailed" type="button" data-action="discard-save" @click="discardUnsaved(props.section)">放弃未保存修改</button>
    </div>
  </footer>

  <!-- 新增字段:右侧抽屉草稿,确认前不落库。抽屉归属「模板字段」分节,不会跟到类型页 -->
  <template v-if="isFields && fieldDraft">
    <div class="s-drawer-backdrop" @click="cancelFieldDraft" />
    <aside
      class="s-drawer"
      data-drawer="field"
      role="dialog"
      aria-modal="true"
      aria-labelledby="field-drawer-title"
      @keydown.esc.stop.prevent="cancelFieldDraft"
      @keydown.tab="trapTab($event, $event.currentTarget as HTMLElement)"
    >
      <header class="s-drawer-head">
        <div>
          <div class="s-drawer-kicker">ADD SHARED FIELD</div>
          <h2 id="field-drawer-title">新增字段</h2>
          <p class="s-drawer-sub">{{ kindLabels[fieldDraft.kind] }} · 共享字段定义（应用于该类别全部模板）</p>
        </div>
        <button type="button" class="s-drawer-close" aria-label="关闭新增字段" data-action="cancel-field" @click="cancelFieldDraft">×</button>
      </header>
      <div class="s-drawer-body">
        <p class="s-drawer-note">新增字段应用于当前类别的共享字段定义；确认后进入自动保存。</p>
        <div class="s-drawer-form">
          <div class="s-drawer-field">
            <label for="field-draft-key">字段 Key</label>
            <input
              id="field-draft-key"
              ref="fieldKeyInput"
              v-model="fieldDraft.key"
              type="text"
              data-field="key"
              placeholder="例如 custom_note"
              autocomplete="off"
            />
            <small>Key 不可与当前类别已有字段重复；创建后不在表内修改。</small>
          </div>
          <div class="s-drawer-field">
            <label for="field-draft-label">中文表头</label>
            <input
              id="field-draft-label"
              v-model="fieldDraft.label"
              type="text"
              data-field="label"
              placeholder="填写字段名称"
              autocomplete="off"
            />
          </div>
          <div class="s-drawer-field">
            <label>字段类型</label>
            <el-select v-model="fieldDraft.type" data-field="type" aria-label="字段类型">
              <el-option v-for="option in fieldTypeOptions" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
          </div>
          <div class="s-drawer-flags">
            <label><input v-model="fieldDraft.visible" type="checkbox" data-flag="visible" /> 显示</label>
            <label><input v-model="fieldDraft.exportable" type="checkbox" data-flag="exportable" /> 导出</label>
            <label><input v-model="fieldDraft.aiGenerated" type="checkbox" data-flag="aiGenerated" /> AI 生成</label>
            <label><input v-model="fieldDraft.required" type="checkbox" data-flag="required" /> 必填</label>
          </div>
          <p v-if="fieldDraft.error" class="s-drawer-error" role="alert">{{ fieldDraft.error }}</p>
        </div>
      </div>
      <footer class="s-drawer-foot">
        <span>新增后进入自动保存</span>
        <div class="s-drawer-actions">
          <button type="button" class="s-drawer-btn" data-action="cancel-field" @click="cancelFieldDraft">取消</button>
          <button type="button" class="s-drawer-btn primary" data-action="submit-field" @click="submitFieldDraft">新增</button>
        </div>
      </footer>
    </aside>
  </template>

  <!-- 新增类型:同一套右抽屉,覆盖全部类别的适用范围(只在「用例类型」分节出现) -->
  <template v-if="!isFields && caseTypeDraft">
    <div class="s-drawer-backdrop" @click="cancelCaseTypeDraft" />
    <aside
      class="s-drawer"
      data-drawer="type"
      role="dialog"
      aria-modal="true"
      aria-labelledby="type-drawer-title"
      @keydown.esc.stop.prevent="cancelCaseTypeDraft"
      @keydown.tab="trapTab($event, $event.currentTarget as HTMLElement)"
    >
      <header class="s-drawer-head">
        <div>
          <div class="s-drawer-kicker">ADD CASE TYPE</div>
          <h2 id="type-drawer-title">新增类型</h2>
          <p class="s-drawer-sub">类型 ID 与名称在该项目内唯一；适用范围决定它在哪些类别出现。</p>
        </div>
        <button type="button" class="s-drawer-close" aria-label="关闭新增类型" data-action="cancel-type" @click="cancelCaseTypeDraft">×</button>
      </header>
      <div class="s-drawer-body">
        <p class="s-drawer-note">新增类型不影响执行能力；“全部类别”适用于所有用例类别。</p>
        <div class="s-drawer-form">
          <div class="s-drawer-field">
            <label for="type-draft-id">类型 ID</label>
            <input
              id="type-draft-id"
              ref="typeIdInput"
              v-model="caseTypeDraft.id"
              type="text"
              data-field="type-id"
              placeholder="例如 web_timeout"
              autocomplete="off"
            />
            <small>ID 创建后不在表内修改；不能与已有类型重复。</small>
          </div>
          <div class="s-drawer-field">
            <label for="type-draft-name">类型名称</label>
            <input
              id="type-draft-name"
              v-model="caseTypeDraft.name"
              type="text"
              data-field="type-name"
              placeholder="填写类型名称"
              autocomplete="off"
            />
          </div>
          <div class="s-drawer-field">
            <label>适用范围</label>
            <el-select v-model="caseTypeDraft.appliesTo" data-field="scope" aria-label="适用范围">
              <el-option v-for="option in scopeOptions" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
          </div>
          <div class="s-drawer-flags">
            <label><input v-model="caseTypeDraft.enabled" type="checkbox" data-flag="enabled" /> 启用</label>
          </div>
          <p v-if="caseTypeDraft.error" class="s-drawer-error" role="alert">{{ caseTypeDraft.error }}</p>
        </div>
      </div>
      <footer class="s-drawer-foot">
        <span>新增后进入自动保存</span>
        <div class="s-drawer-actions">
          <button type="button" class="s-drawer-btn" data-action="cancel-type" @click="cancelCaseTypeDraft">取消</button>
          <button type="button" class="s-drawer-btn primary" data-action="submit-type" @click="submitCaseTypeDraft">新增</button>
        </div>
      </footer>
    </aside>
  </template>
</template>
