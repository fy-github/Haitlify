package com.haitlify.aitest.platform.assistant.explore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.haitlify.aitest.platform.webautomation.WebAutomationProperties;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

/**
 * 受控探索的 DNS / 跳转边界（不依赖真实 DNS）：
 * 「公网域名解析到内网」和「302 跳到内网」都必须拒绝。
 */
class AssistantExplorationNetworkGuardTest {

    private static HttpServer stub;
    private static volatile String responsePageUrl;

    @BeforeAll
    static void start() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/page-locator-discovery", exchange -> {
            String body = "{\"pageUrl\":\"" + responsePageUrl + "\",\"status\":\"ok\",\"elements\":[]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        stub.start();
    }

    @AfterAll
    static void stop() {
        stub.stop(0);
    }

    private static AssistantExplorationService service(AssistantExplorationService.HostResolver resolver) {
        WebAutomationProperties properties = new WebAutomationProperties(
            new WebAutomationProperties.Runner(true, "http://127.0.0.1:" + stub.getAddress().getPort(), 5000));
        return new AssistantExplorationService(properties, new ObjectMapper(), false, true, resolver);
    }

    private static AssistantExplorationService.HostResolver publicResolver() {
        return host -> new InetAddress[] {InetAddress.getByAddress(host, new byte[] {(byte) 93, 1, 1, 1})};
    }

    @Test
    void publicHostResolvingToLoopbackIsRejected() {
        AssistantExplorationService.HostResolver rebinding = host -> new InetAddress[] {
            InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1})
        };
        assertThatThrownBy(() -> service(rebinding).discover("https://looks-public.example/page"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("解析到本机/私网");
    }

    @Test
    void unresolvableHostFailsClosed() {
        AssistantExplorationService.HostResolver failing = host -> {
            throw new UnknownHostException(host);
        };
        assertThatThrownBy(() -> service(failing).discover("https://no-such-host.example/page"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("无法解析主机");
    }

    @Test
    void redirectToPrivateAddressIsRejectedAfterTheFact() {
        responsePageUrl = "http://127.0.0.1:19324/internal";
        try {
            assertThatThrownBy(() -> service(publicResolver()).discover("https://public.example/page"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("跳转后指向本机/私网");
        } finally {
            responsePageUrl = "https://public.example/page";
        }
    }

    @Test
    void publicHostAndPublicFinalUrlPass() {
        responsePageUrl = "https://public.example/page";
        AssistantExplorationService.ExplorationResult result =
            service(publicResolver()).discover("https://public.example/page");
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.pageUrl()).isEqualTo("https://public.example/page");
    }
}
