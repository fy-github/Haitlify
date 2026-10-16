package com.haitlify.aitest.platform.ai.figma;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional
class AiFigmaChangeSetService {

    private final AiFigmaFileRepository fileRepository;
    private final AiFigmaNodeRepository nodeRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    AiFigmaChangeSetService(
        AiFigmaFileRepository fileRepository,
        AiFigmaNodeRepository nodeRepository,
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this.fileRepository = fileRepository;
        this.nodeRepository = nodeRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    AiFigmaChangeSetResponse refreshFile(String figmaFileId, RefreshAiFigmaFileRequest request) {
        AiFigmaFile file = fileRepository.findByFigmaFileId(figmaFileId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown Figma file: " + figmaFileId));
        List<AiFigmaNode> existing = nodeRepository.findByFigmaFilePkOrderByIdAsc(file.getId());
        Map<String, AiFigmaNode> existingByNodeId = existing.stream()
            .collect(Collectors.toMap(AiFigmaNode::getNodeId, Function.identity(), (left, right) -> left));
        Map<String, RefreshedFigmaNodeRequest> refreshedByNodeId = request.nodes().stream()
            .collect(Collectors.toMap(RefreshedFigmaNodeRequest::nodeId, Function.identity(), (left, right) -> left));

        ArrayNode changes = objectMapper.createArrayNode();
        for (RefreshedFigmaNodeRequest refreshed : request.nodes()) {
            AiFigmaNode current = existingByNodeId.get(refreshed.nodeId());
            if (current == null) {
                changes.add(change("added", refreshed.nodeId(), refreshed.nodeName(), objectMapper.createArrayNode()));
                continue;
            }
            ArrayNode changedFields = changedFields(file.getId(), current, refreshed);
            if (!snapshotHash(currentSnapshot(file.getId(), current)).equals(snapshotHash(refreshedSnapshot(refreshed)))) {
                changes.add(change("changed", refreshed.nodeId(), refreshed.nodeName(), changedFields));
            }
        }

        Set<String> refreshedIds = refreshedByNodeId.keySet();
        for (AiFigmaNode current : existing) {
            if (!refreshedIds.contains(current.getNodeId())) {
                changes.add(change("removed", current.getNodeId(), current.getNodeName(), objectMapper.createArrayNode()));
            }
        }

        String changeSetId = "figma-change-" + UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_figma_change_sets(change_set_id, figma_file_pk, status, changes_json, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?)
            """,
            changeSetId,
            file.getId(),
            "created",
            stringify(changes),
            Timestamp.from(now),
            Timestamp.from(now)
        );
        return getChangeSet(changeSetId);
    }

    AiFigmaChangeSetListResponse listChangeSets(String figmaFileId) {
        AiFigmaFile file = fileRepository.findByFigmaFileId(figmaFileId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown Figma file: " + figmaFileId));
        List<AiFigmaChangeSetResponse> items = jdbcTemplate.queryForList(
                "select * from ai_figma_change_sets where figma_file_pk = ? order by id desc",
                file.getId()
            )
            .stream()
            .map(this::toResponse)
            .toList();
        return new AiFigmaChangeSetListResponse(items);
    }

    AiFigmaChangeSetResponse getChangeSet(String changeSetId) {
        Map<String, Object> row = jdbcTemplate.queryForMap("select * from ai_figma_change_sets where change_set_id = ?", changeSetId);
        return toResponse(row);
    }

    private ObjectNode currentSnapshot(Long figmaFilePk, AiFigmaNode node) {
        return snapshot(
            node.getNodeType(),
            node.getNodeName(),
            node.getAbsoluteBoundsJson(),
            node.getComponentKey(),
            node.getVariantPropertiesJson(),
            node.getStyleJson(),
            node.getPrototypeLinkJson(),
            childIds(figmaFilePk, node.getNodeId())
        );
    }

    private ObjectNode refreshedSnapshot(RefreshedFigmaNodeRequest node) {
        return snapshot(
            blankToDefault(node.nodeType(), ""),
            blankToDefault(node.nodeName(), ""),
            blankToDefault(node.absoluteBoundsJson(), "{}"),
            blankToDefault(node.componentKey(), ""),
            blankToDefault(node.variantPropertiesJson(), "{}"),
            blankToDefault(node.styleJson(), "{}"),
            blankToDefault(node.prototypeLinkJson(), "{}"),
            node.childrenIds() == null ? List.of() : node.childrenIds()
        );
    }

    private ObjectNode snapshot(
        String nodeType,
        String nodeName,
        String bounds,
        String componentKey,
        String variantProperties,
        String style,
        String prototype,
        List<String> childrenIds
    ) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("nodeType", nodeType);
        snapshot.put("nodeName", nodeName);
        snapshot.put("bounds", bounds);
        snapshot.put("componentKey", componentKey);
        snapshot.put("variantProperties", variantProperties);
        snapshot.put("style", style);
        snapshot.put("prototype", prototype);
        snapshot.set("childrenIds", objectMapper.valueToTree(childrenIds.stream().sorted().toList()));
        return snapshot;
    }

    private ArrayNode changedFields(Long figmaFilePk, AiFigmaNode current, RefreshedFigmaNodeRequest refreshed) {
        ArrayNode fields = objectMapper.createArrayNode();
        addIfDifferent(fields, "nodeType", current.getNodeType(), refreshed.nodeType());
        addIfDifferent(fields, "nodeName", current.getNodeName(), refreshed.nodeName());
        addIfDifferent(fields, "bounds", current.getAbsoluteBoundsJson(), refreshed.absoluteBoundsJson());
        addIfDifferent(fields, "componentKey", current.getComponentKey(), refreshed.componentKey());
        addIfDifferent(fields, "variantProperties", current.getVariantPropertiesJson(), refreshed.variantPropertiesJson());
        addIfDifferent(fields, "style", current.getStyleJson(), refreshed.styleJson());
        addIfDifferent(fields, "prototype", current.getPrototypeLinkJson(), refreshed.prototypeLinkJson());
        List<String> currentChildren = childIds(figmaFilePk, current.getNodeId()).stream().sorted().toList();
        List<String> refreshedChildren = (refreshed.childrenIds() == null ? List.<String>of() : refreshed.childrenIds()).stream().sorted().toList();
        if (!currentChildren.equals(refreshedChildren)) {
            fields.add("children");
        }
        return fields;
    }

    private void addIfDifferent(ArrayNode fields, String field, String current, String refreshed) {
        if (!blankToDefault(current, "{}").equals(blankToDefault(refreshed, "{}"))) {
            fields.add(field);
        }
    }

    private ObjectNode change(String changeType, String nodeId, String nodeName, ArrayNode changedFields) {
        ObjectNode change = objectMapper.createObjectNode();
        change.put("changeType", changeType);
        change.put("nodeId", nodeId);
        change.put("nodeName", blankToDefault(nodeName, ""));
        change.set("changedFields", changedFields);
        return change;
    }

    private List<String> childIds(Long figmaFilePk, String nodeId) {
        return nodeRepository.findByFigmaFilePkAndParentNodeId(figmaFilePk, nodeId).stream()
            .map(AiFigmaNode::getNodeId)
            .toList();
    }

    private AiFigmaChangeSetResponse toResponse(Map<String, Object> row) {
        return new AiFigmaChangeSetResponse(
            (String) row.get("CHANGE_SET_ID"),
            (String) row.get("STATUS"),
            parse((String) row.get("CHANGES_JSON")),
            String.valueOf(row.get("CREATED_AT")),
            String.valueOf(row.get("UPDATED_AT"))
        );
    }

    private String snapshotHash(JsonNode node) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(stringify(node).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash Figma node snapshot", exception);
        }
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            return objectMapper.createArrayNode();
        }
    }

    private String stringify(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid Figma change payload", exception);
        }
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

record RefreshAiFigmaFileRequest(
    List<RefreshedFigmaNodeRequest> nodes
) {
    RefreshAiFigmaFileRequest {
        nodes = nodes == null ? List.of() : nodes;
    }
}

record RefreshedFigmaNodeRequest(
    String nodeId,
    String nodeType,
    String nodeName,
    String parentNodeId,
    String componentKey,
    String variantPropertiesJson,
    String styleJson,
    String absoluteBoundsJson,
    String prototypeLinkJson,
    List<String> childrenIds
) {
}

record AiFigmaChangeSetListResponse(
    List<AiFigmaChangeSetResponse> items
) {
}

record AiFigmaChangeSetResponse(
    String changeSetId,
    String status,
    JsonNode changes,
    String createdAt,
    String updatedAt
) {
}
