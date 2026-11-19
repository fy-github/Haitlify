package com.haitlify.aitest.platform.assistant.mcp;

import static org.assertj.core.api.Assertions.*;
import com.haitlify.aitest.platform.assistant.execution.ExecutionBindingService;
import com.haitlify.aitest.platform.assistant.generation.tool.GenerationDraftTools;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Uses the real H2 grant fixture; direct filter calls let the clock advance without sleeping. */
class McpSessionLimitsTest extends McpHttpTest {
    @Autowired GenerationDraftTools draftTools;
    @Autowired com.haitlify.aitest.platform.assistant.security.capability.CapabilityTokenCodec codec;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    ExecutionBindingService executionAt(Clock clock) {
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory(Map.of("clock", clock));
        return new ExecutionBindingService(jdbc, codec, transactionManager, beans.getBeanProvider(Clock.class));
    }

    @Test void sessionSurvivesSixtySecondsUntilRealGrantExpiry() throws Exception {
        var clock = new MutableClock();
        Instant expiry = clock.now.plusSeconds(180);
        assertThat(jdbc.update("update assistant_execution_grants set expires_at=? where binding_id=?", expiry, binding.bindingId())).isEqualTo(1);
        var endpoint = new McpEndpoint(true, executionAt(clock), draftTools, clock, 1);
        var init = invoke(endpoint, INIT, null);
        assertThat(init.getStatus()).isEqualTo(200);
        String session = init.getHeader("Mcp-Session-Id");
        clock.now = clock.now.plusSeconds(61);
        assertThat(invoke(endpoint, rpc("ping", Map.of()), session).getStatus()).isEqualTo(200);
        clock.now = expiry.minusNanos(1);
        assertThat(invoke(endpoint, rpc("ping", Map.of()), session).getStatus()).isEqualTo(200);
        clock.now = expiry;
        assertThat(invoke(endpoint, rpc("ping", Map.of()), session).getStatus()).isEqualTo(401);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"assistant_execution_grants", "assistant_execution_bindings"})
    void originalAuthorizationDeadlineCannotSlideEvenIfRowsAreLaterExtended(String table) throws Exception {
        var clock = new MutableClock();
        Instant expiry = clock.now.plusSeconds(180);
        setExpiry(table, expiry);
        var endpoint = new McpEndpoint(true, executionAt(clock), draftTools, clock, 1);
        String session = invoke(endpoint, INIT, null).getHeader("Mcp-Session-Id");
        assertThat(session).isNotBlank();
        assertThat(invoke(endpoint, INIT, null).getStatus()).isEqualTo(503);
        setExpiry(table, expiry.plusSeconds(60));
        clock.now = expiry.minusNanos(1);
        assertThat(invoke(endpoint, rpc("ping", Map.of()), session).getStatus()).isEqualTo(200);
        clock.now = expiry;
        assertThat(invoke(endpoint, rpc("ping", Map.of()), session).getStatus()).isEqualTo(404);
        assertThat(invoke(endpoint, INIT, null).getStatus()).isEqualTo(200);
    }

    @Test void absoluteFiveMinuteCapNeverSlidesAndExpiredEntriesReleaseCapacity() throws Exception {
        var clock = new MutableClock();
        Instant start = clock.now;
        // Deliberately longer persisted rows isolate the independent server ceiling.
        setExpiry("assistant_execution_grants", start.plusSeconds(600));
        setExpiry("assistant_execution_bindings", start.plusSeconds(600));
        var endpoint = new McpEndpoint(true, executionAt(clock), draftTools, clock, 1);
        String session = invoke(endpoint, INIT, null).getHeader("Mcp-Session-Id");
        assertThat(session).isNotBlank();
        assertThat(invoke(endpoint, INIT, null).getStatus()).isEqualTo(503);
        clock.now = start.plusSeconds(299);
        assertThat(invoke(endpoint, rpc("ping", Map.of()), session).getStatus()).isEqualTo(200);
        clock.now = start.plusSeconds(300);
        var expiredGet = new MockHttpServletRequest("GET", "/mcp");
        expiredGet.addHeader("Authorization", "Bearer " + token);
        expiredGet.addHeader("Mcp-Session-Id", session);
        var response = new MockHttpServletResponse();
        endpoint.doFilter(expiredGet, response, (a,b) -> { throw new AssertionError(); });
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(invoke(endpoint, INIT, null).getStatus()).isEqualTo(200);
        assertThat(invoke(endpoint, rpc("ping", Map.of()), session).getStatus()).isEqualTo(404);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"assistant_execution_grants", "assistant_execution_bindings"})
    void shortenedExpiryIsRevalidatedForEveryRequest(String table) throws Exception {
        var clock = new MutableClock();
        var endpoint = new McpEndpoint(true, executionAt(clock), draftTools, clock, 1);
        String session = invoke(endpoint, INIT, null).getHeader("Mcp-Session-Id");
        assertThat(session).isNotBlank();
        setExpiry(table, clock.now.plusSeconds(90));
        clock.now = clock.now.plusSeconds(90);
        for (String method : List.of("ping", "tools/list", "tools/call"))
            assertThat(invoke(endpoint, rpc(method, Map.of()), session).getStatus()).isEqualTo(401);
        assertThat(invoke(endpoint, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", session).getStatus()).isEqualTo(401);
    }

    @Test void revokedGrantIsRevalidatedEvenBeforeSessionDeadline() throws Exception {
        var clock = new MutableClock();
        var endpoint = new McpEndpoint(true, executionAt(clock), draftTools, clock, 1);
        String session = invoke(endpoint, INIT, null).getHeader("Mcp-Session-Id");
        assertThat(session).isNotBlank();
        clock.now = clock.now.plusSeconds(61);
        assertThat(invoke(endpoint, rpc("ping", Map.of()), session).getStatus()).isEqualTo(200);
        executionAt(clock).revoke(binding.bindingId());
        for (String method : List.of("ping", "tools/list", "tools/call"))
            assertThat(invoke(endpoint, rpc(method, Map.of()), session).getStatus()).isEqualTo(401);
    }

    @Test void anotherTokenForSameScopeCannotBorrowSession() throws Exception {
        var clock = new MutableClock();
        var endpoint = new McpEndpoint(true, executionAt(clock), draftTools, clock, 1);
        String session = invoke(endpoint, INIT, null).getHeader("Mcp-Session-Id");
        assertThat(session).isNotBlank();
        String original = token;
        // Simulate token replacement on the single grant row, preserving its exact scope.
        var replacement = codec.issue();
        token = replacement.rawToken().reveal();
        assertThat(jdbc.update("update assistant_execution_grants set token_hash=? where binding_id=?", replacement.hash(), binding.bindingId())).isEqualTo(1);
        assertThat(invoke(endpoint, rpc("ping", Map.of()), session).getStatus()).isEqualTo(404);
        assertThat(jdbc.update("update assistant_execution_grants set token_hash=? where binding_id=?", codec.hashPresented(original), binding.bindingId())).isEqualTo(1);
        token = original;
        clock.now = clock.now.plusSeconds(61);
        assertThat(invoke(endpoint, rpc("ping", Map.of()), session).getStatus()).isEqualTo(200);
    }

    void setExpiry(String table, Instant expiry) {
        String key = table.equals("assistant_execution_grants") ? "binding_id" : "id";
        assertThat(jdbc.update("update " + table + " set expires_at=? where " + key + "=?", expiry, binding.bindingId())).isEqualTo(1);
    }

    @Test void disabledEndpointRejectsValidGrantAndNeverCallsTools() throws Exception {
        var endpoint = new McpEndpoint(false, execution, draftTools);
        assertThat(invoke(endpoint, INIT, null).getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from assistant_generation_drafts where generation_id=?", Integer.class, generation)).isZero();
    }
    @Test void readFailuresStayOpaque() throws Exception {
        var endpoint = new McpEndpoint(true, execution, draftTools);
        var request = new MockHttpServletRequest("POST", "/mcp") {
            @Override public jakarta.servlet.ServletInputStream getInputStream() {
                return new jakarta.servlet.ServletInputStream() {
                    public boolean isFinished() { return false; }
                    public boolean isReady() { return true; }
                    public void setReadListener(jakarta.servlet.ReadListener listener) {}
                    public int read() throws java.io.IOException { throw new java.io.IOException("RAW-SECRET-BODY"); }
                };
            }
        };
        request.addHeader("Authorization", "Bearer " + token);
        request.addHeader("Accept", "application/json,text/event-stream");
        request.setContentType("application/json");
        var response = new MockHttpServletResponse();
        endpoint.doFilter(request, response, (a,b) -> { throw new AssertionError(); });
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).doesNotContain("RAW-SECRET-BODY", token);
    }
    @Test void toolRateIsBoundedPerSession() throws Exception {
        var endpoint = new McpEndpoint(true, execution, draftTools);
        String session = invoke(endpoint, INIT, null).getHeader("Mcp-Session-Id");
        assertThat(invoke(endpoint, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", session).getStatus()).isEqualTo(202);
        for (int i = 0; i < 120; i++) assertThat(invoke(endpoint, rpc("tools/call", Map.of("name", "generation_context", "arguments", Map.of())), session).getStatus()).isEqualTo(200);
        var rejected = invoke(endpoint, rpc("tools/call", Map.of("name", "generation_context", "arguments", Map.of())), session);
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getContentAsString()).doesNotContain(token, session);
    }
    MockHttpServletResponse invoke(McpEndpoint endpoint, String body, String session) throws Exception {
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer " + token);
        request.addHeader("Accept", "application/json, text/event-stream");
        request.setContentType("application/json");
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (session != null) request.addHeader("Mcp-Session-Id", session);
        var response = new MockHttpServletResponse();
        endpoint.doFilter(request, response, (a,b) -> { throw new AssertionError("Must not continue to MVC"); });
        return response;
    }
    static final class MutableClock extends Clock {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
