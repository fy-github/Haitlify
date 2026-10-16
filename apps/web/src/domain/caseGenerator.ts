import type { ParsedOperation, ParsedParameter } from './openapiParser'

export type GenerationGranularity = 'basic' | 'risk' | 'full'

export interface GenerateInterfaceCasesInput {
  project: string
  sourceFile: string
  operations: ParsedOperation[]
  granularity: GenerationGranularity
}

export interface GeneratedInterfaceCase {
  id: string
  row: InterfaceCaseRow
  metadata: InterfaceCaseMetadata
}

export interface InterfaceCaseRow {
  caseId: string
  project: string
  module: string
  interfaceName: string
  method: string
  path: string
  title: string
  caseType: string
  precondition: string
  headers: string
  pathParams: string
  queryParams: string
  body: string
  steps: string
  expectedResult: string
  expectedResponseBody?: string
  actualResponseBody?: string
  assertions: string
  priority: string
  executionResult: string
  executor: string
  executedAt: string
  defectId: string
  tags: string
  sourceFile: string
  remark: string
}

export interface InterfaceCaseMetadata {
  sourceSpecId: string
  sourceFile: string
  sourceOperationId: string
  tag: string
  method: string
  path: string
  baseUrlRef: string
  environmentId: string
  authProfileId: string
  contentType: string
  headers: Record<string, string>
  pathParams: Record<string, string>
  queryParams: Record<string, string>
  body: unknown
  expectedStatus: number
  assertions: string[]
  expectedBodyRules: string[]
  expectedResponseBody?: unknown
  variableExtractions: string[]
  dependsOnCaseIds: string[]
  runOrder: number
  generationMeta: {
    granularity: GenerationGranularity
    strategy: string
  }
  reviewMeta: {
    status: '待复核'
  }
  exportMeta: {
    apifoxReady: boolean
  }
}

interface CaseRecipe {
  type: string
  titleSuffix: string
  priority: string
  expectedStatus: number
  expectedResult: string
  mutate?: (metadata: InterfaceCaseMetadata, operation: ParsedOperation) => void
}

export const generateInterfaceCases = (
  input: GenerateInterfaceCasesInput
): GeneratedInterfaceCase[] => {
  let index = 1
  const cases: GeneratedInterfaceCase[] = []

  for (const operation of input.operations) {
    for (const recipe of recipesFor(input.granularity, operation)) {
      const metadata = buildMetadata(input, operation, recipe, index)
      recipe.mutate?.(metadata, operation)
      const caseId = `API-${String(index).padStart(4, '0')}`
      const row = buildRow(input, operation, recipe, metadata, caseId)

      cases.push({
        id: caseId,
        row,
        metadata
      })
      index += 1
    }
  }

  return cases
}

export const estimateCaseCount = (
  operations: ParsedOperation[],
  granularity: GenerationGranularity
): number => operations.reduce((sum, operation) => sum + recipesFor(granularity, operation).length, 0)

const recipesFor = (
  granularity: GenerationGranularity,
  operation: ParsedOperation
): CaseRecipe[] => {
  const recipes: CaseRecipe[] = [
    {
      type: '正向',
      titleSuffix: '正常请求成功',
      priority: 'P1',
      expectedStatus: successStatus(operation),
      expectedResult: '接口返回成功状态码，响应结构符合接口定义。',
      mutate: (metadata) => {
        metadata.assertions.push('响应状态码为成功状态码')
      }
    },
    {
      type: '必填缺失',
      titleSuffix: '缺少必填参数',
      priority: 'P1',
      expectedStatus: 400,
      expectedResult: '接口返回参数校验失败，错误信息指出缺失的必填字段。',
      mutate: (metadata, currentOperation) => {
        const requiredBodyField = currentOperation.requestBody?.requiredFields[0]
        if (requiredBodyField && isRecord(metadata.body)) {
          delete metadata.body[requiredBodyField]
        }
      }
    }
  ]

  if (granularity === 'basic') {
    return [...recipes, authMissingRecipe()]
  }

  recipes.push(typeErrorRecipe(), authMissingRecipe(), permissionRecipe())

  if (operation.responseStatuses.includes('404')) {
    recipes.push(notFoundRecipe())
  }
  if (operation.method === 'POST' || operation.responseStatuses.includes('409')) {
    recipes.push(duplicateRecipe())
  }

  recipes.push(businessRuleRecipe())

  if (granularity === 'full') {
    recipes.splice(3, 0, boundaryRecipe())
  }

  return recipes
}

const buildMetadata = (
  input: GenerateInterfaceCasesInput,
  operation: ParsedOperation,
  recipe: CaseRecipe,
  runOrder: number
): InterfaceCaseMetadata => ({
  sourceSpecId: `${input.sourceFile}:${operation.operationId}`,
  sourceFile: input.sourceFile,
  sourceOperationId: operation.operationId,
  tag: operation.module,
  method: operation.method,
  path: operation.path,
  baseUrlRef: '',
  environmentId: '',
  authProfileId: '',
  contentType: operation.requestBody?.contentTypes[0] || 'application/json',
  headers: parameterRecord(operation.parameters, 'header'),
  pathParams: parameterRecord(operation.parameters, 'path'),
  queryParams: parameterRecord(operation.parameters, 'query'),
  body: operation.requestBody?.example || sampleBody(operation),
  expectedStatus: recipe.expectedStatus,
  assertions: [`响应状态码为 ${recipe.expectedStatus}`],
  expectedBodyRules: [],
  expectedResponseBody: operation.expectedResponseBody,
  variableExtractions: [],
  dependsOnCaseIds: [],
  runOrder,
  generationMeta: {
    granularity: input.granularity,
    strategy: 'deterministic-risk-rules'
  },
  reviewMeta: {
    status: '待复核'
  },
  exportMeta: {
    apifoxReady: false
  }
})

const buildRow = (
  input: GenerateInterfaceCasesInput,
  operation: ParsedOperation,
  recipe: CaseRecipe,
  metadata: InterfaceCaseMetadata,
  caseId: string
): InterfaceCaseRow => ({
  caseId,
  project: input.project,
  module: operation.module,
  interfaceName: operation.summary,
  method: operation.method,
  path: operation.path,
  title: `${operation.summary}-${recipe.titleSuffix}`,
  caseType: recipe.type,
  precondition: metadata.headers.Authorization === '' ? '不携带认证信息' : '已准备有效环境和测试账号',
  headers: stringify(metadata.headers),
  pathParams: stringify(metadata.pathParams),
  queryParams: stringify(metadata.queryParams),
  body: stringify(metadata.body),
  steps: `1. 按接口定义发起 ${operation.method} 请求\n2. 观察响应状态码和响应体`,
  expectedResult: recipe.expectedResult,
  expectedResponseBody: stringify(metadata.expectedResponseBody || ''),
  actualResponseBody: '',
  assertions: metadata.assertions.join('\n'),
  priority: recipe.priority,
  executionResult: '未执行',
  executor: '',
  executedAt: '',
  defectId: '',
  tags: [operation.module, recipe.type, 'AI生成'].join(','),
  sourceFile: input.sourceFile,
  remark: ''
})

const authMissingRecipe = (): CaseRecipe => ({
  type: '鉴权缺失',
  titleSuffix: '未携带认证信息',
  priority: 'P0',
  expectedStatus: 401,
  expectedResult: '接口拒绝请求并返回未认证错误。',
  mutate: (metadata) => {
    metadata.headers.Authorization = ''
  }
})

const typeErrorRecipe = (): CaseRecipe => ({
  type: '参数类型错误',
  titleSuffix: '参数类型错误',
  priority: 'P1',
  expectedStatus: 400,
  expectedResult: '接口返回参数类型校验失败，错误信息明确指出异常参数。'
})

const boundaryRecipe = (): CaseRecipe => ({
  type: '边界值',
  titleSuffix: '边界值校验',
  priority: 'P2',
  expectedStatus: 400,
  expectedResult: '接口对边界值进行明确校验，不产生异常数据。'
})

const permissionRecipe = (): CaseRecipe => ({
  type: '权限不足',
  titleSuffix: '权限不足',
  priority: 'P0',
  expectedStatus: 403,
  expectedResult: '接口拒绝无权限账号访问并返回权限错误。'
})

const notFoundRecipe = (): CaseRecipe => ({
  type: '数据不存在',
  titleSuffix: '数据不存在',
  priority: 'P1',
  expectedStatus: 404,
  expectedResult: '接口返回数据不存在错误，不产生脏数据。'
})

const duplicateRecipe = (): CaseRecipe => ({
  type: '重复提交',
  titleSuffix: '重复提交',
  priority: 'P1',
  expectedStatus: 409,
  expectedResult: '接口识别重复请求并返回冲突或幂等结果。'
})

const businessRuleRecipe = (): CaseRecipe => ({
  type: '业务规则异常',
  titleSuffix: '业务规则异常',
  priority: 'P1',
  expectedStatus: 400,
  expectedResult: '接口返回明确的业务规则错误信息。'
})

const successStatus = (operation: ParsedOperation): number => {
  const success = operation.responseStatuses.find((status) => status.startsWith('2'))
  return success ? Number(success) : 200
}

const parameterRecord = (
  parameters: ParsedParameter[],
  location: ParsedParameter['in']
): Record<string, string> =>
  Object.fromEntries(
    parameters.filter((parameter) => parameter.in === location).map((parameter) => [parameter.name, sampleValue(parameter)])
  )

const sampleBody = (operation: ParsedOperation): Record<string, string> =>
  Object.fromEntries(
    Object.entries(operation.requestBody?.properties || {}).map(([name, type]) => [
      name,
      type === 'number' || type === 'integer' ? '1' : `${name}-value`
    ])
  )

const sampleValue = (parameter: ParsedParameter): string =>
  parameter.name.toLowerCase() === 'authorization' ? 'Bearer {{token}}' : `${parameter.name}-value`

const stringify = (value: unknown): string =>
  typeof value === 'string' ? value : JSON.stringify(value, null, 2)

const isRecord = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value)
