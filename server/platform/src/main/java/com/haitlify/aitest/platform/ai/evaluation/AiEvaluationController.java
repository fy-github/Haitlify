package com.haitlify.aitest.platform.ai.evaluation;

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

/**
 * T13：Gold Set 评测入口。指标全部由确定性代码计算；人评单列 PENDING_HUMAN；
 * 评测产物不会写入生产知识（评价集防泄漏由知识反馈服务把关）。
 */
@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/evaluation")
class AiEvaluationController {

    private final AiEvaluationService evaluationService;

    AiEvaluationController(AiEvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    record EvaluateRequest(JsonNode goldSet) {
    }

    record CompareRequest(AiEvaluationService.EvaluationReport baseline,
                          AiEvaluationService.EvaluationReport candidate) {
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.OK)
    AiEvaluationService.EvaluationReport run(@RequestBody EvaluateRequest request) {
        return evaluationService.evaluate(evaluationService.loadSpec(request.goldSet()));
    }

    @PostMapping("/compare")
    @ResponseStatus(HttpStatus.OK)
    Map<String, Double> compare(@RequestBody CompareRequest request) {
        return evaluationService.compare(request.baseline(), request.candidate());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        // T-R5 轮 5：IAE 文案同样过脱敏器（当前均为常量，未来携带外部文本时不回显秘密）。
        String message = com.haitlify.aitest.platform.ai.security.AiPayloadSanitizer.mask(
            exception.getMessage() == null ? "invalid_request" : exception.getMessage());
        return ResponseEntity.status(400).body(Map.of("code", "invalid_request", "message", message));
    }

    /**
     * T-R5 轮 3：评测输入/输出形状异常一律 400（脱敏后回显）——绝不让序列化/集合异常以 500 冒泡。
     */
    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, String>> unexpected(RuntimeException exception) {
        if (exception instanceof org.springframework.web.server.ResponseStatusException statusException) {
            throw statusException;
        }
        String message = com.haitlify.aitest.platform.ai.security.AiPayloadSanitizer.mask(
            exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        if (message.length() > 240) {
            message = message.substring(0, 237) + "...";
        }
        return ResponseEntity.status(400).body(Map.of("code", "invalid_payload", "message", message));
    }
}
