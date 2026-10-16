package com.haitlify.aitest.platform.ai.draft;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class WebAutomationCaseValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebAutomationCaseValidator validator = new WebAutomationCaseValidator();

    @Test
    void acceptsExecutableDslAndNormalizesBrowserAndViewport() {
        ObjectNode testCase = validCase();
        testCase.put("browser", "Google Chrome");
        testCase.put("viewport", "1440 * 900");

        WebAutomationValidationResult result = validator.validateAndNormalize(testCase, 1);

        assertThat(result.errors()).isEmpty();
        assertThat(result.normalizedCase().path("browser").asText()).isEqualTo("chrome");
        assertThat(result.normalizedCase().path("viewport").asText()).isEqualTo("1440x900");
    }

    @Test
    void rejectsNumberedProseMissingTargetsAndUnsupportedActions() {
        ObjectNode testCase = validCase();
        testCase.put("steps", "1. 登录系统并进入用户中心\nclick\nmanual 人工确认\nassertVisible text=用户管理");

        WebAutomationValidationResult result = validator.validateAndNormalize(testCase, 1);

        assertThat(result.errors()).anyMatch(error -> error.contains("编号自然语言"));
        assertThat(result.errors()).anyMatch(error -> error.contains("click") && error.contains("目标"));
        assertThat(result.errors()).anyMatch(error -> error.contains("manual") && error.contains("不支持"));
    }

    @Test
    void rejectsNaturalLanguageLocatorsAndCasesWithoutAssertions() {
        ObjectNode testCase = validCase();
        testCase.put("steps", "goto /login\nclick 点击登录按钮");

        WebAutomationValidationResult result = validator.validateAndNormalize(testCase, 1);

        assertThat(result.errors()).anyMatch(error -> error.contains("合法 locator"));
        assertThat(result.errors()).anyMatch(error -> error.contains("至少一个断言"));
    }

    @Test
    void keepsRelativeRouteTargetsValidWhileRejectingProse() {
        ObjectNode relative = validCase();
        relative.put("steps", "goto /login\nassertUrl detail");
        ObjectNode prose = validCase();
        prose.put("steps", "goto /login\nassertUrl 设备详情页");

        // 与编译器/执行器同一条口径：同源相对路径是合法地址；自然语言描述不是。
        assertThat(validator.validateAndNormalize(relative, 1).errors())
            .noneMatch(error -> error.contains("合法 URL 目标"));
        assertThat(validator.validateAndNormalize(prose, 1).errors())
            .anyMatch(error -> error.contains("合法 URL 目标"));
    }

    private ObjectNode validCase() {
        return (ObjectNode) objectMapper.createObjectNode()
            .put("sourcePointId", "point-login")
            .put("title", "登录后进入用户管理")
            .put("module", "登录")
            .put("caseKind", "web_automation")
            .put("pageName", "用户管理")
            .put("startUrl", "/login")
            .put("browser", "chromium")
            .put("viewport", "1440x900")
            .put("precondition", "环境已配置登录账号")
            .put("objective", "验证用户管理可访问")
            .put("naturalSteps", "进入登录页\n提交正确账号密码\n查看用户管理入口")
            .put("naturalExpectedResult", "用户管理标题可见")
            .put("steps", "goto /login\nclick role=button[name=登录]\nwaitForVisible text=用户管理\nassertVisible text=用户管理")
            .put("assertions", "assertVisible text=用户管理")
            .put("expectedResult", "用户管理标题可见")
            .put("sourceFile", "requirements.md")
            .put("sourceText", "用户登录后可进入用户管理");
    }
}
