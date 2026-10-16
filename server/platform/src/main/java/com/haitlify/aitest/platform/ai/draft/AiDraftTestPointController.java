package com.haitlify.aitest.platform.ai.draft;

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai")
class AiDraftTestPointController {

    private final AiDraftTestPointService aiDraftTestPointService;

    AiDraftTestPointController(AiDraftTestPointService aiDraftTestPointService) {
        this.aiDraftTestPointService = aiDraftTestPointService;
    }

    @GetMapping("/analysis-runs/{runId}/draft-test-points")
    AiDraftTestPointListResponse listByRun(@PathVariable String runId) {
        return aiDraftTestPointService.listByRun(runId);
    }

    @PutMapping("/draft-test-points/{pointId}")
    AiDraftTestPointResponse editConfirm(@PathVariable String pointId, @RequestBody EditDraftPointRequest request) {
        return aiDraftTestPointService.editConfirm(pointId, request);
    }

    @DeleteMapping("/draft-test-points/{pointId}")
    void delete(@PathVariable String pointId) {
        aiDraftTestPointService.delete(pointId);
    }

    @PostMapping("/draft-test-points/{pointId}/confirm")
    AiDraftTestPointResponse confirm(@PathVariable String pointId, @RequestBody(required = false) ReviewDraftPointRequest request) {
        return aiDraftTestPointService.confirm(pointId, request);
    }

    @PostMapping("/draft-test-points/{pointId}/reject")
    AiDraftTestPointResponse reject(@PathVariable String pointId, @RequestBody(required = false) ReviewDraftPointRequest request) {
        return aiDraftTestPointService.reject(pointId, request);
    }

    @PostMapping("/draft-test-points/generate-cases")
    GeneratedCaseListResponse generateCases(@RequestBody GenerateCasesRequest request) {
        return aiDraftTestPointService.generateCases(request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> handleBadRequest(IllegalArgumentException exception) {
        return Map.of("message", exception.getMessage());
    }

    /** 全链路人工确认契约：过期/并发 revision 冲突返回 409（与 assistant 面同形）。 */
    @ExceptionHandler(com.haitlify.aitest.platform.ai.agentic.StaleArtifactRevisionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, String> handleStaleRevision(com.haitlify.aitest.platform.ai.agentic.StaleArtifactRevisionException exception) {
        return Map.of("code", "stale_revision", "message", exception.getMessage());
    }
}
