package com.haitlify.aitest.platform.ai.figma;

import tools.jackson.databind.ObjectMapper;

class AiFigmaEvidenceService {

    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;

    AiFigmaEvidenceService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    AiFigmaRequirementEvidence fromNode(AiFigmaFile file, AiFigmaNode node) {
        return new AiFigmaRequirementEvidence(
            "figma",
            file.getFileKey(),
            node.getNodeId(),
            node.getNodeName(),
            node.getNodeType(),
            node.getComponentName(),
            "",
            ""
        );
    }

    AiFigmaRequirementEvidence fromPrototype(AiFigmaFile file, AiFigmaPrototypeLink link) {
        return new AiFigmaRequirementEvidence(
            "figma",
            file.getFileKey(),
            link.sourceNodeId(),
            "",
            "",
            "",
            prototypeLinkId(link),
            ""
        );
    }

    AiFigmaRequirementEvidence fromStyleToken(AiFigmaFile file, AiFigmaStyleToken token) {
        return new AiFigmaRequirementEvidence(
            "figma",
            file.getFileKey(),
            "",
            "",
            "",
            "",
            "",
            token.tokenCategory() + ":" + token.tokenName()
        );
    }

    private String prototypeLinkId(AiFigmaPrototypeLink link) {
        return String.join("->",
            link.sourceNodeId(),
            link.targetNodeId(),
            link.eventType(),
            link.actionType()
        );
    }
}

record AiFigmaRequirementEvidence(
    String sourceType,
    String fileKey,
    String nodeId,
    String nodeName,
    String nodeType,
    String componentName,
    String prototypeLinkId,
    String styleTokenId
) {
}
