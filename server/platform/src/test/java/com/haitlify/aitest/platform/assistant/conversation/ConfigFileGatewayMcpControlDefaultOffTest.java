package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * 默认关闭：没有 {@code gateway-config-path} 时，上下文里<b>没有</b>网关控制面、也没有配置器。
 *
 * <p>这是「不碰任何真实网关配置文件」的装配级证据：不是靠一条 if 判断跳过，而是 Bean 根本不存在，
 * 服务层一次都不会调用。开关打开但没有落点的情况另有 wiring 用例保证启动即失败。
 */
@SpringBootTest(properties = {
    "app.assistant.conversation.enabled=true",
    "app.security.enabled=false",
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "spring.datasource.url=jdbc:h2:mem:gateway-mcp-wiring-off;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles("test")
class ConfigFileGatewayMcpControlDefaultOffTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void registersNoGatewayControlOrConfiguratorByDefault() {
        assertThat(context.getBeansOfType(GatewayMcpControl.class)).isEmpty();
        assertThat(context.getBeansOfType(GatewayMcpConfigurator.class)).isEmpty();
        assertThat(context.getBeansOfType(GatewayReloader.class)).isEmpty();
    }
}
