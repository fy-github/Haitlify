package com.haitlify.aitest.platform.startup;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * 在数据源 bean 初始化完成、被任何使用者（含 Flyway）拿到之前校验它的实际连接目标。
 *
 * <p>只由 {@code AiTestPlatformApplication.main} 通过 ApplicationContextInitializer 注册：
 * 测试上下文不经过 main()，因此测试套件不受影响。校验失败直接抛异常终止启动，
 * 此时尚未建立任何数据库连接。
 */
public final class IsolatedDataSourcePostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof DataSource dataSource)) {
            return bean;
        }
        List<String> violations = IsolatedDataSourceGuard.violations(dataSource, beanName);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("隔离环境启动保护拒绝启动（数据源实际目标）："
                    + String.join("；", violations)
                    + "。受支持的启动入口是 scripts/preflight/start-isolated-stack.sh（见 DEPLOYMENT.md）。");
        }
        return bean;
    }
}
