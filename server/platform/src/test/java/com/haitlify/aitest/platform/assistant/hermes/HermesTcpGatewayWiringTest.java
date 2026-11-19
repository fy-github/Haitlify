package com.haitlify.aitest.platform.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * transport=tcp 时的 Spring 装配回归：网关是一个 bean；没有处理器 bean 时构造函数必须落到
 * fail-closed 默认，而不是因为「没注入」启动失败或退化成放行。不连真实 Hermes、不调模型。
 */
@SpringBootTest(properties = {
    "app.security.enabled=false",
    "app.assistant.hermes.transport=tcp",
    "app.assistant.hermes.host=127.0.0.1",
    "app.assistant.hermes.port=9",
    "spring.datasource.url=jdbc:h2:mem:hermes-wiring;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles("test")
class HermesTcpGatewayWiringTest {

    @Autowired
    HermesGateway gateway;

    @Autowired
    ApplicationContext context;

    @Test
    void tcpGatewayWiresWithoutAnyHandlerBeanAndStaysFailClosed() {
        assertThat(gateway).isInstanceOf(HermesTcpGateway.class);
        assertThat(context.getBeanNamesForType(HermesServerRequestHandler.class)).isEmpty();
        assertThat(gateway.available()).isFalse();
    }
}
