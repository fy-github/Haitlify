package com.haitlify.aitest.platform.security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * T14：出站载荷脱敏器。任何会进入 API 响应、日志、事件流、快照、导出或模型消息的文本，
 * 都必须先经过这里——秘密形态一律替换为 {@code ***}。
 *
 * <p>唯一的合法例外是「transport Authorization」：网关发往模型端点的真实 Authorization
 * 头不经过本类（它存在于 HTTP 传输层，不进入上述任何载荷）；一旦它出现在载荷里，即为泄漏。</p>
 */
public final class PayloadSanitizer {

    /** 金丝雀/秘密形态（顺序固定，便于测试）。 */
    private static final List<Pattern> SECRET_PATTERNS = List.of(
        // Bearer/Authorization：保留前缀，值脱敏。
        Pattern.compile("(?i)((?:authorization|bearer|basic)\\s*[:=]?\\s*)([A-Za-z0-9._~+/=-]{8,})"),
        // OpenAI 风格 key。
        Pattern.compile("\\b(sk-[A-Za-z0-9\\-_]{6,}(?:-[A-Za-z0-9\\-_]+)*)"),
        // AWS 风格 key。
        Pattern.compile("\\b(AKIA[0-9A-Z]{8,})"),
        // key=value / key: value 形态。
        Pattern.compile("(?i)((?:api[_-]?key|token|secret|password|passwd|pwd|credential)\\s*[=:]\\s*)([^\\s\"'&,;]{4,})"),
        // Cookie 头。
        Pattern.compile("(?i)((?:cookie|set-cookie)\\s*:\\s*)([^\\r\\n]{4,})"),
        // URL 查询参数。
        Pattern.compile("([?&](?:access_token|api_key|apikey|key|token|secret|password)=)([^\\s&\"']+)")
    );

    private PayloadSanitizer() {
    }

    /** 文本脱敏：秘密形态替换为 ***，其余原样。 */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = text;
        for (Pattern pattern : SECRET_PATTERNS) {
            masked = replaceAll(pattern, masked);
        }
        return masked;
    }

    private static String replaceAll(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String prefix = matcher.groupCount() >= 2 ? matcher.group(1) : "";
            String replacement = prefix + "***";
            if (pattern.pattern().startsWith("\\b(sk-") || pattern.pattern().startsWith("\\b(AKIA")) {
                replacement = "***";
            }
            matcher.appendReplacement(builder, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    /**
     * 金丝雀扫描：在任意 JSON 载荷里找固定金丝雀串，返回命中的 JSON Pointer 路径列表。
     * 空列表 = 干净。用于 API/log/event/snapshot/export/model-message 的出站前检查与测试断言。
     */
    public static List<String> scan(JsonNode payload, String canary) {
        List<String> hits = new ArrayList<>();
        if (payload == null || canary == null || canary.isBlank()) {
            return hits;
        }
        scanNode("", payload, canary, hits);
        return hits;
    }

    private static void scanNode(String pointer, JsonNode node, String canary, List<String> hits) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(entry ->
                scanNode(pointer + "/" + entry.getKey(), entry.getValue(), canary, hits));
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                scanNode(pointer + "/" + index, node.get(index), canary, hits);
            }
        } else if (node.isTextual() && node.asText().contains(canary)) {
            hits.add(pointer.isBlank() ? "/" : pointer);
        }
    }

    /** 深度脱敏 JSON 载荷（返回可直接出站的新文本）；扫描后再用 scan 复核是调用方责任。 */
    public static String maskJson(JsonNode payload) {
        if (payload == null || payload.isMissingNode()) {
            return "";
        }
        return mask(payload.toString());
    }
}
