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

import com.haitlify.aitest.platform.assistant.hermes.HermesTcpGateway;
import com.haitlify.aitest.platform.assistant.hermes.HermesTuiGateway;

/**
 * 与 {@link GatewayReloaderWiringTest} 对称的另一半：{@code app.assistant.hermes.transport} <b>未设置</b>
 * （即 stdio 默认）且给了 {@code gateway-config-path} 时，控制面三件套必须照样注册——
 * 真实链路走 stdio 子进程，没有 reload 落点就意味着这条路永远配不起来。
 *
 * <p>钉的四件事：reloader 是真实实现（复用 {@link HermesTuiGateway} 的请求路径）、控制面与配置器都在、
 * stdio 网关存活但<b>没有被启动</b>（懒连接，未配置 command 时 {@code available()} false）、
 * 且这个上下文里没有 TCP 网关 Bean（transport 分支确实是二选一）。
 */
@SpringBootTest(properties = {
    "app.assistant.conversation.enabled=true",
    "app.security.enabled=false",
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "spring.datasource.url=jdbc:h2:mem:stdio-gateway-reloader-wiring;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles("test")
class StdioGatewayReloaderWiringTest {

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
    void wiresTheRealReloaderBehindTheRealControlOnStdio() {
        assertThat(context.getBean(GatewayReloader.class)).isInstanceOf(TcpGatewayReloader.class);
        assertThat(context.getBean(GatewayMcpControl.class)).isInstanceOf(ConfigFileGatewayMcpControl.class);
        assertThat(context.getBean(GatewayMcpConfigurator.class)).isInstanceOf(GatewayMcpLeaseConfigurator.class);
    }

    @Test
    void usesTheStdioGatewayAndSpawnsNothingAtStartup() {
        assertThat(context.getBeansOfType(HermesTcpGateway.class)).as("stdio 下不该有 TCP 网关").isEmpty();
        HermesTuiGateway gateway = context.getBean(HermesTuiGateway.class);
        assertThat(gateway.available())
            .as("未配置 app.assistant.hermes.command 时 stdio 网关不可用，且启动不建子进程")
            .isFalse();
        assertThat(gateway.inFlightRequests()).isZero();
    }

    private static Path temporaryConfig() {
        try {
            Path directory = Files.createTempDirectory("stdio-gateway-reloader-wiring");
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
