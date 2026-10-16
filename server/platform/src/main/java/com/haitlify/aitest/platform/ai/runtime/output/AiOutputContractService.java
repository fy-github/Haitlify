package com.haitlify.aitest.platform.ai.runtime.output;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T03 契约服务：按任务类提供版本化的输出 schema（独立 JSON 字节接口）。
 * 资源位于 {@code classpath:ai/schemas/<task>/<version>.json}；启动时全量自校验，
 * 任何非法 schema 一律启动失败（fail-closed），版本注册表对外只读。
 */
@Service
public class AiOutputContractService {

    private static final String SCHEMA_LOCATION_PATTERN = "classpath*:ai/schemas/*/*.json";

    private final ObjectMapper objectMapper;
    private final Map<String, Map<String, JsonNode>> contractsByTask = new LinkedHashMap<>();

    AiOutputContractService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadAndValidateAll() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources(SCHEMA_LOCATION_PATTERN);
            for (Resource resource : resources) {
                String path = resource.getURL().toString();
                String[] segments = path.split("/ai/schemas/");
                if (segments.length != 2) {
                    continue;
                }
                String[] parts = segments[1].split("/");
                if (parts.length != 2 || !parts[1].endsWith(".json")) {
                    continue;
                }
                String taskType = parts[0];
                String version = parts[1].substring(0, parts[1].length() - ".json".length());
                try (InputStream input = resource.getInputStream()) {
                    JsonNode schema = objectMapper.readTree(input);
                    List<String> errors = AiSchemaValidationEngine.validateSchema(schema);
                    if (!errors.isEmpty()) {
                        throw new IllegalStateException("Invalid output schema " + path + ": " + String.join("; ", errors));
                    }
                    contractsByTask.computeIfAbsent(taskType, key -> new LinkedHashMap<>()).put(version, schema);
                }
            }
        } catch (IllegalStateException failClosed) {
            throw failClosed;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to load output contracts", exception);
        }
    }

    /** 指定任务类的指定版本 schema；不存在时抛出（调用方不得静默退化）。 */
    public JsonNode schemaFor(String taskType, String version) {
        JsonNode schema = contractsByTask.getOrDefault(taskType, Map.of()).get(version);
        if (schema == null) {
            throw new IllegalArgumentException("Unknown output contract: " + taskType + "/" + version);
        }
        return schema;
    }

    /** 指定任务类已注册的版本列表（升序字符串序）。 */
    public List<String> versionsFor(String taskType) {
        return contractsByTask.getOrDefault(taskType, Map.of()).keySet().stream().sorted().toList();
    }
}
