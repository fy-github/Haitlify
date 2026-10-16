package com.haitlify.aitest.platform.ai.config;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Additive REST surface for Platform AI run policies (deliverable 1).
 * Does not alter any existing endpoint.
 */
@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/run-policies")
class AiRunPolicyController {

    private final AiRunPolicyService aiRunPolicyService;

    AiRunPolicyController(AiRunPolicyService aiRunPolicyService) {
        this.aiRunPolicyService = aiRunPolicyService;
    }

    @GetMapping
    AiRunPolicyListResponse listPolicies() {
        return aiRunPolicyService.listPolicies();
    }

    @GetMapping("/{policyId}")
    AiRunPolicyResponse getPolicy(@PathVariable String policyId) {
        return aiRunPolicyService.getPolicy(policyId);
    }

    @PutMapping("/{policyId}")
    AiRunPolicyResponse savePolicy(@PathVariable String policyId, @RequestBody SaveAiRunPolicyRequest request) {
        return aiRunPolicyService.savePolicy(policyId, request);
    }

    @PutMapping("/override/{taskType}")
    AiRunPolicyResponse saveTaskOverride(@PathVariable String taskType, @RequestBody SaveAiRunPolicyRequest request) {
        return aiRunPolicyService.saveTaskOverride(taskType, request);
    }

    @DeleteMapping("/override/{taskType}")
    void deleteTaskOverride(@PathVariable String taskType) {
        aiRunPolicyService.deleteTaskOverride(taskType);
    }

    /** 与同族控制器一致：入参类拒绝（如 unknown_task_type 白名单）必须是 400，而非框架默认 500。 */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<java.util.Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(400)
            .body(java.util.Map.of("code", "invalid_request", "message", exception.getMessage()));
    }
}
