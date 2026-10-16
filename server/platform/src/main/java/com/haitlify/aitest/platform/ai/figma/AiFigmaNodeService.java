package com.haitlify.aitest.platform.ai.figma;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
class AiFigmaNodeService {

    private final AiFigmaFileRepository fileRepository;
    private final AiFigmaNodeRepository nodeRepository;
    private final ObjectMapper objectMapper;

    AiFigmaNodeService(
        AiFigmaFileRepository fileRepository,
        AiFigmaNodeRepository nodeRepository,
        ObjectMapper objectMapper
    ) {
        this.fileRepository = fileRepository;
        this.nodeRepository = nodeRepository;
        this.objectMapper = objectMapper;
    }

    AiFigmaNodeListResponse listNodes(String figmaFileId) {
        AiFigmaFile file = fileRepository.findByFigmaFileId(figmaFileId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown Figma file: " + figmaFileId));
        List<AiFigmaNodeResponse> items = nodeRepository.findByFigmaFilePkOrderByIdAsc(file.getId()).stream()
            .map(this::toResponse)
            .toList();
        return new AiFigmaNodeListResponse(items);
    }

    AiFigmaNodeResponse getNode(String figmaNodeId) {
        return toResponse(nodeRepository.findByFigmaNodeId(figmaNodeId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown Figma node: " + figmaNodeId)));
    }

    private AiFigmaNodeResponse toResponse(AiFigmaNode node) {
        return new AiFigmaNodeResponse(
            node.getFigmaNodeId(),
            node.getFigmaFilePk(),
            node.getNodeId(),
            node.getNodeType(),
            node.getNodeName(),
            node.getParentNodeId(),
            node.getComponentKey(),
            node.getComponentName(),
            parse(node.getVariantPropertiesJson()),
            parse(node.getStyleJson()),
            parse(node.getTokenJson()),
            node.getLayoutMode(),
            parse(node.getConstraintsJson()),
            parse(node.getAbsoluteBoundsJson()),
            parse(node.getPrototypeLinkJson()),
            node.getSourceSnapshotHash(),
            node.getCreatedAt() == null ? null : node.getCreatedAt().toString(),
            node.getUpdatedAt() == null ? null : node.getUpdatedAt().toString()
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
}

record AiFigmaNodeListResponse(
    List<AiFigmaNodeResponse> items
) {
}

record AiFigmaNodeResponse(
    String figmaNodeId,
    Long figmaFilePk,
    String nodeId,
    String nodeType,
    String nodeName,
    String parentNodeId,
    String componentKey,
    String componentName,
    JsonNode variantProperties,
    JsonNode style,
    JsonNode tokens,
    String layoutMode,
    JsonNode constraints,
    JsonNode absoluteBounds,
    JsonNode prototypeLink,
    String sourceSnapshotHash,
    String createdAt,
    String updatedAt
) {
}
