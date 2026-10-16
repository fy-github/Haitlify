package com.haitlify.aitest.platform.ai.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

/**
 * T01：旧 provider 配置（ai_system_configs, config_key='default'）的非破坏性导入器。
 *
 * <ul>
 *   <li>导入为 CHAT_COMPLETIONS + 未声明能力（capabilities=[]，verification=UNKNOWN）——旧数据不产生能力假设；</li>
 *   <li>幂等：与最新版本内容一致时不追加新版本；重复导入前后行数与版本号不变；</li>
 *   <li>旧策略超时（request_timeout_ms）作为 EXPLICIT 任务覆盖导入（task_override），null≠0 语义保持；</li>
 *   <li>绝不删除/改写历史行——旧配置与旧版本保留为回滚读取窗口。</li>
 * </ul>
 */
@Service
public class AiLegacyConfigurationImporter {

    /** 与运行中垂直切片一致的 legacy 身份（T07 证据链同源）。 */
    public static final String LEGACY_CONNECTION_ID = "legacy-default";
    public static final String LEGACY_PROFILE_ID = "legacy-default-profile";
    public static final String LEGACY_CREDENTIAL_REF = "legacy-ai-system-config";
    /** 旧 timeout 作为 EXPLICIT 覆盖落到的任务类型（legacy analysis 管道的常量任务）。 */
    public static final String LEGACY_POLICY_TASK_TYPE = "requirement_analysis";

    public record ImportReport(
        String connectionId,
        int connectionVersion,
        String profileId,
        int profileVersion,
        String protocol,
        List<String> capabilities,
        String capabilitiesState,
        boolean connectionAppended,
        boolean profileAppended,
        boolean policyTimeoutImported,
        Map<String, String> notes
    ) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final AiConnectionService connectionService;
    private final AiModelProfileService profileService;
    private final AiRunPolicyService runPolicyService;
    private final ObjectMapper objectMapper;

    AiLegacyConfigurationImporter(JdbcTemplate jdbcTemplate, AiConnectionService connectionService,
                                  AiModelProfileService profileService, AiRunPolicyService runPolicyService,
                                  ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.connectionService = connectionService;
        this.profileService = profileService;
        this.runPolicyService = runPolicyService;
        this.objectMapper = objectMapper;
    }

    public ImportReport importLegacyDefault() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select enabled, provider_type, base_url, text_model, request_timeout_ms from ai_system_configs"
                + " where config_key = 'default'");
        if (rows.isEmpty()) {
            throw new IllegalStateException("legacy configuration missing: ai_system_configs/default");
        }
        Map<String, Object> row = rows.get(0);
        boolean enabled = Boolean.TRUE.equals(row.get("enabled"));
        String providerType = String.valueOf(row.get("provider_type") == null ? "" : row.get("provider_type"));
        String baseUrl = String.valueOf(row.get("base_url") == null ? "" : row.get("base_url"));
        String textModel = String.valueOf(row.get("text_model") == null ? "" : row.get("text_model"));
        int legacyTimeoutMs = row.get("request_timeout_ms") == null
            ? 0 : ((Number) row.get("request_timeout_ms")).intValue();
        String protocol = "responses".equalsIgnoreCase(providerType) ? "RESPONSES" : "CHAT_COMPLETIONS";

        boolean connectionAppended = connectionService
            .latestIfEqual(LEGACY_CONNECTION_ID, baseUrl, LEGACY_CREDENTIAL_REF, protocol).isEmpty();
        ConnectionView connection = connectionAppended
            ? connectionService.register(LEGACY_CONNECTION_ID, baseUrl, LEGACY_CREDENTIAL_REF, protocol,
                enabled, null)
            : connectionService.latest(LEGACY_CONNECTION_ID);

        boolean profileAppended = profileService
            .latestIfEqual(LEGACY_PROFILE_ID, LEGACY_CONNECTION_ID, textModel).isEmpty();
        ProfileView profile = profileAppended
            ? profileService.register(LEGACY_PROFILE_ID, LEGACY_CONNECTION_ID, textModel, List.of(),
                "{}", legacyVerification())
            : profileService.latest(LEGACY_PROFILE_ID);

        boolean policyImported = importExplicitTimeout(legacyTimeoutMs);

        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("capabilities", "未声明（[]）——旧数据不产生能力假设，未知能力默认不发送可选参数");
        notes.put("protocol", protocol + "（provider_type=" + providerType + "）");
        notes.put("policy", policyImported
            ? "旧超时 " + legacyTimeoutMs + "ms 已作为 EXPLICIT 覆盖导入 " + LEGACY_POLICY_TASK_TYPE
            : "旧超时无有效值或与现有覆盖一致——未新增覆盖");
        notes.put("rollbackWindow", "历史版本与旧 ai_system_configs 行均保留，可按版本复读");
        return new ImportReport(connection.connectionId(), connection.version(), profile.profileId(),
            profile.version(), protocol, List.of(), "UNKNOWN", connectionAppended, profileAppended,
            policyImported, Map.copyOf(notes));
    }

    private boolean importExplicitTimeout(int legacyTimeoutMs) {
        if (legacyTimeoutMs <= 0) {
            return false;
        }
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
            "select per_call_timeout_ms from ai_run_policies where scope = 'task_override' and task_type = ?",
            LEGACY_POLICY_TASK_TYPE);
        if (!existing.isEmpty() && existing.get(0).get("per_call_timeout_ms") != null
                && ((Number) existing.get(0).get("per_call_timeout_ms")).intValue() == legacyTimeoutMs) {
            return false;
        }
        runPolicyService.saveTaskOverride(LEGACY_POLICY_TASK_TYPE, new SaveAiRunPolicyRequest(
            "legacy-import", true, legacyTimeoutMs, null, null, null, null));
        return true;
    }

    private String legacyVerification() {
        try {
            Map<String, String> verification = new LinkedHashMap<>();
            verification.put("source", "legacy-import");
            verification.put("status", "UNKNOWN");
            return objectMapper.writeValueAsString(verification);
        } catch (RuntimeException unavailable) {
            return AiModelProfileService.UNKNOWN_VERIFICATION;
        }
    }
}
