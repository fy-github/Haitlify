package com.haitlify.aitest.platform.ai.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class WebCaseCompilationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebAutomationCaseValidator validator = new WebAutomationCaseValidator();
    private final WebCaseCompilationService service = new WebCaseCompilationService(objectMapper, validator);

    @Test
    void missingStructuredActionsFailClosedAsInvalidActionIr() {
        var batch = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
                "point-1",
                "用户管理列表展示",
                "用户",
                "用户管理",
                "/users",
                List.of("进入用户管理页面", "点击搜索按钮", "查看用户管理标题"),
                "用户管理标题可见",
                "验证用户管理列表"
            )),
            objectMapper.createObjectNode()
        );

        List<WebCaseCompilationService.ActionIntentIR> intents = service.buildActionIntentIR(batch);

        assertThat(intents).hasSize(1);
        assertThat(intents.get(0).intent()).isEqualTo("invalid_action_ir");
        assertThat(intents.get(0).expectedState()).contains("缺少结构化 actions");
        // 阶段 4：自然语言步骤仅用于展示，不再被编译成动作。
        assertThat(intents).extracting(WebCaseCompilationService.ActionIntentIR::intent)
            .doesNotContain("navigate_page", "click_readonly", "assert_text");
    }

    @Test
    void nonArrayStructuredActionsFailClosedAsInvalidActionIr() {
        var batch = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
                "point-2",
                "用户管理列表展示",
                "用户",
                "用户管理",
                "/users",
                List.of("进入用户管理页面"),
                "用户管理标题可见",
                "验证用户管理列表",
                objectMapper.valueToTree("navigate /users"),
                "",
                ""
            )),
            objectMapper.createObjectNode()
        );

        List<WebCaseCompilationService.ActionIntentIR> intents = service.buildActionIntentIR(batch);

        assertThat(intents).hasSize(1);
        assertThat(intents.get(0).intent()).isEqualTo("invalid_action_ir");
        assertThat(intents.get(0).expectedState()).contains("缺少结构化 actions");
    }

    @Test
    void structuredActionsUseEvidenceIdsAndKeepAssertTextExpectedSeparateFromTarget() {
        ArrayNode actions = objectMapper.createArrayNode();
        actions.add(objectMapper.createObjectNode().put("id", "fill-search").put("type", "fill")
            .put("safety", "readonly")
            .set("target", objectMapper.createObjectNode().put("evidenceId", "ev-search"))
            .set("value", objectMapper.createObjectNode().put("kind", "runtimeVariable").put("key", "query")));
        actions.add(objectMapper.createObjectNode().put("id", "assert-title").put("type", "assertText")
            .put("safety", "readonly")
            .set("target", objectMapper.createObjectNode().put("evidenceId", "ev-title"))
            .put("expected", "用户管理"));
        var plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
            "point-structured", "标题", "用户", "用户管理", "/users",
            List.of("错误的 legacy 步骤"), "错误的 legacy 预期", "验证结构化动作", actions, "", ""
        );

        assertThat(ActionIrValidator.validate(actions)).as(String.join(" | ", ActionIrValidator.validate(actions))).isEmpty();
        List<WebCaseCompilationService.ActionIntentIR> intents = service.buildActionIntentIR(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode())
        );

        assertThat(intents).hasSize(2);
        assertThat(intents.get(0).actionId()).isEqualTo("fill-search");
        assertThat(intents.get(0).targetTerm()).isEqualTo("ev-search");
        assertThat(intents.get(0).valueSource()).isEqualTo("{{query}}");
        assertThat(intents.get(1).actionId()).isEqualTo("assert-title");
        assertThat(intents.get(1).targetTerm()).isEqualTo("ev-title");
        assertThat(intents.get(1).expectedState()).isEqualTo("用户管理");
    }

    @Test
    void explicitEmptyStructuredActionsDoNotFallBackToNaturalSteps() {
        ArrayNode actions = objectMapper.createArrayNode();
        var plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
            "point-empty", "标题", "用户", "用户管理", "/users",
            List.of("点击搜索按钮"), "搜索按钮可见", "验证结构化动作", actions, "", ""
        );

        List<WebCaseCompilationService.ActionIntentIR> intents = service.buildActionIntentIR(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode())
        );

        assertThat(intents).isEmpty();
    }

    @Test
    void structuredActionValidationRequiresUniqueIdsAndRejectsAiLocators() {
        ArrayNode actions = objectMapper.createArrayNode();
        actions.add(objectMapper.createObjectNode().put("id", "same").put("type", "click").put("safety", "readonly")
            .set("target", objectMapper.createObjectNode().put("locator", "css=.ai-guessed")));
        actions.add(objectMapper.createObjectNode().put("id", "same").put("type", "assertText").put("safety", "readonly")
            .set("target", objectMapper.createObjectNode().put("evidenceId", "ev-title"))
            .put("expected", "标题"));

        List<String> errors = ActionIrValidator.validate(actions);

        assertThat(errors).anyMatch(error -> error.contains("id") && error.contains("唯一"));
        assertThat(errors).anyMatch(error -> error.contains("locator") && error.contains("evidenceId"));
    }

    @Test
    void structuredActionValidationRejectsStringifiedRouteTargetsButKeepsRelativePaths() {
        // 之前只校验 route/value 非空：String(对象) 的产物和 javascript: 这类 scheme 都会被放行，
        // 执行器随后把页面导航到 /[object%20Object]。合法的同源相对路径必须继续通过。
        for (String good : List.of("detail", "users/detail", "?q=1", "#/detail", "/detail", "./detail", "../detail", "https://example.test/detail")) {
            ArrayNode actions = objectMapper.createArrayNode();
            actions.add(objectMapper.createObjectNode().put("id", "go").put("type", "navigate").put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("kind", "route").put("value", good)));
            assertThat(ActionIrValidator.validate(actions)).as(good).isEmpty();
        }
        for (String bad : List.of("[object Object]", "javascript:alert(1)", "data:text/html,hi",
            "not a url", "a b", "威胁情报", "设备详情页")) {
            ArrayNode actions = objectMapper.createArrayNode();
            actions.add(objectMapper.createObjectNode().put("id", "go").put("type", "navigate").put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("kind", "route").put("value", bad)));
            assertThat(ActionIrValidator.validate(actions)).as(bad).anyMatch(error -> error.contains("不是可执行的地址"));
        }
    }

    @Test
    void elementActionsRejectRouteTargetsBeforeTheRunnerTriesToClickThem() {
        // 复核方实测：平台放行了 {kind:'route'} 的元素型动作（click/assertText…），
        // runner 的解析器对 route 目标返回 undefined，执行期在 7 处解引用抛 TypeError（HTTP 503，分类与证据全丢）。
        for (String type : List.of("click", "assertText", "assertVisible", "hover", "fill", "select", "paginate")) {
            ArrayNode actions = objectMapper.createArrayNode();
            ObjectNode action = objectMapper.createObjectNode().put("id", type + "-action").put("type", type).put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("kind", "route").put("value", "/detail"));
            if ("fill".equals(type) || "select".equals(type)) action.set("value", objectMapper.createObjectNode().put("kind", "literal").put("value", "x"));
            actions.add(action);
            assertThat(ActionIrValidator.validate(actions)).as(type).anyMatch(error -> error.contains("元素型动作不接受 route 目标"));
        }
        // 元素型动作引用 evidenceId 仍然放行（取证边界没有被收紧成「什么都不过」）。
        ArrayNode ok = objectMapper.createArrayNode();
        ok.add(objectMapper.createObjectNode().put("id", "click-action").put("type", "click").put("safety", "readonly")
            .set("target", objectMapper.createObjectNode().put("kind", "evidence").put("evidenceId", "element-1")));
        assertThat(ActionIrValidator.validate(ok)).isEmpty();
    }

    @Test
    void runtimeValueKindsStayInTheSharedVocabularyUsedByTheRunner() {
        // 平台与 runner 必须是同一份值来源词表：平台接受 runtimevariable/maskedsecret（编译成 {{变量}}），
        // runner 侧从请求的 variables/maskedSecrets 解析；没有来源时 runner 报分类错误（tests: page-locator-discovery）。
        for (String kind : List.of("literal", "testdata", "runtimevariable", "runtime_variable", "maskedsecret")) {
            ArrayNode actions = objectMapper.createArrayNode();
            // testdata 是内联值（key 只是数据集引用）：只给 key 时两侧都 fail-closed。
            ObjectNode value = objectMapper.createObjectNode().put("kind", kind).put("key", "userName");
            if ("testdata".equals(kind)) value.put("value", "张三");
            actions.add(objectMapper.createObjectNode().put("id", "fill-name").put("type", "fill").put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("kind", "evidence").put("evidenceId", "element-1"))
                .set("value", value));
            assertThat(ActionIrValidator.validate(actions)).as(kind).isEmpty();
        }
        // testdata 只给 key：平台侧没有数据集通道，把键名当值填入会让页面收到错误数据却记 passed。
        ArrayNode keyOnlyTestData = objectMapper.createArrayNode();
        keyOnlyTestData.add(objectMapper.createObjectNode().put("id", "fill-name").put("type", "fill").put("safety", "readonly")
            .set("target", objectMapper.createObjectNode().put("kind", "evidence").put("evidenceId", "element-1"))
            .set("value", objectMapper.createObjectNode().put("kind", "testdata").put("key", "deviceName")));
        assertThat(ActionIrValidator.validate(keyOnlyTestData))
            .anyMatch(error -> error.contains("testdata") && error.contains("内联 value"));
        ArrayNode unknown = objectMapper.createArrayNode();
        unknown.add(objectMapper.createObjectNode().put("id", "fill-name").put("type", "fill").put("safety", "readonly")
            .set("target", objectMapper.createObjectNode().put("kind", "evidence").put("evidenceId", "element-1"))
            .set("value", objectMapper.createObjectNode().put("kind", "environment").put("key", "userName")));
        assertThat(ActionIrValidator.validate(unknown)).anyMatch(error -> error.contains("value.kind 不支持"));
    }

    @Test
    void assertUrlAliasShapesValidateTheirRouteTargetLikePlainAssertUrlActions() {
        // assert 的地址别名（assertion.type=asserturl/assert_url）：目标在 assertion 里。
        // 之前只判断 assertionType=="url"，别名形状整段跳过路由形状校验——地址栏里写描述文字也能过计划期校验。
        for (String aliasType : List.of("asserturl", "assert_url")) {
            ArrayNode prose = objectMapper.createArrayNode();
            prose.add(objectMapper.createObjectNode().put("id", "assert-detail-url").put("type", "assert").put("safety", "readonly")
                .set("assertion", objectMapper.createObjectNode().put("type", aliasType)
                    .set("target", objectMapper.createObjectNode().put("kind", "route").put("value", "设备详情页"))));
            assertThat(ActionIrValidator.validate(prose)).as(aliasType)
                .anyMatch(error -> error.contains("不是可执行的地址") && error.contains("设备详情页"));

            ArrayNode route = objectMapper.createArrayNode();
            route.add(objectMapper.createObjectNode().put("id", "assert-detail-url").put("type", "assert").put("safety", "readonly")
                .set("assertion", objectMapper.createObjectNode().put("type", aliasType)
                    .set("target", objectMapper.createObjectNode().put("kind", "route").put("value", "/detail"))));
            assertThat(ActionIrValidator.validate(route)).as(aliasType).isEmpty();
        }
    }

    @Test
    void assertUrlAliasIntentsKeepTheOriginalTargetValueSoErrorsStayLocatable() {
        // 别名形状的目标读不到时会得到 targetTerm=''，报错只剩 intent 名，原值定位不到。
        for (String aliasType : List.of("asserturl", "assert_url")) {
            ArrayNode actions = objectMapper.createArrayNode();
            actions.add(objectMapper.createObjectNode().put("id", "assert-detail-url").put("type", "assert").put("safety", "readonly")
                .set("assertion", objectMapper.createObjectNode().put("type", aliasType)
                    .set("target", objectMapper.createObjectNode().put("kind", "route").put("value", "/detail"))));
            var batch = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
                List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
                    "point-alias", "详情页地址", "设备管理", "设备列表", "",
                    List.of("查看详情页地址"), "地址为 /detail", "验证地址断言别名", actions, "chromium", "1440x900")),
                objectMapper.createObjectNode());

            List<WebCaseCompilationService.ActionIntentIR> intents = service.buildActionIntentIR(batch);

            assertThat(intents).as(aliasType).hasSize(1);
            assertThat(intents.get(0).targetTerm()).as(aliasType).contains("/detail");
        }
    }

    @Test
    void structuredActionValidationRequiresValueSourceForFillAndSelect() {
        ArrayNode actions = objectMapper.createArrayNode();
        actions.add(objectMapper.createObjectNode().put("id", "fill").put("type", "fill").put("safety", "readonly")
            .set("target", objectMapper.createObjectNode().put("evidenceId", "ev-input")));
        actions.add(objectMapper.createObjectNode().put("id", "select").put("type", "select").put("safety", "readonly")
            .set("target", objectMapper.createObjectNode().put("evidenceId", "ev-select"))
            .set("value", objectMapper.createObjectNode().put("kind", "unknown").put("key", "status")));

        List<String> errors = ActionIrValidator.validate(actions);

        assertThat(errors).anyMatch(error -> error.contains("fill") && error.contains("value"));
        assertThat(errors).anyMatch(error -> error.contains("select") && error.contains("kind"));
    }

    @Test
    void structuredActionValidationUsesStableEvidenceErrorsAndAssertionSemanticTargetAlias() {
        ArrayNode missing = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
            .put("id", "click").put("type", "click").put("safety", "readonly"));
        assertThat(ActionIrValidator.validate(missing)).anyMatch(error -> error.startsWith("action_evidence_missing:"));

        ArrayNode assertion = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
            .put("id", "assert").put("type", "assert").put("safety", "readonly")
            .set("assertion", objectMapper.createObjectNode().put("type", "visible")
                .set("semanticTarget", objectMapper.createObjectNode().put("evidenceId", "ev-title"))));
        assertThat(ActionIrValidator.validate(assertion)).isEmpty();

        ArrayNode semantic = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
            .put("id", "click").put("type", "click").put("safety", "readonly")
            .set("target", objectMapper.createObjectNode().put("kind", "semantic")
                .put("role", "button").put("name", "查询")));
        assertThat(ActionIrValidator.validate(semantic)).anyMatch(error -> error.contains("evidenceId"));
    }

    @Test
    void everySupportedActionValidates() {
        for (String type : ActionIrValidator.SUPPORTED_TYPES) {
            ObjectNode action = objectMapper.createObjectNode().put("id", type).put("type", type).put("safety", "readonly");
            if (Set.of("navigate", "goto", "waitForUrl", "wait_for_url", "assertUrl", "assert_url").contains(type)) {
                action.set("target", objectMapper.createObjectNode().put("value", "/users"));
            } else if (!"screenshot".equals(type)) {
                action.set("target", objectMapper.createObjectNode().put("evidenceId", "ev-1"));
            }
            if (Set.of("fill", "input", "select").contains(type)) {
                action.set("value", objectMapper.createObjectNode().put("kind", "literal").put("key", "value"));
            }
            if (Set.of("assertText", "assert_text").contains(type)) action.put("expected", "text");
            if ("assert".equals(type)) {
                action.set("assertion", objectMapper.createObjectNode().put("type", "visible")
                    .set("target", objectMapper.createObjectNode().put("evidenceId", "ev-1")));
            }
            assertThat(ActionIrValidator.validate(objectMapper.createArrayNode().add(action))).as(type).isEmpty();
        }
    }

    @Test
    void structuredCompilationUsesScrollAndPaginateDslWithoutLocatorOrLabelFallback() {
        ArrayNode actions = objectMapper.createArrayNode()
            .add(objectMapper.createObjectNode().put("id", "scroll").put("type", "scrollIntoView").put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("evidenceId", "ev-list")))
            .add(objectMapper.createObjectNode().put("id", "paginate").put("type", "paginate").put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("evidenceId", "ev-next")))
            .add(objectMapper.createObjectNode().put("id", "assert").put("type", "assertVisible").put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("evidenceId", "ev-list")));
        var plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
            "point-structured-dsl", "列表", "模块", "列表页", "/list", List.of("ignored natural step"),
            "ignored expected result", "验证列表", actions, "chromium", "1440x900"
        );
        var evidence = objectMapper.createArrayNode().add(pageEvidence("point-structured-dsl", "https://example.test/list", "fp-list", List.of(
            elementWithEvidenceId("ev-list", "text=列表", "列表"),
            elementWithEvidenceId("ev-next", "text=下一页", "下一页")
        )));
        ((ObjectNode) evidence.get(0)).set("stepEvidence", objectMapper.createArrayNode()
            .add(stepEvidence("scroll", "scrollIntoView", "ev-list", "text=列表", "point-structured-dsl", "https://example.test/list", "fp-list"))
            .add(stepEvidence("paginate", "paginate", "ev-next", "text=下一页", "point-structured-dsl", "https://example.test/list", "fp-list"))
            .add(stepEvidence("assert", "assertVisible", "ev-list", "text=列表", "point-structured-dsl", "https://example.test/list", "fp-list")));

        var result = service.compileEvidenceBoundCase(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()), evidence,
            List.of(Map.of("POINT_ID", "point-structured-dsl", "SOURCE_FILE", "source.md", "SOURCE_TEXT", "列表"))
        );

        assertThat(result.success()).as(String.join(" | ", result.errors())).isTrue();
        assertThat(result.cases().get(0).steps()).contains("scrollIntoView text=列表", "paginate text=下一页");
        assertThat(result.cases().get(0).steps()).doesNotContain("ignored natural step");
    }

    @Test
    void structuredCompilationAdoptsEvidencePageAsExecutionStartUrlForSiteEntryPlans() {
        ArrayNode actions = objectMapper.createArrayNode().add(structuredAction("a-home", "assertVisible", "ev-home-title"));
        var plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
            "point-site-entry", "首页概览", "模块", "首页", "/", List.of("进入首页"), "首页可见", "验证首页概览",
            actions, "chromium", "1440x900"
        );
        ObjectNode page = pageEvidence("point-site-entry", "https://app-dev.example.test/#/home", "fp-home",
            List.of(elementWithEvidenceId("ev-home-title", "text=首页", "首页")));
        page.set("stepEvidence", objectMapper.createArrayNode().add(
            stepEvidence("a-home", "assertVisible", "ev-home-title", "text=首页", "point-site-entry", "https://app-dev.example.test/#/home", "fp-home")));
        var evidence = objectMapper.createArrayNode().add(page);

        var result = service.compileEvidenceBoundCase(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()), evidence,
            List.of(Map.of("POINT_ID", "point-site-entry", "SOURCE_FILE", "source.md", "SOURCE_TEXT", "首页")));

        assertThat(result.success()).as(String.join(" | ", result.errors())).isTrue();
        assertThat(result.cases().getFirst().startUrl()).isEqualTo("https://app-dev.example.test/#/home");
    }

    @Test
    void structuredCompilationKeepsNonSiteEntryStartUrlUntouched() {
        ArrayNode actions = objectMapper.createArrayNode().add(structuredAction("a-list", "assertVisible", "ev-list"));
        var plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
            "point-explicit-route", "列表", "模块", "列表页", "/list", List.of("查看列表"), "列表可见", "验证列表",
            actions, "chromium", "1440x900"
        );
        ObjectNode page = pageEvidence("point-explicit-route", "https://example.test/list", "fp-list",
            List.of(elementWithEvidenceId("ev-list", "text=列表", "列表")));
        page.set("stepEvidence", objectMapper.createArrayNode().add(
            stepEvidence("a-list", "assertVisible", "ev-list", "text=列表", "point-explicit-route", "https://example.test/list", "fp-list")));
        var evidence = objectMapper.createArrayNode().add(page);

        var result = service.compileEvidenceBoundCase(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()), evidence,
            List.of(Map.of("POINT_ID", "point-explicit-route", "SOURCE_FILE", "source.md", "SOURCE_TEXT", "列表")));

        assertThat(result.success()).as(String.join(" | ", result.errors())).isTrue();
        assertThat(result.cases().getFirst().startUrl()).isEqualTo("/list");
    }

    @Test
    void compilationCarriesExplicitExecutionContextIntoGeneratedCase() {
        ArrayNode contextActions = objectMapper.createArrayNode()
            .add(structuredAction("assert-list", "assertVisible", "ev-context-title"));
        var plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
            "point-context", "列表", "模块", "列表页", "https://example.test/list", List.of("查看列表"),
            "列表可见", "验证列表", contextActions, "env-firefox", "https://example.test", "firefox", "390x844", "reuse_navigation_state");
        ObjectNode contextPage = pageEvidence("point-context", "https://example.test/list", "fp-context",
            List.of(elementWithEvidenceId("ev-context-title", "text=列表", "列表")));
        contextPage.set("stepEvidence", objectMapper.createArrayNode().add(
            stepEvidence("assert-list", "assertVisible", "ev-context-title", "text=列表", "point-context", "https://example.test/list", "fp-context")));
        var evidence = objectMapper.createArrayNode().add(contextPage);

        var result = service.compileEvidenceBoundCase(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()), evidence,
            List.of(Map.of("POINT_ID", "point-context", "SOURCE_FILE", "source.md", "SOURCE_TEXT", "列表")));

        assertThat(result.success()).as(String.join(" | ", result.errors())).isTrue();
        var generated = result.cases().getFirst();
        assertThat(generated.environmentId()).isEqualTo("env-firefox");
        assertThat(generated.baseUrl()).isEqualTo("https://example.test");
        assertThat(generated.browser()).isEqualTo("firefox");
        assertThat(generated.viewport()).isEqualTo("390x844");
        assertThat(generated.authenticationMode()).isEqualTo("reuse_navigation_state");
    }

    @Test
    void structuredCompilationBindsEachActionToItsStepEvidenceResolvedLocator() {
        ArrayNode actions = objectMapper.createArrayNode()
            .add(structuredAction("click-action", "click", "ev-click"))
            .add(structuredAction("assert-action", "assertVisible", "ev-title"));
        var plan = structuredPlan("point-step-evidence", actions);
        ObjectNode page = pageEvidence("point-step-evidence", "https://example.test/list", "fp-step", List.of(
            elementWithEvidenceId("ev-click", "role=button[name=查询]", "查询"),
            elementWithEvidenceId("ev-title", "css=[data-testid=title]", "标题")));
        page.set("stepEvidence", objectMapper.createArrayNode()
            .add(stepEvidence("click-action", "click", "ev-click", "role=button[name=查询]", "point-step-evidence", "https://example.test/list", "fp-step"))
            .add(stepEvidence("assert-action", "assertVisible", "ev-title", "css=[data-testid=title]", "point-step-evidence", "https://example.test/list", "fp-step")));

        var result = service.compileEvidenceBoundCase(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()),
            objectMapper.createArrayNode().add(page), List.of(Map.of("POINT_ID", "point-step-evidence")));

        assertThat(result.success()).as(String.join(" | ", result.errors())).isTrue();
        assertThat(result.cases().get(0).steps()).contains(
            "click role=button[name=查询]", "assertVisible css=[data-testid=title]");
        assertThat(result.cases().get(0).steps()).doesNotContain("text=查询");
        assertThat(result.cases().get(0).stepTraces()).extracting(AgentWebCaseGenerationFacade.StepTrace::evidenceIds)
            .containsExactly(List.of("ev-click"), List.of("ev-title"));
    }

    @Test
    void structuredCompilationAllowsActionsToUseEvidenceFromDifferentPages() {
        ArrayNode actions = objectMapper.createArrayNode()
            .add(structuredAction("open-detail", "click", "ev-open"))
            .add(structuredAction("assert-detail", "assertVisible", "ev-detail"));
        var plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
            "point-cross-page", "详情页", "模块", "列表页", "", List.of("ignored natural step"),
            "详情页可见", "验证跨页面动作", actions, "chromium", "1440x900");
        ObjectNode startPage = pageEvidence("point-cross-page", "https://example.test/list", "fp-list", List.of(
            elementWithEvidenceId("ev-open", "role=link[name=详情]", "详情")));
        startPage.set("stepEvidence", objectMapper.createArrayNode().add(
            stepEvidence("open-detail", "click", "ev-open", "role=link[name=详情]", "point-cross-page",
                "https://example.test/list", "fp-list", "https://example.test/detail", "fp-detail")));
        ObjectNode detailPage = pageEvidence("point-cross-page", "https://example.test/detail", "fp-detail", List.of(
            elementWithEvidenceId("ev-detail", "css=[data-testid=detail]", "详情内容")));
        detailPage.set("stepEvidence", objectMapper.createArrayNode().add(
            stepEvidence("assert-detail", "assertVisible", "ev-detail", "css=[data-testid=detail]", "point-cross-page", "https://example.test/detail", "fp-detail")));

        var result = service.compileEvidenceBoundCase(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()),
            objectMapper.createArrayNode().add(startPage).add(detailPage));

        assertThat(result.success()).as(String.join(" | ", result.errors())).isTrue();
        assertThat(result.cases()).hasSize(1);
        assertThat(result.cases().get(0).pageKey()).isEqualTo("https://example.test/list");
        assertThat(result.cases().get(0).steps()).contains(
            "click role=link[name=详情]", "assertVisible css=[data-testid=detail]");
        assertThat(result.cases().get(0).stepTraces()).extracting(AgentWebCaseGenerationFacade.StepTrace::pageFingerprint)
            .containsExactly("fp-list", "fp-detail");
    }

    @Test
    void structuredCompilationSerializesTheStructuredLocatorSpecIncludingFrameAndShadowScope() {
        String scopedLocator = "frame=#inner >> css=#host >> role=button[name=\"帧内按钮\"]";
        String scopedAssertion = "frame=#inner >> css=#host >> text=帧内标题";
        ArrayNode actions = objectMapper.createArrayNode()
            .add(structuredAction("click-scoped", "click", "ev-scoped"))
            .add(structuredAction("assert-scoped", "assertVisible", "ev-scoped-title"));
        var plan = structuredPlan("point-locator-spec", actions);
        ObjectNode page = pageEvidence("point-locator-spec", "https://example.test/frames", "fp-scope", List.of(
            elementWithEvidenceId("ev-scoped", scopedLocator, "帧内按钮"),
            elementWithEvidenceId("ev-scoped-title", scopedAssertion, "帧内标题")));
        ObjectNode roleSpec = locatorSpec("role").put("role", "button").put("name", "帧内按钮")
            .put("framePath", "#inner").put("shadowPath", "#host");
        ObjectNode textSpec = locatorSpec("text").put("text", "帧内标题")
            .put("framePath", "#inner").put("shadowPath", "#host");
        page.set("stepEvidence", objectMapper.createArrayNode()
            .add(stepEvidenceWithLocatorSpec("click-scoped", "click", "ev-scoped", "point-locator-spec",
                "https://example.test/frames", "fp-scope", roleSpec))
            .add(stepEvidenceWithLocatorSpec("assert-scoped", "assertVisible", "ev-scoped-title", "point-locator-spec",
                "https://example.test/frames", "fp-scope", textSpec)));

        var result = service.compileEvidenceBoundCase(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()),
            objectMapper.createArrayNode().add(page), List.of(Map.of("POINT_ID", "point-locator-spec")));

        assertThat(result.success()).as(String.join(" | ", result.errors())).isTrue();
        assertThat(result.cases().get(0).steps()).contains("click " + scopedLocator, "assertVisible " + scopedAssertion);
    }

    @Test
    void structuredCompilationFailsClosedForAnUnserializableLocatorSpec() {
        ArrayNode actions = objectMapper.createArrayNode()
            .add(structuredAction("click-action", "click", "ev-click"));
        var plan = structuredPlan("point-bad-spec", actions);
        ObjectNode page = pageEvidence("point-bad-spec", "https://example.test/list", "fp-spec", List.of());
        ObjectNode spec = locatorSpec("magic").put("selector", "#x");
        page.set("stepEvidence", objectMapper.createArrayNode().add(
            stepEvidenceWithLocatorSpec("click-action", "click", "ev-click", "point-bad-spec",
                "https://example.test/list", "fp-spec", spec)));

        var result = service.compileEvidenceBoundCase(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()),
            objectMapper.createArrayNode().add(page));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.startsWith("invalid_locator_spec:")
            && error.contains("click-action"));
    }

    @Test
    void structuredCompilationFailsClosedWhenTheLocatorDisagreesWithItsLocatorSpec() {
        ArrayNode actions = objectMapper.createArrayNode()
            .add(structuredAction("click-action", "click", "ev-click"));
        var plan = structuredPlan("point-spec-mismatch", actions);
        ObjectNode page = pageEvidence("point-spec-mismatch", "https://example.test/list", "fp-spec", List.of(
            elementWithEvidenceId("ev-click", "text=别的元素", "别的元素")));
        ObjectNode spec = locatorSpec("role").put("role", "button").put("name", "查询");
        page.set("stepEvidence", objectMapper.createArrayNode().add(
            stepEvidenceWithLocatorSpec("click-action", "click", "ev-click", "point-spec-mismatch",
                "https://example.test/list", "fp-spec", spec, "text=别的元素")));

        var result = service.compileEvidenceBoundCase(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()),
            objectMapper.createArrayNode().add(page));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.startsWith("action_evidence_mismatch:")
            && error.contains("locatorSpec"));
    }

    @Test
    void structuredCompilationReportsActionEvidenceMismatchForWrongActionType() {
        var plan = structuredPlan("point-action-type", objectMapper.createArrayNode()
            .add(structuredAction("click-action", "click", "ev-click")));
        ObjectNode page = pageEvidence("point-action-type", "https://example.test/list", "fp-step", List.of());
        page.set("stepEvidence", objectMapper.createArrayNode().add(
            stepEvidence("click-action", "assertVisible", "ev-click", "text=查询", "point-action-type", "https://example.test/list", "fp-step")));

        var result = service.compileEvidenceBoundCase(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()),
            objectMapper.createArrayNode().add(page));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.startsWith("action_evidence_mismatch:")
            && error.contains("click-action") && error.contains("actionType"));
    }

    @Test
    void structuredCompilationReportsActionEvidenceMismatchForCrossActionEvidence() {
        ArrayNode actions = objectMapper.createArrayNode()
            .add(structuredAction("first-action", "click", "ev-first"))
            .add(structuredAction("second-action", "assertVisible", "ev-second"));
        var plan = structuredPlan("point-cross-action", actions);
        ObjectNode page = pageEvidence("point-cross-action", "https://example.test/list", "fp-step", List.of());
        page.set("stepEvidence", objectMapper.createArrayNode()
            .add(stepEvidence("first-action", "click", "ev-second", "text=查询", "point-cross-action", "https://example.test/list", "fp-step"))
            .add(stepEvidence("second-action", "assertVisible", "ev-first", "text=标题", "point-cross-action", "https://example.test/list", "fp-step")));

        var result = service.compileEvidenceBoundCase(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()),
            objectMapper.createArrayNode().add(page));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.startsWith("action_evidence_mismatch:")
            && error.contains("first-action") && error.contains("evidenceId"));
    }

    @Test
    void structuredCompilationReportsActionEvidenceMismatchForWrongSourcePoint() {
        var plan = structuredPlan("point-action-point", objectMapper.createArrayNode()
            .add(structuredAction("click-action", "click", "ev-click")));
        ObjectNode page = pageEvidence("point-action-point", "https://example.test/list", "fp-step", List.of());
        page.set("stepEvidence", objectMapper.createArrayNode().add(
            stepEvidence("click-action", "click", "ev-click", "text=查询", "other-point", "https://example.test/list", "fp-step")));

        var result = service.compileEvidenceBoundCase(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()),
            objectMapper.createArrayNode().add(page));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.startsWith("action_evidence_mismatch:")
            && error.contains("click-action") && error.contains("sourcePointId"));
    }

    @Test
    void structuredCompilationReportsMissingActionEvidenceByStableStepError() {
        var plan = structuredPlan("point-missing-step", objectMapper.createArrayNode()
            .add(structuredAction("click-action", "click", "ev-click")));
        ObjectNode page = pageEvidence("point-missing-step", "https://example.test/list", "fp-step", List.of());
        page.set("stepEvidence", objectMapper.createArrayNode());

        var result = service.compileEvidenceBoundCase(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()),
            objectMapper.createArrayNode().add(page));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.startsWith("step_evidence_missing:")
            && error.contains("click-action"));
    }

    @Test
    void structuredCompilationReportsMissingTargetEvidenceWithoutLabelOrLocatorFallback() {
        var plan = structuredPlan("point-missing-target", objectMapper.createArrayNode()
            .add(structuredAction("click-action", "click", "ev-click")));
        ObjectNode page = pageEvidence("point-missing-target", "https://example.test/list", "fp-step",
            List.of(element("ev-click", "text=查询", "查询")));
        page.set("stepEvidence", objectMapper.createArrayNode().add(
            stepEvidence("click-action", "click", "ev-click", "", "point-missing-target", "https://example.test/list", "fp-step")));

        var result = service.compileEvidenceBoundCase(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()),
            objectMapper.createArrayNode().add(page));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.startsWith("action_evidence_missing:")
            && error.contains("click-action"));
    }

    @Test
    void structuredCompilationReportsActionEvidenceMismatchForWrongPageFingerprint() {
        var plan = structuredPlan("point-mismatch", objectMapper.createArrayNode()
            .add(structuredAction("click-action", "click", "ev-click")));
        ObjectNode page = pageEvidence("point-mismatch", "https://example.test/list", "fp-page", List.of());
        page.set("stepEvidence", objectMapper.createArrayNode().add(
            stepEvidence("click-action", "click", "ev-click", "text=查询", "point-mismatch", "https://example.test/list", "fp-other")));

        var result = service.compileEvidenceBoundCase(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()),
            objectMapper.createArrayNode().add(page));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.startsWith("action_evidence_mismatch:")
            && error.contains("click-action"));
    }

    @Test
    void compileEvidenceBoundCaseProducesTraceableDslForMatchingRealUrlPageKeyAndFingerprint() {

        String startUrl = "https://example.test/users/";
        ArrayNode actions = objectMapper.createArrayNode()
            .add(structuredAction("click-search", "click", "ev-search"))
            .add(objectMapper.createObjectNode().put("id", "assert-title").put("type", "assertText").put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("evidenceId", "ev-title"))
                .put("expected", "用户管理标题可见"));
        var batch = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
                "point-1",
                "用户管理列表展示",
                "用户",
                "用户管理",
                startUrl,
                List.of("进入用户管理页面", "点击搜索按钮", "查看用户管理标题"),
                "用户管理标题可见",
                "验证用户管理列表",
                actions,
                "chromium",
                "1440x900"
            )),
            objectMapper.createObjectNode()
        );
        ObjectNode traceablePage = pageEvidence("point-1", startUrl, "fp-1",
            List.of(
                elementWithEvidenceId("ev-title", "text=用户管理", "用户管理"),
                elementWithEvidenceId("ev-search", "text=搜索", "搜索"),
                elementWithEvidenceId("ev-next", "text=下一页", "下一页")
            ));
        traceablePage.set("stepEvidence", objectMapper.createArrayNode()
            .add(stepEvidence("click-search", "click", "ev-search", "text=搜索", "point-1", startUrl, "fp-1"))
            .add(stepEvidence("assert-title", "assertText", "ev-title", "text=用户管理", "point-1", startUrl, "fp-1")));
        ArrayNode evidence = objectMapper.createArrayNode().add(traceablePage);

        Map<String, Object> point = Map.of(
            "POINT_ID", "point-1",
            "SOURCE_FILE", "req.docx",
            "SOURCE_TEXT", "用户管理列表展示"
        );
        WebCaseCompilationService.CompilationResult result = service.compileEvidenceBoundCase(batch, evidence, List.of(point));

        assertThat(result.success()).as(String.join(" | ", result.errors())).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.cases()).hasSize(1);
        List<String> steps = List.of(result.cases().get(0).steps().split("\\R"));
        assertThat(steps).containsSequence(
            "goto https://example.test/users/",
            "click text=搜索",
            "assertText text=用户管理 = 用户管理标题可见"
        );
        assertThat(result.cases().get(0).assertions()).contains("assertText text=用户管理 = 用户管理标题可见");
    }

    @Test
    void compileEvidenceBoundCaseRejectsKnowledgeOnlyEvidenceWithoutBrowserUiSource() {
        String startUrl = "https://example.test/users";
        var batch = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
                "point-api-evidence", "用户列表展示", "用户", "用户管理", startUrl,
                List.of("进入用户管理页面", "查看用户管理标题"), "用户管理标题可见", "验证用户管理列表"
            )), objectMapper.createObjectNode()
        );
        ObjectNode evidence = pageEvidence("point-api-evidence", startUrl, "fp-api", List.of(element("title", "text=用户管理", "用户管理")));
        evidence.put("source", "locatorKnowledge");

        var result = service.compileEvidenceBoundCase(batch, objectMapper.createArrayNode().add(evidence), List.of(Map.of(
            "POINT_ID", "point-api-evidence", "SOURCE_FILE", "req.docx", "SOURCE_TEXT", "用户管理列表展示"
        )));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("必须来自浏览器 UI 取证"));
    }

    @Test
    void compileEvidenceBoundCasePersistsStepLevelEvidenceTrace() {
        String startUrl = "https://example.test/users";
        ArrayNode traceActions = objectMapper.createArrayNode()
            .add(objectMapper.createObjectNode().put("id", "open-users").put("type", "navigate").put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("kind", "route").put("value", "/users")))
            .add(structuredAction("assert-title", "assertVisible", "ev-title"));
        var batch = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
                "point-trace", "用户列表展示", "用户", "用户管理", startUrl,
                List.of("进入用户管理页面", "查看用户管理标题"),
                "用户管理标题可见", "验证用户管理列表", traceActions, "chromium", "1440x900"
            )), objectMapper.createObjectNode()
        );
        ObjectNode tracePage = pageEvidence("point-trace", startUrl, "fp-trace",
            List.of(elementWithEvidenceId("ev-title", "text=用户管理", "用户管理")));
        tracePage.set("stepEvidence", objectMapper.createArrayNode()
            .add(stepEvidence("open-users", "navigate", "", "", "point-trace", startUrl, "fp-trace"))
            .add(stepEvidence("assert-title", "assertVisible", "ev-title", "text=用户管理", "point-trace", startUrl, "fp-trace")));
        ArrayNode evidence = objectMapper.createArrayNode().add(tracePage);

        var result = service.compileEvidenceBoundCase(batch, evidence, List.of(Map.of(
            "POINT_ID", "point-trace", "SOURCE_FILE", "req.docx", "SOURCE_TEXT", "用户管理列表展示"
        )));

        assertThat(result.success()).as(String.join(" | ", result.errors())).isTrue();
        var generated = result.cases().get(0);
        assertThat(generated.stepTraces()).hasSize(2);
        assertThat(generated.stepTraces().get(0).stepIndex()).isEqualTo(0);
        assertThat(generated.stepTraces().get(0).naturalStep()).isEqualTo("进入用户管理页面");
        assertThat(generated.stepTraces().get(0).dsl().get(0)).startsWith("goto ").contains("/users");
        assertThat(generated.stepTraces().get(0).pageFingerprint()).isEqualTo("fp-trace");
        assertThat(generated.stepTraces().get(1).stepIndex()).isEqualTo(1);
        assertThat(generated.stepTraces().get(1).evidenceIds()).containsExactly("ev-title");
        // 结构化编译把证据绑定在步骤 trace 上：case 级 expectedResultEvidenceIds 不再重复携带。
        assertThat(generated.expectedResultEvidenceIds()).isEmpty();
    }

    @Test
    void compileEvidenceBoundCaseSupportsRelativeStartUrlAgainstPageUrlBase() {
        String startUrl = "/users";
        String pageUrl = "https://example.test/users";
        ArrayNode actions = objectMapper.createArrayNode()
            .add(objectMapper.createObjectNode().put("id", "assert-title").put("type", "assertText").put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("evidenceId", "ev-title"))
                .put("expected", "用户管理标题可见"));
        var batch = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
                "point-1",
                "用户管理列表展示",
                "用户",
                "用户管理",
                startUrl,
                List.of("进入用户管理页面", "查看用户管理标题"),
                "用户管理标题可见",
                "验证用户管理列表",
                actions,
                "chromium",
                "1440x900"
            )),
            objectMapper.createObjectNode()
        );
        ObjectNode relativePage = pageEvidence("point-1", pageUrl, "fp-1",
            List.of(
                elementWithEvidenceId("ev-title", "text=用户管理", "用户管理"),
                elementWithEvidenceId("ev-search", "text=搜索", "搜索")
            ));
        relativePage.set("stepEvidence", objectMapper.createArrayNode().add(
            stepEvidence("assert-title", "assertText", "ev-title", "text=用户管理", "point-1", pageUrl, "fp-1")));
        ArrayNode evidence = objectMapper.createArrayNode().add(relativePage);

        Map<String, Object> point = Map.of(
            "POINT_ID", "point-1",
            "SOURCE_FILE", "req.docx",
            "SOURCE_TEXT", "用户管理列表展示"
        );
        WebCaseCompilationService.CompilationResult result = service.compileEvidenceBoundCase(batch, evidence, List.of(point));

        assertThat(result.success()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.cases().get(0).startUrl()).isEqualTo(startUrl);
        assertThat(result.cases().get(0).steps()).contains("goto /users");
    }

    @Test
    void compileEvidenceBoundCaseSupportsHashStartUrlAgainstPageUrlBase() {
        String startUrl = "#/users";
        String pageUrl = "https://example.test/app#/users";
        ArrayNode actions = objectMapper.createArrayNode()
            .add(objectMapper.createObjectNode().put("id", "assert-title").put("type", "assertText").put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("evidenceId", "ev-title"))
                .put("expected", "用户管理标题可见"));
        var batch = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
                "point-1",
                "用户管理列表展示",
                "用户",
                "用户管理",
                startUrl,
                List.of("进入用户管理页面", "查看用户管理标题"),
                "用户管理标题可见",
                "验证用户管理列表",
                actions,
                "chromium",
                "1440x900"
            )),
            objectMapper.createObjectNode()
        );
        ObjectNode hashPage = pageEvidence("point-1", pageUrl, "fp-1",
            List.of(
                elementWithEvidenceId("ev-title", "text=用户管理", "用户管理"),
                elementWithEvidenceId("ev-search", "text=搜索", "搜索")
            ));
        hashPage.set("stepEvidence", objectMapper.createArrayNode().add(
            stepEvidence("assert-title", "assertText", "ev-title", "text=用户管理", "point-1", pageUrl, "fp-1")));
        ArrayNode evidence = objectMapper.createArrayNode().add(hashPage);

        Map<String, Object> point = Map.of(
            "POINT_ID", "point-1",
            "SOURCE_FILE", "req.docx",
            "SOURCE_TEXT", "用户管理列表展示"
        );
        WebCaseCompilationService.CompilationResult result = service.compileEvidenceBoundCase(batch, evidence, List.of(point));
        if (!result.errors().isEmpty()) {
            throw new RuntimeException(result.errors().toString());
        }

        assertThat(result.success()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.cases().get(0).startUrl()).isEqualTo("https://example.test/app#/users");
    }

    @Test
    void compileEvidenceBoundCaseAcceptsEvidenceIdsWithoutIdFields() {
        String startUrl = "https://example.test/users/";
        ArrayNode actions = objectMapper.createArrayNode()
            .add(objectMapper.createObjectNode().put("id", "assert-title").put("type", "assertText").put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("evidenceId", "locator-title"))
                .put("expected", "用户管理标题可见"));
        var batch = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
                "point-1",
                "用户管理列表展示",
                "用户",
                "用户管理",
                startUrl,
                List.of("进入用户管理页面", "查看用户管理标题"),
                "用户管理标题可见",
                "验证用户管理列表",
                actions,
                "chromium",
                "1440x900"
            )),
            objectMapper.createObjectNode()
        );
        ObjectNode idsPage = pageEvidence("point-1", startUrl, "fp-1",
            List.of(
                elementWithEvidenceId("locator-title", "text=用户管理", "用户管理"),
                elementWithEvidenceId("locator-search", "text=搜索", "搜索")
            ));
        idsPage.set("stepEvidence", objectMapper.createArrayNode().add(
            stepEvidence("assert-title", "assertText", "locator-title", "text=用户管理", "point-1", startUrl, "fp-1")));
        ArrayNode evidence = objectMapper.createArrayNode().add(idsPage);

        Map<String, Object> point = Map.of(
            "POINT_ID", "point-1",
            "SOURCE_FILE", "req.docx",
            "SOURCE_TEXT", "用户管理列表展示"
        );
        WebCaseCompilationService.CompilationResult result = service.compileEvidenceBoundCase(batch, evidence, List.of(point));

        assertThat(result.success()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.cases()).hasSize(1);
        assertThat(result.cases().get(0).steps()).contains("assertText text=用户管理 = 用户管理标题可见");
        assertThat(result.cases().get(0).stepTraces().get(0).evidenceIds()).containsExactly("locator-title");
    }

    @Test
    void compileEvidenceBoundCaseRejectsDirtyEvidenceEvenWithOneValidCandidate() {
        String startUrl = "/users";
        String pageUrl = "https://example.test/users";
        var batch = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
                "point-1",
                "用户管理列表展示",
                "用户",
                "用户管理",
                startUrl,
                List.of("进入用户管理页面", "点击搜索按钮"),
                "用户管理标题可见",
                "验证用户管理列表"
            )),
            objectMapper.createObjectNode()
        );
        ArrayNode evidence = objectMapper.createArrayNode()
            .add(pageEvidence("point-1", pageUrl, "fp-1", List.of(element("title", "text=用户管理", "用户管理"))))
            .add(pageEvidence("point-1", "", pageUrl, "fp-2", List.of(element("title", "text=用户管理", "用户管理"))));

        Map<String, Object> point = Map.of(
            "POINT_ID", "point-1",
            "SOURCE_FILE", "req.docx",
            "SOURCE_TEXT", "用户管理列表展示"
        );
        WebCaseCompilationService.CompilationResult result = service.compileEvidenceBoundCase(batch, evidence, List.of(point));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("缺少 pageKey"));
    }

    @Test
    void compileEvidenceBoundCaseBlocksWhenPageKeyOrFingerprintAreMissing() {
        String startUrl = "https://example.test/users/";
        var batch = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
                "point-1",
                "用户管理列表展示",
                "用户",
                "用户管理",
                startUrl,
                List.of("进入用户管理页面", "点击搜索按钮"),
                "用户管理标题可见",
                "验证用户管理列表"
            )),
            objectMapper.createObjectNode()
        );
        Map<String, Object> point = Map.of(
            "POINT_ID", "point-1",
            "SOURCE_FILE", "req.docx",
            "SOURCE_TEXT", "用户管理列表展示"
        );

        WebCaseCompilationService.CompilationResult missingPageKey = service.compileEvidenceBoundCase(
            batch,
            objectMapper.createArrayNode().add(pageEvidence("point-1", "", startUrl, "fp-1", List.of(element("title", "text=用户管理", "用户管理")))),
            List.of(point)
        );
        assertThat(missingPageKey.success()).isFalse();
        assertThat(missingPageKey.errors()).anyMatch(error -> error.contains("缺少 pageKey"));

        WebCaseCompilationService.CompilationResult missingFingerprint = service.compileEvidenceBoundCase(
            batch,
            objectMapper.createArrayNode().add(pageEvidence("point-1", normalizedPageKey(startUrl), startUrl, "", List.of(element("title", "text=用户管理", "用户管理")))),
            List.of(point)
        );
        assertThat(missingFingerprint.success()).isFalse();
        assertThat(missingFingerprint.errors()).anyMatch(error -> error.contains("缺少 pageFingerprint"));
    }

    @Test
    void compileEvidenceBoundCaseRejectsCrossUrlOrFingerprintMismatch() {
        String startUrl = "https://example.test/users/";
        var batch = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
                "point-1",
                "用户管理列表展示",
                "用户",
                "用户管理",
                startUrl,
                List.of("进入用户管理页面", "点击搜索按钮"),
                "用户管理标题可见",
                "验证用户管理列表"
            )),
            objectMapper.createObjectNode()
        );
        Map<String, Object> point = Map.of(
            "POINT_ID", "point-1",
            "SOURCE_FILE", "req.docx",
            "SOURCE_TEXT", "用户管理列表展示"
        );

        ArrayNode crossUrlEvidence = objectMapper.createArrayNode().add(pageEvidence("point-1", "https://example.test/admin/", "fp-1",
            List.of(element("title", "text=管理后台", "管理后台"))));
        WebCaseCompilationService.CompilationResult crossUrlResult = service.compileEvidenceBoundCase(batch, crossUrlEvidence, List.of(point));
        assertThat(crossUrlResult.success()).isFalse();
        assertThat(crossUrlResult.errors()).anyMatch(error -> error.contains("pageKey 与 startUrl 不一致"));

        ArrayNode crossFingerprintEvidence = objectMapper.createArrayNode()
            .add(pageEvidence("point-1", startUrl, "fp-1", List.of(element("title", "text=用户管理", "用户管理"))))
            .add(pageEvidence("point-1", startUrl, "fp-2", List.of(element("title", "text=用户管理", "用户管理"))));
        WebCaseCompilationService.CompilationResult crossFingerprintResult = service.compileEvidenceBoundCase(batch, crossFingerprintEvidence, List.of(point));
        assertThat(crossFingerprintResult.success()).isFalse();
        assertThat(crossFingerprintResult.errors()).anyMatch(error -> error.contains("pageFingerprint 不一致"));
    }

    @Test
    void compileEvidenceBoundCaseKeepsRouteOnlyCasesOnAssertUrl() {
        String startUrl = "https://example.test/project/";
        ArrayNode actions = objectMapper.createArrayNode()
            .add(objectMapper.createObjectNode().put("id", "assert-route").put("type", "assertUrl").put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("kind", "route").put("value", startUrl)));
        var batch = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
                "point-route",
                "项目页面可访问",
                "项目",
                "项目页面",
                startUrl,
                List.of("进入项目页面"),
                "项目页面可达",
                "验证项目页面路由",
                actions,
                "chromium",
                "1440x900"
            )),
            objectMapper.createObjectNode()
        );
        Map<String, Object> point = Map.of(
            "POINT_ID", "point-route",
            "SOURCE_FILE", "req.docx",
            "SOURCE_TEXT", "项目页面可访问"
        );
        ObjectNode routePage = pageEvidence("point-route", startUrl, "fp-route", List.of());
        routePage.set("stepEvidence", objectMapper.createArrayNode().add(
            stepEvidence("assert-route", "assertUrl", "", "", "point-route", startUrl, "fp-route")));
        ArrayNode evidence = objectMapper.createArrayNode().add(routePage);

        WebCaseCompilationService.CompilationResult result = service.compileEvidenceBoundCase(batch, evidence, List.of(point));

        assertThat(result.success()).as(String.join(" | ", result.errors())).isTrue();
        assertThat(result.cases()).hasSize(1);
        assertThat(result.cases().get(0).steps()).contains("assertUrl https://example.test/project/");
        assertThat(result.cases().get(0).steps()).doesNotContain("assertVisible", "assertText", "assertHidden");
    }

    @Test
    void compileEvidenceBoundCaseRejectsContentTypeCasesWithoutBusinessLocatorEvenWhenEvidenceIsEmpty() {
        String startUrl = "https://example.test/users/";
        ArrayNode actions = objectMapper.createArrayNode()
            .add(objectMapper.createObjectNode().put("id", "assert-content").put("type", "assertText").put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("evidenceId", "ev-content"))
                .put("expected", "用户管理标题可见"));
        var batch = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
                "point-content",
                "用户管理列表展示",
                "用户",
                "用户管理",
                startUrl,
                List.of("进入用户管理页面"),
                "用户管理标题可见",
                "验证用户管理列表",
                actions,
                "chromium",
                "1440x900"
            )),
            objectMapper.createObjectNode()
        );
        Map<String, Object> point = Map.of(
            "POINT_ID", "point-content",
            "SOURCE_FILE", "req.docx",
            "SOURCE_TEXT", "用户管理列表展示"
        );
        // 证据页为空：内容断言没有可绑定的业务元素，必须 fail-closed。
        ObjectNode contentPage = pageEvidence("point-content", startUrl, "fp-content", List.of());
        contentPage.set("stepEvidence", objectMapper.createArrayNode().add(
            stepEvidence("assert-content", "assertText", "ev-content", "text=用户管理", "point-content", startUrl, "fp-content")));
        ArrayNode evidence = objectMapper.createArrayNode().add(contentPage);

        WebCaseCompilationService.CompilationResult result = service.compileEvidenceBoundCase(batch, evidence, List.of(point));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("证据") || error.contains("evidence"));
    }

    @Test
    void compileEvidenceBoundCasePreservesPageFingerprintOnGeneratedCases() {
        String startUrl = "https://example.test/users/";
        ArrayNode actions = objectMapper.createArrayNode()
            .add(objectMapper.createObjectNode().put("id", "assert-title").put("type", "assertText").put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("evidenceId", "ev-title"))
                .put("expected", "用户管理标题可见"));
        var batch = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
                "point-1",
                "用户管理列表展示",
                "用户",
                "用户管理",
                startUrl,
                List.of("进入用户管理页面", "查看用户管理标题"),
                "用户管理标题可见",
                "验证用户管理列表",
                actions,
                "chromium",
                "1440x900"
            )),
            objectMapper.createObjectNode()
        );
        ObjectNode fingerprintPage = pageEvidence("point-1", startUrl, "fp-1",
            List.of(
                elementWithEvidenceId("ev-title", "text=用户管理", "用户管理"),
                elementWithEvidenceId("ev-search", "text=搜索", "搜索")
            ));
        fingerprintPage.set("stepEvidence", objectMapper.createArrayNode().add(
            stepEvidence("assert-title", "assertText", "ev-title", "text=用户管理", "point-1", startUrl, "fp-1")));
        ArrayNode evidence = objectMapper.createArrayNode().add(fingerprintPage);
        Map<String, Object> point = Map.of(
            "POINT_ID", "point-1",
            "SOURCE_FILE", "req.docx",
            "SOURCE_TEXT", "用户管理列表展示"
        );

        WebCaseCompilationService.CompilationResult result = service.compileEvidenceBoundCase(batch, evidence, List.of(point));

        assertThat(result.success()).as(String.join(" | ", result.errors())).isTrue();
        assertThat(result.cases()).hasSize(1);
        assertThat(result.cases().get(0).pageFingerprint()).isEqualTo("fp-1");
    }

    @Test
    void structuredCompilationEmitsEnabledAndDisabledAssertions() {
        ArrayNode actions = objectMapper.createArrayNode()
            .add(structuredAction("assert-enabled", "assertEnabled", "ev-submit"))
            .add(structuredAction("assert-disabled", "assertDisabled", "ev-cancel"));
        var plan = structuredPlan("point-enabled", actions);
        ObjectNode page = pageEvidence("point-enabled", "https://example.test/list", "fp-enabled", List.of(
            elementWithEvidenceId("ev-submit", "role=button[name=提交]", "提交"),
            elementWithEvidenceId("ev-cancel", "role=button[name=取消]", "取消")));
        page.set("stepEvidence", objectMapper.createArrayNode()
            .add(stepEvidence("assert-enabled", "assertEnabled", "ev-submit", "role=button[name=提交]", "point-enabled", "https://example.test/list", "fp-enabled"))
            .add(stepEvidence("assert-disabled", "assertDisabled", "ev-cancel", "role=button[name=取消]", "point-enabled", "https://example.test/list", "fp-enabled")));

        var result = service.compileEvidenceBoundCase(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()),
            objectMapper.createArrayNode().add(page), List.of(Map.of("POINT_ID", "point-enabled")));

        assertThat(result.success()).as(String.join(" | ", result.errors())).isTrue();
        assertThat(result.cases().get(0).steps()).contains(
            "assertEnabled role=button[name=提交]", "assertDisabled role=button[name=取消]");
        assertThat(result.cases().get(0).assertions()).contains(
            "assertEnabled role=button[name=提交]", "assertDisabled role=button[name=取消]");
    }

    @Test
    void actionIrValidatorAcceptsEnabledAndDisabledShapes() {
        for (String aliasType : List.of("enabled", "assertEnabled", "assert_enabled")) {
            ArrayNode actions = objectMapper.createArrayNode();
            actions.add(objectMapper.createObjectNode().put("id", "a").put("type", "assert").put("safety", "readonly")
                .set("assertion", objectMapper.createObjectNode().put("type", aliasType)
                    .set("target", objectMapper.createObjectNode().put("evidenceId", "ev-x"))));
            assertThat(ActionIrValidator.validate(actions)).as(aliasType).isEmpty();
        }
        for (String plainType : List.of("assertDisabled", "assert_disabled")) {
            ArrayNode actions = objectMapper.createArrayNode();
            actions.add(objectMapper.createObjectNode().put("id", "a").put("type", plainType).put("safety", "readonly")
                .set("target", objectMapper.createObjectNode().put("evidenceId", "ev-x")));
            assertThat(ActionIrValidator.validate(actions)).as(plainType).isEmpty();
        }
    }

    @Test
    void validateCaseCoverageRejectsDuplicateAndMissingPointIds() {
        List<String> errors = service.validateCaseCoverage(
            List.of(
                new AgentWebCaseGenerationFacade.GeneratedWebCase("point-1", "t", "m", "web_automation", "p", "/u", "chromium", "1440x900", "", "o", "s", "a", "e", "n", "ne", "req"),
                new AgentWebCaseGenerationFacade.GeneratedWebCase("point-1", "t", "m", "web_automation", "p", "/u", "chromium", "1440x900", "", "o", "s", "a", "e", "n", "ne", "req")
            ),
            List.of("point-1", "point-2")
        );

        assertThat(errors).contains("当前批次存在重复的 sourcePointId：point-1");
        assertThat(errors).contains("当前批次测试点未生成用例：point-2");
    }

    @Test
    void compileEvidenceBoundCaseBlocksWhenEvidenceIsMissing() {
        var batch = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
                "point-1",
                "用户管理列表展示",
                "用户",
                "用户管理",
                "/users",
                List.of("进入用户管理页面", "点击搜索按钮"),
                "用户管理标题可见",
                "验证用户管理列表"
            )),
            objectMapper.createObjectNode()
        );

        WebCaseCompilationService.CompilationResult result = service.compileEvidenceBoundCase(batch, objectMapper.createArrayNode());

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("当前测试点缺少页面证据"));
    }

    private ObjectNode pageEvidence(String sourcePointId, String pageUrl, String fingerprint, List<ObjectNode> elements) {
        return pageEvidence(sourcePointId, normalizedPageKey(pageUrl), pageUrl, fingerprint, elements);
    }

    private ObjectNode pageEvidence(String sourcePointId, String pageKey, String pageUrl, String fingerprint, List<ObjectNode> elements) {
        ObjectNode page = objectMapper.createObjectNode();
        page.put("sourcePointId", sourcePointId);
        page.put("source", "browserDiscovery");
        page.put("pageKey", pageKey);
        page.put("pageUrl", pageUrl);
        page.put("pageFingerprint", fingerprint);
        page.set("elements", objectMapper.valueToTree(elements));
        return page;
    }

    private String normalizedPageKey(String pageUrl) {
        if (pageUrl == null || pageUrl.isBlank()) return "";
        try {
            java.net.URI parsed = java.net.URI.create(pageUrl.trim());
            String scheme = parsed.getScheme();
            String host = parsed.getHost();
            if (scheme == null || host == null) return "";
            String origin = scheme.toLowerCase() + "://" + host.toLowerCase();
            if (parsed.getPort() > 0) {
                origin += ":" + parsed.getPort();
            }
            String path = parsed.getRawPath();
            if (path == null || path.isBlank()) path = "/";
            while (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }
            String fragment = parsed.getRawFragment();
            String hashRoute = "";
            if (fragment != null && !fragment.isBlank()) {
                String route = fragment.split("[?#]", 2)[0].replaceFirst("^!", "").strip();
                if (!route.isBlank()) hashRoute = "#" + route;
            }
            return origin + path + hashRoute;
        } catch (Exception ignored) {
            return "";
        }
    }

    private ObjectNode element(String id, String locator, String label) {
        return objectMapper.createObjectNode()
            .put("id", id)
            .put("locator", locator)
            .put("label", label);
    }

    private ObjectNode elementWithEvidenceId(String evidenceId, String locator, String label) {
        return objectMapper.createObjectNode()
            .put("evidenceId", evidenceId)
            .put("locator", locator)
            .put("label", label);
    }

    private ObjectNode structuredAction(String id, String type, String evidenceId) {
        return objectMapper.createObjectNode().put("id", id).put("type", type).put("safety", "readonly")
            .set("target", objectMapper.createObjectNode().put("evidenceId", evidenceId));
    }

    private AgentWebCaseGenerationFacade.NaturalWebCasePlan structuredPlan(String pointId, ArrayNode actions) {
        return new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
            pointId, "结构化用例", "模块", "列表页", "/list", List.of("ignored natural step"),
            "结果可见", "验证结构化动作", actions, "chromium", "1440x900");
    }

    private ObjectNode stepEvidence(String actionId, String actionType, String evidenceId, String locator,
        String sourcePointId, String pageKey, String pageFingerprint) {
        return stepEvidence(actionId, actionType, evidenceId, locator, sourcePointId,
            pageKey, pageFingerprint, pageKey, pageFingerprint);
    }

    private ObjectNode stepEvidence(String actionId, String actionType, String evidenceId, String locator,
        String sourcePointId, String beforePageKey, String beforePageFingerprint,
        String afterPageKey, String afterPageFingerprint) {
        ObjectNode step = objectMapper.createObjectNode()
            .put("actionId", actionId).put("actionType", actionType).put("evidenceId", evidenceId)
            .put("sourcePointId", sourcePointId);
        step.set("before", objectMapper.createObjectNode().put("pageKey", beforePageKey).put("pageFingerprint", beforePageFingerprint));
        step.set("after", objectMapper.createObjectNode().put("pageKey", afterPageKey).put("pageFingerprint", afterPageFingerprint));
        step.set("resolvedTarget", locator.isBlank() ? objectMapper.createObjectNode() : objectMapper.createObjectNode().put("locator", locator));
        return step;
    }

    private ObjectNode locatorSpec(String strategy) {
        return objectMapper.createObjectNode().put("strategy", strategy);
    }

    private ObjectNode stepEvidenceWithLocatorSpec(String actionId, String actionType, String evidenceId,
        String sourcePointId, String pageKey, String pageFingerprint, ObjectNode locatorSpec) {
        return stepEvidenceWithLocatorSpec(actionId, actionType, evidenceId, sourcePointId, pageKey, pageFingerprint, locatorSpec, "");
    }

    private ObjectNode stepEvidenceWithLocatorSpec(String actionId, String actionType, String evidenceId,
        String sourcePointId, String pageKey, String pageFingerprint, ObjectNode locatorSpec, String locator) {
        ObjectNode step = objectMapper.createObjectNode()
            .put("actionId", actionId).put("actionType", actionType).put("evidenceId", evidenceId)
            .put("sourcePointId", sourcePointId);
        step.set("before", objectMapper.createObjectNode().put("pageKey", pageKey).put("pageFingerprint", pageFingerprint));
        step.set("after", objectMapper.createObjectNode().put("pageKey", pageKey).put("pageFingerprint", pageFingerprint));
        ObjectNode resolvedTarget = objectMapper.createObjectNode();
        if (!locator.isBlank()) resolvedTarget.put("locator", locator);
        resolvedTarget.set("locatorSpec", locatorSpec);
        step.set("resolvedTarget", resolvedTarget);
        return step;
    }
}
