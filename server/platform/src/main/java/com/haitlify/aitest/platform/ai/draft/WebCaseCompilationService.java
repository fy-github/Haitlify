package com.haitlify.aitest.platform.ai.draft;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
class WebCaseCompilationService {

    private static final Set<String> CONTENT_KEYWORDS = Set.of(
        "tab", "标签", "页签", "展示", "显示", "可见", "列表", "字段", "按钮", "入口", "菜单",
        "筛选", "搜索", "导出", "状态", "文案", "标题", "提示", "详情", "操作列", "状态列", "顺序"
    );
    private static final Set<String> ACTION_KEYWORDS = Set.of(
        "进入", "打开", "访问", "跳转", "查看", "点击", "单击", "选择", "切换", "输入", "填写", "搜索",
        "查询", "筛选", "滚动", "翻页", "分页", "提交", "确认", "保存"
    );
    private static final Set<String> ASSERTION_KEYWORDS = Set.of(
        "可见", "显示", "展示", "存在", "出现", "隐藏", "消失", "文本", "文案", "提示", "标题"
    );
    private static final Pattern QUOTED_COPY = Pattern.compile("[\"'“”‘’]([^\"'“”‘’]{2,80})[\"'“”‘’]");
    /** 没有元素目标、只表达「页面/地址状态」的意图：它们的 targetTerm 是 URL 或状态描述，不是 evidenceId。 */
    private static final Set<String> ROUTE_ONLY_INTENTS = Set.of("navigate_page", "assert_url", "wait_for_url", "screenshot");

    private final ObjectMapper objectMapper;
    private final WebAutomationCaseValidator validator;

    WebCaseCompilationService(ObjectMapper objectMapper, WebAutomationCaseValidator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    List<ActionIntentIR> buildActionIntentIR(AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch naturalPlan) {
        List<ActionIntentIR> intents = new ArrayList<>();
        if (naturalPlan == null || naturalPlan.plans().isEmpty()) return List.of();
        for (AgentWebCaseGenerationFacade.NaturalWebCasePlan plan : naturalPlan.plans()) {
            if (plan.actions() != null && plan.actions().isArray()) {
                List<String> actionErrors = ActionIrValidator.validate(plan.actions());
                if (!actionErrors.isEmpty()) {
                    intents.add(new ActionIntentIR(plan.sourcePointId(), -1, "", "invalid_action_ir", "", "", String.join(";", actionErrors), "", ""));
                } else {
                    intents.addAll(buildStructuredActionIntents(plan, plan.actions()));
                }
                continue;
            }
            // 阶段 4：编译器不再根据自然语言步骤猜测动作 —— actions 缺失（或不是数组）按无效 Action IR fail-closed。
            intents.add(new ActionIntentIR(plan.sourcePointId(), -1, "", "invalid_action_ir", "", "",
                "缺少结构化 actions：自动化编译只接受 Action IR，不再根据自然语言步骤推测", "", ""));
        }
        return List.copyOf(intents);
    }

    private List<ActionIntentIR> buildStructuredActionIntents(
        AgentWebCaseGenerationFacade.NaturalWebCasePlan plan,
        JsonNode actions
    ) {
        List<ActionIntentIR> intents = new ArrayList<>();
        if (!ActionIrValidator.validate(actions).isEmpty()) return List.of();
        for (int index = 0; index < actions.size(); index++) {
            JsonNode action = actions.get(index);
            String actionId = firstText(action, "id", "actionId");
            String type = structuredType(action);
            String safety = firstText(action, "safety").toLowerCase(Locale.ROOT);
            JsonNode targetNode = action.path("target");
            // 别名形状（{type:'assert', assertion:{type:'asserturl'|'assert_url', target:{…}}}）的目标在 assertion 里。
            // 普通形状（{type:'assertUrl', target:{…}}）的 type 也是 "asserturl"，但目标仍在顶层：
            // 只按 type 判断会把普通形状读空（targetTerm='' 后编译期守卫会误判成不可解析目标）。
            if ("assert".equals(type) || targetNode.isMissingNode()) {
                JsonNode assertionTarget = action.path("assertion").path("target");
                if (assertionTarget.isMissingNode()) assertionTarget = action.path("assertion").path("semanticTarget");
                if (!assertionTarget.isMissingNode()) targetNode = assertionTarget;
            }
            String targetTerm = structuredTargetTerm(targetNode);
            String expectedState = firstText(action, "expected");
            if (expectedState.isBlank()) expectedState = firstText(action.path("assertion"), "expected", "value");
            String valueSource = structuredValueSource(action.path("value"));
            String intent = switch (type) {
                case "navigate", "goto" -> "navigate_page";
                case "waitforurl", "wait_for_url" -> "wait_for_url";
                case "click" -> Set.of("mutating", "dangerous").contains(safety) ? "unsafe_action" : "click_readonly";
                case "fill", "input" -> "input_non_secret";
                case "select" -> "select_readonly";
                case "hover" -> "wait_ready";
                case "waitforvisible", "wait_for_visible" -> "wait_ready";
                case "assertvisible", "assert_visible" -> "assert_visible";
                case "asserthidden", "assert_hidden" -> "assert_hidden";
                case "asserttext", "assert_text" -> "assert_text";
                case "asserturl", "assert_url" -> "assert_url";
                case "scrollintoview", "scroll_into_view" -> "scroll_to_target";
                case "assertenabled", "assert_enabled" -> "assert_enabled";
                case "assertdisabled", "assert_disabled" -> "assert_disabled";
                case "paginate" -> "paginate_readonly";
                case "screenshot" -> "screenshot";
                default -> "unsupported_action";
            };
            if ("unsafe_action".equals(intent) || "unsupported_action".equals(intent)) {
                expectedState = type + (safety.isBlank() ? "" : ":" + safety);
            }
            intents.add(new ActionIntentIR(
                plan.sourcePointId(), index, actionId, intent, targetTerm, plan.pageName(), expectedState,
                intent.startsWith("assert_") ? intent.substring("assert_".length()) : "", valueSource, true
            ));
        }
        return intents;
    }

    private String structuredTargetTerm(JsonNode target) {
        if (target == null || target.isMissingNode() || target.isNull()) return "";
        if (target.isTextual()) return target.asText("").strip();
        return firstText(target, "evidenceId", "value", "url");
    }

    private String structuredType(JsonNode action) {
        String type = firstText(action, "type").toLowerCase(Locale.ROOT);
        if (!"assert".equals(type)) return type;
        String assertionType = firstText(action.path("assertion"), "type").toLowerCase(Locale.ROOT);
        return switch (assertionType) {
            case "visible", "assertvisible", "assert_visible" -> "assertvisible";
            case "hidden", "asserthidden", "assert_hidden" -> "asserthidden";
            case "text", "asserttext", "assert_text" -> "asserttext";
            case "enabled", "assertenabled", "assert_enabled" -> "assertenabled";
            case "disabled", "assertdisabled", "assert_disabled" -> "assertdisabled";
            case "url", "asserturl", "assert_url" -> "asserturl";
            default -> "assert";
        };
    }
    private String structuredValueSource(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        if (value.isTextual()) return value.asText("").strip();
        String kind = firstText(value, "kind").toLowerCase(Locale.ROOT);
        String reference = firstText(value, "value", "key");
        if (reference.isBlank()) return "";
        return switch (kind) {
            // testdata 是内联值：只给 key（指向外部数据集）时不能把键名编成字面量，
            // 否则页面收到的是键名而不是数据，用例却记通过（与 runner 的 fail-closed 同口径）。
            case "literal" -> reference;
            case "testdata" -> firstText(value, "value").isBlank() ? "" : firstText(value, "value");
            case "runtimevariable", "runtime_variable", "maskedsecret" -> "{{" + reference + "}}";
            default -> "";
        };
    }

    CompilationResult compileEvidenceBoundCase(AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch naturalPlan, JsonNode browserEvidence) {
        return compileEvidenceBoundCase(naturalPlan, browserEvidence, Map.of());
    }

    CompilationResult compileEvidenceBoundCase(
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch naturalPlan,
        JsonNode browserEvidence,
        List<Map<String, Object>> sourcePoints
    ) {
        Map<String, Map<String, Object>> sourceByPointId = sourcePoints == null ? Map.of() : sourcePoints.stream()
            .filter(row -> row.get("POINT_ID") != null)
            .collect(java.util.stream.Collectors.toMap(
                row -> String.valueOf(row.get("POINT_ID")),
                row -> row,
                (left, right) -> left,
                LinkedHashMap::new
            ));
        return compileEvidenceBoundCase(naturalPlan, browserEvidence, sourceByPointId);
    }

    CompilationResult compileEvidenceBoundCase(
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch naturalPlan,
        JsonNode browserEvidence,
        Map<String, Map<String, Object>> sourceByPointId
    ) {
        List<String> errors = new ArrayList<>();
        List<AgentWebCaseGenerationFacade.GeneratedWebCase> compiled = new ArrayList<>();
        List<ActionIntentIR> actionIntents = buildActionIntentIR(naturalPlan);
        Map<String, List<PageEvidenceBundle>> evidenceByPoint = indexEvidence(browserEvidence);
        Map<String, Map<String, List<StepEvidence>>> stepEvidenceByPoint = indexStepEvidence(browserEvidence);
        Map<String, List<ActionIntentIR>> intentsByPoint = actionIntents.stream()
            .collect(java.util.stream.Collectors.groupingBy(ActionIntentIR::sourcePointId, LinkedHashMap::new, java.util.stream.Collectors.toList()));

        if (naturalPlan != null) {
            for (AgentWebCaseGenerationFacade.NaturalWebCasePlan plan : naturalPlan.plans()) {
                List<PageEvidenceBundle> pageEvidence = evidenceByPoint.getOrDefault(plan.sourcePointId(), List.of());
                boolean structuredPlan = plan.actions() != null && plan.actions().isArray();
                if (pageEvidence.isEmpty()) {
                    if (structuredPlan
                        && !stepEvidenceByPoint.containsKey(plan.sourcePointId())) {
                        errors.add("step_evidence_missing: 当前测试点缺少步骤证据：" + plan.sourcePointId());
                    } else {
                        errors.add("step_evidence_missing: 当前测试点缺少页面证据：" + plan.sourcePointId());
                    }
                    continue;
                }
                EvidenceSelection selection = selectEvidenceBundle(plan, pageEvidence, structuredPlan);
                if (!selection.errors().isEmpty()) {
                    errors.addAll(selection.errors());
                    continue;
                }
                PageEvidenceBundle selected = selection.bundle();
                if (selected == null) {
                    errors.add("当前测试点页面证据不唯一或页面标识不匹配：" + plan.sourcePointId());
                    continue;
                }
                // 起始地址回退必须取「计划里第一个动作 before 所在的页面」，不能取被选中的目标页 bundle：
                // startUrl 为空时 auto-goto 会把最终页当起点（复核方实测：goto /detail 后再点只存在于 /list 的菜单，
                // 编译 success=true 但产物不可执行）。
                String fallbackStartPageUrl = resolveFallbackStartPageUrl(plan, stepEvidenceByPoint.getOrDefault(plan.sourcePointId(), Map.of()), selected);
                String executionStartUrl = resolveExecutionStartUrl(plan.startUrl(), fallbackStartPageUrl);
                List<ActionIntentIR> pointIntents = intentsByPoint.getOrDefault(plan.sourcePointId(), List.of());
                if (pointIntents.stream().anyMatch(intent -> "invalid_action_ir".equals(intent.intent()))) {
                    errors.add("测试点 " + plan.sourcePointId() + " Action IR 无效：" + pointIntents.stream().filter(intent -> "invalid_action_ir".equals(intent.intent())).findFirst().map(ActionIntentIR::expectedState).orElse("unknown"));
                    continue;
                }
                List<String> stepLines = new ArrayList<>();
                List<String> assertionLines = new ArrayList<>();
                List<AgentWebCaseGenerationFacade.StepTrace> stepTraces = new ArrayList<>();
                Set<String> structuredEvidenceLocators = new LinkedHashSet<>();
                boolean sawNavigation = false;
                for (ActionIntentIR intent : pointIntents) {
                    StepEvidence stepEvidence = structuredPlan
                        ? findStepEvidence(stepEvidenceByPoint, plan.sourcePointId(), intent.actionId())
                        : null;
                    if (structuredPlan && stepEvidence == null) {
                        errors.add("step_evidence_missing: 测试点 " + plan.sourcePointId()
                            + " 的步骤 " + (intent.stepIndex() + 1) + " actionId " + intent.actionId() + " 缺少 step evidence");
                        continue;
                    }
                    EvidenceResolution evidenceResolution = structuredPlan
                        ? resolveStructuredEvidence(intent, plan, pageEvidence, stepEvidence)
                        : new EvidenceResolution(resolveLocator(intent, plan, selected), "");
                    if (!evidenceResolution.error().isBlank()) {
                        errors.add(evidenceResolution.error());
                        continue;
                    }
                    EvidenceLocator resolvedLocator = evidenceResolution.locator();
                    if (structuredPlan && resolvedLocator != null && !resolvedLocator.locator().isBlank()) {
                        structuredEvidenceLocators.add(resolvedLocator.locator());
                    }
                    PageEvidenceBundle actionPage = structuredPlan && evidenceResolution.pageEvidence() != null
                        ? evidenceResolution.pageEvidence() : selected;
                    List<String> compiledLines = compileIntent(intent, plan, actionPage, executionStartUrl, resolvedLocator);
                    if (compiledLines.isEmpty()) {
                        errors.add((intent.structured() ? "action_evidence_mismatch: " : "step_evidence_missing: ")
                            + "测试点 " + plan.sourcePointId() + " 的步骤 " + (intent.stepIndex() + 1) + " 未能编译成 DSL"
                            + "（intent=" + intent.intent() + ", targetTerm='" + blankToDefault(intent.targetTerm(), "") + "'）");
                        continue;
                    }
                    sawNavigation = sawNavigation || compiledLines.stream().anyMatch(line -> line.startsWith("goto "));
                    stepLines.addAll(compiledLines);
                    EvidenceLocator traceLocator = resolvedLocator;
                    String stepEvidenceId = structuredPlan && stepEvidence != null
                        ? blankToDefault(stepEvidence.evidenceId(), firstText(stepEvidence.resolvedTarget(), "evidenceId")) : "";
                    List<String> traceEvidenceIds = !stepEvidenceId.isBlank()
                        ? List.of(stepEvidenceId)
                        : traceLocator == null || traceLocator.id().isBlank() ? List.of() : List.of(traceLocator.id());
                    stepTraces.add(new AgentWebCaseGenerationFacade.StepTrace(
                        intent.stepIndex(),
                        intent.actionId(),
                        plan.naturalSteps().size() > intent.stepIndex() ? plan.naturalSteps().get(intent.stepIndex()) : "",
                        intent.intent(),
                        compiledLines,
                        traceEvidenceIds,
                        structuredPlan && resolvedLocator != null && !resolvedLocator.pageFingerprint().isBlank()
                            ? resolvedLocator.pageFingerprint() : selected.pageFingerprint(),
                        plan.sourcePointId()
                    ));
                    for (String line : compiledLines) {
                        if (line.startsWith("assertVisible ") || line.startsWith("assertHidden ") || line.startsWith("assertText ") || line.startsWith("assertUrl ") || line.startsWith("assertEnabled ") || line.startsWith("assertDisabled ")) {
                            assertionLines.add(line);
                        }
                    }
                }
                if (!sawNavigation && !executionStartUrl.isBlank()) {
                    stepLines.add(0, "goto " + executionStartUrl);
                }

                if (stepLines.isEmpty()) {
                    errors.add("测试点 " + plan.sourcePointId() + " 没有可执行步骤");
                    continue;
                }

                ExpectedAssertionBinding expectedBinding = structuredPlan
                    ? new ExpectedAssertionBinding("", "")
                    : buildExpectedAssertionBinding(plan, selected, executionStartUrl);
                if (expectedBinding.line().isBlank()) {
                    if (!structuredPlan) {
                        errors.add("step_evidence_missing: 测试点 " + plan.sourcePointId() + " 的 naturalExpectedResult 未能绑定到业务元素断言");
                        continue;
                    }
                }
                if (!expectedBinding.line().isBlank() && !assertionLines.contains(expectedBinding.line())) {
                    stepLines.add(expectedBinding.line());
                    assertionLines.add(expectedBinding.line());
                }

                ObjectNode caseNode = objectMapper.createObjectNode();
                caseNode.put("sourcePointId", plan.sourcePointId());
                caseNode.put("title", plan.title());
                caseNode.put("module", plan.module());
                caseNode.put("caseKind", "web_automation");
                caseNode.put("pageName", plan.pageName());
                caseNode.put("startUrl", executionStartUrl);
                caseNode.put("browser", blankToDefault(plan.browser(), "chromium"));
                caseNode.put("viewport", blankToDefault(plan.viewport(), "1440x900"));
                caseNode.put("environmentId", plan.environmentId());
                caseNode.put("baseUrl", plan.baseUrl());
                caseNode.put("authenticationMode", plan.authenticationMode());
                caseNode.put("precondition", "");
                caseNode.put("objective", plan.objective());
                caseNode.put("naturalSteps", String.join("\n", plan.naturalSteps()));
                caseNode.put("naturalExpectedResult", plan.naturalExpectedResult());
                caseNode.put("steps", String.join("\n", stepLines));
                caseNode.put("assertions", String.join("\n", assertionLines));
                caseNode.put("expectedResult", structuredPlan
                    ? blankToDefault(plan.naturalExpectedResult(), "")
                    : blankToDefault(plan.naturalExpectedResult(), selected.primaryLabel()));
                caseNode.put("pageFingerprint", selected.pageFingerprint());
                caseNode.put("sourceFile", blankToDefault(textValue(sourceByPointId.get(plan.sourcePointId()), "SOURCE_FILE"), ""));
                caseNode.put("sourceText", blankToDefault(textValue(sourceByPointId.get(plan.sourcePointId()), "SOURCE_TEXT"), plan.objective()));
                caseNode.put("pageKey", selected.pageKey());
                caseNode.set("stepTraces", objectMapper.valueToTree(stepTraces));
                caseNode.set("expectedResultEvidenceIds", objectMapper.valueToTree(
                    expectedBinding.evidenceId().isBlank() ? List.of() : List.of(expectedBinding.evidenceId())
                ));

                if (structuredPlan && caseNode.path("sourceFile").asText("").isBlank()) caseNode.put("sourceFile", "structured-action-ir");
                WebAutomationValidationResult result = validator.validateAndNormalize(caseNode, compiled.size() + 1);
                errors.addAll(result.errors());
                if (!result.errors().isEmpty()) continue;

                ObjectNode normalized = result.normalizedCase();
                List<String> pageScopedErrors = structuredPlan
                    ? validateStructuredStepEvidence(normalized, stepEvidenceByPoint.getOrDefault(plan.sourcePointId(), Map.of()), pointIntents)
                    : validatePageScopedEvidence(normalized, selected, selected.evidenceByLocator(), false, structuredEvidenceLocators);
                if (!pageScopedErrors.isEmpty()) {
                    errors.addAll(pageScopedErrors);
                    continue;
                }
                compiled.add(new AgentWebCaseGenerationFacade.GeneratedWebCase(
                    normalized.path("sourcePointId").asText(""),
                    normalized.path("title").asText(""),
                    normalized.path("module").asText(""),
                    normalized.path("caseKind").asText(""),
                    normalized.path("pageName").asText(""),
                    normalized.path("startUrl").asText(""),
                    normalized.path("browser").asText(""),
                    normalized.path("viewport").asText(""),
                    normalized.path("precondition").asText(""),
                    normalized.path("objective").asText(""),
                    normalized.path("steps").asText(""),
                    normalized.path("assertions").asText(""),
                    normalized.path("expectedResult").asText(""),
                    normalized.path("naturalSteps").asText(""),
                    normalized.path("naturalExpectedResult").asText(""),
                    normalized.path("sourceFile").asText(""),
                    normalized.path("pageFingerprint").asText(""),
                    normalized.path("pageKey").asText(""),
                    stepTraces,
                    expectedBinding.evidenceId().isBlank() ? List.of() : List.of(expectedBinding.evidenceId()),
                    plan.actions(), plan.environmentId(), plan.baseUrl(), plan.authenticationMode()
                ));
            }
        }

        errors.addAll(validateCaseCoverage(compiled, naturalPlan == null ? List.of() : naturalPlan.plans().stream().map(AgentWebCaseGenerationFacade.NaturalWebCasePlan::sourcePointId).toList()));
        return new CompilationResult(List.copyOf(compiled), List.copyOf(errors), List.copyOf(actionIntents));
    }

    List<String> validateCaseCoverage(List<AgentWebCaseGenerationFacade.GeneratedWebCase> cases, List<String> confirmedPointIds) {
        Set<String> requested = confirmedPointIds == null ? Set.of() : new LinkedHashSet<>(confirmedPointIds);
        Set<String> seen = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        for (AgentWebCaseGenerationFacade.GeneratedWebCase item : cases == null ? List.<AgentWebCaseGenerationFacade.GeneratedWebCase>of() : cases) {
            String pointId = blankToDefault(item.sourcePointId(), "");
            if (pointId.isBlank()) {
                errors.add("编译结果缺少 sourcePointId");
                continue;
            }
            if (!requested.isEmpty() && !requested.contains(pointId)) {
                errors.add("用例引用了当前批次之外的 sourcePointId：" + pointId);
                continue;
            }
            if (!seen.add(pointId)) {
                errors.add("当前批次存在重复的 sourcePointId：" + pointId);
            }
        }
        for (String pointId : requested) {
            if (!seen.contains(pointId)) {
                errors.add("当前批次测试点未生成用例：" + pointId);
            }
        }
        return errors;
    }

    List<String> validatePageScopedEvidence(
        AgentWebCaseGenerationFacade.GeneratedWebCase generatedCase,
        Map<String, PageEvidenceBundle> evidenceByPage
    ) {
        if (generatedCase == null || evidenceByPage == null) return List.of("step_evidence_missing: 缺少页面证据或用例");
        PageEvidenceBundle evidence = evidenceByPage.get(generatedCase.sourcePointId());
        return validatePageScopedEvidence(generatedCase, evidence, evidence == null ? null : evidence.evidenceByLocator());
    }

    List<String> validatePageScopedEvidence(
        AgentWebCaseGenerationFacade.GeneratedWebCase generatedCase,
        PageEvidenceBundle evidence,
        Map<String, EvidenceLocator> evidenceByLocator
    ) {
        if (generatedCase == null || evidence == null) return List.of("缺少页面证据或用例");
        ObjectNode testCase = objectMapper.createObjectNode();
        testCase.put("caseKind", generatedCase.caseKind());
        testCase.put("sourcePointId", generatedCase.sourcePointId());
        testCase.put("title", generatedCase.title());
        testCase.put("module", generatedCase.module());
        testCase.put("pageName", generatedCase.pageName());
        testCase.put("startUrl", generatedCase.startUrl());
        testCase.put("browser", generatedCase.browser());
        testCase.put("viewport", generatedCase.viewport());
        testCase.put("objective", generatedCase.objective());
        testCase.put("naturalSteps", generatedCase.naturalSteps());
        testCase.put("naturalExpectedResult", generatedCase.naturalExpectedResult());
        testCase.put("steps", generatedCase.steps());
        testCase.put("assertions", generatedCase.assertions());
        testCase.put("expectedResult", generatedCase.expectedResult());
        testCase.put("pageFingerprint", generatedCase.pageFingerprint());
        return validatePageScopedEvidence(testCase, evidence, evidenceByLocator);
    }

    List<String> validatePageScopedEvidence(
        ObjectNode generatedCase,
        PageEvidenceBundle evidence,
        Map<String, EvidenceLocator> evidenceByLocator
    ) {
        return validatePageScopedEvidence(generatedCase, evidence, evidenceByLocator, false, Set.of());
    }

    private List<String> validatePageScopedEvidence(
        ObjectNode generatedCase,
        PageEvidenceBundle evidence,
        Map<String, EvidenceLocator> evidenceByLocator,
        boolean structured,
        Set<String> structuredEvidenceLocators
    ) {
        List<String> errors = new ArrayList<>();
        if (generatedCase == null || evidence == null) {
            errors.add("step_evidence_missing: 缺少页面证据或用例");
            return errors;
        }
        String caseLabel = "用例 " + blankToDefault(generatedCase.path("sourcePointId").asText(""), "未知") + "：";
        String generatedFingerprint = generatedCase.path("pageFingerprint").asText("").strip();
        if (evidence.pageUrl().isBlank()) {
            errors.add(caseLabel + "页面证据缺少 pageUrl");
        }
        if (evidence.pageKey().isBlank()) {
            errors.add(caseLabel + "页面证据缺少 pageKey");
        }
        if (evidence.pageFingerprint().isBlank()) {
            errors.add(caseLabel + "页面证据缺少 pageFingerprint");
        }
        if (generatedFingerprint.isBlank()) {
            errors.add(caseLabel + "缺少 pageFingerprint");
        } else if (!samePageFingerprint(generatedFingerprint, evidence.pageFingerprint())) {
            errors.add(caseLabel + "pageFingerprint 与页面证据不一致");
        }
        String evidencePageKey = normalizePageKey(evidence.pageUrl());
        if (!evidence.pageUrl().isBlank() && evidencePageKey.isBlank()) {
            errors.add(caseLabel + "pageUrl 无法规范化为页面标识");
        }
        if (!evidence.pageKey().isBlank() && !evidencePageKey.isBlank() && !samePageKey(evidence.pageKey(), evidence.pageUrl())) {
            errors.add(caseLabel + "pageKey 与 pageUrl 不一致");
        }
        String generatedStartUrl = generatedCase.path("startUrl").asText("");
        String expectedPageKey = resolvePageKeyAgainstPageUrl(evidence.pageUrl(), generatedStartUrl);
        if (!generatedStartUrl.isBlank() && expectedPageKey.isBlank()) {
            errors.add(caseLabel + "startUrl 无法绑定到当前页面证据");
        }
        if (!generatedStartUrl.isBlank()
            && !samePageKey(expectedPageKey, evidence.pageKey())) {
            errors.add(caseLabel + "startUrl 与页面证据 pageKey 不一致");
        }
        if (!generatedStartUrl.isBlank()
            && !generatedCase.path("steps").asText("").contains(generatedStartUrl.trim())
            && !generatedCase.path("steps").asText("").contains(evidence.pageUrl())) {
            errors.add(caseLabel + "startUrl 未绑定到当前页面证据");
        }
        for (String rawLine : generatedCase.path("steps").asText("").split("\\R")) {
            String line = rawLine.strip();
            if (line.isBlank()) continue;
            String action = firstToken(line);
            String target = actionTarget(line);
            if (!isSupportedAction(action)) {
                errors.add(caseLabel + "步骤动作不支持: " + action);
                continue;
            }
            if (action.equals("assertUrl")) {
                if (!isRouteIntent(generatedCase) && !sameRoute(target, generatedCase.path("startUrl").asText(""))) {
                    errors.add(caseLabel + "内容型目标不允许使用非路由型 assertUrl");
                }
                continue;
            }
            if (action.equals("goto") || action.equals("waitForUrl")) {
                if (!sameRoute(target, generatedCase.path("startUrl").asText("")) && !sameRoute(target, evidence.pageUrl())) {
                    errors.add(caseLabel + "步骤 " + action + " 未绑定到当前页面证据");
                }
                continue;
            }
            if (action.equals("input") || action.equals("select") || action.equals("assertText")) {
                String[] parts = target.split("\\s+=\\s+", 2);
                target = parts.length == 0 ? target : parts[0].strip();
            }
            EvidenceLocator locator = structured && structuredEvidenceLocators.contains(target)
                ? new EvidenceLocator(target, target, "", evidence.pageUrl(), evidence.pageFingerprint(), evidence.sourcePointId())
                : evidenceByLocator == null ? null : evidenceByLocator.get(target);
            if (locator == null) {
                errors.add(caseLabel + "step_evidence_missing: locator 未由当前页面证据验证: " + target);
            }
        }
        if (generatedCase.path("assertions").asText("").isBlank()) {
            errors.add(caseLabel + "step_evidence_missing: 缺少业务断言");
        }
        if (generatedCase.path("expectedResult").asText("").isBlank()) {
            errors.add(caseLabel + "step_evidence_missing: 缺少预期结果");
        }
        return errors;
    }

    private List<String> validateStructuredStepEvidence(
        ObjectNode generatedCase,
        Map<String, List<StepEvidence>> evidenceByAction,
        List<ActionIntentIR> intents
    ) {
        List<String> errors = new ArrayList<>();
        for (ActionIntentIR intent : intents) {
            List<StepEvidence> matches = evidenceByAction.getOrDefault(intent.actionId(), List.of());
            if (matches.size() != 1) {
                errors.add("step_evidence_missing: actionId 未绑定唯一步骤证据：" + intent.actionId());
                continue;
            }
            StepEvidence evidence = matches.get(0);
            if (evidence.resolvedTarget() == null && !Set.of("navigate_page", "wait_for_url", "assert_url", "screenshot").contains(intent.intent())) {
                errors.add("action_evidence_missing: actionId 缺少 resolvedTarget：" + intent.actionId());
            }
        }
        return errors;
    }

    private List<String> compileIntent(ActionIntentIR intent, AgentWebCaseGenerationFacade.NaturalWebCasePlan plan, PageEvidenceBundle pageEvidence, String executionStartUrl) {
        return compileIntent(intent, plan, pageEvidence, executionStartUrl, resolveLocator(intent, plan, pageEvidence));
    }

    private List<String> compileIntent(ActionIntentIR intent, AgentWebCaseGenerationFacade.NaturalWebCasePlan plan, PageEvidenceBundle pageEvidence, String executionStartUrl, EvidenceLocator resolvedLocator) {
        List<String> lines = new ArrayList<>();
        if ("unsafe_action".equals(intent.intent())) return List.of();
        if ("unsupported_action".equals(intent.intent())) return List.of();
        if ("input_non_secret".equals(intent.intent()) && intent.valueSource().isBlank()) {
            return List.of();
        }
        if ("select_readonly".equals(intent.intent()) && intent.valueSource().isBlank()) {
            return List.of();
        }
        EvidenceLocator locator = resolvedLocator;
        if (locator == null && !Set.of("navigate_page", "wait_for_url", "assert_url", "screenshot").contains(intent.intent())) return List.of();
        switch (intent.intent()) {
            case "navigate_page" -> {
                // 导航动作必须用它自己的 URL 目标：只回退到执行起点会把「跳到详情页」静默编译成「打开列表页」。
                // 结构化动作（来自 Action IR）尤其不能回退：目标不可解析时必须 fail-closed，
                // 否则「断言/导航到详情页」会被编译成「打开列表页」且不留任何提示。
                String navigationTarget = routeTargetTerm(intent.targetTerm());
                if (navigationTarget.isBlank() && intent.structured()) return List.of();
                String gotoUrl = navigationTarget.isBlank() ? executionStartUrl : navigationTarget;
                if (!gotoUrl.isBlank()) {
                    lines.add("goto " + gotoUrl);
                }
                if (locator != null) {
                    lines.add("waitForVisible " + locator.locator());
                }
            }
            case "wait_for_url" -> {
                String expectedUrl = routeTargetTerm(intent.targetTerm());
                if (expectedUrl.isBlank() && intent.structured()) return List.of();
                lines.add("waitForUrl " + (expectedUrl.isBlank() ? executionStartUrl : expectedUrl));
            }
            case "wait_ready" -> {
                if (locator == null) return List.of();
                lines.add("waitForVisible " + locator.locator());
            }
            case "click_readonly" -> lines.add("click " + locator.locator());
            case "input_non_secret" -> lines.add("input " + locator.locator() + " = " + intent.valueSource());
            case "select_readonly" -> lines.add("select " + locator.locator() + " = " + intent.valueSource());
            case "assert_visible" -> lines.add("assertVisible " + locator.locator());
            case "assert_hidden" -> lines.add("assertHidden " + locator.locator());
            case "assert_text" -> lines.add("assertText " + locator.locator() + " = " + blankToDefault(intent.expectedState(), locator.label()));
            case "scroll_to_target" -> lines.add("scrollIntoView " + locator.locator());
            case "assert_enabled" -> lines.add("assertEnabled " + locator.locator());
            case "assert_disabled" -> lines.add("assertDisabled " + locator.locator());
            case "paginate_readonly" -> lines.add("paginate " + locator.locator());
            case "screenshot" -> lines.add("screenshot");
            case "assert_url" -> {
                // 断言地址必须用该动作自己的目标：回退执行起点会把「断言已到详情页」静默编译成「断言还在列表页」。
                // 结构化动作的目标不可解析时同样 fail-closed，不能静默替换成起点地址。
                String expectedUrl = routeTargetTerm(intent.targetTerm());
                if (expectedUrl.isBlank() && intent.structured()) return List.of();
                lines.add("assertUrl " + (expectedUrl.isBlank()
                    ? blankToDefault(executionStartUrl, pageEvidence.pageUrl())
                    : expectedUrl));
            }
            default -> {
                return List.of();
            }
        }
        if (lines.isEmpty()) return List.of();
        return List.copyOf(lines);
    }

    private EvidenceLocator resolveLocator(ActionIntentIR intent, AgentWebCaseGenerationFacade.NaturalWebCasePlan plan, PageEvidenceBundle pageEvidence) {
        if (pageEvidence == null) return null;
        String target = blankToDefault(intent.targetTerm(), "");
        EvidenceLocator byId = pageEvidence.findById(target);
        if (byId != null) return byId;
        if (intent.structured()) return null;
        EvidenceLocator byLocator = pageEvidence.findByLocator(target);
        if (byLocator != null) return byLocator;
        EvidenceLocator byLabel = pageEvidence.findByExactLabel(target);
        if (byLabel != null) return byLabel;
        return null;
    }

    private EvidenceResolution resolveStructuredEvidence(
        ActionIntentIR intent,
        AgentWebCaseGenerationFacade.NaturalWebCasePlan plan,
        List<PageEvidenceBundle> pageEvidence,
        StepEvidence stepEvidence
    ) {
        String prefix = "action_evidence_missing: 测试点 " + plan.sourcePointId()
            + " 的步骤 " + (intent.stepIndex() + 1) + " actionId " + intent.actionId() + " ";
        if (stepEvidence == null) return new EvidenceResolution(null, "step_evidence_missing: " + prefix.substring("action_evidence_missing: ".length()) + "缺少 step evidence");
        if (!sameActionType(intent, stepEvidence.actionType())) {
            return new EvidenceResolution(null, "action_evidence_mismatch: " + prefix.substring("action_evidence_missing: ".length())
                + "actionType 与 Action IR 不一致");
        }
        if (!stepEvidence.sourcePointId().isBlank() && !stepEvidence.sourcePointId().equals(plan.sourcePointId())) {
            return new EvidenceResolution(null, "action_evidence_mismatch: " + prefix.substring("action_evidence_missing: ".length())
                + "sourcePointId 与测试点不一致");
        }
        PageEvidenceBundle actionPage = findMatchingStepPage(stepEvidence, pageEvidence);
        if (actionPage == null) {
            return new EvidenceResolution(null, "action_evidence_mismatch: " + prefix.substring("action_evidence_missing: ".length())
                + "pageKey 或 pageFingerprint 与页面证据不一致");
        }
        String expectedEvidenceId = intent.targetTerm();
        String resolvedEvidenceId = stepEvidence.evidenceId().isBlank()
            ? firstText(stepEvidence.resolvedTarget(), "evidenceId") : stepEvidence.evidenceId();
        // 路由型动作（navigate/assertUrl/waitForUrl/screenshot）没有元素目标：targetTerm 是 URL 而不是
        // evidenceId，拿它做一致性比对会把正确的导航证据判成 mismatch。
        if (!ROUTE_ONLY_INTENTS.contains(intent.intent())
            && !expectedEvidenceId.isBlank() && !expectedEvidenceId.equals(resolvedEvidenceId)) {
            return new EvidenceResolution(null, "action_evidence_mismatch: "
                + prefix.substring("action_evidence_missing: ".length()) + "evidenceId 未解析到 Action IR target");
        }
        JsonNode resolvedTarget = stepEvidence.resolvedTarget();
        String locator = firstText(resolvedTarget, "locator");
        JsonNode locatorSpec = resolvedTarget == null ? null : resolvedTarget.path("locatorSpec");
        boolean hasLocatorSpec = locatorSpec != null && locatorSpec.isObject();
        String serializedSpec = hasLocatorSpec ? serializeLocatorSpec(locatorSpec) : null;
        if (hasLocatorSpec && serializedSpec == null) {
            return new EvidenceResolution(null, "invalid_locator_spec: "
                + prefix.substring("action_evidence_missing: ".length()) + "locatorSpec 无法序列化为可执行 locator");
        }
        String effectiveLocator = locator.isBlank() && serializedSpec != null ? serializedSpec : locator;
        if (effectiveLocator.isBlank()) {
            if (Set.of("navigate_page", "wait_for_url", "assert_url", "screenshot").contains(intent.intent())) {
                return new EvidenceResolution(null, "");
            }
            return new EvidenceResolution(null, prefix + "resolvedTarget 缺少 locator");
        }
        if (serializedSpec != null && !serializedSpec.equals(effectiveLocator)) {
            return new EvidenceResolution(null, "action_evidence_mismatch: "
                + prefix.substring("action_evidence_missing: ".length()) + "locator 与 locatorSpec 序列化结果不一致");
        }
        if (actionPage.evidenceByLocator().values().stream().noneMatch(candidate -> candidate.locator().equals(effectiveLocator)
            && (resolvedEvidenceId.isBlank() || candidate.id().equals(resolvedEvidenceId)))) {
            return new EvidenceResolution(null, "action_evidence_mismatch: " + prefix.substring("action_evidence_missing: ".length()) + "resolvedTarget 不属于当前页面证据");
        }
        return new EvidenceResolution(new EvidenceLocator(
            resolvedEvidenceId, effectiveLocator, "", actionPage.pageUrl(), actionPage.pageFingerprint(), plan.sourcePointId()), "", actionPage);
    }

    private static final Set<String> LOCATOR_SPEC_STRATEGIES = Set.of("css", "xpath", "text", "testid", "role");

    /**
     * 结构化 locator 是长期证据格式（页面证据与 step evidence 都保存结构），DSL 仍然使用可执行字符串。
     * 这里做确定性序列化：未知策略或缺少必需字段时返回 null，由调用方按 fail-closed 处理。
     */
    private String serializeLocatorSpec(JsonNode spec) {
        String strategy = firstText(spec, "strategy").toLowerCase(Locale.ROOT);
        if (!LOCATOR_SPEC_STRATEGIES.contains(strategy)) return null;
        String selector = firstText(spec, "selector");
        String text = firstText(spec, "text");
        String testId = firstText(spec, "testId");
        String role = firstText(spec, "role");
        String name = firstText(spec, "name");
        boolean exact = spec.path("exact").asBoolean(false);
        String base = switch (strategy) {
            case "css" -> selector.isBlank() ? null : "css=" + selector;
            case "xpath" -> selector.isBlank() ? null : "xpath=" + selector;
            case "text" -> text.isBlank() ? null : "text=" + text + (exact ? ", exact=true" : "");
            case "testid" -> testId.isBlank() ? null : "testId=" + testId;
            case "role" -> role.isBlank() ? null
                : "role=" + role + (name.isBlank() ? "" : "[name=\"" + escapeLocatorName(name) + "\"" + (exact ? ", exact=true" : "") + "]");
            default -> null;
        };
        if (base == null) return null;
        String framePath = firstText(spec, "framePath");
        String shadowPath = firstText(spec, "shadowPath");
        StringBuilder builder = new StringBuilder();
        // 作用域逐段加前缀：runner 生成的执行串是 frame=#outer >> frame=#inner >> ...，
        // 这里必须逐段一致，否则嵌套 iframe/shadow 会在「locator 与 locatorSpec 序列化结果不一致」处被拒。
        for (String segment : splitScopePath(framePath)) builder.append("frame=").append(segment).append(" >> ");
        for (String segment : splitScopePath(shadowPath)) builder.append("css=").append(segment).append(" >> ");
        return builder.append(base).toString();
    }

    private static List<String> splitScopePath(String path) {
        List<String> segments = new ArrayList<>();
        for (String raw : blankToDefault(path, "").split(" >> ")) {
            String segment = raw.strip();
            if (!segment.isBlank()) segments.add(segment);
        }
        return segments;
    }

    private String escapeLocatorName(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 只有当 targetTerm 看起来是地址时才把它当作路由目标：绝对地址、以 / # ? ./ ../ 开头，
     * 或纯 ASCII 的路径片段（detail、users/detail、page.html）。语言描述（页面名、中文短语）
     * 与 String(对象) 的产物一律不采用，由调用方各自回退——避免把「去详情页」静默编译成别的东西。
     */
    private static boolean looksLikeRouteTarget(String term) {
        String value = blankToDefault(term, "").strip();
        if (value.isBlank()) return false;
        if (value.contains("[object") || value.matches("(?i)^(javascript|data|vbscript|file|blob):.*")) return false;
        if (value.matches("(?i)^https?://.+")) return true;
        if (value.startsWith("/") || value.startsWith("#") || value.startsWith("?")
            || value.startsWith("./") || value.startsWith("../")) return true;
        return value.matches("[A-Za-z0-9][A-Za-z0-9._~/-]*");
    }

    /** 路由目标可用时返回它，否则返回空串（调用方回退）。 */
    private static String routeTargetTerm(String term) {
        return looksLikeRouteTarget(term) ? blankToDefault(term, "").strip() : "";
    }

    private boolean sameActionType(ActionIntentIR intent, String actionType) {
        String normalized = blankToDefault(actionType, "").replace("_", "").toLowerCase(Locale.ROOT);
        return switch (intent.intent()) {
            case "navigate_page" -> normalized.equals("goto") || normalized.equals("navigate");
            case "wait_for_url" -> normalized.equals("waitforurl");
            case "click_readonly", "unsafe_action" -> normalized.equals("click");
            case "input_non_secret" -> normalized.equals("input") || normalized.equals("fill");
            case "select_readonly" -> normalized.equals("select");
            case "wait_ready" -> normalized.equals("waitforvisible") || normalized.equals("hover");
            case "assert_visible" -> normalized.equals("assertvisible");
            case "assert_hidden" -> normalized.equals("asserthidden");
            case "assert_text" -> normalized.equals("asserttext");
            case "assert_url" -> normalized.equals("asserturl");
            case "scroll_to_target" -> normalized.equals("scrollintoview");
            case "assert_enabled" -> normalized.equals("assertenabled");
            case "assert_disabled" -> normalized.equals("assertdisabled");
            case "paginate_readonly" -> normalized.equals("paginate");
            case "screenshot" -> normalized.equals("screenshot");
            default -> false;
        };
    }

    private PageEvidenceBundle findMatchingStepPage(StepEvidence stepEvidence, List<PageEvidenceBundle> pageEvidence) {
        if (!stepEvidence.hasCompletePageStates()) return null;
        List<PageEvidenceBundle> beforeMatches = pageEvidence.stream()
            .filter(candidate -> stepEvidence.matchesPageState(stepEvidence.before(), candidate))
            .toList();
        boolean afterMatches = pageEvidence.stream()
            .anyMatch(candidate -> stepEvidence.matchesPageState(stepEvidence.after(), candidate));
        if (beforeMatches.isEmpty() || !afterMatches) return null;
        return beforeMatches.get(0);
    }

    private EvidenceSelection selectEvidenceBundle(AgentWebCaseGenerationFacade.NaturalWebCasePlan plan, List<PageEvidenceBundle> candidates) {
        return selectEvidenceBundle(plan, candidates, false);
    }

    private EvidenceSelection selectEvidenceBundle(
        AgentWebCaseGenerationFacade.NaturalWebCasePlan plan,
        List<PageEvidenceBundle> candidates,
        boolean structured
    ) {
        if (candidates.isEmpty()) return new EvidenceSelection(null, List.of("当前测试点缺少页面证据：" + plan.sourcePointId()));
        List<String> errors = new ArrayList<>();
        List<PageEvidenceBundle> matched = new ArrayList<>();
        for (PageEvidenceBundle candidate : candidates) {
            if (!candidate.sourcePointId().equals(plan.sourcePointId())) continue;
            if (!"browserDiscovery".equals(candidate.source())) {
                errors.add("当前测试点页面证据必须来自浏览器 UI 取证：" + plan.sourcePointId());
                continue;
            }
            if (candidate.pageUrl().isBlank()) {
                errors.add("当前测试点页面证据缺少 pageUrl：" + plan.sourcePointId());
                continue;
            }
            if (candidate.pageKey().isBlank()) {
                errors.add("当前测试点页面证据缺少 pageKey：" + plan.sourcePointId());
                continue;
            }
            if (candidate.pageFingerprint().isBlank()) {
                errors.add("当前测试点页面证据缺少 pageFingerprint：" + plan.sourcePointId());
                continue;
            }
            String normalizedPageUrl = normalizePageKey(candidate.pageUrl());
            if (normalizedPageUrl.isBlank()) {
                errors.add("当前测试点页面证据 pageUrl 无法规范化：" + plan.sourcePointId());
                continue;
            }
            if (!samePageKey(candidate.pageKey(), candidate.pageUrl())) {
                errors.add("当前测试点页面证据 pageKey 与 pageUrl 不一致：" + plan.sourcePointId());
                continue;
            }
            if (!structured) {
                String expectedPageKey = resolvePageKeyAgainstPageUrl(candidate.pageUrl(), plan.startUrl());
                if (expectedPageKey.isBlank()) {
                    errors.add("当前测试点 startUrl 无法基于 pageUrl 解析为页面标识：" + plan.sourcePointId());
                    continue;
                }
                if (!samePageKey(candidate.pageKey(), expectedPageKey)
                    && !isSiteEntryPageKeyMismatch(expectedPageKey, candidate.pageUrl())) {
                    errors.add("当前测试点页面证据 pageKey 与 startUrl 不一致：" + plan.sourcePointId());
                    continue;
                }
            }
            matched.add(candidate);
        }
        if (!errors.isEmpty()) {
            return new EvidenceSelection(null, List.copyOf(errors));
        }
        if (structured && !matched.isEmpty()) {
            String expectedPageKey = matched.stream()
                .map(candidate -> resolvePageKeyAgainstPageUrl(candidate.pageUrl(), plan.startUrl()))
                .filter(value -> !value.isBlank())
                .findFirst().orElse("");
            return new EvidenceSelection(
                matched.stream().filter(candidate -> !expectedPageKey.isBlank() && samePageKey(candidate.pageKey(), expectedPageKey))
                    .findFirst().orElse(matched.get(0)), List.of());
        }
        if (matched.size() == 1) {
            return new EvidenceSelection(matched.get(0), List.of());
        }
        if (matched.size() > 1) {
            long distinctFingerprints = matched.stream().map(PageEvidenceBundle::pageFingerprint).distinct().count();
            if (distinctFingerprints > 1) {
                errors.add("当前测试点页面证据 pageFingerprint 不一致：" + plan.sourcePointId());
            } else {
                errors.add("当前测试点页面证据不唯一：" + plan.sourcePointId());
            }
        } else if (errors.isEmpty()) {
            errors.add("当前测试点页面证据不唯一或页面标识不匹配：" + plan.sourcePointId());
        }
        return new EvidenceSelection(null, List.copyOf(errors));
    }

    private Map<String, List<PageEvidenceBundle>> indexEvidence(JsonNode browserEvidence) {
        Map<String, List<PageEvidenceBundle>> byPoint = new LinkedHashMap<>();
        if (browserEvidence == null) return byPoint;
        if (browserEvidence.isArray()) {
            for (JsonNode page : browserEvidence) addPageEvidence(byPoint, page);
        } else if (browserEvidence.isObject()) {
            JsonNode pages = browserEvidence.path("pages");
            if (pages.isArray()) {
                for (JsonNode page : pages) addPageEvidence(byPoint, page);
            } else {
                addPageEvidence(byPoint, browserEvidence);
            }
        }
        return byPoint;
    }

    private void addPageEvidence(Map<String, List<PageEvidenceBundle>> byPoint, JsonNode page) {
        if (page == null || (!page.has("pageUrl") && !page.has("pageKey") && !page.has("elements"))) return;
        PageEvidenceBundle bundle = PageEvidenceBundle.from(page);
        byPoint.computeIfAbsent(bundle.sourcePointId(), key -> new ArrayList<>()).add(bundle);
    }

    private Map<String, Map<String, List<StepEvidence>>> indexStepEvidence(JsonNode browserEvidence) {
        Map<String, Map<String, List<StepEvidence>>> byPoint = new LinkedHashMap<>();
        collectStepEvidence(browserEvidence, "", byPoint);
        return byPoint;
    }

    private void collectStepEvidence(JsonNode node, String inheritedPointId, Map<String, Map<String, List<StepEvidence>>> byPoint) {
        if (node == null || node.isNull()) return;
        if (node.isArray()) {
            for (JsonNode child : node) collectStepEvidence(child, inheritedPointId, byPoint);
            return;
        }
        if (!node.isObject()) return;
        String pointId = firstText(node, "sourcePointId", "sourcePoint", "pointId");
        if (pointId.isBlank()) pointId = inheritedPointId;
        JsonNode nested = node.path("stepEvidence");
        if (nested.isArray()) {
            for (JsonNode item : nested) {
                StepEvidence evidence = StepEvidence.from(item, pointId);
                if (!evidence.actionId().isBlank()) {
                    byPoint.computeIfAbsent(pointId, key -> new LinkedHashMap<>())
                        .computeIfAbsent(evidence.actionId(), key -> new ArrayList<>()).add(evidence);
                }
            }
        }
        if (node.has("actionId") || node.has("actionType")) {
            StepEvidence evidence = StepEvidence.from(node, pointId);
            if (!evidence.actionId().isBlank()) {
                byPoint.computeIfAbsent(pointId, key -> new LinkedHashMap<>())
                    .computeIfAbsent(evidence.actionId(), key -> new ArrayList<>()).add(evidence);
            }
        }
        for (String field : List.of("pages", "pageEvidence", "results", "cases")) {
            collectStepEvidence(node.path(field), pointId, byPoint);
        }
    }

    private StepEvidence findStepEvidence(Map<String, Map<String, List<StepEvidence>>> byPoint, String pointId, String actionId) {
        List<StepEvidence> matches = byPoint.getOrDefault(pointId, Map.of()).getOrDefault(actionId, List.of());
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private String buildExpectedAssertion(AgentWebCaseGenerationFacade.NaturalWebCasePlan plan, PageEvidenceBundle pageEvidence, String executionStartUrl) {
        return buildExpectedAssertionBinding(plan, pageEvidence, executionStartUrl).line();
    }

    private ExpectedAssertionBinding buildExpectedAssertionBinding(AgentWebCaseGenerationFacade.NaturalWebCasePlan plan, PageEvidenceBundle pageEvidence, String executionStartUrl) {
        String expected = blankToDefault(plan.naturalExpectedResult(), "").strip();
        if (expected.isBlank()) return new ExpectedAssertionBinding("", "");
        if (isRouteOnlyPlan(plan, pageEvidence, expected)) {
            return new ExpectedAssertionBinding("assertUrl " + blankToDefault(executionStartUrl, pageEvidence.pageUrl()).strip(), "");
        }
        EvidenceLocator locator = pageEvidence.matchExpected(expected, plan);
        if (locator == null) return new ExpectedAssertionBinding("", "");
        if (expected.contains("隐藏") || expected.contains("消失") || expected.contains("不可见")) {
            return new ExpectedAssertionBinding("assertHidden " + locator.locator(), locator.id());
        }
        if (expected.contains("文本") || expected.contains("文案") || expected.contains("标题") || expected.contains("提示") || expected.contains("显示为")) {
            return new ExpectedAssertionBinding("assertText " + locator.locator() + " = " + expected, locator.id());
        }
        return new ExpectedAssertionBinding("assertVisible " + locator.locator(), locator.id());
    }

    private boolean isRouteOnlyPlan(AgentWebCaseGenerationFacade.NaturalWebCasePlan plan, PageEvidenceBundle pageEvidence, String expected) {
        String semantic = semanticText(planNode(plan)).toLowerCase(Locale.ROOT) + " " + blankToDefault(expected, "").toLowerCase(Locale.ROOT);
        boolean routeHint = containsAny(semantic, Set.of("访问", "路由", "可达", "可访问", "可进入", "入口", "跳转", "进入", "打开"));
        boolean contentHint = containsAny(semantic, CONTENT_KEYWORDS);
        return routeHint && !contentHint;
    }

    private boolean isRouteIntent(ObjectNode generatedCase) {
        String text = semanticText(generatedCase).toLowerCase(Locale.ROOT);
        return text.contains("路由") || text.contains("url") || text.contains("路径") || text.contains("页面地址");
    }

    private String semanticText(JsonNode node) {
        StringBuilder builder = new StringBuilder();
        for (String field : List.of("title", "module", "pageName", "objective", "expectedResult", "naturalSteps", "naturalExpectedResult")) {
            appendText(builder, node.path(field).asText(""));
        }
        return builder.toString();
    }

    private void appendText(StringBuilder builder, String value) {
        String normalized = blankToDefault(value, "").strip();
        if (!normalized.isBlank()) builder.append(' ').append(normalized);
    }

    private JsonNode planNode(AgentWebCaseGenerationFacade.NaturalWebCasePlan plan) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("sourcePointId", blankToDefault(plan.sourcePointId(), ""));
        node.put("title", blankToDefault(plan.title(), ""));
        node.put("module", blankToDefault(plan.module(), ""));
        node.put("pageName", blankToDefault(plan.pageName(), ""));
        node.put("startUrl", blankToDefault(plan.startUrl(), ""));
        node.set("naturalSteps", objectMapper.valueToTree(plan.naturalSteps()));
        node.put("naturalExpectedResult", blankToDefault(plan.naturalExpectedResult(), ""));
        node.put("objective", blankToDefault(plan.objective(), ""));
        return node;
    }

    private List<String> naturalSteps(AgentWebCaseGenerationFacade.NaturalWebCasePlan plan) {
        return plan == null ? List.of() : plan.naturalSteps();
    }

    private List<String> keywordCandidates(String naturalStep) {
        String stripped = stripActionPrefix(blankToDefault(naturalStep, ""));
        List<String> results = new ArrayList<>();
        for (String part : stripped.split("[，,。；;、\\s]+")) {
            String value = part.strip();
            if (!value.isBlank()) results.add(value);
        }
        MatcherResult quoted = MatcherResult.of(QUOTED_COPY, stripped);
        results.addAll(quoted.items());
        return results;
    }

    private String stripActionPrefix(String value) {
        String normalized = blankToDefault(value, "").strip();
        for (String prefix : List.of("进入", "打开", "访问", "跳转到", "跳转", "查看", "点击", "单击", "选择", "切换", "输入", "填写", "搜索", "查询", "筛选", "滚动到", "翻到", "翻页至", "等待")) {
            if (normalized.startsWith(prefix) && normalized.length() > prefix.length()) {
                String remainder = normalized.substring(prefix.length()).strip();
                if (("搜索".equals(prefix) || "查询".equals(prefix)) && containsAny(remainder, Set.of("按钮", "入口", "菜单", "标签", "页签", "链接", "图标"))) {
                    return normalized;
                }
                return remainder;
            }
        }
        return normalized;
    }

    private String normalizeTargetTerm(String value) {
        String normalized = stripActionPrefix(blankToDefault(value, ""));
        if (looksLikeLocator(normalized)) return normalized;
        boolean changed;
        do {
            changed = false;
            for (String suffix : List.of("页面", "列表页", "详情页", "按钮", "入口", "菜单", "标签", "页签", "tab", "链接", "标题", "文案", "提示", "列表", "字段", "图标", "项", "页")) {
                if (normalized.endsWith(suffix) && normalized.length() > suffix.length()) {
                    normalized = normalized.substring(0, normalized.length() - suffix.length()).strip();
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return normalized;
    }

    private boolean looksLikeLocator(String value) {
        String normalized = blankToDefault(value, "").strip();
        return normalized.contains("=") || normalized.startsWith("//") || normalized.startsWith(".//") || normalized.startsWith("#") || normalized.startsWith(".");
    }

    private boolean containsAny(String value, Set<String> keywords) {
        String normalized = blankToDefault(value, "").toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private boolean samePageToken(String left, String right) {
        String normalizedLeft = blankToDefault(left, "").replaceAll("\\s+", "");
        String normalizedRight = blankToDefault(right, "").replaceAll("\\s+", "");
        return normalizedLeft.equalsIgnoreCase(normalizedRight)
            || normalizedLeft.contains(normalizedRight)
            || normalizedRight.contains(normalizedLeft);
    }

    private boolean samePageKey(String left, String right) {
        return normalizePageKey(left).equals(normalizePageKey(right));
    }

    private boolean samePageFingerprint(String left, String right) {
        return blankToDefault(left, "").strip().equals(blankToDefault(right, "").strip());
    }

    private String resolvePageKeyAgainstPageUrl(String pageUrl, String startUrl) {
        String normalizedStartUrl = blankToDefault(startUrl, "").trim();
        if (normalizedStartUrl.isBlank()) return "";
        String normalizedPageUrl = blankToDefault(pageUrl, "").trim();
        try {
            if (normalizedPageUrl.isBlank()) {
                return normalizePageKey(normalizedStartUrl);
            }
            return normalizePageKey(URI.create(normalizedPageUrl).resolve(normalizedStartUrl).toString());
        } catch (Exception ignored) {
            return normalizePageKey(normalizedStartUrl);
        }
    }

    /**
     * 起始页回退：按计划动作顺序取第一个动作 before 所在的页面 URL，取不到才退回被选中的 bundle。
     */
    private String resolveFallbackStartPageUrl(
        AgentWebCaseGenerationFacade.NaturalWebCasePlan plan,
        Map<String, List<StepEvidence>> stepsByAction,
        PageEvidenceBundle selected
    ) {
        if (plan.actions() != null && plan.actions().isArray()) {
            for (JsonNode action : plan.actions()) {
                String actionId = firstText(action, "actionId", "id");
                if (actionId.isBlank()) continue;
                for (StepEvidence step : stepsByAction.getOrDefault(actionId, List.of())) {
                    String url = firstText(step.before(), "pageUrl", "url");
                    if (!url.isBlank()) return url;
                }
            }
        }
        return selected == null ? "" : selected.pageUrl();
    }

    private String resolveExecutionStartUrl(String startUrl, String pageUrl) {
        String normalizedStartUrl = blankToDefault(startUrl, "").trim();
        String normalizedPageUrl = blankToDefault(pageUrl, "").trim();
        if (normalizedStartUrl.isBlank()) {
            return normalizedPageUrl;
        }
        if (!normalizedStartUrl.startsWith("#")) {
            // 「站点入口」语义（如计划写 "/"）而页面证据在同源更深路由时，采用证据页作为执行起点：
            // 计划里元素真实存在于证据页，照搬 "/" 会让用例 goto 到无元素的 SPA 空壳根页。
            if (isSiteEntryStartUrl(normalizedStartUrl, normalizedPageUrl)) {
                return normalizedPageUrl;
            }
            return normalizedStartUrl;
        }
        if (normalizedPageUrl.isBlank()) {
            return normalizedStartUrl;
        }
        try {
            return URI.create(normalizedPageUrl).resolve(normalizedStartUrl).toString();
        } catch (Exception ignored) {
            return normalizedStartUrl;
        }
    }

    /** startUrl 解析结果为源站根入口（路径 "/"、无片段），且证据页与它同源且处于更深路由。 */
    private boolean isSiteEntryStartUrl(String startUrl, String pageUrl) {
        if (pageUrl.isBlank()) return false;
        try {
            URI evidence = URI.create(pageUrl);
            URI resolved = evidence.resolve(startUrl);
            if (!"/".equals(normalizeRoute(resolved.getRawPath()))) return false;
            if (resolved.getRawFragment() != null && !resolved.getRawFragment().isBlank()) return false;
            if (!sameOrigin(evidence, resolved)) return false;
            String evidenceRoute = normalizeRoute(evidence.getRawPath())
                + (evidence.getRawFragment() == null || evidence.getRawFragment().isBlank() ? "" : "#" + evidence.getRawFragment());
            return !"/".equals(evidenceRoute);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 期望页标识＝证据页源站的根入口（计划写 "/"），而证据页在更深路由：SPA 入口等价，允许匹配。 */
    private boolean isSiteEntryPageKeyMismatch(String expectedPageKey, String evidencePageUrl) {
        if (expectedPageKey.isBlank() || evidencePageUrl.isBlank()) return false;
        try {
            URI evidence = URI.create(evidencePageUrl);
            String origin = originOf(evidence);
            if (origin.isBlank()) return false;
            String rootKey = normalizePageKey(origin + "/");
            String evidenceKey = normalizePageKey(evidencePageUrl);
            return expectedPageKey.equals(rootKey) && !evidenceKey.equals(rootKey);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String originOf(URI uri) {
        if (uri.getScheme() == null || uri.getHost() == null) return "";
        String origin = uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT);
        return uri.getPort() > 0 ? origin + ":" + uri.getPort() : origin;
    }

    private boolean sameOrigin(URI left, URI right) {
        return !originOf(left).isBlank() && originOf(left).equals(originOf(right));
    }

    private boolean sameRoute(String left, String right) {
        return normalizeRoute(left).equals(normalizeRoute(right));
    }

    private static String normalizeRoute(String value) {
        String normalized = blankToDefault(value, "").trim();
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            int start = normalized.indexOf('/', normalized.indexOf("://") + 3);
            normalized = start < 0 ? "/" : normalized.substring(start);
        }
        normalized = normalized.replaceAll("[?#].*$", "");
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? "/" : normalized;
    }

    private boolean isSupportedAction(String action) {
        return Set.of("goto", "click", "input", "select", "hover", "waitForVisible", "waitForUrl", "assertVisible", "assertHidden", "assertText", "assertUrl", "assertEnabled", "assertDisabled", "scrollIntoView", "scroll_into_view", "paginate", "screenshot")
            .contains(action);
    }

    private String firstToken(String line) {
        int separator = line.indexOf(' ');
        return separator < 0 ? line : line.substring(0, separator).trim();
    }

    private String actionTarget(String line) {
        int separator = line.indexOf(' ');
        return separator < 0 ? "" : line.substring(separator + 1).trim();
    }

    private static String normalizePageKey(String url) {
        try {
            URI parsed = URI.create(blankToDefault(url, "").trim());
            String scheme = parsed.getScheme();
            String host = parsed.getHost();
            if (scheme == null || host == null) return "";
            String origin = scheme.toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT);
            if (parsed.getPort() > 0) {
                origin += ":" + parsed.getPort();
            }
            return origin + normalizeRoute(parsed.getRawPath()) + normalizeHashRoute(parsed.getRawFragment());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String normalizeHashRoute(String value) {
        String raw = blankToDefault(value, "").strip();
        if (raw.startsWith("#")) raw = raw.substring(1);
        if (raw.isBlank()) return "";
        String route = raw.split("[?#]", 2)[0].replaceFirst("^!", "").strip();
        return route.isBlank() ? "" : "#" + route;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String textValue(Object value, String key) {
        if (!(value instanceof Map<?, ?> map)) return "";
        Object extracted = map.get(key);
        return extracted == null ? "" : String.valueOf(extracted);
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        for (String field : fields) {
            String value = node.path(field).asText("").strip();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    record ActionIntentIR(
        String sourcePointId,
        int stepIndex,
        String actionId,
        String intent,
        String targetTerm,
        String scopeTerm,
        String expectedState,
        String assertionKind,
        String valueSource,
        boolean structured
    ) {
        ActionIntentIR(String sourcePointId, int stepIndex, String actionId, String intent, String targetTerm, String scopeTerm, String expectedState, String assertionKind, String valueSource) {
            this(sourcePointId, stepIndex, actionId, intent, targetTerm, scopeTerm, expectedState, assertionKind, valueSource, false);
        }

        ActionIntentIR(String sourcePointId, int stepIndex, String intent, String targetTerm, String scopeTerm, String expectedState, String assertionKind, String valueSource) {
            this(sourcePointId, stepIndex, "", intent, targetTerm, scopeTerm, expectedState, assertionKind, valueSource, false);
        }
    }

    record CompilationResult(
        List<AgentWebCaseGenerationFacade.GeneratedWebCase> cases,
        List<String> errors,
        List<ActionIntentIR> actionIntents
    ) {
        CompilationResult {
            cases = cases == null ? List.of() : List.copyOf(cases);
            errors = errors == null ? List.of() : List.copyOf(errors);
            actionIntents = actionIntents == null ? List.of() : List.copyOf(actionIntents);
        }

        boolean success() {
            return errors.isEmpty();
        }
    }

    private record EvidenceSelection(PageEvidenceBundle bundle, List<String> errors) {
    }

    private record ExpectedAssertionBinding(String line, String evidenceId) {
        ExpectedAssertionBinding {
            line = line == null ? "" : line;
            evidenceId = evidenceId == null ? "" : evidenceId;
        }
    }

    private record EvidenceLocator(
        String id,
        String locator,
        String label,
        String pageUrl,
        String pageFingerprint,
        String sourcePointId
    ) {
    }

    private record EvidenceResolution(EvidenceLocator locator, String error, PageEvidenceBundle pageEvidence) {
        EvidenceResolution(EvidenceLocator locator, String error) {
            this(locator, error, null);
        }

        EvidenceResolution {
            error = error == null ? "" : error;
        }
    }

    private record StepEvidence(
        String actionId,
        String actionType,
        String evidenceId,
        String sourcePointId,
        JsonNode before,
        JsonNode after,
        JsonNode resolvedTarget
    ) {
        static StepEvidence from(JsonNode node, String inheritedPointId) {
            String pointId = firstText(node, "sourcePointId", "sourcePoint", "pointId");
            return new StepEvidence(
                firstText(node, "actionId", "id"),
                firstText(node, "actionType", "type"),
                firstText(node, "evidenceId"),
                pointId.isBlank() ? inheritedPointId : pointId,
                node == null ? null : node.path("before").isMissingNode() ? node : node.path("before"),
                node == null ? null : node.path("after").isMissingNode() ? node : node.path("after"),
                node == null ? null : node.path("resolvedTarget")
            );
        }

        Set<String> pageKeys() {
            Set<String> values = new LinkedHashSet<>();
            addIfNotBlank(values, firstText(before, "pageKey", "pageUrl"));
            addIfNotBlank(values, firstText(after, "pageKey", "pageUrl"));
            return values;
        }

        Set<String> pageFingerprints() {
            Set<String> values = new LinkedHashSet<>();
            addIfNotBlank(values, firstText(before, "pageFingerprint", "fingerprint"));
            addIfNotBlank(values, firstText(after, "pageFingerprint", "fingerprint"));
            return values;
        }

        boolean hasCompletePageStates() {
            return hasCompletePageState(before) && hasCompletePageState(after);
        }

        boolean matchesPageState(JsonNode state, PageEvidenceBundle page) {
            String pageKey = firstText(state, "pageKey", "pageUrl");
            String pageFingerprint = firstText(state, "pageFingerprint", "fingerprint");
            return samePageKeyValue(pageKey, page.pageKey()) && pageFingerprint.equals(page.pageFingerprint());
        }

        private static boolean hasCompletePageState(JsonNode state) {
            return !firstText(state, "pageKey", "pageUrl").isBlank()
                && !firstText(state, "pageFingerprint", "fingerprint").isBlank();
        }

        private static boolean samePageKeyValue(String left, String right) {
            return normalizePageKey(left).equals(normalizePageKey(right));
        }

        private static void addIfNotBlank(Set<String> values, String value) {
            if (!value.isBlank()) values.add(value);
        }

        private static String firstText(JsonNode node, String... fields) {
            if (node == null || node.isMissingNode() || node.isNull()) return "";
            for (String field : fields) {
                String value = node.path(field).asText("").strip();
                if (!value.isBlank()) return value;
            }
            return "";
        }
    }

    private record PageEvidenceBundle(
        String sourcePointId,
        String source,
        String pageKey,
        String pageFingerprint,
        String pageUrl,
        List<EvidenceLocator> evidence
    ) {
        static PageEvidenceBundle from(JsonNode page) {
            List<EvidenceLocator> evidence = new ArrayList<>();
            if (page != null && page.path("elements").isArray()) {
                for (JsonNode element : page.path("elements")) {
                    String locator = element.path("locator").asText("").strip();
                    if (locator.isBlank()) continue;
                    evidence.add(new EvidenceLocator(
                        firstText(element, "evidenceId", "id"),
                        locator,
                        firstText(element, "label", "text", "name", "accessibleName", "innerText"),
                        page.path("pageUrl").asText(""),
                        page.path("pageFingerprint").asText("").strip(),
                        page.path("sourcePointId").asText("").strip()
                    ));
                }
            }
            String sourcePointId = page == null ? "" : page.path("sourcePointId").asText("").strip();
            String source = page == null ? "" : page.path("source").asText("").strip();
            String pageKey = page == null ? "" : page.path("pageKey").asText("").strip();
            String pageFingerprint = page == null ? "" : page.path("pageFingerprint").asText("").strip();
            String pageUrl = page == null ? "" : page.path("pageUrl").asText("");
            return new PageEvidenceBundle(sourcePointId, source, pageKey, pageFingerprint, pageUrl, List.copyOf(evidence));
        }

        Map<String, EvidenceLocator> evidenceByLocator() {
            Map<String, EvidenceLocator> map = new LinkedHashMap<>();
            for (EvidenceLocator item : evidence) {
                if (!map.containsKey(item.locator())) map.put(item.locator(), item);
                if (!item.id().isBlank() && !map.containsKey(item.id())) map.put(item.id(), item);
            }
            return map;
        }

        EvidenceLocator findById(String target) {
            if (target == null || target.isBlank()) return null;
            for (EvidenceLocator item : evidence) {
                if (target.equals(item.id())) return item;
            }
            return null;
        }

        EvidenceLocator findByLocator(String target) {
            if (target == null || target.isBlank()) return null;
            for (EvidenceLocator item : evidence) {
                if (target.equals(item.locator())) return item;
            }
            return null;
        }

        EvidenceLocator findByExactLabel(String target) {
            if (target == null || target.isBlank()) return null;
            List<EvidenceLocator> exact = new ArrayList<>();
            for (EvidenceLocator item : evidence) {
                if (target.equals(item.label())) exact.add(item);
            }
            if (exact.size() == 1) return exact.get(0);
            return null;
        }

        EvidenceLocator findByLabel(String target) {
            return findByExactLabel(target);
        }

        List<EvidenceLocator> match(String target, AgentWebCaseGenerationFacade.NaturalWebCasePlan plan) {
            String normalized = blankToDefault(target, "").toLowerCase(Locale.ROOT);
            List<EvidenceLocator> matches = new ArrayList<>();
            for (EvidenceLocator item : evidence) {
                String candidate = String.join(" ", blankToDefault(item.label(), ""), blankToDefault(item.locator(), ""), blankToDefault(item.id(), "")).toLowerCase(Locale.ROOT);
                if (!normalized.isBlank() && candidate.contains(normalized)) {
                    matches.add(item);
                }
            }
            if (!matches.isEmpty()) return matches;
            String planTerm = blankToDefault(plan.pageName(), plan.module()).toLowerCase(Locale.ROOT);
            for (EvidenceLocator item : evidence) {
                String candidate = String.join(" ", blankToDefault(item.label(), ""), blankToDefault(item.locator(), "")).toLowerCase(Locale.ROOT);
                if (!planTerm.isBlank() && candidate.contains(planTerm)) {
                    matches.add(item);
                }
            }
            return matches;
        }

        EvidenceLocator matchExpected(String expected, AgentWebCaseGenerationFacade.NaturalWebCasePlan plan) {
            List<String> matches = new ArrayList<>();
            String normalized = expected.toLowerCase(Locale.ROOT);
            for (EvidenceLocator item : evidence) {
                String label = blankToDefault(item.label(), "").toLowerCase(Locale.ROOT);
                String locator = blankToDefault(item.locator(), "").toLowerCase(Locale.ROOT);
                if ((!label.isBlank() && (normalized.contains(label) || label.contains(normalized)))
                    || (!locator.isBlank() && (normalized.contains(locator) || locator.contains(normalized)))) {
                    matches.add(item.locator());
                }
            }
            if (matches.size() == 1) {
                return findByLocator(matches.get(0));
            }
            return null;
        }

        String primaryLabel() {
            return evidence.isEmpty() ? "" : blankToDefault(evidence.get(0).label(), evidence.get(0).locator());
        }

        private static String firstText(JsonNode node, String... fields) {
            for (String field : fields) {
                String value = node.path(field).asText("").strip();
                if (!value.isBlank()) return value;
            }
            return "";
        }

    }

    private static final class MatcherResult {
        private final List<String> items;

        private MatcherResult(List<String> items) {
            this.items = items;
        }

        static MatcherResult of(Pattern pattern, String value) {
            List<String> items = new ArrayList<>();
            if (value == null) return new MatcherResult(items);
            java.util.regex.Matcher matcher = pattern.matcher(value);
            while (matcher.find()) {
                String candidate = matcher.group(1).strip();
                if (!candidate.isBlank()) items.add(candidate);
            }
            return new MatcherResult(items);
        }

        List<String> items() {
            return items;
        }
    }
}
