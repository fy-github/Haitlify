package com.haitlify.aitest.platform.ai.gateway;

import java.util.List;

import org.springframework.stereotype.Component;

import com.haitlify.aitest.platform.ai.runtime.output.AiSchemaValidationEngine;
import com.haitlify.aitest.platform.ai.runtime.output.AiStructuredOutputExtraction;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class AiStructuredOutputValidator {

    private final ObjectMapper objectMapper;

    AiStructuredOutputValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    AiStructuredOutputValidationResult validate(String content, JsonNode schema) {
        JsonNode output;
        try {
            output = objectMapper.readTree(content);
        } catch (Exception rawFailure) {
            // T03 有限 fence 处理：仅在整体单块 ```/```json 包裹时剥壳重试一次；
            // 剥壳后仍失败（含截断/拒绝文本/空输出）一律判 Malformed——绝不补完、绝不转空结果。
            String extracted = AiStructuredOutputExtraction.extract(content);
            if (extracted.equals(content == null ? "" : content.strip())) {
                return new AiStructuredOutputValidationResult(false, objectMapper.createObjectNode(),
                    List.of("Malformed JSON: " + rawFailure.getMessage()));
            }
            try {
                output = objectMapper.readTree(extracted);
            } catch (Exception fencedFailure) {
                return new AiStructuredOutputValidationResult(false, objectMapper.createObjectNode(),
                    List.of("Malformed JSON: " + fencedFailure.getMessage()));
            }
        }
        List<String> errors = AiSchemaValidationEngine.validate(output, schema);
        return new AiStructuredOutputValidationResult(errors.isEmpty(), output, errors);
    }
}

record AiStructuredOutputValidationResult(
    boolean valid,
    JsonNode output,
    List<String> errors
) {
}
