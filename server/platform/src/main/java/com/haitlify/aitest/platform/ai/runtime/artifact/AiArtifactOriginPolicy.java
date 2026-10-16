package com.haitlify.aitest.platform.ai.runtime.artifact;

import java.util.Map;

public final class AiArtifactOriginPolicy {
    private AiArtifactOriginPolicy() {}

    public static boolean isModelClaimAllowed(AiArtifactOrigin origin, boolean hasRuntimeEvidence) {
        return origin == AiArtifactOrigin.MODEL_GENERATED && hasRuntimeEvidence;
    }

    public static String displayLabel(AiArtifactOrigin origin) {
        return switch (origin) {
            case MODEL_GENERATED -> "模型生成";
            case RULE_EXTRACTED -> "规则提取";
            case TOOL_OBSERVED -> "工具观测";
            case HUMAN_EDITED -> "人工编辑";
        };
    }

    public static Map<String, AiFieldOrigin> protectedFieldOrigins() {
        return Map.of(
            "passRate", AiFieldOrigin.RULE_COMPUTED,
            "releaseConclusion", AiFieldOrigin.HUMAN_REQUIRED,
            "sqlExecuted", AiFieldOrigin.HUMAN_REQUIRED,
            "webExecutionResult", AiFieldOrigin.TOOL_OBSERVED
        );
    }
}
