package com.haitlify.aitest.platform.ai.draft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import tools.jackson.databind.node.ObjectNode;

@Component
class WebAutomationCaseValidator {

    private static final Set<String> SUPPORTED_ACTIONS = Set.of(
        "goto", "click", "input", "select", "hover", "waitForVisible", "waitForUrl",
        "assertVisible", "assertHidden", "assertText", "assertUrl", "assertEnabled", "assertDisabled",
        "scrollIntoView", "scroll_into_view", "paginate", "screenshot"
    );
    private static final Set<String> ASSERTION_ACTIONS = Set.of(
        "assertVisible", "assertHidden", "assertText", "assertUrl", "assertEnabled", "assertDisabled"
    );
    private static final Set<String> VALUE_ACTIONS = Set.of("input", "select", "assertText");
    private static final Set<String> URL_ACTIONS = Set.of("goto", "waitForUrl", "assertUrl");
    private static final Set<String> LOCATOR_ACTIONS = Set.of(
        "click", "input", "select", "hover", "waitForVisible", "assertVisible", "assertHidden", "assertText",
        "assertEnabled", "assertDisabled", "scrollIntoView", "scroll_into_view", "paginate"
    );
    private static final List<String> REQUIRED_TEXT_FIELDS = List.of(
        "sourcePointId", "title", "module", "pageName", "startUrl", "browser", "viewport",
        "objective", "naturalSteps", "naturalExpectedResult", "steps", "assertions",
        "expectedResult", "sourceFile", "sourceText"
    );
    private static final Pattern NUMBERED_PROSE = Pattern.compile("^\\d+[.)、]\\s*.+");
    private static final Pattern VIEWPORT = Pattern.compile("^(\\d{3,4})\\s*[xX*×]\\s*(\\d{3,4})$");
    private static final Pattern VALUE_SEPARATOR = Pattern.compile("\\s+=\\s+");
    private static final Pattern LOCATOR = Pattern.compile(
        "^(?:(?:frame|css)=.+ >> )*(?:css=.+|xpath=.+|text=.+|role=.+|test[Ii]d=.+|//.+|\\.//.+|\\.\\.//.+|\\(//.+|#[A-Za-z_][^\\s]*|\\.[A-Za-z_][^\\s]*|\\[.+])$"
    );
    private static final Map<String, String> BROWSERS = Map.ofEntries(
        Map.entry("chromium", "chromium"),
        Map.entry("chrome", "chrome"),
        Map.entry("google chrome", "chrome"),
        Map.entry("edge", "msedge"),
        Map.entry("msedge", "msedge"),
        Map.entry("microsoft edge", "msedge"),
        Map.entry("firefox", "firefox"),
        Map.entry("webkit", "webkit"),
        Map.entry("safari", "webkit")
    );

    WebAutomationValidationResult validateAndNormalize(ObjectNode input, int caseIndex) {
        ObjectNode normalized = input.deepCopy();
        List<String> errors = new ArrayList<>();
        String caseLabel = caseLabel(input, caseIndex);

        if (!"web_automation".equals(input.path("caseKind").asText(""))) {
            return new WebAutomationValidationResult(normalized, errors);
        }

        for (String field : REQUIRED_TEXT_FIELDS) {
            if (input.path(field).asText("").isBlank()) {
                errors.add(caseLabel + "缺少必填字段 " + field);
            }
        }

        normalizeBrowser(normalized, caseLabel, errors);
        normalizeViewport(normalized, caseLabel, errors);
        validateStartUrl(normalized.path("startUrl").asText(""), caseLabel, errors);
        validateSteps(normalized.path("steps").asText(""), caseLabel, errors);

        return new WebAutomationValidationResult(normalized, errors);
    }

    WebAutomationValidationResult validateEvidenceBoundCase(
        ObjectNode input,
        int caseIndex,
        Set<String> evidenceLocators,
        Set<String> evidencePageUrls
    ) {
        return validateEvidenceBoundCase(input, caseIndex, evidenceLocators, evidencePageUrls, Set.of());
    }

    WebAutomationValidationResult validateEvidenceBoundCase(
        ObjectNode input,
        int caseIndex,
        Set<String> evidenceLocators,
        Set<String> evidencePageUrls,
        Set<String> evidenceIds
    ) {
        WebAutomationValidationResult result = validateAndNormalize(input, caseIndex);
        if (!"web_automation".equals(input.path("caseKind").asText(""))) return result;
        List<String> errors = new ArrayList<>(result.errors());
        String label = caseLabel(input, caseIndex);
        boolean sawUrlAssertion = false;
        boolean sawBusinessAssertion = false;
        for (String rawLine : input.path("steps").asText("").split("\\R")) {
            String line = rawLine.trim();
            if (line.isBlank()) continue;
            int separator = line.indexOf(' ');
            String action = separator < 0 ? line : line.substring(0, separator);
            String arguments = separator < 0 ? "" : line.substring(separator + 1).trim();
            String target = arguments;
            if (VALUE_ACTIONS.contains(action)) {
                String[] parts = VALUE_SEPARATOR.split(arguments, 2);
                target = parts.length > 0 ? parts[0].trim() : "";
            }
            boolean inLocatorWhitelist = evidenceLocators.contains(target) || evidenceIds.contains(target);
            if ("assertUrl".equals(action)) {
                sawUrlAssertion = true;
            }
            if (Set.of("assertVisible", "assertHidden", "assertText", "assertEnabled", "assertDisabled").contains(action)) {
                sawBusinessAssertion = true;
            }
            if (LOCATOR_ACTIONS.contains(action) && !inLocatorWhitelist) {
                errors.add(label + "动作 " + action + " locator 未由当前浏览器证据验证");
            }
            if (URL_ACTIONS.contains(action) && !evidencePageUrls.contains(target)) {
                errors.add(label + "动作 " + action + " URL 未由当前浏览器证据验证");
            }
        }
        if (isContentOriented(input) && sawUrlAssertion && !sawBusinessAssertion) {
            errors.add(label + "内容型目标不能只使用 assertUrl");
        }
        return new WebAutomationValidationResult(result.normalizedCase(), errors);
    }

    private boolean isContentOriented(ObjectNode input) {
        String normalized = String.join(" ",
            input.path("title").asText(""),
            input.path("module").asText(""),
            input.path("pageName").asText(""),
            input.path("objective").asText(""),
            input.path("naturalExpectedResult").asText(""),
            input.path("sourceText").asText("")
        ).toLowerCase(Locale.ROOT);
        return List.of("tab", "标签", "页签", "展示", "显示", "可见", "列表", "字段", "按钮", "入口", "菜单", "筛选", "搜索", "导出", "状态", "文案", "标题", "顺序")
            .stream()
            .anyMatch(normalized::contains);
    }

    private void normalizeBrowser(ObjectNode testCase, String caseLabel, List<String> errors) {
        String raw = testCase.path("browser").asText("").trim().toLowerCase(Locale.ROOT);
        if (raw.isBlank()) return;
        String normalized = BROWSERS.get(raw);
        if (normalized == null) {
            errors.add(caseLabel + "browser 不受 runner 支持: " + raw);
            return;
        }
        testCase.put("browser", normalized);
    }

    private void normalizeViewport(ObjectNode testCase, String caseLabel, List<String> errors) {
        String raw = testCase.path("viewport").asText("").trim();
        if (raw.isBlank()) return;
        Matcher matcher = VIEWPORT.matcher(raw);
        if (!matcher.matches()) {
            errors.add(caseLabel + "viewport 格式无效，应为 1440x900");
            return;
        }
        int width = Integer.parseInt(matcher.group(1));
        int height = Integer.parseInt(matcher.group(2));
        if (width < 320 || width > 7680 || height < 240 || height > 4320) {
            errors.add(caseLabel + "viewport 超出 runner 支持范围: " + raw);
            return;
        }
        testCase.put("viewport", width + "x" + height);
    }

    private void validateStartUrl(String startUrl, String caseLabel, List<String> errors) {
        if (startUrl.isBlank()) return;
        if (!isUrlTarget(startUrl)) {
            errors.add(caseLabel + "startUrl 不是可执行 URL 或路径: " + startUrl);
        }
    }

    private void validateSteps(String steps, String caseLabel, List<String> errors) {
        if (steps.isBlank()) return;
        String[] lines = steps.split("\\R");
        boolean hasAssertion = false;
        int executableLine = 0;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isBlank()) continue;
            executableLine++;
            String stepLabel = caseLabel + "步骤 " + executableLine + " ";
            if (NUMBERED_PROSE.matcher(line).matches()) {
                errors.add(stepLabel + "是编号自然语言，必须改为动作 DSL");
                continue;
            }

            int separator = line.indexOf(' ');
            String action = separator < 0 ? line : line.substring(0, separator).trim();
            String arguments = separator < 0 ? "" : line.substring(separator + 1).trim();
            if (!SUPPORTED_ACTIONS.contains(action)) {
                errors.add(stepLabel + "动作 " + action + " 不支持");
                continue;
            }
            if (ASSERTION_ACTIONS.contains(action)) hasAssertion = true;
            if (arguments.isBlank() && !"screenshot".equals(action)) {
                errors.add(stepLabel + "动作 " + action + " 缺少目标");
                continue;
            }

            String target = arguments;
            if (VALUE_ACTIONS.contains(action)) {
                String[] parts = VALUE_SEPARATOR.split(arguments, 2);
                if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    errors.add(stepLabel + "动作 " + action + " 必须使用 <target> = <value>");
                    continue;
                }
                target = parts[0].trim();
            }
            if (URL_ACTIONS.contains(action) && !isUrlTarget(target)) {
                errors.add(stepLabel + "动作 " + action + " 缺少合法 URL 目标");
            }
            if (LOCATOR_ACTIONS.contains(action) && !LOCATOR.matcher(target).matches()) {
                errors.add(stepLabel + "动作 " + action + " 缺少合法 locator，不能使用自然语言句子");
            }
        }

        if (!hasAssertion) {
            errors.add(caseLabel + "至少一个断言动作是必需的: assertVisible/assertHidden/assertText/assertUrl/assertEnabled/assertDisabled");
        }
    }

    private boolean isUrlTarget(String target) {
        String value = target.trim();
        if (value.startsWith("/")
            || value.startsWith("http://")
            || value.startsWith("https://")
            || value.startsWith("{{")
            || value.startsWith("*")) {
            return true;
        }
        // 与编译器/执行器保持同一条路由口径：同源相对路径（detail、users/detail、?q=1、#/x、./x、../x）
        // 是合法地址，浏览器会相对当前页面解析。之前这里只认 / 开头与绝对地址，
        // 于是「编译器认可的相对路由」在 DSL 校验层被判成缺少 URL 目标（同一个值两处判定相反）。
        // 自然语言句子（含空白、中文、标点）仍然被拒绝，避免把描述文字当成地址执行。
        return value.matches("(?:[A-Za-z0-9][A-Za-z0-9._~/-]*|[?#].*|\\.{1,2}/.*)");
    }

    private String caseLabel(ObjectNode testCase, int caseIndex) {
        String sourcePointId = testCase.path("sourcePointId").asText("").trim();
        return sourcePointId.isBlank()
            ? "用例 " + caseIndex + "："
            : "用例 " + sourcePointId + "：";
    }
}

record WebAutomationValidationResult(
    ObjectNode normalizedCase,
    List<String> errors
) {
}
