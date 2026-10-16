export type CaseKind =
  | 'interface'
  | 'web_automation'
  | 'app_automation'
  | 'ai_test'
  | 'functional'
  | 'performance'

export type FieldType = 'text' | 'textarea' | 'select' | 'datetime' | 'tags' | 'json'

export interface CaseField {
  key: string
  label: string
  type: FieldType
  required: boolean
  visible: boolean
  exportable: boolean
  aiGenerated: boolean
  defaultValue?: string
}

export interface CaseTypeDefinition {
  id: string
  name: string
  appliesTo: CaseKind | 'all'
  enabled: boolean
}

export interface CaseTemplateDefinition {
  id: string
  name: string
  kind: CaseKind
  builtIn: boolean
  enabled: boolean
  fieldKeys: string[]
}

const textField = (
  key: string,
  label: string,
  options: Partial<CaseField> = {}
): CaseField => ({
  key,
  label,
  type: 'text',
  required: false,
  visible: true,
  exportable: true,
  aiGenerated: true,
  ...options
})

export const interfaceCaseFields: CaseField[] = [
  textField('caseId', '用例ID', { required: true, aiGenerated: false }),
  textField('project', '项目', { required: true }),
  textField('module', '模块', { required: true }),
  textField('interfaceName', '接口名称', { required: true }),
  textField('method', '请求方法', { required: true }),
  textField('path', '请求路径', { required: true }),
  textField('title', '用例标题', { required: true }),
  textField('caseType', '用例类型', { required: true, type: 'select' }),
  textField('precondition', '前置条件', { type: 'textarea' }),
  textField('headers', '请求头', { type: 'json' }),
  textField('pathParams', '路径参数', { type: 'json' }),
  textField('queryParams', '查询参数', { type: 'json' }),
  textField('body', '请求体', { type: 'json' }),
  textField('steps', '测试步骤', { type: 'textarea' }),
  textField('expectedResult', '预期结果', { type: 'textarea' }),
  textField('expectedResponseBody', '预期返回体', { type: 'json' }),
  textField('actualResponseBody', '实际返回体', { type: 'json', aiGenerated: false }),
  textField('assertions', '断言规则', { type: 'textarea' }),
  textField('priority', '优先级', { type: 'select' }),
  textField('executionResult', '执行结果', {
    type: 'select',
    aiGenerated: false,
    defaultValue: '未执行'
  }),
  textField('executor', '执行人', { aiGenerated: false }),
  textField('executedAt', '执行时间', { type: 'datetime', aiGenerated: false }),
  textField('defectId', '缺陷ID', { aiGenerated: false }),
  textField('tags', '标签', { type: 'tags' }),
  textField('sourceFile', '来源文件'),
  textField('remark', '备注', { type: 'textarea' })
]

export const functionalCaseFields: CaseField[] = [
  textField('caseId', '用例ID', { required: true, aiGenerated: false }),
  textField('project', '项目', { required: true }),
  textField('module', '模块', { required: true }),
  textField('feature', '功能点', { required: true }),
  textField('title', '用例标题', { required: true }),
  textField('caseType', '用例类型', { required: true, type: 'select' }),
  textField('precondition', '前置条件', { type: 'textarea' }),
  textField('steps', '测试步骤', { type: 'textarea' }),
  textField('expectedResult', '预期结果', { type: 'textarea' }),
  textField('priority', '优先级', { type: 'select' }),
  textField('executionResult', '执行结果', {
    type: 'select',
    aiGenerated: false,
    defaultValue: '未执行'
  }),
  textField('executor', '执行人', { aiGenerated: false }),
  textField('executedAt', '执行时间', { type: 'datetime', aiGenerated: false }),
  textField('defectId', '缺陷ID', { aiGenerated: false }),
  textField('tags', '标签', { type: 'tags' }),
  textField('sourceType', '来源类型'),
  textField('remark', '备注', { type: 'textarea' })
]

export const appAutomationCaseFields: CaseField[] = [
  textField('caseId', '用例ID', { required: true, aiGenerated: false }),
  textField('project', '项目', { required: true }),
  textField('module', '模块', { required: true }),
  textField('appName', '应用', { required: true }),
  textField('platform', '平台', { required: true, type: 'select', defaultValue: 'Android' }),
  textField('title', '用例标题', { required: true }),
  textField('caseType', '用例类型', { required: true, type: 'select' }),
  textField('precondition', '前置条件', { type: 'textarea' }),
  textField('deviceProfile', '设备配置'),
  textField('testData', '测试数据', { type: 'json' }),
  textField('steps', '操作步骤', { type: 'textarea' }),
  textField('locators', '元素定位', { type: 'textarea' }),
  textField('assertions', '断言规则', { type: 'textarea' }),
  textField('priority', '优先级', { type: 'select' }),
  textField('executionResult', '执行结果', {
    type: 'select',
    aiGenerated: false,
    defaultValue: '未执行'
  }),
  textField('executor', '执行人', { aiGenerated: false }),
  textField('executedAt', '执行时间', { type: 'datetime', aiGenerated: false }),
  textField('defectId', '缺陷ID', { aiGenerated: false }),
  textField('tags', '标签', { type: 'tags' }),
  textField('sourceFile', '来源文件'),
  textField('remark', '备注', { type: 'textarea' })
]

export const aiTestCaseFields: CaseField[] = [
  textField('caseId', '用例ID', { required: true, aiGenerated: false }),
  textField('project', '项目', { required: true }),
  textField('module', '模块', { required: true }),
  textField('scenario', '场景', { required: true }),
  textField('title', '用例标题', { required: true }),
  textField('caseType', '用例类型', { required: true, type: 'select' }),
  textField('prompt', '提示词', { type: 'textarea' }),
  textField('inputData', '输入数据', { type: 'json' }),
  textField('expectedBehavior', '预期行为', { type: 'textarea' }),
  textField('evaluationRules', '评估规则', { type: 'textarea' }),
  textField('priority', '优先级', { type: 'select' }),
  textField('executionResult', '执行结果', {
    type: 'select',
    aiGenerated: false,
    defaultValue: '未执行'
  }),
  textField('executor', '执行人', { aiGenerated: false }),
  textField('executedAt', '执行时间', { type: 'datetime', aiGenerated: false }),
  textField('defectId', '缺陷ID', { aiGenerated: false }),
  textField('tags', '标签', { type: 'tags' }),
  textField('sourceFile', '来源文件'),
  textField('remark', '备注', { type: 'textarea' })
]

export const performanceCaseFields: CaseField[] = [
  textField('caseId', '用例ID', { required: true, aiGenerated: false }),
  textField('project', '项目', { required: true }),
  textField('module', '模块', { required: true }),
  textField('scenario', '压测场景', { required: true }),
  textField('title', '用例标题', { required: true }),
  textField('caseType', '用例类型', { required: true, type: 'select' }),
  textField('targetUrl', '目标地址'),
  textField('loadProfile', '负载模型', { type: 'textarea' }),
  textField('testData', '测试数据', { type: 'json' }),
  textField('successCriteria', '成功标准', { type: 'textarea' }),
  textField('priority', '优先级', { type: 'select' }),
  textField('executionResult', '执行结果', {
    type: 'select',
    aiGenerated: false,
    defaultValue: '未执行'
  }),
  textField('executor', '执行人', { aiGenerated: false }),
  textField('executedAt', '执行时间', { type: 'datetime', aiGenerated: false }),
  textField('defectId', '缺陷ID', { aiGenerated: false }),
  textField('tags', '标签', { type: 'tags' }),
  textField('sourceFile', '来源文件'),
  textField('remark', '备注', { type: 'textarea' })
]

export const webAutomationCaseFields: CaseField[] = [
  textField('caseId', '用例ID', { required: true, aiGenerated: false }),
  textField('project', '项目', { required: true }),
  textField('module', '模块', { required: true }),
  textField('pageName', '页面', { required: true }),
  textField('title', '用例标题', { required: true }),
  textField('naturalSteps', '自然语言步骤', { type: 'textarea' }),
  textField('naturalExpectedResult', '自然语言预期', { type: 'textarea' }),
  textField('caseType', '用例类型', { required: true, type: 'select' }),
  textField('precondition', '前置条件', { type: 'textarea' }),
  textField('startUrl', '访问地址', { required: true }),
  textField('browser', '浏览器', { type: 'select', defaultValue: 'chromium' }),
  textField('viewport', '视口'),
  textField('testData', '测试数据', { type: 'json' }),
  textField('steps', '操作步骤', { type: 'textarea' }),
  textField('locators', '元素定位', { type: 'textarea' }),
  textField('assertions', '断言规则', { type: 'textarea' }),
  textField('waitStrategy', '等待策略', { type: 'select' }),
  textField('screenshotStrategy', '截图策略', { type: 'select' }),
  textField('priority', '优先级', { type: 'select' }),
  textField('executionResult', '执行结果', {
    type: 'select',
    aiGenerated: false,
    defaultValue: '未执行'
  }),
  textField('executor', '执行人', { aiGenerated: false }),
  textField('executedAt', '执行时间', { type: 'datetime', aiGenerated: false }),
  textField('defectId', '缺陷ID', { aiGenerated: false }),
  textField('tags', '标签', { type: 'tags' }),
  textField('sourceFile', '来源文件'),
  textField('remark', '备注', { type: 'textarea' })
]

export const caseFieldsByKind: Record<CaseKind, CaseField[]> = {
  interface: interfaceCaseFields,
  web_automation: webAutomationCaseFields,
  app_automation: appAutomationCaseFields,
  ai_test: aiTestCaseFields,
  functional: functionalCaseFields,
  performance: performanceCaseFields
}

export const builtInCaseTemplates: CaseTemplateDefinition[] = [
  {
    id: 'builtin-interface',
    name: '接口用例',
    kind: 'interface',
    builtIn: true,
    enabled: true,
    fieldKeys: interfaceCaseFields.map((field) => field.key)
  },
  {
    id: 'builtin-web',
    name: 'Web 用例',
    kind: 'web_automation',
    builtIn: true,
    enabled: true,
    fieldKeys: webAutomationCaseFields.map((field) => field.key)
  },
  {
    id: 'builtin-app',
    name: 'APP 用例',
    kind: 'app_automation',
    builtIn: true,
    enabled: true,
    fieldKeys: appAutomationCaseFields.map((field) => field.key)
  },
  {
    id: 'builtin-ai',
    name: 'AI 用例',
    kind: 'ai_test',
    builtIn: true,
    enabled: true,
    fieldKeys: aiTestCaseFields.map((field) => field.key)
  },
  {
    id: 'builtin-functional',
    name: '功能用例',
    kind: 'functional',
    builtIn: true,
    enabled: true,
    fieldKeys: functionalCaseFields.map((field) => field.key)
  },
  {
    id: 'builtin-performance',
    name: '性能用例',
    kind: 'performance',
    builtIn: true,
    enabled: true,
    fieldKeys: performanceCaseFields.map((field) => field.key)
  }
]

export const defaultCaseTypes: CaseTypeDefinition[] = [
  { id: 'positive', name: '正向', appliesTo: 'all', enabled: true },
  { id: 'required_missing', name: '必填缺失', appliesTo: 'all', enabled: true },
  { id: 'type_error', name: '参数类型错误', appliesTo: 'interface', enabled: true },
  { id: 'boundary', name: '边界值', appliesTo: 'all', enabled: true },
  { id: 'auth_missing', name: '鉴权缺失', appliesTo: 'interface', enabled: true },
  { id: 'permission_denied', name: '权限不足', appliesTo: 'interface', enabled: true },
  { id: 'not_found', name: '数据不存在', appliesTo: 'all', enabled: true },
  { id: 'duplicate_submit', name: '重复提交', appliesTo: 'interface', enabled: true },
  { id: 'business_exception', name: '业务规则异常', appliesTo: 'all', enabled: true }
]

export const visibleExportFields = (fields: CaseField[]) =>
  fields.filter((field) => field.visible && field.exportable)
