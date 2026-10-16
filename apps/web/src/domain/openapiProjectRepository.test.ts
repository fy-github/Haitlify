import { describe, expect, it } from 'vitest'
import type { ParsedOpenApiDocument } from './openapiParser'
import {
  createImportedOpenApiProject,
  selectImportedProject,
  upsertImportedProject
} from './openapiProjectRepository'

const makeDocument = (title: string, sourceFile: string, operationCount: number): ParsedOpenApiDocument => ({
  title,
  version: '1.0.0',
  description: '',
  sourceFile,
  operations: Array.from({ length: operationCount }, (_, index) => ({
    id: `get /api/${index}`,
    module: index % 2 === 0 ? '项目' : '设备',
    method: 'GET',
    path: `/api/${index}`,
    summary: `接口 ${index}`,
    operationId: `operation${index}`,
    parameters: [],
    responseStatuses: ['200']
  }))
})

describe('openapiProjectRepository', () => {
  it('creates stable imported project metadata from parsed OpenAPI documents', () => {
    const project = createImportedOpenApiProject(makeDocument('Haitlify', 'Haitlify.openapi.json', 2))

    expect(project).toMatchObject({
      id: 'Haitlify.openapi.json',
      title: 'Haitlify',
      sourceFile: 'Haitlify.openapi.json',
      operationCount: 2
    })
  })

  it('upserts projects by source file and keeps the newest project active', () => {
    const first = createImportedOpenApiProject(makeDocument('Haitlify', 'Haitlify.openapi.json', 2))
    const second = createImportedOpenApiProject(makeDocument('UcenterAPI', 'UcenterAPI.openapi.json', 3))
    const updatedFirst = createImportedOpenApiProject(makeDocument('Haitlify', 'Haitlify.openapi.json', 4))

    const imported = upsertImportedProject(upsertImportedProject(upsertImportedProject([], first), second), updatedFirst)

    expect(imported).toHaveLength(2)
    expect(imported[0].operationCount).toBe(4)
    expect(selectImportedProject(imported, 'UcenterAPI.openapi.json')?.title).toBe('UcenterAPI')
  })
})
