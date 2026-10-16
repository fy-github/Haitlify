package com.haitlify.aitest.platform.ai.figma;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AiFigmaStyleExtractor {

    private final ObjectMapper objectMapper;

    AiFigmaStyleExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<AiFigmaStyleToken> extractTokens(JsonNode root) {
        List<AiFigmaStyleToken> tokens = new ArrayList<>();
        collect(root, tokens);
        return tokens;
    }

    private void collect(JsonNode node, List<AiFigmaStyleToken> tokens) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        String nodeName = node.path("name").asText("");
        collectColors(nodeName, node.path("fills"), "fill", tokens);
        collectColors(nodeName, node.path("strokes"), "stroke", tokens);
        collectTypography(nodeName, node.path("style"), tokens);
        collectRadius(nodeName, node, tokens);
        collectShadows(nodeName, node.path("effects"), tokens);
        collectSpacing(nodeName, node, tokens);
        collectOpacity(nodeName, node, tokens);
        collectLayout(nodeName, node, tokens);
        for (JsonNode child : node.path("children")) {
            collect(child, tokens);
        }
    }

    private void collectColors(String nodeName, JsonNode paints, String paintName, List<AiFigmaStyleToken> tokens) {
        if (!paints.isArray()) {
            return;
        }
        int index = 0;
        for (JsonNode paint : paints) {
            if ("SOLID".equals(paint.path("type").asText(""))) {
                String hex = colorHex(paint.path("color"));
                tokens.add(new AiFigmaStyleToken("color", nodeName + " " + paintName + " " + hex, withNode(paint, nodeName)));
            }
            index++;
        }
    }

    private void collectTypography(String nodeName, JsonNode style, List<AiFigmaStyleToken> tokens) {
        if (style.isMissingNode() || style.isNull() || style.size() == 0) {
            return;
        }
        String fontFamily = style.path("fontFamily").asText("");
        int fontSize = (int) Math.round(style.path("fontSize").asDouble(0D));
        if (!fontFamily.isBlank() || fontSize > 0) {
            tokens.add(new AiFigmaStyleToken(
                "typography",
                nodeName + " typography " + fontFamily + " " + fontSize + "px",
                withNode(style, nodeName)
            ));
        }
    }

    private void collectRadius(String nodeName, JsonNode node, List<AiFigmaStyleToken> tokens) {
        if (node.has("cornerRadius")) {
            int radius = (int) Math.round(node.path("cornerRadius").asDouble(0D));
            tokens.add(new AiFigmaStyleToken("radius", nodeName + " radius " + radius + "px", withNode(node.path("cornerRadius"), nodeName)));
        }
    }

    private void collectShadows(String nodeName, JsonNode effects, List<AiFigmaStyleToken> tokens) {
        if (!effects.isArray()) {
            return;
        }
        for (JsonNode effect : effects) {
            if ("DROP_SHADOW".equals(effect.path("type").asText("")) || "INNER_SHADOW".equals(effect.path("type").asText(""))) {
                tokens.add(new AiFigmaStyleToken("shadow", nodeName + " shadow " + effect.path("type").asText(""), withNode(effect, nodeName)));
            }
        }
    }

    private void collectSpacing(String nodeName, JsonNode node, List<AiFigmaStyleToken> tokens) {
        if (node.has("itemSpacing")) {
            int spacing = (int) Math.round(node.path("itemSpacing").asDouble(0D));
            tokens.add(new AiFigmaStyleToken("spacing", nodeName + " spacing " + spacing + "px", withNode(node.path("itemSpacing"), nodeName)));
        }
    }

    private void collectOpacity(String nodeName, JsonNode node, List<AiFigmaStyleToken> tokens) {
        if (node.has("opacity")) {
            int percent = (int) Math.round(node.path("opacity").asDouble(1D) * 100D);
            tokens.add(new AiFigmaStyleToken("opacity", nodeName + " opacity " + percent + "%", withNode(node.path("opacity"), nodeName)));
        }
    }

    private void collectLayout(String nodeName, JsonNode node, List<AiFigmaStyleToken> tokens) {
        String layoutMode = node.path("layoutMode").asText("");
        if (!layoutMode.isBlank()) {
            tokens.add(new AiFigmaStyleToken("layout", nodeName + " layout " + layoutMode, withNode(node.path("layoutMode"), nodeName)));
        }
    }

    private JsonNode withNode(JsonNode value, String nodeName) {
        var token = objectMapper.createObjectNode();
        token.put("nodeName", nodeName);
        token.set("value", value == null || value.isMissingNode() ? objectMapper.createObjectNode() : value);
        if (value != null && value.isObject()) {
            value.properties().forEach(entry -> token.set(entry.getKey(), entry.getValue()));
        }
        return token;
    }

    private String colorHex(JsonNode color) {
        int r = channel(color.path("r").asDouble(0D));
        int g = channel(color.path("g").asDouble(0D));
        int b = channel(color.path("b").asDouble(0D));
        return "#%02X%02X%02X".formatted(r, g, b);
    }

    private int channel(double value) {
        return Math.max(0, Math.min(255, (int) Math.round(value * 255D)));
    }
}

record AiFigmaStyleToken(
    String tokenCategory,
    String tokenName,
    JsonNode tokenJson
) {
}
