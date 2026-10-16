package com.haitlify.aitest.platform.ai.assistant;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.ai.agentic.StaleArtifactRevisionException;
import com.haitlify.aitest.platform.ai.knowledge.AiContextBuilder;
import com.haitlify.aitest.platform.ai.evidence.AiEvidenceManifestService;
import com.haitlify.aitest.platform.ai.event.AiRunEvent;
import com.haitlify.aitest.platform.ai.event.AiRunEventService;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * T12：QA 助手执行服务。
 *
 * <ul>
 *   <li>输入缺项在入口即 4xx（缺项清单随响应），绝不用默认值掩盖；</li>
 *   <li>模型调用唯一经 {@link AiTaskExecutionFacade}（台账/attempt/预算统一）；</li>
 *   <li>输出必须过 {@link QaTaskOutputValidators} 的证据规则；违规不会被静默丢弃，
 *       而是随产物一并落库供人审；</li>
 *   <li>产物 revision 不可变：修改产生新 revision，过期 revision 冲突（409）；
 *       人工确认强制已认证 actor，拒绝阻止下游。</li>
 * </ul>
 */
@Service
public class AiAssistantService {

    public static final String STATE_PENDING = "PENDING_HUMAN_CONFIRMATION";
    public static final String STATE_ACCEPTED = "ACCEPTED";
    public static final String STATE_REJECTED = "REJECTED";

    public record AssistantRun(
        String runId,
        String taskType,
        Long projectId,
        String status,
        JsonNode output,
        List<String> violations,
        String artifactId,
        int revision,
        String acceptanceState,
        String blockReason,
        List<String> missingInputs,
        JsonNode evidenceManifest
    ) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AiTaskExecutionFacade facade;
    private final AiEvidenceManifestService evidenceManifestService;
    private final AiContextBuilder contextBuilder;
    private final AiRunEventService runEventService;
    private final com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization authorization;
    private final com.haitlify.aitest.platform.ai.tool.mcp.AiMcpToolExecutor mcpToolExecutor;

    public AiAssistantService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                              AiTaskExecutionFacade facade,
                              AiEvidenceManifestService evidenceManifestService,
                              AiContextBuilder contextBuilder,
                              AiRunEventService runEventService) {
        this(jdbcTemplate, objectMapper, facade, evidenceManifestService, contextBuilder, runEventService, null, null);
    }

    public AiAssistantService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                              AiTaskExecutionFacade facade,
                              AiEvidenceManifestService evidenceManifestService,
                              AiContextBuilder contextBuilder,
                              AiRunEventService runEventService,
                              com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization authorization) {
        this(jdbcTemplate, objectMapper, facade, evidenceManifestService, contextBuilder, runEventService,
            authorization, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiAssistantService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                              AiTaskExecutionFacade facade,
                              AiEvidenceManifestService evidenceManifestService,
                              AiContextBuilder contextBuilder,
                              AiRunEventService runEventService,
                              com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization authorization,
                              com.haitlify.aitest.platform.ai.tool.mcp.AiMcpToolExecutor mcpToolExecutor) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.facade = facade;
        this.evidenceManifestService = evidenceManifestService;
        this.contextBuilder = contextBuilder;
        this.runEventService = runEventService;
        this.authorization = authorization;
        this.mcpToolExecutor = mcpToolExecutor;
    }

    private void requireProjectAccess(Long projectId) {
        if (authorization != null) {
            authorization.requireProjectAccess(projectId);
        }
    }

    /** 启动一个助手任务：预检 → 证据装配 → 真实模型调用（经 facade）→ 输出校验 → 产物落库。 */
    public AssistantRun startRun(String taskType, Long projectId, JsonNode inputs, String actorId) {
        return startRun(taskType, projectId, inputs, actorId, null);
    }

    /**
     * 组合任务入口：{@code prebuiltManifest} 由组合运行构建并要求所有依赖节点复用同一清单
     * （T12：首个案例 Bug→日志→回归共享一份 evidence manifest）；null 时按项目现取。
     */
    AssistantRun startRun(String taskType, Long projectId, JsonNode inputs, String actorId,
                          AiEvidenceManifestService.EvidenceManifest prebuiltManifest) {
        AiTaskCatalog.TaskDefinition definition = AiTaskCatalog.find(taskType).orElseThrow(
            () -> new IllegalArgumentException("unknown_task_type: " + taskType));
        AiTaskCatalog.RequestValidation validation = AiTaskCatalog.validateRequest(taskType, inputs);
        if (!validation.complete()) {
            throw new IllegalArgumentException("missing_inputs: " + String.join(", ", validation.missingInputs()));
        }
        if (projectId == null) {
            throw new IllegalArgumentException("projectId required");
        }
        // T14：项目隔离——创建即校验（失败与不存在同形 404）。
        requireProjectAccess(projectId);

        String runId = "assistant-" + UUID.randomUUID();
        AiEvidenceManifestService.EvidenceManifest manifest = prebuiltManifest != null
            ? prebuiltManifest : evidenceManifestService.manifestForProject(projectId);
        AiContextBuilder.BuiltContext context = contextBuilder.build(projectId, queryFor(definition, inputs), 4000);

        emit(runId, "run_started", "info", definition.displayName() + " 任务已排队");
        // T09 Exit Gate（T-R5 修复）：bug_analysis/log_triage 真实使用 MCP 只读工具证据——
        // 配置了启用服务器且服务端裁决为 readOnly 的工具会被真实调用，结果以 TOOL_OBSERVED
        // 证据进入 prompt 与产物来源清单；不可用/被策略拒绝一律如实记录，绝不编造。
        JsonNode toolObservations = collectMcpEvidence(definition.taskType(), projectId, runId,
            actorId == null ? "" : actorId);
        JsonNode prompt = buildPrompt(definition, inputs, context, manifest, toolObservations);
        AiGenerationResult result;
        try {
            result = facade.generate(new AiTaskExecutionFacade.TaskExecution(
                definition.taskType(), projectId, actorId, runId, prompt, null));
        } catch (RuntimeException failure) {
            emit(runId, "run_failed", "error", "模型调用异常: " + safe(failure.getMessage()));
            return new AssistantRun(runId, definition.taskType(), projectId, "failed", null, List.of(),
                null, 0, "", "模型调用异常: " + safe(failure.getMessage()), List.of(),
                objectMapper.valueToTree(manifest));
        }

        if (result == null || !result.success()) {
            String reason = result == null ? "无响应"
                : (result.errorMessage() == null || result.errorMessage().isBlank()
                    ? safe(result.retryReason()) : result.errorMessage());
            // 真实失败如实 BLOCKED——绝不显示「AI 分析完成」。
            emit(runId, "run_failed", "error", "运行被阻塞: " + safe(reason));
            return new AssistantRun(runId, definition.taskType(), projectId, "blocked", null, List.of(),
                null, 0, "", reason, List.of(), objectMapper.valueToTree(manifest));
        }

        JsonNode output;
        try {
            output = objectMapper.readTree(result.content() == null ? "" : result.content());
        } catch (Exception malformed) {
            emit(runId, "run_failed", "error", "输出不可解析");
            return new AssistantRun(runId, definition.taskType(), projectId, "blocked", null,
                List.of("output_malformed: 模型输出不是合法 JSON"), null, 0, "",
                "output_malformed", List.of(), objectMapper.valueToTree(manifest));
        }

        // 报告任务的数值必须与确定性事实一致：先算事实，再校验模型输出。
        JsonNode deterministicFacts = "report_draft".equals(definition.taskType())
            ? computedReportFacts(inputs) : null;
        QaTaskOutputValidators.Validation outputValidation =
            QaTaskOutputValidators.validate(definition.taskType(), output, deterministicFacts);

        String artifactId = "artifact-" + UUID.randomUUID();
        // 人工确认必须来自已认证操作人；产物一律从 PENDING_HUMAN_CONFIRMATION 起步。
        String sourceManifestJson = manifestJson(manifest);
        if (toolObservations != null && !toolObservations.isEmpty()) {
            try {
                ObjectNode merged = objectMapper.createObjectNode();
                merged.set("evidenceManifest", objectMapper.valueToTree(manifest));
                merged.set("toolObservations", toolObservations);
                sourceManifestJson = merged.toString();
            } catch (Exception ignored) {
                // 组装失败时保留原始 manifest（工具证据仍在 prompt 中）。
            }
        }
        jdbcTemplate.update("""
            insert into ai_assistant_artifacts(
              artifact_id, run_id, task_type, project_id, revision, acceptance_state, result_origin,
              payload_json, violations_json, source_manifest_json, created_by, reviewed_by, created_at, updated_at,
              deterministic_facts_json)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            artifactId, runId, definition.taskType(), projectId, 1, STATE_PENDING, "MODEL_GENERATED",
            output.toString(), toJsonArray(outputValidation.violations()), sourceManifestJson,
            actorId == null ? "" : actorId, "", java.sql.Timestamp.from(Instant.now()),
            java.sql.Timestamp.from(Instant.now()),
            deterministicFacts == null ? null : deterministicFacts.toString());
        emit(runId, "run_succeeded", outputValidation.valid() ? "info" : "warning",
            outputValidation.valid() ? "生成完成，等待人工确认" : "生成完成但存在证据违规，等待人工确认");
        return new AssistantRun(runId, definition.taskType(), projectId, "waiting_review", output,
            outputValidation.violations(), artifactId, 1, STATE_PENDING, "", List.of(),
            objectMapper.valueToTree(manifest));
    }

    /**
     * 草稿测试点目录（testcase_generation 运行卡 draftPointIds 数据源）：
     * 仅列可用于生成的 review_status ∈ (confirmed, modified)——与 generateCases:545-546 的准入一致；
     * projectId 必填 fail-closed，limit 上限 50，新→旧稳定排序。
     */
    public Map<String, Object> listDraftPoints(long projectId, Integer limit) {
        requireProjectAccess(projectId);
        int capped = limit == null ? 20 : Math.max(1, Math.min(limit, 50));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select point_id, title, module_name, case_kind, review_status, updated_at"
                + " from ai_draft_test_points"
                + " where project_id = ? and review_status in ('confirmed', 'modified')"
                + " order by updated_at desc, point_id desc limit ?",
            projectId, capped);
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("pointId", row.get("point_id"));
            item.put("title", row.get("title"));
            item.put("moduleName", row.get("module_name"));
            item.put("caseKind", row.get("case_kind"));
            item.put("reviewStatus", row.get("review_status"));
            item.put("updatedAt", row.get("updated_at"));
            items.add(item);
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("items", items);
        result.put("limit", capped);
        return result;
    }

    /**
     * 近期产物列表（评审 UI 的数据源）：projectId 必填（fail-closed，无跨项目泄漏）；
     * limit 默认 20、上限 50（服务端收紧）；严格按 created_at desc, artifact_id desc 稳定排序。
     * status 与 {runId} 回读同义词：accepted / rejected / waiting_review。
     */
    public Map<String, Object> listArtifacts(long projectId, Integer limit) {
        requireProjectAccess(projectId);
        int capped = limit == null ? 20 : Math.max(1, Math.min(limit, 50));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select artifact_id, run_id, task_type, project_id, revision, acceptance_state, reviewed_by, created_at"
                + " from ai_assistant_artifacts where project_id = ?"
                + " order by created_at desc, artifact_id desc limit ?",
            projectId, capped);
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("artifactId", row.get("artifact_id"));
            item.put("runId", row.get("run_id"));
            item.put("taskType", row.get("task_type"));
            item.put("projectId", row.get("project_id"));
            item.put("revision", row.get("revision"));
            item.put("acceptanceState", row.get("acceptance_state"));
            item.put("status", statusFor(String.valueOf(row.get("acceptance_state"))));
            item.put("reviewedBy", row.get("reviewed_by"));
            item.put("createdAt", row.get("created_at"));
            items.add(item);
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("artifacts", items);
        result.put("limit", capped);
        return result;
    }

    private String statusFor(String state) {
        return STATE_ACCEPTED.equals(state) ? "accepted"
            : STATE_REJECTED.equals(state) ? "rejected" : "waiting_review";
    }

    /**
     * T11:314 导出腿：导出已接受（ACCEPTED）的产物——结构化导出载荷（输出 / 来源清单 / 违规记录）。
     * 未接受 ⇒ 400 `artifact_not_accepted`（fail-closed，绝不导出未经人工确认的产物）；
     * 过期 revision ⇒ 409 `stale_revision`；不存在/无权 ⇒ 统一 404。
     */
    public Map<String, Object> exportArtifact(String artifactId, Integer revision) {
        Map<String, Object> row;
        try {
            row = artifactRow(artifactId);
        } catch (IllegalArgumentException notFoundRow) {
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
        requireProjectAccess(((Number) row.get("project_id")).longValue());
        int currentRevision = ((Number) row.get("revision")).intValue();
        if (revision != null && revision != currentRevision) {
            throw new StaleArtifactRevisionException(
                "artifact revision is stale: expected " + revision + " but current is " + currentRevision);
        }
        String state = String.valueOf(row.get("acceptance_state"));
        if (!STATE_ACCEPTED.equals(state)) {
            throw new IllegalArgumentException("artifact_not_accepted: 当前状态 " + state + " 不可导出（仅人工确认后的产物）");
        }
        // 导出前复检：历史上由旧规则接受的产物若复检不过，fail-closed 并保留违规记录。
        QaTaskOutputValidators.Validation exportRecheck = QaTaskOutputValidators.validate(
            String.valueOf(row.get("task_type")), parseJson(String.valueOf(row.getOrDefault("payload_json", "null"))),
            deterministicFactsFrom(row));
        if (!exportRecheck.valid()) {
            List<String> kept = mergeViolations(
                parseViolations(String.valueOf(row.getOrDefault("violations_json", "[]"))),
                exportRecheck.violations());
            jdbcTemplate.update(
                "update ai_assistant_artifacts set violations_json = ?, updated_at = ? where artifact_id = ?",
                toJsonArray(kept), java.sql.Timestamp.from(Instant.now()), artifactId);
            throw new IllegalArgumentException("unresolved_violations: 已接受产物复检出确定性违规，禁止导出："
                + String.join("；", exportRecheck.violations()));
        }
        Map<String, Object> exported = new java.util.LinkedHashMap<>();
        exported.put("artifactId", artifactId);
        exported.put("runId", row.get("run_id"));
        exported.put("taskType", row.get("task_type"));
        exported.put("projectId", row.get("project_id"));
        exported.put("revision", currentRevision);
        exported.put("acceptanceState", state);
        exported.put("reviewedBy", row.get("reviewed_by"));
        exported.put("exportedAt", java.time.Instant.now().toString());
        exported.put("output", parseJson(String.valueOf(row.getOrDefault("payload_json", "null"))));
        exported.put("sourceManifest", parseJson(String.valueOf(row.getOrDefault("source_manifest_json", "null"))));
        exported.put("violations", parseJson(String.valueOf(row.getOrDefault("violations_json", "null"))));
        exported.put("deterministicFacts", parseJson(String.valueOf(row.getOrDefault("deterministic_facts_json", "null"))));
        return exported;
    }

    /** 人工评审：approve ⇒ ACCEPTED；reject ⇒ REJECTED（阻止下游）；change ⇒ 新 revision（旧版不可变）。 */
    public AssistantRun review(String artifactId, String decision, Integer expectedRevision, JsonNode candidate) {
        String actor = currentActor();
        Map<String, Object> row;
        try {
            row = artifactRow(artifactId);
        } catch (IllegalArgumentException notFoundRow) {
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
        requireProjectAccess(((Number) row.get("project_id")).longValue());
        int currentRevision = ((Number) row.get("revision")).intValue();
        String currentState = String.valueOf(row.get("acceptance_state"));
        if (expectedRevision != null && expectedRevision != currentRevision) {
            throw new StaleArtifactRevisionException(
                "artifact revision is stale: expected " + expectedRevision + " but current is " + currentRevision);
        }
        String runId = String.valueOf(row.get("run_id"));
        String taskType = String.valueOf(row.get("task_type"));
        long projectId = ((Number) row.get("project_id")).longValue();

        switch (decision == null ? "" : decision.trim().toLowerCase()) {
            case "approve" -> {
                if (STATE_ACCEPTED.equals(currentState)) {
                    return new AssistantRun(runId, taskType, projectId, "accepted",
                        parseJson(String.valueOf(row.getOrDefault("payload_json", "null"))), List.of(),
                        artifactId, currentRevision, STATE_ACCEPTED, "", List.of(), null); // 重复批准幂等
                }
                if (!STATE_PENDING.equals(currentState)) {
                    throw new IllegalArgumentException("artifact_not_pending: 当前状态 " + currentState + " 不可评审");
                }
                // 批准前重新执行确定性校验：存在未解决违规 ⇒ fail-closed，绝不写入 ACCEPTED。
                JsonNode approvePayload = parseJson(String.valueOf(row.getOrDefault("payload_json", "null")));
                QaTaskOutputValidators.Validation recheck =
                    QaTaskOutputValidators.validate(taskType, approvePayload, deterministicFactsFrom(row));
                if (!recheck.valid()) {
                    List<String> kept = mergeViolations(
                        parseViolations(String.valueOf(row.getOrDefault("violations_json", "[]"))),
                        recheck.violations());
                    jdbcTemplate.update(
                        "update ai_assistant_artifacts set violations_json = ?, updated_at = ? where artifact_id = ?",
                        toJsonArray(kept), java.sql.Timestamp.from(Instant.now()), artifactId);
                    emit(runId, "human_approve_blocked", "warning", "审批被阻断：存在未解决的确定性违规");
                    throw new IllegalArgumentException("unresolved_violations: 存在未解决的确定性违规，不得批准："
                        + String.join("；", recheck.violations()));
                }
                int approved = jdbcTemplate.update(
                    "update ai_assistant_artifacts set acceptance_state = ?, result_origin = ?, reviewed_by = ?,"
                        + " updated_at = ? where artifact_id = ? and acceptance_state = ? and revision = ?",
                    STATE_ACCEPTED, "HUMAN_EDITED", actor, java.sql.Timestamp.from(Instant.now()), artifactId,
                    STATE_PENDING, currentRevision);
                if (approved == 0) {
                    return resolveReviewRace(artifactId, "approve", STATE_ACCEPTED, () -> new AssistantRun(
                        runId, taskType, projectId, "accepted",
                        parseJson(String.valueOf(artifactRow(artifactId).getOrDefault("payload_json", "null"))),
                        List.of(), artifactId, currentRevision, STATE_ACCEPTED, "", List.of(), null));
                }
                emit(runId, "human_approved", "info", "人工确认通过");
                return new AssistantRun(runId, taskType, projectId, "accepted",
                    parseJson(String.valueOf(row.getOrDefault("payload_json", "null"))), List.of(),
                    artifactId, currentRevision, STATE_ACCEPTED, "", List.of(), null);
            }
            case "reject" -> {
                if (STATE_REJECTED.equals(currentState)) {
                    return new AssistantRun(runId, taskType, projectId, "rejected",
                        parseJson(String.valueOf(row.getOrDefault("payload_json", "null"))), List.of(),
                        artifactId, currentRevision, STATE_REJECTED, "", List.of(), null); // 重复驳回幂等
                }
                if (!STATE_PENDING.equals(currentState)) {
                    throw new IllegalArgumentException("artifact_not_pending: 当前状态 " + currentState + " 不可评审");
                }
                int rejected = jdbcTemplate.update(
                    "update ai_assistant_artifacts set acceptance_state = ?, reviewed_by = ?, updated_at = ?"
                        + " where artifact_id = ? and acceptance_state = ? and revision = ?",
                    STATE_REJECTED, actor, java.sql.Timestamp.from(Instant.now()), artifactId,
                    STATE_PENDING, currentRevision);
                if (rejected == 0) {
                    return resolveReviewRace(artifactId, "reject", STATE_REJECTED, () -> new AssistantRun(
                        runId, taskType, projectId, "rejected",
                        parseJson(String.valueOf(artifactRow(artifactId).getOrDefault("payload_json", "null"))),
                        List.of(), artifactId, currentRevision, STATE_REJECTED, "", List.of(), null));
                }
                emit(runId, "human_rejected", "warning", "人工驳回：下游阻断");
                return new AssistantRun(runId, taskType, projectId, "rejected",
                    parseJson(String.valueOf(row.getOrDefault("payload_json", "null"))), List.of(),
                    artifactId, currentRevision, STATE_REJECTED, "", List.of(), null);
            }
            case "change" -> {
                if (candidate == null || candidate.isNull()) {
                    throw new IllegalArgumentException("candidate required for change");
                }
                if (!STATE_PENDING.equals(currentState)) {
                    throw new IllegalArgumentException("artifact_not_pending: 当前状态 " + currentState + " 不可评审");
                }
                int nextRevision = currentRevision + 1;
                String nextArtifactId = artifactId + "-r" + nextRevision;
                QaTaskOutputValidators.Validation candidateValidation =
                    QaTaskOutputValidators.validate(taskType, candidate, deterministicFactsFrom(row));
                // 旧版不可变：仅当仍处于 pending 且 revision 未变时由本次改稿替换（CAS），
                // 并发双评审/并发改稿时只允许一个赢家继续插入新 revision。
                int superseded = jdbcTemplate.update(
                    "update ai_assistant_artifacts set acceptance_state = ?, reviewed_by = ?, updated_at = ?"
                        + " where artifact_id = ? and acceptance_state = ? and revision = ?",
                    STATE_REJECTED, actor, java.sql.Timestamp.from(Instant.now()), artifactId,
                    STATE_PENDING, currentRevision);
                if (superseded == 0) {
                    throw new StaleArtifactRevisionException(
                        "artifact review state changed concurrently: " + artifactId);
                }
                jdbcTemplate.update("""
                    insert into ai_assistant_artifacts(
                      artifact_id, run_id, task_type, project_id, revision, acceptance_state, result_origin,
                      payload_json, violations_json, source_manifest_json, created_by, reviewed_by, created_at, updated_at,
                      deterministic_facts_json)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    nextArtifactId, runId, taskType, projectId, nextRevision, STATE_PENDING, "HUMAN_EDITED",
                    candidate.toString(), toJsonArray(candidateValidation.violations()),
                    String.valueOf(row.getOrDefault("source_manifest_json", "{}")),
                    String.valueOf(row.getOrDefault("created_by", "")), actor,
                    java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()),
                    row.get("deterministic_facts_json") == null ? null : String.valueOf(row.get("deterministic_facts_json")));
                emit(runId, "human_revised",
                    candidateValidation.valid() ? "info" : "warning",
                    candidateValidation.valid() ? "人工修改：创建 revision " + nextRevision
                        : "人工修改存在未解决违规：创建 revision " + nextRevision + "，需继续修订后才能批准");
                return new AssistantRun(runId, taskType, projectId, "waiting_review", candidate,
                    candidateValidation.violations(), nextArtifactId, nextRevision, STATE_PENDING, "", List.of(), null);
            }
            default -> throw new IllegalArgumentException("unknown_decision: " + decision);
        }
    }

    public AssistantRun getRun(String runId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select artifact_id, task_type, project_id, revision, acceptance_state, payload_json, violations_json"
                + " from ai_assistant_artifacts where run_id = ? order by revision desc limit 1", runId);
        if (rows.isEmpty()) {
            // T14：不存在与无权同形——猜测 runId 无法区分。
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
        Map<String, Object> row = rows.get(0);
        requireProjectAccess(((Number) row.get("project_id")).longValue());
        String state = String.valueOf(row.get("acceptance_state"));
        return new AssistantRun(runId, String.valueOf(row.get("task_type")),
            ((Number) row.get("project_id")).longValue(),
            STATE_ACCEPTED.equals(state) ? "accepted" : STATE_REJECTED.equals(state) ? "rejected" : "waiting_review",
            parseJson(String.valueOf(row.getOrDefault("payload_json", "null"))),
            parseViolations(String.valueOf(row.getOrDefault("violations_json", "[]"))),
            String.valueOf(row.get("artifact_id")), ((Number) row.get("revision")).intValue(), state, "", List.of(), null);
    }

    // ---------- 内部 ----------

    private String queryFor(AiTaskCatalog.TaskDefinition definition, JsonNode inputs) {
        // 检索查询取用户输入里最长的文本字段；空输入以任务名兜底（只影响检索排序）。
        String best = "";
        if (inputs != null && inputs.isObject()) {
            for (JsonNode value : inputs) {
                if (value.isTextual() && value.asText().length() > best.length()) {
                    best = value.asText();
                }
            }
        }
        return best.isBlank() ? definition.displayName() : best;
    }

    private JsonNode buildPrompt(AiTaskCatalog.TaskDefinition definition, JsonNode inputs,
                                 AiContextBuilder.BuiltContext context,
                                 AiEvidenceManifestService.EvidenceManifest manifest,
                                 JsonNode toolObservations) {
        ObjectNode prompt = objectMapper.createObjectNode();
        prompt.put("instruction", "你是 QA 助手，任务类型 " + definition.taskType()
            + "（" + definition.displayName() + "）。只依据提供的证据与上下文作答；"
            + "证据不足的结论必须标注 needs_confirmation 并列入 missingInputs；"
            + "输出必须满足任务 schema，且携带任务自身 taskType 字段。");
        prompt.set("inputs", inputs == null ? objectMapper.createObjectNode() : inputs);
        ObjectNode contextNode = prompt.putObject("context");
        ArrayNode items = contextNode.putArray("items");
        for (AiContextBuilder.ContextItem item : context.items()) {
            ObjectNode node = items.addObject();
            node.put("kind", item.kind());
            node.put("documentId", item.documentId());
            node.put("heading", item.heading());
            node.put("text", item.text());
        }
        contextNode.put("estimatedTokens", context.estimatedTokens());
        ArrayNode evidence = prompt.putArray("evidence");
        for (AiEvidenceManifestService.ManifestEntry entry : manifest.entries()) {
            ObjectNode node = evidence.addObject();
            node.put("documentId", entry.documentId());
            node.put("fileName", entry.fileName());
            node.put("parseStatus", entry.parseStatus());
        }
        if (toolObservations != null && !toolObservations.isEmpty()) {
            prompt.set("toolObservations", toolObservations);
        }
        prompt.set("outputSchema", definition.outputSchema());
        return prompt;
    }

    /** MCP 证据采集：仅 bug_analysis/log_triage；只读工具；结果含 nonce 原样透传（可反回显验证）。 */
    private JsonNode collectMcpEvidence(String taskType, Long projectId, String runId, String actorId) {
        if (mcpToolExecutor == null
                || !("bug_analysis".equals(taskType) || "log_triage".equals(taskType))) {
            return null;
        }
        List<Map<String, Object>> servers = jdbcTemplate.queryForList(
            "select server_id, tool_allowlist_json from ai_mcp_servers"
                + " where project_id = ? and enabled = true order by id asc", projectId);
        ArrayNode observations = objectMapper.createArrayNode();
        for (Map<String, Object> server : servers) {
            String serverId = String.valueOf(server.get("server_id"));
            for (String toolName : readOnlyAllowlistedTools(server.get("tool_allowlist_json"))) {
                ObjectNode observation = observations.addObject();
                observation.put("serverId", serverId);
                observation.put("toolName", toolName);
                try {
                    com.haitlify.aitest.platform.ai.tool.mcp.AiMcpToolExecutionResult outcome =
                        mcpToolExecutor.execute(new com.haitlify.aitest.platform.ai.tool.mcp.AiMcpToolExecutionRequest(
                            runId, projectId, serverId, toolName, true,
                            objectMapper.createObjectNode(), actorId));
                    if (outcome != null && outcome.isToolObserved() && outcome.response() != null) {
                        observation.put("resultOrigin", "TOOL_OBSERVED");
                        observation.set("result", outcome.response());
                        emit(runId, "tool_call_completed", "info", "MCP 工具完成: " + toolName);
                    } else {
                        observation.put("resultOrigin", "BLOCKED");
                        observation.put("unavailable", outcome == null || outcome.errorMessage() == null
                            ? "工具未返回结果" : outcome.errorMessage());
                        emit(runId, "tool_call_failed", "warning", "MCP 工具不可用: " + toolName);
                    }
                } catch (RuntimeException failure) {
                    observation.put("resultOrigin", "BLOCKED");
                    observation.put("unavailable", com.haitlify.aitest.platform.ai.security.AiPayloadSanitizer
                        .mask(failure.getMessage() == null ? "工具调用异常" : failure.getMessage()));
                    emit(runId, "tool_call_failed", "warning", "MCP 工具异常: " + toolName);
                }
            }
        }
        return observations.isEmpty() ? null : observations;
    }

    /** 服务器工具白名单中显式 {name, readOnly:true} 的只读工具（旧字符串形式 fail-closed 跳过）。 */
    private List<String> readOnlyAllowlistedTools(Object rawAllowlist) {
        List<String> tools = new ArrayList<>();
        try {
            JsonNode allowlist = objectMapper.readTree(
                rawAllowlist == null ? "[]" : String.valueOf(rawAllowlist));
            if (allowlist.isArray()) {
                for (JsonNode entry : allowlist) {
                    if (entry.isObject() && entry.get("readOnly") != null
                            && entry.get("readOnly").isBoolean() && entry.get("readOnly").asBoolean()) {
                        String name = entry.path("name").asText("");
                        if (!name.isBlank()) {
                            tools.add(name);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 坏配置 ⇒ 不采集（fail-closed）。
        }
        return tools;
    }

    /** 报告数值确定性计算：pass rate 只能来自执行记录（测试点/用例统计），模型不得参与。 */
    private JsonNode computedReportFacts(JsonNode inputs) {
        ObjectNode facts = objectMapper.createObjectNode();
        JsonNode runIds = inputs == null ? null : inputs.get("sourceRunIds");
        if (runIds != null && runIds.isArray() && !runIds.isEmpty()) {
            List<String> ids = new ArrayList<>();
            runIds.forEach(id -> ids.add(id.asText()));
            Integer passed = jdbcTemplate.queryForObject(
                "select count(*) from ai_draft_test_points where run_id in ("
                    + String.join(",", ids.stream().map(id -> "?").toList()) + ") and review_status = 'accepted'",
                Integer.class, ids.toArray());
            Integer total = jdbcTemplate.queryForObject(
                "select count(*) from ai_draft_test_points where run_id in ("
                    + String.join(",", ids.stream().map(id -> "?").toList()) + ")",
                Integer.class, ids.toArray());
            double passRate = total == null || total == 0 ? 0d : (passed == null ? 0d : passed) * 100d / total;
            facts.put("passRate", passRate);
            facts.put("totalItems", total == null ? 0 : total);
            facts.set("sourceRunIds", runIds.deepCopy()); // 可追溯：事实来自哪些执行记录
        }
        return facts;
    }

    private void emit(String runId, String stage, String severity, String summary) {
        try {
            runEventService.emit(new AiRunEvent(runId, Instant.now(), stage, severity, summary,
                null, null, null, "", "", "", 0, "", "", 0));
        } catch (RuntimeException ignored) {
            // 事件记录失败不阻断主流程；attempt/产物落库仍是审计真相。
        }
    }

    private String currentActor() {
        return com.haitlify.aitest.platform.ai.security.AiActorResolver.requireHumanActor();
    }

    /** 从产物行解析确定性事实（report_draft 生成时落库；其他任务为 null ⇒ 不校验数值）。 */
    private JsonNode deterministicFactsFrom(Map<String, Object> row) {
        Object stored = row.get("deterministic_facts_json");
        if (stored == null || String.valueOf(stored).isBlank()) {
            return null;
        }
        JsonNode parsed = parseJson(String.valueOf(stored));
        return parsed == null || parsed.isNull() || parsed.isMissingNode() ? null : parsed;
    }

    /** 违规记录只增不减：复检新发现并入历史，保留既有审计（去重、保持顺序）。 */
    private List<String> mergeViolations(List<String> existing, List<String> fresh) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(existing);
        merged.addAll(fresh);
        return List.copyOf(merged);
    }

    /** CAS 落败者的统一裁决：目标终态已由并发操作达成 ⇒ 幂等返回；被改成其他终态 ⇒ 状态冲突。 */
    private AssistantRun resolveReviewRace(String artifactId, String decision, String targetState,
                                           java.util.function.Supplier<AssistantRun> idempotent) {
        Map<String, Object> raced = artifactRow(artifactId);
        String racedState = String.valueOf(raced.get("acceptance_state"));
        if (targetState.equals(racedState)) {
            return idempotent.get();
        }
        throw new IllegalArgumentException("review_state_conflict: " + decision + " 不允许，当前状态 " + racedState);
    }

    private Map<String, Object> artifactRow(String artifactId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select * from ai_assistant_artifacts where artifact_id = ?", artifactId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("artifact_not_found: " + artifactId);
        }
        return rows.get(0);
    }

    private JsonNode parseJson(String raw) {
        try {
            return objectMapper.readTree(raw == null || raw.isBlank() ? "null" : raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** violations 必须以合法 JSON 数组落库（修复前用 Java List.toString，回读解析失败被静默吞掉）。 */
    private String toJsonArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private List<String> parseViolations(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            List<String> values = new ArrayList<>();
            if (node != null && node.isArray()) {
                node.forEach(item -> values.add(item.asText()));
            }
            return values;
        } catch (Exception ignored) {
            // 历史行容错：修复前曾以 Java List.toString（[a, b]，元素无引号）落库，属非法 JSON。
            // 历史不回写；读取端对「方括号但非 JSON 数组」形态做尽力拆分（含逗号的单条违规会被拆成两条）。
            String trimmed = raw.trim();
            if (trimmed.length() >= 2 && trimmed.startsWith("[") && trimmed.endsWith("]")
                && trimmed.charAt(1) != '"') {
                List<String> values = new ArrayList<>();
                for (String part : trimmed.substring(1, trimmed.length() - 1).split(",")) {
                    String value = part.trim();
                    if (!value.isEmpty()) {
                        values.add(value);
                    }
                }
                return values;
            }
            return List.of();
        }
    }

    private String manifestJson(AiEvidenceManifestService.EvidenceManifest manifest) {
        try {
            return objectMapper.writeValueAsString(manifest);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "未知原因";
        }
        // T14：出站文本先脱敏（错误回显可能携带密钥）。
        String single = com.haitlify.aitest.platform.ai.security.AiPayloadSanitizer
            .mask(value.replaceAll("\\s+", " "));
        return single.substring(0, Math.min(240, single.length()));
    }
}
