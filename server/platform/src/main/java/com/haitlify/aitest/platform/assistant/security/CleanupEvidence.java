package com.haitlify.aitest.platform.assistant.security;

import java.util.Locale;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 清理证据的形状校验（A0 早期开放项「清理证据自证」）。
 *
 * <p>之前只要传一段非空字符串（例如「已清理」）就能把资源标成 CLEANED，等于「我说清理了」。
 * 现在要求证据是 JSON 对象，并至少包含一种可核验凭证：
 * <ul>
 *   <li>{@code artifactUrl}：可回看的产物链接（http/https）；</li>
 *   <li>{@code readBack}：清理后的业务回读结论（absent / empty / deleted / closed / 404）；</li>
 *   <li>{@code verifiedBy}：执行者或复核人标识（≥2 字符且非占位词）。</li>
 * </ul>
 * {@code reference}（被清理对象标识，≥6 字符）是可选加分项，不强制。
 *
 * <p>这只是提高伪造门槛，不证明清理真的发生：真实回收仍以 Runner 侧产物和业务回读为准。
 */
public final class CleanupEvidence {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String[] PLACEHOLDERS = {"todo", "tbd", "n/a", "na", "none", "null", "unknown", "x"};

    private CleanupEvidence() {
    }

    public static void validate(String evidenceJson) {
        if (evidenceJson == null || evidenceJson.isBlank()) {
            throw new IllegalArgumentException("清理必须带证据（evidence）");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(evidenceJson);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("清理证据必须是 JSON 对象：" + ex.getMessage());
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("清理证据必须是 JSON 对象，例如 {\"reference\":\"order-123\",\"artifactUrl\":\"http://…\"}");
        }
        String reference = text(node, "reference");
        if (reference != null && (reference.length() < 6 || isPlaceholder(reference))) {
            throw new IllegalArgumentException("reference 至少 6 个字符，且不能是占位词");
        }
        String artifactUrl = text(node, "artifactUrl");
        String verifiedBy = text(node, "verifiedBy");
        String readBack = text(node, "readBack") == null ? text(node, "readback") : text(node, "readBack");
        boolean hasArtifact = artifactUrl != null && artifactUrl.matches("^https?://[^\\s]+$");
        boolean hasVerifier = verifiedBy != null && verifiedBy.length() >= 2 && !isPlaceholder(verifiedBy);
        boolean hasReadBack = readBack != null
            && readBack.toLowerCase(Locale.ROOT).matches("^(absent|empty|deleted|closed|removed|404|0)$");
        if (!hasArtifact && !hasVerifier && !hasReadBack) {
            throw new IllegalArgumentException(
                "清理证据必须带 artifactUrl、readBack（absent/empty/deleted/closed/404）或 verifiedBy 之一");
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asString("").trim();
        return text.isEmpty() ? null : text;
    }

    private static boolean isPlaceholder(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String placeholder : PLACEHOLDERS) {
            if (lower.equals(placeholder)) {
                return true;
            }
        }
        return false;
    }
}
