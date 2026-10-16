import { describe, expect, it } from 'vitest'
import haitlify from '../../Haitlify.openapi.json'
import ucenter from '../../UcenterAPI.openapi.json'
import { generateInterfaceCases } from './caseGenerator'
import { parseOpenApiDocument } from './openapiParser'

describe('local Apifox/OpenAPI samples', () => {
  it('parses Haitlify and generates risk-based interface cases', () => {
    const parsed = parseOpenApiDocument(haitlify, 'Haitlify.openapi.json')
    const cases = generateInterfaceCases({
      project: parsed.title,
      sourceFile: parsed.sourceFile,
      operations: parsed.operations.slice(0, 3),
      granularity: 'risk'
    })

    expect(parsed.operations.length).toBeGreaterThan(100)
    expect(cases.length).toBeGreaterThan(3)
    expect(cases[0].row.project).toBe(parsed.title)
    expect(cases[0].row.sourceFile).toBe('Haitlify.openapi.json')
  })

  it('parses UcenterAPI and keeps operation metadata for export adapters', () => {
    const parsed = parseOpenApiDocument(ucenter, 'UcenterAPI.openapi.json')
    const cases = generateInterfaceCases({
      project: parsed.title,
      sourceFile: parsed.sourceFile,
      operations: parsed.operations.slice(0, 2),
      granularity: 'basic'
    })

    expect(parsed.operations.length).toBeGreaterThan(90)
    expect(cases[0].metadata.sourceFile).toBe('UcenterAPI.openapi.json')
    expect(cases[0].metadata.path).toBe(cases[0].row.path)
  })
})
