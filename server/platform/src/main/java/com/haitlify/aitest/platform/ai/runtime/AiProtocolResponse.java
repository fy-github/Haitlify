package com.haitlify.aitest.platform.ai.runtime;

import java.util.Map;
import java.util.List;

public record AiProtocolResponse(String text, String finishReason, String refusal,
                                 List<Map<String, Object>> toolCalls, Usage usage,
                                 String providerRequestId, boolean incomplete) {
    public record Usage(Integer inputTokens, Integer outputTokens) {}
    public boolean hasUsableText() { return text != null && !text.isBlank() && !incomplete && (refusal == null || refusal.isBlank()); }
}
