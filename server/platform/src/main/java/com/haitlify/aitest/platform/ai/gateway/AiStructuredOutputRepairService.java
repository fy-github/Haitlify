package com.haitlify.aitest.platform.ai.gateway;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.haitlify.aitest.platform.ai.call.AiCallRecordPayloads;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional
public class AiStructuredOutputRepairService {


    private final AiStructuredOutputValidator validator;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade taskFacade;

    /** 兼容构造（既有单测）：把裸网关包进最小 facade（无预算服务/attempt 仓储）。 */
    AiStructuredOutputRepairService(
        AiGateway aiGateway,
        AiStructuredOutputValidator validator,
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this(aiGateway, com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade.minimal(
            aiGateway, new com.haitlify.aitest.platform.ai.runtime.AiExecutionLedger()),
            validator, jdbcTemplate, objectMapper);
    }

    @org.springframework.beans.factory.annotation.Autowired
    AiStructuredOutputRepairService(
        AiGateway aiGateway,
        com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade taskFacade,
        AiStructuredOutputValidator validator,
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this.validator = validator;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.taskFacade = taskFacade;
    }

    AiStructuredOutputRepairResult repairIfNeeded(
        AiGatewayConfig config,
        String originalOutput,
        JsonNode schema,
        AiStructuredOutputRepairContext context
    ) {
        // T03：repair 只修结构且最多允许一次——调用方把已尝试次数放进 context，超过即拒绝（不静默重试）。
        if (context != null && context.repairAttempt() > 0) {
            return new AiStructuredOutputRepairResult(false, false, objectMapper.createObjectNode(),
                "repair already attempted once for this run");
        }
        AiStructuredOutputValidationResult originalValidation = validator.validate(originalOutput, schema);
        if (originalValidation.valid()) {
            return new AiStructuredOutputRepairResult(true, false, originalValidation.output(), "");
        }

        ObjectNode prompt = objectMapper.createObjectNode();
        prompt.put("instruction", "Repair the model output to match the JSON schema. Return JSON only.");
        prompt.put("originalOutput", originalOutput);
        prompt.set("validationErrors", objectMapper.valueToTree(originalValidation.errors()));
        prompt.set("targetJsonSchema", schema);

        // T07 收敛：修复请求不再旁路直发——经统一 Runtime（父预算/attempt/失败分类与主调用同权）。
        AiGenerationResult repair = taskFacade.generateForRoute(
            new com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade.RouteTaskExecution(
                "structured_output_repair",
                context == null ? null : context.projectId(),
                "",
                context == null ? null : context.runId(),
                prompt,
                null),
            config);
        if (!repair.success()) {
            String message = blankToDefault(repair.errorMessage(), "AI structured output repair failed");
            insertCallRecord(config, context, repair, prompt, "failed", message);
            return new AiStructuredOutputRepairResult(false, true, objectMapper.createObjectNode(), message);
        }

        AiStructuredOutputValidationResult repairedValidation = validator.validate(repair.content(), schema);
        String status = repairedValidation.valid() ? "succeeded" : "failed";
        String errorMessage = repairedValidation.valid() ? "" : String.join("; ", repairedValidation.errors());
        insertCallRecord(config, context, repair, prompt, status, errorMessage);
        if (!repairedValidation.valid()) {
            return new AiStructuredOutputRepairResult(false, true, objectMapper.createObjectNode(), errorMessage);
        }
        return new AiStructuredOutputRepairResult(true, true, repairedValidation.output(), "");
    }

    /**
     * Repairs a malformed requirement-analysis response using the same model route that
     * produced it. The public wrapper keeps the package-private authoring records private while
     * allowing the asynchronous analysis worker to recover from truncated JSON.
     */
    public RepairResult repairRequirementAnalysisOutput(
        AiGatewayConfig config,
        String originalOutput,
        JsonNode schema,
        Long runPk,
        Long projectId
    ) {
        return repairRequirementAnalysisOutput(config, originalOutput, schema, runPk, projectId, null);
    }

    /** 带 runId 的修复入口：修复调用归属父 run 的台账与预算。 */
    public RepairResult repairRequirementAnalysisOutput(
        AiGatewayConfig config,
        String originalOutput,
        JsonNode schema,
        Long runPk,
        Long projectId,
        String runId
    ) {
        AiStructuredOutputRepairResult result = repairIfNeeded(
            config,
            originalOutput,
            schema,
            new AiStructuredOutputRepairContext(runPk, projectId, "requirement-test-point-analysis", 0, runId)
        );
        return new RepairResult(result.success(), result.output(), result.errorMessage());
    }

    public record RepairResult(boolean success, JsonNode output, String errorMessage) {
    }

    private void insertCallRecord(
        AiGatewayConfig config,
        AiStructuredOutputRepairContext context,
        AiGenerationResult result,
        JsonNode prompt,
        String status,
        String errorMessage
    ) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_call_records(
              record_id, run_id, project_id, provider_type, model_name, config_snapshot_id, agent_id,
              skill_ids_json, rule_version, modality, request_id, status, error_code, error_message,
              duration_ms, token_usage_input, token_usage_output, estimated_cost, input_summary_json,
              output_summary_json, raw_payload_json, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "call-" + UUID.randomUUID(),
            context == null ? null : context.runPk(),
            context == null ? null : context.projectId(),
            config.providerType(),
            config.model(),
            "repair-pass",
            context == null ? "structured-output-repair" : blankToDefault(context.agentId(), "structured-output-repair"),
            stringify(objectMapper.valueToTree(List.of("structured-output-repair"))),
            "",
            "text",
            result.requestId(),
            status,
            "",
            errorMessage,
            0,
            result.inputTokens(),
            result.outputTokens(),
            0,
            AiCallRecordPayloads.compactJson(objectMapper, prompt),
            stringify(objectMapper.createObjectNode().put("contentLength", result.content() == null ? 0 : result.content().length())),
            AiCallRecordPayloads.compactText(objectMapper, result.content() == null ? "" : result.content()),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    private String stringify(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node == null ? objectMapper.createObjectNode() : node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid AI repair JSON", exception);
        }
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

record AiStructuredOutputRepairContext(
    Long runPk,
    Long projectId,
    String agentId,
    int repairAttempt,
    String runId
) {
    /** 兼容既有调用方：默认尚无 repair 尝试、无 run 台账归属。 */
    AiStructuredOutputRepairContext(Long runPk, Long projectId, String agentId) {
        this(runPk, projectId, agentId, 0, null);
    }

    AiStructuredOutputRepairContext(Long runPk, Long projectId, String agentId, int repairAttempt) {
        this(runPk, projectId, agentId, repairAttempt, null);
    }
}

record AiStructuredOutputRepairResult(
    boolean success,
    boolean repaired,
    JsonNode output,
    String errorMessage
) {
}
