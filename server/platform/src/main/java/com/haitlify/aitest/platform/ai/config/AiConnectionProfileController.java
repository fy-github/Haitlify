package com.haitlify.aitest.platform.ai.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * T11：连接/模型档案编辑器 API（增量 REST 面，不改变既有端点）。
 *
 * <ul>
 *   <li>连接：URL / 凭据引用（opaque）/ 协议 / 启用 / 允许项目——版本化追加，历史可按版本读取；</li>
 *   <li>档案：model / 能力声明 / 参数策略 / 验证状态——未知能力 fail-closed；</li>
 *   <li>有效配置：超时溯源（{value, source, sourceVersion}）——显式任务覆盖 &gt; 连接默认（legacy 全局超时），
 *       未声明字段不发送；任务目录仅作展示，不做能力推断。</li>
 * </ul>
 */
@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/configuration")
class AiConnectionProfileController {

    private final AiConnectionService connectionService;
    private final AiModelProfileService profileService;
    private final AiEffectiveOptionsResolver resolver;
    private final JdbcTemplate jdbcTemplate;

    AiConnectionProfileController(AiConnectionService connectionService, AiModelProfileService profileService,
                                  AiEffectiveOptionsResolver resolver, JdbcTemplate jdbcTemplate) {
        this.connectionService = connectionService;
        this.profileService = profileService;
        this.resolver = resolver;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/connections")
    List<ConnectionView> listConnections() {
        return connectionService.list();
    }

    @PostMapping("/connections")
    ConnectionView registerConnection(@RequestBody RegisterConnectionRequest request) {
        return connectionService.register(request.connectionId(), request.baseUrl(), request.credentialRef(),
            request.protocol(), Boolean.TRUE.equals(request.enabled()), request.allowedProjectIds(),
            request.expectedVersion());
    }

    @GetMapping("/connections/{connectionId}")
    ConnectionView getConnection(@PathVariable String connectionId,
                                 @RequestParam(name = "version", required = false) Integer version) {
        return version == null ? connectionService.latest(connectionId) : connectionService.at(connectionId, version);
    }

    @GetMapping("/profiles")
    List<ProfileView> listProfiles() {
        return profileService.list();
    }

    @PostMapping("/profiles")
    ProfileView registerProfile(@RequestBody RegisterProfileRequest request) {
        return profileService.register(request.profileId(), request.connectionId(), request.modelId(),
            request.capabilities(), request.parameterPolicyJson(), request.verificationJson(),
            request.expectedVersion());
    }

    @GetMapping("/profiles/{profileId}")
    ProfileView getProfile(@PathVariable String profileId,
                           @RequestParam(name = "version", required = false) Integer version) {
        return version == null ? profileService.latest(profileId) : profileService.at(profileId, version);
    }

    @GetMapping("/effective-options")
    EffectiveOptionsResponse effectiveOptions(@RequestParam String taskType,
                                              @RequestParam(required = false) String profileId) {
        // 显式任务覆盖（task_override）优先；无覆盖 ⇒ 连接默认（legacy 全局超时）。
        List<Map<String, Object>> overrides = jdbcTemplate.queryForList(
            "select per_call_timeout_ms, updated_at from ai_run_policies where scope = 'task_override'"
                + " and task_type = ? and enabled = true", taskType);
        Integer explicitTimeout = overrides.isEmpty() || overrides.get(0).get("per_call_timeout_ms") == null
            ? null : ((Number) overrides.get(0).get("per_call_timeout_ms")).intValue();
        List<Map<String, Object>> legacy = jdbcTemplate.queryForList(
            "select request_timeout_ms from ai_system_configs where config_key = 'default'");
        int connectionDefault = legacy.isEmpty() || legacy.get(0).get("request_timeout_ms") == null
            ? 0 : ((Number) legacy.get(0).get("request_timeout_ms")).intValue();

        var timeout = resolver.resolveTimeout(explicitTimeout,
            overrides.isEmpty() ? null : "policy@task_override",
            connectionDefault, "legacy-config@default", null, null);

        List<String> capabilities = List.of();
        String verification = AiModelProfileService.UNKNOWN_VERIFICATION;
        Integer profileVersion = null;
        if (profileId != null && !profileId.isBlank()) {
            ProfileView profile = profileService.latest(profileId);
            capabilities = profile.capabilities();
            verification = profile.verificationJson();
            profileVersion = profile.version();
        }
        List<String> taskTypes = com.haitlify.aitest.platform.ai.assistant.AiTaskCatalog.all().stream()
            .map(com.haitlify.aitest.platform.ai.assistant.AiTaskCatalog.TaskDefinition::taskType).toList();

        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("timeout", "显式任务覆盖 > 连接默认（legacy 全局超时）；平台上限（如配置）收紧所有来源");
        notes.put("parameters", "未声明字段不发送——档案参数策略缺失时省略该字段");
        notes.put("taskTypes", "任务目录仅作展示，不代表该档案已通过任何任务的能力验证");

        return new EffectiveOptionsResponse(taskType, profileId, profileVersion,
            new EffectiveOptionView(timeout.value(), timeout.source(), timeout.sourceVersion(), timeout.present()),
            capabilities, verification, taskTypes, Map.copyOf(notes));
    }
}
