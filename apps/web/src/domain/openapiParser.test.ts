import { describe, expect, it } from 'vitest'
import { filterOperations, parseOpenApiDocument } from './openapiParser'

const fixture = {
  openapi: '3.0.1',
  info: {
    title: 'Haitlify',
    version: '1.0.0',
    description: 'Cloud platform API'
  },
  paths: {
    '/api/projects/{projectId}/members': {
      get: {
        tags: ['项目成员'],
        summary: '查询项目成员',
        operationId: 'listProjectMembers',
        parameters: [
          {
            name: 'projectId',
            in: 'path',
            required: true,
            schema: { type: 'string' }
          },
          {
            name: 'keyword',
            in: 'query',
            required: false,
            schema: { type: 'string' }
          },
          {
            name: 'Authorization',
            in: 'header',
            required: true,
            schema: { type: 'string' }
          }
        ],
        responses: {
          '200': { description: 'OK' },
          '401': { description: 'Unauthorized' },
          '403': { description: 'Forbidden' }
        }
      },
      post: {
        tags: ['项目成员'],
        summary: '新增项目成员',
        operationId: 'createProjectMember',
        requestBody: {
          required: true,
          content: {
            'application/json': {
              schema: {
                type: 'object',
                required: ['userId'],
                properties: {
                  userId: { type: 'string' },
                  role: { type: 'string' }
                }
              },
              example: {
                userId: 'u-1',
                role: 'admin'
              }
            }
          }
        },
        responses: {
          '201': {
            description: 'Created',
            content: {
              'application/json': {
                example: {
                  code: 0,
                  data: {
                    userId: 'u-1',
                    role: 'admin'
                  }
                }
              }
            }
          },
          '409': { description: 'Conflict' }
        }
      }
    }
  }
}

describe('parseOpenApiDocument', () => {
  it('parses OpenAPI operations with project, module, parameters, body, and responses', () => {
    const parsed = parseOpenApiDocument(fixture, 'Haitlify.openapi.json')

    expect(parsed.title).toBe('Haitlify')
    expect(parsed.version).toBe('1.0.0')
    expect(parsed.sourceFile).toBe('Haitlify.openapi.json')
    expect(parsed.operations).toHaveLength(2)

    expect(parsed.operations[0]).toMatchObject({
      id: 'get /api/projects/{projectId}/members',
      module: '项目成员',
      method: 'GET',
      path: '/api/projects/{projectId}/members',
      summary: '查询项目成员',
      operationId: 'listProjectMembers'
    })
    expect(parsed.operations[0].parameters.map((param) => `${param.in}:${param.name}`)).toEqual([
      'path:projectId',
      'query:keyword',
      'header:Authorization'
    ])
    expect(parsed.operations[0].responseStatuses).toEqual(['200', '401', '403'])

    expect(parsed.operations[1].requestBody?.contentTypes).toEqual(['application/json'])
    expect(parsed.operations[1].requestBody?.requiredFields).toEqual(['userId'])
    expect(parsed.operations[1].requestBody?.example).toEqual({
      userId: 'u-1',
      role: 'admin'
    })
    expect(parsed.operations[1].expectedResponseBody).toEqual({
      code: 0,
      data: {
        userId: 'u-1',
        role: 'admin'
      }
    })
  })
})

describe('filterOperations', () => {
  it('filters by module, method, path keyword, and summary keyword', () => {
    const parsed = parseOpenApiDocument(fixture, 'Haitlify.openapi.json')

    const result = filterOperations(parsed.operations, {
      module: '项目成员',
      method: 'POST',
      pathKeyword: 'members',
      summaryKeyword: '新增'
    })

    expect(result.map((operation) => operation.operationId)).toEqual(['createProjectMember'])
  })
})
