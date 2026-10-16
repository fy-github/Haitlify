package com.haitlify.aitest.platform.ai.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * T10 bullet 3 合同：DSL 动作白名单与断言底线——任意 JS/shell/SQL 不可能是候选动作，
 * 危险 scheme 与 AI 生成的 locator 不得替代证据；动作表一旦越界，整批 IR 以
 * invalid_action_ir 失败关闭（不可交付），而不是挑出能跑的动作继续。
 */
class AiCaseDeliveryContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebAutomationCaseValidator validator = new WebAutomationCaseValidator();
    private final WebCaseCompilationService service = new WebCaseCompilationService(objectMapper, validator);

    private List<String> validate(ObjectNode... actions) {
        ArrayNode array = objectMapper.createArrayNode();
        for (ObjectNode action : actions) {
            array.add(action);
        }
        return ActionIrValidator.validate(array);
    }

    private ObjectNode evidenceAction(String id, String type) {
        return objectMapper.createObjectNode().put("id", id).put("type", type).put("safety", "readonly")
            .set("target", objectMapper.createObjectNode().put("evidenceId", "ev-" + id));
    }

    @Test
    void scriptShellAndSqlCandidatesAreNotWhitelistedActions() {
        for (String type : List.of("script", "executeScript", "eval", "sql", "shell", "bash", "powershell")) {
            ObjectNode action = evidenceAction("danger", type);
            List<String> errors = validate(action);
            assertThat(errors)
                .as("type " + type + " must be rejected")
                .anyMatch(error -> error.contains("type 不支持"));
        }
    }

    @Test
    void javascriptAndSiblingSchemesAreRejectedInEveryValueSlot() {
        ObjectNode dangerousRoute = objectMapper.createObjectNode()
            .put("id", "nav").put("type", "navigate").put("safety", "readonly")
            .set("target", objectMapper.createObjectNode().put("route", "javascript:alert(1)"));
        assertThat(validate(dangerousRoute)).anyMatch(error -> error.contains("target 不是可执行的地址"));

        ObjectNode dataUrl = objectMapper.createObjectNode()
            .put("id", "nav2").put("type", "navigate").put("safety", "readonly")
            .set("target", objectMapper.createObjectNode().put("route", "data:text/html,<script>1</script>"));
        assertThat(validate(dataUrl)).anyMatch(error -> error.contains("target 不是可执行的地址"));

        ObjectNode vbscript = objectMapper.createObjectNode()
            .put("id", "nav3").put("type", "goto").put("safety", "readonly")
            .set("target", objectMapper.createObjectNode().put("route", "vbscript:msgbox(1)"));
        assertThat(validate(vbscript)).anyMatch(error -> error.contains("target 不是可执行的地址"));
    }

    @Test
    void aiSuppliedLocatorsCannotReplaceEvidenceIds() {
        ObjectNode selectorTarget = evidenceAction("click-1", "click");
        ((ObjectNode) selectorTarget.get("target")).put("selector", "#submit");
        assertThat(validate(selectorTarget)).anyMatch(error -> error.contains("禁止 AI locator"));

        ObjectNode cssTarget = evidenceAction("click-2", "click");
        ((ObjectNode) cssTarget.get("target")).put("css", ".login > button");
        assertThat(validate(cssTarget)).anyMatch(error -> error.contains("禁止 AI locator"));

        ObjectNode xpathTarget = evidenceAction("click-3", "click");
        ((ObjectNode) xpathTarget.get("target")).put("xpath", "//button[@id='ok']");
        assertThat(validate(xpathTarget)).anyMatch(error -> error.contains("禁止 AI locator"));

        ObjectNode bareTarget = objectMapper.createObjectNode()
            .put("id", "click-4").put("type", "click").put("safety", "readonly")
            .set("target", objectMapper.createObjectNode().put("locator", "text=登录"));
        assertThat(validate(bareTarget)).anyMatch(error -> error.contains("evidenceId"));
    }

    @Test
    void routeTargetsOnElementActionsAreRejected() {
        ObjectNode click = objectMapper.createObjectNode()
            .put("id", "click-route").put("type", "click").put("safety", "readonly")
            .set("target", objectMapper.createObjectNode().put("kind", "route").put("route", "/users"));
        assertThat(validate(click)).anyMatch(error -> error.contains("不接受 route 目标"));
    }

    @Test
    void outOfWhitelistActionMakesTheWholeBatchInvalidInsteadOfDroppingIt() {
        ArrayNode actions = objectMapper.createArrayNode();
        actions.add(evidenceAction("fill-ok", "fill").set("value",
            objectMapper.createObjectNode().put("kind", "literal").put("value", "admin")));
        actions.add(objectMapper.createObjectNode().put("id", "teleport").put("type", "teleport")
            .put("safety", "readonly"));
        var plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
            "point-contract", "标题", "用户", "用户管理", "/users",
            List.of("操作"), "预期", "验证越界动作拒绝", actions, "", "");

        List<WebCaseCompilationService.ActionIntentIR> intents = service.buildActionIntentIR(
            new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(List.of(plan), objectMapper.createObjectNode()));

        // 整批失败关闭：不得只挑 fill-ok 继续交付。
        assertThat(intents).isNotEmpty();
        assertThat(intents).allSatisfy(intent ->
            assertThat(intent.intent()).isEqualTo("invalid_action_ir"));
        assertThat(intents.get(0).expectedState()).contains("teleport");
    }
}
