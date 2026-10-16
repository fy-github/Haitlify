package com.haitlify.aitest.platform.document;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class FigmaDesignClient {

    private final FigmaDesignProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    FigmaDesignClient(FigmaDesignProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        int timeoutMs = properties.timeoutMs() > 0 ? properties.timeoutMs() : 3000;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(timeoutMs))
            .build();
    }

    public FigmaDesignParseResult fetchDesign(String fileKey, String nodeId) {
        if (!properties.enabled() || properties.token() == null || properties.token().isBlank()) {
            throw new FigmaDesignUnavailableException("Figma API is not enabled");
        }

        try {
            String query = nodeId == null || nodeId.isBlank()
                ? ""
                : "?ids=" + URLEncoder.encode(nodeId, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.baseUrl().replaceAll("/$", "") + "/v1/files/" + fileKey + "/nodes" + query))
                .timeout(Duration.ofMillis(properties.timeoutMs() > 0 ? properties.timeoutMs() : 3000))
                .header("X-Figma-Token", properties.token())
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new FigmaDesignUnavailableException("Figma API returned HTTP " + response.statusCode());
            }
            return toParseResult(fileKey, nodeId, objectMapper.readTree(response.body()));
        } catch (FigmaDesignUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new FigmaDesignUnavailableException("Figma API is unavailable", exception);
        }
    }

    private FigmaDesignParseResult toParseResult(String fileKey, String nodeId, JsonNode payload) {
        JsonNode document = payload.path("nodes").path(nodeId).path("document");
        String fileName = payload.path("name").asText("Figma Design");
        int width = document.path("absoluteBoundingBox").path("width").asInt(0);
        int height = document.path("absoluteBoundingBox").path("height").asInt(0);
        double aspectRatio = width > 0 && height > 0 ? ((double) width) / height : 0D;
        String viewportHint = width >= height && width > 0 ? "desktop" : (height > width ? "mobile" : "unknown");
        String layoutMode = document.path("layoutMode").asText("");
        int itemSpacing = (int) Math.round(document.path("itemSpacing").asDouble(0D));
        String counterAxisAlignItems = document.path("counterAxisAlignItems").asText("");
        int cornerRadius = document.path("cornerRadius").asInt(0);
        int paddingTop = (int) Math.round(document.path("paddingTop").asDouble(0D));
        int paddingRight = (int) Math.round(document.path("paddingRight").asDouble(0D));
        int paddingBottom = (int) Math.round(document.path("paddingBottom").asDouble(0D));
        int paddingLeft = (int) Math.round(document.path("paddingLeft").asDouble(0D));

        Set<String> componentHints = new LinkedHashSet<>();
        Set<String> styleHints = new LinkedHashSet<>();
        Set<String> suggestedRequirementPoints = new LinkedHashSet<>();
        JsonNode nodes = payload.path("nodes");
        collectNodeHints(document, new ArrayList<>(), componentHints, styleHints, suggestedRequirementPoints, nodes, payload);

        List<String> layoutHints = new ArrayList<>();
        layoutHints.add("VERTICAL".equals(layoutMode) ? "存在 Auto Layout 垂直流布局" : "存在自由布局或横向布局");
        if (itemSpacing > 0) {
            layoutHints.add("主轴间距 " + itemSpacing + "px");
        }
        if ("CENTER".equals(counterAxisAlignItems)) {
            layoutHints.add("横向居中对齐");
        }
        if (paddingTop > 0 || paddingRight > 0 || paddingBottom > 0 || paddingLeft > 0) {
            layoutHints.add("内边距 上" + paddingTop + " / 右" + paddingRight + " / 下" + paddingBottom + " / 左" + paddingLeft);
        }
        if (containsConstraint(document, "LEFT_RIGHT")) {
            layoutHints.add("左右约束跟随容器变化");
        }
        List<String> prototypeFlow = collectPrototypeFlow(componentHints);
        if (!prototypeFlow.isEmpty()) {
            layoutHints.add("页面流: " + String.join(" -> ", prototypeFlow));
        }

        String fillColor = solidFillHex(document.path("fills"));
        if (!fillColor.isBlank()) {
            styleHints.add("主按钮填充色 " + fillColor);
        }
        if (cornerRadius > 0) {
            styleHints.add("圆角 " + cornerRadius + "px");
        }
        String strokeColor = solidFillHex(document.path("strokes"));
        if (!strokeColor.isBlank()) {
            styleHints.add("描边色 " + strokeColor);
        }
        String shadowHint = dropShadowHint(document.path("effects"));
        if (!shadowHint.isBlank()) {
            styleHints.add(shadowHint);
        }
        double opacity = document.path("opacity").asDouble(0D);
        if (opacity > 0D) {
            styleHints.add("不透明度 " + (int) Math.round(opacity * 100) + "%");
        }
        String blendMode = document.path("blendMode").asText("");
        if (!blendMode.isBlank()) {
            styleHints.add("混合模式 " + blendMode);
        }
        if (styleHints.isEmpty()) {
            styleHints.add("待结合 Figma 样式 token 校验颜色、字体、圆角、阴影");
        }
        if (!strokeColor.isBlank() || !shadowHint.isBlank() || opacity > 0D || !blendMode.isBlank()) {
            suggestedRequirementPoints.add("卡片描边、阴影、透明度与混合模式符合设计稿");
        }

        suggestedRequirementPoints.add("主流程按钮、输入框与标题层级符合 Figma 设计结构");
        suggestedRequirementPoints.add("布局结构、间距、对齐与 Auto Layout 约束符合设计稿");
        if (containsVariantState(document)) {
            suggestedRequirementPoints.add("组件默认态、悬浮态、禁用态与 Figma 设计变体一致");
        }
        if (hasMultiVariantCombination(document)) {
            suggestedRequirementPoints.add("组件 Size / State 等变体组合与设计稿定义一致");
        }

        RequirementVisualSummary visualSummary = new RequirementVisualSummary(
            width,
            height,
            aspectRatio,
            viewportHint,
            layoutHints,
            List.copyOf(styleHints),
            componentHints.isEmpty() ? List.of("待解析组件树") : List.copyOf(componentHints),
            "available"
        );

        return new FigmaDesignParseResult(
            fileName,
            fileName,
            "Figma File Key: " + fileKey + "\nFigma Node ID: " + nodeId,
            visualSummary,
            List.copyOf(suggestedRequirementPoints)
        );
    }

    private boolean containsVariantState(JsonNode document) {
        for (JsonNode child : document.path("children")) {
            JsonNode componentProperties = child.path("componentProperties");
            if (!componentProperties.isMissingNode() && componentProperties.size() > 0) {
                return true;
            }
            if (containsVariantState(child)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMultiVariantCombination(JsonNode document) {
        for (JsonNode child : document.path("children")) {
            JsonNode componentProperties = child.path("componentProperties");
            if (!componentProperties.isMissingNode() && componentProperties.size() > 1) {
                return true;
            }
            if (hasMultiVariantCombination(child)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsConstraint(JsonNode document, String horizontalValue) {
        for (JsonNode child : document.path("children")) {
            if (horizontalValue.equals(child.path("constraints").path("horizontal").asText(""))) {
                return true;
            }
            if (containsConstraint(child, horizontalValue)) {
                return true;
            }
        }
        return false;
    }

    private void collectNodeHints(
        JsonNode node,
        List<String> path,
        Set<String> componentHints,
        Set<String> styleHints,
        Set<String> suggestedRequirementPoints,
        JsonNode nodes,
        JsonNode payload
    ) {
        String name = node.path("name").asText("");
        List<String> nextPath = path;
        if (!name.isBlank()) {
            nextPath = new ArrayList<>(path);
            nextPath.add(name);
            componentHints.add(name);
            if (nextPath.size() >= 3 && ("INSTANCE".equals(node.path("type").asText("")) || "TEXT".equals(node.path("type").asText("")))) {
                componentHints.add(normalizeComponentPath(nextPath));
            }
        }

        collectChildHints(node, componentHints, styleHints, suggestedRequirementPoints);
        collectInteractionHints(node, componentHints, suggestedRequirementPoints, nodes, payload);
        for (JsonNode child : node.path("children")) {
            collectNodeHints(child, nextPath, componentHints, styleHints, suggestedRequirementPoints, nodes, payload);
        }
        if (nextPath.size() >= 3) {
            suggestedRequirementPoints.add("表单区、输入框、主按钮等关键组件层级与设计稿一致");
        }
    }

    private String normalizeComponentPath(List<String> path) {
        if (path.size() <= 3) {
            return String.join(" > ", path);
        }
        return String.join(" > ", path.subList(1, path.size()));
    }

    private void collectChildHints(
        JsonNode child,
        Set<String> componentHints,
        Set<String> styleHints,
        Set<String> suggestedRequirementPoints
    ) {
        JsonNode style = child.path("style");
        int fontWeight = style.path("fontWeight").asInt(0);
        int fontSize = (int) Math.round(style.path("fontSize").asDouble(0D));
        if (fontWeight > 0 && fontSize > 0) {
            styleHints.add("标题字重 " + fontWeight + "，字号 " + fontSize + "px");
            suggestedRequirementPoints.add("标题排版、字重、字号与 Figma 设计稿一致");
        }
        int lineHeight = (int) Math.round(style.path("lineHeightPx").asDouble(0D));
        double letterSpacing = style.path("letterSpacing").asDouble(Double.NaN);
        if (lineHeight > 0 && !Double.isNaN(letterSpacing)) {
            styleHints.add("标题行高 " + lineHeight + "px，字距 " + trimDecimal(letterSpacing) + "px");
        }
        String fontFamily = style.path("fontFamily").asText("");
        if (!fontFamily.isBlank()) {
            styleHints.add("标题字体 " + fontFamily);
        }
        String textAlignHorizontal = style.path("textAlignHorizontal").asText("");
        if (!textAlignHorizontal.isBlank()) {
            styleHints.add("标题对齐 " + textAlignHorizontal);
        }
        String textCase = style.path("textCase").asText("");
        if (!textCase.isBlank()) {
            styleHints.add("标题大小写 " + textCase);
        }
        String textDecoration = style.path("textDecoration").asText("");
        if (!textDecoration.isBlank()) {
            styleHints.add("标题删除线 " + textDecoration);
        }
        int paragraphSpacing = (int) Math.round(style.path("paragraphSpacing").asDouble(0D));
        if (paragraphSpacing > 0) {
            styleHints.add("段后间距 " + paragraphSpacing + "px");
        }
        String textColor = solidFillHex(child.path("fills"));
        if (!textColor.isBlank()) {
            styleHints.add("标题文本色 " + textColor);
        }
        if (!textAlignHorizontal.isBlank() || !textCase.isBlank() || !textDecoration.isBlank() || paragraphSpacing > 0) {
            suggestedRequirementPoints.add("标题对齐、大小写、装饰线与段落间距符合设计稿");
        }

        JsonNode characters = child.path("characters");
        JsonNode styleOverrideTable = child.path("styleOverrideTable");
        JsonNode characterStyleOverrides = child.path("characterStyleOverrides");
        if (characters.isTextual() && styleOverrideTable.isObject() && characterStyleOverrides.isArray()) {
            collectRichTextHints(
                characters.asText(""),
                styleOverrideTable,
                characterStyleOverrides,
                styleHints,
                suggestedRequirementPoints
            );
        }

        JsonNode componentProperties = child.path("componentProperties");
        if (!componentProperties.isMissingNode()) {
            componentProperties.properties().forEach(entry -> {
                String value = entry.getValue().path("value").asText("");
                if (!value.isBlank()) {
                    componentHints.add(entry.getKey() + "=" + value);
                }
            });
        }
    }

    private String solidFillHex(JsonNode fills) {
        if (!fills.isArray()) return "";
        for (JsonNode fill : fills) {
            if (!"SOLID".equals(fill.path("type").asText(""))) continue;
            JsonNode color = fill.path("color");
            int red = (int) Math.round(color.path("r").asDouble(0D) * 255);
            int green = (int) Math.round(color.path("g").asDouble(0D) * 255);
            int blue = (int) Math.round(color.path("b").asDouble(0D) * 255);
            return String.format("#%02X%02X%02X", red, green, blue);
        }
        return "";
    }

    private String dropShadowHint(JsonNode effects) {
        if (!effects.isArray()) return "";
        for (JsonNode effect : effects) {
            if (!"DROP_SHADOW".equals(effect.path("type").asText(""))) continue;
            int offsetY = (int) Math.round(effect.path("offset").path("y").asDouble(0D));
            int radius = (int) Math.round(effect.path("radius").asDouble(0D));
            JsonNode color = effect.path("color");
            int red = (int) Math.round(color.path("r").asDouble(0D) * 255);
            int green = (int) Math.round(color.path("g").asDouble(0D) * 255);
            int blue = (int) Math.round(color.path("b").asDouble(0D) * 255);
            double alpha = color.path("a").asDouble(0D);
            return "投影 y=" + offsetY + " blur=" + radius + " rgba("
                + red + "," + green + "," + blue + "," + trimDecimal(alpha) + ")";
        }
        return "";
    }

    private String trimDecimal(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001D) {
            return String.valueOf((int) Math.round(value));
        }
        return String.valueOf(value);
    }

    private void collectRichTextHints(
        String characters,
        JsonNode styleOverrideTable,
        JsonNode characterStyleOverrides,
        Set<String> styleHints,
        Set<String> suggestedRequirementPoints
    ) {
        int start = 0;
        while (start < characterStyleOverrides.size()) {
            int overrideId = characterStyleOverrides.path(start).asInt(0);
            int end = start + 1;
            while (end < characterStyleOverrides.size() && characterStyleOverrides.path(end).asInt(0) == overrideId) {
                end++;
            }
            if (overrideId > 0 && start < characters.length()) {
                String segment = characters.substring(start, Math.min(end, characters.length())).trim();
                if (!segment.isBlank()) {
                    JsonNode overrideStyle = styleOverrideTable.path(String.valueOf(overrideId));
                    int fontWeight = overrideStyle.path("fontWeight").asInt(0);
                    int fontSize = (int) Math.round(overrideStyle.path("fontSize").asDouble(0D));
                    String color = solidFillHex(overrideStyle.path("fills"));
                    styleHints.add(
                        "富文本片段 " + segment + " 字重 " + fontWeight
                            + "，字号 " + fontSize + "px，颜色 " + color
                    );
                    suggestedRequirementPoints.add("富文本不同片段的字号、字重、颜色与设计稿一致");
                }
            }
            start = end;
        }
    }

    private void collectInteractionHints(
        JsonNode node,
        Set<String> componentHints,
        Set<String> suggestedRequirementPoints,
        JsonNode nodes,
        JsonNode payload
    ) {
        String sourceName = node.path("name").asText("");
        if (sourceName.isBlank()) {
            return;
        }
        for (JsonNode interaction : node.path("interactions")) {
            String trigger = interaction.path("trigger").path("type").asText("");
            for (JsonNode action : interaction.path("actions")) {
                String actionType = action.path("type").asText("");
                String navigation = action.path("navigation").asText("");
                String destinationId = action.path("destinationId").asText("");
                String transitionType = action.path("transition").path("type").asText("");
                int durationMs = (int) Math.round(action.path("transition").path("duration").asDouble(0D) * 1000);
                String destinationName = resolveNodeName(destinationId, nodes, payload);

                if ("BACK".equals(actionType) && !trigger.isBlank()) {
                    componentHints.add(sourceName + " --" + trigger + "--> BACK");
                    suggestedRequirementPoints.add("悬浮提示层、返回、锚点滚动等原型交互符合设计稿");
                    continue;
                }

                if ("CLOSE_OVERLAY".equals(actionType) && !trigger.isBlank()) {
                    componentHints.add(sourceName + " --" + trigger + "--> null [CLOSE_OVERLAY 0ms]");
                    suggestedRequirementPoints.add("拖拽、延时消失、关闭浮层等复杂原型交互符合设计稿");
                    continue;
                }

                if (!trigger.isBlank() && !destinationName.isBlank()) {
                    if (!transitionType.isBlank()) {
                        componentHints.add(sourceName + " --" + trigger + "--> " + destinationName + " [" + transitionType + " " + durationMs + "ms]");
                    } else if (!navigation.isBlank()) {
                        componentHints.add(sourceName + " --" + trigger + "--> " + destinationName + " [" + navigation + " 0ms]");
                    }

                    if ("SMART_ANIMATE".equals(transitionType) && durationMs > 0) {
                        suggestedRequirementPoints.add("点击主按钮后以 " + transitionType + " " + durationMs + "ms 跳转到 " + destinationName);
                    }
                    if ("OVERLAY".equals(navigation) || "SCROLL_TO".equals(navigation) || "ON_HOVER".equals(trigger)) {
                        suggestedRequirementPoints.add("悬浮提示层、返回、锚点滚动等原型交互符合设计稿");
                    }
                    if ("ON_DRAG".equals(trigger)) {
                        suggestedRequirementPoints.add("拖拽、延时消失、关闭浮层等复杂原型交互符合设计稿");
                    }
                } else if (!trigger.isBlank() && !transitionType.isBlank()) {
                    componentHints.add(sourceName + " --" + trigger + "--> null [" + transitionType + " " + durationMs + "ms]");
                    suggestedRequirementPoints.add("拖拽、延时消失、关闭浮层等复杂原型交互符合设计稿");
                }
            }
        }
    }

    private String resolveNodeName(String nodeId, JsonNode nodes, JsonNode payload) {
        if (nodeId == null || nodeId.isBlank()) return "";
        JsonNode direct = nodes.path(nodeId).path("document");
        if (!direct.isMissingNode()) {
            return direct.path("name").asText("");
        }
        JsonNode components = nodes.path("components").path(nodeId);
        if (!components.isMissingNode()) {
            return components.path("name").asText("");
        }
        JsonNode payloadComponents = payload.path("components").path(nodeId);
        if (!payloadComponents.isMissingNode()) {
            return payloadComponents.path("name").asText("");
        }
        JsonNode nodeScopedComponents = payload.path("nodes").properties().iterator().hasNext()
            ? payload.path("nodes").values().iterator().next().path("components").path(nodeId)
            : JsonNodeFactory.instance.missingNode();
        return nodeScopedComponents.path("name").asText("");
    }

    private List<String> collectPrototypeFlow(Set<String> componentHints) {
        List<String> flow = new ArrayList<>();
        for (String hint : componentHints) {
            if (hint.startsWith("Button / Primary --ON_CLICK--> Success / Home")) {
                flow.add("Button / Primary");
                flow.add("Success / Home");
            }
            if (hint.startsWith("Back / Link --ON_CLICK--> BACK")) {
                flow.add("Back / Link");
                flow.add("BACK");
            }
        }
        return flow;
    }
}
