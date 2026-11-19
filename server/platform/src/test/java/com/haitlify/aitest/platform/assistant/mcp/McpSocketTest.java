package com.haitlify.aitest.platform.assistant.mcp;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.assistant.mcp.enabled=true", "app.security.enabled=false", "server.address=127.0.0.1",
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "spring.datasource.url=jdbc:h2:mem:mcp-socket;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"})
class McpSocketTest extends McpHttpTest {
    @LocalServerPort int port;
    @Test void realHttpInitializeReadyListAndChunkedByteLimit() throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            var init = client.send(request(INIT, null, false), HttpResponse.BodyHandlers.ofString());
            assertThat(init.statusCode()).isEqualTo(200);
            assertThat(json.readTree(init.body()).path("result").path("protocolVersion").asString()).isEqualTo("2025-11-25");
            String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
            var ready = client.send(request("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", session, true), HttpResponse.BodyHandlers.ofString());
            assertThat(ready.statusCode()).isEqualTo(202); assertThat(ready.body()).isEmpty();
            var list = client.send(request(rpc("tools/list", java.util.Map.of()), session, false), HttpResponse.BodyHandlers.ofString());
            assertThat(list.statusCode()).isEqualTo(200);
            assertThat(json.readTree(list.body()).path("result").path("tools").size()).isEqualTo(3);
            var tooLarge = client.send(request(" ".repeat(65537), session, true), HttpResponse.BodyHandlers.ofString());
            assertThat(tooLarge.statusCode()).isEqualTo(413);
            assertThat(tooLarge.body()).doesNotContain(token, session);
            var noToken = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(noToken.statusCode()).isEqualTo(401);
        }
    }
    private HttpRequest request(String body, String session, boolean chunked) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .version(HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream");
        if (session != null) builder.header("Mcp-Session-Id", session);
        return builder.POST(chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
            : HttpRequest.BodyPublishers.ofString(body)).build();
    }
    @Override @Test void exactPathOnlyAndOrdinaryApiCsrfUnchanged() throws Exception {
        // Parent context checks the security-enabled API. This context intentionally disables ordinary security.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/mcp/")
            .header("Authorization", "Bearer " + token).contentType("application/json").content(INIT))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }
}
