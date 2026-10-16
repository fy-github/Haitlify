import type { GeneratedInterfaceCase } from './caseGenerator'

const AI_REVIEWED_TAG = 'AI已评审'

export const markCasesAsAiReviewed = (cases: GeneratedInterfaceCase[]): GeneratedInterfaceCase[] =>
  cases.map((testCase) => {
    const tags = testCase.row.tags
      .split(',')
      .map((tag) => tag.trim())
      .filter(Boolean)

    if (!tags.includes(AI_REVIEWED_TAG)) {
      tags.push(AI_REVIEWED_TAG)
    }

    return {
      ...testCase,
      row: {
        ...testCase.row,
        tags: tags.join(',')
      }
    }
  })
