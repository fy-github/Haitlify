import {
  builtInCaseTemplates,
  caseFieldsByKind,
  defaultCaseTypes,
  interfaceCaseFields,
  webAutomationCaseFields,
  type CaseTemplateDefinition,
  type CaseField,
  type CaseKind,
  type CaseTypeDefinition
} from './caseTemplates'

export const TEMPLATE_CONFIG_STORAGE_KEY = 'ai-test-platform.template-config.v1'
export const TEMPLATE_CONFIG_META_STORAGE_KEY = 'ai-test-platform.template-config-meta.v1'
const TEMPLATE_CONFIG_SCHEMA_VERSION = 3

export interface TemplateFieldsByKind {
  interface: CaseField[]
  functional: CaseField[]
  web_automation: CaseField[]
  app_automation: CaseField[]
  ai_test: CaseField[]
  performance: CaseField[]
}

export interface TemplateConfig {
  schemaVersion?: number
  interfaceFields: CaseField[]
  fieldsByKind: TemplateFieldsByKind
  caseTypes: CaseTypeDefinition[]
  caseTemplates: CaseTemplateDefinition[]
}

export interface TemplateConfigMeta {
  updatedAt?: string
}

export interface SaveTemplateConfigOptions {
  updatedAt?: string
}

export const defaultTemplateConfig = (): TemplateConfig => ({
  schemaVersion: TEMPLATE_CONFIG_SCHEMA_VERSION,
  interfaceFields: clone(interfaceCaseFields),
  fieldsByKind: {
    interface: clone(interfaceCaseFields),
    web_automation: clone(webAutomationCaseFields),
    app_automation: clone(caseFieldsByKind.app_automation),
    ai_test: clone(caseFieldsByKind.ai_test),
    functional: clone(caseFieldsByKind.functional),
    performance: clone(caseFieldsByKind.performance)
  },
  caseTypes: clone(defaultCaseTypes),
  caseTemplates: clone(builtInCaseTemplates)
})

export const loadTemplateConfig = (storage: Storage): TemplateConfig => {
  const raw = storage.getItem(TEMPLATE_CONFIG_STORAGE_KEY)
  if (!raw) return defaultTemplateConfig()

  try {
    const parsed = JSON.parse(raw) as Partial<TemplateConfig>
    const defaults = defaultTemplateConfig()
    const savedFields = Array.isArray(parsed.interfaceFields)
      ? parsed.interfaceFields.filter(isCaseField)
      : defaults.interfaceFields
    const interfaceFields = parsed.schemaVersion === TEMPLATE_CONFIG_SCHEMA_VERSION
      ? savedFields
      : migrateLegacyInterfaceFields(savedFields, defaults.interfaceFields)
    const fieldsByKind = readFieldsByKind(parsed, defaults, interfaceFields)
    return {
      schemaVersion: TEMPLATE_CONFIG_SCHEMA_VERSION,
      interfaceFields,
      fieldsByKind,
      caseTypes: Array.isArray(parsed.caseTypes)
        ? parsed.caseTypes.filter(isCaseType)
        : defaults.caseTypes,
      caseTemplates: readCaseTemplates(parsed, defaults)
    }
  } catch {
    return defaultTemplateConfig()
  }
}

export const saveTemplateConfig = (
  storage: Storage,
  config: TemplateConfig,
  options: SaveTemplateConfigOptions = {}
): void => {
  storage.setItem(TEMPLATE_CONFIG_STORAGE_KEY, JSON.stringify(normalizeTemplateConfig(config)))
  saveTemplateConfigMeta(storage, {
    updatedAt: options.updatedAt || new Date().toISOString()
  })
}

export const resetTemplateConfig = (storage: Storage): TemplateConfig => {
  storage.removeItem(TEMPLATE_CONFIG_STORAGE_KEY)
  storage.removeItem(TEMPLATE_CONFIG_META_STORAGE_KEY)
  return defaultTemplateConfig()
}

export const loadTemplateConfigMeta = (storage: Storage): TemplateConfigMeta => {
  const raw = storage.getItem(TEMPLATE_CONFIG_META_STORAGE_KEY)
  if (!raw) return {}

  try {
    const parsed = JSON.parse(raw) as Partial<TemplateConfigMeta>
    return typeof parsed.updatedAt === 'string' ? { updatedAt: parsed.updatedAt } : {}
  } catch {
    return {}
  }
}

export const updateCaseField = (
  config: TemplateConfig,
  key: string,
  patch: Partial<CaseField>
): TemplateConfig =>
  normalizeTemplateConfig({
    ...config,
    interfaceFields: config.interfaceFields.map((field) =>
      field.key === key ? { ...field, ...patch, key: field.key } : field
    ),
    fieldsByKind: {
      ...config.fieldsByKind,
      interface: config.fieldsByKind.interface.map((field) =>
        field.key === key ? { ...field, ...patch, key: field.key } : field
      )
    }
  })

export const addCaseField = (config: TemplateConfig, field: CaseField): TemplateConfig =>
  normalizeTemplateConfig({
    ...config,
    interfaceFields: [...config.interfaceFields.filter((item) => item.key !== field.key), field],
    fieldsByKind: {
      ...config.fieldsByKind,
      interface: [...config.fieldsByKind.interface.filter((item) => item.key !== field.key), field]
    }
  })

export const deleteCaseField = (config: TemplateConfig, key: string): TemplateConfig =>
  normalizeTemplateConfig({
    ...config,
    interfaceFields: config.interfaceFields.filter((field) => field.key !== key),
    fieldsByKind: {
      ...config.fieldsByKind,
      interface: config.fieldsByKind.interface.filter((field) => field.key !== key)
    }
  })

export const moveCaseField = (config: TemplateConfig, key: string, direction: -1 | 1): TemplateConfig => {
  const fields = [...config.interfaceFields]
  const index = fields.findIndex((field) => field.key === key)
  const nextIndex = index + direction
  if (index < 0 || nextIndex < 0 || nextIndex >= fields.length) return config

  const [field] = fields.splice(index, 1)
  fields.splice(nextIndex, 0, field)

  return normalizeTemplateConfig({
    ...config,
    interfaceFields: fields,
    fieldsByKind: {
      ...config.fieldsByKind,
      interface: fields
    }
  })
}

export const updateCaseFieldByKind = (
  config: TemplateConfig,
  kind: keyof TemplateFieldsByKind,
  key: string,
  patch: Partial<CaseField>
): TemplateConfig => normalizeTemplateConfig({
  ...config,
  fieldsByKind: {
    ...config.fieldsByKind,
    [kind]: config.fieldsByKind[kind].map((field) =>
      field.key === key ? { ...field, ...patch, key: field.key } : field
    )
  }
})

export const moveCaseFieldByKind = (
  config: TemplateConfig,
  kind: keyof TemplateFieldsByKind,
  key: string,
  direction: -1 | 1
): TemplateConfig => {
  const fields = [...config.fieldsByKind[kind]]
  const index = fields.findIndex((field) => field.key === key)
  const nextIndex = index + direction
  if (index < 0 || nextIndex < 0 || nextIndex >= fields.length) return config

  const [field] = fields.splice(index, 1)
  fields.splice(nextIndex, 0, field)

  return normalizeTemplateConfig({
    ...config,
    fieldsByKind: {
      ...config.fieldsByKind,
      [kind]: fields
    }
  })
}

export const addCaseFieldByKind = (
  config: TemplateConfig,
  kind: keyof TemplateFieldsByKind,
  field: CaseField
): TemplateConfig => normalizeTemplateConfig({
  ...config,
  fieldsByKind: {
    ...config.fieldsByKind,
    [kind]: [...config.fieldsByKind[kind].filter((item) => item.key !== field.key), field]
  }
})

export const deleteCaseFieldByKind = (
  config: TemplateConfig,
  kind: keyof TemplateFieldsByKind,
  key: string
): TemplateConfig => normalizeTemplateConfig({
  ...config,
  fieldsByKind: {
    ...config.fieldsByKind,
    [kind]: config.fieldsByKind[kind].filter((field) => field.key !== key)
  },
  caseTemplates: config.caseTemplates.map((template) =>
    template.kind === kind
      ? { ...template, fieldKeys: template.fieldKeys.filter((fieldKey) => fieldKey !== key) }
      : template
  )
})

export const addCaseTemplate = (
  config: TemplateConfig,
  template: CaseTemplateDefinition
): TemplateConfig => normalizeTemplateConfig({
  ...config,
  caseTemplates: [...config.caseTemplates.filter((item) => item.id !== template.id), {
    ...template,
    builtIn: false
  }]
})

export const updateCaseTemplate = (
  config: TemplateConfig,
  id: string,
  patch: Partial<CaseTemplateDefinition>
): TemplateConfig => normalizeTemplateConfig({
  ...config,
  caseTemplates: config.caseTemplates.map((template) =>
    template.id === id && !template.builtIn
      ? { ...template, ...patch, id: template.id, builtIn: template.builtIn }
      : template
  )
})

export const deleteCaseTemplate = (config: TemplateConfig, id: string): TemplateConfig =>
  normalizeTemplateConfig({
    ...config,
    caseTemplates: config.caseTemplates.filter((template) => template.builtIn || template.id !== id)
  })

export const templateKindOptions = (config: TemplateConfig): Array<{ value: string; label: string; kind: CaseKind; builtIn: boolean }> =>
  config.caseTemplates.map((template) => ({
    value: template.id,
    label: template.builtIn ? `${template.name}（内置）` : template.name,
    kind: template.kind,
    builtIn: template.builtIn
  }))

export const updateCaseType = (
  config: TemplateConfig,
  id: string,
  patch: Partial<CaseTypeDefinition>
): TemplateConfig => ({
  ...config,
  caseTypes: config.caseTypes.map((caseType) =>
    caseType.id === id ? { ...caseType, ...patch, id: caseType.id } : caseType
  )
})

export const addCaseType = (
  config: TemplateConfig,
  caseType: CaseTypeDefinition
): TemplateConfig => ({
  ...config,
  caseTypes: [...config.caseTypes.filter((item) => item.id !== caseType.id), caseType]
})

export const deleteCaseType = (config: TemplateConfig, id: string): TemplateConfig => ({
  ...config,
  caseTypes: config.caseTypes.filter((caseType) => caseType.id !== id)
})

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T

const saveTemplateConfigMeta = (storage: Storage, meta: TemplateConfigMeta): void => {
  storage.setItem(TEMPLATE_CONFIG_META_STORAGE_KEY, JSON.stringify(meta))
}

const isCaseField = (value: unknown): value is CaseField => {
  if (!value || typeof value !== 'object') return false
  const record = value as Record<string, unknown>
  return typeof record.key === 'string' && typeof record.label === 'string'
}

const isCaseType = (value: unknown): value is CaseTypeDefinition => {
  if (!value || typeof value !== 'object') return false
  const record = value as Record<string, unknown>
  return typeof record.id === 'string' && typeof record.name === 'string'
}

const isCaseTemplate = (value: unknown): value is CaseTemplateDefinition => {
  if (!value || typeof value !== 'object') return false
  const record = value as Record<string, unknown>
  return (
    typeof record.id === 'string' &&
    typeof record.name === 'string' &&
    isCaseKind(record.kind) &&
    typeof record.builtIn === 'boolean' &&
    typeof record.enabled === 'boolean' &&
    Array.isArray(record.fieldKeys) &&
    record.fieldKeys.every((fieldKey) => typeof fieldKey === 'string')
  )
}

const isCaseKind = (value: unknown): value is CaseKind =>
  typeof value === 'string' && value in caseFieldsByKind

const readFieldsByKind = (
  parsed: Partial<TemplateConfig>,
  defaults: TemplateConfig,
  interfaceFields: CaseField[]
): TemplateFieldsByKind => {
  const raw = parsed.fieldsByKind as Partial<Record<CaseKind, unknown>> | undefined
  return {
    interface: interfaceFields,
    web_automation: normalizeFields(raw?.web_automation, defaults.fieldsByKind.web_automation),
    app_automation: normalizeFields(raw?.app_automation, defaults.fieldsByKind.app_automation),
    ai_test: normalizeFields(raw?.ai_test, defaults.fieldsByKind.ai_test),
    functional: normalizeFields(raw?.functional, defaults.fieldsByKind.functional),
    performance: normalizeFields(raw?.performance, defaults.fieldsByKind.performance)
  }
}

const readCaseTemplates = (
  parsed: Partial<TemplateConfig>,
  defaults: TemplateConfig
): CaseTemplateDefinition[] => {
  const saved = Array.isArray(parsed.caseTemplates)
    ? parsed.caseTemplates.filter(isCaseTemplate)
    : []
  const customTemplates = saved.filter((template) => !template.builtIn)
  return [...clone(defaults.caseTemplates), ...customTemplates]
}

const normalizeFields = (value: unknown, fallback: CaseField[]): CaseField[] =>
  Array.isArray(value) ? value.filter(isCaseField) : fallback

const normalizeTemplateConfig = (config: TemplateConfig): TemplateConfig => ({
  ...config,
  schemaVersion: TEMPLATE_CONFIG_SCHEMA_VERSION,
  interfaceFields: config.interfaceFields,
  fieldsByKind: {
    interface: config.interfaceFields,
    web_automation: config.fieldsByKind?.web_automation || clone(webAutomationCaseFields),
    app_automation: config.fieldsByKind?.app_automation || clone(caseFieldsByKind.app_automation),
    ai_test: config.fieldsByKind?.ai_test || clone(caseFieldsByKind.ai_test),
    functional: config.fieldsByKind?.functional || clone(caseFieldsByKind.functional),
    performance: config.fieldsByKind?.performance || clone(caseFieldsByKind.performance)
  },
  caseTemplates: normalizeCaseTemplates(config.caseTemplates)
})

const normalizeCaseTemplates = (templates: CaseTemplateDefinition[] = []): CaseTemplateDefinition[] => {
  const customTemplates = templates
    .filter(isCaseTemplate)
    .filter((template) => !template.builtIn)
  return [...clone(builtInCaseTemplates), ...customTemplates]
}

const migrateLegacyInterfaceFields = (saved: CaseField[], defaults: CaseField[]): CaseField[] => {
  const savedKeys = new Set(saved.map((field) => field.key))
  const requiredLegacyAdditions = defaults.filter((field) =>
    ['expectedResponseBody', 'actualResponseBody'].includes(field.key) && !savedKeys.has(field.key)
  )
  const assertionsIndex = saved.findIndex((field) => field.key === 'assertions')
  if (requiredLegacyAdditions.length === 0) return saved
  if (assertionsIndex < 0) return [...saved, ...requiredLegacyAdditions]
  const next = [...saved]
  next.splice(assertionsIndex, 0, ...requiredLegacyAdditions)
  return next
}
