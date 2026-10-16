package com.haitlify.aitest.platform.ai.runtime;

import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.Protocol;
import tools.jackson.databind.JsonNode;

public interface AiProtocolAdapter {
    Protocol protocol();
    AiProtocolResponse normalize(JsonNode response);
}
