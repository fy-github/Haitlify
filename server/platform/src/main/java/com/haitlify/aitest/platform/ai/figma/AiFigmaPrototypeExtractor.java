package com.haitlify.aitest.platform.ai.figma;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AiFigmaPrototypeExtractor {

    private final ObjectMapper objectMapper;

    AiFigmaPrototypeExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<AiFigmaPrototypeLink> extractLinks(JsonNode root) {
        List<AiFigmaPrototypeLink> links = new ArrayList<>();
        collect(root, links);
        return links;
    }

    private void collect(JsonNode node, List<AiFigmaPrototypeLink> links) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        String sourceNodeId = node.path("id").asText("");
        for (JsonNode interaction : node.path("interactions")) {
            String eventType = interaction.path("trigger").path("type").asText("");
            for (JsonNode action : interaction.path("actions")) {
                JsonNode transition = action.path("transition");
                links.add(new AiFigmaPrototypeLink(
                    sourceNodeId,
                    action.path("destinationId").asText(""),
                    eventType,
                    action.path("type").asText(""),
                    transition.path("type").asText(""),
                    durationMs(transition.path("duration").asDouble(0D)),
                    prototypePayload(interaction, action)
                ));
            }
        }
        for (JsonNode child : node.path("children")) {
            collect(child, links);
        }
    }

    private JsonNode prototypePayload(JsonNode interaction, JsonNode action) {
        var payload = objectMapper.createObjectNode();
        payload.set("trigger", interaction.path("trigger"));
        payload.set("action", action);
        return payload;
    }

    private int durationMs(double duration) {
        if (duration <= 0D) {
            return 0;
        }
        return (int) Math.round(duration <= 10D ? duration * 1000D : duration);
    }
}

record AiFigmaPrototypeLink(
    String sourceNodeId,
    String targetNodeId,
    String eventType,
    String actionType,
    String transitionType,
    int durationMs,
    JsonNode prototypeLinkJson
) {
}
