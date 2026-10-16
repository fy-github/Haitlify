package com.haitlify.aitest.platform.ai.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.haitlify.aitest.platform.ai.call.AiCallRecordPayloads;
import tools.jackson.databind.ObjectMapper;

class AiCallRecordPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void compactsOversizedPayloadsIntoTraceableMetadata() throws Exception {
        var payload = objectMapper.createObjectNode().put("prompt", "测试内容".repeat(20_000));

        String compacted = AiCallRecordPayloads.compactJson(objectMapper, payload);
        var summary = objectMapper.readTree(compacted);

        assertThat(compacted.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(60_000);
        assertThat(summary.path("_truncated").asBoolean()).isTrue();
        assertThat(summary.path("originalBytes").asInt()).isGreaterThan(60_000);
        assertThat(summary.path("sha256").asText()).hasSize(64);
    }

    @Test
    void keepsSmallPayloadsUnchanged() throws Exception {
        var payload = objectMapper.createObjectNode().put("prompt", "small");

        assertThat(AiCallRecordPayloads.compactJson(objectMapper, payload))
            .isEqualTo(objectMapper.writeValueAsString(payload));
    }
}
