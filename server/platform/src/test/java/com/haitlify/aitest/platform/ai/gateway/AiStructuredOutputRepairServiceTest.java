package com.haitlify.aitest.platform.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AiStructuredOutputRepairServiceTest {

    @Autowired
    private AiStructuredOutputRepairService repairService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AiGateway aiGateway;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("delete from ai_call_records");
    }

    @Test
    void malformedJsonIsRepairedSuccessfully() throws Exception {
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, "{\"draftTestPoints\":[{\"title\":\"t\"}]}", "repair-1", 20, 10, null));

        AiStructuredOutputRepairResult result = repairService.repairIfNeeded(
            config(),
            "not-json",
            schema(),
            new AiStructuredOutputRepairContext(null, null, "requirement-test-point-analysis")
        );

        assertTrue(result.success());
        assertTrue(result.repaired());
        assertEquals(1, result.output().path("draftTestPoints").size());
    }

    @Test
    void missingRequiredFieldIsRepairedSuccessfully() throws Exception {
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, "{\"draftTestPoints\":[{\"title\":\"t\"}]}", "repair-2", 20, 10, null));

        AiStructuredOutputRepairResult result = repairService.repairIfNeeded(
            config(),
            "{}",
            schema(),
            new AiStructuredOutputRepairContext(null, null, "requirement-test-point-analysis")
        );

        assertTrue(result.success());
        assertTrue(result.repaired());
        assertEquals(1, result.output().path("draftTestPoints").size());
    }

    @Test
    void emptyArrayRepairIsNotCoercedToSuccess() throws Exception {
        // T03：空结果不得被转成「成功的空数组」——契约要求 minItems>=1 时必须失败。
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, "{\"draftTestPoints\":[]}", "repair-4", 20, 10, null));

        AiStructuredOutputRepairResult result = repairService.repairIfNeeded(
            config(),
            "{}",
            schema(),
            new AiStructuredOutputRepairContext(null, null, "requirement-test-point-analysis")
        );

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("minItems"));
    }

    @Test
    void repairIsAttemptedAtMostOnce() throws Exception {
        // T03：最多一次——已尝试过的上下文必须直接拒绝，且不再触发任何模型调用。
        AiStructuredOutputRepairResult result = repairService.repairIfNeeded(
            config(),
            "not-json",
            schema(),
            new AiStructuredOutputRepairContext(null, null, "requirement-test-point-analysis", 1)
        );

        assertFalse(result.success());
        assertFalse(result.repaired());
        assertTrue(result.errorMessage().contains("already attempted once"));
        verifyNoInteractions(aiGateway);
    }

    @Test
    void fencedContentRepairsThroughExtractionAndTruncatedContentStillFails() throws Exception {
        // T03 有限 fence：整体单块包裹可通过；截断（未闭合）不得被补完为成功。
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, "```json\n{\"draftTestPoints\":[{\"title\":\"t\"}]}\n```", "repair-5", 20, 10, null));
        AiStructuredOutputRepairResult fenced = repairService.repairIfNeeded(
            config(),
            "{}",
            schema(),
            new AiStructuredOutputRepairContext(null, null, "requirement-test-point-analysis")
        );
        assertTrue(fenced.success());
        assertEquals(1, fenced.output().path("draftTestPoints").size());

        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, "```json\n{\"draftTestPoints\":[{\"title\":", "repair-6", 20, 10, null));
        AiStructuredOutputRepairResult truncated = repairService.repairIfNeeded(
            config(),
            "{}",
            schema(),
            new AiStructuredOutputRepairContext(null, null, "requirement-test-point-analysis")
        );
        assertFalse(truncated.success());
    }

    @Test
    void unrecoverableOutputFailsAndCreatesCallRecord() throws Exception {
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, "{\"wrong\":[]}", "repair-3", 20, 10, null));

        AiStructuredOutputRepairResult result = repairService.repairIfNeeded(
            config(),
            "not-json",
            schema(),
            new AiStructuredOutputRepairContext(null, null, "requirement-test-point-analysis")
        );

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("/draftTestPoints: missing required field"));
        assertEquals(1, jdbcTemplate.queryForObject("select count(*) from ai_call_records where request_id = 'repair-3'", Integer.class));
        assertEquals("failed", jdbcTemplate.queryForObject("select status from ai_call_records where request_id = 'repair-3'", String.class));
    }

    private AiGatewayConfig config() {
        return new AiGatewayConfig("openai-compatible", "https://api.example.com/v1", "gpt-test", "sk-test", 30000);
    }

    private JsonNode schema() throws Exception {
        return objectMapper.readTree("""
            {
              "type": "object",
              "required": ["draftTestPoints"],
              "properties": {
                "draftTestPoints": { "type": "array", "minItems": 1 }
              }
            }
            """);
    }

    @Test
    void repairCallsAreRecordedThroughTheUnifiedRuntime() throws Exception {
        // T07 收敛轮：修复调用经统一 Runtime（facade.generateForRoute）——
        // 必须留下 T06 两阶段 attempt 落库（这正是旁路直发时代缺失的审计）。
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, "{\"draftTestPoints\":[{\"title\":\"t\"}]}",
                "repair-converge", 20, 10, null));

        AiStructuredOutputRepairService.RepairResult result = repairService.repairRequirementAnalysisOutput(
            config(), "not-json", schema(), null, null, "run-repair-converge");

        assertThat(result.success()).isTrue();
        Integer attempts = jdbcTemplate.queryForObject(
            "select count(*) from ai_runtime_attempts where run_id = ?", Integer.class, "run-repair-converge");
        assertThat(attempts).isEqualTo(1);
        String status = jdbcTemplate.queryForObject(
            "select status from ai_runtime_attempts where run_id = ?", String.class, "run-repair-converge");
        assertThat(status).isEqualTo("succeeded");
    }

}
