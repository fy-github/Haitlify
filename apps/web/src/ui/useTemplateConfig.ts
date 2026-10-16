import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { missingDep } from './depGuard'
import {
  addCaseField,
  addCaseFieldByKind,
  addCaseTemplate,
  addCaseType,
  deleteCaseField,
  deleteCaseFieldByKind,
  deleteCaseTemplate,
  deleteCaseType,
  loadTemplateConfig,
  moveCaseField,
  moveCaseFieldByKind,
  resetTemplateConfig,
  templateKindOptions,
  updateCaseField,
  updateCaseFieldByKind,
  updateCaseTemplate,
  updateCaseType,
  type TemplateConfig
} from '../domain/templateConfigRepository'
import type {
  CaseField,
  CaseKind,
  CaseTemplateDefinition,
  CaseTypeDefinition,
  FieldType
} from '../domain/caseTemplates'

export interface TemplateConfigDeps {
  confirmDanger?: (title: string, message: string, confirmLabel?: string) => Promise<boolean>
  // Persist the config (local + backend); returns the authoritative saved config.
  persistTemplateConfig?: (config: TemplateConfig) => Promise<TemplateConfig>
  // Bridge to the generation workflow's selectedCaseTypeIds (owned by App.vue).
  getGenerationSelectedCaseTypes?: () => string[]
  setGenerationSelectedCaseTypes?: (ids: string[]) => void
}

// Module-level singleton so App.vue (generation workflow + sidebar) and
// SettingsView.vue (template settings) share the same template config state.
let singleton: ReturnType<typeof createTemplateConfigState> | null = null

function createTemplateConfigState(deps: TemplateConfigDeps) {
  const confirmDanger = deps.confirmDanger ?? (async () => missingDep('useTemplateConfig', 'confirmDanger'))
  const persistTemplateConfig = deps.persistTemplateConfig ?? (async (config) => missingDep('useTemplateConfig', 'persistTemplateConfig'))

  const templateConfig = ref<TemplateConfig>(loadTemplateConfig(localStorage))
  const selectedTemplateId = ref('builtin-interface')
  const generationTemplateId = ref('builtin-interface')

  const interfaceFields = computed(() => templateConfig.value.interfaceFields)
  const webAutomationFields = computed(() => templateConfig.value.fieldsByKind.web_automation)
  const templateOptions = computed(() => templateKindOptions(templateConfig.value))
  const selectedTemplate = computed(() =>
    templateConfig.value.caseTemplates.find((template) => template.id === selectedTemplateId.value)
      || templateConfig.value.caseTemplates[0]
  )
  const selectedTemplateKind = computed<CaseKind>(() =>
    selectedTemplate.value?.kind || 'interface'
  )
  const selectedTemplateFields = computed(() =>
    templateConfig.value.fieldsByKind[selectedTemplateKind.value] || []
  )
  const interfaceCaseTypes = computed(() =>
    templateConfig.value.caseTypes.filter((caseType) => caseType.appliesTo === 'interface' || caseType.appliesTo === 'all')
  )
  const enabledInterfaceCaseTypeIds = computed(
    () => new Set(interfaceCaseTypes.value.filter((caseType) => caseType.enabled).map((caseType) => caseType.id))
  )

  const fieldCellLabel = (key: string): string =>
    selectedTemplateFields.value.find((field) => field.key === key)?.label || key

  const isMultilineField = (type: FieldType): boolean => ['textarea', 'json', 'tags'].includes(type)

  const persist = () => {
    void persistTemplateConfig(templateConfig.value).then((saved) => {
      templateConfig.value = saved
    })
  }

  const patchField = (key: string, patch: Partial<CaseField>) => {
    templateConfig.value = selectedTemplateKind.value === 'interface'
      ? updateCaseField(templateConfig.value, key, patch)
      : updateCaseFieldByKind(templateConfig.value, selectedTemplateKind.value, key, patch)
    persist()
  }

  const createField = () => {
    const key = `customField${Date.now()}`
    const field: CaseField = {
      key,
      label: '自定义字段',
      type: 'text',
      required: false,
      visible: true,
      exportable: true,
      aiGenerated: false
    }
    templateConfig.value = selectedTemplateKind.value === 'interface'
      ? addCaseField(templateConfig.value, field)
      : addCaseFieldByKind(templateConfig.value, selectedTemplateKind.value, field)
    if (selectedTemplate.value && !selectedTemplate.value.builtIn) {
      templateConfig.value = updateCaseTemplate(templateConfig.value, selectedTemplate.value.id, {
        fieldKeys: [...selectedTemplate.value.fieldKeys, key]
      })
    }
    persist()
  }

  const removeField = async (key: string) => {
    const confirmed = await confirmDanger('删除字段', `确认删除字段 ${fieldCellLabel(key)} 吗？`)
    if (!confirmed) return
    templateConfig.value = selectedTemplateKind.value === 'interface'
      ? deleteCaseField(templateConfig.value, key)
      : deleteCaseFieldByKind(templateConfig.value, selectedTemplateKind.value, key)
    persist()
  }

  const moveField = (key: string, direction: -1 | 1) => {
    templateConfig.value = selectedTemplateKind.value === 'interface'
      ? moveCaseField(templateConfig.value, key, direction)
      : moveCaseFieldByKind(templateConfig.value, selectedTemplateKind.value, key, direction)
    persist()
  }

  const createCaseTemplate = () => {
    const kind = selectedTemplateKind.value
    const sourceTemplate = selectedTemplate.value
    const template: CaseTemplateDefinition = {
      id: `custom_template_${Date.now()}`,
      name: `${sourceTemplate?.name || '自定义'}副本`,
      kind,
      builtIn: false,
      enabled: true,
      fieldKeys: selectedTemplateFields.value.map((field) => field.key)
    }
    templateConfig.value = addCaseTemplate(templateConfig.value, template)
    selectedTemplateId.value = template.id
    generationTemplateId.value = template.id
    persist()
  }

  const renameCaseTemplate = (id: string, name: string) => {
    templateConfig.value = updateCaseTemplate(templateConfig.value, id, { name })
    persist()
  }

  const removeCaseTemplate = async (template: CaseTemplateDefinition) => {
    const confirmed = await confirmDanger('删除用例模板', `确认删除模板 ${template.name} 吗？`)
    if (!confirmed) return
    templateConfig.value = deleteCaseTemplate(templateConfig.value, template.id)
    selectedTemplateId.value = templateConfig.value.caseTemplates[0]?.id || 'builtin-interface'
    persist()
  }

  const syncSelectedCaseTypes = () => {
    const enabledIds = enabledInterfaceCaseTypeIds.value
    const current = deps.getGenerationSelectedCaseTypes?.() ?? []
    const next = current.filter((id) => enabledIds.has(id))
    for (const caseType of interfaceCaseTypes.value) {
      if (caseType.enabled && !next.includes(caseType.id)) {
        next.push(caseType.id)
      }
    }
    deps.setGenerationSelectedCaseTypes?.(next)
  }

  const patchCaseType = (id: string, patch: Partial<CaseTypeDefinition>) => {
    templateConfig.value = updateCaseType(templateConfig.value, id, patch)
    syncSelectedCaseTypes()
    persist()
  }

  const createCaseType = () => {
    templateConfig.value = addCaseType(templateConfig.value, {
      id: `custom_case_type_${Date.now()}`,
      name: '自定义类型',
      appliesTo: 'interface',
      enabled: true
    })
    syncSelectedCaseTypes()
    persist()
  }

  const removeCaseType = async (id: string) => {
    const caseTypeName = templateConfig.value.caseTypes.find((caseType) => caseType.id === id)?.name || id
    const confirmed = await confirmDanger('删除用例类型', `确认删除用例类型 ${caseTypeName} 吗？`)
    if (!confirmed) return
    templateConfig.value = deleteCaseType(templateConfig.value, id)
    syncSelectedCaseTypes()
    persist()
  }

  const resetConfig = async () => {
    const confirmed = await confirmDanger('恢复默认模板', '确认恢复默认用例模板配置吗？自定义的模板、字段和用例类型将被清除。')
    if (!confirmed) return
    templateConfig.value = resetTemplateConfig(localStorage)
    deps.setGenerationSelectedCaseTypes?.(interfaceCaseTypes.value.filter((caseType) => caseType.enabled).map((caseType) => caseType.id))
    ElMessage.success('已恢复默认模板')
  }

  return {
    templateConfig,
    selectedTemplateId,
    generationTemplateId,
    interfaceFields,
    webAutomationFields,
    templateOptions,
    selectedTemplate,
    selectedTemplateKind,
    selectedTemplateFields,
    interfaceCaseTypes,
    enabledInterfaceCaseTypeIds,
    fieldCellLabel,
    isMultilineField,
    patchField,
    createField,
    removeField,
    moveField,
    createCaseTemplate,
    renameCaseTemplate,
    removeCaseTemplate,
    syncSelectedCaseTypes,
    patchCaseType,
    createCaseType,
    removeCaseType,
    resetConfig
  }
}

export const useTemplateConfig = (deps: TemplateConfigDeps = {}): ReturnType<typeof createTemplateConfigState> => {
  if (!singleton) singleton = createTemplateConfigState(deps)
  return singleton
}
