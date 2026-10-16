package com.haitlify.aitest.platform.ai.runtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.ai.event.AiRunEvent;
import com.haitlify.aitest.platform.ai.event.AiRunEventService;
import com.haitlify.aitest.platform.ai.gateway.AiGatewaySettingsService;
import com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings;
import com.haitlify.aitest.platform.ai.runtime.transport.AiProtocolTransportClient;
import com.haitlify.aitest.platform.ai.runtime.transport.AiTransportRequest;
import com.haitlify.aitest.platform.ai.runtime.transport.AiTransportResult;

import tools.jackson.databind.JsonNode;

/**
 * T06 默认 Runtime 实现。严格按接口声明的固定十步执行：
 *
 * <ol>
 *   <li>authorize：job 与请求的项目/归属一致性校验（提交方 API 与派发方负责完整策略
 *       授权；T07 将策略服务并入此处）。</li>
 *   <li>snapshot：解析网关设置（快照进 attempt 的 route 摘要）。</li>
 *   <li>reserve budget：打开 T05 台账（整体截止 + per-call 预算）。</li>
 *   <li>build evidence：证据 ID 归一（缺省与请求输入里的 evidenceIds 合并）。</li>
 *   <li>resolve compatible route：由快照解析协议/端点/模型（能力 TEXT）。</li>
 *   <li>persist attempt：请求前落库 pending（REQUIRES_NEW，业务回滚不抹）。</li>
 *   <li>transport：按「min(per-call, 距整体截止剩余)」的超时发出；剩余 ≤0 不发请求。</li>
 *   <li>validate：HTTP 2xx + 可用文本 + JSON 解析；失败用分类器归类。</li>
 *   <li>persist candidate：产物（outputRef）随节点写回（worker 落 CHECKPOINT）。</li>
 *   <li>finalize usage/events：attempt 终态 + 事件 + TTFB。</li>
 * </ol>
 */
@Service
public class DefaultAiRuntime implements AiRuntime {

    private static final long DEFAULT_WHOLE_RUN_DEADLINE_MS = 300_000L;

    private final AiGatewaySettingsService settingsService;
    private final AiProtocolTransportClient transportClient;
    private final AiAttemptRepository attempts;
    private final AiExecutionLedger ledger;
    private final AiJobService jobs;
    private final AiRunEventService eventService;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private Clock clock = Clock.systemUTC();

    public DefaultAiRuntime(AiGatewaySettingsService settingsService, AiProtocolTransportClient transportClient,
                            AiAttemptRepository attempts, AiExecutionLedger ledger, AiJobService jobs,
                            AiRunEventService eventService, tools.jackson.databind.ObjectMapper objectMapper) {
        this(settingsService, transportClient, attempts, ledger, jobs, eventService, objectMapper,
            AiRuntimeSwitches.Switches.empty());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultAiRuntime(AiGatewaySettingsService settingsService, AiProtocolTransportClient transportClient,
                            AiAttemptRepository attempts, AiExecutionLedger ledger, AiJobService jobs,
                            AiRunEventService eventService, tools.jackson.databind.ObjectMapper objectMapper,
                            @org.springframework.beans.factory.annotation.Value(
                                "${app.ai.runtime.disabled-task-types:}") String disabledTaskTypes,
                            @org.springframework.beans.factory.annotation.Value(
                                "${app.ai.runtime.disabled-projects:}") String disabledProjects) {
        this(settingsService, transportClient, attempts, ledger, jobs, eventService, objectMapper,
            AiRuntimeSwitches.parse(disabledTaskTypes, disabledProjects));
    }

    DefaultAiRuntime(AiGatewaySettingsService settingsService, AiProtocolTransportClient transportClient,
                     AiAttemptRepository attempts, AiExecutionLedger ledger, AiJobService jobs,
                     AiRunEventService eventService, tools.jackson.databind.ObjectMapper objectMapper,
                     AiRuntimeSwitches.Switches runtimeSwitches) {
        this.settingsService = settingsService;
        this.transportClient = transportClient;
        this.attempts = attempts;
        this.ledger = ledger;
        this.jobs = jobs;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.runtimeSwitches = runtimeSwitches;
    }

    private final AiRuntimeSwitches.Switches runtimeSwitches;

    void useClock(Clock testClock) {
        this.clock = testClock;
    }

    @Override
    public RuntimeExecution execute(AiJobService.AiJob job, String nodeKey, AiRuntimeModels.AiTaskRequest request) {
        // 1) authorize
        if (job == null || request == null || nodeKey == null || nodeKey.isBlank()) {
            return blocked(job, nodeKey, request, "", "invalid_input");
        }
        if (job.projectId() == null || !job.projectId().equals(request.projectId())) {
            return blocked(job, nodeKey, request, "", "project_mismatch");
        }
        // T15-R5：发布开关覆盖 job Runtime 路径——禁用范围在 snapshot/预算/attempt 之前直接阻断，
        // 与 facade 同语义（只阻断、不旁路，绝不发出任何模型请求）。
        if (runtimeSwitches.isDisabled(request.taskType(), job.projectId())) {
            return blocked(job, nodeKey, request, "", "runtime_disabled");
        }
        String runId = request.runId() == null || request.runId().isBlank() ? job.jobId() : request.runId();

        // 2) snapshot
        ResolvedAiGatewaySettings settings;
        try {
            settings = settingsService.loadDefaultTextSettings();
        } catch (Exception snapshotFailure) {
            return failed(job, nodeKey, runId, "", "snapshot_failed");
        }

        // 3) reserve budget（T05 台账；整体截止固定窗口；已有台账不得被覆盖）
        ledger.openIfAbsent(runId, clock.instant().plusMillis(DEFAULT_WHOLE_RUN_DEADLINE_MS));

        // 4) build evidence
        List<String> evidenceIds = request.evidenceIds() == null ? List.of() : request.evidenceIds();
        String prompt = buildPrompt(request, evidenceIds);

        // 5) resolve compatible route
        String routeSummary = "protocol=" + settings.providerType() + " model=" + settings.model()
            + " baseUrl=" + settings.baseUrl();

        // 6) persist attempt（请求前）
        String attemptId = attempts.begin(job.jobId(), nodeKey, runId,
            "task=" + request.taskType() + " evidence=" + evidenceIds.size() + " promptChars=" + prompt.length(),
            routeSummary, job.deploymentRevision());

        // 7) transport（预算收窄；剩余 ≤0 不发请求）
        long remaining = ledger.remainingBudgetMs(runId, settings.timeoutMs(), clock);
        if (remaining <= 0) {
            attempts.finalize(attemptId, new AiAttemptRepository.AttemptOutcome(
                AiAttemptRepository.STATUS_FAILED, null, "", "RUN_DEADLINE_EXCEEDED", "RUN_DEADLINE_EXCEEDED",
                false, null, null, null, null));
            emit(runId, "runtime_attempt_failed", "warn", "整体运行时限已到，未发出模型请求", "RUN_DEADLINE_EXCEEDED");
            ledger.close(runId);
            return new RuntimeExecution(job.jobId(), nodeKey, runId, attemptId, "blocked",
                "run_deadline_exceeded", null, "", job.legacyRunRef());
        }

        // 8) transport + validate
        AiTransportResult transport;
        try {
            transport = transportClient.execute(new AiTransportRequest(
                protocol(settings.providerType()), settings.baseUrl(), settings.model(), settings.apiKey(),
                prompt, List.of(AiRuntimeModels.Capability.TEXT), Map.of(), Duration.ofMillis(remaining)));
        } catch (Exception transportFailure) {
            AiFailureClassifier.Classification classification = AiFailureClassifier.classify(0, transportFailure);
            attempts.finalize(attemptId, new AiAttemptRepository.AttemptOutcome(
                AiAttemptRepository.STATUS_FAILED, null, "", classification.reason().name(),
                classification.reason().name(), false, null, null, null, null));
            emit(runId, "runtime_attempt_failed", "warn", "模型调用失败：" + classification.reason().name(),
                classification.reason().name());
            ledger.close(runId);
            return new RuntimeExecution(job.jobId(), nodeKey, runId, attemptId, "failed",
                classification.reason().name(), null, "", job.legacyRunRef());
        }
        if (transport.httpStatus() < 200 || transport.httpStatus() >= 300) {
            AiFailureClassifier.Classification classification =
                AiFailureClassifier.classify(transport.httpStatus(), null);
            attempts.finalize(attemptId, new AiAttemptRepository.AttemptOutcome(
                AiAttemptRepository.STATUS_FAILED, transport.httpStatus(), safeProviderRequestId(transport),
                classification.reason().name(), classification.reason().name(), false, null, null,
                transport.ttfbMillis(), transport.durationMillis()));
            emit(runId, "runtime_attempt_failed", "warn",
                "模型服务返回 " + transport.httpStatus() + "：" + classification.reason().name(),
                classification.reason().name());
            ledger.close(runId);
            return new RuntimeExecution(job.jobId(), nodeKey, runId, attemptId, "failed",
                classification.reason().name(), null, safeProviderRequestId(transport), job.legacyRunRef());
        }
        if (!transport.response().hasUsableText()) {
            AiFailureClassifier.Classification classification = AiFailureClassifier.classifyUnusableBody(
                transport.response().finishReason(), transport.response().refusal() != null);
            attempts.finalize(attemptId, new AiAttemptRepository.AttemptOutcome(
                AiAttemptRepository.STATUS_FAILED, transport.httpStatus(), safeProviderRequestId(transport),
                classification.reason().name(), classification.reason().name(), false, null, null,
                transport.ttfbMillis(), transport.durationMillis()));
            emit(runId, "runtime_attempt_failed", "warn",
                "模型输出不可用：" + classification.reason().name(), classification.reason().name());
            ledger.close(runId);
            return new RuntimeExecution(job.jobId(), nodeKey, runId, attemptId, "failed",
                classification.reason().name(), null, safeProviderRequestId(transport), job.legacyRunRef());
        }

        String outputJson;
        try {
            JsonNode output = objectMapper.readTree(stripJsonFence(transport.response().text()));
            outputJson = objectMapper.writeValueAsString(output);
        } catch (Exception invalidJson) {
            attempts.finalize(attemptId, new AiAttemptRepository.AttemptOutcome(
                AiAttemptRepository.STATUS_FAILED, transport.httpStatus(), safeProviderRequestId(transport),
                "INCOMPLETE_OUTPUT", "INCOMPLETE_OUTPUT", usageKnown(transport), usageInput(transport),
                usageOutput(transport), transport.ttfbMillis(), transport.durationMillis()));
            emit(runId, "runtime_attempt_failed", "warn", "模型输出不是合法 JSON", "INCOMPLETE_OUTPUT");
            ledger.close(runId);
            return new RuntimeExecution(job.jobId(), nodeKey, runId, attemptId, "failed",
                "incomplete_output", null, safeProviderRequestId(transport), job.legacyRunRef());
        }

        // 9) persist candidate：产物由 worker 以 outputRef 落节点 checkpoint；此处返回。
        // 10) finalize usage/events
        AiProtocolResponse usageSource = transport.response();
        attempts.finalize(attemptId, new AiAttemptRepository.AttemptOutcome(
            AiAttemptRepository.STATUS_SUCCEEDED, transport.httpStatus(), safeProviderRequestId(transport),
            "", "", usageKnown(transport), usageInput(transport), usageOutput(transport),
            transport.ttfbMillis(), transport.durationMillis()));
        if (transport.ttfbMillis() != null && job.fencingToken() > 0) {
            jobs.markFirstToken(job.jobId(), job.fencingToken());
        }
        emit(runId, "runtime_attempt_succeeded", "info",
            "模型调用成功（" + usageSummary(usageSource) + "）", "");
        ledger.close(runId);
        return new RuntimeExecution(job.jobId(), nodeKey, runId, attemptId, "succeeded",
            "", outputJson, safeProviderRequestId(transport), job.legacyRunRef());
    }

    // ------------------------------------------------------------------ helpers

    private RuntimeExecution blocked(AiJobService.AiJob job, String nodeKey, AiRuntimeModels.AiTaskRequest request,
                                     String attemptId, String reason) {
        return new RuntimeExecution(job == null ? "" : job.jobId(), nodeKey, request == null ? "" : request.runId(),
            attemptId, "blocked", reason, null, "", job == null ? null : job.legacyRunRef());
    }

    private RuntimeExecution failed(AiJobService.AiJob job, String nodeKey, String runId, String attemptId,
                                    String reason) {
        return new RuntimeExecution(job.jobId(), nodeKey, runId, attemptId, "failed", reason, null, "",
            job.legacyRunRef());
    }

    private String buildPrompt(AiRuntimeModels.AiTaskRequest request, List<String> evidenceIds) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请按给定的分析要求处理下面的输入，仅输出 JSON。\n");
        prompt.append("task=").append(request.taskType()).append('\n');
        if (!evidenceIds.isEmpty()) {
            prompt.append("evidenceIds=").append(String.join(",", evidenceIds)).append('\n');
        }
        JsonNode input = request.input();
        if (input != null && !input.isMissingNode()) {
            prompt.append("input=").append(input.toString()).append('\n');
        }
        return prompt.toString();
    }

    private AiRuntimeModels.Protocol protocol(String providerType) {
        return "responses".equalsIgnoreCase(providerType)
            ? AiRuntimeModels.Protocol.RESPONSES : AiRuntimeModels.Protocol.CHAT_COMPLETIONS;
    }

    private static String stripJsonFence(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstLine = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstLine > 0 && lastFence > firstLine) {
                return trimmed.substring(firstLine + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private static String safeProviderRequestId(AiTransportResult transport) {
        return transport.response() == null || transport.response().providerRequestId() == null
            ? "" : transport.response().providerRequestId();
    }

    private static boolean usageKnown(AiTransportResult transport) {
        return transport.response() != null && transport.response().usage() != null
            && transport.response().usage().inputTokens() != null
            && transport.response().usage().outputTokens() != null;
    }

    private static Integer usageInput(AiTransportResult transport) {
        return usageKnown(transport) ? transport.response().usage().inputTokens() : null;
    }

    private static Integer usageOutput(AiTransportResult transport) {
        return usageKnown(transport) ? transport.response().usage().outputTokens() : null;
    }

    private static String usageSummary(AiProtocolResponse response) {
        if (response == null || response.usage() == null || response.usage().inputTokens() == null
                || response.usage().outputTokens() == null) {
            return "usage=unknown";
        }
        return "input=" + response.usage().inputTokens() + " output=" + response.usage().outputTokens();
    }

    private void emit(String runId, String stage, String severity, String summary, String retryReason) {
        eventService.emit(new AiRunEvent(runId, Instant.now(), stage, severity, summary,
            null, 0, null, "", "", "", 0L, retryReason, "", 0L));
    }
}
