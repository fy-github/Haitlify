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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.haitlify.aitest.platform.ai.agentic.StaleArtifactRevisionException;

import tools.jackson.databind.JsonNode;

/**
 * T12：QA 助手入口（显式路由优先）。目录/缺项候选是只读面；执行与评审是命令面——
 * 评审强制已认证 actor，过期 revision 冲突（409），拒绝阻断下游。
 */
@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/assistant")
class AiAssistantController {

    private final AiAssistantService assistantService;

    AiAssistantController(AiAssistantService assistantService) {
        this.assistantService = assistantService;
    }

    record RunRequest(String taskType, Long projectId, JsonNode inputs, String actorId) {
    }

    record ReviewRequest(String decision, Integer revision, JsonNode candidate) {
    }

    @GetMapping("/catalog")
    Map<String, Object> catalog() {
        return Map.of(
            "tasks", AiTaskCatalog.all(),
            "notes", List.of(
                "显式选择优先；自动识别只提候选与缺项",
                "所有任务的人工门不可关闭（humanGateRequired=true）",
                "报告/回归/SQL 任务的数值与证据规则由确定性校验器执行"
            )
        );
    }

    @PostMapping("/routing-candidates")
    List<Map<String, Object>> routingCandidates(@RequestBody RunRequest request) {
        return AiTaskCatalog.routeCandidates(request.inputs(), java.util.Set.of());
    }

    @GetMapping("/draft-points")
    @ResponseStatus(HttpStatus.OK)
    Map<String, Object> listDraftPoints(@RequestParam(name = "projectId") long projectId,
                                        @RequestParam(name = "limit", required = false) Integer limit) {
        return assistantService.listDraftPoints(projectId, limit);
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.OK)
    AiAssistantService.AssistantRun startRun(@RequestBody RunRequest request) {
        return assistantService.startRun(request.taskType(), request.projectId(), request.inputs(), request.actorId());
    }

    @GetMapping("/runs/{runId}")
    AiAssistantService.AssistantRun run(@PathVariable String runId) {
        return assistantService.getRun(runId);
    }

    @GetMapping("/artifacts")
    @ResponseStatus(HttpStatus.OK)
    Map<String, Object> listArtifacts(@RequestParam(name = "projectId") long projectId,
                                      @RequestParam(name = "limit", required = false) Integer limit) {
        return assistantService.listArtifacts(projectId, limit);
    }

    @PostMapping("/artifacts/{artifactId}/review")
    @ResponseStatus(HttpStatus.OK)
    AiAssistantService.AssistantRun review(@PathVariable String artifactId, @RequestBody ReviewRequest request) {
        return assistantService.review(artifactId, request.decision(), request.revision(), request.candidate());
    }

    @GetMapping("/artifacts/{artifactId}/export")
    @ResponseStatus(HttpStatus.OK)
    Map<String, Object> exportArtifact(@PathVariable String artifactId,
                                       @RequestParam(name = "revision", required = false) Integer revision) {
        return assistantService.exportArtifact(artifactId, revision);
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
