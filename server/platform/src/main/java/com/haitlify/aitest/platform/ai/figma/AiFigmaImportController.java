package com.haitlify.aitest.platform.ai.figma;

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/figma")
class AiFigmaImportController {

    private final AiFigmaImportService importService;
    private final AiFigmaNodeService nodeService;
    private final AiFigmaAnalysisService analysisService;
    private final AiFigmaChangeSetService changeSetService;
    private final AiFigmaElementCandidateService elementCandidateService;

    AiFigmaImportController(
        AiFigmaImportService importService,
        AiFigmaNodeService nodeService,
        AiFigmaAnalysisService analysisService,
        AiFigmaChangeSetService changeSetService,
        AiFigmaElementCandidateService elementCandidateService
    ) {
        this.importService = importService;
        this.nodeService = nodeService;
        this.analysisService = analysisService;
        this.changeSetService = changeSetService;
        this.elementCandidateService = elementCandidateService;
    }

    @PostMapping("/import")
    AiFigmaFileResponse importFigma(@RequestBody ImportAiFigmaRequest request) {
        return importService.importFigma(request);
    }

    @GetMapping("/files/{figmaFileId}")
    AiFigmaFileResponse getFile(@PathVariable String figmaFileId) {
        return importService.getFile(figmaFileId);
    }

    @GetMapping("/files/{figmaFileId}/nodes")
    AiFigmaNodeListResponse listNodes(@PathVariable String figmaFileId) {
        return nodeService.listNodes(figmaFileId);
    }

    @GetMapping("/nodes/{figmaNodeId}")
    AiFigmaNodeResponse getNode(@PathVariable String figmaNodeId) {
        return nodeService.getNode(figmaNodeId);
    }

    @PostMapping("/files/{figmaFileId}/analysis-runs")
    AiFigmaAnalysisRunResponse startFileAnalysis(@PathVariable String figmaFileId) {
        return analysisService.startFileAnalysis(figmaFileId);
    }

    @PostMapping("/nodes/{figmaNodeId}/analysis-runs")
    AiFigmaAnalysisRunResponse startNodeAnalysis(@PathVariable String figmaNodeId) {
        return analysisService.startNodeAnalysis(figmaNodeId);
    }

    @GetMapping("/analysis-runs/{runId}/draft-test-points")
    AiFigmaDraftPointListResponse listDraftPoints(@PathVariable String runId) {
        return analysisService.listDraftPoints(runId);
    }

    @PostMapping("/files/{figmaFileId}/refresh")
    AiFigmaChangeSetResponse refreshFile(@PathVariable String figmaFileId, @RequestBody RefreshAiFigmaFileRequest request) {
        return changeSetService.refreshFile(figmaFileId, request);
    }

    @GetMapping("/files/{figmaFileId}/change-sets")
    AiFigmaChangeSetListResponse listChangeSets(@PathVariable String figmaFileId) {
        return changeSetService.listChangeSets(figmaFileId);
    }

    @GetMapping("/change-sets/{changeSetId}")
    AiFigmaChangeSetResponse getChangeSet(@PathVariable String changeSetId) {
        return changeSetService.getChangeSet(changeSetId);
    }

    @GetMapping("/files/{figmaFileId}/element-candidates")
    AiFigmaElementCandidateListResponse listElementCandidates(@PathVariable String figmaFileId) {
        return elementCandidateService.listCandidates(figmaFileId);
    }

    /** 全链路人工确认契约：可选 body 携带 expectedRevision（缺省保持兼容；过期 409）。 */
    @PostMapping("/element-candidates/{candidateId}/confirm")
    AiFigmaElementCandidateResponse confirmElementCandidate(
        @PathVariable String candidateId,
        @org.springframework.web.bind.annotation.RequestBody(required = false) ElementCandidateReviewRequest request) {
        return elementCandidateService.confirm(candidateId, request == null ? null : request.revision());
    }

    @PostMapping("/element-candidates/{candidateId}/reject")
    AiFigmaElementCandidateResponse rejectElementCandidate(
        @PathVariable String candidateId,
        @org.springframework.web.bind.annotation.RequestBody(required = false) ElementCandidateReviewRequest request) {
        return elementCandidateService.reject(candidateId, request == null ? null : request.revision());
    }

    @ExceptionHandler(com.haitlify.aitest.platform.ai.agentic.StaleArtifactRevisionException.class)
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
    java.util.Map<String, String> staleRevision(
        com.haitlify.aitest.platform.ai.agentic.StaleArtifactRevisionException exception) {
        return java.util.Map.of("code", "stale_revision", "message", exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> handleBadRequest(IllegalArgumentException exception) {
        return Map.of("message", exception.getMessage());
    }
}

record ElementCandidateReviewRequest(
    Integer revision
) {
}
