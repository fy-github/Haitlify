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
 * 真实 reloader 的装配：{@code transport=tcp} + {@code gateway-config-path} 同时成立时，上下文里出现的
 * {@link GatewayReloader} 必须是 {@link TcpGatewayReloader}（复用 {@code HermesTcpGateway} 的连接与帧约定），
 * 而不是某个什么都不做的空实现。
 *
 * <p>没有 {@code gateway-config-path} 时既不注册 reloader 也不注册控制面，由
 * {@link ConfigFileGatewayMcpControlDefaultOffTest} 钉住。上下文启动<b>不会</b>连接网关：TCP 通道是懒连接的，
 * 本用例也不触发 reload。路径指向临时目录，不碰任何真实 profile。
 */
@SpringBootTest(properties = {
    "app.assistant.conversation.enabled=true",
    "app.assistant.hermes.transport=tcp",
    "app.security.enabled=false",
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "spring.datasource.url=jdbc:h2:mem:gateway-reloader-wiring;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles("test")
class GatewayReloaderWiringTest {

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
    void wiresTheRealTcpReloaderBehindTheRealControl() {
        assertThat(context.getBean(GatewayReloader.class)).isInstanceOf(TcpGatewayReloader.class);
        assertThat(context.getBean(GatewayMcpControl.class)).isInstanceOf(ConfigFileGatewayMcpControl.class);
        assertThat(context.getBean(GatewayMcpConfigurator.class)).isInstanceOf(GatewayMcpLeaseConfigurator.class);
    }

    private static Path temporaryConfig() {
        try {
            Path directory = Files.createTempDirectory("gateway-reloader-wiring");
            Path file = directory.resolve("config.yaml");
            Files.writeString(file,
                "mcp_servers:\n  haitlify:\n    url: 'https://placeholder.internal/mcp'\n",
                StandardCharsets.UTF_8);
            return file;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
