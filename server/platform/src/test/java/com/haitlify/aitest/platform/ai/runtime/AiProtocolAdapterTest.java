package com.haitlify.aitest.platform.ai.runtime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AiProtocolAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void normalizesChatMessageAndUnknownUsage() throws Exception {
        var result = new ChatCompletionsProtocolAdapter().normalize(mapper.readTree("{\"id\":\"chat-1\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}"));
        assertEquals("{\"ok\":true}", result.text());
        assertNull(result.usage().inputTokens());
        assertFalse(result.incomplete());
    }

    @Test
    void responsesOnlyAcceptsOutputTextItems() throws Exception {
        var result = new ResponsesProtocolAdapter().normalize(mapper.readTree("{\"id\":\"resp-1\",\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"content\":[{\"type\":\"output_text\",\"text\":\"hidden\"}]},{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"answer\"}]}],\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}"));
        assertEquals("answer", result.text());
        assertEquals(3, result.usage().inputTokens());
        assertEquals("resp-1", result.providerRequestId());
    }
}
