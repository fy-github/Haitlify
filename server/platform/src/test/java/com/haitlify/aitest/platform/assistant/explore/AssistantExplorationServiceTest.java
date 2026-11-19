package com.haitlify.aitest.platform.assistant.explore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** 受控探索：只读采集、字段裁剪、URL 白名单；Runner 用本地桩。 */
@SpringBootTest(properties = "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(
    properties = "app.assistant.exploration.resolve-dns=false")
class AssistantExplorationServiceTest {

    private static final HttpServer STUB = startStub();
    private static volatile boolean rejectNext;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.web-automation.runner.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/page-locator-discovery", AssistantExplorationServiceTest::handle);
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("桩 Runner 启动失败", ex);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String body = rejectNext
            ? "{\"message\":\"blocked\"}"
            : "{\"pageUrl\":\"https://example.test/icc\",\"status\":\"ok\",\"elements\":["
                + "{\"tag\":\"input\",\"role\":\"textbox\",\"label\":\"搜索\",\"selector\":\"#menu-search\",\"text\":\"\"},"
                + "{\"tag\":\"button\",\"role\":\"button\",\"label\":\"威胁情报\",\"selector\":\"#threat-menu\",\"text\":\"威胁情报\"}]}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(rejectNext ? 422 : 200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Autowired
    private AssistantExplorationService exploration;

    @Test
    void mapsRunnerElementsAndKeepsOrder() {
        rejectNext = false;
        AssistantExplorationService.ExplorationResult result = exploration.discover("https://example.test/icc");

        assertThat(result.pageUrl()).isEqualTo("https://example.test/icc");
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.elements()).extracting(AssistantExplorationService.Element::selector)
            .containsExactly("#menu-search", "#threat-menu");
        assertThat(result.elements().get(1).label()).isEqualTo("威胁情报");
    }

    @Test
    void rejectsNonHttpUrlBeforeCallingRunner() {
        assertThatThrownBy(() -> exploration.discover("file:///etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("http(s)");
    }

    @Test
    void runnerRejectionFailsClosed() {
        rejectNext = true;
        try {
            assertThatThrownBy(() -> exploration.discover("https://example.test/icc"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝");
        } finally {
            rejectNext = false;
        }
    }

    @Test
    void rejectsLoopbackAndPrivateTargetsByDefault() {
        assertThatThrownBy(() -> exploration.discover("http://127.0.0.1:19324/fixtures/x.html"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("私网");
        assertThatThrownBy(() -> exploration.discover("http://169.254.169.254/latest/meta-data/"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("私网");
        assertThatThrownBy(() -> exploration.discover("http://localhost:8080/"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("私网");
        // IPv4-mapped IPv6 的展开写法与压缩写法等价，必须同样拦。
        assertThatThrownBy(() -> exploration.discover("http://[0:0:0:0:0:ffff:127.0.0.1]/"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("私网");
        assertThatThrownBy(() -> exploration.discover("http://[::ffff:169.254.169.254]/latest/meta-data/"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("私网");
        assertThatThrownBy(() -> exploration.discover("http://0177.0.0.1/"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("私网");
    }
}
