package com.haitlify.aitest.platform.ai.figma;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.haitlify.aitest.platform.auth.ProjectAccessService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional
class AiFigmaElementCandidateService {

    private final AiFigmaFileRepository fileRepository;
    private final AiFigmaNodeRepository nodeRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ProjectAccessService projectAccessService;

    AiFigmaElementCandidateService(
        AiFigmaFileRepository fileRepository,
        AiFigmaNodeRepository nodeRepository,
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        ProjectAccessService projectAccessService
    ) {
        this.fileRepository = fileRepository;
        this.nodeRepository = nodeRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.projectAccessService = projectAccessService;
    }

    AiFigmaElementCandidateListResponse listCandidates(String figmaFileId) {
        AiFigmaFile file = fileRepository.findByFigmaFileId(figmaFileId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown Figma file: " + figmaFileId));
        try {
            projectAccessService.requireAccess(file.getProjectId());
        } catch (ResponseStatusException denied) {
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
        ensureCandidates(file);
        List<AiFigmaElementCandidateResponse> items = jdbcTemplate.queryForList(
                "select * from ai_figma_element_candidates where figma_file_pk = ? order by role asc, component_key asc, id asc",
                file.getId()
            )
            .stream()
            .map(this::toResponse)
            .toList();
        return new AiFigmaElementCandidateListResponse(items);
    }

    /**
     * 全链路人工确认 Exit Gate（Figma element candidate）：真实认证 actor + 项目权限（经文件→项目，
     * 无权与不存在同形 404）+ expectedRevision（过期 409）+ 允许的前置状态 + 证据非空方可确认 +
     * CAS 写入（重复确认/驳回幂等）+ 人工动作持久审计（reviewed_by/reviewed_at）。
     */
    AiFigmaElementCandidateResponse confirm(String candidateId) {
        return confirm(candidateId, null);
    }

    AiFigmaElementCandidateResponse confirm(String candidateId, Integer expectedRevision) {
        return review(candidateId, "confirmed", expectedRevision);
    }

    AiFigmaElementCandidateResponse reject(String candidateId) {
        return reject(candidateId, null);
    }

    AiFigmaElementCandidateResponse reject(String candidateId, Integer expectedRevision) {
        return review(candidateId, "rejected", expectedRevision);
    }

    private AiFigmaElementCandidateResponse review(String candidateId, String target, Integer expectedRevision) {
        String actor = currentHumanActor();
        Map<String, Object> row = findCandidateOrNotFound(candidateId);
        requireProjectAccess(row);
        guardRevision(row, expectedRevision);
        String status = String.valueOf(row.get("STATUS"));
        if (target.equals(status)) {
            return toResponse(row);
        }
        if (!"pending".equals(status)) {
            throw new IllegalArgumentException("review_state_conflict: " + target + " 不允许，当前状态 " + status);
        }
        if ("confirmed".equals(target)) {
            Object evidence = row.get("EVIDENCE_JSON");
            if (evidence == null || String.valueOf(evidence).isBlank() || "{}".equals(String.valueOf(evidence).trim())) {
                throw new IllegalArgumentException("evidence_missing: 候选缺少来源证据，不得确认");
            }
        }
        Instant now = Instant.now();
        int updated = jdbcTemplate.update(
            "update ai_figma_element_candidates set status = ?, reviewed_by = ?, reviewed_at = ?, updated_at = ?"
                + " where candidate_id = ? and status = 'pending'",
            target, actor, Timestamp.from(now), Timestamp.from(now), candidateId);
        Map<String, Object> after = findCandidateOrNotFound(candidateId);
        if (updated == 0) {
            if (target.equals(String.valueOf(after.get("STATUS")))) {
                return toResponse(after);
            }
            throw new IllegalArgumentException("review_state_conflict: " + target + " 不允许，当前状态 "
                + after.get("STATUS"));
        }
        return toResponse(after);
    }

    private String currentHumanActor() {
        return com.haitlify.aitest.platform.ai.security.AiActorResolver.requireHumanActor();
    }

    private Map<String, Object> findCandidateOrNotFound(String candidateId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select * from ai_figma_element_candidates where candidate_id = ?", candidateId);
        if (rows.isEmpty()) {
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
        return rows.get(0);
    }

    /** 项目访问闸：候选 → 文件 → 项目；拒绝与不存在同形 404。 */
    private void requireProjectAccess(Map<String, Object> candidateRow) {
        Long projectId = jdbcTemplate.queryForList(
                "select project_id from ai_figma_files where id = ?",
                Long.class, ((Number) candidateRow.get("FIGMA_FILE_PK")).longValue())
            .stream().findFirst().orElse(null);
        try {
            projectAccessService.requireAccess(projectId);
        } catch (ResponseStatusException denied) {
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
    }

    private void guardRevision(Map<String, Object> row, Integer expectedRevision) {
        if (expectedRevision == null) {
            return;
        }
        Object raw = row.get("REVISION");
        int current = raw == null ? 1 : ((Number) raw).intValue();
        if (expectedRevision != current) {
            throw new com.haitlify.aitest.platform.ai.agentic.StaleArtifactRevisionException(
                "candidate revision is stale: expected " + expectedRevision + " but current is " + current);
        }
    }

    private void ensureCandidates(AiFigmaFile file) {
        for (AiFigmaNode node : nodeRepository.findByFigmaFilePkOrderByIdAsc(file.getId())) {
            String role = role(node);
            if (role.isBlank()) {
                continue;
            }
            String componentKey = node.getComponentKey().isBlank() ? slug(node.getComponentName()) : node.getComponentKey();
            Integer existing = jdbcTemplate.queryForObject("""
                select count(*) from ai_figma_element_candidates
                where figma_file_pk = ? and component_key = ? and role = ?
                """, Integer.class, file.getId(), componentKey, role);
            if (existing != null && existing > 0) {
                continue;
            }
            insertCandidate(file, node, componentKey, role);
        }
    }

    private void insertCandidate(AiFigmaFile file, AiFigmaNode node, String componentKey, String role) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_figma_element_candidates(
              candidate_id, figma_file_pk, component_key, component_name, node_id, node_name,
              role, suggested_locator_name, status, evidence_json, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "figma-candidate-" + UUID.randomUUID(),
            file.getId(),
            componentKey,
            node.getComponentName(),
            node.getNodeId(),
            node.getNodeName(),
            role,
            suggestedLocatorName(componentKey, node),
            "pending",
            stringify(evidence(file, node, role)),
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    private String role(AiFigmaNode node) {
        String value = (node.getNodeName() + " " + node.getComponentName()).toLowerCase();
        if (value.contains("button") || value.contains("按钮")) {
            return "button";
        }
        if (value.contains("input") || value.contains("field") || value.contains("输入")) {
            return "textbox";
        }
        return "";
    }

    private String suggestedLocatorName(String componentKey, AiFigmaNode node) {
        if (!componentKey.isBlank()) {
            return componentKey;
        }
        return slug(node.getNodeName());
    }

    private String slug(String value) {
        String slug = value == null ? "" : value.toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "figma-element" : slug;
    }

    private ObjectNode evidence(AiFigmaFile file, AiFigmaNode node, String role) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("fileKey", file.getFileKey());
        evidence.put("nodeId", node.getNodeId());
        evidence.put("nodeName", node.getNodeName());
        evidence.put("componentKey", node.getComponentKey());
        evidence.put("componentName", node.getComponentName());
        evidence.put("role", role);
        return evidence;
    }

    private AiFigmaElementCandidateResponse toResponse(Map<String, Object> row) {
        return new AiFigmaElementCandidateResponse(
            (String) row.get("CANDIDATE_ID"),
            (String) row.get("COMPONENT_KEY"),
            (String) row.get("COMPONENT_NAME"),
            (String) row.get("NODE_ID"),
            (String) row.get("NODE_NAME"),
            (String) row.get("ROLE"),
            (String) row.get("SUGGESTED_LOCATOR_NAME"),
            (String) row.get("STATUS"),
            row.get("REVISION") == null ? 1 : ((Number) row.get("REVISION")).intValue(),
            String.valueOf(row.getOrDefault("REVIEWED_BY", "")),
            parse((String) row.get("EVIDENCE_JSON"))
        );
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    private String stringify(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid Figma element candidate evidence", exception);
        }
    }
}

record AiFigmaElementCandidateListResponse(
    List<AiFigmaElementCandidateResponse> items
) {
}

record AiFigmaElementCandidateResponse(
    String candidateId,
    String componentKey,
    String componentName,
    String nodeId,
    String nodeName,
    String role,
    String suggestedLocatorName,
    String status,
    Integer revision,
    String reviewedBy,
    JsonNode evidence
) {
}
