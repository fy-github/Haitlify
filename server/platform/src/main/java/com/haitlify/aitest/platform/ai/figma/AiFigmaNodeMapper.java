package com.haitlify.aitest.platform.ai.figma;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AiFigmaNodeMapper {

    private final ObjectMapper objectMapper;

    AiFigmaNodeMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<AiFigmaNode> mapTree(Long figmaFilePk, JsonNode root, String sourceSnapshotHash) {
        List<AiFigmaNode> nodes = new ArrayList<>();
        collect(figmaFilePk, root, "", sourceSnapshotHash, nodes);
        return nodes;
    }

    private void collect(Long figmaFilePk, JsonNode node, String parentNodeId, String sourceSnapshotHash, List<AiFigmaNode> nodes) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        String nodeId = node.path("id").asText("");
        if (nodeId.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        nodes.add(new AiFigmaNode(
            null,
            stableNodeId(figmaFilePk, nodeId),
            figmaFilePk,
            nodeId,
            node.path("type").asText(""),
            node.path("name").asText(""),
            parentNodeId,
            componentKey(node),
            componentName(node),
            json(node.path("componentProperties")),
            json(node.path("style")),
            json(node.path("styles")),
            node.path("layoutMode").asText(""),
            json(node.path("constraints")),
            json(node.path("absoluteBoundingBox")),
            json(node.path("transitionNodeID").isMissingNode() ? node.path("interactions") : node.path("transitionNodeID")),
            sourceSnapshotHash,
            now,
            now
        ));
        for (JsonNode child : node.path("children")) {
            collect(figmaFilePk, child, nodeId, sourceSnapshotHash, nodes);
        }
    }

    private String componentKey(JsonNode node) {
        String componentKey = node.path("componentKey").asText("");
        if (!componentKey.isBlank()) {
            return componentKey;
        }
        return node.path("componentId").asText("");
    }

    private String componentName(JsonNode node) {
        String componentName = node.path("componentName").asText("");
        return componentName.isBlank() ? node.path("name").asText("") : componentName;
    }

    private String json(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private String stableNodeId(Long figmaFilePk, String nodeId) {
        return "figma-node-" + sha256((figmaFilePk + ":" + nodeId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash Figma node id", exception);
        }
    }
}
