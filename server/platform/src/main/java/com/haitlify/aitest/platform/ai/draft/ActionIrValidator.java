package com.haitlify.aitest.platform.ai.draft;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/** Strict boundary validation for model-produced Action IR. */
final class ActionIrValidator {
    static final Set<String> SUPPORTED_TYPES = Set.of(
        "navigate", "goto", "click", "fill", "input", "select", "hover",
        "waitForVisible", "wait_for_visible", "waitForUrl", "wait_for_url",
        "assert", "assertVisible", "assert_visible", "assertHidden", "assert_hidden",
        "assertText", "assert_text", "assertUrl", "assert_url",
        "assertEnabled", "assert_enabled", "assertDisabled", "assert_disabled",
        "scrollIntoView", "scroll_into_view", "paginate", "screenshot"
    );
    private static final Set<String> SAFETY = Set.of("readonly", "mutating", "dangerous");
    private static final Set<String> LOCATOR_TYPES = Set.of(
        "click", "fill", "input", "select", "hover", "waitforvisible", "wait_for_visible",
        "assertvisible", "assert_visible", "asserthidden", "assert_hidden", "asserttext", "assert_text",
        "assertenabled", "assert_enabled", "assertdisabled", "assert_disabled",
        "scrollintoview", "scroll_into_view", "paginate"
    );
    private static final Set<String> VALUE_TYPES = Set.of("fill", "input", "select");

    private ActionIrValidator() { }

    static List<String> validate(JsonNode actions) {
        List<String> errors = new ArrayList<>();
        if (actions == null || !actions.isArray()) {
            errors.add("actions 必须是数组");
            return errors;
        }
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < actions.size(); index++) {
            JsonNode action = actions.get(index);
            String prefix = "actions[" + index + "] ";
            if (action == null || !action.isObject()) {
                errors.add(prefix + "必须是对象");
                continue;
            }
            String id = text(action, "id", "actionId");
            if (id.isBlank()) errors.add(prefix + "id 必填");
            else if (!ids.add(id)) errors.add(prefix + "id 必须唯一: " + id);

            String type = text(action, "type");
            if (type.isBlank() || !SUPPORTED_TYPES.contains(type)) {
                errors.add(prefix + "type 不支持: " + type);
                continue;
            }
            String safety = text(action, "safety").toLowerCase(Locale.ROOT);
            if (safety.isBlank()) errors.add(prefix + "safety 必填");
            else if (!SAFETY.contains(safety)) errors.add(prefix + "safety 不支持: " + safety);
            validateSafety(type, safety, prefix, errors);

            JsonNode target = action.path("target");
            if ("assert".equals(type)) {
                JsonNode assertion = action.path("assertion");
                String assertionType = text(assertion, "type").toLowerCase(Locale.ROOT);
                target = assertion.path("target");
                if (target.isMissingNode()) target = assertion.path("semanticTarget");
                if ("url".equals(assertionType) || "asserturl".equals(assertionType) || "assert_url".equals(assertionType)) requireRouteTarget(target, prefix, errors);
                else requireEvidenceTarget(target, prefix, errors);
            } else if (LOCATOR_TYPES.contains(type.toLowerCase(Locale.ROOT))) {
                requireEvidenceTarget(target, prefix, errors);
            } else if (isRouteType(type)) {
                requireRouteTarget(target, prefix, errors);
            } else if (isAssertionType(type)) {
                requireAssertionTarget(target, prefix, errors);
            } else if ("screenshot".equals(type) && target != null && !target.isMissingNode() && !target.isNull()) {
                errors.add(prefix + "screenshot 不允许 target");
            }
            if ("assert".equals(type)) {
                String assertionType = text(action.path("assertion"), "type").toLowerCase(Locale.ROOT);
                if (!Set.of("visible", "hidden", "text", "url", "enabled", "disabled",
                        "assertvisible", "asserthidden", "asserttext", "asserturl", "assertenabled", "assertdisabled",
                        // snake_case 是本仓库的规范写法（见本文件 ACTION_TYPES），断言类型词表必须同样接受，
                        // 否则 {type:'assert', assertion:{type:'assert_url'}} 会被整条判成「不支持」。
                        "assert_visible", "assert_hidden", "assert_text", "assert_url", "assert_enabled", "assert_disabled").contains(assertionType)) {
                    errors.add(prefix + "assertion.type 不支持: " + assertionType);
                }
            }

            if (VALUE_TYPES.contains(type.toLowerCase(Locale.ROOT))) {
                validateValue(action.path("value"), type, prefix, errors);
            } else if (isTextAssertion(type)) {
                String expected = text(action, "expected");
                if (expected.isBlank()) expected = text(action.path("assertion"), "expected", "value");
                if (expected.isBlank()) errors.add(prefix + "assertText 必须提供 expected");
                if (!text(action, "value").isBlank()) errors.add(prefix + "assertText 必须将 expected 与 target 分离");
            } else if (Set.of("assertvisible", "assert_visible", "asserthidden", "assert_hidden").contains(type.toLowerCase(Locale.ROOT))) {
                if (!text(action, "expected").isBlank() || !text(action.path("assertion"), "expected").isBlank()) {
                    errors.add(prefix + type + " 不允许携带 expected");
                }
            } else if (isAssertionType(type) && !text(action, "expected").isBlank()) {
                errors.add(prefix + type + " 不允许 expected");
            }
        }
        return List.copyOf(errors);
    }

    private static void validateSafety(String type, String safety, String prefix, List<String> errors) {
        if (safety.isBlank()) return;
        String normalized = type.toLowerCase(Locale.ROOT);
        if (Set.of("navigate", "goto", "hover", "waitforvisible", "wait_for_visible", "waitforurl", "wait_for_url",
            "assert", "assertvisible", "assert_visible", "asserthidden", "assert_hidden", "asserttext", "assert_text",
            "asserturl", "assert_url", "assertenabled", "assert_enabled", "assertdisabled", "assert_disabled",
            "scrollintoview", "scroll_into_view", "screenshot").contains(normalized)
            && !"readonly".equals(safety)) {
            errors.add(prefix + type + " 的 safety 必须是 readonly");
        }
    }

    private static void requireEvidenceTarget(JsonNode target, String prefix, List<String> errors) {
        if (target == null || !target.isObject()) {
            errors.add("action_evidence_missing: " + prefix + "target 必须是包含 evidenceId 的对象");
            return;
        }
        // 元素型动作收到 route 目标时必须拦下：runner 解析器对 route 目标返回 undefined，
        // 执行期会在 7 处解引用抛 TypeError（HTTP 503，分类与证据一起丢）。
        if ("route".equals(text(target, "kind").toLowerCase(Locale.ROOT))) {
            errors.add(prefix + "元素型动作不接受 route 目标，必须引用 evidenceId");
            return;
        }
        if (text(target, "evidenceId").isBlank()) errors.add("action_evidence_missing: " + prefix + "target 必须包含 evidenceId");
        if (!text(target, "locator").isBlank() || !text(target, "selector").isBlank()
            || !text(target, "css").isBlank() || !text(target, "xpath").isBlank()) {
            errors.add(prefix + "禁止 AI locator，target 只能引用 evidenceId");
        }
    }

    private static void requireRouteTarget(JsonNode target, String prefix, List<String> errors) {
        if (target == null || target.isMissingNode() || target.isNull()) {
            errors.add(prefix + "navigate/assertUrl 必须提供 target");
            return;
        }
        String route = target.isTextual() ? target.asText("").strip() : text(target, "value", "route");
        if (route.isBlank()) {
            errors.add(prefix + "target 必须包含 route/value");
            return;
        }
        // 只校验「非空」太弱：String(对象) 的产物（'[object Object]'）和 javascript:/data: 这类
        // scheme 都会被原样接受，执行器再把页面导航到垃圾地址。合法的同源相对路径（detail、
        // users/detail、?q=1、#/x）必须放过，而含空白的描述文字（'not a url'）与纯自然语言
        // （'威胁情报'）必须拦下——它们会被百分号编码成同源垃圾地址并执行。
        if (!looksLikeRouteTarget(route)) {
            errors.add(prefix + "target 不是可执行的地址：" + route);
        }
    }

    /**
     * 与 runner 的 isRouteShaped / WebCaseCompilationService.looksLikeRouteTarget 同口径：
     * 接受绝对 http(s) 地址、以 / # ? ./ ../ 开头的相对路由、ASCII 路径片段；
     * 拒绝含空白的描述文字、纯自然语言、字符串化产物与危险 scheme。
     */
    private static boolean looksLikeRouteTarget(String route) {
        String value = route.strip();
        if (value.isEmpty()) return false;
        if (value.chars().anyMatch(Character::isWhitespace)) return false;
        if (value.contains("[object")) return false;
        if (value.matches("(?i)^(javascript|data|vbscript|file|blob):.*")) return false;
        if (value.matches("(?i)^(https?://|[/#?]).*")) return true;
        if (value.matches("^\\.\\.?/.*")) return true;
        return value.matches("^[A-Za-z0-9][A-Za-z0-9._~%/-]*$");
    }

    private static void requireAssertionTarget(JsonNode target, String prefix, List<String> errors) {
        requireEvidenceTarget(target, prefix, errors);
    }

    private static void validateValue(JsonNode value, String type, String prefix, List<String> errors) {
        if (value == null || !value.isObject()) {
            errors.add(prefix + type + " 必须提供结构化 value source");
            return;
        }
        String kind = text(value, "kind").toLowerCase(Locale.ROOT);
        String key = text(value, "key", "value");
        // 词表与 runner 的解析能力一一对应：literal/testdata 是内联值；runtimevariable/maskedsecret
        // 需要运行时来源（run/discovery 请求里的 variables/maskedSecrets），runner 拿不到来源时会以
        // 分类错误指名到变量（invalid_action_ir），不会把 key 当值静默填入。
        if (!Set.of("literal", "testdata", "runtimevariable", "runtime_variable", "maskedsecret").contains(kind)) {
            errors.add(prefix + type + " value.kind 不支持: " + kind);
        }
        if (key.isBlank()) errors.add(prefix + type + " value 必须包含 key/value");
        // testdata 是内联值：只给 key 时 runner 会 fail-closed（键名不能当数据填），计划期就拦下更早。
        if ("testdata".equals(kind) && text(value, "value").isBlank()) {
            errors.add(prefix + type + " testdata 必须提供内联 value（只给 key 会把键名当值填入）");
        }
    }

    private static boolean isRouteType(String type) {
        return Set.of("navigate", "goto", "asserturl", "assert_url", "waitforurl", "wait_for_url").contains(type.toLowerCase(Locale.ROOT));
    }

    private static boolean isAssertionType(String type) {
        return Set.of("assertvisible", "assert_visible", "asserthidden", "assert_hidden", "asserttext", "assert_text",
            "asserturl", "assert_url", "assertenabled", "assert_enabled", "assertdisabled", "assert_disabled",
            "assert").contains(type.toLowerCase(Locale.ROOT));
    }

    private static boolean isTextAssertion(String type) {
        return Set.of("asserttext", "assert_text").contains(type.toLowerCase(Locale.ROOT));
    }

    private static String text(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        for (String field : fields) {
            String value = node.path(field).asText("").strip();
            if (!value.isBlank()) return value;
        }
        return "";
    }
}
