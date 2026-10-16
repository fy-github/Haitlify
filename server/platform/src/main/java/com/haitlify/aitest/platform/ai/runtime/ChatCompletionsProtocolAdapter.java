package com.haitlify.aitest.platform.ai.runtime;

import java.util.List;
import java.util.Map;
import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.Protocol;
import tools.jackson.databind.JsonNode;

public class ChatCompletionsProtocolAdapter implements AiProtocolAdapter {
    public Protocol protocol() { return Protocol.CHAT_COMPLETIONS; }
    public AiProtocolResponse normalize(JsonNode body) {
        JsonNode choice = body.path("choices").path(0);
        String text = choice.path("message").path("content").asText("");
        String finish = choice.path("finish_reason").asText("");
        int in = body.path("usage").path("prompt_tokens").asInt(-1), out = body.path("usage").path("completion_tokens").asInt(-1);
        return new AiProtocolResponse(text, finish, choice.path("message").path("refusal").asText(""), List.of(), new AiProtocolResponse.Usage(in < 0 ? null : in, out < 0 ? null : out), body.path("id").asText(""), "length".equals(finish));
    }
}
