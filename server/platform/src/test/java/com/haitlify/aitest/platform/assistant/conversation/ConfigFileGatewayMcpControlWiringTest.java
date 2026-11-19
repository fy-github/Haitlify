package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 真实网关控制面的装配：只有显式给出 {@code gateway-config-path} 才注册。
 *
 * 这条用例证明「开关打开时上下文里确实有一个会写文件 + reload 的落点」，并且配置器仍是唯一被接受的
 * {@link GatewayMcpLeaseConfigurator}。路径指向临时目录；本用例不触发 reload，也不启动 Hermes。
 * 默认关闭（没有该属性 → 不注册）由 {@link ConfigFileGatewayMcpControlDefaultOffTest} 钉住。
 *
 * <p>本上下文不设 {@code app.assistant.hermes.transport}（即 stdio 默认），reload 落点由真实的
 * {@link TcpGatewayReloader}（接 {@code HermesTuiGateway}）提供，装配细节在 {@link StdioGatewayReloaderWiringTest}。
 * 这里不再手注册桩 reloader：同类型的两个 Bean 会让控制面的 {@code getIfAvailable()} 变成
 * 「不唯一」，把「装配是否成立」测成 Spring 的歧义异常。
 */
@SpringBootTest(properties = {
    "app.assistant.conversation.enabled=true",
    "app.assistant.conversation.mcp-lease.configure-gateway=true",
    "app.security.enabled=false",
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "spring.datasource.url=jdbc:h2:mem:gateway-mcp-wiring-on;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles("test")
class ConfigFileGatewayMcpControlWiringTest {

    private static final Path CONFIG = temporaryConfig();

    @DynamicPropertySource
    static void gatewayConfigPath(DynamicPropertyRegistry registry) {
        registry.add("app.assistant.conversation.mcp-lease.gateway-config-path", () -> CONFIG.toString());
    }

    @Autowired
    private ApplicationContext context;

    @AfterAll
    static void removeTemporaryConfig() throws IOException {
        Files.deleteIfExists(CONFIG);
        Files.deleteIfExists(CONFIG.getParent());
    }

    @Test
    void registersTheRealControlAndTheLeaseConfiguratorWhenThePathIsConfigured() {
        assertThat(context.getBean(GatewayMcpControl.class))
            .isInstanceOf(ConfigFileGatewayMcpControl.class);
        assertThat(context.getBean(GatewayMcpConfigurator.class))
            .isInstanceOf(GatewayMcpLeaseConfigurator.class);
    }

    private static Path temporaryConfig() {
        try {
            Path directory = Files.createTempDirectory("gateway-mcp-wiring");
            Path file = directory.resolve("config.yaml");
            Files.writeString(file, "mcp_servers:\n  haitlify:\n    url: 'https://placeholder.internal/mcp'\n",
                StandardCharsets.UTF_8);
            return file;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

}
