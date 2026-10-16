// Browser-grounded generation makes two model calls and carries live DOM evidence per batch.
export const CASE_GENERATION_BATCH_SIZE = 3

/** Keeps AI requests repeatable even when the selection UI emits duplicate ids. */
export const chunkCaseGenerationPointIds = (
  pointIds: readonly string[],
  batchSize = CASE_GENERATION_BATCH_SIZE
): string[][] => {
  if (!Number.isInteger(batchSize) || batchSize < 1) {
    throw new Error('批处理大小必须为正整数')
  }

  const stableIds = Array.from(new Set(pointIds.filter((pointId) => pointId.trim().length > 0)))
  return Array.from({ length: Math.ceil(stableIds.length / batchSize) }, (_, index) =>
    stableIds.slice(index * batchSize, (index + 1) * batchSize)
  )
}

/** Returns the offset to pass to generated Web cases so appended batches remain unique. */
export const generatedWebCaseIdOffset = (
  cases: ReadonlyArray<{ id: string; row?: { caseId?: string } }>
): number => cases.reduce((maximum, testCase) => {
  const candidates = [testCase.id, testCase.row?.caseId]
  for (const candidate of candidates) {
    const match = candidate?.match(/^WEB-AI-(\d+)$/)
    if (match) maximum = Math.max(maximum, Number(match[1]))
  }
  return maximum
}, 0)
