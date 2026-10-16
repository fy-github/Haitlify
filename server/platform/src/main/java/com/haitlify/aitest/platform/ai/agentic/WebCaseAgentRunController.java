package com.haitlify.aitest.platform.ai.agentic;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/web-case-agent-runs")
class WebCaseAgentRunController {
    private final WebCaseAgentRunService runs;
    private final WebCaseAgentEventService events;
    private final com.haitlify.aitest.platform.ai.agentic.locator.LocatorKnowledgeService locatorKnowledge;
    WebCaseAgentRunController(WebCaseAgentRunService runs, WebCaseAgentEventService events, com.haitlify.aitest.platform.ai.agentic.locator.LocatorKnowledgeService locatorKnowledge) { this.runs = runs; this.events = events; this.locatorKnowledge = locatorKnowledge; }
    @PostMapping WebCaseAgentModels.WebCaseAgentRunResponse create(@RequestBody WebCaseAgentModels.WebCaseAgentStartRequest request) { return runs.create(request); }
    @GetMapping("/{runId}") WebCaseAgentModels.WebCaseAgentRunResponse get(@PathVariable String runId) { locatorKnowledge.authorizeRun(runId); return runs.get(runId); }
    @GetMapping("/{runId}/events") List<WebCaseAgentModels.WebCaseAgentEventResponse> events(@PathVariable String runId, @RequestParam(defaultValue = "0") long after) { locatorKnowledge.authorizeRun(runId); return events.list(runId, after); }
    @GetMapping("/{runId}/artifacts") List<WebCaseAgentModels.GeneratedCaseArtifact> artifacts(@PathVariable String runId) { locatorKnowledge.authorizeRun(runId); return runs.artifacts(runId); }
    @GetMapping("/{runId}/artifacts/export") List<WebCaseAgentModels.GeneratedCaseArtifact> exportArtifacts(@PathVariable String runId) { locatorKnowledge.authorizeRun(runId); return runs.acceptedArtifacts(runId); }
    @GetMapping("/{runId}/artifacts/{artifactId}/revisions/{revision}/export") WebCaseAgentModels.GeneratedCaseArtifact exportArtifactRevision(@PathVariable String runId, @PathVariable String artifactId, @PathVariable int revision) { locatorKnowledge.authorizeRun(runId); locatorKnowledge.authorizeArtifact(artifactId); return runs.acceptedArtifactRevision(runId, artifactId, revision); }
    @GetMapping("/{runId}/artifacts/{artifactId}/revision") WebCaseAgentModels.WebCaseAgentArtifactRevisionResponse revision(@PathVariable String runId, @PathVariable String artifactId) { locatorKnowledge.authorizeRun(runId); locatorKnowledge.authorizeArtifact(artifactId); return runs.revision(runId, artifactId); }
    @PostMapping("/{runId}/artifacts/{artifactId}/decision") void decideArtifact(@PathVariable String runId, @PathVariable String artifactId, @RequestBody WebCaseAgentModels.PlanDecisionRequest request) { locatorKnowledge.authorizeRun(runId); locatorKnowledge.authorizeArtifact(artifactId); runs.confirmPlan(artifactId, request.accepted(), request.revision()); }
    @PostMapping("/{runId}/artifacts/confirm-all") void confirmAllPlans(@PathVariable String runId) { locatorKnowledge.authorizeRun(runId); runs.confirmAllPlans(runId); }
    @PutMapping("/artifacts/{artifactId}") void updatePlan(@PathVariable String artifactId, @RequestBody WebCaseAgentModels.PlanUpdateRequest request) { locatorKnowledge.authorizeArtifact(artifactId); runs.updatePlan(artifactId, request.candidateJson(), request.revision()); }
    @PostMapping("/{runId}/cancel") WebCaseAgentModels.WebCaseAgentRunResponse cancel(@PathVariable String runId) { locatorKnowledge.authorizeRun(runId); runs.requestCancel(runId); return runs.get(runId); }
    @PostMapping("/{runId}/retry") WebCaseAgentModels.WebCaseAgentRunResponse retry(@PathVariable String runId) { locatorKnowledge.authorizeRun(runId); return runs.retry(runId); }
    @PostMapping("/{runId}/resume") WebCaseAgentModels.WebCaseAgentRunResponse resume(@PathVariable String runId) { locatorKnowledge.authorizeRun(runId); return runs.resume(runId); }

    /** T10/T11 gate：过期 revision 必须返回冲突（409），不得静默覆盖。 */
    @ExceptionHandler(StaleArtifactRevisionException.class)
    ResponseEntity<Map<String, String>> handleStaleRevision(StaleArtifactRevisionException exception) {
        return ResponseEntity.status(409).body(Map.of("code", "stale_revision", "message", exception.getMessage()));
    }
}

