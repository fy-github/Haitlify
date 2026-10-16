package com.haitlify.aitest.platform.ai.agentic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.AgentMemoryResponse;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.AgentMemoryScopeType;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.AgentMemoryStatus;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.GeneratedCaseArtifact;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.WebCaseAgentEventType;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.WebCaseAgentRunResponse;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.WebCaseAgentRunStatus;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.WebCaseAgentStage;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

class WebCaseAgentModelsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void roundTripsStateAndArtifactContractWithoutSecretFields() throws Exception {
        WebCaseAgentRunResponse run = new WebCaseAgentRunResponse(
            "run-1",
            7L,
            "analysis-1",
            WebCaseAgentRunStatus.RUNNING,
            WebCaseAgentStage.GENERATION,
            List.of("point-1"),
            List.of(),
            List.of(),
            3,
            "2026-08-03T00:00:00Z",
            "2026-08-03T00:01:00Z",
            null
        );
        WebCaseAgentModels.WebCaseAgentStartRequest request = new WebCaseAgentModels.WebCaseAgentStartRequest(
            7L,
            "analysis-1",
            List.of("point-1"),
            "env-1",
            "https://example.test",
            "chromium",
            "1440x900",
            null,
            new WebCaseAgentModels.BrowserDiscoveryRequest(true, 10_000, "domcontentloaded", null, Map.of(), List.of(), null),
            120_000L,
            WebCaseAgentModels.WebCaseAgentMode.COMPILE,
            "plan-1"
        );
        GeneratedCaseArtifact artifact = new GeneratedCaseArtifact(
            "artifact-1",
            "run-1",
            "point-1",
            "web_case",
            JsonNodeFactory.instance.objectNode().put("sourcePointId", "point-1"),
            List.of("evidence-1"),
            List.of(),
            true,
            0,
            "2026-08-03T00:01:00Z",
            "2026-08-03T00:01:00Z"
        );
        AgentMemoryResponse memory = new AgentMemoryResponse(
            "memory-1",
            "run-1",
            7L,
            "登录",
            WebCaseAgentStage.VALIDATION,
            "SUCCESS_PATTERN",
            "每个 Web 用例保留可执行断言",
            AgentMemoryStatus.ACTIVE,
            AgentMemoryScopeType.PROJECT,
            List.of(7L),
            3,
            "agent",
            "reviewer",
            "2026-08-03T00:01:00Z",
            "2026-08-03T00:01:00Z",
            "2026-08-03T00:02:00Z"
        );

        String json = objectMapper.writeValueAsString(Map.of("run", run, "artifact", artifact, "memory", memory));
        WebCaseAgentRunResponse decoded = objectMapper.treeToValue(objectMapper.readTree(json).path("run"),
            WebCaseAgentRunResponse.class);
        WebCaseAgentModels.WebCaseAgentStartRequest decodedRequest = objectMapper.treeToValue(
            objectMapper.readTree(objectMapper.writeValueAsString(request)),
            WebCaseAgentModels.WebCaseAgentStartRequest.class
        );
        GeneratedCaseArtifact decodedArtifact = objectMapper.treeToValue(objectMapper.readTree(json).path("artifact"),
            GeneratedCaseArtifact.class);
        AgentMemoryResponse decodedMemory = objectMapper.treeToValue(objectMapper.readTree(json).path("memory"),
            AgentMemoryResponse.class);

        assertEquals(WebCaseAgentStage.GENERATION, decoded.currentStage());
        assertEquals(WebCaseAgentRunStatus.RUNNING, decoded.status());
        assertEquals(120_000L, decodedRequest.batchTotalDurationMs());
        assertEquals("point-1", decodedArtifact.sourcePointId());
        assertEquals(List.of("evidence-1"), decodedArtifact.evidenceIds());
        assertEquals(true, decodedArtifact.accepted());
        assertEquals(AgentMemoryScopeType.PROJECT, decodedMemory.scopeType());
        assertFalse(json.toLowerCase().contains("secret"));
        assertFalse(json.toLowerCase().contains("password"));
        assertFalse(json.toLowerCase().contains("cookie"));
        assertFalse(json.toLowerCase().contains("api_key"));
    }

    @Test
    void exposesExactLifecycleEnumValues() {
        assertEquals(
            List.of("PREFLIGHT", "EVIDENCE", "PLANNING", "GENERATION", "VALIDATION", "DELIVERY"),
            List.of(WebCaseAgentStage.values()).stream().map(Enum::name).toList()
        );
        assertEquals(
            List.of("INPUT_SUMMARY", "ACTION", "TOOL_RESULT", "OUTPUT_SUMMARY", "VALIDATION", "STATUS", "ERROR"),
            List.of(WebCaseAgentEventType.values()).stream().map(Enum::name).toList()
        );
    }
}
