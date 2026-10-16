package com.haitlify.aitest.platform.ai.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.haitlify.aitest.platform.ai.gateway.AiGatewaySettingsService;
import com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings;
import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.AiTaskRequest;
import com.haitlify.aitest.platform.ai.runtime.transport.AiProtocolTransportClient;
import com.haitlify.aitest.platform.ai.runtime.transport.AiTransportResult;
import tools.jackson.databind.ObjectMapper;

class AiRuntimeInvocationServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsOnlyTwoRealModelItemsWithScopedEvidence() throws Exception {
        var settings = Mockito.mock(AiGatewaySettingsService.class);
        Mockito.when(settings.loadDefaultTextSettings()).thenReturn(
            new ResolvedAiGatewaySettings("openai-compatible", "https://provider.test/v1", "model-a", "secret", 3000)
        );
        var transport = Mockito.mock(AiProtocolTransportClient.class);
        Mockito.when(transport.execute(Mockito.any())).thenReturn(new AiTransportResult(
            200,
            new AiProtocolResponse(
                "{\"items\":[{\"title\":\"A\",\"steps\":\"Open\",\"expectedResult\":\"Visible\",\"evidenceIds\":[\"ev-1\"]},{\"title\":\"B\",\"steps\":\"Search\",\"expectedResult\":\"Filtered\",\"evidenceIds\":[\"ev-1\"]}]}",
                "stop", "", List.of(), new AiProtocolResponse.Usage(4, 8), "req-real-1", false
            )
        ));
        var service = new AiRuntimeInvocationService(settings, mapper, transport);
        var result = service.invoke(new AiTaskRequest(
            "requirement_analysis", 11L, "actor-1", "run-1", "skill-1", "test-points", "1",
            List.of("ev-1"), mapper.readTree("{\"requirement\":\"A search requirement\",\"evidenceIds\":[\"ev-1\"]}"), "idem-1"
        ));
        assertEquals("MODEL_GENERATED", result.resultOrigin());
        assertEquals("PENDING_HUMAN_CONFIRMATION", result.acceptanceState());
        assertEquals("req-real-1", result.providerRequestId());
        assertEquals(2, result.output().path("items").size());
    }

    @Test
    void blocksEmptyOrRuleLikeOutputInsteadOfCreatingArtifact() throws Exception {
        var settings = Mockito.mock(AiGatewaySettingsService.class);
        Mockito.when(settings.loadDefaultTextSettings()).thenReturn(
            new ResolvedAiGatewaySettings("openai-compatible", "https://provider.test/v1", "model-a", "secret", 3000)
        );
        var transport = Mockito.mock(AiProtocolTransportClient.class);
        Mockito.when(transport.execute(Mockito.any())).thenReturn(new AiTransportResult(
            200, new AiProtocolResponse("{}", "stop", "", List.of(), new AiProtocolResponse.Usage(null, null), "req-empty", false)
        ));
        var result = new AiRuntimeInvocationService(settings, mapper, transport).invoke(new AiTaskRequest(
            "requirement_analysis", 11L, "actor-1", "run-1", "skill-1", "test-points", "1",
            List.of("ev-1"), mapper.readTree("{\"requirement\":\"A search requirement\",\"evidenceIds\":[\"ev-1\"]}"), "idem-1"
        ));
        assertEquals("BLOCKED", result.resultOrigin());
        assertEquals("BLOCKED", result.acceptanceState());
        assertNull(result.output());
        assertEquals("business_schema_invalid", result.failureCategory());
        assertEquals("req-empty", result.providerRequestId());
        assertNotNull(result.usage());
        assertNull(result.usage().inputTokens());
        assertNull(result.usage().outputTokens());
    }
}
