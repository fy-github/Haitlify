import type { ParsedOpenApiDocument } from './openapiParser'

export interface ImportedOpenApiProject {
  id: string
  title: string
  sourceFile: string
  version: string
  operationCount: number
  importedAt: string
  document: ParsedOpenApiDocument
}

export const createImportedOpenApiProject = (
  document: ParsedOpenApiDocument,
  importedAt = new Date().toISOString()
): ImportedOpenApiProject => ({
  id: document.sourceFile || document.title,
  title: document.title,
  sourceFile: document.sourceFile,
  version: document.version,
  operationCount: document.operations.length,
  importedAt,
  document
})

export const upsertImportedProject = (
  projects: ImportedOpenApiProject[],
  project: ImportedOpenApiProject
): ImportedOpenApiProject[] => [
  project,
  ...projects.filter((item) => item.id !== project.id)
]

export const selectImportedProject = (
  projects: ImportedOpenApiProject[],
  projectId: string
): ImportedOpenApiProject | undefined => projects.find((project) => project.id === projectId)
