package com.haitlify.aitest.platform.ai.draft;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService;

import tools.jackson.databind.JsonNode;

/** Builds a deterministic, business-focused browser exploration target from a natural plan. */
final class WebExplorationIntentBuilder {

    private static final int MAX_PAGE_ALIASES = 8;
    private static final int MAX_ROUTE_HINTS = 8;
    private static final int MAX_ELEMENT_TERMS = 8;
    private static final int MAX_ACTION_TERMS = 4;
    private static final int MAX_STATE_TERMS = 6;
    private static final int MAX_EXPECTED_TEXTS = 6;
    private static final int MAX_NEGATIVE_TERMS = 8;
    private static final List<String> ELEMENT_KEYWORDS = List.of(
        "停用", "重新开通", "重新开启", "恢复服务资格", "关闭", "删除", "按钮", "操作列", "状态列", "列表", "详情", "入口", "Tab", "标签", "页签", "字段", "文案", "提示", "菜单", "状态"
    );
    private static final List<String> ACTION_KEYWORDS = List.of(
        "查看", "点击", "进入", "切换", "筛选", "搜索", "查询", "导出", "新增", "添加", "编辑", "保存", "删除", "停用", "关闭", "重新开通", "提交", "确认"
    );
    private static final List<String> STATE_KEYWORDS = List.of(
        "有效期", "即将到期", "已过期", "已停用", "已启用", "成功", "失败", "离线", "在线", "启用中", "停用中", "灰度中", "冻结", "失效", "权益失效", "资格失效"
    );
    private static final Pattern QUOTED_UI_COPY = Pattern.compile("[\\\"'‘’“”「」『』]([^\\\"'‘’“”「」『』]{2,80})[\\\"'‘’“”「」『』]");
    private static final List<String> GENERIC_TERMS = List.of("项目", "设备", "应用", "系统", "服务", "用户", "首页", "管理", "页面");

    WebAutomationLocatorDiscoveryService.TargetIntent build(JsonNode plan, Map<String, Object> source) {
        JsonNode safePlan = plan == null ? JsonNodeFactoryShim.missing() : plan;
        Map<String, Object> safeSource = source == null ? Map.of() : source;
        String pageName = firstText(safePlan, "pageName", "targetPage", "page", "pageOrFeature");
        String moduleName = firstText(safePlan, "module", "moduleName");
        String sourcePage = value(safeSource, "PAGE_OR_FEATURE");
        String breadcrumbs = text(safePlan.path("breadcrumbs"));
        String semanticText = Stream.of(
            text(safePlan.path("title")),
            text(safePlan.path("objective")),
            text(safePlan.path("naturalSteps")),
            text(safePlan.path("naturalExpectedResult")),
            text(safePlan.path("expectedResult")),
            value(safeSource, "TITLE"),
            value(safeSource, "TEST_OBJECTIVE"),
            value(safeSource, "EXPECTED_RESULT"),
            value(safeSource, "SOURCE_TEXT")
        ).filter(value -> !value.isBlank()).collect(Collectors.joining(" "));
        String contextText = Stream.of(moduleName, pageName, breadcrumbs,
            text(safePlan.path("activeMenus")), text(safePlan.path("headings")),
            value(safeSource, "MODULE"), value(safeSource, "BREADCRUMBS"), value(safeSource, "ROUTE_HINTS"))
            .filter(value -> !value.isBlank()).collect(Collectors.joining(" "));
        String allText = semanticText + " " + contextText;
        List<String> quotedCopy = quotedUiCopy(allText);
        List<String> quotedStateTerms = quotedCopy.stream().filter(this::looksLikeStatePhrase).toList();
        List<String> deterministicElements = distinct(MAX_ELEMENT_TERMS,
            String.join("\n", quotedCopy),
            String.join("\n", presentKeywords(allText, ELEMENT_KEYWORDS))
        );
        deterministicElements = deterministicElements.stream()
            .filter(term -> !GENERIC_TERMS.contains(term))
            .toList();
        List<String> pageAliases = distinct(MAX_PAGE_ALIASES, pageName, moduleName, sourcePage, breadcrumbs,
            text(safePlan.path("pageAliases")), firstText(safePlan, "pageAlias", "routeAlias"),
            value(safeSource, "PAGE_ALIASES"), value(safeSource, "ROUTE_ALIASES"));

        return new WebAutomationLocatorDiscoveryService.TargetIntent(
            pageAliases,
            distinct(MAX_ROUTE_HINTS,
                firstText(safePlan, "startUrl", "pageUrl", "url", "path", "route"),
                value(safeSource, "START_URL"), value(safeSource, "PAGE_URL"), value(safeSource, "ROUTE_HINT"),
                value(safeSource, "ROUTE_HINTS"), value(safeSource, "ROUTE_ALIAS"), value(safeSource, "ROUTE_ALIASES")
            ),
            deterministicElements,
            distinct(MAX_ACTION_TERMS, String.join("\n", presentKeywords(allText, ACTION_KEYWORDS)), value(safeSource, "ACTION_TERMS")),
            distinct(MAX_STATE_TERMS, String.join("\n", presentKeywords(allText, STATE_KEYWORDS)), String.join("\n", quotedStateTerms), value(safeSource, "STATE_TERMS")),
            distinct(MAX_EXPECTED_TEXTS, text(safePlan.path("naturalExpectedResult")), text(safePlan.path("expectedResult"))),
            distinct(MAX_NEGATIVE_TERMS, value(safeSource, "NEGATIVE_TERMS"), value(safeSource, "EXPLICIT_NEGATIVE_TERMS"))
        );
    }

    private List<String> quotedUiCopy(String value) {
        List<String> matches = new ArrayList<>();
        Matcher matcher = QUOTED_UI_COPY.matcher(value == null ? "" : value);
        while (matcher.find()) {
            String candidate = matcher.group(1).strip();
            if (!candidate.isBlank()) matches.add(candidate);
        }
        return matches;
    }

    private boolean looksLikeStatePhrase(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) return false;
        String lowered = normalized.toLowerCase(Locale.ROOT);
        return lowered.contains("状态")
            || lowered.contains("失效")
            || lowered.contains("冻结")
            || lowered.contains("启用")
            || lowered.contains("停用")
            || lowered.contains("在线")
            || lowered.contains("离线")
            || lowered.contains("成功")
            || lowered.contains("失败")
            || lowered.contains("灰度")
            || lowered.contains("有效");
    }

    private List<String> presentKeywords(String text, List<String> keywords) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return keywords.stream()
            .filter(keyword -> normalized.contains(keyword.toLowerCase(Locale.ROOT)))
            .toList();
    }

    private List<String> distinct(int max, String... values) {
        Set<String> result = new LinkedHashSet<>();
        if (max <= 0) return List.of();
        for (String value : values) {
            if (value == null) continue;
            for (String item : value.split("\\R")) {
                String normalized = item.strip().replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n");
                if (!normalized.isBlank()) {
                    for (String line : normalized.split("\\R")) {
                        String candidate = line.strip();
                        if (candidate.isBlank()) continue;
                        result.add(candidate);
                        if (result.size() >= max) return List.copyOf(result);
                    }
                }
            }
        }
        return List.copyOf(result.stream().limit(max).toList());
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node.path(field));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                String value = item.asText("").strip();
                if (!value.isBlank()) values.add(value);
            }
            return String.join("\n", values);
        }
        return node.asText("").strip();
    }

    private String value(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Iterable<?> values) {
            List<String> items = new ArrayList<>();
            for (Object item : values) {
                if (item != null && !String.valueOf(item).isBlank()) items.add(String.valueOf(item).strip());
            }
            return String.join("\n", items);
        }
        return value == null ? "" : String.valueOf(value).strip();
    }

    /** Avoid coupling this package-private builder to a concrete Jackson missing-node implementation. */
    private static final class JsonNodeFactoryShim {
        private static JsonNode missing() {
            return new tools.jackson.databind.ObjectMapper().createObjectNode().path("missing");
        }
    }
}
