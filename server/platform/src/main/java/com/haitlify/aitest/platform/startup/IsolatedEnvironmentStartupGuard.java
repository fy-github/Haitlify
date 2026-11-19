package com.haitlify.aitest.platform.startup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * 在 Spring 环境准备好之后、任何自动配置（含 Flyway 迁移）之前校验隔离环境契约，
 * 不满足时抛异常终止启动（计划 §7.1：启动保护必须落成代码，不只是说明）。
 *
 * <p>只有 {@code AiTestPlatformApplication.main} 会注册本监听器：测试上下文自行构建 SpringApplication，
 * 因此测试套件不受影响，而所有受支持的启动路径（jar、spring-boot:run、容器 ENTRYPOINT）都会被拦截。
 *
 * <p>校验对象是**实际生效**的数据源目标。固定属性清单不够：Spring 宽松绑定允许同一属性有多种写法
 * （{@code SPRING_DATASOURCE_HIKARI_JDBC_URL} / {@code SPRING_DATASOURCE_HIKARI_JDBCURL} …），
 * 因此除列举候选属性外，还会枚举环境里**所有**名字像数据源/Flyway、值像 JDBC 地址的属性一并校验，
 * 避免只堵住已知写法（第一轮独立复核就是用未列举的覆盖属性绕过的）。
 */
public final class IsolatedEnvironmentStartupGuard
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    /** 名字像数据源目标（含 Flyway 自己的 URL）的属性名。 */
    private static final Pattern DATASOURCE_LIKE_KEY = Pattern.compile("(datasource|jdbc|flyway)", Pattern.CASE_INSENSITIVE);
    /** 值像 JDBC 地址。 */
    private static final Pattern JDBC_LIKE_VALUE = Pattern.compile("^jdbc:", Pattern.CASE_INSENSITIVE);

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        verify(event.getEnvironment());
    }

    static void verify(ConfigurableEnvironment environment) {
        String preflightHash = environment.getProperty(IsolatedEnvironmentGuard.PREFLIGHT_HASH_PROPERTY);
        List<String> violations = new ArrayList<>(IsolatedEnvironmentGuard.violations(
                environment.getProperty(IsolatedEnvironmentGuard.ENV_ID_PROPERTY),
                preflightHash,
                datasourceCandidates(environment)));
        violations.addAll(IsolatedEnvironmentGuard.preflightResultViolations(
                environment.getProperty(IsolatedEnvironmentGuard.PREFLIGHT_RESULT_PROPERTY), preflightHash));
        if (!violations.isEmpty()) {
            throw new IllegalStateException("隔离环境启动保护拒绝启动：" + String.join("；", violations)
                    + "。受支持的启动入口是 scripts/preflight/start-isolated-stack.sh（见 DEPLOYMENT.md）。");
        }
    }

    static Map<String, String> datasourceCandidates(ConfigurableEnvironment environment) {
        Map<String, String> candidates = IsolatedEnvironmentGuard.newCandidateMap();
        for (String property : IsolatedEnvironmentGuard.DATASOURCE_URL_PROPERTIES) {
            String value = environment.getProperty(property);
            if (value != null && !value.isBlank()) {
                candidates.put(property, value);
            }
        }
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (candidates.containsKey(name) || !DATASOURCE_LIKE_KEY.matcher(name).find()) {
                    continue;
                }
                Object value = source.getProperty(name);
                if (value instanceof String text && JDBC_LIKE_VALUE.matcher(text.strip()).find()) {
                    candidates.put(name, text);
                }
            }
        }
        return candidates;
    }
}
