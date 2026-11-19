import { describe, expect, it } from 'vitest'
import appSource from './App.vue?raw'

describe('legacy configuration retirement', () => {
  it('removes the retired legacy AI domain modules from the bundle graph', () => {
    const modules = Object.keys(import.meta.glob('./domain/*.ts')).map((p) => p.replace('./domain/', ''))
    const retired = ['aiPlatformRepository.ts', 'aiConfigRepository.ts', 'webCaseImport.ts',
      'caseDesignDraftRepository.ts', 'caseDesignWorkflow.ts', 'interfaceCaseWorkflow.ts',
      'aiMultiAgentRunView.ts', 'legacyReviewTypes.ts', 'aiAnalysisProgress.ts', 'aiDraftTestPointView.ts',
      'aiAnalysisCoverageView.ts', 'aiRunEventLog.ts', 'aiVisualRequirementView.ts',
      'caseDesignReviewView.ts', 'caseDesignSourceView.ts', 'caseDesignDelivery.ts', 'caseReview.ts',
      'caseSupplement.ts', 'caseEnrichment.ts', 'caseGenerationBatch.ts', 'designLocatorView.ts',
      'designOriginFailureView.ts', 'figmaDesignImport.ts', 'figmaDesignRepository.ts',
      'requirementDocumentPayload.ts', 'requirementDocumentRepository.ts', 'designAutomationDraft.ts']
    for (const name of retired) expect(modules, name).not.toContain(name)
    expect(modules).toContain('assistantChainRepository.ts')
    expect(modules).toContain('webAutomationRunRepository.ts')
  })
  it('removes the retired in-app case design script pipeline', () => {
    for (const symbol of ['monitorAiAnalysisRun', 'buildCaseDesignWorkflowView', 'deliverGeneratedCases',
      'persistCaseDesignDraft', 'promptFigmaDesignImport', 'transferGeneratedCasesToAutomation',
      'supplementMissingCases', 'pickRequirementSourceFiles', 'aiDraftPoints', 'aiAnalysisRun',
      'generatedWebCases', 'caseDesignDeliveryView', 'caseDesignReviewFindings']) {
      expect(appSource, symbol).not.toMatch(new RegExp(`\\b${symbol}\\b`))
    }
  })
  it('removes the retired Web Case Agent transport module, including type-only coupling', () => {
    const modules = import.meta.glob('./domain/*Repository.ts')
    expect(Object.keys(modules)).not.toContain('./domain/webCaseAgentRepository.ts')
    expect(appSource).not.toContain("from './domain/webCaseAgentRepository'")
    expect(appSource).not.toContain('legacyWebCaseTypes')
  })
  it('removes legacy Web Case Agent execution without removing normal Runner execution', () => {
    for (const symbol of ['generateNaturalLanguageCasesWithAi', 'monitorWebCaseAgentRun',
      'generateWebCasesWithAi', 'compileWebAutomationCases', 'cancelAiCaseGeneration',
      'appendAgentArtifacts', 'startWebCaseAgentRun', 'confirmAllWebCasePlanArtifacts',
      'getWebCaseAgentRun', 'listWebCaseAgentEvents', 'listWebCaseAgentArtifacts', 'cancelWebCaseAgentRun']) {
      expect(appSource, symbol).not.toMatch(new RegExp(`\\b${symbol}\\b`))
    }
    expect(appSource).toContain('await createWebAutomationRun(casesToRun,')
    expect(appSource).toContain('await getWebAutomationRun(runId)')
    expect(appSource).toContain('appendAutomationReport(localStorage, reportRecord)')
  })
  it('does not retain the retired multi-agent HTTP call chain', () => {
    for (const symbol of ['aiMultiAgentRunRepository', 'startAiReview', 'startCaseAiReview',
      'startRequirementAiReview', 'mergeAcceptedReviewFinding', 'mergeMultiAgentReview',
      'loadMultiAgentReviewDetails', 'ignoreUnresolvedMultiAgentConflicts', 'ignoreSingleReviewConflict']) {
      expect(appSource, symbol).not.toMatch(new RegExp(`\\b${symbol}\\b`))
    }
  })
  it('retires the in-app Figma/AI requirement import prompt', () => {
    for (const symbol of ['aiFigmaRequirementRepository', 'aiFigmaRequirementView',
      'importStructuredAiFigma', 'reloadAiFigmaWorkspace', 'analyzeAiFigmaFile', 'analyzeSelectedAiFigmaNode',
      'promptFigmaDesignImport', 'parseFigmaDesignInput']) {
      expect(appSource, symbol).not.toContain(symbol)
    }
  })
  it('does not wire legacy model configuration or readiness into the application', () => {
    for (const symbol of ['useAiConfig', 'checkAiEnvironmentBeforeAnalysis', 'checkAiEnvironmentReadiness',
      'loadAiConfigFromPlatform', 'listAiRunPolicies', 'handleCaseDesignPrimaryAction',
      'analyzeRequirementPointsWithAi', 'retryCurrentAiAnalysisRun']) {
      expect(appSource, symbol).not.toMatch(new RegExp(`\\b${symbol}\\b`))
    }
  })
})
