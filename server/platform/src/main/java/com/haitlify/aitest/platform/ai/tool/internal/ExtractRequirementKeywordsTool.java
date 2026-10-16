package com.haitlify.aitest.platform.ai.tool.internal;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
class ExtractRequirementKeywordsTool extends AbstractInternalAiTool {

    private static final Pattern OPERATION_PATTERN = Pattern.compile("(?:点击|执行|进行|可以|必须|需要|支持|允许)([^，。；、]*(?:保存|删除|新增|编辑|修改|查询|导出|导入|审核|提交|取消|登录|切换)[^，。；、]*)");
    private static final Pattern ROLE_PATTERN = Pattern.compile("(租户管理员|项目成员|测试人员|运营人员|管理员|访客|用户)");

    ExtractRequirementKeywordsTool(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public String toolId() {
        return "extract-requirement-keywords";
    }

    @Override
    public String name() {
        return "Extract Requirement Keywords";
    }

    @Override
    public String description() {
        return "Extracts roles, operations, and business keywords from requirement text.";
    }

    @Override
    public JsonNode outputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("operations").put("type", "array");
        properties.putObject("roles").put("type", "array");
        properties.putObject("keywords").put("type", "array");
        return schema;
    }

    @Override
    public JsonNode dryRun(JsonNode input) {
        String text = inputText(input);
        ObjectNode result = objectMapper.createObjectNode();
        fillArray(result.putArray("operations"), extractOperations(text));
        fillArray(result.putArray("roles"), extractRoles(text));
        fillArray(result.putArray("keywords"), extractKeywords(text));
        return result;
    }

    private Set<String> extractOperations(String text) {
        LinkedHashSet<String> operations = new LinkedHashSet<>();
        Matcher matcher = OPERATION_PATTERN.matcher(text);
        while (matcher.find()) {
            String operation = cleanupOperation(matcher.group(1));
            if (!operation.isBlank()) operations.add(operation);
        }
        return operations;
    }

    private String cleanupOperation(String value) {
        return value
            .replaceFirst("^后，?系统", "")
            .replaceFirst("^后，?", "")
            .replaceFirst("^项目后.*", "项目")
            .replaceFirst("后$", "")
            .trim();
    }

    private Set<String> extractRoles(String text) {
        LinkedHashSet<String> roles = new LinkedHashSet<>(extractMatches(text, ROLE_PATTERN));
        if (roles.size() > 1) {
            roles.remove("用户");
        }
        return roles;
    }

    private Set<String> extractKeywords(String text) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        for (String candidate : new String[] { "项目名称", "状态", "草稿项目", "已删除", "权限", "租户", "项目" }) {
            if (text.contains(candidate)) keywords.add(candidate);
        }
        return keywords;
    }

    private Set<String> extractMatches(String text, Pattern pattern) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private void fillArray(ArrayNode array, Set<String> values) {
        values.forEach(array::add);
    }
}
