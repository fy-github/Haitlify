package com.haitlify.aitest.platform.ai.assistant;

import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.haitlify.aitest.platform.ai.agentic.StaleArtifactRevisionException;

import tools.jackson.databind.JsonNode;

/**
 * T12：组合任务入口。当前仅开放首个案例模板 bug_log_regression（Bug→日志→回归），
 * 依赖节点复用共享 evidence manifest；节点评审走与单任务一致的人工门。
 */
@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/assistant/composites")
class AiAssistantCompositeController {

    private final AiAssistantCompositeService compositeService;

    AiAssistantCompositeController(AiAssistantCompositeService compositeService) {
        this.compositeService = compositeService;
    }

    record StartRequest(String template, Long projectId, JsonNode inputs, String actorId) {
    }

    record NodeReviewRequest(String decision, Integer revision, JsonNode candidate) {
    }

    @GetMapping("/templates")
    Map<String, Object> templates() {
        return Map.of("templates", List.of(Map.of(
            "template", "bug_log_regression",
            "displayName", "Bug→日志→回归",
            "nodes", List.of(
                Map.of("nodeId", "bug", "taskType", "bug_analysis", "dependsOn", List.of()),
                Map.of("nodeId", "log", "taskType", "log_triage", "dependsOn", List.of("bug")),
                Map.of("nodeId", "regression", "taskType", "regression_planning", "dependsOn", List.of("log"))
            ),
            "notes", List.of(
                "依赖节点复用同一 evidence manifest",
                "依赖未满足（上游未确认/被拒/阻塞）的节点只标记 skipped 并给出原因",
                "每个节点独立状态与产物，逐节点人工评审"
            )
        )));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    AiAssistantCompositeService.CompositeRun start(@RequestBody StartRequest request) {
        return compositeService.start(request.template(), request.projectId(), request.inputs(), request.actorId());
    }

    @GetMapping("/{compositeId}")
    AiAssistantCompositeService.CompositeRun get(@PathVariable String compositeId) {
        return compositeService.get(compositeId);
    }

    @PostMapping("/{compositeId}/nodes/{nodeId}/review")
    @ResponseStatus(HttpStatus.OK)
    AiAssistantCompositeService.CompositeRun reviewNode(@PathVariable String compositeId,
                                                       @PathVariable String nodeId,
                                                       @RequestBody NodeReviewRequest request) {
        return compositeService.reviewNode(compositeId, nodeId, request.decision(), request.revision(),
            request.candidate());
    }

    @ExceptionHandler(StaleArtifactRevisionException.class)
    ResponseEntity<Map<String, String>> staleRevision(StaleArtifactRevisionException exception) {
        return ResponseEntity.status(409)
            .body(Map.of("code", "stale_revision", "message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(400).body(Map.of("code", "invalid_request", "message", exception.getMessage()));
    }
}
