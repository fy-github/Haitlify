package com.haitlify.aitest.platform.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.haitlify.aitest.platform.assistant.execution.ExecutionBindingService;
import com.haitlify.aitest.platform.security.SecretCipher;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * 真实 Spring 上下文 + 真实随机端口 + 真实 {@link ExecutionBindingService} 签发的 grant。
 *
 * <p>关键不变量：
 * <ul>
 *   <li>调用方只拿到 {@code /mcp-lease/{leaseId}}，请求头里永远没有真实 token（过滤器记录器断言）；</li>
 *   <li>未知/过期/撤销的 lease 一律 401，且错误体不区分它们、不泄露内部信息；</li>
 *   <li>lease 不能越过它对应的 grant（动作范围/会话归属/路径根）。</li>
 * </ul>
 * 这个上下文刻意让 {@code app.assistant.mcp.enabled=false}：lease 路径是有意独立开放的窄面，
 * 关掉整条 {@code /mcp} 不应把租约路径一起关掉。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.assistant.mcp.enabled=false",
    "app.assistant.mcp.lease.enabled=true",
    "app.assistant.mcp.lease.ttl-seconds=2",
    "app.assistant.mcp.lease.capacity=64",
    "app.security.enabled=true",
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "spring.datasource.url=jdbc:h2:mem:mcp-lease-http;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"})
@ActiveProfiles("test")
class McpLeaseHttpTest {

    private static final String INIT = """
        {"jsonrpc":"2.0","id":"init-1","method":"initialize","params":{
        "protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
        """;
    private static final String ACCEPT = "application/json, text/event-stream";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExecutionBindingService execution;
    @Autowired SecretCipher cipher;
    @Autowired ObjectMapper json;
    @Autowired McpLeaseRegistry registry;
    @Autowired com.haitlify.aitest.platform.assistant.security.capability.CapabilityTokenCodec codec;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger root;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeEach void begin() {
        RequestRecorder.EVENTS.clear();
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach void end() {
        root.detachAppender(logs);
    }

    // (a) 有效 lease 走完全相同的一段校验/业务路径，且请求头里没有真实 token
    @Test void validLeaseRunsTheSameToolPathAndNeverCarriesTheRealToken() throws Exception {
        Fixture fx = seed("Immutable requirement");
        Leased lease = lease(fx, Duration.ofSeconds(2));

        var init = post(lease.url(), INIT, null);
        assertThat(init.statusCode()).isEqualTo(200);
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertThat(session).matches("[A-Za-z0-9_-]{43}");

        assertThat(post(lease.url(), "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", session).statusCode())
            .isEqualTo(202);

        var listed = post(lease.url(), rpc("tools/list", Map.of()), session);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(json.readTree(listed.body()).path("result").path("tools").valueStream()
            .map(tool -> tool.path("name").asString()).toList())
            .containsExactly("generation_context", "draft_create", "draft_get");

        var context = post(lease.url(), rpc("tools/call", Map.of("name", "generation_context", "arguments", Map.of())), session);
        assertThat(json.readTree(context.body()).path("result").path("structuredContent").path("requirementText").asString())
            .isEqualTo("Immutable requirement");

        var spec = json.readTree("""
            {"id":"case-lease","name":"Visible content","startUrl":"https://example.com/app","steps":[
            {"id":"check","action":"assertVisible","target":"#content","value":null}]}
            """);
        var created = post(lease.url(), rpc("tools/call", Map.of("name", "draft_create", "arguments", Map.of("spec", spec))), session);
        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(json.readTree(created.body()).path("result").path("isError").asBoolean()).isFalse();
        String draftId = json.readTree(created.body()).path("result").path("structuredContent").path("draftId").asString();
        assertThat(draftId).isNotBlank();

        var fetched = post(lease.url(), rpc("tools/call", Map.of("name", "draft_get", "arguments", Map.of())), session);
        assertThat(json.readTree(fetched.body()).path("result").path("structuredContent").path("draftId").asString())
            .isEqualTo(draftId);

        // H2 落库 + provenance 绑定到这次 lease 解析出来的 binding/lease generation
        assertThat(jdbc.queryForObject("select count(*) from assistant_generation_drafts where generation_id=?",
            Integer.class, fx.generationId())).isEqualTo(1);
        Map<String, Object> provenance = jdbc.queryForMap(
            "select * from assistant_generation_drafts where generation_id=?", fx.generationId());
        assertThat(provenance.get("binding_id")).isEqualTo(fx.bindingId());
        assertThat(((Number) provenance.get("lease_generation")).longValue()).isEqualTo(1L);
        assertThat((String) provenance.get("snapshot_digest")).hasSize(64);
        assertThat((String) provenance.get("spec_digest")).hasSize(64);
        assertThat((String) provenance.get("provenance_digest")).hasSize(64);
        assertThat(jdbc.queryForObject("select state from assistant_draft where draft_id=?", String.class, draftId))
            .isEqualTo("DRAFT");

        // 请求记录器：真实 token 从未出现在任何请求头里，租约请求也不带 Authorization
        assertThat(RequestRecorder.EVENTS).isNotEmpty();
        assertThat(RequestRecorder.EVENTS).anyMatch(event -> event.uri().contains("/mcp-lease/"));
        for (RequestRecorder.Recorded event : RequestRecorder.EVENTS) {
            for (List<String> values : event.headers().values()) {
                assertThat(values).noneMatch(value -> value.contains(fx.token()));
            }
        }
        assertThat(RequestRecorder.EVENTS.stream().filter(event -> event.uri().contains("/mcp-lease/"))
            .allMatch(event -> !event.headers().containsKey("Authorization"))).isTrue();
    }

    // (b) 未知 / 过期 / 已撤销 lease 一律 401，错误体不泄露内部信息
    @Test void unknownExpiredAndRevokedLeasesAreAllOpaqueUnauthorized() throws Exception {
        Fixture fx = seed("Lease lifetime");

        var unknown = post(url("A".repeat(43)), INIT, null);
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(unknown.body()).isEqualTo("{\"error\":\"MCP request rejected\"}");
        assertThat(unknown.body()).doesNotContain(fx.token(), "Lease", "Exception", "SQL");

        String expiring = lease(fx, Duration.ofSeconds(1)).leaseId();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (registry.resolve(expiring).isPresent() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(registry.resolve(expiring)).isEmpty();
        assertThat(post(url(expiring), INIT, null).statusCode()).isEqualTo(401);

        Leased revoked = lease(fx, Duration.ofSeconds(2));
        assertThat(registry.revoke(revoked.leaseId())).isTrue();
        assertThat(post(revoked.url(), INIT, null).statusCode()).isEqualTo(401);

        // 反向也不行：lease 路径不接受真实 token，租约 URL 是这条路径唯一的凭据
        Leased live = lease(fx, Duration.ofSeconds(2));
        assertThat(request("POST", live.url(), INIT, null, Map.of("Authorization", "Bearer " + fx.token())).statusCode())
            .isEqualTo(401);
        assertThat(request("POST", live.url(), INIT, null, Map.of("Cookie", "JSESSIONID=pretend")).statusCode()).isEqualTo(401);
        assertThat(request("POST", live.url() + "?access_token=pretend", INIT, null, Map.of()).statusCode())
            .isEqualTo(401);
        assertThat(request("POST", live.url(), INIT, null, Map.of("Origin", "https://example.com")).statusCode())
            .isEqualTo(403);
    }

    // (b) 错误方法 / 伪造路径
    @Test void wrongMethodsAndForgedPathsAreRejectedWithoutTouchingTools() throws Exception {
        Fixture fx = seed("Path strictness");
        Leased live = lease(fx, Duration.ofSeconds(2));

        for (String method : List.of("GET", "PUT", "DELETE", "OPTIONS", "PATCH", "HEAD")) {
            var response = request(method, live.url(), null, null, Map.of());
            assertThat(response.statusCode()).as("method %s", method).isIn(401, 405);
        }
        var allow = request("GET", live.url(), null, null, Map.of());
        assertThat(allow.statusCode()).isIn(401, 405);

        for (String forged : List.of(
                base() + "/mcp-lease",
                base() + "/mcp-lease/",
                base() + "/mcp-lease/short",
                base() + "/mcp-lease/" + live.leaseId() + "/extra",
                base() + "/mcp-lease/" + "A".repeat(42),
                base() + "/mcp-lease/" + "A".repeat(44))) {
            assertThat(request("POST", forged, INIT, null, Map.of()).statusCode()).as("path %s", forged).isEqualTo(404);
        }
        // 矩阵参数路径由容器防火墙先拒（不是我们的语义，但同样不得进入工具面）
        assertThat(request("POST", base() + "/mcp-lease/" + live.leaseId() + ";x=1", INIT, null, Map.of()).statusCode())
            .isIn(400, 401, 404);

        // lease id 不是 grant：拿它当 bearer 打 /mcp 没有任何授权意义
        assertThat(request("POST", base() + "/mcp", INIT, null, Map.of("Authorization", "Bearer " + live.leaseId())).statusCode())
            .isEqualTo(401);
        assertThat(request("POST", base() + "/mcp-lease/" + live.leaseId(), INIT, null, Map.of()).statusCode())
            .isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from assistant_generation_drafts where generation_id=?",
            Integer.class, fx.generationId())).isZero();
    }

    // (c) 不能越权：跨会话、跨 generation、超出 grant 动作范围
    @Test void leaseCannotEscapeItsGrantOrBeReusedForAnotherGeneration() throws Exception {
        Fixture first = seed("First requirement");
        Fixture second = seed("Second requirement");
        Leased leaseA = lease(first, Duration.ofSeconds(2));
        Leased leaseB = lease(second, Duration.ofSeconds(2));

        String sessionA = post(leaseA.url(), INIT, null).headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertThat(post(leaseA.url(), "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", sessionA).statusCode())
            .isEqualTo(202);
        // A 的会话不能借 B 的租约使用，反之亦然（会话绑定的是 grant token + scope）
        assertThat(post(leaseB.url(), rpc("tools/list", Map.of()), sessionA).statusCode()).isEqualTo(404);

        String sessionB = post(leaseB.url(), INIT, null).headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertThat(post(leaseB.url(), "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", sessionB).statusCode())
            .isEqualTo(202);
        var contextB = post(leaseB.url(), rpc("tools/call", Map.of("name", "generation_context", "arguments", Map.of())), sessionB);
        assertThat(contextB.body()).contains("Second requirement").doesNotContain("First requirement", first.generationId());

        // 同一个 lease 也不能越过它那张 grant 的动作范围
        assertThat(jdbc.update("update assistant_execution_grants set actions='generation_context,draft_get' where binding_id=?",
            first.bindingId())).isEqualTo(1);
        var spec = json.readTree("""
            {"id":"case-narrow","name":"Narrow","startUrl":"https://example.com/app","steps":[
            {"id":"check","action":"assertVisible","target":"#content","value":null}]}
            """);
        assertThat(post(leaseA.url(), rpc("tools/call", Map.of("name", "draft_create", "arguments", Map.of("spec", spec))), sessionA)
            .statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from assistant_generation_drafts where generation_id=?",
            Integer.class, first.generationId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from assistant_generation_drafts where generation_id=?",
            Integer.class, second.generationId())).isZero();

        // 撤销后连已建立的会话也不再被承认
        assertThat(registry.revoke(leaseB.leaseId())).isTrue();
        assertThat(post(leaseB.url(), rpc("ping", Map.of()), sessionB).statusCode()).isEqualTo(401);
    }

    // (e) 日志 / 异常 / 响应 / 数据库里都没有 token 或 lease id 明文
    @Test void logsBodiesAndDatabaseNeverCarryTheTokenOrLeaseId() throws Exception {
        Fixture fx = seed("No leakage");
        Leased live = lease(fx, Duration.ofSeconds(2));
        String session = post(live.url(), INIT, null).headers().firstValue("Mcp-Session-Id").orElseThrow();
        String failures = String.join("\n", List.of(
            post(url("B".repeat(43)), INIT, null).body(),
            post(live.url(), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"draft_get\"}}", session).body(),
            post(live.url(), "not-json", session).body()));

        assertThat(failures).doesNotContain(fx.token(), live.leaseId(), "Exception", "SQL", "assistant_execution");
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(fx.token());
            assertThat(event.getFormattedMessage()).doesNotContain(live.leaseId());
        });
        for (String table : List.of("assistant_generations", "assistant_execution_grants",
                "assistant_execution_bindings", "assistant_generation_drafts",
                "assistant_conversations", "assistant_conversation_messages")) {
            assertThat(jdbc.queryForList("select * from " + table).toString())
                .as(table).doesNotContain(fx.token(), live.leaseId());
        }
        // 注册表是唯一的持有者：token 在内存里，库里只有它的哈希
        assertThat(jdbc.queryForObject("select count(*) from assistant_execution_grants where binding_id=? and token_hash=?",
            Integer.class, fx.bindingId(), codec.hashPresented(fx.token()))).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private record Fixture(String generationId, String bindingId, String token, Instant grantExpiry,
                           long actorId, long tenantId, long projectId) {}

    private record Leased(String leaseId, String url) {}

    private Fixture seed(String requirementText) throws Exception {
        var now = Instant.now();
        String suffix = UUID.randomUUID().toString();
        jdbc.update("insert into platform_users(username,password_hash,role,enabled,created_at,updated_at) values (?,'unused','EDITOR',true,?,?)",
            suffix, now, now);
        long actor = jdbc.queryForObject("select id from platform_users where username=?", Long.class, suffix);
        jdbc.update("insert into tenants(code,name,active,created_at,updated_at) values (?,'Lease',true,?,?)", suffix, now, now);
        long tenant = jdbc.queryForObject("select id from tenants where code=?", Long.class, suffix);
        jdbc.update("insert into projects(code,name,description,active,tenant_id,created_at,updated_at) values (?,'Lease','',true,?,?,?)",
            suffix, tenant, now, now);
        long project = jdbc.queryForObject("select id from projects where code=?", Long.class, suffix);
        jdbc.update("insert into tenant_members(tenant_id,username,created_at) values (?,?,?)", tenant, suffix, now);
        jdbc.update("insert into project_members(project_id,username,created_at) values (?,?,?)", project, suffix, now);
        String generation = UUID.randomUUID().toString();
        String snapshot = json.writeValueAsString(new java.util.TreeMap<>(Map.of("requestId", suffix, "projectId", project,
            "requirementRef", "requirement:lease", "requirementText", requirementText,
            "targetUrl", "https://example.com/app", "targetRevision", "v1")));
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(snapshot.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("insert into assistant_generations(id,actor_id,project_id,tenant_id,request_id,payload_digest,snapshot_ciphertext,state,created_at)"
            + " values (?,?,?,?,?,?,?,'WAITING_WORKER',?)", generation, actor, project, tenant, suffix, hash, cipher.encrypt(snapshot), now);
        String unit = "unit-" + suffix;
        execution.confirmUnitCleaned(execution.registerUnit(
            new ExecutionBindingService.WorkerUnit(unit, "sha256:" + "a".repeat(64), "policy-v1")));
        var binding = execution.allocate(new ExecutionBindingService.AllocationRequest(generation, unit, Duration.ofMinutes(5)));
        var grant = execution.issueGrant(new ExecutionBindingService.IssueRequest(binding.bindingId(), actor));
        return new Fixture(generation, binding.bindingId(), grant.rawToken().reveal(), grant.expiresAt(), actor, tenant, project);
    }

    private Leased lease(Fixture fixture, Duration ttl) {
        String leaseId = registry.issue(fixture.token(), fixture.grantExpiry(), ttl,
            fixture.generationId(), fixture.bindingId(), fixture.actorId());
        return new Leased(leaseId, url(leaseId));
    }

    private String base() { return "http://127.0.0.1:" + port; }

    private String url(String leaseId) { return base() + "/mcp-lease/" + leaseId; }

    private String rpc(String method, Object params) throws Exception {
        return json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params));
    }

    private HttpResponse<String> post(String url, String body, String session) throws Exception {
        return request("POST", url, body, session, Map.of());
    }

    private HttpResponse<String> request(String method, String url, String body, String session,
                                         Map<String, String> extraHeaders) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url))
            .version(HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(10));
        extraHeaders.forEach(builder::header);
        if (!extraHeaders.containsKey("Accept")) builder.header("Accept", ACCEPT);
        if (body != null) builder.header("Content-Type", "application/json");
        if (session != null) builder.header("Mcp-Session-Id", session);
        builder.method(method, body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 捕获真实请求头：用来证明调用方从未持有/发送过真实 grant token。 */
    @TestConfiguration
    static class RecorderConfiguration {
        @Bean
        FilterRegistrationBean<Filter> leaseRequestRecorder() {
            var registration = new FilterRegistrationBean<Filter>(new RequestRecorder());
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.addUrlPatterns("/*");
            return registration;
        }
    }

    static final class RequestRecorder implements Filter {
        static final List<Recorded> EVENTS = new CopyOnWriteArrayList<>();

        record Recorded(String method, String uri, Map<String, List<String>> headers) {}

        @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            var http = (HttpServletRequest) request;
            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (String name : Collections.list(http.getHeaderNames())) {
                headers.put(name, new ArrayList<>(Collections.list(http.getHeaders(name))));
            }
            EVENTS.add(new Recorded(http.getMethod(), http.getRequestURI(), headers));
            chain.doFilter(request, response);
        }
    }
}
