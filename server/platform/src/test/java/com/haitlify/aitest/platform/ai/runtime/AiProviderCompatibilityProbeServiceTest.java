package com.haitlify.aitest.platform.ai.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.haitlify.aitest.platform.ai.gateway.AiGatewaySettingsService;
import com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings;
import com.haitlify.aitest.platform.ai.runtime.transport.AiProtocolTransportClient;
import com.haitlify.aitest.platform.ai.runtime.transport.AiTransportResult;
import tools.jackson.databind.ObjectMapper;

class AiProviderCompatibilityProbeServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void recordsLivePassWithHashUsageSchemaAndEvidence() throws Exception {
        var settings = Mockito.mock(AiGatewaySettingsService.class);
        Mockito.when(settings.loadDefaultTextSettings()).thenReturn(
            new ResolvedAiGatewaySettings("openai-compatible", "https://provider.test/v1", "configured-model", "secret", 3000)
        );
        var transport = Mockito.mock(AiProtocolTransportClient.class);
        Mockito.when(transport.execute(Mockito.any())).thenReturn(new AiTransportResult(200,
            new AiProtocolResponse("{\"items\":[{\"title\":\"A\",\"steps\":[\"Open\"],\"expectedResult\":\"Visible\",\"evidenceIds\":[\"ev-1\"]},{\"title\":\"B\",\"steps\":[\"Search\"],\"expectedResult\":\"Filtered\",\"evidenceIds\":[\"ev-1\"]}]}", "stop", "", List.of(), new AiProtocolResponse.Usage(5, 9), "req-live", false)));
        var entry = new AiProviderCompatibilityProbeService(settings, mapper, transport)
            .probe("profile-1", 1, "model-1", "Search devices", List.of("ev-1"), "config-v1");
        assertEquals("LIVE_BUSINESS_PASS", entry.conclusion());
        assertTrue(entry.isLiveBusinessPass());
        assertNotNull(entry.inputHash());
        assertTrue(entry.usageKnown());
        assertEquals("PASS", entry.schemaOutcome());
        assertEquals("PASS", entry.evidenceOutcome());
        assertTrue(entry.latencyMs() >= 0);
    }

    @Test
    void recordsBlockedForEmptyProviderOutput() throws Exception {
        var settings = Mockito.mock(AiGatewaySettingsService.class);
        Mockito.when(settings.loadDefaultTextSettings()).thenReturn(
            new ResolvedAiGatewaySettings("openai-compatible", "https://provider.test/v1", "configured-model", "secret", 3000)
        );
        var transport = Mockito.mock(AiProtocolTransportClient.class);
        Mockito.when(transport.execute(Mockito.any())).thenReturn(new AiTransportResult(200,
            new AiProtocolResponse("", "stop", "", List.of(), new AiProtocolResponse.Usage(null, null), "req-empty", false)));
        var entry = new AiProviderCompatibilityProbeService(settings, mapper, transport)
            .probe("profile-1", 1, "model-1", "Search devices", List.of("ev-1"), "config-v1");
        assertEquals("BLOCKED", entry.conclusion());
        assertEquals("provider_output_not_usable", entry.blockingReason());
        assertFalse(entry.isLiveBusinessPass());
    }
}
