package com.haitlify.aitest.platform.ai.runtime;

import java.util.List;
import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.Protocol;
import tools.jackson.databind.JsonNode;

public class ResponsesProtocolAdapter implements AiProtocolAdapter {
    public Protocol protocol() { return Protocol.RESPONSES; }
    public AiProtocolResponse normalize(JsonNode body) {
        StringBuilder text = new StringBuilder();
        for (JsonNode item : body.path("output")) {
            if (!"message".equals(item.path("type").asText(""))) continue;
            for (JsonNode part : item.path("content")) {
                if (part.path("type").asText("").equals("output_text")) text.append(part.path("text").asText(""));
            }
        }
        String status = body.path("status").asText("");
        JsonNode usage = body.path("usage");
        return new AiProtocolResponse(text.toString(), status, body.path("refusal").asText(""), List.of(), new AiProtocolResponse.Usage(usage.path("input_tokens").isNumber() ? usage.path("input_tokens").asInt() : null, usage.path("output_tokens").isNumber() ? usage.path("output_tokens").asInt() : null), body.path("id").asText(""), "incomplete".equals(status) || "failed".equals(status));
    }
}
