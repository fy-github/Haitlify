package com.haitlify.aitest.platform.assistant.dispatch;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Runner 的受控登录运行时：凭据只从环境变量读，不让它们进入草稿、编译产物、报告或日志。
 *
 * <p>Runner 会在每个浏览器分组前注入 setup-login-* 步骤（见 web-runner 的 buildLoginSetupSteps），
 * 因此这里只负责把 runtime.login 拼进派发 payload。业务脚本的 digest 仍只覆盖用例本身，
 * 登录配置变化不会改变「确认过的业务断言」。
 */
@Component
public class AssistantRunnerLogin {

    private final boolean enabled;
    private final String loginUrl;
    private final String username;
    private final String password;
    private final String usernameLocator;
    private final String passwordLocator;
    private final String submitLocator;
    private final String successType;
    private final String successTarget;
    private final ObjectMapper objectMapper;

    public AssistantRunnerLogin(@Value("${app.assistant.runner.login.enabled:false}") boolean enabled,
                                @Value("${app.assistant.runner.login.login-url:}") String loginUrl,
                                @Value("${app.assistant.runner.login.username:}") String username,
                                @Value("${app.assistant.runner.login.password:}") String password,
                                @Value("${app.assistant.runner.login.username-locator:#account}") String usernameLocator,
                                @Value("${app.assistant.runner.login.password-locator:#password}") String passwordLocator,
                                @Value("${app.assistant.runner.login.submit-locator:button}") String submitLocator,
                                @Value("${app.assistant.runner.login.success-type:url}") String successType,
                                @Value("${app.assistant.runner.login.success-target:}") String successTarget,
                                ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.loginUrl = loginUrl;
        this.username = username;
        this.password = password;
        this.usernameLocator = usernameLocator;
        this.passwordLocator = passwordLocator;
        this.submitLocator = submitLocator;
        this.successType = successType;
        this.successTarget = successTarget;
        this.objectMapper = objectMapper;
    }

    /** 开启时：把 runtime.login 合进 payload；配置不全直接拒绝派发（不静默跳过登录）。 */
    public String withLoginRuntime(String payloadJson) {
        if (!enabled) {
            return payloadJson;
        }
        if (isBlank(loginUrl) || isBlank(username) || isBlank(password) || isBlank(successTarget)) {
            throw new IllegalStateException(
                "受控登录已开启，但 login-url/username/password/success-target 未配置完整");
        }
        Map<String, Object> payload = objectMapper.readValue(payloadJson, Map.class);
        Map<String, Object> login = new LinkedHashMap<>();
        login.put("enabled", true);
        login.put("loginUrl", loginUrl);
        login.put("username", username);
        login.put("password", password);
        login.put("usernameLocator", usernameLocator);
        login.put("passwordLocator", passwordLocator);
        login.put("submitLocator", submitLocator);
        login.put("successType", successType);
        login.put("successTarget", successTarget);
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("login", login);
        payload.put("runtime", runtime);
        return objectMapper.writeValueAsString(payload);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
