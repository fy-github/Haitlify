export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD' | 'OPTIONS'

export interface ParsedOpenApiDocument {
  title: string
  version: string
  description: string
  sourceFile: string
  operations: ParsedOperation[]
}

export interface ParsedOperation {
  id: string
  module: string
  method: HttpMethod
  path: string
  summary: string
  operationId: string
  parameters: ParsedParameter[]
  requestBody?: ParsedRequestBody
  responseStatuses: string[]
  expectedResponseBody?: unknown
}

export interface ParsedParameter {
  name: string
  in: 'path' | 'query' | 'header' | 'cookie'
  required: boolean
  type: string
  description: string
}

export interface ParsedRequestBody {
  required: boolean
  contentTypes: string[]
  requiredFields: string[]
  properties: Record<string, string>
  example?: unknown
}

export interface OperationFilter {
  module?: string
  method?: HttpMethod | 'ALL'
  pathKeyword?: string
  summaryKeyword?: string
}

const httpMethods = new Set(['get', 'post', 'put', 'patch', 'delete', 'head', 'options'])

export const parseOpenApiDocument = (
  input: unknown,
  sourceFile = 'openapi.json'
): ParsedOpenApiDocument => {
  const document = asRecord(input)
  const info = asRecord(document.info)
  const paths = asRecord(document.paths)
  const operations: ParsedOperation[] = []

  for (const [path, pathItemValue] of Object.entries(paths)) {
    const pathItem = asRecord(pathItemValue)
    const pathParameters = readParameters(pathItem.parameters)

    for (const [methodKey, operationValue] of Object.entries(pathItem)) {
      if (!httpMethods.has(methodKey)) continue

      const operation = asRecord(operationValue)
      const method = methodKey.toUpperCase() as HttpMethod
      const tags = Array.isArray(operation.tags) ? operation.tags.map(String) : []
      const summary = stringValue(operation.summary) || stringValue(operation.description) || `${method} ${path}`
      const operationId = stringValue(operation.operationId) || `${method.toLowerCase()}-${path}`
      const parameters = [...pathParameters, ...readParameters(operation.parameters)]

      operations.push({
        id: `${methodKey} ${path}`,
        module: tags[0] || '未分组',
        method,
        path,
        summary,
        operationId,
        parameters,
        requestBody: readRequestBody(operation.requestBody),
        responseStatuses: Object.keys(asRecord(operation.responses)),
        expectedResponseBody: readExpectedResponseBody(operation.responses)
      })
    }
  }

  return {
    title: stringValue(info.title) || '未命名项目',
    version: stringValue(info.version),
    description: stringValue(info.description),
    sourceFile,
    operations
  }
}

const readExpectedResponseBody = (value: unknown): unknown => {
  const responses = asRecord(value)
  const successEntry = Object.entries(responses).find(([status]) => status.startsWith('2'))
  if (!successEntry) return undefined

  const response = asRecord(successEntry[1])
  const content = asRecord(response.content)
  const contentType = Object.keys(content).find((item) => item.includes('json')) || Object.keys(content)[0]
  if (!contentType) return undefined

  const media = asRecord(content[contentType])
  if (media.example !== undefined) return media.example

  const examples = asRecord(media.examples)
  const firstExample = asRecord(Object.values(examples)[0])
  if (firstExample.value !== undefined) return firstExample.value

  return sampleFromSchema(asRecord(media.schema))
}

export const filterOperations = (
  operations: ParsedOperation[],
  filter: OperationFilter
): ParsedOperation[] =>
  operations.filter((operation) => {
    if (filter.module && operation.module !== filter.module) return false
    if (filter.method && filter.method !== 'ALL' && operation.method !== filter.method) return false
    if (filter.pathKeyword && !operation.path.includes(filter.pathKeyword)) return false
    if (
      filter.summaryKeyword &&
      !operation.summary.toLowerCase().includes(filter.summaryKeyword.toLowerCase())
    ) {
      return false
    }
    return true
  })

const readParameters = (value: unknown): ParsedParameter[] => {
  if (!Array.isArray(value)) return []

  return value.map((item) => {
    const parameter = asRecord(item)
    const schema = asRecord(parameter.schema)

    return {
      name: stringValue(parameter.name),
      in: parameterLocation(parameter.in),
      required: Boolean(parameter.required),
      type: stringValue(schema.type) || 'string',
      description: stringValue(parameter.description)
    }
  })
}

const readRequestBody = (value: unknown): ParsedRequestBody | undefined => {
  const requestBody = asRecord(value)
  const content = asRecord(requestBody.content)
  const contentTypes = Object.keys(content)

  if (contentTypes.length === 0) return undefined

  const firstContent = asRecord(content[contentTypes[0]])
  const schema = asRecord(firstContent.schema)
  const properties = Object.fromEntries(
    Object.entries(asRecord(schema.properties)).map(([name, propertyValue]) => [
      name,
      stringValue(asRecord(propertyValue).type) || 'object'
    ])
  )

  return {
    required: Boolean(requestBody.required),
    contentTypes,
    requiredFields: Array.isArray(schema.required) ? schema.required.map(String) : [],
    properties,
    example: firstContent.example
  }
}

const sampleFromSchema = (schema: Record<string, unknown>): unknown => {
  if (schema.example !== undefined) return schema.example
  const type = stringValue(schema.type)
  if (type === 'array') return [sampleFromSchema(asRecord(schema.items))]
  if (type === 'object' || Object.keys(asRecord(schema.properties)).length > 0) {
    return Object.fromEntries(
      Object.entries(asRecord(schema.properties)).map(([name, value]) => [name, sampleFromSchema(asRecord(value))])
    )
  }
  if (type === 'number' || type === 'integer') return 0
  if (type === 'boolean') return true
  return ''
}

const parameterLocation = (value: unknown): ParsedParameter['in'] => {
  if (value === 'path' || value === 'query' || value === 'header' || value === 'cookie') {
    return value
  }
  return 'query'
}

const asRecord = (value: unknown): Record<string, unknown> =>
  value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {}

const stringValue = (value: unknown): string => (typeof value === 'string' ? value : '')
