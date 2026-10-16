package com.haitlify.aitest.platform.ai.assistant;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.ai.evidence.AiEvidenceManifestService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * T12：组合任务（依赖 DAG）服务。首个案例：Bug→日志→回归。
 *
 * <ul>
 *   <li>组合运行只构建一次 evidence manifest，所有节点复用（共享证据清单）；</li>
 *   <li>依赖未满足（上游未 ACCEPTED / 被拒 / 阻塞）的节点不得执行、不得标完成——
 *       只能 skipped 并携带原因；</li>
 *   <li>人工门逐节点：每次只推进依赖已全部满足的节点，上游等待评审时下游保持 pending；</li>
 *   <li>拒绝阻止下游；每个节点保留自己独立的状态与产物（不合并成不可审计长文本）。</li>
 * </ul>
 */
@Service
public class AiAssistantCompositeService {

    public static final String NODE_PENDING = "pending";
    public static final String NODE_BLOCKED = "blocked";
    public static final String NODE_WAITING_REVIEW = "waiting_review";
    public static final String NODE_ACCEPTED = "accepted";
    public static final String NODE_REJECTED = "rejected";
    public static final String NODE_SKIPPED = "skipped";

    public record NodeSpec(String nodeId, int position, String taskType, List<String> dependsOn) {
    }

    public record CompositeNode(String nodeId, int position, String taskType, List<String> dependsOn,
                                String status, String runId, String artifactId, String skipReason) {
    }

    public record CompositeRun(String compositeId, Long projectId, String template, String status,
                               List<CompositeNode> nodes, JsonNode manifest) {
    }

    private static final Map<String, List<NodeSpec>> TEMPLATES = Map.of(
        "bug_log_regression", List.of(
            new NodeSpec("bug", 0, "bug_analysis", List.of()),
            new NodeSpec("log", 1, "log_triage", List.of("bug")),
            new NodeSpec("regression", 2, "regression_planning", List.of("log"))
        )
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AiAssistantService assistantService;
    private final AiEvidenceManifestService evidenceManifestService;
    private final com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization authorization;

    public AiAssistantCompositeService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                       AiAssistantService assistantService,
                                       AiEvidenceManifestService evidenceManifestService) {
        this(jdbcTemplate, objectMapper, assistantService, evidenceManifestService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiAssistantCompositeService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                       AiAssistantService assistantService,
                                       AiEvidenceManifestService evidenceManifestService,
                                       com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization authorization) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.assistantService = assistantService;
        this.evidenceManifestService = evidenceManifestService;
        this.authorization = authorization;
    }

    private void requireProjectAccess(Long projectId) {
        if (authorization != null) {
            authorization.requireProjectAccess(projectId);
        }
    }

    /** 启动组合运行：构建一次共享清单 → 初始化节点 → 推进所有依赖就绪的节点。 */
    public CompositeRun start(String template, Long projectId, JsonNode inputs, String actorId) {
        List<NodeSpec> specs = TEMPLATES.get(template == null ? "" : template.trim().toLowerCase());
        if (specs == null) {
            throw new IllegalArgumentException("unknown_template: " + template);
        }
        if (projectId == null) {
            throw new IllegalArgumentException("projectId required");
        }
        // T14：项目隔离——组合运行创建即校验。
        requireProjectAccess(projectId);
        // 组合级输入预检：以首个无依赖节点的必要输入为准（缺项 4xx，不默认）。
        NodeSpec root = specs.get(0);
        AiTaskCatalog.RequestValidation validation = AiTaskCatalog.validateRequest(root.taskType(), inputs);
        if (!validation.complete()) {
            throw new IllegalArgumentException("missing_inputs: " + String.join(", ", validation.missingInputs()));
        }

        String compositeId = "composite-" + UUID.randomUUID();
        AiEvidenceManifestService.EvidenceManifest manifest = evidenceManifestService.manifestForProject(projectId);
        String manifestJson;
        try {
            manifestJson = objectMapper.writeValueAsString(manifest);
        } catch (Exception failure) {
            manifestJson = "{}";
        }
        jdbcTemplate.update("""
            insert into ai_assistant_composite_runs(
              composite_id, project_id, actor_id, template, status, manifest_json, inputs_json,
              created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            compositeId, projectId, actorId == null ? "" : actorId, template, "running", manifestJson,
            inputs == null ? "{}" : inputs.toString(),
            java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));
        for (NodeSpec spec : specs) {
            jdbcTemplate.update("""
                insert into ai_assistant_composite_nodes(
                  node_id, composite_id, position, task_type, depends_on_json, status, run_id, artifact_id,
                  skip_reason, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, '', '', '', ?, ?)
                """,
                compositeId + ":" + spec.nodeId(), compositeId, spec.position(), spec.taskType(),
                toJson(spec.dependsOn()), NODE_PENDING,
                java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));
        }
        // 组合内共享同一份清单：节点执行时直接复用，不再二次取。
        advance(compositeId, projectId, actorId, inputs, manifest);
        return get(compositeId);
    }

    /** 人工评审一个节点：approve ⇒ 推进下游；reject ⇒ 下游 skipped（拒绝阻止下游）。 */
    public CompositeRun reviewNode(String compositeId, String nodeId, String decision,
                                   Integer revision, JsonNode candidate) {
        Map<String, Object> runRowForAccess;
        try {
            runRowForAccess = compositeRow(compositeId);
        } catch (IllegalArgumentException notFoundComposite) {
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
        requireProjectAccess(((Number) runRowForAccess.get("project_id")).longValue());
        Map<String, Object> node = nodeRow(compositeId, nodeId);
        String status = String.valueOf(node.get("status"));
        if (!NODE_WAITING_REVIEW.equals(status)) {
            throw new IllegalArgumentException("node_not_waiting_review: 当前状态 " + status);
        }
        String artifactId = String.valueOf(node.get("artifact_id"));
        AiAssistantService.AssistantRun reviewed = assistantService.review(artifactId, decision, revision, candidate);

        String nextStatus = switch (reviewed.acceptanceState()) {
            case AiAssistantService.STATE_ACCEPTED -> NODE_ACCEPTED;
            case AiAssistantService.STATE_REJECTED -> NODE_REJECTED;
            default -> NODE_WAITING_REVIEW;
        };
        updateNode(compositeId, nodeId, nextStatus, reviewed.runId(), reviewed.artifactId(), "");

        Map<String, Object> runRow = compositeRow(compositeId);
        Long projectId = ((Number) runRow.get("project_id")).longValue();
        String actorId = String.valueOf(runRow.getOrDefault("actor_id", ""));
        if (NODE_REJECTED.equals(nextStatus)) {
            skipDownstream(compositeId, nodeId, "依赖被驳回: " + nodeId);
        } else if (NODE_ACCEPTED.equals(nextStatus)) {
            JsonNode inputs = compositeInputs(compositeId);
            AiEvidenceManifestService.EvidenceManifest manifest = parseManifest(
                String.valueOf(runRow.getOrDefault("manifest_json", "{}")));
            advance(compositeId, projectId, actorId, inputs, manifest);
        }
        recomputeStatus(compositeId);
        return get(compositeId);
    }

    public CompositeRun get(String compositeId) {
        Map<String, Object> runRow;
        try {
            runRow = compositeRow(compositeId);
        } catch (IllegalArgumentException notFoundComposite) {
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
        requireProjectAccess(((Number) runRow.get("project_id")).longValue());
        List<Map<String, Object>> nodeRows = jdbcTemplate.queryForList(
            "select * from ai_assistant_composite_nodes where composite_id = ? order by position asc", compositeId);
        List<CompositeNode> nodes = new ArrayList<>();
        for (Map<String, Object> row : nodeRows) {
            nodes.add(new CompositeNode(
                String.valueOf(row.get("node_id")).substring(String.valueOf(row.get("composite_id")).length() + 1),
                ((Number) row.get("position")).intValue(),
                String.valueOf(row.get("task_type")),
                parseStringList(String.valueOf(row.getOrDefault("depends_on_json", "[]"))),
                String.valueOf(row.get("status")),
                String.valueOf(row.getOrDefault("run_id", "")),
                String.valueOf(row.getOrDefault("artifact_id", "")),
                String.valueOf(row.getOrDefault("skip_reason", ""))));
        }
        return new CompositeRun(compositeId, ((Number) runRow.get("project_id")).longValue(),
            String.valueOf(runRow.get("template")), String.valueOf(runRow.get("status")), nodes,
            rawManifestJson(String.valueOf(runRow.getOrDefault("manifest_json", "{}"))));
    }

    // ---------- 内部 ----------

    /**
     * 推进所有「依赖全部 ACCEPTED」的 pending 节点；遇到 waiting_review/blocked 即停止本批，
     * 下游保持 pending——依赖未满足不得自动完成。
     */
    private void advance(String compositeId, Long projectId, String actorId, JsonNode inputs,
                         AiEvidenceManifestService.EvidenceManifest manifest) {
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (Map<String, Object> node : nodeRows(compositeId)) {
                if (!NODE_PENDING.equals(String.valueOf(node.get("status")))) {
                    continue;
                }
                String nodeId = shortNodeId(node);
                List<String> deps = parseStringList(String.valueOf(node.getOrDefault("depends_on_json", "[]")));
                String unmetDep = firstUnmetDep(compositeId, deps);
                if (unmetDep != null) {
                    String depStatus = nodeStatus(compositeId, unmetDep);
                    if (NODE_REJECTED.equals(depStatus) || NODE_BLOCKED.equals(depStatus)
                            || NODE_SKIPPED.equals(depStatus)) {
                        updateNode(compositeId, nodeId, NODE_SKIPPED, "", "",
                            "依赖未满足: " + unmetDep + " = " + depStatus);
                        progressed = true;
                    }
                    continue;
                }
                // 依赖就绪：带上游产物调用助手服务（共享清单）。
                ObjectNode nodeInputs = inputs == null || !inputs.isObject()
                    ? objectMapper.createObjectNode() : ((ObjectNode) inputs).deepCopy();
                ObjectNode upstream = nodeInputs.putObject("upstreamOutputs");
                for (String dep : deps) {
                    Map<String, Object> depNode = nodeRow(compositeId, dep);
                    String depRunId = String.valueOf(depNode.getOrDefault("run_id", ""));
                    if (!depRunId.isBlank()) {
                        try {
                            AiAssistantService.AssistantRun depRun = assistantService.getRun(depRunId);
                            upstream.set(dep, depRun.output() == null
                                ? objectMapper.nullNode() : depRun.output());
                        } catch (RuntimeException ignored) {
                            // 上游产物不可得时不造数据：节点仍可运行，其自身证据规则照常执行。
                        }
                    }
                }
                String taskType = String.valueOf(node.get("task_type"));
                AiAssistantService.AssistantRun run;
                try {
                    run = assistantService.startRun(taskType, projectId, nodeInputs, actorId, manifest);
                } catch (RuntimeException failure) {
                    updateNode(compositeId, nodeId, NODE_BLOCKED, "", "", safe(failure.getMessage()));
                    progressed = true;
                    continue;
                }
                if ("waiting_review".equals(run.status())) {
                    updateNode(compositeId, nodeId, NODE_WAITING_REVIEW, run.runId(), run.artifactId(), "");
                } else {
                    updateNode(compositeId, nodeId, NODE_BLOCKED, run.runId(), "",
                        run.blockReason() == null ? "运行被阻塞" : run.blockReason());
                }
                progressed = true;
            }
        }
        recomputeStatus(compositeId);
    }

    private String firstUnmetDep(String compositeId, List<String> deps) {
        for (String dep : deps) {
            if (!NODE_ACCEPTED.equals(nodeStatus(compositeId, dep))) {
                return dep;
            }
        }
        return null;
    }

    private void skipDownstream(String compositeId, String rejectedNodeId, String reason) {
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (Map<String, Object> node : nodeRows(compositeId)) {
                String status = String.valueOf(node.get("status"));
                if (!NODE_PENDING.equals(status) && !NODE_SKIPPED.equals(status)) {
                    continue;
                }
                String nodeId = shortNodeId(node);
                if (NODE_SKIPPED.equals(status)) {
                    continue;
                }
                List<String> deps = parseStringList(String.valueOf(node.getOrDefault("depends_on_json", "[]")));
                for (String dep : deps) {
                    if (dep.equals(rejectedNodeId) || NODE_SKIPPED.equals(nodeStatus(compositeId, dep))) {
                        updateNode(compositeId, nodeId, NODE_SKIPPED, "", "", reason + "（经 " + dep + "）");
                        progressed = true;
                        break;
                    }
                }
            }
        }
    }

    private void recomputeStatus(String compositeId) {
        List<Map<String, Object>> nodes = nodeRows(compositeId);
        boolean anyBlocked = false;
        boolean anyRejectedOrSkipped = false;
        boolean anyWaiting = false;
        boolean allAccepted = !nodes.isEmpty();
        for (Map<String, Object> node : nodes) {
            String status = String.valueOf(node.get("status"));
            if (NODE_BLOCKED.equals(status)) {
                anyBlocked = true;
            }
            if (NODE_REJECTED.equals(status) || NODE_SKIPPED.equals(status)) {
                anyRejectedOrSkipped = true;
            }
            if (NODE_WAITING_REVIEW.equals(status)) {
                anyWaiting = true;
            }
            if (!NODE_ACCEPTED.equals(status)) {
                allAccepted = false;
            }
        }
        String status = allAccepted ? "succeeded"
            : anyBlocked ? "blocked"
            : anyRejectedOrSkipped ? "partial"
            : anyWaiting ? "waiting_review"
            : "running";
        jdbcTemplate.update("update ai_assistant_composite_runs set status = ?, updated_at = ? where composite_id = ?",
            status, java.sql.Timestamp.from(Instant.now()), compositeId);
    }

    private List<Map<String, Object>> nodeRows(String compositeId) {
        return jdbcTemplate.queryForList(
            "select * from ai_assistant_composite_nodes where composite_id = ? order by position asc", compositeId);
    }

    private Map<String, Object> nodeRow(String compositeId, String nodeId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select * from ai_assistant_composite_nodes where composite_id = ? and node_id = ?",
            compositeId, compositeId + ":" + nodeId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("node_not_found: " + nodeId);
        }
        return rows.get(0);
    }

    private Map<String, Object> compositeRow(String compositeId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select * from ai_assistant_composite_runs where composite_id = ?", compositeId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("composite_not_found: " + compositeId);
        }
        return rows.get(0);
    }

    private String nodeStatus(String compositeId, String nodeId) {
        return String.valueOf(nodeRow(compositeId, nodeId).get("status"));
    }

    private String shortNodeId(Map<String, Object> nodeRow) {
        String full = String.valueOf(nodeRow.get("node_id"));
        String compositeId = String.valueOf(nodeRow.get("composite_id"));
        return full.startsWith(compositeId + ":") ? full.substring(compositeId.length() + 1) : full;
    }

    /** 组合级输入快照（启动时落库；组合内所有节点共享同一输入快照）。 */
    private JsonNode compositeInputs(String compositeId) {
        Object raw = compositeRow(compositeId).get("inputs_json");
        if (raw != null) {
            try {
                JsonNode parsed = objectMapper.readTree(String.valueOf(raw));
                if (parsed != null && parsed.isObject()) {
                    return parsed;
                }
            } catch (Exception ignored) {
                // 坏快照按空对象处理；节点仍会基于共享清单与上游产物执行。
            }
        }
        return objectMapper.createObjectNode();
    }

    private void updateNode(String compositeId, String nodeId, String status, String runId,
                            String artifactId, String skipReason) {
        Map<String, Object> existing = nodeRow(compositeId, nodeId);
        jdbcTemplate.update("""
            update ai_assistant_composite_nodes set status = ?, run_id = ?, artifact_id = ?,
              skip_reason = ?, updated_at = ? where composite_id = ? and node_id = ?
            """,
            status,
            runId == null || runId.isBlank() ? String.valueOf(existing.getOrDefault("run_id", "")) : runId,
            artifactId == null || artifactId.isBlank() ? String.valueOf(existing.getOrDefault("artifact_id", "")) : artifactId,
            skipReason == null ? "" : skipReason, java.sql.Timestamp.from(Instant.now()),
            compositeId, compositeId + ":" + nodeId);
    }

    private JsonNode rawManifestJson(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            return node == null ? objectMapper.createObjectNode() : node;
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private AiEvidenceManifestService.EvidenceManifest parseManifest(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node == null || !node.isObject()) {
                return emptyManifest();
            }
            List<AiEvidenceManifestService.ManifestEntry> entries = new ArrayList<>();
            JsonNode entriesNode = node.path("entries");
            if (entriesNode.isArray()) {
                for (JsonNode entry : entriesNode) {
                    entries.add(new AiEvidenceManifestService.ManifestEntry(
                        entry.path("documentId").asText(""), entry.path("fileName").asText(""),
                        entry.path("documentType").asText(""), entry.path("parseStatus").asText(""),
                        List.of(), entry.path("chunkCount").asInt(0), entry.path("tableCount").asInt(0),
                        entry.path("imageCount").asInt(0), entry.path("parserVersion").asText(""),
                        entry.path("contentHash").asText(""), List.of()));
                }
            }
            return new AiEvidenceManifestService.EvidenceManifest(
                node.path("projectId").asLong(0), entries, node.path("availableCount").asInt(0),
                node.path("partialCount").asInt(0), node.path("unsupportedCount").asInt(0),
                parseStringList(node.path("gaps").toString()));
        } catch (Exception ignored) {
            return emptyManifest();
        }
    }

    private AiEvidenceManifestService.EvidenceManifest emptyManifest() {
        return new AiEvidenceManifestService.EvidenceManifest(0L, List.of(), 0, 0, 0, List.of());
    }

    private List<String> parseStringList(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw == null || raw.isBlank() ? "[]" : raw);
            List<String> values = new ArrayList<>();
            if (node != null && node.isArray()) {
                node.forEach(item -> values.add(item.asText()));
            }
            return values;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String toJson(List<String> values) {
        try {
            ArrayNode array = objectMapper.createArrayNode();
            values.forEach(array::add);
            return array.toString();
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "未知原因";
        }
        String single = value.replaceAll("\\s+", " ");
        return single.length() <= 240 ? single : single.substring(0, 237) + "...";
    }
}
