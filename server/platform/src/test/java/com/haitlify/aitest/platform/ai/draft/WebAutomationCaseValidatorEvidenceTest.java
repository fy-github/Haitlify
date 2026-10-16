package com.haitlify.aitest.platform.ai.draft;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class WebAutomationCaseValidatorEvidenceTest {
    @Test
    void rejectsCurrentPageUrlWhenLocatorTextExistsOnlyInEvidenceFromAnotherPage() throws Exception {
        ObjectNode devicesCase = new ObjectMapper().createObjectNode();
        devicesCase.put("caseKind", "web_automation").put("sourcePointId", "p-devices").put("title", "展示设备状态")
            .put("module", "设备").put("pageName", "设备管理").put("startUrl", "/devices").put("browser", "chromium")
            .put("viewport", "1440x900").put("objective", "验证设备列表展示状态")
            .put("assertions", "assertVisible testId=status-badge")
            .put("naturalSteps", "进入设备管理页\n查看状态列").put("naturalExpectedResult", "状态列可见")
            .put("expectedResult", "设备状态可见").put("sourceFile", "req.doc").put("sourceText", "展示设备状态")
            .put("steps", "goto /devices\nassertVisible testId=status-badge");

        var result = new WebAutomationCaseValidator().validateEvidenceBoundCase(
            devicesCase, 1, Set.of("testId=status-badge"), Set.of("/users")
        );

        assertTrue(result.errors().contains("用例 p-devices：动作 goto URL 未由当前浏览器证据验证"));
    }

    @Test
    void rejectsLocatorOutsideCurrentBrowserEvidence() throws Exception {
        ObjectNode input = new ObjectMapper().createObjectNode();
        input.put("caseKind", "web_automation").put("sourcePointId", "p-1").put("title", "登录")
            .put("module", "账户").put("pageName", "登录").put("startUrl", "/login").put("browser", "chromium")
            .put("viewport", "1440x900").put("objective", "登录").put("assertions", "assertVisible")
            .put("naturalSteps", "进入登录页\n点击登录按钮").put("naturalExpectedResult", "成功")
            .put("expectedResult", "成功").put("sourceFile", "req.doc").put("sourceText", "登录")
            .put("steps", "click css=#guessed\nassertVisible testId=submit");
        var result = new WebAutomationCaseValidator().validateEvidenceBoundCase(input, 1, Set.of("testId=submit"), Set.of("/login"));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("未由当前浏览器证据验证")));
    }

    @Test
    void acceptsEvidenceIdAsWhitelistedLocator() throws Exception {
        ObjectNode input = new ObjectMapper().createObjectNode();
        input.put("caseKind", "web_automation").put("sourcePointId", "p-1").put("title", "登录后展示用户管理列表")
            .put("module", "账户").put("pageName", "用户管理").put("startUrl", "/users").put("browser", "chromium")
            .put("viewport", "1440x900").put("objective", "查看用户管理列表").put("assertions", "assertVisible")
            .put("naturalSteps", "进入用户管理页\n查看用户管理标题").put("naturalExpectedResult", "用户管理标题可见")
            .put("expectedResult", "用户管理标题可见").put("sourceFile", "req.doc").put("sourceText", "用户管理")
            .put("steps", "click submit-action\nassertVisible testId=submit");

        var result = new WebAutomationCaseValidator().validateEvidenceBoundCase(
            input, 1, Set.of("testId=submit"), Set.of("/users"), Set.of("submit-action")
        );

        assertFalse(result.errors().stream().anyMatch(error -> error.contains("locator 未由当前浏览器证据验证")));
    }

    @Test
    void rejectsContentObjectiveWhenOnlyAssertUrlIsUsed() throws Exception {
        ObjectNode input = new ObjectMapper().createObjectNode();
        input.put("caseKind", "web_automation").put("sourcePointId", "p-2").put("title", "用户管理列表展示")
            .put("module", "用户").put("pageName", "用户管理").put("startUrl", "/users").put("browser", "chromium")
            .put("viewport", "1440x900").put("objective", "查看用户管理列表").put("assertions", "assertUrl /users")
            .put("naturalSteps", "进入用户管理页\n查看列表").put("naturalExpectedResult", "用户管理标题可见")
            .put("expectedResult", "用户管理标题可见").put("sourceFile", "req.doc").put("sourceText", "用户管理列表")
            .put("steps", "goto /users\nassertUrl /users");

        var result = new WebAutomationCaseValidator().validateEvidenceBoundCase(
            input, 1, Set.of(), Set.of("/users"), Set.of()
        );

        assertTrue(result.errors().stream().anyMatch(error -> error.contains("内容型目标不能只使用 assertUrl")));
    }

    @Test
    void acceptsContentObjectiveWhenAssertUrlAppearsAlongsideElementAssertions() throws Exception {
        ObjectNode input = new ObjectMapper().createObjectNode();
        input.put("caseKind", "web_automation").put("sourcePointId", "p-3").put("title", "用户管理列表展示")
            .put("module", "用户").put("pageName", "用户管理").put("startUrl", "/users").put("browser", "chromium")
            .put("viewport", "1440x900").put("objective", "查看用户管理列表").put("assertions", "assertVisible text=用户管理\nassertUrl /users")
            .put("naturalSteps", "进入用户管理页\n查看列表").put("naturalExpectedResult", "用户管理标题可见")
            .put("expectedResult", "用户管理标题可见").put("sourceFile", "req.doc").put("sourceText", "用户管理列表")
            .put("steps", "goto /users\nassertVisible text=用户管理\nassertUrl /users");

        var result = new WebAutomationCaseValidator().validateEvidenceBoundCase(
            input, 1, Set.of("text=用户管理"), Set.of("/users"), Set.of("text=用户管理")
        );

        assertFalse(result.errors().stream().anyMatch(error -> error.contains("内容型目标不能只使用 assertUrl")));
    }

    @Test
    void acceptsRunnerSupportedXpathAndLowercaseTestidLocators() throws Exception {
        ObjectNode input = new ObjectMapper().createObjectNode();
        input.put("caseKind", "web_automation").put("sourcePointId", "p-xpath").put("title", "登录")
            .put("module", "账户").put("pageName", "登录").put("startUrl", "/login").put("browser", "chromium")
            .put("viewport", "1440x900").put("objective", "登录").put("assertions", "assertVisible")
            .put("naturalSteps", "进入登录页\n点击登录按钮").put("naturalExpectedResult", "成功")
            .put("expectedResult", "成功").put("sourceFile", "req.doc").put("sourceText", "登录")
            .put("steps", "click //button[normalize-space()=\"登录\"]\nassertVisible testid=submit");
        var result = new WebAutomationCaseValidator().validateAndNormalize(input, 1);
        assertFalse(result.errors().stream().anyMatch(error -> error.contains("缺少合法 locator")));
    }
}
