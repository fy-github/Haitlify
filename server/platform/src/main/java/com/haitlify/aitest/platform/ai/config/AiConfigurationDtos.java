package com.haitlify.aitest.platform.ai.config;

import java.time.Instant;
import java.util.List;

/**
 * T01：版本化配置 DTO——Connection/Profile/Policy/RunSnapshot。
 * 敏感字段纪律：任何 DTO 都不携带密钥材料，只携带 {@code credentialRef}（opaque 引用）。
 */
record ConnectionView(
    String connectionId,
    int version,
    String baseUrl,
    String credentialRef,
    String protocol,
    boolean enabled,
    List<Long> allowedProjectIds,
    Instant createdAt,
    Instant updatedAt
) {
}

record ProfileView(
    String profileId,
    int version,
    String connectionId,
    String modelId,
    List<String> capabilities,
    String parameterPolicyJson,
    String verificationJson,
    Instant createdAt,
    Instant updatedAt
) {
}

record PolicyView(
    String policyId,
    String scope,
    String taskType,
    String name,
    boolean enabled,
    int perCallTimeoutMs,
    long wholeRunDeadlineMs,
    int retryBudget,
    int maxInputChars,
    String routesJson,
    Instant updatedAt
) {
}

record SnapshotView(
    String runId,
    String connectionId,
    int connectionVersion,
    String profileId,
    int profileVersion,
    String protocol,
    String actorId,
    long projectId,
    String inputHash,
    Instant createdAt
) {
}

/** T11：连接编辑器请求体（不携带密钥材料，仅 credentialRef）。 */
record RegisterConnectionRequest(
    String connectionId,
    String baseUrl,
    String credentialRef,
    String protocol,
    Boolean enabled,
    List<Long> allowedProjectIds,
    Integer expectedVersion
) {
}

/** T11：模型档案编辑器请求体（capabilities 只接受目录能力名；参数策略为 JSON 对象）。 */
record RegisterProfileRequest(
    String profileId,
    String connectionId,
    String modelId,
    List<String> capabilities,
    String parameterPolicyJson,
    String verificationJson,
    Integer expectedVersion
) {
}

/** T11：有效超时溯源视图（界面显示"有效值 + 来源 + 版本"）。 */
record EffectiveOptionView(Object value, String source, String sourceVersion, boolean present) {
}

/** T11：有效配置响应——timeout 溯源、档案能力/验证状态、任务目录（仅供展示，不做能力推断）。 */
record EffectiveOptionsResponse(
    String taskType,
    String profileId,
    Integer profileVersion,
    EffectiveOptionView timeout,
    List<String> declaredCapabilities,
    String verificationJson,
    List<String> taskTypes,
    java.util.Map<String, String> notes
) {
}
